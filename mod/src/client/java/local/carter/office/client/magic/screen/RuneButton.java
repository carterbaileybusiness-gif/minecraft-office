package local.carter.office.client.magic.screen;

import java.util.function.IntSupplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/**
 * A magic-styled button (a real {@link Button}, so tests and narration find it by its message): a dark rounded plate with a coloured rim (or a filled element-colour plate when {@code primary}),
 * lifting and glowing on hover or keyboard focus. Fades with {@link #fade}. Text stays crisp and shrinks to fit.
 */
public final class RuneButton extends Button {
	private final IntSupplier colour;
	private final boolean primary;
	/** 0..1 overall opacity (the screen animates it). */
	public float fade = 1f;
	private float hover;
	private long lastFrame = Util.getMillis();

	public RuneButton(int x, int y, int w, int h, Component message, boolean primary, IntSupplier colour, Runnable onPress) {
		super(x, y, w, h, message, b -> onPress.run(), DEFAULT_NARRATION);
		this.primary = primary;
		this.colour = colour;
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		long now = Util.getMillis();
		float dt = Math.min(0.1f, (now - lastFrame) / 1000f);
		lastFrame = now;
		hover = UiKit.approach(hover, isHoveredOrFocused() && active ? 1f : 0f, 14f, dt);
		float k = fade * alpha;
		if (k <= 0.01f) return;
		int c = colour.getAsInt();
		int x0 = getX(), y0 = getY() - Math.round(hover * Math.max(1, height / 14f)), x1 = x0 + width, y1 = y0 + height;
		if (hover > 0.02f) UiKit.glow(g, (x0 + x1) / 2f, (y0 + y1) / 2f, width * 1.3f, UiKit.alpha(c, 0.35f * hover * k));
		int plate = primary ? UiKit.mix(UiKit.mix(c, 0xFF000000, 0.45f), c, 0.25f * hover) : UiKit.mix(0xFF15121C, c, 0.12f * hover);
		UiKit.rounded(g, x0, y0 + 1, x1, y1 + 1, UiKit.alpha(0xFF000000, 0.5f * k)); // drop shadow
		UiKit.rounded(g, x0, y0, x1, y1, UiKit.alpha(plate, 0.94f * k));
		g.fillGradient(x0 + 1, y0 + 1, x1 - 1, y0 + Math.max(2, height / 2), UiKit.alpha(0xFFFFFFFF, (primary ? 0.16f : 0.07f) * k),
				UiKit.alpha(0xFFFFFFFF, 0f));
		int rim = primary ? UiKit.mix(c, 0xFFFFFFFF, 0.25f + 0.35f * hover) : UiKit.mix(0xFF5A5468, c, 0.3f + 0.6f * hover);
		UiKit.roundedOutline(g, x0, y0, x1, y1, UiKit.alpha(rim, k));
		var font = Minecraft.getInstance().font;
		float maxScale = Math.max(1f, (height - 4) / 12f);
		float s = UiKit.fit(font.width(getMessage()), width - 8, maxScale);
		int text = primary ? 0xFFFFFFFF : UiKit.mix(0xFFD8D2E0, 0xFFFFFFFF, hover);
		UiKit.centred(g, font, getMessage(), (x0 + x1) / 2f, (y0 + y1) / 2f - 4f * s + 0.5f, s, UiKit.alpha(text, k), true);
	}
}
