package local.carter.office.core;

/**
 * How an emitter is attached (pure math, shared by server and client).
 *
 * <ul>
 *   <li>{@link #FLOOR}: a projector base on the ground; the hologram floats above it (the original behaviour).</li>
 *   <li>{@link #WALL}: a flat mount on a wall; the hologram hangs on the wall like a painting, its content plane
 *       {@link #WALL_GAP} blocks off the wall surface, facing out. It always stays on that plane.</li>
 *   <li>{@link #CEILING}: a flat mount on a ceiling; the hologram hangs below it.</li>
 * </ul>
 *
 * <p>Conventions as in {@code HologramSpec}: offsets are blocks from the emitter block's centre; {@code yaw} is the
 * Minecraft yaw the hologram's front faces (0 = south/+Z, 90 = west). The front normal is (-sin yaw, 0, cos yaw), right
 * is (cos yaw, 0, sin yaw).
 */
public enum Mount {
    FLOOR, WALL, CEILING;

    /** Distance (blocks) between the wall surface and a wall hologram's content plane. */
    public static final double WALL_GAP = 0.03;
    /** Content-plane offset along the front normal from the emitter centre for a wall hologram (the wall is behind). */
    public static final double WALL_PLANE = -(0.5 - WALL_GAP);
    /** Gap between a floor emitter's top and the hologram's bottom edge at placement. */
    public static final double FLOOR_GAP = 0.1;
    /** Bottom of a ceiling mount (it is 1 px thick), relative to the emitter centre. */
    public static final double CEILING_MOUNT_BOTTOM = 0.5 - 1.0 / 16.0;
    /** Gap between a ceiling mount and the hologram's top edge at placement. */
    public static final double CEILING_GAP = 0.1;
    /** Each scroll notch scales the width by this factor. */
    public static final double SCROLL_STEP = 1.1;

    /** Unit front normal of a hologram facing {@code yaw}. */
    public static Vec3d normal(final double yaw) {
        double r = Math.toRadians(yaw);
        return new Vec3d(snap(-Math.sin(r)), 0, snap(Math.cos(r)));
    }

    /** Removes floating-point dust (sin(180°) = 1.2e-16) so cardinal directions are exact. */
    private static double snap(final double v) {
        return Math.abs(v) < 1e-12 ? 0.0 : v;
    }

    /** Default offsets (x, y, z) of a freshly placed hologram of {@code height} facing {@code yaw}. */
    public Vec3d defaultOffsets(final double yaw, final double height) {
        return switch (this) {
            case FLOOR -> new Vec3d(0, 0.5 + height / 2 + FLOOR_GAP, 0);
            case WALL -> normal(yaw).scale(WALL_PLANE);
            case CEILING -> new Vec3d(0, CEILING_MOUNT_BOTTOM - CEILING_GAP - height / 2, 0);
        };
    }

    /**
     * Keeps a spec where this mount allows it. A wall hologram is projected back onto its wall plane and turned to face
     * out ({@code facingYaw}); floor and ceiling holograms are free. Applied by the server to every edit.
     */
    public HologramSpec constrain(final HologramSpec s, final float facingYaw) {
        if (this != WALL) {
            return s;
        }
        Vec3d n = normal(facingYaw);
        Vec3d off = new Vec3d(s.offX(), s.offY(), s.offZ());
        double along = off.dot(n);
        Vec3d onPlane = off.sub(n.scale(along - WALL_PLANE));
        return new HologramSpec(s.owner(), s.shape(), s.mode(), s.privacy(), s.displaySlot(), s.windowKey(), s.width(),
                onPlane.x(), onPlane.y(), onPlane.z(), facingYaw, s.mirrorDisplay());
    }

    /**
     * Width after {@code notches} scroll notches (each ×1.1, negative ÷1.1), clamped to [0.5, 12]. The anchor stays put:
     * the bottom edge on the floor, the centre on a wall, the top edge under a ceiling. Only offY changes besides width.
     */
    public HologramSpec scrollResize(final HologramSpec s, final int notches) {
        double w = HologramSpec.clampWidth(s.width() * Math.pow(SCROLL_STEP, notches));
        double h = w / s.shape().aspect();
        double offY = switch (this) {
            case FLOOR -> s.offY() - s.height() / 2 + h / 2;
            case WALL -> s.offY();
            case CEILING -> s.offY() + s.height() / 2 - h / 2;
        };
        return new HologramSpec(s.owner(), s.shape(), s.mode(), s.privacy(), s.displaySlot(), s.windowKey(), w, s.offX(), offY,
                s.offZ(), s.yaw(), s.mirrorDisplay());
    }
}
