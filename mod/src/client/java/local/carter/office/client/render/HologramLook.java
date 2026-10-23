package local.carter.office.client.render;

import java.util.UUID;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;
import local.carter.office.core.Privacy;

/** What a hologram shows to the local viewer, and the words/icon on its placeholder panels (pure logic). */
public final class HologramLook {
	public enum State { LIVE, CONNECTING, PRIVATE_FROSTED, OFFLINE, WINDOW_CLOSED, NEEDS_HELPER }

	/** Problems with the owner's own capture source; only the owner's client knows these. */
	public enum OwnSource { READY, NEEDS_HELPER, WINDOW_CLOSED }

	public enum Icon { NONE, LOCK, POWER, DOWNLOAD, WINDOW }

	/** Text on a placeholder panel; {@code subtitle} may be empty. */
	public record Label(String title, String subtitle, Icon icon) {}

	/** Scanlines are invisible within this distance (blocks) so close-up text stays clean. */
	public static final double SCANLINE_NEAR = 3.0;
	public static final double SCANLINE_ALPHA = 0.10;

	/**
	 * Status of the local player's own capture for one of their holograms (by hologram id). The Mac helper (Task 9b)
	 * and Windows capture (Task 11) replace this; until then everything is READY.
	 */
	private static volatile Function<UUID, OwnSource> ownSource = id -> OwnSource.READY;

	private HologramLook() {}

	public static void setOwnSourceProvider(final Function<UUID, OwnSource> provider) {
		ownSource = provider == null ? id -> OwnSource.READY : provider;
	}

	public static OwnSource ownSource(final UUID hologramId) {
		OwnSource s = ownSource.apply(hologramId);
		return s == null ? OwnSource.READY : s;
	}

	/**
	 * Look without a viewer identity: non-owners may see the screen only when it is PUBLIC (a SHOW_TO hologram needs
	 * the viewer's UUID, see the five-argument form).
	 */
	public static State forViewer(final HologramSpec spec, final boolean ownerOnline, final boolean isOwner, final boolean hasFrames) {
		boolean canView = isOwner || spec.privacy().level() == Privacy.Level.PUBLIC;
		return decide(spec, ownerOnline, isOwner, canView, hasFrames, OwnSource.READY);
	}

	/** Look for a specific viewer, honouring SHOW_TO lists and the owner's own source problems. */
	public static State forViewer(final HologramSpec spec, final @Nullable UUID viewer, final boolean ownerOnline, final boolean hasFrames,
			final OwnSource own) {
		boolean isOwner = viewer != null && viewer.equals(spec.owner());
		return decide(spec, ownerOnline, isOwner, spec.privacy().canView(spec.owner(), viewer), hasFrames, own);
	}

	private static State decide(final HologramSpec spec, final boolean ownerOnline, final boolean isOwner, final boolean canView,
			final boolean hasFrames, final OwnSource own) {
		if (!canView) {
			return State.PRIVATE_FROSTED;
		}
		if (!ownerOnline) {
			return State.OFFLINE;
		}
		if (isOwner) {
			if (own == OwnSource.NEEDS_HELPER) {
				return State.NEEDS_HELPER;
			}
			if (needsWindowPick(spec)) {
				return State.CONNECTING;
			}
			if (own == OwnSource.WINDOW_CLOSED && spec.mode() == Mode.WINDOW) {
				return State.WINDOW_CLOSED;
			}
		}
		return hasFrames ? State.LIVE : State.CONNECTING;
	}

	/** WINDOW mode with no window chosen yet. */
	public static boolean needsWindowPick(final HologramSpec spec) {
		return spec.mode() == Mode.WINDOW && (spec.windowKey() == null || spec.windowKey().isBlank());
	}

	/** Placeholder text for {@code state}; null for LIVE (the screen itself is shown). */
	public static @Nullable Label label(final State state, final HologramSpec spec, final boolean isOwner, final String ownerName) {
		String owner = ownerName == null ? "" : ownerName;
		return switch (state) {
			case LIVE -> null;
			case CONNECTING -> isOwner && needsWindowPick(spec)
					? new Label("Pick a window", "Right-click the emitter", Icon.WINDOW)
					: new Label("Connecting…", isOwner ? "" : owner, Icon.NONE);
			case PRIVATE_FROSTED -> new Label("Private", owner, Icon.LOCK);
			case OFFLINE -> new Label("Offline", owner, Icon.POWER);
			case WINDOW_CLOSED -> new Label("Window closed", isOwner ? "Pick another in settings" : "", Icon.WINDOW);
			case NEEDS_HELPER -> new Label("Set up screens", "See the Office Handbook", Icon.DOWNLOAD);
		};
	}

	/** Scanline strength: 0 within {@link #SCANLINE_NEAR} blocks or while zoomed in, 0.10 from one block further out. */
	public static double scanlineAlpha(final double distance, final boolean zoomed) {
		if (zoomed) {
			return 0.0;
		}
		double t = HologramPlacement.clamp(distance - SCANLINE_NEAR, 0.0, 1.0);
		return SCANLINE_ALPHA * t;
	}

	/** Opacity of the content quad: a live screen is fully opaque up close and only slightly see-through from afar. */
	public static double contentAlpha(final State state, final double distance) {
		if (state != State.LIVE) {
			return 1.0; // placeholder textures carry their own translucency
		}
		double t = HologramPlacement.clamp((distance - 3.0) / 5.0, 0.0, 1.0);
		return 1.0 - 0.10 * t;
	}
}
