package local.carter.office.magic.spell;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Finding what a spell hits. Every query goes through {@link TargetFilter}, is sorted nearest first and returns at most
 * {@link #MAX_ENTITIES} entities (the per-cast entity budget).
 */
public final class Targets {
	public static final int MAX_ENTITIES = 64;
	/** How far {@link #ground} searches down from a point below the heightmap. */
	public static final int GROUND_SCAN = 32;

	private Targets() {}

	/**
	 * The nearest entity the caster's look ray hits within {@code range}, stopping at the first solid block. {@code forgiveness}
	 * inflates every hitbox (aim assist; 0 = exact).
	 */
	public static Optional<LivingEntity> ray(CastContext ctx, double range, double forgiveness) {
		Vec3 from = ctx.eye();
		Vec3 end = blockEnd(ctx, from, from.add(ctx.look().scale(range)));
		AABB sweep = new AABB(from, end).inflate(forgiveness + 1);
		LivingEntity best = null;
		double bestDist = Double.MAX_VALUE;
		for (LivingEntity e : ctx.level().getEntitiesOfClass(LivingEntity.class, sweep, e -> TargetFilter.affects(ctx.caster(), e))) {
			AABB box = e.getBoundingBox().inflate(forgiveness + e.getPickRadius());
			double d;
			if (box.contains(from)) {
				d = 0;
			} else {
				Optional<Vec3> at = box.clip(from, end);
				if (at.isEmpty()) continue;
				d = from.distanceToSqr(at.get());
			}
			if (d < bestDist) {
				bestDist = d;
				best = e;
			}
		}
		return Optional.ofNullable(best);
	}

	/** Where the caster's look ray meets a solid block within {@code range}, else the point at {@code range}. */
	public static Vec3 point(CastContext ctx, double range) {
		Vec3 from = ctx.eye();
		return blockEnd(ctx, from, from.add(ctx.look().scale(range)));
	}

	/**
	 * {@code at} moved to the ground of its column. At or above the {@code MOTION_BLOCKING} heightmap that is the heightmap;
	 * below it (caves, under roofs, the Nether) it is the top of the first {@code MOTION_BLOCKING} block at or below {@code at},
	 * searched down at most {@link #GROUND_SCAN} blocks ({@code at} unchanged if none).
	 */
	public static Vec3 ground(ServerLevel level, Vec3 at) {
		int x = Mth.floor(at.x);
		int z = Mth.floor(at.z);
		int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		if (at.y >= top) return new Vec3(at.x, top, at.z);
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, Mth.floor(at.y), z);
		for (int i = 0; i < GROUND_SCAN && m.getY() >= level.getMinY(); i++, m.move(Direction.DOWN)) {
			if (Heightmap.Types.MOTION_BLOCKING.isOpaque().test(level.getBlockState(m))) return new Vec3(at.x, m.getY() + 1, at.z);
		}
		return at;
	}

	/** Affected living entities whose hitbox is within {@code r} of {@code c}, nearest first. */
	public static List<LivingEntity> radius(CastContext ctx, Vec3 c, double r) {
		double r2 = r * r;
		List<LivingEntity> out = new ArrayList<>(ctx.level().getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(r),
				e -> TargetFilter.affects(ctx.caster(), e) && e.getBoundingBox().distanceToSqr(c) <= r2));
		out.sort(Comparator.comparingDouble(e -> e.getBoundingBox().distanceToSqr(c)));
		return cap(out);
	}

	/**
	 * Entities within {@code range} of the caster's eye whose hitbox centre lies within {@code halfAngleDeg} of the look,
	 * nearest first. {@code pushables} widens the filter to {@link TargetFilter#pushable} (items and XP orbs too).
	 */
	public static List<Entity> cone(CastContext ctx, double range, double halfAngleDeg, boolean pushables) {
		Vec3 eye = ctx.eye();
		Vec3 look = ctx.look().normalize();
		double cos = Math.cos(Math.toRadians(halfAngleDeg));
		double r2 = range * range;
		List<Entity> out = new ArrayList<>(ctx.level().getEntities((Entity) null, new AABB(eye, eye).inflate(range), e -> {
			if (pushables ? !TargetFilter.pushable(ctx.caster(), e) : !TargetFilter.affects(ctx.caster(), e)) return false;
			if (e.getBoundingBox().distanceToSqr(eye) > r2) return false;
			Vec3 to = e.getBoundingBox().getCenter().subtract(eye);
			double len = to.length();
			return len < 0.5 || to.dot(look) / len >= cos;
		}));
		out.sort(Comparator.comparingDouble(e -> e.getBoundingBox().distanceToSqr(eye)));
		return cap(out);
	}

	private static Vec3 blockEnd(CastContext ctx, Vec3 from, Vec3 to) {
		BlockHitResult hit = ctx.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.caster()));
		return hit.getType() == HitResult.Type.MISS ? to : hit.getLocation();
	}

	private static <T> List<T> cap(List<T> list) {
		return list.size() <= MAX_ENTITIES ? list : new ArrayList<>(list.subList(0, MAX_ENTITIES));
	}
}
