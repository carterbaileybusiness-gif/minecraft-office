package local.carter.office.client;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.imageio.ImageIO;

import com.mojang.blaze3d.platform.InputConstants;

import local.carter.office.client.magic.MagicInputGate;
import local.carter.office.client.magic.MagicKeys;
import local.carter.office.client.magic.SpellWheel;
import local.carter.office.client.magic.WheelLayout;
import local.carter.office.client.magic.hud.ManaBarHud;
import local.carter.office.client.magic.hud.ManaBarLayout;
import local.carter.office.client.magic.screen.ChoiceLayout;
import local.carter.office.client.magic.screen.ElementChoiceScreen;
import local.carter.office.client.magic.screen.GrimoireScreen;
import local.carter.office.client.magic.screen.SignLayout.Rect;
import local.carter.office.client.pointer.PointerClient;
import local.carter.office.client.render.HologramPlacement;
import local.carter.office.client.zoom.ZoomClient;
import local.carter.office.client.zoom.ZoomController;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Vec3d;
import local.carter.office.hologram.HologramBlockEntity;
import local.carter.office.hologram.HologramEmitterBlock;
import local.carter.office.hologram.OfficeBlocks;
import local.carter.office.magic.MagicTestKit;
import local.carter.office.magic.cast.CastService;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.ManaMath;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.item.MagicItems;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.SpellCost;
import local.carter.office.magic.spell.SpellRegistry;

import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Client GameTests for magic screens and HUD (screenshots land in build/run/clientGameTest/screenshots). The player stands in
 * a closed obsidian room, so everything behind the screens is a known near-black background.
 */
@SuppressWarnings("UnstableApiUsage")
public class MagicClientGameTests implements FabricClientGameTest {
	private static final int MOUSE_RIGHT = InputConstants.MOUSE_BUTTON_RIGHT;
	private static final int MOUSE_LEFT = InputConstants.MOUSE_BUTTON_LEFT;

