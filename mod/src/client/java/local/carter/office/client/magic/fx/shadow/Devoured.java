package local.carter.office.client.magic.fx.shadow;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.magic.spell.shadow.BlackHoleGeometry;

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
 * The land a black hole eats, as it is eaten: real block models (client only, no world change) torn from where the server is
 * removing them and drawn spiralling into the core on {@link BlackHoleGeometry#infall}, tumbling and shrinking until the core
 * swallows them. Each is charged to its event's ghost-block budget ({@link FxKit#budget()}). Drawn from its own
 * {@code COLLECT_SUBMITS} hook (the {@code GhostBlocks} pattern, but with its own motion); {@link FxKit#cancel} or a world change
 * clears them with the hole's timer, which keeps them alive ({@link #keep}).
 */
final class Devoured {
	private static final List<Devoured> LIVE = new ArrayList<>();
	private static BlockModelResolver resolver;
	private static ClientLevel seen;

	private final int casterId;
	private final Vec3 centre;
	private final Vec3 from;
	private final BlockState state;
	private final int ticks;
	private final double turns;
	private final Vec3 axis;
	private final float spin;
	private final BlockModelRenderState model = new BlockModelRenderState();
	private boolean resolved;
	private int age;
	private int kept;

	private Devoured(int casterId, Vec3 centre, BlockPos pos, BlockState state, int ticks, double turns, Vec3 axis, float spin) {
		this.casterId = casterId;
		this.centre = centre;
		this.from = Vec3.atCenterOf(pos);
		this.state = state;
		this.ticks = Math.max(4, ticks);
		this.turns = turns;
		this.axis = axis;
		this.spin = spin;
	}

	/**
	 * Tears the block at {@code pos} ({@code state}) away toward {@code centre} over {@code ticks}, if the event's ghost budget allows.
	 * {@code seed} picks its tumble.
	 */
	static boolean tear(FxKit kit, Vec3 centre, BlockPos pos, BlockState state, int ticks, long seed) {
		if (state.isAir() || !kit.budget().ghostBlock()) return false;
		double u = (seed >>> 11) * 0x1.0p-53;
		Vec3 axis = new Vec3(Math.sin(u * 40), 0.6 + 0.4 * Math.cos(u * 17), Math.cos(u * 23));
		LIVE.add(new Devoured(kit.event().casterId(), centre, pos, state, ticks, 0.6 + 0.6 * u, axis.normalize(), (float) (8 + 10 * u)));
		return true;
	}

	/** The hole's timer still runs: keep every piece of {@code casterId}'s alive another tick. */
	static void keep(int casterId) {
		for (Devoured d : LIVE) {
			if (d.casterId == casterId) d.kept = 0;
		}
	}

	/** Pieces in flight (the client tests read it). */
	static int live() {
		return LIVE.size();
	}

	static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(Devoured::tick);
		LevelRenderEvents.COLLECT_SUBMITS.register(Devoured::submit);
	}

	private static void tick(Minecraft mc) {
		if (mc.level != seen) {
			seen = mc.level;
			LIVE.clear();
			return;
		}
		if (mc.isPaused()) return;
		for (Iterator<Devoured> it = LIVE.iterator(); it.hasNext(); ) {
			Devoured d = it.next();
			// Swallowed, or its hole's timer stopped (cancelled, or the world changed).
			if (++d.age >= d.ticks || ++d.kept > 2) it.remove();
		}
	}

	private static void submit(LevelRenderContext context) {
		if (LIVE.isEmpty()) return;
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null) return;
		if (resolver == null) resolver = new BlockModelResolver(mc.getModelManager());
		Vec3 cam = context.levelState().cameraRenderState.pos;
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		PoseStack pose = context.poseStack();
		for (Devoured d : LIVE) {
			if (!d.resolved) {
				resolver.update(d.model, d.state, BlockDisplayContext.create());
				d.resolved = true;
			}
			if (d.model.isEmpty()) continue;
			double t = d.age + Math.min(partial, 1F);
			double f = Math.min(1, t / d.ticks);
			// Shrinks as it falls in, faster at the end (swallowed).
			float scale = (float) (1.004 * (1 - 0.35 * f) * (1 - Math.pow(f, 6)));
			if (scale <= 0.02F) continue;
			Vec3 at = BlackHoleGeometry.infall(d.centre, d.from, d.turns, t, d.ticks);
			int light = LightCoordsUtil.max(LightCoordsUtil.getLightCoords(level, BlockPos.containing(at)),
					LightCoordsUtil.getLightCoords(level, BlockPos.containing(at).above()));
			pose.pushPose();
			pose.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
			// It holds still for a moment (torn loose), then tumbles faster as it falls.
			float turn = (float) (d.spin * f * f * d.ticks);
			if (turn != 0) pose.rotateDegrees(Axis.of(d.axis.toVector3f()), turn);
			pose.scale(scale, scale, scale);
			pose.translate(-0.5, -0.5, -0.5);
			d.model.submit(pose, context.submitNodeCollector(), light, OverlayTexture.NO_OVERLAY, 0);
			pose.popPose();
		}
	}
}
