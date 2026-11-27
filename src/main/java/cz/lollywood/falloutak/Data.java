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
        /** A people's template, LOCKED until the player joins their faction (`09b` §4, Marek 11-23: the Mývalové *"will be locked to
         *  players unless they join their faction."*). */
        public boolean faction() { return "faction".equals(who); }
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

    /** A perk's gate alternative: a skill of {@code skills} (or the best of {@code any}: skill, weapon, science, melee) at {@code value}. */
    public record Alt(List<String> skills, String any, double value) {}

    /** A gate's alternatives from the perks' JSON ({@code [{"skills": [...], "any": "", "value": N}]}). */
    static List<Alt> altsOf(com.google.gson.JsonArray arr) {
        List<Alt> l = new ArrayList<>();
        for (JsonElement a : arr) {
            JsonObject x = a.getAsJsonObject();
            l.add(new Alt(strs(x, "skills"), s(x, "any", ""), x.get("value").getAsDouble()));
        }
        return List.copyOf(l);
    }

    /**
     * A perk of part 2 (`02c` §2-§3, ACCEPTED 11-14; `data/perks.json`, made by `_tools/fallout_perks_json.py`; Marek 11-20: *"please,
     * add our perks"*): its gate, its effect, {@code parked} until parts 7-8, {@code bound} = the player names the thing at the pick;
     * the numbers the sheet applies as a permanent effect, the rest is the GM's until its rule runs in the plugin.
     */
    public record Perk(String name, String kind, String gate, List<Alt> alts, String effect, boolean parked, boolean bound,
                       int[] mods, Map<String, Double> other, List<List<Alt>> also) {
        public boolean hasNumbers() {
            for (int m : mods) if (m != 0) return true;
            return !other.isEmpty();
        }
    }

    public static final Map<String, Perk> PERKS = new LinkedHashMap<>();
    /**
     * Nemesis's choices (02c, Marek 11-20: *"Should we not make it a selection?"*): the kinds of foe and the world's powers of `01`,
     * in `data/foes.json` [the list mine; the GM adds rows].
     */
    public record Foe(String name, String kind, String note) {}
    public static final List<Foe> FOES = new ArrayList<>();

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

    // ------------------------------------------------------------------ genes made in the game (09b round 12)
    /** The ghoul's own slot (09b round 12, Marek 11-23, verbatim: *"ghouls should be possible to reconstruct into genes as well - add a
     *  new slot if needed."*): the Ghoul gene and the ghoul line's stages, its variants. */
    public static final String NECROSIS = "Necrosis";

    /** The mutations that stay BODY CHANGES, no gene (03b §7a, ACCEPTED round 13): a disease begun, an injury (`data/mutations.json`). */
    public static final Set<String> BODY_CHANGES = Set.of("Tumour", "Withered Limb", "One Eye Blind");

    /** THE GENE POOL's genes (03b §7a) by their name in the pool: registered by the base ({@link Base}) when sampled or read. */
    public static final Map<String, Gene> POOL = new LinkedHashMap<>();

    /** A gene by name: the data's, else the pool's (null if neither). */
    public static Gene gene(String name) { Gene g = GENES.get(name); return g != null ? g : POOL.get(name); }

    /** A mutation of the data (the body changes) by name, or null. */
    public static Mutation mutation(String name) {
        for (Mutation m : MUTATIONS) if (m.name().equals(name)) return m;
        return null;
    }

    /**
     * The slots a mutation may land in (09b round 12, Marek 11-23, verbatim: *"the selected random mutation is applied to selected random
     * gene slot creating a new gene"*): every slot of 03c but the ORGAN genes and the aptitudes (several of each): the 21, and since round
     * 15 NECROSIS too (Marek, verbatim: *""did you mean that ordinary mutations should also be able to land in the Necrosis slot, giving a
     * little necrosis without becoming a ghoul?" - yes, that is what was meant."*): 22.
     */
    public static List<String> mutableSlots() {
        List<String> l = new ArrayList<>();
        for (Gene g : GENES.values())
            if (!g.slot().equals("ORGAN") && !g.slot().equals("Aptitude") && !l.contains(g.slot())) l.add(g.slot());
        if (!l.contains(NECROSIS)) l.add(NECROSIS);
        return l;
    }

    /** A little necrosis (round 15): what a mutation landing in the Necrosis slot adds to its steps: rad res +10 % [mine, EXAMPLE]. */
    public static final double NECROSIS_RAD = 0.10;

    /**
     * THE DIALS a mutation turns (03b §7a, the catalogue: round 13, Marek 11-23, verbatim: *"I think defining all possible effects that
     * mutation can give would be better."* · *"less slot specific/less requiring other genes"*; round 14: *"the 18-dial mutation catalogue -
     * this seems fine, just attributes should be 7 options instead additional of d7 roll."*): 24 dials, the seven attributes each its own;
     * each a number the gene system carries, with a good and a bad step the size of a T1 gene [the steps mine, ACCEPTED round 14]; any dial in
     * any slot. The sun and melee damage are shown on the sheet; the plugin applies neither yet (nor a template's).
     */
    public static final List<String> DIALS = List.of("Strength", "Perception", "Endurance", "Charisma", "Intelligence", "Agility", "Luck",
            "HP max", "DT", "DR", "rad res", "poison res", "isolation", "food", "water", "sleep", "healing", "bleeding", "Pain", "dark", "skill",
            "melee", "falls", "dying");

    /** One step on a dial: good (+1) or bad (−1), single or double (a very bad result), and for the skill dial the skill it hit. */
    public record Step(String dial, int sign, int size, String skill) {
        /** A factor's step: the good or the bad one, to the size's power. */
        double factor(double good, double bad) { return Math.pow(sign > 0 ? good : bad, size); }

        public String text() {
            int k = sign * size;
            String pm = (k > 0 ? "+" : "−") + Math.abs(k);
            int ai = Arrays.asList(Rules.SPECIAL).indexOf(dial);
            if (ai >= 0) return dial + " " + pm;
            return switch (dial) {
                case "HP max" -> "HP max " + (k > 0 ? "+" : "−") + 3 * Math.abs(k);
                case "DT" -> "DT " + pm;
                case "DR" -> "DR " + (k > 0 ? "+" : "−") + 5 * Math.abs(k) + " %";
                case "rad res" -> "rad res " + (k > 0 ? "+" : "−") + 10 * Math.abs(k) + " %";
                case "poison res" -> "poison and disease " + (k > 0 ? "+" : "−") + 10 * Math.abs(k) + " %";
                case "isolation" -> "isolation " + (k > 0 ? "+" : "−") + 10 * Math.abs(k) + " %";
                case "food" -> "food need × " + r2(factor(0.9, 1.15));
                case "water" -> "water need × " + r2(factor(0.9, 1.15));
                case "sleep" -> "sleep need × " + r2(factor(0.85, 1.2));
                case "healing" -> "healing × " + r2(factor(1.25, 0.75));
                case "bleeding" -> "bleeding × " + r2(factor(0.75, 1.5));
                case "Pain" -> "Pain gained × " + r2(factor(0.85, 1.25));
                case "dark" -> sign > 0 ? "the dark halved" : size > 1 ? "the sun burns" : "the sun hurts";
                case "skill" -> skill + " " + (k > 0 ? "+" : "−") + 10 * Math.abs(k) + " %";
                case "melee" -> "melee damage " + (k > 0 ? "+" : "−") + 10 * Math.abs(k) + " %";
                case "falls" -> "fall damage × " + r2(factor(0.75, 1.5));
                case "dying" -> "clinical death's window × " + r2(factor(1.5, 0.75));
                default -> dial + " " + pm;
            };
        }

        private static String r2(double v) { return String.valueOf(Math.round(v * 100) / 100.0); }
    }

    /** What a mutation roll gave: a body change (its name), or the steps that change a gene. */
    public record Rolled(String bodyChange, List<Step> steps) {}

    /**
     * A mutation by its grade (03b §7a, the catalogue, round 14): good = one good step; neutral = one good and one bad step on another dial
     * (Fallout 76's pairs); bad = one bad step; very bad = one bad step at double, or a body change: the tumour one time in five (as ruled,
     * `04b` §3b), a withered limb or a blind eye one time in ten each [the split mine, ACCEPTED round 14].
     */
    public static Rolled rollMutation(String category, Random rnd) {
        switch (category) {
            case "good" -> { return new Rolled(null, List.of(step(rnd, 1, 1, null))); }
            case "bad" -> { return new Rolled(null, List.of(step(rnd, -1, 1, null))); }
            case "neutral" -> {
                Step g = step(rnd, 1, 1, null);
                return new Rolled(null, List.of(g, step(rnd, -1, 1, g.dial())));
            }
            default -> {
                int d = 1 + rnd.nextInt(10);
                if (d <= 2) return new Rolled("Tumour", List.of());
                if (d == 3) return new Rolled("Withered Limb", List.of());
                if (d == 4) return new Rolled("One Eye Blind", List.of());
                return new Rolled(null, List.of(step(rnd, -1, 2, null)));
            }
        }
    }

    /** How many times the skill dial is in the draw (round 15, Marek, verbatim: *"1 in 24 and then 1 in 18 is still too little chance.
     *  Please, Either increase heir weight to 6, or divide them into 6 mutations."*): 6 of 29. */
    public static final int SKILL_WEIGHT = 6;

    /** A random dial (not {@code notDial}), the skill dial {@link #SKILL_WEIGHT} times in the draw; for it a random base skill. */
    static Step step(Random rnd, int sign, int size, String notDial) {
        List<String> l = new ArrayList<>(DIALS);
        for (int i = 1; i < SKILL_WEIGHT; i++) l.add("skill");
        if (notDial != null) l.removeIf(x -> x.equals(notDial));
        String d = l.get(rnd.nextInt(l.size()));
        String sk = d.equals("skill") ? Rules.SKILLS.get(rnd.nextInt(Rules.SKILLS.size())).name() : "";
        return new Step(d, sign, size, sk);
    }

    /**
     * How to make a changed gene again (a body's, or a sample in the base's pool, 03b §7a): the gene it came from ("" = the plain human's,
     * none), its slot, how many changes hit it (the mutations, and the ghoul line's touch) and the mutations' steps; for a ghoul's body
     * ({@code ghoulish}) the ghoul line's way, and {@code turned} when the ghoul's own numbers are its parts (a body turned) rather than its
     * born genes (one born a ghoul); {@code name} = its name in the pool ("" in a body).
     */
    public static final class Recipe {
        public String name = "", base = "", slot = "";
        public int events;
        public List<Step> steps = new ArrayList<>();
        public List<String> ghoul = new ArrayList<>();
        public boolean ghoulish, turned;

        public Recipe() {}

        public Recipe(String base, String slot, int events, List<Step> steps, List<String> ghoul, boolean ghoulish, boolean turned) {
            this.base = base == null ? "" : base; this.slot = slot; this.events = events; this.steps = new ArrayList<>(steps);
            this.ghoul = new ArrayList<>(ghoul); this.ghoulish = ghoulish; this.turned = turned;
        }

        public Recipe copy() { return new Recipe(base, slot, events, steps, ghoul, ghoulish, turned); }

        /** What it is made of (two recipes with one key make the same gene). */
        public String key() { return base + "|" + slot + "|" + events + "|" + steps + "|" + ghoul + "|" + ghoulish + "|" + turned; }

        /** The gene it makes (under its pool name when it has one), or null when its parts are gone from the data. */
        public Gene build() {
            Gene b = base.isEmpty() ? null : gene(base);
            if (!base.isEmpty() && b == null) return null;
            Gene g;
            String root;
            List<Integer> gs = new ArrayList<>();   // the ghoul mutations whose change in this gene lasts (rounds 16-17: G1-G4)
            if (slot.equals(NECROSIS)) {
                Gene core = ghoulish ? ghoulVariant(b != null ? b : GENES.get("Ghoul"), ghoul) : b;
                g = steps.isEmpty() ? core : littleNecrosis(variant(core, NECROSIS, events, steps), events);
                root = ghoulish ? (b != null ? b.name() : "Ghoul") : b == null ? NECROSIS : b.name();
                int ns = ghoulish ? necrosisRad(ghoul)[1] : 0;   // the stage whose rad res lasts; glowing's return to 40 % leaves none
                if (ns > 0) gs.add(ns);
            } else {
                g = variant(b, slot, events, steps);
                Part p = ghoulish ? ghoulParts(ghoul, turned).get(slot) : null;
                if (p != null) g = withPart(g, p, slot);
                root = b == null ? slot : b.name();
                if (p != null) gs.addAll(p.stages());
            }
            if (g == null) return null;
            // round 16 (Marek, verbatim: "name the Ghoul mutations as G1-G4, so that it is clear."): the mutations' M and the ghoul line's G
            StringBuilder gn = new StringBuilder();
            for (int x : gs) gn.append(" G").append(x);
            String nm = !name.isEmpty() ? name : root + (events > 0 && !steps.isEmpty() ? " M" + events : "") + gn;
            return g.name().equals(nm) ? g : remake(g, nm, g.real(), g.text(), g.load(), g.mods(), g.other());
        }
    }

    /**
     * The ghoul line's number in a gene's name (round 16, Marek, verbatim: *"name the Ghoul mutations as G1-G4, so that it is clear."*): G1
     * the ghoul, G2 leathered, G3 husk, G4 glowing. Round 17 (verbatim: *"only the actual lasting changes should be in name. If for the
     * mutation G2 has lasting change and G4 adds something else, it is Gene G2 G4. If mutation G2 is completely subsumed (like if it never
     * happened) by G4, the name should be just Gene G4."*): a gene names every ghoul mutation whose change in it lasts (*Metabolism G3 G4*:
     * the husk's healing stays, the glowing one's E +2 replaced the leathered's E −1; *Look G4*; *Immunity G1 G2*); the Necrosis gene at
     * glowing is the plain *Ghoul* again (its rad res back to 40 %).
     */
    public static int ghoulStageNo(List<String> path) {
        return path == null || path.isEmpty() ? 1 : Arrays.asList(Rules.GHOUL_STAGES).indexOf(path.get(path.size() - 1)) + 2;
    }

    /** A little necrosis (round 15): a mutation in the Necrosis slot adds {@link #NECROSIS_RAD} rad res to its steps, each time. */
    static Gene littleNecrosis(Gene g, int events) {
        return new Gene(g.name(), g.slot(), g.tier(), g.load(), g.real(), g.text() + "; a little necrosis: rad res +" + Math.round(100 * NECROSIS_RAD * events)
                + " %", g.rule(), false, g.mods(), g.food(), g.water(), g.sleep(), g.pain(), g.skills(), g.poison(), g.rad() + NECROSIS_RAD * events, g.iso(),
                g.dt(), g.hp(), g.other(), g.family(), g.adult(), g.dr(), g.melee(), g.senses(), g.window(), g.heal(), g.bleed(), g.fall(), g.sun(),
                g.atDeath(), g.huge(), g.aptitude());
    }

    /** What the ghoul line puts into one of the body's slots (03b §7b, the lean Necrosis, round 15): attribute changes and other numbers. */
    public record Part(int[] mods, Map<String, Double> other, List<String> said, List<Integer> stages) {}

    /**
     * THE LEAN NECROSIS (03b §7b; round 15, Marek 11-23, verbatim: *"Is the lean Necrosis split above fine? - seems so. I presume that every
     * ghouls gene is now the mutation of current gene instead of their replacement."*): the ghoul line's numbers in the body's slots, each a
     * change of the gene there. {@code turned}: the ghoul's own numbers too (S −2 Muscle, P +3 Eyes, I +3 Mind, A −4 and HP −5 Frame, poison
     * +10 % Immunity); one born a ghoul has them in its born genes. The stages: each stage's number is its total against the base ghoul, so a
     * later stage replaces an earlier one's (C in Look −2, −4, −5); a WALKED stage adds its help and gift, a jumped one only its harm (round 9).
     */
    public static Map<String, Part> ghoulParts(List<String> path, boolean turned) {
        Map<String, int[]> mods = new LinkedHashMap<>();
        Map<String, Map<String, Double>> oth = new LinkedHashMap<>();
        Map<String, List<String>> said = new LinkedHashMap<>();
        // round 17: the ghoul mutations whose change in the slot LASTS (G1 the turn, G2-G4 the stages); a stage whose number a later stage
        // replaced is subsumed and not named
        Map<String, TreeSet<Integer>> lasting = new LinkedHashMap<>();
        int[] cur = {1};
        java.util.function.BiConsumer<String, Object[]> mod = (slot, a) -> {
            lasting.computeIfAbsent(slot, x -> new TreeSet<>()).add(cur[0]);
            mods.computeIfAbsent(slot, x -> new int[7])[(Integer) a[0]] += (Integer) a[1];
            said.computeIfAbsent(slot, x -> new ArrayList<>()).add(Rules.SPECIAL[(Integer) a[0]].substring(0, 1) + " " + ((Integer) a[1] > 0 ? "+" : "−")
                    + Math.abs((Integer) a[1]));
        };
        java.util.function.BiConsumer<String, Object[]> put = (slot, a) -> {
            lasting.computeIfAbsent(slot, x -> new TreeSet<>()).add(cur[0]);
            oth.computeIfAbsent(slot, x -> new LinkedHashMap<>()).merge((String) a[0], (Double) a[1], Double::sum);
            said.computeIfAbsent(slot, x -> new ArrayList<>()).add((String) a[2]);
        };
        if (turned) {
            mod.accept("Muscle", new Object[]{Rules.S, -2});
            mod.accept("Eyes", new Object[]{Rules.P, 3});
            mod.accept("Mind", new Object[]{Rules.I, 3});
            mod.accept("Frame", new Object[]{Rules.A, -4});
            put.accept("Frame", new Object[]{"hp_max", -5.0, "HP −5"});
            put.accept("Immunity", new Object[]{"poison_res", 10.0, "poison and disease +10 %"});
        }
        if (path != null && !path.isEmpty()) {
            Integer[] set = new Integer[7];
            int[] setAt = new int[7];
            int last = Arrays.asList(Rules.GHOUL_STAGES).indexOf(path.get(path.size() - 1));
            for (int k = 0; k <= last; k++) {
                String st = Rules.GHOUL_STAGES[k];
                boolean walked = path.contains(st);
                cur[0] = k + 2;
                switch (st) {
                    case "leathered" -> {
                        set[Rules.E] = -1; set[Rules.C] = -2; setAt[Rules.E] = setAt[Rules.C] = 2;
                        put.accept("Skin", new Object[]{"skill:Sneak", -10.0, "the smell, Sneak −10"});
                        if (walked) {
                            put.accept("Skin", new Object[]{"dt", 3.0, "the hide, DT +3"});
                            put.accept("Immunity", new Object[]{"poison_res", 10.0, "the hide's gift, poison +10 %"});
                        }
                    }
                    case "husk" -> {
                        set[Rules.C] = -4; set[Rules.I] = -2; setAt[Rules.C] = setAt[Rules.I] = 3;
                        put.accept("Metabolism", new Object[]{"healing_half", 1.0, "healing × ½"});
                        if (walked) put.accept("Pain", new Object[]{"pain_gain", -0.25, "the dried nerves, Pain gained × ¾"});
                    }
                    case "glowing" -> {
                        set[Rules.I] = -5; set[Rules.C] = -5; set[Rules.E] = 2; set[Rules.A] = 2;
                        setAt[Rules.I] = setAt[Rules.C] = setAt[Rules.E] = setAt[Rules.A] = 4;
                        put.accept("Eyes", new Object[]{"senses", 1.0, "its own light, the dark halved"});
                    }
                    default -> { }
                }
            }
            String[] home = {"Muscle", "Eyes", "Metabolism", "Look", "Mind", "Frame", null};   // S P E C I A L: where each attribute lives
            for (int i = 0; i < 7; i++)
                if (set[i] != null && set[i] != 0 && home[i] != null) { cur[0] = setAt[i]; mod.accept(home[i], new Object[]{i, set[i]}); }
        }
        Map<String, Part> out = new LinkedHashMap<>();
        for (String slot : said.keySet())
            out.put(slot, new Part(mods.getOrDefault(slot, new int[7]), oth.getOrDefault(slot, new LinkedHashMap<>()), said.get(slot),
                    new ArrayList<>(lasting.getOrDefault(slot, new TreeSet<>()))));
        return out;
    }

    /** A gene with a ghoul part laid into it (the gene's own numbers kept, as a mutation of it). */
    static Gene withPart(Gene g, Part p, String name) {
        int[] m = g.mods().clone();
        for (int i = 0; i < 7; i++) m[i] += p.mods()[i];
        Map<String, Double> other = new LinkedHashMap<>(g.other());
        p.other().forEach((k, v) -> other.merge(k, v, Double::sum));
        other.values().removeIf(v -> Math.abs(v) < 1e-9);
        return remake(g, name, "a mutation of " + g.name() + " (the ghoul line)", (g.text().isEmpty() ? g.real() : g.text())
                + "; the ghoul line: " + String.join(", ", p.said()), g.load(), m, other);
    }

    /** An empty gene: the plain human's in a slot (nothing of its own). */
    static Gene plain(String slot) {
        return new Gene("plain " + slot, slot, "T1", 0, "", "the plain human's " + slot, "", false, new int[7], 1, 1, 1, 1, new LinkedHashMap<>(),
                0, 0, 0, 0, 0, new LinkedHashMap<>(), "", true, 0, 0, 0, 1, 1, 1, 1, "", "", false, "");
    }

    /** A gene with other numbers and name (the rest kept). */
    static Gene remake(Gene g, String name, String real, String text, int load, int[] mods, Map<String, Double> other) {
        return new Gene(name, g.slot(), g.tier().isEmpty() ? "T1" : g.tier(), load, real, text, g.rule(), false, mods, g.food(), g.water(), g.sleep(),
                g.pain(), g.skills(), g.poison(), g.rad(), g.iso(), g.dt(), g.hp(), other, g.family(), g.adult(), g.dr(), g.melee(), g.senses(),
                g.window(), g.heal(), g.bleed(), g.fall(), g.sun(), g.atDeath(), g.huge(), g.aptitude());
    }

    /**
     * A gene CHANGED by mutations (03b §7a; 09b round 12, Marek 11-23, verbatim: *"the selected random mutation is applied to selected random
     * gene slot creating a new gene. There genes are not pre-build (too many options)."*; the catalogue, rounds 13-14): the gene in the slot
     * ({@code base}; null = the plain human's) with each step of the dials; its tier the gene's (the plain one T1), its load the gene's + 4 a
     * good step, − 4 a bad one (double: − 8): good +4, neutral 0, bad −4, very bad −8 (ACCEPTED round 13). Its name does not grow (round 13:
     * *"the name gets bigger by stacking it"*): the gene it came from + M and how many mutations hit it (*Arctic fat-burner M2*, *Skin M1*).
     */
    public static Gene variant(Gene base, String slot, int events, List<Step> steps) {
        Gene g = base != null ? base : plain(slot);
        if (steps.isEmpty()) return g;
        int[] m = g.mods().clone();
        int hp = g.hp(), dt = g.dt(), senses = g.senses(), load = g.load();
        double dr = g.dr(), rad = g.rad(), poison = g.poison(), iso = g.iso(), food = g.food(), water = g.water(), sleep = g.sleep(),
                heal = g.heal(), bleed = g.bleed(), pain = g.pain(), melee = g.melee(), fall = g.fall(), window = g.window();
        String sun = g.sun();
        Map<String, Double> skills = new LinkedHashMap<>(g.skills());
        List<String> said = new ArrayList<>();
        for (Step s : steps) {
            int k = s.sign() * s.size();
            int ai = Arrays.asList(Rules.SPECIAL).indexOf(s.dial());
            if (ai >= 0) m[ai] += k;
            else switch (s.dial()) {
                case "HP max" -> hp += 3 * k;
                case "DT" -> dt += k;
                case "DR" -> dr += 0.05 * k;
                case "rad res" -> rad += 0.10 * k;
                case "poison res" -> poison += 0.10 * k;
                case "isolation" -> iso += 0.10 * k;
                case "food" -> food *= s.factor(0.9, 1.15);
                case "water" -> water *= s.factor(0.9, 1.15);
                case "sleep" -> sleep *= s.factor(0.85, 1.2);
                case "healing" -> heal *= s.factor(1.25, 0.75);
                case "bleeding" -> bleed *= s.factor(0.75, 1.5);
                case "Pain" -> pain *= s.factor(0.85, 1.25);
                case "dark" -> {
                    if (s.sign() > 0) senses = Math.min(2, senses + 1);
                    else sun = s.size() > 1 || sun.equals("pain") ? "hp" : "pain";
                }
                case "skill" -> skills.merge(s.skill(), 10.0 * k, Double::sum);
                case "melee" -> melee += 0.10 * k;
                case "falls" -> fall *= s.factor(0.75, 1.5);
                case "dying" -> window *= s.factor(1.5, 0.75);
                default -> { }
            }
            skills.values().removeIf(v -> Math.abs(v) < 1e-9);
            load += 4 * k;
            said.add(s.text());
        }
        String name = (base == null ? slot : base.name()) + " M" + Math.max(1, events);
        String text = (g.text().isEmpty() ? g.real() : g.text()) + "; mutated: " + String.join(", ", said);
        return new Gene(name, slot, g.tier().isEmpty() ? "T1" : g.tier(), load, "a mutation of " + g.name(), text, g.rule(), false, m, food, water,
                sleep, pain, skills, poison, rad, iso, dt, hp, new LinkedHashMap<>(g.other()), g.family(), g.adult(), dr, melee, senses, window, heal,
                bleed, fall, sun, g.atDeath(), g.huge(), g.aptitude());
    }

    /**
     * The ghoul line's stage as a variant of the Necrosis slot's gene, ONLY ITS RADIATION SIDE since round 15 (the lean Necrosis; the rest
     * in the body's slots, {@link #ghoulParts}); before (03b §7b; 09b round 12: the ghoul is a mutation, its stages too): the
     * Ghoul gene + the stage's numbers by the way the body went. Each stage's numbers are its totals against the base ghoul (Marek's, round 7),
     * a later stage's replace an earlier one's; a stage WALKED gives all its numbers and its small gift, a stage JUMPED only its harm (round 9,
     * Marek 11-23: *"the ghoul missing the benefites of previous stages, only gaining the maluses"*). The smell's Sneak −10, the rad
     * resistances and the gifts mine, EXAMPLE. Its load the Ghoul gene's (the stages never changed the food).
     */
    /**
     * The Necrosis gene's rad res by the way the body went (round 15, the lean Necrosis: leathered 75 %, husk 90 %, glowing back to the ghoul's
     * own 40 %): {the added points, the stage number (G2-G4) whose value lasts, 0 when none does (glowing undid it)}.
     */
    static int[] necrosisRad(List<String> path) {
        if (path == null || path.isEmpty()) return new int[]{0, 0};
        int rr = 0, at = 0;
        int last = Arrays.asList(Rules.GHOUL_STAGES).indexOf(path.get(path.size() - 1));
        for (int k = 0; k <= last; k++) {
            boolean walked = path.contains(Rules.GHOUL_STAGES[k]);
            switch (Rules.GHOUL_STAGES[k]) {
                case "leathered" -> { if (walked) { rr = 35; at = 2; } }
                case "husk" -> { if (walked) { rr = 50; at = 3; } }
                case "glowing" -> { rr = 0; at = 0; }
                default -> { }
            }
        }
        return new int[]{rr, rr != 0 ? at : 0};
    }

    public static Gene ghoulVariant(Gene ghoul, List<String> path) {
        if (ghoul == null || path == null || path.isEmpty()) return ghoul;
        double rr = necrosisRad(path)[0];
        int last = Arrays.asList(Rules.GHOUL_STAGES).indexOf(path.get(path.size() - 1));
        List<String> jumped = new ArrayList<>();
        for (int k = 0; k <= last; k++) if (!path.contains(Rules.GHOUL_STAGES[k])) jumped.add(Rules.GHOUL_STAGES[k]);
        Map<String, Double> other = new LinkedHashMap<>(ghoul.other());
        if (rr != 0) other.merge("rad_res", rr, Double::sum);
        String name = ghoul.name() + " G" + ghoulStageNo(path);
        String text = ghoul.text() + "; the ghoul line: " + String.join(" → ", path)
                + (jumped.isEmpty() ? "" : " (jumped " + String.join(", ", jumped) + ": only its harm)") + (rr != 0 ? ", rad res +" + Math.round(rr) + " %" : "")
                + " (03b §7b)";
        return remake(ghoul, name, "the ghoul line's " + path.get(path.size() - 1) + " (fiction)", text, ghoul.load(), ghoul.mods().clone(), other);
    }
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
        // the glowing one's bonus in place of radiation sickness (03b §7b, `09b` round 9, Marek 11-23: "it no longer has illness from
        // radiation, instead gaining a bonus effect") [the steps and numbers mine, EXAMPLE]
        EFFECTS.putIfAbsent("Charged (glowing)", new EffectRow("Charged (glowing)", "a glowing ghoul at 300-599 rads: S +1",
                new int[]{1, 0, 0, 0, 0, 0, 0}, Map.of()));
        EFFECTS.putIfAbsent("Bright (glowing)", new EffectRow("Bright (glowing)", "a glowing ghoul at 600-999 rads: S +1, E +1",
                new int[]{1, 0, 1, 0, 0, 0, 0}, Map.of()));
        EFFECTS.putIfAbsent("Blazing (glowing)", new EffectRow("Blazing (glowing)", "a glowing ghoul full, at 1 000 rads: S +2, E +1, AP +1",
                new int[]{2, 0, 1, 0, 0, 0, 0}, Map.of("ap", 1.0)));
        for (JsonElement e : rows("traits")) {
            JsonObject o = e.getAsJsonObject();
            String name = o.get("name").getAsString();
            TRAITS.put(name, new Trait(name, s(o, "from", ""), s(o, "group", ""), s(o, "bonus", ""), s(o, "drawback", ""), s(o, "status", "in"),
                    attrs(o, "mods"), nums(o, "other")));
        }
        for (JsonElement e : rows("perks")) {
            JsonObject o = e.getAsJsonObject();
            List<Alt> alts = altsOf(o.getAsJsonArray("alts"));
            List<List<Alt>> also = new ArrayList<>();   // 05d round 7: a gate of several conditions, all to be met (Chi Master)
            if (o.has("also")) for (JsonElement g : o.getAsJsonArray("also")) also.add(altsOf(g.getAsJsonArray()));
            String name = o.get("name").getAsString();
            PERKS.put(name, new Perk(name, s(o, "kind", ""), s(o, "gate", ""), alts, s(o, "effect", ""),
                    o.has("parked") && o.get("parked").getAsBoolean(), o.has("bound") && o.get("bound").getAsBoolean(), attrs(o, "mods"), nums(o, "other"),
                    List.copyOf(also)));
        }
        for (JsonElement e : rows("foes")) {
            JsonObject o = e.getAsJsonObject();
            FOES.add(new Foe(o.get("name").getAsString(), s(o, "kind", ""), s(o, "note", "")));
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
            if (e.getValue() != 0) sb.append(sb.isEmpty() ? "" : ", ").append(e.getValue() > 0 ? "+" : "").append(Txt.f(e.getValue())).append(" ")
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
