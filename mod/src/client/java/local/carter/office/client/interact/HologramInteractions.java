package local.carter.office.client.interact;

import java.util.Locale;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.interact.HologramPicker.Picked;
import local.carter.office.client.pointer.PointerClient;
import local.carter.office.client.render.HologramLook;
import local.carter.office.client.render.HologramPlacement;
import local.carter.office.client.zoom.ZoomClient;
import local.carter.office.client.zoom.ZoomController;
import local.carter.office.core.HologramGeometry.Corner;
import local.carter.office.core.HologramGeometry.Frame;
import local.carter.office.core.HologramGeometry.Hit;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;
import local.carter.office.core.Mount;
import local.carter.office.core.Vec3d;
import local.carter.office.hologram.HologramBlockEntity;
import local.carter.office.hologram.HologramEmitterBlock;
import local.carter.office.hologram.OfficeBlocks;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.client.player.ClientHotbarScrollEvents;

/**
 * Right-click and wheel handling for holograms (client thread). With a Hologram Emitter in the MAIN hand, on a hologram
 * you own:
 * <ul>
 *   <li>looking at it: glowing corner handles and an action-bar hint ({@link #HINT});</li>
 *   <li>right-click a corner (or just outside it) and hold: resize ({@link CornerDrag}); a quick tap is a click;</li>
 *   <li>mouse-wheel: resize ×1.1 per notch ({@link ScrollResize}); the hotbar slot doesn't change;</li>
 *   <li>Shift + right-click and hold: move, the wheel turns it ({@link MoveDrag}); on a wall it slides along the wall;</li>
 *   <li>right-click the body: settings.</li>
 * </ul>
 * Without an emitter in the main hand (empty, or any other item: a Wizards staff, food, a shield), right-clicking your
 * OWN hologram is always taken: on a Mac it is a right-click on the app there ({@link PointerClient}, in-world use;
 * left clicks, drags and the wheel go there too); elsewhere it zooms in to work on it ({@link ZoomClient}). Either way the
 * action bar says why when it can't (not live yet, helper not ready, Mirror is view-only). Someone else's hologram swallows the click (no
 * message): nothing is ever placed or used through a hologram ({@link HologramClickRule}), and an emitter in the off hand alone never makes a click a hologram click. Right-clicking an emitter block
 * (empty main hand or an emitter, not sneaking) opens settings, read-only for non-owners. Called from {@code MinecraftUseItemMixin} at the head of
 * {@code Minecraft.startUseItem} (after vanilla's destroying/hands-busy guards; a consumed click gets vanilla's 4-tick
 * repeat delay and its repeats are swallowed until release, see {@link ClickGate}); a consumed click never reaches
 * vanilla, so an emitter in hand is never placed by accident on the wall behind a hologram.
 */
public final class HologramInteractions {
	/** Shown in the action bar while you look at your own hologram with an emitter in the main hand. */
	public static final String HINT = "Hold right-click on a corner and drag to resize · Scroll to resize · Shift + hold right-click to move";
	/** Re-send the hint this often (ticks) while hovering, so it doesn't fade. */
	private static final int HINT_EVERY_TICKS = 40;

	private static @Nullable CornerDrag cornerDrag;
	private static @Nullable MoveDrag moveDrag;
	private static final ClickGate GATE = new ClickGate();
	private static @Nullable HologramBlockEntity hinted;
	private static int hintCooldown;
	private static int dragHintCooldown;

