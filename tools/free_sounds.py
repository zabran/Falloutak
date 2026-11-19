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
# gain dB, kept, pitch), ...]) = several recordings laid one after another (steps, clicks). ("variants", [candidate, ...]) = ALL of
# them in the pack, the client picks one each time (round 2, 11-19: where Marek found several good, "ABC all are good").
# The falls (round 2: "none fits - original has painful scream and body falling on ground"): Fallout 2's is a short cry, then the
# body hitting the ground ~0.4 s later in two knocks (the trunk, then the limbs). Ours is made the same way from free recordings:
# a cry, then a heavy soft thump, a slap and a second smaller knock. The cry is in the file, so the plugin adds no hurt voice to
# these (Sfx.VOICED); a woman's knockback plays down.female.
THUDS = [[("kenney_impact-sounds/Audio/impactSoft_heavy_001.ogg", 0.0, -1, None, 1.0), ("oga_fleshy/punch_alt-2b.wav", 0.0, -6, None, 1.0),
          ("kenney_impact-sounds/Audio/impactSoft_medium_002.ogg", 0.13, -5, None, 1.0)],
         [("kenney_impact-sounds/Audio/impactPunch_heavy_000.ogg", 0.0, -1, None, 1.0), ("oga_bfh/bfh1_hit_06.ogg", 0.01, -6, None, 1.0),
          ("kenney_impact-sounds/Audio/impactSoft_medium_001.ogg", 0.12, -5, None, 1.0)],
         [("kenney_impact-sounds/Audio/impactSoft_heavy_003.ogg", 0.0, -1, None, 1.0), ("oga_thwack/thwack-06.wav", 0.0, -7, None, 1.0),
          ("oga_bfh/bfh1_misc_03.ogg", 0.15, -6, None, 1.0)]]


def fall(cry, keep, gain, thud, at):
    """A cry (file, the part kept, gain dB), then the body hitting the ground `at` seconds later."""
    return ("seq", [(cry, 0.0, gain, keep, 1.0)] + [(f, at + dt, g, k, p) for f, dt, g, k, p in THUDS[thud]])


# Round 3 (11-19): "The original scream is pain scream (yau). A shows surprise scream and fall. BC show assault type of scream";
# down.male A "sounds female" (that yell sat at ~550 Hz; Fallout 2's man cries at ~170 Hz); down.female A "sounds like woman
# driving and screeming because she cannot stop the colision". So round 4: PAIN cries, a man's low (Praat pitch 130-155 Hz).
MAN = [fall("oga_baradari/pain4.wav", (0.0, 0.58), 1, 0, 0.42),       # whisper: "Argh!", 132 Hz
       fall("oga_baradari/painh.wav", (0.0, 0.37), 0, 1, 0.38),       # "Ugh!", 154 Hz
       fall("oga_baradari/pain5.wav", (0.0, 0.52), 0, 2, 0.42)]       # "Ugh.", 146 Hz
WOMAN = [fall("oga_auravoice/female_hurt_grunts_groans_1.ogg", (2.11, 2.67), 3, 0, 0.42),   # "Ah!", 334 Hz
         fall("oga_auravoice/female_hurt_grunts_groans_1.ogg", (0.46, 1.10), 4, 1, 0.45),   # "Ugh!", 383 Hz
         fall("oga_auravoice/female_hurt_grunts_groans_1.ogg", (3.73, 4.10), 4, 2, 0.36)]   # "Ugh!", 465 Hz
