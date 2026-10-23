#!/usr/bin/env python3
"""Tiny development client for OfficeHelper's socket protocol (stands in for the mod).

Usage: python3 -I helper-mac/Tools/devclient.py STEP [STEP ...]
Steps (run in order; incoming messages are printed as they arrive):
  create SLOT W H            CREATE_DISPLAY, then wait up to 5 s for DISPLAY (or ERROR)
  remove SLOT                REMOVE_DISPLAY
  list                       LIST_WINDOWS, wait for WINDOWS
  capture SURFACE display SLOT MAXW FPS | capture SURFACE window KEY MAXW FPS
  ready SURFACE              READY (one more frame)
  stop SURFACE               STOP
  zoomin SURFACE X Y W H     ZOOM_IN with mcWindowFrame
  zoomout                    ZOOM_OUT
  permissions                REQUEST_PERMISSIONS (macOS prompts for what is missing), then a fresh WELCOME
  sleep SECONDS              wait (still printing incoming messages)
  displays                   print online display ids (CoreGraphics)
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
         69: "ENDED", 70: "EXIT_ZOOM", 71: "ERROR"}
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
    s.sendall(frame("HELLO", {"token": token, "version": 1, "mcPid": os.getpid()}))
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
        elif step == "capture":
            surface, kind = int(argv[i + 1]), argv[i + 2]
            payload = {"surface": surface, "kind": kind, "maxW": int(argv[i + 4]), "fps": int(argv[i + 5])}
            payload["slot" if kind == "display" else "windowKey"] = int(argv[i + 3]) if kind == "display" else argv[i + 3]
            c.send("CAPTURE", payload); i += 6
        elif step in ("ready", "stop"):
            c.send(step.upper(), {"surface": int(argv[i + 1])}); i += 2
        elif step == "zoomin":
            surface, x, y, w, h = map(int, argv[i + 1:i + 6]); i += 6
            c.send("ZOOM_IN", {"surface": surface, "mcWindowFrame": {"x": x, "y": y, "w": w, "h": h}})
        elif step == "zoomout":
            c.send("ZOOM_OUT"); i += 1
        elif step == "permissions":
            c.send("REQUEST_PERMISSIONS"); i += 1
        elif step == "sleep":
            time.sleep(float(argv[i + 1])); i += 2
        elif step == "uuid":
            did = c.displays.get(int(argv[i + 1])); i += 2
            print(f"   display {did} uuid {display_uuid(did)}", flush=True)
        elif step == "displays":
            print(f"   online displays: {online_displays()}", flush=True); i += 1
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
