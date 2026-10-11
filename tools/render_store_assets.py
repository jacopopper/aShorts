#!/usr/bin/env python3
"""Render Play Store graphics from the same geometry as the adaptive launcher icon.

Outputs:
  fastlane/metadata/android/en-US/images/icon.png          512x512 (full square; Play masks it)
  fastlane/metadata/android/en-US/images/featureGraphic.png 1024x500 banner
  play/icon-preview.png   the icon under circle / squircle / rounded-square launcher masks

Keep the shapes in sync with res/drawable/ic_launcher_{background,foreground}.xml (108 viewport).
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "fastlane" / "metadata" / "android" / "en-US" / "images"
PREVIEW = ROOT / "play" / "icon-preview.png"
SS = 4  # supersampling

STOPS = [(0.0, (0x3F, 0xA3, 0x74)), (0.55, (0x22, 0x70, 0x4F)), (1.0, (0x0E, 0x3B, 0x2B))]
FONT_BOLD = "/usr/share/fonts/truetype/open-sans/OpenSans-Bold.ttf"
FONT_REGULAR = "/usr/share/fonts/truetype/open-sans/OpenSans-Regular.ttf"


def lerp_stops(t):
    for (t0, c0), (t1, c1) in zip(STOPS, STOPS[1:]):
        if t <= t1:
            k = (t - t0) / (t1 - t0)
            return tuple(round(a + (b - a) * k) for a, b in zip(c0, c1))
    return STOPS[-1][1]


def diagonal_gradient(w, h):
    img = Image.new("RGB", (w, h))
    px = img.load()
    span = w + h
    lut = [lerp_stops(i / (span - 1)) for i in range(span)]
    for y in range(h):
        for x in range(w):
            px[x, y] = lut[x + y]
    return img


def draw_mark(img, ox, oy, scale):
    """Draw the foreground glyph; (ox, oy) is the 108-viewport origin, scale = px per unit."""
    s = lambda v: v * scale
    front_box = [ox + s(33), oy + s(33), ox + s(71), oy + s(83)]

    # faint back card, minus the front card's outline (matches the even-odd vector path)
    back = Image.new("L", img.size, 0)
    bd = ImageDraw.Draw(back)
    bd.rounded_rectangle([ox + s(41), oy + s(29), ox + s(77), oy + s(79)], radius=s(8),
                         fill=round(255 * 0.32))
    bd.rounded_rectangle(front_box, radius=s(9), fill=0)
    img.paste((255, 255, 255), (0, 0), back)

    front = Image.new("L", img.size, 0)
    fd = ImageDraw.Draw(front)
    fd.rounded_rectangle(front_box, radius=s(9), fill=255)
    for x0 in (42, 54):  # pause bars cut out of the card
        fd.rounded_rectangle([ox + s(x0), oy + s(48), ox + s(x0 + 8), oy + s(68)],
                             radius=s(2.5), fill=0)
    img.paste((255, 255, 255), (0, 0), front)


def icon(size):
    big = size * SS
    img = diagonal_gradient(big, big).convert("RGBA")
    draw_mark(img, 0, 0, big / 108)
    return img.resize((size, size), Image.LANCZOS)


def masked(img, kind):
    size = img.width * SS
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    if kind == "circle":
        d.ellipse([0, 0, size - 1, size - 1], fill=255)
    elif kind == "squircle":
        d.rounded_rectangle([0, 0, size - 1, size - 1], radius=size * 0.32, fill=255)
    else:
        d.rounded_rectangle([0, 0, size - 1, size - 1], radius=size * 0.16, fill=255)
    m = m.resize(img.size, Image.LANCZOS)
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.paste(img, (0, 0), m)
    return out


def feature_graphic():
    w, h = 1024 * 2, 500 * 2
    img = diagonal_gradient(w // 4, h // 4).resize((w, h), Image.BICUBIC).convert("RGBA")
    # the mark on the left, about 300px tall in the final image
    scale = 2 * 300 / 54  # card spans y 29..83 (54 units)
    draw_mark(img, 2 * 70 - scale * 33, h / 2 - scale * 56, scale)
    d = ImageDraw.Draw(img)
    title = ImageFont.truetype(FONT_BOLD, 2 * 92)
    tag = ImageFont.truetype(FONT_REGULAR, 2 * 36)
    x = 2 * 420
    eyebrow = ImageFont.truetype(FONT_BOLD, 2 * 26)
    cx = x
    for ch in "REELS BLOCKER":  # PIL has no letter-spacing; track it by hand
        d.text((cx, h / 2 - 2 * 128), ch, font=eyebrow, fill=(255, 255, 255, 200), anchor="ls")
        cx += d.textlength(ch, font=eyebrow) + 2 * 5
    d.text((x, h / 2 - 2 * 20), "aShorts", font=title, fill="white", anchor="ls")
    d.text((x, h / 2 + 2 * 50), "Keep the app, lose the scroll.", font=tag,
           fill="white", anchor="ls")
    values = ImageFont.truetype(FONT_REGULAR, 2 * 24)
    d.text((x, h / 2 + 2 * 104), "Truly free  ·  Lightweight  ·  Private", font=values,
           fill=(255, 255, 255, 190), anchor="ls")
    return img.resize((1024, 500), Image.LANCZOS).convert("RGB")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    base = icon(512)
    base.convert("RGB").save(OUT / "icon.png")
    feature_graphic().save(OUT / "featureGraphic.png")

    # The launcher shows the middle 72/108 of the adaptive layers, then masks it.
    crop = icon(432).crop((72, 72, 360, 360)).resize((192, 192), Image.LANCZOS)
    preview = Image.new("RGBA", (192 * 3 + 80, 232), (245, 245, 242, 255))
    for i, kind in enumerate(("circle", "squircle", "rounded")):
        preview.alpha_composite(masked(crop, kind), (20 + i * 212, 20))
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    preview.convert("RGB").save(PREVIEW)
    print("wrote", OUT / "icon.png", OUT / "featureGraphic.png", PREVIEW)


if __name__ == "__main__":
    main()
