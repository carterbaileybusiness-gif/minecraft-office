#!/usr/bin/env python3
"""Writes the magic GameTest arena structure with only the standard library (deterministic: same bytes every run).

Run from the repo root:  python3 tools/make_magic_arena.py

Output: mod/src/gametest/resources/data/office-test/structure/magic_arena.nbt (gzipped structure-template NBT), used as
"office-test:magic_arena" by MagicTestKit.ARENA. The arena is 32 x 24 x 32 (x, y, z): stone at y 0..3, air above (air is
stored explicitly so placing the arena always clears it). Tests stand the caster at (16, 4, 4) facing +Z.
"""
import gzip
import io
import os
import struct

OUT = os.path.join("mod", "src", "gametest", "resources", "data", "office-test", "structure", "magic_arena.nbt")
SIZE = (32, 24, 32)
FLOOR_TOP = 3
DATA_VERSION = 5023  # Minecraft 26.3 world_version (version.json in the game jar)

TAG_END, TAG_INT, TAG_STRING, TAG_LIST, TAG_COMPOUND = 0, 3, 8, 9, 10


def name(out, s):
    b = s.encode("utf-8")
    out.write(struct.pack(">H", len(b)))
    out.write(b)


def payload(out, tag, value):
    if tag == TAG_INT:
        out.write(struct.pack(">i", value))
    elif tag == TAG_STRING:
        name(out, value)
    elif tag == TAG_LIST:
        elem, items = value
        out.write(struct.pack(">bi", elem if items else TAG_END, len(items)))
        for item in items:
            payload(out, elem, item)
    elif tag == TAG_COMPOUND:
        for key, (t, v) in value:
            out.write(struct.pack(">b", t))
            name(out, key)
            payload(out, t, v)
        out.write(struct.pack(">b", TAG_END))
    else:
        raise ValueError(tag)


def ints(*xs):
    return (TAG_LIST, (TAG_INT, list(xs)))


def main():
    palette = [[("id", (TAG_STRING, "minecraft:air"))], [("id", (TAG_STRING, "minecraft:stone"))]]  # 26.3 block states: id + properties
    blocks = []
    sx, sy, sz = SIZE
    for y in range(sy):
        for z in range(sz):
            for x in range(sx):
                blocks.append([("pos", ints(x, y, z)), ("state", (TAG_INT, 1 if y <= FLOOR_TOP else 0))])
    root = [
        ("DataVersion", (TAG_INT, DATA_VERSION)),
        ("size", ints(*SIZE)),
        ("palette", (TAG_LIST, (TAG_COMPOUND, palette))),
        ("blocks", (TAG_LIST, (TAG_COMPOUND, blocks))),
        ("entities", (TAG_LIST, (TAG_COMPOUND, []))),
    ]
    raw = io.BytesIO()
    raw.write(struct.pack(">b", TAG_COMPOUND))
    name(raw, "")
    payload(raw, TAG_COMPOUND, root)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "wb") as f:
        f.write(gzip.compress(raw.getvalue(), compresslevel=9, mtime=0))
    print("wrote", OUT, os.path.getsize(OUT), "bytes,", len(blocks), "blocks")


if __name__ == "__main__":
    main()
