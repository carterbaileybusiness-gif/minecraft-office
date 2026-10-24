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

	/** The Friends tab at the Task 9 sizes and more: header, 8 rows and pager inside the right page, nothing overlapping. */
	private static void assertFriendsFit(int w, int h) {
		String at = w + "x" + h;
		GrimoireLayout.Layout l = GrimoireLayout.layout(w, h);
		GrimoireLayout.Friends f = l.friends();
		Rect page = l.rightPage();
		assertTrue(inside(page, f.header()), at + ": header " + f.header() + " outside " + page);
		assertTrue(f.header().h() >= 9 + 2, at + ": header holds a line");
		List<Rect> rows = f.rows();
		assertEquals(GrimoireLayout.FRIEND_ROWS, rows.size(), at);
		for (int i = 0; i < rows.size(); i++) {
			Rect r = rows.get(i);
			assertTrue(inside(page, r), at + ": friend row " + i + " " + r + " outside " + page);
			assertTrue(r.h() >= 12, at + ": friend row " + i + " is " + r.h() + " high (a head and a name need 12)");
			assertTrue(r.y() >= f.header().bottom(), at + ": row " + i + " under the header");
			for (int j = i + 1; j < rows.size(); j++) assertFalse(r.overlaps(rows.get(j)), at + ": friend rows " + i + " and " + j);
		}
		for (Rect arrow : List.of(f.prev(), f.next())) {
			assertTrue(inside(f.header(), arrow), at + ": pager arrow " + arrow + " in the header");
			assertTrue(arrow.w() >= 9, at + ": arrow big enough to click");
		}
		assertFalse(f.prev().overlaps(f.next()), at + ": arrows overlap");
		assertTrue(f.next().x() - f.prev().right() >= 18, at + ": room for the page number between the arrows");
		// The requests switch sits at the bottom of the left page, under the state line.
		assertTrue(inside(l.leftPage(), f.toggle()), at + ": switch " + f.toggle());
		assertTrue(f.toggle().y() > l.xpTextY() + 9, at + ": switch under the state line");
		// Tabs: on screen, on the book's left edge, apart; the book stays centred.
		assertEquals(GrimoireLayout.TABS, l.tabs().size());
		for (int i = 0; i < l.tabs().size(); i++) {
			Rect t = l.tabs().get(i);
			assertTrue(t.insideScreen(w, h, MARGIN), at + ": tab " + i + " " + t);
			assertTrue(t.x() < l.book().x() && t.right() > l.book().x(), at + ": tab " + i + " sticks out of the left edge");
			assertTrue(t.bottom() <= l.book().bottom(), at + ": tab " + i + " beside the book");
			assertEquals(i, l.tabAt(t.x() + 1, t.cy()), at + ": tabAt");
			if (i > 0) assertFalse(t.overlaps(l.tabs().get(i - 1)), at + ": tabs overlap");
		}
		assertTrue(Math.abs(l.book().cx() - w / 2) <= 1, at + ": book centred");
	}

	@Test
	void friendsPageFits() {
		for (int[] wh : SIZES) assertFriendsFit(wh[0], wh[1]);
		for (int[] wh : MORE) assertFriendsFit(wh[0], wh[1]);
	}

	/** Spells, Friends, How to cast, Skill tree: every tab on screen, apart, top to bottom, beside the book. */
	@Test
	void everyTabHasATooltip() {
		assertEquals(GrimoireLayout.TABS, GrimoireLayout.TAB_TIPS.length);
	}

	@Test
	void fourTabsFit() {
		List<int[]> all = new java.util.ArrayList<>(List.of(SIZES));
		all.addAll(List.of(MORE));
		for (int[] wh : all) {
			String at = wh[0] + "x" + wh[1];
			GrimoireLayout.Layout l = GrimoireLayout.layout(wh[0], wh[1]);
			assertEquals(4, GrimoireLayout.TABS, "Spells, Friends, How to cast, Skill tree");
			assertEquals(4, l.tabs().size(), at);
			for (int i = 0; i < 4; i++) {
				Rect t = l.tabs().get(i);
				assertTrue(t.insideScreen(wh[0], wh[1], 0), at + ": tab " + i + " " + t);
				assertTrue(t.bottom() < l.book().bottom(), at + ": tab " + i + " above the book's bottom");
				for (int j = i + 1; j < 4; j++) assertFalse(t.overlaps(l.tabs().get(j)), at + ": tabs " + i + " and " + j + " overlap");
				if (i > 0) assertTrue(t.y() > l.tabs().get(i - 1).y(), at + ": tabs top to bottom");
			}
		}
	}

	@Test
	void friendRowAtFindsRows() {
		GrimoireLayout.Layout l = GrimoireLayout.layout(640, 360);
		for (int i = 0; i < GrimoireLayout.FRIEND_ROWS; i++) {
			Rect r = l.friends().rows().get(i);
			assertEquals(i, l.friends().rowAt(r.cx(), r.cy()));
		}
		assertEquals(-1, l.friends().rowAt(l.friends().header().cx(), l.friends().header().cy()));
		assertEquals(-1, l.tabAt(l.book().cx(), l.book().cy()));
	}
}
