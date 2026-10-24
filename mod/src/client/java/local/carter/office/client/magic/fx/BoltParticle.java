// Adapted from Wildercord (MIT) https://github.com/defnotean/wildercord @9f5a21fe7964097492cd92ac64e4c4e05ef5236a
package local.carter.office.client.magic.fx;

import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * A lightning bolt from {@code from} to {@code to}: a jagged path (re-struck every two ticks from the seed, so every client draws
 * the same bolt) with a couple of short forks, drawn as a wide glow in the bolt's colour with a white core, each piece of line
 * turned to face the camera along its own direction (Wildercord's {@code Facing.along}). It flashes and fades over a few ticks.
 */
public final class BoltParticle extends SingleQuadParticle implements LightPipeline.Extent {
	private static final float WIDTH = 0.24F;
	private static final float BAND = 0.75F;

	private final Vec3 from;
	private final Vec3 to;
	private final long seed;
	private final int rgb;
	private final double reach;
	private final TextureAtlasSprite line;
	private final TextureAtlasSprite core;
	private final Vec3[] path = new Vec3[17];
	private final Vec3[][] forks = new Vec3[2][5];
	private int struck = -1;

	// While drawing.
	private QuadParticleRenderState state;
	private final Vector3f dir = new Vector3f();
	private final Vector3f zv = new Vector3f();
	private final Vector3f yv = new Vector3f();
	private final Vector3f tmp = new Vector3f();
	private final Matrix3f m = new Matrix3f();
	private final Quaternionf q = new Quaternionf();

	public BoltParticle(ClientLevel level, Vec3 from, Vec3 to, long seed, int argb, int life) {
		super(level, (from.x + to.x) / 2, (from.y + to.y) / 2, (from.z + to.z) / 2, MagicCircleRenderer.sprite("line"));
		this.from = from;
		this.to = to;
		this.seed = seed;
		this.rgb = argb & 0xFFFFFF;
		this.reach = from.distanceTo(to) / 2 + 1.5;
		this.lifetime = Math.max(2, life);
		this.line = MagicCircleRenderer.sprite("line");
		this.core = MagicCircleRenderer.sprite("core");
		this.hasPhysics = false;
		this.gravity = 0;
		this.xd = 0;
		this.yd = 0;
		this.zd = 0;
		strike(0);
	}

	/**
	 * The path for this strike, by midpoint displacement: each segment's middle is pushed sideways by up to a fifth of its length
	 * (so the bolt is jagged at every scale but never doubles back), and two short forks leave from points along it.
	 */
	private void strike(int n) {
		if (n == struck) {
			return;
		}
		struck = n;
		RandomSource r = RandomSource.create(seed * 31 + n);
		MagicCircleRenderer.Basis b = MagicCircleRenderer.basis(to.subtract(from));
		displace(path, from, to, b, r, 0.2);
		Vec3 d = to.subtract(from);
		double length = d.length();
		for (Vec3[] fork : forks) {
			Vec3 start = path[2 + r.nextInt(path.length - 4)];
			double sideX = r.nextDouble() * 2 - 1;
			double sideY = r.nextDouble() * 2 - 1;
			Vec3 side = new Vec3(b.right().x() * sideX + b.up().x() * sideY, b.right().y() * sideX + b.up().y() * sideY,
					b.right().z() * sideX + b.up().z() * sideY);
			Vec3 end = start.add(d.scale(0.12 + 0.08 * r.nextDouble())).add(side.scale(length * 0.1));
			displace(fork, start, end, MagicCircleRenderer.basis(end.subtract(start)), r, 0.25);
		}
	}

	/** Fills {@code pts} (length 2^k + 1) from {@code a} to {@code b}, each middle pushed off by up to {@code rough} of its span. */
	private static void displace(Vec3[] pts, Vec3 a, Vec3 b, MagicCircleRenderer.Basis basis, RandomSource r, double rough) {
		int last = pts.length - 1;
		pts[0] = a;
		pts[last] = b;
		for (int span = last; span > 1; span /= 2) {
			for (int i = 0; i + span <= last; i += span) {
				Vec3 p = pts[i];
				Vec3 q = pts[i + span];
				double off = p.distanceTo(q) * rough;
				double ox = (r.nextDouble() * 2 - 1) * off;
				double oy = (r.nextDouble() * 2 - 1) * off;
				pts[i + span / 2] = p.add(q).scale(0.5).add(basis.right().x() * ox + basis.up().x() * oy,
						basis.right().y() * ox + basis.up().y() * oy, basis.right().z() * ox + basis.up().z() * oy);
			}
		}
	}

	@Override
	public void tick() {
		age++;
		if (age >= lifetime) {
			remove();
		}
	}

	@Override
	public void extract(QuadParticleRenderState state, Camera camera, float partial) {
		float t = (age + partial) / lifetime;
		// A hard flash, a flicker, then gone.
		float a = (1F - t * t) * (age % 3 == 1 ? 0.55F : 1F);
		if (a <= 0.02F) {
			return;
		}
		strike(age / 2);
		this.state = state;
		Vec3 cam = camera.position();
		for (int i = 0; i + 1 < path.length; i++) {
			seg(path[i], path[i + 1], WIDTH, a, cam);
		}
		for (Vec3[] fork : forks) {
			for (int k = 0; k + 1 < fork.length; k++) {
				seg(fork[k], fork[k + 1], WIDTH * 0.55F * (1 - k / (float) fork.length), a * 0.8F, cam);
			}
		}
		this.state = null;
	}

	private void seg(Vec3 p0, Vec3 p1, float width, float a, Vec3 cam) {
		float x0 = (float) (p0.x - cam.x);
		float y0 = (float) (p0.y - cam.y);
		float z0 = (float) (p0.z - cam.z);
		dir.set((float) (p1.x - p0.x), (float) (p1.y - p0.y), (float) (p1.z - p0.z));
		float length = dir.length();
		if (length < 1.0E-4F) {
			return;
		}
		dir.div(length);
		int n = Math.max(1, Mth.ceil(length / (width / BAND)));
		float half = length / n / 2;
		int glow = MagicCircleRenderer.light(a * 0.9F, rgb);
		int white = MagicCircleRenderer.light(a, 0xFFFFFF);
		for (int i = 0; i < n; i++) {
			float s = (i + 0.5F) / n * length;
			float mx = x0 + dir.x * s;
			float my = y0 + dir.y * s;
			float mz = z0 + dir.z * s;
			along(mx, my, mz);
			state.add(getLayer(), mx, my, mz, q.x, q.y, q.z, q.w, half, line.getU0(), line.getU1(), line.getV0(), line.getV1(), glow,
					LightCoordsUtil.FULL_BRIGHT);
			state.add(getLayer(), mx, my, mz, q.x, q.y, q.z, q.w, half, core.getU0(), core.getU1(), core.getV0(), core.getV1(), white,
					LightCoordsUtil.FULL_BRIGHT);
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
		return LightPipeline.GLOW;
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
