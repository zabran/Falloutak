"""Falloutak's FREE sounds for the public pack (Marek 11-19: *"I put together a public pack of free sounds that sound like Fallout's."
- please try that.*): CC0 recordings (no rights reserved, no credit needed; credited anyway in pack/FREE-SOUNDS-CREDITS.md) and
sounds made here from nothing (the Geiger counter). Nothing is made from Fallout 2's files: a sound derived from a copyrighted
recording would still be that recording (CJEU, Pelham, C-476/17), so the only way to a free pack is other recordings.

    python -I tools/free_sounds.py <the CC0 folder>      (its subfolders: kenney_*/ and oga_sounds/ + oga_*.wav, as downloaded)

Writes pack/assets/falloutak/sounds/free/<key>.ogg and pack/assets/falloutak/sounds.json (events `falloutak:<key>`), which
tools/build_pack.py puts in the public pack, and src/main/resources/data/free-sounds.json (the keys it covers) for the plugin:
a player with the public pack hears these; keys not covered (the voices) stay Minecraft's. Needs ffmpeg.
"""
import io, json, os, random, struct, subprocess, sys, wave

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT = os.path.join(ROOT, "pack", "assets", "falloutak", "sounds", "free")

K = "kenney_"
# key -> (source file(s) under the CC0 folder, pitch factor, gain dB, seconds kept from the start or None, the pack it came from)
# Marek's verdicts of 11-19 (LollyWood-Lore _notes/Fallout/sound-verdicts-2026-11-19.txt): a key he gave to Minecraft ("use MC") is
# not here, so the pack plays Minecraft's: turn.end, ap.lacking, steady, grenade.throw, explosion, gas.release (ours sounded like
# fire: kept in mind for a flamer), flare, burning, stimpak, chem, firstaid, repair, gas.hiss, electric, heart.stop, defib,
# revived, pipboy, radmie; crit is gone ("Just make crits have normal sound + the narrator speach").
# A value is one candidate, or a LIST of candidates: the first goes into the pack, all of them into build/free-cands/ for the
# listening page. A candidate = (file, pitch, gain dB, seconds kept: N or (start, end) or None), or ("seq", [(file, at seconds,
# gain dB, kept, pitch), ...]) = several recordings laid one after another (steps, clicks).
FALL = [("oga_impact/qubodupImpactMeat02.ogg", 0.9, 2, None),        # a body hits the ground; the voice comes from the plugin
        ("oga_fleshy/hammer-1a.wav", 0.8, 0, None),
        ("oga_impacts/Impact Rash 2.wav", 1.0, 0, 0.8)]
