package local.carter.office.magic;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import local.carter.office.OfficeMod;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.item.GrimoireItem;
import local.carter.office.magic.item.GrimoireLook;
import local.carter.office.magic.item.MagicItems;

import net.minecraft.core.component.DataComponents;
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
}
