package local.carter.office.client.platform;

import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.capture.CaptureSource;
import local.carter.office.core.HologramSpec;

/**
 * What this computer can do for the player's own screens: virtual displays (EXTEND), the window list (WINDOW), and
 * working on a screen (zoom: cursor onto the display, exit hotkey). {@link MacPlatform} does it through the helper app,
 * {@link WindowsPlatform} (beta) with Java window capture and Win32 (one window only, no EXTEND); {@link NoPlatform}
 * (Linux, dev) can't.
 */
public interface Platform {
	/** Global screen rectangle, top-left origin (macOS: Quartz points; Windows: physical pixels). */
	record Rect(int x, int y, int w, int h) {}

	/**
	 * A window the player can show on a hologram. {@code key} is opaque and stored in the (synced) hologram spec, so it
	 * never contains the title; {@code title} is only shown on the owner's own screen.
	 */
	record WindowInfo(String key, String app, String title, int w, int h) {}

	/** Whether holograms can be whole virtual displays (EXTEND). */
	boolean supportsExtend();

	/** The windows last reported by the OS (refreshed by {@link #refreshWindows}); may block on Windows (call off-thread). */
	List<WindowInfo> windows();

	/** Asks for a fresh window list; it arrives later in {@link #windows}. */
	default void refreshWindows() {}

	/** The player starts working on {@code spec}'s screen; {@code mcWindow} is Minecraft's window frame. */
	void zoomIn(HologramSpec spec, Rect mcWindow);

	void zoomOut();

	/** Runs (on the client thread) when the OS-level exit gesture fires while zoomed. */
	void onExitRequested(Runnable r);

	/**
	 * Why the player can't work on {@code spec}'s screen right now (a short sentence for the action bar), or null when
	 * {@link #zoomIn} will work. Checked before zooming in and every tick while zoomed: when it turns non-null (e.g. the
	 * helper quit) the zoom ends.
	 */
	default @Nullable String workProblem(final HologramSpec spec) {
		return null;
	}

	/** A capture source for one of the player's own holograms, or null when this computer can't capture. */
	default @Nullable CaptureSource newOwnSource() {
		return null;
	}

	/**
	 * Whether {@link #newOwnSource}'s sources pace themselves (Windows: 10 fps, 30 while working on the window). OwnScreens
	 * then starts them at a fixed {@code MAX_FPS} cap instead of driving {@link CaptureSource#setMaxFps} from visibility.
	 */
	default boolean ownSourcePacesItself() {
		return false;
	}

	/** Hooks events, commands and providers; called once from the client initializer. */
	default void register() {}

	static Platform current() {
		return Holder.INSTANCE;
	}

	static boolean isMac() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
	}

	static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
	}

	/** The platform for this computer, picked once. */
	final class Holder {
		private static final Platform INSTANCE = isMac() ? new MacPlatform() : isWindows() ? new WindowsPlatform() : new NoPlatform();

		private Holder() {}
	}
}
