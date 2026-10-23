package local.carter.office.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import io.netty.channel.local.LocalAddress;
import org.junit.jupiter.api.Test;

class StreamServiceTest {
	@Test
	void fallbackSendsRunOnTheServerThreadAndRecheckThePlayer() {
		Queue<Runnable> serverThread = new ArrayDeque<>();
		AtomicBoolean online = new AtomicBoolean(true);
		List<byte[]> sent = new ArrayList<>();
		Consumer<byte[]> sender = StreamService.onServerThread(serverThread::add, online::get, sent::add);

		sender.accept(new byte[] {1}); // as if from the relay's Netty thread
		assertTrue(sent.isEmpty(), "nothing sent off the server thread");
		assertEquals(1, serverThread.size(), "handed to the server thread");
		serverThread.poll().run();
		assertEquals(1, sent.size(), "sent on the server thread");

		sender.accept(new byte[] {2});
		online.set(false); // player left before the server thread got to it
		serverThread.poll().run();
		assertEquals(1, sent.size(), "a stale player gets nothing");
	}

	@Test
	void udpMustComeFromTheGameConnectionsIp() throws Exception {
		InetAddress ip = InetAddress.getByName("203.0.113.5");
		assertEquals(ip, StreamService.gameAddress(new InetSocketAddress(ip, 51234)));
		assertNull(StreamService.gameAddress(new LocalAddress("singleplayer")), "memory connection = loopback only");
		assertNull(StreamService.gameAddress(null));
	}
}
