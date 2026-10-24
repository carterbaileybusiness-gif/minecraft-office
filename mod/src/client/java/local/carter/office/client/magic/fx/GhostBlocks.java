package local.carter.office.client.magic.fx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/**
 * Client-only blocks: a real block model drawn where the world has none (or over the one it has), with no world change, for spell
 * visuals (earth spikes rising, ground popping up along a shockwave, rubble falling, stones drawn into a fist). Each moves by its
 * {@link Motion} over its life and is submitted from {@link LevelRenderEvents#COLLECT_SUBMITS}, lit by the world where it stands.
 * At most {@link #MAX_LIVE} at once across every spell (each event is also held to {@link FxBudget#GHOST_BLOCKS}).
 *
 * <p>Ghost water ({@link FxKit#ghostFluid}) is drawn here too: a cell of water {@code height} tall with vanilla's water sprites
 * (still on top, flowing down the sides, animated by the block atlas), the biome's water tint and the texture's own ≈ 0.7 alpha.
 * The cells of one fx event make one {@link FluidBody}: a face two of its cells share is not drawn, so a wave reads as one body
 * of water, not a stack of glass cubes. Faces against a solid world block are not drawn either (like real water), and only the
 * faces turned toward the camera are laid (the surface also from below), so it looks the same whether or not the render type culls.
 * Water counts as a ghost block against both limits.
 */
public final class GhostBlocks {
	private GhostBlocks() {}

	/** How a ghost block moves over its life. */
	public enum Motion {
		/** Stands still, shrinking away over its last ticks (water drains away instead). */
		STATIC,
		/** Rises out of the ground into place, holds, then sinks back. */
		RISE,
		/** Jumps up and falls back (a shockwave rolling through the ground). */
		POP,
		/** Falls into place from above and settles with a small bounce (water drains away at the end). */
		FALL,
		/** Spirals in from around its place, rising, and shrinks into it. */
		ORBIT_IN
	}

	public static final int MAX_LIVE = 512;
	/** Ghosts farther than this from the camera are not drawn. */
	public static final double CULL_DISTANCE = 64;
	private static final List<Ghost> LIVE = new ArrayList<>();
	private static BlockModelResolver resolver;
	private static ClientLevel seen;

	private static final class Ghost {
		final BlockPos pos;
		/** The block shown, or null for water ({@link #fluid}). */
		final @Nullable BlockState state;
		final @Nullable FluidCell fluid;
		final Vec3 base;
		final int life;
		final Motion motion;
		final int casterId;
		final float spin;
		final BlockModelRenderState model = new BlockModelRenderState();
		boolean resolved;
		int age;

		Ghost(BlockPos pos, @Nullable BlockState state, @Nullable FluidCell fluid, Vec3 offset, int life, Motion motion, int casterId) {
			this.pos = pos;
			this.state = state;
			this.fluid = fluid;
			this.base = Vec3.atLowerCornerOf(pos).add(offset);
			this.life = Math.max(1, life);
			this.motion = motion;
			this.casterId = casterId;
			this.spin = (float) ((pos.asLong() * 0x9E3779B97F4A7C15L >>> 40) % 360);
		}
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(GhostBlocks::tick);
		LevelRenderEvents.COLLECT_SUBMITS.register(GhostBlocks::submit);
	}

	/** Adds one (false when {@link #MAX_LIVE} are already showing). */
	static boolean add(BlockPos pos, BlockState state, Vec3 offset, int lifeTicks, Motion motion, int casterId) {
		if (LIVE.size() >= MAX_LIVE || state.isAir()) {
			return false;
		}
		LIVE.add(new Ghost(pos.immutable(), state, null, offset, lifeTicks, motion, casterId));
		return true;
	}

	/**
	 * Adds a cell of ghost water to {@code body} (false when {@link #MAX_LIVE} are already showing). A cell the body already has at
	 * {@code pos} is replaced (a wave rolling over the same ground again).
	 */
	static boolean addFluid(BlockPos pos, float height, int lifeTicks, Motion motion, int casterId, FluidBody body) {
		if (LIVE.size() >= MAX_LIVE || !(height > 0)) {
			return false;
		}
		FluidCell cell = body.put(pos.getX(), pos.getY(), pos.getZ(), Math.min(1F, height));
		LIVE.add(new Ghost(pos.immutable(), null, cell, Vec3.ZERO, lifeTicks, motion, casterId));
		return true;
	}

