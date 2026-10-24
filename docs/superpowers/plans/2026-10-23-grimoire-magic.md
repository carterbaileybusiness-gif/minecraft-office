# Grimoire Magic Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Wizards with our own Grimoire magic: one vanilla-crafted book held in the off hand, an element chosen
on first open, a 6-slice spell wheel, tap/hold-to-charge casting from growing vertical summoning circles, hand signs for
ultimates that permanently reshape (claim-safe, undoable) terrain, and Recall home. Monday floor first, all 30 spells by Sunday.

**Architecture:** Server-authoritative inside the existing `office` mod. Casting rides vanilla item use: the grimoire is
a use-duration item (like a bow), so the server measures charge in `releaseUsing`, vanilla keeps main-hand
block/entity interactions first, and remote clients see the charge without extra packets. Each spell is one class behind
a small SPI (`Spell` + client `SpellFx`); the server sends **one `CastFx` payload per cast** and clients build every visual
from it. Pure rules (mana, charge, levels, signs, wheel, terrain verdicts) live in `magic/core` with no Minecraft imports.

**Tech Stack:** Minecraft 26.3, Fabric Loader 0.20.1, Fabric API 0.165.0+26.3 (data-attachment sync, `HudElementRegistry`,
`LevelRenderEvents`, `KeyMappingHelper`, `ClientHotbarScrollEvents`), Loom 1.19.3 (unobfuscated, Mojang names), Java 25,
JUnit 5, Fabric GameTest + client GameTest, Open Parties and Claims API (compile-only, optional), packwiz.

**Spec:** `docs/superpowers/specs/2026-10-23-grimoire-magic-design.md` (section 0 overrides the rest). Context:
`docs/superpowers/specs/2026-10-23-minecraft-office-server-design.md` §5.9 (in-world monitor input), research
`.superpowers/sdd/2026-10-23-minecraft-office-v1/magic-research.md`.

## Global Constraints

Spec (verbatim values):
- Recipe: **book + feather + iron ingot** → Grimoire (shapeless). Re-binding: **nether star + book** → Rebinding Seal.
- Casting: grimoire in the **off hand**, **empty main hand**: **tap right-click** = quick cast of the selected spell,
  **hold** = summoning circle grows (charge), **release** fires a stronger version. Tool/block/any item in the main hand →
  right-click behaves normally (no magic). Select with **Shift + scroll** or the **R** wheel. **No V cast key.**
- Elements: Fire, Water, Wind, Lightning, Earth, Shadow. Chosen on first open; permanent unless a Rebinding Seal is used.
- Per element 5 spells: 2 small, 2 medium, 1 ultimate (hand signs). All from your element, unlocked by grimoire level; no
  tomes; no cross-element spells except **Recall** (everyone, from level 1).
- World effects: **small = particles + knockback only** (no block changes, not even temporary); **medium = temporary or
  minor block changes, radius ≤ 6**; **ultimate = permanent, radius ≤ 12**. Never block entities, bedrock or obsidian, never
  inside another player's claim (Open Parties and Claims; fallback: nothing within **32 blocks** of any hologram emitter or
  another player's bed/home), never in spawn protection. Op command **`/magic undo <player> [n]`**; edits logged to disk.
- Recall: one home per player, any dimension. **Mark Home = Shift + cast Recall**. Recall = **3 s channel** with a circle
  under you, **cancelled by taking damage**, **60 s cooldown**, **no mana**. Waystones stay.
- Hand signs: **4–6 arrow prompts**, typed with arrow keys or WASD while the prompt is open (movement paused); miss →
  fizzle with a sound, mana partly spent. Ultimates also cost **XP levels**.
- Input precedence: aiming at a hologram → **clicks belong to the monitor**; R still works; **typing mode blocks all magic**.
- Look: vertical circles (layered rings appearing one by one, runes, element colour, additive glow), screen shake +
  hit-stop on big hits; screens polished, animated, **no scrolling, readable at any GUI scale**.
- Performance: **one effect packet per cast**, particles client-side, block changes batched; smooth on the 8 GB M1 with 5 players.
- Reuse with attribution: **Wildercord (MIT)** circle rendering, glow pipeline, screen shake. Wheel and grimoire screens are ours.
  Iron's Spells / Ars Nouveau / Wizards / Spell Engine: ideas only, never code.
- Pack: remove Wizards, Spell Engine, Spell Power and every dependency only they need (Better Combat is not in the pack).

Repo rules:
- Packages `local.carter.office.magic` (main) and `local.carter.office.client.magic` (client); mod id `office`.
- Heavy commands only through `scripts/heavy.sh`; Gradle `--no-daemon`; never beside the game; never `pkill java`; no
  osascript automation. Client GameTests run in CI only (Linux/xvfb); locally run `build test runGametest`.
- 26.3 API: SDL3 mouse buttons (`InputConstants.MOUSE_BUTTON_RIGHT` = 3); GUI draws in `extractRenderState(GuiGraphicsExtractor …)`;
  `mc.gui.screen()`; render-state renderers and `LevelRenderEvents`; no raw GL. Never guess a signature: check `genSources`.

Builder numbers (spec §7 left them to us; every task uses these):
- Levels 1–10. `xpToNext(level) = 20 × level`. Tier (looks) = 0 for L1–2, 1 for L3–4, 2 for L5–6, 3 for L7+.
- Slots: 0 small A (unlock L1), 1 medium A (L2), 2 small B (L3), 3 medium B (L5), 4 ultimate (L7), 5 Recall (L1).
- Default cost per slot (mana / cooldown ticks / grimoire xp / XP levels): 0 = 8/10/2/0, 1 = 25/60/5/0, 2 = 12/40/2/0,
  3 = 30/100/5/0, 4 = 60/600/20/3, 5 = 0/1200/0/0 (Recall applies its cooldown itself, Task 7).
- Mana: `max = 100 + 10 × (level − 1)`; +1 every `max(2, 6 − level / 2)` ticks (integer division); regen paused 20 ticks after a cast.
- Charge: tap = released before **8** ticks; `charge = clamp((ticks − 8) / 32, 0, 1)` (full at 40 ticks / 2 s);
  rings shown `1 + floor(charge × 4)` (1–5); mana cost `round(base × (1 + 0.5 × charge))`, charge lowered to what you can afford.
- Power: `(1 + charge) × (1 + 0.05 × (level − 1))`; ultimates always use charge 1. Damage values below are "× P" (power).
- Hand signs: length 6 at L7, 5 at L8, 4 at L9–10; limit `20 + 12 × length` ticks (+10 ticks server grace); no two equal
  arrows in a row; fizzle = `ceil(manaCost / 2)` spent, 100-tick cooldown, no XP levels spent.
- Budgets per cast: ≤ 64 entities affected, ≤ 48 falling blocks; medium edits ≤ 256 blocks, ultimate ≤ 1500; global
  block applier ≤ 256 changes/tick; ≤ 32 lingering effects server-wide; client fx ≤ 400 particles (ultimate ≤ 1500), ≤ 128 ghost blocks.
- `CastFx` range: 64 blocks (128 for ultimates). Spells never hurt or push players (any), tamed pets, villagers, wandering
  traders, iron golems, armor stands, displays, hanging entities, vehicles; push spells also move items and XP orbs.
- Element colours (ARGB): Fire `FFFF6A1A`, Water `FF2F8CFF`, Wind `FF9CF2C8`, Lightning `FFFFE94A`, Earth `FFB0814A`,
  Shadow `FF8A3CFF`; Recall gold `FFFFD27A`; circle core white.
- Wheel: **6 slices** (5 element spells + Recall at slice 5). Deviation from spec §6 "5 slots": Recall needs a slot and the
  wheel must stay the one selector.

## Review Focus

1. Empty main hand, grimoire in off hand, right-click on a chest, door, bed, lever or villager → the vanilla interaction
   happens, no spell. Test in Task 8 (client gametest `emptyHandChestStillOpens`).
2. Main hand holds a sword, food, block or shield with the grimoire in the off hand → eat/place/block as normal, never a
   charge; switching to a tool mid-charge cancels with no mana spent. Tests in Task 4.
3. `/magic undo` after someone rebuilt part of a crater → only untouched blocks are restored; the new blocks stay. Test in Task 5.
4. Server stops or crashes while temporary blocks (Water Prison, Earth Wall) exist → they are gone after the next start;
   nobody is left encased. Tests in Task 5.
5. Death, disconnect or dimension change during a charge, a Recall channel or a hand-sign prompt → no cast, no mana lost,
   channel and challenge discarded. Tests in Tasks 6 and 7.

**Precondition for Task 8:** the in-world monitor input (spec §5.9: `client/pointer/PointerClient`, `TypingMode`) is merged
into feat/v1. If it is not, Task 8 reports BLOCKED; every other task can proceed.

---

## File Structure

```
mod/src/main/java/local/carter/office/magic/
  MagicMod.java                       # init: items, attachments, payloads, receivers, ticks, commands, element spells
  core/ (pure Java, no Minecraft imports)
    Element.java  Dir.java  Slots.java  LevelCurve.java  ManaMath.java  ChargeMath.java  HandSigns.java
    WheelMath.java  ScrollSelect.java  Shapes.java (Cell)  TerrainRules.java  GrimoireTier.java
  item/  MagicItems.java  GrimoireItem.java  RebindingSealItem.java  GrimoireLook.java
  data/  MagicData.java  MagicAttachments.java  MagicHomes.java (SavedData)
  net/   MagicPayloads.java  FxEvent.java  MagicSender.java  MagicServer.java
  cast/  CastService.java  SignChallenges.java  RecallSpell.java  ElementChoice.java
  spell/ Spell.java  SpellCost.java  CastContext.java  CastOutcome.java  SpellRegistry.java  SpellKit.java
         Targets.java  TargetFilter.java  Hurt.java  Motion.java  Projectiles.java  ActiveEffects.java
         fire/FireSpells.java  water/WaterSpells.java  wind/WindSpells.java  lightning/LightningSpells.java
         earth/EarthSpells.java  shadow/ShadowSpells.java      # + one class per spell (Phase M2/M3)
  terrain/ TerrainEdits.java  EditBatch.java  EditTier.java  TempBlocks.java  EditLog.java
           ClaimCheck.java  OpacClaims.java  FallbackClaims.java
  command/ MagicCommand.java
mod/src/client/java/local/carter/office/client/magic/
  MagicClient.java  MagicInputGate.java  MagicKeys.java  SpellWheel.java  SpellWheelHud.java
  screen/ ElementChoiceScreen.java  GrimoireScreen.java  HandSignScreen.java
  hud/    ManaBarHud.java
  fx/     FxClient.java  SpellFx.java  SpellFxRegistry.java  FxKit.java  DefaultFx.java  MagicCircleRenderer.java
          LightPipeline.java  ChargeCircles.java  GhostBlocks.java  Shake.java  GrimoireParticles.java
          fire/FireFx.java  water/WaterFx.java  wind/WindFx.java  lightning/LightningFx.java  earth/EarthFx.java  shadow/ShadowFx.java
mod/src/client/java/local/carter/office/mixin/client/
  CameraShakeMixin.java  MouseHandlerWheelMixin.java  (modify) MinecraftUseItemMixin.java
mod/src/main/resources/
  assets/office/items/{grimoire,rebinding_seal}.json  assets/office/models/item/grimoire_t{0..3}.json, rebinding_seal.json
  assets/office/textures/item/  assets/office/textures/gui/magic/  assets/office/textures/gui/spell/  assets/office/textures/misc/magic/
  assets/office/lang/en_us.json (modify)
  data/office/recipe/{grimoire,rebinding_seal}.json  data/office/advancement/recipes/misc/grimoire.json
  data/office/tags/block/magic_immune.json
mod/src/gametest/java/local/carter/office/magic/
  MagicTestKit.java  MagicCoreGameTests.java  TerrainGameTests.java  HandSignGameTests.java  RecallGameTests.java
  {Fire,Water,Wind,Lightning,Earth,Shadow}GameTests.java
mod/src/gametest/java/local/carter/office/client/MagicClientGameTests.java
mod/src/gametest/resources/data/office-test/structure/magic_arena.nbt
mod/src/test/java/local/carter/office/magic/core/*Test.java  …/client/magic/*Test.java  …/magic/spell/<element>/*Test.java
tools/make_magic_textures.py  tools/make_magic_arena.py
scripts/check-pack-deps.py  THIRD_PARTY_NOTICES.md
pack/mods/*.pw.toml (remove), pack/index.toml, pack/pack.toml, server/deploy.md, docs/friends/SETUP.md
```

