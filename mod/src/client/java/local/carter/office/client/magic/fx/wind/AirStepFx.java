package local.carter.office.client.magic.fx.wind;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.client.magic.fx.LightPipeline;
import local.carter.office.client.magic.fx.MagicCircleRenderer;
import local.carter.office.client.magic.fx.SpellFx;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.wind.AirStep;
import local.carter.office.magic.spell.wind.BladeWindGeometry;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Air Step: the caster stamps on the air. A small flat seal flashes under their feet, two rings of wind burst out level from
 * it, wind lines jet down and out like a kick-off, and a short spiral of motes trails the caster as they rise.
 */
public final class AirStepFx implements SpellFx {
	private static final int WIND = Element.WIND.argb() & 0xFFFFFF;
	private static final int PALE = WindRibbon.lighter(WIND, 0.55F);
	/** Ticks the motes trail the caster. */
	private static final int TRAIL = 10;

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		float c = Mth.clamp(e.charge(), 0F, 1F);
		Vec3 feet = e.target();
		Vec3 h = AirStep.lookH(e.dir(), 0F);

		kit.circle(feet.add(0, 0.05, 0), new Vec3(0, 1, 0), 0xFF000000 | WIND, 0.9F + 0.3F * c, 3 + Math.round(2 * c), 10);
		level.playLocalSound(feet.x, feet.y, feet.z, SoundEvents.WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 0.7F,
				1.45F - 0.2F * c + level.getRandom().nextFloat() * 0.1F, false);
		level.playLocalSound(feet.x, feet.y, feet.z, SoundEvents.BREEZE_JUMP, SoundSource.PLAYERS, 0.8F, 1.2F, false);
		kit.fovKick(0.04F + 0.03F * c);

		// Two level rings bursting out from the feet, the second a little later and wider.
		for (int k = 0; k < 2; k++) {
			StepRing ring = new StepRing(level, feet.add(0, 0.1 + 0.2 * k, 0), 2.0 + 0.8 * c + 0.8 * k, 8 + 2 * k, k * 2, 0.3F + 0.08F * c);
			if (kit.budget().particles(ring.cost()) < ring.cost()) break;
			Minecraft.getInstance().particleEngine.add(ring);
		}

		// Wind lines: half jet down and out under the feet (the kick; seen best mid-air), half spiral up round the body (the lift).
		BladeWindGeometry.Frame down = BladeWindGeometry.frame(new Vec3(h.x * 0.25, -1, h.z * 0.25));
		BladeWindGeometry.Frame up = BladeWindGeometry.frame(new Vec3(h.x * 0.15, 1, h.z * 0.15));
		var r = kit.random();
		int lines = 10 + Math.round(4 * c);
		for (int i = 0; i < lines; i++) {
			double phase = Mth.TWO_PI * (i + r.nextDouble() * 0.5) / lines;
			boolean lift = i % 2 == 0;
			GustFx.Streak s = lift
					? new GustFx.Streak(level, feet.add(0, 0.1, 0), up, phase, 0.12 + 0.08 * r.nextDouble(), 1.6 + 0.6 * r.nextDouble(),
							2.4 + 0.8 * r.nextDouble(), 8 + r.nextInt(3), 0.12F + 0.04F * c, i % 4 == 0 ? 0xFFFFFF : PALE)
					: new GustFx.Streak(level, feet.add(0, 0.3, 0), down, phase, 1.1 + 0.5 * r.nextDouble(), -0.7,
							2.0 + 0.8 * r.nextDouble(), 7 + r.nextInt(3), 0.17F + 0.05F * c, i % 3 == 0 ? 0xFFFFFF : PALE);
			if (kit.budget().particles(s.cost()) < s.cost()) break;
			Minecraft.getInstance().particleEngine.add(s);
		}
		kit.motes(feet.add(0, 0.15, 0), PALE, 16 + Math.round(8 * c), 0.45, new Vec3(0, -0.04, 0), 0.14, GlowMote.Drift.COAST, 0.11F, 9);

		// A spiral of motes trailing the caster as they rise.
		kit.simulate(1, TRAIL, t -> {
			Entity caster = level.getEntity(e.casterId());
			if (caster == null) return;
			double a = t * 1.3;
			Vec3 at = caster.position().add(Math.cos(a) * 0.45, 0.1, Math.sin(a) * 0.45);
			kit.motes(at, t % 2 == 0 ? 0xFFFFFF : PALE, 2, 0.08, new Vec3(0, -0.05, 0), 0.02, GlowMote.Drift.SWIRL, 0.1F, 10);
		});
	}

	/** A level ring of wind that bursts out from the feet, widening and thinning, brighter on one side so it reads as turning. */
	static final class StepRing extends SingleQuadParticle implements LightPipeline.Extent {
		private static final int POINTS = 41;

		private final Vec3 centre;
		private final double reach;
		private final int delay;
		private final float thick;
		private final WindRibbon ribbon = new WindRibbon();
		private final Vec3[] pts = new Vec3[POINTS];
		private final float[] width = new float[POINTS];
		private final float[] alpha = new float[POINTS];

		StepRing(ClientLevel level, Vec3 centre, double reach, int life, int delay, float thick) {
			super(level, centre.x, centre.y, centre.z, MagicCircleRenderer.sprite("line"));
			this.centre = centre;
			this.reach = reach;
			this.delay = delay;
			this.thick = thick;
			this.lifetime = life + delay;
			this.hasPhysics = false;
			this.gravity = 0;
			this.xd = 0;
			this.yd = 0;
			this.zd = 0;
		}

		@Override
		public void tick() {
			age++;
			if (age >= lifetime) remove();
		}

		/** Fx-budget particles this ring costs: its quads a frame at their most (fully widened), per 50. */
		int cost() {
			lay(1, 1);
			return WindRibbon.budgetCost(WindRibbon.pieces(pts, width));
		}

		@Override
		public void extract(QuadParticleRenderState state, Camera camera, float partial) {
			float t = (age + partial - delay) / (lifetime - delay);
			if (t <= 0 || t >= 1) return;
			lay(1 - Math.pow(1 - t, 2.6), (float) Math.pow(1 - t, 1.3) * Math.min(1F, t * 6));
			ribbon.draw(state, camera.position(), pts, width, alpha, PALE, 0.55F, 0xFFFFFF);
		}

		private void lay(double u, float a) {
			double rad = 0.25 + reach * u;
			for (int k = 0; k < POINTS; k++) {
				double ang = Mth.TWO_PI * k / (POINTS - 1);
				pts[k] = centre.add(rad * Math.cos(ang), -0.12 * u, rad * Math.sin(ang));
				float shade = 0.5F + 0.5F * (float) Math.cos(ang - 3.0 * u);
				width[k] = thick * (1 + 0.6F * (float) u) * (0.55F + 0.45F * shade);
				alpha[k] = a * (0.35F + 0.65F * shade);
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
			return centre.x;
		}

		@Override
		public double centreY() {
			return centre.y;
		}

		@Override
		public double centreZ() {
			return centre.z;
		}

		@Override
		public double reach() {
			return reach + 1;
		}
	}
}
