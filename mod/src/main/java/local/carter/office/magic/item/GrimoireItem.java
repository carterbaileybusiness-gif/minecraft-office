package local.carter.office.magic.item;

import java.util.Optional;

import local.carter.office.magic.cast.CastService;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.net.MagicPayloads.OpenGrimoire;
import local.carter.office.magic.net.MagicServer;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellRegistry;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
 * server reads it in {@link #releaseUsing} and remote clients see it without extra packets.
 *
 * <p>Use: unbound (either hand) opens the element choice; bound in the main hand opens the info screen; in the off hand with
 * anything in the main hand it passes, so that item's right-click wins. Off hand + empty main hand + bound + a registered
 * selected spell that is off cooldown and affordable (base mana) starts charging; otherwise it passes, with the reason on the
 * action bar. The spell is captured at that moment and releasing casts it ({@link CastService#release}), even if the selection
 * changed mid-charge. Anything appearing in the main hand mid-charge cancels it with no cast, and so do death, disconnect and
 * dimension change ({@code MagicMod} hooks).
 */
public class GrimoireItem extends Item {
	/** Long enough that a hold never ends by itself; the release decides. */
	public static final int USE_DURATION = 72000;
	/** Ticks between look re-stamps of carried grimoires ({@code MagicAttachments.set} re-stamps held ones at once). */
	public static final int STAMP_INTERVAL = 10;

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
		boolean mainHand = hand == InteractionHand.MAIN_HAND;
		if (!mainHand && !player.getMainHandItem().isEmpty()) return InteractionResult.PASS;
		MagicData data = MagicAttachments.get(player);
		if (data.element().isEmpty()) return open(player, OpenGrimoire.Mode.CHOOSE);
		if (mainHand) return open(player, OpenGrimoire.Mode.INFO);
		// Pre-checks (both sides, from synced data) so a hopeless charge never starts; release re-checks everything.
		Optional<Spell> spell = data.selected().flatMap(SpellRegistry::get);
		if (spell.isEmpty()) return InteractionResult.PASS;
		Component blocked = CastService.chargeBlocker(player, data, spell.get());
		if (blocked != null) {
			if (player instanceof ServerPlayer sp) MagicServer.sender().actionBar(sp, blocked);
			return InteractionResult.PASS;
		}
		player.startUsingItem(hand);
		if (player instanceof ServerPlayer sp) CastService.beginCharge(sp, spell.get().id());
		return InteractionResult.CONSUME;
	}

	private static InteractionResult open(Player player, OpenGrimoire.Mode mode) {
		if (player instanceof ServerPlayer sp) MagicServer.sender().open(sp, mode);
		return InteractionResult.SUCCESS;
	}

	/**
	 * Casts on the server the spell captured when the charge started; a dead or disconnected holder, or one with something
	 * in the main hand, casts nothing.
	 */
	@Override
	public boolean releaseUsing(ItemStack stack, Level level, LivingEntity entity, int remainingTicks) {
		if (!(entity instanceof ServerPlayer p)) return false;
		if (p.isAlive() && !p.hasDisconnected() && p.getMainHandItem().isEmpty()) {
			CastService.release(p, getUseDuration(stack, entity) - remainingTicks);
		} else {
			CastService.endCharge(p);
		}
		return false;
	}

	/** A main-hand item appearing mid-charge cancels the charge (no cast, nothing spent). */
	@Override
	public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remainingTicks) {
		if (entity.getMainHandItem().isEmpty()) return;
		entity.stopUsingItem();
		if (entity instanceof ServerPlayer p) CastService.endCharge(p);
	}

	@Override
	public void inventoryTick(ItemStack stack, ServerLevel level, Entity entity, EquipmentSlot slot) {
		// Keep the look in step with the holder's magic (a traded or picked-up book takes its new holder's colour and tier).
		if (entity instanceof Player p && (entity.tickCount + entity.getId()) % STAMP_INTERVAL == 0) {
			MagicData data = MagicAttachments.get(p);
			GrimoireLook.stamp(stack, data.element(), data.level());
		}
	}
}
