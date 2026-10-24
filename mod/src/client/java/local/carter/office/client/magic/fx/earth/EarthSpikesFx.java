package local.carter.office.client.magic.fx.earth;

import java.util.ArrayList;
import java.util.List;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.client.magic.fx.SpellFx;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.earth.EarthSpikes;
import local.carter.office.magic.spell.earth.EarthSpikesGeometry;
import local.carter.office.magic.spell.earth.EarthWallGeometry;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SpeleothemThickness;
import net.minecraft.world.phys.Vec3;

/**
 * Earth Spikes: a small earth seal opens on the ground ahead, then a line of dripstone spikes stabs up out of the ground, one a
 * tick, each a tall spike with two short ones leaning out beside it and a cracked lump of the ground at its foot, in a burst of
 * the ground's crumbs and dust with a sharp crack. They stand {@link EarthSpikesGeometry#LIFE} ticks and sink back into the ground.
 * No block changes: every spike is a client rock ({@link EarthStones}) on the same line the server strikes along
 * ({@link EarthSpikesGeometry#path} on this client's world), all on one cancellable timer's leash.
 */
public final class EarthSpikesFx implements SpellFx {
	/** Ticks a spike takes to stab up, and to sink back. */
	private static final float RISE = 2.5F;
	private static final float SINK = 8F;
	private static final int LIFE = EarthSpikesGeometry.LIFE;
	/** Block crumbs for the whole cast, and those kept for the sinking (paid at the cast). */
	static final int CRUMB_CAP = 170;
	static final int SINK_CRUMBS = 4;
	/** Particles each spike draws besides crumbs (dust, dust pillars, a glow every third), and rocks per spike. */
	static final int PER_SPIKE = 6;
	static final int TALL_PARTS = 3;
	static final int SHORT_PARTS = 2;
	static final int ROCKS_PER_SPIKE = TALL_PARTS + 2 * SHORT_PARTS + 1;

	private static BlockState drip(SpeleothemThickness t) {
		return Blocks.POINTED_DRIPSTONE.defaultBlockState().setValue(BlockStateProperties.VERTICAL_DIRECTION, Direction.UP)
				.setValue(BlockStateProperties.SPELEOTHEM_THICKNESS, t);
	}

	static final BlockState[] TALL = {drip(SpeleothemThickness.BASE), drip(SpeleothemThickness.FRUSTUM), drip(SpeleothemThickness.TIP)};
	static final BlockState[] SHORT = {drip(SpeleothemThickness.FRUSTUM), drip(SpeleothemThickness.TIP)};

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		float c = Mth.clamp(e.charge(), 0F, 1F);
		float[] d = e.data();
		int length = d.length > 0 ? Math.round(d[0]) : EarthSpikesGeometry.length(c);
		Vec3 forward = EarthWallGeometry.flat(e.dir());
		BlockPos start = BlockPos.containing(e.target().add(0, 0.01, 0));
		// One line, or Spike Field's fan of three from the same first cell (as the server lays them).
		List<List<BlockPos>> paths = new ArrayList<>();
		List<Vec3> dirs = EarthSpikes.lines(forward, e.spellRank());
		for (Vec3 f : dirs) paths.add(EarthSpikesGeometry.path(start, f, length, EarthSpikes.solid(level)));
		List<BlockPos> path = paths.get(dirs.size() / 2);
		int cells = 0;
		for (List<BlockPos> p : paths) cells += p.size();
		EarthFx.Crumbs crumbs = new EarthFx.Crumbs(kit, CRUMB_CAP);
		int sinkCrumbs = crumbs.reserve(SINK_CRUMBS * cells);
		RandomSource r = kit.random();

		// The cast: a small earth seal on the ground where the line starts, a grinding crack.
		Vec3 seal = Vec3.atBottomCenterOf(path.isEmpty() ? start : path.get(0)).add(0, 0.06, 0);
		kit.circle(seal, new Vec3(0, 1, 0), EarthFx.ARGB, 0.7F + 0.25F * c, 2 + Math.round(2 * c), 14);
		level.playLocalSound(seal.x, seal.y, seal.z, SoundEvents.ROOTED_DIRT_BREAK, SoundSource.PLAYERS, 0.9F, 0.6F, false);

