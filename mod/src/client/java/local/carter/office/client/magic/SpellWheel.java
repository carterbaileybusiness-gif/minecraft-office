package local.carter.office.client.magic;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.core.WheelMath;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellRegistry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;

/**
 * The R spell wheel's state (client thread). While it is open the mouse no longer turns the camera
 * ({@code EntityTurnWheelMixin} feeds the turn to {@link #addMouse}); its direction from the centre picks a slice
 * ({@link WheelMath}: slice index = slot, 0 straight up, clockwise). Releasing R picks the hovered slice when it holds an
 * unlocked spell ({@link #releaseSelection}); it never casts. Back in the centre, or on a locked slice, nothing changes.
 * The wheel eases open over {@link #OPEN_TICKS} and fades out over {@link #CLOSE_TICKS}.
 */
public final class SpellWheel {
	public static final int OPEN_TICKS = 3;
	public static final int CLOSE_TICKS = 3;
	/** Radius the scroll wheel puts the pointer at when it steps between slices. */
	private static final double STEP_RADIUS = 40;

	private static boolean open;
	private static int age;
	private static int closing;
	private static double vx;
	private static double vy;
	private static double travel;
	private static int hovered = -1;
	private static long hoveredAt;
	/** The slice picked on release (flashes while the wheel fades), or -1. */
	private static int chosen = -1;

	private SpellWheel() {}

	public static void open() {
		open = true;
		age = 0;
		closing = 0;
		vx = vy = travel = 0;
		hovered = -1;
		chosen = -1;
		hoveredAt = Util.getMillis();
	}

	public static boolean isOpen() {
		return open;
	}

	/** Open, or fading out. */
	public static boolean visible() {
		return open || closing > 0;
	}

	/**
	 * Mouse movement while open (the camera turn vanilla asked for: about one unit per pixel at the default sensitivity, y
	 * down); the pointer stays within {@link WheelMath#MAX_RADIUS}.
	 */
	public static void addMouse(double dx, double dy) {
		if (!open) return;
		travel += Math.hypot(dx, dy);
		double[] c = WheelMath.clamp(vx + dx, vy + dy);
		vx = c[0];
		vy = c[1];
		updateHover();
	}

	/** The hovered slice (= slot), or -1 (centre, or not moved yet). */
	public static int hovered() {
		return hovered;
	}

	/** The pointer's offset from the centre, in {@link WheelMath} units. */
	public static double pointerX() {
		return vx;
	}

	public static double pointerY() {
		return vy;
	}

	/** {@code Util.getMillis()} when the hovered slice last changed. */
	public static long hoveredAt() {
		return hoveredAt;
	}

	public static int chosen() {
		return chosen;
	}

	/** Mouse wheel while open: hover the next ({@code dir} > 0) or previous unlocked slice. */
	public static void step(int dir) {
		if (!open || dir == 0) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		MagicData d = MagicAttachments.get(mc.player);
		int from = hovered;
		if (from < 0) {
			Identifier sel = MagicKeys.selected(d);
			for (int slot = 0; slot < Slots.WHEEL_SLICES; slot++) {
				Spell s = spellAt(d, slot);
				if (s != null && s.id().equals(sel)) from = slot;
			}
		}
		int base = from >= 0 ? from : (dir > 0 ? -1 : Slots.WHEEL_SLICES);
		for (int i = 1; i <= Slots.WHEEL_SLICES; i++) {
			int slot = Math.floorMod(base + i * Integer.signum(dir), Slots.WHEEL_SLICES);
			if (selectable(d, slot) != null) {
				double a = WheelLayout.Layout.angle(slot);
				vx = STEP_RADIUS * Math.sin(a);
				vy = -STEP_RADIUS * Math.cos(a);
				travel = Math.max(travel, WheelMath.SELECT_MIN + 1);
				updateHover();
				return;
			}
		}
	}

	/**
	 * R released: closes the wheel and returns the hovered spell to select, or null to keep the current one (centre, a
	 * locked or empty slice, or the spell already selected). Never casts.
	 */
	public static @Nullable Identifier releaseSelection() {
		if (!open) return null;
		int slot = hovered;
		close();
		Minecraft mc = Minecraft.getInstance();
		if (slot < 0 || mc.player == null) return null;
		MagicData d = MagicAttachments.get(mc.player);
		Spell s = selectable(d, slot);
		if (s == null) {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS, 0.6f));
			return null;
		}
		chosen = slot;
		return s.id().equals(MagicKeys.selected(d)) ? null : s.id();
	}

	/** Closes without choosing (a screen opened, typing began, the stance was lost). */
	public static void cancel() {
		if (!open) return;
		close();
	}

	/** Gone at once (disconnect). */
	public static void reset() {
		open = false;
		closing = 0;
		hovered = -1;
		chosen = -1;
	}

	private static void close() {
		open = false;
		closing = CLOSE_TICKS;
	}

	public static void tick() {
		if (open) age++;
		else if (closing > 0) closing--;
	}

	/** 0 (closed) … 1 (fully open), for drawing. */
	public static float progress(float partial) {
		if (open) return Math.clamp((age + partial) / OPEN_TICKS, 0f, 1f);
		if (closing > 0) return Math.clamp((closing - partial) / CLOSE_TICKS, 0f, 1f);
		return 0f;
	}

	public static boolean closingNow() {
		return !open && closing > 0;
	}

	/** The spell on the player's wheel at {@code slot} (registered; locked or not), or null. */
	public static @Nullable Spell spellAt(MagicData d, int slot) {
		Element e = d.element().orElse(null);
		if (e == null) return null;
		return SpellRegistry.at(e, slot).orElse(null);
	}

	/** The spell at {@code slot} when the player may select it (registered and unlocked), or null. */
	public static @Nullable Spell selectable(MagicData d, int slot) {
		Spell s = spellAt(d, slot);
		return s != null && d.level() >= Slots.unlockLevel(slot) ? s : null;
	}

	private static void updateHover() {
		int h = travel > WheelMath.SELECT_MIN ? WheelMath.sliceAt(vx, vy, Slots.WHEEL_SLICES) : -1;
		if (h == hovered) return;
		hovered = h;
		hoveredAt = Util.getMillis();
		if (h >= 0) {
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.8f, 0.3f));
		}
	}
}
