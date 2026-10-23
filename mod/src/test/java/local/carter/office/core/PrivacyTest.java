package local.carter.office.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PrivacyTest {
    private final UUID owner = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();

    @Test
    void ownerAlwaysViews() {
        assertTrue(new Privacy(Privacy.Level.PUBLIC, Set.of()).canView(owner, owner));
        assertTrue(new Privacy(Privacy.Level.PRIVATE, Set.of()).canView(owner, owner));
        assertTrue(new Privacy(Privacy.Level.SHOW_TO, Set.of(b)).canView(owner, owner));
    }

    @Test
    void publicShowsToStranger() {
        assertTrue(new Privacy(Privacy.Level.PUBLIC, Set.of()).canView(owner, b));
    }

    @Test
    void privateHidesFromStranger() {
        assertFalse(new Privacy(Privacy.Level.PRIVATE, Set.of(b)).canView(owner, b));
    }

    @Test
    void showToOnlyListed() {
        Privacy p = new Privacy(Privacy.Level.SHOW_TO, Set.of(b));
        assertTrue(p.canView(owner, b));
        assertFalse(p.canView(owner, c));
    }

    @Test
    void defaultIsPublic() {
        assertTrue(Privacy.PUBLIC.canView(owner, c));
    }
}
