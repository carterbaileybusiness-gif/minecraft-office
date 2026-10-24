package local.carter.office.magic.cast;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.item.MagicItems;
import local.carter.office.magic.net.MagicServer;
import local.carter.office.magic.spell.SpellIds;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;

/**
 * Binding an element (from the choice screen's {@code ChooseElement}): a first binding while unbound, or a rebinding that a
 * Rebinding Seal opened. Using a seal only records a pending rebind ({@link #beginRebind}); nothing changes, and no seal is
 * spent, until the player actually chooses. Closing the screen (Esc) keeps the old element and the seal.
 */
public final class ElementChoice {
	/** How long a seal's open choice screen may still bind (game ticks): 60 s. */
	public static final long REBIND_TICKS = 1200;
	private static final Map<UUID, Long> PENDING = new ConcurrentHashMap<>();

	private ElementChoice() {}

	/** Binds {@code e} if the player is unbound: selects the first spell, plays the enchanting sound, shows the title. */
	public static boolean choose(ServerPlayer p, Element e) {
		MagicData d = MagicAttachments.get(p);
		if (d.element().isPresent()) return false;
		bind(p, d, e);
		return true;
	}

	/** A seal was used: a choice within {@link #REBIND_TICKS} may rebind, spending one seal then. */
	public static void beginRebind(ServerPlayer p) {
		PENDING.put(p.getUUID(), p.level().getGameTime());
	}

	/** True while a seal's rebind is open for {@code p} (expired ones are dropped). */
	public static boolean rebindPending(ServerPlayer p) {
		Long at = PENDING.get(p.getUUID());
		if (at == null) return false;
		long age = p.level().getGameTime() - at;
		if (age < 0 || age > REBIND_TICKS) {
			PENDING.remove(p.getUUID());
			return false;
		}
		return true;
	}

	/** Drops a pending rebind (death, disconnect). */
	public static void forget(ServerPlayer p) {
		PENDING.remove(p.getUUID());
	}

	/**
	 * Rebinds to {@code e} if a seal opened the choice, a seal is still in either hand and {@code e} is not the current
	 * element: consumes exactly one seal, then binds (level, xp, home and cooldowns stay). Returns false, spending nothing,
	 * otherwise (the rebind stays pending).
	 */
	public static boolean rebind(ServerPlayer p, Element e) {
		if (!rebindPending(p)) return false;
		if (MagicAttachments.get(p).element().equals(Optional.of(e))) return false;
		for (InteractionHand hand : InteractionHand.values()) {
			if (p.getItemInHand(hand).is(MagicItems.REBINDING_SEAL)) {
				p.getItemInHand(hand).consume(1, p);
				PENDING.remove(p.getUUID());
				bind(p, MagicAttachments.get(p), e);
				return true;
			}
		}
		return false;
	}

	/** Title: the element's name in its colour; subtitle: "You are bound — <motto>". */
	private static void bind(ServerPlayer p, MagicData d, Element e) {
		MagicAttachments.set(p, d.withElement(Optional.of(e)).withSelected(Optional.of(SpellIds.at(e, Slots.SMALL_A))));
		p.level().playSound(null, p.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.PLAYERS, 1f, 1f);
		String name = "element.office." + e.key();
		MagicServer.sender().title(p, Component.translatable(name).withColor(e.argb() & 0xFFFFFF),
				Component.translatable("magic.office.bound", Component.translatable(name + ".motto")));
	}
}
