package local.carter.office.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PacketizerTest {
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final Packetizer p = new Packetizer(clock::get);

    private static byte[] bytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static StreamHeader tile(int stream, int seq, int tx, int ty) {
        return new StreamHeader(StreamHeader.TYPE_TILE, stream, seq, tx, ty, 0, 1);
    }

    private Optional<Packetizer.Assembled> feedAll(List<byte[]> ds) {
        Optional<Packetizer.Assembled> out = Optional.empty();
        for (byte[] d : ds) {
            Optional<Packetizer.Assembled> r = p.accept(d);
            if (r.isPresent()) out = r;
        }
        return out;
    }

    @Test
    void headerIsBigEndianAndRoundTrips() {
        StreamHeader h = new StreamHeader(StreamHeader.TYPE_TILE, 0x01020304, 0x0A0B0C0D, 0x1122, 0x3344, 5, 9);
        byte[] b = h.encode();
        assertEquals(StreamHeader.HEADER_BYTES, b.length);
        assertEquals(8, Packetizer.TOKEN_BYTES);
        byte[] expected = {
            0x4F, 0x46, 1, 3, 1, 2, 3, 4, 0x0A, 0x0B, 0x0C, 0x0D, 0x11, 0x22, 0x33, 0x44, 5, 9
        };
        assertArrayEquals(expected, b);
        assertEquals(h, StreamHeader.decode(b).orElseThrow());
    }

    @Test
    void headerDecodeRejectsGarbage() {
        byte[] good = tile(1, 1, 0, 0).encode();
        assertTrue(StreamHeader.decode(new byte[3]).isEmpty());
        byte[] badMagic = good.clone();
        badMagic[0] = 0;
        assertTrue(StreamHeader.decode(badMagic).isEmpty());
        byte[] badVer = good.clone();
        badVer[2] = 9;
        assertTrue(StreamHeader.decode(badVer).isEmpty());
    }

    @Test
    void fiveThousandBytesSplitsIntoFiveDatagramsUnder1200() {
        byte[] jpeg = bytes(5000, 1);
        List<byte[]> ds = p.split(tile(7, 1, 3, 4), jpeg);
        assertEquals(5, ds.size());
        for (byte[] d : ds) assertTrue(d.length <= 1200);
        Packetizer.Assembled a = feedAll(ds).orElseThrow();
        assertArrayEquals(jpeg, a.jpeg());
        assertEquals(7, a.streamId());
        assertEquals(1, a.frameSeq());
        assertEquals(3, a.tx());
        assertEquals(4, a.ty());
    }

    @Test
    void splitStampsPartAndParts() {
        List<byte[]> ds = p.split(tile(7, 1, 3, 4), bytes(5000, 1));
        for (int i = 0; i < ds.size(); i++) {
            StreamHeader h = StreamHeader.decode(ds.get(i)).orElseThrow();
            assertEquals(i, h.part());
            assertEquals(5, h.parts());
        }
    }

    @Test
    void tokenIsAppendedAndStillFitsIn1200() {
        byte[] token = bytes(Packetizer.TOKEN_BYTES, 9);
        byte[] jpeg = bytes(5000, 2);
        List<byte[]> ds = p.split(tile(1, 1, 0, 0), jpeg, token);
        for (byte[] d : ds) {
            assertTrue(d.length <= 1200);
            byte[] tail = java.util.Arrays.copyOfRange(d, d.length - 8, d.length);
            assertArrayEquals(token, tail);
        }
        // the relay strips the token before accept
        List<byte[]> stripped = new ArrayList<>();
        for (byte[] d : ds) stripped.add(java.util.Arrays.copyOf(d, d.length - 8));
        assertArrayEquals(jpeg, feedAll(stripped).orElseThrow().jpeg());
    }

    @Test
    void wrongTokenLengthRejected() {
        assertThrows(IllegalArgumentException.class, () -> p.split(tile(1, 1, 0, 0), new byte[10], new byte[3]));
    }

    @Test
    void smallAndEmptyPayloadsAreOneDatagram() {
        assertEquals(1, p.split(tile(1, 1, 0, 0), new byte[10]).size());
        List<byte[]> empty = p.split(tile(1, 2, 0, 0), new byte[0]);
        assertEquals(1, empty.size());
        assertEquals(0, feedAll(empty).orElseThrow().jpeg().length);
    }

    @Test
    void exactBoundarySizes() {
        int max = Packetizer.MAX_DATAGRAM - StreamHeader.HEADER_BYTES;
        assertEquals(1, p.split(tile(1, 1, 0, 0), new byte[max]).size());
        assertEquals(2, p.split(tile(1, 1, 0, 0), new byte[max + 1]).size());
    }

    @Test
    void tooLargeForUbytePartsIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> p.split(tile(1, 1, 0, 0), new byte[400_000]));
    }

    @Test
    void reassemblesByteExactInAnyOrder() {
        for (long seed = 0; seed < 20; seed++) {
            Packetizer q = new Packetizer(clock::get);
            byte[] jpeg = bytes(5000, seed);
            List<byte[]> ds = new ArrayList<>(q.split(tile(2, (int) seed + 1, 1, 1), jpeg));
            Collections.shuffle(ds, new Random(seed));
            Optional<Packetizer.Assembled> out = Optional.empty();
            for (int i = 0; i < ds.size(); i++) {
                Optional<Packetizer.Assembled> r = q.accept(ds.get(i));
                if (i < ds.size() - 1) assertTrue(r.isEmpty());
                else out = r;
            }
            assertArrayEquals(jpeg, out.orElseThrow().jpeg());
        }
    }

    @Test
    void missingPartEmitsNothing() {
        List<byte[]> ds = p.split(tile(1, 1, 0, 0), bytes(5000, 3));
        ds.remove(2);
        assertTrue(feedAll(ds).isEmpty());
    }

    @Test
    void duplicatePartsAreHarmless() {
        byte[] jpeg = bytes(5000, 4);
        List<byte[]> ds = p.split(tile(1, 1, 0, 0), jpeg);
        assertTrue(p.accept(ds.get(0)).isEmpty());
        assertTrue(p.accept(ds.get(0)).isEmpty());
        assertTrue(p.accept(ds.get(1)).isEmpty());
        assertTrue(p.accept(ds.get(1)).isEmpty());
        assertTrue(p.accept(ds.get(2)).isEmpty());
        assertTrue(p.accept(ds.get(3)).isEmpty());
        assertArrayEquals(jpeg, p.accept(ds.get(4)).orElseThrow().jpeg());
        // repeats after completion do not re-emit
        for (byte[] d : ds) assertTrue(p.accept(d).isEmpty());
    }

    @Test
    void olderFrameSeqIgnoredAfterNewer() {
        List<byte[]> older = p.split(tile(1, 5, 2, 2), bytes(3000, 5));
        byte[] newJpeg = bytes(3000, 6);
        List<byte[]> newer = p.split(tile(1, 6, 2, 2), newJpeg);
        assertArrayEquals(newJpeg, feedAll(newer).orElseThrow().jpeg());
        assertTrue(feedAll(older).isEmpty());
    }

    @Test
    void newerFrameSeqReplacesIncompleteOlder() {
        List<byte[]> older = new ArrayList<>(p.split(tile(1, 5, 2, 2), bytes(3000, 5)));
        byte[] newJpeg = bytes(3000, 6);
        p.accept(older.get(0));
        p.accept(older.get(1));
        assertArrayEquals(newJpeg, feedAll(p.split(tile(1, 6, 2, 2), newJpeg)).orElseThrow().jpeg());
        // the straggler from frame 5 can no longer complete anything
        assertTrue(p.accept(older.get(2)).isEmpty());
        assertEquals(0, p.pendingCount());
    }

    @Test
    void differentTilesAndStreamsAreIndependent() {
        byte[] a = bytes(2000, 1);
        byte[] b = bytes(2000, 2);
        byte[] c = bytes(2000, 3);
        List<byte[]> mixed = new ArrayList<>();
        List<byte[]> da = p.split(tile(1, 1, 0, 0), a);
        List<byte[]> db = p.split(tile(1, 1, 1, 0), b);
        List<byte[]> dc = p.split(tile(2, 1, 0, 0), c);
        for (int i = 0; i < 2; i++) {
            mixed.add(da.get(i));
            mixed.add(db.get(i));
            mixed.add(dc.get(i));
        }
        List<Packetizer.Assembled> out = new ArrayList<>();
        for (byte[] d : mixed) p.accept(d).ifPresent(out::add);
        assertEquals(3, out.size());
    }

    @Test
    void incompleteTilesDropAfterTwoSeconds() {
        List<byte[]> ds = p.split(tile(1, 1, 0, 0), bytes(5000, 7));
        for (int i = 0; i < 4; i++) p.accept(ds.get(i));
        assertEquals(1, p.pendingCount());
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1900));
        // another tile starts (part 0 of 2) inside the window: the first is still pending
        byte[] other = new StreamHeader(StreamHeader.TYPE_TILE, 1, 1, 5, 5, 0, 2).encode();
        assertTrue(p.accept(other).isEmpty());
        assertEquals(2, p.pendingCount());
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(200));
        // late final part arrives after the 2 s window: nothing emitted
        assertTrue(p.accept(ds.get(4)).isEmpty());
        assertEquals(2, p.pendingCount(), "old parts gone; only 'other' and the lone late part are pending");
    }

    @Test
    void malformedDatagramsAreIgnored() {
        assertTrue(p.accept(new byte[0]).isEmpty());
        assertTrue(p.accept(new byte[5]).isEmpty());
        assertTrue(p.accept(bytes(100, 1)).isEmpty());
        // part >= parts
        byte[] bad = new StreamHeader(StreamHeader.TYPE_TILE, 1, 1, 0, 0, 3, 2).encode();
        assertTrue(p.accept(bad).isEmpty());
        // parts == 0
        byte[] zero = new StreamHeader(StreamHeader.TYPE_TILE, 1, 1, 0, 0, 0, 0).encode();
        assertTrue(p.accept(zero).isEmpty());
    }

    @Test
    void nonTileTypesAreNotAssembled() {
        byte[] hello = new StreamHeader(StreamHeader.TYPE_HELLO, 1, 0, 0, 0, 0, 1).encode();
        assertTrue(p.accept(hello).isEmpty());
        assertEquals(0, p.pendingCount());
    }

    @Test
    void frameSeqComparisonSurvivesWraparound() {
        byte[] j = bytes(100, 1);
        assertTrue(feedAll(p.split(tile(1, Integer.MAX_VALUE, 0, 0), j)).isPresent());
        assertTrue(feedAll(p.split(tile(1, Integer.MIN_VALUE, 0, 0), j)).isPresent());
    }

    @Test
    void codecThroughFakeJpegAndPacketizerRoundTrip() {
        TileCodec.Jpeg fake = new FakeJpeg();
        TileCodec codec = new TileCodec();
        int w = 150;
        int h = 90;
        byte[] px = bytes(w * h * 4, 11);
        ByteBuffer src = ByteBuffer.wrap(px);
        ByteBuffer dst = ByteBuffer.allocate(w * h * 4);
        int seq = 1;
        for (TileCodec.Tile t : codec.diff(w, h, src)) {
            byte[] jpeg = fake.encode(t, 80);
            Optional<Packetizer.Assembled> a =
                    feedAll(p.split(tile(1, seq, t.tx(), t.ty()), jpeg));
            byte[] got = a.orElseThrow().jpeg();
            ByteBuffer tileDst = ByteBuffer.allocate(t.w() * t.h() * 4);
            fake.decodeInto(got, tileDst, t.w(), t.h());
            for (int row = 0; row < t.h(); row++) {
                dst.put(((t.ty() * 64 + row) * w + t.tx() * 64) * 4, tileDst.array(), row * t.w() * 4, t.w() * 4);
            }
        }
        assertArrayEquals(px, dst.array());
    }

    /** Lossless fake: "JPEG" is just the raw RGBA tile. */
    private static final class FakeJpeg implements TileCodec.Jpeg {
        @Override
        public byte[] encode(TileCodec.Tile t, int quality) {
            byte[] b = new byte[t.w() * t.h() * 4];
            t.rgba().duplicate().get(b);
            return b;
        }

        @Override
        public void decodeInto(byte[] jpeg, ByteBuffer dst, int w, int h) {
            dst.put(0, jpeg, 0, w * h * 4);
        }
    }
}