MAP = {
    "combat.start": ("kenney_digital-audio/Audio/lowThreeTone.ogg", 0.9, 0, None),
    "combat.end": ("kenney_interface-sounds/Audio/confirmation_002.ogg", 1.0, 0, None),
    "turn.start": [("kenney_interface-sounds/Audio/select_003.ogg", 1.0, 0, None),     # was twoTone1: "Sounds more like a failure"
                   ("kenney_interface-sounds/Audio/toggle_003.ogg", 1.0, 0, None),
                   ("kenney_interface-sounds/Audio/select_004.ogg", 1.0, 0, None)],
    "mode.next": ("kenney_rpg-audio/Audio/metalClick.ogg", 1.2, 0, None),
    "stance": ("kenney_rpg-audio/Audio/cloth1.ogg", 1.0, 0, None),
    "hands": [("oga_shotguncock_0.wav", 1.0, 0, None),                                 # "original has reload sounds"
              ("oga_gunreload1.wav", 1.0, 0, (0.0, 0.55)),
              ("oga_assaultriflereload1_0.wav", 1.0, 0, (0.12, 0.65))],
    "pistol.single": ("oga_sounds/sounds/cz.wav", 1.0, 0, (0.2, 1.1)),   # the shot starts 0.2 s in
    "pistol.burst": ("burst:oga_sounds/sounds/cz.wav", 1.05, 0, (0.2, 1.0)),
    "pistol.empty": ("kenney_rpg-audio/Audio/metalLatch.ogg", 1.4, 0, 0.4),
    "pistol.reload": ("oga_reload.wav", 1.0, 0, None),
    "bullet.flesh": [("oga_fleshy/guns-arm-2b.wav", 1.0, 0, None),                    # "ours bounced off like rubber"
                     ("oga_fleshy/gun-1b.wav", 1.0, 0, None),
                     ("oga_impact/qubodupImpactMeat01.ogg", 1.0, 0, None)],
    "bullet.miss": [("oga_impacts/Impact 7.wav", 1.0, 0, 0.6),                        # "does not sound like bullet impact"
                    ("oga_impact/qubodupImpactStone.ogg", 1.0, 0, None),
                    ("oga_impacts/Impact 1.wav", 1.0, 0, 0.6)],
    "knife.swing": ("kenney_rpg-audio/Audio/drawKnife1.ogg", 1.1, 0, None),
    "knife.thrust": ("kenney_rpg-audio/Audio/drawKnife2.ogg", 0.85, 0, None),
    "knife.flesh": [("oga_fleshy/piercing-1a.wav", 1.0, 0, None),                     # "ours is hitting steel"
                    ("oga_fleshy/piercing-arm-2a.wav", 1.0, 0, None),
                    ("oga_impact/qubodupImpactMeat02.ogg", 1.1, 0, None)],
    "melee.miss": ("kenney_rpg-audio/Audio/cloth3.ogg", 1.3, 0, None),
    "crit.fail": ("kenney_rpg-audio/Audio/metalPot1.ogg", 1.0, 0, None),
    "weapon.drop": ("kenney_rpg-audio/Audio/metalPot1.ogg", 1.0, 0, None),             # "use our crit.fail sound"
    "weapon.pickup": ("kenney_rpg-audio/Audio/handleSmallLeather.ogg", 1.0, 0, None),
    "knockback": FALL,
    "down.male": [(f, p * 0.95, g, k) for f, p, g, k in FALL],
    "down.female": [(f, p * 1.1, g, k) for f, p, g, k in FALL],
    "step": [("seq", [("kenney_impact-sounds/Audio/footstep_grass_00%d.ogg" % i, 0.05 + 0.39 * i, -1.5 * i, None, 1.0) for i in range(4)]),
             ("seq", [("kenney_impact-sounds/Audio/footstep_concrete_00%d.ogg" % i, 0.05 + 0.39 * i, -1.5 * i, None, 1.0) for i in range(4)]),
             ("seq", [("kenney_rpg-audio/Audio/footstep0%d.ogg" % i, 0.05 + 0.39 * i, -1.5 * i, None, 1.0) for i in range(4)])],
    "geiger": ("synth:geiger", 1.0, 0, None),
    "levelup": [("seq", [("kenney_interface-sounds/Audio/tick_004.ogg", 0.0, 0, None, 1.0),   # "a very specific clicking earned sound"
                         ("kenney_interface-sounds/Audio/tick_004.ogg", 0.2, 0, None, 1.1),
                         ("kenney_interface-sounds/Audio/tick_004.ogg", 0.26, 0, None, 1.2),
                         ("kenney_interface-sounds/Audio/confirmation_003.ogg", 0.44, -2, None, 1.0)]),
                ("seq", [("kenney_interface-sounds/Audio/toggle_002.ogg", 0.0, 0, None, 1.0),
                         ("kenney_interface-sounds/Audio/toggle_002.ogg", 0.21, 0, None, 1.05),
                         ("kenney_interface-sounds/Audio/toggle_002.ogg", 0.42, 0, None, 1.1),
                         ("kenney_interface-sounds/Audio/glass_001.ogg", 0.55, -3, None, 1.0)]),
                ("kenney_interface-sounds/Audio/confirmation_003.ogg", 1.0, 0, None)],
    "store.take": ("kenney_rpg-audio/Audio/handleSmallLeather2.ogg", 1.0, 0, None),
    "store.sort": ("kenney_rpg-audio/Audio/dropLeather.ogg", 1.0, 0, None),
}


def find(base, rel):
    """The file, or the first file of its folder whose name starts the same (packs name their audio folders differently)."""
    p = os.path.join(base, rel)
    if os.path.exists(p):
        return p
    stem = os.path.splitext(os.path.basename(rel))[0]
    pack = rel.split("/")[0]
    for dp, _, fs in os.walk(os.path.join(base, pack)):
        for f in sorted(fs):
            if os.path.splitext(f)[0] == stem:
                return os.path.join(dp, f)
    raise FileNotFoundError(rel)


