package local.carter.office.gametest;

import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test-run workaround for a vanilla 26.3 data race (not our code): {@code PalettedContainer.set} grows a {@code LinearPalette}
 * or {@code HashMapPalette} in place with plain stores ({@code values[i] = v; size++}, then the storage id), and the light
 * worker reads the same section through {@code LevelChunkSection.getBlockState} with no lock. On aarch64 (Apple Silicon) the
 * reader can see the new storage id before the palette entry and throws {@code MissingPaletteEntryException}, which kills the
 * light task and leaves the server waiting forever for that chunk (the hang seen in {@code runGametest}).
 *
 * <p>The palette mixins call {@link #await} only where vanilla would throw (or return a null entry): it waits, briefly, for the
 * writer's entry to become visible and returns it. A real corruption still throws after {@link #WAIT_NANOS}. Loaded by the
 * {@code office-test} mod only, so shipped builds keep vanilla's behaviour.
 */
public final class PaletteRaceGuard {
	private static final Logger LOG = LoggerFactory.getLogger("office-test/PaletteRaceGuard");
	/** How long a racy read waits for the writer's palette entry: far above a store becoming visible, or the writer being preempted. */
	static final long WAIT_NANOS = 200_000_000L;
	private static final AtomicLong HEALED = new AtomicLong();

	private PaletteRaceGuard() {}

	/** Racy palette reads healed so far in this JVM. */
	public static long healed() {
		return HEALED.get();
	}

	/** Re-reads {@code entry} (with an acquire fence each time, so the read is never hoisted) until it is non-null or time runs out. */
	public static <T> @Nullable T await(final Supplier<@Nullable T> entry, final int index) {
		long start = System.nanoTime();
		for (int spins = 0;; spins++) {
			VarHandle.acquireFence();
			T value = entry.get();
			if (value != null) {
				if (HEALED.incrementAndGet() == 1) {
					LOG.warn("Healed a racy palette read (index {}): vanilla PalettedContainer growth seen out of order by another thread", index);
				}
				return value;
			}
			if (System.nanoTime() - start > WAIT_NANOS) return null;
			if (spins < 10_000) Thread.onSpinWait();
			else LockSupport.parkNanos(20_000L);
		}
	}
}
