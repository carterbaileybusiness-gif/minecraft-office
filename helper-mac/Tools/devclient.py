#!/usr/bin/env python3
"""Tiny development client for OfficeHelper's socket protocol (stands in for the mod).

Usage: python3 -I helper-mac/Tools/devclient.py STEP [STEP ...]
Steps (run in order; incoming messages are printed as they arrive):
  create SLOT W H            CREATE_DISPLAY, then wait up to 5 s for DISPLAY (or ERROR)
  remove SLOT                REMOVE_DISPLAY
  list                       LIST_WINDOWS, wait for WINDOWS
  capture SURFACE display SLOT MAXW FPS | capture SURFACE window KEY MAXW FPS | capture SURFACE main MAXW FPS
  capturemon SURFACE UUID    CAPTURE main + display: Mirror that monitor (a UUID from `displays`), 1920 wide, 5 fps
  ready SURFACE              READY (one more frame)
  stop SURFACE               STOP
  zoomin SURFACE X Y W H     ZOOM_IN with mcWindowFrame
  zoomout                    ZOOM_OUT
  permissions                REQUEST_PERMISSIONS (macOS prompts for what is missing), then a fresh WELCOME
  down SLOT X Y [BUTTON]     POINTER_DOWN at a display pixel (a helper-driven drag; ends when the physical button is up)
  move SLOT X Y              POINTER_MOVE (during a drag)
  up SLOT X Y [BUTTON]       POINTER_UP (the mod cancels the drag)
  click SLOT X Y [BUTTON]    CLICK: a real click there; the app gets the keyboard (typing mode, Esc returns)
  scroll SLOT X Y DX DY      SCROLL (pixels; positive DY = up)
                             SLOT 0 = the main display (Mirror): X Y are a pixel of a 1920 x 1080 picture of it
                             (w/h are sent). Nothing here makes a window click-through: what is under X Y gets it.
  exittyping                 EXIT_TYPING
  apps                       LIST_APPS, wait for APPS (prints how many and the first few)
  icons SIZE BUNDLE [..]     APP_ICONS (ends at the next step word); prints each icon's PNG size
  open SLOT BUNDLE|URL       OPEN_APP: the app (bundle id) or https page onto that slot's display; waits for APP_RESULT
  work SLOT X Y [BUTTON]     WORK_BEGIN: work mode there (real cursor + real mouse until Esc; CURSOR reports print as
                             they come, at most one line per 0.25 s)
  workmon UUID X Y [BUTTON]  WORK_BEGIN slot 0 + display: work mode at pixel X Y of a 1920 x 1080 picture of that monitor
  sleep SECONDS              wait (still printing incoming messages)
  displays                   LIST_DISPLAYS, wait for DISPLAYS (every screen: UUID, slot, main, name, bounds); also print
                             the online display ids (CoreGraphics)
  origins SLOT X Y [SLOT X Y ..]  SET_ORIGINS: move Office displays (global points); waits for DISPLAYS (or ERROR first)
  uuid SLOT                  print the display UUID macOS uses to restore arrangement / window positions
  waitgone SLOT SECONDS      wait until the display reported for SLOT is offline; print how long it took
  badtoken                   open a second connection with a wrong token and report whether it was closed
The connection closes at the end (the helper then quits).
"""
import ctypes
import json
import os
import socket
import struct
import sys
import threading
import time

DIR = os.path.expanduser("~/Library/Application Support/Minecraft Office")
SOCK = os.path.join(DIR, "helper.sock")
TOKEN = os.path.join(DIR, "helper.token")
NAMES = {1: "HELLO", 2: "LIST_WINDOWS", 3: "CREATE_DISPLAY", 4: "REMOVE_DISPLAY", 5: "CAPTURE", 6: "STOP", 7: "READY",
         8: "ZOOM_IN", 9: "ZOOM_OUT", 10: "REQUEST_PERMISSIONS", 64: "WELCOME", 65: "WINDOWS", 66: "DISPLAY", 67: "FRAME", 68: "PATCH",
         69: "ENDED", 70: "EXIT_ZOOM", 71: "ERROR",
         11: "POINTER_DOWN", 12: "POINTER_MOVE", 13: "POINTER_UP", 14: "SCROLL", 15: "CLICK", 16: "EXIT_TYPING",
         19: "LIST_APPS", 20: "APP_ICONS", 21: "OPEN_APP", 72: "TYPING_EXIT", 73: "DRAG_DELTA", 74: "DRAG_ENDED",
         22: "WORK_BEGIN", 23: "RATE", 75: "APPS", 76: "ICONS", 77: "APP_RESULT", 78: "SECURE_INPUT", 79: "CURSOR", 80: "WALK",
         24: "LIST_DISPLAYS", 25: "SET_ORIGINS", 26: "IDENTIFY", 81: "DISPLAYS"}
