package local.carter.office.client.magic.screen;

import java.util.ArrayList;
import java.util.List;

import local.carter.office.client.magic.screen.SignLayout.Rect;

/**
 * Where the grimoire screen draws, in GUI units: the open book as large as the screen allows (up to 8× its texture, 3:2),
 * three index tabs (Spells, Friends, How to cast) sticking out of its left edge, a two-line footer hint under it, the left page (sigil,
 * level, xp bar, home) and six spell rows filling the right page; on the Friends tab ({@link Friends}) the right page holds a
 * header with the pager and {@link #FRIEND_ROWS} friend rows. Text is drawn at {@link Layout#unit()} (an integer that grows
 * with the book); nothing scrolls at any size.
 */
public final class GrimoireLayout {
	public static final int ROWS = 6;
	/** Friends per page of the Friends tab (more turn pages with the arrows). */
	public static final int FRIEND_ROWS = 8;
	/** Index tabs: Spells, Friends, How to cast (more tabs: raise this and add the page in {@code GrimoireScreen}). */
	public static final int TABS = 4;
	/** Tooltip lang key per tab, in tab order. */
	public static final String[] TAB_TIPS = {"magic.office.tab_spells", "magic.office.tab_friends", "magic.office.tab_howto",
		"magic.office.tab_tree"};

	static {
		// A new tab needs its tooltip (and its page, icon and footer in GrimoireScreen) before the class loads at all.
		if (TAB_TIPS.length != TABS) throw new IllegalStateException("TAB_TIPS has " + TAB_TIPS.length + " entries for " + TABS + " tabs");
	}
	/** How far a tab sticks out of the book, and its height, per text unit. */
	private static final int TAB_OUT = 20;
	private static final int TAB_H = 28;
	/** Font line height. */
	public static final int LINE = 9;
	private static final float MAX_SCALE = 8f;
	/** Book scale per text unit: text keeps about the same physical size relative to the book at every GUI scale. */
	private static final float SCALE_PER_UNIT = 1.8f;
	private static final int[] LEFT_PAGE = {13, 15, 79, 112};
	private static final int[] RIGHT_PAGE = {113, 15, 179, 112};

	/**
	 * @param unit     text scale (an integer, so text stays crisp at every GUI scale)
	 * @param sigil    the element sigil at the top of the left page
	 * @param levelY   top of the "Level n" line (drawn at {@code 1.5 × unit} when it fits, centred on the left page)
	 * @param xpBar    the grimoire xp bar
	 * @param xpTextY  top of the "xp / next" line under the bar
	 * @param homeY    top of the home block (up to two lines) at the bottom of the left page
	 */
	public record Layout(Rect book, Rect leftPage, Rect rightPage, List<Rect> rows, Rect footer, float unit, Rect sigil, int levelY,
			Rect xpBar, int xpTextY, int homeY, List<Rect> tabs, Friends friends) {
		/** The tab under the point, or -1. */
		public int tabAt(double x, double y) {
			for (int i = 0; i < tabs.size(); i++) if (contains(tabs.get(i), x, y)) return i;
			return -1;
		}

		/** The row under the point, or -1. */
		public int rowAt(double x, double y) {
			for (int i = 0; i < rows.size(); i++) if (contains(rows.get(i), x, y)) return i;
			return -1;
		}
	}

	/**
	 * The Friends tab's right page: a header line (title on the left, {@code prev} · page · {@code next} on the right) over
	 * {@link #FRIEND_ROWS} rows (head, name, why a friend is unavailable); and the requests switch at the bottom of the left
	 * page ({@code toggle}, where the home block sits on the Spells tab).
	 */
	public record Friends(Rect header, List<Rect> rows, Rect prev, Rect next, Rect toggle) {
		/** The friend row under the point, or -1. */
		public int rowAt(double x, double y) {
			for (int i = 0; i < rows.size(); i++) if (contains(rows.get(i), x, y)) return i;
			return -1;
		}
	}

	static boolean contains(Rect r, double x, double y) {
		return x >= r.x() && x < r.right() && y >= r.y() && y < r.bottom();
	}

	private GrimoireLayout() {}

