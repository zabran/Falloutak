package cz.lollywood.falloutak;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * The rules' tables, moved out of the Google workbook by LollyWood-Lore `_tools/fallout_workbook_to_json.py` (species, effects,
 * items, factions): read from the jar's `data/` folder. Pure Java, so the tests read the same files.
 */
public final class Data {
    private Data() {}

    static final String[] ATTR_KEYS = {"strength", "perception", "endurance", "charisma", "intelligence", "agility", "luck"};

    /**
     * A template (part 3, `03b-SPECIES-RULES.md` §2, RULED 11-16; the seven = the workbook's Species tab). {@code who}: pc = on
     * every player's list, gm = the GM's list only, data = not in the EU, premium = bought from the CoS points (for the PC, or at
     * 2 x for the whole group when {@code groupUnlock}). {@code fixed}[i] >= 0 = a fixed attribute that takes no points.
     * Resistances are fractions (0.1 = 10 %); poison = poison AND disease, one value (RULED 11-16). perkRate 0 = never.
     */
    public record Template(String name, String family, String who, String cosPoints, boolean groupUnlock, int hp, int perkRate,
                           int attributePoints, int[] mods, int[] fixed, int skillPoints, double poisonRes, double radRes, double isolation,
                           double bonusDamage, double damageRes, int damageThreshold, Map<String, Double> skills, int healingRate,
                           int healingRateFed, String senses, double fallFactor, double foodFactor, double waterFactor, double healFactor,
                           double bleedFactor, double deathWindow, String atDeath, String sun, boolean huge, List<String> bans,
                           List<String> forbiddenTraits, String needs, String notes, double painGain, boolean genome, double sleepFactor,
                           List<Gene> genes, Map<String, Double> other) {
        public boolean premium() { return "premium".equals(who); }
        /** The genes' load (03c §1): the food factor already holds it. */
        public int load() { int s = 0; for (Gene g : genes) s += g.load(); return s; }
        public boolean fixedAttr(int i) { return fixed[i] >= 0; }
        public String perkText() { return perkRate <= 0 ? "no perks" : "a perk every " + perkRate + " levels"; }
    }

    /**
     * A gene of a template (`03c-GENES.md`): its slot (one gene a slot), tier T1-T4, load (the food: × (1 + 0.1 × the sum)), the
     * real variant behind it and what it does in the game. {@code trait}: a trait as a gene (03c §8, Marek 11-16), so that trait
     * cannot be picked again. Its numbers are already in the template's own fields; the list is what the sheet shows.
     */
    public record Gene(String name, String slot, String tier, int load, String real, String text, String rule, boolean free, int[] mods,
                       double food, double water, double sleep, double pain, Map<String, Double> skills, double poison, double rad,
                       double iso, int dt, int hp, Map<String, Double> other, String family, boolean adult, double dr, double melee,
                       int senses, double window, double heal, double bleed, double fall, String sun, String atDeath, boolean huge,
                       String aptitude) {
        /** Its place: one gene a slot, but the ORGAN genes each their own and the aptitudes one a skill (03c §1). */
        public String key() { return slot.equals("ORGAN") ? "ORGAN:" + name : slot.equals("Aptitude") ? "Aptitude:" + aptitude : slot; }
        public int tierNo() { return tier.length() > 1 && java.lang.Character.isDigit(tier.charAt(1)) ? tier.charAt(1) - '0' : 1; }

        /** Only its drawback (03d §5, radiation's twist): every number that helps dropped, every one that harms kept. */
        public Gene drawbackOnly() { return part(false); }

        /** Only its upside (03d §4, the blend: "the new one's upside without the old one's drawback"). */
        public Gene upsideOnly() { return part(true); }

        private Gene part(boolean good) {
            int[] m = new int[7];
            for (int i = 0; i < 7; i++) m[i] = (mods[i] > 0) == good ? mods[i] : 0;
            Map<String, Double> sk = new LinkedHashMap<>(), ot = new LinkedHashMap<>();
            skills.forEach((k, v) -> { if ((v > 0) == good) sk.put(k, v); });
            other.forEach((k, v) -> { boolean helps = k.equals("addiction") || k.equals("pain_gain") ? v < 0 : v > 0; if (helps == good) ot.put(k, v); });
            return new Gene(name, slot, tier, load, real, text, rule, free, m, keep(food, false, good), keep(water, false, good),
                    keep(sleep, false, good), keep(pain, false, good), sk, (poison > 0) == good ? poison : 0, (rad > 0) == good ? rad : 0,
                    (iso > 0) == good ? iso : 0, (dt > 0) == good ? dt : 0, (hp > 0) == good ? hp : 0, ot, family, adult,
                    (dr > 0) == good ? dr : 0, (melee > 0) == good ? melee : 0, good ? senses : 0, keep(window, true, good),
                    keep(heal, true, good), keep(bleed, false, good), keep(fall, false, good), good ? "" : sun, atDeath, huge, aptitude);
        }

        /** A factor kept when it falls on the wanted side ({@code higherBetter}: the window, healing), else 1. */
        private static double keep(double f, boolean higherBetter, boolean good) { return f == 1 ? 1 : ((f > 1) == higherBetter) == good ? f : 1; }
    }

