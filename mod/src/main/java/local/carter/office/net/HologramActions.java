package local.carter.office.net;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;
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
import local.carter.office.net.OfficePayloads.WorkingPlayers;
import local.carter.office.stream.StreamRouter;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
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

	/** Toggles closer together than this (per player) are held back; only the latest state is applied once allowed. */
	public static final long WORKING_MIN_INTERVAL_MS = 250L;

	private static final Set<UUID> WORKING = ConcurrentHashMap.newKeySet();
	private static final Map<UUID, Long> WORKING_LAST_APPLIED = new ConcurrentHashMap<>();
	private static final Map<UUID, Boolean> WORKING_PENDING = new ConcurrentHashMap<>();
	private static volatile LongSupplier clock = () -> System.nanoTime() / 1_000_000L;

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
			case SetWorking w -> setWorking(player, w.working());
			case RemoveHologram r -> remove(player, r.pos());
			default -> false;
		};
	}

	/** Test seam (milliseconds, monotonic); {@code null} restores the real clock. */
	public static void setClock(final LongSupplier c) {
		clock = c == null ? () -> System.nanoTime() / 1_000_000L : c;
	}

	public static boolean isWorking(final UUID player) {
		return WORKING.contains(player);
	}

	public static Set<UUID> working() {
		return Collections.unmodifiableSet(WORKING);
	}

	/**
	 * Where working-set changes go: by default a {@link WorkingPlayers} to every player that has the mod. GameTests
	 * swap it to record the broadcasts (mock players have no connection).
	 */
	public interface WorkingBroadcast {
		void send(MinecraftServer server, List<UUID> working);
	}

	private static volatile WorkingBroadcast broadcast = HologramActions::broadcastToAll;

	/** Test seam; {@code null} restores the real broadcast. */
	public static void setWorkingBroadcast(final WorkingBroadcast b) {
		broadcast = b == null ? HologramActions::broadcastToAll : b;
	}

	/** The working set as one payload (sorted, so every client gets the same list). */
	public static WorkingPlayers workingPayload() {
		List<UUID> list = new ArrayList<>(WORKING);
		list.sort(null);
		return new WorkingPlayers(list);
	}

	/** Called on join: the newcomer learns who is working right now. */
	public static void sendWorkingTo(final ServerPlayer player) {
		if (ServerPlayNetworking.canSend(player, WorkingPlayers.TYPE)) {
			ServerPlayNetworking.send(player, workingPayload());
		}
	}

	/** Called on disconnect: no longer working. */
	public static void clearWorking(final ServerPlayer player) {
		WORKING_PENDING.remove(player.getUUID());
		WORKING_LAST_APPLIED.remove(player.getUUID());
		if (WORKING.remove(player.getUUID())) {
			changed(player.level().getServer());
		}
	}

	/** Called when the server starts: nobody is working. */
	public static void resetWorking(final MinecraftServer server) {
		WORKING.clear();
		WORKING_PENDING.clear();
		WORKING_LAST_APPLIED.clear();
	}

	/**
	 * {@code SetWorking}: the player zoomed into (true) or out of (false) one of their screens. Toggles closer than
	 * {@link #WORKING_MIN_INTERVAL_MS} to the last applied one are held back (returns false); the latest held state is
	 * applied by {@link #flushWorking} once allowed, so the end state stays right.
	 */
	private static boolean setWorking(final ServerPlayer player, final boolean working) {
		UUID id = player.getUUID();
		long now = clock.getAsLong();
		Long last = WORKING_LAST_APPLIED.get(id);
		if (last != null && now - last < WORKING_MIN_INTERVAL_MS) {
			WORKING_PENDING.put(id, working);
			return false;
		}
		WORKING_PENDING.remove(id);
		WORKING_LAST_APPLIED.put(id, now);
		return applyWorking(player.level().getServer(), id, working);
	}

	/** Applies held-back toggles whose interval has passed; called every server tick (cheap when nothing is held). */
	public static void flushWorking(final MinecraftServer server) {
		if (WORKING_PENDING.isEmpty()) {
			return;
		}
		long now = clock.getAsLong();
		for (Map.Entry<UUID, Boolean> e : WORKING_PENDING.entrySet()) {
			UUID id = e.getKey();
			Long last = WORKING_LAST_APPLIED.get(id);
			if (last == null || now - last >= WORKING_MIN_INTERVAL_MS) {
				if (WORKING_PENDING.remove(id, e.getValue())) {
					WORKING_LAST_APPLIED.put(id, now);
					applyWorking(server, id, e.getValue());
				}
			}
		}
	}

	private static boolean applyWorking(final MinecraftServer server, final UUID id, final boolean working) {
		boolean changed = working ? WORKING.add(id) : WORKING.remove(id);
		if (changed) {
			changed(server);
		}
		return changed;
	}

	private static void changed(final MinecraftServer server) {
		if (server != null) {
			broadcast.send(server, workingPayload().players());
		}
	}

	private static void broadcastToAll(final MinecraftServer server, final List<UUID> working) {
		WorkingPlayers payload = new WorkingPlayers(working);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(p, WorkingPlayers.TYPE)) {
				ServerPlayNetworking.send(p, payload);
			}
		}
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
		if (s.mode() != Mode.MIRROR && u.mode() == Mode.MIRROR) {
			privacy = HologramSpec.defaultPrivacy(Mode.MIRROR); // becoming a Mirror (your whole screen): Private first
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
