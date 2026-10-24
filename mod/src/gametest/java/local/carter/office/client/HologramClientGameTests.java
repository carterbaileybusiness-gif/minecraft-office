package local.carter.office.client;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import javax.imageio.ImageIO;

import com.mojang.blaze3d.platform.InputConstants;

import local.carter.office.client.capture.OwnScreens;
import local.carter.office.client.dock.AppSearchScreen;
import local.carter.office.client.dock.Dock;
import local.carter.office.client.dock.DockItem;
import local.carter.office.client.dock.DockLayout;
import local.carter.office.client.capture.TestPatternSource;
import local.carter.office.client.interact.HologramSettingsScreen;
import local.carter.office.client.render.HologramLook;
import local.carter.office.client.render.HologramPlacement;
import local.carter.office.client.render.ScreenTexture;
import local.carter.office.client.render.ScreenTextureRegistry;
import local.carter.office.core.HologramGeometry.Frame;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Vec3d;
import local.carter.office.hologram.HologramBlockEntity;
import local.carter.office.hologram.HologramEmitterBlock;
import local.carter.office.hologram.OfficeBlocks;

import com.mojang.blaze3d.platform.InputConstants;

import local.carter.office.client.zoom.ZoomClient;
import local.carter.office.client.zoom.ZoomController;
import local.carter.office.client.helper.HelperProtocol.PointerButton;
import local.carter.office.client.pointer.PointerClient;
import local.carter.office.client.pointer.PointerPoint;
import local.carter.office.client.pointer.PointerPort;
import local.carter.office.client.pointer.TypingScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Client GameTests for hologram rendering and interaction (screenshots land in build/run/clientGameTest/screenshots).
 * Needs a display: CI runs them on Linux under xvfb.
 */
@SuppressWarnings("UnstableApiUsage")
public class HologramClientGameTests implements FabricClientGameTest {
	/**
	 * 26.3 uses SDL3 button numbers: 1 = left, 2 = middle, 3 = right ({@code Options.keyUse} is MOUSE 3). Task 6 used
	 * GLFW's 1, which is the LEFT button here: the old corner-drag test was attacking, never right-clicking.
	 */
	private static final int MOUSE_RIGHT = InputConstants.MOUSE_BUTTON_RIGHT;

	/** Where the test stands (feet), set up on the server. */
	private BlockPos stand;
	private BlockPos emitter;

