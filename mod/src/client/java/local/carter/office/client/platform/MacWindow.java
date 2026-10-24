package local.carter.office.client.platform;

import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_Rect;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jna.Native;
import com.sun.jna.Pointer;

/**
 * Minecraft's own window on macOS (spec 5.10), through SDL (bundled with Minecraft 26.3) and the Objective-C runtime
 * ({@link MacApp}'s JNA bridge). AppKit and SDL's window calls must run on the main thread, which is Minecraft's
 * client thread on macOS; anywhere else these do nothing. Never throws. Nothing here enters, leaves or reads the
 * window's full-screen mode except where noted (FullscreenGuardTest).
 *
 * <ul>
 * <li>{@link #layer}: floating above normal windows ({@code SDL_SetWindowAlwaysOnTop}: NSFloatingWindowLevel) and
 * click-through ({@code -[NSWindow setIgnoresMouseEvents:]} on SDL's NSWindow), for the interactive Mirror.</li>
 * <li>{@link #fillMainDisplay}: the default window mode on a Mac — a borderless window exactly covering the main display
 * (so the other apps stay on the same desktop, which the Mirror shows), with the menu bar and Dock hidden while
 * Minecraft is in front ({@code NSApplicationPresentationAutoHideMenuBar | AutoHideDock}).</li>
 * </ul>
 */
public final class MacWindow {
	private static final Logger LOGGER = LoggerFactory.getLogger("office");
	/** NSApplicationPresentationAutoHideDock (1 << 0) | NSApplicationPresentationAutoHideMenuBar (1 << 2). */
	static final long AUTO_HIDE_DOCK_AND_MENU_BAR = 1L | 4L;

	interface ObjC extends MacApp.ObjC {
		Pointer objc_msgSend(Pointer receiver, Pointer selector, long arg);
	}

	private MacWindow() {}

	private static boolean onMainThread() {
		try {
			return Native.load("c", MacApp.LibC.class).pthread_main_np() == 1;
		} catch (Throwable e) {
			return false;
		}
	}

	/** SDL's NSWindow for Minecraft's window, or null. */
	private static Pointer nsWindow(final long sdlWindow) {
		long p = SDLProperties.SDL_GetPointerProperty(SDLVideo.SDL_GetWindowProperties(sdlWindow),
				SDLVideo.SDL_PROP_WINDOW_COCOA_WINDOW_POINTER, 0L);
		return p == 0 ? null : new Pointer(p);
	}

	/** Floating / click-through. True when both were applied. */
	public static boolean layer(final long sdlWindow, final boolean floating, final boolean clickThrough) {
		if (!Platform.isMac() || sdlWindow == 0 || !onMainThread()) {
			return false;
		}
		try {
			boolean ok = SDLVideo.SDL_SetWindowAlwaysOnTop(sdlWindow, floating);
			Pointer window = nsWindow(sdlWindow);
			if (window == null) {
				return false;
			}
			ObjC objc = Native.load("objc", ObjC.class);
			objc.objc_msgSend(window, objc.sel_registerName("setIgnoresMouseEvents:"), clickThrough);
			LOGGER.debug("Office: Minecraft's window {} {}", floating ? "floating" : "normal", clickThrough ? "click-through" : "");
			return ok;
		} catch (Throwable e) {
			LOGGER.debug("Office: couldn't set Minecraft's window layer: {}", e.toString());
			return false;
		}
	}

	/**
	 * Makes Minecraft's (windowed) window a borderless window covering the whole main display. Call only when the
	 * window is not in full-screen mode. True when applied.
	 */
	public static boolean fillMainDisplay(final long sdlWindow) {
		if (!Platform.isMac() || sdlWindow == 0 || !onMainThread()) {
			return false;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			SDL_Rect bounds = SDL_Rect.malloc(stack);
			if (!SDLVideo.SDL_GetDisplayBounds(SDLVideo.SDL_GetPrimaryDisplay(), bounds)) {
				return false;
			}
			boolean ok = SDLVideo.SDL_SetWindowBordered(sdlWindow, false);
			ok &= SDLVideo.SDL_SetWindowSize(sdlWindow, bounds.w(), bounds.h());
			ok &= SDLVideo.SDL_SetWindowPosition(sdlWindow, bounds.x(), bounds.y());
			SDLVideo.SDL_SyncWindow(sdlWindow);
			ObjC objc = Native.load("objc", ObjC.class);
			Pointer app = objc.objc_msgSend(objc.objc_getClass("NSApplication"), objc.sel_registerName("sharedApplication"));
			if (app != null) {
				objc.objc_msgSend(app, objc.sel_registerName("setPresentationOptions:"), AUTO_HIDE_DOCK_AND_MENU_BAR);
			}
			LOGGER.info("Office: Minecraft's window fills the main display ({}x{} points, borderless)", bounds.w(), bounds.h());
			return ok;
		} catch (Throwable e) {
			LOGGER.warn("Office: couldn't make Minecraft's window fill the screen: {}", e.toString());
			return false;
		}
	}

	/** Whether the window is in a full-screen mode right now (read only, from SDL's window flags). */
	public static boolean isFullscreen(final long sdlWindow) {
		return sdlWindow != 0 && (SDLVideo.SDL_GetWindowFlags(sdlWindow) & SDLVideo.SDL_WINDOW_FULLSCREEN) != 0;
	}
}
