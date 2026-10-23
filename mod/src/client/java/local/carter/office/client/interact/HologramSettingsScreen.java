package local.carter.office.client.interact;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.interact.WindowListProvider.WindowChoice;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;
import local.carter.office.core.Privacy;
import local.carter.office.core.Shape;
import local.carter.office.hologram.HologramBlockEntity;
import local.carter.office.net.HologramActions;
import local.carter.office.net.OfficePayloads;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Vanilla-styled settings for one hologram, in two fixed columns (no scrolling): Shape, what it shows (whole display
 * 1-4 or one window), who can see it (Public / Private / Show to… with paged online-player checkboxes), Pick up, Done.
 * Every change is sent at once ({@code UpdateHologram}); the server keeps the bottom edge when the shape changes.
 * Non-owners get the same screen read-only with "Owned by <name>".
 */
public class HologramSettingsScreen extends Screen {
	private static final int COL_W = 160;
	private static final int GAP = 16;
	private static final int ROW = 22;
	private static final int LABEL = 12;
	private static final int PLAYERS_PER_PAGE = 5;
	private static final int WINDOWS_PER_PAGE = 3;
	private static final int LABEL_COLOR = 0xFFA0A0A0;
	private static final int TEXT_COLOR = 0xFFFFFFFF;
	private static final int SELECTED_FRAME = 0xFFFFFFFF;

	private final HologramBlockEntity be;
	private final BlockPos pos;
	private final boolean editable;
	private final String ownerName;
	private List<WindowChoice> windows;

	private Shape shape;
	private Mode mode;
	private Privacy.Level privacyLevel;
	private final Set<UUID> allowed;
	private int displaySlot;
	private @Nullable String windowKey;
	private int playerPage;
	private int windowPage;

	/** Buttons drawn with a white frame because they are the current choice. */
	private final List<AbstractWidget> selected = new ArrayList<>();
	/** Section labels: x, y, text. */
	private final List<Object[]> labels = new ArrayList<>();
	private int titleY;

	public HologramSettingsScreen(final HologramBlockEntity be, final String ownerName) {
		super(Component.literal("Hologram"));
		this.be = be;
		this.pos = be.getBlockPos();
		this.ownerName = ownerName;
		HologramSpec spec = be.spec();
		this.editable = minecraft.player != null && be.isOwner(minecraft.player.getUUID());
		this.shape = spec == null ? Shape.WIDE_16_9 : spec.shape();
		this.mode = spec == null ? Mode.EXTEND : spec.mode();
		this.privacyLevel = spec == null ? Privacy.Level.PUBLIC : spec.privacy().level();
		this.allowed = new HashSet<>(spec == null ? Set.of() : spec.privacy().allowed());
		this.displaySlot = spec == null ? 1 : spec.displaySlot();
		this.windowKey = spec == null ? null : spec.windowKey();
		this.windows = editable ? List.copyOf(WindowListProvider.get().windows()) : List.of();
		if (editable) {
			WindowListProvider.get().refresh();
		}
	}

	/** A fresh window list arrived (client thread): show it. */
	public void refreshWindows() {
		if (!editable) {
			return;
		}
		List<WindowChoice> fresh = List.copyOf(WindowListProvider.get().windows());
		if (!fresh.equals(windows)) {
			windows = fresh;
			rebuildWidgets();
		}
	}

