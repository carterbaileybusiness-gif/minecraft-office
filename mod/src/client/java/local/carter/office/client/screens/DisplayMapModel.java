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
	public static final String APPLYING = "Applying…";
	public static final String COULD_NOT_MOVE = "Couldn't move it there";
	/** No screen list this long after Apply: drags unlock again (the moves stay, to Apply again). */
	public static final long APPLY_TIMEOUT_MS = 2000;
	/** How long a refused or failed Apply is shown on the status line. */
	public static final long FAILURE_SHOWN_MS = 3000;
	/** The helper's own reason is shown when it is at most this long, else {@link #COULD_NOT_MOVE}. */
	static final int MAX_HELPER_MESSAGE = 32;

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
	private long appliedAt;
	/** A refused / failed / unanswered Apply's line, shown until {@link #failureUntil}. */
	private @Nullable String failure;
	private long failureUntil;

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

	/** Everything drawn, bottom to top: dashed slots, monitors, Office displays, the dragged one. */
	public List<Screen> drawOrder() {
		List<Screen> out = new ArrayList<>(ghosts());
		List<Screen> now = screens();
		Screen dragged = null;
		for (Screen s : now) {
			if (s.isMonitor()) {
				out.add(s);
			}
		}
		for (Screen s : now) {
			if (!s.isMonitor()) {
				if (s.id().equals(dragId)) {
					dragged = s;
				} else {
					out.add(s);
				}
			}
		}
		if (dragged != null) {
			out.add(dragged);
		}
		return out;
	}

	/**
	 * Everything the map fits: the screens at their base spots (not following a drag), the dashed slots, and the real
	 * spots of displays moved but not Applied (drawn dotted, {@link #movedFrom}).
	 */
	public List<Rect> fitRects() {
		List<Rect> out = new ArrayList<>();
		for (Screen s : placed()) {
			out.add(s.rect());
		}
		for (Screen g : ghosts()) {
			out.add(g.rect());
		}
		for (Screen s : list.screens()) {
			if (!s.isMonitor() && pending.containsKey(s.slot())) {
				out.add(s.rect());
			}
		}
		return out;
	}

	/**
	 * The Office displays drawn away from where they really are (moved but not Applied, or being dragged), at their real
	 * spot: the map draws that spot dotted under the moved box.
	 */
	public List<Screen> movedFrom() {
		List<Screen> out = new ArrayList<>();
		for (Screen s : list.screens()) {
			if (!s.isMonitor() && (pending.containsKey(s.slot()) || s.id().equals(dragId))) {
				out.add(s);
			}
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

	/**
	 * Whether {@code id} can be dragged: an existing Office display, in Extend, where displays can move; never the main
	 * display (the player may have made an Office display main: moving it would shift every other screen).
	 */
	public boolean canDrag(final String id) {
		Screen s = list.byId(id);
		return mode == Mode.EXTEND && list.abilities().canMove() && s != null && !s.isMonitor() && !s.main()
				&& !applied; // not while the helper moves them
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
	 * Released: the display snaps to the nearest spot touching another screen that keeps the helper's rule (nothing
	 * overlaps, every screen stays joined to the main display: {@link DisplaySnap#layoutValid}). When there is none,
	 * nothing moves. Returns whether its spot changed.
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
		if (before == null) {
			return false;
		}
		int[] at = null;
		for (int[] spot : DisplaySnap.spots(new Rect(dragX, dragY, original.w(), original.h()), fixed)) {
			if (spot[0] == before.x() && spot[1] == before.y()) {
				return false; // the nearest good spot is where it already is
			}
			if (layoutValidWith(id, spot)) {
				at = spot;
				break;
			}
		}
		if (at == null) {
			return false;
		}
		if (at[0] == original.x() && at[1] == original.y()) {
			pending.remove(original.slot()); // back where it is for real: nothing to Apply for it
		} else {
			pending.put(original.slot(), at);
		}
		return true;
	}

	/** Whether the layout with Office display {@code id} at {@code at} keeps the helper's rule (the main display first). */
	private boolean layoutValidWith(final String id, final int[] at) {
		List<Rect> monitors = new ArrayList<>();
		List<Rect> offices = new ArrayList<>();
		int mainOffice = -1;
		Screen main = list.main();
		for (Screen s : placed()) {
			Screen p = s.id().equals(id) ? s.at(at[0], at[1]) : s;
			if (p.id().equals(main.id()) && p.isMonitor()) {
				monitors.add(0, p.rect());
			} else if (p.isMonitor()) {
				monitors.add(p.rect());
			} else {
				if (p.id().equals(main.id())) {
					mainOffice = offices.size();
				}
				offices.add(p.rect());
			}
		}
		return DisplaySnap.layoutValid(monitors, offices, mainOffice);
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

	/**
	 * Apply was sent at {@code now} (ms): the helper's next screen list is the truth (moved, or refused and unchanged).
	 * Until it comes the status line says "Applying…" and drags wait; after {@link #APPLY_TIMEOUT_MS} without one they
	 * unlock again ({@link #tick}).
	 */
	public void applied(final long now) {
		applied = true;
		appliedAt = now;
		failure = null;
	}

	/** Whether Apply was sent and the helper hasn't answered yet. */
	public boolean applying() {
		return applied;
	}

	/**
	 * The helper refused or failed the Apply ({@code ERROR bad_request / display_failed}, its DISPLAYS follows): the
	 * moves snap back and the status line says so for {@link #FAILURE_SHOWN_MS}. Ignored when no Apply is waiting (the
	 * error was about something else). Returns whether it was taken.
	 */
	public boolean failed(final @Nullable String helperMessage, final long now) {
		if (!applied) {
			return false;
		}
		applied = false;
		pending.clear();
		failure = failureText(helperMessage);
		failureUntil = now + FAILURE_SHOWN_MS;
		return true;
	}

	/** The line for a refused Apply: the helper's reason when it is short, else "Couldn't move it there". */
	static String failureText(final @Nullable String helperMessage) {
		String m = helperMessage == null ? "" : helperMessage.strip();
		if (m.isEmpty() || m.length() > MAX_HELPER_MESSAGE) {
			return COULD_NOT_MOVE;
		}
		return m.startsWith("macOS") ? m : Character.toUpperCase(m.charAt(0)) + m.substring(1);
	}

	/**
	 * The time is {@code now} (ms; the settings screen calls this every tick): an unanswered Apply times out (drags
	 * unlock, "Couldn't move it there"), a failure line goes after its time. Returns whether {@link #canApply} or the
	 * drag lock may have changed.
	 */
	public boolean tick(final long now) {
		if (failure != null && now >= failureUntil) {
			failure = null;
		}
		if (applied && now - appliedAt >= APPLY_TIMEOUT_MS) {
			applied = false;
			failure = COULD_NOT_MOVE;
			failureUntil = now + FAILURE_SHOWN_MS;
			return true;
		}
		return false;
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
	 * The map's status line, or null: "Applying…" while the helper moves the displays, then a refused Apply's reason for
	 * a few seconds; a Mirror whose chosen monitor is unplugged says so; greyed abilities say why ("Restart Minecraft to
	 * update the helper").
	 */
	public @Nullable String status() {
		if (applied) {
			return APPLYING;
		}
		String f = failure;
		if (f != null) {
			return f;
		}
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
