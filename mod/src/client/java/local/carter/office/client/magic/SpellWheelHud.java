package local.carter.office.client.magic;

import java.util.ArrayList;
import java.util.List;

import local.carter.office.OfficeMod;
import local.carter.office.client.magic.WheelLayout.Align;
import local.carter.office.client.magic.screen.SignLayout.Rect;
import local.carter.office.client.magic.screen.UiKit;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.core.WheelMath;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellCost;
import local.carter.office.magic.spell.SpellIds;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

/**
 * Draws the R spell wheel ({@link SpellWheel}, laid out by {@link WheelLayout}) over the HUD: a dark halo and a slowly
 * turning hexagon circle in the element colour, six glass slices (slice index = slot) with the spell icons, each spell's
 * name beside its slice, and a small magic circle in the centre with the mana cost and cooldown of the hovered spell (else
 * the selected one). The hovered slice pops to {@link WheelLayout#HOVER_SCALE} with a glow; the selected one carries a pip
 * on its rim; a recharging spell sweeps its rim and veils its icon; locked or not-yet-made spells are grey with a padlock
 * and {@code magic.office.lv}; the ultimate shows {@code magic.office.plus_levels}. Colours and helpers are the grimoire
 * screen's ({@link UiKit}).
 */
public final class SpellWheelHud {
	public static final Identifier ID = OfficeMod.id("spell_wheel");
	public static final Identifier SLICE = OfficeMod.id("textures/gui/magic/wheel_slice.png");
	private static final int SLICE_TEX = 256;
	/** The slice texture's outer radius in texels (its half-size is 128). */
	private static final float SLICE_TEX_OUTER = 126f;
	private static final int MANA_BLUE = 0xFF3D7BE0;
	private static final int LOCK_RED = 0xFFE0705E;
	private static final int TEXT = 0xFFD9D2E6;
	private static final int TEXT_DIM = 0xFF8A8494;
	private static final int GLASS = 0xFF15111D;
	private static final int XP_GREEN = 0xFF80FF20;
	private static final int HOVER_MS = 110;

	private static final float[] hoverAnim = new float[Slots.WHEEL_SLICES];
	private static long lastFrameNanos;
	private static final List<FormattedCharSequence> lines = new ArrayList<>();

	private SpellWheelHud() {}

	public static void register() {
		HudElementRegistry.addLast(ID, SpellWheelHud::draw);
	}

	private static void draw(GuiGraphicsExtractor g, DeltaTracker dt) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		float partial = dt.getGameTimeDeltaPartialTick(false);
		float prog = SpellWheel.progress(partial);
		long nanos = System.nanoTime();
		float frameSec = lastFrameNanos == 0 ? 0f : Math.min(0.1f, (nanos - lastFrameNanos) / 1e9f);
		lastFrameNanos = nanos;
		if (p == null || mc.level == null || prog <= 0f) {
			java.util.Arrays.fill(hoverAnim, 0f);
			return;
		}
		MagicData d = MagicAttachments.get(p);
		Element e = d.element().orElse(null);
		if (e == null) return;
		boolean opening = SpellWheel.isOpen();
		float k = opening ? UiKit.easeOut(prog) : prog * prog;
		float pop = opening ? 0.82f + 0.18f * UiKit.easeOutBack(prog) : 1f + 0.05f * (1f - prog);
		WheelLayout.Layout l = WheelLayout.layout(g.guiWidth(), g.guiHeight());
		Font font = mc.font;
		int colour = e.argb();
		long now = Util.getMillis();
		long gameTime = mc.level.getGameTime();
		float gt = gameTime + partial;
		int hovered = SpellWheel.hovered();
		Identifier selected = MagicKeys.selected(d);
		float cx = l.cx(), cy = l.cy();
		float s = l.scale();

		for (int slot = 0; slot < Slots.WHEEL_SLICES; slot++) {
			float target = slot == hovered && opening ? 1f : 0f;
			hoverAnim[slot] = UiKit.approach(hoverAnim[slot], target, 1000f / HOVER_MS * 2.2f, frameSec);
			if (Math.abs(hoverAnim[slot] - target) < 0.002f) hoverAnim[slot] = target;
		}

		g.pose().pushMatrix();
		g.pose().translate(cx, cy);
		g.pose().scale(pop, pop);
		g.pose().translate(-cx, -cy);

		// Halo: darkens the world behind the wheel, with a faint element aura and a hexagon circle turning slowly.
		UiKit.glow(g, cx, cy, l.outer() * 3.6f, UiKit.alpha(0xFF000000, 0.78f * k));
		UiKit.glow(g, cx, cy, l.outer() * 2.9f, UiKit.alpha(colour, 0.16f * k));
		// The hexagon's corners sit on the slice borders; it stays still (a turning pixel ring shimmers when enlarged).
		UiKit.centredTexture(g, UiKit.ring(3), 128, cx, cy, l.outer() * 2.55f, 0f, UiKit.alpha(UiKit.mix(colour, UiKit.WHITE, 0.2f), 0.34f * k));

