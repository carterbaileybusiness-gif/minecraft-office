package local.carter.office.client.screens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import local.carter.office.client.screens.DisplayMapModel.Choice;
import local.carter.office.core.Mode;
import local.carter.office.core.Shape;

/** What clicks and drags on the display map do, and what its status line says. */
class DisplayMapModelTest {
	private static final Screen MAIN = new Screen("MAIN", 0, true, "MacBook", 0, 0, 1440, 900);
	private static final Screen LG = new Screen("LG", 0, false, "LG", -1920, -180, 1920, 1080);
	private static final Screen OFFICE_1 = new Screen("O1", 1, false, null, 1440, 0, 1920, 1080);
	private static final Screen OFFICE_2 = new Screen("O2", 2, false, null, 3360, 0, 1920, 1080);

	private static ScreenList list(final ScreenList.Abilities a, final Screen... s) {
		return new ScreenList(List.of(s), a);
	}

	private static final ScreenList ALL = list(ScreenList.Abilities.ALL, MAIN, LG, OFFICE_1, OFFICE_2);
	private static final ScreenList.Abilities OLD_HELPER =
			new ScreenList.Abilities(false, false, false, false, ScreenList.UPDATE_HELPER_HINT);

	private static DisplayMapModel extend(final ScreenList l) {
		return new DisplayMapModel(l, Mode.EXTEND, 1, null, Shape.WIDE_16_9);
	}

	private static DisplayMapModel mirror(final ScreenList l, final String monitor) {
		return new DisplayMapModel(l, Mode.MIRROR, 1, monitor, Shape.WIDE_16_9);
	}

	@Test
	void extendClicks() {
		DisplayMapModel m = extend(ALL);
		assertEquals(new Choice.Slot(2), m.click("O2"));
		assertEquals(List.of(3, 4), m.ghosts().stream().map(Screen::slot).toList(), "slots 3 and 4 don't exist yet");
		assertEquals(new Choice.Slot(3), m.click(DisplayMapModel.GHOST + 3));
		assertEquals(new Choice.None(), m.click("LG"), "a real monitor isn't an Office display");
		assertEquals("O1", m.shownId());
		m.reset(Mode.EXTEND, 3, null, Shape.WIDE_16_9);
		assertEquals(DisplayMapModel.GHOST + 3, m.shownId(), "a not-yet-existing slot shows dashed and framed");
		Screen ghost = m.ghosts().get(0);
		assertTrue(ghost.x() > OFFICE_2.x() + OFFICE_2.w(), "right of the rightmost screen");
		assertEquals(MAIN.y(), ghost.y(), "top-aligned with main");
		assertEquals(1920, ghost.w());
	}

	@Test
	void mirrorClicks() {
		DisplayMapModel m = mirror(ALL, null);
		assertEquals(new Choice.Monitor("LG"), m.click("LG"), "B");
		assertEquals(new Choice.Monitor(null), m.click("MAIN"), "A = the main display");
		assertEquals(new Choice.None(), m.click("O1"), "Office displays are inert in Mirror");
		assertTrue(m.ghosts().isEmpty(), "no dashed slots in Mirror");
		assertEquals("MAIN", m.shownId());
		assertEquals("LG", mirror(ALL, "LG").shownId());
		assertEquals(new Choice.None(), mirror(list(OLD_HELPER, MAIN, LG), null).click("LG"), "greyed: no pick");
	}

	@Test
	void dragsOnlyMoveOfficeDisplaysInExtendWithCanMove() {
		DisplayMapModel m = extend(ALL);
		assertFalse(m.drag("LG", 0, 0), "real monitors never move");
		assertFalse(m.drag("MAIN", 0, 0));
		assertFalse(mirror(ALL, null).drag("O1", 0, 0), "not in Mirror");
		assertFalse(extend(list(OLD_HELPER, MAIN, OFFICE_1)).drag("O1", 0, 0), "not without canMove");
		assertFalse(m.canApply(), "nothing dropped yet");
		assertTrue(m.drag("O2", 300, -1000));
		assertEquals(300, m.screens().stream().filter(s -> s.id().equals("O2")).findFirst().orElseThrow().x(), "preview follows");
		assertFalse(m.canApply(), "a drag isn't a drop");
		assertTrue(m.drop("O2"));
		assertEquals(List.of(new Origin(2, 300, -1080)), m.pending(), "snapped onto main's top edge");
		assertTrue(m.canApply());
		assertFalse(extend(list(OLD_HELPER, MAIN, OFFICE_1)).canApply());
	}

	@Test
	void aDropThatWouldStrandADisplayTakesTheNearestSpotThatDoesnt() {
		DisplayMapModel m = extend(ALL);
		// O1 is O2's only link to a real monitor: above main (the nearest spot) would leave O2 floating, so O1 goes to the
		// nearest spot that still joins O2 to main: between them, slid up as far as sharing 10 % of its edge allows
		assertTrue(m.drag("O1", 0, -1100));
		assertTrue(m.drop("O1"));
		assertEquals(List.of(new Origin(1, 1440, -972)), m.pending());
		// dropping it back where it is: nothing to Apply
		assertTrue(m.drag("O1", 1450, 10));
		assertTrue(m.drop("O1"));
		assertTrue(m.pending().isEmpty());
		assertFalse(m.canApply());
		assertTrue(m.drag("O1", 1450, 10));
		assertFalse(m.drop("O1"), "already there");
	}