	@Override
	protected void init() {
		selected.clear();
		labels.clear();

		int left = (width - (2 * COL_W + GAP)) / 2;
		int right = left + COL_W + GAP;
		int bodyHeight = 3 * LABEL + 2 * ROW + 8 + 4 * ROW; // tallest column (window list / player list)
		int header = LABEL * 2 + 10;
		int top = Math.max(header + 6, (height - (header + bodyHeight + 8 + 20)) / 2 + header);
		titleY = top - header;

		// ---- left column: shape, what it shows ----
		int y = top;
		label(left, y, "Shape");
		y += LABEL;
		int bw = (COL_W - 8) / 3;
		choice(left, y, bw, "16:9", shape == Shape.WIDE_16_9, () -> setShape(Shape.WIDE_16_9));
		choice(left + bw + 4, y, bw, "21:9", shape == Shape.ULTRA_21_9, () -> setShape(Shape.ULTRA_21_9));
		choice(left + 2 * (bw + 4), y, bw, "9:16", shape == Shape.TALL_9_16, () -> setShape(Shape.TALL_9_16));
		y += ROW + 4;

		CycleButton<Mode> modeButton = CycleButton.<Mode>builder(m -> Component.literal(m == Mode.EXTEND ? "Whole display" : "One window"), mode)
				.withValues(List.of(Mode.EXTEND, Mode.WINDOW))
				.create(left, y, COL_W, 20, Component.literal("Shows"), (b, m) -> {
					mode = m;
					send();
					rebuildWidgets();
				});
		modeButton.active = editable;
		addRenderableWidget(modeButton);
		y += ROW + 4;

		if (mode == Mode.EXTEND) {
			label(left, y, "Display");
			y += LABEL;
			int sw = (COL_W - 12) / 4;
			for (int slot = 1; slot <= HologramActions.MAX_DISPLAY_SLOT; slot++) {
				int s = slot;
				choice(left + (slot - 1) * (sw + 4), y, sw, Integer.toString(slot), displaySlot == slot, () -> {
					displaySlot = s;
					send();
					rebuildWidgets();
				});
			}
		} else {
			label(left, y, "Window");
			y += LABEL;
			if (windows.isEmpty()) {
				note(left, y + 6, editable ? "No windows found" : "Shows one window");
			} else {
				int pages = Math.max(1, (windows.size() + WINDOWS_PER_PAGE - 1) / WINDOWS_PER_PAGE);
				windowPage = Math.min(windowPage, pages - 1);
				for (int i = 0; i < WINDOWS_PER_PAGE; i++) {
					int idx = windowPage * WINDOWS_PER_PAGE + i;
					if (idx >= windows.size()) {
						break;
					}
					WindowChoice w = windows.get(idx);
					choice(left, y + i * ROW, COL_W, trim(w.title(), COL_W - 12), w.key().equals(windowKey), () -> {
						windowKey = w.key();
						send();
						rebuildWidgets();
					});
				}
				pager(left, y + WINDOWS_PER_PAGE * ROW, windowPage, pages, p -> windowPage = p);
			}
		}

		// ---- right column: privacy ----
		y = top;
		label(right, y, "Who can see it");
		y += LABEL;
		choice(right, y, bw, "Public", privacyLevel == Privacy.Level.PUBLIC, () -> setPrivacy(Privacy.Level.PUBLIC));
		choice(right + bw + 4, y, bw, "Private", privacyLevel == Privacy.Level.PRIVATE, () -> setPrivacy(Privacy.Level.PRIVATE));
		choice(right + 2 * (bw + 4), y, bw, "Show to…", privacyLevel == Privacy.Level.SHOW_TO, () -> setPrivacy(Privacy.Level.SHOW_TO));
		y += ROW + 4;

		if (privacyLevel == Privacy.Level.SHOW_TO) {
			label(right, y, "Online players");
			y += LABEL;
			List<PlayerInfo> players = otherPlayers();
			if (players.isEmpty()) {
				note(right, y + 6, "No one else is online");
			} else {
				int pages = Math.max(1, (players.size() + PLAYERS_PER_PAGE - 1) / PLAYERS_PER_PAGE);
				playerPage = Math.min(playerPage, pages - 1);
				for (int i = 0; i < PLAYERS_PER_PAGE; i++) {
					int idx = playerPage * PLAYERS_PER_PAGE + i;
					if (idx >= players.size()) {
						break;
					}
					UUID id = players.get(idx).getProfile().id();
					Checkbox box = Checkbox.builder(Component.literal(players.get(idx).getProfile().name()), font)
							.pos(right, y + i * 20)
							.maxWidth(COL_W)
							.selected(allowed.contains(id))
							.onValueChange((cb, on) -> {
								if (on && allowed.size() < OfficePayloads.MAX_ALLOWED) {
									allowed.add(id);
								} else {
									allowed.remove(id);
								}
								send();
							})
							.build();
					box.active = editable;
					addRenderableWidget(box);
				}
				pager(right, y + PLAYERS_PER_PAGE * 20 + 2, playerPage, pages, p -> playerPage = p);
			}
		} else {
			note(right, y + 6, privacyLevel == Privacy.Level.PUBLIC ? "Anyone nearby sees it" : "Only you see it");
		}

		// ---- footer ----
		int footY = top + bodyHeight + 8;
		int center = width / 2;
		if (editable) {
			addRenderableWidget(Button.builder(Component.literal("Pick up"), b -> {
				ClientPlayNetworking.send(new OfficePayloads.RemoveHologram(pos));
				onClose();
			}).bounds(center - 154, footY, 150, 20).build());
			addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(center + 4, footY, 150, 20).build());
		} else {
			addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(center - 75, footY, 150, 20).build());
		}
	}

	private void setShape(final Shape s) {
		shape = s;
		send();
		rebuildWidgets();
	}

	private void setPrivacy(final Privacy.Level level) {
		privacyLevel = level;
		send();
		rebuildWidgets();
	}

	private void send() {
		if (!editable) {
			return;
		}
		Set<UUID> list = privacyLevel == Privacy.Level.SHOW_TO ? allowed : Set.of();
		ClientPlayNetworking.send(new OfficePayloads.UpdateHologram(pos, shape, mode, new Privacy(privacyLevel, list), displaySlot,
				mode == Mode.WINDOW ? windowKey : null));
	}

	private List<PlayerInfo> otherPlayers() {
		List<PlayerInfo> out = new ArrayList<>();
		if (minecraft.getConnection() == null) {
			return out;
		}
		UUID self = minecraft.player == null ? null : minecraft.player.getUUID();
		for (PlayerInfo info : minecraft.getConnection().getOnlinePlayers()) {
			if (!info.getProfile().id().equals(self)) {
				out.add(info);
			}
		}
		out.sort(Comparator.comparing(i -> i.getProfile().name(), String.CASE_INSENSITIVE_ORDER));
		return out;
	}

	private void choice(final int x, final int y, final int w, final String text, final boolean isSelected, final Runnable onPick) {
		Component msg = isSelected ? Component.literal(text).withStyle(ChatFormatting.YELLOW) : Component.literal(text);
		Button b = Button.builder(msg, btn -> {
			if (!isSelected) {
				onPick.run();
			}
		}).bounds(x, y, w, 20).build();
		b.active = editable;
		addRenderableWidget(b);
		if (isSelected) {
			selected.add(b);
		}
	}

	private interface PageSetter {
		void set(int page);
	}

	private void pager(final int x, final int y, final int page, final int pages, final PageSetter setter) {
		if (pages <= 1) {
			return;
		}
		Button prev = Button.builder(Component.literal("<"), b -> {
			setter.set(page - 1);
			rebuildWidgets();
		}).bounds(x, y, 20, 20).build();
		prev.active = page > 0;
		Button next = Button.builder(Component.literal(">"), b -> {
			setter.set(page + 1);
			rebuildWidgets();
		}).bounds(x + COL_W - 20, y, 20, 20).build();
		next.active = page < pages - 1;
		addRenderableWidget(prev);
		addRenderableWidget(next);
		labels.add(new Object[] {x + COL_W / 2 - font.width((page + 1) + " / " + pages) / 2, y + 6, (page + 1) + " / " + pages});
	}

	private void label(final int x, final int y, final String text) {
		labels.add(new Object[] {x, y, text});
	}

	private void note(final int x, final int y, final String text) {
		labels.add(new Object[] {x, y, text});
	}

	private String trim(final String s, final int maxWidth) {
		if (font.width(s) <= maxWidth) {
			return s;
		}
		String out = s;
		while (!out.isEmpty() && font.width(out + "…") > maxWidth) {
			out = out.substring(0, out.length() - 1);
		}
		return out + "…";
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(font, title, width / 2, titleY, TEXT_COLOR);
		String sub = editable ? "Your hologram" : "Owned by " + ownerName;
		graphics.centeredText(font, Component.literal(sub).withStyle(ChatFormatting.GRAY), width / 2, titleY + LABEL, TEXT_COLOR);
		for (Object[] l : labels) {
			graphics.text(font, (String) l[2], (int) l[0], (int) l[1], LABEL_COLOR);
		}
		for (AbstractWidget w : selected) {
			frame(graphics, w.getX() - 1, w.getY() - 1, w.getX() + w.getWidth() + 1, w.getY() + w.getHeight() + 1);
		}
	}

	private static void frame(final GuiGraphicsExtractor g, final int x0, final int y0, final int x1, final int y1) {
		g.fill(x0, y0, x1, y0 + 1, SELECTED_FRAME);
		g.fill(x0, y1 - 1, x1, y1, SELECTED_FRAME);
		g.fill(x0, y0, x0 + 1, y1, SELECTED_FRAME);
		g.fill(x1 - 1, y0, x1, y1, SELECTED_FRAME);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void tick() {
		super.tick();
		if (be.isRemoved() || be.spec() == null) {
			onClose();
		}
	}
}
