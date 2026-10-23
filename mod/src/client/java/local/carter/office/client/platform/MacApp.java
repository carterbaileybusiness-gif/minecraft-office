package local.carter.office.client.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

/**
 * Minecraft's own app on macOS, through the Objective-C runtime (JNA, bundled with Minecraft). Only used as the safety
 * net for Mirror mode: the helper hides Minecraft while the player works on the real desktop and normally unhides it,
 * but if the helper died mid-zoom nobody else would, so {@link MacPlatform#zoomOut} calls {@link #unhideIfHidden}.
 * AppKit must only be touched on the main thread (Minecraft's client thread on macOS); anywhere else this does nothing.
 */
final class MacApp {
	private static final Logger LOGGER = LoggerFactory.getLogger("office");

	interface ObjC extends Library {
		Pointer objc_getClass(String name);

		Pointer sel_registerName(String name);

		Pointer objc_msgSend(Pointer receiver, Pointer selector);

		Pointer objc_msgSend(Pointer receiver, Pointer selector, Pointer arg);

		Pointer objc_msgSend(Pointer receiver, Pointer selector, boolean arg);
	}

	interface LibC extends Library {
		int pthread_main_np();
	}

	private MacApp() {}

	/**
	 * Asks macOS to make Minecraft the active app ({@code [NSApp activate]} on macOS 14+, else
	 * {@code activateIgnoringOtherApps:YES}). Only activates; never touches the window's frame or fullscreen state. Used
	 * by {@link StartupFocus}. Main thread only (does nothing elsewhere). Never throws.
	 */
	static void activate() {
		if (!Platform.isMac()) {
			return;
		}
		try {
			LibC libc = Native.load("c", LibC.class);
			if (libc.pthread_main_np() != 1) {
				return;
			}
			ObjC objc = Native.load("objc", ObjC.class);
			Pointer app = objc.objc_msgSend(objc.objc_getClass("NSApplication"), objc.sel_registerName("sharedApplication"));
			if (app == null) {
				return;
			}
			Pointer activate = objc.sel_registerName("activate");
			Pointer responds = objc.objc_msgSend(app, objc.sel_registerName("respondsToSelector:"), activate);
			if (responds != null && (Pointer.nativeValue(responds) & 0xFF) != 0) {
				objc.objc_msgSend(app, activate);
			} else {
				objc.objc_msgSend(app, objc.sel_registerName("activateIgnoringOtherApps:"), true);
			}
		} catch (Throwable e) {
			LOGGER.debug("MacApp: couldn't activate Minecraft: {}", e.toString());
		}
	}

	/** Unhides (and so activates) Minecraft if it is hidden. True if it was hidden. Never throws. */
	static boolean unhideIfHidden() {
		if (!Platform.isMac()) {
			return false;
		}
		try {
			LibC libc = Native.load("c", LibC.class);
			if (libc.pthread_main_np() != 1) {
				LOGGER.debug("MacApp: not on the main thread; leaving AppKit alone");
				return false;
			}
			ObjC objc = Native.load("objc", ObjC.class);
			Pointer app = objc.objc_msgSend(objc.objc_getClass("NSApplication"), objc.sel_registerName("sharedApplication"));
			if (app == null) {
				return false;
			}
			Pointer hidden = objc.objc_msgSend(app, objc.sel_registerName("isHidden"));
			if (hidden == null || (Pointer.nativeValue(hidden) & 0xFF) == 0) {
				return false; // BOOL in the low byte
			}
			objc.objc_msgSend(app, objc.sel_registerName("unhide:"), Pointer.NULL);
			LOGGER.info("Minecraft was still hidden after a Mirror zoom; unhid it");
			return true;
		} catch (Throwable e) {
			LOGGER.warn("Couldn't check whether Minecraft is hidden: {}", e.toString());
			return false;
		}
	}
}
