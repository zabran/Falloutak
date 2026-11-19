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
MAP = {
    "combat.start": ("kenney_digital-audio/Audio/lowThreeTone.ogg", 0.9, 0, None),
    "combat.end": ("kenney_interface-sounds/Audio/confirmation_002.ogg", 1.0, 0, None),
    "turn.start": ("kenney_digital-audio/Audio/twoTone1.ogg", 1.0, -3, None),
    "turn.end": ("kenney_interface-sounds/Audio/click_001.ogg", 1.0, 0, None),
    "ap.lacking": ("kenney_interface-sounds/Audio/error_004.ogg", 1.0, 0, None),
    "mode.next": ("kenney_rpg-audio/Audio/metalClick.ogg", 1.2, 0, None),
    "stance": ("kenney_rpg-audio/Audio/cloth1.ogg", 1.0, 0, None),
    "steady": ("kenney_rpg-audio/Audio/clothBelt.ogg", 1.0, 0, None),
    "hands": ("kenney_rpg-audio/Audio/beltHandle1.ogg", 1.0, 0, None),
    "pistol.single": ("oga_sounds/sounds/cz.wav", 1.0, 0, (0.2, 1.1)),   # the shot starts 0.2 s in
    "pistol.burst": ("burst:oga_sounds/sounds/cz.wav", 1.05, 0, (0.2, 1.0)),
    "pistol.empty": ("kenney_rpg-audio/Audio/metalLatch.ogg", 1.4, 0, 0.4),
    "pistol.reload": ("oga_reload.wav", 1.0, 0, None),
    "bullet.flesh": ("kenney_impact-sounds/Audio/impactSoft_heavy_002.ogg", 1.0, 2, None),
    "bullet.miss": ("kenney_impact-sounds/Audio/impactMining_001.ogg", 1.3, 0, None),
    "knife.swing": ("kenney_rpg-audio/Audio/drawKnife1.ogg", 1.1, 0, None),
    "knife.thrust": ("kenney_rpg-audio/Audio/drawKnife2.ogg", 0.85, 0, None),
    "knife.flesh": ("kenney_rpg-audio/Audio/knifeSlice.ogg", 1.0, 0, None),
    "melee.miss": ("kenney_rpg-audio/Audio/cloth3.ogg", 1.3, 0, None),
    "crit": ("kenney_impact-sounds/Audio/impactPunch_heavy_001.ogg", 1.0, 2, None),
    "crit.fail": ("kenney_rpg-audio/Audio/metalPot1.ogg", 1.0, 0, None),
    "weapon.drop": ("kenney_impact-sounds/Audio/impactMetal_medium_001.ogg", 1.0, 0, None),
    "weapon.pickup": ("kenney_rpg-audio/Audio/handleSmallLeather.ogg", 1.0, 0, None),
    "grenade.throw": ("kenney_rpg-audio/Audio/cloth4.ogg", 1.4, 0, None),
    "explosion": ("kenney_sci-fi-sounds/Audio/lowFrequency_explosion_000.ogg", 1.0, 2, None),
    "gas.release": ("kenney_sci-fi-sounds/Audio/thrusterFire_000.ogg", 0.8, 0, (0, 2.0)),
    "flare": ("kenney_sci-fi-sounds/Audio/thrusterFire_001.ogg", 1.3, 0, (0, 1.5)),
    "knockback": ("kenney_impact-sounds/Audio/impactSoft_heavy_001.ogg", 0.9, 0, None),
    "burning": ("kenney_sci-fi-sounds/Audio/thrusterFire_002.ogg", 0.9, 0, (0, 1.5)),
    "down.male": ("kenney_impact-sounds/Audio/impactSoft_heavy_000.ogg", 0.85, 0, None),
    "down.female": ("kenney_impact-sounds/Audio/impactSoft_heavy_000.ogg", 1.0, 0, None),
    "step": ("kenney_impact-sounds/Audio/footstep_concrete_001.ogg", 1.0, 0, None),
    "stimpak": ("kenney_sci-fi-sounds/Audio/thrusterFire_003.ogg", 1.8, -4, 0.35),
    "chem": ("kenney_interface-sounds/Audio/glass_002.ogg", 1.0, 0, None),
    "firstaid": ("kenney_rpg-audio/Audio/clothBelt2.ogg", 1.0, 0, None),
    "repair": ("kenney_impact-sounds/Audio/impactMetal_light_002.ogg", 1.0, 0, None),
    "geiger": ("synth:geiger", 1.0, 0, None),
    "gas.hiss": ("kenney_sci-fi-sounds/Audio/thrusterFire_004.ogg", 0.7, -6, (0, 1.2)),
    "electric": ("kenney_digital-audio/Audio/zap1.ogg", 0.8, 0, None),
    "heart.stop": ("kenney_digital-audio/Audio/lowDown.ogg", 0.8, 0, None),
    "defib": ("kenney_digital-audio/Audio/zapTwoTone.ogg", 0.9, 0, None),
    "revived": ("kenney_digital-audio/Audio/powerUp1.ogg", 1.0, 0, None),
    "levelup": ("kenney_music-jingles/Audio/8-bit jingles/jingles_NES00.ogg", 1.0, -2, None),
    "pipboy": ("kenney_interface-sounds/Audio/maximize_002.ogg", 1.0, 0, None),
    "store.take": ("kenney_rpg-audio/Audio/handleSmallLeather2.ogg", 1.0, 0, None),
    "store.sort": ("kenney_rpg-audio/Audio/dropLeather.ogg", 1.0, 0, None),
    "radmie": ("kenney_sci-fi-sounds/Audio/slime_000.ogg", 0.7, 0, None),
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


def main():
    base = sys.argv[1]
    os.makedirs(OUT, exist_ok=True)
    events, credits = {}, []
    for key, (rel, pitch, gain, keep) in MAP.items():
        name = key.replace(".", "_")
        dst = os.path.join(OUT, name + ".ogg")
        if rel.startswith("synth:"):
            tmp = os.path.join(OUT, name + ".wav")
            geiger(tmp)
            ffmpeg(tmp, dst, pitch, gain, keep)
            os.remove(tmp)
            credits.append((key, "made by tools/free_sounds.py (random clicks), no source", "Falloutak's own"))
        else:
            burst = rel.startswith("burst:")
            src = find(base, rel.split(":", 1)[1] if burst else rel)
            ffmpeg(src, dst, pitch, gain, keep, burst=3 if burst else 0)
            pack = os.path.relpath(src, base).replace(os.sep, "/").split("/")[0]
            credits.append((key, os.path.relpath(src, base).replace(os.sep, "/"), "CC0 (" + SOURCES.get(pack, pack) + ")"))
        events[key] = {"sounds": [{"name": "falloutak:free/" + name, "stream": False}]}
    io.open(os.path.join(ROOT, "pack", "assets", "falloutak", "sounds.json"), "w", encoding="utf-8", newline="\n").write(
        json.dumps(events, indent=1))
    io.open(os.path.join(ROOT, "src", "main", "resources", "data", "free-sounds.json"), "w", encoding="utf-8", newline="\n").write(
        json.dumps(sorted(events), indent=1))
    lines = ["# Falloutak's free sounds: where each comes from", "",
             "All are CC0 (public domain dedication: no rights reserved, no credit required) or made by Falloutak's own tool. None is",
             "made from Fallout's files. Thanks to Kenney (kenney.nl) and the OpenGameArt authors.", "",
             "| key | file | licence (source) |", "|---|---|---|"]
    lines += ["| `%s` | %s | %s |" % c for c in credits]
    io.open(os.path.join(ROOT, "pack", "FREE-SOUNDS-CREDITS.md"), "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")
    print(len(events), "sounds ->", OUT)


SOURCES = {
    "kenney_impact-sounds": "Kenney, Impact Sounds, kenney.nl/assets/impact-sounds",
    "kenney_sci-fi-sounds": "Kenney, Sci-fi Sounds, kenney.nl/assets/sci-fi-sounds",
    "kenney_interface-sounds": "Kenney, Interface Sounds, kenney.nl/assets/interface-sounds",
    "kenney_rpg-audio": "Kenney, RPG Audio, kenney.nl/assets/rpg-audio",
    "kenney_digital-audio": "Kenney, Digital Audio, kenney.nl/assets/digital-audio",
    "kenney_music-jingles": "Kenney, Music Jingles, kenney.nl/assets/music-jingles",
    "oga_sounds": "OpenGameArt, Gunshot sounds, opengameart.org/content/gunshot-sounds",
    "oga_reload.wav": "OpenGameArt, Handgun Reload Sound Effect by zer0_sol, opengameart.org/content/handgun-reload-sound-effect",
}

if __name__ == "__main__":
    main()
