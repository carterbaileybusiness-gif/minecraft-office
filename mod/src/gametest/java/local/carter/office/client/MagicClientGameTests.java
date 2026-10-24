package local.carter.office.client;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import javax.imageio.ImageIO;

import com.mojang.blaze3d.platform.InputConstants;

import local.carter.office.client.magic.hud.ManaBarHud;
import local.carter.office.client.magic.hud.ManaBarLayout;
import local.carter.office.client.magic.screen.ChoiceLayout;
import local.carter.office.client.magic.screen.ElementChoiceScreen;
import local.carter.office.client.magic.screen.GrimoireScreen;
import local.carter.office.client.magic.screen.SignLayout.Rect;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.item.MagicItems;
import local.carter.office.magic.spell.SpellIds;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
