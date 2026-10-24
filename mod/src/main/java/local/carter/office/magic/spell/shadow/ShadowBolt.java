package local.carter.office.magic.spell.shadow;

import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.Hurt;
import local.carter.office.magic.spell.ProjectileHit;
import local.carter.office.magic.spell.ProjectileSpec;
import local.carter.office.magic.spell.Projectiles;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.TargetFilter;

import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Shadow Bolt (Shadow, slot 0, small): a bolt of shadow that saps strength. An entity-free projectile from the eye along the
 * look ({@link #SPEED} blocks a tick, radius {@link #RADIUS}, at most {@link #MAX_TICKS} ticks, no gravity). The first entity it
 * hits takes 4 × P damage and Weakness I for {@link #WEAKNESS_TICKS} ticks. It stops at the first block. No block changes.
 *
 * <p>Fx: {@code origin} = the projectile's start (the eye), {@code dir} = its unit direction, {@code target} = where it would
 * meet a block (or its end of range), {@code duration} = ticks to get there; {@code data} = {speed, radius}. The client flies
 * the same {@link Projectiles#step} path and stops it at the first entity its own sweep meets.
 */
public final class ShadowBolt implements Spell {
	public static final double SPEED = 2.2;
	public static final double RADIUS = 0.3;
	public static final int MAX_TICKS = 24;
	public static final float DAMAGE = 4;
	public static final int WEAKNESS_TICKS = 60;
	/** Weakness I. */
	public static final int WEAKNESS_AMPLIFIER = 0;

	@Override
	public Identifier id() {
		return SpellIds.SHADOW_BOLT;
	}

	@Override
	public Element element() {
		return Element.SHADOW;
	}

	@Override
	public int slot() {
		return Slots.SMALL_A;
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		Vec3 dir = ctx.look().normalize();
		Vec3 origin = ctx.eye();
		ProjectileSpec spec = spec(origin, dir);
		float damage = DAMAGE * ctx.power();
		if (!Projectiles.launch(ctx, spec, hit -> hit(ctx, damage, hit))) return CastOutcome.fizzle("magic.office.busy");

		// Where the path meets a block, so every client stops the bolt at the same wall.
		Vec3 end = Projectiles.step(spec, MAX_TICKS);
		BlockHitResult block = ctx.level().clip(new ClipContext(origin, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
				CollisionContext.empty()));
		Vec3 stop = block.getType() == HitResult.Type.MISS ? end : block.getLocation();
		int ticks = Math.min(MAX_TICKS, (int) Math.ceil(stop.distanceTo(origin) / SPEED));
		return CastOutcome.cast(ctx.fx().withTarget(stop).withDuration(Math.max(1, ticks)).withData((float) SPEED, (float) RADIUS));
	}

	public static ProjectileSpec spec(Vec3 origin, Vec3 dir) {
		return new ProjectileSpec(origin, dir.scale(SPEED), 0, RADIUS, MAX_TICKS, false);
	}

	private static void hit(CastContext ctx, float damage, ProjectileHit hit) {
		LivingEntity e = hit.entity();
		if (e == null || !TargetFilter.affects(ctx.caster(), e)) return;
		Hurt.damage(ctx, e, damage);
		if (e.isAlive()) e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, WEAKNESS_TICKS, WEAKNESS_AMPLIFIER), ctx.caster());
	}
}
