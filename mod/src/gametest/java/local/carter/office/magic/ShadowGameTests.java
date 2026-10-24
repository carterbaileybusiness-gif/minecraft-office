package local.carter.office.magic;

import java.util.ArrayList;
import java.util.List;

import local.carter.office.magic.cast.CastService.Result;
import local.carter.office.magic.command.MagicCommand;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.SpellRegistry;
import local.carter.office.magic.spell.shadow.BindGeometry;
import local.carter.office.magic.spell.shadow.BlackHole;
import local.carter.office.magic.spell.shadow.BlackHoleGeometry;
import local.carter.office.magic.spell.shadow.ShadowBind;
import local.carter.office.magic.spell.shadow.ShadowBolt;
import local.carter.office.magic.spell.shadow.ShadowStep;
import local.carter.office.magic.spell.shadow.WellGeometry;
import local.carter.office.magic.terrain.EditLog.UndoReport;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/** Server GameTests for the Shadow lane: Shadow Bolt and Gravity Well (Task 22); Shadow Step, Shadow Bind and Black Hole (Task 23). */
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
		Vec3 villagerAt = villager.position();
		Vec3 petAt = pet.position();
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
			h.assertTrue(villager.position().distanceTo(villagerAt) < 1.0E-6, "villager moved to " + villager.position());
			h.assertValueEqual(villager.getHealth(), villager.getMaxHealth(), "villager health");
			h.assertTrue(pet.position().distanceTo(petAt) < 1.0E-6, "pet moved to " + pet.position());
			h.assertValueEqual(pet.getHealth(), pet.getMaxHealth(), "pet health");
			h.succeed();
		});
	}

	/** {@code type} at {@code rel} with every goal removed: it stands still unless something moves it. */
	private static <T extends Mob> T still(GameTestHelper h, EntityType<T> type, BlockPos rel) {
		T m = h.spawn(type, rel);
		m.removeAllGoals(g -> true);
		m.setPersistenceRequired();
		return m;
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 60)
	public void gravityWellSparesLivestock(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 2);
		Vec3 c = wellOnTheFloor(h, p, 0f);
		// Friends' animals: a chicken 3 out (in the pull) and a sheep 2 out (in the 2-block hurt zone, but clear of where the
		// dragged zombie lands, so it is never shoved by it). AI on, goals removed, so a pull would really move them.
		Chicken chicken = still(h, EntityTypes.CHICKEN, floor(c, 3, 0));
		Sheep sheep = still(h, EntityTypes.SHEEP, floor(c, -2, 0));
		// Positive controls: a zombie 3 out (from the caster's side) is dragged, a NoAI zombie by the core is hurt.
		Zombie pulled = MagicTestKit.movableDummy(h, floor(c, 0, -3));
		Zombie core = MagicTestKit.dummy(h, floor(c, 0, 1));
		Vec3 chickenAt = chicken.position();
		Vec3 sheepAt = sheep.position();
		double pulledStart = MagicTestKit.dist(pulled, c);
		h.runAfterDelay(45, () -> {
			h.assertTrue(MagicTestKit.dist(pulled, c) < pulledStart - 1, "zombie dragged in: " + MagicTestKit.dist(pulled, c));
			h.assertTrue(core.getHealth() <= 18f, "zombie in the core hurt twice: " + core.getHealth());
			h.assertTrue(chicken.position().subtract(chickenAt).horizontalDistance() < 0.1, "chicken moved to " + chicken.position());
			h.assertTrue(sheep.position().subtract(sheepAt).horizontalDistance() < 0.1, "sheep moved to " + sheep.position());
			h.assertValueEqual(chicken.getHealth(), chicken.getMaxHealth(), "chicken health");
			h.assertValueEqual(sheep.getHealth(), sheep.getMaxHealth(), "sheep health");
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

	// ---- Task 23: registration ----

	@GameTest(structure = MagicTestKit.ARENA)
	public void shadowM3SpellsRegisteredInTheirSlots(GameTestHelper h) {
		Spell step = SpellRegistry.at(Element.SHADOW, Slots.SMALL_B).orElseThrow(() -> new AssertionError("no shadow slot 2"));
		Spell bind = SpellRegistry.at(Element.SHADOW, Slots.MEDIUM_B).orElseThrow(() -> new AssertionError("no shadow slot 3"));
		Spell hole = SpellRegistry.at(Element.SHADOW, Slots.ULTIMATE).orElseThrow(() -> new AssertionError("no shadow slot 4"));
		h.assertTrue(step instanceof ShadowStep && step.id().equals(SpellIds.SHADOW_STEP), "slot 2 is Shadow Step: " + step);
		h.assertTrue(bind instanceof ShadowBind && bind.id().equals(SpellIds.SHADOW_BIND), "slot 3 is Shadow Bind: " + bind);
		h.assertTrue(hole instanceof BlackHole && hole.id().equals(SpellIds.BLACK_HOLE), "slot 4 is Black Hole: " + hole);
		h.assertValueEqual(hole.cost().xpLevels(), 3, "the ultimate costs XP levels");
		h.succeed();
	}

	// ---- Shadow Step ----

	/** Whether {@code p} looks at {@code e}'s eyes (within about 18°). */
	private static boolean facing(ServerPlayer p, LivingEntity e) {
		Vec3 to = e.getEyePosition().subtract(p.getEyePosition()).normalize();
		return p.getLookAngle().dot(to) > 0.95;
	}

	private static void assertStepped(GameTestHelper h, ServerPlayer p) {
		h.assertTrue(h.getLevel().noCollision(p, p.getBoundingBox()), "never inside a block: " + h.relativeVec(p.position()));
		MobEffectInstance inv = p.getEffect(MobEffects.INVISIBILITY);
		h.assertTrue(inv != null && inv.getDuration() <= ShadowStep.INVISIBLE_TICKS && inv.getDuration() > 15, "invisible for 20: " + inv);
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 30)
	public void shadowStepLandsBehindZombie(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 3);
		MagicTestKit.recorder(h); // installed before the cast, so it sees the fx
		Zombie z = MagicTestKit.dummy(h, new BlockPos(16, 4, 12));
		MagicTestKit.aim(p, new BlockPos(16, 5, 12));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		long hash = MagicTestKit.blockHash(h);
		Vec3 start = p.position();
		h.assertTrue(start.z < z.getZ(), "starts on the near side");
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.SHADOW_STEP, 0f), Result.CAST, "result");
		Vec3 at = p.position();
		h.assertTrue(at.z > z.getZ() + 0.5, "ends on the far side: " + h.relativeVec(at) + ", zombie at " + h.relativeVec(z.position()));
		h.assertTrue(at.distanceTo(z.position()) <= 2, "within 2 of the zombie: " + at.distanceTo(z.position()));
		h.assertTrue(facing(p, z), "facing it: look " + p.getLookAngle());
		assertStepped(h, p);
		FxEvent fx = rec.fx(p).getLast();
		h.assertTrue(fx.target().distanceTo(at) < 1.0E-6, "fx lands where the caster did");
		h.assertValueEqual(fx.entities().length, 1, "the target in the fx");
		h.assertValueEqual(fx.entities()[0], z.getId(), "the target in the fx");
		h.assertTrue(at.add(fx.data()[0], fx.data()[1], fx.data()[2]).distanceTo(start) < 1.0E-3, "fx knows where it left");
		h.assertValueEqual(z.getHealth(), 20f, "a step does no damage");
		h.assertValueEqual(MagicTestKit.blockHash(h), hash, "a small spell changes no block");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 30)
	public void shadowStepWithoutTargetGoesEightAhead(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 3);
		MagicTestKit.recorder(h); // installed before the cast, so it sees the fx
		MagicTestKit.aim(p, new BlockPos(16, 6, 28));
		Vec3 start = p.position();
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.SHADOW_STEP, 0f), Result.CAST, "result");
		Vec3 moved = p.position().subtract(start);
		h.assertTrue(Math.abs(moved.z - ShadowStep.AHEAD) < 1.0E-3 && Math.abs(moved.x) < 1.0E-3 && Math.abs(moved.y) < 1.0E-3,
				"8 ahead along the ground: " + moved);
		h.assertValueEqual(MagicTestKit.recorder(h).fx(p).getLast().entities().length, 0, "no target");
		assertStepped(h, p);
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 30)
	public void shadowStepNeedsLineOfSight(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 3);
		MagicTestKit.recorder(h); // installed before the cast, so it sees the fx
		// A wall with a zombie pressed against its far side: aim assist (1.5) alone would reach it through the wall.
		MagicTestKit.fill(h, new BlockPos(12, 4, 8), new BlockPos(20, 9, 8), Blocks.STONE);
		Zombie z = MagicTestKit.dummy(h, new BlockPos(16, 4, 9));
		MagicTestKit.aim(p, new BlockPos(16, 5, 9));
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.SHADOW_STEP, 0f), Result.CAST, "result");
		h.assertTrue(p.getZ() < h.absoluteVec(new Vec3(0, 0, 8)).z, "still on this side of the wall: " + h.relativeVec(p.position()));
		h.assertTrue(p.getZ() > h.absoluteVec(new Vec3(0, 0, 6)).z, "stepped ahead, up to the wall: " + h.relativeVec(p.position()));
		h.assertValueEqual(MagicTestKit.recorder(h).fx(p).getLast().entities().length, 0, "the hidden zombie is no target");
		assertStepped(h, p);
		h.assertTrue(z.isAlive(), "zombie there");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 30)
	public void shadowStepBesideATargetWithItsBackToAWall(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 3);
		MagicTestKit.recorder(h); // installed before the cast, so it sees the fx
		MagicTestKit.fill(h, new BlockPos(13, 4, 13), new BlockPos(19, 8, 13), Blocks.STONE);
		Zombie z = MagicTestKit.dummy(h, new BlockPos(16, 4, 12));
		MagicTestKit.aim(p, new BlockPos(16, 5, 12));
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.SHADOW_STEP, 0f), Result.CAST, "result");
		Vec3 at = p.position();
		h.assertTrue(at.z < h.absoluteVec(new Vec3(0, 0, 13)).z, "never through the wall: " + h.relativeVec(at));
		h.assertTrue(at.distanceTo(z.position()) <= 2 && at.distanceTo(z.position()) > 1, "beside the zombie: " + at.distanceTo(z.position()));
		h.assertTrue(facing(p, z), "facing it: look " + p.getLookAngle());
		assertStepped(h, p);
		h.succeed();
	}

	// ---- Shadow Bind ----

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 100)
	public void shadowBindRootsZombie(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 5);
		MagicTestKit.recorder(h); // installed before the cast, so it sees the fx
		Zombie z = MagicTestKit.movableDummy(h, new BlockPos(16, 4, 12));
		Zombie control = MagicTestKit.movableDummy(h, new BlockPos(22, 4, 12));
		MagicTestKit.aim(p, new BlockPos(16, 5, 12));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		long hash = MagicTestKit.blockHash(h);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.SHADOW_BIND, 0f), Result.CAST, "result");
		FxEvent fx = rec.fx(p).getLast();
		h.assertValueEqual(fx.duration(), 60, "rooted for 60 at charge 0");
		h.assertValueEqual(fx.data()[0], 1f, "rooted");
		h.assertValueEqual(fx.entities()[0], z.getId(), "the zombie in the fx");
		// Level 5, charge 0: P = 1.2, so 4.8 damage.
		h.assertTrue(z.getHealth() <= 20 - 4.8f + 1.0E-3 && z.getHealth() > 14, "hurt once: " + z.getHealth());
		MobEffectInstance slow = z.getEffect(MobEffects.SLOWNESS);
		h.assertTrue(slow != null && slow.getAmplifier() == BindGeometry.SLOWNESS_AMPLIFIER, "Slowness VI: " + slow);
		Vec3 zAt = z.position();
		Vec3 controlAt = control.position();
		// Both are shoved east every tick; only the free one goes anywhere.
		h.onEachTick(() -> {
			z.setDeltaMovement(0.25, z.getDeltaMovement().y, 0);
			control.setDeltaMovement(0.25, control.getDeltaMovement().y, 0);
		});
		h.runAfterDelay(30, () -> h.assertTrue(z.position().subtract(zAt).horizontalDistance() < 0.5, "held at 30: " + z.position().subtract(zAt)));
		h.runAfterDelay(58, () -> {
			h.assertTrue(z.position().subtract(zAt).horizontalDistance() < 0.5, "moved " + z.position().subtract(zAt) + " in 60 ticks");
			h.assertTrue(control.position().subtract(controlAt).horizontalDistance() > 3, "the free zombie is shoved: "
					+ control.position().subtract(controlAt));
			h.assertValueEqual(MagicTestKit.blockHash(h), hash, "no block changes");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 120)
	public void shadowBindLetsGoAfterItsLife(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 5);
		MagicTestKit.recorder(h); // installed before the cast, so it sees the fx
		Zombie z = MagicTestKit.movableDummy(h, new BlockPos(16, 4, 12));
		MagicTestKit.aim(p, new BlockPos(16, 5, 12));
		long t0 = h.getTick();
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.SHADOW_BIND, 1f), Result.CAST, "result");
		h.assertValueEqual(MagicTestKit.recorder(h).fx(p).getLast().duration(), 100, "rooted for 100 at full charge");
		Vec3[] at = new Vec3[1];
		h.onEachTick(() -> {
			if (h.getTick() >= t0 + 20) z.setDeltaMovement(0.25, z.getDeltaMovement().y, 0);
		});
		h.runAtTickTime(t0 + 20, () -> at[0] = z.position());
		h.runAtTickTime(t0 + 95, () -> h.assertTrue(z.position().subtract(at[0]).horizontalDistance() < 0.5, "still held at 95: "
				+ z.position().subtract(at[0])));
		h.runAtTickTime(t0 + 115, () -> {
			h.assertTrue(z.position().subtract(at[0]).horizontalDistance() > 2, "free after 100: " + z.position().subtract(at[0]));
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 60)
	public void shadowBindOnlyRootsFoes(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 5);
		MagicTestKit.recorder(h); // installed before the cast, so it sees the fx
		Sheep sheep = still(h, EntityTypes.SHEEP, new BlockPos(16, 4, 12));
		MagicTestKit.aim(p, new BlockPos(16, 4, 12));
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.SHADOW_BIND, 0f), Result.CAST, "result");
		FxEvent fx = MagicTestKit.recorder(h).fx(p).getLast();
		h.assertValueEqual(fx.entities()[0], sheep.getId(), "the sheep was the target");
		h.assertValueEqual(fx.data()[0], 0f, "not rooted");
		h.assertTrue(sheep.getHealth() < sheep.getMaxHealth(), "the damage goes through TargetFilter: " + sheep.getHealth());
		h.assertTrue(!sheep.hasEffect(MobEffects.SLOWNESS), "no slowness on livestock");
		Vec3 at = sheep.position();
		h.onEachTick(() -> sheep.setDeltaMovement(0.25, sheep.getDeltaMovement().y, 0));
		h.runAfterDelay(40, () -> {
			h.assertTrue(sheep.position().subtract(at).horizontalDistance() > 2, "not held: " + sheep.position().subtract(at));
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void shadowBindNeedsLineOfSight(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 5);
		MagicTestKit.recorder(h); // installed before the cast, so it sees the fx
		MagicTestKit.fill(h, new BlockPos(12, 4, 8), new BlockPos(20, 9, 8), Blocks.STONE);
		Zombie hidden = MagicTestKit.dummy(h, new BlockPos(16, 4, 9));
		MagicTestKit.aim(p, new BlockPos(16, 5, 9));
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.SHADOW_BIND, 0f), Result.CAST, "result");
		FxEvent fx = MagicTestKit.recorder(h).fx(p).getLast();
		h.assertValueEqual(fx.entities().length, 0, "no target through the wall");
		h.assertValueEqual(hidden.getHealth(), 20f, "unhurt");
		h.assertTrue(!hidden.hasEffect(MobEffects.SLOWNESS), "not slowed");
		h.succeed();
	}

	// ---- Black Hole ----

	/** Glass round the arena's edge, so debris flung out at the collapse stays in this test's arena. */
	private static void fence(GameTestHelper h) {
		int mx = MagicTestKit.SIZE_X - 1;
		int mz = MagicTestKit.SIZE_Z - 1;
		int top = 20;
		MagicTestKit.fill(h, new BlockPos(0, 4, 0), new BlockPos(mx, top, 0), Blocks.GLASS);
		MagicTestKit.fill(h, new BlockPos(0, 4, mz), new BlockPos(mx, top, mz), Blocks.GLASS);
		MagicTestKit.fill(h, new BlockPos(0, 4, 0), new BlockPos(0, top, mz), Blocks.GLASS);
		MagicTestKit.fill(h, new BlockPos(mx, 4, 0), new BlockPos(mx, top, mz), Blocks.GLASS);
	}

	/** Casts Black Hole (signs answered) at the floor ahead; returns its centre, test-relative (2 above the floor). */
	private static Vec3 castHole(GameTestHelper h, ServerPlayer p) {
		MagicTestKit.aim(p, new BlockPos(16, 3, 18));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.castUltimate(p, SpellIds.BLACK_HOLE), Result.CAST, "result");
		FxEvent fx = rec.fx(p).getLast();
		h.assertTrue(fx.spell().equals(SpellIds.BLACK_HOLE), "the black hole's fx");
		h.assertValueEqual(fx.duration(), BlackHoleGeometry.LIFE, "life");
		h.assertValueEqual(fx.data()[1], (float) BlackHoleGeometry.PULL_RADIUS, "pull radius");
		Vec3 c = h.relativeVec(fx.target());
		h.assertTrue(Math.abs(c.y - (MagicTestKit.FLOOR_TOP + 1 + BlackHoleGeometry.HEIGHT)) < 1.0E-6, "2 above the floor: " + c);
		h.assertTrue(Math.abs(c.x - 16.5) < 0.01 && c.z > 11 && c.z < 17, "where the look meets the floor: " + c);
		return c;
	}

	/** Every non-air cell in the arena's lower part (test-relative). */
	private static List<BlockPos> solidCells(GameTestHelper h) {
		List<BlockPos> out = new ArrayList<>();
		for (int y = 0; y <= 12; y++) {
			for (int z = 0; z < MagicTestKit.SIZE_Z; z++) {
				for (int x = 0; x < MagicTestKit.SIZE_X; x++) {
					BlockPos c = new BlockPos(x, y, z);
					if (!h.getBlockState(c).isAir()) out.add(c);
				}
			}
		}
		return out;
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 180)
	public void blackHoleDevoursSphereWithinCap(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 7);
		MagicTestKit.recorder(h); // installed before the cast, so it sees the fx
		fence(h);
		// A mound to devour as well as the floor.
		MagicTestKit.fill(h, new BlockPos(14, 4, 13), new BlockPos(18, 5, 17), Blocks.STONE);
		// A pillar its rim cuts through above the centre (a tree trunk, a wall): never scorched up there.
		MagicTestKit.fill(h, new BlockPos(22, 4, 12), new BlockPos(22, 12, 12), Blocks.STONE);
		List<BlockPos> before = solidCells(h);
		long t0 = h.getTick();
		Vec3 c = castHole(h, p);
		h.assertValueEqual(MagicTestKit.recorder(h).fx(p).getLast().data()[0], 6f, "radius 6 at level 7");
		// Halfway, it has eaten only what its sphere has reached so far.
		h.runAtTickTime(t0 + 50, () -> {
			int eaten = 0;
			double r = BlackHoleGeometry.radius(52, 6);
			for (BlockPos b : before) {
				if (!h.getBlockState(b).isAir()) continue;
				eaten++;
				double d = Vec3.atCenterOf(b).distanceTo(c);
				h.assertTrue(d <= r + 1.0E-6, "at tick 50 nothing past " + r + " is eaten: " + b + " at " + d);
			}
			h.assertTrue(eaten > 20, "it is eating by tick 50: " + eaten);
		});
		h.runAtTickTime(t0 + BlackHoleGeometry.LIFE + 3, () -> {
			// This arena only (other terrain tests fling debris in theirs).
			AABB arena = new AABB(h.absoluteVec(Vec3.ZERO), h.absoluteVec(new Vec3(MagicTestKit.SIZE_X, MagicTestKit.SIZE_Y, MagicTestKit.SIZE_Z)));
			int flying = h.getLevel().getEntitiesOfClass(FallingBlockEntity.class, arena).size();
			h.assertTrue(flying > 0 && flying <= 48, "the lip is flung out at the collapse: " + flying + " falling blocks");
		});
		h.runAtTickTime(t0 + BlackHoleGeometry.LIFE + 6, () -> {
			int removed = 0;
			double far = 0;
			for (BlockPos b : before) {
				if (!h.getBlockState(b).isAir()) continue;
				removed++;
				double d = Vec3.atCenterOf(b).distanceTo(c);
				far = Math.max(far, d);
				h.assertTrue(d <= 6.5, "removed beyond 6.5 from the centre: " + b + " at " + d);
			}
			h.assertTrue(removed >= 200, "blocks removed " + removed);
			h.assertTrue(far > 5.5, "it reached its cap: " + far);
			h.assertTrue(MagicTestKit.count(h, Blocks.BLACKSTONE) + MagicTestKit.count(h, Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS) > 0, "a scorched rim");
			// Scorch only in the bowl (below the centre) and over ground: none on the pillar it cuts through above, none afloat.
			for (BlockPos b : before) {
				var s = h.getBlockState(b);
				if (!s.is(Blocks.BLACKSTONE) && !s.is(Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS)) continue;
				h.assertTrue(b.getY() + 0.5 < c.y, "scorched above the centre: " + b);
				h.assertTrue(!h.getBlockState(b.below()).isAir(), "scorched block afloat: " + b);
			}
		});
		// Let the debris land in this arena before it is torn down.
		h.runAtTickTime(t0 + BlackHoleGeometry.LIFE + 50, h::succeed);
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 180)
	public void blackHoleSparesBlockEntities(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 7);
		MagicTestKit.recorder(h); // installed before the cast, so it sees the fx
		fence(h);
		long t0 = h.getTick();
		Vec3 c = castHole(h, p);
		// A furnace in the floor right under the centre (about 2.5 below it) and a chest beside it.
		BlockPos furnace = BlockPos.containing(c.x, MagicTestKit.FLOOR_TOP, c.z);
		h.setBlock(furnace, Blocks.FURNACE);
		MagicTestKit.chest(h, furnace.east());
		BlockPos stone = furnace.west();
		h.assertTrue(h.getBlockState(stone).is(Blocks.STONE), "stone beside it");
		h.runAtTickTime(t0 + BlackHoleGeometry.LIFE + 6, () -> {
			h.assertTrue(h.getBlockState(furnace).is(Blocks.FURNACE), "the furnace is still there");
			h.assertTrue(h.getBlockState(furnace.east()).is(Blocks.CHEST), "the chest is still there");
			h.assertTrue(h.getBlockState(stone).isAir(), "the stone beside them is eaten");
			h.assertTrue(h.getBlockState(furnace.below()).isAir(), "and under them");
		});
		h.runAtTickTime(t0 + BlackHoleGeometry.LIFE + 50, h::succeed);
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 180)
	public void blackHolePullsFoesAndItemsNotFriends(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 7);
		MagicTestKit.recorder(h); // installed before the cast, so it sees the fx
		fence(h);
		long t0 = h.getTick();
		Vec3 c = castHole(h, p);
		// All beyond the devoured sphere (6), within the pull (12).
		Zombie z = MagicTestKit.movableDummy(h, floor(c, -9, 0));
		ItemEntity item = MagicTestKit.item(h, floor(c, 0, 8));
		ServerPlayer other = MagicTestKit.otherPlayer(h, floor(c, 8, 0));
		Villager villager = MagicTestKit.villager(h, floor(c, -7, 4));
		Sheep sheep = still(h, EntityTypes.SHEEP, floor(c, 7, -4));
		double zStart = MagicTestKit.dist(z, c);
		double itemStart = MagicTestKit.dist(item, c);
		Vec3 otherAt = other.position();
		Vec3 villagerAt = villager.position();
		Vec3 sheepAt = sheep.position();
		h.runAtTickTime(t0 + 40, () -> {
			h.assertTrue(MagicTestKit.dist(z, c) < zStart - 2.5, "zombie dragged in: " + zStart + " -> " + MagicTestKit.dist(z, c));
			h.assertTrue(MagicTestKit.dist(item, c) < itemStart - 2.5, "item dragged in: " + itemStart + " -> " + MagicTestKit.dist(item, c));
			h.assertTrue(other.position().distanceTo(otherAt) < 1.0E-6, "player not moved: " + other.position());
			h.assertTrue(villager.position().distanceTo(villagerAt) < 1.0E-6, "villager not moved");
			h.assertTrue(sheep.position().subtract(sheepAt).horizontalDistance() < 0.1, "sheep not moved");
		});
		h.runAtTickTime(t0 + BlackHoleGeometry.LIFE - 5, () -> {
			h.assertTrue(MagicTestKit.dist(item, c) < 1.5, "the item reached the core: " + MagicTestKit.dist(item, c));
			h.assertValueEqual(other.getHealth(), other.getMaxHealth(), "player health");
			h.assertValueEqual(villager.getHealth(), villager.getMaxHealth(), "villager health");
		});
		// At the collapse what it pulled is thrown back out.
		h.runAtTickTime(t0 + BlackHoleGeometry.LIFE + 12, () -> {
			h.assertTrue(item.isRemoved() || MagicTestKit.dist(item, c) > 4, "the item is thrown out: " + MagicTestKit.dist(item, c));
		});
		h.runAtTickTime(t0 + BlackHoleGeometry.LIFE + 50, h::succeed);
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 180)
	public void blackHoleHurtsTheCoreAndBlastsAtTheEnd(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 7);
		MagicTestKit.recorder(h); // installed before the cast, so it sees the fx
		fence(h);
		long t0 = h.getTick();
		Vec3 c = castHole(h, p);
		// NoAI dummies stay where they are put: one in the core, one 4 out (blast only), one 9 out (neither).
		Zombie core = MagicTestKit.dummy(h, floor(c, 0, 0));
		core.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(100);
		core.setHealth(100);
		Zombie near = MagicTestKit.dummy(h, floor(c, 4, 0));
		Zombie far = MagicTestKit.dummy(h, floor(c, -9, 0));
		h.runAtTickTime(t0 + 25, () -> h.assertTrue(core.getHealth() <= 92 && core.getHealth() >= 91.9f, "two core hits by 25: " + core.getHealth()));
		h.runAtTickTime(t0 + BlackHoleGeometry.LIFE - 3, () -> {
			h.assertValueEqual(near.getHealth(), 20f, "4 out: nothing until the collapse");
			h.assertValueEqual(far.getHealth(), 20f, "9 out: untouched");
		});
		h.runAtTickTime(t0 + BlackHoleGeometry.LIFE + 3, () -> {
			// Level 7, ultimate: P = 2 × 1.3 = 2.6, so the blast deals 26.
			h.assertTrue(!near.isAlive(), "the blast kills the zombie 4 out: " + near.getHealth());
			h.assertValueEqual(far.getHealth(), 20f, "9 out: beyond the blast");
		});
		h.runAtTickTime(t0 + BlackHoleGeometry.LIFE + 50, h::succeed);
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = "office-test:magic_terrain_emitter", maxTicks = 160)
	public void blackHoleRespectsClaimFallback(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 7);
		MagicTestKit.recorder(h); // installed before the cast, so it sees the fx
		// An emitter about 20 from the centre: the fallback claim rule protects everything within 32 of it.
		BlockPos emitter = new BlockPos(2, 4, 0);
		MagicTestKit.emitter(h, emitter, p);
		long before = MagicTestKit.blockHash(h);
		long t0 = h.getTick();
		Vec3 c = castHole(h, p);
		double fromEmitter = Vec3.atBottomCenterOf(emitter).distanceTo(c);
		h.assertTrue(fromEmitter > 18 && fromEmitter < 22, "the emitter is about 20 from the centre: " + fromEmitter);
		Zombie z = MagicTestKit.dummy(h, floor(c, 0, 0));
		h.runAtTickTime(t0 + BlackHoleGeometry.LIFE + 6, () -> {
			h.assertValueEqual(MagicTestKit.blockHash(h), before, "no block changed");
			h.assertTrue(z.getHealth() < 20, "the zombie in the core is still hurt: " + z.getHealth());
			h.assertTrue(MagicTestKit.recorder(h).messageKeys(p).contains("magic.office.protected"), "told the land is protected");
			h.succeed();
		});
	}

	// ---- Task 23, fix round 1 ----

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 200)
	public void blackHoleUndoRestores(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 7);
		MagicTestKit.recorder(h);
		fence(h);
		long hash = MagicTestKit.blockHash(h);
		long t0 = h.getTick();
		castHole(h, p);
		// After the collapse and the debris' landings: one logged cast, and undoing it puts the arena back as it was.
		h.runAtTickTime(t0 + BlackHoleGeometry.LIFE + 60, () -> {
			long changed = MagicTestKit.blockHash(h);
			h.assertTrue(changed != hash, "positive control: the hole changed the arena");
			UndoReport r = MagicCommand.undo(h.getLevel().getServer(), p.getUUID(), 1);
			h.assertTrue(r.casts() == 1 && r.restored() >= 200, "undo restores the crater, the scorch and the debris: " + r);
			h.assertValueEqual(MagicTestKit.blockHash(h), hash, "the arena is as it was");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void shadowBindSparesBosses(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 5);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		// A warden: hostile, but a boss (ruling): hurt, never held. NoAI so it stays put and stays calm.
		Warden w = h.spawn(EntityTypes.WARDEN, new BlockPos(16, 4, 12));
		w.setNoAi(true);
		w.setPersistenceRequired();
		MagicTestKit.aim(p, new BlockPos(16, 5, 12));
		float before = w.getHealth();
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.SHADOW_BIND, 0f), Result.CAST, "result");
		FxEvent fx = rec.fx(p).getLast();
		h.assertValueEqual(fx.entities()[0], w.getId(), "the warden was the target");
		h.assertValueEqual(fx.data()[0], 0f, "not rooted");
		h.assertTrue(w.getHealth() < before, "the damage still lands: " + w.getHealth());
		h.assertTrue(!w.hasEffect(MobEffects.SLOWNESS), "no slowness on a boss");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 30)
	public void shadowStepLandsOnTheGroundUnderAnAirborneTarget(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 3);
		MagicTestKit.recorder(h);
		// A NoAI zombie hanging 4 above the floor (not on the ground).
		Zombie z = MagicTestKit.dummy(h, new BlockPos(16, 8, 12));
		h.assertTrue(!z.onGround(), "positive control: the target is airborne");
		MagicTestKit.aim(p, new BlockPos(16, 9, 12));
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.SHADOW_STEP, 0f), Result.CAST, "result");
		Vec3 at = p.position();
		h.assertTrue(Math.abs(h.relativeVec(at).y - (MagicTestKit.FLOOR_TOP + 1)) < 1.0E-6, "on the floor: " + h.relativeVec(at));
		h.assertTrue(at.z > z.getZ() + 0.5, "on the far side: " + h.relativeVec(at));
		h.assertTrue(new Vec3(at.x - z.getX(), 0, at.z - z.getZ()).length() <= 2, "under and behind it");
		assertStepped(h, p);
		h.succeed();
	}

	/** A Shadow Step that fizzled: nothing happened and nothing was spent (no mana, no cooldown, no fx). */
	private static void assertFizzledFree(GameTestHelper h, ServerPlayer p, Vec3 start, int mana) {
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertTrue(p.position().distanceTo(start) < 1.0E-6, "the caster stays put: " + h.relativeVec(p.position()));
		h.assertValueEqual(MagicAttachments.mana(p), mana, "no mana spent");
		h.assertFalse(MagicAttachments.get(p).readyAt().containsKey(SpellIds.SHADOW_STEP), "no cooldown");
		h.assertTrue(rec.fx(p).isEmpty(), "no fx");
		h.assertFalse(p.hasEffect(MobEffects.INVISIBILITY), "not made invisible");
		h.assertTrue(rec.messageKeys(p).contains(ShadowStep.UNSAFE_KEY), "told why: " + rec.messageKeys(p));
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 30)
	public void shadowStepNeverLandsOnLava(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 3);
		MagicTestKit.recorder(h);
		Zombie z = MagicTestKit.dummy(h, new BlockPos(16, 4, 12));
		// A lava pool right behind it, level with the floor: the spot behind it is over lava.
		MagicTestKit.fill(h, new BlockPos(14, 3, 13), new BlockPos(18, 3, 16), Blocks.LAVA);
		MagicTestKit.aim(p, new BlockPos(16, 5, 12));
		Vec3 start = p.position();
		int mana = MagicAttachments.mana(p);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.SHADOW_STEP, 0f), Result.FIZZLED, "result over lava");
		assertFizzledFree(h, p, start, mana);
		// Positive control: the same step over stone lands behind it, at once (the fizzle started no cooldown).
		MagicTestKit.fill(h, new BlockPos(14, 3, 13), new BlockPos(18, 3, 16), Blocks.STONE);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.SHADOW_STEP, 0f), Result.CAST, "result over stone");
		h.assertTrue(p.position().z > z.getZ() + 0.5, "lands behind it: " + h.relativeVec(p.position()));
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 30)
	public void shadowStepNeverLandsInDeepWater(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 3);
		MagicTestKit.recorder(h);
		// A pool three deep, and a NoAI zombie hanging over it: the ground snap under it finds deep water.
		MagicTestKit.fill(h, new BlockPos(13, 1, 9), new BlockPos(19, 3, 16), Blocks.WATER);
		Zombie z = MagicTestKit.dummy(h, new BlockPos(16, 8, 12));
		h.assertTrue(!z.onGround(), "positive control: the target is airborne");
		MagicTestKit.aim(p, new BlockPos(16, 9, 12));
		Vec3 start = p.position();
		int mana = MagicAttachments.mana(p);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.SHADOW_STEP, 0f), Result.FIZZLED, "result over deep water");
		assertFizzledFree(h, p, start, mana);
		// Positive control: one block of water over the floor is no danger: the step lands.
		MagicTestKit.fill(h, new BlockPos(13, 1, 9), new BlockPos(19, 2, 16), Blocks.STONE);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.SHADOW_STEP, 0f), Result.CAST, "result over shallow water");
		h.assertTrue(p.position().z > z.getZ() + 0.5, "lands behind it: " + h.relativeVec(p.position()));
		h.succeed();
	}
}
