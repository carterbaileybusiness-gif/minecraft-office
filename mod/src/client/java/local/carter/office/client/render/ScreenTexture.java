package local.carter.office.client.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;

import local.carter.office.OfficeMod;
import local.carter.office.client.capture.FrameSink;
import local.carter.office.client.render.ScreenTextureMath.Rect;

import net.minecraft.resources.Identifier;

/**
 * A live screen as a GPU texture: the {@link FrameSink} a {@link local.carter.office.client.capture.CaptureSource}
 * feeds, drawn by {@link HologramRenderer} under {@link #id()}.
 *
 * <p>Threads: {@link #full}, {@link #patch} and {@link #ended} may be called from any thread (a capture thread). They
 * only write into a CPU copy of the newest frame (plus its mip levels) and note what changed; nothing touches the GPU.
 * {@link #prepareForRender()} runs on the render thread and uploads whatever changed since the last call, so a burst of
 * frames between two rendered frames costs one upload ("newest wins") and overlapping patches merge.
 *
 * <p>Memory is bounded: one CPU frame (the "pending full frame", reused across frames of the same size) + ~1/3 for mips,
 * at most {@link #MAX_PENDING_RECTS} pending patch rectangles (overflow, or patches covering more than half the frame,
 * become one whole-texture upload from the CPU copy), and one staging buffer no bigger than a frame.
 *
 * <p>Quality: the texture has a mip chain ({@link ScreenTextureMath#mipLevels}) built on the capture thread with a 2x2
 * box filter — only under the changed rectangles — and is sampled trilinearly, so a 1920x1080 screen seen from 6-15
 * blocks doesn't shimmer. The render thread never downsamples.
 */
public class ScreenTexture implements FrameSink {
	/** Pending patch rectangles kept per texture before falling back to one whole-texture upload. */
	public static final int MAX_PENDING_RECTS = 32;
	/** Largest accepted frame side (pixels); bigger frames are ignored. */
	public static final int MAX_SIDE = 8192;

	private static final AtomicInteger SERIAL = new AtomicInteger();

	/** Where uploads go. The real one is {@link ScreenGpuTexture}; tests use a recording fake. */
	public interface Gpu {
		/** (Re)creates the texture at this size with {@code levels} mip levels; old contents are gone. Render thread. */
		void allocate(int width, int height, int levels);

		/** Writes a tightly packed RGBA rect ({@code rgba.remaining() == w * h * 4}) into a mip level. Render thread. */
		void write(int level, int x, int y, int w, int h, ByteBuffer rgba);

		/** Frees the texture (a later {@link #allocate} may create it again). Render thread. */
		void free();

		/** Whether {@link #free()} may be called from the current thread. */
		default boolean canFreeNow() {
			return true;
		}
	}

	private final Identifier id;
	private final Gpu gpu;
	private final Object lock = new Object();

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

	// ---- GPU side (render thread, also guarded by lock) ----
	private int gpuW;
	private int gpuH;
	private boolean gpuHasFrame;
	private boolean freePending;
	private @Nullable ByteBuffer staging;
	private volatile long uploads;

	public ScreenTexture(final Identifier id, final Gpu gpu) {
		this.id = id;
		this.gpu = gpu;
	}

	/** A texture backed by the real GPU, id {@code office:screen/<name>-<n>} ({@code name}: [a-z0-9/._-]). */
	public static ScreenTexture create(final String name) {
		Identifier id = OfficeMod.id("screen/" + name + "-" + SERIAL.incrementAndGet());
		return new ScreenTexture(id, new ScreenGpuTexture(id));
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
		return frameW > 0;
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

	// ---- FrameSink (any thread) ----

	@Override
	public void full(final int w, final int h, final ByteBuffer rgba) {
		if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE || rgba.remaining() < (long) w * h * 4) {
			needsFull = true;
			return;
		}
		synchronized (lock) {
			if (levels == null || w != frameW || h != frameH) {
				allocateCpu(w, h);
			}
			ByteBuffer[] lv = levels;
			lv[0].put(0, rgba, rgba.position(), w * h * 4);
			for (int k = 1; k < lv.length; k++) {
				ScreenTextureMath.downsample(lv[k - 1], levelW[k - 1], levelH[k - 1], lv[k], levelW[k], levelH[k], new Rect(0, 0, levelW[k], levelH[k]));
			}
			dirty.clear();
			dirtyAll = true;
			needsFull = false;
			endedReason = null;
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
		synchronized (lock) {
			ByteBuffer[] lv = levels;
			if (lv == null) {
				needsFull = true; // nothing to patch yet
				return;
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
	 * Returns true if the texture holds a frame and may be drawn.
	 */
	public boolean prepareForRender() {
		synchronized (lock) {
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
			}
			return gpuHasFrame;
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
		synchronized (lock) {
			ByteBuffer[] lv = levels;
			if (lv == null) {
				return ByteBuffer.allocate(0);
			}
			ByteBuffer copy = ByteBuffer.allocate(frameW * frameH * 4);
			copy.put(0, lv[0], 0, copy.capacity());
			return copy;
		}
	}

	/**
	 * Drops the frame, every pending patch and the CPU buffers, and frees the GPU texture (immediately on the render
	 * thread, otherwise at the next {@link #prepareForRender()}). The texture can be fed again afterwards.
	 */
	public void clear() {
		synchronized (lock) {
			levels = null;
			levelW = new int[0];
			levelH = new int[0];
			frameW = 0;
			frameH = 0;
			dirty.clear();
			dirtyAll = false;
			staging = null;
			needsFull = true;
			if (gpuW != 0 || gpuHasFrame) {
				if (gpu.canFreeNow()) {
					freeGpu();
				} else {
					freePending = true;
				}
			}
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
