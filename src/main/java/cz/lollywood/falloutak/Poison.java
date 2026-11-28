package cz.lollywood.falloutak;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.StringJoiner;

/**
 * POISON (part 4, `_notes/Fallout2/04d-POISON-RADIATION.md` §1, RULED rounds 1-8, 11-14..11-16; found unbuilt at the notes' rebuild on
 * 11-28, BUILT 0.117.0, TODO 24; the numbers are the survival sim's, `_tools/fallout_survival_sim.py` §4, tested there).
 * <ul>
 *   <li><b>Only the attack's roll adds</b> ({@link #bite}): a venomous hit through DT that fails the roll against the poison resistance
 *   gives {@value #PER_DAMAGE} poison a point of damage; Lucky Escape makes it a dry bite; antivenom still in a muscle neutralises it.</li>
 *   <li><b>Where it lands</b>: a limb bite sits in the limb (its DEPOT) and reaches the bar through the lymph; the torso, the head and the
 *   groin go straight into the bar (the groin's lymph runs into the trunk and no band goes round it, round 8); under the team's Wild
 *   Wasteland setting a man's groin is a small depot that can be cut away (round 8).</li>
 *   <li><b>The hourly roll only takes away</b> ({@link #hour}): its chance = the poison resistance (+{@value #TEND} for {@value #TEND_HOURS}
 *   hours after a check); a success cures 1 + the resistance (%) / {@value #CURE_DIV} (Double Healing × 2) and halves that hour's drain;
 *   breathing kept up makes the halving certain at Deadly and Fatal; the drain grows with Dehydration; Critical to Fatal sweat and vomit.</li>
 *   <li><b>What holds a depot</b> ({@link #treat}): a pressure bandage kept still or a constriction band (a check; failed: too tight =
 *   a tourniquet, too loose = 1/4); a tourniquet, full or loosened 10 minutes an hour, with its ischemia clock (reperfusion from 2 hours,
 *   crippled from 4, the limb lost from 6 by the medic's Medicine and the cold).</li>
 *   <li><b>What takes it out</b>: antivenom into a muscle (over 12 hours, no kit, no check, two weeks' guard) or by IV (all of it, a
 *   Medicine check and the IV's Saline), varespladib by mouth (within 5 hours of the bite), wide excision by the medic's Medicine, cut and
 *   bleed (worthless), plasma exchange (a pre-war hospital: the GM's), amputation. The antivenoms and varespladib act on VENOM only
 *   ({@link Character#venom}: the bar's venom share; aconite, strychnine and the gases are no venom).</li>
 * </ul>
 * [EXAMPLE, mine: the checks' difficulties, where a creature's strike lands, the band's failure by a coin, the crippled limb's 3 weeks,
 * the guard used by the next bite, the surgical cuts' infection roll at a gunshot's 15 %.]
 */
final class Poison {
    private Poison() {}

    static final double PER_DAMAGE = 3;
    /** The depot to the bar an hour: moving, a pressure bandage kept still or a constriction band, applied too loose, a tourniquet
     *  loosened 10 minutes an hour (a full one: none). */
    static final double FLOW = 0.5, HELD = 1.0 / 8, TOO_LOOSE = 0.25, TQ_LOOSE = 1.0 / 12;
    static final double CURE_DIV = 20, TEND = 0.25, TEND_HOURS = 6;
    /** Water sweated and vomited an hour by the stage (round 5). */
    static final Map<String, Double> SWEAT = Map.of("Critical Poisoning", 1.0, "Deadly Poisoning", 1.5, "Fatal Poisoning", 2.0);
    /** The poison's drain × by the Dehydration stage: none, Minor, Advanced, Critical, Deadly (round 5). */
    static final double[] DRY = {1, 1, 1.25, 1.5, 2};
    /** The tourniquet (round 7): HP an ischemia hour past 2 when it comes off (half with IV fluids first), crippled from 4 hours, the
     *  limb lost from 6 at 2 in 3 × (1 − ½ × min(1, Medicine / 100)) × (1 − ½ if kept under 15 °C, then the ischemia at half). */
    static final double REPERFUSION = 2, LOSS_6H = 2.0 / 3, COLD_C = 15;
    static final int REPERFUSE_H = 2, CRIPPLE_H = 4, LOSE_H = 6, CRIPPLED_DAYS = 21;
    /** Antivenom into a muscle: over 12 hours, a guard of 14 days; varespladib: half over 12 hours (the sim's 1/24 an hour), only within 5
     *  hours of the bite; plasma exchange: a tenth of the bar an hour. */
    static final double AV_IM_HOURS = 12, AV_GUARD_DAYS = 14, VARES_HOURS = 12, VARES_WINDOW = 5, PLASMA = 0.10;
    /** The checks (no roll: a comparison, as the plugin's other first aid) [EXAMPLE]: the bandage or the band and tending by Survival or
     *  Medicine (the splint's 20), an IV and breathing kept up by Medicine (the Trauma pack's 40; a bag squeezed 30). */
    static final int BAND_NEED = 20, TEND_NEED = 20, IV_NEED = 40, BREATH_NEED = 30;
    /** A surgical cut's infection roll (excision, amputation) [EXAMPLE: a deep wound, the gunshot's 15 %]; cut and bleed: a dirty wound. */
    static final String SURGICAL = "gunshot";
    static final List<String> LIMBS = List.of("left_arm", "right_arm", "left_leg", "right_leg");
    static final String GROIN = "groin";
    /** The holds a limb's depot can have. */
    static final List<String> HOLDS = List.of("bandage", "band", "loose", "tq", "tq-loose");

