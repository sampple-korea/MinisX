#!/usr/bin/env python3
"""Regenerates the MinisX launcher icon: a wide, flat-topped "M" woven from two gradient pieces
(a back "M" with the left leg and a long diagonal bar in front, separated by a black gap) on a
black squircle.

Writes (under src/android/app/src/main/res):
  mipmap-*/ic_launcher_foreground{,_dark,_light}.png   adaptive-icon foregrounds (108dp layer)
  mipmap-night-*/ic_launcher_foreground.png
  mipmap-*/ic_launcher.png, mipmap-night-*/ic_launcher.png   legacy full icons
  drawable/ic_launcher_monochrome.xml                  Android 13 themed-icon layer

Needs: pip install pillow shapely
Usage: python3 scripts/gen_icon.py [--palette violet|aurora|mono] [preview.png]
"""
import math
import os
import sys

from PIL import Image, ImageDraw
from shapely.geometry import LineString, box
from shapely.affinity import translate
from shapely.ops import unary_union

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "android", "app", "src", "main", "res")

# (back piece gradient, front piece gradient); gradient = (colour at start, colour at end, angle in degrees)
PALETTES = {
    "violet": (((120, 64, 240), (236, 96, 170), 60), ((255, 110, 150), (255, 172, 110), 90)),
    "aurora": (((44, 108, 232), (36, 176, 214), 60), ((64, 228, 216), (30, 198, 200), 90)),
    "mono": (((222, 226, 232), (150, 156, 168), 90), ((255, 255, 255), (190, 196, 206), 90)),
}

# --- the glyph, in a 0..100 box ---------------------------------------------------------------
# Flat top and bottom (pointed tops made the letter look narrower than it is). Back piece: left leg
# plus a diagonal falling to the lower right. Front piece: a long diagonal rising to the top right,
# then the right leg. The front piece is cut out of the back piece with a gap, so it reads as passing
# in front of it, like the strokes of the X logo.
W = 20.0
GAP = 3.8
XL, XR = 20.0, 80.0
TOP, BOT = 12.0, 88.0


def _stroke(pts):
    return LineString(pts).buffer(W / 2, cap_style=2, join_style=2, mitre_limit=6)


def pieces():
    clip = box(-50, TOP, 150, BOT)
    front = _stroke([(XL - 11, BOT + 20), (XR, TOP - 6), (XR, BOT + 20)]).intersection(clip)
    back = _stroke([(XL, BOT + 20), (XL, TOP - 6), (XR - 5, BOT + 10), (XR + 4, BOT + 20)]).intersection(clip)
    back = back.difference(front.buffer(GAP, join_style=2, mitre_limit=6))
    # morphological opening drops the tiny teeth the cut leaves behind
    back = back.buffer(-2.2, join_style=2, mitre_limit=8).buffer(2.2, join_style=2, mitre_limit=8)
    parts = [p for p in (back.geoms if back.geom_type == "MultiPolygon" else [back]) if p.area >= 40]
    back = unary_union(parts)
    allg = unary_union([back, front])
    minx, miny, maxx, maxy = allg.bounds
    dx, dy = 50 - (minx + maxx) / 2, 50 - (miny + maxy) / 2
    return translate(back, dx, dy), translate(front, dx, dy)


def polys(g):
    return list(g.geoms) if g.geom_type == "MultiPolygon" else [g]


def far(g):
    return max(math.hypot(x - 50, y - 50) for p in polys(g) for x, y in p.exterior.coords)


def gradient(size, spec):
    c1, c2, ang = spec
    ca, sa = math.cos(math.radians(ang)), math.sin(math.radians(ang))
    img = Image.new("RGB", (size, size))
    px = img.load()
    for y in range(size):
        for x in range(size):
            t = min(1.0, max(0.0, ((x / size - 0.5) * ca + (y / size - 0.5) * sa) + 0.5))
            px[x, y] = tuple(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(3))
    return img


def mask(g, size, k, ss=3):
    s = size * ss
    m = Image.new("L", (s, s), 0)
    d = ImageDraw.Draw(m)
    c = s / 2
    for p in polys(g):
        d.polygon([(c + (x - 50) * k * ss, c + (y - 50) * k * ss) for x, y in p.exterior.coords], fill=255)
        for h in p.interiors:
            d.polygon([(c + (x - 50) * k * ss, c + (y - 50) * k * ss) for x, y in h.coords], fill=0)
    return m.resize((size, size), Image.LANCZOS)


def draw_glyph(size, reach, palette):
    """Glyph on a transparent canvas; `reach` = distance of the farthest corner from the centre,
    as a fraction of the canvas."""
    back, front = pieces()
    px_per_unit = reach * size / far(unary_union([back, front]))
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    for g, spec in ((back, PALETTES[palette][0]), (front, PALETTES[palette][1])):
        out.paste(gradient(size, spec).convert("RGBA"), (0, 0), mask(g, size, px_per_unit))
    return out


