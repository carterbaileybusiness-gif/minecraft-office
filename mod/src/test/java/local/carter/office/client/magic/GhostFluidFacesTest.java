package local.carter.office.client.magic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import local.carter.office.client.magic.fx.GhostBlocks;
import local.carter.office.client.magic.fx.GhostBlocks.FluidBody;
import local.carter.office.client.magic.fx.GhostBlocks.FluidCell;

/** Ghost water's face culling: faces shared by cells of one fx event's body are not drawn; another event's cells never cull. */
class GhostFluidFacesTest {
	private static final double EPS = 1.0E-9;

	/** A cell of {@code body} at rest in its place, {@code h} deep. */
	private static FluidCell cell(FluidBody body, int x, int y, int z, float h) {
		return body.put(x, y, z, h).at(0, 0, y, y + h);
	}

	@Test
	void sharedSideOfTheSameBodyIsCulled() {
		FluidBody body = new FluidBody();
		FluidCell a = cell(body, 0, 64, 0, 1F);
		FluidCell b = cell(body, 1, 64, 0, 1F);
		assertEquals(0, body.sideShown(a, GhostBlocks.EAST).length, "a's east side is b's west side");
		assertEquals(0, body.sideShown(b, GhostBlocks.WEST).length);
		assertArrayEquals(new double[] {64, 65}, body.sideShown(a, GhostBlocks.WEST), EPS, "the outer side shows whole");
		assertArrayEquals(new double[] {64, 65}, body.sideShown(a, GhostBlocks.NORTH), EPS);
	}

	@Test
	void anotherEventsWaterNeverCulls() {
		FluidBody mine = new FluidBody();
		FluidBody theirs = new FluidBody();
		FluidCell a = cell(mine, 0, 64, 0, 1F);
		cell(theirs, 1, 64, 0, 1F);
		cell(theirs, 0, 65, 0, 1F);
		assertArrayEquals(new double[] {64, 65}, mine.sideShown(a, GhostBlocks.EAST), EPS, "two events stay two bodies");
		assertTrue(mine.capShown(a, GhostBlocks.UP));
		assertNull(mine.neighbour(a, GhostBlocks.EAST));
	}

	@Test
	void aSlabIsOneBodyWithOnlyItsOuterFaces() {
		// 5 x 3, one deep: 5*2 + 3*2 = 16 outer sides, 15 tops, 15 floors; every inner side culled.
		FluidBody body = new FluidBody();
		FluidCell[][] slab = new FluidCell[5][3];
		for (int x = 0; x < 5; x++) {
			for (int z = 0; z < 3; z++) {
				slab[x][z] = cell(body, x, 64, z, 1F);
			}
		}
		int sides = 0;
		int caps = 0;
		for (FluidCell[] row : slab) {
			for (FluidCell c : row) {
				for (int f = GhostBlocks.NORTH; f <= GhostBlocks.EAST; f++) {
					if (body.sideShown(c, f).length > 0) sides++;
				}
				if (body.capShown(c, GhostBlocks.UP)) caps++;
				if (body.capShown(c, GhostBlocks.DOWN)) caps++;
			}
		}
		assertEquals(16, sides);
		assertEquals(30, caps);
	}

	@Test
	void aShallowerNeighbourLeavesTheTopOfTheSideShowing() {
		FluidBody body = new FluidBody();
		FluidCell deep = cell(body, 0, 64, 0, 1F);
		FluidCell shallow = cell(body, 0, 64, 1, 0.4F);
		assertArrayEquals(new double[] {64.4, 65}, body.sideShown(deep, GhostBlocks.SOUTH), 1.0E-5, "above the shallow water only");
		assertEquals(0, body.sideShown(shallow, GhostBlocks.NORTH).length, "the deep water covers the shallow side");
	}

	@Test
	void aNeighbourRaisedByItsMotionUncoversTheSideBelowIt() {
		FluidBody body = new FluidBody();
		FluidCell a = cell(body, 0, 64, 0, 1F);
		FluidCell popped = body.put(1, 64, 0, 1F).at(0, 0, 64.3, 65.3);
		assertArrayEquals(new double[] {64, 64.3}, body.sideShown(a, GhostBlocks.EAST), 1.0E-6);
		assertArrayEquals(new double[] {65, 65.3}, body.sideShown(popped, GhostBlocks.WEST), 1.0E-6);
		// A neighbour held inside the other's span on both ends leaves two spans.
		FluidCell tall = body.put(0, 64, 5, 1F).at(0, 0, 64, 65);
		body.put(1, 64, 5, 0.4F).at(0, 0, 64.3, 64.7);
		assertArrayEquals(new double[] {64, 64.3, 64.7, 65}, body.sideShown(tall, GhostBlocks.EAST), 1.0E-6);
	}

	@Test
	void stackedCellsShareNoCapButAGapKeepsBoth() {
		FluidBody body = new FluidBody();
		FluidCell low = cell(body, 0, 64, 0, 1F);
		FluidCell high = cell(body, 0, 65, 0, 1F);
		assertFalse(body.capShown(low, GhostBlocks.UP));
		assertFalse(body.capShown(high, GhostBlocks.DOWN));
		assertTrue(body.capShown(high, GhostBlocks.UP));
		// Half-full water under a cell: its surface shows (air between), and so does the floor above it.
		FluidBody gap = new FluidBody();
		FluidCell half = cell(gap, 0, 64, 0, 0.5F);
		FluidCell over = cell(gap, 0, 65, 0, 1F);
		assertTrue(gap.capShown(half, GhostBlocks.UP));
		assertTrue(gap.capShown(over, GhostBlocks.DOWN));
	}

	@Test
	void cellsOutOfLineOrNotDrawnDoNotCull() {
		FluidBody body = new FluidBody();
		FluidCell a = cell(body, 0, 64, 0, 1F);
		FluidCell b = body.put(1, 64, 0, 1F).at(0.6, 0, 64, 65); // spiralling in, off its place
		assertArrayEquals(new double[] {64, 65}, body.sideShown(a, GhostBlocks.EAST), EPS);
		b.at(0, 0, 64, 65);
		assertSame(b, body.neighbour(a, GhostBlocks.EAST));
		b.shown = false; // drained away this frame
		assertArrayEquals(new double[] {64, 65}, body.sideShown(a, GhostBlocks.EAST), EPS);
	}

	@Test
	void aCellAtTheSamePlaceReplacesTheOldOne() {
		FluidBody body = new FluidBody();
		FluidCell first = cell(body, 0, 64, 0, 1F);
		FluidCell again = cell(body, 0, 64, 0, 0.5F);
		assertEquals(1, body.size());
		body.remove(first); // the old one going does not take the new one with it
		assertEquals(1, body.size());
		FluidCell side = cell(body, 1, 64, 0, 1F);
		assertSame(again, body.neighbour(side, GhostBlocks.WEST));
		body.remove(again);
		assertEquals(1, body.size());
	}

	@Test
	void negativeCoordinatesKeepTheirNeighbours() {
		FluidBody body = new FluidBody();
		FluidCell a = cell(body, -1, -5, -1, 1F);
		FluidCell b = cell(body, 0, -5, -1, 1F);
		FluidCell c = cell(body, -1, -5, 0, 1F);
		assertSame(b, body.neighbour(a, GhostBlocks.EAST));
		assertSame(c, body.neighbour(a, GhostBlocks.SOUTH));
		assertNull(body.neighbour(a, GhostBlocks.WEST));
	}
}
