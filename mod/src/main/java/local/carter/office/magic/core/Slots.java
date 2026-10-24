package local.carter.office.magic.core;

/** Spell slots on the wheel (slice index = slot) and the single base cost table. */
public final class Slots {
	public static final int SMALL_A = 0;
	public static final int MEDIUM_A = 1;
	public static final int SMALL_B = 2;
	public static final int MEDIUM_B = 3;
	public static final int ULTIMATE = 4;
	public static final int RECALL = 5;
	public static final int WHEEL_SLICES = 6;

	private static final int[] UNLOCK = {1, 2, 3, 5, 7, 1};
	/** {mana, cooldownTicks, xp, xpLevels} per slot. */
	private static final int[][] COST = {
		{8, 10, 2, 0},
		{25, 60, 5, 0},
		{12, 40, 2, 0},
		{30, 100, 5, 0},
		{60, 600, 20, 3},
		{0, 1200, 0, 0},
	};

	private Slots() {}

	public static int unlockLevel(int slot) { return UNLOCK[slot]; }

	/** A fresh copy: {mana, cooldownTicks, xp, xpLevels}. */
	public static int[] baseCost(int slot) { return COST[slot].clone(); }
}
