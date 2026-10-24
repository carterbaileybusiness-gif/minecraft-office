package local.carter.office.magic.spell.fire;

import local.carter.office.magic.cast.CastService;
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

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Fire Bolt (fire, slot 0, small): a fast bolt of flame from the eye along the look, {@link #SPEED} blocks a tick, radius
 * {@link #RADIUS}, at most {@link #MAX_TICKS} ticks, no gravity. The first creature it meets takes {@code 4 × P} damage, burns
 * for {@link #FIRE_TICKS} ticks and is knocked back {@link #KNOCK}; a block stops it. No block changes.
 *
 * <p>Fx: the cast's default event (origin = eye, dir = look); the client rebuilds the flight with {@link #spec} and
 * {@code Projectiles.step}, so no extra data is sent.
 */
public final class FireBolt implements Spell {
	public static final double SPEED = 2.5;
	public static final double RADIUS = 0.3;
	public static final int MAX_TICKS = 20;
	public static final float DAMAGE = 4;
	public static final int FIRE_TICKS = 60;
	public static final double KNOCK = 0.4;
	/** A little lift with the knockback, so the hit reads. */
	public static final double KNOCK_UP = 0.1;

	/** The bolt's flight from {@code origin} along {@code dir} (shared by the server and the client fx). */
	public static ProjectileSpec spec(Vec3 origin, Vec3 dir) {
		Vec3 d = dir.lengthSqr() < 1.0E-12 ? new Vec3(0, 0, 1) : dir.normalize();
		return new ProjectileSpec(origin, d.scale(SPEED), 0, RADIUS, MAX_TICKS, false);
	}

	@Override
	public Identifier id() {
		return SpellIds.FIRE_BOLT;
	}

	@Override
	public Element element() {
		return Element.FIRE;
	}

	@Override
	public int slot() {
		return Slots.SMALL_A;
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		ProjectileSpec spec = spec(ctx.eye(), ctx.look());
		Vec3 dir = spec.velocity().normalize();
		if (!Projectiles.launch(ctx, spec, hit -> hit(ctx, dir, hit))) return CastOutcome.fizzle(CastService.BUSY_KEY);
		return CastOutcome.cast(ctx.fx());
	}

	private static void hit(CastContext ctx, Vec3 dir, ProjectileHit hit) {
		LivingEntity e = hit.entity();
		if (e == null) return;
		Hurt.damage(ctx, e, DAMAGE * ctx.power());
		if (e.isAlive()) {
			e.igniteForTicks(FIRE_TICKS);
			Hurt.knock(e, dir, KNOCK, KNOCK_UP);
		}
	}
}
