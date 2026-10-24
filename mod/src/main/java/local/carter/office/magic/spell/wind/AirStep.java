package local.carter.office.magic.spell.wind;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.Motion;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Air Step (Wind, slot 2, small): the caster kicks off the air itself, on the ground or mid-jump (a double jump). The new
 * velocity is {@code look_h × 0.4} level plus {@code 0.9 + 0.2 × charge} up, where {@code look_h} is the level look direction
 * at unit length (the facing when looking straight up or down). It replaces the caster's velocity, so a falling caster is
 * caught and thrown up. No fall damage for {@link #NO_FALL_TICKS} ticks ({@link Motion#dash}). No block changes; touches no one else.
 *
 * <p>Fx: {@code target} = the caster's feet, {@code data} = {@code {up speed}} ({@code dir} is the look, as always).
 */
public final class AirStep implements Spell {
	public static final double UP = 0.9;
	public static final double UP_PER_CHARGE = 0.2;
	public static final double FORWARD = 0.4;
	public static final int NO_FALL_TICKS = 60;

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
		return new Vec3(h.x * FORWARD, UP + UP_PER_CHARGE * Mth.clamp(charge, 0F, 1F), h.z * FORWARD);
	}

	/** The level part of {@code look} at unit length; looking straight up or down, the facing of yaw {@code yRot}. */
	public static Vec3 lookH(Vec3 look, float yRot) {
		Vec3 f = new Vec3(look.x, 0, look.z);
		if (f.lengthSqr() > 1.0E-6) return f.normalize();
		float r = yRot * Mth.DEG_TO_RAD;
		return new Vec3(-Mth.sin(r), 0, Mth.cos(r));
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		ServerPlayer p = ctx.caster();
		Vec3 v = velocity(ctx.look(), p.getYRot(), ctx.charge());
		Motion.dash(p, v, NO_FALL_TICKS);
		Vec3 feet = p.position();
		return CastOutcome.cast(ctx.fx().withTarget(feet).withData((float) v.y));
	}
}
