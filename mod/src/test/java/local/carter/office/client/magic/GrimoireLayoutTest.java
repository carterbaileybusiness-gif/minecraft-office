package local.carter.office.client.magic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import local.carter.office.client.magic.screen.GrimoireLayout;
import local.carter.office.client.magic.screen.SignLayout.Rect;

class GrimoireLayoutTest {
	private static final int MARGIN = 4;
	private static final int[][] SIZES = {{320, 240}, {427, 240}, {640, 360}, {1920, 1080}};
	private static final int[][] MORE = {{320, 480}, {400, 400}, {854, 240}, {480, 270}, {480, 300}, {960, 540}, {3840, 2160}};

	private static boolean inside(Rect outer, Rect inner) {
		return inner.x() >= outer.x() && inner.y() >= outer.y() && inner.right() <= outer.right() && inner.bottom() <= outer.bottom();
	}

	private static void assertFits(int w, int h) {
		String at = w + "x" + h;
		GrimoireLayout.Layout l = GrimoireLayout.layout(w, h);
		assertTrue(l.book().insideScreen(w, h, MARGIN), at + ": book " + l.book());
		assertTrue(inside(l.book(), l.leftPage()), at + ": left page in the book");
		assertTrue(inside(l.book(), l.rightPage()), at + ": right page in the book");
		assertFalse(l.leftPage().overlaps(l.rightPage()), at + ": pages overlap");
		List<Rect> rows = l.rows();
		assertEquals(6, rows.size(), at);
		for (int i = 0; i < 6; i++) {
			Rect r = rows.get(i);
			assertTrue(inside(l.rightPage(), r), at + ": row " + i + " " + r + " outside " + l.rightPage());
			assertTrue(r.h() >= 16, at + ": row " + i + " is " + r.h() + " high");
			for (int j = i + 1; j < 6; j++) assertFalse(r.overlaps(rows.get(j)), at + ": rows " + i + " and " + j);
			if (i > 0) assertTrue(r.y() > rows.get(i - 1).y(), at + ": rows top to bottom");
		}
		// Footer: on screen, below the book, at least two text lines high.
		Rect f = l.footer();
		assertTrue(f.insideScreen(w, h, MARGIN), at + ": footer " + f);
		assertFalse(f.overlaps(l.book()), at + ": footer overlaps the book");
		assertTrue(f.y() >= l.book().bottom(), at + ": footer below the book");
		assertTrue(f.h() >= 2 * 9 + 2, at + ": footer holds two lines");
		// Left page pieces stack inside the page.
		assertTrue(inside(l.leftPage(), l.sigil()), at + ": sigil " + l.sigil());
		assertTrue(inside(l.leftPage(), l.xpBar()), at + ": xp bar " + l.xpBar());
		assertTrue(l.xpBar().y() > l.sigil().bottom(), at + ": xp bar under the sigil");
		assertTrue(l.unit() >= 1f, at + ": text unit " + l.unit());
	}

	@Test
	void rowsFitTheirPageAndTheFooterFits() {
		for (int[] wh : SIZES) assertFits(wh[0], wh[1]);
	}

	@Test
	void fitsOtherShapes() {
		for (int[] wh : MORE) assertFits(wh[0], wh[1]);
	}

	@Test
	void bookKeepsTheTextureAspectAndGrowsWithTheScreen() {
		GrimoireLayout.Layout small = GrimoireLayout.layout(320, 240);
		GrimoireLayout.Layout big = GrimoireLayout.layout(1920, 1080);
		for (GrimoireLayout.Layout l : List.of(small, big)) {
			assertEquals(l.book().w() * 128.0 / 192.0, l.book().h(), 1.0);
		}
		assertTrue(big.book().w() > 2 * small.book().w());
		assertTrue(big.unit() >= 2f, "big screens draw text at 2x: " + big.unit());
	}

	@Test
	void rowAtFindsRows() {
		GrimoireLayout.Layout l = GrimoireLayout.layout(640, 360);
		for (int i = 0; i < 6; i++) {
			Rect r = l.rows().get(i);
			assertEquals(i, l.rowAt(r.cx(), r.cy()));
		}
		assertEquals(-1, l.rowAt(l.leftPage().cx(), l.leftPage().cy()));
	}
}
