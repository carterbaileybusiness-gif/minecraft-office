package local.carter.office.magic.tree;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.core.ChargeMath;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.tree.SpellTree.Stat;
import local.carter.office.magic.tree.SpellTree.Stat.Unit;

import net.minecraft.resources.Identifier;

/**
 * Every element spell's {@link SpellTree} (Recall has none), defined per element in {@link FireTrees} … {@link ShadowTrees}
 * (the fork list of the plan, {@code docs/superpowers/plans/2026-10-24-spell-skill-tree.md}). Built on first use.
 */
public final class Trees {
	private static @Nullable Map<Identifier, SpellTree> byId;

	private Trees() {}

	public static @Nullable SpellTree get(Identifier spell) {
		return map().get(spell);
	}

	/** Every tree, element by element in slot order. */
	public static List<SpellTree> all() {
		return List.copyOf(map().values());
	}

	private static synchronized Map<Identifier, SpellTree> map() {
		if (byId == null) {
			Map<Identifier, SpellTree> m = new LinkedHashMap<>();
			for (List<SpellTree> element : List.of(FireTrees.all(), WaterTrees.all(), WindTrees.all(), LightningTrees.all(),
					EarthTrees.all(), ShadowTrees.all())) {
				for (SpellTree t : element) {
					if (m.put(t.spell(), t) != null) throw new IllegalStateException("two trees for " + t.spell());
				}
			}
			byId = m;
		}
		return byId;
	}

	// ---- helpers for the element files ----

	static SpellTree tree(Identifier spell, int slot, String knob, Fork a3, Fork b3, Fork a5, Fork b5, Stat... stats) {
		return new SpellTree(spell, slot, knob, List.of(a3, b3), List.of(a5, b5), List.of(stats));
	}

	/** {@code base × P} at a tap (an ultimate at its charge 1) for grimoire level {@code level}, times the rank's damage. */
	static double damage(double base, int slot, SpellRank r, int level) {
		return base * ChargeMath.power(slot == Slots.ULTIMATE ? 1 : 0, level) * r.mul(Knob.DAMAGE);
	}

	static Stat stat(String key, Unit unit, Stat.Value v) {
		return new Stat(key, unit, v);
	}

	/** The usual damage line: {@code base × P × share(rank)}. */
	static Stat hit(double base, int slot, java.util.function.ToDoubleFunction<SpellRank> share) {
		return new Stat("damage", Unit.DAMAGE, (r, lv) -> damage(base, slot, r, lv) * share.applyAsDouble(r));
	}

	static Stat hit(double base, int slot) {
		return hit(base, slot, r -> 1);
	}

	/** The size knob line: {@code base × mul(SIZE)} (at a tap), capped at {@code cap}. */
	static Stat size(String key, Unit unit, double base, double cap) {
		return new Stat(key, unit, (r, lv) -> Math.min(cap, base * r.mul(Knob.SIZE)));
	}
}