    // ------------------------------------------------------------------ the state
    static double bar(Character c) { return c.survival.getOrDefault(Rules.Stat.POISON, 0.0); }

    static double depot(Character c) { double s = 0; for (double v : c.depots.values()) s += v; return s; }

    /** Poisoned: the bar, a depot, or a limb still under a tourniquet. */
    static boolean poisoned(Character c) { return bar(c) > 1e-9 || depot(c) > 1e-9 || !c.holds.isEmpty(); }

    /** The poison resistance as a fraction, 0..1. */
    static double resistance(Character c) { return Math.max(0, Math.min(1, c.resistances()[2] / 100)); }

    static String where(String part) { return part.replace('_', ' '); }

    /** Adds to the bar (up to its maximum); {@code venomous}: it counts as venom. */
    static void addBar(Character c, double v, boolean venomous) {
        double before = bar(c), after = Math.min(c.max(Rules.Stat.POISON), before + v);
        c.survival.put(Rules.Stat.POISON, after);
        if (venomous) c.venom = Math.min(after, c.venom + (after - before));
    }

    /** Takes {@code v} of venom out of the bar (an antidote's work). */
    static void takeVenom(Character c, double v) {
        double t = Math.min(Math.max(0, v), c.venom);
        c.venom -= t;
        c.survival.put(Rules.Stat.POISON, Math.max(0, bar(c) - t));
    }

    /** Takes {@code v} of any poison out of the bar, its venom share alike. */
    static void takeAny(Character c, double v) {
        double b = bar(c), t = Math.min(Math.max(0, v), b);
        if (b <= 0) return;
        c.venom = Math.max(0, c.venom * (1 - t / b));
        c.survival.put(Rules.Stat.POISON, b - t);
    }

    // ------------------------------------------------------------------ the bite
    /**
     * Where a creature's unaimed strike lands for its venom [EXAMPLE, by the silhouette's area in {@link Rules#part}'s model, 32 rows of
     * Minecraft's body: the legs and the arms 27 % each, the torso 25 %, the groin 2 %, the head 19 %]. The strike's damage and crit still
     * use the torso's numbers (part 5 decides the creatures' hit places: OPEN).
     */
    static String strikePart(Random rnd) {
        int d = 1 + rnd.nextInt(100);
        if (d <= 27) return d <= 13 ? "left_leg" : "right_leg";
        if (d <= 54) return d <= 40 ? "left_arm" : "right_arm";
        if (d <= 79) return "torso";
        if (d <= 81) return GROIN;
        return "head";
    }

    /** The part an aimed hit's {@link Rules.Part} is for the venom. */
    static String part(Rules.Part p) {
        return switch (p) {
            case EYES, HEAD -> "head";
            case TORSO -> "torso";
            case GROIN -> GROIN;
            case LEFT_ARM -> "left_arm";
            case RIGHT_ARM -> "right_arm";
            case LEFT_LEG -> "left_leg";
            case RIGHT_LEG -> "right_leg";
        };
    }

    /** The limb holds a depot (a limb, or a man's groin under Wild Wasteland) and is still there. */
    static boolean holdsDepot(Character c, String part) {
        if (part.equals(GROIN)) return Travel.wildWasteland && !c.female && c.inSlot("injury:groin") == null;
        return LIMBS.contains(part) && c.inSlot("limb:" + part) == null;
    }

