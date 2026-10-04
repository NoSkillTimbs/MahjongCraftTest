"""Generates the Table Cards GUI textures: card frames for the mod's own cards, card backs,
playmats and Energy icons
(the card backs are separate, player-supplied images). All original artwork (no logos or real card designs).

    python3 tools/gen_card_textures.py

Writes into tablecards-mod/src/main/resources/assets/tablecards/textures/gui/.
"""
import math
import os
import random

from PIL import Image, ImageDraw, ImageFilter

OUT = os.path.join(os.path.dirname(__file__), "..", "tablecards-mod", "src", "main", "resources",
                   "assets", "tablecards", "textures", "gui")
SS = 4  # supersampling
CW, CH = 128, 186

TYPES = {
    "fire": (226, 84, 52),
    "water": (58, 140, 222),
    "grass": (88, 170, 72),
    "lightning": (242, 196, 48),
    "psychic": (152, 92, 196),
    "fighting": (190, 110, 58),
    "darkness": (58, 70, 84),
    "metal": (150, 162, 174),
    "fairy": (230, 126, 178),
    "dragon": (178, 150, 58),
    "colorless": (214, 208, 194),
}


class BlendDraw:
    """ImageDraw that alpha-blends each shape onto the image (Pillow's RGBA draw replaces pixels)."""

    def __init__(self, img):
        self.img = img

    def __getattr__(self, name):
        def call(*args, **kwargs):
            layer = Image.new("RGBA", self.img.size, (0, 0, 0, 0))
            getattr(ImageDraw.Draw(layer), name)(*args, **kwargs)
            self.img.alpha_composite(layer)
        return call


def mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def lighten(c, t):
    return mix(c, (255, 255, 255), t)


def darken(c, t):
    return mix(c, (0, 0, 0), t)


def canvas(w=CW, h=CH):
    return Image.new("RGBA", (w * SS, h * SS), (0, 0, 0, 0))


def finish(img, w=CW, h=CH):
    return img.resize((w, h), Image.LANCZOS)


def rr(d, box, r, fill=None, outline=None, width=1):
    x0, y0, x1, y1 = [v * SS for v in box]
    d.rounded_rectangle((x0, y0, x1, y1), radius=r * SS, fill=fill, outline=outline, width=width * SS)


def vgrad(img, box, top, bottom, radius=0):
    x0, y0, x1, y1 = [int(v * SS) for v in box]
    g = Image.new("RGBA", (x1 - x0, y1 - y0))
    gd = ImageDraw.Draw(g)
    for y in range(y1 - y0):
        t = y / max(1, (y1 - y0 - 1))
        gd.line([(0, y), (x1 - x0, y)], fill=mix(top, bottom, t) + (255,))
    mask = Image.new("L", g.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, g.size[0] - 1, g.size[1] - 1), radius=radius * SS, fill=255)
    img.paste(g, (x0, y0), mask)


def star(cx, cy, r_out, r_in, points, rot=-math.pi / 2):
    pts = []
    for i in range(points * 2):
        r = r_out if i % 2 == 0 else r_in
        a = rot + i * math.pi / points
        pts.append(((cx + r * math.cos(a)) * SS, (cy + r * math.sin(a)) * SS))
    return pts


