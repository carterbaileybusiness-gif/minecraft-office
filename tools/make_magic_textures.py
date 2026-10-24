#!/usr/bin/env python3
"""Draws every Grimoire Magic texture with only the standard library (deterministic: same input, same bytes).

Run from the repo root:  python3 tools/make_magic_textures.py [--sheet out.png]

Reads the element colours from the COLOURS block of Element.java (enum lines `NAME("key", 0xAARRGGBB)` and the
`static final int RECALL_ARGB = 0x...` line) and the spell ids from the IDS block of SpellIds.java (a `// <element>`
comment starts each element's five spells, `// recall` marks Recall). Writes under mod/src/main/resources/assets/office/textures/:

  item/grimoire_cover.png       16x16 greyscale cover, tinted in-game by custom_model_data colour 0 (element or leather)
  item/grimoire_t0..t3.png      16x16 untinted tier overlays: pages, iron/gold trim, clasp, gem setting, sparkle
  (also writes assets/office/items/grimoire.json, whose tint default is GrimoireLook.UNBOUND_ARGB)
  item/rebinding_seal.png       16x16 violet wax seal with a nether-star imprint
  gui/spell/<id>.png            32x32 glyph per spell in its element colour (Recall gold)
  gui/magic/sigil_<key>.png     64x64 element medallion
  gui/magic/book_open.png       192x128 open grimoire (parchment pages; text is drawn on top)
  gui/magic/padlock.png         16x16 locked-slot padlock
  gui/magic/wheel_slice.png     128x128 white 60-degree wheel slice, wheel centre = image centre, pointing up (tint + rotate)
  gui/magic/glow.png            64x64 white radial glow (additive)
  misc/magic/ring_0..4.png      128x128 white summoning-circle rings, inner (0) to outer (4) (tint at render)
  misc/magic/runes.png          128x16 atlas of 8 white 16x16 runes

--sheet writes a contact sheet of everything (upscaled, items composited in every element colour) for review.
"""
import json
import math
import os
import re
import struct
import sys
import zlib

ROOT = os.path.join("mod", "src", "main", "resources", "assets", "office", "textures")
ELEMENT_JAVA = os.path.join("mod", "src", "main", "java", "local", "carter", "office", "magic", "core", "Element.java")
SPELL_IDS_JAVA = os.path.join("mod", "src", "main", "java", "local", "carter", "office", "magic", "spell", "SpellIds.java")
GRIMOIRE_LOOK_JAVA = os.path.join("mod", "src", "main", "java", "local", "carter", "office", "magic", "item", "GrimoireLook.java")
ITEMS_JSON = os.path.join("mod", "src", "main", "resources", "assets", "office", "items", "grimoire.json")


# ----------------------------------------------------------------------------------------------------------------------
# Sources of truth

def parse_colours(path=ELEMENT_JAVA):
    """{key: (r, g, b)} for the six elements plus 'recall', from the COLOURS block."""
    text = open(path, encoding="utf-8").read()
    m = re.search(r"//\s*COLOURS\s*\n(.*?)//\s*END COLOURS", text, re.S)
    if not m:
        sys.exit("make_magic_textures: no // COLOURS ... // END COLOURS block in " + path)
    block = m.group(1)
    out = {}
    for line in block.splitlines():
        line = line.strip()
        if not line or line.startswith("//") or line.startswith("/*") or line.startswith("*"):
            continue
        e = re.match(r'([A-Z_]+)\(\s*"([a-z_]+)"\s*,\s*0x([0-9A-Fa-f]{8})\s*\)\s*[,;]$', line)
        s = re.match(r"(?:public\s+|private\s+|protected\s+)?static\s+final\s+int\s+([A-Z_]+)\s*=\s*0x([0-9A-Fa-f]{8})\s*;$", line)
        if e:
            out[e.group(2)] = argb_rgb(e.group(3))
        elif s:
            if s.group(1) != "RECALL_ARGB":
                sys.exit("make_magic_textures: unexpected constant in COLOURS: " + line)
            out["recall"] = argb_rgb(s.group(2))
        else:
            sys.exit("make_magic_textures: can't parse COLOURS line: " + line)
    need = {"fire", "water", "wind", "lightning", "earth", "shadow", "recall"}
    if set(out) != need:
        sys.exit("make_magic_textures: COLOURS block has %s, want %s" % (sorted(out), sorted(need)))
    return out


def parse_unbound(path=GRIMOIRE_LOOK_JAVA):
    """The unbound cover tint (ARGB int) from GrimoireLook.UNBOUND_ARGB."""
    m = re.search(r"static\s+final\s+int\s+UNBOUND_ARGB\s*=\s*0x([0-9A-Fa-f]{8})\s*;", open(path, encoding="utf-8").read())
    if not m:
        sys.exit("make_magic_textures: no UNBOUND_ARGB in " + path)
    return int(m.group(1), 16)


def write_grimoire_items_json(unbound_argb, path=ITEMS_JSON):
    """items/grimoire.json: select the tier model on custom_model_data string 0; tint layer0 by colour 0 (default = unbound)."""
    signed = unbound_argb - (1 << 32) if unbound_argb >= 1 << 31 else unbound_argb

    def tier(t):
        return {"type": "minecraft:model", "model": "office:item/grimoire_t%d" % t,
                "tints": [{"type": "minecraft:custom_model_data", "index": 0, "default": signed}]}
    doc = {"model": {"type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0,
                     "cases": [{"when": "t%d" % t, "model": tier(t)} for t in range(4)], "fallback": tier(0)}}
    with open(path, "w", encoding="utf-8") as f:
        f.write(json.dumps(doc, indent=2) + "\n")


def argb_rgb(hexstr):
    v = int(hexstr, 16)
    return ((v >> 16) & 255, (v >> 8) & 255, v & 255)


def parse_spell_ids(path=SPELL_IDS_JAVA):
    """[(id, element key or 'recall')] in file order, from the IDS block."""
    text = open(path, encoding="utf-8").read()
    m = re.search(r"//\s*IDS\s*\n(.*?)//\s*END IDS", text, re.S)
    if not m:
        sys.exit("make_magic_textures: no // IDS ... // END IDS block in " + path)
    out, current = [], None
    for line in m.group(1).splitlines():
        line = line.strip()
        c = re.match(r"//\s*([a-z_]+)\s*$", line)
        i = re.search(r'\bid\("([a-z0-9_]+)"\)', line)
        if c:
            current = c.group(1)
        elif i:
            if current is None:
                sys.exit("make_magic_textures: spell id before any // <element> comment: " + line)
            out.append((i.group(1), current))
    return out


# ----------------------------------------------------------------------------------------------------------------------
# PNG + canvas (same writer as tools/make_emitter_textures.py, with alpha and any size)

def write_png(path, w, h, px):
    """px: flat list of (r, g, b, a), row-major."""
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        for x in range(w):
            raw.extend(px[y * w + x])

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))


CLEAR = (0, 0, 0, 0)


class Canvas:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.px = [CLEAR] * (w * h)

    def get(self, x, y):
        if 0 <= x < self.w and 0 <= y < self.h:
            return self.px[y * self.w + x]
        return CLEAR

    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            if len(c) == 3:
                c = (c[0], c[1], c[2], 255)
            self.px[y * self.w + x] = tuple(int(v) for v in c)

    def over(self, x, y, c):
        """Alpha-composite c over the pixel."""
        if not (0 <= x < self.w and 0 <= y < self.h):
            return
        if len(c) == 3:
            c = c + (255,)
        r, g, b, a = self.get(x, y)
        sa = c[3] / 255.0
        da = a / 255.0
        oa = sa + da * (1 - sa)
        if oa <= 0:
            return
        mix = lambda s, d: (s * sa + d * da * (1 - sa)) / oa
        self.set(x, y, (round(mix(c[0], r)), round(mix(c[1], g)), round(mix(c[2], b)), round(oa * 255)))

    def save(self, rel):
        write_png(os.path.join(ROOT, rel), self.w, self.h, self.px)
        return self


