# Minecraft Office v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** By Monday 2026-10-26, five friends can join a hosted Fabric 26.3 survival server with the day-one world, place
hologram monitors that show their real computer, zoom in to work on them, and see each other's shared screens.

**Architecture:** One Fabric mod (`office`, client + server) owns holograms, rendering, interaction, zoom and a UDP
screen relay. On Macs a small Swift helper app (grown from Home Base's bridge) creates virtual displays, captures,
places windows and catches the exit hotkey; on Windows the mod captures in Java (webrtc-java) and uses Win32 through
JNA. Shared screens travel as changed JPEG tiles through the server relay; your own screen never touches the network.

**Tech Stack:** Minecraft 26.3, Fabric Loader 0.20.1, Fabric API 0.165.0+26.3, Loom 1.19.3 (`net.fabricmc.fabric-loom`,
no mappings line — 26.x is unobfuscated), Java 25 (Gradle toolchain), Gradle 9.7.1, JUnit 5, Fabric GameTest + client
GameTest, webrtc-java 0.20.0 (windows-x86_64 natives only), LWJGL STB (bundled with Minecraft) for JPEG, JNA (bundled),
Swift 6 / ScreenCaptureKit / private CGVirtualDisplay, packwiz, Prism Launcher, GitHub Actions + Releases.

**Spec:** `docs/superpowers/specs/2026-10-23-minecraft-office-server-design.md` (read it first; this plan argues from it).

## Global Constraints

- Minecraft **26.3** Fabric only; mod id `office`, package `local.carter.office`, Java release 25.
- Players: 5, whitelisted. Supported: macOS 14+ Apple Silicon, Windows 10/11 x64; others best effort.
- Emitters are **free** (starter kit + `/emitter`), never require crafting.
- Hologram look: frameless, slightly translucent, cyan edge glow, faint scanlines over a small emitter base; **text must
  stay crisp** — scanlines/translucency fade out within 3 blocks and are off while zoomed.
- Sizing by **grab a corner and drag**; any size between 0.5 and 12 blocks wide; aspect fixed by shape.
- Shapes → virtual display resolution: 16:9 = 1920×1080, 21:9 = 2560×1080, 9:16 = 1080×1920.
- Modes: **Extend** (Mac v1; Windows after Monday) and **Window** (both). No "mirror main screen" mode.
- Privacy: Public (default) / Private / Show to… ; **private frames never leave the owner's computer**.
- Only the owner may resize, change settings, or remove a hologram; emitters are explosion-proof.
- Shared streams: ≤10 fps, ≤1280 px wide, only to players within 48 blocks who may view; own view from local capture.
- Exit zoom: **double-tap Esc** (two presses within 350 ms), fallback Ctrl+Alt+Cmd+Esc (Mac) / Ctrl+Alt+Shift+Esc (Win).
- Max 8 holograms per player; max 4 Extend displays per Mac.
- No paid signing. Mac helper signed with one stable self-signed identity (never committed); installed by the mod.
- Our mod is never published to Modrinth/CurseForge; third-party mods are referenced by the pack, never redistributed.
- Carter's 8 GB M1: one heavy process at a time; Gradle `-Xmx1G`, `--no-daemon`; never run Gradle beside the game;
  never `pkill java`. Never automate other apps from Terminal; never edit TCC databases.
- Focus order (Carter): **the monitor mod first**; magic is Wizards as shipped plus onboarding only.

## Review Focus

1. Owner quits, crashes, or the helper dies mid-stream → viewers' holograms switch to the dim offline look within 3 s
   (no frozen last frame); the owner's virtual displays disappear. Test in Task 13.
2. Privacy switched to Private while a friend watches → sending stops within 1 s and the friend's texture is replaced by
   the frosted panel (no stale private frame kept in memory). Tests in Tasks 4 and 13.
3. The window shown in Window mode closes or its app quits, including while zoomed in → hologram shows "Window closed",
   zoom exits cleanly, window positions restored. Tests in Tasks 9 and 11.
4. Retina scale (2×) and odd sizes (e.g. 1366×768, vertical) → texture matches capture size, clicks/cursor land on the
   right pixel. Tests in Tasks 4 (mapping) and 7 (upload).
5. Someone other than the owner, or an explosion, tries to break/resize/configure a hologram → nothing changes and the
   hologram survives. Tests in Task 5.

## File Structure

```
Minecraft Office/
  settings.gradle, build.gradle, gradle.properties, gradlew*   # one Gradle build, mod at root of mod/
  mod/
    src/main/java/local/carter/office/
      OfficeMod.java                    # init: registries, payloads, commands, starter kit, relay start
      core/ (pure Java, no Minecraft imports — unit-tested)
        Shape.java  Mode.java  Privacy.java  HologramSpec.java  HologramGeometry.java
        TileCodec.java  TileFrame.java  Packetizer.java  StreamHeader.java
      hologram/ HologramEmitterBlock.java  HologramBlockEntity.java  OfficeBlocks.java  StarterKit.java
      net/ OfficePayloads.java  HologramActions.java (server handlers, owner checks)
      stream/ StreamRelayServer.java  StreamRouter.java  OfficeConfig.java
    src/client/java/local/carter/office/client/
      OfficeClient.java
      render/ HologramRenderer.java  ScreenTexture.java  HologramLook.java
      interact/ HologramPicker.java  CornerDrag.java  HologramSettingsScreen.java
      capture/ CaptureSource.java  MacHelperSource.java  JavaDesktopSource.java  TestPatternSource.java
      platform/ Platform.java  MacPlatform.java  WindowsPlatform.java  Win32.java
      helper/ HelperConnection.java  HelperInstaller.java
      zoom/ ZoomController.java  DoubleEsc.java
      stream/ StreamClient.java  StreamSender.java  StreamReceiver.java
      selftest/ SelfTestScreen.java  SelfTestReport.java
    src/test/java/...                   # JUnit for core/
    src/gametest/java/...               # server GameTests + client GameTests
  helper-mac/
    Package.swift, Sources/OfficeHelper/{App.swift, Protocol.swift, Server.swift, Capture.swift,
      VirtualDisplays.swift, CGVirtualDisplay.h (bridging), Placement.swift, ExitHotkey.swift, CursorFocus.swift}
    Tests/OfficeHelperTests/ProtocolTests.swift, DisplaySpecTests.swift
    build.sh  sign.sh
  pack/        # packwiz: pack.toml, index.toml, mods/*.pw.toml, config/
  server/      # server.properties.template, whitelist notes, deploy.md, pregen.md
  docs/friends/SETUP.md (+ images)      # friend install guide
  .github/workflows/ci.yml  release.yml
```

Reuse sources (copy, then adapt; Home Base root = `~/Documents/Mac UI Makeover/.worktrees/minecraft-native-proof`):
- `Prototypes/MinecraftBridge/{CaptureEngine.swift, HomeBaseServer.swift (framing), BridgeCore.swift, sign-local.py, build.sh}`
- `Prototypes/MinecraftBridge/HomeBaseSession.swift` `place()` (~line 402) for window placement.
- `Prototypes/MinecraftHome/src/main/java/local/carter/homebase/client/{AppSurface.java, BridgeConnection.java, SelfTest.java}`
- In-world quad + mouse-ray mapping: `git show b2f8389:Prototypes/MinecraftHome/src/main/java/local/carter/homebase/client/AppSurface.java`
  (1.21.11 API — re-derive render calls against 26.3 with `./gradlew genSources`; never guess render signatures).

---

## Phase A — the world is playable (Friday/Saturday)

### Task 1: Build, toolchain and CI skeleton

**Files:**
- Create: `settings.gradle`, `build.gradle`, `gradle.properties`, `gradle/wrapper/*`, `gradlew`, `gradlew.bat`
- Create: `mod/src/main/java/local/carter/office/OfficeMod.java`, `mod/src/client/java/local/carter/office/client/OfficeClient.java`
- Create: `mod/src/main/resources/fabric.mod.json`, `office.mixins.json`, `mod/src/client/resources/office.client.mixins.json`
- Create: `.github/workflows/ci.yml`, `README.md`
- Test: `mod/src/test/java/local/carter/office/core/SmokeTest.java`

**Interfaces:**
- Produces: `OfficeMod.MOD_ID = "office"`, `OfficeMod.id(String path) -> Identifier`; Gradle tasks `build`, `test`,
  `runGametest`, `runClientGametest`, `runClient`, `runServer`.

- [ ] **Step 1:** Copy the Fabric `26.3` example-mod branch layout (`https://github.com/FabricMC/fabric-example-mod/tree/26.3`)
  with `splitEnvironmentSourceSets()`, values: `minecraft_version=26.3`, `loader_version=0.20.1`, `loom_version=1.19.3`,
  `fabric_api_version=0.165.0+26.3`, `org.gradle.jvmargs=-Xmx1G`, `org.gradle.daemon=false`, Java 25 toolchain with the
  foojay resolver plugin in `settings.gradle`. Run Gradle with Mojang's bundled JDK
  (`~/Library/Application Support/minecraft/runtime/java-runtime-delta/mac-os-arm64/java-runtime-delta/jre.bundle/Contents/Home`)
  — there is no system Java. Add JUnit 5 and Fabric's gametest source set (`fabricApi.configureTests` or the 26.3
  equivalent shown by Loom docs).
