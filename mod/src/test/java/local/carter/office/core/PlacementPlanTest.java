package local.carter.office.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** "Minecraft is on monitor 1; holograms are my other monitors": first hologram mirrors, the rest extend. */
class PlacementPlanTest {
    private static PlacementPlan.Existing e(Mode m, int slot) {
        return new PlacementPlan.Existing(m, slot);
    }

    @Test
    void firstHologramMirrors() {
        assertEquals(new PlacementPlan.Choice(Mode.MIRROR, 1), PlacementPlan.next(List.of()));
    }

    @Test
    void secondAndThirdExtendOntoTheNextFreeDisplay() {
        assertEquals(new PlacementPlan.Choice(Mode.EXTEND, 1), PlacementPlan.next(List.of(e(Mode.MIRROR, 1))));
        assertEquals(new PlacementPlan.Choice(Mode.EXTEND, 2),
                PlacementPlan.next(List.of(e(Mode.MIRROR, 1), e(Mode.EXTEND, 1))));
        assertEquals(new PlacementPlan.Choice(Mode.EXTEND, 4),
                PlacementPlan.next(List.of(e(Mode.MIRROR, 1), e(Mode.EXTEND, 1), e(Mode.EXTEND, 2), e(Mode.EXTEND, 3))));
    }

    @Test
    void allDisplaysTakenFallsBackToSlotOne() {
        assertEquals(new PlacementPlan.Choice(Mode.EXTEND, 1), PlacementPlan.next(List.of(e(Mode.MIRROR, 1),
                e(Mode.EXTEND, 1), e(Mode.EXTEND, 2), e(Mode.EXTEND, 3), e(Mode.EXTEND, 4))));
    }

    @Test
    void noMirrorYetMeansMirrorEvenWithOtherHolograms() {
        // e.g. the old mirror was switched to Extend, or a legacy player has only Extend/Window holograms
        assertEquals(new PlacementPlan.Choice(Mode.MIRROR, 2),
                PlacementPlan.next(List.of(e(Mode.EXTEND, 1), e(Mode.WINDOW, 3))));
    }

    @Test
    void windowAndMirrorHologramsDontUseDisplays() {
        assertEquals(new PlacementPlan.Choice(Mode.EXTEND, 1),
                PlacementPlan.next(List.of(e(Mode.MIRROR, 1), e(Mode.WINDOW, 1), e(Mode.MIRROR, 2))));
    }
}
