"""
Generates the MahjongCraft tile textures, styled after a classic printed tile set:
red corner index (1-9, or E/S/W/N on winds), blue characters over a red 萬,
peanut-shaped bamboo sticks, and ringed circles in green, red and blue.

  python3 tools/gen_tile_textures.py

Writes into src/main/resources/assets/mahjongcraft/textures/item/mahjong_tile/:
  mahjong_tile_<name>.png  96x128 face for every tile (also used as the flat image in GUIs)
  mahjong_tile_unknown.png the hidden-tile face, made from tools/art/hidden_tile_character.png
  mahjong_tile_back.png    96x128 green back
  mahjong_tile_cover.png   64x64 colour atlas for the tile body (sides, rim, back)

Red fives (riichi bonus tiles) use the normal design with every symbol in red.
Everything is drawn at 4x and downsampled, so edges are anti-aliased.
Needs Pillow, numpy, scipy and the Noto Serif CJK font (fonts-noto-cjk-extra on Debian/Ubuntu).
"""
import math
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont
from scipy import ndimage

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
OUT = os.path.join(ROOT, "src/main/resources/assets/mahjongcraft/textures/item/mahjong_tile")
CHARACTER_ART = os.path.join(HERE, "art/hidden_tile_character.png")
FONT_DIR = "/usr/share/fonts/opentype/noto/"
FONT_TC = 3  # Traditional Chinese face inside the .ttc files

W, H = 96, 128       # final face size (12 x 16 model units, 8 px per unit)
SS = 4               # supersampling factor
CW, CH = W * SS, H * SS

# palette (sampled from the reference set, slightly deepened for Minecraft's lighting)
IVORY = (248, 245, 234)
IVORY_EDGE = (214, 208, 188)
INK = (28, 30, 38)
RED = (200, 34, 38)
GREEN = (26, 116, 58)
GREEN_DARK = (12, 70, 32)
BLUE = (30, 52, 150)
BLUE_DARK = (18, 30, 96)
YELLOW = (236, 196, 52)
BACK_GREEN = (34, 124, 74)
BACK_GREEN_DARK = (20, 86, 50)

CORNER = 5 * SS      # transparent rounded corner radius (GUI only; the 3D model never shows it)
BEVEL = 6 * SS       # width of the darker rim drawn around the face


def s(v):
    return int(round(v * SS))


# ---------------------------------------------------------------- base