    /**
     * A venomous hit's damage through DT at this part ("left_leg" ..., "torso", "head", "groin"): the roll against the poison resistance,
     * Lucky Escape (a dry bite: in life about a fifth of pit viper bites), the antivenom's guard; then {@value #PER_DAMAGE} poison a point of
     * damage into the limb's depot or the bar. Returns the log (with a leading space), "" when nothing.
     */
    static String bite(Character c, String part, int dmg, Random rnd) {
        if (dmg <= 0 || c.dead) return "";
        String head = " The venom (" + where(part) + "): ";
        double res = resistance(c);
        int d = 1 + rnd.nextInt(100);
        if (d <= Math.round(100 * res)) return head + "resisted (d100 " + d + " ≤ the poison resistance " + Math.round(100 * res) + " %).";
        Character.Luck lk = c.luck(rnd);
        if (lk.ok()) return head + "a DRY BITE (Lucky Escape, d100 " + lk.roll() + " ≤ " + lk.chance() + " %): the wound without the venom.";
        if (c.antivenomGuardUntil > c.now()) {
            c.antivenomGuardUntil = -1;
            return head + "the antivenom still in the muscle takes it (its guard is used up).";
        }
        double p = PER_DAMAGE * dmg;
        c.bittenAt = c.now();
        if (holdsDepot(c, part)) {
            c.depots.merge(part, p, Double::sum);
            return head + "+" + Txt.f(p) + " into the " + where(part) + " (" + Txt.f(c.depots.get(part)) + " there; it reaches the body "
                    + "through the lymph: keep still with a pressure bandage, or a band)";
        }
        addBar(c, p, true);
        return head + "+" + Txt.f(p) + " Poison (now " + Math.round(bar(c)) + " of " + Math.round(c.max(Rules.Stat.POISON)) + "): straight into the body";
    }

    // ------------------------------------------------------------------ the hour
    /** This hour's drain × on the Poisoning stage's HP (read by {@link Character#stageHour}): the Dehydration's, halved by a cure. */
    static double drain(Character c) {
        double f = DRY[Rules.fallingLevel(c.fraction(Rules.Stat.WATER))];
        return c.poisonHalved ? f / 2 : f;
    }

    /** The hour's cure on a success: 1 + the resistance (%) / 20. */
    static double cure(double res) { return 1 + 100 * res / CURE_DIV; }

    /**
     * One game hour of poison: the antidotes at work, each depot's share into the bar by its hold, the tourniquet's clock, the hourly roll
     * (it only takes away), the sweat and the vomiting. Runs after the hour's bars, before the stages' HP ({@link Character#stageHour}).
     * Returns the news, or null.
     */
    static String hour(Character c, Random rnd) {
        c.poisonHalved = false;
        if (c.dead || c.inArrest()) return null;
        StringJoiner news = new StringJoiner("; ");
        double now = c.now();
        if (c.antivenomHours > 0) {   // into a muscle: the dose over 12 hours (the sim: a 1/h of what is there, h counting down)
            double k = 1 / c.antivenomHours;
            takeVenom(c, c.venom * k);
            c.depots.replaceAll((l, v) -> v * (1 - k));
            c.antivenomHours = Math.max(0, c.antivenomHours - 1);
        }
        if (c.varesHours > 0) {
            double k = 0.5 / VARES_HOURS;
            takeVenom(c, c.venom * k);
            c.depots.replaceAll((l, v) -> v * (1 - k));
            c.varesHours = Math.max(0, c.varesHours - 1);
        }
        if (c.plasmaExchange) takeAny(c, bar(c) * PLASMA);
        boolean still = c.activity().equals("rest");
        for (String l : new ArrayList<>(c.depots.keySet())) {
            String h = c.holds.getOrDefault(l, "");
            double flow = switch (h) {
                case "tq" -> 0;
                case "tq-loose" -> TQ_LOOSE;
                case "band" -> HELD;
                case "bandage" -> still ? HELD : FLOW;   // a pressure bandage works kept still; moving pumps the lymph
                case "loose" -> TOO_LOOSE;
                default -> FLOW;
            };
            double v = c.depots.get(l), moved = v * flow;
            if (v - moved < 0.05) moved = v;
            addBar(c, moved, true);
            if (v - moved <= 1e-9) c.depots.remove(l); else c.depots.put(l, v - moved);
        }
        for (Map.Entry<String, String> e : c.holds.entrySet()) {   // the tourniquet's clock: 1 an hour, loosened ½, a cold limb ½
            if (!e.getValue().startsWith("tq")) continue;
            double add = (e.getValue().equals("tq") ? 1 : 0.5) * (cold(c, e.getKey()) ? 0.5 : 1);
            double was = c.ischemia.getOrDefault(e.getKey(), 0.0), is = was + add;
            c.ischemia.put(e.getKey(), is);
            for (int mark : new int[]{REPERFUSE_H, CRIPPLE_H, LOSE_H})
                if (was < mark && is >= mark)
                    news.add("the tourniquet on the " + where(e.getKey()) + ": " + Txt.f(is) + " ischemia hours" + switch (mark) {
                        case REPERFUSE_H -> " (from now a reperfusion when it comes off)";
                        case CRIPPLE_H -> " (from now the limb is crippled for weeks)";
                        default -> " (from now the limb is lost in about 2 of 3)";
                    });
        }
        double b = bar(c);
        if (b > 1e-9) {
            double res = resistance(c), chance = Math.min(1, res + (c.poisonTendedUntil > now ? TEND : 0));
            int d = 1 + rnd.nextInt(100);
            if (d <= Math.round(100 * chance)) {
                double cure = cure(res);
                Character.Luck lk = c.luck(rnd);
                if (lk.ok()) cure *= 2;   // Double Healing on the hour's cure (04d §4)
                if (c.coUntil > now && c.oxygenUntil <= now) cure /= 2;   // 03b §3c: CO, full on bottled oxygen
                takeAny(c, cure);
                c.poisonHalved = true;
                if (lk.ok()) news.add("Double Healing on the poison's cure");
            }
        }
        String st = Rules.stage(Rules.Stat.POISON, bar(c), c.max(Rules.Stat.POISON));
        if (st != null && (st.equals("Deadly Poisoning") || st.equals("Fatal Poisoning")) && c.poisonBreathing) c.poisonHalved = true;
        double sweat = st == null ? 0 : SWEAT.getOrDefault(st, 0.0);
        if (sweat > 0 && !"power".equals(c.tpl().needs()))
            c.survival.put(Rules.Stat.WATER, Math.max(0, c.survival.getOrDefault(Rules.Stat.WATER, 0.0) - sweat));
        if (bar(c) <= 1e-9) c.venom = 0;
        return news.length() == 0 ? null : news.toString();
    }

