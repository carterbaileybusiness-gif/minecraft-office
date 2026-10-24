package local.carter.office.magic;

import java.util.Map;

import local.carter.office.magic.cast.CastService.Result;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.earth.EarthWallGeometry;
import local.carter.office.magic.spell.earth.StoneBullet;
import local.carter.office.magic.terrain.TempBlockData;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/** Server GameTests for the Earth lane: Stone Bullet and Earth Wall (Task 20). */
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

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 40)
	public void earthWallRisesFromGroundMaterial(GameTestHelper h) {
		ServerPlayer p = wallCaster(h);
		for (int x = 14; x <= 18; x++) h.setBlock(new BlockPos(x, 3, 7), Blocks.GRASS_BLOCK);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.EARTH_WALL, 0f), Result.CAST, "result");
		FxEvent e = rec.fx(p).get(0);
		h.assertValueEqual(e.duration(), EarthWallGeometry.DURATION, "fx duration");
		h.assertTrue(e.target().distanceTo(h.absoluteVec(Vec3.atBottomCenterOf(cell(16, 4)))) < 1e-6, "fx target = wall centre " + e.target());
		h.assertValueEqual(MagicTestKit.count(h, Blocks.PACKED_MUD), 0, "nothing yet");
		// Column by column: the centre is up before the ends.
		h.runAfterDelay(4, () -> {
			h.assertBlockPresent(Blocks.PACKED_MUD, cell(16, 4));
			h.assertBlockPresent(Blocks.AIR, cell(14, 4));
		});
		h.runAfterDelay(10, () -> {
			BlockState mud = Blocks.PACKED_MUD.defaultBlockState();
			int n = 0;
			for (int x = 14; x <= 18; x++) {
				for (int y = 4; y <= 6; y++) {
					h.assertBlockPresent(Blocks.PACKED_MUD, cell(x, y));
					h.assertTrue(temp(h, cell(x, y), mud), "temporary at " + cell(x, y));
					n++;
				}
				h.assertBlockPresent(Blocks.AIR, cell(x, 7));
				// The floor stays (grass, or dirt if a random tick already found it covered).
				h.assertBlockNotPresent(Blocks.PACKED_MUD, new BlockPos(x, 3, 7));
			}
			h.assertValueEqual(n, 15, "cells");
			h.assertValueEqual(MagicTestKit.count(h, Blocks.PACKED_MUD), 15, "exactly the wall");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 40)
	public void earthWallCobblestoneOnSlabsStoneOnStone(GameTestHelper h) {
		ServerPlayer p = wallCaster(h);
		for (int x = 14; x <= 16; x++) h.setBlock(new BlockPos(x, 3, 7), Blocks.STONE_SLAB);
		long cobble0 = MagicTestKit.count(h, Blocks.COBBLESTONE);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.EARTH_WALL, 0f), Result.CAST, "result");
		h.runAfterDelay(10, () -> {
			for (int y = 4; y <= 6; y++) {
				for (int x = 14; x <= 16; x++) {
					h.assertBlockPresent(Blocks.COBBLESTONE, cell(x, y));
					h.assertTrue(temp(h, cell(x, y), Blocks.COBBLESTONE.defaultBlockState()), "temporary cobblestone");
				}
				// The stone floor is a block the wall may be made of.
				for (int x = 17; x <= 18; x++) {
					h.assertBlockPresent(Blocks.STONE, cell(x, y));
					h.assertTrue(temp(h, cell(x, y), Blocks.STONE.defaultBlockState()), "temporary stone");
				}
			}
			h.assertValueEqual((long) MagicTestKit.count(h, Blocks.COBBLESTONE), cobble0 + 9, "cobblestone only over the slabs");
			h.assertBlockPresent(Blocks.STONE_SLAB, new BlockPos(15, 3, 7));
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 50)
	public void earthWallBlocksArrows(GameTestHelper h) {
		ServerPlayer p = wallCaster(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.EARTH_WALL, 0f), Result.CAST, "result");
		h.runAfterDelay(10, () -> {
			h.assertBlockPresent(Blocks.STONE, cell(16, 5));
			Vec3 v = new Vec3(0, 0.05, 1.5);
			Arrow blocked = MagicTestKit.arrow(h, new BlockPos(16, 5, 5), v);
			// Positive control: the same shot beside the wall flies on.
			Arrow free = MagicTestKit.arrow(h, new BlockPos(22, 5, 5), v);
			double wallZ = h.absoluteVec(new Vec3(0, 0, 7)).z;
			h.runAfterDelay(20, () -> {
				h.assertTrue(blocked.isRemoved() || blocked.getZ() <= wallZ + 0.05, "the arrow stopped at the wall, z " + (blocked.getZ() - wallZ));
				h.assertTrue(free.isRemoved() || free.getZ() > wallZ + 4, "the control arrow flew past, z " + (free.getZ() - wallZ));
				h.succeed();
			});
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 640)
	public void earthWallFallsAfterThirtySeconds(GameTestHelper h) {
		ServerPlayer p = wallCaster(h);
		long stone0 = MagicTestKit.count(h, Blocks.STONE);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.EARTH_WALL, 0f), Result.CAST, "result");
		h.runAtTickTime(590, () -> {
			for (int x = 14; x <= 18; x++) for (int y = 4; y <= 6; y++) h.assertBlockPresent(Blocks.STONE, cell(x, y));
		});
		h.runAtTickTime(601, () -> {
			for (int x = 14; x <= 18; x++) for (int y = 4; y <= 6; y++) h.assertBlockPresent(Blocks.AIR, cell(x, y));
			h.assertValueEqual((long) MagicTestKit.count(h, Blocks.STONE), stone0, "only what was there before");
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
			for (int y = 5; y <= 7; y++) h.assertBlockPresent(Blocks.COBBLESTONE, cell(16, y));
			h.assertBlockPresent(Blocks.STONE, cell(17, 4));
			h.assertBlockPresent(Blocks.AIR, cell(17, 6));
			// Positive control: the columns beside them are whole.
			for (int y = 4; y <= 6; y++) {
				h.assertBlockPresent(Blocks.STONE, cell(15, y));
				h.assertBlockPresent(Blocks.STONE, cell(18, y));
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
			for (int y = 4; y <= 6; y++) h.assertBlockPresent(Blocks.STONE, cell(17, y));
			h.assertTrue(z.getY() >= top - 0.01, "zombie on top of the wall, y " + (z.getY() - top));
			h.assertFalse(z.isInWall(), "zombie not inside the wall");
			h.assertValueEqual(z.getHealth(), 20f, "zombie unhurt");
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
				h.assertBlockPresent(Blocks.STONE, cell(14, y));
				h.assertBlockPresent(Blocks.STONE, cell(16, y));
				h.assertBlockPresent(Blocks.STONE, cell(18, y));
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
		long stone0 = MagicTestKit.count(h, Blocks.STONE);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.EARTH_WALL, 1f), Result.CAST, "result");
		float[] d = rec.fx(p).get(0).data();
		h.assertTrue(d.length == 4 && d[0] == 1 && d[1] == 0 && d[2] == 7 && d[3] == 4, "fx data {1, 0, 7, 4}");
		h.runAfterDelay(10, () -> {
			for (int x = 13; x <= 19; x++) {
				for (int y = 4; y <= 7; y++) h.assertBlockPresent(Blocks.STONE, cell(x, y));
				h.assertBlockPresent(Blocks.AIR, cell(x, 8));
			}
			h.assertValueEqual((long) MagicTestKit.count(h, Blocks.STONE), stone0 + 28, "28 cells");
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
			for (int x = 14; x <= 18; x++) h.assertBlockPresent(Blocks.STONE, new BlockPos(x, 4, 5));
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
			for (int y = 4; y <= 6; y++) h.assertBlockPresent(Blocks.STONE, cell(16, y));
			for (int x = 17; x <= 18; x++) {
				h.assertBlockPresent(ledge, cell(x, 4));
				for (int y = 5; y <= 7; y++) h.assertBlockPresent(ledge, cell(x, y));
				h.assertBlockPresent(Blocks.AIR, cell(x, 8));
			}
			h.succeed();
		});
	}
}
