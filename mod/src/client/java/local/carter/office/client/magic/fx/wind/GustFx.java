package local.carter.office.client.magic.fx.wind;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.client.magic.fx.LightPipeline;
import local.carter.office.client.magic.fx.MagicCircleRenderer;
import local.carter.office.client.magic.fx.SpellFx;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.wind.BladeWindGeometry;
import local.carter.office.magic.spell.wind.Gust;

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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Gust: a small circle bursts open at the hand and a cone of wind tears out of it — curling white wind lines spiralling outward
 * to the cone's reach, shock rings rolling along it, and a puff on everything it threw.
 */
public final class GustFx implements SpellFx {
	private static final int WIND = Element.WIND.argb() & 0xFFFFFF;
	private static final int PALE = WindRibbon.lighter(WIND, 0.55F);
	private static final double CONE = Math.toRadians(27);

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		float c = Mth.clamp(e.charge(), 0F, 1F);
		float[] data = e.data();
		double range = data.length > 0 && data[0] > 0 ? data[0] : Gust.range(c);
		BladeWindGeometry.Frame f = BladeWindGeometry.frame(e.dir());
		Vec3 dir = f.forward();
		Vec3 hand = e.origin().add(dir.scale(1.7)).add(0, -0.15, 0);

		kit.circle(hand, dir.reverse(), 0xFF000000 | WIND, 0.35F * (1F + c), 2 + Math.round(2 * c), 7);
		level.playLocalSound(hand.x, hand.y, hand.z, SoundEvents.WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 0.9F,
				1.15F - 0.25F * c + level.getRandom().nextFloat() * 0.1F, false);
		level.playLocalSound(hand.x, hand.y, hand.z, SoundEvents.BREEZE_SHOOT, SoundSource.PLAYERS, 0.5F, 1.3F, false);
		kit.fovKick(0.05F + 0.05F * c);
		kit.shake(0.12F + 0.18F * c, 5);

		// Curling wind lines out along the cone.
		RandomSource r = kit.random();
		int lines = 16 + Math.round(10 * c);
		for (int i = 0; i < lines; i++) {
			double phase = Mth.TWO_PI * (i + r.nextDouble() * 0.6) / lines;
			double spread = Math.tan(CONE) * (0.45 + 0.55 * r.nextDouble());
			double twist = (i % 2 == 0 ? 1 : -1) * (0.9 + 0.8 * r.nextDouble());
			double reach = range * (0.75 + 0.25 * r.nextDouble());
			int life = 8 + r.nextInt(4);
			Streak line = new Streak(level, hand, f, phase, spread, twist, reach, life, 0.16F + 0.08F * c, i % 3 == 0 ? 0xFFFFFF : PALE);
			if (kit.budget().particles(line.cost()) < line.cost()) break;
			Minecraft.getInstance().particleEngine.add(line);
		}

		// Shock rings rolling out along the cone, and motes thrown with them.
		for (int k = 0; k < 2; k++) {
			Ring ring = new Ring(level, hand, f, range * (k == 0 ? 0.85 : 0.6), 7 + 2 * k, k, 0.2F + 0.08F * c);
			if (kit.budget().particles(ring.cost()) < ring.cost()) break;
			Minecraft.getInstance().particleEngine.add(ring);
		}
		kit.motes(hand, PALE, 18 + Math.round(10 * c), 0.3, dir.scale(range / 8), range / 14, GlowMote.Drift.SWIRL, 0.12F, 9);

