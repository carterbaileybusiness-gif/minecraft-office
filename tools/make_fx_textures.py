#!/usr/bin/env python3
"""Draws the magic fx particle textures (white, tinted at render) with only the standard library (deterministic).

Run from the repo root:  python3 tools/make_fx_textures.py

Writes under mod/src/client/resources/assets/office/textures/particle/fx/ (vanilla's particle atlas picks up every
textures/particle/ file, so each is the sprite office:fx/<name>):

  line.png      32x32  a soft line along x: a bright core with a glow either side. Summoning-circle strokes are laid
                       end to end from it (MagicCircleRenderer), so every line keeps its width at any radius.
  core.png      32x32  the same line's hot middle only (drawn white over line.png in the same pieces: a white core)
  glow.png      32x32  a round glow with a bright middle (motes, roundels, sparks)
  soft.png      128x128 a smooth, wide glow (the light behind a whole circle)
  spark.png     16x16  a four-pointed glint
  rune_0..7.png 32x32  the runes of misc/magic/runes.png (make_magic_textures.RUNES), anti-aliased, with a faint halo
"""
import math
import os
import sys

sys.dont_write_bytecode = True  # no tools/__pycache__ in the tree
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from make_magic_textures import RUNES, write_png  # noqa: E402  (one source for the rune shapes and the PNG writer)

ROOT = os.path.join("mod", "src", "client", "resources", "assets", "office", "textures", "particle", "fx")


def clamp(v):
    return max(0, min(255, int(round(v))))


def save(name, size, alpha):
    """alpha(x, y) in 0..1 over pixel centres; white everywhere, so the tint is the whole colour."""
    w, h = size
    px = [(255, 255, 255, clamp(255 * alpha(x + 0.5, y + 0.5))) for y in range(h) for x in range(w)]
    write_png(os.path.join(ROOT, name), w, h, px)


def line(x, y):
    d = abs(y - 16.0)
    core = math.exp(-(d / 1.7) ** 2)
    halo = 0.42 * math.exp(-(d / 5.5) ** 2)
    edge = max(0.0, 1.0 - max(0.0, d - 10.0) / 2.0)  # nothing past 12 px from the middle
    return min(1.0, (core + halo) * edge)


def core(x, y):
    d = abs(y - 16.0)
    return math.exp(-(d / 1.1) ** 2)


def glow(x, y):
    r = math.hypot(x - 16.0, y - 16.0)
    v = math.exp(-(r / 4.0) ** 2) + 0.45 * math.exp(-(r / 9.0) ** 2)
    return min(1.0, v) * max(0.0, 1.0 - max(0.0, r - 13.0) / 3.0)


def soft(x, y):
    r = math.hypot(x - 64.0, y - 64.0)
    v = math.exp(-(r / 34.0) ** 2)
    return v * max(0.0, 1.0 - max(0.0, r - 56.0) / 8.0)


def spark(x, y):
    dx, dy = abs(x - 8.0), abs(y - 8.0)
    arms = math.exp(-(dy / 0.9) ** 2) * max(0.0, 1 - dx / 7.5) + math.exp(-(dx / 0.9) ** 2) * max(0.0, 1 - dy / 7.5)
    core = math.exp(-((dx * dx + dy * dy) / 4.0))
    return min(1.0, arms * 0.9 + core)


def seg_dist(px, py, ax, ay, bx, by):
    vx, vy = bx - ax, by - ay
    wx, wy = px - ax, py - ay
    l2 = vx * vx + vy * vy
    t = 0.0 if l2 == 0 else max(0.0, min(1.0, (wx * vx + wy * vy) / l2))
    return math.hypot(px - (ax + t * vx), py - (ay + t * vy))


def rune(strokes):
    segs = []
    for pts in strokes:
        # the 16-cell grid of make_magic_textures, doubled, a cell's centre at +0.5
        p = [(2 * (x + 0.5), 2 * (y + 0.5)) for x, y in pts]
        segs += list(zip(p, p[1:]))

    def alpha(x, y):
        d = min(seg_dist(x, y, a[0], a[1], b[0], b[1]) for a, b in segs)
        stroke = max(0.0, min(1.0, 2.3 - d))  # 1.8 px half-width, a half-pixel antialiased edge
        halo = 0.35 * math.exp(-(d / 2.6) ** 2)
        return min(1.0, stroke + halo)

    return alpha


def main():
    save("line.png", (32, 32), line)
    save("core.png", (32, 32), core)
    save("glow.png", (32, 32), glow)
    save("soft.png", (128, 128), soft)
    save("spark.png", (16, 16), spark)
    for i, strokes in enumerate(RUNES):
        save("rune_%d.png" % i, (32, 32), rune(strokes))
    print("wrote", ROOT)


if __name__ == "__main__":
    main()
