package local.carter.office.hologram;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;
import local.carter.office.core.Privacy;
import local.carter.office.core.Shape;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The hologram emitter: a small dark base the hologram floats above. Owner-only removal (non-owners get zero mining
 * progress and their break is cancelled in {@link #allowBreak}), explosion-proof via resistance, max
 * {@link #MAX_HOLOGRAMS} per owner.
 */
public class HologramEmitterBlock extends BaseEntityBlock {
	public static final int MAX_HOLOGRAMS = 8;
	public static final String LIMIT_MESSAGE = "You already have 8 holograms";
	public static final double DEFAULT_WIDTH = 2.0;

	private static final VoxelShape SHAPE = Block.column(12.0, 0.0, 4.0);

	public HologramEmitterBlock(final BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(final BlockPos pos, final BlockState state) {
		return new HologramBlockEntity(pos, state);
	}

	@Override
	protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
		return SHAPE;
	}

	/** Refuses the 9th hologram (server side; the client's prediction is rolled back by the server). */
	@Override
	public @Nullable BlockState getStateForPlacement(final BlockPlaceContext context) {
		Player player = context.getPlayer();
		if (player != null && context.getLevel() instanceof ServerLevel level
				&& HologramIndex.get(level.getServer()).countOwnedBy(player.getUUID()) >= MAX_HOLOGRAMS) {
			if (player instanceof ServerPlayer sp) {
				tell(sp, Component.literal(LIMIT_MESSAGE));
			}
			return null;
		}
		return this.defaultBlockState();
	}

	@Override
	public void setPlacedBy(final Level level, final BlockPos pos, final BlockState state, final @Nullable LivingEntity by,
			final ItemStack itemStack) {
		super.setPlacedBy(level, pos, state, by, itemStack);
		if (by == null || !(level instanceof ServerLevel serverLevel) || !(level.getBlockEntity(pos) instanceof HologramBlockEntity be)) {
			return;
		}
		UUID owner = by.getUUID();
		// Face the placer: the hologram's front looks back along the placer's horizontal view direction.
		float yaw = by.getDirection().getOpposite().toYRot();
		boolean online = serverLevel.getServer().getPlayerList().getPlayer(owner) != null;
		HologramIndex index = HologramIndex.get(serverLevel.getServer());
		be.initPlaced(defaultSpec(owner, index.firstFreeSlot(owner), yaw), online);
		index.put(be.hologramId(), owner, serverLevel.dimension(), pos);
	}

	/** Default hologram: 16:9, Extend, Public, width 2, centred above the emitter, bottom edge 0.6 above its centre. */
	public static HologramSpec defaultSpec(final UUID owner, final int displaySlot, final float yaw) {
		double height = DEFAULT_WIDTH / Shape.WIDE_16_9.aspect();
		return new HologramSpec(owner, Shape.WIDE_16_9, Mode.EXTEND, Privacy.PUBLIC, displaySlot, null, DEFAULT_WIDTH,
				0.0, 0.5 + height / 2 + 0.1, 0.0, yaw);
	}

	@Override
	protected float getDestroyProgress(final BlockState state, final Player player, final BlockGetter level, final BlockPos pos) {
		if (level.getBlockEntity(pos) instanceof HologramBlockEntity be && !be.mayRemove(player.getUUID())) {
			return 0.0F;
		}
		return super.getDestroyProgress(state, player, level, pos);
	}

	/**
	 * {@code PlayerBlockBreakEvents.BEFORE} hook: only the owner may break an emitter, in any game mode (ops too).
	 * A refused break is re-sent to clients so a creative client that predicted the removal gets the block back.
	 */
	public static boolean allowBreak(final Level level, final Player player, final BlockPos pos, final BlockState state,
			final @Nullable BlockEntity blockEntity) {
		if (blockEntity instanceof HologramBlockEntity be && !be.mayRemove(player.getUUID())) {
			level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
			return false;
		}
		return true;
	}

	/** Chat to a player; skips players without a network connection (e.g. GameTest mocks). */
	public static void tell(final ServerPlayer player, final Component message) {
		if (player.connection != null) {
			player.sendSystemMessage(message);
		}
	}
}
