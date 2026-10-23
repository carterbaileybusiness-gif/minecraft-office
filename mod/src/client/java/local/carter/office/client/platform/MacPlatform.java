package local.carter.office.client.platform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import local.carter.office.client.OfficeClient;
import local.carter.office.client.capture.CaptureSource;
import local.carter.office.client.capture.MacHelperSource;
import local.carter.office.client.capture.Target;
import local.carter.office.client.helper.HelperConnection;
import local.carter.office.client.helper.HelperInstaller;
import local.carter.office.client.helper.HelperProtocol.Welcome;
import local.carter.office.client.helper.HelperProtocol.WindowRect;
import local.carter.office.client.helper.MacHelper;
import local.carter.office.client.interact.HologramSettingsScreen;
import local.carter.office.client.interact.WindowListProvider;
import local.carter.office.client.interact.WindowListProvider.WindowChoice;
import local.carter.office.client.selftest.SelfTestScreen;
import local.carter.office.core.HologramSpec;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * macOS: the player's own screens come from OfficeHelper.app (virtual displays, ScreenCaptureKit, window placement,
 * cursor focus, exit hotkey) over {@link HelperConnection}.
 *
 * <p>Setup UX: the first time an own hologram needs the helper and it isn't installed, an in-game confirm screen asks
 * before anything is downloaded ("Set up your screens?", Yes / Not now). Until the helper runs with Screen Recording,
 * own holograms show NEEDS_HELPER and chat offers <b>[Set up screens]</b> (opens SETUP.md#mac and System Settings'
 * Screen Recording pane) and <b>[Check again]</b> (restarts the helper so new permissions apply). Leaving the world
 * removes the displays and disconnects; the helper quits.
 */
public final class MacPlatform implements Platform {
	private static final Logger LOGGER = LoggerFactory.getLogger("office");
	public static final String SCREEN_RECORDING_PANE = "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture";
	public static final String ACCESSIBILITY_PANE = "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility";
	private static final String COMMAND = "officescreens";

	private final HelperInstaller installer;
	private final MacHelper helper;
	private volatile @Nullable Path app;
	// client thread
	private final HelperSetup setup = new HelperSetup();
	private HelperInstaller.@Nullable Asset pendingAsk;
	private int pendingSession;
	private boolean permissionNotified;

	MacPlatform() {
		installer = HelperInstaller.create(OfficeClient.MOD_VERSION, OfficeClient.RELEASE_BASE);
		int pid = (int) ProcessHandle.current().pid();
		helper = new MacHelper(listener -> new HelperConnection(installer.supportDir(), pid, () -> {
			Path a = app;
			if (a != null) {
				HelperInstaller.launch(a);
			}
		}, listener), r -> Minecraft.getInstance().execute(r));
	}

	public MacHelper helper() {
		return helper;
	}

