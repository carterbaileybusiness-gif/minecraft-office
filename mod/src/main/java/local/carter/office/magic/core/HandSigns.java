package local.carter.office.magic.core;

import java.util.Random;

public final class HandSigns {
	/** Extra server-side ticks allowed on top of the client limit. */
	public static final int GRACE_TICKS = 10;

	private HandSigns() {}

	/** 6 arrows at level 7, 5 at level 8, 4 at level 9+. */
	public static int length(int level) {
		if (level <= 7) return 6;
		if (level == 8) return 5;
		return 4;
	}

	public static int limitTicks(int length) { return 20 + 12 * length; }

	/** Deterministic sequence from the seed with no two equal arrows in a row. */
	public static Dir[] sequence(long seed, int length) {
		Random rng = new Random(seed);
		Dir[] all = Dir.values();
		Dir[] out = new Dir[length];
		for (int i = 0; i < length; i++) {
			Dir d;
			do {
				d = all[rng.nextInt(all.length)];
			} while (i > 0 && d == out[i - 1]);
			out[i] = d;
		}
		return out;
	}

	public static boolean matches(Dir[] expected, Dir[] answer) {
		if (expected == null || answer == null || expected.length != answer.length) return false;
		for (int i = 0; i < expected.length; i++) if (expected[i] != answer[i]) return false;
		return true;
	}

	/** Mana spent on a fizzle: ceil(base / 2). */
	public static int fizzleMana(int base) { return (base + 1) / 2; }
}
