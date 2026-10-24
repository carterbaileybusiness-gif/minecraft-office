package local.carter.office.client.magic.fx.water;

import java.util.function.Function;

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

import org.joml.Quaternionf;

/**
 * A sphere of water drawn as light: its silhouette (the ring a sphere shows from wherever you stand) as a soft glowing rim with
 * a crisp edge, tinted round its edge, a faint fill, and a glint high on one side, so it reads as round from every side. The
 * rim wobbles like a water surface and ripples when struck ({@link #ripple}). Seen from inside (the caster in their own bubble)
 * only a faint ring where it meets the ground and the glint show. It grows in, and at its end swells and fades ({@link #pop}).
 * Used by Bubble Shield (following the caster, iridescent like a soap film) and Water Prison (fixed, deep blue).
 */
final class WaterSphere extends SingleQuadParticle implements LightPipeline.Extent {
	enum Style {
		/** A soap-film bubble: thin, bright, its rim running through the colours of a film. */
		BUBBLE,
		/** A ball of water: a fuller blue body, a slower, heavier wobble. */
		PRISON
	}

	private static final int RIM_POINTS = 120;
	/** Points of the prison's round water surface. */
	private static final int SHELL = 110;
	/** Soap-film hues the bubble's rim runs through. */
	private static final int[] FILM = {0xFF9BE8, 0xFFE59B, 0x9BFFF0, 0xA8B8FF};

	private final Function<Float, Vec3> centre;
	private final float radius;
	private final Style style;
	private final int grow;
	private final WaterPen pen = new WaterPen();
	private final Vec3[] rim = new Vec3[RIM_POINTS + 1];
	private final float[] rimW = new float[RIM_POINTS + 1];
	private final float[] rimA = new float[RIM_POINTS + 1];
	private final Vec3[] band = new Vec3[RIM_POINTS + 1];
	private final Vec3[] arc = new Vec3[9];
	private final float[] arcW = new float[9];
	private final float[] arcA = new float[9];
	private final Quaternionf facing = new Quaternionf();
	private float ripple;
	private int popAt = -1;
	private float dim = 1;

	/** {@code centre} gives the sphere's middle at a partial tick; it lives {@code life} ticks plus its pop. */
	WaterSphere(ClientLevel level, Function<Float, Vec3> centre, float radius, int life, Style style) {
		super(level, centre.apply(0F).x, centre.apply(0F).y, centre.apply(0F).z, MagicCircleRenderer.sprite("glow"));
		this.centre = centre;
		this.radius = radius;
		this.style = style;
		this.grow = style == Style.BUBBLE ? 6 : 9;
		this.lifetime = life + POP_TICKS;
		this.hasPhysics = false;
		this.gravity = 0;
		this.xd = 0;
		this.yd = 0;
		this.zd = 0;
	}

	static final int POP_TICKS = 5;

	/** The surface was struck: the rim shivers. */
	void ripple(float amount) {
		ripple = Math.min(1.2F, ripple + amount);
	}

	/** Ends it now: it swells and fades over {@link #POP_TICKS}. */
	void pop() {
		if (popAt < 0) {
			popAt = age;
			lifetime = Math.min(lifetime, age + POP_TICKS);
		}
	}

	/** The budget refused its next step: it fades to a ghost of itself. */
	void dim() {
		dim = 0.25F;
	}

	@Override
	public void tick() {
		age++;
		ripple *= 0.86F;
		Vec3 c = centre.apply(1F);
		x = c.x;
		y = c.y;
		z = c.z;
		if (popAt < 0 && age >= lifetime - POP_TICKS) popAt = lifetime - POP_TICKS;
		if (age >= lifetime) remove();
	}

	/** The sphere's radius at time {@code t}: grown in (with a little overshoot), swollen in its pop. */
	private float size(float t) {
		float g = Mth.clamp(t / grow, 0F, 1F);
		float back = 1 + 2.2F * (float) Math.pow(g - 1, 3) + 1.2F * (float) Math.pow(g - 1, 2); // ease-out-back
		float s = radius * (0.25F + 0.75F * back);
		if (popAt >= 0) s *= 1 + 0.18F * Mth.clamp((t - popAt) / POP_TICKS, 0F, 1F);
		return s;
	}

