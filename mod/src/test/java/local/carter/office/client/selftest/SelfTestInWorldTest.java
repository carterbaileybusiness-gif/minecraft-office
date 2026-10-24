package local.carter.office.client.selftest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import local.carter.office.client.platform.Platform;
import local.carter.office.client.pointer.PointerPort;
import local.carter.office.client.selftest.SelfTest.Outcome;
import local.carter.office.client.selftest.SelfTestReport.Status;
import local.carter.office.core.HologramGeometry;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Mode;
import local.carter.office.core.Privacy;
import local.carter.office.core.Shape;
import local.carter.office.core.Vec3d;

/** Self-test on a Mac after zoom was retired: the zoom step skips, the in-world pointer step checks the helper. */
class SelfTestInWorldTest {
	private static final HologramSpec SPEC = new HologramSpec(UUID.randomUUID(), Shape.WIDE_16_9, Mode.EXTEND, Privacy.PUBLIC, 1, "", 2, 0, 1, 0, 0);
	private static final SelfTest.ZoomTarget TARGET = new SelfTest.ZoomTarget(SPEC,
			new HologramGeometry.Frame(new Vec3d(0, 65, 0), new Vec3d(1, 0, 0), new Vec3d(0, 1, 0), 2, 1.125), 70, 1440, 900);

	/** A platform that zooms (Windows) or not (Mac), with or without an in-world pointer. */
	static final class Fake implements Platform {
		final boolean zooms;
		final @Nullable PointerPort pointer;

		Fake(final boolean zooms, final @Nullable PointerPort pointer) {
			this.zooms = zooms;
			this.pointer = pointer;
		}

		@Override
		public boolean supportsExtend() {
			return true;
		}

		@Override
		public List<WindowInfo> windows() {
			return List.of();
		}

		@Override
		public void zoomIn(final HologramSpec spec, final Rect mcWindow) {}

		@Override
		public void zoomOut() {}

		@Override
		public void onExitRequested(final Runnable r) {}

		@Override
		public boolean supportsZoom() {
			return zooms;
		}

		@Override
		public @Nullable PointerPort pointer() {
			return pointer;
		}
	}

	private static SelfTest.Context ctx(final Fake p) {
		return new SelfTest.Context(p, null, null, new SelfTestReport(), TARGET);
	}

	@Test
	void zoomStepSkipsWhereZoomIsNotUsed() {
		Outcome mac = SelfTestSteps.zoom(ctx(new Fake(false, null)));
		assertEquals(Outcome.skip("Zoom isn't used on this computer — you use screens in-world"), mac);
		assertEquals(Status.PASS, SelfTestSteps.zoom(ctx(new Fake(true, null))).status(), "Windows still zooms");
	}

	@Test
	void pointerStepNeedsTheHelperAndAccessibility() {
		assertEquals(Status.SKIP, SelfTestSteps.pointer(ctx(new Fake(true, null))).status(), "no in-world pointer here");
		PointerPort broken = new PointerPortStub("OfficeHelper needs macOS Accessibility");
		Outcome fail = SelfTestSteps.pointer(ctx(new Fake(false, broken)));
		assertEquals(Status.FAIL, fail.status());
		assertEquals("OfficeHelper needs macOS Accessibility", fail.detail());
		assertEquals(Status.PASS, SelfTestSteps.pointer(ctx(new Fake(false, new PointerPortStub(null)))).status());
	}

	/** Only {@link PointerPort#problem} matters to the self-test. */
	record PointerPortStub(@Nullable String why) implements PointerPort {
		@Override
		public @Nullable String problem() {
			return why;
		}
	}
}
