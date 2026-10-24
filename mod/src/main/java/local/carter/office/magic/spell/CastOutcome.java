package local.carter.office.magic.spell;

import local.carter.office.magic.net.FxEvent;

/** What a {@link Spell#cast} did: cast (send this fx, spend the cost) or fizzle (spend nothing, show the message). */
public sealed interface CastOutcome {
	record Cast(FxEvent fx) implements CastOutcome {}

	/** {@code messageKey} is a lang key shown on the action bar ({@code magic.office.busy} makes the result BUSY). */
	record Fizzle(String messageKey) implements CastOutcome {}

	static CastOutcome cast(FxEvent fx) {
		return new Cast(fx);
	}

	static CastOutcome fizzle(String messageKey) {
		return new Fizzle(messageKey);
	}
}
