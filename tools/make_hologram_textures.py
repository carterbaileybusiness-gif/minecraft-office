#!/usr/bin/env python3
"""Draws the hologram "look" textures (standard library only, deterministic).

Run from the repo root:  python3 tools/make_hologram_textures.py
Writes mod/src/client/resources/assets/office/textures/misc/:
  panel.png      dark translucent glass behind "Connecting", "Set up screens", "Window closed", "Pick a window"
  frosted.png    frosted glass for private holograms seen by other players
  offline.png    dim standby panel with a soft centre glow (owner offline)
  scanlines.png  one soft scanline per 16 rows; tiled vertically (repeat) and drawn at low alpha
  icons.png      64x64 white icons with a soft halo: lock, power, download-to-tray, window (tinted at draw time)
Each texture gets a .png.mcmeta asking for linear filtering ("blur") so it stays smooth at any hologram size.
"""
import json
import math
import os
import random
import struct
import zlib

OUT = os.path.join("mod", "src", "client", "resources", "assets", "office", "textures", "misc")


def write_png(name, w, h, rgba, blur=True, clamp=True):
    """rgba: list of rows, each a list of (r, g, b, a) ints 0-255."""
    raw = b"".join(b"\x00" + b"".join(bytes(px) for px in row) for row in rgba)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    path = os.path.join(OUT, name)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
    with open(path + ".mcmeta", "w") as f:
        json.dump({"texture": {"blur": blur, "clamp": clamp}}, f, indent=2)
        f.write("\n")


def clamp8(v):
    return max(0, min(255, int(round(v))))


def lerp(a, b, t):
    return a + (b - a) * t


def smoothstep(e0, e1, x):
    t = max(0.0, min(1.0, (x - e0) / (e1 - e0)))
    return t * t * (3 - 2 * t)


def value_noise(w, h, cell, seed):
    """Smooth value noise in [0,1] on a w x h grid with lattice spacing `cell` pixels."""
    rnd = random.Random(seed)
    gw, gh = w // cell + 2, h // cell + 2
    grid = [[rnd.random() for _ in range(gw)] for _ in range(gh)]
    out = []
    for y in range(h):
        gy, fy = divmod(y / cell, 1)
        gy = int(gy)
        sy = fy * fy * (3 - 2 * fy)
        row = []
        for x in range(w):
            gx, fx = divmod(x / cell, 1)
            gx = int(gx)
            sx = fx * fx * (3 - 2 * fx)
            top = lerp(grid[gy][gx], grid[gy][gx + 1], sx)
            bot = lerp(grid[gy + 1][gx], grid[gy + 1][gx + 1], sx)
            row.append(lerp(top, bot, sy))
        out.append(row)
    return out


SIZE = 256


def panel():
    rnd = random.Random(7)
    rows = []
    for y in range(SIZE):
        v = (y + 0.5) / SIZE
        row = []
        for x in range(SIZE):
            u = (x + 0.5) / SIZE
            # radial falloff from the centre (0 centre .. ~1 corners)
            d = math.hypot(u - 0.5, v - 0.5) / 0.7071
            centre = 1.0 - smoothstep(0.0, 1.0, d)
            r = lerp(5, 12, centre)
            g = lerp(22, 46, centre)
            b = lerp(31, 60, centre)
            # glassy sheen along the top edge
            sheen = (1.0 - smoothstep(0.0, 0.22, v)) * 0.55
            r, g, b = r + 22 * sheen, g + 40 * sheen, b + 46 * sheen
            # dither so dark gradients don't band
            n = rnd.uniform(-1.0, 1.0)
            a = lerp(0.84, 0.90, d)
            row.append((clamp8(r + n), clamp8(g + n), clamp8(b + n), clamp8(a * 255)))
        rows.append(row)
    write_png("panel.png", SIZE, SIZE, rows)


def frosted():
    rnd = random.Random(11)
    n1 = value_noise(SIZE, SIZE, 48, 1)
    n2 = value_noise(SIZE, SIZE, 16, 2)
    n3 = value_noise(SIZE, SIZE, 6, 3)
    rows = []
    for y in range(SIZE):
        v = (y + 0.5) / SIZE
        row = []
        for x in range(SIZE):
            u = (x + 0.5) / SIZE
            frost = 0.5 * n1[y][x] + 0.32 * n2[y][x] + 0.18 * n3[y][x]  # 0..1, mostly ~0.5
            grain = rnd.uniform(-1.0, 1.0)
            # soft diagonal sheen band
            diag = (u + v) * 0.5
            sheen = math.exp(-((diag - 0.34) / 0.07) ** 2) * 0.8 + math.exp(-((diag - 0.47) / 0.025) ** 2) * 0.5
            # brighter rim, like light catching the glass edges
            edge = min(u, v, 1 - u, 1 - v)
            rim = 1.0 - smoothstep(0.0, 0.05, edge)
            lum = (frost - 0.5) * 34 + sheen * 26 + rim * 22 + grain * 2.5
            r, g, b = 70 + lum, 112 + lum, 130 + lum * 1.05
            a = 0.68 + (frost - 0.5) * 0.10 + sheen * 0.06 + rim * 0.08
            row.append((clamp8(r), clamp8(g), clamp8(b), clamp8(min(a, 0.95) * 255)))
        rows.append(row)
    write_png("frosted.png", SIZE, SIZE, rows)


