package cz.lollywood.falloutak;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.StringJoiner;

/**
 * Part 4b §3c, the conditions with their own clocks (RULED rounds 7-10), and the exposures §5 left over (Marek 11-24, verbatim:
 * *"Please, complete the illnesses and then continue with the next item."*): scurvy by the shelter's days, trench foot by the hour,
 * scabies by contact and tuberculosis's latent years. Each condition is one
 * effect in its own slot ({@code condition:<name>}), set when its stage changes.
 */
final class Conditions {
    private Conditions() {}

    // ------------------------------------------------------------------ scurvy (04b §3c, the shelter only: RULED round 10)
    /** The scurvy counter's stages: Minor 30, Advanced 60, Critical 90, Deadly 120; dead at 150 (RULED round 7). */
    static final double[] SCURVY_AT = {30, 60, 90, 120};
    static final double SCURVY_DEAD = 150;
    static final String[] SCURVY = {"Minor Scurvy", "Advanced Scurvy", "Critical Scurvy", "Deadly Scurvy"};
    /** HP lost an hour at Critical and Deadly (old wounds open; Deadly's double mine). */
    static final double[] SCURVY_HP = {0, 0, -0.1, -0.2};

    /**
     * A day in the shelter (the base): +1 without a vitamin C day, −3 with one, never under 0 (RULED round 7). A vitamin C day = a
     * portion of Tier III or IV food eaten that day (round 8: fresh food of any kind holds it off; the rarity sets the supply, not the
     * effect). Returns the news, or null.
     */
    static String scurvyDay(Character c, boolean vitaminC, Random rnd) {
        if (c.dead) return null;
        int before = stage(c.scurvy, SCURVY_AT);
        c.scurvy = vitaminC ? Math.max(0, c.scurvy - 3) : c.scurvy + 1;
        int now = stage(c.scurvy, SCURVY_AT);
        if (c.scurvy >= SCURVY_DEAD && !c.inArrest()) return "scurvy, day " + f(c.scurvy) + ": " + c.stopHeart("Scurvy", true, rnd);
        if (now == before) return null;
        set(c, "Scurvy", now < 0 ? null : SCURVY[now], scurvyMods(now), scurvyOther(now));
        return now < 0 ? "the scurvy is gone (fresh food)" : SCURVY[now] + " (day " + f(c.scurvy) + " without fresh food: " + scurvyText(now) + ")";
    }

    /** Minor: E −1 and the healing rate −1; Advanced: + C −1, wounds stop healing; Critical and Deadly: + A −2, S −1, HP lost an hour. */
    static int[] scurvyMods(int st) {
        int[] m = new int[7];
        if (st >= 0) m[2] = -1;
        if (st >= 1) m[3] = -1;
        if (st >= 2) { m[5] = -2; m[0] = -1; }
        return m;
    }

    static Map<String, Double> scurvyOther(int st) {
        Map<String, Double> o = new LinkedHashMap<>();
        if (st == 0) o.put("healing_rate", -1.0);
        if (st >= 1) o.put("no_healing", 1.0);
        return o;
    }

    static String scurvyText(int st) {
        return switch (st) {
            case 0 -> "tired: Endurance −1, healing −1";
            case 1 -> "gums and bruises: Charisma −1, wounds stop healing";
            case 2 -> "old wounds open, joints bleed: HP −0.1 an hour, Agility −2, Strength −1";
            default -> "HP −0.2 an hour; dead on day 150";
        };
    }

    // ------------------------------------------------------------------ trench foot (04b §3c, RULED round 9)
    static final double[] TRENCH_AT = {12, 24, 72};
    static final String[] TRENCH = {"Immersion foot", "Severe trench foot", "Trench foot, gangrene"};

    /**
     * The hour's air in the 0-15 °C band where wet feet rot [mine, EXAMPLE: the plugin has no thermometer]: spring and autumn; winter
     * only on a day of rain or fog (a thaw; clear or snowy winter days are frozen); summer never.
     */
    static boolean trenchBand(int season, String weather) {
        return switch (season) {
            case 1, 3 -> true;
            case 0 -> weather.equals("rain") || weather.equals("fog") || weather.equals("black rain");
            default -> false;
        };
    }

    /** The place's wetness (RULED round 9): rain 1, slush 0.5 (a snowy day), else 0; under a roof (the base) 0. */
    static double wetness(boolean outside, String weather) {
        if (!outside) return 0;
        return switch (weather) { case "rain", "black rain" -> 1; case "snow" -> 0.5; default -> 0; };
    }