- [ ] **Step 2:** Write `SmokeTest.idUsesModNamespace`: `assertEquals("office:x", OfficeMod.id("x").toString())`. Run
  `./gradlew test` → FAIL (no class).
- [ ] **Step 3:** Implement `OfficeMod` (ModInitializer) and `OfficeClient` (ClientModInitializer), empty bodies + `id()`.
- [ ] **Step 4:** `./gradlew build test` → PASS; `./gradlew runServer` once with `eula=true` in `run/` → "Done" line, then stop.
- [ ] **Step 5:** `ci.yml`: jobs `build` on `ubuntu-latest` and `windows-latest` (`./gradlew build test runGametest`),
  `helper` on `macos-15` (`swift build && swift test` in `helper-mac/`, added in Task 8). Commit `chore: Fabric 26.3 build + CI`.

### Task 2: Day-one modpack and server config

**Files:**
- Create: `pack/pack.toml`, `pack/index.toml`, `pack/mods/*.pw.toml`, `pack/config/**`
- Create: `server/server.properties.template`, `server/deploy.md`, `server/pregen.md`

**Interfaces:**
- Produces: pack URL `https://<github-pages>/pack/pack.toml` (set in Task 15); `pack/mods/office.pw.toml` placeholder
  pointing at the GitHub Release asset (filled in Task 15).

