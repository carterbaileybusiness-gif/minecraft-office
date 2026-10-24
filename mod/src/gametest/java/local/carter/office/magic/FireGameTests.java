package local.carter.office.magic;

import java.util.ArrayList;
import java.util.List;

import local.carter.office.magic.cast.CastService;
import local.carter.office.magic.cast.CastService.Result;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.ActiveEffects;
import local.carter.office.magic.spell.LingeringEffect;
import local.carter.office.magic.spell.Motion;
import local.carter.office.magic.spell.Projectiles;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.SpellRegistry;
import local.carter.office.magic.spell.fire.BarrageGeometry;
import local.carter.office.magic.spell.fire.FireBolt;
import local.carter.office.magic.spell.fire.FireWall;
import local.carter.office.magic.spell.fire.FireWallGeometry;
import local.carter.office.magic.spell.fire.FireballBarrage;
import local.carter.office.magic.spell.fire.FlameDash;
import local.carter.office.magic.spell.fire.Meteor;
import local.carter.office.magic.spell.fire.MeteorGeometry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/**
 * Server GameTests for the Fire lane, M2 (Task 12): Fire Bolt and Fire Wall, cast through the registry as players cast them
 * ({@link MagicTestKit#cast}; test probes are scoped to their own test's players, so they never hide these).
 */
public class FireGameTests {
	private static final Identifier BOLT = SpellIds.FIRE_BOLT;
	private static final Identifier WALL = SpellIds.FIRE_WALL;
	private static final Identifier DASH = SpellIds.FLAME_DASH;
	private static final Identifier BARRAGE = SpellIds.FIREBALL_BARRAGE;
	private static final Identifier METEOR = SpellIds.METEOR;
	/** On the floor's top face 12 ahead of the caster (a hair under it, so the look ray meets the face right there). */
	private static final Vec3 FLOOR_12_AHEAD = new Vec3(16.5, MagicTestKit.FLOOR_TOP + 0.95, MagicTestKit.STAND.getZ() + 12.5);
	/** The environment of the terrain tests that place hologram emitters (its other test uses no claim rule). */
	private static final String ENV_EMITTER = "office-test:magic_terrain_emitter";

