package local.carter.office.stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import local.carter.office.core.Packetizer;
import local.carter.office.core.StreamHeader;

/** Real UDP sockets on 127.0.0.1 against {@link StreamRelayServer}; routes are set directly (stub router). */
class RelayLoopbackTest {
	private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
	private static final int SID = 7;

	private final AtomicLong clock = new AtomicLong(1_000_000_000L);
	private final List<DatagramSocket> sockets = new ArrayList<>();
	private StreamRelayServer relay;

	private final UUID owner = UUID.randomUUID();
	private final UUID v1 = UUID.randomUUID();
	private final UUID v2 = UUID.randomUUID();

	@BeforeEach
	void start() {
		relay = startRelay(2000);
	}

	@AfterEach
	void stop() {
		sockets.forEach(DatagramSocket::close);
		if (relay != null) {
			relay.close();
		}
	}

	private StreamRelayServer startRelay(int kbps) {
		StreamRelayServer r = new StreamRelayServer(kbps, clock::get);
		r.start(new InetSocketAddress(LOOPBACK, 0));
		assertTrue(r.udpPort() > 0, "relay bound");
		return r;
	}

	private DatagramSocket socket() throws Exception {
		DatagramSocket s = new DatagramSocket(new InetSocketAddress(LOOPBACK, 0));
		s.setSoTimeout(1000);
		sockets.add(s);
		return s;
	}

	private void send(DatagramSocket s, byte[] data) throws Exception {
		s.send(new DatagramPacket(data, data.length, new InetSocketAddress(LOOPBACK, relay.udpPort())));
	}

	private static byte[] receive(DatagramSocket s, int timeoutMs) throws Exception {
		s.setSoTimeout(timeoutMs);
		byte[] buf = new byte[2048];
		DatagramPacket p = new DatagramPacket(buf, buf.length);
		try {
			s.receive(p);
		} catch (SocketTimeoutException e) {
			return null;
		}
		return Arrays.copyOf(p.getData(), p.getLength());
	}

	private static byte[] tokenBytes(long token) {
		return ByteBuffer.allocate(Packetizer.TOKEN_BYTES).putLong(token).array();
	}

	private static byte[] hello(long token) {
		return ByteBuffer.allocate(StreamHeader.HEADER_BYTES + Packetizer.TOKEN_BYTES)
				.put(new StreamHeader(StreamHeader.TYPE_HELLO, 0, 0, 0, 0, 0, 0).encode())
				.putLong(token).array();
	}

	/** One single-part TILE datagram (header + payload + token). */
	private static byte[] tile(int streamId, int seq, int payloadBytes, long token) {
		byte[] jpeg = new byte[payloadBytes];
		for (int i = 0; i < jpeg.length; i++) {
			jpeg[i] = (byte) (i * 31 + seq);
		}
		List<byte[]> parts = new Packetizer().split(new StreamHeader(StreamHeader.TYPE_TILE, streamId, seq, 1, 2, 0, 1), jpeg,
				tokenBytes(token));
		assertEquals(1, parts.size());
		return parts.get(0);
	}

	private static byte[] stripToken(byte[] datagram) {
		return Arrays.copyOf(datagram, datagram.length - Packetizer.TOKEN_BYTES);
	}

	/** Registers {@code id} with {@code token} and completes a HELLO from {@code s}; asserts the ACK. */
	private void bind(UUID id, long token, DatagramSocket s) throws Exception {
		relay.register(id, token);
		send(s, hello(token));
		byte[] ack = receive(s, 2000);
		assertNotNull(ack, "HELLO_ACK received");
		assertEquals(StreamHeader.TYPE_HELLO_ACK, StreamHeader.decode(ack).orElseThrow().type());
	}

	private void routeOwnerToViewers() {
		relay.setRoutes(Map.of(SID, new StreamRelayServer.Route(owner,
				List.of(new StreamRelayServer.Viewer(v1, null), new StreamRelayServer.Viewer(v2, null)))));
	}

	@Test
	void validHelloGetsAck() throws Exception {
		DatagramSocket s = socket();
		bind(owner, 0x1234_5678_9ABCL, s);
		assertEquals(new InetSocketAddress(LOOPBACK, s.getLocalPort()), relay.boundAddress(owner));
	}

	@Test
	void badTokenGetsNoAckAndItsPacketsAreDropped() throws Exception {
		DatagramSocket os = socket();
		DatagramSocket vs1 = socket();
		DatagramSocket vs2 = socket();
		relay.register(owner, 42L);
		bind(v1, 43L, vs1);
		bind(v2, 44L, vs2);
		routeOwnerToViewers();

		send(os, hello(41L)); // wrong token
		assertNull(receive(os, 300), "no ACK for a bad token");
		assertNull(relay.boundAddress(owner), "not bound");

		send(os, tile(SID, 1, 100, 41L));
		send(os, tile(SID, 2, 100, 42L)); // right token, but the source address was never bound
		assertNull(receive(vs1, 300), "viewer 1 got nothing");
		assertNull(receive(vs2, 100), "viewer 2 got nothing");
	}

