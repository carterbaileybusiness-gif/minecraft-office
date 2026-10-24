package local.carter.office.client.magic.fx.earth;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import local.carter.office.client.magic.fx.FxKit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/**
 * The Earth lane's moving rocks: real block models drawn at any position, size and tilt that a {@link Motion} gives them
 * frame by frame (between ticks too), with no world change. Stone Bullet's tumbling boulder and its chips, and Earth Wall's
 * columns heaving out of the ground and sinking back, are these. A rock may start later ({@code delay}) and may check the world
 * when it starts ({@code onStart}: false drops it). {@link FxKit}'s ghost blocks only stand on whole cells; these need free
 * motion. Each rock costs one ghost block of its event's {@code FxBudget} (the caller asks the budget first), and at most
 * {@link #MAX_LIVE} live at once. Cleared on world change.
 */
public final class EarthStones {
	private EarthStones() {}

	/** Where a rock is at {@code t} ticks into its life (fractional); false hides it this frame. */
	@FunctionalInterface
	public interface Motion {
		boolean pose(float t, Pose out);
	}

	/** A rock's pose: its centre, size (1 = a block) and rotation in degrees (yaw about Y, then pitch about X, then roll about Z). */
	public static final class Pose {
		public double x;
		public double y;
		public double z;
		public float scale = 1F;
		public float yaw;
		public float pitch;
		public float roll;
		/** The cell to take light from (with the one above it), or null for the rock's own cell. */
		public @Nullable BlockPos light;

		public Pose at(Vec3 v) {
			x = v.x;
			y = v.y;
			z = v.z;
			return this;
		}
	}

	public static final int MAX_LIVE = 256;
	private static final double CULL_DISTANCE = 80;
	private static final List<Stone> LIVE = new ArrayList<>();
	private static @Nullable BlockModelResolver resolver;
	private static @Nullable ClientLevel seen;

	private static final class Stone {
		final BlockState state;
		final int life;
		final Motion motion;
		final @Nullable BooleanSupplier onStart;
		final BlockModelRenderState model = new BlockModelRenderState();
		final Pose pose = new Pose();
		boolean resolved;
		int age;

		Stone(BlockState state, int delay, int life, Motion motion, @Nullable BooleanSupplier onStart) {
			this.state = state;
			this.age = -Math.max(0, delay);
			this.life = Math.max(1, life);
			this.motion = motion;
			this.onStart = onStart;
		}
	}

	/** Called once from {@link EarthFx#register()}. */
	static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(EarthStones::tick);
		LevelRenderEvents.COLLECT_SUBMITS.register(EarthStones::submit);
	}

	/** Adds a rock now, for {@code life} ticks. False when too many are live. */
	public static boolean add(BlockState state, int life, Motion motion) {
		return add(state, 0, life, motion, null);
	}

	/**
	 * Adds a rock that appears {@code delay} ticks from now for {@code life} ticks; {@code onStart} runs as it appears and may
	 * drop it (false). False when too many are live.
	 */
	public static boolean add(BlockState state, int delay, int life, Motion motion, @Nullable BooleanSupplier onStart) {
		if (LIVE.size() >= MAX_LIVE || state.isAir()) return false;
		Stone s = new Stone(state, delay, life, motion, onStart);
		if (s.age == 0 && s.onStart != null && !s.onStart.getAsBoolean()) return true;
		LIVE.add(s);
		return true;
	}

	/** Rocks live or waiting (the screenshot runs read it). */
	public static int live() {
		return LIVE.size();
	}

	private static void tick(Minecraft mc) {
		if (mc.level != seen) {
			seen = mc.level;
			LIVE.clear();
		}
		if (mc.isPaused()) return;
		for (Iterator<Stone> it = LIVE.iterator(); it.hasNext(); ) {
			Stone s = it.next();
			s.age++;
			if (s.age == 0 && s.onStart != null && !s.onStart.getAsBoolean()) {
				it.remove();
				continue;
			}
			if (s.age >= s.life) it.remove();
		}
	}

	private static void submit(LevelRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || LIVE.isEmpty()) return;
		if (resolver == null) resolver = new BlockModelResolver(mc.getModelManager());
		Vec3 cam = context.levelState().cameraRenderState.pos;
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		PoseStack stack = context.poseStack();
		for (Stone s : LIVE) {
			if (s.age < 0) continue;
			Pose p = s.pose;
			p.scale = 1F;
			p.yaw = p.pitch = p.roll = 0F;
			p.light = null;
			if (!s.motion.pose(Math.min(s.age + partial, s.life), p) || p.scale <= 0.01F) continue;
			double dx = p.x - cam.x;
			double dy = p.y - cam.y;
			double dz = p.z - cam.z;
			if (dx * dx + dy * dy + dz * dz > CULL_DISTANCE * CULL_DISTANCE) continue;
			if (!s.resolved) {
				resolver.update(s.model, s.state, BlockDisplayContext.create());
				s.resolved = true;
			}
			if (s.model.isEmpty()) continue;
			// Lit like the air around it: a rock still inside the ground would otherwise read the solid block's 0 and go black.
			BlockPos cell = p.light != null ? p.light : BlockPos.containing(p.x, p.y, p.z);
			int light = LightCoordsUtil.max(LightCoordsUtil.getLightCoords(level, cell), LightCoordsUtil.getLightCoords(level, cell.above()));
			stack.pushPose();
			stack.translate(dx, dy, dz);
			if (p.yaw != 0) stack.rotateDegrees(Axis.YP, p.yaw);
			if (p.pitch != 0) stack.rotateDegrees(Axis.XP, p.pitch);
			if (p.roll != 0) stack.rotateDegrees(Axis.ZP, p.roll);
			stack.scale(p.scale, p.scale, p.scale);
			stack.translate(-0.5, -0.5, -0.5);
			s.model.submit(stack, context.submitNodeCollector(), light, OverlayTexture.NO_OVERLAY, 0);
			stack.popPose();
		}
	}
}
