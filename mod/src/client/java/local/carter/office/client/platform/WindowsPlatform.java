package local.carter.office.client.platform;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.sun.jna.platform.win32.WinUser.WINDOWPLACEMENT;

import dev.onvoid.webrtc.media.video.desktop.DesktopSource;
import dev.onvoid.webrtc.media.video.desktop.WindowCapturer;

import local.carter.office.client.capture.JavaDesktopSource;
import local.carter.office.client.capture.OwnScreens;
import local.carter.office.client.capture.WebRtcNatives;
import local.carter.office.client.interact.HologramTracker;
import local.carter.office.client.interact.WindowListProvider;
import local.carter.office.client.interact.WindowListProvider.WindowChoice;
import local.carter.office.client.render.HologramLook;
import local.carter.office.client.render.ScreenTexture;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;
import local.carter.office.hologram.HologramBlockEntity;
import local.carter.office.hologram.OfficeBlocks;
import local.carter.office.net.OfficePayloads;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;

/**
 * Windows, beta: Window mode only, with no helper install. Windows are listed and captured with webrtc-java
 * ({@link JavaDesktopSource}); zooming in moves the chosen window over Minecraft's client area and focuses it, a
 * low-level keyboard hook watches for double-Esc / Ctrl+Alt+Shift+Esc, and zooming out puts the window back where it
 * was and focuses Minecraft again. Extend mode ("Whole display") is not available yet.
 */
