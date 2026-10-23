package local.carter.office.client.capture;

import org.jspecify.annotations.Nullable;

import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;
import local.carter.office.core.Shape;

/**
 * What a capture source should capture: a whole display ({@link Mode#EXTEND}, {@code displaySlot} 1-4) or one window
 * ({@link Mode#WINDOW}, {@code windowKey}), at the hologram's {@code shape} (native pixel size {@link Shape#pxW()} x
 * {@link Shape#pxH()}).
 */
public record Target(Mode mode, int displaySlot, @Nullable String windowKey, Shape shape) {
	/** The target a hologram spec asks for (size, position and privacy don't matter to capture). */
	public static Target of(final HologramSpec spec) {
		String key = spec.mode() == Mode.WINDOW ? spec.windowKey() : null;
		return new Target(spec.mode(), spec.displaySlot(), key, spec.shape());
	}
}
