package local.carter.office.core;

import java.util.Optional;

/**
 * Ray / quad math for hologram monitors. The frame is a rectangle centred at {@code center},
 * spanned by the unit vectors {@code right} and {@code up}. (u,v) are texture coordinates:
 * u grows along {@code right}, v grows downward (v=0 is the top edge).
 */
public final class HologramGeometry {
    /** A grab handle counts when the hit is within this many blocks of a corner. */
    public static final double CORNER_RADIUS = 0.25;

    private static final double EPS = 1e-9;

    private HologramGeometry() {}

    public enum Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    public record Frame(Vec3d center, Vec3d right, Vec3d up, double w, double h) {}

    /** {@code corner} is null unless the hit is within {@link #CORNER_RADIUS} of a corner. */
    public record Hit(double u, double v, double distance, Corner corner) {}

    /** Two-sided ray test; {@code dir} need not be normalised. Empty on miss, parallel, or hit behind origin. */
    public static Optional<Hit> hit(Vec3d origin, Vec3d dir, Frame f) {
        Vec3d normal = f.right().cross(f.up());
        double denom = dir.dot(normal);
        if (Math.abs(denom) < EPS) {
            return Optional.empty();
        }
        double t = f.center().sub(origin).dot(normal) / denom;
        if (t < 0) {
            return Optional.empty();
        }
        Vec3d rel = origin.add(dir.scale(t)).sub(f.center());
        double x = rel.dot(f.right());
        double y = rel.dot(f.up());
        double u = x / f.w() + 0.5;
        double v = 0.5 - y / f.h();
        if (u < 0 || u > 1 || v < 0 || v > 1) {
            return Optional.empty();
        }
        return Optional.of(new Hit(u, v, t * dir.length(), cornerAt(x, y, f)));
    }

    private static Corner cornerAt(double x, double y, Frame f) {
        double hw = f.w() / 2;
        double hh = f.h() / 2;
        double dx = hw - Math.abs(x);
        double dy = hh - Math.abs(y);
        if (Math.sqrt(dx * dx + dy * dy) >= CORNER_RADIUS) {
            return null;
        }
        boolean top = y >= 0;
        boolean left = x < 0;
        if (top) {
            return left ? Corner.TOP_LEFT : Corner.TOP_RIGHT;
        }
        return left ? Corner.BOTTOM_LEFT : Corner.BOTTOM_RIGHT;
    }

    /** Texture pixel under the hit, clamped to [0, texW-1] x [0, texH-1]. */
    public static int[] toPixel(Hit hit, int texW, int texH) {
        int x = (int) Math.floor(hit.u() * texW);
        int y = (int) Math.floor(hit.v() * texH);
        return new int[] {Math.max(0, Math.min(texW - 1, x)), Math.max(0, Math.min(texH - 1, y))};
    }

    /**
     * New width when corner {@code c} is dragged to {@code planePoint} (a point on the frame's plane)
     * with the opposite corner fixed. Driven by the horizontal distance from the fixed edge, so the
     * aspect ratio is preserved by the caller deriving height from the returned width; {@code aspect}
     * is accepted for API symmetry. Result is clamped to [0.5, 12.0].
     */
    public static double widthFromDrag(Frame f, Corner c, Vec3d planePoint, double aspect) {
        boolean rightSide = c == Corner.TOP_RIGHT || c == Corner.BOTTOM_RIGHT;
        double px = planePoint.sub(f.center()).dot(f.right());
        double width = rightSide ? px + f.w() / 2 : f.w() / 2 - px;
        return HologramSpec.clampWidth(width);
    }
}