Shared-file rule for parallel phases: Phase M1 creates every shared file (registration stubs, lang keys for all 31 spells,
all textures, gametest entrypoints). M2/M3 tasks touch **only** their element's `spell/<element>/`, `fx/<element>/`,
`<Element>GameTests.java` and `test/.../spell/<element>/` files.

---

## Phase M1 — shared core (sequential; Task 11 may run any time)

### Task 1: Pure magic rules

**Files:**
- Create: `mod/src/main/java/local/carter/office/magic/core/{Element,Dir,Slots,LevelCurve,ManaMath,ChargeMath,HandSigns,WheelMath,ScrollSelect,Shapes,TerrainRules,GrimoireTier}.java`
- Test: `mod/src/test/java/local/carter/office/magic/core/{LevelCurveTest,ManaMathTest,ChargeMathTest,HandSignsTest,WheelMathTest,ScrollSelectTest,ShapesTest,TerrainRulesTest}.java`

**Interfaces:**
- Produces:
  - `enum Element { FIRE, WATER, WIND, LIGHTNING, EARTH, SHADOW; String key(); int argb(); static Optional<Element> byKey(String) }`
  - `enum Dir { UP, DOWN, LEFT, RIGHT }`
  - `Slots`: `SMALL_A=0, MEDIUM_A=1, SMALL_B=2, MEDIUM_B=3, ULTIMATE=4, RECALL=5, WHEEL_SLICES=6`; `int unlockLevel(int slot)`;
    `int[] baseCost(int slot)` → `{mana, cooldownTicks, xp, xpLevels}` (values in Global Constraints).
  - `LevelCurve`: `MAX_LEVEL=10`; `int xpToNext(int level)`; `record Progress(int level, int xp, int levelsGained)`;
    `Progress add(int level, int xp, int gained)` (multi-level, xp stops at 0 at MAX_LEVEL).
  - `ManaMath`: `int max(int level)`; `int regenInterval(int level)`; `REGEN_PAUSE_TICKS=20`.
  - `ChargeMath`: `TAP_TICKS=8, FULL_TICKS=40`; `float charge(int ticksHeld)`; `int rings(int ticksHeld)`;
    `int cost(int base, float charge)`; `float affordableCharge(int mana, int base, float charge)` (largest step of 1/32 ≤ charge, or −1 if even base unaffordable);
    `float power(float charge, int level)`.
  - `HandSigns`: `int length(int level)`; `int limitTicks(int length)`; `GRACE_TICKS=10`; `Dir[] sequence(long seed, int length)`
    (uses `java.util.Random(seed)`, no immediate repeats); `boolean matches(Dir[] expected, Dir[] answer)`.
  - `WheelMath`: `DEAD_ZONE=14, SELECT_MIN=4, MAX_RADIUS=60`; `int sliceAt(double dx, double dy, int slices)` (−1 inside dead
    zone; slice 0 centred straight up, clockwise); `double[] clamp(double dx, double dy)`.
  - `ScrollSelect`: `<T> T next(List<T> unlocked, T current, double scrollDy)` (dy < 0 → next, dy > 0 → previous, wraps;
    unknown current → first).
  - `Shapes`: `record Cell(int x, int y, int z)`; `List<Cell> sphere(double r)`, `bowl(double r, double depthFactor)`,
    `cone(double baseR, int h)` — offsets sorted by distance from origin, never beyond `r`.
  - `TerrainRules`: `enum Verdict { ALLOW, BLOCK_ENTITY, IMMUNE, CLAIMED, SPAWN_PROTECTED, OUTSIDE_BORDER, BEYOND_RADIUS, OVER_BUDGET }`;
    `Verdict decide(boolean blockEntity, boolean immune, boolean claimed, boolean spawnProtected, boolean insideBorder,
    double distance, double radiusCap, int used, int budget)` (checked in that order).
  - `GrimoireTier`: `int of(int level)`.

- [ ] **Step 1: Write the failing tests**
  - `LevelCurveTest`: `xpToNext(1)==20`, `xpToNext(9)==180`; `add(1,15,10)` → `(2,5,1)`; `add(1,0,200)` → level 5 (20+40+60+80=200), xp 0, gained 4; `add(10,0,999).xp()==0 && level==10`.
  - `ManaMathTest`: `max(1)==100`, `max(10)==190`; `regenInterval` = 6,5,5,4,4,3,3,2,2,2 for levels 1..10.
  - `ChargeMathTest`: `charge(7)==0`, `charge(8)==0`, `charge(24)==0.5f`, `charge(40)==1`, `charge(400)==1`; `rings(0)==1`, `rings(40)==5`;
    `cost(25,1)==38`, `cost(8,0)==8`; `affordableCharge(30,25,1)==0.4375f` (14/32: cost 30; 15/32 would cost 31);
    `affordableCharge(7,8,0)==-1`; `power(1,10)` = 2 × 1.45 within 1e-6.
  - `HandSignsTest`: lengths 6/5/4/4 for levels 7/8/9/10; `limitTicks(6)==92`; `sequence(42,6)` equals itself on a second call,
    has 6 entries and no two equal neighbours (check 1000 seeds); `matches` false on wrong order or length.
  - `WheelMathTest` (screen coords, y down; slice centres every 60° clockwise from up): (0,−30)→0, (26,−15)→1, (26,15)→2,
    (0,30)→3, (−26,15)→4, (−26,−15)→5; `sliceAt(5,5,6)==-1`; `clamp(300,0)` → `{60,0}`.
  - `ScrollSelectTest`: list `[a,b,c]`: `next(l,"c",-1)=="a"`, `next(l,"a",1)=="c"`, `next(l,"z",-1)=="a"`.
  - `ShapesTest`: `sphere(1)` has 7 cells; every cell of `sphere(12)` has distance ≤ 12; `bowl(6,0.6)` has no cell with y > 0 and none with y < −4;
    first cell of every shape is (0,0,0) or nearest to it.
  - `TerrainRulesTest`: one case per verdict in priority order (e.g. block entity + claimed → `BLOCK_ENTITY`); distance 6.01 with cap 6 → `BEYOND_RADIUS`; used 256 budget 256 → `OVER_BUDGET`.
- [ ] **Step 2: Run** `scripts/heavy.sh ./gradlew test --no-daemon --tests 'local.carter.office.magic.core.*'` → FAIL (classes missing).
- [ ] **Step 3: Implement** the classes above. `Element.argb()` uses the Global Constraints colours. No Minecraft imports in `core/`.
- [ ] **Step 4: Run** the same command → PASS.
- [ ] **Step 5: Commit** `git add mod/src/main/java/local/carter/office/magic/core mod/src/test/java/local/carter/office/magic/core && git commit -m "feat(magic): pure rules — levels, mana, charge, signs, wheel, shapes, terrain verdicts"`

### Task 2: Grimoire and Rebinding Seal items, recipes, looks, textures, lang

**Files:**
- Create: `magic/item/{MagicItems,GrimoireItem,RebindingSealItem,GrimoireLook}.java`, `magic/MagicMod.java` (items only for now)
- Create: `tools/make_magic_textures.py`; resources listed in File Structure (items, models, textures, recipes, advancement, tag)
- Modify: `mod/src/main/java/local/carter/office/OfficeMod.java` (call `MagicMod.init()`), `assets/office/lang/en_us.json`
- Test: `mod/src/gametest/java/local/carter/office/magic/MagicCoreGameTests.java` (create), `mod/src/gametest/resources/fabric.mod.json` (register all magic gametest classes now: `MagicCoreGameTests, TerrainGameTests, HandSignGameTests, RecallGameTests` and the six `<Element>GameTests` as empty classes)

**Interfaces:**
- Consumes: `Element`, `GrimoireTier` (Task 1).
- Produces: `MagicItems.GRIMOIRE`, `MagicItems.REBINDING_SEAL` (`Item`, keys `office:grimoire`, `office:rebinding_seal`);
  `GrimoireItem.isGrimoire(ItemStack)`; `GrimoireLook.stamp(ItemStack, Optional<Element>, int level)` sets
  `CUSTOM_MODEL_DATA` = strings `["t<tier>"]`, colors `[element argb or FF8B5A2B unbound]`; `GrimoireLook.element(ItemStack)` → `Optional<Element>`.
  Lang keys (all written now, M2/M3 never touch lang): `item.office.grimoire`, `item.office.rebinding_seal`,
  `element.office.<key>` and `element.office.<key>.motto`, `spell.office.<id>` and `spell.office.<id>.desc` for all 31 spell ids
  (table in Phase M2/M3 + `recall`), `magic.office.*` message keys used by Tasks 3–7 (copy given there).
  Textures: `textures/gui/spell/<id>.png` (32×32, element-coloured glyph) for all 31 ids; `textures/gui/magic/sigil_<key>.png` (64×64),
  `book_open.png`, `padlock.png`, `wheel_slice.png`, `glow.png`; `textures/misc/magic/ring_{0..4}.png`, `runes.png` (8×1 atlas);
  `textures/item/grimoire_cover.png` (greyscale, tinted) + `grimoire_t{0..3}.png` overlays (pages glow, trim, gems).

- [ ] **Step 1: Write the failing GameTests** in `MagicCoreGameTests`:
  - `grimoireRecipeUsesBookFeatherIron`: look up recipe `office:grimoire` via `server.getRecipeManager()`; crafting input
    `[book, feather, iron_ingot]` in any order yields 1 `office:grimoire`; `[book, feather]` yields nothing.
  - `rebindingSealRecipe`: `[nether_star, book]` → 1 `office:rebinding_seal`.
  - `grimoireStampsLookFromData`: `GrimoireLook.stamp(stack, Optional.of(FIRE), 7)` → model strings `["t3"]`, colors `[0xFFFF6A1A]`; unbound level 1 → `["t0"]`, `[0xFF8B5A2B]`.
  - `grimoireStacksToOneAndIsNotAShield`: max stack size 1; `stack.has(DataComponents.BLOCKS_ATTACKS)` false.
