package cz.lollywood.falloutak;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.StringJoiner;

/**
 * Part 4b, the diseases (the model RULED 11-15; Marek 11-24, verbatim, `09e` round 3: *"the plugin doesn't simulate diseases - please
 * add that."*). Each disease runs its own race between its SEVERITY (a share of its own bar, four stages with their effects) and the
 * body's IMMUNITY (0 to 100 %), as `_tools/fallout_disease_sim.py` runs it; `data/diseases.json` is generated from that simulation by
 * `_tools/fallout_diseases_json.py`. The incubation; the severity's growth an hour (the rate × the dose × the care); the immunity's speed
 * ((0.5 + 1.5 × the poison resistance) × luck × 1.1 asleep × the food, water and radiation stages × the Immunity effects, the grafts'
 * drugs −30 %); immune first = it fades, severity first = a lethal one stops the heart, a mild one stays at its worst; an antibiotic
 * course from the hour the disease is recognised (its second stage), tending × 0.7 and the other actions of `04c` §7; catching by `04b`
 * §5 (× (1 − the poison resistance), soap × 0.7 from a sick person, the vaccines of §2b, the memory a disease leaves). Second build
 * (Marek 11-24: *"Not built yet - please continue."*): tuberculosis and Lyme on the same race with their long courses of drugs, the sick
 * nearby (§5's daily household chances), the flu's pneumonia, a wound's infection and tetanus by its class, cancer's yearly roll on the
 * lifetime's rads (§3b) with surgery and radiotherapy.
 */
final class Diseases {
    private Diseases() {}

    record Stage(double from, String name, int[] mods, double waterH, double foodH, double hpH, double pain, double hpMax, boolean noDrink) {}

    record Act(double x, int upTo) {}

    record Disease(String name, String cz, String kind, boolean bacterial, boolean lethal, double max, double incLo, double incHi,
                   double rS, double rI, double rRec, String dose, double doseLo, double doseHi, int abxStage, double support,
                   boolean supportSaves, double absorbDays, Map<String, Act> acts, double debride, boolean amputable,
                   double immuneDays, double immuneShare, boolean resist, boolean person, List<String> families, double contagion,
                   double course, double drug, List<Stage> stages, String real, String caught) {
        int stageAt(double sev) {
            int i = -1;
            for (int k = 0; k < stages.size(); k++) if (sev >= stages.get(k).from() - 1e-12) i = k;
            return i;
        }
    }

    static final double[] FOOD_IMM = {1, .9, .8, .6, .4, .3}, WATER_IMM = {1, .9, .8, .6, .4}, RAD_IMM = {1, .8, .6, .4, .2, .1};
    static final double TEND = 0.7, ABX_DAYS = 7, SOAP = 0.7, YEAR = 365 * 24;
    /** A bar of soap lasts a person washing with it this many days [mine, EXAMPLE: a 100 g bar at ~7 g a day]; its item. */
    static final double SOAP_DAYS = 14;
    static final String SOAP_ID = "thing:Chemicals|2|1|Soap";
    static final List<String> FAMILIES = List.of("penicillins", "tetracyclines", "sulfa", "chloramphenicol", "ciprofloxacin", "streptomycin");
    /** 04b §5: a wound's infection by its class (a cut, a gunshot or a blast's fragments, a dirty wound or a bite), % [life's anchors]. */
    static final Map<String, Double> WOUND = Map.of("cut", 2.0, "gunshot", 15.0, "dirty", 40.0, "bite", 40.0);
    static final Map<String, Disease> ALL = load();
    /** The diseases of the runs and the vomiting: opium and codeine halve their water loss, tannins give back 0.5 an hour, cannabis
     * stops the vomiting's food loss (04c §4b, §5). */
    static final java.util.Set<String> GUT = java.util.Set.of("Food poisoning", "Dysentery", "Cholera");
    /** The sanatorium (04c §7: food, rest, air): the immunity × 1.2 (the simulation's; tuberculosis's deaths 66 → 26 %). */
    static final double SANATORIUM = 1.2;

    /** One disease running in a body (saved with the character). */
    public static final class Case {
        public String name = "";
        public double incubation;                 // hours before the first signs
        public double sev, imm, dose = 1, luck = 1, ill;   // ill = hours since the first signs
        public boolean immune;
        public double abxStart = -1, abxRate = 1; // the course: the hour it began (recognised), its strength (the old stores half)
        public String abxFamily = "";
        /** 04i: the doses the course was given when fewer than its days (a short course), else -1. */
        public double abxDays = -1;
        public Map<String, Double> acts = new LinkedHashMap<>();   // an action → until this hour (−1 = while the disease lasts)
        public int stage = -1;
    }

