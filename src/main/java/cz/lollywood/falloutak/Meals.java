package cz.lollywood.falloutak;

import java.util.Map;
import java.util.Random;
import java.util.StringJoiner;

/**
 * Eating and drinking (Marek 11-24, verbatim: *""should players eat and drink from their packs, using a food or water item?" - yes. Also,
 * inside base, we should already have system for water and food usage, so just go based on that."*).
 * <p>
 * Every tier feeds and quenches alike; the tiers differ by harm (`04` §3.9, RULED): Tier I both harms, Tier II one, Tier III clean,
 * Tier IV clean with a bonus. A portion of food is a light meal (8 points: a resting day's 24 = three light meals, `04` §3.3), a litre
 * of water 25 (§3.2); Survival's bonus × (1 + Survival / 100) strengthens both, never the rads (RULED). Plain water counts in full to
 * Advanced Dehydration, a quarter at Critical, a tenth at Deadly (§3.2, RULED). The rolls are `04b` §5's (RULED): spoiled food 10 % a
 * meal, raw meat 3 % (food poisoning), home-preserved food 0.1 % (botulism); the water table's germs a roll a day for 2 l (a river
 * below a town 10 %, a spring 3 %), then the disease by its shares. Out of the base a character eats and drinks from his pack (the
 * Actions menu); in the base the base's daily lot feeds him ({@link Production} draws it for everyone and {@link #baseDay} gives him
 * his share of its harm).
 */
final class Meals {
    private Meals() {}

    static final double PORTION = 8, LITRE = 25;
    /** 04b §5 (RULED), % a meal: spoiled food, raw meat or fish (food poisoning), home-preserved food (botulism). */
    static final double SPOILED = 10, RAW = 3, KEPT_BOTULISM = 0.1;
    /** 04b §5's water table (RULED), the germs' roll a day for 2 l: Tier I as a river below a town, Tier II as a spring. */
    static final double GERMS_T1 = 10, GERMS_T2 = 3;
    /** Rads: Tier I water +18 a litre (New Vegas's dirty water), boiled Tier II as it was; Tier I and II food +3 a portion (04 §3.9). */
    static final double RADS_WATER = 18, RADS_FOOD = 3;
    /** The water's diseases on a failed germs' roll (04b §5: the river's shares; cholera only in an outbreak, the GM's). */
    static final Object[][] WATER_T1 = {{"Food poisoning", 60.0}, {"Dysentery", 20.0}, {"Hepatitis A", 8.0}, {"Typhoid", 7.0}};
    /** A spring's two [the shares mine]. */
    static final Object[][] WATER_T2 = {{"Food poisoning", 75.0}, {"Dysentery", 25.0}};

    /** The tier a named food or water is MADE at (the catalogue's), so a stale portion is known by its name; 0 if unknown. */
    static int homeTier(String type, String name) {
        Production.ItemType ty = Production.TYPES.get(type);
        if (ty == null || name == null || name.isEmpty()) return 0;
        for (Production.Named n : ty.items) if (n.name.equals(name)) return n.tier;
        return 0;
    }

    /** Can he drink: a synthetic body drinks nothing; a disease's stage may keep water down (04b: an IV only). Null = yes. */
    static String cannotDrink(Character c) {
        if (c.dead) return "dead";
        if ("power".equals(c.tpl().needs())) return "a synthetic body drinks nothing";
        if (c.other("no_drink") > 0) return "cannot keep water down (a disease's stage): only an IV gets water in";
        return null;
    }

    /** Can he eat: a synthetic body's food is its power (a charging point). Null = yes. */
    static String cannotEat(Character c) {
        if (c.dead) return "dead";
        if ("power".equals(c.tpl().needs())) return "a synthetic body eats nothing: its food is its power (a charging point)";
        return null;
    }