	/** Brightness at time {@code t}: fades in, and out in its pop. */
	private float fade(float t) {
		float in = Mth.clamp(t / 3F, 0F, 1F);
		float out = popAt < 0 ? 1F : 1F - Mth.clamp((t - popAt) / POP_TICKS, 0F, 1F);
		return in * out * dim;
	}

	@Override
	public void extract(QuadParticleRenderState state, Camera camera, float partial) {
		float t = age + partial;
		facing.set(camera.rotation());
		pen.begin(state, camera.position(), facing, 0);
		draw(centre.apply(partial), camera.position(), t);
	}

	/** Fx-budget particles one {@link WaterPen#STEP_TICKS}-tick step of drawing costs (counted from a typical view). */
	int cost() {
		pen.dry();
		draw(new Vec3(0, 0, 0), new Vec3(0, 1, 8), grow + 1F);
		return WaterPen.cost(pen.quads);
	}

	private void draw(Vec3 c, Vec3 cam, float t) {
		float a = fade(t);
		if (a <= 0.01F) return;
		float r = size(t);
		Vec3 to = c.subtract(cam);
		double d = to.length();
		boolean bubble = style == Style.BUBBLE;
		int body = bubble ? WaterFx.LIGHT : WaterFx.BODY;
		if (d <= r * 1.06) {
			inside(c, r, t, a);
			return;
		}
		// The silhouette: the circle where the view grazes the sphere.
		double k = (d * d - r * r) / (d * d);
		Vec3 cc = cam.add(to.scale(k));
		double rho = r * Math.sqrt(d * d - r * r) / d;
		Vec3 n = to.scale(1 / d);
		Vec3[] p = WaterFx.plane(n);
		float wobble = bubble ? 0.015F : 0.03F;
		float speed = bubble ? 0.22F : 0.12F;
		for (int i = 0; i <= RIM_POINTS; i++) {
			double th = Mth.TWO_PI * i / RIM_POINTS;
			double w = 1 + wobble * Math.sin(2 * th + t * speed) + 0.5 * wobble * Math.sin(3 * th - t * speed * 1.7)
					+ 0.012 * ripple * Math.sin(16 * th + t * 2.1);
			rim[i] = cc.add(p[0].scale(Math.cos(th) * rho * w)).add(p[1].scale(Math.sin(th) * rho * w));
			rimW[i] = (bubble ? 0.2F : 0.32F) * (1 + 0.4F * ripple);
			rimA[i] = a;
		}
		// The fill: a film of see-through water, and faint light in it.
		if (!bubble) pen.water(true).blob(c, r * 2.1F, WaterFx.BODY, 0.16F * a);
		pen.water(false).blob(c, r * 2.3F, WaterFx.BODY, (bubble ? 0.05F : 0.1F) * a);
		if (!bubble) {
			pen.blob(c, r * 1.4F, WaterFx.DEEP, 0.1F * a);
			shell(c, r, t, a);
		}
		// The rim: soft glow round the whole edge, in bands of colour.
		int bands = bubble ? FILM.length * 2 : 1;
		if (bubble) {
			for (int b = 0; b < bands; b++) {
				int from = b * RIM_POINTS / bands;
				int to2 = (b + 1) * RIM_POINTS / bands;
				int hue = WaterPen.mix(body, FILM[(b + (int) (t / 24)) % FILM.length], 0.45F);
				pen.tube(slice(rim, from, to2), slice(rimW, from, to2), slice(rimA, from, to2), to2 - from + 1, 1F, 0.18F, 0.04F, hue, 0.13F);
			}
		} else {
			pen.tube(rim, rimW, rimA, RIM_POINTS + 1, 1F, 0.18F, 0.04F, body, 0.15F);
		}
		if (bubble) {
			// The film is thickest seen edge-on: a soft band of light just inside the rim, fading inward.
			for (int i = 0; i <= RIM_POINTS; i++) band[i] = cc.add(rim[i].subtract(cc).scale(0.88));
			for (int i = 0; i <= RIM_POINTS; i++) rimW[i] = (float) (rho * 0.3);
			pen.tube(band, rimW, rimA, RIM_POINTS + 1, 1F, 0.1F, 0.05F, WaterFx.LIGHT, 0.022F);
		}
		for (int i = 0; i <= RIM_POINTS; i++) rimW[i] = bubble ? 0.05F : 0.07F;
		pen.line(rim, rimW, rimA, RIM_POINTS + 1, WaterFx.FOAM, bubble ? 0.5F : 0.42F);
		glint(cc, p, rho, a);
	}

