#!/usr/bin/env python3
"""Draws the mini Dock's built-in textures (standard library only, deterministic, anti-aliased by 4x4 supersampling).

Run from the repo root:  python3 tools/make_dock_textures.py
Writes into mod/src/client/resources/assets/office/textures/misc/ (each with a .png.mcmeta: smooth filtering):
  dock_bar.png      64 x 64   white disc: the Dock bar is drawn as its left half, a stretched middle column and its
                              right half (a pill), tinted dark by the renderer; also the hover label's backing
  dock_youtube.png  128 x 128 a red rounded square with a white play triangle (YouTube opens in the default browser)
  dock_allapps.png  128 x 128 a slate rounded square with a 3 x 3 grid of white dots ("All apps")
"""
import json
import os
import struct
import zlib

OUT = os.path.join("mod", "src", "client", "resources", "assets", "office", "textures", "misc")
SS = 4  # supersamples per axis


def write_png(path, w, h, pixel):
    rows = []
    for y in range(h):
        row = bytearray(b"\x00")
        for x in range(w):
            r = g = b = a = 0.0
            for sy in range(SS):
                for sx in range(SS):
                    pr, pg, pb, pa = pixel((x + (sx + 0.5) / SS) / w, (y + (sy + 0.5) / SS) / h)
                    r += pr * pa
                    g += pg * pa
                    b += pb * pa
                    a += pa
            n = SS * SS
            if a > 0:
                r, g, b = r / a, g / a, b / a
            row += bytes((round(r), round(g), round(b), round(255 * a / n)))
        rows.append(bytes(row))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + chunk(b"IEND", b""))
    with open(path + ".mcmeta", "w") as f:
        json.dump({"texture": {"blur": True, "clamp": True}}, f, indent=2)
        f.write("\n")


def rounded(u, v, inset, radius):
    """Inside a rounded square inset by `inset`, corner `radius` (all in 0..1 units)."""
    x0, x1 = inset, 1 - inset
    if u < x0 or u > x1 or v < x0 or v > x1:
        return False
    cx = min(max(u, x0 + radius), x1 - radius)
    cy = min(max(v, x0 + radius), x1 - radius)
    return (u - cx) ** 2 + (v - cy) ** 2 <= radius ** 2


def bar(u, v):
    inside = (u - 0.5) ** 2 + (v - 0.5) ** 2 <= 0.25
    return (255, 255, 255, 1.0 if inside else 0.0)


def youtube(u, v):
    if not rounded(u, v, 0.06, 0.22):
        return (0, 0, 0, 0.0)
    # play triangle: left edge x = 0.40, apex x = 0.68, half height 0.16
    if 0.40 <= u <= 0.68 and abs(v - 0.5) <= 0.16 * (0.68 - u) / 0.28:
        return (255, 255, 255, 1.0)
    shade = 1.0 - 0.10 * v  # a little lighter at the top
    return (round(255 * shade), 0, round(51 * shade), 1.0)


def allapps(u, v):
    if not rounded(u, v, 0.06, 0.22):
        return (0, 0, 0, 0.0)
    for gy in (0.31, 0.5, 0.69):
        for gx in (0.31, 0.5, 0.69):
            if rounded((u - gx) / 0.14 + 0.5, (v - gy) / 0.14 + 0.5, 0.0, 0.32):
                return (255, 255, 255, 1.0)
    top, bottom = (84, 96, 116), (46, 54, 68)
    return tuple(round(t + (b - t) * v) for t, b in zip(top, bottom)) + (1.0,)


def main():
    write_png(os.path.join(OUT, "dock_bar.png"), 64, 64, bar)
    write_png(os.path.join(OUT, "dock_youtube.png"), 128, 128, youtube)
    write_png(os.path.join(OUT, "dock_allapps.png"), 128, 128, allapps)
    print("wrote dock_bar.png, dock_youtube.png, dock_allapps.png")


if __name__ == "__main__":
    main()
