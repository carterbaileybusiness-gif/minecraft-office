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
	void aDropThatWouldStrandADisplayMovesNothing() {
		DisplayMapModel m = extend(ALL);
		// O1 is O2's only link to a real monitor: dropping O1 above main would leave O2 floating
		assertTrue(m.drag("O1", 0, -1100));
		assertFalse(m.drop("O1"));
		assertTrue(m.pending().isEmpty());
		// dropping it back where it is: nothing to Apply
		assertTrue(m.drag("O1", 1450, 10));
		assertFalse(m.drop("O1"));
		assertFalse(m.canApply());
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
		m.applied();
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
