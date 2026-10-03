"""
Generates the MahjongCraft table and stool: block models + textures.

  python3 tools/gen_furniture.py

Writes:
  models/block/mahjong_table.json, textures/block/mahjong_table.png (256x256)
  models/block/mahjong_stool.json, textures/block/mahjong_stool.png (64x64)

Gameplay-relevant geometry is kept from the original models:
  table: felt play surface at y 14..16 over x/z -15..31, 1-wide rim at y 14..17 around it
  stool: seat top at y 10
If you change the table's shape, update the collision boxes in block/MahjongTable.kt to match.
"""
import json
import math
import os
import random

from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
ASSETS = os.path.join(ROOT, "src/main/resources/assets/mahjongcraft")

# palette
FELT = (31, 107, 69)
FELT_DARK = (21, 78, 50)
GOLD = (201, 164, 84)
WALNUT = (92, 58, 34)
WALNUT_DARK = (58, 35, 20)
WALNUT_LIGHT = (124, 82, 50)
BRASS = (196, 158, 76)
BRASS_DARK = (138, 104, 44)
LEATHER = (128, 30, 38)
LEATHER_DARK = (86, 18, 24)

rng = random.Random(1119)


# ------------------------------------------------------------------ texture helpers

def noise_fill(img, box, base, amount, seed):
    """Fills box with base colour plus fine per-pixel noise."""
    r = random.Random(seed)
    x0, y0, x1, y1 = box
    px = img.load()
    for y in range(y0, y1):
        for x in range(x0, x1):
            n = r.uniform(-amount, amount)
            px[x, y] = tuple(max(0, min(255, int(c + n))) for c in base) + (255,)


def wood(img, box, base, dark, light, vertical, seed):
    """Wood grain: wavy darker lines along the grain, lighter streaks, fine noise."""
    r = random.Random(seed)
    x0, y0, x1, y1 = box
    w, h = x1 - x0, y1 - y0
    length, across = (h, w) if vertical else (w, h)
    tile = Image.new("RGB", (length, across), base)
    px = tile.load()
    lines = []
    pos = 0.0
    while pos < across:
        lines.append((pos, r.uniform(0.6, 1.6), r.uniform(0, 6.28), r.uniform(0.01, 0.04), r.random() < 0.3))
        pos += r.uniform(1.6, 3.4)
    for a in range(length):
        for b in range(across):
            c = list(base)
            for (lp, amp, ph, fr, is_light) in lines:
                center = lp + amp * math.sin(a * fr * 6.28 + ph)
                d = abs(b - center)
                if d < 0.7:
                    k = (0.7 - d) / 0.7
                    tgt = light if is_light else dark
                    c = [int(c[i] + (tgt[i] - c[i]) * k * 0.55) for i in range(3)]
            n = r.uniform(-5, 5)
            px[a, b] = tuple(max(0, min(255, int(v + n))) for v in c)
    if vertical:
        tile = tile.transpose(Image.Transpose.ROTATE_90)
    img.paste(tile, (x0, y0))


def brass(img, box):
    x0, y0, x1, y1 = box
    d = ImageDraw.Draw(img)
    for y in range(y0, y1):
        t = (y - y0) / max(1, (y1 - y0 - 1))
        k = 0.5 + 0.5 * math.cos(t * math.pi * 1.6)  # bright band near the top
        col = tuple(int(BRASS_DARK[i] + (min(255, BRASS[i] + 40) - BRASS_DARK[i]) * k) for i in range(3))
        d.line([(x0, y), (x1 - 1, y)], fill=col)


# ------------------------------------------------------------------ model helpers

class Atlas:
    """Named texture regions in pixels; converts to model UVs (0..16) for a face of a given size."""

    def __init__(self, size, k):
        self.size = size     # texture size in px
        self.k = k           # px per model unit
        self.regions = {}

    def add(self, name, box):
        self.regions[name] = box

    def uv(self, name, fw, fh, stretch=False, offset=(0, 0)):
        x0, y0, x1, y1 = self.regions[name]
        rw, rh = x1 - x0, y1 - y0
        if stretch:
            w, h = rw, rh
        else:
            w, h = min(rw, max(1.0, fw * self.k)), min(rh, max(1.0, fh * self.k))
        ox = min(offset[0], rw - w)
        oy = min(offset[1], rh - h)
        u = 16 / self.size
        return [round((x0 + ox) * u, 4), round((y0 + oy) * u, 4), round((x0 + ox + w) * u, 4), round((y0 + oy + h) * u, 4)]


