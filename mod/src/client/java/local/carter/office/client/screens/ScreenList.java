package local.carter.office.client.screens;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.platform.Platform;

/**
 * Every screen the player has right now (real monitors and Office displays) and what this computer can do with them.
 * Real monitors get letters (A = main, then B, C… by origin x, then y), Office displays keep their slot number 1-4.
 * Names stay on this computer: nothing here is ever put into a payload except opaque ids and those labels.
 */
public record ScreenList(List<Screen> screens, Abilities abilities) {
	/** Why the map's buttons are greyed when the helper is older than the display map. */
	public static final String UPDATE_HELPER_HINT = "Restart Minecraft to update the helper";
	/** Why they are greyed while no helper is connected. */
	public static final String NO_HELPER_HINT = "The Office helper isn't running";

	/**
	 * {@code live}: the list is the OS's current truth (it follows plug / unplug). {@code canMove}: Office displays can
	 * be dragged and Applied. {@code canPickMirror}: a Mirror can copy another monitor. {@code canIdentifyScreens}: the
	 * real screens can show Identify labels. {@code hint}: why something is greyed (null = nothing to say).
	 */
	public record Abilities(boolean live, boolean canMove, boolean canPickMirror, boolean canIdentifyScreens, @Nullable String hint) {
		public static final Abilities NONE = new Abilities(false, false, false, false, null);
		public static final Abilities ALL = new Abilities(true, true, true, true, null);
	}

	private static volatile @Nullable ScreenList testOverride;

	public ScreenList {
		screens = List.copyOf(screens);
	}

	/** One 1920 × 1080 main display ("main"), no abilities: what a computer that can't list its screens shows. */
	public static ScreenList mainOnly() {
		return new ScreenList(List.of(new Screen("main", 0, true, null, 0, 0, 1920, 1080)), Abilities.NONE);
	}

	/** The test override if set (client GameTests), else this computer's screens. Client thread. */
	public static ScreenList current() {
		ScreenList o = testOverride;
		return o != null ? o : Platform.current().screens();
	}

	/** Client GameTests: pretend to have these screens; null goes back to the real ones. */
	public static void setTestOverride(final @Nullable ScreenList list) {
		testOverride = list;
	}

	public List<Screen> monitors() {
		List<Screen> out = new ArrayList<>();
		for (Screen s : screens) {
			if (s.isMonitor()) {
				out.add(s);
			}
		}
		return out;
	}

	/** The Office displays that exist, by slot. */
	public List<Screen> offices() {
		List<Screen> out = new ArrayList<>();
		for (Screen s : screens) {
			if (!s.isMonitor()) {
				out.add(s);
			}
		}
		out.sort(Comparator.comparingInt(Screen::slot));
		return out;
	}

	/** The main display (the one flagged main, else the first monitor, else the first screen). */
	public Screen main() {
		Screen first = null;
		for (Screen s : screens) {
			if (s.isMonitor()) {
				if (s.main()) {
					return s;
				}
				if (first == null) {
					first = s;
				}
			}
		}
		if (first != null) {
			return first;
		}
		return screens.isEmpty() ? mainOnly().screens().get(0) : screens.get(0);
	}

	public @Nullable Screen byId(final @Nullable String id) {
		if (id == null) {
			return null;
		}
		for (Screen s : screens) {
			if (s.id().equals(id)) {
				return s;
			}
		}
		return null;
	}

	/** Office display {@code slot} (1-4), or null when it doesn't exist right now. */
	public @Nullable Screen office(final int slot) {
		for (Screen s : screens) {
			if (!s.isMonitor() && s.slot() == slot) {
				return s;
			}
		}
		return null;
	}

	/** Monitor id → letter, in letter order: A = main, then the others by origin x, then y (at most 26). */
	public Map<String, Character> letters() {
		Map<String, Character> out = new LinkedHashMap<>();
		Screen main = main();
		List<Screen> rest = new ArrayList<>();
		for (Screen s : monitors()) {
			if (s != main) {
				rest.add(s);
			}
		}
		rest.sort(Comparator.comparingInt(Screen::x).thenComparingInt(Screen::y));
		if (main.isMonitor()) {
			out.put(main.id(), 'A');
		}
		for (Screen s : rest) {
			if (out.size() < 26) {
				out.put(s.id(), (char) ('A' + out.size()));
			}
		}
		return out;
	}

	/** The map label of a screen: its letter (monitors) or slot number (Office displays). */
	public String label(final Screen s) {
		if (!s.isMonitor()) {
			return Integer.toString(s.slot());
		}
		Character c = letters().get(s.id());
		return c == null ? "?" : c.toString();
	}

	/**
	 * The monitor a Mirror that wants {@code wanted} copies: that id when it is a monitor here and not the main one, else
	 * null (= the main display: nothing chosen, the main one chosen, or the chosen one unplugged).
	 */
	public @Nullable String resolveMirror(final @Nullable String wanted) {
		Screen s = byId(wanted);
		if (s == null || !s.isMonitor() || s == main()) {
			return null;
		}
		return s.id();
	}
}
