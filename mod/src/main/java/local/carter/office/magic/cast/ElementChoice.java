package local.carter.office.magic.cast;

import java.util.Optional;

import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.net.MagicServer;
import local.carter.office.magic.spell.SpellIds;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/** Binding an element (from the choice screen's {@code ChooseElement}). */
public final class ElementChoice {
	private ElementChoice() {}

	/** Binds {@code e} if the player is unbound: selects the first spell, plays the enchanting sound, shows the title. */
	public static boolean choose(ServerPlayer p, Element e) {
		MagicData d = MagicAttachments.get(p);
		if (d.element().isPresent()) return false;
		MagicAttachments.set(p, d.withElement(Optional.of(e)).withSelected(Optional.of(SpellIds.at(e, Slots.SMALL_A))));
		p.level().playSound(null, p.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.PLAYERS, 1f, 1f);
		String name = "element.office." + e.key();
		MagicServer.sender().title(p, Component.translatable("magic.office.bound", Component.translatable(name)),
				Component.translatable(name + ".motto"));
		return true;
	}
}
