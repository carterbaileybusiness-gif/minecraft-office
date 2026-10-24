package local.carter.office.client.magic.screen;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.magic.screen.SignLayout.Rect;

/**
 * The Skill tree's detail panel, laid out from what it says (pure: GUI units, text widths from a {@link Measure}, so a unit test
 * budgets the real strings at every size). Top down: the spell's name; "Rank III of V · choose one"; for a bump, what the rank
 * improves ("+15 % damage, +15 % reach"); the spell's one-line description; the body (a before → after table, or a fork's two
 * cards); the next milestone ("Rank V: choose Lance or Flare"). Anchored at the bottom: one row per cost (or the re-pick note) and
 * the button, whose label says what it does or why it can't (cut with "…" when too long; the screen shows the whole text in a
 * tooltip, and every other cut text too).
 *
 * <p>Nothing is drawn over anything else. The body's least form always shows (a table row; each card's name and a line of its
 * description); then, while they fit, in this order: one number per card (two table rows), the description, the milestone, the
 * improvements line, and the rest of the body. Cards sit side by side when both names fit a half-width card of at least
 * {@link #SIDE_MIN} units, else stacked.
 */
public final class TreePanel {
	/** Width in vanilla font pixels (scale 1) of a string. */
	@FunctionalInterface
	public interface Measure {
		int width(String s);
	}

	public static final String ELLIPSIS = "…";
	/** Narrowest card (GUI units) that sits beside its sibling. */
	public static final float SIDE_MIN = 56;
	/** Most lines of a card's description, and most number rows a card shows. */
	public static final int CARD_DESC = 3;
	public static final int CARD_ROWS = 4;
	/** Font line pitch, and the item icon on a cost row, in text units. */
	public static final int LINE = 10;
	public static final int ICON = 9;
	/** The button's text inset each side, in text units. */
	public static final int BUTTON_INSET = 3;

	/** A before → after line ({@code before} and {@code after} empty for a plain note). */
	public record Row(String label, String before, String after) {}

	/** A fork option: its name, its one line, the numbers it changes. */
	public record Card(String name, String desc, List<Row> changes) {}

	/** One cost: the amount needed (drawn first, bold), what it is, and "(have n)" (green or red); {@code icon} for an item. */
	public record Cost(boolean icon, String need, String name, String have, boolean ok) {}

	/** Everything the panel says, already in the player's language ({@code cards} null for a bump, {@code note} instead of costs). */
	public record Content(String title, String subtitle, @Nullable String improves, @Nullable String desc, List<Row> table,
			@Nullable List<Card> cards, @Nullable String milestone, List<Cost> costs, @Nullable String note, String button) {}

	/** A card where it goes: its box, its (fitted) name, its description lines and how many number rows it shows. */
	public record CardBox(float x, float y, float w, float h, String name, List<String> desc, int rows) {}

	/** A cost row where it goes, its name cut to fit. */
	public record CostRow(float y, Cost cost, String name) {}

	/**
	 * The laid-out panel; a {@code …Y} of NaN means that part is left out. {@code contentBottom} is where the top part ends and
	 * {@code bottomTop} where the bottom block starts; {@code fits} is false only if even the least form overlaps it.
	 */
	public record Plan(float unit, int line, String title, float titleY, String subtitle, float subtitleY, String improves, float improvesY,
			String desc, float descY, float bodyY, int tableRows, boolean side, List<CardBox> cards, float cardPad, float cardInset,
			String milestone, float milestoneY, List<CostRow> costs, List<String> note, float noteY, String button, boolean buttonCut,
			float contentBottom, float bottomTop, boolean fits) {
		public static boolean shown(float y) {
			return !Float.isNaN(y);
		}
	}

	private TreePanel() {}

	/** {@code s} cut with "…" to fit {@code room} pixels at scale {@code u} (unchanged if it fits; "" if not even "…" does). */
	public static String fit(String s, float room, float u, Measure m) {
		if (m.width(s) * u <= room) return s;
		int dots = m.width(ELLIPSIS);
		if (dots * u > room) return "";
		int lo = 0, hi = s.length();
		while (lo < hi) {
			int mid = (lo + hi + 1) / 2;
			if ((m.width(s.substring(0, mid).stripTrailing()) + dots) * u <= room) lo = mid;
			else hi = mid - 1;
		}
		return s.substring(0, lo).stripTrailing() + ELLIPSIS;
	}

