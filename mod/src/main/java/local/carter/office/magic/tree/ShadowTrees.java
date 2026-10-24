package local.carter.office.magic.tree;

import static local.carter.office.magic.tree.Trees.hit;
import static local.carter.office.magic.tree.Trees.size;
import static local.carter.office.magic.tree.Trees.stat;
import static local.carter.office.magic.tree.Trees.tree;

import java.util.List;

import local.carter.office.magic.core.Slots;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.shadow.BindGeometry;
import local.carter.office.magic.spell.shadow.BlackHole;
import local.carter.office.magic.spell.shadow.BlackHoleGeometry;
import local.carter.office.magic.spell.shadow.GravityWell;
import local.carter.office.magic.spell.shadow.ShadowBind;
import local.carter.office.magic.spell.shadow.ShadowBolt;
import local.carter.office.magic.spell.shadow.ShadowStep;
import local.carter.office.magic.spell.shadow.WellGeometry;
import local.carter.office.magic.tree.SpellTree.Stat.Unit;

import net.minecraft.world.effect.MobEffects;

/** Shadow's five trees (the plan's Shadow table). */
final class ShadowTrees {
	private ShadowTrees() {}

	static List<SpellTree> all() {
		return List.of(
				tree(SpellIds.SHADOW_BOLT, Slots.SMALL_A, "size",
						Fork.of(ShadowBolt.WITHER).effect(MobEffects.WITHER, 80, 1), Fork.of(ShadowBolt.TWIN),
						Fork.of(ShadowBolt.SIPHON).lifesteal(ShadowBolt.SIPHON_SHARE), Fork.of(ShadowBolt.VOID_LANCE),
						hit(ShadowBolt.DAMAGE, Slots.SMALL_A, r -> r.is(ShadowBolt.TWIN) ? ShadowBolt.TWIN_SHARE : 1),
						stat("bolts", Unit.COUNT, (r, lv) -> r.is(ShadowBolt.TWIN) ? 2 : 1),
						size("size", Unit.BLOCKS, ShadowBolt.RADIUS, 99),
						stat("heal", Unit.PERCENT, (r, lv) -> r.is(ShadowBolt.SIPHON) ? 100 * ShadowBolt.SIPHON_SHARE : 0)),
				tree(SpellIds.GRAVITY_WELL, Slots.MEDIUM_A, "radius",
						Fork.of(GravityWell.LONG_WELL), Fork.of(GravityWell.CRUSHING), Fork.of(GravityWell.SINGULARITY), Fork.of(GravityWell.REPULSOR),
						stat("damage", Unit.DAMAGE, (r, lv) -> WellGeometry.HURT * r.mul(Knob.DAMAGE)),
						stat("pulse", Unit.SECONDS, (r, lv) -> (r.is(GravityWell.CRUSHING) ? GravityWell.CRUSHING_EVERY : WellGeometry.HURT_EVERY) / 20.0),
						size("radius", Unit.BLOCKS, WellGeometry.RADIUS, 99),
						stat("life", Unit.SECONDS, (r, lv) -> GravityWell.life(0, r) / 20.0)),
				tree(SpellIds.SHADOW_STEP, Slots.SMALL_B, "range",
						Fork.of(ShadowStep.BACKSTAB), Fork.of(ShadowStep.LONG_SHADOW), Fork.of(ShadowStep.SWAP), Fork.of(ShadowStep.PHANTOM),
						size("range", Unit.BLOCKS, ShadowStep.RANGE, 99),
						stat("hidden", Unit.SECONDS, (r, lv) -> ShadowStep.INVISIBLE_TICKS / 20.0 * (r.is(ShadowStep.LONG_SHADOW) ? 3 : 1)),
						stat("damage", Unit.DAMAGE, (r, lv) -> r.is(ShadowStep.BACKSTAB) ? Trees.damage(ShadowStep.BACKSTAB_DAMAGE, Slots.SMALL_B, r, lv) : 0)),
				tree(SpellIds.SHADOW_BIND, Slots.MEDIUM_B, "root",
						Fork.of(ShadowBind.CHOKING), Fork.of(ShadowBind.SPREADING), Fork.of(ShadowBind.LEECH), Fork.of(ShadowBind.DREAD),
						hit(BindGeometry.DAMAGE, Slots.MEDIUM_B),
						stat("root", Unit.SECONDS, (r, lv) -> ShadowBind.life(0, r) / 20.0),
						stat("foes", Unit.COUNT, (r, lv) -> ShadowBind.foes(0, r))),
				tree(SpellIds.BLACK_HOLE, Slots.ULTIMATE, "pull",
						Fork.of(BlackHole.RAVENOUS), Fork.of(BlackHole.HORIZON), Fork.of(BlackHole.SUPERNOVA), Fork.of(BlackHole.DARK_RAIN),
						hit(BlackHoleGeometry.BLAST, Slots.ULTIMATE, r -> r.is(BlackHole.SUPERNOVA) ? 2 : 1),
						stat("pull", Unit.BLOCKS, (r, lv) -> BlackHole.pullRadius(r)),
						stat("blast", Unit.BLOCKS, (r, lv) -> BlackHole.blastRadius(r)),
						stat("pulse", Unit.SECONDS, (r, lv) -> (r.is(BlackHole.RAVENOUS) ? BlackHole.RAVENOUS_EVERY : BlackHoleGeometry.HURT_EVERY) / 20.0),
						stat("bolts", Unit.COUNT, (r, lv) -> r.is(BlackHole.DARK_RAIN) ? BlackHole.RAIN_BOLTS : 0)));
	}
}