    /**
     * An armour SET (`_notes/Fallout/05c-ARMOUR.md`; EXAMPLE sets from Fallout 1, 2 and Tactics, Marek 11-18): its pieces in the four
     * slots (05 §6b, RULED: Head, Chest with the hands, Legs, Feet), each piece protecting its parts with the set's DT and DR (by our
     * damage types; % for DR); the set's AC, weight and item health shared among its pieces by {@code shares}. Its own effects
     * ({@code mods}, the resistances, {@code skills}, {@code sealed}) only with every piece worn. {@code repair}: the skill that mends it.
     */
    public record Armour(String name, String source, int ac, Map<String, Double> dt, Map<String, Double> dr, Map<String, Double> shares,
                         List<String> pieces, double weightKg, int value, int health, int[] mods, double radRes, double poisonRes,
                         Map<String, Double> skills, boolean sealed, String repair, String material, String colour, int gasDr,
                         int tier, int rarity, boolean cooled, boolean powerArmour) {
        public int pieceAc(String slot) { return (int) Math.round(ac * shares.getOrDefault(slot, 0.0)); }
        public double pieceHealth(String slot) { return Math.max(10, health * shares.getOrDefault(slot, 0.0)); }
        public double pieceWeight(String slot) { return Math.round(weightKg * shares.getOrDefault(slot, 0.0) * 10) / 10.0; }
        public int dt(String type) { return (int) Math.round(dt.getOrDefault(type, 0.0)); }
        public double dr(String type) { return dr.getOrDefault(type, 0.0); }
    }

    public static final Map<String, Armour> ARMOUR = new LinkedHashMap<>();

    /** A gene set of the shop (03d §9, DRAFT 2 11-18): one course; an adult gets it without its puberty-only (shape) and ORGAN genes. */
    public record GeneSet(String name, List<String> genes, List<String> pubertyOnly, String shop) {}

    public static final Map<String, GeneSet> SETS = new LinkedHashMap<>();

    /** One gene a therapy lays over the template (03d §1): how much of it counts (½ in its last quarter), only its drawback (twisted) or upside (a blend). */
    public record Cover(Gene gene, double share, String state) {}

