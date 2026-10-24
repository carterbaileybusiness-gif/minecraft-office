# OfficeHelper manual checks (Carter's Mac)

These need a person: Screen Recording and Accessibility can only be granted by clicking in System Settings, and
zoom behaviour needs eyes and hands. Everything else is automated (`check.sh`) or was verified from the terminal
(see "Already verified" at the end).

All steps run from the repo root. `devclient.py` stands in for the mod; it prints every message it receives and
answers each FRAME/PATCH with READY. The helper quits when the client disconnects, so each step launches it again.

```
bash scripts/heavy.sh bash helper-mac/build.sh      # signed with "Minecraft Office Helper"
alias helper='open -g helper-mac/build/OfficeHelper.app && sleep 1'
alias dev='python3 -I helper-mac/Tools/devclient.py'
```

## 0. Grant permissions once

1. `helper; dev list sleep 2`
   - Expected: macOS asks to allow **OfficeHelper** to record the screen (Screen Recording). Click **Open System
     Settings** and turn **OfficeHelper** on in Privacy & Security › Screen & System Audio Recording.
2. `helper; dev create 1 1920 1080 capture 1 display 1 1280 5 zoomin 1 0 0 1440 900 sleep 1 zoomout`
   - Expected: macOS asks for Accessibility for OfficeHelper. Turn it on in Privacy & Security › Accessibility.
3. `helper; dev sleep 1` → first line `<- WELCOME {"accessibility": true, "features": ["screens"], "screenRecording": true, "version": 1}`.
4. Rebuild (`bash scripts/heavy.sh bash helper-mac/build.sh`) and repeat step 3.
   - Expected: still both `true` (the persistent signature keeps the grants).

If a grant shows on but WELCOME says `false`, reset only this app:
`tccutil reset ScreenCapture local.carter.office-helper` / `tccutil reset Accessibility local.carter.office-helper`,
then grant again.

## 1. Virtual display in System Settings

`helper; dev create 1 1920 1080 sleep 20` and open System Settings › Displays while it sleeps.
- Expected: a display named **Office Display 1** (1920 × 1080) next to the built-in display.
- When the client exits (after 20 s): the display disappears within 3 s and the helper quits.

## 2. Display capture (Screen Recording)

`helper; dev create 1 1920 1080 capture 1 display 1 1280 5 sleep 5`
- Expected: `<- FRAME surface 1 gen N 1280x720 (3686420 bytes)` first, then mostly `PATCH` lines (only changed
  regions) at up to 5 per second while something moves on Office Display 1 (drag a Finder window onto it during the
  sleep). The cursor is visible in captured frames.
- Wrong-display guard: the FRAME size is 1280 × 720 (from the 1920 × 1080 virtual display), never the built-in
  display's aspect (2560 × 1600 → 1280 × 800).

## 3. Window capture and "window closed"

1. Open TextEdit with a new document. `helper; dev list` → note the TextEdit `windowKey`
   (e.g. `com.apple.TextEdit|1234`).
2. `helper; dev capture 2 window com.apple.TextEdit|1234 1280 5 sleep 15` (quote the key in zsh:
   `'com.apple.TextEdit|1234'`). Type in TextEdit during the sleep.
   - Expected: FRAME then PATCH lines while typing.
