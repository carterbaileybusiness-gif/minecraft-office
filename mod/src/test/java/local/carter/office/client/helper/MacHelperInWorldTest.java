package local.carter.office.client.helper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import local.carter.office.client.helper.HelperProtocol.Click;
import local.carter.office.client.helper.HelperProtocol.CreateDisplay;
import local.carter.office.client.helper.HelperProtocol.Display;
import local.carter.office.client.helper.HelperProtocol.DragDelta;
import local.carter.office.client.helper.HelperProtocol.DragEnded;
import local.carter.office.client.helper.HelperProtocol.ExitTyping;
import local.carter.office.client.helper.HelperProtocol.Message;
import local.carter.office.client.helper.HelperProtocol.PointerButton;
import local.carter.office.client.helper.HelperProtocol.PointerDown;
import local.carter.office.client.helper.HelperProtocol.PointerMove;
import local.carter.office.client.helper.HelperProtocol.PointerUp;
import local.carter.office.client.helper.HelperProtocol.RemoveDisplay;
import local.carter.office.client.helper.HelperProtocol.Scroll;
import local.carter.office.client.helper.HelperProtocol.TypingExit;
import local.carter.office.client.helper.HelperProtocol.Welcome;
import local.carter.office.client.helper.HelperProtocol.WindowsBack;
import local.carter.office.client.helper.HelperProtocol.WindowsToDisplay;
import local.carter.office.client.pointer.PointerPoint;

/** MacHelper's in-world side: windows on join / leave, pointer messages out, typing / drag events in. */
class MacHelperInWorldTest {
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
		helper.setMoveWindowsOnJoin(true);
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
	void theFirstDisplayOfTheSessionGetsThePlayersWindowsAndLeavingPutsThemBack() {
		helper.ensureDisplay(1, 1920, 1080);
		listener().message(new Display(1, 30, 1920, 1080));
		helper.ensureDisplay(2, 1920, 1080);
		listener().message(new Display(2, 31, 1920, 1080));
		listener().message(new Display(1, 30, 1920, 1080)); // re-announced: no second move
		assertEquals(List.of(new WindowsToDisplay(1, null)), sentOf(WindowsToDisplay.class));
		sent.clear();
		helper.stop();
		assertEquals(new WindowsBack(), sent.get(0), "windows go back before any display is removed");
		assertTrue(sent.containsAll(List.of(new RemoveDisplay(1), new RemoveDisplay(2))));
	}

	@Test
	void aReconnectedHelperMovesTheWindowsAgain() {
		listener().message(new Display(1, 30, 1920, 1080));
		listener().disconnected(); // the helper crashed: macOS put the windows back when its displays vanished
		listener().connected(new Welcome(1, true, true));
		listener().message(new Display(1, 40, 1920, 1080));
		assertEquals(2, sentOf(WindowsToDisplay.class).size());
	}

	@Test
	void windowsStayWhenTheOptionIsOffOrNothingWasMoved() {
		helper.setMoveWindowsOnJoin(false);
		listener().message(new Display(1, 30, 1920, 1080));
		helper.stop();
		assertTrue(sentOf(WindowsToDisplay.class).isEmpty());
		assertTrue(sentOf(WindowsBack.class).isEmpty());
		assertTrue(sentOf(CreateDisplay.class).isEmpty());
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
}
