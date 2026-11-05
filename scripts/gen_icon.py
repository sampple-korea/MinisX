#!/usr/bin/env python3
"""Regenerates the MinisX launcher icon: a white, double-lined "M" on a black squircle.

Writes (under src/android/app/src/main/res):
  mipmap-*/ic_launcher_foreground{,_dark,_light}.png   adaptive-icon foregrounds (108dp layer)
  mipmap-night-*/ic_launcher_foreground.png
  mipmap-*/ic_launcher.png, mipmap-night-*/ic_launcher.png   legacy full icons
  drawable/ic_launcher_monochrome.xml                  Android 13 themed-icon layer
and a 1024px preview next to this script's output dir argument (default: ./minisx-icon-preview.png).

Needs: pip install pillow shapely
Usage: python3 scripts/gen_icon.py [preview.png]
"""
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFilter
from shapely.geometry import LineString, Polygon
from shapely.ops import unary_union

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "android", "app", "src", "main", "res")

# --- the glyph, in a 0..100 box centred on (50, 50) ----------------------------------------
# One continuous path (left leg, valley, right leg) stroked with sharp mitred corners; a thin
# slit along the same path turns the stroke into two parallel lines, like the split strokes of
# the X logo. The legs are slightly splayed and the valley is deep so it reads as an "M", not "W".
STROKE = 15.0
SLIT = 3.4
PATH = [(24, 84), (24, 18), (50, 56), (76, 18), (76, 84)]


def glyph():
    line = LineString(PATH)
    body = line.buffer(STROKE / 2, cap_style=2, join_style=2, mitre_limit=4)
    slit = line.buffer(SLIT / 2, cap_style=2, join_style=2, mitre_limit=4)
    # extend the slit past both leg ends so the legs split cleanly
    for (x0, y0), (x1, y1) in ((PATH[0], (PATH[0][0], PATH[0][1] + 4)), (PATH[-1], (PATH[-1][0], PATH[-1][1] + 4))):
        slit = unary_union([slit, LineString([(x0, y0), (x1, y1)]).buffer(SLIT / 2, cap_style=2)])
    g = body.difference(slit)
    # centre the bounding box on (50, 50)
    minx, miny, maxx, maxy = g.bounds
    from shapely.affinity import translate
    return translate(g, 50 - (minx + maxx) / 2, 50 - (miny + maxy) / 2)


def polys(g):
    return list(g.geoms) if g.geom_type == "MultiPolygon" else [g]


def draw_glyph(size, scale, color, supersample=4):
    """Glyph on a transparent size x size canvas; `scale` = fraction of the canvas the 100-box fills."""
    s = size * supersample
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    k = s * scale / 100.0
    off = s * (1 - scale) / 2
    for p in polys(glyph()):
        d.polygon([(off + x * k, off + y * k) for x, y in p.exterior.coords], fill=color)
        for hole in p.interiors:
            d.polygon([(off + x * k, off + y * k) for x, y in hole.coords], fill=(0, 0, 0, 0))
    return img.resize((size, size), Image.LANCZOS)


def squircle_mask(size, supersample=4, n=5.0):
    s = size * supersample
    m = Image.new("L", (s, s), 0)
    px = m.load()
    r = s / 2
    for y in range(s):
        dy = abs((y + 0.5 - r) / r)
        # superellipse |x|^n + |y|^n <= 1  ->  x extent for this row
        t = 1 - dy ** n
        if t <= 0:
            continue
        ext = t ** (1 / n)
        x0, x1 = int(r - ext * r), int(r + ext * r)
        for x in range(max(x0, 0), min(x1, s)):
            px[x, y] = 255
    return m.resize((size, size), Image.LANCZOS)


def background(size):
    """Near-black with a soft lift in the upper middle, like the reference icons."""
    img = Image.new("RGB", (size, size), (0, 0, 0))
    glow = Image.new("L", (size, size), 0)
    ImageDraw.Draw(glow).ellipse([size * 0.1, -size * 0.25, size * 0.9, size * 0.55], fill=70)
    glow = glow.filter(ImageFilter.GaussianBlur(size * 0.18))
    return Image.composite(Image.new("RGB", (size, size), (52, 53, 58)), img, glow)


def full_icon(size):
    bg = background(size).convert("RGBA")
    bg.alpha_composite(draw_glyph(size, 0.58, (244, 244, 246, 255)))
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
    g = glyph()
    far = max(math.hypot(x - 50, y - 50) for p in polys(g) for x, y in p.exterior.coords)
    k = 31.0 / far
    parts = []
    for p in polys(g):
        for ring in [p.exterior, *p.interiors]:
            pts = [(54 + (x - 50) * k, 54 + (y - 50) * k) for x, y in list(ring.coords)[:-1]]
            parts.append("M " + " L ".join(f"{x:.2f},{y:.2f}" for x, y in pts) + " Z")
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!--\n  Monochrome layer for Android 13+ themed icons: the MinisX \"M\" (see scripts/gen_icon.py),\n"
        "  filled with the even-odd rule so the slit stays open. Sized to stay inside the circular\n"
        "  mask (radius 33 around the centre). Generated - edit scripts/gen_icon.py instead.\n-->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n    android:height="108dp"\n    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
        '    <path\n        android:fillColor="#FFFFFFFF"\n        android:fillType="evenOdd"\n'
        f'        android:pathData="{" ".join(parts)}" />\n</vector>\n'
    )


def main():
    white, black = (255, 255, 255, 255), (12, 12, 14, 255)
    for name, d in DENS.items():
        fg = round(108 * d)
        leg = round(48 * d)
        # the launcher layer is inset 15% by the adaptive-icon xml, so the glyph fills most of the layer
        save(draw_glyph(fg, 0.80, white), f"mipmap-{name}", "ic_launcher_foreground.png")
        save(draw_glyph(fg, 0.80, white), f"mipmap-night-{name}", "ic_launcher_foreground.png")
        save(draw_glyph(fg, 0.80, white), f"mipmap-{name}", "ic_launcher_foreground_dark.png")
        save(draw_glyph(fg, 0.80, black), f"mipmap-{name}", "ic_launcher_foreground_light.png")
        icon = full_icon(leg)
        save(icon, f"mipmap-{name}", "ic_launcher.png")
        save(icon, f"mipmap-night-{name}", "ic_launcher.png")
    with open(os.path.join(RES, "drawable", "ic_launcher_monochrome.xml"), "w", encoding="utf-8") as f:
        f.write(monochrome_xml())
    out = sys.argv[1] if len(sys.argv) > 1 else "minisx-icon-preview.png"
    full_icon(1024).save(out)
    print("wrote icon resources; preview:", out)


if __name__ == "__main__":
    main()
