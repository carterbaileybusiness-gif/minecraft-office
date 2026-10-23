package local.carter.office.client.helper;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import local.carter.office.client.helper.HelperProtocol.FrameReader;
import local.carter.office.client.helper.HelperProtocol.Hello;
import local.carter.office.client.helper.HelperProtocol.HelperError;
import local.carter.office.client.helper.HelperProtocol.Message;
import local.carter.office.client.helper.HelperProtocol.Welcome;

/**
 * The mod's connection to the local OfficeHelper app: a Unix-domain socket {@code helper.sock} in the helper's
 * owner-only support folder, authenticated with the token from {@code helper.token} (0600) next to it.
 *
 * <p>One background thread ({@code office-helper-io}) connects, sends HELLO, waits for WELCOME and then delivers every
 * helper message to the {@link Listener} (on that thread). When the socket is missing or refuses the connection, the
 * helper is (re)launched through the {@link Launcher} (at most every {@link #RELAUNCH_INTERVAL_MS}). Failed attempts and
 * dropped connections are retried with a {@link Backoff} of 0.5 → 8 s that resets after each WELCOME. An
 * {@code ERROR busy} reply means a previous connection of ours is still ending (the helper serves one mod at a time); it
 * is retried the same way.
 */
public final class HelperConnection implements HelperChannel {
	private static final Logger LOGGER = LoggerFactory.getLogger("office");
	public static final long RELAUNCH_INTERVAL_MS = 10_000;
	private static final int READ_BUFFER = 256 * 1024;
	/** Outgoing messages waiting for the writer thread (they are tiny control messages). */
	public static final int MAX_QUEUED = 1024;
	/** How long {@link #close} lets queued messages drain before cutting the socket (in the background). */
	public static final long CLOSE_GRACE_MS = 2_000;

	/** Receives the helper's messages; all calls come from the connection's thread. */
	public interface Listener {
		/** WELCOME arrived: the connection is ready for {@link HelperConnection#send}. */
		void connected(Welcome welcome);

		void message(Message message);

		/** A connection that had reached WELCOME is gone (a reconnect follows unless closed). */
		void disconnected();
	}

	/** Starts the helper app (e.g. {@code /usr/bin/open -g OfficeHelper.app}). */
	@FunctionalInterface
	public interface Launcher {
		void launch() throws IOException;
	}

	@FunctionalInterface
	public interface Sleeper {
		void sleep(long millis) throws InterruptedException;
	}

	/** Retry delays: 0.5, 1, 2, 4, 8, 8, … seconds; {@link #reset} after a successful connection. */
	public static final class Backoff {
		public static final long MIN_MS = 500;
		public static final long MAX_MS = 8_000;
		private long next = MIN_MS;

		public long next() {
			long d = next;
			next = Math.min(MAX_MS, next * 2);
			return d;
		}

		public void reset() {
			next = MIN_MS;
		}
	}

	private final Path socket;
	private final Path tokenFile;
	private final int mcPid;
	private final @Nullable Launcher launcher;
	private final Listener listener;
	private final Sleeper sleeper;

	private volatile boolean running;
	private volatile boolean connected;
	/** The socket of the current attempt (also during the handshake), so {@link #close} can break a blocking read. */
	private volatile @Nullable SocketChannel channel;
	/** The connected session's writer; null outside a WELCOMEd connection. */
	private volatile @Nullable Writer writer;
	private @Nullable Thread thread;
	private long lastLaunchNanos;
	private boolean launched;

	/**
	 * Writes one connection's outgoing messages on its own thread, so {@link #send} never blocks the caller (the game's
	 * client thread) on a helper that stopped reading. When the queue overflows the connection is closed instead.
	 */
	private static final class Writer implements Runnable {
		private static final byte[] DRAIN_AND_CLOSE = new byte[0];
		private static final byte[] QUIT = new byte[0];
		final SocketChannel ch;
		final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(MAX_QUEUED + 1);
		final Thread thread;

		Writer(final SocketChannel ch) {
			this.ch = ch;
			this.thread = new Thread(this, "office-helper-writer");
			thread.setDaemon(true);
		}

