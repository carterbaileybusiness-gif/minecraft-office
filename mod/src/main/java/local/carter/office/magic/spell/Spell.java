package local.carter.office.magic.spell;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.core.Element;

import net.minecraft.resources.Identifier;

/**
 * One spell (server side). {@code CastService} has already checked element, unlock, cooldown, mana and XP levels when
 * {@link #cast} runs, and spends them only on {@link CastOutcome#cast}. A spell must decide to fizzle <b>before</b> any side
 * effect: a fizzle spends nothing.
 */
public interface Spell {
	Identifier id();

	/** Null for spells every element has (Recall). */
	@Nullable Element element();

	/** Wheel slot 0..5 ({@code core.Slots}). */
	int slot();

	default SpellCost cost() {
		return SpellCost.forSlot(slot());
	}

	/** True when the spell writes its own {@code readyAt} (Recall); {@code CastService} then never sets its cooldown. */
	default boolean managesOwnCooldown() {
		return false;
	}

	CastOutcome cast(CastContext ctx);
}