STEPS = {"create", "remove", "list", "capture", "ready", "stop", "zoomin", "zoomout", "permissions", "down", "move", "up",
         "click", "scroll", "exittyping", "apps", "icons", "open", "work", "workmon", "capturemon", "sleep",
         "uuid", "displays", "origins", "waitgone", "badtoken"}
MAIN_PICTURE = {"w": 1920, "h": 1080}  # slot 0 (Mirror): the picture size x / y belong to
CODES = {v: k for k, v in NAMES.items()}

cg = ctypes.CDLL("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics")


def online_displays():
    ids = (ctypes.c_uint32 * 32)()
    count = ctypes.c_uint32(0)
    cg.CGGetOnlineDisplayList(32, ids, ctypes.byref(count))
    return [ids[i] for i in range(count.value)]


def display_uuid(display_id):
    """macOS keys display arrangement and window restoration on this UUID (vendor/product/serial based)."""
    cf = ctypes.CDLL("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation")
    cs = ctypes.CDLL("/System/Library/Frameworks/ColorSync.framework/ColorSync")
    cs.CGDisplayCreateUUIDFromDisplayID.restype = ctypes.c_void_p
    cs.CGDisplayCreateUUIDFromDisplayID.argtypes = [ctypes.c_uint32]
    cf.CFUUIDCreateString.restype = ctypes.c_void_p
    cf.CFUUIDCreateString.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
    cf.CFStringGetCString.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_long, ctypes.c_uint32]
    uuid = cs.CGDisplayCreateUUIDFromDisplayID(display_id)
    if not uuid:
        return None
    text = cf.CFUUIDCreateString(None, uuid)
    buffer = ctypes.create_string_buffer(64)
    cf.CFStringGetCString(text, buffer, 64, 0x08000100)
    return buffer.value.decode()


def frame(kind, payload=None):
    body = json.dumps(payload if payload is not None else {}, separators=(",", ":"), sort_keys=True).encode()
    return struct.pack(">IB", 1 + len(body), CODES[kind]) + body


def read_exact(s, n):
    data = b""
    while len(data) < n:
        chunk = s.recv(n - len(data))
        if not chunk:
            return None
        data += chunk
    return data


def connect(token):
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.connect(SOCK)
    s.sendall(frame("HELLO", {"token": token, "version": 1, "mcPid": os.getpid(), "features": ["screens"]}))
    return s


