package local.carter.office.client.magic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import local.carter.office.client.magic.screen.ChoiceLayout;
import local.carter.office.client.magic.screen.SignLayout.Rect;

class ChoiceLayoutTest {
	private static final int MARGIN = 4;
	private static final int[][] SIZES = {{320, 240}, {427, 240}, {640, 360}, {1920, 1080}};
	/** More shapes than the brief's four: tall, square, ultra-wide and the common Mac GUI 6 / 1080p GUI 4 sizes. */
	private static final int[][] MORE = {{320, 480}, {400, 400}, {854, 240}, {480, 270}, {480, 300}, {960, 540}, {3840, 2160}};

	private static void assertFits(int w, int h, float orbit) {
		String at = w + "x" + h + " orbit " + orbit;
		ChoiceLayout.Layout l = ChoiceLayout.layout(w, h, orbit);
		List<Rect> sigils = l.sigils();
		assertEquals(6, sigils.size(), at);
		Rect book = l.book();
		assertTrue(book.insideScreen(w, h, MARGIN), at + ": book " + book);
		for (int i = 0; i < 6; i++) {
			Rect s = sigils.get(i);
			assertEquals(l.sigilSize(), s.w(), at);
			assertEquals(l.sigilSize(), s.h(), at);
			assertTrue(s.insideScreen(w, h, MARGIN), at + ": sigil " + i + " " + s);
			assertFalse(s.overlaps(book), at + ": sigil " + i + " " + s + " overlaps the book " + book);
			for (int j = i + 1; j < 6; j++) assertFalse(s.overlaps(sigils.get(j)), at + ": sigils " + i + " and " + j);
			// Hovered (1.25×) it still stays on screen and off the book.
			Rect big = l.hovered(i);
			assertTrue(big.insideScreen(w, h, 2), at + ": hovered sigil " + i + " " + big);
			assertFalse(big.overlaps(book), at + ": hovered sigil " + i + " overlaps the book");
		}
	}

	@Test
	void sigilsFitAndNeverTouchTheBook() {
		for (int[] wh : SIZES) {
			for (float orbit : new float[] {0f, 37f}) assertFits(wh[0], wh[1], orbit);
		}
	}

	@Test
	void fitsAtEveryOrbitAngleAndShape() {
		for (int[][] set : new int[][][] {SIZES, MORE}) {
			for (int[] wh : set) {
				for (int deg = 0; deg < 360; deg += 3) assertFits(wh[0], wh[1], deg);
			}
		}
	}

	@Test
	void bookIsHalfTheShortSideAndCentred() {
		for (int[] wh : SIZES) {
			ChoiceLayout.Layout l = ChoiceLayout.layout(wh[0], wh[1], 0f);
			assertEquals(Math.round(0.5f * Math.min(wh[0], wh[1])), l.book().w(), wh[0] + "x" + wh[1]);
			assertEquals(Math.round(l.book().w() * 128f / 192f), l.book().h());
			assertEquals(wh[0] / 2, l.book().cx(), 1);
			assertEquals(wh[1] / 2, l.book().cy(), 1);
		}
	}

	@Test
	void sigilsOrbitTheEllipseAndKeepTheirSize() {
		int w = 640, h = 360;
		ChoiceLayout.Layout a = ChoiceLayout.layout(w, h, 0f);
		ChoiceLayout.Layout b = ChoiceLayout.layout(w, h, 90f);
		assertEquals(a.sigilSize(), b.sigilSize(), "size does not pulse while orbiting");
		// Sigil 0 starts at the top of the ellipse (rx 0.40 w, ry 0.34 h).
		Rect top = a.sigils().get(0);
		assertEquals(w / 2.0, top.x() + top.w() / 2.0, 1.0);
		assertEquals(h / 2.0 - 0.34 * h, top.y() + top.h() / 2.0, 1.0);
		// A quarter turn later it sits on the right end.
		Rect right = b.sigils().get(0);
		assertEquals(w / 2.0 + 0.40 * w, right.x() + right.w() / 2.0, 1.0);
		assertEquals(h / 2.0, right.y() + right.h() / 2.0, 1.0);
	}

	@Test
	void sigilsGrowWithTheScreen() {
		int small = ChoiceLayout.layout(320, 240, 0f).sigilSize();
		int big = ChoiceLayout.layout(1920, 1080, 0f).sigilSize();
		assertTrue(small >= 16, "small screen sigil " + small);
		assertTrue(big > small * 3, "big " + big + " vs small " + small);
	}

	@Test
	void hitTestFindsTheSigilUnderTheCursor() {
		ChoiceLayout.Layout l = ChoiceLayout.layout(640, 360, 20f);
		for (int i = 0; i < 6; i++) {
			Rect s = l.sigils().get(i);
			assertEquals(i, l.sigilAt(s.cx(), s.cy()), "centre of sigil " + i);
		}
		assertEquals(-1, l.sigilAt(320, 180), "the book centre is no sigil");
	}

	@Test
	void pagesSitInsideTheBook() {
		for (int[] wh : SIZES) {
			ChoiceLayout.Layout l = ChoiceLayout.layout(wh[0], wh[1], 0f);
			assertTrue(inside(l.book(), l.leftPage()) && inside(l.book(), l.rightPage()), wh[0] + "x" + wh[1]);
			assertFalse(l.leftPage().overlaps(l.rightPage()));
		}
	}

	@Test
	void confirmButtonsFitBelowTheBookOnScreen() {
		for (int[][] set : new int[][][] {SIZES, MORE}) {
			for (int[] wh : set) {
				ChoiceLayout.Layout l = ChoiceLayout.layout(wh[0], wh[1], 0f);
				String at = wh[0] + "x" + wh[1];
				assertTrue(l.bind().insideScreen(wh[0], wh[1], MARGIN), at + " bind " + l.bind());
				assertTrue(l.back().insideScreen(wh[0], wh[1], MARGIN), at + " back " + l.back());
				assertFalse(l.bind().overlaps(l.back()), at);
				assertFalse(l.bind().overlaps(l.book()) || l.back().overlaps(l.book()), at + " buttons off the book");
				assertTrue(l.bind().h() >= 14, at + " button height " + l.bind().h());
			}
		}
	}

	private static boolean inside(Rect outer, Rect inner) {
		return inner.x() >= outer.x() && inner.y() >= outer.y() && inner.right() <= outer.right() && inner.bottom() <= outer.bottom();
	}
}
