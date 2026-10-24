package local.carter.office.magic.spell.fire;

import java.util.HashSet;
import java.util.Set;

import local.carter.office.magic.cast.CastService;
import local.carter.office.magic.core.ChargeMath;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.spell.ActiveEffects;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.Hurt;
import local.carter.office.magic.spell.LingeringEffect;
import local.carter.office.magic.spell.Motion;
import local.carter.office.magic.spell.RankKit;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.TargetFilter;
import local.carter.office.magic.spell.Targets;
import local.carter.office.magic.tree.Knob;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Flame Dash (fire, slot 2, small): the caster bursts forward along their flat facing at {@link #speed} = {@code 1.6 × (1 +
 * charge)} blocks a tick (a full charge dashes twice as far) plus {@link #UP} up ({@link Motion#dash}: their own client does the
 * moving), with no fall damage for {@link #NO_FALL_TICKS} ticks. For the next {@link #BURN_TICKS} ticks every creature within
 * {@link #reach} ({@code 1.5 + 0.75 × charge}) of the caster's body along the way (swept tick to tick, so speed never skips one;
 * never through a wall) takes {@code 3 × P} and burns for {@code FIRE_TICKS × (1 + charge)} ticks, each once. No block changes.
 *
 * <p>Ranks widen the burning reach (the size knob). Forks: III {@link #OVERDRIVE} (dashes {@link #OVERDRIVE_SPEED}× as fast, so as
 * far) or {@link #PHOENIX} (Fire Resistance and Resistance I for {@link #PHOENIX_TICKS}); V {@link #COMET} (the dash ends in a blast
 * of {@code 5 × P} within {@link #COMET_RADIUS}, throwing foes back) or {@link #WAKE} (burnt foes slowed and burning three times as
 * long).
 *
 * <p>Fx: {@code target} = where the dash starts (the caster's feet), {@code dir} = the dash's flat direction, {@code duration} =
 * {@link #BURN_TICKS}, {@code data} = {@code {speed}}.
 */
public final class FlameDash implements Spell {
	public static final double BASE_SPEED = 1.6;
	public static final double UP = 0.2;
	public static final int NO_FALL_TICKS = 40;
	public static final int BURN_TICKS = 8;
	public static final double REACH = 1.5;
	/** What a full charge adds to {@link #REACH}. */
	public static final double CHARGE_REACH = 0.75;
	public static final float DAMAGE = 3;
	public static final int FIRE_TICKS = 40;
	public static final String OVERDRIVE = "overdrive";
	public static final String PHOENIX = "phoenix";
	public static final String COMET = "comet";
	public static final String WAKE = "wake";
	public static final float OVERDRIVE_SPEED = 1.4F;
	public static final int PHOENIX_TICKS = 80;
	public static final double COMET_RADIUS = 3;
	public static final float COMET_DAMAGE = 5;
	public static final double COMET_KNOCK = 1.0;
	public static final double COMET_UP = 0.4;
	public static final int WAKE_SLOW_TICKS = 60;
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

	/** The dash's speed at {@code charge}: {@code 1.6 × ChargeMath.size(charge)}, 1.6 at a tap and 3.2 at full charge. */
	public static double speed(float charge) {
		return BASE_SPEED * ChargeMath.size(charge);
	}

	/** How near the path a creature catches fire at {@code charge}: 1.5, 2.25 at full charge. */
	public static double reach(float charge) {
		return REACH + CHARGE_REACH * Mth.clamp(charge, 0F, 1F);
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
		double speed = speed(ctx.charge()) * (ctx.rank().is(OVERDRIVE) ? OVERDRIVE_SPEED : 1);
		Vec3 start = p.position();
		Trail trail = new Trail(ctx, centre(p));
		if (!ActiveEffects.add(ctx, trail)) return CastOutcome.fizzle(CastService.BUSY_KEY);
		Motion.dash(p, dir.scale(speed).add(0, UP, 0), NO_FALL_TICKS);
		if (ctx.rank().is(PHOENIX)) {
			p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, PHOENIX_TICKS, 0), p);
			p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, PHOENIX_TICKS, 0), p);
		}
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
			// Comet Finish: the dash goes off where it ends.
			if (age >= BURN_TICKS && ctx.rank().is(COMET)) {
				RankKit.blast(ctx, now, COMET_RADIUS, COMET_DAMAGE * ctx.power(), null, FIRE_TICKS, COMET_KNOCK, COMET_UP);
			}
			return age < BURN_TICKS;
		}

		private void burn(ServerLevel level, Vec3 from, Vec3 to) {
			double reach = reach(ctx.charge()) * ctx.rank().mul(Knob.SIZE);
			boolean wake = ctx.rank().is(WAKE);
			AABB near = new AABB(from, to).inflate(reach + 1);
			double len = from.distanceTo(to);
			int steps = Math.max(1, (int) Math.ceil(len / SAMPLE));
			double r2 = reach * reach;
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
				if (!e.isAlive()) continue;
				e.igniteForTicks(Math.round(FIRE_TICKS * ChargeMath.size(ctx.charge()) * (wake ? 3 : 1)));
				if (wake) e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, WAKE_SLOW_TICKS, 1), ctx.caster());
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