    /** The limb is kept cold: packed so (the act), or the air under 15 °C. */
    static boolean cold(Character c, String limb) {
        double air = c.airTemp();
        return c.cooledLimbs.contains(limb) || !Double.isNaN(air) && air < COLD_C;
    }

    // ------------------------------------------------------------------ the acts
    /** The acts: on a limb, on the body, the GM's. */
    static final List<String> ON_LIMB = List.of("bandage", "band", "tq", "tq-loose", "off", "cool", "cut", "excise", "amputate");
    static final List<String> ON_BODY = List.of("tend", "breathing", "fluids", "antivenom-im", "antivenom-iv", "varespladib", "plasma", "cutaway");

    static String actText(String act) {
        return switch (act) {
            case "bandage" -> "a pressure bandage and a splint, kept still: the depot 1/8 an hour (a Survival or Medicine " + BAND_NEED + " check; failed: too tight = a tourniquet, too loose 1/4)";
            case "band" -> "a constriction band (a strap, not a tourniquet): 1/8 an hour even on the move (the same check)";
            case "tq" -> "a full tourniquet: the depot stops; the ischemia 1 h an hour (reperfusion from 2, crippled from 4, the limb lost from 6)";
            case "tq-loose" -> "loosen the tourniquet 10 minutes an hour: the depot 1/12 an hour, the ischemia at half";
            case "off" -> "take the hold off (a tourniquet's reperfusion, the limb's fate by the ischemia)";
            case "cool" -> "keep the limb cold, under 15 °C (snow, cold water): its ischemia at half, the loss halved";
            case "cut" -> "cut and bleed (worthless: venom moves in the tissue and the lymph): 5 % of the depot, 10 % of the Blood, a dirty wound";
            case "excise" -> "cut the bitten flesh out: in the first hour min(50 %, 15 % + Medicine / 400) of the depot, half that in the second";
            case "amputate" -> "the limb off: its depot gone; HP −5, a quarter of the Blood, Pain 60 (none under anaesthesia)";
            case "tend" -> "a Survival or Medicine " + TEND_NEED + " check: the poison resistance +0.25 for 6 hours";
            case "breathing" -> "breathing kept up (a bag squeezed, Medicine " + BREATH_NEED + "): at Deadly and Fatal every hour halves the drain";
            case "fluids" -> "IV fluids (Saline, Medicine " + IV_NEED + ") before a tourniquet comes off: its reperfusion halved";
            case "antivenom-im" -> "antivenom into a muscle: the venom out over 12 hours, no kit, no check; a guard of 2 weeks against the next bite";
            case "antivenom-iv" -> "antivenom by IV (Saline, Medicine " + IV_NEED + "): all the venom out within the hour";
            case "varespladib" -> "varespladib by mouth within 5 hours of the bite: half the venom (the body and the limb) over 12 hours";
            case "plasma" -> "plasma exchange (a pre-war hospital's machine: the GM's): a tenth of the Poison an hour, any poison";
            case "cutaway" -> "Wild Wasteland: the bitten male parts cut away like a finger, the depot whole; a quarter of the Blood, Pain 80";
            default -> act;
        };
    }

