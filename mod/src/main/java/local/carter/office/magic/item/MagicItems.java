package local.carter.office.magic.item;

import java.util.function.Function;

import local.carter.office.OfficeMod;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.UseEffects;

/** Registry entries for the magic items. */
public final class MagicItems {
	public static final ResourceKey<Item> GRIMOIRE_KEY = ResourceKey.create(Registries.ITEM, OfficeMod.id("grimoire"));
	public static final ResourceKey<Item> REBINDING_SEAL_KEY = ResourceKey.create(Registries.ITEM, OfficeMod.id("rebinding_seal"));

	/** Charging (and tapping) slows to 0.6x and blocks sprint. */
	public static final UseEffects CHARGE_EFFECTS = new UseEffects(false, false, 0.6f);

	public static final Item GRIMOIRE = register(GRIMOIRE_KEY, GrimoireItem::new, new Item.Properties()
			.stacksTo(1)
			.rarity(Rarity.UNCOMMON)
			.component(DataComponents.USE_EFFECTS, CHARGE_EFFECTS));

	public static final Item REBINDING_SEAL = register(REBINDING_SEAL_KEY, RebindingSealItem::new, new Item.Properties()
			.stacksTo(16)
			.rarity(Rarity.EPIC)
			.component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true));

	private MagicItems() {}

	private static Item register(ResourceKey<Item> key, Function<Item.Properties, Item> factory, Item.Properties properties) {
		return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(properties.setId(key)));
	}

	/** Forces class init (registration) from the mod initializer. */
	public static void init() {}
}
