package local.carter.office.client.pointer;

/**
 * Minecraft's own window while the player works on their Mac through the holograms (spec 5.10, Mac). Pure; {@link
 * PointerClient} feeds it and applies {@link #layer} to the real window every frame ({@link PointerPort#windowLayer}).
 *
 * <ul>
 * <li><b>Work mode</b> (from the click on a hologram until Esc / ⌘-Tab back): floating above normal windows, so the apps
 * the player uses (their windows on the main display come forward) never cover the game, and click-through, so the
 * real cursor's clicks on the main display reach the app under Minecraft.</li>
 * <li>A <b>scroll</b> on the Mirror outside work mode: the same for {@link #SCROLL_MS}, so the helper's scroll event at the
 * main display reaches the app under Minecraft.</li>
 * <li>Otherwise <b>normal</b>: an ordinary window that takes its own clicks. Never anything else outside a world: leaving,
 * a disconnect, the helper dying or the game closing all end in {@link Layer#NORMAL}.</li>
 * </ul>
 */
public final class MirrorWindow {
	/** A scroll on the Mirror keeps Minecraft click-through this long (the helper waits up to 300 ms for it to apply). */
	public static final long SCROLL_MS = 400;

	public record Layer(boolean floating, boolean clickThrough) {
		public static final Layer NORMAL = new Layer(false, false);
		public static final Layer WORKING = new Layer(true, true);
	}

	private boolean inWorld;
	private boolean working;
	private long scrollUntil = Long.MIN_VALUE;

	/** A world was joined (the player can use holograms). */
	public void enterWorld() {
		inWorld = true;
	}

	/** Left the world, disconnected, the pointer port went away: back to normal. */
	public void leaveWorld() {
		inWorld = false;
		working = false;
		scrollUntil = Long.MIN_VALUE;
	}

	/** Work mode began (before the helper gets WORK_BEGIN). */
	public void workBegan() {
		if (inWorld) {
			working = true;
		}
	}

	/** Work mode ended (any way). */
	public void workEnded() {
		working = false;
	}

	/** A scroll on the Mirror is about to go to the helper. */
	public void mirrorScrolled(final long now) {
		if (inWorld) {
			scrollUntil = now + SCROLL_MS;
		}
	}

	public boolean clickThrough(final long now) {
		return inWorld && (working || now < scrollUntil);
	}

	/** The window's layer now. */
	public Layer layer(final long now) {
		return clickThrough(now) ? Layer.WORKING : Layer.NORMAL;
	}
}
