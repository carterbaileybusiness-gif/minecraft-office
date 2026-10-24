package local.carter.office.magic.spell.wind;

import java.util.List;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.core.ChargeMath;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.Hurt;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.TargetFilter;
import local.carter.office.magic.spell.Targets;
import local.carter.office.magic.tree.Knob;
import local.carter.office.magic.tree.SpellRank;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Gust (Wind, slot 0, small): a blast of wind in a cone ahead, range {@code 6 × (1 + charge)} (6 at a tap, 12 at full charge),
 * half-angle 30°. Every creature in it (hostile mobs and animals) takes {@code 1 × (1 + charge)} and is thrown away from the
 * caster ({@code 1.4 × P} level, {@code 0.4} up); items and XP orbs are blown along too. Wind doesn't go through walls:
 * anything whose centre the caster can't see (a block collider on the line from the eye) is left alone. A thrown target's level
 * speed is capped at {@link #MAX_SPEED}. No block changes.
 *
 * <p>Ranks lengthen the cone (the size knob). Forks: III {@link #GALE} (knockback {@link #GALE_KNOCK}×) or {@link #UPDRAFT}
 * (thrown {@link #UPDRAFT_UP} up); V {@link #CYCLONE} (the cone opens to {@link #CYCLONE_ANGLE}°) or {@link #RAZOR}
 * ({@link #RAZOR_DAMAGE}× damage).
 *
 * <p>Fx: {@code target} = the cone's tip (eye + look × range), {@code entities} = what it moved, {@code data} = {@code {range,
 * half-angle}}.
 */
public final class Gust implements Spell {
	public static final double RANGE = 6;
	public static final double HALF_ANGLE = 30;
	public static final float DAMAGE = 1;
	public static final double KNOCK = 1.4;
	public static final double UP = 0.4;
	/** Most level speed (blocks per tick) a gust leaves anything with. */
	public static final double MAX_SPEED = 3;
	public static final String GALE = "gale";
	public static final String UPDRAFT = "updraft";
	public static final String CYCLONE = "cyclone";
	public static final String RAZOR = "razor";
	public static final float GALE_KNOCK = 1.6F;
	public static final double UPDRAFT_UP = 0.9;
	public static final double CYCLONE_ANGLE = 50;
	public static final float RAZOR_DAMAGE = 4;

	/** The cone's half-angle at {@code rank}. */
	public static double halfAngle(SpellRank rank) {
		return rank.is(CYCLONE) ? CYCLONE_ANGLE : HALF_ANGLE;
	}

	/** One creature's damage at {@code charge} and {@code rank}: {@code 1 × (1 + charge)} times the rank's damage (and Razor Wind's). */
	public static float damage(float charge, SpellRank rank) {
		return DAMAGE * ChargeMath.size(charge) * rank.mul(Knob.DAMAGE) * (rank.is(RAZOR) ? RAZOR_DAMAGE : 1);
	}

	@Override
	public Identifier id() {
		return SpellIds.GUST;
	}

	@Override
	public @Nullable Element element() {
		return Element.WIND;
	}

	@Override
	public int slot() {
		return Slots.SMALL_A;
	}

	/** The cone's reach at {@code charge}: {@code 6 × ChargeMath.size(charge)}. */
	public static double range(float charge) {
		return RANGE * ChargeMath.size(charge);
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		double range = range(ctx.charge()) * ctx.rank().mul(Knob.SIZE);
		Vec3 eye = ctx.eye();
		Vec3 look = ctx.look();
		double strength = KNOCK * ctx.power() * ctx.rank().mul(Knob.KNOCK);
		double up = ctx.rank().is(UPDRAFT) ? UPDRAFT_UP : UP;
		double angle = halfAngle(ctx.rank());
		List<Entity> caught = Targets.cone(ctx, range, angle, true);
		int[] moved = new int[caught.size()];
		int n = 0;
		for (Entity e : caught) {
			if (!inSight(ctx, eye, e.getBoundingBox().getCenter())) continue;
			if (e instanceof LivingEntity l && TargetFilter.affects(ctx.caster(), l)) {
				Hurt.damage(ctx, l, damage(ctx.charge(), ctx.rank()));
			}
			// Away from the caster (a blast, not a conveyor belt); something right on top of them goes along the look.
			Vec3 away = e.getBoundingBox().getCenter().subtract(eye);
			Vec3 flat = new Vec3(away.x, 0, away.z);
			if (flat.lengthSqr() < 0.04) flat = look;
			if (Hurt.knock(e, flat, strength, up)) {
				cap(e);
				moved[n++] = e.getId();
			}
		}
		int[] ids = java.util.Arrays.copyOf(moved, n);
		return CastOutcome.cast(ctx.fx().withTarget(eye.add(look.scale(range))).withEntities(ids).withData((float) range, (float) angle));
	}

	/** True when no block collider stands between the caster's eye and {@code centre}. */
	private static boolean inSight(CastContext ctx, Vec3 eye, Vec3 centre) {
		BlockHitResult hit = ctx.level().clip(new ClipContext(eye, centre, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.caster()));
		return hit.getType() == HitResult.Type.MISS;
	}

	/** Scales {@code e}'s level velocity down to {@link #MAX_SPEED} (the vertical part is kept). */
	private static void cap(Entity e) {
		Vec3 v = e.getDeltaMovement();
		double h = v.horizontalDistance();
		if (h > MAX_SPEED) e.setDeltaMovement(v.x * MAX_SPEED / h, v.y, v.z * MAX_SPEED / h);
	}
}
