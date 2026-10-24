package local.carter.office.client.magic.fx.earth;

import java.util.List;
import java.util.function.Predicate;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.client.magic.fx.SpellFx;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.earth.EarthSpikes;
import local.carter.office.magic.spell.earth.QuakeGeometry;
import local.carter.office.magic.spell.earth.QuakeGeometry.Column;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Quake: the caster stamps, an earth seal flashes on the ground under them with a heavy thud and a jolt, and a wave runs out
 * through the ground: the real ground blocks heave up as the ring reaches them, tilting outward like a crest, and drop back,
 * with a rolling ring of dust pillars and crumbs at the front and a low rumble. The ring is {@link QuakeGeometry}'s, reached
 * column by column as the server's is; creatures thrown and blocks cracked are the server's own. Every heaving block is a
 * client rock ({@link EarthStones}) on one cancellable timer's leash.
 */
public final class QuakeFx implements SpellFx {
	/** Ticks a reached block takes to heave up and drop back. */
	private static final float HEAVE = 6F;
	/** Most heaving blocks per cast (the event allows 128 ghost blocks). */
	static final int HEAVES = 120;
	/** Particles for the stamp, the rolling front, and crumbs over the whole wave. */
	static final int STAMP = 40;
	static final int FRONT = 230;
	static final int CRUMB_CAP = 70;

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		float c = Mth.clamp(e.charge(), 0F, 1F);
		float[] d = e.data();
		double radius = d.length > 0 && d[0] > 0 ? d[0] : QuakeGeometry.radius(c);
		BlockPos centre = BlockPos.containing(e.target()).below();
		Vec3 stamp = Vec3.atBottomCenterOf(centre.above());
		List<Column> disc = QuakeGeometry.disc(radius);
		Predicate<BlockPos> solid = EarthSpikes.solid(level);
		RandomSource r = kit.random();
		BlockState under = level.getBlockState(centre);
		BlockState floor = under.isAir() ? EarthFx.COBBLE : under;

		// The stamp.
		kit.circle(stamp.add(0, 0.06, 0), new Vec3(0, 1, 0), EarthFx.ARGB, 1.6F + 0.4F * c, 3 + Math.round(2 * c), 16);
		kit.ring(stamp.add(0, 0.05, 0), 0.9, new BlockParticleOption(ParticleTypes.DUST_PILLAR, floor), 12);
		kit.burst(stamp.add(0, 0.2, 0), EarthFx.crumbs(floor), 14, 0.2);
		kit.burst(stamp.add(0, 0.3, 0), EarthFx.DUST, 6, 0.06);
		kit.motes(stamp.add(0, 0.1, 0), EarthFx.CLAY, 6, 0.6, new Vec3(0, 0.02, 0), 0.03, GlowMote.Drift.RISE, 0.2F, 12);
		level.playLocalSound(stamp.x, stamp.y, stamp.z, SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.PLAYERS, 1.0F, 0.65F, false);
		level.playLocalSound(stamp.x, stamp.y, stamp.z, SoundEvents.RAVAGER_STEP, SoundSource.PLAYERS, 1.0F, 0.5F, false);
		kit.shake(0.35F + 0.2F * c, 12);

		// Which columns heave: as many as the ghost budget allows, picked evenly (seeded) over the disc.
		float share = Math.min(1F, HEAVES / (float) Math.max(1, disc.size()));
		boolean[] heave = new boolean[disc.size()];
		for (int i = 0; i < disc.size(); i++) heave[i] = r.nextFloat() < share;

		EarthFx.Share front = new EarthFx.Share(FRONT);
		EarthFx.Crumbs crumbs = new EarthFx.Crumbs(kit, CRUMB_CAP);
		int caster = e.casterId();
		int duration = QuakeGeometry.duration(radius);
		int[] next = {0};
		RockLedger.Leash leash = new RockLedger.Leash(EarthStones.now());
		EarthFx.drive(kit, leash, duration + Math.round(HEAVE) + 2, t -> {
			// The server's ring reaches tick t + 1 of its effect as this client draws tick t.
			int age = t + 1;
			while (next[0] < disc.size() && QuakeGeometry.tickOf(disc.get(next[0]).dist()) <= age) {
				Column col = disc.get(next[0]);
				BlockPos ground = QuakeGeometry.surface(centre.getX() + col.dx(), centre.getZ() + col.dz(), centre.getY(), solid);
				if (ground != null) reach(kit, leash, crumbs, level, ground, col, radius, heave[next[0]], caster, r);
				next[0]++;
			}
			if (age > duration) return;
			double ring = QuakeGeometry.ringAt(age);
			BlockState at = level.getBlockState(BlockPos.containing(stamp.x + ring, stamp.y - 0.5, stamp.z));
			if (at.isAir()) at = floor;
			int n = front.take(4 + (int) Math.round(Mth.TWO_PI * ring * 0.55));
			kit.ring(stamp.add(0, 0.05, 0), ring, new BlockParticleOption(ParticleTypes.DUST_PILLAR, at), n);
			if (age % 3 == 1) {
				level.playLocalSound(stamp.x, stamp.y, stamp.z, SoundEvents.ROOTED_DIRT_BREAK, SoundSource.PLAYERS, 0.8F, 0.45F + 0.03F * age, false);
				level.playLocalSound(stamp.x, stamp.y, stamp.z, SoundEvents.GRAVEL_BREAK, SoundSource.PLAYERS, 0.6F, 0.5F, false);
			}
		});
	}

	/** The ring reaches the ground block {@code ground}: it heaves up, tilting outward, and drops back; a crumb or two. */
	private static void reach(FxKit kit, RockLedger.Leash leash, EarthFx.Crumbs crumbs, ClientLevel level, BlockPos ground, Column col,
			double radius, boolean heave, int caster, RandomSource r) {
		BlockState s = level.getBlockState(ground);
		if (s.isAir()) return;
		Vec3 top = Vec3.atBottomCenterOf(ground.above());
		if (r.nextInt(3) == 0) crumbs.burst(top.add(0, 0.05, 0), s, 1, 0.12);
		if (!heave || !kit.budget().ghostBlock()) return;
		// Higher near the stamp, lower at the edge.
		double amp = 0.22 + 0.33 * (1 - col.dist() / Math.max(1, radius));
		float yaw = col.dist() < 0.5 ? r.nextFloat() * 360F : (float) Math.toDegrees(Math.atan2(col.dx(), col.dz()));
		float tilt = 9F + 6F * r.nextFloat();
		Vec3 home = Vec3.atCenterOf(ground);
		EarthStones.add(caster, leash, s, Math.round(HEAVE) + 1, (t, out) -> {
			float k = Mth.clamp(t / HEAVE, 0F, 1F);
			double lift = Math.sin(Math.PI * k);
			out.at(home.add(0, amp * lift, 0));
			out.yaw = yaw;
			// The inner edge comes up first: the block leans outward on the crest.
			out.pitch = (float) (tilt * Math.sin(Math.PI * k) * (1 - k));
			out.scale = 1.01F;
			out.light = ground.above();
			return true;
		});
	}
}
