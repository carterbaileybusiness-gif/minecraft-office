package local.carter.office.client.helper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import local.carter.office.client.helper.HelperProtocol.AppIcon;
import local.carter.office.client.helper.HelperProtocol.AppIcons;
import local.carter.office.client.helper.HelperProtocol.AppInfo;
import local.carter.office.client.helper.HelperProtocol.AppResult;
import local.carter.office.client.helper.HelperProtocol.Apps;
import local.carter.office.client.helper.HelperProtocol.Click;
import local.carter.office.client.helper.HelperProtocol.CreateDisplay;
import local.carter.office.client.helper.HelperProtocol.Display;
import local.carter.office.client.helper.HelperProtocol.DragDelta;
import local.carter.office.client.helper.HelperProtocol.DragEnded;
import local.carter.office.client.helper.HelperProtocol.ExitTyping;
import local.carter.office.client.helper.HelperProtocol.Icons;
import local.carter.office.client.helper.HelperProtocol.ListApps;
import local.carter.office.client.helper.HelperProtocol.Message;
import local.carter.office.client.helper.HelperProtocol.OpenApp;
import local.carter.office.client.helper.HelperProtocol.PointerButton;
import local.carter.office.client.helper.HelperProtocol.PointerDown;
import local.carter.office.client.helper.HelperProtocol.PointerMove;
import local.carter.office.client.helper.HelperProtocol.PointerUp;
import local.carter.office.client.helper.HelperProtocol.RemoveDisplay;
import local.carter.office.client.helper.HelperProtocol.Scroll;
import local.carter.office.client.helper.HelperProtocol.TypingExit;
import local.carter.office.client.helper.HelperProtocol.Welcome;
import local.carter.office.client.pointer.PointerPoint;