def face_dims(frm, to, face):
    dx, dy, dz = to[0] - frm[0], to[1] - frm[1], to[2] - frm[2]
    return {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy), "west": (dz, dy),
            "up": (dx, dz), "down": (dx, dz)}[face]


def box(atlas, name, frm, to, mat, overrides=None, skip=(), texture="#0"):
    """
    One element. mat is a wood-like material name; faces long along v automatically use the
    '<mat>_v' region when the atlas has one (so grain follows the long side).
    overrides: {face: (material, stretch)}.
    """
    overrides = overrides or {}
    faces = {}
    for f in ("north", "south", "east", "west", "up", "down"):
        if f in skip:
            continue
        fw, fh = face_dims(frm, to, f)
        m, stretch = overrides.get(f, (mat, False))
        if not stretch and m + "_v" in atlas.regions and fh > fw:
            m = m + "_v"
        off = (rng.randint(0, 40), rng.randint(0, 40))
        faces[f] = {"uv": atlas.uv(m, fw, fh, stretch, off), "texture": texture}
    return {"name": name, "from": [round(v, 4) for v in frm], "to": [round(v, 4) for v in to], "faces": faces}


def write_model(path, elements, texture_ref, texture_size, old_display):
    model = {
        "credit": "MahjongCraft, generated by tools/gen_furniture.py",
        "texture_size": texture_size,
        "textures": {"0": texture_ref, "particle": texture_ref},
        "elements": elements,
    }
    if old_display:
        model["display"] = old_display
    with open(path, "w", encoding="utf-8") as f:
        json.dump(model, f, indent="\t")
        f.write("\n")


def old_display(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f).get("display")
    except FileNotFoundError:
        return None


# ------------------------------------------------------------------ table

def table_texture():
    S = 256
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    atlas = Atlas(S, k=128 / 47)

    # felt top (0,0)-(128,128): noisy green, darker towards the rim, gold inlay line, centre panel
    noise_fill(img, (0, 0, 128, 128), FELT, 7, 1)
    felt = img.crop((0, 0, 128, 128))
    shade = Image.new("L", (128, 128), 0)
    ImageDraw.Draw(shade).rectangle([0, 0, 127, 127], outline=70, width=6)
    shade = shade.filter(ImageFilter.GaussianBlur(5))
    dark = Image.new("RGBA", (128, 128), FELT_DARK + (255,))
    felt = Image.composite(dark, felt, shade)
    d = ImageDraw.Draw(felt)
    d.rectangle([7, 7, 120, 120], outline=GOLD, width=1)
    # centre panel (the score labels float above it)
    d.rounded_rectangle([46, 46, 81, 81], radius=4, fill=FELT_DARK, outline=GOLD, width=1)
    d.rounded_rectangle([49, 49, 78, 78], radius=3, outline=(160, 130, 66), width=1)
    d.polygon([(63.5, 53), (74, 63.5), (63.5, 74), (53, 63.5)], outline=GOLD)
    img.paste(felt, (0, 0))
    atlas.add("felt", (0, 0, 128, 128))

    wood(img, (128, 0, 256, 64), WALNUT, WALNUT_DARK, WALNUT_LIGHT, vertical=False, seed=2)
    atlas.add("wood", (128, 0, 256, 64))
    wood(img, (128, 64, 192, 192), WALNUT, WALNUT_DARK, WALNUT_LIGHT, vertical=True, seed=3)
    atlas.add("wood_v", (128, 64, 192, 192))
    wood(img, (192, 64, 256, 128), WALNUT_DARK, (36, 20, 10), WALNUT, vertical=False, seed=4)
    atlas.add("dark", (192, 64, 256, 128))
    wood(img, (192, 192, 256, 256), WALNUT_DARK, (36, 20, 10), WALNUT, vertical=True, seed=8)
    atlas.add("dark_v", (192, 192, 256, 256))
    brass(img, (192, 128, 256, 160))
    atlas.add("brass", (192, 128, 256, 160))

    # apron (0,128)-(128,192): horizontal wood with a recessed panel line
    wood(img, (0, 128, 128, 192), WALNUT, WALNUT_DARK, WALNUT_LIGHT, vertical=False, seed=5)
    d = ImageDraw.Draw(img)
    d.line([(0, 129), (127, 129)], fill=WALNUT_LIGHT)
    d.line([(0, 136), (127, 136)], fill=WALNUT_DARK)
    atlas.add("apron", (0, 128, 128, 192))
    # underside (0,192)-(128,256)
    noise_fill(img, (0, 192, 128, 256), (52, 34, 22), 4, 6)
    atlas.add("under", (0, 192, 128, 256))
    return img, atlas


