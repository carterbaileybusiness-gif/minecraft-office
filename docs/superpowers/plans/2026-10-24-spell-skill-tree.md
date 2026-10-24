# Spell skill tree — build plan

Spec: `docs/superpowers/specs/2026-10-24-spell-skill-tree-design.md` (Carter: XP + element materials, forks at III and V,
"make the UI and UX amazing and easy to understand and use"). Ranks stack on top of the play-test rulings (animals fair
game, full charge ≈ 2× a tap, no duds). Three commits: this plan, the server, the client UI.

## 1. Data model

- **Storage:** `MagicData.ranks`: `Map<Identifier, Integer>` spell id → packed rank (`rank | fork3 << 3 | fork5 << 5`,
  rank 0–5, fork 0 = none, 1 = A, 2 = B). Spell ids are per element, so ranks are per element by construction: a Seal
  rebind and back restores them. Persisted (lenient codec, bad entries dropped), synced to the owner (≤ 64 entries),
  `copyOnDeath` like the rest of `MagicData`: survives death, relog and losing the Grimoire.
- **`tree.SpellRank`** — the per-cast view: rank, forks, and `mul(Knob)` / `add(Knob)` / `is(forkKey)` / `riders()`.
  `CastService.run` puts it on `CastContext.rank()` and in `FxEvent.rank` (one packed int, so every client draws the
  caster's forks from the one fx packet).
- **`tree.SpellTree`** (one per element spell, `tree.Trees`): the spell's size knob, its two forks at III and at V
  (`Fork(key, mods, riders)`), and its stat lines for the UI (`Stat(key, unit, value(rank, level))`). The spells read the
  same constants the stat lines show, so the detail panel's numbers are the numbers the cast uses.
- **Knobs:** `DAMAGE, SIZE, MANA, COOLDOWN, DURATION, COUNT, KNOCK, RANGE, SPEED`. Each spell honours `DAMAGE` (through
  `power`), `MANA` and `COOLDOWN` (in `CastService`), `SIZE` (its one size knob) and whatever else its own forks use.
- **Riders** (generic fork behaviour, applied in `Hurt.damage` after a hit lands — the one damage path every spell uses):
  `IGNITE(ticks)`, `EFFECT(effect, ticks, amp)`, `LIFESTEAL(share)`, `SPLASH(radius, share)` (no recursion; line of sight).
  A control effect (slowness, blindness, weakness, levitation, darkness, mining fatigue, nausea: `Hurt.controls`) is given
  only to what `TargetFilter.controllable` takes, so a boss takes the damage but never Petrify, Stun or Concussion. Life
  steal heals a share of the health the hit really took (after armour, never more than the target had). Fork blasts,
  zones and rings burn or throw only creatures inside the cast's budget (`Hurt.admit`).
- **No permanent self-buffs** (Carter's ruling, review fix round): a status effect a fork gives the caster lasts at most
  half the spell's cooldown at the caster's rank (`RankKit.buffTicks`): Phoenix Guard 1 s of 2 s, Surge 1 s of 2 s (0.5 s
  of 1 s with Quick Charge), Phantom 1 s of 2 s, Bastion 1.5 s of 3 s per wall, Stone Skin 10 s of 30 s. Mending, Fortress
  and Air Dash give none.
  Spell-specific forks are checked in the spell with `ctx.rank().is(KEY)`.
- **Per-cast entity budget, by construction:** `Hurt.damage` refuses a new creature once the cast (caster + castId) has
  hurt `Targets.MAX_ENTITIES`, so no fork (splash, extra bolts, zones) can push a cast past the cap.

## 2. Ranks

Ranks I, II and IV are **bumps**: each +15 % damage, +15 % on the spell's size knob, −10 % mana (three bumps: ×1.45,
×1.45, ×0.70). Ranks III and V are **forks**: buying the rank is choosing one of two options; they carry their own numbers.
Terrain shapes clamp to the tier caps (medium ≤ 6, ultimate ≤ 12) and every existing guard (claims, block entities,
temp-block rules, budgets, the 64-entity cap, `TargetFilter`) is untouched. Small spells and all their forks never change
a block. ×P = the cast's power (charge, grimoire level, rank damage).

### Fire — knob per spell, then III-A / III-B / V-A / V-B
| Spell | Size knob | III A | III B | V A | V B |
|---|---|---|---|---|---|
| Fire Bolt | bolt radius | **Scatter**: 3 bolts in a 16° fan, 55 % damage each | **Searing**: burns 2× as long; +50 % damage to a burning foe | **Lance**: pierces creatures, flies 50 % farther | **Flare**: bursts on impact: 50 % damage + 3 s burn within 2.5 |
| Fire Wall | width (≤ 12) | **Inferno**: burns every 5 ticks (was 10) | **Firebreak**: throws back what touches it (0.8) | **Marching Wall**: rolls 6 blocks forward over its life | **Backdraft**: dies in a blast: 4× a pulse + 5 s burn within 3 |
| Flame Dash | burn reach | **Overdrive**: dashes 40 % farther | **Phoenix Guard**: puts you out; Fire Resistance + Resistance I for 1 s | **Comet Finish**: ends in a blast: 5 × P + thrown back within 3 | **Searing Wake**: burnt foes Slowness II 3 s, burn 3× as long |
| Fireball Barrage | fireballs (≤ 9, then the fork's extra: ≤ 11) | **Carpet**: +2 fireballs, spread ×1.6 | **Napalm**: burns 3× as long + Slowness I 3 s | **Guided Shells**: each fireball falls on its own creature within the volley's spread + 3 (nearest first; the rest where the seed puts them) | **Heavy Shells**: blast 3.5 (was 2.5), crater 2 (was 1.5), −1 fireball |
| Meteor | blast radius | **Scorched Earth**: crater burns 5 s: 2 dmg every 0.5 s + burning, within 6 | **Shockwave**: knockback ×1.6, thrown 2× higher | **Extinction**: crater +2 (≤ 12), damage +25 % | **Cluster**: shatters into 6 burning fragments: 6 × P each within 2.5, 5–8 out |

### Water
| Spell | Size knob | III A | III B | V A | V B |
|---|---|---|---|---|---|
| Water Bullet | jet radius | **Riptide**: knockback ×1.8 | **Frost Jet**: Slowness II 4 s | **Pressure Lance**: pierces, flies 50 % farther | **Splash**: bursts: 50 % damage + push within 2.5 |
| Tidal Push | cone/wave range (≤ 24) | **Breaker**: the wave hits: 4 × P to all it carries | **Surge**: shove ×1.6 | **Tsunami**: range +50 % (≤ 24), carries 2× harder | **Frigid Wave**: all it carries Slowness III 4 s |
| Bubble Shield | bubble radius | **Mirror**: an arrow it stops from a shooter spells may hurt comes back as a 4 × P hit on that shooter (`Hurt.damage`, never a live projectile: no player, pet, villager, golem or named mob is ever hit; fireballs, skulls, rockets, potions and tridents are only stopped) | **Mending**: each projectile it stops heals you a heart | **Fortress**: lasts 50 % longer, shoves creatures that touch it back out | **Pop**: when it ends it bursts: 4 × P + push within its radius |
| Water Prison | sphere radius | **Crushing Depths**: the prisoner takes 1.5 × P a second | **Long Hold**: lasts 60 % longer | **Twin Prison**: a second sphere takes the nearest other creature within 8 | **Hurl**: when it bursts it flings its prisoner where you look (1.6, 0.6 up) |
| Water Dragon | strike radius | **Drowning Tide**: struck foes lose all air, Slowness III 5 s | **Torrent**: knockback ×1.6, strike radius +2 | **Leviathan**: flood radius +2 (≤ 12), damage +25 % | **Maelstrom**: a whirlpool 5 s: drags creatures within 8 in, 2 dmg every 0.5 s |

### Wind
| Spell | Size knob | III A | III B | V A | V B |
|---|---|---|---|---|---|
| Gust | cone range | **Vacuum**: the gust sucks: creatures (never a boss), items and XP come toward you, to rest about 1.5 away | **Updraft**: throws creatures 0.9 up (was 0.4) | **Cyclone**: cone 50° (was 30°) | **Wind Wall**: the gust stands 3 s, 3 ahead, 2.5 wide, swatting every projectile not yours out of the air |
| Blade Wind | blades (≤ 9) | **Wide Arc**: fan ±40° | **Boomerang**: at the end of their flight (or a wall) the blades fly back the same way, striking again (each creature once out, once back) | **Cleave**: +80 % damage per blade, 2 fewer blades (the knob's count is capped at 9 first: 7 at full charge) | **Tempest Blades**: what they hit is thrown 0.8 up and 0.6 back |
| Air Step | jump height | **Air Dash**: cast in the air, it dashes 1.3 along your look instead of jumping | **Long Jump**: 2.2× forward | **Second Wind**: cooldown −60 % | **Shockwave Landing**: landing blasts: 3 × P + thrown out within 3 |
| Tornado | funnel radius | **Long Storm**: lasts 60 % longer | **Debris**: every second it hurls debris at the nearest creature outside the funnel within 8, in sight: 3 × P | **Twin Tornadoes**: a second one goes off 30° to the side | **Eye of the Storm**: radius +50 %, pull in ×2 |
| Wind Sphere | blast radius | **Long Drill**: drill +3 (≤ 12) | **Vortex Wake**: a vortex 3 s pulls creatures within 8 in | **Rasen-Shuriken**: blast ×2, throws creatures 1.2 up | **Shockwave**: a ring expands to 12, 6 × P + thrown out |

### Lightning
| Spell | Size knob | III A | III B | V A | V B |
|---|---|---|---|---|---|
| Spark | zap range | **Forked**: +1 arc | **Static**: Glowing 6 s + Slowness I 2 s | **Overcharge**: arcs at 100 % (was 60 %), leap 50 % farther | **Stun**: Slowness IV 1.5 s on all it strikes |
| Chain Lightning | hop range (≤ 9) | **Long Chain**: +2 hops | **Conductor**: a wet target (in water or rain) takes 2× and the chain does not fade past it | **Live Wire**: the chain stays live 3 s: every second it zaps every creature it struck again for 35 % (within 24 of you) | **Overload**: the last link explodes: 2× its hit + 6 × P within 3 |
| Blink | blink range | **Static Step**: 3 × P within 2.5 of where you leave and land | **Quick Charge**: cooldown −50 % | **Thunderclap**: landing: Slowness III 2 s + thrown back within 4 | **Surge**: you come out running: carried 1.2 along your look, Speed II 1 s (0.5 s with Quick Charge) |
| Thunder Spear | blast radius (≤ 6) | **Javelin**: 1.6× faster, no drop | **Magnet**: the impact drags creatures within blast + 4 in toward it | **Returning Spear**: after the strike it flies home at the spear's speed, striking each creature near its path once for 50 % | **Chain Spear**: arcs to 3 more creatures within 8 at 60 % |
| Chidori Strike | strike reach | **Raikiri**: the target takes +40 % and Slowness V 4 s | **Chirping Path**: creatures within 3 of your path take 6 × P | **Kirin**: 5 sky bolts round the end: 10 × P each within 2.5 | **Storm Trail**: the trench crackles 5 s: 3 × P a second + slowed |

### Earth
| Spell | Size knob | III A | III B | V A | V B |
|---|---|---|---|---|---|
| Stone Bullet | stone radius | **Boulder**: stone ×1.5, knockback ×1.5 | **Shrapnel**: shatters: 40 % within 2.5 | **Ricochet**: bounces to the next creature within 6 at 60 % | **Concussion**: Blindness 2 s + Slowness III 2 s |
| Earth Wall | wall life | **Thorns**: touching it hurts 2 × P a second and pushes back | **Upheaval**: rising throws creatures in front 1.2 back, 0.6 up | **Bastion**: Resistance II + Regeneration I beside it (within 4), 1.5 s per wall | **Crumble**: when it falls it crashes forward: 8 × P within 3 in front |
| Earth Spikes | line length | **Impale**: 2× damage, line 25 % shorter | **Seeker**: the line bends toward the nearest creature within 40° of your look and its reach, in sight | **Spike Field**: three lines in a ±20° fan | **Petrify**: Slowness VI 2 s + Weakness 4 s (never on a boss) |
| Quake | ring radius (≤ 12) | **Aftershock**: a second ring 1 s later, 50 % damage (no cracks) | **Fissure**: Slowness II 3 s, twice the cracks (≤ 120) | **Tectonic**: throws 2× higher and farther | **Seismic Pull**: the ring pulls creatures in instead |
| Mountain Fist | blast radius | **Stone Skin**: Resistance II 10 s | **Skyward**: launches 1.6× higher, blast +2 | **Colossus**: damage +25 %, blast +2, spire +2 high / +1 wide (≤ 12 / 6) | **Avalanche**: 8 boulders crash round it (6–10 out): 8 × P each within 2 |

### Shadow
| Spell | Size knob | III A | III B | V A | V B |
|---|---|---|---|---|---|
| Shadow Bolt | bolt radius | **Wither Bolt**: Wither II 4 s | **Twin Bolts**: 2 bolts ±5°, 65 % each | **Soul Siphon**: heals you 40 % of its damage | **Void Lance**: pierces creatures |
| Gravity Well | pull radius | **Mana Drain**: each creature it hurts gives you 1 mana, up to half the well's cost per cast | **Collector**: at its end every item and XP orb in its reach goes to your feet | **Singularity**: implodes at the end: 8 × P within 4 | **Repulsor**: at the end flings all it held out (1.6, 0.6 up) |
| Shadow Step | step range | **Backstab**: the target takes 6 × P | **Long Shadow**: the step reaches 60 % farther | **Shadow Swap**: trade places with the target | **Phantom**: Speed II + Strength I 1 s |
| Shadow Bind | root time | **Choking Shadows**: rooted foes take 1 × P a second | **Spreading Dark**: one more foe (2 at a tap, 3 at full charge) | **Leech**: rooted foes drain 1 hp a second to you | **Dread**: when the root ends: Blindness + Weakness 5 s |
| Black Hole | pull radius | **Hungering**: each creature it kills widens its pull by 1 (up to +4) | **Event Horizon**: pull 40 % stronger | **Supernova**: collapse blast ×2 radius, ×2 damage | **Dark Rain**: collapse fires 8 shadow bolts out: 6 × P each |

## 3. Costs (XP levels + materials; ultimates ×1.5 rounded up)

XP levels per rank I–V: **2 / 3 / 5 / 7 / 10**; ultimates **3 / 5 / 8 / 11 / 15**. Only plain stacks count (not
enchanted, renamed or damaged: an enchanted trident is never eaten). The last material of each chain is for the
**ultimate's V only** (so V of the other four never needs a boss or a treasure map).

| Element | I | II | III | IV | V (spells 1–4) | V (ultimate) |
|---|---|---|---|---|---|---|
| Fire | 6 blaze powder (9) | 4 magma cream (6) | 4 blaze rods (6) | 4 fire charges + 1 ghast tear (6 + 2) | 16 blaze rods | 16 blaze rods + 1 nether star |
| Water | 8 prismarine shards (12) | 6 prismarine crystals (9) | 2 nautilus shells (3) | 2 sponges (3) | 4 sponges + 4 nautilus shells | 1 heart of the sea + 4 sponges |
| Wind | 8 feathers (12) | 2 phantom membranes (3) | 3 breeze rods (5) | 8 wind charges (12) | 6 breeze rods + 16 wind charges | 1 heavy core + 16 wind charges |
| Lightning | 8 copper ingots (12) | 6 amethyst shards (9) | 2 lightning rods (3) | 4 glow ink sacs (6) | 8 glow ink sacs + 4 lightning rods | 1 trident or echo shard + 8 glow ink sacs |
| Earth | 8 iron ingots (12) | 6 gold ingots (9) | 2 diamonds (3) | 1 ancient debris (2) | 1 ancient debris + 3 diamonds | 1 netherite ingot + 3 diamonds |
| Shadow | 4 ender pearls (6) | 8 obsidian (12) | 1 echo shard (2) | 1 sculk catalyst (2) | 2 sculk catalysts + 1 echo shard | 4 dragon's breath + 2 echo shards |

(Amounts in brackets: the ultimate's.) Lightning rods: any oxidation, waxed or not. Maxing an element is one dedicated
session (e.g. Earth: 27 diamonds, 14 ancient debris; Shadow: 12 echo shards).

## 4. Rules and payloads

- **Gate:** rank I needs the spell unlocked by Grimoire level (`Slots.unlockLevel`); higher ranks need only the cost.
  Ranks go one at a time. Only your bound element's five spells (Recall has no tree). Creative: no cost.
- **C2S `RankUp(spell)`** — buy the next bump (I, II, IV). **C2S `ChooseFork(spell, rank, option)`** — buy rank III or V
  by choosing A (1) or B (2); for an owned fork rank it is a **re-pick**: needs an open re-pick window or a Rebinding
  Seal anywhere in the inventory (spends one and opens a 60 s window in which every fork of the element may be re-picked;
  ranks kept, materials not refunded). The window's end is server-authoritative and lives in the synced `MagicData`
  (`repickUntil`, a game time; persisted), so the client shows it after the book is closed and opened again. Both share one
  throttle like `SelectSpell` (10 a second, the rest ignored).
- **Server checks in order** (first failure wins, nothing is taken): not your element's spell → `NOT_YOURS`; maxed →
  `MAXED`; wrong payload for the next rank (RankUp at III/V, ChooseFork at a bump, a rank that is not next) →
  `WRONG_RANK`; spell locked → `LOCKED`; XP levels short → `NO_LEVELS`; materials short → `NO_ITEMS`; a re-pick of the
  option already held → `SAME`, without window or seal → `NO_SEAL`. Then it takes the levels and every item line in one go
  (the server thread: atomic), stores the rank and replies.
- **S2C `TreeResult(spell, rank, fork, outcome)`** — the screen plays the burst on `OK` (the synced `MagicData` already
  shows the new rank) and shows the reason otherwise.
- **Hook:** `CastContext.rank()` (`SpellRank`), `FxEvent.rank` (packed), `Hurt.damage` riders, `CastService` mana /
  cooldown knobs. Spells use `ctx.rank()` for their size knob and fork checks; nothing else changes shape.

## 5. Screen (Skill tree tab, the fourth index tab after Spells, Friends, How to cast)

```
 ┌ book spread ──────────────────────────────────────────────────────────────┐
 │ LEFT PAGE: tree                         │ RIGHT PAGE: detail panel          │
 │  [ico]  [ico]  [ico]  [ico]  [ico]      │  Fire Bolt III — choose a fork    │
 │  Bolt   Wall   Dash   Barr.  Meteor     │  ┌ Scatter ─────┐┌ Searing ─────┐ │
 │   ●      ●      ●      ◌      🔒        │  │3 bolts, fan  ││burns 2× long │ │
 │   ┃      ┃      ┃      ┆                │  │Dmg 4.6→2.5×3 ││Burn 3 s → 6 s│ │
 │   ●      ◎      ●      ┆                │  └──────────────┘└──────────────┘ │
 │   ┃      ┆     ◐◑                       │  Cost  ▣ Blaze rod 3/4  ✦ 4/5 lv  │
 │  ◐◑     ...                             │  [  Choose Scatter (costs …)   ]  │
 │  …                                      │  Need 1 more blaze rod            │
 └───────────────────────────────────────────────────────────────────────────┘
```
- Left page: five columns (slot order = wheel order), spell icon + Roman rank under it, five nodes on a line that fills in
  the element colour as ranks are bought; III and V are split nodes (two small circles, the picked one glows, the other
  greys). Node states by shape + icon + colour: **owned** filled disc + glow; **available** pulsing ring + "+";
  **can't afford** plain ring, cost dot red; **locked** padlock + "Lv N"; future ranks dim dots.
- Right page (fixed place, laid out by the pure `TreePanel` from its real strings): the spell's name; "Rank I of V" (a fork:
  "· choose one"); what the rank improves ("+15 % damage, +15 % reach, −10 % mana"); the spell's one-line description; the
  before → after table or the fork's two cards (name, its line, before → after; side by side when both names fit a card of
  56 units, else stacked; hovering a card shows all of it); the next milestone ("Rank III: choose Scatter or Searing").
  Anchored at the bottom: one row per cost, item icon, the amount in bold, the name, "(have 10)" green or red at the
  right, and "2 levels (have 12)" with an XP orb; then one primary button ("Rank up" / "Choose Scatter"), disabled with the
  reason on it ("Need 2 more: Blaze Rod"). Anything cut to fit ends with "…" and shows whole in a tooltip. When the panel is
  short the extras drop in a fixed order (body extras, improvements, milestone, description, card numbers) and nothing is
  ever drawn over anything else; `TreeLayoutTest` budgets every spell's longest strings at 320 × 240, 427 × 240 and
  480 × 270 (1080p on auto GUI scale).
- Left page, under the tree: a legend of the node marks (owned, can rank up now, missing materials, locked; one row each
  when the page is tall, else two by two), and the Re-pick switch. A node short of materials shows a red "!".
- Mouse: clicking a node selects it (the panel shows the selected node, so the button always acts on what it describes);
  hovering a node shows a tip (rank, fork name, what is short). Keyboard: arrows move between nodes (Left/Right step across
  a split node's two options, Left past the first column turns back a tab, Down past rank V reaches the Re-pick switch),
  Enter or Space presses the button (or flips the switch), R flips the switch, Esc closes.
- Text is drawn at the book's text unit and never below 1 (the vanilla size): where the narrowest panel (427 × 240 GUI) has
  no room, a fork's line is cut to one line with "…" rather than shrunk; spell names live in the panel title and the icons'
  tips (a 20-unit column cannot hold a name at the vanilla size), the columns show the icon and the Roman rank. Feedback: node burst + ring in the element colour, the line animates up, enchant/level-up sound pitched per
  element; fork choice plays a heavier anvil-like chime and dims the other option. First open: a coach hint over the
  legend and the Re-pick line (it hides no node or icon), pointing up at the first available node, which beckons (stored
  once per player in the client config). With a Seal in the inventory: "Re-pick forks"
  toggle with the warning (ranks kept, forks re-pickable 60 s, uses 1 Seal, materials not refunded).
- Elsewhere: Roman rank on the wheel and spell HUD ("Fireball III"), material tooltips "Used to rank up Fire spells" (the
  holder's element only), handbook page "Skill tree" (last page), all text in `en_us.json`. No scrolling; layout tested at
  427 × 240 (854 × 480 at its max GUI scale 2) up to 4K.

## 6. Tests

- Unit: `SpellRank` pack/unpack and knob maths; every element spell has a tree (2 + 2 forks, distinct keys, lang keys);
  cost table (levels, ultimate ×1.5, last item ultimate-only); `MagicData` codec + stream round trip with ranks; screen
  layout fits at every size.
- GameTests (`SkillTreeGameTests`): rank-up takes exactly the cost; refuses with each reason and takes nothing; forks
  change behaviour measurably (Scatter hits three, Siphon heals, Lance pierces, rank damage grows, …); ranks survive death,
  relog (codec) and an element switch and back; Seal re-pick. `BalanceGameTests`: every spell at rank V with each of the
  four fork pairs, full charge, level 10: one fx, ≤ 64 creatures, small spells no blocks, tier budgets.
- GameTests for the review fixes (`SkillTreeGameTests`): Mirror never reflects onto a friend, a villager in the way or a named
  mob (a skeleton's arrow comes back as a hit on it); riders never put control effects on a boss; life steal heals what the
  hit took; no fork buff outlasts half its cooldown; the re-pick window is in the synced data; and one behaviour test per
  new fork against its sibling (Vacuum, Wind Wall, Debris, Boomerang, Guided Shells, Hurl, Conductor, Live Wire, Magnet,
  Returning Spear, Seeker, Mana Drain, Collector, Hungering, Air Dash, Mending, Fortress, Long Shadow). Unit: fork counts at
  full charge rank V (Cleave 7 < Tempest 9; Carpet 11 / 10); a bad rank entry drops only itself.
- Client GameTest (CI only): the tab at GUI scales 1–4 + a rank-up, and at 320 × 240, 427 × 240 and 480 × 270 with a
  fork's cards and a bump open, screenshots.
