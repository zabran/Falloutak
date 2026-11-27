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
    }

    static final List<String> DRESSINGS = List.of("Hemostatic gauze", "Bandage", "Wound powder", "Rag dressing");   // the best first
    static final double DOSE_KG = 0.01;
    static final Map<String, Double> BULKY = Map.of("Blood bag", 0.5, "Oxygen cylinder", 3.0, "Splint", 0.3);
    /** The vaccines' items and their diseases (`04b` §2b). */
    static final Map<String, String> VACCINES = Map.of("Tetanus vaccine", "Tetanus", "TBE vaccine", "Tick-borne encephalitis",
            "Hepatitis A vaccine", "Hepatitis A", "Typhoid vaccine", "Typhoid", "Cholera vaccine", "Cholera", "Rabies vaccine", "Rabies");
    static final List<String> SELF = List.of("Vitamin C tablets", "Blood bag", "Antivenom", "Rehydration salts", "Potassium iodide");
    static final double BLOOD_BAG = 0.25, ANTIVENOM = 50, OXYGEN_HOURS = 10, CHLOROFORM_NEED = 40;

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
            case "Rehydration salts" -> {
                c.add(new Character.Effect("Rehydration salts", "treatment", "treatment:ors", false, new int[7],
                        new LinkedHashMap<>(Map.of("runs", -0.5)), 6, null, 100));
                return "Rehydration salts: the runs' water loss halved for 6 hours";
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

    /** A stack of n of this item weighs (kg): a medicine or chem its vessel and DOSE_KG a further dose; a bulky one or anything else n × kg. */
    static double stackKg(String id, double kg, int n) {
        String nm = name(id);
        boolean dose = id != null && (id.startsWith("chem:") || nm != null && !BULKY.containsKey(nm));
        return dose ? kg + Math.max(0, n - 1) * DOSE_KG : kg * n;
    }
}
