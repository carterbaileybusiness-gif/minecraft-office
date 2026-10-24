package local.carter.office.magic;

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
import local.carter.office.magic.spell.fire.FireBolt;
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
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/**
 * The spell skill tree, server side: buying a rank takes exactly its cost and nothing when it refuses (each reason), a Rebinding
 * Seal re-picks forks, ranks survive death and an element switch and back, the packets are throttled, and the forks really
 * change what a cast does (damage and mana per rank, Scatter, Lance, Soul Siphon, Frost Jet, Twin Prison). The balance of every
 * rank V fork pair is {@code BalanceGameTests.ranked<Spell>}.
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
}
