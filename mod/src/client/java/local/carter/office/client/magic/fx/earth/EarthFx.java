package local.carter.office.client.magic.fx.earth;

import java.util.ArrayList;
import java.util.List;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.SpellFxRegistry;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.spell.SpellIds;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Earth spell visuals: one {@code SpellFx} per spell, registered here, plus the earth palette and the rock physics they share. */
public final class EarthFx {
	/** The element colour (ARGB), for circles. */
	static final int ARGB = Element.EARTH.argb();
	/** The element colour (RGB): warm clay, for the faint glow. */
	static final int CLAY = Element.EARTH.argb() & 0xFFFFFF;
	/** Brown earth dust. */
	static final DustParticleOptions DUST = new DustParticleOptions(0x7A5A3A, 1.6F);
	/** Pale stone dust. */
	static final DustParticleOptions STONE_DUST = new DustParticleOptions(0x9A938A, 1.3F);
	/** Grit falling off a flying rock. */
	static final BlockParticleOption GRIT = new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.COARSE_DIRT.defaultBlockState());
	static final BlockState COBBLE = Blocks.COBBLESTONE.defaultBlockState();

	private EarthFx() {}

	public static void register() {
		EarthStones.register();
		SpellFxRegistry.register(SpellIds.STONE_BULLET, new StoneBulletFx());
		SpellFxRegistry.register(SpellIds.EARTH_WALL, new EarthWallFx());
	}

	static BlockParticleOption crumbs(BlockState s) {
		return new BlockParticleOption(ParticleTypes.BLOCK, s);
	}

	/** {@code v} normalised (+Z if it has no length). */
	static Vec3 unit(Vec3 v) {
		double l = v.length();
		return l < 1.0E-6 || !Double.isFinite(l) ? new Vec3(0, 0, 1) : v.scale(1 / l);
	}

	/** A random unit vector. */
	static Vec3 sphere(RandomSource r) {
		double z = r.nextDouble() * 2 - 1;
		double a = r.nextDouble() * Mth.TWO_PI;
		double s = Math.sqrt(1 - z * z);
		return new Vec3(s * Math.cos(a), z, s * Math.sin(a));
	}

	/**
	 * Throws a small chip of {@code state} from {@code at} with {@code velocity}: it tumbles under gravity, bounces once off the
	 * ground it meets in {@code level} and settles, shrinking away over its last ticks. The flight is worked out now (one
	 * position per tick against the world as it is) and drawn smoothly between ticks. Costs one ghost block of the event.
	 */
	static void chip(FxKit kit, ClientLevel level, BlockState state, Vec3 at, Vec3 velocity, float size, int life) {
		if (!kit.budget().ghostBlock()) return;
		RandomSource r = kit.random();
		List<Vec3> path = new ArrayList<>(life + 2);
		Vec3 p = at;
		Vec3 v = velocity;
		boolean bounced = false;
		for (int t = 0; t <= life + 1; t++) {
			path.add(p);
			Vec3 next = p.add(v);
			BlockPos cell = BlockPos.containing(next.x, next.y - size / 2, next.z);
			if (!level.getBlockState(cell).getCollisionShape(level, cell).isEmpty()) {
				// Hit the ground (or a wall): bounce once, then lie still.
				v = bounced ? Vec3.ZERO : new Vec3(v.x * 0.35, Math.abs(v.y) * 0.3, v.z * 0.35);
				bounced = true;
				next = p.add(v);
			}
			p = next;
			v = v.scale(0.96).add(0, -0.06, 0);
		}
		float spinA = (r.nextFloat() - 0.5F) * 70F;
		float spinB = (r.nextFloat() - 0.5F) * 70F;
		float yaw0 = r.nextFloat() * 360F;
		EarthStones.add(state, life, (t, out) -> {
			int i = Math.min(path.size() - 2, (int) t);
			out.at(path.get(i).lerp(path.get(i + 1), t - i));
			float spin = Math.min(t, life * 0.6F);
			out.yaw = yaw0 + spinA * spin;
			out.pitch = spinB * spin;
			out.scale = size * Mth.clamp((life - t) / 4F, 0F, 1F);
			return true;
		});
	}

	/** Brown dust and crumbs of {@code state} rolling out along the ground round {@code at} (a column landing, a wall falling). */
	static void groundBurst(FxKit kit, Vec3 at, BlockState state, int crumbs, int dust, double speed) {
		kit.burst(at, crumbs(state), crumbs, speed);
		kit.ring(at.add(0, 0.1, 0), 0.6, DUST, dust);
	}
}
