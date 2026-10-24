package local.carter.office.client.screens;

import java.util.List;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.platform.Platform.Rect;

/**
 * Fits global screen rectangles into a map area: one scale for all (sizes stay proportional), the whole arrangement
 * centred. Edges that touch in global coordinates touch on the map too (both ends of every box are rounded the same way),
 * so neighbouring screens never overlap or gap by a pixel. The map panel keeps {@link #MARGIN} free all round (the chosen
 * box's glow) and its status line under the area ({@link #area}); {@link #panelHeight} is how tall a panel the arrangement
 * can use at the panel's full width.
 */
public final class MapLayout {
	/** Free room between the panel's frame and the arrangement, on every side. */
	public static final int MARGIN = 6;
	/** The status line's inset from the panel's frame, and its line height. */
	public static final int STATUS_INSET = 4;
	public static final int STATUS_LINE = 9;

	private MapLayout() {}

	/** The screens' part of a map panel at (x, y), {@code w} × {@code h}, above {@code statusLines} lines of status. */
	public static Rect area(final int x, final int y, final int w, final int h, final int statusLines) {
		return new Rect(x + MARGIN, y + MARGIN, Math.max(1, w - 2 * MARGIN), Math.max(1, h - 2 * MARGIN - statusHeight(statusLines)));
	}

	/** The room the status line takes under the area (none without one). */
	public static int statusHeight(final int statusLines) {
		return statusLines == 0 ? 0 : statusLines * STATUS_LINE + 2;
	}

	/**
	 * The panel height, within [{@code min}, {@code max}], at which {@code rects} fitted to a {@code panelW}-wide panel
	 * (with {@code statusLines} of status) fill its width: a wide arrangement gets no empty bands above and below, a tall
	 * one as much height as allowed.
	 */
	public static int panelHeight(final List<Rect> rects, final int panelW, final int statusLines, final int min, final int max) {
		long[] b = bounds(rects);
		if (b == null) {
			return min;
		}
		double bw = Math.max(1, b[2] - b[0]);
		double bh = Math.max(1, b[3] - b[1]);
		int areaW = Math.max(1, panelW - 2 * MARGIN);
		int need = (int) Math.ceil((areaW - 1) * bh / bw) + 1 + 2 * MARGIN + statusHeight(statusLines);
		return Math.max(min, Math.min(max, need));
	}

	/** {minX, minY, maxX, maxY} of {@code rects}, or null for none. */
	private static long @Nullable [] bounds(final List<Rect> rects) {
		if (rects.isEmpty()) {
			return null;
		}
		long minX = Long.MAX_VALUE, minY = Long.MAX_VALUE, maxX = Long.MIN_VALUE, maxY = Long.MIN_VALUE;
		for (Rect r : rects) {
			minX = Math.min(minX, r.x());
			minY = Math.min(minY, r.y());
			maxX = Math.max(maxX, (long) r.x() + r.w());
			maxY = Math.max(maxY, (long) r.y() + r.h());
		}
		return new long[] {minX, minY, maxX, maxY};
	}

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
		long[] b = bounds(rects);
		if (b == null || areaW <= 1 || areaH <= 1) {
			return new Fit(1, 0, 0);
		}
		long minX = b[0], minY = b[1], maxX = b[2], maxY = b[3];
		double bw = Math.max(1, maxX - minX);
		double bh = Math.max(1, maxY - minY);
		// One pixel of slack, so rounding the far edges never leaves the area.
		double scale = Math.min((areaW - 1) / bw, (areaH - 1) / bh);
		int offX = (int) Math.round((areaW - bw * scale) / 2 - minX * scale);
		int offY = (int) Math.round((areaH - bh * scale) / 2 - minY * scale);
		return new Fit(scale, offX, offY);
	}
}
