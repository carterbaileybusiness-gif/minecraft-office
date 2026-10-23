package local.carter.office.hologram;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.core.component.DataComponents;

/** First-join kit: 4 Hologram Emitters and the "Office Handbook" written book. */
public final class StarterKit {
	public static final int EMITTERS = 4;
	public static final String HANDBOOK_TITLE = "Office Handbook";
	public static final String HANDBOOK_AUTHOR = "Minecraft Office";

	private StarterKit() {}

	/** Gives the kit the first time this player (by UUID) is seen on this world; returns whether it was given. */
	public static boolean giveIfFirstJoin(final ServerPlayer player) {
		if (!HologramIndex.get(player.level().getServer()).markStarterKitGiven(player.getUUID())) {
			return false;
		}
		give(player, new ItemStack(OfficeBlocks.HOLOGRAM_EMITTER_ITEM, EMITTERS));
		give(player, handbook());
		return true;
	}

	/** Into the inventory, or dropped at the player's feet if it is full. */
	public static void give(final ServerPlayer player, final ItemStack stack) {
		if (!player.getInventory().add(stack) && !stack.isEmpty()) {
			player.drop(stack, false, Prediction.SERVER_ONLY);
		}
	}

	public static ItemStack handbook() {
		ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
		List<Filterable<Component>> pages = new ArrayList<>();
		for (String[] page : PAGES) {
			pages.add(Filterable.passThrough(Component.empty()
					.append(Component.literal(page[0]).withStyle(ChatFormatting.BOLD))
					.append(Component.literal("\n\n" + page[1]))));
		}
		book.set(DataComponents.WRITTEN_BOOK_CONTENT,
				new WrittenBookContent(Filterable.passThrough(HANDBOOK_TITLE), HANDBOOK_AUTHOR, 0, pages, true));
		return book;
	}

	/** {title, body} per page; kept short enough to fit a written-book page. */
	static final String[][] PAGES = {
		{"Office Handbook", "Hologram Emitters put your real computer screen into the world.\n\n"
				+ "You get 4. Type /emitter for more (up to 8 holograms each)."},
		{"Placing", "Put an emitter on a wall: the screen hangs flat on it, like a painting.\n"
				+ "On the floor: it floats above, facing you. Under a ceiling: it hangs below.\n\n"
				+ "Only you can resize, change or remove your holograms."},
		{"Sizing", "Hold an emitter and look at your screen: glowing corners appear.\n"
				+ "Hold right-click on a corner and drag, or just scroll.\n\n"
				+ "Shift + hold right-click to move it (scroll turns floor screens)."},
		{"Settings", "Your first hologram mirrors your screen (minus Minecraft); extra ones are new screens.\n\n"
				+ "Right-click the emitter:\n- Shape: 16:9, 21:9 or 9:16\n- Shows: Mirror, Extend or Window\n- Remove"},
		{"Privacy", "Also in the emitter menu:\n- Public: anyone nearby sees it\n- Private: only you; others see frosted glass\n"
				+ "- Show to...: friends you pick\n\nPrivate screens never leave your computer."},
		{"Working", "Click your hologram to zoom in. Your mouse and keyboard now drive your real computer.\n\n"
				+ "Double-tap Esc to come back. Backup: Ctrl+Alt+Cmd+Esc (Mac), Ctrl+Alt+Shift+Esc (Windows)."},
		{"Magic", "Magic is normal survival: craft a table, a wand and runes. No commands.\n\n"
				+ "Recipe book (JEI): hover an item and press R to see how it's made, U to see what it's used for."},
		{"1. Wand", "Novice Wand: 1 coal + 1 stick (coal top-right, stick bottom-left).\n\n"
				+ "Better: Arcane (amethyst + gold), Fire (gunpowder + gold), Frost (snowball + iron).\n\nRight-click to cast."},
		{"2. Runes", "Spells use runes like a bow uses arrows.\n\n1 stone (cobblestone, blackstone...) + amethyst (Arcane), "
				+ "gunpowder or coal (Fire) or a snowball (Frost) = 2 runes. A Crafting Altar makes more (see JEI)."},
		{"3. The Table", "Spell Binding Table: 1 book, 2 amethyst, 1 gold ingot, 3 polished diorite.\n\n"
				+ "Book top middle, amethyst either side of the gold, diorite along the bottom. Put bookshelves around it. "
				+ "Village gazebos have one."},
		{"4. Spell Book", "Put a book into the table and pick a path: Arcane, Fire or Frost. It costs a little XP "
				+ "(about 1 level).\n\nIf it asks for more than a book, check JEI (press R on the table)."},
		{"5. Equip It", "Wear the spell book in its own slot (look for the extra slot in your inventory) and hold a wand "
				+ "or staff to cast the book's spells."},
		{"6. Dress Up", "Wizard robes (lapis + wool) make spells stronger and faster. A Wizard Staff (1 quartz, 2 sticks) "
				+ "beats a wand.\n\nWizard Merchant villagers sell wands, staves and robes for emeralds."},
		{"7. Level Up", "Come back to the table with XP to bind more spells. Look for spell enchantments: Spell Infinity "
				+ "casts without runes.\n\nWizard buildings in villages have loot."},
		{"Also", "Talk: press V for voice chat; friends nearby hear you (check its settings for the talk key).\n\n"
				+ "Claim your office: press ' and claim the chunks around it. Party friends can still build."},
		{"Travel", "A Warp Stone (4 amethyst, 4 ender pearls, 1 gold ingot) turns stone bricks into a Waystone "
				+ "(see JEI).\n\n"
				+ "Waystones you've visited let you teleport between them. Some sit in the wild."},
	};
}
