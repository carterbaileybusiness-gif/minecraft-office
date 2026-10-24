package local.carter.office.client.interact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import local.carter.office.client.interact.HologramSettingsLayout.Content;
import local.carter.office.client.interact.HologramSettingsLayout.MapFit;
import local.carter.office.client.interact.HologramSettingsLayout.Plan;
import local.carter.office.client.platform.Platform.Rect;
import local.carter.office.client.screens.MapLayout;

/**
 * The hologram settings screen's layout: the display map in its own row across both columns, as tall as the
 * arrangement uses up to the room the window has, and nothing leaving the window or overlapping at any GUI scale and
 * window size Minecraft allows (scaled, at least 320 × 240).
 */
class HologramSettingsLayoutTest {
	/** The busy arrangement the client GameTests use: three monitors and four Office displays, much wider than tall. */
	private static final List<Rect> BUSY = List.of(new Rect(0, 0, 1440, 900), new Rect(-1920, -180, 1920, 1080),
			new Rect(-3000, -1000, 1080, 1920), new Rect(1440, 0, 1920, 1080), new Rect(3360, 0, 1920, 1080),
			new Rect(5280, 0, 1920, 1080), new Rect(7200, 0, 1920, 1080));
	/** A laptop and one Office display beside it. */
	private static final List<Rect> PAIR = List.of(new Rect(0, 0, 1440, 900), new Rect(1440, 0, 1920, 1080));
	/** One 16:9 monitor. */
	private static final List<Rect> ONE = List.of(new Rect(0, 0, 1920, 1080));

	/** Scaled window sizes: 1080p at GUI scales 1-4, 854 × 480 at 2, 1280 × 960 at 4, and a few in between. */
	private static final int[][] SIZES = {{1920, 1080}, {960, 540}, {640, 360}, {480, 270}, {427, 240}, {320, 240},
			{335, 250}, {400, 300}, {1280, 720}, {320, 480}};

	private static final int OWNER_MAP = HologramSettingsLayout.CHOICES;
	private static final int WINDOW_LIST = HologramSettingsLayout.CHOICES + HologramSettingsLayout.LABEL + 3 * HologramSettingsLayout.ROW
			+ HologramSettingsLayout.BUTTON;
	private static final int ONE_NOTE = HologramSettingsLayout.CHOICES + HologramSettingsLayout.LABEL;

	private static MapFit fit(final List<Rect> rects, final int statusLines) {
		return (w, min, max) -> MapLayout.panelHeight(rects, w, statusLines, min, max);
	}

	/** Every widget and text line the screen puts down for {@code plan}, as rectangles (text: 9 high). */
	private static List<Rect> parts(final Plan p, final Content c, final int screenW) {
		List<Rect> out = new ArrayList<>();
		int colW = p.colW();
		int bw = (colW - 8) / 3;
		int y = p.top();
		out.add(new Rect(screenW / 2 - 30, p.titleY(), 60, 9)); // title
		out.add(new Rect(screenW / 2 - 40, p.titleY() + HologramSettingsLayout.LABEL, 80, 9)); // subtitle
		for (int col : new int[] {p.left(), p.right()}) {
			out.add(new Rect(col, y, 40, 9));
			for (int i = 0; i < 3; i++) {
				out.add(new Rect(col + i * (bw + 4), y + HologramSettingsLayout.LABEL, bw, 20));
			}
		}
		y += HologramSettingsLayout.LABEL + HologramSettingsLayout.ROW + 4;
		out.add(new Rect(p.left(), y, 40, 9));
		for (int i = 0; i < 3; i++) {
			out.add(new Rect(p.left() + i * (bw + 4), y + HologramSettingsLayout.LABEL, bw, 20));
		}
		y += HologramSettingsLayout.LABEL + HologramSettingsLayout.ROW + 4;
		Rect map = p.map();
		if (map != null) {
			out.add(new Rect(map.x(), map.y() - HologramSettingsLayout.LABEL, 40, 9));
			out.add(map);
			int bwMap = (colW - 4) / 2;
			out.add(new Rect(map.x(), map.y() + map.h() + 4, bwMap, 20));
			out.add(new Rect(map.x() + map.w() - bwMap, map.y() + map.h() + 4, bwMap, 20));
		} else if (c.leftH() == WINDOW_LIST) {
			out.add(new Rect(p.left(), y, 40, 9));
			for (int i = 0; i < 3; i++) {
				out.add(new Rect(p.left(), y + HologramSettingsLayout.LABEL + i * HologramSettingsLayout.ROW, colW, 20));
			}
			out.add(new Rect(p.left(), y + HologramSettingsLayout.LABEL + 3 * HologramSettingsLayout.ROW, colW, 20)); // pager
		} else {
			out.add(new Rect(p.left(), y, colW, 9));
		}
		int ry = p.top() + HologramSettingsLayout.PRIVACY;
		if (c.players() < 0) {
			out.add(new Rect(p.right(), ry + 6, colW, 9));
		} else {
			out.add(new Rect(p.right(), ry, 40, 9));
			ry += HologramSettingsLayout.LABEL;
			int rows = Math.min(c.players(), p.playersPerPage());
			for (int i = 0; i < rows; i++) {
				out.add(new Rect(p.right(), ry + i * HologramSettingsLayout.PLAYER_ROW, colW, 20));
			}
			if (c.players() > p.playersPerPage()) {
				out.add(new Rect(p.right(), ry + p.playersPerPage() * HologramSettingsLayout.PLAYER_ROW + 2, colW, 20));
			}
		}
		out.add(new Rect(screenW / 2 - 154, p.footY(), 308, 20));
		if (p.warningY() >= 0) {
			out.add(new Rect(screenW / 2 - 130, p.warningY(), 260, 9));
		}
		return out;
	}

