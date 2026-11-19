"""Turn the drawn item pictures into Minecraft item icons: the white background taken out (flood-filled from the edges), the
object cropped and centred, reduced to 32 x 32, its alpha made hard and its colours cut to a small palette.

    python tools/pixelize.py <raw folder> [--seed 1] [--size 32]

Reads <raw>/<slug>-s<seed>.png for every icon of pack/icons.json ('kind/x' is kind-x in the file name), writes
pack/assets/falloutak/textures/item/<slug>.png and build/icons-sheet.png (every icon x 4 on a checkerboard, to look at).
"""
import argparse, io, json, os, sys
from collections import deque
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT = os.path.join(ROOT, "pack", "assets", "falloutak", "textures", "item")


def unbackground(im, tol=60):
    im = im.convert("RGBA")
    w, h = im.size
    px = im.load()
    seen = bytearray(w * h)
    q = deque((x, y) for x in range(w) for y in (0, h - 1))
    q.extend((x, y) for y in range(h) for x in (0, w - 1))
    while q:
        x, y = q.popleft()
        i = y * w + x
        if seen[i]:
            continue
        seen[i] = 1
        r, g, b, a = px[x, y]
        if (255 - r) + (255 - g) + (255 - b) > tol:
            continue
        px[x, y] = (255, 255, 255, 0)
        for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
            if 0 <= nx < w and 0 <= ny < h and not seen[ny * w + nx]:
                q.append((nx, ny))
    return im


def icon(src, size):
    im = unbackground(Image.open(src))
    box = im.getbbox()
    if box is None:
        raise ValueError("empty picture: " + src)
    im = im.crop(box)
    side = int(max(im.size) * 1.08)
    sq = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    sq.paste(im, ((side - im.size[0]) // 2, (side - im.size[1]) // 2))
    small = sq.resize((size, size), Image.LANCZOS)
    alpha = small.getchannel("A").point(lambda v: 255 if v >= 110 else 0)
    rgb = small.convert("RGB").quantize(colors=20, method=Image.Quantize.MEDIANCUT).convert("RGB")
    out = rgb.convert("RGBA")
    out.putalpha(alpha)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("raw")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--size", type=int, default=32)
    a = ap.parse_args()
    icons = json.load(io.open(os.path.join(ROOT, "pack", "icons.json"), encoding="utf-8"))["icons"]
    made = []
    for ic in icons:
        src = os.path.join(a.raw, ic["slug"].replace("/", "-") + "-s%d.png" % a.seed)
        if not os.path.exists(src):
            print("no picture yet:", ic["slug"])
            continue
        dst = os.path.join(OUT, ic["slug"] + ".png")
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        icon(src, a.size).save(dst)
        made.append((ic["slug"], dst))
    # a sheet to look at: each icon x 4 on a checkerboard, its slug under it
    cols, cell = 8, a.size * 4 + 8
    rows = (len(made) + cols - 1) // cols
    sheet = Image.new("RGBA", (cols * cell, rows * cell), (200, 200, 200, 255))
    for k, (slug, dst) in enumerate(made):
        x, y = (k % cols) * cell + 4, (k // cols) * cell + 4
        for i in range(0, a.size * 4, 8):
            for j in range(0, a.size * 4, 8):
                if (i // 8 + j // 8) % 2:
                    sheet.paste((170, 170, 170, 255), (x + i, y + j, x + i + 8, y + j + 8))
        big = Image.open(dst).resize((a.size * 4, a.size * 4), Image.NEAREST)
        sheet.alpha_composite(big, (x, y))
    os.makedirs(os.path.join(ROOT, "build"), exist_ok=True)
    sheet.save(os.path.join(ROOT, "build", "icons-sheet.png"))
    print(len(made), "icons; the sheet: build/icons-sheet.png;", ", ".join(s for s, _ in made))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
