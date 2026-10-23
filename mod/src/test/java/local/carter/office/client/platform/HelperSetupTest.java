package local.carter.office.client.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import local.carter.office.client.platform.HelperSetup.Action;
import local.carter.office.client.platform.HelperSetup.State;

class HelperSetupTest {
	private static final Path APP = Path.of("/Users/x/Library/Application Support/Minecraft Office/OfficeHelper.app");

	/** Through "Yes": the download is running in session {@code s.session()}. */
	private static HelperSetup downloading() {
		HelperSetup s = new HelperSetup();
		assertEquals(Action.ASK, s.ensure(Optional.empty(), false));
		assertTrue(s.manifestRead(s.session(), true));
		assertTrue(s.answered(s.session(), true));
		assertEquals(State.INSTALLING, s.state());
		return s;
	}

	@Test
	void installedHelperStartsWithoutAsking() {
		HelperSetup s = new HelperSetup();
		assertEquals(Action.START, s.ensure(Optional.of(APP), false));
		assertEquals(APP, s.app());
		assertEquals(Action.START, s.ensure(Optional.empty(), false));
	}

	@Test
	void downloadInTheSameWorldStartsTheHelper() {
		HelperSetup s = downloading();
		assertEquals(Action.START, s.installFinished(s.session(), APP));
		assertEquals(State.READY, s.state());
	}

	@Test
	void leavingDuringTheDownloadRecordsTheInstallWithoutStarting() {
		HelperSetup s = downloading();
		int started = s.session();
		s.leftWorld();
		assertEquals(State.INSTALLING, s.state(), "the download keeps going");

		assertEquals(Action.NONE, s.installFinished(started, APP), "no helper for a world the player left");
		assertEquals(State.READY, s.state(), "not stuck in INSTALLING");
		assertEquals(APP, s.app());
		// Next world: straight to START, no second download or question.
		assertEquals(Action.START, s.ensure(Optional.empty(), false));
	}

	@Test
	void rejoiningDuringTheDownloadStartsTheHelperWhenItLands() {
		HelperSetup s = downloading();
		int started = s.session();
		s.leftWorld();
		assertEquals(Action.NONE, s.ensure(Optional.empty(), false), "a hologram in the new world waits for the download");
		assertEquals(Action.START, s.installFinished(started, APP));
	}

	@Test
	void failedDownloadAfterLeavingAsksAgainNextTime() {
		HelperSetup s = downloading();
		int started = s.session();
		s.leftWorld();
		assertEquals(Action.NONE, s.installFinished(started, null), "not reported to a world the player left");
		assertEquals(State.UNKNOWN, s.state());
		assertEquals(Action.ASK, s.ensure(Optional.empty(), false));
	}

	@Test
	void failedDownloadInTheSameWorldIsReportedAndRetriable() {
		HelperSetup s = downloading();
		assertEquals(Action.REPORT_FAILURE, s.installFinished(s.session(), null));
		assertEquals(State.FAILED, s.state());
		assertEquals(Action.NONE, s.ensure(Optional.empty(), false), "no nagging until the player asks");
		s.retry();
		assertEquals(Action.ASK, s.ensure(Optional.empty(), false));
	}

	@Test
	void notNowLastsOneWorld() {
		HelperSetup s = new HelperSetup();
		s.ensure(Optional.empty(), false);
		assertTrue(s.manifestRead(s.session(), true));
		assertFalse(s.answered(s.session(), false));
		assertEquals(State.DECLINED, s.state());
		assertEquals(Action.NONE, s.ensure(Optional.empty(), false));
		s.leftWorld();
		assertEquals(Action.ASK, s.ensure(Optional.empty(), false));
	}

	@Test
	void answersFromAWorldThePlayerLeftAreIgnored() {
		HelperSetup s = new HelperSetup();
		s.ensure(Optional.empty(), false);
		int asked = s.session();
		s.leftWorld();
		assertFalse(s.manifestRead(asked, true), "no confirm screen for the old world");
		assertFalse(s.answered(asked, true), "no download for the old world");
		assertEquals(State.UNKNOWN, s.state());
	}

	@Test
	void badOverrideIsReportedOnce() {
		HelperSetup s = new HelperSetup();
		assertEquals(Action.BAD_OVERRIDE, s.ensure(Optional.empty(), true));
		assertEquals(Action.NONE, s.ensure(Optional.empty(), true));
	}
}