def offline():
    rnd = random.Random(13)
    rows = []
    for y in range(SIZE):
        v = (y + 0.5) / SIZE
        row = []
        for x in range(SIZE):
            u = (x + 0.5) / SIZE
            d = math.hypot((u - 0.5) * 1.15, v - 0.5)
            glow = math.exp(-(d / 0.30) ** 2)
            n = rnd.uniform(-1.0, 1.0)
            r = 3 + 16 * glow + n
            g = 12 + 52 * glow + n
            b = 18 + 66 * glow + n
            a = 0.50 + 0.16 * glow
            row.append((clamp8(r), clamp8(g), clamp8(b), clamp8(a * 255)))
        rows.append(row)
    write_png("offline.png", SIZE, SIZE, rows)


def scanlines():
    # 4 x 16: one soft bright row per 16; repeated along the panel height (sampler: repeat + linear).
    profile = {12: 0.30, 13: 1.0, 14: 0.30}
    rows = []
    for y in range(16):
        a = profile.get(y, 0.0)
        rows.append([(230, 250, 255, clamp8(a * 255))] * 4)
    write_png("scanlines.png", 4, 16, rows, blur=True, clamp=False)


# --- icons ---------------------------------------------------------------------------------------------------------

def rrect(x, y, x0, y0, x1, y1, r):
    """Inside a rounded rectangle."""
    cx = min(max(x, x0 + r), x1 - r)
    cy = min(max(y, y0 + r), y1 - r)
    return (x - cx) ** 2 + (y - cy) ** 2 <= r * r and x0 <= x <= x1 and y0 <= y <= y1


def lock(x, y):
    body = rrect(x, y, 15, 29, 49, 55, 5)
    hole = math.hypot(x - 32, y - 39) <= 4.0 or (30.0 <= x <= 34.0 and 39 <= y <= 48)
    rad = math.hypot(x - 32, y - 27)
    shackle = 7.5 <= rad <= 12.5 and y <= 27
    legs = (19.5 <= x <= 24.5 or 39.5 <= x <= 44.5) and 27 <= y <= 30
    return (body and not hole) or shackle or legs


def power(x, y):
    rad = math.hypot(x - 32, y - 35)
    ang = math.degrees(math.atan2(x - 32, -(y - 35)))  # 0 = straight up
    ring = 14.0 <= rad <= 19.5 and abs(ang) > 38
    bar = rrect(x, y, 29.25, 8, 34.75, 33, 2.75)
    return ring or bar


def download(x, y):
    shaft = 28.5 <= x <= 35.5 and 8 <= y <= 32
    # arrow head: triangle (18,30) (46,30) (32,45)
    head = y >= 30 and y <= 45 and abs(x - 32) <= (45 - y) * (14 / 15)
    tray = (rrect(x, y, 12, 49, 52, 55, 2)) or (12 <= x <= 18 and 40 <= y <= 55) or (46 <= x <= 52 and 40 <= y <= 55)
    return shaft or head or tray


def window(x, y):
    outer = rrect(x, y, 9, 13, 55, 51, 4)
    inner = rrect(x, y, 13.5, 22, 50.5, 46.5, 1.5)
    return outer and not inner


def icons():
    shapes = [lock, power, download, window]
    w, h, ss = 64 * len(shapes), 64, 4
    cov = [[0.0] * w for _ in range(h)]
    for i, shape in enumerate(shapes):
        for y in range(h):
            for x in range(64):
                hits = 0
                for sy in range(ss):
                    for sx in range(ss):
                        if shape(x + (sx + 0.5) / ss, y + (sy + 0.5) / ss):
                            hits += 1
                cov[y][i * 64 + x] = hits / (ss * ss)

    def box_blur(src, rad):
        out = [[0.0] * w for _ in range(h)]
        for y in range(h):
            for x in range(w):
                tile = x // 64
                lo, hi = max(tile * 64, x - rad), min(tile * 64 + 63, x + rad)
                out[y][x] = sum(src[y][lo:hi + 1]) / (2 * rad + 1)
        out2 = [[0.0] * w for _ in range(h)]
        for y in range(h):
            for x in range(w):
                lo, hi = max(0, y - rad), min(h - 1, y + rad)
                out2[y][x] = sum(out[yy][x] for yy in range(lo, hi + 1)) / (2 * rad + 1)
        return out2

    halo = box_blur(box_blur(box_blur(cov, 3), 3), 3)
    rows = []
    for y in range(h):
        row = []
        for x in range(w):
            a = max(cov[y][x], min(1.0, halo[y][x] * 1.6) * 0.33)
            row.append((255, 255, 255, clamp8(a * 255)))
        rows.append(row)
    write_png("icons.png", w, h, rows, blur=True, clamp=True)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    panel()
    frosted()
    offline()
    scanlines()
    icons()
    print("wrote", sorted(f for f in os.listdir(OUT) if f.endswith(".png")))
