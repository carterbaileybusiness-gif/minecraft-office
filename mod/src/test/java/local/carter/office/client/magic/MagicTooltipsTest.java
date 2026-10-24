package local.carter.office.client.magic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import local.carter.office.magic.core.Element;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;

class MagicTooltipsTest {
	private static String key(Component c) {
		return ((TranslatableContents) c.getContents()).getKey();
	}

	@Test
	void unboundGrimoireIsOneLine() {
		List<Component> lines = MagicTooltips.grimoire(Optional.empty(), 1);
		assertEquals(1, lines.size());
		assertEquals("item.office.grimoire.tip_unbound", key(lines.get(0)));
	}

	@Test
	void boundGrimoireNamesElementAndLevelThenHowToCast() {
		List<Component> lines = MagicTooltips.grimoire(Optional.of(Element.FIRE), 4);
		assertEquals(2, lines.size());
		TranslatableContents bound = (TranslatableContents) lines.get(0).getContents();
		assertEquals("item.office.grimoire.tip_bound", bound.getKey());
		assertEquals(2, bound.getArgs().length);
		assertEquals(4, bound.getArgs()[1]);
		assertEquals("item.office.grimoire.tip_cast", key(lines.get(1)));
	}

	/** A fake splitter that cuts text into chunks of at most {@code width} characters, keeping each chunk's style. */
	private static List<FormattedText> chunks(FormattedText text, int width) {
		List<FormattedText> out = new ArrayList<>();
		text.visit((style, s) -> {
			for (int i = 0; i < s.length(); i += width) {
				out.add(FormattedText.of(s.substring(i, Math.min(s.length(), i + width)), style));
			}
			return Optional.empty();
		}, Style.EMPTY);
		return out;
	}

	@Test
	void wrapSplitsEveryLineIntoStyledPieces() {
		Component red = Component.literal("abcdefgh").withStyle(ChatFormatting.RED);
		Component plain = Component.literal("xy");
		List<Component> out = MagicTooltips.wrap(List.of(red, plain), 3, MagicTooltipsTest::chunks);
		assertEquals(List.of("abc", "def", "gh", "xy"), out.stream().map(Component::getString).toList());
		assertEquals(Style.EMPTY.withColor(ChatFormatting.RED).getColor(), out.get(1).getSiblings().get(0).getStyle().getColor());
	}

	@Test
	void wrapKeepsShortLinesWhole() {
		List<Component> out = MagicTooltips.wrap(MagicTooltips.seal(), 10_000, MagicTooltipsTest::chunks);
		assertEquals(1, out.size());
		assertEquals(MagicTooltips.seal().get(0).getString(), out.get(0).getString());
	}

	@Test
	void sealIsOneLine() {
		List<Component> lines = MagicTooltips.seal();
		assertEquals(1, lines.size());
		assertEquals("item.office.rebinding_seal.tip", key(lines.get(0)));
	}
}
