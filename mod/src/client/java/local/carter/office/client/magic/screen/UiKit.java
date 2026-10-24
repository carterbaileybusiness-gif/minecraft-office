package local.carter.office.client.magic.screen;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import local.carter.office.OfficeMod;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.tree.SpellRank;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

/** Drawing helpers shared by the magic screens and HUD: colour maths, easing, crisp scaled text, textures centred and tinted. */
public final class UiKit {
	public static final Identifier BOOK = OfficeMod.id("textures/gui/magic/book_open.png");
	public static final Identifier GLOW = OfficeMod.id("textures/gui/magic/glow.png");
	public static final Identifier PADLOCK = OfficeMod.id("textures/gui/magic/padlock.png");
	public static final Identifier RUNES = OfficeMod.id("textures/misc/magic/runes.png");
	public static final int WHITE = 0xFFFFFFFF;
	/** Dark-brown ink for text on parchment. */
	public static final int INK = 0xFF3B2A1A;
	/** Faded ink (secondary text on parchment). */
	public static final int INK_SOFT = 0xFF7A6248;

	private UiKit() {}

	public static Identifier sigil(Element e) {
		return OfficeMod.id("textures/gui/magic/sigil_" + e.key() + ".png");
	}

	public static Identifier spellIcon(Identifier spell) {
		return Identifier.fromNamespaceAndPath(spell.getNamespace(), "textures/gui/spell/" + spell.getPath() + ".png");
	}

	public static Identifier ring(int i) {
		return OfficeMod.id("textures/misc/magic/ring_" + i + ".png");
	}

	public static Component spellName(Identifier spell) {
		return Component.translatable("spell." + spell.getNamespace() + "." + spell.getPath());
	}

	/** The spell's name with its skill-tree rank in Roman numerals when it has one ("Fire Bolt III"), for the player of {@code d}. */
	public static Component rankedName(MagicData d, Identifier spell) {
		int rank = d.rank(spell).rank();
		Component name = spellName(spell);
		return rank == 0 ? name : name.copy().append(" " + SpellRank.roman(rank));
	}

	public static Component elementName(Element e) {
		return Component.translatable("element.office." + e.key());
	}

	/** The element colour darkened enough to read as ink on parchment (Wind and Lightning are pale). */
	public static int inkOf(int argb) {
		float lum = (0.299f * ((argb >> 16) & 0xFF) + 0.587f * ((argb >> 8) & 0xFF) + 0.114f * (argb & 0xFF)) / 255f;
		float t = Math.clamp((lum - 0.32f) / 0.6f, 0.15f, 0.62f);
		return mix(argb, 0xFF24160C, t);
	}

	// ---------------------------------------------------------------- colour

	/** WCAG relative luminance of {@code argb}'s colour (0 black .. 1 white). */
	public static double luminance(int argb) {
		double r = channel((argb >> 16) & 0xFF), g = channel((argb >> 8) & 0xFF), b = channel(argb & 0xFF);
		return 0.2126 * r + 0.7152 * g + 0.0722 * b;
	}

	private static double channel(int v) {
		double c = v / 255.0;
		return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
	}

	/** WCAG contrast ratio of two colours (1 .. 21). */
	public static double contrast(int a, int b) {
		double la = luminance(a), lb = luminance(b);
		return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
	}

	/** {@code fg} darkened (toward the book's ink) just enough to read at 4.5:1 or better on {@code bg}. */
	public static int readable(int fg, int bg) {
		int c = fg;
		for (int i = 1; i <= 20 && contrast(c, bg) < 4.6; i++) c = mix(fg, 0xFF24160C, i / 20f);
		return c;
	}

	/** {@code argb} darkened just enough for white text on it to read at 4.5:1 or better. */
	public static int deepen(int argb) {
		int c = argb;
		for (int i = 1; i <= 20 && contrast(WHITE, c) < 4.8; i++) c = mix(argb, 0xFF000000, i / 20f);
		return c;
	}

	/** {@code argb} with its alpha multiplied by {@code k} (0..1). */
	public static int alpha(int argb, float k) {
		int a = Math.round(((argb >>> 24) & 0xFF) * Math.clamp(k, 0f, 1f));
		return (a << 24) | (argb & 0xFFFFFF);
	}

	/** Linear mix of two ARGB colours ({@code t} = 0 → {@code a}, 1 → {@code b}). */
	public static int mix(int a, int b, float t) {
		t = Math.clamp(t, 0f, 1f);
		int out = 0;
		for (int shift = 0; shift <= 24; shift += 8) {
			int ca = (a >>> shift) & 0xFF;
			int cb = (b >>> shift) & 0xFF;
			out |= Math.round(ca + (cb - ca) * t) << shift;
		}
		return out;
	}

	// ---------------------------------------------------------------- easing

