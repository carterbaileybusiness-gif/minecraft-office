package local.carter.office.magic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import local.carter.office.magic.cast.CastService;
import local.carter.office.magic.cast.CastService.Result;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.item.GrimoireItem;
import local.carter.office.magic.item.MagicItems;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.net.MagicServer;
import local.carter.office.magic.spell.ActiveEffects;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.Hurt;
import local.carter.office.magic.spell.LingeringEffect;
import local.carter.office.magic.spell.Motion;
import local.carter.office.magic.spell.ProjectileHit;
import local.carter.office.magic.spell.ProjectileSpec;
import local.carter.office.magic.spell.Projectiles;
import local.carter.office.magic.spell.SpellCost;
import local.carter.office.magic.spell.TargetFilter;
import local.carter.office.magic.spell.Targets;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/** Server GameTests for the cast pipeline (Task 4): checks, spending, cooldowns, levelling, charge, the item, and the spell kit services. */
public class CastGameTests {
	private static final Identifier PROBE = id("probe");
	private static final Identifier PROBE_MEDIUM = id("probe_medium");

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath("office-test", path);
	}

	/** A FIRE caster at {@code level} with the plain slot-0 probe installed and selected. */
	private static ServerPlayer probeCaster(GameTestHelper h, int level) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, level);
		MagicTestKit.override(h, MagicTestKit.probe("probe", Element.FIRE, 0));
		select(p, PROBE);
		return p;
	}

	private static void select(ServerPlayer p, Identifier spell) {
		MagicAttachments.set(p, MagicAttachments.get(p).withSelected(Optional.of(spell)));
	}

	private static long now(GameTestHelper h) {
		return h.getLevel().getGameTime();
	}

	private static void assertNear(GameTestHelper h, double actual, double expected, String what) {
		h.assertTrue(Math.abs(actual - expected) < 1e-4, what + ": expected " + expected + ", got " + actual);
	}

	// ---- CastService ----

	@GameTest(structure = MagicTestKit.ARENA)
	public void castSpendsManaSetsCooldownSendsOneFx(GameTestHelper h) {
		ServerPlayer p = probeCaster(h, 1);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicAttachments.mana(p), 100, "starting mana");
		h.assertValueEqual(MagicTestKit.cast(p, PROBE, 0f), Result.CAST, "result");
		h.assertValueEqual(MagicAttachments.mana(p), 92, "mana after a tap");
		h.assertValueEqual(MagicAttachments.get(p).cooldownLeft(PROBE, now(h)), 10L, "cooldown");
		h.assertValueEqual(rec.fx(p).size(), 1, "fx sent");
		h.assertValueEqual(rec.fx(p).get(0).spell(), PROBE, "fx spell");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void chargedCastCostsMoreAndIsStronger(GameTestHelper h) {
		ServerPlayer p = probeCaster(h, 1);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.cast(p, PROBE, 1f), Result.CAST, "result");
		h.assertValueEqual(MagicAttachments.mana(p), 88, "mana after a full charge");
		FxEvent fx = rec.fx(p).get(0);
		h.assertValueEqual(fx.power(), 2.0f, "fx power");
		h.assertValueEqual(fx.charge(), 1.0f, "fx charge");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void lowManaLowersChargeInsteadOfFailing(GameTestHelper h) {
		ServerPlayer p = probeCaster(h, 1);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		MagicAttachments.setMana(p, 9);
		h.assertValueEqual(MagicTestKit.cast(p, PROBE, 1f), Result.CAST, "result");
		int spent = 9 - MagicAttachments.mana(p);
		h.assertTrue(spent >= 8 && spent <= 9, "spent " + spent + " of 9");
		FxEvent fx = rec.fx(p).get(0);
		h.assertTrue(fx.charge() < 1f, "charge lowered, was " + fx.charge());
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void notEnoughManaRefused(GameTestHelper h) {
		ServerPlayer p = probeCaster(h, 1);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		MagicAttachments.setMana(p, 7);
		h.assertValueEqual(MagicTestKit.cast(p, PROBE, 0f), Result.NO_MANA, "result");
		h.assertValueEqual(MagicAttachments.mana(p), 7, "mana");
		h.assertValueEqual(rec.fx(p).size(), 0, "fx sent");
		h.assertValueEqual(rec.messageKeys(), List.of("magic.office.no_mana"), "message");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void cooldownBlocksSecondCast(GameTestHelper h) {
		ServerPlayer p = probeCaster(h, 1);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.cast(p, PROBE, 0f), Result.CAST, "first");
		h.assertValueEqual(MagicTestKit.cast(p, PROBE, 0f), Result.COOLDOWN, "second");
		h.assertValueEqual(rec.messageKeys(), List.of("magic.office.cooldown"), "message");
		h.assertValueEqual(MagicAttachments.mana(p), 92, "mana spent once");
		h.assertValueEqual(rec.fx(p).size(), 1, "fx sent");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void lockedSlotRefused(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 1);
		MagicTestKit.override(h, MagicTestKit.probe("probe_medium", Element.FIRE, 1));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.cast(p, PROBE_MEDIUM, 0f), Result.LOCKED, "result");
		h.assertValueEqual(rec.messageKeys(), List.of("magic.office.locked"), "message");
		h.assertValueEqual(MagicAttachments.mana(p), 100, "mana");
		h.assertValueEqual(rec.fx(p).size(), 0, "fx sent");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void unboundAndWrongElementRefused(GameTestHelper h) {
		MagicTestKit.override(h, MagicTestKit.probe("probe_water", Element.WATER, 0));
		ServerPlayer fire = MagicTestKit.caster(h, Element.FIRE, 1);
		ServerPlayer unbound = MagicTestKit.otherPlayer(h, new BlockPos(10, 4, 4));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.cast(fire, id("probe_water"), 0f), Result.WRONG_ELEMENT, "fire casting water");
		h.assertValueEqual(MagicTestKit.cast(unbound, id("probe_water"), 0f), Result.NO_ELEMENT, "unbound");
		h.assertValueEqual(rec.messageKeys(), List.of("magic.office.wrong_element", "magic.office.choose"), "messages");
		h.assertValueEqual(rec.fx().size(), 0, "fx sent");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void castXpLevelsUpGrimoire(GameTestHelper h) {
		ServerPlayer p = probeCaster(h, 1);
		MagicAttachments.set(p, MagicAttachments.get(p).withXp(18));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.cast(p, PROBE, 0f), Result.CAST, "result");
		MagicData d = MagicAttachments.get(p);
		h.assertValueEqual(d.level(), 2, "level");
		h.assertValueEqual(d.xp(), 0, "xp");
		h.assertValueEqual(rec.titleKeys(), List.of("magic.office.level_up"), "titles");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void cooldownNeverLowered(GameTestHelper h) {
		Identifier sid = id("probe_long_cd");
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 1);
		long at = now(h) + 500;
		MagicTestKit.override(h, MagicTestKit.probe("probe_long_cd", Element.FIRE, 0).withCost(new SpellCost(8, 10, 2, 0)).withBody(ctx -> {
			MagicAttachments.set(ctx.caster(), MagicAttachments.get(ctx.caster()).withReady(sid, at));
			return CastOutcome.cast(ctx.fx());
		}));
		h.assertValueEqual(MagicTestKit.cast(p, sid, 0f), Result.CAST, "result");
		h.assertValueEqual(MagicAttachments.get(p).readyAt().get(sid), at, "readyAt");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void ownCooldownSpellUntouched(GameTestHelper h) {
		Identifier sid = id("probe_own_cd");
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 1);
		MagicTestKit.override(h, MagicTestKit.probe("probe_own_cd", Element.FIRE, 0).withCost(new SpellCost(8, 1200, 2, 0)).withOwnCooldown(true));
		h.assertValueEqual(MagicTestKit.cast(p, sid, 0f), Result.CAST, "result");
		h.assertTrue(!MagicAttachments.get(p).readyAt().containsKey(sid), "readyAt written: " + MagicAttachments.get(p).readyAt());
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void busyEffectsFizzleFree(GameTestHelper h) {
		Identifier sid = id("probe_lingering");
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 1);
		ServerLevel level = h.getLevel();
		LingeringEffect idle = (l, age) -> true;
		MagicTestKit.override(h, MagicTestKit.probe("probe_lingering", Element.FIRE, 0).withBody(ctx -> {
			if (!ActiveEffects.add(ctx.level(), (l, age) -> age < 20)) return CastOutcome.fizzle("magic.office.busy");
			return CastOutcome.cast(ctx.fx());
		}));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		List<LingeringEffect> fillers = new ArrayList<>();
		// Fill and empty within this one server tick so concurrent tests never see a full list.
		try {
			while (true) {
				int n = fillers.size();
				LingeringEffect f = (l, age) -> n >= 0;
				if (!ActiveEffects.add(level, f)) break;
				fillers.add(f);
			}
			h.assertValueEqual(ActiveEffects.count(), ActiveEffects.MAX, "effects full");
			h.assertValueEqual(MagicTestKit.cast(p, sid, 0f), Result.BUSY, "result");
		} finally {
			for (LingeringEffect f : fillers) ActiveEffects.remove(f);
		}
		h.assertValueEqual(MagicAttachments.mana(p), 100, "mana");
		h.assertValueEqual(rec.fx(p).size(), 0, "fx sent");
		h.assertValueEqual(rec.messageKeys(), List.of("magic.office.busy"), "message");
		h.assertValueEqual(MagicAttachments.get(p).cooldownLeft(sid, now(h)), 0L, "cooldown");
		h.assertTrue(!ActiveEffects.remove(idle), "removing an unknown effect");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 80)
	public void manaRegensAfterPause(GameTestHelper h) {
		ServerPlayer p = probeCaster(h, 1);
		h.assertValueEqual(MagicTestKit.cast(p, PROBE, 0f), Result.CAST, "result");
		h.runAfterDelay(15, () -> h.assertValueEqual(MagicAttachments.mana(p), 92, "mana during the regen pause"));
		h.runAfterDelay(50, () -> {
			int mana = MagicAttachments.mana(p);
			h.assertTrue(mana > 92 && mana <= 100, "mana regenerated to " + mana);
			h.succeed();
		});
	}

	// ---- GrimoireItem ----

	@GameTest(structure = MagicTestKit.ARENA)
	public void mainHandItemNeverStartsCharge(GameTestHelper h) {
		ServerPlayer p = probeCaster(h, 1);
		for (ItemStack held : List.of(new ItemStack(Items.IRON_SWORD), new ItemStack(Items.BREAD), new ItemStack(Items.DIRT),
				new ItemStack(Items.SHIELD))) {
			p.setItemInHand(InteractionHand.MAIN_HAND, held);
			InteractionResult r = MagicItems.GRIMOIRE.use(h.getLevel(), p, InteractionHand.OFF_HAND);
			h.assertValueEqual(r, InteractionResult.PASS, "use with " + held);
			h.assertFalse(p.isUsingItem(), "using with " + held);
		}
		p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		h.assertValueEqual(MagicItems.GRIMOIRE.use(h.getLevel(), p, InteractionHand.OFF_HAND), InteractionResult.CONSUME, "use with an empty hand");
		h.assertTrue(p.isUsingItem(), "charging with an empty hand");
		h.assertValueEqual(p.getUsedItemHand(), InteractionHand.OFF_HAND, "used hand");
		p.stopUsingItem();
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void switchingToToolCancelsCharge(GameTestHelper h) {
		ServerPlayer p = probeCaster(h, 1);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		MagicItems.GRIMOIRE.use(h.getLevel(), p, InteractionHand.OFF_HAND);
		h.assertTrue(p.isUsingItem(), "charging");
		p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
		p.getUseItem().onUseTick(h.getLevel(), p, p.getUseItemRemainingTicks());
		h.assertFalse(p.isUsingItem(), "still charging with a pickaxe");
		p.releaseUsingItem();
		h.assertValueEqual(MagicAttachments.mana(p), 100, "mana");
		h.assertValueEqual(rec.fx().size(), 0, "fx sent");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void chargeDiscardedOnDeath(GameTestHelper h) {
		ServerPlayer p = probeCaster(h, 1);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		ItemStack grimoire = p.getOffhandItem();
		MagicItems.GRIMOIRE.use(h.getLevel(), p, InteractionHand.OFF_HAND);
		h.assertTrue(p.isUsingItem(), "charging");
		MagicTestKit.kill(p);
		h.assertFalse(p.isUsingItem(), "charging after death");
		p.releaseUsingItem();
		// A late release reaching the item itself (a full 40-tick charge) must still cast nothing for a dead player.
		MagicItems.GRIMOIRE.releaseUsing(grimoire, h.getLevel(), p, GrimoireItem.USE_DURATION - 40);
		ServerPlayer back = MagicTestKit.respawn(h, p);
		h.assertValueEqual(MagicAttachments.mana(back), 100, "mana after respawn");
		h.assertValueEqual(rec.fx().size(), 0, "fx sent");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void chargeDiscardedOnDisconnect(GameTestHelper h) {
		ServerPlayer p = probeCaster(h, 1);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		ItemStack grimoire = p.getOffhandItem();
		MagicItems.GRIMOIRE.use(h.getLevel(), p, InteractionHand.OFF_HAND);
		h.assertTrue(p.isUsingItem(), "charging");
		MagicTestKit.disconnect(p);
		h.assertFalse(p.isUsingItem(), "charging after disconnect");
		p.releaseUsingItem();
		MagicItems.GRIMOIRE.releaseUsing(grimoire, h.getLevel(), p, GrimoireItem.USE_DURATION - 40);
		h.assertValueEqual(MagicAttachments.mana(p), 100, "mana");
		h.assertValueEqual(rec.fx().size(), 0, "fx sent");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void chargeDiscardedOnDimensionChange(GameTestHelper h) {
		ServerPlayer p = probeCaster(h, 1);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		ItemStack grimoire = p.getOffhandItem();
		MagicItems.GRIMOIRE.use(h.getLevel(), p, InteractionHand.OFF_HAND);
		h.assertTrue(p.isUsingItem(), "charging");
		ServerLevel nether = h.getLevel().getServer().getLevel(Level.NETHER);
		MagicTestKit.teleportTo(p, nether, new Vec3(0.5, 64, 0.5));
		h.assertTrue(p.level() == nether, "in the Nether");
		h.assertFalse(p.isUsingItem(), "charging after a dimension change");
		p.releaseUsingItem(); // the client's late release packet
		h.assertValueEqual(MagicAttachments.mana(p), 100, "mana");
		h.assertValueEqual(rec.fx().size(), 0, "fx sent");
		h.assertTrue(p.getOffhandItem() == grimoire, "grimoire kept in the off hand");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void selectionCapturedAtChargeStart(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 3);
		MagicTestKit.override(h, MagicTestKit.probe("probe", Element.FIRE, 0));
		MagicTestKit.override(h, MagicTestKit.probe("probe_small_b", Element.FIRE, 2));
		select(p, PROBE);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		MagicItems.GRIMOIRE.use(h.getLevel(), p, InteractionHand.OFF_HAND);
		h.assertTrue(p.isUsingItem(), "charging");
		h.assertTrue(MagicServer.select(p, id("probe_small_b")), "selection changed mid-charge");
		p.releaseUsingItem();
		h.assertValueEqual(rec.fx(p).size(), 1, "fx sent");
		h.assertValueEqual(rec.fx(p).get(0).spell(), PROBE, "cast the spell selected when the charge started");
		// The next charge uses the new selection.
		MagicItems.GRIMOIRE.use(h.getLevel(), p, InteractionHand.OFF_HAND);
		p.releaseUsingItem();
		h.assertValueEqual(rec.fx(p).size(), 2, "second fx");
		h.assertValueEqual(rec.fx(p).get(1).spell(), id("probe_small_b"), "second spell");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void grimoireWontChargeWhenHopeless(GameTestHelper h) {
		ServerPlayer p = probeCaster(h, 1);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.cast(p, PROBE, 0f), Result.CAST, "cast");
		h.assertValueEqual(MagicItems.GRIMOIRE.use(h.getLevel(), p, InteractionHand.OFF_HAND), InteractionResult.PASS, "use on cooldown");
		h.assertFalse(p.isUsingItem(), "charging on cooldown");
		MagicAttachments.set(p, MagicAttachments.get(p).withReadyAt(Map.of()));
		MagicAttachments.setMana(p, 5);
		h.assertValueEqual(MagicItems.GRIMOIRE.use(h.getLevel(), p, InteractionHand.OFF_HAND), InteractionResult.PASS, "use without mana");
		h.assertFalse(p.isUsingItem(), "charging without mana");
		select(p, id("not_registered"));
		h.assertValueEqual(MagicItems.GRIMOIRE.use(h.getLevel(), p, InteractionHand.OFF_HAND), InteractionResult.PASS, "use with an unknown spell");
		h.assertFalse(p.isUsingItem(), "charging an unknown spell");
		h.assertValueEqual(rec.messageKeys(), List.of("magic.office.cooldown", "magic.office.no_mana"), "messages");
		select(p, PROBE);
		p.setGameMode(GameType.CREATIVE);
		h.assertValueEqual(MagicItems.GRIMOIRE.use(h.getLevel(), p, InteractionHand.OFF_HAND), InteractionResult.CONSUME, "creative without mana");
		p.stopUsingItem();
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void throwingSpellFailsNeutrally(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 1);
		MagicTestKit.override(h, MagicTestKit.probe("probe_throws", Element.FIRE, 0).withBody(ctx -> {
			throw new IllegalStateException("probe_throws: expected test failure");
		}));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.cast(p, id("probe_throws"), 0f), Result.FIZZLED, "result");
		h.assertValueEqual(rec.messageKeys(), List.of("magic.office.spell_failed"), "message");
		h.assertValueEqual(MagicAttachments.mana(p), 100, "mana");
		h.assertValueEqual(rec.fx().size(), 0, "fx sent");
		h.succeed();
	}

	// ---- Ultimates and creative ----

	@GameTest(structure = MagicTestKit.ARENA)
	public void ultimateNeedsXpLevels(GameTestHelper h) {
		Identifier sid = id("probe_ult_levels");
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 7);
		MagicTestKit.override(h, MagicTestKit.probe("probe_ult_levels", Element.FIRE, 4));
		p.setExperienceLevels(2);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.castUltimate(p, sid), Result.NO_LEVELS, "result");
		h.assertValueEqual(rec.messageKeys(), List.of("magic.office.no_levels"), "message");
		h.assertValueEqual(p.experienceLevel, 2, "XP levels");
		h.assertValueEqual(MagicAttachments.mana(p), 160, "mana");
		h.assertValueEqual(rec.fx().size(), 0, "fx sent");
		h.assertValueEqual(rec.challenges().size(), 0, "challenges sent");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void ultimateSpendsXpLevelsAndBaseMana(GameTestHelper h) {
		Identifier sid = id("probe_ult_spend");
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 7);
		MagicTestKit.override(h, MagicTestKit.probe("probe_ult_spend", Element.FIRE, 4));
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.castUltimate(p, sid), Result.CAST, "result");
		h.assertValueEqual(p.experienceLevel, MagicTestKit.XP_LEVELS - 3, "XP levels");
		h.assertValueEqual(MagicAttachments.mana(p), 160 - 60, "mana");
		h.assertValueEqual(MagicAttachments.get(p).cooldownLeft(sid, now(h)), 600L, "cooldown");
		h.assertValueEqual(rec.fx(p).size(), 1, "fx sent");
		h.assertValueEqual(rec.fx(p).get(0).charge(), 1.0f, "ultimate charge");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void creativePaysNothing(GameTestHelper h) {
		Identifier sid = id("probe_ult_creative");
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 7);
		MagicTestKit.override(h, MagicTestKit.probe("probe_ult_creative", Element.FIRE, 4));
		p.setGameMode(GameType.CREATIVE);
		MagicAttachments.setMana(p, 0);
		p.setExperienceLevels(0);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertValueEqual(MagicTestKit.castUltimate(p, sid), Result.CAST, "result");
		h.assertValueEqual(MagicAttachments.mana(p), 0, "mana");
		h.assertValueEqual(p.experienceLevel, 0, "XP levels");
		h.assertValueEqual(MagicAttachments.get(p).cooldownLeft(sid, now(h)), 600L, "cooldown still applies");
		h.assertValueEqual(rec.fx(p).size(), 1, "fx sent");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void releasingTheGrimoireCasts(GameTestHelper h) {
		ServerPlayer p = probeCaster(h, 1);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		MagicItems.GRIMOIRE.use(h.getLevel(), p, InteractionHand.OFF_HAND);
		p.releaseUsingItem();
		h.assertFalse(p.isUsingItem(), "charging after release");
		h.assertValueEqual(rec.fx(p).size(), 1, "fx sent");
		h.assertValueEqual(rec.fx(p).get(0).power(), 1.0f, "tap power");
		h.assertValueEqual(MagicAttachments.mana(p), 92, "mana");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void tapAndHoldReleaseCharges(GameTestHelper h) {
		ServerPlayer p = probeCaster(h, 1);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		CastService.release(p, 5);
		h.assertValueEqual(rec.fx(p).size(), 1, "tap fx");
		h.assertValueEqual(rec.fx(p).get(0).power(), 1.0f, "tap power");
		h.runAfterDelay(11, () -> {
			CastService.release(p, 40);
			h.assertValueEqual(rec.fx(p).size(), 2, "hold fx");
			h.assertValueEqual(rec.fx(p).get(1).power(), 2.0f, "hold power");
			h.assertValueEqual(rec.fx(p).get(1).charge(), 1.0f, "hold charge");
			h.succeed();
		});
	}

	// ---- Spell kit services ----

	@GameTest(structure = MagicTestKit.ARENA)
	public void targetFilterSparesPlayersPetsVillagers(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 1);
		ServerPlayer other = MagicTestKit.otherPlayer(h, new BlockPos(10, 4, 10));
		Wolf wolf = MagicTestKit.tamedWolf(h, new BlockPos(12, 4, 10), other);
		Villager villager = MagicTestKit.villager(h, new BlockPos(14, 4, 10));
		IronGolem golem = h.spawn(EntityTypes.IRON_GOLEM, new BlockPos(18, 4, 10));
		golem.setNoAi(true);
		ArmorStand stand = h.spawn(EntityTypes.ARMOR_STAND, new BlockPos(20, 4, 10));
		ItemEntity item = MagicTestKit.item(h, new BlockPos(22, 4, 10));
		Zombie zombie = MagicTestKit.dummy(h, new BlockPos(16, 4, 14));
		for (Entity e : List.<Entity>of(p, other, wolf, villager, golem, stand, item)) {
			h.assertFalse(TargetFilter.affects(p, e), "affects " + e);
		}
		for (Entity e : List.<Entity>of(p, other, wolf, villager, golem, stand)) {
			h.assertFalse(TargetFilter.pushable(p, e), "pushable " + e);
		}
		h.assertTrue(TargetFilter.affects(p, zombie), "affects the zombie");
		h.assertTrue(TargetFilter.pushable(p, zombie), "pushes the zombie");
		h.assertTrue(TargetFilter.pushable(p, item), "pushes the item");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void targetsHurtAndKnockRespectTheFilter(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 1);
		Zombie zombie = MagicTestKit.dummy(h, new BlockPos(16, 4, 10));
		Villager villager = MagicTestKit.villager(h, new BlockPos(16, 4, 7));
		ItemEntity item = MagicTestKit.item(h, new BlockPos(17, 4, 9));
		MagicTestKit.aim(p, new BlockPos(16, 5, 10));
		AtomicReference<String> failure = new AtomicReference<>();
		MagicTestKit.override(h, MagicTestKit.probe("probe_kit", Element.FIRE, 0).withBody(ctx -> {
			try {
				// The villager stands between the caster and the zombie: rays look through it.
				Optional<LivingEntity> hit = Targets.ray(ctx, 16, 0.3);
				h.assertTrue(hit.isPresent() && hit.get() == zombie, "ray hit " + hit);
				List<LivingEntity> near = Targets.radius(ctx, villager.position(), 4);
				h.assertTrue(near.contains(zombie) && !near.contains(villager), "radius " + near);
				List<Entity> cone = Targets.cone(ctx, 10, 30, true);
				h.assertTrue(cone.contains(zombie) && cone.contains(item) && !cone.contains(villager), "pushable cone " + cone);
				List<Entity> hurtCone = Targets.cone(ctx, 10, 30, false);
				h.assertTrue(hurtCone.contains(zombie) && !hurtCone.contains(item), "cone " + hurtCone);
				h.assertTrue(Hurt.damage(ctx, zombie, 4f), "zombie hurt");
				h.assertFalse(Hurt.damage(ctx, villager, 4f), "villager hurt");
				h.assertTrue(Hurt.knock(zombie, ctx.look(), 1.0, 0.3), "zombie knocked");
				h.assertFalse(Hurt.knock(villager, ctx.look(), 1.0, 0.3), "villager knocked");
				MagicTestKit.aim(p, new BlockPos(16, 3, 8));
				Vec3 point = Targets.point(ctx, 64);
				assertNear(h, point.y, h.absoluteVec(new Vec3(0, MagicTestKit.FLOOR_TOP + 1, 0)).y, "point stops on the floor");
				Vec3 ground = Targets.ground(ctx.level(), h.absoluteVec(new Vec3(16.5, 12, 12.5)));
				assertNear(h, ground.y, h.absoluteVec(new Vec3(0, MagicTestKit.FLOOR_TOP + 1, 0)).y, "ground y");
			} catch (RuntimeException e) {
				failure.set(e.getMessage());
			}
			return CastOutcome.cast(ctx.fx());
		}));
		h.assertValueEqual(MagicTestKit.cast(p, id("probe_kit"), 0f), Result.CAST, "result");
		if (failure.get() != null) h.fail(failure.get());
		h.assertTrue(zombie.getHealth() <= 16f, "zombie health " + zombie.getHealth());
		h.assertValueEqual(villager.getHealth(), villager.getMaxHealth(), "villager health");
		h.assertTrue(zombie.getDeltaMovement().horizontalDistance() > 0.5, "zombie velocity " + zombie.getDeltaMovement());
		h.assertTrue(zombie.syncVelocity, "zombie velocity synced");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void dashAndSafeTeleport(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 1);
		Vec3 v = new Vec3(0, 0.6, 1.4);
		Motion.dash(p, v, 20);
		h.assertValueEqual(p.getDeltaMovement(), v, "dash velocity");
		h.assertTrue(Motion.noFall(p), "fall damage suppressed");
		MagicTestKit.fill(h, new BlockPos(14, 4, 12), new BlockPos(18, 8, 14), Blocks.STONE);
		Vec3 inWall = h.absoluteVec(new Vec3(16.5, 4, 13.5));
		Vec3 landed = Motion.teleportSafe(p, inWall);
		h.assertTrue(h.getLevel().noCollision(p, p.getDimensions(p.getPose()).makeBoundingBox(landed)), "landed inside a block: " + landed);
		h.assertTrue(landed.z <= h.absoluteVec(new Vec3(0, 0, 12)).z - 0.29, "landed in front of the wall: " + landed);
		h.assertValueEqual(p.position(), landed, "player moved");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 30)
	public void projectileStopsAtWall(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 1);
		MagicTestKit.fill(h, new BlockPos(12, 4, 9), new BlockPos(20, 9, 9), Blocks.STONE);
		List<ProjectileHit> hits = new ArrayList<>();
		long[] hitTick = {-1};
		long start = h.getTick();
		MagicTestKit.override(h, MagicTestKit.probe("probe_shot", Element.FIRE, 0).withBody(ctx -> {
			ProjectileSpec spec = new ProjectileSpec(ctx.eye(), ctx.look().scale(2), 0, 0.3, 20, false);
			if (!Projectiles.launch(ctx, spec, hit -> {
				hits.add(hit);
				hitTick[0] = h.getTick();
			})) return CastOutcome.fizzle("magic.office.busy");
			return CastOutcome.cast(ctx.fx());
		}));
		h.assertValueEqual(MagicTestKit.cast(p, id("probe_shot"), 0f), Result.CAST, "result");
		h.succeedWhen(() -> {
			h.assertValueEqual(hits.size(), 1, "hits");
			ProjectileHit hit = hits.get(0);
			h.assertTrue(hit.entity() == null, "entity hit " + hit.entity());
			h.assertTrue(hit.block() != null, "block hit");
			h.assertValueEqual(hit.block().getBlockPos(), h.absolutePos(new BlockPos(16, 5, 9)), "wall block");
			assertNear(h, hit.pos().z, h.absoluteVec(new Vec3(0, 0, 9)).z, "hit z");
			h.assertTrue(hitTick[0] - start <= 4, "hit after " + (hitTick[0] - start) + " ticks");
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void projectileStepMatchesServerPath(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 1);
		List<Vec3> path = new ArrayList<>();
		AtomicReference<ProjectileSpec> launched = new AtomicReference<>();
		boolean[] ended = {false};
		MagicTestKit.override(h, MagicTestKit.probe("probe_arc", Element.FIRE, 0).withBody(ctx -> {
			ProjectileSpec spec = new ProjectileSpec(ctx.eye(), new Vec3(0.1, 0.35, 0.8), 0.03, 0.25, 10, false);
			launched.set(spec);
			Projectiles.launch(ctx, spec, hit -> ended[0] = true, path::add);
			return CastOutcome.cast(ctx.fx());
		}));
		h.assertValueEqual(MagicTestKit.cast(p, id("probe_arc"), 0f), Result.CAST, "result");
		h.runAfterDelay(14, () -> {
			ProjectileSpec spec = launched.get();
			h.assertValueEqual(path.size(), 10, "server steps");
			h.assertFalse(ended[0], "hit something in open air");
			for (int t = 1; t <= 10; t++) h.assertValueEqual(path.get(t - 1), Projectiles.step(spec, t), "position at tick " + t);
			h.assertValueEqual(Projectiles.step(spec, 0), spec.origin(), "step 0");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void projectileHitsZombieNotVillager(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 1);
		MagicTestKit.villager(h, new BlockPos(16, 4, 8));
		Zombie zombie = MagicTestKit.dummy(h, new BlockPos(16, 4, 12));
		MagicTestKit.aim(p, new BlockPos(16, 5, 12));
		List<ProjectileHit> hits = new ArrayList<>();
		MagicTestKit.override(h, MagicTestKit.probe("probe_bolt", Element.FIRE, 0).withBody(ctx -> {
			Projectiles.launch(ctx, new ProjectileSpec(ctx.eye(), ctx.look().scale(1.5), 0, 0.3, 20, false), hits::add);
			return CastOutcome.cast(ctx.fx());
		}));
		h.assertValueEqual(MagicTestKit.cast(p, id("probe_bolt"), 0f), Result.CAST, "result");
		h.succeedWhen(() -> {
			h.assertValueEqual(hits.size(), 1, "hits");
			h.assertTrue(hits.get(0).entity() == zombie, "hit " + hits.get(0).entity());
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 40)
	public void pierceStopsAfterCastHitBudget(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 1);
		for (int x = 2; x <= 30; x += 2) {
			for (int z = 8; z <= 16; z += 2) MagicTestKit.dummy(h, new BlockPos(x, 4, z));
		}
		List<ProjectileHit> hits = new ArrayList<>();
		List<Vec3> moves = new ArrayList<>();
		MagicTestKit.override(h, MagicTestKit.probe("probe_pierce", Element.FIRE, 0).withBody(ctx -> {
			// A huge radius: all 75 dummies are inside the sweep on the first step.
			Vec3 centre = h.absoluteVec(new Vec3(16, 5, 12));
			Projectiles.launch(ctx, new ProjectileSpec(centre, new Vec3(0, 0, 0.05), 0, 20, 20, true), hits::add, moves::add);
			return CastOutcome.cast(ctx.fx());
		}));
		h.assertValueEqual(MagicTestKit.cast(p, id("probe_pierce"), 0f), Result.CAST, "result");
		h.runAfterDelay(6, () -> {
			h.assertValueEqual(hits.size(), Projectiles.MAX_HITS_PER_CAST, "entity hits");
			h.assertValueEqual(moves.size(), 1, "steps flown");
			h.assertValueEqual(hits.stream().map(ProjectileHit::entity).distinct().count(), (long) Projectiles.MAX_HITS_PER_CAST, "distinct entities");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void spellHitsIgnoreAndKeepInvulnerabilityFrames(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 1);
		Zombie fresh = MagicTestKit.dummy(h, new BlockPos(14, 4, 10));
		Zombie recentlyHit = MagicTestKit.dummy(h, new BlockPos(18, 4, 10));
		recentlyHit.setInvulnerableTime(15);
		boolean[] landed = new boolean[4];
		MagicTestKit.override(h, MagicTestKit.probe("probe_iframes", Element.FIRE, 0).withBody(ctx -> {
			landed[0] = Hurt.damage(ctx, fresh, 3f);
			landed[1] = Hurt.damage(ctx, fresh, 3f);
			landed[2] = Hurt.damage(ctx, recentlyHit, 3f);
			landed[3] = Hurt.damage(ctx, recentlyHit, 3f);
			return CastOutcome.cast(ctx.fx());
		}));
		h.assertValueEqual(MagicTestKit.cast(p, id("probe_iframes"), 0f), Result.CAST, "result");
		for (int i = 0; i < landed.length; i++) h.assertTrue(landed[i], "hit " + i + " landed");
		h.assertTrue(fresh.getHealth() <= 14f, "two hits in one tick, health " + fresh.getHealth());
		h.assertTrue(recentlyHit.getHealth() <= 14f, "hits through i-frames, health " + recentlyHit.getHealth());
		h.assertValueEqual(fresh.getInvulnerableTime(), 0, "no i-frames granted");
		h.assertValueEqual(recentlyHit.getInvulnerableTime(), 15, "i-frames kept");
		h.succeed();
	}
}