	private static Result cast(ServerPlayer p, Identifier spell, float charge) {
		return MagicTestKit.cast(p, spell, charge);
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void fireSpellsRegisteredInTheirSlots(GameTestHelper h) {
		Spell bolt = SpellRegistry.get(SpellIds.FIRE_BOLT).orElseThrow(() -> new AssertionError("Fire Bolt not registered"));
		h.assertTrue(bolt instanceof FireBolt, "Fire Bolt is " + bolt);
		h.assertValueEqual(bolt.slot(), 0, "bolt slot");
		h.assertValueEqual(bolt.element(), Element.FIRE, "bolt element");
		h.assertValueEqual(SpellRegistry.at(Element.FIRE, 0).map(Spell::id).orElse(null), SpellIds.FIRE_BOLT, "fire slot 0");
		Spell wall = SpellRegistry.get(SpellIds.FIRE_WALL).orElseThrow(() -> new AssertionError("Fire Wall not registered"));
		h.assertTrue(wall instanceof FireWall, "Fire Wall is " + wall);
		h.assertValueEqual(wall.slot(), 1, "wall slot");
		h.assertValueEqual(wall.element(), Element.FIRE, "wall element");
		h.assertValueEqual(SpellRegistry.at(Element.FIRE, 1).map(Spell::id).orElse(null), SpellIds.FIRE_WALL, "fire slot 1");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void fireBoltHurtsAndIgnitesZombie(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 1);
		BlockPos at = MagicTestKit.STAND.offset(0, 0, 6);
		Zombie z = MagicTestKit.dummy(h, at);
		MagicTestKit.aim(p, at.above());
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		long start = h.getTick();
		h.assertValueEqual(cast(p, BOLT, 0f), Result.CAST, "result");
		List<FxEvent> fx = rec.fx(p);
		h.assertValueEqual(fx.size(), 1, "one fx");
		h.assertValueEqual(fx.get(0).spell(), SpellIds.FIRE_BOLT, "fx spell");
		h.succeedWhen(() -> {
			h.assertTrue(h.getTick() - start <= 10, "too slow");
			h.assertTrue(z.getHealth() <= 16, "health " + z.getHealth());
			h.assertTrue(z.isOnFire(), "zombie should burn");
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 60)
	public void fireBoltStopsAtWall(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 1);
		MagicTestKit.fill(h, new BlockPos(12, 4, 7), new BlockPos(20, 9, 7), Blocks.STONE);
		BlockPos at = MagicTestKit.STAND.offset(0, 0, 6);
		Zombie z = MagicTestKit.dummy(h, at);
		MagicTestKit.aim(p, at.above());
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		int flying = Projectiles.count();
		h.assertValueEqual(cast(p, BOLT, 1f), Result.CAST, "result");
		// It really flew: one bolt launched (same tick, so no other test's flight can land in between) and one fx sent.
		h.assertValueEqual(Projectiles.count(), flying + 1, "bolts in flight");
		h.assertValueEqual(rec.fx(p).size(), 1, "one fx");
		h.runAfterDelay(30, () -> {
			h.assertValueEqual(z.getHealth(), 20f, "health behind the wall");
			h.assertFalse(z.isOnFire(), "zombie behind the wall should not burn");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 60)
	public void fireWallBurnsZombieInside(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 2);
		Zombie z = MagicTestKit.dummy(h, MagicTestKit.STAND.offset(0, 0, 4));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		long start = h.getTick();
		h.assertValueEqual(cast(p, WALL, 0f), Result.CAST, "result");
		List<FxEvent> fx = rec.fx(p);
		h.assertValueEqual(fx.size(), 1, "one fx");
		FxEvent e = fx.get(0);
		h.assertValueEqual(e.duration(), FireWallGeometry.duration(0f), "fx duration");
		Vec3 base = h.absoluteVec(Vec3.atBottomCenterOf(MagicTestKit.STAND.offset(0, 0, 4)));
		h.assertTrue(e.target().distanceTo(base) < 0.01, "fx target at the wall's foot, was " + e.target() + " expected " + base);
		h.assertTrue(e.dir().distanceTo(new Vec3(0, 0, 1)) < 1e-6, "fx dir is the flat facing, was " + e.dir());
		h.succeedWhen(() -> {
			h.assertTrue(h.getTick() - start <= 30, "too slow");
			h.assertTrue(z.getHealth() < 20, "health " + z.getHealth());
			h.assertTrue(z.isOnFire(), "zombie should burn");
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 60)
	public void fireWallBurnsAgainAfterTenTicks(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 2);
		Zombie z = MagicTestKit.dummy(h, MagicTestKit.STAND.offset(0, 0, 4));
		z.setRemainingFireTicks(0);
		h.assertValueEqual(cast(p, WALL, 0f), Result.CAST, "result");
		float[] after = new float[1];
		h.runAfterDelay(3, () -> {
			// One pulse: 2 damage (fire ticks on a zombie deal their own damage only every 20 ticks).
			after[0] = z.getHealth();
			h.assertTrue(after[0] <= 18 && after[0] >= 17, "one pulse, health " + after[0]);
			z.setHealth(20);
			z.clearFire();
		});
		h.runAfterDelay(14, () -> {
			h.assertTrue(z.getHealth() <= 18, "second pulse, health " + z.getHealth());
			h.assertTrue(z.isOnFire(), "burning again");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 60)
	public void fireWallOnlyMeltsSnow(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 2);
		// The footprint, 4 ahead, across the aim: grass with a snow layer, stone, ice (as part of the ground), and a snow layer on stone.
		BlockPos mid = MagicTestKit.STAND.offset(0, 0, 4);
		h.setBlock(mid.offset(-2, -1, 0), Blocks.GRASS_BLOCK);
		h.setBlock(mid.offset(-2, 0, 0), Blocks.SNOW);
		h.setBlock(mid.offset(-1, -1, 0), Blocks.GRASS_BLOCK);
		h.setBlock(mid.offset(0, 0, 0), Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 3));
		h.setBlock(mid.offset(1, -1, 0), Blocks.ICE);
		h.setBlock(mid.offset(2, 0, 0), Blocks.STONE);
		// Outside the footprint: snow stays.
		h.setBlock(mid.offset(0, 0, 3), Blocks.SNOW);
		h.setBlock(mid.offset(4, 0, 0), Blocks.SNOW);
		h.assertValueEqual(cast(p, WALL, 0f), Result.CAST, "result");
		h.runAfterDelay(5, () -> {
			h.assertBlockPresent(Blocks.AIR, mid.offset(-2, 0, 0));
			h.assertBlockPresent(Blocks.AIR, mid.offset(0, 0, 0));
			h.assertBlockPresent(Blocks.WATER, mid.offset(1, -1, 0));
			h.assertBlockPresent(Blocks.GRASS_BLOCK, mid.offset(-2, -1, 0));
			h.assertBlockPresent(Blocks.GRASS_BLOCK, mid.offset(-1, -1, 0));
			h.assertBlockPresent(Blocks.STONE, mid.offset(2, 0, 0));
			h.assertBlockPresent(Blocks.STONE, mid.offset(0, -1, 0));
			h.assertBlockPresent(Blocks.SNOW, mid.offset(0, 0, 3));
			h.assertBlockPresent(Blocks.SNOW, mid.offset(4, 0, 0));
			h.assertValueEqual(MagicTestKit.count(h, Blocks.FIRE), 0, "fire blocks");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 60)
	public void fireWallLeavesBareGroundAlone(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 2);
		long before = MagicTestKit.blockHash(h);
		h.assertValueEqual(cast(p, WALL, 1f), Result.CAST, "result");
		h.runAfterDelay(20, () -> {
			h.assertValueEqual(MagicTestKit.blockHash(h), before, "arena unchanged");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 60)
	public void fireWallSparesPlayer(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 2);
		ServerPlayer other = MagicTestKit.otherPlayer(h, MagicTestKit.STAND.offset(0, 0, 4));
		// Positive control: a zombie in the same footprint, two blocks along the wall, does burn.
		Zombie z = MagicTestKit.dummy(h, MagicTestKit.STAND.offset(2, 0, 4));
		h.assertValueEqual(cast(p, WALL, 1f), Result.CAST, "result");
		h.runAfterDelay(35, () -> {
			h.assertTrue(z.getHealth() < 20, "the zombie beside them burns, health " + z.getHealth());
			h.assertTrue(z.isOnFire(), "the zombie beside them is on fire");
			h.assertValueEqual(other.getHealth(), other.getMaxHealth(), "player health");
			h.assertFalse(other.isOnFire(), "player should not burn");
			h.assertValueEqual(p.getHealth(), p.getMaxHealth(), "caster health");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 60)
	public void fireWallCatchesCreatureThrownThrough(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 2);
		h.assertValueEqual(cast(p, WALL, 0f), Result.CAST, "result");
		Zombie[] z = new Zombie[1];
		double wallZ = h.absoluteVec(new Vec3(0, 0, MagicTestKit.STAND.getZ() + 4.5)).z;
		double[] startZ = new double[1];
		// Off the 10-tick beat and fast: one tick before the wall, the next past it (never inside it at a tick's end).
		h.runAfterDelay(4, () -> {
			z[0] = MagicTestKit.movableDummy(h, MagicTestKit.STAND.offset(2, 0, 9));
			z[0].setPos(z[0].getX(), z[0].getY(), wallZ + 4.8);
			startZ[0] = z[0].getZ();
			z[0].setDeltaMovement(0, 0.42, -3.5);
		});
		h.runAfterDelay(14, () -> {
			h.assertTrue(z[0].getZ() < wallZ - 1, "the zombie flew through the wall: z " + z[0].getZ() + " wall " + wallZ + " start " + startZ[0]);
			h.assertTrue(z[0].getHealth() < 20, "health " + z[0].getHealth());
			h.assertTrue(z[0].isOnFire(), "the zombie caught fire passing through");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 60)
	public void fireWallSparesStuckArrows(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 2);
		// An arrow dropped into the floor where the wall will stand, stuck there before the cast.
		Arrow arrow = MagicTestKit.arrow(h, MagicTestKit.STAND.offset(1, 1, 4), new Vec3(0, -1, 0));
		h.runAfterDelay(6, () -> h.assertValueEqual(cast(p, WALL, 0f), Result.CAST, "result"));
		h.runAfterDelay(14, () -> {
			h.assertFalse(arrow.isRemoved(), "a stuck arrow is not ammunition in flight");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void fireWallFizzlesOnWater(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 2);
		MagicTestKit.fill(h, MagicTestKit.STAND.offset(-3, -1, 3), MagicTestKit.STAND.offset(3, -1, 5), Blocks.WATER);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		int mana = MagicAttachments.mana(p);
		int effects = ActiveEffects.count();
		h.assertValueEqual(cast(p, WALL, 0f), Result.FIZZLED, "result");
		h.assertValueEqual(MagicAttachments.mana(p), mana, "mana unchanged");
		h.assertValueEqual(ActiveEffects.count(), effects, "no wall started");
		h.assertValueEqual(rec.fx(p).size(), 0, "no fx");
		h.assertValueEqual(rec.messageKeys(p), List.of(CastService.SPELL_FAILED_KEY), "message");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 60)
	public void fireWallBurnsUpArrows(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 2);
		h.assertValueEqual(cast(p, WALL, 0f), Result.CAST, "result");
		Arrow[] arrow = new Arrow[1];
		// Off to the caster's side (so it could never hit them), flying back through the wall's right half.
		h.runAfterDelay(2, () -> arrow[0] = MagicTestKit.arrow(h, MagicTestKit.STAND.offset(2, 1, 10), new Vec3(0, 0, -1.5)));
		h.runAfterDelay(12, () -> {
			Vec3 at = arrow[0].position();
			h.assertTrue(arrow[0].isRemoved(), "the arrow should burn up in the wall, it is at " + at);
			double wallZ = h.absoluteVec(new Vec3(0, 0, MagicTestKit.STAND.getZ() + 4.5)).z;
			h.assertTrue(Math.abs(at.z - wallZ) < 2.5, "it burnt up at the wall, not at z " + at.z);
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 220)
	public void fireWallEndsAfterItsDuration(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 2);
		h.assertValueEqual(cast(p, WALL, 0f), Result.CAST, "result");
		Zombie[] z = new Zombie[1];
		h.runAfterDelay(FireWallGeometry.duration(0f) + 2, () -> z[0] = MagicTestKit.dummy(h, MagicTestKit.STAND.offset(0, 0, 4)));
		h.runAfterDelay(FireWallGeometry.duration(0f) + 25, () -> {
			h.assertValueEqual(z[0].getHealth(), 20f, "health after the wall is gone");
			h.assertFalse(z[0].isOnFire(), "not burning after the wall is gone");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 60)
	public void fireWallSparesTeleportedCreature(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 2);
		// In front of the wall (it stands at z 8..9): one is teleported far across it, one only hops a short way across.
		Zombie jumper = MagicTestKit.movableDummy(h, MagicTestKit.STAND.offset(2, 0, 9));
		Zombie hopper = MagicTestKit.movableDummy(h, MagicTestKit.STAND.offset(-2, 0, 6));
		// Moves them inside the server tick after the creatures have ticked, as a teleport does: their last positions stay put.
		// (Effects tick in the order they started, so this one runs just before the wall's.)
		ActiveEffects.add(h.getLevel(), new LingeringEffect() {
			@Override
			public boolean tick(ServerLevel level, int age) {
				if (age < 4) return true;
				jumper.setPos(jumper.getX(), jumper.getY(), jumper.getZ() - 11);
				hopper.setPos(hopper.getX(), hopper.getY(), hopper.getZ() - 3.5);
				return false;
			}
		});
		h.assertValueEqual(cast(p, WALL, 0f), Result.CAST, "result");
		h.runAfterDelay(10, () -> {
			double wallZ = h.absoluteVec(new Vec3(0, 0, MagicTestKit.STAND.getZ() + 4.5)).z;
			h.assertTrue(jumper.getZ() < wallZ - 3, "the jumper ended behind the wall: " + h.relativeVec(jumper.position()));
			h.assertValueEqual(jumper.getHealth(), 20f, "the teleported zombie never crossed the fire");
			h.assertFalse(jumper.isOnFire(), "the teleported zombie is not burning");
			// Positive control: the same kind of move, short enough to be a real pass, is swept and burns.
			h.assertTrue(hopper.getHealth() < 20, "the hopper crossed the fire, health " + hopper.getHealth());
			h.assertTrue(hopper.isOnFire(), "the hopper burns");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 60)
	public void fireWallSparesElytraRockets(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 2);
		h.assertValueEqual(cast(p, WALL, 0f), Result.CAST, "result");
		ServerLevel level = h.getLevel();
		ItemStack stack = new ItemStack(Items.FIREWORK_ROCKET);
		stack.set(DataComponents.FIREWORKS, new Fireworks(3, List.of()));
		FireworkRocketEntity[] rockets = new FireworkRocketEntity[2];
		h.runAfterDelay(2, () -> {
			// A rocket held by someone standing in the fire (as one boosting an elytra is held), and a free one flying into it.
			Zombie holder = MagicTestKit.dummy(h, MagicTestKit.STAND.offset(1, 0, 4));
			rockets[0] = new FireworkRocketEntity(level, stack, holder);
			level.addFreshEntity(rockets[0]);
			Vec3 at = h.absoluteVec(new Vec3(MagicTestKit.STAND.getX() - 1 + 0.5, MagicTestKit.STAND.getY() + 1.5, MagicTestKit.STAND.getZ() + 8.5));
			rockets[1] = new FireworkRocketEntity(level, stack, at.x, at.y, at.z, true);
			rockets[1].setDeltaMovement(0, 0, -1);
			level.addFreshEntity(rockets[1]);
		});
		h.runAfterDelay(12, () -> {
			h.assertTrue(rockets[1].isRemoved(), "the free rocket burns up in the wall (positive control)");
			h.assertFalse(rockets[0].isRemoved(), "the held rocket is spared");
			h.succeed();
		});
	}

	// ---- M3: Flame Dash, Fireball Barrage, Meteor ----

	@GameTest(structure = MagicTestKit.ARENA)
	public void fireM3SpellsRegisteredInTheirSlots(GameTestHelper h) {
		Spell dash = SpellRegistry.get(DASH).orElseThrow(() -> new AssertionError("Flame Dash not registered"));
		h.assertTrue(dash instanceof FlameDash, "Flame Dash is " + dash);
		Spell barrage = SpellRegistry.get(BARRAGE).orElseThrow(() -> new AssertionError("Fireball Barrage not registered"));
		h.assertTrue(barrage instanceof FireballBarrage, "Fireball Barrage is " + barrage);
		Spell meteor = SpellRegistry.get(METEOR).orElseThrow(() -> new AssertionError("Meteor not registered"));
		h.assertTrue(meteor instanceof Meteor, "Meteor is " + meteor);
		h.assertValueEqual(SpellRegistry.at(Element.FIRE, 2).map(Spell::id).orElse(null), DASH, "fire slot 2");
		h.assertValueEqual(SpellRegistry.at(Element.FIRE, 3).map(Spell::id).orElse(null), BARRAGE, "fire slot 3");
		h.assertValueEqual(SpellRegistry.at(Element.FIRE, 4).map(Spell::id).orElse(null), METEOR, "fire slot 4");
		h.succeed();
	}

	/**
	 * The server never moves a player itself (their client does, from the velocity a dash syncs to it), so the test plays that
	 * client: each tick it moves the caster by their velocity against the blocks and slows it the way vanilla does (air drag,
	 * ground friction on stone, gravity).
	 */
	private static void clientStep(ServerPlayer p) {
		p.move(MoverType.SELF, p.getDeltaMovement());
		Vec3 v = p.getDeltaMovement();
		double friction = p.onGround() ? 0.6 * 0.91 : 0.91;
		p.setDeltaMovement(v.x * friction, (v.y - 0.08) * 0.98, v.z * friction);
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void flameDashMovesAndBurns(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 3);
		Zombie onPath = MagicTestKit.dummy(h, MagicTestKit.STAND.offset(0, 0, 3));
		// Positive control for the reach: one three blocks off to the side is never touched.
		Zombie aside = MagicTestKit.dummy(h, MagicTestKit.STAND.offset(3, 0, 3));
		ServerPlayer other = MagicTestKit.otherPlayer(h, MagicTestKit.STAND.offset(-4, 0, 0));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		Vec3 start = p.position();
		long t0 = h.getTick();
		h.assertValueEqual(cast(p, DASH, 0f), Result.CAST, "result");
		FxEvent e = rec.fx(p).get(0);
		h.assertTrue(e.target().distanceTo(start) < 1e-6, "fx target is where the dash starts");
		h.assertTrue(e.dir().distanceTo(new Vec3(0, 0, 1)) < 1e-6, "fx dir is the flat facing " + e.dir());
		h.assertValueEqual(e.data()[0], 1.6f, "fx speed");
		Vec3 v = p.getDeltaMovement();
		h.assertTrue(v.distanceTo(new Vec3(0, FlameDash.UP, 1.6)) < 1e-6, "dash velocity " + v);
		// No fall damage in the window; the same fall hurts a player who did not dash.
		h.assertTrue(Motion.noFall(p), "no-fall window open");
		ServerLevel level = h.getLevel();
		p.hurtServer(level, level.damageSources().fall(), 6);
		h.assertValueEqual(p.getHealth(), p.getMaxHealth(), "caster's fall damage cancelled");
		other.hurtServer(level, level.damageSources().fall(), 6);
		h.assertTrue(other.getHealth() < other.getMaxHealth(), "the control player does take fall damage");
		h.onEachTick(() -> clientStep(p));
		h.succeedWhen(() -> {
			h.assertTrue(h.getTick() - t0 <= 10, "too slow");
			h.assertTrue(p.getZ() - start.z >= 5, "dashed " + (p.getZ() - start.z));
			h.assertTrue(onPath.isOnFire(), "the zombie on the path burns");
			h.assertTrue(onPath.getHealth() <= 20 - 3, "health " + onPath.getHealth());
			h.assertValueEqual(aside.getHealth(), 20f, "the zombie aside");
			h.assertFalse(aside.isOnFire(), "the zombie aside is not burning");
			h.assertValueEqual(p.getHealth(), p.getMaxHealth(), "caster unhurt");
		});
	}

	/** The look straight at {@code rel} (any point, not only a block's centre), set the way the kit's {@code aim} sets it. */
	private static void aimAt(GameTestHelper h, ServerPlayer p, Vec3 rel) {
		Vec3 d = h.absoluteVec(rel).subtract(p.getEyePosition());
		double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
		float yaw = Mth.wrapDegrees((float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f);
		float pitch = (float) -(Mth.atan2(d.y, horizontal) * Mth.RAD_TO_DEG);
		p.setYRot(yaw);
		p.setXRot(pitch);
		p.yRotO = yaw;
		p.xRotO = pitch;
		p.setYHeadRot(yaw);
		p.setYBodyRot(yaw);
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 120)
	public void barrageFiresThreeAtChargeZero(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 5);
		aimAt(h, p, FLOOR_12_AHEAD);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		List<FireballBarrage.Impact> impacts = new ArrayList<>();
		AutoCloseable listening = FireballBarrage.listenForTesting(i -> {
			if (i.caster().equals(p.getUUID())) impacts.add(i);
		});
		h.assertValueEqual(cast(p, BARRAGE, 0f), Result.CAST, "result");
		List<FxEvent> fx = rec.fx(p);
		h.assertValueEqual(fx.size(), 1, "one fx");
		FxEvent e = fx.get(0);
		h.assertValueEqual(e.data()[0], 3f, "three fireballs at charge 0");
		h.assertTrue(e.target().distanceTo(h.absoluteVec(FLOOR_12_AHEAD.add(0, 0.05, 0))) < 1, "fx target is the aim point, was "
				+ h.relativeVec(e.target()));
		List<Vec3> aims = BarrageGeometry.aims(e.target(), e.seed(), 3);
		h.runAfterDelay(2 * BarrageGeometry.INTERVAL + 30, () -> {
			try {
				listening.close();
			} catch (Exception ex) {
				throw new RuntimeException(ex);
			}
			h.assertValueEqual(impacts.size(), 3, "impacts");
			for (int i = 0; i < 3; i++) {
				FireballBarrage.Impact im = impacts.get(i);
				// At its aim, or a block down its path where an earlier crater already opened the floor.
				h.assertTrue(im.at().distanceTo(aims.get(i)) < 2, "fireball " + i + " landed at its aim: " + h.relativeVec(im.at()) + " vs "
						+ h.relativeVec(aims.get(i)));
				h.assertTrue(im.crater() != null, "fireball " + i + " left a crater");
				h.assertTrue(Vec3.atCenterOf(im.crater()).distanceTo(im.at()) <= 6, "crater " + i + " within 6 of its impact");
				h.assertTrue(im.removed() > 0, "crater " + i + " removed blocks");
				h.assertTrue(h.getLevel().getBlockState(im.crater()).isAir(), "crater " + i + " is open");
			}
			int open = 0;
			for (int x = 0; x < MagicTestKit.SIZE_X; x++) {
				for (int z = 0; z < MagicTestKit.SIZE_Z; z++) {
					for (int y = 0; y <= MagicTestKit.FLOOR_TOP; y++) {
						BlockPos c = new BlockPos(x, y, z);
						if (!h.getBlockState(c).isAir()) continue;
						open++;
						h.assertTrue(h.absoluteVec(Vec3.atCenterOf(c)).distanceTo(e.target()) <= BarrageGeometry.SPREAD + 3.5,
								"floor blasted only round the aim, not at " + c);
					}
				}
			}
			h.assertTrue(open >= 9, "three craters' worth of floor blasted: " + open);
			h.assertTrue(MagicTestKit.count(h, Blocks.NETHERRACK) >= 1, "scorched netherrack");
			h.assertValueEqual(MagicTestKit.count(h, Blocks.FIRE), 0, "fire blocks");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 80)
	public void barrageBlastHurtsInRangeOnly(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 5);
		aimAt(h, p, FLOOR_12_AHEAD);
		Zombie inside = MagicTestKit.movableDummy(h, BlockPos.containing(FLOOR_12_AHEAD).above());
		// Control of the radius: 7 away it is untouched.
		Zombie far = MagicTestKit.dummy(h, BlockPos.containing(FLOOR_12_AHEAD).offset(7, 1, 0));
		h.assertValueEqual(cast(p, BARRAGE, 1f), Result.CAST, "result");
		h.runAfterDelay(4 * BarrageGeometry.INTERVAL + 25, () -> {
			h.assertTrue(!inside.isAlive() || inside.getHealth() < 20, "health " + inside.getHealth());
			h.assertValueEqual(far.getHealth(), 20f, "far zombie");
			h.assertFalse(far.isOnFire(), "far zombie not burning");
			h.succeed();
		});
	}

	/** Glass round the arena's edge, 4..20 high, so a meteor's debris lands in this test's arena and nowhere else. */
	private static void fence(GameTestHelper h) {
		int top = 20;
		int mx = MagicTestKit.SIZE_X - 1;
		int mz = MagicTestKit.SIZE_Z - 1;
		MagicTestKit.fill(h, new BlockPos(0, 4, 0), new BlockPos(mx, top, 0), Blocks.GLASS);
		MagicTestKit.fill(h, new BlockPos(0, 4, mz), new BlockPos(mx, top, mz), Blocks.GLASS);
		MagicTestKit.fill(h, new BlockPos(0, 4, 0), new BlockPos(0, top, mz), Blocks.GLASS);
		MagicTestKit.fill(h, new BlockPos(mx, 4, 0), new BlockPos(mx, top, mz), Blocks.GLASS);
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 140)
	public void meteorCratersAndKills(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 7);
		fence(h);
		aimAt(h, p, FLOOR_12_AHEAD);
		Zombie z = MagicTestKit.dummy(h, BlockPos.containing(FLOOR_12_AHEAD).above());
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		long t0 = h.getTick();
		h.assertValueEqual(MagicTestKit.castUltimate(p, METEOR), Result.CAST, "result");
		List<FxEvent> fx = rec.fx(p);
		h.assertValueEqual(fx.size(), 1, "one fx");
		FxEvent e = fx.get(0);
		h.assertValueEqual(e.duration(), MeteorGeometry.FALL_TICKS, "fall ticks");
		h.assertValueEqual(e.data()[0], 7f, "radius at level 7");
		h.assertTrue(e.target().distanceTo(h.absoluteVec(FLOOR_12_AHEAD.add(0, 0.05, 0))) < 1, "falls where the caster pointed, at "
				+ h.relativeVec(e.target()));
		BlockPos origin = BlockPos.containing(h.relativeVec(new Vec3(e.target().x, e.target().y - 0.5, e.target().z)));
		h.runAtTickTime(t0 + 30, () -> h.assertTrue(z.isAlive() && z.getHealth() == 20, "nothing lands before the rock does"));
		h.runAtTickTime(t0 + 45, () -> h.assertFalse(z.isAlive(), "the zombie under it is dead by tick 45, health " + z.getHealth()));
		h.runAtTickTime(t0 + 52, () -> {
			int removed = 0;
			for (int x = 0; x < MagicTestKit.SIZE_X; x++) {
				for (int zz = 0; zz < MagicTestKit.SIZE_Z; zz++) {
					for (int y = 0; y <= MagicTestKit.FLOOR_TOP; y++) {
						BlockPos c = new BlockPos(x, y, zz);
						if (!h.getBlockState(c).isAir()) continue;
						removed++;
						h.assertTrue(Math.sqrt(c.distSqr(origin)) <= 7, "removed beyond 7 at level 7: " + c + " from " + origin);
					}
				}
			}
			h.assertTrue(removed >= 100, "blocks removed " + removed);
			h.assertTrue(MagicTestKit.count(h, Blocks.MAGMA_BLOCK) > 0, "magma floor");
			h.assertTrue(MagicTestKit.count(h, Blocks.BLACKSTONE) > 0, "blackstone floor");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV_EMITTER, maxTicks = 140)
	public void meteorRespectsClaimFallback(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 7);
		aimAt(h, p, FLOOR_12_AHEAD);
		// An emitter about 20 from where it lands: the fallback claim rule protects everything within 32 of it.
		BlockPos emitter = new BlockPos(4, 4, 0);
		MagicTestKit.emitter(h, emitter, p);
		Zombie z = MagicTestKit.dummy(h, BlockPos.containing(FLOOR_12_AHEAD).offset(0, 1, 6));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		long before = MagicTestKit.blockHash(h);
		long t0 = h.getTick();
		h.assertValueEqual(MagicTestKit.castUltimate(p, METEOR), Result.CAST, "result");
		Vec3 target = rec.fx(p).get(0).target();
		double fromEmitter = h.absoluteVec(Vec3.atBottomCenterOf(emitter)).distanceTo(target);
		h.assertTrue(fromEmitter > 19 && fromEmitter < 22, "the emitter is about 20 from the target: " + fromEmitter);
		h.runAtTickTime(t0 + 55, () -> {
			h.assertValueEqual(MagicTestKit.blockHash(h), before, "no block changed");
			h.assertTrue(z.getHealth() < 20, "the zombie is still hurt, health " + z.getHealth());
			h.assertTrue(rec.messageKeys(p).contains("magic.office.protected"), "told the land is protected: " + rec.messageKeys(p));
			h.succeed();
		});
	}
}
