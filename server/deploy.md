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

`pack/mods/office.pw.toml` (our own mod) does **not exist yet**. Task 15 creates it pointing at the GitHub Release asset.
Until then the pack has no Office mod; do not look for it.

## What is in the pack (by purpose)

World (server side only, clients do not need them): Tectonic, Terralith, Lithostitched, Incendium, Nullscape, Dungeons and
Taverns, Towns and Towers (+ Cristel Lib), Structory, Sparse Structures (spread factor 2 = structures twice as rare, left
at the mod's default), Cloth Config API.
Magic: Grimoire comes with the Office mod (not a separate pack mod; Wizards, Spell Engine and Spell Power are removed as of release 0.2.0, the first public release).
Travel: the grimoire's Recall spell (in the Office mod). Waystones is removed as of release 0.2.0.
Hanging out (both): Simple Voice Chat, Open Parties and Claims (+ Forge Config API Port).
QoL (both): Jade, AppleSkin. Client: Mod Menu (+ Text Placeholder API). JEI is removed as of release 0.2.0.
Performance: Lithium, FerriteCore (both); Sodium, ImmediatelyFast, Entity Culling, Dynamic FPS (client).
Optional client, off by default (Prism shows a checkbox): Iris, Distant Horizons, LambDynamicLights.
Server tools (server only): Chunky (pregen), spark (`/spark tps`, profiler).
Fabric API (both).

Dependencies are checked with `python3 scripts/check-pack-deps.py pack`: it reads each jar's `fabric.mod.json` and prints
missing required ids (exit 1) and library mods nothing depends on. Two libraries are only required in Modrinth's metadata
(not in the dependent jar), so the script keeps them on purpose: Text Placeholder API (Mod Menu) and Cloth Config API
(Cristel Lib). If you add more mods, run the script; packwiz only looks one level down.

### Sides

Rule used: Modrinth says client optional + server required -> `side = "server"` (clients skip it). Modrinth says client
required and server unsupported -> `client`. Everything else `both`. Exception: Open Parties and Claims is
client-optional on Modrinth but kept `both` so friends get its claim UI (key `'`) instead of only chat commands.
If a friend reports a missing-registry or "unknown biome" problem when joining, the first thing to try is changing the
server-side world mods back to `both`.

## Deploying release 0.2.0 (Wizards, Waystones and JEI removed)

The server does not update itself from the pack. When release 0.2.0 is deployed, delete these jars from the Bloom
server's `/mods` (exact filenames), then restart:

- `waystones-fabric-26.3-26.3.0.1.jar` (Waystones; Recall in the grimoire replaces it)
- `jei-26.3-fabric-31.14.0.72.jar` (JEI; it was only needed for the old Wizards recipes)
- `balm-fabric-26.3-26.3.0.3.jar` (library, only Waystones needed it)
- `shogi-fabric-26.3-26.3.0.3.jar` (library, only Waystones needed it)
- `mezz_config-26.3-fabric-0.6.8.jar` (library, only JEI needed it)

Leave every other jar alone. Any Waystone blocks or items already in the world (placed ones, and ones generated in the
wild) will disappear with the mod; `config/waystones/` can stay or go. Back up the world first. After the restart, the log must show no "Incompatible mods" or missing-dependency
errors.

## Beta mods to watch

No stable release exists for 26.3 for these; the newest file is what the pack uses:
- Incendium (alpha), Open Parties and Claims (beta), Simple Voice Chat (beta).

Deliberately **not** on the newest beta (pinned with `packwiz pin`, so `packwiz update` will not move them):
- **Tectonic 3.0.31** (release). 3.1.0+beta1 declares `"breaks": {"terralith": "*"}` and the server refuses to start with
  Terralith ("Terralith compatibility is temporarily disabled" in its changelog). Re-check when Tectonic 3.1 leaves beta.
- **Sodium 0.9.2** (release). 0.9.3-beta.1 exists, but the pinned Iris 1.11.7 depends on exactly this Sodium version.

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
2. Launch. Mods list (Mod Menu) should show Voice Chat, Open Parties and Claims, Sodium (and no Waystones or JEI).
   Optional ones (Iris, Distant Horizons, LambDynamicLights) should be unchecked and absent.
3. The world mods (Tectonic, Terralith, ...) are server-only in the pack, so a plain singleplayer world will look vanilla.
   To see the terrain, join a server that has them: the real one, or a local one (install the server side of the pack as
   in "Server install", then connect to `localhost`). **Expected:** huge mountains and valleys (Tectonic) and extra
   biomes (Terralith).
4. While joining, check: no red "mod mismatch" or "missing registry" screen. Take a screenshot if there is.
5. Press `V`: the Voice Chat menu opens. (Push to talk key is under Controls -> Voice Chat.)
6. Press `'` (apostrophe): the claims UI opens.
7. Report anything that differs.

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

### Re-check after the Grimoire pack swap (pack 0.2.0-dev, 2026-10-23)

Wizards, Spell Engine, Spell Power and the libraries only they needed were removed; 78 mods loaded (27 server-side jars),
`Done (3.991s)!`, no "Incompatible mods" or missing-dependency errors (only Forge Config API Port "recommends" Mod Menu,
a client mod). Stopped cleanly with `stop`; test world deleted.
