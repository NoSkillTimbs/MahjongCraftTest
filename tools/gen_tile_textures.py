"""
Generates the MahjongCraft tile textures (classic ivory & green riichi style).

  python3 tools/gen_tile_textures.py

Writes into src/main/resources/assets/mahjongcraft/textures/item/mahjong_tile/:
  mahjong_tile_<name>.png  96x128 face for every tile (also used as the flat image in GUIs)
  mahjong_tile_back.png    96x128 green back
  mahjong_tile_cover.png   64x64 colour atlas for the tile body (sides, rim, back)

Everything is drawn at 4x and downsampled, so edges are anti-aliased.
Needs Pillow and the Noto Serif CJK font (fonts-noto-cjk-extra on Debian/Ubuntu).
"""
import math
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
OUT = os.path.join(ROOT, "src/main/resources/assets/mahjongcraft/textures/item/mahjong_tile")
FONT = "/usr/share/fonts/opentype/noto/NotoSerifCJK-Black.ttc"
FONT_TC = 3  # Traditional Chinese face inside the .ttc

W, H = 96, 128       # final face size (12 x 16 model units, 8 px per unit)
SS = 4               # supersampling factor
CW, CH = W * SS, H * SS

# palette
IVORY = (246, 240, 222)
IVORY_EDGE = (214, 204, 178)
INK = (28, 30, 38)
RED = (196, 36, 36)
GREEN = (30, 125, 64)
GREEN_DARK = (17, 82, 41)
NAVY = (28, 58, 140)
BACK_GREEN = (34, 124, 74)
BACK_GREEN_DARK = (20, 86, 50)

CORNER = 5 * SS      # transparent rounded corner radius (GUI only; the 3D model never shows it)
BEVEL = 6 * SS       # width of the darker rim drawn around the face


def s(v):
    return int(round(v * SS))


