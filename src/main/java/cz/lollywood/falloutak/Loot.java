package cz.lollywood.falloutak;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static cz.lollywood.falloutak.Menus.btn;
import static cz.lollywood.falloutak.Menus.line;
import static cz.lollywood.falloutak.Menus.say;
import static cz.lollywood.falloutak.Menus.tellGm;

/**
 * THE FIELD AFTER A FIGHT (`09d` round 4, Marek 11-24: *"in case of battle map, I think it would be best to allow to loot every
 * killed/downed creature. Survival could be used to do butchering here, Medical to do healing or extractions (genes, replacement organs
 * etc)."* · *"there should be other resources"*). Every body the fight left (dead, or down and dying) on the field the party held, each
 * once, from the Travel menu after the fight [every form and number here mine, EXAMPLE]:
 * <ul>
 *   <li><b>search</b> a person: his gun at the condition it ended with, the rounds he had left;</li>
 *   <li><b>butcher</b> a creature: a Survival check; done well its meat (0.5 kg a portion), fat (15 % of the meat), hides and sinew,
 *   done badly half the meat and fat and the hide spoiled; Hunter doubles it (02c); 20 minutes + 1 a kg;</li>
 *   <li><b>treat</b> a dying one: a Medicine check; it lives, stabilised (a captive: the GM's to question, recruit as a new group or set
 *   free), else it dies under the hands; or <b>finish it off</b>;</li>
 *   <li><b>a tissue sample</b> (a Medicine check, 15 minutes): the creature's genes for the base's research (gene research is parked);
 *   <b>a donor organ</b> from a person (Medicine −20, an hour): a fresh kidney in a cold box with his blood group (a creature's organ
 *   is rejected within hours: only a gene-edited pig's works, `03e` §4 T I; its use waits for Marek's ruling on transplants).</li>
 * </ul>
 * A passed check pays its difficulty, at least 10 (06b §2). The field is left when the party sets out again.
 */
final class Loot {
    private Loot() {}

    static final Set<String> PEOPLE = Set.of("raider", "patrol", "trader");
    static final double FAT_SHARE = 0.15;
    static final int ORGAN_PENALTY = -20, SEARCH_ROUNDS_MAX = 24;

    /** One body on the field. */
    static final class Body {
        final String name, foe;
        final boolean person;
        boolean dead;
        final double meatKg;
        final int hides, hpMax;
        /** His gun's condition in % (0 = none or destroyed) and the rounds he had. */
        final double gunCond;
        final int rounds;
        boolean searched, butchered, treated, sampled, organ;
        /** A person's blood group (the donor organ's match), drawn by Central Europe's shares [EXAMPLE]. */
        final String blood;

        Body(Battle.Target t, Random rnd) {
            name = t.name; foe = t.foe; person = PEOPLE.contains(t.foe); dead = t.dead; meatKg = t.meatKg; hides = t.hides; hpMax = t.hpMax;
            gunCond = person && !t.broken && !t.melee ? Math.max(1, 100 * t.gunHealth / Battle.SMG.maxHealth) : 0;
            rounds = person && !t.melee ? Math.max(0, t.ammo) + rnd.nextInt(SEARCH_ROUNDS_MAX + 1) : 0;
            blood = person ? bloodGroup(rnd) : "";
        }

        boolean creature() { return !person; }

        String state() {
            List<String> l = new ArrayList<>();
            l.add(dead ? "dead" : treated ? "stabilised" : "dying");
            if (searched) l.add("searched");
            if (butchered) l.add("butchered");
            if (sampled) l.add("sampled");
            if (organ) l.add("organ taken");
            return String.join(", ", l);
        }
    }

    /** Central Europe's blood groups by share (A+ 36, O+ 31, B+ 14, AB+ 6, A− 6, O− 5, B− 1.5, AB− 0.5) [inf, EXAMPLE]. */
    static String bloodGroup(Random rnd) {
        String[] g = {"A+", "O+", "B+", "AB+", "A-", "O-", "B-", "AB-"};
        double[] w = {36, 31, 14, 6, 6, 5, 1.5, 0.5};
        double u = rnd.nextDouble() * 100, acc = 0;
        for (int k = 0; k < g.length; k++) { acc += w[k]; if (u < acc) return g[k]; }
        return "A+";
    }

    static final List<Body> bodies = new ArrayList<>();
    /** The checks' dice (a test may fix them). */
    static Random dice;

    static Random rnd(FalloutakPlugin pl) { return dice != null ? dice : pl.rnd; }

    /** The fight's bodies: every figure dead or dying on the field (not one that got away). */
    static int collect(Battle.Fight f, Random rnd) {
        bodies.clear();
        for (Battle.Target t : f.targets.values()) if (!t.escaped && (t.dead || t.dying())) bodies.add(new Body(t, rnd));
        return bodies.size();
    }

