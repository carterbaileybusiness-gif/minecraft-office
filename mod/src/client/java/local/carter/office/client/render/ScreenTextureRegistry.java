package local.carter.office.client.render;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

/**
 * Live screen textures by hologram id; {@link HologramRenderer} draws the one registered for a hologram (when the
 * viewer may see it). Register/remove on the client (render) thread; {@link #get} is thread-safe.
 *
 * <p>The debug override ({@code /hologramtest}) shows one texture on every hologram this client may view.
 */
public final class ScreenTextureRegistry {
	private static final Map<UUID, ScreenTexture> TEXTURES = new ConcurrentHashMap<>();
	private static volatile @Nullable ScreenTexture override;

	private ScreenTextureRegistry() {}

	public static Optional<ScreenTexture> get(final UUID hologramId) {
		ScreenTexture o = override;
		return o != null ? Optional.of(o) : Optional.ofNullable(TEXTURES.get(hologramId));
	}

	/** Shows {@code texture} on a hologram; a different texture registered before is cleared. */
	public static void register(final UUID hologramId, final ScreenTexture texture) {
		ScreenTexture prev = TEXTURES.put(hologramId, texture);
		if (prev != null && prev != texture) {
			prev.clear();
		}
	}

	/** Stops showing a hologram's texture and clears it (frees GPU texture + buffers). */
	public static void remove(final UUID hologramId) {
		ScreenTexture t = TEXTURES.remove(hologramId);
		if (t != null) {
			t.clear();
		}
	}

	/** Debug: show {@code texture} on every hologram (null = off). The caller owns and clears it. */
	public static void setOverride(final @Nullable ScreenTexture texture) {
		override = texture;
	}

	public static @Nullable ScreenTexture override() {
		return override;
	}
}