		int caster = e.casterId();
		RockLedger.Leash leash = new RockLedger.Leash(EarthStones.now());
		int longest = 0;
		for (List<BlockPos> p : paths) longest = Math.max(longest, p.size());
		int total = longest + LIFE + Math.round(SINK) + 2;
		int lines = paths.size();
		EarthFx.drive(kit, leash, total, t -> {
			for (int k = 0; k < lines; k++) {
				List<BlockPos> p = paths.get(k);
				if (t < p.size()) burst(kit, leash, crumbs, level, p.get(t), dirs.get(k), t, c, r, caster);
				int sinking = t - LIFE;
				if (sinking >= 0 && sinking < p.size()) sink(level, p.get(sinking), sinking, p.size(), sinkCrumbs / lines);
			}
		});
	}

	/** Spike {@code i} stabs up out of the ground in {@code cell}. */
	private static void burst(FxKit kit, RockLedger.Leash leash, EarthFx.Crumbs crumbs, ClientLevel level, BlockPos cell, Vec3 forward,
			int i, float c, RandomSource r, int caster) {
		Vec3 foot = Vec3.atBottomCenterOf(cell);
		BlockState ground = level.getBlockState(cell.below());
		if (ground.isAir()) ground = EarthFx.COBBLE;
		Vec3 side = new Vec3(-forward.z, 0, forward.x);
		// The tall spike leans a little forward, along the line; the short ones lean out to either side.
		float scale = 0.95F + 0.25F * c + 0.12F * r.nextFloat();
		float yaw = (float) Math.toDegrees(Math.atan2(forward.x, forward.z));
		spike(kit, leash, caster, cell, foot.add(forward.scale(0.08)), TALL, scale, yaw + (r.nextFloat() - 0.5F) * 30F, 8F + 8F * r.nextFloat(), 0F, LIFE);
		for (int s = -1; s <= 1; s += 2) {
			Vec3 at = foot.add(side.scale(0.3 * s)).add(forward.scale((r.nextDouble() - 0.5) * 0.3));
			float sideYaw = (float) Math.toDegrees(Math.atan2(side.x * s, side.z * s));
			spike(kit, leash, caster, cell, at, SHORT, 0.55F + 0.15F * r.nextFloat(), sideYaw, 20F + 10F * r.nextFloat(), 1.2F + 0.6F * r.nextFloat(), LIFE);
		}
		// A lump of the ground heaved up at its foot.
		if (kit.budget().ghostBlock()) {
			float lumpYaw = r.nextFloat() * 360F;
			float lumpTilt = 10F + 10F * r.nextFloat();
			EarthStones.add(caster, leash, ground, LIFE + Math.round(SINK), (t, out) -> {
				float up = Mth.clamp(t / RISE, 0F, 1F) * (1 - Mth.clamp((t - LIFE) / SINK, 0F, 1F));
				out.at(foot.add(0, -0.55 + 0.35 * up, 0));
				out.yaw = lumpYaw;
				out.pitch = lumpTilt * up;
				out.scale = 0.62F;
				out.light = cell;
				return true;
			});
		}
		crumbs.burst(foot.add(0, 0.15, 0), ground, 7, 0.16 + 0.04 * c);
		kit.burst(foot.add(0, 0.2, 0), EarthFx.DUST, 2, 0.05);
		kit.ring(foot.add(0, 0.05, 0), 0.45, new BlockParticleOption(ParticleTypes.DUST_PILLAR, ground), 3);
		if (i % 3 == 0) kit.motes(foot.add(0, 0.6, 0), EarthFx.CLAY, 1, 0.2, new Vec3(0, 0.03, 0), 0.01, GlowMote.Drift.RISE, 0.14F, 10);
		level.playLocalSound(foot.x, foot.y, foot.z, SoundEvents.POINTED_DRIPSTONE_LAND, SoundSource.PLAYERS, 0.8F, 0.75F + 0.3F * r.nextFloat(), false);
		if (i % 2 == 0) level.playLocalSound(foot.x, foot.y, foot.z, SoundEvents.DRIPSTONE_BLOCK_BREAK, SoundSource.PLAYERS, 0.7F, 0.6F, false);
		if (i == 0 && c >= 0.5F) kit.shake(0.12F * c, 5);
	}

	/**
	 * One dripstone spike of {@code parts} (bottom to top) standing at {@code foot}, {@code scale} blocks a part, leaning
	 * {@code tilt}° toward {@code yaw}: {@code delay} ticks in, it stabs up along its own axis out of the ground over {@link #RISE}
	 * ticks (overshooting a little), trembles once, stands, and sinks back the same way {@code life} ticks after the start. Lit from
	 * {@code cell}. One ghost block of the event per part.
	 */
	static void spike(FxKit kit, RockLedger.Leash leash, int caster, BlockPos cell, Vec3 foot, BlockState[] parts, float scale,
			float yaw, float tilt, float delay, int life) {
		Vector3f axis = new Quaternionf().rotationY(yaw * Mth.DEG_TO_RAD).rotateX(tilt * Mth.DEG_TO_RAD).transform(new Vector3f(0, 1, 0));
		Vec3 a = new Vec3(axis.x, axis.y, axis.z);
		double len = parts.length * scale;
		for (int j = 0; j < parts.length; j++) {
			if (!kit.budget().ghostBlock()) return;
			double along = (j + 0.5) * scale;
			EarthStones.add(caster, leash, parts[j], life + Math.round(SINK), (t, out) -> {
				float s = Mth.clamp((t - delay) / RISE, 0F, 1F);
				if (s <= 0F) return false;
				// Out fast with a small overshoot, then a tremor; sinking back at the end.
				double out1 = 1 - Math.pow(1 - s, 3) + 0.12 * Math.sin(Math.PI * s);
				double sink = Mth.clamp((t - life) / SINK, 0F, 1F);
				double shown = out1 * (1 - sink * sink);
				double tremor = t > delay + RISE && t < delay + RISE + 4 ? 0.025 * Math.sin(t * 7.0) : 0;
				out.at(foot.add(a.scale(along - len * (1 - shown))).add(tremor, 0, -tremor));
				out.yaw = yaw;
				out.pitch = tilt;
				out.scale = scale;
				out.light = cell;
				out.glow = GLOW;
				return true;
			});
		}
	}

	/** Least block light of a spike: earth magic, faintly lit at night. */
	static final int GLOW = 7;

	/** Spike {@code i} gives way: a little crumble and a grind (the crumbs were paid at the cast). */
	private static void sink(ClientLevel level, BlockPos cell, int i, int n, int crumbs) {
		Vec3 foot = Vec3.atBottomCenterOf(cell).add(0, 0.3, 0);
		BlockState ground = level.getBlockState(cell.below());
		int per = n == 0 ? 0 : crumbs / n;
		EarthFx.spray(level, EarthFx.crumbs(ground.isAir() ? EarthFx.COBBLE : ground), foot, per, 0.25, 0.08, true);
		if (i % 3 == 0) level.playLocalSound(foot.x, foot.y, foot.z, SoundEvents.POINTED_DRIPSTONE_BREAK, SoundSource.PLAYERS, 0.5F, 0.7F, false);
	}
}
