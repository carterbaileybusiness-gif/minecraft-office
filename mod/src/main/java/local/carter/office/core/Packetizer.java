package local.carter.office.core;

import java.nio.ByteBuffer;
import java.util.ArrayList;
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
 * older than 2 s are dropped. Memory is bounded: per-key sequence state expires after 10 s idle and is capped
 * (oldest evicted first), and incomplete entries are capped by count and buffered bytes (oldest first). Call
 * {@link #forget(int)} / {@link #clear()} when a stream ends or the connection drops.
 * {@link #accept} expects datagrams with the client token already stripped. Thread-safe.
 */
public final class Packetizer {
    public static final int MAX_DATAGRAM = 1200;
    /** Client-to-server packets append this many token bytes after the payload (outside the header). */
    public static final int TOKEN_BYTES = 8;

    private static final long EXPIRY_NANOS = TimeUnit.SECONDS.toNanos(2);
    /** Per-key sequence state is forgotten after this long without an accepted datagram. */
    private static final long IDLE_NANOS = TimeUnit.SECONDS.toNanos(10);

    public static final int DEFAULT_MAX_KEYS = 16384;
    public static final int DEFAULT_MAX_ENTRIES = 2048;
    public static final long DEFAULT_MAX_BUFFERED_BYTES = 8L * 1024 * 1024;
    /** Fixed bookkeeping charged per incomplete entry on top of its parts array and payload bytes. */
    private static final int ENTRY_OVERHEAD = 64;

    /** A fully reassembled tile payload. */
    public record Assembled(int streamId, int frameSeq, int tx, int ty, byte[] jpeg) {}

    private record Key(int streamId, int tx, int ty) {}

    private static final class State {
        int seq;
        boolean done;
        long touched;

        State(int seq, long touched) {
            this.seq = seq;
            this.touched = touched;
        }
    }

    private static final class Entry {
        final byte[][] chunks;
        final long firstNanos;
        int count;
        long cost;

        Entry(int parts, long firstNanos) {
            this.chunks = new byte[parts][];
            this.firstNanos = firstNanos;
            this.cost = ENTRY_OVERHEAD + 8L * parts;
        }
    }

    private final LongSupplier nanoTime;
    private final int maxKeys;
    private final int maxEntries;
    private final long maxBufferedBytes;
    /** Last accepted frameSeq per key; LRU order by last accepted datagram, so idle expiry/eviction scans the head. */
    private final LinkedHashMap<Key, State> newest = new LinkedHashMap<>();
    /** Insertion order == firstNanos order, so expiry/eviction only scans from the head. */
    private final LinkedHashMap<Key, Entry> incomplete = new LinkedHashMap<>();
    private long bufferedBytes;

    public Packetizer() {
        this(System::nanoTime);
    }

    public Packetizer(LongSupplier nanoTime) {
        this(nanoTime, DEFAULT_MAX_KEYS, DEFAULT_MAX_ENTRIES, DEFAULT_MAX_BUFFERED_BYTES);
    }

    Packetizer(LongSupplier nanoTime, int maxKeys, int maxEntries, long maxBufferedBytes) {
        this.nanoTime = nanoTime;
        this.maxKeys = maxKeys;
        this.maxEntries = maxEntries;
        this.maxBufferedBytes = maxBufferedBytes;
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
        boolean restart = false;
        if (st != null) {
            int d = h.frameSeq() - st.seq; // wrap-safe ordering
            // rejected datagrams do not refresh the idle clock, so a forged far-ahead seq expires
            if (d < 0 || (d == 0 && st.done)) return Optional.empty();
            Entry cur = incomplete.get(key);
            if (d == 0 && cur != null && cur.chunks.length != h.parts()) return Optional.empty();
            restart = d > 0;
        }
        if (st == null) {
            st = new State(h.frameSeq(), now);
        } else if (restart) {
            st.seq = h.frameSeq();
            st.done = false;
            dropEntry(key);
        }
        st.touched = now;
        newest.remove(key);
        newest.put(key, st);

        Entry e = incomplete.get(key);
        if (e == null) {
            e = new Entry(h.parts(), now);
            incomplete.put(key, e);
            bufferedBytes += e.cost;
        }
        if (e.chunks[h.part()] != null) return Optional.empty(); // duplicate part
        byte[] payload = java.util.Arrays.copyOfRange(datagram, StreamHeader.HEADER_BYTES, datagram.length);
        e.chunks[h.part()] = payload;
        e.count++;
        e.cost += payload.length;
        bufferedBytes += payload.length;

        if (e.count < e.chunks.length) {
            evictOverCaps();
            // evictOverCaps drops oldest first and may in the extreme drop this entry; that is a lost tile, not an error
            if (newest.size() > maxKeys) evictKeys();
            return Optional.empty();
        }

        dropEntry(key);
        st.done = true;
        if (newest.size() > maxKeys) evictKeys();
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

    /** Number of (streamId, tx, ty) keys with remembered sequence state. */
    public synchronized int trackedKeys() {
        purge(nanoTime.getAsLong());
        return newest.size();
    }

    /** Approximate bytes held by incomplete entries (payload plus bookkeeping). */
    public synchronized long bufferedBytes() {
        return bufferedBytes;
    }

    /** Drops all state for one stream (call on stream end / disconnect). */
    public synchronized void forget(int streamId) {
        newest.keySet().removeIf(k -> k.streamId() == streamId);
        Iterator<Map.Entry<Key, Entry>> it = incomplete.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Entry> me = it.next();
            if (me.getKey().streamId() == streamId) {
                bufferedBytes -= me.getValue().cost;
                it.remove();
            }
        }
    }

    /** Drops all state. */
    public synchronized void clear() {
        newest.clear();
        incomplete.clear();
        bufferedBytes = 0;
    }

    private void dropEntry(Key key) {
        Entry old = incomplete.remove(key);
        if (old != null) bufferedBytes -= old.cost;
    }

    private void purge(long now) {
        Iterator<Map.Entry<Key, Entry>> it = incomplete.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Entry> me = it.next();
            if (now - me.getValue().firstNanos <= EXPIRY_NANOS) break;
            bufferedBytes -= me.getValue().cost;
            it.remove();
        }
        Iterator<State> sit = newest.values().iterator();
        while (sit.hasNext()) {
            if (now - sit.next().touched <= IDLE_NANOS) break;
            sit.remove();
        }
    }

    private void evictOverCaps() {
        Iterator<Map.Entry<Key, Entry>> it = incomplete.entrySet().iterator();
        while ((incomplete.size() > maxEntries || bufferedBytes > maxBufferedBytes) && it.hasNext()) {
            bufferedBytes -= it.next().getValue().cost;
            it.remove();
        }
    }

    private void evictKeys() {
        Iterator<Map.Entry<Key, State>> it = newest.entrySet().iterator();
        while (newest.size() > maxKeys && it.hasNext()) {
            Key k = it.next().getKey();
            it.remove();
            dropEntry(k);
        }
    }
}
