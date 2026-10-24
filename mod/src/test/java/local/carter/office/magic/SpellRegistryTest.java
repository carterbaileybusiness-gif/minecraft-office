package local.carter.office.magic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellCost;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.SpellRegistry;

import net.minecraft.resources.Identifier;

/**
 * The registry is global, so each test uses its own ids and element slots, and anything shared goes through
 * {@code overrideForTesting} (restored on close), scoped to a player UUID of the test's own.
 */
class SpellRegistryTest {
	private final UUID me = UUID.randomUUID();
	private final UUID other = UUID.randomUUID();

	private AutoCloseable mine(Spell s) {
		return SpellRegistry.overrideForTesting(s, me::equals);
	}

	private record Fake(Identifier id, @Nullable Element element, int slot) implements Spell {
		@Override public CastOutcome cast(CastContext ctx) { return CastOutcome.fizzle("magic.office.busy"); }
	}

	private static Fake fake(String path, @Nullable Element e, int slot) {
		return new Fake(Identifier.fromNamespaceAndPath("office-test", path), e, slot);
	}

	@Test void duplicateIdThrows() {
		SpellRegistry.register(fake("dup_id", Element.SHADOW, 2));
		assertThrows(IllegalArgumentException.class, () -> SpellRegistry.register(fake("dup_id", Element.SHADOW, 3)));
		assertTrue(SpellRegistry.get(Identifier.fromNamespaceAndPath("office-test", "dup_id")).isPresent());
		assertTrue(SpellRegistry.at(Element.SHADOW, 3).isEmpty(), "the refused spell is not registered");
	}

	@Test void duplicateElementSlotThrows() {
		SpellRegistry.register(fake("fire_zero_a", Element.FIRE, 0));
		assertThrows(IllegalArgumentException.class, () -> SpellRegistry.register(fake("fire_zero_b", Element.FIRE, 0)));
		assertTrue(SpellRegistry.get(Identifier.fromNamespaceAndPath("office-test", "fire_zero_b")).isEmpty());
	}

	@Test void slotOutOfRangeThrows() {
		assertThrows(IllegalArgumentException.class, () -> SpellRegistry.register(fake("bad_slot", Element.WIND, 6)));
	}

	@Test void wheelAndUnlocked() throws Exception {
		Fake s0 = fake("probe_s0", Element.FIRE, 0);
		Fake s1 = fake("probe_s1", Element.FIRE, 1);
		Fake s2 = fake("probe_s2", Element.FIRE, 2);
		Fake recall = new Fake(SpellIds.RECALL, null, Slots.RECALL);
		try (AutoCloseable a = mine(s0);
				AutoCloseable b = mine(s1);
				AutoCloseable c = mine(s2);
				AutoCloseable r = mine(recall)) {
			Spell[] wheel = SpellRegistry.wheel(me, Element.FIRE);
			assertEquals(6, wheel.length);
			assertSame(s0, wheel[0]);
			assertSame(s1, wheel[1]);
			assertSame(s2, wheel[2]);
			assertNull(wheel[3]);
			assertNull(wheel[4]);
			assertSame(recall, wheel[5]);
			assertSame(recall, SpellRegistry.wheel(me, Element.WATER)[5], "Recall is on every wheel");

			MagicData lv2 = MagicData.EMPTY.withElement(Optional.of(Element.FIRE)).withLevel(2);
			assertEquals(List.of(s0, s1, recall), SpellRegistry.unlocked(me, lv2));
			assertEquals(List.of(s0, s1, s2, recall), SpellRegistry.unlocked(me, lv2.withLevel(3)));
			assertEquals(List.of(recall), SpellRegistry.unlocked(me, MagicData.EMPTY), "unbound: Recall only");
			assertEquals(Optional.of(s1), SpellRegistry.get(me, s1.id()));
			assertEquals(Optional.of(recall), SpellRegistry.at(me, Element.EARTH, Slots.RECALL));
			assertTrue(SpellRegistry.get(s1.id()).isEmpty(), "no player: no override");
			assertTrue(SpellRegistry.get(other, s1.id()).isEmpty(), "another player: no override");
			assertNull(SpellRegistry.wheel(other, Element.FIRE)[1]);
		}
		assertTrue(SpellRegistry.get(me, s1.id()).isEmpty(), "override removed on close");
		assertNull(SpellRegistry.wheel(me, Element.FIRE)[1]);
		assertNull(SpellRegistry.wheel(me, Element.FIRE)[5]);
	}

