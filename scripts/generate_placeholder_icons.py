#!/usr/bin/env python3
"""Generate generic placeholder icons for the few iOS surfaces the App Store has no entry for.

These bundle ids (Settings, App Store) return nothing from the iTunes Lookup API, so
`fetch_shipped_icons.py` can't produce them — yet they still send ANCS notifications, so we ship a
neutral, generic placeholder rather than a bare monogram. The art here is intentionally generic
geometry (a gear, a simple tile) — NOT Apple's trademarked icons. Replace freely.

Output: app/src/main/assets/appicons/<lowercased-bundle-id>.webp

Usage:  python3 scripts/generate_placeholder_icons.py
Requires: Pillow  (pip install Pillow)
"""
from __future__ import annotations

import argparse
import math
import os

from PIL import Image, ImageDraw

SIZE = 256
RADIUS = 56  # rounded-square corner radius
HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.normpath(os.path.join(HERE, "..", "app", "src", "main", "assets", "appicons"))


def rounded_tile(bg: tuple[int, int, int]) -> tuple[Image.Image, ImageDraw.ImageDraw]:
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, SIZE - 1, SIZE - 1], radius=RADIUS, fill=bg + (255,))
    return img, d


def draw_gear(d: ImageDraw.ImageDraw, fg=(255, 255, 255, 255)) -> None:
    cx = cy = SIZE / 2
    r_out, r_in, teeth, tooth = 78, 60, 8, 26
    for i in range(teeth):  # rectangular teeth around the ring
        a = math.radians(i * 360 / teeth)
        x, y = cx + math.cos(a) * r_out, cy + math.sin(a) * r_out
        d.regular_polygon((x, y, tooth / 2), n_sides=4, rotation=i * 360 / teeth, fill=fg)
    d.ellipse([cx - r_in, cy - r_in, cx + r_in, cy + r_in], fill=fg)
    hole = 26
    d.ellipse([cx - hole, cy - hole, cx + hole, cy + hole], fill=(0, 0, 0, 0))


def draw_store(d: ImageDraw.ImageDraw, fg=(255, 255, 255, 255)) -> None:
    cx = cy = SIZE / 2
    # a generic "tile / card" glyph: a white rounded square outline
    d.rounded_rectangle([cx - 60, cy - 60, cx + 60, cy + 60], radius=20, outline=fg, width=14)
    d.line([cx - 30, cy, cx + 30, cy], fill=fg, width=14)
    d.line([cx, cy - 30, cx, cy + 30], fill=fg, width=14)


def draw_nfc(d: ImageDraw.ImageDraw, fg=(255, 255, 255, 255)) -> None:
    cx = cy = SIZE / 2
    # generic contactless "waves" emanating from a source dot — not the trademarked N mark
    for r in (34, 64, 94):
        d.arc([cx - r, cy - r, cx + r, cy + r], start=-52, end=52, fill=fg, width=16)
    d.ellipse([cx - 80, cy - 13, cx - 54, cy + 13], fill=fg)


# Curated store-absent surfaces. The first two ship as REAL icons (see app/src/main/assets/appicons/);
# they stay here only as a bootstrap fallback and are skipped when a file already exists (use --force to redraw).
PLACEHOLDERS = [
    ("com.apple.preferences", (142, 142, 147), draw_gear),    # Settings — iOS system gray
    ("com.apple.appstore", (10, 132, 255), draw_store),        # App Store — iOS system blue
    ("com.apple.barcodesupport.nfc", (88, 86, 214), draw_nfc), # NFC tag reader — iOS system indigo
]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--force", action="store_true", help="redraw even if the icon file already exists")
    args = ap.parse_args()

    os.makedirs(OUT_DIR, exist_ok=True)
    for stem, bg, glyph in PLACEHOLDERS:
        path = os.path.join(OUT_DIR, f"{stem}.webp")
        if os.path.exists(path) and not args.force:
            print(f"skip  {os.path.basename(path)} (exists)")
            continue
        img, d = rounded_tile(bg)
        glyph(d)
        img.save(path, "WEBP", quality=90, method=6)
        print(f"wrote {path} ({os.path.getsize(path)} bytes)")


if __name__ == "__main__":
    main()