	/** {@code s} word-wrapped into at most {@code max} lines of {@code room} pixels at {@code u}; the last cut with "…" if more remain. */
	public static List<String> wrap(String s, float room, float u, int max, Measure m) {
		List<String> out = new ArrayList<>();
		if (max <= 0 || s.isEmpty()) return out;
		String[] words = s.split(" ");
		String line = "";
		int i = 0;
		while (i < words.length) {
			String next = line.isEmpty() ? words[i] : line + " " + words[i];
			if (m.width(next) * u <= room) {
				line = next;
				i++;
				continue;
			}
			if (out.size() == max - 1) {
				// The last line: what is left, cut with "…".
				StringBuilder rest = new StringBuilder(next);
				for (int j = i + 1; j < words.length; j++) rest.append(' ').append(words[j]);
				out.add(fit(rest.toString(), room, u, m));
				return out;
			}
			if (line.isEmpty()) {
				// One word wider than a line: it is cut.
				out.add(fit(words[i], room, u, m));
				i++;
			} else {
				out.add(line);
				line = "";
			}
		}
		if (!line.isEmpty()) out.add(line);
		return out;
	}

	/** Room (pixels) for a cost row's name: the row less its icon, the bold amount, "(have n)" and two spaces. */
	public static float costNameRoom(Cost k, float w, float u, Measure m) {
		float icon = k.icon() ? (ICON + 1) * u : 0;
		return w - icon - (boldWidth(k.need(), m) + m.width(k.have()) + 2 * m.width(" ")) * u;
	}

	/** A string's width in bold (vanilla bold is a pixel wider per glyph). */
	public static int boldWidth(String s, Measure m) {
		return m.width(s) + s.codePointCount(0, s.length());
	}


	/** The panel {@code p} (the right page) with its button {@code button}, at text unit {@code u}. */
	public static Plan plan(Rect p, Rect button, float u, Content c, Measure m) {
		Sizes z = new Sizes(p, u, c, m);
		// The bottom block, from the button up: the costs or the note.
		float y = button.y() - z.gap;
		List<CostRow> costs = new ArrayList<>();
		List<String> note = List.of();
		float noteY = Float.NaN;
		if (c.note() != null) {
			note = wrap(c.note(), p.w(), u, 3, m);
			noteY = y - note.size() * z.line;
			y = noteY - z.gap;
		} else if (!c.costs().isEmpty()) {
			float ry = y - c.costs().size() * z.line;
			y = ry - z.gap;
			for (Cost k : c.costs()) {
				costs.add(new CostRow(ry, k, fit(k.name(), costNameRoom(k, p.w(), u, m), u, m)));
				ry += z.line;
			}
		}
		float bottomTop = y;
		String label = fit(c.button(), button.w() - 2 * BUTTON_INSET * u, u, m);
		float bodyTop = p.y() + 2 * z.line + z.gap;
		float room = bottomTop - bodyTop;

		// The least form, then each extra while the whole still fits.
		int[] desc = {1, 1};
		int[] rows = {0, 0};
		int table = z.fork ? 0 : Math.min(1, c.table().size());
		boolean fits = z.height(desc, rows, table, false, false, false) <= room;
		boolean showDesc = false, showMilestone = false, showImproves = false;
		if (fits) {
			if (z.fork) {
				int[] r1 = {Math.min(1, z.rows(0)), Math.min(1, z.rows(1))};
				if (z.height(desc, r1, table, false, false, false) <= room) rows = r1;
			} else if (z.height(desc, rows, Math.min(2, c.table().size()), false, false, false) <= room) {
				table = Math.min(2, c.table().size());
			}
			showDesc = c.desc() != null && z.height(desc, rows, table, true, false, false) <= room;
			showMilestone = c.milestone() != null && z.height(desc, rows, table, showDesc, true, false) <= room;
			showImproves = c.improves() != null && z.height(desc, rows, table, showDesc, showMilestone, true) <= room;
			if (!z.fork) {
				while (table < c.table().size() && z.height(desc, rows, table + 1, showDesc, showMilestone, showImproves) <= room) table++;
			} else {
				// Grow the cards a step at a time, a description line then a number row, each card in turn.
				for (int step = 0; step < 2 * (CARD_DESC + CARD_ROWS); step++) {
					boolean grew = false;
					for (int o = 0; o < 2; o++) {
						int[] d2 = desc.clone();
						int[] r2 = rows.clone();
						if (step % 2 == 0 && d2[o] < CARD_DESC && d2[o] < z.descLines(o)) d2[o]++;
						else if (r2[o] < Math.min(CARD_ROWS, z.rows(o))) r2[o]++;
						else continue;
						if (z.height(d2, r2, table, showDesc, showMilestone, showImproves) <= room) {
							desc = d2;
							rows = r2;
							grew = true;
						}
					}
					if (!grew && step % 2 == 1) break;
				}
			}
		}

		// Place it all.
		float yy = bodyTop;
		float improvesY = Float.NaN, descY = Float.NaN, milestoneY = Float.NaN;
		String improves = "", descText = "", milestone = "";
		if (showImproves) {
			improvesY = yy;
			improves = fit(c.improves(), p.w(), u, m);
			yy += z.line;
		}
		if (showDesc) {
			descY = yy;
			descText = fit(c.desc(), p.w(), u, m);
			yy += z.line;
		}
		float bodyY = yy;
		List<CardBox> cards = new ArrayList<>();
		if (z.fork) {
			float tallest = Math.max(z.cardHeight(0, desc, rows), z.cardHeight(1, desc, rows));
			float cy = yy;
			for (int o = 0; o < 2; o++) {
				Card k = c.cards().get(o);
				float h = z.side ? tallest : z.cardHeight(o, desc, rows);
				float x = z.side ? p.x() + o * (z.cardW + 3 * u) : p.x();
				cards.add(new CardBox(x, cy, z.cardW, h, fit(k.name(), z.textW, u, m), wrap(k.desc(), z.textW, u, desc[o], m), rows[o]));
				if (z.side) yy = Math.max(yy, cy + h);
				else {
					yy = cy + h;
					cy += h + z.gap;
				}
			}
		} else {
			yy += table * z.line;
		}
		if (showMilestone) {
			milestoneY = yy + z.gap;
			milestone = fit(c.milestone(), p.w(), u, m);
			yy = milestoneY + z.line;
		}
		return new Plan(u, z.line, fit(c.title(), p.w(), u, m), p.y(), fit(c.subtitle(), p.w(), u, m), p.y() + z.line, improves, improvesY,
				descText, descY, bodyY, table, z.side, cards, z.pad, z.inset, milestone, milestoneY, costs, note, noteY, label,
				!label.equals(c.button()), yy, bottomTop, fits && yy <= bottomTop + 1.0E-3F);
	}

