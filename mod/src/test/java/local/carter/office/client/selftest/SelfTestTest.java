package local.carter.office.client.selftest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import local.carter.office.client.platform.NoPlatform;
import local.carter.office.client.selftest.SelfTestReport.Status;
import local.carter.office.client.stream.StreamClient;
import local.carter.office.client.stream.UdpLink;
import local.carter.office.core.TileCodec;
import local.carter.office.net.OfficePayloads.StreamToken;
import local.carter.office.stream.StreamRelayServer;

/** The runner (off-thread, per-step timeouts, total budget, Throwable-safe) and the steps that need no Minecraft. */
class SelfTestTest {
	private final List<AutoCloseable> closeMe = new ArrayList<>();

	@AfterEach
	void tearDown() throws Exception {
		for (AutoCloseable c : closeMe) {
			c.close();
		}
	}

	@Test
	void aHangingStepTimesOutAndTheRestStillRun() throws Exception {
		List<SelfTest.StepDef> steps = List.of(
				new SelfTest.StepDef("hang", "Hangs", ctx -> {
					Thread.sleep(60_000);
					return SelfTest.Outcome.pass("never");
				}),
				new SelfTest.StepDef("boom", "Throws", ctx -> {
					throw new LinkageError("no natives here");
				}),
				new SelfTest.StepDef("ok", "Fine", ctx -> SelfTest.Outcome.pass("all good")));
		SelfTestReport report = run(steps, 300, 10_000);
		assertEquals(Status.FAIL, SelfTestReportTest.line(report, "hang").status());
		assertTrue(SelfTestReportTest.line(report, "hang").detail().contains("timed out"));
		assertEquals(Status.FAIL, SelfTestReportTest.line(report, "boom").status());
		assertTrue(SelfTestReportTest.line(report, "boom").detail().contains("no natives here"));
		assertEquals(Status.PASS, SelfTestReportTest.line(report, "ok").status());
	}

	@Test
	void theTotalBudgetSkipsWhatIsLeft() throws Exception {
		SelfTest.Step slow = ctx -> {
			Thread.sleep(250);
			return SelfTest.Outcome.pass("slow");
		};
		List<SelfTest.StepDef> steps = List.of(new SelfTest.StepDef("a", "A", slow), new SelfTest.StepDef("b", "B", slow),
				new SelfTest.StepDef("c", "C", slow), new SelfTest.StepDef("d", "D", slow));
		SelfTestReport report = run(steps, 5_000, 600);
		assertEquals(Status.PASS, SelfTestReportTest.line(report, "a").status());
		assertEquals(Status.SKIP, SelfTestReportTest.line(report, "d").status());
		assertTrue(SelfTestReportTest.line(report, "d").detail().contains("30 s") || SelfTestReportTest.line(report, "d").detail().contains("limit"));
	}

	@Test
	void theChecklistSeesRunningThenTheResultForEachStep() throws Exception {
		List<String> seen = new CopyOnWriteArrayList<>();
		CountDownLatch done = new CountDownLatch(1);
		SelfTest.start(List.of(new SelfTest.StepDef("x", "X", ctx -> SelfTest.Outcome.skip("n/a"))), context(), new SelfTest.Listener() {
			@Override
			public void changed(final SelfTestReport.Line line) {
				seen.add(line.status().name());
			}

			@Override
			public void finished(final SelfTestReport report, final String text) {
				done.countDown();
			}
		}, 1000, 1000);
		assertTrue(done.await(5, TimeUnit.SECONDS));
		assertEquals(List.of("RUNNING", "SKIP"), seen);
	}

	@Test
	void registerStepReplacesInPlaceOrAppends() {
		List<String> before = SelfTest.steps().stream().map(SelfTest.StepDef::id).toList();
		assertTrue(before.contains("zoom"));
		SelfTest.Step real = ctx -> SelfTest.Outcome.pass("zoomed in and out");
		SelfTest.registerStep("zoom", "Zoom in/out dry run", real);
		try {
			List<SelfTest.StepDef> after = SelfTest.steps();
			assertEquals(before, after.stream().map(SelfTest.StepDef::id).toList(), "same place, same order");
			assertEquals(real, after.get(before.indexOf("zoom")).step());
		} finally {
			SelfTest.registerStep("zoom", "Zoom in/out dry run", SelfTestSteps::zoomUnavailable);
		}
	}

	@Test
	void zoomIsMarkedNotAvailableInThisBuild() throws Exception {
		SelfTest.Outcome o = SelfTestSteps.zoomUnavailable(context());
		assertEquals(Status.SKIP, o.status());
		assertTrue(o.detail().contains("not available in this build"));
	}

