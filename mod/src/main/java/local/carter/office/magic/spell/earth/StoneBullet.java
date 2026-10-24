package local.carter.office.magic.spell.earth;

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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Stone Bullet (Earth, slot 0, small): a heavy stone flung from the hand. An entity-free projectile from the eye along the look
 * ({@link #SPEED} blocks a tick, gravity {@link #GRAVITY}, radius {@link #RADIUS}, at most {@link #MAX_TICKS} ticks), so it arcs
 * down over a long throw. The first entity it hits takes 5 × P damage and is knocked {@link #KNOCK} along the throw (plus a
 * little lift). No block changes.
 *
 * <p>Fx: {@code origin} = the eye, {@code dir} = the unit look, {@code target} = where the arc meets a block (or where it ends),
 * {@code duration} = ticks to get there; {@code data} = {speed, gravity, radius}. The client flies the same arc ({@link #path},
 * which is {@link Projectiles#step} between ticks) and stops it at the first entity its own sweep meets.
 */
public final class StoneBullet implements Spell {
	public static final double SPEED = 2.2;
	public static final double GRAVITY = 0.02;
	public static final double RADIUS = 0.35;
	public static final int MAX_TICKS = 30;
	public static final float DAMAGE = 5;
	public static final double KNOCK = 1.0;
	/** A little lift on every hit, so ground friction doesn't eat the push at once (plus the throw's own upward part). */
	public static final double LIFT = 0.2;
	/** Cap on the upward push, so a stone thrown steeply up lifts a foe a little instead of launching it. */
	public static final double MAX_UP = 0.5;

	@Override
	public Identifier id() {
		return SpellIds.STONE_BULLET;
	}

	@Override
	public Element element() {
		return Element.EARTH;
	}

	@Override
	public int slot() {
		return Slots.SMALL_A;
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		Vec3 dir = ctx.look().normalize();
		ProjectileSpec spec = spec(ctx.eye(), dir);
		float damage = DAMAGE * ctx.power();
		if (!Projectiles.launch(ctx, spec, hit -> hit(ctx, damage, hit))) return CastOutcome.fizzle("magic.office.busy");

		// Where the arc meets a block, tick by tick as the projectile clips, so every client stops the stone at the same wall.
		Stop stop = stop(ctx.level(), spec);
		return CastOutcome.cast(ctx.fx().withTarget(stop.at()).withDuration(Math.max(1, stop.ticks()))
				.withData((float) SPEED, (float) GRAVITY, (float) RADIUS));
	}

	public static ProjectileSpec spec(Vec3 origin, Vec3 dir) {
		return new ProjectileSpec(origin, dir.scale(SPEED), GRAVITY, RADIUS, MAX_TICKS, false);
	}

	/**
	 * The arc at any time {@code t} (ticks, fractional for drawing between ticks): {@link Projectiles#step}'s formula
	 * {@code origin + velocity × t − (0, gravity × t(t−1)/2, 0)}, equal to it at whole ticks.
	 */
	public static Vec3 path(ProjectileSpec spec, double t) {
		Vec3 o = spec.origin();
		Vec3 v = spec.velocity();
		return new Vec3(o.x + v.x * t, o.y + v.y * t - spec.gravity() * t * (t - 1) / 2, o.z + v.z * t);
	}

	/** The direction of travel at time {@code t} (unit). */
	public static Vec3 heading(ProjectileSpec spec, double t) {
		Vec3 v = spec.velocity();
		Vec3 d = new Vec3(v.x, v.y - spec.gravity() * (t - 0.5), v.z);
		double l = d.length();
		return l < 1.0E-9 ? new Vec3(0, 0, 1) : d.scale(1 / l);
	}

	/** Where (and after how many ticks) the arc first meets a block collider, or its end of range. */
	public record Stop(Vec3 at, int ticks, boolean wall) {}

	public static Stop stop(ServerLevel level, ProjectileSpec spec) {
		for (int t = 0; t < spec.maxTicks(); t++) {
			Vec3 from = Projectiles.step(spec, t);
			Vec3 to = Projectiles.step(spec, t + 1);
			BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
			if (block.getType() != HitResult.Type.MISS) return new Stop(block.getLocation(), t + 1, true);
		}
		return new Stop(Projectiles.step(spec, spec.maxTicks()), spec.maxTicks(), false);
	}

	private static void hit(CastContext ctx, float damage, ProjectileHit hit) {
		LivingEntity e = hit.entity();
		if (e == null) return;
		// The direction the stone was travelling when it struck.
		Vec3 dir = hit.pos().subtract(ctx.eye());
		if (dir.lengthSqr() < 1.0E-6) dir = ctx.look();
		dir = dir.normalize();
		Hurt.damage(ctx, e, damage);
		Hurt.knock(e, dir, KNOCK, Math.min(MAX_UP, LIFT + KNOCK * Math.max(0, dir.y)));
	}
}
