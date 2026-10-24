package local.carter.office.client.magic.fx.earth;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.client.magic.fx.Shake;
import local.carter.office.client.magic.fx.SpellFx;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.earth.EarthSpikes;
import local.carter.office.magic.spell.earth.EarthWallGeometry;
import local.carter.office.magic.spell.earth.MountainFist;
import local.carter.office.magic.spell.earth.MountainFistGeometry;
import local.carter.office.magic.spell.earth.QuakeGeometry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Mountain Fist: an earth seal blazes on the ground at the target and another opens in the sky above it; the ground erupts
 * (plates of it pry up all round, dripstone fangs burst out in a ring, a blast of crumbs, dust and smoke, a shockwave of dust
 * rolling out, a deep boom, shake 1.0 and a 3-tick hit-stop) and a giant fist of rock punches up out of it, turf still on its
 * knuckles. It rises skyward on the spire the server builds under it, layer by layer, shedding grit, punches through the sky
 * seal at the top with a crack, holds, and bursts apart into tumbling rocks, a cloud of dust drifting off the new spire.
 *
 * <p>The fist (62 rocks of 1.3 blocks: forearm, wrist, back of the hand, four curled fingers, knuckles, a thumb; stone, andesite,
 * cobblestone and tuff, leaning toward the caster like an uppercut and glowing faintly at night) and every other rock are
 * {@link EarthStones} on one cancellable timer's leash. The 1500 particles are split into phase shares (cast, eruption, rise,
 * shatter, settle), so no phase can starve a later one; the shake reaches everyone near the target ({@link #nearness}: full
 * within 24 blocks, still 0.8 at 32), not just the caster.
 */
public final class MountainFistFx implements SpellFx {
	private static final Vec3 UP = new Vec3(0, 1, 0);
	/** Ticks the fist takes to burst out of the ground, and when it bursts apart; how long its pieces fly. */
	static final float ERUPT = 3.5F;
	static final int SHATTER_AT = MountainFistGeometry.RISE + 6;
	static final int SHATTER_LIFE = 22;
	/** How long the whole visual runs. */
	static final int TOTAL = SHATTER_AT + 50;
	/** The fist's top, in voxels above its wrist. */
	static final float FIST_TOP = 6F;
	/** Particles each phase may spend: together the ultimate's whole budget. */
	static final int CAST = 80;
	static final int ERUPTION = 560;
	static final int RISE = 300;
	static final int SHATTER = 440;
	static final int SETTLE = 120;
	/** Shake at the eruption: full within {@link #SHAKE_FULL} of the target, 0 past {@link #SHAKE_NONE} (0.8 at 32). */
	static final float SHAKE = 1F;
	static final double SHAKE_FULL = 24;
	static final double SHAKE_NONE = 64;
	/** Ground plates prying up round the base, and dripstone fangs bursting out in a ring. */
	static final int PLATES = 16;
	static final int FANGS = 8;
	/** Lumps of ground thrown out of the eruption. */
	static final int EJECTA = 10;

	/** One rock of the fist: its centre in voxels (x across, y up from the wrist, z toward the caster), its look and size. */
	record Voxel(float x, float y, float z, int kind, float size) {}

	/** The fist, punching up: forearm and wrist, the back of the hand, four curled fingers, the knuckles (turf on top), a thumb across the front. */
	static List<Voxel> fist() {
		List<Voxel> v = new ArrayList<>();
		// The forearm coming out of the spire (2 × 2), then a wrist band (3 × 2).
		for (float y : new float[] {-1.5F, -0.5F, 0.5F}) {
			for (float x : new float[] {-0.5F, 0.5F}) {
				for (float z : new float[] {-0.5F, 0.5F}) v.add(new Voxel(x, y, z, ((int) (y + 2) + (x > 0 ? 1 : 0) + (z > 0 ? 1 : 0)) % 2, 1F));
			}
		}
		for (float x : new float[] {-1F, 0F, 1F}) {
			for (float z : new float[] {-0.5F, 0.5F}) v.add(new Voxel(x, 1.5F, z, 2, 1F));
		}
		// The back of the hand: 4 wide, 2 deep, 3 high.
		for (float y : new float[] {2.5F, 3.5F, 4.5F}) {
			for (float x : new float[] {-1.5F, -0.5F, 0.5F, 1.5F}) {
				for (float z : new float[] {-1F, 0F}) {
					if (z == 0F && Math.abs(x) < 1 && y == 3.5F) continue; // hidden inside
					v.add(new Voxel(x, y, z, (int) (x + y + z + 10) % 4 == 0 ? 1 : 0, 1F));
				}
			}
		}
		// Four curled fingers down the front, a seam between each, and the knuckles bumping up along the top (turf torn up on
		// the back row).
		int finger = 0;
		for (float x : new float[] {-1.5F, -0.5F, 0.5F, 1.5F}) {
			int kind = (finger & 1) == 0 ? 1 : 0;
			float bump = (finger & 1) == 0 ? 0.08F : -0.04F;
			v.add(new Voxel(x, 3.5F, 1F, kind, 0.92F));
			v.add(new Voxel(x, 4.5F, 1F, kind, 0.92F));
			v.add(new Voxel(x, 5.45F + bump, -0.5F, 4, 0.94F));
			v.add(new Voxel(x, 5.3F + bump, 0.6F, kind, 0.9F));
			finger++;
		}
		// Fingertips tucked under, and the thumb wrapped across the front, standing out, its base on the side.
		v.add(new Voxel(0.5F, 2.6F, 1F, 2, 0.82F));
		v.add(new Voxel(1.5F, 2.6F, 1F, 2, 0.82F));
		v.add(new Voxel(-1.6F, 2.6F, 1.55F, 3, 0.94F));
		v.add(new Voxel(-0.6F, 2.65F, 1.6F, 3, 0.94F));
		v.add(new Voxel(0.35F, 2.75F, 1.55F, 3, 0.9F));
		v.add(new Voxel(-2.15F, 3.0F, 0.5F, 3, 0.85F));
		return v;
	}

	/** The fist's rocks: stone, andesite, cobblestone, tuff, or the target's own surface (turf torn up on the knuckles). */
	private static BlockState look(int kind, BlockState surface) {
		return switch (kind) {
			case 1 -> Blocks.ANDESITE.defaultBlockState();
			case 2 -> Blocks.COBBLESTONE.defaultBlockState();
			case 3 -> Blocks.TUFF.defaultBlockState();
			case 4 -> surface;
			default -> Blocks.STONE.defaultBlockState();
		};
	}

	/** Shake strength share at {@code d} blocks from the target: 1 within {@link #SHAKE_FULL}, linear to 0 at {@link #SHAKE_NONE}. */
	static float nearness(double d) {
		return (float) Mth.clamp((SHAKE_NONE - d) / (SHAKE_NONE - SHAKE_FULL), 0, 1);
	}

	/** Voxel size: 1.3 blocks at level 7, a little bigger with each level (the spire's height tells the level). */
	static float voxelScale(int height) {
		return 1.3F + 0.08F * Math.max(0, (height - 8) / 2F);
	}

	/** How far the fist leans toward the caster (degrees): an uppercut, knuckles first. */
	static final float LEAN = 14F;
	/** Least block light of the fist and its fangs: they glow faintly at night. */
	static final int GLOW = 11;

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		float[] d = e.data();
		int baseRadius = d.length > 0 ? Math.round(d[0]) : MountainFistGeometry.baseRadius(7);
		int height = d.length > 1 ? Math.round(d[1]) : MountainFistGeometry.height(7);
		BlockPos target = BlockPos.containing(e.target().add(0, 0.01, 0));
		double ground = target.getY();
		Vec3 foot = Vec3.atBottomCenterOf(target);
		Vec3 toward = EarthWallGeometry.flat(e.dir()).reverse();
		float yaw0 = (float) Math.toDegrees(Math.atan2(toward.x, toward.z));
		float s = voxelScale(height);
		BlockState rawSurface = level.getBlockState(target.below());
		BlockState surface = rawSurface.isAir() ? EarthFx.COBBLE : rawSurface;
		BlockState spireSurface = MountainFist.surfaceMaterial(level, target.below());
		RandomSource r = kit.random();
		int caster = e.casterId();
		Fist fist = new Fist(ground, height, s, yaw0);

		EarthFx.Share cast = new EarthFx.Share(CAST);
		EarthFx.Share erupt = new EarthFx.Share(ERUPTION);
		EarthFx.Share rise = new EarthFx.Share(RISE);
		EarthFx.Share shatter = new EarthFx.Share(SHATTER);
		EarthFx.Share settle = new EarthFx.Share(SETTLE);

		// The seals: a wide one on the ground, one in the sky the fist will punch through.
		double peak = fist.base(MountainFistGeometry.RISE) + FIST_TOP * s;
		Vec3 sky = new Vec3(foot.x, peak + 1.2, foot.z);
		kit.circle(foot.add(0, 0.06, 0), UP, EarthFx.ARGB, baseRadius + 1.6F, 5, 44);
		kit.circle(sky, UP.reverse(), EarthFx.ARGB, 2.6F + 0.3F * s, 5, SHATTER_AT + 4);
		cast.take(2);
		kit.motes(sky, EarthFx.CLAY, cast.take(26), 1.6, Vec3.ZERO, 0.04, GlowMote.Drift.FALL, 0.3F, 26);
		kit.motes(foot.add(0, 0.3, 0), EarthFx.CLAY, cast.take(30), baseRadius, new Vec3(0, 0.04, 0), 0.03, GlowMote.Drift.RISE, 0.26F, 20);

		RockLedger.Leash leash = new RockLedger.Leash(EarthStones.now());
		int debris = d.length > 2 ? Math.round(d[2]) : MountainFistGeometry.debris(7);
		Set<Long> thrown = debrisCells(e.seed(), baseRadius, debris, target);
		Predicate<BlockPos> solid = EarthSpikes.solid(level);
		EarthFx.drive(kit, leash, TOTAL, t -> {
			if (t == 0) {
				// The fist first: if the rock caps are ever reached, the plates and fangs go without.
				fistRocks(kit, leash, fist, target, height, surface, caster, r);
				fangs(kit, leash, target, baseRadius, caster, r);
				plates(kit, leash, level, solid, thrown, target, baseRadius, caster, r);
				ejecta(kit, leash, level, foot, surface, baseRadius, r);
				erupt(kit, level, erupt, foot, surface, baseRadius);
			}
			if (t >= 1 && t <= 10) shockwave(kit, erupt, foot, surface, baseRadius + 1 + t * 0.9);
			if (t >= 1 && t <= MountainFistGeometry.RISE) rising(kit, level, rise, fist, foot, spireSurface, t);
			if (t == MountainFistGeometry.RISE) top(kit, level, rise, fist, foot, peak);
			if (t == SHATTER_AT) burst(kit, level, shatter, fist, foot, surface);
			if (t > SHATTER_AT && t <= SHATTER_AT + 14) {
				Vec3 c = new Vec3(foot.x, fist.base(SHATTER_AT) + 3 * s, foot.z);
				EarthFx.spray(level, new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.STONE.defaultBlockState()), c,
						shatter.spend(kit, 4), 2.5, 0.02, false);
			}
			if (t >= SHATTER_AT + 4 && t % 2 == 0) settle(kit, level, settle, foot, baseRadius, height);
		});
	}

	/** The ground blocks the server throws as debris (the plates skip them). */
	private static Set<Long> debrisCells(long seed, int baseRadius, int n, BlockPos target) {
		Set<Long> out = new HashSet<>();
		for (int i = 0; i < n; i++) {
			int[] o = MountainFistGeometry.debrisCell(i, n, baseRadius, seed);
			out.add(BlockPos.asLong(target.getX() + o[0], 0, target.getZ() + o[1]));
		}
		return out;
	}

	/** Where the fist is: its wrist's height and its sway, at any time (fractional ticks). */
	static final class Fist {
		final double ground;
		final int height;
		final float s;
		final float yaw0;

		Fist(double ground, int height, float s, float yaw0) {
			this.ground = ground;
			this.height = height;
			this.s = s;
			this.yaw0 = yaw0;
		}

		/** The wrist's height riding the spire's top (sunk a little into it). */
		double track(double t) {
			return ground + MountainFistGeometry.topAt(t, height) - 0.9;
		}

		/** The wrist's height at {@code t}: bursting up out of the ground, riding the spire, punching at the top. */
		double base(double t) {
			double buried = ground - FIST_TOP * s - 0.8;
			if (t < ERUPT) {
				double k = t / ERUPT;
				return Mth.lerp(1 - Math.pow(1 - k, 3), buried, track(ERUPT) + 1.3);
			}
			if (t < ERUPT + 3) return track(t) + 1.3 * (1 - (t - ERUPT) / 3);
			if (t < MountainFistGeometry.RISE) return track(t);
			double top = track(MountainFistGeometry.RISE);
			if (t < MountainFistGeometry.RISE + 4) return top + 0.9 * Math.sin(Math.PI * (t - MountainFistGeometry.RISE) / 4);
			return top;
		}

		float yaw(double t) {
			return yaw0 + (float) (5 * Math.sin(t * 0.35) * Math.max(0, 1 - t / MountainFistGeometry.RISE));
		}

		/** Leaning as it tears out of the ground, straightening as it rises; a shudder at the top. */
		float roll(double t) {
			double lean = 12 * Math.max(0, 1 - t / (ERUPT + 4));
			double shudder = t > MountainFistGeometry.RISE && t < SHATTER_AT ? 1.5 * Math.sin(t * 3.1) : 0;
			return (float) (lean * Math.sin(t * 0.9 + 0.4) + shudder);
		}
	}

	/** The fist's rocks: rigid on the fist until it bursts, then each flies out, tumbling and falling, and crumbles away. */
	private static void fistRocks(FxKit kit, RockLedger.Leash leash, Fist fist, BlockPos target, int height, BlockState surface, int caster,
			RandomSource r) {
		Vec3 axis = Vec3.atBottomCenterOf(target);
		BlockPos light = BlockPos.containing(axis.x, target.getY() + height + 1.5, axis.z);
		float s = fist.s;
		for (Voxel v : fist()) {
			if (!kit.budget().ghostBlock()) return;
			BlockState state = look(v.kind(), surface);
			Vector3f local = new Vector3f(v.x() * s, v.y() * s, v.z() * s);
			Vector3f fromCore = new Vector3f(v.x(), v.y() - 3F, v.z());
			if (fromCore.lengthSquared() < 1.0E-4F) fromCore.set(0, 1, 0);
			fromCore.normalize();
			double speed = 0.16 + 0.18 * r.nextDouble();
			double lift = 0.1 + 0.18 * r.nextDouble();
			float spinA = (r.nextFloat() - 0.5F) * 40F;
			float spinB = (r.nextFloat() - 0.5F) * 40F;
			float size = v.size() * s;
			EarthStones.add(caster, leash, state, SHATTER_AT + SHATTER_LIFE, (t, out) -> {
				double tt = Math.min(t, SHATTER_AT);
				float yaw = fist.yaw(tt);
				float roll = fist.roll(tt);
				Quaternionf q = new Quaternionf().rotationY(yaw * Mth.DEG_TO_RAD).rotateX(LEAN * Mth.DEG_TO_RAD).rotateZ(roll * Mth.DEG_TO_RAD);
				Vector3f o = q.transform(new Vector3f(local));
				Vec3 at = new Vec3(axis.x + o.x, fist.base(tt) + o.y, axis.z + o.z);
				out.light = light;
				out.glow = GLOW;
				out.yaw = yaw;
				out.pitch = LEAN;
				out.roll = roll;
				out.scale = size;
				if (t <= SHATTER_AT) {
					out.at(at);
					return true;
				}
				double f = t - SHATTER_AT;
				Vector3f dir = q.transform(new Vector3f(fromCore));
				Vec3 p = at.add(dir.x * speed * f, (dir.y * speed + lift) * f - 0.045 * f * f, dir.z * speed * f);
				if (p.y < fist.ground - 0.4) return false;
				out.at(p);
				out.yaw = yaw + spinA * (float) f;
				out.pitch = LEAN + spinB * (float) f;
				out.scale = size * Mth.clamp((SHATTER_LIFE - (float) f) / 7F, 0F, 1F);
				return true;
			});
		}
	}

	/** The eruption: crumbs, dust and smoke blasting up, a ring of dust pillars, a boom, the big shake and the hit-stop. */
	private static void erupt(FxKit kit, ClientLevel level, EarthFx.Share share, Vec3 foot, BlockState surface, int baseRadius) {
		Vec3 at = foot.add(0, 0.3, 0);
		EarthFx.spray(level, EarthFx.crumbs(surface), at, share.spend(kit, 100), 1.4, 0.55, true);
		EarthFx.spray(level, EarthFx.crumbs(Blocks.STONE.defaultBlockState()), at, share.spend(kit, 50), 1.0, 0.45, true);
		EarthFx.spray(level, EarthFx.DUST, at.add(0, 0.5, 0), share.spend(kit, 40), baseRadius * 0.6, 0.12, true);
		// A cloud of dust and smoke blown out round the base, big puffs where the ground bursts.
		EarthFx.spray(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, at.add(0, 0.6, 0), share.spend(kit, 36), baseRadius * 0.9, 0.06, true);
		EarthFx.spray(level, ParticleTypes.POOF, at, share.spend(kit, 20), baseRadius * 0.6, 0.14, true);
		int puffs = share.spend(kit, 10);
		for (int i = 0; i < puffs; i++) {
			double a = Mth.TWO_PI * i / Math.max(1, puffs);
			level.addParticle(ParticleTypes.EXPLOSION, foot.x + Math.cos(a) * baseRadius * 0.8, foot.y + 0.8, foot.z + Math.sin(a) * baseRadius * 0.8, 0, 0, 0);
		}
		ParticleOptions pillar = new BlockParticleOption(ParticleTypes.DUST_PILLAR, surface);
		kit.ring(foot.add(0, 0.05, 0), baseRadius, pillar, share.take(34));
		kit.ring(foot.add(0, 0.05, 0), baseRadius + 1, pillar, share.take(26));
		// Glowing cracks: earth light welling up out of the ground (it reads at night too).
		kit.motes(foot.add(0, 0.4, 0), EarthFx.CLAY, share.take(50), baseRadius * 0.8, new Vec3(0, 0.08, 0), 0.06, GlowMote.Drift.RISE, 0.34F, 24);
		play(level, foot, SoundEvents.WARDEN_EMERGE, 2.5F, 1.1F);
		play(level, foot, SoundEvents.GENERIC_EXPLODE.value(), 3F, 0.55F);
		play(level, foot, SoundEvents.MACE_SMASH_GROUND_HEAVY, 3F, 0.5F);
		play(level, foot, SoundEvents.ROOTED_DIRT_BREAK, 2F, 0.5F);
		float near = nearness(camera().distanceTo(foot));
		Shake.shake(SHAKE * near, 30);
		Shake.kick(0.1F * near);
		kit.hitStop(3);
	}

	/** The shockwave rolling out over the ground. */
	private static void shockwave(FxKit kit, EarthFx.Share share, Vec3 foot, BlockState surface, double radius) {
		kit.ring(foot.add(0, 0.05, 0), radius, new BlockParticleOption(ParticleTypes.DUST_PILLAR, surface), share.take(14));
		kit.ring(foot.add(0, 0.2, 0), radius, ParticleTypes.POOF, share.take(5));
	}

	/** Lumps of the ground and stone flung out of the eruption: they tumble, bounce once and settle. */
	private static void ejecta(FxKit kit, RockLedger.Leash leash, ClientLevel level, Vec3 foot, BlockState surface, int baseRadius, RandomSource r) {
		for (int i = 0; i < EJECTA; i++) {
			double a = Mth.TWO_PI * (i + r.nextDouble() * 0.6) / EJECTA;
			Vec3 out = new Vec3(Math.cos(a), 0, Math.sin(a));
			Vec3 from = foot.add(out.scale(baseRadius * 0.5)).add(0, 0.6, 0);
			Vec3 v = out.scale(0.22 + 0.2 * r.nextDouble()).add(0, 0.42 + 0.25 * r.nextDouble(), 0);
			BlockState s = i % 3 == 0 ? Blocks.STONE.defaultBlockState() : surface;
			EarthFx.chip(kit, leash, level, s, from, v, 0.45F + 0.3F * r.nextFloat(), 30 + r.nextInt(8));
		}
	}

	/** Ground plates round the base pry up on their outer edge and drop back. */
	private static void plates(FxKit kit, RockLedger.Leash leash, ClientLevel level, Predicate<BlockPos> solid, Set<Long> thrown, BlockPos target,
			int baseRadius, int caster, RandomSource r) {
		double turn = r.nextDouble() * Mth.TWO_PI;
		Set<Long> used = new HashSet<>();
		for (int i = 0; i < PLATES; i++) {
			double a = turn + Mth.TWO_PI * i / PLATES;
			int dx = (int) Math.round(Math.cos(a) * (baseRadius + 1));
			int dz = (int) Math.round(Math.sin(a) * (baseRadius + 1));
			long key = BlockPos.asLong(target.getX() + dx, 0, target.getZ() + dz);
			if (thrown.contains(key) || !used.add(key)) continue;
			BlockPos g = QuakeGeometry.surface(target.getX() + dx, target.getZ() + dz, target.getY() - 1, solid);
			if (g == null) continue;
			BlockState st = level.getBlockState(g);
			if (st.isAir() || !kit.budget().ghostBlock()) continue;
			float yaw = (float) Math.toDegrees(Math.atan2(dx, dz));
			float angle = 22F + 14F * r.nextFloat();
			int life = 14 + r.nextInt(5);
			Vec3 centre = Vec3.atCenterOf(g);
			Vector3f out0 = new Quaternionf().rotationY(yaw * Mth.DEG_TO_RAD).transform(new Vector3f(0, 0, 1));
			// The hinge: the top edge farthest from the fist.
			Vec3 hinge = centre.add(out0.x * 0.5, 0.5, out0.z * 0.5);
			EarthStones.add(caster, leash, st, life, (t, out) -> {
				float k = t < 2 ? t / 2F : Math.max(0F, 1F - (t - 2F) / (life - 2F));
				float pitch = angle * k * k * (3 - 2 * k);
				Vector3f arm = new Quaternionf().rotationY(yaw * Mth.DEG_TO_RAD).rotateX(pitch * Mth.DEG_TO_RAD).transform(new Vector3f(0, -0.5F, -0.5F));
				out.at(hinge.add(arm.x, arm.y, arm.z));
				out.yaw = yaw;
				out.pitch = pitch;
				out.scale = 1.004F;
				out.light = g.above();
				return true;
			});
		}
	}

	/** Dripstone fangs bursting out of the ground in a ring round the base, leaning out, sinking back later. */
	private static void fangs(FxKit kit, RockLedger.Leash leash, BlockPos target, int baseRadius, int caster, RandomSource r) {
		double turn = r.nextDouble() * Mth.TWO_PI;
		for (int i = 0; i < FANGS; i++) {
			double a = turn + Mth.TWO_PI * (i + 0.5) / FANGS;
			double rr = baseRadius + 0.6 + 0.5 * r.nextDouble();
			Vec3 at = Vec3.atBottomCenterOf(target).add(Math.cos(a) * rr, 0, Math.sin(a) * rr);
			float yaw = (float) Math.toDegrees(Math.atan2(Math.cos(a), Math.sin(a)));
			BlockPos cell = BlockPos.containing(at.x, target.getY() + 0.5, at.z);
			EarthSpikesFx.spike(kit, leash, caster, cell, at, EarthSpikesFx.TALL, 0.8F + 0.25F * r.nextFloat(), yaw, 24F + 12F * r.nextFloat(),
					0.5F + r.nextFloat(), 40 + r.nextInt(10));
		}
	}

	/** While it rises: grit pouring off it, crumbs at the spire's growing top, a glow in its cracks, a rumble. */
	private static void rising(FxKit kit, ClientLevel level, EarthFx.Share share, Fist fist, Vec3 foot, BlockState spireSurface, int t) {
		double wrist = fist.base(t);
		Vec3 low = new Vec3(foot.x, Math.max(fist.ground + 0.2, wrist + 0.5), foot.z);
		EarthFx.spray(level, new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.STONE.defaultBlockState()), low.add(0, 2 * fist.s, 0),
				share.spend(kit, 4), 1.8 * fist.s, 0.02, false);
		Vec3 spireTop = new Vec3(foot.x, fist.ground + MountainFistGeometry.topAt(t, fist.height), foot.z);
		BlockState crumb = t % 2 == 0 ? Blocks.STONE.defaultBlockState() : spireSurface;
		EarthFx.spray(level, EarthFx.crumbs(crumb), spireTop, share.spend(kit, 3), 1.2, 0.12, false);
		kit.motes(new Vec3(foot.x, wrist + 3 * fist.s, foot.z), EarthFx.CLAY, share.take(2), 1.6 * fist.s, Vec3.ZERO, 0.02, GlowMote.Drift.RISE,
				0.22F, 10);
		if (t % 3 == 0) {
			play(level, spireTop, SoundEvents.GRAVEL_BREAK, 1.4F, 0.5F + 0.01F * t);
			play(level, spireTop, SoundEvents.DEEPSLATE_BREAK, 1.0F, 0.55F);
		}
		if (t % 4 == 0) {
			float near = nearness(camera().distanceTo(foot));
			Shake.shake(0.22F * near, 6);
		}
	}

	/** At the top: the punch through the sky seal. */
	private static void top(FxKit kit, ClientLevel level, EarthFx.Share share, Fist fist, Vec3 foot, double peak) {
		Vec3 crown = new Vec3(foot.x, peak, foot.z);
		kit.ring(crown, 1.6 * fist.s, ParticleTypes.POOF, share.take(10));
		kit.motes(crown, EarthFx.CLAY, share.take(30), 1.4 * fist.s, new Vec3(0, 0.06, 0), 0.1, GlowMote.Drift.RISE, 0.32F, 16);
		EarthFx.spray(level, EarthFx.DUST, crown, share.spend(kit, 30), 1.6, 0.1, false);
		play(level, crown, SoundEvents.ANVIL_LAND, 1.6F, 0.5F);
		play(level, crown, SoundEvents.MACE_SMASH_AIR, 1.5F, 0.6F);
		Shake.shake(0.4F * nearness(camera().distanceTo(foot)), 10);
	}

	/** The fist bursts apart (its rocks fly from {@link #fistRocks}): crumbs, dust and smoke, a crack and a rumble. */
	private static void burst(FxKit kit, ClientLevel level, EarthFx.Share share, Fist fist, Vec3 foot, BlockState surface) {
		Vec3 c = new Vec3(foot.x, fist.base(SHATTER_AT) + 3 * fist.s, foot.z);
		double spread = 1.8 * fist.s;
		EarthFx.spray(level, EarthFx.crumbs(Blocks.STONE.defaultBlockState()), c, share.spend(kit, 150), spread, 0.38, false);
		EarthFx.spray(level, EarthFx.crumbs(Blocks.ANDESITE.defaultBlockState()), c, share.spend(kit, 60), spread, 0.34, false);
		EarthFx.spray(level, EarthFx.crumbs(surface), c.add(0, 2 * fist.s, 0), share.spend(kit, 40), spread, 0.3, false);
		EarthFx.spray(level, EarthFx.DUST, c, share.spend(kit, 60), spread, 0.12, false);
		EarthFx.spray(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, c, share.spend(kit, 20), spread, 0.04, true);
		EarthFx.spray(level, ParticleTypes.POOF, c, share.spend(kit, 20), spread, 0.15, false);
		kit.motes(c, EarthFx.CLAY, share.take(30), spread, Vec3.ZERO, 0.18, GlowMote.Drift.FALL, 0.26F, 16);
		play(level, c, SoundEvents.STONE_BREAK, 2.5F, 0.5F);
		play(level, c, SoundEvents.DEEPSLATE_BREAK, 2.5F, 0.6F);
		play(level, c, SoundEvents.GRAVEL_BREAK, 2F, 0.7F);
		play(level, c, SoundEvents.GENERIC_EXPLODE.value(), 1.5F, 0.9F);
		Shake.shake(0.5F * nearness(camera().distanceTo(foot)), 12);
	}

	/** Dust drifting off the new spire and round its foot. */
	private static void settle(FxKit kit, ClientLevel level, EarthFx.Share share, Vec3 foot, int baseRadius, int height) {
		RandomSource r = level.getRandom();
		Vec3 side = foot.add((r.nextDouble() - 0.5) * baseRadius * 2, 0.3 + r.nextDouble() * height * 0.5, (r.nextDouble() - 0.5) * baseRadius * 2);
		EarthFx.spray(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, side, share.spend(kit, 1), 0.5, 0.01, true);
		EarthFx.spray(level, EarthFx.DUST, side, share.spend(kit, 2), baseRadius * 0.5, 0.02, true);
		EarthFx.spray(level, EarthFx.crumbs(Blocks.STONE.defaultBlockState()), foot.add(0, height * 0.6, 0), share.spend(kit, 2), 1.0, 0.04, false);
	}

	private static Vec3 camera() {
		return Minecraft.getInstance().gameRenderer.mainCamera().position();
	}

	private static void play(ClientLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
		level.playLocalSound(at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch, false);
	}
}
