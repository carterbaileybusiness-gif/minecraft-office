package local.carter.office.client.screens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import local.carter.office.client.platform.Platform.Rect;

/** The map's scale-to-fit: everything inside the smallest map area (160 × 64), proportional, centred, no overlaps. */
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
}
