package local.carter.office.magic.spell.fire;

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
 * Where a Meteor falls and what crater it leaves (pure: vector and cell math only, no world access). The server effect and the
 * client fx both build it from what the fx event carries: the impact point ({@code target}), the caster's flat facing
 * ({@code dir}), the crater radius and the share of the sky drop that is clear ({@code lift}).
 *
 * <p>The rock starts {@link #HEIGHT} above the impact and {@link #SLANT} beyond it, away from the caster, so it comes at them out of the sky they face (both scaled by {@code lift}
 * under a roof) and falls in a straight line to the impact over {@link #FALL_TICKS} ticks, so its height drops linearly.
 * The crater is {@code Shapes.bowl(radius, }{@link #DEPTH}{@code )} round the floor block under the impact.
 */
public record MeteorGeometry(Vec3 impact, Vec3 start, int radius) {
	public static final double HEIGHT = 40;
	public static final double SLANT = 12;
	public static final int FALL_TICKS = 40;
	/** The crater's depth factor. */
	public static final double DEPTH = 0.6;
	public static final int BASE_RADIUS = 7;
	public static final int MAX_RADIUS = 12;
	/** Grimoire level the ultimate unlocks at (its sizes grow from there). */
	public static final int BASE_LEVEL = 7;
	/** Rim blocks thrown out as debris. */
	public static final int DEBRIS = 40;
	/** How wide the rim band is (cells of the crater's top layer within this of its edge). */
	public static final double RIM_BAND = 1.5;
	/** The blast round the impact. */
	public static final double BLAST = 8;
	/** How far the caster can call it down. */
	public static final double RANGE = 64;
	/** Where it falls when the caster points at nothing. */
	public static final double MISS_AHEAD = 32;
	/** Least share of the sky drop kept under a roof. */
	public static final double MIN_LIFT = 0.1;
	public static final float MAGMA = 0.3F;
	public static final float BLACKSTONE = 0.3F;

	/** Crater radius at grimoire {@code level}: {@code min(12, 7 + (level − 7))}. */
	public static int radius(int level) {
		return Math.max(1, Math.min(MAX_RADIUS, BASE_RADIUS + (level - BASE_LEVEL)));
	}

	/** The meteor falling on {@code impact}, from the sky behind {@code dir} (flattened), its drop scaled by {@code lift}. */
	public static MeteorGeometry of(Vec3 impact, Vec3 dir, double lift, int radius) {
		return new MeteorGeometry(impact, impact.add(drop(dir).scale(Mth.clamp(lift, MIN_LIFT, 1))), radius);
	}

	/** The whole sky drop: {@link #HEIGHT} up, {@link #SLANT} on along {@code dir}. */
	public static Vec3 drop(Vec3 dir) {
		Vec3 f = BarrageGeometry.flat(dir);
		return new Vec3(f.x * SLANT, HEIGHT, f.z * SLANT);
	}

	/** Share of the fall done at tick {@code t} (0 at the cast, 1 at the impact). */
	public static double progress(double t) {
		return Mth.clamp(t / FALL_TICKS, 0, 1);
	}

	/** The rock's centre at tick {@code t} (fractional ticks for drawing). */
	public Vec3 position(double t) {
		return start.lerp(impact, progress(t));
	}

	/** The rock's height above the impact at tick {@code t}: linear from the start's height to 0 at {@link #FALL_TICKS}. */
	public double height(double t) {
		return (start.y - impact.y) * (1 - progress(t));
	}

	/** The rock's velocity in blocks a tick (constant). */
	public Vec3 velocity() {
		return impact.subtract(start).scale(1.0 / FALL_TICKS);
	}

	/** Unit direction of the fall. */
	public Vec3 heading() {
		return impact.subtract(start).normalize();
	}

	public static boolean landed(int t) {
		return t >= FALL_TICKS;
	}

	/** Linear falloff of the blast: 1 at the impact, 0 at {@link #BLAST}. */
	public static double falloff(double distance) {
		return Mth.clamp(1 - distance / BLAST, 0, 1);
	}

	// ---- the crater ---------------------------------------------------------------------------------------------------------

	/** The cells the crater removes, round the floor block under the impact (nearest first). */
	public List<Cell> bowl() {
		return Shapes.bowl(radius, DEPTH);
	}

	/**
	 * The rim: the bowl's top-layer cells within {@link #RIM_BAND} of its edge, ordered so that the first {@link #DEBRIS} are spread
	 * evenly round it (the rest follow, for when some of those turn out to be air).
	 */
	public static List<Cell> rim(List<Cell> bowl, int radius) {
		double inner = radius - RIM_BAND;
		List<Cell> ring = new ArrayList<>();
		for (Cell c : bowl) {
			if (c.y() != 0) continue;
			double d = Math.sqrt(c.x() * c.x() + c.z() * c.z());
			if (d > inner) ring.add(c);
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
		double out = 0.32 + 0.26 * r.nextDouble();
		double up = 0.55 + 0.35 * r.nextDouble();
		return new Vec3(ox * out, up, oz * out);
	}

	/**
	 * The crater's floor: in each column of the bowl, the cell just under its deepest cell, kept only within the radius (so the
	 * crater never reaches beyond it). Ordered as the bowl's columns first appear (nearest first).
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

	/** What a floor cell becomes: 0 = left as it is, 1 = magma block, 2 = blackstone (30 % / 30 %, seeded). */
	public static int floorKind(Random r) {
		float roll = r.nextFloat();
		return roll < MAGMA ? 1 : roll < MAGMA + BLACKSTONE ? 2 : 0;
	}
}
