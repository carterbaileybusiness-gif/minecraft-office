package local.carter.office.hologram;

import java.util.Set;

import local.carter.office.OfficeMod;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

/** Registry entries for the hologram emitter: block, item, block entity type. */
public final class OfficeBlocks {
	/** Explosion resistance that no vanilla blast can beat (same as bedrock's order of magnitude). */
	public static final float EXPLOSION_PROOF = 3600000.0F;

	public static final ResourceKey<Block> HOLOGRAM_EMITTER_KEY = ResourceKey.create(Registries.BLOCK, OfficeMod.id("hologram_emitter"));
	public static final ResourceKey<Item> HOLOGRAM_EMITTER_ITEM_KEY = ResourceKey.create(Registries.ITEM, OfficeMod.id("hologram_emitter"));
	public static final ResourceKey<BlockEntityType<?>> HOLOGRAM_BE_KEY = ResourceKey.create(Registries.BLOCK_ENTITY_TYPE, OfficeMod.id("hologram"));

	public static final Block HOLOGRAM_EMITTER = Registry.register(BuiltInRegistries.BLOCK, HOLOGRAM_EMITTER_KEY,
			new HologramEmitterBlock(BlockBehaviour.Properties.of()
					.setId(HOLOGRAM_EMITTER_KEY)
					.mapColor(MapColor.COLOR_BLACK)
					.strength(1.0F, EXPLOSION_PROOF)
					.sound(SoundType.METAL)
					.lightLevel(state -> 6)
					.noOcclusion()
					.forceSolidOn()
					.pushReaction(PushReaction.IMMOVEABLE)));

	public static final Item HOLOGRAM_EMITTER_ITEM = registerItem();

	public static final BlockEntityType<HologramBlockEntity> HOLOGRAM_BE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
			HOLOGRAM_BE_KEY, new BlockEntityType<>(HologramBlockEntity::new, Set.of(HOLOGRAM_EMITTER)));

	private OfficeBlocks() {}

	private static Item registerItem() {
		BlockItem item = new BlockItem(HOLOGRAM_EMITTER, new Item.Properties().setId(HOLOGRAM_EMITTER_ITEM_KEY).useBlockDescriptionPrefix());
		item.registerBlocks(Item.BY_BLOCK, item);
		return Registry.register(BuiltInRegistries.ITEM, HOLOGRAM_EMITTER_ITEM_KEY, item);
	}

	/** Forces class init (registration) from the mod initializer. */
	public static void init() {}
}
