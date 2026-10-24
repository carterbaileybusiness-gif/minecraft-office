package local.carter.office.client.helper;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import local.carter.office.client.capture.MacHelperSource;
import local.carter.office.client.capture.Target;
import local.carter.office.client.helper.HelperProtocol.CreateDisplay;
import local.carter.office.client.helper.HelperProtocol.Display;
import local.carter.office.client.helper.HelperProtocol.Ended;
import local.carter.office.client.helper.HelperProtocol.ExitZoom;
import local.carter.office.client.helper.HelperProtocol.Frame;
import local.carter.office.client.helper.HelperProtocol.HelperError;
import local.carter.office.client.helper.HelperProtocol.ListWindows;
import local.carter.office.client.helper.HelperProtocol.Message;
import local.carter.office.client.helper.HelperProtocol.Patch;
import local.carter.office.client.helper.HelperProtocol.RemoveDisplay;
import local.carter.office.client.helper.HelperProtocol.RequestPermissions;
import local.carter.office.client.helper.HelperProtocol.Welcome;
import local.carter.office.client.helper.HelperProtocol.WindowInfo;
import local.carter.office.client.helper.HelperProtocol.Windows;
import local.carter.office.client.helper.HelperProtocol.WindowRect;
import local.carter.office.client.helper.HelperProtocol.ZoomIn;
import local.carter.office.client.helper.HelperProtocol.ZoomOut;
import local.carter.office.client.helper.HelperProtocol.Click;
import local.carter.office.client.helper.HelperProtocol.DragDelta;
import local.carter.office.client.helper.HelperProtocol.DragEnded;
import local.carter.office.client.helper.HelperProtocol.ExitTyping;
import local.carter.office.client.helper.HelperProtocol.PointerButton;
import local.carter.office.client.helper.HelperProtocol.PointerDown;
import local.carter.office.client.helper.HelperProtocol.PointerMove;
import local.carter.office.client.helper.HelperProtocol.PointerUp;
import local.carter.office.client.helper.HelperProtocol.Scroll;
import local.carter.office.client.helper.HelperProtocol.TypingExit;
import local.carter.office.client.helper.HelperProtocol.AppIcon;
import local.carter.office.client.helper.HelperProtocol.AppIcons;
import local.carter.office.client.helper.HelperProtocol.AppInfo;
import local.carter.office.client.helper.HelperProtocol.AppResult;
import local.carter.office.client.helper.HelperProtocol.Apps;
import local.carter.office.client.helper.HelperProtocol.Icons;
import local.carter.office.client.helper.HelperProtocol.ListApps;
import local.carter.office.client.helper.HelperProtocol.OpenApp;
import local.carter.office.client.pointer.PointerPoint;

/**
 * The mod's model of the Mac helper session: connection state and permissions (from WELCOME), the virtual displays the
 * player's holograms asked for (re-created after every reconnect), the window list, and routing of FRAME / PATCH /
 * ENDED to the {@link MacHelperSource} that owns each surface. No Minecraft classes, so it is unit-testable.
 *
 * <p>Threads: {@link HelperConnection.Listener} calls come from the connection thread; the rest from any thread. This
 * class never calls a source while holding its own monitor (sources call into it while holding theirs).
 */
public final class MacHelper implements HelperConnection.Listener {
	private static final Logger LOGGER = LoggerFactory.getLogger("office");

	public enum Status {
		/** Not started (outside a world). */
		STOPPED,
		/** The helper is not installed / the player said "Not now" / the install failed. */
		NOT_SET_UP,
		/** Waiting for the helper to start or accept us. */
		CONNECTING,
		READY,
		/** Connected, but macOS Screen Recording is not granted to the helper. */
		NO_PERMISSION
	}

	private record Size(int w, int h) {}

	private final Function<HelperConnection.Listener, HelperChannel> channelFactory;
	private final Executor clientThread;
	private final Scheduler scheduler;
	private final AtomicInteger nextSurface = new AtomicInteger(1);
	private final Set<MacHelperSource> attached = new CopyOnWriteArraySet<>();
	private final Map<Integer, MacHelperSource> bySurface = new ConcurrentHashMap<>();
	private final List<Runnable> windowListeners = new CopyOnWriteArrayList<>();
	private final List<Runnable> exitListeners = new CopyOnWriteArrayList<>();
	private final List<Runnable> statusListeners = new CopyOnWriteArrayList<>();
	private final List<Consumer<HelperError>> errorListeners = new CopyOnWriteArrayList<>();
	private final List<Consumer<String>> typingExitListeners = new CopyOnWriteArrayList<>();
	private final List<Consumer<String>> dragEndedListeners = new CopyOnWriteArrayList<>();
	/** DRAG_DELTA summed until {@link #drainDragDelta}: {dx, dy}. */
	private final long[] dragDelta = new long[2];