    /**
     * The template with the therapies' genes in place of its own in their slots (03d §1, RULED 11-17): every number moves by the
     * given gene's (× its share) minus the covered gene's (an empty slot covers nothing). The food moves by the factors and by the load's
     * difference (food × (1 + 0.01 × Δload)): a premium template keeps its own ruled factor as the base (03d §3, DRAFT 2 11-18).
     */
    public static Template withTherapies(Template t, List<Cover> covers) {
        if (covers.isEmpty()) return t;
        int[] m = t.mods().clone();
        double poison = t.poisonRes(), rad = t.radRes(), iso = t.isolation(), dr = t.damageRes(), melee = t.bonusDamage();
        int dt = t.damageThreshold(), hp = t.hp();
        double food = t.foodFactor(), water = t.waterFactor(), sleep = t.sleepFactor(), pain = t.painGain(), healF = t.healFactor(),
                bleedF = t.bleedFactor(), window = t.deathWindow(), fall = t.fallFactor();
        int senses = Rules.senses(t.senses());
        String sun = t.sun();
        Map<String, Double> skills = new LinkedHashMap<>(t.skills()), other = new LinkedHashMap<>(t.other());
        List<Gene> genes = new ArrayList<>(t.genes());
        for (Cover cv : covers) {
            Gene g = cv.state().equals("twisted") ? cv.gene().drawbackOnly() : cv.state().equals("blend") ? cv.gene().upsideOnly() : cv.gene();
            Gene old = null;
            for (Gene x : t.genes()) if (x.key().equals(g.key())) old = x;
            double s = cv.share();
            for (int i = 0; i < 7; i++) m[i] += Rules.scaleInt(g.mods()[i], s) - (old == null ? 0 : old.mods()[i]);
            hp += Rules.scaleInt(g.hp(), s) - (old == null ? 0 : old.hp());
            dt += Rules.scaleInt(g.dt(), s) - (old == null ? 0 : old.dt());
            poison += g.poison() * s - (old == null ? 0 : old.poison());
            rad += g.rad() * s - (old == null ? 0 : old.rad());
            iso += g.iso() * s - (old == null ? 0 : old.iso());
            dr += g.dr() * s - (old == null ? 0 : old.dr());
            melee += g.melee() * s - (old == null ? 0 : old.melee());
            food = swap(food, old == null ? 1 : old.food(), part(g.food(), s)) * Math.max(0.1, 1 + 0.01 * (g.load() - (old == null ? 0 : old.load())));
            water = swap(water, old == null ? 1 : old.water(), part(g.water(), s));
            sleep = swap(sleep, old == null ? 1 : old.sleep(), part(g.sleep(), s));
            pain = swap(pain, old == null ? 1 : old.pain(), part(g.pain(), s));
            healF = swap(healF, old == null ? 1 : old.heal(), part(g.heal(), s));
            bleedF = swap(bleedF, old == null ? 1 : old.bleed(), part(g.bleed(), s));
            window = swap(window, old == null ? 1 : old.window(), part(g.window(), s));
            fall = swap(fall, old == null ? 1 : old.fall(), part(g.fall(), s));
            if (old != null && old.senses() > 0) {
                int rest = 0;
                for (Gene x : t.genes()) if (x != old) rest = Math.max(rest, x.senses());
                senses = Math.max(rest, g.senses());
            } else senses = Math.max(senses, g.senses());
            if (old != null && !old.sun().isEmpty() && old.sun().equals(sun)) sun = g.sun();
            else if (!g.sun().isEmpty()) sun = g.sun();
            for (Map.Entry<String, Double> e : g.skills().entrySet()) skills.merge(e.getKey(), e.getValue() * s, Double::sum);
            if (old != null) for (Map.Entry<String, Double> e : old.skills().entrySet()) skills.merge(e.getKey(), -e.getValue(), Double::sum);
            for (Map.Entry<String, Double> e : g.other().entrySet()) other.merge(e.getKey(), e.getValue() * s, Double::sum);
            if (old != null) for (Map.Entry<String, Double> e : old.other().entrySet()) other.merge(e.getKey(), -e.getValue(), Double::sum);
            Gene gone = old;
            genes.removeIf(x -> x == gone);
            genes.add(cv.gene());
        }
        skills.values().removeIf(v -> Math.abs(v) < 1e-9);
        other.values().removeIf(v -> Math.abs(v) < 1e-9);
        String sens = senses >= 2 ? "night vision" : senses == 1 ? "dark halved" : "";
        return new Template(t.name(), t.family(), t.who(), t.cosPoints(), t.groupUnlock(), hp, t.perkRate(), t.attributePoints(), m, t.fixed(),
                t.skillPoints(), poison, rad, iso, melee, dr, dt, skills, t.healingRate(), t.healingRateFed(), sens, fall, food, water, healF,
                bleedF, window, t.atDeath(), sun, t.huge(), t.bans(), t.forbiddenTraits(), t.needs(), t.notes(), pain, t.genome(), sleep, genes, other);
    }

    /** A factor at a share: half its way from 1 (×0.6 at ½ → ×0.8). */
    static double part(double f, double share) { return 1 + (f - 1) * share; }

    /** The template's factor with the covered gene's taken out and the new one's put in (a covered 0, No pain's, is simply replaced). */
    static double swap(double base, double old, double now) { return old == 0 ? now : base / old * now; }

