"""
Draws the Photo Frame boot animation.

  part0 (plays once):  frame outline draws itself -> picture appears -> title fades in
  part1 (loops):       a soft light chases around the frame until Android has finished starting

Canvas is small (760x560) on pure black, so it blends into the screen edges and keeps memory low.
"""
import math, os, sys, shutil
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageChops

OUT = sys.argv[1]
W, H = 760, 490          # final size
S = 2                    # draw at 2x, then shrink (smooth edges)
FPS = 30
SHOW_CREDIT = False      # the "Made by" line is switched off
INTRO, LOOP = 40, 36     # frames

ACCENT = (255, 180, 84)
PINK = (232, 97, 140)
WHITE = (245, 245, 246)
DIM = (155, 161, 173)

# frame rectangle (in final pixels)
FX0, FY0, FX1, FY1 = 190, 70, 570, 330
RAD, STROKE = 30, 9


def pick_font(size, want=("Medium", "Bold", "Regular")):
    path = "/System/Library/Fonts/HelveticaNeue.ttc"
    best = None
    for idx in range(12):
        try:
            f = ImageFont.truetype(path, size * S, index=idx)
        except Exception:
            break
        style = f.getname()[1]
        if style in want and (best is None or want.index(style) < want.index(best[0])):
            best = (style, f)
    return best[1] if best else ImageFont.load_default()


FONT_TITLE = pick_font(46, ("Medium", "Bold", "Regular"))
FONT_SUB = pick_font(25, ("Regular", "Medium"))


def ease_out(t):
    t = max(0.0, min(1.0, t))
    return 1 - (1 - t) ** 3


def clamp01(t):
    return max(0.0, min(1.0, t))


# ---- the rounded-rectangle path, as a closed list of points starting at top-centre, going clockwise ----
def rounded_rect_path(x0, y0, x1, y1, r, step=2.0):
    pts = []
    cx = (x0 + x1) / 2

    def line(a, b):
        n = max(1, int(math.dist(a, b) / step))
        for i in range(n):
            pts.append((a[0] + (b[0] - a[0]) * i / n, a[1] + (b[1] - a[1]) * i / n))

    def arc(cx_, cy_, a0, a1):
        n = max(2, int(abs(a1 - a0) * r / step))
        for i in range(n):
            a = a0 + (a1 - a0) * i / n
            pts.append((cx_ + r * math.cos(a), cy_ + r * math.sin(a)))

    line((cx, y0), (x1 - r, y0))
    arc(x1 - r, y0 + r, -math.pi / 2, 0)
    line((x1, y0 + r), (x1, y1 - r))
    arc(x1 - r, y1 - r, 0, math.pi / 2)
    line((x1 - r, y1), (x0 + r, y1))
    arc(x0 + r, y1 - r, math.pi / 2, math.pi)
    line((x0, y1 - r), (x0, y0 + r))
    arc(x0 + r, y0 + r, math.pi, 3 * math.pi / 2)
    line((x0 + r, y0), (cx, y0))
    return pts


PATH = [(x * S, y * S) for x, y in rounded_rect_path(FX0, FY0, FX1, FY1, RAD)]
N = len(PATH)


def stroke_segment(draw, i0, i1, color, width):
    """Draw the part of the path from point i0 to i1 (wraps around), with round ends."""
    if i1 < i0:
        i1 += N
    pts = [PATH[k % N] for k in range(int(i0), int(i1) + 1)]
    if len(pts) < 2:
        return
    draw.line(pts, fill=color, width=width, joint="curve")
    r = width / 2
    for p in (pts[0], pts[-1]):
        draw.ellipse([p[0] - r, p[1] - r, p[0] + r, p[1] + r], fill=color)


def inner_mask():
    m = Image.new("L", (W * S, H * S), 0)
    d = ImageDraw.Draw(m)
    inset = 30
    d.rounded_rectangle([(FX0 + inset) * S, (FY0 + inset) * S, (FX1 - inset) * S, (FY1 - inset) * S],
                        radius=(RAD - 10) * S, fill=255)
    return m


INNER = inner_mask()


