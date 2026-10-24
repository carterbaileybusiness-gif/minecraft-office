package local.carter.office.client.magic.screen;

import java.util.ArrayList;
import java.util.List;

import local.carter.office.client.magic.MagicKeys;
import local.carter.office.client.magic.screen.HowToLayout.Block;
import local.carter.office.client.magic.screen.HowToLayout.Glyph;
import local.carter.office.client.magic.screen.HowToLayout.Heading;
import local.carter.office.client.magic.screen.HowToLayout.Keys;
import local.carter.office.client.magic.screen.HowToLayout.Para;
import local.carter.office.client.magic.screen.HowToLayout.Placed;
import local.carter.office.client.magic.screen.HowToLayout.Step;
import local.carter.office.client.magic.screen.SignLayout.Rect;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.item.MagicItems;
import local.carter.office.magic.spell.SpellIds;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

/**
 * The How to cast tab: a short illustrated guide (the handbook's Casting page, step by step). Left page: five numbered steps,
 * each with a small picture: the Grimoire in the off-hand slot, the right mouse button, the growing cast circle, the spell
 * wheel, the mana bar. Right page: hand signs (the four arrow keycaps, "Esc gives up"), then where spells rank up (the Skill
 * tree tab's tree) and Recall's home mark. Set by {@link HowToLayout}: largest crisp text that fits; nothing scrolls.
 */
final class HowToPage {
	private static final Identifier OFFHAND_SLOT = Identifier.withDefaultNamespace("hud/hotbar_offhand_left");
	private static final Identifier RIGHT_CLICK = Identifier.withDefaultNamespace("toast/right_click");
	private static final int KEYCAP_FACE = 0xFFFBF3DC;
	private static final int KEYCAP_SIDE = 0xFFC9B48C;
	private static final int BADGE_TEXT = 0xFFFFFFFF;

	private static final List<Block> LEFT = List.of(
			new Heading("magic.office.howto_casting"),
			new Step("magic.office.howto_step_offhand", 1, Glyph.OFFHAND),
			new Step("magic.office.howto_step_tap", 2, Glyph.RIGHT_CLICK),
			new Step("magic.office.howto_step_hold", 3, Glyph.CHARGE),
			new Step("magic.office.howto_step_pick", 4, Glyph.WHEEL),
			new Step("magic.office.howto_step_mana", 5, Glyph.MANA));
	private static final List<Block> RIGHT = List.of(
			new Heading("magic.office.howto_signs_title"),
			new Keys(),
			new Para("magic.office.howto_signs", false),
			new Para("magic.office.signs_hint", true),
			new Heading("magic.office.howto_more"),
			new Step("magic.office.howto_tree", 0, Glyph.TREE),
			new Step("magic.office.howto_recall", 0, Glyph.RECALL));

	private final List<FormattedCharSequence> lines = new ArrayList<>();
	/** The last fit per page (it only changes with the page size, the GUI scale or the language). */
	private final Object[] fitKey = new Object[2];
	private final HowToLayout.Page[] fit = new HowToLayout.Page[2];

	void draw(GuiGraphicsExtractor g, Font font, GrimoireLayout.Layout l, Element e, long now, float k) {
		page(g, font, 0, LEFT, l.leftPage(), l.unit(), e, now, k);
		page(g, font, 1, RIGHT, l.rightPage(), l.unit(), e, now, k);
	}

	/** The text of a block's lang key, with the player's own keys filled in. */
	private static Component text(String key) {
		var options = Minecraft.getInstance().options;
		return switch (key) {
			case "magic.office.howto_step_offhand" -> Component.translatable(key, options.keySwapOffhand.getTranslatedKeyMessage());
			case "magic.office.howto_step_pick" -> Component.translatable(key, MagicKeys.WHEEL.getTranslatedKeyMessage());
			default -> Component.translatable(key);
		};
	}

	private static String key(Block b) {
		return switch (b) {
			case Heading h -> h.key();
			case Step s -> s.key();
			case Para p -> p.key();
			case Keys k -> "";
		};
	}

