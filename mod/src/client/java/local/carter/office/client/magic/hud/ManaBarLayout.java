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
	 * @param xpY   top of the XP level text (centred on the bar)
	 */
	public record Layout(int w, int h, Rect bar, int manaY, int xpY) {
		/** The mana number's box for a text {@code textWidth} wide (outline included), centred on the bar. */
		public Rect manaText(int textWidth) {
			return new Rect(bar.x() + (WIDTH - textWidth) / 2 - 1, manaY - 1, textWidth + 2, GLYPH + 2);
		}

		/**
		 * Left of the XP level text: at the bar's right end, or at its left end when the off-hand slot sits on the right
		 * (left-handed players), so the slot never covers it.
		 */
		public int xpX(int textWidth, boolean offhandRight) {
			return offhandRight ? bar.x() - XP_GAP - textWidth : bar.right() + XP_GAP;
		}

		/** The XP level's box for a text {@code textWidth} wide (outline included). */
		public Rect xpText(int textWidth, boolean offhandRight) {
			return new Rect(xpX(textWidth, offhandRight) - 1, xpY - 1, textWidth + 2, GLYPH + 2);
		}

		/** Vanilla's off-hand slot (29 × 24 at {@code h − 23}, beside the hotbar on the off-hand side). */
		public Rect offhandSlot(boolean right) {
			return new Rect(right ? w / 2 + 91 : w / 2 - 91 - 29, h - 23, 29, 24);
		}

		/**
		 * Top of the spell-name label: vanilla's held-item name line for players with hearts ({@code h − 59}). Used whenever
		 * the mana bar shows, creative included (vanilla's lower creative line would sit on the mana number).
		 */
		public int labelY() {
			return h - 59;
		}
	}

	private ManaBarLayout() {}

	public static Layout layout(int w, int h) {
		Rect bar = new Rect((w - WIDTH) / 2, h - 24 - HEIGHT, WIDTH, HEIGHT);
		int manaY = h - 24 - LINE - 4;
		int xpY = bar.y() + HEIGHT / 2 - GLYPH / 2;
		return new Layout(w, h, bar, manaY, xpY);
	}

	/**
	 * Casting stance: the mana bar replaces the XP bar only for a bound player with a grimoire in the main hand, or in the off
	 * hand with the main hand empty, and not on a jumpable mount (its jump bar wins). Any other time vanilla's XP and locator
	 * bars show, even with a grimoire in the off hand.
	 */
	public static boolean shows(boolean bound, boolean mainGrimoire, boolean mainEmpty, boolean offGrimoire, boolean jumpableMount) {
		return bound && !jumpableMount && (mainGrimoire || (offGrimoire && mainEmpty));
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
