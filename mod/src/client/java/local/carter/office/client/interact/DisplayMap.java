package local.carter.office.client.interact;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.platform.cursor.CursorTypes;

import local.carter.office.client.platform.Platform;
import local.carter.office.client.platform.Platform.Rect;
import local.carter.office.client.screens.DisplayMapModel;
import local.carter.office.client.screens.IdentifyClient;
import local.carter.office.client.screens.IdentifyLabels;
import local.carter.office.client.screens.ScreenList;
import local.carter.office.client.screens.DisplayMapModel.Choice;
import local.carter.office.client.screens.MapLayout;
import local.carter.office.client.screens.MapPress;
import local.carter.office.client.screens.Screen;
import local.carter.office.core.Mode;
import local.carter.office.hologram.HologramBlockEntity;
import local.carter.office.net.OfficePayloads;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * The settings screen's display map, like Windows Display Settings: every screen as a box placed and sized by its real
 * global position, one scale for both axes, the whole arrangement centred in the panel ({@link MapLayout}). Every box
 * carries its big letter or number; real monitors are grey with their short name under it when there is room, the main
 * display has a white bar along its top (like a menu bar), Office displays are hologram cyan, slots that don't exist yet
 * are dashed "+3". The one this hologram shows has a white frame and a soft glow; the box under the mouse lights up; a
 * display moved but not Applied leaves a dotted outline where it really is. Clicks pick ({@link DisplayMapModel#click});
 * in Extend an Office display can be dragged and snaps on release. The status line (bottom) says why something is greyed,
 * or the platform's note.
 */
public final class DisplayMap extends AbstractWidget {
	private static final int PANEL_FILL = 0xA0101216;
	private static final int PANEL_EDGE = 0xFF505050;
	private static final int MONITOR_FILL = 0xFF3C3F44;
	private static final int MONITOR_HOVER_FILL = 0xFF5A5E65;
	private static final int MONITOR_EDGE = 0xFF8A8E94;
	private static final int MONITOR_HOVER_EDGE = 0xFFD0D0D0;
	private static final int OFFICE_FILL = 0xFF16414B;
	private static final int OFFICE_HOVER_FILL = 0xFF236272;
	private static final int OFFICE_EDGE = 0xFF7FE3F5;
	private static final int OFFICE_HOVER_EDGE = 0xFFC8F6FF;
	private static final int GHOST_FILL = 0x3016414B;
	private static final int GHOST_HOVER_FILL = 0x70236272;
	private static final int MOVED_FROM_FILL = 0x1A7FE3F5;
	private static final int MOVED_FROM_EDGE = 0xB07FE3F5;
	private static final int MAIN_BAR = 0xFFE6E6E6;
	private static final int SHOWN_FRAME = 0xFFFFFFFF;
	/** The chosen box's glow, ring by ring outwards. */
	private static final int[] SHOWN_GLOW = {0x60FFFFFF, 0x30FFFFFF, 0x14FFFFFF};
	private static final int LABEL = 0xFFFFFFFF;
	private static final int NAME = 0xFFC8C8C8;
	private static final int NOTE = 0xFFA0A0A0;
	private static final int WARNING = 0xFFFFD060;
	/** The largest letter (text scale). */
	private static final float MAX_LABEL_SCALE = 4f;

	private final DisplayMapModel model;
	private final Consumer<Choice> onChoice;
	private final Runnable onChanged;
	private final @Nullable String note;

	private int lastMouseX;
	private int lastMouseY;
	/** The press in progress (pure, so the click rules are unit-tested). */
	private final MapPress press;

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
		this.press = new MapPress(model);
	}

	public DisplayMapModel model() {
		return model;
	}

	// ---------------------------------------------------------------- geometry

	private static Font font() {
		return Minecraft.getInstance().font;
	}

	/** How many lines (0-2) the status line of a {@code width}-wide map takes now: the model's status, else {@code note}. */
	public static int statusLineCount(final DisplayMapModel model, final @Nullable String note, final int width) {
		return statusLines(model, note, width).size();
	}

	private static List<FormattedCharSequence> statusLines(final DisplayMapModel model, final @Nullable String note, final int width) {
		String status = model.status();
		String text = status != null ? status : note;
		if (text == null || text.isEmpty()) {
			return List.of();
		}
		List<FormattedCharSequence> lines = font().split(Component.literal(text), width - 2 * MapLayout.STATUS_INSET);
		return lines.size() > 2 ? lines.subList(0, 2) : lines;
	}

	/** The status line's wrapped text (at most two lines). */
	private List<FormattedCharSequence> statusLines() {
		return statusLines(model, note, width);
	}

	/** The screens' part of the panel, in screen coordinates. */
	private Rect area(final int statusLines) {
		return MapLayout.area(getX(), getY(), width, height, statusLines);
	}

	private MapLayout.Fit fit(final Rect area) {
		MapLayout.Fit f = press.frozen();
		return f != null ? f : MapLayout.fit(model.fitRects(), area.w(), area.h());
	}

	private Rect boxOf(final Screen s, final MapLayout.Fit fit, final Rect area) {
		return MapPress.box(s, fit, area.x(), area.y());
	}

	private @Nullable Screen screenAt(final double mx, final double my) {
		Rect area = area(statusLines().size());
		return MapPress.hit(model.drawOrder(), fit(area), area.x(), area.y(), mx, my);
	}

	/** Where screen {@code id} (or dashed slot) is drawn now, in screen coordinates, or null (client GameTests). */
	public @Nullable Rect boxOnScreen(final String id) {
		Rect area = area(statusLines().size());
		MapLayout.Fit fit = fit(area);
		for (Screen s : model.drawOrder()) {
			if (s.id().equals(id)) {
				return boxOf(s, fit, area);
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- input

	@Override
	public void onClick(final MouseButtonEvent event, final boolean doubleClick) {
		press.press(screenAt(event.x(), event.y()), event.x(), event.y());
	}

	@Override
	protected void onDrag(final MouseButtonEvent event, final double dx, final double dy) {
		press.drag(event.x(), event.y(), () -> fit(area(statusLines().size())));
	}

	@Override
	public void onRelease(final MouseButtonEvent event) {
		MapPress.Release r = press.release(screenAt(event.x(), event.y()));
		if (r.dropped()) {
			onChanged.run();
		} else if (!(r.choice() instanceof Choice.None)) {
			onChoice.accept(r.choice());
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
		Rect area = area(lines.size());
		MapLayout.Fit fit = fit(area);
		Screen hover = isHovered() && !press.dragging() ? screenAt(mouseX, mouseY) : null;
		String shown = model.shownId();
		// The margin round the arrangement is the glow's; the status line stays clear.
		g.enableScissor(x0 + 1, y0 + 1, x1 - 1, area.y() + area.h() + MapLayout.MARGIN - 1);
		for (Screen s : model.movedFrom()) {
			movedFrom(g, boxOf(s, fit, area));
		}
		List<Screen> order = model.drawOrder();
		Rect shownBox = null;
		for (Screen s : order) {
			if (s.id().equals(shown)) {
				shownBox = boxOf(s, fit, area);
			}
		}
		if (shownBox != null) {
			glow(g, shownBox);
		}
		for (Screen s : order) {
			drawBox(g, s, boxOf(s, fit, area), MapPress.same(s, hover) || s.id().equals(model.dragging()));
		}
		if (shownBox != null) {
			frame(g, shownBox.x() - 1, shownBox.y() - 1, shownBox.x() + shownBox.w(), shownBox.y() + shownBox.h(), SHOWN_FRAME);
		}
		g.disableScissor();

		int ty = y1 - MapLayout.STATUS_INSET - lines.size() * MapLayout.STATUS_LINE;
		int color = model.status() != null ? WARNING : NOTE;
		for (FormattedCharSequence line : lines) {
			g.text(font(), line, x0 + (width - font().width(line)) / 2, ty, color, false);
			ty += MapLayout.STATUS_LINE;
		}
		if (hover != null) {
			g.setTooltipForNextFrame(tooltip(hover), mouseX, mouseY);
		}
	}

	/** The chosen box's soft glow: fading rings just outside its white frame (drawn under the boxes). */
	private static void glow(final GuiGraphicsExtractor g, final Rect b) {
		for (int i = 0; i < SHOWN_GLOW.length; i++) {
			int d = i + 2;
			frame(g, b.x() - d, b.y() - d, b.x() + b.w() - 1 + d, b.y() + b.h() - 1 + d, SHOWN_GLOW[i]);
		}
	}

	/** Where a display moved but not Applied really is: a faint dotted outline. */
	private static void movedFrom(final GuiGraphicsExtractor g, final Rect b) {
		int x0 = b.x(), y0 = b.y(), x1 = b.x() + b.w() - 1, y1 = b.y() + b.h() - 1;
		if (x1 - x0 < 2 || y1 - y0 < 2) {
			return;
		}
		g.fill(x0, y0, x1, y1, MOVED_FROM_FILL);
		broken(g, x0, y0, x1, y1, MOVED_FROM_EDGE, 1, 3);
	}

	/** A box one pixel short on the right and bottom, so touching screens keep a hairline gap between them. */
	private void drawBox(final GuiGraphicsExtractor g, final Screen s, final Rect b, final boolean hot) {
		int x0 = b.x(), y0 = b.y(), x1 = b.x() + b.w() - 1, y1 = b.y() + b.h() - 1;
		if (x1 - x0 < 2 || y1 - y0 < 2) {
			return;
		}
		boolean ghost = DisplayMapModel.isGhost(s);
		if (ghost) {
			g.fill(x0, y0, x1, y1, hot ? GHOST_HOVER_FILL : GHOST_FILL);
			broken(g, x0, y0, x1, y1, hot ? OFFICE_HOVER_EDGE : OFFICE_EDGE, 2, 4);
		} else if (s.isMonitor()) {
			g.fill(x0, y0, x1, y1, hot ? MONITOR_HOVER_FILL : MONITOR_FILL);
			frame(g, x0, y0, x1, y1, hot ? MONITOR_HOVER_EDGE : MONITOR_EDGE);
		} else {
			g.fill(x0, y0, x1, y1, hot ? OFFICE_HOVER_FILL : OFFICE_FILL);
			frame(g, x0, y0, x1, y1, hot ? OFFICE_HOVER_EDGE : OFFICE_EDGE);
		}
		int bar = 0;
		if (s.main() && !ghost) {
			// The main display's mark: a bar along its top, like the menu bar only the main display has.
			bar = Math.clamp(Math.round((y1 - y0) * 0.1f), 1, 3);
			g.fill(x0 + 1, y0 + 1, x1 - 1, y0 + 1 + bar, MAIN_BAR);
		}
		label(g, s, x0 + 1, y0 + 1 + bar, x1 - 1, y1 - 1, ghost);
	}

	/**
	 * The box's big letter or number (a dashed slot's "+3") centred in the box's inside ({@code x0..x1}, {@code y0..y1}),
	 * and a real monitor's short name under it when the box is tall and wide enough. Crisp text scales where they fit.
	 */
	private void label(final GuiGraphicsExtractor g, final Screen s, final int x0, final int y0, final int x1, final int y1,
			final boolean ghost) {
		Font font = font();
		int bw = x1 - x0, bh = y1 - y0;
		String label = model.label(s);
		if (ghost) {
			String plus = "+" + label;
			int pw = Math.max(1, font.width(plus) - 1);
			if (labelScale(bw, bh, pw) >= 1f) {
				label = plus; // "+3" where it is still a readable size; else just "3"
			}
		}
		int lw = Math.max(1, font.width(label) - 1); // the trailing pixel is spacing
		float big = labelScale(bw, bh, lw);
		if (big <= 0) {
			return; // too small for anything: the tooltip names it
		}
		String name = s.isMonitor() && s.name() != null && !s.name().isEmpty() ? s.name() : null;
		String shownName = null;
		float small = small();
		if (name != null) {
			float withName = crisp(Math.min(big, (bh - 8 * small - 4) * 0.9f / 8f));
			String fitted = trim(font, name, (bw - 3) / small);
			if (withName >= 1f && 8 * withName + 2 + 8 * small <= bh - 1 && fitted != null) {
				big = withName;
				shownName = fitted;
			}
		}
		float blockH = 8 * big + (shownName != null ? 2 + 8 * small : 0);
		float top = y0 + (bh - blockH) / 2f;
		g.pose().pushMatrix();
		g.pose().translate(x0 + (bw - lw * big) / 2f, top);
		g.pose().scale(big, big);
		g.text(font, label, 0, 0, ghost ? OFFICE_EDGE : LABEL, true);
		g.pose().popMatrix();
		if (shownName != null) {
			float nw = Math.max(1, font.width(shownName) - 1) * small;
			g.pose().pushMatrix();
			g.pose().translate(x0 + (bw - nw) / 2f, top + 8 * big + 2);
			g.pose().scale(small, small);
			g.text(font, shownName, 0, 0, NAME, false);
			g.pose().popMatrix();
		}
	}

	/**
	 * The letter's scale in a {@code bw} × {@code bh} box for text {@code textW} wide: as large as looks right (up to
	 * 80 % of the box's height and 75 % of its width), crisp; a box too small for that gets a smaller scale that still
	 * fits; 0 when even that would be unreadable.
	 */
	private static float labelScale(final int bw, final int bh, final int textW) {
		float want = Math.min(MAX_LABEL_SCALE, Math.min(bh * 0.8f / 8f, bw * 0.75f / textW));
		float c = crisp(want);
		if (8 * c <= bh - 1 && textW * c <= bw - 1) {
			return c;
		}
		float fits = Math.min((bh - 1) / 8f, (bw - 1) / (float) textW);
		return fits * guiScale() >= 0.75f ? fits : 0;
	}

	/** The small scale of a monitor's name: half size where that is still whole physical pixels, else the vanilla size. */
	private static float small() {
		int gs = guiScale();
		return (float) Math.ceil(gs * 0.5) / gs;
	}

	/** {@code scale} snapped down to whole physical pixels per font pixel (crisp), at least one physical pixel. */
	private static float crisp(final float scale) {
		int gs = guiScale();
		return Math.max(1f, (float) Math.floor(scale * gs + 1e-4f)) / gs;
	}

	private static int guiScale() {
		return Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
	}

	/** {@code s} cut to {@code maxWidth} with "…", or null when fewer than three letters would be left. */
	private static @Nullable String trim(final Font font, final String s, final float maxWidth) {
		if (font.width(s) <= maxWidth) {
			return s;
		}
		String out = s;
		while (!out.isEmpty() && font.width(out + "…") > maxWidth) {
			out = out.substring(0, out.length() - 1);
		}
		return out.length() >= 3 ? out + "…" : null;
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

	/** A broken outline: {@code on} pixels drawn, then a gap, every {@code period} pixels. */
	private static void broken(final GuiGraphicsExtractor g, final int x0, final int y0, final int x1, final int y1, final int color,
			final int on, final int period) {
		for (int x = x0; x < x1; x += period) {
			g.fill(x, y0, Math.min(x + on, x1), y0 + 1, color);
			g.fill(x, y1 - 1, Math.min(x + on, x1), y1, color);
		}
		for (int y = y0; y < y1; y += period) {
			g.fill(x0, y, x0 + 1, Math.min(y + on, y1), color);
			g.fill(x1 - 1, y, x1, Math.min(y + on, y1), color);
		}
	}

	@Override
	protected void handleCursor(final GuiGraphicsExtractor g) {
		if (press.dragging()) {
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

	// ---------------------------------------------------------------- Identify

	/**
	 * Whether Identify can run: everywhere it can number the holograms, except a Mac whose helper can't show the
	 * overlays on the real screens yet (greyed with the helper hint, so the real screens are never left out silently).
	 */
	public static boolean canIdentify(final ScreenList list) {
		return list.abilities().canIdentifyScreens() || !Platform.isMac();
	}

	/**
	 * Identify for 3 s: this client draws its own holograms' labels, the server shows them to the players who may see
	 * those holograms (never a monitor name), and the helper puts the same labels on the real screens.
	 */
	public static void identify(final ScreenList list) {
		Minecraft mc = Minecraft.getInstance();
		List<HologramBlockEntity> mine = new ArrayList<>();
		for (HologramBlockEntity be : HologramTracker.loaded()) {
			if (mc.player != null && be.isOwner(mc.player.getUUID())) {
				mine.add(be);
			}
		}
		Map<BlockPos, String> labels = IdentifyLabels.forHolograms(mine, list);
		IdentifyClient.get().show(labels, Util.getMillis());
		if (!labels.isEmpty() && ClientPlayNetworking.canSend(OfficePayloads.IdentifyHolograms.TYPE)) {
			List<OfficePayloads.HologramLabel> out = new ArrayList<>();
			for (Map.Entry<BlockPos, String> e : labels.entrySet()) {
				if (out.size() < OfficePayloads.MAX_IDENTIFY) {
					out.add(new OfficePayloads.HologramLabel(e.getKey(), e.getValue()));
				}
			}
			ClientPlayNetworking.send(new OfficePayloads.IdentifyHolograms(out));
		}
		if (list.abilities().canIdentifyScreens()) {
			Platform.current().identifyScreens(IdentifyLabels.forScreens(list));
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
