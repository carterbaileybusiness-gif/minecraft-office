package local.carter.office.magic.spell.fire;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.core.ChargeMath;
import local.carter.office.magic.core.Shapes.Cell;
import local.carter.office.magic.tree.Knob;
import local.carter.office.magic.tree.SpellRank;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Where a Fire Wall stands (pure: vector math only, no world access). The server effect and the client fx both build it from the
 * same three numbers the fx event carries: {@code base} (the wall's foot on the ground, at the middle of its width; the event's
 * {@code target}), {@code forward} (the caster's flat facing; the event's {@code dir}) and the cast's {@code charge}.
 *
 * <p>The wall is an upright slab across the aim line: {@link #width} wide along {@link #side} (5 at a tap, 10 at full charge),
 * {@link #THICKNESS} deep along {@link #forward}, {@link #height} tall from {@code base.y} (3, 4 at full charge).
 */
public record FireWallGeometry(Vec3 base, Vec3 forward, Vec3 side, double width, double height, int duration) {
	/** How far ahead of the caster's feet the wall stands. */
	public static final double AHEAD = 4;
	/** A tap's width (twice that at full charge) and height (one more at full charge). */
	public static final double WIDTH = 5;
	/** Widest a ranked wall grows (its half-width stays within the medium edit radius of its foot, 6). */
	public static final double MAX_WIDTH = 12;
	public static final double HEIGHT = 3;
	/** Depth of the burning zone along the aim line. */
	public static final double THICKNESS = 1.0;
	public static final int BASE_TICKS = 100;
	public static final int CHARGE_TICKS = 100;
	/**
	 * Longest move in one tick that still counts as passing through the wall ({@link #sweeps}). Anything that went farther in a
	 * single tick was teleported (ender pearl, chorus fruit, an enderman, a spell), not thrown, and never crossed the fire.
	 */
	public static final double MAX_SWEEP = 4;

	/** The caster's flat facing for a yaw in degrees (Minecraft's: 0 = +Z, 90 = −X). Never zero, whatever the pitch. */
	public static Vec3 forward(float yRotDegrees) {
		double a = Math.toRadians(yRotDegrees);
		return new Vec3(-Math.sin(a), 0, Math.cos(a));
	}

	/** The point {@link #AHEAD} in front of {@code feet} along {@code forward} (before it is moved to the ground). */
	public static Vec3 aimPoint(Vec3 feet, Vec3 forward) {
		Vec3 f = flat(forward);
		return feet.add(f.scale(AHEAD));
	}

	/** {@code 5 × ChargeMath.size(charge)}: 5 at a tap, 10 at full charge. */
	public static double width(float charge) {
		return WIDTH * ChargeMath.size(charge);
	}

	/** {@code 3 + charge}: 3 at a tap, 4 at full charge. */
	public static double height(float charge) {
		return HEIGHT + Mth.clamp(charge, 0F, 1F);
	}

	public static int duration(float charge) {
		return BASE_TICKS + Math.round(CHARGE_TICKS * Mth.clamp(charge, 0F, 1F));
	}

	/** The wall at {@code base} facing {@code forward} (flattened; straight ahead +Z if it has no flat part) for a cast at {@code charge}. */
	public static FireWallGeometry of(Vec3 base, Vec3 forward, float charge) {
		return of(base, forward, charge, null);
	}

	/** As {@link #of(Vec3, Vec3, float)} at {@code rank}: the width grows by the rank's size knob, up to {@link #MAX_WIDTH}. */
	public static FireWallGeometry of(Vec3 base, Vec3 forward, float charge, @Nullable SpellRank rank) {
		Vec3 f = flat(forward);
		Vec3 side = new Vec3(-f.z, 0, f.x);
		double width = Math.min(MAX_WIDTH, width(charge) * (rank == null ? 1 : rank.mul(Knob.SIZE)));
		return new FireWallGeometry(base, f, side, width, height(charge), duration(charge));
	}

	/** This wall moved {@code d} along its facing (Marching Wall). */
	public FireWallGeometry marched(double d) {
		return new FireWallGeometry(base.add(forward.scale(d)), forward, side, width, height, duration);
	}

	/** The point {@code s} along the wall's width (−width/2..width/2, from its middle) and {@code h} up from its foot. */
	public Vec3 point(double s, double h) {
		return base.add(side.x * s, h, side.z * s);
	}

	/**
	 * The ground cells under the wall, as offsets from the cell holding {@code base} (y always 0): every cell whose centre lies
	 * within the slab's footprint, half-open on both axes so an axis-aligned wall is exactly one row of {@link #width} cells.
	 */
	public List<Cell> footprint() {
		int bx = Mth.floor(base.x);
		int bz = Mth.floor(base.z);
		int n = (int) Math.ceil(width / 2) + 1;
		double halfW = width / 2;
		double halfT = THICKNESS / 2;
		List<Cell> out = new ArrayList<>();
		for (int dx = -n; dx <= n; dx++) {
			for (int dz = -n; dz <= n; dz++) {
				double rx = bx + dx + 0.5 - base.x;
				double rz = bz + dz + 0.5 - base.z;
				double across = rx * side.x + rz * side.z;
				double along = rx * forward.x + rz * forward.z;
				if (across > -halfW && across <= halfW && along > -halfT && along <= halfT) out.add(new Cell(dx, 0, dz));
			}
		}
		return out;
	}

	/** A box holding the whole wall (for entity queries). */
	public AABB bounds() {
		double halfW = width / 2;
		double halfT = THICKNESS / 2;
		double ex = Math.abs(side.x) * halfW + Math.abs(forward.x) * halfT;
		double ez = Math.abs(side.z) * halfW + Math.abs(forward.z) * halfT;
		return new AABB(base.x - ex, base.y, base.z - ez, base.x + ex, base.y + height, base.z + ez);
	}

	/** Whether {@code box} overlaps the burning slab (separating axes: the wall's two flat axes, x, z, and height). */
	public boolean touches(AABB box) {
		if (box.maxY <= base.y || box.minY >= base.y + height) return false;
		double hx = (box.maxX - box.minX) / 2;
		double hz = (box.maxZ - box.minZ) / 2;
		double cx = (box.minX + box.maxX) / 2 - base.x;
		double cz = (box.minZ + box.maxZ) / 2 - base.z;
		double halfW = width / 2;
		double halfT = THICKNESS / 2;
		if (Math.abs(cx * forward.x + cz * forward.z) > halfT + hx * Math.abs(forward.x) + hz * Math.abs(forward.z)) return false;
		if (Math.abs(cx * side.x + cz * side.z) > halfW + hx * Math.abs(side.x) + hz * Math.abs(side.z)) return false;
		if (Math.abs(cx) > hx + halfT * Math.abs(forward.x) + halfW * Math.abs(side.x)) return false;
		return Math.abs(cz) <= hz + halfT * Math.abs(forward.z) + halfW * Math.abs(side.z);
	}

	/**
	 * Whether a move of {@code motion} in one tick is a real pass through space that the wall should sweep back along: longer than
	 * a hair and no longer than {@link #MAX_SWEEP} (a longer one is a teleport).
	 */
	public static boolean sweeps(Vec3 motion) {
		double l2 = motion.lengthSqr();
		return l2 > 1.0E-6 && l2 <= MAX_SWEEP * MAX_SWEEP;
	}

	/** Whether {@code box} moving by {@code motion} this tick touches the slab anywhere along the way (fast projectiles). */
	public boolean crosses(AABB box, Vec3 motion) {
		return touches(box.expandTowards(motion));
	}

	private static Vec3 flat(Vec3 v) {
		double l = Math.sqrt(v.x * v.x + v.z * v.z);
		if (l < 1.0E-6 || !Double.isFinite(l)) return new Vec3(0, 0, 1);
		return new Vec3(clean(v.x / l), 0, clean(v.z / l));
	}

	/** Trig noise (cos 90° = 6e-17) to an exact 0, so an axis-aligned wall's half-open footprint stays exactly one row. */
	private static double clean(double c) {
		return Math.abs(c) < 1.0E-9 ? 0 : c;
	}
}
