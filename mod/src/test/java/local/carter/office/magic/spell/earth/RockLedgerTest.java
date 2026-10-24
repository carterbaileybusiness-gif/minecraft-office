package local.carter.office.magic.spell.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import local.carter.office.client.magic.fx.earth.RockLedger;

/** The Earth lane's rock bookkeeping: caps per caster and overall, and leashes that die when their timer stops. */
class RockLedgerTest {
	@Test
	void oneCasterCannotStarveTheOthers() {
		RockLedger l = new RockLedger();
		for (int i = 0; i < RockLedger.MAX_PER_CASTER; i++) assertTrue(l.admit(1));
		assertFalse(l.admit(1), "caster 1 is at its cap");
		assertTrue(l.admit(2), "caster 2 still gets rocks");
		assertEquals(RockLedger.MAX_PER_CASTER, l.of(1));
		assertEquals(1, l.of(2));
		l.release(1);
		assertTrue(l.admit(1), "a released rock frees a place");
	}

	@Test
	void roomIsWhatBothCapsStillAllow() {
		RockLedger l = new RockLedger();
		assertEquals(RockLedger.MAX_PER_CASTER, l.room(1));
		for (int i = 0; i < 100; i++) l.admit(1);
		assertEquals(RockLedger.MAX_PER_CASTER - 100, l.room(1));
		for (int i = 0; i < 150; i++) l.admit(2);
		assertEquals(RockLedger.MAX_LIVE - 250, l.room(3), "the total cap binds for a newcomer");
		assertEquals(Math.min(RockLedger.MAX_LIVE - 250, RockLedger.MAX_PER_CASTER - 100), l.room(1));
	}

	@Test
	void totalCapHolds() {
		RockLedger l = new RockLedger();
		int caster = 0;
		int admitted = 0;
		while (admitted < RockLedger.MAX_LIVE) {
			if (l.admit(caster)) admitted++;
			else caster++;
		}
		assertEquals(RockLedger.MAX_LIVE, l.live());
		assertFalse(l.admit(caster + 1), "nobody past the total cap");
		l.release(0);
		assertTrue(l.admit(caster + 1));
		l.release(99); // unknown caster: nothing happens
		assertEquals(RockLedger.MAX_LIVE, l.live());
		l.clear();
		assertEquals(0, l.live());
		assertEquals(0, l.of(0));
	}

	@Test
	void leashDiesOneTickAfterItsTimerStops() {
		RockLedger.Leash leash = new RockLedger.Leash(10);
		// The timer beats before the rocks tick (both at the end of a client tick, timers first).
		for (long now = 11; now <= 20; now++) {
			assertTrue(leash.alive(now), "beaten last tick: alive at " + now);
			leash.beat(now);
		}
		// Cancelled (FxKit.cancel) or out of time: no more beats.
		assertTrue(leash.alive(21));
		assertFalse(leash.alive(22));
	}
}
