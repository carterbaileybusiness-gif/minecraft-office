package local.carter.office.client.render;

import java.nio.ByteBuffer;

import org.jspecify.annotations.Nullable;

/**
 * Pure helpers for {@link ScreenTexture}: rectangle clipping, the mip chain, and a 2x2 box filter. No GPU, no Minecraft
 * state, so they are unit-tested directly.
 */
public final class ScreenTextureMath {
	/** Most mip levels a screen texture gets (1920 wide -> 5 levels down to 120 px; 2880 wide -> 6 down to 90 px). */
	public static final int MAX_MIP_LEVELS = 6;
	/** Mip levels stop once the larger side would drop below this (smaller levels add nothing at hologram sizes). */
	public static final int MIN_MIP_SIZE = 64;

	private ScreenTextureMath() {}

	/** Axis-aligned pixel rectangle; {@code w}/{@code h} may be zero or negative only for an empty result. */
	public record Rect(int x, int y, int w, int h) {
		public boolean isEmpty() {
			return w <= 0 || h <= 0;
		}

		public int right() {
			return x + w;
		}

		public int bottom() {
			return y + h;
		}

		public boolean contains(final Rect o) {
			return o.x >= x && o.y >= y && o.right() <= right() && o.bottom() <= bottom();
		}

		public long area() {
			return isEmpty() ? 0 : (long) w * h;
		}

		/** The part of (x, y, w, h) inside a texW x texH texture, or null if none (overflow-safe). */
		public static @Nullable Rect clip(final int x, final int y, final int w, final int h, final int texW, final int texH) {
			if (w <= 0 || h <= 0) {
				return null;
			}
			long x0 = Math.max(0L, x);
			long y0 = Math.max(0L, y);
			long x1 = Math.min((long) texW, (long) x + w);
			long y1 = Math.min((long) texH, (long) y + h);
			if (x1 <= x0 || y1 <= y0) {
				return null;
			}
			return new Rect((int) x0, (int) y0, (int) (x1 - x0), (int) (y1 - y0));
		}

		/**
		 * The texels of mip {@code level} (levelW x levelH) whose 2^level x 2^level footprint touches this level-0 rect,
		 * clamped to the level (may be empty: the last rows/columns of an odd-sized level 0 feed no smaller level).
		 */
		public Rect atLevel(final int level, final int levelW, final int levelH) {
			int x0 = x >> level;
			int y0 = y >> level;
			int x1 = Math.min(levelW, ((right() - 1) >> level) + 1);
			int y1 = Math.min(levelH, ((bottom() - 1) >> level) + 1);
			return new Rect(x0, y0, x1 - x0, y1 - y0);
		}
	}

	/** Number of mip levels for a w x h screen (1 = no mipmaps). */
	public static int mipLevels(final int w, final int h) {
		int big = Math.max(w, h);
		int small = Math.min(w, h);
		int levels = 1;
		while (levels < MAX_MIP_LEVELS && (big >> levels) >= MIN_MIP_SIZE && (small >> levels) >= 1) {
			levels++;
		}
		return levels;
	}

	/** Size of a side at mip {@code level} (GL/Vulkan rule: floor, at least 1). */
	public static int levelSize(final int size, final int level) {
		return Math.max(1, size >> level);
	}

	/**
	 * Box-filters {@code dstRect} of a dw x dh level from the next larger sw x sh level (RGBA8, tightly packed, absolute
	 * indexing). Each destination texel is the rounded mean of its 2x2 source texels (clamped at the source edge). Four
	 * channels are averaged at once in two 16-bit lanes per int, so it works whatever the buffers' byte order.
	 */
	public static void downsample(final ByteBuffer src, final int sw, final int sh, final ByteBuffer dst, final int dw, final int dh,
			final Rect dstRect) {
		int x1 = Math.min(dw, dstRect.right());
		int y1 = Math.min(dh, dstRect.bottom());
		for (int dy = Math.max(0, dstRect.y()); dy < y1; dy++) {
			int sy0 = Math.min(2 * dy, sh - 1) * sw;
			int sy1 = Math.min(2 * dy + 1, sh - 1) * sw;
			int out = (dy * dw + Math.max(0, dstRect.x())) * 4;
			for (int dx = Math.max(0, dstRect.x()); dx < x1; dx++, out += 4) {
				int sx0 = Math.min(2 * dx, sw - 1);
				int sx1 = Math.min(2 * dx + 1, sw - 1);
				int a = src.getInt((sy0 + sx0) * 4);
				int b = src.getInt((sy0 + sx1) * 4);
				int c = src.getInt((sy1 + sx0) * 4);
				int d = src.getInt((sy1 + sx1) * 4);
				int lo = (((a & 0x00FF00FF) + (b & 0x00FF00FF) + (c & 0x00FF00FF) + (d & 0x00FF00FF) + 0x00020002) >>> 2) & 0x00FF00FF;
				int hi = ((((a >>> 8) & 0x00FF00FF) + ((b >>> 8) & 0x00FF00FF) + ((c >>> 8) & 0x00FF00FF) + ((d >>> 8) & 0x00FF00FF)
						+ 0x00020002) >>> 2) & 0x00FF00FF;
				dst.putInt(out, lo | (hi << 8));
			}
		}
	}
}
