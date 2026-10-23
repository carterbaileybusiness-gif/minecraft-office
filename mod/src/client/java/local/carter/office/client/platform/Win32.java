package local.carter.office.client.platform;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinUser.HHOOK;
import com.sun.jna.platform.win32.WinUser.HOOKPROC;
import com.sun.jna.platform.win32.WinUser.KBDLLHOOKSTRUCT;
import com.sun.jna.platform.win32.WinUser.LowLevelKeyboardProc;
import com.sun.jna.platform.win32.WinUser.MSG;
import com.sun.jna.platform.win32.WinUser.WINDOWPLACEMENT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import local.carter.office.client.platform.Platform.Rect;

/**
 * The few Win32 calls the Windows platform needs, through JNA (bundled with Minecraft). Window handles are plain
 * {@code long}s. Only usable on Windows: {@link #get()} refuses elsewhere before any native library is loaded.
 */
public final class Win32 {
	private static final Logger LOGGER = LogUtils.getLogger();

	static final int SW_SHOW = 5;
	static final int SW_MINIMIZE = 6;
	/** SetWindowDisplayAffinity: the window is left out of every screen capture (Windows 10 2004, build 19041+). */
	public static final int WDA_EXCLUDEFROMCAPTURE = 0x11;
	public static final int WDA_NONE = 0;
	/** First Windows build with {@link #WDA_EXCLUDEFROMCAPTURE} (10 2004). */
	public static final int EXCLUDE_FROM_CAPTURE_BUILD = 19041;
	static final int DISPLAY_DEVICE_ACTIVE = 0x1;
	static final int DISPLAY_DEVICE_PRIMARY_DEVICE = 0x4;
	static final int SW_RESTORE = 9;
	static final int SWP_NOZORDER = 0x0004;
	static final int SWP_NOACTIVATE = 0x0010;
	static final int SWP_SHOWWINDOW = 0x0040;
	static final int SWP_NOOWNERZORDER = 0x0200;
	static final int WH_KEYBOARD_LL = 13;
	static final int WM_QUIT = 0x0012;
	static final int WM_KEYDOWN = 0x0100;
	static final int WM_KEYUP = 0x0101;
	static final int WM_SYSKEYDOWN = 0x0104;
	static final int WM_SYSKEYUP = 0x0105;
	static final int VK_SHIFT = 0x10;
	static final int VK_CONTROL = 0x11;
	static final int VK_MENU = 0x12;
	static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
	static final int DESKTOP_READOBJECTS = 0x0001;
	static final int DWMWA_EXTENDED_FRAME_BOUNDS = 9;

	/** EnumDisplayDevicesW's DISPLAY_DEVICEW (char = WCHAR with the unicode options). */
	@Structure.FieldOrder({"cb", "DeviceName", "DeviceString", "StateFlags", "DeviceID", "DeviceKey"})
	public static class DisplayDevice extends Structure {
		public int cb;
		public char[] DeviceName = new char[32];
		public char[] DeviceString = new char[128];
		public int StateFlags;
		public char[] DeviceID = new char[128];
		public char[] DeviceKey = new char[128];

		public DisplayDevice() {
			cb = size();
		}
	}

	/** user32 functions (W variants picked by the unicode options). */
	public interface User32 extends StdCallLibrary {
		boolean IsWindow(HWND hwnd);

		/** Windows 7+; WDA_EXCLUDEFROMCAPTURE needs 10 2004. Only for windows of the calling process. */
		boolean SetWindowDisplayAffinity(HWND hwnd, int affinity);

		boolean GetWindowDisplayAffinity(HWND hwnd, IntByReference affinity);

		boolean EnumDisplayDevices(String device, int index, DisplayDevice info, int flags);

		boolean IsWindowVisible(HWND hwnd);

		boolean IsIconic(HWND hwnd);

		boolean IsZoomed(HWND hwnd);

		boolean ShowWindow(HWND hwnd, int cmd);

		boolean GetWindowRect(HWND hwnd, RECT rect);

		boolean GetClientRect(HWND hwnd, RECT rect);

		boolean ClientToScreen(HWND hwnd, POINT point);

		boolean SetWindowPos(HWND hwnd, HWND after, int x, int y, int w, int h, int flags);

