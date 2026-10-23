package local.carter.office.client.pointer;

import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

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
}
