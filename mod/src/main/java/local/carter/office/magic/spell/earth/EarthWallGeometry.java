package local.carter.office.magic.spell.earth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Where and when Earth Wall's columns rise (pure: block maths only; the world comes in as an {@code open} predicate, so the
 * server effect and the client fx run the same rule on their own copy of the world).
 *
 * <p>The wall stands {@link #AHEAD} blocks ahead of the caster's feet (closer if something is in the way), across the look: it
 * runs along the block axis perpendicular to the look's dominant horizontal axis, so it is always a straight row of blocks.
 * Column {@code k} ({@code −half..half}) stands at {@code origin + axis × k}. Columns rise from the centre outward: column
 * {@code k} starts rising at {@link #riseStart} and is placed {@link #RISE_TICKS} later ({@link #placeTick}), the last at
 * tick {@link #LAST_PLACE}. A column only rises if the column inside it ({@link #inner}) rose, so the wall never jumps a gap
 * into the next room. Each column finds its ground near the origin's level ({@link #base}: it climbs up to
 * {@link #MAX_CLIMB}, drops down to {@link #MAX_DROP}) and stacks {@code height} cells there, stopping at the first cell that
 * is not open ({@link #cells}). With these limits every cell is within 6 of the origin (one MEDIUM batch).
 */
public final class EarthWallGeometry {
	/** How far ahead of the caster's feet the wall stands. */
	public static final double AHEAD = 3;
	/** The wall never stands closer than this (when something is in the way). */
	public static final double MIN_AHEAD = 1;
	/** How long the wall lasts, from the cast. */
	public static final int DURATION = 600;
	/** Ticks a column takes to rise out of the ground before it is placed. */
	public static final int RISE_TICKS = 3;
	/** The tick the last column is placed (the wall rises column by column over 6 ticks). */
	public static final int LAST_PLACE = 6;
	/** How far a column climbs onto a step, and how far down it reaches for ground. */
	public static final int MAX_CLIMB = 2;
	public static final int MAX_DROP = 3;

	private final BlockPos origin;
	private final int ax;
	private final int az;
	private final int width;
	private final int height;

	/** A wall centred on {@code origin} running along {@code (ax, 0, az)} (a unit block axis), {@code width} columns of {@code height}. */
	public EarthWallGeometry(BlockPos origin, int ax, int az, int width, int height) {
		this.origin = origin.immutable();
		this.ax = ax;
		this.az = az;
		this.width = Math.max(1, width | 1);
		this.height = Math.max(1, height);
	}

	/** The wall for a caster whose feet are at {@code feet}, facing {@code forward} (horizontal), {@code ahead} blocks out. */
	public static EarthWallGeometry of(Vec3 feet, Vec3 forward, double ahead, float charge) {
		Vec3 f = flat(forward);
		int[] axis = axis(f);
		BlockPos origin = BlockPos.containing(feet.add(f.scale(Math.max(MIN_AHEAD, Math.min(AHEAD, ahead)))));
		return new EarthWallGeometry(origin, axis[0], axis[1], width(charge), height(charge));
	}

	/** 5 columns, 7 from charge 0.5. */
	public static int width(float charge) {
		return charge >= 0.5F ? 7 : 5;
	}

	/** 3 cells high, 4 at full charge. */
	public static int height(float charge) {
		return charge >= 0.999F ? 4 : 3;
	}

	/** The block axis the wall runs along for a look {@code forward}: across its dominant horizontal axis. */
	public static int[] axis(Vec3 forward) {
		return Math.abs(forward.x) >= Math.abs(forward.z) ? new int[] {0, 1} : new int[] {1, 0};
	}

	/** {@code v} without its vertical part, unit length (+Z if it has none). */
	public static Vec3 flat(Vec3 v) {
		Vec3 f = new Vec3(v.x, 0, v.z);
		double l = f.length();
		return l < 1.0E-6 || !Double.isFinite(l) ? new Vec3(0, 0, 1) : f.scale(1 / l);
	}

	public BlockPos origin() {
		return origin;
	}

	public int ax() {
		return ax;
	}

	public int az() {
		return az;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public int half() {
		return width / 2;
	}

	/** Column offsets in rising order: 0, −1, 1, −2, 2, … */
	public int[] order() {
		int[] o = new int[width];
		o[0] = 0;
		for (int r = 1, i = 1; r <= half(); r++) {
			o[i++] = -r;
			o[i++] = r;
		}
		return o;
	}

	/** The tick column {@code k} starts rising out of the ground (0 for the centre, {@code LAST_PLACE − RISE_TICKS} at the ends). */
	public int riseStart(int k) {
		int half = half();
		return half == 0 ? 0 : Math.round(Math.abs(k) * (LAST_PLACE - RISE_TICKS) / (float) half);
	}

	/** The tick column {@code k}'s blocks are placed. */
	public int placeTick(int k) {
		return riseStart(k) + RISE_TICKS;
	}

	/** The column one step closer to the centre (a column rises only if that one did); 0 for the centre itself. */
	public static int inner(int k) {
		return k - Integer.signum(k);
	}

	/** Column {@code k}'s cell at the origin's level. */
	public BlockPos nominal(int k) {
		return origin.offset(ax * k, 0, az * k);
	}

	/**
	 * The bottom cell of a column whose nominal cell is {@code nominal}: the lowest open cell above solid ground. On open ground
	 * it reaches down at most {@link #MAX_DROP} for something to stand on; in a step it climbs at most {@link #MAX_CLIMB}.
	 * Null when neither finds ground (over a pit, into a high wall).
	 */
	public static @Nullable BlockPos base(BlockPos nominal, Predicate<BlockPos> open) {
		BlockPos p = nominal;
		if (open.test(p)) {
			for (int i = 0; i <= MAX_DROP; i++) {
				if (!open.test(p.below())) return p;
				if (i < MAX_DROP) p = p.below();
			}
			return null;
		}
		for (int i = 0; i < MAX_CLIMB; i++) {
			p = p.above();
			if (open.test(p)) return p;
		}
		return null;
	}

	/** The column's cells from {@code base} up, at most {@code height}, stopping below the first cell that is not open. */
	public List<BlockPos> cells(BlockPos base, Predicate<BlockPos> open) {
		List<BlockPos> out = new ArrayList<>(height);
		for (int i = 0; i < height; i++) {
			BlockPos c = base.above(i);
			if (!open.test(c)) break;
			out.add(c);
		}
		return out;
	}
}