- [ ] **Step 1:** `packwiz init` (MC 26.3, Fabric 0.20.1). `packwiz modrinth add` each, letting it pull required deps;
  side = server-only where Modrinth says client optional:
  - World: tectonic, terralith, lithostitched, incendium, nullscape, dungeons-and-taverns, towns-and-towers, structory,
    sparsestructures.
  - Magic: wizards (pulls spell-engine, spell-power and its other deps).
  - Hanging out: simple-voice-chat, waystones, open-parties-and-claims.
  - QoL: jei (recipe lookup — the "book" for magic recipes), jade, appleskin, modmenu.
  - Performance: sodium, lithium, ferrite-core, immediatelyfast, entityculling, dynamic-fps.
  - Optional (client, `option.optional = true`, default off): iris, distanthorizons, lambdynamiclights.
  Prefer the newest **release** file for 26.3; if only beta exists (tectonic, sodium today), take it and note it in
  `server/deploy.md` under "Beta mods to watch".
- [ ] **Step 2:** Configs: Simple Voice Chat `port=24454` (UDP); Open Parties and Claims defaults with claims on;
  `server.properties.template`: `difficulty=normal`, `white-list=true`, `enforce-whitelist=true`, `view-distance=10`,
  `simulation-distance=8`, `spawn-protection=0`, `motd=Minecraft Office`.
- [ ] **Step 3:** Verify locally with the game closed: export server pack (`packwiz` server install via
  `packwiz-installer-bootstrap -g -s server`), `java -Xmx2G -jar fabric-server-launch.jar nogui` → reaches "Done",
  no mod errors in `logs/latest.log`; `chunky radius 300; chunky start` completes; `spark tps` ≥ 19.5. Stop server.
- [ ] **Step 4:** One-time client check in Prism (game only, nothing else running): create world, confirm Tectonic/
  Terralith terrain, a Spell Binding Table is obtainable per Wizards' JEI recipe, voice chat menu opens. Write the
  findings and the magic "first 10 minutes" path into `server/deploy.md` (used by the handbook in Task 5).
- [ ] **Step 5:** Commit `feat(pack): day-one world, magic, voice, perf`.

### Task 3: Hosting (needs Carter)

**Files:** Modify `server/deploy.md`

- [ ] **Step 1:** Shortlist 2 hosts meeting: ≥6 GB RAM, custom Fabric 26.3 jars via SFTP/file manager, ≥2 extra
  UDP ports (24454 voice, 24455 screens), daily backups, US-East location (Carter is in NJ), ≤ $22/month. Record prices
  and port policy with links in `deploy.md`. Recommend one.
- [ ] **Step 2:** **Carter** creates the account and pays (never enter payment details for him). Then upload the server
  pack, set ports, whitelist the five players, pregenerate radius 2000 with Chunky, record address in `deploy.md`.
- [ ] **Step 3:** Verify: Carter's Prism client joins; voice chat connects; `spark tps` ≥ 19.5 during pregen-complete idle.
  Commit `docs(server): host deployed`.

---

## Phase B — holograms (all platforms)

### Task 4: Hologram core logic (pure Java)

**Files:**
- Create: `mod/src/main/java/local/carter/office/core/{Shape,Mode,Privacy,HologramSpec,HologramGeometry}.java`
- Test: `mod/src/test/java/local/carter/office/core/{PrivacyTest,HologramGeometryTest,HologramSpecTest}.java`

**Interfaces:**
- Produces:
  - `enum Shape { WIDE_16_9(1920,1080), ULTRA_21_9(2560,1080), TALL_9_16(1080,1920); int pxW(); int pxH(); double aspect(); }`
  - `enum Mode { EXTEND, WINDOW }`
  - `record Privacy(Level level, Set<UUID> allowed)` with `enum Level { PUBLIC, PRIVATE, SHOW_TO }`,
    `boolean canView(UUID owner, UUID viewer)` (owner always true).
  - `record HologramSpec(UUID owner, Shape shape, Mode mode, Privacy privacy, int displaySlot, String windowKey,
    double width, double offX, double offY, double offZ, float yaw)`; `double height()` = width / aspect;
    `HologramSpec withWidth(double)` clamps to [0.5, 12.0]; `CompoundTag`-free codec via `Map<String,Object> toMap()` /
    `static HologramSpec fromMap(Map<String,Object>)`.
  - `HologramGeometry`: `Optional<Hit> hit(Vec3d origin, Vec3d dir, Frame f)` where `record Frame(Vec3d center, Vec3d right,
    Vec3d up, double w, double h)` and `record Hit(double u, double v, double distance, Corner corner /* nullable */)`;
    corner when within 0.25 blocks of a corner; `int[] toPixel(Hit, int texW, int texH)`;
    `double widthFromDrag(Frame f, Corner c, Vec3d planePoint, double aspect)` (opposite corner fixed; returns clamped width).
  - Own tiny `record Vec3d(double x,double y,double z)` with dot/sub/add/scale (no Minecraft types in core).

- [ ] **Step 1: Tests:**
  - `PrivacyTest`: owner always views; PUBLIC → stranger true; PRIVATE → stranger false; SHOW_TO {b} → b true, c false.
  - `HologramGeometryTest`: ray through center of 2×1.125 frame → u=0.5,v=0.5; ray missing → empty; ray behind origin →
    empty; hit 0.1 from top-left corner → `Corner.TOP_LEFT`; `toPixel` for u=0.25,v=0.5 on 2880×1620 (2× Retina of
    1440×810) → [720, 810]; `toPixel` for 1080×1920 vertical at u=1,v=1 → [1079, 1919] (clamped, never out of range);
    `widthFromDrag` dragging bottom-right outward by 1 block → width+1, clamps at 12 and 0.5.
  - `HologramSpecTest`: `fromMap(toMap(s)).equals(s)`; `withWidth(50).width()==12.0`; TALL height = width×16/9.
- [ ] **Step 2:** `./gradlew test` → FAIL. **Step 3:** implement. **Step 4:** PASS. **Step 5:** commit `feat(core): hologram spec, privacy, geometry`.

