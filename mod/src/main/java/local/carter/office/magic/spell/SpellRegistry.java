package local.carter.office.magic.spell;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.data.MagicData;

import net.minecraft.resources.Identifier;

/**
 * Every spell, by id and by wheel position. An element spell sits at (element, slot); a spell with a null element (Recall)
 * sits at that slot on every element's wheel. Filled once at init (both sides) by the {@code <Element>Spells.register()} calls.
 *
 * <p>{@link #overrideForTesting} lays a spell over the registry until closed: it takes its (element, slot) and hides any
 * registered spell with the same id. Overrides may nest and close in any order; the newest open one wins. GameTests running
 * at the same time share the registry, so concurrent probes should use distinct ids.
 */
public final class SpellRegistry {
	private record Key(@Nullable Element element, int slot) {}

	private record Override(Spell spell, Key key) {}

	private static final Map<Identifier, Spell> BY_ID = new LinkedHashMap<>();
	private static final Map<Key, Spell> BY_KEY = new HashMap<>();
	/** Open overrides, oldest first. */
	private static final List<Override> OVERRIDES = new ArrayList<>();

	private SpellRegistry() {}

	private static Key key(Spell s) {
		if (s.slot() < 0 || s.slot() >= Slots.WHEEL_SLICES) throw new IllegalArgumentException("slot " + s.slot() + " of " + s.id());
		return new Key(s.element(), s.slot());
	}

	/** @throws IllegalArgumentException on a duplicate id, a taken (element, slot) or a slot outside 0..5 */
	public static synchronized void register(Spell s) {
		Key k = key(s);
		if (BY_ID.containsKey(s.id())) throw new IllegalArgumentException("duplicate spell id " + s.id());
		Spell taken = BY_KEY.get(k);
		if (taken != null) throw new IllegalArgumentException(s.id() + ": slot " + k.slot() + " of " + k.element() + " is taken by " + taken.id());
		BY_ID.put(s.id(), s);
		BY_KEY.put(k, s);
	}

	/** Open overrides, newest first, keeping only the newest per id. */
	private static List<Override> effective() {
		List<Override> out = new ArrayList<>();
		Set<Identifier> seen = new HashSet<>();
		for (int i = OVERRIDES.size() - 1; i >= 0; i--) {
			Override o = OVERRIDES.get(i);
			if (seen.add(o.spell().id())) out.add(o);
		}
		return out;
	}

	private static boolean hidden(Spell base, List<Override> live) {
		Key k = key(base);
		for (Override o : live) if (o.spell().id().equals(base.id()) || o.key().equals(k)) return true;
		return false;
	}

	private static @Nullable Spell resolve(Key k, List<Override> live) {
		for (Override o : live) if (o.key().equals(k)) return o.spell();
		Spell base = BY_KEY.get(k);
		return base != null && !hidden(base, live) ? base : null;
	}

	private static @Nullable Spell resolveAt(@Nullable Element e, int slot, List<Override> live) {
		Spell s = e == null ? null : resolve(new Key(e, slot), live);
		return s != null ? s : resolve(new Key(null, slot), live);
	}

	public static synchronized Optional<Spell> get(Identifier id) {
		List<Override> live = effective();
		for (Override o : live) if (o.spell().id().equals(id)) return Optional.of(o.spell());
		Spell base = BY_ID.get(id);
		return base != null && !hidden(base, live) ? Optional.of(base) : Optional.empty();
	}

	/** The spell on {@code e}'s wheel at {@code slot} (an element spell, else an every-element one such as Recall). */
	public static synchronized Optional<Spell> at(Element e, int slot) {
		return Optional.ofNullable(resolveAt(e, slot, effective()));
	}

	/** {@code e}'s wheel: length 6, index = slot, null where nothing is registered. */
	public static synchronized @Nullable Spell[] wheel(Element e) {
		List<Override> live = effective();
		Spell[] out = new Spell[Slots.WHEEL_SLICES];
		for (int slot = 0; slot < out.length; slot++) out[slot] = resolveAt(e, slot, live);
		return out;
	}

	/** Registered spells the player has unlocked, in slot order (so Recall, slot 5, is last). Unbound: every-element spells only. */
	public static synchronized List<Spell> unlocked(MagicData d) {
		List<Override> live = effective();
		Element e = d.element().orElse(null);
		List<Spell> out = new ArrayList<>();
		for (int slot = 0; slot < Slots.WHEEL_SLICES; slot++) {
			Spell s = resolveAt(e, slot, live);
			if (s != null && d.level() >= Slots.unlockLevel(slot)) out.add(s);
		}
		return out;
	}

	/** Every visible spell: registered ones (minus hidden) then open overrides. */
	public static synchronized List<Spell> all() {
		List<Override> live = effective();
		List<Spell> out = new ArrayList<>();
		for (Spell s : BY_ID.values()) if (!hidden(s, live)) out.add(s);
		for (int i = live.size() - 1; i >= 0; i--) out.add(live.get(i).spell());
		return out;
	}

	/** Lays {@code s} over the registry until the returned handle is closed (closing twice is harmless). Tests only. */
	public static synchronized AutoCloseable overrideForTesting(Spell s) {
		Override o = new Override(s, key(s));
		OVERRIDES.add(o);
		return () -> {
			synchronized (SpellRegistry.class) {
				OVERRIDES.remove(o);
			}
		};
	}
}
