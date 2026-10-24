package local.carter.office.client.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.Timeout;

import local.carter.office.client.helper.HelperProtocol.CreateDisplay;
import local.carter.office.client.helper.HelperProtocol.Display;
import local.carter.office.client.helper.HelperProtocol.FrameReader;
import local.carter.office.client.helper.HelperProtocol.Hello;
import local.carter.office.client.helper.HelperProtocol.HelperError;
import local.carter.office.client.helper.HelperProtocol.Message;
import local.carter.office.client.helper.HelperProtocol.Welcome;

/** HelperConnection against a fake helper on a real Unix-domain socket. */
class HelperConnectionTest {
	private static final String TOKEN = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

	private Path dir;
	private FakeHelper helper;
	private HelperConnection connection;
	private final List<Long> sleeps = Collections.synchronizedList(new ArrayList<>());
	private final Events events = new Events();

	@BeforeEach
	void setUp() throws IOException {
		dir = Files.createTempDirectory("oh");
	}

	@AfterEach
	void tearDown() throws IOException {
		if (connection != null) {
			connection.close();
		}
		if (helper != null) {
			helper.close();
		}
		try (var s = Files.list(dir)) {
			for (Path p : s.toList()) {
				Files.deleteIfExists(p);
			}
		}
		Files.deleteIfExists(dir);
	}

	private void writeToken() throws IOException {
		Path t = dir.resolve("helper.token");
		Files.writeString(t, TOKEN);
		Files.setPosixFilePermissions(t, PosixFilePermissions.fromString("rw-------"));
	}

	private HelperConnection connect(final HelperConnection.Launcher launcher) {
		connection = new HelperConnection(dir, 4242, launcher, events, ms -> {
			sleeps.add(ms);
			Thread.sleep(5);
		});
		connection.start();
		return connection;
	}

	// macOS helper only: POSIX 0600 token file (setPosixFilePermissions throws on Windows); the Mac helper is never reached on Windows.
	@Test
	@DisabledOnOs(OS.WINDOWS)
	void helloCarriesTheTokenAndWelcomeConnects() throws Exception {
		writeToken();
		helper = new FakeHelper(dir, n -> new Welcome(1, true, false));
		connect(null);

		Welcome w = events.connected.poll(5, TimeUnit.SECONDS);
		assertNotNull(w, "connected");
		assertTrue(w.screenRecording());
		assertFalse(w.accessibility());
		assertEquals(new Hello(TOKEN, HelperProtocol.VERSION, 4242, java.util.List.of("screens")), helper.hellos.get(0),
				"HELLO always announces the display map");
		assertTrue(connection.isConnected());
	}

	// macOS helper only: POSIX 0600 token file (setPosixFilePermissions throws on Windows); the Mac helper is never reached on Windows.
	@Test
	@DisabledOnOs(OS.WINDOWS)
	void sendAndReceiveAfterWelcome() throws Exception {
		writeToken();
		helper = new FakeHelper(dir, n -> new Welcome(1, true, true));
		connect(null);
		assertNotNull(events.connected.poll(5, TimeUnit.SECONDS));

		assertTrue(connection.send(new CreateDisplay(1, 1920, 1080)));
		assertEquals(new CreateDisplay(1, 1920, 1080), helper.received.poll(5, TimeUnit.SECONDS));
		helper.send(new Display(1, 5, 1920, 1080));
		assertEquals(new Display(1, 5, 1920, 1080), events.messages.poll(5, TimeUnit.SECONDS));
	}

	// macOS helper only: POSIX 0600 token file (setPosixFilePermissions throws on Windows); the Mac helper is never reached on Windows.
	@Test
	@DisabledOnOs(OS.WINDOWS)
	void busyHelperIsRetriedWithBackoff() throws Exception {
		writeToken();
		// First client: a stale connection is still ending -> ERROR busy, closed. Second: welcome.
		helper = new FakeHelper(dir, n -> n == 0 ? new HelperError(HelperError.BUSY, "a mod is already connected") : new Welcome(1, true, true));
		connect(null);

		assertNotNull(events.connected.poll(5, TimeUnit.SECONDS), "connected after the busy refusal");
		assertEquals(2, helper.hellos.size());
		assertEquals(500L, sleeps.get(0), "first retry after 0.5 s");
		assertEquals(0, events.disconnects.get(), "a refused attempt is not a disconnect");
	}

