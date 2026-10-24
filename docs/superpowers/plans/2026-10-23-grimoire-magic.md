# Grimoire Magic Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Wizards with our own Grimoire magic. One vanilla-crafted book in the off hand; an element chosen on first
open; a 6-slice spell wheel; tap or hold-to-charge casting from growing vertical summoning circles; hand signs for ultimates
that permanently reshape terrain (claim-safe, undoable); and Recall home. Monday floor first, all 30 spells by Sunday.

**Architecture:** Server-authoritative inside the existing `office` mod. Casting rides vanilla item use: the grimoire is
a use-duration item (like a bow), so the server measures charge in `releaseUsing`, vanilla keeps main-hand
block/entity interactions first, and remote clients see the charge without extra packets. Each spell is one class behind
a small SPI (`Spell` + client `SpellFx`); the server sends **one `CastFx` payload per cast** and clients build every visual
from it, using the same pure geometry helpers the server uses. Pure rules live in `magic/core` with no Minecraft imports.

**Tech Stack:** Minecraft 26.3, Fabric Loader 0.20.1, Fabric API 0.165.0+26.3 (data-attachment sync, `HudElementRegistry`,
`LevelRenderEvents`, `KeyMappingHelper`, `ClientHotbarScrollEvents`), Loom 1.19.3 (unobfuscated, Mojang names), Java 25,
JUnit 5, Fabric GameTest + client GameTest, Open Parties and Claims API (compile-only, optional), packwiz.

**Spec:** `docs/superpowers/specs/2026-10-23-grimoire-magic-design.md` (section 0 overrides the rest). Context:
`docs/superpowers/specs/2026-10-23-minecraft-office-server-design.md` §5.9 (in-world monitor input, merged on feat/v1 as
`client/pointer/*`), research `.superpowers/sdd/2026-10-23-minecraft-office-v1/magic-research.md`, pre-flight
`.superpowers/sdd/2026-10-23-grimoire-magic/preflight.md` (its rulings are folded in below).

## Global Constraints

Spec (verbatim values):
- Recipe: **book + feather + iron ingot** → Grimoire (shapeless). Re-binding: **nether star + book** → Rebinding Seal.
- Casting: grimoire in the **off hand**, **empty main hand**: **tap right-click** = quick cast of the selected spell,
  **hold** = summoning circle grows (charge), **release** fires a stronger version. Tool/block/any item in the main hand →
  right-click behaves normally (no magic). Select with **Shift + scroll** or the **R** wheel. **No V cast key.**
- Elements: Fire, Water, Wind, Lightning, Earth, Shadow. Chosen on first open; permanent unless a Rebinding Seal is used.
- Per element 5 spells: 2 small, 2 medium, 1 ultimate (hand signs). All from your element, unlocked by grimoire level; no
  tomes; no cross-element spells except **Recall** (everyone, from level 1).
