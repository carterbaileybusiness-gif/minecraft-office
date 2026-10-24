package local.carter.office.magic;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import local.carter.office.OfficeMod;
import local.carter.office.magic.cast.ElementChoice;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.ManaMath;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.item.GrimoireItem;
import local.carter.office.magic.item.GrimoireLook;
import local.carter.office.magic.item.MagicItems;
import local.carter.office.magic.net.MagicPayloads.OpenGrimoire;
import local.carter.office.magic.net.MagicServer;
import local.carter.office.magic.spell.SpellIds;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
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
		h.assertValueEqual(rec.titleKeys(), List.of("magic.office.bound"), "one bound title");
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
	public void rebindingSealClearsElementKeepsLevel(GameTestHelper h) {
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		ServerPlayer p = MagicTestKit.caster(h, Element.FIRE, 5);
		p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(MagicItems.REBINDING_SEAL));
		h.assertTrue(use(h, p, InteractionHand.MAIN_HAND).consumesAction(), "seal used");
		MagicData d = MagicAttachments.get(p);
		h.assertValueEqual(d.element(), Optional.<Element>empty(), "element cleared");
		h.assertValueEqual(d.level(), 5, "level kept");
		h.assertTrue(p.getMainHandItem().isEmpty(), "seal used up");
		h.assertValueEqual(rec.opens(), List.of(OpenGrimoire.Mode.CHOOSE), "choice opened");
		h.assertValueEqual(GrimoireLook.element(p.getOffhandItem()), Optional.<Element>empty(), "held grimoire re-stamped unbound");
		h.assertTrue(ElementChoice.choose(p, Element.EARTH), "can choose again");
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
