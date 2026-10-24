package local.carter.office.client.pointer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import local.carter.office.client.pointer.MirrorWindow.Layer;

/** Minecraft's window: floating while in a world, click-through only while the Mirror is used — and every way out. */
class MirrorWindowTest {
	private static final Layer FLOAT = new Layer(true, false);
	private static final Layer THROUGH = new Layer(true, true);

	@Test
	void outsideAWorldNothingEvenWhilePressed() {
		MirrorWindow w = new MirrorWindow();
		w.mirrorPressed();
		w.mirrorScrolled(0);
		assertEquals(Layer.NORMAL, w.layer(10, true, true));
		assertFalse(w.clickThrough(10));
	}

	@Test
	void inAWorldFloatingWhileInFrontOrInUseNotWhenThePlayerWentElsewhere() {
		MirrorWindow w = new MirrorWindow();
		w.enterWorld();
		assertEquals(FLOAT, w.layer(0, true, false), "playing: above the apps the Mirror shows");
		assertEquals(Layer.NORMAL, w.layer(0, false, false), "the player switched to another app themselves: it shows normally");
		assertEquals(FLOAT, w.layer(0, false, true), "an Extend drag or typing took the focus: still on top");
	}

	@Test
	void aMirrorClickIsClickThroughUntilTypingEndsWithEsc() {
		MirrorWindow w = new MirrorWindow();
		w.enterWorld();
		w.mirrorPressed(); // CLICK about to go out
		assertEquals(THROUGH, w.layer(0, true, true));
		assertEquals(THROUGH, w.layer(500, false, true), "typing in the app under Minecraft: still click-through");
		w.interactionEnded(); // TYPING_EXIT esc
		assertEquals(FLOAT, w.layer(600, true, false));
	}

	@Test
	void aMirrorDragIsClickThroughUntilItsRelease() {
		MirrorWindow w = new MirrorWindow();
		w.enterWorld();
		w.mirrorPressed(); // POINTER_DOWN about to go out
		assertEquals(THROUGH, w.layer(0, false, true));
		w.interactionEnded(); // DRAG_ENDED released
		assertEquals(FLOAT, w.layer(10, true, false));
	}

	@Test
	void everyOtherWayOutEndsClickThrough() {
		// typing timed out / the hint replaced / focus came back / helper died: the client sees no interaction any more
		MirrorWindow a = new MirrorWindow();
		a.enterWorld();
		a.mirrorPressed();
		assertEquals(new Layer(true, false), a.layer(5, true, false), "nothing going on: never left click-through");
		assertFalse(a.clickThrough(6), "and it stays off");
		// leaving the world (or a disconnect, or the port going away) in the middle of a drag
		MirrorWindow b = new MirrorWindow();
		b.enterWorld();
		b.mirrorPressed();
		b.leaveWorld();
		assertEquals(Layer.NORMAL, b.layer(1, true, true));
		b.mirrorPressed();
		assertEquals(Layer.NORMAL, b.layer(2, true, true), "no session starts outside a world");
		// rejoining starts clean
		b.enterWorld();
		assertEquals(FLOAT, b.layer(3, true, false));
	}

	@Test
	void aScrollIsAMomentOfClickThrough() {
		MirrorWindow w = new MirrorWindow();
		w.enterWorld();
		w.mirrorScrolled(1_000);
		assertTrue(w.clickThrough(1_000));
		assertEquals(THROUGH, w.layer(1_000 + MirrorWindow.SCROLL_MS - 1, true, false), "no press needed for a scroll");
		assertEquals(FLOAT, w.layer(1_000 + MirrorWindow.SCROLL_MS, true, false), "over after SCROLL_MS");
		w.mirrorScrolled(2_000);
		w.mirrorScrolled(2_300); // the wheel keeps going: extended
		assertTrue(w.clickThrough(2_600));
		w.leaveWorld();
		assertFalse(w.clickThrough(2_301));
	}
}
