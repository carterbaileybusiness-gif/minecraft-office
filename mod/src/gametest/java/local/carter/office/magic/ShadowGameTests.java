package local.carter.office.magic;

import java.util.List;

import local.carter.office.magic.cast.CastService.Result;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.shadow.ShadowBolt;
import local.carter.office.magic.spell.shadow.WellGeometry;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/** Server GameTests for the Shadow lane: Shadow Bolt and Gravity Well (Task 22). */
public class ShadowGameTests {
	// ---- Shadow Bolt ----

	private static void assertWeakened(GameTestHelper h, LivingEntity e, String who) {
		MobEffectInstance w = e.getEffect(MobEffects.WEAKNESS);
		h.assertTrue(w != null, who + " is weakened");
		h.assertValueEqual(w.getAmplifier(), 0, who + ": Weakness I");
		h.assertTrue(w.getDuration() <= ShadowBolt.WEAKNESS_TICKS && w.getDuration() > ShadowBolt.WEAKNESS_TICKS - 20,
				who + ": weakness for 60 ticks, left " + w.getDuration());
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 30)
	public void shadowBoltWeakensZombie(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 1);
		Zombie z = MagicTestKit.dummy(h, new BlockPos(16, 4, 10));
		MagicTestKit.aim(p, new BlockPos(16, 5, 10));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		long hash = MagicTestKit.blockHash(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.SHADOW_BOLT, 0f), Result.CAST, "result");
		List<FxEvent> fx = rec.fx(p);
		h.assertValueEqual(fx.size(), 1, "one fx");
		h.assertValueEqual(fx.get(0).data()[0], (float) ShadowBolt.SPEED, "fx speed");
		h.assertValueEqual(z.getHealth(), 20f, "not hit before it flies");
		h.runAfterDelay(6, () -> {
			h.assertTrue(z.getHealth() <= 16f, "zombie health " + z.getHealth());
			h.assertTrue(z.getHealth() >= 15.9f, "hit once " + z.getHealth());
			assertWeakened(h, z, "zombie");
			h.assertValueEqual(MagicTestKit.blockHash(h), hash, "a small spell changes no block");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 30)
	public void shadowBoltChargedHitsHarder(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 1);
		Zombie z = MagicTestKit.dummy(h, new BlockPos(16, 4, 12));
		MagicTestKit.aim(p, new BlockPos(16, 5, 12));
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.SHADOW_BOLT, 1f), Result.CAST, "result");
		h.runAfterDelay(8, () -> {
			// Level 1, full charge: P = 2, so 8 damage.
			h.assertTrue(z.getHealth() <= 12f, "zombie health " + z.getHealth());
			assertWeakened(h, z, "zombie");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 50)
	public void shadowBoltStopsAtWall(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 1);
		MagicTestKit.fill(h, new BlockPos(13, 4, 7), new BlockPos(19, 8, 7), Blocks.STONE);
		Zombie behind = MagicTestKit.dummy(h, new BlockPos(16, 4, 10));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		long hash = MagicTestKit.blockHash(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.SHADOW_BOLT, 1f), Result.CAST, "result");
		FxEvent fx = rec.fx(p).getFirst();
		// The bolt is cast and stops on the wall's face, 2.5 ahead of the eye (positive control: it flew and met the wall).
		Vec3 stop = h.relativeVec(fx.target());
		h.assertTrue(Math.abs(stop.z - 7.0) < 1.0E-6, "fx stops on the wall face: " + stop);
		h.assertValueEqual(fx.duration(), 2, "ticks to the wall");
		h.runAfterDelay(30, () -> {
			h.assertValueEqual(behind.getHealth(), 20f, "zombie behind the wall");
			h.assertTrue(!behind.hasEffect(MobEffects.WEAKNESS), "zombie behind the wall not weakened");
			h.assertValueEqual(MagicTestKit.blockHash(h), hash, "the wall is untouched");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 30)
	public void shadowBoltPassesFriendsAndHitsTheFoeBehind(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 1);
		ServerPlayer friend = MagicTestKit.otherPlayer(h, new BlockPos(16, 4, 7));
		// A villager right in the line too (both tall enough that the bolt flies through their boxes).
		Villager villager = MagicTestKit.villager(h, new BlockPos(16, 4, 9));
		Zombie z = MagicTestKit.dummy(h, new BlockPos(16, 4, 12));
		MagicTestKit.aim(p, new BlockPos(16, 5, 12));
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.SHADOW_BOLT, 0f), Result.CAST, "result");
		h.runAfterDelay(8, () -> {
			h.assertTrue(z.getHealth() <= 16f, "the zombie behind them is hit: " + z.getHealth());
			assertWeakened(h, z, "zombie");
			h.assertValueEqual(friend.getHealth(), friend.getMaxHealth(), "player health");
			h.assertTrue(!friend.hasEffect(MobEffects.WEAKNESS), "player not weakened");
			h.assertValueEqual(villager.getHealth(), villager.getMaxHealth(), "villager health");
			h.assertTrue(!villager.hasEffect(MobEffects.WEAKNESS), "villager not weakened");
			h.succeed();
		});
	}

	// ---- Gravity Well ----

	/** Casts Gravity Well at {@code charge} and returns its centre, test-relative (from the one fx it sent). */
	private static Vec3 castWell(GameTestHelper h, ServerPlayer p, float charge) {
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		int before = rec.fx(p).size();
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.GRAVITY_WELL, charge), Result.CAST, "result");
		List<FxEvent> fx = rec.fx(p);
		h.assertValueEqual(fx.size(), before + 1, "one fx");
		return h.relativeVec(fx.getLast().target());
	}

	/** The floor cell under {@code centre} moved by ({@code dx}, {@code dz}): where a mob that far from the centre stands. */
	private static BlockPos floor(Vec3 centre, int dx, int dz) {
		return BlockPos.containing(centre.x + dx, MagicTestKit.FLOOR_TOP + 1, centre.z + dz);
	}

	/** A well aimed at the floor 8 ahead: 1 above the floor, in front of the caster on the aim line. */
	private static Vec3 wellOnTheFloor(GameTestHelper h, ServerPlayer p, float charge) {
		MagicTestKit.aim(p, new BlockPos(16, 3, 12));
		Vec3 c = castWell(h, p, charge);
		h.assertTrue(Math.abs(c.y - (MagicTestKit.FLOOR_TOP + 1 + WellGeometry.HEIGHT)) < 1.0E-6, "1 above the floor: " + c);
		h.assertTrue(Math.abs(c.x - 16.5) < 0.01 && c.z > 9 && c.z < 11, "on the aim line, where it meets the floor: " + c);
		return c;
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 80)
	public void gravityWellPullsZombiesAndItems(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 2);
		Vec3 c = wellOnTheFloor(h, p, 0f);
		Zombie z = MagicTestKit.movableDummy(h, floor(c, 5, 0));
		ItemEntity item = MagicTestKit.item(h, floor(c, -5, 0));
		h.assertTrue(MagicTestKit.dist(z, c) >= 4.5, "zombie starts 5 out: " + MagicTestKit.dist(z, c));
		h.assertTrue(MagicTestKit.dist(item, c) >= 4.5, "item starts 5 out: " + MagicTestKit.dist(item, c));
		h.runAfterDelay(60, () -> {
			h.assertTrue(MagicTestKit.dist(z, c) < 2, "zombie dragged in: " + MagicTestKit.dist(z, c));
			h.assertTrue(MagicTestKit.dist(item, c) < 2, "item dragged in: " + MagicTestKit.dist(item, c));
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 150)
	public void gravityWellChangesNoBlocks(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 2);
		// Loose things round where the well opens (off the aim line): flowers, grass, sand, a torch, snow.
		h.setBlock(new BlockPos(13, 3, 10), Blocks.GRASS_BLOCK);
		h.setBlock(new BlockPos(13, 4, 10), Blocks.POPPY);
		h.setBlock(new BlockPos(19, 3, 9), Blocks.GRASS_BLOCK);
		h.setBlock(new BlockPos(19, 4, 9), Blocks.SHORT_GRASS);
		h.setBlock(new BlockPos(18, 4, 11), Blocks.SAND);
		h.setBlock(new BlockPos(14, 4, 11), Blocks.TORCH);
		h.setBlock(new BlockPos(18, 4, 8), Blocks.SNOW);
		long hash = MagicTestKit.blockHash(h);
		Vec3 c = wellOnTheFloor(h, p, 1f);
		Zombie control = MagicTestKit.movableDummy(h, floor(c, 0, 4));
		double start = MagicTestKit.dist(control, c);
		h.runAfterDelay(WellGeometry.life(1f) + 5, () -> {
			h.assertTrue(MagicTestKit.dist(control, c) < start - 2, "the well ran and pulled: " + MagicTestKit.dist(control, c));
			h.assertValueEqual(MagicTestKit.blockHash(h), hash, "block hash");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 60)
	public void gravityWellIgnoresPlayers(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 2);
		Vec3 c = wellOnTheFloor(h, p, 0f);
		ServerPlayer other = MagicTestKit.otherPlayer(h, floor(c, 4, 0));
		Villager villager = MagicTestKit.villager(h, floor(c, -4, 0));
		Wolf pet = MagicTestKit.tamedWolf(h, floor(c, 0, 4), other);
		Zombie control = MagicTestKit.movableDummy(h, floor(c, -3, 3));
		Vec3 at = other.position();
		double controlStart = MagicTestKit.dist(control, c);
		h.runAfterDelay(5, () -> {
			h.assertTrue(other.getDeltaMovement().horizontalDistance() < 1.0E-6, "player velocity " + other.getDeltaMovement());
			h.assertTrue(villager.getDeltaMovement().horizontalDistance() < 1.0E-6, "villager velocity " + villager.getDeltaMovement());
			h.assertTrue(pet.getDeltaMovement().horizontalDistance() < 1.0E-6, "pet velocity " + pet.getDeltaMovement());
			h.assertTrue(control.getDeltaMovement().horizontalDistance() > 0.03, "the zombie beside them is pulled " + control.getDeltaMovement());
		});
		h.runAfterDelay(40, () -> {
			h.assertTrue(other.position().distanceTo(at) < 1.0E-6, "player moved to " + other.position());
			h.assertTrue(MagicTestKit.dist(control, c) < controlStart - 1, "zombie dragged in: " + MagicTestKit.dist(control, c));
			h.assertValueEqual(other.getHealth(), other.getMaxHealth(), "player health");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 60)
	public void gravityWellHurtsOnlyNearTheCore(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 2);
		Vec3 c = wellOnTheFloor(h, p, 0f);
		// NoAI dummies never move, so they stay where they are put: one in the core, one outside the 2-block hurt zone.
		Zombie core = MagicTestKit.dummy(h, floor(c, 0, 0));
		Zombie outside = MagicTestKit.dummy(h, floor(c, 4, 0));
		h.runAfterDelay(25, () -> {
			h.assertTrue(core.getHealth() <= 19f && core.getHealth() >= 18.9f, "one hit by tick 25: " + core.getHealth());
			h.assertValueEqual(outside.getHealth(), 20f, "outside the hurt zone");
		});
		h.runAfterDelay(45, () -> {
			h.assertTrue(core.getHealth() <= 18f && core.getHealth() >= 17.9f, "two hits by tick 45: " + core.getHealth());
			h.assertValueEqual(outside.getHealth(), 20f, "outside the hurt zone");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 50)
	public void gravityWellDoesNotPullThroughWalls(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 2);
		Vec3 c = wellOnTheFloor(h, p, 0f);
		// A wall 2 to the east of the centre hides the zombie 4 out; the one 4 to the west stands in the open.
		BlockPos w = floor(c, 2, 0);
		MagicTestKit.fill(h, w.offset(0, 0, -2), w.offset(0, 4, 2), Blocks.STONE);
		Zombie hidden = MagicTestKit.movableDummy(h, floor(c, 4, 0));
		Zombie open = MagicTestKit.movableDummy(h, floor(c, -4, 0));
		Vec3 hiddenAt = hidden.position();
		h.runAfterDelay(30, () -> {
			h.assertTrue(hidden.position().distanceTo(hiddenAt) < 0.1, "hidden zombie moved to " + hidden.position());
			h.assertTrue(MagicTestKit.dist(open, c) < 2, "open zombie dragged in: " + MagicTestKit.dist(open, c));
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 120)
	public void gravityWellEndsAfterItsLife(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 2);
		Vec3 c = wellOnTheFloor(h, p, 0f);
		FxEvent fx = MagicTestKit.recorder(h).fx(p).getLast();
		h.assertValueEqual(fx.duration(), 80, "life at charge 0");
		h.assertValueEqual(fx.data()[0], (float) WellGeometry.RADIUS, "pull radius");
		Zombie early = MagicTestKit.movableDummy(h, floor(c, -3, 0));
		double earlyStart = MagicTestKit.dist(early, c);
		Zombie[] late = new Zombie[1];
		Vec3[] lateAt = new Vec3[1];
		h.runAfterDelay(30, () -> h.assertTrue(MagicTestKit.dist(early, c) < earlyStart - 1, "pulled while it lasts"));
		h.runAfterDelay(85, () -> {
			late[0] = MagicTestKit.movableDummy(h, floor(c, 3, 0));
			lateAt[0] = late[0].position();
		});
		h.runAfterDelay(100, () -> {
			h.assertTrue(late[0].position().distanceTo(lateAt[0]) < 0.1, "nothing pulls after its life: " + late[0].position());
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 50)
	public void gravityWellDropsToTheGroundUnderTheRay(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 2);
		// Aimed up into open air: the ray runs its full 16 and the well drops to 1 above the floor under that point.
		MagicTestKit.aim(p, new BlockPos(16, 12, 16));
		Vec3 c = castWell(h, p, 0f);
		h.assertTrue(Math.abs(c.y - (MagicTestKit.FLOOR_TOP + 1 + WellGeometry.HEIGHT)) < 1.0E-6, "1 above the floor: " + c);
		h.assertTrue(c.z > 17 && c.z < 19, "under the end of the ray: " + c);
		Zombie z = MagicTestKit.movableDummy(h, floor(c, 0, -4));
		double start = MagicTestKit.dist(z, c);
		h.runAfterDelay(30, () -> {
			h.assertTrue(MagicTestKit.dist(z, c) < start - 2, "pulls there: " + MagicTestKit.dist(z, c));
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 50)
	public void gravityWellOpensInFrontOfAWall(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 2);
		MagicTestKit.fill(h, new BlockPos(12, 4, 12), new BlockPos(20, 9, 12), Blocks.STONE);
		MagicTestKit.aim(p, new BlockPos(16, 7, 12));
		Vec3 c = castWell(h, p, 0f);
		h.assertTrue(Math.abs(c.y - (MagicTestKit.FLOOR_TOP + 1 + WellGeometry.HEIGHT)) < 1.0E-6, "1 above the floor: " + c);
		h.assertTrue(c.z > 11.3 && c.z < 12, "on this side of the wall: " + c);
		Zombie z = MagicTestKit.movableDummy(h, floor(c, 0, -4));
		double start = MagicTestKit.dist(z, c);
		h.runAfterDelay(30, () -> {
			h.assertTrue(MagicTestKit.dist(z, c) < start - 2, "pulls there: " + MagicTestKit.dist(z, c));
			h.succeed();
		});
	}
}
