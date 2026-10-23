package local.carter.office.client.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import local.carter.office.client.helper.HelperChannel;
import local.carter.office.client.helper.HelperProtocol.HelperError;
import local.carter.office.client.helper.HelperProtocol.Message;
import local.carter.office.client.helper.HelperProtocol.Welcome;
import local.carter.office.client.helper.HelperProtocol.WindowRect;
import local.carter.office.client.helper.MacHelper;

class ZoomFailureTest {
	@Test
	void zoomInThatCannotBeSentIsAFailure() {
		assertEquals(ZoomFailure.NO_HELPER, ZoomFailure.forZoomIn(false, 7, false));
		assertEquals(ZoomFailure.NOT_READY, ZoomFailure.forZoomIn(true, 0, false), "no capture surface for the hologram");
		assertEquals(ZoomFailure.NOT_READY, ZoomFailure.forZoomIn(true, 7, false), "send refused");
		assertNull(ZoomFailure.forZoomIn(true, 7, true));
	}

	@Test
	void helperErrorsThatMeanTheZoomDidNotHappen() {
		assertEquals(ZoomFailure.NOT_READY, ZoomFailure.forHelperError("not_found"));
		assertEquals(ZoomFailure.NO_ACCESSIBILITY, ZoomFailure.forHelperError("no_accessibility"));
		assertNull(ZoomFailure.forHelperError("bad_size"), "display errors are not about the zoom");
		assertNull(ZoomFailure.forHelperError("busy"));
	}

	@Test
	void macHelperRoutesErrorsAndRefusesZoomInWithoutASurface() {
		List<Message> sent = new ArrayList<>();
		MacHelper helper = new MacHelper(listener -> new HelperChannel() {
			@Override
			public void start() {}

			@Override
			public boolean send(final Message m) {
				sent.add(m);
				return true;
			}

			@Override
			public void close() {}
		}, Runnable::run, (task, delayMs) -> {});
		helper.setSetUp(true);
		helper.start();
		helper.connected(new Welcome(1, true, true));
		List<String> codes = new ArrayList<>();
		helper.onError(e -> codes.add(e.code()));

		helper.message(new HelperError("not_found", "surface 9 is not captured"));
		helper.message(new HelperError("no_accessibility", "needs Accessibility"));
		assertEquals(List.of("not_found", "no_accessibility"), codes);

		int before = sent.size();
		assertEquals(false, helper.zoomIn(0, new WindowRect(0, 0, 1280, 800)), "surface 0: nothing to zoom into");
		assertEquals(before, sent.size(), "and nothing is sent");
	}
}
