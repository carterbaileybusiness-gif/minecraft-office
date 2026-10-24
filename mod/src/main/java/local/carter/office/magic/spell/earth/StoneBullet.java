package local.carter.office.magic.spell.earth;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.core.ChargeMath;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.Hurt;
import local.carter.office.magic.spell.ProjectileHit;
import local.carter.office.magic.spell.ProjectileSpec;
import local.carter.office.magic.spell.Projectiles;
import local.carter.office.magic.spell.RankKit;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.Targets;
import local.carter.office.magic.tree.Knob;

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
 * down over a long throw; its size is {@link #radius} ({@link #RADIUS} at a tap, twice that at full charge). The first entity it
 * hits takes 5 × P damage and is knocked {@code KNOCK × (1 + charge)} along the throw (plus a little lift). No block changes.
 *
 * <p>Ranks grow the stone (the size knob). Forks: III {@link #BOULDER} (the stone ×1.5, knockback ×1.5, knobs) or
 * {@link #SHRAPNEL} (it shatters: {@link #SHRAPNEL_SHARE} within {@link #SHRAPNEL_RADIUS}); V {@link #RICOCHET} (it bounces on to
 * the nearest creature within {@link #RICOCHET_RANGE} in sight, {@link #RICOCHET_SHARE}) or {@link #CONCUSSION} (Blindness and
 * Slowness III, riders).
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
	public static final String BOULDER = "boulder";
	public static final String SHRAPNEL = "shrapnel";
	public static final String RICOCHET = "ricochet";
	public static final String CONCUSSION = "concussion";
	public static final double SHRAPNEL_RADIUS = 2.5;
	public static final float SHRAPNEL_SHARE = 0.4F;
	public static final double RICOCHET_RANGE = 6;
	public static final float RICOCHET_SHARE = 0.6F;

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
		ProjectileSpec spec = new ProjectileSpec(ctx.eye(), dir.scale(SPEED), GRAVITY, RADIUS * ctx.rank().size(ctx.charge()), MAX_TICKS, false);
		float damage = DAMAGE * ctx.power();
		double knock = KNOCK * ChargeMath.size(ctx.charge()) * ctx.rank().mul(Knob.KNOCK);
		if (!Projectiles.launch(ctx, spec, hit -> hit(ctx, spec, damage, knock, hit))) return CastOutcome.fizzle("magic.office.busy");

		// Where the arc meets a block, tick by tick as the projectile clips, so every client stops the stone at the same wall.
		Stop stop = stop(ctx.level(), spec);
		return CastOutcome.cast(ctx.fx().withTarget(stop.at()).withDuration(Math.max(1, stop.ticks()))
				.withData((float) SPEED, (float) GRAVITY, (float) spec.radius()));
	}

	/** The stone's size at {@code charge}: {@code RADIUS × ChargeMath.size(charge)}. */
	public static double radius(float charge) {
		return RADIUS * ChargeMath.size(charge);
	}

	public static ProjectileSpec spec(Vec3 origin, Vec3 dir) {
		return spec(origin, dir, 0F);
	}

	public static ProjectileSpec spec(Vec3 origin, Vec3 dir, float charge) {
		return new ProjectileSpec(origin, dir.scale(SPEED), GRAVITY, radius(charge), MAX_TICKS, false);
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

	/**
	 * The time (ticks, fractional) at which the arc passes {@code pos}: from the horizontal travel, or from the vertical travel
	 * when the stone was thrown straight up or down. Clamped to the spec's range.
	 */
	public static double timeAt(ProjectileSpec spec, Vec3 pos) {
		Vec3 v = spec.velocity();
		Vec3 d = pos.subtract(spec.origin());
		double h2 = v.x * v.x + v.z * v.z;
		double t;
		if (h2 > 1.0E-6) t = (d.x * v.x + d.z * v.z) / h2;
		else t = Math.abs(v.y) > 1.0E-6 ? d.y / v.y : 0;
		return Math.max(0, Math.min(spec.maxTicks(), t));
	}

	private static void hit(CastContext ctx, ProjectileSpec spec, float damage, double knock, ProjectileHit hit) {
		LivingEntity e = hit.entity();
		if (ctx.rank().is(SHRAPNEL)) RankKit.blast(ctx, hit.pos(), SHRAPNEL_RADIUS, damage * SHRAPNEL_SHARE, e, 0, 0, 0);
		if (e == null) return;
		// The direction the stone was travelling when it struck.
		Vec3 dir = heading(spec, timeAt(spec, hit.pos()));
		Hurt.damage(ctx, e, damage);
		Hurt.knock(e, dir, knock, Math.min(MAX_UP, LIFT + knock * Math.max(0, dir.y)));
		if (ctx.rank().is(RICOCHET)) {
			LivingEntity next = ricochet(ctx, e);
			if (next != null) {
				Hurt.damage(ctx, next, damage * RICOCHET_SHARE);
				Hurt.knock(next, next.position().subtract(e.position()), knock * RICOCHET_SHARE, LIFT);
			}
		}
	}

	/** Ricochet: the nearest other creature within {@link #RICOCHET_RANGE} of {@code from}, in its sight (shared rule with the fx). */
	static @Nullable LivingEntity ricochet(CastContext ctx, LivingEntity from) {
		Vec3 c = from.getBoundingBox().getCenter();
		for (LivingEntity e : Targets.radius(ctx, c, RICOCHET_RANGE)) {
			if (e != from && RankKit.reaches(ctx.level(), c, e.getBoundingBox())) return e;
		}
		return null;
	}
}
