package local.carter.office.magic;

import java.util.List;

import local.carter.office.magic.cast.CastService.Result;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.SpellIds;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/** Server GameTests for the Wind lane: Gust and Blade Wind (Task 16). */
public class WindGameTests {
	/** The test's +Z as a world direction (the caster faces it). */
	private static Vec3 ahead(GameTestHelper h) {
		return h.absoluteVec(new Vec3(0, 0, 1)).subtract(h.absoluteVec(Vec3.ZERO));
	}

	// ---- Gust ----

	@GameTest(structure = MagicTestKit.ARENA)
	public void gustLaunchesZombie(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WIND, 1);
		Zombie z = MagicTestKit.dummy(h, new BlockPos(16, 4, 7));
		MagicTestKit.aim(p, new BlockPos(16, 5, 7));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.GUST, 0f), Result.CAST, "result");
		// NoAI dummies never simulate their own movement: the throw shows in the velocity.
		Vec3 v = z.getDeltaMovement();
		h.assertTrue(v.y >= 0.3, "upward velocity " + v);
		h.assertTrue(v.dot(ahead(h)) > 1.0, "thrown away along +Z " + v);
		h.assertTrue(z.syncVelocity, "velocity synced");
		h.assertTrue(z.getHealth() <= 19f, "zombie health " + z.getHealth());
		List<FxEvent> fx = rec.fx(p);
		h.assertValueEqual(fx.size(), 1, "one fx");
		h.assertTrue(fx.get(0).entities().length == 1 && fx.get(0).entities()[0] == z.getId(), "fx names the zombie");
		h.assertValueEqual(fx.get(0).data()[0], 6f, "range at charge 0");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void gustDoesNotPushPlayer(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WIND, 1);
		ServerPlayer other = MagicTestKit.otherPlayer(h, new BlockPos(16, 4, 7));
		Villager villager = MagicTestKit.villager(h, new BlockPos(18, 4, 8));
		Zombie control = MagicTestKit.dummy(h, new BlockPos(14, 4, 8));
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
		Zombie hidden = MagicTestKit.dummy(h, new BlockPos(16, 4, 8));
		Zombie open = MagicTestKit.dummy(h, new BlockPos(18, 4, 8));
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
		Zombie far = MagicTestKit.dummy(h, new BlockPos(16, 4, 12));
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
}