public final class WindowsPlatform implements Platform {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final ScheduledExecutorService EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "office-win-platform");
		t.setDaemon(true);
		return t;
	});

	/** A capturable top-level window: its handle and the file name of its program. */
	public record Found(long hwnd, String exe) {}

	/** What a zoom changed, to undo it. */
	private record Zoom(long hwnd, String key, @Nullable WINDOWPLACEMENT saved, long minecraft) {}

	private final ExitChord chord = new ExitChord();
	private volatile @Nullable Runnable exitHandler;
	private @Nullable Zoom zoom;
	private Win32.@Nullable KeyHook keyHook;
	private @Nullable ScheduledFuture<?> watch;
	/** Set once the current zoom asked to exit (atomic, not synchronized: the keyboard hook must never wait). */
	private final AtomicBoolean exitSent = new AtomicBoolean();
	/** Bumped by every zoomIn/zoomOut, so a late watchdog tick or hook event from an old zoom is ignored. */
	private final AtomicLong generation = new AtomicLong();

	/** Client start-up on Windows: own holograms capture with {@link JavaDesktopSource}, the settings screen lists windows. */
	public static void install() {
		if (!(Platform.current() instanceof WindowsPlatform p)) {
			return;
		}
		WebRtcNatives.preload();
		WindowListProvider.set(p::windows);
		if (!Boolean.getBoolean(OwnScreens.TEST_PATTERN_PROPERTY)) { // -Doffice.testPattern=true keeps the test pattern
			OwnScreens.setSourceFactory(JavaDesktopSource::new);
		}
		OwnScreens.setEnabled(true);
		HologramLook.setOwnSourceProvider(WindowsPlatform::ownSource);
		HologramLook.setOwnDetailProvider(WindowsPlatform::ownDetail);
		installPlacementDefault();
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> p.zoomOut());
		Runtime.getRuntime().addShutdownHook(new Thread(p::zoomOut, "office-win-restore"));
		LOGGER.info("Minecraft Office: Windows window capture (beta) enabled; webrtc natives {}",
				WebRtcNatives.supported() ? "available" : "not available on this CPU");
	}

	/** How the owner's capture of one hologram is doing, for its placeholder panel. */
	static HologramLook.OwnSource ownSource(final UUID hologramId) {
		ScreenTexture t = OwnScreens.texture(hologramId);
		return ownSourceFor(t == null ? null : t.endedReason());
	}

	/** The reason under "Screens unavailable", or null. */
	static @Nullable String ownDetail(final UUID hologramId) {
		ScreenTexture t = OwnScreens.texture(hologramId);
		String reason = t == null ? null : t.endedReason();
		return ownSourceFor(reason) == HologramLook.OwnSource.UNAVAILABLE ? reason : null;
	}

	/**
	 * Placeholder look for why the capture ended (null: still running or not started): window gone → "Window closed";
	 * an Extend hologram → "Pick a window" (this computer shows one window); anything else → "Screens unavailable" with
	 * the reason (the source logs it). Pure.
	 */
	public static HologramLook.OwnSource ownSourceFor(final @Nullable String endedReason) {
		if (endedReason == null) {
			return HologramLook.OwnSource.READY;
		}
		if (JavaDesktopSource.REASON_WINDOW_CLOSED.equals(endedReason)) {
			return HologramLook.OwnSource.WINDOW_CLOSED;
		}
		if (JavaDesktopSource.REASON_EXTEND.equals(endedReason)) {
			return HologramLook.OwnSource.PICK_WINDOW;
		}
		return HologramLook.OwnSource.UNAVAILABLE;
	}

	/**
	 * New holograms are Extend by default, which Windows can't show: right after the local player places one, switch it
	 * to Window mode (the owner then picks a window in settings).
	 */
	private static void installPlacementDefault() {
		PlacementDefault placed = new PlacementDefault();
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide() && player.getItemInHand(hand).is(OfficeBlocks.HOLOGRAM_EMITTER_ITEM)) {
				BlockPos bp = hit.getBlockPos();
				placed.used(bp.getX(), bp.getY(), bp.getZ(), System.nanoTime() / 1_000_000);
			}
			return InteractionResult.PASS;
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.player == null || mc.getConnection() == null) {
				return;
			}
			UUID me = mc.player.getUUID();
			long now = System.nanoTime() / 1_000_000;
			for (HologramBlockEntity be : HologramTracker.loaded()) {
				HologramSpec spec = be.spec();
				if (spec == null) {
					continue;
				}
				BlockPos pos = be.getBlockPos();
				if (placed.shouldSwitch(be.hologramId(), spec, me.equals(spec.owner()), pos.getX(), pos.getY(), pos.getZ(), now)) {
					LOGGER.info("New hologram at {}: Window mode (Windows can't show a whole display yet)", pos);
					ClientPlayNetworking.send(new OfficePayloads.UpdateHologram(pos, spec.shape(), Mode.WINDOW, spec.privacy(), spec.displaySlot(), null));
				}
			}
		});
	}

	/**
	 * The settings screen's choices: titled with the program name only (never the window title, which may be private),
	 * sorted by name, duplicates numbered "chrome (2)"; entries that can't form a key are dropped.
	 */
	public static List<WindowChoice> choices(final List<Found> found) {
		List<Found> usable = new ArrayList<>();
		for (Found f : found) {
			if (f.hwnd() != 0 && f.exe() != null && !f.exe().isBlank() && f.exe().indexOf(WindowKey.SEP) < 0
					&& f.exe().length() <= WindowKey.MAX_EXE) {
				usable.add(f);
			}
		}
		usable.sort(Comparator.comparing(f -> appName(f.exe()), String.CASE_INSENSITIVE_ORDER)); // stable: keeps list order
		Map<String, Integer> seen = new HashMap<>();
		List<WindowChoice> out = new ArrayList<>();
		for (Found f : usable) {
			String name = appName(f.exe());
			int n = seen.merge(name.toLowerCase(Locale.ROOT), 1, Integer::sum);
			out.add(new WindowChoice(new WindowKey(f.exe(), f.hwnd()).format(), n == 1 ? name : name + " (" + n + ")"));
		}
		return out;
	}

	private static String appName(final String exe) {
		return exe.toLowerCase(Locale.ROOT).endsWith(".exe") && exe.length() > 4 ? exe.substring(0, exe.length() - 4) : exe;
	}

	@Override
	public boolean supportsExtend() {
		return false;
	}

	/** Capturable windows of other programs (webrtc-java's list), named by program. Blocks while natives load the first time. */
	@Override
	public List<WindowChoice> windows() {
		try {
			WebRtcNatives.load();
			Win32 w32 = Win32.get();
			long self = ProcessHandle.current().pid();
			List<Found> found = new ArrayList<>();
			WindowCapturer capturer = new WindowCapturer();
			try {
				for (DesktopSource s : capturer.getDesktopSources()) {
					if (s.id == 0 || !w32.isWindow(s.id) || w32.processId(s.id) == self) {
						continue; // Minecraft itself can't be shown inside Minecraft
					}
					String exe = w32.exeName(s.id);
					if (exe != null) {
						found.add(new Found(s.id, exe));
					}
				}
			} finally {
				capturer.dispose();
			}
			return choices(found);
		} catch (Throwable e) {
			LOGGER.warn("Couldn't list windows: {}", e.toString());
			return List.of();
		}
	}

	/**
	 * Window mode: moves the hologram's window over Minecraft's client area (measured with Win32, in physical pixels,
	 * when Minecraft's window handle is known; else {@code mcWindow}, assumed physical pixels) and focuses it, then hooks
	 * the keyboard for the exit gesture. If the window is gone or now belongs to another program, or anything fails, it
	 * undoes what it did and asks to exit instead. Extend mode does nothing. Never throws, never blocks on the hook.
	 */
	@Override
	public synchronized void zoomIn(final HologramSpec spec, final Rect mcWindow) {
		if (spec.mode() != Mode.WINDOW) {
			return;
		}
		if (zoom != null) {
			zoomOut();
		}
		long gen = generation.incrementAndGet();
		exitSent.set(false);
		chord.reset();
		try {
			WindowKey key = WindowKey.parse(spec.windowKey());
			Win32 w32 = Win32.get();
			String exe = key != null && w32.isWindow(key.hwnd()) ? w32.exeName(key.hwnd()) : null;
			if (key == null || exe == null || !exe.equalsIgnoreCase(key.exe())) {
				LOGGER.info("Zoom: window {} is gone or reused by {}; not moving anything", spec.windowKey(), exe);
				requestExit(gen);
				return;
			}
			long hwnd = key.hwnd();
			long mc = minecraftHwnd();
			Rect area = mc != 0 ? w32.clientRectOnScreen(mc) : null;
			if (area == null || area.w() <= 0 || area.h() <= 0) {
				area = mcWindow;
			}
			zoom = new Zoom(hwnd, key.format(), w32.placement(hwnd), mc);
			JavaDesktopSource.setWorkingKey(spec.windowKey());
			if (!w32.placeOver(hwnd, area)) {
				LOGGER.warn("Couldn't move {} over Minecraft", key.exe());
			}
			if (!w32.focus(hwnd)) {
				LOGGER.warn("Windows refused to focus {}", key.exe());
			}
			keyHook = w32.hookKeys(chord, () -> requestExit(gen));
			watch = EXEC.scheduleWithFixedDelay(() -> {
				if (!w32.isWindow(hwnd)) {
					requestExit(gen);
				}
			}, 250, 250, TimeUnit.MILLISECONDS);
		} catch (Throwable e) {
			LOGGER.warn("Zoom into {} failed", spec.windowKey(), e);
			zoomOut();
			requestExitAlways();
		}
	}

	/** Unhooks the keyboard, puts the window back as it was and focuses Minecraft. Safe to call any time, repeatedly. */
	@Override
	public synchronized void zoomOut() {
		generation.incrementAndGet();
		if (watch != null) {
			watch.cancel(false);
			watch = null;
		}
		if (keyHook != null) {
			keyHook.close();
			keyHook = null;
		}
		Zoom z = zoom;
		zoom = null;
		if (z == null) {
			return;
		}
		JavaDesktopSource.setWorkingKey(null);
		try {
			Win32 w32 = Win32.get();
			if (z.saved() != null && w32.isWindow(z.hwnd())) {
				w32.restorePlacement(z.hwnd(), z.saved());
			}
			if (z.minecraft() != 0 && w32.isWindow(z.minecraft())) {
				w32.focus(z.minecraft());
			}
		} catch (Throwable e) {
			LOGGER.warn("Restoring the window after zoom failed", e);
		}
	}

	@Override
	public void onExitRequested(final Runnable r) {
		exitHandler = r;
	}

	/** True while the exit-gesture keyboard hook is installed and running (it starts asynchronously; tests). */
	public synchronized boolean keyHookRunning() {
		return keyHook != null && keyHook.running();
	}

	/**
	 * Exit gesture or window gone during zoom {@code gen}: tell the zoom controller once per zoom, off the hook thread
	 * (which must return fast). Ignored if that zoom is already over.
	 */
	private void requestExit(final long gen) {
		if (generation.get() != gen || !exitSent.compareAndSet(false, true)) {
			return;
		}
		dispatchExit(gen);
	}

	/** After a failed zoomIn (already undone): the controller must still leave its zoom state. */
	private void requestExitAlways() {
		dispatchExit(-1);
	}

	private void dispatchExit(final long gen) {
		Runnable r = exitHandler;
		EXEC.execute(() -> {
			try {
				if (r != null) {
					r.run();
				} else if (gen < 0 || generation.get() == gen) {
					zoomOut();
				}
			} catch (Throwable e) {
				LOGGER.warn("Exit-zoom handler failed", e);
			}
		});
	}

	/** Minecraft's own HWND (26.3 uses SDL3), or 0 outside the game. */
	private static long minecraftHwnd() {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc == null || mc.getWindow() == null) {
				return 0;
			}
			int props = SDLVideo.SDL_GetWindowProperties(mc.getWindow().handle());
			return props == 0 ? 0 : SDLProperties.SDL_GetPointerProperty(props, SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0L);
		} catch (Throwable e) {
			return 0;
		}
	}
}