def geiger(path, seconds=1.6, rate=14.0, sr=44100, seed=19):
    """A Geiger counter's crackle from nothing: clicks at random (Poisson) times, each a 3 ms burst of decaying noise."""
    rnd = random.Random(seed)
    n = int(seconds * sr)
    buf = [0.0] * n
    t = rnd.expovariate(rate)
    while t < seconds:
        i0 = int(t * sr)
        amp = rnd.uniform(0.5, 1.0)
        for k in range(int(0.003 * sr)):
            if i0 + k < n:
                buf[i0 + k] += amp * rnd.uniform(-1, 1) * (1 - k / (0.003 * sr)) ** 2
        t += rnd.expovariate(rate)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, v * 0.8)) * 32767)) for v in buf))


def ffmpeg(src, dst, pitch, gain, keep, burst=0):
    filt = []
    if keep:   # seconds kept: the first N, or (start, end); faded out over the last 50 ms
        a, b = (0, keep) if not isinstance(keep, tuple) else keep
        filt.append("atrim=%s:%s,asetpts=PTS-STARTPTS,afade=t=out:st=%s:d=0.05" % (a, b, max(0, b - a - 0.05)))
    if pitch != 1.0:
        filt.append("asetrate=44100*%s,aresample=44100" % pitch)
    if gain:
        filt.append("volume=%sdB" % gain)
    filt.append("anull")
    args = ["ffmpeg", "-v", "error", "-y", "-i", src]
    if burst:
        chain = ",".join(filt)
        fc = "[0:a]aresample=44100,%s,asplit=3[a][b][c];[b]adelay=90[b2];[c]adelay=180[c2];[a][b2][c2]amix=inputs=3:normalize=0" % chain
        args += ["-filter_complex", fc]
    else:
        args += ["-af", "aresample=44100," + ",".join(filt)]
    args += ["-ac", "1", "-c:a", "libvorbis", "-q:a", "5", dst]
    subprocess.run(args, check=True)


def licence(pack):
    v = SOURCES.get(pack, "?")
    return v if v.startswith(("CC0,", "OGA-BY")) else "CC0 (" + v + ")"


def render(base, cand, dst):
    """One candidate into dst (ogg); returns [(its source file, the pack it came from)] for the credits."""
    if cand[0] == "seq":
        parts = cand[1]
        args, chains = ["ffmpeg", "-v", "error", "-y"], []
        srcs = []
        for i, (rel, at, gain, keep, pitch) in enumerate(parts):
            src = find(base, rel)
            srcs.append(src)
            args += ["-i", src]
            f = ["aresample=44100"]
            if keep:
                x, y = (0, keep) if not isinstance(keep, tuple) else keep
                f.append("atrim=%s:%s,asetpts=PTS-STARTPTS" % (x, y))
            if pitch != 1.0:
                f.append("asetrate=44100*%s,aresample=44100" % pitch)
            f.append("volume=%sdB" % gain)
            f.append("adelay=%d|%d" % (at * 1000, at * 1000))
            chains.append("[%d:a]%s,aformat=channel_layouts=mono[s%d]" % (i, ",".join(f), i))
        fc = ";".join(chains) + ";" + "".join("[s%d]" % i for i in range(len(parts))) + "amix=inputs=%d:normalize=0:duration=longest" % len(parts)
        subprocess.run(args + ["-filter_complex", fc, "-ac", "1", "-c:a", "libvorbis", "-q:a", "5", dst], check=True)
        return srcs
    rel, pitch, gain, keep = cand
    if rel.startswith("synth:"):
        tmp = dst[:-4] + ".wav"
        geiger(tmp)
        ffmpeg(tmp, dst, pitch, gain, keep)
        os.remove(tmp)
        return []
    burst = rel.startswith("burst:")
    src = find(base, rel.split(":", 1)[1] if burst else rel)
    ffmpeg(src, dst, pitch, gain, keep, burst=3 if burst else 0)
    return [src]