def emblem(d, kind, cx, cy, r, color, bg=(0, 0, 0)):
    """Simple type symbols, drawn in our own style."""
    s = SS
    c = color + (255,)
    if kind == "fire":
        pts = [(cx, cy - r), (cx + r * 0.55, cy - r * 0.1), (cx + r * 0.7, cy + r * 0.45), (cx, cy + r),
               (cx - r * 0.7, cy + r * 0.45), (cx - r * 0.45, cy - r * 0.15), (cx - r * 0.2, cy + r * 0.1)]
        d.polygon([(x * s, y * s) for x, y in pts], fill=c)
    elif kind == "water":
        d.ellipse(((cx - r * 0.62) * s, (cy - r * 0.05) * s, (cx + r * 0.62) * s, (cy + r) * s), fill=c)
        d.polygon([(cx * s, (cy - r) * s), ((cx + r * 0.6) * s, (cy + r * 0.35) * s), ((cx - r * 0.6) * s, (cy + r * 0.35) * s)], fill=c)
    elif kind == "grass":
        d.ellipse(((cx - r * 0.5) * s, (cy - r) * s, (cx + r * 0.5) * s, (cy + r) * s), fill=c)
        d.line([(cx * s, (cy - r * 0.8) * s), (cx * s, (cy + r) * s)], fill=(255, 255, 255, 120), width=int(r * 0.12 * s))
    elif kind == "lightning":
        pts = [(cx + r * 0.25, cy - r), (cx - r * 0.55, cy + r * 0.1), (cx - r * 0.05, cy + r * 0.1),
               (cx - r * 0.3, cy + r), (cx + r * 0.6, cy - r * 0.15), (cx + r * 0.08, cy - r * 0.15)]
        d.polygon([(x * s, y * s) for x, y in pts], fill=c)
    elif kind == "psychic":
        d.ellipse(((cx - r) * s, (cy - r * 0.55) * s, (cx + r) * s, (cy + r * 0.55) * s), fill=c)
        d.ellipse(((cx - r * 0.35) * s, (cy - r * 0.35) * s, (cx + r * 0.35) * s, (cy + r * 0.35) * s), fill=(255, 255, 255, 230))
        d.ellipse(((cx - r * 0.16) * s, (cy - r * 0.16) * s, (cx + r * 0.16) * s, (cy + r * 0.16) * s), fill=c)
    elif kind == "fighting":
        d.polygon(star(cx, cy, r, r * 0.45, 8), fill=c)
    elif kind == "darkness":
        d.ellipse(((cx - r) * s, (cy - r) * s, (cx + r) * s, (cy + r) * s), fill=c)
        d.ellipse(((cx - r * 0.45) * s, (cy - r * 1.05) * s, (cx + r * 1.15) * s, (cy + r * 0.55) * s), fill=tuple(bg[:3]) + (255,))
    elif kind == "metal":
        pts = [(cx + r * math.cos(a), cy + r * math.sin(a)) for a in [math.pi / 6 + k * math.pi / 3 for k in range(6)]]
        d.polygon([(x * s, y * s) for x, y in pts], fill=c)
        pts = [(cx + r * 0.45 * math.cos(a), cy + r * 0.45 * math.sin(a)) for a in [math.pi / 6 + k * math.pi / 3 for k in range(6)]]
        d.polygon([(x * s, y * s) for x, y in pts], fill=(255, 255, 255, 140))
    elif kind == "fairy":
        d.polygon(star(cx, cy, r, r * 0.3, 4), fill=c)
    elif kind == "dragon":
        d.polygon([(cx * s, (cy - r) * s), ((cx + r * 0.7) * s, cy * s), (cx * s, (cy + r) * s), ((cx - r * 0.7) * s, cy * s)], fill=c)
        d.polygon([(cx * s, (cy - r * 0.5) * s), ((cx + r * 0.3) * s, cy * s), (cx * s, (cy + r * 0.5) * s), ((cx - r * 0.3) * s, cy * s)], fill=(255, 255, 255, 120))
    elif kind == "colorless":
        d.polygon(star(cx, cy, r, r * 0.55, 6), fill=c)
    elif kind == "sword":
        d.polygon(star(cx, cy, r, r * 0.38, 5), fill=c)
    elif kind == "spell":
        d.ellipse(((cx - r) * s, (cy - r) * s, (cx + r) * s, (cy + r) * s), outline=c, width=int(r * 0.14 * s))
        d.polygon(star(cx, cy, r * 0.8, r * 0.2, 4, rot=math.pi / 4), fill=c)
    elif kind == "trap":
        pts = []
        for i in range(12):
            a = -math.pi / 2 + i * math.pi / 6
            rr_ = r if i % 2 == 0 else r * 0.62
            pts.append((cx + rr_ * math.cos(a), cy + rr_ * math.sin(a)))
        d.polygon([(x * s, y * s) for x, y in pts], outline=c, width=int(r * 0.13 * s))
        d.ellipse(((cx - r * 0.25) * s, (cy - r * 0.25) * s, (cx + r * 0.25) * s, (cy + r * 0.25) * s), fill=c)
    elif kind == "trainer":
        d.rounded_rectangle(((cx - r * 0.6) * s, (cy - r * 0.8) * s, (cx + r * 0.6) * s, (cy + r * 0.8) * s), radius=r * 0.15 * s, outline=c, width=int(r * 0.12 * s))
        for k in range(3):
            y = cy - r * 0.35 + k * r * 0.35
            d.line([((cx - r * 0.35) * s, y * s), ((cx + r * 0.35) * s, y * s)], fill=c, width=int(r * 0.1 * s))


def noise_layer(w, h, amount, seed):
    rnd = random.Random(seed)
    n = Image.new("L", (w, h))
    n.putdata([128 + int(rnd.gauss(0, amount)) for _ in range(w * h)])
    return n


