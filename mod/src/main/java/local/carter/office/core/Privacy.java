package local.carter.office.core;

import java.util.Set;
import java.util.UUID;

/** Who may see a hologram. The owner can always view their own. */
public record Privacy(Level level, Set<UUID> allowed) {
    public enum Level { PUBLIC, PRIVATE, SHOW_TO }

    public static final Privacy PUBLIC = new Privacy(Level.PUBLIC, Set.of());
    public static final Privacy PRIVATE = new Privacy(Level.PRIVATE, Set.of());

    public Privacy {
        if (level == null) {
            level = Level.PUBLIC;
        }
        allowed = allowed == null ? Set.of() : Set.copyOf(allowed);
    }

    public boolean canView(UUID owner, UUID viewer) {
        if (viewer != null && viewer.equals(owner)) {
            return true;
        }
        return switch (level) {
            case PUBLIC -> true;
            case PRIVATE -> false;
            case SHOW_TO -> viewer != null && allowed.contains(viewer);
        };
    }
}