	public static float clamp01(float t) {
		return Math.clamp(t, 0f, 1f);
	}

	public static float easeOut(float t) {
		float u = 1f - clamp01(t);
		return 1f - u * u * u;
	}

	public static float easeInOut(float t) {
		t = clamp01(t);
		return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
	}

	public static float easeOutBack(float t) {
		t = clamp01(t);
		float c1 = 1.70158f;
		float c3 = c1 + 1f;
		float u = t - 1f;
		return 1f + c3 * u * u * u + c1 * u * u;
	}

	/** Moves {@code from} towards {@code to} exponentially ({@code rate} per second) over {@code dt} seconds. */
	public static float approach(float from, float to, float rate, float dt) {
		return to + (from - to) * (float) Math.exp(-rate * dt);
	}

	// ---------------------------------------------------------------- text

	/** The GUI scale (physical pixels per GUI unit). */
	public static int guiScale() {
		return Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
	}

	/**
	 * Snaps a text scale down so each font pixel covers a whole number of physical pixels (crisp glyphs), never below one
	 * physical pixel (smaller would be unreadable and blurry).
	 */
	public static float crisp(float scale) {
		int gs = guiScale();
		return Math.max(1f, (float) Math.floor(scale * gs + 1e-4f)) / gs;
	}

	/** The crisp scale (at most {@code max}) at which {@code width} units of text fit in {@code room}. */
	public static float fit(float width, float room, float max) {
		if (width <= 0) return crisp(max);
		return crisp(Math.min(max, room / width));
	}

	/** One line at {@code scale}, its left edge at {@code x}. */
	public static void text(GuiGraphicsExtractor g, Font font, FormattedCharSequence s, float x, float y, float scale, int argb, boolean shadow) {
		if (((argb >>> 24) & 0xFF) < 4) return;
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		g.text(font, s, 0, 0, argb, shadow);
		g.pose().popMatrix();
	}

	public static void text(GuiGraphicsExtractor g, Font font, Component s, float x, float y, float scale, int argb, boolean shadow) {
		text(g, font, s.getVisualOrderText(), x, y, scale, argb, shadow);
	}

	/** One line centred on {@code cx} at {@code scale}. */
	public static void centred(GuiGraphicsExtractor g, Font font, Component s, float cx, float y, float scale, int argb, boolean shadow) {
		text(g, font, s, cx - font.width(s) * scale / 2f, y, scale, argb, shadow);
	}

	public static void centred(GuiGraphicsExtractor g, Font font, FormattedCharSequence s, float cx, float y, float scale, int argb, boolean shadow) {
		text(g, font, s, cx - font.width(s) * scale / 2f, y, scale, argb, shadow);
	}

	/** Text with a 1-unit dark outline (vanilla's XP-level style). */
	public static void outlined(GuiGraphicsExtractor g, Font font, Component s, int x, int y, int argb, int outline) {
		g.text(font, s, x + 1, y, outline, false);
		g.text(font, s, x - 1, y, outline, false);
		g.text(font, s, x, y + 1, outline, false);
		g.text(font, s, x, y - 1, outline, false);
		g.text(font, s, x, y, argb, false);
	}

	private record WrapKey(Component text, int room, int max, int maxLines, int guiScale, String language) {}

	private record Wrapped(float scale, List<FormattedCharSequence> lines) {}

