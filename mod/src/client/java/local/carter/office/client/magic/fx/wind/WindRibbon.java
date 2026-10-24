// Adapted from Wildercord (MIT) https://github.com/defnotean/wildercord @9f5a21fe7964097492cd92ac64e4c4e05ef5236a
package local.carter.office.client.magic.fx.wind;

import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import local.carter.office.client.magic.fx.LightPipeline;
import local.carter.office.client.magic.fx.MagicCircleRenderer;

import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;

/**
 * Glowing strokes for the wind visuals, drawn along a polyline in two passes: a soft tube of round {@code glow} blobs packed
 * closely (so curves stay smooth and no piece edges show), and over it a crisp line of {@code line} pieces laid end to end
 * without overlap, each turned to face the camera along its own direction (Wildercord's {@code Facing.along}, as in
 * {@code BoltParticle}). Thickness and brightness are given per point and blended between, so a stroke can taper to nothing
 * (crescent tips, the tail of a wind line). Render thread only; one instance per particle (it keeps scratch vectors).
 */
final class WindRibbon {
	/** Glow blobs: spacing and size as shares of the thickness, and their brightness (they overlap a few deep). */
	private static final float BLOB_STEP = 0.3F;
	private static final float BLOB_SIZE = 1.5F;
	private static final float BLOB_LIGHT = 0.22F;
	/** A blob is at least this many steps across. */
	private static final float BLOB_SPAN = 2.2F;
	/** The crisp line: its pieces as a share of the thickness. */
	private static final float LINE_SIZE = 0.55F;
	/** Pieces are never closer than this (blocks), however thin the stroke gets: tapered tips stay cheap. */
	private static final float MIN_STEP = 0.04F;
	/** Quads one budget particle pays for ({@link #budgetCost}). */
	static final int QUADS_PER_PARTICLE = 50;

	private final Vector3f dir = new Vector3f();
	private final Vector3f zv = new Vector3f();
	private final Vector3f yv = new Vector3f();
	private final Vector3f tmp = new Vector3f();
	private final Matrix3f m = new Matrix3f();
	private final Quaternionf q = new Quaternionf();
	private final TextureAtlasSprite line = MagicCircleRenderer.sprite("line");
	private final TextureAtlasSprite glow = MagicCircleRenderer.sprite("glow");
	/** Coarse strokes use the larger, smoother glow (big blobs of the small one show its texels as beads). */
	private final TextureAtlasSprite soft = MagicCircleRenderer.sprite("soft");

	/**
	 * Draws {@code pts} (world positions) with thickness {@code width[i]} and brightness {@code alpha[i]} at point {@code i}: the
	 * soft glow in {@code rgb}, and the crisp line in {@code coreRgb} at {@code coreShare} of the brightness (0: the line in
	 * {@code rgb} at half brightness instead).
	 */
	void draw(QuadParticleRenderState state, Vec3 cam, Vec3[] pts, float[] width, float[] alpha, int rgb, float coreShare, int coreRgb) {
		draw(state, cam, pts, width, alpha, rgb, coreShare, coreRgb, 1F);
	}

	/**
	 * As {@link #draw(QuadParticleRenderState, Vec3, Vec3[], float[], float[], int, float, int)}, with the glow blobs spaced
	 * {@code coarse} times wider (and brighter to match): for big strokes seen from afar (a tornado's funnel), at a fraction of
	 * the quads ({@link #pieces(Vec3[], float[], float, boolean)}). A negative {@code coreShare} draws the soft glow alone, with no
	 * crisp line.
	 */
	void draw(QuadParticleRenderState state, Vec3 cam, Vec3[] pts, float[] width, float[] alpha, int rgb, float coreShare, int coreRgb, float coarse) {
		pass(state, cam, pts, width, alpha, BLOB_STEP * coarse, BLOB_SIZE, coarse > 1 ? soft : glow, rgb, BLOB_LIGHT * Math.min(2F, coarse));
		if (coreShare < 0) {
			return;
		}
		if (coreShare > 0) {
			pass(state, cam, pts, width, alpha, LINE_SIZE, LINE_SIZE, line, coreRgb, coreShare);
		} else {
			pass(state, cam, pts, width, alpha, LINE_SIZE, LINE_SIZE, line, rgb, 0.5F);
		}
	}

