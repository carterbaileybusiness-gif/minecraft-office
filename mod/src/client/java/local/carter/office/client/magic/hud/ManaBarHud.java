package local.carter.office.client.magic.hud;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.magic.screen.SignLayout.Rect;
import local.carter.office.client.magic.screen.UiKit;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.ManaMath;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.item.GrimoireItem;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellCost;
import local.carter.office.magic.spell.SpellRegistry;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

/**
 * The mana bar. In casting stance ({@link ManaBarLayout#shows}: bound, a grimoire in the main hand or in the off hand with the
 * main hand empty, not on a jumpable mount) it takes the XP bar's place: 182 × 5 in the element colour, a notch per 10 mana,
 * a white sweep while the selected spell recharges, the mana number centred above and the XP level (vanilla green) at the
 * bar's right end (left end for left-handed players, clear of the off-hand slot). Any other time vanilla draws its XP,
 * locator and jump bars. After the selection changes, the spell's icon and name fade in for 40 ticks on vanilla's held-item
 * line (which they replace only meanwhile). The locator bar's markers are hidden while the mana bar shows, so they never sit
 * on top of it. Office action-bar hints (above this line) are untouched.
 */
public final class ManaBarHud {
	public static final int LABEL_TICKS = 40;
	private static final int FADE_IN = 4;
	private static final int FADE_OUT = 10;
	private static final int READY_FLASH_TICKS = 6;
	private static final int XP_GREEN = 0xFF80FF20;

	private static @Nullable Identifier lastSelected;
	private static boolean seen;
	private static int labelTicks;
	private static @Nullable Identifier label;
	/** Per spell: the readyAt seen and the cooldown length it started with (for the sweep's fraction). */
	private static final Map<Identifier, long[]> COOLDOWNS = new HashMap<>();
	private static int readyFlash;
	private static float shown = -1f;
	private static float trail = -1f;
	private static int trailHold;
	private static float lastTarget = -1f;

	private ManaBarHud() {}

	public static void register() {
		HudElementRegistry.replaceElement(VanillaHudElements.INFO_BAR, vanilla -> wrap(vanilla, ManaBarHud::bar));
		HudElementRegistry.replaceElement(VanillaHudElements.EXPERIENCE_LEVEL, vanilla -> wrap(vanilla, ManaBarHud::xpLevel));
		HudElementRegistry.replaceElement(VanillaHudElements.INFO_BAR_FOREGROUND, vanilla -> wrap(vanilla, (g, dt) -> {}));
		HudElementRegistry.replaceElement(VanillaHudElements.HELD_ITEM_TOOLTIP, vanilla -> (g, dt) -> {
			if (label != null && labelTicks > 0 && active(Minecraft.getInstance())) spellLabel(g, dt);
			else vanilla.extractRenderState(g, dt);
		});
		ClientTickEvents.END_CLIENT_TICK.register(ManaBarHud::tick);
	}

	private static HudElement wrap(HudElement vanilla, HudElement ours) {
		return (g, dt) -> {
			if (active(Minecraft.getInstance())) ours.extractRenderState(g, dt);
			else vanilla.extractRenderState(g, dt);
		};
	}

