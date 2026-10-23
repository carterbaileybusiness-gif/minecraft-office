package local.carter.office.net;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import local.carter.office.core.HologramSpec;
import local.carter.office.core.Privacy;
import local.carter.office.hologram.HologramBlockEntity;
import local.carter.office.hologram.HologramEmitterBlock;
import local.carter.office.hologram.OfficeBlocks;
import local.carter.office.hologram.StarterKit;
import local.carter.office.net.OfficePayloads.MoveHologram;
import local.carter.office.net.OfficePayloads.RemoveHologram;
import local.carter.office.net.OfficePayloads.ResizeHologram;
import local.carter.office.net.OfficePayloads.SetWorking;
import local.carter.office.net.OfficePayloads.UpdateHologram;
import local.carter.office.stream.StreamRouter;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/**
 * Server handlers for {@link OfficePayloads}. Every hologram edit is rejected silently unless the sender owns the
 * hologram ({@code player.getUUID().equals(spec.owner())}), is in the same dimension within {@link #MAX_REACH} blocks,
 * and the values are sane.
 */
public final class HologramActions {
	/** Max distance (blocks) from the emitter for an edit; the client picks holograms up to 32 blocks away. */
	public static final double MAX_REACH = 64.0;
	/** Max |offset| (blocks) of a hologram from its emitter on any axis. */
	public static final double MAX_OFFSET = 16.0;
	public static final int MAX_DISPLAY_SLOT = 4;

	private static final Set<UUID> WORKING = ConcurrentHashMap.newKeySet();

	private HologramActions() {}

	public static void registerReceivers() {
		ServerPlayNetworking.registerGlobalReceiver(ResizeHologram.TYPE, (p, ctx) -> apply(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(UpdateHologram.TYPE, (p, ctx) -> apply(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(MoveHologram.TYPE, (p, ctx) -> apply(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(SetWorking.TYPE, (p, ctx) -> apply(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(RemoveHologram.TYPE, (p, ctx) -> apply(ctx.player(), p));
	}

	/** Applies one payload as {@code player}; returns whether anything changed (false = silently rejected). */
	public static boolean apply(final ServerPlayer player, final CustomPacketPayload payload) {
		return switch (payload) {
			case ResizeHologram r -> edit(player, r.pos(), s -> s.withWidth(r.width()));
			case UpdateHologram u -> edit(player, u.pos(), s -> update(s, u));
			case MoveHologram m -> edit(player, m.pos(), s -> move(s, m));
			case SetWorking w -> setWorking(player.getUUID(), w.working());
			case RemoveHologram r -> remove(player, r.pos());
			default -> false;
		};
	}

	public static boolean isWorking(final UUID player) {
		return WORKING.contains(player);
	}

	public static Set<UUID> working() {
		return Collections.unmodifiableSet(WORKING);
	}

	/** Called on disconnect. */
	public static void clearWorking(final UUID player) {
		WORKING.remove(player);
	}

	private static boolean setWorking(final UUID player, final boolean working) {
		return working ? WORKING.add(player) : WORKING.remove(player);
	}

	private static HologramSpec update(final HologramSpec s, final UpdateHologram u) {
		if (u.shape() == null || u.mode() == null || u.privacy() == null) {
			return null;
		}
		if (u.windowKey() != null && u.windowKey().length() > OfficePayloads.MAX_WINDOW_KEY) {
			return null;
		}
		int slot = Math.max(1, Math.min(MAX_DISPLAY_SLOT, u.displaySlot()));
		Privacy privacy = u.privacy().allowed().size() > OfficePayloads.MAX_ALLOWED ? null : u.privacy();
		if (privacy == null) {
			return null;
		}
		// Keep the bottom edge where it was when the shape (and so the height) changes.
		double newHeight = s.width() / u.shape().aspect();
		double offY = s.offY() - s.height() / 2 + newHeight / 2;
		return new HologramSpec(s.owner(), u.shape(), u.mode(), privacy, slot, u.windowKey(), s.width(), s.offX(), offY,
				s.offZ(), s.yaw());
	}

	private static HologramSpec move(final HologramSpec s, final MoveHologram m) {
		if (!okOffset(m.offX()) || !okOffset(m.offY()) || !okOffset(m.offZ()) || !Float.isFinite(m.yaw())) {
			return null;
		}
		float yaw = ((m.yaw() % 360f) + 360f) % 360f;
		return new HologramSpec(s.owner(), s.shape(), s.mode(), s.privacy(), s.displaySlot(), s.windowKey(), s.width(),
				m.offX(), m.offY(), m.offZ(), yaw);
	}

	private static boolean okOffset(final double v) {
		return Double.isFinite(v) && Math.abs(v) <= MAX_OFFSET;
	}

	/** The owned hologram at {@code pos} that {@code player} may edit, or null. */
	private static HologramBlockEntity owned(final ServerPlayer player, final BlockPos pos) {
		if (pos == null) {
			return null;
		}
		ServerLevel level = player.level();
		if (!level.isLoaded(pos) || player.position().distanceToSqr(Vec3.atCenterOf(pos)) > MAX_REACH * MAX_REACH) {
			return null;
		}
		if (!(level.getBlockEntity(pos) instanceof HologramBlockEntity be) || be.spec() == null
				|| !player.getUUID().equals(be.spec().owner())) {
			return null;
		}
		return be;
	}

	private static boolean edit(final ServerPlayer player, final BlockPos pos, final UnaryOperator<HologramSpec> change) {
		HologramBlockEntity be = owned(player, pos);
		if (be == null) {
			return false;
		}
		HologramSpec next = change.apply(be.spec());
		if (next == null) {
			return false;
		}
		// A wall-mounted hologram never leaves its wall plane (resizes keep the centre; moves slide along the wall).
		be.setSpec(HologramEmitterBlock.constrain(be.getBlockState(), next));
		StreamRouter.rerouteNow(); // privacy/position may change who views it: reroute before returning
		return true;
	}

	private static boolean remove(final ServerPlayer player, final BlockPos pos) {
		if (owned(player, pos) == null) {
			return false;
		}
		if (!player.level().destroyBlock(pos, false, player)) {
			return false;
		}
		StarterKit.give(player, new ItemStack(OfficeBlocks.HOLOGRAM_EMITTER_ITEM));
		StreamRouter.rerouteNow();
		return true;
	}
}