- [ ] **Step 2: Run** `scripts/heavy.sh ./gradlew build runGametest --no-daemon` → FAIL (no items).
- [ ] **Step 3: Implement.** `GrimoireItem`: `stacksTo(1)`, `Rarity.UNCOMMON`, component `USE_EFFECTS = new UseEffects(false, false, 0.6f)`,
  `getUseDuration` 72000, `getUseAnimation` `NONE`; `use`/`releaseUsing`/`onUseTick`/`inventoryTick` bodies come in Tasks 3–4
  (return `PASS` for now). Item model `items/grimoire.json`: `minecraft:select` on `custom_model_data` string index 0 (`t0..t3`
  → `item/grimoire_t0..t3`), each model layer0 = cover tinted by `custom_model_data` colour index 0, layer1 = tier overlay.
  Recipes: shapeless; advancement unlocks the grimoire recipe on having a book or an iron ingot. Tag `office:magic_immune`:
  bedrock, obsidian, crying_obsidian, reinforced_deepslate, end_portal_frame, end_portal, end_gateway, nether_portal, barrier,
  light, command blocks (3), structure_block, jigsaw, `office:hologram_emitter`. `tools/make_magic_textures.py` (stdlib +
  the same PNG writer approach as `tools/make_emitter_textures.py`) generates every texture above deterministically; commit its output.
- [ ] **Step 4: Run** the Step 2 command → PASS.
- [ ] **Step 5: Commit** `feat(magic): grimoire + rebinding seal items, recipes, tiered looks, textures, lang`.

### Task 3: Magic data, mana, payloads, element choice (server)

**Files:**
- Create: `magic/data/{MagicData,MagicAttachments,MagicHomes}.java`, `magic/net/{MagicPayloads,FxEvent,MagicSender,MagicServer}.java`,
  `magic/cast/ElementChoice.java`, `magic/command/MagicCommand.java`
- Modify: `magic/item/GrimoireItem.java` (`use` for unbound / main hand, `inventoryTick` stamping), `magic/item/RebindingSealItem.java`, `magic/MagicMod.java`
- Test: `MagicCoreGameTests.java`, `mod/src/test/java/local/carter/office/magic/MagicDataCodecTest.java`

**Interfaces:**
- Produces:
  - `record MagicData(Optional<Element> element, int level, int xp, Optional<Identifier> selected, Optional<GlobalPos> home, Map<Identifier, Long> readyAt)`
    with `EMPTY` (level 1), `CODEC`, `STREAM_CODEC`, `with…` copies, `long cooldownLeft(Identifier spell, long gameTime)`.
  - `MagicAttachments.MAGIC` (`AttachmentType<MagicData>`: persistent, `copyOnDeath`, `syncWith(STREAM_CODEC, AttachmentSyncPredicate.targetOnly())`),
    `MagicAttachments.MANA` (`AttachmentType<Integer>`, same flags, initializer 100); `static MagicData get(Player)`,
    `static void set(ServerPlayer, MagicData)` (also re-stamps held grimoires), `static int mana(Player)`, `static void setMana(ServerPlayer, int)`.
  - `MagicHomes extends SavedData` (`office:magic_homes`, uuid → GlobalPos): `get(MinecraftServer)`, `put(UUID, GlobalPos)`, `Collection<GlobalPos> othersIn(ResourceKey<Level>, UUID except)`.
  - `record FxEvent(Identifier spell, int casterId, Vec3 origin, Vec3 dir, Vec3 target, float power, long seed, int duration, int[] entities, float[] data)`
    (`entities` ≤ 32, `data` ≤ 16, enforced in codec); builder methods `withTarget(Vec3)`, `withDuration(int)`, `withEntities(int...)`, `withData(float...)`.
  - `MagicPayloads` (registered from `MagicMod`, same style as `net/OfficePayloads`): S2C `OpenGrimoire(Mode mode)` (`enum Mode { CHOOSE, INFO }`),
    `CastFx(FxEvent event)`, `FxCancel(int casterId)`, `SignChallenge(int id, Identifier spell, long seed, int length, int limitTicks)`;
    C2S `ChooseElement(Element element)`, `SelectSpell(Identifier spell)`, `SignAnswer(int id, byte[] dirs)` (≤ 6).
  - `interface MagicSender { void fx(ServerLevel level, FxEvent e, double range); void cancelFx(ServerPlayer caster); void challenge(ServerPlayer p, SignChallenge c); void open(ServerPlayer p, OpenGrimoire.Mode m); void actionBar(ServerPlayer p, Component c); }`
  - `MagicServer.sender()` (real: `ServerPlayNetworking` to `PlayerLookup.around(level, origin, range)` ∪ caster) and `MagicServer.setSenderForTesting(@Nullable MagicSender)`.
  - `ElementChoice.choose(ServerPlayer, Element) -> boolean` (only when unbound; sets `selected` to slot 0 spell id `office:<…>`
    from `SpellRegistry` if registered, else Recall; plays `SoundEvents.ENCHANTMENT_TABLE_USE`; title "You are bound to <Element>").
  - `/magic level <player> <1..10>`, `/magic mana <player> <n>`, `/magic reset <player>` (op 2).
- [ ] **Step 1: Write the failing tests**
  - `MagicDataCodecTest`: a fully populated `MagicData` round-trips through `CODEC` (JsonOps) and `STREAM_CODEC` unchanged; `EMPTY.level()==1`.
  - `firstUseOpensChoice` (GameTest, recorder sender): unbound player uses the grimoire in the main hand, or in the off hand with an empty
    main hand → recorder saw `open(p, CHOOSE)`, not using item; off hand with a sword in the main hand → `PASS`, nothing opened.
  - `chooseElementBindsOnce`: `choose(p, FIRE)` true → data element FIRE; `choose(p, WATER)` false → still FIRE.
  - `mainHandUseOpensInfo`: bound player, grimoire in main hand → `open(p, INFO)`.
  - `rebindingSealClearsElementKeepsLevel`: level 5 FIRE player uses seal → element empty, level 5, seal consumed, `open(p, CHOOSE)`.
  - `manaAttachmentSurvivesDeathCopy`: data and mana copied to a respawned player copy (`ServerPlayer.restoreFrom` path).
  - `heldGrimoireRestampedOnLevelChange`: `/magic level p 7` → held grimoire model string `t3`.