### Task 5: Emitter block, block entity, ownership, starter kit

**Files:**
- Create: `hologram/{OfficeBlocks,HologramEmitterBlock,HologramBlockEntity,StarterKit}.java`, `net/{OfficePayloads,HologramActions}.java`
- Create: assets — `assets/office/{blockstates,models,textures,lang/en_us.json}` for `hologram_emitter` (small dark base,
  cyan emissive strip; 16×16 pixel art)
- Test: `mod/src/gametest/java/local/carter/office/HologramGameTests.java`

**Interfaces:**
- Consumes: `HologramSpec`, `Privacy`, `Shape`, `Mode` (Task 4).
- Produces:
  - `OfficeBlocks.HOLOGRAM_EMITTER`, `OfficeBlocks.HOLOGRAM_EMITTER_ITEM`, `OfficeBlocks.HOLOGRAM_BE`.
  - `HologramBlockEntity`: `UUID hologramId()`, `HologramSpec spec()`, `boolean ownerOnline()`; synced to clients via
    the block-entity update packet; persisted with `toMap()` keys.
  - C2S payloads (in `OfficePayloads`): `ResizeHologram(BlockPos pos, double width)`,
    `UpdateHologram(BlockPos pos, Shape shape, Mode mode, Privacy privacy, int displaySlot, String windowKey)`,
    `MoveHologram(BlockPos pos, double offX, double offY, double offZ, float yaw)`, `SetWorking(boolean working)`.
  - `HologramActions.apply(ServerPlayer, payload)` → rejects silently unless `player.getUUID().equals(spec.owner())`.
  - `StarterKit.giveIfFirstJoin(ServerPlayer)`: 4 emitters + written book "Office Handbook" (pages: placing/sizing/
    privacy/zoom; magic: Spell Binding Table + the first-10-minutes path from `server/deploy.md`; use JEI with `R`/`U`).
  - Command `/emitter` → gives 1 emitter if player owns < 8 holograms, else chat message "You already have 8 holograms".

- [ ] **Step 1: GameTests** (server):
  - `placeSetsOwner`: player A places emitter → `spec().owner()==A`, default WIDE_16_9, EXTEND, PUBLIC, width 2.0.
  - `strangerCannotResizeOrConfigure`: B sends Resize/Update/Move → spec unchanged.
  - `strangerCannotBreak`: B breaking → block remains; A breaking → drops emitter item.
  - `explosionProof`: TNT next to emitter → block + spec intact.
  - `resizeClamps`: A resizes to 40 → 12.0.
  - `ninthHologramRefused` and `/emitter` at 8 → no item given.
  - `starterKitOnce`: first join gets 4 emitters + handbook; second join gets nothing.
- [ ] **Step 2:** `./gradlew runGametest` → FAIL. **Step 3:** implement (block `explosionResistance` 3600000, break check in
  `playerWillDestroy`/`getDestroyProgress` = 0 for non-owner; spec persisted in BE save/load). **Step 4:** PASS.
- [ ] **Step 5:** commit `feat: hologram emitter with owner-only control and starter kit`.

### Task 6: Hologram rendering, picking, corner drag, settings screen

**Files:**
- Create: `client/render/{HologramRenderer,HologramLook}.java`, `client/interact/{HologramPicker,CornerDrag,HologramSettingsScreen}.java`
- Create: `assets/office/textures/misc/{frosted.png,offline.png,scanlines.png}`
- Test: `mod/src/gametest/java/local/carter/office/client/HologramClientGameTests.java` (client gametest, screenshots)

**Interfaces:**
- Consumes: `HologramBlockEntity`, payloads (Task 5), `HologramGeometry` (Task 4).
- Produces:
  - `HologramRenderer` (BlockEntityRenderer for `HOLOGRAM_BE`; `shouldRenderOffScreen` true; view distance 64):
    draws, in order, the content quad (`ScreenTexture` for this hologram or a look texture), edge glow, scanlines.
  - `HologramLook.State { LIVE, CONNECTING, PRIVATE_FROSTED, OFFLINE, WINDOW_CLOSED, NEEDS_HELPER }` and
    `HologramLook.forViewer(spec, ownerOnline, isOwner, hasFrames) -> State`.
  - `HologramPicker.pick(float partialTick) -> Optional<Picked(HologramBlockEntity be, Hit hit)>` (nearest hologram along
    the crosshair ray, max 32 blocks).
  - `ScreenTextureRegistry.get(UUID hologramId) -> ScreenTexture` (created in Task 7; renderer uses it if present).

- [ ] **Step 1:** Re-derive the in-world quad from commit `b2f8389` against 26.3 (`./gradlew genSources`; find the 26.3
  BlockEntityRenderer render-state/submit API and an emissive, translucent RenderType for a dynamic texture). Content quad
  is full-bright. Glow = additive cyan quad 0.06 blocks larger behind content. Scanlines alpha = 0.10 when the viewer is
  > 3 blocks away, fading to 0 at ≤ 3 blocks and 0 while zoomed. Hologram floats at `emitter + off`, rotated by `yaw`.
- [ ] **Step 2:** Picking + interactions (only while holding the emitter item, except click-to-zoom which works empty-handed):
  right-click on a corner and hold → `CornerDrag` shows a cyan outline at the new size each frame, release sends
  `ResizeHologram`; right-click the emitter block → `HologramSettingsScreen` (vanilla-styled: Shape buttons, Mode toggle,
  Display slot 1–4 / window picker, Privacy Public/Private/Show to… with online-player checkboxes, Remove); Shift+right-click
  and hold on the hologram body → drag to move (sends `MoveHologram`); scroll while moving → rotate yaw by 15°.
  Non-owners see the settings screen read-only with "Owned by <name>".
