package local.carter.office.client.capture;

import java.nio.ByteBuffer;

/**
 * Receives frames from a {@link CaptureSource}. Pixels are 8-bit RGBA, rows top to bottom, tightly packed
 * ({@code w * 4} bytes per row), starting at {@code rgba.position()}; the buffer's position and limit are not changed.
 * The sink copies what it needs before returning, so the caller may reuse the buffer straight away. May be called from
 * any thread, but one source calls its sink from one thread at a time.
 */
public interface FrameSink {
	/** A whole frame; also announces the frame size (a size change reallocates). */
	void full(int w, int h, ByteBuffer rgba);

	/** A changed rectangle of the current frame (clipped to the frame; ignored before the first full frame). */
	void patch(int x, int y, int w, int h, ByteBuffer rgba);

	/** The source stopped on its own; {@code reason} is short and human-readable. */
	void ended(String reason);
}
