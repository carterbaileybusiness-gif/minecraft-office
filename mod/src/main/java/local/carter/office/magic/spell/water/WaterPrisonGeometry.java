package local.carter.office.magic.spell.water;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Water Prison's pure geometry (no world access), shared by the server spell and the client fx: the sphere, who is inside
 * it, the pull to its centre, and the air a prisoner loses.
 *
 * <p>The sphere has radius {@link #radius} about its centre (the target's middle at the cast): {@link #RADIUS} at a tap,
 * {@link #FULL_SCALE} times that at full charge. A creature whose middle is within that + {@link #HOLD} is inside. Each tick it is pulled to the centre with a velocity of {@link #PULL}
 * × the gap, at most {@link #MAX_PULL}. It loses {@link #AIR_PER_TICK} air a tick (vanilla under water loses 1; three keeps the drowning within the prison's life); at
 * {@link #DROWN_AT} it takes {@link #DROWN_DAMAGE} drowning damage and its air goes back to 0, as vanilla drowning does.
 */
public final class WaterPrisonGeometry {
	public static final double RADIUS = 2.0;
	/**
	 * A full charge's sphere is this many times a tap's across (3.5 blocks): short of 2× because the client draws the water as
	 * ghost cells, and a 4-block ball would need more than an event's {@code FxBudget.GHOST_BLOCKS}.
	 */
	public static final double FULL_SCALE = 1.75;
	/** How far the cast ray reaches. */
	public static final double RANGE = 24;
	/** Aim assist for the ray: hitboxes are this much larger. */
	public static final double FORGIVENESS = 0.4;
	/** Past the sphere's surface a prisoner still counts as inside by this much (it never slips out on a jitter). */
	public static final double HOLD = 0.5;
	public static final double PULL = 0.35;
	public static final double MAX_PULL = 0.6;
	public static final int AIR_PER_TICK = 3;
	/** Vanilla drowns at air −20 and resets the air to 0. */
	public static final int DROWN_AT = -20;
	public static final float DROWN_DAMAGE = 2;

	private WaterPrisonGeometry() {}

	/** How long the prison stands: 100 + 100 × charge ticks. */
	public static int duration(float charge) {
		return 100 + Math.round(100 * Mth.clamp(charge, 0F, 1F));
	}

	/** The sphere's radius at {@code charge}: 2 at a tap, 3.5 at full charge. */
	public static double radius(float charge) {
		return RADIUS * (1 + (FULL_SCALE - 1) * Mth.clamp(charge, 0F, 1F));
	}

	/** One cell of the ball as the client draws it: {@code height} (0..1) of it is water. */
	public record Cell(BlockPos pos, float height) {}

	/**
	 * The ball's water core as cells, rounded on top: every cell in a column within {@link #CORE} of the centre (horizontally)
	 * whose middle is above that sphere's underside and whose floor is below its top; the top cell of each column is only as
	 * full as the sphere is there (at least {@link #MIN_FILL}), so the crown is round, not stepped. The core is smaller than the
	 * prison so the cells' corners stay inside it; the client draws the round surface over them.
	 */
	public static List<Cell> fill(Vec3 centre) {
		return fill(centre, RADIUS);
	}

	/** {@link #fill(Vec3)} for a sphere of {@code radius} (its core {@link #CORE_INSET} smaller). */
	public static List<Cell> fill(Vec3 centre, double radius) {
		double core = radius - CORE_INSET;
		List<Cell> out = new ArrayList<>();
		int r = (int) Math.ceil(radius) + 1;
		BlockPos c = BlockPos.containing(centre);
		for (int dz = -r; dz <= r; dz++) {
			for (int dx = -r; dx <= r; dx++) {
				double x = c.getX() + dx + 0.5 - centre.x;
				double z = c.getZ() + dz + 0.5 - centre.z;
				double d2 = x * x + z * z;
				if (d2 > core * core) continue;
				double h = Math.sqrt(core * core - d2);
				double lo = centre.y - h;
				double hi = centre.y + h;
				for (int y = (int) Math.floor(lo); y <= (int) Math.floor(hi); y++) {
					if (y + 0.5 < lo) continue;
					float full = (float) Math.min(1, hi - y);
					if (full < MIN_FILL) continue;
					out.add(new Cell(new BlockPos(c.getX() + dx, y, c.getZ() + dz), full));
				}
			}
		}
		return out;
	}

	/** How much smaller than the sphere the water core drawn as cells is (block corners then reach about the prison's radius). */
	public static final double CORE_INSET = 0.7;
	/** A tap's core radius. */
	public static final double CORE = RADIUS - CORE_INSET;

	/** The thinnest top cell drawn. */
	public static final float MIN_FILL = 0.15F;

	/** Whether a creature whose middle is at {@code middle} is held by a tap's sphere at {@code centre}. */
	public static boolean inside(Vec3 centre, Vec3 middle) {
		return inside(centre, middle, RADIUS);
	}

	/** Whether a creature whose middle is at {@code middle} is held by the sphere of {@code radius} at {@code centre}. */
	public static boolean inside(Vec3 centre, Vec3 middle, double radius) {
		double r = radius + HOLD;
		return middle.distanceToSqr(centre) <= r * r;
	}

	/** The velocity that draws a creature's middle at {@code middle} to {@code centre}: {@link #PULL} × the gap, at most {@link #MAX_PULL}. */
	public static Vec3 pull(Vec3 centre, Vec3 middle) {
		Vec3 v = centre.subtract(middle).scale(PULL);
		double l = v.length();
		return l > MAX_PULL ? v.scale(MAX_PULL / l) : v;
	}

	/** A prisoner's air after one tick in the sphere, from {@code air}: {@link #AIR_PER_TICK} less, back to 0 when it drowns ({@link #drowns}). */
	public static int air(int air) {
		int next = air - AIR_PER_TICK;
		return next <= DROWN_AT ? 0 : next;
	}

	/** Whether the tick that takes {@code air} down drowns (deals {@link #DROWN_DAMAGE}). */
	public static boolean drowns(int air) {
		return air - AIR_PER_TICK <= DROWN_AT;
	}
}