	/** The measures {@link #plan} budgets with. */
	private static final class Sizes {
		final Content c;
		final Measure m;
		final float u;
		final int line;
		final float gap;
		final float pad;
		/** A card's text starts this far in from its left edge (its padding and the focus bar). */
		final float inset;
		final boolean fork;
		final boolean side;
		final float cardW;
		final float textW;

		Sizes(Rect p, float u, Content c, Measure m) {
			this.c = c;
			this.m = m;
			this.u = u;
			this.line = Math.round(LINE * u);
			this.gap = 2 * u;
			this.pad = 2 * u;
			this.inset = pad + u;
			this.fork = c.cards() != null;
			float half = (p.w() - 3 * u) / 2f;
			boolean s = fork && half >= SIDE_MIN * u;
			if (s) for (Card k : c.cards()) if (m.width(k.name()) * u > half - inset - pad) s = false;
			this.side = s;
			this.cardW = side ? half : p.w();
			this.textW = cardW - inset - pad;
		}

		int rows(int o) {
			return c.cards().get(o).changes().size();
		}

		int descLines(int o) {
			return wrap(c.cards().get(o).desc(), textW, u, CARD_DESC, m).size();
		}

		float cardHeight(int o, int[] desc, int[] rows) {
			int dl = wrap(c.cards().get(o).desc(), textW, u, desc[o], m).size();
			return 2 * pad + line + dl * line + rows[o] * line;
		}

		float height(int[] desc, int[] rows, int table, boolean showDesc, boolean showMilestone, boolean showImproves) {
			float h;
			if (fork) {
				float a = cardHeight(0, desc, rows), b = cardHeight(1, desc, rows);
				h = side ? Math.max(a, b) : a + gap + b;
			} else {
				h = table * line;
			}
			if (showDesc) h += line;
			if (showImproves) h += line;
			if (showMilestone) h += gap + line;
			return h;
		}
	}
}
