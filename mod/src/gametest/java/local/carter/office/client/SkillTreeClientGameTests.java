package local.carter.office.client;

import java.util.Optional;

import com.mojang.blaze3d.platform.InputConstants;

import local.carter.office.client.magic.screen.GrimoireScreen;
import local.carter.office.client.magic.screen.SignLayout.Rect;
import local.carter.office.client.magic.screen.TreeLayout;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.item.MagicItems;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.tree.SpellRank;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * The Skill tree tab (CI only; screenshots land in build/run/clientGameTest/screenshots): a Fire caster at grimoire level 4 (so
 * two spells are still locked) with a few ranks bought and materials for more, the tab at GUI scales 1 to 4 on a 1080p window and
 * at 854 × 480 on its largest GUI scale, a fork's two cards, the Re-pick mode, then a real rank-up through the button (the burst
 * and the rank stored on the server).
 */
@SuppressWarnings("UnstableApiUsage")
public class SkillTreeClientGameTests implements FabricClientGameTest {
	private static final int MOUSE_RIGHT = InputConstants.MOUSE_BUTTON_RIGHT;
	private static final int MOUSE_LEFT = InputConstants.MOUSE_BUTTON_LEFT;

	@Override
	public void runTest(ClientGameTestContext context) {
		int savedScale = context.computeOnClient(mc -> mc.options.guiScale().get());
		context.getInput().resizeWindow(1920, 1080);
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			stage(context, sp);
			openTree(context);
			for (int scale = 1; scale <= 4; scale++) {
				setGuiScale(context, scale);
				context.waitTicks(8);
				context.takeScreenshot("magic-tree-gui" + scale);
			}
			// The smallest screen the spec names: 854 × 480 at its largest GUI scale (2).
			context.getInput().resizeWindow(854, 480);
			setGuiScale(context, 2);
			context.waitTicks(8);
			context.takeScreenshot("magic-tree-854x480-gui2");
			// The narrowest GUIs with a fork's cards open (the review's sizes): 320 × 240, 427 × 240 and 480 × 270 (1080p on auto
			// GUI scale), each with Fire Bolt III (cards to choose) and Fire Wall V (long names: Marching Wall, Backdraft).
			int[][] narrow = {{640, 480, 2}, {854, 480, 2}, {1920, 1080, 4}};
			for (int[] n : narrow) {
				context.getInput().resizeWindow(n[0], n[1]);
				setGuiScale(context, n[2]);
				String size = (n[0] / n[2]) + "x" + (n[1] / n[2]);
				context.runOnClient(mc -> ((GrimoireScreen) mc.gui.screen()).treePage().select(0, 3, 1));
				context.waitTicks(6);
				context.takeScreenshot("magic-tree-fork-" + size);
				context.runOnClient(mc -> ((GrimoireScreen) mc.gui.screen()).treePage().select(1, 5, 2));
				context.waitTicks(4);
				context.takeScreenshot("magic-tree-fork5-" + size);
				context.runOnClient(mc -> ((GrimoireScreen) mc.gui.screen()).treePage().select(2, 1, 0));
				context.waitTicks(4);
				context.takeScreenshot("magic-tree-bump-" + size);
			}
			context.getInput().resizeWindow(1920, 1080);
			setGuiScale(context, 3);
			// A fork rank: Fire Bolt III's two cards.
			context.runOnClient(mc -> ((GrimoireScreen) mc.gui.screen()).treePage().select(0, 3, 1));
			context.waitTicks(4);
			context.takeScreenshot("magic-tree-fork-gui3");
			// Re-pick mode with a Seal in the inventory: Fire Wall's fork B is owned, A can be re-picked.
			Rect repick = context.computeOnClient(mc -> TreeLayout.of(((GrimoireScreen) mc.gui.screen()).layout()).repick());
			moveTo(context, repick.cx(), repick.cy());
			context.getInput().pressMouse(MOUSE_LEFT);
			context.runOnClient(mc -> ((GrimoireScreen) mc.gui.screen()).treePage().select(1, 3, 1));
			context.waitTicks(4);
			context.takeScreenshot("magic-tree-repick-gui3");
			moveTo(context, repick.cx(), repick.cy());
			context.getInput().pressMouse(MOUSE_LEFT);
			// A real rank-up: Flame Dash rank I through the button.
			context.runOnClient(mc -> ((GrimoireScreen) mc.gui.screen()).treePage().select(2, 1, 0));
			Rect button = context.computeOnClient(mc -> TreeLayout.of(((GrimoireScreen) mc.gui.screen()).layout()).button());
			moveTo(context, button.cx(), button.cy());
			context.waitTicks(2);
			context.takeScreenshot("magic-tree-ready-gui3");
			context.getInput().pressMouse(MOUSE_LEFT);
			sp.getConnection().waitForServerboundPackets();
			context.waitTicks(3);
			context.takeScreenshot("magic-tree-rankup-gui3");
			int rank = sp.getServer().computeOnServer(server -> MagicAttachments.get(player(server)).rank(SpellIds.FLAME_DASH).rank());
			if (rank != 1) throw new AssertionError("the button should buy Flame Dash rank I, rank now " + rank);
			context.waitTicks(20);
			context.takeScreenshot("magic-tree-after-gui3");
			context.runOnClient(mc -> mc.gui.setScreen(null));
		} finally {
			setGuiScale(context, savedScale);
		}
	}

	/** A closed obsidian room; Fire, level 4, some ranks, XP and materials for more, a Rebinding Seal, the grimoire in hand. */
	private static void stage(ClientGameTestContext context, TestSingleplayerContext sp) {
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
			p.getInventory().clearContent();
			p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(MagicItems.GRIMOIRE));
			MagicData d = MagicData.EMPTY.withElement(Optional.of(Element.FIRE)).withLevel(4)
					.withSelected(Optional.of(SpellIds.FIRE_BOLT))
					.withRank(new SpellRank(SpellIds.FIRE_BOLT, 2, 0, 0))
					.withRank(new SpellRank(SpellIds.FIRE_WALL, 3, SpellRank.B, 0));
			MagicAttachments.set(p, d);
			p.setExperienceLevels(12);
			p.getInventory().add(new ItemStack(Items.BLAZE_POWDER, 10));
			p.getInventory().add(new ItemStack(Items.BLAZE_ROD, 3));
			p.getInventory().add(new ItemStack(Items.MAGMA_CREAM, 2));
			p.getInventory().add(new ItemStack(MagicItems.REBINDING_SEAL, 1));
		});
		sp.getConnection().waitForChunksRender();
		context.waitTicks(10);
	}

	private static void openTree(ClientGameTestContext context) {
		setGuiScale(context, 3);
		context.getInput().pressMouse(MOUSE_RIGHT);
		context.waitForScreen(GrimoireScreen.class);
		context.waitFor(mc -> mc.gui.screen() instanceof GrimoireScreen s && s.introDone());
		Rect tab = context.computeOnClient(mc -> ((GrimoireScreen) mc.gui.screen()).layout().tabs().get(GrimoireScreen.TAB_TREE));
		moveTo(context, tab.x() + 2, tab.cy());
		context.getInput().pressMouse(MOUSE_LEFT);
		context.waitFor(mc -> mc.gui.screen() instanceof GrimoireScreen s && s.tab() == GrimoireScreen.TAB_TREE, 20);
		context.waitTicks(10);
		context.takeScreenshot("magic-tree-coach-gui3");
	}

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

	/** Moves the cursor to a GUI-space point. */
	private static void moveTo(ClientGameTestContext context, double guiX, double guiY) {
		double[] w = context.computeOnClient(mc -> new double[] {
				guiX * mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth(),
				guiY * mc.getWindow().getScreenHeight() / mc.getWindow().getGuiScaledHeight()});
		context.getInput().setCursorPos(w[0], w[1]);
	}
}
