package local.carter.office.client;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import javax.imageio.ImageIO;

import com.mojang.blaze3d.platform.InputConstants;

import local.carter.office.client.magic.fx.ChargeCircles;
import local.carter.office.client.magic.fx.FxBudget;
import local.carter.office.client.magic.fx.FxClient;
import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GhostBlocks;
import local.carter.office.client.magic.fx.LightPipeline;
import local.carter.office.client.magic.fx.Shake;
import local.carter.office.client.magic.fx.ShaderCompat;
import local.carter.office.client.render.FrameStats;
import local.carter.office.magic.MagicTestKit;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.item.MagicItems;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.SpellRegistry;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Client GameTests for the magic fx core (screenshots land in build/run/clientGameTest/screenshots): the charge circle growing
 * ring by ring, a cast's default visuals, Recall's feet, channel and Mark Home circles, a bolt and ghost blocks, shake, and
 * FxCancel. With {@code -Doffice.measure=true} it also logs rendered fps idle and with five circles plus particles.
 */
@SuppressWarnings("UnstableApiUsage")
public class MagicFxClientGameTests implements FabricClientGameTest {
	private static final int MOUSE_RIGHT = InputConstants.MOUSE_BUTTON_RIGHT;
	private static final Identifier PROBE = Identifier.fromNamespaceAndPath("office-test", "fx_probe");

	@Override
	public void runTest(ClientGameTestContext context) {
		context.getInput().resizeWindow(1600, 900);
		AutoCloseable probe = null;
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			// Scoped to the singleplayer player, whose UUID the client player shares (client code looks up with it).
			java.util.UUID self = sp.getServer().computeOnServer(server -> player(server).getUUID());
			probe = SpellRegistry.overrideForTesting(MagicTestKit.probe("fx_probe", Element.FIRE, 0), self::equals);
			sp.getConnection().waitForChunksRender();
			BlockPos stand = setUpStage(context, sp);
			chargeCircleGrowsRingByRing(context);
			releaseDrawsTheCastCircle(context);
			improvedTransparency(context);
			shaderPackPath(context);
			recallCircles(context, sp, stand);
			boltGhostBlocksAndShake(context, stand);
			chargeInDaylight(context, sp);
			firstPersonCastCircle(context);
			ghostWater(context, sp, stand);
			if (Boolean.getBoolean("office.measure")) {
				measureFps(context, stand);
			}
			context.getInput().pressKey(o -> o.keyToggleGui); // the HUD back
		} finally {
			try {
				if (probe != null) probe.close();
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
			context.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
		}
	}

