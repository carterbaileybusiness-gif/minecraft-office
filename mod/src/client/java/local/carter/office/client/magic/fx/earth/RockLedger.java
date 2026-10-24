package local.carter.office.client.magic.fx.earth;

import java.util.HashMap;
import java.util.Map;

/**
 * Bookkeeping for {@link EarthStones} (pure, so it is unit-tested): how many rocks are live, overall and per caster, and the
 * leashes that tie rocks to a cancellable {@code FxKit.simulate} timer.
 *
 * <p>Caps: at most {@link #MAX_LIVE} rocks at once, and at most {@link #MAX_PER_CASTER} of them for one caster, so one player's
 * walls and stones can never starve everyone else's.
 *
 * <p>A {@link Leash} is beaten once a tick by the timer that owns its rocks. The rocks live while it is beaten; when the timer
 * stops (its time is up, or {@code FxKit.cancel(casterId)} removed it) the beat stops, and the rocks go a tick later.
 */
public final class RockLedger {
	public static final int MAX_LIVE = 256;
	public static final int MAX_PER_CASTER = 160;

	private final Map<Integer, Integer> perCaster = new HashMap<>();
	private int live;

	/** Counts one more rock for {@code casterId}, or refuses (false) when either cap is reached. */
	public boolean admit(int casterId) {
		if (live >= MAX_LIVE || of(casterId) >= MAX_PER_CASTER) return false;
		perCaster.merge(casterId, 1, Integer::sum);
		live++;
		return true;
	}

	/** One of {@code casterId}'s rocks went. */
	public void release(int casterId) {
		Integer n = perCaster.get(casterId);
		if (n == null) return;
		if (n <= 1) perCaster.remove(casterId);
		else perCaster.put(casterId, n - 1);
		live--;
	}

	public int live() {
		return live;
	}

	/** How many more rocks {@code casterId} may have right now (both caps). */
	public int room(int casterId) {
		return Math.max(0, Math.min(MAX_LIVE - live, MAX_PER_CASTER - of(casterId)));
	}

	public int of(int casterId) {
		return perCaster.getOrDefault(casterId, 0);
	}

	public void clear() {
		perCaster.clear();
		live = 0;
	}

	/** Ties rocks to a timer: alive while it was beaten this tick or the last. */
	public static final class Leash {
		private long beat;

		public Leash(long now) {
			this.beat = now;
		}

		public void beat(long now) {
			beat = now;
		}

		public boolean alive(long now) {
			return now - beat <= 1;
		}
	}
}
