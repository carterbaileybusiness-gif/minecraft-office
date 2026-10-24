package local.carter.office.client.magic;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import local.carter.office.client.magic.screen.GrimoireLayout;
import local.carter.office.client.magic.screen.HowToLayout;
import local.carter.office.client.magic.screen.HowToLayout.Block;
import local.carter.office.client.magic.screen.HowToLayout.Glyph;
import local.carter.office.client.magic.screen.HowToLayout.Heading;
import local.carter.office.client.magic.screen.HowToLayout.Keys;
import local.carter.office.client.magic.screen.HowToLayout.Para;
import local.carter.office.client.magic.screen.HowToLayout.Placed;
import local.carter.office.client.magic.screen.HowToLayout.Step;
import local.carter.office.client.magic.screen.SignLayout.Rect;

/**
 * The How to cast pages fit their book page at every window size and GUI scale, with the real (English) text wrapped by a
 * stand-in font a little wider than Minecraft's (6 units a character), and never scroll: every block inside the page, in
 * order, none overlapping.
 */
class HowToLayoutTest {
	private static final int CHAR = 6;
	private static final List<Block> LEFT = List.of(new Heading("magic.office.howto_casting"),
			new Step("magic.office.howto_step_offhand", 1, Glyph.OFFHAND), new Step("magic.office.howto_step_tap", 2, Glyph.RIGHT_CLICK),
			new Step("magic.office.howto_step_hold", 3, Glyph.CHARGE), new Step("magic.office.howto_step_pick", 4, Glyph.WHEEL),
			new Step("magic.office.howto_step_mana", 5, Glyph.MANA));
	private static final List<Block> RIGHT = List.of(new Heading("magic.office.howto_signs_title"), new Keys(),
			new Para("magic.office.howto_signs", false), new Para("magic.office.signs_hint", true), new Heading("magic.office.howto_more"),
			new Step("magic.office.howto_tree", 0, Glyph.TREE), new Step("magic.office.howto_recall", 0, Glyph.RECALL));

	private static JsonObject lang() throws Exception {
		try (InputStream in = HowToLayoutTest.class.getResourceAsStream("/assets/office/lang/en_us.json")) {
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	/** Greedy word wrap of the block's text at {@link #CHAR} units a character. */
	private static HowToLayout.Lines lines(JsonObject lang) {
		return (b, room, scale) -> {
			String key = switch (b) {
				case Step s -> s.key();
				case Para p -> p.key();
				default -> throw new AssertionError("no text in " + b);
			};
			String text = lang.get(key).getAsString().replace("%s", "F");
			int max = Math.max(1, (int) Math.floor(room / scale / CHAR));
			int n = 1, used = 0;
			for (String w : text.split(" ")) {
				int len = w.length();
				if (used > 0 && used + 1 + len > max) {
					n++;
					used = 0;
				}
				used += (used > 0 ? 1 : 0) + len;
				while (used > max) {
					n++;
					used -= max;
				}
			}
			return n;
		};
	}

	private static void assertFits(List<Block> blocks, Rect page, float unit, int gs, HowToLayout.Lines lines, String at) {
		HowToLayout.Page p = HowToLayout.place(blocks, page.w(), page.h(), unit, gs, lines);
		assertEquals(blocks.size(), p.blocks().size(), at);
		assertTrue(p.scale() * gs >= 1f - 1e-4f, at + ": text at least one physical pixel a font pixel");
		assertEquals(Math.round(p.scale() * gs), p.scale() * gs, 1e-4f, at + ": crisp");
		float prev = -1;
		for (Placed pl : p.blocks()) {
			assertTrue(pl.y() >= prev, at + ": " + pl.block() + " in order, not overlapping");
			assertTrue(pl.y() + pl.h() <= page.h() + 0.01f, at + ": " + pl.block() + " ends at " + (pl.y() + pl.h()) + " of " + page.h());
			if (pl.block() instanceof Step) {
				assertTrue(HowToLayout.textRoom(pl.block(), page.w(), p.scale()) >= 8 * CHAR * p.scale(), at + ": room for words beside the picture");
			}
			prev = pl.y() + pl.h();
		}
	}

	@Test
	void bothPagesFitAtEveryWindowAndGuiScale() throws Exception {
		HowToLayout.Lines lines = lines(lang());
		int[][] windows = {{854, 480}, {1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}, {1024, 768}};
		for (int[] win : windows) {
			for (int gs = 1; gs <= 6; gs++) {
				int w = win[0] / gs, h = win[1] / gs;
				if (w < 320 || h < 240) continue; // Minecraft never picks a GUI scale that small
				GrimoireLayout.Layout l = GrimoireLayout.layout(w, h);
				String at = win[0] + "x" + win[1] + " @" + gs;
				assertFits(LEFT, l.leftPage(), l.unit(), gs, lines, at + " left");
				assertFits(RIGHT, l.rightPage(), l.unit(), gs, lines, at + " right");
			}
		}
	}

	@Test
	void roomLeftOverSpreadsBetweenTheBlocks() throws Exception {
		HowToLayout.Lines lines = lines(lang());
		HowToLayout.Page tight = HowToLayout.place(LEFT, 120, 10_000, 1f, 1, lines);
		List<Placed> b = tight.blocks();
		float gap = b.get(1).y() - (b.get(0).y() + b.get(0).h());
		for (int i = 2; i < b.size(); i++) {
			assertEquals(gap, b.get(i).y() - (b.get(i - 1).y() + b.get(i - 1).h()), 1e-3f, "even gaps");
		}
		assertTrue(gap <= 10f, "a tall page doesn't stretch the steps apart without limit");
	}

	@Test
	void theLargestFittingScaleWins() throws Exception {
		HowToLayout.Lines lines = lines(lang());
		HowToLayout.Page big = HowToLayout.place(LEFT, 600, 900, 3f, 3, lines);
		assertEquals(3f, big.scale(), 1e-6f, "plenty of room: the unit's scale");
		HowToLayout.Page small = HowToLayout.place(LEFT, 600, 160, 3f, 3, lines);
		assertTrue(small.scale() < 3f, "a short page shrinks the text");
	}
}
