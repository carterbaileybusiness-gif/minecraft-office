package local.carter.office.hologram;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.core.component.DataComponents;

/** First-join kit: 4 Hologram Emitters and the "Office Handbook" written book; {@code /handbook} refreshes the book. */
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

	public enum HandbookResult { GIVEN, UPDATED, ALREADY_CURRENT }

	/** {@code /handbook}: anyone can run it. */
	public static void registerCommand(final CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("handbook").executes(ctx -> {
			CommandSourceStack source = ctx.getSource();
			HandbookResult result = giveOrUpdateHandbook(source.getPlayerOrException());
			String message = switch (result) {
				case GIVEN -> "Here's the Office Handbook.";
				case UPDATED -> "Updated your Office Handbook.";
				case ALREADY_CURRENT -> "You already have the latest Office Handbook.";
			};
			source.sendSuccess(() -> Component.literal(message), false);
			return 1;
		}));
	}

	/**
	 * Replaces every outdated copy of the handbook (same title, different pages) in place. With none outdated, gives a
	 * fresh copy unless the player already holds a current one.
	 */
	public static HandbookResult giveOrUpdateHandbook(final ServerPlayer player) {
		Replaced done = replaceOutdated(player.getInventory());
		if (done.replaced()) {
			return HandbookResult.UPDATED;
		}
		if (done.hasCurrent()) {
			return HandbookResult.ALREADY_CURRENT;
		}
		give(player, handbook());
		return HandbookResult.GIVEN;
	}

	/**
	 * On join: replaces outdated handbooks anywhere in the inventory (main, armour, off hand) and says so in chat. Never
	 * gives a copy. Returns whether anything was replaced (and the message sent).
	 */
	public static boolean refreshOnJoin(final ServerPlayer player) {
		if (!replaceOutdated(player.getInventory()).replaced()) {
			return false;
		}
		player.sendSystemMessage(Component.literal("Your Office Handbook was updated."));
		return true;
	}

	private record Replaced(boolean replaced, boolean hasCurrent) {}

	/** Replaces every outdated copy (same title, different pages) in place; reports whether it did and whether a current one is held. */
	private static Replaced replaceOutdated(final Inventory inv) {
		List<Filterable<Component>> current = handbook().get(DataComponents.WRITTEN_BOOK_CONTENT).pages();
		boolean replaced = false;
		boolean hasCurrent = false;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack held = inv.getItem(i);
			WrittenBookContent content = held.get(DataComponents.WRITTEN_BOOK_CONTENT);
			if (held.is(Items.WRITTEN_BOOK) && content != null && HANDBOOK_TITLE.equals(content.title().raw())) {
				if (content.pages().equals(current)) {
					hasCurrent = true;
				} else {
					inv.setItem(i, handbook());
					replaced = true;
				}
			}
		}
		return new Replaced(replaced, hasCurrent);
	}

	/** Body of the handbook page with this title (what follows the bold title and blank line). */
	public static String pageBody(final String title) {
		for (String[] page : PAGES) {
			if (page[0].equals(title)) {
				return page[1];
			}
		}
		throw new IllegalArgumentException("no handbook page titled " + title);
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

	/** {title, body} per page; kept short enough to fit a written-book page (at most 14 lines). */
	static final String[][] PAGES = {
		{"Office Handbook", "Hologram Emitters put your real computer screen into the world.\n\n"
				+ "You get 4. Type /emitter for more (up to 8 holograms each)."},
		{"Placing", "Put an emitter on a wall: the screen hangs flat on it, like a painting.\n"
				+ "On the floor: it floats above, facing you. Under a ceiling: it hangs below.\n\n"
				+ "Only you can resize, change or remove your holograms."},
		{"Sizing", "Hold an emitter and look at your screen: glowing corners appear.\n"
				+ "Hold right-click on a corner and drag, or just scroll.\n\n"
				+ "Shift + hold right-click to move it (scroll turns floor screens)."},
		{"Settings", "Your first hologram is your own screen (Mirror, starts Private); each next is a new screen (Extend).\n"
				+ "Right-click the emitter:\n- Shape: 16:9, 21:9 or 9:16\n- Shows: Extend, Window or Mirror\n- Remove"},
		{"Privacy", "Also in the emitter menu:\n- Public: anyone nearby sees it\n- Private: only you; others see frosted glass\n"
				+ "- Show to...: friends you pick\nPrivate screens never leave your computer."},
		{"Working", "Mac: click your screen to enter work mode. Your mouse becomes the Mac's mouse, your view follows it, "
				+ "keys type into the app.\nHold the mouse button to drag windows onto your other screens; WASD walks you meanwhile."},
		{"Working 2", "Esc comes back.\nExtend screens have a Dock.\n\nWindows: click to zoom in; double-tap Esc."},
		{"Magic: Grimoire", "Craft a Grimoire: a book, a feather and an iron ingot, any order.\n"
				+ "Hold it in your main hand and right-click to open it.\n"
				+ "First time, choose your element: Fire, Water, Wind, Lightning, Earth or Shadow. It's for good."},
		{"Rebinding", "A Rebinding Seal (nether star + book) lets you choose your element again."},
		{"Casting", "Grimoire in your OFF hand, main hand empty.\n"
				+ "Tap right-click: quick cast. Hold it and the circle grows; release for a stronger cast.\n"
				+ "Shift + scroll or hold R (wheel) picks spells.\nHolding the Grimoire shows mana on the XP bar."},
		{"Spells", "Casting levels up your Grimoire and unlocks your element's 5 spells.\n"
				+ "The ultimate asks for hand signs: press the arrows shown before time runs out.\n"
				+ "Big spells change the land, but never inside someone else's claim."},
		{"Recall", "Shift + cast Recall to mark home (anywhere, any dimension).\n\n"
				+ "Cast Recall to channel for 3 s and go home. Taking damage cancels it. 60 s cooldown, no mana."},
		{"Travel", "Open your Grimoire → Friends → click a friend; they accept; 3 s later you're there.\n\n"
				+ "Shares Recall's cooldown.\n/magic travel off to refuse requests."},
		{"Also", "Talk: press V for voice chat; friends nearby hear you (check its settings for the talk key).\n\n"
				+ "Claim your office: press ' and claim the chunks around it. Party friends can still build."},
	};
}
