package local.carter.office.client.magic.fx.fire;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.client.magic.fx.RankFx;
import local.carter.office.client.magic.fx.Shake;
import local.carter.office.client.magic.fx.SpellFx;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.fire.Meteor;
import local.carter.office.magic.spell.fire.MeteorGeometry;
import local.carter.office.magic.tree.SpellRank;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
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
 * Meteor's visuals, built from {@link MeteorGeometry} exactly as the server drops it. At the cast two fire circles open in the
 * sky where the rock will come from, facing down its fall line, and a warning seal opens on the ground the size of the crater.
 * A rough burning rock of block models ({@link MeteorRock}) then comes out of the sky circle and falls on the spot, wrapped in a
 * glow and shedding flame sheets, flames, smoke and lava, the ground rumbling more as it nears. On impact: a white flash, a pillar of
 * fire shooting straight up, a fireball dome of flame and lava, a mushroom of smoke, a shockwave of dust rolling out over the crater's rim, chunks of the ground,
 * a deep boom, shake 1.0 for everyone near (still 0.8 at 32 blocks) and a 3-tick hit-stop; then the crater smoulders. The
 * flying debris and the crater itself are the server's real blocks.
 *
 * <p>The ultimate's 1500 particles are split into allowances (cast, fall, impact, smoulder), so the fall can never starve the
 * impact.
 */
public final class MeteorFx implements SpellFx {
	private static final Vec3 UP = new Vec3(0, 1, 0);
	/** Deep red for the ground seal and smoulder. */
	private static final int MAGMA = 0xFF2A08;
	/** Shake strength at the impact, full within {@link #SHAKE_FULL} blocks, still ≥ 0.8 at 32, none past {@link #SHAKE_NONE}. */
	private static final float SHAKE = 1F;
	private static final double SHAKE_FULL = 24;
	private static final double SHAKE_NONE = 64;
	private static final int SMOULDER_TICKS = 70;

	/** Particles each phase may spend, together the ultimate's whole budget. */
	static final int CAST = 60;
	static final int FALL = 640;
	static final int IMPACT = 640;
	static final int SMOULDER = 160;

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		float[] data = e.data();
		int radius = data.length > 0 ? Math.round(data[0]) : MeteorGeometry.BASE_RADIUS;
		double lift = data.length > 1 ? data[1] : 1;
		MeteorGeometry g = MeteorGeometry.of(e.target(), e.dir(), lift, radius);
		RandomSource r = kit.random();
		TextureAtlasSprite fire = Minecraft.getInstance().getModelManager().getBlockStateModelSet()
				.getParticleMaterial(Blocks.FIRE.defaultBlockState()).sprite();
		Allowance cast = new Allowance(CAST);
		Allowance fall = new Allowance(FALL);
		Allowance impact = new Allowance(IMPACT);
		Allowance smoulder = new Allowance(SMOULDER);

		// The sky opens: two circles where the rock comes from, facing down its fall line, and a seal on the ground.
		Vec3 heading = g.heading();
		kit.circle(g.start(), heading, 0xFF000000 | FireBoltFx.FIRE, (float) (radius * 0.9 + 1), 5, MeteorGeometry.FALL_TICKS + 8);
		kit.circle(g.start().add(heading.scale(1.5)), heading, 0xFF000000 | FireBoltFx.HOT, (float) (radius * 0.45 + 0.5), 3,
				MeteorGeometry.FALL_TICKS + 4);
		kit.circle(g.impact().add(0, 0.06, 0), UP, 0xFF000000 | MAGMA, radius + 0.5F, 4, MeteorGeometry.FALL_TICKS + 6);
		kit.motes(g.start(), FireBoltFx.HOT, cast.take(40), radius * 0.6, heading.scale(0.1), 0.08, GlowMote.Drift.COAST, 0.3F, 20);
		kit.ring(g.impact().add(0, 0.1, 0), radius, ParticleTypes.FLAME, cast.take(20));
		Vec3 s = g.start();
		play(level, s, SoundEvents.BEACON_ACTIVATE, 4F, 0.5F);
		play(level, s, SoundEvents.FIRECHARGE_USE, 4F, 0.45F);
		play(level, s, SoundEvents.BLAZE_SHOOT, 3F, 0.4F);
		play(level, g.impact(), SoundEvents.BEACON_POWER_SELECT, 1.5F, 0.5F);