- [ ] **Step 3:** Client gametests (screenshots saved to `build/run/clientGameTest/screenshots`):
  `placedHologramVisible` (emitter placed, screenshot center pixel is not the wall color), `cornerDragResizes` (simulate
  drag → BE width increases), `offlineLook` (owner UUID not online → `HologramLook.OFFLINE`), unit test
  `HologramLookTest`: PRIVATE + not owner → PRIVATE_FROSTED regardless of `hasFrames`.
- [ ] **Step 4:** `./gradlew runClientGametest` (CI Linux with xvfb; locally only with the game closed) → PASS.
- [ ] **Step 5:** Manual look check on Carter's Mac with Sodium on: screenshots from 2, 6, 15 blocks; text in a test
  pattern readable at 2 blocks. Commit `feat(client): hologram rendering, picking, corner drag, settings`.

### Task 7: Live texture and capture sources

**Files:**
- Create: `client/render/ScreenTexture.java`, `client/capture/{CaptureSource,TestPatternSource}.java`
- Test: `mod/src/test/java/local/carter/office/client/ScreenTextureMathTest.java`, client gametest `liveTextureUpdates`

**Interfaces:**
- Consumes: Home Base `AppSurface.java` upload code (full frame + dirty-rect patch via `CommandEncoder.writeToTexture`).
- Produces:
  - `interface CaptureSource { void start(Target t, int maxFps, FrameSink sink); void stop(); boolean isLive(); }`,
    `record Target(Mode mode, int displaySlot, String windowKey, Shape shape)`,
    `interface FrameSink { void full(int w, int h, ByteBuffer rgba); void patch(int x, int y, int w, int h, ByteBuffer rgba); void ended(String reason); }`.
  - `ScreenTexture implements FrameSink`: `Identifier id()`, `int width()`, `int height()`, `void clear()` (frees GPU
    texture + buffers), uploads on the render thread only (queue from capture threads, coalesce to newest).
  - `TestPatternSource`: 1920×1080 grid + moving bar + small text, for tests and the self-test.

- [ ] **Step 1:** Tests: `ScreenTextureMathTest` — patch rect clipped to texture bounds; size change (1920×1080 → 2880×1620)
  reallocates; `clear()` drops pending patches. Client gametest `liveTextureUpdates`: own hologram fed by
  `TestPatternSource` changes pixels between two screenshots 500 ms apart.
- [ ] **Step 2:** FAIL → implement → PASS. **Step 3:** commit `feat(client): live screen textures`.

---

## Phase C — Mac: your own screens

### Task 8: OfficeHelper.app (Swift)

**Files:**
- Create: `helper-mac/Package.swift`, `Sources/OfficeHelper/*.swift` (listed in File Structure), `CGVirtualDisplay.h`
- Create: `helper-mac/build.sh` (builds `build/OfficeHelper.app`, bundle id `local.carter.office-helper`, LSUIElement),
  `helper-mac/sign.sh` (port of `sign-local.py`; identity "Minecraft Office Helper" in
  `~/Library/Application Support/Minecraft Office/Signing/`, never committed)
- Test: `helper-mac/Tests/OfficeHelperTests/{ProtocolTests,DisplaySpecTests}.swift`

**Interfaces:**
- Socket: `~/Library/Application Support/Minecraft Office/helper.sock`, dir 0700, peer-uid check, token from
  `.../helper.token` (0600, written by helper at launch). Framing = Home Base: `u32 BE length | u8 type | payload`;
  control payloads JSON; frames binary. Protocol version 1.
- Mod → helper: `HELLO=1 {token, version, mcPid}`, `LIST_WINDOWS=2`, `CREATE_DISPLAY=3 {slot, w, h}`,
  `REMOVE_DISPLAY=4 {slot}`, `CAPTURE=5 {surface, kind:"display"|"window", slot|windowKey, maxW, fps}`, `STOP=6 {surface}`,
  `READY=7 {surface}`, `ZOOM_IN=8 {surface, mcWindowFrame}`, `ZOOM_OUT=9`.
- Helper → mod: `WELCOME=64 {version, screenRecording:bool, accessibility:bool}`, `WINDOWS=65 [{windowKey, app, title, w, h}]`,
  `DISPLAY=66 {slot, displayId, w, h}`, `FRAME=67`, `PATCH=68` (Home Base layouts), `ENDED=69 {surface, reason}`,
  `EXIT_ZOOM=70`, `ERROR=71 {code, message}`.
- `windowKey` = `"<bundleId>|<CGWindowID>"`.

- [ ] **Step 1: Tests:** `ProtocolTests` — encode/decode round trip for every message; frame > 64 MiB rejected; bad token
  closes. `DisplaySpecTests` — slot 1..4 → unique name "Office Display N" and unique serial; slot 5 rejected; size from
  shape table.