def new_face():
    """Ivory face with a soft bevel towards the edges and rounded corners."""
    img = Image.new("RGBA", (CW, CH), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # rim: graded from edge colour to ivory over BEVEL pixels
    steps = BEVEL
    for i in range(steps + 1):
        t = i / steps
        t = 1 - (1 - t) ** 2
        col = tuple(int(IVORY_EDGE[c] + (IVORY[c] - IVORY_EDGE[c]) * t) for c in range(3))
        d.rounded_rectangle([i, i, CW - 1 - i, CH - 1 - i], radius=max(CORNER - i, 0), fill=col + (255,))
    # very soft highlight on the upper half of the face
    glow = Image.new("L", (CW, CH), 0)
    ImageDraw.Draw(glow).ellipse([-CW * 0.2, -CH * 0.35, CW * 1.2, CH * 0.55], fill=26)
    glow = glow.filter(ImageFilter.GaussianBlur(s(10)))
    white = Image.new("RGBA", (CW, CH), (255, 255, 255, 255))
    img = _blend(img, white, glow)
    return img


def _blend(base, top, mask):
    out = base.copy()
    top = top.copy()
    top.putalpha(mask)
    out.alpha_composite(top)
    # keep the rounded-corner transparency of the base
    out.putalpha(base.getchannel("A"))
    return out


def finish(img):
    return img.resize((W, H), Image.LANCZOS)


# ---------------------------------------------------------------- characters

def font(size):
    return ImageFont.truetype(FONT, s(size), index=FONT_TC)


def draw_char(img, ch, cx, cy, size, color, squash=1.0):
    """Draws one glyph centred on (cx, cy) in final-pixel units; squash < 1 makes it shorter."""
    f = font(size)
    layer = Image.new("RGBA", (s(size * 1.6), s(size * 1.6)), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    ld.text((layer.width / 2, layer.height / 2), ch, font=f, fill=color + (255,), anchor="mm")
    bbox = layer.getbbox()
    if bbox:
        layer = layer.crop(bbox)
    if squash != 1.0:
        layer = layer.resize((layer.width, max(1, int(layer.height * squash))), Image.LANCZOS)
    img.alpha_composite(layer, (int(s(cx) - layer.width / 2), int(s(cy) - layer.height / 2)))


# ---------------------------------------------------------------- circles (pinzu)

def circle(d, cx, cy, r, color):
    """One pin: coloured ring, ivory ring, coloured core with an ivory eye and petals."""
    cx, cy, r = s(cx), s(cy), s(r)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color)
    r2 = r * 0.74
    d.ellipse([cx - r2, cy - r2, cx + r2, cy + r2], fill=IVORY)
    r3 = r * 0.58
    d.ellipse([cx - r3, cy - r3, cx + r3, cy + r3], fill=color)
    # eight small petals inside the core
    for k in range(8):
        a = k * math.pi / 4
        px, py = cx + math.cos(a) * r3 * 0.58, cy + math.sin(a) * r3 * 0.58
        pr = r3 * 0.16
        d.ellipse([px - pr, py - pr, px + pr, py + pr], fill=IVORY)
    r4 = r * 0.16
    d.ellipse([cx - r4, cy - r4, cx + r4, cy + r4], fill=IVORY)


def big_circle(d, cx, cy, r):
    """The ornate 1 pin."""
    S = lambda v: s(v)
    X, Y = S(cx), S(cy)

    def disc(rr, col):
        rr = S(rr)
        d.ellipse([X - rr, Y - rr, X + rr, Y + rr], fill=col)

    disc(r, GREEN)
    disc(r * 0.90, IVORY)
    # ring of navy dots
    for k in range(20):
        a = k * 2 * math.pi / 20
        px, py = X + math.cos(a) * S(r * 0.80), Y + math.sin(a) * S(r * 0.80)
        pr = S(r * 0.065)
        d.ellipse([px - pr, py - pr, px + pr, py + pr], fill=NAVY)
    disc(r * 0.68, NAVY)
    disc(r * 0.60, IVORY)
    # petals
    for k in range(12):
        a = k * 2 * math.pi / 12
        px, py = X + math.cos(a) * S(r * 0.44), Y + math.sin(a) * S(r * 0.44)
        pr = S(r * 0.11)
        d.ellipse([px - pr, py - pr, px + pr, py + pr], fill=GREEN)
    disc(r * 0.30, RED)
    disc(r * 0.17, IVORY)
    disc(r * 0.09, RED)


PIN_LAYOUTS = {
    # (x, y, colour) in final pixels; r = circle radius
    2: (13, [(48, 40, GREEN), (48, 88, NAVY)]),
    3: (12, [(26, 30, NAVY), (48, 64, RED), (70, 98, GREEN)]),
    4: (13, [(30, 42, NAVY), (66, 42, GREEN), (30, 86, GREEN), (66, 86, NAVY)]),
    5: (11.5, [(28, 32, NAVY), (68, 32, GREEN), (48, 64, RED), (28, 96, GREEN), (68, 96, NAVY)]),
    6: (10.5, [(32, 26, GREEN), (64, 26, GREEN), (32, 66, RED), (64, 66, RED), (32, 98, RED), (64, 98, RED)]),
    7: (9, [(24, 23, GREEN), (48, 34, GREEN), (72, 45, GREEN),
            (32, 72, RED), (64, 72, RED), (32, 101, RED), (64, 101, RED)]),
    8: (9.5, [(32, y, NAVY) for y in (22, 49, 76, 103)] + [(64, y, NAVY) for y in (22, 49, 76, 103)]),
    9: (9.8, [(x, 28, NAVY) for x in (24, 48, 72)] + [(x, 64, RED) for x in (24, 48, 72)]
       + [(x, 100, GREEN) for x in (24, 48, 72)]),
}


def pinzu(n, red=False):
    img = new_face()
    d = ImageDraw.Draw(img)
    if n == 1:
        big_circle(d, 48, 64, 32)
    else:
        r, pins = PIN_LAYOUTS[n]
        for x, y, col in pins:
            circle(d, x, y, r, RED if red else col)
    return finish(img)


# ---------------------------------------------------------------- bamboo (souzu)

def stick(cx, cy, h, color, angle=0.0, w=10.5):
    """One bamboo stick centred on (cx, cy), h tall, rotated by angle (radians)."""
    dark = tuple(max(0, int(c * 0.62)) for c in color)
    light = tuple(min(255, int(c + (255 - c) * 0.55)) for c in color)
    layer = Image.new("RGBA", (s(w * 2 + 8), s(h + 8)), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    ox, oy = layer.width / 2, layer.height / 2
    half_w, half_h = s(w / 2), s(h / 2)
    # body
    ld.rounded_rectangle([ox - half_w, oy - half_h, ox + half_w, oy + half_h], radius=s(w / 2.4), fill=dark)
    ld.rounded_rectangle([ox - half_w + s(1.2), oy - half_h + s(1.2), ox + half_w - s(1.2), oy + half_h - s(1.2)],
                         radius=s(w / 3), fill=color)
    # highlight stripe
    ld.rounded_rectangle([ox - s(w * 0.12), oy - half_h + s(3), ox + s(w * 0.08), oy + half_h - s(3)],
                         radius=s(1), fill=light)
    # nodes: top, middle, bottom
    for fy in (-0.5, 0.0, 0.5):
        ny = oy + fy * (2 * half_h - s(4))
        ld.rounded_rectangle([ox - half_w - s(1.2), ny - s(1.4), ox + half_w + s(1.2), ny + s(1.4)],
                             radius=s(1.2), fill=dark)
    if angle:
        layer = layer.rotate(math.degrees(angle), resample=Image.BICUBIC, expand=True)
    return layer, (int(s(cx) - layer.width / 2), int(s(cy) - layer.height / 2))


def place(img, layer_pos):
    layer, pos = layer_pos
    img.alpha_composite(layer, pos)


SOU_LAYOUTS = {
    # (x, y, colour, angle) ; stick height
    2: (46, [(48, 34, GREEN, 0), (48, 94, NAVY, 0)]),
    3: (46, [(48, 34, GREEN, 0), (30, 94, NAVY, 0), (66, 94, NAVY, 0)]),
    4: (46, [(30, 34, GREEN, 0), (66, 34, NAVY, 0), (30, 94, NAVY, 0), (66, 94, GREEN, 0)]),
    5: (46, [(26, 34, GREEN, 0), (70, 34, NAVY, 0), (48, 64, RED, 0), (26, 94, NAVY, 0), (70, 94, GREEN, 0)]),
    6: (46, [(26, 34, GREEN, 0), (48, 34, GREEN, 0), (70, 34, GREEN, 0),
             (26, 94, NAVY, 0), (48, 94, NAVY, 0), (70, 94, NAVY, 0)]),
    7: (31, [(48, 23, RED, 0),
             (26, 64, GREEN, 0), (48, 64, GREEN, 0), (70, 64, GREEN, 0),
             (26, 104, GREEN, 0), (48, 104, GREEN, 0), (70, 104, GREEN, 0)]),
    8: (46, [(19, 34, GREEN, 0), (38, 34, GREEN, -0.40), (58, 34, GREEN, 0.40), (77, 34, GREEN, 0),
             (19, 94, GREEN, 0), (38, 94, GREEN, 0.40), (58, 94, GREEN, -0.40), (77, 94, GREEN, 0)]),
    9: (31, [(26, y, GREEN, 0) for y in (22, 64, 106)] + [(48, y, RED, 0) for y in (22, 64, 106)]
        + [(70, y, GREEN, 0) for y in (22, 64, 106)]),
}


def souzu(n, red=False):
    img = new_face()
    if n == 1:
        bird(img)
        return finish(img)
    h, sticks = SOU_LAYOUTS[n]
    for x, y, col, ang in sticks:
        place(img, stick(x, y, h, RED if red else col, ang))
    return finish(img)


def bird(img):
    """The 1 sou: a stylised peacock with a fanned tail."""
    d = ImageDraw.Draw(img)
    S = s
    # tail fan behind the body
    for k in range(7):
        a = math.radians(-150 + k * 20)
        L = 38
        x2, y2 = 52 + math.cos(a) * L * 0.55, 74 + math.sin(a) * L
        d.line([S(54), S(80), S(x2), S(y2)], fill=GREEN_DARK, width=S(2.2))
        r = 6.2
        d.ellipse([S(x2 - r), S(y2 - r), S(x2 + r), S(y2 + r)], fill=GREEN)
        r2 = 3.4
        d.ellipse([S(x2 - r2), S(y2 - r2), S(x2 + r2), S(y2 + r2)], fill=NAVY)
        r3 = 1.5
        d.ellipse([S(x2 - r3), S(y2 - r3), S(x2 + r3), S(y2 + r3)], fill=RED)
    # body
    d.ellipse([S(30), S(66), S(66), S(100)], fill=GREEN_DARK)
    d.ellipse([S(32), S(68), S(64), S(98)], fill=GREEN)
    # wing
    d.chord([S(38), S(72), S(66), S(96)], 200, 20, fill=NAVY)
    # neck and head
    d.polygon([(S(36), S(76)), (S(30), S(52)), (S(38), S(50)), (S(44), S(74))], fill=GREEN)
    d.ellipse([S(24), S(40), S(40), S(56)], fill=GREEN_DARK)
    d.ellipse([S(25.5), S(41.5), S(38.5), S(54.5)], fill=GREEN)
    # crest
    for k in range(3):
        x = 28 + k * 4
        d.line([S(31), S(42), S(x), S(32)], fill=GREEN_DARK, width=S(1.4))
        d.ellipse([S(x - 1.8), S(30.2), S(x + 1.8), S(33.8)], fill=RED)
    # eye and beak
    d.ellipse([S(28), S(45), S(32), S(49)], fill=IVORY)
    d.ellipse([S(29), S(46), S(31), S(48)], fill=INK)
    d.polygon([(S(25), S(47)), (S(17), S(50)), (S(25), S(52))], fill=RED)
    # legs
    for x in (42, 52):
        d.line([S(x), S(98), S(x - 2), S(110)], fill=RED, width=S(2))
        d.line([S(x - 2), S(110), S(x - 7), S(112)], fill=RED, width=S(1.6))
        d.line([S(x - 2), S(110), S(x + 3), S(112)], fill=RED, width=S(1.6))


# ---------------------------------------------------------------- manzu and honours

NUMS = "一二三四伍六七八九"


def manzu(n, red=False):
    img = new_face()
    draw_char(img, NUMS[n - 1], 48, 38, 46, RED if red else INK, squash=0.86)
    draw_char(img, "萬", 48, 90, 46, RED, squash=0.92)
    return finish(img)


def honour(ch, color):
    img = new_face()
    draw_char(img, ch, 48, 64, 70, color)
    return finish(img)


def white_dragon():
    img = new_face()
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([s(22), s(26), s(74), s(102)], radius=s(4), outline=NAVY, width=s(4))
    d.rounded_rectangle([s(28), s(32), s(68), s(96)], radius=s(2), outline=NAVY, width=s(1.5))
    return finish(img)


def unknown():
    img = new_face()
    draw_char(img, "?", 48, 64, 60, (150, 140, 120))
    return finish(img)


def back():
    img = Image.new("RGBA", (CW, CH), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for i in range(BEVEL + 1):
        t = 1 - (1 - i / BEVEL) ** 2
        col = tuple(int(BACK_GREEN_DARK[c] + (BACK_GREEN[c] - BACK_GREEN_DARK[c]) * t) for c in range(3))
        d.rounded_rectangle([i, i, CW - 1 - i, CH - 1 - i], radius=max(CORNER - i, 0), fill=col + (255,))
    return finish(img)


def cover_atlas():
    """
    64x64 atlas of flat colours for the tile body. Regions are 16x16 (4x4 in model UV units):
      (0,0)  ivory              (16,0)  ivory rim (slightly darker)
      (32,0) green              (48,0)  green rim (darker)
      (0,16) seam between ivory and green
    """
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 15, 15], fill=IVORY)
    d.rectangle([16, 0, 31, 15], fill=(226, 218, 196))
    d.rectangle([32, 0, 47, 15], fill=BACK_GREEN)
    d.rectangle([48, 0, 63, 15], fill=BACK_GREEN_DARK)
    d.rectangle([0, 16, 15, 31], fill=(200, 192, 170))
    return img


def main():
    os.makedirs(OUT, exist_ok=True)
    out = {}
    for n in range(1, 10):
        out[f"m{n}"] = manzu(n)
        out[f"p{n}"] = pinzu(n)
        out[f"s{n}"] = souzu(n)
    out["m5_red"] = manzu(5, red=True)
    out["p5_red"] = pinzu(5, red=True)
    out["s5_red"] = souzu(5, red=True)
    out["east"] = honour("東", INK)
    out["south"] = honour("南", INK)
    out["west"] = honour("西", INK)
    out["north"] = honour("北", INK)
    out["red_dragon"] = honour("中", RED)
    out["green_dragon"] = honour("發", GREEN)
    out["white_dragon"] = white_dragon()
    out["unknown"] = unknown()
    out["back"] = back()
    for name, img in out.items():
        img.save(os.path.join(OUT, f"mahjong_tile_{name}.png"))
    cover_atlas().save(os.path.join(OUT, "mahjong_tile_cover.png"))
    print(f"wrote {len(out) + 1} textures to {OUT}")


if __name__ == "__main__":
    main()
