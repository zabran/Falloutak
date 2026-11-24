package cz.lollywood.falloutak;

import java.util.*;

/**
 * Part 3's commands (`_notes/Fallout/03b-SPECIES-RULES.md`, RULED 11-16): implants, surgery, revival from clinical death, chems,
 * the yearly mutation roll, prostheses and falls. Pure Java (each returns the text to show), so the tests run them; a line that
 * starts with "!" is shown in red.
 */
final class Part3 {
    private Part3() {}

    private static int arg(String[] a, int i, int dflt) {
        try { return a.length > i ? Integer.parseInt(a[i]) : dflt; } catch (NumberFormatException e) { return Integer.MIN_VALUE; }
    }

    private static double num(String[] a, int i, double dflt) {
        try { return a.length > i ? Double.parseDouble(a[i]) : dflt; } catch (NumberFormatException e) { return Double.NaN; }
    }

    static int tier(String s) {
        return switch (s == null ? "IV" : s.toUpperCase(Locale.ROOT)) { case "IV", "4" -> 4; case "III", "3" -> 3; case "II", "2" -> 2; case "I", "1" -> 1; default -> 0; };
    }

    /** The skill typed (any case, "computer_science"), or null. */
    static String skillName(String s) {
        if (s == null) return null;
        for (Rules.Skill k : Rules.SKILLS) if (k.name().replace(" ", "").equalsIgnoreCase(s.replace("_", "").replace("-", ""))) return k.name();
        return null;
    }

    // ------------------------------------------------------------------ /sheet implant
    /** /sheet implant · /sheet implant <name> [IV|III|II] [skill] · /sheet implant remove <slot or name> [Medicine]. */
    static String implant(Character c, String[] a) {
        if (a.length < 2) {
            StringBuilder sb = new StringBuilder("Implants (03b §3b, RULED 11-18; one a slot, brain = the Neural Link + one module; each tier its own numbers;"
                    + " T I today's prototype, T II today's mature device, T III-IV beyond today):");
            for (Data.Implant im : Data.IMPLANTS.values())
                sb.append("\n ").append(im.slot()).append(": ").append(im.name().replace(' ', '_')).append(" (").append(numbers(im)).append(im.notes().isEmpty() ? "" : "; " + im.notes()).append(")");
            sb.append("\n/sheet implant <name> [IV|III|II|I] fits one (no operation: /sheet surgery rolls it) · /sheet implant remove <slot> [Medicine]");
            return sb.toString();
        }
        if (a[1].equalsIgnoreCase("remove")) {
            if (a.length < 3) return "! /sheet implant remove <slot or name> [the surgeon's Medicine]";
            double med = num(a, 3, c.skill("Medicine"));
            Character.Effect e = c.removeImplant(a[2]);
            if (e == null) return "! no implant there: " + a[2];
            double loss = Rules.salvageLoss(med);
            return e.name() + " taken out at Medicine " + Math.round(med) + ": it loses " + Math.round(loss) + " % condition (max(0, 50 − Medicine / 3)), "
                    + Math.round(Math.max(0, e.condition() - loss)) + " % left. An operation: /sheet surgery's chance applies to the patient (03b §3-§4).";
        }
        Data.Implant im = Data.find(Data.IMPLANTS, a[1]);
        if (im == null) return "! no implant " + a[1] + " (/sheet implant lists them)";
        int t = a.length > 2 ? tier(a[2]) : im.maxTier();
        if (t == 0) return "! the tier: IV, III, II or I";
        String why = c.fit(im, t, skillName(a.length > 3 ? a[3] : null));
        if (why != null) return "! " + why;
        return im.name() + " fitted (Tier " + Rules.roman(t) + ", condition 100 %): " + numbersAt(im, t) + harm(c, t) + ".";
    }

    /** The harm of today's devices (T II an infection roll a month, T I twice; none for the Necroat, 03d §11). */
    static String harm(Character c, int tier) {
        if (tier > 2) return "";
        if (c.madeForChange()) return "; no infection rolls (the Necroat, 03d §11)";
        String half = c.immunityTherapy() ? ", its chance halved by the Immunity therapy (03d §10)" : "";
        return tier == 2 ? "; Tier II: an infection roll at the cut class once a month" + half : "; Tier I (a prototype): an infection roll at the cut class twice a month" + half;
    }

