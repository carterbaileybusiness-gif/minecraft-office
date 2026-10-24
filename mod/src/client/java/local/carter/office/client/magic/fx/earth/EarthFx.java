package local.carter.office.client.magic.fx.earth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.SpellFxRegistry;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.spell.SpellIds;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
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
	/** Grit falling off a flying rock. */
	static final BlockParticleOption GRIT = new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.COARSE_DIRT.defaultBlockState());
	static final BlockState COBBLE = Blocks.COBBLESTONE.defaultBlockState();

	private EarthFx() {}

	public static void register() {
		EarthStones.register();
		SpellFxRegistry.register(SpellIds.STONE_BULLET, new StoneBulletFx());
		SpellFxRegistry.register(SpellIds.EARTH_WALL, new EarthWallFx());
		SpellFxRegistry.register(SpellIds.EARTH_SPIKES, new EarthSpikesFx());
		SpellFxRegistry.register(SpellIds.QUAKE, new QuakeFx());
		SpellFxRegistry.register(SpellIds.MOUNTAIN_FIST, new MountainFistFx());
	}

	static BlockParticleOption crumbs(BlockState s) {
		return new BlockParticleOption(ParticleTypes.BLOCK, s);
	}

	/**
	 * A cancellable client timer ({@link FxKit#simulate}, one step a tick for {@code ticks}) that also beats a leash: rocks on that
	 * leash live while the timer runs, so {@code FxKit.cancel(casterId)} or the timer's end takes them away.
	 */
	static RockLedger.Leash leash(FxKit kit, int ticks, IntConsumer onTick) {
		RockLedger.Leash leash = new RockLedger.Leash(EarthStones.now());
		drive(kit, leash, ticks, onTick);
		return leash;
	}

	/**
	 * As {@link #leash(FxKit, int, IntConsumer)} for a leash made beforehand ({@code new RockLedger.Leash(EarthStones.now())}),
	 * so the timer's own steps (the first runs at once) can already put rocks on it.
	 */
	static void drive(FxKit kit, RockLedger.Leash leash, int ticks, IntConsumer onTick) {
		kit.simulate(1, ticks, t -> {
			leash.beat(EarthStones.now());
			onTick.accept(t);
		});
	}

	/** Block crumbs for one event, capped (on top of the event's particle budget) so a big cast never turns into a gravel storm. */
	static final class Crumbs {
		private final FxKit kit;
		private int left;

		Crumbs(FxKit kit, int cap) {
			this.kit = kit;
			this.left = cap;
		}

		void burst(Vec3 at, BlockState s, int count, double speed) {
			int n = Math.min(count, left);
			if (n <= 0) return;
			left -= n;
			kit.burst(at, crumbs(s), n, speed);
		}

		void trail(Vec3 from, Vec3 to, BlockState s, int count) {
			int n = Math.min(count, left);
			if (n <= 0) return;
			left -= n;
			kit.trail(from, to, crumbs(s), n);
		}

		/** Takes up to {@code count} now for later (paid out of the budget too); the number granted. */
		int reserve(int count) {
			int n = Math.min(count, left);
			left -= n;
			return kit.budget().particles(n);
		}
	}

	/**
	 * A share of an event's particle budget set aside for one phase of a long visual (an eruption, a rise, a shatter), so the
	 * phases that come first can never starve the ones that come later. Drawing asks the share first and the event's budget second.
	 */
	static final class Share {
		private int left;

		Share(int particles) {
			this.left = Math.max(0, particles);
		}

		/** Up to {@code want} from this share alone, for a kit call that charges the event's budget itself. */
		int take(int want) {
			int granted = Math.max(0, Math.min(want, left));
			left -= granted;
			return granted;
		}

		/** Up to {@code want} from this share and the event's budget both, for particles added straight to the level ({@link #spray}). */
		int spend(FxKit kit, int want) {
			return kit.budget().particles(take(want));
		}

		int left() {
			return left;
		}
	}

	/**
	 * {@code n} particles (already paid for out of the event's budget) flung from about {@code at} ({@code spread} either way) at up
	 * to {@code speed}; {@code up} keeps them rising.
	 */
	static void spray(ClientLevel level, ParticleOptions p, Vec3 at, int n, double spread, double speed, boolean up) {
		RandomSource r = level.getRandom();
		for (int i = 0; i < n; i++) {
			Vec3 v = sphere(r).scale(speed * (0.35 + 0.65 * r.nextDouble()));
			level.addParticle(p, at.x + (r.nextDouble() - 0.5) * 2 * spread, at.y + (r.nextDouble() - 0.5) * 2 * spread,
					at.z + (r.nextDouble() - 0.5) * 2 * spread, v.x, up ? Math.abs(v.y) : v.y, v.z);
		}
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
	 * ground it meets in {@code level} and settles, shrinking away over its last ticks. A chip a block has since come up over is hidden. The flight is worked out now (one
	 * position per tick against the world as it is) and drawn smoothly between ticks. Costs one ghost block of the event.
	 */
	static void chip(FxKit kit, RockLedger.Leash leash, ClientLevel level, BlockState state, Vec3 at, Vec3 velocity, float size, int life) {
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
		EarthStones.add(kit.event().casterId(), leash, state, life, (t, out) -> {
			int i = Math.min(path.size() - 2, (int) t);
			Vec3 here = path.get(i).lerp(path.get(i + 1), t - i);
			// Buried by a block that came up after it was thrown (the next wall column rising over where it lies): gone, never
			// drawn inside the block, unlit and black.
			BlockPos in = BlockPos.containing(here);
			if (level.getBlockState(in).isCollisionShapeFullBlock(level, in)) return false;
			out.at(here);
			float spin = Math.min(t, life * 0.6F);
			out.yaw = yaw0 + spinA * spin;
			out.pitch = spinB * spin;
			out.scale = size * Mth.clamp((life - t) / 4F, 0F, 1F);
			return true;
		});
	}
}
