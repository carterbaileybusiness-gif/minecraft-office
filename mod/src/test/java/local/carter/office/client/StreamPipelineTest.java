package local.carter.office.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import local.carter.office.client.capture.FrameSink;
import local.carter.office.client.render.ScreenTexture;
import local.carter.office.client.render.ScreenTextureRegistry;
import local.carter.office.client.stream.StreamClient;
import local.carter.office.client.stream.StreamReceiver;
import local.carter.office.client.stream.StreamSender;
import local.carter.office.client.stream.UdpLink;
import local.carter.office.core.Packetizer;
import local.carter.office.core.Privacy;
import local.carter.office.core.StreamHeader;
import local.carter.office.core.TileCodec;
import local.carter.office.net.OfficePayloads.StreamDemand;
import local.carter.office.net.OfficePayloads.StreamToken;
import local.carter.office.stream.StreamRelayServer;

/**
 * Task 14: own screens out (StreamSender), friends' screens in (StreamReceiver), the transport (StreamClient/UdpLink).
 * Fake JPEG (lossless deflate) + an in-memory relay with a fake clock, plus real UDP against {@link StreamRelayServer}.
 */
class StreamPipelineTest {
	private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);
	private static final int SID = 7;

	private final AtomicLong now = new AtomicLong(TimeUnit.SECONDS.toNanos(100));
	private final Map<UUID, Privacy> privacy = new ConcurrentHashMap<>();
	/** Holograms the local player owns. */
	private final java.util.Set<UUID> own = ConcurrentHashMap.newKeySet();
	private final UUID hologram = UUID.randomUUID();
	private final FakeJpeg jpeg = new FakeJpeg();
	private final List<AutoCloseable> closeMe = new ArrayList<>();

	@BeforeEach
	void setUp() {
		ScreenTexture.setStrictIdsForTest(true);
	}

	@AfterEach
	void tearDown() throws Exception {
		for (AutoCloseable c : closeMe) {
			c.close();
		}
		ScreenTextureRegistry.remove(hologram);
	}

	// ------------------------------------------------------------------ privacy at the source

	@Test
	void privateHologramSendsNothingEvenWhenDemanded() {
		Relay relay = new Relay(0, null);
		StreamSender sender = sender(relay);
		privacy.put(hologram, Privacy.PRIVATE);

		sender.onDemand(new StreamDemand(hologram, SID, true));
		assertFalse(sender.isSharing(hologram), "a PRIVATE hologram never starts a share");
		feedFull(sender, 320, 200, frame(320, 200, 1));
		runFrames(sender, 30);
		assertEquals(0, relay.datagrams.size(), "zero packets for a PRIVATE hologram");
		assertEquals(0, jpeg.encodes.get(), "its pixels were never even encoded");
	}

	@Test
	void unknownPrivacySendsNothingAndAKnownPrivateEndsTheShare() {
		Relay relay = new Relay(0, null);
		StreamSender sender = sender(relay);
		sender.onDemand(new StreamDemand(hologram, SID, true)); // privacy not known yet (hologram not loaded)
		feedFull(sender, 320, 200, frame(320, 200, 1));
		runFrames(sender, 10);
		assertEquals(0, relay.datagrams.size(), "nothing leaves while the privacy is unknown");

		privacy.put(hologram, Privacy.PRIVATE);
		runFrames(sender, 10);
		assertEquals(0, relay.tiles(), "no tile once it is known to be PRIVATE");
		assertFalse(sender.isSharing(hologram));
	}

	@Test
	void switchingToPrivateMidStreamStopsWithinOneFrame() {
		Relay relay = new Relay(0, null);
		StreamSender sender = sender(relay);
		privacy.put(hologram, Privacy.PUBLIC);
		sender.onDemand(new StreamDemand(hologram, SID, true));
		feedFull(sender, 320, 200, frame(320, 200, 1));
		runFrames(sender, 3);
		int before = relay.tiles();
		assertTrue(before > 0, "public: tiles are sent");

		privacy.put(hologram, Privacy.PRIVATE);
		FrameSink tap = sender.tap(hologram);
		assertNotNull(tap);
		tap.full(320, 200, frame(320, 200, 2)); // the very next captured frame
		runFrames(sender, 1);
		assertEquals(before, relay.tiles(), "not one more tile after the switch");
		assertFalse(sender.isSharing(hologram), "the share is gone");
		assertEquals(StreamHeader.TYPE_CLEAR, relay.lastType(), "viewers are told to drop the picture");
		runFrames(sender, 20);
		assertEquals(before, relay.tiles());
	}

	@Test
	void switchingToPrivateWhileAFrameIsBeingEncodedSendsNoFurtherTile() {
		Relay relay = new Relay(0, null);
		StreamSender sender = sender(relay);
		privacy.put(hologram, Privacy.PUBLIC);
		sender.onDemand(new StreamDemand(hologram, SID, true));
		feedFull(sender, 320, 200, frame(320, 200, 1));
		// flips privacy inside the first tile's encode of the first frame: that tile and the rest must stay home
		jpeg.onEncode = () -> privacy.put(hologram, Privacy.PRIVATE);
		runFrames(sender, 1);
		assertEquals(0, relay.tiles(), "the tile being encoded at the switch is not sent either");
		assertTrue(jpeg.encodes.get() <= 1, "encoding stops at the switch");
		assertFalse(sender.isSharing(hologram));
	}

	@Test
	void demandFalseAndStreamEndedStopTheShare() {
		Relay relay = new Relay(0, null);
		StreamSender sender = sender(relay);
		privacy.put(hologram, Privacy.PUBLIC);
		sender.onDemand(new StreamDemand(hologram, SID, true));
		sender.onDemand(new StreamDemand(hologram, SID - 1, false)); // stale stop for an older stream: ignored
		assertTrue(sender.isSharing(hologram));
		sender.onDemand(new StreamDemand(hologram, SID, false));
		assertFalse(sender.isSharing(hologram));

		sender.onDemand(new StreamDemand(hologram, SID + 1, true));
		sender.onEnded(SID + 1);
		assertFalse(sender.isSharing(hologram));
		feedFull(sender, 320, 200, frame(320, 200, 1));
		runFrames(sender, 5);
		assertEquals(0, relay.tiles());
	}

	// ------------------------------------------------------------------ receiving

	@Test
	void receiverShowsTheSourceAndHoldsNoPixelsAfterStreamEnded() {
		StreamReceiver receiver = receiver();
		Relay relay = new Relay(0, receiver);
		StreamSender sender = sender(relay);
		privacy.put(hologram, Privacy.PUBLIC);
		start(receiver, SID, hologram);
		assertTrue(ScreenTextureRegistry.get(hologram).isPresent(), "registered at StreamStart");
		sender.onDemand(new StreamDemand(hologram, SID, true));
		ByteBuffer src = frame(320, 200, 3);
		feedFull(sender, 320, 200, src);
		runFrames(sender, 1);

		ScreenTexture tex = receiver.textureOf(hologram);
		assertNotNull(tex);
		assertEquals(320, tex.width());
		assertArrayEquals(bytes(src), bytes(tex.snapshot()), "lossless fake: the viewer sees exactly the source");
		assertTrue(tex.hasFrame(), "look is LIVE");

		receiver.onEnded(SID);
		assertEquals(0, tex.width(), "no pixel buffers after StreamEnded");
		assertEquals(0, tex.snapshot().capacity());
		assertFalse(ScreenTextureRegistry.get(hologram).isPresent(), "look falls back (not LIVE)");
		assertEquals(0, receiver.bufferedBytes());
		assertEquals(0, receiver.streamCount());
	}

	@Test
	void registryChangesRunOnTheClientThreadAndQueuedOnesCantUndoALaterOne() {
		java.util.ArrayDeque<Runnable> clientThread = new java.util.ArrayDeque<>();
		StreamReceiver receiver = new StreamReceiver(id -> new ScreenTexture(ScreenTexture.idFor(id), new NoGpu()), jpeg, now::get,
				clientThread::add, own::contains);
		start(receiver, SID, hologram);
		assertFalse(ScreenTextureRegistry.get(hologram).isPresent(), "not touched off the client thread");
		receiver.onEnded(SID);
		start(receiver, SID + 1, hologram); // end + restart queued before the client thread runs anything
		assertFalse(ScreenTextureRegistry.get(hologram).isPresent());
		assertFalse(clientThread.isEmpty());
		while (!clientThread.isEmpty()) {
			clientThread.poll().run();
		}
		assertSame(receiver.textureOf(hologram), ScreenTextureRegistry.get(hologram).orElseThrow(),
				"the newest wish wins: registered for the new stream");
		assertEquals(1, receiver.textureCount());

		receiver.onEnded(SID + 1);
		assertTrue(ScreenTextureRegistry.get(hologram).isPresent(), "still registered until the client thread runs");
		while (!clientThread.isEmpty()) {
			clientThread.poll().run();
		}
		assertFalse(ScreenTextureRegistry.get(hologram).isPresent());
		assertEquals(0, receiver.textureCount(), "the texture object is pruned once the hologram has no stream");
	}

	// ------------------------------------------------------------------ review fixes: limits and ownership

	@Test
	void aFrameSizeOver1280OrATileOver64EndsTheStream() {
		StreamReceiver receiver = receiver();
		int[][] bad = { { 8192, 8192, 64 }, { 1281, 720, 64 }, { 1280, 1281, 64 }, { 0, 720, 64 }, { 640, 360, 128 } };
		int sid = 100;
		for (int[] b : bad) {
			start(receiver, sid, hologram);
			receiver.onDatagram(size(sid, b[0], b[1], b[2]));
			assertEquals(0, receiver.streamCount(), "stream ended for " + Arrays.toString(b));
			assertFalse(ScreenTextureRegistry.get(hologram).isPresent());
			assertEquals(0, receiver.textureCount());
			sid++;
		}
		start(receiver, sid, hologram);
		receiver.onDatagram(size(sid, 1280, 1280, 64));
		assertEquals(1, receiver.streamCount(), "1280 a side is fine");
		assertEquals(0, receiver.textureOf(hologram).width(), "and nothing is allocated before a tile arrives");
	}

	@Test
	void aFriendsStreamNeverReplacesYourOwnScreen() {
		AtomicInteger made = new AtomicInteger();
		StreamReceiver receiver = new StreamReceiver(id -> {
			made.incrementAndGet();
			return new ScreenTexture(ScreenTexture.idFor(id), new NoGpu());
		}, jpeg, now::get, Runnable::run, own::contains);
		ScreenTexture mine = new ScreenTexture(ScreenTexture.idFor(hologram), new NoGpu());
		ScreenTextureRegistry.register(hologram, mine); // what OwnScreens does for an own hologram
		own.add(hologram);

		assertFalse(receiver.prepareStart(SID, hologram), "a start for an own hologram is ignored");
		receiver.begin(SID, hologram);
		receiver.onDatagram(size(SID, 320, 200, 64));
		assertEquals(0, made.get(), "no texture is ever made for an own hologram");
		assertEquals(0, receiver.streamCount());
		assertSame(mine, ScreenTextureRegistry.get(hologram).orElseThrow(), "own screen untouched");

		// a stream that started before the hologram was known to be ours ends at its next datagram, leaving ours alone
		own.clear();
		ScreenTextureRegistry.remove(hologram);
		start(receiver, SID + 1, hologram);
		own.add(hologram);
		ScreenTextureRegistry.register(hologram, mine);
		receiver.onDatagram(size(SID + 1, 320, 200, 64));
		assertEquals(0, receiver.streamCount());
		assertSame(mine, ScreenTextureRegistry.get(hologram).orElseThrow());
		mine.clear();
	}

	@Test
	void texturesAreMadeOnlyOnTheClientThreadHalf() {
		AtomicInteger made = new AtomicInteger();
		StreamReceiver receiver = new StreamReceiver(id -> {
			made.incrementAndGet();
			return new ScreenTexture(ScreenTexture.idFor(id), new NoGpu());
		}, jpeg, now::get, Runnable::run, own::contains);
		receiver.begin(SID, hologram); // receive thread without the client-thread half: nothing to bind to
		assertEquals(0, made.get(), "the receive thread never creates a (GPU-backed) texture");
		assertEquals(0, receiver.streamCount());
		assertTrue(receiver.prepareStart(SID, hologram));
		assertEquals(1, made.get());
		receiver.begin(SID, hologram);
		assertEquals(1, receiver.streamCount());
		assertEquals(1, made.get());
	}

	@Test
	void theReceiveQueueIsCappedInBytes() {
		List<Runnable> neverRun = new ArrayList<>();
		StreamClient client = new StreamClient(new RecordingPayloads(), UdpLink::open, InetAddress::getLoopbackAddress, jpeg,
				new FakeJpeg(), privacy::get, id -> null, id -> new ScreenTexture(ScreenTexture.idFor(id), new NoGpu()),
				neverRun::add, Runnable::run, own::contains, now::get);
		byte[] body = new byte[30 * 1024 - StreamHeader.HEADER_BYTES];
		ByteBuffer d = ByteBuffer.allocate(StreamHeader.HEADER_BYTES + body.length);
		new StreamHeader(StreamHeader.TYPE_TILE, SID, 1, 0, 0, 0, 1).writeTo(d);
		byte[] datagram = d.put(body).array();
		for (int i = 0; i < 1000; i++) { // ~30 MB offered to a stalled receive thread
			client.onFallbackTile(new local.carter.office.net.OfficePayloads.StreamFallbackTile(SID, datagram));
		}
		assertTrue(client.queuedBytes() <= StreamClient.MAX_QUEUED_BYTES, "queued " + client.queuedBytes());
		assertEquals(StreamClient.MAX_QUEUED_BYTES / datagram.length, neverRun.size(), "the rest was dropped");
	}

	private static byte[] size(final int sid, final int w, final int h, final int tile) {
		return ByteBuffer.allocate(StreamHeader.HEADER_BYTES + 6).put(new StreamHeader(StreamHeader.TYPE_SIZE, sid, 1, 0, 0, 0, 1).encode())
				.putShort((short) w).putShort((short) h).putShort((short) tile).array();
	}

	@Test
	void threeSecondsWithoutPacketsClearsTheTexture() {
		StreamReceiver receiver = receiver();
		Relay relay = new Relay(0, receiver);
		StreamSender sender = sender(relay);
		privacy.put(hologram, Privacy.PUBLIC);
		start(receiver, SID, hologram);
		sender.onDemand(new StreamDemand(hologram, SID, true));
		feedFull(sender, 320, 200, frame(320, 200, 3));
		runFrames(sender, 1);
		ScreenTexture tex = receiver.textureOf(hologram);
		assertEquals(320, tex.width());

		now.addAndGet(2_900 * MS);
		receiver.tick();
		assertEquals(320, tex.width(), "2.9 s of silence: still showing");
		now.addAndGet(200 * MS);
		receiver.tick();
		assertEquals(0, tex.width(), "3 s of silence: cleared");
		assertFalse(ScreenTextureRegistry.get(hologram).isPresent());

		runFrames(sender, 15); // the stream resumes: SIZE (≤ 1 s) and tiles bring the picture back
		assertEquals(320, tex.width());
		assertTrue(ScreenTextureRegistry.get(hologram).isPresent());
	}

	@Test
	void twentyPercentLossConvergesWithinThreeSecondsViaRefresh() {
		StreamReceiver receiver = receiver();
		Relay relay = new Relay(0.20, receiver);
		StreamSender sender = sender(relay);
		privacy.put(hologram, Privacy.PUBLIC);
		start(receiver, SID, hologram);
		sender.onDemand(new StreamDemand(hologram, SID, true));
		int w = 448;
		int h = 256; // 7 x 4 = 28 tiles
		FrameSink tap = sender.tap(hologram);
		tap.full(w, h, frame(w, h, 0));
		ByteBuffer last = null;
		for (int i = 1; i <= 20; i++) { // 2 s of a changing screen (a moving bar)
			last = frame(w, h, i);
			tap.full(w, h, last);
			runFrames(sender, 1);
		}
		assertTrue(relay.dropped > 0, "loss was applied");
		long start = now.get();
		ScreenTexture tex = receiver.textureOf(hologram);
		byte[] want = bytes(last);
		while (tex.width() != w || !Arrays.equals(want, bytes(tex.snapshot()))) {
			assertTrue(now.get() - start <= TimeUnit.SECONDS.toNanos(3), "converged within 3 s");
			runFrames(sender, 1);
		}
		System.out.println("[stream-test] 20% loss: converged " + (now.get() - start) / MS + " ms after the last change ("
				+ relay.dropped + " of " + relay.datagrams.size() + " datagrams dropped)");
	}

	@Test
	void aNewStreamForTheSameHologramReplacesTheOld() {
		StreamReceiver receiver = receiver();
		privacy.put(hologram, Privacy.PUBLIC);
		Relay relay1 = new Relay(0, receiver);
		StreamSender owner1 = sender(relay1);
		start(receiver, 1, hologram);
		owner1.onDemand(new StreamDemand(hologram, 1, true));
		feedFull(owner1, 320, 200, frame(320, 200, 1));
		runFrames(owner1, 1);
		ScreenTexture tex = receiver.textureOf(hologram);
		assertEquals(320, tex.width());

		start(receiver, 2, hologram); // owner respawned / rejoined: new stream id
		assertEquals(1, receiver.streamCount(), "the old stream is gone");
		assertSame(tex, receiver.textureOf(hologram), "same texture (stable id) reused");
		assertEquals(0, tex.width(), "the old picture is dropped");

		runFrames(owner1, 10); // late datagrams of the old stream are ignored
		assertEquals(0, tex.width());

		Relay relay2 = new Relay(0, receiver);
		StreamSender owner2 = sender(relay2);
		owner2.onDemand(new StreamDemand(hologram, 2, true));
		ByteBuffer src = frame(256, 128, 9);
		feedFull(owner2, 256, 128, src);
		runFrames(owner2, 1);
		assertEquals(256, tex.width());
		assertArrayEquals(bytes(src), bytes(tex.snapshot()));
		assertSame(tex, ScreenTextureRegistry.get(hologram).orElseThrow());
	}

	// ------------------------------------------------------------------ downscale

	@Test
	void sharedFramesAreAtMost1280PixelsASide() {
		assertArrayEquals(new int[] { 1280, 720 }, StreamSender.targetSize(1920, 1080));
		assertArrayEquals(new int[] { 1280, 720 }, StreamSender.targetSize(2560, 1440));
		assertArrayEquals(new int[] { 720, 1280 }, StreamSender.targetSize(1080, 1920));
		assertArrayEquals(new int[] { 1280, 800 }, StreamSender.targetSize(1280, 800));

		StreamReceiver receiver = receiver();
		Relay relay = new Relay(0, receiver);
		StreamSender sender = sender(relay);
		privacy.put(hologram, Privacy.PUBLIC);
		start(receiver, SID, hologram);
		sender.onDemand(new StreamDemand(hologram, SID, true));
		// 2x2 checker of black/white pixels: the box filter averages 2x2 source blocks (2560 -> 1280) to mid grey
		int sw = 2560;
		int sh = 256;
		ByteBuffer src = ByteBuffer.allocate(sw * sh * 4);
		for (int y = 0; y < sh; y++) {
			for (int x = 0; x < sw; x++) {
				byte v = (byte) (((x + y) & 1) == 0 ? 0 : 254);
				src.put(v).put(v).put(v).put((byte) 255);
			}
		}
		src.flip();
		feedFull(sender, sw, sh, src);
		for (int i = 0; i < 40 && (receiver.textureOf(hologram).width() == 0 || !allGrey(receiver.textureOf(hologram))); i++) {
			runFrames(sender, 1);
		}
		ScreenTexture tex = receiver.textureOf(hologram);
		assertEquals(1280, tex.width());
		assertEquals(128, tex.height());
		assertTrue(allGrey(tex), "box-filtered");
	}

	private static boolean allGrey(final ScreenTexture tex) {
		byte[] px = bytes(tex.snapshot());
		for (int i = 0; i < px.length; i += 4) {
			if ((px[i] & 0xFF) != 127) {
				return false;
			}
		}
		return px.length > 0;
	}

	// ------------------------------------------------------------------ transport (real UDP + relay)

	@Test
	void udpPortZeroUsesTheFallbackAndSaysSo() {
		RecordingPayloads payloads = new RecordingPayloads();
		StreamClient client = client(payloads, () -> InetAddress.getLoopbackAddress());
		client.connect(new StreamToken(42L, 0));
		assertEquals(List.of(false), payloads.statuses, "UdpStatus(false) straight away");
		assertFalse(client.udpBound());

		shareOneFrame(client);
		assertTrue(payloads.fallback.size() > 0, "owner datagrams go out as FallbackTile payloads");
		for (byte[] d : payloads.fallback) {
			assertTrue(d.length <= 30 * 1024);
			assertEquals(SID, StreamHeader.decode(d).orElseThrow().streamId());
		}
	}

	@Test
	void udpHandshakeBindsThenUdpPortZeroSwitchesToTheFallback() throws Exception {
		StreamRelayServer relay = new StreamRelayServer(2000);
		closeMe.add(relay::close);
		relay.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
		UUID me = UUID.randomUUID();
		long token = 0x1234_5678_9ABC_DEF0L;
		relay.register(me, token, null);

		RecordingPayloads payloads = new RecordingPayloads();
		StreamClient client = client(payloads, () -> InetAddress.getLoopbackAddress());
		closeMe.add(client::disconnect);
		client.connect(new StreamToken(token, relay.udpPort()));
		waitFor(client::udpBound, 5000, "bound within 5 s");
		assertNotNull(relay.boundAddress(me), "the relay bound us after the nonce echo");
		waitFor(() -> payloads.statuses.contains(true), 1000, "UdpStatus(true) reported");

		shareOneFrame(client);
		assertEquals(0, payloads.fallback.size(), "bound: nothing over the game connection");

		client.connect(new StreamToken(token, 0)); // the server lost UDP mid-session
		assertFalse(client.udpBound());
		assertEquals(false, payloads.statuses.get(payloads.statuses.size() - 1), "UdpStatus(false)");
		now.addAndGet(200 * MS);
		FrameSink tap = client.sender().tap(hologram);
		tap.full(320, 200, frame(320, 200, 5));
		now.addAndGet(200 * MS);
		client.pump();
		assertTrue(payloads.fallback.size() > 0, "now everything goes over the fallback");
	}

	@Test
	void noHandshakeAnswerFallsBackAfterFiveSeconds() throws Exception {
		DatagramSocket silent = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
		closeMe.add(silent::close);
		List<Boolean> statuses = new CopyOnWriteArrayList<>();
		AtomicLong clock = new AtomicLong(1_000_000_000L);
		UdpLink link = UdpLink.open(new InetSocketAddress(InetAddress.getLoopbackAddress(), silent.getLocalPort()), 42L,
				new UdpLink.Listener() {
					@Override
					public void onStatus(final boolean ok) {
						statuses.add(ok);
					}

					@Override
					public void onDatagram(final byte[] datagram) {}
				}, clock::get);
		closeMe.add(link);
		silent.setSoTimeout(2000);
		DatagramPacket p = new DatagramPacket(new byte[64], 64);
		silent.receive(p);
		assertEquals(StreamHeader.HEADER_BYTES + Packetizer.TOKEN_BYTES, p.getLength(), "HELLO = header + token");
		assertEquals(StreamHeader.TYPE_HELLO, StreamHeader.decode(Arrays.copyOf(p.getData(), p.getLength())).orElseThrow().type());
		Thread.sleep(300);
		assertTrue(statuses.isEmpty(), "no verdict before the deadline");
		clock.addAndGet(TimeUnit.SECONDS.toNanos(5));
		waitFor(() -> statuses.equals(List.of(false)), 2000, "UdpStatus(false) after 5 s without an ACK");
		assertFalse(link.bound());
	}

	@Test
	void ownerToViewerOverRealUdpThroughTheRelay() throws Exception {
		StreamRelayServer relay = new StreamRelayServer(2000);
		closeMe.add(relay::close);
		relay.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
		UUID ownerId = UUID.randomUUID();
		UUID viewerId = UUID.randomUUID();
		relay.register(ownerId, 1L, null);
		relay.register(viewerId, 2L, null);
		relay.setRoutes(Map.of(SID, new StreamRelayServer.Route(ownerId, List.of(new StreamRelayServer.Viewer(viewerId, null)))));

		StreamClient owner = client(new RecordingPayloads(), InetAddress::getLoopbackAddress);
		StreamClient viewer = client(new RecordingPayloads(), InetAddress::getLoopbackAddress);
		closeMe.add(owner::disconnect);
		closeMe.add(viewer::disconnect);
		owner.connect(new StreamToken(1L, relay.udpPort()));
		viewer.connect(new StreamToken(2L, relay.udpPort()));
		waitFor(() -> owner.udpBound() && viewer.udpBound(), 5000, "both bound");

		viewer.onStart(new local.carter.office.net.OfficePayloads.StreamStart(SID, hologram));
		privacy.put(hologram, Privacy.PUBLIC);
		owner.onDemand(new StreamDemand(hologram, SID, true));
		ByteBuffer src = frame(320, 200, 4);
		owner.sender().tap(hologram).full(320, 200, src);
		byte[] want = bytes(src);
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (true) {
			now.addAndGet(100 * MS);
			owner.pump();
			Thread.sleep(20);
			ScreenTexture tex = viewer.receiver().textureOf(hologram);
			if (tex != null && tex.width() == 320 && Arrays.equals(want, bytes(tex.snapshot()))) {
				break;
			}
			assertTrue(System.nanoTime() < deadline, "the viewer shows the owner's screen over UDP");
		}
	}

	// ------------------------------------------------------------------ helpers

	private StreamSender sender(final StreamSender.Link link) {
		return new StreamSender(link, jpeg, privacy::get, id -> null, now::get);
	}

	private StreamReceiver receiver() {
		return new StreamReceiver(id -> new ScreenTexture(ScreenTexture.idFor(id), new NoGpu()), jpeg, now::get, Runnable::run,
				own::contains);
	}

	/** Both halves of a StreamStart: client thread (texture) then receive thread (bind). */
	private static void start(final StreamReceiver receiver, final int sid, final UUID hologramId) {
		if (receiver.prepareStart(sid, hologramId)) {
			receiver.begin(sid, hologramId);
		}
	}

	private StreamClient client(final RecordingPayloads payloads, final java.util.function.Supplier<InetAddress> host) {
		return new StreamClient(payloads, UdpLink::open, host, jpeg, new FakeJpeg(), privacy::get, id -> null,
				id -> new ScreenTexture(ScreenTexture.idFor(id), new NoGpu()), Runnable::run, Runnable::run, own::contains, now::get);
	}

	private void shareOneFrame(final StreamClient client) {
		privacy.put(hologram, Privacy.PUBLIC);
		client.onDemand(new StreamDemand(hologram, SID, true));
		client.sender().tap(hologram).full(320, 200, frame(320, 200, 1));
		now.addAndGet(200 * MS);
		client.pump();
	}

	/** What the capture would deliver: the fan-out hands each frame to the share tap while one exists. */
	private void feedFull(final StreamSender sender, final int w, final int h, final ByteBuffer rgba) {
		FrameSink tap = sender.tap(hologram);
		if (tap != null) {
			tap.full(w, h, rgba);
		}
	}

	/** {@code count} frames, 100 ms apart. */
	private void runFrames(final StreamSender sender, final int count) {
		for (int i = 0; i < count; i++) {
			now.addAndGet(100 * MS);
			sender.pump();
		}
	}

	/** w x h RGBA: a stepped gradient plus a vertical bar at x = seed * 16 (opaque); ~1 KB a tile deflated, about a JPEG tile. */
	private ByteBuffer frame(final int w, final int h, final int seed) {
		ByteBuffer b = ByteBuffer.allocate(w * h * 4);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				boolean bar = x / 16 == seed % (w / 16);
				b.put((byte) (bar ? 255 : x / 4 * 4)).put((byte) (bar ? 255 : y / 4 * 4)).put((byte) (seed * 37)).put((byte) 255);
			}
		}
		b.flip();
		return b;
	}

	private static byte[] bytes(final ByteBuffer b) {
		byte[] out = new byte[b.remaining()];
		b.duplicate().get(out);
		return out;
	}

	private static void waitFor(final BooleanSupplier cond, final long millis, final String what) throws InterruptedException {
		long end = System.nanoTime() + millis * MS;
		while (!cond.getAsBoolean()) {
			assertTrue(System.nanoTime() < end, what);
			Thread.sleep(10);
		}
	}

	/** Splits like the real link (with a token), strips the token like the relay, drops a share at random. */
	private static final class Relay implements StreamSender.Link {
		final double loss;
		final StreamReceiver receiver;
		final Random random = new Random(1234);
		final Packetizer splitter = new Packetizer();
		final List<byte[]> datagrams = new ArrayList<>();
		int dropped;

		Relay(final double loss, final StreamReceiver receiver) {
			this.loss = loss;
			this.receiver = receiver;
		}

		@Override
		public boolean udp() {
			return true;
		}

		@Override
		public void send(final StreamHeader h, final byte[] body) {
			for (byte[] d : splitter.split(h, body, new byte[Packetizer.TOKEN_BYTES])) {
				byte[] stripped = Arrays.copyOf(d, d.length - Packetizer.TOKEN_BYTES);
				datagrams.add(stripped);
				if (random.nextDouble() < loss) {
					dropped++;
					continue;
				}
				if (receiver != null) {
					receiver.onDatagram(stripped);
				}
			}
		}

		int tiles() {
			int n = 0;
			for (byte[] d : datagrams) {
				if (StreamHeader.decode(d).orElseThrow().type() == StreamHeader.TYPE_TILE) {
					n++;
				}
			}
			return n;
		}

		int lastType() {
			return StreamHeader.decode(datagrams.get(datagrams.size() - 1)).orElseThrow().type();
		}
	}

	/** Lossless stand-in for JPEG: deflate of the tile's RGBA. */
	static final class FakeJpeg implements TileCodec.Jpeg {
		final AtomicInteger encodes = new AtomicInteger();
		volatile Runnable onEncode;

		@Override
		public byte[] encode(final TileCodec.Tile tile, final int quality) {
			encodes.incrementAndGet();
			Runnable hook = onEncode;
			if (hook != null) {
				onEncode = null;
				hook.run();
			}
			byte[] raw = new byte[tile.w() * tile.h() * 4];
			tile.rgba().duplicate().get(raw);
			Deflater d = new Deflater(Deflater.BEST_SPEED);
			d.setInput(raw);
			d.finish();
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			while (!d.finished()) {
				out.write(buf, 0, d.deflate(buf));
			}
			d.end();
			return out.toByteArray();
		}

		@Override
		public void decodeInto(final byte[] jpeg, final ByteBuffer dst, final int w, final int h) {
			Inflater inf = new Inflater();
			inf.setInput(jpeg);
			byte[] raw = new byte[w * h * 4];
			try {
				int n = inf.inflate(raw);
				if (n != raw.length || !inf.finished()) {
					throw new IllegalArgumentException("tile is not " + w + "x" + h);
				}
			} catch (DataFormatException e) {
				throw new IllegalArgumentException(e);
			} finally {
				inf.end();
			}
			dst.put(dst.position(), raw);
		}
	}

	static final class NoGpu implements ScreenTexture.Gpu {
		@Override
		public void allocate(final int width, final int height, final int levels) {}

		@Override
		public void write(final int level, final int x, final int y, final int w, final int h, final ByteBuffer rgba) {}

		@Override
		public void free() {}
	}

	static final class RecordingPayloads implements StreamClient.Payloads {
		final List<byte[]> fallback = new CopyOnWriteArrayList<>();
		final List<Boolean> statuses = new CopyOnWriteArrayList<>();

		@Override
		public void sendFallback(final byte[] datagram) {
			fallback.add(datagram);
		}

		@Override
		public void sendUdpStatus(final boolean ok) {
			statuses.add(ok);
		}
	}
}
