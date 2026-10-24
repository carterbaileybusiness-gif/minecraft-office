package local.carter.office.client.dock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;
import local.carter.office.core.Privacy;
import local.carter.office.core.Shape;

/** The mini Dock: which icons show, where they sit, what the crosshair hits; and the "All apps" search. */
class DockTest {
	private static final UUID ME = UUID.fromString("00000000-0000-0000-0000-00000000000a");

	private static HologramSpec spec(final Mode mode, final Shape shape) {
		return new HologramSpec(ME, shape, mode, Privacy.PUBLIC, 1, "", 2.0, 0, 1.5, 0, 0);
	}

	private static Map<String, String> installed(final String... bundles) {
		Map<String, String> m = new LinkedHashMap<>();
		for (String b : bundles) {
			m.put(b, b);
		}
		return m;
	}

	@Test
	void onlyInstalledAppsShowYouTubeAndAllAppsAlways() {
		assertNull(DockItem.shown(null), "the helper hasn't listed the apps yet: no Dock");
		List<DockItem> some = DockItem.shown(installed("com.apple.Safari", "com.apple.finder", "com.apple.Notes", "com.apple.Music"));
		assertEquals(List.of("Safari", "Finder", "Notes", "Music", "YouTube", "All apps"), some.stream().map(DockItem::name).toList(),
				"Chrome, Terminal, Claude and ChatGPT aren't installed here");
		List<DockItem> all = DockItem.shown(installed("com.google.Chrome", "com.apple.Safari", "com.apple.finder", "com.apple.Notes",
				"com.apple.Terminal", "com.anthropic.claudefordesktop", "com.openai.chat", "com.apple.Music", "com.other.App"));
		assertEquals(List.of("Chrome", "Safari", "Finder", "Notes", "Terminal", "Claude", "ChatGPT", "Music", "YouTube", "All apps"),
				all.stream().map(DockItem::name).toList(), "Carter's order; other apps only via All apps");
		DockItem youtube = all.get(8);
		assertEquals(DockItem.YOUTUBE_URL, youtube.url());
		assertNull(youtube.bundle());
		assertTrue(all.get(9).allApps());
		assertEquals("com.google.Chrome", all.get(0).iconKey());
		assertEquals("glyph:youtube", youtube.iconKey());
		assertEquals("glyph:allapps", all.get(9).iconKey());
	}

	@Test
	void theBarSitsCentredAlongTheBottomInsideThePicture() {
		DockLayout d = new DockLayout(10, 1920, 1080);
		DockLayout.Box bar = d.bar();
		assertEquals(0.5, (bar.u0() + bar.u1()) / 2, 1e-9, "centred");
		assertTrue(bar.v1() < 1 && bar.v1() > 0.98, "just above the bottom edge: " + bar.v1());
		assertTrue(bar.u0() > 0 && bar.u1() < 1, "inside the picture");
		double iconH = d.icon(0).height() * 1080;
		assertEquals(1080 * DockLayout.ICON_SHARE, iconH, 1e-6, "a 76 px icon on a 1080 px screen");
		assertEquals(d.icon(0).width() * 1920, iconH, 1e-6, "square icons");
		for (int i = 1; i < 10; i++) {
			assertTrue(d.icon(i).u0() > d.icon(i - 1).u1(), "left to right, apart");
			assertEquals(d.icon(0).v0(), d.icon(i).v0(), 1e-12, "one row");
		}
		assertTrue(bar.width() > 0.45 && bar.width() < 0.55, "only as wide as its icons on a wide screen (ten: about half): " + bar.width());
	}

	@Test
	void aTallScreenShrinksTheIconsToFitItsWidth() {
		DockLayout d = new DockLayout(10, 1080, 1920);
		assertTrue(d.bar().u0() >= 0.019 && d.bar().u1() <= 0.981, "fits: " + d.bar());
		assertTrue(d.icon(0).height() * 1920 < 1920 * DockLayout.ICON_SHARE, "smaller than the share of the height");
		assertEquals(d.icon(0).width() * 1080, d.icon(0).height() * 1920, 1e-6, "still square");
	}