	// guarded by this
	private @Nullable HelperChannel channel;
	private final Map<Integer, Size> wanted = new HashMap<>();
	private final Map<Integer, Size> online = new HashMap<>();

	private volatile boolean setUp;
	private volatile boolean connected;
	private volatile @Nullable Welcome welcome;
	private volatile boolean permissionDenied;
	private volatile List<WindowInfo> windows = List.of();
	/** The installed apps (bundle id → name, A–Z), asked for on every connect; null until the first APPS. */
	private volatile @Nullable Map<String, String> apps;
	/** App icons (PNG) by bundle id, and the ones asked for (each once per helper launch). */
	private final Map<String, byte[]> icons = new ConcurrentHashMap<>();
	private final Set<String> iconsAsked = ConcurrentHashMap.newKeySet();
	private final List<Consumer<AppResult>> appResultListeners = new CopyOnWriteArrayList<>();
	/** Icon size asked of the helper (pixels): crisp on a big hologram. */
	public static final int ICON_PX = 128;

	/**
	 * @param channelFactory makes the connection (a {@link HelperConnection} in the game) for this helper's listener
	 * @param clientThread runs window-list, exit-zoom and status callbacks (the Minecraft client thread in the game)
	 */
	public MacHelper(final Function<HelperConnection.Listener, HelperChannel> channelFactory, final Executor clientThread) {
		this(channelFactory, clientThread, DEFAULT_SCHEDULER);
	}

	/** @param scheduler runs delayed retries (display re-requests); a manual one in tests */
	public MacHelper(final Function<HelperConnection.Listener, HelperChannel> channelFactory, final Executor clientThread,
			final Scheduler scheduler) {
		this.channelFactory = channelFactory;
		this.clientThread = clientThread;
		this.scheduler = scheduler;
	}

	/** Runs {@code task} after {@code delayMs} on some background thread. */
	@FunctionalInterface
	public interface Scheduler {
		void schedule(Runnable task, long delayMs);
	}

