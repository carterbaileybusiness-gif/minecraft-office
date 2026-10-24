package local.carter.office.magic.spell.fire;

import local.carter.office.magic.cast.CastService;
import local.carter.office.magic.core.ChargeMath;
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
 * Fire Bolt (fire, slot 0, small): a fast bolt of flame from the eye along the look, {@link #SPEED} blocks a tick, at most
 * {@link #MAX_TICKS} ticks, no gravity, radius {@link #radius} ({@link #RADIUS} at a tap, twice that at full charge: a bigger
 * bolt). The first creature it meets takes {@code 4 × P} damage, burns for {@code FIRE_TICKS × (1 + charge)} ticks and is
 * knocked back {@code KNOCK × (1 + charge)}; a block stops it. No block changes.
 *
 * <p>Fx: the cast's default event (origin = eye, dir = look, charge); the client rebuilds the flight with {@link #spec} and
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

	/** The bolt's size at {@code charge}: {@code RADIUS × ChargeMath.size(charge)}. */
	public static double radius(float charge) {
		return RADIUS * ChargeMath.size(charge);
	}

	/** A tap's flight from {@code origin} along {@code dir}. */
	public static ProjectileSpec spec(Vec3 origin, Vec3 dir) {
		return spec(origin, dir, 0F);
	}

	/** The bolt's flight from {@code origin} along {@code dir} at {@code charge} (shared by the server and the client fx). */
	public static ProjectileSpec spec(Vec3 origin, Vec3 dir, float charge) {
		Vec3 d = dir.lengthSqr() < 1.0E-12 ? new Vec3(0, 0, 1) : dir.normalize();
		return new ProjectileSpec(origin, d.scale(SPEED), 0, radius(charge), MAX_TICKS, false);
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
		ProjectileSpec spec = spec(ctx.eye(), ctx.look(), ctx.charge());
		Vec3 dir = spec.velocity().normalize();
		if (!Projectiles.launch(ctx, spec, hit -> hit(ctx, dir, hit))) return CastOutcome.fizzle(CastService.BUSY_KEY);
		return CastOutcome.cast(ctx.fx());
	}

	private static void hit(CastContext ctx, Vec3 dir, ProjectileHit hit) {
		LivingEntity e = hit.entity();
		if (e == null) return;
		Hurt.damage(ctx, e, DAMAGE * ctx.power());
		if (e.isAlive()) {
			float size = ChargeMath.size(ctx.charge());
			e.igniteForTicks(Math.round(FIRE_TICKS * size));
			Hurt.knock(e, dir, KNOCK * size, KNOCK_UP);
		}
	}
}
