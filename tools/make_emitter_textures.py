#!/usr/bin/env python3
"""Draws the 16x16 Hologram Emitter textures (dark base, thin cyan strip) with only the standard library.

Run from the repo root:  python3 tools/make_emitter_textures.py
Writes mod/src/main/resources/assets/office/textures/block/hologram_emitter_{side,top,bottom,lens}.png
"""
import os
import struct
import zlib

OUT = os.path.join("mod", "src", "main", "resources", "assets", "office", "textures", "block")

BASE = (0x1A, 0x1D, 0x24)       # dark gunmetal
BASE_HI = (0x26, 0x2A, 0x33)    # bevel highlight
BASE_LO = (0x10, 0x12, 0x17)    # bevel shadow
CYAN = (0x3F, 0xF5, 0xFF)       # emissive strip
CYAN_DIM = (0x1E, 0x8C, 0x9A)   # strip glow falloff
CYAN_CORE = (0xC8, 0xFF, 0xFF)  # hot centre of the lens


def png(path, pixels):
    """pixels: 16 rows of 16 (r, g, b) tuples -> opaque RGBA PNG."""
    raw = b"".join(b"\x00" + b"".join(bytes(p) + b"\xff" for p in row) for row in pixels)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", 16, 16, 8, 6, 0, 0, 0)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def noise(x, y):
    # Tiny deterministic speckle so the metal isn't flat.
    return ((x * 7 + y * 13) ^ (x * y)) % 5 == 0


def base_px(x, y):
    return BASE_HI if noise(x, y) else BASE


def side():
    # The model shows rows 13-15 (the 3 px tall base): bevel top row, cyan strip in the middle, shadow at the bottom.
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            if y == 13:
                row.append(BASE_HI)
            elif y == 14:
                row.append(CYAN if 3 <= x <= 12 else CYAN_DIM)
            elif y == 15:
                row.append(BASE_LO)
            else:
                row.append(base_px(x, y))
        rows.append(row)
    return rows


def top():
    # Dark plate with a thin cyan ring around the lens socket.
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            edge = x in (2, 13) or y in (2, 13)
            ring = (x in (4, 11) and 4 <= y <= 11) or (y in (4, 11) and 4 <= x <= 11)
            if ring:
                row.append(CYAN_DIM)
            elif edge:
                row.append(BASE_HI)
            else:
                row.append(base_px(x, y))
        rows.append(row)
    return rows


def bottom():
    return [[BASE_LO if (x + y) % 4 else BASE for x in range(16)] for y in range(16)]


def lens():
    # Bright cyan, hotter in the middle (rendered full-bright via light_emission).
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            d = max(abs(x - 7.5), abs(y - 7.5))
            row.append(CYAN_CORE if d < 1.5 else CYAN if d < 3 else CYAN_DIM)
        rows.append(row)
    return rows


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, fn in (("side", side), ("top", top), ("bottom", bottom), ("lens", lens)):
        png(os.path.join(OUT, f"hologram_emitter_{name}.png"), fn())
    print("wrote", OUT)


if __name__ == "__main__":
    main()
