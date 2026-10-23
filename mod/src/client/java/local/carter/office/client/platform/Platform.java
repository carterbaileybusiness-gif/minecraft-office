package local.carter.office.client.platform;

import java.util.List;
import java.util.Locale;

import local.carter.office.client.interact.WindowListProvider.WindowChoice;
import local.carter.office.core.HologramSpec;

/** What the player's own computer can do for their holograms (Mac helper, Windows Java capture, or nothing). */
public interface Platform {
	record Rect(int x, int y, int w, int h) {}

	static Platform current() {
		return Holder.CURRENT;
	}

	boolean supportsExtend();

	List<WindowChoice> windows();

	void zoomIn(HologramSpec spec, Rect mcWindow);

	void zoomOut();

	void onExitRequested(Runnable r);

	/** The platform for this computer, picked once. */
	final class Holder {
		static final Platform CURRENT = pick();

		private Holder() {}

		private static Platform pick() {
			String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
			if (os.startsWith("windows")) {
				return new WindowsPlatform();
			}
			// TODO(Task 9b merge): macOS -> MacPlatform.
			return new NoPlatform();
		}
	}
}
