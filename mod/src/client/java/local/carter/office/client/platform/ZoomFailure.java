package local.carter.office.client.platform;

import org.jspecify.annotations.Nullable;

/**
 * Why the Mac helper couldn't put the player to work on a screen (pure). {@link MacPlatform} reports these through
 * {@link Platform#workProblem}, so the zoom ends with the reason in the action bar instead of leaving the player in a
 * dead full-window view.
 */
public final class ZoomFailure {
	public static final String NOT_READY = "Your screen isn't ready yet";
	public static final String NO_HELPER = "The screen helper isn't running";
	public static final String NO_ACCESSIBILITY = "Allow Accessibility for Minecraft Office Helper in System Settings";

	private ZoomFailure() {}

	/**
	 * After sending ZOOM_IN: {@code connected} = helper connected, {@code surface} = the hologram's capture surface
	 * (0 = not capturing), {@code sent} = the message went out.
	 */
	public static @Nullable String forZoomIn(final boolean connected, final int surface, final boolean sent) {
		if (!connected) {
			return NO_HELPER;
		}
		if (surface == 0 || !sent) {
			return NOT_READY;
		}
		return null;
	}

	/** An ERROR from the helper while zoomed: the codes that mean the zoom did not (fully) happen. */
	public static @Nullable String forHelperError(final String code) {
		return switch (code) {
			case "not_found" -> NOT_READY; // the surface named in ZOOM_IN isn't being captured (any more)
			case "no_accessibility" -> NO_ACCESSIBILITY; // no cursor clamp, no window placement, no exit hotkey
			default -> null;
		};
	}
}
