// Adapted from Wildercord (MIT) https://github.com/defnotean/wildercord @9f5a21fe7964097492cd92ac64e4c4e05ef5236a
package local.carter.office.client.magic.fx;

import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import local.carter.office.OfficeMod;

import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.state.level.ParticleGroupRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.phys.AABB;

import net.fabricmc.fabric.api.client.particle.v1.ParticleGroupRegistry;

/**
 * How magic is blended into the world (Wildercord's GlowLayers + SigilGroup). {@link #GLOW}: light that adds to what is behind it
 * (overlapping light burns brighter, like real light) and never hides anything; {@link #DARK}: the reverse, taking light away, for
 * shadow magic. Both are vanilla's particle pipeline with another blend, no depth writes and no back-face culling (every circle,
 * bolt and mote is drawn once and seen from both sides). Circles and bolts live in their own particle group, culled by their whole
 * extent, so a circle under your feet whose centre is below the view still draws.
 */
public final class LightPipeline {
	private LightPipeline() {}

	/** Added light: source × alpha + destination. */
	public static final RenderPipeline GLOW_PIPELINE = RenderPipeline.builder(RenderPipelines.PARTICLE_SNIPPET)
			.withLocation(OfficeMod.id("pipeline/magic_glow"))
			.withCull(false)
			.withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
			.withDepthStencilState(new DepthStencilState(DepthStencilState.DEFAULT.depthTest(), false))
			.build();

	/** Taken light: destination − source × alpha. */
	public static final RenderPipeline DARK_PIPELINE = RenderPipeline.builder(RenderPipelines.PARTICLE_SNIPPET)
			.withLocation(OfficeMod.id("pipeline/magic_dark"))
			.withCull(false)
			.withColorTargetState(new ColorTargetState(new BlendFunction(BlendFactor.SRC_ALPHA, BlendFactor.ONE, BlendOp.REVERSE_SUBTRACT)))
			.withDepthStencilState(new DepthStencilState(DepthStencilState.DEFAULT.depthTest(), false))
			.build();

	/**
	 * The same added light under Improved Transparency (order-independent transparency), which draws translucent particles in its
	 * own passes and needs a pipeline for each ("OIT pipeline set for particle layer not specified" otherwise). Vanilla's lightning
	 * does the same.
	 */
	public static final OitPipelineSet GLOW_OIT = OitPipelineSet.builder("office_magic_glow",
			RenderPipeline.builder(RenderPipelines.OIT_PARTICLE_SNIPPET).withShaderDefine("OIT_ADDITIVE").withCull(false)).build();

	public static final SingleQuadParticle.Layer GLOW = new SingleQuadParticle.Layer(true, TextureAtlas.LOCATION_PARTICLES, GLOW_PIPELINE, GLOW_OIT);

	/**
	 * Darkness is drawn with the solid particles: Improved Transparency can only add light, never take it away, so a translucent
	 * darkness layer would have no pipeline there. It writes no depth, so it still never hides what is behind it.
	 */
	public static final SingleQuadParticle.Layer DARK = new SingleQuadParticle.Layer(false, TextureAtlas.LOCATION_PARTICLES, DARK_PIPELINE);

	/** The particle group circles and bolts are kept and drawn in. */
	public static final ParticleRenderType GROUP = new ParticleRenderType(OfficeMod.id("magic_fx").toString(), "OM");

	/** What to subtract to darken toward {@code rgb}: mostly white (so it darkens evenly), less of the tint's own channels. */
	public static int darkColor(int rgb) {
		return (sub((rgb >> 16) & 0xFF) << 16) | (sub((rgb >> 8) & 0xFF) << 8) | sub(rgb & 0xFF);
	}

	private static int sub(int c) {
		return Math.round(255 * 0.7F + (255 - c) * 0.3F);
	}

	/** Registers {@link #GROUP} with the particle engine (client init). */
	public static void register() {
		ParticleGroupRegistry.register(GROUP, Group::new);
	}

	/** A particle that knows its whole extent: its centre and how far it reaches from it. */
	public interface Extent {
		double centreX();

		double centreY();

		double centreZ();

		double reach();
	}

	/** Culls each particle by its whole extent instead of its centre. */
	public static final class Group extends ParticleGroup<SingleQuadParticle> {
		private final QuadParticleRenderState state = new QuadParticleRenderState();

		public Group(ParticleEngine engine) {
			super(engine);
		}

		@Override
		public ParticleGroupRenderState extractRenderState(Frustum frustum, Camera camera, float partial) {
			long start = System.nanoTime();
			try {
				extractAll(frustum, camera, partial);
			} finally {
				extractNanos += System.nanoTime() - start;
				extractFrames++;
			}
			return state;
		}

		private void extractAll(Frustum frustum, Camera camera, float partial) {
			for (SingleQuadParticle particle : particles) {
				if (particle instanceof Extent e) {
					double r = e.reach();
					if (!frustum.isVisible(new AABB(e.centreX() - r, e.centreY() - r, e.centreZ() - r, e.centreX() + r, e.centreY() + r, e.centreZ() + r))) {
						continue;
					}
				}
				try {
					particle.extract(state, camera, partial);
				} catch (RuntimeException ex) {
					// One broken visual must never take the frame down (vanilla's quad group does the same).
					particle.remove();
				}
			}
		}
	}

	/** Time spent laying out circles and bolts, for measurements (render thread). */
	private static long extractNanos;
	private static long extractFrames;

	/** Mean milliseconds a frame spent laying out circles and bolts since the last call, and resets (the game tests read it). */
	public static double takeExtractMillisPerFrame() {
		double ms = extractFrames == 0 ? 0 : extractNanos / 1.0E6 / extractFrames;
		extractNanos = 0;
		extractFrames = 0;
		return ms;
	}
}