- [ ] **Step 2:** `swift test` → FAIL. **Step 3:** implement:
  - `VirtualDisplays`: `CGVirtualDisplay` via bridging header (pattern from Chromium `virtual_display_util_mac.mm` /
    go-macos/virtualdisplay); advertise 1× and HiDPI modes but **apply only the 1× mode** (so removal works); remove on
    `REMOVE_DISPLAY` and on process exit.
  - `Capture`: from `CaptureEngine.swift`, add display capture (`SCContentFilter(display:excludingWindows:)`, cursor shown),
    match display by `displayID` and assert it (known wrong-display bug), dirty rects → PATCH, at most one frame in flight
    per surface (wait for READY), 30 fps when zoomed else requested fps; ENDED when window closes.
  - `Placement`: `place()` from `HomeBaseSession.swift`; remember the window's original frame, restore on `ZOOM_OUT`.
  - `CursorFocus`: on `ZOOM_IN` of a display → warp cursor to its center; 120 Hz clamp inside that display's bounds;
    release on exit.
  - `ExitHotkey`: global key monitor (Accessibility); double Esc ≤ 350 ms or Ctrl+Alt+Cmd+Esc → send `EXIT_ZOOM`, release
    cursor, restore placed window, activate the Minecraft pid.
  - Quit when the mod's connection closes (displays vanish with the process).
- [ ] **Step 4:** `swift test` → PASS; `build.sh && sign.sh`; manual: launch via `open -g build/OfficeHelper.app`, grant
  Screen Recording + Accessibility once; `helper-mac/Tests/manual.md` steps: create slot 1 → System Settings › Displays shows
  "Office Display 1"; remove → gone within 3 s; kill helper → display gone.
- [ ] **Step 5:** Commit `feat(helper-mac): virtual displays, capture, placement, exit hotkey`.

### Task 9: Mod ↔ helper connection, install and permissions

**Files:**
- Create: `client/helper/{HelperConnection,HelperInstaller}.java`, `client/capture/MacHelperSource.java`, `client/platform/{Platform,MacPlatform}.java`
- Test: `mod/src/test/java/local/carter/office/client/HelperProtocolTest.java` (Java side of the same round trips, using
  fixtures generated by the Swift tests into `helper-mac/Tests/fixtures/*.bin`)

**Interfaces:**
- Consumes: protocol (Task 8), `CaptureSource`/`FrameSink` (Task 7).
- Produces: `Platform.current() -> Platform` with `boolean supportsExtend()`, `List<WindowInfo> windows()`,
  `void zoomIn(HologramSpec, Rect mcWindow)`, `void zoomOut()`, `void onExitRequested(Runnable)`;
  `HelperInstaller.ensureInstalled() -> CompletableFuture<Path>` (download `OfficeHelper-<version>.zip` from the GitHub
  Release into `~/Library/Application Support/Minecraft Office/`, verify SHA-256 from the release manifest, unzip, launch
  with `/usr/bin/open -g`); `HelperConnection` reconnects with backoff 0.5→8 s.

- [ ] **Step 1:** Tests: Java decodes Swift fixtures byte-exactly; `MacHelperSource` given an `ENDED{reason:"window closed"}`
  calls `sink.ended` and the hologram look becomes `WINDOW_CLOSED`; checksum mismatch → install refused.
- [ ] **Step 2:** FAIL → implement. On join: connect/launch helper; for each own EXTEND hologram send `CREATE_DISPLAY`;
  own holograms capture at 5 fps while visible, 0 when not; missing permissions → hologram shows `NEEDS_HELPER` and a chat
  link button "Set up screens" opening `SETUP.md#mac`. Dev override: system property `office.helperPath` uses a local build.
- [ ] **Step 3:** PASS; manual on Carter's Mac: place EXTEND hologram → "Office Display 1" appears and its desktop shows on
  the hologram; drag a Finder window onto it → visible in-world; WINDOW hologram picking Notes → shows Notes; quit Notes →
  "Window closed". Commit `feat(client): Mac helper connection, install, own screens`.

### Task 10: Zoom in to work

**Files:**
- Create: `client/zoom/{ZoomController,DoubleEsc}.java`
- Test: `mod/src/test/java/local/carter/office/client/{DoubleEscTest,ZoomStateTest}.java`

**Interfaces:**
- Consumes: `HologramPicker`, `Platform`, `ScreenTexture`.
- Produces: `ZoomController.State { WORLD, ZOOMING_IN, WORKING, ZOOMING_OUT }`, `ZoomController.request(HologramBlockEntity)`,
  `ZoomController.exit()`; sends `SetWorking(true/false)`.

- [ ] **Step 1:** Tests: `DoubleEscTest` — presses at 0 and 300 ms → exit; 0 and 400 ms → no; a single press is passed to
  the app. `ZoomStateTest` — request on a non-owned hologram → stays WORLD; request while WORKING → ignored; capture
  ENDED while WORKING → ZOOMING_OUT then WORLD.
- [ ] **Step 2:** Implement: click own hologram (empty hand) → 350 ms ease-in-out camera move until the hologram fills the
  view (hide hand/HUD), then WORKING: EXTEND → full-window overlay showing the 30-fps local capture + `Platform.zoomIn`
  (cursor moves onto that display); WINDOW → `Platform.zoomIn` places the real window over Minecraft and focuses it.
  Exit (double-Esc in Minecraft, or `EXIT_ZOOM` from platform) → reverse animation. Server shows a "⌨ working" suffix on
  the player's name tag while `SetWorking(true)`. Minecraft option `pauseOnLostFocus=false` enforced while WORKING.
- [ ] **Step 3:** PASS + manual on Carter's Mac (game only): EXTEND — type in TextEdit on the virtual display, latency feels
  normal, double-Esc returns; WINDOW — Chrome over the game, typing works, double-Esc returns and Chrome's frame is
  restored. Record FPS from `FRAMES` logging (target ≥ 50 fps idle in office). Commit `feat(client): zoom in to work`.

---

## Phase D — Windows (beta, Window mode)

### Task 11: Windows capture and window control without a helper