    /** The genes a player may build his own template from (03c, Marek 11-16: "the T1 and some of the weaker T2, that are used here"). */
    public static final Map<String, Gene> GENES = new LinkedHashMap<>();
    /** A player's own template: its name in {@link Character#template}. */
    public static final String CUSTOM = "Own genes";
    /**
     * The limit of an own template (Marek 11-17): a load of at most +20 on the finer scale (food × (1 + 0.01 × load), genes from −20 to
     * +20), no count of genes (every gene without a real drawback costs load) and no trait limit (a gene is a gene).
     */
    public static final int CUSTOM_MAX_LOAD = 20;
    /** 03c §8: the traits that are genes, by slot; a filled slot bars them. */
    public static final Map<String, List<String>> GENE_TRAITS = new LinkedHashMap<>();

    /** Null if these genes make a legal own template, else why not. */
    public static String checkCustom(List<String> names) {
        if (names.isEmpty()) return "pick at least one gene";
        Set<String> slots = new HashSet<>();
        int load = 0;
        for (String n : names) {
            Gene g = GENES.get(n);
            if (g == null || !g.free()) return n + " is not a free gene";
            if (!slots.add(g.slot())) return "two genes in the slot " + g.slot();
            load += g.load();
        }
        if (load > CUSTOM_MAX_LOAD) return "the load is +" + load + ", at most +" + CUSTOM_MAX_LOAD;
        return null;
    }

    /** A player's own template, summed from its genes as the basic ones are (the generator's sums()): the baseline human + the genes. */
    public static Template custom(List<String> names) {
        Template h = TEMPLATES.get("Human");
        int[] m = new int[7];
        double food = 1, water = 1, sleep = 1, pain = 1, poison = 0, rad = 0, iso = 0;
        int dt = 0, hp = 0, load = 0;
        Map<String, Double> skills = new LinkedHashMap<>(), other = new LinkedHashMap<>();
        List<Gene> gs = new ArrayList<>();
        List<String> forb = new ArrayList<>();
        for (String n : names) {
            Gene g = GENES.get(n);
            if (g == null) continue;
            gs.add(g);
            for (int i = 0; i < 7; i++) m[i] += g.mods()[i];
            food *= g.food(); water *= g.water(); sleep *= g.sleep(); pain *= g.pain();
            poison += g.poison(); rad += g.rad(); iso += g.iso(); dt += g.dt(); hp += g.hp(); load += g.load();
            g.skills().forEach((k, v) -> skills.merge(k, v, Double::sum));
            g.other().forEach((k, v) -> other.merge(k, v, Double::sum));
            for (String tr : GENE_TRAITS.getOrDefault(g.slot(), List.of())) if (!forb.contains(tr)) forb.add(tr);
        }
        food *= 1 + 0.01 * load;
        int[] fixed = new int[7];
        Arrays.fill(fixed, -1);
        return new Template(CUSTOM, "human", "pc", "", true, h.hp() + hp, h.perkRate(), h.attributePoints(), m, fixed, h.skillPoints(),
                poison, rad, iso, 0, 0, dt, skills, 0, 0, "", 1, food, water, 1, 1, 1, "", "", false, List.of(), forb, "",
                "your own genes (03c §7), load " + (load >= 0 ? "+" : "") + load, pain, true, sleep, gs, other);
    }

    /**
     * An implant (03b §3b, RULED 11-18): its slot, where a hit costs it condition, and its numbers at each tier it exists in (I-IV,
     * each its own, no factor: today's experimental device T I, a mature one T II, T III-IV only where nothing exists today).
     * {@code mods}/{@code other}/{@code senses} = its highest tier's, for the lists.
     */
    public record Implant(String name, String slot, String place, int[] mods, Map<String, Double> other, String senses, String needs,
                          int skillChoice, String notes, NavigableMap<Integer, At> tiers) {
        /** One tier's numbers and its text. */
        public record At(int[] mods, Map<String, Double> other, String senses, String notes) {}
        public At at(int tier) { return tiers.get(tier); }
        public int minTier() { return tiers.firstKey(); }
        public int maxTier() { return tiers.lastKey(); }
    }

