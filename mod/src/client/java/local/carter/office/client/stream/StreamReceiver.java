package local.carter.office.client.stream;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;

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
 * hologram's {@link ScreenTexture} ({@code ScreenTexture.forHologram}, one per hologram, reused across streams).
 *
 * <p>The texture is registered at {@link #onStart} (so the renderer shows CONNECTING, then LIVE once tiles arrive). A
 * SIZE message gives the frame size; each TILE is JPEG-decoded and written as a patch (the first one after a size
 * change starts from an opaque black frame). {@link #onEnded}, a CLEAR, or {@link #TIMEOUT_NANOS} without datagrams
 * ({@link #tick}) removes the texture from the registry and clears it (no pixel buffers kept) and forgets the stream in
 * the {@link Packetizer}; the hologram then falls back to CONNECTING / OFFLINE / PRIVATE as its look decides. A new
 * stream for a hologram replaces the old one (owner respawn = new stream id).
 *
 * <p>Thread-safe (all methods synchronized). Production calls everything from one receive thread, never the render
 * thread, so decoding never stalls a frame. Bad datagrams are dropped, never thrown.
 */
public final class StreamReceiver {
	public static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(3);
	public static final int MAX_TILE = 256;

	private static final Logger LOGGER = LogUtils.getLogger();

	private final Function<UUID, ScreenTexture> textureFactory;
	private final TileCodec.Jpeg jpeg;
	private final LongSupplier clock;
	private final Packetizer packetizer;
	private final Map<Integer, Stream> streams = new HashMap<>();
	/** One texture per hologram, kept (cleared) across streams so its id stays stable. */
	private final Map<UUID, ScreenTexture> textures = new HashMap<>();
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

	public StreamReceiver(final Function<UUID, ScreenTexture> textureFactory, final TileCodec.Jpeg jpeg, final LongSupplier clock) {
		this.textureFactory = textureFactory;
		this.jpeg = jpeg;
		this.clock = clock;
		this.packetizer = new Packetizer(clock);
	}

	/** Server: datagrams of {@code streamId} are the screen of {@code hologramId}. */
	public synchronized void onStart(final int streamId, final UUID hologramId) {
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
		ScreenTexture t = textures.computeIfAbsent(hologramId, textureFactory);
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

	/** Disconnect: end every stream (the texture objects are kept, empty, for reuse). */
	public synchronized void clearAll() {
		for (Stream s : new ArrayList<>(streams.values())) {
			end(s);
		}
		packetizer.clear();
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
		for (Stream s : streams.values()) {
			if (now - s.lastPacket > TIMEOUT_NANOS && (s.registered || s.sized)) {
				drop(s);
			}
		}
	}

	public synchronized int streamCount() {
		return streams.size();
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
		if (w < 1 || hh < 1 || w > ScreenTexture.MAX_SIDE || hh > ScreenTexture.MAX_SIDE || tile < 8 || tile > MAX_TILE) {
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
			ScreenTextureRegistry.register(s.hologramId, s.texture);
			s.registered = true;
		}
	}

	/** Loses the picture (registry entry + pixels + reassembly state) but keeps the stream for when it resumes. */
	private void drop(final Stream s) {
		if (s.registered) {
			ScreenTextureRegistry.remove(s.hologramId);
			s.registered = false;
		}
		s.texture.clear();
		s.sized = false;
		packetizer.forget(s.streamId);
	}

	private void end(final Stream s) {
		streams.remove(s.streamId);
		drop(s);
	}

	private static ByteBuffer black(final int w, final int h) {
		byte[] px = new byte[w * h * 4];
		for (int i = 3; i < px.length; i += 4) {
			px[i] = (byte) 0xFF;
		}
		return ByteBuffer.wrap(px);
	}
}