def new_face():
    """Ivory face with a soft bevel towards the edges and rounded corners."""
    img = Image.new("RGBA", (CW, CH), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for i in range(BEVEL + 1):
        t = 1 - (1 - i / BEVEL) ** 2
        col = tuple(int(IVORY_EDGE[c] + (IVORY[c] - IVORY_EDGE[c]) * t) for c in range(3))
        d.rounded_rectangle([i, i, CW - 1 - i, CH - 1 - i], radius=max(CORNER - i, 0), fill=col + (255,))
    glow = Image.new("L", (CW, CH), 0)
    ImageDraw.Draw(glow).ellipse([-CW * 0.2, -CH * 0.35, CW * 1.2, CH * 0.55], fill=22)
    glow = glow.filter(ImageFilter.GaussianBlur(s(10)))
    top = Image.new("RGBA", (CW, CH), (255, 255, 255, 255))
    top.putalpha(glow)
    out = img.copy()
    out.alpha_composite(top)
    out.putalpha(img.getchannel("A"))
    return out


def finish(img):
    return img.resize((W, H), Image.LANCZOS)


def font(size, weight="Bold"):
    return ImageFont.truetype(f"{FONT_DIR}NotoSerifCJK-{weight}.ttc", s(size), index=FONT_TC)


def draw_glyph(img, ch, cx, cy, size, color, weight="Bold", squash=1.0, stretch=1.0):
    """Draws one glyph centred on (cx, cy) in final-pixel units."""
    f = font(size, weight)
    layer = Image.new("RGBA", (s(size * 1.8), s(size * 1.8)), (0, 0, 0, 0))
    ImageDraw.Draw(layer).text((layer.width / 2, layer.height / 2), ch, font=f, fill=color + (255,), anchor="mm")
    bbox = layer.getbbox()
    if bbox:
        layer = layer.crop(bbox)
    if squash != 1.0 or stretch != 1.0:
        layer = layer.resize((max(1, int(layer.width * stretch)), max(1, int(layer.height * squash))), Image.LANCZOS)
    img.alpha_composite(layer, (int(s(cx) - layer.width / 2), int(s(cy) - layer.height / 2)))


def corner_index(img, text):
    """The small red index in the top-left corner, like the reference set."""
    f = font(15, "Bold")
    d = ImageDraw.Draw(img)
    d.text((s(8.5), s(5.5)), text, font=f, fill=RED + (255,), anchor="la")


# ---------------------------------------------------------------- circles (pinzu)

def ring_circle(d, cx, cy, r, color):
    """One pin as on the reference set: coloured ring, inner ring, centre dot."""
    X, Y, R = s(cx), s(cy), s(r)

    def disc(k, col):
        rr = R * k
        d.ellipse([X - rr, Y - rr, X + rr, Y + rr], fill=col)

    disc(1.00, color)
    disc(0.80, IVORY)
    disc(0.62, color)
    disc(0.44, IVORY)
    disc(0.24, color)


def big_circle(d, cx, cy, r):
    """The 1 pin: a large green medallion with a red centre and a ring of ivory dots."""
    X, Y = s(cx), s(cy)

    def disc(k, col):
        rr = s(r * k)
        d.ellipse([X - rr, Y - rr, X + rr, Y + rr], fill=col)

    disc(1.00, GREEN_DARK)
    disc(0.94, GREEN)
    for k in range(16):  # ivory dots around the rim
        a = k * 2 * math.pi / 16
        px, py = X + math.cos(a) * s(r * 0.77), Y + math.sin(a) * s(r * 0.77)
        pr = s(r * 0.07)
        d.ellipse([px - pr, py - pr, px + pr, py + pr], fill=IVORY)
    disc(0.62, IVORY)
    disc(0.54, RED)
    disc(0.40, IVORY)
    disc(0.30, RED)
    disc(0.16, IVORY)
    disc(0.08, RED)


# colours follow the reference set; G = green, B = blue, R = red
PIN_LAYOUTS = {
    2: (13, [(50, 46, GREEN), (50, 92, BLUE)]),
    3: (11.5, [(28, 38, GREEN), (50, 68, RED), (72, 98, BLUE)]),
    4: (12, [(32, 48, GREEN), (68, 48, BLUE), (32, 90, BLUE), (68, 90, GREEN)]),
    5: (11, [(28, 38, GREEN), (72, 38, BLUE), (50, 68, RED), (28, 98, BLUE), (72, 98, GREEN)]),
    6: (10.5, [(36, 34, GREEN), (64, 34, GREEN), (36, 70, RED), (64, 70, RED), (36, 100, RED), (64, 100, RED)]),
    7: (9, [(32, 28, GREEN), (52, 36, GREEN), (72, 44, GREEN),
            (38, 74, RED), (62, 74, RED), (38, 102, RED), (62, 102, RED)]),
    8: (9.3, [(36, y, BLUE) for y in (30, 54, 78, 102)] + [(62, y, BLUE) for y in (30, 54, 78, 102)]),
    9: (9.8, [(x, 36, GREEN) for x in (26, 50, 74)] + [(x, 68, RED) for x in (26, 50, 74)]
       + [(x, 100, BLUE) for x in (26, 50, 74)]),
}


def pinzu(n, red=False):
    img = new_face()
    d = ImageDraw.Draw(img)
    if n == 1:
        big_circle(d, 50, 68, 31)
    else:
        r, pins = PIN_LAYOUTS[n]
        for x, y, col in pins:
            ring_circle(d, x, y, r, RED if red else col)
    corner_index(img, str(n))
    return finish(img)


# ---------------------------------------------------------------- bamboo (souzu)

def peanut_stick(cx, cy, h, color, angle=0.0, w=9.5):
    """
    One bamboo stick as on the reference set: two stacked rounded segments with a pinched
    joint in the middle (a peanut / figure-8 shape), dark outline and a light centre line.
    Returns (layer, position) for alpha_composite.
    """
    dark = tuple(max(0, int(c * 0.55)) for c in color)
    light = tuple(min(255, int(c + (255 - c) * 0.6)) for c in color)
    pad = 6
    layer = Image.new("RGBA", (s(w + pad * 2), s(h + pad * 2)), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    ox, oy = layer.width / 2, layer.height / 2
    hw, hh = s(w / 2), s(h / 2)
    seg = hh  # each segment is half the stick
    outline = s(1.3)
    for top in (oy - hh, oy):
        d.ellipse([ox - hw, top, ox + hw, top + seg], fill=dark)
    for top in (oy - hh, oy):
        d.ellipse([ox - hw + outline, top + outline, ox + hw - outline, top + seg - outline], fill=color)
    # pinched joint and end caps
    d.rounded_rectangle([ox - hw * 0.55, oy - s(1.2), ox + hw * 0.55, oy + s(1.2)], radius=s(1), fill=dark)
    # light centre line on each segment
    for top in (oy - hh, oy):
        d.line([ox, top + seg * 0.22, ox, top + seg * 0.78], fill=light, width=s(1.3))
    if angle:
        layer = layer.rotate(math.degrees(angle), resample=Image.BICUBIC, expand=True)
    return layer, (int(s(cx) - layer.width / 2), int(s(cy) - layer.height / 2))


SOU_LAYOUTS = {
    # stick height, [(x, y, colour, angle)]
    2: (32, [(50, 46, GREEN, 0), (50, 92, GREEN, 0)]),
    3: (32, [(50, 46, GREEN, 0), (32, 92, GREEN, 0), (68, 92, GREEN, 0)]),
    4: (32, [(34, 46, GREEN, 0), (66, 46, GREEN, 0), (34, 92, GREEN, 0), (66, 92, GREEN, 0)]),
    5: (30, [(28, 44, GREEN, 0), (72, 44, GREEN, 0), (50, 69, RED, 0), (28, 94, GREEN, 0), (72, 94, GREEN, 0)]),
    6: (32, [(28, 46, GREEN, 0), (50, 46, GREEN, 0), (72, 46, GREEN, 0),
             (28, 92, GREEN, 0), (50, 92, GREEN, 0), (72, 92, GREEN, 0)]),
    7: (24, [(50, 32, RED, 0),
             (28, 68, GREEN, 0), (50, 68, BLUE, 0), (72, 68, GREEN, 0),
             (28, 102, GREEN, 0), (50, 102, BLUE, 0), (72, 102, GREEN, 0)]),
    # W on top, M below: PIL rotates counter-clockwise, so +angle leans the top of a stick left
    8: (30, [(22, 46, GREEN, 0), (40, 46, GREEN, 0.42), (60, 46, GREEN, -0.42), (78, 46, GREEN, 0),
             (22, 94, GREEN, 0), (40, 94, GREEN, -0.42), (60, 94, GREEN, 0.42), (78, 94, GREEN, 0)]),
    9: (24, [(28, y, GREEN, 0) for y in (36, 69, 102)] + [(50, y, RED, 0) for y in (36, 69, 102)]
        + [(72, y, GREEN, 0) for y in (36, 69, 102)]),
}


def souzu(n, red=False):
    img = new_face()
    if n == 1:
        bird(img)
    else:
        h, sticks = SOU_LAYOUTS[n]
        for x, y, col, ang in sticks:
            layer, pos = peanut_stick(x, y, h, RED if red else col, ang)
            img.alpha_composite(layer, pos)
    corner_index(img, str(n))
    return finish(img)


def bird(img):
    """The 1 sou: a bright sparrow perched on a bamboo twig, as on the reference set."""
    d = ImageDraw.Draw(img)
    S = s
    # twig
    d.line([S(20), S(96), S(80), S(84)], fill=GREEN_DARK, width=S(3))
    d.line([S(60), S(88), S(70), S(98)], fill=GREEN_DARK, width=S(2))
    for x, y in ((34, 93), (70, 86)):
        d.ellipse([S(x - 4), S(y - 7), S(x + 4), S(y - 1)], fill=GREEN)
    # tail streamers (red and blue), behind the body
    d.polygon([(S(36), S(70)), (S(18), S(108)), (S(26), S(110)), (S(44), S(76))], fill=RED)
    d.polygon([(S(40), S(72)), (S(30), S(114)), (S(37), S(114)), (S(48), S(78))], fill=BLUE)
    # body
    d.ellipse([S(34), S(50), S(70), S(84)], fill=GREEN_DARK)
    d.ellipse([S(36), S(52), S(68), S(82)], fill=GREEN)
    # yellow breast
    d.chord([S(46), S(56), S(72), S(84)], 300, 120, fill=YELLOW)
    # blue wing with red edge
    d.polygon([(S(38), S(60)), (S(60), S(64)), (S(52), S(78)), (S(34), S(74))], fill=BLUE)
    d.line([S(38), S(60), S(60), S(64)], fill=RED, width=S(1.6))
    # head, red cap, eye and beak
    d.ellipse([S(56), S(36), S(78), S(58)], fill=GREEN_DARK)
    d.ellipse([S(57.5), S(37.5), S(76.5), S(56.5)], fill=GREEN)
    d.chord([S(57), S(36), S(77), S(56)], 190, 350, fill=RED)
    d.ellipse([S(66), S(43), S(71), S(48)], fill=IVORY)
    d.ellipse([S(67.5), S(44.5), S(70), S(47)], fill=INK)
    d.polygon([(S(76), S(46)), (S(86), S(49)), (S(76), S(52))], fill=YELLOW)
    # feet on the twig
    for x in (50, 58):
        d.line([S(x), S(82), S(x - 1), S(90)], fill=RED, width=S(1.6))


# ---------------------------------------------------------------- characters and honours

NUMS = "一二三四伍六七八九"


def manzu(n, red=False):
    img = new_face()
    draw_glyph(img, NUMS[n - 1], 52, 42, 34, RED if red else BLUE, weight="Bold", squash=0.9)
    draw_glyph(img, "萬", 50, 90, 40, RED, weight="Bold", squash=0.95)
    corner_index(img, str(n))
    return finish(img)


def wind(ch, letter):
    img = new_face()
    draw_glyph(img, ch, 50, 70, 60, BLUE, weight="Bold")
    corner_index(img, letter)
    return finish(img)


def dragon(ch, color):
    img = new_face()
    draw_glyph(img, ch, 48, 66, 64, color, weight="Bold")
    return finish(img)


def white_dragon():
    """Blue double frame, as on the reference set."""
    img = new_face()
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([s(22), s(24), s(74), s(104)], radius=s(5), outline=BLUE, width=s(4.5))
    d.rounded_rectangle([s(30), s(32), s(66), s(96)], radius=s(3), outline=BLUE, width=s(2.5))
    return finish(img)


# ---------------------------------------------------------------- hidden tile (character art)

def cut_out_character(path):
    """
    The artwork has a dark background the same colour as its line art. Keep every bright pixel,
    the dark pixels enclosed by the drawing (eyes, moustache), and the dark pixels within a few
    pixels of the drawing (its outline); make the rest transparent.
    """
    src = np.asarray(Image.open(path).convert("RGB")).astype(np.int32)
    bg = src[0, 0]
    dark = np.abs(src - bg).sum(axis=2) < 40
    bright = ~dark
    # dark pixels connected to the border are background (plus the outline touching it)
    labels, _ = ndimage.label(dark)
    border_labels = set(np.unique(np.concatenate([labels[0], labels[-1], labels[:, 0], labels[:, -1]]))) - {0}
    outside = np.isin(labels, list(border_labels))
    enclosed_dark = dark & ~outside
    dist = ndimage.distance_transform_edt(~bright)
    outline = outside & (dist <= 2.6)
    alpha = bright | enclosed_dark | outline
    rgb = src.copy()
    rgb[dark] = INK  # crisp, consistent line colour
    rgba = np.dstack([rgb, alpha.astype(np.int32) * 255]).astype(np.uint8)
    img = Image.fromarray(rgba, "RGBA")
    return img.crop(img.getbbox())


def hidden_tile():
    img = new_face()
    art = cut_out_character(CHARACTER_ART)
    box_w, box_h = s(80), s(100)
    k = min(box_w / art.width, box_h / art.height)
    art = art.resize((int(art.width * k), int(art.height * k)), Image.LANCZOS)
    img.alpha_composite(art, (int((CW - art.width) / 2), int((CH - art.height) / 2 + s(2))))
    return finish(img)


# ---------------------------------------------------------------- back and body atlas

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
    d.rectangle([16, 0, 31, 15], fill=(228, 222, 204))
    d.rectangle([32, 0, 47, 15], fill=BACK_GREEN)
    d.rectangle([48, 0, 63, 15], fill=BACK_GREEN_DARK)
    d.rectangle([0, 16, 15, 31], fill=(200, 194, 174))
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
    out["east"] = wind("東", "E")
    out["south"] = wind("南", "S")
    out["west"] = wind("西", "W")
    out["north"] = wind("北", "N")
    out["red_dragon"] = dragon("中", RED)
    out["green_dragon"] = dragon("發", GREEN)
    out["white_dragon"] = white_dragon()
    out["unknown"] = hidden_tile()
    out["back"] = back()
    for name, img in out.items():
        img.save(os.path.join(OUT, f"mahjong_tile_{name}.png"))
    cover_atlas().save(os.path.join(OUT, "mahjong_tile_cover.png"))
    print(f"wrote {len(out) + 1} textures to {OUT}")


if __name__ == "__main__":
    main()
