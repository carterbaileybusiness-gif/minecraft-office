package local.carter.office.hologram;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;
import local.carter.office.core.PlacementPlan;
import local.carter.office.core.Mount;
import local.carter.office.core.Privacy;
import local.carter.office.core.Shape;
import local.carter.office.core.Vec3d;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The hologram emitter. Placed on the floor it is a small projector base the hologram floats above; on a wall it is a
 * thin flat mount and the hologram hangs flush on the wall like a painting; under a ceiling it is a flat mount the
 * hologram hangs below. {@link #FACE} (floor / wall / ceiling, like vanilla buttons) and {@link #FACING} (the direction
 * the hologram faces at placement; for walls, out of the wall) are block-state properties; emitters saved before they
 * existed load as FLOOR. Owner-only removal (non-owners get zero mining
 * progress and their break is cancelled in {@link #allowBreak}), explosion-proof via resistance, max
 * {@link #MAX_HOLOGRAMS} per owner.
 */
public class HologramEmitterBlock extends BaseEntityBlock {
	public static final int MAX_HOLOGRAMS = 8;
	public static final String LIMIT_MESSAGE = "You already have 8 holograms";
	public static final double DEFAULT_WIDTH = 2.0;

	public static final EnumProperty<AttachFace> FACE = BlockStateProperties.ATTACH_FACE;
	public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

	/** Wall mount thickness in pixels: thinner than the 0.03-block gap, so the hologram is always in front of it. */
	public static final double WALL_MOUNT_PX = 0.4;

	private static final VoxelShape FLOOR_SHAPE = Block.column(12.0, 0.0, 4.0);
	private static final double T = WALL_MOUNT_PX;
	/** Wall mounts by FACING (the wall is on the opposite side): an 8 x 4 px plate, 0.4 px thick. */
	private static final VoxelShape WALL_NORTH = Block.box(4.0, 6.0, 16.0 - T, 12.0, 10.0, 16.0);
	private static final VoxelShape WALL_SOUTH = Block.box(4.0, 6.0, 0.0, 12.0, 10.0, T);
	private static final VoxelShape WALL_EAST = Block.box(0.0, 6.0, 4.0, T, 10.0, 12.0);
	private static final VoxelShape WALL_WEST = Block.box(16.0 - T, 6.0, 4.0, 16.0, 10.0, 12.0);
	/** Ceiling mount: a 10 x 6 px plate, 1 px thick, long side along the hologram's width. */
	private static final VoxelShape CEILING_NS = Block.box(3.0, 15.0, 5.0, 13.0, 16.0, 11.0);
	private static final VoxelShape CEILING_EW = Block.box(5.0, 15.0, 3.0, 11.0, 16.0, 13.0);

	public HologramEmitterBlock(final BlockBehaviour.Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACE, AttachFace.FLOOR).setValue(FACING, Direction.NORTH));
	}

	@Override
	protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACE, FACING);
	}

	/** How the emitter in {@code state} is attached (FLOOR for anything that isn't an emitter). */
	public static Mount mount(final BlockState state) {
		if (!state.hasProperty(FACE)) {
			return Mount.FLOOR;
		}
		return switch (state.getValue(FACE)) {
			case FLOOR -> Mount.FLOOR;
			case WALL -> Mount.WALL;
			case CEILING -> Mount.CEILING;
		};
	}

	/** Yaw of the direction the emitter faces (for walls: out of the wall). */
	public static float facingYaw(final BlockState state) {
		return state.hasProperty(FACING) ? state.getValue(FACING).toYRot() : 0.0F;
	}

	/** Server rule for every edit: a wall hologram stays on its wall plane, facing out. */
	public static HologramSpec constrain(final BlockState state, final HologramSpec spec) {
		return mount(state).constrain(spec, facingYaw(state));
	}

	@Override
	protected BlockState rotate(final BlockState state, final Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	protected BlockState mirror(final BlockState state, final Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(FACING)));
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(final BlockPos pos, final BlockState state) {
		return new HologramBlockEntity(pos, state);
	}

	@Override
	protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
		Direction facing = state.getValue(FACING);
		return switch (state.getValue(FACE)) {
			case FLOOR -> FLOOR_SHAPE;
			case CEILING -> facing.getAxis() == Direction.Axis.Z ? CEILING_NS : CEILING_EW;
			case WALL -> switch (facing) {
				case SOUTH -> WALL_SOUTH;
				case EAST -> WALL_EAST;
				case WEST -> WALL_WEST;
				default -> WALL_NORTH;
			};
		};
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
		return placementState(context.getClickedFace(), context.getHorizontalDirection(), context.replacingClickedOnBlock());
	}

	/**
	 * Clicking the top of a block puts a projector on the floor, the side of a block mounts it on that wall (facing out),
	 * the underside hangs it from the ceiling. Floor and ceiling holograms face the placer.
	 */
	public BlockState placementState(final Direction clickedFace, final Direction placerFacing, final boolean replacing) {
		Direction towardsPlacer = placerFacing.getOpposite();
		if (replacing || clickedFace == Direction.UP) {
			return defaultBlockState().setValue(FACE, AttachFace.FLOOR).setValue(FACING, towardsPlacer);
		}
		if (clickedFace == Direction.DOWN) {
			return defaultBlockState().setValue(FACE, AttachFace.CEILING).setValue(FACING, towardsPlacer);
		}
		return defaultBlockState().setValue(FACE, AttachFace.WALL).setValue(FACING, clickedFace);
	}

	@Override
	public void setPlacedBy(final Level level, final BlockPos pos, final BlockState state, final @Nullable LivingEntity by,
			final ItemStack itemStack) {
		super.setPlacedBy(level, pos, state, by, itemStack);
		if (by == null || !(level instanceof ServerLevel serverLevel) || !(level.getBlockEntity(pos) instanceof HologramBlockEntity be)) {
			return;
		}
		UUID owner = by.getUUID();
		// FACING is where the hologram looks: back at the placer (floor/ceiling) or out of the wall.
		float yaw = facingYaw(state);
		boolean online = serverLevel.getServer().getPlayerList().getPlayer(owner) != null;
		HologramIndex index = HologramIndex.get(serverLevel.getServer());
		// Every hologram is a new virtual display on the next free slot (Windows clients switch theirs to Mirror).
		PlacementPlan.Choice choice = index.placementFor(owner);
		HologramSpec spec = defaultSpec(owner, choice.displaySlot(), mount(state), yaw).withMode(choice.mode())
				.withPrivacy(HologramSpec.defaultPrivacy(choice.mode())); // a Mirror starts Private
		be.initPlaced(spec, by.getName().getString(), online);
		index.put(be.hologramId(), owner, serverLevel.dimension(), pos, spec.mode(), spec.displaySlot());
	}

	/**
	 * Default floor hologram: 16:9, Extend, Public, width 2, centred above the emitter, bottom edge 0.6 above its centre.
	 * Placement then picks the display slot with {@link HologramIndex#placementFor}.
	 */
	public static HologramSpec defaultSpec(final UUID owner, final int displaySlot, final float yaw) {
		return defaultSpec(owner, displaySlot, Mount.FLOOR, yaw);
	}

	/**
	 * Default hologram for a mount: 16:9, Extend, Public, width 2. Floor: centred above the emitter. Wall: centred on the
	 * clicked face, content plane {@link Mount#WALL_GAP} off the wall, facing out. Ceiling: hanging just below the mount.
	 */
	public static HologramSpec defaultSpec(final UUID owner, final int displaySlot, final Mount mount, final float yaw) {
		double height = DEFAULT_WIDTH / Shape.WIDE_16_9.aspect();
		Vec3d off = mount.defaultOffsets(yaw, height);
		return new HologramSpec(owner, Shape.WIDE_16_9, Mode.EXTEND, Privacy.PUBLIC, displaySlot, null, DEFAULT_WIDTH,
				off.x(), off.y(), off.z(), yaw);
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
