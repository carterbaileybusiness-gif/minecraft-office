package local.carter.office.client.pointer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A Dynamic FPS update that breaks the focus hook must not fail silently: warn once after ~2 s of work mode. */
class DynamicFpsTest {
	@Test
	void warnsOnceWhenInstalledButTheHookNeverRan() {
		assertTrue(DynamicFps.shouldWarn(true, false, DynamicFps.GRACE_MS, false), "installed, hook never ran, 2 s of work");
		assertFalse(DynamicFps.shouldWarn(true, false, DynamicFps.GRACE_MS - 1, false), "give it its 2 s");
		assertFalse(DynamicFps.shouldWarn(true, true, 60_000, false), "the hook ran: fine");
		assertFalse(DynamicFps.shouldWarn(false, false, 60_000, false), "not installed: nothing to say");
		assertFalse(DynamicFps.shouldWarn(true, false, 60_000, true), "once per session");
	}
}
