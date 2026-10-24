package local.carter.office.magic.spell;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.data.MagicData;

import net.minecraft.resources.Identifier;

/**
 * Every spell, by id and by wheel position. An element spell sits at (element, slot); a spell with a null element (Recall)
 * sits at that slot on every element's wheel. Filled once at init (both sides) by the {@code <Element>Spells.register()} calls.
 *
 * <p><b>Test overrides are per player.</b> {@link #overrideForTesting} lays a spell over the registry, until closed, for the
 * players its owner predicate accepts (by UUID), and for no one else. For those players it takes its (element, slot) and
 * hides any registered spell with the same id or key; overrides may nest and close in any order, and the newest open one
 * wins. Lookups that take a player UUID ({@link #get(UUID, Identifier)}, {@link #at(UUID, Element, int)},
 * {@link #wheel(UUID, Element)}, {@link #unlocked(UUID, MagicData)}) see that player's overrides; the ones without
 * ({@link #get(Identifier)}, {@link #at(Element, int)}, {@link #wheel(Element)}, {@link #unlocked(MagicData)}, {@link #all()})
 * see registered spells only, so concurrent GameTests never see each other's probes or lose a real spell to one.
 * Gameplay code with a player at hand must use the player overloads.
 */
public final class SpellRegistry {
	private record Key(@Nullable Element element, int slot) {}

	private record Override(Spell spell, Key key, Predicate<UUID> owners) {}

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

	/** {@code player}'s open overrides, newest first, keeping only the newest per id. None for a null player. */
	private static List<Override> effective(@Nullable UUID player) {
		if (player == null || OVERRIDES.isEmpty()) return List.of();
		List<Override> out = new ArrayList<>();
		Set<Identifier> seen = new HashSet<>();
		for (int i = OVERRIDES.size() - 1; i >= 0; i--) {
			Override o = OVERRIDES.get(i);
			if (o.owners().test(player) && seen.add(o.spell().id())) out.add(o);
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

	/** The registered spell {@code id}; never a test override. */
	public static Optional<Spell> get(Identifier id) {
		return get(null, id);
	}

	/** The spell {@code id} as {@code player} sees it: their newest override with that id, else the registered one unless hidden. */
	public static synchronized Optional<Spell> get(@Nullable UUID player, Identifier id) {
		List<Override> live = effective(player);
		for (Override o : live) if (o.spell().id().equals(id)) return Optional.of(o.spell());
		Spell base = BY_ID.get(id);
		return base != null && !hidden(base, live) ? Optional.of(base) : Optional.empty();
	}

	/** The registered spell on {@code e}'s wheel at {@code slot}; never a test override. */
	public static Optional<Spell> at(Element e, int slot) {
		return at(null, e, slot);
	}

	/** The spell on {@code e}'s wheel at {@code slot} for {@code player} (an element spell, else an every-element one such as Recall). */
	public static synchronized Optional<Spell> at(@Nullable UUID player, Element e, int slot) {
		return Optional.ofNullable(resolveAt(e, slot, effective(player)));
	}

	/** {@code e}'s wheel of registered spells; never a test override. */
	public static @Nullable Spell[] wheel(Element e) {
		return wheel(null, e);
	}

	/** {@code e}'s wheel for {@code player}: length 6, index = slot, null where nothing is registered. */
	public static synchronized @Nullable Spell[] wheel(@Nullable UUID player, Element e) {
		List<Override> live = effective(player);
		Spell[] out = new Spell[Slots.WHEEL_SLICES];
		for (int slot = 0; slot < out.length; slot++) out[slot] = resolveAt(e, slot, live);
		return out;
	}

	/** As {@link #unlocked(UUID, MagicData)} over registered spells only; never a test override. */
	public static List<Spell> unlocked(MagicData d) {
		return unlocked(null, d);
	}

	/** Spells {@code player} (whose data is {@code d}) has unlocked, in slot order (so Recall, slot 5, is last). Unbound: every-element spells only. */
	public static synchronized List<Spell> unlocked(@Nullable UUID player, MagicData d) {
		List<Override> live = effective(player);
		Element e = d.element().orElse(null);
		List<Spell> out = new ArrayList<>();
		for (int slot = 0; slot < Slots.WHEEL_SLICES; slot++) {
			Spell s = resolveAt(e, slot, live);
			if (s != null && d.level() >= Slots.unlockLevel(slot)) out.add(s);
		}
		return out;
	}

	/** Every registered spell, in registration order; never a test override. */
	public static synchronized List<Spell> all() {
		return List.copyOf(BY_ID.values());
	}

	/**
	 * Lays {@code s} over the registry for the players {@code owners} accepts (tested on every lookup, so it may accept players
	 * that join later) until the returned handle is closed (closing twice is harmless). Lookups without a player never see it.
	 * Tests only: GameTests go through {@code MagicTestKit.override}, which scopes to the test's own kit players.
	 */
	public static synchronized AutoCloseable overrideForTesting(Spell s, Predicate<UUID> owners) {
		Override o = new Override(s, key(s), Objects.requireNonNull(owners, "owners"));
		OVERRIDES.add(o);
		return () -> {
			synchronized (SpellRegistry.class) {
				OVERRIDES.removeIf(x -> x == o);
			}
		};
	}
}
