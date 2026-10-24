package local.carter.office.client.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import local.carter.office.OfficeMod;
import local.carter.office.client.capture.FrameSink;
import local.carter.office.client.render.ScreenTextureMath.Rect;

import net.minecraft.resources.Identifier;

import net.fabricmc.loader.api.FabricLoader;

/**
 * A live screen as a GPU texture: the {@link FrameSink} a {@link local.carter.office.client.capture.CaptureSource}
 * feeds, drawn by {@link HologramRenderer} under {@link #id()}.
 *
 * <p>Threads: {@link #full}, {@link #patch} and {@link #ended} may be called from any thread (a capture thread). They
 * only write into a CPU copy of the newest frame (plus its mip levels) and note what changed; nothing touches the GPU.
 * {@link #prepareForRender()} runs on the render thread and uploads whatever changed since the last call, so a burst of
 * frames between two rendered frames costs one upload ("newest wins") and overlapping patches merge. The render thread
 * never waits for a producer: if a capture thread is mid-frame, that render frame skips the upload and keeps drawing
 * the previous picture (the change is uploaded next frame).
 *
 * <p>Memory is bounded: one CPU frame (the "pending full frame", reused across frames of the same size) + ~1/3 for mips,
 * at most {@link #MAX_PENDING_RECTS} pending patch rectangles (overflow, or patches covering more than half the frame,
 * become one whole-texture upload from the CPU copy), and one staging buffer no bigger than a frame.
 *
 * <p>Quality: the texture has a mip chain ({@link ScreenTextureMath#mipLevels}) built on the capture thread with a 2x2
 * box filter — only under the changed rectangles — and is sampled trilinearly, so a 1920x1080 screen seen from 6-15
 * blocks doesn't shimmer. The render thread never downsamples.
 *
 * <p>Ids: use {@link #forHologram} for a hologram's screen. Its id ({@code office:screen/<hologramUuid>}) is stable, so
 * the render-type cache, which keeps one entry per texture id forever, doesn't grow per reconnect. {@link #create} makes
 * a unique id every call. Only one ScreenTexture per id may be live (holding a frame) at a time: a second one that
 * receives a frame while the first is still live is a bug. In a development environment it fails loudly
 * ({@link IllegalStateException} from {@link #full}); in production the newer one wins and the older one is
 * <em>superseded</em>: it stops drawing and never touches the GPU again until it is {@link #clear()}ed.
 */
public class ScreenTexture implements FrameSink {
	/** Pending patch rectangles kept per texture before falling back to one whole-texture upload. */
	public static final int MAX_PENDING_RECTS = 32;
	/** Largest accepted frame side (pixels); bigger frames are ignored. */
	public static final int MAX_SIDE = 8192;
	/** After this many render frames in a row found a producer mid-frame, wait for it once (no upload starvation). */
	public static final int MAX_MISSED_LOCKS = 8;

	private static final Logger LOGGER = LogUtils.getLogger();
	private static final AtomicInteger SERIAL = new AtomicInteger();
	/** The live (frame-holding) texture of each id. */
	private static final Map<Identifier, ScreenTexture> LIVE = new ConcurrentHashMap<>();
	private static volatile @Nullable Boolean strictIds;

	/** Where uploads go. The real one is {@link ScreenGpuTexture}; tests use a recording fake. */
	public interface Gpu {
		/** (Re)creates the texture at this size with {@code levels} mip levels; old contents are gone. Render thread. */
		void allocate(int width, int height, int levels);

		/** Writes a tightly packed RGBA rect ({@code rgba.remaining() == w * h * 4}) into a mip level. Render thread. */
		void write(int level, int x, int y, int w, int h, ByteBuffer rgba);

		/** Frees the texture (a later {@link #allocate} may create it again). Render thread. */
		void free();

		/** Whether the current thread is the render thread ({@link #free()} may be called directly). */
		default boolean canFreeNow() {
			return true;
		}

		/** Runs {@code task} on the render thread soon (used to free the texture after an off-thread clear). */
		default void runOnRenderThread(final Runnable task) {
			task.run();
		}
	}

	private final Identifier id;
	private final Gpu gpu;
	private final ReentrantLock lock = new ReentrantLock();
	private volatile @Nullable Runnable midFullFrameHook;

