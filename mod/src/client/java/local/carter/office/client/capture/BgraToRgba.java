package local.carter.office.client.capture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Converts BGRA rows (Windows desktop pixel order, libyuv "ARGB") to the tightly packed, opaque RGBA a {@link FrameSink} takes. */
public final class BgraToRgba {
	private BgraToRgba() {}

	/**
	 * Converts {@code w x h} pixels starting at {@code src.position()} (rows {@code srcStride} bytes apart) into
	 * {@code dst} from {@code dst.position()}, tightly packed; alpha is forced to 255 (GDI capture often leaves it 0).
	 * Positions, limits and byte orders of both buffers are unchanged. {@code src} and {@code dst} may be the same buffer
	 * when {@code srcStride == w * 4}.
	 */
	public static void convert(final ByteBuffer src, final int srcStride, final int w, final int h, final ByteBuffer dst) {
		if (w <= 0 || h <= 0) {
			return;
		}
		if (srcStride < w * 4) {
			throw new IllegalArgumentException("stride " + srcStride + " < " + w * 4);
		}
		ByteBuffer s = src.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		ByteBuffer d = dst.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		int s0 = src.position();
		int d0 = dst.position();
		for (int y = 0; y < h; y++) {
			int si = s0 + y * srcStride;
			int di = d0 + y * w * 4;
			for (int x = 0; x < w; x++, si += 4, di += 4) {
				int p = s.getInt(si); // little-endian: 0xAARRGGBB for bytes B,G,R,A
				d.putInt(di, 0xFF000000 | (p & 0x0000FF00) | ((p >>> 16) & 0xFF) | ((p & 0xFF) << 16));
			}
		}
	}
}
