package cz.lollywood.falloutak;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.*;

import static cz.lollywood.falloutak.Menus.*;

/**
 * The research pages (part 8, `08-RESEARCH.md` §3.8): the cards (open, in theory, ready for the prototype, done) with their progress by
 * source, theory and experiments started in a laboratory, the prototype in its way's own building, Eureka; for the GM the finds, the
 * achievements and the inspirations he credits, and the failed rarity-1 prototypes he rules. Any player (*"In base, players are kings"*).
 */
final class Labs {
    private Labs() {}

    static Production pr() { return pl.production; }

    static boolean gm(Player p) { return p.hasPermission("falloutak.gm"); }

    static List<Research.Card> cards() {
        List<Research.Card> l = new ArrayList<>();
        for (Research.Card c : Research.CARDS.values()) if (!c.parked) l.add(c);
        return l;
    }

    static String gives(Research.Card c) {
        StringJoiner j = new StringJoiner(", ");
        for (String id : c.recipes) { Production.Recipe r = Production.RECIPES.get(id); if (r != null) j.add(r.name + " → " + Production.makesText(r)); }
        return j.toString();
    }

    static String label(Research.Card c, Production.Workforce wf) {
        return c.name + " · " + Research.state(pr(), c, wf) + (Research.done(pr(), c) ? "" : " · " + Math.round(Research.progress(pr(), c)) + " %");
    }

    // ------------------------------------------------------------------ the list
    /** The achievements that ask about the base's state, checked when a research page opens (round 4). */
    static void check(Player p) {
        List<String> out = new ArrayList<>();
        Research.check(pr(), pl.base, out);
        if (!out.isEmpty()) { pr().save(); out.forEach(m -> say(p, m)); }
    }

    static void main(Player p) {
        check(p);
        Production pr = pr();
        Production.Workforce wf = pl.workforce();
        List<Component> lines = new ArrayList<>();
        StringJoiner labs = new StringJoiner(", ");
        for (Production.Building b : pr.buildings)
            if ("Laboratory".equals(b.kind)) labs.add(b.name + " (Tier " + Rules.roman(b.tier) + (b.job == null ? ", free" : ", " + Production.recipe(b.job.recipe).name) + ")");
        lines.add(line(labs.length() == 0 ? "No laboratory yet: Work anywhere in the base → Build a laboratory (Tier II)." : "Laboratories: " + labs,
                labs.length() == 0 ? NamedTextColor.RED : NamedTextColor.WHITE));
        lines.add(line("A card's points: a find up to 50 %, its achievement 20 % (a deed near the invention, like Civ6's Eurekas), an "
                + "inspiration 10 %, two experiments 10 % each, related research up to 25 %, theory for the rest; then the prototype, built "
                + "by the way's own crew led by a researcher, and its test. A card that needs others opens when they are done.", NamedTextColor.GRAY));
        List<Ui.Btn> b = new ArrayList<>();
        for (Research.Card c : cards()) b.add(btn(label(c, wf), gives(c) + " · " + Research.breakdown(pr, c), x -> card(x, c.id)));
        if (gm(p) && !pr.pending.isEmpty()) b.add(btn("GM: rule a failed prototype (" + pr.pending.size() + ")", "rarity 1: what it comes out as (round 2)", Labs::pending));
        menu(p, "Research", lines, List.of(), b, Works::main, 2);
    }

