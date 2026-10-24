package local.carter.office.client.magic.fx.shadow;

import org.joml.Quaternionf;
import org.joml.Vector3fc;

import local.carter.office.client.magic.fx.LightPipeline;
import local.carter.office.client.magic.fx.MagicCircleRenderer;
import local.carter.office.client.magic.fx.ShaderCompat;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Lays the camera-facing pieces every shadow visual is built from: darkness ({@code LightPipeline.dark()}, taking the light
 * behind it away) and violet light ({@code LightPipeline.glow()}), plus the shapes made of them: a dark orb with a lit rim
 * (an event horizon) and rings of pieces. It counts the pieces it lays, so a visual can charge its fx budget per frame
 * ({@link #budgetCost}). Render thread only; one instance per particle (it keeps the frame's camera).
 *
 * <p>Darkness is premultiplied (the amount taken is in the colour, alpha stays full), like the kit's light: the particle shader
 * drops fragments under a tenth of alpha, which would cut a faint shadow off at a hard edge. Taking light away from an already
 * dark scene saturates to black quickly, so wide, faint darkness ({@link #haze}) is scaled by {@link #daylight}.
 */
final class Umbra {
	/** Quads one budget particle pays for. */
	static final int QUADS_PER_PARTICLE = 50;

	private final TextureAtlasSprite glow = MagicCircleRenderer.sprite("glow");
	private final TextureAtlasSprite soft = MagicCircleRenderer.sprite("soft");
	private final TextureAtlasSprite spark = MagicCircleRenderer.sprite("spark");

	private QuadParticleRenderState state;
	private Vec3 cam = Vec3.ZERO;
	private Quaternionf q = new Quaternionf();
	private Vector3fc left;
	private Vector3fc up;
	private int laid;

	/** Starts a frame: pieces face {@code camera}. */
	void begin(QuadParticleRenderState state, Camera camera) {
		this.state = state;
		this.cam = camera.position();
		this.q = camera.rotation();
		this.left = camera.leftVector();
		this.up = camera.upVector();
		this.laid = 0;
	}

	/** Pieces laid since {@link #begin}. */
	int laid() {
		return laid;
	}

	/** The camera's position this frame. */
	Vec3 camera() {
		return cam;
	}

	/** The camera's left this frame (the screen's x axis, pointing left). */
	Vec3 left() {
		return new Vec3(left.x(), left.y(), left.z());
	}

	/** The camera's up this frame (the screen's y axis). */
	Vec3 up() {
		return new Vec3(up.x(), up.y(), up.z());
	}

	/** How much daylight {@code at} stands in, 0..1: its sky light times how bright the sky is now (0 at night or indoors). */
	static float daylight(Vec3 at) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return 0F;
		int sky = LightCoordsUtil.sky(LightCoordsUtil.getLightCoords(level, BlockPos.containing(at)));
		return Mth.clamp(sky / 15F * (1F - level.getSkyDarken() / 11F), 0F, 1F);
	}

	/** A round piece of darkness at {@code at}, {@code radius} to its edge, taking {@code intensity} (0..1) of the light. */
	void dark(Vec3 at, float radius, float intensity) {
		if (intensity <= 0.004F || radius <= 0) return;
		add(LightPipeline.dark(), at, radius, glow, ink(intensity));
	}

	/** Most a {@link #haze} reaches, in blocks: wider ones cut into the ground and show the atlas's pixel steps up close. */
	static final float MAX_HAZE = 1.6F;

	/**
	 * A wide, faint piece of darkness round something (at most {@link #MAX_HAZE} to its edge). Drawn with the small {@code glow}
	 * sprite, whose falloff is concentrated in its middle, so its edge never shows the pixel steps a big soft sprite does.
	 */
	void haze(Vec3 at, float radius, float intensity) {
		if (intensity <= 0.004F || radius <= 0) return;
		// Three nested pieces sharing the strength: the 32-px sprite's texel steps fall in different places on each, so where the
		// haze meets a lit wall up close no single step shows at full contrast (Task 22's parked polish).
		float r = Math.min(radius, MAX_HAZE);
		add(LightPipeline.dark(), at, r, glow, ink(intensity * 0.45F));
		add(LightPipeline.dark(), at, r * 0.78F, glow, ink(intensity * 0.33F));
		add(LightPipeline.dark(), at, r * 0.57F, glow, ink(intensity * 0.27F));
	}

	/** Pieces one {@link #haze} lays. */
	static final int HAZE_PIECES = 3;

	/** A round piece of light in {@code rgb}. */
	void light(Vec3 at, float radius, float intensity, int rgb) {
		if (intensity <= 0.004F || radius <= 0) return;
		add(LightPipeline.glow(), at, radius, glow, LightPipeline.light(Math.min(1F, intensity), rgb));
	}

	/** A wide, soft piece of light in {@code rgb}. */
	void aura(Vec3 at, float radius, float intensity, int rgb) {
		if (intensity <= 0.004F || radius <= 0) return;
		add(LightPipeline.glow(), at, radius, soft, LightPipeline.light(Math.min(1F, intensity), rgb));
	}

	/** A four-point sparkle of light in {@code rgb}. */
	void sparkle(Vec3 at, float radius, float intensity, int rgb) {
		if (intensity <= 0.004F || radius <= 0) return;
		add(LightPipeline.glow(), at, radius, spark, LightPipeline.light(Math.min(1F, intensity), rgb));
	}

	/** The point {@code radius} from {@code centre} at angle {@code a} in the plane facing the camera. */
	Vec3 onScreenRing(Vec3 centre, double radius, double a) {
		double c = Math.cos(a) * radius;
		double s = Math.sin(a) * radius;
		return centre.add(left.x() * c + up.x() * s, left.y() * c + up.y() * s, left.z() * c + up.z() * s);
	}

	/**
	 * A dark orb: a black core about {@code radius} round (soft at its edge), its rim lit by {@code n} overlapping pieces of violet
	 * light that seem to turn (one side burns brighter, by {@code spin} radians), a faint violet corona outside and a pale inner
	 * edge. It reads as a hole in the world by day and as a ring of light by night. {@code a} (0..1) fades all of it;
	 * {@code haze} is how much the wide shadow round it takes (0 for none).
	 */
	void orb(Vec3 centre, float radius, float a, double spin, int n, float haze) {
		if (a <= 0.004F || radius <= 0.01F) return;
		if (haze > 0) haze(centre, radius * 2.6F, haze * a);
		// Stacked so the black is solid inside the rim and falls off just outside it (a wide piece at full strength would
		// saturate a lit wall to black far out, showing the sprite's texel steps).
		dark(centre, radius * 1.7F, 0.7F * a);
		dark(centre, radius * 1.3F, a);
		dark(centre, radius * 1.05F, a);
		for (int i = 0; i < n; i++) {
			double ang = spin + Mth.TWO_PI * i / n;
			// One side of the rim burns brighter: it seems to turn.
			float bright = 0.3F + 0.7F * (float) Math.pow(0.5 + 0.5 * Math.cos(ang - spin * 1.7), 2);
			Vec3 p = onScreenRing(centre, radius * 1.02, ang);
			light(p, radius * 0.45F, 0.6F * a * bright, i % 3 == 0 ? ShadowFx.MAGENTA : ShadowFx.VIOLET);
		}
		for (int i = 0; i < n; i += 2) {
			double ang = -spin * 0.6 + Mth.TWO_PI * i / n;
			aura(onScreenRing(centre, radius * 1.4, ang), radius * 0.8F, 0.28F * a, ShadowFx.VIOLET);
			light(onScreenRing(centre, radius * 0.93, ang + 0.3), radius * 0.15F, 0.45F * a, ShadowFx.PALE);
		}
	}

	/** Pieces {@link #orb} lays with {@code n} rim pieces. */
	static int orbPieces(int n) {
		return 3 + HAZE_PIECES + n + 2 * ((n + 1) / 2);
	}

	/** Fx-budget particles a visual laying {@code quads} pieces a frame costs: one per {@value #QUADS_PER_PARTICLE}, at least one. */
	static int budgetCost(int quads) {
		return Math.max(1, (quads + QUADS_PER_PARTICLE - 1) / QUADS_PER_PARTICLE);
	}

	/**
	 * Darkness's colour: on our pipeline what to take away, premultiplied by {@code intensity} with alpha full (no alpha cut-off
	 * edge); under a shader pack, {@code LightPipeline.darkness} (the shade it would leave, laid over the world).
	 */
	private static int ink(float intensity) {
		float k = Mth.clamp(intensity, 0F, 1F);
		if (ShaderCompat.current() == ShaderCompat.Path.PLAIN) return LightPipeline.darkness(k, ShadowFx.TINT);
		int take = LightPipeline.darkColor(ShadowFx.TINT);
		int r = Math.round(((take >> 16) & 0xFF) * k);
		int g = Math.round(((take >> 8) & 0xFF) * k);
		int b = Math.round((take & 0xFF) * k);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	private void add(SingleQuadParticle.Layer layer, Vec3 at, float radius, TextureAtlasSprite sprite, int colour) {
		state.add(layer, (float) (at.x - cam.x), (float) (at.y - cam.y), (float) (at.z - cam.z), q.x, q.y, q.z, q.w, radius, sprite.getU0(),
				sprite.getU1(), sprite.getV0(), sprite.getV1(), colour, LightCoordsUtil.FULL_BRIGHT);
		laid++;
	}
}
