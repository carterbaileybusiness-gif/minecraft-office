package local.carter.office.client.platform;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
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

	/** user32 functions (W variants picked by the unicode options). */
	public interface User32 extends StdCallLibrary {
		boolean IsWindow(HWND hwnd);

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

	/** True if this process can see the interactive input desktop (false on headless service sessions). */
	public boolean hasInputDesktop() {
		Pointer desk = user32.OpenInputDesktop(0, false, DESKTOP_READOBJECTS);
		if (desk == null) {
			return false;
		}
		user32.CloseDesktop(desk);
		return true;
	}

	/** The window's outer rectangle (screen coordinates, including invisible resize borders), or null. */
	public @Nullable Rect windowRect(final long hwnd) {
		RECT r = new RECT();
		if (!user32.GetWindowRect(hwnd(hwnd), r)) {
			return null;
		}
		return new Rect(r.left, r.top, r.right - r.left, r.bottom - r.top);
	}

	/** The part of the window you can see (without Windows 10/11's invisible borders), or the window rect. */
	public @Nullable Rect visibleFrame(final long hwnd) {
		if (dwm != null) {
			RECT r = new RECT();
			try {
				if (dwm.DwmGetWindowAttribute(hwnd(hwnd), DWMWA_EXTENDED_FRAME_BOUNDS, r, r.size()) == 0) {
					return new Rect(r.left, r.top, r.right - r.left, r.bottom - r.top);
				}
			} catch (Throwable e) {
				// fall through
			}
		}
		return windowRect(hwnd);
	}

	/** The client area in screen coordinates, or null. */
	public @Nullable Rect clientRectOnScreen(final long hwnd) {
		RECT r = new RECT();
		if (!user32.GetClientRect(hwnd(hwnd), r)) {
			return null;
		}
		POINT p = new POINT(0, 0);
		if (!user32.ClientToScreen(hwnd(hwnd), p)) {
			return null;
		}
		return new Rect(p.x, p.y, r.right - r.left, r.bottom - r.top);
	}

	/** Saves size, position and minimized/maximized state; null if unavailable. */
	public @Nullable WINDOWPLACEMENT placement(final long hwnd) {
		WINDOWPLACEMENT wp = new WINDOWPLACEMENT();
		wp.length = wp.size();
		return user32.GetWindowPlacement(hwnd(hwnd), wp) ? wp : null;
	}

	public void restorePlacement(final long hwnd, final WINDOWPLACEMENT wp) {
		wp.length = wp.size();
		if (!user32.SetWindowPlacement(hwnd(hwnd), wp)) {
			LOGGER.warn("SetWindowPlacement failed for {}", Long.toHexString(hwnd));
		}
	}

	/**
	 * Un-minimizes / un-maximizes the window and moves it so its visible frame covers {@code area} exactly (screen
	 * coordinates), on top of other normal windows.
	 */
	public boolean placeOver(final long hwnd, final Rect area) {
		HWND h = hwnd(hwnd);
		if (user32.IsIconic(h) || user32.IsZoomed(h)) {
			user32.ShowWindow(h, SW_RESTORE);
		}
		Rect outer = windowRect(hwnd);
		Rect seen = visibleFrame(hwnd);
		int l = 0;
		int t = 0;
		int r = 0;
		int b = 0;
		if (outer != null && seen != null) {
			l = Math.max(0, seen.x() - outer.x());
			t = Math.max(0, seen.y() - outer.y());
			r = Math.max(0, outer.x() + outer.w() - (seen.x() + seen.w()));
			b = Math.max(0, outer.y() + outer.h() - (seen.y() + seen.h()));
		}
		return user32.SetWindowPos(h, null /* HWND_TOP */, area.x() - l, area.y() - t, area.w() + l + r, area.h() + t + b,
				SWP_SHOWWINDOW | SWP_NOOWNERZORDER);
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

	/** Starts a low-level keyboard hook on its own message-loop thread, feeding {@code chord}; {@code onExit} must return fast. */
	public KeyHook hookKeys(final ExitChord chord, final Runnable onExit) {
		return new KeyHook(chord, onExit);
	}

	/** A running WH_KEYBOARD_LL hook. {@link #close()} unhooks it and ends its thread. */
	public final class KeyHook implements AutoCloseable {
		private final Thread thread;
		private final CountDownLatch ready = new CountDownLatch(1);
		private volatile int threadId;
		private volatile @Nullable HHOOK hook;
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
			thread.start();
			try {
				if (!ready.await(2, TimeUnit.SECONDS)) {
					LOGGER.warn("Keyboard hook thread slow to start");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		private void loop() {
			threadId = kernel32.GetCurrentThreadId();
			HHOOK h = null;
			try {
				h = user32.SetWindowsHookEx(WH_KEYBOARD_LL, proc, kernel32.GetModuleHandle(null), 0);
				hook = h;
				if (h == null) {
					LOGGER.warn("SetWindowsHookEx failed: error {}", Native.getLastError());
					return;
				}
			} finally {
				ready.countDown();
			}
			MSG msg = new MSG();
			while (user32.GetMessage(msg, null, 0, 0) > 0) {
				// no windows on this thread: the hook is called from inside GetMessage
			}
			user32.UnhookWindowsHookEx(h);
			hook = null;
		}

		/** True while the hook is installed and its thread runs. */
		public boolean running() {
			return thread.isAlive() && hook != null;
		}

		@Override
		public void close() {
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
