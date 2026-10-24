package local.carter.office.magic;

import java.util.Map;

import local.carter.office.magic.cast.CastService;
import local.carter.office.magic.cast.CastService.Result;
import local.carter.office.magic.command.MagicCommand;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellCost;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.SpellRegistry;
import local.carter.office.magic.spell.earth.EarthSpikes;
import local.carter.office.magic.spell.earth.EarthWall;
import local.carter.office.magic.spell.earth.EarthWallGeometry;
import local.carter.office.magic.spell.earth.MountainFist;
import local.carter.office.magic.spell.earth.MountainFistGeometry;
import local.carter.office.magic.spell.earth.Quake;
import local.carter.office.magic.spell.earth.QuakeGeometry;
import local.carter.office.magic.spell.earth.StoneBullet;
import local.carter.office.magic.terrain.EditLog.UndoReport;
import local.carter.office.magic.terrain.TempBlockData;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/** Server GameTests for the Earth lane: Stone Bullet and Earth Wall (Task 20); Earth Spikes, Quake and Mountain Fist (Task 21). */
public class EarthGameTests {
	// ---- Stone Bullet ----

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 30)
	public void stoneBulletArcsAndHits(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 1);
		Zombie z = MagicTestKit.dummy(h, new BlockPos(16, 4, 14));
		MagicTestKit.aim(p, new BlockPos(16, 5, 14));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		long start = h.getTick();
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.STONE_BULLET, 0f), Result.CAST, "result");
		h.assertValueEqual(rec.fx(p).size(), 1, "one fx");
		FxEvent e = rec.fx(p).get(0);
		h.assertValueEqual(e.data().length, 3, "fx data {speed, gravity, radius}");
		h.assertTrue(Math.abs(e.data()[0] - StoneBullet.SPEED) < 1e-6 && Math.abs(e.data()[1] - StoneBullet.GRAVITY) < 1e-6, "fx data");
		h.succeedWhen(() -> {
			h.assertTrue(h.getTick() - start <= 10, "took " + (h.getTick() - start) + " ticks");
			h.assertTrue(z.getHealth() <= 20 - StoneBullet.DAMAGE, "health " + z.getHealth());
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void stoneBulletFallsAlongItsArc(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 1);
		MagicTestKit.fill(h, new BlockPos(10, 4, 28), new BlockPos(22, 12, 28), Blocks.STONE);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		long hash = MagicTestKit.blockHash(h);
		double eyeY = p.getEyeY();
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.STONE_BULLET, 0f), Result.CAST, "result");
		FxEvent e = rec.fx(p).get(0);
		// Thrown flat 23.5 blocks: about 10.7 ticks, so it has dropped 0.02 × t(t−1)/2 ≈ 1.0 by the wall.
		h.assertTrue(Math.abs(e.target().z - h.absoluteVec(new Vec3(0, 0, 28)).z) < 0.01, "fx stops on the wall: " + e.target());
		double drop = eyeY - e.target().y;
		h.assertTrue(drop > 0.9 && drop < 1.2, "dropped " + drop + " along the arc");
		h.assertValueEqual(e.duration(), 11, "ticks to the wall");
		h.runAfterDelay(20, () -> {
			h.assertValueEqual(MagicTestKit.blockHash(h), hash, "a small spell changes no block");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 30)
	public void stoneBulletKnocksBack(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 1);
		Zombie z = MagicTestKit.movableDummy(h, new BlockPos(16, 4, 10));
		MagicTestKit.aim(p, new BlockPos(16, 5, 10));
		double z0 = z.getZ();
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.STONE_BULLET, 0f), Result.CAST, "result");
		h.succeedWhen(() -> {
			h.assertTrue(z.getHealth() <= 15, "health " + z.getHealth());
			h.assertTrue(z.getZ() - z0 >= 1, "moved " + (z.getZ() - z0) + " +Z");
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 60)
	public void stoneBulletStopsAtWall(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 1);
		MagicTestKit.fill(h, new BlockPos(12, 4, 7), new BlockPos(20, 9, 7), Blocks.STONE);
		Zombie z = MagicTestKit.dummy(h, new BlockPos(16, 4, 10));
		MagicTestKit.aim(p, new BlockPos(16, 5, 10));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		long hash = MagicTestKit.blockHash(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.STONE_BULLET, 0f), Result.CAST, "result");
		FxEvent e = rec.fx(p).get(0);
		h.assertTrue(Math.abs(e.target().z - h.absoluteVec(new Vec3(0, 0, 7)).z) < 0.01, "fx stops at the wall: " + e.target());
		h.runAfterDelay(20, () -> {
			h.assertValueEqual(z.getHealth(), 20f, "zombie behind the wall");
			h.assertValueEqual(MagicTestKit.blockHash(h), hash, "a small spell changes no block");
			// Positive control: with the wall gone the same throw lands.
			MagicTestKit.fill(h, new BlockPos(12, 4, 7), new BlockPos(20, 9, 7), Blocks.AIR);
			MagicAttachments.set(p, MagicAttachments.get(p).withReadyAt(Map.of()));
			h.assertValueEqual(MagicTestKit.cast(p, SpellIds.STONE_BULLET, 0f), Result.CAST, "second cast");
			h.runAfterDelay(10, () -> {
				h.assertTrue(z.getHealth() <= 15, "no wall: hit, health " + z.getHealth());
				h.succeed();
			});
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 30)
	public void stoneBulletPassesVillagerHitsZombie(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 1);
		Villager v = MagicTestKit.villager(h, new BlockPos(16, 4, 8));
		Zombie z = MagicTestKit.dummy(h, new BlockPos(16, 4, 12));
		MagicTestKit.aim(p, new BlockPos(16, 5, 12));
		Vec3 v0 = v.position();
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.STONE_BULLET, 1f), Result.CAST, "result");
		h.succeedWhen(() -> {
			h.assertTrue(z.getHealth() <= 20 - 2 * StoneBullet.DAMAGE + 0.01, "zombie hit at full charge, health " + z.getHealth());
			h.assertValueEqual(v.getHealth(), v.getMaxHealth(), "villager unhurt");
			h.assertTrue(v.position().distanceTo(v0) < 0.01, "villager unmoved");
		});
	}

	// ---- Earth Wall ----

	/** The cells of a 5-wide wall 3 ahead of {@link MagicTestKit#STAND} facing +Z: x 14..18, z 7, from y 4. */
	private static BlockPos cell(int x, int y) {
		return new BlockPos(x, y, 7);
	}

	private static ServerPlayer wallCaster(GameTestHelper h) {
		return MagicTestKit.caster(h, Element.EARTH, 2);
	}

	private static boolean temp(GameTestHelper h, BlockPos rel, BlockState s) {
		return TempBlockData.get(h.getLevel().getServer()).holds(h.getLevel(), h.absolutePos(rel), s);
	}

	/** What a wall made of {@code material} is at {@code rel} (its tuff and mud-brick cells are picked from the position). */
	private static BlockState wallAt(GameTestHelper h, BlockPos rel, Block material) {
		return EarthWall.cellMaterial(material.defaultBlockState(), h.absolutePos(rel));
	}

	/** {@code rel} is a temporary wall cell of {@code material}. */
	private static void assertWall(GameTestHelper h, BlockPos rel, Block material) {
		BlockState want = wallAt(h, rel, material);
		h.assertBlockState(rel, s -> s.equals(want), s -> Component.literal("expected wall " + want + " at " + rel + ", got " + s));
		h.assertTrue(temp(h, rel, want), "temporary at " + rel);
	}

	/** Stone and tuff in the arena (a stone-floor wall is stone with some tuff). */
	private static int stoneAndTuff(GameTestHelper h) {
		return MagicTestKit.count(h, Blocks.STONE) + MagicTestKit.count(h, Blocks.TUFF);
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 40)
	public void earthWallRisesFromGroundMaterial(GameTestHelper h) {
		ServerPlayer p = wallCaster(h);
		for (int x = 14; x <= 18; x++) h.setBlock(new BlockPos(x, 3, 7), Blocks.GRASS_BLOCK);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.EARTH_WALL, 0f), Result.CAST, "result");
		FxEvent e = rec.fx(p).get(0);
		h.assertValueEqual(e.duration(), EarthWallGeometry.DURATION, "fx duration");
		h.assertTrue(e.target().distanceTo(h.absoluteVec(Vec3.atBottomCenterOf(cell(16, 4)))) < 1e-6, "fx target = wall centre " + e.target());
		h.assertBlockPresent(Blocks.AIR, cell(16, 4));
		// Column by column: the centre is up before the ends.
		h.runAfterDelay(4, () -> {
			h.assertBlockState(cell(16, 4), s -> !s.isAir(), s -> Component.literal("centre column not up"));
			h.assertBlockPresent(Blocks.AIR, cell(14, 4));
		});
		h.runAfterDelay(10, () -> {
			int n = 0;
			int mud = 0;
			for (int x = 14; x <= 18; x++) {
				for (int y = 4; y <= 6; y++) {
					assertWall(h, cell(x, y), Blocks.PACKED_MUD);
					if (wallAt(h, cell(x, y), Blocks.PACKED_MUD).is(Blocks.PACKED_MUD)) mud++;
					n++;
				}
				h.assertBlockPresent(Blocks.AIR, cell(x, 7));
				// The floor stays (grass, or dirt if a random tick already found it covered).
				h.assertBlockState(new BlockPos(x, 3, 7), s -> s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT), s -> Component.literal("floor " + s));
			}
			h.assertValueEqual(n, 15, "cells");
			h.assertValueEqual(MagicTestKit.count(h, Blocks.PACKED_MUD) + MagicTestKit.count(h, Blocks.MUD_BRICKS)
					+ MagicTestKit.count(h, Blocks.TUFF), 15, "exactly the wall");
			h.assertTrue(mud >= 8, "mostly packed mud: " + mud);
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 40)
	public void earthWallCobblestoneOnSlabsStoneOnStone(GameTestHelper h) {
		ServerPlayer p = wallCaster(h);
		for (int x = 14; x <= 16; x++) h.setBlock(new BlockPos(x, 3, 7), Blocks.STONE_SLAB);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.EARTH_WALL, 0f), Result.CAST, "result");
		h.runAfterDelay(10, () -> {
			for (int y = 4; y <= 6; y++) {
				for (int x = 14; x <= 16; x++) assertWall(h, cell(x, y), Blocks.COBBLESTONE);
				// The stone floor is a block the wall may be made of.
				for (int x = 17; x <= 18; x++) assertWall(h, cell(x, y), Blocks.STONE);
			}
			h.assertBlockPresent(Blocks.STONE_SLAB, new BlockPos(15, 3, 7));
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 50)
	public void earthWallBlocksArrows(GameTestHelper h) {
		ServerPlayer p = wallCaster(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.EARTH_WALL, 0f), Result.CAST, "result");
		h.runAfterDelay(10, () -> {
			assertWall(h, cell(16, 5), Blocks.STONE);
			Vec3 v = new Vec3(0, 0.05, 1.5);
			Arrow blocked = MagicTestKit.arrow(h, new BlockPos(16, 5, 5), v);
			// Positive control: the same shot beside the wall flies on.
			Arrow free = MagicTestKit.arrow(h, new BlockPos(22, 5, 5), v);
			double wallZ = h.absoluteVec(new Vec3(0, 0, 7)).z;
			h.runAfterDelay(20, () -> {
				h.assertFalse(blocked.isRemoved(), "the blocked arrow is still there");
				h.assertTrue(blocked.getZ() <= wallZ + 0.05, "the arrow stopped at the wall, z " + (blocked.getZ() - wallZ));
				h.assertFalse(free.isRemoved(), "the control arrow is still there");
				h.assertTrue(free.getZ() > wallZ + 4, "the control arrow flew past, z " + (free.getZ() - wallZ));
				h.succeed();
			});
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 680)
	public void earthWallFallsAfterThirtySeconds(GameTestHelper h) {
		ServerPlayer p = wallCaster(h);
		int before = stoneAndTuff(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.EARTH_WALL, 0f), Result.CAST, "result");
		// Still standing just before it expires (600 ticks after the cast)...
		h.runAtTickTime(EarthWallGeometry.DURATION - 5, () -> {
			for (int x = 14; x <= 18; x++) for (int y = 4; y <= 6; y++) assertWall(h, cell(x, y), Blocks.STONE);
		});
		// ...and gone by 20 ticks after: a margin for the shared block applier when other lanes' terrain tests load it.
		h.runAtTickTime(EarthWallGeometry.DURATION + 20, () -> {
			for (int x = 14; x <= 18; x++) for (int y = 4; y <= 6; y++) h.assertBlockPresent(Blocks.AIR, cell(x, y));
			h.assertValueEqual(stoneAndTuff(h), before, "only what was there before");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 40)
	public void earthWallNeverReplacesChest(GameTestHelper h) {
		ServerPlayer p = wallCaster(h);
		// A chest in the centre column's bottom cell (below eye height, so the wall still stands 3 ahead).
		MagicTestKit.chest(h, cell(16, 4));
		// A barrel (another block entity) in the next column's middle cell: that column stops below it.
		h.setBlock(cell(17, 5), Blocks.BARREL);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.EARTH_WALL, 0f), Result.CAST, "result");
		h.runAfterDelay(10, () -> {
			h.assertBlockPresent(Blocks.CHEST, cell(16, 4));
			h.assertBlockPresent(Blocks.BARREL, cell(17, 5));
			// The centre column stands on the chest (made of cobblestone: a chest is no full block to take after).
			for (int y = 5; y <= 7; y++) assertWall(h, cell(16, y), Blocks.COBBLESTONE);
			assertWall(h, cell(17, 4), Blocks.STONE);
			h.assertBlockPresent(Blocks.AIR, cell(17, 6));
			// Positive control: the columns beside them are whole.
			for (int y = 4; y <= 6; y++) {
				assertWall(h, cell(15, y), Blocks.STONE);
				assertWall(h, cell(18, y), Blocks.STONE);
			}
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 40)
	public void earthWallLiftsZombieOntoIt(GameTestHelper h) {
		ServerPlayer p = wallCaster(h);
		Zombie z = MagicTestKit.movableDummy(h, cell(17, 4));
		double top = h.absoluteVec(new Vec3(0, 7, 0)).y;
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.EARTH_WALL, 0f), Result.CAST, "result");
		h.runAfterDelay(20, () -> {
			for (int y = 4; y <= 6; y++) assertWall(h, cell(17, y), Blocks.STONE);
			h.assertTrue(z.getY() >= top - 0.01, "zombie on top of the wall, y " + (z.getY() - top));
			h.assertFalse(z.isInWall(), "zombie not inside the wall");
			h.assertValueEqual(z.getHealth(), 20f, "zombie unhurt");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 50)
	public void earthWallNeverLiftsIntoACeiling(GameTestHelper h) {
		ServerPlayer p = wallCaster(h);
		// A two-high space over the zombie's column (ceiling at y 6): no room on top, so its cells stay open.
		h.setBlock(cell(17, 6), Blocks.STONE_BRICKS);
		Zombie z = MagicTestKit.movableDummy(h, cell(17, 4));
		// Positive control: a zombie in an open column is lifted.
		Zombie free = MagicTestKit.movableDummy(h, cell(15, 4));
		Vec3 z0 = z.position();
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.EARTH_WALL, 0f), Result.CAST, "result");
		h.runAfterDelay(30, () -> {
			h.assertBlockPresent(Blocks.AIR, cell(17, 4));
			h.assertBlockPresent(Blocks.AIR, cell(17, 5));
			h.assertTrue(z.position().distanceTo(z0) < 0.1, "the zombie under the ceiling stays put, moved " + z.position().distanceTo(z0));
			h.assertFalse(z.isInWall(), "not in a wall");
			h.assertValueEqual(z.getHealth(), 20f, "no suffocation damage");
			for (int y = 4; y <= 6; y++) assertWall(h, cell(15, y), Blocks.STONE);
			h.assertTrue(free.getY() >= h.absoluteVec(new Vec3(0, 7, 0)).y - 0.01, "the free zombie is lifted onto its column");
			h.assertValueEqual(free.getHealth(), 20f, "lifted zombie unhurt");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 40)
	public void earthWallLeavesFriendsCellOpen(GameTestHelper h) {
		ServerPlayer p = wallCaster(h);
		ServerPlayer friend = MagicTestKit.otherPlayer(h, cell(15, 4));
		Vec3 f0 = friend.position();
		Villager v = MagicTestKit.villager(h, cell(17, 4));
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.EARTH_WALL, 0f), Result.CAST, "result");
		h.runAfterDelay(10, () -> {
			for (int y = 4; y <= 6; y++) {
				h.assertBlockPresent(Blocks.AIR, cell(15, y));
				h.assertBlockPresent(Blocks.AIR, cell(17, y));
				// Positive control: the rest of the wall stands.
				assertWall(h, cell(14, y), Blocks.STONE);
				assertWall(h, cell(16, y), Blocks.STONE);
				assertWall(h, cell(18, y), Blocks.STONE);
			}
			h.assertTrue(friend.position().distanceTo(f0) < 0.01, "friend not moved");
			h.assertTrue(friend.getDeltaMovement().length() < 0.1, "friend not pushed");
			h.assertTrue(v.getY() < h.absoluteVec(new Vec3(0, 5, 0)).y, "villager not lifted");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 40)
	public void earthWallFullChargeIsSevenByFour(GameTestHelper h) {
		ServerPlayer p = wallCaster(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		int before = stoneAndTuff(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.EARTH_WALL, 1f), Result.CAST, "result");
		float[] d = rec.fx(p).get(0).data();
		h.assertTrue(d.length == 4 && d[0] == 1 && d[1] == 0 && d[2] == 7 && d[3] == 4, "fx data {1, 0, 7, 4}");
		h.runAfterDelay(10, () -> {
			for (int x = 13; x <= 19; x++) {
				for (int y = 4; y <= 7; y++) assertWall(h, cell(x, y), Blocks.STONE);
				h.assertBlockPresent(Blocks.AIR, cell(x, 8));
			}
			h.assertValueEqual(stoneAndTuff(h), before + 28, "28 cells");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 40)
	public void earthWallNeverRisesBeyondAWall(GameTestHelper h) {
		ServerPlayer p = wallCaster(h);
		// A glass wall right in front: the earth wall rises on this side of it, never on the far side.
		MagicTestKit.fill(h, new BlockPos(8, 4, 6), new BlockPos(24, 10, 6), Blocks.GLASS);
		long glass = MagicTestKit.count(h, Blocks.GLASS);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.EARTH_WALL, 0f), Result.CAST, "result");
		h.runAfterDelay(10, () -> {
			for (int x = 12; x <= 20; x++) for (int y = 4; y <= 8; y++) h.assertBlockPresent(Blocks.AIR, new BlockPos(x, y, 7));
			for (int x = 14; x <= 18; x++) assertWall(h, new BlockPos(x, 4, 5), Blocks.STONE);
			h.assertValueEqual((long) MagicTestKit.count(h, Blocks.GLASS), glass, "the glass is untouched");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 40)
	public void earthWallClimbsAStep(GameTestHelper h) {
		ServerPlayer p = wallCaster(h);
		// A one-block ledge under the right half of the wall: those columns stand on it.
		Block ledge = Blocks.STONE_BRICKS;
		MagicTestKit.fill(h, new BlockPos(17, 4, 6), new BlockPos(20, 4, 9), ledge);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.EARTH_WALL, 0f), Result.CAST, "result");
		h.runAfterDelay(10, () -> {
			for (int y = 4; y <= 6; y++) assertWall(h, cell(16, y), Blocks.STONE);
			for (int x = 17; x <= 18; x++) {
				h.assertBlockPresent(ledge, cell(x, 4));
				for (int y = 5; y <= 7; y++) assertWall(h, cell(x, y), ledge);
				h.assertBlockPresent(Blocks.AIR, cell(x, 8));
			}
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 40)
	public void earthWallClearsPlainsButNeverCrops(GameTestHelper h) {
		ServerPlayer p = wallCaster(h);
		// A plains strip: grass with short grass, a poppy and a fern on it; wheat on farmland at the right end.
		for (int x = 14; x <= 17; x++) h.setBlock(new BlockPos(x, 3, 7), Blocks.GRASS_BLOCK);
		h.setBlock(cell(14, 4), Blocks.SHORT_GRASS);
		h.setBlock(cell(15, 4), Blocks.POPPY);
		h.setBlock(cell(16, 4), Blocks.FERN);
		h.setBlock(new BlockPos(18, 3, 7), Blocks.FARMLAND);
		h.setBlock(cell(18, 4), Blocks.WHEAT);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.EARTH_WALL, 0f), Result.CAST, "result");
		h.runAfterDelay(10, () -> {
			// A solid wall over the plants: every column rises from the ground, the plants are gone.
			for (int x = 14; x <= 17; x++) for (int y = 4; y <= 6; y++) assertWall(h, cell(x, y), Blocks.PACKED_MUD);
			// The wheat is never touched, and its column is an open gap.
			h.assertBlockPresent(Blocks.WHEAT, cell(18, 4));
			h.assertBlockPresent(Blocks.FARMLAND, new BlockPos(18, 3, 7));
			h.assertBlockPresent(Blocks.AIR, cell(18, 5));
			h.assertBlockPresent(Blocks.AIR, cell(18, 6));
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 640)
	public void earthWallPlantsStayClearedAfterTheFall(GameTestHelper h) {
		ServerPlayer p = wallCaster(h);
		h.setBlock(cell(16, 3), Blocks.GRASS_BLOCK);
		h.setBlock(cell(16, 4), Blocks.POPPY);
		// Positive control: a poppy beside the wall's line is left alone.
		h.setBlock(new BlockPos(16, 3, 9), Blocks.GRASS_BLOCK);
		h.setBlock(new BlockPos(16, 4, 9), Blocks.POPPY);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.EARTH_WALL, 0f), Result.CAST, "result");
		h.runAtTickTime(610, () -> {
			// The tearing-out is a logged permanent edit: when the temporary wall goes, the cell is clear.
			h.assertBlockPresent(Blocks.AIR, cell(16, 4));
			h.assertBlockPresent(Blocks.POPPY, new BlockPos(16, 4, 9));
			h.succeed();
		});
	}

	// ---- Earth M3: registration ----

	@GameTest(structure = MagicTestKit.ARENA)
	public void earthM3SpellsRegisteredInTheirSlots(GameTestHelper h) {
		Spell spikes = SpellRegistry.get(SpellIds.EARTH_SPIKES).orElseThrow(() -> new AssertionError("Earth Spikes not registered"));
		h.assertTrue(spikes instanceof EarthSpikes, "Earth Spikes is " + spikes);
		Spell quake = SpellRegistry.get(SpellIds.QUAKE).orElseThrow(() -> new AssertionError("Quake not registered"));
		h.assertTrue(quake instanceof Quake, "Quake is " + quake);
		Spell fist = SpellRegistry.get(SpellIds.MOUNTAIN_FIST).orElseThrow(() -> new AssertionError("Mountain Fist not registered"));
		h.assertTrue(fist instanceof MountainFist, "Mountain Fist is " + fist);
		h.assertValueEqual(SpellRegistry.at(Element.EARTH, Slots.SMALL_B).map(Spell::id).orElse(null), SpellIds.EARTH_SPIKES, "earth slot 2");
		h.assertValueEqual(SpellRegistry.at(Element.EARTH, Slots.MEDIUM_B).map(Spell::id).orElse(null), SpellIds.QUAKE, "earth slot 3");
		h.assertValueEqual(SpellRegistry.at(Element.EARTH, Slots.ULTIMATE).map(Spell::id).orElse(null), SpellIds.MOUNTAIN_FIST, "earth slot 4");
		h.assertValueEqual(fist.cost(), SpellCost.forSlot(Slots.ULTIMATE), "ultimate cost");
		h.succeed();
	}

	// ---- Earth Spikes ----

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 100)
	public void earthSpikesChangeNoBlocks(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 3);
		Zombie onLine = MagicTestKit.dummy(h, new BlockPos(16, 4, 9));
		// Positive control for "on the line": a zombie two blocks beside it.
		Zombie beside = MagicTestKit.dummy(h, new BlockPos(18, 4, 9));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		long hash = MagicTestKit.blockHash(h);
		long start = h.getTick();
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.EARTH_SPIKES, 0f), Result.CAST, "result");
		FxEvent e = rec.fx(p).get(0);
		h.assertValueEqual(e.data()[0], 8f, "line length at charge 0");
		h.assertValueEqual(e.duration(), 8, "eight spikes, one a tick");
		h.assertValueEqual(BlockPos.containing(e.target()), h.absolutePos(new BlockPos(16, 4, 5)), "the line starts just ahead");
		h.runAtTickTime(start + 80, () -> {
			h.assertValueEqual(MagicTestKit.blockHash(h), hash, "a small spell changes no block");
			h.assertTrue(onLine.getHealth() <= 20 - EarthSpikes.DAMAGE, "zombie on the line hurt: " + onLine.getHealth());
			h.assertValueEqual(beside.getHealth(), 20f, "zombie beside the line");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void earthSpikesThrowEnemiesUpNeverVillagers(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 3);
		Zombie z = MagicTestKit.movableDummy(h, new BlockPos(16, 4, 8));
		Villager v = MagicTestKit.villager(h, new BlockPos(16, 4, 11));
		double y0 = z.getY();
		Vec3 v0 = v.position();
		double[] top = {y0};
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.EARTH_SPIKES, 0f), Result.CAST, "result");
		h.onEachTick(() -> top[0] = Math.max(top[0], z.getY()));
		h.runAfterDelay(20, () -> {
			h.assertTrue(top[0] - y0 >= 0.8, "zombie thrown up " + (top[0] - y0));
			h.assertTrue(z.getHealth() <= 20 - EarthSpikes.DAMAGE, "zombie hurt " + z.getHealth());
			h.assertValueEqual(v.getHealth(), v.getMaxHealth(), "villager unhurt");
			h.assertTrue(v.position().distanceTo(v0) < 0.01, "villager unmoved");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void earthSpikesStopAtAWall(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 3);
		MagicTestKit.fill(h, new BlockPos(14, 4, 9), new BlockPos(18, 8, 9), Blocks.STONE);
		Zombie before = MagicTestKit.dummy(h, new BlockPos(16, 4, 7));
		Zombie behind = MagicTestKit.dummy(h, new BlockPos(16, 4, 11));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.EARTH_SPIKES, 1f), Result.CAST, "result");
		h.assertValueEqual(rec.fx(p).get(0).duration(), 4, "four spikes, then the wall");
		h.runAfterDelay(20, () -> {
			h.assertTrue(before.getHealth() < 20, "zombie before the wall hurt: " + before.getHealth());
			h.assertValueEqual(behind.getHealth(), 20f, "zombie behind the wall");
			h.succeed();
		});
	}

	// ---- Quake ----

	private static final BlockPos QUAKE_AT = new BlockPos(16, 4, 16);

	/** A Quake caster (level 5) in the middle of the arena, so the whole ring is on the arena floor. */
	private static ServerPlayer quakeCaster(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 5);
		MagicTestKit.teleportTo(p, QUAKE_AT);
		return p;
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 40)
	public void quakeLaunchesGroundedNotFlying(GameTestHelper h) {
		ServerPlayer p = quakeCaster(h);
		Zombie z = MagicTestKit.movableDummy(h, QUAKE_AT.offset(0, 0, 4));
		Bat bat = h.spawn(EntityTypes.BAT, QUAKE_AT.offset(3, 2, 0));
		bat.setNoAi(true);
		bat.setResting(false);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		Vec3 bat0 = bat.position();
		double y0 = z.getY();
		double[] top = {y0};
		// The ring reaches the zombie (4 out) on tick 8: it has long landed by then; the NoAI bat never comes down.
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.QUAKE, 0f), Result.CAST, "result");
		FxEvent e = rec.fx(p).get(0);
		h.assertValueEqual(e.data()[0], 6f, "radius at charge 0");
		h.assertValueEqual(e.duration(), 12, "ticks to radius 6");
		boolean[] grounded = {false};
		h.onEachTick(() -> {
			top[0] = Math.max(top[0], z.getY());
			if (h.getTick() == 6) grounded[0] = z.onGround();
		});
		h.runAfterDelay(20, () -> {
			h.assertTrue(grounded[0], "the zombie stood on the ground before the ring came");
			h.assertTrue(top[0] - y0 >= 0.5, "grounded zombie went up " + (top[0] - y0));
			h.assertTrue(z.getHealth() <= 20 - Quake.DAMAGE, "grounded zombie hurt " + z.getHealth());
			h.assertFalse(bat.onGround(), "the bat flies");
			h.assertValueEqual(bat.getHealth(), bat.getMaxHealth(), "flying bat unhurt");
			h.assertTrue(bat.position().distanceTo(bat0) < 0.01, "flying bat unmoved");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 40)
	public void quakeCracksAtMostQuarter(GameTestHelper h) {
		ServerPlayer p = quakeCaster(h);
		int stone = MagicTestKit.count(h, Blocks.STONE);
		h.assertValueEqual(MagicTestKit.count(h, Blocks.COBBLESTONE), 0, "no cobblestone before");
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.QUAKE, 0f), Result.CAST, "result");
		int ring = QuakeGeometry.disc(QuakeGeometry.radius(0F)).size();
		h.runAfterDelay(25, () -> {
			int cracked = MagicTestKit.count(h, Blocks.COBBLESTONE);
			h.assertTrue(cracked <= ring * QuakeGeometry.CRACK_SHARE + 1, cracked + " cracked of a ring of " + ring);
			h.assertTrue(cracked >= ring / 5, "the ring cracked the floor: " + cracked + " of " + ring);
			h.assertValueEqual(MagicTestKit.count(h, Blocks.STONE) + cracked, stone, "stone only became cobblestone");
			// Only the top of the floor, and only inside the ring.
			for (int x = 0; x < MagicTestKit.SIZE_X; x++) {
				for (int z = 0; z < MagicTestKit.SIZE_Z; z++) {
					for (int y = 0; y < MagicTestKit.FLOOR_TOP; y++) {
						h.assertFalse(h.getBlockState(new BlockPos(x, y, z)).is(Blocks.COBBLESTONE), "cracked below the top at " + x + "," + y + "," + z);
					}
					if (h.getBlockState(new BlockPos(x, MagicTestKit.FLOOR_TOP, z)).is(Blocks.COBBLESTONE)) {
						double d = Math.sqrt(Math.pow(x - QUAKE_AT.getX(), 2) + Math.pow(z - QUAKE_AT.getZ(), 2));
						h.assertTrue(d <= QuakeGeometry.radius(0F) + 1e-6, "cracked outside the ring at " + d);
					}
				}
			}
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 40)
	public void quakeCracksGrassDirtStoneOnly(GameTestHelper h) {
		ServerPlayer p = quakeCaster(h);
		// A full-charge ring (radius 8) over a floor of grass, dirt, stone and planks.
		for (int x = 6; x <= 26; x++) {
			for (int z = 6; z <= 26; z++) {
				Block b = switch (Math.floorMod(x + 2 * z, 4)) {
					case 0 -> Blocks.GRASS_BLOCK;
					case 1 -> Blocks.DIRT;
					case 2 -> Blocks.OAK_PLANKS;
					default -> Blocks.STONE;
				};
				h.setBlock(new BlockPos(x, MagicTestKit.FLOOR_TOP, z), b);
			}
		}
		int planks = MagicTestKit.count(h, Blocks.OAK_PLANKS);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.QUAKE, 1f), Result.CAST, "result");
		h.runAfterDelay(25, () -> {
			h.assertValueEqual(MagicTestKit.count(h, Blocks.OAK_PLANKS), planks, "planks never crack");
			int coarse = MagicTestKit.count(h, Blocks.COARSE_DIRT);
			int cobble = MagicTestKit.count(h, Blocks.COBBLESTONE);
			h.assertTrue(coarse > 0, "dirt cracked to coarse dirt");
			h.assertTrue(cobble > 0, "stone cracked to cobblestone");
			// Grass → dirt: the grass columns that are plain dirt now.
			int grassToDirt = 0;
			for (int x = 6; x <= 26; x++) {
				for (int z = 6; z <= 26; z++) {
					if (Math.floorMod(x + 2 * z, 4) == 0 && h.getBlockState(new BlockPos(x, MagicTestKit.FLOOR_TOP, z)).is(Blocks.DIRT)) grassToDirt++;
				}
			}
			h.assertTrue(grassToDirt > 0, "grass cracked to dirt");
			int ring = QuakeGeometry.disc(QuakeGeometry.radius(1F)).size();
			int changed = coarse + cobble + grassToDirt;
			h.assertTrue(changed <= QuakeGeometry.crackCount(ring), "at most a quarter: " + changed + " of " + ring);
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void quakeSheltersBehindAWall(GameTestHelper h) {
		ServerPlayer p = quakeCaster(h);
		MagicTestKit.fill(h, QUAKE_AT.offset(-2, 0, 3), QUAKE_AT.offset(2, 3, 3), Blocks.GLASS);
		Zombie behind = MagicTestKit.movableDummy(h, QUAKE_AT.offset(0, 0, 5));
		Zombie open = MagicTestKit.movableDummy(h, QUAKE_AT.offset(0, 0, -4));
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.QUAKE, 0f), Result.CAST, "result");
		h.runAfterDelay(20, () -> {
			h.assertValueEqual(behind.getHealth(), 20f, "zombie behind the glass wall");
			h.assertTrue(open.getHealth() <= 20 - Quake.DAMAGE, "zombie in the open hurt " + open.getHealth());
			h.succeed();
		});
	}

	// ---- Mountain Fist ----

	/** The target cell 12 ahead of {@link MagicTestKit#STAND}. */
	private static final BlockPos FIST_AT = new BlockPos(16, 4, 16);

	/** Turns {@code p}'s eyes to the test-relative point {@code rel} exactly (the kit's {@code aim} takes a block centre). */
	private static void lookAt(GameTestHelper h, ServerPlayer p, Vec3 rel) {
		Vec3 d = h.absoluteVec(rel).subtract(p.getEyePosition());
		float yaw = Mth.wrapDegrees((float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f);
		float pitch = (float) -(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG);
		p.setYRot(yaw);
		p.setXRot(pitch);
		p.yRotO = yaw;
		p.xRotO = pitch;
		p.setYHeadRot(yaw);
	}

	private static ServerPlayer fistCaster(GameTestHelper h, int level) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, level);
		lookAt(h, p, Vec3.atBottomCenterOf(FIST_AT));
		return p;
	}

	private static boolean spire(BlockState s) {
		return s.is(Blocks.STONE) || s.is(Blocks.ANDESITE);
	}

	/** Spire blocks above the floor within {@code r} columns of the target. */
	private static int spireBlocks(GameTestHelper h, int r) {
		int n = 0;
		for (BlockPos q : BlockPos.betweenClosed(FIST_AT.offset(-r, 0, -r), FIST_AT.offset(r, MagicTestKit.SIZE_Y - 1 - FIST_AT.getY(), r))) {
			if (spire(h.getBlockState(q))) n++;
		}
		return n;
	}

	/** The highest spire block above the floor within {@code r} columns of the target, as a height over the floor (0 = none). */
	private static int spireHeight(GameTestHelper h, int r) {
		int top = 0;
		for (BlockPos q : BlockPos.betweenClosed(FIST_AT.offset(-r, 0, -r), FIST_AT.offset(r, MagicTestKit.SIZE_Y - 1 - FIST_AT.getY(), r))) {
			if (spire(h.getBlockState(q))) top = Math.max(top, q.getY() - FIST_AT.getY() + 1);
		}
		return top;
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 80)
	public void mountainFistBuildsSpire(GameTestHelper h) {
		ServerPlayer p = fistCaster(h, 7);
		BlockPos chest = FIST_AT.east();
		MagicTestKit.chest(h, chest);
		h.getBlockEntity(chest, ChestBlockEntity.class).setItem(0, new ItemStack(Items.DIAMOND, 3));
		Zombie z = MagicTestKit.movableDummy(h, FIST_AT.offset(0, 0, 2));
		z.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
		z.setHealth(200);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		double y0 = z.getY();
		double[] top = {y0};
		double[] lift = {0};
		// Registered up front: adding tick tasks from inside a running one can make GameTest run that one again.
		h.onEachTick(() -> {
			top[0] = Math.max(top[0], z.getY());
			lift[0] = Math.max(lift[0], z.getDeltaMovement().y);
		});
		h.assertValueEqual(MagicTestKit.castUltimate(p, SpellIds.MOUNTAIN_FIST), Result.CAST, "result");
		FxEvent e = rec.fx(p).get(0);
		h.assertValueEqual(BlockPos.containing(e.target()), h.absolutePos(FIST_AT), "target cell");
		h.assertValueEqual(e.data()[0], 3f, "base radius at level 7");
		h.assertValueEqual(e.data()[1], 8f, "height at level 7");
		h.assertValueEqual(e.duration(), MountainFistGeometry.RISE, "a 20-tick rise");
		h.runAfterDelay(MountainFistGeometry.RISE + 4, () -> {
			int placed = spireBlocks(h, 3);
			h.assertTrue(placed >= 50, "spire blocks placed: " + placed);
			h.assertTrue(placed <= 96, "no more than the level-7 cone: " + placed);
			int height = spireHeight(h, 3);
			h.assertTrue(height >= 7 && height <= 8, "spire height " + height);
			h.assertBlockPresent(Blocks.CHEST, chest);
			h.assertValueEqual(h.getBlockEntity(chest, ChestBlockEntity.class).getItem(0).getCount(), 3, "the chest keeps its diamonds");
			// Its neighbours in the ground layer are spire (the positive control).
			h.assertTrue(spire(h.getBlockState(FIST_AT)) && spire(h.getBlockState(FIST_AT.west())), "spire round the chest");
			h.assertTrue(lift[0] >= 1.0 || top[0] - y0 >= 3, "zombie launched: up " + (top[0] - y0) + ", lift " + lift[0]);
			h.assertTrue(z.getHealth() <= 200 - MountainFistGeometry.DAMAGE, "zombie hurt " + z.getHealth());
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 80)
	public void mountainFistLeavesAPlayersCellOpen(GameTestHelper h) {
		ServerPlayer p = fistCaster(h, 7);
		ServerPlayer friend = MagicTestKit.otherPlayer(h, FIST_AT.offset(1, 0, 0));
		Vec3 at = friend.position();
		h.assertValueEqual(MagicTestKit.castUltimate(p, SpellIds.MOUNTAIN_FIST), Result.CAST, "result");
		h.runAfterDelay(MountainFistGeometry.RISE + 4, () -> {
			// The friend's column and the 8 round it stay open all the way up: never left standing in a one-block shaft.
			for (BlockPos q : BlockPos.betweenClosed(FIST_AT.offset(0, 0, -1), FIST_AT.offset(2, 7, 1))) {
				h.assertFalse(spire(h.getBlockState(q)), "spire block round the friend at " + q);
			}
			h.assertValueEqual(friend.getHealth(), friend.getMaxHealth(), "the player is unhurt");
			h.assertTrue(friend.position().distanceTo(at) < 0.01, "and unmoved");
			h.assertTrue(friend.getDeltaMovement().y < 0.5, "and never launched");
			// Positive control: the rest of the spire stands.
			h.assertTrue(spire(h.getBlockState(FIST_AT.offset(-1, 0, 0))) && spire(h.getBlockState(FIST_AT.offset(-2, 0, 0)))
					&& spire(h.getBlockState(FIST_AT.offset(0, 0, 2))), "the rest of the spire stands");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 80)
	public void mountainFistAtLevelTenIsCapped(GameTestHelper h) {
		ServerPlayer p = fistCaster(h, 10);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.castUltimate(p, SpellIds.MOUNTAIN_FIST), Result.CAST, "result");
		FxEvent e = rec.fx(p).get(0);
		h.assertValueEqual(e.data()[0], 6f, "base radius capped at 6");
		h.assertValueEqual(e.data()[1], 12f, "height capped at 12");
		h.runAfterDelay(MountainFistGeometry.RISE + 4, () -> {
			int height = spireHeight(h, 6);
			h.assertTrue(height >= 11 && height <= 12, "spire height " + height);
			int placed = spireBlocks(h, 6);
			h.assertTrue(placed >= 300 && placed <= MountainFistGeometry.MAX_BLOCKS, "spire blocks " + placed);
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 80)
	public void mountainFistNeverRisesRoundItsCaster(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 7);
		// Looking at the ground a block ahead of its own feet.
		lookAt(h, p, Vec3.atBottomCenterOf(MagicTestKit.STAND.south()));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.castUltimate(p, SpellIds.MOUNTAIN_FIST), Result.CAST, "result");
		Vec3 target = rec.fx(p).get(0).target();
		double out = Math.sqrt(Math.pow(target.x - p.getX(), 2) + Math.pow(target.z - p.getZ(), 2));
		double min = MountainFistGeometry.baseRadius(7) + MountainFistGeometry.CLEAR;
		h.assertTrue(out >= min - 0.75 && out <= min + 1, "moved out along the look: " + out);
		h.runAfterDelay(MountainFistGeometry.RISE + 4, () -> {
			// The caster's cells stay open (the cone's edge is a block or more away); the spire stands ahead (the positive control).
			h.assertBlockPresent(Blocks.AIR, MagicTestKit.STAND);
			h.assertBlockPresent(Blocks.AIR, MagicTestKit.STAND.above());
			h.assertTrue(spire(h.getLevel().getBlockState(BlockPos.containing(target))), "the spire stands at the moved target");
			h.succeed();
		});
	}

	// ---- Fix round 1: refusals, undo, claims ----

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 80)
	public void mountainFistNeverBuildsInsideAHouse(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 7);
		// A house wall 2 blocks ahead of the caster; looking at the ground just in front of it pushes the target out past it.
		MagicTestKit.fill(h, new BlockPos(10, 4, 6), new BlockPos(22, 9, 6), Blocks.GLASS);
		lookAt(h, p, Vec3.atBottomCenterOf(MagicTestKit.STAND.south()));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		long hash = MagicTestKit.blockHash(h);
		int mana = MagicAttachments.mana(p);
		h.assertValueEqual(MagicTestKit.castUltimate(p, SpellIds.MOUNTAIN_FIST), Result.FIZZLED, "refused behind the wall");
		h.assertTrue(rec.fx(p).isEmpty(), "no fx");
		h.assertTrue(rec.messageKeys(p).contains(CastService.SPELL_FAILED_KEY), "told it failed: " + rec.messageKeys(p));
		h.assertValueEqual(MagicAttachments.mana(p), mana, "nothing spent");
		h.runAfterDelay(MountainFistGeometry.RISE + 4, () -> {
			h.assertValueEqual(MagicTestKit.blockHash(h), hash, "no block changed in or out of the house");
			// Positive control: with the wall gone the same cast raises the spire out there.
			MagicTestKit.fill(h, new BlockPos(10, 4, 6), new BlockPos(22, 9, 6), Blocks.AIR);
			h.assertValueEqual(MagicTestKit.castUltimate(p, SpellIds.MOUNTAIN_FIST), Result.CAST, "no wall: cast");
			h.runAfterDelay(MountainFistGeometry.RISE + 4, () -> {
				h.assertTrue(spireBlocks(h, 12) >= 50, "the spire stands: " + spireBlocks(h, 12));
				h.succeed();
			});
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 60)
	public void mountainFistRefusesACliffFace(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 7);
		// A cliff 13 high, 12 ahead: pointing high up its face would drop the target 10 below the aim.
		MagicTestKit.fill(h, new BlockPos(12, 4, 16), new BlockPos(20, 16, 18), Blocks.STONE);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		lookAt(h, p, new Vec3(16.5, 14.5, 16.0));
		h.assertValueEqual(MagicTestKit.castUltimate(p, SpellIds.MOUNTAIN_FIST), Result.FIZZLED, "refused: a drop of about 10");
		h.assertTrue(rec.fx(p).isEmpty(), "no fx");
		// Positive control: the foot of the same face (a drop of about 1) casts, in front of the cliff.
		lookAt(h, p, new Vec3(16.5, 5.0, 16.0));
		h.assertValueEqual(MagicTestKit.castUltimate(p, SpellIds.MOUNTAIN_FIST), Result.CAST, "the cliff's foot: cast");
		h.assertValueEqual(BlockPos.containing(rec.fx(p).get(0).target()), h.absolutePos(new BlockPos(16, 4, 15)), "in front of the face");
		h.runAfterDelay(MountainFistGeometry.RISE + 4, h::succeed);
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 40)
	public void mountainFistRefusesTheSky(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 7);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		long hash = MagicTestKit.blockHash(h);
		// Looking up into the sky: a miss refuses (here the arena's barrier roof catches the ray, 20 above the floor: refused too).
		lookAt(h, p, new Vec3(16.5, 22, 16.5));
		h.assertValueEqual(MagicTestKit.castUltimate(p, SpellIds.MOUNTAIN_FIST), Result.FIZZLED, "refused: nothing to stand on");
		h.assertTrue(rec.fx(p).isEmpty(), "no fx");
		h.runAfterDelay(10, () -> {
			h.assertValueEqual(MagicTestKit.blockHash(h), hash, "no block changed");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 120)
	public void mountainFistIsLoggedAndUndone(GameTestHelper h) {
		ServerPlayer p = fistCaster(h, 7);
		long hash = MagicTestKit.blockHash(h);
		h.assertValueEqual(MagicTestKit.castUltimate(p, SpellIds.MOUNTAIN_FIST), Result.CAST, "result");
		h.runAfterDelay(80, () -> {
			int spire = spireBlocks(h, 3);
			h.assertTrue(spire >= 50, "positive control: the spire stands before the undo (" + spire + ")");
			UndoReport r = MagicCommand.undo(h.getLevel().getServer(), p.getUUID(), 1);
			h.assertTrue(r.casts() == 1 && r.restored() >= spire, "undo restores the spire and the debris: " + r);
			h.assertValueEqual(MagicTestKit.blockHash(h), hash, "the arena is as it was");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 60)
	public void quakeCracksAreLoggedAndUndone(GameTestHelper h) {
		ServerPlayer p = quakeCaster(h);
		long hash = MagicTestKit.blockHash(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.QUAKE, 1f), Result.CAST, "result");
		h.runAfterDelay(30, () -> {
			int cracked = MagicTestKit.count(h, Blocks.COBBLESTONE);
			h.assertTrue(cracked > 0, "positive control: cracks before the undo");
			UndoReport r = MagicCommand.undo(h.getLevel().getServer(), p.getUUID(), 1);
			h.assertValueEqual(r, new UndoReport(cracked, 1, 0), "undo report");
			h.assertValueEqual(MagicTestKit.count(h, Blocks.COBBLESTONE), 0, "no cracks after the undo");
			h.assertValueEqual(MagicTestKit.blockHash(h), hash, "the floor is as it was");
			h.succeed();
		});
	}

	/** The terrain tests that place a hologram emitter (the fallback claim rule protects 32 round it). */
	private static final String ENV_EMITTER = "office-test:magic_terrain_emitter";

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV_EMITTER, maxTicks = 80)
	public void mountainFistRespectsClaimFallback(GameTestHelper h) {
		ServerPlayer p = fistCaster(h, 7);
		// An emitter about 20 from the target: the fallback claim rule protects everything within 32 of it.
		MagicTestKit.emitter(h, new BlockPos(4, 4, 0), p);
		Zombie z = MagicTestKit.movableDummy(h, FIST_AT.offset(0, 0, 2));
		z.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
		z.setHealth(200);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		long hash = MagicTestKit.blockHash(h);
		h.assertValueEqual(MagicTestKit.castUltimate(p, SpellIds.MOUNTAIN_FIST), Result.CAST, "result");
		h.runAfterDelay(MountainFistGeometry.RISE + 20, () -> {
			h.assertValueEqual(MagicTestKit.blockHash(h), hash, "no spire, no debris: the land is protected");
			h.assertTrue(rec.messageKeys(p).contains("magic.office.protected"), "told the land is protected: " + rec.messageKeys(p));
			// Positive control: the strike itself still lands.
			h.assertTrue(z.getHealth() <= 200 - MountainFistGeometry.DAMAGE, "the zombie is still hurt: " + z.getHealth());
			h.succeed();
		});
	}
}
