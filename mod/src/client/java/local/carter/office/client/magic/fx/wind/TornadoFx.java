package local.carter.office.client.magic.fx.wind;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.client.magic.fx.LightPipeline;
import local.carter.office.client.magic.fx.MagicCircleRenderer;
import local.carter.office.client.magic.fx.SpellFx;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.TargetFilter;
import local.carter.office.magic.spell.wind.Tornado;
import local.carter.office.magic.spell.wind.TornadoGeometry;
import local.carter.office.magic.tree.SpellRank;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;


/**
 * Tornado: a seal flashes on the ground where it starts, then a funnel of wind twists up out of it and wanders forward along the
 * same path as the server's whirlwind ({@link TornadoGeometry}, its base hugging the ground with {@link Tornado#follow}). The
 * funnel is streaks of glowing wind spiralling up round the axis, narrow at the ground and flaring at the top, its top swaying;
 * dust and grit from the ground under it whirl round its foot and motes are drawn up through it.
 *
 * <p>Budget: the opening is paid at once; the funnel then pays its quads ({@code 50} a budget particle) for each 10-tick step as it
 * goes, out of an even share of what is left, and draws faint and thin if a step is refused. Dust and motes come out of the same share.
 */
public final class TornadoFx implements SpellFx {
	private static final int WIND = Element.WIND.argb() & 0xFFFFFF;
	private static final int PALE = WindRibbon.lighter(WIND, 0.6F);
	static final int STEP = 10;
	/** Budget kept back for the end. */
	private static final int RESERVE = 10;

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		float c = Mth.clamp(e.charge(), 0F, 1F);
		int life = Math.max(1, Math.min(FxKit.MAX_SIMULATE_TICKS - Funnel.FADE, e.duration()));
		TornadoGeometry geo = new TornadoGeometry(e.target(), e.dir(), life);
		Vec3 start = geo.origin();

		kit.circle(start.add(0, 0.06, 0), new Vec3(0, 1, 0), 0xFF000000 | WIND, 1.3F * (1F + c), 3 + Math.round(2 * c), 14);
		level.playLocalSound(start.x, start.y, start.z, SoundEvents.WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 1.0F, 0.75F, false);
		level.playLocalSound(start.x, start.y, start.z, SoundEvents.BREEZE_INHALE, SoundSource.PLAYERS, 1.0F, 0.6F, false);
		kit.shake(0.15F, 6);
		AirStepFx.StepRing ring = new AirStepFx.StepRing(level, start.add(0, 0.15, 0), 3.5 * (1 + c), 10, 0, 0.22F);
		if (kit.budget().particles(ring.cost()) >= ring.cost()) Minecraft.getInstance().particleEngine.add(ring);
		kit.motes(start.add(0, 0.4, 0), PALE, 20, 0.8, new Vec3(0, 0.12, 0), 0.12, GlowMote.Drift.SWIRL, 0.12F, 14);

