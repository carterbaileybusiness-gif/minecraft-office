package local.carter.office.magic.spell.wind;

import java.util.List;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.core.ChargeMath;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.spell.ActiveEffects;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.Hurt;
import local.carter.office.magic.spell.LingeringEffect;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.TargetFilter;
import local.carter.office.magic.spell.Targets;
import local.carter.office.magic.spell.water.BubbleShield;
import local.carter.office.magic.spell.water.BubbleShieldGeometry;
import local.carter.office.magic.tree.Knob;
import local.carter.office.magic.tree.SpellRank;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;

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
 * <p>Ranks lengthen the cone (the size knob). Forks: III {@link #VACUUM} (the gust sucks instead: creatures (never a boss),
 * items and XP orbs come toward the caster, stopping about {@link #VACUUM_STOP} away) or {@link #UPDRAFT} (thrown
 * {@link #UPDRAFT_UP} up); V {@link #CYCLONE} (the cone opens to {@link #CYCLONE_ANGLE}°) or {@link #WIND_WALL} (the gust
 * lingers {@link #WALL_TICKS} ticks as a wall of wind {@link #WALL_AHEAD} ahead, {@link #WALL_RADIUS} wide: it swats out of the
 * air every projectile flying into it that is not the caster's, the way Bubble Shield stops them; its own caster slot, skipped
 * when the caster's effects are full).
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
	public static final String VACUUM = "vacuum";
	public static final String UPDRAFT = "updraft";
	public static final String CYCLONE = "cyclone";
	public static final String WIND_WALL = "wind_wall";
	/** Vacuum: how far from the caster a pulled thing comes to rest, and the most speed the pull gives. */
	public static final double VACUUM_STOP = 1.5;
	public static final double VACUUM_SPEED = 1.2;
	public static final double VACUUM_PER_BLOCK = 0.4;
	public static final double UPDRAFT_UP = 0.9;
	public static final double CYCLONE_ANGLE = 50;
	/** Wind Wall: how long it stands, how far ahead of the eye its middle is, and its radius. */
	public static final int WALL_TICKS = 60;
	public static final double WALL_AHEAD = 3;
	public static final double WALL_RADIUS = 2.5;

	/** The cone's half-angle at {@code rank}. */
	public static double halfAngle(SpellRank rank) {
		return rank.is(CYCLONE) ? CYCLONE_ANGLE : HALF_ANGLE;
	}

	/** One creature's damage at {@code charge} and {@code rank}: {@code 1 × (1 + charge)} times the rank's damage. */
	public static float damage(float charge, SpellRank rank) {
		return DAMAGE * ChargeMath.size(charge) * rank.mul(Knob.DAMAGE);
	}

	/** Wind Wall's middle for a gust from {@code eye} along {@code look}. */
	public static Vec3 wallCentre(Vec3 eye, Vec3 look) {
		return eye.add(look.normalize().scale(WALL_AHEAD));
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
		boolean vacuum = ctx.rank().is(VACUUM);
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
			if (vacuum) {
				// Vacuum: a pull is a control effect (never a boss); just enough speed to come to rest near the caster.
				if (!TargetFilter.controllable(ctx.caster(), e)) continue;
				double dist = Math.sqrt(flat.lengthSqr());
				// Level only, so ground friction brings it to rest about VACUUM_STOP away (it slides about 2.2–2.4× its speed).
				double speed = Math.min(VACUUM_SPEED, Math.max(0, dist - VACUUM_STOP) * VACUUM_PER_BLOCK);
				if (speed > 0 && Hurt.knock(e, flat.reverse(), speed, 0)) moved[n++] = e.getId();
				continue;
			}
			if (Hurt.knock(e, flat, strength, up)) {
				cap(e);
				moved[n++] = e.getId();
			}
		}
		int[] ids = java.util.Arrays.copyOf(moved, n);
		// Wind Wall: the gust stays standing ahead, swatting projectiles out of the air.
		if (ctx.rank().is(WIND_WALL)) ActiveEffects.add(ctx, new Wall(ctx, wallCentre(eye, look)));
		return CastOutcome.cast(ctx.fx().withTarget(eye.add(look.scale(range))).withEntities(ids).withData((float) range, (float) angle));
	}

	/** True when no block collider stands between the caster's eye and {@code centre}. */
	private static boolean inSight(CastContext ctx, Vec3 eye, Vec3 centre) {
		BlockHitResult hit = ctx.level().clip(new ClipContext(eye, centre, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.caster()));
		return hit.getType() == HitResult.Type.MISS;
	}

	/** Wind Wall: every tick, projectiles in it or about to cross into it (not the caster's) are removed, as Bubble Shield does. */
	static final class Wall implements LingeringEffect {
		private final CastContext ctx;
		private final Vec3 centre;

		Wall(CastContext ctx, Vec3 centre) {
			this.ctx = ctx;
			this.centre = centre;
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			if (!level.isLoaded(BlockPos.containing(centre))) return false;
			AABB near = new AABB(centre, centre).inflate(WALL_RADIUS + BubbleShieldGeometry.SEARCH);
			for (Projectile pr : level.getEntitiesOfClass(Projectile.class, near, pr -> !pr.isRemoved() && pr.getOwner() != ctx.caster())) {
				if (BubbleShield.stops(pr) && BubbleShieldGeometry.threatens(centre, pr.position(), pr.getDeltaMovement(), WALL_RADIUS)) {
					pr.discard();
				}
			}
			return age < WALL_TICKS;
		}

		@Override
		public String toString() {
			return "Gust.Wall[" + ctx.caster().getScoreboardName() + "]";
		}
	}

	/** Scales {@code e}'s level velocity down to {@link #MAX_SPEED} (the vertical part is kept). */
	private static void cap(Entity e) {
		Vec3 v = e.getDeltaMovement();
		double h = v.horizontalDistance();
		if (h > MAX_SPEED) e.setDeltaMovement(v.x * MAX_SPEED / h, v.y, v.z * MAX_SPEED / h);
	}
}
