package local.carter.office.magic.tree;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import local.carter.office.magic.core.Element;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * What a rank costs: XP levels ({@link #LEVELS}, ultimates ×1.5 rounded up: {@link #ULTIMATE_LEVELS}) and the element's
 * materials, rising with the rank. The last material of each chain (nether star, heart of the sea, heavy core, trident or echo
 * shard, netherite ingot, dragon's breath) is for the ultimate's rank V only. Only plain stacks count ({@link #plain}: not
 * enchanted, renamed or damaged), so an enchanted trident or a named item is never eaten.
 */
public final class RankCost {
	/** XP levels for ranks I–V (index 0 = rank I). */
	public static final int[] LEVELS = {2, 3, 5, 7, 10};
	public static final int[] ULTIMATE_LEVELS = {3, 5, 8, 11, 15};

	/** One line of the bill: {@code count} of any of {@code items} together (lightning rods of any oxidation; trident or echo shard). */
	public record Line(List<Item> items, int count) {
		public Line {
			items = List.copyOf(items);
		}

		/** The item shown for the line (the first). */
		public Item icon() {
			return items.getFirst();
		}

		public boolean accepts(ItemStack s) {
			if (s.isEmpty() || !plain(s)) return false;
			for (Item i : items) if (s.is(i)) return true;
			return false;
		}
	}

	/** A whole rank's bill. */
	public record Cost(int levels, List<Line> lines) {
		public Cost {
			lines = List.copyOf(lines);
		}
	}

	private RankCost() {}

	/** The bill for rank {@code rank} (1–5) of a spell of {@code e}; {@code ultimate} for slot 4. */
	public static Cost of(Element e, boolean ultimate, int rank) {
		if (rank < 1 || rank > SpellRank.MAX) throw new IllegalArgumentException("rank " + rank);
		int levels = (ultimate ? ULTIMATE_LEVELS : LEVELS)[rank - 1];
		return new Cost(levels, lines(e, ultimate, rank));
	}

	private static List<Line> lines(Element e, boolean u, int rank) {
		return switch (e) {
			case FIRE -> switch (rank) {
				case 1 -> List.of(line(u ? 9 : 6, Items.BLAZE_POWDER));
				case 2 -> List.of(line(u ? 6 : 4, Items.MAGMA_CREAM));
				case 3 -> List.of(line(u ? 6 : 4, Items.BLAZE_ROD));
				case 4 -> List.of(line(u ? 6 : 4, Items.FIRE_CHARGE), line(u ? 2 : 1, Items.GHAST_TEAR));
				default -> u ? List.of(line(16, Items.BLAZE_ROD), line(1, Items.NETHER_STAR)) : List.of(line(16, Items.BLAZE_ROD));
			};
			case WATER -> switch (rank) {
				case 1 -> List.of(line(u ? 12 : 8, Items.PRISMARINE_SHARD));
				case 2 -> List.of(line(u ? 9 : 6, Items.PRISMARINE_CRYSTALS));
				case 3 -> List.of(line(u ? 3 : 2, Items.NAUTILUS_SHELL));
				case 4 -> List.of(line(u ? 3 : 2, Items.SPONGE));
				default -> u ? List.of(line(1, Items.HEART_OF_THE_SEA), line(4, Items.SPONGE))
						: List.of(line(4, Items.SPONGE), line(4, Items.NAUTILUS_SHELL));
			};
			case WIND -> switch (rank) {
				case 1 -> List.of(line(u ? 12 : 8, Items.FEATHER));
				case 2 -> List.of(line(u ? 3 : 2, Items.PHANTOM_MEMBRANE));
				case 3 -> List.of(line(u ? 5 : 3, Items.BREEZE_ROD));
				case 4 -> List.of(line(u ? 12 : 8, Items.WIND_CHARGE));
				default -> u ? List.of(line(1, Items.HEAVY_CORE), line(16, Items.WIND_CHARGE))
						: List.of(line(6, Items.BREEZE_ROD), line(16, Items.WIND_CHARGE));
			};
			case LIGHTNING -> switch (rank) {
				case 1 -> List.of(line(u ? 12 : 8, Items.COPPER_INGOT));
				case 2 -> List.of(line(u ? 9 : 6, Items.AMETHYST_SHARD));
				case 3 -> List.of(new Line(rods(), u ? 3 : 2));
				case 4 -> List.of(line(u ? 6 : 4, Items.GLOW_INK_SAC));
				default -> u ? List.of(new Line(List.of(Items.TRIDENT, Items.ECHO_SHARD), 1), line(8, Items.GLOW_INK_SAC))
						: List.of(line(8, Items.GLOW_INK_SAC), new Line(rods(), 4));
			};
			case EARTH -> switch (rank) {
				case 1 -> List.of(line(u ? 12 : 8, Items.IRON_INGOT));
				case 2 -> List.of(line(u ? 9 : 6, Items.GOLD_INGOT));
				case 3 -> List.of(line(u ? 3 : 2, Items.DIAMOND));
				case 4 -> List.of(line(u ? 2 : 1, Items.ANCIENT_DEBRIS));
				default -> u ? List.of(line(1, Items.NETHERITE_INGOT), line(3, Items.DIAMOND))
						: List.of(line(1, Items.ANCIENT_DEBRIS), line(3, Items.DIAMOND));
			};
			case SHADOW -> switch (rank) {
				case 1 -> List.of(line(u ? 6 : 4, Items.ENDER_PEARL));
				case 2 -> List.of(line(u ? 12 : 8, Items.OBSIDIAN));
				case 3 -> List.of(line(u ? 2 : 1, Items.ECHO_SHARD));
				case 4 -> List.of(line(u ? 2 : 1, Items.SCULK_CATALYST));
				default -> u ? List.of(line(4, Items.DRAGON_BREATH), line(2, Items.ECHO_SHARD))
						: List.of(line(2, Items.SCULK_CATALYST), line(1, Items.ECHO_SHARD));
			};
		};
	}

	private static Line line(int count, Item item) {
		return new Line(List.of(item), count);
	}

	/** Lightning rods of every oxidation, waxed or not. */
	private static List<Item> rods() {
		return Items.LIGHTNING_ROD.asList();
	}

	/** Every item any rank of {@code e} may take (for the inventory tooltip "Used to rank up … spells"). */
	public static Set<Item> materials(Element e) {
		Set<Item> out = new LinkedHashSet<>();
		for (boolean u : new boolean[] {false, true}) {
			for (int r = 1; r <= SpellRank.MAX; r++) for (Line l : of(e, u, r).lines()) out.addAll(l.items());
		}
		return out;
	}

	/** Not enchanted, renamed or damaged: a stack a rank may take. */
	public static boolean plain(ItemStack s) {
		return !s.isEnchanted() && !s.isDamaged() && !s.has(DataComponents.CUSTOM_NAME);
	}

	/** How many items of {@code line} the inventory holds (plain stacks only). */
	public static int have(Inventory inv, Line line) {
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (line.accepts(s)) n += s.getCount();
		}
		return n;
	}

	/** The lines the inventory is short of, as {line, missing count}. */
	public static List<Shortfall> missing(Inventory inv, Cost cost) {
		List<Shortfall> out = new ArrayList<>();
		for (Line l : cost.lines()) {
			int have = have(inv, l);
			if (have < l.count()) out.add(new Shortfall(l, l.count() - have));
		}
		return out;
	}

	public record Shortfall(Line line, int missing) {}

	/** Takes every line of {@code cost} from the inventory (the caller has checked {@link #missing} is empty). */
	public static void take(Inventory inv, Cost cost) {
		for (Line l : cost.lines()) {
			int left = l.count();
			for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
				ItemStack s = inv.getItem(i);
				if (!l.accepts(s)) continue;
				int n = Math.min(left, s.getCount());
				s.shrink(n);
				left -= n;
			}
		}
		inv.setChanged();
	}
}
