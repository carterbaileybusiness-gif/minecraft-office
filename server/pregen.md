# Pregenerating the world with Chunky

Chunky is in the pack as a server-only mod. Do this on the real host, with the final pack installed, **after the mod list
is frozen** (changing world mods later changes terrain in not-yet-generated chunks and leaves seams against pregenerated
ones). Do it before telling friends to join.

Set the seed first if you want a specific world: put `level-seed=<number>` in `server.properties` before the first start.
Otherwise the first start picks a random one. Keep the seed in `DECISIONS.md` or the backup notes.

## Commands (type in the server console, no `/`)

```
chunky world minecraft:overworld
chunky center 0 0
chunky shape square
chunky radius 2000
chunky start
```

- `chunky progress` shows percent and ETA; `chunky pause` / `chunky continue` pause and resume; `chunky cancel` stops.
- Leave the server running until it prints `Task finished`. If the server restarts, run `chunky continue`
  (progress is saved in `config/chunky/tasks`).
- Optional: `chunky world minecraft:the_nether` then `chunky radius 500` then `chunky start` for the Nether (Incendium).
  The End is not worth pregenerating.

## Sizing

- Chunks = (2 x radius / 16)^2. Radius 300 = 1521 chunks; radius 2000 = 62,500 chunks; radius 3000 = 140,625 chunks.
- Measured on an M1 Mac with 2 GB: about 85 chunks/s, so radius 300 took 15 s and radius 2000 would take about 12 minutes.
  A cheap host is slower; expect 2-4x that. Run it while nobody is online - it uses the main thread's spare time and
  can drop TPS (spark showed 19.5 right at the end of the test).
- Terrain here (Tectonic) is big: mountain ranges and continents are thousands of blocks wide, so radius 2000 is a modest
  part of one continent. Pick the radius for how far five friends will actually wander; more can always be added later
  (run Chunky again with a bigger radius; existing chunks are skipped).
- Disk size was not measured in the local test (world deleted afterwards). Check `world/region` after the first 300-block
  run on the host and scale by area before choosing a large radius.

## After pregen

- Stop the server, back up the world folder, start again.
- Run `spark tps` with no one online and again with players; the target is 19.5 or higher.
- Optional: `worldborder set 4500` (a bit beyond the pregenerated square, diameter = 2 x radius + a margin) so nobody
  walks into ungenerated terrain. Remove or raise it when you pregenerate more.
