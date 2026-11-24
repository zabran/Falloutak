package cz.lollywood.falloutak;

import java.util.*;

/**
 * One character, saved as one JSON file ({@link Store}). Part 3 (`_notes/Fallout/03b-SPECIES-RULES.md`, RULED 11-16): the template's
 * numbers reach every derived value, and everything else that changes a character is an {@link Effect} record (§9).
 */
public final class Character {
    /**
     * Something that changes a character (03b §9): an implant, a prosthesis, a mutation, a chem's stage, a hangover. {@code source}:
     * implant, prosthesis, mutation, chem, perk, trait, item (or "" for the old examples). {@code slot}: one slot holds one effect
     * (RULED: *"everything stacks but one slot"*), "" = none. {@code permanent}: level-up reads it (templates, implants, prostheses,
     * mutations, perks, traits, therapies: yes; a chem: never) and it has no clock. {@code other}: the rest of its numbers (dr, dt,
     * poison_res, rad_res, isolation, hp_max, ap, sequence, aimed, pain_felt, pain_gain, sleep_stage_off, rads_per_hour,
     * efficiency, senses, "skill:Guns", ...). {@code next}: the stage that follows (a chem's chain). {@code condition}: implants and
     * prostheses, 0-100 %, worn as a weapon (New Vegas: full at 75 % and over, half at the last points, broken at 0).
     */
    public record Effect(String name, String source, String slot, boolean permanent, int[] mods, Map<String, Double> other,
                         double hoursLeft, String next, double condition) {
        /** The old four-field form (the examples, saves before 0.8.0). */
        public Effect(String name, boolean permanent, int[] mods, double hoursLeft) { this(name, "", "", permanent, mods, Map.of(), hoursLeft, null, 100); }

        @Override public String source() { return source == null ? "" : source; }
        @Override public String slot() { return slot == null ? "" : slot; }
        @Override public Map<String, Double> other() { return other == null ? Map.of() : other; }
        @Override public int[] mods() { return mods == null ? new int[7] : mods; }

        public Effect withHours(double h) { return new Effect(name, source, slot, permanent, mods, other, h, next, condition); }
        public Effect withCondition(double c) { return new Effect(name, source, slot, permanent, mods, other, hoursLeft, next, Math.max(0, Math.min(100, c))); }

        /** Worn: an implant or a prosthesis (it has a condition). */
        public boolean worn() { return source().equals("implant") || source().equals("prosthesis"); }

        /** The share of its effect it gives now: by condition for the worn ones (Combat.conditionDamage), broken at 0. */
        public double strength() {
            if (!worn()) return 1;
            if (condition <= 0) return 0;
            return Combat.conditionDamage(condition / 100.0);
        }

        public int mod(int i) { return Rules.scaleInt(mods()[i], strength()); }

        public double other(String key) {
            double v = other().getOrDefault(key, 0.0);
            return INT_KEYS.contains(key) || key.startsWith("skill:") ? Rules.scaleInt(v, strength()) : v * strength();
        }
    }

    /** Values that stay whole when an implant's tier or condition scales them. */
    static final Set<String> INT_KEYS = Set.of("dt", "hp_max", "ap", "sequence", "aimed", "senses", "skill_points_level", "defib_shocks", "morale");

    /** A trait or perk as shown on the sheet (texts from `02c-TRAITS-PERKS.md`). */
    public record Feat(String name, String gate, String text) {}

    public String name = "Specimen", player = "", template = "Human";
    public int templateHp = 15, templateSkillPoints = 5, templatePerkRate = 3;
    public final int[] base = {5, 6, 6, 5, 7, 7, 4};
    public final List<Effect> effects = new ArrayList<>();
    /** Part 4b: the diseases running in this body ({@link Diseases}), what keeps each off (pairs of until-hour and share, −1 = for
     *  life: the vaccines and the memory of the last bout), whether the start's vaccines were set, the HP the diseases drain by the hour. */
    public List<Diseases.Case> diseases = new ArrayList<>();
    public Map<String, double[]> immune = new LinkedHashMap<>();
    public boolean vaccinesSet = false;
    public double diseaseHp = 0;
    /** 04b §3b: every rad ever taken (RadAway clears the stage, never this); the last rads seen. The HUD's soap and neighbours (§5). */
    public double lifetimeRads = 0, radsSeen = 0;
    public transient boolean soap = false;
    /**
     * Washing with soap (Marek 11-24, verbatim: *""carrying soap cuts it to 70 %" - washing with soap should be a setting, having it in
     * your pocket does not save you more than "black stone"."*): his own switch (Actions); while it is on he uses a bar every
     * {@link Diseases#SOAP_DAYS} days from his pack, and only while a bar lasts does it cut the catching from people (04b §5).
     */
    public boolean washSoap = false;
    public double soapDays = 0;
    /** In the base and fed or watered by its daily lot (the GM's time passing sets them): his own bar does not fall then. */
    public transient boolean baseFood = false, baseWater = false;
    public transient List<Character> nearby = new ArrayList<>();
    /** 07 §19: the parts of the carried food on their way to the next tier down, by item ({@link Spoil#carried}). */
    public Map<String, Double> spoilCarry = new LinkedHashMap<>();
    public final List<Feat> traits = new ArrayList<>();
    /** Hands-On Learner (round 6): the session its skills were counted in, and those skills (up to {@link #HANDS_ON_MAX}). */
    public String handsOnSession = "";
    public final List<String> handsOnSkills = new ArrayList<>();
    public final List<Feat> perks = new ArrayList<>();
    public final Set<String> tags = new LinkedHashSet<>();
    public final double[] points = new double[Rules.SKILLS.size()];
    public int level = 1, unspentSkillPoints = 0;
    public long xp = 0;
    public int hp;
    public final EnumMap<Rules.Stat, Double> survival = new EnumMap<>(Rules.Stat.class);
    public boolean sleeping = false;
    /** Downtime work in the base (06b §5.4.7: 1 XP an hour, RULED); the hours worked wait here until the GM's clock awards them. */
    public boolean working = false;
    public int workedHours = 0;
    /** While a dead PC's player creates his new character from his group (Marek 11-20): the group and the member; never saved. */
    public transient String fromGroup, fromMember;
    /** Bleeding = Blood lost a round (absolute, Marek 11-14); broken legs 0-2 (the stance table's 400 % / 800 %). */
    public double locationRads = 0, bleeding = 0;
    /**
     * Days since he last washed (round 9): in the base the base's average ({@link Production#unwashed}), outside the base his own
     * count, which a wash ends ({@code outside}: the player's own switch, Actions).
     */
    public double unwashedDays = 0;
    public boolean outside = false;
    public int brokenLegs = 0;
    /** Part 3: the group (part 6), the premium templates bought for this PC himself (03b §2b). */
    @com.google.gson.annotations.SerializedName(value = "group", alternate = {"faction"}) public String group = "";
    /** The player hears Fallout 2's sounds (his own private pack, `/sounds fallout2`); his body sounds are a woman's (`/sounds female`). */
    public boolean fallout2Sounds, female;
    /** /pack on|off: the Pip-Boy pictures in the sheet; null = whether the server's pack loaded in his client. */
    public Boolean pictures;
    /** /sounds narrator on|off: the narrator's comments on crits and fumbles (Narrator); null = on. */
    public Boolean narrator;
    public final List<String> unlocked = new ArrayList<>();
    /** The game hours this character has lived through (the demo clock, later the world's): the chems' 7-day dose count reads it. */
    public double clock = 0;
    /** Each chem's doses by {@link #clock}, and the chems he is addicted to (03b §8). */
    public final Map<String, List<Double>> doses = new LinkedHashMap<>();
    public final Set<String> addictions = new LinkedHashSet<>();
    /**
     * Clinical death (03b §5): when the arrest began and when CPR started, in GAME hours by {@link #now()} (Marek 11-16: *"no part
     * of this game should be real time - game time should be strictly separated, so only DM changes it"*; -1 = none); {@code dead} =
     * final death. Rest: consecutive resting hours (every 8 without bleeding heal the healing rate, part 4).
     */
    public double arrestAt = -1, cprFrom = -1, lastReviveTry = -1e9;
    /** The minutes into the current game hour (the GM passes minutes; a fight's round is 6 s). */
    public double minuteAcc = 0;
    public boolean dead = false;
    public double restHours = 0, mendHours = 0, lastMutation = -1e9;
    /** The crisis was rolled for this stay at 1 000 rads or more (03b §7b); cleared when the rads fall below 1 000. */
    public boolean crisisRolled = false;
    /** The ghoul line this body went through (03b §7b): "leathered", "husk", "glowing" in order; a jump skips the stages between. */
    public List<String> ghoulPath = new ArrayList<>();
    /** The glowing one (03b §7b, round 9): the rads it shed in this 8-hour block (+1 healing a 4), and the hours of the block. */
    public double glowShed = 0;
    public int glowHours = 0;
    /** What the glowing ones near shed an hour onto this body (read from the world each second: players and figurants within 5 blocks). */
    public transient double nearRads = 0;
    /** The anomaly the body is in (09c, round 9: read from the world each second): its kind, its zone, near its heart; what the world
     *  should show for its last hit (a throw, a lightning); out under the Black Rain. */
    public transient String anomaly = "", anomalyZone = "", anomalyFx = "";
    public transient boolean anomalyCentre, rainExposed;
    /** Out in bright sun now (set each second by the HUD: the day by the game clock, nothing over the head, no storm). */
    public transient boolean inSun;
    /** Game hours in bright sun in a row (the sun's harm, 03b §2d: a template's or a gene's {@code sun}). */
    public double sunHours;
    /** Hours toward the next loss of a negative healing rate (Fatal Radiation Poisoning, 11-19). */
    public double sickHours = 0;

    // ------------------------------------------------------------------ 03b §3c: air, gases, ears, the heart (RULED 11-18, rounds 19-21)
    /** Why the heart stopped while HP held (03b §3c): "" = HP below −Endurance (§5), else the cause ("Blood 0", "the cold", ...). */
    public String arrestCause = "";
    /** The place's air: false = none (buried, sealed; under water is read from the world each second, {@link #submerged}). */
    public boolean air = true;
    /** The gas here (a {@link Rules#GASES} name, "" = none) and its strength 1-3 (× the row), set by the GM. */
    public String gas = "";
    public int gasStrength = 1;
    /** A held breath (a choice: it spends Oxygen and keeps the gas out); what is worn (Gas mask, Air bottle, Earplugs, Electronic muffs). */
    public boolean holdingBreath = false;
    public final Set<String> wearing = new LinkedHashSet<>();
    /** The gas mask's filters: "gas" (everything but bad air and CO), "co" (CO); the air bottle's game minutes left (an hour a bottle). */
    public final Set<String> maskFilters = new LinkedHashSet<>();
    public double bottleMinutes = 0;
    /** Rounds in bad air in a row (an hour = 600: P −1, I −1); the game hour Oxygen reached 0 (-1 = above 0). */
    public int badAirRounds = 0;
    public double oxygenZeroAt = -1;
    /** Riot gas acts until this game hour at this strength; CO: Poison cures at half speed until this hour; hours in mercury vapour. */
    public double riotUntil = -1, coUntil = -1, mercuryHours = 0;
    public int riotStrength = 0;
    /** The ears: ringing until this game hour; each eardrum burst until its game hour (-1 = whole); the lasting loss (P −1). */
    public double ringingUntil = -1;
    public final double[] eardrumHealsAt = {-1, -1};
    public boolean lastingEarLoss = false;
    /** Set each second by the HUD for an online player: his head is under water, so there is no air. */
    public transient boolean submerged = false;
    /** The gas zone his eyes are in ({@link Zones}, read each second and before time passes): it wins over the GM's {@link #gas}. */
    public transient String zoneGas = "";
    public transient int zoneStrength = 1;
    /** An effect that lasts until the GM removes it (a treatment), shown as "until treated". */
    public static final double UNTIL_TREATED = 1e9;

    /** A trait picked, or a gene of the template whose effect the same rule carries (03c §8; internal, never shown). */
    public boolean has(String trait) {
        for (Feat t : traits) if (t.name().equals(trait)) return true;
        for (Data.Gene g : tpl().genes()) if (g.rule().equals(trait)) return true;
        return false;
    }

    /** A player's own template's genes (03c §7; {@link Data#CUSTOM}); the template is built from them, cached. */
    public final List<String> genes = new ArrayList<>();
    private transient Data.Template customTpl;

    public void setGenes(List<String> names) { genes.clear(); genes.addAll(names); customTpl = null; }

    /**
     * The genes a body that TURNED GHOUL keeps from the template it was born with (`09b` round 11, Marek 11-23, verbatim: *"The turned
     * ghoul's genes - yes, that is the issue. The genes should stay, just as implant."*): laid over the Ghoul template in their slots, as a
     * therapy's gene is (`Data.withTherapies`), so their numbers ADD to the ghoul's. Empty for a human, who has none.
     */
    public final List<String> keptGenes = new ArrayList<>();
    private transient Data.Template keptTpl;
    private transient int keptKey = -1;

    /**
     * A mutation that CHANGED a gene (03b §7a; 09b round 12, Marek 11-23, verbatim: *"the selected random mutation is applied to selected
     * random gene slot creating a new gene"*; the catalogue, rounds 13-14): its slot and its steps on the dials. In order; a slot may change
     * again.
     */
    public static final class Mutated {
        public String slot = "";
        public List<Data.Step> steps = new ArrayList<>();

        public Mutated() {}

        Mutated(String slot, List<Data.Step> steps) { this.slot = slot; this.steps = new ArrayList<>(steps); }

        public String text() {
            List<String> l = new ArrayList<>();
            for (Data.Step s : steps) l.add(s.text());
            return String.join(", ", l);
        }
    }

    /** The mutations that changed this body's genes (03b §7a), in order. */
    public final List<Mutated> mutated = new ArrayList<>();
    /**
     * The body turned GHOUL (03b §7; 09b round 12, Marek 11-23, verbatim: *"It is supposed to be a mutation."*): the Ghoul gene in the
     * Necrosis slot ({@link Data#NECROSIS}), the template and its genes kept. A ghoul born (the Ghoul template) has the gene already.
     */
    public boolean necrosis;
    private transient Data.Template bodyTpl;
    private transient String bodyKey;

    /**
     * Out of the game (round 11: *"for the purpose of playing, it should be possible to save your current inventory + character (esp. in
     * MC, where you can do other things than just play)"*): `/game off` until `/game on`; the HUD and the world leave him alone.
     */
    public boolean away;

    /**
     * The time of day (Marek 11-16: *"game clock has no time of day yet - add it please"*): the hour of day at clock 0, so the time is
     * {@code dayStart + now()}; the GM sets it (Time of day), passing time moves it. Day 1 begins at clock 0 [08:00 mine].
     */
    public double dayStart = 8;

    public double timeAbs() { return dayStart + now(); }
    public double hourOfDay() { return ((timeAbs() % 24) + 24) % 24; }
    public int day() { return (int) Math.floor(timeAbs() / 24) + 1; }
    /** Night = 18:00-06:00 (Night Person's hours, 02c). */
    public boolean night() { double h = hourOfDay(); return h >= 18 || h < 6; }
    public String timeText() {
        int m = (int) Math.floor(hourOfDay() * 60 + 1e-6);
        return "Day " + day() + " · " + String.format("%02d:%02d", m / 60, m % 60);
    }
    /** Sets the time of day, keeping the day (a time earlier than now turns the clock back within the day). */
    public void setTimeOfDay(double hour) { dayStart += hour - hourOfDay(); }

    public boolean hasPerk(String perk) { for (Feat t : perks) if (t.name().equals(perk) || t.name().startsWith(perk + ": ")) return true; return false; }

    /** A perk's own name (a bound perk is kept as "Nemesis: raiders"). */
    static String perkBase(String shown) { int i = shown.indexOf(": "); return i < 0 ? shown : shown.substring(0, i); }

    /** The level perks owned (02c §2: one each perk level), not the point shop's. */
    public int levelPerks() {
        int n = 0;
        for (Feat f : perks) if (Data.PERKS.containsKey(perkBase(f.name()))) n++;
        return n;
    }

    /** Perks to pick: one a perk level of the template's rate, less those taken. */
    public int perksDue() { return templatePerkRate <= 0 ? 0 : Math.max(0, level / templatePerkRate - levelPerks()); }

    /** The perks this character may pick now: the gate met (permanent values), not taken, not parked (parts 7-8). */
    public List<Data.Perk> perksOpen() {
        double[] v = skills(true);
        List<Data.Perk> l = new ArrayList<>();
        for (Data.Perk p : Data.PERKS.values()) if (!p.parked() && !hasPerk(p.name()) && Rules.gateMet(v, p)) l.add(p);
        return l;
    }

    /**
     * Takes a perk (02c §2): on the sheet, its numbers as a permanent effect; a bound one carries the thing named ("Nemesis:
     * raiders"); Tag! tags the skill named; Here and Now gives the XP to the next level. Returns what happened, "!" when refused.
     */
    public String takePerk(Data.Perk p, String bound) {
        if (perksDue() <= 0) return "! no perk to pick now (one every " + templatePerkRate + " levels)";
        if (p.parked()) return "! " + p.name() + " waits for part 8 (research)";
        if (hasPerk(p.name())) return "! " + p.name() + " is taken already";
        if (!Rules.gateMet(skills(true), p)) return "! the gate is not met: " + p.gate();
        String b = bound == null ? "" : bound.trim();
        if (p.bound() && b.isEmpty()) return "! name the thing " + p.name() + " is bound to";
        if (p.name().equals("Tag!")) {
            if (Rules.SKILLS.stream().noneMatch(s -> s.name().equals(b))) return "! Tag! needs one of the 15 skills";
            if (tags.contains(b)) return "! " + b + " is tagged already";
            tags.add(b);
        }
        perks.add(new Feat(b.isEmpty() ? p.name() : p.name() + ": " + b, p.gate(), p.effect()));
        if (p.hasNumbers())
            effects.add(new Effect(p.name(), "perk", "perk:" + p.name(), true, p.mods().clone(), new LinkedHashMap<>(p.other()), 0, null, 100));
        String more = "";
        if (p.name().equals("Here and Now")) more = " " + gainXp(Math.max(1, Rules.xpForLevel(level + 1) - xp));
        return (p.name() + (b.isEmpty() ? "" : ": " + b) + " taken." + more).trim();
    }

    /** Takes the template's numbers (`data/species.json`, part 3). */
    public void applyTemplate(String name) {
        customTpl = null;
        Data.Template t = Data.CUSTOM.equals(name) ? Data.custom(genes) : Data.TEMPLATES.getOrDefault(name, Data.TEMPLATES.get("Human"));
        template = t.name();
        templateHp = t.hp();
        templateSkillPoints = t.skillPoints();
        templatePerkRate = t.perkRate();
        for (int i = 0; i < 7; i++) if (t.fixedAttr(i)) base[i] = 0;   // a fixed value takes no points (03b §1)
    }

    /** The template as it acts now: as born, with the gene therapies' genes in place of its own while they last (03d §1, §13). */
    public Data.Template tpl() {
        Data.Template base = baseTpl();
        if (therapies.isEmpty()) return base;
        StringBuilder k = new StringBuilder(base.name()).append('#').append(System.identityHashCode(base));
        List<Data.Cover> cv = new ArrayList<>();
        for (Therapy t : therapies)
            if (t.active()) { cv.add(new Data.Cover(t.g(), t.share(), t.state)); k.append('|').append(t.gene).append(t.share()).append(t.state); }
        String key = k.toString();
        if (!key.equals(therapyKey) || therapyTpl == null) { therapyKey = key; therapyTpl = Data.withTherapies(base, cv); }
        return therapyTpl;
    }

