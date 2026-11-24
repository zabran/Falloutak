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
        if (home == 0) home = tier;
        switch (tier) {
            case 1 -> { rads(c, RADS_FOOD, j); roll(c, "Food poisoning", raw(home) ? RAW : SPOILED, j, rnd); }
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
     * A day of the base's lot for a character in the base: his share of what the base's people ate and drank that day, its rads and
     * rolls (the store's rows have no names: Tier I food counted spoiled, Tier II food irradiated, Tier II water as the condensate or
     * filtered water, with germs), and his bars refilled when the base fed everyone in full (else by the share it had).
     */
    static String baseDay(Character c, Production.Ration r, Random rnd) {
        if (c.dead || r == null) return null;
        StringJoiner j = new StringJoiner("; ");
        boolean power = "power".equals(c.tpl().needs());
        if (!power) {
            double share = r.foodNeed() <= 0 ? 0 : Math.min(1, r.foodGot() / r.foodNeed());
            if (share >= 1 - 1e-9) c.survival.put(Rules.Stat.FOOD, c.max(Rules.Stat.FOOD));
            else bump(c, Rules.Stat.FOOD, share * 24 * (1 + c.skill("Survival") / 100));
            if (share < 1 - 1e-9) j.add("the base had food for " + Math.round(100 * share) + " %");
            for (Map.Entry<String, Double> e : r.food().entrySet()) {
                String[] k = e.getKey().split("\\|");
                int tier = Integer.parseInt(k[0]), rarity = Integer.parseInt(k[1]);
                double q = e.getValue();
                if (tier <= 2) rads(c, RADS_FOOD * q, j);
                if (tier == 1) roll(c, "Food poisoning", chance(SPOILED, q), j, rnd);
                if (tier == 3 && rarity >= 2) roll(c, "Botulism", chance(KEPT_BOTULISM, q), j, rnd);
            }
        }
        if (cannotDrink(c) == null) {
            double share = r.waterNeed() <= 0 ? 0 : Math.min(1, r.waterGot() / r.waterNeed());
            if (share >= 1 - 1e-9) c.survival.put(Rules.Stat.WATER, c.max(Rules.Stat.WATER));
            else bump(c, Rules.Stat.WATER, share * 50 * (1 + c.skill("Survival") / 100));
            if (share < 1 - 1e-9) j.add("the base had water for " + Math.round(100 * share) + " %");
            double l1 = r.water()[1], l2 = r.water()[2];
            if (l1 > 0) { rads(c, RADS_WATER * l1, j); germs(c, GERMS_T1, l1, WATER_T1, j, rnd); }
            if (l2 > 0) germs(c, GERMS_T2, l2, WATER_T2, j, rnd);
        } else j.add("cannot drink: " + cannotDrink(c));
        return j.length() == 0 ? null : "the base's day: " + j;
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