    /**
     * An hour of the trench counter (RULED round 9): in the band, w = the wetness × (1 − the boots' water resistance), the counter + w −
     * (1 − w); out of it −1; never under 0. 12 immersion foot (Agility −1, Pain 10), 24 severe (Agility −2, Pain 30), 72 and over a
     * gangrene roll each day (a dirty wound's infection, 40 %).
     */
    static String trenchHour(Character c, int season, String weather, Random rnd) {
        if (c.dead) return null;
        double w = wetness(c.outside, weather) * (1 - c.bootsWater());
        int before = stage(c.trench, TRENCH_AT);
        c.trench = Math.max(0, c.trench + (trenchBand(season, weather) && c.outside ? 2 * w - 1 : -1));
        int now = stage(c.trench, TRENCH_AT);
        StringJoiner j = new StringJoiner("; ");
        if (now != before) {
            int[] m = new int[7];
            if (now >= 0) m[5] = now == 0 ? -1 : -2;
            set(c, "Trench foot", now < 0 ? null : TRENCH[now], m, Map.of());
            j.add(now < 0 ? "the feet are dry and well again" : TRENCH[now] + " (" + f(c.trench) + " wet hours: Agility " + m[5] + ", Pain " + (now == 0 ? 10 : 30) + ")");
        }
        if (now >= 0) c.survival.put(Rules.Stat.PAIN, Math.max(c.survival.getOrDefault(Rules.Stat.PAIN, 0.0), now == 0 ? 10 : 30));
        if (now >= 2 && Math.round(c.clock) % 24 == 0) {
            String g = Diseases.expose(c, "Wound infection", 40, false, false, rnd);
            if (g.contains("CAUGHT")) j.add("gangrene in the feet: " + g + " (amputation saves him)");
        }
        return j.length() == 0 ? null : j.toString();
    }

    // ------------------------------------------------------------------ scabies (04b §3c: only by contact, RULED round 6)
    /** Close contact with an infested person: 10 % a day (04b §5), spread over its hours. */
    static final double SCABIES_DAY = 10;

    /**
     * An hour of scabies: caught from the infested nearby; on him, the itch takes an hour of each night's sleep (the sleep bar's gain
     * × 7/8 while asleep) and the sores roll a wound infection once a week (a cut's 2 %) [the two mine].
     */
    static String scabiesHour(Character c, Random rnd) {
        if (c.dead) return null;
        if (!c.scabies && c.nearby != null)
            for (Character x : c.nearby)
                if (x != c && !x.dead && x.scabies) {
                    double ch = 100 * (1 - Math.pow(1 - SCABIES_DAY / 100, 1.0 / 24)) * (1 - Diseases.resistance(c));
                    if (100 * rnd.nextDouble() < ch) {
                        c.scabies = true;
                        set(c, "Scabies", "Scabies", new int[7], Map.of());
                        return "scabies, from " + x.name + " (itching at night; permethrin or sulphur ointment, everyone in contact at once)";
                    }
                    break;
                }
        if (!c.scabies) return null;
        if (c.sleeping) {
            double gain = c.hourly(Rules.Stat.SLEEP);
            if (gain > 0) c.survival.put(Rules.Stat.SLEEP, Math.max(0, c.survival.getOrDefault(Rules.Stat.SLEEP, 0.0) - gain / 8));
        }
        if (Math.round(c.clock) % 168 == 0) {
            String w = Diseases.expose(c, "Wound infection", 2, false, false, rnd);
            if (w.contains("CAUGHT")) return "scratched sores: " + w;
        }
        return null;
    }

    /** The ointment (permethrin, pre-war; sulphur, makeable): the mites gone (04b §3c); everyone in contact at once, or it comes back. */
    static String ointment(Character c) {
        if (!c.scabies) return "! no scabies";
        c.scabies = false;
        set(c, "Scabies", null, null, null);
        return "the ointment: the scabies gone (treat everyone in contact at once, or it comes back)";
    }

    // ------------------------------------------------------------------ tuberculosis's latent years (04b §5)
    /** 30 % infected over about three months in a room with a cougher (04b §5) [the three months mine]: the hourly share. */
    static final double TB_ROOM = 1 - Math.pow(0.7, 1.0 / (90 * 24));
    /** Latent to ill: 5 % in the first two years, then 0.1 % a year (Tiemersma; RULED in the table). */
    static final double TB_EARLY = 0.05 / (2 * Diseases.YEAR), TB_LATE = 0.001 / Diseases.YEAR;
    /** × 1.5 / 2 / 3 at the starvation stages (Minor, Advanced, Critical and worse) [the mapping mine]. */
    static final double[] TB_HUNGER = {1, 1.5, 2, 3, 3, 3};

