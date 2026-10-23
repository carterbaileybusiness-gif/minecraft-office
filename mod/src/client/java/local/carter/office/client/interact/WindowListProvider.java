package local.carter.office.client.interact;

import java.util.List;

/**
 * Supplies the windows the settings screen offers in Window mode. The Mac helper (Task 9b) and Windows capture
 * (Task 11) install a real provider with {@link #set}; the default offers none.
 */
public interface WindowListProvider {
	/**
	 * One pickable window. {@code key} is an opaque, stable id stored in the hologram spec and synced to nearby players,
	 * so it must not contain the window title; {@code title} is shown only on the owner's screen.
	 */
	record WindowChoice(String key, String title) {}

	List<WindowChoice> windows();

	WindowListProvider NONE = List::of;

	static WindowListProvider get() {
		return Holder.current;
	}

	static void set(final WindowListProvider provider) {
		Holder.current = provider == null ? NONE : provider;
	}

	/** Mutable slot for the installed provider (interfaces can't have mutable static fields). */
	final class Holder {
		private static volatile WindowListProvider current = NONE;

		private Holder() {}
	}
}