		for (int id : e.entities()) {
			Entity hit = level.getEntity(id);
			if (hit == null) continue;
			Vec3 at = hit.getBoundingBox().getCenter();
			if (kit.budget().particles(1) > 0) level.addParticle(ParticleTypes.SMALL_GUST, at.x, at.y, at.z, 0, 0, 0);
			kit.motes(at, 0xFFFFFF, 8, 0.3, dir.scale(0.25), 0.12, GlowMote.Drift.SWIRL, 0.12F, 9);
		}
	}

	/**
	 * One wind line: a stroke on a widening spiral round the cone's axis. Its head races out (fast, then easing) and its tail
	 * follows, so it reads as a curl of wind tearing past; it thins and fades as it goes.
	 */
	static final class Streak extends SingleQuadParticle implements LightPipeline.Extent {
		private static final int POINTS = 9;
		/** How much of the path the line covers. */
		private static final double LENGTH = 0.4;

		private final Vec3 start;
		private final BladeWindGeometry.Frame frame;
		private final double phase;
		private final double spread;
		private final double twist;
		private final double reach;
		private final float thick;
		private final int rgb;
		private final WindRibbon ribbon = new WindRibbon();
		private final Vec3[] pts = new Vec3[POINTS];
		private final float[] width = new float[POINTS];
		private final float[] alpha = new float[POINTS];

		Streak(ClientLevel level, Vec3 start, BladeWindGeometry.Frame frame, double phase, double spread, double twist, double reach, int life,
				float thick, int rgb) {
			super(level, start.x, start.y, start.z, MagicCircleRenderer.sprite("line"));
			this.start = start;
			this.frame = frame;
			this.phase = phase;
			this.spread = spread;
			this.twist = twist;
			this.reach = reach;
			this.thick = thick;
			this.rgb = rgb;
			this.lifetime = life;
			this.hasPhysics = false;
			this.gravity = 0;
			this.xd = 0;
			this.yd = 0;
			this.zd = 0;
			Vec3 mid = point(0.5);
			x = mid.x;
			y = mid.y;
			z = mid.z;
		}

		/** The spiral at {@code u} (0 at the hand, 1 at full reach). */
		private Vec3 point(double u) {
			double d = reach * u;
			double rad = 0.2 + d * spread;
			double a = phase + twist * u * Math.PI;
			return start.add(frame.forward().scale(d)).add(frame.side().scale(rad * Math.cos(a))).add(frame.up().scale(rad * Math.sin(a)));
		}

		@Override
		public void tick() {
			age++;
			if (age >= lifetime) {
				remove();
			}
		}

		/** Fx-budget particles this line costs: its quads a frame at their most (the full length at the reach), per 50. */
		int cost() {
			lay(1 - LENGTH, 1, 1);
			return WindRibbon.budgetCost(WindRibbon.pieces(pts, width));
		}

		/** Points from {@code tail} to {@code head} along the spiral, thickening toward the head, at brightness {@code a}. */
		private void lay(double tail, double head, float a) {
			for (int k = 0; k < POINTS; k++) {
				double u = tail + (head - tail) * k / (POINTS - 1);
				pts[k] = point(u);
				float s = k / (float) (POINTS - 1);
				width[k] = thick * (0.25F + 0.75F * s) * (1F + 0.8F * (float) u);
				alpha[k] = a * s * s;
			}
		}

		@Override
		public void extract(QuadParticleRenderState state, Camera camera, float partial) {
			float t = Math.min(1F, (age + partial) / lifetime);
			// The head eases out to the reach; the tail lags behind it, catching up at the end.
			double head = 1 - Math.pow(1 - t, 2.2);
			double tail = Math.max(0, head - LENGTH * (1 - 0.6 * t));
			float a = (1 - t * t) * Math.min(1F, (age + partial) * 0.8F);
			if (a <= 0.02F || head - tail < 1.0E-3) {
				return;
			}
			lay(tail, head, a);
			ribbon.draw(state, camera.position(), pts, width, alpha, rgb, 0.6F, 0xFFFFFF);
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
			return reach / 2 + reach * spread + 1;
		}
	}

	/** A shock ring: a loop of wind across the cone that rolls out with the blast, widening to the cone's edge and thinning out. */
	static final class Ring extends SingleQuadParticle implements LightPipeline.Extent {
		private static final int POINTS = 49;

		private final Vec3 start;
		private final BladeWindGeometry.Frame frame;
		private final double reach;
		private final int delay;
		private final float thick;
		private final WindRibbon ribbon = new WindRibbon();
		private final Vec3[] pts = new Vec3[POINTS];
		private final float[] width = new float[POINTS];
		private final float[] alpha = new float[POINTS];

		Ring(ClientLevel level, Vec3 start, BladeWindGeometry.Frame frame, double reach, int life, int delay, float thick) {
			super(level, start.x, start.y, start.z, MagicCircleRenderer.sprite("line"));
			this.start = start;
			this.frame = frame;
			this.reach = reach;
			this.delay = delay;
			this.thick = thick;
			this.lifetime = life + delay;
			this.hasPhysics = false;
			this.gravity = 0;
			this.xd = 0;
			this.yd = 0;
			this.zd = 0;
			Vec3 mid = start.add(frame.forward().scale(reach / 2));
			x = mid.x;
			y = mid.y;
			z = mid.z;
		}

		@Override
		public void tick() {
			age++;
			if (age >= lifetime) {
				remove();
			}
		}

		/** Fx-budget particles this ring costs: its quads a frame at their most (fully widened), per 50. */
		int cost() {
			lay(1, 1);
			return WindRibbon.budgetCost(WindRibbon.pieces(pts, width));
		}

		@Override
		public void extract(QuadParticleRenderState state, Camera camera, float partial) {
			float t = (age + partial - delay) / (lifetime - delay);
			if (t <= 0 || t >= 1) {
				return;
			}
			lay(1 - Math.pow(1 - t, 2.4), (1 - t) * (1 - t) * Math.min(1F, t * 8));
			ribbon.draw(state, camera.position(), pts, width, alpha, PALE, 0.5F, 0xFFFFFF);
		}

		/** The loop {@code u} of the way out (0..1), at brightness {@code a}. */
		private void lay(double u, float a) {
			double d = reach * u;
			double rad = 0.3 + d * Math.tan(CONE);
			Vec3 c = start.add(frame.forward().scale(d));
			for (int k = 0; k < POINTS; k++) {
				double ang = Mth.TWO_PI * k / (POINTS - 1);
				pts[k] = c.add(frame.side().scale(rad * Math.cos(ang))).add(frame.up().scale(rad * Math.sin(ang)));
				// Brighter on one side: it reads as turning, not as a flat hoop.
				float shade = 0.55F + 0.45F * (float) Math.cos(ang - 2.2 * u);
				width[k] = thick * (1 + (float) u) * (0.6F + 0.4F * shade);
				alpha[k] = a * shade;
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
			return reach / 2 + reach * Math.tan(CONE) + 1;
		}
	}
}
