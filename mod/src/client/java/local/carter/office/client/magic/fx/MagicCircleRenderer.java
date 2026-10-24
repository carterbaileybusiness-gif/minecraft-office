// Adapted from Wildercord (MIT) https://github.com/defnotean/wildercord @9f5a21fe7964097492cd92ac64e4c4e05ef5236a
package local.carter.office.client.magic.fx;

import java.util.HashMap;
import java.util.Map;

import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import local.carter.office.OfficeMod;
import local.carter.office.magic.core.Element;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.ResourceReloaderKeys;

/**
 * A summoning circle, built every frame from thin pieces of light (Wildercord's SpellCircleParticle): every line is short
 * straight pieces of a soft line texture laid end to end, so it keeps its width and stays crisp at any radius; each piece is
 * drawn twice, a glow in the circle's colour and a white-hot core over it, in the additive {@link LightPipeline#GLOW} layer.
 *
 * <p>A circle has up to five rings, inner (0) to outer (4), at {@link #ringRadius}: 0 the seal with the element's glyph, 1 a band
 * of runes, 2 a hexagram with lit nodes, 3 a second band of runes, 4 the frame with rays. Each ring draws itself in as it
 * appears (an arc sweeping round while it fades in over {@link #RING_FADE_TICKS}), and neighbouring rings turn opposite ways.
 *
 * <p>The geometry helpers ({@link #ringRadius}, {@link #ringAlpha}, {@link #basis}) are pure, for the unit tests. Drawing is render
 * thread only.
 */
public final class MagicCircleRenderer {
	/** Ring 0's radius and the step between rings, in blocks (at scale 1). */
	public static final float BASE_RADIUS = 0.55F;
	public static final float RING_STEP = 0.15F;
	public static final int MAX_RINGS = 5;
	/** A ring fades in over this many ticks once it appears. */
	public static final float RING_FADE_TICKS = 3F;

	/** Radians a tick each ring turns (alternating directions). */
	private static final float[] SPIN = {0.012F, -0.035F, 0.022F, -0.018F, 0.008F};
	/** Line widths in blocks at scale 1: the frame and seal, and everything else. */
	private static final float HEAVY = 0.055F;
	private static final float FINE = 0.038F;
	/** The line texture's visible band is this share of its square (line.png: 24 of 32 px). */
	private static final float BAND = 0.75F;
	private static final int RUNES = 8;

	/** Radius of ring {@code ring} (0 = innermost) at scale 1: {@code 0.55 + 0.15 × ring}. */
	public static float ringRadius(int ring) {
		return BASE_RADIUS + RING_STEP * ring;
	}

	/** A ring's brightness {@code ringAge} ticks after it appeared: 0 → 1 over {@link #RING_FADE_TICKS}, 0 before. */
	public static float ringAlpha(float ringAge) {
		return Mth.clamp(ringAge / RING_FADE_TICKS, 0F, 1F);
	}

	/** A circle's frame: {@code right} and {@code up} span its plane, {@code normal} is the way it faces. All unit, all orthogonal. */
	public record Basis(Vector3f right, Vector3f up, Vector3f normal) {}

	/**
	 * The plane facing {@code normal}: {@code up} leans toward world up, so a vertical circle stands upright; a circle facing
	 * (nearly) straight up or down takes north as its up instead. Never NaN, even for a zero normal (treated as straight up).
	 */
	public static Basis basis(double nx, double ny, double nz) {
		Vector3f n = new Vector3f((float) nx, (float) ny, (float) nz);
		if (!(n.lengthSquared() > 1.0E-12F) || !Float.isFinite(n.lengthSquared())) {
			n.set(0, 1, 0);
		}
		n.normalize();
		Vector3f right = new Vector3f(0, 1, 0).cross(n);
		if (right.lengthSquared() < 1.0E-6F) {
			right.set(0, 0, -1).cross(n); // facing up or down: up = north
		}
		right.normalize();
		Vector3f up = new Vector3f(n).cross(right).normalize();
		return new Basis(right, up, n);
	}

	public static Basis basis(Vec3 normal) {
		return basis(normal.x, normal.y, normal.z);
	}

