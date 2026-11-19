"""The Trhač (Marek 11-13: *"it is also variation on Obilňák from Valarák. It is a gigantic boar that has rapeseed growing on it,
helping it gain nutritions and also releasing its pollen in large quantities to confuse enemies and obfuscate its location. With its
size, it is seriously hard to kill, it can move fast in straight direction and even just the mass alone makes it deadly."*; the
presentation's slide: a boar in a rapeseed field by a yellow car): a Blockbench model for BetterModel, written by script, with its
animations and preview pictures.

    python -I tools/models/trhac.py       -> models/trhac.bbmodel, models/trhac.png, build/models/trhac-*.gif + trhac-sheet.png

Size [mine, to Marek's eye]: 3.75 blocks at the shoulder, ~7.4 long with the snout, the rapeseed up to ~5.5 blocks. It faces north
(-Z). Bones: `body` (the base entity's spot) with the head, four legs of two parts, the tail and `crop` (14 rapeseed stalks, each its
own bone so they sway). Animations: idle, walk, charge (the straight-line run, head low), gore (the tusks tossed up), pollen (shakes
itself like a wet dog, the stalks whip: the plugin releases the cloud), spawn (stands up out of the field), death (falls on its side).
"""
import math, os, random, sys
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bbmodel import Bone, Cube, Model, render

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# ------------------------------------------------------------------ the texture: 64 x 64, regions of 16 x 16
# Round 2 (Marek 11-19: "Trhač's hide is supposed to be yellowish brown from bottom and getting yellow at the top"): a gradient on
# every side face, from LOW (yellowish brown) up to TOP (yellow); the belly and the legs the low colour
LOW, MIDH, TOP = (128, 92, 40), (176, 136, 52), (222, 188, 62)
HIDE, DARKH, BELLY, SNOUT, IVORY, HOOF = MIDH, (104, 74, 34), (112, 80, 36), (170, 120, 112), (232, 222, 196), (30, 26, 24)
STEM, LEAF, FLOWER, EYE = (96, 150, 52), (58, 112, 40), (246, 214, 40), (230, 120, 30)


