package local.carter.office.magic.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** Temporary blocks that restore themselves (persisted, so a stop or crash never leaves them behind). Implemented in Task 5. */
public interface TempBlocks {
	boolean place(ServerLevel level, BlockPos pos, BlockState placed, int ticks);

	int active();

	void restoreAll(MinecraftServer server);
}
