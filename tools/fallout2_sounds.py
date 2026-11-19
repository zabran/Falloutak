"""Build the PRIVATE Fallout 2 sound pack for Falloutak from YOUR OWN copy of Fallout 2.

Fallout 2's sounds belong to Interplay: this tool ships none of them. It reads `sound\\SFX\\*.ACM` out of the master.dat of a
Fallout 2 you own, turns each file the catalogue names (src/main/resources/data/sounds.json, the "f2" lists) into OGG with ffmpeg
(its Interplay ACM decoder), and writes a resource pack whose sounds live under the namespace `falloutak_f2`. A key with no
Fallout 2 file plays Minecraft's own sound. Keep the pack to yourself (your resourcepacks folder, or a link only your group knows):
never on a public page. In the game: `/sounds fallout2`.

    python tools/fallout2_sounds.py "C:/Games/Fallout2/master.dat" [--out build/packs/Falloutak-Fallout2-sounds.zip]

Needs ffmpeg on the PATH (with libvorbis). The DAT2 format: the directory at the end of the file, each entry a name, a compressed
flag, the real and packed sizes and the offset; a compressed entry is zlib (fallout2-ce's dfile.cc).
"""
import argparse, hashlib, io, json, os, struct, subprocess, sys, tempfile, zipfile, zlib

HERE = os.path.dirname(os.path.abspath(__file__))
CATALOGUE = os.path.join(HERE, "..", "src", "main", "resources", "data", "sounds.json")
NS = "falloutak_f2"
PACK_FORMAT = 75   # Minecraft 1.21.11 (version.json: resource_major 75)


def dat_index(path):
    f = open(path, "rb")
    f.seek(-8, 2)
    tree_size, data_size = struct.unpack("<II", f.read(8))
    f.seek(data_size - tree_size - 8)
    count = struct.unpack("<I", f.read(4))[0]
    entries = {}
    for _ in range(count):
        ln = struct.unpack("<I", f.read(4))[0]
        name = f.read(ln).decode("latin-1")
        comp, real, packed, off = struct.unpack("<BIII", f.read(13))
        entries[name.lower().replace("/", "\\")] = (comp, real, packed, off)
    return f, entries


def dat_read(f, e):
    comp, real, packed, off = e
    f.seek(off)
    b = f.read(packed)
    return zlib.decompress(b) if comp else b


def to_ogg(acm_bytes, work):
    src = os.path.join(work, "in.acm")
    dst = os.path.join(work, "out.ogg")
    with open(src, "wb") as w:
        w.write(acm_bytes)
    # mono (Minecraft places only mono sounds in the world), 44.1 kHz, Vorbis quality 5; ffmpeg's ACM decoder stumbles at the end of
    # some files (tested 11-19: the whole length comes out once the error rate is tolerated, icombat1 0.56 of 0.64 s)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-max_error_rate", "1", "-i", src, "-ac", "1", "-ar", "44100", "-c:a", "libvorbis",
                    "-q:a", "5", dst], check=True, capture_output=True)
    samples, channels, rate = struct.unpack("<IHH", acm_bytes[4:12])
    want = samples / max(1, channels) / max(1, rate)
    got = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", dst],
                               capture_output=True, text=True).stdout.strip() or 0)
    with open(dst, "rb") as r:
        return r.read(), want, got


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("master_dat")
    ap.add_argument("--out", default=os.path.join(HERE, "..", "build", "packs", "Falloutak-Fallout2-sounds.zip"))
    a = ap.parse_args()
    rows = json.load(io.open(CATALOGUE, encoding="utf-8"))["rows"]
    f, entries = dat_index(a.master_dat)
    sounds, files, missing, short = {}, {}, [], []
    with tempfile.TemporaryDirectory() as work:
        for r in rows:
            key, got = r["key"], []
            for i, name in enumerate(r["f2"], 1):
                e = entries.get("sound\\sfx\\" + name.lower() + ".acm")
                if e is None:
                    missing.append(key + ": " + name)
                    continue
                ogg, want, length = to_ogg(dat_read(f, e), work)
                if length < 0.85 * want:
                    short.append("%s: %s %.2f of %.2f s" % (key, name, length, want))
                files["assets/%s/sounds/%s/%d.ogg" % (NS, key.replace(".", "_"), i)] = ogg
                got.append({"name": "%s:%s/%d" % (NS, key.replace(".", "_"), i)})
            if not got:   # no Fallout 2 file: Minecraft's own, as the plugin plays it without the pack
                got = [{"name": "minecraft:" + r["mc"], "type": "event", "volume": r["volume"], "pitch": r["pitch"]}]
            sounds[key] = {"sounds": got}
    meta = {"pack": {"description": "Falloutak: Fallout 2's sounds, made from your own copy of the game (private)",
                     "pack_format": PACK_FORMAT, "min_format": PACK_FORMAT, "max_format": PACK_FORMAT}}
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    with zipfile.ZipFile(a.out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("pack.mcmeta", json.dumps(meta, indent=1))
        z.writestr("assets/%s/sounds.json" % NS, json.dumps(sounds, indent=1))
        for p, b in sorted(files.items()):
            z.writestr(p, b)
    sha1 = hashlib.sha1(open(a.out, "rb").read()).hexdigest()
    print("%s: %d keys, %d sound files, %d KB, sha1 %s" % (a.out, len(rows), len(files), os.path.getsize(a.out) // 1024, sha1))
    for m in missing:
        print("not in this master.dat:", m)
    for m in short:
        print("decoded short:", m)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
