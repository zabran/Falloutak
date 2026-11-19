"""The sheet's hover pictures as Pip-Boy screens (Marek 11-19: *"Fallout has these pip-boy images, it would be nice to have something
similar."*): our own mascot (a lanky shelter warden with glasses and a steel helmet, not Bethesda's Vault Boy), drawn in black ink by
FLUX.2 klein (the LollyWood-Lore tool `_tools/falloutak_icons.py --set cards`), turned here into green lines on a dark screen.

    python -I tools/cards.py <raw dir>

For every card of pack/cards.json it takes <raw dir>/<key>-s<seed>.png (the card's "seed", else the newest file of the key) and writes
  pack/assets/falloutak/textures/font/cards/<key>.png   128 x 128
  pack/assets/falloutak/font/cards.json                 a bitmap font, one private-use character a card (from U+E100)
  src/main/resources/data/cards.json                    key -> the character, read by the plugin (Icons.card)
  build/cards-sheet.png                                  all of them, to look at
In a tooltip the glyph is drawn HEIGHT pixels tall from the top of its line, so the plugin leaves LINES empty lines under it.
"""
import glob, io, json, os, re, sys
from PIL import Image, ImageChops, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SIZE, INNER = 128, 108
HEIGHT, ASCENT = 56, 7          # a tooltip line is 10 px: the picture covers its own line and the next 5
FIRST = 0xE100
SCREEN, BORDER, INK, GLOW = (8, 28, 12, 235), (46, 125, 50, 255), (124, 255, 138), (57, 200, 80)


def pick(raw, key, seed):
    if seed:
        return os.path.join(raw, "%s-s%d.png" % (key, seed))
    files = sorted(glob.glob(os.path.join(raw, key + "-s*.png")), key=os.path.getmtime)
    return files[-1] if files else None


def card(path):
    g = Image.open(path).convert("L")
    ink = g.point(lambda v: 255 if v < 150 else 0)
    box = ink.getbbox() or (0, 0, g.width, g.height)
    x0, y0, x1, y1 = box
    side = max(x1 - x0, y1 - y0) + 16
    cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
    sq = Image.new("L", (side, side), 255)   # paste, never crop past the edge: PIL fills that with black, which reads as ink
    sq.paste(g, (side // 2 - cx, side // 2 - cy))
    a = ImageChops.invert(sq).resize((INNER, INNER), Image.LANCZOS)
    a = a.point(lambda v: max(0, min(255, int((v - 35) * 1.9))))
    out = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(out)
    d.rounded_rectangle((0, 0, SIZE - 1, SIZE - 1), radius=8, fill=SCREEN, outline=BORDER, width=2)
    for y in range(3, SIZE - 3, 2):                                   # the screen's scan lines
        d.line((3, y, SIZE - 4, y), fill=(0, 0, 0, 40))
    off = (SIZE - INNER) // 2
    glow = Image.new("RGBA", (INNER, INNER), GLOW + (0,))
    glow.putalpha(a.filter(ImageFilter.GaussianBlur(1.6)).point(lambda v: v // 2))
    out.alpha_composite(glow, (off, off))
    line = Image.new("RGBA", (INNER, INNER), INK + (0,))
    line.putalpha(a)
    out.alpha_composite(line, (off, off))
    return out


def main():
    raw = sys.argv[1]
    d = json.load(io.open(os.path.join(ROOT, "pack", "cards.json"), encoding="utf-8"))
    tex = os.path.join(ROOT, "pack", "assets", "falloutak", "textures", "font", "cards")
    os.makedirs(tex, exist_ok=True)
    providers, chars, made = [], {}, []
    for i, c in enumerate(d["cards"]):
        src = pick(raw, c["key"], c.get("seed"))
        if not src or not os.path.exists(src):
            print("no picture:", c["key"])
            continue
        im = card(src)
        im.save(os.path.join(tex, c["key"] + ".png"))
        ch = chr(FIRST + i)
        providers.append({"type": "bitmap", "file": "falloutak:font/cards/%s.png" % c["key"], "ascent": ASCENT, "height": HEIGHT,
                          "chars": [ch]})
        chars[c["key"]] = FIRST + i
        c["seed"] = int(re.search(r"-s(\d+)\.png$", src).group(1))
        made.append(im)
    fdir = os.path.join(ROOT, "pack", "assets", "falloutak", "font")
    os.makedirs(fdir, exist_ok=True)
    io.open(os.path.join(fdir, "cards.json"), "w", encoding="utf-8", newline="\n").write(json.dumps({"providers": providers}, indent=1))
    io.open(os.path.join(ROOT, "src", "main", "resources", "data", "cards.json"), "w", encoding="utf-8", newline="\n").write(
        json.dumps({"height": HEIGHT, "newlines": -(-HEIGHT // 10), "chars": chars}, indent=1))
    io.open(os.path.join(ROOT, "pack", "cards.json"), "w", encoding="utf-8", newline="\n").write(json.dumps(d, ensure_ascii=False, indent=1))
    cols = 8
    sheet = Image.new("RGBA", (cols * (SIZE + 8) + 8, ((len(made) + cols - 1) // cols) * (SIZE + 8) + 8), (40, 20, 50, 255))
    for i, im in enumerate(made):
        sheet.alpha_composite(im, (8 + (i % cols) * (SIZE + 8), 8 + (i // cols) * (SIZE + 8)))
    os.makedirs(os.path.join(ROOT, "build"), exist_ok=True)
    sheet.save(os.path.join(ROOT, "build", "cards-sheet.png"))
    print(len(made), "cards")


if __name__ == "__main__":
    main()
