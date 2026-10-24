package local.carter.office.magic.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Block-cell shapes around an origin cell. Lists are sorted by distance from the origin (origin first). */
public final class Shapes {
	public record Cell(int x, int y, int z) {
		long dist2() { return (long) x * x + (long) y * y + (long) z * z; }
	}

	private static final Comparator<Cell> ORDER = Comparator.comparingLong(Cell::dist2)
		.thenComparingInt(Cell::y).thenComparingInt(Cell::x).thenComparingInt(Cell::z);

	private Shapes() {}

	private static List<Cell> sorted(List<Cell> cells) {
		cells.sort(ORDER);
		return cells;
	}

	/** All cells within distance r of the origin. */
	public static List<Cell> sphere(double r) {
		int n = (int) Math.floor(r);
		double r2 = r * r;
		List<Cell> out = new ArrayList<>();
		for (int x = -n; x <= n; x++)
			for (int y = -n; y <= n; y++)
				for (int z = -n; z <= n; z++)
					if (x * x + y * y + z * z <= r2) out.add(new Cell(x, y, z));
		return sorted(out);
	}

	/** The lower half of an ellipsoid: radius r sideways, r x depthFactor deep, never above y = 0. */
	public static List<Cell> bowl(double r, double depthFactor) {
		int n = (int) Math.floor(r);
		double r2 = r * r;
		double depth = r * depthFactor;
		List<Cell> out = new ArrayList<>();
		for (int x = -n; x <= n; x++)
			for (int y = -n; y <= 0; y++) {
				if (y < -depth) continue;
				double sy = depthFactor > 0 ? y / depthFactor : (y == 0 ? 0 : Double.MAX_VALUE);
				for (int z = -n; z <= n; z++) {
					if (x * x + z * z + sy * sy <= r2 && x * x + y * y + z * z <= r2) out.add(new Cell(x, y, z));
				}
			}
		return sorted(out);
	}

	/** Upright cone on the origin layer: layers y = 0..h-1, radius shrinking linearly from baseR. */
	public static List<Cell> cone(double baseR, int h) {
		int n = (int) Math.floor(baseR);
		List<Cell> out = new ArrayList<>();
		for (int y = 0; y < h; y++) {
			double layerR = baseR * (1.0 - (double) y / h);
			double l2 = layerR * layerR;
			for (int x = -n; x <= n; x++)
				for (int z = -n; z <= n; z++)
					if (x * x + z * z <= l2) out.add(new Cell(x, y, z));
		}
		return sorted(out);
	}

	/** A ground line of up to {@code length} cells along the horizontal direction (dx, dz), starting at the origin. */
	public static List<Cell> line(double dx, double dz, int length) {
		List<Cell> out = new ArrayList<>();
		double len = Math.sqrt(dx * dx + dz * dz);
		if (length <= 0) return out;
		if (len == 0) {
			out.add(new Cell(0, 0, 0));
			return out;
		}
		double ux = dx / len, uz = dz / len;
		Cell last = null;
		for (int i = 0; i < length; i++) {
			Cell c = new Cell((int) Math.floor(ux * i + 0.5), 0, (int) Math.floor(uz * i + 0.5));
			if (!c.equals(last)) out.add(c);
			last = c;
		}
		return out;
	}
}
