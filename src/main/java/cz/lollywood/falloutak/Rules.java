package cz.lollywood.falloutak;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * The sheet's formulas, pure Java: the workbook's (Character, Species, Effects; Marek's 2023 sheet, read 2026-11-13) with the
 * rulings of 2026-11-13/14 (`_notes/Fallout/02-CHARACTER-SHEET.md`, `02c-TRAITS-PERKS.md`). Values the rules do not fix yet are
 * marked EXAMPLE where they are used.
 */
public final class Rules {
    private Rules() {}

    public static final String[] SPECIAL = {"Strength", "Perception", "Endurance", "Charisma", "Intelligence", "Agility", "Luck"};
    public static final int S = 0, P = 1, E = 2, C = 3, I = 4, A = 5, L = 6;
    public static final int ATTRIBUTE_POINTS = 40;

    /** A skill: its name and its base from the attributes (the workbook's formula, written out for the tooltip). */
    public record Skill(String name, String formula, ToDoubleFunction<int[]> base) {}

    public static final List<Skill> SKILLS = List.of(
            new Skill("Athletics", "2 A + 2 S + 2 E", a -> 2 * a[A] + 2 * a[S] + 2 * a[E]),
            new Skill("Medicine", "P + I", a -> a[P] + a[I]),
            new Skill("Computer Science", "4 I", a -> 4 * a[I]),
            new Skill("Sneak", "3 A", a -> 3 * a[A]),
            new Skill("Survival", "P + A + E", a -> a[P] + a[A] + a[E]),
            new Skill("Crafting", "P + A", a -> a[P] + a[A]),
            new Skill("Engineering", "P + 2 I", a -> a[P] + 2 * a[I]),
            new Skill("Chemistry", "2 P + A", a -> 2 * a[P] + a[A]),
            new Skill("Biology", "E + S + P", a -> a[E] + a[S] + a[P]),
            new Skill("Pilot", "2 P + 2 A", a -> 2 * a[P] + 2 * a[A]),
            new Skill("Economy", "4 C", a -> 4 * a[C]),
            new Skill("Rhetorics", "5 C", a -> 5 * a[C]),
            new Skill("Gambling", "5 L", a -> 5 * a[L]),
            new Skill("Guns", "2 A + P", a -> 2 * a[A] + a[P]),
            new Skill("Leadership", "4 C + P", a -> 4 * a[C] + a[P]));

    public static int skillIndex(String name) {
        for (int i = 0; i < SKILLS.size(); i++) if (SKILLS.get(i).name().equals(name)) return i;
        throw new IllegalArgumentException(name);
    }

    /** A combination ("weapon") skill: a formula over the 15 skills' values. */
    public record Combo(String name, String formula, ToDoubleFunction<double[]> value) {}

    private static double sk(double[] v, String n) { return v[skillIndex(n)]; }

    public static final List<Combo> COMBOS = List.of(
            new Combo("Energy Weapons", "½ Guns + ½ Engineering + 15", v -> sk(v, "Guns") / 2 + sk(v, "Engineering") / 2 + 15),
            new Combo("Bows", "¾ Guns + ¾ Crafting − 10", v -> 0.75 * sk(v, "Guns") + 0.75 * sk(v, "Crafting") - 10),
            new Combo("Throwing", "= Athletics", v -> sk(v, "Athletics")),
            new Combo("Unarmed", "= Athletics", v -> sk(v, "Athletics")),
            new Combo("Traps", "= Survival", v -> sk(v, "Survival")),
            new Combo("Explosives", "= Chemistry", v -> sk(v, "Chemistry")),
            new Combo("Knives", "¾ Medicine + ¾ Survival − 10", v -> 0.75 * sk(v, "Medicine") + 0.75 * sk(v, "Survival") - 10),
            new Combo("Hammers", "Crafting + Athletics − 25", v -> sk(v, "Crafting") + sk(v, "Athletics") - 25),
            new Combo("Spears", "= Survival", v -> sk(v, "Survival")),
            new Combo("Axes", "Biology + Athletics − 25", v -> sk(v, "Biology") + sk(v, "Athletics") - 25),
            new Combo("Swords", "⅔ Survival + ⅔ Athletics", v -> 2.0 / 3 * sk(v, "Survival") + 2.0 / 3 * sk(v, "Athletics")),
            new Combo("Techno Weapons", "½ Engineering + ½ Athletics + 15", v -> sk(v, "Engineering") / 2 + sk(v, "Athletics") / 2 + 15),
            new Combo("Grenades", "⅔ Chemistry + ⅔ Athletics", v -> 2.0 / 3 * sk(v, "Chemistry") + 2.0 / 3 * sk(v, "Athletics")),
            new Combo("Rockets", "⅔ Guns + ⅔ Chemistry", v -> 2.0 / 3 * sk(v, "Guns") + 2.0 / 3 * sk(v, "Chemistry")));

    /** The price in skill points of the next % at this value (Marek 11-13: 1 / 2 / 4 a % to 100, to 200, above; tags x2). */
    public static int pricePerPercent(double value) {
        return value < 100 ? 1 : value < 200 ? 2 : 4;
    }

    // ------------------------------------------------------------------ derived
    public static int hpMax(int[] a, int speciesHp) { return a[S] + 2 * a[E] + speciesHp; }
    public static int ap(int[] a) { return 5 + a[A] / 2; }
    public static int ac(int[] a) { return a[A]; }
    public static int initiative(int[] a) { return 2 * a[P]; }
    public static double carry(int[] a, double athletics) { return 25 + 25 * a[S] + athletics; }

    /** The same in kg (round 11: *"Fallout 2's pounds - covert it to kg."*; round 12: *"cruder "×0.5""*): × 0.5. */
    public static double carryKg(int[] a, double athletics) { return carry(a, athletics) * Character.LB; }

    /**
     * The load's tier (`09b` round 11, Marek's): 0 within the capacity; 1 up to +50 % over (half speed, tired faster, more food, water and
     * oxygen; with Strong Back nothing up to +25 %, `02c`); 2 from +50 to +100 % (the AP halved as well); 3 more (no AP, as paralysed).
     */
    public static int loadTier(double kg, double capacity, boolean strongBack) {
        if (capacity <= 0) return kg > 1e-9 ? 3 : 0;
        double r = kg / capacity;
        if (r <= (strongBack ? 1.25 : 1) + 1e-9) return 0;
        return r <= 1.5 + 1e-9 ? 1 : r <= 2 + 1e-9 ? 2 : 3;
    }
    public static int meleeBonus(int[] a) { return Math.max(a[S] - 5, 1); }

    /** The body's melee damage (a template's bonus damage, the workbook's Species column; a mutation's melee dial, 03b §7a): x (1 + it), never below 0. */
    public static double meleeFactor(double bonus) { return Math.max(0, 1 + bonus); }
    /** Ruled 11-13: the workbook counted Medicine twice; the /20 term is Survival. */
    public static int healingRate(int[] a, double medicine, double survival) {
        return (int) Math.floor(Math.max(a[E] / 3.0 + medicine / 50 + survival / 20, 1));
    }
    public static double critChance(int[] a, double sneak) { return a[L] + sneak / 10; }
    public static int skillPointsPerLevel(int[] a, int species) { return 2 * a[I] + species; }

    // ------------------------------------------------------------------ part 6: experience (06b §5.4-§5.5, 06 §2, RULED 11-20)
    /**
     * A level L → L+1 costs 250 × L × √L (06b §5.5, Marek 11-20: *"this seems a lot better, accepted. I think it would be usable. On
     * the conditions that PC do not die often."*), for the PCs AND the groups (*"leader players can optimize the groups just like
     * player characters"*). It replaced Fallout 2's 1000 × n (n − 1) / 2.
     */
    public static double levelStep(int l) { return 250.0 * l * Math.sqrt(l); }
    /** The XP to reach level n, the steps 1 … n−1 rounded: L2 250, L3 957, L5 4 256, L10 27 762, L15 79 995, L30 472 575. */
    public static long xpForLevel(int n) {
        double s = 0;
        for (int l = 1; l < n; l++) s += levelStep(l);
        return Math.round(s);
    }
    /** The level an XP total reaches (at least 1). */
    public static int levelFor(double xp) {
        int n = 1;
        while (n < 500 && xpForLevel(n + 1) <= xp) n++;
        return n;
    }
    /** The level with the way to the next one as a fraction (a group's level, 06 §2.1). */
    public static double levelExact(double xp) {
        int n = levelFor(xp);
        double a = xpForLevel(n), b = xpForLevel(n + 1);
        return n + Math.max(0, xp - a) / (b - a);
    }
    /** A kill: 3.1 × HP^1.09 (06b §5.4.1: Fallout 1/2's own creature XP, fitted; Marek 11-20: *"OK, I am in"*). */
    public static long killXp(double hp) { return hp <= 0 ? 0 : Math.round(3.1 * Math.pow(hp, 1.09)); }
    /**
     * Each one's share of the team's XP: 1 / (1 + (n − 1) × max(0, 1 − L / 250)), L the team leader's Leadership, the leader
     * included (06b §5.4.1: Mark's own rule of 2023, fitted on his four points; RULED *"This leadership effect should work on the
     * leader as well."*). L 0: 1/n; six people at L 100: 1/4, at L 200: 1/2; from L 250 nobody loses anything.
     */
    public static double xpShare(int n, double leadership) {
        if (n <= 1) return 1;
        return 1 / (1 + (n - 1) * Math.max(0, 1 - leadership / 250));
    }
    /** The minimum of a check that could fail (06b §5.4.2, Marek 11-20: *"probably, it seems fine"*). */
    public static final int CHECK_XP_MIN = 10;
    /** A passed check: its difficulty (the penalty in % points: a −40 % lock = 40), at least 10, Doctor doubled (06b §5.4.2). */
    public static long checkXp(double difficulty, boolean doctor) {
        long x = Math.max(CHECK_XP_MIN, Math.round(Math.abs(difficulty)));
        return doctor ? 2 * x : x;
    }
    /** Reaching a non-generic area: 100 × the areas found before it, to EACH PC (06b §5.4.5: the 1st 0, the 10th 900). */
    public static long discoveryXp(int foundBefore) { return 100L * Math.max(0, foundBefore); }
    /** A finished schematic: 100 × tier × rarity to each researcher (06b §5.4.6: a T1 R1 100, a T3 R2 600). */
    public static long researchXp(int tier, int rarity) { return 100L * Math.max(0, tier) * Math.max(0, rarity); }
    /**
     * A member-hour of work in the base: 1 XP (06 §2.1, the pace RULED *"seems OK"*); idling, healing, sleep and sickness 0 (Marek
     * 11-20: *"yes"*). Work outside (scavenging, patrols) belongs to the local map, which has no rules yet (Marek 11-20).
     */
    public static int workXp(boolean working) { return working ? 1 : 0; }
    /** A lesson hour: 1 + the teacher's Leadership / 25 XP a student (06 §2.5, RULED round 4: 0 → 1, 50 → 3, 100 → 5). */
    public static double lessonXp(double leadership) { return 1 + Math.max(0, leadership) / 25; }
    /** A class: 1 + the teacher's Leadership / 10 students (06 §2.5). */
    public static int classSize(double leadership) { return 1 + (int) Math.floor(Math.max(0, leadership) / 10); }
    /** At most 8 lesson hours a member a week (06 §2.5). */
    public static final int LESSON_HOURS_A_WEEK = 8;

    // ------------------------------------------------------------------ perks' gates (02c §2: they read permanent values, Marek 11-14)
    /** The sciences (02c §2: *"any science = Computer Science, Engineering, Chemistry, Biology or Medicine"*). */
    public static final List<String> SCIENCES = List.of("Computer Science", "Engineering", "Chemistry", "Biology", "Medicine");
    /**
     * The melee families (a gate's "a melee family"): Marek 11-20, *"also unarmed. And I think technoweapons are technically also
     * melee (most of them)."*
     */
    public static final List<String> MELEE = List.of("Unarmed", "Knives", "Hammers", "Spears", "Axes", "Swords", "Techno Weapons");
    /**
     * The weapon skills, a gate's "any weapon skill" and Weapon Master's families: Guns and the 14 combination skills. Athletics and
     * Chemistry count through them (Marek 11-20: *"athletics is unarmed (your body is a weapon) and chemistry is also explosives"*:
     * Unarmed = Athletics, Explosives = Chemistry).
     */
    public static List<String> weaponSkills() {
        List<String> l = new java.util.ArrayList<>(List.of("Guns"));
        for (Combo co : COMBOS) l.add(co.name());
        return l;
    }

    /** A skill's or a weapon skill's value from the 15 skills' values. */
    public static double skillValue(double[] skills, String name) {
        for (int i = 0; i < SKILLS.size(); i++) if (SKILLS.get(i).name().equals(name)) return skills[i];
        for (Combo co : COMBOS) if (co.name().equals(name)) return co.value().applyAsDouble(skills);
        return 0;
    }

    /** The best value a gate alternative can use. */
    static double best(double[] skills, Data.Alt a) {
        List<String> names = switch (a.any()) {
            case "skill" -> SKILLS.stream().map(Skill::name).toList();
            case "weapon" -> weaponSkills();
            case "science" -> SCIENCES;
            case "melee" -> MELEE;
            default -> a.skills();
        };
        double b = Double.NEGATIVE_INFINITY;
        for (String n : names) b = Math.max(b, skillValue(skills, n));
        return b;
    }

    /** A perk's gate is met when any of its alternatives is (02c §2). */
    public static boolean gateMet(double[] skills, Data.Perk p) {
        for (Data.Alt a : p.alts()) if (best(skills, a) >= a.value()) return true;
        return false;
    }

    /** Ruled 11-14 (02c §7): dying from 0 HP down to −Endurance, dead below. */
    public static int deathFloor(int[] a) { return -a[E]; }

    // ------------------------------------------------------------------ survival (the workbook's Character rows 14-22, Effects)
    /** OXYGEN (03b §3c, RULED 11-18): the held breath, counted by ROUNDS (Marek: *"Oxygen (in body)"*), not by hours. */
    public enum Stat { WATER, FOOD, SLEEP, POISON, RADS, HYPOTHERMIA, HYPERTHERMIA, BLOOD, PAIN, OXYGEN }

    /** A human holds his breath a minute = 10 rounds (03b §3c); the Breath genes, Oxygen reserve and the Lung Filter raise it. */
    public static final int HUMAN_BREATH = 10;
    /** Oxygen back a round in clean air (03b §3c). */
    public static final double OXYGEN_REFILL = 5;

    /**
     * Trained lungs on top of the held breath (Marek 11-18, round 24: Athletics, *"swimmers"*, and Endurance, as the other maxima take
     * Survival or Athletics with E) [the numbers mine]: + Athletics / 10 + (E − 5) rounds. The average adult (Athletics 30, E 5) 13
     * rounds (78 s), a trained swimmer (Athletics 100, E 7) 22 (2 minutes 12 s), a frail one (Athletics 15, E 2) 8.
     */
    public static double breathBonus(double athletics, int endurance) { return athletics / 10 + (endurance - 5); }

    /**
     * Repair by combining (New Vegas: up to 100 % whatever the skill; Fallout 3's efficiency, fitted to the wiki's two examples in 05
     * §5b): the better + 5 + 5 % of the other + 0.15 × the skill.
     */
    public static double repairCombine(double a, double b, double skill) {
        return Math.min(100, Math.max(a, b) + 5 + 0.05 * Math.min(a, b) + 0.15 * skill);
    }

    /** Nobody holds less than half a minute [mine]. */
    public static final int MIN_BREATH = 5;

    /**
     * The survival maxima, the workbook's own (Character!C14-C20; the NPC script's CalculateSurvivalMax the same): water 75 + 5 E +
     * Survival / 2, food 50 + 10 E + Survival / 2, sleep 50 + 10 E, poison 40 + 3 x the species' HP bonus, rads 1000, cold and heat
     * the Survival POINTS + (the species' HP bonus + 3) x 4, blood 4 x HP max, pain 100. (Before 0.7.0 water and food took Athletics
     * and poison, cold and heat left out the species' bonus, a human's 15: my slips, found by the NPC script, 11-14.)
     */
    public static double max(Stat st, int[] a, double survival, double survivalPoints, int hpMax, int speciesHp) {
        return switch (st) {
            case WATER -> 75 + 5 * a[E] + survival / 2;
            case FOOD -> 50 + 10 * a[E] + survival / 2;
            case SLEEP -> 50 + 10 * a[E];
            case POISON -> 40 + 3 * speciesHp;
            case RADS -> 1000;
            case HYPOTHERMIA, HYPERTHERMIA -> survivalPoints + (speciesHp + 3) * 4;
            case BLOOD -> hpMax * 4;
            case PAIN -> 100;
            case OXYGEN -> HUMAN_BREATH;   // the character's own: Character.max (genes, implants)
        };
    }

    /** The stage's name, or null at stage 0 (the stat is fine). */
    public static String stage(Stat st, double v, double max) {
        double f = v / max;
        return switch (st) {
            case WATER -> falling(f, "Dehydration");
            case FOOD -> falling(f, "Starvation");
            case SLEEP -> falling(f, "Sleep Deprivation");
            case BLOOD -> falling(f, "Anemia");
            case POISON -> v <= 0 ? null : f < .2 ? "Minor Poisoning" : f < .4 ? "Advanced Poisoning" : f < .6 ? "Critical Poisoning"
                    : f < .8 ? "Deadly Poisoning" : "Fatal Poisoning";
            case RADS -> v < 150 ? null : v < 300 ? "Minor Radiation Poisoning" : v < 450 ? "Advanced Radiation Poisoning"
                    : v < 600 ? "Critical Radiation Poisoning" : v < 1000 ? "Deadly Radiation Poisoning" : "Fatal Radiation Poisoning";
            case HYPOTHERMIA -> rising(f, "Hypothermia");
            case HYPERTHERMIA -> rising(f, "Hyperthermia");
            case PAIN -> v <= 0 ? null : v <= 25 ? "Minor Pain" : v <= 50 ? "Advanced Pain" : v <= 75 ? "Critical Pain" : "Paralyzed by Pain";
            // 03b §3c (RULED 11-18, round 21): under 50 % P −1 I −1, under 25 % A −2 I −2, 0 unconscious
            case OXYGEN -> v <= 0 ? "Deadly Hypoxia" : f < .25 ? "Critical Hypoxia" : f < .5 ? "Minor Hypoxia" : null;
        };
    }

    /** The Hypoxia stages' numbers (03b §3c): Critical keeps Minor's P −1 [the reading mine: a stage never gets better as it worsens]. */
    static final Map<String, int[]> HYPOXIA = Map.of(
            "Minor Hypoxia", new int[]{0, -1, 0, 0, -1, 0, 0},
            "Critical Hypoxia", new int[]{0, -1, 0, 0, -2, -2, 0},
            "Deadly Hypoxia", new int[]{0, -1, 0, 0, -2, -2, 0});

    /**
     * The limit gates (part 4 §3.5, RULED 11-15): Minor and Advanced stages cannot take an attribute below 1; Critical, Deadly and
     * Fatal can. Pain's and Hypoxia's first stages are Minor ones too.
     */
    public static boolean gated(String stage) { return stage.startsWith("Minor") || stage.startsWith("Advanced"); }

    /** 0 = fine, 1 minor ... 4 deadly or worse: the colour of the sidebar line. */
    public static int level(String stage) {
        if (stage == null || stage.endsWith("(glowing)")) return 0;   // the glowing one's bonus (03b §7b, round 9)
        if (stage.startsWith("Minor")) return 1;
        if (stage.startsWith("Advanced")) return 2;
        if (stage.startsWith("Critical")) return 3;
        return 4;
    }

    private static String falling(double f, String what) {
        return f >= .8 ? null : f >= .6 ? "Minor " + what : f >= .4 ? "Advanced " + what : f >= .2 ? "Critical " + what : "Deadly " + what;
    }

    private static String rising(double f, String what) {
        return f <= .2 ? null : f <= .4 ? "Minor " + what : f <= .6 ? "Advanced " + what : f <= .8 ? "Critical " + what : "Deadly " + what;
    }

    /**
     * The hourly change (the workbook's column E), for the demo clock. Blood regrows 4 a day; bleeding is per ROUND now (Andor's
     * rule, {@link #bleedingPerRound}). Pain falls 3 an hour only asleep or under anaesthetics: the workbook's note on Pain
     * (*"Lost with anestetics or sleep (-3/hour)"*; its formula took the 3 off every hour).
     */
    public static double hourly(Stat st, double hyperthermia, boolean sleeping, double locationRads) {
        return switch (st) {
            case WATER -> -2 - 0.1 * hyperthermia;
            case FOOD -> -1;
            case SLEEP -> sleeping ? 4 : -1;
            case POISON -> 0;
            case RADS -> locationRads;
            case HYPOTHERMIA, HYPERTHERMIA -> 0;
            case BLOOD -> 1.0 / 6;
            case PAIN -> sleeping ? -3 : 0;
            case OXYGEN -> 0;   // by rounds (Hud.round), never by hours
        };
    }

    // ------------------------------------------------------------------ 03b §3c: gases, deafness, the heart (RULED 11-18, rounds 19-21)
    /**
     * A gas of 03b §3c (RULED with its numbers, round 21; game numbers only), each round inside unprotected, at strength 1-3 (× the
     * row). {@code blood}: the Blood genes' factor acts on it (CO, sewer gas); {@code withCo}: it carries CO as well (every smoke);
     * {@code hourlyPoison}: a slow one (mercury), an hour; {@code ignites}: a spark or a flame sets it off (a grenade's blast);
     * {@code bad}: bad air (the Oxygen refills only to half; no mask, no filter helps).
     */
    public record Gas(String name, String where, double oxygen, double poison, double pain, int p, int toHit, int ap, int burnDice,
                      boolean blood, boolean withCo, double hourlyPoison, boolean ignites, boolean bad, String text) {}

    public static final Map<String, Gas> GASES = new LinkedHashMap<>();

    static {
        gas(new Gas("smoke", "any burning indoors", 1, 2, 0, -1, -10, 0, 0, false, true, 0, false, false,
                "Oxygen −1, Poison +2, P −1, to hit −10 a round; CO inside it too; it HIDES: a ranged shot into, out of or through it −10"));
        gas(new Gas("carbon monoxide", "inside every smoke; engines, generators and stoves run indoors", 0, 3, 0, 0, 0, 0, 0, true, false, 0, false, false,
                "Poison +3 a round, no smell, no warning; afterwards Poison cures at half speed for a day (full on bottled oxygen)"));
        gas(new Gas("bad air", "cellars, wells, flooded tunnels", 0, 0, 0, 0, 0, 0, 0, false, false, 0, false, true,
                "the Oxygen refills only to half; after an hour P −1, I −1"));
        gas(new Gas("sewer gas", "sewers, cesspits, tanneries", 0, 1, 0, 0, 0, 0, 0, true, false, 0, true, false,
                "Poison +1 a round; the nose stops warning after a while; it burns"));
        gas(new Gas("sewer gas pocket", "a closed pit, a sealed sewer", 0, 4, 0, 0, 0, 0, 0, true, false, 0, true, false,
                "Poison +4 a round; it burns"));
        gas(new Gas("methane", "mines, sewers", 0, 0, 0, 0, 0, 0, 0, false, false, 0, true, false,
                "harmless to breathe; a spark or a flame sets it off: a blast as a grenade's over the pocket"));
        gas(new Gas("chlorine", "the chemical works' tanks", 0, 3, 5, -2, 0, 0, 0, false, false, 0, false, false,
                "P −2, Pain +5, Poison +3 a round; heavier than air: it pools in cellars and hollows"));
        gas(new Gas("ammonia", "the chemical works, the cold stores of breweries and dairies", 0, 2, 5, -2, 0, 0, 0, false, false, 0, false, false,
                "P −2, Pain +5, Poison +2 a round; lighter than air: it rises"));
        gas(new Gas("riot gas", "a faction's weapon (the army remnants, the Union's checkpoints)", 0, 0, 0, -3, -20, -2, 0, false, false, 0, false, false,
                "P −3, to hit −20, AP −2 inside and for 10 minutes after; no Poison"));
        gas(new Gas("mercury vapour", "the chemical works' old halls", 0, 0, 0, 0, 0, 0, 0, false, false, 1, false, false,
                "slow: Poison +1 an hour; after a week of it, I −1 and a tremor (Crafting −10 %) until treated"));
        gas(new Gas("acid fog", "the anomaly", 1, 2, 0, -2, 0, 0, 1, false, false, 0, false, false,
                "1d6 burns a round on bare skin (no mask stops them), P −2; the lungs as smoke (Oxygen −1, Poison +2); it hides as smoke: a ranged shot into, out of or through it −10"));
    }

    static {   // not a gas for the lungs: a cloud that HIDES (Marek 11-19 on the Trhač: "it should obscure for range attacks, the same
               // way as smoke or fog does"); smoke's own number, −10, for a shot into, out of or through it (OBSCURES)
        gas(new Gas("pollen", "the Trhač's cloud", 0, 0, 0, 0, 0, 0, 0, false, false, 0, false, false,
                "harmless to breathe, but not to one with allergies; it HIDES: a ranged shot into, out of or through the cloud −10 (as smoke)"));
        // Marek 11-19: "if we have any alergy genes or something like that, this should trigger for the people alergy." The immunity
        // genes Broad HLA (T2) and Strong immunity (T3) carry "allergies" (03c); the reaction's numbers are mine (EXAMPLE).
        gas(new Gas("pollen allergy", "the Trhač's pollen breathed by one with allergies (Broad HLA, Strong immunity)", 0, 0, 0, -2, -10, 0, 0,
                false, false, 0, false, false,
                "sneezing and running eyes: P −2, to hit −10 a round inside the cloud (× its strength); the big gas mask with a filter keeps it out"));
    }

    /** The clouds that hide what is in them or behind them: the to-hit of a ranged shot whose line of fire crosses one, × the zone's
     *  strength (the worst cloud on the line counts, once). */
    public static final Map<String, Integer> OBSCURES = Map.of("pollen", -10, "smoke", -10, "acid fog", -10);   // smoke and the fog: Marek
    // 11-19, "yes, it should hide it in the same way when the target is in the cloud"; the fog's −10 is smoke's [mine]

    private static void gas(Gas g) { GASES.put(g.name(), g); }

    /** A week in mercury vapour gives the tremor (03b §3c). */
    public static final int MERCURY_HOURS = 168;
    /** Riot gas acts for 10 minutes after one leaves it (03b §3c). */
    public static final double RIOT_LINGER_HOURS = 10.0 / 60;

    /**
     * A blast's ears (03b §3c, RULED round 21): an eardrum roll at 50 % inside its damage radius, 15 % out to twice it, ringing (1d6
     * hours) out to three times. Returns {eardrum chance %, ringing 0/1}.
     */
    public static int[] blastEars(double dist, double radius) {
        int chance = dist <= radius + 1e-9 ? 50 : dist <= 2 * radius + 1e-9 ? 15 : 0;
        return new int[]{chance, dist <= 3 * radius + 1e-9 ? 1 : 0};
    }

    /** A flashbang (03b §3c): ringing within 5 hexes, an eardrum roll at 15 % within 2. Returns {eardrum chance %, ringing 0/1}. */
    public static int[] flashbangEars(double dist) { return new int[]{dist <= 2 + 1e-9 ? 15 : 0, dist <= 5 + 1e-9 ? 1 : 0}; }

    /** A burst eardrum heals by itself in 1d4 + 2 weeks (03b §3c). */
    public static double eardrumHealHours(int d4) { return (d4 + 2) * 7 * 24.0; }

    /** 30 % of burst ears keep a lasting P −1 for the pair until an Audio Implant (03b §3c). */
    public static final int LASTING_EAR_LOSS = 30;

    /**
     * An attribute roll (03b §3c, an electric hazard's "Endurance roll"): d10 at most the attribute passes [Fallout's stat roll: mine;
     * our skill checks make no roll, but this one is ruled as a roll].
     */
    public static boolean attributeRoll(int value, int d10) { return d10 <= value; }

    /** An electric hazard rolls for the heart when its hit reaches 10 (03b §3c; a hazard rolls no crits). */
    public static final int ELECTRIC_HAZARD = 10;

    /**
     * The Pain a hit adds: the workbook's note on Pain (Marek 11-14: *"Pain is specified in the sheet."*): *"Gained 3x with every
     * HP lost. +25 with crit or painful attacks."*
     */
    public static double painOfHit(int hpLost, boolean critOrPainful) { return 3.0 * hpLost + (critOrPainful ? 25 : 0); }

    /**
     * Bleeding, Andor 1's numbers (`games/andor/zdravi`; Marek 11-14: *"For bleeding, please look into Andor rules."*) as ABSOLUTE
     * Blood a round (Marek 11-14: *"lets make it absolute value instead of percentage, that way attributes and better body helps you
     * resist bleeding. Bandaging here is just first aid."*): a weak wound 2 Blood a round, a strong one (a stab through, a crushed
     * bone) 5, every further cut +1; first aid (Medicine) stops it all. Blood is 4 x HP max, so a bigger body lasts longer. A crit
     * of normal damage opens the wound (the workbook's note: Blood is *"Decreased by normal damage criticals + bleeding"*) [the
     * mapping mine]. Returns the new rate.
     */
    public static double bleedingPerRound(double now, boolean strong) {
        if (strong) return Math.max(now + 1, 5);
        return now <= 0 ? 2 : now + 1;
    }

    /**
     * A skill check makes NO roll (Marek 11-14: *"Skill checks, as intended, make no roll. You just compare skill level value with
     * the required value. So with Survival 75, you automatically pass the check on pain 0-75."*): passed when skill ≥ required.
     */
    public static boolean check(double skill, double required) { return skill >= required; }

    // ------------------------------------------------------------------ part 3: species and modifications (03b, RULED 11-16)
    /** A sense by name: 0 none, 1 "dark halved" (the elf, low-light eyes), 2 "night vision" (catfolk, vampire, Coldvein). */
    public static int senses(String s) {
        return s == null ? 0 : switch (s) { case "dark halved" -> 1; case "night vision" -> 2; default -> 0; };
    }

    /** A whole value times a share, rounded down but at least 1 while the share is not 0 (Tactics' and 03b §3's rounding). */
    public static int scaleInt(double v, double share) {
        if (share <= 0 || v == 0) return 0;
        int r = (int) Math.floor(Math.abs(v) * share + 1e-9);
        return (int) Math.signum(v) * Math.max(1, r);
    }

    /** A tier as a Roman numeral (1-4). */
    public static String roman(int tier) { return switch (tier) { case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; default -> "IV"; }; }

    /** The tier at the end of a fitted implant's name ("Cyber Eyes · Tier III" → 3), 4 when none. */
    public static int tierOf(String name) {
        int i = name.lastIndexOf("· Tier ");
        if (i < 0) return 4;
        return switch (name.substring(i + 7).trim()) { case "I" -> 1; case "II" -> 2; case "III" -> 3; default -> 4; };
    }

    /** What a tier is (03b §3b): the harm of today's devices with it. */
    public static String tierText(int tier) {
        return switch (tier) {
            case 1 -> "today's prototype: an infection roll at the cut class twice a month";
            case 2 -> "today's mature device: an infection roll at the cut class once a month";
            case 3 -> "beyond today's devices";
            default -> "pre-war, the best";
        };
    }

    /** 03b §3 (the old factor, kept for old saves): Tier IV (pre-war) full, III three quarters, II half (rounded down, at least 1). */
    public static double tierShare(int tier) { return tier >= 4 ? 1 : tier == 3 ? 0.75 : 0.5; }
    public static int implantTier(double v, int tier) { return scaleInt(v, tierShare(tier)); }

    /**
     * Falls (RULED 11-16, round 3: *"your idea sounds better than MC, accepted"*): 1d6 per 3 blocks beyond the first 3 (D&D's 1d6 per
     * 10 ft); an Athletics roll halves it; it replaces Minecraft's own. The dice for a fall of {@code blocks}.
     */
    public static int fallDice(double blocks) { return Math.max(0, (int) Math.floor((blocks - 3) / 3 + 1e-9)); }

    /**
     * The fall's Athletics CHECK, no roll (Marek 11-16: *"make it just check. I.e., with high Athletics you can ignore basic falls."*):
     * Athletics at least 40 × the dice ignores the fall, at least 20 × the dice halves it [the steps mine: an average adult, about 30,
     * halves a 6-block fall; 40 ignores it; 80 ignores 9 blocks]. Returns the share of the damage taken: 0, 0.5 or 1.
     */
    public static double fallShare(double athletics, int dice) {
        if (dice <= 0 || athletics >= 40 * dice) return 0;
        return athletics >= 20 * dice ? 0.5 : 1;
    }

    /** Surgery (03b §4, *"seems usable"*): RimWorld's form, the factors [mine]. */
    public static final Map<String, Double> ROOMS = new LinkedHashMap<>(), SUPPLIES = new LinkedHashMap<>(), EQUIPMENT = new LinkedHashMap<>();

    static {
        ROOMS.put("field", 0.6); ROOMS.put("clean", 0.8); ROOMS.put("clinic", 1.0); ROOMS.put("prewar", 1.2);
        SUPPLIES.put("none", 0.3); SUPPLIES.put("improvised", 0.6); SUPPLIES.put("tier3", 1.0); SUPPLIES.put("tier4", 1.3);
        EQUIPMENT.put("hands", 0.2); EQUIPMENT.put("adrenaline", 0.4); EQUIPMENT.put("defibrillator", 1.0); EQUIPMENT.put("icu", 1.5);
    }

    /** The operation's factor by the slot: skin, a limb, a prosthesis 1.0; eyes, ears, lungs, gut, blood 0.8; heart, spine 0.7 (the skeleton too [mine]); brain 0.6. */
    public static double operationFactor(String slot) {
        return switch (slot) {
            case "brain" -> 0.6;
            case "heart", "spine", "skeleton" -> 0.7;
            case "eyes", "ears", "lungs", "gut", "blood" -> 0.8;
            default -> 1.0;
        };
    }

    /** Chance in % = min(98, Medicine × room × supplies × the operation). */
    public static double surgeryChance(double medicine, double room, double supplies, double operation) {
        return Math.max(0, Math.min(98, medicine * room * supplies * operation));
    }

    /** A failed operation's d100: 1-70 minor, 71-95 catastrophic, 96-100 ridiculous (at Medicine 100+ a ridiculous one counts as minor). */
    public static String surgeryFailure(int d100, double medicine) {
        if (d100 <= 70) return "minor";
        if (d100 <= 95) return "catastrophic";
        return medicine >= 100 ? "minor" : "ridiculous";
    }

    /** Taking an implant out of the dead (or the living) costs it max(0, 50 − Medicine / 3) % condition (03b §3). */
    public static double salvageLoss(double medicine) { return Math.max(0, 50 - medicine / 3); }

    /**
     * Revival from clinical death (03b §5, ACCEPTED 11-16): Medicine / 2 % × the equipment × (1 − 7 % a minute since the arrest,
     * 3 % a minute while CPR runs), the minutes ÷ the template's window (Coldvein 4, dhampir 2). In %; 0 = final death.
     */
    public static double revivalChance(double medicine, double equipment, double minutesWithout, double minutesCpr, double window) {
        double time = 1 - (0.07 * minutesWithout + 0.03 * minutesCpr) / Math.max(1e-9, window);
        return time <= 0 ? 0 : Math.max(0, medicine / 2 * equipment * time);
    }

    /** The brain's price: Intelligence −1 for every 3 minutes beyond the first 5 (× the window) [mine]. */
    public static int brainPrice(double minutes, double window) { return Math.max(0, (int) Math.floor((minutes / Math.max(1e-9, window) - 5) / 3 + 1e-9)); }

    /** The yearly mutation roll (03b §7): d100 + (Luck − 5) × 4 − 10 for each 100 rads above the first 100. */
    public static int mutationScore(int d100, int luck, int rads) { return d100 + (luck - 5) * 4 - 10 * (rads / 100 - 1); }

    /**
     * THE CRISIS at 1 000 rads (03b §7b, `09b` round 7, Marek 11-22: *""at 1,000 rads the body gets a forced mutation roll" - great idea,
     * lets add that to PCs as well. And just like before, Luck helps a lot here (hence why ghouls have bigger luck)."* · *"please, add
     * luck inside ... The 1 in 2 seems still usable."*): d100 + (Luck − 5) × 4; above {@link #CRISIS_AT} the body answers (Luck 5: one
     * in two, Luck 10: 70 %, a ghoul's Luck +3: 62 % from Luck 5).
     */
    public static int crisisScore(int d100, int luck) { return d100 + (luck - 5) * 4; }

    public static final int CRISIS_AT = 50, CRISIS_RADS = 1000, CRISIS_EXPEL = 500;

    public static boolean crisisLives(int score) { return score > CRISIS_AT; }

    /**
     * THE GHOUL LINE (03b §7b, Marek 11-23, round 8: *"I have seen in more as paralel evolutions, so that you could jump "stages". But
     * yes, it makes sense that when evolving slowly through leathered ghoul, you get the +3 DT."* · *"Should the plugin apply the three
     * stages itself - yes."*): how far a ghoul's very bad result moves it: one stage, two at −20 or less, three at −40 or less [mine,
     * EXAMPLE]; 0 for any other result.
     */
    public static int ghoulSteps(int score) { return score > 0 ? 0 : 1 + (score <= -20 ? 1 : 0) + (score <= -40 ? 1 : 0); }

    public static final String[] GHOUL_STAGES = {"leathered", "husk", "glowing"};
    /**
     * The perk rate of a ghoul and its stages (`09b` round 13, Marek 11-23, verbatim: *"It should change as well. Perk rate should scale with
     * how humanly you can expirience the reality."*): the ghoul the Ghoul template's 4; round 14 (verbatim: *"each stage slows it further -
     * husk 4, glowing 5. So that the slow down is not so fast."*): leathered 4, husk 4, glowing 5.
     * Never faster than the body's own (the max).
     */
    public static final int GHOUL_PERK_RATE = 4;
    public static final int[] GHOUL_STAGE_PERK_RATE = {4, 4, 5};

    /*
     * THE GLOWING ONE'S RADS (03b §7b, `09b` round 9, Marek 11-23, verbatim: *""A glowing ghoul is healed by the radiation around it (10
     * rads = 1 HP) and keeps none" - it should still be irradiated, up to 1000 rads, but it no longer has illness from radiation, instead
     * gaining a bonus effect, and it sheds the radiation to increase healing rate (4 rads per +1 rate for single rest). The instant healing
     * would be too strong."* · *"it is a radiation source and is losing rads. Which is why they cannot surviive outside hot area for
     * extended duration. They have negative healing rate and only increase it with rads."*): it gathers the place's rads (its body open
     * again: only the ghoul's own resistance), up to {@link #GLOW_CAP}; it sheds {@link #GLOW_SHED} of what it holds every hour [mine,
     * EXAMPLE], and what it sheds reaches everyone within {@link #GLOW_REACH} blocks; its healing rate is {@link #GLOW_BASE_RATE} [mine,
     * EXAMPLE] + 1 for every 4 rads shed in the 8 hours (Marek's 4), halved when positive (the husk's healing ×½ it carries).
     */
    public static final int GLOW_CAP = 1000, GLOW_BASE_RATE = -2, GLOW_RADS_PER_RATE = 4;
    public static final double GLOW_SHED = 0.01, GLOW_REACH = 5;

    /** The glowing one's bonus in place of radiation sickness (round 9) [the steps and numbers mine, EXAMPLE]. */
    public static String glowStage(double rads) {
        return rads < 300 ? null : rads < 600 ? "Charged (glowing)" : rads < GLOW_CAP ? "Bright (glowing)" : "Blazing (glowing)";
    }

    /** The glowing one's healing rate for 8 hours in which it shed {@code shed} rads (round 9); {@code half}: the husk's ×½. */
    public static int glowRate(double shed, boolean half) {
        int r = GLOW_BASE_RATE + (int) Math.floor(shed / GLOW_RADS_PER_RATE + 1e-9);
        return r > 0 && half ? r / 2 : r;
    }

    /** The glowing one's episode of insanity (03b §7b, round 8: *"Episodes of insanity - agreed."*): d100 ≤ 20 − Luck. */
    public static boolean episode(int d100, int luck) { return d100 <= 20 - luck; }

    /** 0 or less very bad, 1-50 bad, 51-80 neutral, 81+ good. */
    public static String mutationCategory(int score) { return score <= 0 ? "very bad" : score <= 50 ? "bad" : score <= 80 ? "neutral" : "good"; }

    /** 03b §8: the chem's chance × 2 Chem Reliant, × ½ Chem Resistant, × (1 + ½ a dose of it in the last 7 days); at most 1. */
    public static double addictionChance(double chance, boolean reliant, boolean resistant, int recentDoses) {
        return Math.min(1, chance * (reliant ? 2 : 1) * (resistant ? 0.5 : 1) * (1 + 0.5 * recentDoses));
    }

    /** 03b §6: an arm under 100 % −(100 − eff) / 2 to hit, over 100 % +(eff − 100) / 4. */
    public static int prostheticToHit(double eff) {
        return eff < 100 ? -(int) Math.round((100 - eff) / 2) : (int) Math.floor((eff - 100) / 4 + 1e-9);
    }

    /** 03b §6: a leg at 80 % and over moves as usual, 60-79 % ×1.5, below ×2 [the rule's 1 / 1.5 / 2 AP a hex read as factors of the stance's rate]. */
    public static double legFactor(double eff) { return eff >= 80 ? 1 : eff >= 60 ? 1.5 : 2; }

    /** RULED 11-16 (*"bionics as 120%, powered as 100%, Mechaical as 80% and hook/peg these 50-60%"*): a hook 50, a peg 60. */
    public static Double prosthesis(String kind, boolean leg) {
        return switch (kind) {
            case "hook" -> leg ? null : 50.0;
            case "peg" -> leg ? 60.0 : null;
            case "mechanical" -> 80.0;
            case "powered" -> 100.0;
            case "bionic" -> 120.0;
            default -> null;
        };
    }

    public static String prosthesisName(String kind, boolean leg) {
        return switch (kind) {
            case "hook" -> "Hook";
            case "peg" -> "Peg leg";
            case "mechanical" -> leg ? "Mechanical leg" : "Mechanical hand";
            case "powered" -> leg ? "Powered leg" : "Powered hand";
            default -> leg ? "Bionic leg" : "Bionic arm";
        };
    }

    // ------------------------------------------------------------------ stances (Marek's own table: the workbook's Battle tab)
    /**
     * Marek 11-14: *"Stances - I think I took is from Tactics instead of JA2, and Tactics seem closer to Fallout, so please use that
     * instead."* His note on Battle G21, verbatim: *"Stand: 1 AP na 2 hexy / Crunch: +2 AC, +5 hit ranged, -20 hit melee, 200% AP na
     * pohyb / Prone: +4 AC, +15 hit ranged, cannot hit on melee, +20 to be hit on short range, 300% AP na pohyb / 1 Leg broken: 400%
     * / 2 Legs broken: 800%"*. A hex = a block here; the total AP of a move is rounded up.
     */
    public static final String[] STANCES = {"standing", "crouched", "prone"};
    public static final double[] STEP_AP = {0.5, 1.0, 1.5};
    public static final double[] LEGS_AP = {0, 2.0, 4.0};
    public static final int[] STANCE_AC = {0, 2, 4};
    public static final int[] STANCE_RANGED = {0, 5, 15};
    /** Melee to-hit by stance; prone cannot strike in melee at all. */
    public static final int[] STANCE_MELEE = {0, -20, 0};
    public static final int PRONE_SHORT_RANGE_TO_BE_HIT = 20;

    /** The AP a block in {@code stance}; a broken leg sets its own rate when that is slower [the max mine]; Brittle Bones doubles. */
    public static double stepAp(int stance, int brokenLegs, boolean brittleBones) {
        double k = Math.max(STEP_AP[stance], LEGS_AP[Math.max(0, Math.min(2, brokenLegs))]);
        return brittleBones ? 2 * k : k;
    }

    /** Fallout Tactics: 2 AP to or from a crouch, 4 AP straight between standing and prone (fallout.wiki, Stance; Flexible halves it). */
    public static int stanceChangeAp(int from, int to) { return from == to ? 0 : (from == 1 || to == 1) ? 2 : 4; }

    // ------------------------------------------------------------------ aimed shots
    /** Fallout 2's called-shot penalties (its source's hit-location table). */
    public enum Part {
        EYES("eyes", -60), HEAD("head", -40), TORSO("torso", 0), GROIN("groin", -30), LEFT_ARM("left arm", -30), RIGHT_ARM("right arm", -30),
        LEFT_LEG("left leg", -20), RIGHT_LEG("right leg", -20);
        public final String label; public final int toHit;
        Part(String label, int toHit) { this.label = label; this.toHit = toHit; }
    }

    /**
     * The body part under the crosshair on a humanoid (Marek 11-14: *"could the aiming take into consideration what body part do I
     * aim at? The aiming is now several modules that I need to cycle."*), by Minecraft's own model in 32nds of the height: legs
     * 0-12, body and arms 12-24, head 24-32; the body and head 8 wide, each arm 4 more on a side, each leg 4. {@code h} = the
     * height of the aimed point over the feet / the height; {@code lat} = its side offset / the height, + toward the target's own
     * right; {@code facing} = the target faces the shooter (the eyes only then). The eye band (rows 27-29 of 32) and the groin
     * (the lowest 2 rows of the body, the middle 4 wide) [mine]. Null = off the silhouette.
     */
    public static Part part(double h, double lat, boolean facing) {
        double a = Math.abs(lat);
        if (h < 0 || h > 1.02) return null;
        if (h >= 0.75) {
            if (a > 0.13) return null;
            return facing && h >= 0.83 && h <= 0.91 && a <= 0.1 ? Part.EYES : Part.HEAD;
        }
        if (h >= 0.375) {
            if (a <= 0.125) return h < 0.44 && a <= 0.0625 ? Part.GROIN : Part.TORSO;
            if (a <= 0.26) return lat > 0 ? Part.RIGHT_ARM : Part.LEFT_ARM;
            return null;
        }
        if (a > 0.13) return null;
        return lat > 0 ? Part.RIGHT_LEG : Part.LEFT_LEG;
    }
}
