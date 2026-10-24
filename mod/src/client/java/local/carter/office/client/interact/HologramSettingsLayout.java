package local.carter.office.client.interact;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.platform.Platform.Rect;

/**
 * Where the hologram settings screen's parts go, in GUI units. Two columns on top (left: Shape and Shows, then a note
 * or the window list; right: who can see it), then the display map in its own row across both columns, like the
 * monitor map across the top of Windows Display Settings, with its status line inside and Identify / Apply under it;
 * then Pick up and Done, and under them the room a Mirror's warning takes. The map panel is as tall as the arrangement
 * can use at the row's width ({@link MapFit}), up to the room the window has. Nothing scrolls at any GUI scale or window
 * size (down to the 320 × 240 scaled minimum): columns narrow to fit the width; with little height the map gets shorter
 * than {@link #MAP_H} and the margins shrink; a Show-to player list pages fewer players. Only where a tall player list
 * leaves no room for a {@link #MAP_H} map row under it does the map go back into the left column, beside the list.
 * Pure: unit-tested.
 */
public final class HologramSettingsLayout {
	/** A column's width (narrower only where the window is), and the gap between the columns. */
	public static final int COL_W = 160;
	public static final int GAP = 16;
	/** A row of buttons (20 high) with its gap, and a label line over a row or list. */
	public static final int ROW = 22;
	public static final int LABEL = 12;
	public static final int BUTTON = 20;
	/** The title and subtitle above the columns. */
	public static final int HEADER = 2 * LABEL + 10;
	/** The left column's top: Shape and Shows, each a label over a row of buttons. */
	public static final int CHOICES = 2 * (LABEL + ROW + 4);
	/** The right column's top: its label over Public / Private / Show to…. */
	public static final int PRIVACY = LABEL + ROW + 4;
	/** A line of text set 6 under a label's line (a note under a row of buttons). */
	public static final int NOTE = 6 + 8;
	/** One online player's checkbox, at most this many on a page. */
	public static final int PLAYER_ROW = 20;
	public static final int MAX_PLAYERS_PER_PAGE = 5;
	/** A player list's pager row (&lt; 1 / 2 &gt;) and the gap above it. */
	public static final int PLAYER_PAGER = 2 + BUTTON;
	/** The map's button row under its panel. */
	public static final int MAP_BUTTONS = 4 + BUTTON;
	/** Room between the body and Pick up / Done. */
	public static final int FOOT_GAP = 8;
	/** A Mirror's warning line under Pick up / Done. */
	public static final int WARNING = 4 + 9;
	/** The map panel's usual smallest height; shorter only where the window has no more room. */
	public static final int MAP_H = 56;
	/** The tallest map panel (a tall arrangement in a tall window). */
	public static final int MAP_H_MAX = 200;
	/** A floor for windows smaller than any Minecraft allows (240 scaled high), so the panel never turns inside out. */
	private static final int MAP_H_FLOOR = 24;
	/** The margin kept round the screen when there is room, and the least it keeps when there isn't. */
	public static final int MARGIN = 8;
	public static final int EDGE = 2;
	/** The least room left and right of the columns. */
	public static final int SIDE = 4;

	private HologramSettingsLayout() {}

	/** The display map panel's height for a {@code w}-wide panel, within [{@code min}, {@code max}] (the screen asks the map). */
	@FunctionalInterface
	public interface MapFit {
		int height(int w, int min, int max);
	}

	/**
	 * What the screen shows: the left column's height ({@code map}: down to Shows, the map row follows; else its whole
	 * height), whether the owner's display map is shown, Show to…'s online players (-1: no list, the column's note) and
	 * whether a Mirror's warning line is.
	 */
	public record Content(int leftH, boolean map, int players, boolean warning) {}

