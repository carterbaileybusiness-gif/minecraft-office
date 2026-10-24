package local.carter.office.client.platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A Mirror while Minecraft is in macOS's own full-screen Space: one chat hint, again only after leaving that mode. */
class MirrorHintTest {
	@Test
	void onceWhileInItsOwnSpaceWithAMirrorAgainAfterLeavingIt() {
		MirrorHint h = new MirrorHint();
		assertFalse(h.update(false, true), "in a window: nothing to say");
		assertFalse(h.update(true, false), "own Space but no Mirror of yours here: nothing");
		assertTrue(h.update(true, true), "a Mirror placed / loaded while in its own Space: the hint");
		assertFalse(h.update(true, true), "once");
		assertFalse(h.update(false, true), "back in a window");
		assertTrue(h.update(true, true), "in its own Space again: said again");
		h.leftWorld();
		assertTrue(h.update(true, true), "a new world: said again");
		assertTrue(MirrorHint.TEXT.contains("Options → Video"), MirrorHint.TEXT);
	}
}
