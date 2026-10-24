package local.carter.office.magic.spell.shadow;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Pure geometry of Shadow Bind (Vec3 math only, no world access), shared by the server root ({@link ShadowBind}) and the client
 * tendrils, so the shadows you see hold the foe for exactly as long as the server does.
 *
 * <p><b>Root.</b> A bound foe is held where it stood ({@link #hold}): it may fall, but never step aside or rise. Something that
 * moved more than {@link #BREAK_DISTANCE} in one tick was teleported (an enderman's blink, a command) and breaks free
 * ({@link #breaks}): a root never drags anything back across the world.
 *
 * <p><b>Tendrils.</b> {@link #TENDRILS} tendrils rise from the ground round the foe and wind up it ({@link #tendril}): they grow
 * over {@link #GROW} ticks, writhe while they hold, and sink back over the last {@link #GROW}.
 */
public final class BindGeometry {
	public static final double RANGE = 20;
	/** Aim assist for the target ray. */
	public static final double FORGIVENESS = 0.5;
	public static final float DAMAGE = 4;
	public static final int BASE_LIFE = 60;
	public static final int LIFE_PER_CHARGE = 40;
	/** Slowness VI (amplifier 5). */
	public static final int SLOWNESS_AMPLIFIER = 5;
	/** A move longer than this in one tick is a teleport: the root breaks. */
	public static final double BREAK_DISTANCE = 4;
	public static final int TENDRILS = 6;
	/** Ticks a tendril takes to rise (and to sink at the end). */
	public static final int GROW = 6;

	private BindGeometry() {}

	/** Ticks the root lasts at charge 0..1: 60 + 40 × charge. */
	public static int life(float charge) {
		return BASE_LIFE + Math.round(LIFE_PER_CHARGE * Mth.clamp(charge, 0F, 1F));
	}

	/** Whether a bound foe that was at {@code last} and is now at {@code now} was teleported (and so breaks free). */
	public static boolean breaks(Vec3 last, Vec3 now) {
		return last.distanceToSqr(now) > BREAK_DISTANCE * BREAK_DISTANCE;
	}

	/**
	 * Where a foe held at {@code anchor} (its feet) is put back to when it is at {@code now}: the anchor's x and z, and the lower of
	 * the two heights (it may fall, never rise).
	 */
	public static Vec3 hold(Vec3 anchor, Vec3 now) {
		return new Vec3(anchor.x, Math.min(anchor.y, now.y), anchor.z);
	}

	/** The velocity a held foe keeps: none sideways, none up; a fall keeps falling. */
	public static Vec3 rooted(Vec3 velocity) {
		return new Vec3(0, Math.min(0, velocity.y), 0);
	}

	/** How much of a tendril is out at tick {@code t} of a root lasting {@code life}: 0 → 1 over {@link #GROW}, 1 → 0 over the last. */
	public static double reach(double t, int life) {
		double up = Mth.clamp(t / GROW, 0, 1);
		double down = Mth.clamp((life - t) / GROW, 0, 1);
		return Math.min(1 - (1 - up) * (1 - up), down);
	}

	/**
	 * A point on tendril {@code i} (of {@link #TENDRILS}) wound round a foe standing at {@code feet}, {@code width} wide and
	 * {@code height} tall, at tick {@code t} of a root lasting {@code life}. {@code s} runs 0 (its root in the ground,
	 * {@code width} out from the foe) to 1 (its tip, high on the foe and close in). It winds one and a half turns round the foe as
	 * it climbs and writhes a little; only the part {@link #reach} lets out is shown (beyond that it returns its last point).
	 */
	public static Vec3 tendril(Vec3 feet, double width, double height, int i, double s, double t, int life) {
		double out = reach(t, life);
		double u = Mth.clamp(Math.min(s, out), 0, 1);
		double base = Mth.TWO_PI * i / TENDRILS;
		double a = base + u * 1.5 * Mth.TWO_PI * (i % 2 == 0 ? 1 : -1) + 0.15 * Math.sin(t * 0.35 + i * 1.3);
		double r = width * (1.25 - 0.65 * u) + 0.06 * Math.sin(t * 0.5 + i * 2.1 + u * 6);
		double y = height * 0.85 * u;
		return new Vec3(feet.x + Math.cos(a) * r, feet.y + y, feet.z + Math.sin(a) * r);
	}
}