    // ------------------------------------------------------------------ gene therapies (03d, RULED 11-17)
    /**
     * A gene therapy (`03d-THERAPIES.md`): one gene of the pool in its slot for a while; while it lasts its numbers replace those of
     * the template's gene in that slot (§1). {@code hours}: its time (§2: the tissue's years × the tier's, × 8 760), 0 = for good (a
     * partial template made in puberty, §8-§9); {@code elapsed} runs with game time unless the gene pump holds it (§10); the course
     * takes {@code courseLeft} hours before it acts. {@code state}: on · twisted (only its drawback, radiation, §5) · blend (only its
     * upside, the conflict roll's 6, §4).
     */
    public static final class Therapy {
        public String gene = "", state = "on", set = "";
        public double hours, elapsed, courseLeft;
        public boolean pumped;

        public Therapy() {}

        Therapy(String gene, double hours, double courseLeft, String state, String set) {
            this.gene = gene; this.hours = hours; this.courseLeft = courseLeft; this.state = state; this.set = set == null ? "" : set;
        }

        public Data.Gene g() { return Data.gene(gene); }
        public boolean active() { return g() != null && courseLeft <= 0 && (hours <= 0 || elapsed < hours); }
        /** Full for its time, half its numbers for its last quarter (§2). */
        public double share() { return hours > 0 && elapsed >= 0.75 * hours ? 0.5 : 1; }
        public double hoursLeft() { return hours <= 0 ? -1 : Math.max(0, hours - elapsed); }
    }

    /** The gene therapies in force or in their course (03d). */
    public final List<Therapy> therapies = new ArrayList<>();
    /** When the gene pump was last refilled, on the game clock (03d §10: a refill a year); -1 = never. */
    public double pumpRefilled = -1;
    private transient Data.Template therapyTpl;
    private transient String therapyKey;
    /** What happened in the passing hours for the GM to hear (a therapy took, faded, wore off, radiation switched it off). */
    public final transient List<String> news = new ArrayList<>();
    /** Sounds the body makes (a shock, the heart stopping): the model has no player, the HUD plays them ({@link Sfx}). */
    public final transient List<String> cues = new ArrayList<>();

    /** The news since it was last read, or null. */
    public String takeNews() {
        if (news.isEmpty()) return null;
        String s = String.join("; ", news);
        news.clear();
        return s;
    }

    /**
     * The body as it is now, without the therapies: the template as born with the genes its mutations CHANGED (03b §7a) and the ghoul's
     * Necrosis gene (09b round 12) in their slots.
     */
    public Data.Template baseTpl() {
        Data.Template born = bornTpl();
        if (mutated.isEmpty() && !isGhoul()) return born;
        List<Data.Gene> changed = bodyGenes(born);
        if (changed.isEmpty()) return born;
        StringBuilder k = new StringBuilder(born.name()).append('#').append(System.identityHashCode(born));
        for (Data.Gene g : changed) k.append('|').append(g.name());
        String key = k.toString();
        if (!key.equals(bodyKey) || bodyTpl == null) {
            List<Data.Cover> cv = new ArrayList<>();
            for (Data.Gene g : changed) cv.add(new Data.Cover(g, 1, "on"));
            bodyTpl = Data.withTherapies(born, cv);
            bodyKey = key;
        }
        return bodyTpl;
    }

    /** Is the body a ghoul: turned (the Necrosis gene), born one (the Ghoul template's gene), or a save before 0.68.0's turned one. */
    public boolean isGhoul() {
        if (necrosis) return true;
        Data.Template born = bornTpl();
        if (born.name().equals("Ghoul")) return true;
        for (Data.Gene g : born.genes()) if (g.slot().equals(Data.NECROSIS)) return true;
        return false;
    }

    /** The born template's gene in a plain slot, or null. */
    private static Data.Gene geneIn(Data.Template t, String slot) {
        for (Data.Gene g : t.genes()) if (g.slot().equals(slot)) return g;
        return null;
    }

    /** The genes this body CHANGED, one a slot: each mutated slot's variant, and the Necrosis slot's (the Ghoul gene, or its stage). */
    List<Data.Gene> bodyGenes(Data.Template born) {
        List<Data.Gene> l = new ArrayList<>();
        for (Data.Recipe r : recipes(born)) { Data.Gene g = r.build(); if (g != null) l.add(g); }
        return l;
    }

    /** How each changed gene of this body is made (what a sample takes into the base's pool, 03b §7a). */
    public List<Data.Recipe> recipes() { return recipes(bornTpl()); }

    private List<Data.Recipe> recipes(Data.Template born) {
        Map<String, List<Mutated>> bySlot = new LinkedHashMap<>();
        for (Mutated m : mutated) if (m.steps != null && !m.steps.isEmpty()) bySlot.computeIfAbsent(m.slot, x -> new ArrayList<>()).add(m);
        boolean ghoul = isGhoul();
        List<String> path = ghoulPath == null ? List.of() : ghoulPath;
        boolean turned = ghoul && geneIn(born, Data.NECROSIS) == null;   // turned: the ghoul's own numbers are parts; born: its genes
        Set<String> slots = new LinkedHashSet<>(bySlot.keySet());
        if (ghoul) slots.addAll(Data.ghoulParts(path, turned).keySet());
        List<Data.Recipe> l = new ArrayList<>();
        for (String slot : slots) {
            if (slot.equals(Data.NECROSIS)) continue;
            List<Mutated> ms = bySlot.getOrDefault(slot, List.of());
            boolean part = ghoul && Data.ghoulParts(path, turned).containsKey(slot);
            Data.Gene b = geneIn(born, slot);
            List<Data.Step> steps = new ArrayList<>();
            for (Mutated m : ms) steps.addAll(m.steps);
            l.add(new Data.Recipe(b == null ? "" : b.name(), slot, ms.size(), steps, path, part, turned));
        }
        List<Mutated> nec = bySlot.getOrDefault(Data.NECROSIS, List.of());
        Data.Gene own = geneIn(born, Data.NECROSIS);
        if (ghoul && (own == null || !path.isEmpty() || !nec.isEmpty()) || !ghoul && !nec.isEmpty()) {
            List<Data.Step> steps = new ArrayList<>();
            for (Mutated m : nec) steps.addAll(m.steps);
            l.add(new Data.Recipe(own != null ? own.name() : ghoul ? "Ghoul" : "", Data.NECROSIS, nec.size(), steps, path, ghoul, turned));
        }
        return l;
    }

    /** The template as born (or as made in puberty), without the mutations' changes and the therapies. */
    public Data.Template bornTpl() {
        if (Data.CUSTOM.equals(template)) return customTpl != null ? customTpl : (customTpl = Data.custom(genes));
        Data.Template t = Data.TEMPLATES.getOrDefault(template, Data.TEMPLATES.get("Human"));
        if (keptGenes.isEmpty() || !t.name().equals("Ghoul")) return t;
        if (keptTpl == null || keptKey != keptGenes.hashCode()) {   // the genes kept, over the Ghoul template (round 11)
            List<Data.Cover> cv = new ArrayList<>();
            for (String n : keptGenes) { Data.Gene g = Data.GENES.get(n); if (g != null) cv.add(new Data.Cover(g, 1, "on")); }
            keptTpl = Data.withTherapies(t, cv);
            keptKey = keptGenes.hashCode();
        }
        return keptTpl;
    }

    /** The template's HP bonus, with the therapies' (Bone lattice's HP +5 over the template's gene in the Frame slot). */
    public int hpBonus() { return templateHp + tpl().hp() - bornTpl().hp(); }

    /** The therapy in a gene's place ({@link Data.Gene#key()}), or null. */
    public Therapy therapyIn(String key) {
        for (Therapy t : therapies) if (t.g() != null && t.g().key().equals(key)) return t;
        return null;
    }

    /** The template's own gene in a place, or null. */
    public Data.Gene templateGene(String key) {
        for (Data.Gene g : baseTpl().genes()) if (g.key().equals(key)) return g;
        return null;
    }

    /** The tissue's years (03d §2): fast-renewing 1, slow organs 2, muscle and bone 4, nerves (and the aptitudes) 6. */
    static int tissueYears(String slot) {
        return switch (slot) {
            case "Blood", "Immunity", "Skin", "Look" -> 1;
            case "Muscle", "Frame", "Sleep" -> 4;
            case "Pain", "Eyes", "Ears and nose", "Mind", "Temperament", "Aptitude" -> 6;
            default -> 2;
        };
    }

    static final double[] TIER_YEARS = {0, 1, 1.5, 2, 2.5};
    /** The course's length by tier (03d §2): a week, two weeks, a month, two months; a fashion course a day (§7). */
    static final double[] COURSE_HOURS = {0, 168, 336, 720, 1440};

    public static double therapyHours(Data.Gene g) { return tissueYears(g.slot()) * TIER_YEARS[g.tierNo()] * 8760; }

    static boolean fashion(Data.Gene g) { return g.name().startsWith("Fashion:"); }

    /** The ease of a change (03d §4): +2 another version of the same gene (a family), +1 a fast-renewing tissue, −1 nerves. */
    public static int ease(Data.Gene now, Data.Gene before) {
        int e = 0;
        if (before != null && !now.family().isEmpty() && now.family().equals(before.family())) e += 2;
        int y = tissueYears(now.slot());
        if (y == 1) e += 1;
        if (y == 6) e -= 1;
        return e;
    }

    /** The necroat's body, made to take changes (03d §11): the conflict roll +1, radiation never twists, no implant infections. */
    public boolean madeForChange() {
        for (Data.Gene g : tpl().genes()) if (g.name().equals("Made for change")) return true;
        return false;
    }

    /** An Immunity therapy of T2 or more: a T I-II implant's infection roll halved (03d §10). */
    public boolean immunityTherapy() {
        for (Therapy t : therapies) if (t.active() && t.g().slot().equals("Immunity") && t.g().tierNo() >= 2) return true;
        return false;
    }

    /** The course's chance (03d §6): min(98 %, Biology × the place × the tier's (T1 1.2 · T2 1 · T3 0.8 · T4 0.6) × 1.1 a point of ease). */
    public static double therapyChance(double biology, double place, int tier, int ease) {
        double tf = switch (tier) { case 1 -> 1.2; case 2 -> 1.0; case 3 -> 0.8; default -> 0.6; };
        return Math.max(0, Math.min(98, biology * place * tf * Math.pow(1.1, ease)));
    }

    /** Why this gene cannot go into this body as a therapy, or null (03d §1: no organ ever, no shape gene in a grown body). */
    public String therapyBar(Data.Gene g, boolean puberty) {
        if (synthetic()) return "a machine takes no gene therapy";
        if (g.slot().equals("ORGAN")) return g.name() + " is an organ: never a therapy, only a graft (03e)";
        if (!g.adult() && !puberty) return g.name() + " shapes a growing body: only in puberty (03d §1, §8)";
        return null;
    }

    /** The course's sickness (03d §2): T1 two days of mild fever, T2 four days of fever, T3-T4 a week of fever with S −1, E −1. */
    void courseSickness(int tier, String what) {
        int[] m = new int[7];
        if (tier >= 3) { m[Rules.S] = -1; m[Rules.E] = -1; }
        add(new Effect("Fever: the " + what + " course", "therapy", "", false, m, new LinkedHashMap<>(), tier <= 1 ? 48 : tier == 2 ? 96 : 168, null, 100));
    }

    /**
     * Gives one gene as a therapy (03d §1-§6, RULED 11-17): the course's chance by the therapist's Biology, the place and the tier
     * (×1.1 a point of ease, §6), and its sickness; over a therapy in the same place the conflict roll (§4: d6 + Biology / 30 + the
     * ease, the necroat +1); over the template's own gene it is simply laid on. The same gene again = a booster (§2: the clock reset).
     * {@code roll} false = no rolls (the GM's own); {@code puberty} = for good, the shape genes allowed (§8). Returns the log.
     */
    public String giveTherapy(Data.Gene g, double biology, double place, boolean puberty, boolean roll, Random rnd) {
        String bar = therapyBar(g, puberty);
        if (bar != null) return "! " + bar;
        Therapy old = therapyIn(g.key());
        if (old != null && old.gene.equals(g.name()) && !puberty) {
            old.elapsed = 0;
            return "a booster of " + g.name() + ": its clock starts again (" + SheetView.hours(old.hours) + "), at half the course's price";
        }
        StringBuilder sb = new StringBuilder(g.name() + " (" + g.tier() + ", the " + g.slot() + " slot)");
        int ease = ease(g, old != null ? old.g() : templateGene(g.key()));
        if (roll) {
            double ch = therapyChance(biology, place, g.tierNo(), ease);
            int d = 1 + rnd.nextInt(100);
            sb.append(": the course ").append(Math.round(ch)).append(" % (Biology ").append(Math.round(biology)).append(" × the place ")
                    .append(SheetGui.f(place)).append(" × the tier, ease ").append(ease).append("), rolled ").append(d);
            if (!fashion(g)) courseSickness(g.tierNo(), g.name());
            if (d > ch) return sb.append(": FAILED, the course wasted (its fever all the same)").toString();
        } else if (!fashion(g)) courseSickness(g.tierNo(), g.name());
        sb.append(place(g, puberty ? 0 : therapyHours(g), fashion(g) ? 24 : COURSE_HOURS[g.tierNo()], "", biology, ease, roll, rnd));
        return sb.toString();
    }

    /**
     * Gives a shop set (03d §9, DRAFT 2 11-18): one course and one roll at its highest tier, no conflicts inside it; an adult gets it
     * for 15 years without its shape and organ genes, in puberty it is for good (a partial template) without the organs.
     */
    public String giveSet(Data.GeneSet s, double biology, double place, boolean puberty, boolean roll, Random rnd) {
        if (synthetic()) return "! a machine takes no gene therapy";
        List<Data.Gene> gs = new ArrayList<>();
        List<String> left = new ArrayList<>();
        for (String n : s.genes()) {
            Data.Gene g = Data.GENES.get(n);
            if (g == null) continue;
            if (therapyBar(g, puberty) != null) left.add(n + (g.slot().equals("ORGAN") ? " (an organ: a graft, 03e)" : " (puberty only)"));
            else gs.add(g);
        }
        if (gs.isEmpty()) return "! nothing of " + s.name() + " takes in this body";
        int top = 1;
        for (Data.Gene g : gs) top = Math.max(top, g.tierNo());
        StringBuilder sb = new StringBuilder("the " + s.name() + " set");
        if (roll) {
            double ch = therapyChance(biology, place, top, 0);
            int d = 1 + rnd.nextInt(100);
            sb.append(": the course ").append(Math.round(ch)).append(" % (Biology ").append(Math.round(biology)).append(" × the place ")
                    .append(SheetGui.f(place)).append(" × tier ").append(Rules.roman(top)).append("), rolled ").append(d);
            courseSickness(top, s.name());
            if (d > ch) return sb.append(": FAILED, the course wasted (its fever all the same)").toString();
        } else courseSickness(top, s.name());
        for (Data.Gene g : gs) {
            Therapy old = therapyIn(g.key());
            sb.append("; ").append(g.name()).append(place(g, puberty ? 0 : 15 * 8760, COURSE_HOURS[top], s.name(), biology,
                    ease(g, old != null ? old.g() : templateGene(g.key())), roll, rnd));
        }
        if (!left.isEmpty()) sb.append("; not given: ").append(String.join(", ", left));
        return sb.toString();
    }

    /** Puts a gene into its place: the conflict roll over a therapy already there (03d §4), else laid on. Returns its part of the log. */
    private String place(Data.Gene g, double hours, double course, String set, double biology, int ease, boolean roll, Random rnd) {
        Therapy old = therapyIn(g.key());
        String when = course > 0 ? " (it takes in " + SheetView.hours(course) + ", then " + (hours <= 0 ? "for good" : "for " + SheetView.hours(hours)) + ")" : "";
        if (old == null) {
            therapies.add(new Therapy(g.name(), hours, course, "on", set));
            Data.Gene own = templateGene(g.key());
            return ": laid on" + (own == null ? " an empty slot" : " over the template's " + own.name()) + when;
        }
        if (!roll) {
            therapies.remove(old);
            therapies.add(new Therapy(g.name(), hours, course, "on", set));
            return ": replaces " + old.gene + when;
        }
        int d6 = 1 + rnd.nextInt(6);
        double total = d6 + biology / 30 + ease + (madeForChange() ? 1 : 0);
        int r = (int) Math.floor(total + 1e-9);
        String head = ": over " + old.gene + ", d6 " + d6 + " + Biology / 30 " + SheetGui.f(Math.round(biology / 30 * 10) / 10.0) + " + ease " + ease
                + (madeForChange() ? " + 1 (made for change)" : "") + " = " + r;
        if (r <= 1) { therapies.remove(old); return head + ": a TWIST, both gone and a mutation instead (" + rollMutation(rnd) + ")"; }
        if (r == 2) { therapies.remove(old); return head + ": BOTH OFF, the slot empty until a new course (the old fades in days, the new never comes)"; }
        if (r == 3) return head + ": the OLD HOLDS, the new is wasted";
        therapies.remove(old);
        therapies.add(new Therapy(g.name(), hours, course, r >= 6 ? "blend" : "on", set));
        return head + (r >= 6 ? ": a BLEND, its upside without any drawback (\"better than they promised\")" : ": the new replaces the old") + when;
    }

    /** How many therapies a fitted gene pump holds (03d §10: T III one, T IV two [the two mine]); 0 without one or broken. */
    public int pumpSlots() {
        for (Effect e : implants()) if (e.name().startsWith("Gene Pump")) return e.condition() <= 0 ? 0 : e.name().endsWith("Tier IV") ? 2 : 1;
        return 0;
    }

    /** The pump fed: refilled within the year (03d §10). */
    public boolean pumpFed() { return pumpSlots() > 0 && pumpRefilled >= 0 && now() - pumpRefilled < 8760; }

    /** One game hour of the therapies: the course runs, then the clock (not while the fed pump holds it), the fading, the end. */
    void therapyHour() {
        int held = 0;
        boolean fed = pumpFed();
        for (Iterator<Therapy> it = therapies.iterator(); it.hasNext(); ) {
            Therapy t = it.next();
            if (t.g() == null) { it.remove(); continue; }
            if (t.courseLeft > 0) {
                t.courseLeft -= 1;
                if (t.courseLeft <= 0) news.add("the " + t.gene + " therapy has taken");
                continue;
            }
            if (t.hours <= 0) continue;
            if (t.pumped && fed && held < pumpSlots()) { held++; continue; }
            double before = t.elapsed;
            t.elapsed += 1;
            if (before < 0.75 * t.hours && t.elapsed >= 0.75 * t.hours)
                news.add("the " + t.gene + " therapy fades: half its numbers for its last quarter (a booster resets it)");
            if (t.elapsed >= t.hours) { it.remove(); news.add("the " + t.gene + " therapy has worn off"); }
        }
    }

    static int radStage(double rads) { return rads >= 600 ? 4 : rads >= 450 ? 3 : rads >= 300 ? 2 : rads >= 150 ? 1 : 0; }

    /**
     * Radiation crossing a stage upward (150, 300, 450, 600: 03d §5, AGREED 11-17): every active therapy rolls d100 against 10 % × the
     * stage, halved at Rad resistance 50 % and more, halved for the others by an active Radiation-slot therapy; stages 1-2 switch it
     * off, 3-4 twist it (only its drawback for the rest of its time; on 01-05 a mutation instead); the necroat's are only switched off.
     * The template's genes and a partial template made in puberty never.
     */
    public void radiationRolls(double before, double after, Random rnd) {
        int s0 = radStage(before), s1 = radStage(after);
        if (s1 <= s0 || therapies.isEmpty()) return;
        boolean resist = resistances()[1] >= 50, made = madeForChange();
        for (int st = s0 + 1; st <= s1; st++) {
            boolean shield = false;
            for (Therapy t : therapies) if (t.active() && t.g().slot().equals("Radiation")) shield = true;
            for (Iterator<Therapy> it = therapies.iterator(); it.hasNext(); ) {
                Therapy t = it.next();
                if (!t.active() || t.hours <= 0) continue;
                double ch = 10 * st * (resist ? 0.5 : 1) * (shield && !t.g().slot().equals("Radiation") ? 0.5 : 1);
                int d = 1 + rnd.nextInt(100);
                if (d > ch) continue;
                if (st <= 2 || made) { it.remove(); news.add("radiation (stage " + st + ", " + Math.round(ch) + " %, rolled " + d + ") switched the " + t.gene + " therapy off"); }
                else if (d <= 5) { it.remove(); news.add("radiation twisted the " + t.gene + " therapy into a mutation: " + rollMutation(rnd)); }
                else { t.state = "twisted"; news.add("radiation (stage " + st + ", rolled " + d + ") twisted the " + t.gene + " therapy: only its drawback for the rest of its time"); }
            }
        }
    }

