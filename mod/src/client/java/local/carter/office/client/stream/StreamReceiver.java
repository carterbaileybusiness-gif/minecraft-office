package local.carter.office.client.stream;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import local.carter.office.client.render.ScreenTexture;
import local.carter.office.client.render.ScreenTextureRegistry;
import local.carter.office.core.Packetizer;
import local.carter.office.core.StreamHeader;
import local.carter.office.core.TileCodec;

/**
 * Shows friends' shared screens: assembles the stream datagrams of each {@code StreamStart}ed stream into that
 * hologram's {@link ScreenTexture} ({@code ScreenTexture.forHologram}, one per hologram while it has a stream).
 *
 * <p>A stream starts in two halves: {@link #prepareStart} on the client thread creates (or reuses) the hologram's
 * texture there, because creating its GPU side touches the render system; {@link #begin} on the receive thread then
 * binds the stream to it. The texture is registered right away (the renderer shows CONNECTING, then LIVE once tiles
 * arrive). A SIZE message gives the frame size (at most {@link StreamSender#MAX_SIDE} a side, tiles at most
 * {@link #MAX_TILE}; anything bigger ends the stream); each TILE is JPEG-decoded and written as a patch (the first one
 * after a size change starts from an opaque black frame). {@link #onEnded}, a CLEAR, or {@link #TIMEOUT_NANOS} without
 * datagrams ({@link #tick}) removes the texture from the registry and clears it (no pixel buffers kept) and forgets the
 * stream in the {@link Packetizer}; the hologram then falls back to CONNECTING / OFFLINE / PRIVATE as its look decides.
 * A new stream for a hologram replaces the old one (owner respawn = new stream id). A hologram the local player owns is
 * never received into: a friend's stream can't replace your own screen.
 *
 * <p>Threads: the receive-thread methods are synchronized and only write CPU-side pixels ({@link ScreenTexture#full} /
 * {@link ScreenTexture#patch} / {@link ScreenTexture#clear} are safe off the render thread), so decoding never stalls a
 * frame. {@link ScreenTextureRegistry} changes run on the client thread (the registry's contract): the receiver records
 * which texture each hologram should show and posts a reconcile task, which applies the <em>current</em> wish (so queued
 * tasks can't undo a later change) and drops the texture object once its hologram has no stream. Bad datagrams are
 * dropped, never thrown.
 */
public final class StreamReceiver {
	public static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(3);
	/** Largest accepted tile side (the sender uses {@link StreamSender#TILE}). */
	public static final int MAX_TILE = StreamSender.TILE;

	private static final Logger LOGGER = LogUtils.getLogger();

	private final Function<UUID, ScreenTexture> textureFactory;
	private final Executor clientThread;
	private final Predicate<UUID> isOwnHologram;
	private final TileCodec.Jpeg jpeg;
	private final LongSupplier clock;
	private final Packetizer packetizer;
	/** The texture each hologram should show right now; the client thread mirrors it into the registry. */
	private final Map<UUID, ScreenTexture> wanted = new ConcurrentHashMap<>();
	/** Holograms with a (prepared or running) stream, by their newest stream id. */
	private final Map<UUID, Integer> active = new ConcurrentHashMap<>();
	/** Texture objects by hologram; created and pruned on the client thread only. */
	private final Map<UUID, ScreenTexture> textures = new ConcurrentHashMap<>();
	private final Map<Integer, Stream> streams = new HashMap<>();
	private final ByteBuffer decodeBuf = ByteBuffer.allocateDirect(MAX_TILE * MAX_TILE * 4);

	private static final class Stream {
		final int streamId;
		final UUID hologramId;
		final ScreenTexture texture;
		long lastPacket;
		boolean registered;
		boolean sized;
		int sizeSeq;
		int w;
		int h;
		int tile;

		Stream(final int streamId, final UUID hologramId, final ScreenTexture texture, final long now) {
			this.streamId = streamId;
			this.hologramId = hologramId;
			this.texture = texture;
			this.lastPacket = now;
		}
	}

	/**
	 * @param textureFactory makes a hologram's texture (called on the client thread only)
	 * @param clientThread runs texture creation follow-ups and registry changes on the client thread
	 *                     ({@code Minecraft::execute}; tests: direct)
	 * @param isOwnHologram whether the local player owns a hologram (thread-safe); those are never received into
	 */
	public StreamReceiver(final Function<UUID, ScreenTexture> textureFactory, final TileCodec.Jpeg jpeg, final LongSupplier clock,
			final Executor clientThread, final Predicate<UUID> isOwnHologram) {
		this.textureFactory = textureFactory;
		this.clientThread = clientThread;
		this.isOwnHologram = isOwnHologram;
		this.jpeg = jpeg;
		this.clock = clock;
		this.packetizer = new Packetizer(clock);
	}