	@Override
	public void runTest(ClientGameTestContext context) {
		int savedScale = context.computeOnClient(mc -> mc.options.guiScale().get());
		// A 1080p window, so GUI scale 4 is available (the default test window only allows 2).
		context.getInput().resizeWindow(1920, 1080);
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			setUpStage(context, sp);
			firstOpenShowsElementChoice(context, sp);
			bindingFireClosesAndShowsManaBar(context, sp);
			grimoireScreenSelectsSpells(context, sp);
			manaBarOnlyInCastingStance(context, sp);
			rebindingSealRebindsOnChoice(context, sp);
			friendsPageAsksToTravel(context, sp);
			castingInputPrecedence(context, sp);
		} finally {
			setGuiScale(context, savedScale);
		}
	}

	/** A closed 5×4×5 obsidian room around the player (survival, grimoire in the main hand, unbound). */
	private static void setUpStage(ClientGameTestContext context, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			ServerPlayer p = player(server);
			ServerLevel level = p.level();
			BlockPos feet = p.blockPosition();
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, feet.getX(), feet.getZ()) + 3;
			BlockPos stand = new BlockPos(feet.getX(), y, feet.getZ());
			for (int dx = -3; dx <= 3; dx++) {
				for (int dy = -1; dy <= 4; dy++) {
					for (int dz = -3; dz <= 3; dz++) {
						boolean wall = Math.abs(dx) == 3 || Math.abs(dz) == 3 || dy == -1 || dy == 4;
						level.setBlockAndUpdate(stand.offset(dx, dy, dz), wall ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState());
					}
				}
			}
			p.setGameMode(GameType.SURVIVAL);
			p.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
			p.setYRot(0f);
			p.setXRot(0f);
			p.getInventory().clearContent();
			p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(MagicItems.GRIMOIRE));
			MagicAttachments.set(p, MagicData.EMPTY);
			MagicAttachments.setMana(p, 100);
		});
		sp.getConnection().waitForChunksRender();
		context.waitTicks(10);
	}

	private static void firstOpenShowsElementChoice(ClientGameTestContext context, TestSingleplayerContext sp) {
		setGuiScale(context, 1);
		context.getInput().pressMouse(MOUSE_RIGHT);
		context.waitForScreen(ElementChoiceScreen.class);
		centreCursor(context); // over the book: no sigil hovered
		context.waitFor(mc -> mc.gui.screen() instanceof ElementChoiceScreen s && s.introDone());
		assertSigilsDrawn(context, "magic-choice-gui1");

		setGuiScale(context, 4);
		centreCursor(context);
		context.waitTicks(12);
		assertSigilsDrawn(context, "magic-choice-gui4");

		// Hover (pauses the orbit, previews the element on the pages).
		hoverSigil(context, Element.WATER.ordinal());
		context.waitTicks(12);
		int active = context.computeOnClient(mc -> ((ElementChoiceScreen) mc.gui.screen()).active());
		if (active != Element.WATER.ordinal()) throw new AssertionError("hovering Water should preview it, active = " + active);
		float a = context.computeOnClient(mc -> ((ElementChoiceScreen) mc.gui.screen()).orbitDeg());
		context.waitTicks(10);
		float b = context.computeOnClient(mc -> ((ElementChoiceScreen) mc.gui.screen()).orbitDeg());
		if (a != b) throw new AssertionError("hover should pause the orbit: " + a + " -> " + b);
		context.takeScreenshot("magic-choice-hover-gui4");
		setGuiScale(context, 1);
		context.waitTicks(3);
		hoverSigil(context, Element.LIGHTNING.ordinal());
		context.waitTicks(12);
		context.takeScreenshot("magic-choice-hover-gui1");
		setGuiScale(context, 4);
		context.waitTicks(3);
	}

	/** Each sigil rect (from the screen's own layout) is mostly sigil, not background. */
	private static void assertSigilsDrawn(ClientGameTestContext context, String name) {
		ChoiceLayout.Layout l = context.computeOnClient(mc -> ((ElementChoiceScreen) mc.gui.screen()).layout());
		int scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		BufferedImage img = read(context.takeScreenshot(name));
		int bg = img.getRGB(2, 2);
		for (int i = 0; i < ChoiceLayout.SIGILS; i++) {
			Rect r = l.sigils().get(i);
			int n = 0, hit = 0;
			for (int y = r.y() * scale; y < r.bottom() * scale; y++) {
				for (int x = r.x() * scale; x < r.right() * scale; x++) {
					if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) continue;
					n++;
					if (dist(img.getRGB(x, y), bg) > 36) hit++;
				}
			}
			if (n == 0 || hit < 0.30 * n) {
				throw new AssertionError(name + ": sigil " + Element.values()[i] + " at " + r + " is only " + hit + "/" + n
						+ " non-background pixels (bg " + Integer.toHexString(bg) + ", gui scale " + scale + ")");
			}
		}
	}

	private static void bindingFireClosesAndShowsManaBar(ClientGameTestContext context, TestSingleplayerContext sp) {
		hoverSigil(context, Element.FIRE.ordinal());
		context.waitTicks(3);
		hoverSigil(context, Element.FIRE.ordinal()); // the orbit is paused now: aim again, then click
		context.waitTicks(1);
		context.getInput().pressMouse(MOUSE_LEFT);
		context.waitTicks(2);
		ElementChoiceScreen.State state = context.computeOnClient(mc -> ((ElementChoiceScreen) mc.gui.screen()).state());
		if (state != ElementChoiceScreen.State.CONFIRM) throw new AssertionError("clicking Fire should ask to confirm, state " + state);
		context.waitTicks(20);
		context.takeScreenshot("magic-choice-confirm-gui4");
		setGuiScale(context, 1);
		context.waitTicks(3);
		context.takeScreenshot("magic-choice-confirm-gui1");
		setGuiScale(context, 4);
		context.waitTicks(2);

		context.clickScreenButton("magic.office.bind");
		state = context.computeOnClient(mc -> mc.gui.screen() instanceof ElementChoiceScreen s ? s.state() : null);
		if (state != ElementChoiceScreen.State.BINDING) throw new AssertionError("Bind should wait for the server, state " + state);
		// The flash starts only once the server's binding has synced back.
		context.waitFor(mc -> mc.gui.screen() instanceof ElementChoiceScreen s && s.state() == ElementChoiceScreen.State.BOUND, 40);
		context.waitTicks(6);
		context.takeScreenshot("magic-choice-bound-gui4");
		context.waitFor(mc -> mc.gui.screen() == null, 60);
		sp.getConnection().waitForServerboundPackets();
		Optional<Element> bound = sp.getServer().computeOnServer(server -> MagicAttachments.get(player(server)).element());
		if (!bound.equals(Optional.of(Element.FIRE))) throw new AssertionError("expected FIRE, got " + bound);
		context.waitFor(ManaBarHud::active);
		context.waitTicks(10);

		assertManaBar(context, "magic-hud-gui4", Element.FIRE.argb());
		setGuiScale(context, 1);
		context.waitTicks(3);
		assertManaBar(context, "magic-hud-gui1", Element.FIRE.argb());
		setGuiScale(context, 4);
		context.waitTicks(3);
	}

	/** In the bar's middle row, the filled part is the element colour on at least half of its width. */
	private static void assertManaBar(ClientGameTestContext context, String name, int argb) {
		int scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		ManaBarLayout.Layout l = context.computeOnClient(mc -> ManaBarLayout.layout(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()));
		BufferedImage img = read(context.takeScreenshot(name));
		Rect bar = l.bar();
		int filled = ManaBarLayout.fillWidth(100, 100) * scale;
		int y = (bar.y() + 2) * scale + scale / 2;
		int match = 0;
		for (int x = bar.x() * scale; x < bar.x() * scale + filled; x++) if (dist(img.getRGB(x, y), argb) <= 60) match++;
		if (match < filled / 2) {
			throw new AssertionError(name + ": only " + match + "/" + filled + " bar pixels are the element colour (sample "
					+ Integer.toHexString(img.getRGB(bar.x() * scale + filled / 3, y)) + ")");
		}
	}

	private static void grimoireScreenSelectsSpells(ClientGameTestContext context, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			ServerPlayer p = player(server);
			MagicData d = MagicAttachments.get(p);
			long now = p.level().getGameTime();
			MagicAttachments.set(p, d.withLevel(5).withXp(37)
					.withHome(Optional.of(GlobalPos.of(Level.OVERWORLD, new BlockPos(120, 64, -340))))
					.withReadyAt(Map.of(SpellIds.FIRE_WALL, now + 900)));
			MagicAttachments.setMana(p, 87);
			p.giveExperienceLevels(12);
		});
		context.waitTicks(5);
		context.getInput().pressMouse(MOUSE_RIGHT);
		context.waitForScreen(GrimoireScreen.class);
		context.waitFor(mc -> mc.gui.screen() instanceof GrimoireScreen s && s.introDone());
		context.waitTicks(2);
		context.takeScreenshot("magic-grimoire-gui4");
		setGuiScale(context, 1);
		context.waitTicks(4);
		context.takeScreenshot("magic-grimoire-gui1");
		setGuiScale(context, 2);
		context.waitTicks(4);
		context.takeScreenshot("magic-grimoire-gui2");

		// Clicking Recall's row selects Recall on the server.
		Rect row = context.computeOnClient(mc -> ((GrimoireScreen) mc.gui.screen()).layout().rows().get(Slots.RECALL));
		moveTo(context, row.cx(), row.cy());
		context.waitTicks(2);
		context.getInput().pressMouse(MOUSE_LEFT);
		context.waitTicks(3);
		sp.getConnection().waitForServerboundPackets();
		context.waitTicks(3);
		Optional<?> sel = sp.getServer().computeOnServer(server -> MagicAttachments.get(player(server)).selected());
		if (!sel.equals(Optional.of(SpellIds.RECALL))) throw new AssertionError("clicking Recall should select it, got " + sel);
		// A locked row (ultimate at level 5) does nothing.
		Rect locked = context.computeOnClient(mc -> ((GrimoireScreen) mc.gui.screen()).layout().rows().get(Slots.ULTIMATE));
		moveTo(context, locked.cx(), locked.cy());
		context.waitTicks(1);
		context.getInput().pressMouse(MOUSE_LEFT);
		context.waitTicks(3);
		sp.getConnection().waitForServerboundPackets();
		sel = sp.getServer().computeOnServer(server -> MagicAttachments.get(player(server)).selected());
		if (!sel.equals(Optional.of(SpellIds.RECALL))) throw new AssertionError("a locked row must not select, got " + sel);
		context.waitTicks(4);
		context.takeScreenshot("magic-grimoire-selected-gui2");

		// Back in the world: the selected-spell label above the hotbar.
		context.runOnClient(mc -> mc.gui.setScreen(null));
		setGuiScale(context, 3);
		context.waitTicks(6);
		if (ManaBarHud.labelTicks() <= 0) throw new AssertionError("selecting a spell should show its name above the hotbar");
		context.takeScreenshot("magic-hud-label-gui3");
	}

	/** The mana bar replaces the XP bar only in casting stance; with a tool in the main hand vanilla's bars come back. */
	private static void manaBarOnlyInCastingStance(ClientGameTestContext context, TestSingleplayerContext sp) {
		setGuiScale(context, 3);
		sp.getServer().runOnServer(server -> {
			ServerPlayer p = player(server);
			p.setItemInHand(InteractionHand.OFF_HAND, p.getMainHandItem().copy());
			p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
		});
		context.waitTicks(5);
		if (context.computeOnClient(ManaBarHud::active)) throw new AssertionError("off-hand grimoire + a stick in the main hand: vanilla bars");
		context.takeScreenshot("magic-hud-not-stance-gui3");
		sp.getServer().runOnServer(server -> player(server).setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY));
		context.waitTicks(5);
		if (!context.computeOnClient(ManaBarHud::active)) throw new AssertionError("off-hand grimoire + empty main hand: the mana bar");
		context.takeScreenshot("magic-hud-offhand-stance-gui3");
	}

	/**
	 * A Rebinding Seal (no grimoire needed): the choice opens with the current element marked; a failed bind (the seal put
	 * away) returns to confirm with an error; with the seal back, Bind rebinds and spends exactly one seal.
	 */
	private static void rebindingSealRebindsOnChoice(ClientGameTestContext context, TestSingleplayerContext sp) {
		setGuiScale(context, 4);
		sp.getServer().runOnServer(server -> {
			ServerPlayer p = player(server);
			p.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
			p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(MagicItems.REBINDING_SEAL, 2));
		});
		context.waitTicks(5);
		context.getInput().pressMouse(MOUSE_RIGHT);
		context.waitForScreen(ElementChoiceScreen.class);
		centreCursor(context);
		context.waitFor(mc -> mc.gui.screen() instanceof ElementChoiceScreen s && s.introDone());
		context.waitTicks(4);
		context.takeScreenshot("magic-rebind-choice-gui4");
		int seals = sp.getServer().computeOnServer(server -> player(server).getMainHandItem().getCount());
		if (seals != 2) throw new AssertionError("opening the choice must not spend a seal, have " + seals);

		// Put the seal away on the server: the bind can't take, so the screen returns to confirm with an error.
		hoverSigil(context, Element.WATER.ordinal());
		context.waitTicks(3);
		hoverSigil(context, Element.WATER.ordinal());
		context.waitTicks(1);
		context.getInput().pressMouse(MOUSE_LEFT);
		context.waitTicks(20);
		ItemStack[] stash = new ItemStack[1];
		sp.getServer().runOnServer(server -> {
			stash[0] = player(server).getMainHandItem().copy();
			player(server).setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		});
		context.waitTicks(2);
		context.clickScreenButton("magic.office.bind");
		context.waitFor(mc -> mc.gui.screen() instanceof ElementChoiceScreen s && s.state() == ElementChoiceScreen.State.CONFIRM, 80);
		context.waitTicks(4);
		context.takeScreenshot("magic-rebind-failed-gui4");
		Optional<Element> still = sp.getServer().computeOnServer(server -> MagicAttachments.get(player(server)).element());
		if (!still.equals(Optional.of(Element.FIRE))) throw new AssertionError("a failed bind keeps the old element, got " + still);

		sp.getServer().runOnServer(server -> player(server).setItemInHand(InteractionHand.MAIN_HAND, stash[0]));
		context.waitTicks(3);
		context.clickScreenButton("magic.office.bind");
		context.waitFor(mc -> mc.gui.screen() instanceof ElementChoiceScreen s && s.state() == ElementChoiceScreen.State.BOUND, 40);
		context.waitFor(mc -> mc.gui.screen() == null, 60);
		Optional<Element> now = sp.getServer().computeOnServer(server -> MagicAttachments.get(player(server)).element());
		if (!now.equals(Optional.of(Element.WATER))) throw new AssertionError("rebound to WATER expected, got " + now);
		seals = sp.getServer().computeOnServer(server -> player(server).getMainHandItem().getCount());
		if (seals != 1) throw new AssertionError("exactly one seal spent, have " + seals);
	}

	/** Ava first (the one asked); "Aaron_Wolfington" is a 16-character name, the longest vanilla allows, sorted onto row 0. */
	private static final String[] FRIENDS = {"Ava", "Ben", "Chloe", "Dev", "Eli", "Fay", "Gus", "Hana", "Ivo", "Juno", "Aaron_Wolfington"};

	/**
	 * The grimoire's Friends tab with ten other players online (real server players with an embedded connection, so the client
	 * gets them in its tab list): page 1 of 2 at three GUI scales, page 2, a click on Ava sends a request (the row waits), and
	 * the cooldown greys everyone. Dev has travel off.
	 */
	private static void friendsPageAsksToTravel(ClientGameTestContext context, TestSingleplayerContext sp) {
		setGuiScale(context, 4);
		java.util.List<ServerPlayer> fakes = new java.util.ArrayList<>();
		sp.getServer().runOnServer(server -> {
			ServerPlayer p = player(server);
			p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(MagicItems.GRIMOIRE));
			p.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
			MagicAttachments.set(p, MagicAttachments.get(p).withLevel(5).withReadyAt(Map.of()).withTravel(true));
			for (String name : FRIENDS) {
				ServerPlayer f = fakePlayer(server, name);
				if (name.equals("Dev")) MagicAttachments.set(f, MagicAttachments.get(f).withTravel(false));
				fakes.add(f);
			}
		});
		try {
			context.waitTicks(10);
			context.getInput().pressMouse(MOUSE_RIGHT);
			context.waitForScreen(GrimoireScreen.class);
			context.waitFor(mc -> mc.gui.screen() instanceof GrimoireScreen s && s.introDone());
			Rect tab = context.computeOnClient(mc -> ((GrimoireScreen) mc.gui.screen()).layout().tabs().get(GrimoireScreen.TAB_FRIENDS));
			moveTo(context, tab.x() + 3, tab.cy());
			context.waitTicks(1);
			context.getInput().pressMouse(MOUSE_LEFT);
			context.waitFor(mc -> mc.gui.screen() instanceof GrimoireScreen s && s.tab() == GrimoireScreen.TAB_FRIENDS, 20);
			context.waitFor(mc -> local.carter.office.client.magic.TravelClient.roster().friends().size() >= FRIENDS.length, 60);
			centreCursor(context);
			context.waitTicks(12);
			context.takeScreenshot("magic-friends-gui4");
			setGuiScale(context, 1);
			context.waitTicks(4);
			context.takeScreenshot("magic-friends-gui1");
			setGuiScale(context, 2);
			context.waitTicks(4);
			context.takeScreenshot("magic-friends-gui2");
			setGuiScale(context, 4);
			context.waitTicks(3);

			// Page 2 and back.
			Rect next = context.computeOnClient(mc -> ((GrimoireScreen) mc.gui.screen()).layout().friends().next());
			moveTo(context, next.cx(), next.cy());
			context.waitTicks(1);
			context.getInput().pressMouse(MOUSE_LEFT);
			context.waitTicks(10);
			int page = context.computeOnClient(mc -> ((GrimoireScreen) mc.gui.screen()).friendsPage().page());
			if (page != 1) throw new AssertionError("the next arrow should turn to page 2, page index " + page);
			context.takeScreenshot("magic-friends-page2-gui4");
			context.runOnClient(mc -> ((GrimoireScreen) mc.gui.screen()).friendsPage().turn(-1));

			// Click Ava (row 1, after Aaron_Wolfington): the request reaches the server and the row waits.
			Rect row = context.computeOnClient(mc -> ((GrimoireScreen) mc.gui.screen()).layout().friends().rows().get(1));
			moveTo(context, row.cx(), row.cy());
			context.waitTicks(10);
			context.takeScreenshot("magic-friends-hover-gui4");
			context.getInput().pressMouse(MOUSE_LEFT);
			sp.getConnection().waitForServerboundPackets();
			context.waitTicks(3);
			java.util.UUID ava = fakes.get(0).getUUID();
			Optional<java.util.UUID> asked = sp.getServer().computeOnServer(server ->
					local.carter.office.magic.cast.Travel.pendingFrom(player(server)).map(local.carter.office.magic.cast.Travel.Request::target));
			if (!asked.equals(Optional.of(ava))) throw new AssertionError("clicking Ava should ask Ava, asked " + asked);
			context.waitFor(mc -> local.carter.office.client.magic.TravelClient.roster().state()
					== local.carter.office.magic.net.MagicPayloads.TravelRoster.State.WAITING, 40);
			centreCursor(context);
			context.waitTicks(8);
			context.takeScreenshot("magic-friends-waiting-gui4");

			// Ava says no; then Recall's cooldown greys everyone.
			sp.getServer().runOnServer(server -> {
				var req = local.carter.office.magic.cast.Travel.pendingFrom(player(server)).orElseThrow();
				local.carter.office.magic.cast.Travel.deny(fakes.get(0), req.id());
				ServerPlayer p = player(server);
				MagicAttachments.set(p, MagicAttachments.get(p).withReady(SpellIds.RECALL, p.level().getGameTime() + 700));
			});
			context.waitFor(mc -> local.carter.office.client.magic.TravelClient.roster().state()
					== local.carter.office.magic.net.MagicPayloads.TravelRoster.State.READY, 60);
			context.waitTicks(4);
			context.takeScreenshot("magic-friends-cooldown-gui4");
			context.runOnClient(mc -> mc.gui.setScreen(null));
		} finally {
			sp.getServer().runOnServer(server -> {
				for (ServerPlayer f : fakes) f.connection.disconnect(new net.minecraft.network.DisconnectionDetails(net.minecraft.network.chat.Component.literal("test over")));
			});
		}
	}

	/** A real server player with an embedded-channel connection (the way {@code GameTestHelper} makes its mock players). */
	private static ServerPlayer fakePlayer(MinecraftServer server, String name) {
		var profile = new com.mojang.authlib.GameProfile(java.util.UUID.nameUUIDFromBytes(("office-test:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8)), name);
		var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false);
		ServerPlayer p = new ServerPlayer(server, server.overworld(), profile, cookie.clientInformation());
		var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
		new io.netty.channel.embedded.EmbeddedChannel(connection);
		server.getPlayerList().placeNewPlayer(connection, p, cookie);
		return p;
	}

	// ---- Task 8: input precedence, R wheel, Shift + scroll ----

	/** Probes under the real FIRE slot 0/1 ids, so the wheel shows real names and icons in the screenshots. */
	private static final Identifier PROBE_0 = SpellIds.at(Element.FIRE, 0);
	private static final Identifier PROBE_1 = SpellIds.at(Element.FIRE, 1);
	private static final int CAST_LEVEL = 2; // only slots 0-1 (probes) + Recall unlocked
	private static final int FULL_MANA = ManaMath.max(CAST_LEVEL);

	/**
	 * Casting stance (grimoire in the off hand, empty main hand, FIRE level 3, FIRE slot 0/1 probes): a chest still opens, a
	 * hologram takes right-clicks and Shift + scroll, Shift + scroll and the R wheel select without casting, and typing
	 * blocks every magic input. A chest stands south of the player, an own hologram north, the obsidian wall east.
	 */
	private static void castingInputPrecedence(ClientGameTestContext context, TestSingleplayerContext sp) {
		setGuiScale(context, 4);
		AutoCloseable[] probes = new AutoCloseable[2];
		BlockPos[] at = new BlockPos[2]; // chest, emitter
		sp.getServer().runOnServer(server -> {
			ServerPlayer p = player(server);
			probes[0] = SpellRegistry.overrideForTesting(probe(PROBE_0, 0), p.getUUID()::equals);
			probes[1] = SpellRegistry.overrideForTesting(probe(PROBE_1, 1), p.getUUID()::equals);
			p.getInventory().clearContent();
			p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(MagicItems.GRIMOIRE));
			long now = p.level().getGameTime();
			MagicAttachments.set(p, MagicData.EMPTY.withElement(Optional.of(Element.FIRE)).withLevel(CAST_LEVEL)
					.withSelected(Optional.of(PROBE_0)).withReadyAt(Map.of(SpellIds.RECALL, now + 600L)));
			MagicAttachments.setMana(p, FULL_MANA); // full: no regen, so any spend shows
			BlockPos stand = p.blockPosition();
			at[0] = stand.offset(0, 0, 2);
			at[1] = stand.offset(0, 0, -2);
			p.level().setBlockAndUpdate(at[0], Blocks.CHEST.defaultBlockState());
			placeHologram(server, at[1], p.getUUID());
		});
		try {
			sp.getConnection().waitForChunksRender();
			context.waitFor(mc -> mc.level.getBlockEntity(at[1]) instanceof HologramBlockEntity be && be.spec() != null, 100);
			context.waitFor(ManaBarHud::active, 40);
			context.waitTicks(5);
			emptyHandChestStillOpens(context, sp, at[0]);
			rightClickHologramDoesNotCast(context, sp, at[1]);
			shiftScrollOnHologramScrollsMonitor(context, sp);
			plainScrollMovesTheHotbar(context, sp);
			hiddenHudClosesTheWheel(context, sp);
			shiftScrollSelectsNextSpell(context, sp);
			wheelScreenshotNotBlank(context, sp);
			wheelSelectsByMouse(context, sp);
			typingBlocksMagic(context, sp);
		} finally {
			sp.getServer().runOnServer(server -> {
				for (AutoCloseable c : probes) {
					try {
						if (c != null) c.close();
					} catch (Exception e) {
						throw new IllegalStateException(e);
					}
				}
			});
		}
	}

	/** Grimoire in the off hand, empty main hand: right-click on a chest opens it; nothing is cast or charged. */
	private static void emptyHandChestStillOpens(ClientGameTestContext context, TestSingleplayerContext sp, BlockPos chest) {
		lookAt(context, chest.getX() + 0.5, chest.getY() + 0.5, chest.getZ() + 0.5);
		context.waitTicks(2);
		context.getInput().pressMouse(MOUSE_RIGHT);
		context.waitForScreen(ContainerScreen.class);
		sp.getConnection().waitForServerboundPackets();
		assertNoCast(context, sp, "a chest right-click");
		context.runOnClient(mc -> mc.player.closeContainer());
		context.waitFor(mc -> mc.gui.screen() == null, 20);
		context.waitTicks(3);
	}

	/**
	 * Looking at an own hologram: the right-click is the monitor's; the grimoire neither casts nor charges.
	 *
	 * <p>Note: in {@code MinecraftUseItemMixin} the hologram code ({@code HologramInteractions.onUse}) takes this click first
	 * (own screen: in-world use or zoom; a friend's: swallowed), so the click itself never reaches the magic gate's hologram
	 * branch, which is a backstop. That branch is checked directly here: the live {@code MagicInputGate.blocksUse} must say
	 * "block" while aiming at the hologram in casting stance, and "don't" once the view is off it.
	 */
	private static void rightClickHologramDoesNotCast(ClientGameTestContext context, TestSingleplayerContext sp, BlockPos emitter) {
		HologramSpec spec = context.computeOnClient(mc -> ((HologramBlockEntity) mc.level.getBlockEntity(emitter)).spec());
		Vec3d c = HologramPlacement.frame(emitter.getX(), emitter.getY(), emitter.getZ(), spec).center();
		lookAt(context, c.x(), c.y() + 0.2, c.z());
		context.waitFor(MagicInputGate::aimingAtHologram, 40);
		if (!context.computeOnClient(mc -> MagicInputGate.blocksUse(mc))) {
			throw new AssertionError("the gate's hologram branch must block a right-click at a hologram in casting stance");
		}
		context.getInput().holdMouseFor(MOUSE_RIGHT, 12); // a long hold would be a charge
		context.waitTicks(3);
		sp.getConnection().waitForServerboundPackets();
		assertNoCast(context, sp, "right-click on a hologram");
		if (context.computeOnClient(mc -> ZoomClient.controller().state() != ZoomController.State.WORLD)) {
			context.runOnClient(mc -> ZoomClient.controller().abort()); // Windows/Linux zoom: back to the world
			context.waitTicks(10);
		}
		context.runOnClient(mc -> mc.gui.setScreen(null));
		context.waitTicks(2);
	}

	/**
	 * Aiming at a hologram, Shift + scroll belongs to the monitor: the selected spell stays. The test hologram isn't live (no
	 * helper or screen source here), so the monitor doesn't take the scroll either: it is swallowed and the hotbar stays too.
	 * Then, with the R wheel open on that hologram, the mouse wheel steps the wheel (magic's scroll phase runs first).
	 */
	private static void shiftScrollOnHologramScrollsMonitor(ClientGameTestContext context, TestSingleplayerContext sp) {
		if (!context.computeOnClient(MagicInputGate::aimingAtHologram)) throw new AssertionError("should still aim at the hologram");
		int slot = context.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
		context.getInput().holdShift();
		context.getInput().scroll(-1);
		context.getInput().scroll(1, 0); // a Mac's Shift + wheel (horizontal)
		context.waitTicks(2);
		context.getInput().releaseShift();
		sp.getConnection().waitForServerboundPackets();
		context.waitTicks(2);
		Optional<Identifier> sel = sp.getServer().computeOnServer(server -> MagicAttachments.get(player(server)).selected());
		if (!sel.equals(Optional.of(PROBE_0))) throw new AssertionError("Shift + scroll on a hologram must not select, got " + sel);
		int after = context.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
		if (after != slot) throw new AssertionError("Shift + scroll at a hologram in stance must keep the hotbar: " + slot + " -> " + after);

		context.getInput().holdKey(MagicKeys.WHEEL);
		context.waitFor(mc -> SpellWheel.isOpen(), 20);
		context.getInput().scroll(-1);
		context.waitTicks(1);
		int hovered = context.computeOnClient(mc -> SpellWheel.hovered());
		if (hovered != 1) throw new AssertionError("the open wheel takes the scroll at a hologram: from Fire Bolt to slice 1, hovered " + hovered);
		context.getInput().scroll(1);
		context.waitTicks(1);
		hovered = context.computeOnClient(mc -> SpellWheel.hovered());
		if (hovered != 0) throw new AssertionError("scrolling back up hovers Fire Bolt (0), hovered " + hovered);
		// A trackpad: half notches; one does nothing, two make one step (vanilla adds them up), and back.
		context.getInput().scroll(-0.5);
		context.waitTicks(1);
		hovered = context.computeOnClient(mc -> SpellWheel.hovered());
		if (hovered != 0) throw new AssertionError("half a notch must not step the wheel, hovered " + hovered);
		context.getInput().scroll(-0.5);
		context.waitTicks(1);
		hovered = context.computeOnClient(mc -> SpellWheel.hovered());
		if (hovered != 1) throw new AssertionError("two half notches step the wheel once, hovered " + hovered);
		context.getInput().scroll(0.5);
		context.getInput().scroll(0.5);
		context.waitTicks(1);
		hovered = context.computeOnClient(mc -> SpellWheel.hovered());
		if (hovered != 0) throw new AssertionError("two half notches up step back once, hovered " + hovered);
		context.getInput().releaseKey(MagicKeys.WHEEL); // Fire Bolt is already selected: nothing changes
		context.waitFor(mc -> !SpellWheel.visible(), 20);
		after = context.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
		if (after != slot) throw new AssertionError("scrolling the open wheel must keep the hotbar: " + slot + " -> " + after);
		sp.getConnection().waitForServerboundPackets();
		sel = sp.getServer().computeOnServer(server -> MagicAttachments.get(player(server)).selected());
		if (!sel.equals(Optional.of(PROBE_0))) throw new AssertionError("re-picking the selected spell changes nothing, got " + sel);
		assertNoCast(context, sp, "the wheel at a hologram");
	}

	/**
	 * Vanilla scrolling stays vanilla: in casting stance a plain scroll moves the hotbar (and selects nothing); with a stick in
	 * the main hand (out of casting stance) Shift + scroll moves the hotbar too.
	 */
	private static void plainScrollMovesTheHotbar(ClientGameTestContext context, TestSingleplayerContext sp) {
		lookEast(context);
		context.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
		context.waitTicks(2);
		context.getInput().scroll(-1);
		context.waitTicks(2);
		int slot = context.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
		if (slot != 1) throw new AssertionError("a plain scroll in casting stance moves the hotbar to slot 1, at " + slot);
		context.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
		sp.getServer().runOnServer(server -> player(server).getInventory().setItem(0, new ItemStack(Items.STICK)));
		context.waitFor(mc -> mc.player.getMainHandItem().is(Items.STICK), 20);
		context.getInput().holdShift();
		context.getInput().scroll(-1);
		context.waitTicks(2);
		context.getInput().releaseShift();
		slot = context.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
		if (slot != 1) throw new AssertionError("Shift + scroll out of casting stance moves the hotbar to slot 1, at " + slot);
		sp.getConnection().waitForServerboundPackets();
		Optional<Identifier> sel = sp.getServer().computeOnServer(server -> MagicAttachments.get(player(server)).selected());
		if (!sel.equals(Optional.of(PROBE_0))) throw new AssertionError("vanilla scrolling must not select a spell, got " + sel);
		sp.getServer().runOnServer(server -> player(server).getInventory().setItem(0, ItemStack.EMPTY));
		context.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
		context.waitFor(mc -> mc.player.getMainHandItem().isEmpty() && mc.player.getInventory().getSelectedSlot() == 0, 20);
		context.waitTicks(2);
	}

	/** Hiding the HUD (F1) closes the open wheel without choosing; the wheel doesn't open while it is hidden. */
	private static void hiddenHudClosesTheWheel(ClientGameTestContext context, TestSingleplayerContext sp) {
		lookEast(context);
		context.getInput().holdKey(MagicKeys.WHEEL);
		context.waitFor(mc -> SpellWheel.isOpen(), 20);
		context.getInput().moveCursor(1, 0);
		context.waitTicks(1);
		context.getInput().moveCursor(0, 40); // hover the bottom slice (locked) while open
		context.waitTicks(1);
		context.getInput().pressKey(InputConstants.KEY_F1);
		context.waitTicks(2);
		try {
			if (!context.computeOnClient(mc -> mc.gui.hud.isHidden())) throw new AssertionError("F1 should hide the HUD");
			if (context.computeOnClient(mc -> SpellWheel.isOpen())) throw new AssertionError("hiding the HUD must close the wheel");
			context.getInput().releaseKey(MagicKeys.WHEEL);
			context.getInput().holdKey(MagicKeys.WHEEL);
			context.waitTicks(3);
			if (context.computeOnClient(mc -> SpellWheel.isOpen())) throw new AssertionError("no wheel while the HUD is hidden");
			context.getInput().releaseKey(MagicKeys.WHEEL);
		} finally {
			context.getInput().pressKey(InputConstants.KEY_F1);
			context.waitFor(mc -> !mc.gui.hud.isHidden(), 20);
		}
		sp.getConnection().waitForServerboundPackets();
		Optional<Identifier> sel = sp.getServer().computeOnServer(server -> MagicAttachments.get(player(server)).selected());
		if (!sel.equals(Optional.of(PROBE_0))) throw new AssertionError("a wheel closed by F1 chooses nothing, got " + sel);
	}

	/** Not aiming at a hologram: Shift + scroll down selects the next unlocked spell; the hotbar slot stays. */
	private static void shiftScrollSelectsNextSpell(ClientGameTestContext context, TestSingleplayerContext sp) {
		lookEast(context);
		context.waitTicks(2);
		if (context.computeOnClient(MagicInputGate::aimingAtHologram)) throw new AssertionError("looking east should miss the hologram");
		int slot = context.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
		context.getInput().holdShift();
		context.getInput().scroll(-1);
		context.waitTicks(2);
		context.getInput().releaseShift();
		sp.getConnection().waitForServerboundPackets();
		sp.getServer().waitFor(server -> MagicAttachments.get(player(server)).selected().equals(Optional.of(PROBE_1)), 40);
		int after = context.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
		if (after != slot) throw new AssertionError("Shift + scroll must keep the hotbar slot: " + slot + " -> " + after);
		// A Mac sends Shift + wheel as a horizontal scroll: +x is down (next: Recall), -x is up (back to Fire Wall).
		macShiftScroll(context, sp, 1, SpellIds.RECALL);
		macShiftScroll(context, sp, -1, PROBE_1);
		// A trackpad: quarter notches add up; three select nothing, the fourth steps once.
		context.getInput().holdShift();
		for (int i = 0; i < 3; i++) context.getInput().scroll(-0.25);
		context.waitTicks(3);
		sp.getConnection().waitForServerboundPackets();
		Optional<Identifier> partial = sp.getServer().computeOnServer(server -> MagicAttachments.get(player(server)).selected());
		if (!partial.equals(Optional.of(PROBE_1))) {
			context.getInput().releaseShift();
			throw new AssertionError("three quarter-notches must not step yet, got " + partial);
		}
		context.getInput().scroll(-0.25);
		context.waitTicks(2);
		context.getInput().releaseShift();
		sp.getConnection().waitForServerboundPackets();
		sp.getServer().waitFor(server -> MagicAttachments.get(player(server)).selected().equals(Optional.of(SpellIds.RECALL)), 40);
		context.waitFor(mc -> MagicAttachments.get(mc.player).selected().equals(Optional.of(SpellIds.RECALL)), 40);
		macShiftScroll(context, sp, -1, PROBE_1);
		after = context.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
		if (after != slot) throw new AssertionError("horizontal Shift + scroll must keep the hotbar slot: " + slot + " -> " + after);
		assertNoCast(context, sp, "Shift + scroll");
		context.waitTicks(10);
		context.takeScreenshot("magic-wheel-scroll-label-gui4");
	}

	private static void macShiftScroll(ClientGameTestContext context, TestSingleplayerContext sp, double dx, Identifier expect) {
		context.waitTicks(2);
		context.getInput().holdShift();
		context.getInput().scroll(dx, 0);
		context.waitTicks(2);
		context.getInput().releaseShift();
		sp.getConnection().waitForServerboundPackets();
		sp.getServer().waitFor(server -> MagicAttachments.get(player(server)).selected().equals(Optional.of(expect)), 40);
		context.waitFor(mc -> MagicAttachments.get(mc.player).selected().equals(Optional.of(expect)), 40);
	}

	/** With the wheel open, at least 20 % of the pixels inside its outer circle differ from the same view without it. */
	private static void wheelScreenshotNotBlank(ClientGameTestContext context, TestSingleplayerContext sp) {
		for (int scale : new int[] {4, 1}) {
			setGuiScale(context, scale);
			lookEast(context);
			quietHud(context);
			context.waitTicks(4);
			BufferedImage closed = read(context.takeScreenshot("magic-wheel-closed-gui" + scale));
			context.getInput().holdKey(MagicKeys.WHEEL);
			context.waitFor(mc -> SpellWheel.isOpen(), 20);
			context.waitTicks(SpellWheel.OPEN_TICKS + 3);
			BufferedImage open = read(context.takeScreenshot("magic-wheel-open-gui" + scale));
			WheelLayout.Layout l = context.computeOnClient(mc -> WheelLayout.layout(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()));
			int n = 0, diff = 0;
			int r = Math.round(l.outer() * scale);
			int cx = l.cx() * scale, cy = l.cy() * scale;
			for (int y = cy - r; y <= cy + r; y++) {
				for (int x = cx - r; x <= cx + r; x++) {
					if ((x - cx) * (x - cx) + (y - cy) * (y - cy) > r * r || x < 0 || y < 0 || x >= open.getWidth() || y >= open.getHeight()) continue;
					n++;
					if (dist(open.getRGB(x, y), closed.getRGB(x, y)) > 36) diff++;
				}
			}
			if (n == 0 || diff < 0.20 * n) {
				throw new AssertionError("gui " + scale + ": the open wheel changed only " + diff + "/" + n + " pixels inside its ring");
			}
			// Not moved: releasing keeps the current spell.
			context.getInput().releaseKey(MagicKeys.WHEEL);
			context.waitFor(mc -> !SpellWheel.visible(), 20);
			sp.getConnection().waitForServerboundPackets();
			Optional<Identifier> sel = sp.getServer().computeOnServer(server -> MagicAttachments.get(player(server)).selected());
			if (!sel.equals(Optional.of(PROBE_1))) throw new AssertionError("an untouched wheel must keep the spell, got " + sel);
		}
		setGuiScale(context, 4);
	}

	/** Hold R, move the mouse up and to the right, release: slot 1 is selected; the camera did not turn; nothing is cast. */
	private static void wheelSelectsByMouse(ClientGameTestContext context, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			ServerPlayer p = player(server);
			MagicAttachments.set(p, MagicAttachments.get(p).withSelected(Optional.of(PROBE_0)));
		});
		context.waitFor(mc -> MagicAttachments.get(mc.player).selected().equals(Optional.of(PROBE_0)), 40);
		lookEast(context);
		context.waitTicks(2);
		quietHud(context);
		float yaw = context.computeOnClient(mc -> mc.player.getYRot());
		float pitch = context.computeOnClient(mc -> mc.player.getXRot());
		context.getInput().holdKey(MagicKeys.WHEEL);
		context.waitFor(mc -> SpellWheel.isOpen(), 20);
		context.waitTicks(SpellWheel.OPEN_TICKS + 1);
		context.getInput().moveCursor(1, 0); // the first move after the mouse is grabbed is only a position (vanilla)
		context.waitTicks(1);
		context.getInput().moveCursor(36, -30);
		context.waitTicks(3);
		int hovered = context.computeOnClient(mc -> SpellWheel.hovered());
		if (hovered != 1) {
			String why = context.computeOnClient(mc -> "grabbed " + mc.mouseHandler.isMouseGrabbed() + " active " + mc.isWindowActive()
					+ " pointer " + SpellWheel.pointerX() + "," + SpellWheel.pointerY());
			throw new AssertionError("up and to the right should hover slice 1, hovered " + hovered + " (" + why + ")");
		}
		float yaw2 = context.computeOnClient(mc -> mc.player.getYRot());
		float pitch2 = context.computeOnClient(mc -> mc.player.getXRot());
		if (yaw2 != yaw || pitch2 != pitch) throw new AssertionError("the camera must not turn while the wheel is open: " + yaw + "," + pitch + " -> " + yaw2 + "," + pitch2);
		context.waitTicks(4);
		context.takeScreenshot("magic-wheel-hover-gui4");
		setGuiScale(context, 1);
		context.waitTicks(2);
		context.takeScreenshot("magic-wheel-hover-gui1");
		setGuiScale(context, 2);
		context.waitTicks(2);
		context.takeScreenshot("magic-wheel-hover-gui2");
		// The locked ultimate (slice 4, lower left): hovering shows its level.
		context.getInput().moveCursor(1, 0); // a GUI-scale change makes the next move only a position again
		context.waitTicks(1);
		context.getInput().moveCursor(-36 - 60, 30 + 30);
		context.waitTicks(3);
		hovered = context.computeOnClient(mc -> SpellWheel.hovered());
		if (hovered != 4) throw new AssertionError("down and to the left should hover the ultimate, hovered " + hovered);
		context.takeScreenshot("magic-wheel-locked-gui2");
		// The mouse wheel while open steps through unlocked spells: from the ultimate (locked) on to Recall, then Fire Bolt.
		context.getInput().scroll(-1);
		context.waitTicks(1);
		hovered = context.computeOnClient(mc -> SpellWheel.hovered());
		if (hovered != 5) throw new AssertionError("scroll down from slice 4 should hover Recall (5), hovered " + hovered);
		context.getInput().scroll(-1);
		context.getInput().scroll(-1);
		context.waitTicks(1);
		hovered = context.computeOnClient(mc -> SpellWheel.hovered());
		if (hovered != 1) throw new AssertionError("two more notches: Fire Bolt then Fire Wall (1), hovered " + hovered);
		setGuiScale(context, 4);
		context.waitTicks(2);
		hovered = context.computeOnClient(mc -> SpellWheel.hovered());
		if (hovered != 1) throw new AssertionError("back on slice 1, hovered " + hovered);
		context.getInput().releaseKey(MagicKeys.WHEEL);
		context.waitTicks(1);
		context.takeScreenshot("magic-wheel-chosen-gui4");
		sp.getConnection().waitForServerboundPackets();
		sp.getServer().waitFor(server -> MagicAttachments.get(player(server)).selected().equals(Optional.of(PROBE_1)), 40);
		assertNoCast(context, sp, "the wheel");
		context.waitTicks(8);
		context.takeScreenshot("magic-wheel-after-gui4");
	}

	/**
	 * Typing or work mode on a hologram (from PointerClient's public typing state; its hint screen closed so the gate itself
	 * is tested): a right-click neither casts nor charges, R opens no wheel, Shift + scroll selects nothing, and a charge
	 * started before is dropped without a release (no cast).
	 */
	private static void typingBlocksMagic(ClientGameTestContext context, TestSingleplayerContext sp) {
		lookEast(context);
		context.waitTicks(2);
		Optional<Identifier> before = sp.getServer().computeOnServer(server -> MagicAttachments.get(player(server)).selected());
		// A charge in progress (normally work mode can't begin mid-charge; vanilla ignores clicks while an item is in use).
		context.getInput().holdMouse(MOUSE_RIGHT);
		context.waitFor(mc -> mc.player.isUsingItem(), 20);
		enterTyping(context);
		try {
			context.getInput().releaseMouse(MOUSE_RIGHT);
			context.waitTicks(3);
			if (context.computeOnClient(mc -> mc.player.isUsingItem())) throw new AssertionError("typing must drop the charge");
			sp.getConnection().waitForServerboundPackets();
			assertNoCast(context, sp, "a release while typing");

			assertTyping(context, "release");

			enterTyping(context); // fresh: on a Mac, typing that never left Minecraft ends after 1.5 s
			context.getInput().holdMouseFor(MOUSE_RIGHT, 6);
			if (context.computeOnClient(mc -> mc.player.isUsingItem())) throw new AssertionError("typing: right-click must not charge");
			assertTyping(context, "right-click");

			enterTyping(context);
			context.getInput().holdKey(MagicKeys.WHEEL);
			context.waitTicks(3);
			if (context.computeOnClient(mc -> SpellWheel.isOpen())) throw new AssertionError("typing: R must not open the wheel");
			context.getInput().releaseKey(MagicKeys.WHEEL);
			assertTyping(context, "R");

			enterTyping(context);
			int hotbar = context.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
			context.getInput().holdShift();
			context.getInput().scroll(-1);
			context.getInput().scroll(1, 0); // a Mac's Shift + wheel
			context.getInput().releaseShift();
			context.waitTicks(2);
			assertTyping(context, "Shift + scroll");
			int hotbarAfter = context.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
			if (hotbarAfter != hotbar) throw new AssertionError("typing: Shift + scroll in stance must not move the hotbar: " + hotbar + " -> " + hotbarAfter);
			sp.getConnection().waitForServerboundPackets();
			context.waitTicks(2);
			assertNoCast(context, sp, "inputs while typing");
			Optional<Identifier> after = sp.getServer().computeOnServer(server -> MagicAttachments.get(player(server)).selected());
			if (!after.equals(before)) throw new AssertionError("typing: Shift + scroll must not select, " + before + " -> " + after);
		} finally {
			context.runOnClient(mc -> {
				if (PointerClient.typing().active()) PointerClient.typing().leave();
			});
			sp.getServer().runOnServer(server -> {
				ServerPlayer p = player(server);
				p.stopUsingItem();
				CastService.endCharge(p);
			});
			context.waitTicks(3);
		}
	}

	/** Typing mode as a hologram click starts it, with its hint screen closed again (typing stays on until left). */
	private static void enterTyping(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			if (PointerClient.typing().active()) PointerClient.typing().leave();
			PointerClient.typing().enter(Util.getMillis());
			mc.gui.setScreen(null);
		});
		assertTyping(context, "entering");
	}

	private static void assertTyping(ClientGameTestContext context, String step) {
		if (!context.computeOnClient(mc -> PointerClient.typing().active() && mc.gui.screen() == null)) {
			throw new AssertionError("typing should still be on, with no screen open (" + step + ")");
		}
	}

	/** Mana is full, the grimoire is not charging (client and server), and no probe went on cooldown. */
	private static void assertNoCast(ClientGameTestContext context, TestSingleplayerContext sp, String what) {
		if (context.computeOnClient(mc -> mc.player.isUsingItem())) throw new AssertionError(what + ": the client is charging");
		String problem = sp.getServer().computeOnServer(server -> {
			ServerPlayer p = player(server);
			MagicData d = MagicAttachments.get(p);
			if (MagicAttachments.mana(p) != FULL_MANA) return "mana " + MagicAttachments.mana(p) + " of " + FULL_MANA;
			if (d.readyAt().containsKey(PROBE_0) || d.readyAt().containsKey(PROBE_1)) return "a probe was cast: " + d.readyAt();
			return null;
		});
		if (problem != null) throw new AssertionError(what + " must not cast: " + problem);
	}

	private static MagicTestKit.Probe probe(Identifier id, int slot) {
		return new MagicTestKit.Probe(id, Element.FIRE, slot, SpellCost.forSlot(slot), false, ctx -> CastOutcome.cast(ctx.fx()));
	}

	/** No chat lines or action-bar text over the screenshots. */
	private static void quietHud(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			mc.gui.hud.getChat().clearMessages(false);
			mc.gui.hud.setOverlayMessage(net.minecraft.network.chat.Component.empty(), false);
		});
	}

	/** Places an emitter at {@code pos} owned by {@code owner} with the default spec facing south (towards the player). */
	private static void placeHologram(MinecraftServer server, BlockPos pos, UUID owner) {
		ServerLevel level = player(server).level();
		level.setBlockAndUpdate(pos, OfficeBlocks.HOLOGRAM_EMITTER.defaultBlockState());
		HologramBlockEntity be = (HologramBlockEntity) level.getBlockEntity(pos);
		HologramSpec spec = HologramEmitterBlock.defaultSpec(owner, 1, 0f);
		be.setSpec(spec);
		be.onServerLoad(level);
		be.setSpec(spec);
	}

	private static void lookEast(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			mc.player.setYRot(-90f);
			mc.player.setXRot(0f);
			mc.player.yRotO = -90f;
			mc.player.xRotO = 0f;
		});
	}

	/** Points the client player's view at a world point (no interpolation). */
	private static void lookAt(ClientGameTestContext context, double x, double y, double z) {
		context.runOnClient(mc -> {
			var eye = mc.player.getEyePosition();
			double dx = x - eye.x, dy = y - eye.y, dz = z - eye.z;
			float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
			float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
			mc.player.setYRot(yaw);
			mc.player.setXRot(pitch);
			mc.player.yRotO = yaw;
			mc.player.xRotO = pitch;
		});
	}

	// ---- helpers ----

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().get(0);
	}

	private static void setGuiScale(ClientGameTestContext context, int scale) {
		context.runOnClient(mc -> {
			mc.options.guiScale().set(scale);
			mc.resizeGui();
		});
		context.waitTick();
	}

	/** Puts the cursor on sigil {@code i} as the screen draws it now. */
	private static void hoverSigil(ClientGameTestContext context, int i) {
		float[] c = context.computeOnClient(mc -> {
			ChoiceLayout.Layout l = ((ElementChoiceScreen) mc.gui.screen()).layout();
			return new float[] {l.cx(i), l.cy(i)};
		});
		moveTo(context, c[0], c[1]);
	}

	private static void centreCursor(ClientGameTestContext context) {
		double[] c = context.computeOnClient(mc -> new double[] {mc.getWindow().getGuiScaledWidth() / 2.0, mc.getWindow().getGuiScaledHeight() / 2.0});
		moveTo(context, c[0], c[1]);
	}

	/** Moves the cursor to a GUI-space point. */
	private static void moveTo(ClientGameTestContext context, double guiX, double guiY) {
		double[] w = context.computeOnClient(mc -> new double[] {
				guiX * mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth(),
				guiY * mc.getWindow().getScreenHeight() / mc.getWindow().getGuiScaledHeight()});
		context.getInput().setCursorPos(w[0], w[1]);
	}

	private static int dist(int p, int q) {
		return Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)) + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF)) + Math.abs((p & 0xFF) - (q & 0xFF));
	}

	private static BufferedImage read(Path screenshot) {
		try {
			return ImageIO.read(screenshot.toFile());
		} catch (IOException e) {
			throw new AssertionError("cannot read screenshot " + screenshot, e);
		}
	}
}
