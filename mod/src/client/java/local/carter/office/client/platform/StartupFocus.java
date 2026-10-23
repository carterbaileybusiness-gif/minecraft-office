package local.carter.office.client.platform;

import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLVideo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * macOS: a game started from a script (Terminal → Prism → java) is not launched through LaunchServices, so the launcher
 * stays frontmost and keys / mouse look don't reach Minecraft until the player clicks it. While the frontmost app is
 * still the launcher (a terminal or Prism), this asks for focus at most {@link #MAX_ATTEMPTS} times, {@link #EVERY_MS}
 * apart: first politely ({@code [NSApp activate]}), then with {@code activateIgnoringOtherApps:YES}. It stops for good
 * when Minecraft gets focus (the request worked, or the player clicked it), when the player moves to any other app, or
 * after {@link #WINDOW_MS}. Only raises and activates; never changes the window's size, position or mode. Off in the dev
 * environment (runClient, game tests) and with {@code -Doffice.raiseOnStart=false}; does nothing on other systems.
 */
public final class StartupFocus {
	private static final Logger LOGGER = LoggerFactory.getLogger("office");
	public static final String PROPERTY = "office.raiseOnStart";
	public static final int MAX_ATTEMPTS = 3;
	public static final long EVERY_MS = 1_500;
	/** Never acts later than this after the first client tick. */
	public static final long WINDOW_MS = 20_000;
	/** Apps a script-started game comes up behind. */
	static final Set<String> LAUNCHERS = Set.of("com.apple.Terminal", "org.prismlauncher.PrismLauncher",
			"local.carter.strawhatterminal", "com.googlecode.iterm2", "dev.warp.Warp-Stable", "com.mitchellh.ghostty");

	/** Who is frontmost right now. */
	public enum Front {
		/** Minecraft itself. */
		SELF,
		/** The terminal or launcher the game was started from. */
		LAUNCHER,
		/** Some other app: the player went there. */
		OTHER,
		/** Couldn't tell (no frontmost app, or the lookup failed). */
		UNKNOWN
	}

	private long start = -1;
	private long lastTry = -1;
	private int attempts;
	private boolean done;

	/** {@code bundleId}/{@code pid} of the frontmost app, as seen from a process with {@code ownPid}. */
	public static Front classify(final @Nullable String bundleId, final long pid, final long ownPid) {
		if (pid > 0 && pid == ownPid) {
			return Front.SELF;
		}
		if (bundleId == null) {
			return pid > 0 ? Front.OTHER : Front.UNKNOWN;
		}
		return LAUNCHERS.contains(bundleId) ? Front.LAUNCHER : Front.OTHER;
	}

	/**
	 * The focus request to make now: 0 = none, otherwise the attempt number (1 = polite, 2+ = forced).
	 * {@code focused}: Minecraft's window has keyboard focus.
	 */
	public int next(final long nowMs, final boolean focused, final Front front) {
		if (done) {
			return 0;
		}
		if (start < 0) {
			start = nowMs;
		}
		if (focused || front == Front.SELF || front == Front.OTHER || nowMs - start > WINDOW_MS) {
			done = true; // got focus, or the player chose another app: never pull them back
			return 0;
		}
		if (lastTry >= 0 && nowMs - lastTry < EVERY_MS) {
			return 0;
		}
		if (attempts >= MAX_ATTEMPTS) {
			done = true; // the last request had its chance
			return 0;
		}
		if (front == Front.UNKNOWN) {
			return 0;
		}
		lastTry = nowMs;
		return ++attempts;
	}

	/** No more requests will be made. */
	public boolean done() {
		return done;
	}

	public static void register() {
		if (!Platform.isMac() || FabricLoader.getInstance().isDevelopmentEnvironment()
				|| !Boolean.parseBoolean(System.getProperty(PROPERTY, "true"))) {
			return;
		}
		StartupFocus focus = new StartupFocus();
		long ownPid = ProcessHandle.current().pid();
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (focus.done()) {
				return;
			}
			boolean focused = mc.isWindowActive();
			MacApp.Frontmost f = focused ? null : MacApp.frontmost();
			Front front = f == null ? (focused ? Front.SELF : Front.UNKNOWN) : classify(f.bundleId(), f.pid(), ownPid);
			int attempt = focus.next(Util.getMillis(), focused, front);
			if (attempt > 0) {
				raise(mc, attempt);
			} else if (focus.done()) {
				LOGGER.debug("StartupFocus: done (focused {}, front {})", focused, front);
			}
		});
	}

	private static void raise(final Minecraft mc, final int attempt) {
		LOGGER.info("Bringing Minecraft to the front (attempt {}/{})", attempt, MAX_ATTEMPTS);
		MacApp.activate(attempt > 1);
		try {
			SDLVideo.SDL_RaiseWindow(mc.getWindow().handle());
		} catch (Throwable e) {
			LOGGER.debug("StartupFocus: SDL_RaiseWindow failed: {}", e.toString());
		}
	}
}