	/**
	 * The mana bar shows ({@link ManaBarLayout#shows}): casting stance (bound, grimoire in the main hand, or in the off hand
	 * with the main hand empty), not a spectator, not on a jumpable mount.
	 */
	public static boolean active(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || p.isSpectator()) return false;
		return ManaBarLayout.shows(MagicAttachments.get(p).element().isPresent(), GrimoireItem.isGrimoire(p.getMainHandItem()),
				p.getMainHandItem().isEmpty(), GrimoireItem.isGrimoire(p.getOffhandItem()), p.jumpableVehicle() != null);
	}

	/** Ticks left on the selected-spell label (0 = hidden). */
	public static int labelTicks() {
		return labelTicks;
	}

	private static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			seen = false;
			lastSelected = null;
			labelTicks = 0;
			COOLDOWNS.clear();
			shown = trail = -1f;
			return;
		}
		MagicData d = MagicAttachments.get(mc.player);
		Identifier sel = d.selected().orElse(null);
		if (!seen) {
			seen = true;
			lastSelected = sel;
		} else if (sel != null && !sel.equals(lastSelected)) {
			label = sel;
			labelTicks = LABEL_TICKS;
			lastSelected = sel;
		} else if (sel == null) {
			lastSelected = null;
		}
		if (labelTicks > 0) labelTicks--;
		long now = mc.level.getGameTime();
		if (sel != null) {
			Long ready = d.readyAt().get(sel);
			long[] cd = COOLDOWNS.get(sel);
			if (ready != null && ready > now && (cd == null || cd[0] != ready)) COOLDOWNS.put(sel, new long[] {ready, ready - now});
			if (cd != null && cd[0] <= now && cd[0] > now - 1) readyFlash = READY_FLASH_TICKS;
		}
		if (readyFlash > 0) readyFlash--;
		if (trailHold > 0) trailHold--;
	}

	// ---------------------------------------------------------------- drawing

	private static void bar(GuiGraphicsExtractor g, DeltaTracker dt) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		MagicData d = MagicAttachments.get(p);
		Element e = d.element().orElse(Element.FIRE);
		int colour = e.argb();
		int max = ManaMath.max(d.level());
		int mana = Math.clamp(MagicAttachments.mana(p), 0, max);
		ManaBarLayout.Layout l = ManaBarLayout.layout(g.guiWidth(), g.guiHeight());
		Rect r = l.bar();
		float frame = Math.min(1f, mc.getDeltaTracker().getRealtimeDeltaTicks() / 3f);

		// Smooth fill, with a pale trail that lingers a moment where mana was just spent, then drains.
		float target = ManaBarLayout.fillWidth(mana, max);
		if (shown < 0) shown = trail = lastTarget = target;
		if (target < lastTarget - 0.5f) trailHold = 8;
		lastTarget = target;
		shown += (target - shown) * Math.min(1f, 0.35f * frame * 3f);
		if (Math.abs(target - shown) < 0.3f) shown = target;
		if (trail < shown) trail = shown;
		else if (trailHold == 0) trail += (shown - trail) * Math.min(1f, 0.2f * frame * 3f);

		int x = r.x(), y = r.y(), x1 = r.right(), y1 = r.bottom();
		g.fill(x, y, x1, y1, 0xE00C0912);
		g.fill(x + 1, y + 1, x1 - 1, y1 - 1, UiKit.mix(0xFF1E1A26, colour, 0.18f));
		int fw = Math.round(shown);
		int tw = Math.round(trail);
		if (tw > fw) g.fill(x + fw, y + 1, x + tw, y1 - 1, UiKit.alpha(UiKit.mix(colour, UiKit.WHITE, 0.55f), 0.7f));
		if (fw > 0) {
			g.fill(x, y + 1, x + fw, y1 - 1, colour);
			g.fill(x, y + 1, x + fw, y + 2, UiKit.mix(colour, UiKit.WHITE, 0.45f));
			g.fill(x, y1 - 2, x + fw, y1 - 1, UiKit.mix(colour, 0xFF000000, 0.25f));
		}
		for (int n : ManaBarLayout.notches(max)) {
			boolean filled = n < fw;
			g.fill(x + n, y + 1, x + n + 1, y1 - 1, filled ? UiKit.mix(colour, 0xFF000000, 0.45f) : 0x50FFFFFF);
		}
		// What the selected spell needs, while mana is short of it.
		Optional<Spell> spell = d.selected().flatMap(SpellRegistry::get);
		Identifier sel = d.selected().orElse(null);
		if (sel != null && !p.hasInfiniteMaterials()) {
			int need = spell.map(Spell::cost).map(SpellCost::mana).orElse(0);
			if (need > mana && need <= max) {
				int nx = x + ManaBarLayout.fillWidth(need, max);
				float pulse = 0.55f + 0.45f * (float) Math.sin(System.currentTimeMillis() / 160.0);
				g.fill(nx - 1, y, nx, y1, UiKit.alpha(UiKit.WHITE, 0.85f * pulse));
			}
		}
		// Cooldown sweep: white over the part of the bar that is still recharging, shrinking to the left.
		if (sel != null && mc.level != null) {
			long[] cd = COOLDOWNS.get(sel);
			float now = mc.level.getGameTime() + dt.getGameTimeDeltaPartialTick(false);
			if (cd != null && cd[0] > now && cd[1] > 0) {
				float frac = Math.clamp((cd[0] - now) / cd[1], 0f, 1f);
				int sw = Math.round(ManaBarLayout.WIDTH * frac);
				if (sw > 0) {
					g.fill(x, y + 1, x + sw, y1 - 1, 0x70FFFFFF);
					g.fill(x + sw - 1, y, x + sw, y1, 0xE0FFFFFF);
				}
			}
		}
		if (readyFlash > 0) {
			float k = (readyFlash - dt.getGameTimeDeltaPartialTick(false)) / READY_FLASH_TICKS;
			g.fill(x, y, x1, y1, UiKit.alpha(UiKit.WHITE, 0.5f * Math.max(0f, k)));
		}

		// Mana number centred above, vanilla XP-number style in a pale element tint.
		Component num = Component.literal(Integer.toString(mana));
		int w = mc.font.width(num);
		Rect box = l.manaText(w);
		UiKit.outlined(g, mc.font, num, box.x() + 1, l.manaY(), UiKit.mix(colour, UiKit.WHITE, 0.4f), 0xFF000000);
	}

	/** The XP level at the bar's right end (vanilla green, outlined). */
	private static void xpLevel(GuiGraphicsExtractor g, DeltaTracker dt) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		ManaBarLayout.Layout l = ManaBarLayout.layout(g.guiWidth(), g.guiHeight());
		Component s = Component.literal(Integer.toString(mc.player.experienceLevel));
		boolean offhandRight = mc.player.getMainArm() == net.minecraft.world.entity.HumanoidArm.LEFT;
		UiKit.outlined(g, mc.font, s, l.xpX(mc.font.width(s), offhandRight), l.xpY(), XP_GREEN, 0xFF000000);
	}

	/** The selected spell's icon and name, fading in and out where vanilla shows the held item's name. */
	private static void spellLabel(GuiGraphicsExtractor g, DeltaTracker dt) {
		Minecraft mc = Minecraft.getInstance();
		Identifier id = label;
		if (id == null || mc.player == null) return;
		float t = labelTicks - dt.getGameTimeDeltaPartialTick(false);
		float age = LABEL_TICKS - t;
		float k = Math.min(UiKit.clamp01(age / FADE_IN), UiKit.clamp01(t / FADE_OUT));
		if (k <= 0.01f) return;
		Element e = MagicAttachments.get(mc.player).element().orElse(null);
		int colour = SpellRegistry.get(id).map(s -> s.element() == null ? Element.RECALL_ARGB : s.element().argb())
				.orElse(e != null && !id.getPath().equals("recall") ? e.argb() : Element.RECALL_ARGB);
		ManaBarLayout.Layout l = ManaBarLayout.layout(g.guiWidth(), g.guiHeight());
		Component name = UiKit.spellName(id);
		int tw = mc.font.width(name);
		int icon = 9;
		int total = icon + 3 + tw;
		int x = (g.guiWidth() - total) / 2;
		int y = l.labelY();
		float rise = (1f - UiKit.easeOut(age / FADE_IN)) * 3f;
		g.pose().pushMatrix();
		g.pose().translate(0, rise);
		UiKit.rounded(g, x - 4, y - 2, x + total + 4, y + 10, UiKit.alpha(0xFF000000, 0.45f * k));
		UiKit.glow(g, x + icon / 2f, y + 4f, 22f, UiKit.alpha(colour, 0.45f * k));
		UiKit.centredTexture(g, UiKit.spellIcon(id), 32, x + icon / 2f, y + 4f, icon, 0f, UiKit.alpha(UiKit.WHITE, k));
		g.text(mc.font, name, x + icon + 3, y, UiKit.alpha(UiKit.mix(colour, UiKit.WHITE, 0.35f), k), true);
		g.pose().popMatrix();
	}
}