	private static boolean overlap(final Rect a, final Rect b) {
		return a.x() < b.x() + b.w() && b.x() < a.x() + a.w() && a.y() < b.y() + b.h() && b.y() < a.y() + a.h();
	}

	private static void assertFits(final Plan p, final Content c, final int w, final int h, final String what) {
		List<Rect> parts = parts(p, c, w);
		for (int i = 0; i < parts.size(); i++) {
			Rect r = parts.get(i);
			assertTrue(r.x() >= 0 && r.y() >= 0 && r.x() + r.w() <= w && r.y() + r.h() <= h, r + " leaves the " + w + "x" + h
					+ " window (" + what + ")");
			for (int j = i + 1; j < parts.size(); j++) {
				assertTrue(!overlap(r, parts.get(j)), r + " overlaps " + parts.get(j) + " (" + what + ")");
			}
		}
	}

	@Test
	void nothingLeavesTheWindowOrOverlapsAtAnySize() {
		Content[] contents = {
				new Content(OWNER_MAP, true, -1, false), // Extend or Private Mirror
				new Content(OWNER_MAP, true, -1, true), // Public Mirror: the warning line too
				new Content(OWNER_MAP, true, 0, true), // Show to…, no one online
				new Content(OWNER_MAP, true, 3, true),
				new Content(OWNER_MAP, true, 12, true), // a long player list: paged
				new Content(OWNER_MAP, true, 12, false),
				new Content(WINDOW_LIST, false, -1, false),
				new Content(WINDOW_LIST, false, 12, false),
				new Content(ONE_NOTE, false, -1, true), // a non-owner's Public Mirror
				new Content(ONE_NOTE + HologramSettingsLayout.LABEL, false, 12, false)}; // Extend coming soon
		for (int[] size : SIZES) {
			for (Content c : contents) {
				for (List<Rect> rects : List.of(BUSY, PAIR, ONE)) {
					for (int lines = 0; lines <= 2; lines++) {
						Plan p = HologramSettingsLayout.plan(size[0], size[1], c, fit(rects, lines));
						assertFits(p, c, size[0], size[1], size[0] + "x" + size[1] + " " + c + " " + lines + " status lines");
						assertEquals(c.map(), p.map() != null);
						assertEquals(c.warning(), p.warningY() >= 0);
					}
				}
			}
		}
	}

	@Test
	void theMapHasItsOwnRowAcrossBothColumns() {
		for (int[] size : SIZES) {
			Content c = new Content(OWNER_MAP, true, -1, false);
			Plan p = HologramSettingsLayout.plan(size[0], size[1], c, fit(BUSY, 0));
			Rect map = p.map();
			assertNotNull(map);
			assertEquals(p.left(), map.x(), "starts at the left column");
			assertEquals(p.right() + p.colW(), map.x() + map.w(), "ends at the right column's edge");
			assertTrue(map.y() - HologramSettingsLayout.LABEL >= p.top() + HologramSettingsLayout.CHOICES,
					"its label under Shape and Shows at " + size[0] + "x" + size[1]);
			assertTrue(map.y() + map.h() + HologramSettingsLayout.MAP_BUTTONS <= p.footY(), "its buttons above Pick up and Done");
		}
		Plan full = HologramSettingsLayout.plan(1920, 1080, new Content(OWNER_MAP, true, -1, false), fit(BUSY, 0));
		assertEquals(2 * HologramSettingsLayout.COL_W + HologramSettingsLayout.GAP, full.map().w(), "336 wide where there's room");
	}

