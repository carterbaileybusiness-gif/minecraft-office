package local.carter.office.magic.spell;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.cast.CastService;


import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * What skill-tree forks share (server side): blasts, throws, pulls and lingering zones. Everything goes through
 * {@link Targets}, {@link Hurt} and {@link TargetFilter} as the spells do (so the cast's 64-creature budget, line of sight and the
 * protected entities hold), and none of it changes a block.
 */
public final class RankKit {
	/** Lingering zones sync pulled velocities every this many ticks (the Gravity Well throttle). */
	static final int SYNC_EVERY = 2;

	private RankKit() {}

	/**
	 * A blast at {@code at}: every creature within {@code radius} that it reaches (nothing solid between {@code at} and the body's
	 * centre, feet or top), except {@code skip}, takes {@code damage}; then, if alive and {@code ignite > 0}, burns that long, and
	 * if a control effect may take it and {@code knock > 0}, is thrown {@code knock} away from {@code at} (+ {@code up}). Only
	 * creatures within the cast's budget ({@link Hurt#admit}) are touched at all.
	 *
	 * @return the creatures it hurt
	 */
	public static List<LivingEntity> blast(CastContext ctx, Vec3 at, double radius, float damage, @Nullable Entity skip, int ignite,
			double knock, double up) {
		List<LivingEntity> hurt = new ArrayList<>();
		for (LivingEntity e : Targets.radius(ctx, at, radius)) {
			if (e == skip || !reaches(ctx.level(), at, e.getBoundingBox())) continue;
			// The cast's budget first: past it a creature is neither hurt nor burnt nor thrown.
			if (!Hurt.admit(ctx, e)) continue;
			if (Hurt.damage(ctx, e, damage)) hurt.add(e);
			if (!e.isAlive()) continue;
			if (ignite > 0) e.igniteForTicks(Math.max(e.getRemainingFireTicks(), ignite));
			if (knock > 0 && TargetFilter.controllable(ctx.caster(), e)) throwFrom(e, at, knock, up);
		}
		return hurt;
	}

	/** Gives every creature a control effect may take within {@code radius} of {@code at} (in its sight) {@code effect}. */
	public static void afflict(CastContext ctx, Vec3 at, double radius, Holder<MobEffect> effect, int ticks, int amplifier) {
		for (LivingEntity e : Targets.radius(ctx, at, radius)) {
			if (TargetFilter.controllable(ctx.caster(), e) && reaches(ctx.level(), at, e.getBoundingBox()) && Hurt.admit(ctx, e)) {
				e.addEffect(new MobEffectInstance(effect, ticks, amplifier), ctx.caster());
			}
		}
	}

	/**
	 * How long a self-buff of {@code want} ticks may last for {@code ctx}'s cast: at most half the spell's cooldown at the
	 * caster's rank ({@code CastService.cost}), so no buff can be kept up for good by casting again (Carter's ruling: no
	 * permanent self-buffs). At least 1.
	 */
	public static int buffTicks(CastContext ctx, int want) {
		int cooldown = CastService.cost(ctx.spell().cost(), ctx.rank()).cooldownTicks();
		return Math.max(1, Math.min(want, cooldown / 2));
	}

	/** Throws {@code e} {@code strength} away from {@code from} (level) plus {@code up} ({@link Hurt#knock}). */
	public static void throwFrom(Entity e, Vec3 from, double strength, double up) {
		Vec3 away = e.getBoundingBox().getCenter().subtract(from);
		Hurt.knock(e, away.horizontalDistanceSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : away, strength, up);
	}

	/**
	 * True when nothing solid stands between {@code from} and {@code box}'s centre, feet or top (any level: the client fx asks the
	 * same of its own world, so a drawn arc never goes through a wall the server's would not).
	 */
	public static boolean reaches(Level level, Vec3 from, AABB box) {
		Vec3 c = box.getCenter();
		for (Vec3 to : new Vec3[] {c, new Vec3(c.x, box.minY + 0.1, c.z), new Vec3(c.x, box.maxY - 0.1, c.z)}) {
			if (clear(level, from, to)) return true;
		}
		return false;
	}