	@Test
	void testPatternCapturesOneFullFrame() throws Exception {
		SelfTest.Outcome o = SelfTestSteps.testPattern(context());
		assertEquals(Status.PASS, o.status(), o.detail());
		assertTrue(o.detail().contains("1920x1080"), o.detail());
	}

	@Test
	void noOwnScreenSupportSkipsThePlatformStep() throws Exception {
		SelfTest.Outcome o = SelfTestSteps.platform(context());
		assertEquals(Status.SKIP, o.status(), o.detail());
	}

	// ------------------------------------------------------------------ UDP

	@Test
	void udpReportsTokenPortHandshakeAndRoundTripButNeverTheToken() throws Exception {
		StreamRelayServer relay = new StreamRelayServer(2000);
		closeMe.add(relay::close);
		relay.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
		UUID me = UUID.randomUUID();
		long token = 0x1234_5678_9ABC_DEF0L;
		relay.register(me, token, null);
		StreamClient client = client();
		client.connect(new StreamToken(token, relay.udpPort()));

		SelfTest.Outcome o = SelfTestSteps.udp(new SelfTest.Context(new NoPlatform(), null, client, new SelfTestReport()));
		assertEquals(Status.PASS, o.status(), o.detail());
		assertTrue(o.detail().contains("token received"), o.detail());
		assertTrue(o.detail().contains("udpPort " + relay.udpPort()), o.detail());
		assertTrue(o.detail().contains("bound"), o.detail());
		assertTrue(o.detail().matches(".*round trip \\d+ ms.*"), o.detail());
		assertNoToken(o.detail(), token);
	}

	@Test
	void udpPortZeroIsTheFallback() throws Exception {
		StreamClient client = client();
		client.connect(new StreamToken(77L, 0));
		SelfTest.Outcome o = SelfTestSteps.udp(new SelfTest.Context(new NoPlatform(), null, client, new SelfTestReport()));
		assertEquals(Status.FAIL, o.status());
		assertTrue(o.detail().contains("fallback"), o.detail());
		assertNoToken(o.detail(), 77L);
	}

	@Test
	void anUnansweredHelloSaysNoChallengeCameBack() throws Exception {
		java.net.DatagramSocket silent = new java.net.DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
		closeMe.add(silent::close);
		StreamClient client = client();
		client.connect(new StreamToken(99L, silent.getLocalPort()));
		SelfTest.Outcome o = SelfTestSteps.udp(new SelfTest.Context(new NoPlatform(), null, client, new SelfTestReport()));
		assertEquals(Status.FAIL, o.status());
		assertTrue(o.detail().contains("no challenge"), o.detail());
		assertTrue(o.detail().contains("fallback"), o.detail());
	}

	@Test
	void noTokenYetFails() throws Exception {
		SelfTest.Outcome o = SelfTestSteps.udp(new SelfTest.Context(new NoPlatform(), null, client(), new SelfTestReport()));
		assertEquals(Status.FAIL, o.status());
		assertTrue(o.detail().contains("no stream token"), o.detail());
	}

	// ------------------------------------------------------------------ helpers

	private static void assertNoToken(final String detail, final long token) {
		assertFalse(detail.contains(Long.toString(token)), detail);
		assertFalse(detail.toLowerCase().contains(Long.toHexString(token)), detail);
	}

	private StreamClient client() {
		StreamClient c = new StreamClient(new StreamClient.Payloads() {
			@Override
			public void sendFallback(final byte[] datagram) {}

			@Override
			public void sendUdpStatus(final boolean ok) {}
		}, UdpLink::open, InetAddress::getLoopbackAddress, new NoJpeg(), new NoJpeg(), id -> null, id -> null,
				id -> { throw new UnsupportedOperationException(); }, Runnable::run, Runnable::run, id -> false, System::nanoTime);
		closeMe.add(c::disconnect);
		return c;
	}

	private static SelfTest.Context context() {
		return new SelfTest.Context(new NoPlatform(), null, null, new SelfTestReport());
	}

	private static SelfTestReport run(final List<SelfTest.StepDef> steps, final long stepMs, final long totalMs) throws Exception {
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<SelfTestReport> out = new AtomicReference<>();
		SelfTest.start(steps, context(), new SelfTest.Listener() {
			@Override
			public void changed(final SelfTestReport.Line line) {}

			@Override
			public void finished(final SelfTestReport report, final String text) {
				assertNotEquals("", text);
				out.set(report);
				done.countDown();
			}
		}, stepMs, totalMs);
		assertTrue(done.await(20, TimeUnit.SECONDS), "finished");
		return out.get();
	}

	private static final class NoJpeg implements TileCodec.Jpeg {
		@Override
		public byte[] encode(final TileCodec.Tile tile, final int quality) {
			return new byte[0];
		}

		@Override
		public void decodeInto(final byte[] jpeg, final java.nio.ByteBuffer dst, final int w, final int h) {}
	}
}
