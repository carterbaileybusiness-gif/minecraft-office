package local.carter.office.client.selftest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.platform.Platform;

/**
 * The self-test screen's window choice (pure, client thread). Lists are only ever <em>read</em> from
 * {@link Platform#windows} ({@link #read}): asking the OS/helper for a fresh list happens once per Run, inside the
 * platform check, never in reaction to a list arriving, so a list reply can't trigger another request. Labels are app
 * names only, so a screenshot of the screen shows no window titles.
 */
final class WindowPick {
	/** Null until the first list was read. */
	private @Nullable List<Platform.WindowInfo> windows;
	private List<String> labels = List.of();
	/** -1 = none (real capture is skipped). */
	private int picked = -1;

	/** The platform's current list, without asking for a new one. May block on Windows: call off the client thread. */
	static List<Platform.WindowInfo> read(final Platform platform) {
		return List.copyOf(platform.windows());
	}

	/** A list was read; false if it is the same as the shown one (nothing to redraw). Keeps the picked window if listed. */
	boolean apply(final List<Platform.WindowInfo> list) {
		if (list.equals(windows)) {
			return false;
		}
		String keep = choice() == null ? null : choice().key();
		windows = List.copyOf(list);
		labels = labels(list);
		picked = -1;
		for (int i = 0; keep != null && i < list.size(); i++) {
			if (list.get(i).key().equals(keep)) {
				picked = i;
			}
		}
		return true;
	}

	boolean loaded() {
		return windows != null;
	}

	List<Platform.WindowInfo> windows() {
		return windows == null ? List.of() : windows;
	}

	/** Next window, then back to none. False when there is nothing to pick. */
	boolean cycle() {
		List<Platform.WindowInfo> list = windows();
		if (list.isEmpty()) {
			return false;
		}
		picked = picked + 1 >= list.size() ? -1 : picked + 1;
		return true;
	}

	Platform.@Nullable WindowInfo choice() {
		List<Platform.WindowInfo> list = windows();
		return picked >= 0 && picked < list.size() ? list.get(picked) : null;
	}

	/** The picked window's label (app name), or null. */
	@Nullable String choiceLabel() {
		return choice() == null ? null : labels.get(picked);
	}

	/** "looking…", {@code emptyText}, "None (skip)" or the picked app. */
	String label(final String emptyText) {
		if (windows == null) {
			return "looking…";
		}
		if (windows.isEmpty()) {
			return emptyText;
		}
		String l = choiceLabel();
		return l == null ? "None (skip)" : l;
	}

	/** App names only, numbered when one app has several windows ("Google Chrome (2)"). */
	static List<String> labels(final List<Platform.WindowInfo> list) {
		Map<String, Integer> count = new HashMap<>();
		List<String> out = new ArrayList<>();
		for (Platform.WindowInfo w : list) {
			String app = w.app() == null || w.app().isBlank() ? "Window" : w.app();
			int n = count.merge(app.toLowerCase(Locale.ROOT), 1, Integer::sum);
			out.add(n == 1 || app.matches(".* \\(\\d+\\)") ? app : app + " (" + n + ")");
		}
		return out;
	}
}
