package local.carter.office.magic.spell.shadow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Pure geometry of Black Hole (Vec3 math only, no world access), shared by the server effect ({@link BlackHole}) and the client
 * fx, so the land you see torn away is the land the server devours, and the debris you see falling in follows the pull.
 *
 * <p><b>Devour.</b> A sphere round the centre grows from {@link #START_RADIUS} at tick 0 to {@link #cap} at {@link #LIFE}
 * ({@link #radius}, linear, so it never shrinks). Every block whose centre it reaches is eaten ({@link #sphere} lists them,
 * nearest first). {@link #RIM_SHARE} of its outermost layer ({@link #isRim}) is not eaten but scorched instead ({@link #rimKind}:
 * half {@link #KIND_CRACKED}, half {@link #KIND_BLACKSTONE}); the rest of that layer is held back for the collapse at the end,
 * where its lip ({@link #isLip}) is flung out as debris ({@link #debrisVelocity}).
 *
 * <p><b>Pull.</b> Anything within {@link #PULL_RADIUS} moves toward the centre at {@link #pullSpeed} blocks a tick (0.08 at the
 * start, 0.25 at the end), never past it ({@link #pull}).
 */
public final class BlackHoleGeometry {
	/** How far ahead it opens, at most (the look ray). */
	public static final double RANGE = 24;
	/** It hangs this far above the ground under the ray point. */
	public static final double HEIGHT = 2;
	/** Ticks it lasts. */
	public static final int LIFE = 100;
	/** Everything pullable within this distance of the centre is pulled. */
	public static final double PULL_RADIUS = 12;
	public static final double PULL_START = 0.08;
	public static final double PULL_END = 0.25;
	/** Foes this close to the centre take {@link #HURT} every {@link #HURT_EVERY} ticks. */
	public static final double HURT_RADIUS = 2.5;
	public static final float HURT = 4;
	public static final int HURT_EVERY = 10;
	/** The devoured sphere's radius at tick 0. */
	public static final double START_RADIUS = 1;
	/** Its radius at the end: {@code min(MAX_CAP, BASE_CAP + (level − 7))}. */
	public static final double BASE_CAP = 6;
	public static final double MAX_CAP = 12;
	/** The collapse at the end: {@link #BLAST} × P within {@link #BLAST_RADIUS}, knocked {@link #KNOCK} outward, {@link #KNOCK_UP} up. */
	public static final double BLAST_RADIUS = 6;
	public static final float BLAST = 10;
	public static final double KNOCK = 2.0;
	public static final double KNOCK_UP = 0.6;
	/** Share of the outermost layer turned to scorched stone. */
	public static final double RIM_SHARE = 0.10;
	/** How deep the outermost layer is. */
	public static final double RIM_DEPTH = 1;
	/** Lip blocks flung out at the collapse, at most (the per-cast falling-block budget is 48). */
	public static final int MAX_DEBRIS = 32;
	/** Lip blocks are those whose centre is at most this far below the hole's centre (the ground round the crater's edge). */
	public static final double LIP_DEPTH = 3.6;

	public static final int KIND_NONE = 0;
	/** Cracked polished blackstone bricks (see {@link BlackHole#scorch}). */
	public static final int KIND_CRACKED = 1;
	public static final int KIND_BLACKSTONE = 2;

	private BlackHoleGeometry() {}

	/** One block of the sphere: its cell and how far its centre is from the hole's centre. */
	public record Cell(int x, int y, int z, double dist) {
		/** The cell's centre. */
		public Vec3 middle() {
			return new Vec3(x + 0.5, y + 0.5, z + 0.5);
		}
	}

	/** The devoured sphere's final radius at grimoire {@code level}: {@code min(12, 6 + (level − 7))}, at least 1. */
	public static double cap(int level) {
		return Mth.clamp(BASE_CAP + (level - 7), START_RADIUS, MAX_CAP);
	}

	/** The devoured sphere's radius {@code t} ticks in (fractions allowed): 1 at 0, {@code cap} at {@link #LIFE}, linear. */
	public static double radius(double t, double cap) {
		double f = Mth.clamp(t / LIFE, 0, 1);
		return START_RADIUS + (Math.max(START_RADIUS, cap) - START_RADIUS) * f;
	}

	/** How strongly it pulls {@code t} ticks in: 0.08 → 0.25 blocks a tick over its life. */
	public static double pullSpeed(double t) {
		return PULL_START + (PULL_END - PULL_START) * Mth.clamp(t / LIFE, 0, 1);
	}

	/** The pull on something at {@code at}, {@code t} ticks in: toward {@code centre}, {@link #pullSpeed} long but never past it. */
	public static Vec3 pull(Vec3 centre, Vec3 at, double t) {
		Vec3 to = centre.subtract(at);
		double d = to.length();
		if (d < 1.0E-6 || !Double.isFinite(d)) return Vec3.ZERO;
		return to.scale(Math.min(pullSpeed(t), d) / d);
	}

	public static boolean inReach(Vec3 centre, Vec3 at) {
		return at.distanceToSqr(centre) <= PULL_RADIUS * PULL_RADIUS;
	}

	/** Every block cell whose centre lies within {@code r} of {@code centre}, nearest first. */
	public static List<Cell> sphere(Vec3 centre, double r) {
		List<Cell> out = new ArrayList<>();
		int x0 = Mth.floor(centre.x - r);
		int x1 = Mth.floor(centre.x + r);
		int y0 = Mth.floor(centre.y - r);
		int y1 = Mth.floor(centre.y + r);
		int z0 = Mth.floor(centre.z - r);
		int z1 = Mth.floor(centre.z + r);
		double r2 = r * r;
		for (int x = x0; x <= x1; x++) {
			double dx = x + 0.5 - centre.x;
			for (int y = y0; y <= y1; y++) {
				double dy = y + 0.5 - centre.y;
				for (int z = z0; z <= z1; z++) {
					double dz = z + 0.5 - centre.z;
					double d2 = dx * dx + dy * dy + dz * dz;
					if (d2 <= r2) out.add(new Cell(x, y, z, Math.sqrt(d2)));
				}
			}
		}
		out.sort(Comparator.comparingDouble(Cell::dist).thenComparingInt(Cell::y).thenComparingInt(Cell::x).thenComparingInt(Cell::z));
		return out;
	}

	/** Whether {@code c} is in the sphere's outermost layer (within {@link #RIM_DEPTH} of {@code cap}). */
	public static boolean isRim(Cell c, double cap) {
		return c.dist() > cap - RIM_DEPTH;
	}

	/**
	 * What a rim cell becomes: {@link #KIND_CRACKED} or {@link #KIND_BLACKSTONE} for {@link #RIM_SHARE} of them (half each, picked by
	 * a hash of the cell and the cast's seed, the same on every client), {@link #KIND_NONE} for the rest (and for cells off the rim).
	 */
	public static int rimKind(Cell c, double cap, long seed) {
		if (!isRim(c, cap)) return KIND_NONE;
		double u = unit(seed, c.x(), c.y(), c.z(), 0);
		if (u < RIM_SHARE / 2) return KIND_CRACKED;
		if (u < RIM_SHARE) return KIND_BLACKSTONE;
		return KIND_NONE;
	}

	/**
	 * Whether a scorched rim cell {@code c} may be scorched at all: only below the centre (the crater's bowl, never the canopy of a
	 * tree or the top of a wall the sphere cuts through). The server also wants a solid block under it.
	 */
	public static boolean scorchable(Cell c, Vec3 centre, double cap) {
		if (c.y() + 0.5 >= centre.y) return false;
		// The block under it must stay: outside the sphere (a cell below the centre only gets farther from it going down).
		double dx = c.x() + 0.5 - centre.x;
		double dy = c.y() - 0.5 - centre.y;
		double dz = c.z() + 0.5 - centre.z;
		return dx * dx + dy * dy + dz * dz > cap * cap;
	}

	/** Changes the cast keeps back from eating, for the scorched rim and the collapse (nearest-first eating would starve them). */
	public static final int RESERVE = 60;

	/** Whether rim cell {@code c} is part of the crater's lip: no deeper than {@link #LIP_DEPTH} below the centre. */
	public static boolean isLip(Cell c, Vec3 centre) {
		return c.y() + 0.5 >= centre.y - LIP_DEPTH;
	}

	/** The velocity a lip block is flung out with at the collapse: outward 0.32–0.55 along the ground, up 0.5–0.8 (seeded per cell). */
	public static Vec3 debrisVelocity(Vec3 centre, Cell c, long seed) {
		double dx = c.x() + 0.5 - centre.x;
		double dz = c.z() + 0.5 - centre.z;
		double len = Math.sqrt(dx * dx + dz * dz);
		double a = unit(seed, c.x(), c.y(), c.z(), 1) * Mth.TWO_PI;
		double ux = len < 1.0E-6 ? Math.cos(a) : dx / len;
		double uz = len < 1.0E-6 ? Math.sin(a) : dz / len;
		double out = 0.32 + 0.23 * unit(seed, c.x(), c.y(), c.z(), 2);
		double up = 0.5 + 0.3 * unit(seed, c.x(), c.y(), c.z(), 3);
		return new Vec3(ux * out, up, uz * out);
	}

	/**
	 * Where a block torn from {@code from} is, {@code t} ticks after it was torn (0..{@code ticks}): it spirals into the centre,
	 * slow at first and faster as it falls in (an ease-in on the distance), winding {@code turns} times round the vertical, and
	 * reaches the centre at {@code ticks}. The client draws the devoured land on it.
	 */
	public static Vec3 infall(Vec3 centre, Vec3 from, double turns, double t, int ticks) {
		double f = Mth.clamp(t / Math.max(1, ticks), 0, 1);
		double k = f * f * (1.6 - 0.6 * f);
		Vec3 rel = from.subtract(centre);
		double a = turns * Mth.TWO_PI * f * f;
		double cos = Math.cos(a);
		double sin = Math.sin(a);
		double rx = rel.x * cos - rel.z * sin;
		double rz = rel.x * sin + rel.z * cos;
		double s = 1 - k;
		return new Vec3(centre.x + rx * s, centre.y + rel.y * s, centre.z + rz * s);
	}

	/** The visible core's radius when the devoured sphere is {@code r} across: about a third of it, never under 0.7. */
	public static double coreRadius(double r) {
		return Math.max(0.7, 0.7 + 0.3 * (r - START_RADIUS));
	}

	/** A uniform number in [0, 1) from the cast's seed, a cell and a salt (splitmix64). */
	static double unit(long seed, int x, int y, int z, int salt) {
		long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L) ^ (salt * 0xD6E8FEB86659FD93L);
		h += 0x9E3779B97F4A7C15L;
		h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
		h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
		h ^= h >>> 31;
		return (h >>> 11) * 0x1.0p-53;
	}
}
