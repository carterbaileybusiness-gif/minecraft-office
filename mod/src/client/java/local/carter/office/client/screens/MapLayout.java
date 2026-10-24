package local.carter.office.client.screens;

import java.util.List;

import local.carter.office.client.platform.Platform.Rect;

/**
 * Fits global screen rectangles into a map area: one scale for all (sizes stay proportional), the whole arrangement
 * centred. Edges that touch in global coordinates touch on the map too (both ends of every box are rounded the same way),
 * so neighbouring screens never overlap or gap by a pixel.
 */
public final class MapLayout {
	private MapLayout() {}

	/** Map (area-relative pixels) = {@code off + global * scale}. */
	public record Fit(double scale, int offX, int offY) {
		public Rect toMap(final Rect r) {
			int x0 = (int) Math.round(offX + r.x() * scale);
			int y0 = (int) Math.round(offY + r.y() * scale);
			int x1 = (int) Math.round(offX + ((double) r.x() + r.w()) * scale);
			int y1 = (int) Math.round(offY + ((double) r.y() + r.h()) * scale);
			return new Rect(x0, y0, x1 - x0, y1 - y0);
		}

		/** The global point under map pixel (mx, my). */
		public int[] toGlobal(final int mx, final int my) {
			return new int[] {(int) Math.round((mx - offX) / scale), (int) Math.round((my - offY) / scale)};
		}
	}

	/** The fit of {@code rects} into an {@code areaW} × {@code areaH} area (a unit fit for nothing to show). */
	public static Fit fit(final List<Rect> rects, final int areaW, final int areaH) {
		if (rects.isEmpty() || areaW <= 1 || areaH <= 1) {
			return new Fit(1, 0, 0);
		}
		long minX = Long.MAX_VALUE, minY = Long.MAX_VALUE, maxX = Long.MIN_VALUE, maxY = Long.MIN_VALUE;
		for (Rect r : rects) {
			minX = Math.min(minX, r.x());
			minY = Math.min(minY, r.y());
			maxX = Math.max(maxX, (long) r.x() + r.w());
			maxY = Math.max(maxY, (long) r.y() + r.h());
		}
		double bw = Math.max(1, maxX - minX);
		double bh = Math.max(1, maxY - minY);
		// One pixel of slack, so rounding the far edges never leaves the area.
		double scale = Math.min((areaW - 1) / bw, (areaH - 1) / bh);
		int offX = (int) Math.round((areaW - bw * scale) / 2 - minX * scale);
		int offY = (int) Math.round((areaH - bh * scale) / 2 - minY * scale);
		return new Fit(scale, offX, offY);
	}
}
