package local.carter.office.magic.spell.water;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Pure geometry of Tidal Push (Vec3 math only, no world access), shared by the server effect ({@link TidalPush}) and the
 * client fx, so the picture and the push follow the same wave.
 *
 * <p><b>Wave.</b> A front {@link #WIDTH} cells wide starts at the caster's feet and advances along the horizontal look one
 * block every {@link #TICKS_PER_BLOCK} ticks for {@link #LENGTH} blocks: at tick {@code t} (1..{@link #FRONT_TICKS}) it stands at
 * distance {@code t / 2}. It is {@link #height} cells tall (1 at the feet, then 3, 2 and 1 as it spends itself). Each cell it
 * passes lives {@link #CELL_LIFE} ticks (on the client: only in air). The path is cut into {@link #SEGMENT}-block segments,
 * each with its own origin on the axis, so every cell is well within the medium radius (6) of its segment's origin.
 *
 * <p><b>Cone.</b> The instant shove reaches {@link #range} = 8 + 4 × charge from the eye within {@link #HALF_ANGLE}° of the
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

	private final Vec3 start;
	private final Vec3 dir;
	private final Vec3 side;

	/**
	 * @param start the wave's start: the bottom centre of the caster's feet cell
	 * @param look the caster's look; only its horizontal part is used (straight up or down → +Z)
	 */
	public TidalWaveGeometry(Vec3 start, Vec3 look) {
		this.start = start;
		this.dir = horizontal(look);
		this.side = new Vec3(-dir.z, 0, dir.x);
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

	/** Cone reach for a charge 0..1. */
	public static double range(float charge) {
		return 8 + 4 * Mth.clamp(charge, 0F, 1F);
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
		return start.add(dir.scale(distance(tick)));
	}

	/** The point {@code lateral} blocks to the right of the front's middle at {@code tick}. */
	public Vec3 frontPoint(int tick, double lateral) {
		return frontCentre(tick).add(side.scale(lateral));
	}

	/**
	 * The front's cells at {@code tick} (empty when inactive): columns at lateral offsets −2..2 (left to right), each
	 * {@link #height} tall from the start's level up. Diagonal fronts may merge two columns into one cell (no duplicates).
	 */
	public List<BlockPos> front(int tick) {
		if (!frontActive(tick)) return List.of();
		double d = distance(tick);
		int h = height(d);
		Set<BlockPos> out = new LinkedHashSet<>();
		int half = WIDTH / 2;
		for (int k = -half; k <= half; k++) {
			BlockPos base = BlockPos.containing(frontPoint(tick, k));
			for (int y = 0; y < h; y++) out.add(base.above(y));
		}
		return new ArrayList<>(out);
	}

	/** The column base cell (lowest row) of lateral offset {@code k} (−2..2) at {@code tick}. */
	public BlockPos column(int tick, int k) {
		return BlockPos.containing(frontPoint(tick, k));
	}

	/** The segment (0..{@link #SEGMENTS} − 1) a front at distance {@code d} is in: (0, 4] → 0, (4, 8] → 1. */
	public static int segment(double d) {
		return Mth.clamp((int) Math.ceil(d / SEGMENT) - 1, 0, SEGMENTS - 1);
	}

	/** Segment {@code s}'s batch origin: the cell on the axis in the segment's middle, at the start's level. */
	public BlockPos segmentOrigin(int s) {
		return BlockPos.containing(start.add(dir.scale(s * SEGMENT + SEGMENT / 2.0)));
	}

	/** Whether point {@code p} (an entity's position) is inside the front at {@code tick}. */
	public boolean inFront(int tick, Vec3 p) {
		if (!frontActive(tick)) return false;
		Vec3 rel = p.subtract(start);
		double axial = rel.dot(dir);
		double lateral = rel.dot(side);
		return Math.abs(axial - distance(tick)) <= FRONT_DEPTH && Math.abs(lateral) <= WIDTH / 2.0 + 0.5
				&& rel.y >= -1 && rel.y <= 3;
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