    /** What a portion or a litre does, for the menu's line. */
    static String harmText(String type, int tier, int rarity, String name) {
        int home = homeTier(type, name);
        if (type.equals("Water")) return switch (tier) {
            case 1 -> "+18 rads a litre and the germs' roll (" + GERMS_T1 + " % a day)";
            case 2 -> boiled(name) ? "+18 rads a litre (boiled: the germs gone)" : "the germs' roll (" + GERMS_T2 + " % a day), no rads";
            case 3 -> "clean";
            default -> "clean (a bonus: the item rounds)";
        };
        return switch (tier) {
            case 1 -> "+3 rads and food poisoning " + (raw(home) ? RAW : SPOILED) + " %";
            case 2 -> home >= 3 ? "stale: food poisoning " + SPOILED + " %, no rads" : "+3 rads";
            case 3 -> rarity >= 2 ? "clean, home-preserved: botulism " + KEPT_BOTULISM + " %" : "clean";
            default -> "clean (a bonus: the item rounds)";
        };
    }

    private static boolean raw(int home) { return home == 1; }
    private static boolean boiled(String name) { return name != null && (name.startsWith("Boiled") || name.startsWith("Chlorinated")); }

    /** He eats one portion from his pack. */
    static String eat(Character c, int tier, int rarity, String name, Random rnd) {
        String no = cannotEat(c);
        if (no != null) return "! " + no;
        double got = PORTION * (1 + c.skill("Survival") / 100);
        bump(c, Rules.Stat.FOOD, got);
        StringJoiner j = new StringJoiner("; ");
        j.add("Ate " + (name.isEmpty() ? "Food " + Rules.roman(tier) : name) + ": Food +" + Math.round(got));
        int home = homeTier("Food", name);
        boolean named = home > 0;
        if (!named) home = tier;
        switch (tier) {
            case 1 -> { rads(c, RADS_FOOD, j); roll(c, "Food poisoning", named && raw(home) ? RAW : SPOILED, j, rnd); }
            case 2 -> { if (home >= 3) roll(c, "Food poisoning", SPOILED, j, rnd); else rads(c, RADS_FOOD, j); }
            case 3 -> { if (rarity >= 2) roll(c, "Botulism", KEPT_BOTULISM, j, rnd); }
            default -> { }
        }
        return j.toString();
    }

    /** He drinks a litre from his pack. */
    static String drink(Character c, int tier, String name, Random rnd) {
        String no = cannotDrink(c);
        if (no != null) return "! " + no;
        double got = LITRE * (1 + c.skill("Survival") / 100) * plainWaterShare(c);
        bump(c, Rules.Stat.WATER, got);
        StringJoiner j = new StringJoiner("; ");
        j.add("Drank a litre of " + (name.isEmpty() ? "Water " + Rules.roman(tier) : name) + ": Water +" + Math.round(got)
                + (plainWaterShare(c) < 1 ? " (too dry for plain water to count in full: rehydration salts or an IV)" : ""));
        switch (tier) {
            case 1 -> { rads(c, RADS_WATER, j); germs(c, GERMS_T1, 1, WATER_T1, j, rnd); }
            case 2 -> { if (boiled(name)) rads(c, RADS_WATER, j); else germs(c, GERMS_T2, 1, WATER_T2, j, rnd); }
            default -> { }
        }
        return j.toString();
    }

    /** 04 §3.2 (RULED): plain water counts in full to Advanced Dehydration, a quarter at Critical, a tenth at Deadly. */
    static double plainWaterShare(Character c) {
        int lv = Diseases.level(Rules.stage(Rules.Stat.WATER, c.survival.getOrDefault(Rules.Stat.WATER, c.max(Rules.Stat.WATER)), c.max(Rules.Stat.WATER)));
        return lv >= 4 ? 0.1 : lv == 3 ? 0.25 : 1;
    }

