package local.carter.office.client.magic.fx.wind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.client.magic.fx.LightPipeline;
import local.carter.office.client.magic.fx.MagicCircleRenderer;
import local.carter.office.client.magic.fx.SpellFx;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.ProjectileSpec;
import local.carter.office.magic.spell.TargetFilter;
import local.carter.office.magic.spell.wind.BladeWind;
import local.carter.office.magic.spell.wind.BladeWindGeometry;
import local.carter.office.magic.tree.SpellRank;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Blade Wind: a summoning circle snaps open and white crescents of wind fan out of it, flown along the same paths as the server's
 * blades ({@link BladeWindGeometry}, i.e. {@code Projectiles.step}) and ending where the server says each stops ({@code data}).
 * Each crescent is a bright white edge over a mint glow with two fading afterimages, tilted like a thrown slash. Foliage bursts
 * apart as a blade passes, foes hit flash with a sweep, and a blade that meets a wall breaks into a puff.
 */
public final class BladeWindFx implements SpellFx {
	private static final int WIND = Element.WIND.argb() & 0xFFFFFF;
	private static final int PALE = WindRibbon.lighter(WIND, 0.45F);
	/** Ticks a crescent takes to fade once it stops. */
	private static final int FADE = 4;

	private record Cut(double t, BlockPos pos, BlockState state) {}

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		float c = Mth.clamp(e.charge(), 0F, 1F);
		SpellRank rank = e.spellRank();
		List<ProjectileSpec> specs = BladeWindGeometry.specs(e.origin(), e.dir(), c, rank);
		int flight = BladeWindGeometry.maxTicks(rank);
		boolean home = BladeWindGeometry.returns(rank);
		float[] data = e.data();
		int n = specs.size();
		float[] stops = new float[n];
		float longest = 0;
		for (int i = 0; i < n; i++) {
			stops[i] = data.length > i + 1 ? Mth.clamp(data[i + 1], 0F, flight) : flight;
			longest = Math.max(longest, (float) BladeWindGeometry.life(stops[i], home));
		}
		Vec3 dir = BladeWindGeometry.frame(e.dir()).forward();
		Vec3 eye = e.origin();

		// The circle the blades leave from: in front, facing the caster.
		kit.circle(eye.add(dir.scale(2.0)).add(0, -0.1, 0), dir.reverse(), 0xFF000000 | WIND, 0.4F * (1F + c), 2 + Math.round(3 * c), 9);
		kit.motes(eye.add(dir.scale(1.6)), PALE, 10 + Math.round(8 * c), 0.35, dir.scale(0.12), 0.08, GlowMote.Drift.SWIRL, 0.11F, 10);
		level.playLocalSound(eye.x, eye.y, eye.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0F, 1.3F - 0.25F * c, false);
		level.playLocalSound(eye.x, eye.y, eye.z, SoundEvents.WIND_CHARGE_THROW, SoundSource.PLAYERS, 0.8F, 1.5F - 0.3F * c, false);
		kit.shake(0.12F + 0.15F * c, 5);
		kit.fovKick(0.03F + 0.03F * c);

		double[] angles = BladeWindGeometry.angles(c, rank);
		float size = 1.25F + 0.45F * c;
		for (int i = 0; i < n; i++) {
			// A spray of arcs: the outer blades tilt outward. Each pays the fx budget for the quads it draws a frame.
			float roll = (float) Math.toRadians(angles[i] * 1.2);
			Crescent blade = new Crescent(level, specs.get(i), stops[i], home, size, roll);
			if (kit.budget().particles(blade.cost()) < blade.cost()) break;
			Minecraft.getInstance().particleEngine.add(blade);
		}

		// Foliage on the paths as this client sees it now, before the server's cuts arrive: it bursts when a blade gets there.
		List<Cut> cuts = new ArrayList<>();
		for (int i = 0; i < n && cuts.size() < BladeWindGeometry.MAX_CUTS; i++) {
			ProjectileSpec s = specs.get(i);
			BladeWindGeometry.Frame f = BladeWindGeometry.frame(s.velocity());
			for (int t = 0; t < Mth.ceil(stops[i]) && cuts.size() < BladeWindGeometry.MAX_CUTS; t++) {
				Vec3 a = BladeWindGeometry.at(s, t);
				Vec3 b = BladeWindGeometry.at(s, Math.min(t + 1, stops[i]));
				for (BlockPos p : BladeWindGeometry.cuts(a, b, f)) {
					BlockState st = level.getBlockState(p);
					if (BladeWind.cuttable(st) && cuts.size() < BladeWindGeometry.MAX_CUTS) cuts.add(new Cut(t + 1, p.immutable(), st));
				}
			}
		}