	/** The rotation taking a quad's x, y, z to the basis' right, up, normal. */
	public static Quaternionf orientation(Basis b, Quaternionf out) {
		return out.setFromNormalized(new Matrix3f(b.right(), b.up(), b.normal()));
	}

	/** The glyph at a circle's heart: the alchemical sign of its element, or Recall's compass star. */
	public enum Glyph {
		FIRE, WATER, WIND, LIGHTNING, EARTH, SHADOW, STAR;

		public static Glyph of(Element e) {
			return values()[e.ordinal()];
		}

		/** The glyph for a circle of this colour: an element's own colour gives its glyph, anything else the star. */
		public static Glyph ofColour(int argb) {
			for (Element e : Element.values()) {
				if ((e.argb() & 0xFFFFFF) == (argb & 0xFFFFFF)) return of(e);
			}
			return STAR;
		}
	}

	/** One circle as it should look this frame, filled in by its particle. */
	public static final class Frame {
		public double x;
		public double y;
		public double z;
		/** The circle's plane (quad x/y in the plane, z its normal). */
		public final Quaternionf plane = new Quaternionf();
		public int rgb = 0xFFFFFF;
		public Glyph glyph = Glyph.STAR;
		/** Rings shown, 0..5. */
		public int rings;
		/** Ticks since each ring appeared (below 0: not yet). */
		public final float[] ringAge = new float[MAX_RINGS];
		/** Radius multiplier (ring {@code i} sits at {@code ringRadius(i) × scale}). */
		public float scale = 1F;
		public float alpha = 1F;
		/** Ticks, for the turning. */
		public float time;
		/** 0..1: a full charge's pulse (brighter, a little bigger, rays out). */
		public float flare;
		/**
		 * Blocks around the centre kept empty (0 = none): no backdrop, glyph or centre glow, and no line inside it. The caster's own
		 * first-person charge circle sets it so the crosshair and what it aims at stay clear.
		 */
		public float clear;
		/** 0..1: a pulse of the outer frame only (the first-person full-charge cue). */
		public float rimPulse;
	}

	// While drawing a frame.
	private QuadParticleRenderState state;
	private final Quaternionf plane = new Quaternionf();
	private final Vector3f axisU = new Vector3f();
	private final Vector3f axisV = new Vector3f();
	private float lod = 1F;
	private boolean cores = true;
	/** The white core's brightness: full at night and indoors, dimmed in daylight so the element colour survives a bright sky. */
	private float coreLight = 1F;
	private float clear;
	private float cx;
	private float cy;
	private float cz;
	private TextureAtlasSprite line;
	private TextureAtlasSprite core;
	private TextureAtlasSprite glow;
	private TextureAtlasSprite soft;
	private final TextureAtlasSprite[] runes = new TextureAtlasSprite[RUNES];
	private final TextureAtlasSprite[] runesBack = new TextureAtlasSprite[RUNES];
	private final SingleQuadParticle.Layer layer = LightPipeline.GLOW;

	/** Looked-up fx sprites; emptied after every resource reload ({@link #registerReload}). Render thread only. */
	private static final Map<String, TextureAtlasSprite> SPRITES = new HashMap<>();
	/** Bumped by a reload, so a renderer re-reads its sprites. */
	private static int generation;
	private int seenGeneration = -1;

