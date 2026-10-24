package local.carter.office.magic.spell.earth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import local.carter.office.magic.core.Shapes;
import local.carter.office.magic.core.Shapes.Cell;

import net.minecraft.world.phys.Vec3;

/**
 * Mountain Fist's numbers and shapes (pure: no world access), shared by the server effect and the client fx.
 *
 * <p>The spire is {@code Shapes.cone(baseRadius, height)} on the target cell (the open cell on the ground at the ray point), with
 * {@code baseRadius = min(6, 3 + (level − 7))} and {@code height = min(12, 8 + 2 × (level − 7))}. Its layers are placed bottom-up
 * over {@link #RISE} ticks ({@link #layerTick}); a base column standing over a dip reaches down to the ground at most
 * {@link #SKIRT} blocks first (its {@link #skirtTick}), so the spire never floats. The fist bursts out of the ground at the cast
 * and strikes on {@link #STRIKE_TICK}: creatures within {@link #BLAST} take {@code 24 × P} and are launched {@link #LAUNCH_UP} up.
 * {@link #debris} ground blocks round the base are thrown out as flying debris.
 */
public final class MountainFistGeometry {
	/** How far the look ray looks for ground. */
	public static final double RANGE = 32;
	/** The target is at least the base radius plus this from the caster (horizontally), so the spire never closes round them. */
	public static final double CLEAR = 2;
	/** Ticks the spire takes to rise, bottom to top. */
	public static final int RISE = 20;
	/** The tick the fist breaks the ground and strikes. */
	public static final int STRIKE_TICK = 2;
	/** Who the strike hits: creatures within this of the target. */
	public static final double BLAST = 5;
	public static final float DAMAGE = 24;
	public static final double LAUNCH_UP = 1.6;
	/** A little outward push with the launch, so a mob thrown off the fist lands beside the spire, not in it. */
	public static final double LAUNCH_OUT = 0.35;
	/** How far down a base column over a dip reaches for ground. */
	public static final int SKIRT = 3;
	/** Most blocks the spire may place. */
	public static final int MAX_BLOCKS = 900;
	/** Flying debris: at level 7, more per level above, at most this. */
	public static final int BASE_DEBRIS = 12;
	public static final int MAX_DEBRIS = 20;
	/** Material shares (percent): stone, the target's surface block, andesite. */
	public static final int STONE_SHARE = 60;
	public static final int SURFACE_SHARE = 25;

	private MountainFistGeometry() {}

	private static int over(int level) {
		return Math.max(0, level - 7);
	}

	/** min(6, 3 + (level − 7)). */
	public static int baseRadius(int level) {
		return Math.min(6, 3 + over(level));
	}

	/** min(12, 8 + 2 × (level − 7)). */
	public static int height(int level) {
		return Math.min(12, 8 + 2 * over(level));
	}

	/** Flying debris thrown: 12, two more per level above 7, at most 20. */
	public static int debris(int level) {
		return Math.min(MAX_DEBRIS, BASE_DEBRIS + 2 * over(level));
	}

	/** The spire's cells (offsets from the target cell), bottom layer first, each layer nearest the axis first. */
	public static List<Cell> spire(int baseRadius, int height) {
		List<Cell> cone = new ArrayList<>(Shapes.cone(baseRadius, height));
		cone.sort(Comparator.comparingInt(Cell::y).thenComparingInt(c -> c.x() * c.x() + c.z() * c.z()).thenComparingInt(Cell::x)
				.thenComparingInt(Cell::z));
		return cone;
	}

	/** The tick layer {@code y} (0 = the ground layer) of a spire {@code height} high is placed: bottom-up, the top on {@link #RISE}. */
	public static int layerTick(int y, int height) {
		return Math.max(1, (int) Math.round((y + 1) * (double) RISE / Math.max(1, height)));
	}

	/** The tick a base column's skirt (the cells under its ground layer, down to the ground) is placed: before the ground layer. */
	public static int skirtTick() {
		return 1;
	}

	/** Where the top of the spire stands at time {@code t} (fractional ticks): {@code height × t / RISE}, capped at the top. */
	public static double topAt(double t, int height) {
		return height * Math.max(0, Math.min(1, t / RISE));
	}

	/** What a spire cell at {@code (x, y, z)} is made of, from the cast's seed: 0 stone (60 %), 1 the surface block (25 %), 2 andesite (15 %). */
	public static int material(long seed, int x, int y, int z) {
		long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L);
		h ^= h >>> 31;
		h *= 0xBF58476D1CE4E5B9L;
		h ^= h >>> 29;
		int r = (int) Math.floorMod(h, 100L);
		if (r < STONE_SHARE) return 0;
		if (r < STONE_SHARE + SURFACE_SHARE) return 1;
		return 2;
	}

	/** Debris piece {@code i} of {@code n}: its offset (x, z) round the base, one block outside the spire's footprint. */
	public static int[] debrisCell(int i, int n, int baseRadius, long seed) {
		double turn = new Random(seed).nextDouble() * 2 * Math.PI;
		double a = turn + 2 * Math.PI * i / Math.max(1, n);
		double r = baseRadius + 1.5;
		return new int[] {(int) Math.round(Math.cos(a) * r), (int) Math.round(Math.sin(a) * r)};
	}

	/** Debris piece {@code i}'s launch velocity: outward 0.22–0.32 a tick, up 0.45–0.6 (seeded): it lands about 6–9 blocks out. */
	public static Vec3 debrisVelocity(int i, int dx, int dz, long seed) {
		Random r = new Random(seed * 31 + i);
		double len = Math.sqrt(dx * dx + dz * dz);
		double ox = len < 1.0E-6 ? 1 : dx / len;
		double oz = len < 1.0E-6 ? 0 : dz / len;
		double out = 0.22 + 0.1 * r.nextDouble();
		return new Vec3(ox * out, 0.45 + 0.15 * r.nextDouble(), oz * out);
	}
}
