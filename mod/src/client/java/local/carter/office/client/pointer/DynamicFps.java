package local.carter.office.client.pointer;

import java.lang.reflect.Method;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Soft link to Dynamic FPS (in the modpack, no API): when work mode begins or ends, ask it to re-check its power state
 * ({@code DynamicFPSMod.onStatusChanged(false)}), so the {@code DynamicFpsFocusMixin} answer ("focused while working")
 * takes effect at once instead of at its next window event. Does nothing when the mod isn't there; never throws.
 */
final class DynamicFps {
	private static final Logger LOGGER = LoggerFactory.getLogger("office");
	private static Method onStatusChanged;
	private static boolean looked;

	private DynamicFps() {}

	static void recheck() {
		try {
			if (!looked) {
				looked = true;
				Class<?> mod = Class.forName("dynamic_fps.impl.DynamicFPSMod");
				onStatusChanged = mod.getMethod("onStatusChanged", boolean.class);
				LOGGER.info("Office: Dynamic FPS found; it renders at full rate while you work on your Mac");
			}
			if (onStatusChanged != null) {
				onStatusChanged.invoke(null, false);
			}
		} catch (ClassNotFoundException e) {
			onStatusChanged = null; // not installed
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			LOGGER.debug("Office: couldn't nudge Dynamic FPS: {}", e.toString());
			onStatusChanged = null;
		}
	}
}