	private void page(GuiGraphicsExtractor g, Font font, int side, List<Block> blocks, Rect page, float unit, Element e, long now,
			float k) {
		HowToLayout.Lines counter = (b, room, scale) -> UiKit.wrapLines(font, text(key(b)), room, scale, 64, lines);
		Object key = List.of(page, unit, UiKit.guiScale(), Minecraft.getInstance().options.languageCode,
				Minecraft.getInstance().options.keySwapOffhand.saveString(), MagicKeys.WHEEL.saveString());
		if (!key.equals(fitKey[side])) {
			fitKey[side] = key;
			fit[side] = HowToLayout.place(blocks, page.w(), page.h(), unit, UiKit.guiScale(), counter);
		}
		HowToLayout.Page p = fit[side];
		float s = p.scale();
		int ink = UiKit.inkOf(e.argb());
		for (Placed pl : p.blocks()) {
			float y = page.y() + pl.y();
			switch (pl.block()) {
				case Heading h -> {
					UiKit.text(g, font, text(h.key()), page.x(), y, s, UiKit.alpha(ink, k), false);
					int ly = Math.round(y + 10 * s);
					g.fill(page.x(), ly, page.right(), ly + Math.max(1, Math.round(s * 0.5f)), UiKit.alpha(UiKit.INK_SOFT, 0.45f * k));
				}
				case Keys ks -> keycaps(g, page.x(), y, s, k);
				case Para para -> {
					UiKit.wrapLines(font, text(para.key()), page.w(), s, 64, lines);
					int col = para.soft() ? UiKit.INK_SOFT : UiKit.INK;
					float ty = y;
					for (FormattedCharSequence line : lines) {
						UiKit.text(g, font, line, page.x(), ty, s, UiKit.alpha(col, k), false);
						ty += 10 * s;
					}
				}
				case Step st -> {
					float gsz = HowToLayout.GLYPH * s;
					int n = UiKit.wrapLines(font, text(st.key()), HowToLayout.textRoom(st, page.w(), s), s, 64, lines);
					float th = HowToLayout.textHeight(n, s);
					glyph(g, st.glyph(), page.x(), y + (pl.h() - gsz) / 2f, gsz, e, now, k);
					if (st.number() > 0) badge(g, font, st.number(), page.x(), y + (pl.h() - gsz) / 2f, s, ink, k);
					float tx = page.x() + (HowToLayout.GLYPH + HowToLayout.GLYPH_GAP) * s;
					float ty = y + (pl.h() - th) / 2f;
					for (FormattedCharSequence line : lines) {
						UiKit.text(g, font, line, tx, ty, s, UiKit.alpha(UiKit.INK, k), false);
						ty += 10 * s;
					}
				}
			}
		}
	}

	/** The step number on a small disc in the element's ink, over the picture's top-left corner. */
	private static void badge(GuiGraphicsExtractor g, Font font, int number, float x, float y, float s, int ink, float k) {
		float r = 4.5f * s;
		float cx = x + r - s, cy = y + r - s;
		Disc.fill(g, cx, cy, r + Math.max(1f, s * 0.5f), UiKit.alpha(FriendsPage.paper(), k));
		Disc.fill(g, cx, cy, r, UiKit.alpha(ink, k));
		Component c = Component.literal(Integer.toString(number));
		UiKit.text(g, font, c, cx - (font.width(c) - 1) * s / 2f, cy - 3.5f * s, s, UiKit.alpha(BADGE_TEXT, k), false);
	}