def ygo_frame(name, body, emb, emb_color):
    img = canvas()
    d = BlendDraw(img)
    rr(d, (0, 0, CW - 1, CH - 1), 7, fill=(28, 24, 22, 255))
    vgrad(img, (3, 3, CW - 3, CH - 3), lighten(body, 0.15), darken(body, 0.2), radius=5)
    d = BlendDraw(img)
    # name bar
    rr(d, (7, 7, CW - 7, 21), 2, fill=lighten(body, 0.55) + (255,), outline=darken(body, 0.45) + (255,), width=1)
    # art window
    vgrad(img, (12, 26, CW - 12, 108), (40, 44, 56), (18, 20, 28), radius=1)
    d = BlendDraw(img)
    d.rectangle((12 * SS, 26 * SS, (CW - 12) * SS, 108 * SS), outline=darken(body, 0.5) + (255,), width=2 * SS)
    for k in range(5):
        rad = 12 + k * 7
        d.ellipse(((CW / 2 - rad) * SS, (67 - rad) * SS, (CW / 2 + rad) * SS, (67 + rad) * SS), outline=(255, 255, 255, 18), width=SS)
    emblem(d, emb, CW / 2, 67, 24, emb_color)
    # text box
    rr(d, (9, 114, CW - 9, CH - 9), 2, fill=(236, 226, 200, 255), outline=darken(body, 0.45) + (255,), width=1)
    d.line([(14 * SS, (CH - 22) * SS), ((CW - 14) * SS, (CH - 22) * SS)], fill=darken(body, 0.3) + (255,), width=SS)
    finish(img).save(os.path.join(OUT, name + ".png"))


def ptcg_frame(name, body, emb, emb_color, energy=False):
    img = canvas()
    d = BlendDraw(img)
    rr(d, (0, 0, CW - 1, CH - 1), 8, fill=(236, 200, 72, 255))  # outer border
    vgrad(img, (5, 5, CW - 5, CH - 5), lighten(body, 0.35), darken(body, 0.05), radius=5)
    d = BlendDraw(img)
    if energy:
        d.ellipse(((CW / 2 - 40) * SS, (CH / 2 - 40) * SS, (CW / 2 + 40) * SS, (CH / 2 + 40) * SS), fill=(255, 255, 255, 235))
        d.ellipse(((CW / 2 - 34) * SS, (CH / 2 - 34) * SS, (CW / 2 + 34) * SS, (CH / 2 + 34) * SS), fill=body + (255,))
        emblem(d, emb, CW / 2, CH / 2, 24, (255, 255, 255), bg=body)
        rr(d, (9, 8, CW - 9, 22), 3, fill=lighten(body, 0.6) + (230,))
        finish(img).save(os.path.join(OUT, name + ".png"))
        return
    # name area
    rr(d, (9, 8, CW - 9, 22), 3, fill=lighten(body, 0.6) + (210,))
    # art window with a silver edge
    vgrad(img, (12, 28, CW - 12, 100), lighten(body, 0.25), darken(body, 0.35), radius=1)
    d = BlendDraw(img)
    d.rectangle((12 * SS, 28 * SS, (CW - 12) * SS, 100 * SS), outline=(212, 214, 220, 255), width=2 * SS)
    for k in range(6):
        a = k * math.pi / 3
        d.line([(CW / 2 * SS, 64 * SS), ((CW / 2 + 60 * math.cos(a)) * SS, (64 + 60 * math.sin(a)) * SS)], fill=(255, 255, 255, 25), width=4 * SS)
    d.ellipse(((CW / 2 - 26) * SS, (64 - 26) * SS, (CW / 2 + 26) * SS, (64 + 26) * SS), fill=(255, 255, 255, 60))
    emblem(d, emb, CW / 2, 64, 20, emb_color, bg=mix(lighten(body, 0.1), (255, 255, 255), 0.24))
    # text box
    rr(d, (9, 106, CW - 9, CH - 9), 3, fill=(246, 242, 232, 235), outline=darken(body, 0.25) + (255,), width=1)
    finish(img).save(os.path.join(OUT, name + ".png"))


def back_ygo():
    img = canvas()
    d = BlendDraw(img)
    rr(d, (0, 0, CW - 1, CH - 1), 7, fill=(22, 14, 10, 255))
    vgrad(img, (4, 4, CW - 4, CH - 4), (92, 52, 30), (40, 20, 14), radius=5)
    d = BlendDraw(img)
    cx, cy = CW / 2, CH / 2
    for k in range(9, 0, -1):
        rx, ry = 6 + k * 5.5, 9 + k * 8.5
        col = mix((40, 16, 10), (226, 160, 72), k / 9)
        d.ellipse(((cx - rx) * SS, (cy - ry) * SS, (cx + rx) * SS, (cy + ry) * SS), outline=col + (255,), width=int(1.6 * SS))
    d.ellipse(((cx - 9) * SS, (cy - 13) * SS, (cx + 9) * SS, (cy + 13) * SS), fill=(250, 214, 120, 255))
    d.ellipse(((cx - 4) * SS, (cy - 6) * SS, (cx + 4) * SS, (cy + 6) * SS), fill=(40, 16, 10, 255))
    rr(d, (8, 8, CW - 8, CH - 8), 3, outline=(214, 170, 90, 255), width=1)
    finish(img).save(os.path.join(OUT, "back_ygo.png"))


