#!/usr/bin/env python3
"""Draws the in-world pointer's arrow (standard library only, deterministic).

Run from the repo root:  python3 tools/make_cursor_texture.py
Writes mod/src/client/resources/assets/office/textures/misc/cursor.png (16 x 24, white arrow with a black outline, hot
spot at the top-left pixel) and a .png.mcmeta with nearest filtering ("blur": false) so it stays crisp on a hologram.
"""
import json
import os
import struct
import zlib

OUT = os.path.join("mod", "src", "client", "resources", "assets", "office", "textures", "misc")

# X = outline, o = fill, space = transparent. The classic pointer, tip at (0, 0).
ARROW = [
    "X",
    "XX",
    "XoX",
    "XooX",
    "XoooX",
    "XooooX",
    "XoooooX",
    "XooooooX",
    "XoooooooX",
    "XooooooooX",
    "XoooooooooX",
    "XooooooooooX",
    "XooooooXXXXXX",
    "XoooXooX",
    "XooXXooX",
    "XoX  XooX",
    "XX   XooX",
    "X     XooX",
    "      XooX",
    "       XooX",
    "       XooX",
    "        XX",
]
W, H = 16, 24


def main():
    rows = []
    for y in range(H):
        line = ARROW[y] if y < len(ARROW) else ""
        row = []
        for x in range(W):
            c = line[x] if x < len(line) else " "
            row.append((0, 0, 0, 255) if c == "X" else (255, 255, 255, 255) if c == "o" else (0, 0, 0, 0))
        rows.append(row)
    raw = b"".join(b"\x00" + b"".join(bytes(px) for px in row) for row in rows)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", W, H, 8, 6, 0, 0, 0)
    path = os.path.join(OUT, "cursor.png")
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
    with open(path + ".mcmeta", "w") as f:
        json.dump({"texture": {"blur": False, "clamp": True}}, f, indent=2)
        f.write("\n")


if __name__ == "__main__":
    main()
