"""Refreshes the class table of docs/ARCHITECTURE.md (between its CLASSES markers): each class's lines. The descriptions are kept
by hand; a new class gets its javadoc's first sentence marked NEW (describe it then), a removed class drops out.
    python -I -X utf8 tools/arch_index.py"""
import io, os, re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "cz", "lollywood", "falloutak")
DOC = os.path.join(ROOT, "docs", "ARCHITECTURE.md")


def first_sentence(src):
    m = re.search(r"/\*\*(.*?)\*/\s*(?:@\w+\s*)*(?:public\s+)?(?:final\s+)?(?:abstract\s+)?(?:class|record|enum|interface)\s", src, re.S)
    if not m:
        return ""
    t = re.sub(r"\s*\n\s*\*\s?", " ", m.group(1)).strip()
    t = re.sub(r"\{@(?:link|code) ([^}]*)\}", r"\1", t)
    t = re.sub(r"<[^>]+>", "", t)
    return re.split(r"(?<=[.:])\s", t, maxsplit=1)[0][:220].replace("|", "/")


doc = io.open(DOC, encoding="utf-8").read()
kept = dict(re.findall(r"^\| `(\w+)` \| \d+ \| (.*) \|$", doc, re.M))
rows = []
for fn in sorted(os.listdir(SRC)):
    if fn.endswith(".java"):
        src = io.open(os.path.join(SRC, fn), encoding="utf-8").read()
        n = fn[:-5]
        rows.append((n, src.count("\n"), kept.get(n) or "NEW, describe it: " + first_sentence(src)))
table = ["| class | lines | what it is |", "|---|---|---|"]
table += [f"| `{n}` | {k} | {d} |" for n, k, d in rows]
table.append(f"| **{len(rows)} classes** | **{sum(r[1] for r in rows)}** | |")
a, b = "<!-- CLASSES -->", "<!-- /CLASSES -->"
i, j = doc.index(a) + len(a), doc.index(b)
io.open(DOC, "w", encoding="utf-8", newline="\n").write(doc[:i] + "\n" + "\n".join(table) + "\n" + doc[j:])
print(len(rows), "classes,", sum(1 for r in rows if r[2].startswith("NEW")), "to describe")
