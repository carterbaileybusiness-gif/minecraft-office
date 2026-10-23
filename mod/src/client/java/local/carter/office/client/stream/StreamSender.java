package local.carter.office.client.stream;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.LongSupplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import local.carter.office.client.capture.FrameSink;
import local.carter.office.client.render.ScreenTexture;
import local.carter.office.core.Packetizer;
import local.carter.office.core.Privacy;
import local.carter.office.core.StreamHeader;
import local.carter.office.core.TileCodec;
import local.carter.office.core.TileFrame;
import local.carter.office.net.OfficePayloads.StreamDemand;

/**
 * Shares the local player's own (non-private) screens with the players the server says are watching.
 *
 * <p>One {@link Share} per demanded hologram. It is a <em>tap</em> on the hologram's existing local capture
 * ({@link local.carter.office.client.capture.OwnScreens} fans every frame out to {@link #tap}), never a second capture.
 * The tap keeps a full-resolution copy of the screen (capture thread, only the changed rectangles). {@link #pump} runs
 * on the sender thread: at most {@link #FPS} times a second per share it box-filters the changed area down to at most
 * {@link #MAX_SIDE} pixels a side, diffs it into {@link #TILE}-pixel tiles ({@link TileCodec}), adds
 * {@link #REFRESH_TILES} round-robin refresh tiles (loss recovery), JPEG-encodes them at {@link #QUALITY} and hands
 * them to the {@link Link}, paced by a per-stream byte budget below the relay's.
 *
 * <p>Privacy is enforced here, at the source: a demand for a hologram known to be PRIVATE never starts a share, and
 * the privacy is read again before every frame and every tile; the moment it is PRIVATE (or the share was stopped) no
 * further tile leaves, the share's pixels are dropped, and only a content-free CLEAR is sent.
 *
 * <p>Threads: {@link #onDemand}, {@link #onEnded}, {@link #stopAll} on the client thread; {@link #tap}'s sink from the
 * capture thread; {@link #pump} from one sender thread. Any exception in a share ends that share (logged), never the
 * game.
 */
public final class StreamSender {
	public static final int FPS = 10;
	public static final long FRAME_NANOS = TimeUnit.SECONDS.toNanos(1) / FPS;
	/**
	 * With nothing changed, a refresh-only frame (loss recovery + SIZE keepalive) this often: the full rate, so a lost
	 * tile heals within one refresh cycle (tiles / 8 frames: 3 s at 1280x720).
	 */
	public static final long IDLE_FRAME_NANOS = FRAME_NANOS;
	/** SIZE is re-sent at least this often (a lost SIZE must not blank the stream). */
	public static final long SIZE_EVERY_NANOS = TimeUnit.SECONDS.toNanos(1);
	/** Shared frames are box-filtered down to at most this many pixels a side (aspect kept). */
	public static final int MAX_SIDE = 1280;
	public static final int TILE = 64;
	public static final int QUALITY = 70;
	public static final int REFRESH_TILES = 8;
	/** Owner-side budget over UDP, under the relay's default 2000 kbps per stream (bytes/s, 1 s deep). */
	public static final int UDP_BYTES_PER_SECOND = 200_000;
	/** Owner-side budget over the game-connection fallback, under the relay's 150 KB/s (bytes/s, 1 s deep). */
	public static final int FALLBACK_BYTES_PER_SECOND = 120_000;
	/** Don't start encoding a tile with less budget than this left (it would most likely be dropped). */
	static final int MIN_TILE_BUDGET = 512;

	private static final Logger LOGGER = LogUtils.getLogger();

	/** Where encoded messages go: UDP (token appended, split into datagrams) or the payload fallback. Thread-safe. */
	public interface Link {
		/** Whether the UDP path is up (decides the budget). */
		boolean udp();

		/** Sends one TILE / SIZE / CLEAR message ({@code h.part/parts} are filled in by the link). */
		void send(StreamHeader h, byte[] body);
	}

	private final Link link;
	private final TileCodec.Jpeg jpeg;
	private final Function<UUID, @Nullable Privacy> privacyOf;
	private final Function<UUID, @Nullable ScreenTexture> ownTexture;
	private final LongSupplier clock;
	private final Map<UUID, Share> shares = new ConcurrentHashMap<>();

