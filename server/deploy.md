# Minecraft Office - server deploy and pack notes

Minecraft 26.3, Fabric loader 0.20.1, Java 25. The modpack is managed with packwiz in `pack/`.
Verified locally on 2026-10-23 (details at the end).

## Ports

| Port | Protocol | What | Set in |
|---|---|---|---|
| 25565 | TCP (+UDP is harmless) | Minecraft game | `server.properties` |
| 24454 | UDP | Simple Voice Chat | `config/voicechat/voicechat-server.properties` (`port=24454`, already in the pack) |
| 24455 | UDP | Our own screen relay (Task 8+) | set by the mod's config when it exists |

The host must allow UDP on both 24454 and 24455 (see spec section 9). Voice chat will not work without 24454 UDP.
If the host only gives one port, set voice chat `port=-1` (shares the game port, not recommended by the mod) and tell the
relay builder.

## Server install (host with a shell or SFTP + startup command)

1. Install Java 25 (Temurin). Server RAM: give the JVM `-Xmx5G` on a 6 GB host (the local test used `-Xmx2G` and was fine
   for an empty server; leave headroom for the pregenerated world and 5 players).
2. Download the Fabric server launcher for exactly this combo:
   `https://meta.fabricmc.net/v2/versions/loader/26.3/0.20.1/1.1.2/server/jar` -> `fabric-server-launch.jar`.
3. Download `packwiz-installer-bootstrap.jar` (v0.0.3 or newer, from the packwiz-installer-bootstrap releases page).
4. In the server folder, install the **server side** of the pack (re-run this on every pack update):
   `java -jar packwiz-installer-bootstrap.jar -g -s server https://<pack-host>/pack/pack.toml`
   (the real URL is set in Task 15; for a local check serve the `pack/` folder with `python3 -m http.server`.)
5. Accept the Mojang EULA (`eula.txt` with `eula=true`) - Carter does this on the host.
6. Copy `server/server.properties.template` to `server.properties` on the host (before first start).
7. Start: `java -Xmx5G -jar fabric-server-launch.jar nogui`. The first start downloads the vanilla 26.3 server.
8. Whitelist the five players: `whitelist add <name>` for each (the template sets `white-list=true` and
   `enforce-whitelist=true`). `op` Carter only.
9. Pregenerate the world before announcing: see `server/pregen.md`.
10. Note: the first start prints a burst of `RPG Series loot config: failed to resolve itemList ... (Probably just needs a
    game restart)` lines. They are harmless and gone on the second start - restart once after the first boot.

`pack/mods/office.pw.toml` (our own mod) does **not exist yet**. Task 15 creates it pointing at the GitHub Release asset.
Until then the pack has no Office mod; do not look for it.

## What is in the pack (by purpose)

