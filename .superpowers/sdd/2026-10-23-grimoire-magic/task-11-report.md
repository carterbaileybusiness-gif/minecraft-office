# Task 11 report: pack swap (remove Wizards and its stack)

## Done
- `scripts/check-pack-deps.py [pack-dir]`: downloads each pack jar (hash-checked) to `~/Library/Caches/minecraft-office-tools/pack-jars/`,
  reads `fabric.mod.json` (id, provides, depends, nested jars), prints who depends on what, missing required ids (exit 1) and unused
  library mods. `mixinextras` counts as present (bundled with Fabric Loader). Keep-list: `placeholder-api` (Mod Menu) and `cloth-config`
  (Cristel Lib) are required only in Modrinth metadata, not in the jars, so the script does not flag them.
- Step 1 (before): missing none. Dependents: wizards -> armor_model_api, runes, spell_engine, structure_pool_api; spell-engine ->
  cloth-config, player_animation_library, spell_power; trinkets-updated and bundle-api had no dependents (Wizards-era additions).
- Removed with `packwiz remove`: wizards, spell-engine, spell-power, runes, bundle-api, armor-model-api, player-animation-library,
  trinkets-updated, structure-pool-api. 44 -> 35 mods.
- **Kept `cloth-config`** (candidate): Modrinth lists it as a required dependency of Cristel Lib (Towns and Towers, a world mod).
- `pack.toml` version `0.2.0-dev`; `packwiz refresh` run.
- Docs: `server/deploy.md` (pack contents, dependency note, client check, handbook and sources removed, RPG loot note removed, re-check
  note added); `docs/friends/SETUP.md` section 9 now "Magic: Grimoire (coming in the Office mod)" (TOC anchor `#9-magic`).

## Verification
- `check-pack-deps.py pack`: `missing: none`, `unused libraries: none`.
- `check-pack.py pack`: `pack ok: 37 indexed files, version 0.2.0-dev`.
- Modrinth API required-deps check of all 35 mods: ok.
- Local server (scratch `~/Library/Caches/minecraft-office-tools/server-test/`, installed via packwiz-installer-bootstrap `-g -s server`,
  run through `scripts/heavy.sh`, Temurin 25, `-Xmx2G`): 78 mods loaded, `Done (3.991s)!`, no "Incompatible mods"/missing-dependency
  errors (only Forge Config API Port "recommends" Mod Menu, a client mod). Stopped with `stop`, scratch dir deleted, HTTP server stopped.
  Live Bloom server not touched; no client launched.
