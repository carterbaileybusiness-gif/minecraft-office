package local.carter.office.client.render;

import org.jspecify.annotations.Nullable;

import local.carter.office.core.HologramGeometry.Corner;
import local.carter.office.core.HologramSpec;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.resources.Identifier;

/** Everything {@link HologramRenderer#submit} needs, captured on the render thread in extractRenderState. */
public class HologramRenderState extends BlockEntityRenderState {
	public boolean visible;
	/** The spec to draw (may be a move/resize preview rather than the synced one). */
	public @Nullable HologramSpec spec;
	public HologramLook.State look = HologramLook.State.CONNECTING;
	public @Nullable Identifier texture;
	public float contentAlpha = 1f;
	public float scanlineAlpha;
	public HologramLook.@Nullable Label label;
	public long timeMs;
	/** Glow strength boost while hovered with an emitter or being dragged. */
	public boolean highlighted;
	/** Corner under the crosshair (owner holding an emitter): draws a grab bracket. */
	public @Nullable Corner hoverCorner;
	/** New size while a corner drag is in progress: drawn as a cyan outline. */
	public @Nullable HologramSpec outline;
}
