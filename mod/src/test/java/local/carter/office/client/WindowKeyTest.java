package local.carter.office.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import local.carter.office.client.interact.WindowListProvider.WindowChoice;
import local.carter.office.client.platform.WindowKey;
import local.carter.office.client.platform.WindowsPlatform;
import local.carter.office.client.platform.WindowsPlatform.Found;
import org.junit.jupiter.api.Test;

/** Windows window keys ("<exe>|<HWND hex>") and the window list the settings screen shows (pure logic). */
class WindowKeyTest {
	@Test
	void formatsExeAndLowercaseHex() {
		assertEquals("notepad.exe|1a2b3c", new WindowKey("notepad.exe", 0x1A2B3CL).format());
	}

	@Test
	void roundTrips() {
		WindowKey k = new WindowKey("Code.exe", 0x0000_7FF6_1234_ABCDL);
		assertEquals(k, WindowKey.parse(k.format()));
	}

	@Test
	void parsesUppercaseHexAndHighBitHandles() {
		assertEquals(new WindowKey("a.exe", 0xABCL), WindowKey.parse("a.exe|ABC"));
		assertEquals(new WindowKey("a.exe", -1L), WindowKey.parse("a.exe|ffffffffffffffff"));
	}

	@Test
	void rejectsMalformedKeys() {
		assertNull(WindowKey.parse(null));
		assertNull(WindowKey.parse(""));
		assertNull(WindowKey.parse("notepad.exe"));
		assertNull(WindowKey.parse("notepad.exe|"));
		assertNull(WindowKey.parse("|1a"));
		assertNull(WindowKey.parse("notepad.exe|zz"));
		assertNull(WindowKey.parse("notepad.exe|0"));
		assertNull(WindowKey.parse("notepad.exe|-1a"));
		assertNull(WindowKey.parse("notepad.exe|+1a"));
		assertNull(WindowKey.parse("notepad.exe|1ffffffffffffffff")); // > 64 bits
		assertNull(WindowKey.parse("a|b.exe|1a")); // exe names can't contain '|'
		assertNull(WindowKey.parse("mac:1234")); // a Mac helper key
	}

	@Test
	void constructorRejectsBadParts() {
		assertThrows(IllegalArgumentException.class, () -> new WindowKey("", 1));
		assertThrows(IllegalArgumentException.class, () -> new WindowKey("a|b", 1));
		assertThrows(IllegalArgumentException.class, () -> new WindowKey("a.exe", 0));
	}

	@Test
	void choicesShowTheAppNameOnlyNeverAWindowTitle() {
		List<WindowChoice> c = WindowsPlatform.choices(List.of(new Found(0x10, "notepad.exe")));
		assertEquals(List.of(new WindowChoice("notepad.exe|10", "notepad")), c);
	}

	@Test
	void choicesAreSortedAndDuplicatesNumbered() {
		List<WindowChoice> c = WindowsPlatform.choices(List.of(
				new Found(0x30, "chrome.exe"),
				new Found(0x10, "Notepad.EXE"),
				new Found(0x20, "chrome.exe"),
				new Found(0x40, "ApplicationFrameHost.exe")));
		assertEquals(List.of(
				new WindowChoice("ApplicationFrameHost.exe|40", "ApplicationFrameHost"),
				new WindowChoice("chrome.exe|30", "chrome"),
				new WindowChoice("chrome.exe|20", "chrome (2)"),
				new WindowChoice("Notepad.EXE|10", "Notepad")), c);
	}

	@Test
	void choicesSkipUnusableEntries() {
		List<WindowChoice> c = WindowsPlatform.choices(List.of(new Found(0x10, ""), new Found(0, "a.exe"), new Found(0x20, "a|b")));
		assertEquals(List.of(), c);
	}

	@Test
	void choiceKeysFitThePayloadLimitAndCarryNoTitle() {
		for (WindowChoice ch : WindowsPlatform.choices(List.of(new Found(-1L, "x".repeat(260) + ".exe")))) {
			assertFalse(ch.key().length() > local.carter.office.net.OfficePayloads.MAX_WINDOW_KEY);
		}
	}
}
