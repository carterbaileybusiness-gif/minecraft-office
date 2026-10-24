package local.carter.office.client.magic.screen;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the How to cast tab draws, in GUI units: each page is a column of blocks (a heading, numbered steps with a small
 * picture on the left, a row of arrow keycaps, plain lines) set at the largest crisp text scale at which the whole column
 * fits the page; the room left over spreads evenly between the blocks. Nothing scrolls at any size. Pure: the screen
 * counts the wrapped lines ({@link Lines}), so the fit is unit-tested without a font.
 */
public final class HowToLayout {
	/** A step's picture: drawn at {@link #GLYPH} × scale, square. */
	public enum Glyph { OFFHAND, RIGHT_CLICK, CHARGE, WHEEL, MANA, TREE, RECALL }

	/** One part of a page; a {@code key} is the lang key of the text the screen wraps. */
	public sealed interface Block {}

	/** A page title, underlined. */
	public record Heading(String key) implements Block {}

	/** A step: {@code number} (0 = none) and a picture on the left, its text wrapped beside it. */
	public record Step(String key, int number, Glyph glyph) implements Block {}

	/** The four arrow keys as keycaps (↑ ↓ ← →). */
	public record Keys() implements Block {}

	/** Lines of text across the page; {@code soft} ones in the faded ink. */
	public record Para(String key, boolean soft) implements Block {}

	/** How many lines {@code block}'s text takes wrapped {@code room} wide at {@code scale}. */
	@FunctionalInterface
	public interface Lines {
		int count(Block block, float room, float scale);
	}

	/** A block placed at {@code y} (from the page's top), {@code h} high. */
	public record Placed(Block block, float y, float h) {}

	/** A page laid out at text {@code scale}. */
	public record Page(float scale, List<Placed> blocks) {}

	/** A step's picture size (and a keycap's height) per text scale. */
	public static final float GLYPH = 20f;
	public static final float KEYCAP = 13f;
	/** Room between a step's picture and its text, per text scale. */
	public static final float GLYPH_GAP = 5f;
	/** The smallest gap between blocks, and the most extra room one gap takes, per text scale. */
	private static final float MIN_GAP = 3f;
	private static final float MAX_EXTRA_GAP = 7f;

	private HowToLayout() {}

	/** The text's room in a block {@code w} wide at {@code scale}. */
	public static float textRoom(Block b, float w, float scale) {
		return b instanceof Step ? w - (GLYPH + GLYPH_GAP) * scale : w;
	}

	/** {@code b}'s height at {@code scale} on a page {@code w} wide. */
	public static float height(Block b, float w, float scale, Lines lines) {
		return switch (b) {
			case Heading h -> 10 * scale + 3 * scale;
			case Keys k -> KEYCAP * scale + scale;
			case Para p -> textHeight(lines.count(p, w, scale), scale);
			case Step s -> Math.max(GLYPH * scale, textHeight(lines.count(s, textRoom(s, w, scale), scale), scale));
		};
	}

	/** {@code n} lines of 10 × scale, less the spacing under the last. */
	public static float textHeight(int n, float scale) {
		return Math.max(1, n) * 10 * scale - scale;
	}

	/**
	 * {@code blocks} on a {@code w} × {@code h} page at the largest crisp scale (whole physical pixels at GUI scale
	 * {@code guiScale}) up to {@code max} at which they all fit; at the smallest scale when nothing fits.
	 */
	public static Page place(List<Block> blocks, float w, float h, float max, int guiScale, Lines lines) {
		int gs = Math.max(1, guiScale);
		int steps = Math.max(1, (int) Math.floor(max * gs + 1e-4f));
		for (int n = steps; n >= 1; n--) {
			float s = n / (float) gs;
			Page p = placeAt(blocks, w, h, s, lines);
			if (p != null || n == 1) {
				return p != null ? p : stack(blocks, w, s, MIN_GAP * s, lines);
			}
		}
		throw new IllegalStateException("unreachable");
	}

	private static Page placeAt(List<Block> blocks, float w, float h, float s, Lines lines) {
		float used = 0;
		for (Block b : blocks) used += height(b, w, s, lines);
		int gaps = Math.max(0, blocks.size() - 1);
		float spare = h - used - gaps * MIN_GAP * s;
		if (spare < 0) return null;
		float extra = gaps == 0 ? 0 : Math.min(spare / (gaps + 1), MAX_EXTRA_GAP * s);
		return stack(blocks, w, s, MIN_GAP * s + extra, lines);
	}

	private static Page stack(List<Block> blocks, float w, float s, float gap, Lines lines) {
		List<Placed> out = new ArrayList<>(blocks.size());
		float y = 0;
		for (Block b : blocks) {
			float bh = height(b, w, s, lines);
			out.add(new Placed(b, y, bh));
			y += bh + gap;
		}
		return new Page(s, List.copyOf(out));
	}
}
