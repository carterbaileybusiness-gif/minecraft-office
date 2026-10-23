package local.carter.office.client.interact;

/**
 * Rate limit for live updates (pure): at most one send per {@link #INTERVAL_MS}, and the last value is always sent
 * once things stop changing. Call {@link #offer} on every change and {@link #poll} every tick; each returns true when
 * the caller should send its current value now.
 */
public final class SendThrottle {
	public static final long INTERVAL_MS = 100;

	private long lastSentMs = Long.MIN_VALUE / 2;
	private boolean dirty;

	/** A new value exists. True = send it now (else it is held back and {@link #poll} will send it). */
	public boolean offer(final long nowMs) {
		dirty = true;
		return poll(nowMs);
	}

	/** True = the held-back value is due now. */
	public boolean poll(final long nowMs) {
		if (dirty && nowMs - lastSentMs >= INTERVAL_MS) {
			dirty = false;
			lastSentMs = nowMs;
			return true;
		}
		return false;
	}

	/** True while a value is waiting to be sent. */
	public boolean pending() {
		return dirty;
	}
}
