package local.carter.office.magic.spell.wind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import local.carter.office.magic.core.Shapes.Cell;

import net.minecraft.world.phys.Vec3;

/**
 * Wind Sphere's dash and drill (pure: vectors and cells only, no world access). The server effect and the client fx both use it.
 *
 * <p><b>Dash.</b> After {@link #WINDUP} ticks forming in the hand, the sphere flies with the caster along the cast's look at
 * {@link #SPEED} blocks a tick for at most {@link #DASH_TICKS} ticks, {@link #LEAD} ahead of the eye: {@link #sphere}.
 *
 * <p><b>Drill.</b> A cone laid along the look from the struck block ({@link #cells}): {@link #length} layers, its radius
 * shrinking linearly from {@link #BASE_RADIUS} at the impact to {@link #TIP_RADIUS} at the far end. It is the same rule as
 * {@code Shapes.cone} (a cell is in a layer when its distance from the axis is within that layer's radius), measured along the
 * look instead of +Y: rotating {@code Shapes.cone}'s cell list would leave holes and doubles, so each cell is tested against
 * the turned axis. No cell is further than {@link #MAX_LENGTH} from the impact cell. Up to {@link #LAUNCH} of the drilled
 * blocks are thrown out spinning ({@link #debris}).
 */
public final class DrillGeometry {
	public static final double SPEED = 1.8;
	public static final int DASH_TICKS = 10;
	/** Ticks the sphere spins up in the hand before the dash (the caster is held still). */
	public static final int WINDUP = 6;
	/** How far ahead of the eye the sphere flies. */
	public static final double LEAD = 1.0;
	/** An entity whose box comes within this of the sphere sets it off. */
	public static final double TOUCH = 1.5;
	public static final double BLAST = 4;
	public static final float DAMAGE = 22;
	public static final double KNOCK = 3.5;
	public static final double BASE_RADIUS = 3;
	public static final double TIP_RADIUS = 1;
	public static final int BASE_LENGTH = 8;
	public static final int MAX_LENGTH = 12;
	/** Drilled blocks thrown out as flying debris, at most. */
	public static final int LAUNCH = 32;

	private DrillGeometry() {}

	/** Drill length at grimoire {@code level}: {@code min(12, 8 + (level − 7))}, at least 1. */
	public static int length(int level) {
		return Math.max(1, Math.min(MAX_LENGTH, BASE_LENGTH + (level - 7)));
	}

	/** The sphere's centre {@code t} dash ticks in (fractions allowed): {@code eye + look × (LEAD + SPEED × t)}. */
	public static Vec3 sphere(Vec3 eye, Vec3 look, double t) {
		return eye.add(unit(look).scale(LEAD + SPEED * Math.max(0, t)));
	}

	/** The drill's radius {@code along} blocks down its axis, for a drill {@code length} layers long. */
	public static double radiusAt(double along, int length) {
		if (length <= 1) return BASE_RADIUS;
		double f = Math.max(0, Math.min(1, along / (length - 1)));
		return BASE_RADIUS + (TIP_RADIUS - BASE_RADIUS) * f;
	}

	/**
	 * The drill's cells as offsets from the struck cell, for a drill along {@code look} {@code length} layers long: every cell
	 * whose distance along the axis is in {@code [0, length − 1]} and whose distance from the axis is within
	 * {@link #radiusAt} there, and none beyond {@link #MAX_LENGTH}. Ordered from the mouth inward (then nearest the axis first).
	 */
	public static List<Cell> cells(Vec3 look, int length) {
		Vec3 u = unit(look);
		int n = Math.min(MAX_LENGTH, length) + (int) Math.ceil(BASE_RADIUS);
		record Scored(Cell c, double along, double radial) {}
		List<Scored> out = new ArrayList<>();
		for (int x = -n; x <= n; x++) {
			for (int y = -n; y <= n; y++) {
				for (int z = -n; z <= n; z++) {
					double a = x * u.x + y * u.y + z * u.z;
					if (a < -1.0E-9 || a > length - 1 + 1.0E-9) continue;
					if (x * x + y * y + z * z > MAX_LENGTH * MAX_LENGTH) continue;
					double rx = x - u.x * a;
					double ry = y - u.y * a;
					double rz = z - u.z * a;
					double radial = Math.sqrt(rx * rx + ry * ry + rz * rz);
					double r = radiusAt(a, length);
					if (radial <= r + 1.0E-9) out.add(new Scored(new Cell(x, y, z), a, radial));
				}
			}
		}
		out.sort(Comparator.comparingDouble(Scored::along).thenComparingDouble(Scored::radial));
		List<Cell> cells = new ArrayList<>(out.size());
		for (Scored s : out) cells.add(s.c());
		return cells;
	}

	/** Distance of cell offset {@code c} from the drill's axis along {@code look}. */
	public static double radial(Cell c, Vec3 look) {
		Vec3 u = unit(look);
		Vec3 d = new Vec3(c.x(), c.y(), c.z());
		return d.subtract(u.scale(d.dot(u))).length();
	}

	/**
	 * Which drilled cells are thrown (indices into {@code cells}): the mouth's cells off the axis, spread round it, at most
	 * {@code max}. Picked evenly from the cells with {@code along < 3} that lie at least 1 from the axis.
	 */
	public static List<Integer> thrown(List<Cell> cells, Vec3 look, int max) {
		Vec3 u = unit(look);
		List<Integer> pool = new ArrayList<>();
		for (int i = 0; i < cells.size(); i++) {
			Cell c = cells.get(i);
			double a = c.x() * u.x + c.y() * u.y + c.z() * u.z;
			if (a < 3 && radial(c, look) >= 1) pool.add(i);
		}
		if (pool.size() <= max) return pool;
		List<Integer> out = new ArrayList<>(max);
		for (int k = 0; k < max; k++) out.add(pool.get((int) ((long) k * pool.size() / max)));
		return out;
	}

	/**
	 * A thrown block's velocity: spun round the drill's axis ({@code look × outward}, the way the sphere turns), flung out from
	 * the axis, up, and a little back out of the hole. Seeded jitter from {@code r}.
	 */
	public static Vec3 debris(Cell c, Vec3 look, Random r) {
		Vec3 u = unit(look);
		Vec3 d = new Vec3(c.x(), c.y(), c.z());
		Vec3 out = d.subtract(u.scale(d.dot(u)));
		if (out.lengthSqr() < 1.0E-6) out = BladeWindGeometry.frame(u).side();
		out = out.normalize();
		Vec3 spin = u.cross(out).normalize();
		double k = 0.85 + 0.3 * r.nextDouble();
		return spin.scale(0.55 * k).add(out.scale(0.3 * k)).add(u.scale(-0.2)).add(0, 0.35 + 0.2 * r.nextDouble(), 0);
	}

	static Vec3 unit(Vec3 v) {
		double len = v.length();
		return len < 1.0E-9 ? new Vec3(0, 0, 1) : v.scale(1 / len);
	}
}