		@Override
		public void run() {
			try {
				while (true) {
					byte[] b = queue.take();
					if (b == QUIT) {
						return;
					}
					if (b == DRAIN_AND_CLOSE) {
						closeQuietly(ch);
						return;
					}
					writeAll(ch, b);
				}
			} catch (IOException | InterruptedException e) {
				closeQuietly(ch); // the reader notices and reconnects
			}
		}

		/** Queues a message; false when {@link #MAX_QUEUED} are already waiting. */
		boolean offer(final byte[] b) {
			return queue.size() < MAX_QUEUED && queue.offer(b);
		}

		/** Ends the writer; queued messages are dropped (the connection is gone anyway). */
		void quit() {
			queue.clear();
			queue.offer(QUIT);
		}
	}

	public HelperConnection(final Path supportDir, final int mcPid, final @Nullable Launcher launcher, final Listener listener) {
		this(supportDir, mcPid, launcher, listener, Thread::sleep);
	}

	public HelperConnection(final Path supportDir, final int mcPid, final @Nullable Launcher launcher, final Listener listener,
			final Sleeper sleeper) {
		this.socket = supportDir.resolve("helper.sock");
		this.tokenFile = supportDir.resolve("helper.token");
		this.mcPid = mcPid;
		this.launcher = launcher;
		this.listener = listener;
		this.sleeper = sleeper;
	}

	@Override
	public synchronized void start() {
		if (thread != null) {
			return;
		}
		running = true;
		Thread t = new Thread(this::run, "office-helper-io");
		t.setDaemon(true);
		thread = t;
		t.start();
	}

	public boolean isConnected() {
		return connected;
	}

	/** Queues one message for the writer thread; never blocks. False when not connected, malformed, or the queue was full. */
	@Override
	public boolean send(final Message message) {
		Writer w = writer;
		if (w == null || !connected) {
			return false;
		}
		byte[] bytes;
		try {
			bytes = HelperProtocol.encode(message);
		} catch (HelperProtocol.ProtocolException e) {
			LOGGER.warn("Office helper: not sending a malformed {}: {}", message.getClass().getSimpleName(), e.getMessage());
			return false;
		}
		if (w.offer(bytes)) {
			return true;
		}
		LOGGER.warn("Office helper is not reading ({} messages queued); reconnecting", MAX_QUEUED);
		closeQuietly(w.ch); // the reader notices and reconnects
		return false;
	}

	/**
	 * Disconnects for good without blocking the caller: messages already queued (e.g. REMOVE_DISPLAY) are still written,
	 * then the socket closes; a helper that doesn't take them within {@link #CLOSE_GRACE_MS} is cut off by a background
	 * closer thread.
	 */
	@Override
	public void close() {
		Thread t;
		synchronized (this) {
			running = false;
			t = thread;
			thread = null;
		}
		Writer w = writer;
		if (w != null && w.queue.offer(Writer.DRAIN_AND_CLOSE)) {
			Thread closer = new Thread(() -> {
				try {
					w.thread.join(CLOSE_GRACE_MS);
				} catch (InterruptedException ignored) {
					// closing anyway
				}
				closeQuietly(w.ch);
				if (t != null) {
					t.interrupt();
				}
			}, "office-helper-close");
			closer.setDaemon(true);
			closer.start();
			return;
		}
		SocketChannel ch = channel;
		if (ch != null) {
			closeQuietly(ch);
		}
		if (t != null) {
			t.interrupt();
		}
	}

	private void run() {
		Backoff backoff = new Backoff();
		while (running) {
			attempt(backoff);
			if (!running) {
				break;
			}
			try {
				sleeper.sleep(backoff.next());
			} catch (InterruptedException e) {
				break;
			}
		}
	}

