package local.carter.office.magic;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import local.carter.office.hologram.StarterKit;

/** The "How to cast" tab's text is the handbook's own (the handbook stays the source); only the lang copy is checked here. */
class HowToCastTextTest {
	private static JsonObject lang() throws Exception {
		try (InputStream in = HowToCastTextTest.class.getResourceAsStream("/assets/office/lang/en_us.json")) {
			assertNotNull(in, "en_us.json on the classpath");
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	private static String text(JsonObject lang, String key) {
		assertTrue(lang.has(key), "missing lang key " + key);
		assertFalse(lang.get(key).getAsString().isBlank(), "blank lang key " + key);
		return lang.get(key).getAsString();
	}

	@Test
	void howToCastIsTheHandbookCastingPage() throws Exception {
		assertEquals(StarterKit.pageBody("Casting").replace('\n', ' '), text(lang(), "magic.office.howto_cast"));
	}

	@Test
	void signsLineIsTheHandbookSpellsSentence() throws Exception {
		String sentence = StarterKit.pageBody("Spells").split("\n")[1];
		assertEquals("The ultimate asks for hand signs: press the arrows shown before time runs out.", sentence);
		assertEquals(sentence, text(lang(), "magic.office.howto_signs"));
	}

	@Test
	void tabAndHintKeysExist() throws Exception {
		JsonObject lang = lang();
		assertEquals("How to cast", text(lang, "magic.office.tab_howto"));
		assertEquals("Put the Grimoire in your off hand (%s) with an empty main hand to cast.", text(lang, "magic.office.hold_hint"));
	}
}
