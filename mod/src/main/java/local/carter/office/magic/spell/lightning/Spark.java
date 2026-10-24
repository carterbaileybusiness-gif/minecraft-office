package local.carter.office.magic.spell.lightning;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

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
 * Spark (Lightning, slot 0, small): a hitscan zap along the look, range 16 with 0.5 of aim forgiveness, stopping at the first solid
 * block. The target takes {@code 3 × P}; the spark then arcs to the nearest other target within 4 of it (centre to centre, in its
 * sight) for 60 % of that. No block changes.
 *
 * <p>Fx: {@code target} = the end of the zap (the struck target's middle, else where the look meets a block or the range ends);
 * {@code entities} = the struck target, then the arc's target if any (empty on a miss); no {@code data}.
 */
public final class Spark implements Spell {
	public static final double RANGE = 16;
	public static final double FORGIVENESS = 0.5;
	public static final float DAMAGE = 3;
	public static final double ARC_RANGE = 4;
	public static final float ARC_SHARE = 0.6F;

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
		Optional<LivingEntity> hit = Targets.ray(ctx, RANGE, FORGIVENESS);
		if (hit.isEmpty()) return CastOutcome.cast(ctx.fx().withTarget(Targets.point(ctx, RANGE)));
		LivingEntity first = hit.get();
		float damage = DAMAGE * ctx.power();
		// Pick the arc before the hit lands, so a target the zap kills still passes it on.
		List<LivingEntity> struck = Arcs.chain(ctx, first, 1, ARC_RANGE);
		Hurt.damage(ctx, first, damage);
		if (struck.size() > 1) Hurt.damage(ctx, struck.get(1), damage * ARC_SHARE);
		Vec3 end = Arcs.centre(first);
		return CastOutcome.cast(ctx.fx().withTarget(end).withEntities(Arcs.ids(struck)));
	}
}