	/** Night, an open grass yard with a stone wall ahead; the player is bound to Fire at level 7 (tier-3 grimoire in the off hand). */
	private static BlockPos setUpStage(ClientGameTestContext context, TestSingleplayerContext sp) {
		BlockPos[] stand = new BlockPos[1];
		sp.getServer().runOnServer(server -> {
			ServerPlayer p = player(server);
			ServerLevel level = p.level();
			BlockPos feet = p.blockPosition();
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, feet.getX(), feet.getZ()) + 1;
			BlockPos s = new BlockPos(feet.getX(), y, feet.getZ());
			stand[0] = s;
			for (int dx = -9; dx <= 9; dx++) {
				for (int dz = -6; dz <= 12; dz++) {
					level.setBlockAndUpdate(s.offset(dx, -1, dz), Blocks.GRASS_BLOCK.defaultBlockState());
					for (int dy = 0; dy <= 8; dy++) {
						boolean wall = dz == 12 && dy <= 4;
						level.setBlockAndUpdate(s.offset(dx, dy, dz), wall ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.AIR.defaultBlockState());
					}
				}
			}
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set midnight");
			p.setGameMode(GameType.SURVIVAL);
			p.teleportTo(s.getX() + 0.5, s.getY(), s.getZ() + 0.5);
			p.getInventory().clearContent();
			p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(MagicItems.GRIMOIRE));
			MagicAttachments.set(p, MagicData.EMPTY.withElement(Optional.of(Element.FIRE)).withLevel(7).withSelected(Optional.of(PROBE)));
			MagicAttachments.setMana(p, 160);
		});
		sp.getConnection().waitForChunksRender();
		context.runOnClient(mc -> {
			mc.player.setYRot(0F);
			mc.player.setXRot(0F);
		});
		context.getInput().pressKey(o -> o.keyToggleGui); // no HUD in the pictures
		context.waitTicks(20);
		return stand[0];
	}

	private static void chargeCircleGrowsRingByRing(ClientGameTestContext context) {
		BufferedImage front = view(context, CameraType.THIRD_PERSON_FRONT, "magic-fx-idle-third-front");
		BufferedImage before = view(context, CameraType.FIRST_PERSON, "magic-fx-idle");
		context.getInput().holdMouse(MOUSE_RIGHT);
		int[] at = {4, 20, 28, 36, 46};
		BufferedImage full = null;
		for (int i = 0; i < at.length; i++) {
			int ticks = at[i];
			context.waitFor(mc -> mc.player.getTicksUsingItem() >= ticks, 200);
			BufferedImage img = read(context.takeScreenshot("magic-fx-charge-rings-" + (i + 1)));
			if (i == at.length - 1) full = img;
		}
		int showing = context.computeOnClient(mc -> ChargeCircles.showing());
		if (showing != 1) throw new AssertionError("one charging circle expected, got " + showing);
		// First person (the ruling): the circle shows round the edges, but a box of 8 % of the screen's width and height around the
		// crosshair stays as it was.
		double rings = changedShare(before, full, 0.15, 0.05, 0.85, 0.95);
		double crosshair = changedShare(before, full, 0.46, 0.46, 0.54, 0.54);
		System.out.println(String.format(java.util.Locale.ROOT, "[fx] first person full charge: rings changed %.3f, crosshair box changed %.4f", rings, crosshair));
		context.takeScreenshot("magic-fx-charge-full-first-person-crosshair");
		if (rings < 0.03) throw new AssertionError("the first-person charge circle barely shows: " + rings);
		if (crosshair > 0.01) throw new AssertionError("the crosshair box must stay clear in first person, changed " + crosshair);
		context.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
		context.waitTicks(2);
		context.takeScreenshot("magic-fx-charge-full-third-back");
		BufferedImage fullFront = view(context, CameraType.THIRD_PERSON_FRONT, "magic-fx-charge-full-third-front");
		// Seen from outside it is the full circle: a good share of the middle of the view changes.
		double outside = changedShare(front, fullFront, 0.3, 0.2, 0.7, 0.8);
		if (outside < 0.08) throw new AssertionError("the full charge circle barely shows from the front: " + outside);
		context.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
		context.waitTicks(1);
	}

	/**
	 * The shader-pack path (no Iris here, so it is forced): magic drawn on vanilla's translucent layer must still show. Seen from the
	 * front at full charge, against the same view idle.
	 */
	private static void shaderPackPath(ClientGameTestContext context) {
		context.runOnClient(mc -> ShaderCompat.setOverrideForTesting(ShaderCompat.Path.PLAIN));
		try {
			BufferedImage idle = view(context, CameraType.THIRD_PERSON_FRONT, "magic-fx-plain-idle-third-front");
			context.getInput().holdMouse(MOUSE_RIGHT);
			context.waitFor(mc -> mc.player.getTicksUsingItem() >= 46, 200);
			BufferedImage full = view(context, CameraType.THIRD_PERSON_FRONT, "magic-fx-plain-charge-full-third-front");
			context.getInput().releaseMouse(MOUSE_RIGHT);
			double changed = changedShare(idle, full, 0.3, 0.2, 0.7, 0.8);
			System.out.println(String.format(java.util.Locale.ROOT, "[fx] shader-pack (plain) path: circle changed %.3f", changed));
			if (changed < 0.05) throw new AssertionError("on the shader-pack path the charge circle barely shows: " + changed);
			context.waitTicks(30);
		} finally {
			context.runOnClient(mc -> {
				ShaderCompat.setOverrideForTesting(null);
				mc.options.setCameraType(CameraType.FIRST_PERSON);
			});
			context.waitTicks(2);
		}
	}

	/** Switches the camera, lets two frames pass, and takes a screenshot. */
	private static BufferedImage view(ClientGameTestContext context, CameraType type, String name) {
		context.runOnClient(mc -> mc.options.setCameraType(type));
		context.waitTicks(2);
		return read(context.takeScreenshot(name));
	}

	/**
	 * Improved Transparency (order-independent transparency) draws translucent particles through their OIT pipelines: the circle
	 * must still show. Seen from the front at full charge, against the same view idle.
	 */
	private static void improvedTransparency(ClientGameTestContext context) {
		boolean was = context.computeOnClient(mc -> mc.options.improvedTransparency().get());
		context.runOnClient(mc -> mc.options.improvedTransparency().set(true));
		context.waitTicks(20);
		try {
			BufferedImage idle = view(context, CameraType.THIRD_PERSON_FRONT, "magic-fx-oit-idle-third-front");
			context.getInput().holdMouse(MOUSE_RIGHT);
			context.waitFor(mc -> mc.player.getTicksUsingItem() >= 46, 200);
			BufferedImage full = view(context, CameraType.THIRD_PERSON_FRONT, "magic-fx-oit-charge-full-third-front");
			context.getInput().releaseMouse(MOUSE_RIGHT);
			double changed = changedShare(idle, full, 0.3, 0.2, 0.7, 0.8);
			System.out.println(String.format(java.util.Locale.ROOT, "[fx] improved transparency: circle changed %.3f", changed));
			if (changed < 0.08) throw new AssertionError("with Improved Transparency the charge circle barely shows: " + changed);
			context.waitTicks(30);
		} finally {
			context.runOnClient(mc -> {
				mc.options.improvedTransparency().set(was);
				mc.options.setCameraType(CameraType.FIRST_PERSON);
			});
			context.waitTicks(20);
		}
	}

	private static void releaseDrawsTheCastCircle(ClientGameTestContext context) {
		context.getInput().releaseMouse(MOUSE_RIGHT);
		context.waitTicks(3);
		context.takeScreenshot("magic-fx-cast-default");
		context.waitTicks(4);
		context.takeScreenshot("magic-fx-cast-default-later");
		context.waitTicks(20);
		int showing = context.computeOnClient(mc -> ChargeCircles.showing());
		if (showing != 0) throw new AssertionError("the charge circle should be gone after release, got " + showing);
		// A quick tap from the side, so the cast circle shows from outside.
		context.waitTicks(15); // the probe's cooldown
		context.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
		context.getInput().holdMouse(MOUSE_RIGHT);
		context.waitFor(mc -> mc.player.getTicksUsingItem() >= 30, 200);
		context.getInput().releaseMouse(MOUSE_RIGHT);
		context.waitTicks(3);
		context.takeScreenshot("magic-fx-cast-default-third-front");
		context.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
		context.waitTicks(20);
	}

	private static void recallCircles(ClientGameTestContext context, TestSingleplayerContext sp, BlockPos stand) {
		sp.getServer().runOnServer(server -> {
			ServerPlayer p = player(server);
			MagicAttachments.set(p, MagicAttachments.get(p).withSelected(Optional.of(SpellIds.RECALL)));
		});
		context.waitFor(mc -> MagicAttachments.get(mc.player).selected().filter(SpellIds.RECALL::equals).isPresent(), 100);
		context.runOnClient(mc -> {
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			mc.player.setXRot(35F);
		});
		// The local player charging Recall: a gold circle at the feet.
		context.getInput().holdMouse(MOUSE_RIGHT);
		context.waitFor(mc -> mc.player.getTicksUsingItem() >= 46, 200);
		context.takeScreenshot("magic-fx-recall-feet-charge");
		context.getInput().releaseMouse(MOUSE_RIGHT); // no home: fizzles
		context.waitTicks(10);

		// The channel (as the server sends it): follows the caster, rings filling, until cancelled.
		context.runOnClient(mc -> FxClient.play(mc, recall(mc, 60)));
		context.waitTicks(14);
		context.takeScreenshot("magic-fx-recall-channel-early");
		context.waitTicks(30);
		context.takeScreenshot("magic-fx-recall-channel-late");
		int sims = context.computeOnClient(mc -> FxKit.simulations());
		if (sims < 1) throw new AssertionError("the channel's timer should be running");
		context.runOnClient(mc -> FxKit.cancel(mc.player.getId()));
		context.waitTicks(8);
		sims = context.computeOnClient(mc -> FxKit.simulations());
		if (sims != 0) throw new AssertionError("FxCancel should stop the channel's timer, " + sims + " left");
		context.takeScreenshot("magic-fx-recall-cancelled");

		// Mark Home: a flash.
		context.runOnClient(mc -> FxClient.play(mc, recall(mc, 0)));
		context.waitTicks(7);
		context.takeScreenshot("magic-fx-recall-mark-home");
		context.waitTicks(30);

		// Travel to a friend: the same channel with data[1] = 1 (paler gold, sky-blue motes); it ends on FxCancel too.
		context.runOnClient(mc -> {
			Vec3 feet = mc.player.position();
			FxClient.play(mc, new FxEvent(SpellIds.RECALL, mc.player.getId(), feet, new Vec3(0, 1, 0), feet, 0F, 1F, 43L, 60, new int[0],
					new float[] {0F, 1F}));
		});
		context.waitTicks(40);
		context.takeScreenshot("magic-fx-travel-channel");
		context.runOnClient(mc -> FxKit.cancel(mc.player.getId()));
		context.waitTicks(8);
		sims = context.computeOnClient(mc -> FxKit.simulations());
		if (sims != 0) throw new AssertionError("FxCancel should stop the travel channel, " + sims + " left");
		context.runOnClient(mc -> {
			mc.options.setCameraType(CameraType.FIRST_PERSON);
			mc.player.setXRot(0F);
		});
	}

	private static FxEvent recall(Minecraft mc, int duration) {
		Vec3 feet = mc.player.position();
		return new FxEvent(SpellIds.RECALL, mc.player.getId(), feet, new Vec3(0, 1, 0), feet, 0F, 1F, 42L, duration, new int[0],
				new float[] {duration == 0 ? 1F : 0F});
	}

	private static void boltGhostBlocksAndShake(ClientGameTestContext context, BlockPos stand) {
		context.runOnClient(mc -> {
			Vec3 o = Vec3.atBottomCenterOf(stand).add(0, 1.6, 0);
			FxEvent e = new FxEvent(SpellIds.SPARK, mc.player.getId(), o, new Vec3(0, 0, 1), o.add(0, 0, 8), 1F, 1F, 7L, 0, new int[0], new float[0]);
			FxKit kit = new FxKit(e, mc.level);
			kit.bolt(o.add(1.5, 1.5, 4), Vec3.atBottomCenterOf(stand.offset(2, 0, 9)), 7L, Element.LIGHTNING.argb());
			kit.bolt(o.add(-1.5, 1.8, 4), Vec3.atBottomCenterOf(stand.offset(-3, 0, 8)), 8L, Element.SHADOW.argb());
			for (int i = -4; i <= 4; i++) {
				kit.ghostBlock(stand.offset(i, -1, 6), Blocks.GRASS_BLOCK.defaultBlockState(), Vec3.ZERO, 30, GhostBlocks.Motion.POP);
				kit.ghostBlock(stand.offset(i, 0, 8), Blocks.STONE.defaultBlockState(), Vec3.ZERO, 40, GhostBlocks.Motion.RISE);
			}
			kit.shake(1.5F, 20);
		});
		context.waitTicks(2);
		boolean shaking = context.computeOnClient(mc -> Shake.shaking());
		if (!shaking) throw new AssertionError("a shake within range should be running");
		context.takeScreenshot("magic-fx-bolt-ghosts");
		context.waitTicks(6);
		int ghosts = context.computeOnClient(mc -> GhostBlocks.live());
		if (ghosts != 18) throw new AssertionError("18 ghost blocks expected, got " + ghosts);
		context.takeScreenshot("magic-fx-ghosts-risen");
		context.waitTicks(45);
		ghosts = context.computeOnClient(mc -> GhostBlocks.live());
		if (ghosts != 0) throw new AssertionError("ghost blocks should be gone, " + ghosts + " left");

		// Caps: one event draws at most 6 circles; timers are clamped to 600 ticks and 64 at once.
		int before = context.computeOnClient(MagicFxClientGameTests::circles);
		context.runOnClient(mc -> {
			Vec3 o = Vec3.atBottomCenterOf(stand).add(0, 2, 6);
			FxKit kit = new FxKit(new FxEvent(PROBE, -1, o, new Vec3(0, 0, 1), o, 0F, 1F, 9L, 0, new int[0], new float[0]), mc.level);
			for (int i = 0; i < 10; i++) kit.circle(o, new Vec3(0, 0, -1), Element.WATER.argb(), 0.5F, 2, 20);
			for (int i = 0; i < 80; i++) kit.simulate(1, 100_000, t -> { });
		});
		context.waitTicks(2); // the engine adds new particles on its next tick
		int added = context.computeOnClient(MagicFxClientGameTests::circles) - before;
		if (added != FxKit.MAX_CIRCLES) throw new AssertionError("one event should draw " + FxKit.MAX_CIRCLES + " circles, drew " + added);
		int sims = context.computeOnClient(mc -> FxKit.simulations());
		if (sims > FxKit.MAX_SIMULATIONS) throw new AssertionError("at most " + FxKit.MAX_SIMULATIONS + " timers, running " + sims);
		context.runOnClient(mc -> FxKit.cancel(-1));
		context.waitTicks(2);
		sims = context.computeOnClient(mc -> FxKit.simulations());
		if (sims != 0) throw new AssertionError("cancel should stop the timers, " + sims + " left");
	}

	/** The same full charge at noon: additive light must still read against a bright sky and grass. */
	private static void chargeInDaylight(ClientGameTestContext context, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
			ServerPlayer p = player(server);
			MagicAttachments.set(p, MagicAttachments.get(p).withSelected(Optional.of(PROBE)));
			MagicAttachments.setMana(p, 160);
		});
		context.waitFor(mc -> MagicAttachments.get(mc.player).selected().filter(PROBE::equals).isPresent(), 100);
		context.waitTicks(10);
		context.getInput().holdMouse(MOUSE_RIGHT);
		context.waitFor(mc -> mc.player.getTicksUsingItem() >= 46, 200);
		context.takeScreenshot("magic-fx-charge-full-day");
		context.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
		context.waitTicks(2);
		context.takeScreenshot("magic-fx-charge-full-day-third-front");
		context.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
		context.getInput().releaseMouse(MOUSE_RIGHT);
		context.waitTicks(3);
		context.takeScreenshot("magic-fx-cast-default-day");
		context.waitTicks(30);
		sp.getServer().runOnServer(server -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set midnight"));
		context.waitTicks(5);
	}

	/**
	 * A lane's cast circle held out 1.5 blocks in front of the caster, facing them: in first person it takes the charge circle's
	 * look (dim, no flare, clear middle), so the crosshair box stays as it was; from outside it is the full circle; and someone
	 * else's circle in the same place keeps its full look (it does cover the middle).
	 */
	private static void firstPersonCastCircle(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			mc.player.setYRot(0F);
			mc.player.setXRot(0F);
		});
		BufferedImage idleFront = view(context, CameraType.THIRD_PERSON_FRONT, "magic-fx-cast-circle-idle-third-front");
		BufferedImage idle = view(context, CameraType.FIRST_PERSON, "magic-fx-cast-circle-idle");
		context.runOnClient(mc -> castCircleAhead(mc, mc.player.getId()));
		context.waitTicks(12); // every ring in
		BufferedImage own = read(context.takeScreenshot("magic-fx-cast-circle-first-person"));
		double rings = changedShare(idle, own, 0.15, 0.05, 0.85, 0.95);
		double crosshair = changedShare(idle, own, 0.46, 0.46, 0.54, 0.54);
		BufferedImage front = view(context, CameraType.THIRD_PERSON_FRONT, "magic-fx-cast-circle-third-front");
		double outside = changedShare(idleFront, front, 0.3, 0.2, 0.7, 0.8);
		double outsideMiddle = changedShare(idleFront, front, 0.46, 0.46, 0.54, 0.54); // glyph and centre glow, drawn at once
		context.runOnClient(mc -> {
			FxKit.cancel(mc.player.getId());
			mc.options.setCameraType(CameraType.FIRST_PERSON);
		});
		context.waitTicks(10);
		context.runOnClient(mc -> castCircleAhead(mc, OTHER_CASTER));
		context.waitTicks(12);
		BufferedImage other = read(context.takeScreenshot("magic-fx-cast-circle-first-person-other-caster"));
		double otherCrosshair = changedShare(idle, other, 0.46, 0.46, 0.54, 0.54);
		context.runOnClient(mc -> FxKit.cancel(OTHER_CASTER));
		context.waitTicks(10);
		System.out.println(String.format(java.util.Locale.ROOT,
				"[fx] first-person cast circle: rings changed %.3f, crosshair box %.4f; third person front %.3f (middle %.3f); other caster's crosshair box %.4f",
				rings, crosshair, outside, outsideMiddle, otherCrosshair));
		if (rings < 0.03) throw new AssertionError("the first-person cast circle barely shows: " + rings);
		if (crosshair > 0.01) throw new AssertionError("the crosshair box must stay clear in first person, changed " + crosshair);
		if (outside < 0.08) throw new AssertionError("from outside the cast circle should show in full: " + outside);
		if (outsideMiddle < 0.05) throw new AssertionError("in third person the cast circle's middle should be drawn: " + outsideMiddle);
		if (otherCrosshair < 0.05) throw new AssertionError("someone else's circle should keep its full look over the middle: " + otherCrosshair);
	}

	private static final int OTHER_CASTER = -7;
	private static final int WATER_CASTER = -11;

	/** A cast circle like a lane's: five rings, radius 1, 1.5 blocks ahead of the eye on the look line, facing the eye. */
	private static void castCircleAhead(Minecraft mc, int casterId) {
		Vec3 eye = mc.player.getEyePosition();
		Vec3 look = mc.player.getViewVector(1F);
		Vec3 at = eye.add(look.scale(1.5));
		FxKit kit = new FxKit(new FxEvent(PROBE, casterId, eye, look, at, 1F, 1F, 11L, 0, new int[0], new float[0]), mc.level);
		kit.circle(at, look.reverse(), Element.FIRE.argb(), 1.0F, 5, 60);
	}

	/**
	 * Ghost water: a 5 x 3 slab, one block deep, seen from straight above at noon. It must read blue, and as one body: the band
	 * where an inner side would show (between the slab's second and outer columns) looks like the water beside it. The same slab
	 * made of 15 separate events (each its own body, so every inner side is drawn) is the control: there the band stands out. Then
	 * the shader-pack path and Improved Transparency, a view from the side, a popping wave, and the ghost-block budget.
	 */
	private static void ghostWater(ClientGameTestContext context, TestSingleplayerContext sp, BlockPos stand) {
		BlockPos mid = stand.offset(0, 0, 6); // the slab's middle cell, on the grass
		double fovEffect = context.computeOnClient(mc -> mc.options.fovEffectScale().get());
		sp.getServer().runOnServer(server -> {
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
			ServerPlayer p = player(server);
			p.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY); // its tier-3 motes would drift through the measured pixels
			p.setGameMode(GameType.CREATIVE);
			p.getAbilities().flying = true;
			p.onUpdateAbilities();
			p.teleportTo(mid.getX() + 0.5, mid.getY() + 4, mid.getZ() + 0.5);
		});
		context.waitFor(mc -> Math.abs(mc.player.getY() - (mid.getY() + 4)) < 0.01 && mc.player.getAbilities().flying, 200);
		context.runOnClient(mc -> {
			mc.options.fovEffectScale().set(0.0); // flying widens the view; the projection below needs the plain FOV
			mc.options.setCameraType(CameraType.FIRST_PERSON);
			mc.player.setYRot(0F);
			mc.player.setXRot(90F);
		});
		context.waitTicks(10);
		try {
			double[] view = context.computeOnClient(mc -> {
				Vec3 eye = mc.gameRenderer.mainCamera().position();
				return new double[] {mc.options.fov().get(), eye.y - (mid.getY() + 1), eye.x - (mid.getX() + 0.5), eye.z - (mid.getZ() + 0.5)};
			});
			System.out.println(String.format(java.util.Locale.ROOT, "[fx] ghost water view: fov %.0f, %.3f above the surface, off by %.3f, %.3f",
					view[0], view[1], view[2], view[3]));
			BufferedImage idle = read(context.takeScreenshot("magic-fx-ghost-water-idle-top"));

			// One event: one body of water.
			context.runOnClient(mc -> slab(mc, mid, true));
			context.waitTicks(4);
			int cells = context.computeOnClient(mc -> GhostBlocks.liveFluids());
			if (cells != 15) throw new AssertionError("15 cells of ghost water expected, got " + cells);
			BufferedImage body = read(context.takeScreenshot("magic-fx-ghost-water-slab-top"));
			double[] tint = blueness(idle, body, view);
			double seam = seam(body, view);
			// The control: every cell its own event.
			context.runOnClient(mc -> FxKit.cancel(WATER_CASTER));
			context.waitTicks(2);
			context.runOnClient(mc -> slab(mc, mid, false));
			context.waitTicks(4);
			BufferedImage cubes = read(context.takeScreenshot("magic-fx-ghost-water-cubes-top"));
			double cubeSeam = seam(cubes, view);
			context.runOnClient(mc -> FxKit.cancel(WATER_CASTER));
			context.waitTicks(2);
			System.out.println(String.format(java.util.Locale.ROOT,
					"[fx] ghost water: rgb %.0f %.0f %.0f (idle %.0f %.0f %.0f); seam band vs water beside it: one body %.1f, separate cubes %.1f",
					tint[0], tint[1], tint[2], tint[3], tint[4], tint[5], seam, cubeSeam));
			assertBlue("one body", tint);
			if (seam > 8) throw new AssertionError("the slab shows an inner side (a grid line): band differs by " + seam);
			if (cubeSeam < 2 * seam + 10) {
				throw new AssertionError("the control (separate cubes) should show its inner sides: " + cubeSeam + " vs " + seam);
			}

			// The shader-pack path (forced: no Iris here) draws it on vanilla's entity-translucent layer.
			context.runOnClient(mc -> ShaderCompat.setOverrideForTesting(ShaderCompat.Path.PLAIN));
			try {
				context.runOnClient(mc -> slab(mc, mid, true));
				context.waitTicks(4);
				BufferedImage plain = read(context.takeScreenshot("magic-fx-ghost-water-plain-top"));
				double[] plainTint = blueness(idle, plain, view);
				double plainSeam = seam(plain, view);
				System.out.println(String.format(java.util.Locale.ROOT, "[fx] ghost water, shader-pack path: rgb %.0f %.0f %.0f, seam %.1f",
						plainTint[0], plainTint[1], plainTint[2], plainSeam));
				assertBlue("shader-pack path", plainTint);
				if (plainSeam > 8) throw new AssertionError("on the shader-pack path the slab shows a grid line: " + plainSeam);
			} finally {
				context.runOnClient(mc -> {
					ShaderCompat.setOverrideForTesting(null);
					FxKit.cancel(WATER_CASTER);
				});
			}

			// Improved Transparency.
			boolean was = context.computeOnClient(mc -> mc.options.improvedTransparency().get());
			context.runOnClient(mc -> mc.options.improvedTransparency().set(true));
			context.waitTicks(20);
			try {
				BufferedImage oitIdle = read(context.takeScreenshot("magic-fx-ghost-water-oit-idle-top"));
				context.runOnClient(mc -> slab(mc, mid, true));
				context.waitTicks(4);
				BufferedImage oit = read(context.takeScreenshot("magic-fx-ghost-water-oit-top"));
				double[] oitTint = blueness(oitIdle, oit, view);
				System.out.println(String.format(java.util.Locale.ROOT, "[fx] ghost water, improved transparency: rgb %.0f %.0f %.0f, seam %.1f",
						oitTint[0], oitTint[1], oitTint[2], seam(oit, view)));
				assertBlue("improved transparency", oitTint);
			} finally {
				context.runOnClient(mc -> {
					mc.options.improvedTransparency().set(was);
					FxKit.cancel(WATER_CASTER);
				});
				context.waitTicks(20);
			}

			// From the side, standing on the grass a few blocks back: the look lanes get.
			sp.getServer().runOnServer(server -> player(server).teleportTo(mid.getX() + 0.5, mid.getY() + 1.2, mid.getZ() - 3.5));
			context.waitFor(mc -> mc.player.getZ() < mid.getZ() - 3, 200);
			context.runOnClient(mc -> {
				mc.player.setYRot(0F);
				mc.player.setXRot(25F);
				slab(mc, mid, true);
			});
			context.waitTicks(4);
			context.takeScreenshot("magic-fx-ghost-water-slab-side");
			context.runOnClient(mc -> FxKit.cancel(WATER_CASTER));
			// A wave: columns popping one after another, as a lane would lay one with simulate.
			context.runOnClient(mc -> {
				FxKit kit = waterKit(mc);
				kit.simulate(2, 10, t -> {
					for (int z = -1; z <= 1; z++) {
						kit.ghostFluid(mid.offset(t / 2 - 2, 0, z), 1F, 20, GhostBlocks.Motion.POP);
					}
				});
			});
			context.waitTicks(7);
			context.takeScreenshot("magic-fx-ghost-water-wave");
			context.waitTicks(30);
			int left = context.computeOnClient(mc -> GhostBlocks.liveFluids());
			if (left != 0) throw new AssertionError("the wave's water should be gone, " + left + " left");
			// Water counts against the event's ghost-block budget.
			context.runOnClient(mc -> {
				FxKit kit = waterKit(mc);
				for (int i = 0; i < 140; i++) kit.ghostFluid(mid.offset(i % 14 - 7, 6, i / 14), 0.5F, 40, GhostBlocks.Motion.STATIC);
			});
			int granted = context.computeOnClient(mc -> GhostBlocks.liveFluids());
			context.runOnClient(mc -> FxKit.cancel(WATER_CASTER));
			if (granted != FxBudget.GHOST_BLOCKS) throw new AssertionError("one event's water should stop at " + FxBudget.GHOST_BLOCKS + ", got " + granted);
		} finally {
			context.runOnClient(mc -> {
				FxKit.cancel(WATER_CASTER);
				mc.options.fovEffectScale().set(fovEffect);
				mc.player.setXRot(0F);
			});
			sp.getServer().runOnServer(server -> {
				ServerPlayer p = player(server);
				p.getAbilities().flying = false;
				p.setGameMode(GameType.SURVIVAL);
				p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(MagicItems.GRIMOIRE));
				p.onUpdateAbilities();
				p.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set midnight");
			});
			context.waitTicks(20);
		}
	}

	private static FxKit waterKit(Minecraft mc) {
		Vec3 o = mc.player.position();
		return new FxKit(new FxEvent(PROBE, WATER_CASTER, o, new Vec3(0, 0, 1), o, 1F, 1F, 13L, 0, new int[0], new float[0]), mc.level);
	}

	/** The 5 x 3 slab about {@code mid}: one event ({@code oneBody}) or one event per cell. */
	private static void slab(Minecraft mc, BlockPos mid, boolean oneBody) {
		FxKit kit = waterKit(mc);
		for (int x = -2; x <= 2; x++) {
			for (int z = -1; z <= 1; z++) {
				(oneBody ? kit : waterKit(mc)).ghostFluid(mid.offset(x, 0, z), 1F, 400, GhostBlocks.Motion.STATIC);
			}
		}
	}

	private static void assertBlue(String what, double[] rgbIdle) {
		double r = rgbIdle[0];
		double g = rgbIdle[1];
		double b = rgbIdle[2];
		double idleBlue = rgbIdle[5] - Math.max(rgbIdle[3], rgbIdle[4]);
		if (!(b > r + 25 && b > g + 5 && b - Math.max(r, g) > idleBlue + 30)) {
			throw new AssertionError(what + ": ghost water should be blue-tinted, rgb " + r + " " + g + " " + b);
		}
	}

	/**
	 * Mean colour of the middle cell's water seen from straight above (and the same pixels idle), from the view {fov, height above
	 * the surface, ...}: {r, g, b, idle r, idle g, idle b}.
	 */
	private static double[] blueness(BufferedImage idle, BufferedImage img, double[] view) {
		double h = view[1];
		double[] water = mean(img, view, -0.3 / h, 0.3 / h, -0.3 / h, 0.3 / h);
		double[] grass = mean(idle, view, -0.3 / h, 0.3 / h, -0.3 / h, 0.3 / h);
		return new double[] {water[0], water[1], water[2], grass[0], grass[1], grass[2]};
	}

	/**
	 * How much the band where an inner side would show differs from water with the same texture under it (sum of the channel
	 * differences of their means; the larger of the slab's two sides). Looking straight down from {@code h} above the surface, the
	 * side 1.5 blocks out (between the second and outer columns) would show through the surface from 1.5 - w to 1.5 blocks out,
	 * w = 1.5 / (h + 1): the last w of the second column. The reference is the last w of the second column on the other side (the
	 * water sprite repeats every cell, so the same texels), where no inner side shows in either case. Rows stay within a quarter
	 * block of the middle row's centre, clear of the row boundaries. Every cell's surface: a row across a cell boundary.
	 */
	private static double seam(BufferedImage img, double[] view) {
		double h = view[1];
		double y = 0.25 / h;
		double w = 1.5 / (h + 1);
		double gap = 0.04;
		double worst = 0;
		for (int side = -1; side <= 1; side += 2) {
			double[] band = mean(img, view, side * (1.5 - w + gap) / h, side * (1.5 - gap) / h, -y, y);
			double[] same = mean(img, view, -side * (0.5 + gap) / h, -side * (0.5 + w - gap) / h, -y, y);
			double d = Math.abs(band[0] - same[0]) + Math.abs(band[1] - same[1]) + Math.abs(band[2] - same[2]);
			worst = Math.max(worst, d);
		}
		return worst;
	}

	/** Mean rgb of the pixels between the view tangents {@code x0..x1} (right of the middle) and {@code y0..y1} (up). */
	private static double[] mean(BufferedImage img, double[] view, double x0, double x1, double y0, double y1) {
		int w = img.getWidth();
		int h = img.getHeight();
		double t = Math.tan(Math.toRadians(view[0]) / 2);
		double aspect = w / (double) h;
		int px0 = (int) Math.round(w / 2.0 * (1 + Math.min(x0, x1) / (t * aspect)));
		int px1 = (int) Math.round(w / 2.0 * (1 + Math.max(x0, x1) / (t * aspect)));
		int py0 = (int) Math.round(h / 2.0 * (1 - Math.max(y0, y1) / t));
		int py1 = (int) Math.round(h / 2.0 * (1 - Math.min(y0, y1) / t));
		double r = 0;
		double g = 0;
		double b = 0;
		int n = 0;
		for (int y = py0; y < py1; y++) {
			for (int x = px0; x < px1; x++) {
				int p = img.getRGB(x, y);
				r += (p >> 16) & 0xFF;
				g += (p >> 8) & 0xFF;
				b += p & 0xFF;
				n++;
			}
		}
		return n == 0 ? new double[3] : new double[] {r / n, g / n, b / n};
	}

	/** Rendered fps: idle, then five held circles (as five players charging) plus a cast burst every 10 ticks. */
	private static void measureFps(ClientGameTestContext context, BlockPos stand) {
		context.runOnClient(mc -> {
			mc.options.enableVsync().set(false);
			mc.options.framerateLimit().set(260);
			// The test gives no input for a while: vanilla's AFK limiter would cap it at ~25 fps.
			mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
		});
		context.waitTicks(40);
		double idle = measure(context, "idle", 6000);
		context.runOnClient(mc -> {
			for (int i = 0; i < 5; i++) {
				Vec3 c = Vec3.atBottomCenterOf(stand.offset(-4 + 2 * i, 0, 5)).add(0, 1.4, 0);
				FxEvent e = new FxEvent(PROBE, -1, c, new Vec3(0, 0, -1), c, 1F, 1F, i, 0, new int[0], new float[0]);
				new FxKit(e, mc.level).circle(c, new Vec3(0, 0, -1), Element.values()[i].argb(), 1.15F, 5, 20 * 30);
			}
		});
		context.waitTicks(10);
		context.runOnClient(mc -> LightPipeline.takeExtractMillisPerFrame());
		measure(context, "5 full circles (5 players charging)", 6000);
		double layout5 = context.computeOnClient(mc -> LightPipeline.takeExtractMillisPerFrame());
		System.out.println(String.format(java.util.Locale.ROOT, "[measure] magic fx 5 circles: layout=%.2fms/frame", layout5));
		long end = System.currentTimeMillis() + 6000;
		context.runOnClient(mc -> FrameStats.record(true));
		context.runOnClient(mc -> LightPipeline.takeExtractMillisPerFrame());
		int tick = 0;
		while (System.currentTimeMillis() < end) {
			if (tick++ % 10 == 0) {
				int n = tick;
				context.runOnClient(mc -> {
					for (int i = 0; i < 5; i++) {
						Vec3 o = Vec3.atBottomCenterOf(stand.offset(-4 + 2 * i, 0, 3)).add(0, 1.6, 0);
						FxClient.play(mc, new FxEvent(PROBE, mc.player.getId(), o, new Vec3(0, 0, 1), o, 1F, 1F, n * 7L + i, 0, new int[0], new float[0]));
					}
				});
			}
			context.waitTick();
		}
		FrameStats.Snapshot s = context.computeOnClient(mc -> FrameStats.snapshot());
		context.runOnClient(mc -> FrameStats.record(false));
		double layout = context.computeOnClient(mc -> LightPipeline.takeExtractMillisPerFrame());
		String particles = context.computeOnClient(mc -> mc.particleEngine.countParticles());
		System.out.println(String.format(java.util.Locale.ROOT,
				"[measure] magic fx: idle fps=%.1f, 5 circles + 5 casts/10 ticks fps=%.1f worstFrame=%.0fms circle layout=%.2fms/frame particles=%s",
				idle, s.fps(), s.worstFrameMs(), layout, particles));
		context.takeScreenshot("magic-fx-measure-busy");
		context.runOnClient(mc -> {
			mc.options.enableVsync().set(true);
			mc.options.framerateLimit().set(120);
			mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.AFK);
		});
	}

	private static double measure(ClientGameTestContext context, String what, long ms) {
		context.runOnClient(mc -> FrameStats.record(true));
		long end = System.currentTimeMillis() + ms;
		while (System.currentTimeMillis() < end) {
			context.waitTick();
		}
		FrameStats.Snapshot s = context.computeOnClient(mc -> FrameStats.snapshot());
		context.runOnClient(mc -> FrameStats.record(false));
		System.out.println(String.format(java.util.Locale.ROOT, "[measure] magic fx %s: fps=%.1f worstFrame=%.0fms", what, s.fps(), s.worstFrameMs()));
		return s.fps();
	}

	/** Share of pixels in the given box (fractions of the image) that differ clearly between {@code a} and {@code b}. */
	private static double changedShare(BufferedImage a, BufferedImage b, double x0, double y0, double x1, double y1) {
		int w = Math.min(a.getWidth(), b.getWidth());
		int h = Math.min(a.getHeight(), b.getHeight());
		int n = 0;
		int changed = 0;
		for (int y = (int) (h * y0); y < (int) (h * y1); y += 2) {
			for (int x = (int) (w * x0); x < (int) (w * x1); x += 2) {
				int p = a.getRGB(x, y);
				int q = b.getRGB(x, y);
				int d = Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)) + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF)) + Math.abs((p & 0xFF) - (q & 0xFF));
				n++;
				if (d > 40) changed++;
			}
		}
		return n == 0 ? 0 : changed / (double) n;
	}

	/** Circles and bolts alive in the magic particle group ("OM n" in the engine's count). */
	private static int circles(Minecraft mc) {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("OM (\\d+)").matcher(mc.particleEngine.countParticles());
		return m.find() ? Integer.parseInt(m.group(1)) : 0;
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().get(0);
	}

	private static BufferedImage read(Path screenshot) {
		try {
			return ImageIO.read(screenshot.toFile());
		} catch (IOException e) {
			throw new AssertionError("cannot read screenshot " + screenshot, e);
		}
	}
}