def back_ptcg():
    img = canvas()
    d = BlendDraw(img)
    rr(d, (0, 0, CW - 1, CH - 1), 8, fill=(26, 52, 120, 255))
    vgrad(img, (5, 5, CW - 5, CH - 5), (44, 92, 196), (16, 34, 92), radius=5)
    d = BlendDraw(img)
    cx, cy = CW / 2, CH / 2
    for k in range(12):
        a = k * math.pi / 6
        d.line([(cx * SS, cy * SS), ((cx + 90 * math.cos(a)) * SS, (cy + 90 * math.sin(a)) * SS)], fill=(255, 255, 255, 16), width=5 * SS)
    d.ellipse(((cx - 34) * SS, (cy - 34) * SS, (cx + 34) * SS, (cy + 34) * SS), fill=(250, 214, 80, 255))
    d.ellipse(((cx - 29) * SS, (cy - 29) * SS, (cx + 29) * SS, (cy + 29) * SS), fill=(30, 64, 150, 255))
    d.polygon(star(cx, cy, 24, 9, 4), fill=(250, 214, 80, 255))
    d.polygon(star(cx, cy, 15, 6, 4, rot=-math.pi / 4), fill=(255, 240, 180, 255))
    rr(d, (9, 9, CW - 9, CH - 9), 4, outline=(250, 214, 80, 180), width=1)
    finish(img).save(os.path.join(OUT, "back_ptcg.png"))


def playmat(name, top, bottom, line, seed):
    w, h = 256, 256
    img = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = abs(y - h / 2) / (h / 2)
        d.line([(0, y), (w, y)], fill=mix(top, bottom, t))
    n = noise_layer(w, h, 10, seed).filter(ImageFilter.GaussianBlur(0.6))
    img = Image.composite(img.point(lambda v: min(255, v + 10)), img.point(lambda v: max(0, v - 10)), n)
    img = img.convert("RGBA")
    d = BlendDraw(img)
    d.ellipse((w / 2 - 34, h / 2 - 34, w / 2 + 34, h / 2 + 34), outline=line + (45,), width=1)
    d.ellipse((w / 2 - 22, h / 2 - 22, w / 2 + 22, h / 2 + 22), outline=line + (30,), width=1)
    # soft vignette
    v = Image.new("L", (w, h), 0)
    vd = ImageDraw.Draw(v)
    for k in range(40):
        vd.rectangle((k, k, w - 1 - k, h - 1 - k), outline=int(90 * (1 - k / 40)))
    img = Image.composite(Image.new("RGB", (w, h), (0, 0, 0)), img.convert("RGB"), v)
    img.save(os.path.join(OUT, name + ".png"))


def energy_icon(t, color):
    s = 32
    img = Image.new("RGBA", (s * SS, s * SS), (0, 0, 0, 0))
    d = BlendDraw(img)
    d.ellipse((0, 0, s * SS - 1, s * SS - 1), fill=(255, 255, 255, 255))
    d.ellipse((2 * SS, 2 * SS, (s - 2) * SS, (s - 2) * SS), fill=color + (255,))
    emblem(d, t, s / 2, s / 2, 9.5, (255, 255, 255) if t not in ("lightning", "colorless", "metal") else (40, 40, 40), bg=color)
    img.resize((s, s), Image.LANCZOS).save(os.path.join(OUT, "energy_" + t + ".png"))


def main():
    os.makedirs(OUT, exist_ok=True)
    ygo_frame("frame_ygo_monster", (196, 150, 80), "sword", (250, 222, 150))
    ygo_frame("frame_ygo_spell", (28, 150, 132), "spell", (170, 250, 230))
    ygo_frame("frame_ygo_trap", (172, 56, 120), "trap", (255, 190, 225))
    ygo_frame("frame_default", (120, 124, 132), "colorless", (230, 230, 230))
    for t, col in TYPES.items():
        ptcg_frame("frame_ptcg_" + t, col, t, darken(col, 0.45) if t != "darkness" else (220, 220, 230))
        ptcg_frame("frame_ptcg_energy_" + t, col, t, (255, 255, 255), energy=True)
        energy_icon(t, col)
    ptcg_frame("frame_ptcg_trainer", (150, 170, 196), "trainer", (60, 76, 104))
    # card backs (back_ygo.png, back_ptcg.png) are the player-supplied pixel art, not generated
    playmat("playmat_ygo", (52, 30, 74), (22, 14, 36), (200, 170, 255), 7)
    playmat("playmat_ptcg", (24, 86, 74), (10, 40, 36), (170, 255, 220), 11)
    print("wrote textures to", os.path.normpath(OUT))


if __name__ == "__main__":
    main()
