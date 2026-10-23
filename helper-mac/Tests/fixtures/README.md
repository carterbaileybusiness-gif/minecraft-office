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
| `stop.bin` | STOP = 6 | `{"surface":7}` |
| `ready.bin` | READY = 7 | `{"surface":7}` |
| `zoom_in.bin` | ZOOM_IN = 8 | `{"mcWindowFrame":{"h":875,"w":1440,"x":0,"y":25},"surface":7}` |
| `zoom_out.bin` | ZOOM_OUT = 9 | `{}` |
| `request_permissions.bin` | REQUEST_PERMISSIONS = 10 | `{}` |
| `welcome.bin` | WELCOME = 64 | `{"accessibility":false,"screenRecording":true,"version":1}` |
| `windows.bin` | WINDOWS = 65 | `[{"app":"Notes","h":600,"title":"Groceries — List","w":800,"windowKey":"com.apple.Notes\|4321"},{"app":"Safari","h":900,"title":"","w":1200,"windowKey":"com.apple.Safari\|99"}]` (the dash is U+2014, bytes `e2 80 94`) |
| `display.bin` | DISPLAY = 66 | `{"displayId":5,"h":1080,"slot":1,"w":1920}` |
| `frame.bin` | FRAME = 67 | surface 7, generation 3, 2 × 2; RGBA row 0: `ff 00 00 ff`, `00 ff 00 ff`; row 1: `00 00 ff ff`, `ff ff ff 80` |
| `patch.bin` | PATCH = 68 | surface 7, generation 3, 4 × 2, count 2: rect (0,0,1,1) pixels `0a 14 1e ff`; rect (2,1,2,1) pixels `28 32 3c ff`, `46 50 5a ff` |
| `ended.bin` | ENDED = 69 | `{"reason":"window closed","surface":8}` |
| `exit_zoom.bin` | EXIT_ZOOM = 70 | `{}` |
| `error.bin` | ERROR = 71 | `{"code":"bad_slot","message":"slot must be 1...4"}` |

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
- ENDED reasons: `"window closed"`, `"display removed"`, `"no display"`, `"no permission"` (Screen Recording missing),
  `"capture failed: <detail>"`.
- ERROR codes: `bad_slot`, `bad_size`, `display_failed`, `not_found`, `no_accessibility`, `bad_request`, `version`,
  `busy`.
- `windowKey` = `"<bundleId>|<CGWindowID>"` (id after the last `|`; bundle id may be empty).
- `mcWindowFrame` and WINDOWS `w`/`h` are global points, top-left origin (Quartz window coordinates).
