package local.carter.office.gametest;

import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.core.IdMapper;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.chunk.MissingPaletteEntryException;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/**
 * {@link PaletteRaceGuard}: the vanilla race behind the light-worker crash ({@code MissingPaletteEntryException: Missing Palette
 * entry for index 3} from {@code LightEngine.getState}), reproduced on a bare block-state {@link PalettedContainer} with no world
 * and no mod code: one thread adds a fourth palette entry in place (as the server thread does on any block change), another
 * reads that cell (as the light worker does). On Apple Silicon, without the guard, a large share of rounds throws.
 */
public class PaletteRaceGameTests {
	private static final long RUN_NANOS = 500_000_000L;

	private record Result(long rounds, long misses, long nulls) {}

	private static Result race() {
		IdMapper<Integer> ids = new IdMapper<>();
		for (int i = 0; i < 8; i++) ids.addMapping(i, i);
		Strategy<Integer> strategy = Strategy.createForBlockStates(ids);
		Integer a = 0, b = 1, c = 2, d = 3;
		Object lock = new Object();
		PalettedContainer<?>[] current = new PalettedContainer<?>[1];
		long[] counts = new long[2]; // misses, nulls
		AtomicReference<Integer> handshake = new AtomicReference<>(0); // round the reader is watching (-1: stop)
		Thread reader = new Thread(() -> {
			int last = 0;
			while (true) {
				int r;
				while ((r = handshake.get()) == last || r == -2) Thread.onSpinWait();
				if (r < 0) return;
				last = r;
				@SuppressWarnings("unchecked")
				PalettedContainer<Integer> box;
				synchronized (lock) {
					box = (PalettedContainer<Integer>) current[0];
				}
				handshake.set(-2); // ready: the writer may add the entry now
				for (int spin = 0; spin < 100_000; spin++) {
					try {
						Integer v = box.get(5, 5, 5);
						if (v == null) {
							counts[1]++;
							break;
						}
						if (v == d) break;
					} catch (MissingPaletteEntryException e) {
						counts[0]++;
						break;
					}
				}
			}
		}, "office-test palette reader");
		reader.setDaemon(true);
		reader.start();
		long rounds = 0;
		long end = System.nanoTime() + RUN_NANOS;
		try {
			for (int r = 1; System.nanoTime() < end; r++) {
				PalettedContainer<Integer> box = new PalettedContainer<>(a, strategy);
				box.set(0, 0, 0, b);
				box.set(1, 0, 0, c); // a, b, c: a 4-bit linear palette with room to grow in place
				synchronized (lock) {
					current[0] = box;
				}
				handshake.set(r);
				while (handshake.get() != -2) Thread.onSpinWait();
				box.set(5, 5, 5, d); // the fourth entry (index 3), added in place: no resize, no barrier
				rounds++;
			}
		} finally {
			handshake.set(-1);
		}
		try {
			reader.join(5_000); // happens-before: the reader's counts are visible below
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return new Result(rounds, counts[0], counts[1]);
	}

	/** The server thread is the writer, as in the crash; it is busy for {@link #RUN_NANOS} (game tests tick faster than real time). */
	@GameTest
	public void racyPaletteReadsAreHealed(GameTestHelper h) {
		Result r = race();
		h.assertTrue(r.rounds() > 1000, "rounds raced: " + r.rounds());
		h.assertValueEqual(r.misses(), 0L, "MissingPaletteEntryException in " + r.rounds() + " rounds (healed " + PaletteRaceGuard.healed() + ")");
		h.assertValueEqual(r.nulls(), 0L, "null palette entries in " + r.rounds() + " rounds");
		h.succeed();
	}
}
