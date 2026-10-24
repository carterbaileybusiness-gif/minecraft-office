package local.carter.office.client.pointer;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.dock.DockItem;
import local.carter.office.client.helper.HelperProtocol.PointerButton;

/**
 * What in-world use needs from this computer ({@code Platform.pointer()}; the Mac helper). Every call is made on the
 * client thread and never blocks; listeners run on the client thread.
 */
public interface PointerPort {
	/** Why the pointer can't work right now (a short sentence for the action bar), or null when it can. */
	@Nullable String problem();

	/**
	 * Work mode at {@code p} (spec 5.10 round 2): the helper puts the real cursor there, presses {@code button} there
	 * (just the press when the real button is still held, so the hand finishes it) and leaves the real mouse to the Mac
	 * until Esc (TYPING_EXIT).
	 */
	default void workBegin(final PointerPoint p, final PointerButton button) {}

	/** Where the real cursor is while working: {slot, x, y, w, h} ({@link WorkCursor}), or null. */
	default int @Nullable [] workCursor() {
		return null;
	}

	/** Scroll there, pixels of content motion (dy &gt; 0 = up). */
	default void scroll(final PointerPoint p, final int dx, final int dy) {}

	default void exitTyping() {}

	/** Work mode ended in the helper (Esc, Minecraft active again) or the helper went away ("helper"). */
	default void onTypingExit(final Consumer<String> listener) {}

	/** Bring Minecraft to the front again (the helper that would have done it is gone). */
	default void activateMinecraft() {}

	/**
	 * Minecraft's own window (spec 5.10, {@link MirrorWindow}): {@code floating} above normal windows, {@code
	 * clickThrough} = it ignores mouse events, so the helper's events at the main display reach the app under it.
	 */
	default void windowLayer(final boolean floating, final boolean clickThrough) {}

	// ---- the mini Dock (spec 5.10)

	/** The installed apps, bundle id → name; null until the helper has sent them (asked for on connect). */
	default @Nullable Map<String, String> installedApps() {
		return null;
	}

	/** An app icon as PNG bytes, or null until it arrives ({@link #requestIcons} asks for it). */
	default byte @Nullable [] appIcon(final String bundle) {
		return null;
	}

	/** Ask for these apps' icons (each one once). */
	default void requestIcons(final List<String> bundles) {}

	/**
	 * Open the Dock item's app or page onto virtual display {@code slot}; its window stops {@code bottomPixels} display
	 * pixels above the bottom, where the in-world Dock is.
	 */
	default void openApp(final int slot, final DockItem item, final int bottomPixels) {}

	/** Called with true / false when a password field's secure input goes on / off while typing (Esc can't be seen). */
	default void onSecureInput(final Consumer<Boolean> listener) {}

	/**
	 * Walk while dragging (round 3): called on the client thread with the movement keys the helper holds for Minecraft
	 * (any of forward, back, left, right, jump, sneak; empty = stop) while the real mouse button is held in work mode.
	 */
	default void onWalk(final Consumer<java.util.Set<String>> listener) {}

	/**
	 * Why the Mirror can't be used right now (a sentence), or null: on a Mac, Minecraft in macOS's separate full-screen
	 * Space, where the other apps aren't.
	 */
	default @Nullable String mirrorProblem() {
		return null;
	}

	/** Re-apply Minecraft's window layer even if it didn't change (focus changed: SDL / AppKit may have reset it). */
	default boolean windowLayerApplied(final boolean floating, final boolean clickThrough) {
		windowLayer(floating, clickThrough);
		return true;
	}

	/** Called with the helper's word when opening failed (a sentence for the action bar). */
	default void onAppProblem(final Consumer<String> listener) {}
}