MAP = {
    "combat.start": ("kenney_digital-audio/Audio/lowThreeTone.ogg", 0.9, 0, None),
    "combat.end": ("kenney_interface-sounds/Audio/confirmation_002.ogg", 1.0, 0, None),
    "turn.start": ("kenney_interface-sounds/Audio/select_003.ogg", 1.0, 0, None),      # round 2: "pick A"
    "mode.next": ("kenney_rpg-audio/Audio/metalClick.ogg", 1.2, 0, None),
    "stance": ("kenney_rpg-audio/Audio/cloth1.ogg", 1.0, 0, None),
    "hands": ("oga_shotguncock_0.wav", 1.0, 0, None),                                  # round 2: "pick A"
    "pistol.single": ("oga_sounds/sounds/cz.wav", 1.0, 0, (0.2, 1.1)),   # the shot starts 0.2 s in
    "pistol.burst": ("burst:oga_sounds/sounds/cz.wav", 1.05, 0, (0.2, 1.0)),
    "pistol.empty": ("kenney_rpg-audio/Audio/metalLatch.ogg", 1.4, 0, 0.4),
    "pistol.reload": ("oga_reload.wav", 1.0, 0, None),
    "bullet.flesh": ("variants", [("oga_fleshy/guns-arm-2b.wav", 1.0, 0, None),     # round 2: "ABC all are good."
                                  ("oga_fleshy/gun-1b.wav", 1.0, 0, None),
                                  ("oga_impact/qubodupImpactMeat01.ogg", 1.0, 0, None)]),
    "bullet.miss": ("variants", [("oga_impacts/Impact 7.wav", 1.0, 0, 0.6),         # round 2: "AC are good"
                                 ("oga_impacts/Impact 1.wav", 1.0, 0, 0.6)]),
    "knife.swing": ("kenney_rpg-audio/Audio/drawKnife1.ogg", 1.1, 0, None),
    "knife.thrust": ("kenney_rpg-audio/Audio/drawKnife2.ogg", 0.85, 0, None),
    "knife.flesh": ("variants", [("oga_fleshy/piercing-1a.wav", 1.0, 0, None),      # round 2: "AB fits"
                                 ("oga_fleshy/piercing-arm-2a.wav", 1.0, 0, None)]),
    "melee.miss": ("kenney_rpg-audio/Audio/cloth3.ogg", 1.3, 0, None),
    "crit.fail": ("kenney_rpg-audio/Audio/metalPot1.ogg", 1.0, 0, None),
    "weapon.drop": ("kenney_rpg-audio/Audio/metalPot1.ogg", 1.0, 0, None),             # "use our crit.fail sound"
    "weapon.pickup": ("kenney_rpg-audio/Audio/handleSmallLeather.ogg", 1.0, 0, None),
    # round 4 (11-19): "knockback: ABC is great." "down.male: ABC is great." "down.female: ABC is great." -> all three of each
    "knockback": ("variants", MAN),                                                    # Fallout 2's knockback IS its man's fall
    "down.male": ("variants", MAN),
    "down.female": ("variants", WOMAN),
    "step": ("variants", [                                                             # round 2: "ABC - all are great."
        ("seq", [("kenney_impact-sounds/Audio/footstep_grass_00%d.ogg" % i, 0.05 + 0.39 * i, -1.5 * i, None, 1.0) for i in range(4)]),
        ("seq", [("kenney_impact-sounds/Audio/footstep_concrete_00%d.ogg" % i, 0.05 + 0.39 * i, -1.5 * i, None, 1.0) for i in range(4)]),
        ("seq", [("kenney_rpg-audio/Audio/footstep0%d.ogg" % i, 0.05 + 0.39 * i, -1.5 * i, None, 1.0) for i in range(4)])]),
    "geiger": ("synth:geiger", 1.0, 0, None),
    # round 3: "pick C" (round 2's C, Kenney's confirmation chime, made longer by an echo tail)
    "levelup": ("seq", [("kenney_interface-sounds/Audio/confirmation_003.ogg", 0.0, 0, None, 1.0),
                        ("kenney_interface-sounds/Audio/confirmation_003.ogg", 0.16, -9, None, 1.0),
                        ("kenney_interface-sounds/Audio/confirmation_003.ogg", 0.32, -15, None, 1.0)]),
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
    return v if v.startswith(("CC0,", "OGA-BY", "CC-BY")) else "CC0 (" + v + ")"


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
                f.append("atrim=%s:%s,asetpts=PTS-STARTPTS,afade=t=out:st=%s:d=0.05" % (x, y, max(0, y - x - 0.05)))
            if pitch != 1.0:
                f.append("asetrate=44100*%s,aresample=44100" % pitch)
            f.append("volume=%sdB" % gain)
            f.append("adelay=%d|%d" % (at * 1000, at * 1000))
            chains.append("[%d:a]%s,aformat=channel_layouts=mono[s%d]" % (i, ",".join(f), i))
        fc = ";".join(chains) + ";" + "".join("[s%d]" % i for i in range(len(parts))) + "amix=inputs=%d:normalize=0:duration=longest,alimiter=limit=0.95:level=disabled" % len(parts)
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
    for old in os.listdir(OUT):   # every file is written again below; a key given back to Minecraft leaves the pack
        if old.endswith(".ogg"):
            os.remove(os.path.join(OUT, old))
    for old in os.listdir(os.path.join(ROOT, "build", "free-cands")) if os.path.isdir(os.path.join(ROOT, "build", "free-cands")) else []:
        os.remove(os.path.join(ROOT, "build", "free-cands", old))
    cands_dir = os.path.join(ROOT, "build", "free-cands")
    os.makedirs(cands_dir, exist_ok=True)
    events, credits, cands = {}, [], {}
    for key, val in MAP.items():
        name = key.replace(".", "_")
        if isinstance(val, tuple) and val[0] == "variants":   # all of them in the pack, one event of several sounds
            srcs, files = [], []
            for i, cand in enumerate(val[1]):
                fn = name + ("" if i == 0 else "_%d" % (i + 1))
                srcs += render(base, cand, os.path.join(OUT, fn + ".ogg"))
                files.append(fn)
            credits.append((key, "; ".join(dict.fromkeys(os.path.relpath(x, base).replace(os.sep, "/") for x in srcs)),
                            "; ".join(sorted({licence(os.path.relpath(x, base).replace(os.sep, "/").split("/")[0]) for x in srcs}))))
            events[key] = {"sounds": [{"name": "falloutak:free/" + f, "stream": False} for f in files]}
            continue
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
             "author, will_leamon (opengameart.org/content/fleshy-fight-sounds); the men's pain cries in the falls are CC-BY 3.0, credit",
             "to Michel Baradari (*11 male human pain/death sounds*, opengameart.org/content/11-male-human-paindeath-sounds). Thanks to",
             "Kenney (kenney.nl) and the OpenGameArt authors.", "",
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
    # round 3 (11-19): the falls' cries and the body hitting the ground; dual-licensed packs used under their CC0
    "oga_yell": "CC0, OpenGameArt, Male Grunt/Yelling sounds (CC0 or OGA-BY 3.0, used as CC0), opengameart.org/content/male-gruntyelling-sounds",
    "oga_slightscreams": "CC0, OpenGameArt, 15 vocal male strain/hurt/pain/jump sounds, opengameart.org/content/15-vocal-male-strainhurtpainjump-sounds",
    "oga_dying_female": "CC0, OpenGameArt, Dying Voices - Female - RPG by Galacti-Chron (CC0 or CC-BY 3.0, used as CC0), opengameart.org/content/dying-voices-female-rpg",
    "oga_female_screams": "CC0, OpenGameArt, Female screams (files 1-4 CC0 per its license.txt; 4 = WomanScream by Archeos), opengameart.org/content/female-screams",
    "oga_bfh": "CC0, OpenGameArt, 75 CC0 breaking / falling / hit sfx by rubberduck, opengameart.org/content/75-cc0-breaking-falling-hit-sfx",
    "oga_thwack": "CC0, OpenGameArt, Thwack Sounds by AntumDeluge, opengameart.org/content/thwack-sounds",
    # round 4 (11-19): pain cries
    "oga_baradari": "CC-BY 3.0 (credit required), OpenGameArt, 11 male human pain/death sounds by Michel Baradari, opengameart.org/content/11-male-human-paindeath-sounds",
    "oga_auravoice": "CC0, OpenGameArt, Female Hurt Grunts & Groans by AuraVoice, opengameart.org/content/female-hurt-grunts-groans",
}

if __name__ == "__main__":
    main()
