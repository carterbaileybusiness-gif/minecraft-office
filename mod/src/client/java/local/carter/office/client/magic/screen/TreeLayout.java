package local.carter.office.client.magic.screen;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.magic.screen.SignLayout.Rect;

/**
 * Where the Skill tree tab draws (pure, GUI units), inside the book {@link GrimoireLayout} lays out. The left page holds the tree:
 * {@link #COLS} columns (one per spell, wheel order), each a spell icon with its rank under it and {@link #RANKS} nodes below on a
 * line; ranks III and V are split into two small option nodes side by side. The right page is the detail panel (laid out as it
 * draws, see {@code TreePage}), its primary button pinned to the bottom. A one-line Re-pick switch sits under the tree. Text is
 * drawn at {@link Layout#unit()} (never below 1); nothing scrolls at any size.
 */
public final class TreeLayout {
	public static final int COLS = 5;
	public static final int RANKS = 5;
	/** Font line height. */
	public static final int LINE = 9;

	/**
	 * One node: column {@code col} (wheel slot), {@code rank} 1–5, {@code option} 0 for a bump, 1 (A, left) or 2 (B, right) for
	 * a fork, centred at ({@code cx}, {@code cy}) with radius {@code r}.
	 */
	public record Node(int col, int rank, int option, float cx, float cy, float r) {
		public boolean fork() {
			return option != 0;
		}

		/** Hit test with a little slack round the circle. */
		public boolean contains(double x, double y, float slack) {
			double dx = x - cx, dy = y - cy;
			return dx * dx + dy * dy <= (r + slack) * (r + slack);
		}
	}

	/**
	 * @param columns  each column's strip on the left page
	 * @param icons    each column's spell icon (square)
	 * @param rankY    top of the rank numeral line under each icon
	 * @param nodes    every node, column by column, rank by rank (A before B)
	 * @param repick   the Re-pick switch line at the bottom of the left page
	 * @param panel    the right page (the detail panel's area)
	 * @param button   the primary button, at the bottom of the panel
	 */
	public record Layout(float unit, Rect left, Rect right, List<Rect> columns, List<Rect> icons, List<Integer> rankY, List<Node> nodes,
			Rect repick, Rect panel, Rect button) {
		/** The node under the point, or null. */
		public @Nullable Node nodeAt(double x, double y) {
			float slack = Math.max(2f, unit * 2f);
			Node best = null;
			double bestD = Double.MAX_VALUE;
			for (Node n : nodes) {
				if (!n.contains(x, y, slack)) continue;
				double d = (x - n.cx()) * (x - n.cx()) + (y - n.cy()) * (y - n.cy());
				if (d < bestD) {
					bestD = d;
					best = n;
				}
			}
			return best;
		}

		/** The column whose icon is under the point, or -1. */
		public int iconAt(double x, double y) {
			for (int i = 0; i < icons.size(); i++) if (GrimoireLayout.contains(icons.get(i), x, y)) return i;
			return -1;
		}

		/** The node at column {@code col}, rank {@code rank}, option {@code option} (0 for a bump). */
		public Node node(int col, int rank, int option) {
			for (Node n : nodes) if (n.col == col && n.rank == rank && n.option == option) return n;
			throw new IllegalArgumentException("no node " + col + "/" + rank + "/" + option);
		}
	}

	private TreeLayout() {}

	public static boolean isFork(int rank) {
		return rank == 3 || rank == 5;
	}

	public static Layout of(GrimoireLayout.Layout g) {
		float u = g.unit();
		Rect left = g.leftPage();
		Rect right = g.rightPage();
		float colW = left.w() / (float) COLS;
		int icon = Math.max(8, Math.round(Math.min(16 * u, colW - 2 * u)));
		int iconTop = left.y() + Math.round(2 * u);
		int line = Math.round(LINE * u);
		int rankTop = iconTop + icon + Math.round(u);
		int repickH = line + Math.round(3 * u);
		Rect repick = new Rect(left.x(), left.bottom() - repickH, left.w(), repickH);
		float nodesTop = rankTop + line + 4 * u;
		float nodesBottom = repick.y() - 3 * u;
		// Five rows: the radius from the row pitch and the column width, the split nodes' from the column width.
		float pitch0 = (nodesBottom - nodesTop) / RANKS;
		float r = Math.clamp(Math.min(pitch0 * 0.3f, colW * 0.24f), 3f, 9f * u);
		float pitch = (nodesBottom - nodesTop - 2 * r) / (RANKS - 1);
		float rs = Math.max(2.5f, Math.min(r * 0.8f, (colW - 3 * u) / 4.2f));
		float split = rs + Math.max(1f, u);

		List<Rect> columns = new ArrayList<>(COLS);
		List<Rect> icons = new ArrayList<>(COLS);
		List<Integer> rankY = new ArrayList<>(COLS);
		List<Node> nodes = new ArrayList<>();
		for (int c = 0; c < COLS; c++) {
			int x0 = Math.round(left.x() + c * colW), x1 = Math.round(left.x() + (c + 1) * colW);
			columns.add(new Rect(x0, left.y(), x1 - x0, repick.y() - left.y()));
			float cx = left.x() + (c + 0.5f) * colW;
			icons.add(new Rect(Math.round(cx - icon / 2f), iconTop, icon, icon));
			rankY.add(rankTop);
			for (int rank = 1; rank <= RANKS; rank++) {
				float cy = nodesTop + r + (rank - 1) * pitch;
				if (isFork(rank)) {
					nodes.add(new Node(c, rank, 1, cx - split, cy, rs));
					nodes.add(new Node(c, rank, 2, cx + split, cy, rs));
				} else {
					nodes.add(new Node(c, rank, 0, cx, cy, r));
				}
			}
		}
		int buttonH = Math.round(16 * u);
		Rect button = new Rect(right.x(), right.bottom() - buttonH, right.w(), buttonH);
		return new Layout(u, left, right, List.copyOf(columns), List.copyOf(icons), List.copyOf(rankY), List.copyOf(nodes), repick, right,
				button);
	}
}