		boolean GetWindowPlacement(HWND hwnd, WINDOWPLACEMENT placement);

		boolean SetWindowPlacement(HWND hwnd, WINDOWPLACEMENT placement);

		boolean SetForegroundWindow(HWND hwnd);

		HWND GetForegroundWindow();

		boolean BringWindowToTop(HWND hwnd);

		boolean AttachThreadInput(int idAttach, int idAttachTo, boolean attach);

		int GetWindowThreadProcessId(HWND hwnd, IntByReference pid);

		HWND FindWindow(String className, String windowName);

		HHOOK SetWindowsHookEx(int idHook, HOOKPROC proc, HANDLE module, int threadId);

		LRESULT CallNextHookEx(HHOOK hook, int code, WPARAM wParam, LPARAM lParam);

		boolean UnhookWindowsHookEx(HHOOK hook);

		int GetMessage(MSG msg, HWND hwnd, int min, int max);

		boolean PostThreadMessage(int threadId, int msg, WPARAM wParam, LPARAM lParam);

		short GetAsyncKeyState(int vk);

		Pointer OpenInputDesktop(int flags, boolean inherit, int access);

		boolean CloseDesktop(Pointer desktop);

		/** Windows 10 1607+; returns the previous context. */
		Pointer SetThreadDpiAwarenessContext(Pointer context);
	}

	public interface Dwm extends StdCallLibrary {
		int DwmGetWindowAttribute(HWND hwnd, int attribute, RECT value, int size);
	}

	private static volatile @Nullable Win32 instance;

	private final User32 user32;
	private final Kernel32 kernel32;
	private final @Nullable Dwm dwm;

	private Win32() {
		user32 = Native.load("user32", User32.class, W32APIOptions.DEFAULT_OPTIONS);
		kernel32 = Kernel32.INSTANCE;
		Dwm d;
		try {
			d = Native.load("dwmapi", Dwm.class, W32APIOptions.DEFAULT_OPTIONS);
		} catch (Throwable e) {
			d = null;
		}
		dwm = d;
	}

	/** The Win32 bindings; throws {@link UnsupportedOperationException} when not on Windows. */
	public static Win32 get() {
		Win32 w = instance;
		if (w == null) {
			synchronized (Win32.class) {
				w = instance;
				if (w == null) {
					if (!System.getProperty("os.name", "").startsWith("Windows")) {
						throw new UnsupportedOperationException("Win32 is only available on Windows");
					}
					w = new Win32();
					instance = w;
				}
			}
		}
		return w;
	}

	static HWND hwnd(final long h) {
		return new HWND(new Pointer(h));
	}

	static long value(final @Nullable HWND h) {
		return h == null ? 0 : Pointer.nativeValue(h.getPointer());
	}

	public boolean isWindow(final long hwnd) {
		return hwnd != 0 && user32.IsWindow(hwnd(hwnd));
	}

	public int processId(final long hwnd) {
		IntByReference pid = new IntByReference();
		user32.GetWindowThreadProcessId(hwnd(hwnd), pid);
		return pid.getValue();
	}

	/** The file name of the program that owns {@code hwnd} (e.g. {@code notepad.exe}), or null if it can't be read. */
	public @Nullable String exeName(final long hwnd) {
		int pid = processId(hwnd);
		if (pid == 0) {
			return null;
		}
		HANDLE process = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
		if (process == null) {
			return null;
		}
		try {
			char[] buf = new char[1024];
			IntByReference len = new IntByReference(buf.length);
			if (!kernel32.QueryFullProcessImageName(process, 0, buf, len)) {
				return null;
			}
			String path = new String(buf, 0, len.getValue());
			int slash = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
			String name = path.substring(slash + 1);
			return name.isEmpty() ? null : name;
		} finally {
			kernel32.CloseHandle(process);
		}
	}

	/** Top-level window of a window class (tests), or 0. */
	public long findWindow(final String className) {
		return value(user32.FindWindow(className, null));
	}

