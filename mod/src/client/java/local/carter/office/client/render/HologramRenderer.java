package local.carter.office.client.render;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import org.jspecify.annotations.Nullable;

import local.carter.office.OfficeMod;
import local.carter.office.client.interact.CornerDrag;
import local.carter.office.client.interact.HologramInteractions;
import local.carter.office.client.interact.HologramPreview;
import local.carter.office.client.render.HologramLook.Label;
import local.carter.office.client.render.HologramLook.State;
import local.carter.office.core.HologramGeometry.Corner;
import local.carter.office.core.HologramGeometry.Frame;
import local.carter.office.core.HologramSpec;
import local.carter.office.core.Vec3d;
import local.carter.office.hologram.HologramBlockEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;

/**
 * Draws a hologram above its emitter: the content quad (live screen or a look panel), a soft additive cyan edge glow
 * 0.06 blocks wide, faint scanlines from more than 3 blocks away, and for placeholder looks an icon + label in the game
 * font (crisp at any size). When the owner looks at it holding an emitter, four glowing corner handles and a thin
 * outline show it can be resized; while resizing, a cyan outline (with handles) shows the new size.
 *
 * <p>Everything is emitted through vanilla's block-entity submit pipeline with stock render types (text = full-bright
 * textured, eyes = emissive textured without alpha cut-off, lightning = additive colour), so it works unchanged with
 * Sodium, which leaves block-entity rendering to vanilla.
 */
public class HologramRenderer implements BlockEntityRenderer<HologramBlockEntity, HologramRenderState> {
	public static final Identifier PANEL = OfficeMod.id("textures/misc/panel.png");
	public static final Identifier FROSTED = OfficeMod.id("textures/misc/frosted.png");
	public static final Identifier OFFLINE = OfficeMod.id("textures/misc/offline.png");
	public static final Identifier SCANLINES = OfficeMod.id("textures/misc/scanlines.png");
	public static final Identifier ICONS = OfficeMod.id("textures/misc/icons.png");

	/** Edge glow width (blocks) beyond the content. */
	public static final float GLOW = 0.06f;
	/** One scanline every 1/16 block, like a Minecraft pixel. */
	public static final float SCANLINE_SPACING = 1f / 16f;
	public static final int FULL_BRIGHT = 0xF000F0;

	private static final int CYAN = 0x4FE3FF;
	private static final int AMBER = 0xFFB84D;

	/** Which hologram the local player is zoomed into (Task 10 sets this); scanlines are hidden there. */
	private static volatile Predicate<UUID> zoomed = id -> false;

	private final Font font;

	public HologramRenderer(final BlockEntityRendererProvider.Context context) {
		this.font = context.font();
	}

	public static void setZoomedPredicate(final Predicate<UUID> predicate) {
		zoomed = predicate == null ? id -> false : predicate;
	}

	@Override
	public HologramRenderState createRenderState() {
		return new HologramRenderState();
	}

