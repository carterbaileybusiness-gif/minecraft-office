package local.carter.office.magic.spell.earth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;

/**
 * Where and when Quake's shockwave runs (pure: block maths only; the world comes in as a "has a collision shape" predicate, so
 * the server effect and the client fx run the same rule).
 *
 * <p>The ring starts at the caster's floor block ({@code centre}) and grows {@link #SPEED} blocks a tick ({@link #ringAt}: radius
 * {@code t / 2} at tick {@code t}) to {@link #radius} ({@code 6 + 2 × charge}). Its cells are the columns of the disc
 * ({@link #disc}), each reached on {@link #tickOf} its distance. A column's ground is its top solid block within
 * {@link #REACH} of the centre's level that has an open cell over it ({@link #surface}).
 *
 * <p>Cracks: {@link #CRACK_SHARE} of the disc's columns, at most {@link #MAX_CRACKS}, picked from the cast's seed ({@link #cracks}).
 * Each edit belongs to a MEDIUM batch whose origin keeps it within {@link #EDIT_RADIUS}: the centre batch for blocks within 6 of
 * the centre, else the batch of its 60° arc ({@link #arc}), whose origin lies on the arc {@link #ARC_RADIUS} out
 * ({@link #originFor}).
 */
public final class QuakeGeometry {
	/** Radius at charge 0 and what full charge adds. */
	public static final double BASE_RADIUS = 6;
	public static final double CHARGE_RADIUS = 2;
	/** Blocks the ring grows each tick (1 block per 2 ticks). */
	public static final double SPEED = 0.5;
	/** How far above or below the centre's level a column's ground may be. */
	public static final int REACH = 3;
	/** Share of the ring's columns that crack, and the most per cast. */
	public static final double CRACK_SHARE = 0.25;
	public static final int MAX_CRACKS = 120;
	/** A medium edit's radius cap: no edit is farther than this from its batch origin. */
	public static final double EDIT_RADIUS = 6;
	/** Arc batches: this many arcs (60° each), with their origins this far out on the arc's middle. */
	public static final int ARCS = 6;
	public static final double ARC_RADIUS = 7;

	private QuakeGeometry() {}

	/** The ring's final radius: 6 + 2 × charge. */
	public static double radius(float charge) {
		return BASE_RADIUS + CHARGE_RADIUS * Math.max(0F, Math.min(1F, charge));
	}

	/** The ring's radius at tick {@code t} of the cast: {@code t / 2}. */
	public static double ringAt(double t) {
		return t * SPEED;
	}

	/** The first tick the ring has reached {@code distance} (at least 1). */
	public static int tickOf(double distance) {
		return Math.max(1, (int) Math.ceil(distance / SPEED - 1.0E-9));
	}

	/** Ticks the ring takes to reach {@code radius}. */
	public static int duration(double radius) {
		return tickOf(radius);
	}

	/** One column of the disc: offset {@code (dx, dz)} from the centre, {@code dist} from it (cell centre to cell centre). */
	public record Column(int dx, int dz, double dist) {}

	/** Every column within {@code radius} of the centre (the centre's own column too), nearest first. */
	public static List<Column> disc(double radius) {
		int n = (int) Math.floor(radius);
		List<Column> out = new ArrayList<>();
		for (int dx = -n; dx <= n; dx++) {
			for (int dz = -n; dz <= n; dz++) {
				double d = Math.sqrt(dx * dx + dz * dz);
				if (d <= radius + 1.0E-9) out.add(new Column(dx, dz, d));
			}
		}
		out.sort(Comparator.comparingDouble(Column::dist).thenComparingInt(Column::dx).thenComparingInt(Column::dz));
		return out;
	}

	/**
	 * The ground block of the column at {@code (x, z)}: from {@link #REACH} above {@code centreY} down to {@link #REACH} below it,
	 * the first solid block with an open cell over it. Null when there is none (a cliff, a pit, a wall).
	 */
	public static @Nullable BlockPos surface(int x, int z, int centreY, Predicate<BlockPos> solid) {
		for (int y = centreY + REACH; y >= centreY - REACH; y--) {
			BlockPos p = new BlockPos(x, y, z);
			if (solid.test(p) && !solid.test(p.above())) return p;
		}
		return null;
	}

	/** How many columns of a disc of {@code columns} crack: a quarter, at most {@link #MAX_CRACKS}. */
	public static int crackCount(int columns) {
		return Math.min(MAX_CRACKS, (int) Math.floor(columns * CRACK_SHARE));
	}

	/** Which of the disc's columns (indices into {@link #disc}) crack, picked from {@code seed}: {@link #crackCount} of them. */
	public static boolean[] cracks(int columns, long seed) {
		List<Integer> order = new ArrayList<>(columns);
		for (int i = 0; i < columns; i++) order.add(i);
		java.util.Collections.shuffle(order, new Random(seed));
		boolean[] out = new boolean[columns];
		for (int i = 0; i < crackCount(columns); i++) out[order.get(i)] = true;
		return out;
	}

	/** The 60° arc (0..5) the offset {@code (dx, dz)} lies in. */
	public static int arc(double dx, double dz) {
		double a = Math.atan2(dz, dx);
		if (a < 0) a += 2 * Math.PI;
		return Math.min(ARCS - 1, (int) Math.floor(a / (2 * Math.PI / ARCS)));
	}

	/** Arc {@code k}'s batch origin: on the arc's middle, {@link #ARC_RADIUS} out from {@code centre}, at its level. */
	public static BlockPos arcOrigin(BlockPos centre, int k) {
		double mid = (k + 0.5) * (2 * Math.PI / ARCS);
		return centre.offset((int) Math.round(Math.cos(mid) * ARC_RADIUS), 0, (int) Math.round(Math.sin(mid) * ARC_RADIUS));
	}

	/** Whether an edit at {@code p} belongs to the centre batch (within {@link #EDIT_RADIUS} of {@code centre}). */
	public static boolean central(BlockPos centre, BlockPos p) {
		return p.distSqr(centre) <= EDIT_RADIUS * EDIT_RADIUS;
	}

	/** The batch origin an edit at {@code p} belongs to: {@code centre}, or its arc's origin. */
	public static BlockPos originFor(BlockPos centre, BlockPos p) {
		if (central(centre, p)) return centre;
		return arcOrigin(centre, arc(p.getX() - centre.getX(), p.getZ() - centre.getZ()));
	}
}