		MeteorRock rock = MeteorRock.spawn(kit, g, r);
		int life = MeteorGeometry.FALL_TICKS + SMOULDER_TICKS;
		kit.simulate(1, life, t -> {
			if (t < MeteorGeometry.FALL_TICKS) {
				if (rock != null) rock.keep(t);
				falling(level, kit, g, r, fire, fall, t);
			} else if (t == MeteorGeometry.FALL_TICKS) {
				impact(level, kit, g, r, fire, impact);
				forks(level, kit, e, g);
			} else {
				aftermath(level, kit, g, r, fire, impact, smoulder, t - MeteorGeometry.FALL_TICKS);
			}
		});
	}

	/** The forks' part at the impact: Scorched Earth's burning ground, Shockwave's ring, Cluster's fragments. */
	private static void forks(ClientLevel level, FxKit kit, FxEvent e, MeteorGeometry g) {
		SpellRank rank = e.spellRank();
		Vec3 at = g.impact();
		if (rank.is(Meteor.SCORCHED)) RankFx.zone(kit, level, at, at, Meteor.SCORCH_RADIUS, Meteor.SCORCH_TICKS, FireBoltFx.HOT, ParticleTypes.FLAME,
				GlowMote.Drift.RISE);
		if (rank.is(Meteor.SHOCKWAVE)) RankFx.shockRing(kit, level, at.add(0, 0.2, 0), Meteor.blast(rank), 10, FireBoltFx.HOT, ParticleTypes.FLAME);
		if (!rank.is(Meteor.CLUSTER)) return;
		// Cluster: burning fragments arc out of the crater and burst where they land.
		for (Vec3 f : MeteorGeometry.fragments(at, e.seed())) {
			Vec3 to = f.add(0, 0.5, 0);
			kit.simulate(1, MeteorGeometry.FRAGMENT_TICKS + 1, t -> {
				double k = t / (double) MeteorGeometry.FRAGMENT_TICKS;
				Vec3 p = at.lerp(to, k).add(0, 4 * k * (1 - k) * 4, 0);
				kit.motes(p, FireBoltFx.HOT, 2, 0.1, Vec3.ZERO, 0.02, GlowMote.Drift.COAST, 0.3F, 4);
				level.addParticle(ParticleTypes.FLAME, p.x, p.y, p.z, 0, 0.02, 0);
				if (t == MeteorGeometry.FRAGMENT_TICKS) RankFx.blast(kit, level, to, Meteor.FRAGMENT_RADIUS, FireBoltFx.HOT, ParticleTypes.LAVA);
			});
		}
	}

	/** One tick of the fall: the glow round the rock, flame sheets and flames streaming off it, smoke, lava, the rumble. */
	private static void falling(ClientLevel level, FxKit kit, MeteorGeometry g, RandomSource r, TextureAtlasSprite fire, Allowance a, int t) {
		Vec3 at = g.position(t);
		Vec3 back = g.heading().reverse();
		double rr = MeteorRock.RADIUS;
		// The glow it burns in: a wide dim halo and a hot core riding with it, and a comet tail of fading glows along its path.
		if (a.take(4) == 4) {
			FireBoltFx.glow(kit, level, at.add(back.scale(0.6)), g.velocity(), FireBoltFx.HALO, (float) (rr * 2.0), 3, GlowMote.Drift.COAST);
			FireBoltFx.glow(kit, level, at, g.velocity(), FireBoltFx.HOT, (float) (rr * 1.4), 2, GlowMote.Drift.COAST);
			FireBoltFx.glow(kit, level, at.add(back.scale(rr + 1.5)), Vec3.ZERO, FireBoltFx.FIRE, (float) (rr * 1.3), 6, GlowMote.Drift.COAST);
			FireBoltFx.glow(kit, level, at.add(back.scale(rr + 4)), Vec3.ZERO, FireBoltFx.EMBER, (float) (rr * 0.9), 8, GlowMote.Drift.COAST);
		}
		// Big flame sheets on its back half: they stay where they were lit, so behind the rock they read as a mane of fire.
		for (int i = 0; i < 5 && a.spend(kit, 1) == 1; i++) {
			Vec3 on = surface(r, back, rr * 0.9).add(at).add(back.scale(r.nextDouble() * 1.5));
			float size = 1.0F + 0.8F * r.nextFloat();
			Minecraft.getInstance().particleEngine.add(new FireWallFx.Flame(level, on, fire, size, 6 + r.nextInt(3), r.nextBoolean(),
					r.nextFloat() * Mth.TWO_PI, 0.55F + 0.2F * r.nextFloat(), 0F));
		}
		for (int i = 0; i < 3 && a.spend(kit, 1) == 1; i++) {
			Vec3 on = surface(r, back, rr).add(at);
			Vec3 v = back.scale(0.12 + 0.1 * r.nextDouble()).add(FireBoltFx.jitter(r, 0.05), 0.04, FireBoltFx.jitter(r, 0.05));
			level.addParticle(i < 3 ? ParticleTypes.FLAME : ParticleTypes.SMALL_FLAME, on.x, on.y, on.z, v.x, v.y, v.z);
		}
		for (int i = 0; i < 2 && a.spend(kit, 1) == 1; i++) {
			Vec3 on = surface(r, back, rr * 0.8).add(at).add(back.scale(2.5));
			Vec3 v = back.scale(0.04).add(FireBoltFx.jitter(r, 0.02), 0.02, FireBoltFx.jitter(r, 0.02));
			level.addParticle(ParticleTypes.LARGE_SMOKE, on.x, on.y, on.z, v.x, v.y, v.z);
		}
		if ((t & 1) == 0 && a.spend(kit, 1) == 1) {
			Vec3 on = surface(r, back, rr).add(at);
			level.addParticle(ParticleTypes.LAVA, on.x, on.y, on.z, 0, 0, 0);
		}
		kit.motes(at.add(back.scale(rr)), FireBoltFx.HOT, a.take(2), rr * 0.5, back.scale(0.08), 0.05, GlowMote.Drift.RISE, 0.12F, 16);
		// The rumble grows as it nears, for everyone round the spot.
		double d = camera().distanceTo(g.impact());
		if (t % 5 == 0 && d < SHAKE_NONE) Shake.shake((float) (0.05 + 0.2 * t / MeteorGeometry.FALL_TICKS) * nearness(d), 6);
		if (t % 8 == 0) play(level, at, SoundEvents.FIRE_AMBIENT, 4F, 0.5F + 0.02F * t);
		if (t == MeteorGeometry.FALL_TICKS - 14) play(level, at, SoundEvents.GHAST_SHOOT, 3F, 0.4F);
	}

	/** The landing: flash, fireball, smoke, debris, boom, shake and hit-stop. */
	private static void impact(ClientLevel level, FxKit kit, MeteorGeometry g, RandomSource r, TextureAtlasSprite fire, Allowance a) {
		Vec3 at = g.impact();
		Vec3 core = at.add(0, 1.2, 0);
		int radius = g.radius();
		// The flash (a few big additive glows: for a few ticks it whites out round the spot, then burns down orange).
		if (a.take(5) == 5) {
			// (Each kept under ~8 blocks: the glow sprite is small, and drawn bigger its texels show as steps.)
			FireBoltFx.glow(kit, level, core, Vec3.ZERO, FireBoltFx.CORE, Math.min(8F, radius * 0.9F), 4, GlowMote.Drift.COAST);
			FireBoltFx.glow(kit, level, core, Vec3.ZERO, FireBoltFx.HOT, Math.min(7F, radius * 0.75F), 9, GlowMote.Drift.COAST);
			FireBoltFx.glow(kit, level, core.add(radius * 0.4, 1.5, 0), Vec3.ZERO, FireBoltFx.HALO, Math.min(7F, radius * 0.7F), 16, GlowMote.Drift.COAST);
			FireBoltFx.glow(kit, level, core.add(-radius * 0.4, 1.5, 0), Vec3.ZERO, FireBoltFx.HALO, Math.min(7F, radius * 0.7F), 16, GlowMote.Drift.COAST);
			FireBoltFx.glow(kit, level, core.add(0, 3, 0), new Vec3(0, 0.12, 0), FireBoltFx.FIRE, Math.min(6F, radius * 0.6F), 18, GlowMote.Drift.RISE);
		}
		// The pillar: a column of fire and light shooting straight up out of the hit, so the impact reads at any distance.
		int pillar = a.take(8);
		for (int i = 0; i < pillar; i++) {
			double h = 1 + i * radius * 0.28;
			FireBoltFx.glow(kit, level, at.add(0, h, 0), new Vec3(0, 0.25, 0), i < 2 ? FireBoltFx.CORE : i < 5 ? FireBoltFx.HOT : FireBoltFx.FIRE,
					Math.max(1.6F, 4.2F - 0.35F * i), 6 + i, GlowMote.Drift.RISE);
		}
		int jet = a.spend(kit, 14);
		for (int i = 0; i < jet; i++) {
			float size = 1.4F + 1.0F * r.nextFloat();
			Vec3 p = at.add(FireBoltFx.jitter(r, 1.2), size + i * radius * 0.16, FireBoltFx.jitter(r, 1.2));
			Minecraft.getInstance().particleEngine.add(new FireWallFx.Flame(level, p, fire, size, 8 + r.nextInt(8), r.nextBoolean(),
					r.nextFloat() * Mth.TWO_PI, 0.6F + 0.15F * r.nextFloat(), 0F));
		}
		// The fireball: a dome of flame sheets bursting out of the ground over the whole crater, flames and lava thrown up and out.
		int sheets = a.spend(kit, 60);
		for (int i = 0; i < sheets; i++) {
			double ang = r.nextDouble() * Mth.TWO_PI;
			double rad = Math.sqrt(r.nextDouble()) * radius;
			double rise = (1 - rad / radius) * radius * 0.5 * r.nextDouble();
			float size = (float) (1.0 + 1.6 * (1 - rad / radius) + 0.5 * r.nextDouble());
			Vec3 p = at.add(Math.cos(ang) * rad, rise + size - 1.2, Math.sin(ang) * rad);
			Minecraft.getInstance().particleEngine.add(new FireWallFx.Flame(level, p, fire, size, 18 + r.nextInt(26), r.nextBoolean(),
					r.nextFloat() * Mth.TWO_PI, 0.5F + 0.25F * r.nextFloat(), 0F));
		}
		spray(level, r, a.spend(kit, 100), ParticleTypes.FLAME, core, 0.25, 0.85);
		spray(level, r, a.spend(kit, 36), ParticleTypes.LAVA, core, 0.2, 0.6);
		kit.motes(core, FireBoltFx.HOT, a.take(40), radius * 0.3, new Vec3(0, 0.15, 0), 0.5, GlowMote.Drift.COAST, 0.22F, 22);
		kit.motes(core, FireBoltFx.EMBER, a.take(30), radius * 0.5, new Vec3(0, 0.1, 0), 0.25, GlowMote.Drift.RISE, 0.12F, 50);
		// A mushroom of smoke: a rising column and a cap spreading at its top.
		int column = a.spend(kit, 40);
		for (int i = 0; i < column; i++) {
			// Signal smoke climbs high and hangs for seconds: the column. Large smoke bursts round its foot.
			boolean tall = i % 3 == 0;
			double spread = tall ? radius * 0.35 : 1.5;
			Vec3 p = at.add(FireBoltFx.jitter(r, spread), r.nextDouble() * 2, FireBoltFx.jitter(r, spread));
			level.addParticle(tall ? ParticleTypes.CAMPFIRE_SIGNAL_SMOKE : ParticleTypes.LARGE_SMOKE, true, true, p.x, p.y, p.z,
					FireBoltFx.jitter(r, 0.03), tall ? 0.08 + 0.06 * r.nextDouble() : 0.18 + 0.2 * r.nextDouble(), FireBoltFx.jitter(r, 0.03));
		}
		int cap = a.spend(kit, 30);
		for (int i = 0; i < cap; i++) {
			double ang = r.nextDouble() * Mth.TWO_PI;
			Vec3 p = at.add(0, 5 + r.nextDouble() * 2, 0);
			level.addParticle(ParticleTypes.LARGE_SMOKE, p.x, p.y, p.z, Math.cos(ang) * 0.2, 0.04, Math.sin(ang) * 0.2);
		}
		// Chunks of the ground (the blocks there now: the server's crater lands a tick or two later).
		BlockState ground = level.getBlockState(BlockPos.containing(at.x, at.y - 0.5, at.z));
		if (ground.isAir()) ground = Blocks.STONE.defaultBlockState();
		spray(level, r, a.spend(kit, 60), new BlockParticleOption(ParticleTypes.BLOCK, ground), at.add(0, 0.5, 0), 0.3, 0.9);

		play(level, at, SoundEvents.GENERIC_EXPLODE.value(), 8F, 0.5F);
		play(level, at, SoundEvents.DRAGON_FIREBALL_EXPLODE, 6F, 0.55F);
		play(level, at, SoundEvents.MACE_SMASH_GROUND_HEAVY, 4F, 0.6F);
		play(level, at, SoundEvents.LIGHTNING_BOLT_IMPACT, 3F, 0.5F);

		double d = camera().distanceTo(at);
		float near = nearness(d);
		Shake.shake(SHAKE * near, 30);
		Shake.kick(0.12F * near);
		kit.hitStop(3);
	}

	/** After the landing: the shockwave rolls out, then the crater smoulders. */
	private static void aftermath(ClientLevel level, FxKit kit, MeteorGeometry g, RandomSource r, TextureAtlasSprite fire, Allowance wave,
			Allowance smoulder, int k) {
		Vec3 at = g.impact();
		int radius = g.radius();
		if (k <= 10) {
			// The shockwave: a ring of dust and flame running out past the rim, low over the ground.
			double ringR = 1 + (radius + 4) * k / 10.0;
			kit.ring(at.add(0, 0.3, 0), ringR, ParticleTypes.POOF, wave.take(12));
			kit.ring(at.add(0, 0.2, 0), ringR * 0.92, ParticleTypes.FLAME, wave.take(6));
			if (k == 1) play(level, at, SoundEvents.GENERIC_EXPLODE.value(), 4F, 0.4F);
		}
		// Fires left burning on the crater floor (the bowl's floor: deepest at the middle).
		if (k % 3 == 0 && k < SMOULDER_TICKS - 20 && smoulder.spend(kit, 1) == 1) {
			double ang = r.nextDouble() * Mth.TWO_PI;
			double rad = Math.sqrt(r.nextDouble()) * radius * 0.85;
			double floor = -radius * MeteorGeometry.DEPTH * (1 - (rad / radius) * (rad / radius));
			float size = 0.5F + 0.4F * r.nextFloat();
			Vec3 p = at.add(Math.cos(ang) * rad, floor + size, Math.sin(ang) * rad);
			Minecraft.getInstance().particleEngine.add(new FireWallFx.Flame(level, p, fire, size, 24 + r.nextInt(20), r.nextBoolean(),
					r.nextFloat() * Mth.TWO_PI, 0.7F, 0F));
		}
		// Smoulder: smoke and embers off the crater floor, thinning out.
		if (k % 2 == 0 && k < SMOULDER_TICKS) {
			double fade = 1 - k / (double) SMOULDER_TICKS;
			int n = smoulder.spend(kit, fade > 0.5 ? 3 : 2);
			for (int i = 0; i < n; i++) {
				double ang = r.nextDouble() * Mth.TWO_PI;
				double rad = Math.sqrt(r.nextDouble()) * radius * 0.85;
				Vec3 p = at.add(Math.cos(ang) * rad, -radius * 0.45 + r.nextDouble(), Math.sin(ang) * rad);
				ParticleOptions o = i == 0 ? ParticleTypes.LARGE_SMOKE : i == 1 ? ParticleTypes.SMOKE : ParticleTypes.FLAME;
				level.addParticle(o, p.x, p.y, p.z, 0, 0.05 + 0.05 * r.nextDouble(), 0);
			}
			if (k % 6 == 0) {
				double ang = r.nextDouble() * Mth.TWO_PI;
				Vec3 p = at.add(Math.cos(ang) * radius * 0.5, -radius * 0.4, Math.sin(ang) * radius * 0.5);
				kit.motes(p, MAGMA, smoulder.take(2), 1.2, new Vec3(0, 0.05, 0), 0.03, GlowMote.Drift.RISE, 0.12F, 30);
			}
			if (k % 20 == 0) play(level, at, SoundEvents.LAVA_AMBIENT, 2F, 0.8F);
		}
	}

	/** {@code n} particles from {@code at}, thrown up and out at {@code min}..{@code max} blocks a tick. */
	private static void spray(ClientLevel level, RandomSource r, int n, ParticleOptions p, Vec3 at, double min, double max) {
		for (int i = 0; i < n; i++) {
			double ang = r.nextDouble() * Mth.TWO_PI;
			double up = 0.3 + 0.7 * r.nextDouble();
			double speed = min + (max - min) * r.nextDouble();
			double flat = Math.sqrt(1 - up * up);
			level.addParticle(p, at.x, at.y, at.z, Math.cos(ang) * flat * speed, up * speed, Math.sin(ang) * flat * speed);
		}
	}

	/** A random point on the rock's surface ({@code radius} out), on the half facing {@code back}. */
	private static Vec3 surface(RandomSource r, Vec3 back, double radius) {
		double z = r.nextDouble() * 2 - 1;
		double ang = r.nextDouble() * Mth.TWO_PI;
		double s = Math.sqrt(1 - z * z);
		Vec3 v = new Vec3(s * Math.cos(ang), z, s * Math.sin(ang));
		if (v.dot(back) < 0) v = v.scale(-1);
		return v.scale(radius);
	}

	/** 1 within {@link #SHAKE_FULL} of the impact, linear to 0 at {@link #SHAKE_NONE} (0.8 at 32). */
	static float nearness(double d) {
		return (float) Mth.clamp((SHAKE_NONE - d) / (SHAKE_NONE - SHAKE_FULL), 0, 1);
	}

	private static Vec3 camera() {
		return Minecraft.getInstance().gameRenderer.mainCamera().position();
	}

	private static void play(ClientLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
		level.playLocalSound(at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch, false);
	}
}