def picture_layer(alpha, pulse=0.0):
    """The little 'photo' inside the frame: a sun and two mountains."""
    layer = Image.new("RGBA", (W * S, H * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    ix0, iy0, ix1, iy1 = FX0 + 30, FY0 + 30, FX1 - 30, FY1 - 30
    w, h = ix1 - ix0, iy1 - iy0
    # sun
    sx, sy, sr = ix0 + 0.27 * w, iy0 + 0.30 * h, 20 * (1 + 0.05 * pulse) * (0.6 + 0.4 * alpha)
    d.ellipse([(sx - sr) * S, (sy - sr) * S, (sx + sr) * S, (sy + sr) * S], fill=ACCENT + (255,))
    # mountains
    far = [(ix0 + 0.36 * w, iy1), (ix0 + 0.66 * w, iy1 - 0.50 * h), (ix1, iy1)]
    near = [(ix0, iy1), (ix0 + 0.33 * w, iy1 - 0.66 * h), (ix0 + 0.64 * w, iy1)]
    d.polygon([(x * S, y * S) for x, y in far], fill=(210, 214, 222, 255))
    d.polygon([(x * S, y * S) for x, y in near], fill=WHITE + (255,))
    # keep inside the frame, then fade
    a = layer.split()[3]
    a = ImageChops.multiply(a, INNER)
    a = a.point(lambda v: int(v * alpha))
    layer.putalpha(a)
    return layer


def heart(draw, cx, cy, size, color):
    s = size * S
    r = s * 0.27
    draw.ellipse([cx - 2 * r + r * 0.2, cy - r - r * 0.9, cx + r * 0.2, cy + r - r * 0.9 + r * 0.0], fill=color)
    draw.ellipse([cx - r * 0.2, cy - r - r * 0.9, cx + 2 * r - r * 0.2, cy + r - r * 0.9], fill=color)
    draw.polygon([(cx - 2 * r + r * 0.2 - r * 0.02, cy - r * 0.55), (cx + 2 * r - r * 0.2 + r * 0.02, cy - r * 0.55),
                  (cx, cy + 1.55 * r)], fill=color)


def text_centered(layer, text, font, y, color, alpha, extra_right=0):
    d = ImageDraw.Draw(layer)
    bbox = d.textbbox((0, 0), text, font=font)
    tw = bbox[2] - bbox[0]
    x = (W * S - tw - extra_right) / 2
    d.text((x - bbox[0], y * S - bbox[1]), text, font=font, fill=color + (int(255 * alpha),))
    return x + tw


def render(stroke_p, icon_a, title_a, sub_a, chase_pos, chase_a, glow, pulse, title_rise):
    base = Image.new("RGBA", (W * S, H * S), (0, 0, 0, 255))

    # soft warm glow behind the frame
    gl = Image.new("RGBA", (W * S, H * S), (0, 0, 0, 0))
    gd = ImageDraw.Draw(gl)
    cx, cy = (FX0 + FX1) / 2 * S, (FY0 + FY1) / 2 * S
    for k in range(14, 0, -1):
        rr = (130 + k * 17) * S
        a = int(2.2 * (14 - k) * (0.6 + 0.4 * icon_a))
        gd.ellipse([cx - rr * 1.25, cy - rr * 0.95, cx + rr * 1.25, cy + rr * 0.95], fill=ACCENT + (a,))
    gl = gl.filter(ImageFilter.GaussianBlur(34 * S))
    # fade the glow out at the canvas edges so it never shows a box on the black screen
    edge = Image.new("L", (W * S, H * S), 0)
    ed = ImageDraw.Draw(edge)
    ed.ellipse([-W * S * 0.02, -H * S * 0.05, W * S * 1.02, H * S * 1.05], fill=255)
    edge = edge.filter(ImageFilter.GaussianBlur(60 * S))
    gl.putalpha(ImageChops.multiply(gl.split()[3], edge))
    base = Image.alpha_composite(base, gl)

    # the frame outline, drawn progressively
    outline = Image.new("RGBA", (W * S, H * S), (0, 0, 0, 0))
    od = ImageDraw.Draw(outline)
    if stroke_p > 0:
        end = stroke_p * N
        stroke_segment(od, 0, end, ACCENT + (255,), STROKE * S)

    # glow on the outline
    halo = outline.filter(ImageFilter.GaussianBlur(10 * S))
    halo.putalpha(halo.split()[3].point(lambda v: int(v * (0.55 + 0.35 * glow))))
    base = Image.alpha_composite(base, halo)
    base = Image.alpha_composite(base, outline)

    # the chasing light
    if chase_a > 0:
        ch = Image.new("RGBA", (W * S, H * S), (0, 0, 0, 0))
        cd = ImageDraw.Draw(ch)
        tail = int(N * 0.26)
        head = chase_pos * N
        pieces = 26
        for k in range(pieces):
            f0 = k / pieces
            f1 = (k + 1) / pieces
            i0 = (head - tail * (1 - f0)) % N
            i1 = (head - tail * (1 - f1)) % N
            strength = (f1 ** 2.2) * chase_a
            col = (255, 246, 228, int(255 * min(1.0, strength * 1.35)))
            stroke_segment(cd, i0, i1 + 1, col, int(STROKE * S * (0.9 + 0.7 * f1)))
        ch_halo = ch.filter(ImageFilter.GaussianBlur(9 * S))
        base = Image.alpha_composite(base, ch_halo)
        base = Image.alpha_composite(base, ch)

    # the picture inside
    if icon_a > 0:
        base = Image.alpha_composite(base, picture_layer(icon_a, pulse))

    # text
    if title_a > 0:
        tl = Image.new("RGBA", (W * S, H * S), (0, 0, 0, 0))
        text_centered(tl, "Photo Frame", FONT_TITLE, 372 + title_rise, WHITE, title_a)
        base = Image.alpha_composite(base, tl)
    if SHOW_CREDIT and sub_a > 0:
        sl = Image.new("RGBA", (W * S, H * S), (0, 0, 0, 0))
        d = ImageDraw.Draw(sl)
        label = "Made by Nayam Amarshe"
        bbox = d.textbbox((0, 0), label, font=FONT_SUB)
        tw = bbox[2] - bbox[0]
        heart_w = 30 * S
        x = (W * S - tw - heart_w) / 2
        y = 450 * S
        d.text((x - bbox[0], y - bbox[1]), label, font=FONT_SUB, fill=DIM + (int(255 * sub_a),))
        hl = Image.new("RGBA", (W * S, H * S), (0, 0, 0, 0))
        heart(ImageDraw.Draw(hl), x + tw + 14 * S + 11 * S, y + 17 * S, 30, PINK + (int(255 * sub_a),))
        base = Image.alpha_composite(base, sl)
        base = Image.alpha_composite(base, hl)

    return base.convert("RGB").resize((W, H), Image.LANCZOS)


def save_frames(folder, frames):
    os.makedirs(folder, exist_ok=True)
    for i, im in enumerate(frames):
        im.save(os.path.join(folder, f"{i:05d}.png"), optimize=True)


def main():
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    intro, loop = [], []
    for i in range(INTRO):
        stroke = ease_out(i / 24)
        icon = ease_out((i - 14) / 12)
        title = ease_out((i - 22) / 10)
        sub = ease_out((i - 28) / 9)
        # the chasing light fades in over the last 8 frames, lined up with where the loop starts
        ramp = clamp01((i - (INTRO - 8)) / 8)
        vj = i - INTRO            # virtual loop frame (negative: just before the loop begins)
        chase_pos = (vj / LOOP) % 1.0
        glow = 0.5 + 0.5 * math.sin(2 * math.pi * vj / LOOP)
        intro.append(render(stroke, icon, title, sub, chase_pos, ramp, glow, 0.0, (1 - title) * 14))
    for j in range(LOOP):
        glow = 0.5 + 0.5 * math.sin(2 * math.pi * j / LOOP)
        pulse = math.sin(2 * math.pi * j / LOOP)
        loop.append(render(1.0, 1.0, 1.0, 1.0, j / LOOP, 1.0, glow, pulse, 0))
    save_frames(os.path.join(OUT, "part0"), intro)
    save_frames(os.path.join(OUT, "part1"), loop)
    with open(os.path.join(OUT, "desc.txt"), "w") as f:
        f.write(f"{W} {H} {FPS}\n")
        f.write("p 1 0 part0\n")
        f.write("p 0 0 part1\n")
    # preview sheet + gif for checking by eye
    sheet_idx = [0, 8, 16, 24, 32, 39]
    sheet = Image.new("RGB", (W // 2 * 3, H // 2 * 2))
    for n, i in enumerate(sheet_idx):
        sheet.paste(intro[i].resize((W // 2, H // 2)), ((n % 3) * (W // 2), (n // 3) * (H // 2)))
    sheet.save(os.path.join(os.path.dirname(OUT), "sheet_intro.png"))
    loop_sheet = Image.new("RGB", (W // 2 * 3, H // 2 * 2))
    for n, j in enumerate([0, 6, 12, 18, 24, 30]):
        loop_sheet.paste(loop[j].resize((W // 2, H // 2)), ((n % 3) * (W // 2), (n // 3) * (H // 2)))
    loop_sheet.save(os.path.join(os.path.dirname(OUT), "sheet_loop.png"))
    gif = [f.resize((W // 2, H // 2)) for f in intro + loop + loop]
    gif[0].save(os.path.join(os.path.dirname(OUT), "bootanim-preview.gif"), save_all=True,
                append_images=gif[1:], duration=int(1000 / FPS), loop=0)
    print("frames:", len(intro), "+", len(loop))


main()