	@Test
	void ownerTileReachesBothViewersByteExact() throws Exception {
		DatagramSocket os = socket();
		DatagramSocket vs1 = socket();
		DatagramSocket vs2 = socket();
		bind(owner, 42L, os);
		bind(v1, 43L, vs1);
		bind(v2, 44L, vs2);
		routeOwnerToViewers();

		byte[] d = tile(SID, 1, 900, 42L);
		send(os, d);
		byte[] expected = stripToken(d);
		assertArrayEquals(expected, receive(vs1, 2000), "viewer 1 byte-exact, token stripped");
		assertArrayEquals(expected, receive(vs2, 2000), "viewer 2 byte-exact, token stripped");
		assertNull(receive(os, 200), "nothing echoed to the owner");
	}

	@Test
	void sizeAndClearFromOwnerAreForwardedToo() throws Exception {
		DatagramSocket os = socket();
		DatagramSocket vs1 = socket();
		bind(owner, 42L, os);
		bind(v1, 43L, vs1);
		relay.setRoutes(Map.of(SID, new StreamRelayServer.Route(owner, List.of(new StreamRelayServer.Viewer(v1, null)))));
		byte[] size = ByteBuffer.allocate(StreamHeader.HEADER_BYTES + 4 + Packetizer.TOKEN_BYTES)
				.put(new StreamHeader(StreamHeader.TYPE_SIZE, SID, 1, 0, 0, 0, 1).encode()).putShort((short) 1280)
				.putShort((short) 720).putLong(42L).array();
		send(os, size);
		assertArrayEquals(stripToken(size), receive(vs1, 2000));
	}

	@Test
	void nonOwnerTileIsDropped() throws Exception {
		DatagramSocket os = socket();
		DatagramSocket vs1 = socket();
		DatagramSocket vs2 = socket();
		bind(owner, 42L, os);
		bind(v1, 43L, vs1);
		bind(v2, 44L, vs2);
		routeOwnerToViewers();

		send(vs1, tile(SID, 1, 100, 43L)); // viewer 1 (validly bound) forging the owner's stream
		send(os, tile(SID, 2, 100, 43L)); // owner's address with someone else's token
		send(os, tile(SID + 1, 3, 100, 42L)); // owner, but a stream that has no route
		assertNull(receive(vs2, 300), "viewer 2 got nothing");
		assertNull(receive(vs1, 100), "viewer 1 got nothing");

		// Control: the genuine owner packet still flows.
		byte[] ok = tile(SID, 4, 100, 42L);
		send(os, ok);
		assertArrayEquals(stripToken(ok), receive(vs2, 2000));
	}