    /**
     * A trait of part 2 (`02c` §1, ACCEPTED 11-14; `data/traits.json`, made by `_tools/fallout_traits_json.py`): the traits that
     * became genes are left out (Marek 11-17). {@code status}: in, parked (research and production, until parts 7-8), cos (Fast
     * Shot, for CoS points). {@code mods}/{@code other}: the numbers the sheet applies as a permanent effect [mine]; empty = the GM
     * applies it (it acts in a rule the plugin does not run yet).
     */
    public record Trait(String name, String from, String group, String bonus, String drawback, String status, int[] mods, Map<String, Double> other) {
        public boolean hasNumbers() {
            for (int m : mods) if (m != 0) return true;
            return !other.isEmpty();
        }
    }

    public static final Map<String, Trait> TRAITS = new LinkedHashMap<>();

    /** A chem's stage: how long, what it does. */
    public record Stage(double hours, int[] mods, Map<String, Double> other) {}

    /**
     * A chem (03b §8): a chain of stages, effect, crash, then (if addicted) withdrawal and dependence; the addiction chance a dose
     * (or a day of use, {@code perDay}); {@code cures} = an antidote's chem.
     */
    public record Chem(String name, double chance, boolean perDay, Stage effect, Stage crash, Stage withdrawal, Stage dependence,
                       String cures, boolean example, String source) {
        public Stage stage(int i) { return switch (i) { case 0 -> effect; case 1 -> crash; case 2 -> withdrawal; case 3 -> dependence; default -> null; }; }
    }

    /** A mutation of the yearly roll (03b §7); {@code randomAttr} = that many points off a random attribute. */
    public record Mutation(String name, String category, int[] mods, int randomAttr, Map<String, Double> other, String notes) {}

    /** An effect row: a survival stage or a condition, with its attribute changes and the rest of its columns. */
    public record EffectRow(String name, String description, int[] mods, Map<String, Double> other) {}

    public static final Map<String, Template> TEMPLATES = new LinkedHashMap<>();
    public static final Map<String, EffectRow> EFFECTS = new LinkedHashMap<>();
    public static final Map<String, Implant> IMPLANTS = new LinkedHashMap<>();
    /** How many implants a slot takes (brain 2, the rest 1). */
    public static final Map<String, Integer> SLOTS = new LinkedHashMap<>();
    public static final Map<String, Chem> CHEMS = new LinkedHashMap<>();
    public static final List<Mutation> MUTATIONS = new ArrayList<>();
    public static final List<JsonObject> ITEMS = new ArrayList<>();
    /** The workbook's cell notes (`data/notes.json`), by tab and the cell's own text: the skills' and the stats' descriptions. */
    public static final Map<String, String> NOTES = new LinkedHashMap<>();

