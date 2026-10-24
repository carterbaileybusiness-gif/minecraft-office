package local.carter.office.client.magic.fx.shadow;

import local.carter.office.client.magic.fx.SpellFxRegistry;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.spell.SpellIds;

import net.minecraft.world.phys.Vec3;

/**
 * Shadow spell visuals: one {@code SpellFx} per spell, registered here, plus the palette they share. Shadow is drawn as
 * darkness that eats the light behind it ({@code LightPipeline.dark()}: black by day) edged with violet light
 * ({@code LightPipeline.glow()}: what carries it at night).
 */
public final class ShadowFx {
	/** The element colour (ARGB), for circles. */
	static final int ARGB = Element.SHADOW.argb();
	/** Element violet (RGB). */
	static final int VIOLET = Element.SHADOW.argb() & 0xFFFFFF;
	/** Hot magenta edge light. */
	static final int MAGENTA = 0xD24CFF;
	/** Pale lilac: the brightest edge, sparks. */
	static final int PALE = 0xE2C8FF;
	/** What darkness is tinted toward (it keeps a little violet as it takes the light away). */
	static final int TINT = VIOLET;

	private ShadowFx() {}

	public static void register() {
		SpellFxRegistry.register(SpellIds.SHADOW_BOLT, new ShadowBoltFx());
		SpellFxRegistry.register(SpellIds.GRAVITY_WELL, new GravityWellFx());
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

	/** {@code rgb} moved {@code t} of the way to white. */
	static int lighter(int rgb, float t) {
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		r += Math.round((255 - r) * t);
		g += Math.round((255 - g) * t);
		b += Math.round((255 - b) * t);
		return (r << 16) | (g << 8) | b;
	}
}
