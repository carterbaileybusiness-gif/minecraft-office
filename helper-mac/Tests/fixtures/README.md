# OfficeHelper protocol fixtures (version 1)

Written by `helper-mac/check.sh` (ProtocolTests "golden fixtures written"). Each `.bin` file is one complete message
as it travels on the socket. The Java side (Task 9, `HelperProtocolTest`) decodes them byte-exactly; CI fails if a
Swift change alters them without the fixtures being committed.

## Wire format

- Envelope, big-endian: `u32 length` (= 1 + payload bytes) | `u8 type` | payload.
- Lengths over 64 MiB (67 108 864) are rejected. The helper reads mod → helper messages with a 64 KiB limit.
- Control payloads: UTF-8 JSON, compact (no spaces), keys sorted ascending (byte order), `/` not escaped,
  non-ASCII written as raw UTF-8, integers only. Messages without fields carry `{}`. Optional fields are omitted when
  absent. Decoders ignore unknown keys.
- FRAME / PATCH payloads are binary, all integers big-endian:
  - FRAME: `u32 surface, u64 generation, u32 width, u32 height`, then width × height × 4 RGBA bytes, rows top to bottom.
  - PATCH: `u32 surface, u64 generation, u32 width, u32 height, u32 count`, then count × `(u32 x, u32 y, u32 w, u32 h)`,
    then each rectangle's RGBA rows in rectangle order. 1 ≤ count ≤ 32; rectangles lie inside width × height.
    Apply a PATCH only on top of a FRAME/PATCH with the same surface and generation (the helper always sends a FRAME
    first after each new generation).

## Fixture values

