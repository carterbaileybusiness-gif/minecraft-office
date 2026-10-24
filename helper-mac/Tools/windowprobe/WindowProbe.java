import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_Event;

import local.carter.office.client.platform.MacWindow;

/**
 * WindowProbe: the mod's real {@link MacWindow} code on a real SDL3 window (LWJGL 3.4.3, as bundled with Minecraft
 * 26.3) on this Mac, without Minecraft: does "fill the main display, borderless" give a window exactly over the whole
 * main display, does "floating" put it above normal windows (CGWindow layer 3), and does "click-through" make the
 * window server's hit test skip it (and does turning it off restore all of that)? Prints PASS/FAIL lines. Run with
 * helper-mac/Tools/windowprobe/run.sh (needs -XstartOnFirstThread: AppKit and SDL on the main thread).
 */
public final class WindowProbe {
	private static final List<String> FAILED = new ArrayList<>();
	private static String info = "";

	public static void main(final String[] args) throws Exception {
		String windowinfo = args[0];
		long pid = ProcessHandle.current().pid();
		if (!SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO)) {
			throw new IllegalStateException("SDL_Init failed");
		}
		// like Minecraft: a resizable, high-density window that starts as a normal titled window
		long w = SDLVideo.SDL_CreateWindow("WindowProbe (stands in for Minecraft)", 854, 480,
				SDLVideo.SDL_WINDOW_RESIZABLE | SDLVideo.SDL_WINDOW_HIGH_PIXEL_DENSITY);
		if (w == 0) {
			throw new IllegalStateException("SDL_CreateWindow failed");
		}
		SDLVideo.SDL_RaiseWindow(w);
		pump(800);
		String before = look(windowinfo, pid, 400, 300);
		check("a normal titled window to start with (not covering the display)", !info.contains("layer=0 bounds=0,0,") && info.contains("layer=0"), before);
		check("isFullscreen reads false for a window", !MacWindow.isFullscreen(w), "");

		boolean filled = MacWindow.fillMainDisplay(w);
		pump(1200);
		String main = mainBounds(look(windowinfo, pid, 10, 10));
		String after = look(windowinfo, pid, 10, 10);
		long flags = SDLVideo.SDL_GetWindowFlags(w);
		check("fillMainDisplay: borderless, exactly the main display's bounds (menu bar area included), still a window",
				filled && info.contains("layer=0 bounds=" + main) && (flags & SDLVideo.SDL_WINDOW_BORDERLESS) != 0 && !MacWindow.isFullscreen(w),
				after + " flags borderless=" + ((flags & SDLVideo.SDL_WINDOW_BORDERLESS) != 0));

		boolean floated = MacWindow.layer(w, true, false);
		pump(400);
		String f = look(windowinfo, pid, 300, 300);
		check("layer(floating): CGWindow layer 3 (NSFloatingWindowLevel), and it still takes clicks", floated && info.contains("layer=3")
				&& info.contains("hit=" + pid), f);

		MacWindow.layer(w, true, true);
		pump(400);
		String t = look(windowinfo, pid, 300, 300);
		check("layer(floating, click-through): still on top (layer 3) but the window server's hit test passes it by",
				info.contains("layer=3") && !info.contains("hit=" + pid), t);

		MacWindow.layer(w, false, false);
		pump(400);
		String n = look(windowinfo, pid, 300, 300);
		check("layer(normal): back to layer 0 and it takes clicks again", info.contains("layer=0") && info.contains("hit=" + pid), n);

		SDLVideo.SDL_DestroyWindow(w);
		SDLInit.SDL_Quit();
		System.out.println(FAILED.isEmpty() ? "ALL PASS" : FAILED.size() + " FAILED: " + FAILED);
		System.exit(FAILED.isEmpty() ? 0 : 1);
	}

	private static void pump(final long ms) throws InterruptedException {
		long end = System.currentTimeMillis() + ms;
		try (SDL_Event e = SDL_Event.malloc()) {
			while (System.currentTimeMillis() < end) {
				while (SDLEvents.SDL_PollEvent(e)) {
					// drain
				}
				Thread.sleep(10);
			}
		}
	}

	private static String look(final String tool, final long pid, final int x, final int y) throws Exception {
		Process p = new ProcessBuilder(tool, Long.toString(pid), Integer.toString(x), Integer.toString(y)).redirectErrorStream(true).start();
		StringBuilder sb = new StringBuilder();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
			String line;
			while ((line = r.readLine()) != null) {
				sb.append(line).append(" | ");
			}
		}
		p.waitFor();
		info = sb.toString();
		return info;
	}

	private static String mainBounds(final String out) {
		int i = out.indexOf("main=");
		return i < 0 ? "?" : out.substring(i + 5).replace(" | ", "").trim();
	}

	private static void check(final String name, final boolean ok, final String detail) {
		System.out.println((ok ? "PASS " : "FAIL ") + name + (detail.isEmpty() ? "" : " — " + detail));
		if (!ok) {
			FAILED.add(name);
		}
	}
}
