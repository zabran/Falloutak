"""Blockbench "Generic model" (.bbmodel) files written by script, and a small software renderer for previews.

A model = bones (Blockbench groups: a pivot, a rest rotation, cubes, child bones) + one texture + animations (keyframes per bone:
rotation and position). BetterModel reads the .bbmodel as Blockbench would show it, so the renderer follows Blockbench's own rules:
a group's rest rotation is Euler ZYX in degrees; an ANIMATION's rotation adds (-x, -y, +z) and its position (-x, +y, +z) (the
Bedrock convention Blockbench's animator uses). Units are pixels, 16 to a block, Y up; the creature faces north (-Z).
"""
import base64, io, json, math, uuid as _uuid
import numpy as np
from PIL import Image, ImageDraw

FACES = ("north", "east", "south", "west", "up", "down")


def uid():
    return str(_uuid.uuid4())


class Cube:
    def __init__(self, name, frm, to, uv, inflate=0.0):
        """uv: face -> [u1, v1, u2, v2] in texture pixels (a face left out is not drawn)."""
        self.name, self.frm, self.to, self.uv, self.inflate, self.uuid = name, list(frm), list(to), uv, inflate, uid()


class Bone:
    def __init__(self, name, origin, rotation=(0, 0, 0), cubes=(), children=()):
        self.name, self.origin, self.rotation = name, list(origin), list(rotation)
        self.cubes, self.children, self.uuid = list(cubes), list(children), uid()

    def walk(self):
        yield self
        for c in self.children:
            yield from c.walk()


