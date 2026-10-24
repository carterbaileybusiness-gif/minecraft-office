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

	/** Start a drag the helper drives at the press point. */
	default void pointerDown(final PointerPoint p) {}

	default void pointerMove(final PointerPoint p) {}

	/** Cancel a drag from the mod's side. */
	default void pointerUp(final PointerPoint p) {}

	/** A real click there; the app gets the keyboard (typing mode). */
	default void click(final PointerPoint p, final PointerButton button) {}

	/** Scroll there, pixels of content motion (dy &gt; 0 = up). */
	default void scroll(final PointerPoint p, final int dx, final int dy) {}

	default void exitTyping() {}

	/** Physical mouse motion (points) during a helper-driven drag since the last call: {dx, dy}. */
	default int[] drainDragDelta() {
		return new int[] {0, 0};
	}

	default void onTypingExit(final Consumer<String> listener) {}

	default void onDragEnded(final Consumer<String> listener) {}

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
