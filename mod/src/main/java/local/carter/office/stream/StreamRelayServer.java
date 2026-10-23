package local.carter.office.stream;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import local.carter.office.core.Packetizer;
import local.carter.office.core.StreamHeader;

/**
 * UDP relay for shared screens. It never decodes or stores pixels: it checks headers, then forwards bytes.
 *
 * <ul>
 * <li>Binding is a two-step, return-routable handshake, accepted only from the IP of the player's game connection
 * (any port, for NAT; a memory/local game connection allows loopback only):
 * <ol>
 * <li>HELLO = header(HELLO) + 8-byte token (26 bytes). Valid token from the right IP: answered with a challenge
 * HELLO_ACK = header(HELLO_ACK) + 8-byte nonce (26 bytes). Nothing is bound yet.</li>
 * <li>HELLO = header(HELLO) + token + that nonce (34 bytes), from the same IP and port within
 * {@link #CHALLENGE_TTL_NANOS}: binds the player to that address and is answered with header(HELLO_ACK) alone (18
 * bytes, "bound"). Rebinding after a NAT port change is the same handshake.</li>
 * </ol>
 * Anything else (unknown token, wrong IP, wrong or stale nonce, nonce echoed from another address) gets no answer.</li>
 * <li>TILE / SIZE / CLEAR from a client = header + body + its token. Forwarded only if the source address is bound to the
 * player whose token it carries and that player owns the stream in the current {@link #setRoutes routes}; the token is
 * stripped and the rest is sent unchanged to every viewer of the stream (UDP to its bound address, or through its
 * {@link Viewer#fallback} payload sender).</li>
 * <li>Budgets (token buckets, one second deep): {@code maxKbpsPerStream} on everything an owner sends for a stream;
 * {@link #FALLBACK_BYTES_PER_SEC} on owner fallback payloads and, separately, on fallback payloads sent to viewers.
 * Over budget = dropped, never queued; a full socket send buffer also drops.</li>
 * </ul>
 *
 * <p>Routes are an immutable snapshot published by the server thread ({@link StreamRouter}); the Netty thread only reads
 * it. Bind failure leaves the relay in payload-only mode ({@link #udpPort()} == 0) instead of throwing. If the socket
 * closes unexpectedly the relay logs an error and rebinds the same address once after {@link #REBIND_DELAY_MS}; if that
 * fails it stays payload-only and runs the {@link #onUdpLost} callback so clients can be told.
 */