    /**
     * A day in the base for a character (Marek 11-24, verbatim: *"technically, we should simulate how each person eats in there, because
     * Survival adds bonus to how much people gain from food. And other genes etc. also change that."*): what he lost in 24 hours by
     * his own body (the template's and the genes' factors, the effects: {@link Character#hourly}) plus what his bar lacks, in portions
     * and litres of HIS size (× (1 + Survival / 100); plain water's share when he is very dry), taken from the base's store one by one
     * in the base's order (the filtration's spare clean water first), each with its own harm; the parts carried to the next day. If
     * the store runs out, the bar loses what the day's hold gave him and falls from then on ({@code baseShortFood}).
     */
    static String baseDay(Character c, Production pr, Base base, Random rnd) {
        if (c.dead) return null;
        StringJoiner j = new StringJoiner("; ");
        double rads0 = c.survival.getOrDefault(Rules.Stat.RADS, 0.0);
        double surv = 1 + c.skill("Survival") / 100;
        int[] ateTier = new int[5], drankTier = new int[5];
        if (cannotEat(c) == null) {
            double gain = PORTION * surv, need = dayNeed(c, Rules.Stat.FOOD) / gain;
            double lack = Math.max(0, c.max(Rules.Stat.FOOD) - c.survival.getOrDefault(Rules.Stat.FOOD, c.max(Rules.Stat.FOOD))) / gain;
            double owed = need + c.mealCarry;
            int n = (int) Math.floor(owed + 1e-9), extra = (int) Math.floor(lack + 1e-9);
            c.mealCarry = owed - n;
            c.baseShortFood = false;
            for (int k = 0; k < n + extra; k++) {
                int[] tr = pr.takeMeal(base);
                if (tr == null) {
                    int missed = Math.min(n, n + extra - k);   // the day's own portions not had: the hold gave them, take them back
                    bump(c, Rules.Stat.FOOD, -missed * gain);
                    c.baseShortFood = true;
                    j.add("the store had no food for " + (n + extra - k) + " of your " + (n + extra) + " portions (short)");
                    break;
                }
                ateTier[tr[0]]++;
                String r = eat(c, tr[0], tr[1], "", rnd);
                if (r.contains("CAUGHT")) j.add(r.substring(r.indexOf("; ") + 2));
            }
        }
        String noDrink = cannotDrink(c);
        if (noDrink == null) {
            double gain = LITRE * surv * plainWaterShare(c), need = dayNeed(c, Rules.Stat.WATER) / Math.max(1e-9, LITRE * surv);
            double lack = Math.max(0, c.max(Rules.Stat.WATER) - c.survival.getOrDefault(Rules.Stat.WATER, c.max(Rules.Stat.WATER))) / Math.max(1e-9, gain);
            double owed = need + c.drinkCarry;
            int n = (int) Math.floor(owed + 1e-9), extra = (int) Math.ceil(lack - 1e-9);
            c.drinkCarry = owed - n;
            c.baseShortWater = false;
            for (int k = 0; k < n + extra; k++) {
                int t = pr.takeDrink(base);
                if (t == 0) {
                    int missed = Math.min(n, n + extra - k);
                    bump(c, Rules.Stat.WATER, -missed * LITRE * surv);
                    c.baseShortWater = true;
                    j.add("the base had no water for " + (n + extra - k) + " of your " + (n + extra) + " l (short)");
                    break;
                }
                drankTier[t]++;
                String r = drink(c, t, "", rnd);
                if (r.contains("CAUGHT")) j.add(r.substring(r.indexOf("; ") + 2));
            }
        } else if (!noDrink.startsWith("a synthetic")) j.add("cannot drink: " + noDrink);
        StringJoiner what = new StringJoiner(", ");
        for (int t = 4; t >= 1; t--) if (ateTier[t] > 0) what.add(ateTier[t] + " Food " + Rules.roman(t));
        for (int t = 4; t >= 1; t--) if (drankTier[t] > 0) what.add(drankTier[t] + " l of Tier " + Rules.roman(t));
        // 04b §3c (the shelter only, RULED round 10): a portion of Tier III or IV food = a vitamin C day
        String sc = Conditions.scurvyDay(c, ateTier[3] + ateTier[4] > 0 || c.now() - c.vitaminAt <= 24, rnd);   // 04i: or a vitamin C tablet
        if (sc != null) j.add(sc);
        double rads = c.survival.getOrDefault(Rules.Stat.RADS, 0.0) - rads0;
        if (rads > 1e-9) what.add("+" + Diseases.f(rads) + " rads");
        if (what.length() > 0) j.add("you ate and drank " + what);
        return j.length() == 0 ? null : "The base's day: " + j;
    }

