package local.carter.office.client.magic.fx.shadow;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.client.magic.fx.LightPipeline;
import local.carter.office.client.magic.fx.MagicCircleRenderer;
import local.carter.office.client.magic.fx.SpellFx;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.TargetFilter;
import local.carter.office.magic.spell.shadow.WellGeometry;

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
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Gravity Well: a circle opens before the caster and a streak of portal sparks flies to the spot; there a sigil opens on the
 * ground and a black core tears open above it: a hole of darkness with a violet event horizon, a tilted accretion disc of violet
 * light and black smoke turning round it, and a shadow that dims the light all round. Motes and black wisps spiral in from the
 * edge of its reach on {@link WellGeometry#spiral} (the same law that drags the mobs), portal sparks converge on it, grit is torn
 * off the ground, and everything being dragged sheds wisps toward the core. At the end of its life it collapses into a burst.
 */
public final class GravityWellFx implements SpellFx {
	/** Particles kept back for the collapse. */
	private static final int RESERVE = 70;
	/** Ticks the core takes to collapse after the well's life. */
	static final int COLLAPSE = 6;

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		Vec3 centre = e.target();
		Vec3 dir = ShadowFx.unit(e.dir());
		float c = Mth.clamp(e.charge(), 0F, 1F);
		int life = Math.max(1, e.duration());
		float[] data = e.data();
		double reach = data.length > 0 && data[0] > 0 ? data[0] : WellGeometry.RADIUS;
		RandomSource r = kit.random();

		// The cast: a circle before the caster and a streak of sparks to the spot.
		Vec3 muzzle = e.origin().add(dir.scale(1.7)).add(0, -0.15, 0);
		kit.circle(muzzle, dir.reverse(), ShadowFx.ARGB, 0.45F + 0.25F * c, 2 + Math.round(2 * c), 10);
		kit.trail(muzzle.add(dir.scale(1.0)), centre, ParticleTypes.REVERSE_PORTAL, 12);
		level.playLocalSound(muzzle.x, muzzle.y, muzzle.z, SoundEvents.ILLUSIONER_CAST_SPELL, SoundSource.PLAYERS, 0.5F, 0.55F, false);

		// The well: a sigil on the ground under it for its whole life, and the core.
		Vec3 floor = new Vec3(centre.x, centre.y - WellGeometry.HEIGHT + 0.06, centre.z);
		kit.circle(floor, new Vec3(0, 1, 0), ShadowFx.ARGB, 1.7F + 0.5F * c, 3 + Math.round(2 * c), life);
		float core = 0.7F + 0.2F * c;
		Core well = new Core(level, centre, core, life, r.nextFloat() * Mth.TWO_PI);
		if (kit.budget().particles(Core.cost()) >= Core.cost()) Minecraft.getInstance().particleEngine.add(well);
		kit.burst(centre, ParticleTypes.REVERSE_PORTAL, 6, 0.2);
		level.playLocalSound(centre.x, centre.y, centre.z, SoundEvents.PORTAL_TRIGGER, SoundSource.PLAYERS, 0.35F, 1.5F, false);
		level.playLocalSound(centre.x, centre.y, centre.z, SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.PLAYERS, 0.45F, 1.35F, false);

		// Grit torn off the ground: dirt from grass (grass block particles are green squares).
		BlockState under = level.getBlockState(BlockPos.containing(floor.x, floor.y - 0.5, floor.z));
		BlockState ground = under.is(Blocks.GRASS_BLOCK) || under.is(Blocks.PODZOL) || under.is(Blocks.MYCELIUM) ? Blocks.DIRT.defaultBlockState() : under;
		kit.simulate(1, life + COLLAPSE + 2, t -> {
			if (t < life) {
				// What it can spend this tick, so the motes last the whole life and the collapse keeps its share.
				int allow = Math.max(0, (kit.budget().particlesLeft() - RESERVE) / Math.max(1, life - t));
				int infall = Math.min(allow, 2 + (t % 2 == 0 && allow > 3 ? 1 : 0));
				for (int i = 0; i < infall; i++) {
					double r0 = 2.2 + (reach - 2.2) * r.nextDouble();
					boolean dark = r.nextInt(3) == 0;
					Infall mote = new Infall(level, centre, r0, r.nextDouble() * Mth.TWO_PI, -0.6 + 2.4 * r.nextDouble(), dark,
							dark ? 0.08F + 0.04F * r.nextFloat() : 0.07F + 0.05F * r.nextFloat(),
							dark ? 0 : r.nextInt(4) == 0 ? ShadowFx.PALE : r.nextBoolean() ? ShadowFx.VIOLET : ShadowFx.MAGENTA, life + COLLAPSE - t);
					if (kit.budget().particles(1) == 1) Minecraft.getInstance().particleEngine.add(mote);
				}
				allow -= infall;
				// Portal sparks converging on the core (a portal particle flies from its offset back to where it was put).
				if (allow > 0 && t % 3 == 0) {
					kit.burst(centre, ParticleTypes.PORTAL, 1, 3.0);
					allow--;
				}
				// Grit torn off the ground.
				if (allow > 0 && t % 3 == 0 && !ground.isAir() && kit.budget().particles(1) == 1) {
					double a = r.nextDouble() * Mth.TWO_PI;
					double d = 1.5 + 3 * r.nextDouble();
					Vec3 p = new Vec3(floor.x + Math.cos(a) * d, floor.y + 0.1, floor.z + Math.sin(a) * d);
					Vec3 v = WellGeometry.pull(centre, p).scale(0.8).add(0, 0.12, 0);
					level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, ground), p.x, p.y, p.z, v.x, v.y, v.z);
					allow--;
				}
				// Whatever is being dragged sheds wisps toward the core.
				if (allow > 0 && t % 5 == 0) wisps(level, kit, centre, reach, Math.min(allow, 4));
				if (t > 0 && t % 30 == 0) {
					level.playLocalSound(centre.x, centre.y, centre.z, SoundEvents.BEACON_AMBIENT, SoundSource.PLAYERS, 0.6F, 0.55F, false);
				}
			} else if (t == life) {
				collapse(kit, level, centre, c);
			}
		});
	}

	/** One wisp off each of up to {@code n} things in reach (mobs, items, orbs), drifting toward the core. */
	private static void wisps(ClientLevel level, FxKit kit, Vec3 centre, double reach, int n) {
		int made = 0;
		for (Entity en : level.getEntities((Entity) null, new AABB(centre, centre).inflate(reach),
				x -> (x instanceof LivingEntity || x instanceof ItemEntity || x instanceof ExperienceOrb) && !TargetFilter.isProtected(x)
						&& x.getBoundingBox().getCenter().distanceToSqr(centre) <= reach * reach)) {
			if (made++ >= n) break;
			Vec3 at = en.getBoundingBox().getCenter();
			kit.motes(at, ShadowFx.VIOLET, 1, 0.2, WellGeometry.pull(centre, at).scale(0.5), 0.01, GlowMote.Drift.COAST, 0.11F, 10);
		}
	}

	/** The end: the core swallows itself and bursts, a ring of light runs out along the ground, and the sound of it. */
	private static void collapse(FxKit kit, ClientLevel level, Vec3 centre, float c) {
		ShadowPop pop = new ShadowPop(level, centre, 1.3F + 0.4F * c, 10);
		if (kit.budget().particles(ShadowPop.cost()) >= ShadowPop.cost()) Minecraft.getInstance().particleEngine.add(pop);
		kit.burst(centre, ParticleTypes.REVERSE_PORTAL, 18, 0.35);
		kit.burst(centre, ParticleTypes.SQUID_INK, 6, 0.18);
		int n = 16;
		for (int i = 0; i < n; i++) {
			double a = Mth.TWO_PI * i / n;
			Vec3 v = new Vec3(Math.cos(a) * 0.28, 0.02, Math.sin(a) * 0.28);
			kit.motes(centre, i % 4 == 0 ? ShadowFx.PALE : ShadowFx.VIOLET, 1, 0.05, v, 0.01, GlowMote.Drift.COAST, 0.15F, 12);
		}
		level.playLocalSound(centre.x, centre.y, centre.z, SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), SoundSource.PLAYERS, 0.8F, 1.2F, false);
		level.playLocalSound(centre.x, centre.y, centre.z, SoundEvents.ENDER_EYE_DEATH, SoundSource.PLAYERS, 0.8F, 0.5F, false);
	}

	/**
	 * The core: a hole of darkness with a violet event horizon, swelling open, breathing, then collapsing to nothing; a tilted
	 * accretion disc of violet light and black smoke turning round it (inner parts faster); a wide dim shadow round it all.
	 */
	static final class Core extends SingleQuadParticle implements LightPipeline.Extent {
		private static final int RIM = 24;
		private static final int ARCS = 16;
		private static final int ARC_PIECES = 5;
		private static final int DISC_DARK = 18;
		private static final int OPEN = 6;

		private final Vec3 centre;
		private final float radius;
		private final int life;
		private final Vec3 du;
		private final Vec3 dv;
		/** Daylight at the core (re-read every tick): its wide shadow is stronger by day. */
		private float day;
		private final Umbra umbra = new Umbra();

		Core(ClientLevel level, Vec3 centre, float radius, int life, float yaw) {
			super(level, centre.x, centre.y, centre.z, MagicCircleRenderer.sprite("glow"));
			this.centre = centre;
			this.radius = radius;
			this.life = life;
			// The disc's plane: level, tipped 18° about a seeded axis.
			double tilt = Math.toRadians(18);
			Vec3 n = new Vec3(Math.cos(yaw) * Math.sin(tilt), Math.cos(tilt), Math.sin(yaw) * Math.sin(tilt));
			Vec3[] p = ShadowFx.plane(n);
			this.du = p[0];
			this.dv = p[1];
			this.day = Umbra.daylight(centre);
			this.lifetime = life + COLLAPSE;
			this.hasPhysics = false;
			this.gravity = 0;
			this.xd = 0;
			this.yd = 0;
			this.zd = 0;
		}

		/** Fx-budget particles it costs (its pieces a frame, per 50). */
		static int cost() {
			return Umbra.budgetCost(Umbra.orbPieces(RIM) + ARCS * ARC_PIECES + DISC_DARK);
		}

		@Override
		public void tick() {
			xo = x;
			yo = y;
			zo = z;
			day = Umbra.daylight(centre);
			if (++age >= lifetime) remove();
		}

		@Override
		public void extract(QuadParticleRenderState state, Camera camera, float partial) {
			float now = age + partial;
			float open = Mth.clamp(now / OPEN, 0F, 1F);
			open = 1 - (1 - open) * (1 - open);
			float close = 1 - Mth.clamp((now - life) / COLLAPSE, 0F, 1F);
			float k = open * close;
			if (k <= 0.01F) return;
			umbra.begin(state, camera);
			float r = radius * k * (1 + 0.07F * Mth.sin(now * 0.45F));
			// The disc: a ring of black smoke turning fast close in, and arcs of violet light streaming round further out
			// (inner parts turn faster), each arc bright at its head and fading behind it.
			for (int i = 0; i < DISC_DARK; i++) {
				double rho = r * (1.35 + 0.55 * frac(i * 0.618));
				double a = Mth.TWO_PI * i / DISC_DARK + now * 0.2 * (1.35 * r / rho);
				umbra.dark(disc(rho, a), r * 0.55F, 0.7F * k);
			}
			for (int i = 0; i < ARCS; i++) {
				double rho = r * (1.7 + 1.0 * frac(i * 0.618 + 0.3));
				double head = Mth.TWO_PI * i / ARCS + now * 0.12 * (1.9 * r / rho);
				float flick = 0.75F + 0.25F * Mth.sin(now * 0.6F + i * 1.7F);
				int rgb = i % 4 == 0 ? ShadowFx.PALE : i % 2 == 0 ? ShadowFx.MAGENTA : ShadowFx.VIOLET;
				float w = r * (0.22F + 0.12F * (float) frac(i * 0.37));
				for (int j = 0; j < ARC_PIECES; j++) {
					float f = 1 - (float) j / ARC_PIECES;
					umbra.light(disc(rho, head - j * 0.09), w * (0.6F + 0.4F * f), 0.5F * k * flick * f, rgb);
				}
			}
			umbra.orb(centre, r, k, now * 0.12, RIM, (0.06F + 0.34F * day) * k);
		}

		private Vec3 disc(double rho, double a) {
			return centre.add(du.scale(Math.cos(a) * rho)).add(dv.scale(Math.sin(a) * rho));
		}

		private static double frac(double v) {
			return v - Math.floor(v);
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
			return radius * 6 + 1;
		}
	}

	/**
	 * One mote falling in on {@link WellGeometry#spiral}: a short streak of violet light, or a black wisp of darkness, fading in
	 * at the edge and swallowed at the core.
	 */
	static final class Infall extends SingleQuadParticle implements LightPipeline.Extent {
		private static final int PIECES = 6;

		private final Vec3 centre;
		private final double r0;
		private final double angle0;
		private final double lift0;
		private final boolean dark;
		private final float size;
		private final int rgb;
		private final Umbra umbra = new Umbra();

		/** {@code maxLife}: it is gone by then even if it has not reached the core (the core has collapsed). */
		Infall(ClientLevel level, Vec3 centre, double r0, double angle0, double lift0, boolean dark, float size, int rgb, int maxLife) {
			super(level, centre.x, centre.y, centre.z, MagicCircleRenderer.sprite("glow"));
			this.centre = centre;
			this.r0 = r0;
			this.angle0 = angle0;
			this.lift0 = lift0;
			this.dark = dark;
			this.size = size;
			this.rgb = rgb;
			this.lifetime = Math.max(1, Math.min(WellGeometry.arrival(r0) + 1, maxLife));
			this.hasPhysics = false;
			this.gravity = 0;
			Vec3 p = WellGeometry.spiral(centre, r0, angle0, lift0, 0);
			this.x = p.x;
			this.y = p.y;
			this.z = p.z;
			this.xo = p.x;
			this.yo = p.y;
			this.zo = p.z;
		}

		@Override
		public void tick() {
			xo = x;
			yo = y;
			zo = z;
			Vec3 p = WellGeometry.spiral(centre, r0, angle0, lift0, ++age);
			x = p.x;
			y = p.y;
			z = p.z;
			if (age >= lifetime) remove();
		}

		@Override
		public void extract(QuadParticleRenderState state, Camera camera, float partial) {
			double t = age + partial;
			double rNow = WellGeometry.radiusAt(r0, t);
			float in = (float) Mth.clamp(t / 4, 0, 1);
			float swallowed = (float) Mth.clamp((rNow - 0.25) / 0.5, 0, 1);
			float a = in * swallowed;
			if (a <= 0.01F) return;
			umbra.begin(state, camera);
			// Brighter as it nears the core.
			float heat = 0.55F + 0.45F * (float) (1 - rNow / Math.max(0.01, r0));
			for (int k = 0; k < PIECES; k++) {
				Vec3 p = WellGeometry.spiral(centre, r0, angle0, lift0, Math.max(0, t - k * 0.3));
				float f = 1 - (float) k / PIECES;
				if (dark) {
					umbra.dark(p, size * (0.5F + 0.5F * f), 0.9F * a * f);
				} else {
					umbra.light(p, size * (0.5F + 0.5F * f), 0.85F * a * f * heat, rgb);
				}
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
			return 1.5;
		}
	}
}
