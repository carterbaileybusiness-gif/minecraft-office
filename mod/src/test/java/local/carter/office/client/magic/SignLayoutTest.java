package local.carter.office.client.magic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import local.carter.office.client.magic.screen.SignLayout;
import local.carter.office.client.magic.screen.SignLayout.Layout;
import local.carter.office.client.magic.screen.SignLayout.Rect;

class SignLayoutTest {
	private static final int MARGIN = 4;

	private static void assertFits(int w, int h, int length, Layout l) {
		String at = w + "x" + h + " length " + length;
		assertEquals(length, l.arrows().size(), at);
		List<Rect> arrows = l.arrows();
		for (int i = 0; i < arrows.size(); i++) {
			Rect a = arrows.get(i);
			assertTrue(a.insideScreen(w, h, MARGIN), at + ": arrow " + i + " " + a);
			assertEquals(a.w(), a.h(), at + ": square arrows");
			assertTrue(a.w() >= SignLayout.MIN_ARROW, at + ": arrow size " + a.w());
			for (int j = i + 1; j < arrows.size(); j++) assertFalse(a.overlaps(arrows.get(j)), at + ": arrows " + i + " and " + j);
			assertFalse(a.overlaps(l.timer()), at + ": arrow " + i + " and the timer");
		}
		assertTrue(l.timer().insideScreen(w, h, MARGIN), at + ": timer " + l.timer());
		assertTrue(l.timer().h() > 0 && l.timer().w() > 0, at + ": timer size");
		assertTrue(l.panel().insideScreen(w, h, MARGIN), at + ": panel " + l.panel());
		for (Rect a : arrows) assertTrue(contains(l.panel(), a), at + ": arrow inside the panel");
		assertTrue(contains(l.panel(), l.timer()), at + ": timer inside the panel");
		// Text lines sit inside the panel, title above the arrows, hint below the timer.
		assertTrue(l.nameY() >= l.panel().y() && l.titleY() > l.nameY(), at + ": name/title order");
		assertTrue(l.titleY() + SignLayout.LINE * l.titleScale() <= arrows.get(0).y(), at + ": title above the arrows");
		assertTrue(l.hintY() >= l.timer().bottom() && l.hintY() + SignLayout.LINE <= l.panel().bottom(), at + ": hint below the timer");
		assertTrue(l.innerWidth() <= l.panel().w(), at + ": inner width inside the panel");
	}

	/** Every text line, as drawn, fits the panel's inner width. */
	private static void assertTextFits(String at, Layout l, int titleWidth, int lineWidth) {
		assertTrue(lineWidth * l.fit(lineWidth) <= l.innerWidth() + 1e-3, at + ": line " + lineWidth + " × " + l.fit(lineWidth)
				+ " in " + l.innerWidth());
		assertTrue(titleWidth * l.titleScale() <= l.innerWidth() + 1e-3, at + ": title " + titleWidth + " × " + l.titleScale()
				+ " in " + l.innerWidth());
		assertTrue(l.fit(lineWidth) > 0f && l.titleScale() > 0f, at + ": text still drawn");
	}

	private static boolean contains(Rect outer, Rect inner) {
		return inner.x() >= outer.x() && inner.y() >= outer.y() && inner.right() <= outer.right() && inner.bottom() <= outer.bottom();
	}

	@Test
	void fitsTheSmallestAndALargeScreen() {
		for (int[] wh : new int[][] {{320, 240}, {1920, 1080}}) {
			for (int length : new int[] {4, 6}) {
				assertFits(wh[0], wh[1], length, SignLayout.layout(wh[0], wh[1], length));
			}
		}
	}

	@Test
	void fitsEveryLengthAtManySizesWithText() {
		int[][] sizes = {{320, 240}, {427, 240}, {480, 270}, {640, 360}, {960, 540}, {1280, 720}, {1920, 1080}, {3840, 2160}, {300, 200}};
		for (int[] wh : sizes) {
			for (int length = 1; length <= 6; length++) {
				Layout l = SignLayout.layout(wh[0], wh[1], length, 80, 190);
				assertFits(wh[0], wh[1], length, l);
				assertTextFits(wh[0] + "x" + wh[1], l, 80, 190);
			}
		}
	}

	@Test
	void arrowsAreCentredAndGrowWithTheScreen() {
		Layout small = SignLayout.layout(320, 240, 6);
		Layout big = SignLayout.layout(1920, 1080, 6);
		assertTrue(big.arrowSize() > small.arrowSize(), small.arrowSize() + " vs " + big.arrowSize());
		assertTrue(big.arrowSize() <= SignLayout.MAX_ARROW);
		List<Rect> a = big.arrows();
		int left = a.get(0).x();
		int right = 1920 - a.get(a.size() - 1).right();
		assertTrue(Math.abs(left - right) <= 1, "centred: " + left + " vs " + right);
		for (int i = 1; i < a.size(); i++) assertEquals(a.get(0).y(), a.get(i).y(), "one row");
	}

	@Test
	void bigTitleOnlyWhenItFits() {
		assertEquals(2f, SignLayout.layout(1920, 1080, 6, 80, 190).titleScale());
		assertEquals(1f, SignLayout.layout(320, 240, 6, 80, 190).titleScale());
		assertEquals(1f, SignLayout.layout(320, 240, 6, 80, 190).fit(190));
	}

	@Test
	void longTranslationsShrinkInsideThePanel() {
		// GUI scale 4 on a narrow window: a 1280 × 960 window is 320 × 240 GUI units, 1200 × 1000 is 300 × 250, and a tiny
		// 852 × 960 window is 213 × 240; the hint and title are long translations (wider than the whole screen).
		int[][] sizes = {{320, 240}, {300, 250}, {213, 240}};
		for (int[] wh : sizes) {
			for (int length : new int[] {4, 6}) {
				Layout l = SignLayout.layout(wh[0], wh[1], length, 260, 420);
				String at = wh[0] + "x" + wh[1] + " length " + length;
				assertFits(wh[0], wh[1], length, l);
				assertTextFits(at, l, 260, 420);
				assertTrue(l.fit(420) < 1f, at + ": the long hint shrinks");
				assertTrue(l.innerWidth() <= wh[0] - 2 * MARGIN, at + ": inner width inside the screen");
			}
		}
	}
}
