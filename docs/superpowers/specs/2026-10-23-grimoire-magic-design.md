# Grimoire Magic — design

Date: 2026-10-23. Owner: Carter. Status: approved by Carter 2026-10-23 evening (with the changes in section 0). Replaces the Wizards (RPG Series) mod on the Minecraft Office
server. Marks: **(Carter)** = Carter decided; **(my call)** = Claude's default, Carter may override.

## 0. Carter's final changes (override anything below)

- **Recipe:** book + feather + **iron ingot** — everyone should get it early.
- **Casting (Carter: B, off hand):** grimoire in the **off hand**; with an **empty main hand**, **tap right-click** = quick
  cast of the selected spell, **hold right-click** = the summoning circle grows (charge) and **release** fires a stronger
  version. With a tool/block in the main hand, right-click behaves normally (no magic). Select spells with **Shift + scroll**
  or the **R** wheel. Ultimates still need hand signs. The V cast key is dropped.
- **Timing (Carter: A, ambitious):** playable for Monday; aim for the full set (5 spells × 6 elements) polished by Sunday.
  Guaranteed Monday floor: grimoire + element choice + wheel + charge casting + circles + mana + Recall + 2 spells/element.

## 1. Intent

Carter disliked Wizards: too many new ingredients. He wants magic that is **simple but cool**: built from plain Minecraft
materials, with spells that look amazing and have real physics, inspired by **Naruto jutsu** and **Black Clover**. Big
spells should visibly change the world. Every friend should feel their magic is *theirs*.

## 2. Goals, non-goals, anti-goals

