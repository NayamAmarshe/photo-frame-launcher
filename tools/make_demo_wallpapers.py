#!/usr/bin/env python3
"""
Makes a set of original, generated wallpapers for demos and screenshots.
Nothing here is copied from anywhere, so the pictures are free to use.

    pip install pillow numpy
    python3 tools/make_demo_wallpapers.py demo/wallpapers
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

RNG = np.random.default_rng(7)


# ---------------------------------------------------------------- helpers
def lerp(a, b, t):
    return a + (b - a) * t


def hexrgb(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], dtype=np.float32)


def vgrad(w, h, stops):
    """Vertical gradient. stops = [(position 0..1, '#rrggbb'), ...]"""
    ys = np.linspace(0, 1, h, dtype=np.float32)
    out = np.zeros((h, w, 3), np.float32)
    pos = [p for p, _ in stops]
    cols = [hexrgb(c) for _, c in stops]
    for ch in range(3):
        col = np.interp(ys, pos, [c[ch] for c in cols])
        out[:, :, ch] = col[:, None]
    return out


def fbm_field(w, h, scale=6, octaves=5, seed=0):
    """Soft cloud-like noise in 0..1."""
    rng = np.random.default_rng(seed)
    acc = np.zeros((h, w), np.float32)
    amp, total = 1.0, 0.0
    for o in range(octaves):
        gw, gh = max(2, int(scale * 2 ** o)), max(2, int(scale * 2 ** o * h / w))
        small = rng.random((gh, gw)).astype(np.float32)
        big = np.asarray(Image.fromarray((small * 255).astype(np.uint8)).resize((w, h), Image.BICUBIC), np.float32) / 255
        acc += big * amp
        total += amp
        amp *= 0.5
    return acc / total


def ridge(w, base, amp, seed, detail=5):
    """A wiggly line across the picture (mountain or dune top)."""
    rng = np.random.default_rng(seed)
    x = np.linspace(0, 1, w, dtype=np.float32)
    y = np.zeros(w, np.float32)
    for k in range(1, detail + 1):
        y += rng.uniform(0.4, 1.0) / k * np.sin(2 * math.pi * (k * rng.uniform(0.6, 1.6)) * x + rng.uniform(0, 6.28))
    y = y / max(1e-3, np.abs(y).max())
    return base + amp * y


def fill_below(img, line, color, soft=1.5):
    """Paint everything below a ridge line with a colour (array or tuple)."""
    h, w, _ = img.shape
    ys = np.arange(h, dtype=np.float32)[:, None]
    mask = np.clip((ys - line[None, :]) / soft + 0.5, 0, 1)[:, :, None]
    col = np.asarray(color, np.float32)
    if col.ndim == 1:
        col = col[None, None, :]
    img[:] = img * (1 - mask) + col * mask


def glow(img, cx, cy, radius, color, strength=1.0):
    h, w, _ = img.shape
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    d = np.sqrt((xs - cx) ** 2 + (ys - cy) ** 2) / radius
    g = np.exp(-d * d * 2.2)[:, :, None] * strength
    img += g * hexrgb(color)[None, None, :]


def stars(img, count, seed, ymax=0.7):
    h, w, _ = img.shape
    rng = np.random.default_rng(seed)
    layer = np.zeros((h, w), np.float32)
    for _ in range(count):
        x, y = rng.integers(0, w), int(rng.random() ** 1.5 * h * ymax)
        layer[y, x] = rng.uniform(0.3, 1.0)
    big = np.asarray(Image.fromarray((layer * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.1)), np.float32) / 255
    img += (big * 3.2)[:, :, None] * np.array([235, 240, 255], np.float32)[None, None, :] * 0.9


def finish(img, vignette=0.28, grain=3.0, seed=1):
    h, w, _ = img.shape
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    d = np.sqrt(((xs - w / 2) / (w / 2)) ** 2 + ((ys - h / 2) / (h / 2)) ** 2)
    img *= (1 - vignette * np.clip(d - 0.35, 0, 1) ** 1.6)[:, :, None]
    rng = np.random.default_rng(seed)
    img += rng.normal(0, grain, (h, w, 1)).astype(np.float32)
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))


# ---------------------------------------------------------------- wallpapers
def aurora(w, h):
    img = vgrad(w, h, [(0, "#03060f"), (0.55, "#0a1630"), (1, "#0f2a3c")])
    stars(img, int(w * h / 2600), 3)
    x = np.linspace(0, 1, w, dtype=np.float32)
    ys = np.arange(h, dtype=np.float32)[:, None]
    streak = fbm_field(w, 2, scale=70, octaves=3, seed=5)[0][None, :]
    for i, (col, off, wid) in enumerate([("#2bff9c", 0.0, 0.11), ("#35d6ff", 0.06, 0.09), ("#9a5cff", 0.13, 0.07)]):
        yc = (0.36 + off + 0.1 * np.sin(2 * math.pi * (1.2 + i * 0.3) * x + i * 1.7) + 0.05 * np.sin(2 * math.pi * 3.1 * x + i)) * h
        inten = np.exp(-(((ys - yc[None, :]) / (wid * h)) ** 2))
        inten = inten * (0.45 + 0.9 * streak) * (0.8 + 0.2 * np.sin(2 * math.pi * 9 * x + i))[None, :]
        img += inten[:, :, None] * hexrgb(col)[None, None, :] * 0.85
    soft = np.asarray(Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(6)), np.float32)
    img = img * 0.35 + soft * 0.75
    fill_below(img, ridge(w, 0.80 * h, 0.05 * h, 11), hexrgb("#060b14"))
    fill_below(img, ridge(w, 0.88 * h, 0.04 * h, 12), hexrgb("#03060b"))
    return finish(img)


def dunes(w, h):
    img = vgrad(w, h, [(0, "#2a1b4a"), (0.35, "#b04a6a"), (0.55, "#f08a5d"), (0.7, "#ffd08a")])
    glow(img, w * 0.68, h * 0.5, h * 0.35, "#ffd9a0", 0.9)
    cx, cy, r = w * 0.68, h * 0.5, h * 0.045
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    sun = np.clip((r - np.sqrt((xs - cx) ** 2 + (ys - cy) ** 2)) / 2, 0, 1)[:, :, None]
    img = img * (1 - sun) + hexrgb("#fff3d6")[None, None, :] * sun
    layers = [("#c7646a", 0.62, 0.05), ("#a24b5c", 0.70, 0.06), ("#7a3a52", 0.79, 0.06), ("#4a2742", 0.89, 0.05)]
    for i, (c, base, amp) in enumerate(layers):
        line = ridge(w, base * h, amp * h, 20 + i, detail=4)
        col = np.broadcast_to(hexrgb(c), (h, w, 3)).copy()
        ys2 = np.arange(h, dtype=np.float32)[:, None]
        shade = np.clip((ys2 - line[None, :]) / (0.12 * h), 0, 1)[:, :, None]
        col = col * (1.18 - 0.35 * shade)
        fill_below(img, line, col)
    return finish(img, vignette=0.22)


def ocean(w, h):
    img = vgrad(w, h, [(0, "#0b2a3f"), (0.4, "#1d6f86"), (1, "#0a3548")])
    glow(img, w * 0.5, h * 0.18, h * 0.55, "#9fe9e0", 0.55)
    for i in range(9):
        base = (0.3 + i * 0.075) * h
        line = ridge(w, base, (0.012 + i * 0.006) * h, 40 + i, detail=3)
        t = i / 8
        col = lerp(hexrgb("#56c1c8"), hexrgb("#08394d"), t) * (0.8 + 0.25 * (1 - t))
        a = 0.55
        tmp = img.copy()
        fill_below(tmp, line, col)
        img = img * (1 - a) + tmp * a
        crest = np.clip(1 - np.abs(np.arange(h, dtype=np.float32)[:, None] - line[None, :]) / 2.2, 0, 1)[:, :, None]
        img += crest * 38
    return finish(img, vignette=0.3)


def mesh(w, h, palette=None, seed=3):
    rng = np.random.default_rng(seed)
    pal = palette or ["#ff7a8a", "#ffb36b", "#7b6cff", "#35c8d9", "#f5e1ff"]
    pts = rng.random((len(pal), 2)) * np.array([w, h])
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    warp = fbm_field(w, h, scale=3, octaves=3, seed=seed)
    xw = xs + (warp - 0.5) * w * 0.35
    yw = ys + (np.roll(warp, 50, axis=0) - 0.5) * h * 0.35
    num = np.zeros((h, w, 3), np.float32)
    den = np.zeros((h, w, 1), np.float32)
    for (px, py), c in zip(pts, pal):
        d2 = ((xw - px) ** 2 + (yw - py) ** 2) / (0.5 * max(w, h)) ** 2
        wgt = 1 / (d2 + 0.03) ** 1.6
        num += wgt[:, :, None] * hexrgb(c)[None, None, :]
        den += wgt[:, :, None]
    return finish(num / den, vignette=0.18, grain=4.0, seed=seed)


def forest(w, h):
    img = vgrad(w, h, [(0, "#cfd8d8"), (0.5, "#a9bcb8"), (1, "#7f9a95")])
    glow(img, w * 0.3, h * 0.25, h * 0.6, "#fff6dc", 0.5)
    pil = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
    rng = np.random.default_rng(9)
    for i in range(6):
        d = ImageDraw.Draw(pil)
        t = i / 5
        col = tuple(int(v) for v in lerp(hexrgb("#6f8a86"), hexrgb("#0e1d1c"), t))
        base = h * (0.55 + 0.08 * i)
        x = -40
        while x < w + 40:
            tw = rng.uniform(0.05, 0.09) * w * (0.6 + 0.7 * t)
            th = tw * rng.uniform(2.4, 3.4)
            d.polygon([(x, base), (x + tw / 2, base - th), (x + tw, base)], fill=col)
            x += tw * rng.uniform(0.35, 0.7)
        d.rectangle([0, base, w, h], fill=col)
        fog = Image.new("RGB", (w, h), (200, 214, 212))
        mask = Image.fromarray((np.clip(1 - np.abs(np.mgrid[0:h, 0:w][0] - (base - 0.05 * h)) / (0.12 * h), 0, 1) * 150 * (1 - t * 0.5)).astype(np.uint8))
        pil = Image.composite(fog, pil, mask)
    return finish(np.asarray(pil, np.float32), vignette=0.25, grain=2.5)


def synthwave(w, h):
    img = vgrad(w, h, [(0, "#130a33"), (0.45, "#6a1b9a"), (0.6, "#ff4f9a"), (0.601, "#14052b"), (1, "#14052b")])
    cx, cy, r = w * 0.5, h * 0.5, h * 0.22
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    dist = np.sqrt((xs - cx) ** 2 + (ys - cy) ** 2)
    t = np.clip((ys - (cy - r)) / (2 * r), 0, 1)
    sun_col = lerp(hexrgb("#ffe55c")[None, None, :], hexrgb("#ff3d8f")[None, None, :], t[:, :, None])
    stripes = (np.sin(ys * 0.5 * (1 + t * 3)) > -0.1 + t * 0.9) | (t < 0.35)
    sun = (np.clip(r - dist, 0, 1) * stripes * (ys < h * 0.6))[:, :, None]
    glow(img, cx, cy, r * 2.2, "#ff5fa8", 0.55)
    img = img * (1 - sun) + sun_col * sun
    pil = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
    d = ImageDraw.Draw(pil)
    horizon = int(h * 0.6)
    for i in range(-16, 17):
        d.line([(w / 2 + i * 12, horizon), (w / 2 + i * w * 0.11, h)], fill=(255, 79, 154), width=2)
    y, step = horizon, 6.0
    while y < h:
        d.line([(0, y), (w, y)], fill=(255, 79, 154), width=2)
        step *= 1.22
        y += step
    return finish(np.asarray(pil, np.float32), vignette=0.3)


def moon(w, h):
    img = vgrad(w, h, [(0, "#04081a"), (0.6, "#0f2447"), (0.62, "#0b1a36"), (1, "#050b1c")])
    stars(img, int(w * h / 3200), 21, ymax=0.58)
    mx, my, mr = w * 0.62, h * 0.28, h * 0.085
    glow(img, mx, my, mr * 7, "#8fb4ff", 0.5)
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    disc = np.clip((mr - np.sqrt((xs - mx) ** 2 + (ys - my) ** 2)) / 2, 0, 1)[:, :, None]
    img = img * (1 - disc) + hexrgb("#f6f1e4")[None, None, :] * disc
    for k in range(40):  # reflection ripples
        yy = int(h * 0.64 + k * (h * 0.33 / 40) ** 1.0)
        half = int(mr * (1.2 - k / 60) * (1 + 0.6 * np.sin(k * 1.3)))
        img[yy:yy + 2, int(mx - half):int(mx + half), :] += 40
    fill_below(img, ridge(w, 0.62 * h, 0.012 * h, 31, detail=3), hexrgb("#09142b"))
    pil = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
    d = ImageDraw.Draw(pil)
    rng = np.random.default_rng(4)
    x = 0
    while x < w:
        th = rng.uniform(0.07, 0.15) * h
        tw = th * 0.38
        d.polygon([(x, 0.64 * h), (x + tw / 2, 0.64 * h - th), (x + tw, 0.64 * h)], fill=(4, 8, 16))
        x += tw * rng.uniform(0.45, 0.9)
    d.rectangle([0, 0.64 * h, w, 0.66 * h], fill=(4, 8, 16))
    return finish(np.asarray(pil, np.float32), vignette=0.3)


def topo(w, h):
    f = fbm_field(w, h, scale=3, octaves=5, seed=17)
    lines = np.abs(((f * 26) % 1.0) - 0.5)
    mask = np.clip(1 - lines / 0.035, 0, 1)
    bg = vgrad(w, h, [(0, "#0e1b2c"), (1, "#162a3f")])
    tint = lerp(hexrgb("#4de0c4"), hexrgb("#7a8cff"), f[:, :, None])
    img = bg + mask[:, :, None] * tint * 0.9
    return finish(img, vignette=0.28, grain=2.0)


def peaks(w, h):
    img = vgrad(w, h, [(0, "#25316d"), (0.45, "#6d77c9"), (0.7, "#f2b6c9"), (1, "#ffe0c4")])
    glow(img, w * 0.4, h * 0.55, h * 0.5, "#fff1d0", 0.7)
    cols = ["#9aa0e0", "#6e74c4", "#4b4f9c", "#2f3277", "#1b1d4f"]
    for i, c in enumerate(cols):
        sharp = ridge(w, (0.52 + 0.08 * i) * h, (0.1 - 0.012 * i) * h, 50 + i, detail=7)
        sharp = np.minimum(sharp, ridge(w, (0.52 + 0.08 * i) * h + 40, 0.07 * h, 70 + i, detail=3) + 80)
        fill_below(img, sharp, hexrgb(c))
        mist = np.clip(1 - np.abs(np.arange(h, dtype=np.float32)[:, None] - (sharp[None, :] + 0.05 * h)) / (0.09 * h), 0, 1)[:, :, None]
        img = img * (1 - mist * 0.35) + hexrgb("#ffe6ea")[None, None, :] * mist * 0.35
    return finish(img, vignette=0.25)


def lowpoly(w, h, seed=5):
    rng = np.random.default_rng(seed)
    cols, rows = 14, 9
    cw, ch = w / cols, h / rows
    pts = [[(x * cw + (rng.uniform(-0.35, 0.35) * cw if 0 < x < cols else 0),
             y * ch + (rng.uniform(-0.35, 0.35) * ch if 0 < y < rows else 0)) for x in range(cols + 1)] for y in range(rows + 1)]
    pil = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(pil)
    a, b, c = hexrgb("#ff6b6b"), hexrgb("#845ef7"), hexrgb("#22b8cf")
    for y in range(rows):
        for x in range(cols):
            p00, p10, p01, p11 = pts[y][x], pts[y][x + 1], pts[y + 1][x], pts[y + 1][x + 1]
            for tri in ([p00, p10, p01], [p10, p11, p01]):
                cx_ = sum(p[0] for p in tri) / 3 / w
                cy_ = sum(p[1] for p in tri) / 3 / h
                t = np.clip(0.6 * cx_ + 0.4 * cy_, 0, 1)
                col = lerp(a, b, t * 2) if t < 0.5 else lerp(b, c, (t - 0.5) * 2)
                col = col * rng.uniform(0.88, 1.1)
                d.polygon(tri, fill=tuple(int(v) for v in np.clip(col, 0, 255)))
    return finish(np.asarray(pil, np.float32), vignette=0.2, grain=2.0)


LANDSCAPE = [
    ("aurora", aurora), ("sunset-dunes", dunes), ("ocean-glow", ocean), ("mesh-gradient", mesh),
    ("misty-forest", forest), ("synthwave", synthwave), ("moonlit-lake", moon), ("blue-hour-peaks", peaks), ("low-poly", lowpoly),
]
PORTRAIT = [
    ("portrait-aurora", aurora), ("portrait-dunes", dunes),
    ("portrait-mesh", lambda w, h: mesh(w, h, ["#ffd6a5", "#ff8fab", "#9d8cff", "#6fd3c9", "#fff1e6"], seed=8)),
]


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "demo/wallpapers"
    os.makedirs(out, exist_ok=True)
    for name, fn in LANDSCAPE:
        fn(1920, 1200).save(os.path.join(out, name + ".jpg"), quality=90)
        print("made", name)
    for name, fn in PORTRAIT:
        fn(1200, 1920).save(os.path.join(out, name + ".jpg"), quality=90)
        print("made", name)


if __name__ == "__main__":
    main()
