package local.carter.office.client.screens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import local.carter.office.client.platform.Platform.Rect;

/**
 * The map's scale-to-fit: everything inside a small map area (160 × 64), proportional, centred, no overlaps; and the
 * settings screen's full-width map row (336 wide) as tall as the arrangement uses.
 */
class MapLayoutTest {
	private static final int AREA_W = 160;
	private static final int AREA_H = 64;

	private static List<Rect> mapped(final List<Rect> global) {
		MapLayout.Fit fit = MapLayout.fit(global, AREA_W, AREA_H);
		List<Rect> out = new ArrayList<>();
		for (Rect r : global) {
			out.add(fit.toMap(r));
		}
		return out;
	}

	private static void assertInside(final Rect r) {
		assertTrue(r.x() >= 0 && r.y() >= 0 && r.x() + r.w() <= AREA_W && r.y() + r.h() <= AREA_H, r + " inside");
	}

	private static boolean overlap(final Rect a, final Rect b) {
		return a.x() < b.x() + b.w() && b.x() < a.x() + a.w() && a.y() < b.y() + b.h() && b.y() < a.y() + a.h();
	}

	@Test
	void fitsAndKeepsAspect() {
		Rect main = new Rect(0, 0, 1440, 900);
		Rect office = new Rect(1440, 0, 1920, 1080);
		List<Rect> m = mapped(List.of(main, office));
		m.forEach(MapLayoutTest::assertInside);
		assertEquals(m.get(0).w() * 900.0 / 1440, m.get(0).h(), 1, "main keeps its aspect");
		assertEquals(m.get(1).w() * 1080.0 / 1920, m.get(1).h(), 1, "the Office display keeps its aspect");
		assertEquals(m.get(0).x() + m.get(0).w(), m.get(1).x(), "touching edges still touch");
		int left = m.get(0).x();
		int right = AREA_W - (m.get(1).x() + m.get(1).w());
		int top = Math.min(m.get(0).y(), m.get(1).y());
		int bottom = AREA_H - Math.max(m.get(0).y() + m.get(0).h(), m.get(1).y() + m.get(1).h());
		assertTrue(Math.abs(left - right) <= 2 && Math.abs(top - bottom) <= 2, "centred both ways");
	}

	@Test
	void extremeLayoutsFit() {
		List<Rect> global = List.of(
				new Rect(0, 0, 1440, 900), // main
				new Rect(-1920, -180, 1920, 1080), // left of main, raised
				new Rect(-3000, -1000, 1080, 1920), // portrait, further left and above
				new Rect(1440, 0, 1920, 1080), // Office 1..4 in a row on the right
				new Rect(3360, 0, 1920, 1080),
				new Rect(5280, 0, 1920, 1080),
				new Rect(7200, 0, 1920, 1080));
		List<Rect> m = mapped(global);
		for (int i = 0; i < m.size(); i++) {
			Rect r = m.get(i);
			assertInside(r);
			assertTrue(r.w() >= 12 && r.h() >= 8, r + " big enough to click and label");
			for (int j = i + 1; j < m.size(); j++) {
				assertFalse(overlap(r, m.get(j)), r + " overlaps " + m.get(j));
			}
		}
	}

	@Test
	void mapPointsGoBackToGlobal() {
		MapLayout.Fit fit = MapLayout.fit(List.of(new Rect(0, 0, 1440, 900), new Rect(1440, 0, 1920, 1080)), AREA_W, AREA_H);
		Rect m = fit.toMap(new Rect(1440, 0, 1920, 1080));
		int[] g = fit.toGlobal(m.x(), m.y());
		assertEquals(1440, g[0], 1 / fit.scale() + 1);
		assertEquals(0, g[1], 1 / fit.scale() + 1);
	}

	/** The busy layout the client GameTests use: three monitors and four Office displays, much wider than tall. */
	private static final List<Rect> BUSY = List.of(new Rect(0, 0, 1440, 900), new Rect(-1920, -180, 1920, 1080),
			new Rect(-3000, -1000, 1080, 1920), new Rect(1440, 0, 1920, 1080), new Rect(3360, 0, 1920, 1080),
			new Rect(5280, 0, 1920, 1080), new Rect(7200, 0, 1920, 1080));

	@Test
	void theAreaKeepsTheMarginAndTheStatusLineFree() {
		Rect a = MapLayout.area(10, 20, 160, 80, 0);
		assertEquals(new Rect(10 + MapLayout.MARGIN, 20 + MapLayout.MARGIN, 160 - 2 * MapLayout.MARGIN, 80 - 2 * MapLayout.MARGIN), a);
		Rect two = MapLayout.area(10, 20, 160, 80, 2);
		assertEquals(a.h() - (2 * MapLayout.STATUS_LINE + 2), two.h(), "two status lines under the screens");
		assertEquals(a.w(), two.w());
	}