	/**
	 * @param privacyOf the hologram's current privacy as this client knows it (null = unknown: nothing is sent);
	 *                  called from the sender thread, so it must be thread-safe
	 * @param ownTexture the hologram's own live texture, used once to seed a new share (client thread)
	 */
	public StreamSender(final Link link, final TileCodec.Jpeg jpeg, final Function<UUID, @Nullable Privacy> privacyOf,
			final Function<UUID, @Nullable ScreenTexture> ownTexture, final LongSupplier clock) {
		this.link = link;
		this.jpeg = jpeg;
		this.privacyOf = privacyOf;
		this.ownTexture = ownTexture;
		this.clock = clock;
	}

	// ---- control (client thread) ----

	/** Server: start (wanted) or stop sharing a hologram under {@code streamId}. */
	public void onDemand(final StreamDemand d) {
		UUID id = d.hologramId();
		Share cur = shares.get(id);
		if (!d.wanted()) {
			if (cur != null && cur.streamId == d.streamId()) {
				stop(cur, false);
			}
			return;
		}
		if (cur != null) {
			if (cur.streamId == d.streamId()) {
				return;
			}
			stop(cur, false); // a restart always comes with a new stream id
		}
		Privacy p = privacyOf.apply(id);
		if (p != null && p.level() == Privacy.Level.PRIVATE) {
			LOGGER.debug("[office] Not sharing private hologram {}", id);
			return;
		}
		Share s = new Share(id, d.streamId(), clock.getAsLong());
		shares.put(id, s);
		seed(s);
	}

	/** Server: the stream is over. */
	public void onEnded(final int streamId) {
		for (Share s : shares.values()) {
			if (s.streamId == streamId) {
				stop(s, false);
			}
		}
	}

	/** Disconnect / shutdown: stop every share. */
	public void stopAll() {
		for (Share s : shares.values()) {
			stop(s, false);
		}
	}

	public boolean isSharing(final UUID hologramId) {
		return shares.containsKey(hologramId);
	}

	public int shareCount() {
		return shares.size();
	}

	/** The capture tap for a hologram while it is being shared (null otherwise). Called from the capture thread. */
	public @Nullable FrameSink tap(final UUID hologramId) {
		return shares.get(hologramId);
	}

	private void seed(final Share s) {
		ScreenTexture t;
		try {
			t = ownTexture.apply(s.hologramId);
		} catch (RuntimeException e) {
			return;
		}
		if (t == null) {
			return;
		}
		int w = t.width();
		int h = t.height();
		if (w <= 0 || h <= 0) {
			return; // nothing captured yet: the source's first full frame seeds the share
		}
		ByteBuffer snap = t.snapshot();
		if (snap.capacity() == w * h * 4) {
			s.full(w, h, snap);
		}
	}

	private void stop(final Share s, final boolean sendClear) {
		s.stopped = true;
		shares.remove(s.hologramId, s);
		s.dropPixels();
		if (sendClear) {
			try {
				link.send(new StreamHeader(StreamHeader.TYPE_CLEAR, s.streamId, s.frameSeq, 0, 0, 0, 1), new byte[0]);
			} catch (RuntimeException e) {
				LOGGER.debug("[office] CLEAR for stream {} not sent: {}", s.streamId, e.toString());
			}
		}
	}

	private boolean mayShare(final Share s) {
		if (s.stopped) {
			return false;
		}
		Privacy p = privacyOf.apply(s.hologramId);
		return p != null && p.level() != Privacy.Level.PRIVATE;
	}

	private boolean knownPrivate(final UUID id) {
		Privacy p = privacyOf.apply(id);
		return p != null && p.level() == Privacy.Level.PRIVATE;
	}

	// ---- sender thread ----

	/** Sends a frame for every share that is due. Sender thread only. */
	public void pump() {
		long now = clock.getAsLong();
		for (Iterator<Share> it = shares.values().iterator(); it.hasNext();) {
			Share s = it.next();
			try {
				frame(s, now);
			} catch (Throwable t) {
				LOGGER.warn("[office] Screen share of hologram {} failed; ending it", s.hologramId, t);
				stop(s, false);
			}
		}
	}