	@Test
	void everyPointOfTheBarBelongsToAnIconAndNothingElseDoes() {
		DockLayout d = new DockLayout(4, 1920, 1080);
		for (int i = 0; i < 4; i++) {
			DockLayout.Box b = d.icon(i);
			assertEquals(i, d.hit((b.u0() + b.u1()) / 2, (b.v0() + b.v1()) / 2), "icon " + i + " centre");
		}
		DockLayout.Box bar = d.bar();
		double v = (bar.v0() + bar.v1()) / 2;
		assertEquals(0, d.hit(bar.u0() + 1e-6, v), "the bar's left end");
		assertEquals(3, d.hit(bar.u1() - 1e-6, v), "the bar's right end");
		double gap = (d.icon(0).u1() + d.icon(1).u0()) / 2;
		assertEquals(0, d.hit(gap - 1e-6, v), "left half of a gap");
		assertEquals(1, d.hit(gap + 1e-6, v), "right half of a gap");
		for (double u = bar.u0(); u <= bar.u1(); u += 0.001) {
			assertTrue(d.hit(u, v) >= 0, "no dead spot at u " + u);
		}
		assertEquals(-1, d.hit(0.5, bar.v0() - 0.01), "just above the bar: the screen behind");
		assertEquals(-1, d.hit(bar.u0() - 0.01, v), "beside the bar: the screen behind");
		assertEquals(-1, d.hit(0.5, 0.5));
		assertFalse(new DockLayout(0, 1920, 1080).inBar(0.5, 0.99), "no items, no Dock");
	}

	@Test
	void onlyTheOwnersExtendHologramsHaveADock() {
		List<DockItem> items = DockItem.shown(installed("com.apple.Notes"));
		HologramSpec extend = spec(Mode.EXTEND, Shape.WIDE_16_9);
		DockLayout.Box first = Dock.layout(extend, items.size()).icon(0);
		double u = (first.u0() + first.u1()) / 2;
		double v = (first.v0() + first.v1()) / 2;
		assertEquals(0, Dock.hit(extend, true, true, items, u, v));
		assertEquals(-1, Dock.hit(extend, false, true, items, u, v), "friends: no Dock (it's drawn on the owner's client only)");
		assertEquals(-1, Dock.hit(spec(Mode.MIRROR, Shape.WIDE_16_9), true, true, items, u, v), "the Mirror is the laptop screen: it has the real Dock");
		assertEquals(-1, Dock.hit(spec(Mode.WINDOW, Shape.WIDE_16_9), true, true, items, u, v));
		assertEquals(-1, Dock.hit(extend, true, true, null, u, v), "app list not known yet");
		assertEquals(-1, Dock.hit(extend, true, false, items, u, v), "not live yet: no Dock drawn, so the click goes to the screen");
	}

	@Test
	void searchPrefixFirstThenContainsInPagesOfEight() {
		Map<String, String> apps = new LinkedHashMap<>();
		apps.put("com.apple.Notes", "Notes");
		apps.put("com.apple.Keynote", "Keynote");
		apps.put("com.apple.Numbers", "Numbers");
		apps.put("com.example.voice", "Voice Notes");
		apps.put("com.apple.TextEdit", "TextEdit");
		assertEquals(List.of("Notes", "Voice Notes", "Keynote"), AppSearch.filter(apps, " no ").stream().map(AppSearch.App::name).toList(),
				"starts with (or a word does), then contains");
		assertEquals(List.of("Keynote", "Notes", "Numbers", "TextEdit", "Voice Notes"),
				AppSearch.filter(apps, "").stream().map(AppSearch.App::name).toList(), "empty: everything A-Z");
		assertEquals(List.of("TextEdit"), AppSearch.filter(apps, "textedit").stream().map(AppSearch.App::name).toList(), "case-insensitive");
		assertEquals(List.of(), AppSearch.filter(apps, "zzz"));

		List<Integer> many = new java.util.ArrayList<>();
		for (int i = 0; i < 19; i++) {
			many.add(i);
		}
		assertEquals(3, AppSearch.pages(19));
		assertEquals(1, AppSearch.pages(0), "an empty result is one (empty) page");
		assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), AppSearch.page(many, 0));
		assertEquals(List.of(16, 17, 18), AppSearch.page(many, 2));
		assertEquals(List.of(16, 17, 18), AppSearch.page(many, 9), "past the end: the last page");
		assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), AppSearch.page(many, -1));
	}
}
