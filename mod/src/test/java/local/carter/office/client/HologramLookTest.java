package local.carter.office.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import local.carter.office.client.render.HologramLook;
import local.carter.office.client.render.HologramLook.Icon;
import local.carter.office.client.render.HologramLook.OwnSource;
import local.carter.office.client.render.HologramLook.State;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;
import local.carter.office.core.Privacy;
import local.carter.office.core.Shape;

class HologramLookTest {
	private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
	private static final UUID FRIEND = UUID.fromString("00000000-0000-0000-0000-00000000000b");
	private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-00000000000c");

	private static HologramSpec spec(Privacy privacy, Mode mode, String windowKey) {
		return new HologramSpec(OWNER, Shape.WIDE_16_9, mode, privacy, 1, windowKey, 2.0, 0, 1.2, 0, 0f);
	}

	private static HologramSpec spec(Privacy privacy) {
		return spec(privacy, Mode.EXTEND, null);
	}

	@Test
	void privateNotOwnerIsFrostedRegardlessOfFrames() {
		HologramSpec s = spec(Privacy.PRIVATE);
		assertEquals(State.PRIVATE_FROSTED, HologramLook.forViewer(s, true, false, true));
		assertEquals(State.PRIVATE_FROSTED, HologramLook.forViewer(s, true, false, false));
		// ...and regardless of the owner being online.
		assertEquals(State.PRIVATE_FROSTED, HologramLook.forViewer(s, false, false, true));
	}

	@Test
	void privateOwnerSeesOwnScreen() {
		HologramSpec s = spec(Privacy.PRIVATE);
		assertEquals(State.LIVE, HologramLook.forViewer(s, true, true, true));
		assertEquals(State.CONNECTING, HologramLook.forViewer(s, true, true, false));
	}

	@Test
	void publicFollowsFramesAndOwnerPresence() {
		HologramSpec s = spec(Privacy.PUBLIC);
		assertEquals(State.LIVE, HologramLook.forViewer(s, true, false, true));
		assertEquals(State.CONNECTING, HologramLook.forViewer(s, true, false, false));
		assertEquals(State.OFFLINE, HologramLook.forViewer(s, false, false, true));
		assertEquals(State.OFFLINE, HologramLook.forViewer(s, false, false, false));
	}

	@Test
	void showToUsesTheViewersUuid() {
		HologramSpec s = spec(new Privacy(Privacy.Level.SHOW_TO, Set.of(FRIEND)));
		assertEquals(State.LIVE, HologramLook.forViewer(s, FRIEND, true, true, OwnSource.READY));
		assertEquals(State.PRIVATE_FROSTED, HologramLook.forViewer(s, STRANGER, true, true, OwnSource.READY));
		assertEquals(State.LIVE, HologramLook.forViewer(s, OWNER, true, true, OwnSource.READY));
		// The four-argument form has no viewer: a non-owner of a SHOW_TO hologram is not assumed allowed.
		assertEquals(State.PRIVATE_FROSTED, HologramLook.forViewer(s, true, false, true));
	}

	@Test
	void windowModeWithoutWindowAsksOwnerToPick() {
		HologramSpec s = spec(Privacy.PUBLIC, Mode.WINDOW, null);
		assertEquals(State.CONNECTING, HologramLook.forViewer(s, OWNER, true, true, OwnSource.READY));
		HologramLook.Label owner = HologramLook.label(State.CONNECTING, s, true, "Carter");
		assertEquals("Pick a window", owner.title());
		assertEquals(Icon.WINDOW, owner.icon());
		// Other players only see a neutral "Connecting" panel.
		HologramLook.Label other = HologramLook.label(State.CONNECTING, s, false, "Carter");
		assertEquals("Connecting…", other.title());
	}

	@Test
	void ownerSourceProblemsShowOnlyToOwner() {
		HologramSpec ext = spec(Privacy.PUBLIC);
		HologramSpec win = spec(Privacy.PUBLIC, Mode.WINDOW, "Notes:1");
		assertEquals(State.NEEDS_HELPER, HologramLook.forViewer(ext, OWNER, true, false, OwnSource.NEEDS_HELPER));
		assertEquals(State.WINDOW_CLOSED, HologramLook.forViewer(win, OWNER, true, false, OwnSource.WINDOW_CLOSED));
		assertEquals(State.CONNECTING, HologramLook.forViewer(win, FRIEND, true, false, OwnSource.WINDOW_CLOSED));
		assertEquals("Set up screens", HologramLook.label(State.NEEDS_HELPER, ext, true, "Carter").title());
	}

	@Test
	void labelsNameTheOwner() {
		HologramSpec s = spec(Privacy.PRIVATE);
		HologramLook.Label frosted = HologramLook.label(State.PRIVATE_FROSTED, s, false, "Carter");
		assertEquals("Private", frosted.title());
		assertEquals("Carter", frosted.subtitle());
		assertEquals(Icon.LOCK, frosted.icon());
		HologramLook.Label off = HologramLook.label(State.OFFLINE, s, false, "Carter");
		assertEquals("Offline", off.title());
		assertEquals("Carter", off.subtitle());
		assertEquals(Icon.POWER, off.icon());
		assertNull(HologramLook.label(State.LIVE, s, false, "Carter"));
	}

	@Test
	void scanlinesFadeOutCloseAndWhenZoomed() {
		assertEquals(0.0, HologramLook.scanlineAlpha(1.0, false), 1e-9);
		assertEquals(0.0, HologramLook.scanlineAlpha(3.0, false), 1e-9);
		assertEquals(0.10, HologramLook.scanlineAlpha(6.0, false), 1e-9);
		assertEquals(0.10, HologramLook.scanlineAlpha(40.0, false), 1e-9);
		double mid = HologramLook.scanlineAlpha(3.5, false);
		assertTrue(mid > 0.0 && mid < 0.10, "fades between 3 and 4 blocks: " + mid);
		assertEquals(0.0, HologramLook.scanlineAlpha(10.0, true), 1e-9);
	}

	@Test
	void liveContentIsOpaqueUpClose() {
		assertEquals(1.0, HologramLook.contentAlpha(State.LIVE, 2.0, false), 1e-9);
		assertEquals(1.0, HologramLook.contentAlpha(State.LIVE, 3.0, false), 1e-9);
		assertTrue(HologramLook.contentAlpha(State.LIVE, 15.0, false) < 1.0, "slightly translucent from afar");
		assertTrue(HologramLook.contentAlpha(State.LIVE, 15.0, false) >= 0.85, "never washed out");
	}

	@Test
	void zoomedContentIsFullyOpaqueAtAnyDistance() {
		assertEquals(1.0, HologramLook.contentAlpha(State.LIVE, 15.0, true), 1e-9);
		assertEquals(1.0, HologramLook.contentAlpha(State.LIVE, 6.0, true), 1e-9);
	}
}
