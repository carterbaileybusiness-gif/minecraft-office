package local.carter.office.magic.spell;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.server.level.ServerLevel;

/**
 * Every running {@link LingeringEffect}, server-wide, at most {@link #MAX} at once. A spell adds its effect <b>before</b> any
 * side effect and fizzles with {@code magic.office.busy} when {@link #add} returns false. Server thread only.
 */
public final class ActiveEffects {
	public static final int MAX = 32;

	private static final Logger LOGGER = LoggerFactory.getLogger("office");

	private static final class Entry {
		final ServerLevel level;
		final LingeringEffect effect;
		int age;

		Entry(ServerLevel level, LingeringEffect effect) {
			this.level = level;
			this.effect = effect;
		}
	}

	private static final List<Entry> ENTRIES = new ArrayList<>();

	private ActiveEffects() {}

	/** Starts {@code e} in {@code level}; its first {@code tick} runs on the next server tick. False (nothing added) when full. */
	public static boolean add(ServerLevel level, LingeringEffect e) {
		if (ENTRIES.size() >= MAX) return false;
		ENTRIES.add(new Entry(level, e));
		return true;
	}

	/** Stops {@code e} now (its {@code end} runs). False if it was not running. */
	public static boolean remove(LingeringEffect e) {
		for (int i = 0; i < ENTRIES.size(); i++) {
			Entry en = ENTRIES.get(i);
			if (en.effect == e) {
				ENTRIES.remove(i);
				end(en);
				return true;
			}
		}
		return false;
	}

	public static boolean running(LingeringEffect e) {
		for (Entry en : ENTRIES) if (en.effect == e) return true;
		return false;
	}

	public static int count() {
		return ENTRIES.size();
	}

	/** Once per server tick: ticks every effect in start order; effects added during this pass start next tick. */
	public static void tick() {
		if (ENTRIES.isEmpty()) return;
		for (Entry en : List.copyOf(ENTRIES)) {
			if (!ENTRIES.contains(en)) continue; // removed by an earlier effect this tick
			boolean keep;
			try {
				keep = en.effect.tick(en.level, ++en.age);
			} catch (RuntimeException ex) {
				LOGGER.error("[office] Lingering spell effect {} failed; stopping it", en.effect, ex);
				keep = false;
			}
			if (!keep && ENTRIES.remove(en)) end(en);
		}
	}

	/** Ends every effect (server stopping). */
	public static void endAll() {
		List<Entry> all = List.copyOf(ENTRIES);
		ENTRIES.clear();
		for (Entry en : all) end(en);
	}

	private static void end(Entry en) {
		try {
			en.effect.end(en.level);
		} catch (RuntimeException ex) {
			LOGGER.error("[office] Ending lingering spell effect {} failed", en.effect, ex);
		}
	}
}
