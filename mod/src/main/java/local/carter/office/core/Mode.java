package local.carter.office.core;

/**
 * What a hologram shows. EXTEND (the default for new holograms): a new virtual display, an extra monitor. WINDOW: one
 * app window. MIRROR: the owner's real main screen with Minecraft's own window left out of the capture (view-only on
 * Mac; Windows' default, as it has no virtual displays yet).
 *
 * <p>Order matters on the wire ({@code readEnum} sends ordinals): new values are only ever appended, so a jar that
 * doesn't know a value never reads an old one as something else. Everything persisted stores the name.
 */
public enum Mode {
    EXTEND,
    WINDOW,
    MIRROR
}
