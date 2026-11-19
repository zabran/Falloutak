"""The narrator's comments on critical hits and failures, voiced (Marek 11-19: *"Fallout had funny comments - at least in Czech
version. Could you the documentary narrator speak the comments to crits?"*).

    python -I tools/narrator.py          (needs the kokoro package and ffmpeg; voices only lines whose text changed)

Reads src/main/resources/data/narrator.json (OUR OWN text, nothing from Fallout's messages), voices every line with the English
documentary narrator of the LollyWood films (Kokoro-82M, Apache-2.0, voice bm_lewis at speed 0.92: Marek by ear), a line with
{he}/{his}/... twice (a man's and a woman's form), writes pack/assets/falloutak/sounds/narrator/<id>[_f].ogg and the events
`falloutak:narrator.<id>[.f]` into pack/assets/falloutak/sounds.json (beside the free sounds' events, which it leaves alone),
and pack/NARRATOR.md (where the voice comes from) for tools/build_pack.py.
"""
import hashlib, io, json, os, re, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DATA = os.path.join(ROOT, "src", "main", "resources", "data", "narrator.json")
OUT = os.path.join(ROOT, "pack", "assets", "falloutak", "sounds", "narrator")
SOUNDS = os.path.join(ROOT, "pack", "assets", "falloutak", "sounds.json")
VOICE, LANG, SPEED = "bm_lewis", "b", 0.92
# words Kokoro reads wrong, as its own [word](/IPA/) markup
SAY = {"lead-filled": "[lead](/lˈɛd/)-filled"}
FORMS = {"m": {"he": "he", "him": "him", "his": "his", "himself": "himself", "He": "He", "His": "His"},
         "f": {"he": "she", "him": "her", "his": "her", "himself": "herself", "He": "She", "His": "Her"}}


def form(text, f):
    return re.sub(r"\{(\w+)\}", lambda m: FORMS[f][m.group(1)], text)


def main():
    lines = json.load(io.open(DATA, encoding="utf-8"))["lines"]
    os.makedirs(OUT, exist_ok=True)
    seen_path = os.path.join(OUT, "voiced.json")
    seen = json.load(io.open(seen_path, encoding="utf-8")) if os.path.exists(seen_path) else {}
    todo, events = [], {}
    for ln in lines:
        for f in (("m", "f") if "{" in ln["text"] else ("m",)):
            text = form(ln["text"], f)
            name = ln["id"].replace(".", "_") + ("_f" if f == "f" else "")
            key = "narrator." + ln["id"] + (".f" if f == "f" else "")
            events[key] = {"sounds": [{"name": "falloutak:narrator/" + name, "stream": False}]}
            h = hashlib.sha1((text + VOICE + str(SPEED) + json.dumps(SAY, sort_keys=True)).encode("utf-8")).hexdigest()[:12]
            if seen.get(name) != h or not os.path.exists(os.path.join(OUT, name + ".ogg")):
                todo.append((name, text, h))
    if todo:
        sys.path[:] = [x for x in sys.path if os.path.abspath(x or ".") != HERE]
        import numpy as np, soundfile as sf
        from kokoro import KPipeline
        pipe = KPipeline(lang_code=LANG, repo_id="hexgrad/Kokoro-82M")
        pause = np.zeros(int(24000 * .18))
        for name, text, h in todo:
            said = text
            for w, marked in SAY.items():
                said = said.replace(w, marked)
            parts = [np.asarray(x.audio) for x in pipe(said, voice=VOICE, speed=SPEED, split_pattern=r"(?<=[.!?])\s+")]
            wav = os.path.join(OUT, name + ".wav")
            sf.write(wav, np.concatenate([s for seg in parts for s in (seg, pause)])[:-len(pause)], 24000)
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", wav, "-ac", "1", "-ar", "44100", "-c:a", "libvorbis", "-q:a", "5",
                            os.path.join(OUT, name + ".ogg")], check=True)
            os.remove(wav)
            seen[name] = h
            print("voiced", name, "|", text, flush=True)
    keep = {n for n in seen if os.path.exists(os.path.join(OUT, n + ".ogg"))}
    names = {e["sounds"][0]["name"].split("/", 1)[1] for e in events.values()}
    for stale in sorted(keep - names):   # a line removed from the data: its file goes too
        os.remove(os.path.join(OUT, stale + ".ogg"))
        seen.pop(stale, None)
    io.open(seen_path, "w", encoding="utf-8", newline="\n").write(json.dumps(seen, indent=1, sort_keys=True))
    snd = json.load(io.open(SOUNDS, encoding="utf-8")) if os.path.exists(SOUNDS) else {}
    snd = {k: v for k, v in snd.items() if not k.startswith("narrator.")}
    snd.update(events)
    io.open(SOUNDS, "w", encoding="utf-8", newline="\n").write(json.dumps(snd, indent=1))
    io.open(os.path.join(ROOT, "pack", "NARRATOR.md"), "w", encoding="utf-8", newline="\n").write(
        "# The narrator's comments\n\nThe text is Falloutak's own (`src/main/resources/data/narrator.json`), nothing taken from Fallout's "
        "messages. The voice is Kokoro-82M (hexgrad, Apache-2.0), its British voice `bm_lewis` at speed 0.92, made by "
        "`tools/narrator.py` on our own machine.\n")
    print(len(events), "narrator events,", len(todo), "voiced now ->", OUT)


if __name__ == "__main__":
    main()
