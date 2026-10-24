package local.carter.office.magic.item;

import local.carter.office.magic.cast.ElementChoice;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.net.MagicPayloads.OpenGrimoire;
import local.carter.office.magic.net.MagicServer;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * Rebinding Seal (nether star + book): use it while bound to open the element choice again. Nothing changes yet: one seal is
 * spent, and the new element bound (level, xp, home and cooldowns stay), only when you choose ({@code ElementChoice.rebind});
 * closing the screen keeps both. Passes when you are unbound.
 */
public class RebindingSealItem extends Item {
	public RebindingSealItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		MagicData data = MagicAttachments.get(player);
		if (data.element().isEmpty()) return InteractionResult.PASS;
		if (player instanceof ServerPlayer sp) {
			ElementChoice.beginRebind(sp);
			MagicServer.sender().open(sp, OpenGrimoire.Mode.CHOOSE);
		}
		return InteractionResult.SUCCESS;
	}
}
