package local.carter.office.magic.item;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.level.Level;

/**
 * The grimoire: a use-duration item (like a bow) held in the off hand. Charge is measured from vanilla item use, so the
 * server reads it in {@link #releaseUsing} and remote clients see it without extra packets. Behaviour arrives in Tasks 3–4;
 * until then every hook passes.
 */
public class GrimoireItem extends Item {
	/** Long enough that a hold never ends by itself; the release decides. */
	public static final int USE_DURATION = 72000;

	public GrimoireItem(Properties properties) {
		super(properties);
	}

	public static boolean isGrimoire(ItemStack stack) {
		return stack.is(MagicItems.GRIMOIRE);
	}

	@Override
	public int getUseDuration(ItemStack stack, LivingEntity entity) {
		return USE_DURATION;
	}

	@Override
	public ItemUseAnimation getUseAnimation(ItemStack stack) {
		return ItemUseAnimation.NONE;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		return InteractionResult.PASS;
	}

	@Override
	public boolean releaseUsing(ItemStack stack, Level level, LivingEntity entity, int remainingTicks) {
		return false;
	}

	@Override
	public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remainingTicks) {
	}

	@Override
	public void inventoryTick(ItemStack stack, ServerLevel level, Entity entity, EquipmentSlot slot) {
	}
}
