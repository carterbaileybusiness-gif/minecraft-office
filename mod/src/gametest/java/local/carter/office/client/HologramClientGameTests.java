package local.carter.office.client;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import javax.imageio.ImageIO;

import com.mojang.blaze3d.platform.InputConstants;

import local.carter.office.client.interact.HologramSettingsScreen;
import local.carter.office.client.render.HologramLook;
import local.carter.office.client.render.HologramPlacement;
import local.carter.office.core.HologramGeometry.Frame;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Vec3d;
import local.carter.office.hologram.HologramBlockEntity;
import local.carter.office.hologram.HologramEmitterBlock;
import local.carter.office.hologram.OfficeBlocks;

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
