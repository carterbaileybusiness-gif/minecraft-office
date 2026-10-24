package local.carter.office.magic.spell.lightning;

import java.util.ArrayList;
import java.util.List;

import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.Targets;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * How the Lightning lane's arcs find their targets in the world. The first target is whatever the caster struck; every hop after
 * it goes only to a hostile mob ({@link Enemy}, the lane's ruling: lightning leaps between foes, never to sheep, snow golems or
 * other bystanders). Each hop asks {@link Targets#radius} around the last target (so {@code TargetFilter} still spares players, pets,
 * villagers and the rest), and {@link ChainHops#next} picks the nearest one not yet struck that is in the last target's sight, so
 * lightning never leaps through a wall.
 */
final class Arcs {
	private Arcs() {}

	/** Where an arc meets {@code e}: the middle of its hitbox. */
	static Vec3 centre(LivingEntity e) {
		return e.getBoundingBox().getCenter();
	}

	/** Whether a hop may land on {@code e} (the lane filter, on top of {@code TargetFilter}). */
	static boolean hoppable(LivingEntity e) {
		return e instanceof Enemy;
	}

	/**
	 * {@code first}, then up to {@code hops} more targets, each the nearest hostile mob not yet struck within {@code range} of the
	 * last (centre to centre) and in its sight.
	 */
	static List<LivingEntity> chain(CastContext ctx, LivingEntity first, int hops, double range) {
		List<LivingEntity> out = new ArrayList<>();
		out.add(first);
		for (int hop = 0; hop < hops; hop++) {
			Vec3 from = centre(out.getLast());
			List<LivingEntity> near = Targets.radius(ctx, from, range);
			List<Vec3> points = new ArrayList<>(near.size());
			for (LivingEntity e : near) points.add(centre(e));
			int best = ChainHops.next(from, points, range,
					i -> hoppable(near.get(i)) && !out.contains(near.get(i)) && clear(ctx, from, points.get(i)));
			if (best < 0) break;
			out.add(near.get(best));
		}
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
