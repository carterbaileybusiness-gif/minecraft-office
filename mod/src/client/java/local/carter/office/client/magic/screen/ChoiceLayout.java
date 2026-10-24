package local.carter.office.client.magic.screen;

import java.util.ArrayList;
import java.util.List;

import local.carter.office.client.magic.screen.SignLayout.Rect;

/**
 * Where the element choice scene draws, in GUI units: the open book centred (its width is half the screen's short side,
 * {@code book_open.png}'s 3:2 shape), six sigils orbiting it on an ellipse (rx 0.40 w, ry 0.34 h; sigil 0 at the top at
 * orbit 0°, then clockwise 60° apart, in {@code Element} order), the two page areas and the confirm buttons under the book.
 *
 * <p>The sigil size depends only on the screen, never on the orbit angle: it is the largest size that, at <b>every</b>
 * angle, keeps each sigil on screen, off its neighbours and off the book, even hovered at {@link #HOVER_SCALE}.
 */
public final class ChoiceLayout {
	public static final float RX = 0.40f;
	public static final float RY = 0.34f;
	public static final float HOVER_SCALE = 1.25f;
	public static final int SIGILS = 6;
	/** Book texture size and the page areas inside it (texels): inside the faded ink frames, clear of the gutter shadow. */
	static final int TEX_W = 192, TEX_H = 128;
	private static final int[] LEFT_PAGE = {13, 15, 79, 112};
	private static final int[] RIGHT_PAGE = {113, 15, 179, 112};

	public record Layout(int w, int h, float orbitDeg, List<Rect> sigils, int sigilSize, Rect book, Rect leftPage, Rect rightPage,
			Rect bind, Rect back) {
		/** The sigil's centre (exact, for smooth drawing). */
		public float cx(int i) { return centreX(w, orbitDeg, i); }

		public float cy(int i) { return centreY(h, orbitDeg, i); }

		/** Sigil {@code i} drawn hovered ({@link #HOVER_SCALE}). */
		public Rect hovered(int i) {
			int s = Math.round(sigilSize * HOVER_SCALE);
			return new Rect(Math.round(cx(i) - s / 2f), Math.round(cy(i) - s / 2f), s, s);
		}

		/** The sigil whose disc contains the point, or -1. */
		public int sigilAt(double x, double y) {
			double r = sigilSize / 2.0;
			for (int i = 0; i < SIGILS; i++) {
				double dx = x - cx(i), dy = y - cy(i);
				if (dx * dx + dy * dy <= r * r) return i;
			}
			return -1;
		}
	}

	private ChoiceLayout() {}

	static float angleRad(float orbitDeg, int i) {
		return (float) Math.toRadians(orbitDeg + i * 60f - 90f);
	}

	static float centreX(int w, float orbitDeg, int i) {
		return w / 2f + RX * w * (float) Math.cos(angleRad(orbitDeg, i));
	}

	static float centreY(int h, float orbitDeg, int i) {
		return h / 2f + RY * h * (float) Math.sin(angleRad(orbitDeg, i));
	}

	public static int bookWidth(int w, int h) {
		return Math.round(0.5f * Math.min(w, h));
	}

	public static Layout layout(int w, int h, float orbitDeg) {
		int bw = bookWidth(w, h);
		int bh = Math.round(bw * (float) TEX_H / TEX_W);
		Rect book = new Rect((w - bw) / 2, (h - bh) / 2, bw, bh);
		int size = sigilSize(w, h, book);
		List<Rect> sigils = new ArrayList<>(SIGILS);
		for (int i = 0; i < SIGILS; i++) {
			sigils.add(new Rect(Math.round(centreX(w, orbitDeg, i) - size / 2f), Math.round(centreY(h, orbitDeg, i) - size / 2f), size, size));
		}
		Rect left = page(book, LEFT_PAGE);
		Rect right = page(book, RIGHT_PAGE);
		// Confirm buttons: centred under the book (the other sigils have faded out by then), as big as the room allows.
		int margin = 4;
		int room = h - margin - book.bottom();
		int bhgt = Math.clamp(Math.round(Math.min(w, h) / 14f), 14, 40);
		bhgt = Math.min(bhgt, Math.max(14, room - 8));
		int bwid = Math.max(bhgt * 3, Math.round(bw * 0.42f));
		int gap = Math.max(4, bhgt / 2);
		int by = book.bottom() + Math.max(4, Math.min((room - bhgt) / 2, bhgt));
		int x0 = w / 2 - gap / 2 - bwid;
		Rect bind = new Rect(x0, by, bwid, bhgt);
		Rect back = new Rect(w / 2 + (gap + 1) / 2, by, bwid, bhgt);
		return new Layout(w, h, orbitDeg, List.copyOf(sigils), size, book, left, right, bind, back);
	}

	/** The texel rect {@code t} = {x0, y0, x1, y1} of the book texture, mapped into {@code book} (rounded inwards). */
	static Rect page(Rect book, int[] t) {
		float sx = book.w() / (float) TEX_W, sy = book.h() / (float) TEX_H;
		int x0 = book.x() + (int) Math.ceil(t[0] * sx), y0 = book.y() + (int) Math.ceil(t[1] * sy);
		int x1 = book.x() + (int) Math.floor(t[2] * sx), y1 = book.y() + (int) Math.floor(t[3] * sy);
		return new Rect(x0, y0, x1 - x0, y1 - y0);
	}

	/**
	 * Largest sigil (even, at most a sixth of the short side) that fits at every orbit angle: screen margin 4 (hovered: the
	 * margin may shrink to 3), a gap of at least 3 to the book (hovered too) and to each neighbour.
	 */
	static int sigilSize(int w, int h, Rect book) {
		double rx = RX * w, ry = RY * h;
		double bx = book.w() / 2.0, by = book.h() / 2.0;
		double half = Math.min(w, h) / 12.0;
		for (int d = 0; d < 3600; d += 5) {
			double a = Math.toRadians(d / 10.0);
			double x = Math.abs(rx * Math.cos(a)), y = Math.abs(ry * Math.sin(a));
			// Screen edges (base and hovered).
			half = Math.min(half, Math.min(w / 2.0 - 4 - x, h / 2.0 - 4 - y) - 1);
			half = Math.min(half, (Math.min(w / 2.0 - 3 - x, h / 2.0 - 3 - y) - 1) / HOVER_SCALE);
			// Book: outside it on at least one axis, with a gap, even hovered.
			half = Math.min(half, (Math.max(x - bx, y - by) - 4) / HOVER_SCALE);
			// Neighbour 60° on: apart on at least one axis.
			double b = a + Math.PI / 3;
			double nx = Math.abs(rx * Math.cos(a) - rx * Math.cos(b)), ny = Math.abs(ry * Math.sin(a) - ry * Math.sin(b));
			half = Math.min(half, (Math.max(nx, ny) - 4) / 2.0);
		}
		int size = (int) Math.floor(2 * half);
		return Math.max(8, size - (size & 1));
	}
}
