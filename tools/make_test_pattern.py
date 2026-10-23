#!/usr/bin/env python3
"""Draws a 1920x1080 fake-desktop test pattern for judging hologram sharpness/readability in game.

Needs Pillow and the macOS system fonts (dev tool only; the PNG is committed).
Run from the repo root:  python3 tools/make_test_pattern.py
Writes mod/src/client/resources/assets/office/textures/misc/test_pattern.png (+ .mcmeta: linear filtering).
In game: /hologramtest toggles it onto every hologram you can see (until Task 7's live textures exist).
"""
import json
import os

from PIL import Image, ImageDraw, ImageFont

OUT = os.path.join("mod", "src", "client", "resources", "assets", "office", "textures", "misc")
W, H = 1920, 1080
FONT = "/System/Library/Fonts/HelveticaNeue.ttc"
MONO = "/System/Library/Fonts/Menlo.ttc"


def font(size, path=FONT):
    return ImageFont.truetype(path, size)


def main():
    img = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(img)
    # wallpaper: deep blue to teal
    for y in range(H):
        t = y / H
        d.line([(0, y), (W, y)], fill=(int(18 + 10 * t), int(40 + 70 * t), int(92 + 40 * t)))
    # menu bar
    d.rectangle([0, 0, W, 30], fill=(236, 236, 238))
    d.text((56, 6), "Finder   File   Edit   View   Go   Window   Help", font=font(16), fill=(20, 20, 20))
    d.text((W - 230, 6), "Thu 23 Oct  9:41", font=font(16), fill=(20, 20, 20))

    # a document window with text at common UI sizes
    x0, y0, x1, y1 = 120, 90, 1140, 900
    d.rounded_rectangle([x0 + 6, y0 + 8, x1 + 6, y1 + 8], 12, fill=(10, 20, 40))
    d.rounded_rectangle([x0, y0, x1, y1], 12, fill=(252, 252, 252))
    d.rounded_rectangle([x0, y0, x1, y0 + 44], 12, fill=(232, 232, 234))
    d.rectangle([x0, y0 + 30, x1, y0 + 44], fill=(232, 232, 234))
    for i, c in enumerate([(255, 95, 87), (254, 188, 46), (40, 200, 64)]):
        d.ellipse([x0 + 16 + i * 22, y0 + 15, x0 + 30 + i * 22, y0 + 29], fill=c)
    d.text((x0 + 420, y0 + 13), "Readability test.txt", font=font(15), fill=(60, 60, 60))
    y = y0 + 70
    para = "The quick brown fox jumps over the lazy dog. 0123456789"
    for size in (11, 12, 13, 14, 16, 18, 24, 32):
        d.text((x0 + 30, y), f"{size} px  {para}", font=font(size), fill=(25, 25, 25))
        y += int(size * 1.9)
    y += 10
    for size in (12, 14):
        d.text((x0 + 30, y), f"{size} px mono  for (int i = 0; i < n; i++) total += i;", font=font(size, MONO), fill=(40, 40, 120))
        y += int(size * 1.9)

    # resolution wedges: 1, 2, 3 px line pairs
    wx = 1240
    d.text((wx, 100), "Line pairs: 1 px / 2 px / 3 px", font=font(18), fill=(235, 245, 255))
    for i, p in enumerate((1, 2, 3)):
        bx = wx + i * 200
        d.rectangle([bx, 140, bx + 160, 300], fill=(255, 255, 255))
        for k in range(0, 160, 2 * p):
            d.rectangle([bx + k, 140, bx + k + p - 1, 300], fill=(0, 0, 0))
    # colour bars
    colors = [(255, 255, 255), (255, 255, 0), (0, 255, 255), (0, 255, 0), (255, 0, 255), (255, 0, 0), (0, 0, 255), (0, 0, 0)]
    for i, c in enumerate(colors):
        d.rectangle([wx + i * 75, 340, wx + i * 75 + 74, 460], fill=c)
    # grey ramp
    for i in range(16):
        g = int(i * 255 / 15)
        d.rectangle([wx + i * 37, 480, wx + i * 37 + 36, 540], fill=(g, g, g))
    # a dock
    d.rounded_rectangle([660, 990, 1260, 1066], 18, fill=(225, 230, 240))
    for i in range(8):
        hue = [(70, 140, 255), (255, 150, 60), (90, 200, 120), (230, 80, 90), (160, 110, 230), (240, 200, 70), (60, 190, 200), (120, 120, 130)][i]
        d.rounded_rectangle([680 + i * 72, 1000, 680 + i * 72 + 56, 1056], 12, fill=hue)
    # border + corner marks so cropping is obvious
    d.rectangle([0, 0, W - 1, H - 1], outline=(255, 64, 64), width=2)
    for cx, cy in ((0, 0), (W - 40, 0), (0, H - 40), (W - 40, H - 40)):
        d.rectangle([cx, cy, cx + 39, cy + 39], outline=(255, 64, 64), width=4)

    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, "test_pattern.png")
    img.save(path, optimize=True)
    with open(path + ".mcmeta", "w") as f:
        json.dump({"texture": {"blur": True, "clamp": True}}, f, indent=2)
        f.write("\n")
    print("wrote", path, os.path.getsize(path), "bytes")


if __name__ == "__main__":
    main()