	/** {@code glyph} in the {@code size}² square at ({@code x}, {@code y}). */
	private static void glyph(GuiGraphicsExtractor g, Glyph glyph, float x, float y, float size, Element e, long now, float k) {
		float cx = x + size / 2f, cy = y + size / 2f;
		int ink = UiKit.inkOf(e.argb());
		switch (glyph) {
			case OFFHAND -> {
				// The HUD's off-hand slot with the Grimoire in it (29 × 24; the slot is its left 22 × 22).
				float p = size / 24f;
				int sx = Math.round(x), sy = Math.round(y);
				g.blitSprite(RenderPipelines.GUI_TEXTURED, OFFHAND_SLOT, sx, sy, Math.round(29 * p), Math.round(24 * p),
						UiKit.alpha(UiKit.WHITE, k));
				if (k > 0.5f) {
					g.pose().pushMatrix();
					g.pose().translate(sx + 3 * p, sy + 4 * p);
					g.pose().scale(p, p);
					g.fakeItem(new ItemStack(MagicItems.GRIMOIRE), 0, 0);
					g.pose().popMatrix();
				}
			}
			case RIGHT_CLICK -> g.blitSprite(RenderPipelines.GUI_TEXTURED, RIGHT_CLICK, Math.round(x), Math.round(y), Math.round(size),
					Math.round(size), UiKit.alpha(UiKit.WHITE, k));
			case CHARGE -> {
				// The cast circle growing while held: rings swell outwards and fade, a faint rim where a full charge ends.
				float rMax = size * 0.48f;
				float th = Math.max(1f, size / 14f);
				Disc.ring(g, cx, cy, rMax, Math.max(1f, th * 0.6f), UiKit.alpha(UiKit.INK_SOFT, 0.35f * k));
				float t = (now % 1800) / 1800f;
				for (int i = 0; i < 3; i++) {
					float f = (t + i / 3f) % 1f;
					Disc.ring(g, cx, cy, Math.max(th + 1, rMax * (0.25f + 0.75f * f)), th, UiKit.alpha(e.argb(), (1f - f) * 0.9f * k));
				}
				Disc.fill(g, cx, cy, Math.max(1.5f, size * 0.09f), UiKit.alpha(ink, k));
			}
			case WHEEL -> {
				// The spell wheel: six slots round a ring, the lit one stepping round (picking a spell).
				float r = size * 0.36f;
				Disc.ring(g, cx, cy, r + Math.max(1f, size / 20f), Math.max(1f, size / 16f), UiKit.alpha(UiKit.INK_SOFT, 0.6f * k));
				int lit = (int) ((now / 650) % 6);
				for (int i = 0; i < 6; i++) {
					double a = -Math.PI / 2 + i * Math.PI / 3;
					float dx = (float) Math.cos(a) * r, dy = (float) Math.sin(a) * r;
					boolean on = i == lit;
					Disc.fill(g, cx + dx, cy + dy, size * (on ? 0.13f : 0.09f), UiKit.alpha(on ? e.argb() : UiKit.INK_SOFT, k));
				}
				Disc.fill(g, cx, cy, Math.max(1.5f, size * 0.07f), UiKit.alpha(ink, k));
			}
			case MANA -> {
				// The XP bar showing mana: the element's colour, filling and draining a little, with its notches.
				int bx0 = Math.round(x), bx1 = Math.round(x + size);
				int bh = Math.max(3, Math.round(size * 0.26f));
				int by0 = Math.round(cy - bh / 2f), by1 = by0 + bh;
				g.fill(bx0, by0, bx1, by1, UiKit.alpha(0xFF2A1E12, k));
				g.fill(bx0 + 1, by0 + 1, bx1 - 1, by1 - 1, UiKit.alpha(UiKit.mix(0xFF4A3A28, e.argb(), 0.15f), k));
				float level = 0.6f + 0.15f * (float) Math.sin(now / 900.0);
				int fw = Math.round((bx1 - bx0 - 2) * level);
				g.fill(bx0 + 1, by0 + 1, bx0 + 1 + fw, by1 - 1, UiKit.alpha(e.argb(), k));
				g.fill(bx0 + 1, by0 + 1, bx0 + 1 + fw, by0 + 2, UiKit.alpha(UiKit.mix(e.argb(), UiKit.WHITE, 0.45f), k));
				for (int i = 1; i < 4; i++) {
					int nx = bx0 + Math.round((bx1 - bx0) * i / 4f);
					g.fill(nx, by0 + 1, nx + 1, by1 - 1, UiKit.alpha(0x60000000, k));
				}
			}
			case TREE -> {
				// The Skill tree tab's own picture: a trunk with three nodes, the top one lit.
				float icon = size * 0.9f;
				float nr = Math.max(1.5f, icon * 0.13f);
				float top = cy - icon * 0.36f, bottom = cy + icon * 0.36f;
				int th = Math.max(1, Math.round(icon * 0.06f));
				g.fill(Math.round(cx) - th, Math.round(top), Math.round(cx) + th, Math.round(bottom), UiKit.alpha(UiKit.INK, 0.7f * k));
				Disc.fill(g, cx, bottom, nr, UiKit.alpha(UiKit.INK, k));
				Disc.fill(g, cx - icon * 0.22f, cy, nr * 0.85f, UiKit.alpha(UiKit.INK, k));
				Disc.fill(g, cx + icon * 0.22f, cy, nr * 0.85f, UiKit.alpha(UiKit.INK, k));
				g.fill(Math.round(cx - icon * 0.22f), Math.round(cy) - th / 2 - 1, Math.round(cx + icon * 0.22f), Math.round(cy) + th / 2,
						UiKit.alpha(UiKit.INK, 0.7f * k));
				Disc.fill(g, cx, top, nr, UiKit.alpha(e.argb(), k));
			}
			case RECALL -> UiKit.centredTexture(g, UiKit.spellIcon(SpellIds.RECALL), 32, cx, cy, size, 0f, UiKit.alpha(UiKit.WHITE, k));
		}
	}

