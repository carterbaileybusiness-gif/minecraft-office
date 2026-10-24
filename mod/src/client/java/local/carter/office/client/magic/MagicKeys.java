package local.carter.office.client.magic;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.platform.InputConstants;

import local.carter.office.OfficeMod;
import local.carter.office.magic.core.ScrollSelect;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.net.MagicPayloads.SelectSpell;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellRegistry;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.client.player.ClientHotbarScrollEvents;

/**
 * Spell selection keys: hold <b>R</b> for the spell wheel ({@link SpellWheel}; release picks, never casts) and
 * <b>Shift + scroll</b> to step through unlocked spells. {@link MagicInputGate} decides when they apply: typing or work mode
 * blocks both; aiming at a hologram leaves the scroll to the monitor; the wheel needs casting stance. The scroll handler is
 * registered after the hologram one ({@code MagicClient} after {@code HologramInteractions}), so the monitor sees the wheel
 * first.
 */
public final class MagicKeys {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(OfficeMod.id("magic"));
	public static final KeyMapping WHEEL = new KeyMapping("key.office.spell_wheel", InputConstants.Type.KEYBOARD, InputConstants.KEY_R, CATEGORY);
	/** A just-sent selection counts as current until the server's copy agrees (or this long passes). */
	private static final long PENDING_MS = 1500;
	/** Scroll-event phase that runs before the default one (the monitors' scroll handlers). */
	static final Identifier WHEEL_PHASE = OfficeMod.id("spell_wheel");

	private static @Nullable Identifier pending;
	private static long pendingAt;

	private MagicKeys() {}

	public static void register() {
		KeyMappingHelper.registerKeyMapping(WHEEL);
		ClientTickEvents.START_CLIENT_TICK.register(MagicKeys::beforeTick);
		ClientTickEvents.END_CLIENT_TICK.register(MagicKeys::tick);
		// The open wheel takes the mouse wheel before anything else (a monitor under the crosshair included); Shift + scroll
		// comes after the monitors (default phase, registered after HologramInteractions).
		ClientHotbarScrollEvents.ALLOW.addPhaseOrdering(WHEEL_PHASE, Event.DEFAULT_PHASE);
		ClientHotbarScrollEvents.ALLOW.register(WHEEL_PHASE, (inventory, current, next, dx, dy) -> onWheelScroll(dx, dy));
		ClientHotbarScrollEvents.ALLOW.register((inventory, current, next, dx, dy) -> onScroll(dx, dy));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			SpellWheel.reset();
			pending = null;
		});
	}

	/** The selected spell as this client knows it: a selection it just sent, else the synced one. */
	public static @Nullable Identifier selected(MagicData d) {
		Identifier sel = d.selected().orElse(null);
		if (pending != null) {
			if (pending.equals(sel) || Util.getMillis() - pendingAt > PENDING_MS) pending = null;
			else return pending;
		}
		return sel;
	}

	/** Before vanilla's key handling: a grimoire charge never releases (casts) from typing or work mode. */
	private static void beforeTick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p != null && MagicInputGate.dropsCharge(MagicInputGate.typing(), MagicInputGate.usingGrimoire(p))) {
			p.stopUsingItem(); // client side only: vanilla then sends no release, so nothing is cast
		}
	}

	private static void tick(Minecraft mc) {
		SpellWheel.tick();
		boolean pressed = false;
		while (WHEEL.consumeClick()) pressed = true;
		if (mc.player == null) {
			SpellWheel.reset();
			return;
		}
		boolean down = WHEEL.isDown();
		if (SpellWheel.isOpen()) {
			if (!MagicInputGate.wheelAllowed(mc)) {
				SpellWheel.cancel(); // a screen opened, typing began or the stance was lost: nothing is chosen
			} else if (!down) {
				Identifier id = SpellWheel.releaseSelection();
				if (id != null) choose(id, true);
			}
		} else if (pressed && down && MagicInputGate.wheelAllowed(mc)) {
			SpellWheel.open();
		}
	}

	/**
	 * Mouse wheel while the R wheel is open: steps its hover, whatever is under the crosshair (this listener runs in
	 * {@link #WHEEL_PHASE}, before the monitors'). False = the hotbar slot stays.
	 */
	static boolean onWheelScroll(double dx, double dy) {
		if (!SpellWheel.isOpen()) return true;
		int steps = MagicInputGate.scrollSteps(dx, dy); // vanilla already added up trackpad fractions
		for (int i = 0; i < Math.abs(steps); i++) SpellWheel.step(steps < 0 ? 1 : -1);
		return false;
	}

	/** Shift + scroll in the world, after the monitors had their turn. False = it was magic's (the hotbar slot stays). */
	static boolean onScroll(double dx, double dy) {
		if (SpellWheel.isOpen()) return false;
		Minecraft mc = Minecraft.getInstance();
		MagicInputGate.Scroll what = MagicInputGate.scroll(mc);
		if (what == MagicInputGate.Scroll.VANILLA) return true;
		if (what == MagicInputGate.Scroll.SWALLOW) return false;
		int steps = MagicInputGate.scrollSteps(dx, dy); // vanilla already added up trackpad fractions
		if (steps == 0) return false;
		MagicData d = MagicAttachments.get(mc.player);
		List<Identifier> ids = SpellRegistry.unlocked(d).stream().map(Spell::id).toList();
		Identifier current = selected(d);
		Identifier next = current;
		for (int i = 0; i < Math.abs(steps); i++) next = ScrollSelect.next(ids, next, steps);
		if (next != null && !next.equals(current)) choose(next, false);
		return false;
	}

	/** Sends the selection (never while typing) and remembers it until the server agrees. */
	private static void choose(Identifier id, boolean fromWheel) {
		if (MagicInputGate.typing() || !ClientPlayNetworking.canSend(SelectSpell.TYPE)) return;
		ClientPlayNetworking.send(new SelectSpell(id));
		pending = id;
		pendingAt = Util.getMillis();
		var sounds = Minecraft.getInstance().getSoundManager();
		if (fromWheel) {
			sounds.play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.1f, 0.9f));
			sounds.play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, 1.5f, 0.6f));
		} else {
			sounds.play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.35f, 0.5f));
		}
	}
}
