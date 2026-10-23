package local.carter.office.core;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Splits tightly packed RGBA frames (stride = width*4, top-left origin) into square tiles and reports only the
 * tiles whose CRC32 changed since the previous frame. Edge tiles in the last column/row are the remainder.
 * Not thread-safe.
 */
public final class TileCodec {
    /** JPEG encode/decode is injected (client: LWJGL stb; tests: a fake). */
    public interface Jpeg {
        byte[] encode(Tile tile, int quality);

        /** Decodes into {@code dst} as tightly packed RGBA of {@code w} x {@code h}. */
        void decodeInto(byte[] jpeg, ByteBuffer dst, int w, int h);
    }

    /** One tile; {@code rgba} is an independent heap buffer of exactly {@code w*h*4} bytes at position 0. */
    public record Tile(int tx, int ty, int w, int h, ByteBuffer rgba) {}

    private final int tile;
    private int width;
    private int height;
    private int cols;
    private int rows;
    private byte[] pixels;
    private int[] crcs;
    private int cursor;
    private final CRC32 crc = new CRC32();

    public TileCodec() {
        this(64);
    }

    public TileCodec(int tile) {
        if (tile <= 0) throw new IllegalArgumentException("tile " + tile);
        this.tile = tile;
    }

    /** Changed tiles versus the previous frame; all tiles on the first frame or a size change. */
    public List<Tile> diff(int w, int h, ByteBuffer rgba) {
        return frame(w, h, rgba).tiles();
    }

    public TileFrame frame(int w, int h, ByteBuffer rgba) {
        if (w <= 0 || h <= 0) throw new IllegalArgumentException("size " + w + "x" + h);
        long need = (long) w * h * 4;
        if (rgba.remaining() < need)
            throw new IllegalArgumentException("buffer has " + rgba.remaining() + " bytes, need " + need);

        boolean resized = pixels == null || w != width || h != height;
        if (resized) {
            width = w;
            height = h;
            cols = ceilDiv(w, tile);
            rows = ceilDiv(h, tile);
            pixels = new byte[(int) need];
            crcs = new int[cols * rows];
            cursor = 0;
        }
        rgba.duplicate().get(pixels, 0, (int) need);

        List<Tile> changed = new ArrayList<>();
        for (int ty = 0; ty < rows; ty++) {
            for (int tx = 0; tx < cols; tx++) {
                int c = crcOf(tx, ty);
                int i = ty * cols + tx;
                if (resized || c != crcs[i]) {
                    crcs[i] = c;
                    changed.add(extract(tx, ty));
                }
            }
        }
        return new TileFrame(w, h, cols, rows, resized, changed);
    }

    /** The next {@code n} tiles of the current frame in row-major order, continuing where the last call stopped. */
    public List<Tile> refresh(int n) {
        if (pixels == null || n <= 0) return List.of();
        int total = cols * rows;
        int count = Math.min(n, total);
        List<Tile> out = new ArrayList<>(count);
        for (int k = 0; k < count; k++) {
            out.add(extract(cursor % cols, cursor / cols));
            cursor = (cursor + 1) % total;
        }
        return out;
    }

    private int crcOf(int tx, int ty) {
        int x0 = tx * tile;
        int y0 = ty * tile;
        int tw = Math.min(tile, width - x0);
        int th = Math.min(tile, height - y0);
        crc.reset();
        for (int row = 0; row < th; row++) crc.update(pixels, ((y0 + row) * width + x0) * 4, tw * 4);
        return (int) crc.getValue();
    }

    private Tile extract(int tx, int ty) {
        int x0 = tx * tile;
        int y0 = ty * tile;
        int tw = Math.min(tile, width - x0);
        int th = Math.min(tile, height - y0);
        byte[] out = new byte[tw * th * 4];
        for (int row = 0; row < th; row++)
            System.arraycopy(pixels, ((y0 + row) * width + x0) * 4, out, row * tw * 4, tw * 4);
        return new Tile(tx, ty, tw, th, ByteBuffer.wrap(out));
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
