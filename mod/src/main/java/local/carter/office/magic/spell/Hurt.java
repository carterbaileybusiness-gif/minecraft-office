package local.carter.office.magic.spell;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/** Damage and knockback for spells, both behind {@link TargetFilter}. */
public final class Hurt {
	private Hurt() {}

	/**
	 * Deals {@code amount} indirect-magic damage from the caster ({@code damageSources().indirectMagic(caster, caster)}).
	 *
	 * @return false if the target is filtered out or the damage did not land (invulnerability frames, dead, ...)
	 */
	public static boolean damage(CastContext ctx, LivingEntity target, float amount) {
		if (amount <= 0 || !TargetFilter.affects(ctx.caster(), target)) return false;
		return target.hurtServer(ctx.level(), ctx.level().damageSources().indirectMagic(ctx.caster(), ctx.caster()), amount);
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
}