	@Test
	void theColumnsNarrowToTheWindow() {
		Plan p = HologramSettingsLayout.plan(320, 240, new Content(OWNER_MAP, true, -1, false), fit(BUSY, 0));
		assertTrue(p.left() >= HologramSettingsLayout.SIDE && p.right() + p.colW() <= 320 - HologramSettingsLayout.SIDE);
		assertEquals(p.fullW(), p.map().w());
		assertEquals(HologramSettingsLayout.COL_W,
				HologramSettingsLayout.plan(427, 240, new Content(OWNER_MAP, true, -1, false), fit(BUSY, 0)).colW());
	}

	@Test
	void theMapGrowsWithTheArrangementsAspectUpToTheRoomThereIs() {
		Content c = new Content(OWNER_MAP, true, -1, false);
		int w = 2 * HologramSettingsLayout.COL_W + HologramSettingsLayout.GAP;
		// Plenty of room: as tall as the arrangement uses at the full width (so it fills the width), no taller.
		for (List<Rect> rects : List.of(BUSY, PAIR)) {
			Plan p = HologramSettingsLayout.plan(960, 540, c, fit(rects, 1));
			assertEquals(MapLayout.panelHeight(rects, w, 1, HologramSettingsLayout.MAP_H, HologramSettingsLayout.MAP_H_MAX),
					p.map().h());
		}
		int busy = HologramSettingsLayout.plan(960, 540, c, fit(BUSY, 0)).map().h();
		int pair = HologramSettingsLayout.plan(960, 540, c, fit(PAIR, 0)).map().h();
		int one = HologramSettingsLayout.plan(960, 540, c, fit(ONE, 0)).map().h();
		assertTrue(busy < pair && pair < one, "taller as the arrangement gets less wide: " + busy + " " + pair + " " + one);
		assertTrue(busy > HologramSettingsLayout.MAP_H, "the busy arrangement no longer a thin strip: " + busy);
		assertEquals(HologramSettingsLayout.MAP_H_MAX, HologramSettingsLayout.plan(1920, 1080, c, fit(ONE, 2)).map().h(), "capped");
		// Little room: the window decides.
		int small = HologramSettingsLayout.plan(480, 270, c, fit(ONE, 0)).map().h();
		assertTrue(small < one, "the window caps it: " + small);
	}

	@Test
	void theTightestWindowGetsAShorterMapAndKeepsTheWarningsRoom() {
		Content warned = new Content(OWNER_MAP, true, -1, true);
		Plan p = HologramSettingsLayout.plan(427, 240, warned, fit(BUSY, 1));
		Rect map = p.map();
		assertTrue(map.h() < HologramSettingsLayout.MAP_H && map.h() >= 40, "shorter, still usable: " + map.h());
		assertEquals(p.footY() + HologramSettingsLayout.BUTTON + 4, p.warningY(), "the warning under Pick up and Done");
		assertTrue(p.warningY() + 9 <= 240);
		Plan plain = HologramSettingsLayout.plan(427, 240, new Content(OWNER_MAP, true, -1, false), fit(BUSY, 1));
		assertEquals(HologramSettingsLayout.MAP_H, plain.map().h(), "without the warning, the usual smallest map");
	}

	@Test
	void aTallPlayerListPagesFewerOrPutsTheMapBesideIt() {
		Content show = new Content(OWNER_MAP, true, 12, true);
		// Tall window: five a page, the map in its own row under the list.
		Plan tall = HologramSettingsLayout.plan(960, 540, show, fit(BUSY, 1));
		assertEquals(HologramSettingsLayout.MAX_PLAYERS_PER_PAGE, tall.playersPerPage());
		assertEquals(tall.fullW(), tall.map().w());
		assertTrue(tall.map().y() >= tall.top() + HologramSettingsLayout.rightHeight(12, 5) + HologramSettingsLayout.LABEL);
		// Short window: the map beside the list in the left column, the list paging as many as fit.
		Plan small = HologramSettingsLayout.plan(427, 240, show, fit(BUSY, 1));
		assertEquals(small.colW(), small.map().w());
		assertEquals(small.left(), small.map().x());
		assertTrue(small.playersPerPage() >= 3 && small.playersPerPage() < 5, "pages " + small.playersPerPage());
		// A short list leaves the row to the map.
		Plan few = HologramSettingsLayout.plan(427, 240, new Content(OWNER_MAP, true, 0, true), fit(BUSY, 1));
		assertEquals(few.fullW(), few.map().w());
	}

	@Test
	void nonOwnersKeepTheirLinesUnderShows() {
		Plan p = HologramSettingsLayout.plan(427, 240, new Content(ONE_NOTE, false, -1, true), fit(BUSY, 0));
		assertNull(p.map());
		assertTrue(p.footY() >= p.top() + ONE_NOTE + HologramSettingsLayout.FOOT_GAP);
	}
}