	// ---- client thread ----

	/**
	 * Client thread, on {@code StreamStart}: makes sure the hologram has a texture (created here, never on the receive
	 * thread). False when the stream must be ignored (the local player's own hologram); otherwise call {@link #begin}
	 * on the receive thread next.
	 */
	public boolean prepareStart(final int streamId, final UUID hologramId) {
		if (isOwnHologram.test(hologramId)) {
			LOGGER.debug("[office] Ignoring a stream for own hologram {}", hologramId);
			return false;
		}
		active.put(hologramId, streamId);
		textures.computeIfAbsent(hologramId, textureFactory);
		return true;
	}

	/** Client thread: make the registry show what the receiver wants for this hologram now; prune unused textures. */
	private void reconcile(final UUID hologramId) {
		ScreenTexture want = wanted.get(hologramId);
		if (isOwnHologram.test(hologramId)) {
			want = null; // the registry entry belongs to OwnScreens: never replace or remove it from here
		} else if (want != null) {
			ScreenTextureRegistry.register(hologramId, want);
		} else {
			ScreenTextureRegistry.remove(hologramId);
		}
		if (!active.containsKey(hologramId) && want == null) {
			ScreenTexture t = textures.remove(hologramId);
			if (t != null) {
				t.clear();
			}
		}
	}

	private void postReconcile(final UUID hologramId) {
		try {
			clientThread.execute(() -> reconcile(hologramId));
		} catch (RuntimeException e) {
			LOGGER.debug("[office] Registry update for {} not queued: {}", hologramId, e.toString());
		}
	}

	// ---- receive thread ----

	/** Receive thread, after {@link #prepareStart}: datagrams of {@code streamId} are the screen of {@code hologramId}. */
	public synchronized void begin(final int streamId, final UUID hologramId) {
		ScreenTexture t = textures.get(hologramId);
		if (t == null || isOwnHologram.test(hologramId)) {
			if (active.remove(hologramId, streamId)) {
				postReconcile(hologramId);
			}
			return;
		}
		Stream cur = streams.get(streamId);
		if (cur != null) {
			if (cur.hologramId.equals(hologramId)) {
				return;
			}
			end(cur);
		}
		for (Stream s : new ArrayList<>(streams.values())) {
			if (s.hologramId.equals(hologramId)) {
				end(s); // replaced (e.g. the owner rejoined: new stream id)
			}
		}
		active.put(hologramId, streamId);
		t.clear();
		Stream s = new Stream(streamId, hologramId, t, clock.getAsLong());
		streams.put(streamId, s);
		register(s);
	}

	/** Server: the stream is over (private, out of range, owner offline, hologram removed). */
	public synchronized void onEnded(final int streamId) {
		Stream s = streams.get(streamId);
		if (s != null) {
			end(s);
		}
	}

	/** Disconnect: end every stream and drop every texture. */
	public synchronized void clearAll() {
		for (Stream s : new ArrayList<>(streams.values())) {
			end(s);
		}
		packetizer.clear();
		for (UUID id : new ArrayList<>(active.keySet())) {
			active.remove(id);
			postReconcile(id);
		}
		for (UUID id : textures.keySet()) {
			postReconcile(id);
		}
	}

	/** One datagram (header + body, no token) from UDP or a fallback payload. */
	public synchronized void onDatagram(final byte[] datagram) {
		Optional<StreamHeader> parsed = StreamHeader.decode(datagram);
		if (parsed.isEmpty()) {
			return;
		}
		StreamHeader h = parsed.get();
		Stream s = streams.get(h.streamId());
		if (s == null) {
			return; // not (yet) started, or already ended
		}
		if (isOwnHologram.test(s.hologramId)) {
			end(s); // became ours (e.g. loaded after the start): never show someone else's pixels on it
			return;
		}
		s.lastPacket = clock.getAsLong();
		try {
			switch (h.type()) {
				case StreamHeader.TYPE_SIZE -> onSize(s, h, datagram);
				case StreamHeader.TYPE_CLEAR -> onClear(s, h);
				case StreamHeader.TYPE_TILE -> onTile(s, datagram);
				default -> {
				}
			}
		} catch (RuntimeException e) {
			LOGGER.debug("[office] Dropped a bad datagram of stream {}: {}", s.streamId, e.toString());
		}
	}

