package local.carter.office.magic.spell.lightning;

import java.util.function.Consumer;
import java.util.function.Predicate;

import net.minecraft.world.phys.Vec3;

/**
 * Pure path finding for the Lightning lane's flashes of movement (Blink, and Chidori Strike's charge), on points only: no world
 * access, so the server and a unit test run the same rules. The body walks from {@code from} along {@code dir} in steps of
 * {@link #STEP}, each step taken from the last place the body fitted. Where something is in the way it may rise up to
 * {@link #MAX_RISE} (in {@link #RISE_STEP}s) to get over it, so it slides along the ground it is aimed into and over a
 * one-block bump; where nothing fits it stops at the last place that did. It never passes through anything: every place it
 * stands fits, and no two are more than a step apart.
 */
public final class BlinkPath {
	/** Distance along {@code dir} between the places the body is checked at. */
	public static final double STEP = 0.25;
	/** Most a single step may rise to get over something in the way. */
	public static final double MAX_RISE = 1.0;
	public static final double RISE_STEP = 0.25;

	/** Where a walk ended: {@code end} (feet), how far it went along the direction, and whether something stopped it short. */
	public record Walk(Vec3 end, double travelled, boolean blocked) {}

	private BlinkPath() {}

	/**
	 * Walks up to {@code distance} from {@code from} along {@code dir}. {@code fits} says whether the body (feet at the point) fits
	 * there. {@code from} itself is not checked: the body is already standing there.
	 */
	public static Walk walk(Vec3 from, Vec3 dir, double distance, Predicate<Vec3> fits) {
		return walk(from, dir, distance, fits, at -> {});
	}

	/** As {@link #walk(Vec3, Vec3, double, Predicate)}; {@code onStep} gets every place the body stands on the way, in order. */
	public static Walk walk(Vec3 from, Vec3 dir, double distance, Predicate<Vec3> fits, Consumer<Vec3> onStep) {
		double len = dir.length();
		if (!(len > 1.0E-9) || !(distance > 0) || !Double.isFinite(distance)) return new Walk(from, 0, false);
		Vec3 d = dir.scale(1 / len);
		Vec3 at = from;
		double done = 0;
		while (done < distance - 1.0E-9) {
			double step = Math.min(STEP, distance - done);
			Vec3 next = at.add(d.scale(step));
			Vec3 ok = null;
			for (double rise = 0; rise <= MAX_RISE + 1.0E-9; rise += RISE_STEP) {
				Vec3 c = rise == 0 ? next : next.add(0, rise, 0);
				if (fits.test(c)) {
					ok = c;
					break;
				}
			}
			if (ok == null) return new Walk(at, done, true);
			at = ok;
			onStep.accept(at);
			done += step;
		}
		return new Walk(at, done, false);
	}
}
