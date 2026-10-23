# Minecraft Office Server — design

Date: 2026-10-23. Owner: Carter. Status: draft for approval.

Marks used below: **(Carter)** = Carter decided it. **(my call)** = Claude's default, Carter may override.
Everything unmarked is description or research fact.

## 1. Intent

A private, modded Minecraft Java **survival** server for Carter and about four friends (five players). Each person builds
their own office in the world and fills it with **hologram monitors that show their real computer**, live. You can walk
up to a monitor, click it, zoom in, and actually work on your computer from inside Minecraft. Friends who walk into your
office see your monitors live, unless you mark a monitor private.

The world around the offices is normal survival, made more interesting with landscape mods and a magic mod that feels
like part of Minecraft, all present on the first day.

Why: a shared place to hang out and work together that is more fun than a video call, built on the "Mac as Minecraft"
experience Carter already built for himself in Home Base.

## 2. Players and machines

- Five players **(Carter)**: a mix of Macs and Windows PCs, all on Java Edition **(Carter)**.
- Carter's Mac is an 8 GB M1. It must not host the server, and the client must stay smooth on it.
- Friends set up their own machines, so install must be simple and well explained.

## 3. Goals, non-goals, anti-goals

Goals
- Placeable, resizable hologram monitors showing the owner's real desktop, visible in 3D to everyone nearby.
- Click a monitor → camera zooms in until it fills the view → full mouse/keyboard use of the real computer → exit zooms back out **(Carter)**.
- Each monitor is an **extended** display by default (like having 2–4 monitors); mirroring the main screen is an option **(Carter)**.
- Per-monitor privacy: friends see it unless it's private; the owner can show it when they want **(Carter)**.
- Normal survival world with landscape mods and a magic mod on day one **(Carter)**.
- Easy to share: friends can get the mods, the helper app, and onto the server without technical help.

Non-goals
- Friends controlling someone else's computer. Viewing others' screens is view-only **(my call)**.
- Bedrock / console / phone players.
- A public server or Modrinth/CurseForge listing.

Anti-goals — Carter said these would make it a failure even if it technically works:
- Screens that don't look good.
- Not being able to click into a screen and work from it.
- Not being able to choose the size of a screen.
- Magic that feels off and bolted on rather than part of Minecraft (he wants it discoverable the vanilla way, e.g. its
  own book or workbench, not commands or wiki-reading).

## 4. Platform

- **Minecraft 26.3, Fabric** **(my call; Carter delegated)**. Reason: the 1.21.11 version Home Base uses was skipped by
  most mod makers — no major magic mod exists for it. On 26.3 (verified on Modrinth 2026-10-23) Fabric API 0.165.0,
  Wizards, Spell Engine, Tectonic, Terralith, Sodium, Iris, Simple Voice Chat, Distant Horizons and the rest of the pack
  below all have builds. Cost: Home Base's screen code is ported from 1.21.11 rather than reused as-is.
- Home Base (`~/Documents/Mac UI Makeover/.worktrees/minecraft-native-proof`) stays as it is; this is a separate project
  that borrows its code. Nothing here changes Home Base.
