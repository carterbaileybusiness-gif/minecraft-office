package local.carter.office.magic;

import java.util.Map;

import local.carter.office.magic.cast.CastService.Result;
import local.carter.office.magic.command.MagicCommand;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.Hurt;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.water.BubbleShieldGeometry;
import local.carter.office.magic.spell.water.DragonGeometry;
import local.carter.office.magic.spell.water.TidalWaveGeometry;
import local.carter.office.magic.spell.water.WaterPrisonGeometry;
import local.carter.office.magic.terrain.EditLog.UndoReport;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/** Server GameTests for the Water lane: Water Bullet and Tidal Push (Task 14); Bubble Shield, Water Prison and Water Dragon (Task 15). */
public class WaterGameTests {
	// ---- Water Bullet ----

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 30)
	public void waterBulletKnocksBackZombie(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WATER, 1);
		Zombie z = MagicTestKit.movableDummy(h, new BlockPos(16, 4, 9));
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
		Zombie z = MagicTestKit.movableDummy(h, new BlockPos(16, 4, 8));
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
		Zombie z = MagicTestKit.movableDummy(h, new BlockPos(18, 4, 5));
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
		Zombie z = MagicTestKit.movableDummy(h, new BlockPos(16, 6, 10));
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
		Zombie z = MagicTestKit.movableDummy(h, new BlockPos(16, 4, 9));
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

	// ---- Bubble Shield ----

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void bubbleShieldDeletesArrows(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WATER, 3);
		// Positive control: the same arrow at an unshielded player hits.
		ServerPlayer bare = MagicTestKit.otherPlayer(h, new BlockPos(8, 4, 4));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.BUBBLE_SHIELD, 0f), Result.CAST, "result");
		FxEvent e = rec.fx(p).get(0);
		h.assertValueEqual(e.duration(), BubbleShieldGeometry.duration(0), "fx duration");
		MobEffectInstance res = p.getEffect(MobEffects.RESISTANCE);
		h.assertTrue(res != null && res.getAmplifier() == 0 && res.getDuration() >= 118, "Resistance I for the bubble's life: " + res);
		Arrow atCaster = MagicTestKit.arrow(h, new BlockPos(16, 5, 12), new Vec3(0, 0, -1.5));
		Arrow atBare = MagicTestKit.arrow(h, new BlockPos(8, 5, 12), new Vec3(0, 0, -1.5));
		h.onEachTick(() -> {
			if (!atCaster.isRemoved()) {
				h.assertTrue(atCaster.position().distanceTo(p.position().add(0, 0.9, 0)) > 2.0, "the arrow never gets inside the bubble");
			}
		});
		h.runAfterDelay(25, () -> {
			h.assertTrue(atCaster.isRemoved(), "the arrow at the caster is gone");
			h.assertValueEqual(p.getHealth(), 20f, "caster health");
			h.assertTrue(atBare.isRemoved() || bare.getHealth() < 20, "control arrow landed");
			h.assertTrue(bare.getHealth() < 20, "control: the unshielded player was hit (" + bare.getHealth() + ")");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 30)
	public void bubbleShieldLetsOwnArrowsOut(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WATER, 3);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.BUBBLE_SHIELD, 1f), Result.CAST, "result");
		Arrow own = MagicTestKit.arrow(h, new BlockPos(16, 5, 5), new Vec3(0, 0.05, 1.2));
		own.setOwner(p);
		// Control: a stranger's arrow at the same spot is stopped.
		Arrow other = MagicTestKit.arrow(h, new BlockPos(17, 5, 5), new Vec3(0, 0.02, 0.3));
		h.runAfterDelay(3, () -> {
			h.assertFalse(own.isRemoved(), "the caster's own arrow flies on");
			h.assertTrue(own.position().z > h.absoluteVec(new Vec3(0, 0, 7.5)).z, "out through the bubble: " + own.position());
			h.assertTrue(other.isRemoved(), "control: a stranger's arrow inside the bubble is stopped");
			h.succeed();
		});
	}

	// ---- Water Prison ----

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 240)
	public void waterPrisonHoldsZombieThenDrains(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WATER, 5);
		Zombie z = MagicTestKit.movableDummy(h, new BlockPos(16, 4, 12));
		// Positive control: a free zombie the same shove moves.
		Zombie free = MagicTestKit.movableDummy(h, new BlockPos(24, 4, 12));
		MagicTestKit.aim(p, new BlockPos(16, 5, 12));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		// Positive control: the counter sees water.
		BlockPos control = new BlockPos(2, 4, 28);
		h.setBlock(control, Blocks.WATER);
		h.assertValueEqual(MagicTestKit.waterCount(h), 1, "the counter sees water");
		h.setBlock(control, Blocks.AIR);
		Vec3 start = z.position();
		Vec3 freeStart = free.position();
		int air0 = z.getAirSupply();
		long t0 = h.getTick();
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.WATER_PRISON, 0f), Result.CAST, "result");
		FxEvent e = rec.fx(p).get(0);
		h.assertValueEqual(e.duration(), WaterPrisonGeometry.duration(0), "fx duration");
		h.assertValueEqual(e.entities().length, 1, "the prisoner in the fx");
		h.assertValueEqual(e.entities()[0], z.getId(), "the prisoner is the zombie");
		h.assertTrue(e.target().distanceTo(z.getBoundingBox().getCenter()) < 0.01, "the sphere is about its middle");
		h.runAfterDelay(10, () -> {
			Vec3 shove = new Vec3(1, 0, 0);
			Hurt.knock(z, shove, 1.0, 0.3);
			Hurt.knock(free, shove, 1.0, 0.3);
		});
		h.onEachTick(() -> {
			if (h.getTick() - t0 <= 80) h.assertTrue(z.position().distanceTo(start) <= 1, "held within 1 of the start: " + z.position().distanceTo(start));
		});
		h.runAfterDelay(80, () -> {
			h.assertTrue(free.position().distanceTo(freeStart) >= 1, "control: the free zombie was shoved " + free.position().distanceTo(freeStart));
			MobEffectInstance slow = z.getEffect(MobEffects.SLOWNESS);
			h.assertTrue(slow != null && slow.getAmplifier() == 2, "Slowness III: " + slow);
			h.assertValueEqual(z.getAirSupply(), air0, "a zombie is undead: it breathes water, as in vanilla");
			h.assertValueEqual(z.getHealth(), 20f, "no drowning");
			h.assertValueEqual(MagicTestKit.waterCount(h), 0, "no water placed");
		});
		h.runAtTickTime(220, () -> {
			h.assertValueEqual(MagicTestKit.waterCount(h), 0, "no water at tick 220");
			h.assertTrue(z.getEffect(MobEffects.SLOWNESS) == null, "free again");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void waterPrisonTakesTheFoeNotTheCow(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WATER, 5);
		Cow cow = h.spawn(EntityTypes.COW, new BlockPos(16, 4, 8));
		cow.setNoAi(true);
		Zombie z = MagicTestKit.dummy(h, new BlockPos(16, 4, 14));
		MagicTestKit.aim(p, new BlockPos(16, 5, 14));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.WATER_PRISON, 0f), Result.CAST, "result");
		FxEvent e = rec.fx(p).get(0);
		h.assertValueEqual(e.entities().length, 1, "one prisoner");
		h.assertValueEqual(e.entities()[0], z.getId(), "the ray passed the cow for the zombie");
		h.runAfterDelay(5, () -> {
			h.assertTrue(z.getEffect(MobEffects.SLOWNESS) != null, "the zombie is held");
			h.assertTrue(cow.getEffect(MobEffects.SLOWNESS) == null, "the cow is not");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 60)
	public void waterPrisonDrownsOnlyAirBreathers(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WATER, 5);
		Drowned d = h.spawn(EntityTypes.DROWNED, new BlockPos(12, 4, 12));
		d.setNoAi(true);
		Creeper c = h.spawn(EntityTypes.CREEPER, new BlockPos(20, 4, 12));
		c.setNoAi(true);
		// Nearly out of air: it drowns once the air is used up (vanilla: at -20, 2 damage).
		c.setAirSupply(5);
		int dAir = d.getAirSupply();
		MagicTestKit.aim(p, new BlockPos(12, 5, 12));
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.WATER_PRISON, 0f), Result.CAST, "drowned prison");
		MagicAttachments.set(p, MagicAttachments.get(p).withReadyAt(Map.of()));
		MagicAttachments.setMana(p, 200);
		MagicTestKit.aim(p, new BlockPos(20, 5, 12));
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.WATER_PRISON, 0f), Result.CAST, "creeper prison");
		h.runAfterDelay(15, () -> {
			h.assertTrue(c.getAirSupply() < 0, "the creeper's air runs out (" + c.getAirSupply() + ")");
			h.assertValueEqual(c.getHealth(), c.getMaxHealth(), "no drowning before the air is used up");
		});
		h.runAfterDelay(40, () -> {
			h.assertTrue(d.getEffect(MobEffects.SLOWNESS) != null, "the drowned is held");
			h.assertValueEqual(d.getAirSupply(), dAir, "the drowned breathes water");
			h.assertValueEqual(d.getHealth(), d.getMaxHealth(), "and never drowns");
			h.assertTrue(c.getHealth() <= c.getMaxHealth() - WaterPrisonGeometry.DROWN_DAMAGE, "control: the creeper drowns (" + c.getHealth() + ")");
			h.succeed();
		});
	}

	// ---- Water Dragon ----

	/** Aimed here from the stand, the look meets the arena floor on the top face of (16, 3, 15). */
	private static final BlockPos DRAGON_AIM = new BlockPos(16, 3, 18);
	private static final BlockPos DRAGON_CELL = new BlockPos(16, 4, 15);

	private static ServerPlayer dragonCaster(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WATER, 7);
		MagicTestKit.aim(p, DRAGON_AIM);
		return p;
	}

	/** Water sources in the arena between layers {@code minY} and {@code maxY}. */
	private static int sources(GameTestHelper h, int minY, int maxY) {
		int n = 0;
		for (BlockPos q : BlockPos.betweenClosed(new BlockPos(0, minY, 0), new BlockPos(MagicTestKit.SIZE_X - 1, maxY, MagicTestKit.SIZE_Z - 1))) {
			if (h.getBlockState(q).getFluidState().isSourceOfType(Fluids.WATER)) n++;
		}
		return n;
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 220)
	public void waterDragonFloodsAndStays(GameTestHelper h) {
		ServerPlayer p = dragonCaster(h);
		Zombie z = MagicTestKit.dummy(h, DRAGON_CELL);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(sources(h, 0, MagicTestKit.SIZE_Y - 1), 0, "no water before");
		h.assertValueEqual(MagicTestKit.castUltimate(p, SpellIds.WATER_DRAGON), Result.CAST, "result");
		FxEvent e = rec.fx(p).get(0);
		h.assertValueEqual(e.duration(), DragonGeometry.FLIGHT, "fx duration");
		h.assertValueEqual(e.data()[0], 6f, "flood radius at level 7");
		h.assertValueEqual(e.data()[1], 9f, "mud radius at level 7");
		h.assertValueEqual(BlockPos.containing(e.target().x, e.data()[2] + 0.5, e.target().z), h.absolutePos(DRAGON_CELL), "impact cell");
		h.runAfterDelay(DragonGeometry.FLIGHT - 2, () -> {
			h.assertValueEqual(MagicTestKit.waterCount(h), 0, "nothing before the dragon lands");
			h.assertTrue(z.isAlive(), "the zombie is still alive before the impact");
		});
		h.runAtTickTime(200, () -> {
			h.assertFalse(z.isAlive(), "the zombie at the impact is dead");
			int n = sources(h, 0, DRAGON_CELL.getY());
			h.assertTrue(n >= 50, "water sources remain at tick 200: " + n);
			h.assertTrue(n <= DragonGeometry.MAX_SOURCES, "at most 600: " + n);
			int above = 0;
			for (BlockPos q : BlockPos.betweenClosed(new BlockPos(0, DRAGON_CELL.getY() + 1, 0), new BlockPos(31, 23, 31))) {
				if (!h.getBlockState(q).getFluidState().isEmpty()) above++;
			}
			h.assertValueEqual(above, 0, "no water at all above the impact");
			h.assertTrue(h.getBlockState(DRAGON_CELL.south(6)).getFluidState().isSourceOfType(Fluids.WATER), "a source 6 out");
			h.assertFalse(h.getBlockState(DRAGON_CELL.south(8)).getFluidState().isSourceOfType(Fluids.WATER), "no source 8 out");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 120)
	public void waterDragonNeverFloodsAChest(GameTestHelper h) {
		ServerPlayer p = dragonCaster(h);
		BlockPos chest = DRAGON_CELL.east();
		MagicTestKit.chest(h, chest);
		h.getBlockEntity(chest, ChestBlockEntity.class).setItem(0, new ItemStack(Items.DIAMOND, 3));
		BlockState before = h.getBlockState(chest);
		h.assertValueEqual(MagicTestKit.castUltimate(p, SpellIds.WATER_DRAGON), Result.CAST, "result");
		h.runAtTickTime(100, () -> {
			// The spell never edits it. (Vanilla's infinite-water rule may waterlog it, as two buckets beside it would.)
			BlockState now = h.getBlockState(chest);
			h.assertValueEqual(now.setValue(BlockStateProperties.WATERLOGGED, false), before, "the chest cell is the same chest");
			ChestBlockEntity box = h.getBlockEntity(chest, ChestBlockEntity.class);
			h.assertTrue(box != null && box.getItem(0).is(Items.DIAMOND) && box.getItem(0).getCount() == 3, "with its contents");
			int n = sources(h, 0, DRAGON_CELL.getY());
			h.assertTrue(n >= 50, "control: the flood landed around it (" + n + ")");
			h.assertTrue(h.getBlockState(chest.east()).getFluidState().isSourceOfType(Fluids.WATER), "control: water right beside it");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 60)
	public void waterDragonStrikesAllButThrowsOnlyFoes(GameTestHelper h) {
		ServerPlayer p = dragonCaster(h);
		Zombie z = MagicTestKit.movableDummy(h, DRAGON_CELL.east(3));
		tough(z);
		Cow cow = h.spawn(EntityTypes.COW, DRAGON_CELL.west(3));
		cow.removeAllGoals(g -> true);
		cow.setPersistenceRequired();
		tough(cow);
		Villager v = MagicTestKit.villager(h, DRAGON_CELL.south(3));
		Vec3 z0 = z.position();
		Vec3 c0 = cow.position();
		h.assertValueEqual(MagicTestKit.castUltimate(p, SpellIds.WATER_DRAGON), Result.CAST, "result");
		h.runAfterDelay(DragonGeometry.FLIGHT + 4, () -> {
			h.assertTrue(z.getHealth() <= 200 - DragonGeometry.DAMAGE, "the zombie is struck: " + z.getHealth());
			h.assertTrue(cow.getHealth() <= 200 - DragonGeometry.DAMAGE, "the cow is struck too (damage follows the target filter): " + cow.getHealth());
			h.assertValueEqual(v.getHealth(), v.getMaxHealth(), "the villager is spared");
			h.assertTrue(z.getX() - z0.x >= 1, "the foe is thrown away from the impact: " + (z.getX() - z0.x));
			h.assertTrue(Math.abs(cow.getX() - c0.x) < 0.5, "the cow is not thrown: " + (cow.getX() - c0.x));
			h.succeed();
		});
	}

	private static void tough(LivingEntity e) {
		e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
		e.setHealth(200);
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 100)
	public void waterDragonTurnsSomeDirtToMud(GameTestHelper h) {
		ServerPlayer p = dragonCaster(h);
		MagicTestKit.fill(h, new BlockPos(1, 3, 1), new BlockPos(30, 3, 30), Blocks.GRASS_BLOCK);
		h.assertValueEqual(MagicTestKit.castUltimate(p, SpellIds.WATER_DRAGON), Result.CAST, "result");
		h.runAtTickTime(80, () -> {
			BlockPos under = DRAGON_CELL.below();
			int mud = 0;
			int tops = 0;
			for (BlockPos q : BlockPos.betweenClosed(new BlockPos(1, 3, 1), new BlockPos(30, 3, 30))) {
				// Mud reaches radius + 3 = 9 from the impact cell, which is one above these tops.
				double d2 = q.distSqr(DRAGON_CELL);
				if (h.getBlockState(q).is(Blocks.MUD)) {
					mud++;
					h.assertTrue(d2 <= 9 * 9, "mud only within radius + 3: " + q);
				}
				if (d2 <= 9 * 9) tops++;
			}
			double share = mud / (double) tops;
			h.assertTrue(share > 0.25 && share < 0.55, "about 40 % of the tops turned to mud: " + mud + " / " + tops);
			h.assertTrue(h.getBlockState(under.east(12)).is(Blocks.GRASS_BLOCK), "control: grass far out stays grass");
			h.assertTrue(sources(h, 0, DRAGON_CELL.getY()) >= 50, "and the flood landed on top");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 300)
	public void waterDragonFloodIsLoggedAndUndone(GameTestHelper h) {
		ServerPlayer p = dragonCaster(h);
		h.assertValueEqual(MagicTestKit.castUltimate(p, SpellIds.WATER_DRAGON), Result.CAST, "result");
		h.startSequence()
				.thenWaitUntil(() -> h.assertTrue(sources(h, 4, 4) >= 50, "the flood landed"))
				.thenIdle(5)
				.thenExecute(() -> {
					UndoReport r = MagicCommand.undo(h.getLevel().getServer(), p.getUUID(), 1);
					h.assertTrue(r.casts() == 1 && r.restored() >= 50, "undo takes the flood back: " + r);
					h.assertFalse(h.getBlockState(DRAGON_CELL).getFluidState().isSource(), "the impact cell is no longer a source");
					h.assertFalse(h.getBlockState(DRAGON_CELL.south(4)).getFluidState().isSource(), "nor one 4 out");
				})
				.thenSucceed();
	}
}
