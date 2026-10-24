package local.carter.office.client.dock;

/**
 * Where the mini Dock sits on an Extend hologram's picture and what the crosshair hits on it. Like the macOS Dock: a
 * rounded bar centred along the bottom edge, square icons in a row, only as wide as its icons. Sizes follow the
 * picture's height (icons about 7% of it, a 64 px icon on a 1080 px screen) but always fit its width. Everything is in
 * the picture's (u, v): 0..1 left to right and top to bottom, the same (u, v) as a crosshair hit. Pure.
 */
public final class DockLayout {
	/** Icon size as a share of the picture's height. */
	static final double ICON_SHARE = 0.07;
	/** Gap between icons, padding inside the bar and the bar's lift off the bottom edge, in icon sizes. */
	static final double GAP = 0.28;
	static final double PAD = 0.22;
	static final double LIFT = 0.12;
	/** The bar never takes more than this share of the picture's width. */
	static final double MAX_WIDTH_SHARE = 0.96;

	/** A rectangle in (u, v). */
	public record Box(double u0, double v0, double u1, double v1) {
		public boolean contains(final double u, final double v) {
			return u >= u0 && u <= u1 && v >= v0 && v <= v1;
		}

		public double width() {
			return u1 - u0;
		}

		public double height() {
			return v1 - v0;
		}
	}

	private final int count;
	private final Box bar;
	private final Box[] icons;
	private final Box[] cells;

	/** {@code count} icons on a {@code pictureW} x {@code pictureH} picture (any unit; only the aspect matters). */
	public DockLayout(final int count, final double pictureW, final double pictureH) {
		this.count = Math.max(0, count);
		double w = pictureW > 0 ? pictureW : 1;
		double h = pictureH > 0 ? pictureH : 1;
		double icon = h * ICON_SHARE;
		double units = this.count + Math.max(0, this.count - 1) * GAP + 2 * PAD; // bar width in icon sizes
		if (this.count > 0 && icon * units > w * MAX_WIDTH_SHARE) {
			icon = w * MAX_WIDTH_SHARE / units; // a tall (9:16) screen: smaller icons, still one row
		}
		double gap = icon * GAP;
		double pad = icon * PAD;
		double barW = this.count == 0 ? 0 : icon * units;
		double barH = icon + 2 * pad;
		double left = (w - barW) / 2;
		double bottom = h - icon * LIFT;
		double top = bottom - barH;
		bar = new Box(left / w, top / h, (left + barW) / w, bottom / h);
		icons = new Box[this.count];
		cells = new Box[this.count];
		for (int i = 0; i < this.count; i++) {
			double x = left + pad + i * (icon + gap);
			icons[i] = new Box(x / w, (top + pad) / h, (x + icon) / w, (top + pad + icon) / h);
			// each icon owns half the gap on either side, the outer ones the padding too: the whole bar is clickable
			double c0 = i == 0 ? left : x - gap / 2;
			double c1 = i == this.count - 1 ? left + barW : x + icon + gap / 2;
			cells[i] = new Box(c0 / w, top / h, c1 / w, bottom / h);
		}
	}

	public int count() {
		return count;
	}

	public Box bar() {
		return bar;
	}

	public Box icon(final int i) {
		return icons[i];
	}

	/** Inside the Dock's bar: the Dock takes the click, never the screen behind it. */
	public boolean inBar(final double u, final double v) {
		return count > 0 && bar.contains(u, v);
	}

	/** The icon under (u, v), or -1 off the Dock. Every point of the bar belongs to an icon. */
	public int hit(final double u, final double v) {
		if (!inBar(u, v)) {
			return -1;
		}
		for (int i = 0; i < count; i++) {
			if (cells[i].contains(u, v)) {
				return i;
			}
		}
		return -1;
	}
}
