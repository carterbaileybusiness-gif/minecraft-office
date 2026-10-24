package local.carter.office.client.screens;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.platform.Platform.Rect;

/**
 * Where a dragged Office display lands, like Windows Display Settings: edge to edge against the nearest screen (left,
 * right, above or below), sharing at least {@link #MIN_SHARE} of its own edge, its edges snapping into line with the
 * neighbour's when within {@link #ALIGN} of its size, never overlapping anything. Integer edges only, so the helper's
 * exact touching check (shared vectors V1-V6) agrees. Pure.
 */
public final class DisplaySnap {
	/** A landing spot shares at least this much of the moving display's edge with its neighbour. */
	public static final double MIN_SHARE = 0.10;
	/** Edges this close (share of the moving display's size) line up exactly. */
	public static final double ALIGN = 0.06;

	private DisplaySnap() {}

	/**
	 * The top-left corner {@code moving} snaps to among {@code fixed} (the nearest valid spot to where it was dropped), or
	 * null when there is none.
	 */
	public static int @Nullable [] snap(final Rect moving, final List<Rect> fixed) {
		List<int[]> s = spots(moving, fixed);
		return s.isEmpty() ? null : s.get(0);
	}

	/**
	 * Every top-left corner {@code moving} could snap to among {@code fixed} (touching, overlapping nothing), nearest to
	 * where it was dropped first; the first is {@link #snap}'s.
	 */
	public static List<int[]> spots(final Rect moving, final List<Rect> fixed) {
		List<int[]> out = new ArrayList<>();
		for (int[] c : candidates(moving, fixed)) {
			if (!overlapsAny(new Rect(c[0], c[1], moving.w(), moving.h()), fixed)) {
				out.add(c);
			}
		}
		out.sort(Comparator.comparingDouble(c -> Math.hypot(c[0] - moving.x(), c[1] - moving.y()))); // stable: ties keep their order
		return out;
	}

	private static List<int[]> candidates(final Rect m, final List<Rect> fixed) {
		List<int[]> out = new ArrayList<>();
		int minV = Math.max(1, (int) Math.ceil(m.h() * MIN_SHARE));
		int minH = Math.max(1, (int) Math.ceil(m.w() * MIN_SHARE));
		int alignV = (int) Math.round(m.h() * ALIGN);
		int alignH = (int) Math.round(m.w() * ALIGN);
		for (Rect f : fixed) {
			// left / right of f: x fixed, y slides along f's side
			int y = slide(m.y(), m.h(), f.y(), f.h(), minV, alignV);
			out.add(new int[] {f.x() + f.w(), y});
			out.add(new int[] {f.x() - m.w(), y});
			// above / below f: y fixed, x slides along f's top or bottom
			int x = slide(m.x(), m.w(), f.x(), f.w(), minH, alignH);
			out.add(new int[] {x, f.y() + f.h()});
			out.add(new int[] {x, f.y() - m.h()});
			// the plain aligned corners too, so a spot blocked by a neighbour still has its tidy alternatives
			out.add(new int[] {f.x() + f.w(), f.y()});
			out.add(new int[] {f.x() - m.w(), f.y()});
			out.add(new int[] {f.x(), f.y() + f.h()});
			out.add(new int[] {f.x(), f.y() - m.h()});
		}
		return out;
	}

	/**
	 * A start along one axis for a span of {@code len} next to a side spanning [{@code fStart}, {@code fStart + fLen}):
	 * as close to {@code start} as sharing at least {@code minShare} allows, then lined up with either end when close.
	 */
	private static int slide(final int start, final int len, final int fStart, final int fLen, final int minShare, final int align) {
		int share = Math.min(minShare, Math.min(len, fLen));
		long lo = (long) fStart - len + share;
		long hi = (long) fStart + fLen - share;
		long s = Math.max(lo, Math.min(hi, start));
		if (Math.abs(s - fStart) <= align) {
			s = fStart; // tops (lefts) line up
		} else if (Math.abs(s + len - ((long) fStart + fLen)) <= align) {
			s = (long) fStart + fLen - len; // bottoms (rights) line up
		}
		return (int) s;
	}

	public static boolean overlaps(final Rect a, final Rect b) {
		return (long) a.x() < (long) b.x() + b.w() && (long) b.x() < (long) a.x() + a.w()
				&& (long) a.y() < (long) b.y() + b.h() && (long) b.y() < (long) a.y() + a.h();
	}

	private static boolean overlapsAny(final Rect r, final List<Rect> others) {
		for (Rect o : others) {
			if (overlaps(r, o)) {
				return true;
			}
		}
		return false;
	}

	/** {@code a} and {@code b} share a stretch of edge (at least one point long; a corner alone is not touching). */
	public static boolean touches(final Rect a, final Rect b) {
		if (overlaps(a, b)) {
			return false;
		}
		long ax1 = (long) a.x() + a.w(), ay1 = (long) a.y() + a.h(), bx1 = (long) b.x() + b.w(), by1 = (long) b.y() + b.h();
		long vShare = Math.min(ay1, by1) - Math.max(a.y(), b.y());
		long hShare = Math.min(ax1, bx1) - Math.max(a.x(), b.x());
		boolean side = (ax1 == b.x() || bx1 == a.x()) && vShare > 0;
		boolean topBottom = (ay1 == b.y() || by1 == a.y()) && hShare > 0;
		return side || topBottom;
	}

	/** The touching rule for one display: overlaps none of {@code others} and touches at least one of them. */
	public static boolean valid(final Rect r, final List<Rect> others) {
		if (overlapsAny(r, others)) {
			return false;
		}
		for (Rect o : others) {
			if (touches(r, o)) {
				return true;
			}
		}
		return false;
	}

	/** {@link #layoutValid(List, List, int)} with the main display first among {@code monitors}. */
	public static boolean layoutValid(final List<Rect> monitors, final List<Rect> offices) {
		return layoutValid(monitors, offices, -1);
	}

	/**
	 * The helper's rule for a whole arrangement (its {@code OriginRequest.check}, shared vector V7): no Office display
	 * overlaps another screen, and every screen, monitors and Office displays alike, is joined to the main display through
	 * touching edges. So no Office display floats (two touching only each other are not enough), and no monitor is left
	 * hanging off a moved Office display (macOS would move it). The main display is {@code offices.get(mainOffice)} when
	 * the player made an Office display main, else ({@code mainOffice} -1) the first of {@code monitors}.
	 */
	public static boolean layoutValid(final List<Rect> monitors, final List<Rect> offices, final int mainOffice) {
		List<Rect> all = new ArrayList<>(monitors);
		all.addAll(offices);
		for (int i = 0; i < offices.size(); i++) {
			for (int j = 0; j < all.size(); j++) {
				if (j != monitors.size() + i && overlaps(offices.get(i), all.get(j))) {
					return false;
				}
			}
		}
		int seed = mainOffice >= 0 ? monitors.size() + mainOffice : 0;
		if (seed >= all.size()) {
			return all.isEmpty();
		}
		boolean[] reached = new boolean[all.size()];
		reached[seed] = true;
		boolean grew = true;
		while (grew) {
			grew = false;
			for (int i = 0; i < all.size(); i++) {
				for (int j = 0; j < all.size() && !reached[i]; j++) {
					if (reached[j] && touches(all.get(i), all.get(j))) {
						reached[i] = true;
						grew = true;
					}
				}
			}
		}
		for (boolean r : reached) {
			if (!r) {
				return false;
			}
		}
		return true;
	}
}
