package local.carter.office.client.magic.fx.earth;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.client.magic.fx.SpellFx;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.earth.EarthWall;
import local.carter.office.magic.spell.earth.EarthWallGeometry;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Earth Wall: an earth circle opens on the ground where the wall will stand and the ground cracks along its line; then, from
 * the centre outward, each column heaves up out of the ground in one piece (real block models of what it is made of, rumbling
 * as they rise), the ground beside it bucking up, and lands with a jolt: dust pillars at its foot, crumbs and chips off its
 * top, a heavy thud, a shake at the centre and at the ends. Half a minute later, when the blocks give way, each column sinks
 * back into the ground in a cloud of crumbs.
 *
 * <p>Same rule as the server ({@link EarthWallGeometry} with {@link EarthWall#open}, {@link EarthWall#material} and
 * {@link EarthWall#unoccupied} on this client's world), so the rocks rise exactly where the real blocks appear.
 */
public final class EarthWallFx implements SpellFx {
	/** Ticks a risen column's rocks stay over the real blocks (which arrive about then), so the swap never shows. */
	private static final int HOLD = 3;
	/** Ticks a column takes to sink back when the wall falls. */
	private static final int SINK = 12;

	private record Column(List<BlockPos> cells, BlockState material, BlockState floor) {}

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		float c = Mth.clamp(e.charge(), 0F, 1F);
		float[] d = e.data();
		int ax = d.length > 1 ? Math.round(d[0]) : 1;
		int az = d.length > 1 ? Math.round(d[1]) : 0;
		int width = d.length > 2 ? Math.round(d[2]) : EarthWallGeometry.width(c);
		int height = d.length > 3 ? Math.round(d[3]) : EarthWallGeometry.height(c);
		BlockPos origin = BlockPos.containing(e.target());
		EarthWallGeometry g = new EarthWallGeometry(origin, ax, az, width, height);
		Predicate<BlockPos> open = EarthWall.open(level);
		// Across the wall, toward the caster.
		Vec3 centre = Vec3.atBottomCenterOf(origin);
		Vec3 across = new Vec3(az, 0, ax);
		if (e.origin().subtract(centre).dot(across) < 0) across = across.reverse();
		int fx = (int) Math.round(across.x);
		int fz = (int) Math.round(across.z);

		// The cast: a circle on the ground, the line cracking open.
		kit.circle(centre.add(0, 0.06, 0), new Vec3(0, 1, 0), EarthFx.ARGB, 1.0F + 0.25F * width / 2F, 3 + Math.round(2 * c), 18);
		for (int k : g.order()) {
			BlockPos floor = g.nominal(k).below();
			Vec3 at = Vec3.atCenterOf(floor).add(0, 0.55, 0);
			kit.burst(at, EarthFx.crumbs(level.getBlockState(floor).isAir() ? EarthFx.COBBLE : level.getBlockState(floor)), 3, 0.08);
		}
		level.playLocalSound(centre.x, centre.y, centre.z, SoundEvents.RAVAGER_STEP, SoundSource.PLAYERS, 0.9F, 0.5F, false);
		level.playLocalSound(centre.x, centre.y, centre.z, SoundEvents.ROOTED_DIRT_BREAK, SoundSource.PLAYERS, 1.0F, 0.55F, false);

		Map<Integer, Column> columns = new HashMap<>();
		Set<Integer> raised = new HashSet<>();
		kit.simulate(1, EarthWallGeometry.LAST_PLACE + 2, t -> {
			for (int k : g.order()) {
				if (g.riseStart(k) == t) rise(kit, level, g, k, t, open, raised, columns, fx, fz);
			}
			for (int k : g.order()) {
				if (g.placeTick(k) == t) land(kit, level, g, k, c, columns.get(k));
			}
		});
	}

	/** Column {@code k} starts heaving out of the ground (if the server rule lets it rise). */
	private static void rise(FxKit kit, ClientLevel level, EarthWallGeometry g, int k, int now, Predicate<BlockPos> open, Set<Integer> raised,
			Map<Integer, Column> columns, int fx, int fz) {
		if (k != 0 && !raised.contains(EarthWallGeometry.inner(k))) return;
		BlockPos base = EarthWallGeometry.base(g.nominal(k), open);
		if (base == null) return;
		raised.add(k);
		List<BlockPos> cells = EarthWall.unoccupied(level, g.cells(base, open));
		if (cells.isEmpty()) return;
		BlockPos floorPos = base.below();
		BlockState floor = level.getBlockState(floorPos);
		BlockState material = EarthWall.material(level, floorPos);
		columns.put(k, new Column(cells, material, floor));
		int n = cells.size();
		int rise = EarthWallGeometry.RISE_TICKS;
		float wobble = (k & 1) == 0 ? 1F : -1F;

		// The column comes up as one piece, from all the way under the ground.
		for (int i = 0; i < n; i++) {
			if (!kit.budget().ghostBlock()) break;
			BlockPos cell = cells.get(i);
			Vec3 home = Vec3.atCenterOf(cell);
			EarthStones.add(material, rise + HOLD, (t, out) -> {
				float s = Mth.clamp(t / rise, 0F, 1F);
				float ease = 1 - (1 - s) * (1 - s) * (1 - s);
				double dy = -(n + 0.1) * (1 - ease);
				if (t > rise) {
					// A jolt as it hits its height, settling into place.
					float since = t - rise;
					dy = 0.12 * Math.max(0, 1 - since / 1.5F);
				}
				double shiver = 0.05 * (1 - s) * Math.sin(t * 9.0 + k);
				out.at(home.add(shiver * fz, dy, shiver * fx));
				out.roll = (float) (wobble * 2.5 * (1 - s) * Math.sin(t * 5.0));
				out.scale = 1.004F;
				// Lit by the open air on the caster's side: the cell itself turns solid as the real block lands.
				out.light = cell.offset(fx, 0, fz);
				return true;
			});
		}
		// The ground beside it heaves up and drops back, crumbs spit out at its foot.
		for (int side = -1; side <= 1; side += 2) {
			BlockPos beside = floorPos.offset(fx * side, 0, fz * side);
			BlockState s = level.getBlockState(beside);
			if (s.isAir() || !open.test(beside.above()) || !kit.budget().ghostBlock()) continue;
			Vec3 home = Vec3.atCenterOf(beside);
			float tip = side * 7F;
			EarthStones.add(s, rise + 3, (t, out) -> {
				float u = Mth.clamp(t / (rise + 3), 0F, 1F);
				float lift = Mth.sin(u * Mth.PI);
				out.at(home.add(0, 0.28 * lift, 0));
				if (fx != 0) out.roll = -tip * lift;
				else out.pitch = tip * lift;
				out.scale = 1.004F;
				out.light = beside.above();
				return true;
			});
		}
		Vec3 foot = Vec3.atBottomCenterOf(base).add(0, 0.1, 0);
		kit.burst(foot, EarthFx.crumbs(floor.isAir() ? material : floor), 6, 0.16);
		kit.burst(foot, EarthFx.DUST, 3, 0.05);
		level.playLocalSound(foot.x, foot.y, foot.z, SoundEvents.GRAVEL_BREAK, SoundSource.PLAYERS, 0.5F, 0.55F + 0.05F * Math.abs(k), false);

		// Half a minute on (the wall's end, counted from the cast), the column sinks back where it came from.
		sinkLater(kit, level, cells, material, base, EarthWallGeometry.DURATION - 1 - now, k == 0, fx, fz);
	}

	/** Column {@code k} lands: dust pillars at its foot, crumbs and chips off its top, a thud; the centre and the ends shake. */
	private static void land(FxKit kit, ClientLevel level, EarthWallGeometry g, int k, float c, Column col) {
		if (col == null) return;
		BlockPos base = col.cells().get(0);
		BlockPos top = col.cells().get(col.cells().size() - 1);
		Vec3 foot = Vec3.atBottomCenterOf(base).add(0, 0.05, 0);
		Vec3 crown = Vec3.atBottomCenterOf(top).add(0, 1.02, 0);
		BlockState floor = col.floor().isAir() ? col.material() : col.floor();
		kit.ring(foot, 0.75, new BlockParticleOption(ParticleTypes.DUST_PILLAR, floor), 4);
		kit.burst(foot, EarthFx.DUST, 4, 0.07);
		kit.burst(crown, EarthFx.crumbs(col.material()), 7, 0.16);
		RandomSource r = kit.random();
		for (int i = 0; i < 2; i++) {
			Vec3 v = new Vec3((r.nextDouble() - 0.5) * 0.24, 0.22 + 0.1 * r.nextDouble(), (r.nextDouble() - 0.5) * 0.24);
			EarthFx.chip(kit, level, col.material(), crown, v, 0.14F + 0.08F * r.nextFloat(), 18 + r.nextInt(6));
		}
		kit.motes(crown, EarthFx.CLAY, 2, 0.3, new Vec3(0, 0.03, 0), 0.02, GlowMote.Drift.RISE, 0.16F, 12);
		SoundType sound = col.material().getSoundType();
		level.playLocalSound(foot.x, foot.y, foot.z, sound.getPlaceSound(), SoundSource.BLOCKS, 0.9F, sound.getPitch() * 0.6F, false);
		if (k == 0) {
			level.playLocalSound(foot.x, foot.y, foot.z, SoundEvents.RAVAGER_STEP, SoundSource.PLAYERS, 1.0F, 0.6F, false);
			kit.shake(0.22F + 0.15F * c, 8);
		}
		if (Math.abs(k) == g.half() && k > 0) {
			level.playLocalSound(foot.x, foot.y, foot.z, SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.PLAYERS, 0.45F + 0.2F * c, 0.75F, false);
			kit.shake(0.18F + 0.12F * c, 6);
		}
	}

	/**
	 * When the wall falls ({@code delay} ticks on), the column's rocks take the place of its blocks and sink into the ground.
	 * Each checks the world as it starts: a block already broken (or never placed) shows nothing. The crumbs are paid for now,
	 * out of this event's budget, and thrown then.
	 */
	private static void sinkLater(FxKit kit, ClientLevel level, List<BlockPos> cells, BlockState material, BlockPos base, int delay,
			boolean sound, int fx, int fz) {
		int n = cells.size();
		int crumbs = kit.budget().particles(8);
		int dust = kit.budget().particles(4);
		for (int i = 0; i < n; i++) {
			if (!kit.budget().ghostBlock()) break;
			BlockPos cell = cells.get(i);
			Vec3 home = Vec3.atCenterOf(cell);
			boolean first = i == 0;
			float tilt = ((cell.getX() + cell.getZ()) & 1) == 0 ? 3F : -3F;
			EarthStones.add(material, delay, SINK, (t, out) -> {
				float s = Mth.clamp(t / SINK, 0F, 1F);
				out.at(home.add(0, -(n + 0.2) * s * s, 0));
				out.roll = tilt * s;
				out.scale = 1.004F;
				out.light = cell.offset(fx, 0, fz);
				return true;
			}, () -> {
				if (!level.getBlockState(cell).equals(material)) return false;
				if (first) {
					Vec3 foot = Vec3.atBottomCenterOf(base).add(0, 0.1, 0);
					throwNow(level, EarthFx.crumbs(material), foot.add(0, n * 0.5, 0), crumbs, 0.18);
					throwNow(level, EarthFx.DUST, foot, dust, 0.06);
					if (sound) {
						level.playLocalSound(foot.x, foot.y, foot.z, SoundEvents.GRAVEL_BREAK, SoundSource.BLOCKS, 1.0F, 0.6F, false);
						level.playLocalSound(foot.x, foot.y, foot.z, SoundEvents.ROOTED_DIRT_BREAK, SoundSource.BLOCKS, 0.9F, 0.5F, false);
					}
				}
				return true;
			});
		}
	}

	/** {@code n} particles flung from {@code at} (already paid for out of an event's budget). */
	private static void throwNow(ClientLevel level, ParticleOptions p, Vec3 at, int n, double speed) {
		RandomSource r = level.getRandom();
		for (int i = 0; i < n; i++) {
			Vec3 v = EarthFx.sphere(r).scale(speed * (0.35 + 0.65 * r.nextDouble()));
			level.addParticle(p, at.x + (r.nextDouble() - 0.5) * 0.8, at.y + (r.nextDouble() - 0.5) * 0.8, at.z + (r.nextDouble() - 0.5) * 0.8,
					v.x, Math.abs(v.y), v.z);
		}
	}
}