- New project folder: `~/Documents/Minecraft Office/` (this document's home), its own git repo.

## 5. The monitors

### 5.1 Look **(Carter)**
**Floating hologram**: no frame — a glowing, slightly translucent panel with a cyan edge glow and faint scanlines,
hovering over a small emitter base. It can float mid-air, not only on walls. The desktop inside must stay crisp and
readable; the hologram effect must never make text hard to read when you're close or zoomed in **(my call)**.

### 5.2 Getting and placing
- A **Hologram Emitter** item, free from the start — every player gets some on first join and can get more without
  crafting **(Carter: free; delivery method my call)**.
- Place it like a block; the hologram appears above/in front of it at a default size.

### 5.3 Sizing **(Carter)**
**Grab a corner and drag**: point at a hologram's corner, click-and-drag, a live outline shows the new size, release to
set. Any size from small to wall-sized, any position including mid-air. Bigger hologram = same desktop, just larger.

### 5.4 Settings **(my call)**
Right-click the emitter for a small vanilla-styled menu:
- **Shape**: widescreen 16:9, ultrawide 21:9, vertical 9:16. The shape sets the virtual display's resolution.
- **Mode**: Extend (new virtual display, default) or Window (shows one chosen app window, like Home Base does today)
  **(my call)**. A plain "mirror main screen" mode is not offered: on a one-screen laptop it would show Minecraft inside
  Minecraft. Window mode is also the fallback if virtual displays break.
- **Display**: which of your virtual displays this hologram is.
- **Privacy**: Public (anyone nearby sees it, default) / Private (only you) / Show to… (pick specific online players).
- Pick up / remove.
Only the owner can resize, change settings or remove a hologram **(my call)**.

### 5.5 Working on a monitor
- Click a hologram you own → smooth camera zoom until it fills your view → you are using your real computer.
- While zoomed in: mouse and keyboard go to the computer, not to Minecraft. Your own view comes from your own machine
  (never the network), so it must feel like normal use: sharp, full frame rate, minimal lag.
- Exit back to the office with a dedicated gesture that doesn't collide with apps that use Esc — **double-tap Esc**
  **(my call)**, plus an always-available fallback shortcut.
- Friends see your avatar seated/working while you're zoomed in **(my call)**.

### 5.6 What friends see
- Anyone in range sees your public holograms live, view-only. Shared views may be lower resolution and frame rate
  (target ~10 fps) than your own; they must still look good at normal viewing distance **(my call)**.
- Private holograms show a frosted / "Private" panel to everyone but you **(my call)**.
- **Private screen contents never leave the owner's computer** — privacy is enforced at the source, not by hiding it on
  friends' clients **(my call; required for trust)**.
- Video streams only to players near enough to see the hologram, so idle offices cost nothing **(my call)**.
- When the owner is offline, their holograms show a dim "offline" standby glow **(my call)**.
- Sound **(my call)**: the owner hears their computer normally. Friends don't hear a shared screen's audio in v1;
  hologram-positioned audio is a later feature.

### 5.7 Virtual display lifecycle **(my call)**
- The helper creates a player's virtual displays when they join the server and removes them when they quit Minecraft;
  the OS then moves those windows to the main screen. On rejoin, displays are recreated with the same identity so the
  OS restores window positions.
- Windows get onto a hologram display with normal OS tools: drag across, or open apps while zoomed into that hologram.
  No custom "send window to hologram" feature in v1.

### 5.8 Mirror mode — default **(Carter, 2026-10-23 evening, after play-testing)**
"It should just be my screen… like when I plug my computer into any monitor, mirror at first, then I can choose extend."
- New holograms default to **Mirror**: the hologram shows the owner's main display **with Minecraft's own window excluded
  from the capture** (macOS ScreenCaptureKit excluding the Minecraft app; Windows `SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE)`
  on Minecraft's window), so there is no Minecraft-inside-Minecraft.
- Zooming into a Mirror hologram: camera zoom animation, then Minecraft steps aside (hidden/minimized) so the player uses their
  real desktop natively; double-tap Esc (global hotkey) brings Minecraft back and zooms out.
- Settings offer Mirror / Extend / Window. This supersedes the earlier "no mirror main screen" call in 5.4.

## 6. How the screens work (architecture)

Three parts **(my call; based on research)**:

1. **The mod** (Fabric 26.3, client + server). Hologram emitter block/entity, in-world rendering, corner-drag sizing,
   settings menu, zoom, input hand-off, privacy rules, and stream routing.
2. **A small helper app on each player's computer**, launched/talked to by the mod:
   - **Mac**: Swift, grown from Home Base's `Prototypes/MinecraftBridge`. Creates/removes virtual displays with the
     private `CGVirtualDisplay` API (proven at runtime on macOS 26; used by DeskPad/BetterDisplay), captures them with
     ScreenCaptureKit, moves the cursor onto a display when zoomed in. Talks to the mod over a local owner-only socket
     like Home Base's bridge protocol v2.
   - **Windows**: creates/removes virtual displays at runtime with **parsec-vdd** (MIT, signed driver, no reboot;
     pin the known-good driver version), captures with Windows.Graphics.Capture or Desktop Duplication, injects input
     with SendInput. Local named pipe to the mod.
3. **The server's role**: knows where every hologram is, who owns it, its privacy setting and who is in range; relays
   the shared (non-private) video to the players allowed and close enough to see it.

Video between players does **not** go through the normal Minecraft game connection (it would cause lag). Use a separate
channel; the builder chooses between (a) a relay on the game server sharing the game's port number over UDP, the way
Simple Voice Chat works on most hosts, or (b) WebRTC (webrtc-java) with STUN/TURN. Must work on the chosen host and
through friends' home routers without port-forwarding on their side.

Reusable from Home Base: live texture upload (`client/AppSurface.java`, incl. dirty-rect patches), the in-world
textured quad + mouse-ray mapping from commit `b2f8389`, ScreenCaptureKit capture, bridge IPC, and the persistent
self-signing setup that keeps macOS permissions across rebuilds.

**(Carter)** Reuse any code or assets from past projects that help (Home Base above; One Piece Minecraft's NeoForge
client/server payload patterns, GameTest setup, CI workflows and art tooling) and anything usable online, respecting
licenses (e.g. CC: Tweaked and MCEF as references only; Wizards/WaterMedia/StreamCraft not copied).

The mod must still work without the helper: players can build, see friends' shared screens, and get a clear in-game
prompt to install the helper for their own screens **(my call)**.

## 7. The world (day one)

All 26.3 Fabric builds verified on Modrinth 2026-10-23 unless noted. **(my call; Carter delegated)**

- **Landscape**: Tectonic + Terralith (+ Lithostitched) for big mountains, valleys and new biomes in a vanilla style;
  Incendium (Nether), Nullscape (End). Structures: Dungeons and Taverns, Towns and Towers. Keep structure density sane
  (Sparse Structures if it has a 26.3 build, otherwise config). Pre-generate the world with Chunky before launch.
- **Magic**: **Wizards + Spell Engine + Spell Power** (and required deps). It has its own **Spell Binding Table**
  workbench, spell books and wizard gear found and crafted in normal survival — matching Carter's "its own workbench or
  book" idea. Later: our own office/tech-themed spells as Spell Engine data, no new mod required.
- **Hanging out**: **Simple Voice Chat** (proximity voice — talk to whoever is in the office with you), **Waystones**
  (fast travel between friends' offices), **Open Parties and Claims** (protect each office from accidents).
- **Performance**: Sodium, Lithium, FerriteCore, ImmediatelyFast, Entity Culling, Dynamic FPS, Mod Menu. Optional per
  player: Iris shaders, Distant Horizons (off on Carter's 8 GB M1).
- Builder may swap any pick for a better-fitting 26.3 mod if it serves the same purpose and the anti-goals.

## 8. Sharing with friends

- **Modpack**: managed with packwiz, published from the project's GitHub repo; friends install **Prism Launcher** once and
  import the pack, which auto-updates on every launch. Also export a one-click `.mrpack`.
- **Helper installers**: one Mac download and one Windows installer, with a one-page friend setup guide (screenshots;
  macOS Screen Recording/Accessibility approvals; Windows driver admin prompt). The mod links to it in-game.
- Our own mod is distributed only through our pack/GitHub, never Modrinth (Modrinth bans mostly AI-made mods since
  Aug 2026).
- Server is whitelisted to the five players.
- **No paid signing (Carter).** The Mac helper is signed with one stable self-signed identity (so macOS permission
  grants survive updates) but not notarized: friends approve it once via System Settings → "Open Anyway". Windows
  SmartScreen: "More info → Run anyway". The setup guide shows both with screenshots.

## 9. Hosting **(Carter: cheap paid host unless a reliable free one exists)**

Oracle's free tier was judged not reliable enough (capacity errors, reclaim of idle instances, halved without notice in
June 2026). Use a cheap paid host: ≥6 GB RAM, custom Fabric 26.3 mods, file access (SFTP), UDP allowed on the game port
(needed for voice chat and the screen-video channel), automatic backups. Roughly $12–22/month. The builder confirms
the exact host against these criteria; Carter creates the account and pays.

## 10. Constraints

- Carter's 8 GB M1: one heavy process at a time; never run Gradle builds next to the game; never `pkill java`.
- macOS: Screen Recording + Accessibility permissions for the helper; rebuilds must keep a stable signature or grants
  are lost. Protected video (Netflix etc.) captures as black. Password fields (secure input) may block injected input.
- `CGVirtualDisplay` is private API — a macOS update could break it; Window mode is the fallback.
- Windows virtual displays need a one-time admin driver install.
- Carter prefers native macOS popups for questions and never wants other apps automated from Terminal.

## 11. Deadline, scope and testing

**Playable Monday 2026-10-26 (Carter).** That is three days, so v1 is cut to what can be solid by then **(my call)**:

Monday (v1)
- Server hosted and whitelisted, full day-one world (section 7), friends installing via Prism.
- Holograms for everyone: place, corner-drag size, settings, privacy, seeing friends' shared screens.
- **Mac**: own screens fully working — Extend (virtual displays) and Window mode, zoom in to work, sharing.
- **Windows**: own screens in **Window mode with no helper install**, captured from Java (webrtc-java's built-in
  desktop/window capture), zoom in to work, sharing. Clearly labelled beta.

After Monday (v2)
- Windows Extend mode (parsec-vdd virtual displays + helper installer), hologram audio, polish from playtests.

Testing without a Windows PC **(Carter: none available)**
- GitHub Actions Windows runners build every change and run the automated tests (packaging, natives load, protocol,
  capture against a test window where the runner allows it). They have no real GPU/display, so they can't prove
  the full experience.
- The mod ships an in-game **"Screen self-test"** button: it runs each step (capture, encode, send, receive, input)
  and writes a short report the friend can send back in one click (no screen contents in it). The first Windows
  friend to join runs it; fixes ship through the auto-updating pack.

Friends' machines **(Carter: unknown — some Macs, some Windows PCs)**: support macOS 14+ on Apple Silicon and
Windows 10/11 x64; anything else is best effort. The self-test reports OS/hardware so gaps show up early.

## 12. Left to the builder

Video channel choice (6), codecs and quality tiers, exact helper tech on Windows, emitter delivery method, hologram
shader details, zoom animation, the exact 26.3 mod versions, and the order of work within the Monday scope above.

## 13. Risks

- macOS private API breakage; Windows capture regressions on new builds.
- Input hand-off while zoomed is the hardest UX piece (focus, keybind conflicts, latency).
- Upload bandwidth when several friends share several monitors at once.
- Wizards and other 26.3 mods are ARR or third-party — they are installed from Modrinth via the pack, never
  redistributed or modified.
