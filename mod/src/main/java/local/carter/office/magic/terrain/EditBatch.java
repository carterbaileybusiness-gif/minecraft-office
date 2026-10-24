package local.carter.office.magic.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Block changes around one origin. Each call returns false when refused (protected, immune, out of radius, over budget);
 * accepted changes are queued and applied by the global applier. Use with try-with-resources.
 */
public interface EditBatch extends AutoCloseable {
	boolean set(BlockPos pos, BlockState state);

	boolean remove(BlockPos pos);

	/** Turns the block into a falling block flying with {@code velocity} (no item drop). */
	boolean launch(BlockPos pos, Vec3 velocity);

	/** MEDIUM only: places {@code state} over air or a replaceable block for {@code ticks}, then restores it. */
	boolean placeTemporary(BlockPos pos, BlockState state, int ticks);

	int applied();

	int refused();

	@Override
	void close();
}
