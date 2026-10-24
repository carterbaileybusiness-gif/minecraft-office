package local.carter.office.client.magic.fx.lightning;

import local.carter.office.client.magic.fx.FxBudget;

import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The Lightning lane's look as numbers (pure, so unit tests can check the budget): colours, arc styles, how many motes and sparks
 * each part spends, where random bits land (crackles, crawls, sky strikes), and Chain Lightning's budget plan. The visuals in
 * {@link Volts}, {@link SparkFx} and {@link ChainLightningFx} draw exactly these, so {@link #openingCost} and {@link #hopCost} are
 * what a chain really costs (for the targets' hitboxes it is given).
 */
final class LightningLook {
	/** Element yellow (Lightning {@code FFFFE94A}), a white-hot core, an electric blue and a soft yellow for blooms. */
	static final int YELLOW = 0xFFE94A;
	static final int CORE = 0xFFF7D6;
	static final int BLUE = 0x8CCFFF;
	static final int BLOOM = 0x8A7420;

	/** One arc's look: colour, thickness, jaggedness, forks, life and whether it has a white core. */
	record Style(int rgb, float width, double roughness, int forks, int life, boolean core) {
		int cost(Vec3 from, Vec3 to, long seed) {
			if (from.distanceTo(to) < MIN_ARC) return 0;
			return ArcPath.cost(from, to, seed, roughness, forks, width, life, core);
		}
	}

	/** Arcs shorter than this are not drawn. */
	static final double MIN_ARC = 0.05;

	// Chain Lightning: a heavy first channel out of the hand (Chidori), lighter hops.
	static final Style FIRST = new Style(YELLOW, 0.3F, 0.18, 3, 8, true);
	static final Style FIRST_STRAND = new Style(BLUE, 0.11F, 0.26, 1, 6, false);
	static final Style HOP = new Style(YELLOW, 0.16F, 0.18, 2, 7, true);
	static final Style HOP_STRAND = new Style(BLUE, 0.065F, 0.26, 1, 5, false);
	static final Style SKY = new Style(CORE, 0.12F, 0.18, 1, 5, true);
	// Spark: one bolt, heavier when charged.
	static final Style SPARK = new Style(YELLOW, 0.16F, 0.18, 2, 6, true);
	static final Style SPARK_CHARGED = new Style(YELLOW, 0.22F, 0.18, 3, 6, true);
	static final Style SPARK_STRAND = new Style(BLUE, 0.065F, 0.26, 1, 4, false);
	// Blink: the flash from where the caster was to where they are.
	static final Style BLINK = new Style(YELLOW, 0.2F, 0.16, 2, 5, true);
	static final Style BLINK_STRAND = new Style(BLUE, 0.075F, 0.3, 1, 4, false);
	// Thunder Spear: a straight, heavy spear of lightning, and the cracks it sends over the ground where it lands.
	static final Style SPEAR = new Style(YELLOW, 0.24F, 0.07, 1, 2, true);
	static final Style SPEAR_STRAND = new Style(BLUE, 0.08F, 0.3, 0, 2, false);
	static final Style SPEAR_CRACK = new Style(YELLOW, 0.09F, 0.3, 1, 7, true);
	// Tiny arcs: the crackle at the hand and the crawl over a struck body.
	static final Style CRACKLE = new Style(CORE, 0.04F, 0.4, 0, 3, false);
	static final Style CRAWL = new Style(BLUE, 0.045F, 0.35, 0, 3, false);
	static final Style CRAWL_GOLD = new Style(YELLOW, 0.045F, 0.35, 0, 3, false);

	/** The hand crackle: {@link #CRACKLE_ARCS} arcs every {@link #CRACKLE_EVERY} ticks for {@link #CRACKLE_TICKS}, up to {@link #CRACKLE_REACH} long. */
	static final int CRACKLE_ARCS = 2;
	static final int CRACKLE_EVERY = 2;
	static final int CRACKLE_TICKS = 6;
	static final double CRACKLE_REACH = 0.55;
	/** The crawl over a struck body: one arc and one mote every {@link #CRAWL_EVERY} ticks for {@link #CRAWL_TICKS}. */
	static final int CRAWL_EVERY = 4;
	static final int CRAWL_TICKS = 12;
	/** The scorch at a struck target's feet: sparks round a ring and flickers. */
	static final int GROUND_SPARKS = 4;
	static final int GROUND_MOTES = 2;

	private LightningLook() {}

	/** Motes at the hand as it fires. */
	static int handMotes(float charge) {
		return 6 + Math.round(4 * charge);
	}

	/** Chain Lightning's zap size at {@code hop}: smaller down the chain. */
	static float zapSize(int hop, float charge) {
		return (1F + 0.25F * charge) * (float) Math.pow(0.9, hop);
	}

	/** A zap's two glows, yellow motes, blue flickers and sparks. */
	static int zapGlows() {
		return 2;
	}

	static int zapMotes(float size) {
		return Math.round(4 * size);
	}

	static int zapFlickers(float size) {
		return Math.round(2 * size);
	}

	static int zapSparks(float size) {
		return Math.round(5 * size);
	}

	static int zapCost(float size) {
		return zapGlows() + zapMotes(size) + zapFlickers(size) + zapSparks(size);
	}

	static int groundCost() {
		return GROUND_SPARKS + GROUND_MOTES;
	}

	/** Steps of a timer that runs every {@code every} ticks for {@code ticks} (the first right away). */
	static int steps(int every, int ticks) {
		return (ticks + every - 1) / every;
	}

	/** The crackle's arcs at step {@code step}: pairs (from, to) round {@code hand}. */
	static Vec3[] crackle(Vec3 hand, long seed, int step) {
		RandomSource r = RandomSource.create(seed * 17 + step);
		Vec3[] out = new Vec3[CRACKLE_ARCS * 2];
		for (int i = 0; i < CRACKLE_ARCS; i++) {
			Vec3 a = hand.add(spread(r, 0.12));
			out[2 * i] = a;
			out[2 * i + 1] = a.add(unit(r).scale(CRACKLE_REACH * (0.5 + 0.5 * r.nextDouble())));
		}
		return out;
	}

	static int crackleCost(Vec3 hand, long seed) {
		int n = 0;
		for (int step = 0; step < steps(CRACKLE_EVERY, CRACKLE_TICKS); step++) {
			Vec3[] arcs = crackle(hand, seed, step);
			for (int i = 0; i < arcs.length; i += 2) n += CRACKLE.cost(arcs[i], arcs[i + 1], seed + 97L * step + i);
		}
		return n;
	}

	/** The crawl's arc at step {@code step} over {@code box} (a body's hitbox, a little inflated): from, to. */
	static Vec3[] crawl(AABB box, long seed, int step) {
		RandomSource r = RandomSource.create(seed * 13 + step);
		Vec3 a = onBox(box, r);
		Vec3 b = onBox(box, r);
		if (a.distanceToSqr(b) < 0.25) b = box.getCenter().add(box.getCenter().subtract(a));
		return new Vec3[] {a, b};
	}

	/** One crawl step's cost: its arc and one flickering mote. */
	static int crawlStepCost(AABB box, long seed, int step) {
		Vec3[] ab = crawl(box, seed, step);
		return CRAWL.cost(ab[0], ab[1], seed + 31L * step) + 1;
	}

	static int crawlCost(AABB box, long seed) {
		int n = 0;
		for (int step = 0; step < steps(CRAWL_EVERY, CRAWL_TICKS); step++) n += crawlStepCost(box, seed, step);
		return n;
	}

	/** Where a strike from the sky onto {@code at} starts: {@code height} up, leaning a little. */
	static Vec3 skyTop(Vec3 at, double height, long seed) {
		RandomSource r = RandomSource.create(seed);
		return at.add((r.nextDouble() - 0.5) * height * 0.4, height, (r.nextDouble() - 0.5) * height * 0.4);
	}

	static double skyHeight(float charge) {
		return 3.5 + 1.5 * charge;
	}

	// ---- Chain Lightning's budget plan ----

	/** Seeds of the chain's parts, from the event's seed (shared by the fx and the plan). */
	static long hopSeed(long seed, int hop) {
		return seed + 101L * hop;
	}

	static long strandSeed(long seed) {
		return seed * 7 + 3;
	}

	static long skySeed(long seed, int hop) {
		return seed + 53L * hop;
	}

	static long crawlSeed(long seed, int hop) {
		return seed + 7L * hop + 1;
	}

	/** What Chain Lightning spends before its first target: the circle, the hand motes and crackle, the first channel. */
	static int openingCost(Vec3 hand, Vec3 first, float charge, long seed) {
		return 1 + handMotes(charge) + crackleCost(hand, seed) + FIRST.cost(hand, first, seed) + FIRST_STRAND.cost(hand, first, strandSeed(seed));
	}

	/** Each struck target's share of what is left: {@code (400 − opening) / targets}. */
	static int allowance(int opening, int targets) {
		return Math.max(0, FxBudget.PARTICLES - opening) / Math.max(1, targets);
	}

	/**
	 * What one target's hop costs when nothing is cut: the channel into it (none for the first, whose channel is in the opening), its
	 * zap, its strike from the sky, the scorch at its feet and the crawl over {@code body}.
	 */
	static int hopCost(Vec3 from, Vec3 to, AABB body, int hop, float charge, long seed) {
		int n = 0;
		if (hop > 0) n += HOP.cost(from, to, hopSeed(seed, hop)) + HOP_STRAND.cost(from, to, strandSeed(hopSeed(seed, hop)));
		n += zapCost(zapSize(hop, charge));
		n += SKY.cost(skyTop(to, skyHeight(charge), skySeed(seed, hop)), to, skySeed(seed, hop));
		n += groundCost();
		n += crawlCost(body.inflate(0.06), crawlSeed(seed, hop));
		return n;
	}

	private static Vec3 onBox(AABB box, RandomSource r) {
		double x = box.minX + r.nextDouble() * (box.maxX - box.minX);
		double y = box.minY + r.nextDouble() * (box.maxY - box.minY);
		double z = box.minZ + r.nextDouble() * (box.maxZ - box.minZ);
		// Pushed out to a side face, so arcs skate over the skin rather than through the middle.
		switch (r.nextInt(4)) {
			case 0 -> x = box.minX;
			case 1 -> x = box.maxX;
			case 2 -> z = box.minZ;
			default -> z = box.maxZ;
		}
		return new Vec3(x, y, z);
	}

	private static Vec3 spread(RandomSource r, double s) {
		return new Vec3((r.nextDouble() * 2 - 1) * s, (r.nextDouble() * 2 - 1) * s, (r.nextDouble() * 2 - 1) * s);
	}

	private static Vec3 unit(RandomSource r) {
		double z = r.nextDouble() * 2 - 1;
		double a = r.nextDouble() * Math.PI * 2;
		double h = Math.sqrt(1 - z * z);
		return new Vec3(h * Math.cos(a), z, h * Math.sin(a));
	}
}