def from_rows(rows, palette):
    """Hand-drawn pixel map: one string per row, one char per pixel, palette char -> colour ('.' = clear)."""
    c = Canvas(len(rows[0]), len(rows))
    for y, row in enumerate(rows):
        if len(row) != c.w:
            sys.exit("make_magic_textures: ragged pixel map row %d: %r" % (y, row))
        for x, ch in enumerate(row):
            if ch != ".":
                c.set(x, y, palette[ch])
    return c


# ----------------------------------------------------------------------------------------------------------------------
# Colour helpers (Minecraft-style ramps: darker tones lean cool, lighter tones lean warm)

def clamp(v):
    return max(0, min(255, int(round(v))))


def lerp(a, b, t):
    return tuple(clamp(a[i] + (b[i] - a[i]) * t) for i in range(3))


def scale(c, k):
    return tuple(clamp(v * k) for v in c)


def ramp(base):
    """Five tones for a glyph: outline, dark, body, light, core."""
    cool = (40, 30, 110)
    warm = (255, 250, 220)
    return {
        "O": lerp(scale(base, 0.30), cool, 0.18),
        "d": lerp(scale(base, 0.66), cool, 0.10),
        "b": base,
        "l": lerp(base, warm, 0.42),
        "c": lerp(base, (255, 255, 255), 0.82),
    }


