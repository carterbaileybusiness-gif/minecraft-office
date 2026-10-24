package local.carter.office.magic;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import local.carter.office.magic.cast.CastService;
import local.carter.office.magic.cast.CastService.Result;
import local.carter.office.magic.cast.ElementChoice;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.item.MagicItems;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.net.MagicServer;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.SpellRegistry;
import local.carter.office.magic.spell.TargetFilter;
import local.carter.office.magic.spell.fire.FireBolt;
import local.carter.office.magic.spell.lightning.ChainLightning;
import local.carter.office.magic.spell.lightning.ThunderSpear;
import local.carter.office.magic.spell.shadow.BlackHole;
import local.carter.office.magic.spell.shadow.GravityWell;
import local.carter.office.magic.spell.shadow.ShadowBolt;
import local.carter.office.magic.spell.water.BubbleShield;
import local.carter.office.magic.spell.water.WaterPrison;
import local.carter.office.magic.tree.RankCost;
import local.carter.office.magic.tree.SpellRank;
import local.carter.office.magic.tree.TreeService;
import local.carter.office.magic.tree.TreeService.Outcome;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/**
 * The spell skill tree, server side: buying a rank takes exactly its cost and nothing when it refuses (each reason), a Rebinding
 * Seal re-picks forks, ranks survive death and an element switch and back, the packets are throttled, and the forks really
 * change what a cast does (damage and mana per rank, Scatter, Lance, Soul Siphon, Frost Jet, Twin Prison). The review fixes:
 * Mirror never reflects onto a protected target, riders never control a boss, life steal heals what the hit took, no fork buff
 * outlasts half its cooldown, the re-pick window lives in the synced data; and each fork that replaced a weak pair has a
 * behaviour test against its sibling. The balance of every rank V fork pair is {@code BalanceGameTests.ranked<Spell>}.
 */
public class SkillTreeGameTests {
	private static void give(ServerPlayer p, Item item, int count) {
		p.getInventory().add(new ItemStack(item, count));
	}

