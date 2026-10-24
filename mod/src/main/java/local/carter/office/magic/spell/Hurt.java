package local.carter.office.magic.spell;

import java.lang.reflect.Field;

import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

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
	 * @return false if the target is filtered out or the damage did not land (dead, immune to magic, ...)
	 */
	public static boolean damage(CastContext ctx, LivingEntity target, float amount) {
		if (amount <= 0 || !TargetFilter.affects(ctx.caster(), target)) return false;
		int frames = target.getInvulnerableTime();
		float lastHurt = lastHurt(target);
		target.setInvulnerableTime(0);
		try {
			return target.hurtServer(ctx.level(), ctx.level().damageSources().indirectMagic(ctx.caster(), ctx.caster()), amount);
		} finally {
			target.setInvulnerableTime(frames);
			setLastHurt(target, lastHurt);
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
