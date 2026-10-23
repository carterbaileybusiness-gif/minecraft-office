package local.carter.office.client.stream;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import local.carter.office.core.Packetizer;
import local.carter.office.core.StreamHeader;

/**
 * The UDP path to the server's screen relay (Task 13 protocol), on one daemon thread.
 *
 * <p>Handshake: HELLO = header(HELLO) + token (26 bytes); the relay answers a challenge HELLO_ACK = header + 8-byte
 * nonce (26 bytes); we echo header(HELLO) + token + nonce (34 bytes) from the same socket; an 18-byte HELLO_ACK means
 * bound. HELLO is retried every {@link #RETRY_NANOS} while not bound; the whole handshake is repeated every
 * {@link #KEEPALIVE_NANOS} as a NAT keepalive. If a handshake hasn't completed within {@link #DEADLINE_NANOS},
 * {@link Listener#onStatus}(false) (the client switches to the payload fallback); a later success reports true.
 *
 * <p>Every other datagram from the relay (TILE / SIZE / CLEAR, no token) goes to {@link Listener#onDatagram}. The
 * token is never logged.
 */
public final class UdpLink implements AutoCloseable {
	public static final long RETRY_NANOS = TimeUnit.SECONDS.toNanos(1);
	public static final long DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(5);
	public static final long KEEPALIVE_NANOS = TimeUnit.SECONDS.toNanos(15);
	static final int POLL_MILLIS = 100;

	private static final Logger LOGGER = LogUtils.getLogger();

	public interface Listener {
		/** Bound (true) or not bound within the deadline / lost (false). Called on the link thread, on changes only. */
		void onStatus(boolean ok);

		/** A stream datagram from the relay (link thread). */
		void onDatagram(byte[] datagram);
	}

	private final DatagramSocket socket;
	private final byte[] token;
	private final Listener listener;
	private final LongSupplier clock;
	private final Thread thread;
	private volatile boolean bound;
	private volatile boolean closed;

	// link thread only
	private Boolean reported;
	private boolean handshaking;
	private long attemptStart;
	private long nextHello;
	private long nextKeepalive;

	private UdpLink(final DatagramSocket socket, final long token, final Listener listener, final LongSupplier clock) {
		this.socket = socket;
		this.token = ByteBuffer.allocate(Packetizer.TOKEN_BYTES).putLong(token).array();
		this.listener = listener;
		this.clock = clock;
		this.thread = new Thread(this::run, "office-stream-udp");
		this.thread.setDaemon(true);
	}

	/** Opens a socket to the relay and starts handshaking. */
	public static UdpLink open(final InetSocketAddress relay, final long token, final Listener listener) throws IOException {
		return open(relay, token, listener, System::nanoTime);
	}

	/** {@link #open(InetSocketAddress, long, Listener)} with an injected clock (tests). */
	public static UdpLink open(final InetSocketAddress relay, final long token, final Listener listener, final LongSupplier clock)
			throws IOException {
		DatagramSocket socket = new DatagramSocket();
		try {
			socket.connect(relay); // only the relay's datagrams are received
			socket.setSoTimeout(POLL_MILLIS);
		} catch (IOException | RuntimeException e) {
			socket.close();
			throw e;
		}
		UdpLink link = new UdpLink(socket, token, listener, clock);
		link.thread.start();
		return link;
	}

	public boolean bound() {
		return bound && !closed;
	}

	/** The token appended to every owner datagram. */
	byte[] token() {
		return token;
	}

	/** Sends one datagram (any thread). Errors are dropped: UDP is best effort. */
	public void send(final byte[] datagram) {
		if (closed) {
			return;
		}
		try {
			socket.send(new DatagramPacket(datagram, datagram.length));
		} catch (IOException e) {
			LOGGER.debug("[office] UDP send failed: {}", e.toString());
		}
	}

	@Override
	public void close() {
		closed = true;
		bound = false;
		socket.close();
		if (Thread.currentThread() != thread) {
			try {
				thread.join(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private void run() {
		byte[] buf = new byte[Packetizer.MAX_DATAGRAM + 64];
		DatagramPacket p = new DatagramPacket(buf, buf.length);
		startHandshake(clock.getAsLong());
		while (!closed) {
			try {
				p.setLength(buf.length);
				socket.receive(p);
				handle(Arrays.copyOf(p.getData(), p.getLength()));
			} catch (SocketTimeoutException | PortUnreachableException e) {
				// nothing arrived / the relay port isn't listening (yet): the timers below retry
			} catch (IOException e) {
				if (!closed) {
					LOGGER.warn("[office] Screen-relay UDP socket failed: {}", e.toString());
					setStatus(false);
				}
				break;
			} catch (Throwable t) {
				LOGGER.warn("[office] Screen-relay UDP datagram handling failed", t);
			}
			try {
				timers(clock.getAsLong());
			} catch (Throwable t) {
				LOGGER.warn("[office] Screen-relay UDP timer failed", t);
			}
		}
	}

	private void handle(final byte[] d) {
		var h = StreamHeader.decode(d);
		if (h.isEmpty()) {
			return;
		}
		if (h.get().type() == StreamHeader.TYPE_HELLO_ACK) {
			if (!handshaking) {
				return;
			}
			if (d.length == StreamHeader.HEADER_BYTES + Packetizer.TOKEN_BYTES) {
				// challenge: echo the nonce from this same socket
				byte[] echo = ByteBuffer.allocate(StreamHeader.HEADER_BYTES + 2 * Packetizer.TOKEN_BYTES)
						.put(hello()).put(token).put(d, StreamHeader.HEADER_BYTES, Packetizer.TOKEN_BYTES).array();
				send(echo);
			} else if (d.length == StreamHeader.HEADER_BYTES) {
				long now = clock.getAsLong();
				handshaking = false;
				nextKeepalive = now + KEEPALIVE_NANOS;
				bound = true;
				setStatus(true);
			}
			return;
		}
		listener.onDatagram(d);
	}

	private void timers(final long now) {
		if (!handshaking) {
			if (now - nextKeepalive >= 0) {
				startHandshake(now);
			}
			return;
		}
		if (now - nextHello >= 0) {
			sendHello(now);
		}
		if (now - attemptStart >= DEADLINE_NANOS) {
			bound = false;
			setStatus(false); // keep retrying every RETRY_NANOS; a later bind reports true
		}
	}

	private void startHandshake(final long now) {
		handshaking = true;
		attemptStart = now;
		sendHello(now);
	}

	private void sendHello(final long now) {
		nextHello = now + RETRY_NANOS;
		send(ByteBuffer.allocate(StreamHeader.HEADER_BYTES + Packetizer.TOKEN_BYTES).put(hello()).put(token).array());
	}

	private static byte[] hello() {
		return new StreamHeader(StreamHeader.TYPE_HELLO, 0, 0, 0, 0, 0, 0).encode();
	}

	private void setStatus(final boolean ok) {
		if (reported == null || reported != ok) {
			reported = ok;
			try {
				listener.onStatus(ok);
			} catch (Throwable t) {
				LOGGER.warn("[office] UDP status listener failed", t);
			}
		}
	}
}
