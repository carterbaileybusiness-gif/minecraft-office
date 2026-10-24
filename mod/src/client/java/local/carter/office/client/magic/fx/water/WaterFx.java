package local.carter.office.client.magic.fx.water;

import local.carter.office.client.magic.fx.SpellFxRegistry;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.spell.SpellIds;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/** Water spell visuals: one {@code SpellFx} per spell, registered here, plus the water palette they share. */
public final class WaterFx {
	/** The element colour (ARGB), for circles. */
	static final int ARGB = Element.WATER.argb();
	/** Deep water blue (RGB). */
	static final int DEEP = Element.WATER.argb() & 0xFFFFFF;
	/** The body of the water: a brighter blue. */
	static final int BODY = 0x5CB2FF;
	/** Light where the water is thin. */
	static final int LIGHT = 0x9AD6FF;
	/** Foam and the hot core: almost white. */
	static final int FOAM = 0xE2F6FF;

	private WaterFx() {}

	public static void register() {
		SpellFxRegistry.register(SpellIds.WATER_BULLET, new WaterBulletFx());
		SpellFxRegistry.register(SpellIds.TIDAL_PUSH, new TidalPushFx());
		SpellFxRegistry.register(SpellIds.BUBBLE_SHIELD, new BubbleShieldFx());
		SpellFxRegistry.register(SpellIds.WATER_PRISON, new WaterPrisonFx());
		SpellFxRegistry.register(SpellIds.WATER_DRAGON, new WaterDragonFx());
	}

	/** {@code v} normalised (+Z if it has no length). */
	static Vec3 unit(Vec3 v) {
		double l = v.length();
		return l < 1.0E-6 || !Double.isFinite(l) ? new Vec3(0, 0, 1) : v.scale(1 / l);
	}

	/** Two unit vectors perpendicular to unit {@code n} and to each other. */
	static Vec3[] plane(Vec3 n) {
		Vec3 ref = Math.abs(n.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
		Vec3 u = unit(n.cross(ref));
		Vec3 v = n.cross(u);
		return new Vec3[] {u, v};
	}

	/** A random unit direction within {@code halfAngleDeg} of unit {@code axis}, spread evenly over the cap. */
	static Vec3 inCone(Vec3 axis, double halfAngleDeg, RandomSource r) {
		double cosMax = Math.cos(Math.toRadians(halfAngleDeg));
		double cos = 1 - r.nextDouble() * (1 - cosMax);
		double sin = Math.sqrt(Math.max(0, 1 - cos * cos));
		double phi = r.nextDouble() * Mth.TWO_PI;
		Vec3[] p = plane(axis);
		return axis.scale(cos).add(p[0].scale(sin * Math.cos(phi))).add(p[1].scale(sin * Math.sin(phi)));
	}
}