	/**
	 * Leaves {@code hwnd} (a window of this process: Minecraft's) out of every screen capture, so a Mirror hologram never
	 * shows Minecraft inside Minecraft. Only on Windows 10 2004+ (older builds would black the window out instead);
	 * true once the affinity reads back as {@link #WDA_EXCLUDEFROMCAPTURE}.
	 */
	public boolean excludeFromCapture(final long hwnd) {
		if (hwnd == 0 || !supportsExcludeFromCapture(windowsBuild())) {
			return false;
		}
		HWND h = hwnd(hwnd);
		if (!user32.SetWindowDisplayAffinity(h, WDA_EXCLUDEFROMCAPTURE)) {
			LOGGER.warn("SetWindowDisplayAffinity(EXCLUDEFROMCAPTURE) failed: error {}", kernel32.GetLastError());
			return false;
		}
		return displayAffinity(hwnd) == WDA_EXCLUDEFROMCAPTURE;
	}

	/** The window's display affinity, or -1 if it can't be read. */
	public int displayAffinity(final long hwnd) {
		IntByReference out = new IntByReference(-1);
		return hwnd != 0 && user32.GetWindowDisplayAffinity(hwnd(hwnd), out) ? out.getValue() : -1;
	}

	/** Whether {@code build} (0 = unknown: try) has WDA_EXCLUDEFROMCAPTURE. Pure. */
	public static boolean supportsExcludeFromCapture(final int build) {
		return build == 0 || build >= EXCLUDE_FROM_CAPTURE_BUILD;
	}

	/** Windows build number (registry CurrentBuildNumber), or 0 if unknown. */
	public static int windowsBuild() {
		try {
			String b = com.sun.jna.platform.win32.Advapi32Util.registryGetStringValue(com.sun.jna.platform.win32.WinReg.HKEY_LOCAL_MACHINE,
					"SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion", "CurrentBuildNumber");
			return Integer.parseInt(b.trim());
		} catch (Throwable e) {
			return 0;
		}
	}

	/** Minimizes {@code hwnd} (Mirror zoom: Minecraft steps aside so the player is on their real desktop). */
	public boolean minimize(final long hwnd) {
		return hwnd != 0 && user32.ShowWindow(hwnd(hwnd), SW_MINIMIZE);
	}

	public boolean isMinimized(final long hwnd) {
		return hwnd != 0 && user32.IsIconic(hwnd(hwnd));
	}

	/**
	 * The primary monitor's index in EnumDisplayDevices order, which is also webrtc's screen-source id on Windows (both its
	 * GDI and DXGI capturers number screens that way); -1 if none is marked primary.
	 */
	public int primaryDisplayIndex() {
		for (int i = 0; i < 64; i++) {
			DisplayDevice d = new DisplayDevice();
			if (!user32.EnumDisplayDevices(null, i, d, 0)) {
				break;
			}
			if ((d.StateFlags & DISPLAY_DEVICE_ACTIVE) != 0 && (d.StateFlags & DISPLAY_DEVICE_PRIMARY_DEVICE) != 0) {
				return i;
			}
		}
		return -1;
	}

	/** True if this process can see the interactive input desktop (false on headless service sessions). */
	public boolean hasInputDesktop() {
		Pointer desk = user32.OpenInputDesktop(0, false, DESKTOP_READOBJECTS);
		if (desk == null) {
			return false;
		}
		user32.CloseDesktop(desk);
		return true;
	}

	/*
	 * Coordinates: every geometry call below runs with this thread switched to per-monitor DPI awareness (v2), so
	 * GetWindowRect, GetClientRect/ClientToScreen, Get/SetWindowPlacement, SetWindowPos and DWM's extended frame bounds
	 * (always physical) all speak the same physical-pixel screen coordinates, whatever DPI mode SDL put Minecraft in.
	 * On Windows versions without SetThreadDpiAwarenessContext (before 10 1607) the process's own mode is used for all
	 * of them except DWM, which can then disagree by the scale factor on a scaled monitor (beta limitation).
	 */
	/** DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 (made lazily: touching JNA types loads JNA natives). */
	static final long DPI_PER_MONITOR_AWARE_V2 = -4;

	private <T> T physical(final java.util.function.Supplier<T> body) {
		Pointer old = null;
		try {
			old = user32.SetThreadDpiAwarenessContext(new Pointer(DPI_PER_MONITOR_AWARE_V2));
		} catch (Throwable e) {
			// older Windows: keep the process's mode
		}
		try {
			return body.get();
		} finally {
			if (old != null) {
				try {
					user32.SetThreadDpiAwarenessContext(old);
				} catch (Throwable e) {
					// ignore
				}
			}
		}
	}

