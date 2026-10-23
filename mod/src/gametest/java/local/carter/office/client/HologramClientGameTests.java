package local.carter.office.client;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import javax.imageio.ImageIO;

import local.carter.office.client.capture.OwnScreens;
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
	private static final int MOUSE_RIGHT = 1; // GLFW_MOUSE_BUTTON_RIGHT

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
		// Just inside the bottom-right corner (within the 0.25 grab radius).
		Vec3d grab = f.center().add(f.right().scale(f.w() / 2 - 0.08)).add(f.up().scale(-(f.h() / 2 - 0.08)));
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
		HologramSpec spec = context.computeOnClient(mc -> clientHologram(mc).spec());
		Frame f = HologramPlacement.frame(emitter.getX(), emitter.getY(), emitter.getZ(), spec);
		Vec3d grab = f.center().add(f.right().scale(f.w() / 2 - 0.08)).add(f.up().scale(-(f.h() / 2 - 0.08)));
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
		return sp.getServer().computeOnServer(server -> {
			HologramBlockEntity be = (HologramBlockEntity) player(server).level().getBlockEntity(emitter);
			return be.spec().width();
		});
	}

	/**
	 * The own hologram fed by {@code TestPatternSource} (through OwnScreens, as with -Doffice.testPattern=true) is LIVE and
	 * its pixels change between two screenshots 500 ms apart (moving bar + frame counter, uploaded as patches).
	 */
	private void liveTextureUpdates(final ClientGameTestContext context, final TestSingleplayerContext sp) {
		context.runOnClient(mc -> OwnScreens.setEnabled(true));
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
