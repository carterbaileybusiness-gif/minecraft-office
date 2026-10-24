package local.carter.office.client.magic.fx.wind;

import java.util.ArrayList;
import java.util.List;

import org.joml.Quaternionf;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.client.magic.fx.LightPipeline;
import local.carter.office.client.magic.fx.MagicCircleRenderer;
import local.carter.office.client.magic.fx.Shake;
import local.carter.office.client.magic.fx.SpellFx;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.TargetFilter;
import local.carter.office.magic.spell.wind.BladeWindGeometry;
import local.carter.office.magic.spell.wind.DrillGeometry;
import local.carter.office.magic.spell.wind.WindSphere;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Wind Sphere: a Rasengan. A sphere of wind spins up in the caster's right hand (bands of white-hot wind whirling round a blazing
 * core inside a flickering cyan shell, wind drawn in from all round), then the caster rams it forward, trailing a spiral wake.
 * Where it strikes, it bursts: a flash, a spiral shockwave whose arms wheel out across the hit, rings, wind lines thrown back, and
 * for a block hit a vortex that bores down the drill's cone while the drilled rock bursts out spinning. Camera shake 0.9
 * (at least 0.8 within 32 blocks), a 3-tick hit-stop and an FOV kick.
 *
 * <p>The dash is replayed from the same sweep as the server ({@link WindSphere#step} on {@link DrillGeometry#sphere}), live, so a
 * creature stepping into the path sets the picture off where the server sets the blast off; the server's prediction
 * ({@code target}, {@code data}) is the fallback.
 *
 * <p>Budget (1500, an ultimate): every visual pays its quads, 50 to a budget particle per 10-tick step, before it is drawn, out of
 * fixed shares: the sphere, the wake, the burst, the drill and the rock.
 */
public final class WindSphereFx implements SpellFx {
	/** Rasengan colours: a white core, pale cyan bands, a cyan-blue halo, mint (the wind's own colour) in the shell. */
	static final int CORE = 0xFFFFFF;
	static final int BAND = 0xC8F6FF;
	static final int HALO = 0x58CFFF;
	static final int MINT = 0x9CF2C8;
	/** The sphere's radius in the hand. */
	static final float RADIUS = 0.42F;

	static final int STEP = 10;
	/** Budget shares (particles) of the 1500. */
	static final int ORB_SHARE = 170;
	static final int WAKE_SHARE = 110;
	static final int BURST_SHARE = 360;
	static final int DRILL_SHARE = 330;
	static final int ROCK_SHARE = 220;
	static final int MOTE_SHARE = 260;

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		Vec3 eye = e.origin();
		Vec3 look = e.dir().lengthSqr() < 1.0E-8 ? new Vec3(0, 0, 1) : e.dir().normalize();
		float[] data = e.data();
		int length = data.length > 0 ? Math.max(1, Math.round(data[0])) : DrillGeometry.BASE_LENGTH;
		double predictedT = data.length > 1 ? Mth.clamp(data[1], 0F, DrillGeometry.DASH_TICKS) : DrillGeometry.DASH_TICKS;
		WindSphere.Kind predictedKind = data.length > 2 ? WindSphere.Kind.values()[Mth.clamp(Math.round(data[2]), 0, 2)] : WindSphere.Kind.AIR;
		int caster = e.casterId();

		Orb orb = new Orb(level, caster, eye, look, e.seed());
		Wake wake = new Wake(level, caster, look);
		int[] orbPaid = {0};
		int[] wakePaid = {0};
		int[] motes = {MOTE_SHARE};
		RandomSource r = kit.random();
		Minecraft.getInstance().particleEngine.add(orb);
		Minecraft.getInstance().particleEngine.add(wake);

		Vec3 hand = orb.hand(1F);
		level.playLocalSound(hand.x, hand.y, hand.z, SoundEvents.BREEZE_CHARGE, SoundSource.PLAYERS, 1.2F, 1.3F, false);
		level.playLocalSound(hand.x, hand.y, hand.z, SoundEvents.TRIDENT_RIPTIDE_1.value(), SoundSource.PLAYERS, 0.7F, 1.7F, false);

		boolean[] done = {false};
		int total = DrillGeometry.WINDUP + DrillGeometry.DASH_TICKS + 2;
		kit.simulate(1, total, t -> {
			if (done[0]) return;
			orb.keep();
			wake.keep();
			// Each 10-tick step the sphere and its wake pay for what they draw.
			if (t % STEP == 0) {
				orb.paid(pay(kit, orb.cost(), ORB_SHARE / 2, orbPaid));
				wake.paid(pay(kit, wake.cost(), WAKE_SHARE / 2, wakePaid));
			}
			Vec3 at = orb.hand(1F);
			if (t < DrillGeometry.WINDUP) {
				// Forming: sparks of wind drawn in (the sphere draws its own inflowing spirals).
				if (motes[0] > 0) {
					int n = Math.min(motes[0], 3);
					motes[0] -= n;
					for (int i = 0; i < n; i++) {
						Vec3 d = sphere(r).scale(0.9 + r.nextDouble() * 0.5);
						kit.motes(at.add(d), i % 2 == 0 ? CORE : BAND, 1, 0.02, d.scale(-0.28), 0, GlowMote.Drift.COAST, 0.07F, 4);
					}
				}
				if (t == 2) level.playLocalSound(at.x, at.y, at.z, SoundEvents.BREEZE_WHIRL, SoundSource.PLAYERS, 1.0F, 1.6F, false);
				return;
			}
			int k = t - DrillGeometry.WINDUP;
			if (k == 0) {
				orb.dash();
				level.playLocalSound(at.x, at.y, at.z, SoundEvents.TRIDENT_RIPTIDE_3.value(), SoundSource.PLAYERS, 1.0F, 1.25F, false);
				level.playLocalSound(at.x, at.y, at.z, SoundEvents.WIND_CHARGE_THROW, SoundSource.PLAYERS, 1.0F, 0.7F, false);
				kit.fovKick(0.1F);
				return;
			}
			wake.add(at);
			if (motes[0] > 0) {
				int n = Math.min(motes[0], 6);
				motes[0] -= n;
				kit.motes(at, k % 2 == 0 ? CORE : BAND, n, 0.3, look.scale(-0.08), 0.05, GlowMote.Drift.SWIRL, 0.08F, 8);
			}
			// The live sweep, as the server's: the body stopped short (where this client sees the caster), a creature stepping in,
			// a block on the sphere's or the body's line; else the prediction.
			Entity body = level.getEntity(caster);
			WindSphere.Strike s = body == null ? null : WindSphere.stalled(level, body, eye, look, k - 1);
			if (s == null) s = WindSphere.step(level, eye, look, k, x -> x.getId() != caster && TargetFilter.affects(null, x));
			if (s == null && k >= Math.ceil(predictedT)) {
				Vec3 p = e.target();
				s = new WindSphere.Strike(predictedKind, predictedT, p, null, predictedKind == WindSphere.Kind.BLOCK ? DrillGeometry.body(p) : null);
			}
			if (s == null) return;
			done[0] = true;
			orb.burst(s.at());
			wake.end();
			detonate(e, level, kit, s, look, length, r);
		});
	}

	/** Pays {@code cost} out of a share with {@code paid[0]} spent so far (at most {@code limit} a step); true when it was paid. */
	private static boolean pay(FxKit kit, int cost, int limit, int[] paid) {
		if (cost > limit) return false;
		int got = kit.budget().particles(cost);
		paid[0] += got;
		return got >= cost;
	}

	/** The burst where the sphere struck. */
	private static void detonate(FxEvent e, ClientLevel level, FxKit kit, WindSphere.Strike s, Vec3 look, int length, RandomSource r) {
		Vec3 at = s.at();
		boolean block = s.kind() == WindSphere.Kind.BLOCK;
		boolean air = s.kind() == WindSphere.Kind.AIR;
		float size = air ? 0.7F : 1F;

		level.playLocalSound(at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.4F, 1.3F, false);
		level.playLocalSound(at.x, at.y, at.z, SoundEvents.WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 2.0F, 0.55F, false);
		level.playLocalSound(at.x, at.y, at.z, SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 1.5F, 0.7F, false);
		if (block) level.playLocalSound(at.x, at.y, at.z, SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.PLAYERS, 1.2F, 0.8F, false);

		Shake.shake(shake(Minecraft.getInstance().gameRenderer.mainCamera().position().distanceTo(at)), 14);
		kit.hitStop(3);
		kit.fovKick(0.14F);

		// The spiral shockwave across the hit, and for a block hit the vortex down the drill.
		Spiral wave = new Spiral(level, at, block && s.drill() != null ? s.drill() : at, look, size, block ? length : 0, e.seed());
		int waveCost = wave.cost();
		if (waveCost <= BURST_SHARE + (block ? DRILL_SHARE : 0) && kit.budget().particles(waveCost) >= waveCost) {
			Minecraft.getInstance().particleEngine.add(wave);
		}
		if (block && s.drill() != null) rock(level, kit, s.drill(), look, length, r);
		// Wind lines thrown back out of the hit, spiralling.
		BladeWindGeometry.Frame back = BladeWindGeometry.frame(look.reverse());
		int lines = air ? 10 : 18;
		for (int i = 0; i < lines; i++) {
			double phase = Mth.TWO_PI * (i + r.nextDouble() * 0.5) / lines;
			GustFx.Streak line = new GustFx.Streak(level, at, back, phase, 0.3 + 0.3 * r.nextDouble(), (i % 2 == 0 ? 1 : -1) * (1.2 + r.nextDouble()),
					(3.5 + 2.5 * r.nextDouble()) * size, 9 + r.nextInt(5), 0.22F, i % 3 == 0 ? CORE : BAND);
			if (kit.budget().particles(line.cost()) < line.cost()) break;
			Minecraft.getInstance().particleEngine.add(line);
		}
		// Fewer, finer motes when the camera is in the blast (the caster's own view): big ones right at the lens read as blobs.
		float near = Minecraft.getInstance().gameRenderer.mainCamera().position().distanceTo(at) < 4 ? 0.4F : 1F;
		kit.motes(at, CORE, Math.round(40 * size * near), 0.5, Vec3.ZERO, 0.6, GlowMote.Drift.COAST, 0.1F * (0.5F + 0.5F * near), 12);
		kit.motes(at, HALO, Math.round(30 * size * near), 0.9, look.scale(0.15), 0.35, GlowMote.Drift.SWIRL, 0.1F * (0.5F + 0.5F * near), 16);

	}

	/** The drilled rock bursting out spinning: block particles from the drill's cells, as this client sees them now. */
	private static void rock(ClientLevel level, FxKit kit, Vec3 start, Vec3 look, int length, RandomSource r) {
		List<BlockPos> blocks = DrillGeometry.blocks(start, look, length);
		int spent = 0;
		for (int i = 0; i < blocks.size() && spent < ROCK_SHARE; i += 2) {
			BlockPos p = blocks.get(i);
			BlockState st = level.getBlockState(p);
			if (st.isAir() || st.getRenderShape() == RenderShape.INVISIBLE || !st.getFluidState().isEmpty()) continue;
			int n = kit.budget().particles(i < 60 ? 3 : 2);
			spent += n;
			Vec3 v0 = DrillGeometry.debris(p, start, look, new java.util.Random(r.nextLong()));
			BlockParticleOption opt = new BlockParticleOption(ParticleTypes.BLOCK, st);
			for (int j = 0; j < n; j++) {
				Vec3 v = v0.scale(0.6 + 0.6 * r.nextDouble()).add((r.nextDouble() - 0.5) * 0.2, r.nextDouble() * 0.2, (r.nextDouble() - 0.5) * 0.2);
				level.addParticle(opt, p.getX() + r.nextDouble(), p.getY() + r.nextDouble(), p.getZ() + r.nextDouble(), v.x, v.y, v.z);
			}
		}
	}

	/** Shake for a camera {@code d} from the hit: 0.9 within 24, easing to 0.8 at 32, then to nothing at 48. */
	static float shake(double d) {
		if (d <= 24) return 0.9F;
		if (d <= 32) return (float) (0.9 - 0.1 * (d - 24) / 8);
		return (float) Math.max(0, 0.8 * (48 - d) / 16);
	}

	private static Vec3 sphere(RandomSource r) {
		double z = r.nextDouble() * 2 - 1;
		double a = r.nextDouble() * Mth.TWO_PI;
		double s = Math.sqrt(1 - z * z);
		return new Vec3(s * Math.cos(a), z, s * Math.sin(a));
	}

	// ---- the sphere ------------------------------------------------------------------------------------------------------------

	/**
	 * The sphere: billboarded glows (a cyan halo, a pale body, a white-hot core) inside whirling bands of wind — great-circle arcs
	 * on tilted planes, each spinning fast round its own axis while its axis slowly precesses — and an outer shell of shorter,
	 * faster mint-and-cyan arcs that flicker. It sits in the caster's right hand while forming and dashing, then bursts where it
	 * struck: it swells, flares and its bands fly apart. It lives while its fx timer keeps it.
	 */
	/**
	 * The sphere's shape, pure (no world, no sprites): its bands and shell arcs from the seed, and the rim and inflow strands, laid
	 * into scratch arrays for {@link WindRibbon} to draw. {@link #cost} is what the sphere pays its fx budget per 10-tick step: its
	 * quads a frame at their most, per 50.
	 */
	static final class Shape {
		static final int BANDS = 11;
		static final int SHELL = 8;
		static final int POINTS = 22;
		static final int RIM_POINTS = 41;
		static final int INFLOW = 8;

		final Vec3[] normal = new Vec3[BANDS + SHELL];
		final Vec3[] precess = new Vec3[BANDS + SHELL];
		final double[] speed = new double[BANDS + SHELL];
		final double[] phase = new double[BANDS + SHELL];
		final double[] span = new double[BANDS + SHELL];
		final double[] scale = new double[BANDS + SHELL];
		final Vec3[] pts = new Vec3[POINTS];
		final float[] width = new float[POINTS];
		final float[] alpha = new float[POINTS];
		final Vec3[] rimPts = new Vec3[RIM_POINTS];
		final float[] rimWidth = new float[RIM_POINTS];
		final float[] rimAlpha = new float[RIM_POINTS];

		Shape(long seed) {
			RandomSource r = RandomSource.create(seed ^ 0x5A5EL);
			for (int i = 0; i < BANDS + SHELL; i++) {
				normal[i] = WindSphereFx.sphere(r);
				precess[i] = WindSphereFx.sphere(r);
				boolean shell = i >= BANDS;
				speed[i] = (shell ? 0.9 : 0.6) + r.nextDouble() * (shell ? 0.7 : 0.6);
				if (r.nextBoolean()) speed[i] = -speed[i];
				phase[i] = r.nextDouble() * Mth.TWO_PI;
				span[i] = shell ? Math.toRadians(80 + r.nextDouble() * 70) : Math.toRadians(190 + r.nextDouble() * 110);
				scale[i] = shell ? 1.3 + r.nextDouble() * 0.3 : 0.9 + r.nextDouble() * 0.16;
			}
		}

		/** Fx-budget particles per step: every band, the rim, the thrust collar and the inflow at their largest, per 50 quads. */
		int cost() {
			int quads = 8;
			for (int i = 0; i < BANDS + SHELL; i++) {
				lay(i, Vec3.ZERO, 0, RADIUS, 1, 0.07F);
				quads += WindRibbon.pieces(pts, width);
			}
			rim(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(0, 1, 0), 0, RADIUS, 1);
			quads += WindRibbon.pieces(rimPts, rimWidth);
			// The thrust collar is the same ring, 1.6 times wider round a sphere swollen 1.3 times.
			rim(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(0, 1, 0), 0, RADIUS * 1.3F * 1.6F, 1);
			quads += WindRibbon.pieces(rimPts, rimWidth);
			for (int j = 0; j < INFLOW; j++) {
				inflow(j, Vec3.ZERO, 0, RADIUS, 1);
				quads += WindRibbon.pieces(pts, width);
			}
			return WindRibbon.budgetCost(quads);
		}

		/** Band {@code i} at time {@code now} into the scratch arrays: an arc of its great circle round {@code c}, radius {@code r}. */
		void lay(int i, Vec3 c, float now, float r, float a, float w) {
			Vec3 n = rotate(normal[i], precess[i], now * 0.07);
			Vec3 b1 = n.cross(Math.abs(n.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0)).normalize();
			Vec3 b2 = n.cross(b1);
			double head = phase[i] + speed[i] * now;
			double sp = span[i];
			double rr = r * scale[i];
			for (int k = 0; k < POINTS; k++) {
				double s = k / (double) (POINTS - 1);
				double th = head - Math.signum(speed[i]) * sp * (1 - s);
				pts[k] = c.add(b1.scale(Math.cos(th) * rr)).add(b2.scale(Math.sin(th) * rr));
				float taper = (float) Math.sin(Math.PI * Math.pow(s, 0.7));
				width[k] = w * (0.25F + 0.75F * taper) * (float) (rr / RADIUS);
				alpha[k] = a * taper;
			}
		}

		/** The edge ring round {@code c} in the camera's plane ({@code left}, {@code up}), radius {@code r}, into the rim arrays. */
		void rim(Vec3 c, Vec3 left, Vec3 up, float now, float r, float a) {
			for (int k = 0; k < RIM_POINTS; k++) {
				double th = Mth.TWO_PI * k / (RIM_POINTS - 1);
				double wob = 1 + 0.05 * Math.sin(th * 3 + now * 1.7) + 0.03 * Math.sin(th * 5 - now * 2.3);
				double rr = r * 1.02 * wob;
				rimPts[k] = c.add(left.scale(Math.cos(th) * rr)).add(up.scale(Math.sin(th) * rr));
				float shade = 0.6F + 0.4F * (float) Math.sin(th * 2 - now * 1.1);
				rimWidth[k] = 0.075F * (r / RADIUS) * (0.7F + 0.3F * shade);
				rimAlpha[k] = a * 0.75F * shade;
			}
		}

		/**
		 * Inflow strand {@code j} into the scratch arrays: a spiral of wind on a tilted plane through {@code c}, winding in from
		 * about 3.4 radii to the sphere's edge, drawn in tighter and fading as the sphere forms.
		 */
		void inflow(int j, Vec3 c, float now, float r, float a) {
			Vec3 n = normal[j];
			Vec3 b1 = n.cross(Math.abs(n.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0)).normalize();
			Vec3 b2 = n.cross(b1);
			float form = Mth.clamp(now / DrillGeometry.WINDUP, 0F, 1F);
			double outer = 3.6 - 2.2 * form;
			double turn = phase[j] + now * 0.9;
			float fade = a * (1 - form * form * 0.85F);
			for (int k = 0; k < POINTS; k++) {
				double s = k / (double) (POINTS - 1);
				double rr = r * (outer + (1.05 - outer) * s);
				double th = turn + 2.6 * s;
				pts[k] = c.add(b1.scale(Math.cos(th) * rr)).add(b2.scale(Math.sin(th) * rr));
				float taper = (float) Math.sin(Math.PI * s);
				width[k] = 0.05F * (0.3F + 0.7F * (float) s) * (r / RADIUS);
				alpha[k] = fade * taper * (float) s;
			}
		}

		static Vec3 rotate(Vec3 v, Vec3 axis, double ang) {
			double cos = Math.cos(ang);
			double sin = Math.sin(ang);
			return v.scale(cos).add(axis.cross(v).scale(sin)).add(axis.scale(axis.dot(v) * (1 - cos))).normalize();
		}

	}

	static final class Orb extends SingleQuadParticle implements LightPipeline.Extent {
		private static final int BURST = 7;

		private final int casterId;
		private final Vec3 eye;
		private final Vec3 look;
		private final Shape shape;
		private final WindRibbon ribbon = new WindRibbon();
		private final TextureAtlasSprite glow = MagicCircleRenderer.sprite("glow");
		private final TextureAtlasSprite soft = MagicCircleRenderer.sprite("soft");
		private int kept;
		private int dashAt = -1;
		private int burstAt = -1;
		private Vec3 burstAtPos = Vec3.ZERO;
		private boolean paid = true;
		private Vec3 last;

		Orb(ClientLevel level, int casterId, Vec3 eye, Vec3 look, long seed) {
			super(level, eye.x, eye.y, eye.z, MagicCircleRenderer.sprite("line"));
			this.casterId = casterId;
			this.eye = eye;
			this.look = look;
			this.shape = new Shape(seed);
			this.lifetime = 200;
			this.hasPhysics = false;
			this.gravity = 0;
			this.xd = 0;
			this.yd = 0;
			this.zd = 0;
			this.last = hand(1F);
		}

		void keep() {
			kept = age;
		}

		void paid(boolean p) {
			paid = p;
		}

		void dash() {
			dashAt = age;
		}

		void burst(Vec3 at) {
			burstAt = age;
			burstAtPos = at;
		}

		@Override
		public void tick() {
			age++;
			if (burstAt >= 0 ? age - burstAt > BURST : age - kept > 2) {
				remove();
				return;
			}
			Vec3 p = burstAt >= 0 ? burstAtPos : hand(1F);
			x = p.x;
			y = p.y;
			z = p.z;
		}

		/** The caster's right hand: ahead, right and down of their eye (the planned path if this client can't see them). */
		Vec3 hand(float partial) {
			Entity e = level.getEntity(casterId);
			if (e == null) {
				double t = dashAt < 0 ? 0 : Math.min(DrillGeometry.DASH_TICKS, age + partial - dashAt);
				return DrillGeometry.sphere(eye, look, t);
			}
			Vec3 at = e.getEyePosition(partial);
			Vec3 view = e.getViewVector(partial);
			Vec3 right = view.cross(new Vec3(0, 1, 0));
			if (right.lengthSqr() < 1.0E-6) right = new Vec3(1, 0, 0);
			right = right.normalize();
			Vec3 up = right.cross(view).normalize();
			boolean fp = firstPerson();
			return at.add(view.scale(fp ? 0.95 : 0.85)).add(right.scale(fp ? 0.4 : 0.5)).add(up.scale(fp ? -0.34 : -0.42));
		}

		private boolean firstPerson() {
			Minecraft mc = Minecraft.getInstance();
			return mc.player != null && mc.player.getId() == casterId && mc.getCameraEntity() == mc.player && mc.options.getCameraType().isFirstPerson();
		}

		/** Fx-budget particles the sphere costs per step ({@link Shape#cost}). */
		int cost() {
			return shape.cost();
		}

		@Override
		public void extract(QuadParticleRenderState state, Camera camera, float partial) {
			float now = age + partial;
			Vec3 c;
			float rad;
			float bright;
			float spread = 1;
			boolean fp = burstAt < 0 && firstPerson();
			if (burstAt >= 0) {
				float b = Mth.clamp((now - burstAt) / BURST, 0F, 1F);
				c = burstAtPos;
				rad = RADIUS * (1.3F + 2.6F * (float) Math.sqrt(b));
				bright = (1 - b) * (1 - b);
				spread = 1 + 1.4F * b;
			} else {
				c = hand(partial);
				float form = Mth.clamp(now / DrillGeometry.WINDUP, 0F, 1F);
				float ease = 1 - (1 - form) * (1 - form);
				// Thrust forward, it swells.
				float thrust = dashAt < 0 ? 0F : Mth.clamp((now - dashAt) / 3F, 0F, 1F);
				rad = RADIUS * (0.25F + 0.75F * ease) * (1 + 0.06F * Mth.sin(now * 2.1F)) * (fp ? 0.78F : 1F) * (1 + 0.3F * thrust);
				bright = 0.55F + 0.45F * ease;
			}
			if (bright <= 0.01F) return;
			Vec3 cam = camera.position();
			Quaternionf q = camera.rotation();
			float k = paid ? 1F : 0.35F;
			// Glows: halo, body, core.
			float flicker = 0.9F + 0.1F * Mth.sin(now * 5.3F);
			// The halo in three soft layers, each faint: one big bright disc shows a hard edge where the shader drops its faint rim.
			float h = fp ? 0.6F : 1F;
			glow(state, cam, q, soft, c, rad * (fp ? 1.9F : 2.4F) * spread, HALO, 0.16F * h * bright * k);
			glow(state, cam, q, soft, c, rad * (fp ? 1.6F : 1.9F) * spread, HALO, 0.2F * h * bright * k);
			glow(state, cam, q, soft, c, rad * 1.45F * spread, HALO, 0.26F * h * bright * k);
			glow(state, cam, q, soft, c, rad * 1.15F * spread, BAND, 0.45F * bright * k * flicker);
			glow(state, cam, q, glow, c, rad * 0.9F, CORE, 0.8F * bright * k);
			glow(state, cam, q, glow, c, rad * 0.5F, CORE, 1.0F * bright * k);
			// The sphere's edge: a ring facing the camera, wobbling with the wind inside.
			Vec3 left = new Vec3(camera.leftVector().x(), camera.leftVector().y(), camera.leftVector().z());
			Vec3 up = new Vec3(camera.upVector().x(), camera.upVector().y(), camera.upVector().z());
			shape.rim(c, left, up, now, rad * spread, bright * k);
			ribbon.draw(state, cam, shape.rimPts, shape.rimWidth, shape.rimAlpha, HALO, 0.55F, CORE);
			// While forming: wind spiralling in from all round.
			if (burstAt < 0 && now < DrillGeometry.WINDUP + 2) {
				for (int j = 0; j < Shape.INFLOW; j++) {
					shape.inflow(j, c, now, rad, k);
					ribbon.draw(state, cam, shape.pts, shape.width, shape.alpha, j % 2 == 0 ? CORE : BAND, 0.6F, CORE);
				}
			}
			// Thrust: a collar of pressed air just ahead of the sphere, across the dash (not in the caster's own view).
			if (burstAt < 0 && dashAt >= 0 && !fp) {
				float push = Mth.clamp((now - dashAt) / 2F, 0F, 1F);
				BladeWindGeometry.Frame f = BladeWindGeometry.frame(look);
				shape.rim(c.add(look.scale(rad * 0.7)), f.side(), f.up(), now * 2, rad * 1.6F, bright * k * push);
				ribbon.draw(state, cam, shape.rimPts, shape.rimWidth, shape.rimAlpha, BAND, 0.5F, CORE);
			}
			// Bands and shell.
			int every = paid ? 1 : 3;
			for (int i = 0; i < Shape.BANDS + Shape.SHELL; i += every) {
				boolean shell = i >= Shape.BANDS;
				float a = bright * k * (shell ? 0.55F + 0.45F * Mth.sin(now * 3.1F + (float) shape.phase[i]) : 1F);
				if (a <= 0.02F) continue;
				shape.lay(i, c, now, rad * spread, a, shell ? 0.045F : 0.065F);
				ribbon.draw(state, cam, shape.pts, shape.width, shape.alpha, shell ? (i % 2 == 0 ? MINT : HALO) : BAND, shell ? 0.3F : 0.8F, CORE);
			}
			last = c;
		}

		private void glow(QuadParticleRenderState state, Vec3 cam, Quaternionf q, TextureAtlasSprite sprite, Vec3 p, float half, int rgb, float a) {
			if (a <= 0.004F) return;
			state.add(LightPipeline.glow(), (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z), q.x, q.y, q.z, q.w, Math.min(4F, half),
					sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1(), LightPipeline.light(a, rgb), LightCoordsUtil.FULL_BRIGHT);
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
			return last.x;
		}

		@Override
		public double centreY() {
			return last.y;
		}

		@Override
		public double centreZ() {
			return last.z;
		}

		@Override
		public double reach() {
			return 4;
		}
	}

	// ---- the wake ---------------------------------------------------------------------------------------------------------------

	/** Two strands of wind spiralling round the dash's path behind the sphere, fading from the tail. */
	static final class Wake extends SingleQuadParticle implements LightPipeline.Extent {
		private static final int MAX = 12;
		private static final int PER = 6;
		private static final int FADE = 6;

		private final int casterId;
		private final Vec3 look;
		private final BladeWindGeometry.Frame frame;
		private final List<Vec3> path = new ArrayList<>();
		private final WindRibbon ribbon = new WindRibbon();
		private Vec3[] pts = new Vec3[0];
		private float[] width = new float[0];
		private float[] alpha = new float[0];
		private int kept;
		private int ended = -1;
		private boolean paid = true;

		Wake(ClientLevel level, int casterId, Vec3 look) {
			super(level, 0, 0, 0, MagicCircleRenderer.sprite("line"));
			this.casterId = casterId;
			this.look = look;
			this.frame = BladeWindGeometry.frame(look);
			this.lifetime = 200;
			this.hasPhysics = false;
			this.gravity = 0;
		}

		void keep() {
			kept = age;
		}

		void paid(boolean p) {
			paid = p;
		}

		void add(Vec3 p) {
			path.add(p);
			if (path.size() > MAX) path.removeFirst();
			x = p.x;
			y = p.y;
			z = p.z;
		}

		void end() {
			ended = age;
		}

		@Override
		public void tick() {
			age++;
			if ((ended >= 0 && age - ended > FADE) || (ended < 0 && age - kept > 2)) remove();
		}

		/** Fx-budget particles per step: both strands along a full path, per 50 quads. */
		int cost() {
			List<Vec3> full = new ArrayList<>();
			for (int i = 0; i < MAX; i++) full.add(look.scale(DrillGeometry.SPEED * i));
			int q = 0;
			for (int s = 0; s < 2; s++) {
				lay(full, s, 0, 1);
				q += WindRibbon.pieces(pts, width);
			}
			return WindRibbon.budgetCost(q);
		}

		@Override
		public void extract(QuadParticleRenderState state, Camera camera, float partial) {
			if (path.size() < 2 || !paid) return;
			// In the caster's own first-person view the path runs back through the camera: no wake there.
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null && mc.player.getId() == casterId && mc.getCameraEntity() == mc.player && mc.options.getCameraType().isFirstPerson()) return;
			float now = age + partial;
			float fade = ended < 0 ? 1F : Mth.clamp(1F - (now - ended) / FADE, 0F, 1F);
			if (fade <= 0.01F) return;
			for (int s = 0; s < 2; s++) {
				lay(path, s, now, fade);
				ribbon.draw(state, camera.position(), pts, width, alpha, s == 0 ? BAND : HALO, 0.6F, CORE);
			}
		}

		/** Strand {@code s} wound round the path points into the scratch arrays. */
		private void lay(List<Vec3> path, int s, float now, float fade) {
			int n = (path.size() - 1) * PER + 1;
			if (pts.length != n) {
				pts = new Vec3[n];
				width = new float[n];
				alpha = new float[n];
			}
			for (int k = 0; k < n; k++) {
				int seg = Math.min(path.size() - 2, k / PER);
				double f = (k - seg * PER) / (double) PER;
				Vec3 p = path.get(seg).lerp(path.get(seg + 1), f);
				double u = k / (double) (n - 1);
				double a = now * 0.9 + k * 0.55 + s * Math.PI;
				double rad = 0.15 + 0.45 * u;
				pts[k] = p.add(frame.side().scale(Math.cos(a) * rad)).add(frame.up().scale(Math.sin(a) * rad));
				width[k] = (float) (0.05 + 0.12 * u);
				alpha[k] = (float) (fade * u * u * 0.8);
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
			return MAX * DrillGeometry.SPEED + 2;
		}
	}

	// ---- the burst --------------------------------------------------------------------------------------------------------------

	/**
	 * The spiral shockwave: arms of wind wheeling out across the plane of the hit (log spirals that widen and turn), two rings
	 * rolling out behind them, and, for a block hit, a vortex of strands boring down the drill's cone along the look and spinning
	 * there a moment while the rock goes.
	 */
	static final class Spiral extends SingleQuadParticle implements LightPipeline.Extent {
		private static final int ARMS = 6;
		private static final int ARM_POINTS = 26;
		private static final int RING_POINTS = 49;
		private static final int VORTEX = 4;
		private static final int VORTEX_POINTS = 64;
		private static final int LIFE = 16;
		private static final double REACH = 6.5;

		private final Vec3 at;
		/** Where the drill's axis starts (on the body's line), for the vortex. */
		private final Vec3 axis;
		private final Vec3 look;
		private final BladeWindGeometry.Frame frame;
		private final float size;
		private final int drill;
		private final double turn;
		private final WindRibbon ribbon = new WindRibbon();
		private Vec3[] pts = new Vec3[0];
		private float[] width = new float[0];
		private float[] alpha = new float[0];

		Spiral(ClientLevel level, Vec3 at, Vec3 axis, Vec3 look, float size, int drill, long seed) {
			super(level, at.x, at.y, at.z, MagicCircleRenderer.sprite("line"));
			this.at = at;
			this.axis = axis;
			this.look = look;
			this.frame = BladeWindGeometry.frame(look);
			this.size = size;
			this.drill = drill;
			this.turn = RandomSource.create(seed).nextDouble() * Mth.TWO_PI;
			this.lifetime = LIFE + (drill > 0 ? 8 : 0);
			this.hasPhysics = false;
			this.gravity = 0;
		}

		@Override
		public void tick() {
			age++;
			if (age >= lifetime) remove();
		}

		/** Fx-budget particles: every arm, ring and vortex strand at its largest, per 50 quads, for the whole burst (two steps). */
		int cost() {
			int q = 0;
			for (int i = 0; i < ARMS; i++) {
				arm(i, 1F, 1F);
				q += WindRibbon.pieces(pts, width);
			}
			for (int i = 0; i < 2; i++) {
				ring(i, 1F, 1F);
				q += WindRibbon.pieces(pts, width);
			}
			for (int i = 0; i < VORTEX && drill > 0; i++) {
				vortex(i, LIFE, 1F);
				q += WindRibbon.pieces(pts, width);
			}
			return (lifetime + STEP - 1) / STEP * WindRibbon.budgetCost(q);
		}

		@Override
		public void extract(QuadParticleRenderState state, Camera camera, float partial) {
			float now = age + partial;
			Vec3 cam = camera.position();
			float t = Mth.clamp(now / LIFE, 0F, 1F);
			if (t < 1) {
				float a = (1 - t) * (1 - t) * Math.min(1F, now * 1.5F);
				for (int i = 0; i < ARMS; i++) {
					arm(i, t, a);
					ribbon.draw(state, cam, pts, width, alpha, i % 2 == 0 ? BAND : HALO, 0.7F, CORE);
				}
				for (int i = 0; i < 2; i++) {
					float rt = Mth.clamp((now - 1.5F * i) / (LIFE - 2), 0F, 1F);
					if (rt <= 0 || rt >= 1) continue;
					ring(i, rt, (1 - rt) * (1 - rt));
					ribbon.draw(state, cam, pts, width, alpha, i == 0 ? CORE : MINT, 0.5F, CORE);
				}
			}
			if (drill > 0) {
				float fade = Mth.clamp((lifetime - now) / 8F, 0F, 1F);
				for (int i = 0; i < VORTEX; i++) {
					vortex(i, now, fade);
					ribbon.draw(state, cam, pts, width, alpha, i % 2 == 0 ? CORE : BAND, 0.8F, CORE);
				}
			}
		}

		/** Spiral arm {@code i} at progress {@code t} (0..1), brightness {@code a}. */
		private void arm(int i, float t, float a) {
			ensure(ARM_POINTS);
			double grow = (0.4 + REACH * (1 - Math.pow(1 - t, 2.2))) * size;
			double base = turn + Mth.TWO_PI * i / ARMS + 2.4 * t;
			for (int k = 0; k < ARM_POINTS; k++) {
				double s = k / (double) (ARM_POINTS - 1);
				double rad = grow * (0.12 + 0.88 * s);
				double ang = base + 2.2 * s;
				pts[k] = at.add(frame.side().scale(Math.cos(ang) * rad)).add(frame.up().scale(Math.sin(ang) * rad)).add(look.scale(-0.3 * s * t));
				float taper = (float) Math.sin(Math.PI * Math.pow(s, 0.8));
				width[k] = (float) (0.08 + 0.32 * s) * size * (0.4F + 0.6F * taper);
				alpha[k] = a * taper;
			}
		}

		/** Shock ring {@code i} at progress {@code t}, brightness {@code a}. */
		private void ring(int i, float t, float a) {
			ensure(RING_POINTS);
			double rad = (0.6 + (REACH + 0.8 * i) * (1 - Math.pow(1 - t, 2.4))) * size;
			Vec3 c = at.add(look.scale(-0.4 * i * t));
			for (int k = 0; k < RING_POINTS; k++) {
				double ang = Mth.TWO_PI * k / (RING_POINTS - 1);
				pts[k] = c.add(frame.side().scale(Math.cos(ang) * rad)).add(frame.up().scale(Math.sin(ang) * rad));
				float shade = 0.5F + 0.5F * (float) Math.cos(ang - turn - 3 * t);
				width[k] = (float) (0.12 + 0.25 * t) * size * (0.6F + 0.4F * shade);
				alpha[k] = a * (0.35F + 0.65F * shade);
			}
		}

		/** Vortex strand {@code i} at time {@code now}: a helix down the drill's cone, reaching its end over the first 6 ticks. */
		private void vortex(int i, float now, float fade) {
			ensure(VORTEX_POINTS);
			double reach = (drill - 1) * Mth.clamp(now / 6F, 0F, 1F);
			for (int k = 0; k < VORTEX_POINTS; k++) {
				double s = k / (double) (VORTEX_POINTS - 1);
				double along = reach * s;
				double rad = DrillGeometry.radiusAt(along, drill) * 0.7;
				double ang = turn + Mth.TWO_PI * i / VORTEX + along * 1.5 - now * 0.8;
				pts[k] = axis.add(look.scale(along + 0.2)).add(frame.side().scale(Math.cos(ang) * rad)).add(frame.up().scale(Math.sin(ang) * rad));
				float edge = (float) Math.min(1, Math.min(s, 1 - s) * 6);
				width[k] = (float) (0.1 + 0.08 * rad);
				alpha[k] = fade * edge * 0.9F;
			}
		}

		private void ensure(int n) {
			if (pts.length != n) {
				pts = new Vec3[n];
				width = new float[n];
				alpha = new float[n];
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
			return at.x;
		}

		@Override
		public double centreY() {
			return at.y;
		}

		@Override
		public double centreZ() {
			return at.z;
		}

		@Override
		public double reach() {
			return REACH * size + DrillGeometry.MAX_LENGTH + 2;
		}
	}
}
