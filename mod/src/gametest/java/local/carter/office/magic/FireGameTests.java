package local.carter.office.magic;

import java.util.List;

import local.carter.office.magic.cast.CastService;
import local.carter.office.magic.cast.CastService.Result;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.ActiveEffects;
import local.carter.office.magic.spell.Projectiles;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.SpellRegistry;
import local.carter.office.magic.spell.fire.FireBolt;
import local.carter.office.magic.spell.fire.FireWall;
import local.carter.office.magic.spell.fire.FireWallGeometry;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/**
 * Server GameTests for the Fire lane, M2 (Task 12): Fire Bolt and Fire Wall. Casts go straight through {@link CastService#cast}
 * with the spell objects, not the registry: concurrent tests lay probes over Fire's slots 0 and 1, which hide registered spells.
 */
public class FireGameTests {
	private static final Spell BOLT = new FireBolt();
	private static final Spell WALL = new FireWall();

	private static Result cast(ServerPlayer p, Spell s, float charge) {
		return CastService.cast(p, s, charge);
	}

	/**
	 * The registry holds both spells in their slots. In {@link MagicTestKit#TERRAIN_ENV}: no test in that batch lays a probe over
	 * Fire's slots, so the registered spells are visible.
	 */
	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV)
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
			z[0] = MagicTestKit.dummy(h, MagicTestKit.STAND.offset(2, 0, 9));
			z[0].setNoAi(false); // a mob without AI never moves, even when thrown
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
}
