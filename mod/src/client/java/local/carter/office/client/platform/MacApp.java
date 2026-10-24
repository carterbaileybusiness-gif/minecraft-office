package local.carter.office.client.platform;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

/**
 * Minecraft's own app on macOS, through the Objective-C runtime (JNA, bundled with Minecraft): who is frontmost, and
 * activating Minecraft ({@link StartupFocus}). AppKit must only be touched on the main thread (Minecraft's client thread
 * on macOS); anywhere else this does nothing.
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

	/** The frontmost app: its bundle id (null for an unbundled process) and pid. */
	record Frontmost(@Nullable String bundleId, long pid) {}

	/** {@code NSWorkspace.frontmostApplication}, or null if there is none or it can't be read. Never throws. */
	static @Nullable Frontmost frontmost() {
		if (!Platform.isMac()) {
			return null;
		}
		try {
			ObjC objc = Native.load("objc", ObjC.class);
			Pointer ws = objc.objc_msgSend(objc.objc_getClass("NSWorkspace"), objc.sel_registerName("sharedWorkspace"));
			Pointer app = ws == null ? null : objc.objc_msgSend(ws, objc.sel_registerName("frontmostApplication"));
			if (app == null) {
				return null;
			}
			Pointer pid = objc.objc_msgSend(app, objc.sel_registerName("processIdentifier"));
			Pointer id = objc.objc_msgSend(app, objc.sel_registerName("bundleIdentifier"));
			Pointer utf8 = id == null ? null : objc.objc_msgSend(id, objc.sel_registerName("UTF8String"));
			return new Frontmost(utf8 == null ? null : utf8.getString(0, "UTF-8"), pid == null ? 0 : (int) Pointer.nativeValue(pid));
		} catch (Throwable e) {
			LOGGER.debug("MacApp: couldn't read the frontmost app: {}", e.toString());
			return null;
		}
	}

	/**
	 * Asks macOS to make Minecraft the active app. Not {@code force}: the cooperative {@code [NSApp activate]} (macOS 14+;
	 * the frontmost app may refuse it). {@code force}, or before macOS 14: {@code activateIgnoringOtherApps:YES}
	 * (deprecated, still honoured). Only activates; never touches the window's frame or mode. Used by
	 * {@link StartupFocus}. Main thread only (does nothing elsewhere). Never throws.
	 */
	static void activate(final boolean force) {
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
			boolean cooperative = responds != null && (Pointer.nativeValue(responds) & 0xFF) != 0;
			if (cooperative && !force) {
				objc.objc_msgSend(app, activate);
			} else {
				objc.objc_msgSend(app, objc.sel_registerName("activateIgnoringOtherApps:"), true);
			}
		} catch (Throwable e) {
			LOGGER.debug("MacApp: couldn't activate Minecraft: {}", e.toString());
		}
	}
}
