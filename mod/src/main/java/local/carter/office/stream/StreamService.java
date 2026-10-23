package local.carter.office.stream;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import local.carter.office.hologram.HologramBlockEntity;
import local.carter.office.hologram.HologramIndex;
import local.carter.office.net.OfficePayloads.FallbackTile;
import local.carter.office.net.OfficePayloads.StreamDemand;
import local.carter.office.net.OfficePayloads.StreamEnded;
import local.carter.office.net.OfficePayloads.StreamFallbackTile;
import local.carter.office.net.OfficePayloads.StreamStart;
import local.carter.office.net.OfficePayloads.StreamToken;
import local.carter.office.net.OfficePayloads.UdpStatus;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;

/**
 * One per running server: config, the {@link StreamRouter} (server thread) and the {@link StreamRelayServer} (Netty).
 * Wired from {@code OfficeMod}: start/stop with the server, tick every server tick, join/leave, and the two C2S payloads.
 */
public final class StreamService implements StreamRouter.Sink {
	private static final Logger LOGGER = LoggerFactory.getLogger("office");
	private static final SecureRandom RANDOM = new SecureRandom();
	private static volatile @Nullable StreamService instance;

	private final MinecraftServer server;
	private final OfficeConfig config;
	private final StreamRelayServer relay;
	private final StreamRouter router;
	/** Players that reported no HELLO_ACK: their viewer datagrams go over the game connection. */
	private final Set<UUID> udpFailed = ConcurrentHashMap.newKeySet();
	/** This session's token per online player (re-sent with udpPort 0 if UDP is lost). */
	private final Map<UUID, Long> tokens = new ConcurrentHashMap<>();

	private StreamService(final MinecraftServer server, final OfficeConfig config) {
		this.server = server;
		this.config = config;
		this.relay = new StreamRelayServer(config.maxKbpsPerStream());
		this.router = new StreamRouter(this, config.viewRange(), () -> server.getPlayerList().getPlayers(),
				this::onlineOwnersHolograms);
		this.router.onRoutes(this::publish);
		this.relay.onUdpLost(() -> server.execute(this::udpLost));
	}

	public static @Nullable StreamService get() {
		return instance;
	}

