package local.carter.office.client.interact;

import java.util.Optional;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.interact.HologramPicker.Picked;
import local.carter.office.client.render.HologramPlacement;
import local.carter.office.core.HologramGeometry.Corner;
import local.carter.office.core.HologramGeometry.Frame;
import local.carter.office.core.HologramGeometry.Hit;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Vec3d;
import local.carter.office.hologram.HologramBlockEntity;
import local.carter.office.hologram.OfficeBlocks;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.client.player.ClientHotbarScrollEvents;

/**
 * Right-click handling for holograms (client thread). While holding a Hologram Emitter, on a hologram you own:
 * <ul>
 *   <li>right-click a corner and hold: resize ({@link CornerDrag});</li>
 *   <li>Shift + right-click the body and hold: move, mouse-wheel turns it ({@link MoveDrag});</li>
 *   <li>right-click the body: settings.</li>
 * </ul>
 * Right-clicking an emitter block (any hand, not sneaking) opens settings, read-only for non-owners. Called from
 * {@code MinecraftUseItemMixin} at the head of {@code Minecraft.startUseItem}; a consumed click never reaches vanilla, so
 * an emitter in hand is never placed by accident on the wall behind a hologram.
 */
public final class HologramInteractions {
	private static @Nullable CornerDrag cornerDrag;
	private static @Nullable MoveDrag moveDrag;

	private HologramInteractions() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(HologramInteractions::tick);
		ClientHotbarScrollEvents.ALLOW.register((inventory, current, next, dx, dy) -> {
			MoveDrag move = moveDrag;
			if (move == null) {
				return true;
			}
			double amount = dy != 0 ? dy : dx;
			if (amount != 0) {
				move.rotate(amount > 0 ? 1 : -1);
			}
			return false;
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			cancel();
			HologramPreview.clear();
		});
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
		if (cornerDrag != null) {
			cornerDrag.update(eye, look);
		}
		if (moveDrag != null) {
			moveDrag.update(eye, look);
		}
	}

	/** Use key pressed (or repeating while held). Returns true when the click was for a hologram. */
	public static boolean onUse(final Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.gui.screen() != null) {
			return false;
		}
		if (dragging()) {
			return true; // repeats while the button is held
		}
		float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Vec3 eye = player.getEyePosition(partialTick);
		double vanillaDistance = mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS
				? mc.hitResult.getLocation().distanceTo(eye) : Double.POSITIVE_INFINITY;
		Optional<Picked> picked = HologramPicker.pick(partialTick);
		boolean holdingEmitter = holdingEmitter(player);

		if (holdingEmitter && picked.isPresent() && picked.get().hit().distance() <= vanillaDistance + 1e-3) {
			return onHologram(mc, player, picked.get());
		}
		if (mc.hitResult instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK && !player.isShiftKeyDown()
				&& mc.level.getBlockEntity(block.getBlockPos()) instanceof HologramBlockEntity be && be.spec() != null) {
			openSettings(mc, be);
			return true;
		}
		return false;
	}

	private static boolean onHologram(final Minecraft mc, final LocalPlayer player, final Picked picked) {
		HologramBlockEntity be = picked.be();
		HologramSpec spec = be.spec();
		if (spec == null) {
			return false;
		}
		if (!be.isOwner(player.getUUID())) {
			player.sendOverlayMessage(Component.literal("Owned by " + ownerName(mc, be)));
			return true;
		}
		Hit hit = picked.hit();
		if (player.isShiftKeyDown()) {
			// grab point on the panel from the hit's (u, v)
			Frame f = HologramPlacement.frame(be.getBlockPos().getX(), be.getBlockPos().getY(), be.getBlockPos().getZ(), spec);
			Vec3d point = f.center().add(f.right().scale((hit.u() - 0.5) * f.w())).add(f.up().scale((0.5 - hit.v()) * f.h()));
			moveDrag = new MoveDrag(be, spec, point, hit.distance());
			player.sendOverlayMessage(Component.literal("Moving — scroll to turn, release to place"));
			return true;
		}
		if (hit.corner() != null) {
			cornerDrag = new CornerDrag(be, spec, hit.corner());
			return true;
		}
		openSettings(mc, be);
		return true;
	}

	private static void tick(final Minecraft mc) {
		if (!dragging()) {
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
			finish();
		}
	}

	private static void finish() {
		updateDrag(1.0f);
		HologramBlockEntity be = cornerDrag != null ? cornerDrag.blockEntity() : moveDrag.blockEntity();
		HologramSpec predicted = cornerDrag != null ? cornerDrag.finish() : moveDrag.finish();
		if (predicted != null) {
			HologramPreview.expect(be.getBlockPos(), predicted);
		}
		cornerDrag = null;
		moveDrag = null;
	}

	public static void cancel() {
		cornerDrag = null;
		moveDrag = null;
	}

	static boolean holdingEmitter(final LocalPlayer player) {
		return player.getItemInHand(InteractionHand.MAIN_HAND).getItem() == OfficeBlocks.HOLOGRAM_EMITTER_ITEM
				|| player.getItemInHand(InteractionHand.OFF_HAND).getItem() == OfficeBlocks.HOLOGRAM_EMITTER_ITEM;
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
