package local.carter.office.client.platform;

import org.lwjgl.sdl.SDLVideo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * macOS: a game started from a script (Terminal → Prism → java) is not launched through LaunchServices, so the Terminal
 * stays frontmost and keys / mouse look don't reach Minecraft until the player clicks it. Until the window has had focus
 * once, this asks for it about once a second for the first {@link #WINDOW_MS} ms ({@code SDL_RaiseWindow} plus
 * {@code [NSApp activate]}). After the first focus, or after that window, it never touches focus again. Only raises and
 * activates; never changes the window's size, position or fullscreen state. Off in the dev environment (runClient,
 * game tests) and with {@code -Doffice.raiseOnStart=false}; does nothing on other systems.
 */
public final class StartupFocus {
	private static final Logger LOGGER = LoggerFactory.getLogger("office");
	public static final String PROPERTY = "office.raiseOnStart";
	/** How long after the first client tick a focus request may still be made. */
	public static final long WINDOW_MS = 20_000;
	/** Gap between two requests. */
	public static final long EVERY_MS = 1_000;

	private long start = -1;
	private long lastTry = -1;
	private boolean done;

	/** True when a focus request is due now. {@code focused}: Minecraft's window has keyboard focus. */
	public boolean due(final long nowMs, final boolean focused) {
		if (done) {
			return false;
		}
		if (start < 0) {
			start = nowMs;
		}
		if (focused || nowMs - start > WINDOW_MS) {
			done = true;
			return false;
		}
		if (lastTry < 0 || nowMs - lastTry >= EVERY_MS) {
			lastTry = nowMs;
			return true;
		}
		return false;
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
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (focus.done()) {
				return;
			}
			if (focus.due(Util.getMillis(), mc.isWindowActive())) {
				raise(mc);
			}
		});
	}

	private static void raise(final Minecraft mc) {
		try {
			SDLVideo.SDL_RaiseWindow(mc.getWindow().handle());
		} catch (Throwable e) {
			LOGGER.debug("StartupFocus: SDL_RaiseWindow failed: {}", e.toString());
		}
		MacApp.activate();
	}
}
