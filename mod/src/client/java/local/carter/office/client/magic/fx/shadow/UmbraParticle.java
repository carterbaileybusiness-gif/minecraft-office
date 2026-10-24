package local.carter.office.client.magic.fx.shadow;

import local.carter.office.client.magic.fx.LightPipeline;
import local.carter.office.client.magic.fx.MagicCircleRenderer;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;

/**
 * A shadow visual drawn as one particle that lays many {@link Umbra} pieces a frame (darkness and violet light), in the magic
 * group, culled by its whole extent. Subclasses say where it is ({@link #anchor}), how far it reaches and what it lays
 * ({@link #draw}); it stands still unless they move it, and is gone after {@code life} ticks.
 */
abstract class UmbraParticle extends SingleQuadParticle implements LightPipeline.Extent {
	protected final Umbra umbra = new Umbra();

	UmbraParticle(ClientLevel level, Vec3 at, int life) {
		super(level, at.x, at.y, at.z, MagicCircleRenderer.sprite("glow"));
		this.lifetime = Math.max(1, life);
		this.hasPhysics = false;
		this.gravity = 0;
		this.xd = 0;
		this.yd = 0;
		this.zd = 0;
	}

	/** Where it is this tick (for culling). */
	protected abstract Vec3 anchor();

	/** Lays this frame's pieces; {@code t} is its age with the partial tick. */
	protected abstract void draw(double t);

	@Override
	public void tick() {
		xo = x;
		yo = y;
		zo = z;
		if (++age >= lifetime) remove();
	}

	@Override
	public final void extract(QuadParticleRenderState state, Camera camera, float partial) {
		umbra.begin(state, camera);
		draw(age + partial);
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
		return anchor().x;
	}

	@Override
	public double centreY() {
		return anchor().y;
	}

	@Override
	public double centreZ() {
		return anchor().z;
	}
}