- World effects: **small = no block changes** (not even temporary); **medium = temporary or minor block changes, radius ≤ 6**;
  **ultimate = permanent, radius ≤ 12**. Never block entities, bedrock or obsidian, never inside another player's claim
  (Open Parties and Claims; fallback: nothing within **32 blocks** of any hologram emitter or another player's bed/home), never
  in spawn protection. Op command **`/magic undo <player> [n]`**; edits logged to disk.
- Recall: one home per player, any dimension. **Mark Home = Shift + cast Recall**. Recall = **3 s channel** with a circle
  under you, **cancelled by taking damage**, **60 s cooldown**, **no mana**. Waystones and JEI are removed from the pack (Carter, 2026-10-24).
- Hand signs: **4–6 arrow prompts**, typed with arrow keys or WASD while the prompt is open (movement paused); miss →
  fizzle with a sound, mana partly spent. Ultimates also cost **XP levels**.
- Input precedence: aiming at **any** hologram → clicks **and Shift + scroll** belong to the monitor; R still opens the wheel;
  **typing mode blocks all magic keys**; right-click casting only when not aiming at a hologram.
- Look: vertical circles (rings appearing one by one, runes, element colour, additive glow), screen shake and hit-stop on
  big hits; screens polished, animated, **no scrolling, readable at any GUI scale**.
- Performance: **one effect packet per cast**, particles client-side, block changes batched; smooth on the 8 GB M1 with 5 players.
- Reuse with attribution: **Wildercord (MIT)** circle rendering, glow pipeline, screen shake. Wheel and grimoire screens are ours.
  Iron's Spells, Ars Nouveau, Wizards and Spell Engine: ideas only, never code.
- Pack: remove Wizards, Spell Engine, Spell Power and every dependency only they need (Better Combat is not in the pack).

Rulings (deviations Carter's controller accepted, 2026-10-23):
- Wheel has **6 slices**: 5 element spells plus Recall (slice index = slot). Locked or not-yet-registered slots show greyed with a padlock.
- Charging slows the player to **0.6×** and blocks sprint (`UseEffects(false, false, 0.6f)`), taps included.
- Small spells may do light damage, status effects, dashes and teleports. "Particles + knockback only" is read as *no block changes*.
- Mana bar takes the XP bar's place only while a grimoire is held. The **XP level number stays visible** at the bar's right end.
- Earth Spikes are visual-only blocks (small spell, so no block changes).
- Spells never hurt or push players (any), tamed pets, villagers, wandering traders, iron golems, armor stands, display entities,
  hanging entities or vehicles. Push spells also move items and XP orbs.
- Edit log keeps the newest **20 casts** per player (stated in `/magic undo` help). The fallback claim rule also protects other
  online players' explicit respawn points. Falling-debris landings are not undoable.
- **Ultimates cost their base mana** (no charge scaling) and always cast at charge 1.
- A spell that manages its own cooldown (Recall) is never overwritten by `CastService`, and `CastService` never lowers an existing `readyAt`.
- The player's home has **one store**: `MagicData.home` (the attachment). The fallback claim rule reads homes of online players only.
- Terrain radius caps apply **per edit batch**. A travelling spell opens one batch per segment or hit, each with its own origin
  (medium ≤ 6, ultimate ≤ 12). Block budgets apply **per cast**, summed over its batches.

Repo rules:
- Packages `local.carter.office.magic` (main) and `local.carter.office.client.magic` (client); mod id `office`.
- Heavy commands only through `scripts/heavy.sh` (single-flight lock). Gradle `--no-daemon`. Never beside the game; never `pkill java`;
  no osascript automation. Client GameTests run in CI only (Linux/xvfb). Locally run `build test runGametest`.
- 26.3 API: SDL3 mouse buttons (`InputConstants.MOUSE_BUTTON_RIGHT` = 3); GUI draws in `extractRenderState(GuiGraphicsExtractor …)`;
  `mc.gui.screen()`; render-state renderers and `LevelRenderEvents`; no raw GL. Never guess a signature: check `genSources`.

Builder numbers (spec §7 left them to us; one source each):
- Levels 1–10. `xpToNext(level) = 20 × level`. Tier (looks) = 0 for L1–2, 1 for L3–4, 2 for L5–6, 3 for L7+.
- Slots: 0 small A (unlocks L1), 1 medium A (L2), 2 small B (L3), 3 medium B (L5), 4 ultimate (L7), 5 Recall (L1).
- `Slots.baseCost(slot)` is the only cost table (mana / cooldown ticks / grimoire xp / XP levels). 0 = 8/10/2/0, 1 = 25/60/5/0,
  2 = 12/40/2/0, 3 = 30/100/5/0, 4 = 60/600/20/3, 5 = 0/1200/0/0. `SpellCost.forSlot` wraps it.
- Mana: `max = 100 + 10 × (level − 1)`. +1 every `max(2, 6 − level / 2)` ticks (integer division). Regen pauses 20 ticks after a cast.
- Charge: a tap is a release before **8** ticks. `charge = clamp((ticks − 8) / 32, 0, 1)` (full at 40 ticks, 2 s). Rings shown
  `1 + floor(charge × 4)` (1–5). Non-ultimate mana cost is `round(base × (1 + 0.5 × charge))`, with charge lowered to what you can afford.
- Power: `(1 + charge) × (1 + 0.05 × (level − 1))`. Damage values below are "× P".
- Hand signs: length 6 at L7, 5 at L8, 4 at L9–10. Limit `20 + 12 × length` ticks, plus 10 ticks server grace. No two equal arrows in
  a row. Fizzle: `ceil(base / 2)` mana spent (30), 100-tick cooldown, no XP levels spent.
- Budgets per cast: ≤ 64 entities affected, ≤ 48 falling blocks. Medium edits ≤ 256 blocks, ultimate ≤ 1500. Global applier
  ≤ 256 block changes per tick. ≤ 32 lingering effects server-wide (projectiles have their own cap of 64). Client fx ≤ 400 particles
  (ultimate ≤ 1500) and ≤ 128 ghost blocks.
- `CastFx` range: 64 blocks (128 for ultimates).
- Element colours (ARGB), defined once in `Element.java`; the texture script parses them: Fire `FFFF6A1A`, Water `FF2F8CFF`,
  Wind `FF9CF2C8`, Lightning `FFFFE94A`, Earth `FFB0814A`, Shadow `FF8A3CFF`. Recall gold `FFFFD27A`; circle core white.

## Review Focus

1. Empty main hand, grimoire in the off hand, right-click on a chest, door, bed, lever or villager → the vanilla interaction
   happens and no spell is cast. Test in Task 8 (client gametest `emptyHandChestStillOpens`).
2. Sword, food, block or shield in the main hand with the grimoire in the off hand → eat, place and block work as normal and
   no charge starts. Switching to a tool mid-charge cancels with no mana spent. Tests in Task 4.
3. `/magic undo` after someone rebuilt part of a crater → only untouched blocks are restored and the new blocks stay. Test in Task 5.
4. Server stops or crashes while temporary blocks exist (Water Prison, Earth Wall) → they are gone after the next start and
   nobody is left encased. Tests in Task 5.
5. Death, disconnect or dimension change during a charge, a Recall channel or a hand-sign prompt → no cast, no mana lost, and
   the channel and challenge are discarded. Tests in Tasks 4, 6 and 7.
6. Mining, silk-touching, blowing up, piston-pushing or enderman-grabbing a temporary block (Earth Wall) → no items, no XP,
   no moved real block; a broken one gets its original block back at once. Tests in `TempBlockBreakGameTests`.

---

## File Structure

```
mod/src/main/java/local/carter/office/magic/
  MagicMod.java                       # init: items, attachments, payloads, receivers, ticks, commands, element spells
  core/ (pure Java, no Minecraft imports)
    Element.java  Dir.java  Slots.java  LevelCurve.java  ManaMath.java  ChargeMath.java  HandSigns.java
    WheelMath.java  ScrollSelect.java  Shapes.java (Cell)  TerrainRules.java  GrimoireTier.java
  item/  MagicItems.java  GrimoireItem.java  RebindingSealItem.java  GrimoireLook.java
  data/  MagicData.java  MagicAttachments.java
  net/   MagicPayloads.java  FxEvent.java  MagicSender.java  MagicServer.java
  cast/  CastService.java  SignChallenges.java  RecallSpell.java  ElementChoice.java
  spell/ SpellIds.java  Spell.java  SpellCost.java  CastContext.java  CastOutcome.java  SpellRegistry.java  SpellKit.java
         Targets.java  TargetFilter.java  Hurt.java  Motion.java  Projectiles.java  ActiveEffects.java
         fire/ water/ wind/ lightning/ earth/ shadow/   # <Element>Spells.java (M1 stub) + spell classes + pure *Geometry helpers (lanes)
  terrain/ TerrainEdits.java  EditBatch.java  EditTier.java  TempBlocks.java  NoTerrain.java      # interfaces + refusing impl (Task 3)
           WorldTerrainEdits.java  WorldEditBatch.java  TempBlockData.java  EditLog.java
           ClaimCheck.java  ClaimLookup.java  OpacClaims.java  FallbackClaims.java                # Task 5
  command/ MagicCommand.java
mod/src/client/java/local/carter/office/client/magic/
  MagicClient.java  MagicInputGate.java  MagicKeys.java  SpellWheel.java  SpellWheelHud.java  WheelLayout.java
  screen/ ElementChoiceScreen.java  ChoiceLayout.java  GrimoireScreen.java  GrimoireLayout.java  HandSignScreen.java  SignLayout.java
  hud/    ManaBarHud.java  ManaBarLayout.java
  fx/     FxClient.java  SpellFx.java  SpellFxRegistry.java  FxKit.java  DefaultFx.java  RecallFx.java  MagicCircleRenderer.java
          LightPipeline.java  ChargeCircles.java  GhostBlocks.java  Shake.java  GrimoireParticles.java
          fire/ water/ wind/ lightning/ earth/ shadow/   # <Element>Fx.java (M1 stub) + one SpellFx per spell (lanes)
mod/src/client/java/local/carter/office/mixin/client/
  CameraShakeMixin.java  MouseHandlerWheelMixin.java  (modify) MinecraftUseItemMixin.java
mod/src/client/java/local/carter/office/client/pointer/PointerClient.java   # (modify) make inFront(...) public
mod/src/main/resources/
  assets/office/items/{grimoire,rebinding_seal}.json  assets/office/models/item/grimoire_t{0..3}.json, rebinding_seal.json
  assets/office/textures/item/  assets/office/textures/gui/magic/  assets/office/textures/gui/spell/  assets/office/textures/misc/magic/
  assets/office/lang/en_us.json (modify, complete in Task 2)
  data/office/recipe/{grimoire,rebinding_seal}.json  data/office/advancement/recipes/misc/{grimoire,rebinding_seal}.json
  data/office/tags/block/magic_immune.json
mod/src/gametest/java/local/carter/office/magic/
  MagicTestKit.java  MagicCoreGameTests.java  CastGameTests.java  TerrainGameTests.java  HandSignGameTests.java
  RecallGameTests.java  BalanceGameTests.java  {Fire,Water,Wind,Lightning,Earth,Shadow}GameTests.java
mod/src/gametest/java/local/carter/office/client/MagicClientGameTests.java
mod/src/gametest/resources/data/office-test/structure/magic_arena.nbt
mod/src/test/java/local/carter/office/magic/core/*Test.java
mod/src/test/java/local/carter/office/magic/{MagicDataCodecTest,PayloadCapsTest,SpellRegistryTest,EditLogTest,OpacClaimsTest,LangCoverageTest}.java
mod/src/test/java/local/carter/office/client/magic/*Test.java   mod/src/test/java/local/carter/office/magic/spell/<element>/*Test.java
tools/make_magic_textures.py  tools/make_magic_arena.py
scripts/check-pack-deps.py  THIRD_PARTY_NOTICES.md
pack/mods/*.pw.toml (remove), pack/index.toml, pack/pack.toml, server/deploy.md, docs/friends/SETUP.md
```

**Shared-file rule.** Phase M1 creates every shared file in full: lang for all 31 spells and every UI string, all textures,
`SpellIds`, element registration stubs, every gametest entrypoint, and the complete `MagicTestKit`. Element lanes touch
**only** their element's `spell/<element>/`, `fx/<element>/`, `<Element>GameTests.java` and `test/.../spell/<element>/`.

---

## Phase M1 — shared core (sequential; Task 11 may run any time)

### Task 1: Pure magic rules

**Files:**
- Create: `mod/src/main/java/local/carter/office/magic/core/{Element,Dir,Slots,LevelCurve,ManaMath,ChargeMath,HandSigns,WheelMath,ScrollSelect,Shapes,TerrainRules,GrimoireTier}.java`
- Test: `mod/src/test/java/local/carter/office/magic/core/{ElementTest,SlotsTest,LevelCurveTest,ManaMathTest,ChargeMathTest,HandSignsTest,WheelMathTest,ScrollSelectTest,ShapesTest,TerrainRulesTest,GrimoireTierTest}.java`

**Interfaces:**
- Produces:
  - `enum Element { FIRE, WATER, WIND, LIGHTNING, EARTH, SHADOW; String key(); int argb(); static Optional<Element> byKey(String) }`.
    The colour literals sit in one `// COLOURS` block that `tools/make_magic_textures.py` parses.
  - `enum Dir { UP, DOWN, LEFT, RIGHT }`
  - `Slots`: `SMALL_A=0, MEDIUM_A=1, SMALL_B=2, MEDIUM_B=3, ULTIMATE=4, RECALL=5, WHEEL_SLICES=6`; `int unlockLevel(int slot)`;
    `int[] baseCost(int slot)` → `{mana, cooldownTicks, xp, xpLevels}`.
  - `LevelCurve`: `MAX_LEVEL=10`; `int xpToNext(int level)`; `record Progress(int level, int xp, int levelsGained)`;
    `Progress add(int level, int xp, int gained)` (multi-level; xp pinned at 0 at MAX_LEVEL).
  - `ManaMath`: `int max(int level)`; `int regenInterval(int level)`; `REGEN_PAUSE_TICKS=20`.
  - `ChargeMath`: `TAP_TICKS=8, FULL_TICKS=40`; `float charge(int ticksHeld)`; `int rings(int ticksHeld)`;
    `int cost(int base, float charge, boolean ultimate)` (ultimate → base); `float affordableCharge(int mana, int base, float charge)`
    (largest multiple of 1/32 ≤ charge whose cost fits, or −1 if even base doesn't fit); `float power(float charge, int level)`.
  - `HandSigns`: `int length(int level)`; `int limitTicks(int length)`; `GRACE_TICKS=10`; `Dir[] sequence(long seed, int length)`
    (`java.util.Random(seed)`, no immediate repeats); `boolean matches(Dir[] expected, Dir[] answer)`; `int fizzleMana(int base)` = `ceil(base/2)`.
  - `WheelMath`: `DEAD_ZONE=14` (no slice inside), `SELECT_MIN=4` (hover starts only after the accumulated vector has moved > 4 px
    since the wheel opened), `MAX_RADIUS=60`; `int sliceAt(double dx, double dy, int slices)` (−1 inside the dead zone; slice 0 centred
    straight up, clockwise, screen coords y down); `double[] clamp(double dx, double dy)`.
  - `ScrollSelect`: `<T> T next(List<T> unlocked, T current, double scrollDy)` (dy < 0 → next, dy > 0 → previous, wraps; unknown current → first).
  - `Shapes`: `record Cell(int x, int y, int z)`; `List<Cell> sphere(double r)`, `bowl(double r, double depthFactor)`, `cone(double baseR, int h)`
    (upright, +y), `line(double dx, double dz, int length)` (ground line along a horizontal direction). Cells are sorted by distance from
    the origin and never lie beyond `r`.
  - `TerrainRules`: `enum Verdict { ALLOW, BLOCK_ENTITY, IMMUNE, CLAIMED, SPAWN_PROTECTED, OUTSIDE_BORDER, BEYOND_RADIUS, OVER_BUDGET }`;
    `Verdict decide(boolean blockEntity, boolean immune, boolean claimed, boolean spawnProtected, boolean insideBorder,
    double distance, double radiusCap, int used, int budget)` (checked in that order).
  - `GrimoireTier`: `int of(int level)`.

- [ ] **Step 1: Write the failing tests**
  - `ElementTest`: `byKey("fire")==Optional.of(FIRE)`, `byKey("nope")` empty; `FIRE.argb()==0xFFFF6A1A`; six distinct keys.
  - `SlotsTest`: `unlockLevel` = 1,2,3,5,7,1 for slots 0..5; `baseCost(4)` = `{60,600,20,3}`; `baseCost(5)` = `{0,1200,0,0}`.
  - `LevelCurveTest`: `xpToNext(1)==20`, `xpToNext(9)==180`; `add(1,15,10)` → `(2,5,1)`; `add(1,0,200)` → `(5,0,4)`; `add(10,0,999)` → `(10,0,0)`.
  - `ManaMathTest`: `max(1)==100`, `max(10)==190`; `regenInterval` = 6,5,5,4,4,3,3,2,2,2 for levels 1..10.
  - `ChargeMathTest`: `charge(7)==0`, `charge(8)==0`, `charge(24)==0.5f`, `charge(40)==1`, `charge(400)==1`; `rings(0)==1`, `rings(40)==5`;
    `cost(25,1,false)==38`, `cost(8,0,false)==8`, `cost(60,1,true)==60`; `affordableCharge(30,25,1)==0.4375f`; `affordableCharge(7,8,0)==-1`;
    `power(1,10)` = 2.9 within 1e-6.
  - `HandSignsTest`: lengths 6/5/4/4 for levels 7/8/9/10; `limitTicks(6)==92`; `sequence(42,6)` is the same on a second call, has 6
    entries and no two equal neighbours (checked over 1000 seeds); `matches` is false on a wrong order or length; `fizzleMana(60)==30`.
  - `WheelMathTest`: (0,−30)→0, (26,−15)→1, (26,15)→2, (0,30)→3, (−26,15)→4, (−26,−15)→5; `sliceAt(5,5,6)==-1`; `clamp(300,0)` → `{60,0}`.
  - `ScrollSelectTest`: list `[a,b,c]`: `next(l,"c",-1)=="a"`, `next(l,"a",1)=="c"`, `next(l,"z",-1)=="a"`.
  - `ShapesTest`: `sphere(1)` has 7 cells; every cell of `sphere(12)` has distance ≤ 12; `bowl(6,0.6)` has no cell with y > 0 or y < −4;
    `cone(3,8)` has no cell with y < 0 or y ≥ 8, and the base layer is the widest; `line(0,1,8)` = (0,0,0)…(0,0,7); the first cell of every shape is the origin.
  - `TerrainRulesTest`: one case per verdict in priority order (block entity + claimed → `BLOCK_ENTITY`); distance 6.01 with cap 6 → `BEYOND_RADIUS`; used 256 of budget 256 → `OVER_BUDGET`.
  - `GrimoireTierTest`: levels 1,2,3,4,5,6,7,10 → 0,0,1,1,2,2,3,3.
- [ ] **Step 2: Run** `scripts/heavy.sh ./gradlew test --no-daemon --tests 'local.carter.office.magic.core.*'` → FAIL (classes missing).
- [ ] **Step 3: Implement** the classes. No Minecraft imports in `core/`.
- [ ] **Step 4: Run** the same command → PASS.
- [ ] **Step 5: Commit** `git add mod/src/main/java/local/carter/office/magic/core mod/src/test/java/local/carter/office/magic/core && git commit -m "feat(magic): pure rules — levels, mana, charge, signs, wheel, shapes, terrain verdicts"`

### Task 2: Items, recipes, looks, all textures, all lang, spell ids

**Files:**
- Create: `magic/item/{MagicItems,GrimoireItem,RebindingSealItem,GrimoireLook}.java`, `magic/spell/SpellIds.java`, `magic/MagicMod.java` (items only for now)
- Create: `tools/make_magic_textures.py`; the resources listed in File Structure (items, models, textures, recipes, two advancements, tag)
- Modify: `mod/src/main/java/local/carter/office/OfficeMod.java` (call `MagicMod.init()`), `assets/office/lang/en_us.json`
- Create (empty classes, so later tasks never edit the entrypoint list): `mod/src/gametest/java/local/carter/office/magic/{MagicCoreGameTests,CastGameTests,TerrainGameTests,HandSignGameTests,RecallGameTests,BalanceGameTests,FireGameTests,WaterGameTests,WindGameTests,LightningGameTests,EarthGameTests,ShadowGameTests}.java`,
  `mod/src/gametest/java/local/carter/office/client/MagicClientGameTests.java`
- Modify: `mod/src/gametest/resources/fabric.mod.json` (all 12 server classes + the client class)
- Test: `MagicCoreGameTests.java`, `mod/src/test/java/local/carter/office/magic/LangCoverageTest.java`

**Interfaces:**
- Consumes: `Element`, `GrimoireTier`, `Slots` (Task 1).
- Produces:
  - `MagicItems.GRIMOIRE`, `MagicItems.REBINDING_SEAL` (keys `office:grimoire`, `office:rebinding_seal`); `GrimoireItem.isGrimoire(ItemStack)`.
  - `GrimoireLook.stamp(ItemStack, Optional<Element>, int level)` sets `CUSTOM_MODEL_DATA` to strings `["t<tier>"]` and colors
    `[element argb, or FF8B5A2B when unbound]`; `GrimoireLook.element(ItemStack)` → `Optional<Element>`.
  - `SpellIds`: one `Identifier` constant per spell (`FIRE_BOLT = office:fire_bolt`, …, `RECALL = office:recall`);
    `Identifier at(Element e, int slot)`; `List<Identifier> ALL` (31). The ids are the ones in the lane tables.
  - Lang (complete; lanes never touch it): `item.office.grimoire` "Grimoire", `item.office.rebinding_seal` "Rebinding Seal";
    `element.office.<key>` (Fire, Water, Wind, Lightning, Earth, Shadow) and `.motto` ("Burn bright.", "Flow and crush.",
    "Never still.", "Strike first.", "Stand unbroken.", "Unseen, unstoppable."); `spell.office.<id>` (title-case name) and
    `spell.office.<id>.desc` (copy in the lane tables; Recall: "Shift + cast to mark home. Cast to return there.");
    `key.office.spell_wheel` "Spell wheel", `key.category.office.magic` "Magic"; screen strings `magic.office.choose` "Choose your element",
    `.bind_q` "Bind to %s?", `.bind_note` "This is permanent (a Rebinding Seal can change it).", `.bind` "Bind", `.back` "Back",
    `.level` "Level %d", `.home` "Home: %d %d %d (%s)", `.no_home_short` "No home", `.lv` "Lv %d", `.plus_levels` "+%d lvl",
    `.hint` "Off hand + empty hand: tap right-click to cast · hold to charge · R wheel · Shift+scroll", `.signs` "Make the signs!";
    messages `magic.office.bound` "You are bound to %s", `.no_mana` "Not enough mana (%d/%d)", `.cooldown` "%s is recharging (%.1f s)",
    `.locked` "%s unlocks at grimoire level %d", `.no_levels` "%s needs %d XP levels", `.fizzle` "The signs broke — the spell fizzled",
    `.level_up` "Grimoire level %d", `.unlocked` "%s unlocked", `.stronger` "Your magic grows stronger",
    `.no_home` "No home yet — Shift + cast Recall to mark one", `.home_marked` "Home marked", `.recalling` "Recalling…",
    `.recall_interrupted` "Recall interrupted", `.protected` "The land here is protected", `.busy` "Too much magic here — wait a moment",
    `.undo_done` "Restored %d blocks from %d casts (%d changed since, left alone)", `.undo_help` "Undo a player's last n terrain casts (the newest 20 are kept)".
  - Textures (all generated now): `textures/gui/spell/<id>.png` (32×32 glyph, element colour) for all 31 ids; `textures/gui/magic/sigil_<key>.png`
    (64×64), `book_open.png`, `padlock.png`, `wheel_slice.png`, `glow.png`; `textures/misc/magic/ring_{0..4}.png`, `runes.png` (8×1 atlas);
    `textures/item/grimoire_cover.png` (greyscale, tinted), `grimoire_t{0..3}.png` overlays (pages glow, trim, gems), `rebinding_seal.png`.

- [ ] **Step 1: Write the failing tests**
  - `LangCoverageTest` (JUnit, reads `assets/office/lang/en_us.json` and the texture folder from the main resources on the classpath): every
    `SpellIds.ALL` id has a non-empty `spell.office.<id>` and `.desc` and an icon PNG; every `element.office.*` and `.motto` exists; every
    `magic.office.*` key listed above exists.
  - `MagicCoreGameTests`: `grimoireRecipeUsesBookFeatherIron` (recipe `office:grimoire` from `[book, feather, iron_ingot]` in any order →
    1 grimoire; `[book, feather]` → nothing); `rebindingSealRecipe` (`[nether_star, book]` → 1 seal); `grimoireStampsLookFromData`
    (`stamp(stack, FIRE, 7)` → strings `["t3"]`, colors `[0xFFFF6A1A]`; unbound level 1 → `["t0"]`, `[0xFF8B5A2B]`);
    `grimoireStacksToOneAndIsNotAShield` (max stack 1; no `DataComponents.BLOCKS_ATTACKS`); `grimoireChargeSlowsAndBlocksSprint`
    (`USE_EFFECTS` = `(false, false, 0.6f)`).
- [ ] **Step 2: Run** `scripts/heavy.sh ./gradlew build test runGametest --no-daemon` → FAIL.
- [ ] **Step 3: Implement.** `GrimoireItem`: `stacksTo(1)`, `Rarity.UNCOMMON`, `USE_EFFECTS = new UseEffects(false, false, 0.6f)`, `getUseDuration`
  72000, `getUseAnimation` `NONE`; `use`, `releaseUsing`, `onUseTick` and `inventoryTick` return `PASS` / do nothing until Tasks 3–4.
  `items/grimoire.json`: `minecraft:select` on `custom_model_data` string index 0 (`t0..t3` → `item/grimoire_t0..t3`); each model has
  layer0 = the cover tinted by `custom_model_data` colour index 0, and layer1 = the tier overlay. Recipes are shapeless. Advancements unlock
  the grimoire recipe on having a book or an iron ingot, and the seal on having a nether star. Tag `office:magic_immune`: bedrock, obsidian,
  crying_obsidian, reinforced_deepslate, end_portal_frame, end_portal, end_gateway, nether_portal, barrier, light, the three command blocks,
  structure_block, jigsaw, `office:hologram_emitter`. `tools/make_magic_textures.py` (stdlib, same PNG writer as `tools/make_emitter_textures.py`)
  reads colours from `Element.java` and the spell ids from `SpellIds.java`, then writes every texture deterministically; commit the output.
- [ ] **Step 4: Run** the Step 2 command → PASS.
- [ ] **Step 5: Commit** `feat(magic): grimoire + rebinding seal, recipes, tiered looks, all magic textures and lang`.

### Task 3: Magic data, payloads, SPI skeleton, terrain interfaces, complete test kit

**Files:**
- Create: `magic/data/{MagicData,MagicAttachments}.java`, `magic/net/{MagicPayloads,FxEvent,MagicSender,MagicServer}.java`, `magic/cast/{ElementChoice,CastService}.java`,
  `magic/spell/{Spell,SpellCost,CastContext,CastOutcome,SpellRegistry,SpellKit}.java`, six `magic/spell/<element>/<Element>Spells.java` stubs
  (`public static void register() {}`), `magic/terrain/{TerrainEdits,EditBatch,EditTier,TempBlocks,NoTerrain}.java`, `magic/command/MagicCommand.java`
- Create: `mod/src/gametest/java/local/carter/office/magic/MagicTestKit.java`, `tools/make_magic_arena.py` → `mod/src/gametest/resources/data/office-test/structure/magic_arena.nbt`
- Modify: `GrimoireItem.java` (`use` when unbound or in the main hand; `inventoryTick` stamping), `RebindingSealItem.java`, `MagicMod.java`
- Test: `MagicCoreGameTests.java`; `mod/src/test/java/local/carter/office/magic/{MagicDataCodecTest,PayloadCapsTest,SpellRegistryTest}.java`

**Interfaces:**
- Consumes: Tasks 1–2.
- Produces:
  - `record MagicData(Optional<Element> element, int level, int xp, Optional<Identifier> selected, Optional<GlobalPos> home, Map<Identifier, Long> readyAt)`
    with `EMPTY` (level 1), `CODEC`, `STREAM_CODEC`, `with…` copies, and `long cooldownLeft(Identifier spell, long gameTime)`. `home` is the only home store.
  - `MagicAttachments.MAGIC` (`AttachmentType<MagicData>`: persistent, `copyOnDeath`, `syncWith(STREAM_CODEC, AttachmentSyncPredicate.targetOnly())`) and
    `MagicAttachments.MANA` (`AttachmentType<Integer>`, same flags, initializer 100). Helpers: `MagicData get(Player)`, `void set(ServerPlayer, MagicData)`
    (also re-stamps held grimoires), `int mana(Player)`, `void setMana(ServerPlayer, int)`.
  - `record FxEvent(Identifier spell, int casterId, Vec3 origin, Vec3 dir, Vec3 target, float charge, float power, long seed, int duration, int[] entities, float[] data)`.
    The codec enforces `entities` ≤ 32 and `data` ≤ 16. Builders: `withTarget(Vec3)`, `withDuration(int)`, `withEntities(int...)`, `withData(float...)`.
  - `MagicPayloads` (same style as `net/OfficePayloads`): S2C `OpenGrimoire(Mode mode)` (`enum Mode { CHOOSE, INFO }`), `CastFx(FxEvent event)`,
    `FxCancel(int casterId)`, `SignChallenge(int id, Identifier spell, long seed, int length, int limitTicks)`; C2S `ChooseElement(Element element)`,
    `SelectSpell(Identifier spell)`, `SignAnswer(int id, byte[] dirs)` (≤ 6 entries, enforced in the codec).
  - `interface MagicSender { void fx(ServerLevel level, FxEvent e, double range); void cancelFx(ServerPlayer caster); void challenge(ServerPlayer p, SignChallenge c); void open(ServerPlayer p, OpenGrimoire.Mode m); void actionBar(ServerPlayer p, Component c); void title(ServerPlayer p, Component title, Component subtitle); }`
  - `MagicServer.sender()` (real impl: `ServerPlayNetworking` to `PlayerLookup.around(level, origin, range)` ∪ caster) and `MagicServer.setSenderForTesting(@Nullable MagicSender)`;
    `MagicServer.kit()` / `MagicServer.setKit(SpellKit)` (default `SpellKit.NONE`; Task 5 installs the world kit on server start).
  - `interface Spell { Identifier id(); @Nullable Element element(); int slot(); default SpellCost cost() { return SpellCost.forSlot(slot()); } default boolean managesOwnCooldown() { return false; } CastOutcome cast(CastContext ctx); }`
  - `record SpellCost(int mana, int cooldownTicks, int xp, int xpLevels) { static SpellCost forSlot(int slot) /* wraps Slots.baseCost */ }`
  - `record CastContext(ServerPlayer caster, ServerLevel level, Spell spell, float charge, float power, int grimoireLevel, long seed, long castId, SpellKit kit) { Vec3 eye(); Vec3 look(); RandomSource random(); FxEvent fx(); }`
    (`fx()` = event pre-filled with spell, caster id, origin = eye, dir = look, target = eye + look × 8, charge, power, seed).
  - `sealed interface CastOutcome { static CastOutcome cast(FxEvent fx); static CastOutcome fizzle(String messageKey); }` — fizzle spends nothing.
    A spell must decide fizzle **before** any side effect.
  - `SpellRegistry`: `register(Spell)` (throws on a duplicate id or duplicate element+slot), `Optional<Spell> get(Identifier)`, `Optional<Spell> at(Element, int slot)`,
    `@Nullable Spell[] wheel(Element)` (length 6, index = slot, null where unregistered), `List<Spell> unlocked(MagicData)` (registered, unlocked, slot order, Recall last),
    `AutoCloseable overrideForTesting(Spell)` (takes that element+slot or id temporarily and restores on close).
  - `record SpellKit(TerrainEdits terrain, TempBlocks temp)`; `SpellKit.NONE` uses `NoTerrain` (every edit refused).
  - Terrain interfaces (implemented in Task 5): `enum EditTier { MEDIUM(6, 256), ULTIMATE(12, 1500); double radius(); int budget(); }`;
    `interface TerrainEdits { EditBatch begin(CastContext ctx, BlockPos origin, EditTier tier); }`;
    `interface EditBatch extends AutoCloseable { boolean set(BlockPos, BlockState); boolean remove(BlockPos); boolean launch(BlockPos, Vec3 velocity); boolean placeTemporary(BlockPos, BlockState, int ticks); int applied(); int refused(); void close(); }`;
    `interface TempBlocks { boolean place(ServerLevel, BlockPos, BlockState placed, int ticks); int active(); void restoreAll(MinecraftServer); }`.
  - `CastService` skeleton with final signatures (bodies land in Tasks 4 and 6; until then they throw `UnsupportedOperationException`):
    `enum Result { CAST, CHALLENGED, FIZZLED, NO_ELEMENT, WRONG_ELEMENT, LOCKED, COOLDOWN, NO_MANA, NO_LEVELS, BUSY }`;
    `Result cast(ServerPlayer p, Spell spell, float charge)`, `void release(ServerPlayer p, int ticksHeld)`, `Result answer(ServerPlayer p, int id, Dir[] dirs)`, `void tick(MinecraftServer)`.
  - `ElementChoice.choose(ServerPlayer, Element) -> boolean` (only when unbound; sets `selected` to `SpellIds.at(e, 0)`; `ENCHANTMENT_TABLE_USE`; title `magic.office.bound`).
  - `SelectSpell` receiver accepts only ids in `SpellRegistry.unlocked(data)`.
  - `/magic level <player> <1..10>`, `/magic mana <player> <n>`, `/magic reset <player>` (op 2).
  - `MagicTestKit` (complete; lanes never edit it): `ARENA = "office-test:magic_arena"` (32×24×32, stone floor y 0–3; `STAND = (16,4,4)` facing +Z);
    `ServerPlayer caster(GameTestHelper h, Element e, int level)` (survival mock at `STAND`, bound, full mana, grimoire in the off hand, empty main hand,
    30 XP levels, respawn config cleared); `ServerPlayer otherPlayer(GameTestHelper h, BlockPos rel)`; `void aim(ServerPlayer p, BlockPos rel)`;
    `Result cast(ServerPlayer p, Identifier spell, float charge)`; `Result castUltimate(ServerPlayer p, Identifier spell)` (casts, reads the recorded
    challenge, answers with `HandSigns.sequence`); `Zombie dummy(GameTestHelper h, BlockPos rel)` (NoAI, 20 hp, persistent); `Villager villager(h, rel)`;
    `Wolf tamedWolf(h, rel, ServerPlayer owner)`; `ItemEntity item(h, rel)`; `Arrow arrow(h, BlockPos rel, Vec3 velocity)`;
    `void fill(h, BlockPos fromRel, BlockPos toRel, Block b)`; `void chest(h, BlockPos rel)`; `HologramBlockEntity emitter(h, BlockPos rel, ServerPlayer owner)`;
    `long blockHash(h)` (hash of every block state in the arena); `int count(h, Block b)`; `int waterCount(h)` (water sources + flows);
    `double dist(Entity a, Vec3 relPoint)`; `Recorder recorder(h)` (installs a `MagicSender` recording `fx()`, `challenges()`, `messages()`,
    `titles()`, `cancels()`; restored when the test ends).
    Note: GameTest servers have no spawn protection (only `DedicatedServer` overrides `isUnderSpawnProtection`), and the kit clears respawn configs,
    so the fallback claim rule only reacts to what a test sets up.
- [ ] **Step 1: Write the failing tests**
  - `MagicDataCodecTest`: a fully populated `MagicData` round-trips through `CODEC` (JsonOps) and `STREAM_CODEC`; `EMPTY.level()==1`.
  - `PayloadCapsTest`: `FxEvent` with 33 entities or 17 data floats fails to decode (`DecoderException`); `SignAnswer` with 7 dirs fails; valid ones round-trip.
  - `SpellRegistryTest` (fake spells, Recall probe via `overrideForTesting`): a duplicate id throws; a duplicate FIRE slot 0 throws; `wheel(FIRE)` has length 6
    with nulls at empty slots; `unlocked` at level 2 = slots 0, 1 then Recall; `SpellCost.forSlot(3)` equals `Slots.baseCost(3)`.
  - `MagicCoreGameTests`: `firstUseOpensChoice` (unbound, grimoire in the main hand → recorder `open(CHOOSE)`; off hand + empty main hand → `open(CHOOSE)`;
    off hand + sword in the main hand → `PASS`, nothing opened); `chooseElementBindsOnce` (`choose(FIRE)` true, then `choose(WATER)` false, still FIRE);
    `mainHandUseOpensInfo` (bound, main hand → `open(INFO)`); `rebindingSealClearsElementKeepsLevel` (level 5 FIRE → element empty, level 5, seal used up,
    `open(CHOOSE)`); `magicDataSurvivesDeath` (data and mana copied on `restoreFrom`); `heldGrimoireRestampedOnLevelChange` (`/magic level p 7` → held
    grimoire string `t3`); `selectSpellRejectsLocked` (level 1, `SelectSpell(slot-1 probe)` → selected unchanged); `kitArenaIsReady`
    (`caster` stands on stone, faces +Z, has 30 XP levels; `blockHash` is the same twice; `waterCount==0`).
- [ ] **Step 2: Run** `scripts/heavy.sh ./gradlew build test runGametest --no-daemon` → FAIL.
- [ ] **Step 3: Implement** the above; register payloads, receivers and attachments in `MagicMod`. `tools/make_magic_arena.py` writes the gzipped structure NBT with the stdlib.
- [ ] **Step 4: Run** → PASS (the `CastService` stubs are not called by these tests).
- [ ] **Step 5: Commit** `feat(magic): magic data + mana, payloads, spell SPI skeleton, terrain interfaces, gametest kit`.

### Task 4: Cast pipeline and spell kit services

**Files:**
- Create: `magic/spell/{Targets,TargetFilter,Hurt,Motion,Projectiles,ActiveEffects}.java`
- Modify: `magic/cast/CastService.java` (`cast`, `release`, `tick`), `GrimoireItem.java` (off-hand use, charge, release), `MagicMod.java` (server tick, death/disconnect hooks)
- Test: `mod/src/gametest/java/local/carter/office/magic/CastGameTests.java`

**Interfaces:**
- Consumes: Task 3 SPI, kit and payloads.
- Produces:
  - `CastService.cast` checks, in this order: element bound (`NO_ELEMENT`), spell element = player's or null (`WRONG_ELEMENT`), unlocked (`LOCKED`),
    channeling Recall (`BUSY`), cooldown (`COOLDOWN`), mana for `cost(base, affordableCharge(...), ultimate)` (`NO_MANA`), XP levels for ultimates (`NO_LEVELS`).
    Ultimates go to `SignChallenges.issue` (Task 6). Then `spell.cast(ctx)`. Fizzle `magic.office.busy` → `BUSY`; other fizzles → `FIZZLED`; both spend nothing.
    On `CAST`: spend mana, then set the cooldown `readyAt = max(existing, gameTime + cooldownTicks)` only when `cooldownTicks > 0 && !spell.managesOwnCooldown()`.
    Add xp via `LevelCurve.add`; on a level-up play `PLAYER_LEVELUP` and send title `magic.office.level_up` with subtitle `magic.office.unlocked` (the newly
    unlocked spell) or `magic.office.stronger`. Send `fx` once with range 64 (128 for slot 4). Remember the last-cast tick for the regen pause.
    Failure messages go to the action bar with the Task 2 keys.
  - `release(p, ticksHeld)`: selected spell, `ChargeMath.charge(ticksHeld)`; Recall and ultimates ignore charge.
  - `tick`: mana regen (`ManaMath`), `ActiveEffects` and `Projectiles` ticking.
  - `GrimoireItem` (server side): off hand + empty main hand + bound + selected present → `player.startUsingItem(OFF_HAND)`, `CONSUME`; any main-hand item → `PASS`;
    `onUseTick`: main hand no longer empty → `stopUsingItem()` with no cast; `releaseUsing` → `CastService.release`. Death and disconnect stop using with no cast.
  - `TargetFilter.affects(ServerPlayer caster, Entity e)`, `TargetFilter.pushable(ServerPlayer caster, Entity e)` (rules in Global Constraints).
  - `Targets`: `Optional<LivingEntity> ray(CastContext, double range, double forgiveness)`, `Vec3 point(CastContext, double range)` (block hit or max range),
    `Vec3 ground(ServerLevel, Vec3)` (heightmap MOTION_BLOCKING), `List<LivingEntity> radius(CastContext, Vec3 c, double r)`,
    `List<Entity> cone(CastContext, double range, double halfAngleDeg, boolean pushables)`. All are filtered and capped at 64.
  - `Hurt.damage(CastContext, LivingEntity, float amount)` (`damageSources().indirectMagic(caster, caster)`), `Hurt.knock(Entity, Vec3 dir, double strength, double up)` (sets `hurtMarked`).
  - `Motion.dash(ServerPlayer, Vec3 velocity, int noFallTicks)`, `Motion.teleportSafe(ServerPlayer, Vec3 target) -> Vec3` (steps back along the path until the player box fits).
  - `interface LingeringEffect { boolean tick(ServerLevel level, int age); default void end(ServerLevel level) {} }`; `ActiveEffects.add(LingeringEffect) -> boolean`
    (false at 32 → the spell returns `fizzle("magic.office.busy")` before anything else happens). `end` runs for all on `SERVER_STOPPING`.
  - `record ProjectileSpec(Vec3 origin, Vec3 velocity, double gravity, double radius, int maxTicks, boolean pierce)`;
    `record ProjectileHit(Vec3 pos, @Nullable LivingEntity entity, @Nullable BlockHitResult block)`;
    `Projectiles.launch(CastContext, ProjectileSpec, Consumer<ProjectileHit> onHit) -> boolean` (entity-free; own cap 64; block clip `ClipContext.Block.COLLIDER`;
    entity sweep through `TargetFilter`; pierce hits each entity once). `Projectiles.step(ProjectileSpec, int tick) -> Vec3` is pure and shared with the client fx.
- [ ] **Step 1: Write the failing GameTests** in `CastGameTests` (probes via `overrideForTesting`: `office-test:probe` FIRE slot 0 and `office-test:probe_medium`
  FIRE slot 1; each returns `CastOutcome.cast(ctx.fx())`):
  - `castSpendsManaSetsCooldownSendsOneFx` (mana 100 → 92, `cooldownLeft == 10`, exactly 1 fx); `chargedCastCostsMoreAndIsStronger` (charge 1 → mana 88,
    fx power 2.0, fx charge 1.0); `lowManaLowersChargeInsteadOfFailing` (mana 9, charge 1 → CAST, mana spent ≤ 9); `cooldownBlocksSecondCast`
    (→ COOLDOWN, message `magic.office.cooldown`); `lockedSlotRefused` (probe_medium at level 1 → LOCKED); `castXpLevelsUpGrimoire` (level 1, xp 18,
    gain 2 → level 2, one title); `cooldownNeverLowered` (a probe with cooldown 10 whose `cast` itself writes `readyAt` = now + 500 → after the cast `readyAt` is still now + 500);
    `ownCooldownSpellUntouched` (a probe with `managesOwnCooldown()=true` and cost cooldown 1200 → `readyAt` not written by `CastService`);
    `busyEffectsFizzleFree` (fill `ActiveEffects` to 32, a lingering probe → BUSY, mana unchanged, 0 fx).
  - `mainHandItemNeverStartsCharge` (sword, bread, dirt and shield in the main hand: `use(OFF_HAND)` → PASS, `!isUsingItem()`); `switchingToToolCancelsCharge`
    (start using, put a pickaxe in the main hand, `onUseTick` → not using, mana unchanged, 0 fx); `chargeDiscardedOnDeath` (start using, kill → 0 fx, mana unchanged
    after respawn copy); `tapAndHoldReleaseCharges` (`release(p,5)` → fx power 1.0; `release(p,40)` after the cooldown → power 2.0).
  - `targetFilterSparesPlayersPetsVillagers` (other player, tamed wolf, villager, iron golem, armor stand, item → `affects` all false; zombie true; item `pushable` true).
  - `projectileStopsAtWall` (speed 2 toward a stone wall 5 ahead → onHit at the wall within 4 ticks, entity null); `projectileStepMatchesServerPath`
    (`Projectiles.step` positions equal the server's recorded positions for 10 ticks with gravity 0.03).
- [ ] **Step 2: Run** `scripts/heavy.sh ./gradlew build test runGametest --no-daemon` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(magic): server cast pipeline, targeting, projectiles, lingering effects`.

### Task 5: Terrain edits, temporary blocks, claims, edit log, `/magic undo`

**Files:**
- Create: `magic/terrain/{WorldTerrainEdits,WorldEditBatch,TempBlockData,EditLog,ClaimCheck,ClaimLookup,FallbackClaims}.java`; `OpacClaims.java` only if Step 1 passes
- Modify: `build.gradle` only if Step 1 passes (Modrinth maven `exclusiveContent` for group `maven.modrinth`; `compileOnly("maven.modrinth:open-parties-and-claims:KBCCg0os") { transitive = false }`);
  `MagicMod.java` (install `SpellKit(new WorldTerrainEdits(...), TempBlockData.get(server))` on server start; lifecycle hooks), `MagicCommand.java`,
  `hologram/HologramIndex.java` (add `List<Entry> entriesIn(ResourceKey<Level>)`)
- Test: `TerrainGameTests.java`, `mod/src/test/java/local/carter/office/magic/{EditLogTest,OpacClaimsTest}.java`

**Interfaces:**
- Consumes: `TerrainRules`, `Shapes` (Task 1); terrain interfaces and `CastContext` (Task 3); `MagicData.home`; `HologramIndex`.
- Produces:
  - `WorldTerrainEdits implements TerrainEdits`. Each batch has its own origin and radius cap (`tier.radius()`). The block budget is per cast
    (`ctx.castId()`), summed over its batches. Every call runs `TerrainRules.decide`, where immune = `#office:magic_immune`, claimed = `ClaimCheck`,
    spawn = `!level.mayInteract(caster, pos)`, and border = `level.getWorldBorder().isWithinBounds(pos)`. Changes queue to one global applier
    (≤ 256 `setBlock(…, Block.UPDATE_ALL)` per tick, FIFO across casts). `before` is captured when the change is **applied**. `launch` = remove +
    `FallingBlockEntity.fall` with `dropItem=false` (≤ 48 per cast). `placeTemporary` is MEDIUM only, air or replaceable only, and goes through `TempBlocks`.
    The first CLAIMED or SPAWN_PROTECTED refusal in a cast sends `magic.office.protected` to the action bar.
  - `TempBlockData extends SavedData implements TempBlocks` (`office:magic_temp`): entries (dimension, pos, original, placed, expireGameTime).
    It restores expired entries **only if the block is still `placed`**. `restoreAll` runs on `SERVER_STOPPING`, and expired entries are restored on `SERVER_STARTED`.
  - `interface ClaimLookup { @Nullable UUID owner(ServerLevel level, int chunkX, int chunkZ); }`;
    `interface ClaimCheck { Predicate<BlockPos> forBatch(ServerPlayer caster, ServerLevel level, BlockPos origin, double radius); static ClaimCheck forServer(MinecraftServer); }`.
    With OPAC loaded and its API resolved, `OpacClaims(ClaimLookup)` blocks a chunk claimed by any UUID other than the caster's (cached per chunk).
    Otherwise `FallbackClaims` blocks anything within 32 blocks of: any hologram emitter in that dimension (`HologramIndex.entriesIn`), the `MagicData.home`
    of another online player, or another online player's explicit respawn position (null respawn configs are skipped).
  - `EditLog`: files at `<world>/data/office/magic-edits/<uuid>/<gameTime>-<castId>.nbt` (compressed). `record LoggedEdit(BlockPos pos, BlockState before, BlockState after)`;
    `record LoggedCast(UUID player, Identifier spell, ResourceKey<Level> dimension, long gameTime, List<LoggedEdit> edits)` (all batches of one cast).
    Keeps the newest 20 per player. `UndoReport undo(MinecraftServer, UUID player, int n)` works newest first and restores an edit only if the current
    state equals `after`; `record UndoReport(int restored, int casts, int skipped)`.
  - `/magic undo <player> [n=1]` (op 2, `GameProfileArgument`) → `magic.office.undo_done`; its help text is `magic.office.undo_help`.
- [ ] **Step 1: Verify the OPAC API** before coding. Download the pinned jar, then run `javap` (Temurin 25 under `~/.gradle/jdks`) on
  `xaero.pac.common.server.api.OpenPACServerAPI` (`get(MinecraftServer)`, `getServerClaimsManager()`), the claims manager's `get(Identifier, int, int)`, and
  `IPlayerChunkClaimAPI.getPlayerId()`. If any is missing on 26.3, skip `OpacClaims`/`build.gradle`, ship `FallbackClaims` only, and say so in the commit message.
- [ ] **Step 2: Write the failing tests**
  - `EditLogTest`: a `LoggedCast` with 3 edits round-trips through its NBT codec.
  - `OpacClaimsTest` (fake `ClaimLookup`): own claim → allowed; another UUID's claim → blocked; unclaimed → allowed; lookup called once per chunk.
  - `TerrainGameTests` (arena): `refusesBlockEntitiesBedrockObsidian` (chest, bedrock, obsidian, emitter in range → unchanged, `refused()==4`);
    `refusesBeyondRadius` (MEDIUM `set` at distance 7 from its origin → false); `segmentedBatchesReachFurther` (two MEDIUM batches of one cast,
    origins 10 apart, each sets a block 5 from its origin → both applied; one log entry with 2 edits); `budgetIsPerCast` (two MEDIUM batches of one cast,
    200 sets each → 256 applied in total); `fallbackProtectsNearEmitter` (emitter 20 from origin → nothing changes; 40 → changes);
    `fallbackProtectsOtherPlayersHome` (`otherPlayer` with home 10 away → nothing changes; the caster's own home → changes); `applierCapsPerTick`
    (1000 queued → ≤ 256 after 1 tick, all after 4); `undoRestoresLastCast` (two casts; `undo(p,1)` restores only the second);
    `undoSkipsBlocksChangedSince` (crater, then 3 crater cells set to planks → undo restores the rest, the planks stay, `skipped==3`);
    `tempBlocksRestoreWhenExpired` (temp water for 20 ticks → air at tick 21; a temp cell replaced by dirt → the dirt stays);
    `stoppingRestoresTempBlocks` (`restoreAll` → all temp cells back to original); `tempBlocksRestoredAfterReload` (save the SavedData, drop the instance,
    load, advance past expiry → restored).
- [ ] **Step 3: Run** `scripts/heavy.sh ./gradlew build test runGametest --no-daemon` → FAIL.
- [ ] **Step 4: Implement.**
- [ ] **Step 5: Run** → PASS.
- [ ] **Step 6: Commit** `feat(magic): claim-safe batched terrain edits, persisted temp blocks, edit log + /magic undo`.

### Task 6: Hand signs for ultimates (server + prompt screen)

**Files:**
- Create: `magic/cast/SignChallenges.java`, `client/magic/screen/{HandSignScreen,SignLayout}.java`, `client/magic/MagicClient.java` (`register()`: client payload receivers; later tasks add to it)
- Modify: `CastService.java` (`answer`, ultimate branch), `MagicMod.java` (receiver; death, disconnect and dimension-change hooks),
  `client/OfficeClient.java` (call `MagicClient.register()` **after** `HologramInteractions.register()`, so the hologram use and scroll handlers run first)
- Test: `HandSignGameTests.java`, `mod/src/test/java/local/carter/office/client/magic/{HandSignInputTest,SignLayoutTest}.java`

**Interfaces:**
- Consumes: `HandSigns`, `Dir` (Task 1); `CastService`, payloads, kit (Tasks 3–4).
- Produces:
  - `SignChallenges.issue(ServerPlayer, Spell, float power) -> SignChallenge` (id, seed from `RandomSource`, `HandSigns.length(level)`, limit); one open challenge per player.
  - `CastService.answer`: the id must match the open challenge. Correct and within `limit + GRACE_TICKS`, with mana, levels and cooldown re-checked **at answer time**
    → full ultimate cast (base mana and `xpLevels` spent, cooldown 600, one fx). Wrong, late or no longer affordable → `FIZZLED` (`HandSigns.fizzleMana`
    spent, cooldown 100, `FIRE_EXTINGUISH`, `magic.office.fizzle`). An unknown id is ignored. `SignChallenges.discard(ServerPlayer)` costs nothing.
    `tick` expires challenges as fizzles. Death, disconnect and dimension change call `discard`.
  - `HandSignScreen(SignChallenge c)`: `isPauseScreen()=false`. ↑/W, ↓/S, ←/A, →/D map to `Dir`. Arrows are drawn large in a row; entered ones light up in the
    element colour; a timer bar shrinks; the title is `magic.office.signs`. The first wrong key or Esc sends the partial answer (the server fizzles it).
    A complete sequence sends `SignAnswer`, flashes and closes. The screen closes on player death.
    Pure helpers: `HandSignScreen.dirFor(int keyCode) -> @Nullable Dir`; `SignLayout.layout(int w, int h, int length)` → arrow rects + timer rect.
- [ ] **Step 1: Write the failing tests**
  - `HandSignInputTest`: `dirFor(KEY_UP)==UP`, `dirFor(KEY_W)==UP`, `KEY_A→LEFT`, `KEY_D→RIGHT`, `KEY_S→DOWN`, `KEY_E→null`.
  - `SignLayoutTest`: for 320×240 and 1920×1080 with lengths 4 and 6, every rect lies inside the screen with ≥ 4 px margin and no two arrows overlap.
  - `HandSignGameTests` (probe `office-test:probe_ultimate` at FIRE slot 4 via `overrideForTesting`, level 7, 30 XP levels):
    `ultimateIssuesChallengeNotCast` (CHALLENGED, 0 fx, mana unchanged, length 6, limit 92); `correctSignsCastUltimate` (1 fx, mana −60, XP levels −3);
    `chargeDoesNotRaiseUltimateCost` (release after 40 ticks + correct answer → mana −60); `wrongSignsFizzleHalfMana` (mana −30, XP levels unchanged, cooldown 100);
    `lateAnswerFizzles` (answer after 103 ticks → FIZZLED); `notEnoughXpLevels` (2 levels → NO_LEVELS, no challenge);
    `manaSpentElsewhereBeforeAnswerFizzles` (mana set to 10 before answering → FIZZLED); `deathDiscardsChallenge` (kill, then a correct answer → 0 fx, mana unchanged);
    `disconnectDiscardsChallenge` (remove from the player list → challenge gone); `dimensionChangeDiscardsChallenge`.
- [ ] **Step 2: Run** `scripts/heavy.sh ./gradlew build test runGametest --no-daemon` → FAIL.
- [ ] **Step 3: Implement.** The client receiver for `SignChallenge` opens `HandSignScreen` and rebuilds the sequence with `HandSigns.sequence(seed, length)`.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(magic): hand-sign challenges for ultimates`.

### Task 7: Recall (Mark Home + channel)

**Files:**
- Create: `magic/cast/RecallSpell.java`
- Modify: `MagicMod.java` (register Recall; damage, death, disconnect and dimension-change hooks)
- Test: `RecallGameTests.java`

**Interfaces:**
- Consumes: SPI and kit (Tasks 3–4), `MagicData.home`, `MagicSender.cancelFx`.
- Produces: `RecallSpell implements Spell` (id `SpellIds.RECALL`, element `null`, slot 5, `cost()` = `forSlot(5)` for display, `managesOwnCooldown()=true`).
  - Sneaking at release → mark home: `MagicData.home` = `GlobalPos` of the feet block, `magic.office.home_marked`, `readyAt` = now + 20.
  - No home → fizzle `magic.office.no_home`.
  - Otherwise a 60-tick `LingeringEffect` channel (fx duration 60) that keeps the caster in place. It is cancelled by any damage
    (`ServerLivingEntityEvents.AFTER_DAMAGE`), death, disconnect or dimension change → `cancelFx` + `magic.office.recall_interrupted`, no cooldown.
  - Success → `teleport(TeleportTransition)` to home (any dimension, block centre, keep yaw), vanilla `ENDERMAN_TELEPORT` sound plus one
    `sendParticles(PORTAL)` at arrival, then `readyAt` = now + 1200.
  - `RecallSpell.channeling(ServerPlayer) -> boolean` (`CastService` returns BUSY for any cast while it is true).
- [ ] **Step 1: Write the failing GameTests**: `shiftCastMarksHome` (home = feet block, cooldown 20); `recallWithoutHomeFizzles` (no cooldown set);
  `recallTeleportsAfterThreeSeconds` (still in place at tick 59, at home by tick 61, cooldown 1200, mana unchanged); `noRecastDuringChannel`
  (second cast at tick 10 → BUSY, one channel only); `recallCancelledByDamage` (hurt at tick 30 → stays, no cooldown, 1 cancel); `recallCancelledByDeath`;
  `recallCancelledByDisconnect` (channel removed, no teleport on a later tick); `recallCrossDimension` (home in the Nether, cast in the overworld → in the Nether
  at home); `recallCancelledByDimensionChange`; `recallAvailableAtLevelOneForEveryElement` (each element at level 1 → `unlocked` contains `SpellIds.RECALL`,
  and `wheel(e)[5]` is Recall).
- [ ] **Step 2: Run** `scripts/heavy.sh ./gradlew build test runGametest --no-daemon` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(magic): Recall — mark home, 3 s channel, cancel on damage`.

### Task 8: Client input — precedence, R wheel, Shift + scroll

**Files:**
- Create: `client/magic/{MagicInputGate,MagicKeys,SpellWheel,SpellWheelHud,WheelLayout}.java`, `mixin/client/MouseHandlerWheelMixin.java`
- Modify: `client/magic/MagicClient.java`, `mixin/client/MinecraftUseItemMixin.java`, `office.client.mixins.json`,
  `client/pointer/PointerClient.java` (make `inFront(Minecraft, float)` public; no behaviour change), `MagicClientGameTests.java`
- Test: `mod/src/test/java/local/carter/office/client/magic/{MagicInputGateTest,WheelLayoutTest}.java`

**Interfaces:**
- Consumes: `WheelMath`, `ScrollSelect` (Task 1); `SelectSpell`, `MagicAttachments` (Task 3); `SpellRegistry.wheel/unlocked` (Task 3);
  `PointerClient.inFront(mc, partial)` and `PointerClient.typing().active()` (feat/v1).
- Produces:
  - `MagicInputGate` (pure statics plus live wrappers):
    `boolean blocksUse(boolean typing, boolean aimingAtHologram, boolean grimoireOffhand, boolean mainHandEmpty)` — true when typing (deliberate:
    nothing reaches the world while typing), or when grimoire off hand + empty main hand + aiming at any hologram;
    `boolean wheelAllowed(boolean screenOpen, boolean typing, boolean grimoireHeld, boolean bound)`;
    `boolean scrollSelects(boolean shift, boolean typing, boolean aimingAtHologram, boolean grimoireOffhand, boolean mainHandEmpty, boolean bound)` — false whenever
    aiming at any hologram (the monitor gets the scroll). The live versions `blocksUse(Minecraft)` and `scrollSelects(Minecraft)` use `PointerClient.inFront(...).isPresent()`.
  - `MinecraftUseItemMixin`: after `HologramInteractions.onUse` declines, `if (MagicInputGate.blocksUse(mc)) ci.cancel()`. Everything else stays vanilla, so
    main-hand block and entity interactions run before the off-hand grimoire.
  - `MagicKeys.WHEEL`: `KeyMapping("key.office.spell_wheel", KEYSYM, InputConstants.KEY_R, MagicKeys.CATEGORY)` with category
    `KeyMapping.Category.register(OfficeMod.id("magic"))`, registered through `KeyMappingHelper` (26.3 keeps a list per key).
  - `SpellWheel`: `open()`, `addMouse(double dx, double dy)`, `int hovered()`, `@Nullable Identifier releaseSelection()` (null = keep the current spell; never casts);
    eases open over 3 ticks. `MouseHandlerWheelMixin` (`turnPlayer` HEAD, cancellable): while the wheel is open, feed `accumulatedDX/DY` to `SpellWheel.addMouse`,
    zero them and cancel. WASD keeps working.
  - `WheelLayout.layout(int w, int h)` → centre, inner radius 22 and outer radius 70 GUI px (scaled down to fit `min(w,h) × 0.42`), and six slice label anchors.
  - `SpellWheelHud` (`HudElementRegistry.addLast(office:spell_wheel)`): slice index = slot. The hovered slice draws at 1.15× with a glow and plays `UI_BUTTON_CLICK`
    (vol 0.3, pitch 1.8) on change. Each slice has a cooldown sweep. Locked or empty slots are grey with a padlock and `magic.office.lv`. The centre shows name,
    mana cost and cooldown; the ultimate slice shows `magic.office.plus_levels`.
  - Shift + scroll: `ClientHotbarScrollEvents.ALLOW` → when `scrollSelects(mc)`, run `ScrollSelect.next` over `unlocked`, send `SelectSpell`, return false.
- [ ] **Step 1: Write the failing tests**
  - `MagicInputGateTest`: typing → `blocksUse` true, `wheelAllowed` false, `scrollSelects` false; grimoire off hand + empty hand + aiming at a hologram → `blocksUse` true,
    `wheelAllowed` true, `scrollSelects` false; tool in the main hand → `blocksUse` false; not aiming → false; unbound → `scrollSelects` false.
  - `WheelLayoutTest`: for 320×240, 427×240, 640×360 and 1920×1080 the outer ring lies inside the screen with ≥ 8 px margin; label anchors never overlap.
  - `MagicClientGameTests` (CI; tests set level 3 and register FIRE slot 0/1 probes via `overrideForTesting` in the integrated server):
    `emptyHandChestStillOpens` (grimoire off hand, bound, empty main hand, right-click a chest → `ContainerScreen` open, mana unchanged);
    `rightClickHologramDoesNotCast` (own emitter in front, right-click → mana unchanged, not using item); `shiftScrollOnHologramScrollsMonitor`
    (aiming at a hologram → selected spell unchanged); `shiftScrollSelectsNextSpell` (not aiming → selected moves slot 0 → 1, hotbar slot unchanged);
    `wheelSelectsByMouse` (hold R, move the mouse up-right, release → slot 1 selected, mana unchanged); `wheelScreenshotNotBlank` (screenshot with the wheel open:
    inside `WheelLayout`'s outer circle ≥ 20 % of pixels differ from the same frame with the wheel closed).
- [ ] **Step 2: Run** `scripts/heavy.sh ./gradlew test --no-daemon --tests 'local.carter.office.client.magic.*'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `scripts/heavy.sh ./gradlew build test runGametest --no-daemon` → PASS (client gametests compile; CI runs them).
- [ ] **Step 5: Commit** `feat(magic): input precedence with monitors, R spell wheel, Shift+scroll selection`.

### Task 9: Element choice, grimoire screen, mana bar HUD

**Files:**
- Create: `client/magic/screen/{ElementChoiceScreen,ChoiceLayout,GrimoireScreen,GrimoireLayout}.java`, `client/magic/hud/{ManaBarHud,ManaBarLayout}.java`
- Modify: `client/magic/MagicClient.java` (`OpenGrimoire` receiver, HUD registration), `MagicClientGameTests.java`
- Test: `mod/src/test/java/local/carter/office/client/magic/{ChoiceLayoutTest,GrimoireLayoutTest,ManaBarLayoutTest}.java`

**Interfaces:**
- Consumes: textures and lang (Task 2); `ChooseElement`, `SelectSpell`, `OpenGrimoire`, `MagicData` (Task 3); `SpellRegistry.wheel`, `SpellIds` (Tasks 2–3).
- Produces:
  - `ElementChoiceScreen`: dark vignette; the open book centred (`book_open.png`, 50 % of `min(w,h)`); six sigils orbit on an ellipse (rx 0.40 w, ry 0.34 h, 4°/s).
    Hover pauses the orbit and draws the sigil at 1.25× with a pulsing element-colour glow. The book pages then show the element name (2× text), its motto,
    its five spells (icon + name from `SpellIds.at`) and a rotating mini circle. Clicking enters a confirm state (`magic.office.bind_q`, `.bind_note`,
    [`.bind`] [`.back`]). Bind sends `ChooseElement`, flashes for 20 ticks and closes. Tab and the arrow keys cycle sigils, Enter confirms, Esc closes unbound.
    `isPauseScreen()=false`. `ChoiceLayout.layout(int w, int h, float orbitDeg)` → six sigil centres, sigil size and the book rect.
  - `GrimoireScreen` (main-hand use, bound). Left page: sigil, `magic.office.level`, xp bar to next level, and `magic.office.home` from `MagicData.home`
    (or `.no_home_short`). Right page: 6 spell rows (icon, name, mana, cooldown in s, padlock + `.lv`); click selects. Footer `magic.office.hint`.
    `GrimoireLayout.layout(int w, int h)` → page rects + 6 row rects.
  - `ManaBarHud`: `HudElementRegistry.replaceElement(VanillaHudElements.INFO_BAR, …)` and `(EXPERIENCE_LEVEL, …)`. While a grimoire is in either hand and the player
    is bound, it draws the mana bar where the XP bar goes (182×5, element-colour fill, a notch per 10 mana, white sweep for the selected spell's cooldown, the
    mana number centred above). The **XP level number** sits at the bar's right end in vanilla green. Otherwise it delegates to vanilla. The selected spell's name
    fades in above the hotbar for 40 ticks after a change. `ManaBarLayout.layout(int w, int h)` → bar rect, mana-number pos, XP-level pos.
- [ ] **Step 1: Write the failing tests**
  - `ChoiceLayoutTest`: for 320×240, 427×240, 640×360 and 1920×1080 (orbit 0° and 37°), all six sigils lie fully inside the screen with ≥ 4 px margin, no two overlap,
    and the book rect never overlaps a sigil.
  - `GrimoireLayoutTest`: same sizes; all row rects lie inside their page, none overlap, the footer fits.
  - `ManaBarLayoutTest`: the bar rect equals the vanilla XP bar rect (`(w − 182) / 2, h − 29, 182×5`); the XP-level text never overlaps the mana number.
  - `MagicClientGameTests`: `firstOpenShowsElementChoice` (use an unbound grimoire → `ElementChoiceScreen`; screenshots at GUI scale 1 and 4; inside each
    `ChoiceLayout` sigil rect ≥ 30 % of pixels are non-background); `bindingFireClosesAndShowsManaBar` (click the Fire sigil, then Bind → screen closed,
    element FIRE; in the `ManaBarLayout` rect the screenshot pixels match the Fire colour within tolerance on ≥ 50 % of the filled width).
- [ ] **Step 2: Run** `scripts/heavy.sh ./gradlew test --no-daemon --tests 'local.carter.office.client.magic.*'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS; `scripts/heavy.sh ./gradlew build --no-daemon` → BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `feat(magic): element choice scene, grimoire screen, mana bar HUD`.

### Task 10: Client FX core — circles, glow, shake, FX dispatch, Recall visual

**Files:**
- Create: `client/magic/fx/{FxClient,SpellFx,SpellFxRegistry,FxKit,DefaultFx,RecallFx,MagicCircleRenderer,LightPipeline,ChargeCircles,GhostBlocks,Shake,GrimoireParticles}.java`,
  six `client/magic/fx/<element>/<Element>Fx.java` stubs, `mixin/client/CameraShakeMixin.java`, `THIRD_PARTY_NOTICES.md`
- Modify: `office.client.mixins.json`, `client/magic/MagicClient.java`
- Test: `mod/src/test/java/local/carter/office/client/magic/{CircleMathTest,FxBudgetTest}.java`

**Interfaces:**
- Consumes: `FxEvent`, `CastFx`, `FxCancel` (Task 3); `ChargeMath`, `Element` (Task 1); `GrimoireLook.element` (Task 2); `Projectiles.step` (Task 4).
- Produces:
  - `@FunctionalInterface interface SpellFx { void play(FxEvent e, ClientLevel level, FxKit kit); }`; `SpellFxRegistry.register(Identifier spell, SpellFx fx)`,
    `SpellFx get(Identifier)` (unknown → `DefaultFx`: a cast circle plus an element burst).
  - `FxKit`: `circle(Vec3 centre, Vec3 normal, int argb, float radius, int rings, int lifeTicks)`; `trail(Vec3 from, Vec3 to, ParticleOptions p, int count)`;
    `burst(Vec3 at, ParticleOptions p, int count, double speed)`; `ring(Vec3 centre, double radius, ParticleOptions p, int count)`;
    `bolt(Vec3 from, Vec3 to, long seed, int argb)` (jagged, white core); `ghostBlock(BlockPos pos, BlockState state, Vec3 offset, int lifeTicks, GhostBlocks.Motion motion)`
    (a client-only block model with no world change; `enum Motion { STATIC, RISE, POP, FALL, ORBIT_IN }`); `simulate(int everyTicks, int durationTicks, IntConsumer onTick)`
    (client timing for lingering visuals, driven by the same pure geometry helper the server uses); `cancel(int casterId)`; `shake(float intensity, int ticks)`;
    `hitStop(int ticks)`; `fovKick(float amount)`. Every call counts against the particle and ghost-block budgets (`FxBudget`).
  - `RecallFx` (registered for `SpellIds.RECALL`): a gold horizontal circle at the caster's feet for `e.duration()` ticks, rings filling over the channel and
    rising motes; it stops on `FxCancel`.
  - `ChargeCircles`: every frame, for every player (local and remote) using a grimoire, draw a vertical circle 1.5 blocks in front of the eye and 0.25 below, facing
    the look vector. It has `ChargeMath.rings(useTicks)` rings, each fading in over 3 ticks with alternating rotation and runes on rings 2 and 4; radius
    `0.55 + 0.15 × rings`, alpha 0.55. At full charge it pulses and plays a local `BEACON_POWER_SELECT` (pitch 1.6) once. Colour comes from
    `GrimoireLook.element(useItem)`. For the local player with Recall selected, draw a gold feet circle instead (remote selections aren't synced).
  - `Shake` + `CameraShakeMixin` (`Camera.alignWithEntity` TAIL, alongside `CameraZoomMixin`): decaying offset scaled by `options.screenEffectScale()`.
    Hit-stop and FOV kick come from Wildercord.
  - `GrimoireParticles`: tier 3 grimoires in any player's hand within 32 blocks emit one element particle every 4 ticks.
  - `FxClient`: `CastFx` → `SpellFxRegistry.get(e.spell()).play(…)`; `FxCancel` → `FxKit.cancel(casterId)`.
- [ ] **Step 1: Port Wildercord.** Clone `https://github.com/defnotean/wildercord` at the 0.13.0-alpha tag (2026-10-15; record the SHA) into the scratchpad. Locate the
  magic-circle renderer, the additive light pipeline (`BlendFunction.LIGHTNING`), camera shake, hit-stop and FOV kick (grep `circle`, `BlendFunction`, `shake`, `hitStop`).
  Copy them into `MagicCircleRenderer`, `LightPipeline` and `Shake` with the header `// Adapted from Wildercord (MIT) https://github.com/defnotean/wildercord @<sha>`,
  and put the MIT text and SHA in `THIRD_PARTY_NOTICES.md`. Hook rendering where Wildercord does on 26.3 (`LevelRenderEvents`); check signatures in `genSources`.
- [ ] **Step 2: Write the failing tests**
  - `CircleMathTest`: `MagicCircleRenderer.ringRadius(0..4)` increases strictly from 0.55; ring alpha at ring age 0/1/2/3 ticks = 0, ⅓, ⅔, 1; basis vectors built from
    look (0,0,1) are orthonormal and perpendicular to it; look straight up (0,1,0) gives no NaN.
  - `FxBudgetTest`: requesting 500 particles in a non-ultimate fx grants 400; ultimate 2000 → 1500; the 129th ghost block is refused; budgets reset per fx event.
- [ ] **Step 3: Run** `scripts/heavy.sh ./gradlew test --no-daemon --tests 'local.carter.office.client.magic.*'` → FAIL.
- [ ] **Step 4: Implement** the rest; register `RecallFx` and the six empty `<Element>Fx.register()` calls in `MagicClient`.
- [ ] **Step 5: Run** → PASS; `scripts/heavy.sh ./gradlew build runGametest --no-daemon` → BUILD SUCCESSFUL.
- [ ] **Step 6: Commit** `feat(magic): summoning circles, glow pipeline, shake/hit-stop, FX dispatch, Recall visual (Wildercord MIT)`.

### Task 11: Pack swap — remove Wizards and its stack (independent; any time)

**Files:**
- Create: `scripts/check-pack-deps.py`
- Delete: `pack/mods/{wizards,spell-engine,spell-power}.pw.toml`, plus every other entry the script reports as needed only by them
  (candidates: `runes`, `bundle-api`, `armor-model-api`, `player-animation-library`, `trinkets-updated`, `cloth-config`, `structure-pool-api`)
- Modify: `pack/index.toml`, `pack/pack.toml` (version `0.2.0-dev`), `server/deploy.md` ("What is in the pack" and the dependency note; remove the Wizards handbook and its sources)

**Interfaces:**
- Produces: `scripts/check-pack-deps.py [pack-dir]`. It downloads each `pack/mods/*.pw.toml` jar into `~/Library/Caches/minecraft-office-tools/pack-jars/`
  (checking the listed hash) and reads `fabric.mod.json` (`id`, `provides`, `depends`, nested `jars`). It prints (a) missing required ids (exit 1) and
  (b) library mods nothing depends on. `minecraft`, `java`, `fabricloader`, `fabric-api` and `fabric-*` are always present.
- [ ] **Step 1:** Run `python3 scripts/check-pack-deps.py pack` on the current pack → "missing: none". Record which mods depend on each candidate.
- [ ] **Step 2:** Remove Wizards, Spell Engine and Spell Power with `~/Library/Caches/minecraft-office-tools/bin/packwiz remove <slug>` (inside `pack/`). Rerun the script
  and remove each candidate it now lists as unused **and** that only the removed mods required; keep anything a world or QoL mod needs. Run `packwiz refresh`.
- [ ] **Step 3: Verify:** `python3 scripts/check-pack-deps.py pack` → "missing: none" and no unused library left; `python3 scripts/check-pack.py pack` → "pack ok".
- [ ] **Step 4: Verify the pack still loads** (same procedure as the v1 Task 2 report). Serve `pack/` with `python3 -m http.server`, install with
  `packwiz-installer-bootstrap.jar -g -s server` into `~/Library/Caches/minecraft-office-tools/server-test/`, and start the Fabric 26.3 server through `scripts/heavy.sh`
  (Temurin 25, `-Xmx2G nogui`). Pass = the log has `Done (` and no "Incompatible mods" or "requires". Then `stop` and delete the test world. The client check is in Task 25.
- [ ] **Step 5: Commit** `git add scripts/check-pack-deps.py pack server/deploy.md && git commit -m "feat(pack): drop Wizards, Spell Engine, Spell Power and their unused deps"`.

**Phase M1 checkpoint:** `scripts/heavy.sh ./gradlew build test runGametest --no-daemon` → BUILD SUCCESSFUL and no failed GameTests; push feat/v1 → CI green.

---

## Element lanes — Phases M2 and M3 (6 lanes in parallel, each lane sequential)

Each element is one **lane**: one worktree and branch off feat/v1 after the M1 checkpoint. A lane does its M2 task, merges it into feat/v1 (Monday floor), then
continues with its M3 task on the same branch and merges again. Run at most **3 lanes at once**: every build goes through the `heavy.sh` lock, and each worktree
needs disk (the 4 GiB gate).

| Lane | M2 task (slots 0, 1) | M3 task (slots 2, 3, 4) |
|---|---|---|
| Fire | Task 12 | Task 13 |
| Water | Task 14 | Task 15 |
| Wind | Task 16 | Task 17 |
| Lightning | Task 18 | Task 19 |
| Earth | Task 20 | Task 21 |
| Shadow | Task 22 | Task 23 |

Lane contract (applies to every lane task):
- **Files:** only `mod/src/main/java/local/carter/office/magic/spell/<element>/`, `mod/src/client/java/local/carter/office/client/magic/fx/<element>/`,
  `mod/src/gametest/java/local/carter/office/magic/<Element>GameTests.java` and `mod/src/test/java/local/carter/office/magic/spell/<element>/`.
  Lang, textures, `SpellIds`, `MagicTestKit` and entrypoints are already complete. If a lane needs one of them changed, it stops and reports back.
- **Interfaces consumed:** `Spell`, `SpellCost`, `CastContext`, `CastOutcome`, `SpellIds`, `SpellKit`/`EditBatch`/`EditTier`, `Targets`, `TargetFilter`, `Hurt`, `Motion`,
  `Projectiles` (+ `step`), `ActiveEffects`/`LingeringEffect`, `Shapes`, `FxEvent`, `SpellFx`, `SpellFxRegistry`, `FxKit`, `MagicTestKit` (Tasks 1–10).
- One server class per spell, `implements Spell` with id `SpellIds.<NAME>`, the slot from the table and `SpellCost.forSlot`, registered in `<Element>Spells.register()`.
  One client `SpellFx` per spell, registered in `<Element>Fx.register()`.
- Each `cast` returns exactly one `CastOutcome.cast(fx)`. Put the charge-dependent sizes into `fx` (the event already carries `charge` and `power`; any extra
  goes in `data`, documented in the spell class javadoc).
- **Lingering spells** (marked *L* below) get one pure `<Spell>Geometry` class in `spell/<element>/` (Vec3 math only, no world access). The server effect and the
  client `FxKit.simulate` both call it, and a unit test pins it. Never send extra packets.
- Small spells never open an `EditBatch`. Mediums use `EditTier.MEDIUM`. A medium that travels or hits several places opens **one batch per segment or hit**
  (origin = that point), so no edit is more than 6 from its own origin. Ultimates use `EditTier.ULTIMATE` and `launch` for flying debris.
- Ultimate sizes scale as `min(cap, base + (level − 7))` where given. Every ultimate shakes ≥ 0.8 within 32 blocks and hit-stops the caster for 3 ticks.
- GameTests use `MagicTestKit` in `ARENA` with the caster at `STAND` facing +Z (`aim` for targets) and NoAI dummies. Health assertions are "≤ 20 − X".
- Steps per task: (1) write the tests listed; (2) run `scripts/heavy.sh ./gradlew build test runGametest --no-daemon` → FAIL; (3) implement the server spells and
  geometry; (4) implement the client fx; (5) run → PASS; (6) commit `feat(magic/<element>): <spells>`.

P = power; "ahead" = along the caster's look; distances in blocks, times in ticks. `desc` = the lang copy already in Task 2.

### Task 12: Fire lane, M2 — Fire Bolt, Fire Wall
- `fire_bolt` (0), desc "A fast bolt of flame that sets targets alight.": projectile speed 2.5, radius 0.3, max 20 ticks, no gravity; hit: 4×P damage, fire 60, knock 0.4. FX: flame trail, pop on hit.
- `fire_wall` (1, *L*), desc "Raise a curtain of fire that burns whatever crosses it.": a zone 4 ahead on the ground, perpendicular, width 5 (7 at charge ≥ 0.5), height 3,
  lasting 100 + 60×charge. Every 10 ticks entities inside take 2 + fire 80; projectiles entering are discarded. Minor permanent change: snow layers and ice in the
  footprint melt (snow → air, ice → water) in one MEDIUM batch. FX: flame curtain from `FireWallGeometry`.
- [ ] Tests: `FireWallGeometryTest` (footprint cells for width 5/7 are centred on the aim line, perpendicular, inside radius 6); GameTests `fireBoltHurtsAndIgnitesZombie`
  (zombie 6 ahead → health ≤ 16 within 10 ticks, on fire); `fireBoltStopsAtWall` (stone wall 3 ahead, zombie behind it unharmed); `fireWallBurnsZombieInside`
  (zombie 4 ahead → health < 20 and on fire by tick 30); `fireWallOnlyMeltsSnow` (stone and grass unchanged, snow layer gone); `fireWallSparesPlayer` (`otherPlayer` inside → full health, not burning).

### Task 13: Fire lane, M3 — Flame Dash, Fireball Barrage, Meteor
- `flame_dash` (2), desc "Burst forward on a trail of fire.": horizontal dash `look_h × (1.6 + 0.5×charge)` + 0.2 up, no fall damage for 40; entities within 1.5 of
  the caster in the next 8 ticks take 3×P + fire 40 (each once).
- `fireball_barrage` (3, *L*), desc "Rain fireballs on the spot you point at.": `3 + round(2×charge)` fireballs, one every 4 ticks, at the ray point ≤ 40 with ±1.5
  seeded spread; projectile speed 1.8, gravity 0.03. Each impact: 5×P within 2.5 (linear falloff), knock 0.8, its own MEDIUM batch with a `Shapes.bowl(1.5, 0.6)`
  crater, 20 % of the exposed floor → netherrack. FX: explosion puffs, shake 0.2 within 16.
- `meteor` (4, signs, *L*), desc "Call a burning rock from the sky — it leaves a crater.": target = ray point ≤ 64 (miss → ground 32 ahead). The rock falls from +40
  over 40 ticks. Impact: 20×P within 8 (falloff), knock 2.5 + 0.8 up. Permanent bowl radius `min(12, 7 + (level − 7))`, depth factor 0.6; 40 rim blocks are
  `launch`ed outward (≤ 48); floor 30 % magma block, 30 % blackstone. FX: sky circle at cast, flaming ghost-block rock, flash, shake 1.0.
- [ ] Tests: `BarrageGeometryTest` (impact points for a seed are identical across calls and within 1.5 of the aim point); `MeteorGeometryTest` (height at tick t
  falls linearly from +40 to 0 at tick 40); GameTests `flameDashMovesAndBurns` (caster ≥ 5 +Z within 10 ticks, zombie on the path burning, no fall damage);
  `barrageFiresThreeAtChargeZero` (3 impacts, 3 craters, each within 6 of its impact); `meteorCratersAndKills` (aimed at the floor 12 ahead; zombie at the target dead by
  tick 45; ≥ 100 blocks removed; none beyond 7 at level 7); `meteorRespectsClaimFallback` (emitter 20 from the target → 0 blocks changed, zombie still hurt).

### Task 14: Water lane, M2 — Water Bullet, Tidal Push
- `water_bullet` (0), desc "A hard jet of water that knocks foes back.": projectile speed 2.8, radius 0.25, max 20; hit: 3×P damage, knock 1.2 along travel, extinguishes the target.
- `tidal_push` (1, *L*), desc "A wave that shoves everything in front of you.": cone range 8 + 4×charge, half-angle 35°. Pushables get knock 1.6×P horizontal + 0.3 up;
  burning entities are extinguished; fire blocks in the cone are removed (one MEDIUM batch per 6-block segment). Temporary wave: a 5-wide front of temp water advancing
  1 block per 2 ticks for 8 blocks; each cell lives 6 ticks (air only), one batch per 4-block segment.
- [ ] Tests: `TidalWaveGeometryTest` (front cells at tick t are 5 wide at distance t/2, and each segment's cells are within 6 of its origin); GameTests
  `waterBulletKnocksBackZombie` (zombie 5 ahead moves ≥ 1 +Z within 10 ticks, health ≤ 17); `tidalPushPushesItemsAndMobs` (zombie + item 4 ahead both move ≥ 2 +Z);
  `tidalPushExtinguishesFire` (fire block 10 ahead in the cone → air); `tidalWaveLeavesNoWater` (`waterCount==0` at tick 100, flows included).

### Task 15: Water lane, M3 — Bubble Shield, Water Prison, Water Dragon
- `bubble_shield` (2, *L*), desc "A bubble that stops arrows and softens blows.": lasts 120 + 60×charge. Resistance I; each tick removes `Projectile` entities not owned by the caster within 2.5.
- `water_prison` (3, *L*), desc "Trap a foe in a sphere of water.": target by ray ≤ 24; a temp water sphere of radius 2 around it (air only) for 100 + 40×charge;
  the target gets Slowness III and is pulled to the centre each tick while inside.
- `water_dragon` (4, signs, *L*), desc "A dragon of water that crashes down and floods the land.": the dragon flies from the caster to the ray point ≤ 48 over 30 ticks
  (spline from the seed in `DragonGeometry`). Impact: 18×P within 6, knock 2.0. Permanent flood: water sources in air cells within radius `min(12, 6 + (level − 7))`,
  y ≤ impact y, each with solid or water below (≤ 600); grass and dirt tops within radius + 3 → mud at 40 % (≤ 12 from the origin). FX: water-particle dragon, splash, shake 0.8.
- [ ] Tests: `DragonGeometryTest` (spline starts at the caster, ends at the target at tick 30, same for one seed); GameTests `bubbleShieldDeletesArrows` (`arrow` aimed at the caster
  removed before it hits; caster health full); `waterPrisonHoldsZombieThenDrains` (zombie stays within 1 of start for 80 ticks; `waterCount==0` at tick 220);
  `waterDragonFloodsAndStays` (≥ 50 water sources remain at tick 200; none above impact y); `waterDragonNeverFloodsAChest` (`chest` cell untouched).

### Task 16: Wind lane, M2 — Gust, Blade Wind
- `gust` (0), desc "A blast of wind that throws foes back.": cone range 6 + 2×charge, half-angle 30°: 1 damage, knock 1.4×P + 0.4 up; items and XP orbs are pushed too.
- `blade_wind` (1, *L*), desc "Crescent blades that cut foes and foliage.": 3 blades in a ±15° fan (5 blades at ±25° at charge ≥ 0.5), projectile speed 2.0, radius 0.6,
  pierce, max 14; hit 5×P. Minor permanent change: blades cut `#minecraft:leaves`, `#minecraft:flowers`, short and tall grass, fern, large fern and dead bush along their path
  (one MEDIUM batch per blade per 6-block segment, ≤ 64 per cast). FX: white crescents from `Projectiles.step`.
- [ ] Tests: GameTests `gustLaunchesZombie` (zombie 3 ahead gains ≥ 0.3 upward velocity and moves +Z); `bladeWindPiercesTwoZombies` (zombies 3 and 6 ahead in line both hurt);
  `bladeWindCutsLeavesNotLogs` (oak leaves + log 10 ahead → leaves gone, log stays); `gustDoesNotPushPlayer` (`otherPlayer` 3 ahead doesn't move).

### Task 17: Wind lane, M3 — Air Step, Tornado, Wind Sphere
- `air_step` (2), desc "Leap off the air itself — even mid-jump.": castable mid-air: velocity y = 0.9 + 0.2×charge plus `look_h × 0.4`; no fall damage for 60.
- `tornado` (3, *L*), desc "A whirlwind that wanders forward, lifting everything.": starts on the ground at the ray point ≤ 20 and travels along `look_h` at 0.15/tick for
  120 + 40×charge. Pushables within 4 get tangential 0.35, inward 0.15 and up 0.25 per tick, and 1 damage every 10 ticks. No block changes. Path from `TornadoGeometry`.
- `wind_sphere` (4, signs, *L*), desc "A spinning sphere that drills through anything you hit.": the caster dashes 1.8/tick along the look for ≤ 10 ticks. The first entity within 1.5
  or block hit detonates it: 22×P within 4, knock 3.5. Permanent drill: a cone from the impact along the look (`DrillGeometry` orients `Shapes.cone` along the look),
  length `min(12, 8 + (level − 7))`, radius 3 → 1. ≤ 32 removed blocks are `launch`ed in a spiral; the rest are removed. FX: spinning sphere in hand, spiral shockwave, shake 0.9.
- [ ] Tests: `TornadoGeometryTest` (position at tick t = origin + dir × 0.15t); `DrillGeometryTest` (looking +Z, every cell has z ≥ 0 and radius ≤ 3; no cell beyond 12);
  GameTests `airStepWorksMidAir` (falling caster → upward velocity ≥ 0.9, no fall damage on landing); `tornadoLiftsZombie` (zombie gains ≥ 2 height by tick 40);
  `windSphereDrillsTunnel` (stone wall 6 ahead → ≥ 20 blocks removed, none beyond 12 from the impact); `windSphereStopsAtFirstZombie` (detonates at the zombie 4 ahead, wall at 8 untouched).

### Task 18: Lightning lane, M2 — Spark, Chain Lightning
- `spark` (0), desc "A quick zap that arcs to a second foe.": hitscan 16 (forgiveness 0.5): 3×P damage; it arcs to the nearest other target within 4 for 60 %. FX: `FxKit.bolt`, ids in `entities`.
- `chain_lightning` (1), desc "Lightning that leaps from foe to foe.": the first target by ray ≤ 20 (forgiveness 1.5), then hops to the nearest unhit target ≤ 6,
  `4 + round(2×charge)` hops, damage 6×P × 0.8^hop. Minor permanent change: sand directly under each hit target → glass (one MEDIUM batch per hit). FX: a bolt and flash per hop.
- [ ] Tests: `ChainHopsTest` (pure hop selection on points: nearest unhit, stops when none ≤ 6, never revisits); GameTests `sparkHitsZombie` (health ≤ 17);
  `chainLightningHopsFiveZombies` (5 zombies 3 apart, charge 0 → all hurt, damage non-increasing along the chain); `chainSkipsVillager` (villager in the chain unhurt, chain continues);
  `chainGlassesSandUnderTargets` (sand under each hit zombie → glass, other sand unchanged).

### Task 19: Lightning lane, M3 — Blink, Thunder Spear, Chidori Strike
- `blink` (2), desc "Flash forward in an instant.": teleport along the look up to 10 + 4×charge via `Motion.teleportSafe`, velocity zeroed, no fall damage for 20.
- `thunder_spear` (3), desc "Hurl a spear of thunder.": projectile speed 3.0, gravity 0.01, max 30. Impact: 10×P within 2.5 + Slowness II 40, plus a visual-only
  `LightningBolt` (`setVisualOnly(true)`). Minor permanent change: bowl radius 2 and sand → glass within 3 (one MEDIUM batch).
- `chidori_strike` (4, signs, *L*), desc "Charge through your target, carving a scorched trench.": target entity by ray ≤ 16 (forgiveness 2) or point ≤ 16; the caster moves along the path
  over 6 ticks (`ChidoriGeometry`). End: 24×P to the target, 8×P within 3, Slowness V + Weakness 60, 3 visual-only bolts. Permanent: a trench along the path (2 wide, 2 deep below the
  surface, edges 30 % blackstone, sand → glass; one ULTIMATE batch per 12-block segment) and a bowl of radius `min(12, 4 + (level − 7))` at the end.
- [ ] Tests: `ChidoriGeometryTest` (path points evenly spaced over 6 ticks; trench cells are within 1 of the path line); GameTests `blinkStopsBeforeWall` (wall 6 ahead → caster ends ≤ 5.5 ahead,
  not inside); `blinkIntoTunnelIsSafe` (2-high tunnel, no suffocation damage over 20 ticks); `thunderSpearStartsNoFire` (`count(FIRE)==0` at tick 60);
  `chidoriTrenchesPathAndHits` (zombie 12 ahead loses ≥ 20 health; trench cells along the path are air).

### Task 20: Earth lane, M2 — Stone Bullet, Earth Wall
- `stone_bullet` (0), desc "Fling a heavy stone.": projectile speed 2.2, gravity 0.02, radius 0.35, max 30; hit 5×P, knock 1.0. FX: stone-block particles along `Projectiles.step`.
- `earth_wall` (1, *L*), desc "Raise a wall of earth for half a minute.": a wall 3 ahead, perpendicular, width 5 (7 at charge ≥ 0.5), height 3 (4 at charge 1). Temp blocks use the block
  under each column if it is a full solid block, else cobblestone. It rises column by column over 6 ticks (`EarthWallGeometry`) and lasts 600; air or replaceable cells only;
  entities in a wall cell are pushed up onto it. FX: dust and rising ghost blocks.
- [ ] Tests: `EarthWallGeometryTest` (column order and heights for widths 5/7; all cells within 6 of the origin); GameTests `stoneBulletArcsAndHits` (zombie 10 ahead hurt);
  `earthWallRisesFromGroundMaterial` (grass floor → 15 temp cells of the floor material; cobblestone where the floor is slabs); `earthWallBlocksArrows` (`arrow` fired at the wall stops
  before passing it); `earthWallFallsAfterThirtySeconds` (tick 601 → all cells air); `earthWallNeverReplacesChest` (`chest` in a cell stays).

### Task 21: Earth lane, M3 — Earth Spikes, Quake, Mountain Fist
- `earth_spikes` (2, small, *L*), desc "Spikes burst from the ground in a line.": a line of 8 + 4×charge cells ahead along the ground (`Shapes.line`), one per tick. Entities on a cell
  take 4×P + 0.6 up. FX only: dripstone ghost blocks rise for 60 ticks; no block changes.
- `quake` (3, *L*), desc "A shockwave that ripples out through the ground.": a ring expanding from the caster 1 block per 2 ticks to radius 6 + 2×charge (`QuakeGeometry`). Grounded
  entities on the ring take 5×P, 0.7 up and knock 0.6 outward. Minor permanent change: 25 % of ring surface blocks crack (grass block → dirt, stone → cobblestone, dirt → coarse dirt;
  ≤ 120, one MEDIUM batch per 60° arc beyond radius 6, origin on the arc). FX: real ground ghost blocks pop up and fall back along the ring.
- `mountain_fist` (4, signs, *L*), desc "A giant fist of rock erupts and leaves a mountain spire.": target = ground at the ray point ≤ 32; a 20-tick rise. Entities within 5 take 24×P and are
  launched 1.6 up. Permanent spire: `Shapes.cone(min(6, 3 + (level − 7)), min(12, 8 + 2×(level − 7)))` of 60 % stone, 25 % the target's surface block and 15 % andesite, air or
  replaceable cells only, placed bottom-up over 20 ticks (≤ 900). FX: a giant fist of ghost blocks, shake 1.0.
- [ ] Tests: `QuakeGeometryTest` (ring radius at tick t = t/2; arc origins keep each cell within 6); GameTests `earthSpikesChangeNoBlocks` (`blockHash` unchanged at tick 80; zombie on
  the line hurt); `quakeLaunchesGroundedNotFlying` (grounded zombie goes up, a flying bat is untouched); `quakeCracksAtMostQuarter` (cracked cells ≤ 25 % of the ring + 1);
  `mountainFistBuildsSpire` (level 7: ≥ 50 blocks placed, height ≤ 8, none in a `chest` cell, zombie launched).

### Task 22: Shadow lane, M2 — Shadow Bolt, Gravity Well
- `shadow_bolt` (0), desc "A bolt of shadow that saps strength.": projectile speed 2.2, radius 0.3, max 24; hit 4×P + Weakness I 60. FX: smoke and portal trail.
- `gravity_well` (1, *L*), desc "A dark point that drags everything in.": point by ray ≤ 16 (air, 1 above ground); lasts 80 + 40×charge. Pushables within 6 are pulled toward the centre
  (`WellGeometry.pull`: 0.12 + 0.03 × distance per tick, capped 0.6); 1 damage every 20 ticks within 2. No block changes. FX: dark core and inward spiral.
- [ ] Tests: `WellGeometryTest` (the pull points at the centre, magnitude capped at 0.6, zero at the centre); GameTests `shadowBoltWeakensZombie` (Weakness present, health ≤ 16);
  `gravityWellPullsZombiesAndItems` (zombie 5 from the centre ends < 2 from it by tick 60, item too); `gravityWellChangesNoBlocks` (`blockHash` unchanged);
  `gravityWellIgnoresPlayers` (`otherPlayer` 4 from the centre doesn't move).

### Task 23: Shadow lane, M3 — Shadow Step, Shadow Bind, Black Hole
- `shadow_step` (2), desc "Step out of the shadows behind your target.": target by ray ≤ 16 (forgiveness 1.5) → teleport 1.5 behind it facing it (`teleportSafe`); with no target, 8 ahead.
  Invisibility for 20.
- `shadow_bind` (3, *L*), desc "Shadows root a foe in place.": target by ray ≤ 20 takes 4×P and is rooted for 60 + 40×charge (Slowness amplifier 5, velocity zeroed each tick).
  FX: tendrils for the duration.
- `black_hole` (4, signs, *L*), desc "A black hole that devours the land around it.": at the ray point ≤ 24 (2 above ground); lasts 100. Pushables within 12 are pulled
  (0.08 → 0.25 over its life); 4 damage every 10 ticks within 2.5. Permanent devour: a sphere growing from 1 to `min(12, 6 + (level − 7))` over its life (`BlackHoleGeometry`,
  ≤ 1500); 10 % of the rim → crying obsidian or blackstone (half each). At the end: knock 2.0 outward + 10×P within 6. FX: lensing core, inward debris ghost blocks, shake 1.0.
- [ ] Tests: `BlackHoleGeometryTest` (radius at tick 0 = 1, at tick 100 = cap, monotonic); GameTests `shadowStepLandsBehindZombie` (caster ends on the far side, ≤ 2 from the zombie,
  facing it); `shadowBindRootsZombie` (moves < 0.5 in 60 ticks); `blackHoleDevoursSphereWithinCap` (level 7: ≥ 200 blocks removed, none beyond 6.5 from the centre);
  `blackHoleSparesBlockEntities` (a furnace inside the sphere is still there).

**Monday floor checkpoint (after Tasks 12, 14, 16, 18, 20, 22 merge):** `scripts/heavy.sh ./gradlew build test runGametest --no-daemon` green; push feat/v1 → CI client gametests green;
build the jar and run `scripts/play-test.sh` for Carter (grimoire, choice, wheel, charge circles, mana, Recall, 2 spells per element; check dashes for rubber-banding on the real server).

---

## Final

### Task 24: Balance pass + client GameTests

**Files:**
- Modify: `BalanceGameTests.java`, `MagicClientGameTests.java`, `magic/core/Slots.java` and spell classes (numbers only), this plan's Builder numbers (record the final values)

- [ ] **Step 1: Write the tests.**
  - `BalanceGameTests`: `everyElementHasFiveSpellsAndRecall` (`wheel(e)` has no nulls for every element); `everySpellCastsOnceWithOneFx` (each of the 31 spells at level 10 in a fresh
    arena: Result CAST, or CHALLENGED and then CAST via `castUltimate`; exactly 1 fx per cast); `smallSpellsNeverEditBlocks` (`blockHash` identical after every slot 0 and 2 spell);
    `noSpellExceedsBudgets` (per cast ≤ 64 entities hurt, edits ≤ tier budget, falling blocks ≤ 48); `zombieTimeToKillBands` (level 1, charge 0: fire_bolt, water_bullet, spark,
    stone_bullet and shadow_bolt each kill a 20-hp zombie in **3–8 casts**. The 3-damage spells need 7, and 8 allows one miss while still catching a halving or doubling of damage.)
  - `MagicClientGameTests`: `chargeCircleGrowsWhileHeld` (hold right-click 40 ticks: mana unchanged before release; screenshot pixels in the circle's projected rect differ from the
    same frame before charging on ≥ 20 %); `tapCastsQuickly` (press and release within 5 ticks → mana −8); `handSignPromptAppearsForUltimate` (level 7, select slot 4, cast →
    `HandSignScreen` open; type the sequence → mana −60); `typingModeBlocksMagic` (enter typing mode → R doesn't open the wheel and right-click doesn't cast);
    `screensFitAtGuiScale4` (at GUI scale 4 on an 854×480 window: `ChoiceLayout`, `GrimoireLayout`, `WheelLayout`, `SignLayout` and `ManaBarLayout` rects all lie within the screen,
    and each widget rect in its screenshot has ≥ 20 % non-background pixels).
- [ ] **Step 2: Run** `scripts/heavy.sh ./gradlew build test runGametest --no-daemon`; tune only numbers until it passes, and keep each change inside the spec caps.
- [ ] **Step 3: Push feat/v1; CI client gametests green** (`gh run watch`); check the screenshots artifact.
- [ ] **Step 4: Commit** `test(magic): balance pass + client gametests`.

### Task 25: Friend docs and release

**Files:**
- Modify: `docs/friends/SETUP.md` (§9 "Magic in 10 minutes" rewritten for the grimoire), `server/deploy.md` (handbook text), `gradle.properties` (`version=0.2.0`),
  `pack/pack.toml` (`0.2.0`), `pack/mods/office.pw.toml` (0.2.0 release asset)

- [ ] **Step 1:** Rewrite §9. Craft a Grimoire (book + feather + iron ingot, shown in the recipe book) and right-click it to choose an element. Hold it in the off hand with an empty
  main hand: tap right-click to cast, hold to charge. Pick spells with the R wheel or Shift + scroll. Casting levels the grimoire up; ultimates come at level 7 and need hand signs
  (arrow keys or WASD). Recall: Shift + cast to mark home, cast to go home (3 s, 60 s cooldown). Claims protect offices from big spells.
- [ ] **Step 2:** Release per `RELEASING.md` (tag `v0.2.0`, CI release, `scripts/release.sh`, pack commit pointing `office.pw.toml` at 0.2.0). Replace the jar on the server, remove the
  Wizards stack from the server's `/mods` (list from Task 11) and restart.
- [ ] **Step 3: Verify:** `python3 scripts/check-pack.py pack` → "pack ok"; `python3 scripts/check-pack-deps.py pack` → "missing: none"; the server log shows `Done (` with the new jar;
  `scripts/play-test.sh` launches, Mod Menu lists no Wizards or Spell Engine, and the grimoire is craftable.
- [ ] **Step 4: Commit** `docs+release: grimoire magic 0.2.0`.

---

## Self-review notes

- Spec coverage: §0 recipe, casting and timing → Tasks 2, 4, 8, 10 and the checkpoints; §3 element choice → 3, 9; wheel → 8; hand signs → 6; mana → 3, 4, 9;
  progression and evolving looks → 1, 2, 4, 10; 30 spells → lanes 12–23; world effects → 5 + lanes; Recall → 7 (+ visual in 10); §4 look and performance → 10, budgets, 24;
  §5 reuse + pack → 10, 11; §6 holding, unlocks, terrain, recall, precedence and pack → 4, 5, 7, 8, 11; Rebinding Seal → 2, 3.
- Compile order: Task 3 owns every type Tasks 4–10 and the lanes reference (SPI, registry, kit, terrain interfaces, `CastService` signatures, the complete `MagicTestKit`);
  Tasks 4–7 fill in bodies only.
- Single sources: costs `Slots.baseCost`; home `MagicData.home`; colours `Element`; ids `SpellIds`; lingering geometry `<Spell>Geometry`.
- Open risks: the OPAC 26.3 API shape (Task 5 Step 1 gate, fallback ready); Wildercord render hooks may not map 1:1 onto `LevelRenderEvents` (Task 10); falling debris can land in a
  claim and its landing isn't undoable; player dashes may rubber-band on the real server (Monday checkpoint); all lanes share one `heavy.sh` lock, so builds serialise.
