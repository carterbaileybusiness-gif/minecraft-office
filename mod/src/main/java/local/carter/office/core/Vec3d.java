package local.carter.office.core;

/** Minimal 3D vector so core has no Minecraft dependency. */
public record Vec3d(double x, double y, double z) {
    public double dot(Vec3d o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public Vec3d sub(Vec3d o) {
        return new Vec3d(x - o.x, y - o.y, z - o.z);
    }

    public Vec3d add(Vec3d o) {
        return new Vec3d(x + o.x, y + o.y, z + o.z);
    }

    public Vec3d scale(double s) {
        return new Vec3d(x * s, y * s, z * s);
    }

    public Vec3d cross(Vec3d o) {
        return new Vec3d(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }

    public double length() {
        return Math.sqrt(dot(this));
    }
}