def main():
    base = sys.argv[1]
    os.makedirs(OUT, exist_ok=True)
    for old in os.listdir(OUT):   # a key given back to Minecraft leaves the pack
        if old.endswith(".ogg") and old[:-4].replace("_", ".") not in {k for k in MAP} and old[:-4] not in {k.replace(".", "_") for k in MAP}:
            os.remove(os.path.join(OUT, old))
    cands_dir = os.path.join(ROOT, "build", "free-cands")
    os.makedirs(cands_dir, exist_ok=True)
    events, credits, cands = {}, [], {}
    for key, val in MAP.items():
        name = key.replace(".", "_")
        options = val if isinstance(val, list) else [val]
        for i, cand in enumerate(options):
            dst = os.path.join(OUT, name + ".ogg") if i == 0 else os.path.join(cands_dir, "%s_%s.ogg" % (name, "abc"[i]))
            srcs = render(base, cand, dst)
            cred = "; ".join(os.path.relpath(x, base).replace(os.sep, "/") for x in srcs) or "made by tools/free_sounds.py (random clicks), no source"
            lic = "; ".join(sorted({licence(os.path.relpath(x, base).replace(os.sep, "/").split("/")[0]) for x in srcs})) or "Falloutak's own"
            if i == 0:
                credits.append((key, cred, lic))
            if len(options) > 1:
                if i == 0:
                    import shutil
                    shutil.copy(dst, os.path.join(cands_dir, "%s_a.ogg" % name))
                cands.setdefault(key, []).append({"label": "abc"[i], "src": "%s_%s.ogg" % (name, "abc"[i]), "from": cred, "licence": lic})
        events[key] = {"sounds": [{"name": "falloutak:free/" + name, "stream": False}]}
    io.open(os.path.join(cands_dir, "cands.json"), "w", encoding="utf-8", newline="\n").write(json.dumps(cands, indent=1))
    sj = os.path.join(ROOT, "pack", "assets", "falloutak", "sounds.json")
    old = json.load(io.open(sj, encoding="utf-8")) if os.path.exists(sj) else {}
    allev = dict(events)
    allev.update({k: v for k, v in old.items() if k.startswith("narrator.")})   # tools/narrator.py's events stay
    io.open(sj, "w", encoding="utf-8", newline="\n").write(json.dumps(allev, indent=1))
    io.open(os.path.join(ROOT, "src", "main", "resources", "data", "free-sounds.json"), "w", encoding="utf-8", newline="\n").write(
        json.dumps(sorted(events), indent=1))
    lines = ["# Falloutak's free sounds: where each comes from", "",
             "None is made from Fallout's files. Most are CC0 (public domain dedication: no rights reserved, no credit required) or made",
             "by Falloutak's own tool; the ones from *Fleshy Fight Sounds* are OGA-BY 3.0: free to use and share WITH credit to their",
             "author, will_leamon (opengameart.org/content/fleshy-fight-sounds). Thanks to Kenney (kenney.nl) and the OpenGameArt authors.", "",
             "| key | file | licence (source) |", "|---|---|---|"]
    lines += ["| `%s` | %s | %s |" % c for c in credits]
    io.open(os.path.join(ROOT, "pack", "FREE-SOUNDS-CREDITS.md"), "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")
    print(len(events), "sounds ->", OUT, ";", sum(len(v) for v in cands.values()), "candidates ->", cands_dir)


SOURCES = {
    "kenney_impact-sounds": "Kenney, Impact Sounds, kenney.nl/assets/impact-sounds",
    "kenney_sci-fi-sounds": "Kenney, Sci-fi Sounds, kenney.nl/assets/sci-fi-sounds",
    "kenney_interface-sounds": "Kenney, Interface Sounds, kenney.nl/assets/interface-sounds",
    "kenney_rpg-audio": "Kenney, RPG Audio, kenney.nl/assets/rpg-audio",
    "kenney_digital-audio": "Kenney, Digital Audio, kenney.nl/assets/digital-audio",
    "kenney_music-jingles": "Kenney, Music Jingles, kenney.nl/assets/music-jingles",
    "oga_sounds": "OpenGameArt, Gunshot sounds, opengameart.org/content/gunshot-sounds",
    "oga_reload.wav": "OpenGameArt, Handgun Reload Sound Effect by zer0_sol, opengameart.org/content/handgun-reload-sound-effect",
    "oga_shotguncock_0.wav": "OpenGameArt, Gun reload sounds, opengameart.org/content/gun-reload-sounds",
    "oga_gunreload1.wav": "OpenGameArt, Gun reload sounds, opengameart.org/content/gun-reload-sounds",
    "oga_assaultriflereload1_0.wav": "OpenGameArt, Gun reload sounds, opengameart.org/content/gun-reload-sounds",
    "oga_impact": "CC0, OpenGameArt, Impact by Iwan 'qubodup' Gabovitch, opengameart.org/content/impact",
    "oga_impacts": "CC0, OpenGameArt, Impacts/Gun/sfx by Eldritch Grim, opengameart.org/content/impactsgunsfx",
    "oga_fleshy": "OGA-BY 3.0 (credit required), OpenGameArt, Fleshy Fight Sounds by will_leamon, opengameart.org/content/fleshy-fight-sounds",
}

if __name__ == "__main__":
    main()
