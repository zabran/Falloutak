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
 * etc)."*; round 5: *"How much you get depends on skill and higher skill may give you harder to get resources. Players may also select
 * what to priorize (if some extraction may damage other parts)."*). Every body the fight left (dead, or down and dying) on the field the
 * party held, each once, from the Travel menu after the fight. EVERY CHECK IS A COMPARISON, NEVER A ROLL (02c, RULED 11-14): the skill
 * against the need [every form and number here mine, EXAMPLE]:
 * <ul>
 *   <li><b>search</b> a person: his weapon (the pistol, the bow, the machete he fought with) at its condition, the rounds or arrows he
 *   had left (they hoard them, 09d round 5);</li>
 *   <li><b>butcher</b> a creature by Survival, with a PRIORITY ({@link Focus}): the meat share grows with the skill (half at 0, all at
 *   100), fat 15 % of the meat; the hide, the sinew, the bones and the kind's SPECIAL PARTS (the Radmie's venom, the Trhač's pollen, the
 *   spider's silk, the Ropák's oil, claws and tusks) each need a level; the priority's own parts need {@value #FOCUS} less, the others
 *   10 more, and a FRAGILE part is ruined unless it was put first (parts first costs a tenth of the meat); Hunter doubles the yield
 *   (02c); 20 minutes + 1 a kg;</li>
 *   <li><b>treat</b> a dying one: Medicine against 30 + 20 x how deep he is below 0 HP (to −Endurance); it lives, stabilised (a captive:
 *   the GM's), else it dies under the hands; or <b>finish it off</b>;</li>
 *   <li><b>a tissue sample</b> (Medicine 20, 15 minutes): its genes for the base's research (gene research is parked);</li>
 *   <li><b>donor organs</b> from a person ({@link #ORGANS}; 09e §4.2, RULED round 5: *"Donor organs as a T II - yes"*): the kidneys,
 *   the liver, the heart, the lungs, the corneas, skin, each its Medicine level and the hours it keeps cold, with his blood group; taking
 *   the heart or the lungs ends a dying one. A creature's organ is rejected within hours (only the gene-edited pig line's works).</li>
 * </ul>
 * A passed check pays its difficulty, at least 10 (06b §2): the hardest part taken. The field is left when the party sets out again.
 */
final class Loot {
    private Loot() {}

    static final Set<String> PEOPLE = Set.of("raider", "raider-bow", "raider-melee", "patrol", "trader");
    static final double FAT_SHARE = 0.15;
    /** How much less the priority's own parts need, and how much more the others do [mine, EXAMPLE]. */
    static final int FOCUS = 15, OFF_FOCUS = 10;
    static final int HIDE_NEED = 25, SINEW_NEED = 20, BONES_NEED = 15, SAMPLE_NEED = 20;

    /** The butcher's priority (09d round 5): the meat and fat, the hide and sinew, or the parts (bones, glands, claws). */
    enum Focus { MEAT, HIDE, PARTS }

    /** A donor organ: its name, the Medicine it needs, how many, how long it keeps cold, whether taking it ends a living donor [real hours]. */
    record Organ(String name, int need, int count, String keeps, boolean ends) {}

    static final List<Organ> ORGANS = List.of(
            new Organ("kidney", 40, 2, "24-36 hours", false),
            new Organ("liver", 60, 1, "12 hours", false),
            new Organ("heart", 70, 1, "4-6 hours", true),
            new Organ("lungs", 70, 1, "6-8 hours", true),
            new Organ("corneas", 30, 2, "a week (no drugs needed)", false),
            new Organ("skin", 30, 1, "weeks, dried (no drugs needed)", false),
            new Organ("arm", 50, 2, "6 hours, cold", false));   // 09e §6: a donor's limb, a T II graft (RULED round 2)

    /** One body on the field. */
    static final class Body {
        final String name, foe;
        final boolean person;
        boolean dead;
        final double meatKg;
        final int hides, hpMax, hp, endurance;
        /** His weapon (its name, condition in %, 0 = none or destroyed) and the rounds or arrows he had (09d round 5). */
        final String weapon;
        final boolean bow;
        final double gunCond;
        final int rounds;
        boolean searched, butchered, treated, sampled;
        /** 05d round 7, the dart: asleep (alive, taken unhurt), or it ran off the map with the dose in it (a TRAIL: followed by Survival
         *  it is found asleep, else lost); taken alive. */
        boolean asleep, trail, lost, taken;
        /** Out of the fight at its end and alive (knocked out, in the net, his gun gone): surrendered (Marek 11-27), taken alive or finished. */
        boolean surrendered;
        final Set<String> organs = new java.util.HashSet<>();
        /** A person's blood group (the donor organ's match), drawn by Central Europe's shares [EXAMPLE]. */
        final String blood;

        Body(Battle.Target t, Random rnd) {
            name = t.name; foe = t.foe; person = PEOPLE.contains(t.foe); dead = t.dead; meatKg = t.meatKg; hides = t.hides; hpMax = t.hpMax;
            hp = t.hp; endurance = Math.max(1, t.a[Rules.E]);
            weapon = person ? t.weaponName : "";
            bow = t.gun != null && t.gun.skill.equals("Bows") && !t.weaponName.equals(Battle.KNIFE.name) && t.weaponName.equals(t.gun.name);
            boolean ranged = t.gun != null && t.weaponName.equals(t.gun.name);
            gunCond = !person || t.broken ? 0 : ranged ? Math.max(1, 100 * t.gunHealth / t.gun.maxHealth) : 30 + rnd.nextInt(41);
            rounds = person && ranged ? Math.max(0, t.ammo) + Math.max(0, Math.min(99, t.reserve)) : 0;
            blood = person ? bloodGroup(rnd) : "";
            asleep = !t.dead && t.asleep;
            surrendered = !t.dead && !t.escaped && !t.asleep && !t.dying() && t.outOfIt();
            trail = t.escaped && !t.dead && t.sedatedAt >= 0;
        }

        boolean creature() { return !person; }

        String state() {
            List<String> l = new ArrayList<>();
            if (trail && !asleep && !dead) return lost ? "the trail lost" : "ran off with your dart in " + (person ? "him" : "it") + ": a trail to follow";
            l.add(dead ? "dead" : taken ? "taken alive" : asleep ? "asleep" : surrendered ? "surrendered" : treated ? "stabilised" : "dying");
            if (searched) l.add("searched");
            if (butchered) l.add("butchered");
            if (sampled) l.add("sampled");
            if (!organs.isEmpty()) l.add("organs taken: " + String.join(", ", organs));
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
    /** The fight's party (the kill XP of one found asleep after it). */
    static final List<java.util.UUID> team = new ArrayList<>();
    /** Following a darted one's trail [mine, EXAMPLE: the dose drops it within ~3 minutes, a few hundred metres off]: Survival, 20 min. */
    static final int TRAIL_NEED = 30;

    /** The fight's bodies: every figure dead or dying on the field (not one that got away, nor an ally of convenience still standing). */
    static int collect(Battle.Fight f, Random rnd) {
        bodies.clear();
        team.clear();
        team.addAll(f.members);
        for (Battle.Target t : f.targets.values())
            if (!t.escaped && (t.dead || t.dying() || t.asleep || t.outOfIt()) || t.escaped && !t.dead && t.sedatedAt >= 0) bodies.add(new Body(t, rnd));
        return bodies.size();
    }

    static void clear() { bodies.clear(); }

    /** A level the skill reaches (the comparison of 02c's ruled skill check). */
    static boolean reaches(Character c, String skill, int need) { return c.skill(skill) >= need; }

    /** A part's need under a priority: its own {@value #FOCUS} less, another's {@value #OFF_FOCUS} more, none as it is. */
    static int need(int base, Focus own, Focus focus) { return focus == null ? base : focus == own ? base - FOCUS : base + OFF_FOCUS; }

    /** A creature's special parts (`data/arena-foes.json`). */
    static List<Arena.Part> parts(Body x) {
        Arena.Foe f = Arena.FOES.get(x.foe);
        return f == null ? List.of() : f.parts();
    }

    // ------------------------------------------------------------------ the menus
    static void menu(FalloutakPlugin pl, Player p) {
        List<Component> lines = new ArrayList<>();
        List<Ui.Btn> b = new ArrayList<>();
        lines.add(line("The field after the fight: " + bodies.size() + " bod" + (bodies.size() == 1 ? "y" : "ies") + ". Each can be searched or butchered, "
                + "treated or sampled once; the time it takes is yours. Your skill decides how much you get, and what.", NamedTextColor.GOLD));
        for (Body x : bodies) b.add(btn(x.name + " (" + x.state() + ")", x.person ? "a person: search, treat, samples, organs" : "a creature: butcher, treat, samples",
                q -> body(pl, q, x)));
        b.add(btn("Leave the field", "the bodies stay behind", q -> { clear(); say(q, "You leave the field."); Travel.menu(pl, q); }));
        Menus.menu(p, "The field", lines, b, q -> Travel.menu(pl, q));
    }

    static void body(FalloutakPlugin pl, Player p, Body x) {
        Character c = pl.character(p);
        List<Component> lines = new ArrayList<>();
        List<Ui.Btn> b = new ArrayList<>();
        lines.add(line(x.name + ": " + x.state() + (x.person ? "; blood group " + x.blood : "") + ".", NamedTextColor.GOLD));
        if (x.trail && !x.lost && !x.asleep) {   // first found: the dart's dose drops it a few minutes off
            b.add(btn("Follow the trail (Survival " + TRAIL_NEED + ")", "your Survival " + Math.round(c.skill("Survival")) + ", 20 minutes: found asleep, or the trail is lost",
                    q -> { say(q, track(pl, q, x)); body(pl, q, x); }));
        }
        if (x.trail && !x.asleep || x.taken) {
            b.add(btn("Back to the field", bodies.size() + " bodies", q -> menu(pl, q)));
            Menus.menu(p, "The field: " + x.name, lines, b, q -> menu(pl, q));
            return;
        }
        if ((x.asleep || x.surrendered) && !x.dead)
            b.add(btn("Take " + (x.person ? "him" : "it") + " alive (bound)", (x.person ? "a captive" : "for the pens or the lab") + ": the GM places " + (x.person ? "him" : "it"),
                    q -> { say(q, takeAlive(pl, q, x)); body(pl, q, x); }));
        if (x.person && !x.searched)
            b.add(btn("Search him", "his " + (x.weapon.isEmpty() ? "weapon" : x.weapon) + " and what he had left", q -> { say(q, search(pl, q, x)); body(pl, q, x); }));
        if (x.creature() && !x.butchered && (x.meatKg > 0 || !parts(x).isEmpty())) {
            lines.add(line("Butchering (your Survival " + Math.round(c.skill("Survival")) + "): " + butcherPreview(c, x), NamedTextColor.GRAY));
            b.add(btn("Butcher it: meat first", "more meat, quicker; the hide and the parts need " + OFF_FOCUS + " more, a fragile part is ruined",
                    q -> { say(q, butcher(pl, q, x, Focus.MEAT)); body(pl, q, x); }));
            b.add(btn("Butcher it: hide first", "skinned slowly (+20 min): the hide and sinew need " + FOCUS + " less", q -> { say(q, butcher(pl, q, x, Focus.HIDE)); body(pl, q, x); }));
            b.add(btn("Butcher it: parts first", "the bones, glands, claws need " + FOCUS + " less (+15 min); a tenth of the meat is lost round them",
                    q -> { say(q, butcher(pl, q, x, Focus.PARTS)); body(pl, q, x); }));
        }
        if (!x.dead && !x.treated && x.hp <= 0) {
            b.add(btn("Treat " + (x.person ? "him" : "it") + " (Medicine " + treatNeed(x) + ")", "your Medicine " + Math.round(c.skill("Medicine"))
                    + (reaches(c, "Medicine", treatNeed(x)) ? ": you can keep " + (x.person ? "him" : "it") + " alive (a captive: the GM's)" : ": beyond you; " + (x.person ? "he" : "it") + " would die"),
                    q -> { say(q, treat(pl, q, x)); body(pl, q, x); }));
        }
        if (!x.dead && !x.treated)
            b.add(btn("Finish " + (x.person ? "him" : "it") + " off", "dead", q -> { x.dead = true; say(q, x.name + " is dead."); body(pl, q, x); }));
        if (!x.sampled)
            b.add(btn("Take a tissue sample (Medicine " + SAMPLE_NEED + ")", "15 minutes: its genes for the base's research", q -> { say(q, sample(pl, q, x)); body(pl, q, x); }));
        if (x.person && !x.treated) {
            List<String> beyond = new ArrayList<>();
            for (Organ o : ORGANS) {
                if (x.organs.contains(o.name())) continue;
                if (!reaches(c, "Medicine", o.need())) { beyond.add(o.name() + " " + o.need()); continue; }
                b.add(btn("Take the " + o.name() + " (Medicine " + o.need() + ")", "keeps " + o.keeps() + ", blood group " + x.blood + (o.ends() && !x.dead ? "; it ends him" : ""),
                        q -> { say(q, organ(pl, q, x, o.name())); body(pl, q, x); }));
            }
            if (!beyond.isEmpty()) lines.add(line("Organs beyond your Medicine: " + String.join(", ", beyond) + ".", NamedTextColor.GRAY));
        }
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

    /** The first design of this name (the weapons and arrows a raider carries). */
    static Production.Design named(String name) {
        for (Production.Design d : Production.DESIGNS.values()) if (d.name.equals(name)) return d;
        return null;
    }

    /** The rounds a raider's gun leaves (05d round 1: its calibre): the lowest-tier load of its category, his remanufactured ones. */
    static Production.Design roundsFor(String weapon) {
        Battle.Weapon w = Battle.GEAR.get(weapon);
        String kind = w != null && !w.ammoKind.isEmpty() ? w.ammoKind : "9x19 rounds";
        Production.Design best = null;
        for (Production.Design d : Production.DESIGNS.values())
            if (d.category.equals(kind) && (best == null || d.tier < best.tier)) best = d;
        return best;
    }

    static String search(FalloutakPlugin pl, Player p, Body x) {
        if (!x.person || x.searched) return "! nothing to search";
        x.searched = true;
        List<ItemStack> got = new ArrayList<>();
        Production.Design gun = x.weapon.isEmpty() ? null : named(x.weapon);
        Production.Design rounds = x.bow ? named("Metal-headed arrows") : roundsFor(x.weapon);
        if (x.gunCond > 0 && gun != null) got.add(Items.made(gun, 1, x.gunCond));
        if (x.rounds > 0 && rounds != null) for (int n = x.rounds; n > 0; n -= 64) got.add(Items.made(rounds, Math.min(64, n), 100));
        give(p, got);
        return got.isEmpty() ? "Nothing on " + x.name + " worth taking." : "On " + x.name + ": " + (x.gunCond > 0 && gun != null ? "his " + x.weapon + " (" + Math.round(x.gunCond) + " %)" : "no weapon")
                + (x.rounds > 0 ? ", " + x.rounds + (x.bow ? " arrows" : " rounds") : "") + ".";
    }

    /** What the butcher's Survival takes from this body, with no priority (the menu's preview). */
    static String butcherPreview(Character c, Body x) {
        List<String> l = new ArrayList<>();
        l.add(Math.round(100 * meatShare(c.skill("Survival"), null, false)) + " % of the meat");
        if (x.hides > 0) l.add("the hide " + (reaches(c, "Survival", HIDE_NEED) ? "yes" : "no (" + HIDE_NEED + ")"));
        for (Arena.Part pt : parts(x)) l.add(pt.name() + " " + (reaches(c, pt.skill(), pt.need()) ? "yes" : "no (" + pt.need() + ")") + (pt.fragile() ? ", fragile" : ""));
        return String.join("; ", l);
    }

    /** The meat share: half at Survival 0, all at 100; +10 points meat first, −10 parts first round a fragile part. */
    static double meatShare(double survival, Focus focus, boolean fragileParts) {
        double k = 0.5 + Math.max(0, Math.min(100, survival)) / 200 + (focus == Focus.MEAT ? 0.1 : 0) - (focus == Focus.PARTS && fragileParts ? 0.1 : 0);
        return Math.max(0.4, Math.min(1, k));
    }

    static String butcher(FalloutakPlugin pl, Player p, Body x) { return butcher(pl, p, x, null); }

    static String butcher(FalloutakPlugin pl, Player p, Body x, Focus focus) {
        if (!x.creature() || x.butchered || (x.meatKg <= 0 && parts(x).isEmpty())) return "! nothing to butcher";
        x.butchered = true;
        x.dead = true;
        Character c = pl.character(p);
        double sv = c.skill("Survival");
        int hunter = c.hasPerk("Hunter") ? 2 : 1;
        boolean fragile = false;
        for (Arena.Part pt : parts(x)) fragile |= pt.fragile();
        double share = meatShare(sv, focus, fragile);
        int meat = (int) Math.round(x.meatKg * 2 * share * hunter), fat = (int) Math.round(x.meatKg * FAT_SHARE * 2 * share * hunter);
        int hardest = 0;
        List<String> lost = new ArrayList<>();
        List<ItemStack> all = new ArrayList<>();
        if (meat > 0) all.addAll(Arena.stacks(Items.storeId("Food", 1, 1, "Raw meat"), meat));
        if (fat > 0) all.addAll(Arena.stacks(Items.storeId("Food", 1, 1, "Raw fat"), fat));
        if (x.hides > 0) {
            int hn = need(HIDE_NEED, Focus.HIDE, focus), sn = need(SINEW_NEED, Focus.HIDE, focus);
            if (sv >= hn) { all.addAll(Arena.stacks(Items.storeId("Textile", 1, 1, "Raw hide"), x.hides * hunter)); hardest = Math.max(hardest, hn); }
            else lost.add("the hide (Survival " + hn + ")");
            if (sv >= sn) { all.addAll(Arena.stacks(Items.storeId("Textile", 1, 1, "Sinew"), Math.max(1, x.hides * hunter))); hardest = Math.max(hardest, sn); }
        }
        if (x.meatKg > 0) {
            int bn = need(BONES_NEED, Focus.PARTS, focus);
            if (sv >= bn) { all.addAll(Arena.stacks(Items.storeId("Chemicals", 1, 1, "Bones"), Math.max(1, (int) Math.round(x.meatKg / 20)))); hardest = Math.max(hardest, bn); }
        }
        for (Arena.Part pt : parts(x)) {
            int n = need(pt.need(), Focus.PARTS, focus);
            double lv = c.skill(pt.skill());
            if (pt.fragile() && focus != Focus.PARTS && lv < pt.need() + 2 * OFF_FOCUS) { lost.add(pt.name() + " (ruptured: put it first, or " + pt.skill() + " " + (pt.need() + 2 * OFF_FOCUS) + ")"); continue; }
            if (lv < n) { lost.add(pt.name() + " (" + pt.skill() + " " + n + ")"); continue; }
            all.addAll(Arena.stacks(Items.storeId(pt.row(), pt.tier(), pt.rarity(), pt.name()), pt.count() * hunter));
            hardest = Math.max(hardest, n);
        }
        give(p, all);
        long xp = Math.max(10, hardest);
        String xpText = all.isEmpty() ? "" : " " + pl.roster.award(p, xp, "butchered " + x.name + " (the Survival check, difficulty " + xp + ")");
        double minutes = 20 + x.meatKg * (focus == Focus.MEAT ? 0.5 : 1) + (focus == Focus.HIDE ? 20 : focus == Focus.PARTS ? 15 : 0);
        return "You butcher " + x.name + (focus == null ? "" : " (" + focus.name().toLowerCase(java.util.Locale.ROOT) + " first)") + ", Survival " + Math.round(sv)
                + ": " + (all.isEmpty() ? "nothing usable" : Arena.spoilsText(all)) + "." + (lost.isEmpty() ? "" : " Lost: " + String.join(", ", lost) + ".") + xpText
                + spend(c, minutes);
    }

    /** Following the trail of one that ran off darted: Survival against {@value #TRAIL_NEED}; found asleep it counts as beaten (its kill XP). */
    static String track(FalloutakPlugin pl, Player p, Body x) {
        if (!x.trail || x.lost || x.asleep) return "! no trail to follow";
        Character c = pl.character(p);
        long sv = Math.round(c.skill("Survival"));
        if (sv < TRAIL_NEED) { x.lost = true; return "You lose " + x.name + "'s trail (Survival " + sv + " of " + TRAIL_NEED + "): it wakes somewhere and is gone." + spend(c, 20); }
        x.asleep = true;
        String xp = pl.roster.award(p, TRAIL_NEED, "a darted " + x.name + " tracked (the Survival check, difficulty " + TRAIL_NEED + ")");
        List<Player> on = new ArrayList<>();
        for (java.util.UUID u : team) { Player q = pl.getServer().getPlayer(u); if (q != null) on.add(q); }
        if (on.isEmpty()) on.add(p);
        String kill = pl.roster.killAward(on, List.of((double) x.hpMax), x.name + " found asleep");
        return "You follow " + x.name + "'s trail (Survival " + sv + " of " + TRAIL_NEED + ") and find " + (x.person ? "him" : "it") + " asleep under the dart's dose. "
                + xp + " Beaten: " + kill + spend(c, 20);
    }

    /** One asleep from the dart, bound and taken alive (the GM places a creature in the pens or the lab, a person as a captive). */
    static String takeAlive(FalloutakPlugin pl, Player p, Body x) {
        if (!(x.asleep || x.surrendered) || x.dead || x.taken) return "! nothing alive to take";
        x.taken = true;
        String how = x.asleep ? "asleep from a dart" : "surrendered";
        tellGm("the field: " + p.getName() + " took " + x.name + " (" + x.foe + ") alive, " + how + ": " + (x.person ? "a captive" : "for the pens or the lab"));
        return "You bind " + x.name + " and take " + (x.person ? "him" : "it") + " alive, " + (x.asleep ? "still asleep" : "surrendered") + ": " + (x.person ? "a captive" : "for the pens or the lab")
                + "; the GM places " + (x.person ? "him" : "it") + "." + spend(pl.character(p), 10);
    }

    /** Treating a dying one: 30 + 20 x how deep below 0 HP he is (to −Endurance) [mine, EXAMPLE]. */
    static int treatNeed(Body x) { return 30 + (int) Math.round(20 * Math.max(0, Math.min(1, -x.hp / (double) x.endurance))); }

    static String treat(FalloutakPlugin pl, Player p, Body x) {
        if (x.dead || x.treated) return "! nothing to treat";
        x.treated = true;
        Character c = pl.character(p);
        int need = treatNeed(x);
        long med = Math.round(c.skill("Medicine"));
        if (med < need) { x.dead = true; return "You try to keep " + x.name + " alive (Medicine " + med + " of " + need + "): " + (x.person ? "he dies" : "it dies") + " under your hands." + spend(c, 20); }
        tellGm("the field: " + p.getName() + " stabilised " + x.name + " (" + x.foe + "): a captive, yours to question, recruit as a new group or set free");
        return "You stabilise " + x.name + " (Medicine " + med + " of " + need + "): " + (x.person ? "he lives, a captive" : "it lives") + "; the GM decides what comes of it. "
                + pl.roster.award(p, need, "a wounded enemy stabilised (the Medicine check, difficulty " + need + ")") + spend(c, 20);
    }

    static String sample(FalloutakPlugin pl, Player p, Body x) {
        if (x.sampled) return "! sampled already";
        x.sampled = true;
        Character c = pl.character(p);
        long med = Math.round(c.skill("Medicine"));
        if (med < SAMPLE_NEED) return "The sample from " + x.name + " is spoiled (Medicine " + med + " of " + SAMPLE_NEED + ")." + spend(c, 15);
        give(p, List.of(Items.make(bodyId("Tissue sample", x), 1)));
        return "A tissue sample from " + x.name + " (Medicine " + med + " of " + SAMPLE_NEED + "): its genes, for the base's research. "
                + pl.roster.award(p, SAMPLE_NEED, "a tissue sample (the Medicine check)") + spend(c, 15);
    }

    /** The kidney (the round-4 call). */
    static String organ(FalloutakPlugin pl, Player p, Body x) { return organ(pl, p, x, "kidney"); }

    static String organ(FalloutakPlugin pl, Player p, Body x, String what) {
        Organ o = null;
        for (Organ k : ORGANS) if (k.name().equals(what)) o = k;
        if (o == null || !x.person || x.treated || x.organs.contains(what)) return "! no organ to take (a creature's is rejected within hours)";
        Character c = pl.character(p);
        long med = Math.round(c.skill("Medicine"));
        if (med < o.need()) return "! the " + o.name() + " is beyond you (Medicine " + med + " of " + o.need() + ")";
        x.organs.add(what);
        if (o.ends()) x.dead = true;
        String label = "Donor " + o.name();
        give(p, List.of(Items.make("body:" + label + "|" + x.blood, o.count())));
        return "From " + x.name + ": " + (o.count() > 1 ? o.count() + " × " : "the ") + o.name() + ", blood group " + x.blood + ", in a cold box: it keeps " + o.keeps()
                + " (Medicine " + med + " of " + o.need() + "). " + pl.roster.award(p, o.need(), "a donor " + o.name() + " (the Medicine check)") + spend(c, 60);
    }

    /** A body's part as an item: body:<what>|<whose> (not a store row: no recipe takes it as an input). */
    static String bodyId(String what, Body x) {
        String whose = x.name.replaceAll(" \\d+$", "");
        return "body:" + what + "|" + whose;
    }
}
