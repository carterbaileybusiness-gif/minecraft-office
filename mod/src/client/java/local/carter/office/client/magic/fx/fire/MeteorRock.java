package local.carter.office.client.magic.fx.fire;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.magic.spell.fire.MeteorGeometry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/**
 * The Meteor's rock: a rough ball of real block models (magma block, blackstone, basalt, netherrack ghost blocks: client only,
 * no world change) flying along {@link MeteorGeometry#position} and tumbling, full bright as it burns. Each block of it is charged
 * to its event's ghost-block budget. It lives while its fx keeps it ({@link #keep}, every tick of the fx's timer), so cancelling
 * the fx or leaving the world removes it, and it is gone once it lands.
 */
final class MeteorRock {
	/** The rock's shell: cells of a ball this big, hollow inside (nothing in there shows). */
	static final double RADIUS = 3.0;
	private static final double HOLLOW = 1.9;
	/** Degrees it turns a tick. */
	private static final float SPIN = 6F;

	private static final List<MeteorRock> LIVE = new ArrayList<>();
	private static BlockModelResolver resolver;
	private static ClientLevel seen;

	private record Piece(double x, double y, double z, BlockModelRenderState model, BlockState state) {}

	private final MeteorGeometry g;
	private final List<Piece> pieces = new ArrayList<>();
	private final Vec3 axis;
	private int age;
	private int kept;
	private boolean resolved;

	private MeteorRock(MeteorGeometry g, Vec3 axis) {
		this.g = g;
		this.axis = axis;
	}

	/** A new rock for {@code g}, as many blocks as the event's ghost budget grants (null if none). */
	static MeteorRock spawn(FxKit kit, MeteorGeometry g, RandomSource r) {
		Vec3 axis = new Vec3(r.nextDouble() - 0.5, r.nextDouble() - 0.5, r.nextDouble() - 0.5);
		MeteorRock rock = new MeteorRock(g, axis.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : axis.normalize());
		int n = (int) Math.ceil(RADIUS);
		for (int x = -n; x <= n; x++) {
			for (int y = -n; y <= n; y++) {
				for (int z = -n; z <= n; z++) {
					double d = Math.sqrt(x * x + y * y + z * z);
					// A rough ball: the shell, its edge nibbled at random.
					if (d > RADIUS || d <= HOLLOW || (d > RADIUS - 0.5 && r.nextFloat() < 0.3F)) continue;
					if (!kit.budget().ghostBlock()) break;
					float k = r.nextFloat();
					BlockState s = k < 0.4F ? Blocks.MAGMA_BLOCK.defaultBlockState()
							: k < 0.7F ? Blocks.BLACKSTONE.defaultBlockState()
							: k < 0.85F ? Blocks.BASALT.defaultBlockState() : Blocks.NETHERRACK.defaultBlockState();
					rock.pieces.add(new Piece(x, y, z, new BlockModelRenderState(), s));
				}
			}
		}
		if (rock.pieces.isEmpty()) return null;
		LIVE.add(rock);
		return rock;
	}

	/** The fx is still running at its tick {@code t}: keep the rock (and its clock) there. */
	void keep(int t) {
		kept = 0;
		age = t;
	}

	int blocks() {
		return pieces.size();
	}

	static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(MeteorRock::tick);
		LevelRenderEvents.COLLECT_SUBMITS.register(MeteorRock::submit);
	}

	/** Rocks flying (the client tests read it). */
	static int live() {
		return LIVE.size();
	}

	private static void tick(Minecraft mc) {
		if (mc.level != seen) {
			seen = mc.level;
			LIVE.clear();
			return;
		}
		if (mc.isPaused()) return;
		for (Iterator<MeteorRock> it = LIVE.iterator(); it.hasNext(); ) {
			MeteorRock rock = it.next();
			// Gone once it lands, or when its fx stopped keeping it.
			if (++rock.kept > 2 || rock.age >= MeteorGeometry.FALL_TICKS) it.remove();
		}
	}

	private static void submit(LevelRenderContext context) {
		if (LIVE.isEmpty()) return;
		Minecraft mc = Minecraft.getInstance();
		if (resolver == null) resolver = new BlockModelResolver(mc.getModelManager());
		Vec3 cam = context.levelState().cameraRenderState.pos;
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		PoseStack pose = context.poseStack();
		for (MeteorRock rock : LIVE) {
			if (!rock.resolved) {
				for (Piece p : rock.pieces) resolver.update(p.model(), p.state(), BlockDisplayContext.create());
				rock.resolved = true;
			}
			double t = rock.age + Math.min(partial, 1F);
			Vec3 at = rock.g.position(t);
			pose.pushPose();
			pose.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
			pose.rotateDegrees(Axis.of(rock.axis.toVector3f()), (float) (t * SPIN));
			for (Piece p : rock.pieces) {
				if (p.model().isEmpty()) continue;
				pose.pushPose();
				pose.translate(p.x() - 0.5, p.y() - 0.5, p.z() - 0.5);
				p.model().submit(pose, context.submitNodeCollector(), LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
				pose.popPose();
			}
			pose.popPose();
		}
	}
}
