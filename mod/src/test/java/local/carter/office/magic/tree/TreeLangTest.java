package local.carter.office.magic.tree;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import local.carter.office.hologram.StarterKit;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * Every Skill tree string is in {@code en_us.json}: each fork's name and one line (short enough for two lines of the narrowest
 * panel), each stat, each refusal and the screen's own strings; and the handbook has its Skill tree page, last.
 */
class TreeLangTest {
	/** A fork's line fits two lines of the narrowest panel at the vanilla size. */
	static final int MAX_DESC = 40;

	@BeforeAll
	static void boot() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static JsonObject lang() throws Exception {
		try (InputStream in = TreeLangTest.class.getResourceAsStream("/assets/office/lang/en_us.json")) {
			assertNotNull(in);
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	private static String text(JsonObject lang, String key) {
		assertTrue(lang.has(key), "missing lang key " + key);
		String v = lang.get(key).getAsString();
		assertFalse(v.isBlank(), "blank lang key " + key);
		return v;
	}

	@Test
	void everyForkAndStatHasText() throws Exception {
		JsonObject lang = lang();
		for (SpellTree t : Trees.all()) {
			for (Fork f : List.of(t.three().get(0), t.three().get(1), t.five().get(0), t.five().get(1))) {
				String key = "fork.office." + t.spell().getPath() + "." + f.name();
				text(lang, key);
				String desc = text(lang, key + ".desc");
				assertTrue(desc.length() <= MAX_DESC, key + ".desc is " + desc.length() + " long: " + desc);
			}
			for (SpellTree.Stat s : t.stats()) text(lang, "stat.office." + s.key());
		}
		text(lang, "stat.office.mana");
		text(lang, "stat.office.cooldown");
	}

	@Test
	void everyRefusalAndScreenStringHasText() throws Exception {
		JsonObject lang = lang();
		for (TreeService.Outcome o : TreeService.Outcome.values()) text(lang, "tree.office.outcome." + o.name().toLowerCase(java.util.Locale.ROOT));
		for (String k : List.of("rank_up", "choose", "rank_n", "unranked", "sub_bump", "sub_fork", "sub_owned", "sub_picked", "sub_other",
				"sub_repick", "grows", "unlock_at", "first", "owned", "picked", "picked_mark", "need_level", "need_levels", "need_items", "free",
				"improve", "have", "level_word", "levels_word", "milestone", "milestone_picked", "legend_owned", "legend_owned_short",
				"legend_owned_tip", "legend_ready", "legend_ready_short", "legend_ready_tip", "legend_short", "legend_short_short",
				"legend_short_tip", "legend_locked", "legend_locked_short", "legend_locked_tip",
 "minus_levels", "minus_items", "repick", "repick_on", "repick_first", "repick_need", "repick_button",
				"repick_warn", "repick_free", "coach", "tip_ready", "lv", "unit_s", "unit_pct", "unit_deg")) {
			text(lang, "tree.office." + k);
		}
		text(lang, "magic.office.tab_tree");
		text(lang, "magic.office.tree_hint");
		text(lang, "magic.office.material_tip");
	}

	@Test
	void theHandbookEndsWithTheSkillTree() {
		List<String> titles = StarterKit.pageTitles();
		assertEquals("Skill tree", titles.getLast());
		assertTrue(StarterKit.pageBody("Skill tree").contains("Rebinding Seal"));
	}
}
