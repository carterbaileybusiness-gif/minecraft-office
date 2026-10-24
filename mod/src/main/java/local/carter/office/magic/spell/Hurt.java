package local.carter.office.magic.spell;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

import local.carter.office.magic.tree.Fork;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/** Damage and knockback for spells, both behind {@link TargetFilter}. */
public final class Hurt {
	/** {@code LivingEntity.lastHurt} is protected; 26.x runs unobfuscated, so the field is reached by its name. */
	private static final @Nullable Field LAST_HURT = lastHurtField();

	private Hurt() {}

	/**
	 * Deals {@code amount} indirect-magic damage from the caster ({@code damageSources().indirectMagic(caster, caster)}).
	 *
	 * <p>Spells own their hit timing: invulnerability frames are bypassed and restored afterwards (the target's
	 * {@code invulnerableTime} and {@code lastHurt}), so a spell hit neither eats nor grants i-frames. <b>Every call lands</b>,
	 * so a lingering effect that damages on a timer deals its full amount on every hit: scale per-tick damage for that
	 * (for example hit every 10 ticks, not every tick).
	 *
	 * <p><b>Per-cast entity budget.</b> One cast (caster + {@code castId}) hurts at most {@link Targets#MAX_ENTITIES} different
	 * creatures, whatever its forks add (extra bolts, splashes, zones): a creature it has not hurt yet is refused once the budget
	 * is spent. <b>Riders.</b> When the damage lands, the rank's riders ({@code SpellRank.riders()}: burn, a status effect,
	 * life steal) apply to the target.
	 *
	 * @return false if the target is filtered out, over the cast's budget or the damage did not land (dead, immune to magic, ...)
	 */
	public static boolean damage(CastContext ctx, LivingEntity target, float amount) {
		if (amount <= 0 || !TargetFilter.affects(ctx.caster(), target)) return false;
		if (!admit(ctx, target)) return false;
		int frames = target.getInvulnerableTime();
		float lastHurt = lastHurt(target);
		target.setInvulnerableTime(0);
		boolean landed;
		try {
			landed = target.hurtServer(ctx.level(), ctx.level().damageSources().indirectMagic(ctx.caster(), ctx.caster()), amount);
		} finally {
			target.setInvulnerableTime(frames);
			setLastHurt(target, lastHurt);
		}
		if (landed) ride(ctx, target, amount);
		return landed;
	}

	/** The cast's creatures so far, by (caster, castId); the oldest casts are forgotten (none of them still runs by then). */
	private static final Map<CastKey, Set<Integer>> HURT_BY_CAST = new LinkedHashMap<>(64, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<CastKey, Set<Integer>> eldest) {
			return size() > 1024;
		}
	};

	private record CastKey(UUID caster, long castId) {}

	/** True when {@code ctx} may hurt {@code e}: it already did, or it has hurt fewer than the budget so far (then {@code e} counts). */
	private static boolean admit(CastContext ctx, LivingEntity e) {
		Set<Integer> hit = HURT_BY_CAST.computeIfAbsent(new CastKey(ctx.caster().getUUID(), ctx.castId()), k -> new HashSet<>());
		if (hit.contains(e.getId())) return true;
		if (hit.size() >= Targets.MAX_ENTITIES) return false;
		hit.add(e.getId());
		return true;
	}

	/** The rank's riders on a hit that landed. */
	private static void ride(CastContext ctx, LivingEntity target, float amount) {
		for (Fork.Rider r : ctx.rank().riders()) {
			switch (r.kind()) {
				case IGNITE -> {
					if (target.isAlive()) target.igniteForTicks(Math.max(target.getRemainingFireTicks(), r.ticks()));
				}
				case EFFECT -> {
					if (target.isAlive() && r.effect() != null) target.addEffect(new MobEffectInstance(r.effect(), r.ticks(), r.amplifier()), ctx.caster());
				}
				case LIFESTEAL -> {
					if (ctx.caster().isAlive()) ctx.caster().heal(amount * r.share());
				}
			}
		}
	}

	/**
	 * Adds a push of {@code strength} along the horizontal part of {@code dir} plus {@code up} vertically, scaled by the
	 * target's knockback resistance, and marks the velocity for sync ({@code syncVelocity}, 26.3's {@code hurtMarked}).
	 * Protected entities ({@link TargetFilter#isProtected}) are never moved; callers filter by caster with
	 * {@link TargetFilter#pushable} as well.
	 *
	 * @return false if {@code e} is protected or removed
	 */
	public static boolean knock(Entity e, Vec3 dir, double strength, double up) {
		if (e.isRemoved() || TargetFilter.isProtected(e)) return false;
		double scale = 1;
		if (e instanceof LivingEntity l) scale = Math.max(0, 1 - l.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
		Vec3 flat = new Vec3(dir.x, 0, dir.z);
		Vec3 push = flat.lengthSqr() < 1e-8 ? Vec3.ZERO : flat.normalize().scale(strength * scale);
		e.setDeltaMovement(e.getDeltaMovement().add(push.x, up * scale, push.z));
		e.syncVelocity = true;
		e.needsSync = true;
		return true;
	}

	private static @Nullable Field lastHurtField() {
		try {
			Field f = LivingEntity.class.getDeclaredField("lastHurt");
			f.setAccessible(true);
			return f;
		} catch (ReflectiveOperationException | RuntimeException e) {
			LoggerFactory.getLogger("office").warn("[office] LivingEntity.lastHurt not found; spell hits may shorten melee i-frames", e);
			return null;
		}
	}

	private static float lastHurt(LivingEntity e) {
		try {
			return LAST_HURT == null ? 0 : LAST_HURT.getFloat(e);
		} catch (IllegalAccessException ex) {
			return 0;
		}
	}

	private static void setLastHurt(LivingEntity e, float v) {
		try {
			if (LAST_HURT != null) LAST_HURT.setFloat(e, v);
		} catch (IllegalAccessException ex) {
			// only the i-frame bookkeeping is affected
		}
	}
}