	private void frame(final Share s, final long now) {
		if (knownPrivate(s.hologramId)) {
			stop(s, true);
			return;
		}
		if (!mayShare(s) || now - s.lastFrame < FRAME_NANOS) {
			return;
		}
		boolean idleDue = now - s.lastFrame >= IDLE_FRAME_NANOS;
		if (!s.prepare(idleDue)) {
			return;
		}
		TileFrame f = s.codec.frame(s.dw, s.dh, ByteBuffer.wrap(s.down));
		int seq = s.frameSeq++;
		s.lastFrame = now;
		s.refill(now, link.udp() ? UDP_BYTES_PER_SECOND : FALLBACK_BYTES_PER_SECOND);
		if (f.sizeChanged()) {
			s.owed.clear();
		}
		if (!mayShare(s)) {
			stopIfPrivate(s);
			return;
		}
		if (f.sizeChanged() || now - s.lastSize >= SIZE_EVERY_NANOS) {
			byte[] body = ByteBuffer.allocate(6).putShort((short) s.dw).putShort((short) s.dh).putShort((short) TILE).array();
			link.send(new StreamHeader(StreamHeader.TYPE_SIZE, s.streamId, seq, 0, 0, 0, 1), body);
			s.tokens -= cost(body.length);
			s.lastSize = now;
		}
		// owed (budget-deferred changed tiles) first, then this frame's changes, then refresh tiles
		Map<Long, TileCodec.Tile> mandatory = new LinkedHashMap<>();
		for (long key : s.owed) {
			TileCodec.Tile t = s.extract(tx(key), ty(key));
			if (t.w() > 0 && t.h() > 0) {
				mandatory.put(key, t);
			}
		}
		for (TileCodec.Tile t : f.tiles()) {
			mandatory.put(key(t.tx(), t.ty()), t);
		}
		List<TileCodec.Tile> refresh = new ArrayList<>();
		for (TileCodec.Tile t : s.codec.refresh(REFRESH_TILES)) {
			if (!mandatory.containsKey(key(t.tx(), t.ty()))) {
				refresh.add(t);
			}
		}
		Set<Long> stillOwed = new LinkedHashSet<>();
		boolean out = false;
		for (Map.Entry<Long, TileCodec.Tile> e : mandatory.entrySet()) {
			if (out || s.tokens < MIN_TILE_BUDGET) {
				out = true;
				stillOwed.add(e.getKey());
				continue;
			}
			if (!sendTile(s, seq, e.getValue())) {
				if (!mayShare(s)) {
					stopIfPrivate(s);
					return;
				}
				out = true;
				stillOwed.add(e.getKey());
			}
		}
		s.owed.clear();
		s.owed.addAll(stillOwed);
		if (!out) {
			for (TileCodec.Tile t : refresh) {
				if (s.tokens < MIN_TILE_BUDGET || !sendTile(s, seq, t)) {
					break;
				}
			}
		}
		if (!mayShare(s)) {
			stopIfPrivate(s);
		}
	}

	private void stopIfPrivate(final Share s) {
		if (!s.stopped && knownPrivate(s.hologramId)) {
			stop(s, true);
		}
	}

	/** Encodes and sends one tile if the share may still send and the budget allows; false otherwise. */
	private boolean sendTile(final Share s, final int seq, final TileCodec.Tile t) {
		if (!mayShare(s)) {
			return false;
		}
		byte[] body = jpeg.encode(t, QUALITY);
		int c = cost(body.length);
		if (c > s.tokens) {
			return false;
		}
		if (!mayShare(s)) {
			return false; // privacy flipped while encoding: this tile never leaves
		}
		link.send(new StreamHeader(StreamHeader.TYPE_TILE, s.streamId, seq, t.tx(), t.ty(), 0, 1), body);
		s.tokens -= c;
		s.tilesSent++;
		return true;
	}

	/** Bytes a message costs against the relay's budget (worst case: UDP datagrams with the token). */
	static int cost(final int bodyLen) {
		int room = Packetizer.MAX_DATAGRAM - StreamHeader.HEADER_BYTES - Packetizer.TOKEN_BYTES;
		int parts = Math.max(1, (bodyLen + room - 1) / room);
		return bodyLen + parts * (StreamHeader.HEADER_BYTES + Packetizer.TOKEN_BYTES);
	}

	static long key(final int tx, final int ty) {
		return ((long) tx << 32) | (ty & 0xFFFFFFFFL);
	}

	static int tx(final long key) {
		return (int) (key >>> 32);
	}

	static int ty(final long key) {
		return (int) key;
	}