	private void attempt(final Backoff backoff) {
		SocketChannel ch;
		try {
			ch = SocketChannel.open(StandardProtocolFamily.UNIX);
		} catch (IOException e) {
			LOGGER.warn("Office helper: cannot open a socket: {}", e.toString());
			return;
		}
		try {
			ch.connect(UnixDomainSocketAddress.of(socket));
		} catch (IOException e) {
			closeQuietly(ch);
			maybeLaunch(); // no helper (socket missing) or a stale socket of one that died
			return;
		}
		channel = ch;
		if (!running) {
			closeQuietly(ch);
			channel = null;
			return;
		}
		Writer w = null;
		try {
			// The handshake is written here, on the connection thread (never the client thread).
			writeAll(ch, HelperProtocol.encode(new Hello(readToken(tokenFile), HelperProtocol.VERSION, mcPid)));
			FrameReader reader = FrameReader.forHelperMessages();
			ByteBuffer buf = ByteBuffer.allocate(READ_BUFFER);
			Message first = read(ch, reader, buf);
			if (first instanceof Welcome welcome) {
				w = new Writer(ch);
				w.thread.start();
				writer = w;
				connected = true;
				backoff.reset();
				LOGGER.info("Office helper connected (screen recording {}, accessibility {})", welcome.screenRecording(), welcome.accessibility());
				listener.connected(welcome);
				Message m;
				while ((m = read(ch, reader, buf)) != null) {
					listener.message(m);
				}
			} else if (first instanceof HelperError e && e.isBusy()) {
				LOGGER.info("Office helper is still busy with an earlier connection; retrying");
			} else if (first instanceof HelperError e) {
				LOGGER.warn("Office helper refused the connection: {} {}", e.code(), e.message());
			} else if (first == null) {
				LOGGER.debug("Office helper closed the connection before WELCOME (stale token?)");
			} else {
				LOGGER.warn("Office helper sent {} before WELCOME", first.getClass().getSimpleName());
			}
		} catch (IOException e) {
			if (running) {
				LOGGER.debug("Office helper connection ended: {}", e.toString());
			}
		} finally {
			channel = null;
			if (w != null) {
				writer = null;
				if (running) {
					w.quit();
					closeQuietly(ch);
				} else if (!w.queue.offer(Writer.DRAIN_AND_CLOSE)) {
					closeQuietly(ch);
				} // else the writer drains what close() left queued, then closes the socket
			} else {
				closeQuietly(ch);
			}
			if (connected) {
				connected = false;
				LOGGER.info("Office helper disconnected");
				listener.disconnected();
			}
		}
	}

	private void maybeLaunch() {
		if (launcher == null) {
			return;
		}
		long now = System.nanoTime();
		if (launched && now - lastLaunchNanos < TimeUnit.MILLISECONDS.toNanos(RELAUNCH_INTERVAL_MS)) {
			return;
		}
		launched = true;
		lastLaunchNanos = now;
		try {
			launcher.launch();
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Office helper: launch failed: {}", e.toString());
		}
	}

	/** The helper's token: 64 hex characters in a file only its owner can read or write. */
	static String readToken(final Path file) throws IOException {
		Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
		for (PosixFilePermission p : perms) {
			if (p != PosixFilePermission.OWNER_READ && p != PosixFilePermission.OWNER_WRITE) {
				throw new IOException("helper token file is not private (" + perms + ")");
			}
		}
		String token = new String(Files.readAllBytes(file), StandardCharsets.US_ASCII).trim();
		if (!token.matches("[0-9a-f]{64}")) {
			throw new IOException("helper token file is malformed");
		}
		return token;
	}

	private static @Nullable Message read(final SocketChannel ch, final FrameReader reader, final ByteBuffer buf) throws IOException {
		while (true) {
			Message m = reader.next();
			if (m != null) {
				return m;
			}
			buf.clear();
			int n = ch.read(buf);
			if (n < 0) {
				return null;
			}
			reader.push(buf.array(), 0, n);
		}
	}

	private static void writeAll(final SocketChannel ch, final byte[] bytes) throws IOException {
		ByteBuffer out = ByteBuffer.wrap(bytes);
		while (out.hasRemaining()) {
			ch.write(out);
		}
	}

	private static void closeQuietly(final SocketChannel ch) {
		try {
			ch.close();
		} catch (IOException ignored) {
			// closing
		}
	}
}
