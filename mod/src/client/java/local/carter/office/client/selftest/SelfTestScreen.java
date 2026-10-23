package local.carter.office.client.selftest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.blaze3d.Blaze3D;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.GpuDevice;

import local.carter.office.client.OfficeClient;
import local.carter.office.client.platform.MacPlatform;
import local.carter.office.client.platform.NoPlatform;
import local.carter.office.client.platform.Platform;
import local.carter.office.client.platform.WindowsPlatform;
import local.carter.office.client.selftest.SelfTestReport.Line;
import local.carter.office.client.selftest.SelfTestReport.Status;
import local.carter.office.client.stream.StreamClient;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/**
 * "Screen self-test" (Esc menu button, or {@code /officeselftest}): the player may pick one of their windows (app names
 * only on this screen, so a screenshot of it is safe too), presses <b>Run test</b>, and watches a live checklist while
 * {@link SelfTest} runs off the client thread. The plain-text report is saved as
 * {@code <gameDir>/office-selftest/selftest-<timestamp>.txt}; <b>Copy report</b> puts it on the clipboard and
 * <b>Open folder</b> shows the folder. Closing the screen cancels a running test.
 */
public final class SelfTestScreen extends Screen {
	private static final Logger LOGGER = LogUtils.getLogger();
	public static final String TITLE = "Screen self-test";
	public static final String COMMAND = "officeselftest";
	public static final String FOLDER = "office-selftest";
	private static final int ROW = 12;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int GREY = 0xFFA0A0A0;
	private static final int GREEN = 0xFF55FF55;
	private static final int RED = 0xFFFF5555;
	private static final int YELLOW = 0xFFFFFF55;

	private static boolean openRequested;

	private final @Nullable Screen parent;
	/** Windows the player may pick (null while loading); index -1 = none (real capture is skipped). */
	private @Nullable List<Platform.WindowInfo> windows;
	private List<String> windowLabels = List.of();
	private int picked = -1;
	private int windowsAsked;

	// client thread
	private final Map<String, Line> lines = new HashMap<>();
	private List<SelfTest.StepDef> steps = SelfTest.steps();
	private SelfTest.@Nullable Run run;
	private @Nullable SelfTestReport report;
	private @Nullable String text;
	private @Nullable Path saved;
	private String status = "Pick a window to also test real capture (optional), then press Run test.";

	public SelfTestScreen(final @Nullable Screen parent) {
		super(Component.literal(TITLE));
		this.parent = parent;
		loadWindows();
	}

	// ------------------------------------------------------------------ entry points