    static void clear() { bodies.clear(); }

    static int chance(Character c, String skill, int bonus) { return Math.max(5, Math.min(95, (int) Math.round(c.skill(skill)) + bonus)); }

    // ------------------------------------------------------------------ the menus
    static void menu(FalloutakPlugin pl, Player p) {
        List<Component> lines = new ArrayList<>();
        List<Ui.Btn> b = new ArrayList<>();
        lines.add(line("The field after the fight: " + bodies.size() + " bod" + (bodies.size() == 1 ? "y" : "ies") + ". Each can be searched or butchered, "
                + "treated or sampled once; the time it takes is yours.", NamedTextColor.GOLD));
        for (Body x : bodies) b.add(btn(x.name + " (" + x.state() + ")", x.person ? "a person: search, treat, samples" : "a creature: butcher, treat, samples",
                q -> body(pl, q, x)));
        b.add(btn("Leave the field", "the bodies stay behind", q -> { clear(); say(q, "You leave the field."); Travel.menu(pl, q); }));
        Menus.menu(p, "The field", lines, b, q -> Travel.menu(pl, q));
    }

    static void body(FalloutakPlugin pl, Player p, Body x) {
        Character c = pl.character(p);
        List<Component> lines = new ArrayList<>();
        List<Ui.Btn> b = new ArrayList<>();
        lines.add(line(x.name + ": " + x.state() + ".", NamedTextColor.GOLD));
        if (x.person && !x.searched)
            b.add(btn("Search him", "his gun and what rounds he had left", q -> { say(q, search(pl, q, x)); body(pl, q, x); }));
        if (x.creature() && !x.butchered && x.meatKg > 0)
            b.add(btn("Butcher it (Survival " + chance(c, "Survival", 0) + " %)", Math.round(20 + x.meatKg) + " minutes: meat, fat, hides, sinew; done badly half and no hide",
                    q -> { say(q, butcher(pl, q, x)); body(pl, q, x); }));
        if (!x.dead && !x.treated) {
            b.add(btn("Treat " + (x.person ? "him" : "it") + " (Medicine " + chance(c, "Medicine", 0) + " %)", "stabilised it lives (a captive: the GM's); failed it dies",
                    q -> { say(q, treat(pl, q, x)); body(pl, q, x); }));
            b.add(btn("Finish " + (x.person ? "him" : "it") + " off", "dead", q -> { x.dead = true; say(q, x.name + " is dead."); body(pl, q, x); }));
        }
        if (!x.sampled)
            b.add(btn("Take a tissue sample (Medicine " + chance(c, "Medicine", 0) + " %)", "15 minutes: its genes for the base's research",
                    q -> { say(q, sample(pl, q, x)); body(pl, q, x); }));
        if (x.person && !x.organ && !x.treated)
            b.add(btn("Take a donor organ (Medicine " + chance(c, "Medicine", ORGAN_PENALTY) + " %)", "an hour: a kidney in a cold box, blood group " + x.blood + "; failed, it is ruined",
                    q -> { say(q, organ(pl, q, x)); body(pl, q, x); }));
        b.add(btn("Back to the field", bodies.size() + " bodies", q -> menu(pl, q)));
        Menus.menu(p, "The field: " + x.name, lines, b, q -> menu(pl, q));
    }

    // ------------------------------------------------------------------ the actions
    /** The minutes pass for the one who works (part 4's survival runs). */
    private static String spend(Character c, double minutes) {
        String r = Hud.pass(c, minutes);
        return r == null ? "" : " " + r;
    }

    private static void give(Player p, List<ItemStack> l) {
        for (ItemStack it : l) for (ItemStack rest : p.getInventory().addItem(it).values()) p.getWorld().dropItem(p.getLocation(), rest);
    }

    static String search(FalloutakPlugin pl, Player p, Body x) {
        if (!x.person || x.searched) return "! nothing to search";
        x.searched = true;
        List<ItemStack> got = new ArrayList<>();
        Production.Design gun = Production.design("Pistols", Battle.SMG.name), rounds = Production.design("Pistol rounds", "Remanufactured pistol rounds");
        if (x.gunCond > 0 && gun != null) got.add(Items.made(gun, 1, x.gunCond));
        if (x.rounds > 0 && rounds != null) for (int n = x.rounds; n > 0; n -= 64) got.add(Items.made(rounds, Math.min(64, n), 100));
        give(p, got);
        return got.isEmpty() ? "Nothing on " + x.name + " worth taking." : "On " + x.name + ": " + (x.gunCond > 0 ? "his " + Battle.SMG.name + " (" + Math.round(x.gunCond) + " %)" : "no gun")
                + (x.rounds > 0 ? ", " + x.rounds + " rounds" : "") + ".";
    }

