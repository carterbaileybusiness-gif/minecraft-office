package local.carter.office.client.magic.fx.earth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.client.magic.fx.SpellFx;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.earth.EarthWall;
import local.carter.office.magic.spell.earth.EarthWallGeometry;
import local.carter.office.magic.spell.earth.EarthWallGeometry.Cell;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
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
 * as they rise), the ground plates beside it prying up on their outer edge, and lands with a jolt: dust pillars at its foot,
 * crumbs and chips off its top, a heavy thud, a shake at the centre and at the ends. Short plants in its way burst into crumbs.
 * A rock whose real block never arrived (claimed land) crumbles in a puff. Half a minute later, when the blocks give way, each
 * column sinks back into the ground in a cloud of crumbs.
 *
 * <p>Same rules as the server ({@link EarthWallGeometry} with {@link EarthWall#cell}, {@link EarthWall#material},
 * {@link EarthWall#cellMaterial} and {@link EarthWall#fill} on this client's world), so the rocks rise where the real blocks
 * appear. Every rock hangs on a cancellable timer's leash ({@link EarthFx#leash}), so {@code FxCancel} takes them away.
 */
public final class EarthWallFx implements SpellFx {
	/** Ticks a risen column's rocks stay over the real blocks (which arrive about then), so the swap never shows. */
	private static final int HOLD = 3;
	/** Ticks a column takes to sink back when the wall falls, and how long before the fall the sinking rocks take over. */
	private static final int SINK = 12;
	private static final int SINK_LEAD = 2;
	/** Longest life of a chip off a column's top. */
	private static final int CHIP_LIFE = 24;
	/** Block crumbs for the whole wall, start to fall. */
	private static final int CRUMB_CAP = 150;

	private record Column(List<BlockPos> cells, List<BlockState> states, BlockState floor, BlockPos base) {}

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
		Function<BlockPos, Cell> cells = EarthWall.cells(level);
		// Across the wall, toward the caster.
		Vec3 centre = Vec3.atBottomCenterOf(origin);
		Vec3 across = new Vec3(az, 0, ax);
		if (e.origin().subtract(centre).dot(across) < 0) across = across.reverse();
		int fx = (int) Math.round(across.x);
		int fz = (int) Math.round(across.z);
		EarthFx.Crumbs crumbs = new EarthFx.Crumbs(kit, CRUMB_CAP);
		// Paid now for the fall, so the rise can never spend them.
		int fallCrumbs = crumbs.reserve(4 * width);
		int fallDust = kit.budget().particles(2 * width);

		// The cast: a circle on the ground, the line cracking open.
		kit.circle(centre.add(0, 0.06, 0), new Vec3(0, 1, 0), EarthFx.ARGB, 1.0F + 0.25F * width / 2F, 3 + Math.round(2 * c), 18);
		for (int k : g.order()) {
			BlockPos floor = g.nominal(k).below();
			BlockState s = level.getBlockState(floor);
			crumbs.burst(Vec3.atCenterOf(floor).add(0, 0.55, 0), s.isAir() ? EarthFx.COBBLE : s, 2, 0.08);
		}
		level.playLocalSound(centre.x, centre.y, centre.z, SoundEvents.RAVAGER_STEP, SoundSource.PLAYERS, 0.9F, 0.5F, false);
		level.playLocalSound(centre.x, centre.y, centre.z, SoundEvents.ROOTED_DIRT_BREAK, SoundSource.PLAYERS, 1.0F, 0.55F, false);

		// One cancellable timer for the wall's whole life (one of FxKit's shared timer slots per wall): it raises, lands and checks
		// each column, keeps their rocks and chips on its leash, waits out the wall's life, then hands the fall to a short timer of
		// its own as it ends.
		Map<Integer, Column> columns = new HashMap<>();
		Set<Integer> planned = new HashSet<>();
		RockLedger.Leash rise = new RockLedger.Leash(EarthStones.now());
		int risen = EarthWallGeometry.LAST_PLACE + HOLD;
		int sinkAt = EarthWallGeometry.DURATION - 1 - SINK_LEAD;
		EarthFx.drive(kit, rise, sinkAt + 1, t -> {
			if (t <= risen) {
				for (int k : g.order()) {
					if (g.riseStart(k) == t) rise(kit, rise, crumbs, level, g, k, cells, planned, columns, fx, fz);
				}
				for (int k : g.order()) {
					if (g.placeTick(k) == t) land(kit, rise, crumbs, level, g, k, c, columns.get(k));
					if (g.placeTick(k) + HOLD == t) puffMissing(kit, crumbs, level, columns.get(k));
				}
			}
			if (t == sinkAt) sink(kit, level, columns, fx, fz, fallCrumbs, fallDust);
		});
	}

	/** Column {@code k} starts heaving out of the ground (if the server rule lets it rise). */
	private static void rise(FxKit kit, RockLedger.Leash leash, EarthFx.Crumbs crumbs, ClientLevel level, EarthWallGeometry g, int k,
			Function<BlockPos, Cell> cells, Set<Integer> planned, Map<Integer, Column> columns, int fx, int fz) {
		if (k != 0 && !planned.contains(EarthWallGeometry.inner(k))) return;
		BlockPos base = EarthWallGeometry.base(g.nominal(k), cells);
		if (base == null) return;
		planned.add(k);
		List<BlockPos> span = g.cells(base, cells);
		// Short plants in its way burst as the column comes up (the server tears them out now).
		for (BlockPos p : span) {
			BlockState s = level.getBlockState(p);
			if (EarthWall.shortVegetation(s)) crumbs.burst(Vec3.atCenterOf(p), s, 5, 0.12);
		}
		List<BlockPos> fill = EarthWall.fill(level, span, null);
		if (fill.isEmpty()) return;
		BlockPos floorPos = base.below();
		BlockState floor = level.getBlockState(floorPos);
		BlockState material = EarthWall.material(level, floorPos);
		List<BlockState> states = new ArrayList<>(fill.size());
		for (BlockPos p : fill) states.add(EarthWall.cellMaterial(material, p));
		columns.put(k, new Column(fill, states, floor, base));
		int n = fill.size();
		int up = EarthWallGeometry.RISE_TICKS;
		float wobble = (k & 1) == 0 ? 1F : -1F;
		int caster = kit.event().casterId();

		// The column comes up as one piece, from all the way under the ground.
		for (int i = 0; i < n; i++) {
			if (!kit.budget().ghostBlock()) break;
			BlockPos cell = fill.get(i);
			Vec3 home = Vec3.atCenterOf(cell);
			EarthStones.add(caster, leash, states.get(i), up + HOLD, (t, out) -> {
				float s = Mth.clamp(t / up, 0F, 1F);
				float ease = 1 - (1 - s) * (1 - s) * (1 - s);
				double dy = -(n + 0.1) * (1 - ease);
				if (t > up) dy = 0.12 * Math.max(0, 1 - (t - up) / 1.5F); // a jolt as it hits its height
				double shiver = 0.05 * (1 - s) * Math.sin(t * 9.0 + k);
				out.at(home.add(shiver * fz, dy, shiver * fx));
				out.roll = (float) (wobble * 2.5 * (1 - s) * Math.sin(t * 5.0));
				out.scale = 1.004F;
				// Lit by the open air on the caster's side: the cell itself turns solid as the real block lands.
				out.light = cell.offset(fx, 0, fz);
				return true;
			});
		}
		// The ground plates either side pry up on their edge by the wall and drop back: the ground cracking, never a floating slab.
		for (int side = -1; side <= 1; side += 2) {
			BlockPos beside = floorPos.offset(fx * side, 0, fz * side);
			BlockState s = level.getBlockState(beside);
			if (s.isAir() || cells.apply(beside) != Cell.SOLID || !cells.apply(beside.above()).fillable() || !kit.budget().ghostBlock()) continue;
			pry(caster, leash, s, beside, fx * side, fz * side, up + 3);
		}
		Vec3 foot = Vec3.atBottomCenterOf(base).add(0, 0.1, 0);
		crumbs.burst(foot, floor.isAir() ? material : floor, 5, 0.16);
		kit.burst(foot, EarthFx.DUST, 3, 0.05);
		level.playLocalSound(foot.x, foot.y, foot.z, SoundEvents.GRAVEL_BREAK, SoundSource.PLAYERS, 0.5F, 0.55F + 0.05F * Math.abs(k), false);
	}

	/**
	 * The ground block {@code at} (beside the wall, {@code (dx, dz)} away from it) tilts up by its edge nearest the wall, hinged on
	 * its far top edge, and settles back over {@code life} ticks.
	 */
	private static void pry(int caster, RockLedger.Leash leash, BlockState s, BlockPos at, int dx, int dz, int life) {
		Vec3 centre = Vec3.atCenterOf(at);
		// The hinge: the top edge farthest from the wall.
		Vec3 hinge = centre.add(dx * 0.5, 0.5, dz * 0.5);
		Vector3f arm = new Vector3f((float) (centre.x - hinge.x), (float) (centre.y - hinge.y), (float) (centre.z - hinge.z));
		EarthStones.add(caster, leash, s, life, (t, out) -> {
			float lift = Mth.sin(Mth.clamp(t / life, 0F, 1F) * Mth.PI);
			float angle = 16F * lift;
			Quaternionf q = new Quaternionf();
			if (dz != 0) {
				out.pitch = dz * angle;
				q.rotationX(out.pitch * Mth.DEG_TO_RAD);
			} else {
				out.roll = -dx * angle;
				q.rotationZ(out.roll * Mth.DEG_TO_RAD);
			}
			Vector3f o = q.transform(new Vector3f(arm));
			out.at(hinge.add(o.x, o.y, o.z));
			out.scale = 1.004F;
			out.light = at.above();
			return true;
		});
	}

	/** Column {@code k} lands: dust pillars at its foot, crumbs and chips off its top, a thud; the centre and the ends shake. */
	private static void land(FxKit kit, RockLedger.Leash leash, EarthFx.Crumbs crumbs, ClientLevel level, EarthWallGeometry g, int k, float c,
			Column col) {
		if (col == null) return;
		BlockPos top = col.cells().get(col.cells().size() - 1);
		BlockState topState = col.states().get(col.states().size() - 1);
		Vec3 foot = Vec3.atBottomCenterOf(col.base()).add(0, 0.05, 0);
		Vec3 crown = Vec3.atBottomCenterOf(top).add(0, 1.02, 0);
		BlockState floor = col.floor().isAir() ? topState : col.floor();
		kit.ring(foot, 0.75, new BlockParticleOption(ParticleTypes.DUST_PILLAR, floor), 4);
		kit.burst(foot, EarthFx.DUST, 4, 0.07);
		crumbs.burst(crown, topState, 6, 0.16);
		RandomSource r = kit.random();
		for (int i = 0; i < 2; i++) {
			Vec3 v = new Vec3((r.nextDouble() - 0.5) * 0.24, 0.22 + 0.1 * r.nextDouble(), (r.nextDouble() - 0.5) * 0.24);
			EarthFx.chip(kit, leash, level, topState, crown, v, 0.14F + 0.08F * r.nextFloat(), CHIP_LIFE - r.nextInt(6));
		}
		kit.motes(crown, EarthFx.CLAY, 2, 0.3, new Vec3(0, 0.03, 0), 0.02, GlowMote.Drift.RISE, 0.16F, 12);
		SoundType sound = topState.getSoundType();
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

	/** As the rocks let go: where the real block never arrived (claimed land, refused), the rock crumbles in a puff. */
	private static void puffMissing(FxKit kit, EarthFx.Crumbs crumbs, ClientLevel level, Column col) {
		if (col == null) return;
		for (int i = 0; i < col.cells().size(); i++) {
			BlockPos p = col.cells().get(i);
			if (level.getBlockState(p).equals(col.states().get(i))) continue;
			Vec3 at = Vec3.atCenterOf(p);
			crumbs.burst(at, col.states().get(i), 4, 0.1);
			kit.burst(at, ParticleTypes.POOF, 1, 0.03);
		}
	}

	/**
	 * The wall falls: each column's rocks take the place of its blocks (only where the block is still the wall's: a block
	 * already broken shows nothing), hold a moment and sink into the ground, on a fresh cancellable leash. The crumbs were paid
	 * for at the cast.
	 */
	private static void sink(FxKit kit, ClientLevel level, Map<Integer, Column> columns, int fx, int fz, int crumbs, int dust) {
		RockLedger.Leash leash = EarthFx.leash(kit, SINK_LEAD + SINK + 2, t -> {});
		int caster = kit.event().casterId();
		int perColumn = columns.isEmpty() ? 0 : crumbs / columns.size();
		int dustPer = columns.isEmpty() ? 0 : dust / columns.size();
		boolean sound = true;
		for (Column col : columns.values()) {
			int n = col.cells().size();
			boolean any = false;
			for (int i = 0; i < n; i++) {
				BlockPos cell = col.cells().get(i);
				BlockState state = col.states().get(i);
				if (!level.getBlockState(cell).equals(state) || !kit.budget().ghostBlock()) continue;
				any = true;
				Vec3 home = Vec3.atCenterOf(cell);
				float tilt = ((cell.getX() + cell.getZ()) & 1) == 0 ? 3F : -3F;
				EarthStones.add(caster, leash, state, SINK_LEAD + SINK, (t, out) -> {
					float s = Mth.clamp((t - SINK_LEAD) / SINK, 0F, 1F);
					out.at(home.add(0, -(n + 0.2) * s * s, 0));
					out.roll = tilt * s;
					out.scale = 1.004F;
					out.light = cell.offset(fx, 0, fz);
					return true;
				});
			}
			if (!any) continue;
			Vec3 foot = Vec3.atBottomCenterOf(col.base()).add(0, 0.1, 0);
			throwNow(level, EarthFx.crumbs(col.states().get(0)), foot.add(0, n * 0.5, 0), perColumn, 0.18);
			throwNow(level, EarthFx.DUST, foot, dustPer, 0.06);
			if (sound) {
				sound = false;
				level.playLocalSound(foot.x, foot.y, foot.z, SoundEvents.GRAVEL_BREAK, SoundSource.BLOCKS, 1.0F, 0.6F, false);
				level.playLocalSound(foot.x, foot.y, foot.z, SoundEvents.ROOTED_DIRT_BREAK, SoundSource.BLOCKS, 0.9F, 0.5F, false);
			}
		}
	}

	/** {@code n} particles flung from {@code at} (already paid for out of the event's budget). */
	private static void throwNow(ClientLevel level, net.minecraft.core.particles.ParticleOptions p, Vec3 at, int n, double speed) {
		RandomSource r = level.getRandom();
		for (int i = 0; i < n; i++) {
			Vec3 v = EarthFx.sphere(r).scale(speed * (0.35 + 0.65 * r.nextDouble()));
			level.addParticle(p, at.x + (r.nextDouble() - 0.5) * 0.8, at.y + (r.nextDouble() - 0.5) * 0.8, at.z + (r.nextDouble() - 0.5) * 0.8,
					v.x, Math.abs(v.y), v.z);
		}
	}
}