	public static void registerReceivers() {
		ServerPlayNetworking.registerGlobalReceiver(FallbackTile.TYPE, (p, ctx) -> {
			StreamService s = instance;
			if (s != null) {
				s.relay.fromFallback(ctx.player().getUUID(), p.datagram());
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(UdpStatus.TYPE, (p, ctx) -> {
			StreamService s = instance;
			if (s != null) {
				s.setUdpStatus(ctx.player().getUUID(), p.ok());
			}
		});
	}

	/** Server started: load config and open the relay (a bind failure only logs; streams then use payloads). */
	public static void start(final MinecraftServer server) {
		stop();
		OfficeConfig config = OfficeConfig.load(FabricLoader.getInstance().getConfigDir().resolve(OfficeConfig.FILE_NAME));
		StreamService s = new StreamService(server, config);
		if (config.udpPort() > 0) {
			s.relay.start(bindAddress(server, config.udpPort()));
		} else {
			LOGGER.info("[office] udpPort is 0: shared screens use the game-connection fallback only");
		}
		instance = s;
	}

	/**
	 * Dedicated servers listen on all interfaces. Singleplayer / dev runs bind loopback only, so opening a world never
	 * triggers a firewall prompt (LAN guests then use the payload fallback).
	 */
	private static InetSocketAddress bindAddress(final MinecraftServer server, final int port) {
		boolean publicBind = server.isDedicatedServer() && !FabricLoader.getInstance().isDevelopmentEnvironment();
		return publicBind ? new InetSocketAddress(port) : new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
	}

	public static void stop() {
		StreamService s = instance;
		instance = null;
		if (s != null) {
			s.router.close();
			s.relay.close();
		}
	}

	public void tick() {
		router.tick();
	}

	public void onJoin(final ServerPlayer player) {
		long token = RANDOM.nextLong();
		relay.register(player.getUUID(), token, gameAddress(player.connection.getRemoteAddress()));
		tokens.put(player.getUUID(), token);
		udpFailed.remove(player.getUUID());
		send(player, new StreamToken(token, relay.udpPort()));
	}

	/**
	 * The IP UDP datagrams from this player must come from: their game connection's IP, or null (= loopback only) for an
	 * in-memory / local connection (singleplayer host, test mocks).
	 */
	static @Nullable InetAddress gameAddress(final @Nullable SocketAddress remote) {
		return remote instanceof InetSocketAddress isa && isa.getAddress() != null ? isa.getAddress() : null;
	}

	/** UDP socket died and could not be rebound (server thread): tell every client to use the fallback. */
	private void udpLost() {
		if (instance != this) {
			return;
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Long token = tokens.get(p.getUUID());
			if (token != null) {
				send(p, new StreamToken(token, 0));
			}
		}
		StreamRouter.rerouteNow(); // republish: every viewer becomes a fallback viewer
	}

	public void onLeave(final ServerPlayer player) {
		tokens.remove(player.getUUID());
		relay.unregister(player.getUUID());
		udpFailed.remove(player.getUUID());
		StreamRouter.signalChange();
	}

	void setUdpStatus(final UUID player, final boolean ok) {
		if (ok ? udpFailed.remove(player) : udpFailed.add(player)) {
			StreamRouter.signalChange();
		}
	}

	public StreamRouter router() {
		return router;
	}

	public StreamRelayServer relay() {
		return relay;
	}

	public OfficeConfig config() {
		return config;
	}

	/** Loaded holograms of online owners, via the index (holograms of offline owners never stream). */
	private List<HologramBlockEntity> onlineOwnersHolograms() {
		List<HologramBlockEntity> out = new ArrayList<>();
		HologramIndex index = HologramIndex.get(server);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			for (HologramIndex.Entry e : index.entriesOwnedBy(p.getUUID())) {
				ServerLevel level = server.getLevel(e.dimension());
				if (level != null && level.isLoaded(e.pos()) && level.getBlockEntity(e.pos()) instanceof HologramBlockEntity be) {
					out.add(be);
				}
			}
		}
		return out;
	}

	/** Router result -> relay snapshot (viewers on fallback get a payload sender instead of a UDP address). */
	private void publish(final List<StreamRouter.Route> routes) {
		Map<Integer, StreamRelayServer.Route> snapshot = new HashMap<>();
		boolean payloadOnly = relay.payloadOnly();
		for (StreamRouter.Route r : routes) {
			List<StreamRelayServer.Viewer> viewers = new ArrayList<>(r.viewers().size());
			for (ServerPlayer v : r.viewers()) {
				boolean fallback = payloadOnly || udpFailed.contains(v.getUUID());
				int sid = r.streamId();
				Consumer<byte[]> sender = null;
				if (fallback) {
					sender = onServerThread(server,
							() -> server.getPlayerList().getPlayer(v.getUUID()) == v && !v.hasDisconnected()
									&& ServerPlayNetworking.canSend(v, StreamFallbackTile.TYPE),
							bytes -> ServerPlayNetworking.send(v, new StreamFallbackTile(sid, bytes)));
				}
				viewers.add(new StreamRelayServer.Viewer(v.getUUID(), sender));
			}
			snapshot.put(r.streamId(), new StreamRelayServer.Route(r.owner().getUUID(), viewers));
		}
		relay.setRoutes(snapshot);
	}

	/**
	 * Wraps a fallback send for the relay: the relay may call it on its Netty thread, so the send is handed to the
	 * server thread ({@code serverThread.execute}; runs inline when already on it), where {@code stillValid} re-checks
	 * that the captured player is still this online session before anything is sent.
	 */
	static Consumer<byte[]> onServerThread(final Executor serverThread, final BooleanSupplier stillValid,
			final Consumer<byte[]> send) {
		return bytes -> serverThread.execute(() -> {
			if (stillValid.getAsBoolean()) {
				send.accept(bytes);
			}
		});
	}

	@Override
	public void demand(final ServerPlayer owner, final UUID hologramId, final int streamId, final boolean wanted) {
		send(owner, new StreamDemand(hologramId, streamId, wanted));
	}

	@Override
	public void start(final ServerPlayer viewer, final int streamId, final UUID hologramId) {
		send(viewer, new StreamStart(streamId, hologramId));
	}

	@Override
	public void ended(final ServerPlayer viewer, final int streamId) {
		send(viewer, new StreamEnded(streamId));
	}

	/** Sends only to clients that registered the channel (a client without the mod, or a test mock, is skipped). */
	private static void send(final ServerPlayer player, final CustomPacketPayload payload) {
		if (ServerPlayNetworking.canSend(player, payload.type())) {
			ServerPlayNetworking.send(player, payload);
		}
	}
}
