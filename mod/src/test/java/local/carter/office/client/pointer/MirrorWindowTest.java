package local.carter.office.client.pointer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import local.carter.office.client.pointer.MirrorWindow.Layer;

/** Minecraft's window: floating + click-through exactly while working (or a Mirror scroll's moment) — and every way out. */
class MirrorWindowTest {
	@Test
	void outsideAWorldAlwaysNormalEvenIfAskedToWork() {
		MirrorWindow w = new MirrorWindow();
		w.workBegan();
		w.mirrorScrolled(0);
		assertEquals(Layer.NORMAL, w.layer(10));
		assertFalse(w.clickThrough(10));
	}

	@Test
	void inAWorldNormalUntilWorkModeThenFloatingAndClickThroughUntilItEnds() {
		MirrorWindow w = new MirrorWindow();
		w.enterWorld();
		assertEquals(Layer.NORMAL, w.layer(0), "playing: an ordinary window (Cmd-Tab shows other apps normally)");
		w.workBegan(); // the click on a hologram
		assertEquals(Layer.WORKING, w.layer(1));
		assertEquals(new Layer(true, true), w.layer(60_000), "however long the player works");
		w.workEnded(); // Esc / ⌘-Tab back to Minecraft / helper gone / timeout
		assertEquals(Layer.NORMAL, w.layer(60_001), "Esc: back to normal");
	}

	@Test
	void leavingTheWorldWhileWorkingEndsIt() {
		MirrorWindow w = new MirrorWindow();
		w.enterWorld();
		w.workBegan();
		w.leaveWorld();
		assertEquals(Layer.NORMAL, w.layer(1));
		w.workBegan();
		assertEquals(Layer.NORMAL, w.layer(2), "no work mode outside a world");
		w.enterWorld();
		assertEquals(Layer.NORMAL, w.layer(3), "rejoining starts clean");
	}

	@Test
	void aMirrorScrollIsAMomentOfClickThrough() {
		MirrorWindow w = new MirrorWindow();
		w.enterWorld();
		w.mirrorScrolled(1_000);
		assertTrue(w.clickThrough(1_000));
		assertEquals(Layer.WORKING, w.layer(1_000 + MirrorWindow.SCROLL_MS - 1));
		assertEquals(Layer.NORMAL, w.layer(1_000 + MirrorWindow.SCROLL_MS), "over after SCROLL_MS");
		w.mirrorScrolled(2_000);
		w.mirrorScrolled(2_300);
		assertTrue(w.clickThrough(2_600), "the wheel keeps going: extended");
		w.leaveWorld();
		assertFalse(w.clickThrough(2_301));
	}
}
