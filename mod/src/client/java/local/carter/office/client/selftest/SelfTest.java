package local.carter.office.client.selftest;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import local.carter.office.client.platform.Platform;
import local.carter.office.client.selftest.SelfTestReport.Line;
import local.carter.office.client.selftest.SelfTestReport.Status;
import local.carter.office.client.stream.StreamClient;
import local.carter.office.core.HologramGeometry;
import local.carter.office.core.HologramSpec;

/**
 * The screen self-test: an ordered list of steps run one after another on a background thread, each on its own
 * daemon thread with a {@link #STEP_TIMEOUT_MS} limit and all of them within {@link #TOTAL_TIMEOUT_MS}, so a stuck
 * capture or native call never freezes the game or the test. A step that throws (anything, even a LinkageError) or
 * hangs is recorded as failed with the reason; steps left when the total limit runs out are skipped.
 *
 * <p>Other features add their own checks with {@link #registerStep} (same id: replaced in place). No Minecraft classes
 * here, so the runner is unit-tested.
 */
public final class SelfTest {
	private static final Logger LOGGER = LogUtils.getLogger();
	public static final long STEP_TIMEOUT_MS = 5_000;
	public static final long TOTAL_TIMEOUT_MS = 30_000;

	/** A step's verdict; {@code detail} is short, plain words (app names only, never window titles or tokens). */
	public record Outcome(Status status, String detail) {
		public static Outcome pass(final String detail) {
			return new Outcome(Status.PASS, detail);
		}

		public static Outcome fail(final String detail) {
			return new Outcome(Status.FAIL, detail);
		}

		public static Outcome skip(final String detail) {
			return new Outcome(Status.SKIP, detail);
		}
	}

	/** One check. Runs on its own thread; should give up when interrupted (the runner interrupts it at the timeout). */
	@FunctionalInterface
	public interface Step {
		Outcome run(Context ctx) throws Exception;
	}

	public record StepDef(String id, String name, Step step) {}

	/**
	 * What the steps may use: this computer's platform, the window the player picked for the real-capture check (null:
	 * skip it — the self-test never captures a window the player didn't choose), the shared-screen client (null outside a
	 * world), the report (facts already collected on the client thread) and the zoom dry run's hologram (null: none).
	 */
	public record Context(Platform platform, Platform.@Nullable WindowInfo window, @Nullable StreamClient stream, SelfTestReport report,
			@Nullable ZoomTarget zoom) {
		public Context(final Platform platform, final Platform.@Nullable WindowInfo window, final @Nullable StreamClient stream,
				final SelfTestReport report) {
			this(platform, window, stream, report, null);
		}
	}

	/**
	 * The player's first loaded own hologram for the zoom dry run (read on the client thread): its spec, its panel in the
	 * world, the FOV and the game window size. Null: no own hologram nearby.
	 */
	public record ZoomTarget(HologramSpec spec, HologramGeometry.Frame frame, double fovDeg, int windowW, int windowH) {}

	/** Progress for the live checklist and the end result. Called on the self-test thread. */
	public interface Listener {
		void changed(Line line);

		void finished(SelfTestReport report, String text);
	}

	private static final List<StepDef> STEPS = new ArrayList<>();

	static {
		SelfTestSteps.registerDefaults();
	}

	private SelfTest() {}

	/** Adds a step, or replaces the step with the same id in place (keeping the checklist order). */
	public static void registerStep(final String id, final String name, final Step step) {
		synchronized (STEPS) {
			StepDef def = new StepDef(id, name, step);
			for (int i = 0; i < STEPS.size(); i++) {
				if (STEPS.get(i).id().equals(id)) {
					STEPS.set(i, def);
					return;
				}
			}
			STEPS.add(def);
		}
	}

	public static List<StepDef> steps() {
		synchronized (STEPS) {
			return List.copyOf(STEPS);
		}
	}

	/** Java/OS/CPU/memory facts (no Minecraft needed). */
	public static void basicInfo(final SelfTestReport report) {
		Runtime rt = Runtime.getRuntime();
		report.info("Java", System.getProperty("java.version", "?") + " (" + System.getProperty("java.vendor", "?") + ")");
		report.info("OS", System.getProperty("os.name", "?") + " " + System.getProperty("os.version", "?") + " ("
				+ System.getProperty("os.arch", "?") + ")");
		report.info("CPU cores", Integer.toString(rt.availableProcessors()));
		report.info("Max memory", (rt.maxMemory() / (1024 * 1024)) + " MB for Minecraft" + physicalMemory());
	}

	private static String physicalMemory() {
		try {
			if (java.lang.management.ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
				return String.format(Locale.ROOT, ", %.1f GB in the computer", os.getTotalMemorySize() / (1024.0 * 1024 * 1024));
			}
		} catch (Throwable t) {
			// not available on this JVM
		}
		return "";
	}

