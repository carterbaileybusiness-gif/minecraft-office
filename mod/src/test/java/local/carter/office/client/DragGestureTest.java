package local.carter.office.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import local.carter.office.client.interact.DragArming;
import local.carter.office.client.interact.SendThrottle;

/** Tap-vs-drag arming and the 100 ms send throttle for live resizing. */
class DragGestureTest {
	@Test
	void quickTapNeverBecomesADrag() {
		DragArming a = new DragArming(1000);
		assertFalse(a.update(1050, 0.0));
		assertFalse(a.update(1149, 0.03), "small jitter under 150 ms is still a tap");
		assertFalse(a.armed());
	}

	@Test
	void holdingOrMovingStartsTheDrag() {
		DragArming held = new DragArming(1000);
		assertTrue(held.update(1150, 0.0), "held 150 ms");
		DragArming moved = new DragArming(1000);
		assertTrue(moved.update(1020, 0.05), "moved more than 0.04 blocks");
		assertTrue(moved.update(1030, 0.0), "stays armed");
	}

	@Test
	void throttleSendsAtMostEvery100msAndAlwaysTheLastValue() {
		SendThrottle t = new SendThrottle();
		assertTrue(t.offer(0), "first change goes out at once");
		assertFalse(t.offer(30), "held back");
		assertFalse(t.offer(60), "still held back");
		assertFalse(t.poll(90));
		assertTrue(t.pending());
		assertTrue(t.poll(100), "final value sent once 100 ms passed");
		assertFalse(t.pending());
		assertFalse(t.poll(500), "nothing new, nothing sent");
		assertTrue(t.offer(500));
	}
}
