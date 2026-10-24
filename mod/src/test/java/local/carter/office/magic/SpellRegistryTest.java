package local.carter.office.magic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;

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
 * {@code overrideForTesting} (restored on close).
 */
class SpellRegistryTest {
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
		try (AutoCloseable a = SpellRegistry.overrideForTesting(s0);
				AutoCloseable b = SpellRegistry.overrideForTesting(s1);
				AutoCloseable c = SpellRegistry.overrideForTesting(s2);
				AutoCloseable r = SpellRegistry.overrideForTesting(recall)) {
			Spell[] wheel = SpellRegistry.wheel(Element.FIRE);
			assertEquals(6, wheel.length);
			assertSame(s0, wheel[0]);
			assertSame(s1, wheel[1]);
			assertSame(s2, wheel[2]);
			assertNull(wheel[3]);
			assertNull(wheel[4]);
			assertSame(recall, wheel[5]);
			assertSame(recall, SpellRegistry.wheel(Element.WATER)[5], "Recall is on every wheel");

			MagicData lv2 = MagicData.EMPTY.withElement(Optional.of(Element.FIRE)).withLevel(2);
			assertEquals(List.of(s0, s1, recall), SpellRegistry.unlocked(lv2));
			assertEquals(List.of(s0, s1, s2, recall), SpellRegistry.unlocked(lv2.withLevel(3)));
			assertEquals(List.of(recall), SpellRegistry.unlocked(MagicData.EMPTY), "unbound: Recall only");
			assertEquals(Optional.of(s1), SpellRegistry.get(s1.id()));
			assertEquals(Optional.of(recall), SpellRegistry.at(Element.EARTH, Slots.RECALL));
		}
		assertTrue(SpellRegistry.get(s1.id()).isEmpty(), "override removed on close");
		assertNull(SpellRegistry.wheel(Element.FIRE)[1]);
		assertNull(SpellRegistry.wheel(Element.FIRE)[5]);
	}

	@Test void overridesRestoreInAnyOrder() throws Exception {
		Fake base = fake("earth_four_base", Element.EARTH, 4);
		SpellRegistry.register(base);
		Fake x = fake("earth_four_x", Element.EARTH, 4);
		Fake y = fake("earth_four_y", Element.EARTH, 4);
		AutoCloseable ax = SpellRegistry.overrideForTesting(x);
		assertEquals(Optional.of(x), SpellRegistry.at(Element.EARTH, 4));
		assertTrue(SpellRegistry.get(base.id()).isEmpty(), "a displaced spell is hidden");
		AutoCloseable ay = SpellRegistry.overrideForTesting(y);
		assertEquals(Optional.of(y), SpellRegistry.at(Element.EARTH, 4));
		ax.close();
		assertEquals(Optional.of(y), SpellRegistry.at(Element.EARTH, 4), "closing an older override keeps the newer one");
		assertTrue(SpellRegistry.get(x.id()).isEmpty());
		ay.close();
		assertEquals(Optional.of(base), SpellRegistry.at(Element.EARTH, 4));
		assertEquals(Optional.of(base), SpellRegistry.get(base.id()));
	}

	@Test void overrideById() throws Exception {
		Fake base = fake("lightning_by_id", Element.LIGHTNING, 3);
		SpellRegistry.register(base);
		Fake moved = fake("lightning_by_id", Element.LIGHTNING, 1);
		try (AutoCloseable c = SpellRegistry.overrideForTesting(moved)) {
			assertEquals(Optional.of(moved), SpellRegistry.get(base.id()));
			assertEquals(Optional.of(moved), SpellRegistry.at(Element.LIGHTNING, 1));
			assertTrue(SpellRegistry.at(Element.LIGHTNING, 3).isEmpty(), "the original slot is vacated while overridden");
		}
		assertEquals(Optional.of(base), SpellRegistry.at(Element.LIGHTNING, 3));
	}

	@Test void costWrapsSlots() {
		int[] c = Slots.baseCost(3);
		assertEquals(new SpellCost(c[0], c[1], c[2], c[3]), SpellCost.forSlot(3));
		assertEquals(SpellCost.forSlot(4), fake("cost_probe", Element.WATER, 4).cost());
		assertFalse(fake("cost_probe", Element.WATER, 4).managesOwnCooldown());
	}
}