	@Test void overridesRestoreInAnyOrder() throws Exception {
		Fake base = fake("earth_four_base", Element.EARTH, 4);
		SpellRegistry.register(base);
		Fake x = fake("earth_four_x", Element.EARTH, 4);
		Fake y = fake("earth_four_y", Element.EARTH, 4);
		AutoCloseable ax = mine(x);
		assertEquals(Optional.of(x), SpellRegistry.at(me, Element.EARTH, 4));
		assertTrue(SpellRegistry.get(me, base.id()).isEmpty(), "a displaced spell is hidden from the owner");
		assertEquals(Optional.of(base), SpellRegistry.get(base.id()), "...and from no one else");
		assertEquals(Optional.of(base), SpellRegistry.at(other, Element.EARTH, 4));
		AutoCloseable ay = mine(y);
		assertEquals(Optional.of(y), SpellRegistry.at(me, Element.EARTH, 4));
		ax.close();
		assertEquals(Optional.of(y), SpellRegistry.at(me, Element.EARTH, 4), "closing an older override keeps the newer one");
		assertTrue(SpellRegistry.get(me, x.id()).isEmpty());
		ay.close();
		ay.close(); // closing twice is harmless
		assertEquals(Optional.of(base), SpellRegistry.at(me, Element.EARTH, 4));
		assertEquals(Optional.of(base), SpellRegistry.get(me, base.id()));
	}

	@Test void overrideById() throws Exception {
		Fake base = fake("lightning_by_id", Element.LIGHTNING, 3);
		SpellRegistry.register(base);
		Fake moved = fake("lightning_by_id", Element.LIGHTNING, 1);
		try (AutoCloseable c = mine(moved)) {
			assertEquals(Optional.of(moved), SpellRegistry.get(me, base.id()));
			assertEquals(Optional.of(moved), SpellRegistry.at(me, Element.LIGHTNING, 1));
			assertTrue(SpellRegistry.at(me, Element.LIGHTNING, 3).isEmpty(), "the original slot is vacated while overridden");
			assertEquals(Optional.of(base), SpellRegistry.get(base.id()), "by id without a player: the registered spell");
			assertEquals(Optional.of(base), SpellRegistry.at(other, Element.LIGHTNING, 3));
			assertTrue(SpellRegistry.all().contains(base) && !SpellRegistry.all().contains(moved), "all(): registered spells only");
		}
		assertEquals(Optional.of(base), SpellRegistry.at(me, Element.LIGHTNING, 3));
	}

	@Test void overridesAreScopedToTheirOwners() throws Exception {
		Fake base = fake("wind_two_base", Element.WIND, 2);
		SpellRegistry.register(base);
		Fake a = fake("wind_two_a", Element.WIND, 2);
		Fake b = fake("wind_two_b", Element.WIND, 2);
		UUID late = UUID.randomUUID();
		java.util.Set<UUID> owners = java.util.concurrent.ConcurrentHashMap.newKeySet();
		owners.add(me);
		try (AutoCloseable ca = SpellRegistry.overrideForTesting(a, owners::contains);
				AutoCloseable cb = SpellRegistry.overrideForTesting(b, other::equals)) {
			assertEquals(Optional.of(a), SpellRegistry.at(me, Element.WIND, 2));
			assertEquals(Optional.of(b), SpellRegistry.at(other, Element.WIND, 2), "the newer override is someone else's");
			assertEquals(Optional.of(base), SpellRegistry.at(Element.WIND, 2));
			assertEquals(Optional.of(base), SpellRegistry.at(late, Element.WIND, 2));
			assertTrue(SpellRegistry.get(me, b.id()).isEmpty(), "another owner's probe is invisible by id");
			owners.add(late); // a player joining the owners later sees the override
			assertEquals(Optional.of(a), SpellRegistry.at(late, Element.WIND, 2));
		}
		assertEquals(Optional.of(base), SpellRegistry.at(me, Element.WIND, 2));
	}

	@Test void costWrapsSlots() {
		int[] c = Slots.baseCost(3);
		assertEquals(new SpellCost(c[0], c[1], c[2], c[3]), SpellCost.forSlot(3));
		assertEquals(SpellCost.forSlot(4), fake("cost_probe", Element.WATER, 4).cost());
		assertFalse(fake("cost_probe", Element.WATER, 4).managesOwnCooldown());
	}
}