	/** ↑ ↓ ← → as keycaps, left to right from {@code x}. */
	private static void keycaps(GuiGraphicsExtractor g, float x, float y, float s, float k) {
		int cap = Math.round(HowToLayout.KEYCAP * s);
		int gap = Math.max(1, Math.round(3 * s));
		int depth = Math.max(1, Math.round(1.5f * s));
		int[][] dirs = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
		int x0 = Math.round(x), y0 = Math.round(y);
		for (int[] d : dirs) {
			UiKit.rounded(g, x0, y0, x0 + cap, y0 + cap, UiKit.alpha(UiKit.INK, k));
			UiKit.rounded(g, x0 + 1, y0 + 1, x0 + cap - 1, y0 + cap - 1, UiKit.alpha(KEYCAP_SIDE, k));
			UiKit.rounded(g, x0 + 1, y0 + 1, x0 + cap - 1, y0 + cap - 1 - depth, UiKit.alpha(KEYCAP_FACE, k));
			arrow(g, x0 + cap / 2f, y0 + (cap - depth) / 2f, cap * 0.32f, d[0], d[1], UiKit.alpha(UiKit.INK, k));
			x0 += cap + gap;
		}
	}

	/** A small solid arrow centred on ({@code cx}, {@code cy}), {@code r} from centre to tip, pointing ({@code dx}, {@code dy}). */
	private static void arrow(GuiGraphicsExtractor g, float cx, float cy, float r, int dx, int dy, int argb) {
		int n = Math.max(2, Math.round(r));
		// Head: rows (or columns) narrowing towards the tip; shaft: a thin bar behind it.
		for (int i = 0; i < n; i++) {
			int half = n - 1 - i;
			int along = Math.round(-n * 0.1f) + i;
			if (dy != 0) {
				int yy = Math.round(cy + dy * along);
				g.fill(Math.round(cx) - half - 1, yy, Math.round(cx) + half, yy + 1, argb);
			} else {
				int xx = Math.round(cx + dx * along);
				g.fill(xx, Math.round(cy) - half - 1, xx + 1, Math.round(cy) + half, argb);
			}
		}
		int sw = Math.max(1, n / 3);
		if (dy != 0) {
			int a = Math.round(cy - dy * n * 0.9f), b = Math.round(cy);
			g.fill(Math.round(cx) - sw / 2 - 1 + (sw % 2 == 0 ? 1 : 0), Math.min(a, b), Math.round(cx) + sw / 2, Math.max(a, b), argb);
		} else {
			int a = Math.round(cx - dx * n * 0.9f), b = Math.round(cx);
			g.fill(Math.min(a, b), Math.round(cy) - sw / 2 - 1 + (sw % 2 == 0 ? 1 : 0), Math.max(a, b), Math.round(cy) + sw / 2, argb);
		}
	}
}
