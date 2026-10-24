package local.carter.office.magic;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import local.carter.office.hologram.StarterKit;
import local.carter.office.magic.core.ChargeMath;

/**
 * The "How to cast" tab is the handbook's Casting page told as five numbered steps (plus hand signs, the Skill tree and
 * Recall on the right page). The handbook stays the source: every step carries a fact of that page in the handbook's own
 * words, so the two can't drift apart; "about 2× at full" is the charge maths' own number.
 */
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

	/** {@code fact} is on handbook page {@code page} and in the tab's {@code key}. */
	private static void same(JsonObject lang, String page, String fact, String key) {
		assertTrue(StarterKit.pageBody(page).contains(fact), "the handbook's " + page + " page says \"" + fact + "\"");
		assertTrue(text(lang, key).contains(fact), key + " says \"" + fact + "\": " + text(lang, key));
	}

	@Test
	void theStepsAreTheHandbookCastingPage() throws Exception {
		JsonObject lang = lang();
		assertTrue(StarterKit.pageBody("Casting").contains("Grimoire in your OFF hand, main hand empty."));
		String hand = text(lang, "magic.office.howto_step_offhand");
		assertTrue(hand.contains("Grimoire in your off hand (%s), main hand empty."), hand + ": the swap-hands key fills %s");
		same(lang, "Casting", "Tap right-click: quick cast.", "magic.office.howto_step_tap");
		same(lang, "Casting", "Hold it and the circle grows; release for a stronger cast", "magic.office.howto_step_hold");
		assertTrue(StarterKit.pageBody("Casting").contains("Shift + scroll or hold R (wheel) picks spells."));
		assertEquals("Shift + scroll or hold %s (wheel): pick a spell.", text(lang, "magic.office.howto_step_pick"),
				"the wheel key (R unless rebound) fills %s");
		same(lang, "Casting", "Holding the Grimoire shows mana on the XP bar.", "magic.office.howto_step_mana");
		assertEquals("Casting", text(lang, "magic.office.howto_casting"), "the left page is titled as the handbook page");
	}

	@Test
	void aFullChargeIsAboutTwiceATap() throws Exception {
		assertTrue(text(lang(), "magic.office.howto_step_hold").contains("(about 2× at full)"));
		assertEquals(2f, ChargeMath.power(1f, 1) / ChargeMath.power(0f, 1), 1e-6f, "damage");
		assertEquals(2f, ChargeMath.size(1f) / ChargeMath.size(0f), 1e-6f, "size");
	}

	@Test
	void signsLineIsTheHandbookSpellsSentence() throws Exception {
		String sentence = StarterKit.pageBody("Spells").split("\n")[1];
		assertEquals("The ultimate asks for hand signs: press the arrows shown before time runs out.", sentence);
		assertEquals(sentence, text(lang(), "magic.office.howto_signs"));
		assertTrue(text(lang(), "magic.office.signs_hint").endsWith("Esc gives up"));
	}

	@Test
	void treeAndRecallLinesMatchTheirHandbookPages() throws Exception {
		JsonObject lang = lang();
		assertTrue(StarterKit.pageBody("Skill tree").startsWith("Grimoire → Skill tree: rank your 5 spells"));
		assertTrue(text(lang, "magic.office.howto_tree").contains("Skill tree"));
		assertEquals("Skill tree", text(lang, "magic.office.tab_tree"), "the tab the line names");
		assertTrue(StarterKit.pageBody("Recall").contains("Shift + cast Recall to mark home"));
		assertTrue(StarterKit.pageBody("Recall").contains("Cast Recall to channel for 3 s and go home."));
		assertTrue(text(lang, "magic.office.howto_recall").startsWith("Shift + cast Recall marks home."));
	}

	@Test
	void tabAndHintKeysExist() throws Exception {
		JsonObject lang = lang();
		assertEquals("How to cast", text(lang, "magic.office.tab_howto"));
		assertEquals("Put the Grimoire in your off hand (%s) with an empty main hand to cast.", text(lang, "magic.office.hold_hint"));
		for (String k : new String[] {"howto_signs_title", "howto_more"}) text(lang, "magic.office." + k);
		assertFalse(lang.has("magic.office.howto_cast"), "the one-paragraph version is gone (the steps replaced it)");
	}
}
