package local.carter.office.client.magic;

import java.util.ArrayList;
import java.util.List;

import local.carter.office.client.magic.screen.SignLayout.Rect;
import local.carter.office.magic.core.Slots;

/**
 * Where the R spell wheel draws, in GUI units: a ring of six slices (slice index = slot, slice 0 straight up, clockwise)
 * around the centre, inner radius {@link #INNER} and outer radius {@link #OUTER}, scaled down when the screen is too small
 * (the outer radius stays within {@code min(w, h) × 0.42}, and the ring and its labels inside the screen) and up in half
 * steps on screens that are large in GUI units (GUI scale 1 or 2 on a big window, {@link #GROW_PER}), so the wheel keeps
 * about the same size on the monitor at every GUI scale. Each slice
 * has a label anchor just outside the ring, clear of the hovered slice's 1.15× pop: the top and bottom labels are one wide
 * line, the side ones two narrower lines growing away from the ring. Everything sits above the hotbar, the mana bar and its
 * number ({@link #HUD_RESERVE}); the wheel centres on the crosshair when there is room and moves up a little when not.
 */
public final class WheelLayout {
	public static final int SLICES = Slots.WHEEL_SLICES;
	public static final float INNER = 22f;
	public static final float OUTER = 70f;
	/** The outer radius never exceeds this share of the smaller screen side. */
	public static final float FIT = 0.42f;
	/** The hovered slice draws this much larger. */
	public static final float HOVER_SCALE = 1.15f;
	public static final int MARGIN = 8;
	/** Kept free at the bottom: hotbar, mana bar and its number, hearts and the armour row (it starts at h − 49). */
	public static final int HUD_RESERVE = 50;
	/** On screens this many GUI units tall (or more) per step, the wheel grows in half steps, so it reads the same at GUI 1. */
	public static final float GROW_PER = 360f;
	/** Label anchors sit this far outside the outer ring. */
	public static final float LABEL_GAP = 17f;
	/** Side labels: two lines this wide. */
	public static final float SIDE_W = 64f;
	public static final float SIDE_H = 20f;
	/** Top and bottom labels: one line this wide. */
	public static final float LINE_W = 100f;
	public static final float LINE_H = 10f;
	/** Spell icon size (before the hover pop). */
	public static final float ICON = 18f;

	public enum Align { LEFT, CENTRE, RIGHT }

	/**
	 * @param labels label anchor rects, index = slice
	 * @param aligns how text sits in each label: LEFT grows away from a right-hand slice, RIGHT from a left-hand one
	 */
	public record Layout(int cx, int cy, float inner, float outer, float scale, float icon, List<Rect> labels, List<Align> aligns) {
		public float hoverOuter() {
			return outer * HOVER_SCALE;
		}

		/** Slice {@code i}'s centre angle in radians (0 = up, clockwise). */
		public static double angle(int i) {
			return Math.toRadians(i * 360.0 / SLICES);
		}

		public float iconRadius() {
			return (inner + outer) / 2f;
		}

		public float iconX(int i) {
			return cx + iconRadius() * (float) Math.sin(angle(i));
		}

		public float iconY(int i) {
			return cy - iconRadius() * (float) Math.cos(angle(i));
		}
	}

	private WheelLayout() {}

	public static Layout layout(int w, int h) {
		double sin60 = Math.sin(Math.toRadians(60));
		float anchor = OUTER + LABEL_GAP;
		double sideReach = anchor * sin60 + SIDE_W;
		double vertReach = anchor + LINE_H;
		double s = Math.max(1.0, Math.floor(Math.min(w, h) / GROW_PER * 2) / 2);
		s = Math.min(s, Math.min(w, h) * FIT / OUTER);
		s = Math.min(s, fit(w - 2.0 * MARGIN, 2 * sideReach));
		s = Math.min(s, fit(h - MARGIN - HUD_RESERVE, 2 * vertReach));
		s = Math.max(0.05, s);
		float sc = (float) s;

		int cx = w / 2;
		double lo = MARGIN + vertReach * s;
		double hi = h - HUD_RESERVE - vertReach * s;
		int cy = (int) Math.floor(Math.max(lo, Math.min(h / 2.0, hi)) + 1e-6);
		if (cy < lo - 1e-6) cy = (int) Math.ceil(lo); // rounding never pushes the top label past the margin

		float a = anchor * sc;
		List<Rect> labels = new ArrayList<>(SLICES);
		List<Align> aligns = new ArrayList<>(SLICES);
		for (int i = 0; i < SLICES; i++) {
			double ang = Layout.angle(i);
			double ax = cx + a * Math.sin(ang);
			double ay = cy - a * Math.cos(ang);
			double sx = Math.sin(ang);
			if (Math.abs(sx) < 0.1) {
				// Top or bottom: one centred line beyond the ring.
				double lw = LINE_W * sc, lh = LINE_H * sc;
				int x0 = (int) Math.floor(ax - lw / 2), x1 = (int) Math.ceil(ax + lw / 2);
				boolean top = Math.cos(ang) > 0;
				int y0 = top ? (int) Math.floor(ay - lh) : (int) Math.ceil(ay);
				int y1 = top ? (int) Math.floor(ay) : (int) Math.ceil(ay + lh);
				labels.add(new Rect(x0, y0, x1 - x0, y1 - y0));
				aligns.add(Align.CENTRE);
			} else {
				// Sides: two lines, vertically centred on the anchor, growing away from the ring.
				double lw = SIDE_W * sc, lh = SIDE_H * sc;
				int y0 = (int) Math.round(ay - lh / 2), y1 = (int) Math.round(ay + lh / 2);
				if (sx > 0) {
					int x0 = (int) Math.ceil(ax);
					labels.add(new Rect(x0, y0, (int) Math.floor(ax + lw) - x0, y1 - y0));
					aligns.add(Align.LEFT);
				} else {
					int x1 = (int) Math.floor(ax);
					int x0 = (int) Math.ceil(ax - lw);
					labels.add(new Rect(x0, y0, x1 - x0, y1 - y0));
					aligns.add(Align.RIGHT);
				}
			}
		}
		return new Layout(cx, cy, INNER * sc, OUTER * sc, sc, ICON * sc, List.copyOf(labels), List.copyOf(aligns));
	}

	/** The scale at which {@code need} fits {@code room}; when it has to shrink, 2 units are kept for rounding to whole pixels. */
	private static double fit(double room, double need) {
		double s = room / need;
		return s >= 1 ? s : (room - 2) / need;
	}
}