	// ---- CPU side (guarded by lock) ----
	private ByteBuffer @Nullable [] levels;
	private int[] levelW = new int[0];
	private int[] levelH = new int[0];
	private volatile int frameW;
	private volatile int frameH;
	private final List<Rect> dirty = new ArrayList<>();
	private boolean dirtyAll;
	private volatile boolean needsFull = true;
	private volatile @Nullable String endedReason;
	private long changedNanos; // FrameStats: when the oldest not-yet-uploaded change arrived (0 = none)

	// ---- GPU side (render thread, also guarded by lock) ----
	private int gpuW;
	private int gpuH;
	private volatile boolean gpuHasFrame;
	private boolean freePending;
	private @Nullable ByteBuffer staging;
	private volatile long uploads;
	private int missedLocks;

	// ---- id ownership ----
	private boolean claimed; // guarded by lock
	private volatile boolean superseded;

	public ScreenTexture(final Identifier id, final Gpu gpu) {
		this.id = id;
		this.gpu = gpu;
	}

	/**
	 * A texture backed by the real GPU with a <em>unique</em> id {@code office:screen/<name>-<n>} ({@code name}:
	 * [a-z0-9/._-]). For a hologram's screen use {@link #forHologram} instead (stable id, no render-type growth).
	 */
	public static ScreenTexture create(final String name) {
		Identifier id = OfficeMod.id("screen/" + name + "-" + SERIAL.incrementAndGet());
		return new ScreenTexture(id, new ScreenGpuTexture(id));
	}

	/**
	 * The texture for one hologram, id {@link #idFor(UUID)} (the same every time). Keep at most one live per hologram:
	 * reuse it across reconnects, or {@link #clear()} the old one before feeding a new one.
	 */
	public static ScreenTexture forHologram(final UUID hologramId) {
		Identifier id = idFor(hologramId);
		return new ScreenTexture(id, new ScreenGpuTexture(id));
	}

	/** Tests: whether a second live texture for an id throws (true) or supersedes the older one (false). */
	public static void setStrictIdsForTest(final boolean strict) {
		strictIds = strict;
	}

	private static boolean strictIds() {
		Boolean s = strictIds;
		if (s == null) {
			try {
				s = FabricLoader.getInstance().isDevelopmentEnvironment();
			} catch (Throwable t) {
				s = false;
			}
			strictIds = s;
		}
		return s;
	}

	/** Whether a newer texture with the same id took over (this one no longer draws; {@link #clear()} resets it). */
	public boolean isSuperseded() {
		return superseded;
	}

	/** Caller holds {@code lock}: makes this the live texture of its id (first frame since construction/clear). */
	private void claimId() {
		if (claimed) {
			return;
		}
		ScreenTexture prev = LIVE.putIfAbsent(id, this);
		if (prev != null && prev != this) {
			if (strictIds()) {
				throw new IllegalStateException("Two live ScreenTextures for " + id
						+ ": clear the old one (or reuse it) before feeding a new one");
			}
			LOGGER.warn("Two live ScreenTextures for {}: the newer one wins", id);
			prev.superseded = true;
			LIVE.put(id, this);
		}
		claimed = true;
		superseded = false;
	}

	/** {@code office:screen/<hologramUuid>}: the stable texture id of a hologram's screen. */
	public static Identifier idFor(final UUID hologramId) {
		return OfficeMod.id("screen/" + hologramId);
	}

	public Identifier id() {
		return id;
	}

	/** Width of the newest frame, 0 before the first frame or after {@link #clear()}. */
	public int width() {
		return frameW;
	}

	public int height() {
		return frameH;
	}

	/** A frame has arrived (it is uploaded by the next {@link #prepareForRender()}). */
	public boolean hasFrame() {
		return frameW > 0 && !superseded;
	}

	/** The source should send a full frame (nothing received yet, a patch could not apply, or after a clear). */
	public boolean needsFullFrame() {
		return needsFull;
	}

	/** Why the source ended, or null while it is (or may still be) delivering. */
	public @Nullable String endedReason() {
		return endedReason;
	}

	/** Number of GPU writes so far (for tests and debugging). */
	public long uploadCount() {
		return uploads;
	}

	/** Tests only: runs inside {@link #full} after the copy, before the mips are built, while the lock is held. */
	public void setMidFullFrameHookForTest(final @Nullable Runnable hook) {
		midFullFrameHook = hook;
	}

	// ---- FrameSink (any thread) ----

