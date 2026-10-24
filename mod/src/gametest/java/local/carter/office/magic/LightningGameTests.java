package local.carter.office.magic;

import java.util.List;

import local.carter.office.magic.cast.CastService.Result;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.lightning.ChainHops;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/** Server GameTests for the Lightning lane: Spark and Chain Lightning (Task 18). */
public class LightningGameTests {
	/** Power at grimoire level 1 and 2 for a tap ({@code (1 + charge) × (1 + 0.05 × (level − 1))}). */
	private static final float P1 = 1F;
	private static final float P2 = 1.05F;
	private static final float EPS = 0.01F;

	private static void assertHealth(GameTestHelper h, LivingEntity e, float expected, String what) {
		h.assertTrue(Math.abs(e.getHealth() - expected) < EPS, what + " health " + e.getHealth() + ", expected " + expected);
	}

	private static void assertUnhurt(GameTestHelper h, LivingEntity e, String what) {
		h.assertValueEqual(e.getHealth(), e.getMaxHealth(), what + " health");
	}

	private static int[] ids(LivingEntity... es) {
		int[] out = new int[es.length];
		for (int i = 0; i < es.length; i++) out[i] = es[i].getId();
		return out;
	}

	private static void assertIds(GameTestHelper h, int[] actual, int[] expected, String what) {
		h.assertTrue(java.util.Arrays.equals(actual, expected),
				what + ": " + java.util.Arrays.toString(actual) + ", expected " + java.util.Arrays.toString(expected));
	}

	// ---- Spark ----

