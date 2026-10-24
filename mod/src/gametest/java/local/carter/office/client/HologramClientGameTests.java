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
import local.carter.office.client.screens.Screen;
import local.carter.office.client.screens.ScreenList;
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
			if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")) {
				zoomInToWorkAndBack(context, sp); // zoom is Windows / Linux; a Mac works on its screens in-world (work mode)
			}
			inWorldPointer(context, sp);
			scrollResizes(context, sp);
			wallMount(context, sp);
			settingsMapFitsAtEveryGuiScale(context, sp);
			dashedSlotClickPicksIt(context, sp);
			offlineLook(context, sp);
			if (Boolean.getBoolean("office.measure")) {
				measureWork(context, sp); // opt-in, on a Mac: in-game frame rate and latency in work mode (fix round A)
			}
		}
	}

	/**
	 * Opt-in measurement ({@code -Doffice.measure=true}, Mac): work mode on the own Mirror with Minecraft really inactive
	 * (as when a Mac app has the keyboard). Logs rendered fps, Dynamic FPS's state and the in-game latency of the own
	 * screen — picture arriving → GPU upload → on screen — with the Dynamic FPS integration on and off, and focused
	 * outside work mode. The source is the real helper with {@code -Doffice.helperPath}, else the test pattern at the
	 * same adaptive rate. Asserts the integration keeps the game rendering (≥ 20 fps) where Dynamic FPS is installed.
	 */
	private void measureWork(final ClientGameTestContext context, final TestSingleplayerContext sp) {
		if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")) {
			return;
		}
		boolean realHelper = System.getProperty("office.helperPath") != null;
		boolean dynamicFps = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("dynamic_fps");
		FakePointer fake = new FakePointer();
		sp.getServer().runOnServer(server -> {
			ServerPlayer p = player(server);
			p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			p.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() - 1.5); // within reach, as in inWorldPointer
			HologramBlockEntity be = (HologramBlockEntity) p.level().getBlockEntity(emitter);
			be.setSpec(be.spec().withMode(local.carter.office.core.Mode.MIRROR));
		});
		context.waitFor(mc -> clientHologram(mc) != null && clientHologram(mc).spec().mode() == local.carter.office.core.Mode.MIRROR, 100);
		context.runOnClient(mc -> {
			PointerClient.setPortForTesting(fake);
			OwnScreens.useAdaptiveSourceForTesting(realHelper ? null : TestPatternSource::new);
			OwnScreens.setEnabled(true);
		});
		try {
			context.waitFor(mc -> clientHologram(mc) != null
					&& ScreenTextureRegistry.get(clientHologram(mc).hologramId()).map(ScreenTexture::hasFrame).orElse(false), 2400);
			HologramSpec spec = context.computeOnClient(mc -> clientHologram(mc).spec());
			look(context, hologramCenter(spec));
			context.waitTicks(30);
			measurePhase(context, "focused, not working", 4_000);
			look(context, hologramCenter(spec));
			context.waitTicks(3);
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(5);
			if (!context.computeOnClient(mc -> PointerClient.working())) {
				throw new AssertionError("measurement: a click on the Mirror should start work mode, sent " + fake.calls);
			}
			fake.cursor = new int[] {0, 720, 450, 1440, 900}; // the real cursor on the laptop screen: the Mirror asks 60 fps
			context.runOnClient(mc -> {
				PointerClient.setFocusForTesting(false); // work mode sees the Mac app take the focus at once ...
				local.carter.office.client.platform.MacPlatform.setActiveForTesting(false); // ... and the window really loses it
			});
			context.waitTicks(30);
			if (!context.computeOnClient(mc -> PointerClient.working())) {
				throw new AssertionError("measurement: work mode should be on");
			}
			double on = measurePhase(context, "working, Minecraft inactive, Dynamic FPS integration ON", 5_000);
			context.runOnClient(mc -> PointerClient.setDynamicFpsAwakeOffForTesting(true));
			double off = measurePhase(context, "working, Minecraft inactive, Dynamic FPS integration OFF", 5_000);
			context.runOnClient(mc -> PointerClient.setDynamicFpsAwakeOffForTesting(false));
			measurePhase(context, "working, Minecraft inactive, integration ON again", 4_000);
			// the drawn cursor (round 3): a real-time feed of cursor positions (as CURSOR reports arrive), set → on screen
			measureCursor(context, fake, true);
			measureCursor(context, fake, false);
			fake.cursor = new int[] {0, 720, 450, 1440, 900};
			boolean hookRan = context.computeOnClient(mc -> local.carter.office.client.pointer.DynamicFps.hookHasRun());
			System.out.println("[measure] realHelper=" + realHelper + " dynamicFps=" + dynamicFps + " hookRan=" + hookRan + " fps on=" + on
					+ " off=" + off);
			if (dynamicFps && !hookRan) {
				throw new AssertionError("Dynamic FPS is installed but its focus hook never ran");
			}
			if (dynamicFps && on < 20) {
				throw new AssertionError("with Dynamic FPS installed the game must keep rendering in work mode: " + on + " fps");
			}
		} finally {
			fake.cursor = null;
			context.runOnClient(mc -> {
				PointerClient.setDynamicFpsAwakeOffForTesting(false);
				if (PointerClient.working()) {
					fake.typingExit.accept("esc");
				}
				PointerClient.setFocusForTesting(null);
				local.carter.office.client.platform.MacPlatform.setActiveForTesting(true);
				OwnScreens.setEnabled(false);
				PointerClient.setPortForTesting(null);
			});
			context.waitTicks(5);
		}
	}

	/**
	 * The drawn work-mode cursor: a thread moves the reported cursor every 3-20 ms (like CURSOR reports of a moving mouse)
	 * for 4 s; each new position's set → on-screen time (the end of the frame that drew it). {@code atFrameStart}: the
	 * round-2 way (read at the frame's start) instead of round 3's (the newest at draw time).
	 */
	private static void measureCursor(final ClientGameTestContext context, final FakePointer fake, final boolean atFrameStart) {
		java.util.concurrent.ConcurrentHashMap<Long, Long> setAt = new java.util.concurrent.ConcurrentHashMap<>();
		context.runOnClient(mc -> {
			PointerClient.setCursorAtFrameStartForTesting(atFrameStart);
			local.carter.office.client.render.FrameStats.setCursorStamps(k -> setAt.getOrDefault(k, 0L));
			local.carter.office.client.render.FrameStats.record(true);
		});
		java.util.concurrent.atomic.AtomicBoolean run = new java.util.concurrent.atomic.AtomicBoolean(true);
		Thread mover = new Thread(() -> {
			java.util.Random rnd = new java.util.Random(7);
			while (run.get()) {
				int x = 100 + rnd.nextInt(1200);
				setAt.put(x * 100_000L + 450, System.nanoTime());
				fake.cursor = new int[] {0, x, 450, 1440, 900};
				try {
					Thread.sleep(3 + rnd.nextInt(18));
				} catch (InterruptedException e) {
					return;
				}
			}
		}, "measure-cursor");
		mover.start();
		long end = System.currentTimeMillis() + 4_000;
		while (System.currentTimeMillis() < end) {
			context.waitTick();
		}
		run.set(false);
		local.carter.office.client.render.FrameStats.Snapshot s = context.computeOnClient(mc -> local.carter.office.client.render.FrameStats.snapshot());
		context.runOnClient(mc -> {
			local.carter.office.client.render.FrameStats.setCursorStamps(null);
			local.carter.office.client.render.FrameStats.record(false);
			PointerClient.setCursorAtFrameStartForTesting(false);
		});
		System.out.println(String.format(java.util.Locale.ROOT,
				"[measure] cursor %s: set → drawn p50=%.1f p90=%.1f ms, set → on screen p50=%.1f p90=%.1f ms (n=%d) at %.1f fps",
				atFrameStart ? "read at frame start (round 2)" : "newest at draw time (round 3)", s.cursorDrawP50(), s.cursorDrawP90(),
				s.cursorP50(), s.cursorP90(), s.cursorsShown(), s.fps()));
		if (!atFrameStart && s.cursorDrawP90() > 1000.0 / Math.max(30, s.fps()) + 2) {
			throw new AssertionError("the drawn cursor should be within one rendered frame of the newest report: set → drawn p90 "
					+ s.cursorDrawP90() + " ms at " + s.fps() + " fps");
		}
	}

	/** Records for {@code ms} of real time (ticking), logs one "[measure]" line and returns the rendered fps. */
	private static double measurePhase(final ClientGameTestContext context, final String what, final long ms) {
		context.runOnClient(mc -> local.carter.office.client.render.FrameStats.record(true));
		long ioBefore = helperIoAllocatedBytes();
		long start = System.currentTimeMillis();
		long end = start + ms;
		while (System.currentTimeMillis() < end) {
			context.waitTick();
		}
		long ioBytes = helperIoAllocatedBytes() - ioBefore;
		double ioMBps = ioBefore < 0 ? -1 : ioBytes / 1e6 / ((System.currentTimeMillis() - start) / 1000.0);
		local.carter.office.client.render.FrameStats.Snapshot s = context.computeOnClient(mc -> local.carter.office.client.render.FrameStats.snapshot());
		String state = context.computeOnClient(mc -> "active=" + mc.isWindowActive() + " dynamicFps=" + local.carter.office.client.render.FrameStats.dynamicFps()
				+ " captureFps=" + (clientHologramStatic(mc) == null ? 0 : OwnScreens.fpsForTesting(clientHologramStatic(mc))));
		context.runOnClient(mc -> local.carter.office.client.render.FrameStats.record(false));
		System.out.println(String.format(java.util.Locale.ROOT,
				"[measure] %s: fps=%.1f worstFrame=%.0fms arrival->upload p50=%.1f p90=%.1f ms, arrival->on screen p50=%.1f p90=%.1f ms (n=%d) %s",
				what, s.fps(), s.worstFrameMs(), s.uploadP50(), s.uploadP90(), s.presentP50(), s.presentP90(), s.presented(), state)
				+ String.format(java.util.Locale.ROOT, " helperIoAlloc=%.2fMB/s", ioMBps));
		return s.fps();
	}

	/** Bytes the helper's socket thread has allocated so far (the frame path: no new pixel array per frame), or -1. */
	private static long helperIoAllocatedBytes() {
		if (!(java.lang.management.ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean mx)) {
			return -1;
		}
		for (Thread t : Thread.getAllStackTraces().keySet()) {
			if ("office-helper-io".equals(t.getName())) {
				return mx.getThreadAllocatedBytes(t.threadId());
			}
		}
		return -1;
	}

	/** The id of the nearest own Mirror's hologram (measurement logging), or null. */
	private static @org.jspecify.annotations.Nullable UUID clientHologramStatic(final Minecraft mc) {
		for (HologramBlockEntity be : local.carter.office.client.interact.HologramTracker.loaded()) {
			if (be.spec() != null && be.spec().mode() == local.carter.office.core.Mode.MIRROR && mc.player != null && be.isOwner(mc.player.getUUID())) {
				return be.hologramId();
			}
		}
		return null;
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
				old.offX(), old.offY(), old.offZ(), old.yaw(), old.mirrorDisplay());
		be.setSpec(spec);
		be.onServerLoad(level);
		be.setSpec(spec);
	}

	private void placedHologramVisible(final ClientGameTestContext context, final TestSingleplayerContext sp) {
		Vec3d center = hologramCenter(HologramEmitterBlock.defaultSpec(UUID.randomUUID(), 1, 0f));
		look(context, center);
		context.waitTicks(10);
		BufferedImage before = read(context.takeScreenshot("hologram-wall-only"));

		sp.getServer().runOnServer(server -> placeHologram(server, emitter, player(server).getUUID()));
		sp.getConnection().waitForChunksRender();
		context.waitFor(mc -> clientHologram(mc) != null && clientHologram(mc).spec() != null && clientHologram(mc).ownerOnline());
		context.waitTicks(10);
		BufferedImage after = read(context.takeScreenshot("hologram-placed"));

		// the panel (its frame, its picture or placeholder) replaces the wall in the middle of the view: count changed
		// pixels in a box there rather than one pixel, whose colour depends on which placeholder shows (the helper's
		// setup state settles asynchronously)
		int w = before.getWidth() / 5;
		int h = before.getHeight() / 5;
		int x0 = before.getWidth() / 2 - w / 2;
		int y0 = before.getHeight() / 2 - h;
		int changed = 0;
		for (int y = y0; y < y0 + h; y++) {
			for (int x = x0; x < x0 + w; x++) {
				int p = before.getRGB(x, y);
				int q = after.getRGB(x, y);
				if (Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)) + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF)) + Math.abs((p & 0xFF) - (q & 0xFF)) > 20) {
					changed++; // the same view before and after: only what the panel covers differs
				}
			}
		}
		if (changed < w * h / 10) {
			int[] b = {(before.getRGB(x0 + w / 2, y0 + h / 2) >> 16) & 0xFF, (before.getRGB(x0 + w / 2, y0 + h / 2) >> 8) & 0xFF, before.getRGB(x0 + w / 2, y0 + h / 2) & 0xFF};
			int[] a = {(after.getRGB(x0 + w / 2, y0 + h / 2) >> 16) & 0xFF, (after.getRGB(x0 + w / 2, y0 + h / 2) >> 8) & 0xFF, after.getRGB(x0 + w / 2, y0 + h / 2) & 0xFF};
			throw new AssertionError("hologram not visible: only " + changed + " of " + (w * h) + " pixels changed in the middle of the view (e.g. "
					+ rgb(b) + " → " + rgb(a) + ")");
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
		java.util.function.Consumer<java.util.Set<String>> walk = keys -> {};
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
		public void activateMinecraft() {
			calls.add("activate");
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
		public void onWalk(final java.util.function.Consumer<java.util.Set<String>> listener) {
			walk = listener;
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

			// a WALK outside work mode (a late one) presses nothing
			context.runOnClient(mc -> fake.walk.accept(java.util.Set.of("forward")));
			context.waitTicks(2);
			if (context.computeOnClient(mc -> mc.options.keyUp.isDown()) || !context.computeOnClient(mc -> PointerClient.walkingForTesting()).isEmpty()) {
				throw new AssertionError("WALK outside work mode must be ignored");
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
			// the mouse alone turns nothing (until the helper reports where the real cursor is)
			float yaw0 = context.computeOnClient(mc -> mc.player.getYRot());
			float pitch0 = context.computeOnClient(mc -> mc.player.getXRot());
			context.getInput().moveCursor(240, 120);
			context.waitTicks(5);
			float yaw1 = context.computeOnClient(mc -> mc.player.getYRot());
			float pitch1 = context.computeOnClient(mc -> mc.player.getXRot());
			if (yaw1 != yaw0 || pitch1 != pitch0) {
				throw new AssertionError("the mouse must not turn the camera while working: yaw " + yaw0 + " → " + yaw1 + ", pitch " + pitch0 + " → " + pitch1);
			}
			// the real cursor the helper reports is drawn where it is: on display 1 at (1440, 270) = u 0.75, v 0.25 ...
			fake.cursor = new int[] {1, 1440, 270, 1920, 1080};
			context.waitTicks(3);
			// ... and the view follows it: within ~100 ms the crosshair aims at that point of the hologram
			context.waitTicks(4);
			Frame onScreen = HologramPlacement.frame(emitter.getX(), emitter.getY(), emitter.getZ(), spec);
			Vec3d cursorPoint = onScreen.center().add(onScreen.right().scale((1440.5 / 1920 - 0.5) * onScreen.w()))
					.add(onScreen.up().scale((0.5 - 270.5 / 1080) * onScreen.h()));
			float[] wanted = context.computeOnClient(mc -> local.carter.office.client.pointer.ViewFollow.aim(
					new Vec3d(mc.player.getEyePosition().x, mc.player.getEyePosition().y, mc.player.getEyePosition().z), cursorPoint));
			float[] looking = context.computeOnClient(mc -> PointerClient.look());
			if (Math.abs(wrapDegrees(looking[0] - wanted[0])) > 0.5f
					|| Math.abs(looking[1] - wanted[1]) > 0.5f) {
				throw new AssertionError("the view should follow the cursor: looking " + java.util.Arrays.toString(looking) + ", cursor at "
						+ java.util.Arrays.toString(wanted));
			}
			double[] drawn = context.computeOnClient(mc -> PointerClient.cursorOn(clientHologram(mc).spec()));
			if (drawn == null || Math.abs(drawn[0] - 1440.5 / 1920) > 1e-6 || Math.abs(drawn[1] - 270.5 / 1080) > 1e-6) {
				throw new AssertionError("the cursor should be drawn at the helper's position, was " + java.util.Arrays.toString(drawn));
			}
			context.takeScreenshot("pointer-work-cursor");

			// walk while dragging (round 3): the helper holds S for Minecraft while the button is down (it never reaches
			// the app: helper tests / inputprobe W9) → the player walks, still working, the view still on the cursor;
			// the button released (WALK []) → Minecraft's key is released at once and the player stops
			// the Mac app has the keyboard (as when the helper's press activates it): work mode doesn't time out waiting
			context.runOnClient(mc -> PointerClient.setFocusForTesting(false));
			net.minecraft.world.phys.Vec3 start = context.computeOnClient(mc -> mc.player.position());
			context.runOnClient(mc -> fake.walk.accept(java.util.Set.of("back")));
			context.waitTicks(10);
			net.minecraft.world.phys.Vec3 walked = context.computeOnClient(mc -> mc.player.position());
			if (walked.subtract(start).horizontalDistance() < 0.5 || !context.computeOnClient(mc -> PointerClient.working())
					|| !java.util.Set.of("back").equals(context.computeOnClient(mc -> PointerClient.walkingForTesting()))
					|| !context.computeOnClient(mc -> mc.options.keyDown.isDown())) {
				throw new AssertionError("WALK [back] while working should walk the player back: " + start + " → " + walked);
			}
			float[] walkWanted = context.computeOnClient(mc -> local.carter.office.client.pointer.ViewFollow.aim(
					new Vec3d(mc.player.getEyePosition().x, mc.player.getEyePosition().y, mc.player.getEyePosition().z), cursorPoint));
			float[] walkLook = context.computeOnClient(mc -> PointerClient.look());
			if (Math.abs(wrapDegrees(walkLook[0] - walkWanted[0])) > 1.5f || Math.abs(walkLook[1] - walkWanted[1]) > 1.5f) {
				throw new AssertionError("while walking the view keeps following the cursor: looking " + java.util.Arrays.toString(walkLook)
						+ ", cursor at " + java.util.Arrays.toString(walkWanted));
			}
			context.runOnClient(mc -> fake.walk.accept(java.util.Set.of()));
			context.waitTicks(1);
			if (context.computeOnClient(mc -> mc.options.keyDown.isDown()) || !context.computeOnClient(mc -> PointerClient.walkingForTesting()).isEmpty()) {
				throw new AssertionError("WALK [] must release Minecraft's movement key at once");
			}
			context.waitTicks(6); // the last bit of momentum
			net.minecraft.world.phys.Vec3 stopped = context.computeOnClient(mc -> mc.player.position());
			context.waitTicks(5);
			if (context.computeOnClient(mc -> mc.player.position()).subtract(stopped).horizontalDistance() > 0.01) {
				throw new AssertionError("after WALK [] the player stands still");
			}
			// Sneak: Toggle on: sneak stays held (no flip each tick) while reported, and is off after the release
			context.runOnClient(mc -> mc.options.toggleCrouch().set(true));
			context.runOnClient(mc -> fake.walk.accept(java.util.Set.of("sneak")));
			for (int i = 0; i < 4; i++) {
				context.waitTick();
				if (!context.computeOnClient(mc -> mc.options.keyShift.isDown() && mc.player.isShiftKeyDown())) {
					throw new AssertionError("with Sneak: Toggle, WALK [sneak] must keep the player sneaking every tick (tick " + i + ")");
				}
			}
			context.runOnClient(mc -> fake.walk.accept(java.util.Set.of()));
			context.waitTicks(2);
			if (context.computeOnClient(mc -> mc.options.keyShift.isDown() || mc.player.isShiftKeyDown())) {
				throw new AssertionError("with Sneak: Toggle, releasing must not leave sneak on");
			}
			context.runOnClient(mc -> fake.walk.accept(java.util.Set.of("sneak")));
			context.waitTicks(2);
			// walking again, then work mode ends (Esc): nothing stays pressed
			context.runOnClient(mc -> fake.walk.accept(java.util.Set.of("left", "jump", "sneak")));
			context.waitTicks(2);
			sp.getServer().runOnServer(server -> player(server).teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() - 1.5));
			context.waitTicks(2);
			float[] before = context.computeOnClient(mc -> PointerClient.look());
			fake.cursor = new int[] {-1, 0, 0, 0, 0}; // moved onto another monitor
			context.waitTicks(4);
			if (context.computeOnClient(mc -> PointerClient.cursorOn(clientHologram(mc).spec())) != null) {
				throw new AssertionError("a cursor on another monitor isn't drawn on the hologram");
			}
			float[] held = context.computeOnClient(mc -> PointerClient.look());
			if (held[0] != before[0] || held[1] != before[1]) {
				throw new AssertionError("with the cursor on another monitor the view holds still: " + java.util.Arrays.toString(before)
						+ " → " + java.util.Arrays.toString(held));
			}
			if (!context.computeOnClient(mc -> mc.options.keyLeft.isDown())) {
				throw new AssertionError("WALK [left, jump] presses Minecraft's left key while working (working "
						+ context.computeOnClient(mc -> PointerClient.working()) + ", pressed " + context.computeOnClient(mc -> PointerClient.walkingForTesting())
						+ ", key " + context.computeOnClient(mc -> mc.options.keyLeft.saveString()) + ")");
			}
			// Esc (the helper's TYPING_EXIT): the camera turns again, Minecraft's window is normal again, no cursor; the
			// movement keys are released right away, in the same tick (not at the next one)
			boolean stillPressed = context.computeOnClient(mc -> {
				fake.typingExit.accept("esc");
				return mc.options.keyLeft.isDown() || mc.options.keyJump.isDown() || mc.options.keyShift.isDown();
			});
			if (stillPressed) {
				throw new AssertionError("work mode ending must release the movement keys at once (same tick)");
			}
			context.runOnClient(mc -> PointerClient.setFocusForTesting(null));
			context.waitFor(mc -> !(mc.gui.screen() instanceof TypingScreen), 40);
			context.waitTicks(3);
			String lastLayer = fake.calls.stream().filter(c -> c.startsWith("layer")).reduce((a, b) -> b).orElse("");
			if (!"layer normal".equals(lastLayer)) {
				throw new AssertionError("after Esc Minecraft's window must be normal again, sent " + fake.calls);
			}
			if (context.computeOnClient(mc -> mc.options.keyLeft.isDown() || mc.options.keyJump.isDown() || mc.options.keyShift.isDown()
					|| mc.player.isShiftKeyDown())) {
				throw new AssertionError("work mode ending must release the movement keys it pressed (Sneak: Toggle too)");
			}
			context.runOnClient(mc -> mc.options.toggleCrouch().set(false));
			context.waitTicks(10); // the walk's momentum dies down, then back to the spot (twice: a moving client drifts once)
			sp.getServer().runOnServer(server -> player(server).teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() - 1.5));
			context.waitTicks(10);
			sp.getServer().runOnServer(server -> player(server).teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() - 1.5));
			context.waitTicks(5);
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

			// 2b. other ways out of work mode (a fake focus): another screen replacing the hint (death, a menu) ends it AND
			// brings Minecraft back; focus coming back (⌘-Tab to Minecraft) ends it with a normal window
			fake.calls.clear();
			look(context, hologramCenter(spec));
			context.waitTicks(2);
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(3);
			context.runOnClient(mc -> PointerClient.setFocusForTesting(false)); // the Mac app has the keyboard now
			context.waitTicks(3);
			context.runOnClient(mc -> mc.gui.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player)));
			context.waitTicks(3);
			if (!fake.calls.contains("exit-typing") || !fake.calls.contains("activate")) {
				throw new AssertionError("a screen replacing the work hint must end work mode and bring Minecraft back, sent " + fake.calls);
			}
			context.runOnClient(mc -> mc.gui.setScreen(null));
			context.runOnClient(mc -> PointerClient.setFocusForTesting(true));
			context.waitTicks(3);
			fake.calls.clear();
			look(context, hologramCenter(spec));
			context.waitTicks(2);
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(3);
			context.runOnClient(mc -> PointerClient.setFocusForTesting(false));
			context.waitTicks(3);
			context.runOnClient(mc -> PointerClient.setFocusForTesting(true)); // ⌘-Tab back to Minecraft
			context.waitTicks(4);
			String afterFocus = fake.calls.stream().filter(c -> c.startsWith("layer")).reduce((a, b) -> b).orElse("");
			if (context.computeOnClient(mc -> PointerClient.working()) || !fake.calls.contains("exit-typing") || !"layer normal".equals(afterFocus)) {
				throw new AssertionError("focus back in Minecraft must end work mode with a normal window, sent " + fake.calls);
			}
			context.runOnClient(mc -> PointerClient.setFocusForTesting(null));
			context.waitTicks(2);

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

	private static float wrapDegrees(final float d) {
		float w = d % 360f;
		return w > 180f ? w - 360f : w <= -180f ? w + 360f : w;
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

	/** Three monitors (one portrait, left of and above main) and four Office displays: the widest map the screen gets. */
	private static final ScreenList BUSY_SCREENS = new ScreenList(java.util.List.of(
			new Screen("MAIN", 0, true, "MacBook", 0, 0, 1440, 900),
			new Screen("LG", 0, false, "LG", -1920, -180, 1920, 1080),
			new Screen("TALL", 0, false, "Dell", -3000, -1000, 1080, 1920),
			new Screen("O1", 1, false, null, 1440, 0, 1920, 1080),
			new Screen("O2", 2, false, null, 3360, 0, 1920, 1080),
			new Screen("O3", 3, false, null, 5280, 0, 1920, 1080),
			new Screen("O4", 4, false, null, 7200, 0, 1920, 1080)), ScreenList.Abilities.ALL);

	/**
	 * The display map (Task 10) in Extend and in Mirror, in a 1080p window at GUI scales 1-4, in the default 854 × 480
	 * window at its largest (240 high) and at 320 × 240 scaled (1280 × 960 at 4): the map has its own row across both
	 * columns; no widget leaves the window or overlaps another; every box lies inside the map panel, which the arrangement
	 * fills one way. Screenshot each, and in Extend one with a display moved but not Applied (its real spot dotted) and the
	 * mouse over another box.
	 */
	private void settingsMapFitsAtEveryGuiScale(final ClientGameTestContext context, final TestSingleplayerContext sp) {
		int savedScale = context.computeOnClient(mc -> mc.options.guiScale().get());
		local.carter.office.core.Mode original = context.computeOnClient(mc -> clientHologram(mc).spec().mode());
		context.runOnClient(mc -> ScreenList.setTestOverride(BUSY_SCREENS));
		try {
			for (local.carter.office.core.Mode mode : new local.carter.office.core.Mode[] {local.carter.office.core.Mode.EXTEND,
					local.carter.office.core.Mode.MIRROR}) {
				if (mode == local.carter.office.core.Mode.EXTEND
						&& local.carter.office.client.platform.Platform.current() instanceof local.carter.office.client.platform.WindowsPlatform) {
					continue; // Extend is greyed on Windows: no map there
				}
				switchMode(context, sp, mode);
				identifyLabelsTheHologram(context, mode);
				String name = "settings-map-" + mode.name().toLowerCase(java.util.Locale.ROOT);
				context.getInput().resizeWindow(1920, 1080);
				for (int scale = 1; scale <= 4; scale++) {
					openMapAt(context, mode, scale, name + "-scale-" + scale);
				}
				if (mode == local.carter.office.core.Mode.EXTEND) {
					pendingMoveShows(context, name + "-pending-move");
				}
				context.getInput().resizeWindow(854, 480);
				openMapAt(context, mode, 4, name + "-854x480"); // GUI scale 4 is clamped to 2 here: the shortest layout
				context.getInput().resizeWindow(1280, 960);
				openMapAt(context, mode, 4, name + "-320x240"); // the narrowest and shortest GUI Minecraft allows
			}
		} finally {
			context.getInput().resizeWindow(854, 480);
			context.runOnClient(mc -> {
				mc.gui.setScreen(null);
				ScreenList.setTestOverride(null);
				mc.options.guiScale().set(savedScale);
				mc.resizeGui();
			});
			switchMode(context, sp, original);
		}
	}

	/** Opens the settings screen at GUI scale {@code scale}, checks the map's fit and takes screenshot {@code shot}. */
	private void openMapAt(final ClientGameTestContext context, final local.carter.office.core.Mode mode, final int scale,
			final String shot) {
		context.runOnClient(mc -> {
			mc.options.guiScale().set(scale);
			mc.resizeGui();
			mc.gui.setScreen(new HologramSettingsScreen(clientHologram(mc), "Test"));
		});
		context.waitTicks(2);
		String problem = context.computeOnClient(mc -> mapProblem(mc, mode));
		context.takeScreenshot(shot);
		if (problem != null) {
			throw new AssertionError(mode + " at GUI scale " + scale + " (" + shot + "): " + problem);
		}
	}

	/**
	 * Extend at GUI scale 2 in the 1080p window: Office display 4 dragged under display 1 and dropped (not Applied) keeps
	 * a dotted outline where it really is; the mouse rests on display 2 (lit). Screenshot.
	 */
	private void pendingMoveShows(final ClientGameTestContext context, final String shot) {
		context.runOnClient(mc -> {
			mc.options.guiScale().set(2);
			mc.resizeGui();
			mc.gui.setScreen(new HologramSettingsScreen(clientHologram(mc), "Test"));
		});
		context.waitTicks(2);
		double[] at = context.computeOnClient(mc -> {
			HologramSettingsScreen screen = (HologramSettingsScreen) mc.gui.screen();
			local.carter.office.client.screens.DisplayMapModel model = screen.displayMap().model();
			if (!model.drag("O4", 1440, 1080) || !model.drop("O4") || model.pending().isEmpty()) {
				throw new AssertionError("display 4 should move under display 1 (not Applied): " + model.pending());
			}
			if (model.movedFrom().size() != 1) {
				throw new AssertionError("display 4's real spot should be drawn dotted: " + model.movedFrom());
			}
			local.carter.office.client.platform.Platform.Rect box = screen.displayMap().boxOnScreen("O2");
			double gx = box.x() + box.w() / 2.0, gy = box.y() + box.h() / 2.0;
			return new double[] {gx * mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth(),
					gy * mc.getWindow().getScreenHeight() / mc.getWindow().getGuiScaledHeight()};
		});
		context.getInput().setCursorPos(at[0], at[1]);
		context.waitTicks(2);
		context.takeScreenshot(shot);
		context.runOnClient(mc -> mc.gui.setScreen(null)); // closing drops the move
	}

	/** One Office display: dashed slots 2, 3 and 4 right of it. */
	private static final ScreenList ONE_OFFICE = new ScreenList(java.util.List.of(
			new Screen("MAIN", 0, true, "MacBook", 0, 0, 1440, 900),
			new Screen("O1", 1, false, null, 1440, 0, 1920, 1080)), ScreenList.Abilities.ALL);

	/**
	 * Review fix #1: an Extend hologram can still be pointed at an Office display that doesn't exist yet. A real mouse
	 * press and release (through the screen, as the game delivers them) on the dashed slot 3 shows display 3; on the box of
	 * the slot it had, back again.
	 */
	private void dashedSlotClickPicksIt(final ClientGameTestContext context, final TestSingleplayerContext sp) {
		if (local.carter.office.client.platform.Platform.current() instanceof local.carter.office.client.platform.WindowsPlatform) {
			return; // Extend is greyed on Windows: no map there
		}
		local.carter.office.core.Mode original = context.computeOnClient(mc -> clientHologram(mc).spec().mode());
		switchMode(context, sp, local.carter.office.core.Mode.EXTEND);
		int slot = context.computeOnClient(mc -> clientHologram(mc).spec().displaySlot());
		context.runOnClient(mc -> ScreenList.setTestOverride(ONE_OFFICE));
		try {
			clickMapBox(context, local.carter.office.client.screens.DisplayMapModel.GHOST + 3);
			sp.getConnection().waitForServerboundPackets();
			context.waitFor(mc -> clientHologram(mc).spec().displaySlot() == 3, 100);
			context.takeScreenshot("settings-map-dashed-slot-3");
			clickMapBox(context, slot == 1 ? "O1" : local.carter.office.client.screens.DisplayMapModel.GHOST + slot);
			sp.getConnection().waitForServerboundPackets();
			context.waitFor(mc -> clientHologram(mc).spec().displaySlot() == slot, 100);
		} finally {
			context.runOnClient(mc -> {
				mc.gui.setScreen(null);
				ScreenList.setTestOverride(null);
			});
			switchMode(context, sp, original);
		}
	}

	/** Opens the settings screen and clicks (press, then release) the middle of map box {@code id}. */
	private void clickMapBox(final ClientGameTestContext context, final String id) {
		context.runOnClient(mc -> mc.gui.setScreen(new HologramSettingsScreen(clientHologram(mc), "Test")));
		context.waitTicks(2);
		context.runOnClient(mc -> {
			if (!(mc.gui.screen() instanceof HologramSettingsScreen screen) || screen.displayMap() == null) {
				throw new AssertionError("the settings screen should show the display map in Extend");
			}
			local.carter.office.client.platform.Platform.Rect box = screen.displayMap().boxOnScreen(id);
			if (box == null) {
				throw new AssertionError("no box " + id + " on the map");
			}
			net.minecraft.client.input.MouseButtonEvent click = new net.minecraft.client.input.MouseButtonEvent(
					box.x() + box.w() / 2.0, box.y() + box.h() / 2.0,
					new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
			screen.mouseClicked(click, false);
			screen.mouseReleased(click);
		});
	}

	/** Identify (Task 11) from the settings screen: the own hologram shows its number (Extend) or letter (Mirror) for 3 s. */
	private void identifyLabelsTheHologram(final ClientGameTestContext context, final local.carter.office.core.Mode mode) {
		String want = context.computeOnClient(mc -> mode == local.carter.office.core.Mode.EXTEND
				? Integer.toString(clientHologram(mc).spec().displaySlot()) : "A");
		context.runOnClient(mc -> {
			HologramSettingsScreen screen = new HologramSettingsScreen(clientHologram(mc), "Test");
			mc.gui.setScreen(screen);
			if (screen.identifyButton() == null || !screen.identifyButton().active) {
				throw new AssertionError("Identify should be there and active in " + mode);
			}
			screen.identifyButton().onPress(new net.minecraft.client.input.KeyEvent(InputConstants.KEY_RETURN, 0, 0));
			mc.gui.setScreen(null);
		});
		context.waitTicks(5);
		String shown = context.computeOnClient(mc -> local.carter.office.client.screens.IdentifyClient.get()
				.labelAt(clientHologram(mc).getBlockPos(), net.minecraft.util.Util.getMillis()));
		context.takeScreenshot("identify-" + mode.name().toLowerCase(java.util.Locale.ROOT));
		if (!want.equals(shown)) {
			throw new AssertionError("Identify in " + mode + " should show " + want + " on the hologram, shows " + shown);
		}
		context.waitTicks(70); // 3.5 s
		String after = context.computeOnClient(mc -> local.carter.office.client.screens.IdentifyClient.get()
				.labelAt(clientHologram(mc).getBlockPos(), net.minecraft.util.Util.getMillis()));
		if (after != null) {
			throw new AssertionError("Identify should be gone after 3 s, still shows " + after);
		}
	}

	/** Puts the test hologram into {@code mode} through its settings screen (as a player would) and waits for the sync. */
	private void switchMode(final ClientGameTestContext context, final TestSingleplayerContext sp, final local.carter.office.core.Mode mode) {
		if (context.computeOnClient(mc -> clientHologram(mc).spec().mode()) == mode) {
			return;
		}
		String label = switch (mode) {
			case MIRROR -> "Mirror";
			case EXTEND -> "Extend";
			case WINDOW -> "Window";
		};
		context.runOnClient(mc -> {
			HologramSettingsScreen screen = new HologramSettingsScreen(clientHologram(mc), "Test");
			mc.gui.setScreen(screen);
			// find first, press after: pressing a mode rebuilds the screen's widgets
			net.minecraft.client.gui.components.Button pick = null;
			for (var child : screen.children()) {
				if (child instanceof net.minecraft.client.gui.components.Button b && b.getMessage().getString().equals(label)) {
					pick = b;
				}
			}
			if (pick == null) throw new AssertionError("no " + label + " button in the hologram settings");
			pick.onPress(new net.minecraft.client.input.KeyEvent(InputConstants.KEY_RETURN, 0, 0));
			mc.gui.setScreen(null);
		});
		sp.getConnection().waitForServerboundPackets();
		context.waitFor(mc -> clientHologram(mc) != null && clientHologram(mc).spec().mode() == mode, 100);
	}

	/** Why the open settings screen's map breaks the fit rule, or null. */
	private static @org.jspecify.annotations.Nullable String mapProblem(final Minecraft mc, final local.carter.office.core.Mode mode) {
		if (!(mc.gui.screen() instanceof HologramSettingsScreen screen)) {
			return "the settings screen isn't open";
		}
		java.util.List<net.minecraft.client.gui.components.AbstractWidget> mine = new java.util.ArrayList<>();
		if (screen.displayMap() == null) {
			return "no display map";
		}
		mine.add(screen.displayMap());
		if (screen.identifyButton() == null) {
			return "no Identify button";
		}
		mine.add(screen.identifyButton());
		if (mode == local.carter.office.core.Mode.EXTEND) {
			if (screen.applyButton() == null) {
				return "no Apply button";
			}
			mine.add(screen.applyButton());
		}
		int w = screen.width;
		int h = screen.height;
		java.util.List<net.minecraft.client.gui.components.AbstractWidget> all = new java.util.ArrayList<>();
		for (var child : screen.children()) {
			if (child instanceof net.minecraft.client.gui.components.AbstractWidget o) {
				all.add(o);
			}
		}
		if (!all.containsAll(mine)) {
			return "the map or its buttons aren't on the screen";
		}
		// no widget (the map, its buttons, Shape / Shows / privacy, Pick up and Done) leaves the window or overlaps another
		for (int i = 0; i < all.size(); i++) {
			var m = all.get(i);
			if (m.getX() < 0 || m.getY() < 0 || m.getRight() > w || m.getBottom() > h) {
				return m.getMessage().getString() + " " + m.getRectangle() + " leaves the " + w + " x " + h + " window";
			}
			for (int j = i + 1; j < all.size(); j++) {
				var o = all.get(j);
				if (o.getRectangle().intersects(m.getRectangle())) {
					return m.getMessage().getString() + " overlaps " + o.getMessage().getString();
				}
			}
		}
		local.carter.office.client.interact.DisplayMap map = screen.displayMap();
		// the map has a row of its own across both columns: from the left column's Shape buttons to the right column's
		// privacy buttons, every other widget wholly above or below it
		int colLeft = Integer.MAX_VALUE, colRight = Integer.MIN_VALUE;
		for (var o : all) {
			String text = o.getMessage().getString();
			if (text.equals("16:9")) {
				colLeft = o.getX();
			} else if (text.equals("Show to…")) {
				colRight = o.getRight();
			}
		}
		if (map.getX() != colLeft || map.getRight() != colRight) {
			return "the map " + map.getRectangle() + " should span both columns (" + colLeft + ".." + colRight + ")";
		}
		for (var o : all) {
			if (o != map && o.getBottom() > map.getY() && o.getY() < map.getBottom()) {
				return o.getMessage().getString() + " " + o.getRectangle() + " shares the map's row " + map.getRectangle();
			}
		}
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
		for (Screen s : map.model().drawOrder()) {
			local.carter.office.client.platform.Platform.Rect b = map.boxOnScreen(s.id());
			if (b == null || b.x() < map.getX() + 2 || b.y() < map.getY() + 2 || b.x() + b.w() > map.getRight() - 2
					|| b.y() + b.h() > map.getBottom() - 2) {
				return "box " + s.id() + " " + b + " leaves the map panel " + map.getRectangle();
			}
			minX = Math.min(minX, b.x());
			minY = Math.min(minY, b.y());
			maxX = Math.max(maxX, b.x() + b.w());
			maxY = Math.max(maxY, b.y() + b.h());
		}
		// one scale fills the panel one way: its width (less the margin), or its height (less the margin and status line)
		int margin = local.carter.office.client.screens.MapLayout.MARGIN;
		if (maxX - minX < map.getWidth() - 2 * margin - 2
				&& maxY - minY < map.getHeight() - 2 * margin - local.carter.office.client.screens.MapLayout.statusHeight(2) - 2) {
			return "the arrangement " + minX + "," + minY + ".." + maxX + "," + maxY + " fills neither way of the map panel "
					+ map.getRectangle();
		}
		return null;
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
	private static String rgb(final int[] c) {
		return "(" + c[0] + "," + c[1] + "," + c[2] + ")";
	}
}
