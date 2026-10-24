package local.carter.office.client.magic.fx.shadow;

import java.util.List;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.client.magic.fx.RankFx;
import local.carter.office.client.magic.fx.Shake;
import local.carter.office.client.magic.fx.SpellFx;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.ProjectileSpec;
import local.carter.office.magic.spell.Projectiles;
import local.carter.office.magic.spell.shadow.BlackHole;
import local.carter.office.magic.spell.shadow.BlackHoleGeometry;
import local.carter.office.magic.spell.shadow.BlackHoleGeometry.Cell;
import local.carter.office.magic.spell.shadow.WellGeometry;
import local.carter.office.magic.terrain.WorldTerrainEdits;
import local.carter.office.magic.tree.SpellRank;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Black Hole, the Shadow ultimate. A gate circle opens before the caster and a sigil the size of the crater-to-be lights the
 * ground; out of the gate tears a singularity: a black core that swells as it feeds, ringed by a burning photon ring, a tilted
 * accretion disc of violet and white-hot light whose far side is bent over and under the core (the lensed halo), and black
 * anti-magic lightning crackling off it. The land is torn away as the server eats it ({@link BlackHoleGeometry}, the same cells):
 * real blocks rip loose from the crater wall and spiral into the core, grit sprays inward, motes and black wisps stream in from
 * the pull's reach, and the ground rumbles harder and harder. At the end the core crunches to a point and bursts: a shock ring
 * runs out across the land, the screen shakes (1.0 near it, still 0.8 at 32 blocks), the caster's hit-stop, and the crater's lip
 * flies (the server's own falling debris).
 *
 * <p>Budget (1500): cast {@link #CAST}, the life {@link #LIFE_SHARE}, the collapse {@link #BURST}; ghost blocks: up to 128
 * spread evenly over the land actually eaten.
 */
public final class BlackHoleFx implements SpellFx {
	private static final Vec3 UP = new Vec3(0, 1, 0);
	static final int CAST = 90;
	static final int LIFE_SHARE = 1000;
	static final int BURST = 380;
	/** Ticks the core takes to crunch before its burst at the end of its life. */
	static final int CRUNCH = 7;
	/** How far ahead (ticks) the client tears a block loose, so the ghost covers it as the real one goes. */
	private static final int LEAD = 1;
	/** Shake at the burst: full within {@link #SHAKE_FULL}, 0.8 at 32, none past {@link #SHAKE_NONE}. */
	static final float SHAKE = 1.0F;
	static final double SHAKE_FULL = 24;
	static final double SHAKE_NONE = 64;

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		Vec3 centre = e.target();
		float[] data = e.data();
		double cap = data.length > 0 && data[0] > 0 ? data[0] : BlackHoleGeometry.BASE_CAP;
		double reach = data.length > 1 && data[1] > 0 ? data[1] : BlackHoleGeometry.PULL_RADIUS;
		int life = Math.max(1, e.duration());
		RandomSource r = kit.random();
		int[] spend = {LIFE_SHARE};

		// ---- the cast ----
		Vec3 dir = ShadowFx.unit(e.dir());
		Vec3 muzzle = e.origin().add(dir.scale(1.8)).add(0, -0.15, 0);
		kit.circle(muzzle, dir.reverse(), ShadowFx.ARGB, 0.8F, 5, 16);
		Vec3 toCaster = ShadowFx.unit(new Vec3(e.origin().x - centre.x, 0, e.origin().z - centre.z));
		kit.circle(centre, toCaster, ShadowFx.ARGB, (float) (1.4 + 0.25 * cap), 5, 26);
		Vec3 ground = new Vec3(centre.x, centre.y - BlackHoleGeometry.HEIGHT + 0.06, centre.z);
		// Dimmed (the alpha scales a circle's light): it marks the land to be eaten without drowning the crater in light.
		kit.circle(ground, UP, (0x58 << 24) | ShadowFx.VIOLET, (float) cap + 0.4F, 5, life + 4);
		kit.trail(muzzle.add(dir), centre, ParticleTypes.REVERSE_PORTAL, 20);
		Singularity core = new Singularity(level, centre, cap, life, r.nextFloat() * Mth.TWO_PI);
		if (kit.budget().particles(Singularity.cost()) >= Singularity.cost()) Minecraft.getInstance().particleEngine.add(core);
		kit.burst(centre, ParticleTypes.REVERSE_PORTAL, 30, 0.45);
		level.playLocalSound(centre.x, centre.y, centre.z, SoundEvents.END_PORTAL_SPAWN, SoundSource.PLAYERS, 0.8F, 0.5F, false);
		level.playLocalSound(centre.x, centre.y, centre.z, SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.PLAYERS, 1.2F, 0.5F, false);
		level.playLocalSound(muzzle.x, muzzle.y, muzzle.z, SoundEvents.EVOKER_CAST_SPELL, SoundSource.PLAYERS, 0.8F, 0.5F, false);
		double d0 = Minecraft.getInstance().gameRenderer.mainCamera().position().distanceTo(centre);
		Shake.shake(0.35F * nearness(d0), 12);

		// ---- the land it will eat: the same cells as the server, nearest first ----
		List<Cell> cells = BlackHoleGeometry.sphere(centre, cap);
		int[] solidLeft = {0};
		for (Cell c : cells) {
			if (!BlackHoleGeometry.isRim(c, cap) && eats(level.getBlockState(new BlockPos(c.x(), c.y(), c.z())))) solidLeft[0]++;
		}
		int[] next = {0};

		kit.simulate(1, life + 30, t -> {
			Devoured.keep(e.casterId());
			if (t < life) {
				devour(kit, level, centre, cap, cells, next, solidLeft, spend, t);
				feed(kit, level, centre, reach, life, spend, t, r);
				double d = Minecraft.getInstance().gameRenderer.mainCamera().position().distanceTo(centre);
				if (t % 5 == 0) Shake.shake((float) (0.08 + 0.32 * t / life) * nearness(d), 7);
				if (t % 20 == 10) level.playLocalSound(centre.x, centre.y, centre.z, SoundEvents.BEACON_AMBIENT, SoundSource.PLAYERS, 1.2F, 0.5F, false);
				if (t % 25 == 5) level.playLocalSound(centre.x, centre.y, centre.z, SoundEvents.RESPAWN_ANCHOR_AMBIENT, SoundSource.PLAYERS, 1.0F, 0.5F, false);
				if (t == life - CRUNCH) {
					level.playLocalSound(centre.x, centre.y, centre.z, SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.PLAYERS, 1.5F, 1.4F, false);
					Shake.kick(-0.08F * nearness(d));
				}
			} else if (t == life) {
				burst(kit, level, centre, cap, ground);
				forks(e, kit, level, centre);
			}
		});
	}

	/** The collapse's forks: Supernova's wider blast, Dark Rain's bolts flying out (as the server flies them). */
	private static void forks(FxEvent e, FxKit kit, ClientLevel level, Vec3 centre) {
		SpellRank rank = e.spellRank();
		if (rank.is(BlackHole.SUPERNOVA)) {
			RankFx.shockRing(kit, level, centre, BlackHole.blastRadius(rank), 10, ShadowFx.MAGENTA, ParticleTypes.REVERSE_PORTAL);
			RankFx.blast(kit, level, centre, BlackHole.blastRadius(rank) * 0.6, ShadowFx.PALE, ParticleTypes.SQUID_INK);
		}
		if (!rank.is(BlackHole.DARK_RAIN)) return;
		for (ProjectileSpec s : BlackHole.rain(centre, e.seed())) {
			boolean[] done = {false};
			kit.simulate(1, s.maxTicks(), t -> {
				if (done[0]) return;
				Vec3 from = Projectiles.step(s, t);
				Vec3 to = Projectiles.step(s, t + 1);
				BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
				Vec3 end = hit.getType() == HitResult.Type.MISS ? to : hit.getLocation();
				kit.motes(end, t % 2 == 0 ? ShadowFx.VIOLET : ShadowFx.MAGENTA, 2, 0.08, Vec3.ZERO, 0.01, GlowMote.Drift.SINK, 0.2F, 5);
				kit.trail(from, end, ParticleTypes.SMOKE, 2);
				if (hit.getType() != HitResult.Type.MISS) {
					done[0] = true;
					kit.burst(end, ParticleTypes.REVERSE_PORTAL, 10, 0.2);
				}
			});
		}
	}

	/** Tears loose (as ghost blocks) a fair share of what the sphere reaches this tick, and sprays grit off the rest. */
	private static void devour(FxKit kit, ClientLevel level, Vec3 centre, double cap, List<Cell> cells, int[] next, int[] solidLeft,
			int[] spend, int t) {
		double r = BlackHoleGeometry.radius(t + LEAD, cap);
		int torn = 0;
		while (next[0] < cells.size() && cells.get(next[0]).dist() <= r) {
			Cell c = cells.get(next[0]++);
			if (BlackHoleGeometry.isRim(c, cap)) continue;
			BlockPos pos = new BlockPos(c.x(), c.y(), c.z());
			BlockState s = level.getBlockState(pos);
			if (!eats(s)) continue;
			int left = kit.budget().ghostBlocksLeft();
			// Spread the ghost budget evenly over the land there is to eat.
			boolean tear = torn < 4 && left > 0 && kit.random().nextInt(Math.max(1, solidLeft[0])) < left;
			solidLeft[0] = Math.max(0, solidLeft[0] - 1);
			if (tear && Devoured.tear(kit, centre, pos, s, 9 + (int) Math.round(c.dist() * 2.2), kit.random().nextLong())) {
				torn++;
			} else if (spend[0] > 0) {
				// Grit sprayed inward off a block that went without a ghost.
				int n = kit.budget().particles(Math.min(2, spend[0]));
				spend[0] -= n;
				Vec3 at = c.middle();
				for (int i = 0; i < n; i++) {
					Vec3 v = BlackHoleGeometry.pull(centre, at, 100).scale(0.9 + 0.6 * kit.random().nextDouble());
					level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, s), at.x, at.y, at.z, v.x, v.y + 0.05, v.z);
				}
			}
		}
	}

	/** Motes and black wisps streaming in, portal sparks converging, paced so they last the whole life. */
	private static void feed(FxKit kit, ClientLevel level, Vec3 centre, double reach, int life, int[] spend, int t, RandomSource r) {
		int allow = Math.max(0, spend[0] / Math.max(1, life - t));
		int n = Math.min(allow, 4 + (t > life / 2 ? 2 : 0));
		for (int i = 0; i < n; i++) {
			if (kit.budget().particles(1) == 0) break;
			spend[0]--;
			double r0 = 3 + (reach - 3) * Math.sqrt(r.nextDouble());
			double a = r.nextDouble() * Mth.TWO_PI;
			double y = (r.nextDouble() - 0.35) * 0.7 * r0;
			Vec3 from = centre.add(Math.cos(a) * r0, y, Math.sin(a) * r0);
			boolean dark = r.nextInt(5) == 0;
			int rgb = r.nextInt(4) == 0 ? ShadowFx.PALE : r.nextBoolean() ? ShadowFx.VIOLET : ShadowFx.MAGENTA;
			Minecraft.getInstance().particleEngine.add(new Mote(level, centre, from, 14 + (int) (r0 * 1.6), dark, rgb,
					dark ? 0.13F : 0.09F + 0.04F * r.nextFloat(), 0.8 + 0.8 * r.nextDouble()));
		}
		if (t % 2 == 0 && spend[0] > 0) {
			int k = kit.budget().particles(Math.min(2, spend[0]));
			spend[0] -= k;
			for (int i = 0; i < k; i++) {
				// A portal particle flies from its offset back to where it was put: it converges on the core.
				Vec3 off = new Vec3(r.nextDouble() - 0.5, r.nextDouble() - 0.5, r.nextDouble() - 0.5).normalize().scale(5);
				level.addParticle(ParticleTypes.PORTAL, centre.x, centre.y, centre.z, off.x, off.y, off.z);
			}
		}
	}

	/** The end: a burst of darkness and light, a shock ring along the ground, the boom, the shake and the hit-stop. */
	private static void burst(FxKit kit, ClientLevel level, Vec3 centre, double cap, Vec3 ground) {
		ShadowPop pop = new ShadowPop(level, centre, (float) (2.2 + 0.25 * cap), 16);
		if (kit.budget().particles(ShadowPop.cost()) >= ShadowPop.cost()) Minecraft.getInstance().particleEngine.add(pop);
		Shock shock = new Shock(level, ground.add(0, 0.35, 0), cap, 18);
		if (kit.budget().particles(Shock.cost()) >= Shock.cost()) Minecraft.getInstance().particleEngine.add(shock);
		kit.burst(centre, ParticleTypes.REVERSE_PORTAL, 60, 0.9);
		kit.burst(centre, ParticleTypes.SQUID_INK, 24, 0.5);
		kit.burst(centre, ParticleTypes.LARGE_SMOKE, 20, 0.35);
		int n = 40;
		for (int i = 0; i < n; i++) {
			double a = Mth.TWO_PI * i / n;
			Vec3 v = new Vec3(Math.cos(a) * 0.7, 0.03 + 0.05 * (i % 3), Math.sin(a) * 0.7);
			kit.motes(centre, i % 4 == 0 ? ShadowFx.PALE : i % 2 == 0 ? ShadowFx.MAGENTA : ShadowFx.VIOLET, 1, 0.2, v, 0.04, GlowMote.Drift.COAST,
					0.22F, 18);
		}
		kit.motes(centre, ShadowFx.PALE, 24, 0.6, Vec3.ZERO, 0.6, GlowMote.Drift.COAST, 0.18F, 14);
		double d = Minecraft.getInstance().gameRenderer.mainCamera().position().distanceTo(centre);
		float near = nearness(d);
		Shake.shake(SHAKE * near, 30);
		Shake.kick(0.14F * near);
		kit.hitStop(3);
		level.playLocalSound(centre.x, centre.y, centre.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 3.0F, 0.6F, false);
		level.playLocalSound(centre.x, centre.y, centre.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 2.5F, 0.55F, false);
		level.playLocalSound(centre.x, centre.y, centre.z, SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), SoundSource.PLAYERS, 2.0F, 0.5F, false);
	}

	/**
	 * Whether the hole eats (and the client tears loose) a block like {@code s}: not air or fluid, and nothing the terrain core
	 * refuses (block entities, {@code #office:magic_immune}, either half of a two-block-tall block), so no ghost doubles a block
	 * that stays. (Claims are the server's to know: a refused claimed block may still get a ghost.)
	 */
	static boolean eats(BlockState s) {
		return !s.isAir() && s.getFluidState().isEmpty() && !s.hasBlockEntity() && !s.is(WorldTerrainEdits.IMMUNE)
				&& !s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF);
	}

	/** Shake share at {@code d} blocks from the hole: 1 within 24, 0.8 at 32, 0 past 64. */
	static float nearness(double d) {
		if (d <= SHAKE_FULL) return 1F;
		if (d <= 32) return (float) (1 - 0.2 * (d - SHAKE_FULL) / (32 - SHAKE_FULL));
		return (float) Mth.clamp(0.8 * (SHAKE_NONE - d) / (SHAKE_NONE - 32), 0, 0.8);
	}

	/**
	 * The singularity: a black core growing with what it has eaten ({@link BlackHoleGeometry#coreRadius}), a photon ring of light
	 * hugging it (one side brighter, as if turning toward you), the lensed halo of the disc's far side bent over and under it, a
	 * tilted accretion disc (black smoke close in, streaming arcs of violet, magenta and white-hot light further out; far-side pieces
	 * behind the core are dimmed so it stays black), black anti-magic lightning crackling off it and black flame licking up from
	 * it, and a wide shadow round it all. It tears open over {@link #OPEN} ticks and crunches to a point over {@link #CRUNCH}.
	 */
	static final class Singularity extends UmbraParticle {
		private static final int OPEN = 8;
		private static final int PHOTON = 120;
		private static final int HALO = 40;
		private static final int DISC_DARK = 28;
		private static final int ARCS = 26;
		private static final int ARC_PIECES = 6;
		private static final int BOLTS = 7;
		private static final int BOLT_SEGMENTS = 8;
		private static final int FLAMES = 12;

		private final Vec3 centre;
		private final double cap;
		private final int life;
		private final Vec3 du;
		private final Vec3 dv;
		private final Vec3 normal;
		private final long seed;
		private float day;

		Singularity(ClientLevel level, Vec3 centre, double cap, int life, float yaw) {
			super(level, centre, life + 2);
			this.centre = centre;
			this.cap = cap;
			this.life = life;
			double tilt = Math.toRadians(14);
			this.normal = new Vec3(Math.cos(yaw) * Math.sin(tilt), Math.cos(tilt), Math.sin(yaw) * Math.sin(tilt));
			Vec3[] p = ShadowFx.plane(normal);
			this.du = p[0];
			this.dv = p[1];
			this.seed = Double.doubleToLongBits(centre.x * 31 + centre.z * 17 + yaw);
			this.day = Umbra.daylight(centre);
		}

		static int cost() {
			int pieces = 2 + Umbra.HAZE_PIECES + 4 + PHOTON + HALO + DISC_DARK + ARCS * ARC_PIECES + BOLTS * BOLT_SEGMENTS * 3 * 2 + FLAMES * 3;
			return Umbra.budgetCost(pieces);
		}

		@Override
		public void tick() {
			super.tick();
			day = Umbra.daylight(centre);
		}

		@Override
		protected Vec3 anchor() {
			return centre;
		}

		@Override
		public double reach() {
			return cap + 4;
		}

		/** How open it is at {@code t}: tears open, holds, crunches. */
		private double open(double t) {
			double in = Mth.clamp(t / OPEN, 0, 1);
			in = 1 - (1 - in) * (1 - in) * (1 - in);
			double out = Mth.clamp((life - t) / CRUNCH, 0, 1);
			return Math.min(in, out * out);
		}

		@Override
		protected void draw(double t) {
			double k = open(t);
			if (k <= 0.01) return;
			float kf = (float) k;
			// Just before the burst the core swells a moment, then crunches.
			double swell = 1 + 0.25 * Mth.clamp(1 - Math.abs((life - CRUNCH - 1) - t) / 3, 0, 1);
			double R = BlackHoleGeometry.coreRadius(BlackHoleGeometry.radius(t, cap)) * k * swell * (1 + 0.04 * Math.sin(t * 0.5));
			float r = (float) R;
			Vec3 cam = umbra.camera();
			umbra.haze(centre, r * 3.4F, (0.1F + 0.4F * day) * kf);

			// The disc: dark smoke close in, then arcs of light (inner parts turn faster and burn hotter).
			double hide = WellGeometry.HIDDEN_SHARE * R;
			for (int i = 0; i < DISC_DARK; i++) {
				double rho = R * (1.25 + 0.7 * frac(i * 0.618));
				double a = Mth.TWO_PI * i / DISC_DARK + t * 0.22 * (1.3 * R / rho);
				Vec3 p = disc(rho, a);
				if (WellGeometry.behindCore(cam, centre, p, hide)) continue;
				umbra.dark(p, r * 0.5F, 0.75F * kf);
			}
			for (int i = 0; i < ARCS; i++) {
				double f = frac(i * 0.618 + 0.3);
				double rho = R * (1.55 + 2.2 * f * f);
				double head = Mth.TWO_PI * i / ARCS + t * 0.16 * Math.pow(1.6 * R / rho, 1.5);
				float flick = 0.75F + 0.25F * Mth.sin((float) (t * 0.7 + i * 1.7));
				int rgb = rho < R * 1.9 ? ShadowFx.PALE : i % 3 == 0 ? ShadowFx.MAGENTA : ShadowFx.VIOLET;
				float w = r * (0.2F + 0.14F * (float) frac(i * 0.37));
				for (int j = 0; j < ARC_PIECES; j++) {
					float fj = 1 - (float) j / ARC_PIECES;
					Vec3 p = disc(rho, head - j * 0.1);
					float behind = WellGeometry.behindCore(cam, centre, p, hide) ? WellGeometry.HIDDEN_LIGHT : 1F;
					umbra.light(p, w * (0.6F + 0.4F * fj), 0.55F * kf * flick * fj * behind, rgb);
				}
			}

			// The core: solid black, falling off just outside its edge.
			umbra.dark(centre, r * 1.75F, 0.75F * kf);
			umbra.dark(centre, r * 1.32F, kf);
			umbra.dark(centre, r * 1.08F, kf);
			umbra.dark(centre, r * 1.0F, kf);

			// The lensed halo: the disc's far side, bent round over and under the core (brightest across the disc's line on screen).
			double phi = screenAngle(du);
			for (int i = 0; i < HALO; i++) {
				double a = Mth.TWO_PI * i / HALO;
				double across = Math.abs(Math.sin(a - phi));
				float b = (float) (0.15 + 0.85 * across * across);
				Vec3 p = umbra.onScreenRing(centre, R * (1.28 + 0.06 * Math.sin(a * 3 + t * 0.3)), a);
				umbra.light(p, r * 0.22F, 0.42F * kf * b, i % 4 == 0 ? ShadowFx.PALE : ShadowFx.MAGENTA);
			}
			// The photon ring hugging the core: thin and hot, one side brighter (turning toward you).
			for (int i = 0; i < PHOTON; i++) {
				double a = Mth.TWO_PI * i / PHOTON + t * 0.05;
				float b = 0.35F + 0.65F * (float) Math.pow(0.5 + 0.5 * Math.cos(a - phi - t * 0.02), 2);
				Vec3 p = umbra.onScreenRing(centre, R * 1.04, a);
				umbra.light(p, r * 0.14F, 0.5F * kf * b, i % 4 == 0 ? 0xFFFFFF : ShadowFx.PALE);
			}

			// Black anti-magic lightning: jagged black bolts edged in violet, re-struck every few ticks, flickering.
			long strike = (long) Math.floor(t / 3);
			for (int i = 0; i < BOLTS; i++) {
				long s = seed * 31 + strike * 7919 + i * 104729L;
				if (((s >>> 7) & 3) == 0) continue; // not this time
				bolt(R, s, kf);
			}

			// Black flame licking up off it.
			for (int i = 0; i < FLAMES; i++) {
				double a = Mth.TWO_PI * i / FLAMES + 0.3 * Math.sin(t * 0.2 + i);
				double f = frac(t * 0.07 + i * 0.37);
				Vec3 base = umbra.onScreenRing(centre, R * 0.85, a);
				for (int j = 0; j < 3; j++) {
					double rise = (f + j * 0.12) * R * 1.1;
					float fade = (float) ((1 - f) * (1 - j * 0.3));
					umbra.dark(base.add(0, rise, 0).add(centre.subtract(base).scale(-0.15 * f)), r * (0.32F - 0.07F * j), 0.6F * kf * fade);
				}
			}
		}

		/** One bolt of black lightning off the core, from seed {@code s}. */
		private void bolt(double R, long s, float k) {
			RandomSource rs = RandomSource.create(s);
			Vec3 out = new Vec3(rs.nextDouble() - 0.5, (rs.nextDouble() - 0.5) * 0.8, rs.nextDouble() - 0.5);
			out = ShadowFx.unit(out);
			double len = R * (1.6 + 1.8 * rs.nextDouble()) + 1.0;
			Vec3 p = centre.add(out.scale(R * 0.95));
			Vec3[] side = ShadowFx.plane(out);
			for (int j = 0; j < BOLT_SEGMENTS; j++) {
				double step = len / BOLT_SEGMENTS;
				Vec3 kink = side[0].scale((rs.nextDouble() - 0.5) * step * 1.4).add(side[1].scale((rs.nextDouble() - 0.5) * step * 1.4));
				Vec3 q = p.add(out.scale(step)).add(kink);
				float taper = 1 - 0.7F * j / BOLT_SEGMENTS;
				for (int m = 0; m < 3; m++) {
					Vec3 at = p.lerp(q, (m + 0.5) / 3);
					umbra.dark(at, 0.2F * taper, 0.95F * k);
					umbra.light(at, 0.11F * taper, 0.7F * k, m == 1 ? ShadowFx.MAGENTA : ShadowFx.VIOLET);
				}
				p = q;
			}
		}

		/** The angle (radians, in the camera's left/up plane) that {@code v} points along on screen. */
		private double screenAngle(Vec3 v) {
			Vec3 l = umbra.left();
			Vec3 u = umbra.up();
			return Math.atan2(v.dot(u), v.dot(l));
		}

		private Vec3 disc(double rho, double a) {
			return centre.add(du.scale(Math.cos(a) * rho)).add(dv.scale(Math.sin(a) * rho));
		}

		private static double frac(double v) {
			return v - Math.floor(v);
		}
	}

	/** One mote falling in on {@link BlackHoleGeometry#infall}: a streak of violet light or a black wisp, swallowed at the core. */
	static final class Mote extends UmbraParticle {
		private static final int PIECES = 5;
		private final Vec3 centre;
		private final Vec3 from;
		private final int ticks;
		private final boolean dark;
		private final int rgb;
		private final float size;
		private final double turns;

		Mote(ClientLevel level, Vec3 centre, Vec3 from, int ticks, boolean dark, int rgb, float size, double turns) {
			super(level, from, ticks);
			this.centre = centre;
			this.from = from;
			this.ticks = ticks;
			this.dark = dark;
			this.rgb = rgb;
			this.size = size;
			this.turns = turns;
		}

		@Override
		protected Vec3 anchor() {
			return BlackHoleGeometry.infall(centre, from, turns, age, ticks);
		}

		@Override
		public double reach() {
			return 2;
		}

		@Override
		protected void draw(double t) {
			float in = (float) Mth.clamp(t / 4, 0, 1);
			float heat = (float) (0.5 + 0.5 * Mth.clamp(t / ticks, 0, 1));
			for (int k = 0; k < PIECES; k++) {
				double tk = Math.max(0, t - k * 0.35);
				Vec3 p = BlackHoleGeometry.infall(centre, from, turns, tk, ticks);
				float f = (1 - (float) k / PIECES) * in;
				if (dark) {
					umbra.dark(p, size * (0.5F + 0.5F * f), 0.9F * f);
				} else {
					umbra.light(p, size * (0.5F + 0.5F * f), 0.85F * f * heat, rgb);
				}
			}
		}
	}

	/** The shock ring at the burst: light and darkness running out along the ground, fading. */
	static final class Shock extends UmbraParticle {
		private static final int PIECES = 128;
		private final Vec3 at;
		private final double cap;

		Shock(ClientLevel level, Vec3 at, double cap, int life) {
			super(level, at, life);
			this.at = at;
			this.cap = cap;
		}

		static int cost() {
			return Umbra.budgetCost(PIECES);
		}

		@Override
		protected Vec3 anchor() {
			return at;
		}

		@Override
		public double reach() {
			return cap + 12;
		}

		@Override
		protected void draw(double t) {
			double f = Mth.clamp(t / lifetime, 0, 1);
			double grow = 1 - (1 - f) * (1 - f);
			double radius = cap * 0.5 + (cap + 9) * grow;
			float a = (float) ((1 - f) * (1 - f));
			// Overlapping pieces (about one a block round even at its widest), so it reads as one ring of light, not beads.
			float size = (float) (0.9 + 0.5 * (1 - f));
			for (int i = 0; i < PIECES; i++) {
				double ang = Mth.TWO_PI * i / PIECES;
				Vec3 p = at.add(Math.cos(ang) * radius, 0.15 * Math.sin(ang * 5 + t), Math.sin(ang) * radius);
				umbra.light(p, size, 0.55F * a, i % 3 == 0 ? ShadowFx.PALE : i % 2 == 0 ? ShadowFx.MAGENTA : ShadowFx.VIOLET);
			}
		}
	}
}
