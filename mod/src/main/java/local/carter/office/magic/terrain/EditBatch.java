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

	/**
	 * Turns the block into a falling block flying with {@code velocity}. It never drops an item, and lands as a block only where
	 * this cast could have set one (claims, spawn protection, immune blocks) with no player or protected entity in the cell or
	 * above; elsewhere it vanishes. Landings are logged with the cast ({@link LaunchedDebris}).
	 */
	boolean launch(BlockPos pos, Vec3 velocity);

	/**
	 * MEDIUM only: places {@code state} over air or a replaceable block for {@code ticks}, then restores it. Placing the same
	 * state again on a live temporary block refreshes its timer. {@code state} must be inert ({@code #office:magic_temp_safe};
	 * see {@link TempBlocks}): water and ice effects use packed or blue ice, or display entities, never a temporary liquid.
	 */
	boolean placeTemporary(BlockPos pos, BlockState state, int ticks);

	int applied();

	int refused();

	@Override
	void close();
}
