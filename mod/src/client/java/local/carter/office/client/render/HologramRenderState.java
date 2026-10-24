package local.carter.office.client.render;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.dock.DockLayout;

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
	/** Owner holding an emitter looks at it: draw the four resize handles and a thin outline. */
	public boolean handles;
	/** Corner under the crosshair (owner holding an emitter): its handle is drawn bigger and brighter. */
	public @Nullable Corner hoverCorner;
	/** Corner being dragged (its handle on the outline is the bright one). */
	public @Nullable Corner dragCorner;
	/** New size while a corner drag is in progress: drawn as a cyan outline. */
	public @Nullable HologramSpec outline;
	/** The mini Dock (owner's live Extend holograms only): its layout, each icon's texture (null: not loaded yet). */
	public @Nullable DockLayout dock;
	public final List<@Nullable Identifier> dockIcons = new ArrayList<>();
	/** The icon under the crosshair (-1: none) and its name. */
	public int dockHover = -1;
	public String dockLabel = "";
}
