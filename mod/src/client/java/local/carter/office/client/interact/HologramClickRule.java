package local.carter.office.client.interact;

/**
 * What a right-click does when a hologram may be under the crosshair (pure). Never place a block or use an item
 * through a hologram (spec 5.9): if any hologram is in front of what vanilla aims at, the click is the hologram's.
 */
public enum HologramClickRule {
	/** No hologram in front: vanilla (or the emitter block's settings) gets the click. */
	NOT_A_HOLOGRAM,
	/** Emitter in the main hand: resize / move / settings (owners), "Owned by …" (others). */
	EDIT,
	/** Your own hologram, in-world use available (Mac): a right-click on the app there. */
	POINTER,
	/** Your own hologram elsewhere (Windows beta): zoom in. */
	ZOOM,
	/** Someone else's hologram: swallowed, nothing happens. */
	SWALLOW;

	public static HologramClickRule decide(final boolean hologramInFront, final boolean own, final boolean holdingEmitter,
			final boolean pointerAvailable) {
		if (!hologramInFront) {
			return NOT_A_HOLOGRAM;
		}
		if (holdingEmitter) {
			return EDIT;
		}
		if (!own) {
			return SWALLOW;
		}
		return pointerAvailable ? POINTER : ZOOM;
	}
}
