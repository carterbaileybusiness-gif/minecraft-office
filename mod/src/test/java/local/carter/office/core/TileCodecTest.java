package local.carter.office.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TileCodecTest {
    private static ByteBuffer frame(int w, int h, int fill) {
        byte[] b = new byte[w * h * 4];
        for (int i = 0; i < b.length; i++) b[i] = (byte) (fill + (i / 4) % 7);
        return ByteBuffer.wrap(b);
    }

    private static void setPixel(ByteBuffer f, int w, int x, int y, byte v) {
        f.put((y * w + x) * 4, v);
    }

    @Test
    void firstFrameYieldsEveryTile() {
        TileCodec c = new TileCodec();
        assertEquals(4 * 3, c.diff(256, 192, frame(256, 192, 1)).size());
    }

    @Test
    void identicalFramesYieldNothing() {
        TileCodec c = new TileCodec();
        c.diff(256, 192, frame(256, 192, 1));
        assertTrue(c.diff(256, 192, frame(256, 192, 1)).isEmpty());
    }

    @Test
    void oneChangedPixelYieldsExactlyOneTile() {
        TileCodec c = new TileCodec();
        c.diff(256, 192, frame(256, 192, 1));
        ByteBuffer g = frame(256, 192, 1);
        setPixel(g, 256, 70, 130, (byte) 99);
        List<TileCodec.Tile> d = c.diff(256, 192, g);
        assertEquals(1, d.size());
        assertEquals(1, d.get(0).tx());
        assertEquals(2, d.get(0).ty());
    }

    @Test
    void changedTileContainsTheNewPixels() {
        TileCodec c = new TileCodec();
        c.diff(128, 128, frame(128, 128, 1));
        ByteBuffer g = frame(128, 128, 1);
        setPixel(g, 128, 65, 66, (byte) 99); // tile (1,1), local (1,2)
        TileCodec.Tile t = c.diff(128, 128, g).get(0);
        assertEquals(64, t.w());
        assertEquals(64, t.h());
        assertEquals(64 * 64 * 4, t.rgba().remaining());
        assertEquals((byte) 99, t.rgba().get((2 * 64 + 1) * 4));
    }

    @Test
    void odd1366x768GridHasNoOutOfBounds() {
        TileCodec c = new TileCodec();
        TileFrame f = c.frame(1366, 768, frame(1366, 768, 3));
        assertEquals(22, f.cols());
        assertEquals(12, f.rows());
        assertEquals(22 * 12, f.tiles().size());
        for (TileCodec.Tile t : f.tiles()) {
            assertEquals(t.tx() == 21 ? 22 : 64, t.w());
            assertEquals(64, t.h());
            assertEquals(t.w() * t.h() * 4, t.rgba().remaining());
        }
    }

    @Test
    void partialLastRowIsSmaller() {
        TileCodec c = new TileCodec();
        TileFrame f = c.frame(70, 100, frame(70, 100, 3));
        assertEquals(2, f.cols());
        assertEquals(2, f.rows());
        TileCodec.Tile corner = f.tiles().get(3);
        assertEquals(6, corner.w());
        assertEquals(36, corner.h());
    }

    @Test
    void lastColumnPixelsAreCopiedFromTheRightPlace() {
        TileCodec c = new TileCodec();
        ByteBuffer f = frame(70, 64, 3);
        setPixel(f, 70, 69, 63, (byte) 77);
        TileCodec.Tile last = c.frame(70, 64, f).tiles().get(1);
        assertEquals(6, last.w());
        assertEquals((byte) 77, last.rgba().get((63 * 6 + 5) * 4));
    }

    @Test
    void sizeChangeYieldsAllTilesAndFlag() {
        TileCodec c = new TileCodec();
        assertTrue(c.frame(128, 128, frame(128, 128, 1)).sizeChanged());
        assertFalse(c.frame(128, 128, frame(128, 128, 1)).sizeChanged());
        TileFrame f = c.frame(192, 128, frame(192, 128, 1));
        assertTrue(f.sizeChanged());
        assertEquals(3 * 2, f.tiles().size());
    }

    @Test
    void refreshCyclesRoundRobinAndWraps() {
        TileCodec c = new TileCodec();
        c.diff(192, 128, frame(192, 128, 1)); // 3x2 = 6 tiles, row-major
        List<TileCodec.Tile> a = c.refresh(4);
        List<TileCodec.Tile> b = c.refresh(4);
        assertEquals(4, a.size());
        assertEquals(4, b.size());
        assertEquals(List.of(0, 0), List.of(a.get(0).tx(), a.get(0).ty()));
        assertEquals(List.of(0, 1), List.of(a.get(3).tx(), a.get(3).ty()));
        // continues at index 4, wraps to 0, 1
        assertEquals(List.of(1, 1), List.of(b.get(0).tx(), b.get(0).ty()));
        assertEquals(List.of(2, 1), List.of(b.get(1).tx(), b.get(1).ty()));
        assertEquals(List.of(0, 0), List.of(b.get(2).tx(), b.get(2).ty()));
    }

    @Test
    void refreshCoversAllTilesWithinOneLap() {
        TileCodec c = new TileCodec();
        c.diff(1366, 768, frame(1366, 768, 1));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 22 * 12 / 10 + 1; i++)
            for (TileCodec.Tile t : c.refresh(10)) seen.add(t.tx() + "," + t.ty());
        assertEquals(22 * 12, seen.size());
    }

    @Test
    void refreshIsCappedAtTileCountAndEmptyBeforeFirstFrame() {
        TileCodec c = new TileCodec();
        assertTrue(c.refresh(5).isEmpty());
        c.diff(64, 64, frame(64, 64, 1));
        assertEquals(1, c.refresh(5).size());
    }

    @Test
    void refreshReturnsLatestFramePixelsAndRestartsAfterResize() {
        TileCodec c = new TileCodec();
        c.diff(64, 64, frame(64, 64, 1));
        ByteBuffer g = frame(64, 64, 1);
        setPixel(g, 64, 0, 0, (byte) 42);
        c.diff(64, 64, g);
        assertEquals((byte) 42, c.refresh(1).get(0).rgba().get(0));
        c.diff(128, 64, frame(128, 64, 1));
        TileCodec.Tile first = c.refresh(1).get(0);
        assertEquals(List.of(0, 0), List.of(first.tx(), first.ty()));
    }

    @Test
    void tilesDoNotAliasCallerBuffer() {
        TileCodec c = new TileCodec();
        ByteBuffer f = frame(64, 64, 1);
        TileCodec.Tile t = c.diff(64, 64, f).get(0);
        byte before = t.rgba().get(0);
        f.put(0, (byte) (before + 1));
        assertEquals(before, t.rgba().get(0));
    }

    @Test
    void honoursBufferPositionAndRejectsBadInput() {
        TileCodec c = new TileCodec();
        ByteBuffer f = frame(64, 64, 1);
        ByteBuffer padded = ByteBuffer.allocate(f.capacity() + 16);
        padded.position(16);
        padded.put(f.duplicate().clear());
        padded.position(16);
        assertEquals(1, c.diff(64, 64, padded).size());
        assertEquals(16, padded.position(), "caller's position is not consumed");
        assertThrows(IllegalArgumentException.class, () -> c.diff(64, 64, ByteBuffer.allocate(10)));
        assertThrows(IllegalArgumentException.class, () -> c.diff(0, 64, ByteBuffer.allocate(10)));
    }

    @Test
    void oversizeFramesAreRejectedBeforeAllocating() {
        TileCodec c = new TileCodec();
        ByteBuffer small = ByteBuffer.allocate(16);
        assertThrows(IllegalArgumentException.class, () -> c.diff(8193, 1, small));
        assertThrows(IllegalArgumentException.class, () -> c.diff(1, 8193, small));
        assertThrows(IllegalArgumentException.class, () -> c.diff(Integer.MAX_VALUE, Integer.MAX_VALUE, small));
        assertThrows(IllegalArgumentException.class, () -> c.diff(65536, 65536, small));
        assertThrows(IllegalArgumentException.class, () -> c.diff(-1, 10, small));
        // codec is still usable and the max side is allowed
        assertEquals(1, c.frame(1, 1, ByteBuffer.allocate(4)).tiles().size());
        assertEquals(128, c.frame(TileCodec.MAX_DIMENSION, 1, ByteBuffer.allocate(TileCodec.MAX_DIMENSION * 4)).cols());
    }

    @Test
    void customTileSize() {
        TileCodec c = new TileCodec(32);
        assertEquals(4 * 2, c.diff(100, 50, frame(100, 50, 1)).size());
    }
}