    // ------------------------------------------------------------------ a card
    static void card(Player p, String id) {
        Production pr = pr();
        Research.Card c = Research.CARDS.get(id);
        if (c == null) { main(p); return; }
        check(p);
        Production.Workforce wf = pl.workforce();
        Research.Project pj = pr.research.get(c.id);
        Production.Recipe t = c.target(), proto = Research.jobFor(c, "prototype");
        List<Component> lines = new ArrayList<>();
        lines.add(line(c.name + ": " + gives(c) + " (" + c.better + ")", NamedTextColor.WHITE));
        lines.add(line("Tier " + Rules.roman(c.tier) + ", rarity " + c.rarity + " · " + c.points + " points · " + c.science + " " + c.gate
                + " · a laboratory of Tier " + Rules.roman(c.lab) + " or better" + (c.basic && c.lab < c.tier ? " (a basic card: up to two tiers above the laboratory)" : "")
                + " · lines: " + String.join(", ", c.lines), NamedTextColor.GRAY));
        if (!c.needs.isEmpty()) lines.add(line("Needs first: " + Research.needsText(c) + (Research.open(pr, c) ? " (done)" : ""), Research.open(pr, c) ? NamedTextColor.GRAY : NamedTextColor.RED));
        List<Research.Card> opens = Research.opens(c);
        if (!opens.isEmpty()) {
            StringJoiner o = new StringJoiner(", ");
            for (Research.Card d : opens) o.add(d.name);
            lines.add(line("Opens the way to: " + o, NamedTextColor.GRAY));
        }
        for (String n : c.notes) lines.add(line("Note: " + n, NamedTextColor.GOLD));
        lines.add(line("Progress " + Math.round(Research.progress(pr, c)) + " %: " + Research.breakdown(pr, c), NamedTextColor.YELLOW));
        if (!Research.done(pr, c)) {
            double left = Research.theoryLeft(pr, c);
            lines.add(line(left <= 0 ? "Theory: done." : "Theory left: " + Math.round(left) + " points (a researcher at the gate " + Math.round(left)
                    + " hours, at four times it " + Math.round(left / Research.RULES.theory_max) + "; up to " + Research.RULES.researchers + " at once)", NamedTextColor.GRAY));
            lines.add(line(Research.achievementLine(pr, c, pl.base), NamedTextColor.GRAY));
            lines.add(line("Experiments: " + (pj == null ? 0 : pj.experimentsDone) + " of " + Research.maxExperiments(pj, wf, null) + ", each "
                    + c.experiment.electronics + " Electronics + " + c.experiment.machinery + " Machinery of Tier " + Rules.roman(c.tier) + ", "
                    + SheetGui.f(Research.jobFor(c, "experiment").hours) + " h in a laboratory (no roll)", NamedTextColor.GRAY));
            if (t != null) lines.add(line("The prototype: at 100 % (90 % with Prototype), in a " + t.building + " of Tier " + Rules.roman(t.tier) + ", "
                    + SheetGui.f(proto.hours) + " h from " + Production.inputsText(t) + ", by a crew of " + t.skill + " " + t.min + " led by a researcher ("
                    + c.science + " " + c.gate + ", no slot); the test 50 % + (the lead's " + c.science + " − " + c.gate + "), +10 % a failure"
                    + (pj != null && pj.failures > 0 ? " (failed " + pj.failures + ")" : ""), NamedTextColor.GRAY));
        }
        if (pj != null && !pj.researchers.isEmpty()) {
            StringJoiner who = new StringJoiner(", ");
            for (Production.Worker w : Research.researchers(pj, wf)) who.add(w.label());
            if (who.length() > 0) lines.add(line("Researchers: " + who, NamedTextColor.GRAY));
        }
        List<Ui.Btn> b = new ArrayList<>();
        if (!Research.done(pr, c) && Research.open(pr, c)) {
            if (Research.theoryLeft(pr, c) > 0 && running(c, "theory") == null)
                b.add(btn("Theory in a laboratory", "the researchers: " + c.science + " " + c.gate + " or better, the best free fill the slots", x -> pickLab(x, c, "theory")));
            if ((pj == null ? 0 : pj.experimentsDone) + count(c, "experiment") < Research.maxExperiments(pj, wf, null))
                b.add(btn("An experiment", c.experiment.electronics + " Electronics + " + c.experiment.machinery + " Machinery " + Rules.roman(c.tier) + " used up (Lab Rat: half)", x -> pickLab(x, c, "experiment")));
            if (Research.ready(pr, c, wf) && running(c, "prototype") == null)
                b.add(btn("Build the prototype", "in a " + proto.building + " of Tier " + Rules.roman(proto.tier) + ": a crew of " + proto.skill + " " + proto.min
                        + " led by a researcher (" + c.science + " " + c.gate + "), then the test", x -> pickProto(x, c)));
            if (pj != null && !pj.eureka && pj.inspiration <= 0 && Research.perk(pj, Research.researchers(pj, wf), "Eureka"))
                b.add(btn("Eureka: the inspiration now", "once a project, worth 10 % more (round 3)", x -> { say(x, Research.eureka(pr, c, wf)); pr.save(); card(x, c.id); }));
        }
        if (gm(p) && !Research.done(pr, c)) {
            b.add(btn("GM: a find", "a working item, its manual, a schematic, a pre-war lab's records (30 %), a related thing (10 %); Reverse Engineer 50 % and the inspiration", x -> gmFind(x, c)));
            b.add(btn("GM: the achievement", (c.achievement == null ? "its condition met" : c.achievement.text) + " (+" + Math.round(Research.RULES.achievement)
                    + " %)" + (c.achievement != null && c.achievement.counted() ? "; the plugin counts it too" : ""), x -> { say(x, Research.achievement(pr, c)); pr.save(); card(x, c.id); }));
            b.add(btn("GM: an inspiration", "an event he links, a link the player argues, a find used for it (+10 %, Absent-Minded Genius 20 %)", x -> { say(x, Research.inspiration(pr, c, wf)); pr.save(); card(x, c.id); }));
            b.add(btn("GM: the base knows it now", "the way unlocked without the rest (a found schematic)", x -> {
                Research.project(pr, c).status = "done";
                pr.known.addAll(c.recipes);
                pr.save();
                say(x, c.name + ": known now");
                card(x, c.id);
            }));
        }
        menu(p, "Research: " + c.name, lines, List.of(), b, Labs::main, 1);
    }

    /** The building running this card's job of this kind, or null. */
    static Production.Building running(Research.Card c, String what) {
        for (Production.Building b : pr().buildings) if (b.job != null && b.job.recipe.equals("research|" + c.id + "|" + what)) return b;
        return null;
    }

    static int count(Research.Card c, String what) {
        int n = 0;
        for (Production.Building b : pr().buildings) if (b.job != null && b.job.recipe.equals("research|" + c.id + "|" + what)) n++;
        return n;
    }

