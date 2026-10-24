package local.carter.office.magic.spell.wind;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.core.ChargeMath;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.spell.ActiveEffects;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.LingeringEffect;
import local.carter.office.magic.spell.Motion;
import local.carter.office.magic.spell.RankKit;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.tree.Knob;
import local.carter.office.magic.tree.SpellRank;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

import net.minecraft.world.phys.Vec3;

/**
 * Air Step (Wind, slot 2, small): the caster kicks off the air itself, on the ground or mid-jump (a double jump). The new
 * velocity is {@code look_h × 0.4 × (1 + charge)} level plus {@code 0.9 + 0.43 × charge} up, where {@code look_h} is the level
 * look direction at unit length (the facing when looking straight up or down): a tap rises about 5 blocks, a full charge about
 * 10 ({@link #apex}). It replaces the caster's velocity, so a falling caster is caught and thrown up. No fall damage for {@link #NO_FALL_TICKS} ticks ({@link Motion#dash}). No block changes; touches no one else.
 *
 * <p>Ranks raise the jump (the size knob: the up speed grows by its square root, so the height grows by about the knob). Forks:
 * III {@link #AIR_DASH} (cast off the ground, it dashes {@link #AIR_DASH_SPEED} along the look instead of jumping: no status
 * effect, the same no-fall window) or {@link #LONG_JUMP} ({@link #LONG_JUMP_FORWARD}× forward);
 * V {@link #SECOND_WIND} (cooldown −60 %, a knob) or {@link #SHOCKWAVE} (landing within {@link #SHOCKWAVE_WAIT} ticks blasts
 * {@link #SHOCKWAVE_DAMAGE}{@code × P} and throws foes out within {@link #SHOCKWAVE_RADIUS}).
 *
 * <p>Fx: {@code target} = the caster's feet, {@code data} = {@code {up speed}} ({@code dir} is the look, as always).
 */
public final class AirStep implements Spell {
	public static final double UP = 0.9;
	/** Full charge's extra lift: 1.33 up climbs twice a tap's height ({@link #apex}). */
	public static final double UP_PER_CHARGE = 0.43;
	public static final double FORWARD = 0.4;
	/** Long enough for a full charge's rise and fall (about 40 ticks). */
	public static final int NO_FALL_TICKS = 80;
	public static final String AIR_DASH = "air_dash";
	public static final String LONG_JUMP = "long_jump";
	public static final String SECOND_WIND = "second_wind";
	public static final String SHOCKWAVE = "shockwave";
	/** Air Dash: speed along the look, and the little lift that keeps it from dipping at once. */
	public static final double AIR_DASH_SPEED = 1.3;
	public static final double AIR_DASH_LIFT = 0.15;
	public static final double LONG_JUMP_FORWARD = 2.2;
	public static final double SHOCKWAVE_RADIUS = 3;
	public static final float SHOCKWAVE_DAMAGE = 3;
	/** How long Shockwave Landing waits for the caster to come down. */
	public static final int SHOCKWAVE_WAIT = 80;
	/** Vanilla's player gravity and air drag, for {@link #apex}. */
	private static final double GRAVITY = 0.08;
	private static final double DRAG = 0.98;

	@Override
	public Identifier id() {
		return SpellIds.AIR_STEP;
	}

	@Override
	public @Nullable Element element() {
		return Element.WIND;
	}

	@Override
	public int slot() {
		return Slots.SMALL_B;
	}

	/** The jump's velocity for a caster looking along {@code look} (yaw {@code yRot} for a straight-up look) at {@code charge}. */
	public static Vec3 velocity(Vec3 look, float yRot, float charge) {
		Vec3 h = lookH(look, yRot);
		double forward = FORWARD * ChargeMath.size(charge);
		return new Vec3(h.x * forward, UP + UP_PER_CHARGE * Mth.clamp(charge, 0F, 1F), h.z * forward);
	}

	/** The jump's velocity at {@code rank}: the up speed times the square root of the size knob, Long Jump's reach forward. */
	public static Vec3 velocity(Vec3 look, float yRot, float charge, SpellRank rank) {
		Vec3 v = velocity(look, yRot, charge);
		double forward = rank.is(LONG_JUMP) ? LONG_JUMP_FORWARD : 1;
		return new Vec3(v.x * forward, v.y * Math.sqrt(rank.mul(Knob.SIZE)), v.z * forward);
	}

	/** Air Dash's velocity along {@code look}: {@link #AIR_DASH_SPEED}, plus {@link #AIR_DASH_LIFT} up. */
	public static Vec3 dash(Vec3 look) {
		Vec3 d = look.lengthSqr() < 1.0E-8 ? new Vec3(0, 0, 1) : look.normalize();
		return d.scale(AIR_DASH_SPEED).add(0, AIR_DASH_LIFT, 0);
	}

	/** How high a jump of {@code up} blocks a tick climbs in open air (vanilla's step: move, then less gravity, times drag). */

	public static double apex(double up) {
		double y = 0;
		for (double v = up; v > 0; v = (v - GRAVITY) * DRAG) y += v;
		return y;
	}

	/** The level part of {@code look} at unit length; looking straight up or down, the facing of yaw {@code yRot}. */
	public static Vec3 lookH(Vec3 look, float yRot) {
		Vec3 f = new Vec3(look.x, 0, look.z);
		if (f.lengthSqr() > 1.0E-6) return f.normalize();
		float r = yRot * Mth.DEG_TO_RAD;
		return new Vec3(-Mth.sin(r), 0, Mth.cos(r));
	}

	/** Shockwave Landing: waits for the caster to leave the ground and come back down, then blasts round their feet. */
	private static final class Landing implements LingeringEffect {
		private final CastContext ctx;
		private boolean airborne;

		Landing(CastContext ctx) {
			this.ctx = ctx;
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			ServerPlayer p = ctx.caster();
			if (p.isRemoved() || !p.isAlive() || p.level() != level) return false;
			if (!p.onGround()) airborne = true;
			else if (airborne || age > 10) {
				RankKit.blast(ctx, p.position().add(0, 0.3, 0), SHOCKWAVE_RADIUS, SHOCKWAVE_DAMAGE * ctx.power(), null, 0, 1.0, 0.5);
				return false;
			}
			return age < SHOCKWAVE_WAIT;
		}

		@Override
		public String toString() {
			return "AirStep.Landing[" + ctx.caster().getScoreboardName() + "]";
		}
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		ServerPlayer p = ctx.caster();
		Vec3 v = velocity(ctx.look(), p.getYRot(), ctx.charge(), ctx.rank());
		// Air Dash: in the air, a dash along the look instead of a second jump.
		if (ctx.rank().is(AIR_DASH) && !p.onGround()) v = dash(ctx.look());
		Motion.dash(p, v, NO_FALL_TICKS);
		if (ctx.rank().is(SHOCKWAVE)) ActiveEffects.add(ctx, new Landing(ctx));
		Vec3 feet = p.position();
		return CastOutcome.cast(ctx.fx().withTarget(feet).withData((float) v.y));
	}
}