	/** Client init: the Esc-menu button and {@code /officeselftest}. */
	public static void register() {
		ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			if (screen instanceof PauseScreen pause && pause.showsPauseMenu()) {
				Screens.getWidgets(screen).add(Button.builder(Component.literal(TITLE), b -> mc.gui.setScreen(new SelfTestScreen(screen)))
						.bounds(4, h - 24, 100, 20).build());
			}
		});
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(ClientCommands.literal(COMMAND)
				.executes(ctx -> {
					openRequested = true; // opened next tick, once the chat screen has closed
					return 1;
				})));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (openRequested && mc.gui.screen() == null) {
				openRequested = false;
				mc.gui.setScreen(new SelfTestScreen(null));
			}
		});
	}

	/** The window list changed (Mac helper reply): fetch it again. */
	public void refreshWindows() {
		if (run == null || !run.isAlive()) {
			loadWindows();
		}
	}

	// ------------------------------------------------------------------ layout

	@Override
	protected void init() {
		int w = 308;
		int left = (width - w) / 2;
		int top = 44;
		boolean running = run != null && run.isAlive();

		Button pick = Button.builder(Component.literal(trim("Test window: " + pickedLabel(), 196)), b -> cyclePick()).bounds(left, top, 200, 20).build();
		pick.active = !running && windows != null;
		addRenderableWidget(pick);
		Button go = Button.builder(Component.literal(text == null ? "Run test" : "Run again"), b -> runTest()).bounds(left + 204, top, 104, 20).build();
		go.active = !running;
		addRenderableWidget(go);

		int footY = height - 28;
		Button copy = Button.builder(Component.literal("Copy report"), b -> copyReport()).bounds(left, footY, 100, 20).build();
		copy.active = text != null;
		addRenderableWidget(copy);
		addRenderableWidget(Button.builder(Component.literal("Open folder"), b -> openFolder()).bounds(left + 104, footY, 100, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(left + 208, footY, 100, 20).build());
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(font, title, width / 2, 14, TEXT);
		graphics.centeredText(font, Component.literal("Checks your screens. The report has no pictures of your screen and no window titles.")
				.withStyle(ChatFormatting.GRAY), width / 2, 27, TEXT);
		int left = (width - 308) / 2;
		int y = 72;
		for (SelfTest.StepDef s : steps) {
			Line l = lines.get(s.id());
			Status st = l == null ? Status.PENDING : l.status();
			int color = switch (st) {
				case PASS -> GREEN;
				case FAIL -> RED;
				case RUNNING -> YELLOW;
				default -> GREY;
			};
			graphics.text(font, st.symbol(), left, y, color);
			String detail = l == null || l.detail().isEmpty() ? "" : ": " + (report == null ? l.detail() : report.scrubDetail(l.detail()));
			graphics.text(font, trim(s.name() + detail, 296), left + 12, y, st == Status.PENDING ? GREY : TEXT);
			y += ROW;
		}
		graphics.centeredText(font, Component.literal(trim(status, width - 16)), width / 2, Math.min(y + 8, height - 42), GREY);
	}

	@Override
	public boolean isPauseScreen() {
		return false; // the game (and the network the UDP check needs) keeps running
	}

	@Override
	public void onClose() {
		cancel();
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void removed() {
		cancel();
		super.removed();
	}

	private void cancel() {
		SelfTest.Run r = run;
		if (r != null && r.isAlive()) {
			r.cancel();
		}
	}

	// ------------------------------------------------------------------ window pick

	/**
	 * Fetches the window list off the client thread (Windows: slow, loads natives the first time; Mac: starts an
	 * installed helper if needed and asks it for windows).
	 */
	private void loadWindows() {
		int ask = ++windowsAsked;
		Platform platform = Platform.current();
		CompletableFuture.supplyAsync(() -> {
			platform.prepareSelfTest();
			return List.copyOf(platform.windows());
		}, r -> {
			Thread t = new Thread(r, "office-selftest-windows");
			t.setDaemon(true);
			t.start();
		}).exceptionally(e -> List.of()).thenAccept(list -> minecraft.execute(() -> {
			if (ask != windowsAsked) {
				return;
			}
			String keep = picked >= 0 && windows != null && picked < windows.size() ? windows.get(picked).key() : null;
			windows = list;
			windowLabels = labels(list);
			picked = -1;
			for (int i = 0; keep != null && i < list.size(); i++) {
				if (list.get(i).key().equals(keep)) {
					picked = i;
				}
			}
			if (minecraft.gui.screen() == this) {
				rebuildWidgets();
			}
		}));
	}

	/** App names only, numbered when one app has several windows ("Google Chrome (2)"). */
	static List<String> labels(final List<Platform.WindowInfo> list) {
		Map<String, Integer> count = new HashMap<>();
		List<String> out = new ArrayList<>();
		for (Platform.WindowInfo w : list) {
			String app = w.app() == null || w.app().isBlank() ? "Window" : w.app();
			int n = count.merge(app.toLowerCase(Locale.ROOT), 1, Integer::sum);
			out.add(n == 1 || app.matches(".* \\(\\d+\\)") ? app : app + " (" + n + ")");
		}
		return out;
	}

	private String pickedLabel() {
		if (windows == null) {
			return "looking…";
		}
		if (windows.isEmpty()) {
			return "none found";
		}
		return picked < 0 ? "None (skip)" : windowLabels.get(picked);
	}

	private void cyclePick() {
		List<Platform.WindowInfo> list = windows;
		if (list == null) {
			return;
		}
		if (list.isEmpty()) {
			loadWindows(); // look again (e.g. the Mac helper has started meanwhile)
			Platform.current().refreshWindows();
			return;
		}
		picked = picked + 1 >= list.size() ? -1 : picked + 1;
		rebuildWidgets();
	}

	// ------------------------------------------------------------------ run

	private void runTest() {
		if (run != null && run.isAlive()) {
			return;
		}
		Platform platform = Platform.current();
		List<Platform.WindowInfo> list = windows == null ? List.of() : windows;
		Platform.WindowInfo choice = picked >= 0 && picked < list.size() ? list.get(picked) : null;
		SelfTestReport r = new SelfTestReport();
		r.hideWindowTitles(list);
		collectInfo(r, platform, choice == null ? null : windowLabels.get(picked));
		report = r;
		text = null;
		saved = null;
		lines.clear();
		steps = SelfTest.steps();
		status = "Running… (up to 30 s; you can keep playing)";
		Path dir = folder();
		run = SelfTest.start(steps, new SelfTest.Context(platform, choice, StreamClient.get(), r), new SelfTest.Listener() {
			@Override
			public void changed(final Line line) {
				minecraft.execute(() -> lines.put(line.id(), line));
			}

			@Override
			public void finished(final SelfTestReport rep, final String rendered) {
				Path file = null;
				String err = null;
				try {
					Files.createDirectories(dir);
					file = dir.resolve("selftest-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)) + ".txt");
					Files.writeString(file, rendered, StandardCharsets.UTF_8);
				} catch (IOException | RuntimeException e) {
					LOGGER.warn("[office] Could not save the self-test report: {}", e.toString());
					err = e.getMessage();
					file = null;
				}
				Path f = file;
				String e = err;
				minecraft.execute(() -> {
					text = rendered;
					saved = f;
					status = f != null ? "Done. Saved as " + FOLDER + "/" + f.getFileName() + ". Press Copy report and send it to Carter."
							: "Done, but saving failed (" + e + "). Copy report still works.";
					LOGGER.info("[office] Self-test finished:\n{}", rendered);
					if (minecraft.gui.screen() == SelfTestScreen.this) {
						rebuildWidgets();
					}
				});
			}
		});
		rebuildWidgets();
	}

	private void copyReport() {
		if (text != null) {
			minecraft.keyboardHandler.setClipboard(text);
			status = "Report copied. Paste it into a message to Carter.";
		}
	}

	private void openFolder() {
		Path dir = folder();
		try {
			Files.createDirectories(dir);
			Blaze3D.openPath(dir);
		} catch (IOException | RuntimeException e) {
			status = "Couldn't open the folder: it is " + dir;
		}
	}

	private static Path folder() {
		return FabricLoader.getInstance().getGameDir().resolve(FOLDER);
	}

	// ------------------------------------------------------------------ facts (client thread)

	/** Versions, Java/OS/CPU/RAM, GPU, display and optional mods. Never the server address or player data. */
	private void collectInfo(final SelfTestReport r, final Platform platform, final @Nullable String pickedApp) {
		r.info("Mod", "Minecraft Office " + OfficeClient.MOD_VERSION);
		r.info("Minecraft", version("minecraft") + ", Fabric Loader " + version("fabricloader") + ", Fabric API " + version("fabric-api"));
		SelfTest.basicInfo(r);
		r.info("GPU", gpu());
		r.info("Display", display());
		r.info("Optional mods", "Sodium " + present("sodium") + ", Iris " + present("iris") + ", Distant Horizons "
				+ present("distanthorizons") + ", Simple Voice Chat " + present("voicechat") + ", Dynamic FPS " + present("dynamic_fps"));
		r.info("Screens", platform instanceof MacPlatform ? "Mac (helper app)" : platform instanceof WindowsPlatform
				? "Windows beta (one window per hologram)" : platform instanceof NoPlatform ? "none on this system" : platform.getClass().getSimpleName());
		r.info("Window picked", pickedApp == null ? "none" : pickedApp);
		r.info("Server address", "omitted");
	}

	private static String version(final String modId) {
		return FabricLoader.getInstance().getModContainer(modId).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
	}

	private static String present(final String modId) {
		return FabricLoader.getInstance().getModContainer(modId).map(ModContainer::getMetadata).map(m -> m.getVersion().getFriendlyString())
				.orElse("no");
	}

	private static String gpu() {
		try {
			GpuDevice device = RenderSystem.tryGetDevice();
			if (device == null) {
				return "unknown (no device yet)";
			}
			DeviceInfo info = device.getDeviceInfo();
			return info.name() + " (" + info.vendorName() + ", " + info.backendName() + ", driver " + info.driverInfo() + ")";
		} catch (Throwable t) {
			return "unknown (" + t.getClass().getSimpleName() + ")";
		}
	}

	private String display() {
		try {
			Window w = minecraft.getWindow();
			return w.getWidth() + "x" + w.getHeight() + " px game window, GUI scale " + w.getGuiScale()
					+ String.format(Locale.ROOT, ", display scale %.2f", w.getPixelDensity()) + (w.isExclusiveFullscreen() ? ", fullscreen" : "");
		} catch (Throwable t) {
			return "unknown (" + t.getClass().getSimpleName() + ")";
		}
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
}