    /** A roll on the mutation table (03b §7) with no rads spent: a therapy's twist (03d §4, §5). */
    String rollMutation(Random rnd) {
        int d = 1 + rnd.nextInt(100);
        int score = Rules.mutationScore(d, attr(Rules.L, true), 100);
        String cat = Rules.mutationCategory(score);
        return cat + ": " + applyMutation(cat, rnd);
    }

    /**
     * A mutation hits the body (03b §7a; 09b round 12, Marek 11-23, verbatim: *"I think there should be a system where the selected random
     * mutation is applied to selected random gene slot creating a new gene."*; the catalogue, rounds 13-14): the grade's steps on the dials
     * ({@link Data#rollMutation}) land in a random slot of the 21 ({@link Data#mutableSlots}): its gene (the template's, or the plain human's)
     * becomes a NEW gene; a body change (the tumour, a withered limb, a blind eye) is no gene. Returns what happened.
     */
    String applyMutation(String category, Random rnd) {
        Data.Rolled r = Data.rollMutation(category, rnd);
        if (r.bodyChange() != null) {
            Data.Mutation m = Data.mutation(r.bodyChange());
            add(new Effect("Mutation: " + r.bodyChange(), "mutation", "", true, m == null ? new int[7] : m.mods().clone(),
                    m == null ? new LinkedHashMap<>() : new LinkedHashMap<>(m.other()), 0, null, 100));
            return r.bodyChange() + (m != null && !m.notes().isEmpty() ? " (" + m.notes() + ")" : "") + ": a body change, no gene";
        }
        List<String> slots = Data.mutableSlots();
        return applySteps(slots.get(rnd.nextInt(slots.size())), r.steps());
    }

    /** The steps change the gene of a slot (03b §7a). Returns what happened. */
    String applySteps(String slot, List<Data.Step> steps) {
        Data.Gene before = geneIn(baseTpl(), slot);
        Mutated m = new Mutated(slot, steps);
        mutated.add(m);
        bodyTpl = null;
        Data.Gene now = geneIn(baseTpl(), slot);
        return m.text() + " in the " + slot + " slot: " + (before == null ? "the plain " + slot : before.name()) + " → "
                + (now == null ? "?" : now.name() + " (load " + (now.load() >= 0 ? "+" : "") + now.load() + ")");
    }

    /** The GM undoes a mutation's change to a gene (03b §7a): the slot loses it. Returns what was undone. */
    public String undoMutation(int i) {
        if (i < 0 || i >= mutated.size()) return "no such mutation";
        Mutated m = mutated.remove(i);
        bodyTpl = null;
        return "the mutation (" + m.text() + ") undone in the " + m.slot + " slot";
    }

    /** How many mutations the body took: the genes changed and the body changes (and a save's old ones, before 0.68.0). */
    public int mutationCount() {
        return mutated.size() + (int) effects.stream().filter(e -> "mutation".equals(e.source())).count();
    }


    // ------------------------------------------------------------------ attributes
    /**
     * An attribute: its points + the template's modifier (03b §1: the modifier moves both ends, min 1 + mod, max 10 + mod), or the
     * template's fixed value; then the effects. {@code permanentOnly} leaves out the temporary ones (level-up reads those). The
     * current survival stages add their effects to the "now" value only; no clamp, so Paralyzed by Pain's Agility −99 leaves no AP.
     */
    public int attr(int i, boolean permanentOnly) {
        return permanentOnly ? attr(i, true, false) : attr(i, false, ignoresPain());
    }

    private int attr(int i, boolean permanentOnly, boolean withoutPain) {
        Data.Template t = tpl();
        int v = t.fixedAttr(i) ? t.fixed()[i] : base[i] + t.mods()[i];
        for (Effect e : effects) if (e.permanent() || !permanentOnly) v += e.mod(i);
        if (!permanentOnly) v += armourMod(i);   // a whole power armour's Strength (05c): worn, never read by level-up
        // Night Person (02c; a trait or its gene): I and P +1 at night, -1 by day; a temporary value, so level-up never reads it
        if (!permanentOnly && (i == Rules.I || i == Rules.P) && has("Night Person")) v += night() ? 1 : -1;
        if (!permanentOnly) {
            List<String> sts = stages();
            // the limit gates (part 4 §3.5, RULED 11-15): Minor and Advanced stages never take an attribute below 1; the worse ones can
            int gated = 0;
            for (String st : sts) {
                if (withoutPain && isPainStage(st)) continue;
                Data.EffectRow r = Data.EFFECTS.get(st);
                if (r != null && Rules.gated(st)) gated += r.mods()[i];
            }
            v = gated < 0 ? Math.max(Math.min(v, 1), v + gated) : v + gated;
            for (String st : sts) {
                if (withoutPain && isPainStage(st)) continue;
                Data.EffectRow r = Data.EFFECTS.get(st);
                if (r != null && !Rules.gated(st)) v += r.mods()[i];
            }
            if (i == Rules.P) v += gasPenalty()[0] - hearingLoss();   // 03b §3c: the gas, the ears
            if ((i == Rules.P || i == Rules.I) && badAirRounds >= 600) v -= 1;   // an hour of bad air (03b §3c)
        }
        return v;
    }

    /** The lowest and highest an attribute may be at creation (03b §1): 1 + mod to 10 + mod, never below 1; a fixed value both. */
    public int[] attrRange(int i) {
        Data.Template t = tpl();
        if (t.fixedAttr(i)) return new int[]{t.fixed()[i], t.fixed()[i]};
        return new int[]{Math.max(1, 1 + t.mods()[i]), 10 + t.mods()[i]};
    }

    /** The sum of the points (the fixed attributes take none). */
    public int pointsSpent() {
        int s = 0;
        for (int i = 0; i < 7; i++) if (!tpl().fixedAttr(i)) s += base[i];
        return s;
    }

    /** Null if the points are legal for the template (03b §1), else why not. */
    public String checkPoints() {
        Data.Template t = tpl();
        for (int i = 0; i < 7; i++) {
            if (t.fixedAttr(i)) continue;
            if (base[i] < 1 || base[i] > 10) return Rules.SPECIAL[i] + " takes 1 to 10 points (has " + base[i] + ")";
            if (base[i] + t.mods()[i] < 1)
                return Rules.SPECIAL[i] + " would be " + (base[i] + t.mods()[i]) + ": never below 1, so at least " + (1 - t.mods()[i]) + " points";
        }
        if (pointsSpent() != t.attributePoints()) return "the points add up to " + pointsSpent() + "; a " + t.name() + " has " + t.attributePoints();
        return null;
    }

    /** The sum of an effect value over the effects ({@code permanentOnly}: level-up's view). */
    public double other(String key, boolean permanentOnly) {
        double s = tpl().other().getOrDefault(key, 0.0);   // the template's genes (03c: Thick-Skinned's −1 AP, Twitchy's +10 Initiative)
        for (Effect e : effects) if (e.permanent() || !permanentOnly) s += e.other(key);
        return s;
    }

    public double other(String key) { return other(key, false); }

    static boolean isPainStage(String stage) { return stage.endsWith(" Pain") || stage.equals("Paralyzed by Pain"); }

    /** The Pain a painkiller leaves felt (04c §4b: the stage is read from Pain − its strength; the bar itself stays). */
    public double painFelt() {
        Double pain = survival.get(Rules.Stat.PAIN);
        return pain == null ? 0 : Math.max(0, pain + other("pain_felt"));
    }

    /** The Pain a hit adds is multiplied by this: the Pain Editor ×½, a mutation's Twitch Muscles ×1.5. */
    public double painGain() { return Math.max(0, tpl().painGain() * (1 + other("pain_gain"))); }

    /** The Survival the Pain check reads: as it is now, leaving out the Pain stage's own effects (else the pain would fail itself). */
    public double painCheckSurvival() {
        int[] a = new int[7];
        for (int i = 0; i < 7; i++) a[i] = attr(i, false, true);
        int s = Rules.skillIndex("Survival");
        return Rules.SKILLS.get(s).base().applyAsDouble(a) + points[s] + skillBonus("Survival", false);
    }

    /**
     * The workbook's note on Pain: *"Passing Survival check with pain value allows you to ignore pain effects."*, a check with no
     * roll (Marek 11-14: *"with Survival 75, you automatically pass the check on pain 0-75."*).
     */
    public boolean ignoresPain() {
        double pain = painFelt();
        return pain > 0 && Rules.check(painCheckSurvival(), pain);
    }

    /**
     * A survival stage's own value beyond the attributes, summed: Paralyzed by Pain's AP −99 (Marek 11-19: *"I should have only meant
     * "can't act". ... just remove 99 AP instead."*). A Pain stage counts only while the pain is felt (the Survival check).
     */
    public double stageOther(String key) {
        double s = 0;
        boolean ignore = ignoresPain();
        for (String st : stages()) {
            if (ignore && isPainStage(st)) continue;
            Data.EffectRow r = Data.EFFECTS.get(st);
            if (r != null) s += r.other().getOrDefault(key, 0.0);
        }
        return s;
    }

    /** The current survival stages (their maxima read permanent values, so this never loops back into itself). */
    public List<String> stages() {
        List<String> l = new ArrayList<>();
        for (Rules.Stat st : Rules.Stat.values()) {
            Double v = survival.get(st);
            if (v == null) continue;
            if (st == Rules.Stat.SLEEP && other("sleep_stage_off") > 0) continue;   // caffeine, amphetamine: the debt stays
            String s = stageOf(st, st == Rules.Stat.PAIN ? painFelt() : v, max(st));
            if (s != null) l.add(s);
        }
        return l;
    }

    /** A stat's stage for THIS body: a glowing ghoul's rads give its bonus, never radiation sickness (03b §7b, round 9). */
    public String stageOf(Rules.Stat st, double v, double max) {
        if (st == Rules.Stat.RADS && glowing()) return Rules.glowStage(v);
        return Rules.stage(st, v, max);
    }

    public int[] attrs(boolean permanentOnly) {
        int[] a = new int[7];
        for (int i = 0; i < 7; i++) a[i] = attr(i, permanentOnly);
        return a;
    }

    // ------------------------------------------------------------------ skills
    /** The template's skill bonus (the Ent's Survival +50) + the effects' ("skill:Guns" of a memory chip). */
    public double skillBonus(String skill, boolean permanentOnly) {
        return tpl().skills().getOrDefault(skill, 0.0) + other("skill:" + skill, permanentOnly) + (permanentOnly ? 0 : armourSkill(skill));
    }

    public double[] skills() { return skills(false); }

    public double[] skills(boolean permanentOnly) {
        int[] a = attrs(permanentOnly);
        double[] v = new double[Rules.SKILLS.size()];
        for (int i = 0; i < v.length; i++)
            v[i] = Rules.SKILLS.get(i).base().applyAsDouble(a) + points[i] + skillBonus(Rules.SKILLS.get(i).name(), permanentOnly);
        return v;
    }

    public double skill(String name) { return skills()[Rules.skillIndex(name)]; }

    // ------------------------------------------------------------------ derived
    /** Read from permanent values (a hangover does not cut the maximum), without the survival stages: the stages' maxima read this. */
    public int hpMaxBase() {
        int extra = 0;
        if (has("Medical Marvel")) extra += 5;
        return Rules.hpMax(attrs(true), hpBonus()) + extra + (int) Math.round(other("hp_max", true));
    }

    /**
     * The maximum with the survival stages' own HP Max (the workbook's column: poison −3/−5/−10, Deadly Anemia −8, the heat −5; radiation's
     * moved to the healing rate, 04 §3.8 RULED 11-15), at least 1 [the floor mine].
     */
    public int hpMax() { return Math.max(1, hpMaxBase() + (int) Math.round(stageOther("hp_max"))); }

    public int apMax() {
        int extra = 0;
        if (has("Brittle Bones")) extra += 2;
        int ap = Math.max(0, Rules.ap(attrs(false)) + extra + (int) Math.round(other("ap") + stageOther("ap")) + gasPenalty()[2]   // riot gas AP −2 (03b §3c)
                + CLASS_AP[armourClass()]);   // the armour's class (05c §3b)
        int tier = loadTier();
        return tier >= 3 ? 0 : tier == 2 ? ap / 2 : ap;   // round 11: over half again the load halves the AP, over double none
    }

    // ------------------------------------------------------------------ the load (02; `09b` round 11)
    /*
     * Marek 11-23, round 11, verbatim: *"Fallout 2's pounds - covert it to kg."* · *"overloading - I would say up to +50%, it just means
     * half speed of movement and getting tired faster (and using more food, water and oxygen). Then +50% to +100% means AP halved as well
     * and using even more. And more than that would mean no AP, like with paralyzed."* · *""the plugin doesn't add up what you carry." - it
     * should - please add that function. It should work with inventory."* The needs ×1.5 and ×2 [mine, EXAMPLE].
     */
    /** What he carries now, in kg: the inventory and the armour worn (set each second by the HUD; {@link Items#weight}). */
    public transient double carriedKg;
    /**
     * A pound in OUR kg (09b round 12, Marek 11-23, verbatim: *"If the numbers have needlessly long decimal expansion and it does not allow
     * players to bring in more items, please consider instead convert it to kg with cruder "×0.5"."*): ×0.5, not 0.4536 (round 11's).
     */
    static final double LB = 0.5;

    /**
     * The capacity in kg: Fallout 2's 25 + 25 × S + Athletics pounds (Small Frame 25 + 15 × S; Strong Back +50, Hoarder +25) × 0.5,
     * the Raccoon frame's ×½, + the artifacts' kg (the feather stone +15, the float −10). The Hoarder's own −1 does not lower it.
     */
    public double capacityKg() {
        int st = attr(Rules.S, false) - (int) Math.round(effectMod("hoarder", Rules.S));
        double ath = skills()[Rules.skillIndex("Athletics")];
        double lb = 25 + (has("Small Frame") ? 15 : 25) * st + ath + (hasPerk("Strong Back") ? 50 : 0) + (has("Hoarder") ? 25 : 0);
        double kg = lb * LB;
        for (Data.Gene g : tpl().genes()) if (g.name().equals("Raccoon frame")) kg /= 2;
        return Math.max(0, kg + other("carry_kg"));
    }

    private double effectMod(String slot, int i) {
        for (Effect e : effects) if (e.slot().equals(slot)) return e.mod(i);
        return 0;
    }

    /** 0 = within the capacity; 1 = to +50 % over (Strong Back: from +25 %); 2 = +50 to +100 %; 3 = more. */
    public int loadTier() { return Rules.loadTier(carriedKg, capacityKg(), hasPerk("Strong Back")); }

    /** Each block moved costs this many times the AP (round 11: half speed up to +100 % over). */
    public double loadMoveFactor() { int t = loadTier(); return t == 1 || t == 2 ? 2 : 1; }

    /**
     * The load (round 11) as one effect in the slot "load": tiers 1 and 2 use food, water, sleep and oxygen ×1.5 and ×2 [mine]; tier 3
     * is the paralysed body's AP (none, {@link #apMax}). And the Hoarder's −1 to every attribute below half the capacity (`02c`).
     */
    public void setLoad(double kg) {
        carriedKg = kg;
        int t = loadTier();
        if (t == 0) effects.removeIf(e -> e.slot().equals("load"));
        else {
            double more = t == 1 ? 0.5 : 1.0;
            Map<String, Double> o = new LinkedHashMap<>();
            for (String k : List.of("food_need", "water_need", "sleep_need", "oxygen_need")) o.put(k, more);
            String what = t == 1 ? "half speed, food, water, sleep and breath ×1.5" : t == 2 ? "half speed, AP halved, food, water, sleep and breath ×2"
                    : "too heavy to move: no AP";
            add(new Effect("Overloaded " + SheetGui.f(Math.round(kg * 10) / 10.0) + " / " + SheetGui.f(Math.round(capacityKg() * 10) / 10.0) + " kg: " + what,
                    "load", "load", false, new int[7], o, 1e6, null, 100));
        }
        if (has("Hoarder") && kg < capacityKg() / 2) {
            if (effects.stream().noneMatch(e -> e.slot().equals("hoarder")))
                add(new Effect("Hoarder: carrying less than half (−1 to every attribute)", "trait", "hoarder", false, new int[]{-1, -1, -1, -1, -1, -1, -1}, Map.of(), 1e6, null, 100));
        } else effects.removeIf(e -> e.slot().equals("hoarder"));
    }

    /**
     * E / 3 + Medicine / 50 + Survival / 20 (at least 1) + the template's (the revenant +3) + the effects' + the survival stages'
     * (radiation −3/−5/−10/−10, 04 §3.8 RULED 11-15); at 0 rest heals nothing (RULED 11-19). Below 0 only in Fatal Radiation Poisoning
     * (Marek 11-19: *"I think we do need the negative healing here"*): then the body LOSES that many HP every 8 hours, resting or not
     * ({@link Hud#heal}); every other stage stops at 0. The vampire's +3 when fed is shown, not counted.
     */
    public int healingRate() {
        if (glowing()) return Rules.glowRate(shedRate() * 8, other("healing_half") > 0);   // round 9: by the rads it sheds (shown: at this rate)
        double[] sk = skills();
        int raw = Rules.healingRate(attrs(false), sk[Rules.skillIndex("Medicine")], sk[Rules.skillIndex("Survival")]) + tpl().healingRate()
                + (int) Math.round(other("healing_rate") + stageOther("healing_rate")) - Production.hygieneHealing(unwashedDays);
        if (raw > 0 && other("healing_half") > 0) raw /= 2;   // the husk's, carried by a glowing one that went through it (03b §7b)
        return raw < 0 && stages().contains(FATAL_RADIATION) ? raw : Math.max(0, raw);
    }

    static final String FATAL_RADIATION = "Fatal Radiation Poisoning";

    public int initiative() { return Rules.initiative(attrs(false)) + (int) Math.round(other("sequence")); }

    /** 0 = none, 1 = the dark halved (the elf, low-light eyes), 2 = night vision (catfolk, vampire, Coldvein): the best one counts. */
    public int senses() {
        int s = Rules.senses(tpl().senses());
        return Math.max(s, (int) Math.round(Math.max(0, other("senses"))));
    }

    public static final String[] DAMAGE_TYPES = {"Normal", "Rad", "Poison/Disease", "Fire", "Heat", "Isolation"};

    /**
     * The resistances in % by {@link #DAMAGE_TYPES}: the sheet's own (Normal = Athletics / 10, Rad = 2 E + Survival / 10, Poison and
     * disease = 5 E + Survival / 4; the rest the armour, none yet) + the template's + the effects'. Isolation = the old Cold/Lightning
     * (renamed, Marek 11-16); poison and disease ONE value (RULED 11-16); Heat = Fallout's Laser and Plasma in one, also against the
     * weather's heat (05c §2, Marek 11-18: *"Heat DR/DT is laser/plasma defence"*).
     */
    public double[] resistances() {
        int[] a = attrs(false);
        double[] sk = skills();
        double surv = sk[Rules.skillIndex("Survival")];
        Data.Template t = tpl();
        return new double[]{
                sk[Rules.skillIndex("Athletics")] / 10 + 100 * t.damageRes() + other("dr"),
                2 * a[Rules.E] + surv / 10 + 100 * t.radRes() + other("rad_res") + armourRes(true),
                5 * a[Rules.E] + surv / 4 + 100 * t.poisonRes() + other("poison_res") + armourRes(false),
                other("fire_res"), other("heat_res"),
                100 * t.isolation() + other("isolation")};
    }

