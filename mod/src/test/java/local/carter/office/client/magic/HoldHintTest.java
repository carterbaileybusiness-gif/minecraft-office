package local.carter.office.client.magic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class HoldHintTest {
	@Test
	void showsAtMostOncePerThirtySeconds() {
		AtomicLong clock = new AtomicLong(1_000);
		HoldHint hint = new HoldHint(clock::get);
		assertTrue(hint.tryShow(), "first time");
		clock.set(1_000 + 29_999);
		assertFalse(hint.tryShow(), "29 999 ms later");
		clock.set(1_000 + 30_000);
		assertTrue(hint.tryShow(), "30 000 ms later");
		clock.set(1_000 + 30_000 + 29_999);
		assertFalse(hint.tryShow(), "the window restarts from the last shown");
	}

	@Test
	void firstShowWorksEvenAtClockZero() {
		assertTrue(new HoldHint(() -> 0L).tryShow());
	}
}