3. Close the TextEdit window (don't save) before the sleep ends.
   - Expected: within about 1 s `<- ENDED {"reason": "window closed", "surface": 2}`.

## 4. Zoom into a display: cursor warp and clamp

`helper; dev create 1 1920 1080 capture 1 display 1 1280 5 sleep 1 zoomin 1 0 0 1440 900 sleep 10 zoomout sleep 1`
- Expected on `zoomin`: the pointer jumps off the built-in screen onto Office Display 1 (its centre). Moving the
  mouse hard toward the built-in display does not leave Office Display 1 (it is pulled back at the edge).
  Frames arrive faster (up to 30 per second) while zoomed.
- On `zoomout`: the pointer returns to where it was before the zoom and moves freely again.
- Note: Office Display 1 is not a physical screen, so the pointer is invisible while it is held there. It is always
  released when the client ends (the helper quits), so the longest it can stay trapped is the `sleep`. Without
  Accessibility the pointer is only moved there, never held (the exit hotkey could not fire).
- Safety release (secure input): in Terminal turn on **Terminal › Secure Keyboard Entry**, keep Terminal in front,
  and run the command again. Expected: the pointer jumps to Office Display 1 but is not held (it can be moved back
  at once), and `~/Library/Application Support/Minecraft Office/helper.log` shows
  `cursor clamp released: secure event input is on`. `zoomout` still returns the pointer to its starting place.
  Turn Secure Keyboard Entry off again afterwards.
- Safety release (Accessibility revoked): run the command, then during the sleep switch OfficeHelper off in
  Privacy & Security › Accessibility (use the keyboard or wait for the sleep if the pointer is held).
  Expected: within about 0.1 s the pointer is free; the log shows `cursor clamp released: Accessibility is not
  granted`. Switch it back on afterwards.

## 5. Exit hotkey (Accessibility)

`helper; dev create 1 1920 1080 capture 1 display 1 1280 5 sleep 1 zoomin 1 0 0 1440 900 sleep 15`
- Press **Esc** once: nothing happens (the app in front still gets that Esc).
- Press **Esc** twice quickly (within about a third of a second):
  `<- EXIT_ZOOM {}` appears and the pointer is released and returns to where it was. (With the real mod, Minecraft
  is also brought to the front; the dev client is not an app, so nothing is activated here.)
- Repeat the command and instead press **Control + Option + Command + Esc**: same result.
- Two slow presses (about a second apart) do not exit; holding Esc down (auto-repeat) does not exit.

## 6. Window placement on zoom (Accessibility)

1. Open a TextEdit window somewhere small; `helper; dev list` → note its key.
2. `helper; dev capture 2 window 'com.apple.TextEdit|1234' 1280 5 sleep 1 zoomin 2 100 100 1000 700 sleep 8 zoomout sleep 2`
   - Expected on `zoomin`: TextEdit comes to the front and its window moves/resizes to x 100, y 100, 1000 × 700
     points (top-left origin). A new FRAME (new generation) follows at the new size within about 1 s.
   - On `zoomout`: the window goes back to its original position and size.
3. Repeat and press Esc twice during the sleep instead of waiting for `zoomout`: `EXIT_ZOOM`, window restored.
4. Repeat and close the TextEdit window while zoomed: `ENDED {"reason":"window closed"}` then `EXIT_ZOOM`.

## 6b. Mirror: the main display minus Minecraft, used in-world (spec 5.10)

Best done with the mod (step 7): devclient's own process has no windows, so nothing is excluded by it.
1. `helper; dev capture 3 main 1920 5 sleep 2` → FRAME of the main display (≤ 1920 wide) with the cursor.
2. In game: your first hologram is a Mirror. It shows your desktop **without** the Minecraft window (no
   Minecraft-inside-Minecraft), and you use it like the Extend screens: Minecraft floats above your other windows and
   is click-through while you click, drag, scroll or type on the Mirror.
3. Automated, no Minecraft needed: `bash helper-mac/Tools/inputprobe.sh` (part M: the probe's own window stands in for
   Minecraft — borderless, floating, covering the main display — over a TextEdit window; a slot-0 CLICK with it
   click-through reaches TextEdit at the click point and types, without click-through it hits the stand-in, a head-turn
   drag selects text, a title-bar drag carries TextEdit from the main display onto Office Display 2, a scroll with a
   click-through moment scrolls it). Minecraft's side (the mod's real `MacWindow` on a real SDL3 window: borderless
   fill, floating level 3, click-through by the window server's own hit test, and back): `JAVA_HOME=<JDK 25>
   helper-mac/Tools/windowprobe/run.sh` after building the mod.

## 6c. In-world use (the crosshair is the mouse) and the mini Dock

Automated: `bash helper-mac/Tools/inputprobe.sh` (builds the probe, compares the delivery routes, then drives the signed
helper end to end: the Dock's app list, icons and OPEN_APP onto a display, a click into typing mode, Esc back, a
head-turn text selection, a window dragged from display 1 to display 2, scrolling, the Mirror (6b), quit). Windows never
move on join or leave any more. By hand with devclient:
`helper; dev create 1 1920 1080 apps icons 64 com.apple.Notes com.google.Chrome open 1 com.apple.TextEdit sleep 1 click 1 400 300 sleep 5`
→ the app list and icons arrive; TextEdit (open a document first) moves onto Office Display 1 filling it, gets clicked
(caret where you clicked) and the keyboard; press Esc → `TYPING_EXIT esc` and the terminal comes back to the front.
`open 1 https://www.youtube.com` opens YouTube in the default browser on that display.

## 6d. Display map: list and move Office displays, remembered arrangement ("screens")

devclient's HELLO lists `"screens"`, so the helper also pushes DISPLAYS after every display change (300 ms debounce).
1. `helper; dev create 1 1920 1080 displays`
   - Expected: WELCOME lists `"features": ["screens"]`; DISPLAYS lists the built-in display (`"main": true`,
     `"name": "MacBook"`, slot 0, 0,0 1440×900), any monitor (slot 0, short name such as "LG"), and Office Display 1
     (slot 1, no `name`) right of the main display (or left when a monitor is there). Ids are UUID strings.
2. `helper; dev create 1 1920 1080 origins 1 0 -1080 sleep 10`
   - Expected: DISPLAYS shows slot 1 at 0,-1080 and System Settings › Displays shows it above the built-in display;
     moving the cursor off the top of the built-in display carries it onto Office Display 1.
   - `~/Library/Application Support/Minecraft Office/display-arrangement.json` reads
     `{"layouts":{"<main uuid>":{"1":{"x":0,"y":-1080}}},"schema":1}`.
3. Quit (the client ends) and relaunch: `helper; dev create 1 1920 1080 displays` → slot 1 comes back at 0,-1080.
4. Refusals (nothing moves, ERROR bad_request then DISPLAYS unchanged): `origins 1 1440 900` ("must touch a
   screen", corner only) and `origins 1 1000 0` ("overlaps").
5. Unplug-style check: edit the saved spot to sit next to where a monitor is not (e.g. `{"x":5000,"y":0}`), relaunch,
   create 1 → automatic placement (right of the main display, or left when a monitor is on the right).
6. Finally `helper; dev create 1 1920 1080 origins 1 1440 0` to put it back next to the main display's right edge.

## 6e. Mirror a chosen monitor, used in world (needs an external monitor)

1. `helper; dev displays` → note the external monitor's `id` (UUID), e.g. `9B2F…`.
2. `helper; dev capturemon 3 <uuid> sleep 3` → FRAMEs of **that** monitor (its aspect), never the built-in display.
   (devclient's own process has no windows, so nothing is left out here.) With the mod and Minecraft's window on that
   monitor: the Mirror shows the monitor without the Minecraft window.
3. `helper; dev capturemon 3 <uuid> workmon <uuid> 960 540 sleep 8`
   - Expected: the real cursor jumps to the middle of that monitor and clicks there; while you move it on that
     monitor the system cursor stays hidden and CURSOR lines read `{"display": "<uuid>", "h": …, "slot": 0, …}` in
     that monitor's points; on the built-in display slot 0 without `display`; elsewhere slot -1 and the cursor shows.
4. Unplug the monitor during `capturemon 3 <uuid> sleep 20`: within about 1 s a new FRAME generation of the main
   display follows (no ENDED); plug it back → frames of the monitor again. An unknown UUID mirrors the main display
   (`helper.log`: "is not connected; mirroring the main display").

## 6f. Identify overlays

`helper; dev create 1 1920 1080 identify sleep 4`, with TextEdit (or Minecraft) in front.
- Expected: for 3 s a dark rounded square sits in the middle of every screen — **A** on the built-in display, **B**, C…
  on monitors, **1** on Office Display 1 (see it in System Settings › Displays' arrangement or through a capture:
  `capture 1 display 1 1280 5` shows the square) — its side 28 % of the screen's shorter side, a big white label.
- Clicks on a square go through to the window under it; the app in front keeps the keyboard (type during the 3 s); the
  helper never shows in the Dock or ⌘-Tab. A second `identify` within 3 s replaces the squares (no doubles).
- On a full-screen app's Space the squares show too.

## 7. With the mod (after Task 9)

Place an Extend hologram → "Office Display 1" appears in System Settings › Displays; quit Minecraft → it disappears
within 3 s and OfficeHelper is no longer running (`pgrep OfficeHelper` prints nothing).

## Already verified from the terminal (no permissions needed)

- CREATE_DISPLAY slot 1 → DISPLAY in ~0.4 s; `system_profiler SPDisplaysDataType` lists "Office Display 1"
  (1920 × 1080); slots 1–4 with all three shapes at once.
- REMOVE_DISPLAY → display offline in 0.07–0.12 s.
- Client disconnect → helper quits, displays gone. `kill -9` of the helper → displays gone within 0.5 s.
  SIGTERM → displays removed and helper quits.
- Wrong token → connection closed; slot 0 / 5 → `ERROR bad_slot`; 1366 × 768 → `ERROR bad_size`.
- No mod connection within 120 s → helper quits by itself.
