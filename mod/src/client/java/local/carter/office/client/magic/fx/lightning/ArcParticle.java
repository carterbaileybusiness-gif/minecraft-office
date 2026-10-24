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
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * The Lightning lane's arc of lightning: a jagged channel {@code from} → {@code to} (midpoint displacement from the seed, re-struck
 * every two ticks, so every client draws the same arc) with forks, drawn in three passes: a wide soft halo of round glows in the
 * colour, a crisp line in the colour and a white-hot core, each piece of line turned to face the camera along its own direction
 * (Wildercord's {@code Facing.along}, as {@code BoltParticle} does). Thickness is set per arc; it flashes, flickers and fades over its
 * life. {@link #quads} says how many quads it draws, so callers charge the fx budget for them.
 */
final class ArcParticle extends SingleQuadParticle implements LightPipeline.Extent {
	/** Points on the main path (2^k + 1) and on each fork. */
	private static final int PATH = 17;
	private static final int FORK = 5;
	/** Pieces of line are this share of their width long; halo blobs are spaced this share of the halo width. */
	private static final float BAND = 0.75F;
	private static final float HALO_STEP = 0.55F;

	private final Vec3 from;
	private final Vec3 to;
	private final long seed;
	private final int rgb;
	private final float width;
	private final float roughness;
	private final double reach;
	private final TextureAtlasSprite line;
	private final TextureAtlasSprite core;
	private final TextureAtlasSprite glow;
	private final Vec3[] path = new Vec3[PATH];
	private final Vec3[][] forks;
	private int struck = -1;

	private QuadParticleRenderState state;
	private final Vector3f dir = new Vector3f();
	private final Vector3f zv = new Vector3f();
	private final Vector3f yv = new Vector3f();
	private final Vector3f tmp = new Vector3f();
	private final Matrix3f m = new Matrix3f();
	private final Quaternionf q = new Quaternionf();

	/**
	 * @param width thickness of the crisp line (blocks; the halo is 3.5 × that, the core 0.45 ×)
	 * @param roughness how far each midpoint may be pushed sideways, as a share of its span (0.2 = a bolt, 0.3 = a wild crackle)
	 * @param forks short side branches (0..3)
	 */
	ArcParticle(ClientLevel level, Vec3 from, Vec3 to, long seed, int rgb, float width, float roughness, int forks, int life) {
		super(level, (from.x + to.x) / 2, (from.y + to.y) / 2, (from.z + to.z) / 2, MagicCircleRenderer.sprite("line"));
		this.from = from;
		this.to = to;
		this.seed = seed;
		this.rgb = rgb & 0xFFFFFF;
		this.width = width;
		this.roughness = roughness;
		this.forks = new Vec3[Mth.clamp(forks, 0, 3)][FORK];
		this.reach = from.distanceTo(to) / 2 + 1.5 + width * 2;
		this.lifetime = Math.max(2, life);
		this.line = MagicCircleRenderer.sprite("line");
		this.core = MagicCircleRenderer.sprite("core");
		this.glow = MagicCircleRenderer.sprite("glow");
		this.hasPhysics = false;
		this.gravity = 0;
		this.xd = 0;
		this.yd = 0;
		this.zd = 0;
		strike(0);
	}

	/**
	 * Quads one frame of an arc of {@code length} draws at most: the path runs about 1.25 × the straight length, each pass laying
	 * pieces along it, plus the forks (about a quarter of the length each, thinner).
	 */
	static int quads(double length, float width, int forks) {
		double path = 1.25 * length;
		double lines = 2 * (path / (width / BAND) + PATH);
		double halo = path / (width * 3.5F * HALO_STEP) + PATH;
		double fork = forks * 3 * (0.3 * length / (width * 0.5F / BAND) + FORK);
		return (int) Math.ceil(lines + halo + fork);
	}

	private void strike(int n) {
		if (n == struck) return;
		struck = n;
		RandomSource r = RandomSource.create(seed * 31 + n);
		MagicCircleRenderer.Basis b = MagicCircleRenderer.basis(to.subtract(from));
		displace(path, from, to, b, r, roughness);
		Vec3 d = to.subtract(from);
		double length = d.length();
		for (Vec3[] fork : forks) {
			Vec3 start = path[2 + r.nextInt(PATH - 4)];
			double sx = r.nextDouble() * 2 - 1;
			double sy = r.nextDouble() * 2 - 1;
			Vec3 side = new Vec3(b.right().x() * sx + b.up().x() * sy, b.right().y() * sx + b.up().y() * sy, b.right().z() * sx + b.up().z() * sy);
			Vec3 end = start.add(d.scale(0.1 + 0.1 * r.nextDouble())).add(side.scale(Math.min(1.6, length * 0.14)));
			displace(fork, start, end, MagicCircleRenderer.basis(end.subtract(start)), r, 0.3);
		}
	}

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
		if (age >= lifetime) remove();
	}

	@Override
	public void extract(QuadParticleRenderState state, Camera camera, float partial) {
		float t = (age + partial) / lifetime;
		// A hard flash, a flicker on every third tick, then gone.
		float a = (1F - t * t) * (age % 3 == 1 ? 0.6F : 1F);
		if (a <= 0.02F) return;
		strike(age / 2);
		this.state = state;
		Vec3 cam = camera.position();
		pass(path, width * 3.5F, HALO_STEP, glow, rgb, a * 0.3F, cam);
		pass(path, width, BAND, line, rgb, a * 0.95F, cam);
		pass(path, width * 0.45F, BAND, core, 0xFFFFFF, a, cam);
		for (Vec3[] fork : forks) {
			pass(fork, width * 0.5F, BAND, line, rgb, a * 0.8F, cam);
			pass(fork, width * 0.22F, BAND, core, 0xFFFFFF, a * 0.8F, cam);
		}
		this.state = null;
	}

	/** Pieces of {@code sprite}, {@code w} wide, along {@code pts}, spaced {@code step} × {@code w}. */
	private void pass(Vec3[] pts, float w, float step, TextureAtlasSprite sprite, int colour, float a, Vec3 cam) {
		int c = LightPipeline.light(a, colour);
		float piece = w / step;
		for (int i = 0; i + 1 < pts.length; i++) {
			Vec3 p0 = pts[i];
			Vec3 p1 = pts[i + 1];
			float x0 = (float) (p0.x - cam.x);
			float y0 = (float) (p0.y - cam.y);
			float z0 = (float) (p0.z - cam.z);
			dir.set((float) (p1.x - p0.x), (float) (p1.y - p0.y), (float) (p1.z - p0.z));
			float length = dir.length();
			if (length < 1.0E-4F) continue;
			dir.div(length);
			int n = Math.max(1, Mth.ceil(length / piece));
			float half = sprite == glow ? w / 2 : length / n / 2;
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