		Set<Integer> struck = new HashSet<>();
		Set<Integer> struckBack = new HashSet<>();
		boolean[] ended = new boolean[n];
		kit.simulate(1, Mth.ceil(longest) + 2, t -> {
			boolean cutSound = false;
			for (int i = 0; i < n; i++) {
				ProjectileSpec s = specs.get(i);
				double end = BladeWindGeometry.life(stops[i], home);
				if (t >= 1 && t - 1 < end) {
					Vec3 a = BladeWindGeometry.path(s, t - 1, stops[i], home);
					Vec3 b = BladeWindGeometry.path(s, Math.min(t, end), stops[i], home);
					Vec3 back = a.subtract(b).scale(0.02);
					kit.motes(b, PALE, n > 3 ? 1 : 2, 0.3, back, 0.03, GlowMote.Drift.SWIRL, 0.12F, 8);
					if (t % 2 == 0) kit.trail(a, b, ParticleTypes.WHITE_ASH, 2);
					// Boomerang: the blade strikes again on its way home (the server keeps a second set for that leg).
					hits(level, kit, a, b, t - 1 >= stops[i] ? struckBack : struck, s);
				}
				if (!ended[i] && !home && t >= stops[i] && stops[i] < flight) {
					ended[i] = true;
					Vec3 p = BladeWindGeometry.at(s, stops[i]);
					kit.burst(p, ParticleTypes.POOF, 3, 0.06);
					kit.motes(p, PALE, 6, 0.2, Vec3.ZERO, 0.12, GlowMote.Drift.COAST, 0.1F, 7);
				}
			}
			for (Cut cut : cuts) {
				if (cut.t() != t) continue;
				Vec3 at = Vec3.atCenterOf(cut.pos());
				kit.burst(at, new BlockParticleOption(ParticleTypes.BLOCK, cut.state()), 4, 0.14);
				if (!cutSound) {
					cutSound = true;
					var sound = cut.state().getSoundType();
					level.playLocalSound(at.x, at.y, at.z, sound.getBreakSound(), SoundSource.BLOCKS, 0.7F, sound.getPitch() * 1.1F, false);
				}
			}
		});
	}

	/** A sweep flash on every foe this client sees the blade cross (the server does the damage). */
	private static void hits(ClientLevel level, FxKit kit, Vec3 a, Vec3 b, Set<Integer> struck, ProjectileSpec s) {
		double r = s.radius();
		for (LivingEntity le : level.getEntitiesOfClass(LivingEntity.class, new AABB(a, b).inflate(r), x -> TargetFilter.affects(null, x))) {
			AABB box = le.getBoundingBox().inflate(r);
			Optional<Vec3> p = box.contains(a) ? Optional.of(a) : box.clip(a, b);
			if (p.isEmpty() || !struck.add(le.getId())) continue;
			Vec3 at = le.getBoundingBox().getCenter();
			if (kit.budget().particles(1) > 0) level.addParticle(ParticleTypes.SWEEP_ATTACK, at.x, at.y, at.z, 0.2, 0, 0);
			kit.motes(at, 0xFFFFFF, 8, 0.25, s.velocity().scale(0.08), 0.15, GlowMote.Drift.COAST, 0.1F, 6);
			level.playLocalSound(at.x, at.y, at.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.6F, 1.7F, false);
		}
	}

	/**
	 * One blade: a crescent riding its path, across the flight like a thrown slash. Its edge is white-hot, an inner arc fills
	 * it with mint glow, and two afterimages trail it. It fades and widens once it stops.
	 */
	static final class Crescent extends SingleQuadParticle implements LightPipeline.Extent {
		private static final int POINTS = 17;
		private static final double SPAN = Math.toRadians(78);

		private final ProjectileSpec spec;
		private final float stop;
		/** Boomerang: it turns at {@code stop} and flies home, so it rests (and fades) only at {@code end}. */
		private final boolean home;
		private final float end;
		private final float size;
		private final Vec3 forward;
		private final Vec3 lateral;
		private final Vec3 upward;
		private final WindRibbon ribbon = new WindRibbon();
		private final Vec3[] pts = new Vec3[POINTS];
		private final float[] width = new float[POINTS];
		private final float[] alpha = new float[POINTS];

		Crescent(ClientLevel level, ProjectileSpec spec, float stop, boolean home, float size, float roll) {
			super(level, spec.origin().x, spec.origin().y, spec.origin().z, MagicCircleRenderer.sprite("line"));
			this.spec = spec;
			this.stop = stop;
			this.home = home;
			this.end = (float) BladeWindGeometry.life(stop, home);
			this.size = size;
			BladeWindGeometry.Frame f = BladeWindGeometry.frame(spec.velocity());
			this.forward = f.forward();
			this.lateral = f.side().scale(Math.cos(roll)).add(f.up().scale(Math.sin(roll)));
			this.upward = lateral.cross(forward);
			this.lifetime = Mth.ceil(end) + FADE + 1;
			this.hasPhysics = false;
			this.gravity = 0;
			this.xd = 0;
			this.yd = 0;
			this.zd = 0;
		}

		@Override
		public void tick() {
			age++;
			Vec3 p = BladeWindGeometry.path(spec, Math.min(age, end), stop, home);
			xo = x;
			yo = y;
			zo = z;
			x = p.x;
			y = p.y;
			z = p.z;
			if (age >= lifetime) {
				remove();
			}
		}

		@Override
		public void extract(QuadParticleRenderState state, Camera camera, float partial) {
			float now = age + partial;
			float past = Math.max(0, now - end);
			float fade = 1 - past / FADE;
			if (fade <= 0.02F) {
				return;
			}
			float a = Math.min(1F, 0.35F + now) * fade;
			float grow = 1 + 0.5F * past / FADE;
			double t = Math.min(now, end);
			Vec3 cam = camera.position();
			// Afterimages (the edge only) first, then the blade.
			if (t > 0.8) arc(state, cam, BladeWindGeometry.path(spec, t - 0.8, stop, home), a * 0.18F, size * 0.92F * grow, 0.9, false);
			if (t > 0.4) arc(state, cam, BladeWindGeometry.path(spec, t - 0.4, stop, home), a * 0.4F, size * 0.96F * grow, 0.95, false);
			arc(state, cam, BladeWindGeometry.path(spec, t, stop, home), a, size * grow, 1, true);

		}

		/** Fx-budget particles this blade costs: its quads a frame at their most (fully grown, both afterimages), per 50. */
		int cost() {
			Vec3 tip = spec.origin();
			float r = size * 1.5F;
			fill(tip, r, SPAN, 0.42F * r, 1);
			int edge = WindRibbon.pieces(pts, width);
			fill(tip, r * 0.82, SPAN * 0.8, 0.3F * r, 1);
			return WindRibbon.budgetCost(3 * edge + WindRibbon.pieces(pts, width));
		}

		/** The crescent with its peak at {@code tip}: the bright edge (white core) and, if {@code inner}, a softer arc under it. */
		private void arc(QuadParticleRenderState state, Vec3 cam, Vec3 tip, float a, float r, double spanScale, boolean inner) {
			fill(tip, r, SPAN * spanScale, 0.42F * r, a);
			ribbon.draw(state, cam, pts, width, alpha, PALE, 0.85F, 0xF4FFFA);
			if (!inner) {
				return;
			}
			fill(tip.add(upward.scale(-0.16 * r)).add(forward.scale(-0.08 * r)), r * 0.82, SPAN * 0.8 * spanScale, 0.3F * r, a * 0.5F);
			ribbon.draw(state, cam, pts, width, alpha, WIND, 0F, 0);
		}

		/**
		 * Points of a crescent through {@code tip}, across the flight: it bows up at the middle and sweeps its tips down and back,
		 * so from behind it reads as an arc flying away and from the side as a swept blade. Thick in the middle, sharp at the tips.
		 */
		private void fill(Vec3 tip, double r, double span, float thick, float a) {
			for (int k = 0; k < POINTS; k++) {
				double u = k / (double) (POINTS - 1) * 2 - 1;
				double th = u * span;
				double drop = r * (1 - Math.cos(th));
				pts[k] = tip.add(lateral.scale(r * Math.sin(th))).add(upward.scale(0.25 * r - 0.65 * drop)).add(forward.scale(-0.55 * drop));
				float taper = (float) Math.pow(Math.cos(u * Math.PI / 2), 0.8);
				width[k] = thick * (0.08F + 0.92F * taper);
				alpha[k] = a * (0.25F + 0.75F * taper);
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
			return 3 + size * 2;
		}
	}
}
