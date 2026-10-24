package local.carter.office.client.magic.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Filled discs and rings drawn as horizontal spans, one per GUI row (no texture, so they stay crisp and any size): the Skill
 * tree's nodes. Pure maths in {@link #halfWidth}; the drawing is a fill per row.
 */
public final class Disc {
	private Disc() {}

	/** Half the width of a disc of radius {@code r} at vertical offset {@code dy} from its centre (0 outside it). */
	public static float halfWidth(float r, float dy) {
		float h = r * r - dy * dy;
		return h <= 0 ? 0 : (float) Math.sqrt(h);
	}

	/** A filled disc of radius {@code r} centred on ({@code cx}, {@code cy}). */
	public static void fill(GuiGraphicsExtractor g, float cx, float cy, float r, int argb) {
		if (r <= 0 || ((argb >>> 24) & 0xFF) < 2) return;
		int y0 = (int) Math.floor(cy - r), y1 = (int) Math.ceil(cy + r);
		for (int y = y0; y < y1; y++) {
			float w = halfWidth(r, y + 0.5f - cy);
			if (w <= 0.25f) continue;
			int x0 = Math.round(cx - w), x1 = Math.round(cx + w);
			if (x1 > x0) g.fill(x0, y, x1, y + 1, argb);
		}
	}

	/** A ring {@code th} thick whose outer radius is {@code r}. */
	public static void ring(GuiGraphicsExtractor g, float cx, float cy, float r, float th, int argb) {
		if (r <= 0 || ((argb >>> 24) & 0xFF) < 2) return;
		float inner = Math.max(0, r - Math.max(1f, th));
		int y0 = (int) Math.floor(cy - r), y1 = (int) Math.ceil(cy + r);
		for (int y = y0; y < y1; y++) {
			float dy = y + 0.5f - cy;
			float w = halfWidth(r, dy);
			if (w <= 0.25f) continue;
			int ox0 = Math.round(cx - w), ox1 = Math.round(cx + w);
			float wi = halfWidth(inner, dy);
			if (wi <= 0.25f) {
				if (ox1 > ox0) g.fill(ox0, y, ox1, y + 1, argb);
				continue;
			}
			int ix0 = Math.round(cx - wi), ix1 = Math.round(cx + wi);
			if (ix0 > ox0) g.fill(ox0, y, ix0, y + 1, argb);
			if (ox1 > ix1) g.fill(ix1, y, ox1, y + 1, argb);
		}
	}
}