    /** The acts that make sense on this body now (the menu's). */
    static List<String> acts(Character c) {
        List<String> l = new ArrayList<>();
        for (String limb : c.depots.keySet()) if (LIMBS.contains(limb)) for (String a : ON_LIMB) l.add(a + " " + limb);
        for (String limb : c.holds.keySet()) if (!c.depots.containsKey(limb)) for (String a : List.of("off", "cool", "amputate")) l.add(a + " " + limb);
        if (bar(c) > 1e-9) { l.add("tend"); l.add("breathing"); l.add("plasma"); }
        if (c.venom > 1e-9 || depot(c) > 1e-9) { l.add("antivenom-im"); l.add("antivenom-iv"); l.add("varespladib"); }
        if (c.holds.values().stream().anyMatch(h -> h.startsWith("tq"))) l.add("fluids");
        if (c.depots.containsKey(GROIN)) l.add("cutaway");
        return l;
    }

    /**
     * An act on a poisoned body ({@code limb} for the limb acts, else ignored). {@code survival} and {@code medicine}: the carer's skills
     * (the better counts where either does; NaN = the GM's word: every check passes); {@code anaesthetic}: an amputation without Pain.
     * Returns what happened, or "! why not".
     */
    static String treat(Character c, String act, String limb, double survival, double medicine, boolean anaesthetic, Random rnd) {
        boolean gm = Double.isNaN(medicine);
        double either = gm ? 999 : Math.max(Double.isNaN(survival) ? 0 : survival, medicine), med = gm ? 100 : medicine;
        double now = c.now();
        if (ON_LIMB.contains(act)) {
            if (limb == null || !LIMBS.contains(limb)) return "! " + act + " needs a limb: " + String.join(", ", LIMBS);
            if (c.inSlot("limb:" + limb) != null) return "! the " + where(limb) + " is not his own";
        }
        switch (act) {
            case "bandage", "band" -> {
                if (!c.depots.containsKey(limb)) return "! no venom in the " + where(limb);
                if (Rules.check(either, BAND_NEED)) {
                    c.holds.put(limb, act);
                    return "the " + where(limb) + ": " + (act.equals("band") ? "a constriction band, right" : "a pressure bandage, right (keep him still)")
                            + ": the venom 1/8 an hour";
                }
                boolean tight = rnd.nextBoolean();
                c.holds.put(limb, tight ? "tq" : "loose");
                return "the " + where(limb) + ": the " + (act.equals("band") ? "band" : "bandage") + " is WRONG (Survival or Medicine " + Math.round(either)
                        + " under " + BAND_NEED + "): " + (tight ? "too tight, it is a tourniquet (the ischemia clock runs)" : "too loose, the venom 1/4 an hour");
            }
            case "tq" -> {
                c.holds.put(limb, "tq");
                return "the " + where(limb) + ": a full tourniquet; the venom stops there, the ischemia clock runs ("
                        + Txt.f(c.ischemia.getOrDefault(limb, 0.0)) + " h so far)";
            }
            case "tq-loose" -> {
                if (!c.holds.getOrDefault(limb, "").startsWith("tq")) return "! no tourniquet on the " + where(limb);
                c.holds.put(limb, "tq-loose");
                return "the " + where(limb) + ": the tourniquet loosened 10 minutes every hour (the venom 1/12 an hour, the ischemia at half)";
            }
            case "cool" -> {
                if (c.cooledLimbs.remove(limb)) return "the " + where(limb) + ": no longer kept cold";
                c.cooledLimbs.add(limb);
                return "the " + where(limb) + ": kept cold, under 15 °C (its ischemia at half)";
            }
            case "off" -> { return off(c, limb, med, rnd); }
            case "cut" -> {
                double v = c.depots.getOrDefault(limb, 0.0);
                if (v <= 0) return "! no venom in the " + where(limb);
                c.depots.put(limb, v * 0.95);
                bump(c, Rules.Stat.BLOOD, -0.10 * c.max(Rules.Stat.BLOOD));
                String inf = Diseases.wound(c, "dirty", rnd);
                return "the " + where(limb) + " cut and bled: 5 % of its venom out (" + Txt.f(0.05 * v) + "), 10 % of the Blood; a dirty wound" + (inf == null ? "" : " (the GM's: an infection)");
            }
            case "excise" -> {
                double v = c.depots.getOrDefault(limb, 0.0);
                if (v <= 0) return "! no venom in the " + where(limb);
                double since = c.bittenAt < 0 ? 99 : now - c.bittenAt;
                double share = Math.min(0.5, 0.15 + med / 400) * (since <= 1 ? 1 : since <= 2 ? 0.5 : 0);
                c.depots.put(limb, v * (1 - share));
                double blood = Math.max(3, 15 - med / 10), hp = Math.max(1, 5 - med / 40);
                bump(c, Rules.Stat.BLOOD, -blood / 100 * c.max(Rules.Stat.BLOOD));
                c.hp -= (int) Math.round(hp);
                c.survival.put(Rules.Stat.PAIN, c.survival.getOrDefault(Rules.Stat.PAIN, 0.0) + 40);
                Diseases.wound(c, SURGICAL, rnd);
                return "the bite in the " + where(limb) + " cut out (Medicine " + Math.round(med) + ", " + Txt.f(since) + " h after the bite): "
                        + Math.round(100 * share) + " % of its venom (" + Txt.f(v * share) + ")" + (share <= 0 ? ", too late: nothing" : "")
                        + "; Blood −" + Txt.f(blood) + " %, HP −" + Math.round(hp) + ", Pain +40, an infection roll";
            }
            case "amputate" -> {
                double v = c.depots.getOrDefault(limb, 0.0);
                String r = loseLimb(c, limb, anaesthetic, rnd);
                return "the " + where(limb) + " amputated" + (v > 0 ? ", its venom with it (" + Txt.f(v) + ")" : "") + ": " + r;
            }
            case "tend" -> {
                if (!Rules.check(either, TEND_NEED)) return "! tending the poisoned needs Survival or Medicine " + TEND_NEED + ": " + Math.round(either);
                c.poisonTendedUntil = now + TEND_HOURS;
                return "tended: the poison resistance +0.25 for " + Math.round(TEND_HOURS) + " hours (the hourly roll " + Math.round(100 * Math.min(1, resistance(c) + TEND)) + " %)";
            }
            case "breathing" -> {
                if (c.poisonBreathing) { c.poisonBreathing = false; return "breathing support stopped"; }
                if (!Rules.check(med, BREATH_NEED)) return "! keeping his breathing up needs Medicine " + BREATH_NEED + ": " + Math.round(med);
                c.poisonBreathing = true;
                return "breathing kept up (someone at the bag all the time; on until stopped): at Deadly and Fatal Poisoning each hour's drain halved";
            }
            case "fluids" -> {
                if (!Rules.check(med, IV_NEED)) return "! an IV needs Medicine " + IV_NEED + ": " + Math.round(med);
                c.ivFluids = true;
                return "IV fluids running: the next tourniquet off has its reperfusion halved";
            }
            case "antivenom-im" -> {
                c.antivenomHours = AV_IM_HOURS;
                c.antivenomGuardUntil = now + AV_GUARD_DAYS * 24;
                return "antivenom into a muscle: the venom out over 12 hours (now " + Txt.f(c.venom) + " in the body, " + Txt.f(depot(c))
                        + " in the limbs); it guards against the next bite for 2 weeks";
            }
            case "antivenom-iv" -> {
                if (!Rules.check(med, IV_NEED)) return "! antivenom by IV needs Medicine " + IV_NEED + ": " + Math.round(med) + " (into a muscle needs none)";
                double v = c.venom, dp = depot(c);
                takeVenom(c, v);
                c.depots.clear();
                return "antivenom by IV: the venom gone within the hour (" + Txt.f(v) + " from the body, " + Txt.f(dp) + " from the limbs); Poison now " + Math.round(bar(c));
            }
            case "varespladib" -> {
                double since = c.bittenAt < 0 ? 99 : now - c.bittenAt;
                if (since > VARES_WINDOW) return "varespladib taken " + Txt.f(since) + " h after the bite: too late (it works within 5 hours), nothing";
                c.varesHours = VARES_HOURS;
                return "varespladib by mouth (" + Txt.f(since) + " h after the bite): half the venom out over 12 hours";
            }
            case "plasma" -> {
                c.plasmaExchange = !c.plasmaExchange;
                return c.plasmaExchange ? "plasma exchange running (a pre-war hospital's machine): a tenth of the Poison an hour, until stopped" : "plasma exchange stopped";
            }
            case "cutaway" -> {
                if (!c.depots.containsKey(GROIN)) return "! no venom in the groin's depot (Wild Wasteland, a man's groin)";
                double v = c.depots.remove(GROIN);
                bump(c, Rules.Stat.BLOOD, -0.25 * c.max(Rules.Stat.BLOOD));
                c.survival.put(Rules.Stat.PAIN, c.survival.getOrDefault(Rules.Stat.PAIN, 0.0) + 80);
                Diseases.wound(c, SURGICAL, rnd);
                c.add(new Character.Effect("Unmanned (Wild Wasteland)", "injury", "injury:groin", true, new int[7], new LinkedHashMap<>(), 0, null, 100));
                return "WILD WASTELAND: the bitten male parts cut away, the venom with them (" + Txt.f(v) + "); a quarter of the Blood, Pain +80, a wound-infection roll; for good";
            }
            default -> { return "! no such act: " + String.join(", ", ON_LIMB) + " <limb>, " + String.join(", ", ON_BODY); }
        }
    }

