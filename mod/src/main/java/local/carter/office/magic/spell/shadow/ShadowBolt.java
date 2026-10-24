package local.carter.office.magic.spell.shadow;

import java.util.List;

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
import local.carter.office.magic.spell.TargetFilter;
import local.carter.office.magic.tree.Knob;
import local.carter.office.magic.tree.SpellRank;

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
 * look ({@link #SPEED} blocks a tick, radius {@link #radius}: {@link #RADIUS} at a tap, twice that at full charge; at most
 * {@link #MAX_TICKS} ticks, no gravity). The first entity it hits takes 4 × P damage and Weakness I for
 * {@code WEAKNESS_TICKS × (1 + charge)} ticks. It stops at the first block. No block changes.
 *
 * <p>Ranks grow the bolt (the size knob). Forks: III {@link #WITHER} (Wither II, a rider) or {@link #TWIN} (two bolts
 * ±{@link #TWIN_SPREAD}°, {@link #TWIN_SHARE} each); V {@link #SIPHON} (heals the caster {@link #SIPHON_SHARE} of its damage, a
 * rider) or {@link #VOID_LANCE} (pierces creatures).
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
	public static final String WITHER = "wither";
	public static final String TWIN = "twin";
	public static final String SIPHON = "siphon";
	public static final String VOID_LANCE = "void_lance";
	public static final float TWIN_SPREAD = 5;
	public static final float TWIN_SHARE = 0.65F;
	public static final float SIPHON_SHARE = 0.4F;

	/** The bolts' directions: the look, or (Twin Bolts) one {@link #TWIN_SPREAD}° to each side of it. */
	public static List<Vec3> dirs(Vec3 look, SpellRank rank) {
		if (!rank.is(TWIN)) return List.of(look);
		float a = (float) Math.toRadians(TWIN_SPREAD);
		return List.of(look.yRot(a), look.yRot(-a));
	}

	/** The bolt at {@code rank}: the size knob on its radius; Void Lance pierces. */
	public static ProjectileSpec spec(Vec3 origin, Vec3 dir, float charge, SpellRank rank) {
		return new ProjectileSpec(origin, dir.scale(SPEED), 0, RADIUS * rank.size(charge), MAX_TICKS, rank.is(VOID_LANCE));
	}

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
		ProjectileSpec spec = spec(origin, dir, ctx.charge(), ctx.rank());
		float damage = DAMAGE * ctx.power() * (ctx.rank().is(TWIN) ? TWIN_SHARE : 1);
		boolean first = true;
		for (Vec3 d : dirs(dir, ctx.rank())) {
			ProjectileSpec s = spec(origin, d.normalize(), ctx.charge(), ctx.rank());
			// The first bolt decides the cast; a second that finds no room is just not thrown.
			if (!Projectiles.launch(ctx, s, hit -> hit(ctx, damage, hit)) && first) return CastOutcome.fizzle("magic.office.busy");
			first = false;
		}

		// Where the path meets a block, so every client stops the bolt at the same wall.
		Vec3 end = Projectiles.step(spec, MAX_TICKS);
		BlockHitResult block = ctx.level().clip(new ClipContext(origin, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
				CollisionContext.empty()));
		Vec3 stop = block.getType() == HitResult.Type.MISS ? end : block.getLocation();
		int ticks = Math.min(MAX_TICKS, (int) Math.ceil(stop.distanceTo(origin) / SPEED));
		return CastOutcome.cast(ctx.fx().withTarget(stop).withDuration(Math.max(1, ticks)).withData((float) SPEED, (float) spec.radius()));
	}

	/** The bolt's size at {@code charge}: {@code RADIUS × ChargeMath.size(charge)}. */
	public static double radius(float charge) {
		return RADIUS * ChargeMath.size(charge);
	}

	public static ProjectileSpec spec(Vec3 origin, Vec3 dir) {
		return spec(origin, dir, 0F);
	}

	public static ProjectileSpec spec(Vec3 origin, Vec3 dir, float charge) {
		return new ProjectileSpec(origin, dir.scale(SPEED), 0, radius(charge), MAX_TICKS, false);
	}

	private static void hit(CastContext ctx, float damage, ProjectileHit hit) {
		LivingEntity e = hit.entity();
		if (e == null || !TargetFilter.affects(ctx.caster(), e)) return;
		Hurt.damage(ctx, e, damage);
		int weak = Math.round(WEAKNESS_TICKS * ChargeMath.size(ctx.charge()));
		if (e.isAlive()) e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, weak, WEAKNESS_AMPLIFIER), ctx.caster());
	}
}
