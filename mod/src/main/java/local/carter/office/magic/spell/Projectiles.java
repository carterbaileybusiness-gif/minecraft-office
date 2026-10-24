package local.carter.office.magic.spell;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Entity-free projectiles: a position stepped once per server tick along {@link #step}, at most {@link #MAX} in flight
 * server-wide (separate from the lingering-effect cap). Each tick the segment from the last position to the next is clipped
 * against block colliders ({@code ClipContext.Block.COLLIDER}, fluids ignored) and swept for entities that pass
 * {@link TargetFilter#affects}, hitboxes widened by the spec's radius. A non-piercing projectile stops at its first hit; a
 * piercing one hits each entity once and stops at a block. Running out of ticks ends it without a hit. Server thread only.
 */
public final class Projectiles {
	public static final int MAX = 64;

	private static final Logger LOGGER = LoggerFactory.getLogger("office");

	private static final class Flight {
		final CastContext ctx;
		final ProjectileSpec spec;
		final Consumer<ProjectileHit> onHit;
		final @Nullable Consumer<Vec3> onMove;
		final Set<Integer> hit = new HashSet<>();
		int tick;

		Flight(CastContext ctx, ProjectileSpec spec, Consumer<ProjectileHit> onHit, @Nullable Consumer<Vec3> onMove) {
			this.ctx = ctx;
			this.spec = spec;
			this.onHit = onHit;
			this.onMove = onMove;
		}
	}

	private record EntityHit(LivingEntity entity, Vec3 pos, double dist) {}

	private static final List<Flight> FLIGHTS = new ArrayList<>();

	private Projectiles() {}

	/** Launches {@code spec}; it first moves on the next server tick. False (nothing launched) when {@link #MAX} are in flight. */
	public static boolean launch(CastContext ctx, ProjectileSpec spec, Consumer<ProjectileHit> onHit) {
		return launch(ctx, spec, onHit, null);
	}

	/** As {@link #launch(CastContext, ProjectileSpec, Consumer)}; {@code onMove} gets the position reached at the end of every tick. */
	public static boolean launch(CastContext ctx, ProjectileSpec spec, Consumer<ProjectileHit> onHit, @Nullable Consumer<Vec3> onMove) {
		if (FLIGHTS.size() >= MAX || spec.maxTicks() <= 0) return false;
		FLIGHTS.add(new Flight(ctx, spec, onHit, onMove));
		return true;
	}

	/**
	 * Position after {@code tick} ticks (pure; the client fx uses it too): each tick the position moves by the velocity, then
	 * the vertical velocity drops by {@code gravity}. So {@code origin + velocity × t − (0, gravity × t(t−1)/2, 0)}.
	 */
	public static Vec3 step(ProjectileSpec spec, int tick) {
		double t = tick;
		Vec3 o = spec.origin();
		Vec3 v = spec.velocity();
		return new Vec3(o.x + v.x * t, o.y + v.y * t - spec.gravity() * t * (t - 1) / 2, o.z + v.z * t);
	}

	public static int count() {
		return FLIGHTS.size();
	}

	/** Once per server tick: moves every projectile one step. */
	public static void tick() {
		if (FLIGHTS.isEmpty()) return;
		for (Flight f : List.copyOf(FLIGHTS)) {
			boolean keep;
			try {
				keep = advance(f);
			} catch (RuntimeException ex) {
				LOGGER.error("[office] Spell projectile of {} failed; dropping it", f.ctx.spell().id(), ex);
				keep = false;
			}
			if (!keep) FLIGHTS.remove(f);
		}
	}

	/** Drops every projectile (server stopping). */
	public static void clear() {
		FLIGHTS.clear();
	}

	private static boolean advance(Flight f) {
		ServerLevel level = f.ctx.level();
		ProjectileSpec spec = f.spec;
		Vec3 from = step(spec, f.tick);
		f.tick++;
		Vec3 to = step(spec, f.tick);
		if (!level.isLoaded(BlockPos.containing(to))) return false;

		BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
		boolean blocked = block.getType() != HitResult.Type.MISS;
		Vec3 end = blocked ? block.getLocation() : to;

		for (EntityHit h : sweep(f, level, from, end)) {
			if (!spec.pierce()) {
				move(f, h.pos());
				f.onHit.accept(new ProjectileHit(h.pos(), h.entity(), null));
				return false;
			}
			if (f.hit.add(h.entity().getId())) f.onHit.accept(new ProjectileHit(h.pos(), h.entity(), null));
		}
		move(f, end);
		if (blocked) {
			f.onHit.accept(new ProjectileHit(end, null, block));
			return false;
		}
		return f.tick < spec.maxTicks();
	}

	private static void move(Flight f, Vec3 pos) {
		if (f.onMove != null) f.onMove.accept(pos);
	}

	private static List<EntityHit> sweep(Flight f, ServerLevel level, Vec3 from, Vec3 end) {
		double r = Math.max(0, f.spec.radius());
		List<EntityHit> out = new ArrayList<>();
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(from, end).inflate(r),
				e -> TargetFilter.affects(f.ctx.caster(), e))) {
			AABB box = e.getBoundingBox().inflate(r);
			if (box.contains(from)) {
				out.add(new EntityHit(e, from, 0));
				continue;
			}
			Optional<Vec3> at = box.clip(from, end);
			at.ifPresent(p -> out.add(new EntityHit(e, p, from.distanceToSqr(p))));
		}
		out.sort(Comparator.comparingDouble(EntityHit::dist));
		return out;
	}
}
