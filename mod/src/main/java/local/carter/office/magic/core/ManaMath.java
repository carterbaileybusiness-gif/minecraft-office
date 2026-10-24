package local.carter.office.magic.core;

public final class ManaMath {
	public static final int REGEN_PAUSE_TICKS = 20;

	private ManaMath() {}

	/** max = 100 + 10 x (level - 1). */
	public static int max(int level) { return 100 + 10 * (level - 1); }

	/** Ticks per +1 mana: max(2, 6 - level / 2), integer division. */
	public static int regenInterval(int level) { return Math.max(2, 6 - level / 2); }
}
