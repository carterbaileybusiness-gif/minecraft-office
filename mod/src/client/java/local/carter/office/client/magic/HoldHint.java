package local.carter.office.client.magic;

import java.util.function.LongSupplier;

/** Rate limit for the "put the Grimoire in your off hand" hint: it may show at most once per {@link #INTERVAL_MS}. */
public final class HoldHint {
	public static final long INTERVAL_MS = 30_000;

	private final LongSupplier clockMs;
	private boolean shown;
	private long lastMs;

	public HoldHint(LongSupplier clockMs) {
		this.clockMs = clockMs;
	}

	/** True when the hint may be shown now (and counts it as shown). */
	public boolean tryShow() {
		long now = clockMs.getAsLong();
		if (shown && now - lastMs < INTERVAL_MS) {
			return false;
		}
		shown = true;
		lastMs = now;
		return true;
	}
}
