package local.carter.office.client.dock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.InputConstants;

import local.carter.office.client.pointer.PointerPort;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The Dock's "All apps" (spec 5.10): type part of a name, pick an app, and it opens on the hologram you clicked. A
 * vanilla-styled list in pages of {@link AppSearch#PAGE_SIZE} (no scrolling), with the real app icons, in the spirit of
 * Carter's Home Base search book (Mac UI Makeover, MinecraftHome SearchScreen.java: prefix-first ranking, Enter opens
 * the first). Not a pause screen.
 */
public final class AppSearchScreen extends Screen {
	private static final int ROW = 20;
	/** Title 12 + search box 22 + the rows + 2 + the page buttons 20: fits a 240-high GUI. */
	private static final int HEIGHT = 12 + 22 + AppSearch.PAGE_SIZE * ROW + 2 + 20;
	private static final int WIDTH = 240;

	private final PointerPort port;
	private final int slot;
	private EditBox box;
	private String query = "";
	private int page;
	private List<AppSearch.App> results = List.of();
	private final List<Button> rows = new ArrayList<>();
	private Button prev;
	private Button next;
	private int left;
	private int top;

	public AppSearchScreen(final PointerPort port, final int slot) {
		super(Component.literal("All apps"));
		this.port = port;
		this.slot = slot;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected void init() {
		left = (width - WIDTH) / 2;
		top = Math.max(2, (height - HEIGHT) / 2);
		box = new EditBox(font, left, top + 12, WIDTH, 18, Component.literal("Search apps"));
		box.setHint(Component.literal("Search apps…"));
		box.setValue(query);
		box.setResponder(text -> {
			query = text;
			page = 0;
			refresh();
		});
		addRenderableWidget(box);
		setInitialFocus(box);
		rows.clear();
		for (int i = 0; i < AppSearch.PAGE_SIZE; i++) {
			final int index = i;
			Button b = Button.builder(Component.empty(), btn -> open(index)).bounds(left, top + 34 + i * ROW, WIDTH, ROW - 2).build();
			rows.add(addRenderableWidget(b));
		}
		int footY = top + 34 + AppSearch.PAGE_SIZE * ROW + 2;
		prev = addRenderableWidget(Button.builder(Component.literal("<"), b -> {
			page--;
			refresh();
		}).bounds(left, footY, 20, 20).build());
		next = addRenderableWidget(Button.builder(Component.literal(">"), b -> {
			page++;
			refresh();
		}).bounds(left + WIDTH - 20, footY, 20, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(left + WIDTH / 2 - 40, footY, 80, 20).build());
		refresh();
	}

	private List<AppSearch.App> shown() {
		return AppSearch.page(results, page);
	}

	private void refresh() {
		Map<String, String> installed = port.installedApps();
		results = installed == null ? List.of() : AppSearch.filter(installed, query);
		page = Math.max(0, Math.min(page, AppSearch.pages(results.size()) - 1));
		List<AppSearch.App> on = shown();
		for (int i = 0; i < rows.size(); i++) {
			Button b = rows.get(i);
			b.visible = i < on.size();
			b.setMessage(i < on.size() ? Component.literal("      " + on.get(i).name()) : Component.empty());
		}
		if (prev != null) {
			prev.active = page > 0;
			next.active = page < AppSearch.pages(results.size()) - 1;
		}
		port.requestIcons(on.stream().map(AppSearch.App::bundle).toList());
	}

	private void open(final int index) {
		List<AppSearch.App> on = shown();
		if (index < 0 || index >= on.size()) {
			return;
		}
		AppSearch.App app = on.get(index);
		onClose();
		port.openApp(slot, DockItem.app(app.name(), app.bundle()));
		if (minecraft != null && minecraft.player != null) {
			minecraft.player.sendOverlayMessage(Component.literal("Opening " + app.name() + " on this screen…"));
		}
	}

	@Override
	public boolean keyPressed(final KeyEvent event) {
		int key = event.key();
		if ((key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) && !shown().isEmpty()) {
			open(0);
			return true;
		}
		if (key == InputConstants.KEY_PAGEDOWN) {
			page++;
			refresh();
			return true;
		}
		if (key == InputConstants.KEY_PAGEUP) {
			page--;
			refresh();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void tick() {
		if (results.isEmpty() && port.installedApps() != null && query.isEmpty()) {
			refresh(); // the list arrived after the screen opened
		}
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int pages = AppSearch.pages(results.size());
		String title = "All apps — opens on this screen" + (pages > 1 ? " · page " + (page + 1) + " of " + pages : "");
		graphics.centeredText(font, Component.literal(title), width / 2, top, 0xFFFFFFFF);
		List<AppSearch.App> on = shown();
		if (on.isEmpty()) {
			String empty = port.installedApps() == null ? "Asking your Mac for its apps…" : "No app matches";
			graphics.centeredText(font, Component.literal(empty), width / 2, top + 40, 0xFFA0A0A0);
		}
		for (int i = 0; i < on.size(); i++) {
			Identifier icon = DockIcons.app(on.get(i).bundle(), port);
			int y = top + 34 + i * ROW + 1;
			if (icon != null) {
				graphics.blit(icon, left + 4, y, left + 20, y + 16, 0f, 1f, 0f, 1f);
			}
		}
	}
}
