package local.carter.office.client.magic.screen;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the hand-sign prompt draws, in GUI units, for any GUI scale and window size: one row of square arrow tiles, the
 * timer bar under it, the spell name and title above, the key hint below, and a panel around everything. The arrows grow
 * with the screen (up to {@link #MAX_ARROW}) and shrink until everything fits inside the margin; nothing ever scrolls.
 */
public final class SignLayout {
	/** Line height of the Minecraft font. */
	public static final int LINE = 9;
	public static final int MIN_ARROW = 12;
	public static final int MAX_ARROW = 64;
	/** Smallest gap between the panel and the screen edge. */
	public static final int MIN_MARGIN = 6;

	public record Rect(int x, int y, int w, int h) {
		public int right() { return x + w; }

		public int bottom() { return y + h; }

		public int cx() { return x + w / 2; }

		public int cy() { return y + h / 2; }

		public boolean overlaps(Rect o) {
			return x < o.right() && o.x < right() && y < o.bottom() && o.y < bottom();
		}

		/** Inside a {@code w × h} screen with at least {@code margin} on every side. */
		public boolean insideScreen(int screenW, int screenH, int margin) {
			return x >= margin && y >= margin && right() <= screenW - margin && bottom() <= screenH - margin;
		}
	}

	/**
	 * @param arrows    one tile per sign, left to right
	 * @param timer     the time bar (full width; the screen shrinks what it fills)
	 * @param panel     the backdrop around everything
	 * @param nameY     top of the spell-name line (centred on {@code panel.cx()})
	 * @param titleY    top of the title line, drawn at {@code titleScale}
	 * @param titleScale 2 when the arrows are big and it fits, else 1, or less than 1 when even that is wider than the panel
	 * @param hintY     top of the key-hint line
	 * @param innerWidth the panel's width minus its padding: no text line is drawn wider than this
	 */
	public record Layout(List<Rect> arrows, Rect timer, Rect panel, int nameY, int titleY, float titleScale, int hintY, int arrowSize,
			int innerWidth) {
		/** Scale for a scale-1 text line {@code textWidth} wide: 1, or less so it stays inside the panel (long translations). */
		public float fit(int textWidth) {
			return textWidth <= innerWidth ? 1f : innerWidth / (float) textWidth;
		}
	}

	private SignLayout() {}

	/** {@link #layout(int, int, int, int, int)} with no text to fit. */
	public static Layout layout(int w, int h, int length) {
		return layout(w, h, length, 0, 0);
	}

	/**
	 * @param titleWidth the title's width at scale 1 (drawn at scale 2 when the arrows are big and it fits, else 1, scaled
	 *                   down further only if it is wider than the panel can be)
	 * @param lineWidth  the widest other text line (name, hint), drawn at scale 1 or scaled down to fit ({@link Layout#fit})
	 */
	public static Layout layout(int w, int h, int length, int titleWidth, int lineWidth) {
		int n = Math.max(1, length);
		int margin = Math.max(MIN_MARGIN, Math.min(w, h) / 32);
		int size = Math.min(MAX_ARROW, Math.max(MIN_ARROW, h / 5));
		while (true) {
			int gap = gap(size);
			int pad = pad(size);
			int titleScale = titleScale(size, titleWidth, w - 2 * margin - 2 * pad);
			int rowW = n * size + (n - 1) * gap;
			int contentH = height(size, titleScale);
			boolean fits = rowW + 2 * pad <= w - 2 * margin && contentH + 2 * pad <= h - 2 * margin;
			if (fits || size <= MIN_ARROW) {
				int textW = Math.max(titleWidth * titleScale, lineWidth);
				return build(w, h, n, size, gap, pad, titleScale, rowW, contentH, textW, titleWidth, margin);
			}
			size--;
		}
	}

	private static int gap(int size) {
		return Math.max(4, size / 4);
	}

	private static int pad(int size) {
		return Math.max(6, size / 3);
	}

	private static int timerH(int size) {
		return Math.max(3, size / 10);
	}

	private static int titleScale(int size, int textWidth, int room) {
		return size >= 40 && textWidth * 2 <= room ? 2 : 1;
	}

	/** name, title, row, timer, hint and the gaps between them. */
	private static int height(int size, int titleScale) {
		int g = gap(size);
		return LINE + 3 + LINE * titleScale + g + size + g + timerH(size) + g + LINE;
	}

	private static Layout build(int w, int h, int n, int size, int gap, int pad, int titleScale, int rowW, int contentH,
			int textWidth, int titleWidth, int margin) {
		int innerW = Math.min(Math.max(rowW, textWidth), w - 2 * margin - 2 * pad);
		innerW = Math.max(innerW, rowW);
		float titleFit = titleScale == 2 || titleWidth <= innerW ? titleScale : innerW / (float) titleWidth;
		int panelW = innerW + 2 * pad;
		int panelH = contentH + 2 * pad;
		int panelX = (w - panelW) / 2;
		// A little below the middle: the arrows sit near the crosshair, the title above them.
		int panelY = Math.clamp((h - panelH) / 2 + size / 4, margin, Math.max(margin, h - margin - panelH));
		Rect panel = new Rect(panelX, panelY, panelW, panelH);

		int y = panelY + pad;
		int nameY = y;
		y += LINE + 3;
		int titleY = y;
		y += LINE * titleScale + gap;
		int rowX = (w - rowW) / 2;
		List<Rect> arrows = new ArrayList<>(n);
		for (int i = 0; i < n; i++) arrows.add(new Rect(rowX + i * (size + gap), y, size, size));
		y += size + gap;
		Rect timer = new Rect(rowX, y, rowW, timerH(size));
		y += timer.h() + gap;
		int hintY = y;
		return new Layout(List.copyOf(arrows), timer, panel, nameY, titleY, titleFit, hintY, size, innerW);
	}
}