    /**
     * A hold comes off a limb: a band or a bandage simply; a tourniquet by its ischemia hours: from 6 the limb is lost at 2 in 3 × (1 − ½ ×
     * min(1, Medicine / 100)) × (½ if kept cold) (Lucky Escape against it); from 4 it is crippled for 3 weeks; from 2 the reperfusion costs
     * 2 HP an ischemia hour past 2 (half with IV fluids first). The venom still in it flows again.
     */
    static String off(Character c, String limb, double medicine, Random rnd) {
        String h = c.holds.remove(limb);
        if (h == null) return "! nothing holds the " + where(limb);
        double is = c.ischemia.getOrDefault(limb, 0.0);
        boolean coldLimb = cold(c, limb);
        c.cooledLimbs.remove(limb);
        if (!h.startsWith("tq") || is < REPERFUSE_H) {
            c.ischemia.remove(limb);
            return "the " + where(limb) + ": the " + (h.startsWith("tq") ? "tourniquet" : "hold") + " off (" + Txt.f(is) + " ischemia hours: no harm)";
        }
        c.ischemia.remove(limb);
        if (is >= LOSE_H) {
            double p = LOSS_6H * (1 - 0.5 * Math.min(1, medicine / 100)) * (coldLimb ? 0.5 : 1);
            int d = 1 + rnd.nextInt(100);
            if (d <= Math.round(100 * p)) {
                Character.Luck lk = c.luck(rnd);
                if (!lk.ok()) {
                    double v = c.depots.getOrDefault(limb, 0.0);
                    return "the " + where(limb) + ": the tourniquet off after " + Txt.f(is) + " ischemia hours: the limb is DEAD (d100 " + d + " ≤ "
                            + Math.round(100 * p) + " %); it must come off" + (v > 0 ? " with its venom (" + Txt.f(v) + ")" : "") + ": " + loseLimb(c, limb, false, rnd);
                }
                return "the " + where(limb) + ": the tourniquet off after " + Txt.f(is) + " ischemia hours: LUCKY ESCAPE (d100 " + lk.roll() + " ≤ "
                        + lk.chance() + " %), the limb lives; " + reperfuse(c, limb, is);
            }
        }
        return "the " + where(limb) + ": the tourniquet off after " + Txt.f(is) + " ischemia hours; " + reperfuse(c, limb, is);
    }

