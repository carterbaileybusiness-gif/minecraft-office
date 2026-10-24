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
import local.carter.office.magic.terrain.EditBatch;
import local.carter.office.magic.terrain.EditTier;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Chain Lightning (Lightning, slot 1, medium): the first target along the look (range 20, 1.5 of aim forgiveness, stopping at
 * solid blocks), then {@code 4 + round(2 × charge)} hops, each to the nearest target not yet struck within 6 of the last one and in
 * its sight ({@link ChainHops}, {@link Arcs}). The target at hop {@code k} (the first is hop 0) takes {@code 6 × P × 0.8^k}.
 * Minor permanent change: sand (or red sand) right under each struck target fuses to glass, one MEDIUM batch per target with the
 * sand as its origin. With nothing to strike it still casts: the bolt grounds where the look meets a block (no block change).
 *
 * <p>Fx: {@code target} = the first target's middle (on a miss, the grounding point); {@code entities} = the struck targets in
 * chain order; {@code data[0]} = how many were struck (0 on a miss).
 */
public final class ChainLightning implements Spell {
	public static final double RANGE = 20;
	public static final double FORGIVENESS = 1.5;
	/** How far below a target's feet to look for the block it stands on (blocks). */
	public static final int GROUND_DEPTH = 2;

	@Override
	public Identifier id() {
		return SpellIds.CHAIN_LIGHTNING;
	}

	@Override
	public @Nullable Element element() {
		return Element.LIGHTNING;
	}

	@Override
	public int slot() {
		return Slots.MEDIUM_A;
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		Optional<LivingEntity> hit = Targets.ray(ctx, RANGE, FORGIVENESS);
		if (hit.isEmpty()) return CastOutcome.cast(ctx.fx().withTarget(Targets.point(ctx, RANGE)).withData(0));
		List<LivingEntity> struck = Arcs.chain(ctx, hit.get(), ChainHops.hops(ctx.charge()), ChainHops.HOP_RANGE);
		for (int hop = 0; hop < struck.size(); hop++) {
			LivingEntity e = struck.get(hop);
			// The ground is read before the hit: a target the bolt kills still leaves its glass.
			BlockPos sand = sandUnder(ctx.level(), e);
			Hurt.damage(ctx, e, ChainHops.damage(hop, ctx.power()));
			if (sand != null) {
				try (EditBatch batch = ctx.kit().terrain().begin(ctx, sand, EditTier.MEDIUM)) {
					batch.set(sand, Blocks.GLASS.defaultBlockState());
				}
			}
		}
		return CastOutcome.cast(ctx.fx().withTarget(Arcs.centre(struck.getFirst())).withEntities(Arcs.ids(struck)).withData(struck.size()));
	}

	/**
	 * The sand {@code e} stands on: the first non-air block under its feet, at most {@link #GROUND_DEPTH} down, if that is sand or
	 * red sand; else null.
	 */
	static @Nullable BlockPos sandUnder(ServerLevel level, LivingEntity e) {
		BlockPos.MutableBlockPos m = BlockPos.containing(e.getX(), e.getY() - 0.2, e.getZ()).mutable();
		for (int i = 0; i <= GROUND_DEPTH; i++, m.move(0, -1, 0)) {
			if (m.getY() < level.getMinY()) return null;
			BlockState s = level.getBlockState(m);
			if (s.isAir()) continue;
			return s.is(Blocks.SAND) || s.is(Blocks.RED_SAND) ? m.immutable() : null;
		}
		return null;
	}
}
