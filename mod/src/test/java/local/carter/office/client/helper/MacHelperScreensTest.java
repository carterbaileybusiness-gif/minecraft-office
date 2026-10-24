package local.carter.office.client.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import local.carter.office.client.helper.HelperProtocol.Displays;
import local.carter.office.client.helper.HelperProtocol.Identify;
import local.carter.office.client.helper.HelperProtocol.IdentifyLabel;
import local.carter.office.client.helper.HelperProtocol.ListDisplays;
import local.carter.office.client.helper.HelperProtocol.Message;
import local.carter.office.client.helper.HelperProtocol.ScreenInfo;
import local.carter.office.client.helper.HelperProtocol.SetOrigins;
import local.carter.office.client.helper.HelperProtocol.SlotOrigin;
import local.carter.office.client.helper.HelperProtocol.Welcome;
import local.carter.office.client.screens.Origin;
import local.carter.office.client.screens.Screen;
import local.carter.office.client.screens.ScreenList;

/** The display map's helper state: gated on the "screens" feature both ways, DISPLAYS becomes the screen list. */
class MacHelperScreensTest {
	static final String MAIN = "37D8832A-2D66-02CA-B9F7-8F30A301B230";
	static final String LG = "9B2F1C44-5E6A-4D7B-8C9D-0E1F2A3B4C5D";
	static final String OFFICE = "5A1E0F00-0000-4000-8000-0000000000A1";
	static final Displays DISPLAYS = new Displays(List.of(
			new ScreenInfo(MAIN, 0, true, "MacBook", 0, 0, 1440, 900),
			new ScreenInfo(LG, 0, false, "LG", -1920, -180, 1920, 1080),
			new ScreenInfo(OFFICE, 1, false, null, 1440, 0, 1920, 1080)));

	private final List<Message> sent = new ArrayList<>();
	private final List<HelperConnection.Listener> listeners = new ArrayList<>();
	private MacHelper helper;

	@BeforeEach
	void setUp() {
		helper = new MacHelper(listener -> {
			listeners.add(listener);
			return new HelperChannel() {
				@Override
				public void start() {}

				@Override
				public boolean send(final Message m) {
					sent.add(m);
					return true;
				}

				@Override
				public void close() {}
			};
		}, Runnable::run, (task, delayMs) -> {});
		helper.setSetUp(true);
		helper.start();
	}

	private static Welcome welcome(final boolean screens) {
		return new Welcome(1, true, true, screens ? List.of(HelperProtocol.FEATURE_SCREENS) : null);
	}

	private boolean sentAny(final Class<? extends Message> type) {
		return sent.stream().anyMatch(type::isInstance);
	}

	@Test
	void oldHelperGetsNoNewMessages() {
		helper.connected(welcome(false));
		assertFalse(helper.supportsScreens());
		helper.listDisplays();
		assertFalse(helper.setOrigins(List.of(new Origin(1, 1440, 0))));
		assertFalse(helper.identify(Map.of(MAIN, "A")));
		assertFalse(sentAny(ListDisplays.class) || sentAny(SetOrigins.class) || sentAny(Identify.class), "sent " + sent);
		ScreenList.Abilities a = helper.screens().abilities();
		assertFalse(a.live() || a.canMove() || a.canPickMirror() || a.canIdentifyScreens(), "greyed");
		assertEquals("Restart Minecraft to update the helper", a.hint());
		assertNull(helper.resolveMirror(LG), "old helpers always copy the main display");
		// Even a DISPLAYS from it (it can't, but) wouldn't unlock anything.
		helper.message(DISPLAYS);
		assertFalse(helper.screens().abilities().live());
	}

	@Test
	void screensHelperListsOnConnect() {
		helper.connected(welcome(true));
		assertTrue(helper.supportsScreens());
		assertTrue(sentAny(ListDisplays.class), "asks for the screens at once");
		assertNull(helper.screens().abilities().hint(), "nothing to explain while the list is on its way");
	}

	@Test
	void displaysBecomeTheScreenList() {
		helper.connected(welcome(true));
		AtomicInteger changes = new AtomicInteger();
		helper.onScreensChanged(changes::incrementAndGet);
		helper.message(DISPLAYS);
		assertEquals(1, changes.get(), "listeners run once");
		ScreenList l = helper.screens();
		assertEquals(List.of(
				new Screen(MAIN, 0, true, "MacBook", 0, 0, 1440, 900),
				new Screen(LG, 0, false, "LG", -1920, -180, 1920, 1080),
				new Screen(OFFICE, 1, false, null, 1440, 0, 1920, 1080)), l.screens());
		assertEquals(ScreenList.Abilities.ALL, l.abilities());
		assertTrue(l.abilities().live());
		assertEquals(LG, helper.resolveMirror(LG));
		assertNull(helper.resolveMirror(MAIN));

		assertTrue(helper.setOrigins(List.of(new Origin(1, 1440, -180))));
		assertEquals(new SetOrigins(List.of(new SlotOrigin(1, 1440, -180))), sent.get(sent.size() - 1));
		assertFalse(helper.setOrigins(List.of(new Origin(5, 0, 0))), "a broken list is refused locally");
		assertTrue(helper.identify(Map.of(LG, "B")));
		assertEquals(new Identify(List.of(new IdentifyLabel(LG, "B"))), sent.get(sent.size() - 1));

		helper.disconnected();
		assertEquals(2, changes.get(), "a lost helper changes the map too");
		assertFalse(helper.screens().abilities().live());
		assertEquals(ScreenList.NO_HELPER_HINT, helper.screens().abilities().hint());
		assertNull(helper.resolveMirror(LG), "no helper: back to the main display");
	}

	@Test
	void fallbackListWithoutTheFeature() {
		List<Screen> sdl = List.of(new Screen("sdl:1", 0, true, "MacBook", 0, 0, 1440, 900));
		helper.setFallbackScreens(() -> sdl);
		helper.connected(welcome(false));
		assertEquals(sdl, helper.screens().screens());
	}
}