	/** Recent {@link #wrap} results: screens wrap the same few texts every frame. */
	private static final Map<WrapKey, Wrapped> WRAPS = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<WrapKey, Wrapped> eldest) {
			return size() > 96;
		}
	};

	/**
	 * Wraps {@code s} into at most {@code maxLines} lines that fit {@code room} wide, at the largest crisp scale up to
	 * {@code max}. Returns the scale; the lines go into {@code out}. Cached per (text, room, scale, lines, GUI scale, language).
	 */
	public static float wrap(Font font, Component s, float room, float max, int maxLines, List<FormattedCharSequence> out) {
		WrapKey key = new WrapKey(s, Math.round(room * 16), Math.round(max * 64), maxLines, guiScale(),
				Minecraft.getInstance().options.languageCode);
		Wrapped w = WRAPS.get(key);
		if (w == null) {
			List<FormattedCharSequence> lines = new java.util.ArrayList<>();
			float scale = wrapNow(font, s, room, max, maxLines, lines);
			w = new Wrapped(scale, List.copyOf(lines));
			WRAPS.put(key, w);
		}
		out.clear();
		out.addAll(w.lines());
		return w.scale();
	}

	/**
	 * Wraps {@code s} at exactly {@code scale} (it never shrinks: the Skill tree keeps text at the vanilla size or above) into at
	 * most {@code maxLines} lines {@code room} wide; a cut-off last line ends in "…". Returns how many lines went into {@code out}.
	 */
	public static int wrapLines(Font font, Component s, float room, float scale, int maxLines, List<FormattedCharSequence> out) {
		int width = Math.max(1, (int) Math.floor(room / scale));
		List<FormattedText> parts = font.splitIgnoringLanguage(s, width);
		out.clear();
		for (int i = 0; i < parts.size() && i < maxLines; i++) {
			FormattedText t = parts.get(i);
			if (i == maxLines - 1 && parts.size() > maxLines) {
				FormattedText dots = FormattedText.of("…");
				t = FormattedText.composite(font.substrByWidth(t, Math.max(0, width - font.width(dots))), dots);
			}
			out.add(Language.getInstance().getVisualOrder(t));
		}
		return out.size();
	}

	private static float wrapNow(Font font, Component s, float room, float max, int maxLines, List<FormattedCharSequence> out) {
		float scale = crisp(max);
		float step = 1f / guiScale();
		for (int guard = 0; guard < 64; guard++) {
			List<FormattedCharSequence> lines = font.split(s, Math.max(1, (int) Math.floor(room / scale)));
			int widest = 0;
			for (FormattedCharSequence l : lines) widest = Math.max(widest, font.width(l));
			if ((lines.size() <= maxLines && widest * scale <= room + 0.01f) || scale <= step * 1.01f) {
				out.clear();
				out.addAll(lines);
				return scale;
			}
			scale = crisp(scale - step);
		}
		out.clear();
		out.addAll(font.split(s, Math.max(1, (int) Math.floor(room / scale))));
		return scale;
	}

	/** Darkens towards every edge (strips, so it needs no texture); {@code k} fades it. */
	public static void vignette(GuiGraphicsExtractor g, int width, int height, float k) {
		int strips = 12;
		int ex = Math.max(8, width / 5), ey = Math.max(8, height / 4);
		for (int s = 0; s < strips; s++) {
			float t = 1f - s / (float) strips;
			int col = alpha(0x60000000, k * t * t);
			int sx = ex * s / strips, sx1 = ex * (s + 1) / strips;
			int sy = ey * s / strips, sy1 = ey * (s + 1) / strips;
			g.fill(sx, 0, sx1, height, col);
			g.fill(width - sx1, 0, width - sx, height, col);
			g.fill(0, sy, width, sy1, col);
			g.fill(0, height - sy1, width, height - sy, col);
		}
	}

	// ---------------------------------------------------------------- textures

	/** A square texture ({@code texSize}²) drawn {@code size} wide centred on ({@code cx}, {@code cy}), rotated, tinted. */
	public static void centredTexture(GuiGraphicsExtractor g, Identifier tex, int texSize, float cx, float cy, float size, float rotRad, int argb) {
		if (((argb >>> 24) & 0xFF) < 2 || size <= 0.5f) return;
		g.pose().pushMatrix();
		g.pose().translate(cx, cy);
		if (rotRad != 0f) g.pose().rotate(rotRad);
		g.pose().scale(size / texSize, size / texSize);
		g.blit(RenderPipelines.GUI_TEXTURED, tex, -texSize / 2, -texSize / 2, 0f, 0f, texSize, texSize, texSize, texSize, argb);
		g.pose().popMatrix();
	}

	/** A soft round glow ({@code glow.png} is white; {@code argb} tints it). */
	public static void glow(GuiGraphicsExtractor g, float cx, float cy, float size, int argb) {
		centredTexture(g, GLOW, 64, cx, cy, size, 0f, argb);
	}

	/** {@code tex} ({@code tw × th}) stretched over a float rect. */
	public static void stretch(GuiGraphicsExtractor g, Identifier tex, int tw, int th, float x, float y, float w, float h, int argb) {
		if (w <= 0.5f || h <= 0.5f) return;
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(w / tw, h / th);
		g.blit(RenderPipelines.GUI_TEXTURED, tex, 0, 0, 0f, 0f, tw, th, tw, th, argb);
		g.pose().popMatrix();
	}

	/** A filled rect with 1-unit cut corners. */
	public static void rounded(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int argb) {
		g.fill(x0 + 1, y0, x1 - 1, y1, argb);
		g.fill(x0, y0 + 1, x0 + 1, y1 - 1, argb);
		g.fill(x1 - 1, y0 + 1, x1, y1 - 1, argb);
	}

	/** A 1-unit outline with cut corners. */
	public static void roundedOutline(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int argb) {
		g.fill(x0 + 1, y0, x1 - 1, y0 + 1, argb);
		g.fill(x0 + 1, y1 - 1, x1 - 1, y1, argb);
		g.fill(x0, y0 + 1, x0 + 1, y1 - 1, argb);
		g.fill(x1 - 1, y0 + 1, x1, y1 - 1, argb);
	}
}
