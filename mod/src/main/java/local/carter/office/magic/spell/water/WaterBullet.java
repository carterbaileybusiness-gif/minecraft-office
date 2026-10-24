package local.carter.office.magic.spell.water;

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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Water Bullet (Water, slot 0, small): a hard jet of water that knocks foes back. An entity-free projectile from the eye along
 * the look ({@link #SPEED} blocks a tick, radius {@link #RADIUS}, at most {@link #MAX_TICKS} ticks, no gravity). The first
 * entity it hits takes 3 × P damage, is knocked {@link #KNOCK} along the travel and is extinguished. No block changes.
 *
 * <p>Fx: {@code origin} = the projectile's start (the eye), {@code dir} = its unit direction, {@code target} = where it would
 * meet a block (or its end of range), {@code duration} = ticks to get there; {@code data} = {speed, radius}. The client flies
 * the same {@link Projectiles#step} path and stops it at the first entity its own sweep meets.
 */
public final class WaterBullet implements Spell {
	public static final double SPEED = 2.8;
	public static final double RADIUS = 0.25;
	public static final int MAX_TICKS = 20;
	public static final float DAMAGE = 3;
	public static final double KNOCK = 1.2;
	/** A little lift on every hit so ground friction doesn't eat the push at once (plus the travel's own upward part). */
	public static final double LIFT = 0.1;
	/** Cap on the upward push, so a jet fired steeply up lifts a foe a little instead of launching it. */
	public static final double MAX_UP = 0.5;

	@Override
	public Identifier id() {
		return SpellIds.WATER_BULLET;
	}

	@Override
	public Element element() {
		return Element.WATER;
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
		if (!Projectiles.launch(ctx, spec, hit -> hit(ctx, dir, damage, hit))) return CastOutcome.fizzle("magic.office.busy");

		// Where the path meets a block, so every client stops the jet at the same wall.
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

	private static void hit(CastContext ctx, Vec3 dir, float damage, ProjectileHit hit) {
		LivingEntity e = hit.entity();
		if (e == null) return;
		Hurt.damage(ctx, e, damage);
		Hurt.knock(e, dir, KNOCK, Math.min(MAX_UP, LIFT + KNOCK * Math.max(0, dir.y)));
		e.extinguishFire();
	}
}
