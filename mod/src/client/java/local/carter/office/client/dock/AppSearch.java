package local.carter.office.client.dock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The "All apps" search (pure): names that start with what was typed first (or any word of the name does), then names
 * that contain it, each in A–Z order; then pages of {@link #PAGE_SIZE} (no scrolling, as Carter's search book in Home
 * Base: Mac UI Makeover, MinecraftHome SearchScreen.java, does the same prefix-then-contains ranking).
 */
public final class AppSearch {
	public static final int PAGE_SIZE = 8;

	public record App(String bundle, String name) {}

	private AppSearch() {}

	/** {@code installed}: bundle id → name. */
	public static List<App> filter(final Map<String, String> installed, final String query) {
		String q = query.strip().toLowerCase(Locale.ROOT);
		List<App> all = new ArrayList<>();
		installed.forEach((bundle, name) -> all.add(new App(bundle, name)));
		all.sort((a, b) -> {
			int c = a.name().compareToIgnoreCase(b.name());
			return c != 0 ? c : a.bundle().compareTo(b.bundle());
		});
		if (q.isEmpty()) {
			return all;
		}
		List<App> first = new ArrayList<>();
		List<App> later = new ArrayList<>();
		for (App a : all) {
			String name = a.name().toLowerCase(Locale.ROOT);
			if (name.startsWith(q) || name.contains(" " + q)) {
				first.add(a);
			} else if (name.contains(q) || a.bundle().toLowerCase(Locale.ROOT).contains(q)) {
				later.add(a);
			}
		}
		first.addAll(later);
		return first;
	}

	public static int pages(final int results) {
		return Math.max(1, (results + PAGE_SIZE - 1) / PAGE_SIZE);
	}

	/** Page {@code page} (0-based, clamped) of {@code results}. */
	public static <T> List<T> page(final List<T> results, final int page) {
		int p = Math.max(0, Math.min(page, pages(results.size()) - 1));
		int from = p * PAGE_SIZE;
		return results.subList(Math.min(from, results.size()), Math.min(from + PAGE_SIZE, results.size()));
	}
}