	/** Pieces of {@code sprite} every {@code step} × thickness along the line, {@code size} × thickness across. */
	private void pass(QuadParticleRenderState state, Vec3 cam, Vec3[] pts, float[] width, float[] alpha, float step, float size,
			TextureAtlasSprite sprite, int rgb, float light) {
		var layer = LightPipeline.glow();
		// No gaps: a line piece is at least as long as the step to the next, and a glow blob (whose light fades well inside its
		// quad) at least 2.2 steps across, so thin strokes (where MIN_STEP sets the spacing) never break into dashes or beads.
		float span = sprite == line ? 1F : BLOB_SPAN;
		float carry = 0;
		for (int i = 0; i + 1 < pts.length; i++) {
			Vec3 p0 = pts[i];
			Vec3 p1 = pts[i + 1];
			dir.set((float) (p1.x - p0.x), (float) (p1.y - p0.y), (float) (p1.z - p0.z));
			float length = dir.length();
			if (length < 1.0E-5F) {
				continue;
			}
			dir.div(length);
			float s = carry;
			while (s < length) {
				float f = s / length;
				float w = Math.max(0.02F, width[i] + (width[i + 1] - width[i]) * f);
				float a = (alpha[i] + (alpha[i + 1] - alpha[i]) * f) * light;
				float advance = Math.max(MIN_STEP, w * step);
				// Centred on its own stretch even past the corner, so it meets the next stretch's first piece with no gap.
				float at = s + advance / 2;
				if (a > 0.004F) {
					float mx = (float) (p0.x - cam.x) + dir.x * at;
					float my = (float) (p0.y - cam.y) + dir.y * at;
					float mz = (float) (p0.z - cam.z) + dir.z * at;
					along(mx, my, mz);
					state.add(layer, mx, my, mz, q.x, q.y, q.z, q.w, Math.max(w * size, advance * span) / 2, sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1(),
							LightPipeline.light(a, rgb), LightCoordsUtil.FULL_BRIGHT);
				}
				s += advance;
			}
			carry = s - length;
		}
	}

	/** Quads {@link #draw} lays for {@code pts} with thicknesses {@code width} (both passes). */
	static int pieces(Vec3[] pts, float[] width) {
		return pieces(pts, width, 1F, true);
	}

	/** Quads the coarse {@link #draw} lays for {@code pts} with thicknesses {@code width}: the glow, and the crisp line if {@code line}. */
	static int pieces(Vec3[] pts, float[] width, float coarse, boolean line) {
		return count(pts, width, BLOB_STEP * coarse) + (line ? count(pts, width, LINE_SIZE) : 0);
	}

	/** What a visual drawing {@code quads} quads a frame costs in fx-budget particles: one per {@value #QUADS_PER_PARTICLE}, at least one. */
	static int budgetCost(int quads) {
		return Math.max(1, (quads + QUADS_PER_PARTICLE - 1) / QUADS_PER_PARTICLE);
	}

	private static int count(Vec3[] pts, float[] width, float step) {
		int n = 0;
		double carry = 0;
		for (int i = 0; i + 1 < pts.length; i++) {
			double length = pts[i].distanceTo(pts[i + 1]);
			if (length < 1.0E-5) {
				continue;
			}
			double s = carry;
			while (s < length) {
				double f = s / length;
				double w = Math.max(0.02, width[i] + (width[i + 1] - width[i]) * f);
				s += Math.max(MIN_STEP, w * step);
				n++;
			}
			carry = s - length;
		}
		return n;
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
