// Adapted from Wildercord (MIT) https://github.com/defnotean/wildercord @9f5a21fe7964097492cd92ac64e4c4e05ef5236a
package local.carter.office.client.magic.fx.lightning;

import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import local.carter.office.client.magic.fx.LightPipeline;
import local.carter.office.client.magic.fx.MagicCircleRenderer;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;

/**
 * The Lightning lane's arc of lightning: a jagged channel {@code from} → {@code to} ({@link ArcPath}: midpoint displacement from the
 * seed, re-struck every two ticks, so every client draws the same arc) with forks, drawn in three passes: a wide soft halo of round
 * glows in the colour, a crisp line in the colour and a white-hot core, each piece of line turned to face the camera along its own
 * direction (Wildercord's {@code Facing.along}, as {@code BoltParticle} does). It flashes, flickers and fades over its life.
 * {@link #quads()} is exactly what a frame lays (the heaviest strike), so callers charge the fx budget for it.
 */
final class ArcParticle extends SingleQuadParticle implements LightPipeline.Extent {
	private final int rgb;
	private final float width;
	private final boolean core;
	private final double reach;
	private final TextureAtlasSprite line;
	private final TextureAtlasSprite coreSprite;
	private final TextureAtlasSprite glow;
	private final ArcPath.Strike[] strikes;
	private final int quads;

	private QuadParticleRenderState state;
	private final Vector3f dir = new Vector3f();
	private final Vector3f zv = new Vector3f();
	private final Vector3f yv = new Vector3f();
	private final Vector3f tmp = new Vector3f();
	private final Matrix3f m = new Matrix3f();
	private final Quaternionf q = new Quaternionf();

	/**
	 * @param width thickness of the crisp line (blocks; the halo is {@link ArcPath#HALO} × that, the core {@link ArcPath#CORE} ×)
	 * @param roughness how far each midpoint may be pushed sideways, as a share of its span (at most {@link ArcPath#MAX_OFFSET})
	 * @param forks short side branches (0..3)
	 * @param core whether a white-hot core is drawn down the middle (thin secondary strands go without)
	 */
	ArcParticle(ClientLevel level, Vec3 from, Vec3 to, long seed, int rgb, float width, float roughness, int forks, int life, boolean core) {
		super(level, (from.x + to.x) / 2, (from.y + to.y) / 2, (from.z + to.z) / 2, MagicCircleRenderer.sprite("line"));
		this.rgb = rgb & 0xFFFFFF;
		this.width = width;
		this.core = core;
		this.reach = from.distanceTo(to) / 2 + 2.5 + width * 2;
		this.lifetime = Math.max(2, life);
		this.strikes = ArcPath.strikes(from, to, seed, roughness, forks, lifetime);
		this.quads = ArcPath.quads(strikes, width, core);
		this.line = MagicCircleRenderer.sprite("line");
		this.coreSprite = MagicCircleRenderer.sprite("core");
		this.glow = MagicCircleRenderer.sprite("glow");
		this.hasPhysics = false;
		this.gravity = 0;
		this.xd = 0;
		this.yd = 0;
		this.zd = 0;
	}

	/** Quads a frame of this arc lays at most. */
	int quads() {
		return quads;
	}

	@Override
	public void tick() {
		age++;
		if (age >= lifetime) remove();
	}

	@Override
	public void extract(QuadParticleRenderState state, Camera camera, float partial) {
		float t = (age + partial) / lifetime;
		// A hard flash, a flicker on every third tick, then gone.
		float a = (1F - t * t) * (age % 3 == 1 ? 0.6F : 1F);
		if (a <= 0.02F) return;
		ArcPath.Strike s = strikes[Math.min(strikes.length - 1, age / 2)];
		this.state = state;
		Vec3 cam = camera.position();
		pass(s.path(), width * ArcPath.HALO, ArcPath.haloStep(width), glow, rgb, a * 0.3F, cam);
		pass(s.path(), width, ArcPath.lineStep(width), line, rgb, a * 0.95F, cam);
		if (core) pass(s.path(), width * ArcPath.CORE, ArcPath.lineStep(width * ArcPath.CORE), coreSprite, 0xFFFFFF, a, cam);
		for (Vec3[] fork : s.forks()) {
			pass(fork, width * ArcPath.FORK_WIDTH, ArcPath.lineStep(width * ArcPath.FORK_WIDTH), line, rgb, a * 0.8F, cam);
			if (core) pass(fork, width * ArcPath.FORK_CORE, ArcPath.lineStep(width * ArcPath.FORK_CORE), coreSprite, 0xFFFFFF, a * 0.8F, cam);
		}
		this.state = null;
	}

	/**
	 * Pieces of {@code sprite}, {@code w} wide, along {@code pts}, one every {@code step} ({@link ArcPath#pieces} per segment, the
	 * count the budget was charged for). Line pieces are as long as their share of the segment; glow blobs are {@code w} square.
	 */
	private void pass(Vec3[] pts, float w, float step, TextureAtlasSprite sprite, int colour, float a, Vec3 cam) {
		int c = LightPipeline.light(a, colour);
		for (int i = 0; i + 1 < pts.length; i++) {
			Vec3 p0 = pts[i];
			Vec3 p1 = pts[i + 1];
			float length = ArcPath.segment(p0, p1);
			int n = ArcPath.pieces(length, step);
			if (n == 0) continue;
			float x0 = (float) (p0.x - cam.x);
			float y0 = (float) (p0.y - cam.y);
			float z0 = (float) (p0.z - cam.z);
			dir.set((float) (p1.x - p0.x), (float) (p1.y - p0.y), (float) (p1.z - p0.z)).div(length);
			float half = sprite == glow ? w / 2 : Math.max(w / 2, length / n / 2);
			for (int k = 0; k < n; k++) {
				float s = (k + 0.5F) / n * length;
				float mx = x0 + dir.x * s;
				float my = y0 + dir.y * s;
				float mz = z0 + dir.z * s;
				along(mx, my, mz);
				state.add(getLayer(), mx, my, mz, q.x, q.y, q.z, q.w, half, sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1(), c,
						LightCoordsUtil.FULL_BRIGHT);
			}
		}
	}

	/** Into {@link #q}: a piece at (mx, my, mz) (camera-relative) lying along {@link #dir}, facing the camera as squarely as it can. */
	private void along(float mx, float my, float mz) {
		zv.set(-mx, -my, -mz);
		zv.sub(tmp.set(dir).mul(zv.dot(dir)));
		if (zv.lengthSquared() < 1.0E-6F) {
			zv.set(0, 1, 0).sub(tmp.set(dir).mul(dir.y));
			if (zv.lengthSquared() < 1.0E-6F) zv.set(1, 0, 0).sub(tmp.set(dir).mul(dir.x));
		}
		zv.normalize();
		yv.set(zv).cross(dir).normalize();
		q.setFromNormalized(m.set(dir, yv, zv));
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
		return reach;
	}
}
