# Minecraft Office: friend setup

Welcome! This is our private Minecraft server where you can put **hologram monitors** in the world that show your
**real computer**. Setup takes about 15 minutes. Stuck? Message Carter.

**Jump to:** [What you need](#1-what-you-need) · [Prism](#2-install-prism-launcher) · [Modpack](#3-add-the-modpack) ·
[Memory](#4-memory) · [Join](#5-join-the-server) · [Your screens](#6-your-screens) ·
[Holograms](#7-using-holograms) · [Voice, waystones, claims](#8-voice-chat-waystones-and-claims) ·
[Magic](#9-magic-in-10-minutes) · [Problems](#10-something-wrong)

---

## 1. What you need

- A **Minecraft: Java Edition** account (the Microsoft account you bought it with).
- About **6 GB** of free disk space.
- One of these computers:
  - **Mac** with Apple Silicon (M1 or newer) running **macOS 14 or newer**.
  - **Windows 10 or 11**, 64-bit.
- Your Minecraft username, sent to **Carter**. The server is private and only lets in people on the whitelist.

Other computers might work, but we have not tested them.

## 2. Install Prism Launcher

Prism is a free Minecraft launcher. It sets up the right Java by itself, so you do not install Java.

1. Go to <https://prismlauncher.org/download> and download the version for your computer.
2. Install it (Mac: drag Prism into Applications. Windows: run the installer).
3. Open Prism and **sign in with your Microsoft account** (the one with Minecraft Java Edition).

<!-- screenshot: Prism download page with the Mac and Windows buttons -->
<!-- screenshot: Prism account sign-in -->

## 3. Add the modpack

The pack is all the mods we use. It **updates itself every time you press Play**, so you never reinstall.

**Easiest way (recommended):**

1. Download **Minecraft-Office.zip** from the latest release:
   <https://github.com/carterbaileybusiness-gif/minecraft-office/releases/latest/download/Minecraft-Office.zip>
   (Do not unzip it. Your browser may call it "unsafe" because it is a zip from GitHub; it is fine.)
2. In Prism click **Add Instance**.
3. Pick **Import** on the left.
4. Click **Browse** and choose the **Minecraft-Office.zip** you downloaded, then press **OK**.
5. Wait for it to finish. The instance **Minecraft Office** appears in your list.

<!-- screenshot: Add Instance, Import tab with Minecraft-Office.zip chosen -->

The first launch downloads all the mods, so it takes a few minutes. A small window shows the progress. It may ask
which optional mods you want: leave **Iris** and **Distant Horizons** off unless your computer has 16 GB of RAM.
After the first launch, starting is quick, and every time you press **Play** it quietly fetches any new mods first.

**Alternative: one-click `.mrpack` file.** Download
<https://github.com/carterbaileybusiness-gif/minecraft-office/releases/latest/download/Minecraft-Office.mrpack>,
then Add Instance, Import, and choose the downloaded file. This works, but it does **not** update itself. When Carter
says there is a new version, download the new `.mrpack` and import it again.

<details>
<summary>How the auto-update works (you can skip this)</summary>

The instance runs a small tool, `packwiz-installer-bootstrap.jar`, just before Minecraft starts. In Prism it is set
under **Edit Instance, Settings, Custom commands** (turn on **Custom commands**), in the **Pre-launch command** box:

```
"$INST_JAVA" -jar packwiz-installer-bootstrap.jar https://carterbaileybusiness-gif.github.io/minecraft-office/pack/pack.toml
```

The jar lives in the instance's Minecraft folder (next to `options.txt`). To set it up by hand: make a Prism
instance for **Minecraft 26.3** with **Fabric** (loader 0.20.1), put the jar in that folder, and paste the line above.
</details>

## 4. Memory

Minecraft needs memory (RAM) to run the mods.

The imported instance is already set to **4096 MB** (4 GB). You only need to change it on a small computer:

1. In Prism, right-click the instance, **Edit**, then **Settings**, then **Java**.
2. Check **Memory** is on and **Maximum memory allocation** says **4096 MB**.
3. If your computer has only **8 GB of RAM**, use **3072 MB** (3 GB) instead and keep other big apps closed.

Not sure how much RAM you have? Mac: Apple menu, About This Mac. Windows: Settings, System, About.

**On an 8 GB computer, leave the optional mods off:** Iris (shaders) and Distant Horizons. They are off by default.
With 16 GB or more you can turn them on in the instance's **Mods** tab (Prism shows a checkbox).

<!-- screenshot: Prism Settings, Java tab, memory set to 4096 -->

## 5. Join the server

1. Double-click your instance to start Minecraft. The first start is slow. That is normal.
2. Click **Multiplayer**, then **Add Server**.
3. Server address: `TODO(controller): server address`
4. Join. If it says you are **not whitelisted**, send Carter your exact Minecraft username.

If you see a red "mod mismatch" or "missing registry" screen, take a photo and send it to Carter.

## 6. Your screens

Holograms need a small helper to see your screen. **You do not install it yourself.**

<a id="mac"></a>
### Mac

The first time one of your holograms is near you, the mod asks **"Set up your screens?"**. Click **Yes** and it
downloads the small helper (OfficeHelper, a few MB, from this project's GitHub page) and starts it. Clicked
**Not now**? Click **[Set up screens]** in chat when you're ready. Then macOS asks for two permissions. You need both.

1. **Screen Recording.** macOS pops up a message about "OfficeHelper". Click **Open System Settings** and switch
   **OfficeHelper** on:
   - macOS 15 and macOS 26: **System Settings, Privacy & Security, Screen & System Audio Recording**.
   - macOS 14: **System Settings, Privacy & Security, Screen Recording**.
2. **Accessibility.** macOS asks "OfficeHelper would like to control this computer" when the helper starts (or
   click **[Set up screens]** in chat to be asked again). Click **Open System Settings** and switch **OfficeHelper**
   on in **System Settings, Privacy & Security, Accessibility**. This lets your mouse and keyboard work inside a
   hologram, and double-tap Esc bring you back from your screen. The game notices within a couple of seconds; no
   restart needed.
3. Normally nothing else shows up. But if macOS ever says OfficeHelper cannot be opened because it is from an
   unidentified developer, open **System Settings, Privacy & Security**, scroll to the Security section, and click
   **Open Anyway** next to OfficeHelper. (We do not pay Apple for signing.)
4. **Click [Check again] in chat** (or leave the world and join again). macOS only applies new permissions to programs
   that start afterwards, and this restarts the helper.

<!-- screenshot: macOS prompt asking to let OfficeHelper record the screen -->
<!-- screenshot: Privacy & Security, Screen & System Audio Recording, OfficeHelper switched on -->
<!-- screenshot: Privacy & Security, Accessibility, OfficeHelper switched on -->
<!-- screenshot: Open Anyway button in Privacy & Security -->

**If a hologram says it needs the helper, or macOS asks again:**

- Open the two Privacy & Security pages above and check **OfficeHelper** is switched **on** in both. If it already
  is, switch it off and on again.
- Then quit and restart Minecraft.
- Still stuck? In Terminal, run these two lines, then grant the permissions again as above:
  `tccutil reset ScreenCapture local.carter.office-helper` and
  `tccutil reset Accessibility local.carter.office-helper`

Good to know on a Mac:
- **Extend** (every new hologram) gives it its own brand new virtual screen, like plugging in another monitor. It
  shows up as "Office Display 1" (2, 3...) in System Settings, Displays, and goes away when you quit Minecraft.
  **Window** mode shows one app window instead, and **Mirror** (optional, view-only) shows your Mac's screen,
  everything except Minecraft itself.
- Protected video (Netflix and similar) shows black, and password boxes may not accept typing.

<a id="windows"></a>
### Windows (beta)

- **Nothing to install.** Windows holograms show your screen (**Mirror**, everything except Minecraft; needs
  Windows 10 version 2004 or newer) or one app window you pick (**Window**). Extend (a separate virtual screen)
  comes later, so on Windows every new hologram mirrors.
- If Windows **SmartScreen** shows a blue "Windows protected your PC" box for anything, click **More info**, then
  **Run anyway**.
- It is a beta. After you first join, please run the [self-test](#10-something-wrong) and send Carter the report,
  so we can fix problems early.

<!-- screenshot: Windows SmartScreen with More info and Run anyway -->

## 7. Using holograms

A hologram is a glowing floating screen above a small **emitter**. Emitters are free, nobody needs to craft them.

1. **Get emitters.** Your first-join starter kit has 4. Need more? Type `/emitter` in chat (limit: 8 holograms each).
2. **Place it** like a block, on the floor, a desk, anywhere. The hologram appears above it. It can float mid-air.
   On a Mac each hologram is a new screen (**Extend**), like plugging in monitor #2, #3. On Windows it is your screen
   (**Mirror**): exactly what you see on your computer, minus Minecraft.
3. **Resize.** Hold the emitter, point at a corner of the hologram, then **hold right-click and drag**. A cyan outline
   shows the new size. Let go to set it. Anything from tiny to wall-sized.
4. **Settings.** Right-click the emitter itself:
   - **Shape:** widescreen 16:9, ultrawide 21:9, or tall 9:16.
   - **Shows:** **Mirror** (your screen, minus Minecraft), **Extend** (a new virtual screen, Mac only for now) or
     **Window** (one app window you pick). Switch any hologram any time.
   - **Display** (Extend only): which of your virtual displays this hologram is.
   - **Privacy:** **Public** (anyone nearby sees it, view-only), **Private** (only you; everyone else sees a frosted
     "Private" panel), or **Show to...** (pick specific players).
   - **Remove** to pick it back up.
5. **Move it.** Hold **Shift**, then right-click the hologram and drag. **Scroll** while dragging to rotate it.
6. **Work on it.** **Click your hologram.** The camera zooms in until it fills your view, and your real mouse and
   keyboard now control your real computer. On a **Mirror** hologram, Minecraft then steps aside (hidden on Mac,
   minimized on Windows) and you are simply on your normal desktop.
7. **Come back.** **Double-tap Esc**, or just click back into Minecraft (Dock, Cmd-Tab, taskbar). If that does not
   work, use the backup:
   - Mac: **Ctrl + Option + Cmd + Esc**
   - Windows: **Ctrl + Alt + Shift + Esc**

Good to know:
- Only **you** can resize, change, or remove your holograms. Emitters survive explosions.
- Friends see your public holograms live but a bit smaller and slower (about 10 frames a second). You always see
  your own at full speed. When you are offline your holograms show a dim "offline" glow.
- **A Public (or Show to...) Mirror shows those players your whole screen** (the settings say so too). Make it **Private** or
  **Show to...** when you have something personal open.
- **Privacy promise:** a Private screen never leaves your computer. It is not hidden after being sent. It is never
  sent at all.
- Screens only stream to players close by (about 48 blocks), so an empty office costs nothing.

<!-- screenshot: placed hologram with cyan corner drag outline -->
<!-- screenshot: emitter settings screen -->

## 8. Voice chat, waystones and claims

- **Voice chat:** press **V** to open the voice chat menu. The first time you press it, a short setup asks you to
  pick your microphone and test it. Allow the microphone if your computer asks. Nearby friends hear you (about 48
  blocks). Voice chat starts in **push-to-talk** mode, but no key is set for it yet. In the setup either pick
  **voice activation** (your mic opens when you speak), or choose a key for push-to-talk under Options, Controls,
  Key Binds, Voice Chat, **Push To Talk**. Handy keys: **M** mutes your mic, **N** turns voice chat off, **H** hides
  the little voice icons.
- **Waystones:** a Warp Stone (4 amethyst shards, 4 ender pearls, 1 gold ingot) turns stone bricks into a waystone.
  Waystones you have visited let you teleport between them. Some sit in the wild too.
- **Claims** (protect your office): press **'** (apostrophe) and claim the chunks around your office. Friends you add
  to your party can still build there.

## 9. Magic in 10 minutes

Magic is normal survival, no commands. Hover over any item and press **R** to see how it is made (JEI).

1. **Wand:** 1 coal + 1 stick (coal top-right, stick bottom-left). Fire wand = gunpowder + gold ingot. Hold it and
   right-click to cast.
2. **Runes** (like arrows for spells): 1 stone + an amethyst shard (Arcane), gunpowder or coal (Fire), or a snowball
   (Frost) in the crafting grid gives 2 runes.
3. **Spell Binding Table:** 1 book, 2 amethyst shards, 1 gold ingot, 3 polished diorite. Put bookshelves around it.
4. **First spell book:** put a plain book in the table and choose Arcane, Fire or Frost (costs 1 XP level).
5. **Equip the book** (it has its own slot), hold a wand, and cast.
6. **Dress the part:** wizard robes (lapis + wool) make spells stronger. Wizard Merchants in villages sell wands,
   staves and robes for emeralds.

Your starter kit also has an **Office Handbook** book with the basics.

## 10. Something wrong?

1. Start Minecraft and press **Esc** to open the pause menu.
2. Click **Screen self-test** and wait a few seconds.
3. Click **Copy report**, then paste it into a message to **Carter**.

The report says what computer you have and which step failed. It has **no pictures of your screen** and no window
titles, only app names. **Open folder** shows the saved report files if copying does not work.

Quick fixes:
- Hologram says it needs the helper (Mac): redo the [Mac permissions](#mac) and restart Minecraft.
- Game feels slow: close other apps, and keep Iris and Distant Horizons off.
- Cannot join: check you sent Carter your username and that the server address has no typos.

<!-- screenshot: Esc menu with the Screen self-test button -->