	@Override
	public void register() {
		helper.onStatusChange(this::statusChanged);
		helper.onWindows(() -> {
			if (Minecraft.getInstance().gui.screen() instanceof HologramSettingsScreen s) {
				s.refreshWindows();
			} else if (Minecraft.getInstance().gui.screen() instanceof SelfTestScreen s) {
				s.refreshWindows();
			}
		});
		WindowListProvider.set(new WindowListProvider() {
			@Override
			public List<WindowChoice> windows() {
				List<WindowChoice> out = new ArrayList<>();
				for (WindowInfo w : MacPlatform.this.windows()) {
					out.add(new WindowChoice(w.key(), w.title().isEmpty() ? w.app() : w.app() + " — " + w.title()));
				}
				return out;
			}

			@Override
			public void refresh() {
				refreshWindows();
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(this::leftWorld));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(ClientCommands.literal(COMMAND)
				.then(ClientCommands.literal("setup").executes(ctx -> {
					setUpScreens();
					return 1;
				}))
				.then(ClientCommands.literal("retry").executes(ctx -> {
					checkAgain();
					return 1;
				}))));
	}

	// ---------------------------------------------------------------- Platform

	@Override
	public boolean supportsExtend() {
		return true;
	}

	@Override
	public List<WindowInfo> windows() {
		List<WindowInfo> out = new ArrayList<>();
		for (var w : helper.windows()) {
			out.add(new WindowInfo(w.windowKey(), w.app(), w.title(), w.w(), w.h()));
		}
		return out;
	}

	@Override
	public void refreshWindows() {
		helper.listWindows();
	}

	@Override
	public void zoomIn(final HologramSpec spec, final Rect mcWindow) {
		helper.zoomIn(helper.surfaceFor(Target.of(spec)), new WindowRect(mcWindow.x(), mcWindow.y(), mcWindow.w(), mcWindow.h()));
	}

	@Override
	public void zoomOut() {
		helper.zoomOut();
	}

	@Override
	public void onExitRequested(final Runnable r) {
		helper.onExitZoom(r);
	}

	// ---------------------------------------------------------------- self-test (off the client thread)

	/** Self-test: longest wait for an installed helper to connect before reporting its state. */
	static final long SELF_TEST_HELPER_WAIT_MS = 3_000;

	/** Installed? Connected? Screen Recording / Accessibility granted? Starts an installed helper; never installs one. */
	@Override
	public Readiness readiness() throws InterruptedException {
		if (installer.installedApp().isEmpty()) {
			return new Readiness(true, false, false, installer.usesOverride() ? "office.helperPath does not point to OfficeHelper.app"
					: "screen helper not installed yet (Minecraft offers it when your first hologram needs it, or type /officescreens setup)");
		}
		ensureStarted();
		long deadline = System.nanoTime() + SELF_TEST_HELPER_WAIT_MS * 1_000_000L;
		while (helper.welcome() == null && System.nanoTime() < deadline) {
			Thread.sleep(100);
		}
		Welcome w = helper.welcome();
		MacHelper.Status st = helper.status();
		if (w == null) {
			return new Readiness(true, false, false, "screen helper installed but not connected after " + SELF_TEST_HELPER_WAIT_MS / 1000
					+ " s (" + st.name().toLowerCase(Locale.ROOT).replace('_', ' ') + ")");
		}
		boolean recording = st == MacHelper.Status.READY;
		boolean ok = recording && w.accessibility();
		String detail = "helper connected (protocol " + w.version() + "), Screen Recording " + (recording ? "on" : "OFF")
				+ ", Accessibility " + (w.accessibility() ? "on" : "OFF");
		if (!ok) {
			detail += ": switch it on in System Settings > Privacy & Security, then type /officescreens retry";
		}
		return new Readiness(true, ok, recording, detail);
	}

	@Override
	public void prepareSelfTest() {
		if (installer.installedApp().isEmpty()) {
			return;
		}
		ensureStarted();
		long deadline = System.nanoTime() + SELF_TEST_HELPER_WAIT_MS * 1_000_000L;
		try {
			while (!helper.isConnected() && System.nanoTime() < deadline) {
				Thread.sleep(100);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		}
		helper.listWindows(); // the reply refreshes the self-test screen (onWindows)
	}

	@Override
	public CaptureSource newOwnSource() {
		return new MacHelperSource(helper, this::ensureStarted);
	}

	// ---------------------------------------------------------------- setup (client thread)

	/** An own hologram needs the helper: connect, or ask to install it first. */
	private void ensureStarted() {
		Minecraft mc = Minecraft.getInstance();
		if (!mc.isSameThread()) {
			mc.execute(this::ensureStarted);
			return;
		}
		act(setup.ensure(installer.installedApp(), installer.usesOverride()), null);
	}

	private void act(final HelperSetup.Action action, final @Nullable Throwable failure) {
		app = setup.app(); // for the connection's (re)launcher, even when the helper isn't started now
		if (setup.state() == HelperSetup.State.READY) {
			helper.setSetUp(true);
		}
		switch (action) {
			case START -> {
				helper.setSetUp(true);
				helper.start();
			}
			case ASK -> ask();
			case BAD_OVERRIDE -> chat(Component.literal("office.helperPath does not point to OfficeHelper.app.").withStyle(ChatFormatting.RED));
			case REPORT_FAILURE -> chat(Component.literal("Screen helper setup failed: " + (failure == null ? "unknown error" : cause(failure).getMessage()) + " ")
					.withStyle(ChatFormatting.RED).append(link("Try again", "setup", "Download again")));
			case NONE -> {
				// waiting for an answer or a download, or "Not now" this session
			}
		}
	}

	private void ask() {
		int asked = setup.session();
		installer.fetchHelperInfo().whenComplete((asset, err) -> Minecraft.getInstance().execute(() -> {
			if (!setup.manifestRead(asked, err == null)) {
				if (err != null && asked == setup.session()) {
					LOGGER.warn("Office helper: release manifest unavailable: {}", cause(err).toString());
					chat(Component.literal("Couldn't reach GitHub to set up your screens (" + cause(err).getMessage() + "). ")
							.append(link("Try again", "setup", "Ask again")));
				}
				return;
			}
			pendingAsk = asset;
			pendingSession = asked;
		}));
	}

	private void tick(final Minecraft mc) {
		HelperInstaller.Asset asset = pendingAsk;
		if (asset == null || mc.player == null || mc.gui.screen() != null) {
			return;
		}
		pendingAsk = null;
		int asked = pendingSession;
		if (asked != setup.session()) {
			return;
		}
		mc.gui.setScreen(new ConfirmScreen(yes -> {
			mc.gui.setScreen(null);
			answered(asked, yes);
		}, Component.literal("Set up your screens?"),
				Component.literal("This downloads the small Minecraft Office helper app (" + HelperInstaller.sizeLabel(asset.size())
						+ ") from GitHub."),
				Component.literal("Yes"), Component.literal("Not now")));
	}

	private void answered(final int asked, final boolean yes) {
		if (!setup.answered(asked, yes)) {
			if (!yes && asked == setup.session()) {
				chat(Component.literal("Your screens stay off for now. ").append(link("Set up screens", "setup", "Download the helper app")));
			}
			return;
		}
		chat(Component.literal("Downloading the screen helper…"));
		int started = setup.session();
		installer.ensureInstalled().whenComplete((path, err) -> Minecraft.getInstance().execute(() -> {
			if (err != null) {
				LOGGER.warn("Office helper install failed", cause(err));
			} else if (started == setup.session()) {
				chat(Component.literal("Screen helper installed."));
			}
			// Recorded even if the player left meanwhile (never stuck "installing"); only acts on the current world.
			act(setup.installFinished(started, err == null ? path : null), err);
		}));
	}

	/** {@code /officescreens setup} (the chat link). */
	private void setUpScreens() {
		switch (setup.state()) {
			case UNKNOWN, DECLINED, FAILED -> {
				setup.retry();
				ensureStarted();
			}
			case ASKING, INSTALLING -> chat(Component.literal("Setting up your screens…"));
			case READY -> {
				Welcome w = helper.welcome();
				if (w == null) {
					act(HelperSetup.Action.START, null);
					chat(Component.literal("Starting the screen helper…"));
				} else if (!w.screenRecording() || helper.status() == MacHelper.Status.NO_PERMISSION) {
					openSetupPages(SCREEN_RECORDING_PANE);
				} else if (!w.accessibility()) {
					openSetupPages(ACCESSIBILITY_PANE);
				} else {
					chat(Component.literal("Your screens are set up."));
				}
			}
		}
	}

	/** {@code /officescreens retry}: restart the helper so permissions granted meanwhile apply. */
	private void checkAgain() {
		if (setup.state() != HelperSetup.State.READY) {
			setUpScreens();
			return;
		}
		permissionNotified = false;
		helper.restart();
		chat(Component.literal("Restarting the screen helper…"));
	}

	private void statusChanged() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		Welcome w = helper.welcome();
		MacHelper.Status st = helper.status();
		if (w != null && (st == MacHelper.Status.NO_PERMISSION || !w.accessibility())) {
			if (permissionNotified) {
				return;
			}
			permissionNotified = true;
			List<String> missing = new ArrayList<>();
			if (st == MacHelper.Status.NO_PERMISSION) {
				missing.add("Screen Recording (to show your screens)");
			}
			if (!w.accessibility()) {
				missing.add("Accessibility (to work on a screen)");
			}
			chat(Component.literal("OfficeHelper needs macOS " + String.join(" and ", missing) + ". ")
					.append(link("Set up screens", "setup", "Opens the setup guide and System Settings"))
					.append(" ")
					.append(link("Check again", "retry", "Restart the helper after switching it on")));
		} else if (st == MacHelper.Status.READY && permissionNotified) {
			permissionNotified = false;
			chat(Component.literal("Screens ready."));
		}
	}

	private void leftWorld() {
		setup.leftWorld();
		helper.stop();
		pendingAsk = null;
		permissionNotified = false;
	}

	// ---------------------------------------------------------------- helpers

	/** Opens SETUP.md#mac in the browser and the given System Settings pane (never changes any setting). */
	private static void openSetupPages(final String pane) {
		open(OfficeClient.SETUP_MAC_URL);
		open(pane);
	}

	private static void open(final String target) {
		try {
			new ProcessBuilder("/usr/bin/open", target).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
		} catch (IOException e) {
			LOGGER.warn("Could not open {}: {}", target, e.toString());
		}
	}

	private static MutableComponent link(final String text, final String sub, final String hover) {
		return Component.literal("[" + text + "]").withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
				.withClickEvent(new ClickEvent.RunCommand("/" + COMMAND + " " + sub))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	private static void chat(final Component message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[Office] ").withStyle(ChatFormatting.DARK_AQUA).append(message));
		}
	}

	private static Throwable cause(final Throwable t) {
		return t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
	}
}
