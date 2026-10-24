package local.carter.office.magic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import local.carter.office.magic.cast.CastService.Result;
import local.carter.office.magic.cast.RecallSpell;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.item.MagicItems;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.ActiveEffects;
import local.carter.office.magic.spell.LingeringEffect;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.SpellRegistry;
import local.carter.office.magic.terrain.OpacClaims;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/**
 * Server GameTests for Recall (Task 7): Mark Home, the 3 s channel, the four interruptions, cross-dimension recall, level-1
 * availability and teleport safety (blocked, lava, void, unloaded chunk, mounts, leads).
 *
 * <p>These tests set homes, which the terrain fallback-claim rule reads, so they run in an environment of their own
 * ({@link #ENV}): they never share a batch with terrain tests.
 */
public class RecallGameTests {
	static final String ENV = "office-test:magic_recall";
	/** Where homes go in the arena: open floor, 24 blocks from {@link MagicTestKit#STAND}. */
	private static final BlockPos HOME = new BlockPos(16, 4, 28);

	/** Kit players are not ticked by the server, and a shield only blocks after its raise delay: age the use by hand. */
	private static void ageUse(ServerPlayer p, int ticks) {
		try {
			java.lang.reflect.Field f = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("useItemRemaining");
			f.setAccessible(true);
			f.setInt(p, f.getInt(p) - ticks);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static long now(GameTestHelper h) {
		return h.getLevel().getGameTime();
	}

	private static ServerPlayer caster(GameTestHelper h) {
		return MagicTestKit.caster(h, Element.WIND, 1);
	}

	private static Result recall(ServerPlayer p) {
		return MagicTestKit.cast(p, SpellIds.RECALL, 0f);
	}

	private static void setHome(GameTestHelper h, ServerPlayer p, BlockPos rel) {
		setHome(p, GlobalPos.of(h.getLevel().dimension(), h.absolutePos(rel)));
	}

	private static void setHome(ServerPlayer p, GlobalPos home) {
		MagicAttachments.set(p, MagicAttachments.get(p).withHome(Optional.of(home)));
	}

	private static Optional<GlobalPos> home(ServerPlayer p) {
		return MagicAttachments.get(p).home();
	}

	private static long cooldownLeft(GameTestHelper h, ServerPlayer p) {
		return MagicAttachments.get(p).cooldownLeft(SpellIds.RECALL, now(h));
	}

	private static void assertAt(GameTestHelper h, ServerPlayer p, Vec3 relPoint, String what) {
		double d = MagicTestKit.dist(p, relPoint);
		h.assertTrue(d < 0.01, what + ": " + d + " blocks from " + relPoint + ", at " + p.position());
	}

	private static void assertStayed(GameTestHelper h, ServerPlayer p, String what) {
		assertAt(h, p, Vec3.atBottomCenterOf(MagicTestKit.STAND), what);
	}

	private static Vec3 centre(BlockPos rel) {
		return Vec3.atBottomCenterOf(rel);
	}

	/** An overworld spot far from every test arena. */
	private static ServerLevel nether(GameTestHelper h) {
		return h.getLevel().getServer().getLevel(Level.NETHER);
	}

	/** A stone floor at {@code feet - 1} and air in the two cells above, in {@code level} (absolute). */
	private static void platform(ServerLevel level, BlockPos feet) {
		level.setBlockAndUpdate(feet.below(), Blocks.STONE.defaultBlockState());
		level.setBlockAndUpdate(feet, Blocks.AIR.defaultBlockState());
		level.setBlockAndUpdate(feet.above(), Blocks.AIR.defaultBlockState());
	}

	// ---- Mark Home ----

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV)
	public void shiftCastMarksHome(GameTestHelper h) {
		ServerPlayer p = caster(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		p.setShiftKeyDown(true);
		h.assertValueEqual(recall(p), Result.CAST, "result");
		h.assertValueEqual(home(p), Optional.of(GlobalPos.of(h.getLevel().dimension(), h.absolutePos(MagicTestKit.STAND))), "home");
		h.assertValueEqual(MagicAttachments.get(p).cooldownLeft(RecallSpell.MARK_KEY, now(h)), 20L, "mark cooldown");
		h.assertValueEqual(cooldownLeft(h, p), 0L, "Recall's own cooldown untouched");
		h.assertValueEqual(MagicAttachments.mana(p), 100, "mana");
		h.assertFalse(RecallSpell.channeling(p), "channeling");
		h.assertValueEqual(rec.messageKeys(), List.of("magic.office.home_marked"), "message");
		h.assertValueEqual(rec.fx(p).size(), 1, "fx sent");
		h.assertValueEqual(rec.fx(p).get(0).duration(), 0, "mark fx is a flash, not a channel");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 60)
	public void markHomeReplacesTheOldHome(GameTestHelper h) {
		ServerPlayer p = caster(h);
		setHome(h, p, HOME);
		p.setShiftKeyDown(true);
		h.assertValueEqual(recall(p), Result.CAST, "first mark");
		h.assertValueEqual(recall(p), Result.COOLDOWN, "a second mark inside 20 ticks");
		h.runAfterDelay(22, () -> {
			MagicTestKit.teleportTo(p, new BlockPos(8, 4, 8));
			h.assertValueEqual(recall(p), Result.CAST, "second mark");
			h.assertValueEqual(home(p), Optional.of(GlobalPos.of(h.getLevel().dimension(), h.absolutePos(new BlockPos(8, 4, 8)))), "the new home");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV)
	public void recallWithoutHomeFizzles(GameTestHelper h) {
		ServerPlayer p = caster(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(recall(p), Result.FIZZLED, "result");
		h.assertValueEqual(rec.messageKeys(), List.of("magic.office.no_home"), "message");
		h.assertValueEqual(cooldownLeft(h, p), 0L, "cooldown");
		h.assertValueEqual(rec.fx(p).size(), 0, "fx sent");
		h.assertFalse(RecallSpell.channeling(p), "channeling");
		h.succeed();
	}

	// ---- The channel ----

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void recallTeleportsAfterThreeSeconds(GameTestHelper h) {
		ServerPlayer p = caster(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		setHome(h, p, HOME);
		float yaw = p.getYRot();
		h.assertValueEqual(recall(p), Result.CAST, "result");
		h.assertTrue(RecallSpell.channeling(p), "channeling");
		h.assertValueEqual(rec.fx(p).size(), 1, "one fx");
		h.assertValueEqual(rec.fx(p).get(0).duration(), RecallSpell.CHANNEL_TICKS, "channel fx duration");
		h.assertValueEqual(MagicAttachments.mana(p), 100, "mana at the start");
		h.runAfterDelay(59, () -> {
			assertStayed(h, p, "still in place at tick 59");
			h.assertTrue(RecallSpell.channeling(p), "still channeling at tick 59");
			h.assertValueEqual(cooldownLeft(h, p), 0L, "no cooldown while channeling");
		});
		h.runAfterDelay(61, () -> {
			assertAt(h, p, centre(HOME), "at home");
			h.assertTrue(p.level() == h.getLevel(), "same level");
			h.assertValueEqual(p.getYRot(), yaw, "yaw kept");
			h.assertFalse(RecallSpell.channeling(p), "channel over");
			long left = cooldownLeft(h, p);
			h.assertTrue(left >= 1190 && left <= 1200, "cooldown " + left);
			h.assertValueEqual(MagicAttachments.mana(p), 100, "mana unchanged");
			h.assertValueEqual(rec.cancels().size(), 0, "nothing cancelled");
			h.assertValueEqual(rec.fx(p).size(), 1, "still one fx");
			h.assertValueEqual(recall(p), Result.COOLDOWN, "cooling down");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void recallStartsAtGlobalEffectCap(GameTestHelper h) {
		ServerPlayer p = caster(h);
		setHome(h, p, HOME);
		List<LingeringEffect> fillers = new ArrayList<>();
		// Other players fill the server-wide cap and it is emptied again within this one server tick.
		try {
			UUID other = UUID.randomUUID();
			while (ActiveEffects.count() < ActiveEffects.MAX) {
				if (ActiveEffects.count(other) >= ActiveEffects.MAX_PER_CASTER) other = UUID.randomUUID();
				LingeringEffect f = CastGameTests.idleEffect();
				h.assertTrue(ActiveEffects.add(h.getLevel(), other, f), "filler refused below the cap");
				fillers.add(f);
			}
			h.assertTrue(ActiveEffects.full(p.getUUID()), "a lingering spell would be refused");
			h.assertValueEqual(recall(p), Result.CAST, "Recall at the global cap");
			h.assertTrue(RecallSpell.channeling(p), "channeling");
			h.assertValueEqual(ActiveEffects.count(p.getUUID()), 1, "the channel counts toward its caster");
		} finally {
			for (LingeringEffect f : fillers) ActiveEffects.remove(f);
		}
		h.runAfterDelay(62, () -> {
			assertAt(h, p, centre(HOME), "at home");
			h.assertFalse(RecallSpell.channeling(p), "channel over");
			h.assertValueEqual(ActiveEffects.count(p.getUUID()), 0, "channel gone");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void noRecastDuringChannel(GameTestHelper h) {
		ServerPlayer p = caster(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		setHome(h, p, HOME);
		h.assertValueEqual(recall(p), Result.CAST, "first");
		h.runAfterDelay(10, () -> {
			h.assertValueEqual(recall(p), Result.BUSY, "second cast");
			p.setShiftKeyDown(true);
			h.assertValueEqual(recall(p), Result.BUSY, "Mark Home during the channel");
			p.setShiftKeyDown(false);
			h.assertValueEqual(home(p), Optional.of(GlobalPos.of(h.getLevel().dimension(), h.absolutePos(HOME))), "home untouched");
			h.assertValueEqual(rec.fx(p).size(), 1, "one channel only");
			h.assertValueEqual(rec.messageKeys().stream().filter("magic.office.busy"::equals).count(), 2L, "busy messages");
		});
		h.runAfterDelay(62, () -> {
			assertAt(h, p, centre(HOME), "at home");
			long left = cooldownLeft(h, p);
			h.assertTrue(left >= 1190 && left <= 1200, "one cooldown, not two: " + left);
			h.assertValueEqual(rec.fx(p).size(), 1, "still one fx");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void recallCancelledByDamage(GameTestHelper h) {
		ServerPlayer p = caster(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		setHome(h, p, HOME);
		h.assertValueEqual(recall(p), Result.CAST, "result");
		h.runAfterDelay(30, () -> {
			h.assertTrue(p.hurtServer(h.getLevel(), h.getLevel().damageSources().generic(), 1f), "the hit landed");
			h.assertFalse(RecallSpell.channeling(p), "channeling after damage");
			h.assertValueEqual(rec.cancels(), List.of(p.getId()), "one cancel");
			h.assertTrue(rec.messageKeys().contains("magic.office.recall_interrupted"), "interrupted message " + rec.messageKeys());
			h.assertValueEqual(cooldownLeft(h, p), 0L, "no cooldown");
		});
		h.runAfterDelay(70, () -> {
			assertStayed(h, p, "stayed");
			h.assertValueEqual(cooldownLeft(h, p), 0L, "still no cooldown");
			h.assertValueEqual(rec.cancels().size(), 1, "still one cancel");
			h.assertValueEqual(recall(p), Result.CAST, "Recall can be tried again at once");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void recallCancelledByDeath(GameTestHelper h) {
		ServerPlayer p = caster(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		setHome(h, p, HOME);
		h.assertValueEqual(recall(p), Result.CAST, "result");
		h.runAfterDelay(30, () -> {
			MagicTestKit.kill(p);
			h.assertFalse(p.isAlive(), "dead");
			h.assertFalse(RecallSpell.channeling(p), "channeling after death");
			h.assertValueEqual(rec.cancels(), List.of(p.getId()), "one cancel");
			h.assertValueEqual(cooldownLeft(h, p), 0L, "no cooldown");
		});
		h.runAfterDelay(70, () -> {
			assertStayed(h, p, "the dead player was not teleported");
			h.assertValueEqual(cooldownLeft(h, p), 0L, "still no cooldown");
			h.assertValueEqual(MagicAttachments.mana(p), 100, "mana");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void recallCancelledByDisconnect(GameTestHelper h) {
		ServerPlayer p = caster(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		setHome(h, p, HOME);
		h.assertValueEqual(recall(p), Result.CAST, "result");
		h.runAfterDelay(30, () -> {
			MagicTestKit.disconnect(p);
			h.assertFalse(RecallSpell.channeling(p), "channel removed");
			h.assertValueEqual(rec.cancels(), List.of(p.getId()), "one cancel");
		});
		h.runAfterDelay(70, () -> {
			assertStayed(h, p, "no teleport on a later tick");
			h.assertValueEqual(cooldownLeft(h, p), 0L, "no cooldown");
			h.assertFalse(RecallSpell.channeling(p), "still no channel");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void recallCancelledByDimensionChange(GameTestHelper h) {
		ServerPlayer p = caster(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		ServerLevel nether = nether(h);
		Vec3 there = new Vec3(300.5, 80, 300.5);
		setHome(h, p, HOME);
		h.assertValueEqual(recall(p), Result.CAST, "result");
		h.runAfterDelay(30, () -> {
			MagicTestKit.teleportTo(p, nether, there);
			h.assertTrue(p.level() == nether, "in the Nether");
			h.assertFalse(RecallSpell.channeling(p), "channeling after the dimension change");
			h.assertValueEqual(rec.cancels(), List.of(p.getId()), "one cancel");
			h.assertTrue(rec.messageKeys().contains("magic.office.recall_interrupted"), "interrupted message " + rec.messageKeys());
		});
		h.runAfterDelay(70, () -> {
			h.assertTrue(p.level() == nether, "still in the Nether, not carried home");
			h.assertTrue(Math.abs(p.getX() - there.x) < 0.01 && Math.abs(p.getZ() - there.z) < 0.01, "stayed at " + p.position());
			h.assertValueEqual(cooldownLeft(h, p), 0L, "no cooldown");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void recallCrossDimension(GameTestHelper h) {
		ServerPlayer p = caster(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		ServerLevel nether = nether(h);
		BlockPos feet = new BlockPos(200, 80, 200);
		platform(nether, feet);
		setHome(p, GlobalPos.of(Level.NETHER, feet));
		float yaw = p.getYRot();
		h.assertValueEqual(recall(p), Result.CAST, "result");
		h.runAfterDelay(59, () -> h.assertTrue(p.level() == h.getLevel(), "still in the overworld at tick 59"));
		h.runAfterDelay(61, () -> {
			h.assertTrue(p.level() == nether, "in the Nether");
			h.assertTrue(p.position().distanceTo(Vec3.atBottomCenterOf(feet)) < 0.01, "at home: " + p.position());
			h.assertValueEqual(p.getYRot(), yaw, "yaw kept");
			long left = MagicAttachments.get(p).cooldownLeft(SpellIds.RECALL, nether.getGameTime());
			h.assertTrue(left >= 1190 && left <= 1200, "cooldown " + left);
			h.assertValueEqual(rec.cancels().size(), 0, "arriving is not an interruption");
			h.assertFalse(rec.messageKeys().contains("magic.office.recall_interrupted"), "no interrupted message");
			h.assertValueEqual(MagicAttachments.mana(p), 100, "mana unchanged");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV)
	public void recallAvailableAtLevelOneForEveryElement(GameTestHelper h) {
		for (Element e : Element.values()) {
			MagicData d = MagicData.EMPTY.withElement(Optional.of(e)).withLevel(1);
			h.assertTrue(SpellRegistry.unlocked(d).stream().anyMatch(s -> s.id().equals(SpellIds.RECALL)), e + ": Recall unlocked at level 1");
			var wheel = SpellRegistry.wheel(e);
			h.assertTrue(wheel[5] != null && wheel[5].id().equals(SpellIds.RECALL), e + ": Recall on slice 5");
			h.assertTrue(wheel[5].element() == null && wheel[5].managesOwnCooldown(), e + ": every-element spell with its own cooldown");
			h.assertValueEqual(wheel[5].cost().mana(), 0, e + ": no mana");
			h.assertValueEqual(wheel[5].cost().cooldownTicks(), 1200, e + ": display cooldown");
		}
		h.succeed();
	}

	// ---- Teleport safety ----

	/** Fills the cube of radius {@code r} around {@code centre} with {@code b} (relative). */
	private static void fillAround(GameTestHelper h, BlockPos centre, int r, int dyDown, int dyUp, net.minecraft.world.level.block.Block b) {
		MagicTestKit.fill(h, centre.offset(-r, -dyDown, -r), centre.offset(r, dyUp, r), b);
	}

	/** Final review M4: a blocked start costs a short cooldown only, so taps can't keep loading the home chunk. */
	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void blockedHomeFizzlesAtTheStartWithAShortCooldown(GameTestHelper h) {
		ServerPlayer p = caster(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		setHome(h, p, HOME);
		fillAround(h, HOME, 3, 0, 3, Blocks.STONE); // solid stone around, above and on the home
		h.assertValueEqual(recall(p), Result.FIZZLED, "result");
		h.assertValueEqual(rec.messageKeys(), List.of("magic.office.home_blocked"), "message");
		h.assertFalse(RecallSpell.channeling(p), "no channel");
		h.assertValueEqual(cooldownLeft(h, p), (long) RecallSpell.BLOCKED_COOLDOWN_TICKS, "a 3 s cooldown, not Recall's 60 s");
		h.assertValueEqual(rec.fx(p).size(), 0, "no fx");
		h.assertTrue(home(p).isPresent(), "the home is kept");
		h.assertValueEqual(recall(p), Result.COOLDOWN, "a second tap at once");
		// Mark Home has its own key: never blocked by it.
		h.runAfterDelay(RecallSpell.BLOCKED_COOLDOWN_TICKS, () -> {
			h.assertValueEqual(cooldownLeft(h, p), 0L, "over after 3 s");
			// Positive control: cleared, the same home recalls.
			fillAround(h, HOME, 3, 0, 3, Blocks.AIR);
			h.setBlock(HOME.below(), Blocks.STONE);
			h.assertValueEqual(recall(p), Result.CAST, "cleared home");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void homeBlockedDuringTheChannelStaysPut(GameTestHelper h) {
		ServerPlayer p = caster(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		setHome(h, p, HOME);
		h.assertValueEqual(recall(p), Result.CAST, "result");
		h.runAfterDelay(20, () -> fillAround(h, HOME, 3, 0, 3, Blocks.STONE));
		h.runAfterDelay(62, () -> {
			assertStayed(h, p, "stayed");
			h.assertFalse(RecallSpell.channeling(p), "channel over");
			h.assertValueEqual(cooldownLeft(h, p), 0L, "no cooldown for a recall that did not happen");
			h.assertValueEqual(rec.cancels(), List.of(p.getId()), "the circle was cancelled");
			h.assertTrue(rec.messageKeys().contains("magic.office.home_blocked"), "message " + rec.messageKeys());
			h.assertTrue(home(p).isPresent(), "the home is kept");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void obstructedHomeCellUsesANeighbouringSafeOne(GameTestHelper h) {
		ServerPlayer p = caster(h);
		setHome(h, p, HOME);
		h.setBlock(HOME, Blocks.STONE); // the home cell itself is now a block: a neighbouring cell at the same height is the nearest safe one
		h.assertValueEqual(recall(p), Result.CAST, "result");
		h.runAfterDelay(62, () -> {
			double d = MagicTestKit.dist(p, centre(HOME));
			h.assertTrue(d > 0.5 && d < 1.01, "one block from the home, not inside it: " + p.position());
			h.assertTrue(h.getBlockState(p.blockPosition().subtract(h.absolutePos(BlockPos.ZERO))).isAir(), "standing in air");
			h.assertTrue(cooldownLeft(h, p) > 1000, "cooldown paid");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void lavaHomeIsNeverADestination(GameTestHelper h) {
		ServerPlayer p = caster(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		setHome(h, p, HOME);
		MagicTestKit.fill(h, HOME.offset(-3, 0, -3), HOME.offset(3, 0, 3), Blocks.LAVA); // a lava lake over the floor
		h.assertValueEqual(recall(p), Result.FIZZLED, "lava all around");
		h.assertValueEqual(rec.messageKeys(), List.of("magic.office.home_blocked"), "message");
		h.assertValueEqual(cooldownLeft(h, p), (long) RecallSpell.BLOCKED_COOLDOWN_TICKS, "only the short blocked cooldown");
		assertStayed(h, p, "stayed");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void lavaFloorAtHomeIsAvoided(GameTestHelper h) {
		ServerPlayer p = caster(h);
		setHome(h, p, HOME);
		h.setBlock(HOME.below(), Blocks.LAVA); // a lava source sunk into the floor: stone on its sides, so it cannot spread
		h.assertValueEqual(recall(p), Result.CAST, "result");
		h.runAfterDelay(62, () -> {
			BlockPos feet = p.blockPosition().subtract(h.absolutePos(BlockPos.ZERO));
			h.assertTrue(!feet.equals(HOME), "not above the lava: " + p.position());
			h.assertTrue(Math.abs(feet.getX() - HOME.getX()) <= RecallSpell.SEARCH_XZ && Math.abs(feet.getZ() - HOME.getZ()) <= RecallSpell.SEARCH_XZ
					&& Math.abs(feet.getY() - HOME.getY()) <= RecallSpell.SEARCH_Y, "near home: " + feet);
			h.assertFalse(h.getBlockState(feet.below()).is(Blocks.LAVA), "not above lava");
			h.assertTrue(cooldownLeft(h, p) > 1000, "cooldown paid");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void voidAndCliffHomesAreRefused(GameTestHelper h) {
		ServerPlayer p = caster(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		// Mid-air, well over a drop of three blocks: the arena floor is 8 blocks below.
		setHome(h, p, new BlockPos(16, 12, 28));
		h.assertValueEqual(recall(p), Result.FIZZLED, "mid-air home");
		MagicAttachments.set(p, MagicAttachments.get(p).withReadyAt(Map.of())); // the short blocked cooldown
		// Below the world: nothing there at all.
		ServerLevel level = h.getLevel();
		setHome(p, GlobalPos.of(level.dimension(), new BlockPos(h.absolutePos(HOME).getX(), level.getMinY() - 20, h.absolutePos(HOME).getZ())));
		h.assertValueEqual(recall(p), Result.FIZZLED, "home below the world");
		h.assertValueEqual(rec.messageKeys(), List.of("magic.office.home_blocked", "magic.office.home_blocked"), "messages");
		h.assertValueEqual(cooldownLeft(h, p), (long) RecallSpell.BLOCKED_COOLDOWN_TICKS, "only the short blocked cooldown");
		assertStayed(h, p, "stayed");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void missingHomeDimensionIsRefused(GameTestHelper h) {
		ServerPlayer p = caster(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		setHome(p, GlobalPos.of(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
				net.minecraft.resources.Identifier.fromNamespaceAndPath("office-test", "no_such_dimension")), BlockPos.ZERO));
		h.assertValueEqual(recall(p), Result.FIZZLED, "result");
		h.assertValueEqual(rec.messageKeys(), List.of("magic.office.home_blocked"), "message");
		h.assertTrue(home(p).isPresent(), "home kept");
		h.succeed();
	}

	/**
	 * Lets the chunk at {@code pos} go: forcing it and un-forcing it at once replaces the short-lived loading ticket the platform
	 * build left behind, so only a ticket someone else adds can keep it loaded.
	 */
	private static void release(ServerLevel level, BlockPos pos) {
		int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
		level.setChunkForced(cx, cz, true);
		level.setChunkForced(cx, cz, false);
	}

	/**
	 * The channel holds the home chunk with its own ticket: a far home whose chunk nothing else holds is still loaded at tick 50
	 * and the recall lands on it.
	 */
	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void recallChannelHoldsTheHomeChunkWithATicket(GameTestHelper h) {
		ServerPlayer p = caster(h);
		ServerLevel level = h.getLevel();
		BlockPos far = new BlockPos(40_008, 90, 40_008);
		platform(level, far);
		release(level, far);
		setHome(p, GlobalPos.of(level.dimension(), far));
		h.assertValueEqual(recall(p), Result.CAST, "the start check finds the platform");
		h.runAfterDelay(50, () -> h.assertTrue(level.hasChunkAt(far), "held by the channel's ticket"));
		h.runAfterDelay(62, () -> {
			h.assertTrue(p.level() == level, "same level");
			h.assertTrue(p.position().distanceTo(Vec3.atBottomCenterOf(far)) < 0.01, "at home: " + p.position());
			h.assertTrue(cooldownLeft(h, p) > 1000, "cooldown paid");
			h.succeed();
		});
	}

	/** {@code destination} loads a chunk that was never loaded (no waiting for an unload: this chunk never existed). */
	@GameTest(structure = MagicTestKit.ARENA, environment = ENV)
	public void destinationLoadsAnUnloadedChunk(GameTestHelper h) {
		ServerPlayer p = caster(h);
		ServerLevel level = h.getLevel();
		BlockPos far = new BlockPos(41_000 + 16 * (int) (h.getLevel().getGameTime() % 64), 90, -41_000); // never touched this run
		h.assertFalse(level.hasChunkAt(far), "never loaded");
		Vec3 at = RecallSpell.destination(p, GlobalPos.of(level.dimension(), far));
		h.assertTrue(level.hasChunkAt(far), "destination loaded it (found " + at + ")");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void carpetedHomeInATwoTallRoomLandsExactlyOnTheHome(GameTestHelper h) {
		ServerPlayer p = caster(h);
		setHome(h, p, HOME);
		h.setBlock(HOME, Blocks.MOSS_CARPET);
		MagicTestKit.fill(h, HOME.offset(-3, 0, -3), HOME.offset(3, 0, 3), Blocks.MOSS_CARPET);
		MagicTestKit.fill(h, HOME.offset(-3, 2, -3), HOME.offset(3, 2, 3), Blocks.STONE); // a ceiling two blocks over the floor cell
		h.assertValueEqual(recall(p), Result.CAST, "result");
		h.runAfterDelay(62, () -> {
			Vec3 expected = new Vec3(HOME.getX() + 0.5, HOME.getY() + 0.0625, HOME.getZ() + 0.5);
			assertAt(h, p, expected, "standing on the carpet in the home cell");
			h.assertTrue(cooldownLeft(h, p) > 1000, "cooldown paid");
			h.succeed();
		});
	}

	// ---- Mounts and leads ----

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void recallWhileRidingDismountsAndLeavesTheMount(GameTestHelper h) {
		ServerPlayer p = caster(h);
		setHome(h, p, HOME);
		Zombie mount = MagicTestKit.dummy(h, MagicTestKit.STAND.east(3));
		h.assertTrue(p.startRiding(mount, true, false), "riding");
		h.assertTrue(p.isPassenger(), "is a passenger");
		h.assertValueEqual(recall(p), Result.CAST, "result");
		h.runAfterDelay(62, () -> {
			assertAt(h, p, centre(HOME), "at home");
			h.assertFalse(p.isPassenger(), "dismounted");
			h.assertTrue(mount.isAlive() && !mount.isRemoved(), "the mount is untouched");
			h.assertTrue(mount.getPassengers().isEmpty(), "the mount has no rider");
			h.assertTrue(mount.position().distanceTo(p.position()) > 10, "the mount stayed behind");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void recallWithALeashedAnimalLosesNothing(GameTestHelper h) {
		ServerPlayer p = caster(h);
		setHome(h, p, HOME);
		Cow cow = h.spawn(EntityTypes.COW, MagicTestKit.STAND.east(2));
		cow.setNoAi(true);
		cow.setPersistenceRequired();
		cow.setLeashedTo(p, true);
		h.assertTrue(cow.isLeashed(), "leashed");
		h.assertValueEqual(recall(p), Result.CAST, "result");
		h.runAfterDelay(62, () -> assertAt(h, p, centre(HOME), "at home"));
		h.runAfterDelay(70, () -> {
			h.assertTrue(cow.isAlive() && !cow.isRemoved(), "the cow is fine");
			AABB around = new AABB(cow.blockPosition()).inflate(4);
			h.assertFalse(cow.isLeashed(), "the leash snapped: the home is 24 blocks away");
			h.assertFalse(h.getLevel().getEntitiesOfClass(ItemEntity.class, around, i -> i.getItem().is(Items.LEAD)).isEmpty(), "the lead dropped as an item");
			h.succeed();
		});
	}

	// ---- Mark Home vs Recall cooldown, regen, charge, shields, hand signs ----

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void markHomeRightAfterARecall(GameTestHelper h) {
		ServerPlayer p = caster(h);
		setHome(h, p, HOME);
		h.assertValueEqual(recall(p), Result.CAST, "recall");
		h.runAfterDelay(62, () -> {
			assertAt(h, p, centre(HOME), "at home");
			h.assertTrue(cooldownLeft(h, p) > 1000, "Recall is cooling down");
			h.assertValueEqual(recall(p), Result.COOLDOWN, "Recall itself is blocked");
			MagicTestKit.teleportTo(p, new BlockPos(8, 4, 8));
			p.setShiftKeyDown(true);
			h.assertValueEqual(recall(p), Result.CAST, "Mark Home is not blocked by Recall's cooldown");
			h.assertValueEqual(home(p), Optional.of(GlobalPos.of(h.getLevel().dimension(), h.absolutePos(new BlockPos(8, 4, 8)))), "the new home");
			h.assertTrue(cooldownLeft(h, p) > 1000, "Recall's cooldown is untouched");
			h.assertValueEqual(MagicAttachments.get(p).cooldownLeft(RecallSpell.MARK_KEY, now(h)), 20L, "mark cooldown");
			h.assertValueEqual(recall(p), Result.COOLDOWN, "a second mark inside 20 ticks");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 60)
	public void recallAndMarkHomeDoNotPauseManaRegen(GameTestHelper h) {
		ServerPlayer p = caster(h);
		MagicAttachments.setMana(p, 80);
		p.setShiftKeyDown(true);
		h.assertValueEqual(recall(p), Result.CAST, "mark");
		h.runAfterDelay(15, () -> {
			h.assertTrue(MagicAttachments.mana(p) > 80, "mana regenerated at once: " + MagicAttachments.mana(p));
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void noChargeStartsDuringTheChannel(GameTestHelper h) {
		ServerPlayer p = caster(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		setHome(h, p, HOME);
		MagicAttachments.set(p, MagicAttachments.get(p).withSelected(Optional.of(SpellIds.RECALL)));
		h.assertValueEqual(recall(p), Result.CAST, "recall");
		h.runAfterDelay(10, () -> {
			MagicItems.GRIMOIRE.use(h.getLevel(), p, InteractionHand.OFF_HAND);
			h.assertFalse(p.isUsingItem(), "no charge started");
			h.assertTrue(rec.messageKeys().contains("magic.office.busy"), "busy message " + rec.messageKeys());
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void recallCancelledByAShieldBlockedHit(GameTestHelper h) {
		ServerPlayer p = caster(h);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		setHome(h, p, HOME);
		Zombie attacker = MagicTestKit.dummy(h, MagicTestKit.STAND.offset(0, 0, 2));
		h.assertValueEqual(recall(p), Result.CAST, "result");
		h.runAfterDelay(5, () -> {
			p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SHIELD));
			p.startUsingItem(InteractionHand.MAIN_HAND);
			ageUse(p, 6);
		});
		h.runAfterDelay(25, () -> {
			float before = p.getHealth();
			h.assertTrue(p.isBlocking(), "the shield is up: using=" + p.isUsingItem() + " ticks=" + p.getTicksUsingItem() + " main=" + p.getMainHandItem());
			p.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(attacker), 4f);
			h.assertValueEqual(p.getHealth(), before, "the shield took the whole hit");
			h.assertFalse(RecallSpell.channeling(p), "channeling after a blocked hit");
			h.assertValueEqual(rec.cancels(), List.of(p.getId()), "one cancel");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 100)
	public void recallIsBusyWhileAHandSignChallengeIsOpen(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WIND, 7);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		Identifier ultimate = Identifier.fromNamespaceAndPath("office-test", "probe_recall_signs");
		MagicTestKit.override(h, MagicTestKit.probe("probe_recall_signs", Element.WIND, 4));
		setHome(h, p, HOME);
		h.assertValueEqual(MagicTestKit.cast(p, ultimate, 1f), Result.CHALLENGED, "ultimate opens a challenge");
		h.assertValueEqual(recall(p), Result.BUSY, "Recall during the challenge");
		h.assertTrue(rec.messageKeys().contains("magic.office.signs"), "message " + rec.messageKeys());
		h.assertFalse(RecallSpell.channeling(p), "no channel");
		h.assertValueEqual(cooldownLeft(h, p), 0L, "no cooldown");
		h.succeed();
	}

	// ---- Final review M11: no home inside another player's claim ----

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV, maxTicks = 40)
	public void markHomeRefusedInsideAnotherPlayersClaim(GameTestHelper h) {
		ServerPlayer p = caster(h);
		ServerPlayer owner = MagicTestKit.otherPlayer(h, new BlockPos(28, 4, 28));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		BlockPos at = p.blockPosition();
		// Open Parties and Claims says the owner claimed the caster's chunk. Asked for this test's caster only, and set and
		// cleared within this tick, so concurrent tests never see it.
		OpacClaims theirs = new OpacClaims((level, cx, cz) -> cx == at.getX() >> 4 && cz == at.getZ() >> 4 ? owner.getUUID() : null);
		OpacClaims mine = new OpacClaims((level, cx, cz) -> p.getUUID());
		try {
			RecallSpell.setHomeClaimsForTesting((caster, level, origin, r) -> caster == p ? theirs.forBatch(caster, level, origin, r) : pos -> false);
			p.setShiftKeyDown(true);
			h.assertValueEqual(recall(p), Result.FIZZLED, "Mark Home in someone else's claim");
			h.assertTrue(home(p).isEmpty(), "no home marked");
			h.assertValueEqual(rec.messageKeys(), List.of(RecallSpell.HOME_CLAIMED_KEY), "message");
			h.assertValueEqual(MagicAttachments.get(p).cooldownLeft(RecallSpell.MARK_KEY, now(h)), 0L, "no mark cooldown");
			// Positive control: in the caster's own claim the same press marks home.
			RecallSpell.setHomeClaimsForTesting((caster, level, origin, r) -> caster == p ? mine.forBatch(caster, level, origin, r) : pos -> false);
			h.assertValueEqual(recall(p), Result.CAST, "Mark Home in your own claim");
			h.assertValueEqual(home(p).map(GlobalPos::pos), Optional.of(at), "home marked");
		} finally {
			RecallSpell.setHomeClaimsForTesting(null);
		}
		h.succeed();
	}
}