class Model:
    def __init__(self, name, texture, roots, animations):
        """texture: a PIL RGBA image · animations: name -> {"length", "loop" (loop|once|hold), "bones": {bone: {"rotation"|"position": [(t, (x, y, z))]}}}
        with the values as Blockbench's keyframes hold them."""
        self.name, self.texture, self.roots, self.animations = name, texture, roots, animations

    def bones(self):
        for r in self.roots:
            yield from r.walk()

    # ------------------------------------------------------------------ the .bbmodel
    def bbmodel(self):
        buf = io.BytesIO()
        self.texture.save(buf, "PNG")
        w, h = self.texture.size
        elements, by_name = [], {}

        def group(b):
            by_name[b.name] = b
            kids = []
            for c in b.cubes:
                elements.append({"name": c.name, "box_uv": False, "rescale": False, "locked": False, "light_emission": 0,
                                 "render_order": "default", "allow_mirror_modeling": True, "from": c.frm, "to": c.to,
                                 "autouv": 0, "color": 0, "inflate": c.inflate, "origin": list(b.origin), "rotation": [0, 0, 0],
                                 "faces": {f: ({"uv": c.uv[f], "texture": 0} if f in c.uv else {"uv": [0, 0, 0, 0], "texture": None})
                                           for f in FACES},
                                 "type": "cube", "uuid": c.uuid})
                kids.append(c.uuid)
            kids += [group(k) for k in b.children]
            return {"name": b.name, "origin": b.origin, "color": 0, "uuid": b.uuid, "export": True, "mirror_uv": False,
                    "isOpen": True, "locked": False, "visibility": True, "autouv": 0, "rotation": b.rotation, "children": kids}

        outliner = [group(r) for r in self.roots]
        anims = []
        for an, a in self.animations.items():
            animators = {}
            for bn, chans in a["bones"].items():
                kfs = []
                for ch, keys in chans.items():
                    for t, v in keys:
                        kfs.append({"channel": ch, "data_points": [{"x": round(v[0], 3), "y": round(v[1], 3), "z": round(v[2], 3)}],
                                    "uuid": uid(), "time": round(t, 4), "color": -1, "interpolation": a.get("interp", "linear")})
                animators[by_name[bn].uuid] = {"name": bn, "type": "bone", "keyframes": kfs}
            anims.append({"uuid": uid(), "name": an, "loop": a["loop"], "override": False, "length": a["length"], "snapping": 20,
                          "selected": False, "anim_time_update": "", "blend_weight": "", "start_delay": "", "loop_delay": "",
                          "animators": animators})
        return {"meta": {"format_version": "4.10", "model_format": "free", "box_uv": False}, "name": self.name,
                "model_identifier": "", "visible_box": [1, 1, 0], "variable_placeholders": "", "variable_placeholder_buttons": [],
                "timeline_setups": [], "unhandled_root_fields": {}, "resolution": {"width": w, "height": h},
                "elements": elements, "outliner": outliner,
                "textures": [{"path": "", "name": self.name + ".png", "folder": "", "namespace": "", "id": "0", "width": w,
                              "height": h, "uv_width": w, "uv_height": h, "particle": False, "use_as_default": False,
                              "layers_enabled": False, "sync_to_project": "", "render_mode": "default", "render_sides": "auto",
                              "frame_time": 1, "frame_order_type": "loop", "frame_order": "", "frame_interpolate": False,
                              "visible": True, "internal": True, "saved": False, "uuid": uid(), "relative_path": "",
                              "source": "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()}],
                "animations": anims}

    def save(self, path):
        io.open(path, "w", encoding="utf-8", newline="\n").write(json.dumps(self.bbmodel(), indent=1))

    # ------------------------------------------------------------------ posing
    def sample(self, anim, t):
        """bone -> (rotation (x, y, z), position (x, y, z)) of the keyframes at time t (linear between them)."""
        out = {}
        if anim is None:
            return out
        a = self.animations[anim]
        for bn, chans in a["bones"].items():
            r = p = (0.0, 0.0, 0.0)
            for ch, keys in chans.items():
                keys = sorted(keys)
                if t <= keys[0][0]:
                    v = keys[0][1]
                elif t >= keys[-1][0]:
                    v = keys[-1][1]
                else:
                    for (t0, v0), (t1, v1) in zip(keys, keys[1:]):
                        if t0 <= t <= t1:
                            f = 0 if t1 == t0 else (t - t0) / (t1 - t0)
                            if a.get("interp") == "catmullrom":
                                f = f * f * (3 - 2 * f)
                            v = tuple(x0 + (x1 - x0) * f for x0, x1 in zip(v0, v1))
                            break
                if ch == "rotation":
                    r = v
                else:
                    p = v
            out[bn] = (r, p)
        return out


def rot(deg_xyz):
    x, y, z = (math.radians(d) for d in deg_xyz)
    rx = np.array([[1, 0, 0], [0, math.cos(x), -math.sin(x)], [0, math.sin(x), math.cos(x)]])
    ry = np.array([[math.cos(y), 0, math.sin(y)], [0, 1, 0], [-math.sin(y), 0, math.cos(y)]])
    rz = np.array([[math.cos(z), -math.sin(z), 0], [math.sin(z), math.cos(z), 0], [0, 0, 1]])
    return rz @ ry @ rx   # three.js order 'ZYX'


def corners(c):
    (x1, y1, z1), (x2, y2, z2) = c.frm, c.to
    i = c.inflate
    x1, y1, z1, x2, y2, z2 = x1 - i, y1 - i, z1 - i, x2 + i, y2 + i, z2 + i
    # each face: 4 corners as (top-left, top-right, bottom-right, bottom-left) seen from outside, matching (u1,v1)..(u2,v2)
    return {
        "north": [(x2, y2, z1), (x1, y2, z1), (x1, y1, z1), (x2, y1, z1)],
        "south": [(x1, y2, z2), (x2, y2, z2), (x2, y1, z2), (x1, y1, z2)],
        "east": [(x2, y2, z2), (x2, y2, z1), (x2, y1, z1), (x2, y1, z2)],
        "west": [(x1, y2, z1), (x1, y2, z2), (x1, y1, z2), (x1, y1, z1)],
        "up": [(x1, y2, z1), (x2, y2, z1), (x2, y2, z2), (x1, y2, z2)],
        "down": [(x1, y1, z2), (x2, y1, z2), (x2, y1, z1), (x1, y1, z1)],
    }


def posed_quads(model, anim=None, t=0.0):
    """Every textured face in model space: (4 corners, uv)."""
    pose = model.sample(anim, t)
    quads = []

    def visit(b, parent_m, parent_origin):
        r, p = pose.get(b.name, ((0, 0, 0), (0, 0, 0)))
        R = rot((b.rotation[0] - r[0], b.rotation[1] - r[1], b.rotation[2] + r[2]))
        T = np.array(b.origin, float) - np.array(parent_origin, float) + np.array((-p[0], p[1], p[2]), float)
        M = np.eye(4)
        M[:3, :3] = R
        M[:3, 3] = T
        M = parent_m @ M
        for c in b.cubes:
            for f, pts in corners(c).items():
                if f not in c.uv:
                    continue
                w = []
                for q in pts:
                    v = np.array(q, float) - np.array(b.origin, float)
                    w.append((M @ np.append(v, 1.0))[:3])
                quads.append((w, c.uv[f]))
        for k in b.children:
            visit(k, M, b.origin)

    for r0 in model.roots:
        visit(r0, np.eye(4), (0, 0, 0))
    return quads


def render(model, anim=None, t=0.0, size=(480, 360), yaw=-35, pitch=28, scale=2.2, center=(0, 10, -10), ground=True):
    W, H = size
    tex = np.asarray(model.texture.convert("RGBA"), dtype=np.float32)
    th, tw = tex.shape[:2]
    cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))
    Ryaw = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    Rp = np.array([[1, 0, 0], [0, cp, -sp], [0, sp, cp]])
    V = Rp @ Ryaw
    img = np.zeros((H, W, 3), np.float32)
    img[:] = (34, 38, 46)
    zbuf = np.full((H, W), -1e9, np.float32)
    light = np.array([0.4, 0.8, -0.45])
    light /= np.linalg.norm(light)

    def proj(p):
        q = V @ (np.array(p) - np.array(center))
        return np.array([W / 2 + q[0] * scale, H / 2 - q[1] * scale, q[2]])

    if ground:   # a floor grid, one line a block
        for k in range(-12, 13):
            for a, b in (((k * 16, 0, -192), (k * 16, 0, 192)), ((-192, 0, k * 16), (192, 0, k * 16))):
                p0, p1 = proj(a), proj(b)
                n = int(max(abs(p1[0] - p0[0]), abs(p1[1] - p0[1]))) + 1
                for s in np.linspace(0, 1, n):
                    x, y = int(p0[0] + (p1[0] - p0[0]) * s), int(p0[1] + (p1[1] - p0[1]) * s)
                    if 0 <= x < W and 0 <= y < H:
                        img[y, x] = (52, 58, 70)
    for pts, uv in posed_quads(model, anim, t):
        P = [proj(p) for p in pts]
        n = np.cross(np.array(pts[1]) - np.array(pts[0]), np.array(pts[3]) - np.array(pts[0]))
        nl = np.linalg.norm(n)
        if nl < 1e-9:
            continue
        n /= nl
        shade = 0.55 + 0.45 * max(0.0, float(n @ light))
        u1, v1, u2, v2 = uv
        UV = [(u1, v1), (u2, v1), (u2, v2), (u1, v2)]
        for tri in ((0, 1, 2), (0, 2, 3)):
            a, b, c = (P[i] for i in tri)
            ta, tb, tc = (UV[i] for i in tri)
            xs, ys = [a[0], b[0], c[0]], [a[1], b[1], c[1]]
            x0, x1 = max(0, int(min(xs))), min(W - 1, int(max(xs)) + 1)
            y0, y1 = max(0, int(min(ys))), min(H - 1, int(max(ys)) + 1)
            if x0 > x1 or y0 > y1:
                continue
            den = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
            if abs(den) < 1e-9:
                continue
            gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
            l1 = ((b[1] - c[1]) * (gx - c[0]) + (c[0] - b[0]) * (gy - c[1])) / den
            l2 = ((c[1] - a[1]) * (gx - c[0]) + (a[0] - c[0]) * (gy - c[1])) / den
            l3 = 1 - l1 - l2
            m = (l1 >= -1e-4) & (l2 >= -1e-4) & (l3 >= -1e-4)
            if not m.any():
                continue
            z = l1 * a[2] + l2 * b[2] + l3 * c[2]
            sub = zbuf[y0:y1 + 1, x0:x1 + 1]
            m &= z > sub
            if not m.any():
                continue
            u = l1 * ta[0] + l2 * tb[0] + l3 * tc[0]
            v = l1 * ta[1] + l2 * tb[1] + l3 * tc[1]
            ui = np.clip(u.astype(int), 0, tw - 1)
            vi = np.clip(v.astype(int), 0, th - 1)
            col = tex[vi, ui]
            m &= col[..., 3] > 8
            sub[m] = z[m]
            region = img[y0:y1 + 1, x0:x1 + 1]
            region[m] = col[..., :3][m] * shade
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
