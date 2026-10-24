package local.carter.office.client.pointer;

import java.lang.reflect.Method;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Soft link to Dynamic FPS (in the modpack, no API). When work mode begins or ends, it asks Dynamic FPS to re-check its
 * power state ({@code DynamicFPSMod.onStatusChanged(false)}), so the {@code DynamicFpsFocusMixin} answer ("focused while
 * working") takes effect at once. Does nothing when the mod isn't there; never throws.
 *
 * <p>The mixin targets Dynamic FPS's own implementation, which an update may change: then it is skipped (not a crash)
 * and the game drops to Dynamic FPS's unfocused rate (1 fps by default) while working. So the mixin reports that it
 * ran ({@link #hookRan()}), and if Dynamic FPS is installed but the hook has not run after {@link #GRACE_MS} of work
 * mode, the player is told once per game session (and the log gets a WARN).
 */
public final class DynamicFps {
	private static final Logger LOGGER = LoggerFactory.getLogger("office");
	/** How long work mode may run before a hook that never ran counts as broken. */
	public static final long GRACE_MS = 2_000;
	public static final String BROKEN_CHAT = "Dynamic FPS changed — set its unfocused FPS higher or work mode will lag";

	private static Method onStatusChanged;
	private static boolean looked;
	private static boolean present;
	private static volatile boolean hookRan;
	private static boolean warned;

	private DynamicFps() {}

	/** DynamicFpsFocusMixin: the hook in Dynamic FPS ran (it is in place). */
	public static void hookRan() {
		hookRan = true;
	}

	/** The hook has run this game session (measurements / tests). */
	public static boolean hookHasRun() {
		return hookRan;
	}

	/** Dynamic FPS is installed. */
	static boolean present() {
		lookUp();
		return present;
	}

	/**
	 * Whether to warn now (pure): Dynamic FPS is installed, its hook has never run although work mode has lasted
	 * {@code workingMs}, at least {@link #GRACE_MS}, and this session hasn't warned yet.
	 */
	static boolean shouldWarn(final boolean present, final boolean hookRan, final long workingMs, final boolean warned) {
		return present && !hookRan && !warned && workingMs >= GRACE_MS;
	}

	/** Client tick while working ({@code workingMs} so far): true the one time the player should be told. */
	static boolean checkHook(final long workingMs) {
		if (!shouldWarn(present(), hookRan, workingMs, warned)) {
			return false;
		}
		warned = true;
		LOGGER.warn("Office: Dynamic FPS is installed but its focus hook never ran after {} ms of work mode (a Dynamic FPS update?);"
				+ " it will throttle the game while you work", workingMs);
		return true;
	}

	static void recheck() {
		try {
			lookUp();
			if (onStatusChanged != null) {
				onStatusChanged.invoke(null, false);
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			LOGGER.debug("Office: couldn't nudge Dynamic FPS: {}", e.toString());
			onStatusChanged = null;
		}
	}

	private static void lookUp() {
		if (looked) {
			return;
		}
		looked = true;
		// installed = its mod id, not its classes (an update moving them is exactly what the hook check is for)
		present = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("dynamic_fps");
		if (!present) {
			return;
		}
		try {
			Class<?> mod = Class.forName("dynamic_fps.impl.DynamicFPSMod");
			LOGGER.info("Office: Dynamic FPS found; work mode asks it to keep the full frame rate while you work on your Mac");
			onStatusChanged = mod.getMethod("onStatusChanged", boolean.class);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			LOGGER.warn("Office: Dynamic FPS found but couldn't be nudged: {}", e.toString());
			onStatusChanged = null;
		}
	}
}
