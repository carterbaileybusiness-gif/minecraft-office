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
- The server's "pause when empty" setting (`pause-when-empty-seconds=60` in the template) does **not** stall Chunky:
  Chunky keeps ticking the chunk system while the server is paused with nobody online (see "Tested" below). Leave the
  setting alone; you do not need to stay logged in.
- Give the JVM at least 4 GB for pregen (`-Xmx4G` or more; the host's `-Xmx5G` is fine). With `-Xmx2G` a radius-1000 run
  ran out of memory ("OutOfMemoryError: Java heap space") before finishing, so do not pregen on a small heap.
- Optional: `chunky world minecraft:the_nether` then `chunky radius 500` then `chunky start` for the Nether (Incendium).
  The End is not worth pregenerating.

## Sizing

- Chunks = (2 x radius / 16)^2. Radius 300 = 1521 chunks; radius 2000 = 62,500 chunks; radius 3000 = 140,625 chunks.
- Measured on an M1 Mac: about 85-90 chunks/s. Radius 300 took 15 s (2 GB heap); radius 1000 took 3:02 (4 GB heap), so
  radius 2000 would take about 12 minutes.
  A cheap host is slower; expect 2-4x that. Run it while nobody is online - it uses the main thread's spare time and
  can drop TPS (spark showed 19.5 right at the end of the test).
- Terrain here (Tectonic) is big: mountain ranges and continents are thousands of blocks wide, so radius 2000 is a modest
  part of one continent. Pick the radius for how far five friends will actually wander; more can always be added later
  (run Chunky again with a bigger radius; existing chunks are skipped).
- Disk: radius 1000 (16,129 chunks) made a 142 MB world folder (about 9 KB per chunk). So radius 2000 is about 550 MB
  and radius 3000 about 1.2 GB.

## Staged pregeneration (verified 2026-10-23, 8 GB Mac, `-Xmx4G`)

A single `chunky radius 2000` run on a 4 GB heap ran out of memory (`OutOfMemoryError: Java heap space`) at about 40%:
the world stayed almost empty on disk (chunks were not being flushed), the heap sat near 3 GB and the server crawled.
What worked: raise the radius in stages and restart the server between stages, so each run only generates about 12,000
new chunks and everything is flushed to disk on `stop`. Existing chunks are skipped, so each stage is cheap.

Stages used: radius 800, 1200, 1500, 1750, 2000. For each: start the server, run
`chunky world minecraft:overworld`, `chunky center 0 0`, `chunky shape square`, `chunky radius <R>`, `chunky start`,
wait for `Task finished`, run `save-all flush`, then `stop`. Each stage took 3 to 4 minutes (about 130 chunks/s when the
heap is healthy). Radius 2000 finished as a 620 MB world folder (63,001 chunks).

## After pregen

- Stop the server, back up the world folder, start again.
- Run `spark tps` with no one online and again with players; the target is 19.5 or higher.
- Optional: `worldborder set 4500` (a bit beyond the pregenerated square, diameter = 2 x radius + a margin) so nobody
  walks into ungenerated terrain. Remove or raise it when you pregenerate more.

## Tested (2026-10-23, scratch server, 26.3 with the full pack)

Pause-when-empty with `pause-when-empty-seconds=5`, `-Xmx4G`, `chunky radius 1000`, nobody online:
```
[14:52:06] Server thread/INFO: Server empty for 5 seconds, pausing
[14:55:09] Server thread/INFO: [Chunky] Task finished for minecraft:overworld. Processed: 16129 chunks (100.00%), Total time: 0:03:02
```
The server paused about 5 s after boot and Chunky still finished the whole task (Chunky hooks the paused tick:
`MinecraftServer.handler$zbf000$chunky$tickPaused` appears in the stack traces). With `-Xmx2G` the same task hit
`OutOfMemoryError: Java heap space` both with pause on (at about 99%) and with `pause-when-empty-seconds=-1` (at 69%), so
the failure is about heap size, not about pausing.
