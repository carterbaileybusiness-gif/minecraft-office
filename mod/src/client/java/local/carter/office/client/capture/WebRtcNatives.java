package local.carter.office.client.capture;

import java.util.Locale;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.onvoid.webrtc.internal.NativeLoader;

/**
 * Loads webrtc-java's native library (bundled for 64-bit Windows only). Nothing else may touch a webrtc-java class that
 * has native methods before {@link #load()} succeeded; on other systems it refuses without touching any native code.
 */
public final class WebRtcNatives {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static boolean loaded;
	private static @Nullable RuntimeException failure;

	private WebRtcNatives() {}

	/** True on 64-bit x86 Windows, the only platform whose natives the mod ships. */
	public static boolean supported() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		return os.startsWith("windows") && (arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x86-64"));
	}

	/** Loads the natives once (extracting the DLL to the temp folder); throws with the reason if that is impossible. */
	public static synchronized void load() {
		if (loaded) {
			return;
		}
		if (failure != null) {
			throw failure;
		}
		if (!supported()) {
			failure = new UnsupportedOperationException("Window capture needs 64-bit Windows (this is " + System.getProperty("os.name") + " "
					+ System.getProperty("os.arch") + ")");
			throw failure;
		}
		try {
			long t0 = System.nanoTime();
			NativeLoader.loadLibrary("webrtc-java");
			loaded = true;
			LOGGER.info("webrtc-java natives loaded in {} ms", (System.nanoTime() - t0) / 1_000_000);
		} catch (Throwable e) {
			failure = new IllegalStateException("Couldn't load webrtc-java natives: " + e, e);
			throw failure;
		}
	}

	/** Loads in the background so the first window list or capture doesn't stall the game. */
	public static void preload() {
		Thread t = new Thread(() -> {
			try {
				load();
			} catch (RuntimeException e) {
				LOGGER.warn("Windows capture unavailable: {}", e.getMessage());
			}
		}, "office-webrtc-load");
		t.setDaemon(true);
		t.start();
	}
}
