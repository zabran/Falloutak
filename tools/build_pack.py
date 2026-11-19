"""Build Falloutak's PUBLIC resource pack: our own item pictures (pack/icons.json, pack/assets/). No Fallout 2 file goes in here
(those are Interplay's: tools/fallout2_sounds.py makes a private pack from your own copy).

    python tools/build_pack.py [--client <the 1.21.11 client jar>] [--out build/packs/Falloutak-pack.zip] [--bettermodel <its build.zip>]

--bettermodel merges the creature models BetterModel generated from models/*.bbmodel on a server (plugins/BetterModel/build.zip,
its legacy models and player animations off in its config.yml): its 1.21.4+ overlay is copied flat into assets/, so the one pack
carries the item pictures, the Pip-Boy pictures and the creatures.

Each picture is switched on by the item's custom-model-data string ([0] the item, [1] its kind; the plugin's Icons.model): the pack
overrides the vanilla item's definition with a select on those strings and keeps the vanilla model as the fallback, copied from the
client jar, so every other use of that vanilla item looks as before. Prints the zip's SHA-1 for the plugin's config.yml.
"""
import argparse, hashlib, io, json, os, sys, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
PACK_FORMAT = 75   # Minecraft 1.21.11
HANDHELD = {"pistol_10mm", "knife", "flare", "weapon_repair_kit"}
CLIENT = os.path.join(os.environ.get("APPDATA", ""), ".minecraft", "versions", "1.21.11", "1.21.11.jar")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--client", default=CLIENT)
    ap.add_argument("--out", default=os.path.join(ROOT, "build", "packs", "Falloutak-pack.zip"))
    ap.add_argument("--bettermodel", default="")
    a = ap.parse_args()
    d = json.load(io.open(os.path.join(ROOT, "pack", "icons.json"), encoding="utf-8"))
    tex = os.path.join(ROOT, "pack", "assets", "falloutak", "textures", "item")
    by_item = {}
    for ic in d["icons"]:
        if not os.path.exists(os.path.join(tex, ic["slug"] + ".png")):
            print("no picture, left out:", ic["slug"])
            continue
        for it in ic["items"]:
            by_item.setdefault(it, []).append(ic["slug"])
    jar = zipfile.ZipFile(a.client)
    files = {}
    for it, slugs in sorted(by_item.items()):
        vanilla = json.loads(jar.read("assets/minecraft/items/%s.json" % it))
        own = lambda s: {"type": "minecraft:model", "model": "falloutak:item/" + s}
        kinds = [s for s in slugs if s.startswith("kind/")]
        items = [s for s in slugs if not s.startswith("kind/")]
        fallback = vanilla["model"]
        if kinds:
            fallback = {"type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 1,
                        "cases": [{"when": "falloutak:" + s, "model": own(s)} for s in kinds], "fallback": fallback}
        model = fallback if not items else {"type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0,
                                            "cases": [{"when": "falloutak:" + s, "model": own(s)} for s in items], "fallback": fallback}
        definition = dict(vanilla)
        definition["model"] = model
        files["assets/minecraft/items/%s.json" % it] = json.dumps(definition, indent=1).encode()
    for ic in d["icons"]:
        s = ic["slug"]
        png = os.path.join(tex, s + ".png")
        if not os.path.exists(png):
            continue
        files["assets/falloutak/textures/item/%s.png" % s] = open(png, "rb").read()
        files["assets/falloutak/models/item/%s.json" % s] = json.dumps(
            {"parent": "minecraft:item/" + ("handheld" if s in HANDHELD else "generated"), "textures": {"layer0": "falloutak:item/" + s}},
            indent=1).encode()
    for sub in ("font", os.path.join("textures", "font"), "sounds"):   # the Pip-Boy pictures (tools/cards.py), the free sounds (tools/free_sounds.py)
        base = os.path.join(ROOT, "pack", "assets", "falloutak", sub)
        for dp, _, fs in os.walk(base):
            for fn in fs:
                full = os.path.join(dp, fn)
                files["assets/falloutak/" + os.path.relpath(full, os.path.join(ROOT, "pack", "assets", "falloutak")).replace(os.sep, "/")] = open(full, "rb").read()
    for extra, inzip in ((os.path.join(ROOT, "pack", "assets", "falloutak", "sounds.json"), "assets/falloutak/sounds.json"),
                         (os.path.join(ROOT, "pack", "FREE-SOUNDS-CREDITS.md"), "FREE-SOUNDS-CREDITS.md")):
        if os.path.exists(extra):
            files[inzip] = open(extra, "rb").read()
    if a.bettermodel:
        bm = zipfile.ZipFile(a.bettermodel)
        for n in bm.namelist():
            if n.endswith("/") or n in ("pack.mcmeta", "pack.png") or n.startswith("bettermodel_legacy/"):
                continue
            files[n.split("/", 1)[1] if n.startswith("bettermodel_modern/") else n] = bm.read(n)
    meta = {"pack": {"description": "Fallouťák: the game's own item pictures, Pip-Boy pictures and creatures", "pack_format": PACK_FORMAT, "min_format": PACK_FORMAT,
                     "max_format": PACK_FORMAT}}
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    with zipfile.ZipFile(a.out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("pack.mcmeta", json.dumps(meta, indent=1))
        for p, b in sorted(files.items()):
            z.writestr(p, b)
    sha1 = hashlib.sha1(open(a.out, "rb").read()).hexdigest()
    print("%s: %d pictures over %d vanilla items, %d KB, sha1 %s" % (a.out, sum(len(v) for v in by_item.values()), len(by_item),
                                                                      os.path.getsize(a.out) // 1024, sha1))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
