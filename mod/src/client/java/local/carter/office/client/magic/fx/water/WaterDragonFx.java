package local.carter.office.client.magic.fx.water;

import org.joml.Quaternionf;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.client.magic.fx.LightPipeline;
import local.carter.office.client.magic.fx.MagicCircleRenderer;
import local.carter.office.client.magic.fx.Shake;
import local.carter.office.client.magic.fx.SpellFx;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.water.DragonGeometry;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Water Dragon: a great circle opens before the caster and a sigil marks the ground where it will strike; with a roar a dragon
 * of water pours out of the circle and flies the server's own seeded path ({@link DragonGeometry}), swimming as it goes: a long
 * glowing body of water with currents spiralling through it, a ridge of fins, horns swept back, bright eyes, whiskers trailing
 * from an opening jaw, water raining off it and spray at its head. It climbs, swings over its crest and dives onto the target;
 * its body pours in after it. The crash throws up a geyser and a crown of water that races outward over the flood
 * ({@link Splash}) with shock rings of spray, a heavy splash, a camera shake of at least 0.8 for everyone within 32 blocks of the
 * impact and a three-tick hit-stop. The real flood is the server's: it lands a tick or two later.
 *
 * <p>The body is see-through water with light riding on it ({@link WaterPen}), paid from the event's budget step by step ({@link WaterPen#STEP_TICKS}); the
 * impact's share is kept back for it. In the caster's own first-person view nothing of the dragon is drawn within
 * {@link #OWN_CLEAR} blocks of their eye, so it leaves the circle and flies away from them instead of filling their screen.
 */
public final class WaterDragonFx implements SpellFx {
	/** Ticks the body pours into the impact after the head lands. */
	static final int POUR = 12;
	/** How long the impact's rings and drizzle run after the landing. */
	static final int AFTER = 26;
	static final double OWN_CLEAR = 3.2;
	/** Particles kept back for the impact. */
	private static final int IMPACT_PARTICLES = 520;

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		Vec3 eye = e.origin();
		Vec3 look = WaterFx.unit(e.dir());
		Vec3 target = e.target();
		Vec3 start = DragonGeometry.start(eye, look);
		DragonGeometry g = new DragonGeometry(start, target, e.seed());
		float[] data = e.data();
		int flood = data.length > 0 && data[0] > 0 ? Math.round(data[0]) : DragonGeometry.floodRadius(7);
		double ground = data.length > 2 ? data[2] : Math.floor(target.y);
		RandomSource r = kit.random();
		Minecraft mc = Minecraft.getInstance();
		boolean own = mc.player != null && mc.player.getId() == e.casterId() && mc.options.getCameraType().isFirstPerson();

		// The circles: a great one before the caster, a sigil on the ground where the dragon will land.
		kit.circle(start, look.reverse(), WaterFx.ARGB, 1.6F, 5, 24);
		kit.circle(new Vec3(target.x, ground + 0.07, target.z), new Vec3(0, 1, 0), WaterFx.ARGB, Math.max(2.5F, flood * 0.6F), 5,
				DragonGeometry.FLIGHT + 2);
		kit.burst(start, ParticleTypes.SPLASH, 16, 0.3);
		level.playLocalSound(start.x, start.y, start.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.PLAYERS, 1.1F, 1.45F, false);
		level.playLocalSound(start.x, start.y, start.z, SoundEvents.TRIDENT_RIPTIDE_3.value(), SoundSource.PLAYERS, 1F, 0.85F, false);
		level.playLocalSound(start.x, start.y, start.z, SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SoundSource.PLAYERS, 1F, 0.7F, false);
		kit.fovKick(0.05F);

		Dragon body = new Dragon(level, g, own ? OWN_CLEAR : 0);
		int step = body.cost();
		if (kit.budget().particles(step) == step) mc.particleEngine.add(body);

		kit.simulate(1, DragonGeometry.FLIGHT + AFTER, t -> {
			if (t > 0 && t % WaterPen.STEP_TICKS == 0 && t <= DragonGeometry.FLIGHT + POUR && kit.budget().particles(step) < step) body.dim();
			int spare = kit.budget().particlesLeft() - (t < DragonGeometry.FLIGHT ? IMPACT_PARTICLES : 0);
			if (t < DragonGeometry.FLIGHT) {
				if (spare > 0) flying(kit, body, t, r);
				Vec3 head = g.at(t);
				if (t % 6 == 3) level.playLocalSound(head.x, head.y, head.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.PLAYERS, 0.9F, 1.35F, false);
				if (t % 8 == 4) level.playLocalSound(head.x, head.y, head.z, SoundEvents.PLAYER_SWIM, SoundSource.PLAYERS, 1F, 0.6F, false);
				if (t == DragonGeometry.FLIGHT - 10) {
					level.playLocalSound(head.x, head.y, head.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.PLAYERS, 1.6F, 1.2F, false);
				}
			} else if (t == DragonGeometry.FLIGHT) {
				impact(kit, level, target, ground, flood, r);
			} else {
				after(kit, level, target, ground, flood, t - DragonGeometry.FLIGHT, r);
			}
		});
	}

	/** One tick of flight: water raining off the body, spray at the head, foam and light left in its wake. */
	private static void flying(FxKit kit, Dragon d, int t, RandomSource r) {
		d.pose(t);
		int n = Dragon.N;
		Vec3 head = d.pts[0];
		kit.burst(head.add(d.tan[0].scale(1.2)), ParticleTypes.SPLASH, 6, 0.25);
		for (int i = 0; i < 7; i++) {
			int k = 2 + r.nextInt(n - 6);
			Vec3 under = d.pts[k].subtract(d.up[k].scale(d.width[k] * 0.45));
			kit.burst(under, ParticleTypes.FALLING_WATER, 1, 0.02);
		}
		for (int i = 0; i < 3; i++) {
			int k = r.nextInt(n / 2);
			Vec3 at = d.pts[k].add(d.up[k].scale(d.width[k] * 0.4 * (r.nextDouble() * 2 - 1)));
			kit.motes(at, i == 0 ? WaterFx.FOAM : WaterFx.LIGHT, 1, 0.25, d.tan[k].scale(-0.05), 0.03, GlowMote.Drift.COAST, 0.3F, 12);
		}
		int k = n / 2 + r.nextInt(n / 2 - 1);
		kit.motes(d.pts[k], WaterFx.BODY, 1, 0.3, Vec3.ZERO, 0.02, GlowMote.Drift.FALL, 0.3F, 10);
		if (t % 2 == 0) kit.burst(d.pts[r.nextInt(n / 3)], ParticleTypes.BUBBLE_POP, 1, 0.05);
	}

	/** The crash: geyser, crown of water, spray, the ground sigil flaring, the splash and the shake. */
	private static void impact(FxKit kit, ClientLevel level, Vec3 target, double ground, int flood, RandomSource r) {
		Vec3 base = new Vec3(target.x, ground, target.z);
		kit.circle(base.add(0, 0.09, 0), new Vec3(0, 1, 0), WaterFx.ARGB, flood, 5, 20);
		// The geyser and the crown of water thrown up round it (drawn as water, see-through).
		Splash splash = new Splash(level, base, flood, r.nextLong());
		int cost = splash.cost() * 3; // it lives about three steps
		if (kit.budget().particles(cost) == cost) Minecraft.getInstance().particleEngine.add(splash);
		for (int i = 0; i < 34; i++) {
			Vec3 v = new Vec3((r.nextDouble() - 0.5) * 0.35, 0.55 + 0.45 * r.nextDouble(), (r.nextDouble() - 0.5) * 0.35);
			kit.motes(base.add(0, 0.5, 0), i % 3 == 0 ? WaterFx.FOAM : i % 3 == 1 ? WaterFx.LIGHT : WaterFx.BODY, 1, 0.4, v, 0.05,
					GlowMote.Drift.FALL, 0.32F, 22);
		}
		kit.burst(base.add(0, 0.6, 0), ParticleTypes.SPLASH, 90, 0.65);
		kit.burst(base.add(0, 1.5, 0), ParticleTypes.FALLING_WATER, 40, 0.45);
		kit.burst(base.add(0, 0.3, 0), ParticleTypes.BUBBLE_POP, 12, 0.3);
		for (int i = 0; i < 44; i++) {
			double a = Mth.TWO_PI * i / 44 + r.nextDouble() * 0.1;
			Vec3 out = new Vec3(Math.cos(a), 0, Math.sin(a));
			kit.motes(base.add(out.scale(0.8)).add(0, 0.4, 0), i % 2 == 0 ? WaterFx.FOAM : WaterFx.LIGHT, 1, 0.1,
					out.scale(0.38 + 0.12 * r.nextDouble()).add(0, 0.14, 0), 0.04, GlowMote.Drift.COAST, 0.26F, 16);
		}
		kit.ring(base.add(0, 0.2, 0), 1.5, ParticleTypes.SPLASH, 30);
		level.playLocalSound(base.x, base.y, base.z, SoundEvents.GENERIC_SPLASH, SoundSource.PLAYERS, 3F, 0.55F, false);
		level.playLocalSound(base.x, base.y, base.z, SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SoundSource.PLAYERS, 2.5F, 0.6F, false);
		level.playLocalSound(base.x, base.y, base.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.9F, 1.3F, false);
		level.playLocalSound(base.x, base.y, base.z, SoundEvents.AMBIENT_UNDERWATER_EXIT, SoundSource.PLAYERS, 1F, 0.7F, false);
		// Everyone within 32 blocks of the crash shakes by at least 0.8 (more up close); the caster is held for 3 ticks.
		Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
		double d = camera.position().distanceTo(base);
		Shake.shake(shake(d), 26);
		Shake.kick(0.09F * (float) Mth.clamp((32 - d) / 24, 0, 1));
		kit.hitStop(3);
	}

	/** Shake at {@code d} blocks from the crash: 1.6 within 8, easing to 0.8 at 32, gone by 48. */
	static float shake(double d) {
		if (d <= 8) return 1.6F;
		if (d <= 32) return (float) (1.6 - 0.8 * (d - 8) / 24);
		return (float) Math.max(0, 0.8 * (48 - d) / 16);
	}

	/** After the crash: the crown of water rolling out over the flood with shock rings of spray, then a drizzle. */
	private static void after(FxKit kit, ClientLevel level, Vec3 target, double ground, int flood, int since, RandomSource r) {
		Vec3 base = new Vec3(target.x, ground, target.z);
		int reach = Math.min(flood, 7);
		if (since <= reach) {
			int k = since + 1;
			kit.ring(base.add(0, 0.25, 0), k + 0.3, ParticleTypes.SPLASH, 18 + 4 * k);
			for (int i = 0; i < 6; i++) {
				double a = r.nextDouble() * Mth.TWO_PI;
				Vec3 out = new Vec3(Math.cos(a), 0, Math.sin(a));
				kit.motes(base.add(out.scale(k)).add(0, 0.5, 0), WaterFx.FOAM, 1, 0.1, out.scale(0.12).add(0, 0.1, 0), 0.03,
						GlowMote.Drift.FALL, 0.22F, 12);
			}
			if (since == 3) level.playLocalSound(base.x, base.y, base.z, SoundEvents.GENERIC_SPLASH, SoundSource.PLAYERS, 1.5F, 0.8F, false);
		} else if (since % 2 == 0) {
			for (int i = 0; i < 4; i++) {
				double a = r.nextDouble() * Mth.TWO_PI;
				double rad = r.nextDouble() * flood;
				kit.burst(base.add(Math.cos(a) * rad, 3 + r.nextDouble() * 2, Math.sin(a) * rad), ParticleTypes.FALLING_WATER, 1, 0.02);
			}
		}
	}

	/**
	 * The crash's water: a geyser shooting up out of the impact and falling back, and a crown of water thrown up in a ring that
	 * races outward over the flood and sinks; see-through water with foam lit on its crests.
	 */
	static final class Splash extends SingleQuadParticle implements LightPipeline.Extent {
		static final int LIFE = 26;
		private static final int COLUMNS = 40;
		private final Vec3 base;
		private final float reach;
		private final float[] lean = new float[COLUMNS];
		private final float[] tall = new float[COLUMNS];
		private final WaterPen pen = new WaterPen();
		private final Quaternionf facing = new Quaternionf();

		Splash(ClientLevel level, Vec3 base, int flood, long seed) {
			super(level, base.x, base.y, base.z, MagicCircleRenderer.sprite("glow"));
			this.base = base;
			this.reach = Math.max(3, flood);
			RandomSource r = RandomSource.create(seed);
			for (int i = 0; i < COLUMNS; i++) {
				lean[i] = (r.nextFloat() - 0.5F) * 0.12F;
				tall[i] = 0.7F + 0.6F * r.nextFloat();
			}
			this.lifetime = LIFE;
			this.hasPhysics = false;
			this.gravity = 0;
			this.xd = 0;
			this.yd = 0;
			this.zd = 0;
		}

		/** Fx-budget particles one step of drawing costs (a frame near its fullest). */
		int cost() {
			pen.dry();
			draw(LIFE * 0.3F);
			return WaterPen.cost(pen.quads);
		}

		@Override
		public void tick() {
			if (++age >= lifetime) remove();
		}

		@Override
		public void extract(QuadParticleRenderState state, Camera camera, float partial) {
			facing.set(camera.rotation());
			pen.begin(state, camera.position(), facing, 0);
			draw(age + partial);
		}

		private void draw(float t) {
			float f = Mth.clamp(t / LIFE, 0F, 1F);
			float fade = 1 - f * f;
			// The geyser.
			double gh = 9 * Math.sin(Math.PI * Math.min(1, f * 1.3));
			for (double y = 0; y < gh; y += 0.45) {
				float size = (float) (3.3 - 0.16 * y);
				pen.water(true).blob(base.x, base.y + y, base.z, size, Dragon.WATER_LIGHT, 0.4F * fade);
				pen.water(false).blob(base.x, base.y + y, base.z, size * 0.6F, WaterFx.LIGHT, 0.12F * fade);
			}
			pen.blob(base.x, base.y + gh, base.z, 2.2F, WaterFx.FOAM, 0.35F * fade);
			// The crown.
			double g = 1 - (1 - f) * (1 - f);
			double r = 1.5 + reach * 0.8 * g;
			double h = 4.5 * Math.sin(Math.PI * Math.min(1, f * 1.4));
			if (h < 0.1) return;
			for (int i = 0; i < COLUMNS; i++) {
				double a = Mth.TWO_PI * i / COLUMNS;
				double ch = h * tall[i];
				double cx = Math.cos(a);
				double cz = Math.sin(a);
				for (double y = 0; y < ch; y += 0.35) {
					double rr = r + y * (0.45 + lean[i]);
					float size = (float) (2.3 * (1 - 0.45 * y / ch));
					pen.water(true).blob(base.x + cx * rr, base.y + y, base.z + cz * rr, size, Dragon.WATER_LIGHT, 0.3F * fade);
				}
				double rr = r + ch * (0.45 + lean[i]);
				pen.water(false).blob(base.x + cx * rr, base.y + ch, base.z + cz * rr, 1F, WaterFx.FOAM, 0.3F * fade);
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
			return base.x;
		}

		@Override
		public double centreY() {
			return base.y + 4;
		}

		@Override
		public double centreZ() {
			return base.z;
		}

		@Override
		public double reach() {
			return reach * 1.3 + 6;
		}
	}

	/**
	 * The dragon, drawn as light along the path: {@link #N} points spaced along the path behind the head (by arc length, so
	 * it is bunched in the circle as it pours out and pours into the impact after the head lands), swimming from side to side.
	 */
	static final class Dragon extends SingleQuadParticle implements LightPipeline.Extent {
		static final int N = 34;
		/** Thickest part of the body, and the head's size. */
		private static final float THICK = 2.3F;
		private static final float HEAD = 2.1F;

		private final DragonGeometry g;
		private final double clear;
		private final double bodyLength;
		private final WaterPen pen = new WaterPen();
		private final Quaternionf facing = new Quaternionf();
		final Vec3[] pts = new Vec3[N];
		final Vec3[] tan = new Vec3[N];
		final Vec3[] up = new Vec3[N];
		final Vec3[] side = new Vec3[N];
		final float[] width = new float[N];
		private final float[] alpha = new float[N];
		private final Vec3[] tmp = new Vec3[N];
		private final float[] thin = new float[N];
		/** The currents' thickness. */
		private static final int SUB = 3;
		private static final float[] CURRENT = filled(N * SUB, 0.12F);
		private final Vec3[] current = new Vec3[N * SUB];
		private final float[] currentA = new float[N * SUB];
		/** The water of the body (RGB; drawn see-through). */
		static final int WATER_DEEP = 0x2A78E0;
		static final int WATER_LIGHT = 0x78C6FF;
		private final Vec3[] p3 = new Vec3[3];
		private final float[] w3 = new float[3];
		private final float[] a3 = new float[3];
		private final Vec3[] whisker = new Vec3[8];
		private final float[] whiskerW = new float[8];
		private final float[] whiskerA = new float[8];
		private float dim = 1;
		private double t;

		Dragon(ClientLevel level, DragonGeometry g, double clear) {
			super(level, g.start().x, g.start().y, g.start().z, MagicCircleRenderer.sprite("glow"));
			this.g = g;
			this.clear = clear;
			this.bodyLength = Mth.clamp(g.length() * 0.5, 14, 26);
			this.lifetime = DragonGeometry.FLIGHT + POUR + 2;
			this.hasPhysics = false;
			this.gravity = 0;
			this.xd = 0;
			this.yd = 0;
			this.zd = 0;
		}

		void dim() {
			dim = 0.3F;
		}

		@Override
		public void tick() {
			age++;
			Vec3 h = g.at(age);
			x = h.x;
			y = h.y;
			z = h.z;
			if (age >= lifetime) remove();
		}

		/** Fx-budget particles one step of drawing costs: the quads of a mid-flight frame. */
		int cost() {
			pose(DragonGeometry.FLIGHT * 0.6);
			pen.dry();
			draw();
			return WaterPen.cost(pen.quads);
		}

		/** Lays the body out at time {@code time} (ticks since the cast). */
		void pose(double time) {
			t = time;
			double headS = g.headDistance(time);
			double l = g.length();
			double ds = bodyLength / (N - 1);
			for (int k = 0; k < N; k++) {
				double s = headS - k * ds;
				pts[k] = g.along(s);
				double u = k / (double) (N - 1);
				// Swimming: a wave runs down the body, none where it is still bunched in the circle or the impact.
				double env = smooth(u / 0.18) * Mth.clamp(s / 3, 0, 1) * Mth.clamp((l - s) / 3, 0, 1);
				tmp[k] = new Vec3(env, u, 0);
				width[k] = THICK * profile(u) * (float) (0.45 + 0.55 * Mth.clamp(s / 2.5, 0, 1));
				alpha[k] = (float) Mth.clamp((l + 1.5 - s) / 1.5, 0, 1); // fades as it pours into the ground
			}
			for (int k = 0; k < N; k++) {
				Vec3 a = pts[Math.max(0, k - 1)];
				Vec3 b = pts[Math.min(N - 1, k + 1)];
				Vec3 d = a.subtract(b);
				tan[k] = d.lengthSqr() < 1.0E-8 ? g.tangent(Math.min(time, DragonGeometry.FLIGHT)) : d.normalize();
				Vec3 upk = new Vec3(0, 1, 0).subtract(tan[k].scale(tan[k].y));
				up[k] = upk.lengthSqr() < 1.0E-6 ? new Vec3(1, 0, 0) : upk.normalize();
				side[k] = tan[k].cross(up[k]);
			}
			for (int k = 0; k < N; k++) {
				double env = tmp[k].x;
				double u = tmp[k].y;
				double phase = u * Mth.TWO_PI * 1.3 - time * 0.55;
				pts[k] = pts[k].add(side[k].scale(0.8 * env * Math.sin(phase))).add(up[k].scale(0.35 * env * Math.sin(phase * 0.7 + 1.1)));
			}
		}

		/** Thickness along the body (0 at the head, 1 at the tail): head, a thinner neck, a full body, a long taper. */
		private static float profile(double u) {
			if (u < 0.05) return 0.9F;
			if (u < 0.14) return (float) Mth.lerp((u - 0.05) / 0.09, 0.9, 0.7);
			if (u < 0.28) return (float) Mth.lerp((u - 0.14) / 0.14, 0.7, 1.0);
			if (u < 0.5) return 1F;
			double f = (u - 0.5) / 0.5;
			return (float) Math.max(0.08, 1 - f * f * 0.92);
		}

		private static float[] filled(int n, float v) {
			float[] a = new float[n];
			java.util.Arrays.fill(a, v);
			return a;
		}

		private static double smooth(double x) {
			double c = Mth.clamp(x, 0, 1);
			return c * c * (3 - 2 * c);
		}

		@Override
		public void extract(QuadParticleRenderState state, Camera camera, float partial) {
			pose(age + partial);
			facing.set(camera.rotation());
			pen.begin(state, camera.position(), facing, clear);
			draw();
		}

		private void draw() {
			float k = dim * (float) Mth.clamp(t / 2, 0.35, 1);
			for (int i = 0; i < N; i++) thin[i] = alpha[i] * k;
			// The body is water: blue, see-through, blended over the sky (real-looking by day as well as by night)...
			pen.water(true);
			pen.tube(pts, width, thin, N, 1.15F, 0.3F, 0.1F, WATER_DEEP, 0.4F);
			pen.tube(pts, width, thin, N, 0.7F, 0.35F, 0.1F, WATER_LIGHT, 0.3F);
			pen.water(false);
			// ...lit from inside: a soft halo, and a bright sheen riding high on the side you see.
			pen.tube(pts, width, thin, N, 2.1F, 0.55F, 0.2F, WaterFx.DEEP, 0.07F);
			for (int i = 0; i < N; i++) tmp[i] = pen.towardCamera(pts[i].add(up[i].scale(width[i] * 0.22)), width[i] * 0.5);
			pen.tube(tmp, width, thin, N, 0.38F, 0.45F, 0.1F, WaterFx.LIGHT, 0.2F);
			// Two currents spiralling through the front of the body.
			int front = (int) (N * 0.8);
			for (int h = 0; h < 2; h++) {
				int m = 0;
				for (int i = 0; i + 1 < front; i++) {
					for (int q = 0; q < SUB; q++) {
						double f = q / (double) SUB;
						double phi = (i + f) * 0.55 + t * 0.5 + h * Math.PI;
						double rad = Mth.lerp(f, width[i], width[i + 1]) * 0.42;
						Vec3 c = pts[i].lerp(pts[i + 1], f);
						Vec3 sd = side[i].lerp(side[i + 1], f);
						Vec3 upv = up[i].lerp(up[i + 1], f);
						current[m] = c.add(sd.scale(Math.cos(phi) * rad)).add(upv.scale(Math.sin(phi) * rad));
						currentA[m] = (float) Mth.lerp(f, thin[i], thin[i + 1]);
						m++;
					}
				}
				pen.line(current, CURRENT, currentA, m, WaterFx.FOAM, 0.4F);
			}
			// The ridge of fins along the back, swept back: soft water with a bright edge.
			for (int i = 5; i < N * 0.78; i += 3) {
				Vec3 b = pts[i].add(up[i].scale(width[i] * 0.4));
				Vec3 tip = b.add(up[i].scale(width[i] * 0.6)).subtract(tan[i].scale(width[i] * 0.6));
				lay3(b, b.lerp(tip, 0.5), tip, width[i] * 0.3F, width[i] * 0.18F, 0.04F, thin[i]);
				pen.water(true);
				pen.tube(p3, w3, a3, 3, 1F, 0.4F, 0.08F, WATER_LIGHT, 0.35F);
				pen.water(false);
				pen.segment(b, tip, 0.14F, 0.03F, WaterFx.FOAM, 0.55F * thin[i]);
			}
			float head = (float) Mth.clamp(1 - (t - DragonGeometry.FLIGHT) / 3, 0, 1) * k;
			if (head > 0.01F) head(head);
		}

		/** The head at the front point: skull, opening jaw, horns, eyes and whiskers. */
		private void head(float a) {
			Vec3 p = pts[0];
			Vec3 f = tan[0];
			Vec3 u = up[0];
			Vec3 s = side[0];
			float h = HEAD;
			float open = (float) (0.25 + 0.75 * smooth((t - 12) / (DragonGeometry.FLIGHT - 12)));
			// Skull and snout.
			lay3(p.subtract(f.scale(0.3 * h)), p.add(f.scale(0.5 * h)).add(u.scale(0.12 * h)), p.add(f.scale(1.2 * h)).add(u.scale(0.04 * h)),
					1.0F * h, 0.75F * h, 0.4F * h, a);
			pen.water(true);
			pen.tube(p3, w3, a3, 3, 1.1F, 0.3F, 0.08F, WATER_DEEP, 0.45F);
			pen.water(false);
			pen.tube(p3, w3, a3, 3, 1.9F, 0.5F, 0.12F, WaterFx.DEEP, 0.08F);
			pen.tube(p3, w3, a3, 3, 0.5F, 0.4F, 0.06F, WaterFx.LIGHT, 0.2F);
			// The lower jaw, dropping open as it dives.
			lay3(p.subtract(u.scale(0.18 * h)), p.add(f.scale(0.6 * h)).subtract(u.scale((0.3 + 0.35 * open) * h)),
					p.add(f.scale(1.05 * h)).subtract(u.scale((0.25 + 0.6 * open) * h)), 0.5F * h, 0.36F * h, 0.2F * h, a);
			pen.water(true);
			pen.tube(p3, w3, a3, 3, 1.0F, 0.3F, 0.05F, WATER_DEEP, 0.45F);
			pen.water(false);
			for (int i = 0; i < 3; i++) w3[i] = 0.09F;
			pen.line(p3, w3, a3, 3, WaterFx.FOAM, 0.55F);
			// The upper lip.
			lay3(p.add(f.scale(0.15 * h)).subtract(u.scale(0.05 * h)), p.add(f.scale(0.75 * h)).subtract(u.scale(0.02 * h)),
					p.add(f.scale(1.3 * h)), 0.07F, 0.07F, 0.05F, a);
			pen.line(p3, w3, a3, 3, WaterFx.FOAM, 0.55F);
			for (int sg = -1; sg <= 1; sg += 2) {
				// Horns, swept back.
				lay3(p.add(u.scale(0.35 * h)).add(s.scale(0.28 * h * sg)), p.subtract(f.scale(0.7 * h)).add(u.scale(0.9 * h)).add(s.scale(0.45 * h * sg)),
						p.subtract(f.scale(1.55 * h)).add(u.scale(1.25 * h)).add(s.scale(0.62 * h * sg)), 0.17F * h, 0.11F * h, 0.025F * h, a);
				pen.tube(p3, w3, a3, 3, 1.8F, 0.6F, 0.06F, WaterFx.LIGHT, 0.16F);
				pen.line(p3, w3, a3, 3, WaterFx.FOAM, 0.65F);
				// Eyes.
				Vec3 eye = p.add(f.scale(0.42 * h)).add(u.scale(0.33 * h)).add(s.scale(0.3 * h * sg));
				pen.blob(eye, 0.62F * h, WaterFx.LIGHT, 0.4F * a);
				pen.blob(eye, 0.24F * h, 0xFFFFFF, 0.95F * a);
				// Whiskers trailing from the snout.
				Vec3 root = p.add(f.scale(1.0 * h)).add(s.scale(0.25 * h * sg));
				for (int i = 0; i < whisker.length; i++) {
					double q = i / (double) (whisker.length - 1);
					whisker[i] = root.add(s.scale(sg * 0.5 * h * q)).subtract(f.scale(2.6 * h * q))
							.add(u.scale(Math.sin(q * 5 - t * 0.6) * 0.35 * h * q - 0.3 * h * q));
					whiskerW[i] = (float) (0.08 - 0.06 * q);
					whiskerA[i] = a * (float) (1 - 0.6 * q);
				}
				pen.line(whisker, whiskerW, whiskerA, whisker.length, WaterFx.LIGHT, 0.6F);
			}
		}

		private void lay3(Vec3 a, Vec3 b, Vec3 c, float wa, float wb, float wc, float al) {
			p3[0] = a;
			p3[1] = b;
			p3[2] = c;
			w3[0] = wa;
			w3[1] = wb;
			w3[2] = wc;
			a3[0] = al;
			a3[1] = al;
			a3[2] = al;
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
			return bodyLength + 4;
		}
	}
}