    /** DR against normal damage as a fraction, capped at 90 % [the cap mine]. */
    public double dr() { return Math.max(-1, Math.min(0.9, resistances()[0] / 100)); }

    public int dt() { return tpl().damageThreshold() + (int) Math.round(other("dt")); }

    public double max(Rules.Stat st) {
        if (st == Rules.Stat.OXYGEN) return breathRounds();
        double[] perm = skills(true);
        return Rules.max(st, attrs(true), perm[Rules.skillIndex("Survival")], points[Rules.skillIndex("Survival")], hpMaxBase(), hpBonus());
    }

    /**
     * An hour of the survival stages' HP (the workbook's HP Current, 04 RULED 11-15: *"HP Current - it is incremental. Based on notes, it
     * seems to be hourly rate."*): poison's and the heat's every hour, radiation's ONCE when its stage begins (`hp_once`: *"in case of
     * radiation, it should be one off effect"*); HP above a lowered maximum fall to it. {@code before} = the stages at the hour's start.
     * Returns the news, or null.
     */
    public String stageHour(List<String> before) {
        if (dead || inArrest()) return null;
        String scar = before.contains(FATAL_RADIATION) && !stages().contains(FATAL_RADIATION) ? surviveFatal() : null;
        if (before.contains(DEADLY_COLD) && !stages().contains(DEADLY_COLD)) scar = (scar == null ? "" : scar + " · ") + frostbite();
        int loss = 0;
        List<String> why = new ArrayList<>();
        boolean ignore = ignoresPain();
        for (String st : stages()) {
            if (ignore && isPainStage(st)) continue;
            Data.EffectRow r = Data.EFFECTS.get(st);
            if (r == null) continue;
            double h = -r.other().getOrDefault("hp_current", 0.0);
            if (!before.contains(st)) h -= onceUpTo(st, before);
            if (h > 0) { loss += (int) Math.round(h); why.add(st); }
        }
        int max = hpMax();
        if (hp > max) hp = max;
        if (loss <= 0) return scar;
        return (scar == null ? "" : scar + " · ") + String.join(", ", why) + ": HP −" + loss + drain(loss);
    }

    static final String FATAL_SCAR = "Survived Fatal Radiation";

    /**
     * Out of Fatal Radiation Poisoning alive (Marek 11-19: *"I think I would allow survival with extreme resource use, that would have
     * permanent penalities."*): the way out is RadAway (and the Stimpaks that outrun the drain); what it leaves is permanent:
     * Endurance −1 and HP Max −5 each time [mine, EXAMPLE numbers for his ruling].
     */
    String surviveFatal() {
        int[] m = new int[7];
        m[Rules.E] = -1;
        effects.add(new Effect(FATAL_SCAR, "scar", "", true, m, Map.of("hp_max", -5.0), 0, null, 100));
        if (hp > hpMax()) hp = hpMax();
        return FATAL_SCAR + ": Endurance −1 and HP Max −5 for good";
    }

    static final String DEADLY_COLD = "Deadly Hypothermia", FROSTBITE = "Frostbite";

    /**
     * Out of Deadly Hypothermia alive: the workbook's own Frostbite (its row: *"After Deadly Hypothermia"*, the stage's note
     * *"frostbite if cured"*), HP Max −3 for good, each time. In the plugin since 0.31.0, when Marek ruled the Fatal Radiation scar
     * the same kind of thing (11-19, verbatim: *""Whoever comes out of Fatal alive now keeps a scar" - agreed. Similar to
     * frostbite."*).
     */
    String frostbite() {
        Data.EffectRow r = Data.EFFECTS.get(FROSTBITE);
        if (r == null) throw new IllegalStateException("effects.json has no " + FROSTBITE);
        effects.add(new Effect(FROSTBITE, "scar", "", true, r.mods().clone(), Map.copyOf(r.other()), 0, null, 100));
        if (hp > hpMax()) hp = hpMax();
        double m = r.other().getOrDefault("hp_max", 0.0);
        return FROSTBITE + ": HP Max " + (m < 0 ? "−" : "+") + (int) Math.abs(m) + " for good";
    }

    static final List<String> RAD_LADDER = List.of("Minor Radiation Poisoning", "Advanced Radiation Poisoning", "Critical Radiation Poisoning",
            "Deadly Radiation Poisoning", "Fatal Radiation Poisoning");

    /**
     * A stage just begun: its one-off HP (negative), and on radiation's ladder every rung passed in the same hour too (a dose that jumps from
     * none to Deadly passes Critical) [mine].
     */
    static double onceUpTo(String stage, List<String> before) {
        int to = RAD_LADDER.indexOf(stage);
        if (to < 0) { Data.EffectRow r = Data.EFFECTS.get(stage); return r == null ? 0 : r.other().getOrDefault("hp_once", 0.0); }
        int from = -1;
        for (String b : before) from = Math.max(from, RAD_LADDER.indexOf(b));
        double s = 0;
        for (int i = from + 1; i <= to; i++) {
            Data.EffectRow r = Data.EFFECTS.get(RAD_LADDER.get(i));
            if (r != null) s += r.other().getOrDefault("hp_once", 0.0);
        }
        return s;
    }

    /** HP lost without a wound (a survival stage): no Pain, no bleeding; below −Endurance clinical death begins. Returns what it left. */
    String drain(int n) {
        hp -= n;
        int floor = Rules.deathFloor(attrs(false));
        if (hp < floor && arrestAt < 0) {
            arrestAt = now();
            return ", CLINICAL DEATH" + autoDefibrillate(java.util.concurrent.ThreadLocalRandom.current());
        }
        return hp > 0 ? ", HP " + hp : hp >= floor ? ", DYING (HP " + hp + ", dead below " + floor + ")" : "";
    }

    /**
     * The hourly change with parts 3 and 4 in it: the template's food and water (the orc ×1.25, Coldvein ×0.6; a synthetic body
     * drinks nothing, its food is its power and its sleep its memory, Marek 11-16), the place's rads × (1 − rad resistance) (part 4's table) + the effects'
     * (RadAway, the Blood Scrubber).
     */
    public double hourly(Rules.Stat st) {
        double h = Rules.hourly(st, survival.getOrDefault(Rules.Stat.HYPERTHERMIA, 0.0), sleeping, locationRads);
        Data.Template t = tpl();
        boolean power = "power".equals(t.needs());
        return switch (st) {
            case WATER -> power || baseWater ? 0 : h * t.waterFactor() * (1 + other("water_need"));   // an artifact's price (the ember: x1.25)
            case FOOD -> baseFood && !power ? 0 : h * t.foodFactor() * (1 + other("food_need"));   // a synthetic body: its power; the green heart x1.25
            case SLEEP -> h < 0 ? h * t.sleepFactor() * (1 + other("sleep_need")) : h;   // the need (DEC2: x0.75); a synthetic body: its memory, cleaned like sleep
            case HYPOTHERMIA -> h + other("cold_per_hour");   // the frost pearl's price (09c §3)
            case RADS -> (locationRads + nearRads) * (1 - Math.min(100, resistances()[1]) / 100) + other("rads_per_hour");   // + the glowing ones near (round 9)
            case BLOOD -> inArrest() ? 0 : h;   // no circulation, no new blood [mine]
            default -> h;
        };
    }

    // ------------------------------------------------------------------ effects: one slot, one effect
    /** Adds an effect; one in the same slot is replaced (RULED 11-16) and returned. */
    public Effect add(Effect e) {
        Effect old = null;
        if (!e.slot().isEmpty())
            for (Iterator<Effect> it = effects.iterator(); it.hasNext(); ) {
                Effect x = it.next();
                if (x.slot().equals(e.slot())) { old = x; it.remove(); }
            }
        effects.add(e);
        return old;
    }

    public Effect inSlot(String slot) {
        for (Effect e : effects) if (e.slot().equals(slot)) return e;
        return null;
    }

    // ------------------------------------------------------------------ implants (03b §3)
    public List<Effect> implants() {
        List<Effect> l = new ArrayList<>();
        for (Effect e : effects) if (e.source().equals("implant")) l.add(e);
        return l;
    }

    /** The slots this body has: the data's, + one brain slot with the synthetic family perk Upgrade Port. */
    public int slots(String slot) { return Data.SLOTS.getOrDefault(slot, 0) + (slot.equals("brain") && hasPerk("Upgrade Port") ? 1 : 0); }

    /**
     * Fits an implant at a tier (03b §3b, RULED 11-18: I-IV, each tier its own numbers; T II an infection roll a month, T I twice),
     * its condition 100. Returns null when fitted, else why not (no such tier, the slot full, the Neural Link missing, a robot).
     */
    public String fit(Data.Implant im, int tier, String chosenSkill) {
        Data.Implant.At at = im.at(tier);
        if (at == null) {
            StringBuilder ts = new StringBuilder();
            for (int k : im.tiers().keySet()) ts.append(ts.isEmpty() ? "" : ", ").append(Rules.roman(k));
            return im.name() + " has no Tier " + Rules.roman(tier) + " (it exists at " + ts + ")";
        }
        if (tpl().name().equals("Basic Robot") || tpl().family().equals("animal")) return "a " + template + " takes no implants";
        if (!im.needs().isEmpty() && implants().stream().noneMatch(e -> e.name().startsWith(im.needs())))
            return im.name() + " needs the " + im.needs() + " first";
        int n = slots(im.slot());
        String free = null;
        for (int k = 1; k <= n && free == null; k++) if (inSlot("implant:" + im.slot() + "#" + k) == null) free = "implant:" + im.slot() + "#" + k;
        if (free == null) return "the " + im.slot() + " slot is taken (" + n + " " + (n == 1 ? "implant" : "implants") + "): remove one first";
        Map<String, Double> other = new LinkedHashMap<>(at.other());
        if (im.skillChoice() != 0) {
            if (chosenSkill == null) return im.name() + ": name the skill (e.g. Guns)";
            try { Rules.skillIndex(chosenSkill); } catch (IllegalArgumentException x) { return "no skill " + chosenSkill; }
            other.put("skill:" + chosenSkill, (double) im.skillChoice());
        }
        if (!at.senses().isEmpty()) other.put("senses", (double) Rules.senses(at.senses()));
        int[] mods = at.mods().clone();
        String tierName = Rules.roman(tier);
        add(new Effect(im.name() + (chosenSkill != null && im.skillChoice() != 0 ? " (" + chosenSkill + ")" : "") + " · Tier " + tierName,
                "implant", free, true, mods, other, 0, null, 100));
        return null;
    }

    /** Takes out what fills a slot ("eyes", "brain#2") or an implant by its name's start; returns it, or null. */
    public Effect removeImplant(String what) {
        String w = what.toLowerCase(Locale.ROOT).replace('_', ' ');
        for (Iterator<Effect> it = effects.iterator(); it.hasNext(); ) {
            Effect e = it.next();
            if (!e.source().equals("implant")) continue;
            String s = e.slot().substring("implant:".length());
            if (s.equalsIgnoreCase(what) || s.replace("#1", "").equalsIgnoreCase(what) || e.name().toLowerCase(Locale.ROOT).startsWith(w)) {
                it.remove();
                return e;
            }
        }
        return null;
    }

    /** Where a slot sits: the eyes, ears and brain in the head, the organs in the torso, skin and skeleton everywhere, a limb. */
    static String place(String slot) {
        String s = slot.replaceAll("^(implant|limb):", "").replaceAll("#\\d+$", "");
        return switch (s) {
            case "brain", "eyes", "ears" -> "head";
            case "skin", "skeleton" -> "everywhere";
            case "left_arm", "right_arm", "left_leg", "right_leg" -> s;
            default -> "torso";
        };
    }

    /**
     * A hit on a part costs the implants and the prosthesis there condition = the damage in % (a crit × 2) (Marek 11-16: *"they will
     * be damaged by both fight (unavoidable, unless you hit different place) and the removal"*; the amount [mine]). Returns the log.
     */
    public String wearOnHit(Rules.Part part, int dmg, boolean crit) {
        if (dmg <= 0 || part == null) return "";
        String at = switch (part) {
            case EYES, HEAD -> "head";
            case TORSO, GROIN -> "torso";
            case LEFT_ARM -> "left_arm";
            case RIGHT_ARM -> "right_arm";
            case LEFT_LEG -> "left_leg";
            case RIGHT_LEG -> "right_leg";
        };
        StringBuilder sb = new StringBuilder();
        for (ListIterator<Effect> it = effects.listIterator(); it.hasNext(); ) {
            Effect e = it.next();
            if (!e.worn()) continue;
            String pl = place(e.slot());
            if (!pl.equals(at) && !pl.equals("everywhere")) continue;
            Effect n = e.withCondition(e.condition() - dmg * (crit ? 2 : 1));
            it.set(n);
            sb.append(" ").append(e.name()).append(" ").append(Math.round(n.condition())).append(" %").append(n.condition() <= 0 ? " (BROKEN)" : "").append(";");
        }
        return sb.isEmpty() ? "" : " Implants hit:" + sb.substring(0, sb.length() - 1) + ".";
    }

    // ------------------------------------------------------------------ grafts from a donor (03e §4 T II, `09e` §6)
    /**
     * The transplant (Marek 11-24, verbatim: *"The transplant operation itself still isn't built. - please, look into that."*; the donor
     * organ a T II RULED `09d` round 5, the limb a T II with a worse rejection and the made drug Tier III RULED `09e` round 2): a donor's
     * organ in the place's implant slot (03e §3: one thing a place), the arm in its limb's place. While the anti-rejection drugs are taken
     * (Tacrolimus = the pre-war rate, Azathioprine = the made one) a monthly rejection roll; two weeks without them = rejected. The
     * corneas need no drugs and no match. The places, the operation's factor and the rates [03e §4, `09e` §6; the numbers mine, EXAMPLE].
     */
    public record Graft(String organ, String place, double op, double preWar, double made, boolean drugs) {}

    public static final List<Graft> GRAFTS = List.of(
            new Graft("kidney", "gut", 0.8, 2, 5, true),
            new Graft("liver", "gut", 0.8, 2, 5, true),
            new Graft("heart", "heart", 0.7, 2, 5, true),
            new Graft("lungs", "lungs", 0.8, 2, 5, true),
            new Graft("corneas", "eyes", 0.8, 0, 0, false),
            new Graft("arm", "limb", 0.6, 5, 10, true));
    /** A month of game hours; two weeks without the drugs; the arm's use from 20 % to 90 % over 270 days (nerves ~1 mm a day) [mine]. */
    static final double MONTH = 720, WITHOUT_DRUGS = 336, LIMB_START = 20, LIMB_FULL = 90, LIMB_DAYS = 270;

    public static Graft graftOf(String organ) {
        for (Graft g : GRAFTS) if (g.organ().equalsIgnoreCase(organ == null ? "" : organ.trim())) return g;
        return null;
    }

    /** The blood group (the transplant's match, `09e` §6), drawn by Central Europe's shares when first needed. */
    public String blood = "";

    public String bloodGroup(Random rnd) {
        if (blood == null || blood.isEmpty()) blood = Loot.bloodGroup(rnd);
        return blood;
    }

    /** ABO only [real: Rh does not bar a solid organ]: O gives to all, AB takes from all. */
    static boolean aboMatch(String donor, String patient) {
        String d = donor.replaceAll("[+\\-−]", "").toUpperCase(Locale.ROOT), p = patient.replaceAll("[+\\-−]", "").toUpperCase(Locale.ROOT);
        return d.equals("O") || d.equals(p) || p.equals("AB");
    }

    /** The strongest anti-rejection drug taken now: 2 the pre-war tablets, 1 the made drug, 0 none. */
    public double immuno() {
        double m = 0;
        for (Effect e : effects) m = Math.max(m, e.other("immuno"));
        return m;
    }

    /** Puts a donor's organ in (the operation has succeeded): null when done, else why not. {@code limb} = left_arm or right_arm for an arm. */
    public String graft(String organ, String donorBlood, String limb, Random rnd) {
        Graft g = graftOf(organ);
        if (g == null) return "a donor organ: kidney, liver, heart, lungs, corneas, arm";
        if (tpl().name().equals("Basic Robot") || tpl().family().equals("animal")) return "a " + template + " takes no donor organ";
        String donor = donorBlood == null ? "" : donorBlood.trim();
        if (g.drugs() && !aboMatch(donor, bloodGroup(rnd))) return "the donor's blood group " + donor + " does not match " + blood + " (O gives to all, AB takes from all)";
        String slot = null;
        if (g.place().equals("limb")) {
            if (limb == null || !List.of("left_arm", "right_arm").contains(limb)) return "which arm: left_arm or right_arm";
            Effect there = inSlot("limb:" + limb);
            if (there == null) return "the " + limb.replace('_', ' ') + " is whole";
            if (!there.source().equals("injury")) return "take off the " + there.name() + " first";
            effects.remove(there);
            slot = "limb:" + limb;
        } else {
            int n = slots(g.place());
            for (int k = 1; k <= n && slot == null; k++) if (inSlot("implant:" + g.place() + "#" + k) == null) slot = "implant:" + g.place() + "#" + k;
            if (slot == null) return "the " + g.place() + " slot is taken: take out what is there first (03e §3: one thing a place)";
        }
        Map<String, Double> o = new LinkedHashMap<>();
        o.put("graft_drugs", g.drugs() ? 1.0 : 0.0);
        o.put("graft_prewar", g.preWar());
        o.put("graft_made", g.made());
        o.put("graft_hours", 0.0);
        o.put("graft_without", 0.0);
        if (slot.startsWith("limb:")) o.put("efficiency", LIMB_START);
        add(new Effect("Donor " + organ.toLowerCase(Locale.ROOT) + (slot.startsWith("limb:") ? " (" + limb.replace('_', ' ') + ")" : "")
                + " · graft, Tier II" + (g.drugs() ? ", blood " + donor : ""), "graft", slot, true, new int[7], o, 0, null, 100));
        if (g.organ().equals("corneas")) effects.removeIf(e -> e.name().startsWith("One Eye Blind"));
        return null;
    }

    public List<Effect> grafts() {
        List<Effect> l = new ArrayList<>();
        for (Effect e : effects) if (e.source().equals("graft")) l.add(e);
        return l;
    }

    /** One game hour of the grafts: the arm's nerves regrow, two weeks without the drugs reject, the month's roll. Null when nothing happened. */
    public String graftHour(Random rnd) {
        double drug = immuno();
        StringJoiner out = new StringJoiner("; ");
        for (int i = 0; i < effects.size(); i++) {
            Effect e = effects.get(i);
            if (!e.source().equals("graft")) continue;
            Map<String, Double> o = new LinkedHashMap<>(e.other());
            double h = o.getOrDefault("graft_hours", 0.0) + 1;
            o.put("graft_hours", h);
            String why = null;
            if (o.getOrDefault("graft_drugs", 0.0) > 0) {
                double without = drug > 0 ? 0 : o.getOrDefault("graft_without", 0.0) + 1;
                o.put("graft_without", without);
                if (without >= WITHOUT_DRUGS) why = "two weeks without the anti-rejection drugs";
                else if (drug > 0 && Math.abs(h % MONTH) < 1e-9) {
                    double rate = drug >= 2 ? o.getOrDefault("graft_prewar", 0.0) : o.getOrDefault("graft_made", 0.0);
                    int d = 1 + rnd.nextInt(100);
                    if (d <= rate) why = "the month's rejection roll, " + d + " of " + Math.round(rate) + " % (" + (drug >= 2 ? "the pre-war drug" : "the made drug") + ")";
                }
            }
            if (e.slot().startsWith("limb:"))
                o.put("efficiency", Math.min(LIMB_FULL, LIMB_START + (LIMB_FULL - LIMB_START) * h / (LIMB_DAYS * 24)));
            if (why == null) {
                effects.set(i, new Effect(e.name(), e.source(), e.slot(), true, e.mods(), o, 0, e.next(), e.condition()));
                continue;
            }
            effects.remove(i--);
            String part = e.slot().startsWith("limb:") ? e.slot().substring(5) : null;
            if (part != null) add(new Effect("Missing " + part.replace('_', ' '), "injury", "limb:" + part, true, new int[7],
                    new LinkedHashMap<>(Map.of("efficiency", 0.0)), 0, null, 100));
            out.add(e.name() + " REJECTED (" + why + "): " + rejected(e));
        }
        return out.length() == 0 ? null : out.toString();
    }

