package local.carter.office.magic.terrain;

import local.carter.office.magic.spell.CastContext;

import net.minecraft.core.BlockPos;

/**
 * Claim-safe, budgeted, logged block changes for spells (medium and ultimate only; small spells never change blocks).
 * A travelling spell opens one batch per segment or hit, each with its own origin. Implemented in Task 5.
 */
public interface TerrainEdits {
	EditBatch begin(CastContext ctx, BlockPos origin, EditTier tier);
}