**Files:**
- Create: `client/capture/JavaDesktopSource.java`, `client/platform/{WindowsPlatform,Win32}.java`
- Modify: `build.gradle` — `include` webrtc-java 0.20.0 + `windows-x86_64` classifier (jar-in-jar)
- Test: `mod/src/test/java/local/carter/office/client/JavaDesktopSourceWindowsTest.java` (`@EnabledOnOs(WINDOWS)`)

**Interfaces:**
- Consumes: `CaptureSource`, `Platform` (Tasks 7, 9).
- Produces: `WindowsPlatform.supportsExtend() == false`; `windows()` from `WindowCapturer.getDesktopSources()`;
  `windowKey` = `"<exe>|<HWND hex>"`; `Win32` (JNA): `GetWindowRect`, `SetWindowPos`, `SetForegroundWindow`,
  `IsWindow`, low-level keyboard hook thread for double-Esc / Ctrl+Alt+Shift+Esc; Minecraft HWND via `GLFWNativeWin32.glfwGetWin32Window`.

- [ ] **Step 1:** Tests (CI `windows-latest`): natives load; spawn `notepad.exe`, find it in `windows()`, capture ≥ 1 frame
  with non-zero size (skip with a logged reason if the runner has no interactive desktop — never fake a pass);
  `IsWindow` false after killing notepad → `sink.ended("window closed")`.
- [ ] **Step 2:** Implement: BGRA → RGBA convert into `FrameSink.full` (no dirty rects on Windows v1; cap 10 fps unless
  WORKING); zoom: save rect, `SetWindowPos` to Minecraft's client rect, `SetForegroundWindow`; exit restores rect and focuses
  Minecraft. EXTEND option is greyed out on Windows with tooltip "Coming soon on Windows".
- [ ] **Step 3:** CI green on Windows. Commit `feat(client): Windows window capture and zoom (beta)`.

---

## Phase E — sharing screens with friends

### Task 12: Tile codec and packetizer (pure Java)

**Files:**
- Create: `core/{TileCodec,TileFrame,Packetizer,StreamHeader}.java`
- Test: `mod/src/test/java/local/carter/office/core/{TileCodecTest,PacketizerTest}.java`

**Interfaces:**
- Produces:
  - `TileCodec(int tile = 64)`: `List<Tile> diff(int w, int h, ByteBuffer rgba)` (CRC32 per tile vs previous frame; first
    frame / size change → all tiles), `List<Tile> refresh(int n)` (next n tiles round-robin), `record Tile(int tx, int ty, int w, int h, ByteBuffer rgba)`.
    JPEG encode/decode is injected: `interface Jpeg { byte[] encode(Tile, int quality); void decodeInto(byte[], ByteBuffer dst, int w, int h); }`
    (client impl uses LWJGL `STBImageWrite.stbi_write_jpg_to_func` / `STBImage.stbi_load_from_memory`; tests use a fake).
  - `StreamHeader` (17 bytes, big-endian): `magic 0x4F46 | ver u8=1 | type u8 (1=HELLO,2=HELLO_ACK,3=TILE,4=CLEAR,5=SIZE) |
    streamId i32 | frameSeq i32 | tx u16 | ty u16 | part u8 | parts u8`; client→server packets append an 8-byte token.
  - `Packetizer`: `List<byte[]> split(StreamHeader h, byte[] jpeg)` with each datagram ≤ 1200 bytes;
    `Optional<Assembled> accept(byte[] datagram)`; incomplete tiles dropped after 2 s or when a newer frameSeq for the same
    tile completes.

- [ ] **Step 1:** Tests: identical frames → `diff` empty; one changed pixel → exactly one tile; 1366×768 → 22 columns × 12 rows, last column 22 px
  wide (no out-of-bounds); size change → all tiles + SIZE; `split` of 5,000 bytes → 5 datagrams ≤ 1200, reassembles
  byte-exact in any order; one part missing → nothing emitted; duplicate parts harmless; older frameSeq ignored after newer.
- [ ] **Step 2:** FAIL → implement → PASS. Commit `feat(core): tile diff codec and UDP packetizer`.

### Task 13: Server relay and routing

**Files:**
- Create: `stream/{StreamRelayServer,StreamRouter,OfficeConfig}.java`
- Modify: `net/OfficePayloads.java` — add S2C `StreamToken(long token, int udpPort)`, `StreamDemand(UUID hologramId, int streamId, boolean wanted)`,
  `StreamFallbackTile(int streamId, byte[] datagram)` (S2C) and C2S `FallbackTile(byte[] datagram)`, S2C `StreamEnded(int streamId)`
- Test: `mod/src/test/java/local/carter/office/stream/RelayLoopbackTest.java`, gametest `StreamRoutingGameTests.java`

**Interfaces:**
- Consumes: `StreamHeader`, `Privacy`, `HologramBlockEntity`.
- Produces: `config/office.json` `{ "udpPort": 24455, "viewRange": 48, "maxKbpsPerStream": 2000 }`;
  `StreamRouter.viewers(HologramBlockEntity) -> Set<ServerPlayer>` (online, same dimension, within `viewRange`,
  `privacy.canView`, not the owner); recomputed every 10 ticks and on privacy change/disconnect; owner gets
  `StreamDemand(wanted = !viewers.isEmpty())`.
- Relay: Netty `DatagramChannel` on `udpPort`; HELLO with valid token binds player↔address and returns HELLO_ACK; TILE from
  the owner of `streamId` only → forwarded unchanged (token stripped) to current viewers; per-stream token-bucket at
  `maxKbpsPerStream`. Fallback: same datagrams wrapped in payloads when a client reports no HELLO_ACK within 5 s
  (limit 150 KB/s per stream, each payload ≤ 30 KiB).

