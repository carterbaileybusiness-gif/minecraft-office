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
import local.carter.office.magic.tree.Knob;
import local.carter.office.magic.tree.SpellRank;

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
 * <p>Ranks lengthen the zap (the size knob). Forks: III {@link #FORKED} (+1 arc, a knob) or {@link #STATIC} (Glowing and
 * Slowness I, riders); V {@link #OVERCHARGE} (arcs hit for the full damage and leap {@link #OVERCHARGE_LEAP}× as far) or
 * {@link #STUN} (Slowness IV, a rider).
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
	public static final String FORKED = "forked";
	public static final String STATIC = "static";
	public static final String OVERCHARGE = "overcharge";
	public static final String STUN = "stun";
	public static final double OVERCHARGE_LEAP = 1.5;

	/** The zap's reach at {@code charge} and {@code rank} (the size knob). */
	public static double range(float charge, SpellRank rank) {
		return range(charge) * rank.mul(Knob.SIZE);
	}

	/** Arcs at {@code charge} and {@code rank} (Forked's extra). */
	public static int arcs(float charge, SpellRank rank) {
		return arcs(charge) + Math.round(rank.add(Knob.COUNT));
	}

	/** How far an arc leaps at {@code charge} and {@code rank} (Overcharge's ×1.5). */
	public static double arcRange(float charge, SpellRank rank) {
		return arcRange(charge) * (rank.is(OVERCHARGE) ? OVERCHARGE_LEAP : 1);
	}

	/** What an arc deals, as a share of the zap: {@link #ARC_SHARE}, all of it with Overcharge. */
	public static float arcShare(SpellRank rank) {
		return rank.is(OVERCHARGE) ? 1F : ARC_SHARE;
	}

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
		double range = range(ctx.charge(), ctx.rank());
		Optional<LivingEntity> hit = Targets.ray(ctx, range, FORGIVENESS);
		if (hit.isEmpty()) return CastOutcome.cast(ctx.fx().withTarget(Targets.point(ctx, range)));
		LivingEntity first = hit.get();
		float damage = DAMAGE * ctx.power();
		// Pick the arc before the hit lands, so a target the zap kills still passes it on.
		List<LivingEntity> struck = Arcs.chain(ctx, first, arcs(ctx.charge(), ctx.rank()), arcRange(ctx.charge(), ctx.rank()));
		Hurt.damage(ctx, first, damage);
		for (int i = 1; i < struck.size(); i++) Hurt.damage(ctx, struck.get(i), damage * arcShare(ctx.rank()));
		Vec3 end = Arcs.centre(first);
		return CastOutcome.cast(ctx.fx().withTarget(end).withEntities(Arcs.ids(struck)));
	}
}