    static {
        for (JsonElement e : rows("species")) {
            JsonObject o = e.getAsJsonObject();
            int[] m = new int[7], fx = new int[7];
            JsonObject fixed = o.has("fixed") ? o.getAsJsonObject("fixed") : new JsonObject();
            for (int i = 0; i < 7; i++) {
                m[i] = (int) d(o, ATTR_KEYS[i] + "_modifier");
                fx[i] = fixed.has(ATTR_KEYS[i]) ? fixed.get(ATTR_KEYS[i]).getAsInt() : -1;
            }
            String name = o.get("name").getAsString();
            TEMPLATES.put(name, new Template(name, s(o, "family", ""), s(o, "who", "pc"), s(o, "cos_points", ""),
                    !o.has("faction_unlock") || o.get("faction_unlock").getAsBoolean(), (int) d(o, "hp_bonus"), (int) d(o, "perk_rate"),
                    Rules.ATTRIBUTE_POINTS + (int) d(o, "attribute_modifier"), m, fx, (int) d(o, "skill_point_bonus"),
                    d(o, "poison_resistance"), d(o, "rad_resistance"), d(o, "isolation"), d(o, "bonus_damage"), d(o, "damage_resistance"),
                    (int) d(o, "damage_threshold"), nums(o, "skills"), (int) d(o, "healing_rate"), (int) d(o, "healing_rate_fed"),
                    s(o, "senses", ""), d(o, "fall_factor", 1), d(o, "food_factor", 1), d(o, "water_factor", 1), d(o, "heal_factor", 1),
                    d(o, "bleed_factor", 1), d(o, "death_window", 1), s(o, "at_death", ""), s(o, "sun", ""),
                    o.has("huge") && o.get("huge").getAsBoolean(), strs(o, "bans"), strs(o, "forbidden_traits"), s(o, "needs", ""),
                    s(o, "notes", ""), d(o, "pain_gain", 1), o.has("genome") && o.get("genome").getAsBoolean(), d(o, "sleep_factor", 1),
                    genes(o), nums(o, "other")));
        }
        for (JsonElement e : rows("genes")) { Gene g = gene(e.getAsJsonObject()); GENES.put(g.name(), g); }
        for (JsonElement e : rows("sets")) {
            JsonObject o = e.getAsJsonObject();
            SETS.put(o.get("name").getAsString(), new GeneSet(o.get("name").getAsString(), strs(o, "genes"), strs(o, "puberty_only"), s(o, "shop", "")));
        }
        String[][] gt = {{"Immunity", "Garrakg Returnz"}, {"Blood", "Medical Marvel"}, {"Sleep", "Night Person"}, {"Growth", "Small Frame"},
                {"Muscle", "Bruiser"}, {"Metabolism", "Glutton", "Fast Metabolism", "Weird Loner"}, {"Chem response", "Chem Reliant", "Chem Resistant"},
                {"Skin", "Thick-Skinned"}, {"Frame", "Brittle Bones", "Circus Freak"}, {"Temperament", "Kamikaze", "Twitchy", "Hot Blooded"}, {"Mind", "Gifted"}};
        for (String[] r : gt) GENE_TRAITS.put(r[0], List.of(Arrays.copyOfRange(r, 1, r.length)));
        for (JsonElement e : rows("implants")) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("slots")) { for (String k : o.getAsJsonObject("slots").keySet()) SLOTS.put(k, o.getAsJsonObject("slots").get(k).getAsInt()); continue; }
            NavigableMap<Integer, Implant.At> tiers = new TreeMap<>();
            if (o.has("tiers")) {
                for (String k : o.getAsJsonObject("tiers").keySet()) tiers.put(Integer.parseInt(k), at(o.getAsJsonObject("tiers").getAsJsonObject(k)));
            } else tiers.put(4, at(o));   // the old one-tier form
            Implant.At top = tiers.lastEntry().getValue();
            String name = o.get("name").getAsString();
            IMPLANTS.put(name, new Implant(name, s(o, "slot", ""), s(o, "place", "torso"), top.mods(), top.other(), top.senses(),
                    s(o, "needs", ""), (int) d(o, "skill_choice"), s(o, "notes", ""), tiers));
        }
        for (JsonElement e : rows("armour")) {
            JsonObject o = e.getAsJsonObject();
            String name = o.get("name").getAsString();
            ARMOUR.put(name, new Armour(name, s(o, "source", ""), (int) d(o, "ac"), nums(o, "dt"), nums(o, "dr"), nums(o, "shares"), strs(o, "pieces"),
                    d(o, "weight_kg"), (int) d(o, "value"), (int) d(o, "health"), attrs(o, "mods"), d(o, "rad_res"), d(o, "poison_res"),
                    nums(o, "skills"), o.has("sealed") && o.get("sealed").getAsBoolean(), s(o, "repair", "Crafting"), s(o, "material", "leather"),
                    s(o, "colour", ""), (int) d(o, "gas_dr"), (int) d(o, "tier", 1), (int) d(o, "rarity", 1),
                    o.has("cooled") && o.get("cooled").getAsBoolean(), o.has("power_armour") && o.get("power_armour").getAsBoolean()));
        }
        for (JsonElement e : rows("chems")) {
            JsonObject o = e.getAsJsonObject();
            String name = o.get("name").getAsString();
            CHEMS.put(name, new Chem(name, d(o, "chance"), "day".equals(s(o, "roll", "dose")), stage(o, "effect"), stage(o, "crash"),
                    stage(o, "withdrawal"), stage(o, "dependence"), s(o, "cures", null), o.has("example") && o.get("example").getAsBoolean(),
                    s(o, "source", "")));
        }
        for (JsonElement e : rows("mutations")) {
            JsonObject o = e.getAsJsonObject();
            JsonObject mo = o.has("mods") ? o.getAsJsonObject("mods") : new JsonObject();
            MUTATIONS.add(new Mutation(o.get("name").getAsString(), s(o, "category", "bad"), attrs(o, "mods"),
                    mo.has("random") ? mo.get("random").getAsInt() : 0, nums(o, "other"), s(o, "notes", "")));
        }
        for (JsonElement e : rows("effects")) {
            JsonObject o = e.getAsJsonObject();
            int[] m = new int[7];
            for (int i = 0; i < 7; i++) m[i] = (int) d(o, ATTR_KEYS[i]);
            Map<String, Double> other = new LinkedHashMap<>();
            for (String k : o.keySet())
                if (!k.equals("name") && !k.equals("description") && !Arrays.asList(ATTR_KEYS).contains(k) && o.get(k).isJsonPrimitive()
                        && o.get(k).getAsJsonPrimitive().isNumber()) other.put(k, o.get(k).getAsDouble());
            String name = o.get("name").getAsString();
            EFFECTS.put(name, new EffectRow(name, o.has("description") ? o.get("description").getAsString() : "", m, other));
        }
        // Oxygen's stages (03b §3c, RULED 11-18): not in the workbook's tab
        EFFECTS.putIfAbsent("Minor Hypoxia", new EffectRow("Minor Hypoxia", "under 50 % Oxygen", Rules.HYPOXIA.get("Minor Hypoxia"), Map.of()));
        EFFECTS.putIfAbsent("Critical Hypoxia", new EffectRow("Critical Hypoxia", "under 25 % Oxygen", Rules.HYPOXIA.get("Critical Hypoxia"), Map.of()));
        EFFECTS.putIfAbsent("Deadly Hypoxia", new EffectRow("Deadly Hypoxia", "Oxygen 0: unconscious, the heart stops after E minutes",
                Rules.HYPOXIA.get("Deadly Hypoxia"), Map.of()));
        for (JsonElement e : rows("traits")) {
            JsonObject o = e.getAsJsonObject();
            String name = o.get("name").getAsString();
            TRAITS.put(name, new Trait(name, s(o, "from", ""), s(o, "group", ""), s(o, "bonus", ""), s(o, "drawback", ""), s(o, "status", "in"),
                    attrs(o, "mods"), nums(o, "other")));
        }
        for (JsonElement e : rows("items")) ITEMS.add(e.getAsJsonObject());
        for (JsonElement e : rows("notes")) {
            JsonObject o = e.getAsJsonObject();
            NOTES.put(o.get("tab").getAsString() + "|" + o.get("label").getAsString().trim(), o.get("note").getAsString());
        }
    }

    /** The note on the cell holding {@code label} in {@code tab}, or "" (e.g. note("Character", "Sneak")). */
    public static String note(String tab, String label) { return NOTES.getOrDefault(tab + "|" + label, ""); }

    private static double d(JsonObject o, String k) { return d(o, k, 0); }

    private static List<Gene> genes(JsonObject o) {
        List<Gene> r = new ArrayList<>();
        if (o.has("genes")) for (JsonElement e : o.getAsJsonArray("genes")) r.add(gene(e.getAsJsonObject()));
        return r;
    }

    private static Gene gene(JsonObject g) {
        int[] m = new int[7];
        JsonObject mo = g.has("mods") ? g.getAsJsonObject("mods") : new JsonObject();
        for (int i = 0; i < 7; i++) m[i] = mo.has(ATTR_KEYS[i]) ? mo.get(ATTR_KEYS[i]).getAsInt() : 0;
        return new Gene(s(g, "name", ""), s(g, "slot", ""), s(g, "tier", ""), (int) d(g, "load"), s(g, "real", ""), s(g, "text", ""),
                s(g, "rule", ""), !g.has("free") || g.get("free").getAsBoolean(), m, d(g, "food", 1), d(g, "water", 1), d(g, "sleep", 1),
                d(g, "pain", 1), nums(g, "skills"), d(g, "poison"), d(g, "rad"), d(g, "iso"), (int) d(g, "dt"), (int) d(g, "hp"), nums(g, "other"),
                s(g, "family", ""), !g.has("adult") || g.get("adult").getAsBoolean(), d(g, "dr"), d(g, "melee"), (int) d(g, "senses"),
                d(g, "window", 1), d(g, "heal", 1), d(g, "bleed", 1), d(g, "fall", 1), s(g, "sun", ""), s(g, "at_death", ""),
                g.has("huge") && g.get("huge").getAsBoolean(), s(g, "aptitude", ""));
    }

    private static double d(JsonObject o, String k, double dflt) {
        return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isNumber() ? o.get(k).getAsDouble() : dflt;
    }

    private static String s(JsonObject o, String k, String dflt) { return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : dflt; }

    private static List<String> strs(JsonObject o, String k) {
        List<String> l = new ArrayList<>();
        if (o.has(k) && o.get(k).isJsonArray()) o.getAsJsonArray(k).forEach(x -> l.add(x.getAsString()));
        return l;
    }

    /** A JSON object of numbers as a map (keys kept). */
    private static Map<String, Double> nums(JsonObject o, String k) {
        Map<String, Double> m = new LinkedHashMap<>();
        if (o.has(k) && o.get(k).isJsonObject())
            for (String x : o.getAsJsonObject(k).keySet()) {
                JsonElement v = o.getAsJsonObject(k).get(x);
                if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) m.put(x, v.getAsDouble());
            }
        return m;
    }

    /** {"strength": 2, ...} as the seven. */
    private static int[] attrs(JsonObject o, String k) {
        int[] m = new int[7];
        Map<String, Double> n = nums(o, k);
        for (int i = 0; i < 7; i++) m[i] = (int) Math.round(n.getOrDefault(ATTR_KEYS[i], 0.0));
        return m;
    }

    private static Stage stage(JsonObject o, String k) {
        if (!o.has(k) || !o.get(k).isJsonObject()) return null;
        JsonObject s = o.getAsJsonObject(k);
        return new Stage(d(s, "hours"), attrs(s, "mods"), nums(s, "other"));
    }

    /** One implant tier's numbers from its JSON (mods, other, skills as "skill:<name>", senses, notes). */
    static Implant.At at(JsonObject o) {
        Map<String, Double> other = nums(o, "other");
        for (Map.Entry<String, Double> sk : nums(o, "skills").entrySet()) other.put("skill:" + sk.getKey(), sk.getValue());
        return new Implant.At(attrs(o, "mods"), other, s(o, "senses", ""), s(o, "notes", ""));
    }

    /** A template, implant or chem by name: any case, spaces, _ and - ignored ("bone_lacing", "radaway"). */
    public static <T> T find(Map<String, T> m, String name) {
        if (name == null) return null;
        String n = name.replaceAll("[ _-]", "");
        for (Map.Entry<String, T> e : m.entrySet()) if (e.getKey().replaceAll("[ _-]", "").equalsIgnoreCase(n)) return e.getValue();
        return null;
    }

    private static List<JsonElement> rows(String name) {
        try (InputStream in = Data.class.getResourceAsStream("/data/" + name + ".json")) {
            if (in == null) return List.of();
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            List<JsonElement> l = new ArrayList<>();
            root.getAsJsonArray("rows").forEach(l::add);
            return l;
        } catch (Exception e) {
            throw new IllegalStateException("data/" + name + ".json: " + e.getMessage(), e);
        }
    }

    /** "−1 Agility, −2 Charisma" for a tooltip. */
    public static String effectText(String stage) {
        EffectRow r = EFFECTS.get(stage);
        if (r == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++)
            if (r.mods()[i] != 0) sb.append(sb.isEmpty() ? "" : ", ").append(r.mods()[i] > 0 ? "+" : "").append(r.mods()[i]).append(" ").append(Rules.SPECIAL[i]);
        for (Map.Entry<String, Double> e : r.other().entrySet())
            if (e.getValue() != 0) sb.append(sb.isEmpty() ? "" : ", ").append(e.getValue() > 0 ? "+" : "").append(SheetGui.f(e.getValue())).append(" ")
                    .append(switch (e.getKey()) {
                        case "hp_current" -> "HP an hour";
                        case "hp_once" -> "HP once, when it begins";
                        case "hp_max" -> "HP max";
                        case "ap" -> "AP";
                        default -> e.getKey().replace('_', ' ');
                    });
        return sb.toString();
    }

    /** The workbook's weapon by name (the 10mm Pistol is its one weapon). */
    public static JsonObject item(String name) {
        for (JsonObject o : ITEMS) if (o.has("name") && o.get("name").getAsString().equals(name)) return o;
        return null;
    }
}