		SpellRank rank = e.spellRank();
		double widen = TornadoGeometry.radius(c, rank) / TornadoGeometry.RADIUS;
		boolean twin = rank.is(Tornado.TWIN);
		double debris = rank.is(Tornado.DEBRIS) ? TornadoGeometry.radius(c, rank) : -1;
		funnel(level, kit, geo, c, widen, e.seed(), life, twin ? 2 : 1, debris);
		// Twin Tornadoes: the second one leaves turned, as the server sends it.
		if (twin) funnel(level, kit, new TornadoGeometry(geo.origin(), Tornado.twinDir(geo.dir()), life), c, widen, e.seed() + 1, life, 2, -1);
	}

	/**
	 * One funnel along {@code geo}, with its dust and motes; it gets {@code 1 / of} of what the budget has left. With Debris
	 * ({@code debris} = the funnel's radius, else negative) a chunk streaks out every {@code Tornado.DEBRIS_EVERY} ticks to the
	 * nearest creature outside it, as the server throws it.
	 */
	private static void funnel(ClientLevel level, FxKit kit, TornadoGeometry geo, float c, double widen, long seed, int life, int of,
			double debris) {
		Funnel funnel = new Funnel(level, geo, c, widen, seed);
		Minecraft.getInstance().particleEngine.add(funnel);
		int steps = (life + STEP - 1) / STEP;
		int share = Math.max(0, (kit.budget().particlesLeft() - RESERVE) / steps / of);
		int funnelCost = funnel.cost();
		int[] left = {0};
		RandomSource r = kit.random();
		kit.simulate(1, life, t -> {
			funnel.keep();
			if (t % STEP == 0) {
				int paid = kit.budget().particles(Math.min(share, funnelCost));
				funnel.paid(paid >= funnelCost);
				left[0] = Math.max(0, share - paid);
			}
			Vec3 base = funnel.base();
			if (debris >= 0 && t > 0 && t % Tornado.DEBRIS_EVERY == 0) hurl(level, kit, base, debris);
			if (t % 20 == 5) {
				level.playLocalSound(base.x, base.y + 2, base.z, SoundEvents.BREEZE_WHIRL, SoundSource.PLAYERS, 0.9F, 0.55F + r.nextFloat() * 0.1F, false);
			}
			// Dust whirling round the foot, and motes drawn up the core: paid from what the step's share leaves.
			if (t % 2 == 0 && left[0] > 0) {
				ParticleOptions dust = dust(level, base);
				int n = Math.min(left[0], 2);
				for (int i = 0; i < n && dust != null; i++) {
					double a = r.nextDouble() * Mth.TWO_PI;
					double rad = (1.2 + r.nextDouble() * 2.4) * funnel.widen();
					Vec3 off = new Vec3(Math.sin(a) * rad, 0.1 + r.nextDouble() * 0.5, Math.cos(a) * rad);
					Vec3 tan = TornadoGeometry.tangent(off.x, off.z).scale(0.35);
					Vec3 p = base.add(off);
					level.addParticle(dust, p.x, p.y, p.z, tan.x - off.x * 0.04, 0.12 + r.nextDouble() * 0.1, tan.z - off.z * 0.04);
				}
				left[0] -= n;
			}
			if (t % 3 == 0 && left[0] > 0) {
				int n = Math.min(left[0], 2);
				kit.motes(base.add(0, 0.6, 0), t % 2 == 0 ? 0xFFFFFF : PALE, n, 0.6, new Vec3(0, 0.22, 0), 0.06, GlowMote.Drift.SWIRL, 0.13F, 22);
				left[0] -= n;
			}
		});
	}

	/** A chunk of debris from the funnel to the nearest creature outside it within reach (the server's pick, seen from here). */
	private static void hurl(ClientLevel level, FxKit kit, Vec3 base, double radius) {
		Vec3 from = base.add(0, 1.5, 0);
		double reach = radius + Tornado.DEBRIS_REACH;
		LivingEntity best = null;
		double bestD = Double.MAX_VALUE;
		for (LivingEntity l : level.getEntitiesOfClass(LivingEntity.class, new AABB(from, from).inflate(reach),
				l -> TargetFilter.affects(null, l))) {
			Vec3 m = l.getBoundingBox().getCenter();
			double dx = m.x - base.x, dz = m.z - base.z;
			double d = m.distanceToSqr(from);
			if (dx * dx + dz * dz <= radius * radius || d > reach * reach || d >= bestD) continue;
			if (level.clip(new ClipContext(from, m, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty())).getType()
					!= HitResult.Type.MISS) continue;
			best = l;
			bestD = d;
		}
		if (best == null) return;
		Vec3 to = best.getBoundingBox().getCenter();
		ParticleOptions grit = dust(level, base);
		if (grit != null) kit.trail(from, to, grit, 8);
		kit.motes(to, PALE, 6, 0.3, Vec3.ZERO, 0.1, GlowMote.Drift.FALL, 0.12F, 8);
		level.playLocalSound(to.x, to.y, to.z, SoundEvents.STONE_HIT, SoundSource.PLAYERS, 0.8F, 0.8F, false);
	}

	/** What the ground under {@code base} throws up: grit of its block, spray off water, or nothing over air. */

	private static ParticleOptions dust(ClientLevel level, Vec3 base) {
		BlockPos under = BlockPos.containing(base.x, base.y - 0.5, base.z);
		BlockState s = level.getBlockState(under);
		if (!s.getFluidState().isEmpty()) return ParticleTypes.SPLASH;
		if (s.isAir() || s.getRenderShape() == RenderShape.INVISIBLE) return null;
		return new BlockParticleOption(ParticleTypes.BLOCK, s);
	}

	/**
	 * The funnel, three layers spinning anticlockwise from above (as the server's wind turns), radius {@link #R0} at the ground
	 * flaring to {@link #R_TOP} at {@link TornadoGeometry#HEIGHT}, the upper part swaying gently:
	 * <ul>
	 * <li>soft streaks of wind ({@code strands}), each a rising stretch of a helix round the axis, thin at the foot and broad
	 * at the top;</li>
	 * <li>{@link #BASE_STRANDS} thin crisp streaks whirling tight round the foot, so the funnel is densest where it touches down;</li>
	 * <li>a faint shell of {@link #RINGS} stacked rings along the cone, drifting up, so it reads as a funnel at any light (noon
	 * included).</li>
	 * </ul>
	 * It lives while its fx timer keeps it ({@link #keep}), then fades out.
	 */
	static final class Funnel extends SingleQuadParticle implements LightPipeline.Extent {
		static final int FADE = 12;
		private static final int POINTS = 24;
		private static final int RING_POINTS = 33;
		private static final double R0 = 0.45;
		private static final double R_TOP = 3.7;
		/** Height a streak covers. */
		private static final double LENGTH = 6.5;
		/** Radians a streak winds per block of height, and the spin in radians a tick. */
		private static final double TWIST = 0.55;
		private static final double SPIN = 0.42;
		private static final double RISE = 0.22;
		/** Glow blobs spaced this many times wider than a near stroke: the funnel is big and seen from afar. */
		private static final float COARSE = 4.5F;
		/** The thin streaks at the foot: how many, how high they reach, how long each is. */
		private static final int BASE_STRANDS = 6;
		private static final double BASE_TOP = 3.2;
		private static final double BASE_LENGTH = 2.2;
		private static final float BASE_COARSE = 2.5F;
		/** Shell rings stacked along the cone. */
		private static final int RINGS = 8;

		private final TornadoGeometry geo;
		/** How much wider than a tap's the funnel is: the server's reach, {@link TornadoGeometry#radius}, over a tap's. */
		private final double widen;
		private final int strands;
		private final double[] phase;
		private final double[] offset;
		private final int[] colour;
		private final float[] thick;
		private final double sway;
		private final WindRibbon ribbon = new WindRibbon();
		private final Vec3[] pts = new Vec3[POINTS];
		private final float[] width = new float[POINTS];
		private final float[] alpha = new float[POINTS];
		private final Vec3[] ringPts = new Vec3[RING_POINTS];
		private final float[] ringWidth = new float[RING_POINTS];
		private final float[] ringAlpha = new float[RING_POINTS];
		private Vec3 base;
		private Vec3 prevBase;
		private int kept;
		private int ended = -1;
		private boolean paid = true;

		Funnel(ClientLevel level, TornadoGeometry geo, float charge, double widen, long seed) {
			super(level, geo.origin().x, geo.origin().y, geo.origin().z, MagicCircleRenderer.sprite("line"));
			this.geo = geo;
			this.widen = widen;
			this.base = geo.origin();
			this.prevBase = base;
			this.strands = BASE_STRANDS + 14 + Math.round(4 * charge);
			RandomSource r = RandomSource.create(seed ^ 0x7A11L);
			phase = new double[strands];
			offset = new double[strands];
			colour = new int[strands];
			thick = new float[strands];
			for (int i = 0; i < strands; i++) {
				phase[i] = Mth.TWO_PI * (i + 0.4 * r.nextDouble()) / (i < BASE_STRANDS ? BASE_STRANDS : strands - BASE_STRANDS);
				offset[i] = r.nextDouble() * (i < BASE_STRANDS ? BASE_TOP + BASE_LENGTH : TornadoGeometry.HEIGHT + LENGTH);
				colour[i] = switch (i % 3) {
					case 0 -> 0xFFFFFF;
					case 1 -> PALE;
					default -> 0xB8C8C0;
				};
				thick[i] = i < BASE_STRANDS ? 0.55F + 0.2F * r.nextFloat() : 0.8F + 0.6F * r.nextFloat();
			}
			this.sway = r.nextDouble() * Mth.TWO_PI;
			this.lifetime = FxKit.MAX_SIMULATE_TICKS + FADE;
			this.hasPhysics = false;
			this.gravity = 0;
			this.xd = 0;
			this.yd = 0;
			this.zd = 0;
		}

		/** The fx timer is still running. */
		void keep() {
			kept = age;
		}

		/** Whether this step's quads were paid for. */
		void paid(boolean p) {
			paid = p;
		}

		Vec3 base() {
			return base;
		}

		double widen() {
			return widen;
		}

		@Override
		public void tick() {
			age++;
			prevBase = base;
			if (ended < 0 && (age > geo.life() || age - kept > 2)) ended = age;
			if (ended >= 0 && age - ended >= FADE) {
				remove();
				return;
			}
			Vec3 flat = geo.at(Math.min(age, geo.life()));
			base = new Vec3(flat.x, Tornado.follow(level, flat.x, flat.z, base.y), flat.z);
			x = base.x;
			y = base.y + TornadoGeometry.HEIGHT / 2;
			z = base.z;
		}

		/** Fx-budget particles the funnel costs per step: its quads a frame at their most (every streak at full length), per 50. */
		int cost() {
			int quads = 0;
			for (int i = 0; i < strands; i++) {
				lay(i, Vec3.ZERO, 20, TornadoGeometry.HEIGHT, 1, 1);
				quads += i < BASE_STRANDS ? WindRibbon.pieces(pts, width, BASE_COARSE, true) : WindRibbon.pieces(pts, width, COARSE, false);
			}
			for (int j = 0; j < RINGS; j++) {
				ring(j, Vec3.ZERO, 0, TornadoGeometry.HEIGHT, 1);
				quads += WindRibbon.pieces(ringPts, ringWidth, COARSE, false);
			}
			return WindRibbon.budgetCost(quads);
		}

		@Override
		public void extract(QuadParticleRenderState state, Camera camera, float partial) {
			float now = age + partial;
			float grow = Mth.clamp(now / 12F, 0F, 1F);
			float fade = ended < 0 ? 1F : Mth.clamp(1F - (now - ended) / FADE, 0F, 1F);
			if (fade <= 0.01F) return;
			Vec3 at = prevBase.lerp(base, partial);
			Vec3 cam = camera.position();
			float bright = (paid ? 1F : 0.3F) * fade * grow;
			int every = paid ? 1 : 3;
			double top = TornadoGeometry.HEIGHT * (0.25 + 0.75 * grow);
			for (int j = 0; j < RINGS; j += every) {
				ring(j, at, now, top, bright);
				ribbon.draw(state, cam, ringPts, ringWidth, ringAlpha, PALE, -1F, 0, COARSE);
			}
			for (int i = 0; i < strands; i += every) {
				lay(i, at, now, top, bright, paid ? 1F : 0.6F);
				if (i < BASE_STRANDS) {
					ribbon.draw(state, cam, pts, width, alpha, PALE, 0.7F, 0xFFFFFF, BASE_COARSE);
				} else {
					ribbon.draw(state, cam, pts, width, alpha, colour[i], -1F, 0, COARSE);
				}
			}
		}

		/** The funnel's radius at height {@code h} above its base (wider, by {@link #widen}, for a charged cast). */
		private double radius(double h) {
			return (R0 + (R_TOP - R0) * Math.pow(Mth.clamp(h / TornadoGeometry.HEIGHT, 0, 1), 1.4)) * widen;
		}

		/** The axis's sway at height {@code h} (none at the foot, most at the top). */
		private Vec3 sway(double h, float now) {
			double f = Mth.clamp(h / TornadoGeometry.HEIGHT, 0, 1);
			double sw = f * f;
			return new Vec3(Math.sin(now * 0.045 + sway) * 0.3 * sw, 0, Math.cos(now * 0.037 + sway * 1.7) * 0.25 * sw);
		}

		/** Strand {@code i} at time {@code now} into the scratch arrays: a rising stretch of helix under {@code top}. */
		private void lay(int i, Vec3 at, float now, double top, float bright, float slim) {
			boolean low = i < BASE_STRANDS;
			double length = low ? BASE_LENGTH : LENGTH;
			double cap = low ? Math.min(top, BASE_TOP) : top;
			double cycle = (low ? BASE_TOP : TornadoGeometry.HEIGHT) + length;
			double head = (offset[i] + now * (low ? RISE * 1.5 : RISE)) % cycle;
			double tail = head - length;
			double spin = phase[i] + now * (low ? SPIN * 1.6 : SPIN);
			for (int k = 0; k < POINTS; k++) {
				double s = k / (double) (POINTS - 1);
				double h = Mth.clamp(tail + length * s, 0, cap);
				double f = h / TornadoGeometry.HEIGHT;
				double rad = radius(h) * (low ? 0.8 : 1);
				double a = spin + TWIST * h;
				pts[k] = at.add(sway(h, now)).add(Math.sin(a) * rad, h, Math.cos(a) * rad);
				float edge = (float) Math.min(1, Math.min(s, 1 - s) * 4);
				boolean inside = tail + length * s >= 0 && tail + length * s <= cap;
				width[k] = slim * thick[i] * (float) (low ? 0.07 + 0.08 * f : 0.08 + 0.5 * f);
				alpha[k] = inside ? bright * edge * (float) (1.0 - 0.3 * f) : 0F;
			}
		}

		/** Shell ring {@code j} into the ring arrays: a loop round the cone at its height, drifting up, brighter on its turning side. */
		private void ring(int j, Vec3 at, float now, double top, float bright) {
			double spacing = TornadoGeometry.HEIGHT / RINGS;
			double h = (0.4 + j * spacing + now * 0.04) % TornadoGeometry.HEIGHT;
			double rad = radius(h) * 1.06;
			Vec3 c = at.add(sway(h, now)).add(0, h, 0);
			// Rings fade in at the foot and out at the top, so the drift never pops.
			float life = (float) Math.min(1, Math.min(h / 1.2, (TornadoGeometry.HEIGHT - h) / 1.5));
			float a = h > top ? 0F : bright * 0.4F * Math.max(0F, life);
			for (int k = 0; k < RING_POINTS; k++) {
				double ang = Mth.TWO_PI * k / (RING_POINTS - 1);
				ringPts[k] = c.add(Math.sin(ang) * rad, 0, Math.cos(ang) * rad);
				float shade = 0.4F + 0.6F * (float) Math.max(0, Math.sin(ang - now * SPIN));
				ringWidth[k] = (float) (0.1 + 0.05 * rad) * (0.7F + 0.3F * shade);
				ringAlpha[k] = a * shade;
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
			return TornadoGeometry.HEIGHT / 2 + R_TOP * widen + 2;
		}
	}
}
