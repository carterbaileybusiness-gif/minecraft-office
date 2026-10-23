package local.carter.office.client.zoom;

import java.util.Optional;

import com.mojang.blaze3d.platform.Window;

import local.carter.office.client.render.ScreenTexture;
import local.carter.office.client.render.ScreenTextureRegistry;
import local.carter.office.client.zoom.ZoomController.Candidate;
import local.carter.office.client.zoom.ZoomController.State;
import local.carter.office.client.zoom.ZoomPose.Rect;
import local.carter.office.core.Mode;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * Open from the moment a zoom starts until it is back in the world. A screen is what releases the mouse (so the helper
 * can move the real cursor onto the virtual display) and keeps game input away from the player; it is not a pause
 * screen and draws no background, so the zoom animation stays visible.
 *
 * <p>While WORKING on an EXTEND hologram it draws the hologram's local capture (30 fps while zoomed) over the whole
 * window, letterboxed to the shape exactly where the zoomed hologram is, with the bars fading to near-black. In WINDOW
 * mode the real window sits on top of Minecraft, so nothing is drawn. Esc: two presses within 350 ms leave, a single
 * Esc does nothing here (never the pause menu); Ctrl+Alt+Cmd+Esc (the fallback) leaves at once.
 */
final class WorkScreen extends Screen {
	/** SDL modifier bits: shift 0x3, ctrl 0xC0, alt 0x300, gui (Cmd/Win) 0xC00. */
	private static final int SHIFT = 0x3;
	private static final int CTRL = 0xC0;
	private static final int ALT = 0x300;
	private static final int GUI = 0xC00;
	static final long BAR_FADE_MS = 200;
	private static final long HINT_MS = 2_500;

	private final ZoomController controller;

	WorkScreen(final ZoomController controller) {
		super(Component.literal("Working"));
		this.controller = controller;
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
		// never closes itself: the zoom controller closes it when back in the world
	}

	@Override
	public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		// no blur, no dimming: the world (and the zooming camera) stays visible
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		Candidate t = controller.target();
		long working = ZoomClient.workingForMs();
		// MIRROR and WINDOW draw nothing: Minecraft has stepped aside / the real window is on top.
		if (t == null || controller.state() != State.WORKING || working < 0 || t.spec().mode() != Mode.EXTEND) {
			ZoomClient.overlayDrawn(false);
			return;
		}
		Optional<ScreenTexture> tex = ScreenTextureRegistry.get(t.hologramId());
		if (tex.isEmpty() || !tex.get().prepareForRender()) {
			ZoomClient.overlayDrawn(false);
			return; // nothing uploaded yet: the zoomed hologram itself is showing
		}
		Window w = minecraft.getWindow();
		int fw = w.getWidth();
		int fh = w.getHeight();
		float scale = Math.max(1, w.getGuiScale());
		Rect r = ZoomPose.letterbox(fw, fh, t.spec().shape().aspect());
		// Bars fade to opaque black; once they have, the overlay covers the window and the world isn't rendered.
		int barAlpha = (int) (0xFF * Math.min(1.0, working / (double) BAR_FADE_MS));
		int bar = barAlpha << 24;

		graphics.pose().pushMatrix();
		graphics.pose().scale(1f / scale, 1f / scale); // draw in framebuffer pixels for an exact 1:1 letterbox
		if (barAlpha > 0) {
			if (r.y() > 0) {
				graphics.fill(0, 0, fw, r.y(), bar);
				graphics.fill(0, r.y() + r.h(), fw, fh, bar);
			}
			if (r.x() > 0) {
				graphics.fill(0, r.y(), r.x(), r.y() + r.h(), bar);
				graphics.fill(r.x() + r.w(), r.y(), fw, r.y() + r.h(), bar);
			}
		}
		graphics.blit(tex.get().id(), r.x(), r.y(), r.x() + r.w(), r.y() + r.h(), 0f, 1f, 0f, 1f);
		graphics.pose().popMatrix();
		ZoomClient.overlayDrawn(true);

		int y = height - 28;
		if (ZoomClient.needsClickToType()) {
			// The helper moved the cursor onto the display but nothing there has focus yet: keys still reach
			// Minecraft (and are swallowed here) until the player clicks into an app.
			hint(graphics, Component.literal("Click to start typing"), y, 1f);
			y -= 18;
		}
		if (working < HINT_MS) {
			hint(graphics, Component.literal("Double-tap Esc to return to the office"), y,
					(float) Math.min(1.0, (HINT_MS - working) / 600.0));
		}
	}

	private void hint(final GuiGraphicsExtractor graphics, final Component text, final int y, final float fade) {
		int alpha = Math.max(4, (int) (0xFF * fade));
		int tw = font.width(text);
		int x = (width - tw) / 2;
		graphics.fill(x - 6, y - 4, x + tw + 6, y + 12, ((int) (0xA0 * fade) << 24));
		graphics.text(font, text, x, y, (alpha << 24) | 0xFFFFFF, false);
	}

	@Override
	public boolean keyPressed(final KeyEvent event) {
		if (event.isEscape()) {
			int mods = event.modifiers();
			if ((mods & CTRL) != 0 && (mods & ALT) != 0 && (mods & GUI) != 0) {
				controller.exit(); // fallback chord
				return true;
			}
			controller.escape((mods & (SHIFT | CTRL | ALT | GUI)) != 0);
			return true;
		}
		controller.otherKey();
		return true; // game keys never act while zoomed
	}

	@Override
	public boolean keyReleased(final KeyEvent event) {
		if (event.isEscape()) {
			controller.escapeReleased();
		}
		return true;
	}

	@Override
	public void removed() {
		controller.escapeReleased();
		ZoomClient.overlayDrawn(false); // the world renders again at once
	}
}