Goals
- One magic item: a **Grimoire** **(Carter)**, crafted from vanilla items, held in the **off hand** **(Carter: "maybe grimoire
  in off hand")** — main hand stays free.
- Each player **chooses an element** when they first open their grimoire **(Carter)**; the choice is a memorable moment.
  UI/UX must be amazing **(Carter)**.
- A **circle spell wheel** to pick spells **(Carter)**; spells launch from **vertical summoning circles** **(Carter)**.
- **Hand signs** (short on-screen key combo) only for the big spells **(Carter: B, only for big ones)**.
- Big spells **permanently** destroy, move and change terrain; small spells are lighter **(Carter)**.
- A simple, clean way to **mark home and always teleport back** (to the monitors/office) **(Carter)**.

Non-goals
- New ores, ingredients, mobs, dimensions, wands, rune systems **(Carter)**.
- PvP balance tuning for strangers (5 friends only).

Anti-goals
- Feels bolted on or off-vanilla; menus that are confusing; magic that lags the 8 GB M1 or the server.

## 3. Player experience

- **Get a grimoire:** craft from a book + feather + iron ingot **(Carter)**. First open → full-screen
  "choose your element" scene: the book opens, six element sigils orbit, hovering previews a signature spell; confirm to
  bind. Choice is permanent; a rare **Re-binding** (crafted from vanilla: e.g. nether star + book) lets you change **(my call)**.
- **Elements (my call):** Fire, Water, Wind, Lightning, Earth, Shadow.
- **Casting:** hold **R** → radial wheel of unlocked spells (mouse direction selects, release selects, never casts);
  **V** casts (or right-click with the grimoire in main hand) **(my call)**. A vertical summoning circle opens in front of
  the player (rings appear one by one), the spell launches from it.
- **Hand signs:** ultimates show 4–6 arrow prompts on screen; type them (arrow keys / WASD) within a short time; miss →
  fizzle with a sound, mana partly spent **(my call)**.
- **Mana:** one bar above the hotbar, regenerates; ultimates also cost XP levels **(my call)**.
- **Progression:** the grimoire **levels up from casting** (grimoire XP). New spells unlock per level; the grimoire's look
  evolves (glowing pages, cover trim, floating particles in off hand) **(my call)**.
- **Spells (my call):** per element 5 spells — 2 small, 2 medium, 1 ultimate (hand signs). E.g. Fire: Fire Bolt, Flame Dash,
  Fire Wall, Fireball Barrage, **Meteor** (ultimate). Water: Water Bullet, Bubble Shield, Tidal Push, Water Prison,
  **Water Dragon**. Wind: Gust, Air Step (double jump), Blade Wind, Tornado, **Rasengan-style Wind Sphere**. Lightning:
  Spark, Blink (lightning dash), Chain Lightning, Thunder Spear, **Chidori Strike**. Earth: Stone Bullet, Earth Spikes,
  Earth Wall, Quake (ground ripples), **Earth Golem / Mountain Fist**. Shadow: Shadow Bolt, Shadow Step (teleport behind),
  Gravity Well, Shadow Bind, **Black Hole**. Names/effects to be refined; inspired by, not copied from, Naruto/Black Clover.
- **World effects:** small spells: particles + knockback only. Medium: temporary or minor block changes. Big/ultimate:
  **permanent** terrain change (craters, walls, flood water, displaced blocks with physics) **(Carter)** — but **never inside
  another player's land claim** (Open Parties and Claims) and never in spawn-protected areas **(my call)**.
- **Temporary blocks are never a source of items (Carter):** a temporary block (Earth Wall, ice, glass…) broken by any means —
  mining in survival (any tool, silk touch included), explosions (wind bursts break nothing, as in vanilla), `Level.destroyBlock` (the wither, mods) — drops no items and
  no XP, and the **original block comes back at once** (the entry is done, so the timed restore never fights the player).
  Pistons can't move one (immovable, like obsidian), endermen can't pick one up, silverfish can't infest one, and fluids can't
  wash one out (only solid, unwashable blocks may be temporary). Code: `magic/terrain/TempBlockGuard` and the server mixins.
- **Recall (everyone, any element) (Carter + my call):** "Mark Home" (shift + cast Recall at a spot) and "Recall" (channel
  2 s with a circle under you → teleport home). Available from level 1.

## 4. Look and feel

- Vertical summoning circles (layered rings, runes, element colour), screen shake + hit-stop for big hits, physics:
  thrown mobs, flying block-display chunks, shockwaves rippling real ground blocks.
- Wheel, element choice and grimoire screens: polished, animated, no scrolling, readable at any GUI scale.
- Performance budget: one effect packet per cast, particles client-side, block changes batched; must stay smooth on the
  8 GB M1 with 5 players.

## 5. Platform and reuse

- Same Fabric 26.3 mod repo (`Minecraft Office`), new package `local.carter.office.magic` **(my call)**, or a sibling mod
  in the same build — builder decides.
- Reuse with attribution: **Wildercord** (MIT, Fabric 26.3) for magic-circle rendering, glow pipeline, screen shake and
  wheel code; **Avatar Bending** (MIT) restore/physics patterns as ideas. Iron's Spells (ARR) / Ars Nouveau (LGPL): ideas
  only. Research notes: `.superpowers/sdd/2026-10-23-minecraft-office-v1/magic-research.md`.
- Remove Wizards + Spell Engine/Power and their now-unneeded deps from the pack; keep Better Combat? (only if needed).

## 6. Calls made after the builder check (my call unless marked)

- **Holding & casting:** the grimoire normally sits in the off hand; **V** casts from either hand; right-click also casts
  when it's in the main hand. Swap-hands (F) is fine; it can't be a shield/totem.
- **Unlocks:** all spells come from your chosen element, unlocked automatically by grimoire level; no cross-element
  spells except Recall. No tomes.
- **Terrain:** permanent changes allowed for medium/big spells (Carter), with caps (radius ≤ 6 for medium, ≤ 12 for
  ultimates), never touching block entities (chests, furnaces, emitters…), bedrock or obsidian, never inside a claim
  (Open Parties and Claims; if no usable API on 26.3, fall back to: no terrain change within 32 blocks of any hologram
  emitter or another player's bed/home), and an op command `/magic undo <player> [n]` that restores that player's last n
  terrain-changing casts (edits logged to disk).
- **Recall:** one home per player ("Mark Home" anywhere, any dimension); Recall = 3 s channel with a circle under you,
  cancelled by taking damage, 60 s cooldown, no mana cost. Waystones and JEI are removed from the pack **(Carter, 2026-10-24)**.
- **Travel to a friend (Carter, 2026-10-24; details my call):** the grimoire screen gets a **Friends** page listing online
  players; clicking one sends a request, the friend gets a chat prompt **[Accept] [Deny]** (expires after 30 s). On accept,
  the caster channels 3 s like Recall (same circle, cancelled by damage) and lands at a safe spot beside the friend (same
  safety rules as Recall, any dimension). Shares Recall's 60 s cooldown, no mana, available from level 1. One pending
  request per caster; a friend can turn requests off with `/magic travel off`.
- **Input precedence:** while aiming at a hologram, clicks belong to the monitor; R/V still work; typing mode blocks all
  magic keys. Hand-sign entry uses arrow keys and WASD while the sign prompt is open (movement paused during it).
- **Pack:** clean swap — remove Wizards, Spell Engine, Spell Power and any deps only they need (incl. Better Combat
  unless something else needs it). Wizards items in the existing Bloom world simply vanish (only test play so far).
- **Reuse:** Wildercord (MIT) magic-circle rendering, glow and screen shake with attribution; our own wheel (5 slots)
  and grimoire screens.

## 7. Left to the builder

Exact recipes (vanilla items only), numbers (mana, cooldowns, XP curve), effect implementation details, hand-sign
timing, file layout, test strategy (pure logic unit tests + server GameTests + client gametests for UI).

## 8. Order

After the monitor work (in-world interaction) is done and Carter has tested it **(Carter)**.
