package local.carter.office.magic;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import local.carter.office.OfficeMod;
import local.carter.office.magic.cast.ElementChoice;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.ManaMath;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.item.GrimoireItem;
import local.carter.office.magic.item.GrimoireLook;
import local.carter.office.magic.item.MagicItems;
import local.carter.office.magic.net.MagicPayloads.OpenGrimoire;
import local.carter.office.magic.net.MagicServer;
import local.carter.office.magic.cast.CastService;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.Hurt;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellCost;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.SpellRegistry;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.UseEffects;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/** Server GameTests for the grimoire and seal items: recipes, tiered look stamping, stack size and use effects. */
public class MagicCoreGameTests {
	private static Optional<RecipeHolder<CraftingRecipe>> craft(GameTestHelper h, int w, int ht, Item... items) {
		ServerLevel level = h.getLevel();
		return level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, CraftingInput.of(w, ht, toStacks(items)), level);
	}

	private static void assertCrafts(GameTestHelper h, Optional<RecipeHolder<CraftingRecipe>> found, String recipe, Item result, int w, int ht, Item... items) {
		h.assertTrue(found.isPresent(), "no recipe for " + List.of(items));
		h.assertValueEqual(found.get().id().identifier(), OfficeMod.id(recipe), "recipe id");
		ItemStack out = found.get().value().assemble(CraftingInput.of(w, ht, toStacks(items)));
		h.assertTrue(out.is(result), "result item");
		h.assertValueEqual(out.getCount(), 1, "result count");
	}

	private static List<ItemStack> toStacks(Item... items) {
		List<ItemStack> stacks = new ArrayList<>();
		for (Item i : items) stacks.add(i == Items.AIR ? ItemStack.EMPTY : new ItemStack(i));
		return stacks;
	}

	@GameTest
	public void grimoireRecipeUsesBookFeatherIron(GameTestHelper h) {
		Item[] a = {Items.BOOK, Items.FEATHER, Items.IRON_INGOT};
		assertCrafts(h, craft(h, 3, 1, a), "grimoire", MagicItems.GRIMOIRE, 3, 1, a);
		Item[] b = {Items.IRON_INGOT, Items.AIR, Items.BOOK, Items.FEATHER};
		assertCrafts(h, craft(h, 2, 2, b), "grimoire", MagicItems.GRIMOIRE, 2, 2, b);
		h.assertTrue(craft(h, 2, 1, Items.BOOK, Items.FEATHER).isEmpty(), "book + feather alone crafts nothing");
		h.succeed();
	}

	@GameTest
	public void rebindingSealRecipe(GameTestHelper h) {
		Item[] a = {Items.NETHER_STAR, Items.BOOK};
		assertCrafts(h, craft(h, 2, 1, a), "rebinding_seal", MagicItems.REBINDING_SEAL, 2, 1, a);
		h.succeed();
	}

	@GameTest
	public void grimoireStampsLookFromData(GameTestHelper h) {
		ItemStack fire = new ItemStack(MagicItems.GRIMOIRE);
		GrimoireLook.stamp(fire, Optional.of(Element.FIRE), 7);
		CustomModelData cmd = fire.get(DataComponents.CUSTOM_MODEL_DATA);
		h.assertTrue(cmd != null, "custom model data set");
		h.assertValueEqual(cmd.strings(), List.of("t3"), "fire L7 strings");
		h.assertValueEqual(cmd.colors(), List.of(0xFFFF6A1A), "fire L7 colours");
		h.assertValueEqual(GrimoireLook.element(fire), Optional.of(Element.FIRE), "element read back");

		ItemStack plain = new ItemStack(MagicItems.GRIMOIRE);
		GrimoireLook.stamp(plain, Optional.empty(), 1);
		CustomModelData p = plain.get(DataComponents.CUSTOM_MODEL_DATA);
		h.assertValueEqual(p.strings(), List.of("t0"), "unbound L1 strings");
		h.assertValueEqual(p.colors(), List.of(0xFF6E6E80), "unbound L1 colours");
		h.assertValueEqual(GrimoireLook.element(plain), Optional.<Element>empty(), "unbound reads back empty");
		h.assertTrue(GrimoireItem.isGrimoire(plain) && !GrimoireItem.isGrimoire(new ItemStack(Items.BOOK)), "isGrimoire");
		h.succeed();
	}

	@GameTest
	public void grimoireStacksToOneAndIsNotAShield(GameTestHelper h) {
		ItemStack g = new ItemStack(MagicItems.GRIMOIRE);
		h.assertValueEqual(g.getMaxStackSize(), 1, "max stack");
		h.assertFalse(g.has(DataComponents.BLOCKS_ATTACKS), "grimoire must not block attacks");
		h.assertValueEqual(BuiltInRegistries.ITEM.getKey(MagicItems.GRIMOIRE), Identifier.fromNamespaceAndPath("office", "grimoire"), "grimoire id");
		h.assertValueEqual(BuiltInRegistries.ITEM.getKey(MagicItems.REBINDING_SEAL), Identifier.fromNamespaceAndPath("office", "rebinding_seal"), "seal id");
		h.succeed();
	}

	@GameTest
	public void grimoireChargeSlowsAndBlocksSprint(GameTestHelper h) {
		ItemStack g = new ItemStack(MagicItems.GRIMOIRE);
		h.assertValueEqual(g.get(DataComponents.USE_EFFECTS), new UseEffects(false, false, 0.6f), "use effects");
		h.succeed();
	}

	// ---- Task 3: data, payload receivers, element choice, seal, kit ----

	private static InteractionResult use(GameTestHelper h, ServerPlayer p, InteractionHand hand) {
		return p.getItemInHand(hand).use(h.getLevel(), p, hand);
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void firstUseOpensChoice(GameTestHelper h) {
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		ServerPlayer p = MagicTestKit.otherPlayer(h, MagicTestKit.STAND);
		p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(MagicItems.GRIMOIRE));
		h.assertTrue(use(h, p, InteractionHand.MAIN_HAND).consumesAction(), "main hand, unbound: handled");
		h.assertValueEqual(rec.opens(), List.of(OpenGrimoire.Mode.CHOOSE), "main hand opens the choice");

		p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(MagicItems.GRIMOIRE));
		h.assertTrue(use(h, p, InteractionHand.OFF_HAND).consumesAction(), "off hand + empty main hand, unbound: handled");
		h.assertValueEqual(rec.opens(), List.of(OpenGrimoire.Mode.CHOOSE, OpenGrimoire.Mode.CHOOSE), "off hand opens the choice");

		p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
		h.assertValueEqual(use(h, p, InteractionHand.OFF_HAND), InteractionResult.PASS, "a sword in the main hand: pass");
		h.assertValueEqual(rec.opens().size(), 2, "nothing more opened");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void chooseElementBindsOnce(GameTestHelper h) {
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		ServerPlayer p = MagicTestKit.otherPlayer(h, MagicTestKit.STAND);
		h.assertFalse(MagicServer.chooseElement(p, Element.WIND), "ChooseElement without a grimoire in hand is ignored");
		h.assertValueEqual(MagicAttachments.get(p).element(), Optional.<Element>empty(), "still unbound");
		p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(MagicItems.GRIMOIRE));
		h.assertTrue(MagicServer.chooseElement(p, Element.FIRE), "first choice binds");
		h.assertFalse(ElementChoice.choose(p, Element.WATER), "second choice refused");
		MagicData d = MagicAttachments.get(p);
		h.assertValueEqual(d.element(), Optional.of(Element.FIRE), "still fire");
		h.assertValueEqual(d.selected(), Optional.of(SpellIds.FIRE_BOLT), "first spell selected");
		h.assertValueEqual(rec.titleKeys(), List.of("element.office.fire"), "one title: the element's name");
		MagicTestKit.Recorder.Title t = rec.titles().get(0);
		h.assertValueEqual(t.title().getStyle().getColor().getValue(), Element.FIRE.argb() & 0xFFFFFF, "title in the element colour");
		h.assertTrue(t.subtitle().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc
				&& tc.getKey().equals("magic.office.bound") && tc.getArgs()[0] instanceof net.minecraft.network.chat.Component m
				&& m.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents mc
				&& mc.getKey().equals("element.office.fire.motto"), "subtitle: bound + motto");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void mainHandUseOpensInfo(GameTestHelper h) {
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		ServerPlayer p = MagicTestKit.caster(h, Element.WIND, 3);
		p.setItemInHand(InteractionHand.MAIN_HAND, p.getOffhandItem());
		p.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
		h.assertTrue(use(h, p, InteractionHand.MAIN_HAND).consumesAction(), "handled");
		h.assertValueEqual(rec.opens(), List.of(OpenGrimoire.Mode.INFO), "bound main hand opens the info screen");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void rebindingSealOpensChoiceChangesNothing(GameTestHelper h) {
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 5);
		p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(MagicItems.REBINDING_SEAL, 2));
		h.assertTrue(use(h, p, InteractionHand.MAIN_HAND).consumesAction(), "seal used");
		h.assertValueEqual(rec.opens(), List.of(OpenGrimoire.Mode.CHOOSE), "choice opened");
		h.assertTrue(ElementChoice.rebindPending(p), "a rebind is pending");
		// The player closes the screen (Esc): no ChooseElement ever arrives.
		MagicData d = MagicAttachments.get(p);
		h.assertValueEqual(d.element(), Optional.of(Element.FIRE), "old element kept");
		h.assertValueEqual(d.selected(), Optional.of(SpellIds.FIRE_BOLT), "selection kept");
		h.assertValueEqual(p.getMainHandItem().getCount(), 2, "no seal spent");
		h.assertValueEqual(GrimoireLook.element(p.getOffhandItem()), Optional.of(Element.FIRE), "grimoire keeps its look");
		h.assertTrue(rec.titles().isEmpty(), "no title");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void rebindWithoutGrimoireSpendsExactlyOneSeal(GameTestHelper h) {
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 5);
		p.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY); // no grimoire anywhere
		p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(MagicItems.REBINDING_SEAL, 3));
		use(h, p, InteractionHand.MAIN_HAND);
		h.assertTrue(MagicServer.chooseElement(p, Element.EARTH), "the seal's choice binds");
		MagicData d = MagicAttachments.get(p);
		h.assertValueEqual(d.element(), Optional.of(Element.EARTH), "rebound to earth");
		h.assertValueEqual(d.selected(), Optional.of(SpellIds.STONE_BULLET), "first spell selected");
		h.assertValueEqual(d.level(), 5, "level kept");
		h.assertValueEqual(p.getMainHandItem().getCount(), 2, "exactly one seal spent");
		h.assertValueEqual(rec.titleKeys(), List.of("element.office.earth"), "bound title");
		h.assertFalse(ElementChoice.rebindPending(p), "the rebind is used up");
		h.assertFalse(MagicServer.chooseElement(p, Element.WATER), "a second choice is refused");
		h.assertValueEqual(MagicAttachments.get(p).element(), Optional.of(Element.EARTH), "still earth");
		h.assertValueEqual(p.getMainHandItem().getCount(), 2, "no second seal spent");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void rebindNeedsTheSealInHand(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 5);
		p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(MagicItems.REBINDING_SEAL));
		use(h, p, InteractionHand.MAIN_HAND);
		ItemStack seal = p.getMainHandItem().copy();
		p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); // put away before choosing
		h.assertFalse(MagicServer.chooseElement(p, Element.WIND), "no seal in hand: refused");
		h.assertValueEqual(MagicAttachments.get(p).element(), Optional.of(Element.FIRE), "still fire");
		p.setItemInHand(InteractionHand.OFF_HAND, seal); // the seal back, in the off hand this time
		h.assertTrue(MagicServer.chooseElement(p, Element.WIND), "a seal in either hand works");
		h.assertTrue(p.getOffhandItem().isEmpty(), "that seal spent");
		h.assertValueEqual(MagicAttachments.get(p).element(), Optional.of(Element.WIND), "wind");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void rebindToTheSameElementSpendsNothing(GameTestHelper h) {
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 5);
		p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(MagicItems.REBINDING_SEAL, 2));
		use(h, p, InteractionHand.MAIN_HAND);
		h.assertFalse(MagicServer.chooseElement(p, Element.FIRE), "choosing the current element is refused");
		h.assertValueEqual(p.getMainHandItem().getCount(), 2, "no seal spent");
		h.assertTrue(rec.titles().isEmpty(), "no title");
		h.assertTrue(ElementChoice.rebindPending(p), "the rebind stays open");
		h.assertTrue(MagicServer.chooseElement(p, Element.SHADOW), "another element still binds");
		h.assertValueEqual(p.getMainHandItem().getCount(), 1, "one seal spent");
		h.assertValueEqual(MagicAttachments.get(p).element(), Optional.of(Element.SHADOW), "shadow");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void deathCancelsAPendingRebind(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 5);
		p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(MagicItems.REBINDING_SEAL));
		use(h, p, InteractionHand.MAIN_HAND);
		h.assertTrue(ElementChoice.rebindPending(p), "pending before death");
		ServerPlayer respawned = MagicTestKit.respawn(h, p);
		h.assertFalse(ElementChoice.rebindPending(respawned), "death drops the pending rebind");
		respawned.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(MagicItems.REBINDING_SEAL));
		h.assertFalse(MagicServer.chooseElement(respawned, Element.WATER), "a late choice after death is refused");
		h.assertValueEqual(respawned.getMainHandItem().getCount(), 1, "seal kept");
		h.assertValueEqual(MagicAttachments.get(respawned).element(), Optional.of(Element.FIRE), "still fire");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void unboundPlayerCannotUseASeal(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.otherPlayer(h, MagicTestKit.STAND);
		p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(MagicItems.REBINDING_SEAL));
		h.assertValueEqual(use(h, p, InteractionHand.MAIN_HAND), InteractionResult.PASS, "unbound: pass");
		h.assertFalse(ElementChoice.rebindPending(p), "nothing pending");
		h.assertFalse(MagicServer.chooseElement(p, Element.FIRE), "no grimoire, no pending rebind: refused");
		h.assertValueEqual(p.getMainHandItem().getCount(), 1, "seal kept");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void magicDataSurvivesDeath(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WATER, 4);
		GlobalPos home = GlobalPos.of(h.getLevel().dimension(), h.absolutePos(new BlockPos(3, 4, 3)));
		MagicData d = MagicAttachments.get(p).withHome(Optional.of(home)).withReady(SpellIds.WATER_BULLET, 500L);
		MagicAttachments.set(p, d);
		MagicAttachments.setMana(p, 37);
		ServerPlayer respawned = MagicTestKit.respawn(h, p);
		h.assertTrue(respawned != p, "a new player object");
		h.assertValueEqual(MagicAttachments.get(respawned), d, "magic data copied on death");
		h.assertValueEqual(MagicAttachments.mana(respawned), 37, "mana copied on death");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void heldGrimoireRestampedOnLevelChange(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 1);
		h.assertValueEqual(p.getOffhandItem().get(DataComponents.CUSTOM_MODEL_DATA).strings(), List.of("t0"), "level 1 look");
		CommandSourceStack src = h.getLevel().getServer().createCommandSourceStack().withEntity(p).withSuppressedOutput();
		h.getLevel().getServer().getCommands().performPrefixedCommand(src, "magic level @s 7");
		h.assertValueEqual(MagicAttachments.get(p).level(), 7, "level set by the command");
		h.assertValueEqual(p.getOffhandItem().get(DataComponents.CUSTOM_MODEL_DATA).strings(), List.of("t3"), "held grimoire re-stamped");
		h.getLevel().getServer().getCommands().performPrefixedCommand(src, "magic mana @s 55");
		h.assertValueEqual(MagicAttachments.mana(p), 55, "mana set by the command");
		h.getLevel().getServer().getCommands().performPrefixedCommand(src, "magic reset @s");
		h.assertValueEqual(MagicAttachments.get(p), MagicData.EMPTY, "reset");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void selectSpellRejectsLocked(GameTestHelper h) {
		MagicTestKit.Probe locked = MagicTestKit.probe("probe_select_locked", Element.FIRE, 1);
		MagicTestKit.Probe open = MagicTestKit.probe("probe_select_open", Element.FIRE, 0);
		MagicTestKit.override(h, locked);
		MagicTestKit.override(h, open);
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 1);
		h.assertFalse(MagicServer.select(p, locked.id()), "slot 1 is locked at level 1");
		h.assertValueEqual(MagicAttachments.get(p).selected(), Optional.of(SpellIds.FIRE_BOLT), "selection unchanged");
		h.assertFalse(MagicServer.select(p, SpellIds.WATER_BULLET), "another element's spell refused");
		h.assertTrue(MagicServer.select(p, open.id()), "an unlocked spell is selectable");
		h.assertValueEqual(MagicAttachments.get(p).selected(), Optional.of(open.id()), "selected");
		h.succeed();
	}

	/**
	 * Overrides are per player. Two kit players with probes at Recall's key (B's reusing Recall's id) each resolve their own,
	 * while lookups without a player still see the real Recall; two probes at FIRE slot 0 are each selected and cast by their
	 * own player only; a test-wide override reaches a kit player made after it.
	 */
	@GameTest(structure = MagicTestKit.ARENA)
	public void overridesAreScopedToTheirPlayers(GameTestHelper h) {
		Spell realRecall = SpellRegistry.get(SpellIds.RECALL).orElseThrow();
		h.assertFalse(realRecall instanceof MagicTestKit.Probe, "Recall is registered");
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		ServerPlayer a = MagicTestKit.caster(h, Element.FIRE, 1);
		ServerPlayer b = MagicTestKit.caster(h, Element.FIRE, 1);
		MagicTestKit.teleportTo(b, new BlockPos(24, 4, 4));

		// Same key, two players; B's probe also takes the real spell's id.
		MagicTestKit.Probe recallA = MagicTestKit.probe("probe_scoped_recall_a", null, Slots.RECALL);
		MagicTestKit.Probe recallB = new MagicTestKit.Probe(SpellIds.RECALL, null, Slots.RECALL, SpellCost.forSlot(Slots.RECALL), false,
				ctx -> CastOutcome.cast(ctx.fx()));
		MagicTestKit.overrideFor(a, recallA);
		MagicTestKit.overrideFor(b, recallB);
		h.assertValueEqual(SpellRegistry.at(a.getUUID(), Element.FIRE, Slots.RECALL), Optional.<Spell>of(recallA), "A's slot 5");
		h.assertValueEqual(SpellRegistry.at(b.getUUID(), Element.FIRE, Slots.RECALL), Optional.<Spell>of(recallB), "B's slot 5");
		h.assertTrue(SpellRegistry.wheel(a.getUUID(), Element.WATER)[Slots.RECALL] == recallA, "A's wheel");
		h.assertTrue(SpellRegistry.wheel(b.getUUID(), Element.WATER)[Slots.RECALL] == recallB, "B's wheel");
		h.assertValueEqual(SpellRegistry.get(b.getUUID(), SpellIds.RECALL), Optional.<Spell>of(recallB), "B's Recall is B's probe");
		h.assertTrue(SpellRegistry.get(a.getUUID(), SpellIds.RECALL).isEmpty(), "A's probe displaces Recall for A");
		h.assertTrue(SpellRegistry.get(b.getUUID(), recallA.id()).isEmpty(), "B never sees A's probe");
		h.assertValueEqual(SpellRegistry.get(SpellIds.RECALL), Optional.of(realRecall), "byId without a player: the real Recall");
		h.assertValueEqual(SpellRegistry.at(Element.FIRE, Slots.RECALL), Optional.of(realRecall), "slot without a player: the real Recall");
		h.assertTrue(SpellRegistry.wheel(Element.FIRE)[Slots.RECALL] == realRecall, "wheel without a player: the real Recall");
		h.assertTrue(SpellRegistry.all().contains(realRecall) && !SpellRegistry.all().contains(recallA) && !SpellRegistry.all().contains(recallB),
				"all(): real spells only");

		// The cast path: each player selects and casts its own probe at the same key.
		int[] ran = new int[2];
		MagicTestKit.Probe castA = MagicTestKit.probe("probe_scoped_cast_a", Element.FIRE, 0).withBody(ctx -> {
			ran[0]++;
			return CastOutcome.cast(ctx.fx());
		});
		MagicTestKit.Probe castB = MagicTestKit.probe("probe_scoped_cast_b", Element.FIRE, 0).withBody(ctx -> {
			ran[1]++;
			return CastOutcome.cast(ctx.fx());
		});
		MagicTestKit.overrideFor(a, castA);
		MagicTestKit.overrideFor(b, castB);
		h.assertValueEqual(SpellRegistry.unlocked(a.getUUID(), MagicAttachments.get(a)).get(0), (Spell) castA, "A's unlocked");
		h.assertValueEqual(SpellRegistry.unlocked(b.getUUID(), MagicAttachments.get(b)).get(0), (Spell) castB, "B's unlocked");
		h.assertFalse(MagicServer.select(b, castA.id()), "B cannot select A's probe");
		h.assertTrue(MagicServer.select(a, castA.id()), "A selects its probe");
		h.assertTrue(MagicServer.select(b, castB.id()), "B selects its probe");
		CastService.release(a, 5);
		CastService.release(b, 5);
		h.assertValueEqual(ran[0], 1, "A cast its probe");
		h.assertValueEqual(ran[1], 1, "B cast its probe");
		h.assertValueEqual(rec.fx(a).size(), 1, "A's fx");
		h.assertValueEqual(rec.fx(b).size(), 1, "B's fx");

		MagicTestKit.Probe shared = MagicTestKit.probe("probe_scoped_shared", Element.FIRE, 1);
		MagicTestKit.override(h, shared);
		ServerPlayer late = MagicTestKit.caster(h, Element.FIRE, 2);
		h.assertValueEqual(SpellRegistry.at(late.getUUID(), Element.FIRE, 1), Optional.<Spell>of(shared), "a later kit player sees the test's override");
		h.assertValueEqual(SpellRegistry.at(late.getUUID(), Element.FIRE, Slots.RECALL), Optional.of(realRecall), "...but not A's or B's");
		h.assertValueEqual(SpellRegistry.at(a.getUUID(), Element.FIRE, 1), Optional.<Spell>of(shared), "A sees it too");
		h.assertTrue(SpellRegistry.get(shared.id()).isEmpty(), "no player: no override");
		h.succeed();
	}

	/** A movable dummy has no goals, stands still on its own, and knockback moves it. */
	@GameTest(structure = MagicTestKit.ARENA, maxTicks = 60)
	public void movableDummyStandsStillButTakesKnockback(GameTestHelper h) {
		Zombie z = MagicTestKit.movableDummy(h, new BlockPos(16, 4, 12));
		h.assertFalse(z.isNoAi(), "AI on, so physics applies");
		h.assertTrue(z.getGoalSelector().getAvailableGoals().isEmpty(), "no goals");
		h.assertFalse(z.canPickUpLoot(), "no loot pickup");
		h.assertTrue(z.isPersistenceRequired(), "persistent");
		Vec3 start = z.position();
		h.runAfterDelay(20, () -> {
			h.assertTrue(z.position().subtract(start).horizontalDistance() < 0.01, "stands still: moved " + z.position().subtract(start));
			h.assertTrue(z.getTarget() == null, "targets no one");
			Vec3 before = z.position();
			h.assertTrue(Hurt.knock(z, new Vec3(0, 0, 1), 1.0, 0.2), "knockable");
			h.runAfterDelay(10, () -> {
				double moved = z.position().subtract(before).horizontalDistance();
				h.assertTrue(moved > 0.5, "knockback moved it " + moved + " blocks");
				h.succeed();
			});
		});
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void kitPlayersAreRealPlayers(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		PlayerList list = level.getServer().getPlayerList();
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 2);
		ServerPlayer other = MagicTestKit.otherPlayer(h, new BlockPos(20, 4, 10));
		h.assertTrue(list.getPlayer(p.getUUID()) == p && list.getPlayer(other.getUUID()) == other, "in the player list");
		List<Player> inBounds = level.getEntitiesOfClass(Player.class, h.getBounds());
		h.assertTrue(inBounds.contains(p) && inBounds.contains(other), "found by level entity queries");
		h.assertTrue(p.connection != null, "has a connection");

		MagicTestKit.teleportTo(p, new BlockPos(8, 4, 20));
		h.assertValueEqual(p.blockPosition(), h.absolutePos(new BlockPos(8, 4, 20)), "teleported");
		h.assertTrue(p.hurtServer(level, level.damageSources().generic(), 2f), "can be hurt");
		h.assertValueEqual(p.getHealth(), 18f, "health after 2 damage");

		MagicData d = MagicAttachments.get(other).withLevel(6);
		MagicAttachments.set(other, d);
		MagicTestKit.kill(other);
		h.assertFalse(other.isAlive(), "died without throwing");
		ServerPlayer back = MagicTestKit.respawn(h, other);
		h.assertTrue(back != other && back.isAlive() && list.getPlayer(other.getUUID()) == back, "respawned through the player list");
		h.assertValueEqual(MagicAttachments.get(back).level(), 6, "magic survives a real respawn");

		MagicTestKit.disconnect(p);
		h.assertTrue(list.getPlayer(p.getUUID()) == null, "disconnect removes it from the player list");
		h.assertTrue(p.isRemoved(), "and from the level");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA)
	public void kitArenaIsReady(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.SHADOW, 7);
		h.assertValueEqual(p.blockPosition(), h.absolutePos(MagicTestKit.STAND), "stands at STAND");
		h.assertBlockPresent(Blocks.STONE, MagicTestKit.STAND.below());
		Vec3 facing = Vec3.atLowerCornerOf(h.getTestRotation().rotate(Direction.SOUTH).getUnitVec3i());
		h.assertTrue(p.getLookAngle().dot(facing) > 0.999, "faces +Z, got " + p.getLookAngle());
		h.assertValueEqual(p.experienceLevel, MagicTestKit.XP_LEVELS, "30 XP levels");
		h.assertTrue(p.getMainHandItem().isEmpty(), "empty main hand");
		h.assertTrue(GrimoireItem.isGrimoire(p.getOffhandItem()), "grimoire in the off hand");
		h.assertValueEqual(MagicAttachments.mana(p), ManaMath.max(7), "full mana");
		h.assertTrue(p.getRespawnConfig() == null, "respawn config cleared");
		h.assertValueEqual(MagicAttachments.get(p).element(), Optional.of(Element.SHADOW), "bound");
		long hash = MagicTestKit.blockHash(h);
		h.assertValueEqual(MagicTestKit.blockHash(h), hash, "block hash is stable");
		h.assertValueEqual(MagicTestKit.waterCount(h), 0, "no water");
		h.assertValueEqual(MagicTestKit.count(h, Blocks.STONE), MagicTestKit.SIZE_X * MagicTestKit.SIZE_Z * (MagicTestKit.FLOOR_TOP + 1), "stone floor");
		MagicTestKit.fill(h, new BlockPos(2, 4, 2), new BlockPos(3, 5, 3), Blocks.WATER);
		h.assertValueEqual(MagicTestKit.waterCount(h), 8, "water counted");
		h.assertTrue(MagicTestKit.blockHash(h) != hash, "hash sees the change");
		MagicTestKit.aim(p, new BlockPos(16, 1, 20));
		h.assertTrue(p.getLookAngle().y < 0 && p.getLookAngle().dot(facing) > 0, "aim turns the eyes");
		h.succeed();
	}
}