	@Test
	void overBudgetPacketsAreDroppedNotQueued() throws Exception {
		relay.close();
		// 80 kbps = 10_000 bytes/s; the bucket holds one second, and the test clock does not move.
		relay = startRelay(80);
		DatagramSocket os = socket();
		DatagramSocket vs1 = socket();
		bind(owner, 42L, os);
		bind(v1, 43L, vs1);
		relay.setRoutes(Map.of(SID, new StreamRelayServer.Route(owner, List.of(new StreamRelayServer.Viewer(v1, null)))));

		int payload = 1000 - StreamHeader.HEADER_BYTES; // forwarded datagram = 1000 bytes; budget counts forwarded bytes
		for (int seq = 1; seq <= 25; seq++) {
			send(os, tile(SID, seq, payload, 42L));
		}
		List<Integer> seqs = new ArrayList<>();
		byte[] got;
		while ((got = receive(vs1, 400)) != null) {
			seqs.add(StreamHeader.decode(got).orElseThrow().frameSeq());
		}
		assertEquals(10, seqs.size(), "exactly one second of budget forwarded: " + seqs);
		assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), seqs);
		assertNull(receive(vs1, 300), "dropped packets never show up later");

		clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(500)); // refills 5_000 bytes = 5 datagrams
		for (int seq = 100; seq < 108; seq++) {
			send(os, tile(SID, seq, payload, 42L));
		}
		List<Integer> after = new ArrayList<>();
		while ((got = receive(vs1, 400)) != null) {
			after.add(StreamHeader.decode(got).orElseThrow().frameSeq());
		}
		assertEquals(List.of(100, 101, 102, 103, 104), after, "half a second refills half the budget; new packets, not queued ones");
	}

	@Test
	void fallbackViewerGetsBytesAsPayloadAndFallbackOwnerIsRouted() throws Exception {
		DatagramSocket os = socket();
		DatagramSocket vs1 = socket();
		bind(owner, 42L, os);
		bind(v1, 43L, vs1);
		List<byte[]> fallback = new CopyOnWriteArrayList<>();
		relay.setRoutes(Map.of(SID, new StreamRelayServer.Route(owner,
				List.of(new StreamRelayServer.Viewer(v1, null), new StreamRelayServer.Viewer(v2, fallback::add)))));

		byte[] d = tile(SID, 1, 500, 42L);
		send(os, d);
		assertArrayEquals(stripToken(d), receive(vs1, 2000), "UDP viewer");
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		while (fallback.isEmpty() && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		assertEquals(1, fallback.size(), "fallback viewer got one payload");
		assertArrayEquals(stripToken(d), fallback.get(0), "fallback bytes == forwarded datagram");

		// Owner without UDP: a small FallbackTile payload (no token) is routed the same way, byte-exact.
		byte[] small = stripToken(tile(SID, 2, 300, 42L));
		assertTrue(relay.fromFallback(owner, small), "small owner fallback accepted");
		assertArrayEquals(small, receive(vs1, 2000), "UDP viewer gets the owner's fallback datagram");

		// A whole tile in one payload (20 KB body): fallback viewers get it as is; UDP viewers get standard
		// <= 1200-byte datagrams that reassemble to the same body.
		byte[] body = new byte[20_000];
		for (int i = 0; i < body.length; i++) {
			body[i] = (byte) (i * 7);
		}
		byte[] whole = ByteBuffer.allocate(StreamHeader.HEADER_BYTES + body.length)
				.put(new StreamHeader(StreamHeader.TYPE_TILE, SID, 3, 4, 5, 0, 1).encode()).put(body).array();
		assertTrue(relay.fromFallback(owner, whole), "whole-tile fallback accepted");
		Packetizer assembler = new Packetizer();
		Optional<Packetizer.Assembled> done = Optional.empty();
		int pieces = 0;
		byte[] got;
		while (done.isEmpty() && (got = receive(vs1, 2000)) != null) {
			assertTrue(got.length <= Packetizer.MAX_DATAGRAM, "UDP datagram size " + got.length);
			pieces++;
			done = assembler.accept(got);
		}
		assertTrue(done.isPresent(), "UDP viewer reassembled the tile");
		assertEquals(17, pieces, "ceil(20000 / 1182)");
		assertArrayEquals(body, done.get().jpeg());
		assertEquals(3, done.get().frameSeq());
		assertArrayEquals(whole, fallback.get(2), "fallback viewer gets the single payload as is");

		assertFalse(relay.fromFallback(v1, whole), "non-owner fallback refused");
		assertFalse(relay.fromFallback(owner, new byte[StreamRelayServer.FALLBACK_MAX_BYTES + 1]), "oversize refused");
		byte[] bigSize = ByteBuffer.allocate(2000)
				.put(new StreamHeader(StreamHeader.TYPE_SIZE, SID, 4, 0, 0, 0, 1).encode()).array();
		assertFalse(relay.fromFallback(owner, bigSize), "only single-part TILEs may exceed one datagram");
	}

	@Test
	void rebindNeedsTheSameTokenAndMovesTheBinding() throws Exception {
		DatagramSocket os = socket();
		DatagramSocket os2 = socket(); // NAT gave the owner a new port
		DatagramSocket vs1 = socket();
		bind(owner, 42L, os);
		bind(v1, 43L, vs1);
		relay.setRoutes(Map.of(SID, new StreamRelayServer.Route(owner, List.of(new StreamRelayServer.Viewer(v1, null)))));

		send(os2, hello(42L));
		assertNotNull(receive(os2, 2000), "rebind ACKed");
		assertEquals(new InetSocketAddress(LOOPBACK, os2.getLocalPort()), relay.boundAddress(owner));
		send(os, tile(SID, 1, 100, 42L));
		assertNull(receive(vs1, 300), "old address no longer accepted");
		byte[] ok = tile(SID, 2, 100, 42L);
		send(os2, ok);
		assertArrayEquals(stripToken(ok), receive(vs1, 2000));

		relay.unregister(owner);
		send(os2, ok);
		assertNull(receive(vs1, 300), "unregistered owner dropped");
	}

	@Test
	void bindFailureFallsBackToPayloadOnly() throws Exception {
		StreamRelayServer second = new StreamRelayServer(2000, clock::get);
		try {
			second.start(new InetSocketAddress(LOOPBACK, relay.udpPort())); // port already taken
			assertEquals(0, second.udpPort(), "no UDP");
			assertTrue(second.payloadOnly());
			List<byte[]> fallback = new CopyOnWriteArrayList<>();
			second.setRoutes(Map.of(SID, new StreamRelayServer.Route(owner, List.of(new StreamRelayServer.Viewer(v1, fallback::add)))));
			byte[] d = stripToken(tile(SID, 1, 100, 42L));
			assertTrue(second.fromFallback(owner, d));
			assertEquals(1, fallback.size());
		} finally {
			second.close();
		}
	}
}
