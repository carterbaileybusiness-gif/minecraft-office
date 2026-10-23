package local.carter.office.client.render;

import java.util.Optional;
import java.util.UUID;

import local.carter.office.OfficeMod;

/**
 * Live screen textures by hologram id. Task 6 placeholder: empty, so holograms show their look panels; Task 7 keeps
 * the real textures here. For look checks before capture exists, {@code /hologramtest} shows a bundled 1920x1080
 * desktop test pattern on every hologram this client may view.
 */
public final class ScreenTextureRegistry {
	public static final ScreenTexture TEST_PATTERN = new ScreenTexture(OfficeMod.id("textures/misc/test_pattern.png"), 1920, 1080);

	private static volatile boolean testPattern;

	private ScreenTextureRegistry() {}

	public static Optional<ScreenTexture> get(final UUID hologramId) {
		return testPattern ? Optional.of(TEST_PATTERN) : Optional.empty();
	}

	/** Toggles the test pattern; returns the new state. */
	public static boolean toggleTestPattern() {
		testPattern = !testPattern;
		return testPattern;
	}
}
