// Adapted from Wildercord (MIT) https://github.com/defnotean/wildercord @9f5a21fe7964097492cd92ac64e4c4e05ef5236a
package local.carter.office.client.magic.fx.water;

import org.jspecify.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import local.carter.office.client.magic.fx.LightPipeline;
import local.carter.office.client.magic.fx.MagicCircleRenderer;

import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the water lane's glowing shapes into a particle frame, as additive light ({@link LightPipeline#glow()}): round
 * {@code glow} blobs facing the camera (soft bodies of water: a tube is blobs packed along a line, each its own thickness) and
 * crisp {@code line} pieces laid end to end, each turned to face the camera along its own direction (Wildercord's
 * {@code Facing.along}, as in {@code BoltParticle}), for currents, fins, horns and rims. Counts every quad it lays, and with no
 * frame ({@link #dry}) only counts, so a visual knows its cost before it draws. Pieces within {@link #clear} of the camera are
 * skipped (the caster's own first-person view). Render thread only; one per particle (it keeps scratch vectors).
 */
final class WaterPen {
	/** One budget particle pays for this many quads for {@link #STEP_TICKS} ticks. */
	static final int QUADS_PER_PARTICLE = 50;
	static final int STEP_TICKS = 10;

	private final TextureAtlasSprite glow = MagicCircleRenderer.sprite("glow");
	private final TextureAtlasSprite line = MagicCircleRenderer.sprite("line");
	private final Vector3f dir = new Vector3f();
	private final Vector3f zv = new Vector3f();
	private final Vector3f yv = new Vector3f();
	private final Vector3f tmp = new Vector3f();
	private final Matrix3f m = new Matrix3f();
	private final Quaternionf q = new Quaternionf();

	private @Nullable QuadParticleRenderState state;
	private SingleQuadParticle.Layer layer = LightPipeline.GLOW;
	private Vec3 cam = Vec3.ZERO;
	private Quaternionf facing = new Quaternionf();
	private double clear2;
	/** Quads laid (or counted) since {@link #begin}. */
	int quads;

	/** Starts a frame: draws into {@code state} (null: count only) seen from {@code cam} turned {@code facing}, nothing within {@code clear}. */
	WaterPen begin(@Nullable QuadParticleRenderState state, Vec3 cam, Quaternionf facing, double clear) {
		this.state = state;
		this.cam = cam;
		this.facing = facing;
		this.clear2 = clear * clear;
		this.layer = LightPipeline.glow();
		this.quads = 0;
		this.water = false;
		return this;
	}

	/** Starts a count-only pass. */
	WaterPen dry() {
		return begin(null, Vec3.ZERO, new Quaternionf(), 0);
	}

	/** What {@code quads} quads cost for one {@link #STEP_TICKS}-tick step, in fx-budget particles (at least one). */
	static int cost(int quads) {
		return Math.max(1, (quads + QUADS_PER_PARTICLE - 1) / QUADS_PER_PARTICLE);
	}

	/**
	 * Switches blobs (and tubes) between light (false: added light, {@code light} is brightness) and water (true: vanilla's
	 * translucent layer, blended over what is behind, {@code light} is opacity), so a body can be real-looking water in daylight
	 * with light riding on it.
	 */
	WaterPen water(boolean on) {
		this.water = on;
		return this;
	}

	private boolean water;

	/** {@code p} moved {@code d} toward the camera (so light laid on a water body sits in front of it). */
	Vec3 towardCamera(Vec3 p, double d) {
		Vec3 to = cam.subtract(p);
		double l = to.length();
		return l < 1.0E-6 ? p : p.add(to.scale(Math.min(d, l * 0.5) / l));
	}

	/** One round blob at {@code p}, {@code size} across: light of brightness {@code light}, or water of that opacity ({@link #water}). */
	void blob(double px, double py, double pz, float size, int rgb, float light) {
		if (light <= 0.004F || size <= 0.005F) return;
		quads++;
		if (state == null) return;
		float mx = (float) (px - cam.x);
		float my = (float) (py - cam.y);
		float mz = (float) (pz - cam.z);
		if (mx * mx + my * my + mz * mz < clear2) return;
		SingleQuadParticle.Layer l = water ? SingleQuadParticle.Layer.TRANSLUCENT : layer;
		int colour = water ? Math.round(Math.min(1F, light) * 255) << 24 | (rgb & 0xFFFFFF) : LightPipeline.light(Math.min(1F, light), rgb);
		state.add(l, mx, my, mz, facing.x, facing.y, facing.z, facing.w, size / 2, glow.getU0(), glow.getU1(), glow.getV0(), glow.getV1(),
				colour, LightCoordsUtil.FULL_BRIGHT);
	}

	void blob(Vec3 p, float size, int rgb, float light) {
		blob(p.x, p.y, p.z, size, rgb, light);
	}

	/**
	 * Blobs along {@code pts} (first {@code n}), {@code width[i] × size} across at point i, spaced {@code step × width} (at least
	 * {@code minStep}), brightness {@code alpha[i] × light}: a soft tube of water.
	 */
	void tube(Vec3[] pts, float[] width, float[] alpha, int n, float size, float step, float minStep, int rgb, float light) {
		double carry = 0;
		for (int i = 0; i + 1 < n; i++) {
			Vec3 a = pts[i];
			Vec3 b = pts[i + 1];
			double len = a.distanceTo(b);
			if (len < 1.0E-5) continue;
			double s = carry;
			while (s < len) {
				double f = s / len;
				float w = (float) (width[i] + (width[i + 1] - width[i]) * f);
				float al = (float) (alpha[i] + (alpha[i + 1] - alpha[i]) * f);
				blob(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.z + (b.z - a.z) * f, w * size, rgb, al * light);
				s += Math.max(minStep, w * step);
			}
			carry = s - len;
		}
	}

	/** A crisp line along {@code pts} (first {@code n}), {@code width[i]} thick, brightness {@code alpha[i] × light}. */
	void line(Vec3[] pts, float[] width, float[] alpha, int n, int rgb, float light) {
		for (int i = 0; i + 1 < n; i++) {
			Vec3 p0 = pts[i];
			Vec3 p1 = pts[i + 1];
			dir.set((float) (p1.x - p0.x), (float) (p1.y - p0.y), (float) (p1.z - p0.z));
			float length = dir.length();
			if (length < 1.0E-5F) continue;
			dir.div(length);
			float s = 0;
			while (s < length) {
				float f = s / length;
				float w = Math.max(0.015F, width[i] + (width[i + 1] - width[i]) * f);
				float a = (alpha[i] + (alpha[i + 1] - alpha[i]) * f) * light;
				float step = Math.max(0.04F, w);
				if (a > 0.004F) piece(p0, Math.min(length, s + step / 2), step, rgb, a);
				s += step;
			}
		}
	}

	/** A straight crisp segment {@code a → b}, {@code width} thick (tapering to {@code tipWidth}), brightness {@code light}. */
	void segment(Vec3 a, Vec3 b, float width, float tipWidth, int rgb, float light) {
		seg[0] = a;
		seg[1] = b;
		segW[0] = width;
		segW[1] = tipWidth;
		segA[0] = 1;
		segA[1] = 1;
		line(seg, segW, segA, 2, rgb, light);
	}

	private final Vec3[] seg = new Vec3[2];
	private final float[] segW = new float[2];
	private final float[] segA = new float[2];

	/** One square line piece {@code length} long (and wide) centred {@code at} along {@link #dir} from {@code p0}. */
	private void piece(Vec3 p0, float at, float length, int rgb, float a) {
		quads++;
		if (state == null) return;
		float mx = (float) (p0.x - cam.x) + dir.x * at;
		float my = (float) (p0.y - cam.y) + dir.y * at;
		float mz = (float) (p0.z - cam.z) + dir.z * at;
		if (mx * mx + my * my + mz * mz < clear2) return;
		along(mx, my, mz);
		// The line sprite is drawn square: one piece is as long as it is wide (pieces abut).
		state.add(layer, mx, my, mz, q.x, q.y, q.z, q.w, length / 2, line.getU0(), line.getU1(), line.getV0(), line.getV1(),
				LightPipeline.light(Math.min(1F, a), rgb), LightCoordsUtil.FULL_BRIGHT);
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

	/** {@code a} blended {@code t} of the way to {@code b} (RGB). */
	static int mix(int a, int b, float t) {
		float k = Math.max(0, Math.min(1, t));
		int r = Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * k);
		int g = Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * k);
		int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * k);
		return (r << 16) | (g << 8) | bl;
	}
}
