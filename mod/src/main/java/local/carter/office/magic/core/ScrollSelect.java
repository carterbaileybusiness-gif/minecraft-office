package local.carter.office.magic.core;

import java.util.List;

public final class ScrollSelect {
	private ScrollSelect() {}

	/** Scroll down (dy &lt; 0) selects the next entry, up (dy &gt; 0) the previous, wrapping. Unknown current gives the first. */
	public static <T> T next(List<T> unlocked, T current, double scrollDy) {
		if (unlocked.isEmpty()) return null;
		int i = current == null ? -1 : unlocked.indexOf(current);
		if (i < 0 || scrollDy == 0) return i < 0 ? unlocked.get(0) : current;
		int n = unlocked.size();
		int step = scrollDy < 0 ? 1 : -1;
		return unlocked.get(((i + step) % n + n) % n);
	}
}