	private static Rect rect(final RECT r) {
		return new Rect(r.left, r.top, r.right - r.left, r.bottom - r.top);
	}

	/** The window's outer rectangle (physical screen pixels, including invisible resize borders), or null. */
	public @Nullable Rect windowRect(final long hwnd) {
		return physical(() -> {
			RECT r = new RECT();
			return user32.GetWindowRect(hwnd(hwnd), r) ? rect(r) : null;
		});
	}

	/** The part of the window you can see (without Windows 10/11's invisible borders, physical pixels), or the window rect. */
	public @Nullable Rect visibleFrame(final long hwnd) {
		if (dwm != null) {
			RECT r = new RECT();
			try {
				if (dwm.DwmGetWindowAttribute(hwnd(hwnd), DWMWA_EXTENDED_FRAME_BOUNDS, r, r.size()) == 0) {
					return rect(r);
				}
			} catch (Throwable e) {
				// fall through
			}
		}
		return windowRect(hwnd);
	}

	/** The client area in physical screen pixels, or null. */
	public @Nullable Rect clientRectOnScreen(final long hwnd) {
		return physical(() -> {
			RECT r = new RECT();
			if (!user32.GetClientRect(hwnd(hwnd), r)) {
				return null;
			}
			POINT p = new POINT(0, 0);
			if (!user32.ClientToScreen(hwnd(hwnd), p)) {
				return null;
			}
			return new Rect(p.x, p.y, r.right - r.left, r.bottom - r.top);
		});
	}

	/** Saves size, position and minimized/maximized state; null if unavailable. */
	public @Nullable WINDOWPLACEMENT placement(final long hwnd) {
		return physical(() -> {
			WINDOWPLACEMENT wp = new WINDOWPLACEMENT();
			wp.length = wp.size();
			return user32.GetWindowPlacement(hwnd(hwnd), wp) ? wp : null;
		});
	}

	public void restorePlacement(final long hwnd, final WINDOWPLACEMENT wp) {
		physical(() -> {
			wp.length = wp.size();
			if (!user32.SetWindowPlacement(hwnd(hwnd), wp)) {
				LOGGER.warn("SetWindowPlacement failed for {}", Long.toHexString(hwnd));
			}
			return null;
		});
	}

	/**
	 * The outer window rectangle that puts the window's visible frame exactly on {@code area}: the invisible border on
	 * each side ({@code visible} inset within {@code outer}, never negative) is added back around {@code area}. Pure.
	 */
	public static Rect outerFor(final Rect area, final @Nullable Rect outer, final @Nullable Rect visible) {
		if (outer == null || visible == null) {
			return area;
		}
		int l = Math.max(0, visible.x() - outer.x());
		int t = Math.max(0, visible.y() - outer.y());
		int r = Math.max(0, outer.x() + outer.w() - (visible.x() + visible.w()));
		int b = Math.max(0, outer.y() + outer.h() - (visible.y() + visible.h()));
		return new Rect(area.x() - l, area.y() - t, area.w() + l + r, area.h() + t + b);
	}

	/**
	 * Un-minimizes / un-maximizes the window and moves it so its visible frame covers {@code area} (physical screen
	 * pixels) exactly, on top of other normal windows.
	 */
	public boolean placeOver(final long hwnd, final Rect area) {
		return physical(() -> {
			HWND h = hwnd(hwnd);
			if (user32.IsIconic(h) || user32.IsZoomed(h)) {
				user32.ShowWindow(h, SW_RESTORE);
			}
			Rect to = outerFor(area, windowRect(hwnd), visibleFrame(hwnd));
			return user32.SetWindowPos(h, null /* HWND_TOP */, to.x(), to.y(), to.w(), to.h(), SWP_SHOWWINDOW | SWP_NOOWNERZORDER);
		});
	}

