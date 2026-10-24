package local.carter.office.magic.tree;

import static local.carter.office.magic.tree.Trees.hit;
import static local.carter.office.magic.tree.Trees.size;
import static local.carter.office.magic.tree.Trees.stat;
import static local.carter.office.magic.tree.Trees.tree;

import java.util.List;

import local.carter.office.magic.core.Slots;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.lightning.Blink;
import local.carter.office.magic.spell.lightning.ChainHops;
import local.carter.office.magic.spell.lightning.ChainLightning;
import local.carter.office.magic.spell.lightning.ChidoriStrike;
import local.carter.office.magic.spell.lightning.Spark;
import local.carter.office.magic.spell.lightning.ThunderSpear;
import local.carter.office.magic.tree.SpellTree.Stat.Unit;

import net.minecraft.world.effect.MobEffects;

/** Lightning's five trees (the plan's Lightning table). */
final class LightningTrees {
	private LightningTrees() {}

	static List<SpellTree> all() {
		return List.of(
				tree(SpellIds.SPARK, Slots.SMALL_A, "range",
						Fork.of(Spark.FORKED).add(Knob.COUNT, 1),
						Fork.of(Spark.STATIC).effect(MobEffects.GLOWING, 120, 0).effect(MobEffects.SLOWNESS, 40, 0),
						Fork.of(Spark.OVERCHARGE), Fork.of(Spark.STUN).effect(MobEffects.SLOWNESS, 30, 3),
						hit(Spark.DAMAGE, Slots.SMALL_A),
						size("range", Unit.BLOCKS, Spark.RANGE, 99),
						stat("arcs", Unit.COUNT, (r, lv) -> Spark.arcs(0, r)),
						stat("arc_damage", Unit.PERCENT, (r, lv) -> 100 * Spark.arcShare(r)),
						stat("arc_range", Unit.BLOCKS, (r, lv) -> Spark.arcRange(0, r))),
				tree(SpellIds.CHAIN_LIGHTNING, Slots.MEDIUM_A, "hop",
						Fork.of(ChainLightning.LONG_CHAIN).add(Knob.COUNT, 2), Fork.of(ChainLightning.CONDUCTOR),
						Fork.of(ChainLightning.THUNDERSTORM), Fork.of(ChainLightning.OVERLOAD),
						hit(ChainHops.DAMAGE, Slots.MEDIUM_A),
						stat("hops", Unit.COUNT, (r, lv) -> ChainLightning.hops(0, r)),
						stat("hop", Unit.BLOCKS, (r, lv) -> ChainLightning.hopRange(r)),
						stat("fade", Unit.PERCENT, (r, lv) -> 100 * (1 - ChainLightning.falloff(r)))),
				tree(SpellIds.BLINK, Slots.SMALL_B, "range",
						Fork.of(Blink.STATIC_STEP), Fork.of(Blink.QUICK).mul(Knob.COOLDOWN, 0.5F), Fork.of(Blink.THUNDERCLAP), Fork.of(Blink.SURGE),
						size("range", Unit.BLOCKS, Blink.BASE_RANGE, 99),
						stat("damage", Unit.DAMAGE, (r, lv) -> r.is(Blink.STATIC_STEP) ? Trees.damage(Blink.STATIC_DAMAGE, Slots.SMALL_B, r, lv) : 0)),
				tree(SpellIds.THUNDER_SPEAR, Slots.MEDIUM_B, "blast",
						Fork.of(ThunderSpear.JAVELIN), Fork.of(ThunderSpear.PARALYZE), Fork.of(ThunderSpear.STORM), Fork.of(ThunderSpear.CHAIN),
						hit(ThunderSpear.DAMAGE, Slots.MEDIUM_B),
						stat("blast", Unit.BLOCKS, (r, lv) -> ThunderSpear.blast(0, r)),
						stat("speed", Unit.PERCENT, (r, lv) -> r.is(ThunderSpear.JAVELIN) ? 100 * ThunderSpear.JAVELIN_SPEED : 100),
						stat("slow", Unit.SECONDS, (r, lv) -> (r.is(ThunderSpear.PARALYZE) ? ThunderSpear.PARALYZE_TICKS : ThunderSpear.SLOW_TICKS) / 20.0),
						stat("bolts", Unit.COUNT, (r, lv) -> r.is(ThunderSpear.STORM) ? ThunderSpear.STORM_BOLTS : 0),
						stat("arcs", Unit.COUNT, (r, lv) -> r.is(ThunderSpear.CHAIN) ? ThunderSpear.CHAIN_HOPS : 0)),
				tree(SpellIds.CHIDORI_STRIKE, Slots.ULTIMATE, "reach",
						Fork.of(ChidoriStrike.RAIKIRI), Fork.of(ChidoriStrike.CHIRPING), Fork.of(ChidoriStrike.KIRIN), Fork.of(ChidoriStrike.STORM_TRAIL),
						hit(ChidoriStrike.DAMAGE, Slots.ULTIMATE, r -> r.is(ChidoriStrike.RAIKIRI) ? ChidoriStrike.RAIKIRI_BONUS : 1),
						stat("reach", Unit.BLOCKS, (r, lv) -> ChidoriStrike.reach(r)),
						stat("bolts", Unit.COUNT, (r, lv) -> r.is(ChidoriStrike.KIRIN) ? ChidoriStrike.KIRIN_BOLTS : 0)));
	}
}
