package local.carter.office.client.magic.fx;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
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
 */
public final class GhostBlocks {
	private GhostBlocks() {}

	/** How a ghost block moves over its life. */
	public enum Motion {
		/** Stands still, shrinking away over its last ticks. */
		STATIC,
		/** Rises out of the ground into place, holds, then sinks back. */
		RISE,
		/** Jumps up and falls back (a shockwave rolling through the ground). */
		POP,
		/** Falls into place from above and settles with a small bounce. */
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
		final BlockState state;
		final Vec3 base;
		final int life;
		final Motion motion;
		final int casterId;
		final float spin;
		final BlockModelRenderState model = new BlockModelRenderState();
		boolean resolved;
		int age;

		Ghost(BlockPos pos, BlockState state, Vec3 offset, int life, Motion motion, int casterId, float spin) {
			this.pos = pos;
			this.state = state;
			this.base = Vec3.atLowerCornerOf(pos).add(offset);
			this.life = Math.max(1, life);
			this.motion = motion;
			this.casterId = casterId;
			this.spin = spin;
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
		float spin = (float) ((pos.asLong() * 0x9E3779B97F4A7C15L >>> 40) % 360);
		LIVE.add(new Ghost(pos.immutable(), state, offset, lifeTicks, motion, casterId, spin));
		return true;
	}

	/** Removes {@code casterId}'s ghosts. */
	static void cancel(int casterId) {
		LIVE.removeIf(g -> g.casterId == casterId);
	}

	public static int live() {
		return LIVE.size();
	}

	private static void tick(Minecraft mc) {
		if (mc.level != seen) {
			seen = mc.level;
			LIVE.clear();
		}
		if (mc.isPaused()) {
			return;
		}
		for (Iterator<Ghost> it = LIVE.iterator(); it.hasNext(); ) {
			Ghost g = it.next();
			if (++g.age >= g.life) {
				it.remove();
			}
		}
	}

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
		for (Ghost g : LIVE) {
			if (!g.resolved) {
				resolver.update(g.model, g.state, BlockDisplayContext.create());
				g.resolved = true;
			}
			if (g.model.isEmpty() || g.base.distanceToSqr(cam) > CULL_DISTANCE * CULL_DISTANCE) {
				continue; // too far to matter (a few pixels at most)
			}
			float t = g.age + partial;
			float p = Mth.clamp(t / g.life, 0F, 1F);
			double dy = 0;
			double dx = 0;
			double dz = 0;
			float scale = 1F;
			float yaw = 0;
			float tilt = 0;
			float end = Mth.clamp((g.life - t) / Math.min(5F, g.life), 0F, 1F); // last ticks: shrink away
			switch (g.motion) {
				case STATIC -> scale = end;
				case RISE -> {
					float in = Math.min(6F, g.life / 4F);
					float up = Mth.clamp(t / in, 0F, 1F);
					float down = Mth.clamp((g.life - t) / in, 0F, 1F);
					float k = Math.min(1 - (1 - up) * (1 - up) * (1 - up), down * down);
					dy = -(1 - k) * 1.05;
				}
				case POP -> {
					dy = 0.9 * 4 * p * (1 - p);
					tilt = (float) Math.sin(p * Math.PI) * 12F * ((g.spin % 2) < 1 ? 1 : -1);
				}
				case FALL -> {
					float landAt = Math.min(g.life * 0.4F, 14F);
					if (t < landAt) {
						float q = 1 - t / landAt;
						dy = 7 * q * q;
						yaw = q * 90F;
					} else {
						float since = t - landAt;
						dy = Math.abs(Math.sin(since * 0.9)) * 0.18 * Math.exp(-since * 0.35);
					}
					scale = end;
				}
				case ORBIT_IN -> {
					float r = 2.6F * (1 - p);
					float ang = (float) Math.toRadians(g.spin) + p * 9F;
					dx = Math.cos(ang) * r;
					dz = Math.sin(ang) * r;
					dy = 1.4 * (1 - p) * (1 - p);
					yaw = p * 540F;
					scale = 0.4F + 0.6F * (1 - p * p);
				}
			}
			if (scale <= 0.01F) {
				continue;
			}
			Vec3 at = g.base.add(dx, dy, dz);
			// Lit like the air above it: a ghost inside the ground (rising, popping) would otherwise read the solid block's 0 and go black.
			BlockPos cell = BlockPos.containing(at.x + 0.5, at.y + 0.5, at.z + 0.5);
			int light = LightCoordsUtil.max(LightCoordsUtil.getLightCoords(level, cell), LightCoordsUtil.getLightCoords(level, cell.above()));
			pose.pushPose();
			pose.translate(at.x - cam.x + 0.5, at.y - cam.y + 0.5, at.z - cam.z + 0.5);
			if (yaw != 0) pose.rotateDegrees(Axis.YP, yaw);
			if (tilt != 0) pose.rotateDegrees(Axis.XP, tilt);
			// A hair larger than a block, so one standing in a real block's place covers it instead of flickering against it.
			float cover = scale * 1.004F;
			pose.scale(cover, cover, cover);
			pose.translate(-0.5, -0.5, -0.5);
			g.model.submit(pose, context.submitNodeCollector(), light, OverlayTexture.NO_OVERLAY, 0);
			pose.popPose();
		}
	}
}
