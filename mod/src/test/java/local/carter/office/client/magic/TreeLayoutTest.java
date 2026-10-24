package local.carter.office.client.magic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import local.carter.office.client.magic.screen.Disc;
import local.carter.office.client.magic.screen.GrimoireLayout;
import local.carter.office.client.magic.screen.SignLayout.Rect;
import local.carter.office.client.magic.screen.TreeLayout;
import local.carter.office.client.magic.screen.TreeLayout.Node;
import local.carter.office.client.magic.screen.UiKit;
import local.carter.office.magic.core.Element;

/**
 * The Skill tree fits the book at every size (GUI units), 854 × 480 at its largest GUI scale (427 × 240) included: five columns,
 * icons and nodes inside the left page and apart, the split nodes inside their column, the button inside the right page,
 * text at the vanilla size or above, nothing scrolling.
 */
class TreeLayoutTest {
	private static final int[][] SIZES = {{320, 240}, {427, 240}, {480, 270}, {640, 360}, {854, 480}, {960, 540}, {1280, 720}, {1920, 1080},
		{400, 400}, {854, 240}, {3840, 2160}};

	private static boolean inside(Rect outer, float x, float y, float r) {
		return x - r >= outer.x() && x + r <= outer.right() && y - r >= outer.y() && y + r <= outer.bottom();
	}

	private static boolean inside(Rect outer, Rect inner) {
		return inner.x() >= outer.x() && inner.y() >= outer.y() && inner.right() <= outer.right() && inner.bottom() <= outer.bottom();
	}

	@Test
	void fitsAtEverySize() {
		for (int[] wh : SIZES) {
			String at = wh[0] + "x" + wh[1];
			GrimoireLayout.Layout g = GrimoireLayout.layout(wh[0], wh[1]);
			TreeLayout.Layout l = TreeLayout.of(g);
			assertTrue(l.unit() >= 1f, at + ": text never below the vanilla size");
			assertEquals(TreeLayout.COLS, l.columns().size());
			assertEquals(TreeLayout.COLS * 7, l.nodes().size(), at + ": 3 bumps + 2 split ranks per column");
			for (int c = 0; c < TreeLayout.COLS; c++) {
				Rect col = l.columns().get(c);
				assertTrue(inside(l.left(), col), at + ": column " + c);
				assertTrue(inside(col, l.icons().get(c)), at + ": icon " + c + " in its column");
				assertTrue(l.icons().get(c).w() >= 8, at + ": icon size");
				int rankBottom = l.rankY().get(c) + Math.round(TreeLayout.LINE * l.unit());
				assertTrue(rankBottom <= l.node(c, 1, 0).cy() - l.node(c, 1, 0).r(), at + ": the rank line above the first node");
				if (c > 0) assertFalse(col.overlaps(l.columns().get(c - 1)), at + ": columns overlap");
			}
			List<Node> nodes = l.nodes();
			for (int i = 0; i < nodes.size(); i++) {
				Node n = nodes.get(i);
				assertTrue(n.r() >= 2.5f, at + ": node radius " + n.r());
				assertTrue(inside(l.columns().get(n.col()), n.cx(), n.cy(), n.r()), at + ": node " + n + " in its column");
				assertTrue(n.cy() + n.r() <= l.repick().y(), at + ": node above the Re-pick switch");
				for (int j = i + 1; j < nodes.size(); j++) {
					Node m = nodes.get(j);
					double d = Math.hypot(n.cx() - m.cx(), n.cy() - m.cy());
					assertTrue(d > n.r() + m.r(), at + ": nodes " + n + " and " + m + " touch");
				}
			}
			assertTrue(inside(l.right(), l.button()), at + ": button in the right page");
			assertTrue(l.button().h() >= 9 * l.unit() + 4, at + ": the button holds a line");
			assertTrue(inside(l.left(), l.repick()), at + ": Re-pick switch in the left page");
			assertTrue(l.repick().h() >= 9 * l.unit(), at + ": the switch holds a line");
			// The detail panel always holds a title, a subtitle, a fork's two stacked cards at their smallest (name and one line),
			// the cost and the button at text scale 1 × unit; from 427 × 240 (854 × 480 at its largest GUI scale) the cards keep two
			// lines and a change each.
			float least = (10 + 10 + 2 * (10 + 10 + 4) + 2 + 10 + 16 + 8) * l.unit();
			assertTrue(l.panel().h() >= least, at + ": panel " + l.panel().h() + " < " + least);
			float full = (10 + 10 + 2 * (10 + 20 + 10 + 4) + 10 + 16 + 8) * l.unit();
			if (wh[0] >= 427) assertTrue(l.panel().h() >= full, at + ": panel " + l.panel().h() + " < " + full);
		}
	}

	@Test
	void hitTestsFindNodesAndIcons() {
		TreeLayout.Layout l = TreeLayout.of(GrimoireLayout.layout(640, 360));
		for (Node n : l.nodes()) assertEquals(n, l.nodeAt(n.cx(), n.cy()), "node at its centre");
		Rect icon = l.icons().get(3);
		assertEquals(3, l.iconAt(icon.cx(), icon.cy()));
		assertNull(l.nodeAt(l.right().cx(), l.right().cy()), "nothing on the right page");
		assertEquals(-1, l.iconAt(l.right().cx(), l.right().cy()));
		assertTrue(l.node(2, 3, 1).cx() < l.node(2, 3, 2).cx(), "A left of B");
		assertEquals(l.node(2, 3, 1).cy(), l.node(2, 3, 2).cy(), "a split rank's options side by side");
	}

	/** Every element's text on the page and the cards, and the white button label on its body, read at 4.5:1 or better. */
	@Test
	void elementColoursReadOnTheBook() {
		int page = 0xFFF8EED0, card = 0xFFF3E8CC;
		for (Element e : Element.values()) {
			int ink = UiKit.inkOf(e.argb());
			for (int bg : new int[] {page, card, UiKit.mix(card, e.argb(), 0.14f)}) {
				assertTrue(UiKit.contrast(UiKit.readable(ink, bg), bg) >= 4.5, e + " text on " + Integer.toHexString(bg));
			}
			assertTrue(UiKit.contrast(UiKit.WHITE, UiKit.deepen(e.argb())) >= 4.5, e + " button label");
		}
		assertTrue(UiKit.contrast(UiKit.INK_SOFT, page) >= 4.5, "soft ink on the page");
	}

	@Test
	void discSpans() {
		assertEquals(5f, Disc.halfWidth(5, 0), 1.0E-6);
		assertEquals(0f, Disc.halfWidth(5, 5), 1.0E-6);
		assertEquals(0f, Disc.halfWidth(5, 7), 1.0E-6);
		assertEquals(4f, Disc.halfWidth(5, 3), 1.0E-6);
	}
}
