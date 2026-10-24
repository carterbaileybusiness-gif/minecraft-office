package local.carter.office.magic.data;

import com.mojang.serialization.Codec;

import local.carter.office.OfficeMod;
import local.carter.office.magic.core.ManaMath;
import local.carter.office.magic.item.GrimoireItem;
import local.carter.office.magic.item.GrimoireLook;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

/**
 * Player attachments: {@link #MAGIC} (element, level, selection, home, cooldowns) and {@link #MANA}. Both persist, survive
 * death and sync to their own player only. Read with {@link #get}/{@link #mana} on either side; write on the server only.
 */
public final class MagicAttachments {
	public static final int START_MANA = 100;

	public static final AttachmentType<MagicData> MAGIC = AttachmentRegistry.<MagicData>builder()
			.persistent(MagicData.CODEC)
			.copyOnDeath()
			.initializer(() -> MagicData.EMPTY)
			.syncWith(MagicData.STREAM_CODEC, AttachmentSyncPredicate.targetOnly())
			.buildAndRegister(OfficeMod.id("magic"));

	public static final AttachmentType<Integer> MANA = AttachmentRegistry.<Integer>builder()
			.persistent(Codec.INT)
			.copyOnDeath()
			.initializer(() -> START_MANA)
			.syncWith(ByteBufCodecs.VAR_INT, AttachmentSyncPredicate.targetOnly())
			.buildAndRegister(OfficeMod.id("mana"));

	private MagicAttachments() {}

	/** Forces class init (registration) from the mod initializer. */
	public static void init() {}

	public static MagicData get(Player p) {
		return p.getAttachedOrElse(MAGIC, MagicData.EMPTY);
	}

	/** Stores {@code d}, clamps mana to the new maximum and re-stamps the grimoires in both hands. */
	public static void set(ServerPlayer p, MagicData d) {
		p.setAttached(MAGIC, d);
		int max = ManaMath.max(d.level());
		if (mana(p) > max) p.setAttached(MANA, max);
		for (InteractionHand hand : InteractionHand.values()) {
			ItemStack s = p.getItemInHand(hand);
			if (GrimoireItem.isGrimoire(s)) GrimoireLook.stamp(s, d.element(), d.level());
		}
	}

	public static int mana(Player p) {
		return p.getAttachedOrElse(MANA, START_MANA);
	}

	/** Sets mana, clamped to 0..{@link ManaMath#max} for the player's level. */
	public static void setMana(ServerPlayer p, int mana) {
		p.setAttached(MANA, Math.clamp(mana, 0, ManaMath.max(get(p).level())));
	}
}