	private HologramInteractions() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(HologramInteractions::tick);
		ClientHotbarScrollEvents.ALLOW.register((inventory, current, next, dx, dy) -> onScroll(dx, dy));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			cancel();
			HologramPreview.clear();
			ScrollResize.clear();
		});
	}

	/**
	 * Mouse wheel: turns a hologram being moved, or resizes the owned hologram under the crosshair (emitter in the main
	 * hand). Returns false (hotbar slot unchanged) when the wheel was ours.
	 */
	static boolean onScroll(final double dx, final double dy) {
		if (PointerClient.onScroll(dx, dy)) {
			return false; // scrolled the app under the crosshair (in-world use): the hotbar stays
		}
		double amount = dy != 0 ? dy : dx;
		int notches = amount > 0 ? 1 : amount < 0 ? -1 : 0;
		MoveDrag move = moveDrag;
		if (move != null) {
			move.rotate(notches);
			return false;
		}
		if (cornerDrag != null) {
			return false;
		}
		Hover h = hover(Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true));
		if (h == null) {
			return true;
		}
		ScrollResize.scroll(h.be(), notches);
		return false;
	}

	/** The owned hologram under the crosshair while holding an emitter (and the corner, if one is in grab range). */
	public record Hover(HologramBlockEntity be, @Nullable Corner corner) {}

	private static long hoverTick = Long.MIN_VALUE;
	private static float hoverPartial = Float.NaN;
	private static @Nullable Hover hover;

	/** Hover target for this frame (computed once per frame, shared by every hologram's render-state extraction). */
	public static @Nullable Hover hover(final float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			return null;
		}
		long tick = mc.level.getGameTime();
		if (tick == hoverTick && Float.compare(partialTick, hoverPartial) == 0) {
			return hover;
		}
		hoverTick = tick;
		hoverPartial = partialTick;
		hover = null;
		if (!dragging() && mc.gui.screen() == null && holdingEmitter(player)) {
			HologramPicker.pick(partialTick)
					.filter(p -> p.be().isOwner(player.getUUID()))
					.ifPresent(p -> hover = new Hover(p.be(), p.hit().corner()));
		}
		return hover;
	}

	/** True while a resize or move drag is in progress. */
	public static boolean dragging() {
		return cornerDrag != null || moveDrag != null;
	}

	public static @Nullable CornerDrag cornerDrag() {
		return cornerDrag;
	}

	/** True when {@code be} is the hologram being resized or moved. */
	public static boolean isDragging(final HologramBlockEntity be) {
		return (cornerDrag != null && cornerDrag.blockEntity() == be) || (moveDrag != null && moveDrag.blockEntity() == be);
	}

	/** The live position of {@code be} while it is being moved, else null. */
	static @Nullable HologramSpec movingPreview(final HologramBlockEntity be) {
		MoveDrag move = moveDrag;
		return move != null && move.blockEntity() == be ? move.preview() : null;
	}

	/** Recomputes the active drag from the current view (the renderer calls this once per frame). */
	public static void updateDrag(final float partialTick) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || !dragging()) {
			return;
		}
		Vec3d eye = HologramPicker.vec(player.getEyePosition(partialTick));
		Vec3d look = HologramPicker.vec(player.getViewVector(partialTick));
		long now = Util.getMillis();
		if (cornerDrag != null) {
			cornerDrag.update(eye, look, now);
		}
		if (moveDrag != null) {
			moveDrag.update(eye, look, now);
		}
	}

	/** Use key pressed (or repeating while held). Returns true when the click was for a hologram. */
	public static boolean onUse(final Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.gui.screen() != null) {
			return false;
		}
		if (dragging() || GATE.isRepeat()) {
			return true; // auto-repeat of a click we already took: swallow until the button is released
		}
		if (consumeClick(mc, player)) {
			GATE.consumed();
			return true;
		}
		return false;
	}

	private static boolean consumeClick(final Minecraft mc, final LocalPlayer player) {
		float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Vec3 eye = player.getEyePosition(partialTick);
		double vanillaDistance = mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS
				? mc.hitResult.getLocation().distanceTo(eye) : Double.POSITIVE_INFINITY;
		Optional<Picked> picked = HologramPicker.pick(partialTick);
		boolean holdingEmitter = holdingEmitter(player);

		boolean hologramFirst = picked.isPresent() && picked.get().hit().distance() <= vanillaDistance + 1e-3;
		boolean own = hologramFirst && picked.get().be().isOwner(player.getUUID());
		switch (HologramClickRule.decide(hologramFirst, own, holdingEmitter, PointerClient.available())) {
			case EDIT -> {
				return onHologram(mc, player, picked.get());
			}
			case POINTER -> {
				return PointerClient.onUse(mc, picked.get().be());
			}
			case ZOOM -> {
				return zoom(player, picked.get().be());
			}
			case SWALLOW -> {
				return true; // a friend's screen: never place or use anything through it
			}
			case NOT_A_HOLOGRAM -> {
				// below: the emitter block itself, else vanilla
			}
		}
		boolean mainHandFree = holdingEmitter || player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty();
		if (mainHandFree && mc.hitResult instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK && !player.isShiftKeyDown()
				&& mc.level.getBlockEntity(block.getBlockPos()) instanceof HologramBlockEntity be && be.spec() != null) {
			openSettings(mc, be);
			return true;
		}
		return false;
	}

	/**
	 * Right-click on your own hologram without an emitter in the main hand where in-world use isn't available: zoom in to
	 * work on it.
	 */
	private static boolean zoom(final LocalPlayer player, final HologramBlockEntity be) {
		ZoomController.Request r = ZoomClient.request(be);
		switch (r) {
			case STARTED, BUSY, NOT_OWNER -> {
				// NOT_OWNER can't happen (checked by the caller); consumed either way so nothing is placed through it
			}
			case UNAVAILABLE -> {
				String why = ZoomClient.problem(be);
				player.sendOverlayMessage(Component.literal(why != null ? why : "Can't work on this screen right now"));
			}
			case NOT_LIVE -> player.sendOverlayMessage(Component.literal(notLiveMessage(be)));
		}
		return true;
	}

	private static String notLiveMessage(final HologramBlockEntity be) {
		HologramSpec spec = be.spec();
		HologramLook.OwnSource own = HologramLook.ownSource(be.hologramId());
		if (own == HologramLook.OwnSource.NEEDS_HELPER) {
			return "Set up your screens first: /officescreens setup";
		}
		if (own == HologramLook.OwnSource.UNAVAILABLE) {
			String detail = HologramLook.ownDetail(be.hologramId()); // e.g. Windows Mirror that can't hide Minecraft
			return detail != null ? detail : "Screens unavailable on this computer";
		}
		if (spec != null && spec.mode() == Mode.WINDOW && (own == HologramLook.OwnSource.WINDOW_CLOSED
				|| own == HologramLook.OwnSource.PICK_WINDOW || spec.windowKey() == null
				|| spec.windowKey().isEmpty())) {
			return "Pick a window first: right-click the emitter";
		}
		return "This screen is still connecting…";
	}

	private static boolean onHologram(final Minecraft mc, final LocalPlayer player, final Picked picked) {
		HologramBlockEntity be = picked.be();
		if (be.spec() == null) {
			return false;
		}
		if (!be.isOwner(player.getUUID())) {
			player.sendOverlayMessage(Component.literal("Owned by " + ownerName(mc, be)));
			return true;
		}
		HologramSpec spec = HologramPreview.effective(be); // what is drawn (a just-scrolled size may not be synced yet)
		if (spec == null) {
			return false;
		}
		Hit hit = picked.hit();
		// grab point on the hologram's plane from the hit's (u, v); outside [0, 1] when grabbed just past a corner
		Frame f = HologramPlacement.frame(be.getBlockPos().getX(), be.getBlockPos().getY(), be.getBlockPos().getZ(), spec);
		Vec3d point = f.center().add(f.right().scale((hit.u() - 0.5) * f.w())).add(f.up().scale((0.5 - hit.v()) * f.h()));
		Mount mount = HologramEmitterBlock.mount(be.getBlockState());
		long now = Util.getMillis();
		if (player.isShiftKeyDown()) {
			moveDrag = new MoveDrag(be, spec, mount, point, hit.distance(), now);
			player.sendOverlayMessage(Component.literal(mount == Mount.WALL ? "Moving — it slides along the wall, release to place"
					: "Moving — scroll to turn, release to place"));
			return true;
		}
		if (hit.corner() != null) {
			cornerDrag = new CornerDrag(be, spec, hit.corner(), mount, point, now);
			return true;
		}
		openSettings(mc, be);
		return true;
	}

	private static void tick(final Minecraft mc) {
		GATE.tick(mc.options.keyUse.isDown());
		ScrollResize.tick();
		if (!dragging()) {
			showHint(mc);
			return;
		}
		LocalPlayer player = mc.player;
		if (player == null || mc.gui.screen() != null || !holdingEmitter(player)) {
			cancel();
			return;
		}
		HologramBlockEntity be = cornerDrag != null ? cornerDrag.blockEntity() : moveDrag.blockEntity();
		if (be.isRemoved() || be.spec() == null) {
			cancel();
			return;
		}
		if (!mc.options.keyUse.isDown()) {
			finish(mc);
			return;
		}
		CornerDrag drag = cornerDrag;
		if (drag != null && drag.armed() && --dragHintCooldown <= 0) {
			dragHintCooldown = 5;
			HologramSpec p = drag.preview();
			player.sendOverlayMessage(Component.literal(String.format(Locale.ROOT, "%.1f × %.1f blocks — release to set", p.width(), p.height())));
		}
	}

	private static void finish(final Minecraft mc) {
		updateDrag(1.0f);
		HologramBlockEntity be = cornerDrag != null ? cornerDrag.blockEntity() : moveDrag.blockEntity();
		boolean tap = cornerDrag != null && !cornerDrag.armed();
		HologramSpec predicted = cornerDrag != null ? cornerDrag.finish() : moveDrag.finish();
		if (predicted != null) {
			HologramPreview.expect(be.getBlockPos(), predicted);
		}
		cornerDrag = null;
		moveDrag = null;
		dragHintCooldown = 0;
		if (tap) {
			openSettings(mc, be); // a quick tap on a corner is a click, like on the body
		}
	}

	/** Action-bar hint while you look at your own hologram holding an emitter (re-sent so it doesn't fade). */
	private static void showHint(final Minecraft mc) {
		LocalPlayer player = mc.player;
		Hover h = player == null || mc.level == null ? null : hover(1.0f);
		if (h == null) {
			hinted = null;
			return;
		}
		if (h.be() != hinted || --hintCooldown <= 0) {
			hinted = h.be();
			hintCooldown = HINT_EVERY_TICKS;
			player.sendOverlayMessage(Component.literal(HINT));
		}
	}

	public static void cancel() {
		cornerDrag = null;
		moveDrag = null;
	}

	/** Main hand only: an off-hand emitter must not take right-clicks meant for the main-hand item. */
	static boolean holdingEmitter(final LocalPlayer player) {
		return player.getItemInHand(InteractionHand.MAIN_HAND).getItem() == OfficeBlocks.HOLOGRAM_EMITTER_ITEM;
	}

	public static void openSettings(final Minecraft mc, final HologramBlockEntity be) {
		mc.gui.setScreen(new HologramSettingsScreen(be, ownerName(mc, be)));
	}

	/** Owner's name: recorded on the block entity, else from the tab list, else a neutral fallback. */
	public static String ownerName(final Minecraft mc, final HologramBlockEntity be) {
		if (!be.ownerName().isEmpty()) {
			return be.ownerName();
		}
		HologramSpec spec = be.spec();
		if (spec != null && mc.getConnection() != null) {
			PlayerInfo info = mc.getConnection().getPlayerInfo(spec.owner());
			if (info != null) {
				return info.getProfile().name();
			}
		}
		return "another player";
	}
}