	private static int count(ServerPlayer p, Item item) {
		int n = 0;
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (s.is(item)) n += s.getCount();
		}
		return n;
	}

	private static SpellRank rank(ServerPlayer p, Identifier spell) {
		return MagicAttachments.get(p).rank(spell);
	}

	private static void setRank(ServerPlayer p, SpellRank r) {
		MagicAttachments.set(p, MagicAttachments.get(p).withRank(r));
	}

	// ---- Buying ranks ----

	@GameTest(structure = MagicTestKit.ARENA)
	public void rankUpTakesExactlyTheCost(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 10);
		give(p, Items.BLAZE_POWDER, 6);
		give(p, Items.BLAZE_ROD, 2);
		h.assertValueEqual(TreeService.rankUp(p, SpellIds.FIRE_BOLT), Outcome.OK, "rank I");
		h.assertValueEqual(p.experienceLevel, MagicTestKit.XP_LEVELS - 2, "rank I takes 2 levels");
		h.assertValueEqual(count(p, Items.BLAZE_POWDER), 0, "and 6 blaze powder");
		h.assertValueEqual(count(p, Items.BLAZE_ROD), 2, "nothing else");
		h.assertValueEqual(rank(p, SpellIds.FIRE_BOLT).rank(), 1, "rank I stored");

		give(p, Items.MAGMA_CREAM, 4);
		h.assertValueEqual(TreeService.rankUp(p, SpellIds.FIRE_BOLT), Outcome.OK, "rank II");
		h.assertValueEqual(p.experienceLevel, MagicTestKit.XP_LEVELS - 5, "rank II takes 3 more levels");
		h.assertValueEqual(count(p, Items.MAGMA_CREAM), 0, "and 4 magma cream");

		// Rank III is a fork: a plain rank-up is the wrong request, and takes nothing.
		give(p, Items.BLAZE_ROD, 4);
		h.assertValueEqual(TreeService.rankUp(p, SpellIds.FIRE_BOLT), Outcome.WRONG_RANK, "III is a fork");
		h.assertValueEqual(count(p, Items.BLAZE_ROD), 6, "nothing taken");
		h.assertValueEqual(TreeService.chooseFork(p, SpellIds.FIRE_BOLT, 3, SpellRank.A), Outcome.OK, "III as Scatter");
		h.assertValueEqual(count(p, Items.BLAZE_ROD), 2, "4 blaze rods taken");
		h.assertValueEqual(p.experienceLevel, MagicTestKit.XP_LEVELS - 10, "and 5 levels");
		h.assertTrue(rank(p, SpellIds.FIRE_BOLT).is(FireBolt.SCATTER), "Scatter picked");

		// An ultimate costs half again: Meteor's rank I is 3 levels and 9 blaze powder.
		RankCost.Cost meteor = RankCost.of(Element.FIRE, true, 1);
		h.assertValueEqual(meteor.levels(), 3, "Meteor I levels");
		give(p, Items.BLAZE_POWDER, 9);
		h.assertValueEqual(TreeService.rankUp(p, SpellIds.METEOR), Outcome.OK, "Meteor I");
		h.assertValueEqual(count(p, Items.BLAZE_POWDER), 0, "9 blaze powder taken");
		h.assertValueEqual(p.experienceLevel, MagicTestKit.XP_LEVELS - 13, "3 levels taken");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void refusesWithEachReasonAndTakesNothing(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 1);
		give(p, Items.BLAZE_POWDER, 6);
		int xp = p.experienceLevel;
		// Not this element's spell, or no tree at all.
		h.assertValueEqual(TreeService.rankUp(p, SpellIds.WATER_BULLET), Outcome.NOT_YOURS, "another element's spell");
		h.assertValueEqual(TreeService.rankUp(p, SpellIds.RECALL), Outcome.NOT_YOURS, "Recall has no tree");
		// Fire Wall unlocks at grimoire level 2.
		h.assertValueEqual(TreeService.rankUp(p, SpellIds.FIRE_WALL), Outcome.LOCKED, "locked spell");
		// A fork choice where a bump is due, a fork rank that is not next, a bad option.
		h.assertValueEqual(TreeService.chooseFork(p, SpellIds.FIRE_BOLT, 5, 1), Outcome.WRONG_RANK, "V is not next");
		h.assertValueEqual(TreeService.chooseFork(p, SpellIds.FIRE_BOLT, 4, 1), Outcome.WRONG_RANK, "IV is no fork");
		h.assertValueEqual(TreeService.chooseFork(p, SpellIds.FIRE_BOLT, 3, 3), Outcome.WRONG_RANK, "no option 3");
		// Short of levels.
		p.setExperienceLevels(1);
		h.assertValueEqual(TreeService.rankUp(p, SpellIds.FIRE_BOLT), Outcome.NO_LEVELS, "1 of 2 levels");
		h.assertValueEqual(p.experienceLevel, 1, "no level taken");
		p.setExperienceLevels(xp);
		// Short of items: one short, and a renamed stack does not count.
		p.getInventory().clearContent();
		give(p, Items.BLAZE_POWDER, 5);
		h.assertValueEqual(TreeService.rankUp(p, SpellIds.FIRE_BOLT), Outcome.NO_ITEMS, "5 of 6 blaze powder");
		ItemStack named = new ItemStack(Items.BLAZE_POWDER, 1);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Keepsake"));
		p.getInventory().add(named);
		h.assertValueEqual(TreeService.rankUp(p, SpellIds.FIRE_BOLT), Outcome.NO_ITEMS, "a renamed stack is never eaten");
		h.assertValueEqual(count(p, Items.BLAZE_POWDER), 6, "no item taken");
		h.assertValueEqual(p.experienceLevel, xp, "no level taken");
		h.assertValueEqual(rank(p, SpellIds.FIRE_BOLT).rank(), 0, "no rank");
		// Maxed.
		setRank(p, new SpellRank(SpellIds.FIRE_BOLT, 5, 1, 1));
		h.assertValueEqual(TreeService.rankUp(p, SpellIds.FIRE_BOLT), Outcome.MAXED, "rank V is the top");
		h.assertValueEqual(TreeService.chooseFork(p, SpellIds.FIRE_BOLT, 5, 1), Outcome.SAME, "the same fork again");
		h.assertValueEqual(count(p, Items.BLAZE_POWDER), 6, "still nothing taken");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void creativePaysNothing(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 10);
		p.setGameMode(GameType.CREATIVE);
		h.assertValueEqual(TreeService.rankUp(p, SpellIds.STONE_BULLET), Outcome.OK, "free in creative");
		h.assertValueEqual(p.experienceLevel, MagicTestKit.XP_LEVELS, "no levels taken");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void rebindingSealRepicksForks(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 10);
		setRank(p, new SpellRank(SpellIds.SHADOW_BOLT, 3, SpellRank.A, 0));
		h.assertValueEqual(TreeService.chooseFork(p, SpellIds.SHADOW_BOLT, 3, SpellRank.B), Outcome.NO_SEAL, "no seal, no re-pick");
		give(p, MagicItems.REBINDING_SEAL, 1);
		give(p, Items.ECHO_SHARD, 5);
		h.assertValueEqual(TreeService.chooseFork(p, SpellIds.SHADOW_BOLT, 3, SpellRank.B), Outcome.OK, "re-picked with a seal");
		h.assertValueEqual(count(p, MagicItems.REBINDING_SEAL), 0, "one seal spent");
		h.assertValueEqual(count(p, Items.ECHO_SHARD), 5, "no materials taken or refunded");
		SpellRank r = rank(p, SpellIds.SHADOW_BOLT);
		h.assertValueEqual(r.rank(), 3, "the rank is kept");
		h.assertValueEqual(r.fork(3), SpellRank.B, "fork B now");
		h.assertTrue(TreeService.repickOpen(p), "the window is open");
		h.assertValueEqual(TreeService.chooseFork(p, SpellIds.SHADOW_BOLT, 3, SpellRank.A), Outcome.OK, "again within the window, free");
		h.assertValueEqual(rank(p, SpellIds.SHADOW_BOLT).fork(3), SpellRank.A, "back to A");
		h.assertValueEqual(TreeService.chooseFork(p, SpellIds.SHADOW_BOLT, 3, SpellRank.A), Outcome.SAME, "the same option");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void ranksSurviveDeathAndAnElementSwitch(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 10);
		setRank(p, new SpellRank(SpellIds.FIRE_BOLT, 4, SpellRank.B, 0));
		ServerPlayer back = MagicTestKit.respawn(h, p);
		h.assertValueEqual(rank(back, SpellIds.FIRE_BOLT), new SpellRank(SpellIds.FIRE_BOLT, 4, SpellRank.B, 0), "kept through death");
		// A Seal to Water: the fire ranks stay stored but no longer cast; and back to Fire: they cast again.
		back.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(MagicItems.REBINDING_SEAL, 2));
		ElementChoice.beginRebind(back);
		h.assertTrue(ElementChoice.rebind(back, Element.WATER), "rebound to water");
		MagicData d = MagicAttachments.get(back);
		h.assertValueEqual(d.rank(SpellIds.FIRE_BOLT).rank(), 4, "fire's ranks kept while water");
		h.assertValueEqual(CastService.rank(d, SpellRegistry.get(SpellIds.FIRE_BOLT).orElseThrow()).rank(), 0, "but not cast");
		ElementChoice.beginRebind(back);
		h.assertTrue(ElementChoice.rebind(back, Element.FIRE), "back to fire");
		d = MagicAttachments.get(back);
		h.assertValueEqual(CastService.rank(d, SpellRegistry.get(SpellIds.FIRE_BOLT).orElseThrow()).rank(), 4, "fire's rank IV restored");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void treePacketsAreThrottled(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WIND, 10);
		int allowed = 0;
		for (int i = 0; i < MagicServer.TREE_PER_SECOND + 5; i++) if (MagicServer.allowTree(p)) allowed++;
		h.assertValueEqual(allowed, MagicServer.TREE_PER_SECOND, "at most TREE_PER_SECOND a second");
		h.succeed();
	}

	// ---- Ranks and forks change the cast ----

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void ranksRaiseDamageAndLowerMana(GameTestHelper h) {
		ServerPlayer plain = MagicTestKit.caster(h, Element.EARTH, 1);
		ServerPlayer ranked = MagicTestKit.caster(h, Element.EARTH, 1);
		MagicTestKit.teleportTo(plain, new BlockPos(8, 4, 4));
		MagicTestKit.teleportTo(ranked, new BlockPos(24, 4, 4));
		setRank(ranked, new SpellRank(SpellIds.STONE_BULLET, 2, 0, 0));
		Zombie a = MagicTestKit.dummy(h, new BlockPos(8, 4, 9));
		Zombie b = MagicTestKit.dummy(h, new BlockPos(24, 4, 9));
		MagicTestKit.aim(plain, new BlockPos(8, 5, 9));
		MagicTestKit.aim(ranked, new BlockPos(24, 5, 9));
		int mana0 = MagicAttachments.mana(plain);
		int mana1 = MagicAttachments.mana(ranked);
		h.assertValueEqual(MagicTestKit.cast(plain, SpellIds.STONE_BULLET, 0f), Result.CAST, "plain");
		h.assertValueEqual(MagicTestKit.cast(ranked, SpellIds.STONE_BULLET, 0f), Result.CAST, "rank II");
		h.assertValueEqual(mana0 - MagicAttachments.mana(plain), 8, "a tap costs 8");
		h.assertValueEqual(mana1 - MagicAttachments.mana(ranked), 6, "rank II: 20 % less (8 × 0.8, rounded)");
		h.runAfterDelay(10, () -> {
			float lostA = 20 - a.getHealth();
			float lostB = 20 - b.getHealth();
			h.assertTrue(Math.abs(lostA - 5) < 0.01, "plain stone deals 5, dealt " + lostA);
			h.assertTrue(Math.abs(lostB - 6.5) < 0.01, "rank II deals 30 % more (6.5), dealt " + lostB);
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void scatterThrowsThreeBolts(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 1);
		setRank(p, new SpellRank(SpellIds.FIRE_BOLT, 3, SpellRank.A, 0));
		Zombie left = MagicTestKit.dummy(h, new BlockPos(17, 4, 12));
		Zombie mid = MagicTestKit.dummy(h, new BlockPos(16, 4, 12));
		Zombie right = MagicTestKit.dummy(h, new BlockPos(15, 4, 12));
		MagicTestKit.aim(p, new BlockPos(16, 5, 12));
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.FIRE_BOLT, 0f), Result.CAST, "cast");
		h.runAfterDelay(10, () -> {
			for (Zombie z : List.of(left, mid, right)) h.assertTrue(z.getHealth() < 20, "every zombie of the fan is struck");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void withoutScatterOnlyTheMiddleIsStruck(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 1);
		setRank(p, new SpellRank(SpellIds.FIRE_BOLT, 3, SpellRank.B, 0)); // Searing
		Zombie left = MagicTestKit.dummy(h, new BlockPos(17, 4, 12));
		Zombie mid = MagicTestKit.dummy(h, new BlockPos(16, 4, 12));
		Zombie right = MagicTestKit.dummy(h, new BlockPos(15, 4, 12));
		MagicTestKit.aim(p, new BlockPos(16, 5, 12));
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.FIRE_BOLT, 0f), Result.CAST, "cast");
		h.runAfterDelay(10, () -> {
			h.assertTrue(mid.getHealth() < 20, "the middle is struck");
			h.assertValueEqual(left.getHealth(), 20F, "control: no side bolt");
			h.assertValueEqual(right.getHealth(), 20F, "control: no side bolt");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void lancePiercesAndFlareDoesNot(GameTestHelper h) {
		ServerPlayer lance = MagicTestKit.caster(h, Element.FIRE, 1);
		ServerPlayer flare = MagicTestKit.caster(h, Element.FIRE, 1);
		MagicTestKit.teleportTo(lance, new BlockPos(8, 4, 4));
		MagicTestKit.teleportTo(flare, new BlockPos(24, 4, 4));
		setRank(lance, new SpellRank(SpellIds.FIRE_BOLT, 5, SpellRank.B, SpellRank.A));
		setRank(flare, new SpellRank(SpellIds.FIRE_BOLT, 5, SpellRank.B, SpellRank.B));
		Zombie l1 = MagicTestKit.dummy(h, new BlockPos(8, 4, 9));
		Zombie l2 = MagicTestKit.dummy(h, new BlockPos(8, 4, 14));
		Zombie f1 = MagicTestKit.dummy(h, new BlockPos(24, 4, 9));
		Zombie f2 = MagicTestKit.dummy(h, new BlockPos(24, 4, 14));
		MagicTestKit.aim(lance, new BlockPos(8, 5, 9));
		MagicTestKit.aim(flare, new BlockPos(24, 5, 9));
		h.assertValueEqual(MagicTestKit.cast(lance, SpellIds.FIRE_BOLT, 0f), Result.CAST, "lance");
		h.assertValueEqual(MagicTestKit.cast(flare, SpellIds.FIRE_BOLT, 0f), Result.CAST, "flare");
		h.runAfterDelay(12, () -> {
			h.assertTrue(l1.getHealth() < 20 && l2.getHealth() < 20, "Lance passes through the first and strikes the second");
			h.assertTrue(f1.getHealth() < 20, "Flare strikes the first");
			h.assertValueEqual(f2.getHealth(), 20F, "control: Flare stops at the first (its burst is 2.5 wide)");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void soulSiphonHealsTheCaster(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 1);
		setRank(p, new SpellRank(SpellIds.SHADOW_BOLT, 5, SpellRank.A, SpellRank.A));
		p.setHealth(10);
		// No natural regeneration in the window: only the siphon can heal.
		p.getFoodData().setFoodLevel(10);
		p.getFoodData().setSaturation(0);
		Zombie z = MagicTestKit.dummy(h, new BlockPos(16, 4, 9));
		MagicTestKit.aim(p, new BlockPos(16, 5, 9));
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.SHADOW_BOLT, 0f), Result.CAST, "cast");
		h.runAfterDelay(10, () -> {
			h.assertTrue(z.getHealth() < 20, "the bolt struck");
			h.assertTrue(p.getHealth() > 10, "the caster healed: " + p.getHealth());
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void frostJetSlows(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WATER, 1);
		setRank(p, new SpellRank(SpellIds.WATER_BULLET, 3, SpellRank.B, 0));
		Zombie z = MagicTestKit.dummy(h, new BlockPos(16, 4, 9));
		MagicTestKit.aim(p, new BlockPos(16, 5, 9));
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.WATER_BULLET, 0f), Result.CAST, "cast");
		h.runAfterDelay(10, () -> {
			h.assertTrue(z.getHealth() < 20, "struck");
			var slow = z.getEffect(MobEffects.SLOWNESS);
			h.assertTrue(slow != null && slow.getAmplifier() == 1, "Slowness II from the rider: " + slow);
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void twinPrisonHoldsTwo(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WATER, 10);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		setRank(p, new SpellRank(SpellIds.WATER_PRISON, 5, SpellRank.B, SpellRank.A));
		MagicTestKit.movableDummy(h, new BlockPos(16, 4, 10));
		MagicTestKit.movableDummy(h, new BlockPos(19, 4, 11));
		MagicTestKit.aim(p, new BlockPos(16, 5, 10));
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.WATER_PRISON, 0f), Result.CAST, "cast");
		FxEvent fx = rec.fx(p).getFirst();
		h.assertValueEqual(fx.entities().length, 2, "two prisoners");
		h.assertValueEqual(fx.data().length, 4, "the second sphere's centre rides in the fx");
		h.assertTrue(fx.spellRank().is(WaterPrison.TWIN), "the fx carries the rank");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void rankIsOnTheContextAndTheFx(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.LIGHTNING, 10);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		SpellRank r = new SpellRank(SpellIds.SPARK, 4, SpellRank.A, 0);
		setRank(p, r);
		MagicTestKit.aim(p, new BlockPos(16, 5, 12));
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.SPARK, 0f), Result.CAST, "cast");
		h.assertValueEqual(rec.fx(p).getFirst().rank(), r.packed(), "the fx carries the caster's rank");
		h.assertValueEqual(Optional.of(rec.fx(p).getFirst().spellRank()), Optional.of(r), "and unpacks to it");
		h.succeed();
	}

	// ---- Review fixes: targets, riders, buffs, the re-pick window ----

	/** A kit caster of {@code e} at level {@code level} moved to {@code (x, 4, 4)} with rank {@code r}. */
	private static ServerPlayer lane(GameTestHelper h, Element e, int level, int x, SpellRank r) {
		ServerPlayer p = MagicTestKit.caster(h, e, level);
		MagicTestKit.teleportTo(p, new BlockPos(x, 4, 4));
		setRank(p, r);
		return p;
	}

	/** An arrow from {@code shooter} at {@code rel} flying {@code velocity}. */
	private static Arrow arrowFrom(GameTestHelper h, Entity shooter, BlockPos rel, Vec3 velocity) {
		Arrow a = MagicTestKit.arrow(h, rel, velocity);
		a.setOwner(shooter);
		return a;
	}

	private static double flatDist(Entity a, Entity b) {
		double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
		return Math.sqrt(dx * dx + dz * dz);
	}

	/**
	 * Mirror never sends anything at a protected target: a friend's (a player's) arrow is only stopped, a named skeleton's arrow
	 * is only stopped, and a skeleton's arrow is returned as a hit on the skeleton, never on the villager standing in the way.
	 */
	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 60)
	public void mirrorNeverReflectsOntoProtectedTargets(GameTestHelper h) {
		SpellRank mirror = new SpellRank(SpellIds.BUBBLE_SHIELD, 3, SpellRank.A, 0);
		ServerPlayer a = lane(h, Element.WATER, 3, 6, mirror);
		ServerPlayer b = lane(h, Element.WATER, 3, 16, mirror);
		ServerPlayer c = lane(h, Element.WATER, 3, 26, mirror);
		ServerPlayer friend = MagicTestKit.otherPlayer(h, new BlockPos(6, 4, 14));
		var skeleton = h.spawn(EntityTypes.SKELETON, new BlockPos(16, 4, 18));
		skeleton.setNoAi(true);
		skeleton.setPersistenceRequired();
		skeleton.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
		Villager villager = MagicTestKit.villager(h, new BlockPos(16, 4, 13));
		var named = h.spawn(EntityTypes.SKELETON, new BlockPos(26, 4, 18));
		named.setNoAi(true);
		named.setPersistenceRequired();
		named.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
		named.setCustomName(Component.literal("Bones"));
		for (ServerPlayer p : List.of(a, b, c)) h.assertValueEqual(MagicTestKit.cast(p, SpellIds.BUBBLE_SHIELD, 0f), Result.CAST, "shield up");
		float friendHp = friend.getHealth(), villagerHp = villager.getHealth(), skeletonHp = skeleton.getHealth(), namedHp = named.getHealth();
		Vec3 in = new Vec3(0, 0, -1.5);
		Arrow fromFriend = arrowFrom(h, friend, new BlockPos(6, 5, 10), in);
		Arrow fromSkeleton = arrowFrom(h, skeleton, new BlockPos(16, 5, 10), in);
		Arrow fromNamed = arrowFrom(h, named, new BlockPos(26, 5, 10), in);
		h.runAfterDelay(20, () -> {
			for (Arrow arrow : List.of(fromFriend, fromSkeleton, fromNamed)) h.assertTrue(arrow.isRemoved(), "every arrow is stopped, never sent back live");
			h.assertValueEqual(friend.getHealth(), friendHp, "a friend's arrow is never sent back at the friend");
			h.assertValueEqual(villager.getHealth(), villagerHp, "the villager in the way is untouched");
			h.assertValueEqual(named.getHealth(), namedHp, "a named skeleton is protected: its arrow is only stopped");
			h.assertTrue(skeleton.getHealth() < skeletonHp, "positive control: the skeleton's arrow comes back at it " + skeleton.getHealth());
			for (ServerPlayer p : List.of(a, b, c)) h.assertValueEqual(p.getHealth(), p.getMaxHealth(), "the casters are unhurt");
			h.succeed();
		});
	}

	/** Riders put control effects only on what a control effect may take: Petrify hurts a boss but never slows or weakens it. */
	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void ridersNeverControlABoss(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 3);
		setRank(p, new SpellRank(SpellIds.EARTH_SPIKES, 5, SpellRank.A, SpellRank.B)); // Impale, Petrify
		var wither = h.spawn(EntityTypes.WITHER, new BlockPos(16, 4, 9));
		wither.setNoAi(true);
		wither.setPersistenceRequired();
		Zombie z = MagicTestKit.dummy(h, new BlockPos(16, 4, 7));
		float hp = wither.getHealth();
		h.assertTrue(TargetFilter.isBoss(wither), "a boss");
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.EARTH_SPIKES, 0f), Result.CAST, "result");
		h.runAfterDelay(20, () -> {
			h.assertTrue(wither.getHealth() < hp, "the boss takes the damage " + wither.getHealth());
			h.assertFalse(wither.hasEffect(MobEffects.SLOWNESS), "but never Petrify's slowness");
			h.assertFalse(wither.hasEffect(MobEffects.WEAKNESS), "nor its weakness");
			var slow = z.getEffect(MobEffects.SLOWNESS);
			h.assertTrue(slow != null && slow.getAmplifier() == 5, "positive control: the zombie is petrified " + slow);
			h.succeed();
		});
	}

	/** Life steal heals a share of what the hit really took: a 1-hp zombie gives back a share of 1, not of the bolt's damage. */
	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void lifeStealHealsWhatTheHitTook(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 10);
		setRank(p, new SpellRank(SpellIds.SHADOW_BOLT, 5, SpellRank.A, SpellRank.A)); // Siphon
		p.setHealth(10);
		p.getFoodData().setFoodLevel(10);
		p.getFoodData().setSaturation(0);
		Zombie z = MagicTestKit.dummy(h, new BlockPos(16, 4, 9));
		z.setHealth(1);
		MagicTestKit.aim(p, new BlockPos(16, 5, 9));
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.SHADOW_BOLT, 1f), Result.CAST, "cast");
		h.runAfterDelay(10, () -> {
			h.assertTrue(z.isDeadOrDying(), "the bolt killed it");
			float healed = p.getHealth() - 10;
			h.assertTrue(healed > 0 && healed <= ShadowBolt.SIPHON_SHARE + 1.0E-3, "healed a share of 1 hp, not of the bolt: " + healed);
			h.succeed();
		});
	}

	/** No fork's self-buff outlasts half its spell's cooldown (Carter's ruling), and the reworked buffs keep their behaviour. */
	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 100)
	public void forkBuffsStayUnderHalfTheCooldown(GameTestHelper h) {
		// Blink: Quick Charge (cooldown 1 s) + Surge: carried on along the look, Speed II for at most 0.5 s.
		ServerPlayer blink = lane(h, Element.LIGHTNING, 10, 6, new SpellRank(SpellIds.BLINK, 5, SpellRank.B, SpellRank.B));
		MagicTestKit.aim(blink, new BlockPos(6, 5, 20));
		h.assertValueEqual(MagicTestKit.cast(blink, SpellIds.BLINK, 0f), Result.CAST, "blink");
		MobEffectInstance speed = blink.getEffect(MobEffects.SPEED);
		h.assertTrue(speed != null && speed.getDuration() <= 10, "Surge with Quick Charge: Speed II at most half of 20 ticks: " + speed);
		h.assertFalse(blink.hasEffect(MobEffects.HASTE), "no Haste (a mining buff) any more");
		h.assertTrue(blink.getDeltaMovement().horizontalDistance() > 1.0, "Surge carries the caster on " + blink.getDeltaMovement());
		// Flame Dash Phoenix: put out, then 1 s at most.
		ServerPlayer dash = lane(h, Element.FIRE, 10, 16, new SpellRank(SpellIds.FLAME_DASH, 3, SpellRank.B, 0));
		dash.igniteForTicks(100);
		h.assertValueEqual(MagicTestKit.cast(dash, SpellIds.FLAME_DASH, 0f), Result.CAST, "dash");
		MobEffectInstance fire = dash.getEffect(MobEffects.FIRE_RESISTANCE);
		h.assertTrue(fire != null && fire.getDuration() <= 20, "Phoenix: at most half of 40 ticks: " + fire);
		h.assertFalse(dash.isOnFire(), "Phoenix puts the caster out");
		// Bubble Shield: Mending and Fortress add no status effect (Resistance I stays the bubble's own).
		ServerPlayer bubble = lane(h, Element.WATER, 10, 26, new SpellRank(SpellIds.BUBBLE_SHIELD, 5, SpellRank.B, SpellRank.A));
		h.assertValueEqual(MagicTestKit.cast(bubble, SpellIds.BUBBLE_SHIELD, 1f), Result.CAST, "bubble");
		h.assertFalse(bubble.hasEffect(MobEffects.REGENERATION), "Mending gives no Regeneration");
		MobEffectInstance res = bubble.getEffect(MobEffects.RESISTANCE);
		h.assertTrue(res != null && res.getAmplifier() == 0, "Fortress gives no Resistance II: " + res);
		// Shadow Step Phantom: Speed II and Strength I for at most 1 s.
		ServerPlayer step = lane(h, Element.SHADOW, 10, 11, new SpellRank(SpellIds.SHADOW_STEP, 5, SpellRank.A, SpellRank.B));
		MagicTestKit.dummy(h, new BlockPos(11, 4, 12));
		MagicTestKit.aim(step, new BlockPos(11, 5, 12));
		h.assertValueEqual(MagicTestKit.cast(step, SpellIds.SHADOW_STEP, 0f), Result.CAST, "step");
		MobEffectInstance strength = step.getEffect(MobEffects.STRENGTH);
		h.assertTrue(strength != null && strength.getDuration() <= 20, "Phantom: at most half of 40 ticks: " + strength);
		h.succeed();
	}

	/** Earth Wall's Bastion: Resistance II beside the wall, but at most half the 3 s cooldown per wall, not for the wall's life. */
	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 120)
	public void bastionGuardsOnlyHalfTheCooldown(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 10);
		setRank(p, new SpellRank(SpellIds.EARTH_WALL, 5, SpellRank.A, SpellRank.A)); // Thorns, Bastion
		MagicTestKit.aim(p, new BlockPos(16, 5, 10));
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.EARTH_WALL, 0f), Result.CAST, "wall");
		// Every time Resistance II is (re)applied, the ticks it grants (a kit player's effects may not tick down, so the
		// grants are summed, not the ticks it is seen).
		int[] guarded = {0, -1};
		h.onEachTick(() -> {
			MobEffectInstance r = p.getEffect(MobEffects.RESISTANCE);
			int d = r != null && r.getAmplifier() == 1 ? r.getDuration() : -1;
			if (d > guarded[1]) guarded[0] += d;
			guarded[1] = d;
		});
		h.runAfterDelay(100, () -> {
			h.assertTrue(guarded[0] > 0, "positive control: Bastion guards the caster beside it");
			h.assertTrue(guarded[0] <= 31, "at most 30 ticks per wall (half its 60-tick cooldown), was " + guarded[0]);
			h.succeed();
		});
	}

	/** The re-pick window's end lives in the synced player data (the client reads it when the book opens again). */
	@GameTest(structure = MagicTestKit.ARENA)
	public void repickWindowIsInTheSyncedData(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 10);
		setRank(p, new SpellRank(SpellIds.SHADOW_BOLT, 3, SpellRank.A, 0));
		h.assertValueEqual(MagicAttachments.get(p).repickUntil(), 0L, "no window before a Seal");
		give(p, MagicItems.REBINDING_SEAL, 1);
		long now = p.level().getGameTime();
		h.assertValueEqual(TreeService.chooseFork(p, SpellIds.SHADOW_BOLT, 3, SpellRank.B), Outcome.OK, "re-picked with a seal");
		long until = MagicAttachments.get(p).repickUntil();
		h.assertValueEqual(until, now + TreeService.REPICK_TICKS, "the window's end is stored with the player");
		h.assertTrue(MagicAttachments.get(p).repickOpen(now + TreeService.REPICK_TICKS - 1), "open until then");
		h.assertFalse(MagicAttachments.get(p).repickOpen(now + TreeService.REPICK_TICKS), "closed after");
		// It survives a respawn like the rest of the data, and the stream codec carries it to the client.
		ServerPlayer back = MagicTestKit.respawn(h, p);
		h.assertValueEqual(MagicAttachments.get(back).repickUntil(), until, "kept through death");
		h.succeed();
	}

	// ---- The new forks: each one's behaviour, against its sibling ----

	/** Gust III Vacuum pulls toward the caster; Updraft (its sibling) throws away. */
	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void vacuumPullsTowardTheCaster(GameTestHelper h) {
		ServerPlayer vac = lane(h, Element.WIND, 3, 8, new SpellRank(SpellIds.GUST, 3, SpellRank.A, 0));
		ServerPlayer up = lane(h, Element.WIND, 3, 24, new SpellRank(SpellIds.GUST, 3, SpellRank.B, 0));
		Zombie a = MagicTestKit.movableDummy(h, new BlockPos(8, 4, 9));
		Zombie b = MagicTestKit.movableDummy(h, new BlockPos(24, 4, 9));
		ItemEntity loot = MagicTestKit.item(h, new BlockPos(9, 4, 9));
		MagicTestKit.aim(vac, new BlockPos(8, 5, 9));
		MagicTestKit.aim(up, new BlockPos(24, 5, 9));
		double a0 = flatDist(a, vac), b0 = flatDist(b, up), l0 = flatDist(loot, vac);
		h.assertValueEqual(MagicTestKit.cast(vac, SpellIds.GUST, 0f), Result.CAST, "vacuum");
		h.assertValueEqual(MagicTestKit.cast(up, SpellIds.GUST, 0f), Result.CAST, "updraft");
		h.runAfterDelay(15, () -> {
			h.assertTrue(flatDist(a, vac) < a0 - 1, "Vacuum pulls the zombie in: " + a0 + " -> " + flatDist(a, vac));
			h.assertTrue(flatDist(loot, vac) < l0 - 1, "and the loot: " + l0 + " -> " + flatDist(loot, vac));
			h.assertTrue(flatDist(b, up) > b0 + 1, "control: Updraft throws it away: " + b0 + " -> " + flatDist(b, up));
			h.succeed();
		});
	}

	/** Gust V Wind Wall swats an arrow out of the air ahead of the caster; Cyclone (its sibling) does not. */
	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 50)
	public void windWallSwatsArrows(GameTestHelper h) {
		ServerPlayer wall = lane(h, Element.WIND, 10, 8, new SpellRank(SpellIds.GUST, 5, SpellRank.B, SpellRank.B));
		ServerPlayer cyc = lane(h, Element.WIND, 10, 24, new SpellRank(SpellIds.GUST, 5, SpellRank.B, SpellRank.A));
		MagicTestKit.aim(wall, new BlockPos(8, 5, 12));
		MagicTestKit.aim(cyc, new BlockPos(24, 5, 12));
		h.assertValueEqual(MagicTestKit.cast(wall, SpellIds.GUST, 0f), Result.CAST, "wind wall");
		h.assertValueEqual(MagicTestKit.cast(cyc, SpellIds.GUST, 0f), Result.CAST, "cyclone");
		h.runAfterDelay(10, () -> {
			Arrow atWall = MagicTestKit.arrow(h, new BlockPos(8, 5, 14), new Vec3(0, 0, -1.5));
			Arrow atCyc = MagicTestKit.arrow(h, new BlockPos(24, 5, 14), new Vec3(0, 0, -1.5));
			h.runAfterDelay(12, () -> {
				h.assertTrue(atWall.isRemoved(), "the wind wall swats the arrow");
				h.assertValueEqual(wall.getHealth(), wall.getMaxHealth(), "the caster behind it is unhurt");
				h.assertTrue(atCyc.isRemoved() || cyc.getHealth() < cyc.getMaxHealth(), "control: the arrow reaches the Cyclone caster");
				h.assertTrue(cyc.getHealth() < cyc.getMaxHealth(), "control: the Cyclone caster is hit " + cyc.getHealth());
				h.succeed();
			});
		});
	}

	/** Tornado III Debris hurls debris at a creature outside the funnel; Long Storm (its sibling) does not reach it. */
	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 80)
	public void debrisHitsOutsideTheFunnel(GameTestHelper h) {
		ServerPlayer deb = lane(h, Element.WIND, 10, 6, new SpellRank(SpellIds.TORNADO, 3, SpellRank.B, 0));
		ServerPlayer storm = lane(h, Element.WIND, 10, 26, new SpellRank(SpellIds.TORNADO, 3, SpellRank.A, 0));
		MagicTestKit.aim(deb, new BlockPos(6, 3, 12));
		MagicTestKit.aim(storm, new BlockPos(26, 3, 12));
		Zombie a = MagicTestKit.dummy(h, new BlockPos(12, 4, 13));
		Zombie b = MagicTestKit.dummy(h, new BlockPos(20, 4, 13));
		h.assertValueEqual(MagicTestKit.cast(deb, SpellIds.TORNADO, 0f), Result.CAST, "debris");
		h.assertValueEqual(MagicTestKit.cast(storm, SpellIds.TORNADO, 0f), Result.CAST, "long storm");
		h.runAfterDelay(45, () -> {
			h.assertTrue(a.getHealth() < 20, "Debris hits the zombie outside the funnel " + a.getHealth());
			h.assertValueEqual(b.getHealth(), 20F, "control: Long Storm's funnel never reaches it");
			h.succeed();
		});
	}

	/** Blade Wind III Boomerang strikes on the way out and again on the way back: twice Wide Arc's (its sibling's) one hit. */
	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 60)
	public void boomerangStrikesTwice(GameTestHelper h) {
		ServerPlayer boom = lane(h, Element.WIND, 10, 8, new SpellRank(SpellIds.BLADE_WIND, 3, SpellRank.B, 0));
		ServerPlayer wide = lane(h, Element.WIND, 10, 24, new SpellRank(SpellIds.BLADE_WIND, 3, SpellRank.A, 0));
		Zombie a = MagicTestKit.dummy(h, new BlockPos(8, 4, 10));
		Zombie b = MagicTestKit.dummy(h, new BlockPos(24, 4, 10));
		for (Zombie z : List.of(a, b)) {
			z.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
			z.setHealth(100);
		}
		MagicTestKit.aim(boom, new BlockPos(8, 5, 10));
		MagicTestKit.aim(wide, new BlockPos(24, 5, 10));
		h.assertValueEqual(MagicTestKit.cast(boom, SpellIds.BLADE_WIND, 0f), Result.CAST, "boomerang");
		h.assertValueEqual(MagicTestKit.cast(wide, SpellIds.BLADE_WIND, 0f), Result.CAST, "wide arc");
		h.runAfterDelay(40, () -> {
			float lostA = 100 - a.getHealth(), lostB = 100 - b.getHealth();
			h.assertTrue(lostB > 0, "control: Wide Arc strikes once " + lostB);
			h.assertTrue(Math.abs(lostA - 2 * lostB) < 0.05, "Boomerang strikes twice: " + lostA + " vs " + lostB);
			h.succeed();
		});
	}

	/** Fireball Barrage V Guided Shells puts fireballs on the creatures near the aim; Heavy Shells (its sibling) aims by the seed only. */
	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 80)
	public void guidedShellsFallOnFoes(GameTestHelper h) {
		ServerPlayer guided = lane(h, Element.FIRE, 10, 8, new SpellRank(SpellIds.FIREBALL_BARRAGE, 5, SpellRank.B, SpellRank.A));
		ServerPlayer heavy = lane(h, Element.FIRE, 10, 24, new SpellRank(SpellIds.FIREBALL_BARRAGE, 5, SpellRank.B, SpellRank.B));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		Zombie a1 = MagicTestKit.dummy(h, new BlockPos(4, 4, 14));
		Zombie a2 = MagicTestKit.dummy(h, new BlockPos(12, 4, 14));
		Zombie b1 = MagicTestKit.dummy(h, new BlockPos(18, 4, 14));
		MagicTestKit.aim(guided, new BlockPos(8, 3, 14));
		MagicTestKit.aim(heavy, new BlockPos(24, 3, 14));
		h.assertValueEqual(MagicTestKit.cast(guided, SpellIds.FIREBALL_BARRAGE, 0f), Result.CAST, "guided");
		h.assertValueEqual(MagicTestKit.cast(heavy, SpellIds.FIREBALL_BARRAGE, 0f), Result.CAST, "heavy");
		List<Integer> named = new ArrayList<>();
		for (int id : rec.fx(guided).getFirst().entities()) named.add(id);
		h.assertTrue(named.contains(a1.getId()) && named.contains(a2.getId()), "a fireball for each zombie near the aim: " + named);
		h.assertValueEqual(rec.fx(heavy).getFirst().entities().length, 0, "control: Heavy Shells guides none");
		h.runAfterDelay(60, () -> {
			h.assertTrue(a1.getHealth() < 20 && a2.getHealth() < 20, "both guided-on zombies are struck " + a1.getHealth() + ", " + a2.getHealth());
			h.assertValueEqual(b1.getHealth(), 20F, "control: a zombie 6 off Heavy Shells' aim is missed");
			h.succeed();
		});
	}

	/** Water Prison V Hurl flings the prisoner where the caster looks when the prison bursts; Twin Prison (its sibling) drops it. */
	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 160)
	public void hurlFlingsThePrisoner(GameTestHelper h) {
		ServerPlayer hurl = lane(h, Element.WATER, 10, 8, new SpellRank(SpellIds.WATER_PRISON, 5, SpellRank.A, SpellRank.B));
		ServerPlayer twin = lane(h, Element.WATER, 10, 24, new SpellRank(SpellIds.WATER_PRISON, 5, SpellRank.A, SpellRank.A));
		Zombie a = MagicTestKit.movableDummy(h, new BlockPos(8, 4, 9));
		Zombie b = MagicTestKit.movableDummy(h, new BlockPos(24, 4, 9));
		for (Zombie z : List.of(a, b)) {
			z.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
			z.setHealth(100);
		}
		MagicTestKit.aim(hurl, new BlockPos(8, 5, 9));
		MagicTestKit.aim(twin, new BlockPos(24, 5, 9));
		h.assertValueEqual(MagicTestKit.cast(hurl, SpellIds.WATER_PRISON, 0f), Result.CAST, "hurl");
		h.assertValueEqual(MagicTestKit.cast(twin, SpellIds.WATER_PRISON, 0f), Result.CAST, "twin");
		h.runAfterDelay(140, () -> {
			h.assertTrue(flatDist(a, hurl) > 9, "Hurl flings the prisoner away along the look " + flatDist(a, hurl));
			h.assertTrue(flatDist(b, twin) < 7, "control: Twin Prison's prisoner drops where it was held " + flatDist(b, twin));
			h.succeed();
		});
	}

	/** Chain Lightning III Conductor: a wet first target takes twice what a dry one does. */
	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void conductorDoublesOnWetFoes(GameTestHelper h) {
		SpellRank cond = new SpellRank(SpellIds.CHAIN_LIGHTNING, 3, SpellRank.B, 0);
		ServerPlayer wet = lane(h, Element.LIGHTNING, 10, 6, cond);
		ServerPlayer dry = lane(h, Element.LIGHTNING, 10, 26, cond);
		h.setBlock(new BlockPos(6, 4, 10), Blocks.WATER);
		Zombie a = MagicTestKit.dummy(h, new BlockPos(6, 4, 10));
		Zombie b = MagicTestKit.dummy(h, new BlockPos(26, 4, 10));
		for (Zombie z : List.of(a, b)) {
			z.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
			z.setHealth(100);
		}
		MagicTestKit.aim(wet, new BlockPos(6, 5, 10));
		MagicTestKit.aim(dry, new BlockPos(26, 5, 10));
		h.runAfterDelay(2, () -> {
			h.assertTrue(ChainLightning.wet(a), "the first zombie stands in water");
			h.assertValueEqual(MagicTestKit.cast(wet, SpellIds.CHAIN_LIGHTNING, 0f), Result.CAST, "wet lane");
			h.assertValueEqual(MagicTestKit.cast(dry, SpellIds.CHAIN_LIGHTNING, 0f), Result.CAST, "dry lane");
			float lostA = 100 - a.getHealth(), lostB = 100 - b.getHealth();
			h.assertTrue(lostB > 0 && Math.abs(lostA - 2 * lostB) < 0.05, "the wet zombie takes double: " + lostA + " vs " + lostB);
			h.succeed();
		});
	}

	/** Chain Lightning V Live Wire zaps what it struck again over the next seconds; Overload (its sibling) is done at once. */
	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 100)
	public void liveWireZapsAgain(GameTestHelper h) {
		ServerPlayer wire = lane(h, Element.LIGHTNING, 10, 8, new SpellRank(SpellIds.CHAIN_LIGHTNING, 5, SpellRank.A, SpellRank.A));
		ServerPlayer over = lane(h, Element.LIGHTNING, 10, 24, new SpellRank(SpellIds.CHAIN_LIGHTNING, 5, SpellRank.A, SpellRank.B));
		Zombie a = MagicTestKit.dummy(h, new BlockPos(8, 4, 10));
		Zombie b = MagicTestKit.dummy(h, new BlockPos(24, 4, 10));
		for (Zombie z : List.of(a, b)) {
			z.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
			z.setHealth(200);
		}
		MagicTestKit.aim(wire, new BlockPos(8, 5, 10));
		MagicTestKit.aim(over, new BlockPos(24, 5, 10));
		h.assertValueEqual(MagicTestKit.cast(wire, SpellIds.CHAIN_LIGHTNING, 0f), Result.CAST, "live wire");
		h.assertValueEqual(MagicTestKit.cast(over, SpellIds.CHAIN_LIGHTNING, 0f), Result.CAST, "overload");
		float a0 = a.getHealth(), b0 = b.getHealth();
		h.runAfterDelay(ChainLightning.WIRE_EVERY * ChainLightning.WIRE_PULSES + 5, () -> {
			h.assertTrue(a.getHealth() < a0 - 1, "Live Wire zaps the zombie again: " + a0 + " -> " + a.getHealth());
			h.assertValueEqual(b.getHealth(), b0, "control: Overload deals nothing more after the cast");
			h.succeed();
		});
	}

	/** Thunder Spear III Magnet drags a creature near the impact in; Javelin (its sibling) leaves it. */
	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void magnetDragsFoesIn(GameTestHelper h) {
		ServerPlayer mag = lane(h, Element.LIGHTNING, 10, 8, new SpellRank(SpellIds.THUNDER_SPEAR, 3, SpellRank.B, 0));
		ServerPlayer jav = lane(h, Element.LIGHTNING, 10, 24, new SpellRank(SpellIds.THUNDER_SPEAR, 3, SpellRank.A, 0));
		MagicTestKit.dummy(h, new BlockPos(8, 4, 12));
		MagicTestKit.dummy(h, new BlockPos(24, 4, 12));
		Zombie a = MagicTestKit.movableDummy(h, new BlockPos(13, 4, 12));
		Zombie b = MagicTestKit.movableDummy(h, new BlockPos(19, 4, 12));
		MagicTestKit.aim(mag, new BlockPos(8, 5, 12));
		MagicTestKit.aim(jav, new BlockPos(24, 5, 12));
		Vec3 aim = h.absoluteVec(new Vec3(8.5, 4, 12.5)), aimB = h.absoluteVec(new Vec3(24.5, 4, 12.5));
		double a0 = a.position().distanceTo(aim), b0 = b.position().distanceTo(aimB);
		h.assertValueEqual(MagicTestKit.cast(mag, SpellIds.THUNDER_SPEAR, 0f), Result.CAST, "magnet");
		h.assertValueEqual(MagicTestKit.cast(jav, SpellIds.THUNDER_SPEAR, 0f), Result.CAST, "javelin");
		h.runAfterDelay(20, () -> {
			h.assertTrue(a.position().distanceTo(aim) < a0 - 1, "Magnet drags it in: " + a0 + " -> " + a.position().distanceTo(aim));
			h.assertTrue(b.position().distanceTo(aimB) > b0 - 0.5, "control: Javelin leaves it: " + b0 + " -> " + b.position().distanceTo(aimB));
			h.succeed();
		});
	}

	/** Thunder Spear V Returning Spear strikes again on its way home; Chain Spear (its sibling) has no second creature to arc to. */
	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 60)
	public void returningSpearStrikesOnTheWayHome(GameTestHelper h) {
		ServerPlayer ret = lane(h, Element.LIGHTNING, 10, 8, new SpellRank(SpellIds.THUNDER_SPEAR, 5, SpellRank.A, SpellRank.A));
		ServerPlayer chain = lane(h, Element.LIGHTNING, 10, 24, new SpellRank(SpellIds.THUNDER_SPEAR, 5, SpellRank.A, SpellRank.B));
		Zombie a = MagicTestKit.dummy(h, new BlockPos(8, 4, 12));
		Zombie b = MagicTestKit.dummy(h, new BlockPos(24, 4, 12));
		for (Zombie z : List.of(a, b)) {
			z.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
			z.setHealth(200);
		}
		MagicTestKit.aim(ret, new BlockPos(8, 5, 12));
		MagicTestKit.aim(chain, new BlockPos(24, 5, 12));
		h.assertValueEqual(MagicTestKit.cast(ret, SpellIds.THUNDER_SPEAR, 0f), Result.CAST, "returning");
		h.assertValueEqual(MagicTestKit.cast(chain, SpellIds.THUNDER_SPEAR, 0f), Result.CAST, "chain");
		h.runAfterDelay(30, () -> {
			float lostA = 200 - a.getHealth(), lostB = 200 - b.getHealth();
			h.assertTrue(lostB > 0, "control: the spear strikes " + lostB);
			h.assertTrue(Math.abs(lostA - lostB * (1 + ThunderSpear.RETURN_SHARE)) < 0.05,
					"the way home strikes again for half: " + lostA + " vs " + lostB);
			h.succeed();
		});
	}

	/** Earth Spikes III Seeker bends the line to a creature 30° off the look; Impale (its sibling) runs straight past it. */
	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void seekerBendsToTheFoe(GameTestHelper h) {
		ServerPlayer seek = lane(h, Element.EARTH, 10, 8, new SpellRank(SpellIds.EARTH_SPIKES, 3, SpellRank.B, 0));
		ServerPlayer imp = lane(h, Element.EARTH, 10, 24, new SpellRank(SpellIds.EARTH_SPIKES, 3, SpellRank.A, 0));
		Zombie a = MagicTestKit.dummy(h, new BlockPos(12, 4, 11));
		Zombie b = MagicTestKit.dummy(h, new BlockPos(28, 4, 11));
		MagicTestKit.aim(seek, new BlockPos(8, 5, 12));
		MagicTestKit.aim(imp, new BlockPos(24, 5, 12));
		h.assertValueEqual(MagicTestKit.cast(seek, SpellIds.EARTH_SPIKES, 0f), Result.CAST, "seeker");
		h.assertValueEqual(MagicTestKit.cast(imp, SpellIds.EARTH_SPIKES, 0f), Result.CAST, "impale");
		h.runAfterDelay(20, () -> {
			h.assertTrue(a.getHealth() < 20, "Seeker's line bends to the zombie " + a.getHealth());
			h.assertValueEqual(b.getHealth(), 20F, "control: Impale's straight line misses it");
			h.succeed();
		});
	}

	/** Gravity Well III Mana Drain gives mana back for what it hurts; Collector (its sibling) does not. */
	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 80)
	public void manaDrainGivesManaBack(GameTestHelper h) {
		ServerPlayer drain = lane(h, Element.SHADOW, 10, 6, new SpellRank(SpellIds.GRAVITY_WELL, 3, SpellRank.A, 0));
		ServerPlayer coll = lane(h, Element.SHADOW, 10, 26, new SpellRank(SpellIds.GRAVITY_WELL, 3, SpellRank.B, 0));
		for (int i = 0; i < 4; i++) {
			MagicTestKit.dummy(h, new BlockPos(6 + i % 2, 4, 12 + i / 2));
			MagicTestKit.dummy(h, new BlockPos(26 + i % 2, 4, 12 + i / 2));
		}
		MagicTestKit.aim(drain, new BlockPos(6, 3, 12));
		MagicTestKit.aim(coll, new BlockPos(26, 3, 12));
		for (ServerPlayer p : List.of(drain, coll)) MagicAttachments.setMana(p, 50);
		h.assertValueEqual(MagicTestKit.cast(drain, SpellIds.GRAVITY_WELL, 0f), Result.CAST, "drain");
		h.assertValueEqual(MagicTestKit.cast(coll, SpellIds.GRAVITY_WELL, 0f), Result.CAST, "collector");
		h.runAfterDelay(45, () -> {
			int gained = MagicAttachments.mana(drain) - MagicAttachments.mana(coll);
			h.assertTrue(gained > 0, "Mana Drain gives mana back: " + gained + " more than Collector");
			h.assertTrue(gained <= GravityWell.drainCap(MagicAttachments.get(drain).rank(SpellIds.GRAVITY_WELL)), "at most half the cost: " + gained);
			h.succeed();
		});
	}

	/** Gravity Well III Collector sends the loot it held to the caster's feet at its end; Mana Drain (its sibling) leaves it. */
	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 120)
	public void collectorSendsLootHome(GameTestHelper h) {
		ServerPlayer coll = lane(h, Element.SHADOW, 10, 6, new SpellRank(SpellIds.GRAVITY_WELL, 3, SpellRank.B, 0));
		ServerPlayer drain = lane(h, Element.SHADOW, 10, 26, new SpellRank(SpellIds.GRAVITY_WELL, 3, SpellRank.A, 0));
		ItemEntity a = MagicTestKit.item(h, new BlockPos(7, 4, 13));
		ItemEntity b = MagicTestKit.item(h, new BlockPos(27, 4, 13));
		MagicTestKit.aim(coll, new BlockPos(6, 3, 12));
		MagicTestKit.aim(drain, new BlockPos(26, 3, 12));
		h.assertValueEqual(MagicTestKit.cast(coll, SpellIds.GRAVITY_WELL, 0f), Result.CAST, "collector");
		h.assertValueEqual(MagicTestKit.cast(drain, SpellIds.GRAVITY_WELL, 0f), Result.CAST, "drain");
		h.runAfterDelay(95, () -> {
			h.assertTrue(a.position().distanceTo(coll.position()) < 2.5, "Collector sends the loot to the caster: " + a.position().distanceTo(coll.position()));
			h.assertTrue(b.position().distanceTo(drain.position()) > 5, "control: Mana Drain leaves it in the well");
			h.succeed();
		});
	}

	/** Black Hole III Hungering: every creature it kills widens its pull, so a zombie past the first reach is pulled after the kills. */
	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 140)
	public void hungeringGrowsWithEveryKill(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 7);
		SpellRank r = new SpellRank(SpellIds.BLACK_HOLE, 3, SpellRank.A, 0);
		setRank(p, r);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		MagicTestKit.aim(p, new BlockPos(16, 3, 14));
		h.assertValueEqual(MagicTestKit.castUltimate(p, SpellIds.BLACK_HOLE), Result.CAST, "result");
		Vec3 c = rec.fx(p).getLast().target();
		// Six frail zombies by the core (each dies on its first hurt), and one just past the unfed pull, in the far corner.
		for (int i = 0; i < 6; i++) {
			Zombie frail = MagicTestKit.dummy(h, BlockPos.containing(h.relativeVec(c)).offset(i % 3 - 1, -2, i / 3));
			frail.setHealth(1);
		}
		Zombie far = MagicTestKit.movableDummy(h, new BlockPos(1, 4, 1));
		double d0 = far.getBoundingBox().getCenter().distanceTo(c);
		h.assertTrue(d0 > BlackHole.pullRadius(r), "out of the unfed pull: " + d0 + " > " + BlackHole.pullRadius(r));
		h.assertTrue(d0 < BlackHole.pullRadius(r, 6), "inside the fed one: " + d0);
		h.runAfterDelay(80, () -> {
			h.assertTrue(far.getBoundingBox().getCenter().distanceTo(c) < d0 - 1, "fed, the hole reaches and pulls it: " + d0 + " -> "
					+ far.getBoundingBox().getCenter().distanceTo(c));
			h.succeed();
		});
	}

	/** Air Step III Air Dash: cast in the air it dashes along the look; Long Jump (its sibling) still jumps up. */
	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 20)
	public void airDashDashesInTheAir(GameTestHelper h) {
		ServerPlayer dash = lane(h, Element.WIND, 10, 8, new SpellRank(SpellIds.AIR_STEP, 3, SpellRank.A, 0));
		ServerPlayer jump = lane(h, Element.WIND, 10, 24, new SpellRank(SpellIds.AIR_STEP, 3, SpellRank.B, 0));
		for (ServerPlayer p : List.of(dash, jump)) {
			MagicTestKit.teleportTo(p, new BlockPos(p == dash ? 8 : 24, 8, 4));
			p.setOnGround(false);
		}
		MagicTestKit.aim(dash, new BlockPos(8, 9, 12));
		MagicTestKit.aim(jump, new BlockPos(24, 9, 12));
		h.assertValueEqual(MagicTestKit.cast(dash, SpellIds.AIR_STEP, 0f), Result.CAST, "air dash");
		h.assertValueEqual(MagicTestKit.cast(jump, SpellIds.AIR_STEP, 0f), Result.CAST, "long jump");
		Vec3 v = dash.getDeltaMovement(), w = jump.getDeltaMovement();
		h.assertTrue(v.horizontalDistance() > 1.1 && v.y < 0.5, "Air Dash dashes along the look: " + v);
		h.assertTrue(w.y > 0.8, "control: Long Jump jumps: " + w);
		h.succeed();
	}

	/** Bubble Shield III Mending heals a heart for each projectile it stops. */
	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void mendingHealsPerStoppedArrow(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WATER, 3);
		setRank(p, new SpellRank(SpellIds.BUBBLE_SHIELD, 3, SpellRank.B, 0));
		p.getFoodData().setFoodLevel(10);
		p.getFoodData().setSaturation(0);
		h.assertValueEqual(MagicTestKit.cast(p, SpellIds.BUBBLE_SHIELD, 0f), Result.CAST, "shield up");
		p.setHealth(10);
		Arrow arrow = MagicTestKit.arrow(h, new BlockPos(16, 5, 10), new Vec3(0, 0, -1.5));
		h.runAfterDelay(15, () -> {
			h.assertTrue(arrow.isRemoved(), "the arrow is stopped");
			h.assertTrue(Math.abs(p.getHealth() - (10 + BubbleShield.MENDING_HEAL)) < 0.01, "healed a heart: " + p.getHealth());
			h.succeed();
		});
	}

	/** Bubble Shield V Fortress shoves a creature that touches the bubble back out; Pop (its sibling) leaves it until the end. */
	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void fortressShovesFoesOut(GameTestHelper h) {
		ServerPlayer fort = lane(h, Element.WATER, 10, 8, new SpellRank(SpellIds.BUBBLE_SHIELD, 5, SpellRank.A, SpellRank.A));
		ServerPlayer pop = lane(h, Element.WATER, 10, 24, new SpellRank(SpellIds.BUBBLE_SHIELD, 5, SpellRank.A, SpellRank.B));
		Zombie a = MagicTestKit.movableDummy(h, new BlockPos(8, 4, 6));
		Zombie b = MagicTestKit.movableDummy(h, new BlockPos(24, 4, 6));
		double a0 = flatDist(a, fort), b0 = flatDist(b, pop);
		h.assertValueEqual(MagicTestKit.cast(fort, SpellIds.BUBBLE_SHIELD, 0f), Result.CAST, "fortress");
		h.assertValueEqual(MagicTestKit.cast(pop, SpellIds.BUBBLE_SHIELD, 0f), Result.CAST, "pop");
		h.runAfterDelay(15, () -> {
			h.assertTrue(flatDist(a, fort) > a0 + 1, "Fortress shoves it out: " + a0 + " -> " + flatDist(a, fort));
			h.assertTrue(flatDist(b, pop) < b0 + 0.5, "control: Pop leaves it: " + b0 + " -> " + flatDist(b, pop));
			h.succeed();
		});
	}

	/** Shadow Step III Long Shadow reaches a target past the plain step's range; Backstab (its sibling) cannot. */
	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 20)
	public void longShadowStepsFarther(GameTestHelper h) {
		ServerPlayer longer = lane(h, Element.SHADOW, 10, 8, new SpellRank(SpellIds.SHADOW_STEP, 3, SpellRank.B, 0));
		ServerPlayer stab = lane(h, Element.SHADOW, 10, 24, new SpellRank(SpellIds.SHADOW_STEP, 3, SpellRank.A, 0));
		// Rank III's two bumps reach 16 × 1.3 = 20.8; Long Shadow's 33.
		MagicTestKit.teleportTo(longer, new BlockPos(8, 4, 1));
		MagicTestKit.teleportTo(stab, new BlockPos(24, 4, 1));
		MagicTestKit.dummy(h, new BlockPos(8, 4, 27));
		MagicTestKit.dummy(h, new BlockPos(24, 4, 27));
		MagicTestKit.aim(longer, new BlockPos(8, 5, 27));
		MagicTestKit.aim(stab, new BlockPos(24, 5, 27));
		h.assertValueEqual(MagicTestKit.cast(longer, SpellIds.SHADOW_STEP, 0f), Result.CAST, "long shadow");
		h.assertValueEqual(MagicTestKit.cast(stab, SpellIds.SHADOW_STEP, 0f), Result.CAST, "backstab");
		// Long Shadow lands behind the zombie (past it); Backstab's step finds no target in reach and only steps ahead.
		h.assertTrue(h.relativeVec(longer.position()).z > 27.5, "Long Shadow steps behind the far zombie: " + h.relativeVec(longer.position()));
		h.assertTrue(h.relativeVec(stab.position()).z < 26, "control: Backstab cannot reach it: " + h.relativeVec(stab.position()));
		h.succeed();

	}
}