	@GameTest(structure = MagicTestKit.ARENA)
	public void sparkHitsZombie(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.LIGHTNING, 1);
		// Sand under the target: a small spell never changes a block (Chain Lightning's glass test is the positive case).
		h.setBlock(new BlockPos(16, 3, 10), Blocks.SAND);
		Zombie z = MagicTestKit.dummy(h, new BlockPos(16, 4, 10));
		MagicTestKit.aim(p, new BlockPos(16, 5, 10));
		long hash = MagicTestKit.blockHash(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.SPARK, 0f), Result.CAST, "result");
		h.assertTrue(z.getHealth() <= 17f, "zombie health " + z.getHealth());
		assertHealth(h, z, 20 - 3 * P1, "zombie");
		List<FxEvent> fx = rec.fx(p);
		h.assertValueEqual(fx.size(), 1, "one fx");
		assertIds(h, fx.getFirst().entities(), ids(z), "fx entities");
		h.assertTrue(fx.getFirst().target().distanceTo(z.getBoundingBox().getCenter()) < 1.0E-6, "fx ends at the zombie " + fx.getFirst().target());
		h.runAfterDelay(5, () -> {
			h.assertValueEqual(MagicTestKit.blockHash(h), hash, "no block changed");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void sparkArcsToTheNearestOtherFoe(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.LIGHTNING, 1);
		Zombie first = MagicTestKit.dummy(h, new BlockPos(16, 4, 10));
		Zombie near = MagicTestKit.dummy(h, new BlockPos(18, 4, 10)); // 2 from the first
		Zombie next = MagicTestKit.dummy(h, new BlockPos(16, 4, 13)); // 3 from the first
		Zombie far = MagicTestKit.dummy(h, new BlockPos(21, 4, 10)); // 5 from the first: out of the arc's 4
		MagicTestKit.aim(p, new BlockPos(16, 5, 10));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.SPARK, 0f), Result.CAST, "result");
		assertHealth(h, first, 20 - 3 * P1, "first");
		assertHealth(h, near, 20 - 3 * P1 * 0.6F, "arc target (60 %)");
		assertUnhurt(h, next, "second-nearest");
		assertUnhurt(h, far, "far");
		assertIds(h, rec.fx(p).getFirst().entities(), ids(first, near), "fx entities");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void sparkArcSparesVillagersAndPlayersAndStopsAtWalls(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.LIGHTNING, 1);
		Zombie first = MagicTestKit.dummy(h, new BlockPos(16, 4, 10));
		Villager villager = MagicTestKit.villager(h, new BlockPos(17, 4, 10)); // 1 away
		ServerPlayer other = MagicTestKit.otherPlayer(h, new BlockPos(16, 4, 11)); // 1 away
		Wolf pet = MagicTestKit.tamedWolf(h, new BlockPos(15, 4, 11), p);
		float petHealth = pet.getHealth();
		// 2 away but behind a wall.
		MagicTestKit.fill(h, new BlockPos(15, 4, 9), new BlockPos(15, 6, 9), Blocks.STONE);
		MagicTestKit.fill(h, new BlockPos(14, 4, 9), new BlockPos(14, 6, 9), Blocks.STONE);
		MagicTestKit.fill(h, new BlockPos(15, 4, 10), new BlockPos(15, 6, 10), Blocks.STONE);
		Zombie hidden = MagicTestKit.dummy(h, new BlockPos(14, 4, 10));
		// 3 away in the open: the arc's only fair target.
		Zombie open = MagicTestKit.dummy(h, new BlockPos(19, 4, 10));
		MagicTestKit.aim(p, new BlockPos(16, 5, 10));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.SPARK, 0f), Result.CAST, "result");
		assertHealth(h, first, 20 - 3 * P1, "first");
		assertHealth(h, open, 20 - 3 * P1 * 0.6F, "zombie in the open");
		assertUnhurt(h, villager, "villager");
		assertUnhurt(h, other, "player");
		h.assertValueEqual(pet.getHealth(), petHealth, "tamed wolf health");
		assertUnhurt(h, hidden, "zombie behind the wall");
		assertIds(h, rec.fx(p).getFirst().entities(), ids(first, open), "fx entities");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void sparkStopsAtWall(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.LIGHTNING, 1);
		MagicTestKit.fill(h, new BlockPos(14, 4, 7), new BlockPos(18, 7, 7), Blocks.STONE);
		Zombie z = MagicTestKit.dummy(h, new BlockPos(16, 4, 10));
		MagicTestKit.aim(p, new BlockPos(16, 5, 10));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.SPARK, 0f), Result.CAST, "result");
		assertUnhurt(h, z, "zombie behind the wall");
		FxEvent fx = rec.fx(p).getFirst();
		h.assertValueEqual(fx.entities().length, 0, "nothing struck");
		h.assertTrue(fx.target().distanceTo(p.getEyePosition()) < 3.0, "the zap grounds on the wall " + fx.target());
		// Positive control: the same shot with the wall gone (Spark's cooldown is 10 ticks).
		h.runAfterDelay(12, () -> {
			MagicTestKit.fill(h, new BlockPos(14, 4, 7), new BlockPos(18, 7, 7), Blocks.AIR);
			h.assertValueEqual(MagicTestKit.cast(p, SpellIds.SPARK, 0f), Result.CAST, "second result");
			assertHealth(h, z, 20 - 3 * P1, "zombie in the open");
			h.succeed();
		});
	}

	// ---- Chain Lightning ----

	@GameTest(structure = MagicTestKit.ARENA)
	public void chainLightningHopsFiveZombies(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.LIGHTNING, 2);
		Zombie[] line = new Zombie[5];
		for (int i = 0; i < line.length; i++) line[i] = MagicTestKit.dummy(h, new BlockPos(16, 4, 7 + 3 * i));
		// A sixth in range of the fifth: a tap's 4 hops never reach it.
		Zombie sixth = MagicTestKit.dummy(h, new BlockPos(16, 4, 22));
		MagicTestKit.aim(p, new BlockPos(16, 5, 7));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.CHAIN_LIGHTNING, 0f), Result.CAST, "result");
		float lastDamage = Float.MAX_VALUE;
		for (int i = 0; i < line.length; i++) {
			float damage = 20 - line[i].getHealth();
			h.assertTrue(damage > 0, "zombie " + i + " hurt");
			h.assertTrue(damage <= lastDamage + 1.0E-4F, "damage non-increasing along the chain: " + damage + " after " + lastDamage);
			assertHealth(h, line[i], 20 - ChainHops.damage(i, P2), "zombie " + i);
			lastDamage = damage;
		}
		assertHealth(h, line[0], 20 - 6 * P2, "first (6 × P)");
		assertUnhurt(h, sixth, "sixth (past the hop limit)");
		FxEvent fx = rec.fx(p).getFirst();
		assertIds(h, fx.entities(), ids(line), "fx chain order");
		h.assertValueEqual(fx.data()[0], 5f, "fx count");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void chainLightningFullChargeHopsSix(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.LIGHTNING, 2);
		Zombie[] line = new Zombie[7];
		for (int i = 0; i < line.length; i++) line[i] = MagicTestKit.dummy(h, new BlockPos(16, 4, 7 + 3 * i));
		Zombie eighth = MagicTestKit.dummy(h, new BlockPos(16, 4, 28));
		MagicTestKit.aim(p, new BlockPos(16, 5, 7));
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.CHAIN_LIGHTNING, 1f), Result.CAST, "result");
		for (int i = 0; i < line.length; i++) assertHealth(h, line[i], 20 - ChainHops.damage(i, 2 * P2), "zombie " + i);
		assertUnhurt(h, eighth, "eighth (past 6 hops)");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void chainSkipsVillager(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.LIGHTNING, 2);
		Zombie a = MagicTestKit.dummy(h, new BlockPos(16, 4, 7));
		Zombie b = MagicTestKit.dummy(h, new BlockPos(16, 4, 10));
		// Nearer to b than the next zombie, and spared: the chain passes over them.
		Villager villager = MagicTestKit.villager(h, new BlockPos(16, 4, 12));
		ServerPlayer other = MagicTestKit.otherPlayer(h, new BlockPos(17, 4, 11));
		Wolf pet = MagicTestKit.tamedWolf(h, new BlockPos(15, 4, 12), p);
		float petHealth = pet.getHealth();
		Zombie c = MagicTestKit.dummy(h, new BlockPos(16, 4, 15)); // 5 from b
		MagicTestKit.aim(p, new BlockPos(16, 5, 7));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.CHAIN_LIGHTNING, 0f), Result.CAST, "result");
		assertUnhurt(h, villager, "villager");
		assertUnhurt(h, other, "player");
		h.assertValueEqual(pet.getHealth(), petHealth, "tamed wolf health");
		assertHealth(h, a, 20 - ChainHops.damage(0, P2), "first zombie");
		assertHealth(h, b, 20 - ChainHops.damage(1, P2), "second zombie");
		assertHealth(h, c, 20 - ChainHops.damage(2, P2), "zombie past the villager");
		assertIds(h, rec.fx(p).getFirst().entities(), ids(a, b, c), "fx chain order");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void chainStopsAtGapsAndWalls(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.LIGHTNING, 2);
		Zombie first = MagicTestKit.dummy(h, new BlockPos(16, 4, 7));
		Zombie open = MagicTestKit.dummy(h, new BlockPos(13, 4, 7)); // 3 away in the open
		MagicTestKit.fill(h, new BlockPos(18, 4, 6), new BlockPos(18, 7, 8), Blocks.STONE);
		Zombie walled = MagicTestKit.dummy(h, new BlockPos(19, 4, 7)); // 3 away behind a wall
		Zombie far = MagicTestKit.dummy(h, new BlockPos(16, 4, 14)); // 7 away from the first, 7.6 from the second
		MagicTestKit.aim(p, new BlockPos(16, 5, 7));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.CHAIN_LIGHTNING, 0f), Result.CAST, "result");
		assertHealth(h, first, 20 - ChainHops.damage(0, P2), "first");
		assertHealth(h, open, 20 - ChainHops.damage(1, P2), "zombie in the open");
		assertUnhurt(h, walled, "zombie behind the wall");
		assertUnhurt(h, far, "zombie out of hop range");
		FxEvent fx = rec.fx(p).getFirst();
		assertIds(h, fx.entities(), ids(first, open), "fx chain order");
		h.assertValueEqual(fx.data()[0], 2f, "fx count");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV)
	public void chainGlassesSandUnderTargets(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.LIGHTNING, 2);
		BlockPos[] under = {new BlockPos(16, 3, 7), new BlockPos(16, 3, 10), new BlockPos(16, 3, 13)};
		for (BlockPos b : under) h.setBlock(b, Blocks.SAND);
		h.setBlock(new BlockPos(17, 3, 10), Blocks.SAND); // beside a struck zombie
		h.setBlock(new BlockPos(16, 3, 20), Blocks.RED_SAND); // under a zombie the chain can't reach (7 from the last)
		h.setBlock(new BlockPos(18, 3, 13), Blocks.RED_SAND); // under a zombie standing on red sand
		Zombie[] struck = new Zombie[under.length];
		for (int i = 0; i < under.length; i++) struck[i] = MagicTestKit.dummy(h, under[i].above());
		Zombie onRed = MagicTestKit.dummy(h, new BlockPos(18, 4, 13)); // 2 from the third
		Zombie away = MagicTestKit.dummy(h, new BlockPos(16, 4, 20));
		MagicTestKit.aim(p, new BlockPos(16, 5, 7));
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.CHAIN_LIGHTNING, 0f), Result.CAST, "result");
		for (Zombie z : struck) h.assertTrue(z.getHealth() < 20, "struck zombie hurt");
		h.assertTrue(onRed.getHealth() < 20, "zombie on red sand hurt");
		assertUnhurt(h, away, "zombie out of reach");
		h.runAfterDelay(5, () -> {
			for (BlockPos b : under) h.assertBlockPresent(Blocks.GLASS, b);
			h.assertBlockPresent(Blocks.GLASS, new BlockPos(18, 3, 13));
			h.assertBlockPresent(Blocks.SAND, new BlockPos(17, 3, 10));
			h.assertBlockPresent(Blocks.RED_SAND, new BlockPos(16, 3, 20));
			h.assertValueEqual(MagicTestKit.count(h, Blocks.GLASS), 4, "glass blocks");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV)
	public void chainMissGroundsWithoutChangingBlocks(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.LIGHTNING, 2);
		// Aimed at a patch of bare sand on the floor: nothing to strike, so nothing turns to glass.
		MagicTestKit.fill(h, new BlockPos(15, 3, 7), new BlockPos(17, 3, 10), Blocks.SAND);
		MagicTestKit.aim(p, new BlockPos(16, 3, 9));
		long hash = MagicTestKit.blockHash(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.CHAIN_LIGHTNING, 0f), Result.CAST, "result");
		FxEvent fx = rec.fx(p).getFirst();
		h.assertValueEqual(fx.data()[0], 0f, "nothing struck");
		h.assertValueEqual(fx.entities().length, 0, "no fx entities");
		// The look from the eye to that block's centre meets the floor's top 3.8 ahead (z 8.3).
		h.assertTrue(fx.target().distanceTo(h.absoluteVec(new net.minecraft.world.phys.Vec3(16.5, 4, 8.5))) < 1.0,
				"grounds on the sand " + fx.target());
		h.runAfterDelay(5, () -> {
			h.assertValueEqual(MagicTestKit.blockHash(h), hash, "no block changed");
			h.succeed();
		});
	}
}
