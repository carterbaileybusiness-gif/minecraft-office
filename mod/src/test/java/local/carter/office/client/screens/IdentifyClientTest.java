package local.carter.office.client.screens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

/** Identify labels last 3 s; a new Identify replaces the one showing. */
class IdentifyClientTest {
	private static final BlockPos A = new BlockPos(0, 64, 0);
	private static final BlockPos B = new BlockPos(5, 64, 0);

	@Test
	void threeSeconds() {
		IdentifyClient c = new IdentifyClient();
		long t = 1_000_000;
		c.show(Map.of(A, "1"), t);
		assertEquals("1", c.labelAt(A, t));
		assertEquals("1", c.labelAt(A, t + 2999));
		assertNull(c.labelAt(A, t + 3000), "gone after 3 s");
		assertNull(c.labelAt(B, t), "only the holograms it named");
	}

	@Test
	void aSecondShowReplacesTheFirst() {
		IdentifyClient c = new IdentifyClient();
		c.show(Map.of(A, "1"), 0);
		c.show(Map.of(B, "B"), 2000);
		assertNull(c.labelAt(A, 2001), "the first one's labels are gone");
		assertEquals("B", c.labelAt(B, 4999), "and the new one has its own 3 s");
	}
}
