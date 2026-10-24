package local.carter.office.magic.spell.earth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import local.carter.office.magic.core.Shapes;
import local.carter.office.magic.core.Shapes.Cell;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Where and when Earth Spikes burst (pure: block maths only; the world comes in as a "has a collision shape" predicate, so the
 * server effect and the client fx run the same rule on their own copy of the world).
 *
 * <p>The line ({@code Shapes.line}) starts at the cell {@link #START} ahead of the caster's feet and runs {@link #length} cells
 * along the flat look. Each spike stands in the open cell on top of the ground of its column ({@link #path}): from the last
 * spike's level it climbs a step of at most {@link #MAX_CLIMB} or drops at most {@link #MAX_DROP}. A wall (no open cell within
 * a climb) or a pit (no ground within a drop) ends the line there, so spikes never pass through a wall. Spike {@code i} bursts on
 * tick {@code i} of the cast ({@link #burstTick}) and stands {@link #LIFE} ticks.
 */
public final class EarthSpikesGeometry {
	/** Spikes in the line at charge 0, and how many more full charge adds. */
	public static final int BASE_LENGTH = 8;
	public static final int CHARGE_LENGTH = 4;
	/** How far ahead of the caster's feet the first spike stands. */
	public static final double START = 1.2;
	/** How high a step the line climbs, and how far down it reaches for ground. */
	public static final int MAX_CLIMB = 1;
	public static final int MAX_DROP = 3;
	/** How long each spike stands (fx). */
	public static final int LIFE = 60;
	/** How tall a spike is, in blocks: what it hits stands in its cell up to this high. */
	public static final double HEIGHT = 1.7;

	private EarthSpikesGeometry() {}

	/** 8 + 4 × charge cells (rounded). */
	public static int length(float charge) {
		return BASE_LENGTH + Math.round(CHARGE_LENGTH * Math.max(0F, Math.min(1F, charge)));
	}

	/** The first cell of the line for a caster whose feet are at {@code feet}, facing {@code forward} (made flat). */
	public static BlockPos start(Vec3 feet, Vec3 forward) {
		Vec3 f = EarthWallGeometry.flat(forward);
		return BlockPos.containing(feet.x + f.x * START, feet.y + 0.01, feet.z + f.z * START);
	}

	/** The tick spike {@code i} bursts (0 for the first, one per tick). */
	public static int burstTick(int i) {
		return i;
	}

	/**
	 * The spike cells of a line from {@code start} along {@code forward} (flat), {@code length} long, following the ground
	 * ({@code solid}: the cell has a collision shape). Ends early at a wall or a pit.
	 */
	public static List<BlockPos> path(BlockPos start, Vec3 forward, int length, Predicate<BlockPos> solid) {
		Vec3 f = EarthWallGeometry.flat(forward);
		List<Cell> line = Shapes.line(f.x, f.z, length);
		List<BlockPos> out = new ArrayList<>(line.size());
		int y = start.getY();
		for (Cell c : line) {
			BlockPos p = new BlockPos(start.getX() + c.x(), y, start.getZ() + c.z());
			BlockPos at = ground(p, solid);
			if (at == null) break;
			out.add(at);
			y = at.getY();
		}
		return out;
	}

	/** The open cell on the ground of {@code p}'s column, near {@code p}'s level (see the class doc); null for a wall or a pit. */
	static BlockPos ground(BlockPos p, Predicate<BlockPos> solid) {
		if (solid.test(p)) {
			for (int i = 1; i <= MAX_CLIMB; i++) {
				BlockPos up = p.above(i);
				if (!solid.test(up)) return solid.test(up.above()) ? null : up; // a step needs headroom, else it is a wall
			}
			return null;
		}
		BlockPos q = p;
		for (int i = 0; i <= MAX_DROP; i++) {
			if (solid.test(q.below())) return q;
			q = q.below();
		}
		return null;
	}
}
