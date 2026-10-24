package local.carter.office.magic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import local.carter.office.magic.cast.CastService.Result;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.Motion;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.SpellRegistry;
import local.carter.office.magic.spell.lightning.Blink;
import local.carter.office.magic.spell.lightning.ChainHops;
import local.carter.office.magic.spell.lightning.ChidoriGeometry;
import local.carter.office.magic.spell.lightning.ChidoriStrike;
import local.carter.office.magic.spell.lightning.ThunderSpear;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.cow.MushroomCow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/** Server GameTests for the Lightning lane: Spark and Chain Lightning (Task 18); Blink, Thunder Spear and Chidori Strike (Task 19). */
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

	/** A still bystander mob (no AI, persistent): a sheep or a snow golem, neither of them hostile. */
	private static Mob bystander(GameTestHelper h, net.minecraft.world.entity.EntityType<? extends Mob> type, BlockPos rel) {
		Mob m = h.spawn(type, rel);
		m.setNoAi(true);
		m.setPersistenceRequired();
		return m;
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
	public void sparkArcsOnlyToEnemies(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.LIGHTNING, 1);
		Zombie first = MagicTestKit.dummy(h, new BlockPos(16, 4, 10));
		// 1.4 away, a step behind the first so the zap itself still strikes the zombie.
		Mob sheep = bystander(h, EntityTypes.SHEEP, new BlockPos(17, 4, 11));
		Mob golem = bystander(h, EntityTypes.SNOW_GOLEM, new BlockPos(15, 4, 11));
		Zombie foe = MagicTestKit.dummy(h, new BlockPos(16, 4, 13)); // 3 away: the only hostile one
		MagicTestKit.aim(p, new BlockPos(16, 5, 10));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.SPARK, 0f), Result.CAST, "result");
		assertHealth(h, first, 20 - 3 * P1, "first");
		assertHealth(h, foe, 20 - 3 * P1 * 0.6F, "hostile arc target");
		assertUnhurt(h, sheep, "sheep");
		assertUnhurt(h, golem, "snow golem");
		assertIds(h, rec.fx(p).getFirst().entities(), ids(first, foe), "fx entities");
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

	@GameTest(structure = MagicTestKit.ARENA)
	public void chainNeverHopsToSheepOrSnowGolem(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.LIGHTNING, 2);
		Zombie a = MagicTestKit.dummy(h, new BlockPos(16, 4, 7));
		Mob sheep = bystander(h, EntityTypes.SHEEP, new BlockPos(16, 4, 9)); // 2 from a
		Mob golem = bystander(h, EntityTypes.SNOW_GOLEM, new BlockPos(18, 4, 7)); // 2 from a
		Zombie b = MagicTestKit.dummy(h, new BlockPos(16, 4, 12)); // 5 from a
		MagicTestKit.aim(p, new BlockPos(16, 5, 7));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.CHAIN_LIGHTNING, 1f), Result.CAST, "result");
		assertUnhurt(h, sheep, "sheep");
		assertUnhurt(h, golem, "snow golem");
		assertHealth(h, a, 20 - ChainHops.damage(0, 2 * P2), "first zombie");
		assertHealth(h, b, 20 - ChainHops.damage(1, 2 * P2), "zombie past the bystanders");
		assertIds(h, rec.fx(p).getFirst().entities(), ids(a, b), "fx chain order");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void chainStartsOnAnyFoeButHopsOnlyToEnemies(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.LIGHTNING, 2);
		// The look strikes a sheep first: that is allowed. The hop then skips the snow golem for the zombie.
		Mob sheep = bystander(h, EntityTypes.SHEEP, new BlockPos(16, 4, 7));
		Mob golem = bystander(h, EntityTypes.SNOW_GOLEM, new BlockPos(18, 4, 8)); // 2.2 from the sheep
		Zombie z = MagicTestKit.dummy(h, new BlockPos(13, 4, 8)); // 3.2 from the sheep
		MagicTestKit.aim(p, new BlockPos(16, 4, 7));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		float sheepHealth = sheep.getHealth();
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.CHAIN_LIGHTNING, 0f), Result.CAST, "result");
		h.assertTrue(sheep.getHealth() < sheepHealth, "the struck sheep is hurt " + sheep.getHealth());
		assertUnhurt(h, golem, "snow golem");
		assertHealth(h, z, 20 - ChainHops.damage(1, P2), "zombie");
		assertIds(h, rec.fx(p).getFirst().entities(), ids(sheep, z), "fx chain order");
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

	// ---- Task 19: Blink, Thunder Spear, Chidori Strike ----

	/** Power of an ultimate at grimoire level 7 (always charge 1): {@code 2 × (1 + 0.05 × 6)}. */
	private static final float P7_ULT = 2.6F;
	/** Power of a tap at grimoire level 5: {@code 1 × (1 + 0.05 × 4)}. */
	private static final float P5 = 1.2F;

	@GameTest(structure = MagicTestKit.ARENA)
	public void lightningM3SpellsRegisteredInTheirSlots(GameTestHelper h) {
		h.assertTrue(SpellRegistry.get(SpellIds.BLINK).orElse(null) instanceof Blink, "Blink registered");
		h.assertTrue(SpellRegistry.get(SpellIds.THUNDER_SPEAR).orElse(null) instanceof ThunderSpear, "Thunder Spear registered");
		h.assertTrue(SpellRegistry.get(SpellIds.CHIDORI_STRIKE).orElse(null) instanceof ChidoriStrike, "Chidori Strike registered");
		h.assertValueEqual(SpellRegistry.at(Element.LIGHTNING, 2).map(Spell::id).orElse(null), SpellIds.BLINK, "lightning slot 2");
		h.assertValueEqual(SpellRegistry.at(Element.LIGHTNING, 3).map(Spell::id).orElse(null), SpellIds.THUNDER_SPEAR, "lightning slot 3");
		h.assertValueEqual(SpellRegistry.at(Element.LIGHTNING, 4).map(Spell::id).orElse(null), SpellIds.CHIDORI_STRIKE, "lightning slot 4");
		h.succeed();
	}

	/** Glass round the arena's edge, 4..20 high, so an ultimate's debris lands in this test's arena and nowhere else. */
	private static void fence(GameTestHelper h) {
		int mx = MagicTestKit.SIZE_X - 1;
		int mz = MagicTestKit.SIZE_Z - 1;
		MagicTestKit.fill(h, new BlockPos(0, 4, 0), new BlockPos(mx, 20, 0), Blocks.GLASS);
		MagicTestKit.fill(h, new BlockPos(0, 4, mz), new BlockPos(mx, 20, mz), Blocks.GLASS);
		MagicTestKit.fill(h, new BlockPos(0, 4, 0), new BlockPos(0, 20, mz), Blocks.GLASS);
		MagicTestKit.fill(h, new BlockPos(mx, 4, 0), new BlockPos(mx, 20, mz), Blocks.GLASS);
	}

	/**
	 * Watches every lightning bolt in this test's arena: their ids go into the returned set, and any bolt that hit an entity or set a
	 * block on fire is reported in {@code problems}.
	 */
	private static Set<Integer> watchBolts(GameTestHelper h, List<String> problems) {
		Set<Integer> seen = new HashSet<>();
		h.onEachTick(() -> {
			for (LightningBolt b : h.getLevel().getEntitiesOfClass(LightningBolt.class, h.getBounds())) {
				seen.add(b.getId());
				if (b.getHitEntities().findAny().isPresent()) problems.add("bolt " + b.getId() + " hit an entity");
				if (b.getBlocksSetOnFire() > 0) problems.add("bolt " + b.getId() + " set fire");
			}
		});
		return seen;
	}

	/**
	 * One living tick of a kit player ({@code ServerPlayer.doTick}: suffocation, fire, drowning, physics). A real server runs it
	 * from the player's connection; the kit's players have no client ticking theirs, so a test that checks such damage runs it.
	 */
	private static void live(ServerPlayer p) {
		p.doTick();
	}

	private static boolean fits(ServerPlayer p) {
		return p.level().noCollision(p, p.getBoundingBox()) && !p.isInWall();
	}

	private static float slowness(LivingEntity e) {
		MobEffectInstance i = e.getEffect(MobEffects.SLOWNESS);
		return i == null ? -1 : i.getAmplifier();
	}

	/** {@code e} moved to the test-relative point {@code rel}. */
	private static <T extends Entity> T at(GameTestHelper h, T e, Vec3 rel) {
		e.snapTo(h.absoluteVec(rel));
		return e;
	}

	private static void maxHealth(LivingEntity e, float hp) {
		e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(hp);
		e.setHealth(hp);
	}

	/**
	 * Tick the terrain tests below cast at. Their casts queue hundreds of block changes, and the applier is one FIFO for the whole
	 * server (at most 256 a tick): casting a little after the batch starts keeps that load off other tests' first ticks (Earth
	 * Wall's timing test needs its own change applied the tick it casts).
	 */
	private static final long HEAVY_START = 25;

	// ---- Blink ----

	@GameTest(structure = MagicTestKit.ARENA)
	public void blinkGoesItsRangeInTheOpen(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.LIGHTNING, 3);
		ServerPlayer q = MagicTestKit.caster(h, Element.LIGHTNING, 3);
		MagicTestKit.teleportTo(q, MagicTestKit.STAND.offset(-6, 0, 0));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		Vec3 start = p.position();
		Vec3 startQ = q.position();
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.BLINK, 0f), Result.CAST, "tap");
		h.assertValueEqual(MagicTestKit.cast(q, SpellIds.BLINK, 1f), Result.CAST, "full charge");
		Vec3 moved = p.position().subtract(start);
		h.assertTrue(Math.abs(moved.z - 10) < 1.0E-6 && Math.abs(moved.x) < 1.0E-6 && Math.abs(moved.y) < 1.0E-6, "a tap blinks 10 straight ahead: " + moved);
		h.assertTrue(Math.abs(q.getZ() - startQ.z - 14) < 1.0E-6, "full charge blinks 14: " + q.position().subtract(startQ));
		h.assertTrue(p.getDeltaMovement().lengthSqr() < 1.0E-12, "velocity zeroed: " + p.getDeltaMovement());
		h.assertTrue(Motion.noFall(p), "no fall damage window open");
		ServerLevel level = h.getLevel();
		p.hurtServer(level, level.damageSources().fall(), 6);
		h.assertValueEqual(p.getHealth(), p.getMaxHealth(), "fall damage cancelled");
		FxEvent e = rec.fx(p).getFirst();
		h.assertTrue(e.target().distanceTo(p.position()) < 1.0E-6, "fx target is where the caster landed");
		h.assertTrue(e.origin().subtract(0, e.data()[0], 0).distanceTo(start) < 1.0E-4, "fx origin less the eye height is the start");
		h.assertTrue(Math.abs(e.data()[1] - 10) < 1.0E-4, "travelled " + e.data()[1]);
		h.assertTrue(fits(p) && fits(q), "both fit where they landed");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void blinkStopsBeforeWall(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.LIGHTNING, 3);
		MagicTestKit.fill(h, MagicTestKit.STAND.offset(-2, 0, 6), MagicTestKit.STAND.offset(2, 3, 6), Blocks.STONE);
		Vec3 start = p.position();
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.BLINK, 0f), Result.CAST, "result");
		double ahead = p.getZ() - start.z;
		h.assertTrue(ahead <= 5.5, "ends before the wall 6 ahead: " + ahead);
		h.assertTrue(ahead >= 4.5, "went right up to it: " + ahead);
		h.assertTrue(fits(p), "not inside the wall");
		float[] least = {p.getHealth()};
		h.onEachTick(() -> {
			live(p);
			least[0] = Math.min(least[0], p.getHealth());
		});
		h.runAfterDelay(20, () -> {
			h.assertValueEqual(least[0], p.getMaxHealth(), "never hurt");
			h.assertTrue(fits(p), "still not inside the wall");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void blinkIntoTunnelIsSafe(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.LIGHTNING, 3);
		// A 2-high, 1-wide tunnel from 3 ahead to 16 ahead.
		BlockPos s = MagicTestKit.STAND;
		MagicTestKit.fill(h, s.offset(-1, 0, 3), s.offset(-1, 1, 16), Blocks.STONE);
		MagicTestKit.fill(h, s.offset(1, 0, 3), s.offset(1, 1, 16), Blocks.STONE);
		MagicTestKit.fill(h, s.offset(-1, 2, 3), s.offset(1, 2, 16), Blocks.STONE);
		// Positive control: a player stuck in the tunnel's wall does suffocate.
		ServerPlayer stuck = MagicTestKit.otherPlayer(h, s.offset(-1, 0, 8));
		Vec3 start = p.position();
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.BLINK, 0f), Result.CAST, "result");
		h.assertTrue(p.getZ() - start.z > 9.9, "blinked deep into the tunnel: " + (p.getZ() - start.z));
		h.assertTrue(fits(p), "fits in the tunnel");
		float[] least = {p.getHealth()};
		h.onEachTick(() -> {
			live(p);
			live(stuck);
			least[0] = Math.min(least[0], p.getHealth());
		});
		h.runAfterDelay(20, () -> {
			h.assertValueEqual(least[0], p.getMaxHealth(), "no suffocation damage over 20 ticks");
			h.assertTrue(fits(p), "still fits");
			h.assertTrue(stuck.getHealth() < stuck.getMaxHealth(), "the control player in the wall suffocates, health " + stuck.getHealth());
			h.succeed();
		});
	}

	// ---- Thunder Spear ----

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 110)
	public void thunderSpearStartsNoFire(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.LIGHTNING, 5);
		// The caster stands in the middle and hurls the spear straight down: it lands at their feet, (16.5, 4, 16.5).
		MagicTestKit.teleportTo(p, new BlockPos(16, 4, 16));
		MagicTestKit.aim(p, new BlockPos(16, 3, 16));
		// Just outside the spear's 2.5 blast, inside the vanilla bolt's reach (3 round it): a bolt that did anything would show here.
		Creeper creeper = h.spawn(EntityTypes.CREEPER, new Vec3(19.6, 4, 16.5));
		Villager villager = at(h, MagicTestKit.villager(h, new BlockPos(13, 4, 16)), new Vec3(13.4, 4, 16.5));
		Pig pig = h.spawn(EntityTypes.PIG, new Vec3(16.5, 4, 19.8));
		MushroomCow cow = h.spawn(EntityTypes.MOOSHROOM, new Vec3(16.5, 4, 13.2));
		for (Mob m : List.<Mob>of(creeper, pig, cow)) {
			m.setNoAi(true);
			m.setPersistenceRequired();
		}
		MushroomCow.Variant variant = cow.getVariant();
		// Positive control for the blast: a zombie 1.5 from the impact takes exactly the spear's 10 × P, nothing from the bolt.
		Zombie z = at(h, MagicTestKit.dummy(h, new BlockPos(18, 4, 16)), new Vec3(18, 4, 16.5));
		List<String> problems = new ArrayList<>();
		Set<Integer> bolts = watchBolts(h, problems);
		long t0 = HEAVY_START;
		h.runAtTickTime(t0, () -> {
			h.assertValueEqual(MagicTestKit.cast(p, SpellIds.THUNDER_SPEAR, 0f), Result.CAST, "result");
		});
		h.runAtTickTime(t0 + 60, () -> {
			h.assertValueEqual(MagicTestKit.count(h, Blocks.FIRE), 0, "fire blocks");
			h.assertValueEqual(MagicTestKit.count(h, Blocks.SOUL_FIRE), 0, "soul fire blocks");
			h.assertTrue(problems.isEmpty(), "bolts: " + problems);
			h.assertTrue(!bolts.isEmpty(), "a visual bolt struck");
			h.assertTrue(creeper.isAlive() && !creeper.isPowered(), "creeper not charged");
			assertUnhurt(h, creeper, "creeper");
			h.assertTrue(villager.isAlive() && !villager.isRemoved(), "villager not turned into a witch");
			h.assertTrue(pig.isAlive() && !pig.isRemoved(), "pig not turned into a zombified piglin");
			assertUnhurt(h, pig, "pig");
			h.assertTrue(cow.isAlive() && cow.getVariant() == variant, "mooshroom unchanged: " + cow.getVariant());
			h.assertTrue(h.getLevel().getEntitiesOfClass(Witch.class, h.getBounds()).isEmpty(), "no witch");
			h.assertTrue(h.getLevel().getEntitiesOfClass(ZombifiedPiglin.class, h.getBounds()).isEmpty(), "no zombified piglin");
			assertHealth(h, z, 20 - ThunderSpear.DAMAGE * P5, "zombie in the blast");
			h.assertFalse(z.isOnFire(), "the zombie does not burn");
			h.assertTrue(h.getBlockState(new BlockPos(16, 3, 16)).isAir(), "the spear cratered the floor (it really landed there)");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void aRealBoltWouldChargeTheCreeperAndTurnTheMooshroom(GameTestHelper h) {
		// Positive control for thunderSpearStartsNoFire: the same layout under a bolt that is not visual-only does change them.
		Creeper creeper = h.spawn(EntityTypes.CREEPER, new Vec3(19.6, 4, 16.5));
		MushroomCow cow = h.spawn(EntityTypes.MOOSHROOM, new Vec3(16.5, 4, 13.2));
		creeper.setNoAi(true);
		cow.setNoAi(true);
		MushroomCow.Variant variant = cow.getVariant();
		LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(h.getLevel(), EntitySpawnReason.TRIGGERED);
		bolt.snapTo(h.absoluteVec(new Vec3(16.5, 4.02, 16.5)));
		h.getLevel().addFreshEntity(bolt);
		h.runAfterDelay(6, () -> {
			h.assertTrue(creeper.isPowered(), "a real bolt charges the creeper");
			h.assertTrue(cow.getVariant() != variant, "a real bolt turns the mooshroom");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 90)
	public void thunderSpearHitsSlowsAndGlassesSand(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.LIGHTNING, 5);
		MagicTestKit.fill(h, new BlockPos(13, 3, 9), new BlockPos(19, 3, 15), Blocks.SAND);
		Zombie z = MagicTestKit.dummy(h, new BlockPos(16, 4, 12));
		// A non-hostile mob in the blast is hurt but not slowed (control effects are for foes only).
		Mob sheep = at(h, bystander(h, EntityTypes.SHEEP, new BlockPos(14, 4, 12)), new Vec3(14.5, 4, 12.5));
		maxHealth(sheep, 40);
		Zombie far = MagicTestKit.dummy(h, new BlockPos(16, 4, 18));
		MagicTestKit.aim(p, new BlockPos(16, 5, 12));
		List<String> problems = new ArrayList<>();
		Set<Integer> bolts = watchBolts(h, problems);
		long t0 = HEAVY_START;
		h.runAtTickTime(t0, () -> {
			h.assertValueEqual(MagicTestKit.cast(p, SpellIds.THUNDER_SPEAR, 0f), Result.CAST, "result");
		});
		h.runAtTickTime(t0 + 6, () -> {
			assertHealth(h, z, 20 - ThunderSpear.DAMAGE * P5, "struck zombie");
			h.assertValueEqual(slowness(z), 1F, "struck zombie has Slowness II");
			assertHealth(h, sheep, 40 - ThunderSpear.DAMAGE * P5, "sheep in the blast");
			h.assertFalse(sheep.hasEffect(MobEffects.SLOWNESS), "the sheep is not slowed");
			assertUnhurt(h, far, "zombie 6 away");
		});
		h.runAtTickTime(t0 + 20, () -> {
			h.assertTrue(h.getBlockState(new BlockPos(16, 3, 12)).isAir(), "the crater under the zombie");
			h.assertTrue(h.getBlockState(new BlockPos(16, 3, 9)).is(Blocks.GLASS), "sand 3 from the crater fused to glass");
			h.assertTrue(h.getBlockState(new BlockPos(19, 3, 15)).is(Blocks.SAND), "sand beyond 3 is still sand");
			h.assertTrue(h.getBlockState(new BlockPos(16, 2, 9)).is(Blocks.STONE), "stone stays stone");
			h.assertValueEqual(MagicTestKit.count(h, Blocks.FIRE), 0, "no fire");
			h.assertTrue(problems.isEmpty(), "bolts: " + problems);
			h.assertTrue(!bolts.isEmpty(), "a visual bolt struck");
			h.succeed();
		});
	}

	// ---- Chidori Strike ----

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 110)
	public void chidoriTrenchesPathAndHits(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.LIGHTNING, 7);
		fence(h);
		BlockPos s = MagicTestKit.STAND;
		Zombie z = MagicTestKit.dummy(h, s.offset(0, 0, 12));
		// Positive control for the reach: one well off to the side is untouched.
		Zombie aside = MagicTestKit.dummy(h, s.offset(6, 0, 12));
		MagicTestKit.aim(p, s.offset(0, 1, 12));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		List<String> problems = new ArrayList<>();
		Set<Integer> bolts = watchBolts(h, problems);
		boolean[] inside = {false};
		h.onEachTick(() -> inside[0] |= !fits(p));
		Vec3 start = p.position();
		Vec3 end = h.absoluteVec(new Vec3(16.5, 4, s.getZ() + 12.5 + ChidoriGeometry.PASS));
		long t0 = HEAVY_START;
		h.runAtTickTime(t0, () -> {
			h.assertValueEqual(MagicTestKit.castUltimate(p, SpellIds.CHIDORI_STRIKE), Result.CAST, "result");
			FxEvent e = rec.fx(p).getFirst();
			h.assertValueEqual(e.duration(), ChidoriGeometry.TICKS, "duration");
			h.assertValueEqual(e.data()[1], 4F, "bowl radius at level 7");
			h.assertTrue(e.target().distanceTo(end) < 1.0E-6, "charges through the zombie to " + h.relativeVec(e.target()));
			assertIds(h, e.entities(), ids(z), "fx target");
		});
		h.runAtTickTime(t0 + 3, () -> {
			double done = p.getZ() - start.z;
			h.assertTrue(done > 2 && done < 13, "moving along the path at tick 3: " + done);
		});
		h.runAtTickTime(t0 + 9, () -> {
			h.assertTrue(z.getHealth() <= 0 || !z.isAlive(), "the zombie 12 ahead loses at least 20 health: " + z.getHealth());
			assertUnhurt(h, aside, "zombie 6 to the side");
			h.assertTrue(Math.abs(p.getZ() - end.z) < 0.6 && Math.abs(p.getX() - end.x) < 0.6, "the caster ended at the end: " + h.relativeVec(p.position()));
			h.assertTrue(bolts.size() >= 3, "three visual bolts, saw " + bolts.size());
		});
		h.runAtTickTime(t0 + 14, () -> {
			h.assertFalse(inside[0], "never inside a block");
			for (int zz = s.getZ() + 2; zz <= s.getZ() + 8; zz++) {
				for (int x = 15; x <= 17; x++) {
					for (int y = 2; y <= 3; y++) h.assertTrue(h.getBlockState(new BlockPos(x, y, zz)).isAir(), "trench cell " + x + "," + y + "," + zz);
				}
				h.assertFalse(h.getBlockState(new BlockPos(16, 1, zz)).isAir(), "the trench is 2 deep, floor at y 1, z " + zz);
				h.assertFalse(h.getBlockState(new BlockPos(14, 3, zz)).isAir(), "the lip is scorched, not cut, z " + zz);
				h.assertTrue(h.getBlockState(new BlockPos(13, 3, zz)).is(Blocks.STONE), "beyond the lip untouched, z " + zz);
			}
			h.assertTrue(h.getBlockState(new BlockPos(16, 3, s.getZ() + 14)).isAir(), "the bowl at the end");
			h.assertTrue(MagicTestKit.count(h, Blocks.BLACKSTONE) > 0, "scorched edges");
		});
		h.runAtTickTime(t0 + 60, () -> {
			h.assertValueEqual(MagicTestKit.count(h, Blocks.FIRE), 0, "no fire");
			h.assertTrue(problems.isEmpty(), "bolts: " + problems);
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 90)
	public void chidoriStopsAtTheFirstSolidBlock(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.LIGHTNING, 7);
		fence(h);
		BlockPos s = MagicTestKit.STAND;
		// A wall 6 ahead with a one-block slit at eye level: the look (and the aim) goes through it, the body can't.
		MagicTestKit.fill(h, s.offset(-3, 0, 6), s.offset(3, 0, 6), Blocks.STONE);
		MagicTestKit.fill(h, s.offset(-3, 2, 6), s.offset(3, 4, 6), Blocks.STONE);
		Zombie z = MagicTestKit.dummy(h, s.offset(0, 0, 12));
		MagicTestKit.aim(p, s.offset(0, 1, 12));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		boolean[] inside = {false};
		h.onEachTick(() -> inside[0] |= !fits(p));
		Vec3 start = p.position();
		long t0 = HEAVY_START;
		h.runAtTickTime(t0, () -> {
			h.assertValueEqual(MagicTestKit.castUltimate(p, SpellIds.CHIDORI_STRIKE), Result.CAST, "result");
			assertIds(h, rec.fx(p).getFirst().entities(), ids(z), "aimed at the zombie through the slit");
		});
		h.runAtTickTime(t0 + 12, () -> {
			double ahead = p.getZ() - start.z;
			h.assertTrue(ahead <= 5.5 && ahead >= 4.5, "stopped at the wall: " + ahead);
			h.assertFalse(inside[0], "never inside a block");
			assertUnhurt(h, z, "zombie behind the wall");
			h.assertTrue(h.getBlockState(new BlockPos(16, 3, s.getZ() + 3)).isAir(), "the trench up to the wall");
			h.assertTrue(h.getBlockState(new BlockPos(16, 3, s.getZ() + 12)).is(Blocks.STONE), "no trench beyond the wall");
			h.assertTrue(h.getBlockState(new BlockPos(16, 2, s.getZ() + 12)).is(Blocks.STONE), "no trench beyond the wall (deep)");
			h.assertTrue(h.getBlockState(s.offset(0, 0, 6)).is(Blocks.STONE) && h.getBlockState(s.offset(0, 2, 6)).is(Blocks.STONE), "the wall stands");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = "office-test:magic_terrain_emitter", maxTicks = 60)
	public void chidoriRespectsClaims(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.LIGHTNING, 7);
		// An emitter: the fallback claim rule protects everything within 32 of it, the whole arena.
		MagicTestKit.emitter(h, new BlockPos(4, 4, 0), p);
		Zombie z = MagicTestKit.dummy(h, MagicTestKit.STAND.offset(0, 0, 10));
		MagicTestKit.aim(p, MagicTestKit.STAND.offset(0, 1, 10));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		long before = MagicTestKit.blockHash(h);
		long t0 = h.getTick();
		h.assertValueEqual(MagicTestKit.castUltimate(p, SpellIds.CHIDORI_STRIKE), Result.CAST, "result");
		h.runAtTickTime(t0 + 20, () -> {
			h.assertValueEqual(MagicTestKit.blockHash(h), before, "no block changed");
			h.assertTrue(!z.isAlive() || z.getHealth() <= 0, "the strike still lands: " + z.getHealth());
			h.assertTrue(rec.messageKeys(p).contains("magic.office.protected"), "told the land is protected: " + rec.messageKeys(p));
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 90)
	public void chidoriBlastSparesBystandersAndSlowsOnlyFoes(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.LIGHTNING, 7);
		fence(h);
		Zombie target = MagicTestKit.dummy(h, new BlockPos(16, 4, 14));
		maxHealth(target, 100);
		// The charge ends at (16.5, 4, 16): round it a villager, a sheep and a second zombie, all within 3.
		Villager villager = at(h, MagicTestKit.villager(h, new BlockPos(18, 4, 16)), new Vec3(18, 4, 16));
		Mob sheep = at(h, bystander(h, EntityTypes.SHEEP, new BlockPos(14, 4, 16)), new Vec3(14.8, 4, 16));
		maxHealth(sheep, 40);
		Zombie second = at(h, MagicTestKit.dummy(h, new BlockPos(16, 4, 17)), new Vec3(16.5, 4, 17.8));
		maxHealth(second, 100);
		MagicTestKit.aim(p, new BlockPos(16, 5, 14));
		long t0 = HEAVY_START;
		h.runAtTickTime(t0, () -> {
			h.assertValueEqual(MagicTestKit.castUltimate(p, SpellIds.CHIDORI_STRIKE), Result.CAST, "result");
		});
		h.runAtTickTime(t0 + 9, () -> {
			assertHealth(h, target, 100 - ChidoriStrike.DAMAGE * P7_ULT, "target (24 × P, nothing from the bolt on it)");
			h.assertValueEqual(slowness(target), 4F, "target Slowness V");
			h.assertTrue(target.hasEffect(MobEffects.WEAKNESS), "target weakened");
			assertHealth(h, second, 100 - ChidoriStrike.BLAST_DAMAGE * P7_ULT, "second zombie (8 × P)");
			h.assertValueEqual(slowness(second), 4F, "second zombie Slowness V");
			assertHealth(h, sheep, 40 - ChidoriStrike.BLAST_DAMAGE * P7_ULT, "sheep (8 × P)");
			h.assertFalse(sheep.hasEffect(MobEffects.SLOWNESS) || sheep.hasEffect(MobEffects.WEAKNESS), "the sheep is not slowed or weakened");
			assertUnhurt(h, villager, "villager");
			h.assertFalse(villager.hasEffect(MobEffects.SLOWNESS), "the villager is not slowed");
			h.succeed();
		});
	}
}