	public static Layout layout(int w, int h) {
		int margin = Math.max(4, Math.min(w, h) / 40);
		// Provisional book scale (one-unit footer) decides the text unit; then the real footer decides the book.
		float scale0 = Math.min((w - 2f * margin) / ChoiceLayout.TEX_W, (h - 2f * margin - 26f) / ChoiceLayout.TEX_H);
		float unit = Math.max(1f, Math.round(Math.min(scale0, MAX_SCALE) / SCALE_PER_UNIT));
		int footerH = Math.round((2 * LINE + 4) * unit);
		int gap = Math.round(4 * unit);
		// The tabs stick out on the left; the same room is kept on the right (the ribbon), so the book stays centred.
		int tabOut = Math.round(TAB_OUT * unit);
		float scale = Math.min(MAX_SCALE, Math.min((w - 2f * margin - 2f * tabOut) / ChoiceLayout.TEX_W,
				(h - 2f * margin - footerH - gap) / ChoiceLayout.TEX_H));
		int bw = (int) Math.floor(ChoiceLayout.TEX_W * scale);
		int bh = (int) Math.floor(ChoiceLayout.TEX_H * scale);
		int blockH = bh + gap + footerH;
		int top = margin + Math.max(0, (h - 2 * margin - blockH) / 2);
		Rect book = new Rect((w - bw) / 2, top, bw, bh);
		Rect footer = new Rect(margin, book.bottom() + gap, w - 2 * margin, footerH);

		Rect left = ChoiceLayout.page(book, LEFT_PAGE);
		Rect right = ChoiceLayout.page(book, RIGHT_PAGE);

		int rowGap = Math.max(1, Math.round(right.h() * 0.012f));
		int rowH = (right.h() - (ROWS - 1) * rowGap) / ROWS;
		int used = ROWS * rowH + (ROWS - 1) * rowGap;
		int y = right.y() + (right.h() - used) / 2;
		List<Rect> rows = new ArrayList<>(ROWS);
		for (int i = 0; i < ROWS; i++) {
			rows.add(new Rect(right.x(), y, right.w(), rowH));
			y += rowH + rowGap;
		}

		// Left page, top to bottom: sigil, level, xp bar, xp numbers ... home at the bottom.
		int pad = Math.max(2, Math.round(left.h() * 0.03f));
		int line = Math.round(LINE * unit);
		int homeH = 2 * line + Math.round(unit);
		int textH = Math.round(LINE * 1.5f * unit) + Math.round(3 * unit) + Math.round(5 * unit) + Math.round(3 * unit) + line;
		// The sigil's turning ring reaches 6 % past it on each side; a mana line sits between the xp numbers and home.
		int avail = left.h() - 2 * pad - textH - homeH - 2 * line - Math.round(8 * unit);
		int sigilSize = Math.min(Math.round(left.w() * 0.56f), Math.round(avail / 1.14f));
		sigilSize = Math.max(8, sigilSize);
		Rect sigil = new Rect(left.cx() - sigilSize / 2, left.y() + pad + Math.round(sigilSize * 0.06f), sigilSize, sigilSize);
		int levelY = sigil.bottom() + Math.round(sigilSize * 0.08f) + Math.round(3 * unit);
		int barY = levelY + Math.round(LINE * 1.5f * unit) + Math.round(3 * unit);
		int barW = Math.round(left.w() * 0.8f);
		Rect xpBar = new Rect(left.cx() - barW / 2, barY, barW, Math.round(5 * unit));
		int xpTextY = xpBar.bottom() + Math.round(3 * unit);
		int homeY = left.bottom() - pad - homeH;

		// Index tabs on the book's left edge, a little below its top; the part under the cover is hidden by the book.
		int tabH = Math.round(TAB_H * unit);
		int tabGap = Math.max(1, Math.round(2 * unit));
		int tabTuck = Math.round(6 * unit);
		List<Rect> tabs = new ArrayList<>(TABS);
		int ty = book.y() + Math.round(book.h() * 0.16f);
		for (int i = 0; i < TABS; i++) {
			tabs.add(new Rect(book.x() - tabOut, ty, tabOut + tabTuck, tabH));
			ty += tabH + tabGap;
		}
		Rect toggle = new Rect(left.x(), homeY, left.w(), homeH);
		return new Layout(book, left, right, List.copyOf(rows), footer, unit, sigil, levelY, xpBar, xpTextY, homeY, List.copyOf(tabs),
				friends(right, unit, toggle));
	}

	private static Friends friends(Rect right, float unit, Rect toggle) {
		int headerH = Math.max(LINE + 2, Math.round((LINE + 4) * unit));
		Rect header = new Rect(right.x(), right.y(), right.w(), headerH);
		int top = header.bottom() + Math.max(1, Math.round(2 * unit));
		int rowGap = Math.max(1, Math.round(right.h() * 0.008f));
		int rowH = (right.bottom() - top - (FRIEND_ROWS - 1) * rowGap) / FRIEND_ROWS;
		List<Rect> rows = new ArrayList<>(FRIEND_ROWS);
		int y = top;
		for (int i = 0; i < FRIEND_ROWS; i++) {
			rows.add(new Rect(right.x(), y, right.w(), rowH));
			y += rowH + rowGap;
		}
		int arrow = headerH;
		Rect next = new Rect(right.right() - arrow, right.y(), arrow, arrow);
		Rect prev = new Rect(next.x() - arrow - Math.round(22 * unit), right.y(), arrow, arrow);
		return new Friends(header, List.copyOf(rows), prev, next, toggle);
	}
}