	/** Removes {@code casterId}'s ghosts. */
	static void cancel(int casterId) {
		LIVE.removeIf(g -> g.casterId == casterId && drop(g));
	}

	/** Ghosts showing, blocks and water. */
	public static int live() {
		return LIVE.size();
	}

	/** Cells of ghost water showing. */
	public static int liveFluids() {
		int n = 0;
		for (Ghost g : LIVE) {
			if (g.fluid != null) n++;
		}
		return n;
	}

	/** Takes {@code g}'s water cell out of its body (always true, for {@code removeIf}). */
	private static boolean drop(Ghost g) {
		if (g.fluid != null) {
			g.fluid.body.remove(g.fluid);
		}
		return true;
	}

	private static void tick(Minecraft mc) {
		if (mc.level != seen) {
			seen = mc.level;
			LIVE.removeIf(GhostBlocks::drop);
		}
		if (mc.isPaused()) {
			return;
		}
		for (Iterator<Ghost> it = LIVE.iterator(); it.hasNext(); ) {
			Ghost g = it.next();
			if (++g.age >= g.life || g.fluid != null && g.fluid.replaced) {
				drop(g);
				it.remove();
			}
		}
	}

	// ---- motion ----------------------------------------------------------------------------------------------------------

	/** Where a ghost is this frame: offset from its place, scale (water: how full) and turn. */
	private static final class Placed {
		double dx;
		double dy;
		double dz;
		float scale;
		float yaw;
		float tilt;
	}

	private static void place(Ghost g, float t, Placed out) {
		float p = Mth.clamp(t / g.life, 0F, 1F);
		out.dx = 0;
		out.dy = 0;
		out.dz = 0;
		out.scale = 1F;
		out.yaw = 0;
		out.tilt = 0;
		float end = Mth.clamp((g.life - t) / Math.min(5F, g.life), 0F, 1F); // last ticks: shrink away
		switch (g.motion) {
			case STATIC -> out.scale = end;
			case RISE -> {
				float in = Math.min(6F, g.life / 4F);
				float up = Mth.clamp(t / in, 0F, 1F);
				float down = Mth.clamp((g.life - t) / in, 0F, 1F);
				float k = Math.min(1 - (1 - up) * (1 - up) * (1 - up), down * down);
				out.dy = -(1 - k) * 1.05;
			}
			case POP -> {
				out.dy = 0.9 * 4 * p * (1 - p);
				out.tilt = (float) Math.sin(p * Math.PI) * 12F * ((g.spin % 2) < 1 ? 1 : -1);
			}
			case FALL -> {
				float landAt = Math.min(g.life * 0.4F, 14F);
				if (t < landAt) {
					float q = 1 - t / landAt;
					out.dy = 7 * q * q;
					out.yaw = q * 90F;
				} else {
					float since = t - landAt;
					out.dy = Math.abs(Math.sin(since * 0.9)) * 0.18 * Math.exp(-since * 0.35);
				}
				out.scale = end;
			}
			case ORBIT_IN -> {
				float r = 2.6F * (1 - p);
				float ang = (float) Math.toRadians(g.spin) + p * 9F;
				out.dx = Math.cos(ang) * r;
				out.dz = Math.sin(ang) * r;
				out.dy = 1.4 * (1 - p) * (1 - p);
				out.yaw = p * 540F;
				out.scale = 0.4F + 0.6F * (1 - p * p);
			}
		}
	}

	// ---- drawing ---------------------------------------------------------------------------------------------------------

	private static final Placed PLACED = new Placed();

