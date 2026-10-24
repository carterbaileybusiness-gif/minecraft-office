package local.carter.office.client.platform;

/**
 * The Mirror needs Minecraft in a window (spec 5.10): in macOS's separate full-screen Space the other apps aren't on the
 * screen the Mirror shows and clicks into. While the player has a Mirror hologram loaded and Minecraft is in that mode,
 * chat says how to fix it — once, and again only after the mode was left and re-entered (or the world re-joined). Pure.
 */
public final class MirrorHint {
	public static final String TEXT = "Mirror needs Minecraft in a window: Options → Video → Fullscreen OFF "
			+ "(the game then fills the screen as a borderless window)";

	private boolean shown;

	/** Once a second: should the hint be shown now? */
	public boolean update(final boolean ownSpace, final boolean ownMirrorLoaded) {
		if (!ownSpace) {
			shown = false;
			return false;
		}
		if (!ownMirrorLoaded || shown) {
			return false;
		}
		shown = true;
		return true;
	}

	public void leftWorld() {
		shown = false;
	}
}
