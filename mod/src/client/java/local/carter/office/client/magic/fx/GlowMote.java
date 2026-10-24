package local.carter.office.client.magic.fx;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A mote of light: a small glow in the additive {@link LightPipeline#GLOW} layer that drifts, slows, flickers and fades.
 * Element motes (grimoire auras, bursts, Recall's rising sparks) are these, made through {@link FxKit} so they count
 * against the fx budget.
 */
public final class GlowMote extends SingleQuadParticle {
	/** How a mote moves after its first push. */
	public enum Drift {
		/** Floats up (fire, Recall). */
		RISE,
		/** Falls (water drips, earth grit). */
		FALL,
		/** Wanders sideways in loops (wind). */
		SWIRL,
		/** Flickers in place and blinks (lightning). */
		FLICKER,
		/** Sinks slowly (shadow). */
		SINK,
		/** Keeps its push, slowing (bursts). */
		COAST
	}

	private final float size;
	private final float baseAlpha;
	private final Drift drift;
	private final float phase;
	private final float red;
	private final float green;
	private final float blue;

	public GlowMote(ClientLevel level, Vec3 at, Vec3 velocity, int rgb, float size, int life, Drift drift, boolean spark) {
		super(level, at.x, at.y, at.z, MagicCircleRenderer.sprite(spark ? "spark" : "glow"));
		this.xd = velocity.x;
		this.yd = velocity.y;
		this.zd = velocity.z;
		this.size = size;
		this.quadSize = size;
		this.lifetime = Math.max(2, life);
		this.drift = drift;
		this.hasPhysics = false;
		this.friction = drift == Drift.COAST ? 0.86F : 0.94F;
		this.gravity = switch (drift) {
			case RISE -> -0.012F;
			case FALL -> 0.03F;
			case SINK -> 0.004F;
			default -> 0F;
		};
		this.phase = random.nextFloat() * Mth.TWO_PI;
		this.baseAlpha = 0.95F;
		this.red = ((rgb >> 16) & 0xFF) / 255F;
		this.green = ((rgb >> 8) & 0xFF) / 255F;
		this.blue = (rgb & 0xFF) / 255F;
		glow(baseAlpha);
	}

	@Override
	public void tick() {
		super.tick();
		if (removed) {
			return;
		}
		float t = age / (float) lifetime;
		switch (drift) {
			case SWIRL -> {
				xd += Mth.cos(age * 0.45F + phase) * 0.008F;
				zd += Mth.sin(age * 0.45F + phase) * 0.008F;
			}
			case FLICKER -> {
				xd *= 0.6;
				yd *= 0.6;
				zd *= 0.6;
			}
			default -> { }
		}
		float flicker = drift == Drift.FLICKER ? (random.nextFloat() < 0.35F ? 0.25F : 1F) : 0.85F + 0.15F * Mth.sin(age * 0.9F + phase);
		glow(baseAlpha * (1 - t * t) * flicker);
		quadSize = size * (1F - 0.5F * t);
	}

	/** Brightness in the colour, alpha kept full (the particle shader drops fragments under a tenth of alpha). */
	private void glow(float k) {
		if (ShaderCompat.current() == ShaderCompat.Path.PLAIN) {
			// Under a shader pack it is drawn translucent: the colour as is, the brightness in alpha.
			setColor(red, green, blue);
			setAlpha(k);
			return;
		}
		setColor(red * k, green * k, blue * k);
		setAlpha(1F);
	}

	@Override
	public int getLightCoords(float partial) {
		return LightCoordsUtil.FULL_BRIGHT;
	}

	@Override
	protected Layer getLayer() {
		return LightPipeline.glow();
	}
}