	@Override
	public void extractRenderState(final HologramBlockEntity be, final HologramRenderState st, final float partialTicks,
			final Vec3 cameraPosition, final @Nullable CrumblingOverlay breakProgress) {
		BlockEntityRenderer.super.extractRenderState(be, st, partialTicks, cameraPosition, breakProgress);
		boolean dragging = HologramInteractions.isDragging(be);
		if (dragging) {
			HologramInteractions.updateDrag(partialTicks);
		}
		HologramSpec spec = HologramPreview.effective(be);
		st.spec = spec;
		st.visible = spec != null;
		st.outline = null;
		st.hoverCorner = null;
		st.dragCorner = null;
		st.handles = false;
		if (spec == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		UUID viewer = mc.player == null ? null : mc.player.getUUID();
		boolean isOwner = viewer != null && viewer.equals(spec.owner());
		Optional<ScreenTexture> screen = ScreenTextureRegistry.get(be.hologramId()).filter(ScreenTexture::hasFrame);
		HologramLook.OwnSource own = isOwner ? HologramLook.ownSource(be.hologramId()) : HologramLook.OwnSource.READY;
		st.look = HologramLook.forViewer(spec, viewer, be.ownerOnline(), screen.isPresent(), own);
		// Upload what changed since the last frame (render thread; same place vanilla updates map textures). Only for
		// viewers who will see the screen, so a frosted/offline hologram never costs an upload.
		if (st.look == State.LIVE && !screen.get().prepareForRender()) {
			st.look = State.CONNECTING;
		}
		st.texture = st.look == State.LIVE ? screen.get().id() : lookTexture(st.look);

		BlockPosView pos = new BlockPosView(be);
		Frame frame = HologramPlacement.frame(pos.x, pos.y, pos.z, spec);
		double distance = HologramPlacement.distanceToPanel(frame, new Vec3d(cameraPosition.x, cameraPosition.y, cameraPosition.z));
		boolean isZoomed = zoomed.test(be.hologramId());
		st.contentAlpha = (float) HologramLook.contentAlpha(st.look, distance, isZoomed);
		st.scanlineAlpha = (float) HologramLook.scanlineAlpha(distance, isZoomed);
		String detail = isOwner && st.look == State.UNAVAILABLE ? HologramLook.ownDetail(be.hologramId()) : null;
		st.label = HologramLook.label(st.look, spec, isOwner, HologramInteractions.ownerName(mc, be), detail);
		st.timeMs = Util.getMillis();

		HologramInteractions.Hover hover = HologramInteractions.hover(partialTicks);
		st.highlighted = dragging || (hover != null && hover.be() == be);
		if (hover != null && hover.be() == be) {
			st.handles = true;
			st.hoverCorner = hover.corner();
		}
		CornerDrag drag = HologramInteractions.cornerDrag();
		if (drag != null && drag.blockEntity() == be) {
			st.outline = drag.preview();
			st.dragCorner = drag.corner();
		}
	}

	private static Identifier lookTexture(final State look) {
		return switch (look) {
			case PRIVATE_FROSTED -> FROSTED;
			case OFFLINE -> OFFLINE;
			default -> PANEL;
		};
	}

	@Override
	public void submit(final HologramRenderState st, final PoseStack poseStack, final SubmitNodeCollector collector,
			final CameraRenderState camera) {
		HologramSpec spec = st.spec;
		if (!st.visible || spec == null || st.texture == null) {
			return;
		}
		float hw = (float) spec.width() / 2f;
		float hh = (float) spec.height() / 2f;
		float seconds = (st.timeMs % 3_600_000L) / 1000f;
		int glowRgb = st.look == State.NEEDS_HELPER || st.look == State.UNAVAILABLE ? AMBER : CYAN;
		float glowAlpha = glowAlpha(st.look, seconds) + (st.highlighted ? 0.25f : 0f);

		poseStack.pushPose();
		toHologram(poseStack, spec);

		// 1. content quad (front only) and a faint sheet seen from behind
		Identifier texture = st.texture;
		int contentColor = argb(st.contentAlpha, 0xFFFFFF);
		collector.order(1).submitCustomGeometry(poseStack, RenderTypes.text(texture), (pose, buf) -> {
			textured(buf, pose, -hw, -hh, hw, hh, 0f, 0f, 0f, 1f, 1f, contentColor);
		});
		int backColor = argb(st.look == State.OFFLINE ? 0.05f : 0.10f, glowRgb);
		collector.order(1).submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, buf) -> {
			colored(buf, pose, hw, -hh, -hw, hh, -0.002f, backColor, backColor, false); // reversed: faces backwards
		});