		// Slices, the hovered one last (on top).
		int[] order = new int[Slots.WHEEL_SLICES];
		int n = 0;
		for (int slot = 0; slot < Slots.WHEEL_SLICES; slot++) if (slot != hovered) order[n++] = slot;
		if (hovered >= 0) order[n] = hovered;
		for (int slot : order) slice(g, l, d, e, slot, selected, gt, now, k);

		// Pointer: a small spark in the direction the mouse points.
		double vx = SpellWheel.pointerX(), vy = SpellWheel.pointerY();
		double len = Math.hypot(vx, vy);
		if (opening && len > WheelMath.SELECT_MIN) {
			float reach = (float) Math.min(1.0, len / WheelMath.DEAD_ZONE);
			float px = cx + (float) (vx / len) * (l.inner() - 3f * s) * reach;
			float py = cy + (float) (vy / len) * (l.inner() - 3f * s) * reach;
			UiKit.glow(g, px, py, 14f * s, UiKit.alpha(colour, 0.9f * k));
			UiKit.glow(g, px, py, 6f * s, UiKit.alpha(UiKit.WHITE, 0.9f * k));
		}

		centre(g, font, l, d, e, hovered, selected, gameTime, now, k);
		g.pose().popMatrix();

		// Labels stay unscaled (crisp text).
		for (int slot = 0; slot < Slots.WHEEL_SLICES; slot++) label(g, font, l, d, e, slot, selected, k);
	}

	/** One slice: glass in the element colour (grey when locked), icon, rim pip when selected, cooldown sweep. */
	private static void slice(GuiGraphicsExtractor g, WheelLayout.Layout l, MagicData d, Element e, int slot, Identifier selected,
			float gt, long now, float k) {
		Spell spell = SpellWheel.spellAt(d, slot);
		Identifier id = spell != null ? spell.id() : SpellIds.at(e, slot);
		boolean locked = SpellWheel.selectable(d, slot) == null;
		boolean isSel = !locked && id.equals(selected);
		float h = hoverAnim[slot];
		float hs = 1f + (WheelLayout.HOVER_SCALE - 1f) * UiKit.easeOut(h);
		float cx = l.cx(), cy = l.cy();
		float ang = (float) WheelLayout.Layout.angle(slot);
		int colour = slot == Slots.RECALL ? Element.RECALL_ARGB : e.argb();
		boolean chosenFlash = SpellWheel.closingNow() && SpellWheel.chosen() == slot;

		int fill;
		float alpha;
		if (locked) {
			fill = 0xFF3A3742;
			alpha = 0.78f;
		} else {
			fill = UiKit.mix(GLASS, colour, isSel ? 0.55f : 0.30f);
			alpha = 0.86f;
		}
		fill = UiKit.mix(fill, locked ? 0xFF6A6672 : UiKit.mix(colour, UiKit.WHITE, 0.12f), h);
		alpha = alpha + (0.97f - alpha) * h;
		if (chosenFlash) fill = UiKit.mix(fill, UiKit.WHITE, 0.6f * SpellWheel.progress(0f));

		float size = l.outer() * hs * SLICE_TEX / SLICE_TEX_OUTER;
		float ix = cx + l.iconRadius() * hs * (float) Math.sin(ang);
		float iy = cy - l.iconRadius() * hs * (float) Math.cos(ang);
		if (h > 0.01f && !locked) UiKit.glow(g, ix, iy, l.outer() * 1.25f, UiKit.alpha(colour, 0.55f * h * k));
		UiKit.centredTexture(g, SLICE, SLICE_TEX, cx, cy, size, ang, UiKit.alpha(fill, alpha * k));

		// Selected: a bright pip on the rim; recharging: the rim fills back up clockwise.
		SpellCost cost = SpellCost.forSlot(slot);
		long left = locked ? 0 : d.cooldownLeft(id, (long) Math.floor(gt));
		float rim = l.outer() * hs - 2.2f * l.scale();
		if (left > 0) {
			float frac = Math.clamp((left - (gt - (float) Math.floor(gt))) / (float) Math.max(left, cost.cooldownTicks()), 0f, 1f);
			int segs = 16;
			for (int i = 0; i < segs; i++) {
				float t = (i + 0.5f) / segs;
				float a = ang + (float) Math.toRadians(-26 + 52 * t);
				boolean ready = t <= 1f - frac;
				dot(g, cx + rim * (float) Math.sin(a), cy - rim * (float) Math.cos(a), a, 2.4f * l.scale() * hs, 1.3f * l.scale(),
						UiKit.alpha(ready ? UiKit.mix(colour, UiKit.WHITE, 0.4f) : 0xFFFFFFFF, (ready ? 0.95f : 0.22f) * k));
			}
		} else if (isSel) {
			float pulse = 0.75f + 0.25f * (float) Math.sin(now / 220.0);
			float px = cx + (rim + 0.5f) * (float) Math.sin(ang), py = cy - (rim + 0.5f) * (float) Math.cos(ang);
			UiKit.glow(g, px, py, 16f * l.scale(), UiKit.alpha(colour, 0.8f * pulse * k));
			dot(g, px, py, ang, 9f * l.scale() * hs, 1.6f * l.scale(), UiKit.alpha(UiKit.mix(colour, UiKit.WHITE, 0.55f), k));
		}

		// Icon (grey when locked), cooldown veil, padlock, small caption under it.
		float icon = l.icon() * hs;
		int tint = locked ? 0x66FFFFFF : UiKit.WHITE;
		UiKit.centredTexture(g, UiKit.spellIcon(id), 32, ix, iy, icon, 0f, UiKit.alpha(tint, k));
		if (left > 0) {
			float frac = Math.clamp(left / (float) Math.max(left, cost.cooldownTicks()), 0f, 1f);
			int x0 = Math.round(ix - icon / 2f), x1 = Math.round(ix + icon / 2f), y1 = Math.round(iy + icon / 2f);
			int y0 = Math.round(y1 - icon * frac);
			g.fill(x0, y0, x1, y1, UiKit.alpha(0xFF100C16, 0.6f * k));
		}
		Font font = Minecraft.getInstance().font;
		float cs = UiKit.crisp(0.75f * l.scale());
		float capY = iy + icon / 2f + 1f * l.scale();
		if (locked) {
			UiKit.centredTexture(g, UiKit.PADLOCK, 16, ix + icon * 0.3f, iy + icon * 0.25f, Math.max(7f, icon * 0.55f), 0f, UiKit.alpha(UiKit.WHITE, k));
			if (d.level() < Slots.unlockLevel(slot)) {
				Component lv = Component.translatable("magic.office.lv", Slots.unlockLevel(slot));
				UiKit.centred(g, font, lv, ix, capY, cs, UiKit.alpha(LOCK_RED, k), true);
			}
		} else if (left > 0) {
			Component sec = Component.literal(left(left) + "s");
			UiKit.centred(g, font, sec, ix, capY, cs, UiKit.alpha(LOCK_RED, k), true);
		} else if (slot == Slots.ULTIMATE && cost.xpLevels() > 0) {
			Component lv = Component.translatable("magic.office.plus_levels", cost.xpLevels());
			UiKit.centred(g, font, lv, ix, capY, cs, UiKit.alpha(XP_GREEN, k), true);
		}
	}

	/** A small rotated bar ({@code w} along the rim, {@code t} thick) centred on (x, y). */
	private static void dot(GuiGraphicsExtractor g, float x, float y, float rot, float w, float t, int argb) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().rotate(rot);
		g.pose().scale(w / 8f, t / 2f);
		g.fill(-4, -1, 4, 1, argb);
		g.pose().popMatrix();
	}

	/** The centre: a little magic circle with the focused spell's mana cost and cooldown (or its unlock level). */
	private static void centre(GuiGraphicsExtractor g, Font font, WheelLayout.Layout l, MagicData d, Element e, int hovered,
			Identifier selected, long gameTime, long now, float k) {
		float cx = l.cx(), cy = l.cy();
		int colour = e.argb();
		UiKit.glow(g, cx, cy, l.inner() * 3.0f, UiKit.alpha(0xFF000000, 0.9f * k));
		float spin = (float) ((now % 24_000L) / 24_000.0 * Math.PI * 2);
		UiKit.centredTexture(g, UiKit.ring(0), 128, cx, cy, l.inner() * 2.05f, -spin, UiKit.alpha(colour, 0.38f * k));
		UiKit.centredTexture(g, UiKit.ring(2), 128, cx, cy, l.inner() * 2.05f, spin * 0.5f, UiKit.alpha(UiKit.mix(colour, UiKit.WHITE, 0.3f), 0.55f * k));

		int slot = hovered;
		if (slot < 0) {
			for (int i = 0; i < Slots.WHEEL_SLICES; i++) {
				Spell s = SpellWheel.spellAt(d, i);
				if (s != null && s.id().equals(selected)) slot = i;
			}
		}
		if (slot < 0) return;
		UiKit.glow(g, cx, cy, l.inner() * 1.7f, UiKit.alpha(0xFF000000, 0.75f * k)); // a darker spot under the text
		Spell spell = SpellWheel.spellAt(d, slot);
		Identifier id = spell != null ? spell.id() : SpellIds.at(e, slot);
		SpellCost cost = spell != null ? spell.cost() : SpellCost.forSlot(slot);
		boolean locked = SpellWheel.selectable(d, slot) == null;
		float ts = UiKit.crisp(l.scale());
		float line = 10f * ts;
		if (locked) {
			Component lv = d.level() < Slots.unlockLevel(slot) ? Component.translatable("magic.office.lv", Slots.unlockLevel(slot))
					: Component.literal("—");
			UiKit.centred(g, font, lv, cx, cy - 4f * ts, ts, UiKit.alpha(LOCK_RED, k), true);
			return;
		}
		long left = d.cooldownLeft(id, gameTime);
		boolean hasMana = cost.mana() > 0;
		float top = cy - (hasMana ? line : line / 2f) + 0.5f * ts;
		if (hasMana) {
			Component mana = Component.literal(Integer.toString(cost.mana()));
			float dw = 6f * ts;
			float w = dw + 2f * ts + font.width(mana) * ts;
			float x0 = cx - w / 2f;
			g.pose().pushMatrix();
			g.pose().translate(x0 + dw / 2f, top + 4f * ts);
			g.pose().rotate((float) (Math.PI / 4));
			int dm = Math.max(1, Math.round(2.2f * ts));
			g.fill(-dm, -dm, dm, dm, UiKit.alpha(MANA_BLUE, k));
			g.fill(-dm, -dm, dm, -dm + 1, UiKit.alpha(0xFF9CC2FF, k));
			g.pose().popMatrix();
			UiKit.text(g, font, mana, x0 + dw + 2f * ts, top, ts, UiKit.alpha(0xFFB8D4FF, k), true);
			top += line;
		}
		Component cd = left > 0 ? Component.literal(left(left) + "s") : Component.literal(seconds(cost.cooldownTicks()) + "s");
		UiKit.centred(g, font, cd, cx, top, ts, UiKit.alpha(left > 0 ? LOCK_RED : TEXT, k), true);
	}

	/** The spell's name beside its slice (two lines at the sides, one at top and bottom), brightest when hovered. */
	private static void label(GuiGraphicsExtractor g, Font font, WheelLayout.Layout l, MagicData d, Element e, int slot,
			Identifier selected, float k) {
		Rect r = l.labels().get(slot);
		Spell spell = SpellWheel.spellAt(d, slot);
		Identifier id = spell != null ? spell.id() : SpellIds.at(e, slot);
		boolean locked = SpellWheel.selectable(d, slot) == null;
		float h = hoverAnim[slot];
		int colour = slot == Slots.RECALL ? Element.RECALL_ARGB : e.argb();
		int base = locked ? TEXT_DIM : id.equals(selected) ? UiKit.mix(colour, UiKit.WHITE, 0.45f) : TEXT;
		int col = UiKit.mix(base, locked ? 0xFFB4AEBE : UiKit.mix(colour, UiKit.WHITE, 0.7f), h);
		float a = (locked ? 0.7f : 0.88f) + 0.12f * h;
		Component name = UiKit.spellName(id);
		Align align = l.aligns().get(slot);
		int maxLines = align == Align.CENTRE ? 1 : 2;
		float ts = UiKit.wrap(font, name, r.w(), UiKit.crisp(l.scale()), maxLines, lines);
		float total = lines.size() * 9f * ts + (lines.size() - 1) * ts;
		float y = r.y() + (r.h() - total) / 2f;
		float shift = 2f * h * l.scale() * (align == Align.LEFT ? 1 : align == Align.RIGHT ? -1 : 0);
		float lift = 2f * h * l.scale() * (slot == 0 ? -1 : slot == 3 ? 1 : 0);
		for (FormattedCharSequence line : lines) {
			float w = font.width(line) * ts;
			float x = switch (align) {
				case LEFT -> r.x();
				case RIGHT -> r.right() - w;
				case CENTRE -> r.x() + (r.w() - w) / 2f;
			};
			UiKit.text(g, font, line, x + shift, y + lift, ts, UiKit.alpha(col, a * k), true);
			y += 10f * ts;
		}
	}

	/** Time left: one decimal under 10 s ("2.4"), whole seconds rounded up above ("47"). */
	static String left(long ticks) {
		if (ticks < 200) return String.format(java.util.Locale.ROOT, "%.1f", ticks / 20f);
		return Long.toString((ticks + 19) / 20);
	}

	/** Ticks as seconds: whole when even ("3"), else one decimal ("0.5"), like the grimoire screen. */
	static String seconds(long ticks) {
		if (ticks % 20 == 0) return Long.toString(ticks / 20);
		return String.format(java.util.Locale.ROOT, "%.1f", ticks / 20f);
	}
}