	@Test
	void theArrangementFillsTheAreaOneWayAndIsCentredTheOther() {
		for (int[] size : new int[][] {{148, 44}, {148, 120}, {60, 120}}) {
			MapLayout.Fit fit = MapLayout.fit(BUSY, size[0], size[1]);
			int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
			for (Rect r : BUSY) {
				Rect m = fit.toMap(r);
				minX = Math.min(minX, m.x());
				minY = Math.min(minY, m.y());
				maxX = Math.max(maxX, m.x() + m.w());
				maxY = Math.max(maxY, m.y() + m.h());
			}
			int w = maxX - minX, h = maxY - minY;
			assertTrue(w >= size[0] - 2 || h >= size[1] - 2, "fills the width or the height of " + size[0] + "x" + size[1]);
			assertTrue(minX >= 0 && minY >= 0 && maxX <= size[0] && maxY <= size[1], "inside " + size[0] + "x" + size[1]);
			assertTrue(Math.abs(minX - (size[0] - maxX)) <= 1 && Math.abs(minY - (size[1] - maxY)) <= 1, "centred in " + size[0] + "x" + size[1]);
			assertEquals(12120 * fit.scale(), w, 1.01, "one scale for x");
			assertEquals(2080 * fit.scale(), h, 1.01, "and the same for y");
		}
	}

	/** The settings screen's map row: both columns and the gap between them. */
	private static final int ROW_W = 336;

	@Test
	void thePanelGrowsOnlyAsFarAsTheArrangementUsesIt() {
		// Wide: the panel's width decides; more height would only add empty bands.
		assertEquals(56, MapLayout.panelHeight(BUSY, 160, 0, 56, 200), "a column-wide panel left the busy layout a thin strip");
		int wide = MapLayout.panelHeight(BUSY, ROW_W, 0, 56, 200);
		assertEquals(69, wide, "across the whole row it gets the height it can use (56 + the margin)");
		// One portrait monitor: as tall as allowed.
		List<Rect> tall = List.of(new Rect(0, 0, 1080, 1920));
		assertEquals(200, MapLayout.panelHeight(tall, ROW_W, 0, 56, 200));
		assertEquals(80, MapLayout.panelHeight(tall, ROW_W, 0, 56, 80), "never past the room the window has");
		// Main + one Office display (2.1:1): just tall enough for the row's width, the status line under it.
		List<Rect> pair = List.of(new Rect(0, 0, 1440, 900), new Rect(1440, 0, 1920, 1080));
		int h0 = MapLayout.panelHeight(pair, ROW_W, 0, 40, 200);
		int h1 = MapLayout.panelHeight(pair, ROW_W, 1, 40, 200);
		assertEquals(117, h0);
		assertEquals(h0 + MapLayout.STATUS_LINE + 2, h1);
		assertTrue(h0 > MapLayout.panelHeight(pair, 160, 0, 40, 200), "a wider panel is taller at the same aspect");
		for (List<Rect> rects : List.of(pair, BUSY)) {
			int h = MapLayout.panelHeight(rects, ROW_W, 0, 40, 200);
			Rect area = MapLayout.area(0, 0, ROW_W, h, 0);
			MapLayout.Fit fit = MapLayout.fit(rects, area.w(), area.h());
			int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
			for (Rect r : rects) {
				minX = Math.min(minX, fit.toMap(r).x());
				maxX = Math.max(maxX, fit.toMap(r).x() + fit.toMap(r).w());
			}
			assertTrue(minX <= 1 && maxX >= area.w() - 2, "spans the area's width (" + minX + ".." + maxX + " of " + area.w() + ")");
		}
		assertEquals(56, MapLayout.panelHeight(List.of(), ROW_W, 0, 56, 200), "nothing to show: the smallest panel");
	}

	@Test
	void theFullRowFitsTheBusyLayoutInsideWithOneScale() {
		Rect area = MapLayout.area(0, 0, ROW_W, MapLayout.panelHeight(BUSY, ROW_W, 1, 56, 200), 1);
		MapLayout.Fit fit = MapLayout.fit(BUSY, area.w(), area.h());
		List<Rect> m = new ArrayList<>();
		for (Rect r : BUSY) {
			m.add(fit.toMap(r));
		}
		for (int i = 0; i < m.size(); i++) {
			Rect r = m.get(i);
			assertTrue(r.x() >= 0 && r.y() >= 0 && r.x() + r.w() <= area.w() && r.y() + r.h() <= area.h(), r + " inside " + area);
			assertTrue(r.w() >= 24 && r.h() >= 14, r + " bigger than in a column");
			assertEquals(BUSY.get(i).w() * fit.scale(), r.w(), 1.01, "one scale for x");
			assertEquals(BUSY.get(i).h() * fit.scale(), r.h(), 1.01, "and the same for y");
			for (int j = i + 1; j < m.size(); j++) {
				assertFalse(overlap(r, m.get(j)), r + " overlaps " + m.get(j));
			}
		}
	}
}
