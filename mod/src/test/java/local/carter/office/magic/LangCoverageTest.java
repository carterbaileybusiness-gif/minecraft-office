package local.carter.office.magic;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.spell.SpellIds;

import net.minecraft.resources.Identifier;

/** Lang and icons are complete in Task 2, so element lanes never touch them: every spell id has a name, a description and an icon. */
class LangCoverageTest {
	private static final List<String> MAGIC_KEYS = List.of(
			"choose", "bind_q", "bind_note", "bind", "back", "level", "home", "no_home_short", "lv", "plus_levels", "hint", "signs",
			"bound", "no_mana", "cooldown", "locked", "no_levels", "fizzle", "level_up", "unlocked", "stronger", "no_home", "home_marked",
			"recalling", "recall_interrupted", "protected", "busy", "undo_done", "undo_help", "wrong_element", "spell_failed",
			"signs_hint", "fizzle_reason", "home_blocked", "choose_hint", "mana", "binding", "bind_failed", "current", "dim.overworld",
			"dim.the_nether", "dim.the_end",
			// Task 7b: Travel to a friend.
			"travel_sent", "travel_need_grimoire", "travel_busy", "travel_cooldown", "travel_offline", "travel_refuses", "travel_pending",
			"travel_ask", "travel_accept", "travel_deny", "travel_accept_hover", "travel_deny_hover", "travel_gone", "travel_blocked",
			"travel_cannot", "travel_accepted", "travel_coming", "travel_denied", "travel_you_denied", "travel_expired", "travel_left",
			"travel_interrupted", "travel_arrived", "travel_now_on", "travel_now_off", "travel_state_on", "travel_state_off", "tab_spells",
			"tab_friends", "friends_title", "friends_none", "friends_page", "friend_ask", "why_off", "why_cooldown", "why_busy",
			"why_waiting", "why_travelling", "travel_title", "travel_blurb", "panel_ready", "panel_cooldown", "panel_waiting",
			"panel_travelling", "panel_busy", "panel_requests_on", "panel_requests_off", "toggle_off", "toggle_on", "friends_hint",
			"travel_too_soon", "travel_friend_full", "why_one",
			// Final review M11: no home inside someone else's claim.
			"home_claimed");

	private static JsonObject lang() throws Exception {
		try (InputStream in = LangCoverageTest.class.getResourceAsStream("/assets/office/lang/en_us.json")) {
			assertNotNull(in, "en_us.json on the classpath");
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	private static void assertText(JsonObject lang, String key) {
		assertTrue(lang.has(key), "missing lang key " + key);
		assertFalse(lang.get(key).getAsString().isBlank(), "blank lang key " + key);
	}

	@Test
	void everySpellHasNameDescriptionAndIcon() throws Exception {
		JsonObject lang = lang();
		for (Identifier id : SpellIds.ALL) {
			assertEquals("office", id.getNamespace());
			assertText(lang, "spell.office." + id.getPath());
			assertText(lang, "spell.office." + id.getPath() + ".desc");
			assertNotNull(LangCoverageTest.class.getResource("/assets/office/textures/gui/spell/" + id.getPath() + ".png"),
					"missing icon for " + id);
		}
	}

	@Test
	void everyElementHasNameMottoAndSigil() throws Exception {
		JsonObject lang = lang();
		for (Element e : Element.values()) {
			assertText(lang, "element.office." + e.key());
			assertText(lang, "element.office." + e.key() + ".motto");
			assertNotNull(LangCoverageTest.class.getResource("/assets/office/textures/gui/magic/sigil_" + e.key() + ".png"),
					"missing sigil for " + e);
		}
	}

	@Test
	void everyUiStringExists() throws Exception {
		JsonObject lang = lang();
		for (String k : MAGIC_KEYS) assertText(lang, "magic.office." + k);
		for (String k : List.of("item.office.grimoire", "item.office.rebinding_seal", "key.office.spell_wheel", "key.category.office.magic")) {
			assertText(lang, k);
		}
	}

	/**
	 * Vanilla lang strings support only {@code %s} (and {@code %n$s}); it rewrites {@code %d} / {@code %.1f} to {@code %s} on load, so
	 * a float argument would print raw ("2.0500000000000003"). No key may use a float format: format numbers in code.
	 */
	@Test
	void noKeyUsesAFloatFormat() throws Exception {
		JsonObject lang = lang();
		java.util.regex.Pattern floatFormat = java.util.regex.Pattern.compile("%(\\d+\\$)?[-+ 0#,(]*\\d*(\\.\\d+)?[fFeEgG]");
		for (String k : lang.keySet()) {
			String v = lang.get(k).getAsString();
			assertFalse(floatFormat.matcher(v).find(), k + " uses a float format: " + v);
		}
		assertTrue(floatFormat.matcher("%.1f").find() && floatFormat.matcher("%2$f").find() && !floatFormat.matcher("%s").find(), "the check works");
	}

	@Test
	void spellIdsAreThirtyOneDistinctAndIndexedByElementAndSlot() {
		assertEquals(31, SpellIds.ALL.size());
		assertEquals(31, new HashSet<>(SpellIds.ALL).size());
		assertEquals(SpellIds.FIRE_BOLT, SpellIds.at(Element.FIRE, Slots.SMALL_A));
		assertEquals(Identifier.fromNamespaceAndPath("office", "black_hole"), SpellIds.at(Element.SHADOW, Slots.ULTIMATE));
		assertEquals(Identifier.fromNamespaceAndPath("office", "recall"), SpellIds.RECALL);
		Set<Identifier> seen = new HashSet<>();
		for (Element e : Element.values()) {
			assertEquals(SpellIds.RECALL, SpellIds.at(e, Slots.RECALL));
			for (int slot = 0; slot < Slots.RECALL; slot++) assertTrue(seen.add(SpellIds.at(e, slot)), "duplicate " + e + "/" + slot);
		}
		assertEquals(30, seen.size());
	}
}
