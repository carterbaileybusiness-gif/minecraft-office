package local.carter.office.core;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * What a newly placed hologram shows (Carter, 2026-10-23 late: every hologram is a monitor you plug in). It is EXTEND
 * on the next free virtual display (1-4; 1 again when all are taken), like plugging in monitor #2, #3. Only Extend
 * holograms use a display; Mirror and Window ones don't. Windows clients, which can't make virtual displays yet, switch
 * a hologram they just placed to Mirror ({@code PlacementDefault}). Pure.
 */
public final class PlacementPlan {
    public static final int MAX_SLOT = 4;

    /** One of the player's existing holograms. */
    public record Existing(Mode mode, int displaySlot) {}

    public record Choice(Mode mode, int displaySlot) {}

    private PlacementPlan() {}

    public static Choice next(final Collection<Existing> existing) {
        Set<Integer> used = new HashSet<>();
        for (Existing e : existing) {
            if (e.mode() == Mode.EXTEND) {
                used.add(e.displaySlot());
            }
        }
        for (int s = 1; s <= MAX_SLOT; s++) {
            if (!used.contains(s)) {
                return new Choice(Mode.EXTEND, s);
            }
        }
        return new Choice(Mode.EXTEND, 1);
    }
}
