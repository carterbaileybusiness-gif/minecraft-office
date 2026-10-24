package local.carter.office.client.pointer;

/**
 * Minecraft's own window while the laptop screen is a hologram (spec 5.10, Mac). Pure; {@link PointerClient} feeds it
 * and applies {@link #layer} to the real window every frame ({@link PointerPort#windowLayer}).
 *
 * <ul>
 * <li><b>Floating</b> (above normal windows) while in a world and Minecraft is in front or the player is using a
 * hologram: the apps the Mirror shows, which the helper brings forward under Minecraft, never cover the game. When the
 * player switches to another app themselves (not interacting), it drops back so that app shows normally.</li>
 * <li><b>Click-through</b> only while the player uses the Mirror: from the moment a click or drag on it reaches the helper
 * until it is over (the drag's release; typing mode until Esc / Minecraft is back), and for {@link #SCROLL_MS} after each
 * scroll on it. Then the events the helper posts at the main display reach the app under Minecraft.</li>
 * <li>Never either outside a world: leaving, a disconnect, the helper dying (which ends its drag and typing) or a crash
 * of the session all end in {@link Layer#NORMAL}. A session whose drag and typing are both over is ended on the next
 * {@link #layer} call even if no end event came.</li>
 * </ul>
 */
public final class MirrorWindow {
	/** A scroll on the Mirror keeps Minecraft click-through this long (the helper waits up to 300 ms for it to apply). */
	public static final long SCROLL_MS = 400;

	public record Layer(boolean floating, boolean clickThrough) {
		public static final Layer NORMAL = new Layer(false, false);
	}

	private boolean inWorld;
	private boolean session;
	private long scrollUntil = Long.MIN_VALUE;

	/** A world was joined (the player can use holograms). */
	public void enterWorld() {
		inWorld = true;
	}

	/** Left the world, disconnected, the pointer port went away: nothing stays floating or click-through. */
	public void leaveWorld() {
		inWorld = false;
		session = false;
		scrollUntil = Long.MIN_VALUE;
	}

	/** A click or the start of a drag on the Mirror is about to go to the helper. */
	public void mirrorPressed() {
		if (inWorld) {
			session = true;
		}
	}

	/** A scroll on the Mirror is about to go to the helper. */
	public void mirrorScrolled(final long now) {
		if (inWorld) {
			scrollUntil = now + SCROLL_MS;
		}
	}

	/** The drag ended / typing mode ended (any reason). */
	public void interactionEnded() {
		session = false;
	}

	public boolean clickThrough(final long now) {
		return inWorld && (session || now < scrollUntil);
	}

	/**
	 * The window's layer now. {@code focused}: Minecraft is the active app; {@code interacting}: a press, drag or typing
	 * session (on any hologram) is going on.
	 */
	public Layer layer(final long now, final boolean focused, final boolean interacting) {
		if (!inWorld) {
			return Layer.NORMAL;
		}
		if (!interacting) {
			session = false; // nothing of the Mirror's is going on any more
		}
		boolean through = clickThrough(now);
		return new Layer(focused || interacting || through, through);
	}
}
