package local.carter.office.magic.spell.water;

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
import local.carter.office.magic.tree.Knob;
import local.carter.office.magic.tree.SpellRank;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Water Bullet (Water, slot 0, small): a hard jet of water that knocks foes back. An entity-free projectile from the eye along
 * the look ({@link #SPEED} blocks a tick, radius {@link #radius}: {@link #RADIUS} at a tap, twice that at full charge; at most
 * {@link #MAX_TICKS} ticks, no gravity). The first entity it hits takes 3 × P damage, is knocked {@code KNOCK × (1 + charge)}
 * along the travel and is extinguished. No block changes.
 *
 * <p>Ranks grow the jet (the size knob). Forks: III {@link #RIPTIDE} (knockback {@link #RIPTIDE_KNOCK}×) or {@link #FROST}
 * (Slowness II, a rider); V {@link #LANCE} (pierces creatures, flies 50 % farther) or {@link #SPLASH} (bursts: {@link #SPLASH_SHARE}
 * damage and a push within {@link #SPLASH_RADIUS}).
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
	public static final String RIPTIDE = "riptide";
	public static final String FROST = "frost";
	public static final String LANCE = "lance";
	public static final String SPLASH = "splash";
	public static final float RIPTIDE_KNOCK = 1.8F;
	public static final int LANCE_TICKS = 30;
	public static final double SPLASH_RADIUS = 2.5;
	public static final float SPLASH_SHARE = 0.5F;
	public static final double SPLASH_PUSH = 0.8;

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
		ProjectileSpec spec = spec(origin, dir, ctx.charge(), ctx.rank());
		float damage = DAMAGE * ctx.power();
		double knock = KNOCK * ChargeMath.size(ctx.charge()) * ctx.rank().mul(Knob.KNOCK);
		if (!Projectiles.launch(ctx, spec, hit -> hit(ctx, dir, damage, knock, hit))) return CastOutcome.fizzle("magic.office.busy");

		// Where the path meets a block, so every client stops the jet at the same wall.
		Vec3 end = Projectiles.step(spec, spec.maxTicks());
		BlockHitResult block = ctx.level().clip(new ClipContext(origin, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
				CollisionContext.empty()));
		Vec3 stop = block.getType() == HitResult.Type.MISS ? end : block.getLocation();
		int ticks = Math.min(spec.maxTicks(), (int) Math.ceil(stop.distanceTo(origin) / SPEED));
		return CastOutcome.cast(ctx.fx().withTarget(stop).withDuration(Math.max(1, ticks)).withData((float) SPEED, (float) spec.radius()));
	}

	/** The jet's size at {@code charge}: {@code RADIUS × ChargeMath.size(charge)}. */
	public static double radius(float charge) {
		return RADIUS * ChargeMath.size(charge);
	}

	public static ProjectileSpec spec(Vec3 origin, Vec3 dir) {
		return spec(origin, dir, 0F);
	}

	public static ProjectileSpec spec(Vec3 origin, Vec3 dir, float charge) {
		return new ProjectileSpec(origin, dir.scale(SPEED), 0, radius(charge), MAX_TICKS, false);
	}

	/** The jet at {@code rank}: the size knob on its radius; Lance pierces and flies {@link #LANCE_TICKS}. */
	public static ProjectileSpec spec(Vec3 origin, Vec3 dir, float charge, SpellRank rank) {
		boolean lance = rank.is(LANCE);
		return new ProjectileSpec(origin, dir.scale(SPEED), 0, RADIUS * rank.size(charge), lance ? LANCE_TICKS : MAX_TICKS, lance);
	}

	private static void hit(CastContext ctx, Vec3 dir, float damage, double knock, ProjectileHit hit) {
		LivingEntity e = hit.entity();
		if (e != null) {
			Hurt.damage(ctx, e, damage);
			Hurt.knock(e, dir, knock, Math.min(MAX_UP, LIFT + knock * Math.max(0, dir.y)));
			e.extinguishFire();
		}
		if (ctx.rank().is(SPLASH)) RankKit.blast(ctx, hit.pos(), SPLASH_RADIUS, damage * SPLASH_SHARE, e, 0, SPLASH_PUSH, 0.2);
	}
}
