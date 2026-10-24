package local.carter.office.magic.spell.fire;

import java.util.HashSet;
import java.util.Set;

import local.carter.office.magic.cast.CastService;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.spell.ActiveEffects;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.Hurt;
import local.carter.office.magic.spell.LingeringEffect;
import local.carter.office.magic.spell.Motion;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.TargetFilter;
import local.carter.office.magic.spell.Targets;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Flame Dash (fire, slot 2, small): the caster bursts forward along their flat facing at {@code 1.6 + 0.5 × charge} blocks a tick
 * plus {@link #UP} up ({@link Motion#dash}: their own client does the moving), with no fall damage for {@link #NO_FALL_TICKS}
 * ticks. For the next {@link #BURN_TICKS} ticks every creature within {@link #REACH} of the caster's body along the way (swept
 * tick to tick, so speed never skips one; never through a wall) takes {@code 3 × P} and burns for {@link #FIRE_TICKS} ticks,
 * each once. No block changes.
 *
 * <p>Fx: {@code target} = where the dash starts (the caster's feet), {@code dir} = the dash's flat direction, {@code duration} =
 * {@link #BURN_TICKS}, {@code data} = {@code {speed}}.
 */
public final class FlameDash implements Spell {
	public static final double BASE_SPEED = 1.6;
	public static final double CHARGE_SPEED = 0.5;
	public static final double UP = 0.2;
	public static final int NO_FALL_TICKS = 40;
	public static final int BURN_TICKS = 8;
	public static final double REACH = 1.5;
	public static final float DAMAGE = 3;
	public static final int FIRE_TICKS = 40;
	/** Spacing of the points the path is checked at. */
	private static final double SAMPLE = 0.25;

	@Override
	public Identifier id() {
		return SpellIds.FLAME_DASH;
	}

	@Override
	public Element element() {
		return Element.FIRE;
	}

	@Override
	public int slot() {
		return Slots.SMALL_B;
	}

	public static double speed(float charge) {
		return BASE_SPEED + CHARGE_SPEED * Mth.clamp(charge, 0F, 1F);
	}

	/** The dash's flat direction: the look without its vertical part (the facing when looking straight up or down). */
	public static Vec3 direction(Vec3 look, float yRot) {
		double l = Math.sqrt(look.x * look.x + look.z * look.z);
		return l < 1.0E-3 ? FireWallGeometry.forward(yRot) : new Vec3(look.x / l, 0, look.z / l);
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		ServerPlayer p = ctx.caster();
		Vec3 dir = direction(ctx.look(), p.getYRot());
		double speed = speed(ctx.charge());
		Vec3 start = p.position();
		Trail trail = new Trail(ctx, centre(p));
		if (!ActiveEffects.add(ctx.level(), trail)) return CastOutcome.fizzle(CastService.BUSY_KEY);
		Motion.dash(p, dir.scale(speed).add(0, UP, 0), NO_FALL_TICKS);
		return CastOutcome.cast(ctx.fx().withTarget(start).withDuration(BURN_TICKS).withData((float) speed));
	}

	private static Vec3 centre(ServerPlayer p) {
		return p.getBoundingBox().getCenter();
	}

	/** The burning wake: each tick, everything within reach of the caster's path since the last tick catches fire, once. */
	private static final class Trail implements LingeringEffect {
		private final CastContext ctx;
		private final Set<Integer> burnt = new HashSet<>();
		private Vec3 last;

		Trail(CastContext ctx, Vec3 from) {
			this.ctx = ctx;
			this.last = from;
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			ServerPlayer p = ctx.caster();
			if (p.isRemoved() || p.level() != level) return false;
			Vec3 now = centre(p);
			// A jump longer than FireWallGeometry.MAX_SWEEP in one tick is a teleport (pearl, chorus, Recall, a command), not the dash:
			// nothing between is burnt. Standing still (dashed into a wall) still burns what is in reach of where they stand.
			if (now.distanceToSqr(last) <= FireWallGeometry.MAX_SWEEP * FireWallGeometry.MAX_SWEEP) burn(level, last, now);
			last = now;
			return age < BURN_TICKS;
		}

		private void burn(ServerLevel level, Vec3 from, Vec3 to) {
			AABB near = new AABB(from, to).inflate(REACH + 1);
			double len = from.distanceTo(to);
			int steps = Math.max(1, (int) Math.ceil(len / SAMPLE));
			double r2 = REACH * REACH;
			for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, near, e -> TargetFilter.affects(ctx.caster(), e))) {
				if (burnt.contains(e.getId()) || burnt.size() >= Targets.MAX_ENTITIES) continue;
				AABB box = e.getBoundingBox();
				Vec3 closest = null;
				for (int i = 0; i <= steps; i++) {
					Vec3 at = from.lerp(to, i / (double) steps);
					if (box.distanceToSqr(at) <= r2) {
						closest = at;
						break;
					}
				}
				if (closest == null || !clear(level, closest, box.getCenter())) continue;
				burnt.add(e.getId());
				Hurt.damage(ctx, e, DAMAGE * ctx.power());
				if (e.isAlive()) e.igniteForTicks(FIRE_TICKS);
			}
		}

		/** Nothing solid between the caster's body and the creature. */
		private boolean clear(ServerLevel level, Vec3 from, Vec3 to) {
			return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.caster())).getType()
					== HitResult.Type.MISS;
		}

		@Override
		public String toString() {
			return "FlameDash.Trail[" + ctx.caster().getScoreboardName() + "]";
		}
	}
}