    /** A therapy's state in a few words (03d): its course, its time left, fading, twisted, pumped. */
    static String therapyState(Character.Therapy t) {
        String s = t.courseLeft > 0 ? "course " + SheetView.hours(t.courseLeft) : t.hours <= 0 ? "for good" : SheetView.hours(t.hoursLeft()) + " left";
        if (t.share() < 1) s += ", fading";
        if (!t.state.equals("on")) s += ", " + t.state;
        if (t.pumped) s += ", pumped";
        return s;
    }

    /** A therapy in one line: the gene, its tier and slot, its state and what covers what. */
    static String therapyText(Character c, Character.Therapy t) {
        Data.Gene g = t.g();
        if (g == null) return t.gene + " (no such gene)";
        Data.Gene own = c.templateGene(g.key());
        return g.name() + " (" + g.tier() + ", " + g.slot() + (t.set.isEmpty() ? "" : ", the " + t.set + " set") + "): " + therapyState(t)
                + (own == null ? "" : "; over the template's " + own.name())
                + (t.state.equals("twisted") ? "; only its drawback (radiation, 03d §5)" : t.state.equals("blend") ? "; its upside only (03d §4)" : "");
    }

    /** An implant's numbers at a tier (03b §3b: each tier its own), and the tier's text. */
    static String numbersAt(Data.Implant im, int tier) {
        Data.Implant.At at = im.at(tier);
        if (at == null) return "no Tier " + Rules.roman(tier);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++) { int v = at.mods()[i]; if (v != 0) sb.append(sb.isEmpty() ? "" : ", ").append(v > 0 ? "+" : "").append(v).append(" ").append(Rules.SPECIAL[i]); }
        for (Map.Entry<String, Double> e : at.other().entrySet()) {
            if (TEXT_ONLY.contains(e.getKey())) continue;
            double v = e.getValue();
            sb.append(sb.isEmpty() ? "" : ", ").append(v > 0 ? "+" : "").append(SheetGui.f(Math.round(v * 100) / 100.0)).append(" ").append(e.getKey().replace("skill:", "").replace('_', ' '));
        }
        if (!at.senses().isEmpty()) sb.append(sb.isEmpty() ? "" : ", ").append(at.senses());
        if (!at.notes().isEmpty()) sb.append(sb.isEmpty() ? "" : "; ").append(at.notes());
        return sb.isEmpty() ? "no numbers" : sb.toString();
    }

    /** Keys whose number the tier's text already says (a morale of 100 = never panics, the shocks, the window). */
    static final Set<String> TEXT_ONLY = Set.of("morale", "defib_shocks", "death_window", "addiction", "pain_gain", "skill_points_level", "rads_per_hour");

    /** All its tiers in one line: "T III: ... · T IV: ...". */
    static String numbers(Data.Implant im) {
        StringBuilder sb = new StringBuilder();
        for (int t : im.tiers().keySet()) sb.append(sb.isEmpty() ? "" : " · ").append("T ").append(Rules.roman(t)).append(": ").append(numbersAt(im, t));
        return sb.toString();
    }

    // ------------------------------------------------------------------ /sheet surgery
    /** /sheet surgery <implant> <Medicine> <field|clean|clinic|prewar> <none|improvised|tier3|tier4> [IV|III|II] [skill]: the roll, then the fitting. */
    static String surgery(Character c, String[] a, Random rnd) {
        if (a.length < 5)
            return "! /sheet surgery <implant> <the surgeon's Medicine> <field|clean|clinic|prewar> <none|improvised|tier3|tier4> [IV|III|II] [skill]"
                    + "\n! chance = min(98 %, Medicine × room × supplies × the operation) (03b §4, RimWorld's form)";
        Data.Implant im = Data.find(Data.IMPLANTS, a[1]);
        if (im == null) return "! no implant " + a[1];
        double med = num(a, 2, 0);
        Double room = Rules.ROOMS.get(a[3].toLowerCase(Locale.ROOT)), sup = Rules.SUPPLIES.get(a[4].toLowerCase(Locale.ROOT));
        if (Double.isNaN(med) || room == null || sup == null) return "! Medicine a number, the room field|clean|clinic|prewar, the supplies none|improvised|tier3|tier4";
        int t = a.length > 5 ? tier(a[5]) : im.maxTier();
        double op = Rules.operationFactor(im.slot());
        double chance = Rules.surgeryChance(med, room, sup, op);
        int d = 1 + rnd.nextInt(100);
        String head = "Surgery, " + im.name() + ": " + Math.round(med) + " × " + room + " × " + sup + " × " + op + " = " + SheetGui.f(Math.round(chance * 10) / 10.0)
                + " %, rolled " + d + ": ";
        String always = "\nAlways (part 4): the infection roll (a cut 2 %; cleaned with alcohol, none), the anaesthesia's own risk, Pain without it.";
        if (d <= chance) {
            String why = c.fit(im, t, skillName(a.length > 6 ? a[6] : null));
            return head + (why == null ? "SUCCESS, fitted." : "success, but: " + why) + always;
        }
        String kind = Rules.surgeryFailure(1 + rnd.nextInt(100), med);
        boolean vital = List.of("heart", "spine", "brain").contains(im.slot());
        return switch (kind) {
            case "minor" -> {
                int hp = 1 + rnd.nextInt(6);
                c.hp -= hp;
                c.survival.put(Rules.Stat.PAIN, Math.min(100, c.survival.getOrDefault(Rules.Stat.PAIN, 0.0) + 30));
                yield head + "FAILED, minor: not fitted, HP −" + hp + ", Pain +30; try again when healed." + always;
            }
            case "catastrophic" -> {
                int hp = 2 + rnd.nextInt(10) + rnd.nextInt(10);
                c.hp -= hp;
                yield "! " + head + "FAILED, CATASTROPHIC: the place damaged, HP −" + hp + ", the implant lost"
                        + (vital ? "; at the " + im.slot() + ": part 4's death roll (Lucky Escape against it)." : ".") + always;
            }
            default -> "! " + head + "FAILED, RIDICULOUS: the GM's absurd mistake (the wrong part, the implant in backwards with its effect reversed, "
                    + "a sponge left inside = an infection roll at the dirty class)." + always;
        };
    }

    /**
     * /sheet transplant <organ> <the donor's blood> <the surgeon's Medicine> <room> <supplies> [left_arm|right_arm]: a donor's organ as a
     * T II graft (03e §4, `09e` §6): the implant operation's chance with the place's factor (an arm's microsurgery 0.6 [mine]) and its
     * failures; this character is the patient.
     */
    static String transplant(Character c, String[] a, Random rnd) {
        if (a.length < 6)
            return "! /sheet transplant <kidney|liver|heart|lungs|corneas|arm> <the donor's blood group> <the surgeon's Medicine> <field|clean|clinic|prewar> "
                    + "<none|improvised|tier3|tier4> [left_arm|right_arm]\n! chance = min(98 %, Medicine × room × supplies × the operation) (03b §4); "
                    + "then a month's anti-rejection course at a time (Tacrolimus, Azathioprine), the corneas none";
        Character.Graft g = Character.graftOf(a[1]);
        if (g == null) return "! a donor organ: kidney, liver, heart, lungs, corneas, arm";
        double med = num(a, 3, 0);
        Double room = Rules.ROOMS.get(a[4].toLowerCase(Locale.ROOT)), sup = Rules.SUPPLIES.get(a[5].toLowerCase(Locale.ROOT));
        if (Double.isNaN(med) || room == null || sup == null) return "! Medicine a number, the room field|clean|clinic|prewar, the supplies none|improvised|tier3|tier4";
        String limb = a.length > 6 ? a[6].toLowerCase(Locale.ROOT) : null;
        String donor = a[2].toUpperCase(Locale.ROOT);
        if (g.drugs() && !Character.aboMatch(donor, c.bloodGroup(rnd)))   // checked before the knife: the GM's blood test
            return "! the donor's blood group " + donor + " does not match " + c.blood + " (O gives to all, AB takes from all): no operation";
        double chance = Rules.surgeryChance(med, room, sup, g.op());
        int d = 1 + rnd.nextInt(100);
        String head = "Transplant, the donor's " + g.organ() + ": " + Math.round(med) + " × " + room + " × " + sup + " × " + g.op() + " = "
                + SheetGui.f(Math.round(chance * 10) / 10.0) + " %, rolled " + d + ": ";
        String always = "\nAlways (part 4): the infection roll (a cut 2 %), the anaesthesia's own risk"
                + (g.drugs() ? "; from today a month's anti-rejection course at a time (Tacrolimus the pre-war rate, Azathioprine the made one), "
                + "Immunity −30 % while taken, two weeks without it = rejected" : "; the corneas need no drugs");
        if (d <= chance) {
            String why = c.graft(g.organ(), donor, limb, rnd);
            return head + (why == null ? "SUCCESS, the " + g.organ() + " is in." : "success, but: " + why) + always;
        }
        String kind = Rules.surgeryFailure(1 + rnd.nextInt(100), med);
        boolean vital = List.of("heart", "lungs", "liver").contains(g.organ());
        return switch (kind) {
            case "minor" -> {
                int hp = 1 + rnd.nextInt(6);
                c.hp -= hp;
                c.survival.put(Rules.Stat.PAIN, Math.min(100, c.survival.getOrDefault(Rules.Stat.PAIN, 0.0) + 30));
                yield head + "FAILED, minor: not in, HP −" + hp + ", Pain +30; the organ still good if it is still within its hours." + always;
            }
            case "catastrophic" -> {
                int hp = 2 + rnd.nextInt(10) + rnd.nextInt(10);
                c.hp -= hp;
                yield "! " + head + "FAILED, CATASTROPHIC: the place damaged, HP −" + hp + ", the organ lost"
                        + (vital ? "; part 4's death roll (Lucky Escape against it)." : ".") + always;
            }
            default -> "! " + head + "FAILED, RIDICULOUS: the GM's absurd mistake (the wrong side, a vessel tied off, a sponge left inside = an "
                    + "infection roll at the dirty class)." + always;
        };
    }

    // ------------------------------------------------------------------ /sheet revive
    /**
     * /sheet revive <the medic's Medicine> <minutes since the arrest> [of them with CPR] <hands|adrenaline|defibrillator|icu> (03b §5,
     * ACCEPTED 11-16): this character is the patient (his template's window); on success the brain's price.
     */
    static String revive(Character c, String[] a, Random rnd) {
        if (a.length < 4)
            return "! /sheet revive <the medic's Medicine> <minutes since the arrest> [of them with CPR] <hands|adrenaline|defibrillator|icu>"
                    + "\n! chance = Medicine / 2 % × the equipment × (1 − 7 % a minute, 3 % a minute with CPR), the minutes ÷ the template's window (03b §5)";
        double med = num(a, 1, 0), min = num(a, 2, 0);
        double cpr = a.length >= 5 ? num(a, 3, 0) : 0;
        Double eq = Rules.EQUIPMENT.get(a[a.length - 1].toLowerCase(Locale.ROOT));
        if (Double.isNaN(med) || Double.isNaN(min) || Double.isNaN(cpr) || eq == null) return "! numbers, then hands|adrenaline|defibrillator|icu";
        cpr = Math.min(cpr, min);
        Data.Template t = c.tpl();
        double w = c.deathWindow();
        boolean torpor = t.atDeath().equals("torpor");
        double chance = torpor ? med / 2 * eq : Rules.revivalChance(med, eq, min - cpr, cpr, w);
        if (chance <= 0) return "! FINAL DEATH: " + SheetGui.f(min) + " minutes is past the window (× " + SheetGui.f(w) + " for a " + c.template + ").";
        int d = 1 + rnd.nextInt(100);
        String head = "Revival of a " + c.template + (torpor ? " in torpor (no time penalty)" : ", window × " + SheetGui.f(w)) + ": " + SheetGui.f(Math.round(chance * 10) / 10.0)
                + " %, rolled " + d + ": ";
        if (c.has("Medical Marvel")) return "! " + head + "Medical Marvel: nobody can revive you (02c).";
        if (d > chance) return "! " + head + "failed; one attempt a minute.";
        int price = torpor ? 0 : Rules.brainPrice(min - cpr + cpr / 2, w);
        int floor = Rules.deathFloor(c.attrs(false));
        if (c.hp < floor) c.hp = floor;
        String out = head + "the heart beats again (HP " + c.hp + ", still dying until treated).";
        if (price > 0) {
            int[] m = new int[7];
            m[Rules.I] = -price;
            c.add(new Character.Effect("Brain damage (clinical death)", "trait", "", true, m, new LinkedHashMap<>(), 0, null, 100));
            out += " The brain's price: Intelligence −" + price + " (−1 for every 3 minutes beyond 5 × the window)";
            if (c.attr(Rules.I, true) <= 0) out += "; Intelligence 0 = FINAL DEATH";
            out += ".";
        }
        return out;
    }

    // ------------------------------------------------------------------ /sheet dose
    static String dose(Character c, String[] a, Random rnd) {
        if (a.length < 2) {
            StringBuilder sb = new StringBuilder("Chems (03b §8: effect → crash → if addicted withdrawal → dependence; a dose goes back to the start):");
            for (Data.Chem ch : Data.CHEMS.values()) {
                sb.append("\n ").append(ch.name().replace(' ', '_')).append(": ").append(ch.chance() > 0 ? Math.round(ch.chance() * 100) + " % " + (ch.perDay() ? "a day of use" : "a dose") : "no addiction");
                if (ch.withdrawal() != null) sb.append(", withdrawal ").append(SheetView.hours(ch.withdrawal().hours()));
                if (ch.dependence() != null) sb.append(", dependence ").append(SheetView.hours(ch.dependence().hours()));
                if (ch.example()) sb.append(" (EXAMPLE numbers)");
            }
            sb.append("\n/sheet dose <chem> · /sheet wait <hours> runs the clock");
            return sb.toString();
        }
        Data.Chem ch = Data.find(Data.CHEMS, String.join(" ", Arrays.copyOfRange(a, 1, a.length)));
        if (ch == null) return "! no chem " + a[1] + " (/sheet dose lists them)";
        if (c.tpl().needs().equals("power") && ch.chance() > 0) return "! a " + c.template + " takes no chems";
        return c.dose(ch, rnd);
    }

    /** The timed effects, for /sheet wait. */
    static String timed(Character c) {
        StringBuilder sb = new StringBuilder();
        for (Character.Effect e : c.effects)
            if (!e.permanent()) sb.append(sb.isEmpty() ? "" : "; ").append(e.name()).append(" ").append(SheetView.hours(e.hoursLeft())).append(e.next() != null ? " → " + e.next() : "");
        if (!c.addictions.isEmpty()) sb.append(sb.isEmpty() ? "" : "; ").append("addicted: ").append(String.join(", ", c.addictions));
        return sb.isEmpty() ? "nothing timed runs" : sb.toString();
    }

    // ------------------------------------------------------------------ /sheet mutate, /sheet limb
    static String mutate(Character c, String[] a, Random rnd) {
        if (a.length < 2) return "! /sheet mutate <100|200|300|400|500>: once a year, expel that many rads for one mutation (03b §7; no Lucky Escape)";
        int r = arg(a, 1, 0);
        if (r == Integer.MIN_VALUE) return "! a number of rads";
        String out = c.mutate(r, rnd);
        return out.startsWith("d100") || out.startsWith("rolled") ? out : "! " + out;
    }

    static String limb(Character c, String[] a) {
        if (a.length < 3) return "! /sheet limb <left_arm|right_arm|left_leg|right_leg> <hook|peg|mechanical|powered|bionic|none> (03b §6: 50-60 / 80 / 100 / 120 %)";
        String why = c.fitLimb(a[1].toLowerCase(Locale.ROOT), a[2].toLowerCase(Locale.ROOT));
        if (why != null) return "! " + why;
        double eff = c.efficiency(a[1].toLowerCase(Locale.ROOT));
        return a[1].replace('_', ' ') + ": " + (a[2].equalsIgnoreCase("none") ? "natural again" : a[2] + ", " + Math.round(eff) + " %")
                + (a[1].endsWith("arm") ? "; to hit with it " + Rules.prostheticToHit(eff) : "; movement × " + SheetGui.f(c.legFactor()));
    }

    // ------------------------------------------------------------------ falls
    /** A fall of {@code blocks}: the Athletics check, then the damage through the /sheet hit rules (Pain 3 a HP); null for a fall too short to hurt. */
    static String fall(Character c, double blocks, Random rnd) {
        int dice = Rules.fallDice(blocks);
        if (dice == 0) return null;
        int ath = (int) Math.floor(c.skill("Athletics"));
        double share = Rules.fallShare(ath, dice);
        if (share == 0) return "A fall of " + Math.round(blocks) + " blocks: your Athletics " + ath + " takes it (at least " + 40 * dice + " ignores " + dice + "d6).";
        int dmg = 0;
        for (int i = 0; i < dice; i++) dmg += 1 + rnd.nextInt(6);
        double f = c.tpl().fallFactor();
        int total = (int) Math.floor(dmg * share * f);
        String how = share < 1 ? ", halved (Athletics " + ath + " ≥ " + 20 * dice + ")" : " (Athletics " + ath + " < " + 20 * dice + ": full)";
        if (total <= 0) return "A fall of " + Math.round(blocks) + " blocks: " + dice + "d6 = " + dmg + how + ": nothing.";
        return "A fall of " + Math.round(blocks) + " blocks: " + dice + "d6 = " + dmg + how + (f != 1 ? ", × " + SheetGui.f(f) + " " + c.template : "")
                + ": " + Hud.hit(c, total, "normal");
    }
}
