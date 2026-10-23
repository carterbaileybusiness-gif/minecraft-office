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
import local.carter.office.client.interact.WindowListProvider;
import local.carter.office.client.interact.WindowListProvider.WindowChoice;
import local.carter.office.client.render.HologramLook;
import local.carter.office.client.render.ScreenTexture;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;

import net.minecraft.client.Minecraft;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

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
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> p.zoomOut());
		Runtime.getRuntime().addShutdownHook(new Thread(p::zoomOut, "office-win-restore"));
		LOGGER.info("Minecraft Office: Windows window capture (beta) enabled; webrtc natives {}",
				WebRtcNatives.supported() ? "available" : "not available on this CPU");
	}

	/** How the owner's capture of one hologram is doing, for its placeholder panel. */
	static HologramLook.OwnSource ownSource(final UUID hologramId) {
		ScreenTexture t = OwnScreens.texture(hologramId);
		String reason = t == null ? null : t.endedReason();
		if (JavaDesktopSource.REASON_WINDOW_CLOSED.equals(reason)) {
			return HologramLook.OwnSource.WINDOW_CLOSED;
		}
		if (JavaDesktopSource.REASON_EXTEND.equals(reason)) {
			return HologramLook.OwnSource.NEEDS_HELPER; // "Set up screens": pick One window in settings
		}
		return HologramLook.OwnSource.READY;
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
	 * Window mode: moves the hologram's window over Minecraft's client area (measured with Win32 when Minecraft's
	 * window handle is known, else {@code mcWindow}) and focuses it, then hooks the keyboard for the exit gesture. If the
	 * window is gone, asks to exit at once. Extend mode does nothing.
	 */
	@Override
	public synchronized void zoomIn(final HologramSpec spec, final Rect mcWindow) {
		if (spec.mode() != Mode.WINDOW) {
			return;
		}
		if (zoom != null) {
			zoomOut();
		}
		exitSent.set(false);
		chord.reset();
		WindowKey key = WindowKey.parse(spec.windowKey());
		Win32 w32 = Win32.get();
		if (key == null || !w32.isWindow(key.hwnd())) {
			requestExit();
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
		keyHook = w32.hookKeys(chord, this::requestExit);
		watch = EXEC.scheduleWithFixedDelay(() -> {
			if (!w32.isWindow(hwnd)) {
				requestExit();
			}
		}, 250, 250, TimeUnit.MILLISECONDS);
	}

	/** Unhooks the keyboard, puts the window back as it was and focuses Minecraft. Safe to call any time, repeatedly. */
	@Override
	public synchronized void zoomOut() {
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

	/** True while the exit-gesture keyboard hook is installed (tests). */
	public synchronized boolean keyHookRunning() {
		return keyHook != null && keyHook.running();
	}

	/** Exit gesture or window gone: tell the zoom controller once per zoom (off the hook thread, which must return fast). */
	private void requestExit() {
		if (!exitSent.compareAndSet(false, true)) {
			return;
		}
		Runnable r = exitHandler;
		EXEC.execute(() -> {
			try {
				if (r != null) {
					r.run();
				} else {
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
