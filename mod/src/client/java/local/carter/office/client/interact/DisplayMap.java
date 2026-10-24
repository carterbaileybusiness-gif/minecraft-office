package local.carter.office.client.interact;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.platform.cursor.CursorTypes;

import local.carter.office.client.platform.Platform.Rect;
import local.carter.office.client.screens.DisplayMapModel;
import local.carter.office.client.screens.DisplayMapModel.Choice;
import local.carter.office.client.screens.MapLayout;
import local.carter.office.client.screens.Screen;
import local.carter.office.core.Mode;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * The settings screen's display map, like Windows Display Settings: every screen as a box placed and sized by its real
 * global position, scaled to fit. Real monitors are grey with a big letter (and their short name when there is room),
 * Office displays hologram cyan with their number, slots that don't exist yet dashed; the one this hologram shows has the
 * white frame. Clicks pick ({@link DisplayMapModel#click}); in Extend an Office display can be dragged and snaps on
 * release. The status line (bottom) says why something is greyed, or the platform's note.
 */
public final class DisplayMap extends AbstractWidget {
	private static final int PANEL_FILL = 0x90000000;
	private static final int PANEL_EDGE = 0xFF505050;
	private static final int MONITOR_FILL = 0xFF3A3A3A;
	private static final int MONITOR_EDGE = 0xFF8A8A8A;
	private static final int MONITOR_HOVER_EDGE = 0xFFC8C8C8;
	private static final int OFFICE_FILL = 0xFF16414B;
	private static final int OFFICE_EDGE = 0xFF7FE3F5;
	private static final int OFFICE_HOVER_EDGE = 0xFFC8F6FF;
	private static final int GHOST_FILL = 0x4016414B;
	private static final int SHOWN_FRAME = 0xFFFFFFFF;
	private static final int LABEL = 0xFFFFFFFF;
	private static final int NAME = 0xFFC0C0C0;
	private static final int NOTE = 0xFFA0A0A0;
	private static final int WARNING = 0xFFFFD060;
	private static final int INSET = 4;
	private static final int DRAG_THRESHOLD = 3;

	private final DisplayMapModel model;
	private final Consumer<Choice> onChoice;
	private final Runnable onChanged;
	private final @Nullable String note;

	// the press in progress
	private int lastMouseX;
	private int lastMouseY;
	private @Nullable Screen pressed;
	private double pressX;
	private double pressY;
	private boolean dragging;
	/** The fit while dragging (the map doesn't rescale under the mouse). */
	private MapLayout.@Nullable Fit frozen;

	/**
	 * @param onChoice a click picked something (send it)
	 * @param onChanged a drop changed what could be Applied
	 * @param note the platform's line, shown when the model has nothing to say
	 */
	public DisplayMap(final int x, final int y, final int w, final int h, final DisplayMapModel model, final Consumer<Choice> onChoice,
			final Runnable onChanged, final @Nullable String note) {
		super(x, y, w, h, Component.literal("Display map"));
		this.model = model;
		this.onChoice = onChoice;
		this.onChanged = onChanged;
		this.note = note;
	}

	public DisplayMapModel model() {
		return model;
	}

	// ---------------------------------------------------------------- geometry

	private static Font font() {
		return Minecraft.getInstance().font;
	}

	/** The status line's wrapped text (at most two lines) and whether it is a warning. */
	private List<FormattedCharSequence> statusLines() {
		String status = model.status();
		String text = status != null ? status : note;
		if (text == null || text.isEmpty()) {
			return List.of();
		}
		List<FormattedCharSequence> lines = font().split(Component.literal(text), width - 2 * INSET);
		return lines.size() > 2 ? lines.subList(0, 2) : lines;
	}

	/** The screens' part of the panel: {x, y, w, h} in screen coordinates. */
	private int[] area(final int statusLines) {
		int statusH = statusLines == 0 ? 0 : statusLines * 9 + 2;
		return new int[] {getX() + INSET, getY() + INSET, width - 2 * INSET, height - 2 * INSET - statusH};
	}

	private MapLayout.Fit fit(final int[] area) {
		MapLayout.Fit f = frozen;
		return f != null ? f : MapLayout.fit(model.fitRects(), area[2], area[3]);
	}

	private Rect boxOf(final Screen s, final MapLayout.Fit fit, final int[] area) {
		Rect m = fit.toMap(s.rect());
		return new Rect(area[0] + m.x(), area[1] + m.y(), m.w(), m.h());
	}

	/** Everything drawn, bottom to top: dashed slots, monitors, Office displays, the dragged one. */
	private List<Screen> drawOrder() {
		List<Screen> out = new ArrayList<>(model.ghosts());
		String drag = model.dragging();
		Screen dragged = null;
		for (Screen s : model.screens()) {
			if (s.isMonitor()) {
				out.add(s);
			}
		}
		for (Screen s : model.screens()) {
			if (!s.isMonitor()) {
				if (s.id().equals(drag)) {
					dragged = s;
				} else {
					out.add(s);
				}
			}
		}
		if (dragged != null) {
			out.add(dragged);
		}
		return out;
	}

	private @Nullable Screen screenAt(final double mx, final double my) {
		int[] area = area(statusLines().size());
		MapLayout.Fit fit = fit(area);
		List<Screen> order = drawOrder();
		for (int i = order.size() - 1; i >= 0; i--) {
			Rect b = boxOf(order.get(i), fit, area);
			if (mx >= b.x() && mx < b.x() + b.w() && my >= b.y() && my < b.y() + b.h()) {
				return order.get(i);
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- input

	@Override
	public void onClick(final MouseButtonEvent event, final boolean doubleClick) {
		pressed = screenAt(event.x(), event.y());
		pressX = event.x();
		pressY = event.y();
		dragging = false;
	}

	@Override
	protected void onDrag(final MouseButtonEvent event, final double dx, final double dy) {
		Screen p = pressed;
		if (p == null || !model.canDrag(p.id())) {
			return;
		}
		if (!dragging && Math.hypot(event.x() - pressX, event.y() - pressY) <= DRAG_THRESHOLD) {
			return;
		}
		if (!dragging) {
			dragging = true;
			frozen = fit(area(statusLines().size()));
		}
		MapLayout.Fit f = frozen;
		int gx = (int) Math.round(p.x() + (event.x() - pressX) / f.scale());
		int gy = (int) Math.round(p.y() + (event.y() - pressY) / f.scale());
		model.drag(p.id(), gx, gy);
	}

	@Override
	public void onRelease(final MouseButtonEvent event) {
		Screen p = pressed;
		pressed = null;
		if (dragging && p != null) {
			dragging = false;
			frozen = null;
			model.drop(p.id());
			onChanged.run();
			return;
		}
		dragging = false;
		frozen = null;
		if (p != null && p == screenAt(event.x(), event.y())) {
			Choice c = model.click(p.id());
			if (!(c instanceof Choice.None)) {
				onChoice.accept(c);
			}
		}
	}

	// ---------------------------------------------------------------- drawing

	@Override
	protected void extractWidgetRenderState(final GuiGraphicsExtractor g, final int mouseX, final int mouseY, final float a) {
		lastMouseX = mouseX;
		lastMouseY = mouseY;
		int x0 = getX(), y0 = getY(), x1 = getX() + width, y1 = getY() + height;
		g.fill(x0, y0, x1, y1, PANEL_FILL);
		frame(g, x0, y0, x1, y1, PANEL_EDGE);

		List<FormattedCharSequence> lines = statusLines();
		int[] area = area(lines.size());
		MapLayout.Fit fit = fit(area);
		Screen hover = isHovered() && !dragging ? screenAt(mouseX, mouseY) : null;
		String shown = model.shownId();
		g.enableScissor(area[0] - 1, area[1] - 1, area[0] + area[2] + 1, area[1] + area[3] + 1);
		Rect shownBox = null;
		for (Screen s : drawOrder()) {
			Rect b = boxOf(s, fit, area);
			drawBox(g, s, b, s == hover || s.id().equals(model.dragging()));
			if (s.id().equals(shown)) {
				shownBox = b;
			}
		}
		if (shownBox != null) {
			frame(g, shownBox.x() - 1, shownBox.y() - 1, shownBox.x() + shownBox.w(), shownBox.y() + shownBox.h(), SHOWN_FRAME);
		}
		g.disableScissor();

		int ty = y1 - INSET - lines.size() * 9;
		int color = model.status() != null ? WARNING : NOTE;
		for (FormattedCharSequence line : lines) {
			g.text(font(), line, x0 + (width - font().width(line)) / 2, ty, color, false);
			ty += 9;
		}
		if (hover != null) {
			g.setTooltipForNextFrame(tooltip(hover), mouseX, mouseY);
		}
	}

	/** A box one pixel short on the right and bottom, so touching screens keep a hairline gap between them. */
	private void drawBox(final GuiGraphicsExtractor g, final Screen s, final Rect b, final boolean hot) {
		int x0 = b.x(), y0 = b.y(), x1 = b.x() + b.w() - 1, y1 = b.y() + b.h() - 1;
		if (x1 - x0 < 2 || y1 - y0 < 2) {
			return;
		}
		boolean ghost = DisplayMapModel.isGhost(s);
		if (ghost) {
			g.fill(x0, y0, x1, y1, GHOST_FILL);
			dashed(g, x0, y0, x1, y1, hot ? OFFICE_HOVER_EDGE : OFFICE_EDGE);
		} else {
			boolean monitor = s.isMonitor();
			g.fill(x0, y0, x1, y1, monitor ? MONITOR_FILL : OFFICE_FILL);
			frame(g, x0, y0, x1, y1, monitor ? (hot ? MONITOR_HOVER_EDGE : MONITOR_EDGE) : (hot ? OFFICE_HOVER_EDGE : OFFICE_EDGE));
		}
		Font font = font();
		String label = model.label(s);
		int bw = x1 - x0, bh = y1 - y0;
		String name = s.isMonitor() ? s.name() : null;
		boolean withName = name != null && !name.isEmpty() && bw >= 40;
		float big = Math.min(3f, Math.min((bh - 3) / 9f * (withName ? 0.5f : 0.7f), (bw - 4) / (float) Math.max(1, font.width(label)) * 0.7f));
		if (withName && bh - big * 9 < 13) {
			withName = false;
			big = Math.min(3f, Math.min((bh - 3) / 9f * 0.7f, (bw - 4) / (float) Math.max(1, font.width(label)) * 0.7f));
		}
		if (big < 0.6f) {
			return; // too small for a label: the tooltip names it
		}
		float blockH = big * 9 + (withName ? 10 : 0);
		float top = y0 + (bh - blockH) / 2f + 1;
		g.pose().pushMatrix();
		g.pose().translate(x0 + bw / 2f, top);
		g.pose().scale(big, big);
		g.text(font, label, -font.width(label) / 2, 0, ghost ? OFFICE_EDGE : LABEL, true);
		g.pose().popMatrix();
		if (withName) {
			String n = fit(font, name, bw - 4);
			g.text(font, n, x0 + (bw - font.width(n)) / 2, (int) (top + big * 9 + 1), NAME, false);
		}
	}

	private static String fit(final Font font, final String s, final int maxWidth) {
		if (font.width(s) <= maxWidth) {
			return s;
		}
		String out = s;
		while (!out.isEmpty() && font.width(out + "…") > maxWidth) {
			out = out.substring(0, out.length() - 1);
		}
		return out + "…";
	}

	private List<FormattedCharSequence> tooltip(final Screen s) {
		String what;
		String how = null;
		boolean shown = s.id().equals(model.shownId());
		if (DisplayMapModel.isGhost(s)) {
			what = "Office display " + s.slot() + " (not on yet)";
			how = "Click to show it here";
		} else if (s.isMonitor()) {
			String n = s.name() == null || s.name().isEmpty() ? "" : " · " + s.name();
			what = model.label(s) + n + (s.main() ? " (main display)" : "");
			if (model.mode() == Mode.MIRROR && !(model.click(s.id()) instanceof Choice.None)) {
				how = "Click to mirror it";
			}
		} else {
			what = "Office display " + s.slot();
			if (model.mode() == Mode.EXTEND) {
				how = model.canDrag(s.id()) ? "Click to show it · drag to move it" : "Click to show it here";
			}
		}
		if (shown) {
			how = "Shown on this hologram";
		}
		List<FormattedCharSequence> out = new ArrayList<>();
		out.add(Component.literal(what).getVisualOrderText());
		if (how != null) {
			out.add(Component.literal(how).withStyle(ChatFormatting.GRAY).getVisualOrderText());
		}
		return out;
	}

	private static void frame(final GuiGraphicsExtractor g, final int x0, final int y0, final int x1, final int y1, final int color) {
		g.fill(x0, y0, x1, y0 + 1, color);
		g.fill(x0, y1 - 1, x1, y1, color);
		g.fill(x0, y0, x0 + 1, y1, color);
		g.fill(x1 - 1, y0, x1, y1, color);
	}

	private static void dashed(final GuiGraphicsExtractor g, final int x0, final int y0, final int x1, final int y1, final int color) {
		for (int x = x0; x < x1; x += 4) {
			g.fill(x, y0, Math.min(x + 2, x1), y0 + 1, color);
			g.fill(x, y1 - 1, Math.min(x + 2, x1), y1, color);
		}
		for (int y = y0; y < y1; y += 4) {
			g.fill(x0, y, x0 + 1, Math.min(y + 2, y1), color);
			g.fill(x1 - 1, y, x1, Math.min(y + 2, y1), color);
		}
	}

	@Override
	protected void handleCursor(final GuiGraphicsExtractor g) {
		if (dragging) {
			g.requestCursor(CursorTypes.RESIZE_ALL);
			return;
		}
		if (!isHovered()) {
			return;
		}
		Screen s = screenAt(lastMouseX, lastMouseY);
		if (s != null && model.canDrag(s.id())) {
			g.requestCursor(CursorTypes.RESIZE_ALL);
		} else if (s != null && !(model.click(s.id()) instanceof Choice.None)) {
			g.requestCursor(CursorTypes.POINTING_HAND);
		}
	}

	@Override
	protected void updateWidgetNarration(final NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, Component.literal("Display map"));
		String status = model.status();
		if (status != null) {
			output.add(NarratedElementType.HINT, Component.literal(status));
		}
	}
}