	/**
	 * Brings {@code hwnd} to the front with keyboard focus. Windows only lets the foreground process do that; when the
	 * plain call is refused, briefly attach to the foreground thread's input (the usual workaround) and retry.
	 */
	public boolean focus(final long hwnd) {
		HWND h = hwnd(hwnd);
		if (user32.IsIconic(h)) {
			user32.ShowWindow(h, SW_RESTORE);
		}
		if (user32.SetForegroundWindow(h)) {
			return true;
		}
		HWND fg = user32.GetForegroundWindow();
		int fgThread = fg == null ? 0 : user32.GetWindowThreadProcessId(fg, null);
		int me = kernel32.GetCurrentThreadId();
		boolean attached = fgThread != 0 && fgThread != me && user32.AttachThreadInput(me, fgThread, true);
		try {
			user32.BringWindowToTop(h);
			user32.ShowWindow(h, SW_SHOW);
			return user32.SetForegroundWindow(h);
		} finally {
			if (attached) {
				user32.AttachThreadInput(me, fgThread, false);
			}
		}
	}

	private boolean pressed(final int vk) {
		return (user32.GetAsyncKeyState(vk) & 0x8000) != 0;
	}

	/**
	 * Starts a low-level keyboard hook on its own message-loop thread, feeding {@code chord}; returns at once (see
	 * {@link KeyHook#running()} for readiness). {@code onExit} must return fast.
	 */
	public KeyHook hookKeys(final ExitChord chord, final Runnable onExit) {
		return new KeyHook(chord, onExit);
	}

	/** A WH_KEYBOARD_LL hook on its own thread. {@link #close()} unhooks it and ends its thread. */
	public final class KeyHook implements AutoCloseable {
		private final Thread thread;
		private volatile int threadId;
		private volatile @Nullable HHOOK hook;
		private volatile boolean closed;
		private final LowLevelKeyboardProc proc; // strong reference: the native hook calls it

		KeyHook(final ExitChord chord, final Runnable onExit) {
			proc = (code, wParam, info) -> {
				try {
					if (code >= 0 && info != null) {
						int msg = wParam.intValue();
						boolean down = msg == WM_KEYDOWN || msg == WM_SYSKEYDOWN;
						boolean up = msg == WM_KEYUP || msg == WM_SYSKEYUP;
						if (down || up) {
							ExitChord.Action a = chord.key(info.vkCode, down, pressed(VK_CONTROL), pressed(VK_MENU), pressed(VK_SHIFT),
									System.nanoTime() / 1_000_000);
							if (a == ExitChord.Action.EXIT) {
								onExit.run();
							}
							if (a != ExitChord.Action.PASS) {
								return new LRESULT(1);
							}
						}
					}
				} catch (Throwable e) {
					LOGGER.warn("Keyboard hook failed", e);
				}
				return user32.CallNextHookEx(hook, code, wParam, info == null ? new LPARAM(0) : new LPARAM(Pointer.nativeValue(info.getPointer())));
			};
			thread = new Thread(this::loop, "office-win-keys");
			thread.setDaemon(true);
			thread.start(); // no waiting: zoomIn runs on the game thread
		}

		private void loop() {
			threadId = kernel32.GetCurrentThreadId();
			HHOOK h = user32.SetWindowsHookEx(WH_KEYBOARD_LL, proc, kernel32.GetModuleHandle(null), 0); // also creates this thread's queue
			if (h == null) {
				LOGGER.warn("Keyboard hook not installed (SetWindowsHookEx error {}); double-Esc won't leave the zoom", Native.getLastError());
				return;
			}
			hook = h;
			try {
				if (closed) {
					return; // closed before we got here: don't wait for a WM_QUIT that may have been posted too early
				}
				MSG msg = new MSG();
				while (user32.GetMessage(msg, null, 0, 0) > 0) {
					// no windows on this thread: the hook is called from inside GetMessage
				}
			} finally {
				user32.UnhookWindowsHookEx(h);
				hook = null;
			}
		}

		/** True once the hook is installed, while its thread runs. */
		public boolean running() {
			return thread.isAlive() && hook != null;
		}

		@Override
		public void close() {
			closed = true;
			for (int i = 0; i < 40 && thread.isAlive(); i++) {
				if (threadId != 0) {
					user32.PostThreadMessage(threadId, WM_QUIT, new WPARAM(0), new LPARAM(0));
				}
				try {
					thread.join(50);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
			if (thread.isAlive()) {
				LOGGER.warn("Keyboard hook thread did not stop");
			}
		}
	}
}
