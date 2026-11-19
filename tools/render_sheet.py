"""Draws the sheet's pages as the dialog shows them, with the client's own font, to look at the layout without a game.

    python -I tools/render_sheet.py build/sheet-preview.json <font dir> <out dir>

<font dir> = a folder holding `assets/minecraft/font/include/*.json` and `assets/minecraft/textures/font/*.png`, unzipped from the
client jar (`%APPDATA%/.minecraft/versions/1.21.11/1.21.11.jar`). The JSON comes from the test `SheetPreviewTest`. Each line is
centred by its own width, as the client centres a plain message; the GUI is drawn at scale 2 in a 986-pixel-wide window (Marek's
screenshot of 11-14). A model of the client, not the client: the game is the real check."""
import io, json, sys
from PIL import Image, ImageFilter

sys.stdout.reconfigure(encoding="utf-8")
SRC, FONT, OUT = sys.argv[1], sys.argv[2] + "/assets/minecraft/", sys.argv[3]
SCALE, WIN = 2, 493          # GUI scale, window width in GUI pixels
LINE, GAP = 9, 6             # a text line, the space between body elements [the gap mine]

glyphs = {}                   # codepoint -> (image or None, advance)
for k, v in json.load(io.open(FONT + "font/include/space.json", encoding="utf-8"))["providers"][0]["advances"].items():
    glyphs[ord(k)] = (None, v)
for p in json.load(io.open(FONT + "font/include/default.json", encoding="utf-8"))["providers"]:
    if p["type"] != "bitmap":
        continue
    img = Image.open(FONT + "textures/" + p["file"].split(":")[1]).convert("RGBA")
    rows = p["chars"]
    cw, ch = img.width // len(rows[0]), img.height // len(rows)
    h = p.get("height", 8)
    asc = p["ascent"]
    for r, row in enumerate(rows):
        for c, chr_ in enumerate(row):
            cp = ord(chr_)
            if cp == 0 or cp in glyphs:
                continue
            cell = img.crop((c * cw, r * ch, (c + 1) * cw, (r + 1) * ch))
            box = cell.getbbox()
            w = box[2] if box else 0
            if h != ch:
                cell = cell.resize((max(1, round(cw * h / ch)), h), Image.NEAREST)
                w = round(w * h / ch)
            glyphs[cp] = ((cell, 7 - asc), w + 1)


def draw_text(canvas, x, y, text, color, bold):
    for chr_ in text:
        g, adv = glyphs.get(ord(chr_), (None, 6))
        if g:
            cell, dy = g
            mask = cell.split()[3]
            for ox, col in ((1, (color >> 2) & 0x3F3F3F), (0, color)):   # the shadow a quarter as bright, 1 px down-right
                solid = Image.new("RGBA", cell.size, ((col >> 16) & 255, (col >> 8) & 255, col & 255, 255))
                for bx in ((0, 1) if bold else (0,)):
                    canvas.paste(solid, (x + ox + bx, y + ox + dy), mask)
        x += adv + (1 if bold else 0)
    return x


def width(segs):
    return sum(sum(glyphs.get(ord(c), (None, 6))[1] + (1 if s["b"] else 0) for c in s["t"]) for s in segs)


data = json.load(io.open(SRC, encoding="utf-8"))
for page, blocks in data.items():
    height = 30 + sum(len(b) * LINE + GAP for b in blocks) + 10
    canvas = Image.new("RGBA", (WIN, height), (58, 66, 78, 255))
    y = 30
    title = page.capitalize()
    draw_text(canvas, (WIN - width([{"t": title, "b": False}])) // 2, 10, title, 0xFFFFFF, False)
    for b in blocks:
        for segs in b:
            x = (WIN - width(segs)) // 2
            for s in segs:
                x = draw_text(canvas, x, y, s["t"], s["c"], s["b"])
            y += LINE
        y += GAP
    canvas = canvas.resize((WIN * SCALE, height * SCALE), Image.NEAREST)
    path = OUT + "/sheet-" + page.lower() + ".png"
    canvas.save(path)
    print(path, canvas.size)
