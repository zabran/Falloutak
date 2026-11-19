package cz.lollywood.falloutak;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * Part 5: the workbook's {@code Battle} tab as rules (LollyWood-Lore {@code _notes/Fallout/05-BATTLE.md}), checked term by term
 * against Fallout 2's own code ({@code fallout2-ce/src/combat.cc}). The sheet's four bugs are fixed as F2 does it (the distance
 * cap, the blind bonus, the critical failures that never fired, the damage roll's max + 1); the knockdown is read with its
 * constant outside the {@code 3 x degree} product, as the knockout beside it; every deliberate change of Marek's is kept as the
 * sheet has it. Pure Java: no Bukkit, so the tests can run it.
 */
public final class Combat {
    private Combat() {}

    // ------------------------------------------------------------------ the chance to hit
    /** Everything the chance to hit is made of (the sheet's M22). */
    public static final class Shot {
        public double skill;
        public int blocks;                 // the distance, whole blocks (F2's hexes)
        public int perception, strength, minStrength;
        public String weaponPerk = "";     // "Accurate", "Long Range", "Scope Range"
        public boolean melee, blind, aimed, huge, targetDown, targetProne;
        public int lightLevel = 15;        // Minecraft's 0-15 at the target
        public int targetAc;
        public Rules.Part part = Rules.Part.TORSO;
        public int obstacles;              // creatures in the line of fire
        public int stance;                 // 0 standing, 1 crouched, 2 prone
        public int movePenalty;            // RULED 11-14: -10, -20 two-handed, 0 once steadied
        public int range;                  // the weapon's, blocks
        public int senses;                 // part 3: 0 none, 1 the dark halved, 2 night vision (the shooter's)
        /** Part 3's own terms (a prosthetic arm, targeting eyes), by name. */
        public final Map<String, Integer> extra = new LinkedHashMap<>();
    }

    /** The sheet's bonus against a target knocked down or out (its T20), RULED back 11-14 over F1/F2's +40. */
    public static final int TARGET_DOWN = 10;

    /** The chance and its terms in order, for the log (only the non-zero ones). */
    public record ToHit(int chance, Map<String, Integer> terms, boolean outOfRange) {}

    /**
     * Fallout 2's distance term for the player (`attackDetermineToHit`): the effective distance shrinks by 2 (Long Range 4, Scope
     * Range 5) x (PE - 2); a scope adds 8 below 8 blocks; never below -2 x PE (the sheet subtracted a further 2 x PE instead: its
     * bug); x -4 a block (a blind shooter -12, but only on a penalty: the sheet tripled the bonus too). The sheet applies none within
     * 2 blocks (Marek's).
     */
    public static int distanceTerm(int blocks, int perception, String perk, boolean blind) {
        if (blocks <= 2) return 0;
        int mult = "Long Range".equals(perk) ? 4 : "Scope Range".equals(perk) ? 5 : 2;
        int minEff = "Scope Range".equals(perk) ? 8 : 0;
        int d = blocks;
        if (d >= minEff) d -= mult * (perception - 2);
        else d += minEff;
        if (d < -2 * perception) d = -2 * perception;
        return d >= 0 ? d * (blind ? -12 : -4) : d * -4;
    }

    /**
     * Within this many blocks the light costs nothing: 0 = Fallout 1 and 2 (at any distance), the sheet had 4. Kept at the games'
     * until the balancing (Marek 11-14, verbatim: *"I think this led to wierd effects where peple were unable to hit things just in
     * front of them. But we will do balancing last anyway, so we may catch that later."*).
     */
    public static int lightFreeWithin = 0;

    /**
     * Light at the target: darkness -40, dark -25, dim -10 (F2's steps at 40/60/80 % of full light) at ANY distance, as Fallout 1
     * and 2 both do (checked 11-14; the sheet's "only beyond 4 blocks" is in neither), unless {@link #lightFreeWithin} says
     * otherwise. F1 and F2 charge it only to the player's shots; here everyone's [mine].
     */
    public static int lightTerm(int lightLevel, int blocks) {
        if (blocks <= lightFreeWithin) return 0;
        return lightLevel <= 6 ? -40 : lightLevel <= 9 ? -25 : lightLevel <= 12 ? -10 : 0;
    }

    /**
     * Part 3's senses (03b §2d, the steps mine): the dark halved (the elf, low-light eyes, the perk Night Vision) = −20 / −12 / −5;
     * night vision (catfolk, vampire, Coldvein) = darkness counts as dim (−10), dark and dim cost nothing.
     */
    public static int lightTerm(int lightLevel, int blocks, int senses) {
        int t = lightTerm(lightLevel, blocks);
        if (senses >= 2) return t == -40 ? -10 : 0;
        if (senses == 1) return t == -40 ? -20 : t == -25 ? -12 : t == -10 ? -5 : 0;
        return t;
    }

    /**
     * The aimed part's penalty (F2's table), halved within melee reach (2 blocks) for any weapon: F2 halves it for melee; Marek 11-14,
     * *"The no distance+half aim penalty was supposed to be based on weapon reach ... it is for melee and ranged should be able to do
     * the same."* (so a shot within reach has no distance term either).
     */
    public static int partTerm(Rules.Part part, boolean aimed, int blocks) {
        if (!aimed || part == null) return 0;
        return blocks < 3 ? part.toHit / 2 : part.toHit;
    }

    public static ToHit toHit(Shot s) {
        Map<String, Integer> t = new LinkedHashMap<>();
        t.put("skill", (int) Math.floor(s.skill));
        if (!s.melee) t.put("distance", distanceTerm(s.blocks, s.perception, s.weaponPerk, s.blind));
        t.put("light", lightTerm(s.lightLevel, s.blocks, s.senses));
        t.put("target AC", -s.targetAc);
        t.put("aimed " + (s.part == null ? "" : s.part.label), partTerm(s.part, s.aimed, s.blocks));
        t.put("blind", s.blind ? -25 : 0);
        t.put("Accurate", "Accurate".equals(s.weaponPerk) ? 20 : 0);
        t.put("in the way", -10 * s.obstacles * (s.huge ? 2 : 1));
        t.put("huge target", s.huge ? 15 : 0);
        t.put("Strength", -20 * Math.max(0, s.minStrength - s.strength));
        t.put(Rules.STANCES[s.stance], s.melee ? Rules.STANCE_MELEE[s.stance] : Rules.STANCE_RANGED[s.stance]);
        // the sheet's +10 against a target knocked down or out, RULED back 11-14: F1 and F2 give +40, but they have no crawling, and
        // with crawling +40 would make a fallen man far easier to hit than a crawling one (Marek 11-14: *"maybe tak was because of
        // parity with crawl?"*). A fallen target also LIES like a crawling one [my reading of the parity]: +20 within reach (Marek's
        // Tactics note) and the prone +4 AC, which the caller puts into targetAc; so it is always 10 easier than a crawler
        t.put("target down", s.targetDown ? TARGET_DOWN : 0);
        t.put("lying, within reach", (s.targetProne || s.targetDown) && s.blocks < 3 ? Rules.PRONE_SHORT_RANGE_TO_BE_HIT : 0);
        t.put("moved", s.movePenalty);
        t.putAll(s.extra);
        t.values().removeIf(v -> v == 0);
        int sum = 0;
        for (int v : t.values()) sum += v;
        boolean out = s.blocks > s.range;
        return new ToHit(out ? 0 : sum, t, out);   // no 95 % cap (Marek 11-14: *"no, I hated that"*)
    }

    // ------------------------------------------------------------------ the roll
    /** degree 0 = an ordinary hit or miss; 1-6 a crit's (hit) or 1-5 a critical failure's (miss). */
    public record Roll(int chance, int d100, int delta, boolean hit, boolean crit, boolean critFail, int degree, int critChance) {}

    /**
     * F2's `randomRoll` and the sheet's T22-T27: a d100 against the chance. A hit is a crit when a second d100 is at most
     * delta / 10 + Crit Chance + |the aimed penalty|; its degree from a third d100 (+ better criticals): 20/45/70/90/100. A miss
     * is a critical failure when a d100 is at most -delta / 10; its degree from a d100 - 5 x (LK - 5): 20/50/75/95.
     */
    public static Roll roll(int chance, double critChance, int partTerm, int luck, int betterCrits, IntSupplier d100) {
        return roll(chance, critChance, partTerm, luck, betterCrits, false, d100);
    }

    /**
     * The roll with Marek's 11-14 rulings: **a roll of 100 always misses**, so no chance reaches 100 % however high it is (Marek,
     * verbatim: *"I think the was some Jinx trait that made extreme effects. With that, it should still be possible to famble, so
     * 100% is not reachable."*); **Jinxed** as Fallout 2 does it (`combat.cc` `attackCompute`): every plain miss becomes a critical
     * failure half the time, everyone's in the battle while anyone in it has the trait (02c: *"everyone near you fumbles more
     * often, foes too"*). So a 140 % shot can still fumble: a 100, then Jinxed's coin.
     */
    public static Roll roll(int chance, double critChance, int partTerm, int luck, int betterCrits, boolean jinxed, IntSupplier d100) {
        int d = d100.getAsInt();
        int delta = chance - d;
        if (delta < 0 || d >= 100) {
            boolean cf = delta < 0 && d100.getAsInt() <= -delta / 10;
            if (!cf && jinxed) cf = d100.getAsInt() <= 50;
            int deg = 0;
            if (cf) {
                int r = d100.getAsInt() - 5 * (luck - 5);
                deg = r <= 20 ? 1 : r <= 50 ? 2 : r <= 75 ? 3 : r <= 95 ? 4 : 5;
            }
            return new Roll(chance, d, delta, false, false, cf, deg, 0);
        }
        int cc = (int) Math.floor(delta / 10 + critChance - partTerm);
        boolean crit = d100.getAsInt() <= cc;
        int deg = 0;
        if (crit) {
            int r = d100.getAsInt() + betterCrits;
            deg = r <= 20 ? 1 : r <= 45 ? 2 : r <= 70 ? 3 : r <= 90 ? 4 : r <= 100 ? 5 : 6;
        }
        return new Roll(chance, d, delta, true, crit, false, deg, cc);
    }

    // ------------------------------------------------------------------ the crit
    private enum Zone { HEAD, EYES, BODY, ARMS, LEGS, GROIN }

    private static Zone zone(Rules.Part p) {
        if (p == null) return Zone.BODY;
        return switch (p) {
            case HEAD -> Zone.HEAD;
            case EYES -> Zone.EYES;
            case TORSO -> Zone.BODY;
            case LEFT_ARM, RIGHT_ARM -> Zone.ARMS;
            case LEFT_LEG, RIGHT_LEG -> Zone.LEGS;
            case GROIN -> Zone.GROIN;
        };
    }

    /** The sheet's T37 = Fallout 2's table for men, exactly (05-BATTLE.md §3). */
    public static double critMultiplier(Rules.Part part, int degree) {
        if (degree <= 0) return 1;
        int k = degree;
        return switch (zone(part)) {
            case HEAD -> 2 + 0.5 * ((k - 1) / 2);
            case ARMS, LEGS -> 1.5 + 0.5 * ((k + 1) / 4);
            case EYES -> 2 + (k - 1) / 2;
            case BODY -> 1.5 + 0.5 * ((k - 1) / 2) + 0.5 * ((k - 1) / 4);
            case GROIN -> 1.5 + 0.5 * ((k - 1) / 4);
        };
    }

    /** What a crit does to its target. */
    public record CritEffects(boolean bypass, boolean knockedDown, boolean knockedOut, boolean crippledArm, boolean crippledLeg,
                              boolean blinded, boolean losesTurn, boolean dropsWeapon, boolean dies, String heart) {
        public CritEffects(boolean bypass, boolean knockedDown, boolean knockedOut, boolean crippledArm, boolean crippledLeg,
                           boolean blinded, boolean losesTurn, boolean dropsWeapon, boolean dies) {
            this(bypass, knockedDown, knockedOut, crippledArm, crippledLeg, blinded, losesTurn, dropsWeapon, dies, "");
        }

        /** The heart stops (03b §3c): "" = no, else the cause (a blow to the chest, electricity). */
        public boolean heartStops() { return heart != null && !heart.isEmpty(); }

        public List<String> names() {
            List<String> n = new ArrayList<>();
            if (heartStops()) n.add("the heart stops (" + heart + ")");
            if (dies) n.add("killed outright");
            if (knockedOut) n.add("knocked out");
            if (knockedDown) n.add("knocked down");
            if (crippledArm) n.add("arm crippled");
            if (crippledLeg) n.add("leg crippled");
            if (blinded) n.add("blinded");
            if (losesTurn) n.add("loses the next turn");
            if (dropsWeapon) n.add("drops the weapon");
            if (bypass) n.add("through the armour");
            return n;
        }
    }

    /**
     * The sheet's T28-T36: an effect when the TARGET's stat is below a threshold growing with the degree (no roll: Marek's rule for
     * every check). The knockdown with its constant outside the 3 x degree product (05-BATTLE.md §4, question 2).
     * @param a the target's S, P, E, C, I, A, L
     */
    public static CritEffects critEffects(Rules.Part part, int d, int[] a) {
        if (d <= 0) return new CritEffects(false, false, false, false, false, false, false, false, false);
        Zone z = zone(part);
        int st = a[Rules.S], pe = a[Rules.P], en = a[Rules.E], ag = a[Rules.A], lk = a[Rules.L];
        boolean eyes = z == Zone.EYES, legs = z == Zone.LEGS, arms = z == Zone.ARMS;
        boolean bypass = d >= (arms || legs ? 4 : 2);
        int kdStat = (legs || z == Zone.BODY) ? ag : en;
        int kdConst = switch (z) { case HEAD -> 3; case ARMS -> -8; case LEGS -> 5; case EYES -> 7; default -> 0; };
        boolean down = kdStat < -4 + 3 * d * (!eyes && !legs ? 1 : 0) + d * (eyes ? 1 : 0) + 2 * d * (legs ? 1 : 0) + kdConst;
        int koConst = switch (z) { case HEAD -> 3; case ARMS -> -2; case LEGS -> -5; case EYES -> 3; case BODY -> -3; default -> 0; };
        boolean out = en < -7 + 3 * d * (!eyes ? 1 : 0) + d * (eyes ? 1 : 0) + koConst;
        boolean blind = (z == Zone.HEAD && lk < 1 + d) || (eyes && lk < 3 + d);
        boolean cripArm = arms && en < -1 + 2 * d;
        boolean cripLeg = legs && en < -4 + 3 * d;
        boolean turn = (z == Zone.HEAD || z == Zone.BODY) && pe < -4 + 3 * d;
        boolean drop = arms && ag < 1 + 2 * d;
        boolean dies = (z == Zone.HEAD || eyes || z == Zone.BODY) && d >= 6;
        return new CritEffects(bypass, down, out, cripArm, cripLeg, blind, turn, drop, dies);
    }

    /**
     * A crit by the weapon's kind (03b §3c, RULED 11-18): a blunt weapon's or a fist's HIGHEST crit to the torso stops the heart (a
     * blow to the chest: an arrest with HP kept, instead of killed outright); an electric weapon's any crit to the torso does too.
     * {@code kind}: "blunt", "electric", anything else = the plain table.
     */
    public static CritEffects critEffects(Rules.Part part, int d, int[] a, String kind) {
        CritEffects fx = critEffects(part, d, a);
        if (d <= 0 || zone(part) != Zone.BODY) return fx;
        boolean blunt = "blunt".equals(kind) && d >= 6;
        String heart = blunt ? "a blow to the chest" : "electric".equals(kind) && !fx.dies() ? "electricity" : "";
        if (heart.isEmpty()) return fx;
        return new CritEffects(fx.bypass(), fx.knockedDown(), fx.knockedOut(), fx.crippledArm(), fx.crippledLeg(), fx.blinded(),
                fx.losesTurn(), fx.dropsWeapon(), false, heart);   // the blow replaces "killed outright"; an electric top crit still kills
    }

    /** What a critical failure does to the shooter. */
    public record Failure(boolean losesAmmo, boolean losesTurn, boolean dropsWeapon, boolean weaponDamaged, boolean hitsRandom,
                          boolean hurt1d6, boolean weaponDestroyed, boolean knockedDown, boolean crippledArm, boolean hitsSelf, boolean onFire) {
        public List<String> names() {
            List<String> n = new ArrayList<>();
            if (losesAmmo) n.add("loses the ammo in the magazine");
            if (dropsWeapon) n.add("drops the weapon");
            if (weaponDamaged) n.add("damages the weapon");
            if (weaponDestroyed) n.add("destroys the weapon");
            if (hitsRandom) n.add("hits someone else at random");
            if (hurt1d6) n.add("takes 1d6");
            if (hitsSelf) n.add("hits himself");
            if (knockedDown) n.add("falls down");
            if (crippledArm) n.add("cripples an arm");
            if (onFire) n.add("catches fire");
            if (losesTurn) n.add("loses the rest of the turn");
            return n;
        }
    }

    /**
     * The sheet's own critical failures (U30-U44, its `&` read as AND: they never fired in Sheets), RULED 11-14 over F2's rows
     * (Marek: *"Critical failures - use the combined list."*): 2 the ammo, 3 drops and damages the weapon, 4 a random hit, 1d6 to
     * himself, the weapon destroyed, 5 falls, cripples an arm, hits himself, catches fire, the weapon destroyed; above 1 the turn.
     */
    public static Failure critFailure(int d) { return critFailure(d, false); }

    /**
     * The list with catching fire only for a FIRE weapon (Marek 11-14, verbatim: *"set on fire means the attacker literally ignites
     * themself. That should only be possible with some weapon types, I think."*): Fallout 2's own table has it only in the flamers'
     * row (`_cf_table` row 6) and strips what a weapon cannot do (`_attackFindInvalidFlags`), so a pistol never sets you alight.
     */
    public static Failure critFailure(int d, boolean fireWeapon) {
        return new Failure(d == 2, d > 1, d == 3, d == 3, d == 4, d == 4, d > 3, d == 5, d == 5, d == 5, d == 5 && fireWeapon);
    }

    /** A fire weapon: the Inventory's damage type Fire (a flamer, incendiary rounds, a Molotov). */
    public static boolean fireWeapon(String damageType) { return "Fire".equalsIgnoreCase(damageType); }

    // ------------------------------------------------------------------ the weapon's condition: Fallout: New Vegas
    // Marek 11-14: *"Weapon condition - please check FNV one, that one I think works better with repairs."* FNV's rules as the
    // Fallout Wiki gives them (*Equipment condition*, *Repair (Fallout 3)*, *Weapon repair kit*): a weapon has ITEM HEALTH, its
    // condition = health / maximum; a gun loses 0.2 health a round fired; damage falls from full at 75 % to half at 0; it is
    // broken on its last health point; repairs combine two of the same item, or use a repair kit, by the repair skill.

    /** FNV: a gun loses 0.2 item health for every round it fires (ammo and companions change the rate there). */
    public static final double WEAR_PER_ROUND = 0.2;

    /** FNV: damage x (0.5 + min(2 x condition / 3, 0.5)): full at 75 % condition and above, half at 0. */
    public static double conditionDamage(double condition) { return 0.5 + Math.min(2 * Math.max(0, condition) / 3, 0.5); }

    /** FNV: a weapon is broken (cannot be fired) on its last item health point. */
    public static boolean broken(double health) { return health <= 1 + 1e-9; }

    /**
     * "The weapon damaged" of a critical failure (Marek 11-14: *"The weapon condition is decreased (we still have to find how to
     * calculate how much)"*): FNV has no fumbles, so the amount is ours: a tenth of its maximum health [EXAMPLE].
     */
    public static double damaged(double health, double maxHealth) { return Math.max(0, health - WEAPON_DAMAGED_SHARE * maxHealth); }

    /** [EXAMPLE] the share of its maximum health a "weapon damaged" costs. */
    public static final double WEAPON_DAMAGED_SHARE = 0.1;

    /**
     * FNV's repair by combining two of the same item (Fallout 3's efficiency, FNV's cap of 100 %): the better one + 5 + 5 % of the
     * other + 0.15 x the repair skill, in % of condition [the wiki writes no formula, only two worked examples: 20 % with 10 % at
     * skill 10 = 27 %, at skill 50 = 33 %; this fits both]. Mr. Fixit (02c) doubles what is restored.
     */
    public static double combineRepair(double c1, double c2, double skill, boolean mrFixit) {
        double hi = Math.max(c1, c2), lo = Math.min(c1, c2);
        double gain = 5 + 0.05 * lo + 0.15 * skill;
        return Math.min(100, hi + (mrFixit ? 2 : 1) * gain);
    }

    /**
     * FNV's weapon repair kit: +10, 15, 20, 25 or 30 % condition at the repair skill 0-24, 25-49, 50-74, 75-99, 100+ (the wiki's
     * table, read as percentage points), at most 100; Mr. Fixit doubles it.
     */
    public static double kitRepair(double condition, double skill, boolean mrFixit) {
        int add = skill >= 100 ? 30 : skill >= 75 ? 25 : skill >= 50 ? 20 : skill >= 25 ? 15 : 10;
        return Math.min(100, condition + (mrFixit ? 2 : 1) * add);
    }

    /**
     * Burning, as Fallout 2's code does it (Marek 11-14: *"Burning - please check it from F2 code."*): DAM_ON_FIRE is only the
     * "fire dance" animation (`actions.cc`); the harm is the flamers' critical failure row itself, `DAM_EXPLODE | DAM_LOSE_TURN |
     * DAM_ON_FIRE`, where DAM_EXPLODE deals the weapon's own damage once to the shooter (`attackComputeDamage(attack, 1, 2)`: one
     * round, no crit) through HIS fire DT and DR, and the turn is lost. No damage over time. In our list the self-hit is the
     * "hits himself" of degree 5; with a fire weapon it is fire damage, which the survival sheet turns into Hyperthermia x3.
     */
    public static final double FIRE_HYPERTHERMIA_PER_HP = 3;

    // ------------------------------------------------------------------ damage
    /**
     * The sheet's R28-R33 (F2's form): ((base) x max(1, crit multiplier) x (1 + the ammo's damage modifier) - DT) x (1 - DR), DR
     * scaled by (1 - the ammo's penetration); through the armour DT and DR are a fifth. Never below 0; rounded to whole HP [mine].
     */
    public static int damage(double base, double critMult, double ammoDamageMod, double ammoPenetration, int dt, double dr, boolean bypass) {
        double dm = Math.max(1, critMult) * (1 + ammoDamageMod);
        double dtE = dt / (bypass ? 5.0 : 1.0);
        double drE = Math.max(0, Math.min(1, (1 - ammoPenetration) * dr / (bypass ? 5.0 : 1.0)));
        double v = (base * dm - dtE) * (1 - drE);
        return (int) Math.max(0, Math.round(v));
    }

    /**
     * Fallout 2's burst (`_compute_spray`): the rounds split in thirds (centre at least 1, left, right = the rest); half the centre
     * (at least 1) each roll at the target. Returns {at the target, the rest of the centre, left, right}.
     */
    public static int[] burstSplit(int rounds) {
        int centre = Math.max(1, rounds / 3), left = rounds / 3, right = rounds - centre - left;
        int main = centre / 2;
        if (main == 0) { main = 1; centre -= 1; } else centre -= main;
        return new int[]{main, centre, left, right};
    }

    /** The sheet's knockback, RULED 11-14 for every attack, bullets too (Marek: *"Knockback for bullets too - yes."*): damage / 10 blocks. */
    public static int knockback(int damage) {
        return damage / 10;
    }

    /** Fallout 2's frag grenade [EXAMPLE until the workbook has one]: 20-35 explosion damage, 4 AP, a 2-block blast (item.cc). */
    public static final int GRENADE_MIN = 20, GRENADE_MAX = 35, GRENADE_RADIUS = 2;

    /** Fallout 2: a missed grenade lands 1 to distance / 2 blocks from where it was thrown at, in a random direction. */
    public static int grenadeScatter(int blocks, IntSupplierBound rnd) {
        return Math.max(1, rnd.between(1, Math.max(1, blocks / 2)));
    }

    /** A bounded die for the tests. */
    public interface IntSupplierBound { int between(int lo, int hi); }

    /** Fallout 2's Stimpak [EXAMPLE]: +10 to +20 HP (fallout.wiki), never above the maximum. */
    public static int stimpak(int hp, int hpMax, IntSupplierBound rnd) {
        return Math.min(hpMax, hp + rnd.between(10, 20));
    }
}
