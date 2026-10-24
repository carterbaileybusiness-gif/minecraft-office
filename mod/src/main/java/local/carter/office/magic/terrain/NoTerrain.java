package local.carter.office.magic.terrain;

import local.carter.office.magic.spell.CastContext;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Refuses every block change (the kit before a world kit is installed). */
public final class NoTerrain implements TerrainEdits, TempBlocks {
	public static final NoTerrain INSTANCE = new NoTerrain();

	private NoTerrain() {}

	@Override
	public EditBatch begin(CastContext ctx, BlockPos origin, EditTier tier) {
		return new Refusing();
	}

	@Override
	public boolean place(ServerLevel level, BlockPos pos, BlockState placed, int ticks) {
		return false;
	}

	@Override
	public int active() {
		return 0;
	}

	@Override
	public void restoreAll(MinecraftServer server) {}

	private static final class Refusing implements EditBatch {
		private int refused;

		private boolean refuse() {
			refused++;
			return false;
		}

		@Override public boolean set(BlockPos pos, BlockState state) { return refuse(); }

		@Override public boolean remove(BlockPos pos) { return refuse(); }

		@Override public boolean launch(BlockPos pos, Vec3 velocity) { return refuse(); }

		@Override public boolean placeTemporary(BlockPos pos, BlockState state, int ticks) { return refuse(); }

		@Override public int applied() { return 0; }

		@Override public int refused() { return refused; }

		@Override public void close() {}
	}
}