	/** Periodic (receive thread): streams silent for {@link #TIMEOUT_NANOS} lose their picture. */
	public synchronized void tick() {
		long now = clock.getAsLong();
		for (Stream s : new ArrayList<>(streams.values())) {
			if (now - s.lastPacket > TIMEOUT_NANOS && (s.registered || s.sized)) {
				drop(s);
			}
		}
	}

	public synchronized int streamCount() {
		return streams.size();
	}

	/** Texture objects currently held (tests: pruned once a hologram has no stream). */
	public int textureCount() {
		return textures.size();
	}

	/** The texture of an active stream for a hologram (tests / debugging). */
	public synchronized @Nullable ScreenTexture textureOf(final UUID hologramId) {
		for (Stream s : streams.values()) {
			if (s.hologramId.equals(hologramId)) {
				return s.texture;
			}
		}
		return null;
	}

	/** Bytes held by incomplete tiles (tests). */
	public long bufferedBytes() {
		return packetizer.bufferedBytes();
	}

	private void onSize(final Stream s, final StreamHeader h, final byte[] d) {
		int body = d.length - StreamHeader.HEADER_BYTES;
		if (body < 4) {
			return;
		}
		ByteBuffer b = ByteBuffer.wrap(d, StreamHeader.HEADER_BYTES, body);
		int w = b.getShort() & 0xFFFF;
		int hh = b.getShort() & 0xFFFF;
		int tile = body >= 6 ? b.getShort() & 0xFFFF : StreamSender.TILE;
		if (w < 1 || hh < 1 || w > StreamSender.MAX_SIDE || hh > StreamSender.MAX_SIDE || tile < 8 || tile > MAX_TILE) {
			LOGGER.warn("[office] Ending shared-screen stream {}: bad frame size {}x{} (tile {})", s.streamId, w, hh, tile);
			end(s); // no peer gets to make us allocate more than a 1280x1280 frame
			return;
		}
		if (s.sized && h.frameSeq() - s.sizeSeq < 0) {
			return; // an older SIZE arriving late
		}
		s.sizeSeq = h.frameSeq();
		if (!s.sized || s.w != w || s.h != hh || s.tile != tile) {
			s.w = w;
			s.h = hh;
			s.tile = tile;
			s.sized = true;
		}
		register(s);
	}

	private void onClear(final Stream s, final StreamHeader h) {
		if (s.sized && h.frameSeq() - s.sizeSeq < 0) {
			return;
		}
		drop(s);
	}

	private void onTile(final Stream s, final byte[] d) {
		Optional<Packetizer.Assembled> a = packetizer.accept(d);
		if (a.isEmpty() || !s.sized || a.get().frameSeq() - s.sizeSeq < 0) {
			return; // incomplete, or no / an older frame size
		}
		Packetizer.Assembled t = a.get();
		int x = t.tx() * s.tile;
		int y = t.ty() * s.tile;
		if (x >= s.w || y >= s.h) {
			return;
		}
		int tw = Math.min(s.tile, s.w - x);
		int th = Math.min(s.tile, s.h - y);
		ByteBuffer buf = decodeBuf.clear().limit(tw * th * 4);
		jpeg.decodeInto(t.jpeg(), buf, tw, th);
		buf.position(0).limit(tw * th * 4);
		ScreenTexture tex = s.texture;
		if (tex.width() != s.w || tex.height() != s.h) {
			tex.full(s.w, s.h, black(s.w, s.h));
		}
		tex.patch(x, y, tw, th, buf);
		register(s);
	}

	private void register(final Stream s) {
		if (!s.registered) {
			s.registered = true;
			wanted.put(s.hologramId, s.texture);
			postReconcile(s.hologramId);
		}
	}

	/** Loses the picture (registry entry + pixels + reassembly state) but keeps the stream for when it resumes. */
	private void drop(final Stream s) {
		if (s.registered) {
			s.registered = false;
			wanted.remove(s.hologramId, s.texture);
			postReconcile(s.hologramId);
		}
		s.texture.clear();
		s.sized = false;
		packetizer.forget(s.streamId);
	}

	private void end(final Stream s) {
		streams.remove(s.streamId);
		drop(s);
		if (active.remove(s.hologramId, s.streamId)) {
			postReconcile(s.hologramId); // prunes the texture object unless a newer stream is on its way
		}
	}

	private static ByteBuffer black(final int w, final int h) {
		byte[] px = new byte[w * h * 4];
		for (int i = 3; i < px.length; i += 4) {
			px[i] = (byte) 0xFF;
		}
		return ByteBuffer.wrap(px);
	}
}