def hash01(x, y, seed=0):
    """Deterministic per-pixel noise in [0, 1)."""
    n = (x * 374761393 + y * 668265263 + seed * 2246822519) & 0xFFFFFFFF
    n = ((n ^ (n >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((n ^ (n >> 16)) & 0xFFFF) / 65536.0


# ----------------------------------------------------------------------------------------------------------------------
# Shape masks (sets of pixel coords; a pixel is in when its centre is inside — crisp, no anti-aliasing)

def _cells(w, h, inside):
    return {(x, y) for y in range(h) for x in range(w) if inside(x + 0.5, y + 0.5)}


def disc(cx, cy, r, size=32):
    return _cells(size, size, lambda x, y: (x - cx) ** 2 + (y - cy) ** 2 <= r * r)


def ellipse(cx, cy, rx, ry, size=32):
    return _cells(size, size, lambda x, y: ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2 <= 1)


def ring(cx, cy, r0, r1, size=32, a0=None, a1=None):
    """Annulus r0..r1; optional arc a0..a1 in degrees (0 = right, 90 = up, counter-clockwise on screen)."""
    def inside(x, y):
        d = math.hypot(x - cx, y - cy)
        if not (r0 <= d <= r1):
            return False
        if a0 is None:
            return True
        a = math.degrees(math.atan2(-(y - cy), x - cx)) % 360
        lo, hi = a0 % 360, a1 % 360
        span = (a1 - a0) % 360 if a1 - a0 < 360 else 360
        return span >= 360 or (a - lo) % 360 <= span
    return _cells(size, size, inside)


def poly(points, size=32):
    def inside(x, y):
        hit = False
        n = len(points)
        for i in range(n):
            x1, y1 = points[i]
            x2, y2 = points[(i + 1) % n]
            if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1:
                hit = not hit
        return hit
    return _cells(size, size, inside)


def seg(x0, y0, x1, y1, w, size=32):
    """Capsule of width w along a segment."""
    r = w / 2.0
    dx, dy = x1 - x0, y1 - y0
    ll = dx * dx + dy * dy or 1e-9

    def inside(x, y):
        t = max(0.0, min(1.0, ((x - x0) * dx + (y - y0) * dy) / ll))
        return (x - x0 - t * dx) ** 2 + (y - y0 - t * dy) ** 2 <= r * r
    return _cells(size, size, inside)


def path(points, w, size=32):
    out = set()
    for a, b in zip(points, points[1:]):
        out |= seg(a[0], a[1], b[0], b[1], w, size)
    return out


def tapered(points, w0, w1, size=32):
    """Polyline whose width runs from w0 to w1."""
    out = set()
    n = len(points) - 1
    for i, (a, b) in enumerate(zip(points, points[1:])):
        w = w0 + (w1 - w0) * (i + 0.5) / n
        out |= seg(a[0], a[1], b[0], b[1], w, size)
    return out


def rect(x0, y0, x1, y1):
    """Inclusive pixel rectangle."""
    return {(x, y) for y in range(y0, y1 + 1) for x in range(x0, x1 + 1)}


def spiral(cx, cy, r0, r1, turns, a0, w, size=32, steps=90):
    pts = []
    for i in range(steps + 1):
        t = i / steps
        a = math.radians(a0 + 360 * turns * t)
        r = r0 + (r1 - r0) * t
        pts.append((cx + r * math.cos(a), cy - r * math.sin(a)))
    return path(pts, w, size)


def rotate(points, deg, cx, cy):
    a = math.radians(deg)
    ca, sa = math.cos(a), math.sin(a)
    return [(cx + (x - cx) * ca - (y - cy) * sa, cy + (x - cx) * sa + (y - cy) * ca) for x, y in points]


def star(cx, cy, r_out, r_in, n, rot=-90, size=32):
    pts = []
    for i in range(n * 2):
        r = r_out if i % 2 == 0 else r_in
        a = math.radians(rot + 180.0 * i / n)
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return poly(pts, size)


def flame(cx, base, w, h, lean=0.0, size=32):
    """A flame tongue: round bottom, pointed (slightly leaning) tip."""
    r = w / 2.0
    body = disc(cx, base - r, r, size)
    tip = poly([(cx - r, base - r), (cx + r, base - r), (cx + r * 0.35 + lean, base - h * 0.62), (cx + lean, base - h)], size)
    tip |= poly([(cx - r, base - r), (cx + lean, base - h), (cx - r * 0.2 + lean * 0.5, base - h * 0.6)], size)
    return body | tip


def comet(cx, cy, r, dx, dy, length, size=32):
    """Teardrop: a disc with a pointed tail of the given length in direction (dx, dy)."""
    ln = math.hypot(dx, dy)
    ux, uy = dx / ln, dy / ln
    px, py = -uy, ux
    tip = (cx + ux * length, cy + uy * length)
    return disc(cx, cy, r, size) | poly([(cx + px * r, cy + py * r), tip, (cx - px * r, cy - py * r)], size)


def bolt_path(x0, y0, x1, y1, kinks, amp, seed):
    pts = [(x0, y0)]
    for i in range(1, kinks):
        t = i / kinks
        side = amp if (i + seed) % 2 else -amp
        nx, ny = -(y1 - y0), (x1 - x0)
        ln = math.hypot(nx, ny) or 1
        pts.append((x0 + (x1 - x0) * t + nx / ln * side, y0 + (y1 - y0) * t + ny / ln * side))
    pts.append((x1, y1))
    return pts


# ----------------------------------------------------------------------------------------------------------------------
# Glyph styling: tone layers -> bevelled, outlined pixel art

def render_glyph(layers, base, size=32, outline=True, bevel=True):
    """layers: [(mask, tone)] painted in order; tone in 'd' 'b' 'l' 'c' (or 'O').
    'b' pixels get an automatic bevel: light where the pixel above/left is open, dark where below/right is open."""
    tones = {}
    for mask, tone in layers:
        for p in mask:
            if 0 <= p[0] < size and 0 <= p[1] < size:
                tones[p] = tone
    pal = ramp(base)
    cv = Canvas(size, size)
    filled = set(tones)
    for (x, y), t in tones.items():
        if t == "b" and bevel:
            if (x, y - 1) not in filled or (x - 1, y) not in filled:
                t = "l"
            elif (x, y + 1) not in filled or (x + 1, y) not in filled:
                t = "d"
        cv.set(x, y, pal[t])
    if outline:
        for (x, y) in filled:
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                q = (x + dx, y + dy)
                if q not in filled and 0 <= q[0] < size and 0 <= q[1] < size and cv.get(*q)[3] == 0:
                    cv.set(q[0], q[1], pal["O"])
    return cv


# ----------------------------------------------------------------------------------------------------------------------
# Spell glyphs (32x32). Each returns tone layers; render_glyph adds the bevel and the outline.

def g_fire_bolt():
    tail = tapered([(4, 28), (9, 23), (14, 18), (18, 14)], 2.0, 6.0)
    side1 = tapered([(5, 18), (10, 15), (15, 12)], 1.2, 3.0)
    side2 = tapered([(14, 28), (17, 23), (20, 18)], 1.2, 3.0)
    head = disc(21, 11, 6.5)
    tip = poly([(17, 6), (24, 3), (27, 2), (26, 6), (27, 12)])
    return [(tail | side1 | side2 | head | tip, "b"), (disc(21.5, 10.5, 4), "l"), (disc(22, 10, 2.2), "c"),
            (tapered([(8, 24), (12, 20), (16, 16)], 0.8, 2.2), "l")]


def g_fire_wall():
    ground = rect(3, 25, 28, 27)
    f1 = flame(8, 25, 7, 15, lean=-1)
    f2 = flame(16, 25, 8, 22, lean=1)
    f3 = flame(24, 25, 7, 16, lean=1.5)
    cores = flame(8, 25, 3, 8, -0.5) | flame(16, 25, 4, 13, 0.5) | flame(24, 25, 3, 9, 0.8)
    return [(f1 | f2 | f3, "b"), (cores, "l"), (flame(16, 25, 2, 7, 0.3), "c"), (ground, "d"), (rect(3, 25, 28, 25), "b")]


def g_flame_dash():
    chev1 = path([(15, 6), (25, 16), (15, 26)], 5)
    chev2 = path([(6, 9), (13, 16), (6, 23)], 3.5)
    trail = tapered([(2, 16), (8, 16)], 1.0, 2.5)
    return [(chev2 | trail, "d"), (chev1, "b"), (path([(16, 9), (23, 16), (16, 23)], 1.6), "c")]


def g_fireball_barrage():
    layers = []
    for (cx, cy, r) in ((9, 22.5, 5.0), (23.5, 23.5, 3.8), (19.5, 9.5, 3.8)):
        layers.append((comet(cx, cy, r, 1, -1, r * 2.6), "b"))
        layers.append((comet(cx - 0.5, cy + 0.5, r * 0.6, 1, -1, r * 1.5), "l"))
        layers.append((disc(cx - 0.8, cy + 0.8, r * 0.28), "c"))
    return layers


def g_meteor():
    tail = poly([(7, 15), (17, 25), (30, 2)])
    inner = poly([(10, 17), (15, 22), (26, 6)])
    rock = disc(11.5, 20.5, 7.5)
    craters = disc(9, 18, 1.6) | disc(14, 23, 1.3) | disc(8, 23.5, 1.0)
    return [(tail, "b"), (inner, "l"), (poly([(13, 19), (15, 20), (24, 9)]), "c"), (rock, "d"), (craters, "O"),
            (ring(11.5, 20.5, 6.4, 7.6, a0=20, a1=110), "b")]


def g_water_bullet():
    drop = disc(19, 16, 7) | poly([(3, 16), (16, 9.6), (16, 22.4)])
    lines = seg(3, 7, 11, 7, 2.2) | seg(1, 25, 9, 25, 2.2)
    return [(lines, "d"), (drop, "b"), (disc(21, 13, 2.5), "l"), (disc(21.5, 12.5, 1.2), "c"),
            (path([(10, 16), (16, 16)], 1.2), "l")]


def g_tidal_push():
    wave = poly([(2, 29), (2, 21), (6, 14), (11, 8), (17, 4.5), (23, 4.5), (28, 8), (29.5, 13), (26.5, 15.5), (25, 11.5),
                 (21, 10.5), (17.5, 13.5), (17.5, 18.5), (21, 22), (26, 22.5), (30, 21.5), (30, 29)])
    inner = poly([(17.5, 13.5), (21, 10.5), (25, 11.5), (24, 13), (21, 13), (19.5, 16), (20, 19), (17.5, 18.5)])
    crest = path([(8, 12), (12, 8), (17, 5.5), (23, 5.5), (27.5, 8.5), (28.5, 12.5)], 1.4)
    foam = disc(27.5, 19, 1.3) | disc(24.5, 17.5, 0.9) | path([(18, 20), (22, 23.5), (29, 23)], 1.2)
    return [(wave, "b"), (inner, "d"), (crest, "c"), (foam, "c"), (rect(3, 27, 29, 28), "d"),
            (path([(5, 23), (9, 20), (13, 21)], 1.0) | path([(7, 27), (12, 25), (16, 26)], 1.0), "l")]


def g_bubble_shield():
    shell = ring(15, 16, 9.6, 12.4)
    shine = ring(15, 16, 7.0, 8.6, a0=105, a1=165)
    bubbles = disc(27, 6, 2.2) | disc(29, 11.5, 1.4) | disc(26.5, 27, 1.8)
    return [(shell | bubbles, "b"), (shine, "c"), (ring(15, 16, 11.2, 12.4, a0=190, a1=340), "d"),
            (disc(26.5, 5.5, 0.9), "c"), (disc(9.5, 9.5, 1.1), "c")]


def g_water_prison():
    shell = ring(16, 16, 10, 13)
    fill = disc(16, 16, 10)
    figure = disc(16, 11.5, 2.8) | poly([(12, 23), (13, 15.5), (19, 15.5), (20, 23)])
    bubbles = disc(10, 20, 1.2) | disc(22, 9, 1.0) | disc(21.5, 21, 0.9)
    return [(shell, "b"), (fill - figure, "d"), (figure, "O"), (bubbles, "l"), (ring(16, 16, 10.5, 12, a0=110, a1=160), "c")]


def g_water_dragon():
    body = tapered([(3, 29), (7, 23), (12, 22), (16, 25), (21, 22), (20, 15), (14, 12), (14, 7), (19, 4)], 2.0, 5.0)
    head = poly([(17, 2), (24, 1), (30, 5), (29, 9), (23, 9), (18, 8)])
    jaw = poly([(22, 9), (29, 9), (26, 12)])
    fins = poly([(7, 22), (6, 18), (9, 21)]) | poly([(19, 22), (22, 18), (22, 22)]) | poly([(14, 12), (10, 11), (14, 9)])
    horn = poly([(18, 3), (14, 0.5), (19, 1.5)])
    return [(body | head | jaw | fins | horn, "b"), (tapered([(8, 24), (12, 23.5), (16, 26)], 0.8, 1.2), "l"),
            (rect(24, 4, 24, 4), "c"), (rect(25, 4, 25, 4), "O")]


def g_gust():
    top = seg(3, 10, 20, 10, 2.6) | ring(20, 6.5, 2.2, 4.8, a0=-90, a1=180)
    mid = seg(6, 16, 27, 16, 2.6)
    bot = seg(3, 22, 23, 22, 2.6) | ring(23, 25.5, 2.2, 4.8, a0=180, a1=450)
    return [(mid, "d"), (top | bot, "b"), (seg(5, 10, 16, 10, 0.9) | seg(5, 22, 18, 22, 0.9), "c")]


def g_blade_wind():
    def crescent(cx, cy, r, off):
        return disc(cx, cy, r) - disc(cx - off, cy, r * 0.92)
    c1 = crescent(17, 16, 11, 5)
    c2 = crescent(11, 16, 7.5, 3.8)
    c3 = crescent(26, 16, 5.0, 2.6)
    return [(c2, "d"), (c1, "b"), (c3, "l"), (ring(17, 16, 9.6, 10.6, a0=-50, a1=50), "c")]


def g_air_step():
    shaft = seg(16, 22, 16, 11, 4.5)
    head = poly([(8, 13), (24, 13), (16, 3)])
    cloud = disc(9, 25, 3.5) | disc(14, 23, 4.5) | disc(20, 24, 4.0) | disc(25, 26, 3.0) | rect(7, 25, 27, 28)
    return [(cloud, "l"), (rect(7, 27, 27, 28), "b"), (shaft | head, "b"), (seg(16, 19, 16, 9, 1.4), "c"),
            (disc(13, 22, 1.2) | disc(20, 22.5, 1.0), "c")]


def g_tornado():
    bands = set()
    rows = [(5, 24, 0), (10, 19, 1.5), (15, 14, 0), (20, 9.5, -1.2), (24.5, 6, 0.4), (28, 3, 1.2)]
    for y, w, off in rows:
        cx = 16 + off
        bands |= seg(cx - w / 2, y, cx + w / 2, y, 3.6)
    debris = disc(4, 14, 1.2) | disc(28, 19, 1.0) | disc(26, 9, 0.9)
    hi = seg(6, 4.5, 20, 4.5, 1.0) | seg(10, 9.5, 18, 9.5, 0.9)
    return [(bands, "b"), (debris, "d"), (hi, "c")]


def g_wind_sphere():
    shell = ring(16, 16, 11, 13.2)
    arms = spiral(16, 16, 1.5, 9.5, 1.15, 90, 2.4) | spiral(16, 16, 1.5, 9.5, 1.15, 270, 2.4)
    return [(shell, "b"), (arms, "l"), (disc(16, 16, 2.2), "c"), (ring(16, 16, 12, 13.2, a0=200, a1=340), "d")]


def g_spark():
    big = star(14, 17, 13, 3.2, 4, rot=-90)
    small = star(26, 6, 5, 1.4, 4, rot=-90)
    return [(big | small, "b"), (star(14, 17, 7, 1.8, 4), "l"), (disc(14, 17, 2.2), "c"), (disc(26, 6, 0.9), "c")]


def g_chain_lightning():
    nodes = [(5, 6), (17, 16), (27, 27)]
    bolts = path(bolt_path(5, 6, 17, 16, 4, 3.0, 0), 2.4) | path(bolt_path(17, 16, 27, 27, 4, 2.6, 1), 2.4)
    cores = path(bolt_path(5, 6, 17, 16, 4, 3.0, 0), 0.9) | path(bolt_path(17, 16, 27, 27, 4, 2.6, 1), 0.9)
    knots = set().union(*(disc(x, y, 3.2) for x, y in nodes))
    return [(bolts, "b"), (cores, "c"), (knots, "b"), (set().union(*(disc(x, y, 1.4) for x, y in nodes)), "c")]


def g_blink():
    ghost1 = path([(3, 11), (8, 16), (3, 21)], 2.6)
    ghost2 = path([(9, 9), (15, 16), (9, 23)], 3.6)
    chev = path([(16, 6), (26, 16), (16, 26)], 5.2)
    flash = star(27, 16, 5.5, 1.3, 4)
    return [(ghost1, "d"), (ghost2, "b"), (chev, "b"), (flash, "c"), (path([(17, 9), (24, 16), (17, 23)], 1.6), "c")]


def g_thunder_spear():
    def local(pts):
        return rotate([(16 + u, 16 + v) for u, v in pts], -45, 16, 16)
    shaft = poly(local([(-15, -2.1), (4, -2.1), (4, 2.1), (-15, 2.1)]))
    head = poly(local([(3, 0), (7.5, -5.5), (16.5, 0), (7.5, 5.5)]))
    guard = poly(local([(2.5, -5), (5, -5), (5, 5), (2.5, 5)]))
    zig = path(local([(-14, 4.5), (-10, -4), (-6, 4), (-2, -4), (2, 3)]), 1.6)
    return [(shaft, "d"), (head | guard, "b"), (poly(local([(6.5, 0), (8.5, -2.4), (14.5, 0), (8.5, 2.4)])), "c"), (zig, "c")]


def g_chidori_strike():
    orb = disc(21, 16, 6.5)
    bolts = set()
    for i, (x, y) in enumerate(((2, 8), (1, 17), (3, 26), (14, 2), (13, 30))):
        bolts |= path(bolt_path(21, 16, x, y, 4, 2.0, i), 1.8)
    sparks = star(29, 9, 3.5, 1, 4) | star(29, 24, 3, 0.9, 4)
    return [(bolts, "b"), (sparks, "l"), (orb, "b"), (disc(21, 16, 4.6), "l"), (disc(21.5, 15.5, 2.4), "c")]


def g_stone_bullet():
    rock = poly([(13, 8), (20, 5), (27, 9), (29, 16), (25, 24), (17, 26), (12, 21), (11, 14)])
    facet = poly([(20, 5), (27, 9), (22, 13), (16, 11)])
    crack = path([(17, 25), (18.5, 21), (16.5, 18), (18, 15)], 1.0) | path([(18.5, 21), (22, 20)], 1.0)
    lines = seg(2, 11, 9, 11, 2) | seg(1, 17, 8, 17, 2.2) | seg(3, 23, 9, 23, 2)
    return [(lines, "d"), (rock, "b"), (facet, "l"), (crack, "d"), (disc(23, 9, 0.8), "c")]


def g_earth_wall():
    wall = rect(3, 9, 28, 28)
    merlons = rect(3, 5, 8, 8) | rect(13, 5, 18, 8) | rect(23, 5, 28, 8)
    mortar = set()
    for y in (13, 18, 23):
        mortar |= rect(4, y, 27, y)
    for row, y0 in enumerate((9, 14, 19, 24)):
        for x in range(4 + (row % 2) * 4, 28, 8):
            mortar |= rect(x, y0, x, y0 + 3)
    return [(wall | merlons, "b"), (mortar, "d"), (rect(4, 10, 6, 10) | rect(14, 6, 16, 6), "c")]


def g_earth_spikes():
    ground = rect(2, 25, 29, 28)
    spikes = [(8, 25, 5.5, 13), (16, 25, 6.5, 22), (24, 25, 5.5, 15)]
    layers = [(ground, "d"), (rect(2, 25, 29, 25), "b")]
    for cx, base, hw, h in spikes:
        left = poly([(cx - hw, base), (cx, base - h), (cx, base)])
        right = poly([(cx, base), (cx, base - h), (cx + hw, base)])
        layers += [(right, "d"), (left, "l"), (seg(cx - 0.6, base - h + 2, cx - 0.6, base - h + 4, 0.8), "c")]
    layers.append((disc(5, 21, 1.1) | disc(28, 19, 0.9) | disc(21, 6, 1.0), "b"))
    return layers


def g_quake():
    ground = rect(2, 21, 29, 28)
    crack = path([(16, 21), (14, 24), (17, 26), (15, 29)], 1.6)
    wave1 = ring(16, 21, 5.5, 7.8, a0=15, a1=165)
    wave2 = ring(16, 21, 10.5, 12.8, a0=15, a1=165)
    rocks = disc(6, 14, 1.4) | disc(26, 13, 1.2) | disc(16, 6, 1.0)
    return [(ground, "d"), (rect(2, 21, 29, 21), "b"), (wave2, "b"), (wave1, "l"), (rocks, "b"),
            ((crack | path([(16, 21), (20, 23), (24, 22)], 1.2)), "O")]


def g_mountain_fist():
    mountain = poly([(1, 29), (12, 7), (16, 13), (20, 4), (31, 29)])
    snow = poly([(20, 4), (23.5, 11), (21, 10), (19, 12), (17, 10.5)]) | poly([(12, 7), (14.5, 11), (12.5, 10.5), (10.5, 11)])
    shade = poly([(20, 4), (31, 29), (21, 29), (24, 18)])
    crack = path([(14, 29), (15, 24), (13, 20)], 1.2)
    debris = disc(5, 14, 1.4) | disc(28, 10, 1.2) | disc(8, 6, 1.0)
    return [(mountain, "b"), (shade, "d"), (snow, "c"), (crack, "O"), (debris, "b")]


def g_shadow_bolt():
    body = comet(20, 12, 7, -1, 1, 22)
    wisps = tapered([(4, 20), (9, 18), (14, 15)], 1.0, 3.2) | tapered([(12, 30), (14, 25), (17, 20)], 1.0, 3.2)
    return [(wisps, "d"), (body, "b"), (comet(20, 12, 4.4, -1, 1, 11), "d"), (disc(20.5, 11.5, 2.6), "O"),
            (ring(20, 12, 5.2, 6.4, a0=60, a1=170), "c")]


def g_gravity_well():
    arms = set()
    for a in (0, 120, 240):
        arms |= spiral(16, 16, 2.5, 13, 0.55, a, 2.6)
    return [(arms, "b"), (disc(16, 16, 4.2), "b"), (disc(16, 16, 2.8), "O"), (ring(16, 16, 2.8, 3.6), "c")]


def g_shadow_step():
    portal = ellipse(10, 17, 6.5, 12.5)
    void = ellipse(10, 17, 4.2, 10)
    figure = disc(21, 8, 3.4) | poly([(15, 29), (16.5, 14), (21, 12), (25.5, 14), (27, 29)])
    wisps = tapered([(10, 9), (14, 11), (17, 13)], 1.0, 2.2) | tapered([(10, 25), (14, 24), (16, 22)], 1.0, 2.2)
    return [(portal, "b"), (void, "O"), (wisps, "d"), (figure, "b"), (seg(17.5, 15, 17, 27, 1.0), "l"),
            (disc(22.5, 7.5, 0.9), "c"), (disc(10, 17, 1.4), "d")]


def g_shadow_bind():
    figure = disc(16, 7, 3.4) | poly([(10.5, 29), (11.5, 14), (16, 11.5), (20.5, 14), (21.5, 29)])
    band1 = ellipse(16, 15.5, 11, 3.6) - ellipse(16, 14.5, 9.2, 2.2)
    band2 = ellipse(16, 23.5, 11, 3.6) - ellipse(16, 22.5, 9.2, 2.2)
    tendril = tapered([(4, 29), (5, 24), (7, 20)], 2.4, 1.0) | tapered([(28, 29), (27, 25), (25, 21)], 2.4, 1.0)
    return [(figure, "O"), (tendril, "d"), (band1 | band2, "b"), (seg(9, 17.6, 14, 18.6, 0.9) | seg(9, 26.1, 14, 27.1, 0.9), "c")]


def g_black_hole():
    disk_back = ellipse(16, 16, 15, 5.2) - ellipse(16, 16, 10.5, 2.6)
    hole = disc(16, 16, 7)
    front = {(x, y) for (x, y) in disk_back if y >= 16}
    photon = ring(16, 16, 7, 8.2)
    return [(disk_back, "b"), (photon, "c"), (hole, "O"), (front, "l"), ({p for p in front if p[1] <= 17}, "c")]


def g_recall():
    arc = ring(16, 16, 10.5, 13.5, a0=120, a1=420 - 10)
    head = poly([(4, 6), (12, 4.5), (8.5, 12)])
    house = poly([(9, 16), (16, 9), (23, 16)]) | rect(11, 16, 21, 23)
    door = rect(15, 19, 17, 23)
    return [(arc | head, "b"), (house, "l"), (door, "O"), (rect(18, 18, 19, 19), "c")]


GLYPHS = {
    "fire_bolt": g_fire_bolt, "fire_wall": g_fire_wall, "flame_dash": g_flame_dash, "fireball_barrage": g_fireball_barrage,
    "meteor": g_meteor, "water_bullet": g_water_bullet, "tidal_push": g_tidal_push, "bubble_shield": g_bubble_shield,
    "water_prison": g_water_prison, "water_dragon": g_water_dragon, "gust": g_gust, "blade_wind": g_blade_wind,
    "air_step": g_air_step, "tornado": g_tornado, "wind_sphere": g_wind_sphere, "spark": g_spark,
    "chain_lightning": g_chain_lightning, "blink": g_blink, "thunder_spear": g_thunder_spear, "chidori_strike": g_chidori_strike,
    "stone_bullet": g_stone_bullet, "earth_wall": g_earth_wall, "earth_spikes": g_earth_spikes, "quake": g_quake,
    "mountain_fist": g_mountain_fist, "shadow_bolt": g_shadow_bolt, "gravity_well": g_gravity_well,
    "shadow_step": g_shadow_step, "shadow_bind": g_shadow_bind, "black_hole": g_black_hole, "recall": g_recall,
}


# ----------------------------------------------------------------------------------------------------------------------
# Element sigils (64x64): a bevelled medallion with the element's emblem

def emblem(key):
    """Element emblem on a 64 grid (centre 32,32, about 34 px tall)."""
    S = 64
    if key == "fire":
        outer = flame(32, 50, 20, 38, lean=2, size=S) | flame(22, 50, 9, 18, lean=-2, size=S) | flame(42, 50, 9, 20, lean=2, size=S)
        return [(outer, "b"), (flame(32, 50, 11, 25, lean=1.2, size=S), "l"), (flame(32, 50, 5, 13, lean=0.6, size=S), "c")]
    if key == "water":
        drop = disc(32, 38, 12, S) | poly([(20.5, 35), (32, 12), (43.5, 35)], S)
        return [(drop, "b"), (ring(32, 38, 7, 9.5, S, a0=150, a1=230), "c"), (disc(27, 34, 2.2, S), "c"),
                (ring(32, 38, 10.5, 12, S, a0=270, a1=360), "d")]
    if key == "wind":
        arms = set()
        for a in (90, 210, 330):
            arms |= spiral(32, 32, 3, 17, 0.62, a, 4.2, S, steps=120)
        return [(arms, "b"), (disc(32, 32, 4.5, S), "l"), (disc(32, 32, 2.2, S), "c")]
    if key == "lightning":
        b = poly([(36, 12), (22, 34), (31, 34), (26, 53), (43, 28), (33, 28), (39, 12)], S)
        return [(b, "b"), (poly([(35.5, 15), (26, 31), (31, 31)], S), "c"), (poly([(31, 34), (29, 45), (38, 31)], S), "l")]
    if key == "earth":
        m = poly([(14, 48), (27, 22), (32, 29), (38, 16), (51, 48)], S)
        shade = poly([(38, 16), (51, 48), (39, 48), (42, 33)], S)
        snow = poly([(38, 16), (42, 24), (39, 23), (37, 25), (35, 22)], S)
        base = rect(12, 48, 52, 51)
        return [(m, "b"), (shade, "d"), (snow, "c"), (base, "d")]
    if key == "shadow":
        moon = disc(32, 32, 16, S) - disc(39, 27, 13.5, S)
        eye = ellipse(41, 36, 7.5, 3.6, S)
        return [(moon, "b"), (eye, "c"), (disc(41, 36, 2.4, S), "O"), (ring(32, 32, 14.5, 16, S, a0=150, a1=250), "l")]
    raise KeyError(key)


def sigil(key, base):
    S = 64
    rim = ring(32, 32, 26.5, 31, S)
    notches = set()
    for i in range(12):
        a = math.radians(i * 30)
        notches |= disc(32 + 28.8 * math.cos(a), 32 + 28.8 * math.sin(a), 1.25, S)
    inner = ring(32, 32, 23.4, 24.6, S)
    gems = set()
    for i in range(8):
        a = math.radians(i * 45 + 22.5)
        gx, gy = 32 + 28.8 * math.cos(a), 32 + 28.8 * math.sin(a)
        gems |= poly([(gx, gy - 2.2), (gx + 2.2, gy), (gx, gy + 2.2), (gx - 2.2, gy)], S)
    face = disc(32, 32, 23.4, S)
    pal = ramp(base)
    # Face: a dark disc tinted toward the element so the emblem pops.
    face_col = lerp(pal["O"], (12, 10, 20), 0.35)
    cv = render_glyph([(rim, "b"), (notches, "d"), (gems, "c"), (inner, "l")] + emblem(key), base, size=S)
    out = Canvas(S, S)
    for (x, y) in face:
        out.set(x, y, face_col + (255,))
    # subtle radial sheen on the face
    for (x, y) in face:
        d = math.hypot(x + 0.5 - 26, y + 0.5 - 24)
        if d < 14 and hash01(x, y, 7) < 0.5 * (1 - d / 14):
            out.set(x, y, lerp(face_col, pal["d"], 0.35) + (255,))
    for y in range(S):
        for x in range(S):
            p = cv.get(x, y)
            if p[3]:
                out.set(x, y, p)
    # outer outline ring (from a snapshot, so it never spreads)
    solid = {(x, y) for y in range(S) for x in range(S) if out.get(x, y)[3] == 255}
    for y in range(S):
        for x in range(S):
            if (x, y) not in solid and any((x + dx, y + dy) in solid for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                out.set(x, y, pal["O"])
    return out


# ----------------------------------------------------------------------------------------------------------------------
# Items (16x16)

COVER_ROWS = [
    "................",
    ".OOOOOOOOOOOO...",
    ".OSshhhhhhhhO...",
    ".ObsleeeeeedOO..",
    ".OSslemmmmedOpO.",
    ".OSslmmEEmmdOpO.",
    ".OSslmEccEmdOpO.",
    ".OSslmEccEmdOpO.",
    ".OSslmmEEmmdOpO.",
    ".OSslemmmmedOpO.",
    ".ObsleeeeeedOpO.",
    ".OSsddddddddOpO.",
    ".OOOOOOOOOOOOpO.",
    "..OppppppppppqO.",
    "...OOOOOOOOOOOO.",
    "................",
]
COVER_PALETTE = {
    "O": (44, 44, 44), "s": (104, 104, 104), "S": (150, 150, 150), "b": (214, 214, 214),
    "h": (238, 238, 238), "l": (212, 212, 212), "m": (176, 176, 176), "d": (128, 128, 128),
    "e": (146, 146, 146), "E": (232, 232, 232), "c": (255, 255, 255),
}


def grimoire_cover():
    rows = [r.replace("p", ".").replace("q", ".") for r in COVER_ROWS]
    return from_rows(rows, COVER_PALETTE)


PAGE = {"p": (242, 232, 203), "P": (226, 213, 178), "q": (196, 180, 140)}
GLOW_PAGE = {"p": (255, 248, 214), "P": (255, 236, 160), "q": (232, 196, 110), "w": (255, 255, 255)}
IRON = {"I": (232, 232, 236), "i": (176, 176, 184), "k": (104, 104, 114)}
GOLD = {"W": (255, 255, 216), "Y": (252, 228, 92), "G": (226, 169, 42), "g": (150, 96, 18)}


def overlay(tier):
    grid = [list("." * 16) for _ in range(16)]

    def put(x, y, ch):
        grid[y][x] = ch

    # pages: right edge column 13 (rows 4..12) and bottom row 13 (cols 3..13)
    for y in range(3, 13):
        if COVER_ROWS[y][13] == "p":
            put(13, y, "p" if y % 2 == 0 else "P")
    for x in range(3, 13):
        put(x, 13, "p" if x % 2 == 0 else "P")
    put(13, 13, "q")
    if tier >= 1:
        # iron (t1) or gold (t2+) corner caps on the front cover, spine bands
        hi, mid, lo = ("I", "i", "k") if tier == 1 else ("Y", "G", "g")
        put(10, 2, hi); put(11, 2, hi); put(11, 3, mid)
        put(11, 10, mid); put(11, 11, lo); put(10, 11, lo)
        put(2, 3, hi); put(3, 3, mid); put(2, 10, hi); put(3, 10, mid)
    if tier >= 2:
        # left corners, clasp across the pages, studs around the emblem
        put(4, 2, "Y"); put(5, 2, "Y"); put(4, 3, "G")
        put(4, 11, "g"); put(5, 11, "g"); put(4, 10, "G")
        put(11, 6, "G"); put(12, 6, "Y"); put(13, 6, "G"); put(14, 6, "g")
        put(11, 7, "g"); put(12, 7, "G"); put(13, 7, "g"); put(14, 7, "g")
        put(6, 4, "G"); put(9, 4, "G"); put(6, 9, "g"); put(9, 9, "g")
    if tier >= 3:
        # gold filigree, gem setting with a glint, glowing pages, a sparkle
        for x in (5, 7, 8, 10):
            put(x, 3, "G")
        for x in (5, 7, 8, 10):
            put(x, 10, "g")
        put(5, 6, "G"); put(5, 7, "g"); put(10, 6, "G"); put(10, 7, "g")
        put(7, 6, "W")
        for y in range(3, 13):
            if COVER_ROWS[y][13] == "p":
                put(13, y, "w" if y in (5, 9) else ("p" if y % 2 == 0 else "P"))
        for x in range(3, 13):
            put(x, 13, "w" if x in (6, 10) else ("p" if x % 2 == 0 else "P"))
        put(14, 0, "Y"); put(13, 1, "Y"); put(15, 1, "Y"); put(14, 2, "Y"); put(14, 1, "W")
    if tier >= 2:
        # dark under-trim: every gold pixel on the cover casts a 1 px shadow below it onto bare cover (not the emblem),
        # so the gold still reads on light covers (Lightning yellow, Earth tan)
        gold_px = [(x, y) for y in range(16) for x in range(16) if grid[y][x] in "WYGg"]
        for x, y in gold_px:
            for dx, dy in ((0, 1),):
                qx, qy = x + dx, y + dy
                if qx < 16 and qy < 16 and grid[qy][qx] == "." and COVER_ROWS[qy][qx] not in ".OpqEc":
                    grid[qy][qx] = "x"
    pal = {"x": (62, 40, 14)}
    pal.update(GLOW_PAGE if tier >= 3 else PAGE)
    pal.update(IRON)
    pal.update(GOLD)
    return from_rows(["".join(r) for r in grid], pal)


SEAL_ROWS = [
    "................",
    ".....OOOOO......",
    "...OOhhhllOO....",
    "..OhhllllllmO...",
    ".OhhlllSlllmmO..",
    ".OhllllWlllmmO..",
    ".OhlllSWSmmmmdO.",
    "OhllSWWXWWSmmdO.",
    ".OllmmSWSmmmddO.",
    ".OlmmmmWmmmmddO.",
    "..OmmmmSmmmddO..",
    "..OOmmmmmmddOO..",
    "..ORrOOOOOORrO..",
    ".ORrrO....ORrrO.",
    ".ORrO......ORrO.",
    ".OO.........OO..",
]
SEAL_PALETTE = {
    "O": (40, 12, 56), "d": (86, 28, 116), "m": (132, 50, 170), "l": (178, 96, 214), "h": (220, 158, 244), "w": (255, 236, 255),
    "S": (176, 176, 204), "W": (232, 232, 252), "X": (255, 255, 255), "R": (226, 172, 46), "r": (150, 98, 18),
}


def rebinding_seal():
    """A violet wax seal pressed with a silver nether-star, two gold ribbon tails."""
    return from_rows(SEAL_ROWS, SEAL_PALETTE)


# ----------------------------------------------------------------------------------------------------------------------
# GUI pieces

def padlock():
    rows = [
        "................",
        "................",
        ".....OOOOOO.....",
        "....OiIIIIkO....",
        "...OiO....OkO...",
        "...OIO....OkO...",
        "...OIO....OkO...",
        "..OOOOOOOOOOOO..",
        "..OYYYYYYYYYGO..",
        "..OYGGGooGGGgO..",
        "..OYGGoooGGGgO..",
        "..OYGGGooGGGgO..",
        "..OYGGGooGGGgO..",
        "..OGgggggggggO..",
        "..OOOOOOOOOOOO..",
        "................",
    ]
    pal = {"O": (36, 26, 12), "I": (230, 230, 236), "i": (176, 176, 186), "k": (104, 104, 116),
           "Y": (252, 228, 92), "G": (226, 169, 42), "g": (150, 96, 18), "o": (40, 26, 8)}
    return from_rows(rows, pal)


def wheel_slice():
    S = 128
    c = S / 2.0
    r_in, r_out = 20.0, 63.0
    cv = Canvas(S, S)
    for y in range(S):
        for x in range(S):
            dx, dy = x + 0.5 - c, y + 0.5 - c
            d = math.hypot(dx, dy)
            a = math.degrees(math.atan2(dx, -dy))  # 0 = up, clockwise positive
            if r_in <= d <= r_out and -30 <= a <= 30:
                edge_r = min(d - r_in, r_out - d)
                edge_a = (30 - abs(a)) * math.pi / 180 * d
                edge = min(edge_r, edge_a)
                if edge < 1.0:
                    cv.set(x, y, (255, 255, 255, 255))
                elif edge < 2.0:
                    cv.set(x, y, (255, 255, 255, 120))
                else:
                    t = (d - r_in) / (r_out - r_in)
                    v = 200 + int(40 * t)
                    cv.set(x, y, (v, v, v, 150 + int(40 * t)))
    return cv


def glow():
    S = 64
    cv = Canvas(S, S)
    for y in range(S):
        for x in range(S):
            d = math.hypot(x + 0.5 - S / 2, y + 0.5 - S / 2) / (S / 2)
            if d < 1:
                a = (1 - d) ** 2
                cv.set(x, y, (255, 255, 255, clamp(a * 255)))
    return cv


def ring_tex(i):
    S = 128
    c = S / 2.0
    cv = Canvas(S, S)
    W = (255, 255, 255, 255)
    H = (255, 255, 255, 96)

    def ann(r0, r1, alpha=255):
        for y in range(S):
            for x in range(S):
                d = math.hypot(x + 0.5 - c, y + 0.5 - c)
                if r0 <= d <= r1:
                    cv.set(x, y, (255, 255, 255, alpha))

    def line(x0, y0, x1, y1, w=1.6):
        for (x, y) in seg(x0, y0, x1, y1, w, S):
            cv.set(x, y, W)

    def polar(r, deg):
        a = math.radians(deg)
        return c + r * math.cos(a), c + r * math.sin(a)

    if i == 0:
        ann(56, 61)
        ann(61, 62.5, 96)
        for k in range(2):
            pts = [polar(54, -90 + k * 60 + j * 120) for j in range(3)]
            for a, b in zip(pts, pts[1:] + pts[:1]):
                line(a[0], a[1], b[0], b[1], 2.2)
        ann(14, 17)
        for (x, y) in disc(c, c, 6, S):
            cv.set(x, y, W)
    elif i == 1:
        ann(58, 62)
        ann(48, 50)
        for y in range(S):
            for x in range(S):
                d = math.hypot(x + 0.5 - c, y + 0.5 - c)
                a = math.degrees(math.atan2(y + 0.5 - c, x + 0.5 - c)) % 360
                k = a % 15
                long_tick = (a % 30) < 15
                if 50 < d < 58 and min(k, 15 - k) * math.pi / 180 * d < 1.0 and (long_tick or 52 < d < 56):
                    cv.set(x, y, W)
                elif 52.5 < d < 55.5 and abs(k - 7.5) * math.pi / 180 * d < 1.4:
                    cv.set(x, y, H)
    elif i == 2:
        ann(59, 62)
        ann(40, 42)
        ann(57, 59, 96)
        for k in range(8):
            x, y = polar(50, k * 45 + 22.5)
            for p in disc(x, y, 2.4, S):
                cv.set(p[0], p[1], H)
    elif i == 3:
        ann(59, 62)
        pts = [polar(58, -90 + k * 60) for k in range(6)]
        for a, b in zip(pts, pts[1:] + pts[:1]):
            line(a[0], a[1], b[0], b[1], 2.0)
        for p in pts:
            for q in disc(p[0], p[1], 4.2, S):
                cv.set(q[0], q[1], W)
            for q in disc(p[0], p[1], 2.2, S):
                cv.set(q[0], q[1], CLEAR)
    else:
        for y in range(S):
            for x in range(S):
                d = math.hypot(x + 0.5 - c, y + 0.5 - c)
                a = math.degrees(math.atan2(y + 0.5 - c, x + 0.5 - c)) % 15
                if 55 <= d <= 62 and not (6.5 <= a <= 8.5 and d < 60):
                    cv.set(x, y, W)
                elif 50 <= d <= 51.5:
                    cv.set(x, y, W)
                elif 51.5 < d < 55 and a < 1.2:
                    cv.set(x, y, H)
    return cv


RUNES = [
    # strokes on a 16-cell grid; each rune: list of polylines
    [[(5, 2), (5, 14)], [(5, 3), (11, 6)], [(5, 7), (11, 10)]],                       # fehu
    [[(5, 14), (5, 3), (11, 6), (11, 14)]],                                            # uruz
    [[(5, 2), (5, 14)], [(5, 5), (10, 8), (5, 11)]],                                   # thurisaz
    [[(8, 3), (8, 14)], [(3, 8), (8, 3), (13, 8)]],                                    # tiwaz
    [[(5, 14), (5, 2), (10, 5), (5, 8), (11, 14)]],                                    # raidho
    [[(11, 2), (5, 8), (11, 14)]],                                                     # kenaz
    [[(4, 3), (12, 13)], [(12, 3), (4, 13)]],                                          # gebo
    [[(8, 2), (12, 6), (8, 10), (4, 6), (8, 2)], [(8, 10), (4, 14)], [(8, 10), (12, 14)]],  # othala
]


def runes():
    cv = Canvas(128, 16)
    for i, strokes in enumerate(RUNES):
        ox = i * 16
        cells = set()
        for line_pts in strokes:
            pts = [(ox + x, y) for x, y in line_pts]
            cells |= path(pts, 2.0, 128)
        for (x, y) in cells:
            if 0 <= y < 16 and ox <= x < ox + 16:
                cv.set(x, y, (255, 255, 255, 255))
    return cv


def book_open():
    W, H = 192, 128
    cv = Canvas(W, H)
    leather = [(44, 24, 12), (78, 44, 22), (104, 62, 32), (130, 82, 44)]
    parch = [(228, 210, 168), (240, 226, 190), (248, 238, 208), (206, 186, 142), (184, 160, 116)]
    gold = GOLD
    # cover (behind), rounded corners
    for y in range(H):
        for x in range(W):
            corner = min(x, W - 1 - x) + min(y, H - 1 - y)
            if (x < 3 or x > W - 4) and (y < 3 or y > H - 4) and corner < 4:
                continue
            edge = min(x, W - 1 - x, y, H - 1 - y)
            if edge == 0:
                c = leather[0]
            elif edge == 1:
                c = leather[3] if y < H / 2 else leather[2]
            else:
                c = leather[2] if hash01(x, y, 3) > 0.18 else leather[1]
            cv.set(x, y, c)
    # page stack edges (several thin layers at top and bottom), then the pages
    gut = W // 2
    for side in (0, 1):
        x0, x1 = (6, gut - 2) if side == 0 else (gut + 1, W - 7)
        for layer in range(3):
            y0 = 5 + layer
            y1 = H - 7 + (2 - layer)
            for x in range(x0 + (2 - layer), x1 - (2 - layer) + 1):
                cv.set(x, y1, parch[3] if layer % 2 == 0 else parch[4])
        for y in range(8, H - 9):
            for x in range(x0, x1 + 1):
                # light falloff into the gutter (stepped, dithered at each step) + a soft edge
                dg = abs(x + 0.5 - gut)
                dv = min(y - 8, H - 10 - y)
                dither = hash01(x, y, 5) < 0.5
                t = 2
                if dg < 3 or (dg < 4 and dither):
                    t = 4
                elif dg < 7 or (dg < 8 and dither):
                    t = 3
                elif dg < 13 or (dg < 14 and dither) or dv < 1 or min(x - x0, x1 - x) < 1:
                    t = 0
                elif hash01(x, y, 11) < 0.05:
                    t = 1
                cv.set(x, y, parch[t])
        for x in range(x0, x1 + 1):
            cv.set(x, 7, parch[3])
            cv.set(x, H - 9, parch[3])
        # faded ink frame with corner flourishes (text is drawn inside it)
        ink = (196, 172, 124)
        fx0, fx1 = (x0 + 5, x1 - 14) if side == 0 else (x0 + 14, x1 - 5)
        fy0, fy1 = 13, H - 15
        for x in range(fx0 + 3, fx1 - 2):
            cv.set(x, fy0, ink)
            cv.set(x, fy1, ink)
        for y in range(fy0 + 3, fy1 - 2):
            cv.set(fx0, y, ink)
            cv.set(fx1, y, ink)
        for (cx, cy, sx, sy) in ((fx0, fy0, 1, 1), (fx1, fy0, -1, 1), (fx0, fy1, 1, -1), (fx1, fy1, -1, -1)):
            for (i, j) in ((1, 1), (2, 0), (0, 2), (2, 2), (3, 3)):
                cv.set(cx + i * sx, cy + j * sy, ink)
    # spine shadow line
    for y in range(6, H - 6):
        cv.set(gut - 1, y, leather[1])
        cv.set(gut, y, leather[0])
    # gold corner ornaments on the cover
    orn = ["YYYYYG", "YWYG..", "YYg...", "YG....", "Y.....", "G....."]
    for (ox, oy, fx, fy) in ((1, 1, 1, 1), (W - 2, 1, -1, 1), (1, H - 2, 1, -1), (W - 2, H - 2, -1, -1)):
        for j, row in enumerate(orn):
            for i, ch in enumerate(row):
                if ch != ".":
                    cv.set(ox + i * fx, oy + j * fy, gold[ch])
    # ribbon bookmark hanging from the gutter
    for y in range(H - 10, H):
        for x in range(gut + 6, gut + 11):
            if y > H - 3 and (x == gut + 8):
                continue
            col = (150, 30, 40) if x in (gut + 6, gut + 10) else (196, 48, 58)
            cv.set(x, y, col)
    return cv


# ----------------------------------------------------------------------------------------------------------------------
# Contact sheet

def tint(cv, rgb):
    out = Canvas(cv.w, cv.h)
    for i, p in enumerate(cv.px):
        out.px[i] = (p[0] * rgb[0] // 255, p[1] * rgb[1] // 255, p[2] * rgb[2] // 255, p[3])
    return out


def composite(*layers):
    out = Canvas(layers[0].w, layers[0].h)
    for cv in layers:
        for y in range(cv.h):
            for x in range(cv.w):
                p = cv.get(x, y)
                if p[3]:
                    out.over(x, y, p)
    return out


def contact_sheet(path, items, glyph_list, sigils, gui, misc):
    """items: [[Canvas]] rows of 16x16 (6x); glyphs 32x32 (3x); sigils 64 (2x); gui = book, padlock, slice, glow; misc = rings, runes."""
    BG, BG2 = (56, 58, 66), (46, 48, 56)
    pad = 8

    def row_of(cvs, k):
        return [(cv, k) for cv in cvs]

    book, lock, slice_, glow_ = gui
    rows = [row_of(r, 6) for r in items] + [row_of(glyph_list[i:i + 11], 3) for i in range(0, len(glyph_list), 11)] \
        + [row_of(sigils, 2), [(book, 3), (lock, 8)], [(slice_, 2), (glow_, 2), (misc[-1], 4)], row_of(misc[:-1], 2)]
    width = max(sum(cv.w * k + pad for cv, k in r) + pad for r in rows)
    height = sum(max(cv.h * k for cv, k in r) + pad for r in rows) + pad
    sheet = Canvas(width, height)
    for y in range(height):
        for x in range(width):
            sheet.px[y * width + x] = (BG if ((x // 8) + (y // 8)) % 2 else BG2) + (255,)
    y = pad
    for r in rows:
        x = pad
        for cv, k in r:
            for yy in range(cv.h * k):
                for xx in range(cv.w * k):
                    p = cv.get(xx // k, yy // k)
                    if p[3]:
                        sheet.over(x + xx, y + yy, p)
            x += cv.w * k + pad
        y += max(cv.h * k for cv, k in r) + pad
    write_png(path, width, height, sheet.px)


# ----------------------------------------------------------------------------------------------------------------------

def main(argv):
    colours = parse_colours()
    spells = parse_spell_ids()
    if len(spells) != 31:
        sys.exit("make_magic_textures: expected 31 spell ids, got %d" % len(spells))
    missing = [s for s, _ in spells if s not in GLYPHS]
    if missing:
        sys.exit("make_magic_textures: no glyph drawn for " + ", ".join(missing))

    unbound_argb = parse_unbound()
    write_grimoire_items_json(unbound_argb)
    cover = grimoire_cover().save("item/grimoire_cover.png")
    tiers = [overlay(t).save("item/grimoire_t%d.png" % t) for t in range(4)]
    seal = rebinding_seal().save("item/rebinding_seal.png")

    glyphs = []
    for sid, el in spells:
        cv = render_glyph(GLYPHS[sid](), colours[el])
        cv.save("gui/spell/%s.png" % sid)
        glyphs.append(cv)

    keys = ["fire", "water", "wind", "lightning", "earth", "shadow"]
    sigils = [sigil(k, colours[k]).save("gui/magic/sigil_%s.png" % k) for k in keys]
    gui = [book_open().save("gui/magic/book_open.png"), padlock().save("gui/magic/padlock.png"),
           wheel_slice().save("gui/magic/wheel_slice.png"), glow().save("gui/magic/glow.png")]
    misc = [ring_tex(i).save("misc/magic/ring_%d.png" % i) for i in range(5)] + [runes().save("misc/magic/runes.png")]
    print("wrote", ROOT)

    if "--sheet" in argv:
        out = argv[argv.index("--sheet") + 1]
        unbound = argb_rgb("%08X" % unbound_argb)
        item_rows = []
        for rgb in [unbound] + [colours[k] for k in keys]:
            item_rows.append([composite(tint(cover, rgb), t) for t in tiers] + ([seal] if rgb == unbound else []))
        contact_sheet(out, item_rows, glyphs, sigils, gui, misc)
        print("sheet", out)


if __name__ == "__main__":
    main(sys.argv[1:])