def table_model(atlas):
    E = []
    # felt play surface (same box as the original top)
    E.append(box(atlas, "felt", [-15, 14, -15], [31, 16, 31], "wood",
                 overrides={"up": ("felt", True), "down": ("under", False)}))
    # rim, 1 wide at y 14..17, stopping short of the brass corners
    E.append(box(atlas, "rim_north", [-14.5, 14, -16], [30.5, 17, -15], "wood"))
    E.append(box(atlas, "rim_south", [-14.5, 14, 31], [30.5, 17, 32], "wood"))
    E.append(box(atlas, "rim_west", [-16, 14, -14.5], [-15, 17, 30.5], "wood"))
    E.append(box(atlas, "rim_east", [31, 14, -14.5], [32, 17, 30.5], "wood"))
    # rounded rim top: a half-width cap
    E.append(box(atlas, "cap_north", [-14.5, 17, -15.75], [30.5, 17.25, -15.25], "wood"))
    E.append(box(atlas, "cap_south", [-14.5, 17, 31.25], [30.5, 17.25, 31.75], "wood"))
    E.append(box(atlas, "cap_west", [-15.75, 17, -14.5], [-15.25, 17.25, 30.5], "wood"))
    E.append(box(atlas, "cap_east", [31.25, 17, -14.5], [31.75, 17.25, 30.5], "wood"))
    # brass corners
    for cx, cz in ((-16, -16), (30.5, -16), (-16, 30.5), (30.5, 30.5)):
        E.append(box(atlas, "corner", [cx, 13, cz], [cx + 1.5, 17.4, cz + 1.5], "brass",
                     overrides={f: ("brass", False) for f in ("north", "south", "east", "west", "up", "down")}))
    # trim band under the rim, slightly recessed
    E.append(box(atlas, "trim", [-15.5, 13, -15.5], [31.5, 14, 31.5], "dark",
                 overrides={"down": ("under", False)}))
    # apron
    E.append(box(atlas, "apron", [-14.5, 10, -14.5], [30.5, 13, 30.5], "apron",
                 overrides={"down": ("under", False), "up": ("under", False)}))
    # legs: tapered, with brass feet
    for lx, lz in ((-14, -14), (27, -14), (-14, 27), (27, 27)):
        E.append(box(atlas, "leg_upper", [lx, 4, lz], [lx + 3, 10, lz + 3], "wood", skip=("up",)))
        E.append(box(atlas, "leg_lower", [lx + 0.25, 0.75, lz + 0.25], [lx + 2.75, 4, lz + 2.75], "wood"))
        E.append(box(atlas, "foot", [lx + 0.1, 0, lz + 0.1], [lx + 2.9, 0.75, lz + 2.9], "brass",
                     overrides={f: ("brass", False) for f in ("north", "south", "east", "west", "up", "down")}))
    # stretchers between the legs
    E.append(box(atlas, "stretcher_n", [-11, 2.5, -13], [27, 3.5, -12], "dark"))
    E.append(box(atlas, "stretcher_s", [-11, 2.5, 28], [27, 3.5, 29], "dark"))
    E.append(box(atlas, "stretcher_w", [-13, 2.5, -11], [-12, 3.5, 27], "dark"))
    E.append(box(atlas, "stretcher_e", [28, 2.5, -11], [29, 3.5, 27], "dark"))
    return E


# ------------------------------------------------------------------ stool

