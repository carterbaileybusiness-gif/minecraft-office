package local.carter.office.client.pointer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import local.carter.office.client.helper.HelperProtocol.PointerButton;
import local.carter.office.client.pointer.PointerGesture.State;

/** Left press on a hologram: a click (typing mode) or, once the crosshair moves, a drag the helper drives. */
class PointerGestureTest {
	private final List<String> out = new ArrayList<>();
	private final PointerGesture g = new PointerGesture(new PointerGesture.Out() {
		@Override
		public void down(final PointerPoint p) {
			out.add("down " + p);
		}

		@Override
		public void move(final PointerPoint p) {
			out.add("move " + p);
		}

		@Override
		public void up(final PointerPoint p) {
			out.add("up " + p);
		}

		@Override
		public void click(final PointerPoint p, final PointerButton b) {
			out.add("click " + b + " " + p);
		}
	});

	private static PointerPoint p(final int slot, final int x, final int y) {
		return new PointerPoint(slot, x, y);
	}

	@Test
	void aPressReleasedWithoutMovingIsAClickAtThePressPoint() {
		assertTrue(g.press(p(1, 100, 100), 0));
		g.aim(p(1, 103, 102), 10); // a little wobble is still a click
		assertEquals(State.PRESSED, g.state());
		g.release(true, 400);
		assertEquals(List.of("click LEFT " + p(1, 100, 100)), out);
		assertEquals(State.IDLE, g.state());
	}

	@Test
	void movingPastTheThresholdStartsADragAtThePressPointThenFollowsTheCrosshair() {
		g.press(p(1, 100, 100), 0);
		g.aim(p(1, 110, 100), 16);
		assertEquals(State.DRAGGING, g.state());
		g.aim(p(1, 110, 100), 32); // same pixel: nothing new
		g.aim(p(1, 300, 200), 48);
		assertEquals(List.of("down " + p(1, 100, 100), "move " + p(1, 110, 100), "move " + p(1, 300, 200)), out);
		assertEquals(p(1, 300, 200), g.point());
	}

	@Test
	void aDragCarriesOnAcrossHologramsAndKeepsTheLastPointBetweenThem() {
		g.press(p(1, 1900, 500), 0);
		g.aim(p(1, 1919, 500), 16);
		g.aim(null, 32); // crosshair in the gap between two holograms
		g.aim(null, 48);
		g.aim(p(2, 5, 510), 64); // and onto hologram 2
		assertEquals(List.of("down " + p(1, 1900, 500), "move " + p(1, 1919, 500), "move " + p(2, 5, 510)), out);
		assertEquals(p(2, 5, 510), g.point());
	}

	@Test
	void aimingAtAnotherHologramRightAwayIsADragToo() {
		g.press(p(1, 10, 10), 0);
		g.aim(p(2, 10, 10), 16);
		assertEquals(List.of("down " + p(1, 10, 10), "move " + p(2, 10, 10)), out);
	}

	@Test
	void leavingEveryHologramWhilePressedStartsTheDragAtThePressPoint() {
		g.press(p(1, 10, 10), 0);
		g.aim(null, 16);
		assertEquals(State.DRAGGING, g.state());
		assertEquals(List.of("down " + p(1, 10, 10)), out);
		assertEquals(p(1, 10, 10), g.point());
	}

	@Test
	void theHelperEndsTheDragNotMinecraftsKeyState() {
		g.press(p(1, 10, 10), 0);
		g.aim(p(1, 50, 10), 16);
		g.release(false, 100); // Minecraft lost focus: its key state says "up", the helper holds the real button
		g.tick(5_000);
		assertEquals(State.DRAGGING, g.state());
		g.dragEnded();
		assertEquals(State.IDLE, g.state());
		assertEquals(List.of("down " + p(1, 10, 10), "move " + p(1, 50, 10)), out, "no up from the mod: the helper posted it");
	}

	@Test
	void aDragTheHelperNeverTookOverIsCancelledAfterASecond() {
		g.press(p(1, 10, 10), 0);
		g.aim(p(1, 50, 10), 16);
		g.release(true, 100); // released while Minecraft still has the mouse
		g.tick(1_000);
		assertEquals(State.DRAGGING, g.state());
		g.tick(1_101);
		assertEquals(State.IDLE, g.state());
		assertEquals("up " + p(1, 50, 10), out.get(out.size() - 1));
	}

	@Test
	void rightClickIsAnImmediateRightClickAndNeverDuringADrag() {
		g.rightClick(p(2, 7, 8));
		assertEquals(List.of("click RIGHT " + p(2, 7, 8)), out);
		g.press(p(1, 0, 0), 0);
		g.aim(p(1, 50, 0), 16);
		g.rightClick(p(1, 60, 0));
		assertEquals(3, out.size());
	}

	@Test
	void cancelReleasesADragAndForgetsAPress() {
		g.press(p(1, 0, 0), 0);
		g.cancel();
		assertEquals(State.IDLE, g.state());
		assertTrue(out.isEmpty(), "nothing was pressed in the app yet");
		g.press(p(1, 0, 0), 0);
		g.aim(p(1, 40, 0), 16);
		g.cancel();
		assertEquals("up " + p(1, 40, 0), out.get(out.size() - 1));
		assertFalse(g.busy());
	}

	@Test
	void aSecondPressWhileBusyIsIgnored() {
		g.press(p(1, 0, 0), 0);
		assertFalse(g.press(p(1, 500, 500), 5));
		g.release(true, 10);
		assertEquals(List.of("click LEFT " + p(1, 0, 0)), out);
	}
}
