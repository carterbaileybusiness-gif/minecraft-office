package local.carter.office.magic.spell.lightning;

import java.util.ArrayList;
import java.util.List;

import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.Targets;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * How the Lightning lane's arcs find their targets in the world: the candidates near a struck target (through {@link Targets}, so
 * {@code TargetFilter} spares players, pets, villagers and the rest), {@link ChainHops} to order them, and a line of sight between
 * each pair, so lightning never leaps through a wall.
 */
final class Arcs {
	private Arcs() {}

	/** Where an arc meets {@code e}: the middle of its hitbox. */
	static Vec3 centre(LivingEntity e) {
		return e.getBoundingBox().getCenter();
	}

	/**
	 * {@code first}, then up to {@code hops} more targets, each the nearest not yet struck within {@code range} of the last (centre
	 * to centre) and in its sight.
	 */
	static List<LivingEntity> chain(CastContext ctx, LivingEntity first, int hops, double range) {
		Vec3 start = centre(first);
		List<LivingEntity> found = new ArrayList<>();
		found.add(first);
		if (hops > 0) {
			// Every candidate the whole chain could reach (nearest first, at most the per-cast entity budget).
			for (LivingEntity e : Targets.radius(ctx, start, range * hops + 1)) {
				if (e != first) found.add(e);
			}
		}
		List<Vec3> points = new ArrayList<>(found.size());
		for (LivingEntity e : found) points.add(centre(e));
		int[] order = ChainHops.select(points, 0, hops, range, (a, b) -> clear(ctx, points.get(a), points.get(b)));
		List<LivingEntity> out = new ArrayList<>(order.length);
		for (int i : order) out.add(found.get(i));
		return out;
	}

	/** True when no block collider stands between {@code a} and {@code b}. */
	static boolean clear(CastContext ctx, Vec3 a, Vec3 b) {
		return ctx.level().clip(new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.caster())).getType()
				== HitResult.Type.MISS;
	}

	static int[] ids(List<LivingEntity> struck) {
		int[] ids = new int[struck.size()];
		for (int i = 0; i < ids.length; i++) ids[i] = struck.get(i).getId();
		return ids;
	}
}