	/**
	 * The prison's round surface: soft light laid over a sphere of points (a Fibonacci spiral, turning slowly), overlapping so
	 * thickly it reads as one glowing skin of water, round over the block-shaped water inside it. Added light, not the
	 * translucent layer: that one drops faint pixels, which would leave the skin dotted.
	 */
	private void shell(Vec3 c, float r, float t, float a) {
		double spin = t * 0.02;
		for (int i = 0; i < SHELL; i++) {
			double y = 1 - 2 * (i + 0.5) / SHELL;
			double s = Math.sqrt(1 - y * y);
			double th = i * 2.399963229728653 + spin;
			double rr = r * (0.97 + 0.03 * Math.sin(t * 0.15 + i));
			pen.blob(c.x + Math.cos(th) * s * rr, c.y + y * rr, c.z + Math.sin(th) * s * rr, 1.8F, SHELL_WATER, 0.055F * a);
		}
	}

	/** The prison surface's water (RGB). */
	private static final int SHELL_WATER = 0x3A86E8;

	/** A bright arc high on one side, and a spot of light: the sheen that makes it read as round. */
	private void glint(Vec3 cc, Vec3[] p, double rho, float a) {
		for (int i = 0; i < arc.length; i++) {
			double th = Math.toRadians(105 + 45.0 * i / (arc.length - 1));
			arc[i] = cc.add(p[0].scale(Math.cos(th) * rho * 0.8)).add(p[1].scale(Math.sin(th) * rho * 0.8));
			float s = i / (float) (arc.length - 1);
			arcW[i] = 0.09F;
			arcA[i] = a * (float) Math.sin(Math.PI * s);
		}
		pen.line(arc, arcW, arcA, arc.length, WaterFx.FOAM, 0.6F);
		double th = Math.toRadians(140);
		pen.blob(cc.add(p[0].scale(Math.cos(th) * rho * 0.6)).add(p[1].scale(Math.sin(th) * rho * 0.6)), 0.3F, 0xFFFFFF, 0.55F * a);
	}

	/** Seen from inside: a faint ring where the bubble meets the ground, and a few glints overhead. */
	private void inside(Vec3 c, float r, float t, float a) {
		double drop = Math.min(r * 0.9, 0.9);
		double ring = Math.sqrt(Math.max(0, r * r - drop * drop));
		for (int i = 0; i <= RIM_POINTS; i++) {
			double th = Mth.TWO_PI * i / RIM_POINTS;
			double w = 1 + 0.015 * Math.sin(2 * th + t * 0.2) + 0.012 * ripple * Math.sin(16 * th + t * 2.1);
			rim[i] = c.add(Math.cos(th) * ring * w, -drop + 0.05, Math.sin(th) * ring * w);
			rimW[i] = 0.07F;
			rimA[i] = a;
		}
		pen.line(rim, rimW, rimA, RIM_POINTS + 1, WaterFx.LIGHT, 0.35F + 0.3F * ripple);
		for (int i = 0; i < 3; i++) {
			double th = t * 0.03 + i * 2.1;
			pen.blob(c.add(Math.cos(th) * r * 0.55, r * 0.8, Math.sin(th) * r * 0.55), 0.18F, WaterFx.FOAM, 0.3F * a);
		}
	}

	private static Vec3[] slice(Vec3[] a, int from, int to) {
		Vec3[] out = new Vec3[to - from + 1];
		System.arraycopy(a, from, out, 0, out.length);
		return out;
	}

	private static float[] slice(float[] a, int from, int to) {
		float[] out = new float[to - from + 1];
		System.arraycopy(a, from, out, 0, out.length);
		return out;
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
		return radius * 1.4 + 0.5;
	}
}
