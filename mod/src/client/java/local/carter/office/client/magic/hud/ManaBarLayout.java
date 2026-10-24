package local.carter.office.client.magic.hud;

import local.carter.office.client.magic.screen.SignLayout.Rect;

/**
 * Where the mana bar HUD draws, in GUI units: the bar exactly where vanilla's XP bar goes ({@code (w − 182) / 2, h − 29,
 * 182 × 5}), the mana number centred above it in vanilla's level spot (the gap between hearts and food), the XP level at the
 * bar's right end, and the selected-spell label where vanilla shows the held item's name.
 */
public final class ManaBarLayout {
	public static final int WIDTH = 182;
	public static final int HEIGHT = 5;
	/** Font line height. */
	public static final int LINE = 9;
	/** Rows a digit covers (the outline adds one on each side). */
	public static final int GLYPH = 7;
	/** Gap between the bar's right end and the XP level. */
	public static final int XP_GAP = 4;

	/**
	 * @param manaY top of the mana number: vanilla's XP-level spot ({@code h − 24 − 9 − 2}) lifted 2 so its outline clears the bar
	 * @param xpX   left of the XP level text
	 * @param xpY   top of the XP level text (centred on the bar)
	 */
	public record Layout(int w, int h, Rect bar, int manaY, int xpX, int xpY) {
		/** The mana number's box for a text {@code textWidth} wide (outline included), centred on the bar. */
		public Rect manaText(int textWidth) {
			return new Rect(bar.x() + (WIDTH - textWidth) / 2 - 1, manaY - 1, textWidth + 2, GLYPH + 2);
		}

		/** The XP level's box for a text {@code textWidth} wide (outline included). */
		public Rect xpText(int textWidth) {
			return new Rect(xpX - 1, xpY - 1, textWidth + 2, GLYPH + 2);
		}

		/** Top of the spell-name label: vanilla's held-item name line (14 lower when there are no hearts). */
		public int labelY(boolean hasHearts) {
			return h - 59 + (hasHearts ? 0 : 14);
		}
	}

	private ManaBarLayout() {}

	public static Layout layout(int w, int h) {
		Rect bar = new Rect((w - WIDTH) / 2, h - 24 - HEIGHT, WIDTH, HEIGHT);
		int manaY = h - 24 - LINE - 4;
		int xpX = bar.right() + XP_GAP;
		int xpY = bar.y() + HEIGHT / 2 - GLYPH / 2;
		return new Layout(w, h, bar, manaY, xpX, xpY);
	}

	/** Filled width of the bar for {@code mana} of {@code max}. */
	public static int fillWidth(int mana, int max) {
		if (max <= 0) return 0;
		return Math.round(WIDTH * Math.clamp(mana, 0, max) / (float) max);
	}

	/** Bar-relative x of one notch per 10 mana, excluding both ends. */
	public static int[] notches(int max) {
		int n = Math.max(0, (max - 1) / 10);
		int[] out = new int[n];
		for (int i = 0; i < n; i++) out[i] = Math.round(WIDTH * (i + 1) * 10f / max);
		return out;
	}
}