	/** A running self-test; {@link #cancel} stops it (the current step is interrupted, the rest are skipped). */
	public static final class Run {
		private final Thread thread;
		private volatile boolean cancelled;
		private final AtomicReference<@Nullable FutureTask<Outcome>> current = new AtomicReference<>();

		private Run(final Runnable body) {
			this.thread = new Thread(body, "office-selftest");
			this.thread.setDaemon(true);
		}

		public void cancel() {
			cancelled = true;
			FutureTask<Outcome> t = current.get();
			if (t != null) {
				t.cancel(true);
			}
			thread.interrupt();
		}

		public boolean isAlive() {
			return thread.isAlive();
		}
	}

	public static Run start(final List<StepDef> steps, final Context ctx, final Listener listener) {
		return start(steps, ctx, listener, STEP_TIMEOUT_MS, TOTAL_TIMEOUT_MS);
	}

	/** {@link #start(List, Context, Listener)} with other limits (tests). */
	public static Run start(final List<StepDef> steps, final Context ctx, final Listener listener, final long stepMs, final long totalMs) {
		Run[] self = new Run[1];
		Run run = new Run(() -> runAll(self[0], steps, ctx, listener, stepMs, totalMs));
		self[0] = run;
		run.thread.start();
		return run;
	}

	private static void runAll(final Run run, final List<StepDef> steps, final Context ctx, final Listener listener, final long stepMs,
			final long totalMs) {
		SelfTestReport report = ctx.report();
		for (StepDef s : steps) {
			report.result(new Line(s.id(), s.name(), Status.PENDING, "", -1));
		}
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalMs);
		for (StepDef s : steps) {
			long leftMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
			Line line;
			if (run.cancelled) {
				line = new Line(s.id(), s.name(), Status.SKIP, "cancelled", -1);
			} else if (leftMs <= 0) {
				line = new Line(s.id(), s.name(), Status.SKIP, "not run: the self-test's " + seconds(totalMs) + " limit was reached", -1);
			} else {
				notify(listener, new Line(s.id(), s.name(), Status.RUNNING, "", -1));
				long t0 = System.nanoTime();
				Outcome o = runOne(run, s, ctx, Math.min(stepMs, leftMs), stepMs, totalMs);
				line = new Line(s.id(), s.name(), o.status(), o.detail() == null ? "" : o.detail(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
			}
			report.result(line);
			notify(listener, line);
		}
		String text;
		try {
			text = report.render(ZonedDateTime.now());
		} catch (Throwable t) {
			LOGGER.warn("[office] Self-test report failed", t);
			text = "Minecraft Office screen self-test\nThe report could not be written: " + describe(t) + "\n";
		}
		try {
			listener.finished(report, text);
		} catch (Throwable t) {
			LOGGER.warn("[office] Self-test listener failed", t);
		}
	}

	private static Outcome runOne(final Run run, final StepDef s, final Context ctx, final long limitMs, final long stepMs, final long totalMs) {
		FutureTask<Outcome> task = new FutureTask<>(() -> s.step().run(ctx));
		Thread t = new Thread(task, "office-selftest-" + s.id());
		t.setDaemon(true);
		run.current.set(task);
		try {
			t.start();
			Outcome o = task.get(limitMs, TimeUnit.MILLISECONDS);
			return o == null ? Outcome.fail("the step gave no result") : o;
		} catch (TimeoutException e) {
			task.cancel(true);
			return Outcome.fail(limitMs < stepMs ? "timed out (the self-test's " + seconds(totalMs) + " limit was reached)"
					: "timed out after " + seconds(stepMs));
		} catch (ExecutionException e) {
			Throwable cause = e.getCause() == null ? e : e.getCause();
			LOGGER.info("[office] Self-test step {} failed: {}", s.id(), ctx.report().scrubDetail(cause.toString()));
			return Outcome.fail(describe(cause));
		} catch (InterruptedException | CancellationException e) {
			task.cancel(true);
			return Outcome.skip("cancelled");
		} catch (Throwable e) {
			task.cancel(true);
			return Outcome.fail(describe(e));
		} finally {
			run.current.set(null);
		}
	}

	private static void notify(final Listener listener, final Line line) {
		try {
			listener.changed(line);
		} catch (Throwable t) {
			LOGGER.warn("[office] Self-test checklist update failed", t);
		}
	}

	/** "LinkageError: no natives here" (plus the first cause), never a stack trace. */
	static String describe(final Throwable t) {
		String s = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
		Throwable c = t.getCause();
		if (c != null && c != t && (t.getMessage() == null || !t.getMessage().contains(c.toString()))) {
			s += " (cause: " + c.getClass().getSimpleName() + (c.getMessage() == null ? "" : ": " + c.getMessage()) + ")";
		}
		return s;
	}

	private static String seconds(final long ms) {
		return ms % 1000 == 0 ? (ms / 1000) + " s" : ms + " ms";
	}
}
