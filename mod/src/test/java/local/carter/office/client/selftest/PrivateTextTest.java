package local.carter.office.client.selftest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;

/** Personal paths and the login name never reach the self-test report, status lines or log lines. */
class PrivateTextTest {
	private static final PrivateText WIN = new PrivateText("C:\\Users\\alice", "alice",
			"C:\\Users\\alice\\AppData\\Roaming\\PrismLauncher\\instances\\Office\\.minecraft", "C:\\Users\\alice\\AppData\\Local\\Temp\\");

	@Test
	void windowsPathsInAnyCaseAndEitherSlash() {
		assertEquals("<temp>\\webrtc.dll", WIN.scrub("C:\\Users\\alice\\AppData\\Local\\Temp\\webrtc.dll"));
		assertEquals("<temp>\\webrtc.dll", WIN.scrub("c:\\USERS\\Alice\\appdata\\local\\temp\\webrtc.dll"));
		assertEquals("<game folder>/office-selftest", WIN.scrub("C:/Users/alice/AppData/Roaming/PrismLauncher/instances/Office/.minecraft/office-selftest"));
		assertEquals("~\\\\Desktop\\x.txt", WIN.scrub("C:\\\\Users\\\\alice\\\\Desktop\\x.txt"));
	}

	@Test
	void otherUsersFoldersAndShortNamesToo() {
		PrivateText p = new PrivateText("C:\\Users\\alice", "alice", null, null);
		String s = p.scrub("D:\\Users\\bob\\x; C:\\USERS\\ROBERT~1\\AppData; C:\\DOCUME~1\\carol\\y; C:\\Documents and Settings\\dave\\z; "
				+ "\\\\pc\\c$\\Users\\erin\\w");
		String lower = s.toLowerCase(Locale.ROOT);
		for (String name : new String[] {"bob", "robert", "carol", "dave", "erin"}) {
			assertFalse(lower.contains(name), s);
		}
		assertTrue(s.contains("~\\AppData"), s);
	}

	@Test
	void macAndLinuxHomes() {
		PrivateText p = new PrivateText("/Users/carter", "carter", "/Users/carter/Library/Application Support/PrismLauncher/instances/Office/.minecraft",
				"/var/folders/ab/xyz/T/");
		assertEquals("<game folder>/logs", p.scrub("/Users/carter/Library/Application Support/PrismLauncher/instances/Office/.minecraft/logs"));
		assertEquals("~/Desktop", p.scrub("/users/CARTER/Desktop"));
		assertEquals("<temp>/lwjgl/x.dylib", p.scrub("/var/folders/ab/xyz/T/lwjgl/x.dylib"));
		assertEquals("~/x and ~/y", p.scrub("/Users/someoneelse/x and /home/dan/y"));
	}

	@Test
	void theLoginNameAsAWordButNotInsideOtherWords() {
		PrivateText p = new PrivateText("/home/sam", "sam", null, null);
		assertEquals("owner <user> (<user>), sample, samsung", p.scrub("owner sam (SAM), sample, samsung"));
		PrivateText shortName = new PrivateText("/home/al", "al", null, null);
		assertEquals("al ok", shortName.scrub("al ok"), "names under 3 letters are left alone");
	}

	@Test
	void aFolderIsNotMatchedInsideALongerName() {
		PrivateText p = new PrivateText("/Users/bob", "bob", null, null);
		assertEquals("~/x", p.scrub("/Users/bob/x"));
		assertEquals("~/y", p.scrub("/Users/bobby/y"), "another user's folder is scrubbed by the generic rule, not as ~ + by");
	}
}
