package local.carter.office.magic.spell.fire;

import java.util.List;

import org.jspecify.annotations.Nullable;

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
import local.carter.office.magic.spell.RankKit;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.tree.SpellRank;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Fire Bolt (fire, slot 0, small): a fast bolt of flame from the eye along the look, {@link #SPEED} blocks a tick, at most
 * {@link #MAX_TICKS} ticks, no gravity, radius {@link #radius} ({@link #RADIUS} at a tap, twice that at full charge: a bigger
 * bolt; the rank's size knob on top). The first creature it meets takes {@code 4 × P} damage, burns for
 * {@code FIRE_TICKS × (1 + charge)} ticks and is knocked back {@code KNOCK × (1 + charge)}; a block stops it. No block changes.
 *
 * <p>Forks: III {@link #SCATTER} (3 bolts in a fan, {@link #SCATTER_SHARE} each) or {@link #SEARING} (burns twice as long,
 * +50 % on a burning foe); V {@link #LANCE} (pierces creatures, flies 50 % farther) or {@link #FLARE} (bursts on impact:
 * {@link #FLARE_SHARE} damage and a burn within {@link #FLARE_RADIUS}).
 *
 * <p>Fx: the cast's default event (origin = eye, dir = look, charge, rank); the client rebuilds every bolt's flight with
 * {@link #dirs} and {@link #spec}, so no extra data is sent.
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

	public static final String SCATTER = "scatter";
	public static final String SEARING = "searing";
	public static final String LANCE = "lance";
	public static final String FLARE = "flare";
	public static final float SCATTER_SHARE = 0.55F;
	/** Degrees each side bolt leaves the look. */
	public static final float SCATTER_SPREAD = 8;
	public static final float SEARING_BONUS = 1.5F;
	public static final double FLARE_RADIUS = 2.5;
	public static final float FLARE_SHARE = 0.5F;
	public static final int FLARE_BURN = 60;
	/** Lance's flight time. */
	public static final int LANCE_TICKS = 30;

	/** The bolt's size at {@code charge}: {@code RADIUS × ChargeMath.size(charge)}. */
	public static double radius(float charge) {
		return RADIUS * ChargeMath.size(charge);
	}

	/** A tap's flight from {@code origin} along {@code dir}. */
	public static ProjectileSpec spec(Vec3 origin, Vec3 dir) {
		return spec(origin, dir, 0F);
	}

	/** The bolt's flight from {@code origin} along {@code dir} at {@code charge}, rank 0. */
	public static ProjectileSpec spec(Vec3 origin, Vec3 dir, float charge) {
		return spec(origin, dir, charge, null);
	}

	/** The bolt's flight at {@code charge} and {@code rank} (shared by the server and the client fx). */
	public static ProjectileSpec spec(Vec3 origin, Vec3 dir, float charge, @Nullable SpellRank rank) {
		Vec3 d = dir.lengthSqr() < 1.0E-12 ? new Vec3(0, 0, 1) : dir.normalize();
		boolean lance = rank != null && rank.is(LANCE);
		double r = RADIUS * (rank == null ? ChargeMath.size(charge) : rank.size(charge));
		return new ProjectileSpec(origin, d.scale(SPEED), 0, r, lance ? LANCE_TICKS : MAX_TICKS, lance);
	}

	/** The bolts' directions: the look, or (Scatter) the look and one {@link #SCATTER_SPREAD} to each side. */
	public static List<Vec3> dirs(Vec3 look, SpellRank rank) {
		if (!rank.is(SCATTER)) return List.of(look);
		float a = (float) Math.toRadians(SCATTER_SPREAD);
		return List.of(look.yRot(a), look, look.yRot(-a));
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
		boolean first = true;
		for (Vec3 d : dirs(ctx.look(), ctx.rank())) {
			ProjectileSpec spec = spec(ctx.eye(), d, ctx.charge(), ctx.rank());
			Vec3 dir = spec.velocity().normalize();
			// The first bolt decides the cast (a full flight list fizzles for free); a side bolt that finds no room is just not thrown.
			if (!Projectiles.launch(ctx, spec, hit -> hit(ctx, dir, hit)) && first) return CastOutcome.fizzle(CastService.BUSY_KEY);
			first = false;
		}
		return CastOutcome.cast(ctx.fx());
	}

	/** One bolt's damage on {@code e}: {@code 4 × P}, Scatter's share, Searing's bonus on a burning foe. */
	static float damage(CastContext ctx, LivingEntity e) {
		float d = DAMAGE * ctx.power();
		if (ctx.rank().is(SCATTER)) d *= SCATTER_SHARE;
		if (ctx.rank().is(SEARING) && e.isOnFire()) d *= SEARING_BONUS;
		return d;
	}

	private static void hit(CastContext ctx, Vec3 dir, ProjectileHit hit) {
		LivingEntity e = hit.entity();
		boolean flare = ctx.rank().is(FLARE);
		if (e == null) {
			if (flare) RankKit.blast(ctx, hit.pos(), FLARE_RADIUS, DAMAGE * ctx.power() * FLARE_SHARE, null, FLARE_BURN, 0, 0);
			return;
		}
		float damage = damage(ctx, e);
		Hurt.damage(ctx, e, damage);
		if (e.isAlive()) {
			float size = ChargeMath.size(ctx.charge());
			int burn = Math.round(FIRE_TICKS * size * (ctx.rank().is(SEARING) ? 2 : 1));
			e.igniteForTicks(Math.max(e.getRemainingFireTicks(), burn));
			Hurt.knock(e, dir, KNOCK * size, KNOCK_UP);
		}
		if (flare) RankKit.blast(ctx, hit.pos(), FLARE_RADIUS, damage * FLARE_SHARE, e, FLARE_BURN, 0, 0);
	}
}
