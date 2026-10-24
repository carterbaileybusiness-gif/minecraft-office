# Display Map + Magic Onboarding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the hologram settings screen's "Display 1 2 3 4" row with a Windows-style map of the player's real
monitors and Office displays (pick, drag + Apply, Mirror picks its monitor, Identify), and make the Grimoire learnable
in game (self-updating handbook, tooltips, holding-it-wrong hint, a "How to cast" tab).

**Architecture:** Part B (Tasks 1–2) is mod-only and ships first. Part A grows the helper protocol behind a
`"screens"` feature flag exchanged in HELLO / WELCOME (protocol version stays 1), so a mod and helper one release apart
never send each other a type the other closes the connection on. The helper owns the truth about displays (list,
move, persist, overlays, capture, input); the mod owns letters, the map, snapping and the hologram side. The Mirror's
monitor is a new optional `HologramSpec.mirrorDisplay` set through its own payload; only opaque ids and one-character
labels ever reach the server.

**Tech Stack:** Minecraft 26.3 / Fabric (unobfuscated Mojang names, Java 25, JUnit 5, Fabric GameTest + client
GameTest), LWJGL SDL3 (`org.lwjgl.sdl`, bundled), JNA (Windows), Swift 5 helper (AppKit, CoreGraphics,
ScreenCaptureKit), `helper-mac/check.sh` test runner.

**Spec:** `docs/superpowers/specs/2026-10-24-display-map-and-magic-onboarding-design.md`

## Global Constraints

From the spec and Carter's standing rules (verbatim values):
- No TCC edits, no new Mac permissions: the helper uses only Screen Recording and Accessibility, which it already has.
- Helper and mod may be **one release apart**: every new helper message is gated by the `"screens"` feature (both
  directions). Without it the map shows what the mod already knows and Apply / Identify / Mirror-pick are greyed with the
  hint **"Restart Minecraft to update the helper"**.
- **Monitor names never leave the owner's computer.** Only opaque ids (CGDisplay UUID string on Mac, Windows device
  name such as `\\.\DISPLAY2`) and labels matching `[A-Z]|[1-4]` reach the server.
- Real monitors are **letters** (A = main, then B, C… ordered by origin x, then y). Office displays stay **1–4**.
  No existing hologram changes meaning; old Mirrors (no `mirrorDisplay`) copy the main display.
- Mirror capture of any monitor **excludes Minecraft's own window** (same rule and retry as today's Mirror).
- Identify lasts **3 s**. Real monitors cannot be dragged. Extend stays greyed on Windows.
- Screens fit with **no scrolling at any GUI scale** (the map lives inside the existing two-column body).
- Hold hint: action bar, **at most once per 30 s**, client side. Handbook join refresh: chat line only if something was
  replaced: **"Your Office Handbook was updated."**
- Handbook text stays the source (Java, `StarterKit.PAGES`, ≤ 14 lines a page); new tooltip and page strings live in
  `mod/src/main/resources/assets/office/lang/en_us.json`. Part A screen strings stay literals like the rest of
  `HologramSettingsScreen`.
- Tests: Java unit tests + server GameTests locally only through `scripts/heavy.sh` (one heavy process at a time):
  `scripts/heavy.sh ./gradlew test --no-daemon --tests '<pattern>'` and
  `scripts/heavy.sh ./gradlew build test runGametest --no-daemon`. **Client GameTests run in CI only — never
  `runClientGameTest` locally**; locally they only need to compile (`build`). Swift: `scripts/heavy.sh bash
  helper-mac/check.sh` (also rewrites `helper-mac/Tests/fixtures/*.bin`; commit them — CI fails on a fixture diff);
  helper compile: `scripts/heavy.sh bash helper-mac/build.sh`.
- Git: never `git add -f`; never commit `.superpowers/` or `test-reports/`; never put the server address in the repo.
  Commit after each task on `feat/v1`; do not push (the controller pushes for CI).
- 26.3 API: GUI draws in `extractRenderState(GuiGraphicsExtractor …)`, `mc.gui.screen()`, SDL3 (not GLFW). Never guess a
  signature (SDL, Fabric `ItemTooltipCallback`, `Gui` overlay message): check `genSources` / the LWJGL jar.

### Planner's calls (spec silent or "left to the builder")
1. Mirror's monitor = `HologramSpec.mirrorDisplay` (nullable String, NBT key `mirrorDisplay`, omitted when null; null =
   follow the main display). Set by a **new c2s `SetMirrorDisplay`** (not a new `UpdateHologram` field: an older server
   would fail to decode a longer packet). Picking **A** stores **null**.
2. Protocol: HELLO and WELCOME get optional `features: ["screens"]`. New types: **LIST_DISPLAYS 24**, **SET_ORIGINS 25**,
   **IDENTIFY 26** (mod → helper), **DISPLAYS 81** (helper → mod). Optional `display` (monitor id) on CAPTURE kind
   `main`, on slot-0 POINTER_DOWN/MOVE/UP, CLICK, SCROLL, WORK_BEGIN, and on CURSOR. The helper sends DISPLAYS on
   LIST_DISPLAYS, after SET_ORIGINS, and (only when HELLO had `"screens"`) on display reconfiguration, debounced 300 ms.
3. SET_ORIGINS carries all moved displays in one list, applied in one `CGBeginDisplayConfiguration`.
4. Persistence: on every successful SET_ORIGINS the helper saves **every live Office display's** origin, keyed by the
   main display's UUID, in `~/Library/Application Support/Minecraft Office/display-arrangement.json`
   (`{"layouts":{"<main uuid>":{"1":{"x":1440,"y":0}}},"schema":1}`). `arrange` uses a saved spot when it is valid
   (no overlap, shares ≥ 1 pt of edge with main, a monitor or an already placed Office display), else today's automatic
   placement.
5. Snap (mod): candidates against every other screen on all four sides; a candidate must share **≥ 10 %** of the
   moving display's edge; edges align when within **6 %** of the moving display's size; nearest candidate to the drop
   wins; never overlapping area.
6. A Mirror of a non-main monitor is **fully usable in world** (work mode, clicks, scroll, drawn cursor): input carries
   `display`, the helper maps it onto that monitor. Without this, clicks would land on the main display.
7. Monitor names: `NSScreen.localizedName`, shortened — built-in → "MacBook", else first word, ≤ 12 chars, "" → "Screen".
8. Identify overlay: borderless click-through window per display, dark rounded square **28 % of the display's shorter
   side**, centred, big white label; not excluded from captures. The in-world label uses the same 28 % of the hologram's
   shorter side, so the captured overlay sits under it.