    // ------------------------------------------------------------------ the data
    private static Map<String, Disease> load() {
        Map<String, Disease> m = new LinkedHashMap<>();
        try (InputStream in = Diseases.class.getResourceAsStream("/data/diseases.json")) {
            if (in == null) return m;
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement el : root.getAsJsonArray("rows")) {
                JsonObject o = el.getAsJsonObject();
                List<Stage> st = new ArrayList<>();
                for (JsonElement se : o.getAsJsonArray("stages")) {
                    JsonObject s = se.getAsJsonObject();
                    int[] mods = new int[7];
                    JsonObject mo = s.getAsJsonObject("mods");
                    for (String k : mo.keySet()) mods["SPECIAL".indexOf(k)] = mo.get(k).getAsInt();
                    st.add(new Stage(d(s, "from"), s.get("name").getAsString(), mods, d(s, "water_h"), d(s, "food_h"), d(s, "hp_h"),
                            d(s, "pain"), d(s, "hp_max"), s.has("no_drink") && s.get("no_drink").getAsBoolean()));
                }
                Map<String, Act> acts = new LinkedHashMap<>();
                JsonObject ao = o.getAsJsonObject("acts");
                for (String k : ao.keySet()) acts.put(k, new Act(d(ao.getAsJsonObject(k), "x"), ao.getAsJsonObject(k).get("up_to").getAsInt()));
                List<String> fam = new ArrayList<>();
                o.getAsJsonArray("families").forEach(f -> fam.add(f.getAsString()));
                JsonElement dose = o.get("dose");
                boolean sq = dose.isJsonPrimitive();
                String name = o.get("name").getAsString();
                m.put(name, new Disease(name, o.get("cz").getAsString(), o.get("kind").getAsString(), o.get("bacterial").getAsBoolean(),
                        o.get("lethal").getAsBoolean(), d(o, "max"), o.getAsJsonArray("inc").get(0).getAsDouble(),
                        o.getAsJsonArray("inc").get(1).getAsDouble(), d(o, "r_s"), d(o, "r_i"), d(o, "r_rec"), sq ? "sq" : "range",
                        sq ? 0 : dose.getAsJsonArray().get(0).getAsDouble(), sq ? 1.5 : dose.getAsJsonArray().get(1).getAsDouble(),
                        o.get("abx_stage").getAsInt(), d(o, "support"), o.get("support_saves").getAsBoolean(), d(o, "absorb_days"), acts,
                        d(o, "debride"), o.get("amputable").getAsBoolean(), d(o, "immune_days"), d(o, "immune_share"),
                        o.get("resist").getAsBoolean(), o.get("person").getAsBoolean(), fam, d(o, "contagion"), d(o, "course"), d(o, "drug"),
                        st, o.get("real").getAsString(),
                        o.get("catch").getAsString()));
            }
        } catch (Exception e) {
            throw new IllegalStateException("data/diseases.json: " + e.getMessage(), e);
        }
        return m;
    }

    private static double d(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : 0; }

    /** A disease by its name, its Czech name, or either without spaces, dashes and underscores; null when none. */
    static Disease find(String n) {
        if (n == null) return null;
        String k = norm(n);
        for (Disease d : ALL.values()) if (norm(d.name()).equals(k) || norm(d.cz()).equals(k) || norm(d.cz().split(",")[0]).equals(k)) return d;
        return null;
    }

    private static String norm(String s) { return s.toLowerCase(Locale.ROOT).replaceAll("[ _-]", ""); }

    static Case active(Character c, String name) {
        for (Case k : c.diseases) if (k.name.equals(name)) return k;
        return null;
    }

    // ------------------------------------------------------------------ catching (04b §5) and the vaccines (§2b)
    /**
     * An exposure: the chance in % × (1 − the poison resistance) (not for wound infection and botulism) × soap's 0.7 when caught from a
     * sick person, × what a vaccine or the last bout still keeps off; a roll under it starts the incubation.
     */
    static String expose(Character c, String name, double pct, boolean fromPerson, boolean soap, Random rnd) {
        Disease d = find(name);
        if (d == null) return "! no disease " + name + " (" + String.join(", ", ALL.keySet()) + ")";
        if (active(c, d.name()) != null) return d.name() + ": already ill with it";
        vaccinesAtStart(c);
        double ch = pct, kept = kept(c, d.name());
        StringJoiner why = new StringJoiner(", ");
        if (kept > 0) { ch *= 1 - kept; why.add("protected " + Math.round(100 * kept) + " %"); }
        double res = resistance(c);
        if (d.resist() && res > 0) { ch *= 1 - res; why.add("resistance " + Math.round(100 * res) + " %"); }
        if (fromPerson && d.person() && soap) { ch *= SOAP; why.add("soap × " + SOAP); }
        double roll = 100 * rnd.nextDouble();
        String num = f(roll) + " of " + f(ch) + " %" + (why.length() == 0 ? "" : " (" + pct + " %, " + why + ")");
        if (roll >= ch) return d.name() + ": not caught, " + num;
        Character.Luck lk = c.luck(rnd);   // 04d §4 (RULED round 9): Lucky Escape on catching a disease or a wound infection
        if (lk.ok()) return d.name() + ": LUCKY ESCAPE (d100 " + lk.roll() + " ≤ " + lk.chance() + " %), not caught, " + num;
        Case k = infect(c, d, rnd);
        return d.name() + ": CAUGHT, " + num + "; the first signs in " + time(k.incubation);
    }

    /** Infected without a roll (the GM's; a failed exposure): the incubation, the dose and the luck drawn. */
    static Case infect(Character c, Disease d, Random rnd) {
        Case old = active(c, d.name());
        if (old != null) return old;
        Case k = new Case();
        k.name = d.name();
        k.incubation = Math.max(1, (d.incLo() + (d.incHi() - d.incLo()) * rnd.nextDouble()) * 24);
        double u = rnd.nextDouble();
        k.dose = d.dose().equals("sq") ? 1.5 * u * u : d.doseLo() + (d.doseHi() - d.doseLo()) * u;   // "sq": most cases mild
        k.luck = 0.8 + 0.4 * rnd.nextDouble();
        c.diseases.add(k);
        return k;
    }

    /** The share of the catching roll a vaccine or the last bout keeps off now (pairs of until-hour and share; −1 = for life). */
    static double kept(Character c, String name) {
        double[] a = c.immune.get(name);
        if (a == null) return 0;
        for (int i = 0; i + 1 < a.length; i += 2) if (a[i] < 0 || a[i] > c.now()) return a[i + 1];
        return 0;
    }

    /**
     * 04b §2b (RULED round 6): everyone born before day D has measles (MMR) for life, and the tetanus booster of the start package a year
     * before day D: 10 years in full, then 5 at half (RULED round 7). Set once, the first time a disease touches the body.
     */
    static void vaccinesAtStart(Character c) {
        if (c.vaccinesSet) return;
        c.vaccinesSet = true;
        c.immune.putIfAbsent("Measles", new double[]{-1, 1});
        c.immune.putIfAbsent("Tetanus", vaccine("Tetanus", c.now(), Travel.years() + 1));
    }

    /**
     * A vaccine given {@code yearsAgo} (04b §2b): measles and hepatitis A for life; tetanus 10 years, then 5 at half; tick-borne
     * encephalitis 4 years [3-5, the middle mine], then a third less each year; typhoid 3 years at 60 %, cholera 2 years at 70 % [the
     * middles of 50-70 and 60-85 mine]; rabies keeps nothing off, but after a bite two doses in the incubation save (04c §7). Null: none.
     */
    static double[] vaccine(String name, double now, double yearsAgo) {
        double y = Math.max(0, yearsAgo);
        return switch (name) {
            case "Measles", "Hepatitis A" -> new double[]{-1, 1};
            case "Tetanus" -> new double[]{now + (10 - y) * YEAR, 1, now + (15 - y) * YEAR, .5};
            case "Tick-borne encephalitis" -> new double[]{now + (4 - y) * YEAR, 1, now + (5 - y) * YEAR, 2.0 / 3, now + (6 - y) * YEAR, 1.0 / 3};
            case "Typhoid" -> new double[]{now + (3 - y) * YEAR, .6};
            case "Cholera" -> new double[]{now + (2 - y) * YEAR, .7};
            case "Rabies" -> new double[]{now + (10 - y) * YEAR, 0};
            case "Influenza" -> new double[]{now + (1 - y) * YEAR, 1};
            default -> null;
        };
    }

    static double resistance(Character c) { return Math.max(0, c.resistances()[2] / 100); }

    // ------------------------------------------------------------------ the hour
    /** One game hour of every disease in this body; what changed, or null. */
    static String hour(Character c, Random rnd) {
        if (c.dead) return null;
        cancerYear(c, rnd);
        nearby(c, rnd);
        if (c.diseases.isEmpty()) return null;
        StringJoiner out = new StringJoiner("; ");
        double now = c.now(), res = resistance(c);
        int fl = level(Rules.stage(Rules.Stat.FOOD, c.survival.getOrDefault(Rules.Stat.FOOD, c.max(Rules.Stat.FOOD)), c.max(Rules.Stat.FOOD)));
        int wl = level(Rules.stage(Rules.Stat.WATER, c.survival.getOrDefault(Rules.Stat.WATER, c.max(Rules.Stat.WATER)), c.max(Rules.Stat.WATER)));
        String rs = c.stageOf(Rules.Stat.RADS, c.survival.getOrDefault(Rules.Stat.RADS, 0.0), c.max(Rules.Stat.RADS));
        int rl = rs != null && rs.contains("Radiation Poisoning") ? level(rs) : 0;
        double speedBody = (0.5 + 1.5 * res) * (c.sleeping ? 1.1 : 1) * FOOD_IMM[Math.min(fl, 5)] * WATER_IMM[Math.min(wl, 4)]
                * RAD_IMM[Math.min(rl, 5)] * Math.max(0, 1 + c.other("immunity") / 100);
        List<String> complication = new ArrayList<>();
        for (Iterator<Case> it = c.diseases.iterator(); it.hasNext(); ) {
            Case k = it.next();
            Disease d = ALL.get(k.name);
            if (d == null) { it.remove(); continue; }
            k.acts.values().removeIf(u -> u >= 0 && u <= now);
            if (k.incubation > 0) {
                k.incubation -= 1;
                if (k.incubation > 1e-9) continue;
                k.incubation = 0;
                k.sev = 1e-6;
                out.add(d.name() + ": the first signs");
            }
            k.ill += 1;
            double virex = d.kind().contains("virus") ? 1 + Math.max(0, c.other("viral_immunity")) / 100 : 1;   // Virex (04j §7c): a virus's immunity ×2
            k.imm = Math.min(1, k.imm + d.rI() * speedBody * k.luck * (k.acts.containsKey("sanatorium") ? SANATORIUM : 1) * virex / 24);
            if (k.imm >= 1 && !k.immune) { k.immune = true; out.add(d.name() + ": the body has its answer, it fades from now"); }
            int si = d.stageAt(k.sev);
            double full = d.course() > 0 ? d.course() : ABX_DAYS;
            boolean shortCourse = k.abxDays > 0 && k.abxDays < full;
            double days = (shortCourse ? k.abxDays : full) / k.abxRate;
            if (!k.abxFamily.isEmpty() && k.abxStart < 0 && si >= d.abxStage()) {
                k.abxStart = now;
                out.add(d.name() + " recognised: the " + k.abxFamily + " course starts (" + f(days) + " days)");
            }
            double courseEnd = k.abxStart + days * 24;
            boolean abx = k.abxStart >= 0 && now < courseEnd && (d.bacterial() || si >= d.abxStage());
            if (k.abxStart >= 0 && now >= courseEnd) {
                k.abxStart = -1;
                k.abxFamily = "";
                k.abxDays = -1;
                if (d.course() > 0 && !shortCourse) {   // a long course run to its end cures (the simulation's rule); a short one does not
                    it.remove();
                    out.add(d.name() + ": the full course is done, cured" + cured(c, d));
                    continue;
                }
                out.add(d.name() + ": the antibiotic course is over");
            }
            if (abx && d.drug() > 0) k.sev = Math.max(0.02, k.sev - d.drug() * k.abxRate / 24);   // a few germs live until the course ends
            else if (k.immune || abx) k.sev -= d.rRec() * (k.immune ? 1 : k.abxRate) / 24;
            else {
                double grow = d.rS() * k.dose * (k.acts.containsKey("tend") ? TEND : 1);
                if (d.absorbDays() > 0 && k.ill > d.absorbDays() * 24) grow = 0;   // a toxin: all of it absorbed
                if (k.acts.containsKey("support") && d.support() > 0) grow *= d.support();
                for (Map.Entry<String, Act> a : d.acts().entrySet())   // a sedative taken counts as the sedation (04c §4b)
                    if ((k.acts.containsKey(a.getKey()) || (a.getKey().equals("sedation") && c.other("sedation") > 0)) && si <= a.getValue().upTo())
                        grow *= a.getValue().x();
                k.sev += grow / 24;
            }
            if (k.sev <= 0) {
                it.remove();
                out.add(d.name() + ": over" + cured(c, d));
                continue;
            }
            boolean saved = k.acts.containsKey("support") && d.supportSaves();   // a breathing machine: the paralysis cannot kill
            if (saved) k.sev = Math.min(k.sev, .99);
            if (k.sev >= 1) {
                k.sev = 1;
                if (d.lethal() && !c.inArrest()) out.add(d.name() + " at its worst: " + c.stopHeart(d.name(), true, rnd));
            }
            int ni = Math.max(0, d.stageAt(k.sev));
            Stage s = d.stages().get(ni);
            if (ni != k.stage) {
                k.stage = ni;
                setEffect(c, d, ni);
                out.add(d.name() + ": " + s.name() + " (" + stageText(s) + ")");
                if (d.name().equals("Influenza") && ni == 2) complication.add("Pneumonia");   // 04b §5: Bedridden flu, 5 %
            }
            double wh = s.waterH(), fh = s.foodH();
            if (GUT.contains(d.name())) {   // 04c §4b, §5: opium and codeine slow the gut, tannins, cannabis against the vomiting
                if (wh < 0) wh = Math.min(0, wh * Math.max(0, 1 + c.other("runs")) + c.other("runs_back"));
                if (fh < 0 && c.other("nausea") > 0) fh = 0;
            }
            if (wh != 0) bump(c, Rules.Stat.WATER, wh);
            if (fh != 0) bump(c, Rules.Stat.FOOD, fh);
            if (s.pain() > 0) c.survival.put(Rules.Stat.PAIN, Math.max(c.survival.getOrDefault(Rules.Stat.PAIN, 0.0), s.pain()));
            if (!saved && s.hpH() < 0) {
                c.diseaseHp += s.hpH();
                while (c.diseaseHp <= -1) { c.hp -= 1; c.diseaseHp += 1; }
            }
        }
        for (String n : complication) expose(c, n, 5, false, false, rnd);
        return out.length() == 0 ? null : out.toString();
    }

    /**
     * The sick nearby (04b §5, RULED round 7): an hour beside someone whose disease has shown is that day's household chance spread over
     * the day's hours (dysentery 4 %, cholera 2 %, hepatitis A 3 %, a cold 3 %, the flu 2 % a day; measles 90 % for the unvaccinated);
     * soap × 0.7. The neighbours are the HUD's (within 8 blocks).
     */
    static void nearby(Character c, Random rnd) {
        if (c.nearby == null || c.nearby.isEmpty()) return;
        for (Character x : new ArrayList<>(c.nearby)) {
            if (x == c || x.dead) continue;
            for (Case kx : new ArrayList<>(x.diseases)) {
                Disease d = ALL.get(kx.name);
                if (d == null || d.contagion() <= 0 || kx.incubation > 0 || active(c, d.name()) != null) continue;
                double hourly = 100 * (1 - Math.pow(1 - d.contagion() / 100, 1.0 / 24));
                expose(c, d.name(), hourly, true, c.soap, rnd);
            }
        }
    }

    /**
     * The giant tick's bite (`09b` §4, Marek 11-24: the standard ticks were removed as too random; the giant tick is seen and can be
     * killed): what one tick bite carries in life (04b §5): tick-borne encephalitis 2 % infected, a third of them ill; Lyme 1 % (it
     * cannot stay on unseen for a day). Null if nothing.
     */
    static String tickBite(Character c, Random rnd) {
        if (c.dead) return null;
        StringJoiner j = new StringJoiner("; ");
        for (String r : new String[]{expose(c, "Tick-borne encephalitis", 2.0 / 3, false, false, rnd), expose(c, "Lyme disease", 1, false, false, rnd)})
            if (r.contains("CAUGHT")) j.add(r);
        return j.length() == 0 ? null : j.toString();
    }

    /** 04b §5: a wound through the skin rolls its infection by its class, and tetanus at 1 % (the vaccine keeps it off); null if none. */
    static String wound(Character c, String cls, Random rnd) {
        if (c.dead) return null;
        StringJoiner j = new StringJoiner("; ");
        // round 9 (Marek 11-21: *"it should make infections and wounds/healing worse"*): Filthy (7 days unwashed) doubles the roll
        double pct = WOUND.getOrDefault(cls, 15.0) * (c.unwashedDays >= 7 ? 2 : 1);
        String w = expose(c, "Wound infection", pct, false, false, rnd);
        if (w.contains("CAUGHT")) j.add(w);
        if (!cls.equals("cut")) {
            String t = expose(c, "Tetanus", 1, false, false, rnd);
            if (t.contains("CAUGHT")) j.add(t);
        }
        return j.length() == 0 ? null : j.toString();
    }

    /**
     * 04b §3b (RULED round 6): every game year the lifetime's rads roll for cancer, the rads × 0.002 % (100 rads ≈ 0.2 % a year, 1 000
     * ≈ 2 %), from the second year (leukemia; the solid cancers' fifth year folded in) [the folding mine].
     */
    static void cancerYear(Character c, Random rnd) {
        long h = Math.round(c.clock);
        if (h < 2 * YEAR || h % (long) YEAR != 0 || c.lifetimeRads <= 0 || active(c, "Cancer") != null) return;
        double pct = c.lifetimeRads * 0.002;
        if (100 * rnd.nextDouble() < pct && !c.luck(rnd).ok()) infect(c, ALL.get("Cancer"), rnd);   // Lucky Escape (04d §4)
    }

    private static void bump(Character c, Rules.Stat st, double v) {
        c.survival.put(st, Math.max(0, Math.min(c.max(st), c.survival.getOrDefault(st, c.max(st)) + v)));
    }

    /** The survival stage's level: none 0, Minor 1, Advanced 2, Critical 3, Deadly 4, Fatal 5. */
    static int level(String stage) {
        if (stage == null) return 0;
        return stage.startsWith("Minor") ? 1 : stage.startsWith("Advanced") ? 2 : stage.startsWith("Critical") ? 3 : stage.startsWith("Deadly") ? 4
                : stage.startsWith("Fatal") ? 5 : 0;
    }

    /**
     * The stage's effect in the slot {@code disease:<name>}; its first two stages never push an attribute under 1 (the limit gates,
     * as in the simulation); its HP Max and "cannot drink" ride along.
     */
    static void setEffect(Character c, Disease d, int i) {
        String slot = "disease:" + d.name();
        c.effects.removeIf(e -> e.slot().equals(slot));
        Stage s = d.stages().get(i);
        int[] mods = s.mods().clone();
        if (i < 2) for (int a = 0; a < 7; a++) if (mods[a] < 0) mods[a] = Math.max(mods[a], Math.min(0, 1 - c.attr(a, false)));
        Map<String, Double> other = new LinkedHashMap<>();
        if (s.hpMax() != 0) other.put("hp_max", s.hpMax());
        if (s.noDrink()) other.put("no_drink", 1.0);
        c.add(new Character.Effect(d.name() + ": " + s.name(), "disease", slot, false, mods, other, 1e6, null, 100));
    }

    private static String cured(Character c, Disease d) {
        c.effects.removeIf(e -> e.slot().equals("disease:" + d.name()));
        if (d.immuneDays() == 0) return " (no immunity after it)";
        c.immune.put(d.name(), new double[]{d.immuneDays() < 0 ? -1 : c.now() + d.immuneDays() * 24, d.immuneShare()});
        return " (immune " + (d.immuneDays() < 0 ? "for life" : "for " + f(d.immuneDays() / 365) + " years")
                + (d.immuneShare() < 1 ? ", partly" : "") + ")";
    }

    static String stageText(Stage s) {
        StringJoiner j = new StringJoiner(", ");
        String names = "SPECIAL";
        for (int a = 0; a < 7; a++) if (s.mods()[a] != 0) j.add(names.charAt(a) + " " + (s.mods()[a] > 0 ? "+" : "") + s.mods()[a]);
        if (s.waterH() != 0) j.add("water " + f(s.waterH()) + " an hour");
        if (s.foodH() != 0) j.add("food " + f(s.foodH()) + " an hour");
        if (s.hpH() != 0) j.add("HP " + f(s.hpH()) + " an hour");
        if (s.hpMax() != 0) j.add("HP Max " + f(s.hpMax()));
        if (s.pain() > 0) j.add("Pain " + f(s.pain()));
        if (s.noDrink()) j.add("cannot drink");
        return j.toString();
    }

    // ------------------------------------------------------------------ the actions (04c §7)
    /** What the medic can do for this disease now (the menu offers these). */
    static List<String> actions(Disease d, Case k) {
        List<String> l = new ArrayList<>();
        if (k.incubation > 0) { if (d.name().equals("Rabies")) l.add("vaccine"); return l; }
        if (d.name().equals("Cancer")) {   // 04b §3b: surgery while local, pre-war radio- or chemotherapy until it is terminal
            if (k.stage <= 0) l.add("surgery");
            if (k.stage <= 1) l.add("radiotherapy");
            return l;
        }
        l.add("tend");
        if (d.name().equals("Tuberculosis")) l.add("sanatorium");
        if (!d.families().isEmpty()) l.add("antibiotics");
        if (d.support() > 0) l.add("support");
        l.addAll(d.acts().keySet());
        if (d.debride() > 0) l.add("debride");
        if (d.amputable()) l.add("amputate");
        return l;
    }

    static String actText(Disease d, String act) {
        return switch (act) {
            case "tend" -> "a carer's day: the growth × 0.7 for 24 hours; the carer's Medicine at least " + CARE.getOrDefault(d.name(), 20) + " + " + TEND_STAGE + " × the stage (1-4)";
            case "sanatorium" -> "the sanatorium: food, rest and clean air, the immunity × " + f(SANATORIUM) + " (on until stopped); isolated";
            case "antibiotics" -> "a 7-day course from the hour it is recognised (its second stage); works for: " + String.join(", ", d.families());
            case "support" -> (d.name().equals("Botulism") ? "breathing kept up: the paralysis cannot kill" : "breathing kept up: the growth × " + f(d.support()))
                    + "; someone at it all the time (on until stopped)";
            case "sedation" -> "a dark quiet room and sedatives: the spasms × " + f(d.acts().get(act).x());
            case "antitoxin" -> "the antitoxin: the growth × " + f(d.acts().get(act).x());
            case "maggots" -> "maggots in the wound: the growth × 0.5 while it is local";
            case "honey" -> "honey or sugar packed in: the growth × 0.8 while it is local";
            case "debride" -> "cut away the dead and dirty tissue: the severity −" + f(100 * d.debride()) + " % at once; Pain 30, 5 % of the Blood";
            case "amputate" -> "the limb off: the infection gone; 5 HP, a quarter of the Blood, Pain 60";
            case "vaccine" -> "the vaccine series in the incubation: it never shows (two doses if vaccinated before)";
            case "surgery" -> "cut out while it is local: high Medicine and an operating room (the GM's check); cured";
            case "radiotherapy" -> "pre-war radiotherapy or chemotherapy (a hospital's machines, its drugs): cured while it has not reached its end";
            default -> act;
        };
    }

    /**
     * The tending check (04c §4: a Medicine check a day; Marek 11-24: *"some diseases should be harder for medic than other, please look
     * into resolving that"*): the carer's Medicine at least the disease's CARE + {@link #TEND_STAGE} × its stage (1-4). The care by what
     * nursing it needs in life [the classes and numbers mine, EXAMPLE]: home care 0 (rest and fluids), nursing 10 (the fever watched,
     * fluids and food pushed), medical 20 (rehydration measured, a wound dressed, the lungs watched: a medic's work), intensive 30
     * (breathing, spasms, the brain: an intensive-care nurse's work).
     */
    static final int TEND_STAGE = 15;
    static final Map<String, Integer> CARE = Map.ofEntries(
            Map.entry("Common cold", 0), Map.entry("Food poisoning", 0),
            Map.entry("Influenza", 10), Map.entry("Dysentery", 10), Map.entry("Hepatitis A", 10), Map.entry("Measles", 10), Map.entry("Lyme disease", 10),
            Map.entry("Cholera", 20), Map.entry("Typhoid", 20), Map.entry("Pneumonia", 20), Map.entry("Wound infection", 20),
            Map.entry("Leptospirosis", 20), Map.entry("Tuberculosis", 20), Map.entry("Epidemic typhus", 20),
            Map.entry("Tetanus", 30), Map.entry("Botulism", 30), Map.entry("Tick-borne encephalitis", 30), Map.entry("Rabies", 30));

    /** The Medicine tending this disease needs at this stage. */
    static int tendNeed(Disease d, Case k) { return CARE.getOrDefault(d.name(), 20) + TEND_STAGE * (Math.max(0, k.stage) + 1); }

    /** A medic's action on one disease; {@code family} and {@code strength} for antibiotics (the old stores half, sulfa 0.7). */
    static String treat(Character c, String name, String act, String family, double strength) { return treat(c, name, act, family, strength, null); }

    /** The same by a carer (null = the GM's word): tending is a Medicine check against the disease's stage (no roll: a comparison). */
    static String treat(Character c, String name, String act, String family, double strength, Character carer) {
        Disease d = find(name);
        if (d == null) return "! no disease " + name;
        Case k = active(c, d.name());
        if (k == null) return "! not ill with " + d.name();
        if (!actions(d, k).contains(act)) return "! " + act + " does nothing for " + d.name() + (k.incubation > 0 ? " before it shows" : "");
        double now = c.now();
        switch (act) {
            case "tend" -> {
                int need = tendNeed(d, k);
                double med = carer == null ? need : carer.skill("Medicine");
                if (!Rules.check(med, need)) return "! " + carer.name + "'s Medicine " + Math.round(med) + " is under the " + need + " this stage needs: no tending";
                k.acts.put("tend", now + 24);
                return d.name() + ": tended for the day (the growth × 0.7" + (carer == null ? "" : "; Medicine " + Math.round(med) + " of " + need) + ")";
            }
            case "sanatorium" -> {
                if (k.acts.remove("sanatorium") != null) return d.name() + ": out of the sanatorium";
                k.acts.put("sanatorium", -1.0);
                return d.name() + ": in the sanatorium (the immunity × " + f(SANATORIUM) + ")";
            }
            case "antibiotics" -> {
                if (!d.families().contains(family)) return "! " + family + " does nothing for " + d.name() + " (it takes " + String.join(", ", d.families()) + ")";
                if (strength <= 0) return "! these antibiotics no longer work (over 10 years past day D)";
                k.abxFamily = family;
                k.abxRate = strength;
                return d.name() + ": " + family + (strength < 1 ? " at " + f(100 * strength) + " %" : "") + " given; the course starts when it is recognised (its second stage)";
            }
            case "support" -> {
                if (k.acts.remove("support") != null) return d.name() + ": breathing support stopped";
                k.acts.put("support", -1.0);
                return d.name() + ": breathing kept up";
            }
            case "debride" -> {
                k.sev = Math.max(0.01, k.sev - d.debride());
                c.survival.put(Rules.Stat.PAIN, c.survival.getOrDefault(Rules.Stat.PAIN, 0.0) + 30);
                bump(c, Rules.Stat.BLOOD, -0.05 * c.max(Rules.Stat.BLOOD));
                return d.name() + ": debrided, the severity now " + f(100 * k.sev) + " %; Pain +30, 5 % of the Blood";
            }
            case "amputate" -> {
                c.diseases.remove(k);
                c.effects.removeIf(e -> e.slot().equals("disease:" + d.name()));
                c.hp -= 5;
                c.survival.put(Rules.Stat.PAIN, c.survival.getOrDefault(Rules.Stat.PAIN, 0.0) + 60);
                bump(c, Rules.Stat.BLOOD, -0.25 * c.max(Rules.Stat.BLOOD));
                return d.name() + ": the limb is off and the infection with it; HP −5, Pain +60, a quarter of the Blood (the GM: which limb; a prosthesis later)";
            }
            case "vaccine" -> {
                c.diseases.remove(k);
                return d.name() + ": the vaccine series in time, it never shows";
            }
            case "surgery", "radiotherapy" -> {
                c.diseases.remove(k);
                c.effects.removeIf(e -> e.slot().equals("disease:" + d.name()));
                return d.name() + ": " + (act.equals("surgery") ? "cut out" : "treated") + ", cured";
            }
            default -> { k.acts.put(act, -1.0); return d.name() + ": " + actText(d, act); }
        }
    }

    /** Pre-war antibiotics by the years since day D (04c §4c, RULED): full for 5 years, half from 5 to 10, nothing after. */
    static double prewarStrength(double years) { return years < 5 ? 1 : years < 10 ? 0.5 : 0; }

    // ------------------------------------------------------------------ /sheet disease
    static String command(Character c, String[] a, Random rnd) {
        String usage = "! /sheet disease [list | catch <name> | expose <name> <chance %> [person] [soap] | treat <name> <act> [family] [strength]"
                + " | vaccine <name> [years ago] | cure <name>]; names without spaces (food_poisoning)";
        if (a.length < 2) return show(c);
        String what = a[1].toLowerCase(Locale.ROOT);
        if (what.equals("list")) return "Diseases: " + String.join(", ", ALL.keySet()) + "\n" + show(c);
        if (a.length < 3) return usage;
        Disease d = find(a[2]);
        if (d == null) return "! no disease " + a[2] + " (" + String.join(", ", ALL.keySet()) + ")";
        switch (what) {
            case "catch" -> { vaccinesAtStart(c); Case k = infect(c, d, rnd); return d.name() + ": infected; the first signs in " + time(k.incubation); }
            case "expose" -> {
                if (a.length < 4) return usage;
                boolean person = false, soap = false;
                for (int i = 4; i < a.length; i++) { person |= a[i].equals("person"); soap |= a[i].equals("soap"); }
                return expose(c, d.name(), Double.parseDouble(a[3]), person, soap, rnd);
            }
            case "treat" -> {
                if (a.length < 4) return usage;
                String fam = a.length > 4 ? a[4] : "";
                double str = a.length > 5 ? Double.parseDouble(a[5]) : prewarStrength(Travel.years());
                return treat(c, d.name(), a[3], fam, str);
            }
            case "vaccine" -> {
                double[] v = vaccine(d.name(), c.now(), a.length > 3 ? Double.parseDouble(a[3]) : 0);
                if (v == null) return "! no vaccine for " + d.name();
                vaccinesAtStart(c);
                c.immune.put(d.name(), v);
                return d.name() + ": vaccinated" + (d.name().equals("Rabies") ? " (after a bite two doses in the incubation still needed)" : "");
            }
            case "cure" -> {
                Case k = active(c, d.name());
                if (k == null) return "! not ill with " + d.name();
                c.diseases.remove(k);
                c.effects.removeIf(e -> e.slot().equals("disease:" + d.name()));
                return d.name() + ": cured (the GM)";
            }
            default -> { return usage; }
        }
    }

    /** The diseases in this body and what keeps others off, for the sheet and the GM. */
    static String show(Character c) {
        StringJoiner j = new StringJoiner("\n");
        for (Case k : c.diseases) {
            Disease d = ALL.get(k.name);
            if (d == null) continue;
            if (k.incubation > 0) { j.add(d.name() + ": incubating, the first signs in " + time(k.incubation) + " (the GM's)"); continue; }
            Stage s = d.stages().get(Math.max(0, k.stage));
            StringJoiner acts = new StringJoiner(", ");
            for (String x : k.acts.keySet()) acts.add(x);
            j.add(d.name() + ": " + s.name() + ", severity " + f(100 * k.sev) + " %, immunity " + f(100 * k.imm) + " %"
                    + (k.abxStart >= 0 ? ", on " + k.abxFamily : k.abxFamily.isEmpty() ? "" : ", " + k.abxFamily + " waiting")
                    + (acts.length() == 0 ? "" : ", " + acts));
        }
        StringJoiner im = new StringJoiner(", ");
        for (String n : c.immune.keySet()) { double s = kept(c, n); if (s > 0) im.add(n + (s < 1 ? " " + Math.round(100 * s) + " %" : "")); }
        if (j.length() == 0) j.add("No disease.");
        if (im.length() > 0) j.add("Protected: " + im);
        return j.toString();
    }

    /** Hours as "3 d 5 h" or "14 h". */
    static String time(double h) {
        long t = Math.round(h);
        return t >= 24 ? t / 24 + " d" + (t % 24 == 0 ? "" : " " + t % 24 + " h") : t + " h";
    }

    static String f(double v) { return Math.abs(v - Math.round(v)) < 1e-9 ? String.valueOf(Math.round(v)) : String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", ""); }
}