	private static void submit(LevelRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || LIVE.isEmpty()) {
			return;
		}
		if (resolver == null) {
			resolver = new BlockModelResolver(mc.getModelManager());
		}
		Vec3 cam = context.levelState().cameraRenderState.pos;
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		PoseStack pose = context.poseStack();
		boolean water = false;
		for (Ghost g : LIVE) {
			if (g.fluid != null) {
				water = true;
				continue;
			}
			if (!g.resolved) {
				resolver.update(g.model, g.state, BlockDisplayContext.create());
				g.resolved = true;
			}
			if (g.model.isEmpty() || g.base.distanceToSqr(cam) > CULL_DISTANCE * CULL_DISTANCE) {
				continue; // too far to matter (a few pixels at most)
			}
			Placed at = PLACED;
			place(g, g.age + partial, at);
			if (at.scale <= 0.01F) {
				continue;
			}
			double x = g.base.x + at.dx;
			double y = g.base.y + at.dy;
			double z = g.base.z + at.dz;
			int light = lightAbove(level, x, y, z);
			pose.pushPose();
			pose.translate(x - cam.x + 0.5, y - cam.y + 0.5, z - cam.z + 0.5);
			if (at.yaw != 0) pose.rotateDegrees(Axis.YP, at.yaw);
			if (at.tilt != 0) pose.rotateDegrees(Axis.XP, at.tilt);
			// A hair larger than a block, so one standing in a real block's place covers it instead of flickering against it.
			float cover = at.scale * 1.004F;
			pose.scale(cover, cover, cover);
			pose.translate(-0.5, -0.5, -0.5);
			g.model.submit(pose, context.submitNodeCollector(), light, OverlayTexture.NO_OVERLAY, 0);
			pose.popPose();
		}
		if (water) {
			submitWater(context, level, cam, partial);
		}
	}

	/** Lit like the air above it: a ghost inside the ground (rising, popping) would otherwise read the solid block's 0 and go black. */
	private static int lightAbove(ClientLevel level, double x, double y, double z) {
		BlockPos cell = BlockPos.containing(x + 0.5, y + 0.5, z + 0.5);
		return LightCoordsUtil.max(LightCoordsUtil.getLightCoords(level, cell), LightCoordsUtil.getLightCoords(level, cell.above()));
	}

	// ---- ghost water -----------------------------------------------------------------------------------------------------

	/** Face numbers: down, up, north (−z), south (+z), west (−x), east (+x). */
	public static final int DOWN = 0;
	public static final int UP = 1;
	public static final int NORTH = 2;
	public static final int SOUTH = 3;
	public static final int WEST = 4;
	public static final int EAST = 5;
	private static final int[][] STEP = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
	/** Vanilla's face shading for water: top full, bottom half, north/south 0.8, east/west 0.6. */
	private static final float[] SHADE = {0.5F, 1F, 0.8F, 0.8F, 0.6F, 0.6F};
	private static final double EPS = 1.0E-4;
	/** The water's floor sits this far above its cell's bottom, so it never flickers against the ground's top it lies on. */
	private static final double FLOOR_LIFT = 0.002;

	/**
	 * One fx event's ghost water: its cells by position. A face is hidden where another cell of the same body covers it, never by a
	 * cell of another event (two spells' water stays two bodies). Pure (no game state), for the unit tests.
	 */
	public static final class FluidBody {
		private final Map<Long, FluidCell> cells = new HashMap<>();

		/** Adds a cell {@code height} (0..1) tall at the block x, y, z, replacing (and marking) any this body has there. */
		public FluidCell put(int x, int y, int z, float height) {
			FluidCell c = new FluidCell(this, x, y, z, height);
			FluidCell old = cells.put(key(x, y, z), c);
			if (old != null) old.replaced = true;
			return c;
		}

		/** Takes {@code c} out (a no-op once another cell has replaced it). */
		public void remove(FluidCell c) {
			cells.remove(key(c.x, c.y, c.z), c);
		}

		public int size() {
			return cells.size();
		}

		/** This body's cell beside {@code c} across {@code face}, if it is drawn this frame and lined up with {@code c} (else null). */
		public @Nullable FluidCell neighbour(FluidCell c, int face) {
			int[] s = STEP[face];
			FluidCell n = cells.get(key(c.x + s[0], c.y + s[1], c.z + s[2]));
			if (n == null || !n.shown || Math.abs(n.dx - c.dx) > EPS || Math.abs(n.dz - c.dz) > EPS) {
				return null;
			}
			return n;
		}

		/** Whether {@code c}'s top ({@link #UP}) or floor ({@link #DOWN}) shows: not where a cell of this body covers it. */
		public boolean capShown(FluidCell c, int face) {
			FluidCell n = neighbour(c, face);
			if (n == null) return true;
			return face == UP
					? !(n.bottom <= c.top + EPS && n.top > c.top + EPS) // the cell above starts at (or under) this one's surface
					: !(n.top >= c.bottom - EPS && n.bottom < c.bottom - EPS); // the cell below reaches this one's floor
		}

		/**
		 * The parts of {@code c}'s side {@code face} that show, as world heights {from, to, from, to, ...} (none, one or two spans):
		 * all of {@code bottom..top}, less what this body's neighbour across that face covers.
		 */
		public double[] sideShown(FluidCell c, int face) {
			return spans(c.bottom, c.top, neighbour(c, face));
		}

		private static double[] spans(double bottom, double top, @Nullable FluidCell n) {
			if (top - bottom <= EPS) return new double[0];
			if (n == null || n.top <= bottom + EPS || n.bottom >= top - EPS) return new double[] {bottom, top};
			boolean below = n.bottom > bottom + EPS;
			boolean above = n.top < top - EPS;
			if (below && above) return new double[] {bottom, n.bottom, n.top, top};
			if (below) return new double[] {bottom, n.bottom};
			if (above) return new double[] {n.top, top};
			return new double[0];
		}

		private static long key(int x, int y, int z) {
			return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (long) y & 0xFFFL;
		}
	}

	/** One cell of ghost water, and where it is drawn this frame. */
	public static final class FluidCell {
		final FluidBody body;
		public final int x;
		public final int y;
		public final int z;
		/** How full the cell is (0..1 of a block). */
		public final float height;
		/** Replaced by a newer cell of its body at the same place. */
		boolean replaced;
		/** This frame: drawn at all, offset from its place sideways, and its world floor and surface. */
		public boolean shown;
		public double dx;
		public double dz;
		public double bottom;
		public double top;
		/** Biome water tint (RGB) and solid world neighbours (bit per face), found on its first frame. */
		int tint = -1;
		int solid;

		FluidCell(FluidBody body, int x, int y, int z, float height) {
			this.body = body;
			this.x = x;
			this.y = y;
			this.z = z;
			this.height = height;
		}

		/** Places it for this frame (the game tests and unit tests set it directly). */
		public FluidCell at(double dx, double dz, double bottom, double top) {
			this.shown = true;
			this.dx = dx;
			this.dz = dz;
			this.bottom = bottom;
			this.top = top;
			return this;
		}
	}

	private static final Identifier WATER_STILL = Identifier.withDefaultNamespace("block/water_still");
	private static final Identifier WATER_FLOW = Identifier.withDefaultNamespace("block/water_flow");
	/**
	 * Laid quads for this frame's water, drawn by {@link #emitWater}: eight floats a vertex (x y z u v nx ny nz) and two ints
	 * (colour, light).
	 */
	private static float[] quads = new float[32 * 64];
	private static int[] quadInts = new int[8 * 64];
	private static int quadCount;

	private static void submitWater(LevelRenderContext context, ClientLevel level, Vec3 cam, float partial) {
		Minecraft mc = Minecraft.getInstance();
		TextureAtlas atlas = mc.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
		TextureAtlasSprite still = atlas.getSprite(WATER_STILL);
		TextureAtlasSprite flow = atlas.getSprite(WATER_FLOW);
		boolean plain = ShaderCompat.current() == ShaderCompat.Path.PLAIN;
		// First where every cell is this frame (faces depend on the neighbours), then the faces.
		Placed at = PLACED;
		for (Ghost g : LIVE) {
			FluidCell c = g.fluid;
			if (c == null) continue;
			c.shown = false;
			if (c.replaced || g.base.distanceToSqr(cam) > CULL_DISTANCE * CULL_DISTANCE) continue;
			place(g, g.age + partial, at);
			float full = c.height * Mth.clamp(at.scale, 0F, 1F);
			if (full <= 0.01F) continue;
			double bottom = g.base.y + at.dy;
			c.at(at.dx, at.dz, bottom, bottom + full);
		}
		quadCount = 0;
		for (Ghost g : LIVE) {
			FluidCell c = g.fluid;
			if (c == null || !c.shown) continue;
			if (c.tint < 0) {
				BlockPos p = new BlockPos(c.x, c.y, c.z);
				c.tint = BiomeColors.getAverageWaterColor(level, p) & 0xFFFFFF;
				for (int f = 0; f < 6; f++) {
					int[] s = STEP[f];
					BlockState there = level.getBlockState(p.offset(s[0], s[1], s[2]));
					if (there.isSolidRender()) c.solid |= 1 << f;
				}
			}
			int light = lightAbove(level, c.x + c.dx, c.bottom, c.z + c.dz);
			layCell(c, cam, still, flow, light, plain);
		}
		if (quadCount == 0) return;
		RenderType type = plain ? RenderTypes.entityTranslucent(TextureAtlas.LOCATION_BLOCKS) : RenderTypes.translucentMovingBlock();
		PoseStack pose = context.poseStack();
		pose.pushPose();
		context.submitNodeCollector().submitCustomGeometry(pose, type, GhostBlocks::emitWater);
		pose.popPose();
	}

	/** Whether a solid world block hides {@code c}'s face {@code f}: only while the cell stands exactly in its place. */
	private static boolean wallHides(FluidCell c, int f) {
		if ((c.solid & (1 << f)) == 0 || Math.abs(c.dx) > EPS || Math.abs(c.dz) > EPS) return false;
		return switch (f) {
			case UP -> Math.abs(c.top - (c.y + 1)) < EPS;
			case DOWN -> Math.abs(c.bottom - c.y) < EPS;
			default -> Math.abs(c.bottom - c.y) < EPS; // the side stands in its cell
		};
	}

	private static void layCell(FluidCell c, Vec3 cam, TextureAtlasSprite still, TextureAtlasSprite flow, int light, boolean plain) {
		double x0 = c.x + c.dx;
		double x1 = x0 + 1;
		double z0 = c.z + c.dz;
		double z1 = z0 + 1;
		int tint = c.tint;
		// The surface: from above, and from below too (a swimmer looking up), like vanilla water.
		if (c.body.capShown(c, UP) && !wallHides(c, UP)) {
			boolean above = cam.y >= c.top;
			int colour = colour(tint, plain ? 1F : SHADE[UP]);
			quad(cam, x0, c.top, z0, x0, c.top, z1, x1, c.top, z1, x1, c.top, z0,
					still.getU0(), still.getV0(), still.getU0(), still.getV1(), still.getU1(), still.getV1(), still.getU1(), still.getV0(),
					colour, light, 0, above ? 1 : -1, 0);
		}
		// The floor: only from below (from above it is seen through the surface, and would only darken it).
		if (cam.y < c.bottom && c.body.capShown(c, DOWN) && !wallHides(c, DOWN)) {
			double y = c.bottom + FLOOR_LIFT;
			quad(cam, x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1,
					still.getU0(), still.getV0(), still.getU1(), still.getV0(), still.getU1(), still.getV1(), still.getU0(), still.getV1(),
					colour(tint, plain ? 1F : SHADE[DOWN]), light, 0, -1, 0);
		}
		for (int f = NORTH; f <= EAST; f++) {
			// Sides only from outside (looking through the water at its far walls would stack layers, the glass-cube look).
			boolean outside = switch (f) {
				case NORTH -> cam.z < z0;
				case SOUTH -> cam.z > z1;
				case WEST -> cam.x < x0;
				default -> cam.x > x1;
			};
			if (!outside || wallHides(c, f)) continue;
			double[] spans = c.body.sideShown(c, f);
			int colour = colour(tint, plain ? 1F : SHADE[f]);
			for (int i = 0; i + 1 < spans.length; i += 2) {
				double lo = spans[i];
				double hi = spans[i + 1];
				// The flowing sprite at half scale (vanilla's sides): one block wide is half of it across, heights down from its middle.
				float u0 = flow.getU(0F);
				float u1 = flow.getU(0.5F);
				float vHi = flow.getV((float) (1 - (hi - c.bottom)) * 0.5F);
				float vLo = flow.getV((float) (1 - (lo - c.bottom)) * 0.5F);
				switch (f) {
					case NORTH -> quad(cam, x1, lo, z0, x1, hi, z0, x0, hi, z0, x0, lo, z0, u0, vLo, u0, vHi, u1, vHi, u1, vLo, colour, light, 0, 0, -1);
					case SOUTH -> quad(cam, x0, lo, z1, x0, hi, z1, x1, hi, z1, x1, lo, z1, u0, vLo, u0, vHi, u1, vHi, u1, vLo, colour, light, 0, 0, 1);
					case WEST -> quad(cam, x0, lo, z0, x0, hi, z0, x0, hi, z1, x0, lo, z1, u0, vLo, u0, vHi, u1, vHi, u1, vLo, colour, light, -1, 0, 0);
					default -> quad(cam, x1, lo, z1, x1, hi, z1, x1, hi, z0, x1, lo, z0, u0, vLo, u0, vHi, u1, vHi, u1, vLo, colour, light, 1, 0, 0);
				}
			}
		}
	}

	/** Water's tint shaded for a face, alpha full (the water texture's own alpha, 180/255 ≈ 0.7, makes it see-through). */
	private static int colour(int rgb, float shade) {
		int r = Math.round(((rgb >> 16) & 0xFF) * shade);
		int g = Math.round(((rgb >> 8) & 0xFF) * shade);
		int b = Math.round((rgb & 0xFF) * shade);
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	/**
	 * Lays one quad (camera-relative) facing along the normal: the corners are turned round if they wind the other way, so the
	 * face is front-facing toward that side whatever order they came in.
	 */
	private static void quad(Vec3 cam, double ax, double ay, double az, double bx, double by, double bz, double cx, double cy, double cz,
			double dx, double dy, double dz, float ua, float va, float ub, float vb, float uc, float vc, float ud, float vd, int colour,
			int light, float nx, float ny, float nz) {
		// Winding: (b - a) × (c - a) must point along the normal.
		double ex = bx - ax, ey = by - ay, ez = bz - az;
		double fx = cx - ax, fy = cy - ay, fz = cz - az;
		double wx = ey * fz - ez * fy, wy = ez * fx - ex * fz, wz = ex * fy - ey * fx;
		boolean flip = wx * nx + wy * ny + wz * nz < 0;
		if (quads.length < (quadCount + 1) * 32) {
			quads = java.util.Arrays.copyOf(quads, quads.length * 2);
			quadInts = java.util.Arrays.copyOf(quadInts, quadInts.length * 2);
		}
		int o = quadCount * 4;
		if (!flip) {
			o = vertex(o, cam, ax, ay, az, ua, va, colour, light, nx, ny, nz);
			o = vertex(o, cam, bx, by, bz, ub, vb, colour, light, nx, ny, nz);
			o = vertex(o, cam, cx, cy, cz, uc, vc, colour, light, nx, ny, nz);
			vertex(o, cam, dx, dy, dz, ud, vd, colour, light, nx, ny, nz);
		} else {
			o = vertex(o, cam, ax, ay, az, ua, va, colour, light, nx, ny, nz);
			o = vertex(o, cam, dx, dy, dz, ud, vd, colour, light, nx, ny, nz);
			o = vertex(o, cam, cx, cy, cz, uc, vc, colour, light, nx, ny, nz);
			vertex(o, cam, bx, by, bz, ub, vb, colour, light, nx, ny, nz);
		}
		quadCount++;
	}

	/** Writes vertex {@code o} (counting vertices) and returns the next. */
	private static int vertex(int o, Vec3 cam, double x, double y, double z, float u, float v, int colour, int light, float nx, float ny, float nz) {
		float[] q = quads;
		int f = o * 8;
		q[f] = (float) (x - cam.x);
		q[f + 1] = (float) (y - cam.y);
		q[f + 2] = (float) (z - cam.z);
		q[f + 3] = u;
		q[f + 4] = v;
		q[f + 5] = nx;
		q[f + 6] = ny;
		q[f + 7] = nz;
		quadInts[o * 2] = colour;
		quadInts[o * 2 + 1] = light;
		return o + 1;
	}

	/** Draws this frame's laid water (the submit runs later in the same frame). */
	private static void emitWater(PoseStack.Pose pose, VertexConsumer out) {
		float[] q = quads;
		int[] k = quadInts;
		for (int i = 0, n = quadCount * 4; i < n; i++) {
			int f = i * 8;
			out.addVertex(pose, q[f], q[f + 1], q[f + 2])
					.setColor(k[i * 2])
					.setUv(q[f + 3], q[f + 4])
					.setOverlay(OverlayTexture.NO_OVERLAY)
					.setLight(k[i * 2 + 1])
					.setNormal(pose, q[f + 5], q[f + 6], q[f + 7]);
		}
	}
}
