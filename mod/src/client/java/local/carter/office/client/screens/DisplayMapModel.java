package local.carter.office.client.screens;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.platform.Platform.Rect;
import local.carter.office.core.Mode;
import local.carter.office.core.Shape;

/**
 * The display map's state, without any drawing: the player's screens with the Office displays the player moved but
 * hasn't Applied yet, the dashed slots an Extend hologram could still create, what a click picks, and what the status
 * line says. Extend: click an Office display (or a dashed slot) to show it, drag one to move it. Mirror: click a real
 * monitor to copy it. Real monitors never move. Pure; the settings screen owns one per open screen.
 */
public final class DisplayMapModel {
	/** What a click on the map picks. */
	public sealed interface Choice {
		/** Extend: show Office display {@code slot} (1-4). */
		record Slot(int slot) implements Choice {}

		/** Mirror: copy monitor {@code id} (null = the main display). */
		record Monitor(@Nullable String id) implements Choice {}

		/** Nothing to pick there. */
		record None() implements Choice {}
	}

	/** Ids of the dashed, not-yet-existing Office displays. */
	public static final String GHOST = "ghost:";
	public static final String UNPLUGGED = "That screen isn't plugged in — showing A";

	private ScreenList list;
	private Mode mode;
	private int displaySlot;
	private @Nullable String mirrorDisplay;
	private Shape shape;
	/** Office display slot → its moved origin {x, y}, not Applied yet. */
	private final Map<Integer, int[]> pending = new TreeMap<>();
	private @Nullable String dragId;
	private int dragX;
	private int dragY;
	/** Apply was pressed: the next screen list (the helper's answer) replaces the pending moves. */
	private boolean applied;

	public DisplayMapModel(final ScreenList list, final Mode mode, final int displaySlot, final @Nullable String mirrorDisplay,
			final Shape shape) {
		this.list = list;
		this.mode = mode;
		this.displaySlot = displaySlot;
		this.mirrorDisplay = mirrorDisplay;
		this.shape = shape;
	}

	public ScreenList list() {
		return list;
	}

	public Mode mode() {
		return mode;
	}

	/** The hologram's choices changed (a click was sent, or the mode / shape changed); a new mode drops pending moves. */
	public void reset(final Mode m, final int slot, final @Nullable String mirror, final Shape s) {
		if (m != mode) {
			pending.clear();
			dragId = null;
		}
		mode = m;
		displaySlot = slot;
		mirrorDisplay = mirror;
		shape = s;
	}

	// ---------------------------------------------------------------- what is drawn

	/** Every real screen where it is drawn now: moved Office displays at their pending spot, the dragged one under the mouse. */
	public List<Screen> screens() {
		List<Screen> out = new ArrayList<>();
		for (Screen s : placed()) {
			out.add(s.id().equals(dragId) ? s.at(dragX, dragY) : s);
		}
		return out;
	}

	/** The real screens with the pending moves applied (no drag). */
	private List<Screen> placed() {
		List<Screen> out = new ArrayList<>();
		for (Screen s : list.screens()) {
			int[] p = s.isMonitor() ? null : pending.get(s.slot());
			out.add(p == null ? s : s.at(p[0], p[1]));
		}
		return out;
	}

	/**
	 * Extend: the Office displays 1-4 that don't exist yet, dashed, in a row right of the rightmost screen, top-aligned
	 * with the main display, at the hologram shape's size (a click picks one; it is created when the hologram needs it).
	 */
	public List<Screen> ghosts() {
		List<Screen> out = new ArrayList<>();
		if (mode != Mode.EXTEND) {
			return out;
		}
		Screen main = list.main();
		long right = Long.MIN_VALUE;
		for (Screen s : placed()) {
			right = Math.max(right, (long) s.x() + s.w());
		}
		int gap = Math.max(1, main.w() / 10);
		long x = right + gap;
		for (int slot = 1; slot <= 4; slot++) {
			if (list.office(slot) == null) {
				out.add(new Screen(GHOST + slot, slot, false, null, (int) x, main.y(), shape.pxW(), shape.pxH()));
				x += shape.pxW() + gap;
			}
		}
		return out;
	}

	/** Everything the map fits: the screens at their base spots (not following a drag) and the dashed slots. */
	public List<Rect> fitRects() {
		List<Rect> out = new ArrayList<>();
		for (Screen s : placed()) {
			out.add(s.rect());
		}
		for (Screen g : ghosts()) {
			out.add(g.rect());
		}
		return out;
	}

	public static boolean isGhost(final Screen s) {
		return s.id().startsWith(GHOST);
	}

	/** The big label: a monitor's letter, an Office display's (or dashed slot's) number. */
	public String label(final Screen s) {
		return isGhost(s) ? Integer.toString(s.slot()) : list.label(s);
	}

