package local.carter.office.magic.core;

/** Grimoire level curve: levels 1-10, xpToNext(level) = 20 x level. */
public final class LevelCurve {
	public static final int MAX_LEVEL = 10;

	public record Progress(int level, int xp, int levelsGained) {}

	private LevelCurve() {}

	public static int xpToNext(int level) { return 20 * level; }

	/** Adds xp, levelling as many times as it pays for. At MAX_LEVEL xp is pinned at 0. */
	public static Progress add(int level, int xp, int gained) {
		int lv = level;
		long have = (long) xp + gained;
		int up = 0;
		while (lv < MAX_LEVEL && have >= xpToNext(lv)) {
			have -= xpToNext(lv);
			lv++;
			up++;
		}
		if (lv >= MAX_LEVEL) have = 0;
		return new Progress(lv, (int) have, up);
	}
}
