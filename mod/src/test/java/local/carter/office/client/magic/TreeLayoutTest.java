package local.carter.office.client.magic;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import local.carter.office.client.magic.screen.Disc;
import local.carter.office.client.magic.screen.GrimoireLayout;
import local.carter.office.client.magic.screen.SignLayout.Rect;
import local.carter.office.client.magic.screen.TreeLayout;
import local.carter.office.client.magic.screen.TreeLayout.Node;
import local.carter.office.client.magic.screen.TreePanel;
import local.carter.office.client.magic.screen.UiKit;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.tree.Fork;
import local.carter.office.magic.tree.RankCost;
import local.carter.office.magic.tree.SpellRank;
import local.carter.office.magic.tree.SpellTree;
import local.carter.office.magic.tree.Trees;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;

/**
 * The Skill tree fits the book at every size (GUI units), 320 × 240, 427 × 240 (854 × 480 at its largest GUI scale) and 480 × 270
 * (1080p on auto GUI scale) included: five columns, icons and nodes inside the left page and apart, the split nodes inside their
 * column, the legend and the coach hint under them, the button inside the right page, text at the vanilla size or above, nothing
 * scrolling; and the detail panel holds the worst case of its real strings at every size ({@link TreePanel}).
 */
class TreeLayoutTest {
	private static final int[][] SIZES = {{320, 240}, {427, 240}, {480, 270}, {640, 360}, {854, 480}, {960, 540}, {1280, 720}, {1920, 1080},
		{400, 400}, {854, 240}, {3840, 2160}};

