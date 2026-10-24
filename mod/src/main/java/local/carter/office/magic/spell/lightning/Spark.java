package local.carter.office.magic.spell.lightning;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.core.ChargeMath;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.Hurt;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.Targets;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Spark (Lightning, slot 0, small): a hitscan zap along the look, {@link #range} = 16 × {@code ChargeMath.size} (16 at a tap, 32
 * at full charge) with 0.5 of aim forgiveness, stopping at the first solid block. The target takes {@code 3 × P}; the spark then
 * arcs on {@link #arcs} times (1 at a tap, 2 at full charge), each time to the nearest creature not yet struck within
 * {@link #arcRange} (4 → 8) of the last one (centre to centre, in its sight; a control effect, so never a boss: {@link Arcs}),
 * for 60 % of that. No block changes.
 *
 * <p>Fx: {@code target} = the end of the zap (the struck target's middle, else where the look meets a block or the range ends);
 * {@code entities} = the struck target, then the arcs' targets in order (empty on a miss); no {@code data}.
 */
public final class Spark implements Spell {
	public static final double RANGE = 16;
	public static final double FORGIVENESS = 0.5;
	public static final float DAMAGE = 3;
	public static final double ARC_RANGE = 4;
	public static final float ARC_SHARE = 0.6F;

	/** How far the zap reaches at {@code charge}: 16, 32 at full charge. */
	public static double range(float charge) {
		return RANGE * ChargeMath.size(charge);
	}

	/** How far each arc leaps at {@code charge}: 4, 8 at full charge. */
	public static double arcRange(float charge) {
		return ARC_RANGE * ChargeMath.size(charge);
	}

	/** Arcs after the first target at {@code charge}: 1, 2 from half charge. */
	public static int arcs(float charge) {
		return Math.round(ChargeMath.size(charge));
	}

	@Override
	public Identifier id() {
		return SpellIds.SPARK;
	}

	@Override
	public @Nullable Element element() {
		return Element.LIGHTNING;
	}

	@Override
	public int slot() {
		return Slots.SMALL_A;
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		double range = range(ctx.charge());
		Optional<LivingEntity> hit = Targets.ray(ctx, range, FORGIVENESS);
		if (hit.isEmpty()) return CastOutcome.cast(ctx.fx().withTarget(Targets.point(ctx, range)));
		LivingEntity first = hit.get();
		float damage = DAMAGE * ctx.power();
		// Pick the arc before the hit lands, so a target the zap kills still passes it on.
		List<LivingEntity> struck = Arcs.chain(ctx, first, arcs(ctx.charge()), arcRange(ctx.charge()));
		Hurt.damage(ctx, first, damage);
		for (int i = 1; i < struck.size(); i++) Hurt.damage(ctx, struck.get(i), damage * ARC_SHARE);
		Vec3 end = Arcs.centre(first);
		return CastOutcome.cast(ctx.fx().withTarget(end).withEntities(Arcs.ids(struck)));
	}
}