	/**
	 * Where it all goes: the columns at {@code left} and {@code right}, {@code colW} wide; the title at {@code titleY};
	 * both columns from {@code top}; {@code playersPerPage} on a Show-to list; the map panel ({@code map}, null without
	 * one; as wide as both columns unless it went beside the list), its label line just above it and its button row
	 * just under it; Pick up and Done at {@code footY}; the warning line at {@code warningY} (-1: none).
	 */
	public record Plan(int colW, int left, int right, int titleY, int top, int playersPerPage, @Nullable Rect map, int footY,
			int warningY) {
		/** Both columns and the gap between them. */
		public int fullW() {
			return 2 * colW + GAP;
		}
	}

	/** The screen laid out in a {@code screenW} × {@code screenH} window. */
	public static Plan plan(final int screenW, final int screenH, final Content c, final MapFit fit) {
		int colW = Math.max(1, Math.min(COL_W, (screenW - GAP - 2 * SIDE) / 2));
		int fullW = 2 * colW + GAP;
		int left = (screenW - fullW) / 2;
		int right = left + colW + GAP;
		int fixed = HEADER + FOOT_GAP + BUTTON + (c.warning() ? WARNING : 0);
		int perPage = playersPerPage(c.players(), screenH - 2 * EDGE - fixed);
		int rightH = rightHeight(c.players(), perPage);
		int body;
		Rect map = null; // y from the columns' top until the top is known
		if (!c.map()) {
			body = Math.max(c.leftH(), rightH);
		} else {
			int above = Math.max(c.leftH(), rightH);
			int wideMax = mapRoom(screenH, fixed + above + LABEL + MAP_BUTTONS);
			int besideMax = mapRoom(screenH, fixed + c.leftH() + LABEL + MAP_BUTTONS);
			if (wideMax >= MAP_H || wideMax >= besideMax) {
				int h = fit.height(fullW, Math.min(MAP_H, wideMax), wideMax);
				map = new Rect(left, above + LABEL, fullW, h);
				body = above + LABEL + h + MAP_BUTTONS;
			} else { // a tall player list: the map beside it, as it was before it had a row of its own
				int h = fit.height(colW, Math.min(MAP_H, besideMax), besideMax);
				map = new Rect(left, c.leftH() + LABEL, colW, h);
				body = Math.max(c.leftH() + LABEL + h + MAP_BUTTONS, rightH);
			}
		}
		int titleY = Math.max(EDGE, (screenH - fixed - body) / 2);
		int top = titleY + HEADER;
		int footY = top + body + FOOT_GAP;
		Rect placed = map == null ? null : new Rect(map.x(), top + map.y(), map.w(), map.h());
		return new Plan(colW, left, right, titleY, top, perPage, placed, footY, c.warning() ? footY + BUTTON + 4 : -1);
	}

	/**
	 * The map panel's tallest height with {@code fixed} taken by everything else: the window's height less the margins,
	 * up to {@link #MAP_H_MAX}; where that is under {@link #MAP_H}, the margins shrink to {@link #EDGE} for up to
	 * {@link #MAP_H} (shorter only if even that doesn't fit).
	 */
	static int mapRoom(final int screenH, final int fixed) {
		int roomy = screenH - 2 * MARGIN - fixed;
		int tight = screenH - 2 * EDGE - fixed;
		return Math.max(MAP_H_FLOOR, Math.min(MAP_H_MAX, roomy >= MAP_H ? roomy : Math.min(tight, MAP_H)));
	}

	/** The most players (1 to {@link #MAX_PLAYERS_PER_PAGE}) a page shows with the right column at most {@code room} high. */
	static int playersPerPage(final int players, final int room) {
		for (int rows = MAX_PLAYERS_PER_PAGE; rows > 1; rows--) {
			if (rightHeight(players, rows) <= room) {
				return rows;
			}
		}
		return 1;
	}

	/** The right column's height: the privacy row, then the note or the player list (and its pager when it has pages). */
	static int rightHeight(final int players, final int perPage) {
		if (players < 0) {
			return PRIVACY + NOTE;
		}
		if (players == 0) {
			return PRIVACY + LABEL + NOTE;
		}
		return PRIVACY + LABEL + Math.min(players, perPage) * PLAYER_ROW + (players > perPage ? PLAYER_PAGER : 0);
	}
}