class Client:
    def __init__(self):
        with open(TOKEN) as f:
            self.token = f.read().strip()
        self.sock = connect(self.token)
        self.cond = threading.Condition()
        self.inbox = []
        self.displays = {}
        threading.Thread(target=self.reader, daemon=True).start()

    def reader(self):
        try:
            self.read_loop()
        except OSError:
            pass

    def read_loop(self):
        while True:
            head = read_exact(self.sock, 4)
            if head is None:
                print("[closed by helper]", flush=True)
                return
            (length,) = struct.unpack(">I", head)
            body = read_exact(self.sock, length)
            if body is None:
                return
            kind, payload = NAMES.get(body[0], str(body[0])), body[1:]
            if kind in ("FRAME", "PATCH"):
                surface, generation, w, h = struct.unpack(">IQII", payload[:20])
                extra = ""
                if kind == "PATCH":
                    (count,) = struct.unpack(">I", payload[20:24])
                    extra = f" rects {count}"
                print(f"<- {kind} surface {surface} gen {generation} {w}x{h}{extra} ({len(payload)} bytes)", flush=True)
                self.sock.sendall(frame("READY", {"surface": surface}))
                value = None
            else:
                value = json.loads(payload) if payload else None
                if kind == "APPS":
                    apps = value.get("apps", [])
                    print(f"<- APPS {len(apps)} apps, e.g. {[a['name'] for a in apps[:6]]}", flush=True)
                elif kind == "CURSOR":
                    now = time.time()
                    if now - getattr(self, "last_cursor_print", 0) >= 0.25:
                        self.last_cursor_print = now
                        print(f"<- CURSOR {json.dumps(value)}", flush=True)
                elif kind == "ICONS":
                    print(f"<- ICONS {[(i['bundle'], len(i['png']) * 3 // 4) for i in value.get('icons', [])]} (bundle, PNG bytes)", flush=True)
                else:
                    print(f"<- {kind} {json.dumps(value)}", flush=True)
                if kind == "DISPLAY":
                    self.displays[value["slot"]] = value["displayId"]
            with self.cond:
                self.inbox.append((kind, value))
                self.cond.notify_all()

    def wait_for(self, kind, timeout, also=()):
        deadline = time.time() + timeout
        with self.cond:
            while True:
                for i, (k, v) in enumerate(self.inbox):
                    if k == kind or k in also:
                        del self.inbox[i]
                        return (k, v) if also else v
                left = deadline - time.time()
                if left <= 0:
                    return None
                self.cond.wait(left)

    def send(self, kind, payload=None):
        print(f"-> {kind} {json.dumps(payload) if payload else ''}", flush=True)
        self.sock.sendall(frame(kind, payload))