    private static String rejected(Effect e) {
        String s = e.slot();
        if (s.startsWith("limb:")) return "the arm dies and is taken off: the limb is missing again";
        if (e.name().startsWith("Donor kidney")) return "the old failure is back: weeks to live without another kidney (the GM)";
        return "the old failure is back: part 4's death roll unless another graft comes in time (the GM)";
    }

    // ------------------------------------------------------------------ prostheses (03b §6)
    /** A limb's efficiency in %: a natural one 100, else the prosthesis's (hook 50, peg 60, mechanical 80, powered 100, bionic 120) × its condition. */
    public double efficiency(String limb) {
        Effect e = inSlot("limb:" + limb);
        return e == null ? 100 : e.other("efficiency");
    }

    /** The to-hit of an attack with the main hand (the right arm), and the other one too with a two-handed weapon. */
    public int armToHit(boolean twoHanded) {
        double eff = twoHanded ? Math.min(efficiency("right_arm"), efficiency("left_arm")) : efficiency("right_arm");
        return Rules.prostheticToHit(eff);
    }

    /** Missing legs (no prosthesis): each moves as a broken leg does (the stance table's 400 %, both 800 %) [mine]. */
    public int missingLegs() {
        int n = 0;
        for (String l : List.of("left_leg", "right_leg")) { Effect e = inSlot("limb:" + l); if (e != null && e.source().equals("injury")) n++; }
        return n;
    }

    /** Movement's AP × this: 1, a prosthetic leg moves as a natural one (RULED 11-16: *"They are 100% efficient, so just the same as normal leg would."*). */
    public double legFactor() { return 1; }

    /** Fits (or with "none" takes off) a prosthesis; null when done, else why not. */
    public String fitLimb(String limb, String kind) {
        if (!List.of("left_arm", "right_arm", "left_leg", "right_leg").contains(limb)) return "a limb: left_arm, right_arm, left_leg, right_leg";
        if (kind.equals("none") || kind.equals("natural")) { effects.removeIf(e -> e.slot().equals("limb:" + limb)); return null; }
        boolean leg = limb.endsWith("leg");
        if (kind.equals("missing")) {   // Marek 11-16: "Prostesis has no option for missing limb."
            add(new Effect("Missing " + limb.replace('_', ' '), "injury", "limb:" + limb, true, new int[7],
                    new LinkedHashMap<>(Map.of("efficiency", 0.0)), 0, null, 100));
            return null;
        }
        Double eff = Rules.prosthesis(kind, leg);
        if (eff == null) return "a kind: " + (leg ? "peg" : "hook") + ", mechanical, powered, bionic, missing, none";
        if (kind.equals("bionic") && implants().stream().noneMatch(e -> e.name().startsWith("Neural Link")))
            return "a bionic limb needs the Neural Link (03b §3)";
        add(new Effect(Rules.prosthesisName(kind, leg) + " (" + limb.replace('_', ' ') + ")", "prosthesis", "limb:" + limb, true, new int[7],
                new LinkedHashMap<>(Map.of("efficiency", eff)), 0, null, 100));
        return null;
    }

    // ------------------------------------------------------------------ chems (03b §8)
    static final String[] STAGE_NAMES = {"", " (after-effect)", " (withdrawal)", " (dependence)"};

    /** The chem a stage belongs to, from its slot; null if not a chem's. */
    static Data.Chem chemOf(Effect e) {
        if (!e.source().equals("chem") || !e.slot().startsWith("chem:")) return null;
        return Data.CHEMS.get(e.slot().substring(5));
    }

    /** The stage's index (0 effect, 1 crash, 2 withdrawal, 3 dependence) from its name. */
    static int stageIndex(Data.Chem ch, Effect e) {
        for (int i = 3; i >= 0; i--) if (e.name().equals(ch.name() + STAGE_NAMES[i])) return i;
        return 0;
    }

    /** The stage after {@code from}: the crash, and withdrawal and dependence only if addicted; -1 = clean. */
    int nextStage(Data.Chem ch, int from) {
        for (int i = from + 1; i <= 3; i++) {
            if (ch.stage(i) == null) continue;
            if (i >= 2 && !addictions.contains(ch.name())) return -1;
            return i;
        }
        return -1;
    }

    Effect stageEffect(Data.Chem ch, int i) {
        Data.Stage s = ch.stage(i);
        Map<String, Double> other = new LinkedHashMap<>(s.other());
        if (other.containsKey("radaway")) {   // 04 (RULED 11-15): 50 + Medicine rads a dose, out over its hours; its value a share
            double k = other.remove("radaway");   // (09e round 2: Prussian blue, the lower RadAway, ½ of it over three days)
            other.put("rads_per_hour", -k * (50 + skill("Medicine")) / s.hours());
        }
        int n = nextStage(ch, i);
        int[] mods = s.mods().clone();
        if (i == 0 && oldFriend(ch)) {   // Old Friend (02c): the effect x1.5 (a switch such as a sleep stage off stays one)
            for (int k = 0; k < mods.length; k++) mods[k] = (int) (Math.signum(mods[k]) * Math.round(Math.abs(mods[k]) * 1.5));
            other.replaceAll((k, v) -> OLD_FRIEND_SWITCHES.contains(k) ? v : v * 1.5);
        }
        return new Effect(ch.name() + STAGE_NAMES[i], "chem", "chem:" + ch.name(), false, mods, other, s.hours(),
                n < 0 ? null : ch.name() + STAGE_NAMES[n], 100);
    }

    /** The chems' switches, which Old Friend does not multiply. */
    static final Set<String> OLD_FRIEND_SWITCHES = Set.of("sleep_stage_off");

    /** Old Friend bound to this chem (02c). */
    boolean oldFriend(Data.Chem ch) { return hasPerk("Old Friend: " + ch.name()); }

    /** When a timed effect runs out: a chem's next stage (or clean: an addiction that has run its course ends), else nothing. */
    Effect afterStage(Effect e) {
        Data.Chem ch = chemOf(e);
        if (ch == null) return null;
        int at = stageIndex(ch, e);
        int n = nextStage(ch, at);
        if (n < 0) {
            if (at >= 2) addictions.remove(ch.name());   // withdrawal (with no dependence) or dependence done: clean
            return null;
        }
        return stageEffect(ch, n);
    }

    /**
     * A dose (03b §8, RULED): stage 1 in place of whatever stage of it runs (in withdrawal or dependence it also restarts the
     * addiction's clocks); the addiction roll: the chem's chance × 2 Chem Reliant, × ½ Chem Resistant, × (1 + ½ a dose of it in the
     * last 7 days); in dependence the next dose addicts at once. An antidote ends its chem's addiction.
     */
    /**
     * The chems taken by a needle (Marek 11-19: *"chem: bad - use MC, and stimpak mc for injection"*): these sound as the Stimpak,
     * the rest (pills, drinks, smoke, Jet's inhaler) as a drink [the list mine: Fallout 2's Psycho is a syringe, RadAway an IV bag].
     */
    static final Set<String> INJECTED = Set.of("Morphine", "Ketamine", "Psycho", "RadAway", "Jet Antidote");