    static void pickLab(Player p, Research.Card c, String what) {
        Production.Recipe r = Research.jobFor(c, what);
        List<String> ids = new ArrayList<>();
        for (Production.Building b : pr().buildings) if (b.job == null && b.condition > 0 && Production.fits(b, r)) ids.add(b.id);
        if (ids.isEmpty()) { say(p, "! no free laboratory of Tier " + Rules.roman(r.tier) + " or better"); card(p, c.id); return; }
        pick(p, r.name, "A free laboratory of Tier " + Rules.roman(r.tier) + " or better.", ids, id -> pr().building(id).name + " (Tier " + Rules.roman(pr().building(id).tier) + ")",
                id -> pr().building(id).label(), (x, id) -> {
                    String m = pr().start(pr().building(id), r, "theory".equals(what) ? 0 : 1, 1, pl.workforce());
                    if (!m.startsWith("!")) Research.project(pr(), c).status = "theory";
                    say(x, m);
                    pr().save();
                    card(x, c.id);
                }, x -> card(x, c.id));
    }

    static void pickProto(Player p, Research.Card c) {
        Production.Recipe r = Research.jobFor(c, "prototype");
        if (Production.SITES.contains(r.building)) {
            Production.Building s = pr().startAt(r.building, r, 1, 1, pl.workforce());
            say(p, s == null ? "! nobody free to lead it (" + c.science + " " + c.gate + ") or to build it (" + r.skill + " " + r.min + ")"
                    : s.name + ": " + r.name + " × 1; " + pr().status(s));
            pr().save();
            card(p, c.id);
            return;
        }
        List<String> ids = new ArrayList<>();
        for (Production.Building b : pr().buildings) if (b.job == null && b.condition > 0 && Production.fits(b, r)) ids.add(b.id);
        if (ids.isEmpty()) { say(p, "! no free " + r.building + " of Tier " + Rules.roman(r.tier) + " or better"); card(p, c.id); return; }
        pick(p, r.name, "A free " + r.building + " of Tier " + Rules.roman(r.tier) + " or better; the best free researcher leads it (" + c.science + " " + c.gate
                + ", no slot), the way's own crew fills the slots (" + r.skill + " " + r.min + ").", ids,
                id -> pr().building(id).name, id -> pr().building(id).label(), (x, id) -> {
                    say(x, pr().start(pr().building(id), r, 1, 1, pl.workforce()));
                    pr().save();
                    card(x, c.id);
                }, x -> card(x, c.id));
    }

    // ------------------------------------------------------------------ the GM
    static void gmFind(Player p, Research.Card c) {
        ask(p, "A find for " + c.name, "The GM names the share: a working item of the kind, its manual, a schematic, a pre-war lab's records 30 %, a "
                        + "related thing 10 %; at most 50 % a project. Reverse Engineer: a working item taken apart = 50 % and a free inspiration. "
                        + "Data Miner: a database read is a find for every project it touches (credit each).",
                List.of(Ui.In.numberRange("s", Component.text("Share (%)"), 0, 50).step(5f).initial(30f).width(300).build(),
                        Ui.In.bool("re", Component.text("Taken apart by a Reverse Engineer (50 % and the inspiration)")).initial(false).build()),
                form("Credit", "the share goes to the card", (v, x) -> {
                    say(x, Research.find(pr(), c, v.getFloat("s"), Boolean.TRUE.equals(v.getBoolean("re")), pl.workforce()));
                    pr().save();
                    card(x, c.id);
                }), x -> card(x, c.id));
    }

    static void pending(Player p) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < pr().pending.size(); i++) ids.add(String.valueOf(i));
        if (ids.isEmpty()) { main(p); return; }
        pick(p, "Failed prototypes", "Rarity 1 failed its test: the GM rules what it comes out as (round 2: \"I would rule each on case by case basis\").", ids,
                i -> pr().pending.get(Integer.parseInt(i)).text, i -> pr().pending.get(Integer.parseInt(i)).text, (x, i) -> rule(x, pr().pending.get(Integer.parseInt(i))), Labs::main);
    }

    static void rule(Player p, Research.Pending pd) {
        int tier = pd.builds != null ? pd.builds.tier : pd.makes.isEmpty() ? 1 : pd.makes.get(0).tier;
        ask(p, "Rule: " + pd.text, "It comes out at the tier and rarity you choose, or nothing comes of it.",
                List.of(Ui.In.numberRange("t", Component.text("Tier"), 1, 4).step(1f).initial((float) tier).width(300).build(),
                        Ui.In.numberRange("r", Component.text("Rarity"), 1, 4).step(1f).initial(1f).width(300).build(),
                        Ui.In.bool("no", Component.text("Nothing comes of it")).initial(false).build()),
                form("Rule", "into the store (or the building built)", (v, x) -> {
                    say(x, Research.rule(pr(), pl.base, pd, Math.round(v.getFloat("t")), Math.round(v.getFloat("r")), Boolean.TRUE.equals(v.getBoolean("no"))));
                    pr().save();
                    pending(x);
                }), Labs::pending);
    }
}