public final class StreamRelayServer implements AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger("office");

	/** Max bytes of one fallback payload's datagram, either direction. */
	public static final int FALLBACK_MAX_BYTES = 30 * 1024;
	/** Fallback budget per stream (each direction), bytes per second. */
	public static final int FALLBACK_BYTES_PER_SEC = 150_000;
	private static final int HELLO_BYTES = StreamHeader.HEADER_BYTES + Packetizer.TOKEN_BYTES;
	private static final int NONCE_BYTES = 8;
	private static final int CONFIRM_BYTES = HELLO_BYTES + NONCE_BYTES;
	/** How long a HELLO challenge nonce stays valid. */
	public static final long CHALLENGE_TTL_NANOS = TimeUnit.SECONDS.toNanos(10);
	/** Delay before rebinding after the socket closed unexpectedly. */
	public static final long REBIND_DELAY_MS = 500;
	private static final SecureRandom RANDOM = new SecureRandom();
	/** Largest datagram the relay sends over UDP (a client datagram minus its token is at most 1192). */
	public static final int UDP_FORWARD_MAX = Packetizer.MAX_DATAGRAM;
	/** Stateless use only ({@code split}); never reassembles anything server-side. */
	private static final Packetizer PACKETIZER = new Packetizer();

	/** One receiver of a stream. {@code fallback} non-null = send over the game connection instead of UDP. */
	public record Viewer(UUID id, @Nullable Consumer<byte[]> fallback) {}

	/** Who may send a stream and who receives it. */
	public record Route(UUID owner, List<Viewer> viewers) {
		public Route {
			viewers = List.copyOf(viewers);
		}
	}

	private record Challenge(InetSocketAddress address, byte[] nonce, long expiresAt) {}

	/** One player's session: token, the IP their game connection uses (null = loopback only), pending challenge. */
	private static final class Session {
		final UUID player;
		final byte[] token;
		final @Nullable InetAddress gameAddress;
		volatile @Nullable Challenge challenge;

		Session(final UUID player, final byte[] token, final @Nullable InetAddress gameAddress) {
			this.player = player;
			this.token = token;
			this.gameAddress = gameAddress;
		}

		boolean allows(final InetAddress source) {
			if (gameAddress == null || gameAddress.isLoopbackAddress()) {
				return source.isLoopbackAddress();
			}
			return gameAddress.equals(source);
		}
	}

	private final int bytesPerSecPerStream;
	private final LongSupplier nanoTime;
	private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
	private final Map<UUID, InetSocketAddress> bindings = new ConcurrentHashMap<>();
	private final Map<InetSocketAddress, UUID> byAddress = new ConcurrentHashMap<>();
	private final Map<Integer, Buckets> buckets = new ConcurrentHashMap<>();
	private volatile Map<Integer, Route> routes = Map.of();

	private @Nullable EventLoopGroup group;
	private @Nullable Bootstrap bootstrap;
	private @Nullable InetSocketAddress bindAddress;
	private volatile @Nullable Channel channel;
	private volatile boolean closing;
	private volatile Runnable udpLost = () -> { };
	private volatile Rebinder rebinder = Bootstrap::bind;

	/** How the relay re-opens its socket after an unexpected close (injectable so tests can force a failure). */
	@FunctionalInterface
	interface Rebinder {
		ChannelFuture bind(Bootstrap bootstrap, InetSocketAddress address);
	}

	public StreamRelayServer(final int maxKbpsPerStream) {
		this(maxKbpsPerStream, System::nanoTime);
	}

	public StreamRelayServer(final int maxKbpsPerStream, final LongSupplier nanoTime) {
		this.bytesPerSecPerStream = Math.max(1, maxKbpsPerStream) * 1000 / 8;
		this.nanoTime = nanoTime;
	}

	/** Binds the UDP socket. On failure logs and stays payload-only; never throws. */
	public synchronized void start(final InetSocketAddress bind) {
		if (channel != null) {
			return;
		}
		EventLoopGroup g = new MultiThreadIoEventLoopGroup(1, new DefaultThreadFactory("office-relay", true),
				NioIoHandler.newFactory());
		try {
			Bootstrap b = new Bootstrap().group(g).channel(NioDatagramChannel.class).handler(new Handler());
			Channel ch = b.bind(bind).syncUninterruptibly().channel();
			this.group = g;
			this.bootstrap = b;
			this.bindAddress = (InetSocketAddress) ch.localAddress();
			attach(ch);
			LOGGER.info("[office] Screen relay listening on UDP {}", ch.localAddress());
		} catch (Exception e) {
			g.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
			LOGGER.error("[office] Could not open the screen relay on UDP {} ({}). Shared screens will use the slower "
					+ "game-connection fallback. Check that the port is free and allowed by the host.", bind, e.toString());
		}
	}

	/** Run (on a Netty thread) when UDP is gone for good after an unexpected close and a failed rebind. */
	public void onUdpLost(final Runnable callback) {
		this.udpLost = callback;
	}

	private void attach(final Channel ch) {
		this.channel = ch;
		ch.closeFuture().addListener(f -> onChannelClosed(ch));
	}

	/** Socket closed without {@link #close()}: log, then rebind the same address once; on failure go payload-only. */
	private void onChannelClosed(final Channel ch) {
		EventLoopGroup g = group;
		Bootstrap b = bootstrap;
		InetSocketAddress addr = bindAddress;
		if (closing || channel != ch || g == null || b == null || addr == null) {
			return;
		}
		channel = null;
		LOGGER.error("[office] Screen relay UDP socket {} closed unexpectedly; rebinding in {} ms", addr, REBIND_DELAY_MS);
		g.schedule(() -> {
			if (closing) {
				return;
			}
			rebinder.bind(b, addr).addListener((ChannelFutureListener) f -> {
				if (closing) {
					f.channel().close();
				} else if (f.isSuccess()) {
					attach(f.channel());
					LOGGER.info("[office] Screen relay rebound on UDP {}", addr);
				} else {
					LOGGER.error("[office] Could not rebind the screen relay on UDP {} ({}). Switching everyone to the "
							+ "game-connection fallback.", addr, String.valueOf(f.cause()));
					udpLost.run();
				}
			});
		}, REBIND_DELAY_MS, TimeUnit.MILLISECONDS);
	}

	/** Test hook: replaces the rebind attempt (e.g. with one that fails) without relying on OS port conflicts. */
	void rebinderForTest(final Rebinder rebinder) {
		this.rebinder = rebinder;
	}

	/** Test hook: the live socket (null when payload-only). */
	@Nullable Channel channelForTest() {
		return channel;
	}

	/** Local UDP port, or 0 when UDP is unavailable (payload-only). */
	public int udpPort() {
		Channel ch = channel;
		return ch != null && ch.localAddress() instanceof InetSocketAddress a ? a.getPort() : 0;
	}

	public boolean payloadOnly() {
		return channel == null;
	}

	/**
	 * New session for {@code player}: only HELLOs carrying {@code token} from {@code gameAddress} (the IP of their game
	 * connection; null for a memory/local connection = loopback only) can bind it. Replaces any earlier session.
	 */
	public void register(final UUID player, final long token, final @Nullable InetAddress gameAddress) {
		unbind(player);
		sessions.put(player, new Session(player, tokenBytes(token), gameAddress));
	}

	/** Player left: forget the token and address. */
	public void unregister(final UUID player) {
		sessions.remove(player);
		unbind(player);
	}

	public @Nullable InetSocketAddress boundAddress(final UUID player) {
		return bindings.get(player);
	}

	/** Replaces the routing snapshot (server thread). Budgets of streams that are gone are dropped. */
	public void setRoutes(final Map<Integer, Route> next) {
		Map<Integer, Route> copy = Map.copyOf(next);
		routes = copy;
		buckets.keySet().retainAll(copy.keySet());
	}

	/**
	 * A datagram the owner sent over the game connection ({@code FallbackTile}, no token). Routed like a UDP one.
	 * Up to {@link #UDP_FORWARD_MAX} bytes it may be any forwardable type and is forwarded as is. Larger ones (up to
	 * {@link #FALLBACK_MAX_BYTES}) must be a single-part TILE: fallback viewers get it as is, UDP viewers get it
	 * re-framed by {@link Packetizer#split} into standard datagrams (the body stays opaque bytes). Returns whether it
	 * was accepted.
	 */
	public boolean fromFallback(final UUID sender, final byte[] datagram) {
		if (datagram.length > FALLBACK_MAX_BYTES) {
			return false;
		}
		Optional<StreamHeader> parsed = StreamHeader.decode(datagram);
		if (parsed.isEmpty() || !forwardable(parsed.get().type())) {
			return false;
		}
		StreamHeader h = parsed.get();
		boolean large = datagram.length > UDP_FORWARD_MAX;
		if (large && (h.type() != StreamHeader.TYPE_TILE || h.parts() != 1 || h.part() != 0)) {
			return false;
		}
		int streamId = h.streamId();
		Route route = routes.get(streamId);
		if (route == null || !route.owner().equals(sender)) {
			return false;
		}
		Buckets b = buckets(streamId);
		if (!b.fallbackIn.tryTake(datagram.length) || !b.stream.tryTake(datagram.length)) {
			return false;
		}
		List<byte[]> udp = large
				? PACKETIZER.split(h, java.util.Arrays.copyOfRange(datagram, StreamHeader.HEADER_BYTES, datagram.length))
				: List.of(datagram);
		forward(route, b, datagram, udp);
		return true;
	}

	/** Handles one UDP datagram (Netty thread; package-private for tests). */
	void onDatagram(final byte[] data, final InetSocketAddress from) {
		Optional<StreamHeader> parsed = StreamHeader.decode(data);
		if (parsed.isEmpty() || data.length > Packetizer.MAX_DATAGRAM) {
			return;
		}
		StreamHeader h = parsed.get();
		switch (h.type()) {
			case StreamHeader.TYPE_HELLO -> hello(data, from);
			default -> {
				if (forwardable(h.type())) {
					media(h, data, from);
				}
			}
		}
	}

	private void hello(final byte[] data, final InetSocketAddress from) {
		if (data.length != HELLO_BYTES && data.length != CONFIRM_BYTES) {
			return;
		}
		Session s = sessionWithToken(data, StreamHeader.HEADER_BYTES);
		if (s == null || !s.allows(from.getAddress())) {
			return; // unknown token or not the player's IP: no answer, so no reflection to spoofed sources
		}
		long now = nanoTime.getAsLong();
		if (data.length == HELLO_BYTES) {
			byte[] nonce = new byte[NONCE_BYTES];
			RANDOM.nextBytes(nonce);
			s.challenge = new Challenge(from, nonce, now + CHALLENGE_TTL_NANOS);
			send(ByteBuffer.allocate(StreamHeader.HEADER_BYTES + NONCE_BYTES)
					.put(new StreamHeader(StreamHeader.TYPE_HELLO_ACK, 0, 0, 0, 0, 0, 0).encode()).put(nonce).array(), from);
			return;
		}
		Challenge c = s.challenge;
		byte[] echoed = java.util.Arrays.copyOfRange(data, HELLO_BYTES, CONFIRM_BYTES);
		if (c == null || !c.address.equals(from) || now - c.expiresAt > 0 || !MessageDigest.isEqual(c.nonce, echoed)) {
			return;
		}
		s.challenge = null;
		bind(s.player, from);
		send(new StreamHeader(StreamHeader.TYPE_HELLO_ACK, 0, 0, 0, 0, 0, 0).encode(), from);
	}

	private void bind(final UUID player, final InetSocketAddress from) {
		InetSocketAddress old = bindings.put(player, from);
		if (old != null && !old.equals(from)) {
			byAddress.remove(old, player);
		}
		UUID previous = byAddress.put(from, player);
		if (previous != null && !previous.equals(player)) {
			bindings.remove(previous, from); // someone else's stale binding on this address
		}
	}

	private void media(final StreamHeader h, final byte[] data, final InetSocketAddress from) {
		if (data.length < StreamHeader.HEADER_BYTES + Packetizer.TOKEN_BYTES) {
			return;
		}
		UUID player = byAddress.get(from);
		Session s = player == null ? null : sessions.get(player);
		if (s == null || !MessageDigest.isEqual(s.token, tokenAt(data, data.length - Packetizer.TOKEN_BYTES))) {
			return;
		}
		Route route = routes.get(h.streamId());
		if (route == null || !route.owner().equals(player)) {
			return;
		}
		byte[] out = java.util.Arrays.copyOf(data, data.length - Packetizer.TOKEN_BYTES);
		Buckets b = buckets(h.streamId());
		if (!b.stream.tryTake(out.length)) {
			return;
		}
		forward(route, b, out, List.of(out));
	}

	/** {@code out} to fallback viewers (one payload), {@code udp} (each at most {@link #UDP_FORWARD_MAX}) to UDP viewers. */
	private void forward(final Route route, final Buckets b, final byte[] out, final List<byte[]> udp) {
		Channel ch = channel;
		boolean wrote = false;
		boolean fallbackPaid = false;
		boolean fallbackAllowed = false;
		for (Viewer v : route.viewers()) {
			if (v.fallback() != null) {
				if (!fallbackPaid) {
					fallbackPaid = true;
					fallbackAllowed = out.length <= FALLBACK_MAX_BYTES && b.fallbackOut.tryTake(out.length);
				}
				if (fallbackAllowed) {
					try {
						v.fallback().accept(out);
					} catch (RuntimeException e) {
						LOGGER.debug("[office] fallback send failed: {}", e.toString());
					}
				}
				continue;
			}
			InetSocketAddress to = bindings.get(v.id());
			if (to != null && ch != null && ch.isWritable()) {
				for (byte[] d : udp) {
					ch.write(new DatagramPacket(Unpooled.wrappedBuffer(d), to));
				}
				wrote = true;
			}
		}
		if (wrote) {
			ch.flush();
		}
	}

	private static boolean forwardable(final int type) {
		return type == StreamHeader.TYPE_TILE || type == StreamHeader.TYPE_SIZE || type == StreamHeader.TYPE_CLEAR;
	}

	/** Constant-time token match against every session (no early exit). */
	private @Nullable Session sessionWithToken(final byte[] data, final int offset) {
		byte[] token = tokenAt(data, offset);
		Session match = null;
		for (Session s : sessions.values()) {
			if (MessageDigest.isEqual(s.token, token)) {
				match = s;
			}
		}
		return match;
	}

	private static byte[] tokenAt(final byte[] data, final int offset) {
		return java.util.Arrays.copyOfRange(data, offset, offset + Packetizer.TOKEN_BYTES);
	}

	static byte[] tokenBytes(final long token) {
		return ByteBuffer.allocate(Packetizer.TOKEN_BYTES).putLong(token).array();
	}

	private void unbind(final UUID player) {
		InetSocketAddress a = bindings.remove(player);
		if (a != null) {
			byAddress.remove(a, player);
		}
	}

	private Buckets buckets(final int streamId) {
		return buckets.computeIfAbsent(streamId, id -> new Buckets());
	}

	private void send(final byte[] data, final InetSocketAddress to) {
		Channel ch = channel;
		if (ch != null && ch.isWritable()) {
			ch.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(data), to));
		}
	}

	@Override
	public synchronized void close() {
		closing = true;
		Channel ch = channel;
		channel = null;
		if (ch != null) {
			ch.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
		}
		if (group != null) {
			group.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(3, TimeUnit.SECONDS);
			group = null;
		}
		sessions.clear();
		bindings.clear();
		byAddress.clear();
		buckets.clear();
		routes = Map.of();
	}

	private final class Buckets {
		final TokenBucket stream = new TokenBucket(bytesPerSecPerStream, nanoTime);
		final TokenBucket fallbackIn = new TokenBucket(FALLBACK_BYTES_PER_SEC, nanoTime);
		final TokenBucket fallbackOut = new TokenBucket(FALLBACK_BYTES_PER_SEC, nanoTime);
	}

	/** Bytes-per-second bucket holding at most one second of budget; starts full. */
	static final class TokenBucket {
		private final double rate;
		private final LongSupplier nanoTime;
		private double tokens;
		private long last;

		TokenBucket(final int bytesPerSec, final LongSupplier nanoTime) {
			this.rate = bytesPerSec;
			this.nanoTime = nanoTime;
			this.tokens = bytesPerSec;
			this.last = nanoTime.getAsLong();
		}

		synchronized boolean tryTake(final int bytes) {
			long now = nanoTime.getAsLong();
			if (now > last) {
				tokens = Math.min(rate, tokens + (now - last) * rate / 1e9);
				last = now;
			}
			if (tokens < bytes) {
				return false;
			}
			tokens -= bytes;
			return true;
		}
	}

	/** Stateless, so the same instance can serve a rebound channel. */
	@ChannelHandler.Sharable
	private final class Handler extends SimpleChannelInboundHandler<DatagramPacket> {
		@Override
		protected void channelRead0(final ChannelHandlerContext ctx, final DatagramPacket packet) {
			ByteBuf content = packet.content();
			int len = content.readableBytes();
			if (len < StreamHeader.HEADER_BYTES || len > Packetizer.MAX_DATAGRAM) {
				return;
			}
			byte[] data = new byte[len];
			content.getBytes(content.readerIndex(), data);
			onDatagram(data, packet.sender());
		}

		@Override
		public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
			LOGGER.debug("[office] relay socket error: {}", cause.toString()); // e.g. ICMP port unreachable; keep going
		}
	}
}