- [ ] **Step 2: Run** `scripts/heavy.sh ./gradlew build test runGametest --no-daemon` → FAIL.
- [ ] **Step 3: Implement.** Receivers: `ChooseElement` → `ElementChoice.choose`; `SelectSpell` → accept only spells unlocked for
  the player (Task 4 adds the check via `SpellRegistry`; for now store if registered). Message keys + copy:
  `magic.office.bound` "You are bound to %s", `magic.office.no_mana` "Not enough mana (%d/%d)", `magic.office.cooldown`
  "%s is recharging (%.1f s)", `magic.office.locked` "%s unlocks at grimoire level %d", `magic.office.no_levels` "%s needs %d XP levels",
  `magic.office.fizzle` "The signs broke — the spell fizzled", `magic.office.level_up` "Grimoire level %d",
  `magic.office.unlocked` "%s unlocked", `magic.office.stronger` "Your magic grows stronger", `magic.office.no_home`
  "No home yet — Shift + cast Recall to mark one", `magic.office.home_marked` "Home marked", `magic.office.recalling` "Recalling…",
  `magic.office.recall_interrupted` "Recall interrupted", `magic.office.protected` "The land here is protected", `magic.office.busy` "Too much magic here — wait a moment".
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(magic): player magic data + mana attachments, payloads, element choice, /magic admin`.

### Task 4: Spell SPI, registry, cast pipeline, spell kit, test kit

**Files:**
- Create: `magic/spell/{Spell,SpellCost,CastContext,CastOutcome,SpellRegistry,SpellKit,Targets,TargetFilter,Hurt,Motion,Projectiles,ActiveEffects}.java`,
  `magic/cast/CastService.java`, six `magic/spell/<element>/<Element>Spells.java` stubs (`public static void register() {}`)
- Create: `mod/src/gametest/java/local/carter/office/magic/MagicTestKit.java`, `tools/make_magic_arena.py` → `mod/src/gametest/resources/data/office-test/structure/magic_arena.nbt`
- Modify: `GrimoireItem.java` (off-hand use/charge/release), `MagicMod.java` (register stubs, server tick, `SelectSpell` receiver accepts only `SpellRegistry.unlocked`)
- Test: `MagicCoreGameTests.java`, `mod/src/test/java/local/carter/office/magic/SpellRegistryTest.java`

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces (the SPI every M2/M3 task codes against):
  - `interface Spell { Identifier id(); @Nullable Element element(); int slot(); default SpellCost cost() { return SpellCost.forSlot(slot()); } CastOutcome cast(CastContext ctx); }`
  - `record SpellCost(int mana, int cooldownTicks, int xp, int xpLevels) { static SpellCost forSlot(int slot); }`
  - `record CastContext(ServerPlayer caster, ServerLevel level, Spell spell, float charge, float power, int grimoireLevel, long seed, SpellKit kit) { Vec3 eye(); Vec3 look(); RandomSource random(); FxEvent fx(); }`
    (`fx()` = event pre-filled with spell, caster id, origin = eye, dir = look, target = eye + look×8, power, seed).
  - `sealed interface CastOutcome { static CastOutcome cast(FxEvent fx); static CastOutcome fizzle(String messageKey); }` — fizzle spends nothing.
  - `SpellRegistry`: `register(Spell)` (throws on duplicate id or duplicate element+slot), `Optional<Spell> get(Identifier)`,
    `Optional<Spell> at(Element, int slot)`, `List<Spell> wheel(Element)` (slots 0–5, missing slots absent), `List<Spell> unlocked(MagicData)`,
    `AutoCloseable overrideForTesting(Spell)` (temporarily takes that element+slot, restores the real spell on close — test probes never collide with M2/M3 spells).
  - `record SpellKit(TerrainEdits terrain, TempBlocks temp, ActiveEffects effects, Projectiles projectiles)` (terrain/temp land in Task 5; until then `null`-safe stubs throw `UnsupportedOperationException`).
  - `TargetFilter.affects(ServerPlayer caster, Entity e)`, `TargetFilter.pushable(ServerPlayer caster, Entity e)` (rules in Global Constraints).
  - `Targets`: `Optional<LivingEntity> ray(CastContext, double range, double forgiveness)`, `Vec3 point(CastContext, double range)`
    (block hit or max range), `Vec3 ground(ServerLevel, Vec3)` (heightmap MOTION_BLOCKING), `List<LivingEntity> radius(CastContext, Vec3 c, double r)`,
    `List<Entity> cone(CastContext, double range, double halfAngleDeg, boolean pushables)` — all filtered and capped at 64.
  - `Hurt.damage(CastContext, LivingEntity, float amount)` (`damageSources().indirectMagic(caster, caster)`), `Hurt.knock(Entity, Vec3 dir, double strength, double up)` (sets `hurtMarked`).
  - `Motion.dash(ServerPlayer, Vec3 velocity, int noFallTicks)`, `Motion.teleportSafe(ServerPlayer, Vec3 target)` (steps back along the path until the player box fits; returns the final position).
  - `interface LingeringEffect { boolean tick(ServerLevel level, int age); default void end(ServerLevel level) {} }`;
    `ActiveEffects.add(LingeringEffect) -> boolean` (false at 32), ticked from server tick, all `end` on `SERVER_STOPPING`.
  - `record ProjectileSpec(Vec3 origin, Vec3 velocity, double gravity, double radius, int maxTicks, boolean pierce)`;
    `record ProjectileHit(Vec3 pos, @Nullable LivingEntity entity, @Nullable BlockHitResult block)`;
    `Projectiles.launch(CastContext, ProjectileSpec, Consumer<ProjectileHit> onHit)` — an entity-free `LingeringEffect`
    (block clip with `ClipContext.Block.COLLIDER`, entity sweep via `TargetFilter`); pierce = keep flying after entity hits (each entity once).
  - `CastService`: `enum Result { CAST, CHALLENGED, FIZZLED, NO_ELEMENT, WRONG_ELEMENT, LOCKED, COOLDOWN, NO_MANA, NO_LEVELS, BUSY }`;
    `Result cast(ServerPlayer p, Spell spell, float charge)` (checks in that order, then `spell.cast`; ultimates go to
    `SignChallenges.issue` in Task 6 — until then `CHALLENGED` is never returned); `void release(ServerPlayer p, int ticksHeld)`
    (selected spell, `ChargeMath.charge`, Recall ignores charge); `void tick(MinecraftServer)` (mana regen, effects).
    On CAST: spend `ChargeMath.cost` mana, set cooldown `readyAt = gameTime + cooldownTicks`, add xp via `LevelCurve.add`
    (level-up: `PLAYER_LEVELUP` sound + title `magic.office.level_up` / subtitle unlocked spell or `magic.office.stronger`), send `fx` once
    with range 64 (128 if slot 4), remember last-cast tick for regen pause.
  - `GrimoireItem` (server side): off hand + empty main hand + bound + selected spell present → `player.startUsingItem(OFF_HAND)`, `CONSUME`;
    any main-hand item → `PASS`; `onUseTick`: main hand no longer empty → `stopUsingItem()` (no cast); `releaseUsing` → `CastService.release`.
  - `MagicTestKit`: `ServerPlayer caster(GameTestHelper h, Element e, int level, BlockPos standRel)` (survival mock, bound, full mana,
    grimoire in off hand, facing +Z); `Result cast(ServerPlayer p, Identifier spell, float charge)`; `Result castUltimate(ServerPlayer p, Identifier spell)`
    (answers the challenge correctly; Task 6 fills it); `Zombie dummy(GameTestHelper h, BlockPos rel)` (NoAI, 20 hp, persistent);
    `Recorder recorder(GameTestHelper h)` (installs a `MagicSender` recording fx/challenges/messages, restored on test end);
    `void aim(ServerPlayer p, BlockPos rel)` (sets yaw/pitch so the eye looks at the centre of `rel`);
    `ARENA = "office-test:magic_arena"` (32×24×32, stone floor y 0–3, caster stands at (16,4,4) facing +Z).
- [ ] **Step 1: Write the failing tests**
  - `SpellRegistryTest`: duplicate id throws; duplicate FIRE slot 0 throws; `wheel(FIRE)` ordered by slot with Recall last; `unlocked` at level 2 = slots 0, 1, 5.
  - GameTests (test-only probe spells via `overrideForTesting`: `office-test:probe` at FIRE slot 0, `office-test:probe_medium` at FIRE slot 1;
    the probe returns `CastOutcome.cast(ctx.fx())`):
    `castSpendsManaSetsCooldownSendsOneFx` (mana 100 → 92, `cooldownLeft == 10`, recorder has exactly 1 fx);
    `chargedCastCostsMoreAndIsStronger` (charge 1 → mana 88, fx power 2.0);
    `lowManaLowersChargeInsteadOfFailing` (mana 9, charge 1 → CAST, mana spent ≤ 9);
    `cooldownBlocksSecondCast` (second cast same tick → COOLDOWN, message key `magic.office.cooldown`);
    `lockedSlotRefused` (slot 1 spell at level 1 → LOCKED);
    `castXpLevelsUpGrimoire` (level 1, xp 18, gain 2 → level 2, title sent);
    `mainHandItemNeverStartsCharge` (sword / bread / dirt / shield in main hand: `use(OFF_HAND)` returns PASS, `!isUsingItem()`);
    `switchingToToolCancelsCharge` (start using, put a pickaxe in main hand, run `onUseTick` → not using, mana unchanged, 0 fx);
    `tapAndHoldReleaseCharges` (`release(p, 5)` → fx power 1.0; `release(p, 40)` after cooldown → power 2.0);
    `targetFilterSparesPlayersPetsVillagers` (second mock player, tamed wolf, villager, iron golem, armor stand, item entity → `affects` all false; zombie true; item `pushable` true);
    `projectileStopsAtWall` (spec speed 2 toward a stone wall 5 blocks away → onHit block pos at the wall within 4 ticks, entity null).
- [ ] **Step 2: Run** `scripts/heavy.sh ./gradlew build test runGametest --no-daemon` → FAIL.
- [ ] **Step 3: Implement** the classes; `tools/make_magic_arena.py` writes the gzipped structure NBT with the stdlib.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(magic): spell SPI, registry, server cast pipeline, spell kit, gametest kit`.

### Task 5: Terrain edits, temporary blocks, claims, edit log, `/magic undo`

**Files:**
- Create: `magic/terrain/{TerrainEdits,EditBatch,EditTier,TempBlocks,EditLog,ClaimCheck,OpacClaims,FallbackClaims}.java`
- Modify: `build.gradle` (Modrinth maven `exclusiveContent` for group `maven.modrinth`; `compileOnly("maven.modrinth:open-parties-and-claims:KBCCg0os") { transitive = false }`),
  `magic/spell/SpellKit.java`, `magic/command/MagicCommand.java`, `magic/MagicMod.java`, `hologram/HologramIndex.java` (add `List<Entry> entriesIn(ResourceKey<Level>)` if absent)
- Test: `mod/src/gametest/java/local/carter/office/magic/TerrainGameTests.java`, `mod/src/test/java/local/carter/office/magic/EditLogTest.java`

**Interfaces:**
- Consumes: `TerrainRules`, `Shapes` (Task 1); `CastContext`, `SpellKit` (Task 4); `MagicHomes` (Task 3); `HologramIndex`.
- Produces:
  - `enum EditTier { MEDIUM(6, 256), ULTIMATE(12, 1500); double radius(); int budget(); }`
  - `TerrainEdits.begin(CastContext ctx, BlockPos origin, EditTier tier) -> EditBatch`.
  - `EditBatch`: `boolean set(BlockPos, BlockState)`, `boolean remove(BlockPos)`, `boolean launch(BlockPos, Vec3 velocity)`
    (remove + `FallingBlockEntity.fall`, `dropItem=false`, ≤ 48), `boolean placeTemporary(BlockPos, BlockState, int ticks)`
    (air/replaceable only, MEDIUM only), `int refused()`, `void close()` (commits the log entry; open batches may be filled over
    several ticks by a `LingeringEffect`). Each call runs `TerrainRules.decide` (immune = `#office:magic_immune`, claimed via
    `ClaimCheck`, spawn = `!level.mayInteract(caster, pos)`, border = `level.getWorldBorder().isWithinBounds(pos)`).
    Changes queue to a global applier (≤ 256 `setBlock(…, Block.UPDATE_ALL)` per tick, FIFO across casts).
    First refusal for CLAIMED/SPAWN_PROTECTED in a batch → action bar `magic.office.protected`.
  - `TempBlocks extends SavedData` (`office:magic_temp`): entries (dimension, pos, original, placed, expireGameTime); restores when
    expired **only if the block is still `placed`**; `restoreAll(MinecraftServer)` on `SERVER_STOPPING`; expired entries restored on `SERVER_STARTED`.
  - `interface ClaimCheck { Predicate<BlockPos> forBatch(ServerPlayer caster, ServerLevel level, BlockPos origin, double radius); }`;
    `ClaimCheck.forServer(MinecraftServer)` → `OpacClaims` when `FabricLoader.isModLoaded("openpartiesandclaims")` and its API
    resolves (blocked = chunk claimed by any UUID ≠ caster, cached per chunk), else `FallbackClaims` (blocked = within 32 blocks of
    any hologram emitter in that dimension, another player's `MagicHomes` home, or another online player's respawn position).
  - `EditLog`: files `<world>/data/office/magic-edits/<uuid>/<gameTime>-<seq>.nbt` (compressed); `record LoggedEdit(BlockPos pos, BlockState before, BlockState after)`;
    `record LoggedCast(UUID player, Identifier spell, ResourceKey<Level> dimension, long gameTime, List<LoggedEdit> edits)`;
    keeps the newest 20 per player; `int undo(MinecraftServer, UUID player, int n)` (newest first, restores an edit only if the
    current state equals `after`; returns restored count, reports skipped).
  - `/magic undo <player> [n=1]` (op 2, `GameProfileArgument`): "Restored %d blocks from %d casts (%d changed since, left alone)".
- [ ] **Step 1: Verify the OPAC API** against the jar before coding: `javap` (Temurin 25 under `~/.gradle/jdks`) on
  `xaero.pac.common.server.api.OpenPACServerAPI` (`get(MinecraftServer)`, `getServerClaimsManager()`), the claims manager `get(Identifier, int, int)`
  and `IPlayerChunkClaimAPI.getPlayerId()`. If any is missing on 26.3, ship `FallbackClaims` only and note it in the commit message.