World (server side only, clients do not need them): Tectonic, Terralith, Lithostitched, Incendium, Nullscape, Dungeons and
Taverns, Towns and Towers (+ Cristel Lib), Structory, Sparse Structures (spread factor 2 = structures twice as rare, left
at the mod's default), Structure Pool API.
Magic (both): Wizards, Spell Engine, Spell Power Attributes, Runes (+ Bundle API), Armor Model API, Player Animation
Library, Trinkets Updated, Cloth Config API.
Hanging out (both): Simple Voice Chat, Waystones (+ Balm, Shogi), Open Parties and Claims (+ Forge Config API Port).
QoL (both): JEI (+ MezzConfig), Jade, AppleSkin. Client: Mod Menu (+ Text Placeholder API).
Performance: Lithium, FerriteCore (both); Sodium, ImmediatelyFast, Entity Culling, Dynamic FPS (client).
Optional client, off by default (Prism shows a checkbox): Iris, Distant Horizons, LambDynamicLights.
Server tools (server only): Chunky (pregen), spark (`/spark tps`, profiler).
Fabric API (both).

Dependencies that packwiz did **not** pull on its own and were added by hand after checking each mod's declared required
dependencies on Modrinth: Spell Power Attributes, Player Animation Library, Trinkets Updated, Cloth Config API (all four
are required by Spell Engine), and Bundle API (required by Runes). If you add more mods, check their dependencies;
packwiz only looks one level down.

### Sides

Rule used: Modrinth says client optional + server required -> `side = "server"` (clients skip it). Modrinth says client
required and server unsupported -> `client`. Everything else `both`. Exception: Open Parties and Claims is
client-optional on Modrinth but kept `both` so friends get its claim UI (key `'`) instead of only chat commands.
If a friend reports a missing-registry or "unknown biome" problem when joining, the first thing to try is changing the
server-side world mods back to `both`.

## Beta mods to watch

No stable release exists for 26.3 for these; the newest file is what the pack uses:
- Incendium (alpha), Open Parties and Claims (beta), Simple Voice Chat (beta), Player Animation Library (beta),
  MezzConfig (beta).

Deliberately **not** on the newest beta (pinned with `packwiz pin`, so `packwiz update` will not move them):
- **Tectonic 3.0.31** (release). 3.1.0+beta1 declares `"breaks": {"terralith": "*"}` and the server refuses to start with
  Terralith ("Terralith compatibility is temporarily disabled" in its changelog). Re-check when Tectonic 3.1 leaves beta.
- **Sodium 0.9.2** (release). 0.9.3-beta.1 exists, but the pinned Iris 1.11.7 depends on exactly this Sodium version.
- **JEI 31.14.0.72** (release); 31.16 is beta.

## Beta caveats for Carter

- Tectonic changes terrain a lot; mountains reach near the build limit. Terralith + Tectonic compatibility is what 3.0.31
  advertises ("Reintroduced compatibility with Terralith"). A Terralith biome (`yosemite_cliffs`) was found in the test
  world, so the biome set loads.

---

## Client check (Carter)

One-time, in Prism, **nothing else running** (no Gradle, no other game). This is the part an agent cannot do for you.

1. Create a Prism instance: Minecraft 26.3, Fabric loader 0.20.1. Add the packwiz installer as a pre-launch command
   (`$INST_JAVA -jar packwiz-installer-bootstrap.jar <pack url>`), or until the URL exists use a local server:
   in `pack/` run `python3 -m http.server 8089` and use `http://127.0.0.1:8089/pack.toml`.
2. Launch. Mods list (Mod Menu) should show Wizards, Spell Engine, Voice Chat, Waystones, JEI, Sodium.
   Optional ones (Iris, Distant Horizons, LambDynamicLights) should be unchecked and absent.
3. The world mods (Tectonic, Terralith, ...) are server-only in the pack, so a plain singleplayer world will look vanilla.
   To see the terrain, join a server that has them: the real one, or a local one (install the server side of the pack as
   in "Server install", then connect to `localhost`). **Expected:** huge mountains and valleys (Tectonic) and extra
   biomes (Terralith).
4. While joining, check: no red "mod mismatch" or "missing registry" screen. Take a screenshot if there is.
5. Press `V`: the Voice Chat menu opens. (Push to talk key is under Controls -> Voice Chat.)
6. Open a recipe in JEI (hover an item, press `R`): confirm the Spell Binding Table recipe below shows up.
7. Craft the Spell Binding Table, put 3-4 bookshelves around it, and try making a Tome of Fire from a plain book.
   Confirm the "first 10 minutes" text below matches what you see (especially the spell-book slot and what the table asks for).
8. Press `'` (apostrophe): the claims UI opens.
9. Report anything that differs; the handbook text in the next section is what Task 5 copies.

---

## Magic: your first 10 minutes (handbook text)

Magic here is part of normal survival: you craft a table, a wand, and some runes. Nothing needs commands.
Press `R` over any item in the recipe book (JEI) to see how it is made.

1. **Make a Wand.** A Novice Wand is 1 coal and 1 stick (coal top-right, stick bottom-left). Better early wands:
   Arcane = amethyst shard + gold ingot, Fire = gunpowder + gold ingot, Frost = snowball + iron ingot. Hold it and
   right-click to cast the spell it comes with.
2. **Make runes.** Spells use runes like a bow uses arrows. Put one piece of stone (cobblestone, blackstone...) together with
   an amethyst shard (Arcane), gunpowder or coal (Fire), or a snowball (Frost) in the crafting grid: you get 2 runes.
   A Crafting Altar (see JEI) makes more per craft.
3. **Build a Spell Binding Table.** 1 book, 2 amethyst shards, 1 gold ingot, 3 polished diorite:
   book in the top middle, amethyst left and right of the gold ingot, diorite along the bottom.
   Put bookshelves around it, like an enchanting table. You can also find tables in village gazebos.
4. **Make your first Spell Book.** Put a plain book into the Spell Binding Table and choose a path: Arcane, Fire or Frost.
   It costs a little XP (the server default is 1 level). The book gives you a whole set of spells.
5. **Equip it and hold a weapon.** Wear the spell book (it has its own slot) and hold a wand or staff to cast the book's spells.
6. **Dress the part.** Wizard robes are lapis lazuli plus wool; they make your spells stronger and faster.
   A Wizard Staff (1 quartz, 2 sticks) is a step up from a wand. Villagers called Wizard Merchants sell wands, staves and robes for emeralds.
7. **Level up.** Bind more spells by coming back to the table with XP, and look for spell enchantments (Spell Infinity
   casts without runes). Wizard buildings in villages have loot.

Other things you can do in the first 10 minutes:
- **Talk:** press `V`, nearby friends hear you (about 48 blocks).
- **Travel:** a Warp Stone (4 amethyst, 4 ender pearls, 1 gold ingot) turns stone bricks into a Waystone; waystones you
  have visited let you teleport between each other. Waystones also sit in the wild.
- **Protect your office:** press `'` and claim the chunks around your office. Friends you add to your party can still build.

### Sources for the magic text

- Spell Binding Table recipe: Spell Engine 1.10.9 `data/spell_engine/recipe/spell_binding_table.json` (inside the jar, so
  JEI shows the same recipe): book, amethyst_shard x2, gold_ingot, polished_diorite x3.
- Wand and staff and robe recipes, Wizard Merchant trades, rune recipes: Wizards 3.1.4 and Runes 1.3.2 jars
  (`data/wizards/recipe/`, `data/wizards/villager_trade/`, `data/runes/recipe/`).
- "Obtain a Wand or Staff... Runes serve as ammunition... find a Spell Binding Table in village Gazebos, or build your
  own (just don't forget bookshelves)... To use the spell books: equip them and hold a weapon": Wizards on Modrinth,
  https://modrinth.com/mod/wizards
- Spell book creation cost (1 level, requirement 1) and the default options: `config/spell_engine/server.json5`
  generated by Spell Engine 1.10.9; Spell Engine overview: https://modrinth.com/mod/spell-engine
- Voice Chat menu key `V`: https://modrinth.com/mod/simple-voice-chat ; claims UI key `'`:
  https://modrinth.com/mod/open-parties-and-claims ; Waystone and Warp Stone recipes: Waystones jar `data/waystones/recipe/`.

Not confirmed by anyone playing yet (step 7 of the client check): that the table asks for a plain book only (no lapis),
the exact name of the spell-book slot, and the default Push-to-Talk key.

---

## Local verification (2026-10-23, M1 Mac, Temurin 25, `-Xmx2G`)

Fresh server in a scratch folder, installed with packwiz-installer-bootstrap (`-g -s server`) from a local pack server,
`server.properties` from the template, run through `scripts/heavy.sh`.
- 96 mods loaded (includes embedded ones). `Done (4.128s)! For help, type "help"`.
- No mod errors. Warnings only: Open Parties and Claims "invalid entries in its mod json", missing refmaps for Open
  Parties and Sparse Structures (harmless), "primary party system argonauts_guilds / permission system prometheus isn't
  registered" (OPAC defaults for optional mods we do not use; it falls back to its built-in ones).
- `chunky radius 300` + `chunky start`: 1521 chunks (overworld) in 15 s (about 85 chunks/s).
- `spark tps` straight after: 19.52 / 19.75 / 19.98 / 20.0 / 20.0 (5s/10s/1m/5m/15m); 20 s later 19.99 / 20.0.
- `locate biome terralith:yosemite_cliffs` found one (2769 blocks away), `locate structure #minecraft:village` found one.
- Voice chat log line: `Voice chat server started at port 24454`.
- Server stopped cleanly with `stop`; test world deleted.
