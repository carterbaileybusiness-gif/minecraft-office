package local.carter.office.magic.spell;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.server.level.ServerLevel;

/**
 * Every running {@link LingeringEffect}, server-wide. Each effect belongs to the player who cast it, and two caps apply: at most
 * {@link #MAX_PER_CASTER} per caster (so one player spamming lingering spells cannot starve anyone else) and at most {@link #MAX}
 * on the whole server (a safety net). A spell adds its effect <b>before</b> any side effect and fizzles with
 * {@code magic.office.busy} when {@link #add} returns false; a spell whose effect cannot be built without side effects checks
 * {@link #full} first. Server thread only.
 *
 * <p>Teleport channels (Recall, Travel) start through {@link #addChannel}: they count toward their caster's cap but are never
 * refused by the global one, so other players' effects can never block a player's way home.
 *
 * <p>Cost: {@link #add}, {@link #remove}, {@link #running}, {@link #count(UUID)} and {@link #full} are O(1); {@link #tick} is
 * O(n) plus the effects' own ticks.
 */
public final class ActiveEffects {
	/** Global safety cap on running effects (teleport channels may exceed it, by at most one per player). */
	public static final int MAX = 128;
	/** Cap on one caster's running effects, channels included. */
	public static final int MAX_PER_CASTER = 12;

	private static final Logger LOGGER = LoggerFactory.getLogger("office");

	private static final class Entry {
		final ServerLevel level;
		final UUID caster;
		final LingeringEffect effect;
		int age;
		boolean live = true;

		Entry(ServerLevel level, UUID caster, LingeringEffect effect) {
			this.level = level;
			this.caster = caster;
			this.effect = effect;
		}
	}

	/** In start order. May briefly hold retired entries (not {@code live}); {@link #tick} compacts them away. */
	private static final List<Entry> ENTRIES = new ArrayList<>();
	/** Live entries by effect instance. */
	private static final Map<LingeringEffect, Entry> BY_EFFECT = new IdentityHashMap<>();
	/** Live entries per caster (absent = 0). */
	private static final Map<UUID, Integer> PER_CASTER = new HashMap<>();

	private ActiveEffects() {}

	/**
	 * Starts {@code e} for {@code caster} in {@code level}; its first {@code tick} runs on the next server tick. False (nothing
	 * added) when {@link #full} for that caster, or when {@code e} is already running.
	 */
	public static boolean add(ServerLevel level, UUID caster, LingeringEffect e) {
		if (full(caster)) return false;
		return put(level, caster, e);
	}

	/** {@link #add} for the cast's own caster and level. */
	public static boolean add(CastContext ctx, LingeringEffect e) {
		return add(ctx.level(), ctx.caster().getUUID(), e);
	}

	/**
	 * Starts a teleport channel (Recall, Travel): only the per-caster cap applies, never the global one. False (nothing added)
	 * when {@code caster} is at {@link #MAX_PER_CASTER}, or when {@code e} is already running.
	 */
	public static boolean addChannel(ServerLevel level, UUID caster, LingeringEffect e) {
		if (count(caster) >= MAX_PER_CASTER) return false;
		return put(level, caster, e);
	}

	private static boolean put(ServerLevel level, UUID caster, LingeringEffect e) {
		Objects.requireNonNull(caster, "caster");
		Objects.requireNonNull(e, "effect");
		if (BY_EFFECT.containsKey(e)) return false;
		Entry en = new Entry(level, caster, e);
		ENTRIES.add(en);
		BY_EFFECT.put(e, en);
		PER_CASTER.merge(caster, 1, Integer::sum);
		return true;
	}

	/** True when {@link #add} would refuse an effect of {@code caster}: they are at their cap, or the server is at {@link #MAX}. */
	public static boolean full(UUID caster) {
		return BY_EFFECT.size() >= MAX || count(caster) >= MAX_PER_CASTER;
	}

	/** Stops {@code e} now (its {@code end} runs). False if it was not running. */
	public static boolean remove(LingeringEffect e) {
		Entry en = BY_EFFECT.get(e);
		if (en == null) return false;
		retire(en);
		end(en);
		return true;
	}

	public static boolean running(LingeringEffect e) {
		return BY_EFFECT.containsKey(e);
	}

	/** Running effects, server-wide. */
	public static int count() {
		return BY_EFFECT.size();
	}

	/** Running effects of {@code caster}. */
	public static int count(UUID caster) {
		Integer n = PER_CASTER.get(caster);
		return n == null ? 0 : n;
	}

	/** Once per server tick: ticks every effect in start order; effects added during this pass start next tick. */
	public static void tick() {
		if (ENTRIES.isEmpty()) return;
		Entry[] pass = ENTRIES.toArray(new Entry[0]);
		for (Entry en : pass) {
			if (!en.live) continue; // removed by an earlier effect this tick
			boolean keep;
			try {
				keep = en.effect.tick(en.level, ++en.age);
			} catch (RuntimeException ex) {
				LOGGER.error("[office] Lingering spell effect {} failed; stopping it", en.effect, ex);
				keep = false;
			}
			if (!keep && en.live) {
				retire(en);
				end(en);
			}
		}
		ENTRIES.removeIf(en -> !en.live);
	}

	/** Ends every effect (server stopping). */
	public static void endAll() {
		List<Entry> all = new ArrayList<>(BY_EFFECT.size());
		for (Entry en : ENTRIES) if (en.live) all.add(en);
		for (Entry en : all) retire(en);
		ENTRIES.clear();
		for (Entry en : all) end(en);
	}

	/** Takes {@code en} out of the counts and lookups (its list slot is compacted by {@link #tick}). */
	private static void retire(Entry en) {
		en.live = false;
		BY_EFFECT.remove(en.effect);
		PER_CASTER.computeIfPresent(en.caster, (k, n) -> n <= 1 ? null : n - 1);
	}

	private static void end(Entry en) {
		try {
			en.effect.end(en.level);
		} catch (RuntimeException ex) {
			LOGGER.error("[office] Ending lingering spell effect {} failed", en.effect, ex);
		}
	}
}
