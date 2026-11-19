"""The Radmie ("Radioaktivní zmije", the Czech radscorpion; Marek 11-13: *"It is big, but hides in buildings and rubble, attacks by
ambush launching it whole body at distance for venomous bite attack and then fling back."*; the presentation's slide: a red-and-yellow
snake): a Blockbench model for BetterModel, written by script, with its animations and preview pictures.

    python -I tools/models/radmie.py       -> models/radmie.bbmodel, models/radmie.png, build/models/radmie-*.gif + radmie-sheet.png

The chain of bones runs from the middle (`mid`, the base entity's spot) forward to the head and back to the tail. It faces north
(-Z). Animations: idle (coiled, head up, swaying), walk (slithering), lunge (the whole body launched 3 blocks forward, the bite, back
into the coil), hide (sinks into the rubble, holds), spawn (rises out of it), death.
"""
import math, os, random, sys
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bbmodel import Bone, Cube, Model, render

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# ------------------------------------------------------------------ the texture: 64 x 64, regions of 16 x 16
RED, DARK, YEL, PALE, GLOW = (168, 34, 26), (88, 16, 14), (236, 186, 46), (226, 208, 136), (140, 255, 80)


def texture():
    rnd = random.Random(7)
    im = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    px = im.load()

    def fill(x0, y0, f):
        for y in range(16):
            for x in range(16):
                px[x0 + x, y0 + y] = f(x, y) + (255,)

    def scales(c, x, y):   # a scale pattern: darker rims every 4 px, a little noise
        k = 0.82 if (x + (y // 4) * 2) % 4 == 0 or y % 4 == 0 else 1.0
        n = rnd.uniform(0.9, 1.08)
        return tuple(max(0, min(255, int(v * k * n))) for v in c)

    def back(x, y):        # A: the back, a yellow zig-zag on red
        zig = abs(((y * 2) % 16) - 8)
        if abs(x - 4 - zig) <= 1:
            return scales(YEL, x, y)
        return scales(RED, x, y)

    def side(x, y):        # B: the flank, red above a yellow band, the belly's edge below
        if y >= 12:
            return scales(PALE, x, y)
        if 8 <= y <= 10:
            return scales(YEL, x, y)
        if (x * 3 + y) % 11 == 0:
            return GLOW if rnd.random() < 0.5 else scales(DARK, x, y)
        return scales(RED, x, y)

    def belly(x, y):       # C: the belly plates
        return scales(PALE, x, y) if y % 4 else tuple(int(v * 0.75) for v in PALE)

    def head_top(x, y):    # D: the head: a yellow V and dark brows
        v = abs(x - 8)
        if abs(y - (4 + v)) <= 1 and y < 13:
            return scales(YEL, x, y)
        return scales(DARK if y < 3 else RED, x, y)

    def head_side(x, y):   # E: an eye glowing green with a slit
        if (x - 5) ** 2 + (y - 6) ** 2 <= 7:
            return (20, 20, 20) if x == 5 else GLOW
        if y >= 12:
            return scales(PALE, x, y)
        return scales(RED, x, y)

    def mouth(x, y):       # F: the inside of the mouth
        return (150, 40, 60) if y > 2 else (240, 240, 230)

    def snout(x, y):       # G: the snout's front, two nostrils
        if y in (5, 6) and x in (5, 10):
            return (20, 10, 10)
        return scales(RED, x, y)

    def tail(x, y):        # H: rings and glowing warts
        if (x - 8) ** 2 + (y - 8) ** 2 <= 3 or (x * 7 + y * 5) % 23 == 0:
            return GLOW
        return scales(YEL if (y // 3) % 2 else RED, x, y)

    for i, f in enumerate((back, side, belly, head_top)):
        fill(16 * i, 0, f)
    for i, f in enumerate((head_side, mouth, snout, tail)):
        fill(16 * i, 16, f)
    ImageDraw.Draw(im).rectangle((0, 32, 15, 47), fill=(245, 245, 235, 255))   # I: the fangs
    return im


A, B, C, D = (0, 0, 16, 16), (16, 0, 32, 16), (32, 0, 48, 16), (48, 0, 64, 16)
E, F, G, H, I = (0, 16, 16, 32), (16, 16, 32, 32), (32, 16, 48, 32), (48, 16, 64, 32), (0, 32, 16, 48)


def seg(name, z0, z1, w, h, uvset="body"):
    """A body segment from z0 to z1 (z0 < z1), w wide, h tall, standing on the ground."""
    if uvset == "tail":
        uv = {f: list(H) for f in ("north", "south", "east", "west", "up", "down")}
    else:
        uv = {"up": list(A), "east": list(B), "west": list(B), "down": list(C), "north": list(B), "south": list(B)}
    return Cube(name, (-w / 2, 0, z0), (w / 2, h, z1), uv)


def model():
    L = 12
    head = Bone("head", (0, 3, -64), cubes=[
        Cube("skull", (-6, 3, -80), (6, 9, -64), {"up": list(D), "east": list(E), "west": list(E), "north": list(G),
                                                  "south": list(B), "down": list(F)}),
        Cube("fang_l", (-4, 1, -79), (-3, 3, -78), {f: list(I) for f in ("north", "south", "east", "west")}),
        Cube("fang_r", (3, 1, -79), (4, 3, -78), {f: list(I) for f in ("north", "south", "east", "west")}),
    ], children=[Bone("jaw", (0, 3, -66), cubes=[
        Cube("jaw", (-5, 0, -79), (5, 3, -66), {"up": list(F), "east": list(B), "west": list(B), "down": list(C), "north": list(G),
                                               "south": list(B)})])])
    neck = Bone("neck", (0, 0, -54), cubes=[seg("neck", -64, -54, 8, 7)], children=[head])
    s1 = Bone("seg1", (0, 0, -42), cubes=[seg("seg1", -54, -42, 9, 8)], children=[neck])
    s2 = Bone("seg2", (0, 0, -30), cubes=[seg("seg2", -42, -30, 10, 8)], children=[s1])
    s3 = Bone("seg3", (0, 0, -18), cubes=[seg("seg3", -30, -18, 10, 8)], children=[s2])
    s4 = Bone("seg4", (0, 0, -6), cubes=[seg("seg4", -18, -6, 10, 8)], children=[s3])
    t2 = Bone("tail2", (0, 0, 54), cubes=[seg("tail2", 54, 66, 4, 4, "tail")])
    t1 = Bone("tail1", (0, 0, 42), cubes=[seg("tail1", 42, 54, 6, 5, "tail")], children=[t2])
    s8 = Bone("seg8", (0, 0, 30), cubes=[seg("seg8", 30, 42, 8, 6)], children=[t1])
    s7 = Bone("seg7", (0, 0, 18), cubes=[seg("seg7", 18, 30, 9, 7)], children=[s8])
    s6 = Bone("seg6", (0, 0, 6), cubes=[seg("seg6", 6, 18, 10, 8)], children=[s7])
    mid = Bone("mid", (0, 0, 0), cubes=[seg("mid", -6, 6, 10, 8)], children=[s4, s6])
    return [mid]


FRONT = ["seg4", "seg3", "seg2", "seg1", "neck", "head"]
BACK = ["seg6", "seg7", "seg8", "tail1", "tail2"]


def kf(x, y, z):
    """A keyframe's rotation from the turn I mean (x raises the front, y turns left seen from above): Blockbench's animator
    applies (-x, -y, +z)."""
    return (-x, -y, z)


# the coil: the back part curled on the ground, the front part raised in an S with the head level
COIL = {"seg4": (8, 40, 0), "seg3": (22, -55, 0), "seg2": (28, 30, 0), "seg1": (-8, 25, 0), "neck": (-26, -20, 0),
        "head": (-20, 0, 0), "seg6": (0, 55, 0), "seg7": (0, 60, 0), "seg8": (0, 60, 0), "tail1": (0, 55, 0), "tail2": (0, 45, 0)}


def pose(p, t, extra=None):
    out = {}
    for b, r in p.items():
        out.setdefault(b, {}).setdefault("rotation", []).append((t, kf(*r)))
    for b, d in (extra or {}).items():
        for ch, v in d.items():
            out.setdefault(b, {}).setdefault(ch, []).append((t, v))
    return out


def merge(*poses):
    out = {}
    for p in poses:
        for b, chans in p.items():
            for ch, keys in chans.items():
                out.setdefault(b, {}).setdefault(ch, []).extend(keys)
    return out


def scaled(p, k, add=None):
    q = {b: tuple(v * k for v in r) for b, r in p.items()}
    for b, r in (add or {}).items():
        q[b] = tuple(a + c for a, c in zip(q.get(b, (0, 0, 0)), r))
    return q


def animations():
    anim = {}
    # idle: coiled, the head swaying and the jaw twitching (2 s, loop)
    anim["idle"] = {"length": 2.0, "loop": "loop", "interp": "catmullrom", "bones": merge(
        pose(COIL, 0.0), pose(scaled(COIL, 1, {"neck": (3, 6, 0), "head": (-2, -8, 0)}), 1.0), pose(COIL, 2.0),
        pose({"jaw": (0, 0, 0)}, 0.0), pose({"jaw": (-8, 0, 0)}, 1.1), pose({"jaw": (0, 0, 0)}, 1.3))}
    # walk: a slithering wave along the flat body (1.2 s, loop)
    T, steps, chain = 1.2, 6, list(reversed(FRONT)) + ["mid"] + BACK
    keys = {}
    for i in range(steps + 1):
        t = T * i / steps
        p = {}
        for j, b in enumerate(FRONT + BACK):
            phase = (j if b in FRONT else -j) * 0.9
            amp = 24 if b not in ("head", "neck") else 12
            p[b] = (0, amp * math.sin(2 * math.pi * t / T + phase), 0)
        keys = merge(keys, pose(p, t))
    anim["walk"] = {"length": T, "loop": "loop", "interp": "catmullrom", "bones": keys}
    # lunge: gather, launch the whole body 3 blocks forward and straight, the jaw wide, the bite, fling back into the coil (1.0 s)
    gather = scaled(COIL, 1.15, {"neck": (12, 0, 0), "seg2": (10, 0, 0)})
    straight = {b: (0, 0, 0) for b in COIL}
    straight["head"] = (-4, 0, 0)
    anim["lunge"] = {"length": 1.0, "loop": "once", "bones": merge(
        pose(COIL, 0.0, {"mid": {"position": (0, 0, 0)}}),
        pose(gather, 0.15, {"mid": {"position": (0, 0, 4)}}),
        pose(straight, 0.32, {"mid": {"position": (0, 2, -48)}}),
        pose(straight, 0.45, {"mid": {"position": (0, 0, -48)}}),
        pose(COIL, 0.8, {"mid": {"position": (0, 0, 0)}}),
        pose(COIL, 1.0, {"mid": {"position": (0, 0, 0)}}),
        pose({"jaw": (0, 0, 0)}, 0.15), pose({"jaw": (-50, 0, 0)}, 0.3), pose({"jaw": (-50, 0, 0)}, 0.38),
        pose({"jaw": (0, 0, 0)}, 0.45), pose({"jaw": (0, 0, 0)}, 1.0))}
    # hide: sinks into the rubble and stays (1.0 s, hold)
    low = scaled(COIL, 0.6)
    anim["hide"] = {"length": 1.0, "loop": "hold", "bones": merge(
        pose(COIL, 0.0, {"mid": {"position": (0, 0, 0)}}), pose(low, 1.0, {"mid": {"position": (0, -7, 0)}}))}
    # spawn: rises out of it into the coil (1.0 s)
    anim["spawn"] = {"length": 1.0, "loop": "once", "bones": merge(
        pose(low, 0.0, {"mid": {"position": (0, -7, 0)}}), pose(COIL, 1.0, {"mid": {"position": (0, 0, 0)}}))}
    # death: writhes, stretches and rolls onto its back (1.5 s, hold)
    dead = {b: (0, (12 if i % 2 else -12), 0) for i, b in enumerate(COIL)}
    anim["death"] = {"length": 1.5, "loop": "hold", "bones": merge(
        pose(COIL, 0.0, {"mid": {"rotation": (0, 0, 0)}}), pose(scaled(COIL, 0.5), 0.5),
        pose(dead, 1.5, {"mid": {"rotation": (0, 0, 160), "position": (0, 6, 0)}}),
        pose({"jaw": (0, 0, 0)}, 0.0), pose({"jaw": (-30, 0, 0)}, 1.5))}
    return anim


VIEW = dict(yaw=150, pitch=24, scale=3.4, center=(0, 12, -22))
SIDE = dict(yaw=90, pitch=5, scale=3.4, center=(0, 14, -10))


def main():
    m = Model("radmie", texture(), model(), animations())
    os.makedirs(os.path.join(ROOT, "models"), exist_ok=True)
    m.save(os.path.join(ROOT, "models", "radmie.bbmodel"))
    m.texture.save(os.path.join(ROOT, "models", "radmie.png"))
    out = os.path.join(ROOT, "build", "models")
    os.makedirs(out, exist_ok=True)
    stills = []
    for an, a in m.animations.items():
        frames = []
        n = max(2, int(a["length"] * 12))
        for i in range(n + 1):
            frames.append(render(m, an, a["length"] * i / n, **VIEW))
        frames[0].save(os.path.join(out, "radmie-%s.gif" % an), save_all=True, append_images=frames[1:], duration=1000 // 12, loop=0)
        pick = {"lunge": [0, 4, 6, 10], "idle": [0], "walk": [3], "hide": [n], "spawn": [n // 2], "death": [n]}[an]
        stills += [(an + " %.2fs" % (a["length"] * i / n), frames[i]) for i in pick]
    stills.insert(0, ("rest (as modelled)", render(m, None, 0, **VIEW)))
    stills.insert(2, ("idle from the side", render(m, "idle", 0, **SIDE)))
    cols = 3
    w, h = stills[0][1].size
    sheet = Image.new("RGB", (cols * w, ((len(stills) + cols - 1) // cols) * (h + 18)), (20, 22, 28))
    d = ImageDraw.Draw(sheet)
    for i, (label, im) in enumerate(stills):
        x, y = (i % cols) * w, (i // cols) * (h + 18)
        sheet.paste(im, (x, y + 18))
        d.text((x + 6, y + 3), label, fill=(220, 220, 220))
    sheet.save(os.path.join(out, "radmie-sheet.png"))
    print("models/radmie.bbmodel,", len(list(m.bones())), "bones,", len(m.animations), "animations; previews in build/models/")


if __name__ == "__main__":
    main()
