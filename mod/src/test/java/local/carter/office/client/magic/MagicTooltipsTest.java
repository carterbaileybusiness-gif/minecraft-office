package local.carter.office.client.magic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import local.carter.office.magic.core.Element;

import net.minecraft.network.chat.Component;
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

	@Test
	void sealIsOneLine() {
		List<Component> lines = MagicTooltips.seal();
		assertEquals(1, lines.size());
		assertEquals("item.office.rebinding_seal.tip", key(lines.get(0)));
	}
}
