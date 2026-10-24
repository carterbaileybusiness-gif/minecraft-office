package local.carter.office.client.magic.fx.lightning;

import static local.carter.office.client.magic.fx.lightning.LightningLook.BLUE;
import static local.carter.office.client.magic.fx.lightning.LightningLook.CORE;
import static local.carter.office.client.magic.fx.lightning.LightningLook.YELLOW;

import local.carter.office.client.magic.fx.lightning.LightningLook.Style;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Chidori Strike's look as numbers (pure, so unit tests can check them): the arc styles, how the ultimate's 1500 fx budget is split
 * between its phases, the shake's reach and where random bits land.
 */
final class ChidoriLook {
	/** The budget's phases: the opening at the cast, the charge's six ticks, the impact, the scorched aftermath. They add up to 1500. */
	static final int OPENING = 170;
	static final int CHARGE = 520;
	static final int IMPACT = 560;
	static final int AFTERMATH = 250;

	/** The impact's shake: {@link #SHAKE} × nearness, 1 within {@link #SHAKE_FULL} of the impact, 0 at {@link #SHAKE_NONE} (0.8 at 32, so 0.88 there). */
	static final float SHAKE = 1.1F;
	static final double SHAKE_FULL = 24;
	static final double SHAKE_NONE = 64;
	static final int SHAKE_TICKS = 28;
	static final int HIT_STOP = 3;

	/** Height of the hand above the feet as it drives forward, and how far ahead of the body it is thrust. */
	static final double HAND_HEIGHT = 1.1;
	static final double HAND_AHEAD = 0.55;
	static final double HAND_SIDE = 0.3;
	/**
	 * Through the caster's own eyes: how far ahead of the hand the charge's channel reaches, and how far ahead of them the impact's
	 * flash, pillars and smoke are drawn.
	 */
	static final double FP_SPEAR = 5.5;
	static final double FP_AHEAD = 3.5;

	/** The heavy channel the hand drags behind it, a thinner blue one round it, the arcs it throws at the ground. */
	static final Style DRIVE = new Style(YELLOW, 0.34F, 0.16, 3, 7, true);
	static final Style DRIVE_STRAND = new Style(BLUE, 0.12F, 0.28, 1, 6, false);
	static final Style GROUNDING = new Style(CORE, 0.07F, 0.35, 1, 4, true);
	/** The chirping crackle in the hand: many tiny white arcs. */
	static final Style CHIRP = new Style(CORE, 0.05F, 0.45, 0, 3, false);
	/** The impact: pillars from the sky, cracks racing out over the ground, a second blue wave. */
	static final Style PILLAR = new Style(CORE, 0.55F, 0.14, 3, 10, true);
	static final Style PILLAR_STRAND = new Style(BLUE, 0.2F, 0.24, 2, 8, false);
	static final Style CRACK = new Style(YELLOW, 0.14F, 0.26, 2, 9, true);
	static final Style CRACK_BLUE = new Style(BLUE, 0.1F, 0.3, 1, 8, false);
	/** The aftermath: small arcs jumping in the trench. */
	static final Style EMBER_ARC = new Style(YELLOW, 0.06F, 0.4, 0, 4, true);

	/** Ground cracks in the first wave and the second, and the ticks after the impact the second comes. */
	static final int CRACKS = 10;
	static final int CRACKS_BLUE = 7;
	static final int SECOND_WAVE = 2;
	/** Arcs from the sky at the impact (beside the server's three visual-only bolts). */
	static final int PILLARS = 3;
	static final double PILLAR_HEIGHT = 22;
	/** The chirp: tiny arcs round the hand each tick of the charge, and at the cast. */
	static final int CHIRPS = 3;
	static final int CHIRPS_OPENING = 6;
	static final double CHIRP_REACH = 0.6;
	/** The aftermath: a step every {@link #AFTER_EVERY} ticks for {@link #AFTER_TICKS} after the impact. */
	static final int AFTER_EVERY = 4;
	static final int AFTER_TICKS = 44;

	private ChidoriLook() {}

	/** The phases' sum (the ultimate's whole budget). */
	static int total() {
		return OPENING + CHARGE + IMPACT + AFTERMATH;
	}

	/** Each of the charge's ticks gets an even share of {@link #CHARGE}. */
	static int chargeShare(int ticks) {
		return CHARGE / Math.max(1, ticks);
	}

	/** Each aftermath step gets an even share of {@link #AFTERMATH}. */
	static int afterShare() {
		return AFTERMATH / Math.max(1, (AFTER_TICKS + AFTER_EVERY - 1) / AFTER_EVERY);
	}

	/** Strength of the impact's shake for a camera {@code d} from it (never above {@code Shake.MAX}). */
	static float shake(double d) {
		return SHAKE * nearness(d);
	}

	static float nearness(double d) {
		return (float) Mth.clamp((SHAKE_NONE - d) / (SHAKE_NONE - SHAKE_FULL), 0, 1);
	}

	/** Where crack {@code i} of {@code n} ends, out over the ground from {@code at}, about {@code reach} long. */
	static Vec3 crack(Vec3 at, int i, int n, double reach, long seed) {
		RandomSource r = RandomSource.create(seed * 19 + i);
		double ang = Mth.TWO_PI * (i + 0.6 * (r.nextDouble() - 0.5)) / n;
		double len = reach * (0.7 + 0.45 * r.nextDouble());
		return at.add(Math.cos(ang) * len, 0, Math.sin(ang) * len);
	}

	/** Where pillar {@code i} starts in the sky over {@code at}. */
	static Vec3 pillarTop(Vec3 at, int i, long seed) {
		RandomSource r = RandomSource.create(seed * 23 + i);
		double lean = i == 0 ? 0.6 : 4.5;
		return at.add((r.nextDouble() - 0.5) * 2 * lean, PILLAR_HEIGHT * (0.85 + 0.3 * r.nextDouble()), (r.nextDouble() - 0.5) * 2 * lean);
	}

	/** The chirp's arcs at {@code step} round {@code hand}: pairs (from, to). */
	static Vec3[] chirp(Vec3 hand, long seed, int step, int count) {
		RandomSource r = RandomSource.create(seed * 29 + step);
		Vec3[] out = new Vec3[count * 2];
		for (int i = 0; i < count; i++) {
			Vec3 a = hand.add((r.nextDouble() - 0.5) * 0.3, (r.nextDouble() - 0.5) * 0.3, (r.nextDouble() - 0.5) * 0.3);
			double z = r.nextDouble() * 2 - 1;
			double ang = r.nextDouble() * Mth.TWO_PI;
			double h = Math.sqrt(1 - z * z);
			Vec3 d = new Vec3(h * Math.cos(ang), z, h * Math.sin(ang));
			out[2 * i] = a;
			out[2 * i + 1] = a.add(d.scale(CHIRP_REACH * (0.5 + 0.5 * r.nextDouble())));
		}
		return out;
	}
}
