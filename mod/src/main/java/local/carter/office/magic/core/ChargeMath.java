package local.carter.office.magic.core;

public final class ChargeMath {
	/** A release before this many ticks is a tap. */
	public static final int TAP_TICKS = 8;
	/** Full charge (2 s). */
	public static final int FULL_TICKS = 40;
	private static final int STEPS = 32;

	private ChargeMath() {}

	/** clamp((ticks - 8) / 32, 0, 1). */
	public static float charge(int ticksHeld) {
		float c = (ticksHeld - TAP_TICKS) / (float) (FULL_TICKS - TAP_TICKS);
		return Math.max(0f, Math.min(1f, c));
	}

	/** Rings shown: 1 + floor(charge x 4), 1-5. */
	public static int rings(int ticksHeld) { return 1 + (int) Math.floor(charge(ticksHeld) * 4); }

	/** Mana cost: round(base x (1 + 0.5 x charge)); ultimates always cost base. */
	public static int cost(int base, float charge, boolean ultimate) {
		if (ultimate) return base;
		return Math.round(base * (1f + 0.5f * charge));
	}

	/** Largest multiple of 1/32 not above charge whose cost fits in mana, or -1 if even the base cost does not fit. */
	public static float affordableCharge(int mana, int base, float charge) {
		if (cost(base, 0f, false) > mana) return -1f;
		for (int step = (int) Math.floor(charge * STEPS); step > 0; step--) {
			float c = step / (float) STEPS;
			if (cost(base, c, false) <= mana) return c;
		}
		return 0f;
	}

	/** (1 + charge) x (1 + 0.05 x (level - 1)). */
	public static float power(float charge, int level) { return (1f + charge) * (1f + 0.05f * (level - 1)); }
}