- [ ] **Step 1: Tests:**
  - `RelayLoopbackTest` (real sockets on 127.0.0.1, stub router): valid HELLO → ACK; bad token → no ACK, packets dropped;
    owner TILE reaches both viewers byte-exact; non-owner TILE dropped; over-budget packets dropped, not queued.
  - GameTests: viewer at 40 blocks gets demand, at 60 not; privacy → PRIVATE while viewing → owner gets `wanted=false` and
    viewer gets `StreamEnded` within 20 ticks; SHOW_TO {B} → B receives, C not; owner disconnect → viewers get `StreamEnded`
    and BE `ownerOnline=false` within 60 ticks.
- [ ] **Step 2:** FAIL → implement → PASS. Commit `feat(server): screen relay with range and privacy routing`.

### Task 14: Client sending and receiving

**Files:**
- Create: `client/stream/{StreamClient,StreamSender,StreamReceiver}.java`
- Test: `mod/src/test/java/local/carter/office/client/StreamPipelineTest.java`

**Interfaces:**
- Consumes: Tasks 7, 9, 11 sources; Task 12 codec; Task 13 payloads.
- Produces: `StreamClient.connect(StreamToken)`; `StreamSender.onDemand(StreamDemand)` starts/stops a shared copy of the
  hologram's capture (downscale to ≤ 1280 wide, 10 fps, JPEG q70, `refresh(8)` per frame); **never starts for a hologram
  whose privacy is PRIVATE** (checked again at every frame); `StreamReceiver` assembles tiles into the viewer's
  `ScreenTexture`; `StreamEnded` or 3 s without packets → `texture.clear()` and look = OFFLINE/PRIVATE_FROSTED as appropriate.

- [ ] **Step 1:** Tests (fake Jpeg + in-memory relay): sender with PRIVATE spec emits zero packets even if demand=true;
  switching to PRIVATE mid-stream stops within one frame; receiver after `StreamEnded` holds no pixel buffers
  (`texture.width()==0`); 20% random datagram loss → image converges to source within 3 s via refresh.
- [ ] **Step 2:** FAIL → implement → PASS. Commit `feat(client): share and view screens`.

---

## Phase F — ship it (Sunday/Monday)

### Task 15: Self-test, releases, friend guide

**Files:**
- Create: `client/selftest/{SelfTestScreen,SelfTestReport}.java`, `.github/workflows/release.yml`, `docs/friends/SETUP.md`
- Modify: `pack/mods/office.pw.toml`, `README.md`

**Interfaces:**
- Produces: Esc menu button "Screen self-test" running: OS/CPU/RAM/GPU, helper/permissions (Mac) or natives (Windows),
  capture 1 frame from `TestPatternSource` and from the chosen real source, JPEG encode/decode, UDP HELLO_ACK vs fallback,
  zoom-in/out dry run. Report = plain text, no screen pixels, window titles reduced to app names; buttons "Copy report"
  and "Open folder" (`<gameDir>/office-selftest/*.txt`).
- `release.yml` on tag `v*`: build mod jar (CI Linux), helper zip (macOS runner build; **signing happens on Carter's Mac**
  via `helper-mac/sign.sh` then uploaded with `gh release upload` — the key never goes to CI), `manifest.json` with SHA-256s;
  update `pack/mods/office.pw.toml` (url + sha512) and publish `pack/` to GitHub Pages.

- [ ] **Step 1:** Unit test `SelfTestReportTest`: report contains no window titles, only app names; contains OS + result
  lines for every step. FAIL → implement → PASS.
- [ ] **Step 2:** **Ask Carter** before creating the GitHub repo/Pages (public, needed so Prism can fetch the pack without
  logins). Then tag `v0.1.0`, run release, `packwiz refresh`.
- [ ] **Step 3:** `SETUP.md`: install Prism + Java (Prism manages it), add pack URL, join address; Mac: first-run helper
  prompts with screenshots (Screen Recording, Accessibility; how to re-enable); Windows: SmartScreen "More info → Run
  anyway" if shown, Window mode only for now, how to run the self-test; voice chat key; using holograms (place, drag corner,
  right-click emitter, double-Esc). Commit `feat: self-test, release pipeline, friend setup guide`.

### Task 16: End-to-end verification and Monday checklist

**Files:** Create `docs/verification/2026-10-26-v1.md` (evidence: commands, screenshots, numbers)

- [ ] **Step 1:** Fresh Prism instance from the published pack URL on Carter's Mac joins the hosted server (nothing else
  heavy running). Check: starter kit; place 2 holograms (EXTEND + WINDOW); drag-resize to wall size; privacy switch;
  zoom in/out on both; FPS ≥ 45 in the office with 2 live holograms; memory within the 8 GB budget (no swap growth).
- [ ] **Step 2:** Sharing check without a second computer: run a second, minimal **headless viewer** (`./gradlew
  runViewerProbe` — a JUnit-driven UDP client using the Task 13 protocol with a test token issued by `/office probe`, op only)
  from the host's console network or CI, confirming tiles arrive for PUBLIC and stop for PRIVATE. Remove `/office probe`
  from non-op use.
- [ ] **Step 3:** Run the self-test; attach report. Write the "Monday checklist" for Carter: send friends `SETUP.md` link,
  whitelist names, ask the first Windows friend to run the self-test and paste the report.
- [ ] **Step 4:** Commit `docs: v1 verification`. Hand over with known limits (Windows Extend, audio after Monday).
