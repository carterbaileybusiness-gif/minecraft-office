package local.carter.office.magic.tree;

/**
 * What a rank or a fork changes on a spell ({@link SpellRank#mul}, {@link SpellRank#add}). Every spell honours {@link #DAMAGE}
 * (it is folded into the cast's power), {@link #MANA} and {@link #COOLDOWN} (in {@code CastService}) and {@link #SIZE} (its one
 * size knob, {@link SpellTree#knob}); the others only where its own forks use them.
 */
public enum Knob {
	DAMAGE,
	SIZE,
	MANA,
	COOLDOWN,
	DURATION,
	COUNT,
	KNOCK,
	RANGE,
	SPEED
}
