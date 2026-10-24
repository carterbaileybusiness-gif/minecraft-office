package local.carter.office.client.magic.fx.earth;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

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
 * columns heaving out of the ground and sinking back, are these. {@link FxKit}'s ghost blocks only stand on whole cells; these need free
 * motion. Each rock costs one ghost block of its event's {@code FxBudget} (the caller asks the budget first). Every rock is on
 * a {@link RockLedger.Leash} beaten by a cancellable {@code FxKit.simulate} timer of its cast, so {@code FxKit.cancel(casterId)}
 * (and the timer running out) takes it away; {@link RockLedger} caps them overall and per caster. Cleared on world change.
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
		/** The least block light (0..15) the rock is drawn with: earth magic glowing faintly through the night. */
		public int glow;

		public Pose at(Vec3 v) {
			x = v.x;
			y = v.y;
			z = v.z;
			return this;
		}
	}

	private static final double CULL_DISTANCE = 80;
	private static final List<Stone> LIVE = new ArrayList<>();
	private static final RockLedger LEDGER = new RockLedger();
	/** Client ticks counted here (not while paused): the clock leashes are beaten with. */
	private static long ticks;
	private static @Nullable BlockModelResolver resolver;
	private static @Nullable ClientLevel seen;

	private static final class Stone {
		final int casterId;
		final RockLedger.Leash leash;
		final BlockState state;
		final int life;
		final Motion motion;
		final BlockModelRenderState model = new BlockModelRenderState();
		final Pose pose = new Pose();
		boolean resolved;
		int age;

		Stone(int casterId, RockLedger.Leash leash, BlockState state, int life, Motion motion) {
			this.casterId = casterId;
			this.leash = leash;
			this.state = state;
			this.life = Math.max(1, life);
			this.motion = motion;
		}
	}

	/** Called once from {@link EarthFx#register()}. */
	static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(EarthStones::tick);
		LevelRenderEvents.COLLECT_SUBMITS.register(EarthStones::submit);
	}

	/** The clock leashes are beaten with. */
	public static long now() {
		return ticks;
	}

	/**
	 * Adds a rock of {@code casterId}'s cast for {@code life} ticks, on {@code leash} (it goes as soon as the leash is no longer
	 * beaten). False when the ledger refuses it (too many rocks, or too many of this caster's).
	 */
	public static boolean add(int casterId, RockLedger.Leash leash, BlockState state, int life, Motion motion) {
		if (state.isAir() || !leash.alive(ticks) || !LEDGER.admit(casterId)) return false;
		LIVE.add(new Stone(casterId, leash, state, life, motion));
		return true;
	}

	/** Rocks live (the screenshot runs read it). */
	public static int live() {
		return LIVE.size();
	}

	/** Rocks live for {@code casterId}. */
	public static int live(int casterId) {
		return LEDGER.of(casterId);
	}

	private static void tick(Minecraft mc) {
		if (mc.level != seen) {
			seen = mc.level;
			LIVE.clear();
			LEDGER.clear();
		}
		if (mc.isPaused()) return;
		ticks++;
		for (Iterator<Stone> it = LIVE.iterator(); it.hasNext(); ) {
			Stone s = it.next();
			s.age++;
			if (s.age >= s.life || !s.leash.alive(ticks)) {
				it.remove();
				LEDGER.release(s.casterId);
			}
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
			if (!s.leash.alive(ticks)) continue;
			Pose p = s.pose;
			p.scale = 1F;
			p.yaw = p.pitch = p.roll = 0F;
			p.light = null;
			p.glow = 0;
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
			if (p.glow > 0) light = LightCoordsUtil.max(light, LightCoordsUtil.pack(Math.min(15, p.glow), 0));
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
