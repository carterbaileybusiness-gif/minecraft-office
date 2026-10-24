package local.carter.office.client.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Fullscreen belongs to Minecraft and the player (F11, Video Settings, macOS): none of our code — mod (zoom, mirror,
 * input mixins) or the Mac helper — may enter, leave or block it. Source scan, comments excluded: no fullscreen API is
 * called, and no mixin targets the classes that handle the fullscreen key or apply the window mode.
 */
class FullscreenGuardTest {
	private static final Path ROOT = findRoot();
	private static final Pattern FULLSCREEN = Pattern.compile("(?i)full[_ ]?screen");
	/**
	 * Reading the mode never changes it: a getter for a report (the self-test's "fullscreen" fact), and the helper reading a
	 * window's AXFullScreen attribute so it never moves a window that is in its own Space.
	 */
	private static final Pattern READ_ONLY = Pattern.compile(
			"\\bis\\w*Fullscreen\\((final long \\w+)?\\)|\\bis\\w*Fullscreen\\(\\w+\\)"
					+ "|AXUIElementCopyAttributeValue\\(\\w+, \"AXFullScreen\" as CFString"
					+ "|SDLVideo\\.SDL_GetWindowFlags\\(\\w+\\) & SDLVideo\\.SDL_WINDOW_FULLSCREEN\\)"
					// Identify's number windows may show over a fullscreen app's Space: a collection behaviour, not a mode change
					+ "|\\.fullScreenAuxiliary\\b"
					// the player-facing hint naming the Video setting to turn off (MirrorHint): words, not an API call
					+ "|\"Mirror needs Minecraft in a window: Options → Video → Fullscreen OFF ");
	/** Window applies fullscreen; KeyboardHandler/MouseHandler route F11 (handleGlobalKeyPress); the video screens toggle it. */
	private static final Set<String> FORBIDDEN_MIXIN_TARGETS = Set.of("Window", "KeyboardHandler", "MouseHandler",
			"MonitorManager", "VideoSettingsScreen", "WindowEventHandler");

	private static Path findRoot() {
		Path p = Path.of("").toAbsolutePath();
		while (p != null && !Files.isDirectory(p.resolve("mod/src/client/java"))) {
			p = p.getParent();
		}
		if (p == null) {
			throw new IllegalStateException("repo root (mod/src/client/java) not found from " + Path.of("").toAbsolutePath());
		}
		return p;
	}

	private static List<Path> sources(final String dir, final String ext) {
		Path d = ROOT.resolve(dir);
		if (!Files.isDirectory(d)) {
			return List.of();
		}
		try (Stream<Path> s = Files.walk(d)) {
			return s.filter(f -> f.toString().endsWith(ext)).sorted().toList();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Source text without comments (block, doc and line comments). */
	static String code(final String src) {
		String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", " ");
		return noBlock.replaceAll("(?m)(^|[^:\"])//.*$", "$1");
	}

	private static List<String> fullscreenUses(final List<Path> files) throws IOException {
		List<String> hits = new ArrayList<>();
		for (Path f : files) {
			String[] lines = code(Files.readString(f)).split("\n", -1);
			for (int i = 0; i < lines.length; i++) {
				if (FULLSCREEN.matcher(READ_ONLY.matcher(lines[i]).replaceAll("")).find()) {
					hits.add(ROOT.relativize(f) + ":" + (i + 1) + ": " + lines[i].trim());
				}
			}
		}
		return hits;
	}

	@Test
	void stripsComments() {
		assertEquals("int a = 1; ", code("int a = 1; // fullscreen"));
		assertFalse(FULLSCREEN.matcher(code("/** keeps fullscreen */ void f() {}")).find());
		assertTrue(FULLSCREEN.matcher(code("w.setFullscreen(true); // x")).find());
		assertTrue(code("String u = \"https://x\";").contains("https://x"), "URLs in strings are not comments");
		assertFalse(FULLSCREEN.matcher(READ_ONLY.matcher("w.isExclusiveFullscreen() ? 1 : 0").replaceAll("")).find(), "a read is fine");
		assertTrue(FULLSCREEN.matcher(READ_ONLY.matcher("w.toggleFullscreen()").replaceAll("")).find(), "a change is not");
		assertFalse(FULLSCREEN.matcher(READ_ONLY.matcher("AXUIElementCopyAttributeValue(window, \"AXFullScreen\" as CFString, &x)")
				.replaceAll("")).find(), "the helper may read a window's flag");
		assertTrue(FULLSCREEN.matcher(READ_ONLY.matcher("AXUIElementSetAttributeValue(window, \"AXFullScreen\" as CFString, kCFBooleanTrue)")
				.replaceAll("")).find(), "but never set it");
		// the borderless-window default (MacWindow) checks SDL's flag so it leaves a full-screen window alone
		assertFalse(FULLSCREEN.matcher(READ_ONLY.matcher(
				"return w != 0 && (SDLVideo.SDL_GetWindowFlags(w) & SDLVideo.SDL_WINDOW_FULLSCREEN) != 0;").replaceAll("")).find(), "reading SDL's flag");
		assertFalse(FULLSCREEN.matcher(READ_ONLY.matcher("public static boolean isFullscreen(final long sdlWindow) {").replaceAll("")).find());
		assertFalse(FULLSCREEN.matcher(READ_ONLY.matcher("if (MacWindow.isFullscreen(handle)) {").replaceAll("")).find());
		assertTrue(FULLSCREEN.matcher(READ_ONLY.matcher("SDLVideo.SDL_SetWindowFullscreen(handle, true);").replaceAll("")).find(), "SDL's setter");
		assertTrue(FULLSCREEN.matcher(READ_ONLY.matcher("SDL_SetWindowFlags(w, SDLVideo.SDL_WINDOW_FULLSCREEN)").replaceAll("")).find());
		assertTrue(FULLSCREEN.matcher(READ_ONLY.matcher("mc.options.fullscreen().set(false)").replaceAll("")).find(), "the option");
	}

	@Test
	void modNeverTouchesFullscreen() throws IOException {
		List<Path> files = new ArrayList<>();
		files.addAll(sources("mod/src/main/java", ".java"));
		files.addAll(sources("mod/src/client/java", ".java"));
		assertTrue(files.size() > 20, "found the mod sources under " + ROOT);
		assertEquals(List.of(), fullscreenUses(files));
	}

	@Test
	void macHelperNeverTouchesFullscreen() throws IOException {
		List<Path> files = sources("helper-mac/Sources", ".swift");
		assertFalse(files.isEmpty(), "found the helper sources under " + ROOT);
		assertEquals(List.of(), fullscreenUses(files));
	}

	@Test
	void noMixinOnTheFullscreenPath() throws IOException {
		Pattern mixin = Pattern.compile("@Mixin\\(\\s*\\{?\\s*([A-Za-z0-9_.]+)\\.class");
		List<String> targets = new ArrayList<>();
		for (Path f : sources("mod/src/client/java/local/carter/office/mixin", ".java")) {
			Matcher m = mixin.matcher(code(Files.readString(f)));
			while (m.find()) {
				String name = m.group(1);
				targets.add(name.substring(name.lastIndexOf('.') + 1));
			}
		}
		assertFalse(targets.isEmpty(), "found the client mixins");
		for (String t : targets) {
			assertFalse(FORBIDDEN_MIXIN_TARGETS.contains(t), "a mixin targets " + t + ", which handles fullscreen / F11");
		}
	}
}