def squircle_mask(size, supersample=4, n=5.0):
    s = size * supersample
    m = Image.new("L", (s, s), 0)
    px = m.load()
    r = s / 2
    for y in range(s):
        dy = abs((y + 0.5 - r) / r)
        t = 1 - dy ** n
        if t <= 0:
            continue
        ext = t ** (1 / n)
        x0, x1 = int(r - ext * r), int(r + ext * r)
        for x in range(max(x0, 0), min(x1, s)):
            px[x, y] = 255
    return m.resize((size, size), Image.LANCZOS)


# Background: diagonal gradient from a deep violet (top left) to near-black (bottom right).
# Keep in sync with res/drawable/ic_launcher_bg_gradient.xml (the adaptive-icon background).
BG_START, BG_MID, BG_END = (58, 36, 104), (20, 13, 36), (5, 5, 6)


def background(size):
    img = Image.new("RGB", (size, size))
    px = img.load()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2.0 * (size - 1))  # 0 top-left .. 1 bottom-right
            if t < 0.5:
                u = t / 0.5
                c = tuple(BG_START[i] + (BG_MID[i] - BG_START[i]) * u for i in range(3))
            else:
                u = (t - 0.5) / 0.5
                c = tuple(BG_MID[i] + (BG_END[i] - BG_MID[i]) * u for i in range(3))
            px[x, y] = tuple(int(v) for v in c)
    return img


def full_icon(size, palette):
    bg = background(size).convert("RGBA")
    bg.alpha_composite(draw_glyph(size, 0.37, palette))
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(bg, (0, 0), squircle_mask(size))
    return out


DENS = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def save(img, *parts):
    path = os.path.normpath(os.path.join(RES, *parts))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)


def monochrome_xml():
    # themed icons are clipped to a circle of radius 33 around (54, 54) in the 108 viewport
    back, front = pieces()
    k = 31.0 / far(unary_union([back, front]))
    parts = []
    for g in (back, front):
        for p in polys(g):
            for ring in [p.exterior, *p.interiors]:
                pts = [(54 + (x - 50) * k, 54 + (y - 50) * k) for x, y in list(ring.coords)[:-1]]
                parts.append("M " + " L ".join(f"{x:.2f},{y:.2f}" for x, y in pts) + " Z")
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!--\n  Monochrome layer for Android 13+ themed icons: the MinisX \"M\" (see scripts/gen_icon.py),\n"
        "  both pieces with the gap between them. Sized to stay inside the circular mask (radius 33\n"
        "  around the centre). Generated - edit scripts/gen_icon.py instead.\n-->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n    android:height="108dp"\n    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
        '    <path\n        android:fillColor="#FFFFFFFF"\n        android:fillType="evenOdd"\n'
        f'        android:pathData="{" ".join(parts)}" />\n</vector>\n'
    )


def main():
    args = sys.argv[1:]
    palette = "violet"
    if "--palette" in args:
        i = args.index("--palette")
        palette = args[i + 1]
        del args[i : i + 2]
    preview = args[0] if args else "minisx-icon-preview.png"
    for name, d in DENS.items():
        fg = round(108 * d)
        leg = round(48 * d)
        # the launcher layer is inset 15% by the adaptive-icon xml, so 0.44 of the layer here is
        # 0.31 on screen: inside the 66dp circular safe zone.
        glyph = draw_glyph(fg, 0.44, palette)
        for folder, fname in ((f"mipmap-{name}", "ic_launcher_foreground.png"),
                              (f"mipmap-night-{name}", "ic_launcher_foreground.png"),
                              (f"mipmap-{name}", "ic_launcher_foreground_dark.png"),
                              (f"mipmap-{name}", "ic_launcher_foreground_light.png")):
            save(glyph, folder, fname)
        icon = full_icon(leg, palette)
        save(icon, f"mipmap-{name}", "ic_launcher.png")
        save(icon, f"mipmap-night-{name}", "ic_launcher.png")
    with open(os.path.join(RES, "drawable", "ic_launcher_monochrome.xml"), "w", encoding="utf-8") as f:
        f.write(monochrome_xml())
    hexc = lambda c: "#FF%02X%02X%02X" % c
    with open(os.path.join(RES, "drawable", "ic_launcher_bg_gradient.xml"), "w", encoding="utf-8") as f:
        f.write(
            '<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated by scripts/gen_icon.py: adaptive-icon background. -->\n'
            '<shape xmlns:android="http://schemas.android.com/apk/res/android">\n'
            f'    <gradient android:type="linear" android:angle="315" android:startColor="{hexc(BG_START)}"\n'
            f'        android:centerColor="{hexc(BG_MID)}" android:endColor="{hexc(BG_END)}" />\n</shape>\n'
        )
    full_icon(1024, palette).save(preview)
    print("wrote icon resources; preview:", preview)


if __name__ == "__main__":
    main()
