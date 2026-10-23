package local.carter.office.client.capture;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import local.carter.office.client.interact.HologramTracker;
import local.carter.office.client.platform.Platform;
import local.carter.office.client.render.HologramLook;
import local.carter.office.client.render.HologramPlacement;
import local.carter.office.client.render.HologramRenderer;
import local.carter.office.client.render.ScreenTexture;
import local.carter.office.client.render.ScreenTextureRegistry;
import local.carter.office.core.HologramGeometry.Frame;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Vec3d;
import local.carter.office.hologram.HologramBlockEntity;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * Feeds the local player's own loaded holograms from capture sources: one {@link CaptureSource} + {@link ScreenTexture}
 * per hologram, keyed by hologram id (texture id {@code office:screen/<hologramUuid>}, kept across restarts), restarted
 * when the hologram's {@link Target} changes and stopped when it unloads,
 * changes owner, or the player disconnects. Client thread only.
 *
 * <p>Default source: this computer's own capture ({@link Platform#newOwnSource}: the Mac helper with an adaptive rate,
 * {@link CaptureRate}: 5 fps while the hologram's front is on screen, 0 when not, 30 while zoomed in; or Windows'
 * {@link JavaDesktopSource}, which paces itself under {@link #MAX_FPS}). With
 * {@code -Doffice.testPattern=true} (dev override) every own hologram shows a {@link TestPatternSource} at 30 fps
 * instead. Without either, own holograms are not fed.
 *
 * <p>Sharing (Task 14): every source (Mac helper, Windows desktop capture, test pattern) feeds a {@link FanOutSink}:
 * the hologram's texture first, then the share tap ({@link #setShareTaps}) while friends are watching. A watched
 * hologram is captured at least {@link #SHARE_FPS} even when its owner isn't looking at it (adaptive-rate sources only;
 * a source that paces itself is left alone).
 */
public final class OwnScreens {
	public static final String TEST_PATTERN_PROPERTY = "office.testPattern";
	public static final int MAX_FPS = 30;
	/** Capture rate floor while a hologram is being shared (the shared stream's rate). */
	public static final int SHARE_FPS = 10;

	private static final Logger LOGGER = LogUtils.getLogger();
	/** The share tap of a hologram while it is being shared, else null (thread-safe; called from capture threads). */
	private static volatile Function<UUID, @Nullable FrameSink> shareTaps = id -> null;

	private static final Map<UUID, Feed> FEEDS = new HashMap<>();
	private static boolean enabled;
	private static Supplier<CaptureSource> factory = TestPatternSource::new;
	/** Whether {@link #factory}'s sources get {@link CaptureSource#setMaxFps} from visibility (real capture). */
	private static boolean adaptiveRate;

	private static final class Feed {
		final Target target;
		final CaptureSource source;
		final ScreenTexture texture;
		int fps;
		CaptureRate.Visibility visibility = new CaptureRate.Visibility();

		Feed(final Target target, final CaptureSource source, final ScreenTexture texture, final int fps) {
			this.target = target;
			this.source = source;
			this.texture = texture;
			this.fps = fps;
		}
	}

	private OwnScreens() {}

	public static void register() {
		if (Boolean.getBoolean(TEST_PATTERN_PROPERTY)) {
			enabled = true;
		} else if (Platform.current().newOwnSource() != null) {
			factory = () -> Platform.current().newOwnSource();
			// Mac helper: 5 / 0 / 30 fps from visibility. Windows' JavaDesktopSource paces itself under the MAX_FPS cap.
			adaptiveRate = !Platform.current().ownSourcePacesItself();
			enabled = true;
		}
		HologramLook.setOwnSourceProvider(OwnScreens::ownSource);
		ClientTickEvents.END_CLIENT_TICK.register(OwnScreens::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(OwnScreens::stopAll));
	}

	public static boolean enabled() {
		return enabled;
	}

	/** Turns feeding own holograms on or off (off stops every source and frees every texture at the next tick). */
	public static void setEnabled(final boolean on) {
		enabled = on;
	}

	/** The source used for each own hologram, at a fixed {@link #MAX_FPS}; running feeds restart with it. Client thread. */
	public static void setSourceFactory(final Supplier<CaptureSource> sourceFactory) {
		factory = sourceFactory;
		adaptiveRate = false;
		stopAll();
	}

	/** Installs the share taps (Task 14's sender). */
	public static void setShareTaps(final @Nullable Function<UUID, @Nullable FrameSink> taps) {
		shareTaps = taps == null ? id -> null : taps;
	}

	private static boolean shared(final UUID hologramId) {
		try {
			return shareTaps.apply(hologramId) != null;
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** The texture currently fed for one of the local player's holograms, if any. */
	public static @Nullable ScreenTexture texture(final UUID hologramId) {
		Feed f = FEEDS.get(hologramId);
		return f == null ? null : f.texture;
	}

	/** Owner-only problems of a hologram's source (for {@link HologramLook}); READY when not fed. */
	public static HologramLook.OwnSource ownSource(final UUID hologramId) {
		Feed f = FEEDS.get(hologramId);
		return f == null ? HologramLook.OwnSource.READY : f.source.status();
	}

	/** The source's reason under "Screens unavailable" (owner only), or null. */
	public static @Nullable String ownDetail(final UUID hologramId) {
		Feed f = FEEDS.get(hologramId);
		return f == null ? null : f.source.statusDetail();
	}

	static void tick(final Minecraft mc) {
		if (!enabled || mc.player == null || mc.level == null) {
			if (!FEEDS.isEmpty()) {
				stopAll();
			}
			return;
		}
		UUID me = mc.player.getUUID();
		Camera camera = mc.gameRenderer.mainCamera();
		Set<UUID> seen = new HashSet<>();
		for (HologramBlockEntity be : HologramTracker.loaded()) {
			HologramSpec spec = be.spec();
			if (spec == null || !me.equals(spec.owner())) {
				continue;
			}
			UUID id = be.hologramId();
			if (!seen.add(id)) {
				continue;
			}
			Target target = Target.of(spec);
			Feed feed = FEEDS.get(id);
			CaptureRate.Visibility visibility = feed != null ? feed.visibility : new CaptureRate.Visibility();
			// Debounced (1 s) so sweeping the camera past a hologram doesn't STOP / CAPTURE the helper over and over.
			int fps = adaptiveRate
					? CaptureRate.fps(visibility.update(visible(camera, be.getBlockPos(), spec), System.nanoTime() / 1_000_000L),
							HologramRenderer.isZoomed(id))
					: MAX_FPS;
			if (adaptiveRate && shared(id)) {
				// a source that paces itself (Windows: 10 fps, 30 while working) already runs fast enough to share
				fps = Math.max(fps, SHARE_FPS);
			}
			if (feed != null && feed.target.equals(target)) {
				if (feed.fps != fps) {
					feed.fps = fps;
					feed.source.setMaxFps(fps);
				}
				continue;
			}
			ScreenTexture texture;
			if (feed != null) {
				// same hologram, new target: keep the texture (stable id), drop the old picture until the new source's
				// first frame arrives
				feed.source.stop();
				texture = feed.texture;
				texture.clear();
			} else {
				texture = ScreenTexture.forHologram(id);
			}
			CaptureSource source = factory.get();
			if (source == null) {
				if (FEEDS.remove(id) != null) {
					ScreenTextureRegistry.remove(id);
				}
				continue;
			}
			ScreenTextureRegistry.register(id, texture);
			Feed fresh = new Feed(target, source, texture, fps);
			fresh.visibility = visibility;
			FEEDS.put(id, fresh);
			source.start(target, fps, new FanOutSink(id, texture));
		}
		for (Iterator<Map.Entry<UUID, Feed>> it = FEEDS.entrySet().iterator(); it.hasNext();) {
			Map.Entry<UUID, Feed> e = it.next();
			if (!seen.contains(e.getKey())) {
				e.getValue().source.stop();
				ScreenTextureRegistry.remove(e.getKey());
				it.remove();
			}
		}
	}

	/** The hologram's front is in the camera's view frustum and within view distance. */
	private static boolean visible(final @Nullable Camera camera, final BlockPos pos, final HologramSpec spec) {
		if (camera == null) {
			return true;
		}
		Frame f = HologramPlacement.frame(pos.getX(), pos.getY(), pos.getZ(), spec);
		Vec3 eye = camera.position();
		if (!CaptureRate.seesFront(f, new Vec3d(eye.x, eye.y, eye.z))) {
			return false;
		}
		Vec3d half = f.right().scale(f.w() / 2);
		double hy = f.h() / 2;
		Vec3d c = f.center();
		AABB box = new AABB(c.x() - Math.abs(half.x()), c.y() - hy, c.z() - Math.abs(half.z()),
				c.x() + Math.abs(half.x()), c.y() + hy, c.z() + Math.abs(half.z())).inflate(0.1);
		return camera.getCullFrustum().isVisible(box);
	}

	/**
	 * Delivers every frame to the hologram's texture, then to its share tap if it is being shared. A failing tap is
	 * logged and skipped; it never affects the owner's own picture.
	 */
	static final class FanOutSink implements FrameSink {
		static final long WARN_EVERY_NANOS = 10_000_000_000L;
		private final UUID id;
		private final FrameSink primary;
		private volatile long lastWarn;

		FanOutSink(final UUID id, final FrameSink primary) {
			this.id = id;
			this.primary = primary;
		}

		private @Nullable FrameSink tap() {
			try {
				return shareTaps.apply(id);
			} catch (RuntimeException e) {
				return null;
			}
		}

		/** Logs a tap failure at most once per {@link #WARN_EVERY_NANOS} per hologram (it could fail every frame). */
		private void tapFailed(final RuntimeException e) {
			long now = System.nanoTime();
			if (lastWarn == 0 || now - lastWarn >= WARN_EVERY_NANOS) {
				lastWarn = now;
				LOGGER.warn("[office] Share tap of hologram {} failed (further failures muted for 10 s)", id, e);
			}
		}

		@Override
		public void full(final int w, final int h, final ByteBuffer rgba) {
			primary.full(w, h, rgba);
			FrameSink t = tap();
			if (t != null) {
				try {
					t.full(w, h, rgba);
				} catch (RuntimeException e) {
					tapFailed(e);
				}
			}
		}

		@Override
		public void patch(final int x, final int y, final int w, final int h, final ByteBuffer rgba) {
			primary.patch(x, y, w, h, rgba);
			FrameSink t = tap();
			if (t != null) {
				try {
					t.patch(x, y, w, h, rgba);
				} catch (RuntimeException e) {
					tapFailed(e);
				}
			}
		}

		@Override
		public void ended(final String reason) {
			primary.ended(reason);
			FrameSink t = tap();
			if (t != null) {
				try {
					t.ended(reason);
				} catch (RuntimeException e) {
					tapFailed(e);
				}
			}
		}
	}

	static void stopAll() {
		for (Map.Entry<UUID, Feed> e : FEEDS.entrySet()) {
			e.getValue().source.stop();
			ScreenTextureRegistry.remove(e.getKey());
		}
		FEEDS.clear();
	}
}