| File | Type | Content |
|---|---|---|
| `hello.bin` | HELLO = 1 | `{"mcPid":4242,"token":"00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff","version":1}` |
| `list_windows.bin` | LIST_WINDOWS = 2 | `{}` |
| `create_display.bin` | CREATE_DISPLAY = 3 | `{"h":1080,"slot":1,"w":1920}` |
| `remove_display.bin` | REMOVE_DISPLAY = 4 | `{"slot":1}` |
| `capture_display.bin` | CAPTURE = 5 | `{"fps":5,"kind":"display","maxW":1280,"slot":1,"surface":7}` |
| `capture_window.bin` | CAPTURE = 5 | `{"fps":5,"kind":"window","maxW":1280,"surface":8,"windowKey":"com.apple.Notes\|4321"}` |
| `capture_main.bin` | CAPTURE = 5 | `{"fps":5,"kind":"main","maxW":1920,"surface":9}` (Mirror: no `slot`, no `windowKey`) |
| `stop.bin` | STOP = 6 | `{"surface":7}` |
| `ready.bin` | READY = 7 | `{"surface":7}` |
| `zoom_in.bin` | ZOOM_IN = 8 | `{"mcWindowFrame":{"h":875,"w":1440,"x":0,"y":25},"surface":7}` |
| `zoom_out.bin` | ZOOM_OUT = 9 | `{}` |
| `request_permissions.bin` | REQUEST_PERMISSIONS = 10 | `{}` |
| `pointer_down.bin` | POINTER_DOWN = 11 | `{"button":"left","slot":1,"x":960,"y":540}` |
| `pointer_move.bin` | POINTER_MOVE = 12 | `{"slot":2,"x":10,"y":20}` (no `button`) |
| `pointer_up.bin` | POINTER_UP = 13 | `{"button":"left","slot":2,"x":10,"y":20}` |
| `scroll.bin` | SCROLL = 14 | `{"dx":0,"dy":-120,"slot":1,"x":100,"y":200}` |
| `click.bin` | CLICK = 15 | `{"button":"right","slot":1,"x":300,"y":400}` |
| `exit_typing.bin` | EXIT_TYPING = 16 | `{}` |
| `pointer_down_main.bin` | POINTER_DOWN = 11 | `{"button":"left","h":1080,"slot":0,"w":1920,"x":960,"y":540}` (Mirror: the main display) |
| `click_main.bin` | CLICK = 15 | `{"button":"left","h":1080,"slot":0,"w":1920,"x":100,"y":50}` |
| `scroll_main.bin` | SCROLL = 14 | `{"dx":0,"dy":-48,"h":1080,"slot":0,"w":1920,"x":100,"y":200}` |
| `list_apps.bin` | LIST_APPS = 19 | `{}` |
| `app_icons.bin` | APP_ICONS = 20 | `{"bundles":["com.apple.Notes","com.google.Chrome"],"size":64}` |
| `open_app.bin` | OPEN_APP = 21 | `{"bottom":96,"bundle":"com.apple.Notes","slot":2}` (`bottom`: the Dock's band, display pixels) |
| `open_app_url.bin` | OPEN_APP = 21 | `{"slot":1,"url":"https://www.youtube.com"}` |
| `work_begin.bin` | WORK_BEGIN = 22 | `{"button":"left","h":1080,"slot":0,"w":1920,"x":960,"y":540}` |
| `rate.bin` | RATE = 23 | `{"fps":60,"surface":7}` (a live capture's new rate, 1…60; no stream rebuild) |
| `welcome.bin` | WELCOME = 64 | `{"accessibility":false,"screenRecording":true,"version":1}` |
| `windows.bin` | WINDOWS = 65 | `[{"app":"Notes","h":600,"title":"Groceries — List","w":800,"windowKey":"com.apple.Notes\|4321"},{"app":"Safari","h":900,"title":"","w":1200,"windowKey":"com.apple.Safari\|99"}]` (the dash is U+2014, bytes `e2 80 94`) |
| `display.bin` | DISPLAY = 66 | `{"displayId":5,"h":1080,"slot":1,"w":1920}` |
| `display_side.bin` | DISPLAY = 66 | `{"displayId":5,"h":1080,"side":"left","slot":1,"w":1920,"x":-1920,"y":0}` (origin in the arrangement, and which side of the main display) |
| `frame.bin` | FRAME = 67 | surface 7, generation 3, 2 × 2; RGBA row 0: `ff 00 00 ff`, `00 ff 00 ff`; row 1: `00 00 ff ff`, `ff ff ff 80` |
| `patch.bin` | PATCH = 68 | surface 7, generation 3, 4 × 2, count 2: rect (0,0,1,1) pixels `0a 14 1e ff`; rect (2,1,2,1) pixels `28 32 3c ff`, `46 50 5a ff` |
| `ended.bin` | ENDED = 69 | `{"reason":"window closed","surface":8}` |
| `exit_zoom.bin` | EXIT_ZOOM = 70 | `{}` |
| `error.bin` | ERROR = 71 | `{"code":"bad_slot","message":"slot must be 1...4"}` |
| `typing_exit.bin` | TYPING_EXIT = 72 | `{"reason":"esc"}` |
| `drag_delta.bin` | DRAG_DELTA = 73 | `{"dx":12,"dy":-3}` |
| `drag_ended.bin` | DRAG_ENDED = 74 | `{"reason":"released"}` |
| `apps.bin` | APPS = 75 | `{"apps":[{"bundle":"com.google.Chrome","name":"Google Chrome"},{"bundle":"com.apple.Notes","name":"Notes"}]}` |
| `icons.bin` | ICONS = 76 | `{"icons":[{"bundle":"com.apple.Notes","png":"iVBORw0KGgo="}]}` (`png`: base64 PNG bytes) |
| `app_result.bin` | APP_RESULT = 77 | `{"message":"Notes is on screen 2","ok":true,"target":"com.apple.Notes"}` |
| `secure_input.bin` | SECURE_INPUT = 78 | `{"on":true}` |
| `cursor.bin` | CURSOR = 79 | `{"h":1080,"slot":1,"w":1920,"x":100,"y":200}` |
| `walk.bin` | WALK = 80 | `{"keys":["forward","jump"]}` (movement keys held for Minecraft while dragging; `[]` = stop) |

(`\|` above is a table escape; the bytes contain a plain `|`.)

Example, `frame.bin` (41 bytes):

```
00 00 00 25  43  00 00 00 07  00 00 00 00 00 00 00 03  00 00 00 02  00 00 00 02  ff 00 00 ff 00 ff 00 ff 00 00 ff ff ff ff ff 80
length=37    67  surface=7    generation=3             width=2      height=2     RGBA
```

## Semantics the fixtures don't show

- HELLO must be the first message, within 3 s, with the token from `helper.token` and version 1. Wrong token or a
  different first message → the socket is closed with no reply. Wrong version → `ERROR {"code":"version"}`, then closed.
  A second authenticated client while one is connected → `ERROR {"code":"busy"}`, then closed (the first stays).
- WELCOME is the handshake reply and is sent again whenever Screen Recording or Accessibility changes while
  connected (the helper re-checks every 2 s), so a grant reaches the mod without a restart. Treat every WELCOME
  after the first as a permission update.
- REQUEST_PERMISSIONS: the helper shows macOS's prompt for each missing permission (Accessibility via
  `AXIsProcessTrustedWithOptions` with the prompt option, which also lists OfficeHelper in System Settings →
  Accessibility; Screen Recording via `CGRequestScreenCaptureAccess`), then replies with a WELCOME. The helper also
  asks for Accessibility once by itself when the mod connects and it isn't trusted.
- CAPTURE lets the first frame go without READY; after that each FRAME/PATCH waits for `READY {surface}`.
- CAPTURE kinds: `display` (a virtual display, needs `slot`), `window` (needs `windowKey`), `main` (Mirror: the main
  display, `CGMainDisplayID()`, captured with `SCContentFilter(display:excludingApplications:[the app whose pid is
  HELLO's mcPid], exceptingWindows:[])`, cursor shown; needs neither).
- Zoom (ZOOM_IN / ZOOM_OUT / EXIT_ZOOM) is retired on a Mac (spec 5.9); the messages stay for compatibility. ZOOM_IN
  on a `main` (Mirror) surface does nothing (the Mirror is used in-world instead).
- In-world use (spec 5.9, 5.10; the crosshair is the mouse). `slot` + `x`, `y` = a pixel of that slot's virtual display
  (0…w-1, 0…h-1); the helper maps it to `CGDisplayBounds` (global points). **Slot 0 is the main display** (a Mirror
  hologram): `x`, `y` are then a pixel of a `w` × `h` picture of it (both required, else the message is malformed), mapped
  by proportion onto `CGDisplayBounds(CGMainDisplayID())` (points, so Retina and any capture size work). The mod makes
  Minecraft's window click-through before sending a slot-0 CLICK / POINTER_DOWN / SCROLL; the window server applies that
  a moment later, so for slot 0 the helper waits (≤ 300 ms) until a click at the point would no longer hit Minecraft's
  window (`NSWindow.windowNumber(at:)`), then acts. A CLICK (any slot) first activates the app under the point (≤ 300 ms),
  so the click lands in it (caret at the click point) instead of only activating it. `button`: `left`, `right` or `middle`,
  required for POINTER_DOWN, POINTER_UP and CLICK. All of it needs Accessibility (else `ERROR no_accessibility`); an
  unknown slot gives `ERROR not_found`. Why it works this way: `helper-mac/Tools/inputprobe.swift`.
  - CLICK: a real click there (the cursor moves there, the app under it comes forward with the keyboard) = typing mode.
    A session event tap swallows a plain Esc (its repeats and release too) → the helper re-activates Minecraft and sends
    `TYPING_EXIT {"reason":"esc"}`. Minecraft becoming active (a click on its window, Cmd-Tab) → `TYPING_EXIT
    {"reason":"minecraft"}`. Any **other** app becoming active (not Minecraft, not the app typed into — the one the click
    brought forward, or the first one activated after a desktop click) → `TYPING_EXIT {"reason":"elsewhere"}`, Minecraft
    not activated. While typing the helper samples `IsSecureEventInputEnabled()` twice a second and sends
    `SECURE_INPUT {"on":true|false}` on each change (secure input hides Esc from the tap; ⌘-Tab still works). EXIT_TYPING
    (mod → helper) ends typing without a reply.
  - POINTER_DOWN starts a drag the helper drives: it brings the app under the point forward, posts a real button down
    there, and takes the physical mouse with an event tap until the physical button is released. The physical motion
    goes to the mod as `DRAG_DELTA {dx, dy}` (points, summed, about every 4 ms) so it can turn the player's head;
    POINTER_MOVE (the crosshair's new pixel, on any slot) is posted as a real drag there. The physical release (or a
    0.25 s button-state poll that finds it up, also when it was released before POINTER_DOWN arrived) posts the up at
    the last point, sends `DRAG_ENDED {"reason":"released"}` and re-activates Minecraft. POINTER_UP cancels from the
    mod side (`DRAG_ENDED {"reason":"cancelled"}`). The mod disconnecting cancels too.
  - SCROLL: a real scroll-wheel event at the point, in pixels (`dy` > 0 = up); scrolling never activates an app. On
    slot 0 the (hidden) cursor is also put at the point, so a wheel event of the player's during Minecraft's
    click-through moment lands in the same app.
- **Work mode** (spec 5.10 round 2; what the Mac mod uses now instead of CLICK / POINTER_DOWN): WORK_BEGIN `{slot, x,
  y, button[, w, h]}` (slot 0 needs w, h) → the helper hides the system cursor (background-hide, re-asserted on every
  app activation and ten times a second), warps the real cursor to the point, waits for click-through on slot 0, brings
  the app there forward, then posts only a **down** if the physical button is still held (the hand finishes the press:
  real drags, the real release) or a **click** if it is already up. From then on the real mouse drives the real cursor;
  the helper reports where it is about 120 times a second while it moves: `CURSOR {slot, x, y, w, h}` (slot 0 = the main
  display in points, 1–4 = an Office display in its pixels, -1 = another monitor), on every real mouse event (round 3;
  plus 30 times a second as a fallback). **Walk while dragging** (round 3): while a real mouse button is held, W A S D,
  Space and Shift are taken from the app (swallowed by the key tap; Shift is also stripped from the app's events) and
  reported as `WALK {keys}` whenever the set changes (forward, back, left, right, jump, sneak); the button's release
  sends `WALK []` at once. A key taken stays taken until its key-up; a key already down in the app stays the app's.
  On another monitor (slot -1: no
  hologram shows it) the system cursor is shown, and hidden again when it comes back. Esc (the tap) or Minecraft
  becoming active end it (`TYPING_EXIT esc|minecraft`), other apps coming forward do not; EXIT_TYPING ends it from the
  mod (it also waits a moment before taking clicks again). The cursor shows again when it ends (and by itself if the
  helper dies) and is put back over Minecraft's window, so its mouse grab starts there. SECURE_INPUT is reported as
  for typing. The cursor hide uses private CGS calls looked up at run time (`dlsym`); without them the cursor just
  stays visible.
- The Office displays are placed next to the main display (session-only display configuration): in slot order along its
  top edge from its right edge, or from its left edge when a real monitor already touches its right edge (past any
  monitor already on the left; a monitor macOS shoved aside to fit a new display in is put back where it was), so the
  cursor moves off the main display straight onto display 1. DISPLAY then carries the display's origin (`x`, `y`) and
  `side` (`left` / `right` of the main display); the mod puts the new Extend hologram on that side of the Mirror. The
  arrangement is redone when a display is removed. Display and Mirror captures leave the cursor out (the mod draws it).
- RATE `{surface, fps}` changes a running capture's frame rate in place (`SCStream.updateConfiguration`): the mod asks
  60 fps only for the own screen the cursor is on, 30 for its other visible ones, and stops hidden ones.
- Types 17 and 18 (WINDOWS_TO_DISPLAY / WINDOWS_BACK) are retired (spec 5.10: windows never move by themselves); they
  are unknown types now and close the connection like any other.
- The mini Dock (spec 5.10):
  - LIST_APPS → `APPS {"apps":[{bundle,name}]}`: the apps in /Applications, /Applications/Utilities,
    /System/Applications(/Utilities), ~/Applications, plus Finder; one per bundle id, A–Z, the helper left out. The mod
    asks on every connect.
  - APP_ICONS `{bundles, size}` (16…256 px, at most 64 plausible bundle ids, else malformed) → `ICONS` with a base64 PNG
    of NSWorkspace's icon for each installed one (others are left out).
  - OPEN_APP `{slot, bundle}` or `{slot, url}` (exactly one; slot 1…4; url `https://` with a host; optional `bottom`
    0…4096 = display pixels the in-world Dock covers, the window stops above them; else malformed): opens
    the app (or the page in the default browser) without activating it, takes its newest window (a new one, else its
    frontmost; apps that launch without a window are "reopened" after 3 s and 12 s, as Home Base did), and puts it on
    that slot's display filling its visible area (Accessibility), raised. If the app activated itself while Minecraft
    was in front, Minecraft is activated again. Then `APP_RESULT {target, ok, message}` (`target` = the bundle id or
    url): e.g. "That app isn't installed", "That screen isn't on yet", "… opened but showed no window" (after 40 s),
    "OfficeHelper needs Accessibility…".
- ENDED reasons: `"window closed"`, `"display removed"`, `"no display"`, `"no permission"` (Screen Recording missing),
  `"capture failed: <detail>"`.
- ERROR codes: `bad_slot`, `bad_size`, `display_failed`, `not_found`, `no_accessibility`, `bad_request`, `version`,
  `busy`.
- TYPING_EXIT / DRAG_ENDED reasons: `esc`, `minecraft`, `elsewhere` / `released`, `cancelled`.
- `windowKey` = `"<bundleId>|<CGWindowID>"` (id after the last `|`; bundle id may be empty).
- `mcWindowFrame` and WINDOWS `w`/`h` are global points, top-left origin (Quartz window coordinates).
