package local.carter.office.magic.tree;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.spell.SpellIds;

import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Every element spell has a full tree, and the cost table follows the plan (levels, ultimates ×1.5, boss items ultimate-only). */
class TreesTest {
	@BeforeAll
	static void boot() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void everyElementSpellHasATree() {
		assertEquals(30, Trees.all().size());
		for (Element e : Element.values()) {
			for (int slot = 0; slot <= Slots.ULTIMATE; slot++) {
				Identifier id = SpellIds.at(e, slot);
				SpellTree t = Trees.get(id);
				assertNotNull(t, "no tree for " + id);
				assertEquals(slot, t.slot(), id + ": slot");
				Set<String> names = new HashSet<>();
				for (Fork f : List.of(t.three().get(0), t.three().get(1), t.five().get(0), t.five().get(1))) {
					assertTrue(names.add(f.name()), id + ": fork name " + f.name() + " used twice");
					assertTrue(f.name().matches("[a-z_]+"), id + ": fork name " + f.name());
				}
				assertFalse(t.stats().isEmpty(), id + ": stat lines");
				boolean knob = t.stats().stream().anyMatch(s -> s.key().equals(t.knob()));
				assertTrue(knob, id + ": its size knob " + t.knob() + " has a stat line");
			}
		}
		assertNull(Trees.get(SpellIds.RECALL), "Recall has no tree");
	}

	@Test
	void statLinesComputeAtEveryRankAndFork() {
		for (SpellTree t : Trees.all()) {
			for (int rank = 0; rank <= 5; rank++) {
				for (int f3 = 1; f3 <= 2; f3++) {
					for (int f5 = 1; f5 <= 2; f5++) {
						SpellRank r = new SpellRank(t.spell(), rank, f3, f5);
						for (SpellTree.Stat s : t.stats()) {
							double v = s.value().at(r, 10);
							assertTrue(Double.isFinite(v) && v >= 0, t.spell() + " " + s.key() + " at " + r + ": " + v);
						}
					}
				}
			}
		}
	}

	@Test
	void ranksGrowTheKnob() {
		for (SpellTree t : Trees.all()) {
			SpellTree.Stat knob = t.stats().stream().filter(s -> s.key().equals(t.knob())).findFirst().orElseThrow();
			double r0 = knob.value().at(SpellRank.none(t.spell()), 10);
			double r2 = knob.value().at(new SpellRank(t.spell(), 2, 0, 0), 10);
			assertTrue(r2 >= r0, t.spell() + ": rank II's " + t.knob() + " " + r2 + " < rank 0's " + r0);
		}
	}

	@Test
	void costsFollowThePlan() {
		Set<Item> ultimateOnly = Set.of(Items.NETHER_STAR, Items.HEART_OF_THE_SEA, Items.HEAVY_CORE, Items.TRIDENT, Items.NETHERITE_INGOT,
				Items.DRAGON_BREATH);
		for (Element e : Element.values()) {
			for (boolean ult : new boolean[] {false, true}) {
				for (int rank = 1; rank <= 5; rank++) {
					RankCost.Cost c = RankCost.of(e, ult, rank);
					assertEquals((ult ? RankCost.ULTIMATE_LEVELS : RankCost.LEVELS)[rank - 1], c.levels());
					assertFalse(c.lines().isEmpty(), e + " " + rank);
					for (RankCost.Line l : c.lines()) {
						assertTrue(l.count() > 0);
						for (Item i : l.items()) {
							if (ultimateOnly.contains(i)) assertTrue(ult && rank == 5, e + ": " + i + " outside the ultimate's V");
						}
					}
				}
			}
			assertTrue(RankCost.materials(e).size() >= 5, e + " materials");
		}
		for (int i = 0; i < 5; i++) {
			assertEquals((int) Math.ceil(RankCost.LEVELS[i] * 1.5), RankCost.ULTIMATE_LEVELS[i], "ultimates ×1.5 rounded up");
		}
	}
}
