package local.carter.office.client.magic.fx.lightning;

/**
 * A share of an event's fx budget set aside for one part of a visual (one hop of a chain, say), so the parts that come first
 * can't starve the ones that come later. Drawing asks it first and the event's {@code FxBudget} second; what it refuses is not
 * drawn. Pure.
 */
final class Allowance {
	private int left;

	Allowance(int particles) {
		this.left = Math.max(0, particles);
	}

	/** No limit beyond the event's own budget. */
	static Allowance unlimited() {
		return new Allowance(Integer.MAX_VALUE);
	}

	/** Grants up to {@code want} (0 once spent) and counts it. */
	int take(int want) {
		int granted = Math.max(0, Math.min(want, left));
		left -= granted;
		return granted;
	}

	/** Pays {@code cost} whole, or nothing (false) if it can't. */
	boolean pay(int cost) {
		if (cost > left) return false;
		left -= cost;
		return true;
	}

	int left() {
		return left;
	}
}
