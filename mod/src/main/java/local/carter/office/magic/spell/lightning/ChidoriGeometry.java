package local.carter.office.magic.spell.lightning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import local.carter.office.magic.core.Shapes;
import local.carter.office.magic.core.Shapes.Cell;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Where a Chidori Strike charges and what it carves (pure: vector and cell math only, no world access). The server effect and
 * the client fx both build it from what the fx event carries: where the caster's feet started ({@code origin − (0, eyeHeight, 0)}),
 * where the charge stops ({@code target}) and the end bowl's radius.
 *
 * <p>The caster moves along the straight path {@code start → end} over {@link #TICKS} ticks, an even share of it each tick
 * ({@link #point}). The trench is the block columns whose centres lie within {@link #HALF_WIDTH} of the path seen from above
 * ({@link #trench}), cut {@link #DEPTH} blocks down from the ground's surface; the columns beyond that, out to {@link #RIM}, are
 * its scorched lips ({@link #lips}). The trench is cut in {@link #SEGMENT}-block segments along the path, one ULTIMATE batch
 * each, each as the caster passes its end ({@link #segmentsPassed}). The end bowl is {@code Shapes.bowl(radius, }{@link
 * #BOWL_DEPTH}{@code )} round the ground block under the end.
 */
public record ChidoriGeometry(Vec3 start, Vec3 end, int radius) {
	/** Ticks the charge takes. */
	public static final int TICKS = 6;
	/** How far ahead it may strike: a creature by ray (with {@link #FORGIVENESS} of aim forgiveness), else a point. */
	public static final double RANGE = 16;
	public static final double FORGIVENESS = 2;
	/** How far past a creature the charge carries ("through your target"). */
	public static final double PASS = 1.5;
	/** The trench: columns within this of the path (2 wide), cut this many blocks deep. */
	public static final double HALF_WIDTH = 1.0;
	public static final int DEPTH = 2;
	/** The scorched lips: columns beyond the trench out to this far from the path. */
	public static final double RIM = 2.0;
	/** Path length cut by one batch. */
	public static final double SEGMENT = 12;
	/** Share of the scorched edges (lips, trench floor, bowl floor) turned to blackstone. */
	public static final float BLACKSTONE = 0.3F;
	/** The end bowl: radius {@code min(12, 4 + (level − 7))}, depth factor 0.5. */
	public static final int BASE_RADIUS = 4;
	public static final int MAX_RADIUS = 12;
	public static final int BASE_LEVEL = 7;
	public static final double BOWL_DEPTH = 0.5;
	/** Rim blocks of the bowl thrown out as debris, and how wide that rim band is. */
	public static final int DEBRIS = 16;
	public static final double RIM_BAND = 1.5;
	/** The strike at the end: the target within this, and the blast round the end. */
	public static final double REACH = 3;

	/** One block column of the trench or its lips: its x and z, and how far along the path (seen from above) its centre lies. */
	public record Column(int x, int z, double along) {}

	/** End bowl radius at grimoire {@code level}: {@code min(12, 4 + (level − 7))}. */
	public static int radius(int level) {
		return Math.max(1, Math.min(MAX_RADIUS, BASE_RADIUS + (level - BASE_LEVEL)));
	}

	/** Share of the charge done at tick {@code t} (0 at the cast, 1 at the end). */
	public static double progress(double t) {
		return Mth.clamp(t / TICKS, 0, 1);
	}

	/** Where the caster's feet are at tick {@code t} (fractional for drawing): {@code start → end} evenly over {@link #TICKS}. */
	public Vec3 point(double t) {
		return start.lerp(end, progress(t));
	}

	/** The flat direction of the charge (+Z if it goes straight up or down). */
	public Vec3 flatDir() {
		double dx = end.x - start.x;
		double dz = end.z - start.z;
		double l = Math.sqrt(dx * dx + dz * dz);
		return l < 1.0E-6 ? new Vec3(0, 0, 1) : new Vec3(dx / l, 0, dz / l);
	}

	/** Length of the path seen from above. */
	public double flatLength() {
		double dx = end.x - start.x;
		double dz = end.z - start.z;
		return Math.sqrt(dx * dx + dz * dz);
	}

	/** How far along the path (seen from above, 0 at the start, clamped to it) the point {@code (x, z)} lies. */
	public double along(double x, double z) {
		double len = flatLength();
		if (len < 1.0E-9) return 0;
		Vec3 f = flatDir();
		return Mth.clamp((x - start.x) * f.x + (z - start.z) * f.z, 0, len);
	}

	/** Distance from {@code (x, z)} to the path, seen from above. */
	public double offset(double x, double z) {
		double a = along(x, z);
		Vec3 f = flatDir();
		double px = start.x + f.x * a;
		double pz = start.z + f.z * a;
		return Math.sqrt((x - px) * (x - px) + (z - pz) * (z - pz));
	}

	/** The path's height at {@code along} (feet height, between the start's and the end's). */
	public double heightAt(double along) {
		double len = flatLength();
		return len < 1.0E-9 ? start.y : start.y + (end.y - start.y) * Mth.clamp(along / len, 0, 1);
	}

	/** The point on the path {@code along} from the start (seen from above). */
	public Vec3 at(double along) {
		double len = flatLength();
		return len < 1.0E-9 ? start : start.lerp(end, Mth.clamp(along / len, 0, 1));
	}

	/** The trench's columns: centres within {@link #HALF_WIDTH} of the path, ordered along it. */
	public List<Column> trench() {
		return columns(0, HALF_WIDTH);
	}

	/** The scorched lips: columns whose centres lie beyond {@link #HALF_WIDTH} and within {@link #RIM} of the path, ordered along it. */
	public List<Column> lips() {
		return columns(HALF_WIDTH, RIM);
	}

	private List<Column> columns(double outside, double within) {
		int x0 = Mth.floor(Math.min(start.x, end.x) - within - 1);
		int x1 = Mth.floor(Math.max(start.x, end.x) + within + 1);
		int z0 = Mth.floor(Math.min(start.z, end.z) - within - 1);
		int z1 = Mth.floor(Math.max(start.z, end.z) + within + 1);
		List<Column> out = new ArrayList<>();
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				double cx = x + 0.5;
				double cz = z + 0.5;
				double o = offset(cx, cz);
				if (o <= within + 1.0E-9 && (outside <= 0 || o > outside + 1.0E-9)) out.add(new Column(x, z, along(cx, cz)));
			}
		}
		out.sort(Comparator.comparingDouble(Column::along).thenComparingInt(Column::x).thenComparingInt(Column::z));
		return out;
	}

	/** How many {@link #SEGMENT}-block segments the trench is cut in (at least one). */
	public int segments() {
		return Math.max(1, (int) Math.ceil(flatLength() / SEGMENT - 1.0E-9));
	}

	/** Which segment a column is cut with. */
	public int segment(Column c) {
		return Math.min(segments() - 1, (int) Math.floor(c.along() / SEGMENT));
	}

	/** Where segment {@code k}'s batch is centred: the point on the path half way along it. */
	public Vec3 segmentMiddle(int k) {
		double from = k * SEGMENT;
		double to = Math.min(flatLength(), (k + 1) * SEGMENT);
		return at((from + to) / 2);
	}

	/** Segments the caster has wholly passed by tick {@code t} (all of them at {@link #TICKS}). */
	public int segmentsPassed(int t) {
		if (t >= TICKS) return segments();
		double done = flatLength() * progress(t);
		return Math.min(segments(), (int) Math.floor(done / SEGMENT + 1.0E-9));
	}

	// ---- the bowl at the end ------------------------------------------------------------------------------------------------

	/** The cells the end bowl removes, round the ground block under the end (nearest first). */
	public List<Cell> bowl() {
		return Shapes.bowl(radius, BOWL_DEPTH);
	}

	/**
	 * The bowl's rim: its top-layer cells within {@link #RIM_BAND} of its edge, ordered so that the first {@link #DEBRIS} are spread
	 * evenly round it (the rest follow, for when some of those turn out to be air).
	 */
	public static List<Cell> rim(List<Cell> bowl, int radius) {
		double inner = radius - RIM_BAND;
		List<Cell> ring = new ArrayList<>();
		for (Cell c : bowl) {
			if (c.y() != 0) continue;
			if (Math.sqrt(c.x() * c.x() + c.z() * c.z()) > inner) ring.add(c);
		}
		ring.sort(Comparator.comparingDouble((Cell c) -> Math.atan2(c.z(), c.x())).thenComparingInt(Cell::x).thenComparingInt(Cell::z));
		int n = ring.size();
		List<Cell> out = new ArrayList<>(n);
		boolean[] taken = new boolean[n];
		int even = Math.min(DEBRIS, n);
		for (int k = 0; k < even; k++) {
			int i = (int) ((long) k * n / even);
			if (!taken[i]) {
				taken[i] = true;
				out.add(ring.get(i));
			}
		}
		for (int i = 0; i < n; i++) if (!taken[i]) out.add(ring.get(i));
		return out;
	}

	/** Where a thrown rim cell flies: outward from the centre and up, a little different for each (seeded). */
	public static Vec3 debrisVelocity(Cell c, Random r) {
		double len = Math.sqrt(c.x() * c.x() + c.z() * c.z());
		double ox = len < 1.0E-6 ? 1 : c.x() / len;
		double oz = len < 1.0E-6 ? 0 : c.z() / len;
		double out = 0.22 + 0.16 * r.nextDouble();
		double up = 0.42 + 0.22 * r.nextDouble();
		return new Vec3(ox * out, up, oz * out);
	}

	/**
	 * The bowl's floor: in each column of the bowl, the cell just under its deepest cell, kept only within the radius (so nothing
	 * changes beyond it). Ordered as the bowl's columns first appear (nearest first).
	 */
	public static List<Cell> floor(List<Cell> bowl, int radius) {
		Map<Long, Cell> deepest = new HashMap<>();
		List<Long> order = new ArrayList<>();
		for (Cell c : bowl) {
			long key = ((long) c.x() << 32) ^ (c.z() & 0xFFFFFFFFL);
			Cell was = deepest.get(key);
			if (was == null) order.add(key);
			if (was == null || c.y() < was.y()) deepest.put(key, c);
		}
		long r2 = (long) radius * radius;
		List<Cell> out = new ArrayList<>(order.size());
		for (long key : order) {
			Cell d = deepest.get(key);
			Cell f = new Cell(d.x(), d.y() - 1, d.z());
			if ((long) f.x() * f.x() + (long) f.y() * f.y() + (long) f.z() * f.z() <= r2) out.add(f);
		}
		return out;
	}

	/** Whether a scorched edge cell turns to blackstone ({@link #BLACKSTONE} of them, seeded). */
	public static boolean blackstone(Random r) {
		return r.nextFloat() < BLACKSTONE;
	}
}
