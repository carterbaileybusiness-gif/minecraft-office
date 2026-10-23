package local.carter.office.client.helper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
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
import local.carter.office.client.helper.HelperProtocol.Welcome;
import local.carter.office.client.helper.HelperProtocol.WindowInfo;
import local.carter.office.client.helper.HelperProtocol.Windows;
import local.carter.office.client.helper.HelperProtocol.WindowRect;
import local.carter.office.client.helper.HelperProtocol.ZoomIn;
import local.carter.office.client.helper.HelperProtocol.ZoomOut;

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
	private final AtomicInteger nextSurface = new AtomicInteger(1);
	private final Set<MacHelperSource> attached = new CopyOnWriteArraySet<>();
	private final Map<Integer, MacHelperSource> bySurface = new ConcurrentHashMap<>();
	private final List<Runnable> windowListeners = new CopyOnWriteArrayList<>();
	private final List<Runnable> exitListeners = new CopyOnWriteArrayList<>();
	private final List<Runnable> statusListeners = new CopyOnWriteArrayList<>();

	// guarded by this
	private @Nullable HelperChannel channel;
	private final Map<Integer, Size> wanted = new HashMap<>();
	private final Map<Integer, Size> online = new HashMap<>();

	private volatile boolean setUp;
	private volatile boolean connected;
	private volatile @Nullable Welcome welcome;
	private volatile boolean permissionDenied;
	private volatile List<WindowInfo> windows = List.of();

	/**
	 * @param channelFactory makes the connection (a {@link HelperConnection} in the game) for this helper's listener
	 * @param clientThread runs window-list, exit-zoom and status callbacks (the Minecraft client thread in the game)
	 */
	public MacHelper(final Function<HelperConnection.Listener, HelperChannel> channelFactory, final Executor clientThread) {
		this.channelFactory = channelFactory;
		this.clientThread = clientThread;
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
			ch = channelFactory.apply(this);
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
				ch.send(new RemoveDisplay(slot));
			}
		}
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

	/** The helper said a display is gone ("display removed" / "no display"). */
	public synchronized void displayOffline(final int slot) {
		online.remove(slot);
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

	public boolean zoomIn(final int surface, final WindowRect mcWindowFrame) {
		return surface != 0 && send(new ZoomIn(surface, mcWindowFrame));
	}

	public boolean zoomOut() {
		return send(new ZoomOut());
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
		for (Map.Entry<Integer, Size> e : displays) {
			send(new CreateDisplay(e.getKey(), e.getValue().w(), e.getValue().h()));
		}
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
			case HelperError e -> LOGGER.warn("Office helper error {}: {}", e.code(), e.message());
			default -> LOGGER.debug("Office helper: ignoring {}", m.getClass().getSimpleName());
		}
	}

	@Override
	public void disconnected() {
		connected = false;
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