    /** What his body loses in 24 hours as it is now (his template, genes, effects, the sweat), never less than 0. */
    static double dayNeed(Character c, Rules.Stat st) {
        boolean bf = c.baseFood, bw = c.baseWater;
        c.baseFood = c.baseWater = false;
        try { return Math.max(0, -c.hourly(st) * 24); } finally { c.baseFood = bf; c.baseWater = bw; }
    }

    /** The base's people by their species: the average food and water factor of their templates (a synthetic body 0 and 0). */
    static void npcFactors(Production pr, java.util.List<Population.Person> people) { npcFactors(pr, people, g -> 0); }

    /**
     * The same with each person's group's Survival (Marek 11-24, verbatim: *"groups exist mostly for the skills values, so please add
     * it."*): a portion and a litre give him × (1 + Survival / 100), as for a character, so he needs that much less.
     */
    static void npcFactors(Production pr, java.util.List<Population.Person> people, java.util.function.ToDoubleFunction<String> survivalOf) {
        double f = 0, w = 0;
        int n = 0;
        java.util.Map<String, Double> surv = new java.util.HashMap<>();
        for (Population.Person p : people) {
            Data.Template t = Data.TEMPLATES.getOrDefault(p.species, Data.TEMPLATES.get("Human"));
            if (t == null) continue;
            boolean power = "power".equals(t.needs());
            double gain = 1 + Math.max(0, surv.computeIfAbsent(p.group == null ? "" : p.group, g -> g.isEmpty() ? 0 : survivalOf.applyAsDouble(g))) / 100;
            f += power ? 0 : t.foodFactor() / gain;
            w += power ? 0 : t.waterFactor() / gain;
            n++;
        }
        pr.mealFactor = n == 0 ? 1 : f / n;
        pr.drinkFactor = n == 0 ? 1 : w / n;
    }

    /** The chance of at least one hit in q meals of p % each. */
    static double chance(double pct, double q) { return 100 * (1 - Math.pow(1 - pct / 100, q)); }

    private static void rads(Character c, double rads, StringJoiner j) {
        if (rads <= 0) return;
        double got = rads * (1 - Math.min(100, c.resistances()[1]) / 100);
        if (got <= 0) return;
        c.survival.put(Rules.Stat.RADS, Math.min(2000, c.survival.getOrDefault(Rules.Stat.RADS, 0.0) + got));
        j.add("+" + Diseases.f(got) + " rads");
    }

    private static void roll(Character c, String disease, double pct, StringJoiner j, Random rnd) {
        if (pct <= 0) return;
        String r = Diseases.expose(c, disease, pct, false, false, rnd);
        if (r.contains("CAUGHT")) j.add(r);
    }

    /** The water's germs: a roll a day for 2 l (`pctDay`), then the disease by its shares, its own roll × (1 − resistance). */
    static void germs(Character c, double pctDay, double litres, Object[][] mix, StringJoiner j, Random rnd) {
        if (100 * rnd.nextDouble() >= chance(pctDay, litres / 2)) return;
        double total = 0;
        for (Object[] m : mix) total += (Double) m[1];
        double u = total * rnd.nextDouble();
        for (Object[] m : mix) {
            u -= (Double) m[1];
            if (u < 0) { roll(c, (String) m[0], 100, j, rnd); return; }
        }
    }

    private static void bump(Character c, Rules.Stat st, double v) {
        c.survival.put(st, Math.max(0, Math.min(c.max(st), c.survival.getOrDefault(st, c.max(st)) + v)));
    }
}
