package local.carter.office.client.stream;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import local.carter.office.client.capture.OwnScreens;
import local.carter.office.client.interact.HologramTracker;
import local.carter.office.client.render.ScreenTexture;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Packetizer;
import local.carter.office.core.Privacy;
import local.carter.office.core.StreamHeader;
import local.carter.office.core.TileCodec;
import local.carter.office.hologram.HologramBlockEntity;
import local.carter.office.net.OfficePayloads;
import local.carter.office.net.OfficePayloads.FallbackTile;
import local.carter.office.net.OfficePayloads.StreamDemand;
import local.carter.office.net.OfficePayloads.StreamEnded;
import local.carter.office.net.OfficePayloads.StreamFallbackTile;
import local.carter.office.net.OfficePayloads.StreamStart;
import local.carter.office.net.OfficePayloads.StreamToken;
import local.carter.office.net.OfficePayloads.UdpStatus;

import net.minecraft.client.Minecraft;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Client side of shared screens: the transport ({@link UdpLink} to the server's relay, or the game-connection payload
 * fallback) plus one {@link StreamSender} (own screens out) and one {@link StreamReceiver} (friends' screens in).
 *
 * <p>{@link #connect(StreamToken)} on every token: {@code udpPort == 0} (no UDP, or UDP lost mid-session) closes the
 * UDP link and reports {@code UdpStatus(false)}; otherwise a UDP handshake starts and its result is reported
 * ({@code UdpStatus(true)} once bound, {@code false} if not bound within 5 s). Until UDP is bound, owner datagrams go
 * out as {@code FallbackTile}s.
 *
 * <p>Threads: the payload handlers and {@link #connect} run on the client thread; receiving/decoding runs on the
 * receive executor; the sender has its own thread ({@link #pump}). Production wiring is {@link #register()}.
 */
public final class StreamClient {
	/** Datagrams waiting for the receive thread beyond this are dropped (refresh tiles repair the picture). */
	public static final int MAX_QUEUED_DATAGRAMS = 4096;
	/** A local switch to PRIVATE is trusted over the (not yet updated) synced spec for this long. */
	public static final long LOCAL_PRIVATE_NANOS = TimeUnit.SECONDS.toNanos(5);

	private static final Logger LOGGER = LogUtils.getLogger();

	/** Game-connection side (payloads). Thread-safe. */
	public interface Payloads {
		void sendFallback(byte[] datagram);

		void sendUdpStatus(boolean ok);
	}

	public interface UdpOpener {
		UdpLink open(InetSocketAddress relay, long token, UdpLink.Listener listener) throws IOException;
	}

	private final Payloads payloads;
	private final UdpOpener opener;
	private final Supplier<@Nullable InetAddress> serverHost;
	private final Executor receiveExecutor;
	private final AtomicInteger queued = new AtomicInteger();
	private final Packetizer splitter = new Packetizer();
	private final StreamSender sender;
	private final StreamReceiver receiver;
	private volatile @Nullable UdpLink udp;

	public StreamClient(final Payloads payloads, final UdpOpener opener, final Supplier<@Nullable InetAddress> serverHost,
			final TileCodec.Jpeg sendJpeg, final TileCodec.Jpeg receiveJpeg, final Function<UUID, @Nullable Privacy> privacyOf,
			final Function<UUID, @Nullable ScreenTexture> ownTexture, final Function<UUID, ScreenTexture> textureFactory,
			final Executor receiveExecutor, final LongSupplier clock) {
		this.payloads = payloads;
		this.opener = opener;
		this.serverHost = serverHost;
		this.receiveExecutor = receiveExecutor;
		this.sender = new StreamSender(new Link(), sendJpeg, privacyOf, ownTexture, clock);
		this.receiver = new StreamReceiver(textureFactory, receiveJpeg, clock);
	}

	public StreamSender sender() {
		return sender;
	}

	public StreamReceiver receiver() {
		return receiver;
	}

	/** Whether owner datagrams currently go over UDP. */
	public boolean udpBound() {
		UdpLink u = udp;
		return u != null && u.bound();
	}

	// ---- payload handlers (client thread) ----

	/** This session's relay token (on join, or again with {@code udpPort == 0} when the server lost UDP). */
	public void connect(final StreamToken t) {
		closeUdp();
		InetAddress host = t.udpPort() > 0 ? serverHost.get() : null;
		if (host == null) {
			if (t.udpPort() > 0) {
				LOGGER.info("[office] Shared screens: server address unknown, using the game-connection fallback");
			}
			payloads.sendUdpStatus(false);
			return;
		}
		try {
			udp = opener.open(new InetSocketAddress(host, t.udpPort()), t.token(), new UdpLink.Listener() {
				@Override
				public void onStatus(final boolean ok) {
					LOGGER.info("[office] Shared screens: {}", ok ? "UDP relay connected" : "UDP relay unreachable, using the game-connection fallback");
					payloads.sendUdpStatus(ok);
				}

				@Override
				public void onDatagram(final byte[] datagram) {
					enqueueDatagram(datagram);
				}
			});
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("[office] Shared screens: could not open UDP ({}), using the game-connection fallback", e.toString());
			payloads.sendUdpStatus(false);
		}
	}

	public void onDemand(final StreamDemand d) {
		try {
			sender.onDemand(d);
		} catch (RuntimeException e) {
			LOGGER.warn("[office] Screen share demand failed", e);
		}
	}

	public void onStart(final StreamStart s) {
		receiveExecutor.execute(() -> safely(() -> receiver.onStart(s.streamId(), s.hologramId())));
	}

	public void onEnded(final StreamEnded e) {
		sender.onEnded(e.streamId());
		receiveExecutor.execute(() -> safely(() -> receiver.onEnded(e.streamId())));
	}

	public void onFallbackTile(final StreamFallbackTile p) {
		byte[] d = p.datagram();
		var h = StreamHeader.decode(d);
		if (h.isPresent() && h.get().streamId() == p.streamId()) {
			enqueueDatagram(d);
		}
	}

	/** Left the server: stop sharing, drop friends' screens, close UDP. */
	public void disconnect() {
		closeUdp();
		sender.stopAll();
		receiveExecutor.execute(() -> safely(receiver::clearAll));
	}

	// ---- worker threads ----

	/** Sender thread. */
	public void pump() {
		safely(sender::pump);
	}

	/** Receive thread, about twice a second. */
	public void tickReceiver() {
		safely(receiver::tick);
	}

	private void enqueueDatagram(final byte[] d) {
		if (queued.incrementAndGet() > MAX_QUEUED_DATAGRAMS) {
			queued.decrementAndGet();
			return;
		}
		try {
			receiveExecutor.execute(() -> {
				try {
					safely(() -> receiver.onDatagram(d));
				} finally {
					queued.decrementAndGet();
				}
			});
		} catch (RuntimeException e) {
			queued.decrementAndGet();
		}
	}

	private void closeUdp() {
		UdpLink u = udp;
		udp = null;
		if (u != null) {
			u.close();
		}
	}

	private static void safely(final Runnable r) {
		try {
			r.run();
		} catch (Throwable t) {
			LOGGER.warn("[office] Shared-screen task failed", t);
		}
	}

	/** Owner datagrams: UDP with the token when bound, else {@code FallbackTile} payloads. */
	private final class Link implements StreamSender.Link {
		@Override
		public boolean udp() {
			return udpBound();
		}

		@Override
		public void send(final StreamHeader h, final byte[] body) {
			UdpLink u = udp;
			if (u != null && u.bound()) {
				for (byte[] d : splitter.split(h, body, u.token())) {
					u.send(d);
				}
				return;
			}
			if (h.type() == StreamHeader.TYPE_TILE && StreamHeader.HEADER_BYTES + body.length <= OfficePayloads.MAX_FALLBACK_DATAGRAM) {
				// one whole tile per payload (the relay re-frames it for UDP viewers)
				ByteBuffer b = ByteBuffer.allocate(StreamHeader.HEADER_BYTES + body.length);
				new StreamHeader(h.type(), h.streamId(), h.frameSeq(), h.tx(), h.ty(), 0, 1).writeTo(b);
				payloads.sendFallback(b.put(body).array());
				return;
			}
			for (byte[] d : splitter.split(h, body)) {
				payloads.sendFallback(d);
			}
		}
	}

	// =============================== production wiring ===============================

	private static final Map<UUID, Privacy> PRIVACY = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LOCAL_PRIVATE = new ConcurrentHashMap<>();
	private static @Nullable StreamClient instance;

	public static @Nullable StreamClient get() {
		return instance;
	}

	/** Client init: threads, payload receivers, disconnect + tick hooks, the OwnScreens tap. */
	public static void register() {
		ScheduledExecutorService send = Executors.newSingleThreadScheduledExecutor(daemon("office-stream-send"));
		ScheduledExecutorService recv = Executors.newSingleThreadScheduledExecutor(daemon("office-stream-recv"));
		StreamClient c = new StreamClient(new Payloads() {
			@Override
			public void sendFallback(final byte[] datagram) {
				try {
					if (ClientPlayNetworking.canSend(FallbackTile.TYPE)) {
						ClientPlayNetworking.send(new FallbackTile(datagram)); // Connection.send is thread-safe
					}
				} catch (RuntimeException e) {
					LOGGER.debug("[office] Fallback tile not sent: {}", e.toString());
				}
			}

			@Override
			public void sendUdpStatus(final boolean ok) {
				Minecraft.getInstance().execute(() -> {
					if (ClientPlayNetworking.canSend(UdpStatus.TYPE)) {
						ClientPlayNetworking.send(new UdpStatus(ok));
					}
				});
			}
		}, UdpLink::open, StreamClient::serverHost, new LazyJpeg(), new LazyJpeg(), PRIVACY::get, OwnScreens::texture,
				ScreenTexture::forHologram, recv, System::nanoTime);
		instance = c;
		send.scheduleAtFixedRate(c::pump, 20, 20, TimeUnit.MILLISECONDS);
		recv.scheduleAtFixedRate(c::tickReceiver, 500, 500, TimeUnit.MILLISECONDS);
		OwnScreens.setShareTaps(c.sender::tap);

		ClientPlayNetworking.registerGlobalReceiver(StreamToken.TYPE, (p, ctx) -> c.connect(p));
		ClientPlayNetworking.registerGlobalReceiver(StreamDemand.TYPE, (p, ctx) -> c.onDemand(p));
		ClientPlayNetworking.registerGlobalReceiver(StreamStart.TYPE, (p, ctx) -> c.onStart(p));
		ClientPlayNetworking.registerGlobalReceiver(StreamEnded.TYPE, (p, ctx) -> c.onEnded(p));
		ClientPlayNetworking.registerGlobalReceiver(StreamFallbackTile.TYPE, (p, ctx) -> c.onFallbackTile(p));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			c.disconnect();
			PRIVACY.clear();
			LOCAL_PRIVATE.clear();
		});
		ClientTickEvents.END_CLIENT_TICK.register(StreamClient::tickPrivacy);
	}

	/**
	 * The owner just changed a hologram's privacy in the settings screen (before the server confirms): a switch to
	 * PRIVATE stops sharing at once, without waiting for the server or the synced spec.
	 */
	public static void localPrivacyChanged(final UUID hologramId, final Privacy privacy) {
		if (privacy.level() == Privacy.Level.PRIVATE) {
			LOCAL_PRIVATE.put(hologramId, System.nanoTime() + LOCAL_PRIVATE_NANOS);
			PRIVACY.put(hologramId, Privacy.PRIVATE);
		} else {
			LOCAL_PRIVATE.remove(hologramId);
		}
	}

	/** Client thread: the privacy of each loaded own hologram, read by the sender before every frame. */
	static void tickPrivacy(final Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			PRIVACY.clear();
			return;
		}
		UUID me = mc.player.getUUID();
		long now = System.nanoTime();
		Set<UUID> seen = new HashSet<>();
		for (HologramBlockEntity be : HologramTracker.loaded()) {
			HologramSpec spec = be.spec();
			if (spec == null || !me.equals(spec.owner())) {
				continue;
			}
			UUID id = be.hologramId();
			Privacy p = spec.privacy();
			Long until = LOCAL_PRIVATE.get(id);
			if (until != null) {
				if (now - until >= 0 || p.level() == Privacy.Level.PRIVATE) {
					LOCAL_PRIVATE.remove(id);
				} else {
					p = Privacy.PRIVATE; // the synced spec hasn't caught up with the local switch yet
				}
			}
			PRIVACY.put(id, p);
			seen.add(id);
		}
		PRIVACY.keySet().retainAll(seen);
	}

	/** The game server's IP (UDP goes to the same host); loopback for an integrated/local connection. */
	private static @Nullable InetAddress serverHost() {
		var conn = Minecraft.getInstance().getConnection();
		if (conn == null) {
			return null;
		}
		SocketAddress a = conn.getConnection().getRemoteAddress();
		if (a instanceof InetSocketAddress isa) {
			return isa.getAddress();
		}
		return InetAddress.getLoopbackAddress();
	}

	private static java.util.concurrent.ThreadFactory daemon(final String name) {
		return r -> {
			Thread t = new Thread(r, name);
			t.setDaemon(true);
			return t;
		};
	}

	/** Creates its {@link StbJpeg} on first use, on the (single) thread that uses it. */
	private static final class LazyJpeg implements TileCodec.Jpeg {
		private @Nullable StbJpeg jpeg;

		private StbJpeg jpeg() {
			StbJpeg j = jpeg;
			if (j == null) {
				j = new StbJpeg();
				jpeg = j;
			}
			return j;
		}

		@Override
		public byte[] encode(final TileCodec.Tile tile, final int quality) {
			return jpeg().encode(tile, quality);
		}

		@Override
		public void decodeInto(final byte[] data, final ByteBuffer dst, final int w, final int h) {
			jpeg().decodeInto(data, dst, w, h);
		}
	}
}
