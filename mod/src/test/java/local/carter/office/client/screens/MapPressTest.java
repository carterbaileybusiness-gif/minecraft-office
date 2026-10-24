package local.carter.office.client.screens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import local.carter.office.client.platform.Platform.Rect;
import local.carter.office.client.screens.DisplayMapModel.Choice;
import local.carter.office.core.Mode;
import local.carter.office.core.Shape;

/**
 * The display map's clicks as the widget makes them: a press and a release at map pixels, each hit-tested afresh on the
 * map's geometry (the widget's own {@link MapPress#hit} over {@link DisplayMapModel#drawOrder}), never the model's
 * click rules called directly.
 */
class MapPressTest {
	private static final Screen MAIN = new Screen("MAIN", 0, true, "MacBook", 0, 0, 1440, 900);
	private static final Screen OFFICE_1 = new Screen("O1", 1, false, null, 1440, 0, 1920, 1080);
	/** The widget's screens area: x, y, w, h (a 160-px column less the insets, above the status line). */
	private static final int AX = 24;
	private static final int AY = 40;
	private static final int AW = 152;
	private static final int AH = 72;

	private final DisplayMapModel model = new DisplayMapModel(new ScreenList(List.of(MAIN, OFFICE_1), ScreenList.Abilities.ALL),
			Mode.EXTEND, 1, null, Shape.WIDE_16_9);
	private final MapPress press = new MapPress(model);

	private MapLayout.Fit fit() {
		return MapLayout.fit(model.fitRects(), AW, AH);
	}

	private Screen hit(final double x, final double y) {
		return MapPress.hit(model.drawOrder(), fit(), AX, AY, x, y);
	}

	private double[] centre(final String id) {
		for (Screen s : model.drawOrder()) {
			if (s.id().equals(id)) {
				Rect b = MapPress.box(s, fit(), AX, AY);
				return new double[] {b.x() + b.w() / 2.0, b.y() + b.h() / 2.0};
			}
		}
		throw new AssertionError("no box " + id);
	}

	private Choice click(final String id) {
		double[] c = centre(id);
		press.press(hit(c[0], c[1]), c[0], c[1]);
		MapPress.Release r = press.release(hit(c[0], c[1]));
		assertFalse(r.dropped(), "a click is not a drop");
		return r.choice();
	}

	@Test
	void clickingADashedSlotPicksIt() {
		String ghost = DisplayMapModel.GHOST + 3;
		double[] c = centre(ghost);
		Screen down = hit(c[0], c[1]);
		Screen up = hit(c[0], c[1]);
		assertNotNull(down);
		assertEquals(ghost, down.id(), "the dashed slot is under the mouse");
		assertNotSame(down, up, "every hit test makes the dashed slot anew (why boxes compare by id)");
		assertEquals(new Choice.Slot(3), click(ghost), "Extend on 3, which doesn't exist yet");
		assertEquals(new Choice.Slot(2), click(DisplayMapModel.GHOST + 2));
		assertEquals(new Choice.Slot(1), click("O1"));
		assertTrue(MapPress.same(down, up), "hover highlights the dashed slot too");
	}

	@Test
	void clickingADisplayMovedButNotAppliedPicksIt() {
		assertTrue(model.drag("O1", 0, -1100));
		assertTrue(model.drop("O1"));
		assertEquals(List.of(new Origin(1, 0, -1080)), model.pending());
		assertEquals(new Choice.Slot(1), click("O1"), "at its new, not yet Applied spot");
	}

	@Test
	void aPressReleasedOverAnotherBoxPicksNothing() {
		double[] a = centre("O1");
		double[] b = centre(DisplayMapModel.GHOST + 2);
		press.press(hit(a[0], a[1]), a[0], a[1]);
		assertEquals(new Choice.None(), press.release(hit(b[0], b[1])).choice());
	}

	@Test
	void aDragPastTheThresholdDrops() {
		double[] c = centre("O1");
		press.press(hit(c[0], c[1]), c[0], c[1]);
		press.drag(c[0] + MapPress.DRAG_THRESHOLD, c[1], this::fit);
		assertFalse(press.dragging(), "within the threshold: still a click");
		press.drag(c[0] - 60, c[1] - 40, this::fit);
		assertTrue(press.dragging());
		assertEquals("O1", model.dragging());
		MapPress.Release r = press.release(hit(c[0] - 60, c[1] - 40));
		assertTrue(r.dropped());
		assertEquals(new Choice.None(), r.choice());
		assertFalse(model.pending().isEmpty(), "snapped somewhere new");
	}
}
