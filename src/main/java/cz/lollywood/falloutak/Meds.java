package cz.lollywood.falloutak;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * THE MEDICINES AS ITEMS (`04i` round 1, Marek 11-27, verbatim: *"Medicines aren't items - this needs to be fixed. They need to be used,
 * at least partially."* · *"Medicines as items - 1 dose/item seem fine. But they should be easy to stack, since most of the weight is
 * the vessel and more liquid should weight little."*; `04b` round 3, long RULED: *"Vaccines and antibiotics as items in part 7? - yes,
 * 100%."*). Every dose is one item of the Medicine row (`thing:Medicine|tier|rarity|name`) or a chem; the actions take them:
 * <ul>
 *   <li><b>antibiotics</b>: a course = one dose a day of ONE antibiotic ({@link #ABX}: its family and strength; the factory ones are
 *   the pre-war stores, aged by `04g` §4c's rule), {@link #courseDoses} of them; fewer = a short course (it ends early, a long course
 *   then does not cure);</li>
 *   <li><b>the antitoxin</b> (or the crude antiserum), <b>sedation</b> (diazepam), <b>honey</b>, <b>breathing support</b> (an oxygen
 *   cylinder for 10 hours; botulism's bagging by hand needs none), <b>the rabies series</b> (4 doses, 2 if vaccinated before);</li>
 *   <li><b>an anaesthetic</b> (ether, chloroform or ketamine) takes the Pain of debriding and amputating away; chloroform stops the heart
 *   under a medic's Medicine 40 (`04g` §4b, RULED: *"chloroform can stop the heart (a check or a death roll)"*);</li>
 *   <li><b>a dressing</b> (hemostatic gauze, a bandage, wound powder or a rag) for first aid's dressing; stopping the bleeding is pressure, free;</li>
 *   <li><b>taken by oneself</b> ({@link #useSelf}): vitamin C, a blood bag, antivenom, rehydration salts, potassium iodide, the vaccines.</li>
 * </ul>
 * Weight: a stack weighs its vessel (the first dose's kg) and {@value #DOSE_KG} kg a further dose; the bulky ones ({@link #BULKY}) each
 * their own [the kg mine, EXAMPLE].
 */
final class Meds {
    private Meds() {}

    static final String TYPE = "Medicine";
    /** An antibiotic: its family ("any" = phages, matched to the germ), its strength, and whether it is the pre-war stores' (aged). */
    record Abx(String family, double strength, boolean prewar) {}

    static final Map<String, Abx> ABX = new LinkedHashMap<>();
    static {
        ABX.put("Penicillin", new Abx("penicillins", 1, true));
        ABX.put("Amoxicillin", new Abx("penicillins", 1, true));
        ABX.put("Doxycycline", new Abx("tetracyclines", 1, true));
        ABX.put("Oxytetracycline", new Abx("tetracyclines", 1, true));
        ABX.put("Chloramphenicol", new Abx("chloramphenicol", 1, true));
        ABX.put("Streptomycin", new Abx("streptomycin", 1, true));
        ABX.put("Ciprofloxacin", new Abx("ciprofloxacin", 1, true));
        ABX.put("Crude penicillin", new Abx("penicillins", 1, false));   // 04g §4c: made, full for its kinds
        ABX.put("Sulfa", new Abx("sulfa", 0.7, false));                  // at 0.7 of the rate
        ABX.put("Phages", new Abx("any", 0.8, false));                   // one named germ; the matching folded into 0.8 [mine]
        ABX.put("Lysin", new Abx("any", 1, false));                      // 04j §7c: phage lysins, any bacterial disease at full, never aged
    }

    static final List<String> DRESSINGS = List.of("Hemostatic gauze", "Bandage", "Sphagnum moss", "Wound powder", "Rag dressing");   // the best first
    static final double DOSE_KG = 0.01;
    static final Map<String, Double> BULKY = Map.of("Blood bag", 0.5, "Oxygen cylinder", 3.0, "Splint", 0.3, "Blood Pack", 0.5, "Trauma pack", 1.5);
    /** The vaccines' items and their diseases (`04b` §2b). */
    static final Map<String, String> VACCINES = Map.of("Tetanus vaccine", "Tetanus", "TBE vaccine", "Tick-borne encephalitis",
            "Hepatitis A vaccine", "Hepatitis A", "Typhoid vaccine", "Typhoid", "Cholera vaccine", "Cholera", "Rabies vaccine", "Rabies");
    static final List<String> SELF = List.of("Vitamin C tablets", "Blood bag", "Antivenom", "Rehydration salts", "Potassium iodide",
            "Home ORS", "Blood Pack", "Virex");
    static final double BLOOD_BAG = 0.25, ANTIVENOM = 50, OXYGEN_HOURS = 10, CHLOROFORM_NEED = 40;
    /** `04i` §6a, §6c, `04j` §7c (RULED 11-27) [the numbers mine, EXAMPLE, the table's where it gives them]. */
    static final double BLOOD_PACK = 0.40, TRAUMA_BLOOD = 0.50, SUPER_BLOOD = 0.25, DONOR = 0.25, ATROPINE = 30, VIREX_HOURS = 7 * 24;
    static final int TRAUMA_NEED = 40, ATROPINE_NEED = 50, SUPER_REAL = 25, SUPER_HELD = 25, HYPO_HELD = 40, TRAUMA_HP = 15;
    /** The injectors (`04i` §6a): the Stimpak (gear, Fallout's), the Super Stimpak and the Hypo (Medicine IV). */
    static final List<String> INJECTORS = List.of("Stimpak", "Super Stimpak", "Hypo");

    /** A Medicine row item's name, or null. */
    static String name(String id) {
        String[] t = Items.thing(id);
        return t != null && t[0].equals(TYPE) ? t[3] : null;
    }

    /** The item id of a Medicine row item by its name (its tier and rarity from the catalogue). */
    static String id(String name) {
        Production.ItemType ty = Production.TYPES.get(TYPE);
        if (ty != null) for (Production.Named n : ty.items) if (n.name.equals(name)) return Items.storeId(TYPE, n.tier, n.rarity, name);
        return Items.thingId(TYPE, 3, 1, name);
    }

    static int count(Player p, String name) {
        int n = 0;
        for (Map.Entry<String, Integer> e : Items.carried(p).entrySet()) if (name.equals(name(e.getKey()))) n += e.getValue();
        return n;
    }

    /** Takes up to {@code n} doses of this medicine; how many. */
    static int take(Player p, String name, int n) { return n <= 0 ? 0 : Battle.removeIds(p, id -> name.equals(name(id)), n); }

    static boolean takeChem(Player p, String chem) { return Items.take(p, "chem:" + chem); }

    /** A disease's course in doses: a dose a day of its course (`Diseases.ABX_DAYS`, or its own long course). */
    static int courseDoses(Diseases.Disease d) { return (int) Math.round(d.course() > 0 ? d.course() : Diseases.ABX_DAYS); }

    /** The antibiotics he carries: name → doses. */
    static Map<String, Integer> antibiotics(Player p) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String n : ABX.keySet()) { int c = count(p, n); if (c > 0) m.put(n, c); }
        return m;
    }

    /** The family this antibiotic gives against this disease (phages fit any bacterial one), or "". */
    static String family(String abx, Diseases.Disease d) {
        Abx a = ABX.get(abx);
        if (a == null) return "";
        if (a.family().equals("any")) return d.families().isEmpty() ? "" : d.families().get(0);
        return a.family();
    }

    /** Its strength now (the pre-war stores by the years since day D, `04g` §4c). */
    static double strength(String abx, double years) {
        Abx a = ABX.get(abx);
        return a == null ? 0 : a.strength() * (a.prewar() ? Diseases.prewarStrength(years) : 1);
    }

    /**
     * The items a disease action uses, taken from the medic before it (null = none needed or allowed without), or "! why not".
     * {@code support} running is stopped without an item.
     */
    static String forAct(Player medic, Diseases.Disease d, Diseases.Case k, String act, Character patient) {
        switch (act) {
            case "support" -> {
                if (k.acts.containsKey("support") || d.name().equals("Botulism")) return null;   // stopping it; botulism bagged by hand
                return take(medic, "Oxygen cylinder", 1) == 1 ? "an oxygen cylinder (" + Math.round(OXYGEN_HOURS) + " hours)" : "! breathing support needs an oxygen cylinder";
            }
            case "sedation" -> { return takeChem(medic, "Diazepam") ? "a dose of diazepam" : "! sedation needs a sedative (diazepam)"; }
            case "honey" -> { return take(medic, "Honey", 1) == 1 ? "honey" : "! no honey"; }
            case "antitoxin" -> {
                if (take(medic, "Antitoxin", 1) == 1) return "the antitoxin";
                return take(medic, "Crude antiserum", 1) == 1 ? "the crude antiserum (its allergy: the GM's)" : "! no antitoxin or crude antiserum";
            }
            case "vaccine" -> {
                int need = patient.immune.containsKey(d.name()) ? 2 : 4;
                if (count(medic, "Rabies vaccine") < need) return "! the series needs " + need + " doses of the rabies vaccine";
                take(medic, "Rabies vaccine", need);
                return need + " doses of the rabies vaccine";
            }
            case "maggots" -> { return take(medic, "Maggots", 1) == 1 ? "maggots" : "! maggots in the wound need Maggots (bred: Survival 30)"; }
            case "debride", "amputate" -> {
                if (take(medic, "Ether", 1) == 1) return "anaesthesia (ether)";
                if (take(medic, "Chloroform", 1) == 1) return "anaesthesia (chloroform)";
                if (takeChem(medic, "Ketamine")) return "anaesthesia (ketamine)";
                return null;
            }
            default -> { return null; }
        }
    }

    /** First aid's dressing: the best dressing he carries, taken; null if none. */
    static String dressing(Player medic) {
        for (String n : DRESSINGS) if (take(medic, n, 1) == 1) return n;
        return null;
    }

    /** The medicine taken by oneself (the Pip-Boy's Use, or a right click); "! ..." if it is not one. */
    static String useSelf(Character c, String name, Random rnd) {
        if (VACCINES.containsKey(name)) {
            String r = Diseases.command(c, new String[]{"disease", "vaccine", VACCINES.get(name).replace(' ', '_')}, rnd);
            return name + ": " + r;
        }
        switch (name) {
            case "Vitamin C tablets" -> { c.vitaminAt = c.now(); return "Vitamin C: today is a vitamin C day (scurvy, 04b §3c)"; }
            case "Blood bag" -> {
                double max = c.max(Rules.Stat.BLOOD), before = c.survival.getOrDefault(Rules.Stat.BLOOD, max);
                c.survival.put(Rules.Stat.BLOOD, Math.min(max, before + BLOOD_BAG * max));
                return "A blood bag: Blood " + Math.round(before) + " → " + Math.round(c.survival.get(Rules.Stat.BLOOD)) + " / " + Math.round(max)
                        + " (the group must match: the GM's)";
            }
            case "Antivenom" -> {
                double before = c.survival.getOrDefault(Rules.Stat.POISON, 0.0);
                c.survival.put(Rules.Stat.POISON, Math.max(0, before - ANTIVENOM));
                return "Antivenom: Poison " + Math.round(before) + " → " + Math.round(c.survival.get(Rules.Stat.POISON));
            }
            case "Rehydration salts", "Home ORS" -> {   // 04i §6c: the home ORS = the salts (the WHO's formula)
                c.add(new Character.Effect("Rehydration salts", "treatment", "treatment:ors", false, new int[7],
                        new LinkedHashMap<>(Map.of("runs", -0.5)), 6, null, 100));
                return name + ": the runs' water loss halved for 6 hours";
            }
            case "Blood Pack" -> {   // 04j §7c: freeze-dried artificial red cells, any group
                double max = c.max(Rules.Stat.BLOOD), before = c.survival.getOrDefault(Rules.Stat.BLOOD, max);
                c.survival.put(Rules.Stat.BLOOD, Math.min(max, before + BLOOD_PACK * max));
                return "A Blood Pack: Blood " + Math.round(before) + " → " + Math.round(c.survival.get(Rules.Stat.BLOOD)) + " / " + Math.round(max)
                        + " (any group)";
            }
            case "Virex" -> {   // 04j §7c: a broad antiviral, a viral disease's immunity ×2 [the week mine]
                c.add(new Character.Effect("Virex", "treatment", "treatment:virex", false, new int[7],
                        new LinkedHashMap<>(Map.of("viral_immunity", 100.0)), VIREX_HOURS, null, 100));
                return "Virex: a viral disease's immunity grows twice as fast for a week";
            }
            case "Potassium iodide" -> { return "Potassium iodide: the thyroid is full for a day; it helps only in the days after a new release (none now)"; }
            default -> { return "! " + name + " is not taken by oneself"; }
        }
    }

    /** What he carries that he can take by himself: name → count. */
    static Map<String, Integer> selfUse(Player p) {
        Map<String, Integer> m = new LinkedHashMap<>();
        List<String> l = new ArrayList<>(SELF);
        l.addAll(VACCINES.keySet());
        for (String n : l) { int c = count(p, n); if (c > 0) m.put(n, c); }
        return m;
    }

    // ------------------------------------------------------------------ 04i §6a: the Fallout standard rationalized (RULED 11-27)
    /** Keeps the stronger of two effects in one slot (a Stimpak after a Super Stimpak does not cut its healing), the longer hours kept. */
    static void keepStronger(Character c, Character.Effect e, String key) {
        Character.Effect old = c.inSlot(e.slot());
        if (old != null && Math.abs(old.other().getOrDefault(key, 0.0)) > Math.abs(e.other().getOrDefault(key, 0.0))) {
            c.add(old.withHours(Math.max(old.hoursLeft(), e.hoursLeft())));
            return;
        }
        c.add(e);
    }

    private static Character.Effect fx(String name, String slot, String key, double v, double hours) {
        return new Character.Effect(name, "treatment", slot, false, new int[7], new LinkedHashMap<>(Map.of(key, v)), hours, null, 100);
    }

    private static Character.Effect needs(String name, double v) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("food_need", v);
        m.put("water_need", v);
        return new Character.Effect(name + " (healing's hunger)", "treatment", "treatment:heal-need", false, new int[7], m, 24, null, 100);
    }

    /**
     * An injector on {@code t} (`04i` §6a, Marek 11-27: *"Stimpak, Hypo, Trauma pack - thank you, this works a lot better."*); nothing
     * regrows flesh in seconds, so:
     * <ul>
     *   <li><b>Stimpak</b> (a sealant, synthetic platelets, an anaesthetic, a growth factor): every bleeding stopped, +10-20 HP (Fallout 2's,
     *   the wound sealed), the healing rate +1 for 3 days, Pain −10 for an hour, food and water needs +25 % for a day;</li>
     *   <li><b>Super Stimpak</b> (+ a blood substitute and adrenaline): bleeding stopped, Blood +25 %, +25 HP real and +25 HELD for an hour,
     *   the healing rate +2 for 3 days, needs +50 % for a day, a heart check (Endurance: else the heart races, AP −2 for an hour);</li>
     *   <li><b>Hypo</b> (combat resuscitation, it heals nothing): a man at or below 0 HP stands up, +40 HP HELD for an hour, bleeding
     *   stopped, Pain felt −50 for the hour; when it ends he is where he was.</li>
     * </ul>
     * Returns the news; "! ..." if it cannot.
     */
    static String inject(Character t, String name, Random rnd) {
        if (t.dead) return "! dead";
        if (t.inArrest()) return "! clinical death: the heart must be started first (revival)";
        int before = t.hp;
        double bleed = t.bleeding;
        switch (name) {
            case "Stimpak" -> {
                int up = Math.min(t.hpMax(), t.hp + 10 + rnd.nextInt(11)) - t.hp;
                t.healReal(Math.max(0, up));
                t.bleeding = 0;
                keepStronger(t, fx("Stimpak (healing)", "treatment:heal-boost", "healing_rate", 1, 72), "healing_rate");
                keepStronger(t, fx("Stimpak (anaesthetic)", "treatment:stimpak-pain", "pain_felt", -10, 1), "pain_felt");
                keepStronger(t, needs("Stimpak", 0.25), "food_need");
                return "a Stimpak: +" + (t.hp - before) + " HP (" + t.hp + "/" + t.hpMax() + ")" + (bleed > 0 ? ", the bleeding stops" : "")
                        + "; the healing rate +1 for 3 days, Pain −10 for an hour, food and water +25 % for a day";
            }
            case "Super Stimpak" -> {
                int real = Math.max(0, Math.min(t.hpMax(), t.hp + SUPER_REAL) - t.hp);
                t.healReal(real);
                t.hold(SUPER_HELD);
                t.bleeding = 0;
                double max = t.max(Rules.Stat.BLOOD), b = t.survival.getOrDefault(Rules.Stat.BLOOD, max);
                t.survival.put(Rules.Stat.BLOOD, Math.min(max, b + SUPER_BLOOD * max));
                keepStronger(t, fx("Super Stimpak (healing)", "treatment:heal-boost", "healing_rate", 2, 72), "healing_rate");
                keepStronger(t, needs("Super Stimpak", 0.5), "food_need");
                int e = t.attrs(false)[Rules.E], d = 1 + rnd.nextInt(10);
                String heart = d <= e ? "the heart holds (d10 " + d + " ≤ Endurance " + e + ")"
                        : "the heart races (d10 " + d + " > Endurance " + e + "): AP −2 for an hour";
                if (d > e) t.add(fx("Super Stimpak (racing heart)", "treatment:racing-heart", "ap", -2, 1));
                return "a Super Stimpak: +" + real + " HP and " + SUPER_HELD + " HELD for an hour (" + t.hp + "/" + t.hpMax() + "), Blood +25 %"
                        + (bleed > 0 ? ", the bleeding stops" : "") + "; the healing rate +2 for 3 days, food and water +50 % for a day; " + heart;
            }
            case "Hypo" -> {
                t.hold(HYPO_HELD);
                t.bleeding = 0;
                keepStronger(t, fx("Hypo (adrenaline, ketamine)", "treatment:hypo-pain", "pain_felt", -50, 1), "pain_felt");
                return "a Hypo: +" + HYPO_HELD + " HP HELD for an hour (" + before + " → " + t.hp + (before <= 0 && t.hp > 0 ? ": he stands up" : "")
                        + ")" + (bleed > 0 ? ", the bleeding stops" : "") + ", Pain felt −50; when the hour ends he is where he was";
            }
            default -> { return "! " + name + " is no injector"; }
        }
    }

    /** Takes one injector of this kind from the player (the Stimpak is gear, the others Medicine IV): whether he had one. */
    static boolean takeInjector(Player p, String name) {
        return name.equals("Stimpak") ? Items.take(p, "gear:Stimpak") : take(p, name, 1) == 1;
    }

    /** The injectors he carries: name → count. */
    static Map<String, Integer> injectors(Player p) {
        Map<String, Integer> m = new LinkedHashMap<>();
        Integer st = Items.carried(p).get("gear:Stimpak");
        if (st != null && st > 0) m.put("Stimpak", st);
        for (String n : INJECTORS) { if (n.equals("Stimpak")) continue; int c = count(p, n); if (c > 0) m.put(n, c); }
        return m;
    }

    /**
     * The Trauma pack (`04i` §6a: a 2040s medic's kit, not a drug: chest seals, tourniquets, sealant, a blood substitute, splints,
     * ketamine): 10 minutes and the medic's Medicine 40 (a check, no roll; under it the kit stays unused). Every bleeding stopped, Blood
     * +50 %, a broken limb splinted (it still has to knit), Pain −40 for 4 h, +15 HP, the healing rate +2 for 3 days.
     */
    static String traumaPack(double medicine, Character t) {
        if (t.dead) return "! dead";
        if (t.inArrest()) return "! clinical death: revive first";
        if (!Rules.check(medicine, TRAUMA_NEED)) return "! the Trauma pack needs Medicine " + TRAUMA_NEED + " (a check, no roll): " + Math.round(medicine);
        int before = t.hp;
        t.healReal(Math.max(0, Math.min(t.hpMax(), t.hp + TRAUMA_HP) - t.hp));
        t.bleeding = 0;
        double max = t.max(Rules.Stat.BLOOD), b = t.survival.getOrDefault(Rules.Stat.BLOOD, max);
        t.survival.put(Rules.Stat.BLOOD, Math.min(max, b + TRAUMA_BLOOD * max));
        keepStronger(t, fx("Trauma pack (ketamine, blocks)", "treatment:trauma-pain", "pain_felt", -40, 4), "pain_felt");
        keepStronger(t, fx("Trauma pack (healing)", "treatment:heal-boost", "healing_rate", 2, 72), "healing_rate");
        String splint = t.brokenLegs > t.splints.size() ? "; " + t.splint() : "";   // its splints (0.108.0)
        return "the Trauma pack (10 minutes): every bleeding stopped, Blood " + Math.round(b) + " → " + Math.round(t.survival.get(Rules.Stat.BLOOD))
                + ", +" + (t.hp - before) + " HP, Pain −40 for 4 hours, the healing rate +2 for 3 days" + splint;
    }

    /** First aid's splint (0.108.0): a broken leg splinted with a Splint item, at the medic's Medicine 20 (as a dressing) [the 20 mine]. */
    static final int SPLINT_NEED = 20;

    static String splint(double medicine, Character t) {
        if (!Rules.check(medicine, SPLINT_NEED)) return "! splinting a leg needs Medicine " + SPLINT_NEED + " (a check, no roll): " + Math.round(medicine);
        return t.splint();
    }

    /** Atropine (`04i` §6c: the antidote for the farms' pesticide poisoning): Poison −30; its overdose kills, so the medic needs Medicine 50. */
    static String atropine(double medicine, Character t) {
        if (!Rules.check(medicine, ATROPINE_NEED)) return "! atropine needs Medicine " + ATROPINE_NEED + " to dose (an overdose kills): " + Math.round(medicine);
        double b = t.survival.getOrDefault(Rules.Stat.POISON, 0.0);
        t.survival.put(Rules.Stat.POISON, Math.max(0, b - ATROPINE));
        return "atropine: Poison " + Math.round(b) + " → " + Math.round(t.survival.get(Rules.Stat.POISON));
    }

    /**
     * Fresh whole blood (`04i` §6c: the armies' walking blood bank): the donor's Blood −25 %, the patient's +25 % (the group tested with
     * a card: the GM's).
     */
    static String transfuse(Character donor, Character t) {
        if (donor == t) return "! not into yourself";
        double dm = donor.max(Rules.Stat.BLOOD), db = donor.survival.getOrDefault(Rules.Stat.BLOOD, dm);
        if (db < 0.5 * dm) return "! you have too little blood to give (Blood under half)";
        double tm = t.max(Rules.Stat.BLOOD), tb = t.survival.getOrDefault(Rules.Stat.BLOOD, tm);
        donor.survival.put(Rules.Stat.BLOOD, db - DONOR * dm);
        t.survival.put(Rules.Stat.BLOOD, Math.min(tm, tb + DONOR * tm));
        return "fresh whole blood: the donor's Blood " + Math.round(db) + " → " + Math.round(donor.survival.get(Rules.Stat.BLOOD))
                + ", the patient's " + Math.round(tb) + " → " + Math.round(t.survival.get(Rules.Stat.BLOOD)) + " (the groups must match: the GM's)";
    }


    /** What a medicine does, for its item's text (`04i`, `04j`), or null for the plain ones. */
    static String text(String name) {
        return switch (name) {
            case "Super Stimpak" -> "bleeding stopped, Blood +25 %, +25 HP and 25 HELD for an hour, the healing rate +2 for 3 days,\nneeds +50 % for a day, a heart check (Endurance): right click or your Actions";
            case "Hypo" -> "combat resuscitation: a dying man stands up, +40 HP HELD for an hour, bleeding stopped, Pain −50;\nwhen the hour ends he is where he was: right click or your Actions";
            case "Trauma pack" -> "a medic's kit (10 minutes, Medicine 40): every bleeding stopped, Blood +50 %, +15 HP, Pain −40 for 4 h,\nthe healing rate +2 for 3 days, a broken leg splinted";
            case "Blood Pack" -> "freeze-dried artificial red cells: Blood +40 %, any group, no cooling";
            case "Lysin" -> "phage lysins: an antibiotic course against any bacterial disease, at full, never aged";
            case "Virex" -> "a broad antiviral: a viral disease's immunity grows twice as fast for a week";
            case "Sphagnum moss" -> "a dressing (first aid's): the dressing's healing rate +1";
            case "Home ORS" -> "boiled water, salt and sugar: the runs' water loss halved for 6 hours";
            case "Maggots" -> "maggots in the wound (a disease's action): the growth × 0.5 while it is local";
            case "Atropine" -> "the pesticide antidote: Poison −30, given at Medicine 50 (an overdose kills)";
            case "Splint" -> "a broken leg splinted (Medicine 20): it knits in 6 weeks (your Actions or a medic's)";
            case "Ephedra" -> "ma huang: the old way to pervitin (Pervitin kitchen)";
            default -> null;
        };
    }

    /** A stack of n of this item weighs (kg): a medicine or chem its vessel and DOSE_KG a further dose; a bulky one or anything else n × kg. */
    static double stackKg(String id, double kg, int n) {
        String nm = name(id);
        boolean dose = id != null && (id.startsWith("chem:") || nm != null && !BULKY.containsKey(nm));
        return dose ? kg + Math.max(0, n - 1) * DOSE_KG : kg * n;
    }
}