	static boolean clear(Level level, Vec3 a, Vec3 b) {
		if (a.distanceToSqr(b) < 1.0E-8) return true;
		return level.clip(new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()))
				.getType() == HitResult.Type.MISS;
	}

	/**
	 * A lingering zone round the segment {@code a → b} (a point when they are equal), {@code radius} wide, for {@code life} ticks:
	 * every {@code every} ticks each creature in it (in sight of the segment's nearest point) takes {@code damage} (none at 0),
	 * burns {@code ignite} ticks and gets {@code effect}; every tick what a control effect may take is pulled toward the segment
	 * at {@code pull} blocks a tick (none at 0; items and XP orbs too). Its own caster slot; skipped (no error) when the caster's
	 * effects are full. No block changes.
	 */
	public record Zone(Vec3 a, Vec3 b, double radius, int life, int every, float damage, int ignite, @Nullable Holder<MobEffect> effect,
			int effectTicks, int amplifier, double pull) {
		public static Zone at(Vec3 c, double radius, int life) {
			return new Zone(c, c, radius, life, 10, 0, 0, null, 0, 0, 0);
		}

		public Zone between(Vec3 from, Vec3 to) {
			return new Zone(from, to, radius, life, every, damage, ignite, effect, effectTicks, amplifier, pull);
		}

		public Zone hurting(float dmg, int everyTicks) {
			return new Zone(a, b, radius, life, everyTicks, dmg, ignite, effect, effectTicks, amplifier, pull);
		}

		public Zone burning(int ticks) {
			return new Zone(a, b, radius, life, every, damage, ticks, effect, effectTicks, amplifier, pull);
		}

		public Zone afflicting(Holder<MobEffect> e, int ticks, int amp) {
			return new Zone(a, b, radius, life, every, damage, ignite, e, ticks, amp, pull);
		}

		public Zone pulling(double speed) {
			return new Zone(a, b, radius, life, every, damage, ignite, effect, effectTicks, amplifier, speed);
		}

		/** The nearest point of the segment to {@code p}. */
		public Vec3 nearest(Vec3 p) {
			Vec3 ab = b.subtract(a);
			double l2 = ab.lengthSqr();
			if (l2 < 1.0E-8) return a;
			double t = Math.clamp(p.subtract(a).dot(ab) / l2, 0, 1);
			return a.add(ab.scale(t));
		}
	}

	/** Starts {@code z} for {@code ctx}'s caster; false (nothing happens) when its effects are full. */
	public static boolean zone(CastContext ctx, Zone z) {
		return ActiveEffects.add(ctx.level(), ctx.caster().getUUID(), new ZoneEffect(ctx, z));
	}

	/**
	 * A shock ring growing from {@code centre} to {@code radius} over {@code ticks}: each creature it passes (level distance, within
	 * 3 above or below, in sight of the centre) takes {@code damage} once and, if a control effect may take it, is thrown
	 * {@code knock} outward (+ {@code up}). Its own caster slot; skipped when the caster's effects are full. No block changes.
	 */
	public static boolean ring(CastContext ctx, Vec3 centre, double radius, int ticks, float damage, double knock, double up) {
		return ActiveEffects.add(ctx.level(), ctx.caster().getUUID(), new RingEffect(ctx, centre, radius, Math.max(1, ticks), damage, knock, up));
	}

	private static final class RingEffect implements LingeringEffect {
		private final CastContext ctx;
		private final Vec3 centre;
		private final double radius;
		private final int ticks;
		private final float damage;
		private final double knock;
		private final double up;
		private final Set<Integer> passed = new HashSet<>();

		RingEffect(CastContext ctx, Vec3 centre, double radius, int ticks, float damage, double knock, double up) {
			this.ctx = ctx;
			this.centre = centre;
			this.radius = radius;
			this.ticks = ticks;
			this.damage = damage;
			this.knock = knock;
			this.up = up;
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			double r = radius * Math.min(1, age / (double) ticks);
			AABB box = new AABB(centre, centre).inflate(r + 1, 3, r + 1);
			for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, e -> TargetFilter.affects(ctx.caster(), e))) {
				Vec3 m = e.getBoundingBox().getCenter();
				double dx = m.x - centre.x, dz = m.z - centre.z;
				if (dx * dx + dz * dz > r * r || passed.contains(e.getId()) || !reaches(level, centre, e.getBoundingBox())) continue;
				passed.add(e.getId());
				if (!Hurt.admit(ctx, e)) continue;
				if (damage > 0) Hurt.damage(ctx, e, damage);
				if (e.isAlive() && knock > 0 && TargetFilter.controllable(ctx.caster(), e)) throwFrom(e, centre, knock, up);
			}
			return age < ticks;
		}

		@Override
		public String toString() {
			return "RankKit.Ring[" + ctx.caster().getScoreboardName() + " " + ctx.spell().id().getPath() + "]";
		}
	}

	private static final class ZoneEffect implements LingeringEffect {
		private final CastContext ctx;
		private final Zone z;
		private final AABB box;

		ZoneEffect(CastContext ctx, Zone z) {
			this.ctx = ctx;
			this.z = z;
			this.box = new AABB(z.a(), z.b()).inflate(z.radius() + 1);
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			if (age > z.life()) return false;
			if (!level.isLoaded(BlockPos.containing(z.a()))) return false;
			boolean pulse = z.every() > 0 && age % z.every() == 0;
			if (z.pull() > 0) pull(level, age);
			if (pulse) hurt(level);
			return age < z.life();
		}

		private void hurt(ServerLevel level) {
			double r2 = z.radius() * z.radius();
			for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, e -> TargetFilter.affects(ctx.caster(), e))) {
				Vec3 m = e.getBoundingBox().getCenter();
				Vec3 near = z.nearest(m);
				if (e.getBoundingBox().distanceToSqr(near) > r2 || !reaches(level, near.add(0, 0.5, 0), e.getBoundingBox())) continue;
				if (!Hurt.admit(ctx, e)) continue;
				if (z.damage() > 0) Hurt.damage(ctx, e, z.damage());
				if (!e.isAlive()) continue;
				if (z.ignite() > 0) e.igniteForTicks(Math.max(e.getRemainingFireTicks(), z.ignite()));
				if (z.effect() != null && TargetFilter.controllable(ctx.caster(), e)) {
					e.addEffect(new MobEffectInstance(z.effect(), z.effectTicks(), z.amplifier()), ctx.caster());
				}
			}
		}

		private void pull(ServerLevel level, int age) {
			double r2 = z.radius() * z.radius();
			int n = 0;
			for (Entity e : level.getEntities((Entity) null, box, e -> TargetFilter.controllable(ctx.caster(), e))) {
				if (n++ >= Targets.MAX_ENTITIES) break;
				Vec3 m = e.getBoundingBox().getCenter();
				Vec3 near = z.nearest(m);
				Vec3 to = near.subtract(m);
				if (to.lengthSqr() > r2 || to.lengthSqr() < 0.25) continue;
				Vec3 v = to.normalize().scale(z.pull());
				e.setDeltaMovement(e.getDeltaMovement().scale(0.6).add(v.x, Math.max(v.y, 0.02), v.z));
				e.resetFallDistance();
				if (age % SYNC_EVERY == 0) {
					e.syncVelocity = true;
					e.needsSync = true;
				}
			}
		}

		@Override
		public String toString() {
			return "RankKit.Zone[" + ctx.caster().getScoreboardName() + " " + ctx.spell().id().getPath() + "]";
		}
	}
}