	/** Tiles sent so far by the share of a hologram (tests); -1 when not sharing. */
	long tilesSent(final UUID hologramId) {
		Share s = shares.get(hologramId);
		return s == null ? -1 : s.tilesSent;
	}

	/** Shared frame size for a {@code w x h} source: longest side at most {@link #MAX_SIDE}, aspect kept. */
	public static int[] targetSize(final int w, final int h) {
		double scale = Math.min(1.0, Math.min((double) MAX_SIDE / w, (double) MAX_SIDE / h));
		if (scale >= 1.0) {
			return new int[] { w, h };
		}
		return new int[] { Math.max(1, (int) Math.round(w * scale)), Math.max(1, (int) Math.round(h * scale)) };
	}

	/**
	 * Box filter: every destination pixel in {@code [dx0,dx1) x [dy0,dy1)} becomes the average of the source pixels it
	 * covers (alpha forced opaque). Same size = plain copy of that area.
	 */
	static void downscale(final byte[] src, final int sw, final int sh, final byte[] dst, final int dw, final int dh,
			final int dx0, final int dy0, final int dx1, final int dy1) {
		if (sw == dw && sh == dh) {
			int rowBytes = (dx1 - dx0) * 4;
			for (int y = dy0; y < dy1; y++) {
				System.arraycopy(src, (y * sw + dx0) * 4, dst, (y * dw + dx0) * 4, rowBytes);
			}
			return;
		}
		for (int dy = dy0; dy < dy1; dy++) {
			int sy0 = (int) ((long) dy * sh / dh);
			int sy1 = Math.max(sy0 + 1, (int) ((long) (dy + 1) * sh / dh));
			for (int dx = dx0; dx < dx1; dx++) {
				int sx0 = (int) ((long) dx * sw / dw);
				int sx1 = Math.max(sx0 + 1, (int) ((long) (dx + 1) * sw / dw));
				int r = 0;
				int g = 0;
				int b = 0;
				for (int sy = sy0; sy < sy1; sy++) {
					int i = (sy * sw + sx0) * 4;
					for (int sx = sx0; sx < sx1; sx++, i += 4) {
						r += src[i] & 0xFF;
						g += src[i + 1] & 0xFF;
						b += src[i + 2] & 0xFF;
					}
				}
				int n = (sy1 - sy0) * (sx1 - sx0);
				int o = (dy * dw + dx) * 4;
				dst[o] = (byte) (r / n);
				dst[o + 1] = (byte) (g / n);
				dst[o + 2] = (byte) (b / n);
				dst[o + 3] = (byte) 0xFF;
			}
		}
	}

	/** One shared hologram: the capture tap (any thread) plus the sender-thread encoder state. */
	private static final class Share implements FrameSink {
		final UUID hologramId;
		final int streamId;
		final ReentrantLock lock = new ReentrantLock();
		volatile boolean stopped;

		// full-resolution copy of the screen (guarded by lock)
		byte @Nullable [] canvas;
		int sw;
		int sh;
		boolean dirtyAll;
		int dirtyX0 = Integer.MAX_VALUE;
		int dirtyY0 = Integer.MAX_VALUE;
		int dirtyX1;
		int dirtyY1;

		// sender thread only
		byte[] down = new byte[0];
		int dw;
		int dh;
		final TileCodec codec = new TileCodec(TILE);
		int frameSeq = 1;
		long lastFrame;
		long lastSize;
		long lastRefill;
		double tokens;
		final Set<Long> owed = new LinkedHashSet<>();
		long tilesSent;

		Share(final UUID hologramId, final int streamId, final long now) {
			this.hologramId = hologramId;
			this.streamId = streamId;
			this.lastFrame = now - IDLE_FRAME_NANOS;
			this.lastSize = now - SIZE_EVERY_NANOS;
			this.lastRefill = now;
			this.tokens = -1;
		}

		void refill(final long now, final int perSecond) {
			double dt = Math.max(0, now - lastRefill) / 1e9;
			lastRefill = now;
			// starts full, like the relay's bucket
			tokens = tokens < 0 ? perSecond : Math.min(perSecond, tokens + dt * perSecond);
		}

