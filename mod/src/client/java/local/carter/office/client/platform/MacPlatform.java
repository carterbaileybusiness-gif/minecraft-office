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
import local.carter.office.client.dock.DockItem;
import local.carter.office.client.capture.MacHelperSource;
import local.carter.office.client.capture.OwnScreens;
import local.carter.office.client.render.HologramLook;
import local.carter.office.client.capture.Target;
import local.carter.office.client.helper.HelperConnection;
import local.carter.office.client.helper.HelperInstaller;
import local.carter.office.client.helper.HelperProtocol;
import local.carter.office.client.helper.HelperProtocol.Welcome;
import local.carter.office.client.helper.HelperProtocol.PointerButton;
import local.carter.office.client.helper.MacHelper;
import local.carter.office.client.interact.HologramSettingsScreen;
import local.carter.office.client.interact.WindowListProvider;
import local.carter.office.client.interact.WindowListProvider.WindowChoice;
import local.carter.office.client.pointer.PointerPoint;
import local.carter.office.client.pointer.PointerPort;
import local.carter.office.client.screens.Origin;
import local.carter.office.client.screens.ScreenList;
import local.carter.office.client.screens.SdlScreens;
import local.carter.office.client.selftest.SelfTestScreen;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;
import local.carter.office.hologram.HologramBlockEntity;
import local.carter.office.client.interact.HologramTracker;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * macOS: the player's own screens come from OfficeHelper.app (virtual displays, Mirror = the main display minus
 * Minecraft, ScreenCaptureKit) over {@link HelperConnection}, and the holograms are used right in the world with the
 * crosshair as the mouse ({@link #pointer}, spec 5.9: clicks, helper-driven drags, scrolling, typing mode with Esc to
 * return). The first hologram is an interactive Mirror of the laptop screen; Minecraft's window floats above the other
 * apps there and is click-through while the player uses the Mirror ({@link MacWindow#layer}). Windows never move by
 * themselves; the Extend holograms' mini Dock opens apps onto them. Minecraft's window defaults to borderless, filling
 * the main display ({@link MacWindow#fillMainDisplay}; {@code -Doffice.borderless=false} keeps a normal window). There
 * is no zoom on a Mac ({@link #supportsZoom} false).
 *
 * <p>Setup UX: the first time an own hologram needs the helper and it isn't installed, an in-game confirm screen asks
 * before anything is downloaded ("Set up your screens?", Yes / Not now). Until the helper runs with Screen Recording,
 * own holograms show NEEDS_HELPER and chat offers <b>[Set up screens]</b> (opens SETUP.md#mac and System Settings'
 * Screen Recording pane) and <b>[Check again]</b> (restarts the helper so new permissions apply). Leaving the world
 * removes the displays and disconnects; the helper quits.
 */
public final class MacPlatform implements Platform {
	private static final Logger LOGGER = LoggerFactory.getLogger("office");
	/** Measurements (client GameTest): make Minecraft inactive for real (false) or active again (true). Main thread. */
	public static void setActiveForTesting(final boolean active) {
		if (active) {
			MacApp.activate(true);
		} else {
			MacApp.deactivate();
		}
	}

	public static final String SCREEN_RECORDING_PANE = "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture";
	public static final String ACCESSIBILITY_PANE = "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility";

	private final HelperInstaller installer;
	private final MacHelper helper;
	private volatile @Nullable Path app;
	// client thread
	private final HelperSetup setup = new HelperSetup();
	private HelperInstaller.@Nullable Asset pendingAsk;
	private int pendingSession;
	private boolean permissionNotified;
	private final PointerPort pointer = new HelperPointer();

	MacPlatform() {
		installer = HelperInstaller.create(OfficeClient.MOD_VERSION, OfficeClient.RELEASE_BASE);
		int pid = (int) ProcessHandle.current().pid();
		helper = new MacHelper(listener -> new HelperConnection(installer.supportDir(), pid, () -> {
			Path a = app;
			if (a != null) {
				HelperInstaller.launch(a);
			}
		}, listener), r -> Minecraft.getInstance().execute(r));
		helper.setFallbackScreens(SdlScreens::list);
	}

	/** The borderless fill-the-screen default was applied (or skipped) once at startup. */
	private boolean windowModeDone;
	private final MirrorHint mirrorHint = new MirrorHint();
	private int mirrorHintTicks;

	public MacHelper helper() {
		return helper;
	}

	@Override
	public void register() {
		helper.onStatusChange(this::statusChanged);
		installBesideMirror();
		HologramLook.setOwnDetailProvider(OwnScreens::ownDetail); // why a capture ended, under "Screens unavailable"
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
		// The chat links' /officescreens: a Fabric client command, so clicks never reach the (dedicated) server.
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
				OfficeScreensCommand.<FabricClientCommandSource>tree(action -> {
					switch (action) {
						case SETUP -> setUpScreens();
						case RETRY -> checkAgain();
					}
				})));
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

	/** The helper's list when it has the display map, else SDL's (greyed: "Restart Minecraft to update the helper"). */
	@Override
	public ScreenList screens() {
		return helper.screens();
	}

	@Override
	public void refreshScreens() {
		helper.listDisplays();
	}

	@Override
	public void onScreensChanged(final Runnable r) {
		helper.onScreensChanged(r);
	}

	@Override
	public boolean applyOrigins(final List<Origin> origins) {
		return helper.setOrigins(origins);
	}

	/** SET_ORIGINS refused (bad_request) or macOS refused it (display_failed): the helper's ERROR, then its DISPLAYS. */
	@Override
	public void onMoveRefused(final java.util.function.Consumer<@Nullable String> r) {
		helper.onError(e -> {
			if ("bad_request".equals(e.code()) || "display_failed".equals(e.code())) {
				r.accept(e.message());
			}
		});
	}

	@Override
	public boolean identifyScreens(final java.util.Map<String, String> labelsById) {
		return helper.identify(labelsById);
	}

	/** No zoom on a Mac: the holograms are used in the world (spec 5.9). */
	@Override
	public boolean supportsZoom() {
		return false;
	}

	@Override
	public void zoomIn(final HologramSpec spec, final Rect mcWindow) {
		LOGGER.debug("Office: zoom isn't used on a Mac");
	}

	@Override
	public void zoomOut() {}

	@Override
	public void onExitRequested(final Runnable r) {}

	@Override
	public PointerPort pointer() {
		return pointer;
	}

	/** In-world use through the helper. */
	private final class HelperPointer implements PointerPort {
		@Override
		public @Nullable String problem() {
			Welcome w = helper.welcome();
			if (!helper.isConnected() || w == null) {
				return ZoomFailure.NO_HELPER;
			}
			return w.accessibility() ? null : "OfficeHelper needs macOS Accessibility to click and type on your screens: /officescreens setup";
		}

		@Override
		public void workBegin(final PointerPoint p, final PointerButton button) {
			helper.workBegin(p, button);
		}

		@Override
		public int @Nullable [] workCursor() {
			HelperProtocol.Cursor c = helper.cursor();
			return c == null ? null : new int[] {c.slot(), c.x(), c.y(), c.w(), c.h()};
		}

		@Override
		public @Nullable String workCursorDisplay() {
			return helper.workCursorDisplay();
		}

		@Override
		public CursorReport workCursorReport() {
			HelperProtocol.Cursor c = helper.cursor(); // one read: position and monitor from the same CURSOR
			return c == null ? new CursorReport(null, null) : new CursorReport(new int[] {c.slot(), c.x(), c.y(), c.w(), c.h()}, c.display());
		}

		@Override
		public @Nullable String mirrorShown(final @Nullable String wanted) {
			return helper.resolveMirror(wanted);
		}

		@Override
		public void scroll(final PointerPoint p, final int dx, final int dy) {
			helper.scroll(p, dx, dy);
		}

		@Override
		public void exitTyping() {
			helper.exitTyping();
		}

		@Override
		public void onTypingExit(final java.util.function.Consumer<String> listener) {
			helper.onTypingExit(listener);
		}

		@Override
		public void activateMinecraft() {
			MacApp.activate(true);
		}

		@Override
		public void windowLayer(final boolean floating, final boolean clickThrough) {
			MacWindow.layer(Minecraft.getInstance().getWindow().handle(), floating, clickThrough);
		}

		@Override
		public boolean windowLayerApplied(final boolean floating, final boolean clickThrough) {
			return MacWindow.layer(Minecraft.getInstance().getWindow().handle(), floating, clickThrough);
		}

		@Override
		public @Nullable String mirrorProblem() {
			long handle = Minecraft.getInstance().getWindow().handle();
			return MacWindow.isFullscreen(handle) ? MirrorHint.TEXT : null;
		}

		@Override
		public void onSecureInput(final java.util.function.Consumer<Boolean> listener) {
			helper.onSecureInput(listener);
		}

		@Override
		public void onWalk(final java.util.function.Consumer<java.util.Set<String>> listener) {
			helper.onWalk(listener);
		}

		@Override
		public java.util.@Nullable Map<String, String> installedApps() {
			return helper.apps();
		}

		@Override
		public byte @Nullable [] appIcon(final String bundle) {
			return helper.icon(bundle);
		}

		@Override
		public void requestIcons(final List<String> bundles) {
			helper.requestIcons(bundles);
		}

		@Override
		public void openApp(final int slot, final DockItem item, final int bottomPixels) {
			helper.openApp(slot, item.bundle(), item.url(), bottomPixels > 0 ? bottomPixels : null);
		}

		@Override
		public void onAppProblem(final java.util.function.Consumer<String> listener) {
			helper.onAppResult(r -> {
				if (!r.ok()) {
					listener.accept(r.message());
				}
			});
		}
	}

	// ---------------------------------------------------------------- self-test (off the client thread)

	/** Self-test: longest wait for an installed helper to connect before reporting its state. */
	static final long SELF_TEST_HELPER_WAIT_MS = 3_000;

	/**
	 * Installed? Connected? Screen Recording / Accessibility granted? Only runs when the player pressed Run test: starts
	 * an installed helper (never installs one) and, once connected, asks it for the window list once (the reply only
	 * makes the self-test screen re-read {@link #windows}).
	 */
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
					+ " s (" + st.name().toLowerCase(Locale.ROOT).replace('_', ' ') + "): type /officescreens setup");
		}
		helper.listWindows(); // one request per check (never from the reply), so the next run can offer windows
		boolean recording = st == MacHelper.Status.READY;
		boolean ok = recording && w.accessibility();
		String detail = "helper connected (protocol " + w.version() + "), Screen Recording " + (recording ? "on" : "OFF")
				+ ", Accessibility " + (w.accessibility() ? "on" : "OFF");
		if (!ok) {
			detail += ": type /officescreens setup, switch OfficeHelper on in System Settings > Privacy & Security, then run again";
		}
		return new Readiness(true, ok, recording, detail);
	}

	/** Using a screen in the world needs the helper connected and Accessibility (posting events, the Esc tap). */
	@Override
	public @Nullable String workProblem(final HologramSpec spec) {
		return pointer.problem();
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
					.withStyle(ChatFormatting.RED).append(link("Try again", OfficeScreensCommand.Action.SETUP, "Download again")));
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
							.append(link("Try again", OfficeScreensCommand.Action.SETUP, "Ask again")));
				}
				return;
			}
			pendingAsk = asset;
			pendingSession = asked;
		}));
	}

	private void tick(final Minecraft mc) {
		if (!windowModeDone) {
			windowModeDone = true;
			long handle = mc.getWindow().handle();
			if ("false".equalsIgnoreCase(System.getProperty("office.borderless"))) {
				LOGGER.info("Office: -Doffice.borderless=false: Minecraft keeps its own window");
			} else if (MacWindow.isFullscreen(handle)) {
				LOGGER.info("Office: Minecraft started in its own Space; the Mirror hologram works best in a window (fn+F11 / Video Settings)");
			} else {
				MacWindow.fillMainDisplay(handle);
			}
		}
		mirrorHint(mc);
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

	/**
	 * A new Extend hologram the player just placed goes beside their Mirror, on the side the helper put its display in
	 * the arrangement (left when a real monitor takes the right), and chat says which edge of the Mirror leads there
	 * (spec 5.10 round 2 review). The same "just placed by me" rule as Windows' switch to Mirror ({@link
	 * PlacementDefault}); the move is a normal MoveHologram, which the server checks as any other.
	 */
	private void installBesideMirror() {
		PlacementDefault placed = new PlacementDefault();
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide() && player.getItemInHand(hand).is(local.carter.office.hologram.OfficeBlocks.HOLOGRAM_EMITTER_ITEM)) {
				net.minecraft.core.BlockPos bp = hit.getBlockPos();
				placed.used(bp.getX(), bp.getY(), bp.getZ(), System.nanoTime() / 1_000_000);
			}
			return net.minecraft.world.InteractionResult.PASS;
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.player == null || mc.getConnection() == null) {
				return;
			}
			java.util.UUID me = mc.player.getUUID();
			long now = System.nanoTime() / 1_000_000;
			for (HologramBlockEntity be : HologramTracker.loaded()) {
				HologramSpec spec = be.spec();
				net.minecraft.core.BlockPos pos = be.getBlockPos();
				if (spec == null || !placed.shouldSwitch(be.hologramId(), spec, me.equals(spec.owner()), pos.getX(), pos.getY(), pos.getZ(), now)) {
					continue;
				}
				boolean left = "left".equals(helper.displaySide());
				HologramBlockEntity mirror = nearestOwnMirror(me, pos);
				if (mirror != null) {
					net.minecraft.core.BlockPos mp = mirror.getBlockPos();
					BesideMirror.Placement at = BesideMirror.place(new local.carter.office.core.Vec3d(mp.getX(), mp.getY(), mp.getZ()),
							mirror.spec(), new local.carter.office.core.Vec3d(pos.getX(), pos.getY(), pos.getZ()), spec, left);
					if (at != null) {
						net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
								new local.carter.office.net.OfficePayloads.MoveHologram(pos, at.offX(), at.offY(), at.offZ(), at.yaw()));
						LOGGER.info("Office: display {} hologram placed {} of the Mirror", spec.displaySlot(), left ? "left" : "right");
					}
				}
				chat(Component.literal(BesideMirror.hint(spec.displaySlot(), left)).withStyle(ChatFormatting.AQUA));
			}
		});
	}

	/** The player's Mirror hologram nearest to {@code pos}, or null. */
	private static @Nullable HologramBlockEntity nearestOwnMirror(final java.util.UUID me, final net.minecraft.core.BlockPos pos) {
		HologramBlockEntity best = null;
		double bestDistance = Double.MAX_VALUE;
		for (HologramBlockEntity be : HologramTracker.loaded()) {
			HologramSpec s = be.spec();
			if (s == null || s.mode() != Mode.MIRROR || !me.equals(s.owner())) {
				continue;
			}
			double d = be.getBlockPos().distSqr(pos);
			if (d < bestDistance) {
				bestDistance = d;
				best = be;
			}
		}
		return best;
	}

	/** Once a second: an own Mirror hologram while Minecraft is in its own full-screen Space gets one chat hint. */
	private void mirrorHint(final Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			mirrorHint.leftWorld();
			return;
		}
		if (++mirrorHintTicks % 20 != 0) {
			return;
		}
		boolean ownMirror = false;
		for (HologramBlockEntity be : HologramTracker.loaded()) {
			HologramSpec spec = be.spec();
			if (spec != null && spec.mode() == Mode.MIRROR && be.isOwner(mc.player.getUUID())) {
				ownMirror = true;
				break;
			}
		}
		long handle = mc.getWindow().handle();
		if (mirrorHint.update(MacWindow.isFullscreen(handle), ownMirror)) {
			chat(Component.literal(MirrorHint.TEXT).withStyle(ChatFormatting.YELLOW));
		}
	}

	private void answered(final int asked, final boolean yes) {
		if (!setup.answered(asked, yes)) {
			if (!yes && asked == setup.session()) {
				chat(Component.literal("Your screens stay off for now. ").append(link("Set up screens", OfficeScreensCommand.Action.SETUP, "Download the helper app")));
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
					helper.requestPermissions();
					openSetupPages(SCREEN_RECORDING_PANE);
					chat(Component.literal("Opened System Settings → Privacy & Security → Screen Recording: switch on OfficeHelper."));
				} else if (!w.accessibility()) {
					helper.requestPermissions(); // macOS lists OfficeHelper under Accessibility and shows its prompt
					openSetupPages(ACCESSIBILITY_PANE);
					chat(Component.literal("Opened System Settings → Privacy & Security → Accessibility: switch on OfficeHelper. "
							+ "Your screens update by themselves."));
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
				missing.add("Accessibility (to click and type on your screens)");
			}
			chat(Component.literal("OfficeHelper needs macOS " + String.join(" and ", missing) + ". ")
					.append(link("Set up screens", OfficeScreensCommand.Action.SETUP, "Asks macOS again and opens System Settings"))
					.append(" ")
					.append(link("Check again", OfficeScreensCommand.Action.RETRY, "Restart the helper after switching it on")));
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

	private static MutableComponent link(final String text, final OfficeScreensCommand.Action action, final String hover) {
		return Component.literal("[" + text + "]").withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
				.withClickEvent(OfficeScreensCommand.click(action))
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
