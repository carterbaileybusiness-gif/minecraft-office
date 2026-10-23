package local.carter.office.client.capture;

/**
 * Something that produces live screen frames for one {@link Target}: the Mac helper (Task 9b), Windows Java capture
 * (Task 11), a network receiver for other players' screens (Task 14), or {@link TestPatternSource}.
 *
 * <p>Contract:
 * <ul>
 * <li>{@link #start} returns quickly; frames are delivered to the sink from the source's own thread(s), never the render
 * thread. The first frame after a start is a {@link FrameSink#full}; later ones may be {@link FrameSink#patch}es.</li>
 * <li>At most {@code maxFps} sink deliveries (a full frame or one batch of patches) per second.</li>
 * <li>{@link #start} on a running source restarts it with the new target. {@link #stop} is idempotent and returns after
 * the source has stopped calling the sink.</li>
 * <li>If the source stops on its own (window closed, helper died, connection lost) it calls
 * {@link FrameSink#ended(String)} once and {@link #isLive()} becomes false.</li>
 * </ul>
 */
public interface CaptureSource {
	void start(Target t, int maxFps, FrameSink sink);

	void stop();

	boolean isLive();
}
