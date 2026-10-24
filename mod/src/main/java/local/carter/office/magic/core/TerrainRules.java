package local.carter.office.magic.core;

public final class TerrainRules {
	public enum Verdict {
		ALLOW, BLOCK_ENTITY, IMMUNE, CLAIMED, SPAWN_PROTECTED, OUTSIDE_BORDER, BEYOND_RADIUS, OVER_BUDGET
	}

	private TerrainRules() {}

	/** First failing rule wins, in the order of the Verdict constants. */
	public static Verdict decide(boolean blockEntity, boolean immune, boolean claimed, boolean spawnProtected,
			boolean insideBorder, double distance, double radiusCap, int used, int budget) {
		if (blockEntity) return Verdict.BLOCK_ENTITY;
		if (immune) return Verdict.IMMUNE;
		if (claimed) return Verdict.CLAIMED;
		if (spawnProtected) return Verdict.SPAWN_PROTECTED;
		if (!insideBorder) return Verdict.OUTSIDE_BORDER;
		if (distance > radiusCap) return Verdict.BEYOND_RADIUS;
		if (used >= budget) return Verdict.OVER_BUDGET;
		return Verdict.ALLOW;
	}
}
