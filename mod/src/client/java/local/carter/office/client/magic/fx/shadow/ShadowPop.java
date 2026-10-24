package local.carter.office.client.magic.fx.shadow;

import local.carter.office.client.magic.fx.LightPipeline;
import local.carter.office.client.magic.fx.MagicCircleRenderer;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A burst of shadow: a ball of darkness that swells and thins out, ringed by a violet shock edge running outward, for a few
 * ticks. Where a shadow bolt lands and where a gravity well collapses.
 */
final class ShadowPop extends SingleQuadParticle implements LightPipeline.Extent {
	private static final int RIM = 30;

	private final Vec3 at;
	private final float size;
	/** Daylight where it bursts: its wide shadow is stronger by day (at night taking light away soon saturates to black). */
	private final float day;
	private final Umbra umbra = new Umbra();

	ShadowPop(ClientLevel level, Vec3 at, float size, int life) {
		super(level, at.x, at.y, at.z, MagicCircleRenderer.sprite("glow"));
		this.at = at;
		this.size = size;
		this.day = Umbra.daylight(at);
		this.lifetime = Math.max(2, life);
		this.hasPhysics = false;
		this.gravity = 0;
		this.xd = 0;
		this.yd = 0;
		this.zd = 0;
	}

	/** Fx-budget particles it costs (its pieces a frame, per 50). */
	static int cost() {
		return Umbra.budgetCost(2 + Umbra.HAZE_PIECES + RIM + RIM / 2);
	}

	@Override
	public void tick() {
		xo = x;
		yo = y;
		zo = z;
		if (++age >= lifetime) remove();
	}

	@Override
	public void extract(QuadParticleRenderState state, Camera camera, float partial) {
		float f = Mth.clamp((age + partial) / lifetime, 0F, 1F);
		float grow = 1 - (1 - f) * (1 - f) * (1 - f);
		float r = size * (0.35F + 0.65F * grow);
		float a = (1 - f) * (1 - f);
		umbra.begin(state, camera);
		umbra.haze(at, r * 2.4F, (0.15F + 0.45F * day) * a);
		umbra.dark(at, r * 1.6F, a);
		umbra.dark(at, r * 1.6F, a * (1 - f));
		// The shock edge runs ahead of the darkness.
		double edge = size * (0.5 + 1.1 * grow);
		for (int i = 0; i < RIM; i++) {
			double ang = Mth.TWO_PI * i / RIM + f * 0.8;
			umbra.light(umbra.onScreenRing(at, edge, ang), size * 0.3F * (1 - 0.5F * f), 0.7F * (1 - f), i % 2 == 0 ? ShadowFx.VIOLET : ShadowFx.MAGENTA);
		}
		for (int i = 0; i < RIM; i += 2) {
			umbra.sparkle(umbra.onScreenRing(at, edge * 0.92, Mth.TWO_PI * i / RIM - f), size * 0.12F, 0.9F * (1 - f), ShadowFx.PALE);
		}
	}

	@Override
	public int getLightCoords(float partial) {
		return LightCoordsUtil.FULL_BRIGHT;
	}

	@Override
	protected Layer getLayer() {
		return LightPipeline.glow();
	}

	@Override
	public ParticleRenderType getGroup() {
		return LightPipeline.GROUP;
	}

	@Override
	public double centreX() {
		return at.x;
	}

	@Override
	public double centreY() {
		return at.y;
	}

	@Override
	public double centreZ() {
		return at.z;
	}

	@Override
	public double reach() {
		return size * 3 + 0.5;
	}
}