    /**
     * An hour of tuberculosis outside the race: a cougher nearby (a tuberculosis that has shown) infects him latently (× (1 − the poison
     * resistance)); a latent infection falls ill by its rate × the hunger, and then runs the race (Diseases).
     */
    static String tbHour(Character c, Random rnd) {
        if (c.dead || Diseases.active(c, "Tuberculosis") != null) return null;
        if (c.tbLatent < 0 && c.nearby != null)
            for (Character x : c.nearby) {
                Diseases.Case k = x == c || x.dead ? null : Diseases.active(x, "Tuberculosis");
                if (k == null || k.incubation > 0) continue;
                if (rnd.nextDouble() < TB_ROOM * (1 - Diseases.resistance(c))) { c.tbLatent = c.now(); return null; }   // the GM's secret
                break;
            }
        if (c.tbLatent < 0) return null;
        int fl = Diseases.level(Rules.stage(Rules.Stat.FOOD, c.survival.getOrDefault(Rules.Stat.FOOD, c.max(Rules.Stat.FOOD)), c.max(Rules.Stat.FOOD)));
        double rate = (c.now() - c.tbLatent < 2 * Diseases.YEAR ? TB_EARLY : TB_LATE) * TB_HUNGER[Math.min(fl, 5)];
        if (rnd.nextDouble() >= rate) return null;
        c.tbLatent = -1;
        Diseases.Case k = Diseases.infect(c, Diseases.ALL.get("Tuberculosis"), rnd);
        k.incubation = 1;   // the latent years were its incubation
        return null;
    }

    // ------------------------------------------------------------------ the hour, the GM, the sheet
    /** The hourly part (trench foot, scabies, tuberculosis's latency); the world map's weather and season decide the feet. */
    static String hour(Character c, Random rnd) {
        StringJoiner j = new StringJoiner("; ");
        for (String s : new String[]{trenchHour(c, Travel.season, Travel.weather, rnd), scabiesHour(c, rnd), tbHour(c, rnd)})
            if (s != null) j.add(s);
        int sc = stage(c.scurvy, SCURVY_AT);
        if (sc >= 0 && SCURVY_HP[sc] < 0 && !c.dead) {
            c.diseaseHp += SCURVY_HP[sc];
            while (c.diseaseHp <= -1) { c.hp -= 1; c.diseaseHp += 1; }
        }
        return j.length() == 0 ? null : j.toString();
    }

    /** What the conditions are now, for the sheet and the GM ("" when none). */
    static String show(Character c) {
        List<String> l = new ArrayList<>();
        int sc = stage(c.scurvy, SCURVY_AT), tr = stage(c.trench, TRENCH_AT);
        if (c.scurvy > 0) l.add((sc < 0 ? "days without fresh food " : SCURVY[sc] + ", day ") + f(c.scurvy));
        if (c.trench > 0) l.add((tr < 0 ? "wet feet " : TRENCH[tr] + ", ") + f(c.trench) + " h");
        if (c.scabies) l.add("Scabies");
        return String.join(", ", l);
    }

    /** /sheet condition scurvy|trench <value> · scabies on|off · tb_latent on|off (the GM). */
    static String command(Character c, String[] a) {
        String usage = "! /sheet condition [scurvy <days> | trench <hours> | scabies on|off | tb_latent on|off]";
        if (a.length < 2) { String s = show(c); return s.isEmpty() ? "No condition." + (c.tbLatent >= 0 ? " Latent tuberculosis (the GM's)." : "") : s; }
        if (a.length < 3) return usage;
        boolean on = a[2].equalsIgnoreCase("on");
        switch (a[1].toLowerCase(Locale.ROOT)) {
            case "scurvy" -> {
                c.scurvy = Math.max(0, Double.parseDouble(a[2]));
                int st = stage(c.scurvy, SCURVY_AT);
                set(c, "Scurvy", st < 0 ? null : SCURVY[st], scurvyMods(st), scurvyOther(st));
            }
            case "trench" -> {
                c.trench = Math.max(0, Double.parseDouble(a[2]));
                int st = stage(c.trench, TRENCH_AT);
                int[] m = new int[7];
                if (st >= 0) m[5] = st == 0 ? -1 : -2;
                set(c, "Trench foot", st < 0 ? null : TRENCH[st], m, Map.of());
            }
            case "scabies" -> { if (on) { c.scabies = true; set(c, "Scabies", "Scabies", new int[7], Map.of()); } else ointment(c); }
            case "tb_latent" -> c.tbLatent = on ? c.now() : -1;
            default -> { return usage; }
        }
        String s = show(c);
        return s.isEmpty() ? "No condition." : s;
    }

    // ------------------------------------------------------------------ helpers
    /** The stage index of a counter (−1 under the first). */
    static int stage(double v, double[] at) {
        int i = -1;
        for (int k = 0; k < at.length; k++) if (v >= at[k]) i = k;
        return i;
    }

    /** The condition's effect in its slot; {@code name} null removes it. */
    static void set(Character c, String cond, String name, int[] mods, Map<String, Double> other) {
        String slot = "condition:" + cond;
        c.effects.removeIf(e -> e.slot().equals(slot));
        if (name != null) c.add(new Character.Effect(name, "condition", slot, false, mods, new LinkedHashMap<>(other), 1e6, null, 100));
    }

    static String f(double v) { return Diseases.f(Math.round(v * 10) / 10.0); }
}