    public String dose(Data.Chem ch, Random rnd) {
        cues.add(INJECTED.contains(ch.name()) ? "stimpak" : "chem");
        List<Double> past = doses.computeIfAbsent(ch.name(), k -> new ArrayList<>());
        past.removeIf(t -> t < clock - 24 * 30);
        int recent = (int) past.stream().filter(t -> t > clock - 168).count();
        boolean usedToday = past.stream().anyMatch(t -> t > clock - 24);
        past.add(clock);
        StringBuilder sb = new StringBuilder(ch.name() + ": ");
        if (ch.cures() != null) {
            boolean was = addictions.remove(ch.cures());
            effects.removeIf(e -> e.slot().equals("chem:" + ch.cures()) && (e.name().endsWith("(withdrawal)") || e.name().endsWith("(dependence)")));
            sb.append(was ? "the " + ch.cures() + " addiction ends. " : "no " + ch.cures() + " addiction to end. ");
        }
        if (addictions.contains(ch.name())) sb.append("addicted: the stages start again. ");
        else if (oldFriend(ch)) sb.append("Old Friend: x1.5, and it never addicts you. ");
        else {
            Effect cur = inSlot("chem:" + ch.name());
            if (cur != null && cur.name().endsWith("(dependence)")) { addictions.add(ch.name()); sb.append("in dependence: ADDICTED again at once. "); }
            else if (ch.chance() > 0 && !(ch.perDay() && usedToday)) {
                double p = Rules.addictionChance(ch.chance(), has("Chem Reliant"), has("Chem Resistant"), recent) * Math.max(0, 1 + other("addiction"));   // the Mood Stimulator (03b §3b)
                boolean got = rnd.nextDouble() < p;
                if (got) addictions.add(ch.name());
                sb.append("addiction roll ").append(Math.round(p * 100)).append(" %").append(recent > 0 ? " (" + recent + " earlier dose(s) in 7 days)" : "")
                        .append(got ? ": ADDICTED. " : ": not addicted. ");
            }
        }
        if (ch.effect() != null) {
            Effect e = stageEffect(ch, 0);
            add(e);
            sb.append(SheetGui.f(e.hoursLeft())).append(" h").append(e.next() != null ? ", then " + e.next() : "").append(".");
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ mutations (03b §7)
    /** The yearly roll: expels {@code rads} (100-500 in steps of 100), rolls and adds a mutation of the category. */
    public String mutate(int rads, Random rnd) {
        if (rads < 100 || rads > 500 || rads % 100 != 0) return "expel 100, 200, 300, 400 or 500 rads (RULED: the cap 500)";
        double held = survival.getOrDefault(Rules.Stat.RADS, 0.0);
        if (held < rads) return "you hold only " + Math.round(held) + " rads";
        int d = 1 + rnd.nextInt(100);
        int score = Rules.mutationScore(d, attr(Rules.L, true), rads);
        String cat = Rules.mutationCategory(score);
        survival.put(Rules.Stat.RADS, held - rads);
        String what = applyMutation(cat, rnd);
        return "d100 " + d + " + (Luck − 5) × 4 − 10 a 100 rads above the first = " + score + ": " + cat.toUpperCase(Locale.ROOT) + " → " + what
                + "; " + rads + " rads expelled" + ghoulLine(cat, score, rnd);
    }

    /**
     * What a mutation's result means for the ghoul line (03b §7, RULED 11-22: *"the third mutation, if it rolls bad or very bad, turns the
     * person into a ghoul." - seems fine.*; §7b, round 7: a ghoul's very bad result = the next stage). Since 0.64.0 the sheet turns the
     * body itself (`09b` round 9, Marek 11-23: *""a human turning into a ghoul (you set the template)" - what exactly is needed? Cannot we
     * just use the ghoul template that we already have?"*): {@link #turnGhoul}.
     */
    String ghoulLine(String cat, int score, Random rnd) {
        long n = mutationCount();
        boolean ghoul = isGhoul();
        if (!ghoul && n >= 3 && (cat.equals("bad") || cat.equals("very bad")))
            return " → mutation " + n + ", " + cat + ": " + turnGhoul();
        if (ghoul && cat.equals("very bad")) return ghoulAdvance(Rules.ghoulSteps(score), rnd);
        return "";
    }

    /**
     * The body turns GHOUL (03b §7; 09b round 12, Marek 11-23, verbatim: *"so ghoul is now not gene but implant? Why? It is supposed to be a
     * mutation."* · *"ghouls should be possible to reconstruct into genes as well - add a new slot if needed."*): the GHOUL GENE fills the
     * new Necrosis slot; the template, its genes and what the player built stay (his points, skills, traits, perks, mutations and
     * therapies). An attribute the gene would take below 1 is kept at 1 [mine]; a trait the Ghoul template bars stays until the GM removes
     * it [mine]; the perk rate stays the template's [mine]. A synthetic body never turns. Returns the news.
     */
    public String turnGhoul() {
        Data.Template born = bornTpl(), gt = Data.TEMPLATES.get("Ghoul");
        Data.Gene g = Data.GENES.get("Ghoul");
        if (g == null) return "the body turns GHOUL (no Ghoul gene in the data: the GM sets it)";
        if (born.family().equals("synthetic")) return "a synthetic body cannot turn ghoul";
        if (isGhoul()) return "already a ghoul";
        int[] body = baseTpl().mods(), gm = gt != null ? gt.mods() : g.mods();   // the ghoul's whole numbers (the Necrosis gene and its parts)
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < 7; i++)
            if (base[i] + body[i] + gm[i] < 1) { base[i] = 1 - body[i] - gm[i]; kept.add(Rules.SPECIAL[i] + " kept at 1"); }
        necrosis = true;
        bodyTpl = null;
        int rate0 = templatePerkRate;
        if (templatePerkRate > 0) templatePerkRate = Math.max(templatePerkRate, gt != null ? gt.perkRate() : Rules.GHOUL_PERK_RATE);   // round 13
        List<String> barred = new ArrayList<>();
        if (gt != null) for (Feat t : traits) if (gt.forbiddenTraits().contains(t.name())) barred.add(t.name());
        ghoulPath.clear();
        crisisRolled = false;
        if (hp > hpMax()) hp = hpMax();
        return "the body turns GHOUL: the Ghoul gene fills the Necrosis slot (03b §7b: rad res +40 %, L +3) and the ghoul's other numbers change "
                + "the genes of Muscle (S −2), Eyes (P +3), Mind (I +3), Frame (A −4, HP −5) and Immunity (poison +10 %); the template (" + born.name()
                + ") and its genes stay"
                + (templatePerkRate != rate0 ? "; a perk every " + templatePerkRate + " levels now (was " + rate0 + ")" : "")
                + (kept.isEmpty() ? "" : "; " + String.join(", ", kept))
                + (barred.isEmpty() ? "" : "; a ghoul's body bars " + String.join(", ", barred) + ": the GM decides");
    }

    public String ghoulStage() { return ghoulPath == null || ghoulPath.isEmpty() ? "" : ghoulPath.get(ghoulPath.size() - 1); }

    public boolean glowing() { return "glowing".equals(ghoulStage()); }

    /**
     * Moves a ghoul {@code steps} stages along the line (03b §7b, round 8) and puts the line's ONE effect in its slot: each stage's
     * numbers are its totals against the base ghoul (Marek's, round 7), a later stage's numbers replace an earlier one's, and what a stage
     * passed through gave and no later one gives anew carries on (the leathered hide's DT +3; *"when evolving slowly through leathered
     * ghoul, you get the +3 DT"*); a jump skips the stages between. Reaching glowing gives a mental illness; a glowing one's further very
     * bad result gives one more. Returns the news.
     */
    public String ghoulAdvance(int steps, Random rnd) {
        if (ghoulPath == null) ghoulPath = new ArrayList<>();
        if (steps <= 0 || !isGhoul()) return "";
        int at = ghoulPath.isEmpty() ? 0 : java.util.Arrays.asList(Rules.GHOUL_STAGES).indexOf(ghoulStage()) + 1;
        if (at >= 3) return " → already glowing: " + madness(rnd);
        int to = Math.min(3, at + steps);
        String from = at == 0 ? "ghoul" : ghoulStage();
        ghoulPath.add(Rules.GHOUL_STAGES[to - 1]);
        bodyTpl = null;   // 09b round 12: the stage is the Necrosis gene's variant (Data.ghoulVariant), no effect
        int rate0 = templatePerkRate;   // round 13: the further from human, the slower the perks
        if (templatePerkRate > 0) templatePerkRate = Math.max(templatePerkRate, Rules.GHOUL_STAGE_PERK_RATE[to - 1]);
        if (glowing()) {   // round 9: it keeps its rads, up to the cap; no crisis any more
            survival.put(Rules.Stat.RADS, Math.min(Rules.GLOW_CAP, survival.getOrDefault(Rules.Stat.RADS, 0.0)));
            crisisRolled = false;
        }
        String s = " → THE GHOUL LINE: " + from + " → " + ghoulStage() + (to - at > 1 ? " (a jump of " + (to - at) + " stages)" : "") + " (03b §7b)"
                + (templatePerkRate != rate0 ? "; a perk every " + templatePerkRate + " levels now" : "");
        return to == 3 ? s + "; " + madness(rnd) : s;
    }

    /** The Necrosis slot's gene now: the Ghoul gene or its stage (03b §7b), or null for a body that is no ghoul. */
    public Data.Gene necrosisGene() { return geneIn(baseTpl(), Data.NECROSIS); }

    static final String[][] ILLNESSES = {
            {"paranoia", "trusts nobody: Charisma checks with strangers −20"},
            {"the voices", "Perception −10 in quiet; the GM may give it one false order a session"},
            {"the fixed idea", "one thing it must gather, guard or reach"},
            {"the old life", "it lives on day D's eve: talks to the dead, keeps its pre-war habits"},
            {"the swings", "a week high, a week low: Agility and Intelligence ±1 by the week (the GM)"},
            {"a fear", "of the dark, of clean ground or of being touched: a Pain check to face it"}};

    /** One more mental illness for a glowing one (03b §7b, d6; one not held yet) [the list mine, EXAMPLE]. */
    String madness(Random rnd) {
        List<String[]> free = new ArrayList<>();
        for (String[] il : ILLNESSES)
            if (effects.stream().noneMatch(e -> e.name().startsWith("Glowing mind: " + il[0]))) free.add(il);
        if (free.isEmpty()) return "every illness of the list already";
        String[] il = free.get(rnd.nextInt(free.size()));
        add(new Effect("Glowing mind: " + il[0] + " (" + il[1] + ")", "glowing mind", "", true, new int[7], new LinkedHashMap<>(), 0, null, 100));
        return "a mental illness: " + il[0] + " (" + il[1] + ")";
    }

    static final String[] EPISODES = {"freezes (AP 0)", "flees", "attacks the nearest, friend or foe", "shouts and babbles (it gives the party away)",
            "walks to the hottest place near", "is taken by a vision (unconscious)"};

    /**
     * The glowing one's episode of insanity (03b §7b, round 8: *"Episodes of insanity - agreed."*): each game day and at the start of each
     * fight, d100 ≤ 20 − Luck; d6 rounds in a fight, d6 × 10 minutes out of one; d6 what it does. Returns the news, or null.
     */
    public String episode(Random rnd, boolean fight) {
        if (!glowing() || dead) return null;
        int d = 1 + rnd.nextInt(100), luck = attr(Rules.L, true);
        if (!Rules.episode(d, luck)) return null;
        int k = rnd.nextInt(6), len = 1 + rnd.nextInt(6);
        return "AN EPISODE OF INSANITY (d100 " + d + " ≤ 20 − Luck " + luck + "): " + name + " " + EPISODES[k] + " for "
                + (fight ? len + " round" + (len > 1 ? "s" : "") : len * 10 + " minutes");
    }

    /**
     * Damage of a type ({@link #DAMAGE_TYPES} index) after the body's protection: Normal through DT, then DR; the others through their
     * resistance % (the anomalies, 09c §2). Never below 0.
     */
    public int harm(int raw, int type) {
        if (type == 0) return (int) Math.round(Math.max(0, raw - dt()) * (1 - dr()));
        double r = Math.max(-1, Math.min(0.9, resistances()[type] / 100));
        return (int) Math.max(0, Math.round(raw * (1 - r)));
    }

    /**
     * The artifacts on the belt (09c §3, round 9): each in the hotbar is an effect in its own slot while it is there; the black glass's
     * Sneak +60 only while he stands still (Marek 11-23: *"the black glass - make it +60 while standing still."*).
     */
    public void belt(Set<String> names, boolean still) {
        effects.removeIf(e -> e.slot().startsWith("artifact:") && !names.contains(e.slot().substring(9).replace(" (still)", ""))
                || e.slot().equals("artifact:Black glass (still)") && !still);
        for (String n : names) {
            Anomalies.Artifact a = Anomalies.ARTIFACTS.get(n);
            if (a == null || inSlot("artifact:" + n) != null) continue;
            effects.add(new Effect("Artifact: " + n + " (" + a.text() + ")", "artifact", "artifact:" + n, false, a.mods().clone(),
                    new LinkedHashMap<>(a.other()), 1e6, null, 100));
        }
        if (still && names.contains("Black glass") && inSlot("artifact:Black glass (still)") == null)
            effects.add(new Effect("Artifact: the black glass, standing still: Sneak +60", "artifact", "artifact:Black glass (still)", false, new int[7],
                    new LinkedHashMap<>(Map.of("skill:Sneak", 60.0)), 1e6, null, 100));
    }

    /** What a glowing one sheds an hour now (03b §7b, round 9): {@link Rules#GLOW_SHED} of the rads it holds; 0 for anyone else. */
    public double shedRate() { return glowing() && !dead ? survival.getOrDefault(Rules.Stat.RADS, 0.0) * Rules.GLOW_SHED : 0; }

    /**
     * The glowing one's hour (03b §7b, round 9): it sheds {@link #shedRate()} (a radiation source: the rads leave it, onto everyone within
     * 5 blocks), and what it sheds counts toward its healing ({@link Hud#heal}: +1 a 4 rads in the 8 hours). Returns the rads shed.
     */
    public double glowHour() {
        double s = shedRate();
        if (s <= 0) return 0;
        survival.put(Rules.Stat.RADS, Math.max(0, survival.getOrDefault(Rules.Stat.RADS, 0.0) - s));
        glowShed += s;
        return s;
    }

    /**
     * THE CRISIS (03b §7b, round 7): the first hour at 1 000 rads or more, the body's last resort: d100 + (Luck − 5) × 4; above 50 it
     * answers with a FORCED mutation roll expelling 500 rads (outside the yearly roll: {@link #lastMutation} untouched), which takes it out
     * of Fatal Radiation; 50 or less, nothing: Fatal Radiation runs as ruled (RadAway and Stimpaks, the scar, 04 §3.8). Once a stay;
     * below 1 000 the next stay rolls again. Returns the news, or null.
     */
    public String crisis(Random rnd) {
        if (glowing()) { crisisRolled = false; return null; }   // round 9: its rads are its life, held at 1 000 at most
        double held = survival.getOrDefault(Rules.Stat.RADS, 0.0);
        if (held < Rules.CRISIS_RADS) { crisisRolled = false; return null; }
        if (crisisRolled || dead) return null;
        crisisRolled = true;
        int d = 1 + rnd.nextInt(100);
        int score = Rules.crisisScore(d, attr(Rules.L, true));
        String head = "THE CRISIS at " + Rules.CRISIS_RADS + " rads: d100 " + d + " + (Luck − 5) × 4 = " + score;
        if (!Rules.crisisLives(score)) return head + " (50 or less): the body does not answer; Fatal Radiation runs (RadAway, Stimpaks)";
        return head + " (above 50): the body answers with a forced mutation (" + mutate(Rules.CRISIS_EXPEL, rnd) + ")";
    }

    // ------------------------------------------------------------------ clinical death (03b §5)
    /** The clinical death window × (the template's: Coldvein 4, dhampir 2; the undead perk Cold Blood × 2 more). */
    public double deathWindow() {
        return tpl().deathWindow() * (hasPerk("Cold Blood") ? 2 : 1) * (1 + other("death_window")) * (coldBody() ? 2 : 1);
    }

    /** A cold body (03b §5: the cold doubles the window; §3c: so does the cold's arrest): Deadly Hypothermia, 80 % and over [mine]. */
    public boolean coldBody() {
        Double v = survival.get(Rules.Stat.HYPOTHERMIA);
        return v != null && v >= 0.8 * max(Rules.Stat.HYPOTHERMIA) - 1e-9;
    }

    /**
     * An implanted defibrillator (03b §3b, RULED 11-18: T II one automatic shock, T III two, T IV three and the window ×1.5): when
     * clinical death begins it shocks by itself, each shock a revival roll as a defibrillator's with the Medicine of a trained hand
     * (100) in the first minute [the Medicine mine]. Returns the log, or "" without one.
     */
    public String autoDefibrillate(Random rnd) {
        int shocks = (int) Math.round(other("defib_shocks"));
        if (shocks <= 0 || !inArrest()) return "";
        StringBuilder sb = new StringBuilder(" The implanted defibrillator shocks:");
        cues.add("defib");
        for (int i = 1; i <= shocks; i++) {
            double ch = revivalChance(100, Rules.EQUIPMENT.get("defibrillator"));
            int d = 1 + rnd.nextInt(100);
            sb.append(" ").append(i).append(". ").append(Math.round(ch)).append(" %, rolled ").append(d);
            if (d <= ch) { revived(); cues.add("revived"); return sb.append(": the heart beats again (HP ").append(hp).append(").").toString(); }
        }
        return sb.append(": no rhythm.").toString();
    }

    /** Skill points a level: 2 × I + the template's, + the Neural Link's memory (T II +1, III +2, IV +3; 03b §3b). */
    public int skillRate() { return Rules.skillPointsPerLevel(attrs(true), templateSkillPoints) + (int) Math.round(other("skill_points_level", true)); }

    /**
     * XP earned (part 6, `06b`): added, and at each threshold of the table ({@link Rules#xpForLevel}) the level rises and that
     * level's skill points ({@link #skillRate}, read from the permanent values) are added to spend; a perk level is named (the
     * template's rate). Returns what happened ("" if nothing).
     */
    /** Hands-On Learner's skills a session (round 6, Marek 11-20: *"I would make it up to 3 (meaning it can be less)"*). */
    public static final int HANDS_ON_MAX = 3;

    /**
     * Hands-On Learner (02c; round 6, Marek 11-20, verbatim: *"but dying should remove it, so it shuld be a bit lower on average, for
     * PCs. It would be really OP for members. Also, I would make it up to 3 (meaning it can be less)."*): a skill used successfully when
     * it mattered gains +1 skill point, priced as any point (1 a % to 100, 2 to 200, 4 above; a tagged skill twice), at most
     * {@value #HANDS_ON_MAX} skills a session, each once. A PC's own trait: the gains are his and die with him (a new character from his
     * group starts without them); a group cannot take it. {@code session} names the session (the plugin's: the evening's date).
     * Returns what happened ("" without the trait).
     */
    public String handsOn(String skill, String session) {
        if (traits.stream().noneMatch(f -> f.name().equals("Hands-On Learner"))) return "";
        int i = Rules.skillIndex(skill);
        if (i < 0) return "";
        if (!session.equals(handsOnSession)) { handsOnSession = session; handsOnSkills.clear(); }
        if (handsOnSkills.contains(skill)) return "Hands-On Learner: " + skill + " already grew this session";
        if (handsOnSkills.size() >= HANDS_ON_MAX) return "Hands-On Learner: " + HANDS_ON_MAX + " skills already grew this session";
        double gain = (tags.contains(skill) ? 2.0 : 1.0) / Rules.pricePerPercent(skills(true)[i]);
        points[i] += gain;
        handsOnSkills.add(skill);
        return "Hands-On Learner: " + skill + " +" + SheetGui.f(Math.round(gain * 100) / 100.0) + " % (" + handsOnSkills.size() + " of " + HANDS_ON_MAX
                + " this session)";
    }

    public String gainXp(long n) {
        if (n <= 0 || dead) return "";
        xp += n;
        StringBuilder sb = new StringBuilder();
        while (xp >= Rules.xpForLevel(level + 1)) {
            level++;
            int pts = skillRate();
            unspentSkillPoints += pts;
            sb.append(" Level ").append(level).append(": +").append(pts).append(" skill points");
            if (templatePerkRate > 0 && level % templatePerkRate == 0) sb.append(" and a perk");
            sb.append('.');
        }
        return sb.toString().trim();
    }

    /** The game time of this character in hours (his clock + the minutes into the hour). */
    public double now() { return clock + minuteAcc / 60; }

    /** Clinical death: HP below −Endurance (§5), or the heart stopped by a cause of 03b §3c with HP kept. */
    public boolean inArrest() {
        return arrestAt >= 0 && !dead && (!arrestCause.isEmpty() || hp < Rules.deathFloor(attrs(false)));
    }

    /**
     * Repairs a save of an older version (Marek 11-16: *"why am I in clinical death with full HP?"*: 0.9.0 saved "no arrest" as 0,
     * which 0.10.0 read as an arrest at game hour 0), and turns the old example implant into a real one in its slot (*"Eagle Eye
     * example is now outside implant category"*).
     */
    public void migrate() {
        if (arrestCause == null) arrestCause = "";
        if (gas == null) gas = "";
        if (!survival.containsKey(Rules.Stat.OXYGEN)) survival.put(Rules.Stat.OXYGEN, max(Rules.Stat.OXYGEN));   // saves before 0.19.0
        if (arrestAt >= 0 && !dead && arrestCause.isEmpty() && hp >= Rules.deathFloor(attrs(false))) arrestAt = -1;
        if (arrestAt < 0) cprFrom = -1;
        refitImplants();
        effects.removeIf(e -> "ghoul line".equals(e.source()));   // 0.63.0-0.67.0: the stage's numbers are the Necrosis gene's now (09b round 12)
        mutated.removeIf(m -> m == null || m.steps == null || m.steps.isEmpty());   // 0.68.0-0.69.0's named mutations: the catalogue replaced them
        // part 8 round 7 (Marek 11-21: *"Shall I rename it, for example to Flash of Insight? - yes."*): the perk Eureka's new name
        for (ListIterator<Feat> it = perks.listIterator(); it.hasNext(); ) {
            Feat f = it.next();
            if (f.name().equals("Eureka")) it.set(new Feat("Flash of Insight", f.gate(), f.text()));
        }
        for (ListIterator<Effect> it = effects.listIterator(); it.hasNext(); ) {
            Effect e = it.next();
            if (!e.source().isEmpty() || !e.permanent() || !e.name().toLowerCase(Locale.ROOT).contains("implant")) continue;
            String slot = inSlot("implant:eyes#1") == null ? "implant:eyes#1" : inSlot("implant:ears#1") == null ? "implant:ears#1" : null;
            if (slot == null) continue;
            it.set(new Effect(e.name().replace(" implant", "") + " · Tier IV", "implant", slot, true, e.mods(), new LinkedHashMap<>(), 0, null, 100));
        }
    }

    /**
     * The implants of a save before 0.17.0 refitted to their tier's own numbers (03b §3b, RULED 11-18), condition and slot kept; the
     * Memory Chip is gone into the Neural Link (its skill points a level), so a fitted one is taken out; a tier that no longer exists
     * (a Tier II Reflex Chip) moves to the nearest one that does.
     */
    void refitImplants() {
        for (ListIterator<Effect> it = effects.listIterator(); it.hasNext(); ) {
            Effect e = it.next();
            if (!e.source().equals("implant")) continue;
            String base = e.name().replaceAll(" · Tier.*", "").replaceAll(" \\(.*", "");
            if (base.equals("Memory Chip")) { it.remove(); continue; }
            Data.Implant im = Data.IMPLANTS.get(base);
            if (im == null) continue;
            int tier = Math.max(im.minTier(), Math.min(im.maxTier(), Rules.tierOf(e.name())));
            Data.Implant.At at = im.at(tier);
            if (at == null) tier = im.at(im.tiers().ceilingKey(tier)) != null ? im.tiers().ceilingKey(tier) : im.maxTier();
            at = im.at(tier);
            Map<String, Double> other = new LinkedHashMap<>(at.other());
            if (!at.senses().isEmpty()) other.put("senses", (double) Rules.senses(at.senses()));
            it.set(new Effect(im.name() + " · Tier " + Rules.roman(tier), "implant", e.slot(), true, at.mods().clone(), other, 0, null, e.condition()));
        }
    }

    /** The game minutes of the arrest without CPR and with it. */
    public double[] arrestMinutes() {
        if (arrestAt < 0) return new double[]{0, 0};
        double all = Math.max(0, now() - arrestAt) * 60;
        double cpr = cprFrom >= 0 ? Math.max(0, now() - cprFrom) * 60 : 0;
        return new double[]{all - cpr, cpr};
    }

    /** A revival's chance in % now, with the medic's Medicine and this equipment (a vampire's torpor has no time penalty). */
    public double revivalChance(double medicine, double equipment) {
        double[] m = arrestMinutes();
        if (tpl().atDeath().equals("torpor")) return medicine / 2 * equipment;
        return Rules.revivalChance(medicine, equipment, m[0], m[1], deathWindow());
    }

    /** True when the window has run out (the time term at 0) for a body that does not keep (torpor, self-mending). */
    public boolean windowGone() {
        if (!inArrest() || !tpl().atDeath().isEmpty()) return false;
        double[] m = arrestMinutes();
        return 1 - (0.07 * m[0] + 0.03 * m[1]) / Math.max(1e-9, deathWindow()) <= 0;
    }

    /** Back from the arrest: the heart beats, HP at −Endurance (still dying: a doctor, or 8 hours of rest if nothing worsens). */
    public void revived() {
        arrestAt = -1;
        cprFrom = -1;
        arrestCause = "";
        oxygenZeroAt = -1;
        hp = Math.max(hp, Rules.deathFloor(attrs(false)));
    }

    // ------------------------------------------------------------------ 03b §3c: Oxygen and the air (RULED 11-18, rounds 20-21)
    /**
     * The held breath in rounds = Oxygen's maximum (03b §3c): a human 10 (a minute); the best of the genes' (Diver 20, Bajau 30, Diving
     * reflex and Oxygen reserve 50, Anoxia 100) and the implants' (the Lung Filter 30 / 50, by its condition); then trained lungs
     * (Marek 11-18, round 24: *"Athletics should add small boost to oxygen max (swimmers) and maybe endurance as well"*):
     * {@link Rules#breathBonus}, from the permanent values like every other maximum.
     */
    public double breathRounds() {
        double b = Rules.HUMAN_BREATH;
        for (Data.Gene g : tpl().genes()) b = Math.max(b, g.other().getOrDefault("breath", 0.0));
        for (Effect e : effects) {
            Double v = e.other().get("breath");
            if (v != null) b = Math.max(b, Rules.HUMAN_BREATH + (v - Rules.HUMAN_BREATH) * e.strength());
        }
        return Math.max(Rules.MIN_BREATH, b + Rules.breathBonus(skills(true)[Rules.skillIndex("Athletics")], attrs(true)[Rules.E]));
    }

    /**
     * The gas he breathes: the zone his eyes are in (Marek 11-18: effects *"localized to specific areas"*), else the GM's own setting
     * for him (a statblock with no body in the world, a quick test); null = none.
     */
    public Rules.Gas gasHere() {
        if (zoneGas != null && !zoneGas.isEmpty()) return Rules.GASES.get(zoneGas);
        return gas == null || gas.isEmpty() ? null : Rules.GASES.get(gas);
    }

    /** Allergies (Marek 11-19): the immunity genes that carry them (03c: Broad HLA, Strong immunity) set {@code allergy}. */
    public boolean allergic() { return other("allergy") > 0; }

    /** That gas's strength 1-3. */
    public int gasPower() { return zoneGas != null && !zoneGas.isEmpty() ? zoneStrength : gasStrength; }

    /** In a zone, not the GM's per-character gas. */
    public boolean inZone() { return zoneGas != null && !zoneGas.isEmpty(); }

    /** No air to breathe here: a sealed place, or the head under water. */
    public boolean noAir() { return !air || submerged; }

    /**
     * The genes' and implants' factor on a gas (03b §3c): the Blood genes (Highlander ×0.75, Oxygen tuned ×0.5) on CO and sewer gas
     * only; Anoxia ×0.2 on every gas; the Lung Filter T III ×0.5 (not CO), T IV harmless (CO too), by its condition. Never bad air.
     */
    public double gasFactor(Rules.Gas g) {
        if (g == null || g.bad()) return 1;
        double blood = 1, lungs = 1;
        for (Data.Gene x : tpl().genes()) {
            blood = Math.min(blood, x.other().getOrDefault("gas_blood", 1.0));
            lungs = Math.min(lungs, x.other().getOrDefault("gas_lungs", 1.0));
        }
        double f = (g.blood() ? blood : 1) * lungs;
        boolean co = g.name().equals("carbon monoxide");
        for (Effect e : effects) {
            Double v = e.other().get("gas_filter");
            if (v == null || (co && e.other().getOrDefault("co_filter", 0.0) <= 0)) continue;
            f *= 1 - (1 - v) * e.strength();
        }
        return f;
    }

    /**
     * The breath kept from a gas (03b §3c): a held breath, an air bottle with air left (everything), a gas mask with a filter
     * (everything but bad air and CO; a CO filter is its own). Acid fog's burns are on the skin: no mask stops them, a hazard suit
     * does (Marek 11-18).
     */
    public boolean shielded(Rules.Gas g) {
        if (holdingBreath || (wearing.contains("Air bottle") && bottleMinutes > 0)) return true;
        if (g == null || g.bad() || !(wearing.contains("Gas mask") || wearing.contains("Small gas mask"))) return false;
        return filtered(g);
    }

    private boolean filtered(Rules.Gas g) { return g.name().equals("carbon monoxide") ? maskFilters.contains("co") : maskFilters.contains("gas"); }

    /**
     * The EYES kept from a gas (its P, to hit and AP): the big gas mask (a helmet-like piece, Marek 11-18) with the right filter, or
     * the air bottle's face piece; not the small gas mask (mouth and nose) and not a held breath [both mine].
     */
    public boolean eyesShielded(Rules.Gas g) {
        if (wearing.contains("Air bottle") && bottleMinutes > 0) return true;
        return g != null && !g.bad() && wearing.contains("Gas mask") && filtered(g);
    }

    /**
     * The environment hurts him now, so the rounds run (Marek 11-18: *"battle rounds should also be active during taking damage from
     * environment"*): no air or a held breath, or a gas that acts each round and is not kept out. Bad air and mercury act over hours.
     */
    public boolean envHarm() {
        if (dead || inArrest()) return false;
        if (noAir() || holdingBreath) return true;
        if (rainExposed && !wearing.contains("Hazard suit")) return true;   // the Black Rain (round 9)
        if (!anomaly.isEmpty()) {                                            // an anomaly that acts each round (09c §2)
            Anomalies.Kind k = Anomalies.KINDS.get(anomaly);
            if (k != null && (k.hurts() || k.recharge() > 0) && !(anomaly.equals("echo") && deaf())) return true;
        }
        Rules.Gas g = gasHere();
        if (g == null || g.bad() || g.hourlyPoison() > 0) return riotUntil > now() + 1e-9;
        if (g.burnDice() > 0 && !wearing.contains("Hazard suit")) return true;
        boolean lungs = !shielded(g) && (g.oxygen() > 0 || g.poison() > 0 || g.pain() > 0);
        boolean eyes = !eyesShielded(g) && (g.p() != 0 || g.toHit() != 0 || g.ap() != 0);
        boolean co = g.withCo() && !shielded(Rules.GASES.get("carbon monoxide"));
        return lungs || eyes || co;
    }

    /** The gas's P, to-hit and AP now (03b §3c): inside unprotected, × its strength and the factors; riot gas 10 minutes after. */
    public int[] gasPenalty() {
        int[] r = new int[3];
        Rules.Gas g = gasHere();
        boolean riotIn = false;
        if (g != null && !g.bad() && !eyesShielded(g)) {
            double f = gasPower() * gasFactor(g);
            r[0] += Rules.scaleInt(g.p(), f);
            r[1] += Rules.scaleInt(g.toHit(), f);
            r[2] += Rules.scaleInt(g.ap(), f);
            riotIn = g.name().equals("riot gas");
        }
        if (!riotIn && riotUntil > now() + 1e-9 && riotStrength > 0) {
            Rules.Gas riot = Rules.GASES.get("riot gas");
            double f = riotStrength * gasFactor(riot);
            r[0] += Rules.scaleInt(riot.p(), f);
            r[1] += Rules.scaleInt(riot.toHit(), f);
            r[2] += Rules.scaleInt(riot.ap(), f);
        }
        return r;
    }

    /** Poison from a gas: × (1 − the poison resistance), up to the maximum (03b §3c: part 4's Poison value). */
    void addPoison(double v) {
        if (v <= 0) return;
        double res = Math.max(0, Math.min(100, resistances()[2])) / 100;
        double p = survival.getOrDefault(Rules.Stat.POISON, 0.0) + v * (1 - res);
        survival.put(Rules.Stat.POISON, Math.min(max(Rules.Stat.POISON), p));
    }

    /**
     * An hour of the SUN on a body that cannot bear it (03b §2d, RULED for the dhampir, the coldvein and the vampire; a mutation's sun dial,
     * 03b §7a; wired round 15, Marek: *"the sun and melee damage - what is the issue here?"*): {@code pain} (the sun hurts) = Pain 10 for each
     * hour in bright sun [each hour mine]; {@code hp} (the sun burns, protoporphyria) = Pain 5 an hour, and after 2 hours 1 HP an hour.
     * Out of the sun the count starts again. Returns the news, or null.
     */
    public String sunHour() {
        String kind = tpl().sun();
        if (kind.isEmpty() || !inSun || dead) { sunHours = 0; return null; }
        sunHours += 1;
        if (kind.equals("pain")) { addPain(10); return "the sun hurts: Pain 10 (an hour in bright sun)"; }
        addPain(5);
        if (sunHours > 2) { hp -= 1; return "the sun burns: Pain 5 and 1 HP (" + Math.round(sunHours) + " hours in bright sun)"; }
        return "the sun burns: Pain 5 (" + Math.round(sunHours) + " hours in bright sun; from the third, 1 HP an hour too)";
    }

    void addPain(double v) {
        if (v <= 0) return;
        survival.put(Rules.Stat.PAIN, Math.min(100, survival.getOrDefault(Rules.Stat.PAIN, 0.0) + v * painGain()));
    }

    /**
     * One round (6 s) of breathing (03b §3c): Oxygen −1 without air or with a held breath, +5 in clean air, only to half in bad air,
     * the smoke's −1 in smoke; the gas's Poison, Pain and the acid's burns; the air bottle runs down; then the arrest's causes.
     * Returns the news or null.
     */
    public String breathRound(Random rnd) {
        if (dead) return null;
        List<String> out = new ArrayList<>();
        double max = max(Rules.Stat.OXYGEN), o = survival.getOrDefault(Rules.Stat.OXYGEN, max);
        Rules.Gas g = gasHere();
        boolean bottle = wearing.contains("Air bottle") && bottleMinutes > 0;
        if (bottle) {
            bottleMinutes = Math.max(0, bottleMinutes - 0.1);
            if (bottleMinutes <= 1e-9) { wearing.remove("Air bottle"); bottleMinutes = 0; out.add("the air bottle is empty"); }
        }
        boolean arrest = inArrest();
        if (!arrest) {   // in clinical death nobody breathes: Oxygen stays where it is
            if (bottle) o = Math.min(max, o + Rules.OXYGEN_REFILL);
            else if (holdingBreath || noAir()) o -= 1 + other("oxygen_need");   // overloaded: more (round 11)
            else if (g != null && g.bad()) { badAirRounds++; if (o < max / 2) o = Math.min(max / 2, o + Rules.OXYGEN_REFILL); }
            else if (g != null && g.oxygen() > 0 && !shielded(g)) o -= g.oxygen() * gasPower() * gasFactor(g);
            else o = Math.min(max, o + Rules.OXYGEN_REFILL);
            if (g == null || !g.bad() || bottle) badAirRounds = 0;
            o = Math.max(0, o);
            survival.put(Rules.Stat.OXYGEN, o);
            if (o <= 0 && holdingBreath) { holdingBreath = false; out.add("the held breath ends: you gasp"); }   // nobody holds it to the end [mine]
            if (o <= 0) {
                if (oxygenZeroAt < 0) {
                    oxygenZeroAt = now();
                    out.add("UNCONSCIOUS: Oxygen 0 (the heart stops after " + Math.max(1, attrs(false)[Rules.E]) + " minutes without air)");
                }
            } else oxygenZeroAt = -1;
            if (g != null && !g.bad()) {   // the lungs: Poison and Pain
                double f = gasPower() * gasFactor(g);
                if (!shielded(g)) {
                    addPoison(g.poison() * f);
                    addPain(g.pain() * f);
                    if (g.name().equals("carbon monoxide")) coUntil = now() + 24;
                }
                // riot gas works on the eyes: they stay sore 10 minutes after
                if (g.name().equals("riot gas") && !eyesShielded(g)) { riotUntil = now() + Rules.RIOT_LINGER_HOURS; riotStrength = gasPower(); }
                Rules.Gas co = Rules.GASES.get("carbon monoxide");
                if (g.withCo() && !shielded(co)) { addPoison(co.poison() * gasPower() * gasFactor(co)); coUntil = now() + 24; }
            }
        }
        // acid fog: 1d6 a round on bare skin, × the strength; no mask stops it, a hazard suit does (Marek 11-18: "hazard suit should")
        if (g != null && g.burnDice() > 0 && !wearing.contains("Hazard suit")) {
            int n = 0;
            for (int k = 0; k < g.burnDice() * gasPower(); k++) n += 1 + rnd.nextInt(6);
            out.add("the acid burns: " + Hud.hit(this, n, "normal"));
        }
        String a = checkArrest(rnd);
        if (a != null) out.add(a);
        return out.isEmpty() ? null : String.join("; ", out);
    }

    /** An hour in a slow gas (mercury vapour, 03b §3c): Poison +1 × the strength; a week of it gives the tremor until treated. */
    public void gasHour() {
        Rules.Gas g = gasHere();
        if (g == null || g.hourlyPoison() <= 0 || dead || inArrest() || shielded(g)) return;
        addPoison(g.hourlyPoison() * gasPower() * gasFactor(g));
        mercuryHours += 1;
        if (mercuryHours >= Rules.MERCURY_HOURS && inSlot("disease:mercury") == null) {
            int[] mods = new int[7];
            mods[Rules.I] = -1;
            add(new Effect("Mercury poisoning (tremor)", "disease", "disease:mercury", false, mods,
                    new LinkedHashMap<>(Map.of("skill:Crafting", -10.0)), UNTIL_TREATED, null, 100));
            news.add("a week in mercury vapour: I −1 and a tremor (Crafting −10 %) until treated");
        }
    }

    /** Whether rounds change anything now (else time can jump to the next hour): bleeding, the air, a gas, Oxygen not full, the ears' clocks do not need rounds. */
    public boolean roundsMatter() {
        if (dead) return false;
        Double o = survival.get(Rules.Stat.OXYGEN);
        return bleeding > 0 || noAir() || holdingBreath || gasHere() != null || (wearing.contains("Air bottle") && bottleMinutes > 0)
                || (o != null && o < max(Rules.Stat.OXYGEN) - 1e-9) || riotUntil > now();
    }

    /** Unconscious, not in clinical death: Oxygen 0, or a pump keeping the body alive at Endurance 0 or the cold's end (03b §3c). */
    public boolean unconscious() {
        if (dead || inArrest()) return false;
        Double o = survival.get(Rules.Stat.OXYGEN);
        if (o != null && o <= 0) return true;
        return hasPump() && (attrs(false)[Rules.E] <= 0 || atEnd(Rules.Stat.HYPOTHERMIA));
    }

    private boolean atEnd(Rules.Stat st) {
        Double v = survival.get(st);
        return v != null && v >= max(st) - 1e-9;
    }

    // ------------------------------------------------------------------ 03b §3c: the heart
    /** A pump in the chest (the Artificial Heart, any tier that works): no rhythm to lose (Marek, round 20: *"I agree"*). */
    public boolean hasPump() {
        for (Effect e : effects) if (e.other().getOrDefault("pump", 0.0) > 0 && e.strength() > 0) return true;
        return false;
    }

    /**
     * The heart stops while HP holds (03b §3c, RULED): clinical death at once (§5), HP kept. {@code rhythm}: a cause a pump ignores
     * (electricity, a chest blow, Endurance 0, the cold). An implanted defibrillator shocks only when the cause no longer lasts.
     */
    public String stopHeart(String cause, boolean rhythm, Random rnd) {
        if (dead || inArrest()) return "";
        if (rhythm && hasPump()) return "the Artificial Heart pumps on through " + cause + " (a pump has no rhythm to lose)";
        arrestAt = now();
        arrestCause = cause;
        cues.add("heart.stop");
        cprFrom = -1;
        String block = arrestBlock();
        String d = block == null ? autoDefibrillate(rnd) : other("defib_shocks") > 0 ? " The implanted defibrillator cannot help while it lasts (" + block + ")." : "";
        return "CARDIAC ARREST (" + cause + "): clinical death with HP kept" + d;
    }

    /** What still stops the heart, so a revival cannot hold yet (03b §3c: *"the cause dealt with first where it lasts"*); null = none. */
    public String arrestBlock() {
        if (noAir()) return "no air: out of the water or the sealed place first";
        if (survival.getOrDefault(Rules.Stat.BLOOD, 1.0) <= 0) return "Blood 0: a transfusion first";
        if (atEnd(Rules.Stat.HYPOTHERMIA)) return "the cold at its end: warm him first";
        if (atEnd(Rules.Stat.HYPERTHERMIA)) return "the heat at its end: cool him first";
        if (attrs(false)[Rules.E] <= 0 && !hasPump()) return "Endurance 0: raise it first";
        return null;
    }

    /**
     * The causes a value reaching its end sets off (03b §3c, RULED): Oxygen 0 for E minutes, Blood 0, Endurance 0, the cold's or the
     * heat's end. A pump ignores the rhythm causes (the body stays unconscious instead). Returns the news or null.
     */
    public String checkArrest(Random rnd) {
        if (dead || inArrest()) return null;
        int e = attrs(false)[Rules.E];
        Double o = survival.get(Rules.Stat.OXYGEN);
        if (o != null && o <= 0 && oxygenZeroAt >= 0 && now() - oxygenZeroAt >= Math.max(1, e) / 60.0 - 1e-9)
            return stopHeart("Oxygen 0 for " + Math.max(1, e) + " minutes", false, rnd);
        if (survival.getOrDefault(Rules.Stat.BLOOD, 1.0) <= 0) return stopHeart("Blood 0", false, rnd);
        if (atEnd(Rules.Stat.HYPERTHERMIA)) return stopHeart("the heat", false, rnd);
        if (hasPump()) return null;
        if (atEnd(Rules.Stat.HYPOTHERMIA)) return stopHeart("the cold", true, rnd);
        if (e <= 0) return stopHeart("Endurance 0", true, rnd);
        return null;
    }

    /**
     * An electric hazard (a live cable, a lightning strike, the lightning anomaly; 03b §3c): its damage, and when the hit reaches 10 an
     * Endurance roll (d10 at most E) or the heart stops. A hazard rolls no crits.
     */
    public String electricHazard(int dmg, Random rnd) {
        cues.add("electric");
        String r = Hud.hit(this, dmg, "normal");
        if (dmg < Rules.ELECTRIC_HAZARD || dead || inArrest()) return r;
        int d = 1 + rnd.nextInt(10), en = attrs(false)[Rules.E];
        if (Rules.attributeRoll(en, d)) return r + "; the Endurance roll " + d + " ≤ " + en + ": the heart holds";
        return r + "; the Endurance roll " + d + " > " + en + ": " + stopHeart("electricity", true, rnd);
    }

    // ------------------------------------------------------------------ 03b §3c: the ears (RULED round 21)
    /** Never deafened: an Audio Implant of Tier III-IV that works (it sends sound past the eardrum). */
    public boolean deafProof() {
        for (Effect e : effects) if (e.other().getOrDefault("deaf_proof", 0.0) > 0 && e.strength() > 0) return true;
        return false;
    }

    private boolean audioImplant() {
        for (Effect e : implants()) if (e.name().startsWith("Audio Implant")) return true;
        return false;
    }

    /** 0 none, 1 earplugs or muffs (P −1 while worn), 2 electronic muffs (Tier IV: no −1). */
    public int earProtection() { return wearing.contains("Electronic muffs") ? 2 : wearing.contains("Earplugs") ? 1 : 0; }

    public int burstEars() {
        int n = 0;
        for (double h : eardrumHealsAt) if (h > now() + 1e-9) n++;
        return n;
    }

    public boolean ringing() { return ringingUntil > now() + 1e-9; }

    public boolean deaf() { return burstEars() >= 2 && !deafProof(); }

    /**
     * Perception the ears cost (03b §3c, RULED round 21, as Perception, not by ear): ringing −1, one burst eardrum −1, both (deaf)
     * −2, the lasting loss −1 until an Audio Implant, earplugs or muffs −1 while worn; the worst of them counts [mine: one organ].
     */
    public int hearingLoss() {
        if (deafProof()) return 0;
        int l = Math.max(ringing() ? 1 : 0, burstEars());
        if (lastingEarLoss && !audioImplant()) l = Math.max(l, 1);
        if (earProtection() == 1) l = Math.max(l, 1);
        return l;
    }

    /** Tough eardrums (03c: "burst at twice the blast, the ringing halved"): the eardrum roll and the ringing × 0.5. */
    double earFactor() {
        double f = 1;
        for (Data.Gene g : tpl().genes()) f = Math.min(f, g.other().getOrDefault("ears_tough", 1.0));
        return f;
    }

    /** Ringing for these hours (P −1), unless the ears are protected or never deafened; returns the news or "". */
    public String ring(double hours) {
        if (deafProof() || earProtection() > 0 || hours <= 0) return "";
        double h = hours * earFactor();
        ringingUntil = Math.max(ringingUntil, now() + h);
        return "ringing ears for " + SheetView.hours(h) + " (P −1)";
    }

    /**
     * An eardrum roll for each whole ear at {@code chance} % (halved by earplugs or muffs, and by Tough eardrums): a burst eardrum =
     * P −1, Pain 10, no direction to a sound, healed in 1d4 + 2 weeks, 30 % keep a lasting P −1; both = deaf (P −2). Returns news or "".
     */
    public String eardrumRoll(int chance, Random rnd) {
        if (deafProof() || chance <= 0) return "";
        double ch = chance * (earProtection() > 0 ? 0.5 : 1) * earFactor();
        List<String> out = new ArrayList<>();
        for (int k = 0; k < 2; k++) { String s = burst(k, ch, rnd); if (s != null) out.add(s); }
        return earNews(out);
    }

    /** One ear only (a dive below 3 m without a Breath gene, 10 %; 03b §3c): a whole ear, rolled once. */
    public String oneEarRoll(int chance, Random rnd) {
        if (deafProof() || chance <= 0) return "";
        List<Integer> whole = new ArrayList<>();
        for (int k = 0; k < 2; k++) if (eardrumHealsAt[k] <= now()) whole.add(k);
        if (whole.isEmpty()) return "";
        String s = burst(whole.get(rnd.nextInt(whole.size())), chance * earFactor(), rnd);
        return s == null ? "" : earNews(List.of(s));
    }

    private String burst(int k, double ch, Random rnd) {
        if (eardrumHealsAt[k] > now()) return null;
        int d = 1 + rnd.nextInt(100);
        if (d > ch) return null;
        eardrumHealsAt[k] = now() + Rules.eardrumHealHours(1 + rnd.nextInt(4));
        addPain(10);
        boolean lasting = 1 + rnd.nextInt(100) <= Rules.LASTING_EAR_LOSS;
        if (lasting) lastingEarLoss = true;
        return (k == 0 ? "the left" : "the right") + " eardrum bursts (" + Math.round(ch) + " %, rolled " + d + "; heals in "
                + SheetView.hours(eardrumHealsAt[k] - now()) + (lasting ? ", a lasting P −1 after" : "") + ")";
    }

    private String earNews(List<String> out) {
        if (out.isEmpty()) return "";
        return String.join(", ", out) + (deaf() ? ": DEAF (P −2, no radio, no spoken orders, lip-reading)" : ": P −1, no direction to a sound, Pain +10");
    }

    /** A Breath gene (Diver, Bajau, Diving reflex, Anoxia, Cold blood): a dive does not hurt the ears (03b §3c). */
    public boolean breathGene() {
        for (Data.Gene g : tpl().genes()) if (g.slot().equals("Breath")) return true;
        return false;
    }

    /** A lasting loss at {@code chance} % (meningitis or a streptomycin-type course: 5 %, 03b §3c). */
    public String lastingRoll(int chance, Random rnd) {
        if (deafProof()) return "never deafened (the Audio Implant)";
        int d = 1 + rnd.nextInt(100);
        if (d > chance) return "the hearing holds (" + chance + " %, rolled " + d + ")";
        lastingEarLoss = true;
        return "one ear's hearing is lost for good (" + chance + " %, rolled " + d + "): P −1 until an Audio Implant";
    }

    /** The ears healed (the GM's): no ringing, both eardrums whole, no lasting loss. */
    public void healEars() { ringingUntil = -1; eardrumHealsAt[0] = eardrumHealsAt[1] = -1; lastingEarLoss = false; }

    /** A blast at {@code dist} from a burst of {@code radius} (03b §3c): the eardrum roll and the ringing (1d6 hours). */
    public String blast(double dist, double radius, Random rnd) {
        int[] e = Rules.blastEars(dist, radius);
        return ears(e, 1 + rnd.nextInt(6), rnd);
    }

    /** A flashbang at {@code dist} hexes (03b §3c): ringing (1d6 hours) within 5, an eardrum roll at 15 % within 2. */
    public String flashbang(double dist, Random rnd) { return ears(Rules.flashbangEars(dist), 1 + rnd.nextInt(6), rnd); }

    private String ears(int[] e, int ringHours, Random rnd) {
        List<String> out = new ArrayList<>();
        String b = eardrumRoll(e[0], rnd);
        if (!b.isEmpty()) out.add(b);
        if (e[1] > 0) { String r = ring(ringHours); if (!r.isEmpty()) out.add(r); }
        return String.join("; ", out);
    }

    // ------------------------------------------------------------------ armour (05c-ARMOUR.md; 05 §6b RULED 11-18)
    /** A piece worn: its set (`data/armour.json`), its slot (Head, Chest, Legs, Feet) and its condition 0-100 %. */
    /** A worn piece: its set, slot, condition and the body it is made for (05c §9.5; null in old saves = standard). */
    public record Worn(String set, String slot, double condition, String fit) {
        public Worn(String set, String slot, double condition) { this(set, slot, condition, STANDARD); }
        public String fitName() { return fit == null ? STANDARD : fit; }
        public Worn at(double newCondition) { return new Worn(set, slot, newCondition, fit); }
    }

    // ------------------------------------------------------------------ body fits (05c §9.5, RULED in principle 11-18; the table mine)
    public static final String STANDARD = "standard";
    /** The templates whose body is not the human shape; the Laron gene makes any body small. */
    public static final Map<String, String> TEMPLATE_FIT = Map.of("Orc", "large", "Catfolk", "tailed", "Pikachu", "tailed", "Ent", "huge",
            "Fallen Angel", "winged");
    /** The slots where a body differs from the human shape (a piece of another fit is shared only in the other slots). */
    public static final Map<String, Set<String>> FIT_SLOTS = Map.of(STANDARD, Set.of(), "large", Set.of("Chest", "Legs"),
            "small", Set.of("Head", "Chest", "Legs", "Feet"), "tailed", Set.of("Head", "Legs"), "winged", Set.of("Chest"),
            "huge", Set.of("Head", "Chest", "Legs", "Feet"));

    public String fit() {
        for (Data.Gene g : tpl().genes()) if (g.name().equals("Laron")) return "small";
        return TEMPLATE_FIT.getOrDefault(template, STANDARD);
    }

    /** A piece made for one body fits another when they are the same, or the slot is the human shape's in both. */
    public static boolean fits(String pieceFit, String bodyFit, String slot) {
        String p = pieceFit == null ? STANDARD : pieceFit, b = bodyFit == null ? STANDARD : bodyFit;
        return p.equals(b) || (!FIT_SLOTS.getOrDefault(p, Set.of()).contains(slot) && !FIT_SLOTS.getOrDefault(b, Set.of()).contains(slot));
    }

    public boolean fits(Worn w) { return w != null && fits(w.fit(), fit(), w.slot()); }

    /** Crafting needed to make or refit a piece: 25 a tier [EXAMPLE]. */
    public static int craftingFor(int tier) { return 25 * Math.max(1, tier); }

    /**
     * Refitting a worn piece for this body (05c §9.5): a Crafting job at the piece's tier (25 a tier), an hour a kg of the piece
     * [EXAMPLE]; a huge body's piece cannot be refitted (a new one is made). Returns what happened.
     */
    public String refit(String slot) {
        Worn w = armour.get(slot);
        Data.Armour a = w == null ? null : Data.ARMOUR.get(w.set());
        if (a == null) return "nothing worn on the " + slot.toLowerCase(Locale.ROOT);
        if (fits(w)) return "the " + w.set() + " fits you already";
        if (w.fitName().equals("huge") || fit().equals("huge")) return "a huge body's piece cannot be refitted: a new one must be made";
        int need = craftingFor(a.tier());
        if (skill("Crafting") < need) return "refitting the " + w.set() + " needs Crafting " + need;
        armour.put(slot, new Worn(w.set(), slot, w.condition(), fit()));
        return "the " + w.set() + " (" + slot.toLowerCase(Locale.ROOT) + ") refitted for your body: " + (int) Math.ceil(a.pieceWeight(slot))
                + " h of work (the GM passes them)";
    }


    /** What each slot wears (a player's: read from his equipment each second; the items keep the condition). */
    public final Map<String, Worn> armour = new LinkedHashMap<>();

    /** Power Armour Training (05c §3c, RULED 11-18): the hours worn whole with Engineering over 50 (100 teach it), or taught (10 = 100). */
    public double paHours = 0;
    public boolean paTrained = false;

    /** Unlocked from the start, the base can make it: Tier I and II at rarity 1 (RULED 11-18: "at least all T2 with R1"); the rest: {@link Base}. */
    public static boolean knownAtStart(Data.Armour a) { return a.tier() <= 2 && a.rarity() == 1; }

    public static final List<String> ARMOUR_SLOTS = List.of("Head", "Chest", "Legs", "Feet");

    /** The slot over a body part (05 §6b, RULED 11-18: the chest covers the arms and the hands). */
    public static String slotOf(Rules.Part part) {
        return switch (part) {
            case HEAD, EYES -> "Head";
            case TORSO, LEFT_ARM, RIGHT_ARM -> "Chest";
            case GROIN, LEFT_LEG, RIGHT_LEG -> "Legs";
        };
    }

    /**
     * New Vegas's armour by condition (the Fallout Wiki, *Equipment condition*): full protection at 50 % and above, two thirds at 0:
     * × (0.66 + min(0.34 × condition / 0.5, 0.34)).
     */
    public static double conditionShare(double conditionPct) { return 0.66 + Math.min(0.34 * (conditionPct / 100) / 0.5, 0.34); }

    /** The piece's set, or null when nothing is worn or it does not fit this body (a misfit gives nothing but its weight, 05c §9.5). */
    private Data.Armour setOf(Worn w) { return w == null || !fits(w) ? null : Data.ARMOUR.get(w.set()); }

    /** The piece over a body part, or null. */
    public Worn armourAt(Rules.Part part) { return armour.get(slotOf(part)); }

    /** Every piece of the set worn: its own effects count (the power armours' Strength, a sealed suit). */
    public boolean complete(String set) {
        Data.Armour a = Data.ARMOUR.get(set);
        if (a == null) return false;
        for (String s : a.pieces()) { Worn w = armour.get(s); if (w == null || !w.set().equals(set) || !fits(w)) return false; }
        return true;
    }

    private List<Data.Armour> completeSets() {
        List<Data.Armour> l = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Worn w : armour.values()) if (seen.add(w.set()) && complete(w.set())) l.add(Data.ARMOUR.get(w.set()));
        return l;
    }

