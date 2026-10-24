package local.carter.office.magic.tree;

import java.util.List;

import local.carter.office.magic.core.Slots;

import net.minecraft.resources.Identifier;

/**
 * One spell's tree: its size knob ({@code knob}: the stat key ranks I, II and IV grow, see {@link SpellRank#bumps}), the two
 * options of its rank III fork ({@code three}) and of its rank V fork ({@code five}), and the stat lines the Skill tree shows
 * before → after ({@code stats}; mana and cooldown are added by the screen from {@code SpellCost}). The spells read the same
 * numbers through {@code ctx.rank()}, so what the panel shows is what the cast does.
 */
public record SpellTree(Identifier spell, int slot, String knob, List<Fork> three, List<Fork> five, List<Stat> stats) {
	public SpellTree {
		if (three.size() != 2 || five.size() != 2) throw new IllegalArgumentException(spell + ": a fork has two options");
		three = List.copyOf(three);
		five = List.copyOf(five);
		stats = List.copyOf(stats);
	}

	public boolean ultimate() {
		return slot == Slots.ULTIMATE;
	}

	/** The options of the fork at {@code rank} (3 or 5). */
	public List<Fork> forks(int rank) {
		if (rank == 3) return three;
		if (rank == 5) return five;
		throw new IllegalArgumentException("rank " + rank + " is no fork");
	}

	/**
	 * A stat line: lang {@code stat.office.<key>}, shown in {@code unit}, worth {@code value(rank, grimoireLevel)} at a tap. A line
	 * the fork changes appears on that fork's card.
	 */
	public record Stat(String key, Unit unit, Value value) {
		public enum Unit { DAMAGE, BLOCKS, SECONDS, COUNT, PERCENT, DEGREES }

		@FunctionalInterface
		public interface Value {
			double at(SpellRank rank, int level);
		}
	}
}
