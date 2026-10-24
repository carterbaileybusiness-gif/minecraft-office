# Minecraft Office: friend setup

Welcome! This is our private Minecraft server where you can put **hologram monitors** in the world that show your
**real computer**, and cast **magic** with a Grimoire. Setup takes about 15 minutes. Stuck? Message Carter.

**Jump to:** [What you need](#1-what-you-need) · [Prism](#2-install-prism-launcher) · [Modpack](#3-add-the-modpack) ·
[Memory](#4-memory) · [Join](#5-join-the-server) · [Your screens](#6-your-screens) ·
[Holograms](#7-using-holograms) · [Voice and claims](#8-voice-chat-and-claims) ·
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
3. Server address: **Carter will send you the server address privately.** <!-- never put the address in this file -->
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
   on in **System Settings, Privacy & Security, Accessibility**. This lets you click, drag, scroll and type on your
   holograms, and Esc bring you back to the game. The game notices within a couple of seconds; no restart needed.
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
- Like a laptop with monitors: your **first** hologram is **Mirror**, your Mac's own screen (everything except
  Minecraft itself), and you can use it right there. Every **further** hologram is **Extend**: a brand new virtual
  screen, like plugging in another monitor. It shows up as "Office Display 1" (2, 3...) in System Settings, Displays,
  and goes away when you quit Minecraft. **Window** mode shows one app window instead.
- Minecraft opens as a borderless window filling your screen (not macOS's separate full-screen space, where your
  other windows wouldn't be on the Mirror). Prefer a normal window? Launch with `-Doffice.borderless=false`.
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
2. **Place it** like a block. On the floor or a desk, the hologram floats above it, facing you. On a **wall**, it
   hangs flat on the wall like a painting. Under a **ceiling**, it hangs below. On a Mac your first hologram is your own screen (**Mirror**, minus Minecraft), and each further one is a new
   screen (**Extend**), like plugging monitors #2, #3 into a laptop. On Windows every hologram is your screen
   (**Mirror**).
3. **Resize.** Hold an emitter and look at your hologram: glowing corners appear. Point at a corner, then **hold
   right-click and drag**. A cyan outline shows the new size. Let go to set it. Or just **scroll** while looking at it.
   Anything from tiny to wall-sized.
4. **Settings.** Right-click the emitter itself:
   - **Shape:** widescreen 16:9, ultrawide 21:9, or tall 9:16.
   - **Shows:** **Mirror** (your screen, minus Minecraft), **Extend** (a new virtual screen, Mac only for now) or
     **Window** (one app window you pick). Switch any hologram any time.
   - **Display** (Extend only): which of your virtual displays this hologram is.
   - **Privacy:** **Public** (anyone nearby sees it, view-only), **Private** (only you; everyone else sees a frosted
     "Private" panel), or **Show to...** (pick specific players).
   - **Remove** to pick it back up.
5. **Move it.** Hold an emitter, hold **Shift**, then hold right-click on the hologram and drag. **Scroll** while
   dragging to turn it. Wall holograms slide along their wall instead.
6. **Use it (Mac): work mode.** With an empty hand (or anything but an emitter), **click** your hologram (your Mirror
   or any Extend screen). You are now working on your Mac: Minecraft shows "Working on your Mac — Esc (or ⌘-Tab) to
   return", the view stops turning, and **your mouse is your Mac's mouse**, starting where you clicked. The hologram
   shows the cursor right where it is.
   - Click, right-click, drag, select and type exactly as on a real Mac. Your keys go to the app.
   - **Move the cursor off the edge of your Mirror** and it continues onto the Extend screen next to it, like a laptop
     next to its monitor. So to move a window to another screen, drag its title bar over the edge.
   - Press **Esc** (the app never gets it) or **⌘-Tab** back to Minecraft to look around again. In a **password
     field** macOS hides Esc from everyone: Minecraft says so; press **⌘-Tab**.
   - Not in work mode: **scroll** while aiming at a screen scrolls the app there (your hotbar stays).
   - The Mirror needs Minecraft in a window: if you turned on Fullscreen in Options → Video, turn it off (Minecraft
     then fills the screen as a borderless window); chat reminds you.
   - Your windows stay where they are when you join or leave.
   - **The mini Dock** along the bottom of each Extend hologram (only you see it): click Chrome, Safari, Finder,
     Notes, Terminal, Claude, ChatGPT, Music or YouTube and it opens on that screen (only apps you have are shown).
     **All apps** (the grid) searches every app on your Mac.
   - While you work, Minecraft stays on top of your apps and lets your clicks through to them; after Esc it is a normal
     window again.
   - Your **Mirror starts Private** (only you see it: it shows your whole screen). Make it Public in its settings if
     you want friends to see it. Switching a screen to Mirror makes it Private too.
   - Friends see your public Extend screens **without your cursor** (it's drawn only on your own client).
   - While you work, **your view follows the cursor** around your screens, so the screen you're using stays in front of
     you. On a real second monitor it holds still.
   - **Walk while dragging:** hold the mouse button (dragging a window, say) and **W A S D / Space / Shift** walk you
     around, so you can carry a window to a screen further away. Let go of the button and those keys type into your app
     again. These are the keys in the W A S D / Space / Shift positions of your keyboard (on AZERTY: Z Q S D), whatever
     your movement keys are set to in Minecraft.
   - Place a second screen and it moves next to your Mirror on the side your Mac put that display (chat says which edge
     of the Mirror to move the cursor across).
7. **Work on it (Windows).** **Click your hologram.** The camera zooms in until it fills your view, and your real
   mouse and keyboard now control your real computer. On a **Mirror** hologram, Minecraft is minimized and you are
   simply on your normal desktop. **Double-tap Esc**, or click back into Minecraft (taskbar), to come back. Backup:
   **Ctrl + Alt + Shift + Esc**.

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

## 8. Voice chat and claims

- **Voice chat:** press **V** to open the voice chat menu. The first time you press it, a short setup asks you to
  pick your microphone and test it. Allow the microphone if your computer asks. Nearby friends hear you (about 48
  blocks). Voice chat starts in **push-to-talk** mode, but no key is set for it yet. In the setup either pick
  **voice activation** (your mic opens when you speak), or choose a key for push-to-talk under Options, Controls,
  Key Binds, Voice Chat, **Push To Talk**. Handy keys: **M** mutes your mic, **N** turns voice chat off, **H** hides
  the little voice icons.
- **Claims** (protect your office): press **'** (apostrophe) and claim the chunks around your office. Friends you add
  to your party can still build there. Claims also keep other players' big spells from changing your land.

## 9. Magic in 10 minutes

Magic is part of the Office mod, so there is nothing extra to install. Your starter kit's **Office Handbook** has
the short version too.

**1. Make your Grimoire.** Craft a **book + feather + iron ingot** (any order; it is in your recipe book). Hold it in
your **main hand** and right-click to open it.

**2. Choose your element.** The first time you open it, pick **Fire, Water, Wind, Lightning, Earth or Shadow**. This
choice is permanent. Changed your mind? Craft a **Rebinding Seal** (**nether star + book**) and you can choose again.

**3. Cast.** Put the Grimoire in your **off hand** and keep your **main hand empty**.
- **Tap right-click:** a quick cast.
- **Hold right-click:** the magic circle grows. Let go for a stronger cast.
- **Pick a spell:** hold **R** for the spell wheel, or **Shift + scroll**.
- Your **mana** shows where the XP bar normally is while you hold your Grimoire.

**4. Level up.** Casting levels up your Grimoire and unlocks more of your element's spells. **Ultimates** come at
level 7 and need **hand signs**: press the arrows shown on screen with the **arrow keys** or your **movement keys**
(W A S D), before the time runs out.

**5. Recall (go home).** Every element has it.
- **Shift + cast Recall:** marks this spot as home (anywhere, any dimension).
- **Cast Recall:** after 3 seconds you are home. Taking damage cancels it. 60 second cooldown, no mana.

**6. Travel to a friend.** Open your Grimoire, go to the **Friends** tab and click a friend. They accept in chat, and
3 seconds later you are standing next to them. It shares Recall's cooldown. Don't want visitors? Type
`/magic travel off` (and `/magic travel on` to allow them again).

**7. Your office is safe.** Big spells change the land for good (craters, walls and so on), but **never inside other
players' claims**. Claim your office (see [claims](#8-voice-chat-and-claims)) and it is protected.

**Every element has 5 spells.** They unlock as your grimoire levels up; the fifth is the ultimate (level 7, hand signs).

| Element | Spells (last one is the ultimate) |
|---|---|
| Fire | Fire Bolt, Fire Wall, Flame Dash, Fireball Barrage, **Meteor** |
| Water | Water Bullet, Tidal Push, Bubble Shield, Water Prison, **Water Dragon** |
| Wind | Gust, Blade Wind, Air Step, Tornado, **Wind Sphere** |
| Lightning | Spark, Chain Lightning, Blink, Thunder Spear, **Chidori Strike** |
| Earth | Stone Bullet, Earth Wall, Earth Spikes, Quake, **Mountain Fist** |
| Shadow | Shadow Bolt, Gravity Well, Shadow Step, Shadow Bind, **Black Hole** |

Updates arrive automatically when you press **Play**.

**Old handbook?** Type `/handbook` and it is replaced with the latest one.

## 10. Something wrong?

Run the self-test while you are on the server:

1. Press **Esc** to open the pause menu.
2. Click **Screen self-test** (bottom-left corner). You can also type `/officeselftest` in chat.
3. Optional: click **Test window** until it shows an app you have open (for example your browser). This lets the test
   capture one frame of that window to check that capture works. Leave it on **None (skip)** to skip that check. The
   test never captures a window you did not pick. On a Mac the list says **none yet (press Run test)** until the
   first run, because the helper only starts when you press **Run test**. Run once, then pick a window and click
   **Run again**.
4. Click **Run test**. A checklist fills in: ✓ worked, ✗ failed, – skipped. It takes up to 30 seconds and the game
   keeps running.
5. Click **Copy report**, then paste it into a message to **Carter**.

The report says what computer you have and which step failed. It has **no pictures of your screen**, no window
titles (only app names) and no server address. Each run is also saved as a text file: **Open folder** shows them
(`office-selftest` in your Minecraft folder) if copying does not work.

Quick fixes:
- Hologram says it needs the helper (Mac): redo the [Mac permissions](#mac) and restart Minecraft.
- Game feels slow: close other apps, and keep Iris and Distant Horizons off.
- Cannot join: check you sent Carter your username and that the server address has no typos.
- Your second monitor (Mac) jumps back right after your Office screens close: drag it again in System Settings →
  Displays. For a few seconds after an Office screen closes, Minecraft keeps your monitors where they were, which
  can undo a drag made in that moment.
- Fullscreen: use **Options → Video Settings → Fullscreen Mode: Borderless**, never "Exclusive" (on a Mac it can't be
  left with F11). If you ever get stuck: **⌘Q** quits (Alt+F4 on Windows). If it starts stuck again, open the instance
  folder (Prism: **Folder**) and set `exclusiveFullscreen:false` and `fullscreen:false` in `minecraft/options.txt`.

<!-- screenshot: Esc menu with the Screen self-test button -->