9. Identify in world: the owner's client draws its own holograms' labels (Extend → slot, Mirror → letter of the resolved
   monitor, Window → none) and sends c2s `IdentifyHolograms(List<(BlockPos, label)>)`; the server checks ownership and
   relays s2c `IdentifyShown` to each other player in the same level **only for holograms that player may view**
   (`Privacy.canView`). One Identify per player per 1000 ms.
10. Windows: monitors from Win32 `EnumDisplayMonitors` + `GetMonitorInfo` (id = `szDevice`, names from
    `EnumDisplayDevices(adapter, 0).DeviceString`, else "Screen"); Mirror-pick only when `WebRtcNatives.supported()`;
    Identify numbers holograms (and the map) only — no OS overlays (see open question). Mac without a "screens" helper
    (old or not running): SDL3 display list for drawing (names starting "Office Display" are Office displays).
    NoPlatform: one main box "A".
11. Non-owners get no map (the owner's screens aren't theirs): "Shows display N" / "Shows the owner's screen".
12. Not-yet-existing slots 1–4 (Extend) are dashed boxes in a row right of the rightmost screen, top-aligned with main,
    sized at the hologram shape's pixel size; clicking one picks it. Pending drags are discarded on close (Done / Esc);
    Apply is active only while something moved.
13. Handbook refresh on join covers the whole player inventory (main, armour, off hand) and never gives a copy.
14. Grimoire tooltip reads the local player's synced `MagicData` (any carried book is stamped to its holder).
15. Main-hand right-click of a bound Grimoire opens the info screen (`GrimoireItem.use`), so the hold hint fires when
    **that screen closes while the Grimoire is in the main hand** (the action bar is hidden under a screen). The key
    shown is the player's swap-offhand binding (default F).
16. Rebinding Seal tooltip follows the real mechanic (use while bound → element choice; spent only when you choose;
    level, xp, home, cooldowns stay).
17. "How to cast" is the Grimoire's third index tab: left page = handbook Casting text, right page = the hand-sign
    sentence from the handbook Spells page + `magic.office.signs_hint`.
18. Task 11 adds a line about the screen map and Identify to the handbook's Settings page; Task 1 delivers it on join.

## Review Focus

1. A spec copy that drops `mirrorDisplay` (resize, move, shape/privacy/mode change, remount) silently sends the Mirror
   back to the main display → Task 7 `copiesKeepMirrorDisplay` + gametest `updateAndMoveKeepMirrorDisplay`.
2. New mod + old helper: one LIST_DISPLAYS / SET_ORIGINS / IDENTIFY or `display` key and the old helper closes the
   socket (unknown type) or captures the wrong screen → Task 8 `oldHelperGetsNoNewMessages`.
3. The picked monitor is unplugged (while capturing, while working) and plugged back → the Mirror falls back to the main
   display, the map says so, and capture switches back on replug → Task 9 `mirrorFallsBackAndReturns`.
4. A drop with no valid spot, or the Swift and Java touching rules disagreeing → nothing moves and the map shows the
   helper's DISPLAYS → shared vectors V1–V6 in Task 4 `ArrangementTests` and Task 10 `DisplaySnapTest`.
5. Extreme layouts — a monitor left of / above main (negative origins), portrait, 3 monitors + 4 Office displays — in
   the smallest map area (160 × 64) → Task 8 `MapLayoutTest.extremeLayoutsFit`.

Shared touching vectors (main = (0, 0, 1440, 900); rects are x, y, w, h in global points):
V1 (1440, 0, 1920, 1080) valid · V2 (1440, 900, 1920, 1080) invalid (corner only) · V3 (1000, 0, 1920, 1080) invalid
(overlap) · V4 (1500, 0, 1920, 1080) invalid (detached) · V5 (-1920, -180, 1920, 1080) valid · V6 (0, -1080, 1920, 1080) valid.

---

## Part B — Magic onboarding (ships first)

### Task 1: Handbook refreshes on join + Grimoire and Seal tooltips

**Files:**
- Modify: `mod/src/main/java/local/carter/office/hologram/StarterKit.java`, `mod/src/main/java/local/carter/office/OfficeMod.java:55-57`
- Create: `mod/src/client/java/local/carter/office/client/magic/MagicTooltips.java`
- Modify: `mod/src/client/java/local/carter/office/client/magic/MagicClient.java` (register), `mod/src/main/resources/assets/office/lang/en_us.json`
- Test: `mod/src/gametest/java/local/carter/office/HologramGameTests.java`, `mod/src/test/java/local/carter/office/client/magic/MagicTooltipsTest.java`, `mod/src/test/java/local/carter/office/magic/LangCoverageTest.java`