- [ ] **Step 2: Write the failing tests**
  - `EditLogTest`: a `LoggedCast` with 3 edits round-trips through its NBT codec.
  - `TerrainGameTests` (arena): `refusesBlockEntitiesBedrockObsidian` (chest, bedrock, obsidian, emitter in range → all unchanged, `refused()==4`);
    `refusesBeyondRadius` (MEDIUM `set` at distance 7 → false); `budgetCapsBatch` (MEDIUM 300 sets → 256 applied);
    `fallbackProtectsNearEmitter` (emitter at 20 blocks from origin → nothing changes; emitter at 40 → changes);
    `fallbackProtectsOtherPlayersHome` (another player's home 10 blocks away → nothing changes; own home → changes);
    `applierCapsPerTick` (1000 queued → after 1 tick ≤ 256 changed, after 4 ticks all);
    `undoRestoresLastCast` (two casts; `undo(p,1)` restores only the second);
    `undoSkipsBlocksChangedSince` (after a crater, set 3 crater cells to planks → undo restores the rest, the 3 planks stay, message reports 3);
    `tempBlocksRestoreWhenExpired` (water temp 20 ticks → air at tick 21; a temp block replaced by a player's dirt → dirt stays);
    `stoppingRestoresTempBlocks` (call `restoreAll` → all temp cells back to original);
    `tempBlocksRestoredAfterReload` (save the SavedData, clear in-memory instance, load, advance past expiry → restored).
- [ ] **Step 3: Run** `scripts/heavy.sh ./gradlew build test runGametest --no-daemon` → FAIL.
- [ ] **Step 4: Implement**; wire `SpellKit.terrain/temp`; register lifecycle hooks in `MagicMod`.
- [ ] **Step 5: Run** → PASS.
- [ ] **Step 6: Commit** `feat(magic): claim-safe batched terrain edits, persisted temp blocks, edit log + /magic undo`.

### Task 6: Hand signs for ultimates (server + prompt screen)

**Files:**
- Create: `magic/cast/SignChallenges.java`, `client/magic/screen/HandSignScreen.java`, `client/magic/MagicClient.java` (`register()`: client
  payload receivers; later tasks add to it)
- Modify: `magic/cast/CastService.java`, `magic/MagicMod.java` (receiver, disconnect/death/dimension hooks), `MagicTestKit.java` (`castUltimate`),
  `client/OfficeClient.java` (call `MagicClient.register()` **after** `HologramInteractions.register()`, so hologram scroll/use handlers run first)
- Test: `mod/src/gametest/java/local/carter/office/magic/HandSignGameTests.java`, `mod/src/test/java/local/carter/office/client/magic/HandSignInputTest.java`

**Interfaces:**
- Consumes: `HandSigns`, `Dir` (Task 1); `CastService`, payloads (Tasks 3–4).
- Produces:
  - `SignChallenges.issue(ServerPlayer, Spell, float power) -> SignChallenge` (id, seed from `RandomSource`, `HandSigns.length(level)`, limit);
    `CastService.Result answer(ServerPlayer, int id, Dir[] dirs)` — correct + within `limit + GRACE_TICKS` → full ultimate cast
    (mana + `xpLevels` spent, cooldown 600, fx); wrong/late/unknown id → FIZZLED (`ceil(mana/2)` spent, cooldown 100,
    `SoundEvents.FIRE_EXTINGUISH`, `magic.office.fizzle`); `discard(ServerPlayer)` (no cost); `tick(MinecraftServer)` expires (= fizzle).
    Death, disconnect and dimension change call `discard`.
  - `HandSignScreen(SignChallenge c)`: `isPauseScreen()=false`; maps ↑/W, ↓/S, ←/A, →/D to `Dir`; arrows drawn large in a row,
    entered ones lit in the element colour, a shrinking timer bar; first wrong key or Esc → sends the partial answer (server fizzles);
    complete → sends `SignAnswer`, flashes, closes. Pure helper `HandSignScreen.dirFor(int keyCode) -> @Nullable Dir`.
- [ ] **Step 1: Write the failing tests**
  - `HandSignInputTest`: `dirFor(InputConstants.KEY_UP)==UP`, `dirFor(KEY_W)==UP`, `KEY_A→LEFT`, `KEY_D→RIGHT`, `KEY_S→DOWN`, `KEY_E→null`.
  - `HandSignGameTests` (probe ultimate `office-test:probe_ultimate` via `overrideForTesting` at FIRE slot 4, level 7, 3+ XP levels): `ultimateIssuesChallengeNotCast` (Result CHALLENGED, 0 fx, mana unchanged, challenge length 6, limit 92);
    `correctSignsCastUltimate` (1 fx, mana −60, XP levels −3); `wrongSignsFizzleHalfMana` (mana −30, XP unchanged, cooldown 100);
    `lateAnswerFizzles` (answer after 103 ticks → FIZZLED); `notEnoughXpLevels` (2 levels → NO_LEVELS, no challenge);
    `deathDiscardsChallenge` (challenge, kill, answer correctly → nothing cast, mana unchanged); `dimensionChangeDiscardsChallenge`.
- [ ] **Step 2: Run** `scripts/heavy.sh ./gradlew build test runGametest --no-daemon` → FAIL.
- [ ] **Step 3: Implement**; client receiver for `SignChallenge` opens `HandSignScreen` (closes it on player death).
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(magic): hand-sign challenges for ultimates`.

### Task 7: Recall (Mark Home + channel)

**Files:**
- Create: `magic/cast/RecallSpell.java`
- Modify: `magic/MagicMod.java` (register Recall, damage + dimension-change hooks), `magic/cast/CastService.java`
- Test: `mod/src/gametest/java/local/carter/office/magic/RecallGameTests.java`

**Interfaces:**
- Consumes: SPI (Task 4), `MagicHomes` (Task 3), `MagicSender.cancelFx` (Task 3).
- Produces: `RecallSpell implements Spell` (id `office:recall`, element `null`, slot 5, `cost()` = `SpellCost(0, 0, 0, 0)` so
  `CastService` sets no cooldown — Recall applies its own 20 / 1200 below): sneaking at release → mark home
  (`GlobalPos` of feet block, `MagicHomes.put`, `magic.office.home_marked`, cooldown 20 ticks only); otherwise no home →
  fizzle `magic.office.no_home`; else a 60-tick `LingeringEffect` channel (fx duration 60, horizontal circle under the caster),
  cancelled by any damage (`ServerLivingEntityEvents.AFTER_DAMAGE`), death, disconnect or dimension change →
  `cancelFx` + `magic.office.recall_interrupted`, no cooldown; success → `teleport(TeleportTransition)` to home (any dimension,
  centre of block, keep yaw), `SoundEvents.ENDERMAN_TELEPORT`, cooldown 1200. `RecallSpell.channeling(ServerPlayer) -> boolean`.
- [ ] **Step 1: Write the failing GameTests**: `shiftCastMarksHome`; `recallWithoutHomeFizzles` (no cooldown set); `recallTeleportsAfterThreeSeconds`
  (at tick 59 still in place, by tick 61 at home, cooldown 1200, mana unchanged); `recallCancelledByDamage` (hurt at tick 30 → stays, no cooldown, 1 cancel sent);
  `recallCrossDimension` (home in the Nether, cast in the overworld → in the Nether at home); `recallCancelledByDimensionChange`;
  `recallAvailableAtLevelOneForEveryElement` (each element at level 1 → `unlocked` contains `office:recall`).
- [ ] **Step 2: Run** `scripts/heavy.sh ./gradlew build test runGametest --no-daemon` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(magic): Recall — mark home, 3 s channel, cancel on damage`.

### Task 8: Client input — precedence, R wheel, Shift + scroll

**Files:**
- Create: `client/magic/{MagicInputGate,MagicKeys,SpellWheel,SpellWheelHud}.java`, `mixin/client/MouseHandlerWheelMixin.java`
- Create: `mod/src/gametest/java/local/carter/office/client/MagicClientGameTests.java` (+ entrypoint in gametest `fabric.mod.json`)
- Modify: `client/magic/MagicClient.java`, `mixin/client/MinecraftUseItemMixin.java`, `office.client.mixins.json`
- Test: `mod/src/test/java/local/carter/office/client/magic/MagicInputGateTest.java`

**Interfaces:**
- Consumes: `WheelMath`, `ScrollSelect` (Task 1); `SelectSpell`, `MagicAttachments` (Task 3); `SpellRegistry.wheel/unlocked` (Task 4);
  `HologramPicker.pick(float)` (existing); `PointerClient.typing().active()` (spec §5.9 work).
- Produces:
  - `MagicInputGate` (pure statics + one live wrapper): `boolean blocksUse(boolean screenOpen, boolean typing, boolean aimingAtHologram, boolean grimoireOffhand, boolean mainHandEmpty)`
    (true only when typing, or grimoire off hand + empty main hand + aiming at a hologram); `boolean wheelAllowed(boolean screenOpen, boolean typing, boolean grimoireHeld, boolean bound)`;
    `boolean scrollSelects(boolean shift, boolean typing, boolean aimingAtHologram, boolean grimoireOffhand, boolean mainHandEmpty, boolean bound)`;
    `static boolean blocksUse(Minecraft mc)` (live values; hologram = `HologramPicker.pick` hit closer than vanilla `hitResult`).
  - `MinecraftUseItemMixin`: after `HologramInteractions.onUse` declines, `if (MagicInputGate.blocksUse(mc)) ci.cancel()`. Everything else
    stays vanilla, so main-hand block/entity interactions run before the off-hand grimoire.
  - `MagicKeys.WHEEL`: `KeyMapping("key.office.spell_wheel", KEYSYM, InputConstants.KEY_R, MagicKeys.CATEGORY)`, category
    `KeyMapping.Category.register(OfficeMod.id("magic"))`, registered with `KeyMappingHelper` (vanilla 26.3 keeps a list per key, so JEI's GUI-only R coexists).
  - `SpellWheel` state: `open()`, `addMouse(double dx, double dy)`, `int hovered()`, `Identifier releaseSelection()` (null if nothing hovered — keeps the current spell), eases open over 3 ticks;
    `MouseHandlerWheelMixin` (`turnPlayer` HEAD, cancellable): while open, feed `accumulatedDX/DY` to `SpellWheel.addMouse`, zero them, cancel (WASD keeps working).
  - `SpellWheelHud` (`HudElementRegistry.addLast(office:spell_wheel)`): 6 slices (donut inner 22 / outer 70 GUI px, scaled down to fit
    `min(width,height)×0.42`), hovered slice 1.15× + glow + `UI_BUTTON_CLICK` (vol 0.3, pitch 1.8) on change, cooldown sweep per slice,
    locked slices grey + padlock + "Lv %d", centre: name, mana cost, cooldown; ultimate slice shows "+3 lvl".
  - Shift + scroll: `ClientHotbarScrollEvents.ALLOW` → when `scrollSelects`, `ScrollSelect.next` over `unlocked`, send `SelectSpell`, return false.
- [ ] **Step 1: Write the failing tests**
  - `MagicInputGateTest`: typing → `blocksUse` true and `wheelAllowed` false and `scrollSelects` false; grimoire off hand + empty hand + aiming at hologram → `blocksUse` true, `wheelAllowed` true, `scrollSelects` false;
    tool in main hand → `blocksUse` false; not aiming → false; unbound → `scrollSelects` false.
  - `MagicClientGameTests` (CI): `emptyHandChestStillOpens` (grimoire off hand, bound, empty main hand, right-click a chest → `ContainerScreen` open, mana unchanged);
    `rightClickHologramDoesNotCast` (own emitter in front, right-click → no mana change); `shiftScrollSelectsNextSpell` (level 3 → selected moves slot 0 → 1, hotbar slot unchanged);
    `wheelSelectsByMouse` (hold R, move mouse up-right, release → slot 1 selected, nothing cast); screenshots `magic-wheel.png`.
- [ ] **Step 2: Run** `scripts/heavy.sh ./gradlew test --no-daemon --tests 'local.carter.office.client.magic.*'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `scripts/heavy.sh ./gradlew build test runGametest --no-daemon` → PASS (client gametests compile; they run in CI).
- [ ] **Step 5: Commit** `feat(magic): input precedence with monitors, R spell wheel, Shift+scroll selection`.

### Task 9: Element choice, grimoire screen, mana bar HUD

**Files:**
- Create: `client/magic/screen/{ElementChoiceScreen,GrimoireScreen}.java`, `client/magic/hud/ManaBarHud.java`
- Modify: `client/magic/MagicClient.java` (`OpenGrimoire` receiver, HUD registration)
- Test: `mod/src/test/java/local/carter/office/client/magic/ChoiceLayoutTest.java`, `MagicClientGameTests.java`

**Interfaces:**
- Consumes: textures + lang (Task 2), `ChooseElement`/`SelectSpell`/`OpenGrimoire` (Task 3), `SpellRegistry.wheel` (Task 4).
- Produces:
  - `ElementChoiceScreen`: dark vignette; open book centred (`book_open.png`, 50 % of `min(w,h)`); six sigils orbit the book on an ellipse
    (rx = 0.40 w, ry = 0.34 h, 4°/s); hover → orbit pauses, sigil 1.25× with pulsing element-colour glow, book pages show element name (2× text),
    motto and its five spells (icon + name, slot order) and a rotating mini circle in the element colour; click → confirm state
    "Bind to %s?" / "This is permanent (a Rebinding Seal can change it)." with [Bind] [Back]; Bind → `ChooseElement`, 20-tick flash, close.
    Tab/arrow keys cycle sigils, Enter confirms, Esc closes unbound. `isPauseScreen()=false`. Pure layout helper
    `static ChoiceLayout layout(int width, int height, float orbitDeg)` → six sigil centres + sigil size + book rect.
  - `GrimoireScreen` (main-hand use, bound): left page sigil, "Level %d", xp bar to next, "Home: x y z (dimension)" or "No home";
    right page 6 spell rows (icon, name, mana, cooldown s, lock + "Lv %d"), click selects; footer hint
    "Off hand + empty hand: tap right-click to cast · hold to charge · R wheel · Shift+scroll".
  - `ManaBarHud`: `HudElementRegistry.replaceElement(VanillaHudElements.INFO_BAR, …)` and `(EXPERIENCE_LEVEL, …)` — while a grimoire is
    in either hand and the player is bound, draw the mana bar in the XP bar's place (182×5, element-colour fill, notch per 10 mana, white
    sweep for the selected spell's cooldown, mana number where the level number sits); otherwise delegate to vanilla.
    Selected-spell name fades in above the hotbar for 40 ticks after a change.
- [ ] **Step 1: Write the failing tests**
  - `ChoiceLayoutTest`: for (320×240), (427×240), (1920×1080), (640×360): all six sigils fully inside the screen with ≥ 4 px margin, no two sigils overlap, the book rect never overlaps a sigil.
  - `MagicClientGameTests`: `firstOpenShowsElementChoice` (use unbound grimoire → `ElementChoiceScreen`; screenshots at GUI scale 1 and 4: `choice-gui1.png`, `choice-gui4.png`);
    `bindingFireClosesAndShowsManaBar` (click Fire sigil, Bind → screen closed, element FIRE, screenshot `mana-bar.png`).
- [ ] **Step 2: Run** `scripts/heavy.sh ./gradlew test --no-daemon --tests 'local.carter.office.client.magic.*'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS; `scripts/heavy.sh ./gradlew build --no-daemon` → BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `feat(magic): element choice scene, grimoire screen, mana bar HUD`.

### Task 10: Client FX core — circles, glow, shake, FX dispatch

**Files:**
- Create: `client/magic/fx/{FxClient,SpellFx,SpellFxRegistry,FxKit,DefaultFx,MagicCircleRenderer,LightPipeline,ChargeCircles,GhostBlocks,Shake,GrimoireParticles}.java`,
  six `client/magic/fx/<element>/<Element>Fx.java` stubs, `mixin/client/CameraShakeMixin.java`, `THIRD_PARTY_NOTICES.md`
- Modify: `office.client.mixins.json`, `client/magic/MagicClient.java`
- Test: `mod/src/test/java/local/carter/office/client/magic/CircleMathTest.java`

**Interfaces:**
- Consumes: `FxEvent`, `CastFx`, `FxCancel` (Task 3); `ChargeMath`, `Element` (Task 1); `GrimoireLook.element` (Task 2).
- Produces:
  - `@FunctionalInterface interface SpellFx { void play(FxEvent e, ClientLevel level, FxKit kit); }`;
    `SpellFxRegistry.register(Identifier spell, SpellFx fx)`, `SpellFx get(Identifier)` (unknown → `DefaultFx`: cast circle + element burst).
  - `FxKit`: `circle(Vec3 centre, Vec3 normal, int argb, float radius, int rings, int lifeTicks)`;
    `trail(Vec3 from, Vec3 to, ParticleOptions p, int count)`; `burst(Vec3 at, ParticleOptions p, int count, double speed)`;
    `ring(Vec3 centre, double radius, ParticleOptions p, int count)`; `bolt(Vec3 from, Vec3 to, long seed, int argb)` (jagged, white core);
    `ghostBlock(BlockPos pos, BlockState state, Vec3 offset, int lifeTicks, GhostBlocks.Motion motion)` (client-only block model, no world change;
    `enum Motion { STATIC, RISE, POP, FALL, ORBIT_IN }`);
    `simulate(int everyTicks, int durationTicks, IntConsumer onTick)` (client-side timing, so lingering spells need no extra packets);
    `shake(float intensity, int ticks)`, `hitStop(int ticks)`, `fovKick(float amount)`; every call respects the particle/ghost budgets.
  - `ChargeCircles`: every frame, for every player (local and remote) using a grimoire: vertical circle 1.5 blocks in front of the eye,
    0.25 below, facing the look vector, `ChargeMath.rings(useTicks)` rings fading in one by one (3 ticks each, alternate rotation,
    runes on rings 2 and 4), radius `0.55 + 0.15 × rings`, alpha 0.55; full charge → pulse + local `BEACON_POWER_SELECT` (pitch 1.6) once;
    colour from `GrimoireLook.element(useItem)`; for the local player with Recall selected → gold horizontal circle at the feet instead
    (remote players always show their element circle; their selection isn't synced).
  - `Shake` + `CameraShakeMixin` (`Camera.alignWithEntity` TAIL, alongside `CameraZoomMixin`): decaying offset scaled by
    `options.screenEffectScale()`; hit-stop and FOV kick from Wildercord.
  - `GrimoireParticles`: tier 3 grimoires in any player's hand within 32 blocks emit one element particle every 4 ticks.
  - `FxClient`: `CastFx` → `SpellFxRegistry.get(e.spell()).play(…)`; `FxCancel` → stop that caster's channel visuals.
- [ ] **Step 1: Port Wildercord.** Clone `https://github.com/defnotean/wildercord` (tag of 0.13.0-alpha, 2026-10-15) into the scratchpad;
  locate the magic-circle renderer, the additive light pipeline (`BlendFunction.LIGHTNING`), camera shake, hit-stop and FOV kick (grep
  `circle`, `BlendFunction`, `shake`, `hitStop`). Copy into `MagicCircleRenderer`, `LightPipeline`, `Shake` with header
  `// Adapted from Wildercord (MIT) https://github.com/defnotean/wildercord @<sha>`; put the MIT text and SHA in `THIRD_PARTY_NOTICES.md`.
  Hook rendering where Wildercord does on 26.3 (`LevelRenderEvents`); check signatures in `genSources`.
- [ ] **Step 2: Write the failing test** `CircleMathTest`: `MagicCircleRenderer.ringRadius(0..4)` strictly increasing from 0.55; ring 3 alpha at
  ring age 0/1/2/3 ticks = 0, ⅓, ⅔, 1; basis vectors built from look (0,0,1) are orthonormal and perpendicular to it; from look straight up (0,1,0) no NaN.
- [ ] **Step 3: Run** `scripts/heavy.sh ./gradlew test --no-daemon --tests 'local.carter.office.client.magic.*'` → FAIL.
- [ ] **Step 4: Implement** the rest; register six empty `<Element>Fx.register()` calls in `MagicClient`.
- [ ] **Step 5: Run** → PASS; `scripts/heavy.sh ./gradlew build runGametest --no-daemon` → BUILD SUCCESSFUL.
- [ ] **Step 6: Commit** `feat(magic): summoning circles, glow pipeline, shake/hit-stop, FX dispatch (Wildercord MIT)`.

### Task 11: Pack swap — remove Wizards and its stack (independent; any time)

**Files:**
- Create: `scripts/check-pack-deps.py`
- Delete: `pack/mods/{wizards,spell-engine,spell-power}.pw.toml` + every other entry the script reports as only needed by them
  (candidates: `runes`, `bundle-api`, `armor-model-api`, `player-animation-library`, `trinkets-updated`, `cloth-config`, `structure-pool-api`)
- Modify: `pack/index.toml`, `pack/pack.toml` (version `0.2.0`), `server/deploy.md` ("What is in the pack", dependency note, remove the Wizards handbook + sources)

**Interfaces:**
- Produces: `scripts/check-pack-deps.py [pack-dir]` — downloads each `pack/mods/*.pw.toml` jar into `~/Library/Caches/minecraft-office-tools/pack-jars/`
  (verifying the listed hash), reads `fabric.mod.json` (`id`, `provides`, `depends`, nested `jars`), and prints (a) missing required ids
  (exit 1), (b) library mods nothing depends on. Builtins `minecraft`, `java`, `fabricloader`, `fabric-api*`, `fabric-*` are always present.
- [ ] **Step 1:** Run `python3 scripts/check-pack-deps.py pack` on the current pack → expect "missing: none". Record which mods depend on each candidate.
- [ ] **Step 2:** Remove Wizards, Spell Engine, Spell Power with `~/Library/Caches/minecraft-office-tools/bin/packwiz remove <slug>` (run inside `pack/`),
  rerun the script, and remove each candidate it lists as unused **and** that only the removed mods required (keep anything a world/QoL mod needs).
  `packwiz refresh`.
- [ ] **Step 3: Verify:** `python3 scripts/check-pack-deps.py pack` → "missing: none", no unused library left; `python3 scripts/check-pack.py pack` → "pack ok".
- [ ] **Step 4: Verify the pack still loads** (same procedure as the v1 Task 2 report): serve `pack/` with `python3 -m http.server`, install with
  `packwiz-installer-bootstrap.jar -g -s server` into `~/Library/Caches/minecraft-office-tools/server-test/`, start the Fabric 26.3 server via
  `scripts/heavy.sh` (Temurin 25, `-Xmx2G nogui`) → log has `Done (` and no "Incompatible mods" / "requires"; then `stop`; delete the test world.
  Client side is checked in Task 25 with `scripts/play-test.sh`.
- [ ] **Step 5: Commit** `git add scripts/check-pack-deps.py pack server/deploy.md && git commit -m "feat(pack): drop Wizards, Spell Engine, Spell Power and their unused deps"`.

**Phase M1 checkpoint:** `scripts/heavy.sh ./gradlew build test runGametest --no-daemon` → BUILD SUCCESSFUL, no failed GameTests.

---

## Phases M2 and M3 — one task per element (parallel; each in its own worktree)

Every element task follows the same contract:
- Touches only `magic/spell/<element>/`, `client/magic/fx/<element>/`, `magic/<Element>GameTests.java`, `test/.../magic/spell/<element>/`.
- One server class per spell `implements Spell` (id `office:<id>`, slot from the table, default `SpellCost.forSlot` unless the row says otherwise),
  registered in `<Element>Spells.register()`; one client `SpellFx` per spell registered in `<Element>Fx.register()`.
- Each `cast` returns exactly one `CastOutcome.cast(fx)`; lingering behaviour runs in `ActiveEffects` on the server and `FxKit.simulate`
  on the client from the same `FxEvent` (duration, seed, `data`) — never extra packets.
- Small spells never call `TerrainEdits`/`TempBlocks`; mediums use `EditTier.MEDIUM`; ultimates `EditTier.ULTIMATE` and `launch` for flying debris.
- Ultimate radius/size scales `min(cap, base + (level − 7))` as given; all ultimates screen-shake ≥ 0.8 within 32 blocks and hit-stop 3 ticks for the caster.
- GameTests run in `MagicTestKit.ARENA`, caster at the kit's stand point facing +Z, dummies NoAI. Assertions use "≤ 20 − X" health
  bounds (armor-free zombies) and block counts in the arena.
- Steps per task: (1) write the GameTests/unit tests listed, (2) `scripts/heavy.sh ./gradlew build test runGametest --no-daemon` → FAIL,
  (3) implement server spells, (4) implement client fx, (5) run → PASS, (6) commit `feat(magic/<element>): <spells>`.

Values per spell (P = power; "ahead" = along the caster's look; distances in blocks, times in ticks).

### Phase M2 — Monday floor (slot 0 small A + slot 1 medium A)

#### Task 12: Fire — Fire Bolt, Fire Wall
- `fire_bolt` (0): projectile speed 2.5, radius 0.3, max 20 ticks, no gravity; hit: 4×P damage, fire 60, knock 0.4. FX: flame trail, small pop on hit.
- `fire_wall` (1): zone 4 ahead on the ground, perpendicular, width 5 (7 at charge ≥ 0.5), height 3, lasts 100 + 60×charge; every 10 ticks entities inside take 2 + fire 80;
  arrows/projectiles entering are discarded. Minor permanent: snow layers and ice in the footprint melt (snow → air, ice → water) via MEDIUM batch. FX: flame curtain for the duration.
- [ ] GameTests: `fireBoltHurtsAndIgnitesZombie` (zombie 6 ahead → health ≤ 16 within 10 ticks, on fire); `fireBoltStopsAtWall` (stone wall 3 ahead, zombie behind it unharmed);
  `fireWallBurnsZombieInside` (zombie 4 ahead → health < 20 and on fire by tick 30); `fireWallOnlyMeltsSnow` (stone/grass in footprint unchanged, snow layer gone);
  `fireWallSparesPlayer` (second mock player inside → full health, not burning).

#### Task 13: Water — Water Bullet, Tidal Push
- `water_bullet` (0): projectile speed 2.8, radius 0.25, max 20; hit: 3×P damage, knock 1.2 along travel, extinguishes the target.
- `tidal_push` (1): cone range 8 + 4×charge, half-angle 35°: pushables knocked 1.6×P horizontal + 0.3 up, burning entities extinguished, fire blocks in the cone removed (MEDIUM);
  temporary wave: a 5-wide front of temp water advancing 1 block per 2 ticks for 8 blocks, each cell lives 6 ticks (air only). FX: wave spray.
- [ ] GameTests: `waterBulletKnocksBackZombie` (zombie 5 ahead moves ≥ 1 block +Z within 10 ticks, health ≤ 17); `tidalPushPushesItemsAndMobs` (zombie + item 4 ahead both move ≥ 2 +Z);
  `tidalPushExtinguishesFire` (fire block in cone → air); `tidalWaveLeavesNoWater` (after 100 ticks the arena has 0 water blocks, flows included).

#### Task 14: Wind — Gust, Blade Wind
- `gust` (0): cone range 6 + 2×charge, half-angle 30°: 1 damage, knock 1.4×P + 0.4 up, items and XP orbs pushed too.
- `blade_wind` (1): 3 blades in a ±15° fan (5 blades ±25° at charge ≥ 0.5), projectile speed 2.0, radius 0.6, pierce, max 14; hit 5×P;
  minor permanent: blades cut `#minecraft:leaves`, `#minecraft:flowers`, short/tall grass, fern, large fern, dead bush along their path (MEDIUM, ≤ 64). FX: white crescents.
- [ ] GameTests: `gustLaunchesZombie` (zombie 3 ahead gains ≥ 0.3 upward velocity, moves +Z); `bladeWindPiercesTwoZombies` (zombies 3 and 6 ahead in line both hurt);
  `bladeWindCutsLeavesNotLogs` (oak leaves + log 5 ahead → leaves gone, log stays); `gustDoesNotPushPlayer`.

#### Task 15: Lightning — Spark, Chain Lightning
- `spark` (0): hitscan 16 (forgiveness 0.5): 3×P damage; arcs to the 1 nearest other target within 4 for 60 %. FX: `FxKit.bolt` with entity ids in `entities`.
- `chain_lightning` (1): first target by ray ≤ 20 (forgiveness 1.5), then hops to the nearest unhit target ≤ 6, `4 + round(2×charge)` hops, damage 6×P × 0.8^hop;
  minor permanent: sand directly under each hit target → glass (MEDIUM). FX: bolt per hop + flash; ids in `entities`.
- [ ] Unit test `ChainHopsTest` (pure hop selection on points: picks nearest unhit, stops when none ≤ 6, never revisits). GameTests: `sparkHitsZombie` (health ≤ 17);
  `chainLightningHopsFiveZombies` (5 zombies 3 apart, charge 0 → all hurt, damage decreasing along the chain); `chainSkipsVillager` (villager in the chain unhurt, chain continues);
  `chainGlassesSandUnderTargets`.

#### Task 16: Earth — Stone Bullet, Earth Wall
- `stone_bullet` (0): projectile speed 2.2, gravity 0.02, radius 0.35, max 30; hit 5×P, knock 1.0. FX: stone-block particles.
- `earth_wall` (1): wall 3 ahead, perpendicular, width 5 (7 at charge ≥ 0.5), height 3 (4 at charge 1); temp blocks of the block under each column if it is a full solid
  block, else cobblestone; rises column by column over 6 ticks; lasts 600; air/replaceable only; entities in a wall cell are pushed up onto it. FX: dust + rising ghost blocks.
- [ ] GameTests: `stoneBulletArcsAndHits` (zombie 10 ahead hurt); `earthWallRisesFromGroundMaterial` (grass floor → 15 grass/dirt-type temp blocks, cobblestone on slabs);
  `earthWallBlocksArrows` (arrow fired at the wall stops); `earthWallFallsAfterThirtySeconds` (tick 601 → all cells air); `earthWallNeverReplacesChest` (chest in a cell stays).

#### Task 17: Shadow — Shadow Bolt, Gravity Well
- `shadow_bolt` (0): projectile speed 2.2, radius 0.3, max 24; hit 4×P + Weakness I 60. FX: smoke/portal trail.
- `gravity_well` (1): point by ray ≤ 16 (air 1 above ground); lasts 80 + 40×charge; pushables within 6 pulled toward the centre (0.12 + 0.03 × distance per tick, capped 0.6);
  1 damage every 20 ticks within 2. No block changes. FX: dark core + inward spiral.
- [ ] Unit test `WellPullTest` (pull vector points at the centre, magnitude capped 0.6, zero at the centre). GameTests: `shadowBoltWeakensZombie`;
  `gravityWellPullsZombiesAndItems` (zombie 5 from centre ends < 2 from centre by tick 60); `gravityWellChangesNoBlocks`; `gravityWellIgnoresPlayers`.

**Monday floor checkpoint (after Tasks 12–17 merge):** `scripts/heavy.sh ./gradlew build test runGametest --no-daemon` green; push feat/v1 → CI client
gametests green; build the jar and run `scripts/play-test.sh` for Carter (grimoire, choice, wheel, charge circles, mana, Recall, 2 spells/element).

### Phase M3 — full set (slot 2 small B, slot 3 medium B, slot 4 ultimate)

#### Task 18: Fire — Flame Dash, Fireball Barrage, Meteor
- `flame_dash` (2): horizontal dash `look_h × (1.6 + 0.5×charge)` + 0.2 up, no fall damage 40; entities within 1.5 of the caster during the next 8 ticks: 3×P + fire 40 (each once). FX: flame trail.
- `fireball_barrage` (3): `3 + round(2×charge)` fireballs, one every 4 ticks, at the ray point ≤ 40 with ±1.5 seeded spread; projectile speed 1.8, gravity 0.03;
  impact 5×P within 2.5 (linear falloff), knock 0.8; minor permanent crater `Shapes.bowl(1.5, 0.6)` (MEDIUM), 20 % of exposed floor → netherrack. FX: explosion puffs, shake 0.2 within 16.
- `meteor` (4, signs): target = ray point ≤ 64 (miss → ground 32 ahead); rock falls from +40 over 40 ticks; impact 20×P within 8 (falloff), knock 2.5 + 0.8 up;
  permanent bowl radius `min(12, 7 + (level − 7))`, depth factor 0.6; 40 rim blocks `launch`ed outward (≤ 48); floor 30 % magma block, 30 % blackstone. FX: sky circle at cast, flaming ghost-block rock, flash, shake 1.0.
- [ ] GameTests: `flameDashMovesAndBurns` (caster moves ≥ 5 +Z within 10 ticks, zombie on the path burning, no fall damage); `barrageFiresThreeAtChargeZero` (3 impacts, 3 small craters);
  `meteorCratersAndKills` (castUltimate, zombie at target dead by tick 45, ≥ 100 blocks removed, radius ≤ 7 at level 7); `meteorRespectsClaimFallback` (emitter 20 from target → 0 blocks changed, zombie still hurt).

#### Task 19: Water — Bubble Shield, Water Prison, Water Dragon
- `bubble_shield` (2): 120 + 60×charge: Resistance I; each tick removes `Projectile` entities not owned by the caster within 2.5. FX: bubble sphere.
- `water_prison` (3): target by ray ≤ 24; temp water sphere radius 2 around it (air only) for 100 + 40×charge; target Slowness III and pulled to the centre each tick while inside. FX: ripple shell.
- `water_dragon` (4, signs): dragon flies caster → ray point ≤ 48 over 30 ticks (client spline from seed); impact 18×P within 6, knock 2.0;
  permanent flood: water sources in air cells within radius `min(12, 6 + (level − 7))`, y ≤ impact y, each with solid or water below (≤ 600); grass/dirt tops within radius+3 → mud at 40 %. FX: dragon of water particles, splash, shake 0.8.
- [ ] GameTests: `bubbleShieldDeletesArrows`; `waterPrisonHoldsZombieThenDrains` (zombie stays within 1 of start for 80 ticks; arena water count 0 at tick 220, flows included);
  `waterDragonFloodsAndStays` (≥ 50 water sources remain at tick 200; none placed above impact y); `waterDragonNeverFloodsAChest` (chest cell untouched).

#### Task 20: Wind — Air Step, Tornado, Wind Sphere
- `air_step` (2): castable mid-air: velocity y = 0.9 + 0.2×charge, plus `look_h × 0.4`; no fall damage 60. FX: ring under the feet.
- `tornado` (3): starts on the ground at ray point ≤ 20, travels `look_h` at 0.15/tick for 120 + 40×charge; pushables within 4: tangential 0.35, inward 0.15, up 0.25 per tick;
  1 damage every 10 ticks. No block changes. FX: swirling column on the same path (client simulates from origin, dir, speed, duration).
- `wind_sphere` (4, signs): caster dashes 1.8/tick along look for ≤ 10 ticks; first entity within 1.5 or block hit → detonation 22×P within 4, knock 3.5;
  permanent drill: cone from impact along look, length `min(12, 8 + (level − 7))`, radius 3 → 1; ≤ 32 removed blocks `launch`ed in a spiral, rest removed. FX: spinning sphere in hand, spiral shockwave, shake 0.9.
- [ ] Unit test `TornadoPathTest` (position at tick t = origin + dir × 0.15t, same on both sides). GameTests: `airStepWorksMidAir` (caster falling → upward velocity ≥ 0.9, no fall damage on landing);
  `tornadoLiftsZombie` (zombie gains ≥ 2 blocks height by tick 40); `windSphereDrillsTunnel` (stone block wall 6 ahead → ≥ 20 blocks removed in a cone, none beyond 12);
  `windSphereStopsAtFirstZombie`.

#### Task 21: Lightning — Blink, Thunder Spear, Chidori Strike
- `blink` (2): teleport along look up to 10 + 4×charge via `Motion.teleportSafe`, velocity zeroed, no fall damage 20. FX: afterimage bolt.
- `thunder_spear` (3): projectile speed 3.0, gravity 0.01, max 30; impact 10×P within 2.5 + Slowness II 40, visual-only `LightningBolt` (`setVisualOnly(true)`);
  minor permanent bowl radius 2 (MEDIUM), sand → glass within 3. FX: spear trail + bolt.
- `chidori_strike` (4, signs): target entity by ray ≤ 16 (forgiveness 2) or point ≤ 16; caster moves along the path over 6 ticks; end: 24×P to the target, 8×P within 3,
  Slowness V + Weakness 60; 3 visual-only bolts; permanent trench along the path (2 wide, 2 deep below the surface, edges 30 % blackstone, sand → glass)
  and a bowl radius `min(12, 4 + (level − 7))` at the end. FX: crackling hand, path bolts, shake 0.9.
- [ ] GameTests: `blinkStopsBeforeWall` (wall 6 ahead → caster ends ≤ 5.5 ahead, not inside the wall); `blinkIntoCaveCeilingIsSafe` (2-high tunnel, no suffocation damage over 20 ticks);
  `thunderSpearStartsNoFire` (0 fire blocks in the arena at tick 60); `chidoriTrenchesPathAndHits` (zombie 12 ahead hurt ≥ 20, trench cells along the path air).

#### Task 22: Earth — Earth Spikes, Quake, Mountain Fist
- `earth_spikes` (2, small → no blocks): line of 8 + 4×charge cells ahead along the ground, one per tick; entities on a cell: 4×P + 0.6 up. FX: dripstone ghost blocks rising for 60 ticks.
- `quake` (3): ring expanding from the caster 1 block per 2 ticks to radius 6 + 2×charge; grounded entities on the ring: 5×P, 0.7 up, knock 0.6 outward;
  minor permanent: 25 % of ring surface blocks crack (grass block → dirt, stone → cobblestone, dirt → coarse dirt; ≤ 120, MEDIUM). FX: real ground ghost blocks pop up and fall back along the ring.
- `mountain_fist` (4, signs): target = ground at ray point ≤ 32; 20-tick rise; entities within 5: 24×P, launched 1.6 up; permanent spire at the target: `Shapes.cone(min(6, 3 + (level − 7)), min(12, 8 + 2×(level − 7)))`
  of 60 % stone, 25 % the target's surface block, 15 % andesite, air/replaceable cells only, placed bottom-up over 20 ticks (≤ 900). FX: giant fist of ghost blocks, shake 1.0.
- [ ] GameTests: `earthSpikesChangeNoBlocks` (arena block hash unchanged at tick 80, zombie on the line hurt); `quakeLaunchesGroundedNotFlying` (grounded zombie up, flying bat untouched);
  `quakeCracksAtMostQuarter`; `mountainFistBuildsSpire` (level 7: ≥ 60 blocks placed, height ≤ 8, none inside a chest cell, zombie launched).

#### Task 23: Shadow — Shadow Step, Shadow Bind, Black Hole
- `shadow_step` (2): target by ray ≤ 16 (forgiveness 1.5) → teleport 1.5 behind it facing it (`teleportSafe`); none → 8 ahead; Invisibility 20. FX: smoke at both ends.
- `shadow_bind` (3): target by ray ≤ 20: 4×P, then rooted 60 + 40×charge (Slowness amplifier 5, velocity zeroed each tick). FX: tendrils for the duration.
- `black_hole` (4, signs): at ray point ≤ 24 (2 above ground); lasts 100; pushables within 12 pulled (0.08 → 0.25 over its life); 4 damage every 10 ticks within 2.5;
  permanent devour: sphere grows from 1 to `min(12, 6 + (level − 7))` over its life (≤ 1500), 10 % of the rim → crying obsidian/blackstone (half each); end: knock 2.0 outward + 10×P within 6. FX: lensing core, inward debris ghost blocks (client-only), shake 1.0.
- [ ] GameTests: `shadowStepLandsBehindZombie` (caster ends on the far side, ≤ 2 from it, facing it); `shadowBindRootsZombie` (moves < 0.5 in 60 ticks);
  `blackHoleDevoursSphereWithinCap` (level 7: ≥ 200 blocks removed, none beyond 6.5 from centre); `blackHoleSparesBlockEntities` (furnace inside the sphere still there).

---

## Final

### Task 24: Balance pass + client GameTests

**Files:**
- Modify: `mod/src/gametest/java/local/carter/office/client/MagicClientGameTests.java`, `magic/core/Slots.java` and spell classes (only numbers), `docs/superpowers/plans/2026-10-23-grimoire-magic.md` (record final numbers in Global Constraints)
- Test: `mod/src/gametest/java/local/carter/office/magic/BalanceGameTests.java` (create + register)

- [ ] **Step 1: Write the tests.** `BalanceGameTests`: `everyElementHasFiveSpellsAndRecall` (`wheel(e)` = 6 for each element);
  `everySpellCastsAtLevelTenWithoutError` (each of the 31 spells cast once in a fresh arena, Result CAST or CHALLENGED → answered → CAST; no exception);
  `smallSpellsNeverEditBlocks` (arena block hash identical after every slot 0 and 2 spell); `noSpellExceedsBudgets` (recorder: ≤ 1 fx per cast, ≤ 64 entities hurt, edits ≤ tier budget);
  `zombieTimeToKillBands` (level 1, charge 0: each slot-0 damage spell — fire_bolt, water_bullet, spark, stone_bullet, shadow_bolt — kills a
  20-hp zombie in 3–7 casts).
  `MagicClientGameTests` adds: `chargeCircleGrowsWhileHeld` (hold right-click 40 ticks, screenshot `charge-full.png`, mana unchanged until release);
  `tapCastsQuickly` (press + release within 5 ticks → mana −8); `handSignPromptAppearsForUltimate` (level 7, select slot 4, cast → `HandSignScreen`, type the sequence → mana −60);
  `typingModeBlocksMagic` (enter typing mode → R does not open the wheel, right-click no cast); `screensFitAtGuiScale4` (choice, grimoire, wheel, sign screenshots at GUI scale 4).
- [ ] **Step 2: Run** `scripts/heavy.sh ./gradlew build test runGametest --no-daemon` → note failures; tune numbers only, then rerun → PASS.
- [ ] **Step 3: Push feat/v1; CI client gametests green** (`gh run watch`); review the screenshots artifact.
- [ ] **Step 4: Commit** `test(magic): balance pass + client gametests`.

### Task 25: Friend docs and release

**Files:**
- Modify: `docs/friends/SETUP.md` (§9 "Magic in 10 minutes" rewritten for the grimoire), `server/deploy.md` (handbook text), `gradle.properties` (`version=0.2.0`), `pack/` (office mod entry → 0.2.0)

- [ ] **Step 1:** Rewrite §9: craft a Grimoire (book + feather + iron ingot, shown in the recipe book), right-click it to choose an element, hold it in
  the off hand, empty main hand: tap right-click to cast / hold to charge, R wheel or Shift+scroll, level up by casting, ultimates at level 7 with hand
  signs (arrow keys or WASD), Recall: Shift + cast to mark home, cast to go home (3 s, 60 s cooldown). Claims protect offices from big spells.
- [ ] **Step 2:** Release per `RELEASING.md` (tag `v0.2.0`, CI release, `scripts/release.sh`, pack commit pointing `office.pw.toml` at 0.2.0); replace the jars on the
  server and remove the Wizards stack from the server's `/mods` (list from Task 11), restart.
- [ ] **Step 3: Verify:** `python3 scripts/check-pack.py pack` → "pack ok"; `python3 scripts/check-pack-deps.py pack` → "missing: none";
  server log `Done (` with the new jar; `scripts/play-test.sh` launches, Mod Menu shows no Wizards/Spell Engine, grimoire craftable.
- [ ] **Step 4: Commit** `docs+release: grimoire magic 0.2.0`.

---

## Self-review notes

- Spec coverage: §0 recipe/casting/timing → Tasks 2, 4, 8, 10, checkpoints; §3 element choice → 3, 9; wheel → 8; hand signs → 6; mana → 3, 9;
  progression + evolving looks → 1, 2, 4, 10; 30 spells → 12–23; world effects → 5 + element tasks; Recall → 7; §4 look/perf → 10, budgets, 24;
  §5 reuse + pack → 10, 11; §6 holding/unlocks/terrain/recall/precedence/pack → 4, 5, 7, 8, 11; Rebinding Seal → 2, 3.
- Deviations: 6-slice wheel (Recall needs a slot); mana bar takes the XP bar's place only while a grimoire is held (keeps the HUD uncluttered;
  XP levels show on the wheel for ultimates); Earth Spikes is visual-only blocks because the spec forbids block changes for small spells.
- Open risks: OPAC 26.3 API shape (Task 5 Step 1 gate; fallback ready); Wildercord render hooks may not match `LevelRenderEvents` 1:1
  (Task 10); falling-block debris can land inside a claim and its landing is not undoable; player dashes may rubber-band on the real
  server (verify at the Monday checkpoint); Task 8 waits on the §5.9 pointer/typing merge.
