package local.carter.office.magic;

import local.carter.office.magic.cast.CastService.Result;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.water.TidalWaveGeometry;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/** Server GameTests for the Water lane: Water Bullet and Tidal Push (Task 14). */
public class WaterGameTests {
	/**
	 * The kit's dummy is NoAI, and a NoAI mob never moves (no gravity, no knockback). Knock tests need a body that physics moves:
	 * AI back on with every goal removed, so it stands still on its own but flies when pushed.
	 */
	private static Zombie mobile(Zombie z) {
		z.setNoAi(false);
		z.removeAllGoals(g -> true);
		z.setCanPickUpLoot(false);
		return z;
	}

	// ---- Water Bullet ----

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 30)
	public void waterBulletKnocksBackZombie(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WATER, 1);
		Zombie z = mobile(MagicTestKit.dummy(h, new BlockPos(16, 4, 9)));
		MagicTestKit.aim(p, new BlockPos(16, 5, 9));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		double z0 = z.getZ();
		long start = h.getTick();
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.WATER_BULLET, 0f), Result.CAST, "result");
		h.assertValueEqual(rec.fx(p).size(), 1, "one fx");
		h.succeedWhen(() -> {
			h.assertTrue(h.getTick() - start <= 10, "took " + (h.getTick() - start) + " ticks");
			h.assertTrue(z.getHealth() <= 17, "health " + z.getHealth());
			h.assertTrue(z.getZ() - z0 >= 1, "moved " + (z.getZ() - z0) + " +Z");
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 30)
	public void waterBulletExtinguishesTarget(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WATER, 1);
		Zombie z = MagicTestKit.dummy(h, new BlockPos(16, 4, 10));
		z.setRemainingFireTicks(200);
		h.assertTrue(z.isOnFire(), "burning before");
		MagicTestKit.aim(p, new BlockPos(16, 5, 10));
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.WATER_BULLET, 1f), Result.CAST, "result");
		h.succeedWhen(() -> {
			h.assertTrue(z.getHealth() <= 17, "health " + z.getHealth());
			h.assertFalse(z.isOnFire(), "still burning");
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 30)
	public void waterBulletStopsAtWall(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WATER, 1);
		MagicTestKit.fill(h, new BlockPos(12, 4, 7), new BlockPos(20, 9, 7), Blocks.STONE);
		Zombie z = MagicTestKit.dummy(h, new BlockPos(16, 4, 10));
		MagicTestKit.aim(p, new BlockPos(16, 5, 10));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		long hash = MagicTestKit.blockHash(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.WATER_BULLET, 0f), Result.CAST, "result");
		FxEvent e = rec.fx(p).get(0);
		h.assertTrue(Math.abs(e.target().z - h.absoluteVec(new Vec3(0, 0, 7)).z) < 0.01, "fx stops at the wall: " + e.target());
		h.runAfterDelay(20, () -> {
			h.assertValueEqual(z.getHealth(), 20f, "zombie behind the wall");
			h.assertValueEqual(MagicTestKit.blockHash(h), hash, "a small spell changes no block");
			h.succeed();
		});
	}

	// ---- Tidal Push ----

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void tidalPushPushesItemsAndMobs(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WATER, 2);
		Zombie z = mobile(MagicTestKit.dummy(h, new BlockPos(16, 4, 8)));
		ItemEntity item = MagicTestKit.item(h, new BlockPos(17, 4, 8));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		double z0 = z.getZ();
		double i0 = item.getZ();
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.TIDAL_PUSH, 0f), Result.CAST, "result");
		h.assertValueEqual(rec.fx(p).size(), 1, "one fx");
		FxEvent e = rec.fx(p).get(0);
		h.assertValueEqual(e.duration(), TidalWaveGeometry.DURATION, "fx duration");
		h.assertValueEqual(e.data()[0], 8f, "range at charge 0");
		h.assertTrue(e.entities().length >= 2, "both shoved ids in the fx");
		h.succeedWhen(() -> {
			h.assertTrue(z.getZ() - z0 >= 2, "zombie moved " + (z.getZ() - z0));
			h.assertTrue(item.getZ() - i0 >= 2, "item moved " + (item.getZ() - i0));
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void tidalPushSparesFriendsButDousesThem(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WATER, 2);
		ServerPlayer friend = MagicTestKit.otherPlayer(h, new BlockPos(16, 4, 8));
		friend.setRemainingFireTicks(200);
		Villager v = MagicTestKit.villager(h, new BlockPos(18, 4, 8));
		Vec3 v0 = v.position();
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.TIDAL_PUSH, 1f), Result.CAST, "result");
		h.assertFalse(friend.isOnFire(), "friend doused");
		h.assertTrue(friend.getDeltaMovement().horizontalDistance() < 0.01, "friend not pushed: " + friend.getDeltaMovement());
		h.assertTrue(v.getDeltaMovement().length() < 0.01, "villager not pushed: " + v.getDeltaMovement());
		h.runAfterDelay(20, () -> {
			h.assertTrue(v.position().distanceTo(v0) < 0.1, "villager not pushed: moved " + v.position().distanceTo(v0));
			h.assertTrue(friend.getDeltaMovement().horizontalDistance() < 0.01, "friend not carried by the wave");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void tidalWaveCarriesWhatTheConeMissed(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WATER, 2);
		// Beside the caster, a block ahead: far outside the 35° cone, but in the 5-wide wave.
		Zombie z = mobile(MagicTestKit.dummy(h, new BlockPos(18, 4, 5)));
		double z0 = z.getZ();
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.TIDAL_PUSH, 0f), Result.CAST, "result");
		h.assertTrue(z.getDeltaMovement().horizontalDistance() < 0.01, "the cone missed it");
		h.succeedWhen(() -> h.assertTrue(z.getZ() - z0 >= 0.5, "zombie carried " + (z.getZ() - z0)));
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 40)
	public void tidalPushExtinguishesFire(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WATER, 2);
		BlockPos fire = new BlockPos(16, 4, 14);
		BlockPos aside = new BlockPos(25, 4, 8); // 63° off the look: outside the cone
		for (BlockPos f : new BlockPos[] {fire, aside}) {
			h.setBlock(f.below(), Blocks.NETHERRACK);
			h.setBlock(f, Blocks.FIRE);
			h.assertBlockPresent(Blocks.FIRE, f);
		}
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.TIDAL_PUSH, 1f), Result.CAST, "result");
		h.succeedWhen(() -> {
			h.assertBlockPresent(Blocks.AIR, fire);
			h.assertBlockPresent(Blocks.FIRE, aside);
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 120)
	public void tidalWaveLeavesNoWater(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WATER, 2);
		// Positive control: the counter sees a water block.
		BlockPos control = new BlockPos(4, 4, 28);
		h.setBlock(control, Blocks.WATER);
		h.assertValueEqual(MagicTestKit.waterCount(h), 1, "the counter sees water");
		h.setBlock(control, Blocks.AIR);
		h.setBlock(new BlockPos(16, 3, 7), Blocks.NETHERRACK);
		h.setBlock(new BlockPos(16, 4, 7), Blocks.FIRE);
		// Beside the caster: 49° off the look (outside the cone) but in the wave's outer column.
		BlockPos aside = new BlockPos(18, 4, 6);
		h.setBlock(aside.below(), Blocks.NETHERRACK);
		h.setBlock(aside, Blocks.FIRE);
		h.assertValueEqual(MagicTestKit.waterCount(h), 0, "no water before");
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.TIDAL_PUSH, 1f), Result.CAST, "result");
		h.runAfterDelay(8, () -> h.assertValueEqual(MagicTestKit.waterCount(h), 0, "no water while the wave rolls"));
		h.runAtTickTime(100, () -> {
			h.assertValueEqual(MagicTestKit.waterCount(h), 0, "no water (flows included) at tick 100");
			h.assertBlockPresent(Blocks.AIR, new BlockPos(16, 4, 7));
			h.assertBlockPresent(Blocks.AIR, aside);
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 50)
	public void tidalWaveClimbsASlopeAndCarries(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WATER, 2);
		// A slope up: one block high from z 6, two from z 7 on (the wave climbs at most 2).
		MagicTestKit.fill(h, new BlockPos(12, 4, 6), new BlockPos(20, 4, 20), Blocks.STONE);
		MagicTestKit.fill(h, new BlockPos(12, 5, 7), new BlockPos(20, 5, 20), Blocks.STONE);
		Zombie z = mobile(MagicTestKit.dummy(h, new BlockPos(16, 6, 10)));
		// Look steeply up: the cone misses the zombie, only the wave (horizontal) can reach it.
		MagicTestKit.aim(p, new BlockPos(16, 20, 6));
		double z0 = z.getZ();
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.TIDAL_PUSH, 0f), Result.CAST, "result");
		h.assertTrue(z.getDeltaMovement().horizontalDistance() < 0.01, "the cone missed it");
		h.succeedWhen(() -> h.assertTrue(z.getZ() - z0 >= 0.5, "zombie on the slope carried " + (z.getZ() - z0)));
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 50)
	public void tidalWaveStopsAtWall(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WATER, 2);
		MagicTestKit.fill(h, new BlockPos(12, 4, 7), new BlockPos(20, 6, 7), Blocks.STONE);
		Zombie z = mobile(MagicTestKit.dummy(h, new BlockPos(16, 4, 9)));
		BlockPos fire = new BlockPos(14, 4, 9);
		h.setBlock(fire.below(), Blocks.NETHERRACK);
		h.setBlock(fire, Blocks.FIRE);
		Vec3 z0 = z.position();
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.TIDAL_PUSH, 1f), Result.CAST, "result");
		h.assertTrue(z.getDeltaMovement().horizontalDistance() < 0.01, "the cone can't push through the wall");
		h.runAfterDelay(30, () -> {
			h.assertTrue(z.position().distanceTo(z0) < 0.1, "zombie behind the wall moved " + z.position().distanceTo(z0));
			h.assertBlockPresent(Blocks.FIRE, fire);
			h.succeed();
		});
	}
}
