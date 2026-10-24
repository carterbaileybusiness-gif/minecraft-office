package local.carter.office.client.screens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Letters for real monitors (A = main, then by origin) and which monitor a Mirror really copies. */
class ScreenListTest {
	static final Screen MAIN = new Screen("MAIN", 0, true, "MacBook", 0, 0, 1440, 900);
	static final Screen LG = new Screen("LG", 0, false, "LG", -1920, -180, 1920, 1080);
	static final Screen DELL = new Screen("DELL", 0, false, "Dell", 1440, -1080, 1920, 1080);
	static final Screen SIDE = new Screen("SIDE", 0, false, "Side", 1440, 0, 1080, 1920);
	static final Screen OFFICE_1 = new Screen("OFFICE-1", 1, false, null, 2520, 0, 1920, 1080);

	private static ScreenList list(final Screen... s) {
		return new ScreenList(List.of(s), ScreenList.Abilities.ALL);
	}

	@Test
	void lettersByOrigin() {
		ScreenList l = list(OFFICE_1, SIDE, DELL, LG, MAIN);
		assertEquals(Map.of("MAIN", 'A', "LG", 'B', "DELL", 'C', "SIDE", 'D'), l.letters());
		assertEquals(List.of("MAIN", "LG", "DELL", "SIDE"), List.copyOf(l.letters().keySet()), "letter order");
		assertNull(l.letters().get("OFFICE-1"), "Office displays keep their number");
		assertEquals("1", l.label(OFFICE_1));
		assertEquals("B", l.label(LG));
		assertEquals(MAIN, l.main());
	}

	@Test
	void resolveMirror() {
		ScreenList l = list(MAIN, LG, OFFICE_1);
		assertNull(l.resolveMirror(null), "nothing chosen: main");
		assertNull(l.resolveMirror("MAIN"), "main chosen: main");
		assertEquals("LG", l.resolveMirror("LG"));
		assertNull(list(MAIN, OFFICE_1).resolveMirror("LG"), "unplugged: main");
		assertNull(l.resolveMirror("OFFICE-1"), "an Office display is not a monitor");
	}

	@Test
	void mainOnlyIsOneGreyedScreen() {
		ScreenList l = ScreenList.mainOnly();
		assertEquals(1, l.screens().size());
		assertEquals("main", l.main().id());
		assertEquals(Map.of("main", 'A'), l.letters());
		assertEquals(ScreenList.Abilities.NONE, l.abilities());
	}

	@Test
	void shortNames() {
		assertEquals("MacBook", SdlScreens.shortName("Built-in Retina Display"));
		assertEquals("LG", SdlScreens.shortName("LG HDR 4K"));
		assertEquals("Screen", SdlScreens.shortName(""));
		assertEquals("Supercalifra", SdlScreens.shortName("Supercalifragilistic 27"));
		assertEquals(2, SdlScreens.officeSlot("Office Display 2"));
		assertEquals(0, SdlScreens.officeSlot("Office Display 9"));
		assertEquals(0, SdlScreens.officeSlot("DELL U2720Q"));
	}
}
