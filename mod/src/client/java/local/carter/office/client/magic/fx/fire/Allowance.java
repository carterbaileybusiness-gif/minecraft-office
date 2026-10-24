package local.carter.office.client.magic.fx.fire;

import local.carter.office.client.magic.fx.FxKit;

/**
 * A share of an event's fx budget set aside for one part of a long visual (a fall, an impact, one fireball), so the parts that
 * come first can't starve the ones that come later. Drawing asks it first and the event's {@code FxBudget} second.
 */
final class Allowance {
	private int left;

	Allowance(int particles) {
		this.left = Math.max(0, particles);
	}

	/** Up to {@code want} from this share alone (0 once spent), for a kit call that charges the event's budget itself. */
	int take(int want) {
		int granted = Math.max(0, Math.min(want, left));
		left -= granted;
		return granted;
	}

	/** Up to {@code want} from this share and the event's budget both, for particles added straight to the level. */
	int spend(FxKit kit, int want) {
		return kit.budget().particles(take(want));
	}

	int left() {
		return left;
	}
}
