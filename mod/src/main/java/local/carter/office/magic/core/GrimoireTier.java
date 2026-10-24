package local.carter.office.magic.core;

public final class GrimoireTier {
	private GrimoireTier() {}

	/** Look tier: 0 for L1-2, 1 for L3-4, 2 for L5-6, 3 for L7+. */
	public static int of(int level) {
		if (level <= 2) return 0;
		if (level <= 4) return 1;
		if (level <= 6) return 2;
		return 3;
	}
}