def grad(y):
    """The hide's colour at row y of a side face (0 = the top of the face, 15 = its bottom)."""
    k = y / 15.0
    a, b, t = (TOP, MIDH, k * 2) if k < 0.5 else (MIDH, LOW, k * 2 - 1)
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def texture():
    rnd = random.Random(11)
    im = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    px = im.load()

    def fill(x0, y0, f):
        for y in range(16):
            for x in range(16):
                px[x0 + x, y0 + y] = f(x, y) + (255,)

    def hair(c, x, y, k=1.0):   # bristles: short dark strokes running down, a little noise
        streak = 0.72 if (x * 5 + (y // 3) * 3) % 7 == 0 else 1.0
        n = rnd.uniform(0.86, 1.1)
        return tuple(max(0, min(255, int(v * streak * n * k))) for v in c)

    def back(x, y):        # A: the back, yellow, pollen dusted on it
        if rnd.random() < 0.08:
            return FLOWER
        return hair(TOP, x, y, 0.95)

    def side(x, y):        # B: the flank, yellow at the top, yellowish brown at the bottom
        return hair(grad(y), x, y)

    def belly(x, y):       # C: the belly, the low colour, darker
        return hair(BELLY, x, y)

    def head_top(x, y):    # D: the head's top: yellow with a darker crest
        return hair(MIDH if abs(x - 8) < 2 else TOP, x, y)

    def head_side(x, y):   # E: the eye, small and orange, set high, on the gradient
        if (x - 4) ** 2 + (y - 5) ** 2 <= 2:
            return (20, 14, 10) if (x, y) == (4, 5) else EYE
        return hair(grad(y), x, y)

    def snout(x, y):       # F: the snout's disc, two nostrils
        if y in (6, 7, 8) and x in (4, 5, 10, 11):
            return (40, 22, 22)
        return tuple(int(v * rnd.uniform(0.92, 1.04)) for v in SNOUT)

    def tusk(x, y):        # G: ivory, yellowed at the root
        k = 0.82 + 0.18 * (y / 15)
        return tuple(int(v * k) for v in IVORY)

    def hoof(x, y):        # H
        return tuple(int(v * rnd.uniform(0.85, 1.15)) for v in HOOF)

    def stem(x, y):        # I: a green stalk
        return tuple(int(v * (0.9 if x % 4 == 0 else 1.0) * rnd.uniform(0.92, 1.06)) for v in STEM)

    def flower(x, y):      # J: a raceme of small yellow four-petalled flowers
        if (x % 4, y % 4) in ((0, 0), (2, 2)):
            return (200, 160, 20)
        return tuple(int(v * rnd.uniform(0.9, 1.04)) for v in FLOWER)

    def leaf(x, y):        # K: a bluish-green leaf with a midrib
        return (120, 170, 80) if x == 8 else tuple(int(v * rnd.uniform(0.9, 1.08)) for v in LEAF)

    def mane(x, y):        # L: the stiff ridge of bristles on the spine, yellow like the back
        return hair(TOP, x, y, 0.85 if y % 3 else 0.7)

    def leg(x, y):         # M: the legs, the low colour
        return hair(LOW, x, y, 0.9 + 0.1 * (1 - y / 15))

    for i, f in enumerate((back, side, belly, head_top)):
        fill(16 * i, 0, f)
    for i, f in enumerate((head_side, snout, tusk, hoof)):
        fill(16 * i, 16, f)
    for i, f in enumerate((stem, flower, leaf, mane)):
        fill(16 * i, 32, f)
    fill(0, 48, leg)
    return im


A, B, C, D = (0, 0, 16, 16), (16, 0, 32, 16), (32, 0, 48, 16), (48, 0, 64, 16)
E, F, G, H = (0, 16, 16, 32), (16, 16, 32, 32), (32, 16, 48, 32), (48, 16, 64, 32)
I, J, K, Lm = (0, 32, 16, 48), (16, 32, 32, 48), (32, 32, 48, 48), (48, 32, 64, 48)
M = (0, 48, 16, 64)


def uv(up=None, side=None, down=None, front=None, back=None):
    s = list(side or B)
    return {"up": list(up or A), "east": s, "west": s, "down": list(down or C), "north": list(front or s), "south": list(back or s)}


def all_faces(r):
    return {f: list(r) for f in ("north", "south", "east", "west", "up", "down")}


LEGS = {"fl": (-14, -24), "fr": (14, -24), "bl": (-14, 24), "br": (14, 24)}   # x, z of each leg (round 2: wider)
STALKS = []


def back_top(z):
    """The height of the back at z (px): the shoulder hump, the back, the lower rump."""
    return 60 if z < -8 else 52 if z < 12 else 48


def crop_bones():
    """Round 2 (Marek 11-19: "The flowers should be short - showing just the yellow bloom."): low clumps of bloom on short stems,
    close together over the whole back and the top of the flanks, so the back reads as one yellow mound."""
    rnd = random.Random(5)
    bones = []
    spots = [(x, z) for z in range(-34, 37, 8) for x in (-14, -5, 5, 14)]
    for i, (x, z) in enumerate(spots, 1):
        x += rnd.uniform(-2, 2)
        z += rnd.uniform(-2, 2)
        side = abs(x) > 10                      # the outer rows sit on the flank's top edge, a little lower
        y = back_top(z) - (3 if side else 0)
        h = rnd.randint(1, 3)
        w = rnd.uniform(3.5, 5.5)
        tall = rnd.uniform(3, 6)
        cubes = [Cube("stem%d" % i, (x - 0.75, y, z - 0.75), (x + 0.75, y + h, z + 0.75), all_faces(I)),
                 Cube("bloom%d" % i, (x - w, y + h, z - w), (x + w, y + h + tall, z + w), all_faces(J))]
        lean = (rnd.uniform(-6, 6), 0, (14 if x > 0 else -14) if side else rnd.uniform(-6, 6))   # the side rows lean outward
        bones.append(Bone("s%d" % i, (x, y, z), rotation=lean, cubes=cubes))
        STALKS.append("s%d" % i)
    return bones


def model():
    STALKS.clear()
    head = Bone("head", (0, 44, -38), cubes=[
        Cube("skull", (-14, 28, -60), (14, 51, -38), uv(up=D, side=E, front=E, back=B)),
        Cube("neck", (-13, 30, -42), (13, 50, -28), uv(up=Lm)),        # inside the chest at rest: no gap when the head tosses
        Cube("snout", (-9, 25, -78), (9, 40, -60), uv(up=D, side=B, front=F, down=C)),
        Cube("disc", (-8, 26, -80), (8, 38, -78), all_faces(F)),
        Cube("tusk_l", (-13, 30, -76), (-10, 46, -73), all_faces(G)),
        Cube("tusk_r", (10, 30, -76), (13, 46, -73), all_faces(G)),
        Cube("ear_l", (-15, 51, -46), (-9, 58, -42), uv(up=Lm, side=Lm)),
        Cube("ear_r", (9, 51, -46), (15, 58, -42), uv(up=Lm, side=Lm)),
    ])
    legs = []
    for name, (x, z) in LEGS.items():
        lower = Bone(name + "_low", (x, 14, z), cubes=[
            Cube(name + "_shin", (x - 4, 3, z - 4), (x + 4, 14, z + 4), uv(up=M, side=M, down=H)),
            Cube(name + "_hoof", (x - 4, 0, z - 4.5), (x + 4, 3, z + 3.5), all_faces(H))])
        legs.append(Bone(name, (x, 30, z), cubes=[Cube(name + "_thigh", (x - 6, 13, z - 6), (x + 6, 32, z + 6), uv(up=M, side=M))],
                         children=[lower]))
    tail = Bone("tail", (0, 44, 38), cubes=[Cube("tail", (-1.5, 30, 38), (1.5, 44, 41), uv(side=Lm, up=Lm)),
                                            Cube("tuft", (-2.5, 26, 38), (2.5, 31, 42), all_faces(Lm))])
    crop = Bone("crop", (0, 52, 0), children=crop_bones())
    body = Bone("body", (0, 30, 0), cubes=[
        Cube("chest", (-21, 26, -38), (21, 54, 12), uv()),             # round 2: wider (2.6 blocks)
        Cube("hump", (-19, 50, -36), (19, 60, -8), uv()),
        Cube("rump", (-20, 24, 12), (20, 48, 38), uv(back=B)),
        Cube("mane", (-3, 58, -34), (3, 64, -10), all_faces(Lm)),
    ], children=[head, tail, crop] + legs)
    return [body]


def kf(x, y, z):
    """A keyframe's rotation from the turn I mean (x raises the front, y turns left seen from above, z rolls): Blockbench's
    animator applies (-x, -y, +z)."""
    return (-x, -y, z)


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


def sway(t, T, amp, seed=0.0):
    """Every stalk swaying, each a little out of step."""
    return {s: (amp * math.sin(2 * math.pi * t / T + seed + i * 0.9), 0, amp * 0.7 * math.cos(2 * math.pi * t / T + i * 1.3))
            for i, s in enumerate(STALKS)}


def legs(swing, knee, t, T, gallop=False):
    """A leg swings forward (+) and back (-); the knee bends while the leg comes forward. Walk: the diagonal pairs together;
    gallop: the front pair together and the back pair together, half a cycle apart."""
    p = {}
    for name in LEGS:
        if gallop:
            ph = 0.0 if name[0] == "f" else math.pi
        else:
            ph = 0.0 if name in ("fl", "br") else math.pi
        a = math.sin(2 * math.pi * t / T + ph)
        p[name] = (-swing * a, 0, 0)
        # round 2 (Marek 11-19: "the walk animation seems going backward?"): the knee bent while the foot went BACK, a moonwalk;
        # now the foot lifts while it swings forward and is planted while it pushes back (MEASURED on the posed hoof: the height
        # against the forward speed, correlation +0.52 in the charge; the old form -0.38)
        p[name + "_low"] = (knee * max(0.0, -math.cos(2 * math.pi * t / T + ph)), 0, 0)
    return p


# lying as a rock: the legs folded under the body, the head down on the ground and turned a little aside, the tail tucked
# (measured on the posed hoof: a thigh at -90 lays a front leg flat BACKWARD, +90 a hind leg flat FORWARD, both inside the body;
# the snout sinks into the ground, which hides it in the game)
ROCK = {"fl": (-90, 0, 0), "fr": (-90, 0, 0), "fl_low": (0, 0, 0), "fr_low": (0, 0, 0),
        "bl": (90, 0, 0), "br": (90, 0, 0), "bl_low": (0, 0, 0), "br_low": (0, 0, 0),
        "head": (-48, 8, 0), "tail": (12, 0, 0)}


def animations():
    anim = {}
    # idle = a big yellow rock (Marek 11-19: "in idle animation, he should appear like big yellow rock (especially from back or
    # side)"): it lies in the field, the legs folded under it, the head down on the ground, the tail tucked; only the blooms stir
    # and the back rises with a slow breath (4 s, loop)
    keys = {}
    for i in range(9):
        t = 4.0 * i / 8
        p = dict(ROCK)
        p.update(sway(t, 4.0, 2.5))
        keys = merge(keys, pose(p, t, {"body": {"position": (0, -28 + 0.5 * math.sin(2 * math.pi * t / 4.0), 0)}}))
    anim["idle"] = {"length": 4.0, "loop": "loop", "interp": "catmullrom", "bones": keys}
    # walk: a heavy trot, diagonal pairs, the body rocking (1.0 s, loop)
    keys = {}
    for i in range(9):
        t = 1.0 * i / 8
        p = dict(sway(t, 1.0, 7))
        p.update(legs(22, 65, t, 1.0))
        p["head"] = (3 * math.sin(4 * math.pi * t), 0, 0)
        p["tail"] = (0, 15 * math.sin(2 * math.pi * t), 0)
        keys = merge(keys, pose(p, t, {"body": {"position": (0, abs(math.sin(2 * math.pi * t)) * 1.2, 0)}}))
    anim["walk"] = {"length": 1.0, "loop": "loop", "interp": "catmullrom", "bones": keys}
    # charge: the straight-line run, a gallop with the head low and the tusks forward, the rapeseed blown back (0.5 s, loop)
    keys = {}
    for i in range(9):
        t = 0.5 * i / 8
        p = {s: (24 + 6 * math.sin(2 * math.pi * t / 0.5 + j), 0, 0) for j, s in enumerate(STALKS)}   # + = the top leans back
        p.update(legs(42, 55, t, 0.5, gallop=True))
        p["head"] = (-18 + 4 * math.sin(2 * math.pi * t / 0.5), 0, 0)
        p["body"] = (5 * math.sin(2 * math.pi * t / 0.5), 0, 0)
        p["tail"] = (40, 0, 0)
        keys = merge(keys, pose(p, t, {"body": {"position": (0, 3 * abs(math.sin(2 * math.pi * t / 0.5)), 0)}}))
    anim["charge"] = {"length": 0.5, "loop": "loop", "interp": "catmullrom", "bones": keys}
    # gore: the head down, then the tusks tossed up hard, the front legs planted (0.9 s)
    anim["gore"] = {"length": 0.9, "loop": "once", "interp": "catmullrom", "bones": merge(
        pose({"head": (0, 0, 0), "body": (0, 0, 0)}, 0.0), pose({"head": (-28, 0, 0), "body": (-4, 0, 0)}, 0.3),
        pose({"head": (38, 0, 8), "body": (6, 0, 0)}, 0.48), pose({"head": (30, 0, 6), "body": (5, 0, 0)}, 0.6),
        pose({"head": (0, 0, 0), "body": (0, 0, 0)}, 0.9),
        pose({"fl": (0, 0, 0), "fr": (0, 0, 0)}, 0.0), pose({"fl": (18, 0, 0), "fr": (18, 0, 0)}, 0.3),
        pose({"fl": (-6, 0, 0), "fr": (-6, 0, 0)}, 0.5), pose({"fl": (0, 0, 0), "fr": (0, 0, 0)}, 0.9))}
    # pollen: it shakes itself like a wet dog, the stalks whipping, the head shaking (1.6 s): the plugin releases the cloud
    keys = {}
    for i in range(17):
        t = 1.6 * i / 16
        k = math.sin(math.pi * t / 1.6)                     # swells and dies away
        p = {s: (25 * k * math.sin(2 * math.pi * t / 0.2 + j), 0, 25 * k * math.cos(2 * math.pi * t / 0.2 + j * 0.7))
             for j, s in enumerate(STALKS)}
        p["body"] = (0, 0, 12 * k * math.sin(2 * math.pi * t / 0.2))
        p["head"] = (0, 18 * k * math.sin(2 * math.pi * t / 0.2 + 1), 0)
        p["tail"] = (0, 30 * k * math.sin(2 * math.pi * t / 0.2), 0)
        keys = merge(keys, pose(p, t))
    anim["pollen"] = {"length": 1.6, "loop": "once", "interp": "catmullrom", "bones": keys}
    # spawn: the rock gets up onto its feet (1.6 s)
    down = dict(ROCK)
    up = {b: (0, 0, 0) for b in down}
    anim["spawn"] = {"length": 1.6, "loop": "once", "interp": "catmullrom", "bones": merge(
        pose(down, 0.0, {"body": {"position": (0, -28, 0)}}), pose(down, 0.4, {"body": {"position": (0, -28, 0)}}),
        pose({**up, "fl": (-10, 0, 0), "fr": (-10, 0, 0)}, 0.9, {"body": {"position": (0, -10, 0)}}),
        pose(up, 1.6, {"body": {"position": (0, 0, 0)}}))}
    # death: the legs give, it falls onto its side, the legs stiff (1.5 s, hold)
    anim["death"] = {"length": 1.5, "loop": "hold", "interp": "catmullrom", "bones": merge(
        pose({"body": (0, 0, 0)}, 0.0, {"body": {"position": (0, 0, 0)}}),
        pose({"body": (-6, 0, 8), "fl": (20, 0, 0), "fr": (20, 0, 0)}, 0.5, {"body": {"position": (0, -8, 0)}}),
        pose({"body": (0, 0, 88), "fl": (-25, 0, 0), "fr": (25, 0, 0), "bl": (-30, 0, 0), "br": (20, 0, 0), "head": (-10, 0, 0)}, 1.5,
             {"body": {"position": (0, -14, 0)}}))}
    return anim


VIEW = dict(yaw=150, pitch=22, scale=2.4, center=(0, 40, -10), size=(560, 400))
SIDE = dict(yaw=90, pitch=4, scale=2.4, center=(0, 40, -10), size=(560, 400))
BACKV = dict(yaw=-30, pitch=14, scale=2.4, center=(0, 30, 0), size=(560, 400))


def main():
    m = Model("trhac", texture(), model(), animations())
    os.makedirs(os.path.join(ROOT, "models"), exist_ok=True)
    m.save(os.path.join(ROOT, "models", "trhac.bbmodel"))
    m.texture.save(os.path.join(ROOT, "models", "trhac.png"))
    out = os.path.join(ROOT, "build", "models")
    os.makedirs(out, exist_ok=True)
    stills = [("rest (as modelled)", render(m, None, 0, **VIEW)), ("rest from the side", render(m, None, 0, **SIDE)),
              ("idle = the rock, from the back", render(m, "idle", 0, **BACKV)),
              ("idle = the rock, from the side", render(m, "idle", 0, **SIDE))]
    for an, a in m.animations.items():
        n = max(2, int(a["length"] * 12))
        view = SIDE if an in ("walk", "charge", "gore") else VIEW
        frames = [render(m, an, a["length"] * i / n, **view) for i in range(n + 1)]
        frames[0].save(os.path.join(out, "trhac-%s.gif" % an), save_all=True, append_images=frames[1:], duration=1000 // 12, loop=0)
        pick = {"idle": [0], "walk": [n // 4], "charge": [n // 4, 3 * n // 4], "gore": [n // 3, n // 2], "pollen": [n // 2],
                "spawn": [0, n], "death": [n]}[an]
        stills += [(an + " %.2fs" % (a["length"] * i / n) + (" (side)" if view is SIDE else ""), frames[i]) for i in pick]
    cols = 3
    w, h = stills[0][1].size
    sheet = Image.new("RGB", (cols * w, ((len(stills) + cols - 1) // cols) * (h + 18)), (20, 22, 28))
    d = ImageDraw.Draw(sheet)
    for i, (label, im) in enumerate(stills):
        x, y = (i % cols) * w, (i // cols) * (h + 18)
        sheet.paste(im, (x, y + 18))
        d.text((x + 6, y + 3), label, fill=(220, 220, 220))
    sheet.save(os.path.join(out, "trhac-sheet.png"))
    a = m.animations["walk"]   # the walk as a strip of 8 frames from the side: a foot must lift while it moves forward (left)
    strip = [render(m, "walk", a["length"] * i / 8, **dict(SIDE, size=(420, 300))) for i in range(8)]
    row = Image.new("RGB", (4 * 420, 2 * 300))
    for i, im in enumerate(strip):
        row.paste(im, ((i % 4) * 420, (i // 4) * 300))
    row.save(os.path.join(out, "trhac-walk-strip.png"))
    print("models/trhac.bbmodel,", len(list(m.bones())), "bones,", len(m.animations), "animations; previews in build/models/")


if __name__ == "__main__":
    main()