    /** The armour's AC: each piece's share of its set's (Fallout's AC adds to the wearer's; condition does not touch it [mine]). */
    public int armourAc() {
        int s = 0;
        for (Worn w : armour.values()) { Data.Armour a = setOf(w); if (a != null) s += a.pieceAc(w.slot()); }
        return s;
    }

    /** DT against a hit on this part of this type: the body's (Normal only: the template and the effects) + the piece's by its condition. */
    public int dtAt(Rules.Part part, String type) {
        Worn w = armourAt(part);
        Data.Armour a = setOf(w);
        return (type.equals("Normal") ? dt() : 0) + (a == null ? 0 : (int) Math.round(a.dt(type) * conditionShare(w.condition())));
    }

    /** DR (a fraction) against a hit on this part of this type: the body's resistance + the piece's by its condition, at most 90 %. */
    public double drAt(Rules.Part part, String type) {
        int i = Arrays.asList(DAMAGE_TYPES).indexOf(type);
        double body = i < 0 ? 0 : resistances()[i];
        Worn w = armourAt(part);
        Data.Armour a = setOf(w);
        double piece = a == null ? 0 : a.dr(type) * conditionShare(w.condition());
        return Math.max(-1, Math.min(0.9, (body + piece) / 100));
    }

    /** A whole set's attribute changes (the power armours' Strength): temporary, never read by level-up. */
    int armourMod(int i) {
        int s = 0;
        for (Data.Armour a : completeSets()) if (runs(a)) s += a.mods()[i];   // untrained, a power armour is dead weight (05c §3c)
        return s;
    }