def stool_texture():
    S = 64
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    atlas = Atlas(S, k=2)
    # tufted leather (0,0)-(32,32)
    noise_fill(img, (0, 0, 32, 32), LEATHER, 6, 11)
    d = ImageDraw.Draw(img)
    for gx in (8, 16, 24):
        for gy in (8, 16, 24):
            d.point((gx, gy), fill=LEATHER_DARK)
            d.point((gx + 1, gy), fill=LEATHER_DARK)
            d.point((gx, gy + 1), fill=(150, 50, 58))
    d.rectangle([0, 0, 31, 31], outline=LEATHER_DARK)
    atlas.add("leather", (0, 0, 32, 32))
    noise_fill(img, (0, 32, 32, 40), LEATHER_DARK, 5, 12)
    atlas.add("leather_side", (0, 32, 32, 40))
    wood(img, (32, 0, 64, 16), WALNUT, WALNUT_DARK, WALNUT_LIGHT, vertical=False, seed=13)
    atlas.add("wood", (32, 0, 64, 16))
    wood(img, (32, 16, 48, 48), WALNUT, WALNUT_DARK, WALNUT_LIGHT, vertical=True, seed=14)
    atlas.add("wood_v", (32, 16, 48, 48))
    wood(img, (48, 16, 64, 32), WALNUT_DARK, (36, 20, 10), WALNUT, vertical=False, seed=15)
    atlas.add("dark", (48, 16, 64, 32))
    brass(img, (48, 32, 64, 40))
    atlas.add("brass", (48, 32, 64, 40))
    return img, atlas


def stool_model(atlas):
    E = []
    leather_sides = {f: ("leather_side", False) for f in ("north", "south", "east", "west")}
    # cushion: a firm base with a slightly smaller, tufted top so the edge reads as rounded; top at y = 10
    E.append(box(atlas, "cushion_base", [1.5, 8.5, 1.5], [14.5, 9.5, 14.5], "leather",
                 overrides={**leather_sides, "up": ("leather_side", False)}))
    E.append(box(atlas, "cushion_top", [2.25, 9.5, 2.25], [13.75, 10, 13.75], "leather",
                 overrides={"up": ("leather", True)}, skip=("down",)))
    # seat frame and apron
    E.append(box(atlas, "frame", [1, 7.5, 1], [15, 8.5, 15], "wood"))
    E.append(box(atlas, "apron", [2, 6, 2], [14, 7.5, 14], "dark"))
    # legs with brass feet (same corners as the original stool)
    for lx, lz in ((3, 3), (11, 3), (3, 11), (11, 11)):
        E.append(box(atlas, "leg", [lx, 0.5, lz], [lx + 2, 6, lz + 2], "wood", skip=("up",)))
        E.append(box(atlas, "foot", [lx - 0.15, 0, lz - 0.15], [lx + 2.15, 0.5, lz + 2.15], "brass",
                     overrides={f: ("brass", False) for f in ("north", "south", "east", "west", "up", "down")}))
    # stretchers
    E.append(box(atlas, "stretcher_n", [5, 3, 3.5], [11, 4, 4.5], "dark"))
    E.append(box(atlas, "stretcher_s", [5, 3, 11.5], [11, 4, 12.5], "dark"))
    E.append(box(atlas, "stretcher_w", [3.5, 3.5, 5], [4.5, 4.5, 11], "dark"))
    E.append(box(atlas, "stretcher_e", [11.5, 3.5, 5], [12.5, 4.5, 11], "dark"))
    return E


def main():
    tex_dir = os.path.join(ASSETS, "textures/block")
    model_dir = os.path.join(ASSETS, "models/block")

    img, atlas = table_texture()
    img.save(os.path.join(tex_dir, "mahjong_table.png"))
    path = os.path.join(model_dir, "mahjong_table.json")
    write_model(path, table_model(atlas), "mahjongcraft:block/mahjong_table", [256, 256], old_display(path))

    img, atlas = stool_texture()
    img.save(os.path.join(tex_dir, "mahjong_stool.png"))
    path = os.path.join(model_dir, "mahjong_stool.json")
    write_model(path, stool_model(atlas), "mahjongcraft:block/mahjong_stool", [64, 64], old_display(path))
    print("wrote table and stool models and textures")


if __name__ == "__main__":
    main()
