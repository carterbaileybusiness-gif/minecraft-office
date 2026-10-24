package local.carter.office.client.magic.fx;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import net.minecraft.resources.Identifier;

/** Spell id → its visuals. Lanes register theirs from {@code <Element>Fx.register()}; an unknown spell draws {@link DefaultFx}. */
public final class SpellFxRegistry {
	private static final Map<Identifier, SpellFx> FX = new HashMap<>();
	private static final SpellFx DEFAULT = new DefaultFx();

	private SpellFxRegistry() {}

	/** Registers (or replaces) {@code spell}'s visuals. */
	public static synchronized void register(Identifier spell, SpellFx fx) {
		FX.put(Objects.requireNonNull(spell, "spell"), Objects.requireNonNull(fx, "fx"));
	}

	/** {@code spell}'s visuals, or {@link DefaultFx} (a cast circle and an element burst). */
	public static synchronized SpellFx get(Identifier spell) {
		return FX.getOrDefault(spell, DEFAULT);
	}

	public static synchronized boolean has(Identifier spell) {
		return FX.containsKey(spell);
	}
}
