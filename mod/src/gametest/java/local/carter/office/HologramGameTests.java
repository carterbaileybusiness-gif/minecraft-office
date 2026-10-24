package local.carter.office;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;
import local.carter.office.core.Mount;
import local.carter.office.core.Privacy;
import local.carter.office.core.Shape;
import local.carter.office.hologram.EmitterCommand;
import local.carter.office.hologram.HologramBlockEntity;
import local.carter.office.hologram.HologramEmitterBlock;
import local.carter.office.hologram.HologramIndex;
import local.carter.office.hologram.OfficeBlocks;
import local.carter.office.hologram.StarterKit;
import local.carter.office.net.HologramActions;
import local.carter.office.net.OfficePayloads;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/** Server GameTests for the hologram emitter: ownership, owner-only control, explosion-proofing, limits, starter kit. */
public class HologramGameTests {
	private static ServerPlayer survivalPlayer(GameTestHelper h, BlockPos standAt) {
		ServerPlayer p = (ServerPlayer) h.makeMockServerPlayer(GameType.SURVIVAL);
		Vec3 v = h.absoluteVec(Vec3.atBottomCenterOf(standAt));
		p.setPos(v.x, v.y, v.z);
		p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(OfficeBlocks.HOLOGRAM_EMITTER_ITEM, 16));
		return p;
	}

	/** Places an emitter at {@code rel} (relative) as {@code player} through the normal BlockItem path. */
	private static void placeAs(GameTestHelper h, ServerPlayer player, BlockPos rel) {
		h.placeAt(player, player.getMainHandItem(), rel.below(), Direction.UP);
	}

	private static HologramBlockEntity place(GameTestHelper h, ServerPlayer player, BlockPos rel) {
		placeAs(h, player, rel);
		h.assertBlockPresent(OfficeBlocks.HOLOGRAM_EMITTER, rel);
		return h.getBlockEntity(rel, HologramBlockEntity.class);
	}

	private static int count(ServerPlayer p, Item item) {
		Inventory inv = p.getInventory();
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(item)) {
				n += s.getCount();
			}
		}
		return n;
	}

	private static int countHandbooks(ServerPlayer p) {
		Inventory inv = p.getInventory();
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			WrittenBookContent book = s.get(DataComponents.WRITTEN_BOOK_CONTENT);
			if (s.is(Items.WRITTEN_BOOK) && book != null && book.title().raw().equals("Office Handbook")) {
				n += s.getCount();
			}
		}
		return n;
	}

	/** Mock players may already have joined with the starter kit; start from a clean slate. */
	private static void clearHandbooks(ServerPlayer p) {
		Inventory inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (inv.getItem(i).is(Items.WRITTEN_BOOK)) {
				inv.setItem(i, ItemStack.EMPTY);
			}
		}
	}

	@GameTest
	public void placeSetsOwner(GameTestHelper h) {
		ServerPlayer a = survivalPlayer(h, new BlockPos(1, 0, 5));
		HologramBlockEntity be = place(h, a, new BlockPos(1, 1, 1));
		HologramSpec s = be.spec();
		h.assertTrue(s != null, "spec set on place");
		h.assertValueEqual(s.owner(), a.getUUID(), "owner");
		h.assertValueEqual(s.shape(), Shape.WIDE_16_9, "shape");
		h.assertValueEqual(s.mode(), Mode.MIRROR, "mode (the first hologram is your own screen, spec 5.10)");
		h.assertValueEqual(s.privacy(), Privacy.PRIVATE, "privacy (a Mirror starts Private: it shows your whole screen)");
		h.assertValueEqual(s.width(), 2.0, "width");
		h.assertValueEqual(s.displaySlot(), 1, "displaySlot");
		h.assertTrue(s.windowKey() == null, "windowKey null");
		h.assertValueEqual(s.offY(), 0.5 + s.height() / 2 + 0.1, "offY");
		h.assertTrue(be.hologramId() != null, "hologramId set");
		h.assertValueEqual(be.ownerName(), a.getName().getString(), "owner name recorded for panels");
		h.assertTrue(!be.ownerName().isEmpty(), "owner name not empty");
		h.assertValueEqual(HologramIndex.get(h.getLevel().getServer()).countOwnedBy(a.getUUID()), 1, "indexed");
		h.succeed();
	}

	/**
	 * Laptop + monitors (spec 5.10): the first hologram mirrors your screen, each further one is Extend on display 1, 2…
	 * Another player's holograms don't count; with no Mirror left, the next one mirrors again.
	 */
	@GameTest
	public void firstHologramMirrorsThenEachNewOneExtends(GameTestHelper h) {
		ServerPlayer a = survivalPlayer(h, new BlockPos(1, 0, 5));
		ServerPlayer b = survivalPlayer(h, new BlockPos(5, 0, 5));
		HologramSpec first = place(h, a, new BlockPos(1, 1, 1)).spec();
		HologramSpec second = place(h, a, new BlockPos(3, 1, 1)).spec();
		HologramSpec third = place(h, a, new BlockPos(5, 1, 1)).spec();
		HologramSpec friends = place(h, b, new BlockPos(7, 1, 1)).spec();
		h.assertValueEqual(first.mode(), Mode.MIRROR, "first: Mirror (the laptop's screen)");
		h.assertValueEqual(second.mode(), Mode.EXTEND, "second: Extend");
		h.assertValueEqual(first.displaySlot(), 1, "first: holds display slot 1 (for a later switch to Extend)");
		h.assertValueEqual(second.displaySlot(), 2, "second: display 2 (no collision with the Mirror's slot)");
		h.assertValueEqual(third.mode(), Mode.EXTEND, "third: Extend");
		h.assertValueEqual(third.displaySlot(), 3, "third: display 3");
		h.assertValueEqual(friends.mode(), Mode.MIRROR, "another player's first hologram mirrors their screen");
		h.assertValueEqual(first.privacy(), Privacy.PRIVATE, "the Mirror starts Private");
		h.assertValueEqual(second.privacy(), Privacy.PUBLIC, "an Extend screen starts Public, as before");
		// Settings can still switch any of them; the index remembers it for the next placement.
		BlockPos firstAbs = h.absolutePos(new BlockPos(1, 1, 1));
		h.assertTrue(HologramActions.apply(a, new OfficePayloads.UpdateHologram(firstAbs, first.shape(), Mode.WINDOW, first.privacy(),
				first.displaySlot(), null)), "switch the Mirror to Window");
		HologramSpec fourth = place(h, a, new BlockPos(1, 1, 3)).spec();
		h.assertValueEqual(fourth.mode(), Mode.MIRROR, "no Mirror left: the next one mirrors again");
		h.assertValueEqual(fourth.displaySlot(), 1, "on the free slot 1 (a Window hologram holds none)");
		HologramSpec fifth = place(h, a, new BlockPos(3, 1, 3)).spec();
		h.assertValueEqual(fifth.mode(), Mode.EXTEND, "fifth: Extend");
		h.assertValueEqual(fifth.displaySlot(), 4, "fifth: display 4 (1 is the Mirror's, 2 and 3 taken)");
		h.succeed();
	}

	/** Switching a screen to Mirror (your whole screen) makes it Private; afterwards it can be made Public on purpose. */
	@GameTest
	public void switchingToMirrorMakesItPrivate(GameTestHelper h) {
		ServerPlayer a = survivalPlayer(h, new BlockPos(1, 0, 5));
		place(h, a, new BlockPos(1, 1, 1)); // the Mirror
		HologramBlockEntity be = place(h, a, new BlockPos(3, 1, 1)); // Extend, Public
		BlockPos abs = h.absolutePos(new BlockPos(3, 1, 1));
		HologramSpec s = be.spec();
		h.assertValueEqual(s.privacy(), Privacy.PUBLIC, "an Extend screen starts Public");
		h.assertTrue(HologramActions.apply(a, new OfficePayloads.UpdateHologram(abs, s.shape(), Mode.MIRROR, Privacy.PUBLIC, s.displaySlot(), null)),
				"switch to Mirror");
		h.assertValueEqual(be.spec().privacy(), Privacy.PRIVATE, "becoming a Mirror makes it Private");
		h.assertTrue(HologramActions.apply(a, new OfficePayloads.UpdateHologram(abs, s.shape(), Mode.MIRROR, Privacy.PUBLIC, s.displaySlot(), null)),
				"make the Mirror Public on purpose");
		h.assertValueEqual(be.spec().privacy(), Privacy.PUBLIC, "an existing Mirror can be made Public");
		h.succeed();
	}

	/** The client's corner drag sends ResizeHologram then MoveHologram (fixed opposite corner); both apply in order. */
	@GameTest
	public void resizeThenMoveKeepsOppositeCorner(GameTestHelper h) {
		ServerPlayer a = survivalPlayer(h, new BlockPos(1, 0, 5));
		BlockPos rel = new BlockPos(1, 1, 1);
		HologramBlockEntity be = place(h, a, rel);
		BlockPos abs = h.absolutePos(rel);
		HologramSpec s = be.spec();
		// Grow 2 -> 3 wide by the bottom-right corner: the top-left corner stays put. right = (cos yaw, 0, sin yaw).
		double rx = Math.cos(Math.toRadians(s.yaw()));
		double rz = Math.sin(Math.toRadians(s.yaw()));
		double newW = 3.0;
		double newH = newW / s.shape().aspect();
		double dw = (newW - s.width()) / 2;
		double offX = s.offX() + rx * dw;
		double offZ = s.offZ() + rz * dw;
		double offY = s.offY() - (newH - s.height()) / 2;
		h.assertTrue(HologramActions.apply(a, new OfficePayloads.ResizeHologram(abs, newW)), "resize applied");
		h.assertTrue(HologramActions.apply(a, new OfficePayloads.MoveHologram(abs, offX, offY, offZ, s.yaw())), "move applied");
		HologramSpec after = be.spec();
		h.assertValueEqual(after.width(), newW, "width");
		// top-left = centre - right * w/2 + up * h/2
		double dx = (after.offX() - rx * after.width() / 2) - (s.offX() - rx * s.width() / 2);
		double dz = (after.offZ() - rz * after.width() / 2) - (s.offZ() - rz * s.width() / 2);
		double dy = (after.offY() + after.height() / 2) - (s.offY() + s.height() / 2);
		h.assertTrue(Math.abs(dx) < 1e-6 && Math.abs(dy) < 1e-6 && Math.abs(dz) < 1e-6,
				"top-left fixed: " + dx + ", " + dy + ", " + dz);
		h.succeed();
	}

	@GameTest
	public void strangerCannotResizeOrConfigure(GameTestHelper h) {
		ServerPlayer a = survivalPlayer(h, new BlockPos(1, 0, 5));
		ServerPlayer b = survivalPlayer(h, new BlockPos(2, 0, 5));
		BlockPos rel = new BlockPos(1, 1, 1);
		HologramBlockEntity be = place(h, a, rel);
		BlockPos abs = h.absolutePos(rel);
		HologramSpec before = be.spec();

		h.assertFalse(HologramActions.apply(b, new OfficePayloads.ResizeHologram(abs, 5.0)), "stranger resize refused");
		h.assertFalse(HologramActions.apply(b, new OfficePayloads.UpdateHologram(abs, Shape.TALL_9_16, Mode.WINDOW,
				Privacy.PRIVATE, 3, "Safari:1")), "stranger update refused");
		h.assertFalse(HologramActions.apply(b, new OfficePayloads.MoveHologram(abs, 3, 3, 3, 90f)), "stranger move refused");
		h.assertValueEqual(be.spec(), before, "spec unchanged");

		// Control: the owner's update does apply.
		h.assertTrue(HologramActions.apply(a, new OfficePayloads.UpdateHologram(abs, Shape.TALL_9_16, Mode.WINDOW,
				Privacy.PRIVATE, 2, "Safari:1")), "owner update applied");
		h.assertValueEqual(be.spec().shape(), Shape.TALL_9_16, "owner shape");
		h.assertValueEqual(be.spec().privacy(), Privacy.PRIVATE, "owner privacy");
		h.succeed();
	}

	@GameTest
	@SuppressWarnings("removal")
	public void strangerCannotBreak(GameTestHelper h) {
		ServerPlayer a = survivalPlayer(h, new BlockPos(1, 0, 5));
		ServerPlayer b = survivalPlayer(h, new BlockPos(2, 0, 5));
		b.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		a.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(OfficeBlocks.HOLOGRAM_EMITTER_ITEM, 1));
		BlockPos rel = new BlockPos(3, 1, 3);
		place(h, a, rel);
		BlockPos abs = h.absolutePos(rel);

		// Survival stranger: mining never progresses.
		h.assertValueEqual(h.getLevel().getBlockState(abs).getDestroyProgress(b, h.getLevel(), abs), 0.0F,
				"stranger destroy progress");
		// Instant break (creative stranger, the path that ignores progress) is cancelled. In-level mock: Fabric's
		// cancel handler resends the block over the player's connection, so it needs one.
		ServerPlayer c = h.makeMockServerPlayerInLevel();
		try {
			h.assertFalse(c.gameMode.destroyBlock(abs), "stranger break refused");
		} finally {
			h.getLevel().getServer().getPlayerList().remove(c);
		}
		h.assertBlockPresent(OfficeBlocks.HOLOGRAM_EMITTER, rel);
		h.assertItemEntityNotPresent(OfficeBlocks.HOLOGRAM_EMITTER_ITEM, rel, 3.0);

		h.assertTrue(h.getLevel().getBlockState(abs).getDestroyProgress(a, h.getLevel(), abs) > 0.0F, "owner can mine");
		h.assertTrue(a.gameMode.destroyBlock(abs), "owner break allowed");
		h.assertBlockNotPresent(OfficeBlocks.HOLOGRAM_EMITTER, rel);
		h.assertItemEntityPresent(OfficeBlocks.HOLOGRAM_EMITTER_ITEM, rel, 2.0);
		h.assertValueEqual(HologramIndex.get(h.getLevel().getServer()).countOwnedBy(a.getUUID()), 0, "unindexed");
		h.succeed();
	}

	@GameTest(maxTicks = 100)
	public void explosionProof(GameTestHelper h) {
		ServerPlayer a = survivalPlayer(h, new BlockPos(1, 0, 6));
		BlockPos rel = new BlockPos(3, 1, 3);
		HologramBlockEntity be = place(h, a, rel);
		HologramSpec before = be.spec();
		BlockPos dirt = new BlockPos(5, 1, 3);
		h.setBlock(dirt, Blocks.DIRT);
		PrimedTnt tnt = h.spawn(EntityTypes.TNT, new BlockPos(4, 1, 3));
		tnt.setFuse(1);
		h.runAfterDelay(20, () -> {
			h.assertBlockNotPresent(Blocks.DIRT, dirt); // proves the blast happened
			h.assertBlockPresent(OfficeBlocks.HOLOGRAM_EMITTER, rel);
			HologramBlockEntity after = h.getBlockEntity(rel, HologramBlockEntity.class);
			h.assertValueEqual(after.spec(), before, "spec intact");
			h.assertValueEqual(after.hologramId(), be.hologramId(), "same hologram");
			h.succeed();
		});
	}

	@GameTest
	public void resizeClamps(GameTestHelper h) {
		ServerPlayer a = survivalPlayer(h, new BlockPos(1, 0, 5));
		BlockPos rel = new BlockPos(1, 1, 1);
		HologramBlockEntity be = place(h, a, rel);
		BlockPos abs = h.absolutePos(rel);
		h.assertTrue(HologramActions.apply(a, new OfficePayloads.ResizeHologram(abs, 40.0)), "owner resize applied");
		h.assertValueEqual(be.spec().width(), 12.0, "clamped to max");
		h.assertTrue(HologramActions.apply(a, new OfficePayloads.ResizeHologram(abs, 0.1)), "owner resize applied");
		h.assertValueEqual(be.spec().width(), 0.5, "clamped to min");
		h.succeed();
	}

	@GameTest
	@SuppressWarnings("removal")
	public void ninthHologramRefused(GameTestHelper h) {
		ServerPlayer a = h.makeMockServerPlayerInLevel(); // real connection, so chat + commands work
		try {
			Vec3 v = h.absoluteVec(Vec3.atBottomCenterOf(new BlockPos(4, 0, 6)));
			a.setPos(v.x, v.y, v.z);
			a.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(OfficeBlocks.HOLOGRAM_EMITTER_ITEM, 64));
			for (int x = 0; x < 8; x++) {
				place(h, a, new BlockPos(x, 1, 1));
			}
			HologramIndex index = HologramIndex.get(h.getLevel().getServer());
			h.assertValueEqual(index.countOwnedBy(a.getUUID()), 8, "eight placed");

			BlockPos ninth = new BlockPos(3, 1, 3);
			placeAs(h, a, ninth);
			h.assertBlockNotPresent(OfficeBlocks.HOLOGRAM_EMITTER, ninth);
			h.assertValueEqual(index.countOwnedBy(a.getUUID()), 8, "still eight");

			int before = count(a, OfficeBlocks.HOLOGRAM_EMITTER_ITEM);
			h.getLevel().getServer().getCommands().performPrefixedCommand(a.createCommandSourceStack(), "emitter");
			h.assertValueEqual(count(a, OfficeBlocks.HOLOGRAM_EMITTER_ITEM), before, "/emitter at 8 gives nothing");
		} finally {
			h.getLevel().getServer().getPlayerList().remove(a);
		}
		h.succeed();
	}

	@GameTest
	@SuppressWarnings("removal")
	public void emitterCommandGivesOne(GameTestHelper h) {
		ServerPlayer a = h.makeMockServerPlayerInLevel();
		try {
			int before = count(a, OfficeBlocks.HOLOGRAM_EMITTER_ITEM);
			h.getLevel().getServer().getCommands().performPrefixedCommand(a.createCommandSourceStack(), "emitter");
			h.assertValueEqual(count(a, OfficeBlocks.HOLOGRAM_EMITTER_ITEM), before + 1, "/emitter gives one");
		} finally {
			h.getLevel().getServer().getPlayerList().remove(a);
		}
		h.succeed();
	}

	@GameTest
	public void starterKitOnce(GameTestHelper h) {
		ServerPlayer p = (ServerPlayer) h.makeMockServerPlayer(GameType.SURVIVAL);
		h.assertTrue(StarterKit.giveIfFirstJoin(p), "first join gives kit");
		h.assertValueEqual(count(p, OfficeBlocks.HOLOGRAM_EMITTER_ITEM), 4, "4 emitters");
		h.assertValueEqual(countHandbooks(p), 1, "1 handbook");
		h.assertFalse(StarterKit.giveIfFirstJoin(p), "second join gives nothing");
		h.assertValueEqual(count(p, OfficeBlocks.HOLOGRAM_EMITTER_ITEM), 4, "still 4 emitters");
		h.assertValueEqual(countHandbooks(p), 1, "still 1 handbook");
		h.succeed();
	}

	@GameTest
	@SuppressWarnings("removal")
	public void handbookCommandGivesOne(GameTestHelper h) {
		ServerPlayer a = h.makeMockServerPlayerInLevel();
		try {
			clearHandbooks(a);
			h.assertValueEqual(countHandbooks(a), 0, "no handbook yet");
			h.getLevel().getServer().getCommands().performPrefixedCommand(a.createCommandSourceStack(), "handbook");
			h.assertValueEqual(countHandbooks(a), 1, "/handbook gives one");
		} finally {
			h.getLevel().getServer().getPlayerList().remove(a);
		}
		h.succeed();
	}

	@GameTest
	@SuppressWarnings("removal")
	public void handbookCommandReplacesOutdatedInPlace(GameTestHelper h) {
		ServerPlayer a = h.makeMockServerPlayerInLevel();
		try {
			clearHandbooks(a);
			ItemStack old = StarterKit.handbook();
			WrittenBookContent fresh = old.get(DataComponents.WRITTEN_BOOK_CONTENT);
			old.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(fresh.title(), fresh.author(), 0,
					List.of(Filterable.passThrough(Component.literal("Wand: craft runes at the Spell Binding Table."))), true));
			a.getInventory().setItem(7, old);
			h.getLevel().getServer().getCommands().performPrefixedCommand(a.createCommandSourceStack(), "handbook");
			h.assertValueEqual(countHandbooks(a), 1, "still exactly one handbook");
			WrittenBookContent now = a.getInventory().getItem(7).get(DataComponents.WRITTEN_BOOK_CONTENT);
			h.assertTrue(now != null && now.pages().equals(fresh.pages()), "outdated copy replaced in the same slot");
		} finally {
			h.getLevel().getServer().getPlayerList().remove(a);
		}
		h.succeed();
	}

	@GameTest
	@SuppressWarnings("removal")
	public void handbookCommandSkipsWhenAlreadyCurrent(GameTestHelper h) {
		ServerPlayer a = h.makeMockServerPlayerInLevel();
		try {
			clearHandbooks(a);
			a.getInventory().setItem(7, StarterKit.handbook());
			h.getLevel().getServer().getCommands().performPrefixedCommand(a.createCommandSourceStack(), "handbook");
			h.assertValueEqual(countHandbooks(a), 1, "no second copy when already current");
			h.assertTrue(StarterKit.giveOrUpdateHandbook(a) == StarterKit.HandbookResult.ALREADY_CURRENT, "reports already current");
			h.assertValueEqual(countHandbooks(a), 1, "still one copy");
		} finally {
			h.getLevel().getServer().getPlayerList().remove(a);
		}
		h.succeed();
	}

	/** An outdated copy of the handbook (same title, other pages). */
	private static ItemStack outdatedHandbook() {
		ItemStack old = StarterKit.handbook();
		WrittenBookContent fresh = old.get(DataComponents.WRITTEN_BOOK_CONTENT);
		old.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(fresh.title(), fresh.author(), 0,
				List.of(Filterable.passThrough(Component.literal("Wand: craft runes at the Spell Binding Table."))), true));
		return old;
	}

	/** The Settings page points at the screen map; no page overflows a written-book page (14 lines of about 19 chars). */
	@GameTest
	public void handbookSettingsMentionsIdentify(GameTestHelper h) {
		h.assertTrue(StarterKit.pageBody("Settings").contains("Identify"), "the Settings page mentions Identify");
		for (String title : StarterKit.pageTitles()) {
			int lines = bookLines(StarterKit.pageBody(title));
			h.assertTrue(lines <= 14, "handbook page " + title + " needs " + lines + " lines (max 14)");
		}
		h.succeed();
	}

	/** Greedy word wrap at 19 characters a line, about what a written-book line holds. */
	private static int bookLines(String body) {
		int lines = 0;
		for (String para : body.split("\n", -1)) {
			int used = 0;
			lines++;
			for (String word : para.split(" ")) {
				int need = used == 0 ? word.length() : used + 1 + word.length();
				if (need > 19 && used > 0) {
					lines++;
					used = word.length();
				} else {
					used = need;
				}
			}
		}
		return lines;
	}

	@GameTest
	@SuppressWarnings("removal")
	public void joinReplacesOutdatedHandbooksAndSaysSo(GameTestHelper h) {
		ServerPlayer a = h.makeMockServerPlayerInLevel();
		try {
			clearHandbooks(a);
			a.getInventory().setItem(7, outdatedHandbook());
			a.setItemInHand(InteractionHand.OFF_HAND, outdatedHandbook());
			h.assertTrue(StarterKit.refreshOnJoin(a), "replaced outdated copies and said so");
			List<Filterable<Component>> current = StarterKit.handbook().get(DataComponents.WRITTEN_BOOK_CONTENT).pages();
			h.assertTrue(a.getInventory().getItem(7).get(DataComponents.WRITTEN_BOOK_CONTENT).pages().equals(current), "slot 7 current");
			h.assertTrue(a.getOffhandItem().get(DataComponents.WRITTEN_BOOK_CONTENT).pages().equals(current),
					"off hand current");
			h.assertValueEqual(countHandbooks(a), 2, "still two handbooks");
		} finally {
			h.getLevel().getServer().getPlayerList().remove(a);
		}
		h.succeed();
	}

	@GameTest
	@SuppressWarnings("removal")
	public void joinWithCurrentOrNoHandbookIsSilent(GameTestHelper h) {
		ServerPlayer a = h.makeMockServerPlayerInLevel();
		try {
			clearHandbooks(a);
			a.getInventory().setItem(7, StarterKit.handbook());
			h.assertFalse(StarterKit.refreshOnJoin(a), "current copy: silent");
			h.assertValueEqual(countHandbooks(a), 1, "still one handbook");
			clearHandbooks(a);
			h.assertFalse(StarterKit.refreshOnJoin(a), "no handbook: silent");
			h.assertValueEqual(countHandbooks(a), 0, "never gives a copy on join");
		} finally {
			h.getLevel().getServer().getPlayerList().remove(a);
		}
		h.succeed();
	}

	@GameTest
	public void handbookIsAboutGrimoireNotWizards(GameTestHelper h) {
		StringBuilder text = new StringBuilder();
		for (Filterable<Component> page : StarterKit.handbook().get(DataComponents.WRITTEN_BOOK_CONTENT).pages()) {
			text.append(page.raw().getString()).append('\n');
		}
		String all = text.toString();
		h.assertFalse(all.contains("Wand"), "no Wand");
		h.assertFalse(all.contains("rune"), "no rune");
		h.assertFalse(all.contains("Rune"), "no Rune");
		h.assertFalse(all.contains("Spell Binding"), "no Spell Binding");
		h.assertTrue(all.contains("Grimoire"), "mentions Grimoire");
		h.succeed();
	}

	@GameTest
	public void specPersistsAcrossSaveLoad(GameTestHelper h) {
		ServerPlayer a = survivalPlayer(h, new BlockPos(1, 0, 5));
		BlockPos rel = new BlockPos(1, 1, 1);
		HologramBlockEntity be = place(h, a, rel);
		BlockPos abs = h.absolutePos(rel);
		java.util.UUID friend = java.util.UUID.randomUUID();
		HologramActions.apply(a, new OfficePayloads.UpdateHologram(abs, Shape.ULTRA_21_9, Mode.WINDOW,
				new Privacy(Privacy.Level.SHOW_TO, java.util.Set.of(friend)), 3, "Safari:42"));
		HologramActions.apply(a, new OfficePayloads.MoveHologram(abs, 0.25, 2.0, -1.5, 90f));
		HologramActions.apply(a, new OfficePayloads.ResizeHologram(abs, 4.5));

		net.minecraft.nbt.CompoundTag tag = be.saveCustomOnly(h.getLevel().registryAccess());
		HologramBlockEntity copy = new HologramBlockEntity(abs, be.getBlockState());
		copy.loadCustomOnly(net.minecraft.world.level.storage.TagValueInput.create(
				net.minecraft.util.ProblemReporter.DISCARDING, h.getLevel().registryAccess(), tag));
		h.assertValueEqual(copy.spec(), be.spec(), "spec round-trips");
		h.assertValueEqual(copy.hologramId(), be.hologramId(), "id round-trips");
		h.assertValueEqual(copy.ownerName(), be.ownerName(), "owner name round-trips");
		h.assertTrue(tag.getCompound("spec").isPresent() && tag.getCompound("spec").get().contains("windowKey"),
				"stored under toMap() keys");
		h.succeed();
	}

	@GameTest
	public void onlyOwnerCanRemoveViaPayload(GameTestHelper h) {
		ServerPlayer a = survivalPlayer(h, new BlockPos(1, 0, 5));
		ServerPlayer b = survivalPlayer(h, new BlockPos(2, 0, 5));
		a.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(OfficeBlocks.HOLOGRAM_EMITTER_ITEM, 1));
		BlockPos rel = new BlockPos(1, 1, 1);
		place(h, a, rel);
		BlockPos abs = h.absolutePos(rel);
		h.assertFalse(HologramActions.apply(b, new OfficePayloads.RemoveHologram(abs)), "stranger remove refused");
		h.assertBlockPresent(OfficeBlocks.HOLOGRAM_EMITTER, rel);
		int before = count(a, OfficeBlocks.HOLOGRAM_EMITTER_ITEM);
		h.assertTrue(HologramActions.apply(a, new OfficePayloads.RemoveHologram(abs)), "owner remove applied");
		h.assertBlockNotPresent(OfficeBlocks.HOLOGRAM_EMITTER, rel);
		h.assertValueEqual(count(a, OfficeBlocks.HOLOGRAM_EMITTER_ITEM), before + 1, "emitter returned to owner");
		h.assertItemEntityNotPresent(OfficeBlocks.HOLOGRAM_EMITTER_ITEM, rel, 3.0);
		h.succeed();
	}

	private static final String LG = "9B2F1C44-5E6A-4D7B-8C9D-0E1F2A3B4C5D";

	/** The spec's {@code mirrorDisplay} as the update tag (what clients receive) carries it, or null. */
	private static String syncedMirrorDisplay(GameTestHelper h, HologramBlockEntity be) {
		CompoundTag spec = be.getUpdateTag(h.getLevel().registryAccess()).getCompound("spec").orElseThrow();
		return spec.getString("mirrorDisplay").orElse(null);
	}

	@GameTest
	public void setMirrorDisplayStoresIt(GameTestHelper h) {
		ServerPlayer a = survivalPlayer(h, new BlockPos(1, 0, 5));
		ServerPlayer b = survivalPlayer(h, new BlockPos(2, 0, 5));
		HologramBlockEntity be = place(h, a, new BlockPos(1, 1, 1));
		BlockPos abs = be.getBlockPos();
		h.assertTrue(HologramActions.apply(a, new OfficePayloads.SetMirrorDisplay(abs, LG)), "owner sets the monitor");
		h.assertValueEqual(be.spec().mirrorDisplay(), LG, "stored");
		h.assertValueEqual(syncedMirrorDisplay(h, be), LG, "synced in the update tag");
		h.assertTrue(HologramActions.apply(a, new OfficePayloads.SetMirrorDisplay(abs, null)), "owner clears it");
		h.assertTrue(be.spec().mirrorDisplay() == null, "null = the main display");
		h.assertTrue(syncedMirrorDisplay(h, be) == null, "no key when null");
		h.assertFalse(HologramActions.apply(a, new OfficePayloads.SetMirrorDisplay(abs, "x".repeat(65))), "65 characters refused");
		h.assertFalse(HologramActions.apply(a, new OfficePayloads.SetMirrorDisplay(abs, "LG\nUUID")), "control character refused");
		h.assertFalse(HologramActions.apply(a, new OfficePayloads.SetMirrorDisplay(abs, "")), "empty refused");
		h.assertFalse(HologramActions.apply(b, new OfficePayloads.SetMirrorDisplay(abs, LG)), "non-owner refused");
		h.assertTrue(be.spec().mirrorDisplay() == null, "nothing changed");
		h.assertTrue(HologramActions.apply(a, new OfficePayloads.SetMirrorDisplay(abs, "\\\\.\\DISPLAY2")), "a Windows device name");
		h.succeed();
	}

	@GameTest
	public void updateAndMoveKeepMirrorDisplay(GameTestHelper h) {
		ServerPlayer a = survivalPlayer(h, new BlockPos(1, 0, 5));
		HologramBlockEntity be = place(h, a, new BlockPos(1, 1, 1));
		BlockPos abs = be.getBlockPos();
		HologramSpec s = be.spec();
		h.assertTrue(HologramActions.apply(a, new OfficePayloads.UpdateHologram(abs, s.shape(), Mode.MIRROR, Privacy.PRIVATE, 1, null)),
				"becomes a Mirror");
		h.assertTrue(HologramActions.apply(a, new OfficePayloads.SetMirrorDisplay(abs, LG)), "picks LG");
		h.assertTrue(HologramActions.apply(a, new OfficePayloads.UpdateHologram(abs, Shape.TALL_9_16, Mode.MIRROR, Privacy.PUBLIC, 1, null)),
				"shape and privacy change");
		h.assertValueEqual(be.spec().mirrorDisplay(), LG, "kept by UpdateHologram");
		h.assertTrue(HologramActions.apply(a, new OfficePayloads.MoveHologram(abs, 0.5, 2.0, -1.0, 45f)), "move");
		h.assertValueEqual(be.spec().mirrorDisplay(), LG, "kept by MoveHologram");
		h.assertTrue(HologramActions.apply(a, new OfficePayloads.ResizeHologram(abs, 4.0)), "resize");
		h.assertValueEqual(be.spec().mirrorDisplay(), LG, "kept by ResizeHologram");
		h.succeed();
	}

	@GameTest
	public void mirrorDisplaySurvivesSaveLoad(GameTestHelper h) {
		ServerPlayer a = survivalPlayer(h, new BlockPos(1, 0, 5));
		HologramBlockEntity be = place(h, a, new BlockPos(1, 1, 1));
		h.assertTrue(HologramActions.apply(a, new OfficePayloads.SetMirrorDisplay(be.getBlockPos(), LG)), "picks LG");
		CompoundTag tag = be.saveCustomOnly(h.getLevel().registryAccess());
		HologramBlockEntity copy = new HologramBlockEntity(be.getBlockPos(), be.getBlockState());
		copy.loadCustomOnly(net.minecraft.world.level.storage.TagValueInput.create(
				net.minecraft.util.ProblemReporter.DISCARDING, h.getLevel().registryAccess(), tag));
		h.assertValueEqual(copy.spec().mirrorDisplay(), LG, "read back");
		h.assertValueEqual(copy.spec(), be.spec(), "whole spec round-trips");
		h.succeed();
	}

	private static boolean setPrivacy(ServerPlayer owner, HologramBlockEntity be, Privacy privacy) {
		HologramSpec s = be.spec();
		return HologramActions.apply(owner, new OfficePayloads.UpdateHologram(be.getBlockPos(), s.shape(), Mode.EXTEND, privacy, 1, null));
	}

	private static OfficePayloads.IdentifyHolograms identifyOne(HologramBlockEntity be, String label) {
		return new OfficePayloads.IdentifyHolograms(List.of(new OfficePayloads.HologramLabel(be.getBlockPos(), label)));
	}

	@GameTest
	@SuppressWarnings("removal")
	public void identifyRelaysToViewersWhoMaySee(GameTestHelper h) {
		ServerPlayer a = survivalPlayer(h, new BlockPos(1, 0, 5));
		ServerPlayer v1 = h.makeMockServerPlayerInLevel();
		ServerPlayer v2 = h.makeMockServerPlayerInLevel();
		java.util.Map<UUID, List<OfficePayloads.HologramLabel>> got = new java.util.concurrent.ConcurrentHashMap<>();
		AtomicLong now = new AtomicLong(5_000_000);
		HologramActions.setClock(now::get);
		HologramActions.setIdentifyBroadcast((viewer, labels) -> got.put(viewer.getUUID(), labels));
		try {
			HologramBlockEntity be = place(h, a, new BlockPos(1, 1, 1));
			h.assertTrue(setPrivacy(a, be, Privacy.PUBLIC), "Public Extend");
			h.assertTrue(HologramActions.apply(a, identifyOne(be, "1")), "Identify accepted");
			List<OfficePayloads.HologramLabel> want = List.of(new OfficePayloads.HologramLabel(be.getBlockPos(), "1"));
			h.assertValueEqual(got.get(v1.getUUID()), want, "Public: viewer 1 sees it");
			h.assertValueEqual(got.get(v2.getUUID()), want, "Public: viewer 2 sees it");
			h.assertFalse(got.containsKey(a.getUUID()), "the owner's client draws its own");

			got.clear();
			now.addAndGet(HologramActions.IDENTIFY_MIN_INTERVAL_MS);
			h.assertTrue(setPrivacy(a, be, Privacy.PRIVATE), "Private");
			h.assertTrue(HologramActions.apply(a, identifyOne(be, "1")), "accepted (the owner still sees their own)");
			h.assertFalse(got.containsKey(v1.getUUID()) || got.containsKey(v2.getUUID()), "Private: nobody else");

			got.clear();
			now.addAndGet(HologramActions.IDENTIFY_MIN_INTERVAL_MS);
			h.assertTrue(setPrivacy(a, be, new Privacy(Privacy.Level.SHOW_TO, java.util.Set.of(v1.getUUID()))), "Show to viewer 1");
			h.assertTrue(HologramActions.apply(a, identifyOne(be, "1")), "accepted");
			h.assertValueEqual(got.get(v1.getUUID()), want, "viewer 1 may see it");
			h.assertFalse(got.containsKey(v2.getUUID()), "viewer 2 may not");
		} finally {
			HologramActions.setIdentifyBroadcast(null);
			HologramActions.setClock(null);
			h.getLevel().getServer().getPlayerList().remove(v1);
			h.getLevel().getServer().getPlayerList().remove(v2);
		}
		h.succeed();
	}

	@GameTest
	public void identifyRejectsBadInput(GameTestHelper h) {
		ServerPlayer a = survivalPlayer(h, new BlockPos(1, 0, 5));
		ServerPlayer b = survivalPlayer(h, new BlockPos(3, 0, 5));
		AtomicLong now = new AtomicLong(9_000_000);
		HologramActions.setClock(now::get);
		HologramActions.setIdentifyBroadcast((viewer, labels) -> {});
		try {
			HologramBlockEntity mine = place(h, a, new BlockPos(1, 1, 1));
			HologramBlockEntity theirs = place(h, b, new BlockPos(3, 1, 1));
			for (String bad : new String[] {"AB", "LG", "5", "", "a"}) {
				h.assertFalse(HologramActions.apply(a, identifyOne(mine, bad)), "label '" + bad + "' dropped");
			}
			h.assertFalse(HologramActions.apply(a, identifyOne(theirs, "1")), "someone else's hologram dropped");
			h.assertTrue(HologramActions.apply(a, identifyOne(mine, "B")), "a letter on my own hologram");
			now.addAndGet(HologramActions.IDENTIFY_MIN_INTERVAL_MS - 1);
			h.assertFalse(HologramActions.apply(a, identifyOne(mine, "B")), "a second Identify within 1000 ms ignored");
			now.addAndGet(1);
			h.assertTrue(HologramActions.apply(a, identifyOne(mine, "2")), "a second later it works again");
		} finally {
			HologramActions.setIdentifyBroadcast(null);
			HologramActions.setClock(null);
		}
		h.succeed();
	}

	/** Places an emitter by clicking face {@code face} of the block at {@code clicked} (relative); returns its BE. */
	private static HologramBlockEntity placeOnFace(GameTestHelper h, ServerPlayer player, BlockPos clicked, Direction face) {
		// Like a real click on that face (GameTestHelper.placeAt clicks the air block in front, i.e. a "replace").
		BlockPos abs = h.absolutePos(clicked);
		Vec3 onFace = Vec3.atCenterOf(abs).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
		player.getMainHandItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, new BlockHitResult(onFace, face, abs, false)));
		BlockPos rel = clicked.relative(face);
		h.assertBlockPresent(OfficeBlocks.HOLOGRAM_EMITTER, rel);
		return h.getBlockEntity(rel, HologramBlockEntity.class);
	}

	/** Where the content plane is along the wall normal, in absolute world coordinates. */
	private static double planeCoordinate(HologramBlockEntity be, Direction facing) {
		BlockPos p = be.getBlockPos();
		HologramSpec s = be.spec();
		return switch (facing.getAxis()) {
			case X -> p.getX() + 0.5 + s.offX();
			case Z -> p.getZ() + 0.5 + s.offZ();
			default -> throw new IllegalArgumentException();
		};
	}

	@GameTest
	public void wallPlacementIsFlushAndFacesOut(GameTestHelper h) {
		ServerPlayer a = survivalPlayer(h, new BlockPos(2, 0, 6));
		for (Direction face : new Direction[] {Direction.SOUTH, Direction.EAST, Direction.NORTH, Direction.WEST}) {
			BlockPos wall = new BlockPos(2, 2, 2);
			h.setBlock(wall, Blocks.STONE);
			HologramBlockEntity be = placeOnFace(h, a, wall, face);
			BlockState state = be.getBlockState();
			h.assertValueEqual(state.getValue(HologramEmitterBlock.FACE), AttachFace.WALL, face + " attaches to the wall");
			h.assertValueEqual(state.getValue(HologramEmitterBlock.FACING), face, face + " faces out of the wall");
			h.assertValueEqual(HologramEmitterBlock.mount(state), Mount.WALL, "mount");
			HologramSpec s = be.spec();
			h.assertValueEqual(s.width(), 2.0, "default width");
			h.assertTrue(Math.abs(s.yaw() - face.toYRot()) < 1e-4, face + ": hologram faces out, yaw " + s.yaw());
			// Flush: content plane 0.03 off the clicked wall face, on the outside.
			BlockPos wallAbs = h.absolutePos(wall);
			double wallSurface = face.getAxis() == Direction.Axis.X ? wallAbs.getX() + 0.5 + 0.5 * face.getStepX()
					: wallAbs.getZ() + 0.5 + 0.5 * face.getStepZ();
			double outward = (planeCoordinate(be, face) - wallSurface) * (face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1);
			h.assertTrue(Math.abs(outward - Mount.WALL_GAP) < 1e-9, face + ": plane " + outward + " off the wall");
			// Centred on the clicked face: no sideways or vertical offset.
			double sideways = face.getAxis() == Direction.Axis.X ? s.offZ() : s.offX();
			h.assertTrue(Math.abs(sideways) < 1e-9 && Math.abs(s.offY()) < 1e-9, face + ": centred on the clicked face");
			// The thin mount sits against the wall, behind the content plane (never pokes through it).
			var bounds = state.getShape(h.getLevel(), be.getBlockPos()).bounds();
			double mountFront = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? bounds.max(face.getAxis())
					: 1.0 - bounds.min(face.getAxis());
			h.assertTrue(mountFront > 0 && mountFront < Mount.WALL_GAP, face + ": mount front " + mountFront + " is behind the hologram");
			h.destroyBlock(wall.relative(face));
			h.setBlock(wall, Blocks.AIR);
		}
		h.succeed();
	}

	@GameTest
	public void ceilingPlacementHangsBelow(GameTestHelper h) {
		ServerPlayer a = survivalPlayer(h, new BlockPos(1, 0, 5));
		BlockPos ceiling = new BlockPos(1, 4, 1);
		h.setBlock(ceiling, Blocks.STONE);
		HologramBlockEntity be = placeOnFace(h, a, ceiling, Direction.DOWN);
		h.assertValueEqual(be.getBlockState().getValue(HologramEmitterBlock.FACE), AttachFace.CEILING, "ceiling mount");
		HologramSpec s = be.spec();
		double top = s.offY() + s.height() / 2;
		h.assertTrue(top < 0.5 - 1.0 / 16.0, "top edge " + top + " below the mount");
		h.assertTrue(s.offY() < 0, "hangs below the emitter centre");
		h.succeed();
	}

	@GameTest
	public void floorPlacementKeepsTheProjector(GameTestHelper h) {
		ServerPlayer a = survivalPlayer(h, new BlockPos(1, 0, 5));
		HologramBlockEntity be = place(h, a, new BlockPos(1, 1, 1));
		h.assertValueEqual(be.getBlockState().getValue(HologramEmitterBlock.FACE), AttachFace.FLOOR, "floor");
		h.assertValueEqual(be.spec().offY(), 0.5 + be.spec().height() / 2 + 0.1, "floats above as before");
		h.succeed();
	}

	/** Resize and move on a wall hologram: it never leaves the wall plane (server-enforced). */
	@GameTest
	public void wallResizeStaysOnPlane(GameTestHelper h) {
		ServerPlayer a = survivalPlayer(h, new BlockPos(2, 0, 6));
		BlockPos wall = new BlockPos(2, 2, 2);
		h.setBlock(wall, Blocks.STONE);
		HologramBlockEntity be = placeOnFace(h, a, wall, Direction.SOUTH);
		BlockPos abs = be.getBlockPos();
		double plane = planeCoordinate(be, Direction.SOUTH);
		float yaw = be.spec().yaw();

		h.assertTrue(HologramActions.apply(a, new OfficePayloads.ResizeHologram(abs, 5.0)), "resize applied");
		h.assertValueEqual(be.spec().width(), 5.0, "width");
		h.assertTrue(Math.abs(planeCoordinate(be, Direction.SOUTH) - plane) < 1e-9, "resize stays on the wall plane");
		// A corner drag's recentre (in-plane) is kept; an off-plane offset or a turn is pulled back.
		h.assertTrue(HologramActions.apply(a, new OfficePayloads.MoveHologram(abs, 1.25, -0.75, 2.0, 90f)), "move applied");
		HologramSpec s = be.spec();
		h.assertTrue(Math.abs(planeCoordinate(be, Direction.SOUTH) - plane) < 1e-9, "move can't pull it off the wall: " + s.offZ());
		h.assertTrue(Math.abs(s.offX() - 1.25) < 1e-9 && Math.abs(s.offY() + 0.75) < 1e-9, "slides along the wall");
		h.assertTrue(Math.abs(s.yaw() - yaw) < 1e-4, "still faces out of the wall");
		// Shape change (keeps the bottom edge) also stays on the plane.
		h.assertTrue(HologramActions.apply(a, new OfficePayloads.UpdateHologram(abs, Shape.TALL_9_16, Mode.EXTEND, Privacy.PUBLIC, 1, null)),
				"update applied");
		h.assertTrue(Math.abs(planeCoordinate(be, Direction.SOUTH) - plane) < 1e-9, "shape change stays on the plane");
		h.succeed();
	}

	/** Emitters saved before the attachment property existed load as FLOOR (default state). */
	@GameTest
	public void legacyEmitterStateDefaultsToFloor(GameTestHelper h) {
		BlockState def = OfficeBlocks.HOLOGRAM_EMITTER.defaultBlockState();
		h.assertValueEqual(def.getValue(HologramEmitterBlock.FACE), AttachFace.FLOOR, "default state");
		// The old chunk palette stored the property-less state as just its id.
		BlockState fromName = BlockState.CODEC.parse(NbtOps.INSTANCE, StringTag.valueOf("office:hologram_emitter")).getOrThrow();
		h.assertValueEqual(fromName.getValue(HologramEmitterBlock.FACE), AttachFace.FLOOR, "id-only state");
		CompoundTag idOnly = new CompoundTag();
		idOnly.putString("id", "office:hologram_emitter");
		BlockState fromTag = BlockState.CODEC.parse(NbtOps.INSTANCE, idOnly).getOrThrow();
		h.assertValueEqual(fromTag.getValue(HologramEmitterBlock.FACE), AttachFace.FLOOR, "{id} state");
		h.assertValueEqual(NbtUtils.readBlockState(h.getLevel().holderLookup(Registries.BLOCK), idOnly), def, "NbtUtils");
		h.assertValueEqual(HologramEmitterBlock.mount(fromTag), Mount.FLOOR, "mount");
		// A legacy hologram (spec placed by Task 6, any yaw/offset) is untouched by the server's mount rule.
		ServerPlayer a = survivalPlayer(h, new BlockPos(1, 0, 5));
		HologramBlockEntity be = place(h, a, new BlockPos(1, 1, 1));
		h.assertTrue(HologramActions.apply(a, new OfficePayloads.MoveHologram(be.getBlockPos(), 0.5, 2.0, -1.5, 45f)), "move");
		h.assertValueEqual(be.spec().offZ(), -1.5, "floor holograms move freely");
		h.succeed();
	}

	@GameTest
	public void workingSetIsBroadcastAndClearedOnDisconnect(GameTestHelper h) {
		ServerPlayer a = survivalPlayer(h, new BlockPos(1, 0, 5));
		String teamBefore = String.valueOf(a.getTeam());
		List<List<UUID>> sent = new ArrayList<>();
		HologramActions.setWorkingBroadcast((server, working) -> sent.add(working));
		AtomicLong now = new AtomicLong(1_000_000);
		HologramActions.setClock(now::get);
		try {
			h.assertTrue(HologramActions.apply(a, new OfficePayloads.SetWorking(true)), "working");
			now.addAndGet(1000);
			h.assertTrue(HologramActions.isWorking(a.getUUID()), "server knows");
			h.assertTrue(sent.size() == 1 && sent.get(0).contains(a.getUUID()), "broadcast with the player: " + sent);
			h.assertTrue(HologramActions.workingPayload().players().contains(a.getUUID()), "what a joining player is sent");
			h.assertFalse(HologramActions.apply(a, new OfficePayloads.SetWorking(true)), "repeat is a no-op");
			now.addAndGet(1000);
			h.assertValueEqual(sent.size(), 1, "no broadcast without a change");
			h.assertValueEqual(String.valueOf(a.getTeam()), teamBefore, "teams / scoreboard untouched");
			h.assertFalse(a.getDisplayName().getString().contains("working"), "server-side name (chat) unchanged");

			h.assertTrue(HologramActions.apply(a, new OfficePayloads.SetWorking(false)), "stopped working");
			now.addAndGet(1000);
			h.assertFalse(sent.get(sent.size() - 1).contains(a.getUUID()), "broadcast without the player");

			HologramActions.apply(a, new OfficePayloads.SetWorking(true));
			now.addAndGet(1000);
			int before = sent.size();
			HologramActions.clearWorking(a); // disconnect
			h.assertFalse(HologramActions.isWorking(a.getUUID()), "cleared on disconnect");
			h.assertValueEqual(sent.size(), before + 1, "disconnect is broadcast");
			h.assertFalse(sent.get(sent.size() - 1).contains(a.getUUID()), "everyone drops the suffix");
			HologramActions.clearWorking(a);
			h.assertValueEqual(sent.size(), before + 1, "clearing someone not working sends nothing");
		} finally {
			HologramActions.setWorkingBroadcast(null);
			HologramActions.setClock(null);
		}
		h.succeed();
	}

	@GameTest
	public void workingToggleIsThrottledButEndStateIsCorrect(GameTestHelper h) {
		ServerPlayer a = survivalPlayer(h, new BlockPos(1, 0, 5));
		var server = h.getLevel().getServer();
		List<List<UUID>> sent = new ArrayList<>();
		AtomicLong now = new AtomicLong(5_000_000);
		HologramActions.setWorkingBroadcast((srv, working) -> sent.add(working));
		HologramActions.setClock(now::get);
		try {
			h.assertTrue(HologramActions.apply(a, new OfficePayloads.SetWorking(true)), "first toggle applies");
			h.assertValueEqual(sent.size(), 1, "one broadcast");

			// Inside the window: held back, nothing broadcast, state unchanged.
			now.addAndGet(100);
			h.assertFalse(HologramActions.apply(a, new OfficePayloads.SetWorking(false)), "too soon: ignored");
			h.assertTrue(HologramActions.isWorking(a.getUUID()), "still working");
			h.assertValueEqual(sent.size(), 1, "no broadcast inside the window");
			now.addAndGet(100);
			HologramActions.flushWorking(server);
			h.assertTrue(HologramActions.isWorking(a.getUUID()), "tick before the window ends changes nothing");
			h.assertValueEqual(sent.size(), 1, "still one broadcast");

			// Window over: the latest state goes through on the next tick.
			now.addAndGet(100);
			HologramActions.flushWorking(server);
			h.assertFalse(HologramActions.isWorking(a.getUUID()), "latest state applied");
			h.assertValueEqual(sent.size(), 2, "one broadcast for the held toggle");
			h.assertFalse(sent.get(1).contains(a.getUUID()), "broadcast without the player");
			HologramActions.flushWorking(server);
			h.assertValueEqual(sent.size(), 2, "nothing left to flush");

			// A burst of flip-flops collapses to its last state (here equal to the current one: no extra broadcast).
			now.addAndGet(1000);
			h.assertTrue(HologramActions.apply(a, new OfficePayloads.SetWorking(true)), "applies");
			for (int i = 0; i < 9; i++) {
				now.addAndGet(10);
				HologramActions.apply(a, new OfficePayloads.SetWorking(i % 2 != 0));
			}
			h.assertValueEqual(sent.size(), 3, "burst broadcasts nothing extra");
			now.addAndGet(500);
			HologramActions.flushWorking(server);
			h.assertFalse(HologramActions.isWorking(a.getUUID()), "end state is the last toggle (false)");
			h.assertValueEqual(sent.size(), 4, "one broadcast for the burst");

			// Disconnect drops anything held back.
			now.addAndGet(1000);
			HologramActions.apply(a, new OfficePayloads.SetWorking(true));
			now.addAndGet(10);
			HologramActions.apply(a, new OfficePayloads.SetWorking(false));
			HologramActions.apply(a, new OfficePayloads.SetWorking(true));
			HologramActions.clearWorking(a);
			now.addAndGet(1000);
			HologramActions.flushWorking(server);
			h.assertFalse(HologramActions.isWorking(a.getUUID()), "a held toggle does not resurrect a disconnected player");
		} finally {
			HologramActions.setWorkingBroadcast(null);
			HologramActions.setClock(null);
			HologramActions.clearWorking(a);
		}
		h.succeed();
	}

	private static int itemEntitiesNear(GameTestHelper h, ServerPlayer p) {
		return h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(p.blockPosition()).inflate(16)).size();
	}

	@GameTest
	@SuppressWarnings("removal")
	public void emitterSpamCannotExceedLimit(GameTestHelper h) {
		ServerPlayer a = h.makeMockServerPlayerInLevel();
		AtomicLong now = new AtomicLong(1_000_000);
		EmitterCommand.setClock(now::get);
		try {
			a.getInventory().clearContent(); // saved data of the shared mock UUID may carry items over
			// 3 placed + spam: carried + placed never passes the limit.
			a.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(OfficeBlocks.HOLOGRAM_EMITTER_ITEM, 3));
			Vec3 v = h.absoluteVec(Vec3.atBottomCenterOf(new BlockPos(4, 0, 6)));
			a.setPos(v.x, v.y, v.z);
			for (int x = 0; x < 3; x++) {
				place(h, a, new BlockPos(x, 1, 1));
			}
			a.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); // mock player may be creative: nothing consumed
			HologramIndex index = HologramIndex.get(h.getLevel().getServer());
			h.assertValueEqual(index.countOwnedBy(a.getUUID()), 3, "three placed");
			int given = 0;
			for (int i = 0; i < 40; i++) {
				now.addAndGet(EmitterCommand.COOLDOWN_MS + 1);
				if (EmitterCommand.tryGive(a) == EmitterCommand.Outcome.GIVEN) {
					given++;
				}
				h.assertTrue(index.countOwnedBy(a.getUUID()) + count(a, OfficeBlocks.HOLOGRAM_EMITTER_ITEM) <= HologramEmitterBlock.MAX_HOLOGRAMS,
						"never above the limit (round " + i + ")");
			}
			h.assertValueEqual(given, 5, "exactly the remaining 5");
			now.addAndGet(EmitterCommand.COOLDOWN_MS + 1);
			h.assertTrue(EmitterCommand.tryGive(a) == EmitterCommand.Outcome.LIMIT, "limit reached");
			// Same through the real command.
			h.getLevel().getServer().getCommands().performPrefixedCommand(a.createCommandSourceStack(), "emitter");
			h.assertValueEqual(count(a, OfficeBlocks.HOLOGRAM_EMITTER_ITEM), 5, "command adds nothing at the limit");

			// Off-hand emitters count too.
			a.getInventory().clearContent();
			a.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(OfficeBlocks.HOLOGRAM_EMITTER_ITEM, 5));
			now.addAndGet(EmitterCommand.COOLDOWN_MS + 1);
			h.assertTrue(EmitterCommand.tryGive(a) == EmitterCommand.Outcome.LIMIT, "3 placed + 5 in the off hand = limit");
			a.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(OfficeBlocks.HOLOGRAM_EMITTER_ITEM, 4));
			h.assertTrue(EmitterCommand.tryGive(a) == EmitterCommand.Outcome.GIVEN, "3 placed + 4 in the off hand has room for one");
			h.assertValueEqual(itemEntitiesNear(h, a), 0, "nothing dropped");
		} finally {
			EmitterCommand.setClock(null);
			a.getInventory().clearContent();
			h.getLevel().getServer().getPlayerList().remove(a);
		}
		h.succeed();
	}

	@GameTest
	@SuppressWarnings("removal")
	public void emitterFullInventoryGivesNothingAndDropsNothing(GameTestHelper h) {
		ServerPlayer a = h.makeMockServerPlayerInLevel();
		AtomicLong now = new AtomicLong(1_000_000);
		EmitterCommand.setClock(now::get);
		try {
			Vec3 v = h.absoluteVec(Vec3.atBottomCenterOf(new BlockPos(4, 0, 6)));
			a.setPos(v.x, v.y, v.z);
			Inventory inv = a.getInventory();
			inv.clearContent();
			for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
				inv.setItem(i, new ItemStack(Items.STONE, 64));
			}
			EmitterCommand.Outcome full = EmitterCommand.tryGive(a);
			h.assertTrue(full == EmitterCommand.Outcome.FULL, "full inventory: " + full + " size " + inv.getContainerSize() + " carried " + count(a, OfficeBlocks.HOLOGRAM_EMITTER_ITEM));
			h.assertTrue(EmitterCommand.message(EmitterCommand.Outcome.FULL).contains("full"), "says so");
			h.getLevel().getServer().getCommands().performPrefixedCommand(a.createCommandSourceStack(), "emitter");
			h.assertValueEqual(count(a, OfficeBlocks.HOLOGRAM_EMITTER_ITEM), 0, "gave nothing");
			h.assertValueEqual(itemEntitiesNear(h, a), 0, "dropped nothing");
			// A failed attempt does not burn the cooldown.
			inv.setItem(0, ItemStack.EMPTY);
			h.assertTrue(EmitterCommand.tryGive(a) == EmitterCommand.Outcome.GIVEN, "works once there is room");
			h.assertValueEqual(count(a, OfficeBlocks.HOLOGRAM_EMITTER_ITEM), 1, "got one");
		} finally {
			EmitterCommand.setClock(null);
			a.getInventory().clearContent();
			h.getLevel().getServer().getPlayerList().remove(a);
		}
		h.succeed();
	}

	@GameTest
	@SuppressWarnings("removal")
	public void emitterCooldownMessage(GameTestHelper h) {
		ServerPlayer a = h.makeMockServerPlayerInLevel();
		AtomicLong now = new AtomicLong(1_000_000);
		EmitterCommand.setClock(now::get);
		try {
			a.getInventory().clearContent();
			h.assertTrue(EmitterCommand.tryGive(a) == EmitterCommand.Outcome.GIVEN, "first is given");
			now.addAndGet(EmitterCommand.COOLDOWN_MS - 1);
			EmitterCommand.Outcome again = EmitterCommand.tryGive(a);
			h.assertTrue(again == EmitterCommand.Outcome.COOLDOWN, "inside 10 s: cooldown");
			h.assertValueEqual(EmitterCommand.message(again), EmitterCommand.COOLDOWN_MESSAGE, "cooldown message");
			h.assertTrue(EmitterCommand.COOLDOWN_MESSAGE.toLowerCase().contains("wait"), "friendly: tells them to wait");
			h.assertValueEqual(count(a, OfficeBlocks.HOLOGRAM_EMITTER_ITEM), 1, "nothing extra given");
			now.addAndGet(1);
			h.assertTrue(EmitterCommand.tryGive(a) == EmitterCommand.Outcome.GIVEN, "after 10 s: given again");
			h.assertValueEqual(count(a, OfficeBlocks.HOLOGRAM_EMITTER_ITEM), 2, "two now");
		} finally {
			EmitterCommand.setClock(null);
			a.getInventory().clearContent();
			h.getLevel().getServer().getPlayerList().remove(a);
		}
		h.succeed();
	}

	@GameTest
	public void ownerOnlineFollowsJoinAndLeave(GameTestHelper h) {
		ServerPlayer a = survivalPlayer(h, new BlockPos(1, 0, 5));
		HologramBlockEntity be = place(h, a, new BlockPos(1, 1, 1));
		h.assertFalse(be.ownerOnline(), "mock owner is not in the player list");
		HologramBlockEntity.setOwnerOnline(h.getLevel().getServer(), a.getUUID(), true);
		h.assertTrue(be.ownerOnline(), "online after join");
		HologramBlockEntity.setOwnerOnline(h.getLevel().getServer(), a.getUUID(), false);
		h.assertFalse(be.ownerOnline(), "offline after leave");
		h.succeed();
	}
}