    /** The blood back into a limb after a tourniquet: the reperfusion's HP, crippled from 4 hours. */
    static String reperfuse(Character c, String limb, double is) {
        double loss = REPERFUSION * (is - REPERFUSE_H) * (c.ivFluids ? 0.5 : 1);
        boolean iv = c.ivFluids;
        c.ivFluids = false;
        String r = "the reperfusion: HP −" + Math.round(loss) + (iv ? " (halved by the IV fluids)" : "") + c.drain((int) Math.round(loss));
        if (is >= CRIPPLE_H) {
            boolean leg = limb.endsWith("leg");
            Map<String, Double> o = new LinkedHashMap<>();
            if (leg) { o.put("tq_leg", 1.0); c.brokenLegs = Math.min(2, c.brokenLegs + 1); }
            int[] mods = new int[7];
            if (!leg) mods[Rules.A] = -1;
            c.add(new Character.Effect("Crippled " + where(limb) + " (a tourniquet)", "injury", "injury:tq:" + limb, false, mods, o, CRIPPLED_DAYS * 24, null, 100));
            r += "; the " + where(limb) + " crippled for 3 weeks (" + (leg ? "walks as a broken leg" : "Agility −1") + ")";
        }
        return r + (c.depots.containsKey(limb) ? "; its venom flows again" : "");
    }

    /** The limb is lost (the amputation of 03b): its depot, hold and clock gone; HP −5, a quarter of the Blood, Pain 60 unless under
     *  anaesthesia, an infection roll. */
    static String loseLimb(Character c, String limb, boolean anaesthetic, Random rnd) {
        c.depots.remove(limb);
        c.holds.remove(limb);
        c.ischemia.remove(limb);
        c.cooledLimbs.remove(limb);
        c.fitLimb(limb, "missing");
        c.hp -= 5;
        bump(c, Rules.Stat.BLOOD, -0.25 * c.max(Rules.Stat.BLOOD));
        if (!anaesthetic) c.survival.put(Rules.Stat.PAIN, c.survival.getOrDefault(Rules.Stat.PAIN, 0.0) + 60);
        Diseases.wound(c, SURGICAL, rnd);
        return "HP −5, a quarter of the Blood" + (anaesthetic ? "" : ", Pain +60") + ", an infection roll; the " + where(limb) + " is missing (a prosthesis later)";
    }

