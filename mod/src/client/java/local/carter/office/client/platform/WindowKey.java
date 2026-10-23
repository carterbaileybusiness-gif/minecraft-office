package local.carter.office.client.platform;

import org.jspecify.annotations.Nullable;

/**
 * A Windows window as stored in a hologram's {@code windowKey}: {@code "<exe>|<HWND hex>"}, e.g. {@code "notepad.exe|1a2b3c"}.
 * The key is synced to nearby players, so it carries the program's file name and handle only, never the window title.
 */
public record WindowKey(String exe, long hwnd) {
	public static final char SEP = '|';
	/** Longest exe name kept in a key (Windows file names are at most 255 characters). */
	public static final int MAX_EXE = 255;

	public WindowKey {
		if (exe == null || exe.isBlank() || exe.indexOf(SEP) >= 0 || exe.length() > MAX_EXE) {
			throw new IllegalArgumentException("bad exe name: " + exe);
		}
		if (hwnd == 0) {
			throw new IllegalArgumentException("null window handle");
		}
	}

	public String format() {
		return exe + SEP + Long.toHexString(hwnd);
	}

	/** The key's parts, or null if {@code key} is not a Windows window key. */
	public static @Nullable WindowKey parse(final @Nullable String key) {
		if (key == null) {
			return null;
		}
		int bar = key.lastIndexOf(SEP);
		if (bar <= 0 || bar == key.length() - 1) {
			return null;
		}
		String exe = key.substring(0, bar);
		String hex = key.substring(bar + 1);
		if (exe.isBlank() || exe.indexOf(SEP) >= 0 || exe.length() > MAX_EXE || hex.length() > 16) {
			return null;
		}
		for (int i = 0; i < hex.length(); i++) {
			if (Character.digit(hex.charAt(i), 16) < 0) {
				return null;
			}
		}
		long hwnd = Long.parseUnsignedLong(hex, 16);
		return hwnd == 0 ? null : new WindowKey(exe, hwnd);
	}
}
