package local.carter.office.magic.spell.lightning;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.cast.CastService;
import local.carter.office.magic.core.ChargeMath;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.Motion;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Blink (Lightning, slot 2, small): the caster flashes forward along the look, up to {@link #range} = {@code 10 × (1 + charge)} blocks (10 at a
 * tap, 20 at full charge), in an instant. The way is walked first ({@link BlinkPath}: it slides along ground it is aimed into, steps over a one-block bump, and
 * stops at the last place the caster's body fits before anything solid, lava or fire), then {@link Motion#teleportSafe} puts
 * them there, so a blink never passes through a wall or ends inside a block. Velocity is zeroed and there is no fall damage for
 * {@link #NO_FALL_TICKS} ticks. No block changes, nothing hurt. A blink that
 * would travel less than {@link #MIN_TRAVEL} fizzles before anything happens (nothing spent).
 *
 * <p>Fx: {@code origin} = the eye where the blink started, {@code target} = where the caster's feet ended, {@code data} =
 * {@code {eyeHeight, travelled}} (the feet started at {@code origin − (0, eyeHeight, 0)}).
 */
public final class Blink implements Spell {
	public static final double BASE_RANGE = 10;
	public static final int NO_FALL_TICKS = 20;
	/** A blink that would travel less than this fizzles at no cost, with {@link #BLOCKED_KEY} ("The spell failed"). */
	public static final double MIN_TRAVEL = 0.5;
	public static final String BLOCKED_KEY = CastService.SPELL_FAILED_KEY;

	@Override
	public Identifier id() {
		return SpellIds.BLINK;
	}

	@Override
	public @Nullable Element element() {
		return Element.LIGHTNING;
	}

	@Override
	public int slot() {
		return Slots.SMALL_B;
	}

	/** How far a blink goes at {@code charge}: {@code 10 × ChargeMath.size(charge)}, 10 at a tap and 20 at full charge. */
	public static double range(float charge) {
		return BASE_RANGE * ChargeMath.size(charge);
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		ServerPlayer p = ctx.caster();
		// Taken before the move: the fx's origin is the eye where the blink started.
		FxEvent fx = ctx.fx();
		Vec3 from = p.position();
		BlinkPath.Walk w = BlinkPath.walk(from, ctx.look(), range(ctx.charge()), at -> fits(p, at));
		// Hemmed in (a wall right in front, a ceiling overhead): no blink, and nothing spent.
		if (w.travelled() < MIN_TRAVEL) return CastOutcome.fizzle(BLOCKED_KEY);
		Vec3 end = Motion.teleportSafe(p, w.end());
		Motion.dash(p, Vec3.ZERO, NO_FALL_TICKS);
		float eye = p.getEyeHeight();
		return CastOutcome.cast(fx.withTarget(end).withData(eye, (float) w.travelled()));
	}

	/**
	 * Whether {@code p}'s body (in its current pose) fits with its feet at {@code at}: inside the world border, clear of every
	 * block collider, and touching no lava or fire.
	 */
	static boolean fits(ServerPlayer p, Vec3 at) {
		ServerLevel level = p.level();
		if (!level.getWorldBorder().isWithinBounds(at)) return false;
		AABB box = p.getDimensions(p.getPose()).makeBoundingBox(at);
		if (!level.noCollision(p, box)) return false;
		BlockPos lo = BlockPos.containing(box.minX, box.minY, box.minZ);
		BlockPos hi = BlockPos.containing(box.maxX, box.maxY, box.maxZ);
		for (BlockPos c : BlockPos.betweenClosed(lo, hi)) {
			if (!level.isLoaded(c)) return false;
			BlockState s = level.getBlockState(c);
			if (s.is(BlockTags.FIRE) || s.getFluidState().is(FluidTags.LAVA)) return false;
		}
		return true;
	}
}
