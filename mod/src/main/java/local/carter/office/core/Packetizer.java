package local.carter.office.core;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Splits one JPEG tile into datagrams of at most {@link #MAX_DATAGRAM} bytes and reassembles them.
 *
 * <p>Reassembly is keyed by (streamId, tx, ty) and only the newest frameSeq per key is kept: a newer frameSeq
 * replaces an incomplete older one, older or already-completed frameSeqs are ignored, and incomplete entries
 * older than 2 s are dropped. {@link #accept} expects datagrams with the client token already stripped.
 * Thread-safe.
 */
public final class Packetizer {
    public static final int MAX_DATAGRAM = 1200;
    /** Client-to-server packets append this many token bytes after the payload (outside the header). */
    public static final int TOKEN_BYTES = 8;

    private static final long EXPIRY_NANOS = TimeUnit.SECONDS.toNanos(2);

    /** A fully reassembled tile payload. */
    public record Assembled(int streamId, int frameSeq, int tx, int ty, byte[] jpeg) {}

    private record Key(int streamId, int tx, int ty) {}

    private static final class State {
        int seq;
        boolean done;

        State(int seq) {
            this.seq = seq;
        }
    }

    private static final class Entry {
        final byte[][] chunks;
        final long firstNanos;
        int count;

        Entry(int parts, long firstNanos) {
            this.chunks = new byte[parts][];
            this.firstNanos = firstNanos;
        }
    }

    private final LongSupplier nanoTime;
    private final Map<Key, State> newest = new HashMap<>();
    /** Insertion order == firstNanos order, so expiry only scans from the head. */
    private final LinkedHashMap<Key, Entry> incomplete = new LinkedHashMap<>();

    public Packetizer() {
        this(System::nanoTime);
    }

    public Packetizer(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    /** Split without a token (server-to-client). */
    public List<byte[]> split(StreamHeader h, byte[] jpeg) {
        return split(h, jpeg, null);
    }

    /**
     * Splits {@code jpeg} into datagrams; {@code h.part()}/{@code h.parts()} are overwritten. If {@code token}
     * is non-null (must be {@link #TOKEN_BYTES} long) it is appended to every datagram.
     */
    public List<byte[]> split(StreamHeader h, byte[] jpeg, byte[] token) {
        if (token != null && token.length != TOKEN_BYTES)
            throw new IllegalArgumentException("token must be " + TOKEN_BYTES + " bytes");
        int tokenLen = token == null ? 0 : TOKEN_BYTES;
        int room = MAX_DATAGRAM - StreamHeader.HEADER_BYTES - tokenLen;
        int parts = Math.max(1, (jpeg.length + room - 1) / room);
        if (parts > 255) throw new IllegalArgumentException("payload of " + jpeg.length + " bytes needs " + parts + " parts (max 255)");
        List<byte[]> out = new ArrayList<>(parts);
        for (int i = 0; i < parts; i++) {
            int off = i * room;
            int len = Math.min(room, jpeg.length - off);
            StreamHeader ph = new StreamHeader(h.type(), h.streamId(), h.frameSeq(), h.tx(), h.ty(), i, parts);
            ByteBuffer b = ByteBuffer.allocate(StreamHeader.HEADER_BYTES + len + tokenLen);
            ph.writeTo(b);
            b.put(jpeg, off, len);
            if (token != null) b.put(token);
            out.add(b.array());
        }
        return out;
    }

    /** Feeds one datagram (token already stripped); returns the tile when its last part arrives. */
    public synchronized Optional<Assembled> accept(byte[] datagram) {
        Optional<StreamHeader> parsed = StreamHeader.decode(datagram);
        if (parsed.isEmpty()) return Optional.empty();
        StreamHeader h = parsed.get();
        if (h.type() != StreamHeader.TYPE_TILE) return Optional.empty();
        if (h.parts() < 1 || h.part() >= h.parts()) return Optional.empty();

        long now = nanoTime.getAsLong();
        purge(now);

        Key key = new Key(h.streamId(), h.tx(), h.ty());
        State st = newest.get(key);
        if (st == null) {
            st = new State(h.frameSeq());
            newest.put(key, st);
        } else {
            int d = h.frameSeq() - st.seq; // wrap-safe ordering
            if (d < 0 || (d == 0 && st.done)) return Optional.empty();
            if (d > 0) {
                st.seq = h.frameSeq();
                st.done = false;
                incomplete.remove(key);
            }
        }

        Entry e = incomplete.get(key);
        if (e == null) {
            e = new Entry(h.parts(), now);
            incomplete.put(key, e);
        } else if (e.chunks.length != h.parts()) {
            return Optional.empty();
        }
        if (e.chunks[h.part()] != null) return Optional.empty(); // duplicate part
        int headerEnd = StreamHeader.HEADER_BYTES;
        e.chunks[h.part()] = java.util.Arrays.copyOfRange(datagram, headerEnd, datagram.length);
        e.count++;
        if (e.count < e.chunks.length) return Optional.empty();

        incomplete.remove(key);
        st.done = true;
        int total = 0;
        for (byte[] c : e.chunks) total += c.length;
        byte[] jpeg = new byte[total];
        int pos = 0;
        for (byte[] c : e.chunks) {
            System.arraycopy(c, 0, jpeg, pos, c.length);
            pos += c.length;
        }
        return Optional.of(new Assembled(h.streamId(), h.frameSeq(), h.tx(), h.ty(), jpeg));
    }

    /** Number of tiles currently waiting for more parts (after dropping expired ones). */
    public synchronized int pendingCount() {
        purge(nanoTime.getAsLong());
        return incomplete.size();
    }

    private void purge(long now) {
        Iterator<Entry> it = incomplete.values().iterator();
        while (it.hasNext()) {
            if (now - it.next().firstNanos > EXPIRY_NANOS) it.remove();
            else break;
        }
    }
}