		@Override
		public void full(final int w, final int h, final ByteBuffer rgba) {
			if (stopped || w <= 0 || h <= 0 || w > TileCodec.MAX_DIMENSION || h > TileCodec.MAX_DIMENSION
					|| rgba.remaining() < (long) w * h * 4) {
				return;
			}
			lock.lock();
			try {
				if (stopped) {
					return;
				}
				byte[] c = canvas;
				if (c == null || w != sw || h != sh) {
					c = new byte[w * h * 4];
					canvas = c;
					sw = w;
					sh = h;
				}
				rgba.get(rgba.position(), c, 0, w * h * 4);
				dirtyAll = true;
			} finally {
				lock.unlock();
			}
		}

		@Override
		public void patch(final int x, final int y, final int w, final int h, final ByteBuffer rgba) {
			if (stopped || w <= 0 || h <= 0 || rgba.remaining() < (long) w * h * 4) {
				return;
			}
			lock.lock();
			try {
				byte[] c = canvas;
				if (stopped || c == null) {
					return;
				}
				int x0 = Math.max(0, x);
				int y0 = Math.max(0, y);
				int x1 = Math.min(sw, x + w);
				int y1 = Math.min(sh, y + h);
				if (x0 >= x1 || y0 >= y1) {
					return;
				}
				int rowBytes = (x1 - x0) * 4;
				for (int row = y0; row < y1; row++) {
					rgba.get(rgba.position() + ((row - y) * w + (x0 - x)) * 4, c, (row * sw + x0) * 4, rowBytes);
				}
				dirtyX0 = Math.min(dirtyX0, x0);
				dirtyY0 = Math.min(dirtyY0, y0);
				dirtyX1 = Math.max(dirtyX1, x1);
				dirtyY1 = Math.max(dirtyY1, y1);
			} finally {
				lock.unlock();
			}
		}

		@Override
		public void ended(final String reason) {
			// the source stopped; the share just has nothing new to send (refresh frames keep the last picture)
		}

		/**
		 * Sender thread: brings {@link #down} up to date with the canvas. False when there is nothing to send this
		 * frame (no picture yet, or nothing changed and no refresh/owed work is due).
		 */
		boolean prepare(final boolean idleDue) {
			lock.lock();
			try {
				byte[] c = canvas;
				if (stopped || c == null) {
					return false;
				}
				boolean dirty = dirtyAll || dirtyX0 < dirtyX1;
				if (!dirty && owed.isEmpty() && !idleDue) {
					return false;
				}
				int[] t = targetSize(sw, sh);
				boolean all = dirtyAll;
				if (t[0] != dw || t[1] != dh || down.length != t[0] * t[1] * 4) {
					dw = t[0];
					dh = t[1];
					down = new byte[dw * dh * 4];
					all = true;
				}
				if (all) {
					downscale(c, sw, sh, down, dw, dh, 0, 0, dw, dh);
				} else if (dirty) {
					// destination pixels whose source boxes touch the dirty rect, one pixel of slack each side
					int x0 = Math.max(0, (int) ((long) dirtyX0 * dw / sw) - 1);
					int y0 = Math.max(0, (int) ((long) dirtyY0 * dh / sh) - 1);
					int x1 = Math.min(dw, (int) (((long) dirtyX1 * dw + sw - 1) / sw) + 1);
					int y1 = Math.min(dh, (int) (((long) dirtyY1 * dh + sh - 1) / sh) + 1);
					downscale(c, sw, sh, down, dw, dh, x0, y0, x1, y1);
				}
				dirtyAll = false;
				dirtyX0 = Integer.MAX_VALUE;
				dirtyY0 = Integer.MAX_VALUE;
				dirtyX1 = 0;
				dirtyY1 = 0;
				return true;
			} finally {
				lock.unlock();
			}
		}

		/** Sender thread: the current tile at (tx, ty) of {@link #down}. */
		TileCodec.Tile extract(final int tx, final int ty) {
			int x0 = tx * TILE;
			int y0 = ty * TILE;
			int tw = Math.max(0, Math.min(TILE, dw - x0));
			int th = Math.max(0, Math.min(TILE, dh - y0));
			byte[] out = new byte[tw * th * 4];
			for (int row = 0; row < th; row++) {
				System.arraycopy(down, ((y0 + row) * dw + x0) * 4, out, row * tw * 4, tw * 4);
			}
			return new TileCodec.Tile(tx, ty, tw, th, ByteBuffer.wrap(out));
		}

		void dropPixels() {
			lock.lock();
			try {
				canvas = null;
				sw = 0;
				sh = 0;
			} finally {
				lock.unlock();
			}
		}
	}
}
