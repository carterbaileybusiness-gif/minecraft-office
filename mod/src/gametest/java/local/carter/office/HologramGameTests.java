package local.carter.office;

import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;
import local.carter.office.core.Privacy;
import local.carter.office.core.Shape;
import local.carter.office.hologram.HologramBlockEntity;
import local.carter.office.hologram.HologramIndex;
import local.carter.office.hologram.OfficeBlocks;
import local.carter.office.hologram.StarterKit;
import local.carter.office.net.HologramActions;
import local.carter.office.net.OfficePayloads;
import local.carter.office.net.WorkingTag;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;

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

	@GameTest
	public void placeSetsOwner(GameTestHelper h) {
		ServerPlayer a = survivalPlayer(h, new BlockPos(1, 0, 5));
		HologramBlockEntity be = place(h, a, new BlockPos(1, 1, 1));
		HologramSpec s = be.spec();
		h.assertTrue(s != null, "spec set on place");
		h.assertValueEqual(s.owner(), a.getUUID(), "owner");
		h.assertValueEqual(s.shape(), Shape.WIDE_16_9, "shape");
		h.assertValueEqual(s.mode(), Mode.EXTEND, "mode");
		h.assertValueEqual(s.privacy(), Privacy.PUBLIC, "privacy");
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

	@GameTest
	public void workingSuffixFollowsSetWorking(GameTestHelper h) {
		ServerPlayer a = survivalPlayer(h, new BlockPos(1, 0, 5));
		ServerScoreboard board = h.getLevel().getServer().getScoreboard();
		String name = a.getScoreboardName();
		PlayerTeam blue = board.getPlayerTeam("office_test_blue");
		if (blue == null) {
			blue = board.addPlayerTeam("office_test_blue");
		}
		board.addPlayerToTeam(name, blue);

		h.assertTrue(HologramActions.apply(a, new OfficePayloads.SetWorking(true)), "working");
		h.assertTrue(HologramActions.isWorking(a.getUUID()), "server knows");
		h.assertTrue(WorkingTag.isTagged(a), "tagged");
		h.assertTrue(a.getDisplayName().getString().endsWith(WorkingTag.SUFFIX),
				"name tag / tab list shows the suffix: " + a.getDisplayName().getString());
		h.assertFalse(HologramActions.apply(a, new OfficePayloads.SetWorking(true)), "repeat is a no-op");

		h.assertTrue(HologramActions.apply(a, new OfficePayloads.SetWorking(false)), "stopped working");
		h.assertFalse(WorkingTag.isTagged(a), "untagged");
		h.assertFalse(a.getDisplayName().getString().contains("working"), "suffix gone: " + a.getDisplayName().getString());
		h.assertValueEqual(board.getPlayersTeam(name), blue, "back on the team they were on");

		HologramActions.apply(a, new OfficePayloads.SetWorking(true));
		HologramActions.clearWorking(a); // disconnect
		h.assertFalse(HologramActions.isWorking(a.getUUID()), "cleared on disconnect");
		h.assertFalse(WorkingTag.isTagged(a), "tag cleared on disconnect");
		board.removePlayerFromTeam(name, blue);
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
