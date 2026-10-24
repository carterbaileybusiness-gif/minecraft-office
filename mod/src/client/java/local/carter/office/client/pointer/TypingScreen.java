package local.carter.office.client.pointer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Open while the player types into an app on a hologram (typing mode). A screen is what releases Minecraft's mouse (so
 * the real cursor can move on that display) and keeps movement and looking off; it is not a pause screen and draws no
 * background, only a small "Typing — Esc to return" hint at the top. Esc normally never reaches Minecraft (the helper
 * swallows it); if Minecraft does get one, or a click lands here (the player clicked back into Minecraft), typing ends.
 */
public final class TypingScreen extends Screen {
	public static final String HINT = "Typing — Esc to return";

	TypingScreen() {
		super(Component.literal("Typing"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public void onClose() {
		PointerClient.typing().leave();
	}

	@Override
	public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		// no blur, no dimming: the office stays visible
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		Component text = Component.literal(PointerClient.typingHint());
		int tw = font.width(text);
		int x = (width - tw) / 2;
		int y = 10;
		graphics.fill(x - 6, y - 4, x + tw + 6, y + 12, 0xA0000000);
		graphics.text(font, text, x, y, 0xFFFFFFFF, false);
	}

	@Override
	public boolean keyPressed(final KeyEvent event) {
		if (event.isEscape()) {
			PointerClient.typing().leave();
		}
		return true; // game keys never act while typing
	}

	@Override
	public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
		PointerClient.typing().leave(); // back in Minecraft
		return true;
	}
}