	/** The id of the screen this hologram shows (drawn with the white frame), or null (Window mode). */
	public @Nullable String shownId() {
		return switch (mode) {
			case EXTEND -> {
				Screen office = list.office(displaySlot);
				yield office != null ? office.id() : GHOST + displaySlot;
			}
			case MIRROR -> {
				String resolved = list.resolveMirror(mirrorDisplay);
				yield resolved != null ? resolved : list.main().id();
			}
			case WINDOW -> null;
		};
	}

	// ---------------------------------------------------------------- clicks and drags

	public Choice click(final String screenId) {
		if (screenId.startsWith(GHOST)) {
			return mode == Mode.EXTEND ? new Choice.Slot(Integer.parseInt(screenId.substring(GHOST.length()))) : new Choice.None();
		}
		Screen s = list.byId(screenId);
		if (s == null) {
			return new Choice.None();
		}
		if (mode == Mode.EXTEND && !s.isMonitor()) {
			return new Choice.Slot(s.slot());
		}
		if (mode == Mode.MIRROR && s.isMonitor() && list.abilities().canPickMirror()) {
			return new Choice.Monitor(s == list.main() ? null : s.id());
		}
		return new Choice.None();
	}

	/** Whether {@code id} can be dragged: an existing Office display, in Extend, where displays can move. */
	public boolean canDrag(final String id) {
		Screen s = list.byId(id);
		return mode == Mode.EXTEND && list.abilities().canMove() && s != null && !s.isMonitor() && !applied; // not while the helper moves them
	}

	/** The dragged display's top-left is now at (globalX, globalY) (preview only); false when it can't be dragged. */
	public boolean drag(final String id, final int globalX, final int globalY) {
		if (!canDrag(id)) {
			return false;
		}
		dragId = id;
		dragX = globalX;
		dragY = globalY;
		return true;
	}

	public @Nullable String dragging() {
		return dragId;
	}

	/**
	 * Released: the display snaps to the nearest spot touching another screen. When there is none, or the arrangement
	 * would break the helper's rule (every Office display reaches a real monitor, nothing overlaps), nothing moves.
	 * Returns whether its spot changed.
	 */
	public boolean drop(final String id) {
		if (!id.equals(dragId)) {
			return false;
		}
		dragId = null;
		Screen original = list.byId(id);
		if (original == null) {
			return false;
		}
		List<Rect> fixed = new ArrayList<>();
		Screen before = null;
		for (Screen s : placed()) {
			if (s.id().equals(id)) {
				before = s;
			} else {
				fixed.add(s.rect());
			}
		}
		int[] at = DisplaySnap.snap(new Rect(dragX, dragY, original.w(), original.h()), fixed);
		if (at == null || before == null || (at[0] == before.x() && at[1] == before.y())) {
			return false;
		}
		List<Rect> monitors = new ArrayList<>();
		List<Rect> offices = new ArrayList<>();
		for (Screen s : placed()) {
			Screen p = s.id().equals(id) ? s.at(at[0], at[1]) : s;
			(p.isMonitor() ? monitors : offices).add(p.rect());
		}
		if (!DisplaySnap.layoutValid(monitors, offices)) {
			return false;
		}
		if (at[0] == original.x() && at[1] == original.y()) {
			pending.remove(original.slot()); // back where it is for real: nothing to Apply for it
		} else {
			pending.put(original.slot(), at);
		}
		return true;
	}

	/** The moves to Apply, by slot. */
	public List<Origin> pending() {
		List<Origin> out = new ArrayList<>();
		for (Map.Entry<Integer, int[]> e : pending.entrySet()) {
			out.add(new Origin(e.getKey(), e.getValue()[0], e.getValue()[1]));
		}
		return out;
	}

	public boolean canApply() {
		return mode == Mode.EXTEND && list.abilities().canMove() && !pending.isEmpty() && !applied;
	}

	/** Apply was sent: the helper's next screen list is the truth (moved, or refused and unchanged). */
	public void applied() {
		applied = true;
	}

	/** A fresh screen list: after Apply it replaces the pending moves; otherwise moves of displays that are gone are dropped. */
	public void update(final ScreenList fresh) {
		list = fresh;
		if (applied) {
			pending.clear();
			applied = false;
		} else {
			pending.keySet().removeIf(slot -> fresh.office(slot) == null);
		}
		if (dragId != null && fresh.byId(dragId) == null) {
			dragId = null;
		}
	}

	/**
	 * The map's status line, or null: a Mirror whose chosen monitor is unplugged says so; greyed abilities say why
	 * ("Restart Minecraft to update the helper").
	 */
	public @Nullable String status() {
		ScreenList.Abilities a = list.abilities();
		if (mode == Mode.MIRROR && mirrorDisplay != null && a.live() && list.byId(mirrorDisplay) == null) {
			return UNPLUGGED;
		}
		boolean greyed = switch (mode) {
			case EXTEND -> !a.canMove();
			case MIRROR -> !a.canPickMirror();
			case WINDOW -> false;
		};
		return greyed ? a.hint() : null;
	}
}