	@Test
	void aDropNeverCutsAMonitorOff() {
		// shared vector V7: main | O1 | monitor DELL, attached only through O1
		Screen dell = new Screen("DELL", 0, false, "DELL", 3360, 0, 1920, 1080);
		DisplayMapModel m = extend(list(ScreenList.Abilities.ALL, MAIN, OFFICE_1, dell));
		assertTrue(m.drag("O1", -1900, 0));
		assertFalse(m.drop("O1"), "left of main would cut DELL off; the only spot joining DELL is where O1 is");
		assertTrue(m.pending().isEmpty());
	}

	@Test
	void anOfficeDisplayMadeMainNeverMoves() {
		Screen officeMain = new Screen("O1", 1, true, null, 0, 0, 1920, 1080);
		Screen built = new Screen("MB", 0, false, "MacBook", -1440, 0, 1440, 900);
		DisplayMapModel m = extend(list(ScreenList.Abilities.ALL, officeMain, built, OFFICE_2.at(1920, 0)));
		assertFalse(m.canDrag("O1"), "the main display");
		assertFalse(m.drag("O1", 0, -2000));
		assertTrue(m.canDrag("O2"));
		assertTrue(m.drag("O2", 0, -1100));
		assertTrue(m.drop("O2"));
		assertEquals(List.of(new Origin(2, 0, -1080)), m.pending(), "joined to the Office main display");
	}

	@Test
	void applyShowsApplyingThenTheAnswer() {
		DisplayMapModel m = extend(ALL);
		m.drag("O2", 300, -1000);
		m.drop("O2");
		m.applied(1000);
		assertEquals(DisplayMapModel.APPLYING, m.status());
		assertFalse(m.canApply());
		assertFalse(m.canDrag("O1"), "drags wait for the helper");
		assertFalse(m.tick(2000), "still waiting at 1 s");

		// refused: the helper's short reason for 3 s, the move snaps back
		assertTrue(m.failed("display 2 must touch a screen", 2100));
		assertEquals("Display 2 must touch a screen", m.status());
		assertTrue(m.pending().isEmpty());
		assertTrue(m.canDrag("O1"));
		m.update(ALL); // the helper's DISPLAYS follows its ERROR
		assertEquals("Display 2 must touch a screen", m.status(), "still shown after the screen list");
		m.tick(5099);
		assertEquals("Display 2 must touch a screen", m.status());
		m.tick(5100);
		assertNull(m.status(), "gone after 3 s");
		assertFalse(m.failed("bad request", 6000), "an error with no Apply waiting is someone else's");
		assertNull(m.status());
		assertEquals(DisplayMapModel.COULD_NOT_MOVE, DisplayMapModel.failureText("macOS refused the new arrangement and more"));
		assertEquals("macOS refused it", DisplayMapModel.failureText("macOS refused it"));
		assertEquals(DisplayMapModel.COULD_NOT_MOVE, DisplayMapModel.failureText(null));
	}

	@Test
	void anUnansweredApplyTimesOut() {
		DisplayMapModel m = extend(ALL);
		m.drag("O2", 300, -1000);
		m.drop("O2");
		m.applied(1000);
		assertTrue(m.tick(1000 + DisplayMapModel.APPLY_TIMEOUT_MS));
		assertFalse(m.applying());
		assertEquals(DisplayMapModel.COULD_NOT_MOVE, m.status());
		assertTrue(m.canDrag("O1"), "drags unlock");
		assertTrue(m.canApply(), "the move stays, to Apply again");
	}

	@Test
	void updateDropsMovesOfDisplaysThatAreGone() {
		DisplayMapModel m = extend(ALL);
		m.drag("O2", 100, -1000);
		m.drop("O2");
		m.update(list(ScreenList.Abilities.ALL, MAIN, LG, OFFICE_1));
		assertTrue(m.pending().isEmpty(), "display 2 was removed");
		m.drag("O1", 0, -1100);
		m.drop("O1");
		assertEquals(1, m.pending().size());
		m.applied(0);
		m.update(list(ScreenList.Abilities.ALL, MAIN, LG, OFFICE_1.at(0, -1080)));
		assertTrue(m.pending().isEmpty(), "the helper's answer replaces the pending move");
		assertEquals(-1080, m.screens().stream().filter(s -> s.id().equals("O1")).findFirst().orElseThrow().y());
	}

	@Test
	void statusLine() {
		assertEquals("That screen isn't plugged in — showing A", mirror(list(ScreenList.Abilities.ALL, MAIN, OFFICE_1), "LG").status());
		assertNull(mirror(ALL, "LG").status());
		assertNull(mirror(ALL, null).status());
		assertEquals("Restart Minecraft to update the helper", extend(list(OLD_HELPER, MAIN, OFFICE_1)).status());
		assertEquals("Restart Minecraft to update the helper", mirror(list(OLD_HELPER, MAIN), "LG").status(),
				"an old helper can't tell what's plugged in: the hint");
		assertNull(extend(ALL).status());
		assertEquals("A", mirror(ALL, null).label(MAIN));
		assertEquals("B", mirror(ALL, null).label(LG));
		assertEquals("2", extend(ALL).label(OFFICE_2));
	}
}