    double armourRes(boolean rad) {
        double s = 0;
        for (Data.Armour a : completeSets()) s += rad ? a.radRes() : a.poisonRes();
        return s;
    }

    double armourSkill(String skill) {
        double s = 0;
        for (Data.Armour a : completeSets()) s += a.skills().getOrDefault(skill, 0.0);
        if (skill.equals("Sneak")) s += CLASS_SNEAK[armourClass()];   // the class (05c §3b)
        return s;
    }

    // ------------------------------------------------------------------ the class, the training, the outfit (05c §3b, §3c, §6)
    public static final String[] CLASS_NAMES = {"light", "medium", "heavy", "super-heavy"};
    /** New Vegas's three classes and a fourth, as penalties (Marek 11-18: *"just make the effect a panality"*) [EXAMPLE, mine]. */
    public static final double[] CLASS_KG = {6, 13, 25};
    public static final int[] CLASS_SNEAK = {0, -10, -25, -40}, CLASS_AP = {0, 0, -1, -2};
    public static final double PA_SELF_HOURS = 100, PA_TAUGHT_HOURS = 10, PA_ENGINEERING = 50;

    /** A set's power runs: anything but power armour, or power armour with the training (untrained it is dead weight). */
    public boolean runs(Data.Armour a) { return !a.powerArmour() || paTrained; }

    /** The armour's worn weight in kg; a power armour that runs counts half (its servos carry it). */
    public double wornKg() {
        double kg = 0;
        for (Worn w : armour.values()) {
            Data.Armour a = Data.ARMOUR.get(w.set());   // a misfit piece still weighs
            if (a != null) kg += a.weightKg() * a.shares().getOrDefault(w.slot(), 0.0) * (a.powerArmour() && runs(a) && fits(w) ? 0.5 : 1);
        }
        return kg;
    }

    /** 0 light (to 6 kg), 1 medium (to 13), 2 heavy (to 25), 3 super-heavy. */
    public int armourClass() {
        double kg = wornKg();
        int k = 0;
        while (k < CLASS_KG.length && kg > CLASS_KG[k] + 1e-9) k++;
        return k;
    }

    /** A whole power armour worn an hour with Engineering over 50 (100 such hours teach it). Returns the news, or null. */
    public String paHour() {
        if (paTrained || completeSets().stream().noneMatch(Data.Armour::powerArmour) || skill("Engineering") <= PA_ENGINEERING) return null;
        paHours += 1;
        if (paHours < PA_SELF_HOURS) return null;
        paTrained = true;
        return "Power Armour Training learned (" + Math.round(paHours) + " hours in the suit)";
    }

    /** Taught by anyone trained: 10 hours teach it [EXAMPLE], so an hour taught counts as 10 worn. */
    public String paTaught(double hours) {
        if (paTrained) return "already trained";
        paHours += hours * PA_SELF_HOURS / PA_TAUGHT_HOURS;
        if (paHours >= PA_SELF_HOURS) { paTrained = true; return "Power Armour Training learned"; }
        return "Power Armour Training " + Math.round(paHours) + " of " + Math.round(PA_SELF_HOURS) + " hours";
    }

    public String paText() {
        return paTrained ? "trained" : Math.round(paHours) + " of " + Math.round(PA_SELF_HOURS) + " h (a whole suit worn with Engineering over 50, or taught: 10 h)";
    }

    public static final Map<String, Double> SLOT_SHARE = Map.of("Head", 0.15, "Chest", 0.5, "Legs", 0.25, "Feet", 0.10);
    public static final double CLOTHES_ISOLATION_DT = 6, SHOES_ISOLATION_DR = 25;

    /**
     * The outfit (05c §6): each slot's Isolation and Heat at its condition, or ordinary clothes (Isolation DT 6; shoes on the feet
     * Isolation DR 25), weighted Head 15 %, Chest 50, Legs 25, Feet 10. Returns {Isolation DT, Isolation DR %, Heat DT, Heat DR %}.
     */
    public double[] outfit() {
        double[] o = new double[4];
        for (String slot : ARMOUR_SLOTS) {
            double sh = SLOT_SHARE.get(slot);
            Worn w = armour.get(slot);
            Data.Armour a = setOf(w);
            if (a == null) { o[0] += sh * CLOTHES_ISOLATION_DT; if (slot.equals("Feet")) o[1] += sh * SHOES_ISOLATION_DR; continue; }
            double c = conditionShare(w.condition());
            o[0] += sh * a.dt("Isolation") * c;
            o[1] += sh * a.dr("Isolation") * c;
            o[2] += sh * a.dt("Heat") * c;
            o[3] += sh * a.dr("Heat") * c;
        }
        return o;
    }

    /** A cooled set worn whole whose power runs (the power armours, the EOD suit's fan): isolation does not stop it venting. */
    public boolean cooled() { return completeSets().stream().anyMatch(a -> a.cooled() && runs(a)); }

    /** The comfort interval in °C (05c §6; part 4's naked human 16-31): 16 − Isolation DT to 31 − Isolation DT + Heat DT (cooled: 31 + Heat DT). */
    public int[] comfort() {
        double[] o = outfit();
        return new int[]{(int) Math.round(16 - o[0]), (int) Math.round(31 + o[2] - (cooled() ? 0 : o[0]))};
    }

    /** Trench foot's water resistance (04b §3c, RULED: the boots' Isolation): the Feet piece's Isolation DR; a sealed suit 1; shoes 0.25. */
    public double bootsWater() {
        Worn w = armour.get("Feet");
        Data.Armour a = setOf(w);
        if (a == null) return SHOES_ISOLATION_DR / 100;
        if (a.sealed() && complete(a.name())) return 1;
        return Math.max(0, Math.min(1, a.dr("Isolation") / 100));
    }

    /**
     * A hit's wear on the piece over the part (New Vegas: armour wears only when a hit's power exceeds its DT; how much is not
     * published): what the piece's DR stopped beyond its DT, (raw − DT) × DR, in item health (Marek 11-18: *"make it work with DR as well"*), the set's item health shared by its pieces [EXAMPLE, mine].
     * Returns the note, or "".
     */
    public String wearArmour(Rules.Part part, double raw) { return wearArmour(part, raw, "Normal"); }

    public String wearArmour(Rules.Part part, double raw, String type) {
        Worn w = armourAt(part);
        Data.Armour a = setOf(w);
        if (a == null) return "";
        double share = conditionShare(w.condition()), dt = a.dt(type) * share, dr = Math.max(0, a.dr(type)) / 100 * share;
        if (raw <= dt || dr <= 0) return "";
        double loss = (raw - dt) * dr / a.pieceHealth(w.slot()) * 100;
        double c = Math.max(0, w.condition() - loss);
        armour.put(w.slot(), w.at(c));
        return " Your " + w.set() + " (" + w.slot().toLowerCase(Locale.ROOT) + "): " + Math.round(w.condition()) + " → " + Math.round(c) + " %.";
    }

    /**
     * What is worn, read from the equipment (a player each second): the slots, then the gear the gas rules read: the big gas mask
     * on the head (with the filters carried), a sealed suit (the hazard suit; Tactics' environmental armour with its own gas filter
     * [mine]) keeping the acid off.
     */
    public void setArmour(Map<String, Worn> worn, boolean gasFilter, boolean coFilter) {
        armour.clear();
        armour.putAll(worn);
        boolean small = wearing.contains("Small gas mask");
        wearing.remove("Gas mask");
        wearing.remove("Hazard suit");
        Worn head = armour.get("Head");
        boolean mask = head != null && head.set().equals("Gas mask");
        for (Data.Armour a : completeSets()) {
            if (a.sealed()) wearing.add("Hazard suit");
            if (a.gasDr() > 0) { mask = true; gasFilter = true; }
        }
        if (mask) {
            wearing.add("Gas mask");
            if (!small) {
                maskFilters.clear();
                if (gasFilter) maskFilters.add("gas");
                if (coFilter) maskFilters.add("co");
            }
        }
    }

    // ------------------------------------------------------------------ the synthetic body (Marek 11-16)
    /**
     * Robots and androids (Marek 11-16: *"they still need energy in form of electricity. And they should still clean their memory
     * from time to time."*): food is their POWER (recharged at a charging point), sleep is their MEMORY (cleaned like sleep), no water.
     */
    public boolean synthetic() { return "power".equals(tpl().needs()); }

    public String statName(Rules.Stat st) {
        if (synthetic()) {
            if (st == Rules.Stat.FOOD) return "Power";
            if (st == Rules.Stat.SLEEP) return "Memory";
        }
        return SheetGui.name(st);
    }

    /** A stage as this body names it (the effects stay the stage's own). */
    public String label(String stage) {
        if (stage == null || !synthetic()) return stage;
        return stage.replace("Starvation", "Power Loss").replace("Sleep Deprivation", "Memory Clutter");
    }

    /** A plain character of a template, its points spread legally (03b §1), fed and rested: an NPC figurant's start. */
    public static Character blank(String name, String template) {
        Character c = new Character();
        c.name = name;
        c.applyTemplate(template);
        Data.Template t = c.tpl();
        int left = t.attributePoints();
        for (int i = 0; i < 7; i++) { c.base[i] = t.fixedAttr(i) ? 0 : Math.max(1, 1 - t.mods()[i]); left -= c.base[i]; }
        for (int guard = 0; left > 0 && guard < 200; guard++) {
            int i = guard % 7;
            if (!t.fixedAttr(i) && c.base[i] < 10) { c.base[i]++; left--; }
        }
        c.hp = c.hpMax();
        for (Rules.Stat st : Rules.Stat.values())
            c.survival.put(st, switch (st) { case POISON, RADS, HYPOTHERMIA, HYPERTHERMIA, PAIN -> 0.0; default -> c.max(st); });
        return c;
    }

    // ------------------------------------------------------------------ the example
    /** The EXAMPLE character: a human with two traits, three tags, an implant and a hangover, survival as the workbook's sample. */
    public static Character sample(String player) {
        Character c = new Character();
        c.player = player;
        c.applyTemplate("Human");
        c.fit(Data.IMPLANTS.get("Audio Implant"), 4, null);
        c.effects.add(new Effect("Hangover (example)", false, new int[]{0, -1, 0, -1, 0, 0, 0}, 3));
        c.traits.add(new Feat("Brittle Bones", "trait (Wasteland 2)", "+2 AP; movement costs double the AP (Marek)"));
        c.traits.add(new Feat("Medical Marvel", "trait (Wasteland 3)", "+5 HP; nobody can stabilise you (no Field Surgeon, no first aid when dying)"));
        c.tags.addAll(List.of("Guns", "Medicine", "Sneak"));
        c.points[Rules.skillIndex("Guns")] = 30;
        c.points[Rules.skillIndex("Medicine")] = 25;
        c.points[Rules.skillIndex("Sneak")] = 12;
        c.unspentSkillPoints = 19;
        c.xp = 120;   // level 1 on the PCs' table (level 2 at 250)
        c.hp = c.hpMax();
        c.survival.put(Rules.Stat.WATER, 0.85 * c.max(Rules.Stat.WATER));
        c.survival.put(Rules.Stat.FOOD, c.max(Rules.Stat.FOOD));
        c.survival.put(Rules.Stat.SLEEP, c.max(Rules.Stat.SLEEP));
        c.survival.put(Rules.Stat.POISON, 0.0);
        c.survival.put(Rules.Stat.RADS, 50.0);
        c.survival.put(Rules.Stat.HYPOTHERMIA, 0.0);
        c.survival.put(Rules.Stat.HYPERTHERMIA, 4.0);
        c.survival.put(Rules.Stat.BLOOD, c.max(Rules.Stat.BLOOD));
        c.survival.put(Rules.Stat.PAIN, 0.0);
        c.survival.put(Rules.Stat.OXYGEN, c.max(Rules.Stat.OXYGEN));
        return c;
    }

    /** Perks shown on the sample's perk page (a few of `02c` §2, with their gates). */
    public static final List<Feat> SAMPLE_PERKS = List.of(
            new Feat("Swift Learner", "any skill 50", "+15 % XP"),
            new Feat("Awareness", "Survival or Guns 50", "you see the HP, armour, weapon and ammo of anyone you see"),
            new Feat("Toughness", "Athletics or Survival 75", "+10 % DR and +2 DT against normal damage"),
            new Feat("Tag!", "any skill 100", "a fourth tagged skill"),
            new Feat("Educated", "any skill 100", "+4 skill points a level"),
            new Feat("Medic!", "Medicine 50", "healing an ally takes half the AP and heals x1.5"),
            new Feat("Living Anatomy", "Medicine 100", "+5 damage a hit against the living, +15 to aimed shots at body parts"),
            new Feat("Field Surgeon", "Medicine 150", "brings a dying ally (not dead) back to 1 HP for 4 AP, once per ally a battle"),
            new Feat("Quick Draw", "Guns 50", "reloading and switching weapons cost half the AP"),
            new Feat("Sniper", "Guns 100", "aimed shots +25 to hit, no range penalty within the weapon's range"),
            new Feat("Bonus Move", "Athletics 50", "+3 AP of movement only, each turn"),
            new Feat("Cautious Nature", "Survival 50", "never surprised: you always act in the first round"),
            new Feat("Quick Pockets", "Athletics 50", "taking an item from your pack costs 1 AP less"),
            new Feat("Run 'n Gun", "Guns 75", "no penalty for shooting after moving"),
            new Feat("Tunnel Rat", "Sneak 75", "crawling costs no more AP than walking"));

    /** Whether a perk's gate is met: reads "Skill N" or "A or B N" or "any skill N" (permanent values). */
    public boolean meets(Feat f) {
        String g = f.gate();
        String[] parts = g.split(" ");
        double need;
        try { need = Double.parseDouble(parts[parts.length - 1]); } catch (NumberFormatException e) { return false; }
        double[] v = skills(true);   // gates read permanent values (Marek 11-14)
        if (g.startsWith("any skill")) { for (double x : v) if (x >= need) return true; return false; }
        for (String s : g.substring(0, g.lastIndexOf(' ')).split(" or "))
            try { if (v[Rules.skillIndex(s.trim())] >= need) return true; } catch (IllegalArgumentException ignored) {}
        return false;
    }
}
