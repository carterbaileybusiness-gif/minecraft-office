package local.carter.office.magic.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Temporary blocks that restore themselves (persisted, so a stop or crash never leaves them behind). Only inert blocks
 * ({@code #office:magic_temp_safe}: stone family, packed/blue ice, glass…) may be placed: never liquids, falling blocks,
 * melting ice, block entities, two-block-tall blocks or light sources. Water and ice effects use packed or blue ice, or display entities,
 * never a temporary liquid. Spells go through {@link EditBatch#placeTemporary}, which also applies the claim rules.
 * A temporary block never drops anything: broken by any means, its original block comes back at once ({@link TempBlockGuard}).
 */
public interface TempBlocks {
	boolean place(ServerLevel level, BlockPos pos, BlockState placed, int ticks);

	/** True when {@code pos} holds a live temporary {@code state} placed here (placing it again refreshes its timer). */
	default boolean holds(ServerLevel level, BlockPos pos, BlockState state) {
		return false;
	}

	int active();

	void restoreAll(MinecraftServer server);
}