	@BeforeAll
	static void boot() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap(); // the trees name status effects, the costs items
	}

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
			// The detail panel's content is budgeted with its real strings in panelFitsTheWorstCaseAtEverySize.
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

	// ---- The detail panel: the worst case of the real strings, budgeted at every size ----

	/**
	 * Vanilla's default font widths (advance in pixels, scale 1), close enough to budget with: most glyphs 6, narrow ones less,
	 * anything outside ASCII 8 (wider than any vanilla glyph that we use, so the budget errs wide).
	 */
	static int width(String s) {
		int w = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			w += switch (c) {
				case 'i', '!', '.', ',', ':', ';', '|', '\'' -> 2;
				case 'l', '`' -> 3;
				case 't', 'I', '[', ']', ' ' -> 4;
				case 'f', 'k', '<', '>', '{', '}', '(', ')', '*', '"' -> 5;
				case '@', '~' -> 7;
				default -> c < 128 ? 6 : 8;
			};
		}
		return w;
	}

	private static JsonObject lang() throws Exception {
		try (InputStream in = TreeLayoutTest.class.getResourceAsStream("/assets/office/lang/en_us.json")) {
			assertNotNull(in);
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	private static String t(JsonObject lang, String key, Object... args) {
		String v = lang.get(key).getAsString().replace("%%", "\u0000");
		for (Object a : args) v = v.replaceFirst("%s", java.util.regex.Matcher.quoteReplacement(String.valueOf(a)));
		return v.replace("\u0000", "%");
	}

	/** The longest of {@code options}, by the font. */
	private static String longest(List<String> options) {
		String best = "";
		for (String s : options) if (width(s) > width(best)) best = s;
		return best;
	}

	/** An item's English name from its id ("blaze_powder" → "Blaze Powder"). */
	private static String itemName(Item item) {
		String path = BuiltInRegistries.ITEM.getKey(item).getPath();
		StringBuilder out = new StringBuilder();
		for (String w : path.split("_")) out.append(out.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
		return out.toString();
	}

	/** Every stat line a tree shows, at its widest numbers, plus mana and cooldown. */
	private static List<TreePanel.Row> worstRows(JsonObject lang, SpellTree tree) {
		List<TreePanel.Row> rows = new ArrayList<>();
		for (SpellTree.Stat s : tree.stats()) rows.add(new TreePanel.Row(t(lang, "stat.office." + s.key()), "99.9 s", "99.9 s"));
		rows.add(new TreePanel.Row(t(lang, "stat.office.mana"), "60", "54"));
		rows.add(new TreePanel.Row(t(lang, "stat.office.cooldown"), "30 s", "12 s"));
		return rows;
	}

	/**
	 * The panel for every spell and rank with its longest strings (the longest subtitle, every stat line changing, the longest
	 * cost rows of the element and slot, the longest button text or refusal), at every size: it fits, nothing is drawn wider than
	 * its room, the cards keep their names and a line, and from 427 × 240 (854 × 480 at its largest GUI scale) they show a number.
	 */
	@Test
	void panelFitsTheWorstCaseAtEverySize() throws Exception {
		JsonObject lang = lang();
		for (int[] wh : SIZES) {
			String at = wh[0] + "x" + wh[1];
			TreeLayout.Layout l = TreeLayout.of(GrimoireLayout.layout(wh[0], wh[1]));
			float u = l.unit();
			Rect p = l.panel();
			for (SpellTree tree : Trees.all()) {
				Element e = elementOf(tree);
				for (int rank = 1; rank <= 5; rank++) {
					String r = SpellRank.roman(rank);
					boolean fork = TreeLayout.isFork(rank);
					List<String> subs = new ArrayList<>();
					for (String k : List.of("sub_bump", "sub_fork", "sub_owned", "sub_picked", "sub_other", "sub_repick")) subs.add(t(lang, "tree.office." + k, r));
					subs.add(t(lang, "tree.office.unlock_at", 10));
					List<String> pcts = new ArrayList<>();
					for (SpellTree.Stat s : tree.stats()) pcts.add(t(lang, "tree.office.improve", "+15", t(lang, "stat.office." + s.key()).toLowerCase()));
					List<TreePanel.Card> cards = null;
					List<String> buttons = new ArrayList<>(List.of(t(lang, "tree.office.rank_up"), t(lang, "tree.office.repick_first"),
							t(lang, "tree.office.repick_need"), t(lang, "tree.office.need_levels", 15)));
					if (fork) {
						cards = new ArrayList<>();
						for (Fork f : tree.forks(rank)) {
							String name = t(lang, "fork.office." + tree.spell().getPath() + "." + f.name());
							cards.add(new TreePanel.Card(name + t(lang, "tree.office.picked_mark"), t(lang, "fork.office." + tree.spell().getPath() + "." + f.name() + ".desc"),
									worstRows(lang, tree)));
							buttons.add(t(lang, "tree.office.choose", name));
							buttons.add(t(lang, "tree.office.repick_button", name));
						}
					}
					String milestone = null;
					if (rank < 5) {
						int next = rank < 3 ? 3 : 5;
						List<Fork> fs = tree.forks(next);
						milestone = t(lang, "tree.office.milestone", SpellRank.roman(next), t(lang, "fork.office." + tree.spell().getPath() + "." + fs.get(0).name()),
								t(lang, "fork.office." + tree.spell().getPath() + "." + fs.get(1).name()));
					}
					RankCost.Cost cost = RankCost.of(e, tree.ultimate(), rank);
					List<TreePanel.Cost> costs = new ArrayList<>();
					for (RankCost.Line ln : cost.lines()) {
						String name = longest(ln.items().stream().map(TreeLayoutTest::itemName).toList());
						buttons.add(t(lang, "tree.office.need_items", ln.count(), name));
						costs.add(new TreePanel.Cost(true, Integer.toString(ln.count()), name, t(lang, "tree.office.have", 999), false));
					}
					costs.add(new TreePanel.Cost(true, Integer.toString(cost.levels()), t(lang, "tree.office.levels_word"), t(lang, "tree.office.have", 999), false));
					String spellName = t(lang, "spell.office." + tree.spell().getPath());
					String desc = t(lang, "spell.office." + tree.spell().getPath() + ".desc");
					for (boolean note : new boolean[] {false, true}) {
						TreePanel.Content c = new TreePanel.Content(spellName, longest(subs), fork ? null : String.join(", ", pcts), desc,
								fork ? List.of() : worstRows(lang, tree), cards, milestone, note ? List.of() : costs,
								note ? t(lang, "tree.office.repick_warn") : null, longest(buttons));
						check(at + " " + tree.spell().getPath() + " " + r + (note ? " (re-pick note)" : ""), l, p, u, c, wh[0] >= 427 && wh[1] >= 240);
					}
				}
			}
		}
	}

	private static Element elementOf(SpellTree tree) {
		for (Element e : Element.values()) for (int s = 0; s < 5; s++) if (SpellIds.at(e, s).equals(tree.spell())) return e;
		throw new IllegalStateException("no element for " + tree.spell());
	}

	private static void check(String at, TreeLayout.Layout l, Rect p, float u, TreePanel.Content c, boolean roomy) {
		TreePanel.Plan plan = TreePanel.plan(p, l.button(), u, c, TreeLayoutTest::width);
		assertTrue(plan.fits(), at + ": the least of the panel does not fit (" + plan.contentBottom() + " > " + plan.bottomTop() + ")");
		assertTrue(plan.contentBottom() <= plan.bottomTop() + 1.0E-3, at + ": the body runs into the costs");
		for (String s : List.of(plan.title(), plan.subtitle(), plan.improves(), plan.desc(), plan.milestone())) {
			assertTrue(width(s) * u <= p.w(), at + ": too wide: " + s);
		}
		assertTrue(width(plan.button()) * u <= l.button().w() - 2 * TreePanel.BUTTON_INSET * u, at + ": button label too wide: " + plan.button());
		for (TreePanel.CostRow row : plan.costs()) {
			float w = (TreePanel.ICON + 1) * u + (TreePanel.boldWidth(row.cost().need(), TreeLayoutTest::width) + width(" ") + width(row.name())
					+ width(" ") + width(row.cost().have())) * u;
			assertTrue(w <= p.w() + 1.0E-3, at + ": cost row too wide: " + row);
			assertTrue(row.y() >= plan.bottomTop() && row.y() + plan.line() <= l.button().y(), at + ": cost row out of place: " + row);
		}
		if (c.cards() != null) {
			assertEquals(2, plan.cards().size());
			TreePanel.CardBox a = plan.cards().get(0), b = plan.cards().get(1);
			for (TreePanel.CardBox k : plan.cards()) {
				float textW = k.w() - plan.cardInset() - plan.cardPad();
				assertTrue(k.x() >= p.x() && k.x() + k.w() <= p.right() + 1.0E-3 && k.y() >= p.y() && k.y() + k.h() <= plan.bottomTop() + 1.0E-3,
						at + ": card out of the panel: " + k);
				assertFalse(k.name().isEmpty(), at + ": a card keeps its name");
				assertTrue(width(k.name()) * u <= textW, at + ": card name too wide: " + k.name());
				assertFalse(k.desc().isEmpty(), at + ": a card keeps a line of its description");
				for (String ln : k.desc()) assertTrue(width(ln) * u <= textW, at + ": card line too wide: " + ln);
				if (roomy) assertTrue(k.rows() >= 1, at + ": from 427 × 240 a card shows a number: " + k);
				float need = 2 * plan.cardPad() + (1 + k.desc().size() + k.rows()) * plan.line();
				assertTrue(k.h() >= need - 1.0E-3, at + ": card shorter than its text: " + k);
			}
			boolean apart = plan.side() ? a.x() + a.w() <= b.x() : a.y() + a.h() <= b.y();
			assertTrue(apart, at + ": the cards overlap: " + a + " / " + b);
		} else {
			assertTrue(plan.tableRows() >= 1, at + ": a bump shows at least one number");
		}
		for (String ln : plan.note()) assertTrue(width(ln) * u <= p.w(), at + ": note line too wide: " + ln);
	}

	/** The legend's short words fit their cells with the mark, and the coach hint's place covers no node or icon. */
	@Test
	void legendAndCoachHintFit() throws Exception {
		JsonObject lang = lang();
		for (int[] wh : SIZES) {
			String at = wh[0] + "x" + wh[1];
			TreeLayout.Layout l = TreeLayout.of(GrimoireLayout.layout(wh[0], wh[1]));
			float u = l.unit();
			assertEquals(TreeLayout.LEGEND, l.legend().size());
			String[] keys = {"owned", "ready", "short", "locked"};
			for (int i = 0; i < TreeLayout.LEGEND; i++) {
				Rect cell = l.legend().get(i);
				assertTrue(inside(l.left(), cell), at + ": legend cell in the left page");
				assertTrue(cell.y() >= l.node(0, 5, 1).cy() + l.node(0, 5, 1).r(), at + ": legend under the nodes");
				assertTrue(cell.bottom() <= l.repick().y(), at + ": legend above the switch");
				float r = Math.min(3.5f * u, cell.h() * 0.32f);
				assertTrue(width(t(lang, "tree.office.legend_" + keys[i] + "_short")) * u <= cell.w() - (2 * r + 3 * u),

						at + ": the legend's short word fits: " + keys[i]);
			}
			Rect coach = l.coach();
			for (Node n : l.nodes()) assertTrue(n.cy() + n.r() <= coach.y(), at + ": the coach hint covers a node");
			for (Rect icon : l.icons()) assertFalse(icon.overlaps(coach), at + ": the coach hint covers an icon");
			assertTrue(inside(l.left(), coach), at + ": the coach hint on the left page");
		}
	}
}