def main(argv):
    c = Client()
    if c.wait_for("WELCOME", 5) is None:
        print("no WELCOME", flush=True)
        return 1
    i = 0
    status = 0
    while i < len(argv):
        step = argv[i]
        if step == "create":
            slot, w, h = map(int, argv[i + 1:i + 4]); i += 4
            t = time.time()
            c.send("CREATE_DISPLAY", {"slot": slot, "w": w, "h": h})
            got = c.wait_for("DISPLAY", 5, also=("ERROR",))
            print(f"   {got[0]} after {time.time() - t:.2f} s" if got else "   no DISPLAY within 5 s", flush=True)
            status |= 0 if got else 1
        elif step == "remove":
            c.send("REMOVE_DISPLAY", {"slot": int(argv[i + 1])}); i += 2
        elif step == "list":
            c.send("LIST_WINDOWS"); i += 1
            c.wait_for("WINDOWS", 5)
        elif step == "capture" and argv[i + 2] == "main":
            # Mirror: the main display minus the mcPid app (devclient's HELLO mcPid is its own pid)
            c.send("CAPTURE", {"surface": int(argv[i + 1]), "kind": "main", "maxW": int(argv[i + 3]), "fps": int(argv[i + 4])})
            i += 5
        elif step == "capture":
            surface, kind = int(argv[i + 1]), argv[i + 2]
            payload = {"surface": surface, "kind": kind, "maxW": int(argv[i + 4]), "fps": int(argv[i + 5])}
            payload["slot" if kind == "display" else "windowKey"] = int(argv[i + 3]) if kind == "display" else argv[i + 3]
            c.send("CAPTURE", payload); i += 6
        elif step == "capturemon":
            c.send("CAPTURE", {"surface": int(argv[i + 1]), "kind": "main", "maxW": 1920, "fps": 5, "display": argv[i + 2]})
            i += 3
        elif step == "workmon":
            uuid, x, y = argv[i + 1], int(argv[i + 2]), int(argv[i + 3]); i += 4
            button = "left"
            if i < len(argv) and argv[i] in ("left", "right", "middle"):
                button = argv[i]; i += 1
            c.send("WORK_BEGIN", {"slot": 0, "x": x, "y": y, "button": button, "display": uuid, **MAIN_PICTURE})
        elif step in ("ready", "stop"):
            c.send(step.upper(), {"surface": int(argv[i + 1])}); i += 2
        elif step == "zoomin":
            surface, x, y, w, h = map(int, argv[i + 1:i + 6]); i += 6
            c.send("ZOOM_IN", {"surface": surface, "mcWindowFrame": {"x": x, "y": y, "w": w, "h": h}})
        elif step == "zoomout":
            c.send("ZOOM_OUT"); i += 1
        elif step == "permissions":
            c.send("REQUEST_PERMISSIONS"); i += 1
        elif step in ("down", "up", "click"):
            slot, x, y = map(int, argv[i + 1:i + 4]); i += 4
            button = "left"
            if i < len(argv) and argv[i] in ("left", "right", "middle"):
                button = argv[i]; i += 1
            kind = {"down": "POINTER_DOWN", "up": "POINTER_UP", "click": "CLICK"}[step]
            c.send(kind, {"slot": slot, "x": x, "y": y, "button": button, **(MAIN_PICTURE if slot == 0 else {})})
        elif step == "move":
            slot, x, y = map(int, argv[i + 1:i + 4]); i += 4
            c.send("POINTER_MOVE", {"slot": slot, "x": x, "y": y, **(MAIN_PICTURE if slot == 0 else {})})
        elif step == "scroll":
            slot, x, y, dx, dy = map(int, argv[i + 1:i + 6]); i += 6
            c.send("SCROLL", {"slot": slot, "x": x, "y": y, "dx": dx, "dy": dy, **(MAIN_PICTURE if slot == 0 else {})})
        elif step == "exittyping":
            c.send("EXIT_TYPING"); i += 1
        elif step == "apps":
            c.send("LIST_APPS"); i += 1
            c.wait_for("APPS", 10)
        elif step == "icons":
            size = int(argv[i + 1]); i += 2
            bundles = []
            while i < len(argv) and argv[i] not in STEPS:
                bundles.append(argv[i]); i += 1
            c.send("APP_ICONS", {"bundles": bundles, "size": size})
            c.wait_for("ICONS", 10)
        elif step == "work":
            slot, x, y = map(int, argv[i + 1:i + 4]); i += 4
            button = "left"
            if i < len(argv) and argv[i] in ("left", "right", "middle"):
                button = argv[i]; i += 1
            c.send("WORK_BEGIN", {"slot": slot, "x": x, "y": y, "button": button, **(MAIN_PICTURE if slot == 0 else {})})
        elif step == "open":
            slot, target = int(argv[i + 1]), argv[i + 2]; i += 3
            c.send("OPEN_APP", {"slot": slot, ("url" if target.startswith("https://") else "bundle"): target})
            c.wait_for("APP_RESULT", 45)
        elif step == "sleep":
            time.sleep(float(argv[i + 1])); i += 2
        elif step == "uuid":
            did = c.displays.get(int(argv[i + 1])); i += 2
            print(f"   display {did} uuid {display_uuid(did)}", flush=True)
        elif step == "displays":
            c.send("LIST_DISPLAYS"); i += 1
            c.wait_for("DISPLAYS", 5)
            print(f"   online displays: {online_displays()}", flush=True)
        elif step == "origins":
            i += 1
            origins = []
            while i + 2 < len(argv) and argv[i] not in STEPS:
                origins.append({"slot": int(argv[i]), "x": int(argv[i + 1]), "y": int(argv[i + 2])}); i += 3
            c.send("SET_ORIGINS", {"origins": origins})
            got = c.wait_for("DISPLAYS", 5, also=("ERROR",))
            if got and got[0] == "ERROR":
                c.wait_for("DISPLAYS", 5)
        elif step == "waitgone":
            slot, limit = int(argv[i + 1]), float(argv[i + 2]); i += 3
            did = c.displays.get(slot)
            t = time.time()
            while did in online_displays() and time.time() - t < limit:
                time.sleep(0.05)
            gone = did not in online_displays()
            print(f"   display {did} {'gone' if gone else 'STILL ONLINE'} after {time.time() - t:.2f} s", flush=True)
            status |= 0 if gone else 1
        elif step == "badtoken":
            i += 1
            s = connect("0" * 64)
            s.settimeout(5)
            try:
                closed = s.recv(1) == b""
            except socket.timeout:
                closed = False
            print(f"   wrong token connection {'closed' if closed else 'NOT closed'}", flush=True)
            status |= 0 if closed else 1
        else:
            print(f"unknown step {step}", flush=True)
            return 2
    c.sock.close()
    return status


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