	@Override
	public void runTest(final ClientGameTestContext context) {
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			setUpStage(context, sp);
			placedHologramVisible(context, sp);
			cornerDragResizes(context, sp);
			liveTextureUpdates(context, sp);
			zoomInToWorkAndBack(context, sp);
			inWorldPointer(context, sp);
			scrollResizes(context, sp);
			wallMount(context, sp);
			offlineLook(context, sp);
		}
	}

	/** Clear a 9x6x8 box north of the player, a stone wall behind it, the player facing north. */
	private void setUpStage(final ClientGameTestContext context, final TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			ServerPlayer p = player(server);
			ServerLevel level = p.level();
			BlockPos feet = p.blockPosition();
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, feet.getX(), feet.getZ());
			stand = new BlockPos(feet.getX(), y, feet.getZ());
			emitter = stand.offset(0, 0, -4);
			for (int dx = -5; dx <= 5; dx++) {
				for (int dy = 0; dy <= 6; dy++) {
					for (int dz = -9; dz <= 1; dz++) {
						level.setBlockAndUpdate(stand.offset(dx, dy, dz), dz == -8 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
					}
				}
			}
			p.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
		});
		sp.getConnection().waitForChunksRender();
		context.waitTicks(5);
	}

	/** Places an emitter at {@code pos} owned by {@code owner} with the default spec facing south (towards the player). */
	private static void placeHologram(final MinecraftServer server, final BlockPos pos, final UUID owner) {
		ServerLevel level = player(server).level();
		level.setBlockAndUpdate(pos, OfficeBlocks.HOLOGRAM_EMITTER.defaultBlockState());
		HologramBlockEntity be = (HologramBlockEntity) level.getBlockEntity(pos);
		HologramSpec spec = HologramEmitterBlock.defaultSpec(owner, 1, 0f);
		be.setSpec(spec);
		be.onServerLoad(level); // indexes it and computes ownerOnline from the player list
		be.setSpec(spec); // re-sync with ownerOnline
	}

	/** Replaces the emitter at {@code pos} with one owned by {@code owner}, keeping its spec (an owner never changes). */
	private static void reownHologram(final MinecraftServer server, final BlockPos pos, final UUID owner) {
		ServerLevel level = player(server).level();
		HologramSpec old = ((HologramBlockEntity) level.getBlockEntity(pos)).spec();
		level.removeBlock(pos, false);
		level.setBlockAndUpdate(pos, OfficeBlocks.HOLOGRAM_EMITTER.defaultBlockState());
		HologramBlockEntity be = (HologramBlockEntity) level.getBlockEntity(pos);
		HologramSpec spec = new HologramSpec(owner, old.shape(), old.mode(), old.privacy(), old.displaySlot(), old.windowKey(), old.width(),
				old.offX(), old.offY(), old.offZ(), old.yaw());
		be.setSpec(spec);
		be.onServerLoad(level);
		be.setSpec(spec);
	}

	private void placedHologramVisible(final ClientGameTestContext context, final TestSingleplayerContext sp) {
		Vec3d center = hologramCenter(HologramEmitterBlock.defaultSpec(UUID.randomUUID(), 1, 0f));
		look(context, center);
		context.waitTicks(10);
		int[] before = samplePixel(context.takeScreenshot("hologram-wall-only"));

		sp.getServer().runOnServer(server -> placeHologram(server, emitter, player(server).getUUID()));
		sp.getConnection().waitForChunksRender();
		context.waitFor(mc -> clientHologram(mc) != null && clientHologram(mc).spec() != null && clientHologram(mc).ownerOnline());
		context.waitTicks(10);
		int[] after = samplePixel(context.takeScreenshot("hologram-placed"));

		int diff = Math.abs(before[0] - after[0]) + Math.abs(before[1] - after[1]) + Math.abs(before[2] - after[2]);
		if (diff < 40) {
			throw new AssertionError("hologram not visible: pixel above the crosshair went from " + rgb(before) + " to " + rgb(after));
		}
	}

	private void cornerDragResizes(final ClientGameTestContext context, final TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> player(server).setItemInHand(InteractionHand.MAIN_HAND,
				new ItemStack(OfficeBlocks.HOLOGRAM_EMITTER_ITEM, 4)));
		context.waitTicks(5);
		HologramSpec start = context.computeOnClient(mc -> clientHologram(mc).spec());
		Frame f = HologramPlacement.frame(emitter.getX(), emitter.getY(), emitter.getZ(), start);
		// Just inside the bottom-right corner (within the grab radius).
		Vec3d grab = f.center().add(f.right().scale(f.w() / 2 - 0.08)).add(f.up().scale(-(f.h() / 2 - 0.08)));
		look(context, grab);
		context.waitTicks(3);
		context.takeScreenshot("hologram-handles"); // four corner handles, the hovered one big and bright

		// A quick tap on a corner is a click (settings), never a resize.
		context.getInput().pressMouse(MOUSE_RIGHT);
		context.waitTicks(2);
		boolean settings = context.computeOnClient(mc -> mc.gui.screen() instanceof HologramSettingsScreen);
		if (!settings) {
			throw new AssertionError("a quick tap on a corner should open settings");
		}
		context.runOnClient(mc -> mc.gui.setScreen(null));
		context.waitTicks(3);
		sp.getConnection().waitForServerboundPackets();
		double afterTap = serverWidth(sp);
		if (Math.abs(afterTap - start.width()) > 1e-6) {
			throw new AssertionError("a tap resized the hologram: " + start.width() + " -> " + afterTap);
		}

		look(context, grab);
		context.waitTicks(3);
		context.getInput().holdMouse(MOUSE_RIGHT);
		context.waitTicks(3);
		// Drag one block further right and a little down.
		look(context, grab.add(f.right().scale(1.0)).add(f.up().scale(-0.2)));
		context.waitTicks(5);
		context.takeScreenshot("hologram-resize-outline");
		context.getInput().releaseMouse(MOUSE_RIGHT);
		context.waitTicks(5);
		sp.getConnection().waitForServerboundPackets();
		double width = sp.getServer().computeOnServer(server -> {
			HologramBlockEntity be = (HologramBlockEntity) player(server).level().getBlockEntity(emitter);
			return be.spec().width();
		});
		if (width < start.width() + 0.5) {
			throw new AssertionError("corner drag did not resize: width " + start.width() + " -> " + width);
		}
		context.waitTicks(5);
		context.takeScreenshot("hologram-resized");

		// Shrinking: drag the same corner straight left (horizontal only) -> narrower.
		double grown = width;
		double shrunk = dragBottomRightBy(context, sp, -0.6, 0.0);
		if (shrunk > grown - 0.3) {
			throw new AssertionError("horizontal inward drag did not shrink: width " + grown + " -> " + shrunk);
		}

		// Grabbing from just OUTSIDE the corner works too, and the corner doesn't jump to the aim.
		double before = shrunk;
		double outside = dragBottomRightBy(context, sp, 0.6, 0.0, -0.1);
		if (outside < before + 0.4 || outside > before + 0.8) {
			throw new AssertionError("outside-corner drag by 0.6 should grow by ~0.6: " + before + " -> " + outside);
		}
		shrunk = outside;

		// Off-hand emitter must not take main-hand right-clicks (e.g. a Wizards staff): nothing changes.
		sp.getServer().runOnServer(server -> {
			ServerPlayer p = player(server);
			p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
			p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(OfficeBlocks.HOLOGRAM_EMITTER_ITEM, 1));
		});
		context.waitTicks(5);
		double untouched = dragBottomRightBy(context, sp, 1.0, 0.0);
		if (Math.abs(untouched - shrunk) > 1e-6) {
			throw new AssertionError("off-hand emitter intercepted a main-hand click: width " + shrunk + " -> " + untouched);
		}
		sp.getServer().runOnServer(server -> {
			ServerPlayer p = player(server);
			p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(OfficeBlocks.HOLOGRAM_EMITTER_ITEM, 4));
			p.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
		});
		context.waitTicks(5);
	}

	/** Holds right-click on the bottom-right corner, moves the aim by (right, up) blocks, releases; returns server width. */
	private double dragBottomRightBy(final ClientGameTestContext context, final TestSingleplayerContext sp, final double right,
			final double up) {
		return dragBottomRightBy(context, sp, right, up, 0.08);
	}

	/** Same, grabbing {@code inset} blocks inside the corner on both axes (negative = outside the quad). */
	private double dragBottomRightBy(final ClientGameTestContext context, final TestSingleplayerContext sp, final double right,
			final double up, final double inset) {
		return dragCornerBy(context, sp, emitter, right, up, inset);
	}

	private double dragCornerBy(final ClientGameTestContext context, final TestSingleplayerContext sp, final BlockPos at, final double right,
			final double up, final double inset) {
		HologramSpec spec = context.computeOnClient(mc -> ((HologramBlockEntity) mc.level.getBlockEntity(at)).spec());
		Frame f = HologramPlacement.frame(at.getX(), at.getY(), at.getZ(), spec);
		Vec3d grab = f.center().add(f.right().scale(f.w() / 2 - inset)).add(f.up().scale(-(f.h() / 2 - inset)));
		look(context, grab);
		context.waitTicks(3);
		context.getInput().holdMouse(MOUSE_RIGHT);
		context.waitTicks(3);
		look(context, grab.add(f.right().scale(right)).add(f.up().scale(up)));
		context.waitTicks(5);
		context.getInput().releaseMouse(MOUSE_RIGHT);
		context.waitTicks(5);
		sp.getConnection().waitForServerboundPackets();
		context.waitTicks(5);
		return serverSpec(sp, at).width();
	}

	private double serverWidth(final TestSingleplayerContext sp) {
		return serverSpec(sp, emitter).width();
	}

	private static HologramSpec serverSpec(final TestSingleplayerContext sp, final BlockPos at) {
		return sp.getServer().computeOnServer(server -> ((HologramBlockEntity) player(server).level().getBlockEntity(at)).spec());
	}

	/** Mouse-wheel over your own hologram (emitter in hand): ×1.1 per notch, bottom edge fixed, hotbar slot unchanged. */
	private void scrollResizes(final ClientGameTestContext context, final TestSingleplayerContext sp) {
		HologramSpec start = serverSpec(sp, emitter);
		look(context, hologramCenter(start));
		context.waitTicks(3);
		int slot = context.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
		context.getInput().scroll(1.0);
		context.waitTicks(6);
		sp.getConnection().waitForServerboundPackets();
		context.waitTicks(2);
		HologramSpec up = serverSpec(sp, emitter);
		if (Math.abs(up.width() - start.width() * 1.1) > 1e-6) {
			throw new AssertionError("one notch up should be x1.1: " + start.width() + " -> " + up.width());
		}
		double bottom0 = start.offY() - start.height() / 2;
		if (Math.abs(up.offY() - up.height() / 2 - bottom0) > 1e-6) {
			throw new AssertionError("scroll moved the bottom edge: " + bottom0 + " -> " + (up.offY() - up.height() / 2));
		}
		int slotAfter = context.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
		if (slotAfter != slot) {
			throw new AssertionError("scrolling over a hologram changed the hotbar slot " + slot + " -> " + slotAfter);
		}
		// Two quick notches down: throttled, but the final value always reaches the server.
		context.getInput().scroll(-1.0);
		context.getInput().scroll(-1.0);
		context.waitTicks(8);
		sp.getConnection().waitForServerboundPackets();
		context.waitTicks(2);
		HologramSpec down = serverSpec(sp, emitter);
		if (Math.abs(down.width() - up.width() / 1.21) > 1e-6) {
			throw new AssertionError("two notches down should be /1.21: " + up.width() + " -> " + down.width());
		}
		// Off the hologram the wheel changes the hotbar as usual.
		look(context, hologramCenter(down).add(new Vec3d(0, 4, 0)));
		context.waitTicks(3);
		context.getInput().scroll(-1.0);
		context.waitTicks(2);
		int slotOff = context.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
		if (slotOff == slot) {
			throw new AssertionError("scrolling away from holograms should still change the hotbar slot");
		}
		context.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(slot));
		context.waitTicks(3);
	}

	/**
	 * Right-clicking a wall face with an emitter mounts it on the wall: hologram flush with the wall, facing out; a
	 * corner drag resizes it within the wall plane.
	 */
	private void wallMount(final ClientGameTestContext context, final TestSingleplayerContext sp) {
		BlockPos wallBlock = stand.offset(-3, 2, -8);
		BlockPos mount = wallBlock.south();
		sp.getServer().runOnServer(server -> {
			ServerPlayer p = player(server);
			p.teleportTo(stand.getX() - 2.5, stand.getY(), stand.getZ() - 4.5);
			p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(OfficeBlocks.HOLOGRAM_EMITTER_ITEM, 4));
		});
		context.waitTicks(10);
		look(context, new Vec3d(wallBlock.getX() + 0.5, wallBlock.getY() + 0.5, wallBlock.getZ() + 1.0));
		context.waitTicks(3);
		context.getInput().pressMouse(MOUSE_RIGHT);
		context.waitFor(mc -> mc.level.getBlockEntity(mount) instanceof HologramBlockEntity be && be.spec() != null, 100);
		HologramSpec placed = serverSpec(sp, mount);
		String face = sp.getServer().computeOnServer(server -> player(server).level().getBlockState(mount)
				.getValue(HologramEmitterBlock.FACE).getSerializedName());
		if (!"wall".equals(face)) {
			throw new AssertionError("clicking a wall should wall-mount the emitter, got face=" + face);
		}
		double plane = mount.getZ() + 0.5 + placed.offZ();
		if (Math.abs(plane - (wallBlock.getZ() + 1 + 0.03)) > 1e-6 || Math.abs(placed.yaw()) > 1e-4) {
			throw new AssertionError("wall hologram should sit 0.03 off the wall facing south: plane z " + plane + ", yaw " + placed.yaw());
		}
		context.waitTicks(10);
		context.takeScreenshot("hologram-wall");

		double grown = dragCornerBy(context, sp, mount, 0.8, -0.3, 0.08);
		HologramSpec after = serverSpec(sp, mount);
		if (grown < placed.width() + 0.5) {
			throw new AssertionError("wall corner drag did not resize: " + placed.width() + " -> " + grown);
		}
		if (Math.abs(mount.getZ() + 0.5 + after.offZ() - plane) > 1e-6) {
			throw new AssertionError("wall resize left the wall plane: " + plane + " -> " + (mount.getZ() + 0.5 + after.offZ()));
		}
		context.waitTicks(5);
		context.takeScreenshot("hologram-wall-resized");
		sp.getServer().runOnServer(server -> player(server).teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5));
		context.waitTicks(10);
	}

	/**
	 * The own hologram fed by {@code TestPatternSource} (through OwnScreens, as with -Doffice.testPattern=true) is LIVE and
	 * its pixels change between two screenshots 500 ms apart (moving bar + frame counter, uploaded as patches).
	 */
	private void liveTextureUpdates(final ClientGameTestContext context, final TestSingleplayerContext sp) {
		context.runOnClient(mc -> {
			OwnScreens.setSourceFactory(TestPatternSource::new); // not the Mac helper when run on a Mac
			OwnScreens.setEnabled(true);
		});
		context.waitFor(mc -> clientHologram(mc) != null
				&& ScreenTextureRegistry.get(clientHologram(mc).hologramId()).map(ScreenTexture::hasFrame).orElse(false), 1200);
		HologramSpec spec = context.computeOnClient(mc -> clientHologram(mc).spec());
		look(context, hologramCenter(spec));
		context.waitTicks(5);
		HologramLook.State state = context.computeOnClient(mc -> {
			HologramBlockEntity be = clientHologram(mc);
			boolean frames = ScreenTextureRegistry.get(be.hologramId()).map(ScreenTexture::hasFrame).orElse(false);
			return HologramLook.forViewer(be.spec(), mc.player.getUUID(), be.ownerOnline(), frames, HologramLook.OwnSource.READY);
		});
		if (state != HologramLook.State.LIVE) {
			throw new AssertionError("own hologram with a test pattern should be LIVE, was " + state);
		}
		long uploadsBefore = context.computeOnClient(mc -> OwnScreens.texture(clientHologram(mc).hologramId()).uploadCount());
		BufferedImage first = read(context.takeScreenshot("hologram-live-1"));
		try {
			Thread.sleep(500); // real time: the pattern thread keeps producing while the client waits
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
		context.waitTicks(1);
		BufferedImage second = read(context.takeScreenshot("hologram-live-2"));
		long uploadsAfter = context.computeOnClient(mc -> OwnScreens.texture(clientHologram(mc).hologramId()).uploadCount());
		if (uploadsAfter <= uploadsBefore) {
			throw new AssertionError("no texture uploads between the screenshots (" + uploadsBefore + " -> " + uploadsAfter + ")");
		}
		int changed = changedPixels(first, second);
		if (changed < 100) {
			throw new AssertionError("live hologram did not change on screen: only " + changed + " pixels differ after 500 ms");
		}

		context.runOnClient(mc -> OwnScreens.setEnabled(false));
		context.waitTicks(2);
		boolean cleared = context.computeOnClient(mc -> ScreenTextureRegistry.get(clientHologram(mc).hologramId()).isEmpty());
		if (!cleared) {
			throw new AssertionError("disabling own screens did not remove the texture");
		}
	}

	/**
	 * Zoom in to work: right-click the own LIVE hologram with an empty hand -> 350 ms zoom -> WORKING with the work
	 * screen open, HUD hidden and pauseOnLostFocus off; a single Esc stays; a double Esc zooms back out and restores
	 * everything. Screenshots: zoom-working (the test pattern fills the window), zoom-back.
	 */
	private void zoomInToWorkAndBack(final ClientGameTestContext context, final TestSingleplayerContext sp) {
		context.runOnClient(mc -> {
			OwnScreens.setSourceFactory(TestPatternSource::new);
			OwnScreens.setEnabled(true);
		});
		sp.getServer().runOnServer(server -> player(server).setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY));
		context.waitFor(mc -> clientHologram(mc) != null
				&& ScreenTextureRegistry.get(clientHologram(mc).hologramId()).map(ScreenTexture::hasFrame).orElse(false), 1200);
		HologramSpec spec = context.computeOnClient(mc -> clientHologram(mc).spec());
		look(context, hologramCenter(spec));
		context.waitTicks(5);
		boolean pauseBefore = context.computeOnClient(mc -> mc.options.pauseOnLostFocus);
		boolean hudBefore = context.computeOnClient(mc -> mc.gui.hud.isHidden());

		context.getInput().pressMouse(MOUSE_RIGHT);
		context.waitFor(mc -> ZoomClient.controller().state() == ZoomController.State.WORKING, 100);
		context.waitTicks(5);
		context.takeScreenshot("zoom-working");
		String during = context.computeOnClient(mc -> {
			if (!(mc.gui.screen() instanceof net.minecraft.client.gui.screens.Screen s) || s.isPauseScreen()) {
				return "work screen not open: " + mc.gui.screen();
			}
			if (!mc.gui.hud.isHidden()) {
				return "HUD/hand still shown";
			}
			if (mc.options.pauseOnLostFocus) {
				return "pauseOnLostFocus still on";
			}
			if (!local.carter.office.client.zoom.WorkingNames.isWorking(mc.player.getUUID())) {
				return "client never got the working broadcast";
			}
			return ZoomClient.controller().isZoomedInto(clientHologram(mc).hologramId()) ? null : "not zoomed into the clicked hologram";
		});
		if (during != null) {
			throw new AssertionError("while working: " + during);
		}
		boolean taggedOnServer = sp.getServer().computeOnServer(server -> local.carter.office.net.HologramActions.isWorking(player(server).getUUID()));
		if (!taggedOnServer) {
			throw new AssertionError("server did not get SetWorking(true)");
		}

		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		sleep(450); // longer than the double-tap window
		context.waitTicks(2);
		if (context.computeOnClient(mc -> ZoomClient.controller().state()) != ZoomController.State.WORKING) {
			throw new AssertionError("a single Esc left the screen");
		}
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		context.waitFor(mc -> ZoomClient.controller().state() == ZoomController.State.WORLD, 100);
		context.waitTicks(5);
		context.takeScreenshot("zoom-back");
		String after = context.computeOnClient(mc -> {
			if (mc.gui.screen() != null) {
				return "a screen is still open: " + mc.gui.screen();
			}
			if (mc.gui.hud.isHidden() != hudBefore) {
				return "HUD visibility not restored";
			}
			if (mc.options.pauseOnLostFocus != pauseBefore) {
				return "pauseOnLostFocus not restored";
			}
			return null;
		});
		if (after != null) {
			throw new AssertionError("after zooming out: " + after);
		}
		sp.getConnection().waitForServerboundPackets();
		boolean stillTagged = sp.getServer().computeOnServer(server -> local.carter.office.net.HologramActions.isWorking(player(server).getUUID()));
		if (stillTagged) {
			throw new AssertionError("server did not get SetWorking(false)");
		}
		context.runOnClient(mc -> OwnScreens.setEnabled(false));
		sp.getServer().runOnServer(server -> player(server).setItemInHand(InteractionHand.MAIN_HAND,
				new ItemStack(OfficeBlocks.HOLOGRAM_EMITTER_ITEM, 4))); // as the following tests expect
		context.waitTicks(2);
	}

	/** A fake in-world pointer (the Mac helper's side): records what the mod sends, lets the test answer. */
	private static final class FakePointer implements PointerPort {
		final java.util.List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
		java.util.function.Consumer<String> typingExit = r -> {};
		java.util.function.Consumer<Boolean> secureInput = on -> {};
		volatile int[] cursor;

		@Override
		public String problem() {
			return null;
		}

		@Override
		public void workBegin(final PointerPoint p, final PointerButton button) {
			calls.add("work " + button + " " + p.slot() + " " + p.x() + "," + p.y());
		}

		@Override
		public int[] workCursor() {
			return cursor;
		}

		@Override
		public void scroll(final PointerPoint p, final int dx, final int dy) {
			calls.add("scroll " + dy);
		}

		@Override
		public void exitTyping() {
			calls.add("exit-typing");
		}

		@Override
		public void onTypingExit(final java.util.function.Consumer<String> listener) {
			typingExit = listener;
		}

		@Override
		public void onSecureInput(final java.util.function.Consumer<Boolean> listener) {
			secureInput = listener;
		}

		@Override
		public void windowLayer(final boolean floating, final boolean clickThrough) {
			calls.add("layer " + (floating ? "floating" : "normal") + (clickThrough ? " click-through" : ""));
		}

		@Override
		public java.util.Map<String, String> installedApps() {
			return java.util.Map.of("com.google.Chrome", "Google Chrome", "com.apple.Notes", "Notes");
		}

		@Override
		public void openApp(final int slot, final DockItem item, final int bottomPixels) {
			calls.add("open " + slot + " " + (item.bundle() != null ? item.bundle() : item.url()));
			if (bottomPixels <= 0) {
				calls.add("open without the Dock's band");
			}
		}
	}

	/**
 * Mac in-world use with a fake helper. Work mode (spec 5.10 round 2): a left click on your own Extend hologram makes
	 * Minecraft floating + click-through, then WORK_BEGIN at that pixel; the hint screen freezes the camera (no turning
	 * however the mouse moves) and the real cursor the helper reports is drawn on that hologram where it is; Esc
	 * (TYPING_EXIT) brings back the camera and a normal window. A right click is work mode with a right press. The wheel
	 * scrolls the app, not the hotbar; the Dock's icons open apps (OPEN_APP) and "All apps" the search; the Mirror's
	 * work mode is on slot 0; the stone right behind the hologram is never broken.
	 */
	private void inWorldPointer(final ClientGameTestContext context, final TestSingleplayerContext sp) {
		FakePointer fake = new FakePointer();
		// a stone patch wide enough to sit behind the hologram's centre even after the corner drag moved it sideways
		java.util.List<BlockPos> behind = new java.util.ArrayList<>();
		for (int dx = -3; dx <= 3; dx++) {
			for (int dy = 0; dy <= 3; dy++) {
				behind.add(emitter.offset(dx, dy, -1));
			}
		}
		sp.getServer().runOnServer(server -> {
			ServerPlayer p = player(server);
			p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			behind.forEach(b -> p.level().setBlockAndUpdate(b, Blocks.STONE.defaultBlockState()));
			p.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() - 1.5); // the stone is within reach
		});
		context.runOnClient(mc -> {
			PointerClient.setPortForTesting(fake);
			// the Dock is drawn and clicked only on a LIVE screen: feed the test pattern again (the zoom test turned it off)
			OwnScreens.setSourceFactory(TestPatternSource::new);
			OwnScreens.setEnabled(true);
		});
		try {
			context.waitFor(mc -> clientHologram(mc) != null
					&& ScreenTextureRegistry.get(clientHologram(mc).hologramId()).map(ScreenTexture::hasFrame).orElse(false), 1200);
			context.waitTicks(5);
			HologramSpec spec = context.computeOnClient(mc -> clientHologram(mc).spec());
			look(context, hologramCenter(spec));
			context.waitTicks(3);
			String aimed = context.computeOnClient(mc -> mc.hitResult == null ? "nothing" : mc.hitResult.getType().name());
			if (!"BLOCK".equals(aimed)) {
				throw new AssertionError("the stone behind the hologram should be what vanilla aims at, was " + aimed
						+ " (hologram centre " + hologramCenter(spec) + ", emitter " + emitter + ")");
			}

			// 1. work mode: a click on display 1's hologram → floating + click-through first, then WORK_BEGIN at its middle
			fake.calls.clear();
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(5);
			int layered = fake.calls.indexOf("layer floating click-through");
			int work = indexOfNear(fake.calls, "work LEFT 1", 960, 540);
			if (layered < 0 || work < 0 || layered > work) {
				throw new AssertionError("a click should make Minecraft click-through, then WORK_BEGIN on display 1's middle, sent " + fake.calls);
			}
			if (!context.computeOnClient(mc -> mc.gui.screen() instanceof TypingScreen)
					|| !PointerClient.WORK_HINT.equals(context.computeOnClient(mc -> PointerClient.typingHint()))) {
				throw new AssertionError("work mode shows its hint screen: \"" + PointerClient.WORK_HINT + "\"");
			}
			// the camera is frozen: moving the mouse turns nothing
			float yaw0 = context.computeOnClient(mc -> mc.player.getYRot());
			float pitch0 = context.computeOnClient(mc -> mc.player.getXRot());
			context.getInput().moveCursor(240, 120);
			context.waitTicks(5);
			float yaw1 = context.computeOnClient(mc -> mc.player.getYRot());
			float pitch1 = context.computeOnClient(mc -> mc.player.getXRot());
			if (yaw1 != yaw0 || pitch1 != pitch0) {
				throw new AssertionError("the camera must not turn while working: yaw " + yaw0 + " → " + yaw1 + ", pitch " + pitch0 + " → " + pitch1);
			}
			// the real cursor the helper reports is drawn where it is: on display 1 at (1440, 270) = u 0.75, v 0.25
			fake.cursor = new int[] {1, 1440, 270, 1920, 1080};
			context.waitTicks(3);
			double[] drawn = context.computeOnClient(mc -> PointerClient.cursorOn(clientHologram(mc).spec()));
			if (drawn == null || Math.abs(drawn[0] - 1440.5 / 1920) > 1e-6 || Math.abs(drawn[1] - 270.5 / 1080) > 1e-6) {
				throw new AssertionError("the cursor should be drawn at the helper's position, was " + java.util.Arrays.toString(drawn));
			}
			context.takeScreenshot("pointer-work-cursor");
			fake.cursor = new int[] {-1, 0, 0, 0, 0}; // moved onto another monitor
			context.waitTicks(2);
			if (context.computeOnClient(mc -> PointerClient.cursorOn(clientHologram(mc).spec())) != null) {
				throw new AssertionError("a cursor on another monitor isn't drawn on the hologram");
			}
			// Esc (the helper's TYPING_EXIT): the camera turns again, Minecraft's window is normal again, no cursor
			context.runOnClient(mc -> fake.typingExit.accept("esc"));
			context.waitFor(mc -> !(mc.gui.screen() instanceof TypingScreen), 40);
			context.waitTicks(3);
			String lastLayer = fake.calls.stream().filter(c -> c.startsWith("layer")).reduce((a, b) -> b).orElse("");
			if (!"layer normal".equals(lastLayer)) {
				throw new AssertionError("after Esc Minecraft's window must be normal again, sent " + fake.calls);
			}
			fake.cursor = new int[] {1, 1440, 270, 1920, 1080};
			if (context.computeOnClient(mc -> PointerClient.cursorOn(clientHologram(mc).spec())) != null) {
				throw new AssertionError("no cursor is drawn outside work mode");
			}
			fake.cursor = null;

			// 2. a right click is work mode with a right press
			fake.calls.clear();
			look(context, hologramCenter(spec));
			context.waitTicks(2);
			context.getInput().pressMouse(MOUSE_RIGHT);
			context.waitTicks(5);
			if (indexOfNear(fake.calls, "work RIGHT 1", 960, 540) < 0) {
				throw new AssertionError("a right click should send WORK_BEGIN with the right button, sent " + fake.calls);
			}
			context.runOnClient(mc -> fake.typingExit.accept("minecraft")); // ⌘-Tab back to Minecraft
			context.waitFor(mc -> !(mc.gui.screen() instanceof TypingScreen), 40);
			context.waitTicks(3);
			Frame f = HologramPlacement.frame(emitter.getX(), emitter.getY(), emitter.getZ(), spec);

			// 3. the wheel scrolls the app, not the hotbar
			look(context, hologramCenter(spec));
			context.waitTicks(2);
			int slot = context.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
			context.getInput().scroll(-1.0);
			context.waitTicks(3);
			if (fake.calls.stream().noneMatch(c -> c.startsWith("scroll"))
					|| context.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot()) != slot) {
				throw new AssertionError("the wheel on a hologram should scroll the app and keep the hotbar slot, sent " + fake.calls);
			}

			// 5. the mini Dock (spec 5.10): its first icon (Chrome; Safari, Finder… aren't "installed" here) opens Chrome onto
			// display 1 instead of clicking the screen behind it; "All apps" opens the search
			fake.calls.clear();
			java.util.List<DockItem> items = DockItem.shown(fake.installedApps());
			DockLayout dock = Dock.layout(spec, items.size());
			look(context, onPicture(f, dock.icon(0)));
			context.waitTicks(3);
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(3);
			if (!fake.calls.contains("open 1 com.google.Chrome") || fake.calls.contains("open without the Dock's band")
					|| fake.calls.stream().anyMatch(c -> c.startsWith("work"))) {
				throw new AssertionError("a click on the Dock's Chrome icon should send OPEN_APP Chrome on display 1 and nothing else, sent " + fake.calls);
			}
			if (context.computeOnClient(mc -> mc.gui.screen() != null)) {
				throw new AssertionError("opening an app from the Dock keeps you in the world");
			}
			context.takeScreenshot("pointer-dock");
			look(context, onPicture(f, dock.icon(items.size() - 1)));
			context.waitTicks(3);
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(3);
			if (!context.computeOnClient(mc -> mc.gui.screen() instanceof AppSearchScreen)) {
				throw new AssertionError("All apps should open the app search");
			}
			context.takeScreenshot("pointer-dock-all-apps");
			context.runOnClient(mc -> mc.gui.setScreen(null));
			context.waitTicks(3);

			// 6. the Mirror (spec 5.10): work mode on the main display (slot 0), click-through first; Esc restores it
			sp.getServer().runOnServer(server -> {
				HologramBlockEntity be = (HologramBlockEntity) player(server).level().getBlockEntity(emitter);
				be.setSpec(be.spec().withMode(local.carter.office.core.Mode.MIRROR));
			});
			context.waitFor(mc -> clientHologram(mc) != null && clientHologram(mc).spec().mode() == local.carter.office.core.Mode.MIRROR, 100);
			fake.calls.clear();
			look(context, hologramCenter(spec));
			context.waitTicks(3);
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(5);
			int through = fake.calls.indexOf("layer floating click-through");
			// the centre of the 1920x1080 picture of the main display, give or take float rounding of the look ray
			int mirrorWork = indexOfNear(fake.calls, "work LEFT 0", 960, 540);
			if (through < 0 || mirrorWork < 0 || through > mirrorWork) {
				throw new AssertionError("a Mirror click should make Minecraft click-through, then WORK_BEGIN slot 0 at the centre, sent " + fake.calls);
			}
			fake.cursor = new int[] {0, 720, 450, 1440, 900}; // the real cursor in the middle of the laptop screen
			context.waitTicks(2);
			double[] onMirror = context.computeOnClient(mc -> PointerClient.cursorOn(clientHologram(mc).spec()));
			if (onMirror == null || Math.abs(onMirror[0] - 720.5 / 1440) > 1e-6 || Math.abs(onMirror[1] - 450.5 / 900) > 1e-6) {
				throw new AssertionError("on the Mirror the cursor is drawn at its share of the main display, was " + java.util.Arrays.toString(onMirror));
			}
			fake.cursor = null;
			context.runOnClient(mc -> fake.secureInput.accept(true)); // a password field: Esc can't be seen
			context.waitTicks(2);
			if (!PointerClient.PASSWORD_HINT.equals(context.computeOnClient(mc -> PointerClient.typingHint()))) {
				throw new AssertionError("secure input should say: press ⌘-Tab to return");
			}
			context.takeScreenshot("pointer-mirror-password");
			context.runOnClient(mc -> fake.typingExit.accept("esc"));
			context.waitFor(mc -> !(mc.gui.screen() instanceof TypingScreen), 40);
			context.waitTicks(3);
			String last = fake.calls.stream().filter(c -> c.startsWith("layer")).reduce((a, b) -> b).orElse("");
			if (last.contains("click-through")) {
				throw new AssertionError("after work mode Minecraft must not stay click-through, sent " + fake.calls);
			}
			sp.getServer().runOnServer(server -> {
				HologramBlockEntity be = (HologramBlockEntity) player(server).level().getBlockEntity(emitter);
				be.setSpec(be.spec().withMode(local.carter.office.core.Mode.EXTEND));
			});
			context.waitFor(mc -> clientHologram(mc) != null && clientHologram(mc).spec().mode() == local.carter.office.core.Mode.EXTEND, 100);

			// 4. a friend's hologram: right-click with a block in hand places nothing through it, left-click breaks nothing
			UUID friend = UUID.fromString("00000000-0000-0000-0000-0000000f00d5");
			sp.getServer().runOnServer(server -> {
				reownHologram(server, emitter, friend); // a hologram's owner never changes: a friend's emitter in its place
				player(server).setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT, 8));
			});
			context.waitFor(mc -> clientHologram(mc) != null && friend.equals(clientHologram(mc).spec().owner()), 100);
			look(context, hologramCenter(spec));
			context.waitTicks(3);
			context.getInput().pressMouse(MOUSE_RIGHT);
			context.waitTicks(3);
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(5);
			sp.getConnection().waitForServerboundPackets();
			String placed = sp.getServer().computeOnServer(server -> {
				for (int dy = 1; dy <= 2; dy++) {
					if (!player(server).level().getBlockState(emitter.offset(0, dy, 0)).isAir()) {
						return "a block was placed through a friend's hologram at +" + dy;
					}
				}
				return null;
			});
			if (placed != null) {
				throw new AssertionError(placed);
			}
			sp.getServer().runOnServer(server -> reownHologram(server, emitter, player(server).getUUID()));
			context.waitFor(mc -> clientHologram(mc) != null && mc.player.getUUID().equals(clientHologram(mc).spec().owner()), 100);
			context.waitTicks(3);

			sp.getConnection().waitForServerboundPackets();
			boolean intact = sp.getServer().computeOnServer(server -> behind.stream()
					.allMatch(b -> player(server).level().getBlockState(b).is(Blocks.STONE)));
			if (!intact) {
				throw new AssertionError("a block behind the hologram was broken");
			}
		} finally {
			context.runOnClient(mc -> {
				PointerClient.setPortForTesting(null);
				OwnScreens.setEnabled(false);
			});
			sp.getServer().runOnServer(server -> {
				ServerPlayer p = player(server);
				behind.forEach(b -> p.level().setBlockAndUpdate(b, Blocks.AIR.defaultBlockState()));
				p.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
				p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(OfficeBlocks.HOLOGRAM_EMITTER_ITEM, 4));
			});
			context.waitTicks(3);
		}
	}

	/** Index of the first call "<prefix> x,y" with x, y within 2 of the expected pixel (float rounding of the look ray), or -1. */
	private static int indexOfNear(final java.util.List<String> calls, final String prefix, final int x, final int y) {
		for (int i = 0; i < calls.size(); i++) {
			String c = calls.get(i);
			if (!c.startsWith(prefix + " ")) {
				continue;
			}
			String[] xy = c.substring(prefix.length() + 1).split(",");
			if (xy.length == 2 && Math.abs(Integer.parseInt(xy[0]) - x) <= 2 && Math.abs(Integer.parseInt(xy[1]) - y) <= 2) {
				return i;
			}
		}
		return -1;
	}

	/** The world point at the centre of a box of the hologram's picture ((u, v), v down). */
	private static Vec3d onPicture(final Frame f, final DockLayout.Box b) {
		double u = (b.u0() + b.u1()) / 2;
		double v = (b.v0() + b.v1()) / 2;
		return f.center().add(f.right().scale((u - 0.5) * f.w())).add(f.up().scale((0.5 - v) * f.h()));
	}

	private static void sleep(final long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	private static BufferedImage read(final Path screenshot) {
		try {
			return ImageIO.read(screenshot.toFile());
		} catch (IOException e) {
			throw new AssertionError("cannot read screenshot " + screenshot, e);
		}
	}

	/** Pixels whose RGB differs by more than 48 (sum of channel differences). */
	private static int changedPixels(final BufferedImage a, final BufferedImage b) {
		int w = Math.min(a.getWidth(), b.getWidth());
		int h = Math.min(a.getHeight(), b.getHeight());
		int n = 0;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int p = a.getRGB(x, y);
				int q = b.getRGB(x, y);
				int d = Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)) + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF))
						+ Math.abs((p & 0xFF) - (q & 0xFF));
				if (d > 48) {
					n++;
				}
			}
		}
		return n;
	}

	private void offlineLook(final ClientGameTestContext context, final TestSingleplayerContext sp) {
		BlockPos other = emitter.offset(3, 0, 0);
		UUID stranger = UUID.randomUUID();
		sp.getServer().runOnServer(server -> placeHologram(server, other, stranger));
		sp.getConnection().waitForChunksRender();
		context.waitFor(mc -> mc.level.getBlockEntity(other) instanceof HologramBlockEntity be && be.spec() != null);
		HologramLook.State look = context.computeOnClient(mc -> {
			HologramBlockEntity be = (HologramBlockEntity) mc.level.getBlockEntity(other);
			return HologramLook.forViewer(be.spec(), mc.player.getUUID(), be.ownerOnline(), false, HologramLook.OwnSource.READY);
		});
		if (look != HologramLook.State.OFFLINE) {
			throw new AssertionError("expected OFFLINE for a hologram whose owner is not online, got " + look);
		}
		look(context, hologramCenter(HologramEmitterBlock.defaultSpec(stranger, 1, 0f)).add(new Vec3d(3, 0, 0)));
		context.waitTicks(10);
		context.takeScreenshot("hologram-offline");
	}

	// ---- helpers ----

	private static ServerPlayer player(final MinecraftServer server) {
		return server.getPlayerList().getPlayers().get(0);
	}

	private HologramBlockEntity clientHologram(final Minecraft mc) {
		return mc.level != null && mc.level.getBlockEntity(emitter) instanceof HologramBlockEntity be ? be : null;
	}

	private Vec3d hologramCenter(final HologramSpec spec) {
		return HologramPlacement.frame(emitter.getX(), emitter.getY(), emitter.getZ(), spec).center();
	}

	/** Points the client player's view at {@code target} (no interpolation). */
	private static void look(final ClientGameTestContext context, final Vec3d target) {
		context.runOnClient(mc -> {
			var eye = mc.player.getEyePosition();
			double dx = target.x() - eye.x;
			double dy = target.y() - eye.y;
			double dz = target.z() - eye.z;
			float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
			float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
			mc.player.setYRot(yaw);
			mc.player.setXRot(pitch);
			mc.player.yRotO = yaw;
			mc.player.xRotO = pitch;
		});
	}

	/** RGB just above the crosshair (the crosshair itself would hide the hologram at the exact centre). */
	private static int[] samplePixel(final Path screenshot) {
		try {
			BufferedImage img = ImageIO.read(screenshot.toFile());
			int x = img.getWidth() / 2;
			int y = img.getHeight() / 2 - Math.max(20, img.getHeight() / 20);
			int c = img.getRGB(x, y);
			return new int[] {(c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF};
		} catch (IOException e) {
			throw new AssertionError("cannot read screenshot " + screenshot, e);
		}
	}

	private static String rgb(final int[] c) {
		return "(" + c[0] + "," + c[1] + "," + c[2] + ")";
	}
}
