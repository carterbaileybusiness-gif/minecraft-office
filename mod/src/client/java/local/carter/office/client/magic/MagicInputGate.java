package local.carter.office.client.magic;

import local.carter.office.client.pointer.PointerClient;
import local.carter.office.client.zoom.ZoomClient;
import local.carter.office.client.zoom.ZoomController;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.item.GrimoireItem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Who gets an input when magic and the monitors both want it (spec §6). The pure rules take plain booleans so they can be
 * unit-tested; the live wrappers read the client.
 * <ul>
 *   <li>Typing or work mode on a hologram: nothing reaches the world, and no magic key works (no cast, charge, release,
 *       wheel or selection).</li>
 *   <li>Aiming at any hologram: right-clicks and Shift + scroll belong to the monitor (the grimoire never casts there);
 *       R still opens the wheel.</li>
 *   <li>The wheel opens in casting stance only (the same rule as the mana bar: bound, and a grimoire in the main hand or in
 *       the off hand with the main hand empty). Shift + scroll selects only in the off-hand casting stance; anything in the
 *       main hand keeps the hotbar scroll and its own right-click.</li>
 * </ul>
 */
public final class MagicInputGate {
	private MagicInputGate() {}

	// ---------------------------------------------------------------- pure rules

	/**
	 * Right-click must not reach vanilla: always while typing (deliberate: nothing reaches the world then), or with the
	 * grimoire in the off hand, an empty main hand and any hologram in front (a cast there would land behind the monitor).
	 */
	public static boolean blocksUse(boolean typing, boolean aimingAtHologram, boolean grimoireOffhand, boolean mainHandEmpty) {
		return typing || (grimoireOffhand && mainHandEmpty && aimingAtHologram);
	}

	/** The R wheel may open (and stay open): no screen, not typing, a grimoire held in casting stance, bound. */
	public static boolean wheelAllowed(boolean screenOpen, boolean typing, boolean grimoireHeld, boolean bound) {
		return !screenOpen && !typing && grimoireHeld && bound;
	}

	/** Shift + scroll selects a spell (instead of the hotbar): off-hand casting stance, bound, not typing, no hologram in front. */
	public static boolean scrollSelects(boolean shift, boolean typing, boolean aimingAtHologram, boolean grimoireOffhand,
			boolean mainHandEmpty, boolean bound) {
		return shift && !typing && !aimingAtHologram && grimoireOffhand && mainHandEmpty && bound;
	}

	/** Casting stance (the mana bar's hand rule): a grimoire in the main hand, or in the off hand with the main hand empty. */
	public static boolean stance(boolean mainGrimoire, boolean mainHandEmpty, boolean offGrimoire) {
		return mainGrimoire || (offGrimoire && mainHandEmpty);
	}

	/**
	 * A grimoire charge still running while typing is dropped on the client without a release, so no cast is ever sent from
	 * typing or work mode. (Normally unreachable: vanilla ignores clicks while an item is in use, so work mode can't begin
	 * mid-charge.)
	 */
	public static boolean dropsCharge(boolean typing, boolean usingGrimoire) {
		return typing && usingGrimoire;
	}

	// ---------------------------------------------------------------- live

	/** Typing into a hologram's app, or working zoomed into one (both block every magic key). */
	public static boolean typing() {
		return PointerClient.typing().active() || ZoomClient.controller().state() != ZoomController.State.WORLD;
	}

	/** Any hologram (any owner) under the crosshair, in front of whatever vanilla aims at. */
	public static boolean aimingAtHologram(Minecraft mc) {
		return mc.player != null && PointerClient.inFront(mc, mc.getDeltaTracker().getGameTimeDeltaPartialTick(true)).isPresent();
	}

	/** {@code MinecraftUseItemMixin}, after the hologram code declined the click. The open wheel also keeps the click. */
	public static boolean blocksUse(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null) return false;
		if (SpellWheel.isOpen()) return true;
		boolean off = GrimoireItem.isGrimoire(p.getOffhandItem());
		boolean empty = p.getMainHandItem().isEmpty();
		boolean typing = typing();
		// The pick is only needed for the hologram rule.
		return blocksUse(typing, !typing && off && empty && aimingAtHologram(mc), off, empty);
	}

	public static boolean wheelAllowed(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || p.isSpectator()) return false;
		boolean held = stance(GrimoireItem.isGrimoire(p.getMainHandItem()), p.getMainHandItem().isEmpty(),
				GrimoireItem.isGrimoire(p.getOffhandItem()));
		return wheelAllowed(mc.gui.screen() != null, typing(), held, MagicAttachments.get(p).element().isPresent());
	}

	public static boolean scrollSelects(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || p.isSpectator() || mc.gui.screen() != null) return false;
		boolean shift = mc.hasShiftDown();
		boolean off = GrimoireItem.isGrimoire(p.getOffhandItem());
		boolean empty = p.getMainHandItem().isEmpty();
		boolean bound = MagicAttachments.get(p).element().isPresent();
		if (!shift || !off || !empty || !bound) return false;
		boolean typing = typing();
		return scrollSelects(true, typing, !typing && aimingAtHologram(mc), true, true, true);
	}

	/** The player is charging a grimoire (either hand). */
	public static boolean usingGrimoire(LocalPlayer p) {
		return p.isUsingItem() && GrimoireItem.isGrimoire(p.getUseItem());
	}
}
