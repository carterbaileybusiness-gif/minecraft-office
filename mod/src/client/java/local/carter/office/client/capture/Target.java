package local.carter.office.client.capture;

import org.jspecify.annotations.Nullable;

import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;
import local.carter.office.core.Shape;

/**
 * What a capture source should capture: the owner's main screen minus Minecraft ({@link Mode#MIRROR}; slot 0), a virtual
 * display ({@link Mode#EXTEND}, {@code displaySlot} 1-4) or one window ({@link Mode#WINDOW}, {@code windowKey}), at the
 * hologram's {@code shape} (native pixel size {@link Shape#pxW()} x {@link Shape#pxH()}). {@code monitor} (Mirror only):
 * the real monitor it copies instead of the main one (an opaque id; null = main, also when that monitor is unplugged).
 */
public record Target(Mode mode, int displaySlot, @Nullable String windowKey, Shape shape, @Nullable String monitor) {
	public Target(final Mode mode, final int displaySlot, final @Nullable String windowKey, final Shape shape) {
		this(mode, displaySlot, windowKey, shape, null);
	}

	/** The target a hologram spec asks for (size, position and privacy don't matter to capture). */
	public static Target of(final HologramSpec spec) {
		String key = spec.mode() == Mode.WINDOW ? spec.windowKey() : null;
		int slot = spec.mode() == Mode.MIRROR ? 0 : spec.displaySlot(); // Mirror uses no display: a slot change isn't a new target
		String monitor = spec.mode() == Mode.MIRROR ? spec.mirrorDisplay() : null;
		return new Target(spec.mode(), slot, key, spec.shape(), monitor);
	}
}
