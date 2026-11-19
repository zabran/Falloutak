"""The Radmie ("Radioaktivní zmije", the Czech radscorpion; Marek 11-13: *"It is big, but hides in buildings and rubble, attacks by
ambush launching it whole body at distance for venomous bite attack and then fling back."*; the presentation's slide: a red-and-yellow
snake): a Blockbench model for BetterModel, written by script, with its animations and preview pictures.

    python -I tools/models/radmie.py       -> models/radmie.bbmodel, models/radmie.png, build/models/radmie-*.gif + radmie-sheet.png

The chain of bones runs from the middle (`mid`, the base entity's spot) forward to the head and back to the tail. It faces north
(-Z). Animations: idle (lying in curves), walk (slithering), rise (the head up over the rubble, holds), lunge (the attack from
above: up, the strike down onto the target, fast back, the tail on the ground throughout), hide, spawn, death.
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

# Round 2 (Marek 11-19: *"about 2x longer, maybe even more, likely tinner ... attack from above (head goes up to get sight on target
# and then attack from above and wents fast back while tail stays on ground all the time"*): ~18.6 blocks long, 6 x 5 px thick
# (a human is 10 x 29 px), 10 segments forward and 10 back from the middle.
L, NF, NB = 12, 10, 10
FRONT = ["f%d" % i for i in range(1, NF + 1)] + ["neck", "head"]
BACK = ["b%d" % i for i in range(1, NB + 1)] + ["tail1", "tail2"]


def seg(name, z0, z1, w, h, uvset="body"):
    """A body segment from z0 to z1 (z0 < z1), w wide, h tall, lying on the ground."""
    if uvset == "tail":
        uv = {f: list(H) for f in ("north", "south", "east", "west", "up", "down")}
    else:
        uv = {"up": list(A), "east": list(B), "west": list(B), "down": list(C), "north": list(B), "south": list(B)}
    return Cube(name, (-w / 2, 0, z0), (w / 2, h, z1), uv)


def model():
    zh = -(L / 2 + NF * L + 10)                       # the head's back edge
    head = Bone("head", (0, 2, zh), cubes=[
        Cube("skull", (-4.5, 2, zh - 13), (4.5, 6, zh), {"up": list(D), "east": list(E), "west": list(E), "north": list(G),
                                                          "south": list(B), "down": list(F)}),
        Cube("fang_l", (-3, 0.5, zh - 12), (-2, 2, zh - 11), {f: list(I) for f in ("north", "south", "east", "west")}),
        Cube("fang_r", (2, 0.5, zh - 12), (3, 2, zh - 11), {f: list(I) for f in ("north", "south", "east", "west")}),
    ], children=[Bone("jaw", (0, 2, zh - 1), cubes=[
        Cube("jaw", (-3.5, 0, zh - 12), (3.5, 2, zh - 1), {"up": list(F), "east": list(B), "west": list(B), "down": list(C),
                                                          "north": list(G), "south": list(B)})])])
    node = Bone("neck", (0, 0, zh + 10), cubes=[seg("neck", zh, zh + 10, 5, 4)], children=[head])
    for i in range(NF, 0, -1):                        # f10 (next to the neck) ... f1 (next to the middle)
        z1 = -(L / 2 + (i - 1) * L)
        node = Bone("f%d" % i, (0, 0, z1), cubes=[seg("f%d" % i, z1 - L, z1, 6, 5)], children=[node])
    front = node
    zt = L / 2 + NB * L
    node = Bone("tail1", (0, 0, zt), cubes=[seg("tail1", zt, zt + 10, 3.5, 3, "tail")],
                children=[Bone("tail2", (0, 0, zt + 10), cubes=[seg("tail2", zt + 10, zt + 20, 2, 2, "tail")])])
    for i in range(NB, 0, -1):
        z0 = L / 2 + (i - 1) * L
        w = 6 - 2.0 * (i - 1) / NB
        node = Bone("b%d" % i, (0, 0, z0), cubes=[seg("b%d" % i, z0, z0 + L, w, max(3, 5 - (i - 1) * 0.2))], children=[node])
    mid = Bone("mid", (0, 0, 0), cubes=[seg("mid", -L / 2, L / 2, 6, 5)], children=[front, node])
    return [mid]


def kf(x, y, z):
    """A keyframe's rotation from the turn I mean (x raises the front, y turns left seen from above): Blockbench's animator
    applies (-x, -y, +z)."""
    return (-x, -y, z)


def wave(names, amp, phase0=0.0, step=0.9, t=0.0, T=1.0):
    return {b: (0, amp * math.sin(2 * math.pi * t / T + phase0 + j * step), 0) for j, b in enumerate(names)}


# lying: the whole body in loose S-curves on the ground, the neck a little up
LIE = dict(wave(BACK[:-2], 22, 0.3, 0.7))
LIE.update(wave(FRONT[:-2], 20, 1.5, 0.7))
LIE.update({"neck": (10, 0, 0), "head": (-10, 0, 0)})
# raised: the front third rises like a periscope to look over the rubble, the head level; the tail stays down
UP = dict(LIE)
UP.update({"f5": (0, 0, 0), "f6": (25, 0, 0), "f7": (30, 0, 0), "f8": (30, 0, 0), "f9": (5, 0, 0), "f10": (-35, 0, 0),
           "neck": (-35, 0, 0), "head": (-15, 0, 0)})
# the strike: the raised front throws itself forward and DOWN onto the target, the jaw wide
STRIKE = dict(LIE)
STRIKE.update({"f5": (0, 0, 0), "f6": (15, 0, 0), "f7": (0, 0, 0), "f8": (-10, 0, 0), "f9": (-10, 0, 0), "f10": (-5, 0, 0),
               "neck": (-5, 0, 0), "head": (0, 0, 0)})   # the snout lands at ground level 3 blocks past where it looked from
for b in ("f1", "f2", "f3", "f4"):
    STRIKE[b] = (0, LIE[b][1] * 0.3, 0)              # the body behind the strike straightens a little


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
    # idle: lying in curves, the head swaying, the jaw twitching (2 s, loop)
    anim["idle"] = {"length": 2.0, "loop": "loop", "interp": "catmullrom", "bones": merge(
        pose(LIE, 0.0), pose(scaled(LIE, 1, {"neck": (4, 8, 0), "head": (-3, -10, 0)}), 1.0), pose(LIE, 2.0),
        pose({"jaw": (0, 0, 0)}, 0.0), pose({"jaw": (-8, 0, 0)}, 1.1), pose({"jaw": (0, 0, 0)}, 1.3))}
    # walk: a slithering wave along the whole flat body (1.2 s, loop)
    T, steps = 1.2, 6
    keys = {}
    chain = list(reversed(FRONT)) + BACK
    for i in range(steps + 1):
        t = T * i / steps
        p = {}
        for j, b in enumerate(FRONT + BACK):
            phase = (j if b in FRONT else -(j - len(FRONT))) * 0.7
            p[b] = (0, (10 if b in ("head", "neck") else 22) * math.sin(2 * math.pi * t / T + phase), 0)
        keys = merge(keys, pose(p, t))
    anim["walk"] = {"length": T, "loop": "loop", "interp": "catmullrom", "bones": keys}
    # rise: the head goes up to see over the rubble and stays there (0.8 s, hold)
    anim["rise"] = {"length": 0.8, "loop": "hold", "interp": "catmullrom", "bones": merge(pose(LIE, 0.0), pose(UP, 0.8))}
    # lunge = the attack from above: up to look (0.6 s), the strike down onto the target, the jaw wide (fast), back up at once,
    # then down again; the back half never leaves the ground (1.9 s)
    anim["lunge"] = {"length": 1.9, "loop": "once", "bones": merge(
        pose(LIE, 0.0), pose(UP, 0.6), pose(UP, 0.75), pose(STRIKE, 0.9), pose(STRIKE, 1.0), pose(UP, 1.2), pose(UP, 1.45),
        pose(LIE, 1.9),
        pose({"jaw": (0, 0, 0)}, 0.75), pose({"jaw": (-55, 0, 0)}, 0.88), pose({"jaw": (0, 0, 0)}, 0.97),
        pose({"jaw": (0, 0, 0)}, 1.9))}
    # hide: sinks flat into the rubble and stays (1.0 s, hold)
    flat = scaled(LIE, 0.6, {"neck": (-10, 0, 0), "head": (10, 0, 0)})
    anim["hide"] = {"length": 1.0, "loop": "hold", "bones": merge(
        pose(LIE, 0.0, {"mid": {"position": (0, 0, 0)}}), pose(flat, 1.0, {"mid": {"position": (0, -4, 0)}}))}
    # spawn: out of the rubble, the head up to look round, then down (1.6 s)
    anim["spawn"] = {"length": 1.6, "loop": "once", "interp": "catmullrom", "bones": merge(
        pose(flat, 0.0, {"mid": {"position": (0, -4, 0)}}), pose(LIE, 0.6, {"mid": {"position": (0, 0, 0)}}),
        pose(UP, 1.1), pose(LIE, 1.6, {"mid": {"position": (0, 0, 0)}}))}
    # death: writhes and rolls onto its back (1.5 s, hold)
    dead = {b: (0, (10 if i % 2 else -10), 0) for i, b in enumerate(FRONT + BACK)}
    anim["death"] = {"length": 1.5, "loop": "hold", "bones": merge(
        pose(LIE, 0.0, {"mid": {"rotation": (0, 0, 0), "position": (0, 0, 0)}}), pose(scaled(LIE, 1.4), 0.5),
        pose(dead, 1.5, {"mid": {"rotation": (0, 0, 170), "position": (0, 4, 0)}}),
        pose({"jaw": (0, 0, 0)}, 0.0), pose({"jaw": (-30, 0, 0)}, 1.5))}
    return anim


VIEW = dict(yaw=150, pitch=26, scale=1.9, center=(0, 14, -30), size=(560, 400))
SIDE = dict(yaw=90, pitch=4, scale=1.9, center=(0, 30, -40), size=(560, 400))


def head_tip(m, anim, t):
    """Where the snout is (x, y, z in px) at time t: for checking the strike reaches the ground ahead."""
    from bbmodel import posed_quads
    import numpy as np
    pts = []
    for b in m.bones():
        if b.name == "head":
            break
    sk = posed_quads(Model("x", m.texture, m.roots, m.animations), anim, t)
    ys = [p for q, _ in sk for p in q]
    return min(ys, key=lambda p: p[2])


def main():
    m = Model("radmie", texture(), model(), animations())
    os.makedirs(os.path.join(ROOT, "models"), exist_ok=True)
    m.save(os.path.join(ROOT, "models", "radmie.bbmodel"))
    m.texture.save(os.path.join(ROOT, "models", "radmie.png"))
    out = os.path.join(ROOT, "build", "models")
    os.makedirs(out, exist_ok=True)
    stills = [("rest (as modelled)", render(m, None, 0, **VIEW))]
    for an, a in m.animations.items():
        frames = []
        n = max(2, int(a["length"] * 12))
        for i in range(n + 1):
            frames.append(render(m, an, a["length"] * i / n, **(SIDE if an == "lunge" else VIEW)))
        frames[0].save(os.path.join(out, "radmie-%s.gif" % an), save_all=True, append_images=frames[1:], duration=1000 // 12, loop=0)
        if an == "lunge":
            front = [render(m, an, a["length"] * i / n, **VIEW) for i in range(n + 1)]
            front[0].save(os.path.join(out, "radmie-lunge-front.gif"), save_all=True, append_images=front[1:], duration=1000 // 12, loop=0)
        pick = {"lunge": [0, 7, 11, 14], "idle": [0], "walk": [3], "rise": [n], "hide": [n], "spawn": [n // 2], "death": [n]}[an]
        stills += [(an + " %.2fs" % (a["length"] * i / n) + (" (side)" if an == "lunge" else ""), frames[i]) for i in pick]
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
    for t in (0.0, 0.6, 0.9):
        print("lunge t=%.2f snout at" % t, tuple(round(v / 16, 2) for v in head_tip(m, "lunge", t)), "blocks")
    print("models/radmie.bbmodel,", len(list(m.bones())), "bones,", len(m.animations), "animations; previews in build/models/")


if __name__ == "__main__":
    main()
