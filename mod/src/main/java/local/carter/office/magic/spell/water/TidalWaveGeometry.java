package local.carter.office.magic.spell.water;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import local.carter.office.magic.core.ChargeMath;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Pure geometry of Tidal Push (Vec3 math only, no world access), shared by the server effect ({@link TidalPush}) and the
 * client fx, so the picture and the push follow the same wave.
 *
 * <p><b>Wave.</b> A front {@link #WIDTH} cells wide starts at the caster's feet and advances along the horizontal look one
 * block every {@link #TICKS_PER_BLOCK} ticks for {@link #length()} blocks ({@link #LENGTH} at a tap, as far as the cone reaches
 * at full charge: 16): at tick {@code t} (1..{@link #frontTicks()}) it stands at distance {@code t / 2}. It is {@link #height} cells tall (1 at the feet, then 3, 2 and 1 as it spends itself). Each cell it
 * passes lives {@link #CELL_LIFE} ticks (on the client: only in air). The path is cut into {@link #SEGMENT}-block segments,
 * each with its own origin on the axis, so every cell is within the medium radius (6) of its segment's origin. The front
 * follows the ground column by column ({@link #step}): it climbs one-block steps, pours down drops and breaks on walls; the
 * server and the client run the same rule on their own view of the world.
 *
 * <p>The static {@code FRONT_TICKS}, {@code DURATION}, {@code SEGMENTS}, {@link #frontActive} and {@link #distance} are a tap's
 * wave; a charged one asks its own instance ({@link #frontTicks()}, {@link #duration()}, {@link #active}, {@link #at}).
 *
 * <p><b>Cone.</b> The instant shove reaches {@link #range} = 8 × (1 + charge) from the eye within {@link #HALF_ANGLE}° of the
 * look. Fire blocks in it are grouped into {@link #FIRE_SEGMENT}-block cubes, one edit batch per cube, origin at the cube's
 * middle cell (no cell farther than √27 ≈ 5.2 from it).
 */
public final class TidalWaveGeometry {
	public static final int LENGTH = 8;
	public static final int TICKS_PER_BLOCK = 2;
	public static final int FRONT_TICKS = LENGTH * TICKS_PER_BLOCK;
	public static final int WIDTH = 5;
	public static final int CELL_LIFE = 6;
	/** Whole life of the wave, including the last cells' lifetime. */
	public static final int DURATION = FRONT_TICKS + CELL_LIFE;
	public static final int SEGMENT = 4;
	public static final int SEGMENTS = LENGTH / SEGMENT;
	public static final double HALF_ANGLE = 35;
	public static final int FIRE_SEGMENT = 6;
	/** How far behind and ahead of the front line (along the axis) an entity still counts as in the wave. */
	public static final double FRONT_DEPTH = 0.8;
	/** Highest a column climbs above the start's level (a taller rise is a wall: it breaks). */
	public static final int MAX_CLIMB = 2;
	/** Lowest a column pours below the start's level (past that it runs on over the drop). */
	public static final int MAX_DROP = 3;

	private final Vec3 start;
	private final Vec3 dir;
	private final Vec3 side;
	private final int length;

	/** A tap's wave ({@link #LENGTH} blocks); see {@link #TidalWaveGeometry(Vec3, Vec3, int)}. */
	public TidalWaveGeometry(Vec3 start, Vec3 look) {
		this(start, look, LENGTH);
	}

	/**
	 * @param start the wave's start: the bottom centre of the caster's feet cell
	 * @param look the caster's look; only its horizontal part is used (straight up or down → +Z)
	 * @param length how far the wave runs, in blocks ({@link #length(double)} of the cone's range)
	 */
	public TidalWaveGeometry(Vec3 start, Vec3 look, int length) {
		this.start = start;
		this.dir = horizontal(look);
		this.side = new Vec3(-dir.z, 0, dir.x);
		this.length = Math.max(1, length);
	}

	/** How far a wave runs for a cone of {@code range}: as far as the cone reaches (8 at a tap, 16 at full charge). */
	public static int length(double range) {
		return (int) Math.round(range);
	}

	/** This wave's length in blocks. */
	public int length() { return length; }

	/** Ticks this wave's front runs ({@link #FRONT_TICKS} for a tap's). */
	public int frontTicks() { return length * TICKS_PER_BLOCK; }

	/** This wave's whole life, including its last cells' lifetime ({@link #DURATION} for a tap's). */
	public int duration() { return frontTicks() + CELL_LIFE; }

	/** Whether this wave's front exists at {@code tick} (1..{@link #frontTicks()}). */
	public boolean active(int tick) {
		return tick >= 1 && tick <= frontTicks();
	}

	/** Distance of this wave's front from the start at {@code tick}: {@code tick / 2}, clamped to 0..{@link #length()}. */
	public double at(int tick) {
		return Mth.clamp(tick / (double) TICKS_PER_BLOCK, 0, length);
	}

	/** The segment (0..) a front of this wave at distance {@code d} is in: (0, 4] → 0, (4, 8] → 1, and on. */
	public int segmentOf(double d) {
		return Mth.clamp((int) Math.ceil(d / SEGMENT) - 1, 0, (length + SEGMENT - 1) / SEGMENT - 1);
	}

	/** The horizontal unit direction of {@code look} (+Z if it has none). */
	public static Vec3 horizontal(Vec3 look) {
		Vec3 flat = new Vec3(look.x, 0, look.z);
		double l = flat.length();
		return l < 1.0E-6 || !Double.isFinite(l) ? new Vec3(0, 0, 1) : flat.scale(1 / l);
	}

	public Vec3 start() { return start; }

	public Vec3 dir() { return dir; }

	/** Unit vector to the wave's right, horizontal. */
	public Vec3 side() { return side; }

	/** Cone reach for a charge 0..1: {@code 8 × ChargeMath.size(charge)}, 8 at a tap and 16 at full charge. */
	public static double range(float charge) {
		return LENGTH * ChargeMath.size(charge);
	}

	/** Whether the front exists at {@code tick} (1..{@link #FRONT_TICKS}). */
	public static boolean frontActive(int tick) {
		return tick >= 1 && tick <= FRONT_TICKS;
	}

	/** Distance of the front from the start at {@code tick}: {@code tick / 2}, clamped to 0..{@link #LENGTH}. */
	public static double distance(int tick) {
		return Mth.clamp(tick / (double) TICKS_PER_BLOCK, 0, LENGTH);
	}

	/**
	 * Cells tall at distance {@code d}: a surge 1 tall at the caster's feet (so the caster sees over it), then the wave rears up
	 * 3 tall, runs on 2 tall, and spends itself 1 tall over its last 2 blocks.
	 */
	public static int height(double d) {
		return d <= 1.5 ? 1 : d <= 3.5 ? 3 : d <= 6 ? 2 : 1;
	}

	/** The middle of the front line at {@code tick} (at the start's height). */
	public Vec3 frontCentre(int tick) {
		return start.add(dir.scale(at(tick)));
	}

	/** The point {@code lateral} blocks to the right of the front's middle at {@code tick}. */
	public Vec3 frontPoint(int tick, double lateral) {
		return frontCentre(tick).add(side.scale(lateral));
	}

	// ---- columns: the wave follows the ground ----------------------------------------------------------------------------

	/** Fresh column state: every column's lowest row at the start's level ({@link #step} moves them). */
	public int[] columns() {
		int[] colY = new int[WIDTH];
		java.util.Arrays.fill(colY, startY());
		return colY;
	}

	/** The start's cell level. */
	public int startY() {
		return Mth.floor(start.y);
	}

	/**
	 * Moves the columns to tick {@code tick}'s front (call once per tick, 1..{@link #FRONT_TICKS}, in order; server and client
	 * run the same rule). {@code colY[i]} is the cell level of column {@code i}'s lowest row (lateral offset {@code i − 2});
	 * {@code free} says whether the wave may stand in a cell (nothing to collide with). In order, for each standing column:
	 * <ul>
	 *   <li>its cell is blocked → it climbs one block if the two cells above are free and it stays within {@link #MAX_CLIMB} of
	 *   the start's level; otherwise it <b>breaks</b> (a wall: that column is gone for good);</li>
	 *   <li>its cell is free and so is the one below → it pours down one block (at most {@link #MAX_DROP} below the start).</li>
	 * </ul>
	 *
	 * @return a bit mask of the columns that broke this tick (bit {@code i} = column {@code i})
	 */
	public int step(int tick, int[] colY, boolean[] broken, Predicate<BlockPos> free) {
		if (!active(tick)) return 0;
		int startY = startY();
		int brokeNow = 0;
		for (int i = 0; i < WIDTH; i++) {
			if (broken[i]) continue;
			BlockPos col = column(tick, i - WIDTH / 2);
			BlockPos base = new BlockPos(col.getX(), colY[i], col.getZ());
			if (!free.test(base)) {
				if (colY[i] + 1 - startY <= MAX_CLIMB && free.test(base.above()) && free.test(base.above(2))) {
					colY[i]++;
				} else {
					broken[i] = true;
					brokeNow |= 1 << i;
				}
			} else if (startY - (colY[i] - 1) <= MAX_DROP && free.test(base.below())) {
				colY[i]--;
			}
		}
		return brokeNow;
	}

	/** The front's cells on flat open ground (no column moved or broken); see {@link #front(int, int[], boolean[])}. */
	public List<BlockPos> front(int tick) {
		return front(tick, columns(), new boolean[WIDTH]);
	}

	/**
	 * The front's cells at {@code tick} (empty when inactive): each standing column (lateral offsets −2..2, left to right),
	 * {@link #height} tall from its {@code colY} up; broken columns have none. Diagonal fronts may merge two columns into one
	 * cell (no duplicates).
	 */
	public List<BlockPos> front(int tick, int[] colY, boolean[] broken) {
		if (!active(tick)) return List.of();
		int h = height(at(tick));
		Set<BlockPos> out = new LinkedHashSet<>();
		for (int i = 0; i < WIDTH; i++) {
			if (broken[i]) continue;
			BlockPos col = column(tick, i - WIDTH / 2);
			for (int y = 0; y < h; y++) out.add(new BlockPos(col.getX(), colY[i] + y, col.getZ()));
		}
		return new ArrayList<>(out);
	}

	/** The column base cell at the start's level for lateral offset {@code k} (−2..2) at {@code tick}. */
	public BlockPos column(int tick, int k) {
		return BlockPos.containing(frontPoint(tick, k));
	}

	/** The segment (0..{@link #SEGMENTS} − 1) a front at distance {@code d} is in: (0, 4] → 0, (4, 8] → 1. */
	public static int segment(double d) {
		return Mth.clamp((int) Math.ceil(d / SEGMENT) - 1, 0, SEGMENTS - 1);
	}

	/**
	 * Segment {@code s}'s batch origin: the cell on the axis in the segment's middle, at the start's level. Columns stay within
	 * {@link #MAX_DROP} below and {@link #MAX_CLIMB} (+ 2 rows) above it, so every cell is within 6.
	 */
	public BlockPos segmentOrigin(int s) {
		return BlockPos.containing(start.add(dir.scale(s * SEGMENT + SEGMENT / 2.0)));
	}

	/** Whether {@code p} (an entity's position) is in the front on flat open ground; see {@link #inFront(int, Vec3, int[], boolean[])}. */
	public boolean inFront(int tick, Vec3 p) {
		return inFront(tick, p, columns(), new boolean[WIDTH]);
	}

	/**
	 * Whether point {@code p} (an entity's feet) is inside the front at {@code tick}: within {@link #FRONT_DEPTH} of the front
	 * line, in a standing column's width, and from a block below that column's lowest row to its top.
	 */
	public boolean inFront(int tick, Vec3 p, int[] colY, boolean[] broken) {
		if (!active(tick)) return false;
		Vec3 rel = p.subtract(start);
		double axial = rel.dot(dir);
		double lateral = rel.dot(side);
		if (Math.abs(axial - at(tick)) > FRONT_DEPTH || Math.abs(lateral) > WIDTH / 2.0 + 0.5) return false;
		int i = Mth.clamp((int) Math.round(lateral), -(WIDTH / 2), WIDTH / 2) + WIDTH / 2;
		if (broken[i]) return false;
		return p.y >= colY[i] - 1 && p.y <= colY[i] + height(at(tick));
	}

	// ---- cone -------------------------------------------------------------------------------------------------------------

	/** Whether {@code p} is within {@code range} of {@code eye} and within {@link #HALF_ANGLE}° of {@code look}. */
	public static boolean inCone(Vec3 eye, Vec3 look, double range, Vec3 p) {
		Vec3 to = p.subtract(eye);
		double len = to.length();
		if (len > range) return false;
		if (len < 0.5) return true;
		Vec3 l = look.normalize();
		return to.dot(l) / len >= Math.cos(Math.toRadians(HALF_ANGLE));
	}

	/** Cells whose centre is in the cone ({@link #inCone}), nearest first. */
	public static List<BlockPos> coneCells(Vec3 eye, Vec3 look, double range) {
		int r = Mth.ceil(range);
		BlockPos c = BlockPos.containing(eye);
		List<BlockPos> out = new ArrayList<>();
		for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -r, -r), c.offset(r, r, r))) {
			if (inCone(eye, look, range, Vec3.atCenterOf(p))) out.add(p.immutable());
		}
		out.sort((a, b) -> Double.compare(Vec3.atCenterOf(a).distanceToSqr(eye), Vec3.atCenterOf(b).distanceToSqr(eye)));
		return out;
	}

	/** The fire segment (a {@link #FIRE_SEGMENT}-block world-grid cube) {@code cell} belongs to, as cube coordinates. */
	public static BlockPos fireSegment(BlockPos cell) {
		return new BlockPos(Math.floorDiv(cell.getX(), FIRE_SEGMENT), Math.floorDiv(cell.getY(), FIRE_SEGMENT),
				Math.floorDiv(cell.getZ(), FIRE_SEGMENT));
	}

	/** The batch origin of fire segment {@code key}: its cube's middle cell. */
	public static BlockPos fireSegmentOrigin(BlockPos key) {
		int m = FIRE_SEGMENT / 2;
		return new BlockPos(key.getX() * FIRE_SEGMENT + m, key.getY() * FIRE_SEGMENT + m, key.getZ() * FIRE_SEGMENT + m);
	}
}