**Interfaces:**
- Produces: `StarterKit.refreshOnJoin(ServerPlayer) -> boolean` (true = replaced ≥ 1 copy, message sent);
  `StarterKit.pageBody(String title) -> String` (body of a handbook page; Task 2's test reads it);
  `MagicTooltips.grimoire(Optional<Element> element, int level) -> List<Component>`, `MagicTooltips.seal() -> List<Component>`.

- [ ] **Step 1: Write the failing tests.** Gametests (style of `handbookCommandReplacesOutdatedInPlace`):
  `joinReplacesOutdatedHandbooksAndSaysSo` — outdated copies in slot 7 and the off hand → `refreshOnJoin` true, both
  slots now hold current pages, count still 2 (true = the message was sent; mock players have no connection to read it
  from); `joinWithCurrentOrNoHandbookIsSilent` — current copy → false; empty inventory → false, still 0 handbooks. Unit `MagicTooltipsTest`: `grimoire(Optional.empty(), 1)` is one line with key
  `item.office.grimoire.tip_unbound`; `grimoire(Optional.of(FIRE), 4)` is two lines, keys `item.office.grimoire.tip_bound`
  (args: element name component, 4) then `item.office.grimoire.tip_cast`; `seal()` keys `item.office.rebinding_seal.tip`.
  `LangCoverageTest.onboardingKeysExist`: the four keys above are present and non-blank.
- [ ] **Step 2: Run** `scripts/heavy.sh ./gradlew build test runGametest --no-daemon` → FAIL (methods missing).
- [ ] **Step 3: Implement.** Extract the replace loop of `giveOrUpdateHandbook` into a private
  `replaceOutdated(Inventory) -> {replaced, hasCurrent}` used by both. `refreshOnJoin` calls it and on `replaced` sends
  `Component.literal("Your Office Handbook was updated.")` as a system message. Hook it in the JOIN handler after
  `giveIfFirstJoin`. Lang values:
  `tip_unbound` "Right-click to open and choose your element." · `tip_bound` "%s · Level %d" · `tip_cast` "Off hand +
  empty main hand: tap right-click to cast, hold to charge. Shift+scroll or hold R: pick spell." · `rebinding_seal.tip`
  "Right-click while bound to choose your element again. Used up only when you pick one; your level stays." Lines are
  grey (`ChatFormatting.GRAY`), the bound line in the element's colour. Register Fabric `ItemTooltipCallback` in
  `MagicClient`: Grimoire → `grimoire(data.element(), data.level())` from `MagicAttachments.get(mc.player)`; Seal → `seal()`.
- [ ] **Step 4: Run** the same command → PASS.
- [ ] **Step 5: Commit** `git add` the files above; `git commit -m "feat(magic): handbook refreshes on join; grimoire and seal tooltips"`.

### Task 2: Holding-it-wrong hint + "How to cast" tab

**Files:**
- Create: `mod/src/client/java/local/carter/office/client/magic/HoldHint.java`
- Modify: `mod/src/client/java/local/carter/office/client/magic/screen/GrimoireLayout.java` (`TABS = 3`),
  `mod/src/client/java/local/carter/office/client/magic/screen/GrimoireScreen.java` (tab 2, page drawing, Left/Right
  step through tabs, hint on close), `en_us.json`
- Test: `mod/src/test/java/local/carter/office/client/magic/HoldHintTest.java`, `.../client/magic/GrimoireLayoutTest.java`,
  `mod/src/test/java/local/carter/office/magic/HowToCastTextTest.java`, `mod/src/gametest/java/local/carter/office/client/MagicClientGameTests.java`

**Interfaces:**
- Consumes: `StarterKit.pageBody(String)` (Task 1).
- Produces: `HoldHint(LongSupplier clockMs)`, `boolean tryShow()` (true at most once per 30 000 ms);
  `GrimoireScreen.TAB_HOWTO = 2`.

- [ ] **Step 1: Write the failing tests.** `HoldHintTest`: first `tryShow()` true; again at +29 999 ms false; at
  +30 000 ms true. `GrimoireLayoutTest.threeTabsFit`: for every size in `SIZES` and `MORE`, 3 tab rects, each inside the
  screen, none overlapping, top to bottom, all above `book().bottom()`. `HowToCastTextTest`: lang
  `magic.office.howto_cast` equals `StarterKit.pageBody("Casting")` with `\n` → `" "`; `magic.office.howto_signs` equals
  the second line of `StarterKit.pageBody("Spells")` ("The ultimate asks for hand signs: press the arrows shown before
  time runs out."); keys `magic.office.tab_howto` = "How to cast", `magic.office.hold_hint` = "Put the Grimoire in your
  off hand (%s) with an empty main hand to cast." exist. Client gametest (CI only) `grimoireHowToTabFits`: open the
  screen at GUI scales 1–4, switch to tab 2, screenshot, assert no exception.
- [ ] **Step 2: Run** `scripts/heavy.sh ./gradlew test --no-daemon --tests 'local.carter.office.client.magic.*' --tests 'local.carter.office.magic.HowToCastTextTest'` → FAIL.
- [ ] **Step 3: Implement.** Tab 2 icon: the book's own page glyph or a "?" drawn with `UiKit.centred`; tooltip
  `magic.office.tab_howto`. Page text via `UiKit.wrap(font, text, page.w(), l.unit(), maxLines, lines)` with
  `maxLines = page.h() / (LINE × unit)` (wrap shrinks the scale until it fits; nothing scrolls). Left/Right: previous /
  next tab, clamped. Footer on tab 2 = `magic.office.hint`. `HoldHint` lives in `MagicClient` (one instance, real
  clock). In `GrimoireScreen.onClose()`: if `GrimoireItem.isGrimoire(player.getMainHandItem())` and `tryShow()`, set the
  action-bar overlay to `Component.translatable("magic.office.hold_hint", mc.options.keySwapOffhand.getTranslatedKeyMessage())`.
  Nothing is shown for the off-hand-with-item case (that click never reaches the Grimoire).
- [ ] **Step 4: Run** Step 2's command → PASS; `scripts/heavy.sh ./gradlew build --no-daemon` → BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `git commit -m "feat(magic): how-to-cast tab and holding-it-wrong hint"` (files above).

---

## Part A — Display map

### Task 3: Helper protocol: "screens" feature and the new messages (codec only, both sides)

**Files:**
- Modify: `helper-mac/Sources/OfficeHelper/Protocol.swift`, `helper-mac/Tests/OfficeHelperTests/ProtocolTests.swift`,
  `helper-mac/Tests/fixtures/README.md`, `helper-mac/Tests/fixtures/*.bin` (9 new),
  `mod/src/client/java/local/carter/office/client/helper/HelperProtocol.java`,
  `mod/src/test/java/local/carter/office/client/HelperProtocolTest.java`
- Modify (exhaustive switches only): `helper-mac/Sources/OfficeHelper/Session.swift:132-136` (new mod → helper cases
  answer `bad_request "not yet"` until Task 4; `.displays` joins the helper → mod list)

**Interfaces (both languages, same names):**
- Feature constant `"screens"`: Swift `OfficeProtocol.screensFeature`, Java `HelperProtocol.FEATURE_SCREENS`.
- Swift: `HelloPayload.features: [String]? = nil`, `WelcomePayload.features: [String]? = nil`;
  `ScreenInfo {id: String, slot: Int, main: Bool, name: String?, x, y, w, h: Int}`, `DisplaysPayload {displays}`,
  `SlotOrigin {slot, x, y}`, `SetOriginsPayload {origins}`, `IdentifyLabel {display: String, label: String}`,
  `IdentifyPayload {labels}`; `CapturePayload.display: String?`, `PointerPayload.display: String?`,
  `ScrollPayload.display: String?`, `CursorPayload.display: String?` (all `= nil`); `Message.listDisplays`,
  `.setOrigins(_)`, `.identify(_)`, `.displays(_)`.
- Java: `LIST_DISPLAYS = 24, SET_ORIGINS = 25, IDENTIFY = 26, DISPLAYS = 81`; records `ListDisplays()`,
  `SetOrigins(List<SlotOrigin> origins)`, `SlotOrigin(int slot, int x, int y)`, `Identify(List<IdentifyLabel> labels)`,
  `IdentifyLabel(String display, String label)`, `Displays(List<ScreenInfo> displays)`,
  `ScreenInfo(String id, int slot, boolean main, @Nullable String name, int x, int y, int w, int h)`;
  `Hello(..., @Nullable List<String> features)` and `Welcome(..., @Nullable List<String> features)` with the old
  constructors kept and `Welcome.has(String feature)`; `Capture`, `PointerDown/Move/Up`, `Scroll`, `Click`, `WorkBegin`,
  `Cursor` each gain a trailing `@Nullable String display` with their old constructors kept.
- Rules (encoder and decoder, both sides): SET_ORIGINS 1–4 entries, slots 1–4, unique; IDENTIFY 1–16 labels, label 1–2
  chars, display id 1–64 chars; DISPLAYS ≤ 16 entries, slot 0–4, w, h > 0, id 1–64 chars; `display` on CAPTURE only
  with kind `main`, on pointer / scroll / cursor only with slot 0; otherwise malformed.

- [ ] **Step 1: Write the failing tests.** Swift `examples` gain (values in README): `hello_features`
  `{"features":["screens"],"mcPid":4242,"token":…,"version":1}`, `welcome_features`
  `{"accessibility":true,"features":["screens"],"screenRecording":true,"version":1}`, `list_displays` `{}`, `displays`
  `{"displays":[{"h":900,"id":"37D8832A-2D66-02CA-B9F7-8F30A301B230","main":true,"name":"MacBook","slot":0,"w":1440,"x":0,"y":0},{"h":1080,"id":"9B2F1C44-5E6A-4D7B-8C9D-0E1F2A3B4C5D","main":false,"name":"LG","slot":0,"w":1920,"x":-1920,"y":-180},{"h":1080,"id":"5A1E0F00-0000-4000-8000-0000000000A1","main":false,"slot":1,"w":1920,"x":1440,"y":0}]}`,
  `set_origins` `{"origins":[{"slot":1,"x":1440,"y":-180}]}`, `identify` (labels A for the main id, B for the LG id,
  1 for the Office id), `capture_monitor` `{"display":"<LG id>","fps":5,"kind":"main","maxW":1920,"surface":9}`,
  `work_begin_monitor` `{"button":"left","display":"<LG id>","h":1080,"slot":0,"w":1920,"x":960,"y":540}`,
  `cursor_monitor` `{"display":"<LG id>","h":1080,"slot":0,"w":1920,"x":100,"y":200}`. New Swift `run("screens rules")`
  expects throws for: empty / 5-entry / duplicate / slot-5 SET_ORIGINS, label "ABC", 17 labels, CAPTURE kind display
  with `display`, POINTER_MOVE slot 1 with `display`. Java: `screensMessages()` checks each new fixture (decode equals
  the record, byte-identical re-encode; mod → helper ones through `checkModMessage`), `featuresAreOptional()` (old
  `hello` / `welcome` fixtures decode with `features() == null`, `has("screens")` false), `screensRulesMatchTheHelper()`
  (same rejects), `typeConstantsMatchSwift` adds 24, 25, 26, 81, `everyFixtureDecodesAndReencodes` expects **54**.
- [ ] **Step 2: Run** `scripts/heavy.sh bash helper-mac/check.sh` → FAIL; `scripts/heavy.sh ./gradlew test --no-daemon --tests 'local.carter.office.client.HelperProtocolTest'` → FAIL.
- [ ] **Step 3: Implement** the payloads, cases, rules and `isKnownType` ranges (`LIST_APPS..IDENTIFY`, `WELCOME..DISPLAYS`);
  document the new rows and the gating rule in `Tests/fixtures/README.md`.
- [ ] **Step 4: Run** both commands → PASS; `scripts/heavy.sh bash helper-mac/build.sh` compiles.
- [ ] **Step 5: Commit** sources, tests, README and fixtures: `git commit -m "feat(protocol): screens feature and display-map messages"`.

### Task 4: Helper: list displays, move Office displays, remember the arrangement

**Files:**
- Create: `helper-mac/Sources/OfficeHelper/Arrangement.swift` (pure, Foundation + CoreGraphics types only),
  `helper-mac/Sources/OfficeHelper/Screens.swift` (CG/AppKit), `helper-mac/Tests/OfficeHelperTests/ArrangementTests.swift`
- Modify: `helper-mac/Sources/OfficeHelper/Pointer.swift` (`DisplayArrangement.origins` gains `saved`),
  `VirtualDisplays.swift` (`arrange` passes saved spots; `apply(origins:)`), `Session.swift` (LIST_DISPLAYS,
  SET_ORIGINS, reconfiguration push, WELCOME features, remembers HELLO features), `helper-mac/check.sh` (add
  Arrangement.swift), `Tests/OfficeHelperTests/main.swift`, `helper-mac/Tools/devclient.py` (`displays`,
  `origins <slot> <x> <y>`), `helper-mac/Tests/manual.md`

**Interfaces:**
- Pure: `ScreenRules.overlaps(_:_:) -> Bool` (interior), `ScreenRules.sharedEdge(_:_:) -> CGFloat`,
  `ScreenRules.valid(_ r: CGRect, among: [CGRect]) -> Bool` (no overlap, shared edge ≥ 1 with one of them);
  `DisplayArrangement.origins(main:others:ours:saved: [Int: CGPoint] = [:]) -> [Int: CGPoint]`;
  `OriginRequest.check(_ p: SetOriginsPayload, live: [Int: CGRect], fixed: [CGRect]) -> Result<[Int: CGPoint], String>`;
  `SavedArrangement {schema = 1, layouts: [String: [String: SavedOrigin]]}` with `origins(forMain:) -> [Int: CGPoint]`
  and `mutating record(forMain:_:)`; `ArrangementStore(url:)` `load() -> SavedArrangement` (missing / corrupt → empty),
  `save(_:) throws`; `MonitorNames.short(_ localized: String, builtIn: Bool) -> String`.
- CG: `ScreenCatalog.list(ours: [Int: CGDirectDisplayID]) -> [ScreenInfo]`, `ScreenCatalog.uuid(_:) -> String?`,
  `ScreenCatalog.displayID(uuid:) -> CGDirectDisplayID?` (`CGDisplayCreateUUIDFromDisplayID` /
  `CGDisplayGetDisplayIDFromUUID`), `ScreenCatalog.onReconfigure(_:)`.

- [ ] **Step 1: Write the failing tests** (`ArrangementTests`): `valid` gives V1–V6 as listed; saved spot V1 for slot 1 is
  used; saved slot 1 at V4 (detached) → today's automatic origin; saved slot 2 touching saved slot 1 → both used;
  saved spot overlapping a monitor → automatic; every existing `DisplayArrangement` expectation in `PointerTests`
  unchanged; `OriginRequest.check` accepts slot 1 → V1, rejects V2 with "must touch a screen" and V3 with "overlaps",
  accepts slots 1 and 2 swapped in one request; `SavedArrangement` JSON round-trips, two main UUIDs keep separate
  layouts, garbage file → empty; `MonitorNames.short`: ("Built-in Retina Display", true) "MacBook", ("LG HDR 4K",
  false) "LG", ("DELL U2720Q", false) "DELL", ("", false) "Screen", ("Supercalifragilistic", false) 12 chars.
- [ ] **Step 2: Run** `scripts/heavy.sh bash helper-mac/check.sh` → FAIL.
- [ ] **Step 3: Implement.** Order in `origins`: saved slots placed in slot order while valid against main, others and
  already placed ones (repeat until no change), then the rest with today's rule treating placed saved rects as `others`.
  SET_ORIGINS: `OriginRequest.check` (error → `ERROR bad_request` + DISPLAYS unchanged), else configure all in one
  transaction, read back `CGDisplayBounds`, record every live Office display's origin, save, send DISPLAYS. Names come
  from `NSScreen.localizedName` matched by `NSScreenNumber`; Office displays carry no name. The store lives beside the
  helper's socket and token (Application Support/Minecraft Office). Push DISPLAYS on reconfiguration (debounced 300 ms)
  only when the HELLO had `"screens"`; WELCOME always carries `features: ["screens"]`.
- [ ] **Step 4: Run** check.sh → PASS; build.sh compiles; follow the new manual.md steps (create 1 → `displays`; `origins
  1 0 -1080` → display above the main display, cursor crosses at the top; quit and relaunch, create 1 → it comes back
  above; unplug-style check: saved spot next to a monitor that is gone → automatic).
- [ ] **Step 5: Commit** sources, tests, check.sh, devclient.py, manual.md: `git commit -m "feat(helper): list and move Office displays, remember the arrangement"`.

### Task 5: Helper: Mirror any monitor, with in-world input on it

**Files:**
- Modify: `helper-mac/Sources/OfficeHelper/Capture.swift` (`Source.main(excluding:display:)`), `Session.swift`
  (capture + input wiring), `Pointer.swift` (`PointerTarget`, `CursorLocator`), `InWorld.swift` (monitor lookups;
  cursor hidden on mirrored monitors), `helper-mac/Tests/OfficeHelperTests/PointerTests.swift`, `helper-mac/Tests/manual.md`,
  `helper-mac/Tools/devclient.py` (`capturemon <surface> <uuid>`)

**Interfaces:**
- `PointerTarget.globalPoint(slot:x:y:w:h:display: String?, main:monitor: (String) -> CGRect?, display:)` — slot 0 with
  a known `display` maps onto that monitor's bounds, unknown / nil → main.
- `CursorLocator.locate(_:main:displays:monitors: [(id: String, bounds: CGRect)]) -> CursorPayload` — on a listed
  monitor: slot 0, `display` = its id, points relative to it.
- `SurfaceCapture.Source.main(excluding: pid_t?, display: CGDirectDisplayID?)` (nil = `CGMainDisplayID()`).

- [ ] **Step 1: Write the failing tests** (`PointerTests`): slot 0 + `display: "LG"` with LG at (-1920, -180, 1920, 1080),
  pixel (960, 540) of 1920 × 1080 → (-959.5, 360.5) (pixel centre, as the main-display cases); unknown id → main bounds;
  `CursorLocator` with LG listed: point (-1000, 0) → slot 0, display "LG", x 920, y 180, w 1920, h 1080; LG not listed →
  slot -1; main still wins over a listed monitor.
- [ ] **Step 2: Run** `scripts/heavy.sh bash helper-mac/check.sh` → FAIL.
- [ ] **Step 3: Implement.** CAPTURE `main` + `display`: resolve the UUID with `ScreenCatalog.displayID(uuid:)`; if not
  online, capture main (log it). Exclude Minecraft exactly as today (`MirrorExclusion` retry). Pointer / scroll /
  work-begin on slot 0 use the resolved monitor's bounds, and the slot-0 click-through wait is unchanged. `monitors`
  for the cursor = monitors with a live `main`+`display` capture, and only when the HELLO had `"screens"` (an old mod
  keeps today's slot -1); the system cursor stays hidden on those monitors.
- [ ] **Step 4: Run** check.sh → PASS; build.sh compiles; manual.md: `capturemon` with an external monitor's UUID shows
  that monitor without Minecraft; `workbegin` slot 0 + display clicks on that monitor; CURSOR reports `display`.
- [ ] **Step 5: Commit** `git commit -m "feat(helper): mirror a chosen monitor and use it in world"` (files above).

### Task 6: Helper: Identify overlays

**Files:**
- Create: `helper-mac/Sources/OfficeHelper/IdentifyOverlay.swift` (AppKit)
- Modify: `Arrangement.swift` (`IdentifyLayout`), `Session.swift` (IDENTIFY), `ArrangementTests.swift`, `manual.md`,
  `devclient.py` (`identify`)

**Interfaces:**
- Pure: `IdentifyLayout.square(in bounds: CGRect) -> CGRect` (side = 28 % of the shorter side, rounded down, centred).
- `@MainActor final class IdentifyOverlay { func show(_ items: [(bounds: CGRect, label: String)], seconds: Double = 3) }`
  — a new call replaces the windows of the previous one.

- [ ] **Step 1: Write the failing test:** `square(in: (0, 0, 1440, 900))` = side 252 at (594, 324);
  `square(in: (-1920, -180, 1920, 1080))` = side 302 at (-1111, 209); portrait (0, 0, 1080, 1920) = side 302 at (389, 809).
- [ ] **Step 2: Run** check.sh → FAIL.
- [ ] **Step 3: Implement** one borderless `NSWindow` per label at `square` (converted from Quartz top-left to Cocoa
  coordinates via the main screen's height): `.screenSaver` level, `ignoresMouseEvents = true`, `[.canJoinAllSpaces,
  .fullScreenAuxiliary, .stationary]`, clear background, dark rounded square (0xE6101418, corner 12 %), white bold
  label at 70 % of the side; never activates the helper or steals focus. Unknown display ids are skipped.
- [ ] **Step 4: Run** check.sh → PASS; build.sh compiles; manual.md: `identify` shows A/B/1 for 3 s on each screen,
  clicks pass through, Minecraft keeps focus.
- [ ] **Step 5: Commit** `git commit -m "feat(helper): identify overlays"`.

### Task 7: Mod: `HologramSpec.mirrorDisplay` and `SetMirrorDisplay`

**Files:**
- Modify: `mod/src/main/java/local/carter/office/core/HologramSpec.java`, `core/Mount.java`,
  `hologram/HologramBlockEntity.java` (`readSpec`), `net/OfficePayloads.java`, `net/HologramActions.java`,
  `mod/src/client/java/local/carter/office/client/interact/CornerDrag.java`, `interact/MoveDrag.java`,
  `mod/src/gametest/java/local/carter/office/client/HologramClientGameTests.java:309`
- Test: `mod/src/test/java/local/carter/office/core/HologramSpecTest.java`, `core/MountTest.java`,
  `mod/src/test/java/local/carter/office/client/DragGestureTest.java`, `mod/src/gametest/java/local/carter/office/HologramGameTests.java`

**Interfaces:**
- Produces: `HologramSpec(..., float yaw, @Nullable String mirrorDisplay)` (12th component; the 11-arg constructor stays
  for fresh specs only, meaning null); `HologramSpec.withMirrorDisplay(@Nullable String)`;
  `OfficePayloads.MAX_MONITOR_ID = 64`; `record SetMirrorDisplay(BlockPos pos, @Nullable String display)` (c2s,
  `office:set_mirror_display`). Every **copy** of an existing spec uses the 12-arg form.

- [ ] **Step 1: Write the failing tests.** `HologramSpecTest.mirrorDisplayRoundTripsAndIsOptional` (toMap has no
  `mirrorDisplay` key when null; with "LG-UUID" fromMap(toMap) equals); `HologramSpecTest.copiesKeepMirrorDisplay`
  (withWidth, withMode, withPrivacy); `MountTest.remountKeepsMirrorDisplay`; `DragGestureTest.dragsKeepMirrorDisplay`
  (corner drag preview and move drag result). Gametests: `setMirrorDisplayStoresIt` (owner sets "9B2F…" → stored and
  synced in the update tag; null clears; 65-char id and an id with a control char rejected; non-owner rejected);
  `updateAndMoveKeepMirrorDisplay` (UpdateHologram shape change, MoveHologram, ResizeHologram keep it);
  `mirrorDisplaySurvivesSaveLoad` (`writeSpec` → `readSpec`).
- [ ] **Step 2: Run** `scripts/heavy.sh ./gradlew build test runGametest --no-daemon` → FAIL.
- [ ] **Step 3: Implement.** `readSpec` reads `in.getString("mirrorDisplay")`; `HologramActions.apply` handles
  `SetMirrorDisplay` through `edit(player, pos, s -> s.withMirrorDisplay(...))` with the id check (1–64 printable
  ASCII); register the receiver and the c2s type. Clients send it only when `ClientPlayNetworking.canSend(SetMirrorDisplay.TYPE)`.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `git commit -m "feat(hologram): mirror monitor in the spec"`.

### Task 8: Mod: the player's screens (model, platforms, helper state)

**Files:**
- Create: `mod/src/client/java/local/carter/office/client/screens/{Screen,ScreenList,Origin,MapLayout,SdlScreens}.java`
- Modify: `client/platform/Platform.java`, `MacPlatform.java`, `WindowsPlatform.java`, `Win32.java` (`monitors()`),
  `client/helper/MacHelper.java` (screens state), `client/helper/HelperConnection.java:280` (HELLO features)
- Test: `mod/src/test/java/local/carter/office/client/screens/{ScreenListTest,MapLayoutTest}.java`,
  `mod/src/test/java/local/carter/office/client/helper/MacHelperScreensTest.java`

**Interfaces:**
- Produces: `record Screen(String id, int slot, boolean main, @Nullable String name, int x, int y, int w, int h)`
  (`isMonitor()` = slot 0; `rect()` → `Platform.Rect`); `record ScreenList(List<Screen> screens, Abilities abilities)`
  with `Map<String, Character> letters()`, `@Nullable String resolveMirror(@Nullable String wanted)` (null when wanted is
  null, the main id or absent), `Screen main()`, `static ScreenList mainOnly()` (one 1920 × 1080 main, id "main", no
  abilities); `record Abilities(boolean live, boolean canMove, boolean canPickMirror, boolean canIdentifyScreens,
  @Nullable String hint)`; `ScreenList.current()` (test override if set, else `Platform.current().screens()`) and
  `ScreenList.setTestOverride(@Nullable ScreenList)` (client gametests); `record Origin(int slot, int x, int y)`; `MapLayout.fit(List<Platform.Rect> rects, int areaW,
  int areaH) -> Fit` with `Fit(double scale, int offX, int offY)`, `Platform.Rect toMap(Platform.Rect)`, `int[] toGlobal(int mx, int my)`.
- Platform: `default ScreenList screens()` (mainOnly), `default void refreshScreens() {}`, `default void
  onScreensChanged(Runnable r) {}`, `default boolean applyOrigins(List<Origin> o) { return false; }`, `default boolean
  identifyScreens(Map<String, String> labelsById) { return false; }`.
- MacHelper: `boolean supportsScreens()` (= `welcome().has("screens")`), `ScreenList screens()`, `void listDisplays()`,
  `boolean setOrigins(List<Origin>)`, `boolean identify(Map<String, String>)`, `@Nullable String resolveMirror(@Nullable String)`,
  `void onScreensChanged(Runnable)`.

- [ ] **Step 1: Write the failing tests.** `ScreenListTest.lettersByOrigin`: main (0, 0), LG (-1920, -180), Dell
  (1440, -1080), Side (1440, 0), Office 1 → A main, B LG, C Dell, D Side, no letter for the Office display;
  `resolveMirror`: null → null, main id → null, "LG" present → "LG", absent → null. `MapLayoutTest.fitsAndKeepsAspect`
  (main + one Office display in 160 × 64: all inside, aspect within 1 px, centred); `extremeLayoutsFit` (3 monitors incl.
  a portrait one at negative origins + 4 Office displays in 160 × 64: all inside, no overlaps, every box ≥ 12 × 8).
  `MacHelperScreensTest` (fake channel as in `MacHelperSourceTest`): `oldHelperGetsNoNewMessages` (Welcome without
  features → `listDisplays()`, `setOrigins`, `identify` send nothing and return false; abilities greyed with hint
  "Restart Minecraft to update the helper"); `screensHelperListsOnConnect` (Welcome with features → a `ListDisplays`
  is sent); `displaysBecomeTheScreenList` (a `Displays` message → `screens()` has those entries, `live` true,
  listeners run once); HELLO always carries `features: ["screens"]`.
- [ ] **Step 2: Run** `scripts/heavy.sh ./gradlew test --no-daemon --tests 'local.carter.office.client.screens.*' --tests 'local.carter.office.client.helper.MacHelperScreensTest'` → FAIL.
- [ ] **Step 3: Implement.** Mac `screens()`: helper list when `supportsScreens()` and a DISPLAYS arrived (abilities:
  move, mirror-pick, identify all true), else `SdlScreens.list()` (`SDL_GetDisplays`, `SDL_GetDisplayBounds`,
  `SDL_GetDisplayName`, `SDL_GetPrimaryDisplay`; client thread) with abilities false + hint. Windows: `Win32.monitors()`
  → `List<Screen>` (main from `MONITORINFOF_PRIMARY`), `canPickMirror = WebRtcNatives.supported()`, others false, hint
  null. `ScreenList` names stay client-side (never passed to any payload).
- [ ] **Step 4: Run** → PASS; `scripts/heavy.sh ./gradlew build --no-daemon` → BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `git commit -m "feat(screens): the player's monitors and Office displays on every platform"`.

### Task 9: Mod: Mirror captures and uses the chosen monitor

**Files:**
- Modify: `client/capture/Target.java` (`@Nullable String monitor`, 4-arg constructor kept), `client/capture/MacHelperSource.java`,
  `client/helper/MacHelper.java` (pointer / work-begin send resolved `display`; `workCursorDisplay`),
  `client/pointer/PointerPoint.java` (`@Nullable String display`, old constructors kept), `client/pointer/WorkCursor.java`,
  `client/pointer/PointerPort.java` (`default @Nullable String workCursorDisplay()`), `client/pointer/PointerClient.java`
  (`cursorOn`), `client/platform/MacPlatform.java`, `client/capture/JavaDesktopSource.java` (one shared feed per monitor),
  `client/platform/WindowsPlatform.java`
- Test: `mod/src/test/java/local/carter/office/client/capture/MacHelperSourceTest.java`,
  `.../client/pointer/{PointerPointTest,WorkCursorTest}.java`, `.../client/JavaDesktopSourceWindowsTest.java`

**Interfaces:**
- Consumes: `ScreenList.resolveMirror`, `MacHelper.resolveMirror`, `HologramSpec.mirrorDisplay()` (Tasks 7–8).
- Produces: `Target.of(spec)` sets `monitor = spec.mirrorDisplay()` for MIRROR only; `PointerPoint.of(spec, hit)` sets
  `display = spec.mirrorDisplay()` for MIRROR; `WorkCursor.on(HologramSpec spec, int[] cursor, @Nullable String
  cursorDisplay, @Nullable String mirrorShown) -> double[]`; `JavaDesktopSource.pickScreen(List<Long> ids, int
  primaryIndex, int chosenIndex) -> Long` (chosen when present, else primary, else first).

- [ ] **Step 1: Write the failing tests.** `MacHelperSourceTest`: `mirrorCapturesChosenMonitor` (screens helper,
  DISPLAYS lists LG, target monitor "LG" → `Capture` kind MAIN, display "LG"); `mirrorFallsBackAndReturns` (LG absent →
  display null; DISPLAYS with LG arrives → a new `Capture` with "LG"; LG gone again → back to null);
  `oldHelperMirrorIsMain` (no features → display null even with monitor set); `targetIncludesMonitorOnlyForMirror`.
  `PointerPointTest.mirrorCarriesItsMonitor`. `WorkCursorTest`: cursor slot 0 + display "LG" is drawn on the LG Mirror
  and not on a main Mirror; slot 0 without display is drawn on the main Mirror only. `MacHelperScreensTest.pointerDisplayResolved`
  (absent monitor → `WorkBegin.display()` null). `JavaDesktopSourceWindowsTest.pickScreenPrefersChosenMonitor`.
- [ ] **Step 2: Run** `scripts/heavy.sh ./gradlew test --no-daemon --tests 'local.carter.office.client.*'` → FAIL.
- [ ] **Step 3: Implement.** MacHelperSource resolves at CAPTURE time and re-issues CAPTURE from an
  `onScreensChanged` listener when the resolved id changed. Windows: the monitor id → EnumDisplayDevices index (webrtc
  screen id) via `Win32.displayIndex(String deviceName)`; feeds keyed by that index.
- [ ] **Step 4: Run** → PASS; `scripts/heavy.sh ./gradlew build test runGametest --no-daemon` → BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `git commit -m "feat(mirror): capture and work on the chosen monitor"`.

### Task 10: Mod: the display map in the settings screen

**Files:**
- Create: `client/screens/DisplaySnap.java`, `client/screens/DisplayMapModel.java` (pure state),
  `client/interact/DisplayMap.java` (the widget)
- Modify: `client/interact/HologramSettingsScreen.java:160-177` (map replaces the slot row; Mirror keeps its first note
  line, the second (platform) note shows as the map's status line when `status()` is null; non-owners get the text line
  of planner's call 11; the screen reads `ScreenList.current()`)
- Test: `mod/src/test/java/local/carter/office/client/screens/{DisplaySnapTest,DisplayMapModelTest}.java`,
  `mod/src/gametest/java/local/carter/office/client/HologramClientGameTests.java`

**Interfaces:**
- Consumes: Tasks 7–9 (`SetMirrorDisplay`, `ScreenList`, `MapLayout`, `Platform.applyOrigins`).
- Produces: `DisplaySnap.snap(Platform.Rect moving, List<Platform.Rect> fixed) -> int[] {x, y}`;
  `DisplayMapModel(ScreenList list, Mode mode, int displaySlot, @Nullable String mirrorDisplay, Shape shape)` with
  `click(String screenId) -> Choice` (`Choice.Slot(int)`, `Choice.Monitor(@Nullable String id)`, `Choice.None`),
  `drag(String id, int globalX, int globalY) -> boolean` (preview), `drop(String id)`, `List<Origin> pending()`,
  `boolean canApply()`, `@Nullable String status()`, `List<Screen> ghosts()` (missing slots), `void update(ScreenList)`.

- [ ] **Step 1: Write the failing tests.** `DisplaySnapTest`: V1–V6 under the touching rule; a drop at (1500, 40) of a
  1920 × 1080 display beside main → (1440, 0) (aligned within 6 %); a drop overlapping main → nearest touching spot,
  never overlapping; result always shares ≥ 10 % of an edge. `DisplayMapModelTest`: Extend — click Office 2 →
  `Slot(2)`, click a ghost 3 → `Slot(3)`, click a monitor → `None`; Mirror — click B → `Monitor("LG")`, click A →
  `Monitor(null)`, Office displays inert; drags only move Office displays in Extend mode with `canMove`; `canApply`
  false before a drop and when `canMove` is false; `update` with the moved display gone drops its pending origin;
  `status()` = "That screen isn't plugged in — showing A" when `mirrorDisplay` is absent, the abilities hint when
  greyed. Client gametest (CI only) `settingsMapFitsAtEveryGuiScale`: with a test override `ScreenList` (3 monitors +
  4 Office displays), open the screen in Extend and Mirror at GUI scales 1–4: the map and its Apply button lie inside
  the window and overlap no other widget; screenshot each.
- [ ] **Step 2: Run** `scripts/heavy.sh ./gradlew test --no-daemon --tests 'local.carter.office.client.screens.*'` → FAIL.
- [ ] **Step 3: Implement.** Map area = left column width × (body bottom − map top − 24); Apply (and in Task 11
  Identify) sit in one 20 px row under it. Drawing: monitors grey (fill 0xFF3A3A3A, edge 0xFF8A8A8A), big letter, name
  under it when the box is ≥ 40 px wide; Office displays hologram cyan (fill 0xFF16414B, edge 0xFF7FE3F5), big number;
  ghosts dashed cyan; the shown one gets the existing white `frame`. Drag threshold 3 px. Apply →
  `Platform.applyOrigins(model.pending())`; the next DISPLAYS clears pending. Choices send `UpdateHologram` (slot) or
  `SetMirrorDisplay` (canSend-gated). The screen asks `refreshScreens()` on open and redraws on `onScreensChanged`.
  Greyed buttons carry the hint as tooltip and as `status()`.
- [ ] **Step 4: Run** Step 2 → PASS; `scripts/heavy.sh ./gradlew build test runGametest --no-daemon` → BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `git commit -m "feat(settings): display map with drag, Apply and Mirror's monitor"`.

### Task 11: Identify (real screens and holograms) + handbook line

**Files:**
- Create: `client/screens/IdentifyLabels.java`, `client/screens/IdentifyClient.java`
- Modify: `net/OfficePayloads.java` (`IdentifyHolograms` c2s, `IdentifyShown` s2c), `net/HologramActions.java`
  (relay, privacy filter, 1000 ms limit, test seam `setIdentifyBroadcast`), `client/interact/DisplayMap.java` (Identify
  button), `client/render/HologramRenderer.java` + `HologramRenderState.java` (`@Nullable String identify`),
  `client/OfficeClient.java` (receiver), `hologram/StarterKit.java` (Settings page line)
- Test: `mod/src/test/java/local/carter/office/client/screens/{IdentifyLabelsTest,IdentifyClientTest}.java`,
  `mod/src/gametest/java/local/carter/office/HologramGameTests.java`, client gametest in `HologramClientGameTests.java`

**Interfaces:**
- Produces: `OfficePayloads.HologramLabel(BlockPos pos, String label)`; `IdentifyHolograms(List<HologramLabel> labels)`
  (≤ 32, `office:identify_holograms`), `IdentifyShown(List<HologramLabel> labels)` (`office:identify_shown`); `IdentifyLabels.forHolograms(List<HologramBlockEntity> mine, ScreenList list) ->
  Map<BlockPos, String>` and `IdentifyLabels.forScreens(ScreenList) -> Map<String, String>` (monitors → letters,
  Office displays → slot); `IdentifyClient.show(Map<BlockPos, String>, long nowMs)`, `@Nullable String labelAt(BlockPos, long nowMs)`
  (expires after 3000 ms).

- [ ] **Step 1: Write the failing tests.** `IdentifyLabelsTest`: Extend slot 2 → "2"; Mirror with LG present → "B";
  Mirror with LG absent → "A"; Window → no entry; `forScreens` gives A/B/C and 1–4. `IdentifyClientTest`: shown at t,
  present at t + 2999, gone at t + 3000; a second show replaces the first. Gametests:
  `identifyRelaysToViewersWhoMaySee` (owner + two mock viewers via the broadcast seam: Public → both; Private → none;
  Show-to viewer 1 → only viewer 1); `identifyRejectsBadInput` (label "AB", "LG", "5" dropped; a non-owned pos dropped;
  a second call within 1000 ms ignored); `handbookSettingsMentionsIdentify` (Settings page contains "Identify", every
  page ≤ 14 lines). Client gametest (CI only): press Identify on an owned Extend hologram → the renderer's state has
  identify "1" for 3 s; screenshot.
- [ ] **Step 2: Run** `scripts/heavy.sh ./gradlew build test runGametest --no-daemon` → FAIL.
- [ ] **Step 3: Implement.** Identify button: `IdentifyClient.show(local labels)`; send `IdentifyHolograms` when
  `canSend`; `Platform.identifyScreens(IdentifyLabels.forScreens(list))` when `canIdentifyScreens`. Enabled on Windows /
  NoPlatform (holograms only); greyed on a Mac without a screens helper. Renderer: when `labelAt` is non-null draw, over
  the content, a PANEL-textured square tinted 0xE6101418 with side 28 % of the hologram's shorter side and the label
  through the existing `text()` at 70 % of that side, white. Handbook Settings page: add "Your screens show as a map:
  drag, Apply, Identify." keeping ≤ 14 lines.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `git commit -m "feat(identify): numbers on screens and holograms"`.

**Final checkpoint:** `scripts/heavy.sh bash helper-mac/check.sh`, `scripts/heavy.sh ./gradlew build test runGametest
--no-daemon` → all green, `git status` clean (no `.superpowers/`, `test-reports/`, fixtures committed); the controller
pushes `feat/v1` and waits for CI (client GameTests, helper build, fixture diff).