/** MacHelper's in-world side: no window moves on join / leave, pointer messages out (Mirror too), the Dock, events in. */
class MacHelperInWorldTest {
	private final List<Message> sent = new ArrayList<>();
	private final List<HelperConnection.Listener> listeners = new ArrayList<>();
	private final List<Runnable> scheduled = new ArrayList<>();
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
		}, Runnable::run, (task, delayMs) -> scheduled.add(task));
		helper.setSetUp(true);
		helper.start();
		listener().connected(new Welcome(1, true, true));
	}

	private HelperConnection.Listener listener() {
		return listeners.get(listeners.size() - 1);
	}

	private List<Message> sentOf(final Class<?> type) {
		return sent.stream().filter(type::isInstance).toList();
	}

	@Test
	void joiningAndLeavingMoveNoWindows() {
		helper.ensureDisplay(1, 1920, 1080);
		listener().message(new Display(1, 30, 1920, 1080));
		new ArrayList<>(scheduled).forEach(Runnable::run);
		helper.stop();
		// only display traffic and the Dock's app list: no window message exists any more (spec 5.10)
		for (Message m : sent) {
			assertTrue(m instanceof CreateDisplay || m instanceof RemoveDisplay || m instanceof ListApps, "unexpected " + m);
		}
		assertEquals(List.of(new RemoveDisplay(1)), sentOf(RemoveDisplay.class));
	}

	@Test
	void theHelperDyingEndsADragAndTypingOnTheClientThread() {
		List<String> events = new ArrayList<>();
		helper.onTypingExit(reason -> events.add("typing " + reason));
		helper.onDragEnded(reason -> events.add("drag " + reason));
		listener().message(new DragDelta(5, 5));
		listener().disconnected();
		assertEquals(List.of("drag cancelled", "typing helper"), events);
		assertArrayEquals(new int[] {0, 0}, helper.drainDragDelta(), "no stale motion turns the head later");
	}

	@Test
	void pointerMessagesGoOutAsTheProtocolSays() {
		PointerPoint p = new PointerPoint(2, 10, 20);
		helper.pointerDown(p);
		helper.pointerMove(p);
		helper.pointerUp(p);
		helper.click(p, PointerButton.RIGHT);
		helper.scroll(p, 0, -96);
		helper.exitTyping();
		assertEquals(List.of(new PointerDown(2, 10, 20, PointerButton.LEFT), new PointerMove(2, 10, 20), new PointerUp(2, 10, 20, PointerButton.LEFT),
				new Click(2, 10, 20, PointerButton.RIGHT), new Scroll(2, 10, 20, 0, -96), new ExitTyping()), sent.subList(sent.size() - 6, sent.size()));
	}

	@Test
	void mirrorPointsGoToSlotZeroWithTheirPictureSize() {
		PointerPoint p = new PointerPoint(0, 960, 540, 1920, 1080);
		helper.pointerDown(p);
		helper.pointerMove(p);
		helper.click(p, PointerButton.LEFT);
		helper.scroll(p, 0, 48);
		assertEquals(List.of(new PointerDown(0, 960, 540, PointerButton.LEFT, 1920, 1080), new PointerMove(0, 960, 540, 1920, 1080),
				new Click(0, 960, 540, PointerButton.LEFT, 1920, 1080), new Scroll(0, 960, 540, 0, 48, 1920, 1080)),
				sent.subList(sent.size() - 4, sent.size()));
	}

	@Test
	void workModeGoesOutWithThePictureSizeOnTheMirrorAndTheNewestCursorIsKept() {
		helper.workBegin(new PointerPoint(0, 960, 540, 1920, 1080), PointerButton.LEFT);
		helper.workBegin(new PointerPoint(2, 10, 20), PointerButton.RIGHT);
		assertEquals(List.of(new HelperProtocol.WorkBegin(0, 960, 540, PointerButton.LEFT, 1920, 1080),
				new HelperProtocol.WorkBegin(2, 10, 20, PointerButton.RIGHT, null, null)), sentOf(HelperProtocol.WorkBegin.class));
		assertNull(helper.cursor(), "no cursor until the helper reports one");
		listener().message(new HelperProtocol.Cursor(2, 11, 21, 1920, 1080));
		listener().message(new HelperProtocol.Cursor(2, 12, 22, 1920, 1080));
		assertEquals(new HelperProtocol.Cursor(2, 12, 22, 1920, 1080), helper.cursor(), "the newest");
		helper.workBegin(new PointerPoint(1, 5, 5), PointerButton.LEFT);
		assertNull(helper.cursor(), "a new work session starts without the old cursor");
	}

	@Test
	void dragDeltasAddUpUntilDrainedAndTypingAndDragEndsReachTheClientThread() {
		List<String> events = new ArrayList<>();
		helper.onTypingExit(reason -> events.add("typing " + reason));
		helper.onDragEnded(reason -> events.add("drag " + reason));
		listener().message(new DragDelta(3, -1));
		listener().message(new DragDelta(4, 2));
		assertArrayEquals(new int[] {7, 1}, helper.drainDragDelta());
		assertArrayEquals(new int[] {0, 0}, helper.drainDragDelta());
		listener().message(new TypingExit("esc"));
		listener().message(new DragEnded("released"));
		assertEquals(List.of("typing esc", "drag released"), events);
	}

	@Test
	void theDockAsksForAppsOnEveryConnectAndKeepsThemInOrder() {
		assertEquals(1, sentOf(ListApps.class).size(), "asked on connect");
		assertNull(helper.apps(), "unknown until APPS");
		listener().message(new Apps(List.of(new AppInfo("com.google.Chrome", "Google Chrome"), new AppInfo("com.apple.Notes", "Notes"))));
		assertEquals(List.of("com.google.Chrome", "com.apple.Notes"), List.copyOf(helper.apps().keySet()));
		assertEquals(Map.of("com.google.Chrome", "Google Chrome", "com.apple.Notes", "Notes"), helper.apps());
		listener().disconnected();
		listener().connected(new Welcome(1, true, true));
		assertEquals(2, sentOf(ListApps.class).size(), "a new helper: asked again");
	}

	@Test
	void iconsAreAskedForOnceAndDecoded() {
		helper.requestIcons(List.of("com.apple.Notes", "com.apple.Notes", "/bin/sh", "com.google.Chrome"));
		helper.requestIcons(List.of("com.apple.Notes"));
		assertEquals(List.of(new AppIcons(List.of("com.apple.Notes", "com.google.Chrome"), MacHelper.ICON_PX)), sentOf(AppIcons.class),
				"once each, bad ids left out");
		byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
		listener().message(new Icons(List.of(new AppIcon("com.apple.Notes", Base64.getEncoder().encodeToString(png)),
				new AppIcon("com.google.Chrome", "%%%not base64%%%"))));
		assertArrayEquals(png, helper.icon("com.apple.Notes"));
		assertNull(helper.icon("com.google.Chrome"), "a broken icon is dropped");
		List<String> many = new ArrayList<>();
		for (int i = 0; i < 70; i++) {
			many.add("com.example.app" + i);
		}
		helper.requestIcons(many);
		assertEquals(List.of(64, 6), sentOf(AppIcons.class).stream().skip(1).map(m -> ((AppIcons) m).bundles().size()).toList(),
				"batches the helper accepts");
	}

	@Test
	void typingEndedElsewhereAndSecureInputReachTheClientThread() {
		List<String> events = new ArrayList<>();
		helper.onTypingExit(reason -> events.add("typing " + reason));
		helper.onSecureInput(on -> events.add("secure " + on));
		listener().message(new HelperProtocol.SecureInput(true));
		listener().message(new TypingExit("elsewhere")); // the player Cmd-Tabbed to a third app
		assertEquals(List.of("secure true", "typing elsewhere"), events);
	}

	@Test
	void openingAnAppOrAPageAndHearingBack() {
		List<AppResult> results = new ArrayList<>();
		helper.onAppResult(results::add);
		assertTrue(helper.openApp(2, "com.apple.Notes", null, 96));
		assertTrue(helper.openApp(1, null, "https://www.youtube.com", null));
		assertFalse(helper.openApp(0, "com.apple.Notes", null, null), "never onto the Mirror");
		assertFalse(helper.openApp(1, null, "http://example.com", null), "https only");
		assertEquals(List.of(new OpenApp(2, "com.apple.Notes", null, 96), new OpenApp(1, null, "https://www.youtube.com", null)),
				sentOf(OpenApp.class), "the Dock's band goes with it");
		listener().message(new AppResult("com.apple.Notes", false, "Notes opened but showed no window"));
		assertEquals(List.of(new AppResult("com.apple.Notes", false, "Notes opened but showed no window")), results);
	}
}