		// 2. edge glow: soft additive ring behind the content plus a crisp edge line, visible from both sides
		float inner = Math.min(glowAlpha, 1f);
		collector.order(2).submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, buf) -> {
			glowRing(buf, pose, hw, hh, GLOW, -0.003f, argb(inner, glowRgb), argb(0f, glowRgb));
			edgeLine(buf, pose, hw, hh, 0.012f, 0.0015f, argb(Math.min(1f, inner * 1.25f), glowRgb));
		});

		// 3. scanlines (only beyond 3 blocks, never while zoomed) and the connecting sweep
		if (st.scanlineAlpha > 0f) {
			int scanColor = argb(st.scanlineAlpha, 0xD7F8FF);
			float drift = st.look == State.LIVE ? 0f : seconds * 0.5f;
			float vMax = (2f * hh) / SCANLINE_SPACING;
			collector.order(3).submitCustomGeometry(poseStack, RenderTypes.eyes(SCANLINES), (pose, buf) -> {
				emissive(buf, pose, -hw, -hh, hw, hh, 0.001f, 0f, drift, 1f, drift + vMax, scanColor);
			});
		}
		if (st.look == State.CONNECTING) {
			collector.order(3).submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, buf) -> sweep(buf, pose, hw, hh, seconds, glowRgb));
		}

		// 4. icon + label for placeholder looks
		Label label = st.label;
		if (label != null) {
			submitLabel(poseStack, collector.order(4), label, st.look, hw, hh, seconds);
		}

		// resize affordance (owner holding an emitter, looking at it): thin outline + four corner handles
		if (st.handles) {
			Corner hovered = st.hoverCorner;
			collector.order(4).submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, buf) -> {
				edgeLine(buf, pose, hw, hh, 0.010f, 0.007f, argb(0.70f, CYAN));
				handles(buf, pose, hw, hh, hovered);
			});
		}
		poseStack.popPose();

		// resize preview outline at the new size
		HologramSpec outline = st.outline;
		if (outline != null) {
			poseStack.pushPose();
			toHologram(poseStack, outline);
			float ow = (float) outline.width() / 2f;
			float oh = (float) outline.height() / 2f;
			Corner dragged = st.dragCorner;
			collector.order(4).submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, buf) -> {
				colored(buf, pose, -ow, -oh, ow, oh, 0.010f, argb(0.06f, CYAN), argb(0.06f, CYAN), true);
				edgeLine(buf, pose, ow, oh, 0.025f, 0.012f, argb(0.95f, CYAN));
				handles(buf, pose, ow, oh, dragged);
			});
			poseStack.popPose();
		}
	}

	/** Moves the pose from the block corner to the hologram centre, front facing +Z. */
	private static void toHologram(final PoseStack poseStack, final HologramSpec spec) {
		poseStack.translate(0.5 + spec.offX(), 0.5 + spec.offY(), 0.5 + spec.offZ());
		poseStack.rotateDegrees(Axis.YP, -spec.yaw());
	}

	private static float glowAlpha(final State look, final float seconds) {
		return switch (look) {
			case LIVE -> 0.55f;
			case CONNECTING -> 0.42f + 0.10f * (float) Math.sin(seconds * Math.PI);
			case PRIVATE_FROSTED -> 0.40f;
			case OFFLINE -> 0.13f + 0.07f * (0.5f + 0.5f * (float) Math.sin(seconds * Math.PI / 2.0)); // slow standby breathing
			case WINDOW_CLOSED -> 0.32f;
			case NEEDS_HELPER, UNAVAILABLE -> 0.50f;
			case PICK_WINDOW -> 0.42f;
		};
	}

	private void submitLabel(final PoseStack poseStack, final OrderedSubmitNodeCollector out, final Label label, final State look,
			final float hw, final float hh, final float seconds) {
		float w = 2 * hw;
		float h = 2 * hh;
		FormattedCharSequence title = Component.literal(label.title()).getVisualOrderText();
		FormattedCharSequence sub = label.subtitle().isEmpty() ? null : Component.literal(label.subtitle()).getVisualOrderText();
		float titleH = clamp(h * 0.10f, 0.04f, 0.42f);
		float s = titleH / 8f; // blocks per font pixel (cap height ~7-8 px)
		int titleW = Math.max(1, font.width(title));
		s = Math.min(s, 0.8f * w / titleW);
		float subS = s * 0.62f;
		if (sub != null) {
			subS = Math.min(subS, 0.85f * w / Math.max(1, font.width(sub)));
		}
		boolean hasIcon = label.icon() != HologramLook.Icon.NONE;
		float icon = hasIcon ? Math.min(8f * s * 2.6f, h * 0.34f) : 0f;
		float gapIcon = hasIcon ? 8f * s * 0.45f : 0f;
		float gapSub = sub != null ? 8f * s * 0.35f : 0f;
		float total = icon + gapIcon + 9f * s + gapSub + (sub != null ? 9f * subS : 0f);
		float y = total / 2f; // top of the stack (local +Y is up)

		boolean dim = look == State.OFFLINE;
		float pulse = look == State.CONNECTING ? 0.80f + 0.20f * (float) Math.sin(seconds * Math.PI) : 1f;
		int titleColor = argb((dim ? 0.80f : 1f) * pulse, dim ? 0x9CC7D3 : 0xE8FCFF);
		int subColor = argb(dim ? 0.70f : 0.90f, dim ? 0x7CA3AE : 0xA6DCE8);

		if (hasIcon) {
			int idx = label.icon().ordinal() - 1; // LOCK, POWER, DOWNLOAD, WINDOW in the atlas
			float u0 = idx * 0.25f;
			int iconColor = argb(dim ? 0.70f : 0.95f, dim ? 0x8FC3D1 : 0xC8F6FF);
			float half = icon / 2f;
			float top = y;
			out.submitCustomGeometry(poseStack, RenderTypes.eyes(ICONS), (pose, buf) -> {
				emissive(buf, pose, -half, top - icon, half, top, 0.003f, u0, 0f, u0 + 0.25f, 1f, iconColor);
			});
			y -= icon + gapIcon;
		}
		text(poseStack, out, title, s, y, titleColor);
		y -= 9f * s + gapSub;
		if (sub != null) {
			text(poseStack, out, sub, subS, y, subColor);
		}
	}

	/** Centred line of text whose top edge is at local {@code topY}. */
	private void text(final PoseStack poseStack, final OrderedSubmitNodeCollector out, final FormattedCharSequence line, final float scale,
			final float topY, final int color) {
		poseStack.pushPose();
		poseStack.translate(0f, topY, 0.004f);
		poseStack.scale(scale, -scale, scale);
		out.submitText(poseStack, -font.width(line) / 2f, 0f, line, false, Font.DisplayMode.POLYGON_OFFSET, FULL_BRIGHT, color, 0, 0);
		poseStack.popPose();
	}

	// ---- geometry helpers (local hologram space: front faces +Z; quads wound counter-clockwise seen from the front) ----

	private static void textured(final VertexConsumer b, final PoseStack.Pose p, final float x0, final float y0, final float x1, final float y1,
			final float z, final float u0, final float v0, final float u1, final float v1, final int color) {
		b.addVertex(p, x0, y1, z).setColor(color).setUv(u0, v0).setLight(FULL_BRIGHT);
		b.addVertex(p, x0, y0, z).setColor(color).setUv(u0, v1).setLight(FULL_BRIGHT);
		b.addVertex(p, x1, y0, z).setColor(color).setUv(u1, v1).setLight(FULL_BRIGHT);
		b.addVertex(p, x1, y1, z).setColor(color).setUv(u1, v0).setLight(FULL_BRIGHT);
	}

	/** Emissive textured quad (entity vertex format) for the eyes render type; v0 is the top edge. */
	private static void emissive(final VertexConsumer b, final PoseStack.Pose p, final float x0, final float y0, final float x1, final float y1,
			final float z, final float u0, final float v0, final float u1, final float v1, final int color) {
		vertex(b, p, x0, y1, z, u0, v0, color);
		vertex(b, p, x0, y0, z, u0, v1, color);
		vertex(b, p, x1, y0, z, u1, v1, color);
		vertex(b, p, x1, y1, z, u1, v0, color);
	}

	private static void vertex(final VertexConsumer b, final PoseStack.Pose p, final float x, final float y, final float z, final float u,
			final float v, final int color) {
		b.addVertex(p, x, y, z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULL_BRIGHT).setNormal(p, 0f, 0f, 1f);
	}

	/**
	 * Axis-aligned colour quad from (x0, y0) to (x1, y1); {@code top}/{@code bottom} colours along y. Front-facing when
	 * x0 &lt; x1 and y0 &lt; y1; {@code twoSided} adds the reverse winding.
	 */
	private static void colored(final VertexConsumer b, final PoseStack.Pose p, final float x0, final float y0, final float x1, final float y1,
			final float z, final int bottom, final int top, final boolean twoSided) {
		quad(b, p, z, x0, y1, top, x0, y0, bottom, x1, y0, bottom, x1, y1, top, twoSided);
	}

	/** Quad from four corners given counter-clockwise as seen from the front (tl, bl, br, tr). */
	private static void quad(final VertexConsumer b, final PoseStack.Pose p, final float z, final float ax, final float ay, final int ac,
			final float bx, final float by, final int bc, final float cx, final float cy, final int cc, final float dx, final float dy, final int dc,
			final boolean twoSided) {
		b.addVertex(p, ax, ay, z).setColor(ac);
		b.addVertex(p, bx, by, z).setColor(bc);
		b.addVertex(p, cx, cy, z).setColor(cc);
		b.addVertex(p, dx, dy, z).setColor(dc);
		if (twoSided) {
			b.addVertex(p, dx, dy, z).setColor(dc);
			b.addVertex(p, cx, cy, z).setColor(cc);
			b.addVertex(p, bx, by, z).setColor(bc);
			b.addVertex(p, ax, ay, z).setColor(ac);
		}
	}

	/** Soft ring {@code g} wide around the content: {@code in} at the content edge fading to {@code out}. */
	private static void glowRing(final VertexConsumer b, final PoseStack.Pose p, final float hw, final float hh, final float g, final float z,
			final int in, final int out) {
		float ox = hw + g;
		float oy = hh + g;
		quad(b, p, z, -hw, oy, out, -hw, hh, in, hw, hh, in, hw, oy, out, true); // top
		quad(b, p, z, -hw, -hh, in, -hw, -oy, out, hw, -oy, out, hw, -hh, in, true); // bottom
		quad(b, p, z, -ox, hh, out, -ox, -hh, out, -hw, -hh, in, -hw, hh, in, true); // left
		quad(b, p, z, hw, hh, in, hw, -hh, in, ox, -hh, out, ox, hh, out, true); // right
		quad(b, p, z, -ox, oy, out, -ox, hh, out, -hw, hh, in, -hw, oy, out, true); // top-left
		quad(b, p, z, hw, oy, out, hw, hh, in, ox, hh, out, ox, oy, out, true); // top-right
		quad(b, p, z, -ox, -hh, out, -ox, -oy, out, -hw, -oy, out, -hw, -hh, in, true); // bottom-left
		quad(b, p, z, hw, -hh, in, hw, -oy, out, ox, -oy, out, ox, -hh, out, true); // bottom-right
	}

	/** Thin frame straddling the content edge ({@code t} thick, two thirds outside). */
	private static void edgeLine(final VertexConsumer b, final PoseStack.Pose p, final float hw, final float hh, final float t, final float z,
			final int color) {
		float in = t / 3f;
		float out = t - in;
		colored(b, p, -hw - out, hh - in, hw + out, hh + out, z, color, color, true); // top
		colored(b, p, -hw - out, -hh - out, hw + out, -hh + in, z, color, color, true); // bottom
		colored(b, p, -hw - out, -hh + in, -hw + in, hh - in, z, color, color, true); // left
		colored(b, p, hw - in, -hh + in, hw + out, hh - in, z, color, color, true); // right
	}

	/**
	 * Four glowing square resize handles centred on the corners; the {@code active} one (hovered or being dragged) is
	 * 1.6x larger, near-white, with a soft halo. Sizes scale with the hologram but stay readable on tiny and huge ones.
	 */
	private static void handles(final VertexConsumer b, final PoseStack.Pose p, final float hw, final float hh, final @Nullable Corner active) {
		float base = handleHalfSize(2f * hw, 2f * hh);
		for (Corner c : Corner.values()) {
			float cx = (c == Corner.TOP_RIGHT || c == Corner.BOTTOM_RIGHT ? 1f : -1f) * hw;
			float cy = (c == Corner.TOP_LEFT || c == Corner.TOP_RIGHT ? 1f : -1f) * hh;
			boolean hot = c == active;
			float s = hot ? base * 1.6f : base;
			if (hot) {
				float g = s * 2.4f; // soft halo
				int in = argb(0.30f, CYAN);
				int out = argb(0f, CYAN);
				// one quadrant per quad, bright at the corner point, fading outwards
				quad(b, p, 0.008f, cx - g, cy + g, out, cx - g, cy, out, cx, cy, in, cx, cy + g, out, true);
				quad(b, p, 0.008f, cx - g, cy, out, cx - g, cy - g, out, cx, cy - g, out, cx, cy, in, true);
				quad(b, p, 0.008f, cx, cy, in, cx, cy - g, out, cx + g, cy - g, out, cx + g, cy, out, true);
				quad(b, p, 0.008f, cx, cy + g, out, cx, cy, in, cx + g, cy, out, cx + g, cy + g, out, true);
			}
			int border = argb(hot ? 1.0f : 0.85f, hot ? 0xF2FEFF : CYAN);
			int fill = argb(hot ? 0.95f : 0.55f, hot ? 0xC8FBFF : 0x1FA9C9);
			colored(b, p, cx - s, cy - s, cx + s, cy + s, 0.009f, border, border, true);
			float t = Math.max(0.006f, s * 0.28f);
			colored(b, p, cx - s + t, cy - s + t, cx + s - t, cy + s - t, 0.010f, fill, fill, true);
		}
	}

	/** Half the side of a corner handle for a w x h hologram (blocks). */
	static float handleHalfSize(final float w, final float h) {
		return clamp(0.05f * Math.min(w, h), 0.03f, 0.09f);
	}

	/** "Connecting" sweep: a soft band with a bright leading line moving top to bottom every 2.2 s. */
	private static void sweep(final VertexConsumer b, final PoseStack.Pose p, final float hw, final float hh, final float seconds, final int rgb) {
		float h = 2 * hh;
		float band = 0.22f * h;
		float phase = (seconds % 2.2f) / 2.2f;
		float lead = hh - phase * (h + band); // leading (lower) edge, travelling down from the top
		float trail = lead + band;
		float y0 = Math.max(lead, -hh);
		float y1 = Math.min(trail, hh);
		if (y1 > y0) {
			float a0 = 0.20f * (1f - (y0 - lead) / band);
			float a1 = 0.20f * (1f - (y1 - lead) / band);
			colored(b, p, -hw, y0, hw, y1, 0.002f, argb(a0, rgb), argb(a1, rgb), false);
		}
		float lt = Math.max(0.004f, h * 0.004f);
		if (lead - lt > -hh && lead + lt < hh) {
			int line = argb(0.50f, rgb);
			colored(b, p, -hw, lead - lt, hw, lead + lt, 0.0025f, line, line, false);
		}
	}

	static int argb(final float alpha, final int rgb) {
		int a = Math.round(clamp(alpha, 0f, 1f) * 255f);
		return (a << 24) | (rgb & 0xFFFFFF);
	}

	private static float clamp(final float v, final float lo, final float hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	@Override
	public boolean shouldRenderOffScreen() {
		return true;
	}

	@Override
	public int getViewDistance() {
		return 64;
	}

	/** Block position as doubles (keeps the extract code short). */
	private record BlockPosView(double x, double y, double z) {
		BlockPosView(final HologramBlockEntity be) {
			this(be.getBlockPos().getX(), be.getBlockPos().getY(), be.getBlockPos().getZ());
		}
	}
}