    static String butcher(FalloutakPlugin pl, Player p, Body x) {
        if (!x.creature() || x.butchered || x.meatKg <= 0) return "! nothing to butcher";
        x.butchered = true;
        x.dead = true;
        Character c = pl.character(p);
        int ch = chance(c, "Survival", 0), d = rnd(pl).nextInt(100) + 1;
        boolean ok = d <= ch;
        double k = (ok ? 1 : 0.5) * (c.hasPerk("Hunter") ? 2 : 1);
        int meat = (int) Math.round(x.meatKg * 2 * k), fat = (int) Math.round(x.meatKg * FAT_SHARE * 2 * k);
        int hides = ok ? (int) Math.round(x.hides * (c.hasPerk("Hunter") ? 2 : 1)) : 0, sinew = ok && x.hides > 0 ? Math.max(1, hides) : 0;
        List<ItemStack> got = new ArrayList<>();
        if (meat > 0) got.addAll(Arena.stacks(Items.storeId("Food", 1, 1, "Raw meat"), meat));
        if (fat > 0) got.addAll(Arena.stacks(Items.storeId("Food", 1, 1, "Raw fat"), fat));
        if (hides > 0) got.addAll(Arena.stacks(Items.storeId("Textile", 1, 1, "Raw hide"), hides));
        if (sinew > 0) got.addAll(Arena.stacks(Items.storeId("Textile", 1, 1, "Sinew"), sinew));
        give(p, got);
        String xp = ok ? " " + pl.roster.award(p, 10, "butchered " + x.name + " (the Survival check)") : "";
        return "You butcher " + x.name + " (Survival " + ch + " %, rolled " + d + (ok ? ": well" : ": badly, half of it lost and the hide spoiled") + "): "
                + Arena.spoilsText(got) + "." + xp + spend(c, 20 + x.meatKg);
    }

    static String treat(FalloutakPlugin pl, Player p, Body x) {
        if (x.dead || x.treated) return "! nothing to treat";
        x.treated = true;
        Character c = pl.character(p);
        int ch = chance(c, "Medicine", 0), d = rnd(pl).nextInt(100) + 1;
        if (d > ch) { x.dead = true; return "You try to keep " + x.name + " alive (Medicine " + ch + " %, rolled " + d + "): " + (x.person ? "he dies" : "it dies") + " under your hands." + spend(c, 20); }
        tellGm("the field: " + p.getName() + " stabilised " + x.name + " (" + x.foe + "): a captive, yours to question, recruit as a new group or set free");
        return "You stabilise " + x.name + " (Medicine " + ch + " %, rolled " + d + "): " + (x.person ? "he lives, a captive" : "it lives") + "; the GM decides what comes of it. "
                + pl.roster.award(p, 10, "a wounded enemy stabilised (the Medicine check)") + spend(c, 20);
    }

    static String sample(FalloutakPlugin pl, Player p, Body x) {
        if (x.sampled) return "! sampled already";
        x.sampled = true;
        Character c = pl.character(p);
        int ch = chance(c, "Medicine", 0), d = rnd(pl).nextInt(100) + 1;
        if (d > ch) return "The sample from " + x.name + " is spoiled (Medicine " + ch + " %, rolled " + d + ")." + spend(c, 15);
        give(p, List.of(Items.make(bodyId("Tissue sample", x), 1)));
        return "A tissue sample from " + x.name + " (Medicine " + ch + " %, rolled " + d + "): its genes, for the base's research. "
                + pl.roster.award(p, 10, "a tissue sample (the Medicine check)") + spend(c, 15);
    }

    static String organ(FalloutakPlugin pl, Player p, Body x) {
        if (!x.person || x.organ || x.treated) return "! no organ to take (a creature's is rejected within hours)";
        x.organ = true;
        x.dead = true;
        Character c = pl.character(p);
        int ch = chance(c, "Medicine", ORGAN_PENALTY), d = rnd(pl).nextInt(100) + 1;
        if (d > ch) return "The organ from " + x.name + " is ruined (Medicine " + ORGAN_PENALTY + ": " + ch + " %, rolled " + d + ")." + spend(c, 60);
        give(p, List.of(Items.make("body:Donor kidney|" + x.blood, 1)));
        return "A donor organ from " + x.name + ": a kidney, blood group " + x.blood + ", in a cold box (Medicine " + ORGAN_PENALTY + ": " + ch + " %, rolled " + d + "). "
                + pl.roster.award(p, -ORGAN_PENALTY, "a donor organ (the Medicine check)") + spend(c, 60);
    }

    /** A body's part as an item: body:<what>|<whose> (not a store row: no recipe takes it as an input). */
    static String bodyId(String what, Body x) {
        String whose = x.name.replaceAll(" \\d+$", "");
        return "body:" + what + "|" + whose;
    }
}