	/** One of the fx sprites ({@code office:fx/<name>} in the particle atlas), cached until the next resource reload. */
	public static TextureAtlasSprite sprite(String name) {
		TextureAtlasSprite s = SPRITES.get(name);
		if (s == null) {
			TextureAtlas atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.PARTICLES);
			s = atlas.getSprite(OfficeMod.id("fx/" + name));
			SPRITES.put(name, s);
		}
		return s;
	}

	/** Drops the cached sprites after every client resource reload, ordered after the atlases are stitched (client init). */
	public static void registerReload() {
		Identifier id = OfficeMod.id("magic_fx_sprites");
		ResourceLoader loader = ResourceLoader.get(PackType.CLIENT_RESOURCES);
		loader.registerReloadListener(id, (ResourceManagerReloadListener) manager -> {
			SPRITES.clear();
			generation++;
		});
		loader.addListenerOrdering(ResourceReloaderKeys.Client.ATLAS, id);
	}

	private void sprites() {
		if (seenGeneration == generation && line != null) {
			return;
		}
		seenGeneration = generation;
		line = sprite("line");
		core = sprite("core");
		glow = sprite("glow");
		soft = sprite("soft");
		for (int i = 0; i < RUNES; i++) {
			runes[i] = sprite("rune_" + i);
			runesBack[RUNES - 1 - i] = runes[i];
		}
	}

	/** Lays one circle into {@code state}, camera-relative to {@code cam}. */
	public void draw(QuadParticleRenderState state, Vec3 cam, Frame f) {
		if (f.alpha <= 0.01F || f.rings <= 0) {
			return;
		}
		this.state = state;
		sprites();
		plane.set(f.plane);
		plane.transform(axisU.set(1, 0, 0));
		plane.transform(axisV.set(0, 1, 0));
		cx = (float) (f.x - cam.x);
		cy = (float) (f.y - cam.y);
		cz = (float) (f.z - cam.z);
		// Far away, fewer and wider pieces and no white core: the circle is a few pixels across there.
		float distance = Mth.sqrt(cx * cx + cy * cy + cz * cz);
		lod = Mth.clamp(distance / 6F, 1F, 4F);
		cores = distance < 16F;
		coreLight = 1F - 0.75F * daylight(f);
		clear = f.clear;
		float s = f.scale * (1F + 0.04F * f.flare);
		float a0 = f.alpha * (1F + 0.35F * f.flare);
		int rgb = f.rgb & 0xFFFFFF;
		int hot = lighter(rgb, 0.45F);
		int rings = Math.min(f.rings, MAX_RINGS);

		// The light behind it all, growing with the rings.
		float open = 0;
		for (int i = 0; i < rings; i++) {
			open += ringAlpha(f.ringAge[i]);
		}
		float reach = ringRadius(rings) * s;
		if (clear <= 0) piece(soft, 0, 0, 0, reach * 1.1F, light(f.alpha * (0.16F + 0.06F * open / MAX_RINGS + 0.12F * f.flare), rgb));

		for (int i = 0; i < rings; i++) {
			float shown = ringAlpha(f.ringAge[i]);
			if (shown <= 0) {
				continue;
			}
			float grow = 1 - (1 - shown) * (1 - shown);
			float a = a0 * shown;
			float r = ringRadius(i) * s;
			float spin = f.time * SPIN[i];
			// The ring draws itself in: an arc sweeping round from the top as it fades in.
			float from = Mth.HALF_PI + spin;
			float to = from + Mth.TWO_PI * grow;
			switch (i) {
				case 0 -> {
					stroke(0, 0, r, HEAVY * s, from, to, rgb, a);
					stroke(0, 0, r * 0.84F, FINE * s * 0.8F, from, to, rgb, a * 0.8F);
					if (clear <= 0) {
						glyph(f.glyph, r * 0.66F * (0.8F + 0.2F * grow), FINE * s, rgb, a * grow);
						piece(glow, 0, 0, 0, r * 0.35F, light(a * 0.45F, hot));
					}
				}
				case 1, 3 -> {
					float band = (i == 1 ? 0.045F : 0.05F) * s;
					stroke(0, 0, r + band, FINE * s, from, to, rgb, a);
					stroke(0, 0, r - band, FINE * s, -from, -from - Mth.TWO_PI * grow, rgb, a * 0.85F);
					tiles(i == 1 ? runes : runesBack, r, band * 1.5F, spin, grow, rgb, a);
				}
				case 2 -> {
					stroke(0, 0, r, FINE * s, from, to, rgb, a * 0.9F);
					// A hexagram: two triangles, drawing from their points as the ring appears.
					for (int t = 0; t < 2; t++) {
						polygon(3, r * 0.985F, spin + t * Mth.PI, FINE * s * 0.9F, grow, rgb, a * 0.9F);
					}
					for (int k = 0; k < 6; k++) {
						float ang = spin + Mth.HALF_PI + Mth.TWO_PI * k / 6;
						piece(glow, Mth.cos(ang) * r, Mth.sin(ang) * r, 0, 0.07F * s, light(a * (0.7F + 0.3F * f.flare), hot));
					}
				}
				default -> {
					a *= 1F + 0.6F * f.rimPulse;
					stroke(0, 0, r, HEAVY * s, from, to, rgb, a);
					stroke(0, 0, r - 0.045F * s, FINE * s * 0.8F, from, to, rgb, a * 0.7F);
					// Ticks between the frame's two lines, and rays out past it.
					for (int k = 0; k < 36; k++) {
						float ang = spin + Mth.TWO_PI * k / 36;
						if (ang - from > Mth.TWO_PI * grow) continue;
						float in = r - (k % 3 == 0 ? 0.045F : 0.03F) * s;
						segment(Mth.cos(ang) * in, Mth.sin(ang) * in, Mth.cos(ang) * r, Mth.sin(ang) * r, FINE * s * 0.6F, rgb, a * 0.7F);
					}
					float out = (0.6F + 0.4F * grow) * (1F + 0.5F * f.flare);
					for (int k = 0; k < 8; k++) {
						float ang = -spin * 2 + Mth.HALF_PI + Mth.TWO_PI * k / 8;
						float len = (k % 2 == 0 ? RING_STEP : RING_STEP * 0.55F) * s * out;
						segment(Mth.cos(ang) * r, Mth.sin(ang) * r, Mth.cos(ang) * (r + len), Mth.sin(ang) * (r + len), FINE * s * 0.8F, rgb, a * 0.85F);
					}
				}
			}
		}
		this.state = null;
	}

	/** The glyph at the heart, {@code g} from its middle to its points. */
	private void glyph(Glyph glyph, float g, float w, int rgb, float a) {
		if (a <= 0.01F) {
			return;
		}
		switch (glyph) {
			case FIRE -> {
				polygon(3, g, 0, w, 1, rgb, a);
				polygon(3, g * 0.42F, 0, w * 0.8F, 1, rgb, a * 0.8F);
			}
			case WATER -> {
				polygon(3, g, Mth.PI, w, 1, rgb, a);
				stroke(0, 0, g * 0.26F, w * 0.8F, 0, Mth.TWO_PI, rgb, a * 0.8F);
			}
			case WIND -> {
				polygon(3, g, 0, w, 1, rgb, a);
				segment(-g * 0.62F, g * 0.08F, g * 0.62F, g * 0.08F, w, rgb, a);
			}
			case EARTH -> {
				polygon(3, g, Mth.PI, w, 1, rgb, a);
				segment(-g * 0.62F, -g * 0.08F, g * 0.62F, -g * 0.08F, w, rgb, a);
			}
			case LIGHTNING -> {
				segment(-g * 0.12F, g * 0.92F, g * 0.3F, g * 0.08F, w, rgb, a);
				segment(g * 0.3F, g * 0.08F, -g * 0.3F, -g * 0.02F, w, rgb, a);
				segment(-g * 0.3F, -g * 0.02F, g * 0.12F, -g * 0.92F, w, rgb, a);
			}
			case SHADOW -> {
				stroke(0, 0, g * 0.78F, w, Mth.PI * 0.25F, Mth.PI * 1.75F, rgb, a);
				stroke(g * 0.33F, 0, g * 0.59F, w, Mth.PI * 0.38F, Mth.PI * 1.62F, rgb, a);
			}
			default -> {
				polygon(4, g, 0, w, 1, rgb, a);
				polygon(4, g, Mth.PI / 4, w, 1, rgb, a);
				stroke(0, 0, g * 0.3F, w * 0.8F, 0, Mth.TWO_PI, rgb, a * 0.8F);
			}
		}
	}

	/** A regular polygon with corners on radius {@code rad}, one corner at the top turned by {@code turn}; each side drawn {@code drawn} of the way. */
	private void polygon(int sides, float rad, float turn, float width, float drawn, int rgb, float a) {
		for (int k = 0; k < sides; k++) {
			float a0 = turn + Mth.HALF_PI + Mth.TWO_PI * k / sides;
			float a1 = turn + Mth.HALF_PI + Mth.TWO_PI * (k + 1) / sides;
			float x0 = Mth.cos(a0) * rad;
			float y0 = Mth.sin(a0) * rad;
			segment(x0, y0, x0 + (Mth.cos(a1) * rad - x0) * drawn, y0 + (Mth.sin(a1) * rad - y0) * drawn, width, rgb, a);
		}
	}

	/** An arc around ({@code u}, {@code v}) from angle {@code from} to {@code to}: glow and core. */
	private void stroke(float u, float v, float rad, float width, float from, float to, int rgb, float a) {
		if (a <= 0.01F || rad <= 0 || from == to || clear > 0 && Math.hypot(u, v) + rad - width < clear) {
			return;
		}
		float length = Math.abs(to - from) * rad;
		int n = Math.max(6, Mth.ceil(length / (width * lod / BAND)));
		float half = length / n / 2 * 1.04F;
		int glowInk = light(a, rgb);
		int coreInk = light(a * 0.9F * coreLight, lighter(rgb, 0.8F));
		float step = (to - from) / n;
		for (int i = 0; i < n; i++) {
			float ang = from + step * (i + 0.5F);
			float pu = u + Mth.cos(ang) * rad;
			float pv = v + Mth.sin(ang) * rad;
			piece(line, pu, pv, ang + Mth.HALF_PI, half, glowInk);
			if (cores) piece(core, pu, pv, ang + Mth.HALF_PI, half, coreInk);
		}
	}

	/** A straight line from ({@code u0}, {@code v0}) to ({@code u1}, {@code v1}): glow and core. */
	private void segment(float u0, float v0, float u1, float v1, float width, int rgb, float a) {
		if (clear > 0) {
			// Only the parts outside the clear middle.
			float[] t = clip(u0, v0, u1, v1, clear + width * 0.5F); // the glow too
			if (t == null) {
				line(u0, v0, u1, v1, width, rgb, a);
			} else {
				if (t[0] > 0) line(u0, v0, u0 + (u1 - u0) * t[0], v0 + (v1 - v0) * t[0], width, rgb, a);
				if (t[1] < 1) line(u0 + (u1 - u0) * t[1], v0 + (v1 - v0) * t[1], u1, v1, width, rgb, a);
			}
			return;
		}
		line(u0, v0, u1, v1, width, rgb, a);
	}

	/**
	 * Where the segment from ({@code u0}, {@code v0}) to ({@code u1}, {@code v1}) is inside the circle of radius {@code r} about the
	 * centre, as {t0, t1} (clamped to 0..1), or null when it never enters it. Pure (the unit tests use it).
	 */
	public static float @org.jspecify.annotations.Nullable [] clip(float u0, float v0, float u1, float v1, float r) {
		float du = u1 - u0;
		float dv = v1 - v0;
		float a = du * du + dv * dv;
		if (a < 1.0E-12F) {
			return u0 * u0 + v0 * v0 < r * r ? new float[] {0F, 1F} : null;
		}
		float b = 2 * (u0 * du + v0 * dv);
		float c = u0 * u0 + v0 * v0 - r * r;
		float disc = b * b - 4 * a * c;
		if (disc <= 0) {
			return null;
		}
		float sq = Mth.sqrt(disc);
		float t0 = (-b - sq) / (2 * a);
		float t1 = (-b + sq) / (2 * a);
		if (t1 <= 0 || t0 >= 1) {
			return null;
		}
		return new float[] {Math.max(0F, t0), Math.min(1F, t1)};
	}

	/** A straight line piece by piece: glow and core. */
	private void line(float u0, float v0, float u1, float v1, float width, int rgb, float a) {
		float du = u1 - u0;
		float dv = v1 - v0;
		float length = Mth.sqrt(du * du + dv * dv);
		if (a <= 0.01F || length < 1.0E-4F) {
			return;
		}
		int n = Math.max(1, Mth.ceil(length / (width * lod / BAND)));
		float half = length / n / 2;
		float rot = (float) Mth.atan2(dv, du);
		int glowInk = light(a, rgb);
		int coreInk = light(a * 0.9F * coreLight, lighter(rgb, 0.8F));
		for (int i = 0; i < n; i++) {
			float t = (i + 0.5F) / n;
			piece(line, u0 + du * t, v0 + dv * t, rot, half, glowInk);
			if (cores) piece(core, u0 + du * t, v0 + dv * t, rot, half, coreInk);
		}
	}

	/** A band of runes round radius {@code rad}, {@code height} tall, tops outward, turned by {@code angle}; the first {@code shown} of them. */
	private void tiles(TextureAtlasSprite[] sprites, float rad, float height, float angle, float shown, int rgb, float a) {
		int n = Math.max(8, Math.round(Mth.TWO_PI * rad / (height * 1.25F)));
		n = Math.max(sprites.length, Math.round(n / (float) sprites.length) * sprites.length);
		int count = Math.round(n * shown);
		int ink = light(a * 0.95F, lighter(rgb, 0.3F));
		int white = light(a * 0.45F * coreLight, 0xFFFFFF);
		for (int i = 0; i < count; i++) {
			float ang = angle + Mth.HALF_PI + Mth.TWO_PI * i / n;
			float u = Mth.cos(ang) * rad;
			float v = Mth.sin(ang) * rad;
			TextureAtlasSprite rune = sprites[i % sprites.length];
			piece(rune, u, v, ang - Mth.HALF_PI, height / 2, ink);
			if (cores) piece(rune, u, v, ang - Mth.HALF_PI, height / 2 * 0.92F, white);
		}
	}

	/**
	 * One square piece of {@code sprite} at ({@code u}, {@code v}) in the circle's plane, turned {@code rot}, {@code half} across
	 * from its middle. The place is the plane's two axes scaled; the turn is the plane's rotation times a turn about its normal,
	 * multiplied out by hand (no trigonometry or allocation beyond one table sine and cosine per piece).
	 */
	private void piece(TextureAtlasSprite sprite, float u, float v, float rot, float half, int argb) {
		if ((argb & 0xFFFFFF) == 0 || half <= 0) {
			return;
		}
		float s = Mth.sin(rot * 0.5F);
		float c = Mth.cos(rot * 0.5F);
		float qx = plane.x * c + plane.y * s;
		float qy = plane.y * c - plane.x * s;
		float qz = plane.z * c + plane.w * s;
		float qw = plane.w * c - plane.z * s;
		state.add(layer, cx + axisU.x * u + axisV.x * v, cy + axisU.y * u + axisV.y * v, cz + axisU.z * u + axisV.z * v, qx, qy, qz, qw, half,
				sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1(), argb, LightCoordsUtil.FULL_BRIGHT);
	}

	/**
	 * How much daylight the circle stands in, 0..1: the sky light where it stands, times how bright the sky is now (0 at night,
	 * underground or indoors).
	 */
	private static float daylight(Frame f) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return 0F;
		}
		int sky = LightCoordsUtil.sky(LightCoordsUtil.getLightCoords(level, BlockPos.containing(f.x, f.y, f.z)));
		return Mth.clamp(sky / 15F * (1F - level.getSkyDarken() / 11F), 0F, 1F);
	}

	/**
	 * Additive light of {@code rgb} at {@code intensity}: premultiplied into the colour with the alpha kept full, because the particle
	 * shader discards fragments under a tenth of alpha, which would cut every soft glow off at a hard, stepped edge.
	 */
	static int light(float intensity, int rgb) {
		float k = Mth.clamp(intensity, 0F, 1F);
		int r = Math.round(((rgb >> 16) & 0xFF) * k);
		int g = Math.round(((rgb >> 8) & 0xFF) * k);
		int b = Math.round((rgb & 0xFF) * k);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	static int lighter(int rgb, float t) {
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		return ((r + Math.round((255 - r) * t)) << 16) | ((g + Math.round((255 - g) * t)) << 8) | (b + Math.round((255 - b) * t));
	}
}
