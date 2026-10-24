package local.carter.office.magic.spell;

import local.carter.office.magic.core.Slots;

/** Base cost of a cast: mana, cooldown, grimoire xp gained and XP levels (ultimates). */
public record SpellCost(int mana, int cooldownTicks, int xp, int xpLevels) {
	/** The slot's row of {@link Slots#baseCost} (the only cost table). */
	public static SpellCost forSlot(int slot) {
		int[] c = Slots.baseCost(slot);
		return new SpellCost(c[0], c[1], c[2], c[3]);
	}
}
