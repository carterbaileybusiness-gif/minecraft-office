# Spell skill tree — design

Date: 2026-10-24. Carter approved the shape in chat and asked: **"Make the UI and UX amazing and easy to understand and
use."** Builds on the Grimoire magic system (docs/superpowers/specs/2026-10-23-grimoire-magic-design.md, including its
play-test rulings).

## Why

Spells only get stronger by Grimoire level today. Carter wants each spell to level up, "maybe through a skill tree… trading
XP… or resources… maybe different element grimoires take certain resources you must collect". He chose **XP + element
materials** and **forks at ranks III and V**.

## What Carter decided
- Every spell (the 5 of your element, ultimates included; Recall excluded) has **ranks I–V**.
- A rank costs **XP levels + your element's materials**, rising with rank.
- **Ranks III and V are forks**: pick one of two upgrades for that spell. A Rebinding Seal also allows re-picking forks.
- The UI must be **amazing and easy to understand and use**.

## Behaviour
- **Where:** a **Skill tree** tab in the Grimoire screen (beside the existing tabs). Rank up anywhere, no block needed.
- **Unlock gate:** a spell's rank I needs the spell to be unlocked by Grimoire level (unchanged rule); higher ranks
  need nothing but cost.
- **Per rank (non-fork):** roughly +15 % damage, +15 % size knob, −10 % mana (builder tunes per spell; balance tests
  keep tier caps: small = no blocks, medium ≤ 6, ultimate ≤ 12, per-cast budgets).
- **Forks (III, V):** two named upgrades per spell with a real behaviour difference (e.g. Fireball III: *Scatter* — 3
  smaller fireballs / *Payload* — one bigger blast). 31 spells × 2 forks × 2 options = the builder proposes the list in the
  plan, one line each, in the spirit of the spell; Carter can veto in review.
- **Cost table (per element, rank I → V):** XP levels 2 / 3 / 5 / 7 / 10 (ultimates +50 %), plus materials:
  - Fire: blaze powder → magma cream → blaze rods → fire charges + ghast tear → blaze rods ×16 + nether star? *(builder:
    keep V achievable without a boss for small spells; nether star only for the ultimate's V)*
  - Water: prismarine shards → prismarine crystals → nautilus shells → sponge → heart of the sea
  - Wind: feathers → phantom membrane → breeze rods → wind charges → heavy core (ultimate V)
  - Lightning: copper ingots → amethyst shards → lightning rods → glow ink → trident or echo shard (ultimate V)
  - Earth: iron → gold → diamonds → ancient debris → netherite ingot (ultimate V)
  - Shadow: ender pearls → obsidian → echo shards → sculk catalyst → dragon's breath (ultimate V)
  Exact amounts are the builder's call (target: a dedicated session per element to max everything).
- **Storage:** ranks and fork picks live in the player's magic data (per element, so switching element with a Seal and
  back restores them). Survive death and losing the Grimoire, like the element does. Server-authoritative: the client
  only asks "rank up spell X" / "pick fork A"; the server checks gate, XP and items, takes them, and replies.

## UI / UX (the bar is "amazing and easy")

Layout (one page, no scrolling, readable at every GUI scale including 854×480 @ max scale):
- Five **columns**, one per spell (slot order = wheel order), spell icon at the top with name, five **rank nodes** below
  connected by a line that **fills with the element colour** as ranks are bought. Ranks III and V show as a **split
  node**: two small option circles side by side; the picked one glows, the other greys out.
- Nodes have clear states, distinguishable without colour alone (shape/icon + colour): **owned** (filled, glow),
  **available now** (pulsing outline, can afford), **next but can't afford** (outline, cost shown in red), **locked**
  (padlock + "Unlocks at Grimoire level N").
- **Hover/select a node → detail panel** (bottom or side, fixed place): rank name, *exactly what changes* as before → after
  numbers ("Damage 6 → 7", "Radius 3 → 3.5", "Mana 12 → 11"), and the **cost list with have/need** per item
  ("Blaze rod 3/4" red when short; item icons). For forks: two cards side by side, each with name, one-line effect and
  the before → after numbers; the button says **Choose Scatter** / **Choose Payload**.
- **One big primary button** in the detail panel: **Rank up (costs …)**, disabled with the reason when not possible
  ("Need 2 more blaze rods", "Need 3 more levels"). No hidden right-click tricks; keyboard: arrows move between nodes,
  Enter ranks up, Esc closes.
- **Feedback:** on rank up, a satisfying moment: node fills with a burst in the element colour, the connecting line
  animates up to it, a level-up sound (enchanting-table-like, element-tinted), items/XP visibly deducted. Fork choice
  plays a heavier sound and the unchosen option dims.
- **First open:** a one-line coach hint over the first available node ("Click a glowing node to rank up a spell"),
  shown once per player.
- **Discoverability elsewhere:** the wheel and the spell HUD show the spell's rank as Roman numerals (e.g. "Fireball III"),
  tooltips of element materials in the player's inventory say "Used to rank up Fire spells" (only for the holder's
  element), and the handbook gets a "Skill tree" page.
- **Respec clarity:** with a Rebinding Seal, the tree shows "Re-pick forks" mode with a clear warning about what is kept
  (ranks kept, forks re-pickable, materials not refunded).
- Accessibility: text never below the vanilla font size; contrast against the backdrop ≥ 4.5:1; colour-blind safe states
  (shape + icon); all text in en_us.json.

## Non-goals
- Cross-element spells, new spells, spell crafting.
- A separate skill-tree block or NPC.
- Refunding materials.

## Constraints
- Same as the magic spec: server authoritative, one effect packet per cast, no new client trust; screens polished, no
  scrolling, readable at any GUI scale; tests via scripts/heavy.sh; client GameTests in CI only.
- Balance tests extended: every rank/fork combination stays inside tier caps and budgets.

## Left to the builder
- The 62 fork upgrades (one line each in the plan), exact material amounts, per-spell rank effects, node art.