	// macOS helper only: POSIX 0600 token file (setPosixFilePermissions throws on Windows); the Mac helper is never reached on Windows.
	@Test
	@DisabledOnOs(OS.WINDOWS)
	void launchesTheHelperWhenNoSocketExists() throws Exception {
		AtomicInteger launches = new AtomicInteger();
		connect(() -> {
			launches.incrementAndGet();
			try {
				writeToken();
				helper = new FakeHelper(dir, n -> new Welcome(1, false, false));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});

		Welcome w = events.connected.poll(5, TimeUnit.SECONDS);
		assertNotNull(w);
		assertFalse(w.screenRecording());
		assertEquals(1, launches.get());
	}

	// macOS helper only: POSIX 0600 token file (setPosixFilePermissions throws on Windows); the Mac helper is never reached on Windows.
	@Test
	@DisabledOnOs(OS.WINDOWS)
	void reconnectsWhenTheHelperGoesAway() throws Exception {
		writeToken();
		helper = new FakeHelper(dir, n -> new Welcome(1, true, true));
		connect(null);
		assertNotNull(events.connected.poll(5, TimeUnit.SECONDS));

		helper.dropClients();
		assertTrue(waitFor(() -> events.disconnects.get() == 1), "disconnected");
		assertNotNull(events.connected.poll(5, TimeUnit.SECONDS), "reconnected");
		assertEquals(500L, sleeps.get(sleeps.size() - 1), "backoff was reset by the successful connection");
	}

	// macOS helper only: POSIX 0600 token file (setPosixFilePermissions throws on Windows); the Mac helper is never reached on Windows.
	@Test
	@DisabledOnOs(OS.WINDOWS)
	@Timeout(10)
	void sendAndCloseNeverBlockOnAHelperThatStoppedReading() throws Exception {
		writeToken();
		helper = new FakeHelper(dir, n -> new Welcome(1, true, true));
		helper.stall = true;
		connect(null);
		assertNotNull(events.connected.poll(5, TimeUnit.SECONDS));

		long t0 = System.nanoTime();
		boolean refused = false;
		int sent = 0;
		while (!refused && sent < 500_000) {
			refused = !connection.send(new CreateDisplay(1, 1920, 1080));
			sent++;
		}
		long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
		assertTrue(refused, "a full queue refuses instead of blocking");
		assertTrue(ms < 2_000, "sends returned at once (" + ms + " ms for " + sent + ")");
		assertTrue(waitFor(() -> events.disconnects.get() >= 1), "the stuck connection is dropped (and retried)");
		assertNotNull(events.connected.poll(5, TimeUnit.SECONDS), "reconnected");

		// Fill the new connection's queue again, then close: close() must not wait for the stuck helper.
		for (int i = 0; i < HelperConnection.MAX_QUEUED / 2; i++) {
			connection.send(new CreateDisplay(1, 1920, 1080));
		}
		long t1 = System.nanoTime();
		connection.close();
		assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t1) < 200, "close() returns at once");
		assertTrue(waitFor(() -> !connection.isConnected()), "and the socket is cut in the background");
	}

	@Test
	void backoffDoublesFromHalfASecondToEightAndResets() {
		HelperConnection.Backoff b = new HelperConnection.Backoff();
		assertEquals(List.of(500L, 1000L, 2000L, 4000L, 8000L, 8000L), List.of(b.next(), b.next(), b.next(), b.next(), b.next(), b.next()));
		b.reset();
		assertEquals(500L, b.next());
	}

	// macOS helper only: POSIX 0600 token file (setPosixFilePermissions throws on Windows); the Mac helper is never reached on Windows.
	@Test
	@DisabledOnOs(OS.WINDOWS)
	void tokenMustBeOwnerOnly() throws Exception {
		Path t = dir.resolve("helper.token");
		Files.writeString(t, TOKEN);
		Files.setPosixFilePermissions(t, PosixFilePermissions.fromString("rw-r--r--"));
		assertThrows(IOException.class, () -> HelperConnection.readToken(t));
		Files.setPosixFilePermissions(t, PosixFilePermissions.fromString("rw-------"));
		assertEquals(TOKEN, HelperConnection.readToken(t));
	}

	@Test
	void closeStopsRetrying() throws Exception {
		connect(null);
		assertTrue(waitFor(() -> sleeps.size() >= 2));
		connection.close();
		int n = sleeps.size();
		Thread.sleep(100);
		assertTrue(sleeps.size() <= n + 1, "no more attempts after close");
		assertFalse(connection.isConnected());
	}

	static boolean waitFor(final java.util.function.BooleanSupplier cond) throws InterruptedException {
		long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < end) {
			if (cond.getAsBoolean()) {
				return true;
			}
			Thread.sleep(5);
		}
		return cond.getAsBoolean();
	}

	static final class Events implements HelperConnection.Listener {
		final BlockingQueue<Welcome> connected = new LinkedBlockingQueue<>();
		final BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
		final AtomicInteger disconnects = new AtomicInteger();

		@Override
		public void connected(final Welcome welcome) {
			connected.add(welcome);
		}

		@Override
		public void message(final Message message) {
			messages.add(message);
		}

		@Override
		public void disconnected() {
			disconnects.incrementAndGet();
		}
	}

	/** Accepts clients, checks HELLO, answers with {@code reply.apply(clientIndex)} (ERROR replies close the client). */
	static final class FakeHelper implements AutoCloseable {
		final List<Hello> hellos = new CopyOnWriteArrayList<>();
		volatile boolean stall;
		final BlockingQueue<Message> received = new LinkedBlockingQueue<>();
		private final ServerSocketChannel server;
		private final List<SocketChannel> clients = new CopyOnWriteArrayList<>();
		private final Thread acceptor;

		FakeHelper(final Path dir, final Function<Integer, Message> reply) throws IOException {
			Path sock = dir.resolve("helper.sock");
			Files.deleteIfExists(sock);
			server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
			server.bind(UnixDomainSocketAddress.of(sock));
			acceptor = new Thread(() -> {
				int n = 0;
				while (server.isOpen()) {
					try {
						SocketChannel c = server.accept();
						int index = n++;
						Thread t = new Thread(() -> serve(c, reply.apply(index)), "fake-helper-client");
						t.setDaemon(true);
						t.start();
					} catch (IOException e) {
						return;
					}
				}
			}, "fake-helper-accept");
			acceptor.setDaemon(true);
			acceptor.start();
		}

		private void serve(final SocketChannel c, final Message reply) {
			FrameReader reader = FrameReader.forModMessages();
			ByteBuffer buf = ByteBuffer.allocate(4096);
			try (c) {
				Message first = read(c, reader, buf);
				if (!(first instanceof Hello h)) {
					return;
				}
				hellos.add(h);
				write(c, reply);
				if (reply instanceof HelperError) {
					return;
				}
				clients.add(c);
				Message m;
				while ((m = read(c, reader, buf)) != null) {
					received.add(m);
					while (stall && c.isOpen()) {
						try {
							Thread.sleep(10); // a hung helper: stops reading, its socket buffer fills
						} catch (InterruptedException e) {
							return;
						}
					}
				}
			} catch (IOException e) {
				// client went away
			}
		}

		void send(final Message m) throws IOException {
			for (SocketChannel c : clients) {
				write(c, m);
			}
		}

		void dropClients() throws IOException {
			for (SocketChannel c : clients) {
				c.close();
			}
			clients.clear();
		}

		private static Message read(final SocketChannel c, final FrameReader reader, final ByteBuffer buf) throws IOException {
			while (true) {
				Message m = reader.next();
				if (m != null) {
					return m;
				}
				buf.clear();
				int n = c.read(buf);
				if (n < 0) {
					return null;
				}
				reader.push(buf.array(), 0, n);
			}
		}

		private static synchronized void write(final SocketChannel c, final Message m) throws IOException {
			ByteBuffer out = ByteBuffer.wrap(HelperProtocol.encode(m));
			while (out.hasRemaining()) {
				c.write(out);
			}
		}

		@Override
		public void close() throws IOException {
			server.close();
			dropClients();
		}
	}
}