    private static void bump(Character c, Rules.Stat st, double v) {
        c.survival.put(st, Math.max(0, Math.min(c.max(st), c.survival.getOrDefault(st, c.max(st)) + v)));
    }

    // ------------------------------------------------------------------ the sheet and /sheet poison
    /** The poison in this body for the sheet and the GM. */
    static String show(Character c) {
        StringJoiner j = new StringJoiner("\n");
        double b = bar(c);
        if (b > 1e-9) j.add("Poison " + Txt.f(Math.round(b * 10) / 10.0) + " of " + Math.round(c.max(Rules.Stat.POISON)) + (c.venom > 1e-9 ? " (venom " + Txt.f(Math.round(c.venom * 10) / 10.0) + ")" : "")
                + "; the hourly roll " + Math.round(100 * Math.min(1, resistance(c) + (c.poisonTendedUntil > c.now() ? TEND : 0))) + " %, a success cures " + Txt.f(cure(resistance(c))));
        for (String l : LIMBS) {
            double v = c.depots.getOrDefault(l, 0.0);
            String h = c.holds.get(l);
            if (v <= 1e-9 && h == null) continue;
            j.add("The " + where(l) + ": " + (v > 1e-9 ? Txt.f(Math.round(v * 10) / 10.0) + " venom" : "no venom") + (h == null ? ", nothing holds it (1/2 an hour)" : ", " + holdText(h))
                    + (c.ischemia.containsKey(l) ? ", " + Txt.f(c.ischemia.get(l)) + " ischemia hours" : "") + (cold(c, l) ? ", kept cold" : ""));
        }
        if (c.depots.containsKey(GROIN)) j.add("The groin (Wild Wasteland): " + Txt.f(c.depots.get(GROIN)) + " venom, nothing can hold it");
        List<String> on = new ArrayList<>();
        if (c.antivenomHours > 0) on.add("antivenom working (" + Math.round(c.antivenomHours) + " h left)");
        if (c.antivenomGuardUntil > c.now()) on.add("the antivenom's guard for " + Diseases.time(c.antivenomGuardUntil - c.now()));
        if (c.varesHours > 0) on.add("varespladib (" + Math.round(c.varesHours) + " h left)");
        if (c.plasmaExchange) on.add("plasma exchange");
        if (c.poisonBreathing) on.add("breathing kept up");
        if (c.poisonTendedUntil > c.now()) on.add("tended for " + Diseases.time(c.poisonTendedUntil - c.now()));
        if (c.ivFluids) on.add("IV fluids");
        if (!on.isEmpty()) j.add(String.join(", ", on));
        return j.length() == 0 ? "No poison." : j.toString();
    }

    static String holdText(String h) {
        return switch (h) {
            case "bandage" -> "a pressure bandage (1/8 an hour kept still)";
            case "band" -> "a constriction band (1/8 an hour)";
            case "loose" -> "a band too loose (1/4 an hour)";
            case "tq" -> "a full tourniquet (none)";
            case "tq-loose" -> "a tourniquet loosened 10 minutes an hour (1/12)";
            default -> h;
        };
    }

    /** /sheet poison [bite &lt;part&gt; &lt;damage&gt; | &lt;act&gt; [limb] | clear] (the GM's: every check passes). */
    static String command(Character c, String[] a, Random rnd) {
        String usage = "! /sheet poison [bite <part> <damage> | add <poison> [venom] | <act> [limb] | clear]; parts: " + String.join(", ", LIMBS)
                + ", torso, head, groin; acts: " + String.join(", ", ON_LIMB) + " <limb>, " + String.join(", ", ON_BODY);
        if (a.length < 2) return show(c);
        String what = a[1].toLowerCase(Locale.ROOT);
        switch (what) {
            case "bite" -> {
                if (a.length < 4) return usage;
                String part = a[2].toLowerCase(Locale.ROOT);
                if (!LIMBS.contains(part) && !List.of("torso", "head", GROIN).contains(part)) return usage;
                return ("A bite: " + bite(c, part, Integer.parseInt(a[3]), rnd).trim());
            }
            case "add" -> {
                if (a.length < 3) return usage;
                addBar(c, Double.parseDouble(a[2]), a.length > 3 && a[3].equalsIgnoreCase("venom"));
                return show(c);
            }
            case "clear" -> {
                c.survival.put(Rules.Stat.POISON, 0.0);
                c.venom = 0; c.depots.clear(); c.holds.clear(); c.ischemia.clear(); c.cooledLimbs.clear();
                c.antivenomHours = 0; c.varesHours = 0; c.plasmaExchange = false; c.poisonBreathing = false; c.ivFluids = false;
                return "No poison (the GM).";
            }
            default -> {
                String limb = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : null;
                return treat(c, what, limb, Double.NaN, Double.NaN, false, rnd);
            }
        }
    }
}
