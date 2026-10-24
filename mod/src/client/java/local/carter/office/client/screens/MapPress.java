package local.carter.office.client.screens;

import java.util.List;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.platform.Platform.Rect;
import local.carter.office.client.screens.DisplayMapModel.Choice;

/**
 * The display map's mouse handling without any drawing (the widget feeds it the box under the mouse): what a press
 * hit, whether it became a drag (past {@link #DRAG_THRESHOLD} pixels), and what the release does. Boxes are compared
 * by screen id, never by object: the model makes new {@link Screen} records on every call (dashed slots, displays moved
 * but not Applied), so a press and its release never hold the same one. Pure.
 */
public final class MapPress {
	/** A press moving this many map pixels or less is a click, not a drag. */
	public static final int DRAG_THRESHOLD = 3;

	/** What a release did: dropped a dragged display ({@code dropped}), or clicked {@code choice} (maybe None). */
	public record Release(boolean dropped, Choice choice) {}

	private final DisplayMapModel model;
	private @Nullable Screen pressed;
	private double pressX;
	private double pressY;
	private boolean dragging;
	/** The fit while dragging (the map doesn't rescale under the mouse). */
	private MapLayout.@Nullable Fit frozen;

	public MapPress(final DisplayMapModel model) {
		this.model = model;
	}

	/** The topmost of {@code order} (bottom to top) under (mx, my), on a map fitted with {@code fit} into an area at (ax, ay). */
	public static @Nullable Screen hit(final List<Screen> order, final MapLayout.Fit fit, final int ax, final int ay, final double mx,
			final double my) {
		for (int i = order.size() - 1; i >= 0; i--) {
			Rect b = box(order.get(i), fit, ax, ay);
			if (mx >= b.x() && mx < b.x() + b.w() && my >= b.y() && my < b.y() + b.h()) {
				return order.get(i);
			}
		}
		return null;
	}

	/** {@code s}'s box on screen: the fit's map rectangle moved to the area at (ax, ay). */
	public static Rect box(final Screen s, final MapLayout.Fit fit, final int ax, final int ay) {
		Rect m = fit.toMap(s.rect());
		return new Rect(ax + m.x(), ay + m.y(), m.w(), m.h());
	}

	/** The same screen's box (by id; null is never the same). */
	public static boolean same(final @Nullable Screen a, final @Nullable Screen b) {
		return a != null && b != null && a.id().equals(b.id());
	}

	/** The left button went down at (x, y) over {@code hit} (or nothing). */
	public void press(final @Nullable Screen hit, final double x, final double y) {
		pressed = hit;
		pressX = x;
		pressY = y;
		dragging = false;
		frozen = null;
	}

	/** The mouse moved to (x, y) with the button down: past the threshold a draggable display follows it. */
	public void drag(final double x, final double y, final Supplier<MapLayout.Fit> fit) {
		Screen p = pressed;
		if (p == null || !model.canDrag(p.id())) {
			return;
		}
		if (!dragging && Math.hypot(x - pressX, y - pressY) <= DRAG_THRESHOLD) {
			return;
		}
		if (!dragging) {
			dragging = true;
			frozen = fit.get();
		}
		MapLayout.Fit f = frozen;
		int gx = (int) Math.round(p.x() + (x - pressX) / f.scale());
		int gy = (int) Math.round(p.y() + (y - pressY) / f.scale());
		model.drag(p.id(), gx, gy);
	}

	/** The button came up over {@code hit} (or nothing): a drag drops; a click on the box it pressed picks it. */
	public Release release(final @Nullable Screen hit) {
		Screen p = pressed;
		boolean wasDragging = dragging;
		pressed = null;
		dragging = false;
		frozen = null;
		if (wasDragging && p != null) {
			model.drop(p.id());
			return new Release(true, new Choice.None());
		}
		return new Release(false, p != null && same(p, hit) ? model.click(p.id()) : new Choice.None());
	}

	public boolean dragging() {
		return dragging;
	}

	public MapLayout.@Nullable Fit frozen() {
		return frozen;
	}
}
