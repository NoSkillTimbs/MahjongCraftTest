"""
Game Table designs: one per wood type in the recipe. Bamboo keeps the classic mahjong table
(tools/gen_furniture.py); every other wood gets its own frame (that wood's colours) and a
Minecraft-style tabletop scene.

  python3 tools/gen_table_themes.py

Writes, for each theme:
  textures/block/game_table_<theme>.png       frame/legs atlas (same layout as mahjong_table.png)
  textures/block/game_table_<theme>_top.png   the tabletop (256x256, drawn at 64x64 and scaled up)
  models/block/game_table_<theme>.json        parent mahjong_table with those textures
and the blockstate and item model with one entry per theme.
"""
import json
import math
import os
import random
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_furniture as gf  # noqa: E402

ASSETS = gf.ASSETS
TEX = os.path.join(ASSETS, "textures/block")
MODELS = os.path.join(ASSETS, "models/block")

# theme, wood, (plank base, dark, light)
THEMES = [
    ("classic", "bamboo", None),
    ("forest", "oak", ((162, 130, 78), (112, 86, 50), (190, 154, 98))),
    ("taiga", "spruce", ((114, 84, 48), (74, 52, 28), (140, 106, 64))),
    ("meadow", "birch", ((196, 179, 123), (150, 134, 88), (216, 202, 150))),
    ("jungle", "jungle", ((160, 115, 80), (112, 76, 50), (186, 140, 100))),
    ("desert", "acacia", ((168, 90, 50), (120, 60, 32), (196, 114, 66))),
    ("dark_forest", "dark_oak", ((66, 43, 20), (40, 26, 12), (92, 62, 32))),
    ("swamp", "mangrove", ((117, 54, 48), (80, 34, 30), (145, 72, 64))),
    ("cherry_grove", "cherry", ((226, 178, 172), (180, 128, 124), (240, 204, 198))),
    ("volcano", "crimson", ((101, 48, 70), (66, 28, 46), (130, 64, 92))),
    ("warped_forest", "warped", ((43, 104, 99), (26, 70, 66), (60, 134, 126))),
]

N = 64  # pixel-art resolution of a tabletop
R = random.Random(7)


def clamp(v):
    return max(0, min(255, int(v)))


def shade(c, k):
    return tuple(clamp(x * k) for x in c)


def base(img, palette, seed):
    """A blocky noise field from a few palette colours, like a Minecraft block texture."""
    r = random.Random(seed)
    px = img.load()
    for y in range(N):
        for x in range(N):
            px[x, y] = palette[r.randrange(len(palette))]


def blob(img, cx, cy, rad, palette, seed, density=1.0):
    r = random.Random(seed)
    px = img.load()
    for y in range(int(cy - rad), int(cy + rad) + 1):
        for x in range(int(cx - rad), int(cx + rad) + 1):
            if 0 <= x < N and 0 <= y < N and (x - cx) ** 2 + (y - cy) ** 2 <= rad * rad and r.random() < density:
                px[x, y] = palette[r.randrange(len(palette))]


def flower(img, x, y, petal, center):
    px = img.load()
    for dx, dy in ((0, -1), (-1, 0), (1, 0), (0, 1)):
        if 0 <= x + dx < N and 0 <= y + dy < N:
            px[x + dx, y + dy] = petal
    px[x, y] = center


def corners(n_each=3, margin=6):
    """Spots near the corners and edges, keeping the middle clear for cards."""
    pts = []
    for cx, cy in ((margin, margin), (N - margin, margin), (margin, N - margin), (N - margin, N - margin)):
        for _ in range(n_each):
            pts.append((cx + R.randint(-4, 4), cy + R.randint(-4, 4)))
    for _ in range(n_each * 2):
        side = R.randrange(4)
        t = R.randint(10, N - 10)
        pts.append({0: (t, R.randint(2, 6)), 1: (t, N - R.randint(3, 7)), 2: (R.randint(2, 6), t), 3: (N - R.randint(3, 7), t)}[side])
    return pts


def edge_band(img, palette, width, seed):
    r = random.Random(seed)
    px = img.load()
    for y in range(N):
        for x in range(N):
            d = min(x, y, N - 1 - x, N - 1 - y)
            if d < width or (d == width and r.random() < 0.5):
                px[x, y] = palette[r.randrange(len(palette))]


def forest():
    img = Image.new("RGB", (N, N))
    base(img, [(91, 140, 55), (99, 151, 60), (84, 130, 50), (106, 158, 66)], 1)
    edge_band(img, [(56, 98, 36), (66, 112, 40), (48, 86, 30)], 3, 2)  # leaves
    for (x, y) in corners(4):
        blob(img, x, y, 3.2, [(56, 98, 36), (66, 112, 40), (44, 80, 28), (78, 124, 46)], x * 7 + y)
    for _ in range(18):
        x, y = R.randint(6, N - 7), R.randint(6, N - 7)
        if 14 < x < N - 14 and 14 < y < N - 14:
            continue
        flower(img, x, y, R.choice([(200, 40, 40), (240, 210, 60), (230, 230, 240)]), (240, 200, 60))
    return img


