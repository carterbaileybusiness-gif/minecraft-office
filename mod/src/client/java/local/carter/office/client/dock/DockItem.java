package local.carter.office.client.dock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * One icon of the mini Dock (spec 5.10): an app ({@code bundle}), a web page ({@code url}, opened in the default
 * browser) or the "All apps" search. Pure.
 */
public record DockItem(String name, @Nullable String bundle, @Nullable String url, boolean allApps) {
	public static final String YOUTUBE_URL = "https://www.youtube.com";
	public static final DockItem ALL_APPS = new DockItem("All apps", null, null, true);

	public static DockItem app(final String name, final String bundle) {
		return new DockItem(name, bundle, null, false);
	}

	public static DockItem page(final String name, final String url) {
		return new DockItem(name, null, url, false);
	}

	/** Carter's Dock, in order (spec 5.10, my call): Chrome, Safari, Finder, Notes, Terminal, Claude, ChatGPT, Music, YouTube. */
	public static final List<DockItem> DEFAULTS = List.of(
			app("Chrome", "com.google.Chrome"),
			app("Safari", "com.apple.Safari"),
			app("Finder", "com.apple.finder"),
			app("Notes", "com.apple.Notes"),
			app("Terminal", "com.apple.Terminal"),
			app("Claude", "com.anthropic.claudefordesktop"),
			app("ChatGPT", "com.openai.chat"),
			app("Music", "com.apple.Music"),
			page("YouTube", YOUTUBE_URL));

	/**
	 * What the Dock shows: the defaults whose app is installed ({@code installed}: bundle id → name, from the helper; null
	 * = not known yet, so no Dock), web pages always (they open in whatever browser is the default), then "All apps".
	 */
	public static @Nullable List<DockItem> shown(final @Nullable Map<String, String> installed) {
		if (installed == null) {
			return null;
		}
		List<DockItem> out = new ArrayList<>();
		for (DockItem d : DEFAULTS) {
			if (d.bundle() == null || installed.containsKey(d.bundle())) {
				out.add(d);
			}
		}
		out.add(ALL_APPS);
		return List.copyOf(out);
	}

	/** The icon texture's key: the bundle id, or a built-in glyph for pages and "All apps". */
	public String iconKey() {
		return allApps ? "glyph:allapps" : bundle != null ? bundle : "glyph:youtube";
	}
}