	@Override
	public void full(final int w, final int h, final ByteBuffer rgba) {
		if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE || rgba.remaining() < (long) w * h * 4) {
			needsFull = true;
			return;
		}
		if (superseded) {
			return;
		}
		long arrived = FrameStats.recording() ? System.nanoTime() : 0;
		lock.lock();
		try {
			if (changedNanos == 0) {
				changedNanos = arrived;
			}
			claimId();
			if (levels == null || w != frameW || h != frameH) {
				allocateCpu(w, h);
			}
			ByteBuffer[] lv = levels;
			lv[0].put(0, rgba, rgba.position(), w * h * 4);
			Runnable hook = midFullFrameHook;
			if (hook != null) {
				hook.run();
			}
			for (int k = 1; k < lv.length; k++) {
				ScreenTextureMath.downsample(lv[k - 1], levelW[k - 1], levelH[k - 1], lv[k], levelW[k], levelH[k], new Rect(0, 0, levelW[k], levelH[k]));
			}
			dirty.clear();
			dirtyAll = true;
			needsFull = false;
			endedReason = null;
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void patch(final int x, final int y, final int w, final int h, final ByteBuffer rgba) {
		if (w <= 0 || h <= 0) {
			return;
		}
		if (rgba.remaining() < (long) w * h * 4) {
			needsFull = true;
			return;
		}
		if (superseded) {
			return;
		}
		long arrived = FrameStats.recording() ? System.nanoTime() : 0;
		lock.lock();
		try {
			ByteBuffer[] lv = levels;
			if (lv == null) {
				needsFull = true; // nothing to patch yet
				return;
			}
			if (changedNanos == 0) {
				changedNanos = arrived;
			}
			Rect r = Rect.clip(x, y, w, h, frameW, frameH);
			if (r == null) {
				return;
			}
			int rowBytes = r.w() * 4;
			for (int row = 0; row < r.h(); row++) {
				int src = rgba.position() + ((r.y() - y + row) * w + (r.x() - x)) * 4;
				int dst = ((r.y() + row) * frameW + r.x()) * 4;
				lv[0].put(dst, rgba, src, rowBytes);
			}
			for (int k = 1; k < lv.length; k++) {
				Rect rk = r.atLevel(k, levelW[k], levelH[k]);
				if (rk.isEmpty()) {
					break;
				}
				ScreenTextureMath.downsample(lv[k - 1], levelW[k - 1], levelH[k - 1], lv[k], levelW[k], levelH[k], rk);
			}
			markDirty(r);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void ended(final String reason) {
		endedReason = reason == null ? "ended" : reason;
	}

	private void markDirty(final Rect r) {
		if (dirtyAll) {
			return;
		}
		for (Rect d : dirty) {
			if (d.contains(r)) {
				return;
			}
		}
		dirty.removeIf(r::contains);
		dirty.add(r);
		long area = 0;
		for (Rect d : dirty) {
			area += d.area();
		}
		if (dirty.size() > MAX_PENDING_RECTS || area * 2 > (long) frameW * frameH) {
			dirty.clear();
			dirtyAll = true;
		}
	}

	private void allocateCpu(final int w, final int h) {
		int n = ScreenTextureMath.mipLevels(w, h);
		ByteBuffer[] lv = new ByteBuffer[n];
		levelW = new int[n];
		levelH = new int[n];
		for (int k = 0; k < n; k++) {
			levelW[k] = ScreenTextureMath.levelSize(w, k);
			levelH[k] = ScreenTextureMath.levelSize(h, k);
			// direct: the GPU backends read straight from native memory
			lv[k] = ByteBuffer.allocateDirect(levelW[k] * levelH[k] * 4).order(ByteOrder.nativeOrder());
		}
		levels = lv;
		frameW = w;
		frameH = h;
		staging = null;
	}

	// ---- render thread ----

	/**
	 * Render thread: uploads what changed since the last call (reallocating the GPU texture if the frame size changed).
	 * Returns true if the texture holds a frame and may be drawn. Never waits for a capture thread: if one is writing,
	 * nothing is uploaded this time and the previous picture stays.
	 */
	public boolean prepareForRender() {
		if (superseded) {
			return false; // a newer texture owns this id (and its texture-manager slot): never touch the GPU
		}
		if (!lock.tryLock()) {
			if (++missedLocks < MAX_MISSED_LOCKS) {
				return gpuHasFrame;
			}
			lock.lock(); // a producer kept hitting our frames: wait for it once so uploads can't starve
		}
		missedLocks = 0;
		try {
			if (superseded) {
				return false;
			}
			if (freePending) {
				freeGpu();
			}
			ByteBuffer[] lv = levels;
			if (lv == null) {
				return false;
			}
			if (gpuW != frameW || gpuH != frameH) {
				gpu.allocate(frameW, frameH, lv.length);
				gpuW = frameW;
				gpuH = frameH;
				gpuHasFrame = false;
				dirtyAll = true;
			}
			if (dirtyAll) {
				for (int k = 0; k < lv.length; k++) {
					write(k, 0, 0, levelW[k], levelH[k], lv[k].slice(0, levelW[k] * levelH[k] * 4));
				}
				dirtyAll = false;
				dirty.clear();
				gpuHasFrame = true;
				uploadedChange();
			} else if (!dirty.isEmpty()) {
				for (Rect r : dirty) {
					for (int k = 0; k < lv.length; k++) {
						Rect rk = k == 0 ? r : r.atLevel(k, levelW[k], levelH[k]);
						if (rk.isEmpty()) {
							break;
						}
						write(k, rk.x(), rk.y(), rk.w(), rk.h(), pack(lv[k], levelW[k], rk));
					}
				}
				dirty.clear();
				uploadedChange();
			}
			return gpuHasFrame;
		} finally {
			lock.unlock();
		}
	}

	/** Under the lock, render thread: the pending change is on the GPU now. */
	private void uploadedChange() {
		if (changedNanos != 0) {
			FrameStats.uploaded(changedNanos);
			changedNanos = 0;
		}
	}

	private void write(final int level, final int x, final int y, final int w, final int h, final ByteBuffer data) {
		gpu.write(level, x, y, w, h, data);
		uploads++;
	}

	/** Copies rect {@code r} of a level (row length {@code lw}) into the staging buffer, tightly packed. */
	private ByteBuffer pack(final ByteBuffer level, final int lw, final Rect r) {
		int bytes = r.w() * r.h() * 4;
		ByteBuffer s = staging;
		if (s == null || s.capacity() < bytes) {
			s = ByteBuffer.allocateDirect(Math.max(bytes, Math.min(frameW * frameH * 4, 256 * 1024))).order(ByteOrder.nativeOrder());
			staging = s;
		}
		int rowBytes = r.w() * 4;
		for (int row = 0; row < r.h(); row++) {
			s.put(row * rowBytes, level, ((r.y() + row) * lw + r.x()) * 4, rowBytes);
		}
		return s.slice(0, bytes);
	}

	/** Copy of the newest frame (level 0, tightly packed RGBA), or an empty buffer if none. For tests and debugging. */
	public ByteBuffer snapshot() {
		lock.lock();
		try {
			ByteBuffer[] lv = levels;
			if (lv == null) {
				return ByteBuffer.allocate(0);
			}
			ByteBuffer copy = ByteBuffer.allocate(frameW * frameH * 4);
			copy.put(0, lv[0], 0, copy.capacity());
			return copy;
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Drops the frame, every pending patch and the CPU buffers, and frees the GPU texture: immediately on the render
	 * thread; from any other thread the free is queued onto the render thread (or happens at the next
	 * {@link #prepareForRender()}, whichever comes first). The texture can be fed again afterwards.
	 */
	public void clear() {
		lock.lock();
		try {
			levels = null;
			levelW = new int[0];
			levelH = new int[0];
			frameW = 0;
			frameH = 0;
			dirty.clear();
			dirtyAll = false;
			changedNanos = 0;
			staging = null;
			needsFull = true;
			if (claimed) {
				LIVE.remove(id, this); // never removes a newer owner's claim
				claimed = false;
			}
			superseded = false;
			if (gpuW != 0 || gpuHasFrame || freePending) {
				if (gpu.canFreeNow()) {
					freeGpu();
				} else if (!freePending) {
					freePending = true;
					gpu.runOnRenderThread(this::freeIfPending);
				}
			}
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Render thread: the queued half of an off-thread {@link #clear()}. A no-op if the free already happened. Takes the
	 * lock blocking (a one-off wait of at most one producer section, only after a clear).
	 */
	private void freeIfPending() {
		lock.lock();
		try {
			if (freePending) {
				freeGpu();
			}
		} finally {
			lock.unlock();
		}
	}

	private void freeGpu() {
		gpu.free();
		gpuW = 0;
		gpuH = 0;
		gpuHasFrame = false;
		freePending = false;
	}
}
