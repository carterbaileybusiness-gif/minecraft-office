// Adapted from Wildercord (MIT) https://github.com/defnotean/wildercord @9f5a21fe7964097492cd92ac64e4c4e05ef5236a
package local.carter.office.client.magic.fx;

import org.joml.Vector3f;

import local.carter.office.client.magic.fx.MagicCircleRenderer.Frame;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * A summoning circle in the world, drawn by {@link MagicCircleRenderer} in the {@link LightPipeline#GROUP} group (culled by its
 * whole extent). Subclasses say where it is and how far it has opened each frame ({@link #pose}); {@link #fadeOut()} lets it go
 * over a few ticks (a cancelled channel, a released charge).
 */
public abstract class CircleParticle extends SingleQuadParticle implements LightPipeline.Extent {
	/** Render thread only: every circle draws through this one. */
	private static final MagicCircleRenderer DRAWER = new MagicCircleRenderer();
	static final int FADE_TICKS = 5;

	protected final Frame frame = new Frame();
	/** Ticks left of the fade out, or -1 while it lasts. */
	protected int fading = -1;
	/** Who cast it (-1: nobody), for {@link FxKit#cancel}. */
	final int casterId;

	protected CircleParticle(ClientLevel level, Vec3 at, int casterId) {
		super(level, at.x, at.y, at.z, MagicCircleRenderer.sprite("line"));
		this.casterId = casterId;
		this.gravity = 0;
		this.hasPhysics = false;
		this.xd = 0;
		this.yd = 0;
		this.zd = 0;
		this.lifetime = Integer.MAX_VALUE;
	}

	/**
	 * Fills {@code f} for this frame (centre, plane, colour, rings and their ages, scale, alpha, time). Return false to draw nothing.
	 */
	protected abstract boolean pose(float partial, Frame f);

	/** Lets it go: it fades out over {@link #FADE_TICKS} and is removed. */
	public void fadeOut() {
		if (fading < 0) {
			fading = FADE_TICKS;
		}
	}

	/** 1 while it lasts, then down to 0 over the fade. */
	protected float leaving(float partial) {
		return fading < 0 ? 1F : Mth.clamp((fading - partial) / FADE_TICKS, 0F, 1F);
	}

	@Override
	public void tick() {
		xo = x;
		yo = y;
		zo = z;
		age++;
		if (fading >= 0 && --fading <= 0) {
			remove();
		}
	}

	/** Moves the culling centre (the circle draws wherever {@link #pose} says). */
	protected void anchor(Vec3 at) {
		x = at.x;
		y = at.y;
		z = at.z;
	}

	@Override
	public void extract(QuadParticleRenderState state, Camera camera, float partial) {
		if (pose(partial, frame)) {
			DRAWER.draw(state, camera.position(), frame);
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
		return x;
	}

	@Override
	public double centreY() {
		return y;
	}

	@Override
	public double centreZ() {
		return z;
	}

	@Override
	public double reach() {
		// The frame's rays reach a ring-step past the outer ring; plus a margin for a circle that follows someone moving.
		return (MagicCircleRenderer.ringRadius(MagicCircleRenderer.MAX_RINGS) + 0.2) * Math.max(1F, frame.scale) + 1.0;
	}

	/**
	 * A circle standing still for {@code life} ticks: its rings appear one by one ({@code ringEvery} ticks apart), it holds, then
	 * it brightens and swells a little as it fades over the last third of its life.
	 *
	 * <p>Seen by its own caster in first person while it stands in their face ({@link MagicCircleRenderer#inCastersFace}: a cast
	 * circle held out in front of them), it gets the charge circle's first-person look: alpha × 0.3, no flare, and nothing within
	 * 0.35 × its radius of the middle, so the crosshair stays clear. That look eases in and out over {@link #EASE_TICKS} as the
	 * caster walks into or away from it (a switch to third person drops it at once). Third person, and everyone else's circles,
	 * are unchanged.
	 */
	static final class Fixed extends CircleParticle {
		static final float EASE_TICKS = 3F;
		private final Vec3 centre;
		private final MagicCircleRenderer.Basis basis;
		private final int rgb;
		private final float alpha;
		private final float scale;
		private final float radius;
		/** 0..1, how far the first-person look is in (-1 before the first frame), and that frame's time. */
		private float own = -1F;
		private float ownAt;
		private final int rings;
		private final int life;
		private final int ringEvery;

		Fixed(ClientLevel level, Vec3 centre, Vec3 normal, int argb, float radius, int rings, int life, int casterId) {
			super(level, centre, casterId);
			this.centre = centre;
			this.basis = MagicCircleRenderer.basis(normal);
			this.rgb = argb & 0xFFFFFF;
			int a = argb >>> 24;
			this.alpha = a == 0 ? 1F : a / 255F;
			this.rings = Mth.clamp(rings, 1, MagicCircleRenderer.MAX_RINGS);
			this.scale = radius / MagicCircleRenderer.ringRadius(this.rings - 1);
			this.radius = radius;
			this.life = Math.max(2, life);
			this.ringEvery = Math.max(1, Math.min(2, this.life / (2 * this.rings)));
			this.lifetime = this.life;
			MagicCircleRenderer.orientation(basis, frame.plane);
			frame.rgb = rgb;
			frame.glyph = MagicCircleRenderer.Glyph.ofColour(argb);
			frame.rings = this.rings;
			frame.x = centre.x;
			frame.y = centre.y;
			frame.z = centre.z;
		}

		@Override
		public void tick() {
			super.tick();
			if (age >= life) {
				remove();
			}
		}

		@Override
		protected boolean pose(float partial, Frame f) {
			float t = age + partial;
			for (int i = 0; i < rings; i++) {
				f.ringAge[i] = t - i * ringEvery;
			}
			float tail = Math.max(4F, life / 3F);
			float out = Mth.clamp((life - t) / tail, 0F, 1F);
			int seen = ownFirstPerson();
			float target = seen > 0 ? 1F : 0F;
			if (own < 0 || seen < 0) {
				// The first frame (no flash of the full circle in the caster's face), and a camera switch: at once.
				own = target;
			} else {
				float step = Math.max(0F, t - ownAt) / EASE_TICKS;
				own += Mth.clamp(target - own, -step, step);
			}
			ownAt = t;
			f.alpha = alpha * out * leaving(partial) * Mth.lerp(own, 1F, MagicCircleRenderer.FIRST_PERSON_ALPHA);
			f.scale = scale * (1F + 0.12F * (1F - out));
			f.flare = 0.6F * (1F - out) * (1F - own);
			f.clear = own > 0 ? MagicCircleRenderer.FIRST_PERSON_CLEAR * radius * own : 0F;
			f.time = t;
			return f.alpha > 0.01F;
		}

		/**
		 * 1 when its own caster sees it in first person standing in their face, 0 when they see it in first person out of the way,
		 * -1 when it is not the caster's first-person view at all (third person, someone else's circle).
		 */
		private int ownFirstPerson() {
			Minecraft mc = Minecraft.getInstance();
			Entity camera = mc.getCameraEntity();
			if (mc.player == null || camera != mc.player || casterId != mc.player.getId() || !mc.options.getCameraType().isFirstPerson()) {
				return -1;
			}
			Vec3 eye = mc.gameRenderer.mainCamera().position();
			Vector3f n = basis.normal();
			return MagicCircleRenderer.inCastersFace(centre.x, centre.y, centre.z, n.x, n.y, n.z, eye.x, eye.y, eye.z) ? 1 : 0;
		}

		@Override
		public double reach() {
			return (MagicCircleRenderer.ringRadius(MagicCircleRenderer.MAX_RINGS) + 0.2) * scale * 1.2 + 0.2;
		}
	}
}
