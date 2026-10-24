package local.carter.office.magic;

import java.util.ArrayList;
import java.util.List;

import local.carter.office.magic.cast.CastService.Result;
import local.carter.office.magic.command.MagicCommand;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.Motion;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.wind.DrillGeometry;
import local.carter.office.magic.spell.wind.TornadoGeometry;
import local.carter.office.magic.spell.wind.WindSphere;
import local.carter.office.magic.terrain.EditLog;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/** Server GameTests for the Wind lane: Gust and Blade Wind (Task 16); Air Step, Tornado and Wind Sphere (Task 17). */
public class WindGameTests {
	/** The test's +Z as a world direction (the caster faces it). */
	private static Vec3 ahead(GameTestHelper h) {
		return h.absoluteVec(new Vec3(0, 0, 1)).subtract(h.absoluteVec(Vec3.ZERO));
	}

	// ---- Gust ----

	@GameTest(structure = MagicTestKit.ARENA)
	public void gustLaunchesZombie(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WIND, 1);
		Zombie z = MagicTestKit.movableDummy(h, new BlockPos(16, 4, 7));
		MagicTestKit.aim(p, new BlockPos(16, 5, 7));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		Vec3 start = z.position();
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.GUST, 0f), Result.CAST, "result");
		Vec3 v = z.getDeltaMovement();
		h.assertTrue(v.y >= 0.3, "upward velocity " + v);
		h.assertTrue(v.dot(ahead(h)) > 1.0, "thrown away along +Z " + v);
		h.assertTrue(z.syncVelocity, "velocity synced");
		h.assertTrue(z.getHealth() <= 19f, "zombie health " + z.getHealth());
		List<FxEvent> fx = rec.fx(p);
		h.assertValueEqual(fx.size(), 1, "one fx");
		h.assertTrue(fx.get(0).entities().length == 1 && fx.get(0).entities()[0] == z.getId(), "fx names the zombie");
		h.assertValueEqual(fx.get(0).data()[0], 6f, "range at charge 0");
		// A movable dummy really flies: up and away along +Z.
		double[] peak = {0};
		h.onEachTick(() -> peak[0] = Math.max(peak[0], z.getY() - start.y));
		h.runAfterDelay(10, () -> {
			h.assertTrue(z.position().subtract(start).dot(ahead(h)) > 2.0, "thrown along +Z, moved " + z.position().subtract(start));
			h.assertTrue(peak[0] > 0.3, "lifted off the ground, peak " + peak[0]);
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void gustDoesNotPushPlayer(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WIND, 1);
		ServerPlayer other = MagicTestKit.otherPlayer(h, new BlockPos(16, 4, 7));
		Villager villager = MagicTestKit.villager(h, new BlockPos(18, 4, 8));
		Zombie control = MagicTestKit.movableDummy(h, new BlockPos(14, 4, 8));
		Vec3 at = other.position();
		Vec3 v = other.getDeltaMovement();
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.GUST, 1f), Result.CAST, "result");
		h.assertTrue(control.getDeltaMovement().dot(ahead(h)) > 1.0, "the zombie in the same cone is thrown " + control.getDeltaMovement());
		h.assertValueEqual(other.getDeltaMovement(), v, "player velocity");
		h.assertValueEqual(villager.getDeltaMovement().horizontalDistance(), 0.0, "villager velocity");
		h.assertValueEqual(other.getHealth(), other.getMaxHealth(), "player health");
		h.runAfterDelay(5, () -> {
			h.assertTrue(other.position().distanceTo(at) < 1.0E-6, "player moved to " + other.position());
			h.assertValueEqual(villager.getHealth(), villager.getMaxHealth(), "villager health");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void gustDoesNotBlowThroughWalls(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WIND, 1);
		// A one-wide pillar 2 ahead hides the zombie straight behind it; the one to the side stands in the open.
		MagicTestKit.fill(h, new BlockPos(16, 4, 6), new BlockPos(16, 7, 6), Blocks.STONE);
		Zombie hidden = MagicTestKit.movableDummy(h, new BlockPos(16, 4, 8));
		Zombie open = MagicTestKit.movableDummy(h, new BlockPos(18, 4, 8));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.GUST, 1f), Result.CAST, "result");
		h.assertValueEqual(hidden.getDeltaMovement().horizontalDistance(), 0.0, "hidden zombie velocity");
		h.assertValueEqual(hidden.getHealth(), 20f, "hidden zombie health");
		h.assertTrue(open.getDeltaMovement().horizontalDistance() > 1.0, "zombie in the open thrown " + open.getDeltaMovement());
		h.assertTrue(open.getDeltaMovement().horizontalDistance() <= 3.0 + 1.0E-9, "level speed capped at 3 " + open.getDeltaMovement());
		h.assertTrue(open.getHealth() <= 19f, "zombie in the open hurt " + open.getHealth());
		int[] ids = rec.fx(p).getFirst().entities();
		h.assertTrue(ids.length == 1 && ids[0] == open.getId(), "fx names only the zombie in the open");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void gustBlowsItemsAndReachesFurtherCharged(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WIND, 1);
		ItemEntity item = MagicTestKit.item(h, new BlockPos(16, 4, 8));
		// 7.5 ahead: outside the tap's 6, inside the full charge's 8.
		Zombie far = MagicTestKit.movableDummy(h, new BlockPos(16, 4, 12));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.GUST, 0f), Result.CAST, "tap");
		h.assertTrue(item.getDeltaMovement().dot(ahead(h)) > 0.5, "item blown " + item.getDeltaMovement());
		h.assertValueEqual(far.getHealth(), 20f, "far zombie untouched by the tap");
		// Gust's cooldown is 10 ticks.
		h.runAfterDelay(12, () -> {
			h.assertValueEqual(MagicTestKit.cast(p, SpellIds.GUST, 1f), Result.CAST, "full charge");
			h.assertTrue(far.getHealth() <= 19f, "far zombie hit by the full charge " + far.getHealth());
			h.assertTrue(far.getDeltaMovement().dot(ahead(h)) > 2.0, "full charge throws harder " + far.getDeltaMovement());
			h.assertValueEqual(rec.fx(p).getLast().data()[0], 8f, "range at full charge");
			h.succeed();
		});
	}

	// ---- Blade Wind ----

	@GameTest(structure = MagicTestKit.ARENA)
	public void bladeWindPiercesTwoZombies(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WIND, 2);
		Zombie near = MagicTestKit.dummy(h, new BlockPos(16, 4, 7));
		Zombie far = MagicTestKit.dummy(h, new BlockPos(16, 4, 10));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.BLADE_WIND, 0f), Result.CAST, "result");
		FxEvent fx = rec.fx(p).getFirst();
		h.assertValueEqual(fx.data()[0], 3f, "three blades on a tap");
		h.assertValueEqual(fx.duration(), 14, "fx duration");
		h.runAfterDelay(8, () -> {
			h.assertTrue(near.getHealth() <= 15f, "near zombie " + near.getHealth());
			h.assertTrue(far.getHealth() <= 15f, "far zombie " + far.getHealth());
			// Once per cast, however many blades of the fan cross it.
			h.assertTrue(near.getHealth() >= 14f, "near zombie hit once " + near.getHealth());
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void bladeWindFansFiveWhenChargedAndStopsAtWalls(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WIND, 2);
		MagicTestKit.fill(h, new BlockPos(10, 4, 12), new BlockPos(22, 8, 12), Blocks.STONE);
		Zombie behind = MagicTestKit.dummy(h, new BlockPos(16, 4, 14));
		long hash = MagicTestKit.blockHash(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.BLADE_WIND, 1f), Result.CAST, "result");
		float[] data = rec.fx(p).getFirst().data();
		h.assertValueEqual(data[0], 5f, "five blades at full charge");
		// The wall's face is 7.5 ahead of the eye and blades start 0.5 out: the middle one flies 7 / 2 = 3.5 ticks.
		h.assertTrue(Math.abs(data[3] - 3.5f) < 0.01f, "middle blade stops at the wall: " + data[3]);
		h.runAfterDelay(16, () -> {
			h.assertValueEqual(behind.getHealth(), 20f, "zombie behind the wall");
			h.assertValueEqual(MagicTestKit.blockHash(h), hash, "stone is never cut");
			h.succeed();
		});
	}

	/** Natural oak leaves (not persistent) one step from a log, so they never decay during the test. */
	private static BlockState naturalLeaves() {
		return Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, false).setValue(LeavesBlock.DISTANCE, 1);
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV)
	public void bladeWindCutsLeavesNotLogs(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WIND, 2);
		// Natural leaves at 8 (a log above them) and at 10 (a log right behind): the blade cuts through both and stops at the log.
		h.setBlock(new BlockPos(16, 5, 12), naturalLeaves());
		h.setBlock(new BlockPos(16, 6, 12), Blocks.OAK_LOG);
		h.setBlock(new BlockPos(16, 5, 14), naturalLeaves());
		h.setBlock(new BlockPos(16, 5, 15), Blocks.OAK_LOG);
		// Ground plants in the swath (down to the feet) go too.
		h.setBlock(new BlockPos(17, 3, 9), Blocks.DIRT);
		h.setBlock(new BlockPos(17, 4, 9), Blocks.SHORT_GRASS);
		h.setBlock(new BlockPos(15, 3, 10), Blocks.DIRT);
		h.setBlock(new BlockPos(15, 4, 10), Blocks.POPPY);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.BLADE_WIND, 0f), Result.CAST, "result");
		h.runAfterDelay(20, () -> {
			h.assertBlockPresent(Blocks.AIR, new BlockPos(16, 5, 12));
			h.assertBlockPresent(Blocks.AIR, new BlockPos(16, 5, 14));
			h.assertBlockPresent(Blocks.OAK_LOG, new BlockPos(16, 5, 15));
			h.assertBlockPresent(Blocks.OAK_LOG, new BlockPos(16, 6, 12));
			h.assertBlockPresent(Blocks.AIR, new BlockPos(17, 4, 9));
			h.assertBlockPresent(Blocks.AIR, new BlockPos(15, 4, 10));
			h.assertBlockPresent(Blocks.STONE, new BlockPos(16, 3, 14));
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV)
	public void bladeWindSparesPersistentLeaves(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WIND, 2);
		// A player-placed hedge at 8 with natural leaves (and their log) behind it at 10.
		h.setBlock(new BlockPos(16, 5, 12), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
		h.setBlock(new BlockPos(16, 5, 14), naturalLeaves());
		h.setBlock(new BlockPos(16, 5, 15), Blocks.OAK_LOG);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.BLADE_WIND, 0f), Result.CAST, "result");
		// The hedge is solid to the blade: the middle one stops on its face, 7.5 ahead of the eye (7 from its start).
		float stop = rec.fx(p).getFirst().data()[2];
		h.assertTrue(Math.abs(stop - 3.5f) < 0.01f, "middle blade stops at the hedge: " + stop);
		h.runAfterDelay(20, () -> {
			h.assertBlockState(new BlockPos(16, 5, 12), s -> s.is(Blocks.OAK_LEAVES) && s.getValue(LeavesBlock.PERSISTENT),
					s -> Component.literal("the persistent hedge is cut: " + s));
			h.assertBlockPresent(Blocks.OAK_LEAVES, new BlockPos(16, 5, 14));
			h.succeed();
		});
	}

	// ---- Air Step ----

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 100)
	public void airStepWorksMidAir(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WIND, 3);
		ServerPlayer control = MagicTestKit.otherPlayer(h, new BlockPos(6, 4, 4));
		// Mid-air and falling fast, 8 blocks up.
		MagicTestKit.teleportTo(p, new BlockPos(16, 12, 4));
		p.setDeltaMovement(0, -1.2, 0);
		p.fallDistance = 8;
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.AIR_STEP, 0f), Result.CAST, "result");
		Vec3 v = p.getDeltaMovement();
		h.assertTrue(v.y >= 0.9 - 1.0E-9 && v.y <= 0.9 + 1.0E-6, "upward velocity 0.9 at a tap " + v);
		h.assertTrue(Math.abs(v.dot(ahead(h)) - 0.4) < 1.0E-6, "0.4 along the level look " + v);
		h.assertTrue(p.syncVelocity, "velocity synced to the client");
		h.assertTrue(Motion.noFall(p), "no-fall window open");
		h.assertValueEqual(p.fallDistance, 0.0, "fall distance reset");
		h.runAfterDelay(50, () -> {
			// Landing hard inside the window: no damage. The same landing hurts a player without the window (positive control).
			float before = p.getHealth();
			p.causeFallDamage(10, 1f, p.damageSources().fall());
			h.assertValueEqual(p.getHealth(), before, "caster health after a 10-block landing");
			control.causeFallDamage(10, 1f, control.damageSources().fall());
			h.assertTrue(control.getHealth() < control.getMaxHealth(), "the same landing hurts another player " + control.getHealth());
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 100)
	public void airStepChargedJumpsHigherAndWindowCloses(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WIND, 3);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.AIR_STEP, 1f), Result.CAST, "result");
		Vec3 v = p.getDeltaMovement();
		h.assertTrue(Math.abs(v.y - 1.1) < 1.0E-6, "full charge: 1.1 up " + v);
		h.assertTrue(Math.abs(v.horizontalDistance() - 0.4) < 1.0E-6, "level push 0.4 " + v);
		h.runAfterDelay(62, () -> {
			// The window is 60 ticks: afterwards a landing hurts the caster too.
			h.assertFalse(Motion.noFall(p), "no-fall window closed");
			p.causeFallDamage(10, 1f, p.damageSources().fall());
			h.assertTrue(p.getHealth() < p.getMaxHealth(), "landing after the window hurts " + p.getHealth());
			h.succeed();
		});
	}

	// ---- Tornado ----

	/** A zombie that physics moves and that takes damage like any other. */
	private static double[] peak(GameTestHelper h, net.minecraft.world.entity.Entity e) {
		double start = e.getY();
		double[] peak = {0};
		h.onEachTick(() -> peak[0] = Math.max(peak[0], e.getY() - start));
		return peak;
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 100)
	public void tornadoLiftsZombie(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WIND, 5);
		MagicTestKit.aim(p, new BlockPos(16, 3, 12));
		Zombie z = MagicTestKit.movableDummy(h, new BlockPos(16, 4, 12));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		double[] peak = peak(h, z);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.TORNADO, 0f), Result.CAST, "result");
		FxEvent fx = rec.fx(p).getFirst();
		h.assertValueEqual(fx.duration(), 120, "life at charge 0");
		h.assertTrue(Math.abs(fx.target().y - h.absoluteVec(new Vec3(0, 4, 0)).y) < 1.0E-6, "starts on the floor " + fx.target());
		h.assertTrue(fx.dir().subtract(ahead(h)).length() < 1.0E-6, "travels along the level look " + fx.dir());
		h.runAfterDelay(40, () -> {
			h.assertTrue(peak[0] >= 2, "zombie lifted by tick 40, peak " + peak[0]);
			h.assertTrue(z.getHealth() < 20f, "zombie hurt " + z.getHealth());
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 100)
	public void tornadoTravelsAndCarriesItems(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WIND, 5);
		MagicTestKit.aim(p, new BlockPos(16, 3, 12));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.TORNADO, 1f), Result.CAST, "result");
		FxEvent fx = rec.fx(p).getFirst();
		h.assertValueEqual(fx.duration(), 160, "life at full charge");
		TornadoGeometry g = new TornadoGeometry(fx.target(), fx.dir(), fx.duration());
		// An item 30 ticks down the path, dropped there 20 ticks in: the tornado reaches it and carries it up and along.
		// (All scheduled up front: GameTest can't take a new schedule from inside a scheduled callback.)
		ItemEntity[] item = {null};
		Vec3[] from = {null};
		double[] peak = {0};
		h.onEachTick(() -> {
			if (item[0] != null) peak[0] = Math.max(peak[0], item[0].getY() - from[0].y);
		});
		h.runAfterDelay(20, () -> {
			Vec3 rel = h.relativeVec(g.at(30));
			item[0] = MagicTestKit.item(h, BlockPos.containing(rel.x, 4, rel.z));
			from[0] = item[0].position();
		});
		h.runAfterDelay(50, () -> {
			h.assertTrue(peak[0] >= 1.5, "item lifted, peak " + peak[0]);
			Vec3 moved = item[0].position().subtract(from[0]);
			h.assertTrue(moved.dot(ahead(h)) > -1, "item not left behind " + moved);
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 100)
	public void tornadoSparesLivestockAndPlayers(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WIND, 5);
		MagicTestKit.aim(p, new BlockPos(16, 3, 12));
		Zombie z = MagicTestKit.movableDummy(h, new BlockPos(17, 4, 12));
		Cow cow = h.spawn(EntityTypes.COW, new BlockPos(15, 4, 12));
		cow.removeAllGoals(g -> true);
		cow.setPersistenceRequired();
		ServerPlayer other = MagicTestKit.otherPlayer(h, new BlockPos(16, 4, 13));
		Vec3 otherV = other.getDeltaMovement();
		Vec3 otherAt = other.position();
		double[] zombiePeak = peak(h, z);
		double[] cowPeak = peak(h, cow);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.TORNADO, 0f), Result.CAST, "result");
		h.runAfterDelay(40, () -> {
			h.assertTrue(zombiePeak[0] >= 2, "the zombie beside them is lifted (positive control), peak " + zombiePeak[0]);
			h.assertTrue(cowPeak[0] < 0.5, "cow not lifted, peak " + cowPeak[0]);
			h.assertValueEqual(cow.getHealth(), cow.getMaxHealth(), "cow health");
			h.assertValueEqual(other.getDeltaMovement(), otherV, "player velocity");
			h.assertTrue(other.position().distanceTo(otherAt) < 1.0E-6, "player moved to " + other.position());
			h.assertValueEqual(other.getHealth(), other.getMaxHealth(), "player health");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 100)
	public void tornadoNeverLiftsThroughWalls(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WIND, 5);
		MagicTestKit.aim(p, new BlockPos(16, 3, 12));
		// A zombie shut in a stone cell inside the tornado's reach, and one in the open beside it.
		MagicTestKit.fill(h, new BlockPos(18, 4, 12), new BlockPos(20, 7, 14), Blocks.STONE);
		MagicTestKit.fill(h, new BlockPos(19, 4, 13), new BlockPos(19, 5, 13), Blocks.AIR);
		Zombie shut = MagicTestKit.movableDummy(h, new BlockPos(19, 4, 13));
		Zombie open = MagicTestKit.movableDummy(h, new BlockPos(15, 4, 12));
		double[] shutPeak = peak(h, shut);
		double[] openPeak = peak(h, open);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.TORNADO, 0f), Result.CAST, "result");
		h.runAfterDelay(40, () -> {
			h.assertTrue(openPeak[0] >= 2, "the zombie in the open is lifted (positive control), peak " + openPeak[0]);
			h.assertTrue(shutPeak[0] < 0.5, "the walled-in zombie stays put, peak " + shutPeak[0]);
			h.assertValueEqual(shut.getHealth(), 20f, "the walled-in zombie is unhurt");
			h.succeed();
		});
	}

	// ---- Wind Sphere ----

	/** Every cell's block, for comparing before and after. */
	private static List<BlockState> snapshot(GameTestHelper h) {
		List<BlockState> out = new ArrayList<>();
		for (BlockPos q : BlockPos.betweenClosed(BlockPos.ZERO, new BlockPos(MagicTestKit.SIZE_X - 1, MagicTestKit.SIZE_Y - 1, MagicTestKit.SIZE_Z - 1))) {
			out.add(h.getBlockState(q));
		}
		return out;
	}

	/** Cells whose block differs between two snapshots. */
	private static List<BlockPos> changed(List<BlockState> a, List<BlockState> b) {
		List<BlockPos> out = new ArrayList<>();
		int i = 0;
		for (BlockPos q : BlockPos.betweenClosed(BlockPos.ZERO, new BlockPos(MagicTestKit.SIZE_X - 1, MagicTestKit.SIZE_Y - 1, MagicTestKit.SIZE_Z - 1))) {
			if (a.get(i) != b.get(i)) out.add(q.immutable());
			i++;
		}
		return out;
	}

	/**
	 * Moves {@code p} by its velocity every tick, collisions included, as its client would (the server never moves a player
	 * itself, so without this a dashing caster stands still and trails the plan).
	 */
	private static void drive(GameTestHelper h, ServerPlayer p) {
		h.onEachTick(() -> p.move(MoverType.SELF, p.getDeltaMovement()));
	}

	/**
	 * As {@link #drive}, but the server hears of the moves only every {@code every} ticks, all at once: a lag spike bunching the
	 * movement packets. {@code jump[0]} records the longest single jump the server saw.
	 */
	private static void driveBunched(GameTestHelper h, ServerPlayer p, int every, double[] jump) {
		Vec3[] pending = {Vec3.ZERO};
		int[] n = {0};
		h.onEachTick(() -> {
			pending[0] = pending[0].add(p.getDeltaMovement());
			if (++n[0] % every != 0) return;
			Vec3 from = p.position();
			p.move(MoverType.SELF, pending[0]);
			pending[0] = Vec3.ZERO;
			jump[0] = Math.max(jump[0], p.position().distanceTo(from));
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 80)
	public void windSphereDrillsTunnel(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WIND, 7);
		// A thick stone wall 6 ahead (its face 5.5 in front of the eye).
		MagicTestKit.fill(h, new BlockPos(9, 4, 10), new BlockPos(23, 12, 23), Blocks.STONE);
		List<BlockState> before = snapshot(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		double[] fastest = {0};
		h.onEachTick(() -> fastest[0] = Math.max(fastest[0], p.getDeltaMovement().dot(ahead(h))));
		drive(h, p);
		h.assertValueEqual(MagicTestKit.castUltimate(p, SpellIds.WIND_SPHERE), Result.CAST, "result");
		FxEvent fx = rec.fx(p).getFirst();
		float[] data = fx.data();
		h.assertValueEqual(data[0], 8f, "drill length 8 at level 7");
		h.assertValueEqual(data[2], (float) WindSphere.Kind.BLOCK.ordinal(), "predicted to hit the wall");
		Vec3 impact = fx.target();
		h.assertTrue(Math.abs(h.relativeVec(impact).z - 10) < 1.0E-6, "impact on the wall's face " + h.relativeVec(impact));
		h.assertTrue(Math.abs(data[1] - 2.5f) < 1.0E-4, "reaches it 2.5 dash ticks in: " + data[1]);
		int[] thrown = {0};
		AABB arena = new AABB(h.absoluteVec(Vec3.ZERO), h.absoluteVec(new Vec3(MagicTestKit.SIZE_X, MagicTestKit.SIZE_Y, MagicTestKit.SIZE_Z)));
		h.runAfterDelay(DrillGeometry.WINDUP + 5, () -> thrown[0] = h.getLevel().getEntitiesOfClass(FallingBlockEntity.class, arena).size());
		h.runAfterDelay(40, () -> {
			List<BlockState> after = snapshot(h);
			int removed = 0;
			int i = 0;
			for (BlockPos q : BlockPos.betweenClosed(BlockPos.ZERO, new BlockPos(MagicTestKit.SIZE_X - 1, MagicTestKit.SIZE_Y - 1, MagicTestKit.SIZE_Z - 1))) {
				BlockState was = before.get(i);
				BlockState now = after.get(i);
				i++;
				if (was.isAir() || !now.isAir()) continue;
				removed++;
				double d = h.absoluteVec(Vec3.atCenterOf(q)).distanceTo(impact);
				h.assertTrue(d <= 12 + 0.87, "block removed " + d + " from the impact at " + q);
			}
			h.assertTrue(removed >= 20, "blocks drilled: " + removed);
			// The tunnel runs along the look: the cells straight down the axis are open, and the floor under the path stays.
			for (int z = 10; z < 18; z++) {
				h.assertBlockPresent(Blocks.AIR, new BlockPos(16, 4, z));
				h.assertBlockPresent(Blocks.AIR, new BlockPos(16, 5, z));
				h.assertBlockPresent(Blocks.STONE, new BlockPos(16, 3, z));
			}
			h.assertBlockPresent(Blocks.STONE, new BlockPos(16, 5, 18));
			h.assertTrue(thrown[0] >= 10 && thrown[0] <= DrillGeometry.LAUNCH, "debris thrown: " + thrown[0]);
			h.assertTrue(fastest[0] > 1.79, "the caster dashed at 1.8 " + fastest[0]);
			h.assertTrue(p.getDeltaMovement().length() < 1.0E-9, "the caster is stopped after the hit " + p.getDeltaMovement());
			h.assertTrue(h.relativeVec(p.position()).z < 10, "the caster stops short of the wall " + h.relativeVec(p.position()));
			h.assertTrue(Motion.noFall(p), "no fall damage after the dash");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 80)
	public void windSphereStopsAtKneeHighWall(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WIND, 7);
		// A one-high wall 4 ahead: the sphere's line (eye height) clears it, the body's line does not.
		MagicTestKit.fill(h, new BlockPos(10, 4, 8), new BlockPos(22, 4, 8), Blocks.STONE);
		// Where the sphere would have flown on to, over the wall.
		Zombie beyond = MagicTestKit.dummy(h, new BlockPos(16, 4, 15));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		drive(h, p);
		h.assertValueEqual(MagicTestKit.castUltimate(p, SpellIds.WIND_SPHERE), Result.CAST, "result");
		FxEvent fx = rec.fx(p).getFirst();
		h.assertValueEqual(fx.data()[2], (float) WindSphere.Kind.BLOCK.ordinal(), "the body's line hits the wall");
		h.assertTrue(Math.abs(h.relativeVec(fx.target()).z - 8) < 1.0E-6, "goes off at the wall's face " + h.relativeVec(fx.target()));
		h.runAfterDelay(40, () -> {
			h.assertBlockPresent(Blocks.AIR, new BlockPos(16, 4, 8));
			// No trench: the floor under the path stays.
			for (int z = 8; z < 15; z++) h.assertBlockPresent(Blocks.STONE, new BlockPos(16, 3, z));
			h.assertValueEqual(beyond.getHealth(), 20f, "the zombie the sphere would have reached over the wall");
			h.assertTrue(h.relativeVec(p.position()).z < 8, "the caster stops at the wall " + h.relativeVec(p.position()));
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 80)
	public void windSphereGoesOffWhereTheBodyStops(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WIND, 7);
		// A chest-high beam (bottom slabs at y 5: 1.0 to 1.5 over the floor) that both lines pass: the sphere's over, the
		// body's under. Only the body hits it.
		for (int x = 10; x <= 22; x++) h.setBlock(new BlockPos(x, 5, 9), Blocks.SMOOTH_STONE_SLAB);
		// Far down the plan: the sphere would reach it if it ignored where the body really is.
		Zombie far = MagicTestKit.dummy(h, new BlockPos(16, 4, 20));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		drive(h, p);
		h.assertValueEqual(MagicTestKit.castUltimate(p, SpellIds.WIND_SPHERE), Result.CAST, "result");
		h.assertValueEqual(rec.fx(p).getFirst().data()[2], (float) WindSphere.Kind.CREATURE.ordinal(), "the plan alone would reach the far zombie");
		h.runAfterDelay(40, () -> {
			h.assertTrue(h.relativeVec(p.position()).z < 9, "the body stopped at the beam " + h.relativeVec(p.position()));
			h.assertValueEqual(far.getHealth(), 20f, "it went off at the beam, not at the far zombie");
			h.assertBlockPresent(Blocks.AIR, new BlockPos(16, 5, 9));
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 80)
	public void windSphereSurvivesBunchedMoves(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WIND, 7);
		MagicTestKit.fill(h, new BlockPos(9, 4, 16), new BlockPos(23, 10, 20), Blocks.STONE);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		double[] jump = {0};
		driveBunched(h, p, 4, jump);
		h.assertValueEqual(MagicTestKit.castUltimate(p, SpellIds.WIND_SPHERE), Result.CAST, "result");
		h.assertValueEqual(rec.fx(p).getFirst().data()[2], (float) WindSphere.Kind.BLOCK.ordinal(), "predicted to hit the wall");
		h.runAfterDelay(40, () -> {
			// The server saw the caster jump more than 4 at once: a per-tick check would have cancelled the dash.
			h.assertTrue(jump[0] > WindSphere.TELEPORT, "bunched jump " + jump[0]);
			h.assertBlockPresent(Blocks.AIR, new BlockPos(16, 5, 16));
			h.assertBlockPresent(Blocks.AIR, new BlockPos(16, 5, 19));
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 140)
	public void windSphereDebrisLandsAndUndoes(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WIND, 7);
		MagicTestKit.fill(h, new BlockPos(9, 4, 10), new BlockPos(23, 12, 23), Blocks.STONE);
		List<BlockState> before = snapshot(h);
		drive(h, p);
		h.assertValueEqual(MagicTestKit.castUltimate(p, SpellIds.WIND_SPHERE), Result.CAST, "result");
		AABB arena = new AABB(h.absoluteVec(Vec3.ZERO), h.absoluteVec(new Vec3(MagicTestKit.SIZE_X, MagicTestKit.SIZE_Y, MagicTestKit.SIZE_Z)));
		int[] thrown = {0};
		h.runAfterDelay(DrillGeometry.WINDUP + 5, () -> thrown[0] = h.getLevel().getEntitiesOfClass(FallingBlockEntity.class, arena).size());
		h.runAfterDelay(100, () -> {
			h.assertTrue(thrown[0] >= 10, "debris thrown: " + thrown[0]);
			h.assertValueEqual(h.getLevel().getEntitiesOfClass(FallingBlockEntity.class, arena).size(), 0, "debris still in flight");
			// The core lands debris only where the caster could build, never on a player: the caster's own cells stay clear.
			BlockPos feet = BlockPos.containing(h.relativeVec(p.position()));
			h.assertBlockPresent(Blocks.AIR, feet);
			h.assertBlockPresent(Blocks.AIR, feet.above());
			// The landings are logged with the cast: one undo puts the arena back as it was, debris and tunnel alike.
			EditLog.UndoReport report = MagicCommand.undo(h.getLevel().getServer(), p.getUUID(), 1);
			h.assertTrue(report.restored() > 0, "undo restored " + report);
			List<BlockPos> left = changed(before, snapshot(h));
			h.assertTrue(left.isEmpty(), "cells still changed after the undo: " + left.size() + " e.g. " + (left.isEmpty() ? "" : left.getFirst()));
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 80)
	public void windSphereStopsAtFirstZombie(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WIND, 7);
		Zombie z = MagicTestKit.dummy(h, new BlockPos(16, 4, 8));
		MagicTestKit.fill(h, new BlockPos(10, 4, 12), new BlockPos(22, 10, 13), Blocks.STONE);
		long hash = MagicTestKit.blockHash(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		drive(h, p);
		h.assertValueEqual(MagicTestKit.castUltimate(p, SpellIds.WIND_SPHERE), Result.CAST, "result");
		FxEvent fx = rec.fx(p).getFirst();
		h.assertValueEqual(fx.data()[2], (float) WindSphere.Kind.CREATURE.ordinal(), "predicted to strike a creature");
		h.assertTrue(fx.entities().length == 1 && fx.entities()[0] == z.getId(), "the zombie is the one struck");
		h.assertTrue(Math.sqrt(z.getBoundingBox().distanceToSqr(fx.target())) <= DrillGeometry.TOUCH + 1.0E-6,
				"goes off at the zombie: " + h.relativeVec(fx.target()));
		h.assertValueEqual(z.getHealth(), 20f, "nothing yet while it forms");
		h.runAfterDelay(DrillGeometry.WINDUP + 4, () -> h.assertTrue(z.isDeadOrDying(), "the zombie is struck down (positive control) " + z.getHealth()));
		h.runAfterDelay(40, () -> {
			h.assertValueEqual(MagicTestKit.blockHash(h), hash, "the wall at 8 (and everything else) is untouched");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 80)
	public void windSphereBlastSparesLivestock(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WIND, 7);
		MagicTestKit.fill(h, new BlockPos(9, 4, 10), new BlockPos(23, 10, 14), Blocks.STONE);
		// Beside the path, within the blast of the wall hit, out of the sphere's touch.
		Zombie z = MagicTestKit.dummy(h, new BlockPos(14, 4, 9));
		Cow cow = h.spawn(EntityTypes.COW, new BlockPos(19, 4, 9));
		cow.setNoAi(true);
		cow.setPersistenceRequired();
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		drive(h, p);
		h.assertValueEqual(MagicTestKit.castUltimate(p, SpellIds.WIND_SPHERE), Result.CAST, "result");
		h.assertValueEqual(rec.fx(p).getFirst().data()[2], (float) WindSphere.Kind.BLOCK.ordinal(), "hits the wall");
		h.runAfterDelay(30, () -> {
			h.assertTrue(z.getHealth() < 20f, "the zombie in the blast is hurt (positive control) " + z.getHealth());
			h.assertValueEqual(cow.getHealth(), cow.getMaxHealth(), "cow health");
			h.assertValueEqual(cow.getDeltaMovement().horizontalDistance(), 0.0, "cow not thrown");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 80)
	public void windSphereTeleportEndsTheDash(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WIND, 7);
		Zombie z = MagicTestKit.dummy(h, new BlockPos(16, 4, 8));
		// The same cast without a teleport, 10 to the side (positive control).
		ServerPlayer q = MagicTestKit.caster(h, Element.WIND, 7);
		MagicTestKit.teleportTo(q, new BlockPos(5, 4, 4));
		Zombie zq = MagicTestKit.dummy(h, new BlockPos(5, 4, 8));
		drive(h, p);
		drive(h, q);
		h.assertValueEqual(MagicTestKit.castUltimate(p, SpellIds.WIND_SPHERE), Result.CAST, "result");
		h.assertValueEqual(MagicTestKit.castUltimate(q, SpellIds.WIND_SPHERE), Result.CAST, "control result");
		// Mid-form, the caster is teleported away (an ender pearl, Recall...): the sphere is gone, nothing is struck.
		h.runAfterDelay(2, () -> MagicTestKit.teleportTo(p, new BlockPos(27, 4, 26)));
		h.runAfterDelay(30, () -> {
			h.assertValueEqual(z.getHealth(), 20f, "the zombie ahead of the teleported caster");
			h.assertTrue(zq.isDeadOrDying(), "the control caster's zombie is struck " + zq.getHealth());
			h.assertTrue(p.getDeltaMovement().horizontalDistance() < 1.0E-9, "the teleported caster is not dashed " + p.getDeltaMovement());
			h.succeed();
		});
	}
}