def taiga():
    img = Image.new("RGB", (N, N))
    base(img, [(236, 242, 246), (226, 234, 240), (244, 248, 250), (214, 224, 232)], 3)
    for (x, y) in corners(3):
        # little spruce trees seen from above
        blob(img, x, y, 3.4, [(38, 66, 44), (46, 78, 52), (32, 56, 38)], x + y * 3)
        img.putpixel((x, y), (92, 64, 40))
    for _ in range(30):
        x, y = R.randint(1, N - 2), R.randint(1, N - 2)
        img.putpixel((x, y), (196, 210, 222))
    return img


def meadow():
    img = Image.new("RGB", (N, N))
    base(img, [(121, 172, 74), (130, 182, 80), (112, 162, 68), (138, 188, 90)], 5)
    # birch log border
    px = img.load()
    for i in range(N):
        for w in range(2):
            for (x, y) in ((i, w), (i, N - 1 - w), (w, i), (N - 1 - w, i)):
                px[x, y] = (226, 222, 210) if (i // 3 + w) % 5 else (40, 40, 38)
    for _ in range(60):
        x, y = R.randint(4, N - 5), R.randint(4, N - 5)
        if 16 < x < N - 16 and 16 < y < N - 16:
            continue
        flower(img, x, y, R.choice([(178, 102, 214), (80, 120, 220), (240, 240, 240), (240, 140, 180), (250, 200, 50)]), (250, 230, 120))
    return img


def jungle():
    img = Image.new("RGB", (N, N))
    base(img, [(52, 112, 38), (60, 124, 42), (46, 100, 34), (70, 134, 48)], 7)
    edge_band(img, [(110, 110, 104), (92, 94, 88), (70, 110, 60), (124, 124, 116)], 2, 8)  # mossy cobblestone
    px = img.load()
    for _ in range(14):  # vines
        x = R.randint(4, N - 5)
        top = R.random() < 0.5
        for k in range(R.randint(4, 10)):
            y = 3 + k if top else N - 4 - k
            px[x, y] = (34, 86, 24)
            if k % 3 == 0 and 0 < x < N - 1:
                px[x + 1, y] = (44, 104, 30)
    for (x, y) in corners(1, 7):  # cocoa pods
        blob(img, x, y, 1.5, [(150, 84, 40), (128, 70, 30)], x * y)
    return img


def desert():
    img = Image.new("RGB", (N, N))
    base(img, [(219, 207, 163), (226, 214, 170), (210, 198, 152), (232, 222, 180)], 9)
    px = img.load()
    for row in range(6, N, 9):  # dune ripples
        for x in range(N):
            y = row + int(2 * math.sin(x / 5.0 + row))
            if 0 <= y < N:
                px[x, y] = (200, 186, 140)
    for (x, y) in corners(1, 7):  # cacti
        for k in range(-2, 3):
            px[x + k, y] = (86, 130, 44)
            px[x, y + k] = (86, 130, 44)
        px[x, y] = (110, 156, 60)
    for _ in range(10):  # dead bushes
        x, y = R.randint(3, N - 4), R.randint(3, N - 4)
        if 14 < x < N - 14 and 14 < y < N - 14:
            continue
        for d in ((0, 0), (1, -1), (-1, -1), (0, -2)):
            px[x + d[0], y + d[1]] = (120, 84, 46)
    return img


def dark_forest():
    img = Image.new("RGB", (N, N))
    base(img, [(92, 70, 48), (104, 80, 54), (84, 62, 40), (110, 92, 70)], 11)  # podzol
    edge_band(img, [(38, 62, 26), (30, 52, 20), (46, 72, 30)], 3, 12)  # dark oak leaves
    px = img.load()
    for (x, y) in corners(3):
        red = R.random() < 0.5
        cap = [(190, 40, 36), (220, 220, 220)] if red else [(150, 110, 80), (126, 92, 64)]
        blob(img, x, y, 2.2, cap, x + y)
    return img


def swamp():
    img = Image.new("RGB", (N, N))
    base(img, [(66, 92, 70), (60, 86, 66), (72, 98, 74), (56, 80, 62)], 13)  # murky water
    edge_band(img, [(92, 74, 52), (80, 64, 46), (100, 82, 58)], 3, 14)  # mud
    for (x, y) in corners(3, 8):  # lily pads
        blob(img, x, y, 2.4, [(40, 120, 40), (52, 136, 48)], x * 3 + y)
    px = img.load()
    for _ in range(8):  # mangrove roots
        x = R.randint(3, N - 4)
        for k in range(5):
            px[x + (k % 2), 2 + k] = (110, 60, 50)
            px[N - 4 - (k % 2), x] = (110, 60, 50)
    return img


def cherry_grove():
    img = Image.new("RGB", (N, N))
    base(img, [(121, 172, 74), (130, 182, 80), (112, 162, 68), (138, 188, 90)], 15)
    edge_band(img, [(240, 186, 214), (230, 164, 200), (248, 206, 226)], 3, 16)  # cherry leaves
    px = img.load()
    for _ in range(140):  # fallen petals
        x, y = R.randint(1, N - 2), R.randint(1, N - 2)
        if 18 < x < N - 18 and 18 < y < N - 18 and R.random() < 0.7:
            continue
        px[x, y] = R.choice([(246, 196, 222), (238, 170, 206), (250, 220, 236)])
    return img


def volcano():
    img = Image.new("RGB", (N, N))
    base(img, [(48, 44, 50), (58, 54, 60), (40, 36, 42), (66, 60, 64)], 17)  # basalt/blackstone
    px = img.load()
    # lava rivers along the edges and cracks toward the middle
    for i in range(N):
        for w in range(3):
            for (x, y) in ((i, w), (i, N - 1 - w), (w, i), (N - 1 - w, i)):
                px[x, y] = R.choice([(230, 110, 20), (250, 160, 40), (210, 80, 16)]) if w < 2 else (120, 40, 20)
    for _ in range(10):
        x, y = R.choice([(R.randint(3, N - 4), 3), (R.randint(3, N - 4), N - 4), (3, R.randint(3, N - 4)), (N - 4, R.randint(3, N - 4))])
        dx = 1 if x < N / 2 else -1
        dy = 1 if y < N / 2 else -1
        for k in range(R.randint(4, 9)):
            x += dx if R.random() < 0.6 else 0
            y += dy if R.random() < 0.6 else 0
            if 0 <= x < N and 0 <= y < N:
                px[x, y] = R.choice([(240, 120, 30), (200, 70, 20)])
    for (x, y) in corners(2, 7):  # magma blocks
        blob(img, x, y, 2, [(150, 50, 20), (200, 90, 30), (90, 30, 16)], x + y)
    return img


def warped_forest():
    img = Image.new("RGB", (N, N))
    base(img, [(44, 96, 92), (40, 120, 108), (36, 84, 82), (50, 132, 116)], 19)  # warped nylium
    edge_band(img, [(30, 70, 70), (24, 60, 60)], 2, 20)
    for (x, y) in corners(3):  # warped fungi
        blob(img, x, y, 2.3, [(22, 150, 136), (32, 176, 160)], x * 5 + y)
        img.putpixel((x, y), (250, 120, 40))
    px = img.load()
    for _ in range(40):
        x, y = R.randint(1, N - 2), R.randint(1, N - 2)
        px[x, y] = (110, 220, 200) if R.random() < 0.2 else px[x, y]
    return img


SCENES = {
    "forest": forest, "taiga": taiga, "meadow": meadow, "jungle": jungle, "desert": desert,
    "dark_forest": dark_forest, "swamp": swamp, "cherry_grove": cherry_grove, "volcano": volcano,
    "warped_forest": warped_forest,
}


def frame_texture(palette):
    """The frame atlas in another wood, keeping mahjong_table.png's layout (the model's UVs)."""
    old = (gf.WALNUT, gf.WALNUT_DARK, gf.WALNUT_LIGHT)
    gf.WALNUT, gf.WALNUT_DARK, gf.WALNUT_LIGHT = palette
    try:
        img, _ = gf.table_texture()
    finally:
        gf.WALNUT, gf.WALNUT_DARK, gf.WALNUT_LIGHT = old
    return img


def main():
    variants = {}
    overrides = []
    for i, (theme, wood, palette) in enumerate(THEMES):
        if theme == "classic":
            model = "mahjongcraft:block/mahjong_table"
        else:
            frame_texture(palette).save(os.path.join(TEX, f"game_table_{theme}.png"))
            top = SCENES[theme]().resize((256, 256), Image.NEAREST)
            top.save(os.path.join(TEX, f"game_table_{theme}_top.png"))
            model = f"mahjongcraft:block/game_table_{theme}"
            with open(os.path.join(MODELS, f"game_table_{theme}.json"), "w") as f:
                json.dump({"parent": "mahjongcraft:block/mahjong_table", "textures": {
                    "0": f"mahjongcraft:block/game_table_{theme}",
                    "1": f"mahjongcraft:block/game_table_{theme}_top",
                    "particle": f"mahjongcraft:block/game_table_{theme}"}}, f, indent=2)
        variants[f"theme={theme}"] = {"model": model}
        if i > 0:
            overrides.append({"predicate": {"mahjongcraft:theme": round(i / 16, 4)}, "model": model})
    with open(os.path.join(ASSETS, "blockstates/mahjong_table.json"), "w") as f:
        json.dump({"variants": variants}, f, indent=2)
    with open(os.path.join(ASSETS, "models/item/mahjong_table.json"), "w") as f:
        json.dump({"parent": "mahjongcraft:block/mahjong_table", "overrides": overrides}, f, indent=2)
    # a contact sheet for checking
    sheet = Image.new("RGB", (5 * 136, 2 * 136), (20, 20, 20))
    for k, (theme, _, _) in enumerate(THEMES[1:]):
        im = Image.open(os.path.join(TEX, f"game_table_{theme}_top.png")).resize((128, 128))
        sheet.paste(im, ((k % 5) * 136 + 4, (k // 5) * 136 + 4))
    sheet.save(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "build_table_themes_preview.png"))
    print("wrote", len(THEMES) - 1, "themes")


if __name__ == "__main__":
    main()