	private static final Scheduler DEFAULT_SCHEDULER = new Scheduler() {
		private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "office-helper-retry");
			t.setDaemon(true);
			return t;
		});

		@Override
		public void schedule(final Runnable task, final long delayMs) {
			exec.schedule(task, delayMs, TimeUnit.MILLISECONDS);
		}
	};

	public void schedule(final Runnable task, final long delayMs) {
		scheduler.schedule(task, delayMs);
	}

	/**
	 * Forwards one channel's callbacks, and only while that channel is the current one: a closed connection's thread
	 * may still report its own end after a restart has already connected again.
	 */
	private final class Link implements HelperConnection.Listener {
		private @Nullable HelperChannel ch;

		private boolean current() {
			synchronized (MacHelper.this) {
				return ch != null && channel == ch;
			}
		}

		@Override
		public void connected(final Welcome w) {
			if (current()) {
				MacHelper.this.connected(w);
			}
		}

		@Override
		public void message(final Message m) {
			if (current()) {
				MacHelper.this.message(m);
			}
		}

		@Override
		public void disconnected() {
			if (current()) {
				MacHelper.this.disconnected();
			}
		}
	}

	// ---------------------------------------------------------------- lifecycle

	/** Whether a helper app is available (installed or dev override); until then the status is NOT_SET_UP. */
	public void setSetUp(final boolean value) {
		boolean changed = setUp != value;
		setUp = value;
		if (changed) {
			fireStatus();
		}
	}

	/** Connects (launching the helper as needed); no-op when already started. */
	public void start() {
		HelperChannel ch;
		synchronized (this) {
			if (channel != null) {
				return;
			}
			Link link = new Link();
			ch = channelFactory.apply(link);
			link.ch = ch;
			channel = ch;
		}
		ch.start();
		fireStatus();
	}

	public synchronized boolean started() {
		return channel != null;
	}

	/** Leaving the world: removes every display, disconnects (the helper then quits). */
	public void stop() {
		HelperChannel ch;
		List<Integer> slots;
		synchronized (this) {
			ch = channel;
			channel = null;
			slots = List.copyOf(wanted.keySet());
			wanted.clear();
			online.clear();
		}
		if (ch == null) {
			return;
		}
		if (connected) {
			for (int slot : slots) {
				ch.send(new RemoveDisplay(slot)); // nothing moved on join, so nothing moves back (spec 5.10)
			}
		}
		iconsAsked.clear();
		ch.close();
		boolean was = connected;
		connected = false;
		welcome = null;
		bySurface.clear();
		if (was) {
			for (MacHelperSource s : attached) {
				s.helperDisconnected();
			}
		}
		fireStatus();
	}

	/** Reconnect from scratch (the helper quits and is relaunched): picks up newly granted permissions. */
	public void restart() {
		Map<Integer, Size> keep;
		synchronized (this) {
			keep = Map.copyOf(wanted);
		}
		stop();
		synchronized (this) {
			wanted.putAll(keep);
		}
		start();
	}

	public Status status() {
		if (!setUp) {
			return Status.NOT_SET_UP;
		}
		if (!started()) {
			return Status.STOPPED;
		}
		Welcome w = welcome;
		if (!connected || w == null) {
			return Status.CONNECTING;
		}
		return w.screenRecording() && !permissionDenied ? Status.READY : Status.NO_PERMISSION;
	}

	public boolean isConnected() {
		return connected;
	}

	public @Nullable Welcome welcome() {
		return welcome;
	}

	public boolean send(final Message m) {
		HelperChannel ch;
		synchronized (this) {
			ch = channel;
		}
		return ch != null && connected && ch.send(m);
	}

	// ---------------------------------------------------------------- sources and displays

	public void attach(final MacHelperSource source) {
		attached.add(source);
	}

	public void detach(final MacHelperSource source) {
		attached.remove(source);
		bySurface.values().removeIf(s -> s == source);
	}

	/** A fresh surface id routed to {@code source}. */
	public int newSurface(final MacHelperSource source) {
		int id = nextSurface.getAndIncrement();
		bySurface.put(id, source);
		return id;
	}

	public void releaseSurface(final int surface) {
		bySurface.remove(surface);
	}

	/**
	 * True when display {@code slot} is online at w×h. Otherwise asks the helper for it (CREATE_DISPLAY, unless that
	 * exact display is already being created) and the caller waits for {@link MacHelperSource#displayOnline}.
	 */
	public boolean ensureDisplay(final int slot, final int w, final int h) {
		Size want = new Size(w, h);
		synchronized (this) {
			if (want.equals(online.get(slot))) {
				return true;
			}
			if (want.equals(wanted.get(slot))) {
				return false;
			}
			wanted.put(slot, want);
			online.remove(slot);
		}
		send(new CreateDisplay(slot, w, h));
		return false;
	}

	/**
	 * The helper said a display is gone ("display removed" / "no display"): forget it, so the next
	 * {@link #ensureDisplay} for that slot asks for it again.
	 */
	public synchronized void displayOffline(final int slot) {
		online.remove(slot);
		wanted.remove(slot);
	}

	/** Surface currently capturing {@code target}, or 0. */
	public int surfaceFor(final Target target) {
		for (MacHelperSource s : attached) {
			if (target.equals(s.target())) {
				int surface = s.surface();
				if (surface != 0) {
					return surface;
				}
			}
		}
		return 0;
	}

	/** A capture of this helper answered "no permission" (Screen Recording revoked or never granted). */
	public void permissionDenied() {
		if (!permissionDenied) {
			permissionDenied = true;
			fireStatus();
		}
	}

	// ---------------------------------------------------------------- in-world use (spec 5.9, 5.10)

	/** The picture size of a Mirror point (slot 0, the main display); null for a virtual display's pixel. */
	private static @Nullable Integer w(final PointerPoint p) {
		return p.onMain() ? p.w() : null;
	}

	private static @Nullable Integer h(final PointerPoint p) {
		return p.onMain() ? p.h() : null;
	}

	public boolean pointerDown(final PointerPoint p) {
		return send(new PointerDown(p.slot(), p.x(), p.y(), PointerButton.LEFT, w(p), h(p)));
	}

	public boolean pointerMove(final PointerPoint p) {
		return send(new PointerMove(p.slot(), p.x(), p.y(), w(p), h(p)));
	}

	public boolean pointerUp(final PointerPoint p) {
		return send(new PointerUp(p.slot(), p.x(), p.y(), PointerButton.LEFT, w(p), h(p)));
	}

	public boolean click(final PointerPoint p, final PointerButton button) {
		return send(new Click(p.slot(), p.x(), p.y(), button, w(p), h(p)));
	}

	public boolean scroll(final PointerPoint p, final int dx, final int dy) {
		return send(new Scroll(p.slot(), p.x(), p.y(), dx, dy, w(p), h(p)));
	}

	// ---------------------------------------------------------------- the mini Dock (spec 5.10)

	/** The installed apps (bundle id → name), or null until the helper has listed them. */
	public @Nullable Map<String, String> apps() {
		return apps;
	}

	/** PNG bytes of an app icon, or null until it arrived. */
	public byte @Nullable [] icon(final String bundle) {
		return icons.get(bundle);
	}

	/** Asks for the icons not asked for yet (in batches the helper accepts). */
	public void requestIcons(final List<String> bundles) {
		if (!connected) {
			return;
		}
		List<String> ask = new ArrayList<>();
		for (String b : bundles) {
			if (HelperProtocol.isBundleId(b) && iconsAsked.add(b)) {
				ask.add(b);
			}
		}
		for (int i = 0; i < ask.size(); i += 64) {
			send(new AppIcons(List.copyOf(ask.subList(i, Math.min(ask.size(), i + 64))), ICON_PX));
		}
	}

	/** Opens an app ({@code bundle}) or a web page ({@code url}) onto slot's display; false when refused locally. */
	public boolean openApp(final int slot, final @Nullable String bundle, final @Nullable String url) {
		OpenApp m = new OpenApp(slot, bundle, url);
		String problem = HelperProtocol.openAppProblem(m);
		if (problem != null) {
			LOGGER.warn("Office: not opening {}: {}", bundle != null ? bundle : url, problem);
			return false;
		}
		return send(m);
	}

	/** Called on the client thread with every APP_RESULT. */
	public void onAppResult(final Consumer<AppResult> listener) {
		appResultListeners.add(listener);
	}

	public boolean exitTyping() {
		return send(new ExitTyping());
	}

	/** Physical mouse motion during a helper-driven drag since the last call, {dx, dy} in points. */
	public int[] drainDragDelta() {
		synchronized (dragDelta) {
			int[] out = {(int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, dragDelta[0])),
					(int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, dragDelta[1]))};
			dragDelta[0] = 0;
			dragDelta[1] = 0;
			return out;
		}
	}

	/** Called on the client thread with TYPING_EXIT's reason. */
	public void onTypingExit(final Consumer<String> listener) {
		typingExitListeners.add(listener);
	}

	/** Called on the client thread with DRAG_ENDED's reason. */
	public void onDragEnded(final Consumer<String> listener) {
		dragEndedListeners.add(listener);
	}

	// ---------------------------------------------------------------- windows and zoom

	/** Asks the helper for the window list (it asks macOS for Screen Recording the first time). */
	public void listWindows() {
		send(new ListWindows());
	}

	public List<WindowInfo> windows() {
		return windows;
	}

	/** Called on the client thread after each window list arrives. */
	public void onWindows(final Runnable r) {
		windowListeners.add(r);
	}

	/** Called on the client thread when the helper's exit hotkey fires (EXIT_ZOOM). */
	public void onExitZoom(final Runnable r) {
		exitListeners.add(r);
	}

	/** Called on the client thread when {@link #status()} may have changed. */
	public void onStatusChange(final Runnable r) {
		statusListeners.add(r);
	}

	/** Called on the client thread with every ERROR the helper sends (e.g. a refused ZOOM_IN). */
	public void onError(final Consumer<HelperError> listener) {
		errorListeners.add(listener);
	}

	public boolean zoomIn(final int surface, final WindowRect mcWindowFrame) {
		return surface != 0 && send(new ZoomIn(surface, mcWindowFrame));
	}

	public boolean zoomOut() {
		return send(new ZoomOut());
	}

	/** Asks the helper to show macOS's prompts for missing permissions (it answers with a fresh WELCOME). */
	public boolean requestPermissions() {
		return send(new RequestPermissions());
	}

	// ---------------------------------------------------------------- HelperConnection.Listener (connection thread)

	@Override
	public void connected(final Welcome w) {
		List<Map.Entry<Integer, Size>> displays;
		synchronized (this) {
			online.clear();
			displays = List.copyOf(wanted.entrySet());
		}
		welcome = w;
		permissionDenied = false;
		connected = true;
		iconsAsked.clear(); // a new helper: ask again for what the Dock needs
		for (Map.Entry<Integer, Size> e : displays) {
			send(new CreateDisplay(e.getKey(), e.getValue().w(), e.getValue().h()));
		}
		send(new ListApps()); // for the Dock: which of its apps are installed, and the "All apps" search
		for (MacHelperSource s : attached) {
			s.helperConnected();
		}
		fireStatus();
	}

	@Override
	public void message(final Message m) {
		switch (m) {
			case Display d -> {
				synchronized (this) {
					online.put(d.slot(), new Size(d.w(), d.h()));
				}
				for (MacHelperSource s : attached) {
					s.displayOnline(d.slot(), d.w(), d.h());
				}
			}
			case Frame f -> {
				MacHelperSource s = bySurface.get(f.surface());
				if (s != null) {
					s.onFrame(f);
				}
			}
			case Patch p -> {
				MacHelperSource s = bySurface.get(p.surface());
				if (s != null) {
					s.onPatch(p);
				}
			}
			case Ended e -> {
				MacHelperSource s = bySurface.get(e.surface());
				if (s != null) {
					s.onEnded(e.surface(), e.reason());
				}
			}
			case Windows w -> {
				windows = List.copyOf(w.windows());
				for (Runnable r : windowListeners) {
					clientThread.execute(r);
				}
			}
			case ExitZoom x -> {
				for (Runnable r : exitListeners) {
					clientThread.execute(r);
				}
			}
			case Welcome w -> permissionsChanged(w);
			case DragDelta d -> {
				synchronized (dragDelta) {
					dragDelta[0] += d.dx();
					dragDelta[1] += d.dy();
				}
			}
			case DragEnded e -> {
				for (Consumer<String> l : dragEndedListeners) {
					clientThread.execute(() -> l.accept(e.reason()));
				}
			}
			case TypingExit t -> {
				for (Consumer<String> l : typingExitListeners) {
					clientThread.execute(() -> l.accept(t.reason()));
				}
			}
			case Apps a -> {
				Map<String, String> byBundle = new LinkedHashMap<>();
				for (AppInfo i : a.apps()) {
					byBundle.putIfAbsent(i.bundle(), i.name());
				}
				apps = Collections.unmodifiableMap(byBundle);
			}
			case Icons i -> {
				for (AppIcon icon : i.icons()) {
					try {
						byte[] png = Base64.getDecoder().decode(icon.png());
						if (png.length > 0 && png.length <= 512 * 1024) {
							icons.put(icon.bundle(), png);
						}
					} catch (IllegalArgumentException e) {
						LOGGER.debug("Office helper: bad icon for {}", icon.bundle());
					}
				}
			}
			case AppResult r -> {
				LOGGER.info("Office Dock: {} → {} ({})", r.target(), r.ok() ? "ok" : "failed", r.message());
				for (Consumer<AppResult> l : appResultListeners) {
					clientThread.execute(() -> l.accept(r));
				}
			}
			case HelperError e -> {
				LOGGER.warn("Office helper error {}: {}", e.code(), e.message());
				for (Consumer<HelperError> l : errorListeners) {
					clientThread.execute(() -> l.accept(e));
				}
			}
			default -> LOGGER.debug("Office helper: ignoring {}", m.getClass().getSimpleName());
		}
	}

	/**
	 * A WELCOME after the handshake: a permission changed while connected (the helper re-checks every 2 s). A new
	 * Screen Recording grant restarts the captures that had ended with "no permission".
	 */
	private void permissionsChanged(final Welcome w) {
		Welcome old = welcome;
		welcome = w;
		LOGGER.info("Office helper permissions now: screen recording {}, accessibility {}", w.screenRecording(), w.accessibility());
		if (w.screenRecording() && (permissionDenied || old == null || !old.screenRecording())) {
			permissionDenied = false;
			for (MacHelperSource s : attached) {
				s.permissionGranted();
			}
		}
		fireStatus();
	}

	@Override
	public void disconnected() {
		connected = false;
		drainDragDelta();
		// The helper died: a drag it was driving or typing mode it was guarding is over; the mod must not wait for it.
		for (Consumer<String> l : dragEndedListeners) {
			clientThread.execute(() -> l.accept("cancelled"));
		}
		for (Consumer<String> l : typingExitListeners) {
			clientThread.execute(() -> l.accept("helper"));
		}
		synchronized (this) {
			online.clear();
		}
		bySurface.clear();
		for (MacHelperSource s : attached) {
			s.helperDisconnected();
		}
		fireStatus();
	}

	private void fireStatus() {
		for (Runnable r : statusListeners) {
			clientThread.execute(r);
		}
	}
}
