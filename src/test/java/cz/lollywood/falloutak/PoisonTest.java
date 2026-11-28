package cz.lollywood.falloutak;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The poison system (04d §1, RULED rounds 1-8, 11-14..11-16; TODO 24, built 0.117.0): the bite's roll, the depots and their holds, the
 * hourly roll that only takes away, the drain, the tourniquet's clock, the antidotes, excision, the Wild Wasteland groin. The numbers are
 * the survival sim's (`_tools/fallout_survival_sim.py` §4).
 */
class PoisonTest {

    /** Dice giving these nextInt values in turn (a d100 = the value + 1), the last one repeated. */
    static class Dice extends Random {
        private final int[] v;
        private int i;
        Dice(int... v) { this.v = v; }
        @Override public int nextInt(int bound) { int x = v[Math.min(i++, v.length - 1)]; return Math.min(x, bound - 1); }
        @Override public boolean nextBoolean() { return nextInt(2) == 1; }
        @Override public double nextDouble() { return 0.999; }   // the infection rolls: not caught
    }

    /** Fails every roll (the venom gets in, no luck, no cure). */
    static Dice fail() { return new Dice(99); }

    @AfterEach
    void reset() { Travel.wildWasteland = false; Travel.air = Double.NaN; }

    static Character body() {
        Character c = Part3Test.of("Human", 5, 5, 5, 5, 5, 5, 5);
        c.clock = 1000;
        return c;
    }

    @Test
    void aLimbBiteSitsInTheLimbATorsoBiteGoesIntoTheBody() {
        Character c = body();
        assertTrue(Poison.bite(c, "left_leg", 10, fail()).contains("into the left leg"));
        assertEquals(30, c.depots.get("left_leg"), 1e-9, "3 poison a point of damage through DT (04d §1)");
        assertEquals(0, Poison.bar(c), 1e-9, "nothing in the body yet");
        Poison.bite(c, "torso", 10, fail());
        assertEquals(30, Poison.bar(c), 1e-9);
        assertEquals(30, c.venom, 1e-9, "a bite's poison is venom");
        Poison.bite(c, "groin", 5, fail());
        assertEquals(45, Poison.bar(c), 1e-9, "the groin counts as the torso (round 8)");
        assertFalse(c.depots.containsKey("groin"));
        Poison.bite(c, "head", 2, fail());
        assertEquals(51, Poison.bar(c), 1e-9);
        assertEquals("", Poison.bite(c, "torso", 0, fail()), "no damage through DT, no venom");
    }

    @Test
    void theResistanceRollADryBiteAndTheAntivenomsGuard() {
        Character c = body();
        int res = (int) Math.round(100 * Poison.resistance(c));
        assertTrue(res > 0 && res < 100);
        assertTrue(Poison.bite(c, "torso", 10, new Dice(res - 1)).contains("resisted"), "d100 = the resistance: resisted");
        assertTrue(Poison.bite(c, "torso", 10, new Dice(res)).length() > 0);
        double after = Poison.bar(c);
        assertEquals(30, after, 1e-9, "d100 one over the resistance: the venom in");
        // Luck 5: Lucky Escape at 2 × 5 = 10 %: a dry bite
        assertTrue(Poison.bite(c, "torso", 10, new Dice(99, 9)).contains("DRY BITE"));
        assertFalse(Poison.bite(c, "torso", 10, new Dice(99, 10)).contains("DRY BITE"), "d100 11 > 10");
        assertEquals(60, Poison.bar(c), 1e-9);
        c.perks.add(new Character.Feat("Lucky Streak", "", ""));
        assertEquals(15, c.luck(new Dice(0)).chance(), "Lucky Streak: the procs × 1.5");
        // antivenom into a muscle guards two weeks against the next bite, once
        Poison.treat(c, "antivenom-im", null, Double.NaN, Double.NaN, false, fail());
        assertTrue(Poison.bite(c, "left_arm", 10, fail()).contains("guard is used up"));
        assertFalse(c.depots.containsKey("left_arm"));
        assertTrue(Poison.bite(c, "left_arm", 10, fail()).contains("into the left arm"), "the guard took one bite");
    }

    @Test
    void theDepotFlowsByItsHold() {
        // 04d §1: moving 1/2, a pressure bandage kept still or a band 1/8, too loose 1/4, a loosened tourniquet 1/12, a full one none
        double[][] cases = {{0, 0.5}, {1, 1.0 / 8}, {2, 1.0 / 8}, {3, 0.25}, {4, 0}, {5, 1.0 / 12}};
        String[] holds = {"", "bandage", "band", "loose", "tq", "tq-loose"};
        for (int k = 0; k < holds.length; k++) {
            Character c = body();
            c.depots.put("right_leg", 40.0);
            if (!holds[k].isEmpty()) c.holds.put("right_leg", holds[k]);
            Poison.hour(c, fail());
            assertEquals(40 * cases[k][1], Poison.bar(c), 1e-9, holds[k]);
            assertEquals(40 * cases[k][1], c.venom, 1e-9);
        }
        Character c = body();   // a pressure bandage works kept still: moving pumps the lymph
        c.depots.put("right_leg", 40.0);
        c.holds.put("right_leg", "bandage");
        c.walking = true;
        Poison.hour(c, fail());
        assertEquals(20, Poison.bar(c), 1e-9, "a bandage on the march: 1/2");
        c.holds.put("right_leg", "band");
        Poison.hour(c, fail());
        assertEquals(20 + 20.0 / 8, Poison.bar(c), 1e-9, "a constriction band on the march: 1/8");
    }

    @Test
    void theHourlyRollOnlyTakesAwayAndHalvesTheDrain() {
        Character c = body();
        Poison.addBar(c, 40, true);
        double res = Poison.resistance(c);   // with the Poisoning stage's own penalties (Critical: E down)
        Poison.hour(c, fail());
        assertEquals(40, Poison.bar(c), 1e-9, "a failed roll takes nothing (and adds nothing)");
        assertFalse(c.poisonHalved);
        Poison.hour(c, new Dice(0, 99));
        assertEquals(40 - (1 + 100 * res / 20), Poison.bar(c), 1e-9, "a success cures 1 + the resistance (%) / 20 (RULED round 5)");
        assertTrue(c.poisonHalved, "and halves that hour's drain");
        assertEquals(0.5, Poison.drain(c), 1e-9);
        double b = Poison.bar(c);
        Poison.hour(c, new Dice(0, 0));
        assertEquals(b - 2 * (1 + 100 * res / 20), Poison.bar(c), 1e-9, "Double Healing (2 × Luck %): the cure × 2 (04d §4)");
        // the chance = the resistance; tended +0.25 for 6 hours (a Survival or Medicine 20 check)
        res = Poison.resistance(c);
        int need = (int) Math.round(100 * res);
        b = Poison.bar(c);
        Poison.hour(c, new Dice(need, 99));
        assertEquals(b, Poison.bar(c), 1e-9, "d100 one over the resistance: no cure");
        assertTrue(Poison.treat(c, "tend", null, 10, 15, false, fail()).startsWith("!"), "Survival 10, Medicine 15: under 20");
        assertTrue(Poison.treat(c, "tend", null, 25, 0, false, fail()).startsWith("tended"), "Survival 25 does it");
        Poison.hour(c, new Dice(need, 99));
        assertTrue(Poison.bar(c) < b, "tended: the same d100 now cures");
        c.clock += 7;
        b = Poison.bar(c);
        Poison.hour(c, new Dice(need, 99));
        assertEquals(b, Poison.bar(c), 1e-9, "6 hours later the tending is over");
    }

    @Test
    void theDrainGrowsWithDehydrationAndBreathingMakesTheHalvingCertain() {
        Character c = body();
        double w = c.max(Rules.Stat.WATER);
        c.survival.put(Rules.Stat.WATER, 0.5 * w);   // Advanced Dehydration
        assertEquals(1.25, Poison.drain(c), 1e-9);
        c.survival.put(Rules.Stat.WATER, 0.3 * w);
        assertEquals(1.5, Poison.drain(c), 1e-9);
        c.survival.put(Rules.Stat.WATER, 0.1 * w);
        assertEquals(2, Poison.drain(c), 1e-9);
        c.survival.put(Rules.Stat.WATER, w);
        Poison.addBar(c, 0.7 * c.max(Rules.Stat.POISON), true);   // Deadly Poisoning
        Poison.hour(c, fail());
        assertFalse(c.poisonHalved);
        assertTrue(Poison.treat(c, "breathing", null, 99, 20, false, fail()).startsWith("!"), "breathing kept up needs Medicine 30");
        Poison.treat(c, "breathing", null, 0, 30, false, fail());
        Poison.hour(c, fail());
        assertTrue(c.poisonHalved, "at Deadly the breathing makes the halving certain (round 5)");
        // the stage's HP an hour × the factor (Deadly Poisoning: −4 an hour, halved: −2)
        List<String> before = c.stages();
        c.hp = c.hpMax() - 1;
        int hp = c.hp;
        c.stageHour(before);
        assertEquals(hp - 2, c.hp);
    }

    @Test
    void critialToFatalPoisoningSweatAndVomit() {
        Character c = body();
        double w = c.survival.get(Rules.Stat.WATER);
        Poison.addBar(c, 0.5 * c.max(Rules.Stat.POISON), false);   // Critical Poisoning
        Poison.hour(c, fail());
        assertEquals(w - 1, c.survival.get(Rules.Stat.WATER), 1e-9, "Critical: 1 water an hour");
        Poison.addBar(c, 0.35 * c.max(Rules.Stat.POISON), false);   // Fatal
        Poison.hour(c, fail());
        assertEquals(w - 3, c.survival.get(Rules.Stat.WATER), 1e-9, "Fatal: 2");
    }

    @Test
    void aFullBarStopsTheHeart() {
        Character c = body();
        Poison.addBar(c, 999, false);
        assertEquals(c.max(Rules.Stat.POISON), Poison.bar(c), 1e-9, "the bar stops at its maximum");
        assertNotNull(c.checkArrest(fail()));
        assertTrue(c.inArrest());
        assertEquals("the poison", c.arrestCause);
    }

    @Test
    void theTourniquetsClock() {
        Character c = body();
        c.depots.put("left_arm", 30.0);
        Poison.treat(c, "tq", "left_arm", Double.NaN, Double.NaN, false, fail());
        for (int h = 0; h < 3; h++) Poison.hour(c, fail());
        assertEquals(3, c.ischemia.get("left_arm"), 1e-9, "a full tourniquet: 1 ischemia hour an hour");
        assertEquals(30, c.depots.get("left_arm"), 1e-9, "the venom stays in the arm");
        Poison.treat(c, "tq-loose", "left_arm", Double.NaN, Double.NaN, false, fail());
        Poison.hour(c, fail());
        assertEquals(3.5, c.ischemia.get("left_arm"), 1e-9, "loosened: half");
        Poison.treat(c, "cool", "left_arm", Double.NaN, Double.NaN, false, fail());
        Poison.hour(c, fail());
        assertEquals(3.75, c.ischemia.get("left_arm"), 1e-9, "loosened and cold: a quarter");
        int hp = c.hp;
        String r = Poison.treat(c, "off", "left_arm", Double.NaN, 40, false, fail());
        assertEquals(hp - Math.round(2 * 1.75), c.hp, "the reperfusion: 2 HP an ischemia hour past 2");
        assertTrue(r.contains("flows again"), r);
        assertTrue(c.holds.isEmpty() && c.ischemia.isEmpty());
        // 4 hours: crippled for 3 weeks; IV fluids first halve the reperfusion
        c.holds.put("right_leg", "tq");
        c.ischemia.put("right_leg", 5.0);
        assertTrue(Poison.treat(c, "fluids", null, 0, 39, false, fail()).startsWith("!"), "an IV needs Medicine 40");
        Poison.treat(c, "fluids", null, 0, 40, false, fail());
        hp = c.hp;
        int legs = c.brokenLegs;
        r = Poison.treat(c, "off", "right_leg", Double.NaN, 40, false, fail());
        assertEquals(hp - 3, c.hp, "(5 − 2) × 2 = 6, halved");
        assertEquals(legs + 1, c.brokenLegs, "a crippled leg walks as a broken one");
        assertNotNull(c.inSlot("injury:tq:right_leg"));
        assertTrue(r.contains("crippled"), r);
    }

    @Test
    void afterSixHoursTheLimbIsLostByTheMedicAndTheCold() {
        // 2 in 3 × (1 − ½ × min(1, Medicine / 100)) × (½ if cold): Medicine 0 67 %, 100 33 %, 100 and cold 17 %
        for (double[] k : new double[][]{{0, 0, 67}, {100, 0, 33}, {100, 1, 17}}) {
            Character c = body();
            c.holds.put("left_leg", "tq");
            c.ischemia.put("left_leg", 6.0);
            if (k[1] > 0) c.cooledLimbs.add("left_leg");
            int edge = (int) k[2];
            String lost = Poison.off(c, "left_leg", k[0], new Dice(edge - 1, 99));
            assertTrue(lost.contains("DEAD"), k[0] + " " + lost);
            assertNotNull(c.inSlot("limb:left_leg"), "the leg is missing");
            Character d = body();
            d.holds.put("left_leg", "tq");
            d.ischemia.put("left_leg", 6.0);
            if (k[1] > 0) d.cooledLimbs.add("left_leg");
            assertFalse(Poison.off(d, "left_leg", k[0], new Dice(edge, 99)).contains("DEAD"), "d100 one over: it lives");
            assertNull(d.inSlot("limb:left_leg"));
        }
        Character c = body();   // Lucky Escape against the loss
        c.holds.put("left_leg", "tq");
        c.ischemia.put("left_leg", 7.0);
        assertTrue(Poison.off(c, "left_leg", 0, new Dice(0, 0)).contains("LUCKY ESCAPE"));
        assertNull(c.inSlot("limb:left_leg"));
    }

    @Test
    void theBandageCheckAndItsFailures() {
        Character c = body();
        c.depots.put("left_leg", 30.0);
        assertTrue(Poison.treat(c, "bandage", "left_leg", 20, 0, false, fail()).contains("right"), "Survival 20 does it");
        assertEquals("bandage", c.holds.get("left_leg"));
        Poison.treat(c, "bandage", "left_leg", 5, 19, false, new Dice(1));
        assertEquals("tq", c.holds.get("left_leg"), "failed, too tight: a tourniquet");
        Poison.treat(c, "band", "left_leg", 5, 19, false, new Dice(0));
        assertEquals("loose", c.holds.get("left_leg"), "failed, too loose: 1/4");
        assertTrue(Poison.treat(c, "band", "right_leg", 50, 50, false, fail()).startsWith("!"), "no venom there");
        assertTrue(Poison.treat(c, "bandage", null, 50, 50, false, fail()).startsWith("!"), "a limb is needed");
    }

    @Test
    void theAntidotesActOnVenomOnly() {
        Character c = body();
        Poison.addBar(c, 20, false);   // a gas's poison: no venom
        Poison.addBar(c, 30, true);
        c.depots.put("left_arm", 24.0);
        // by IV: a Medicine 40 check; all the venom at once, the gas's poison stays
        assertTrue(Poison.treat(c, "antivenom-iv", null, 0, 39, false, fail()).startsWith("!"));
        Poison.treat(c, "antivenom-iv", null, 0, 40, false, fail());
        assertEquals(20, Poison.bar(c), 1e-9, "the wrong poison for antivenom: it stays");
        assertEquals(0, c.venom, 1e-9);
        assertTrue(c.depots.isEmpty());
        // into a muscle: over 12 hours
        Character m = body();
        Poison.addBar(m, 36, true);
        Poison.treat(m, "antivenom-im", null, Double.NaN, Double.NaN, false, fail());
        Poison.hour(m, fail());
        assertEquals(36 * (1 - 1.0 / 12), Poison.bar(m), 1e-9, "the first of its 12 hours");
        for (int h = 1; h < 12; h++) Poison.hour(m, fail());
        assertEquals(0, Poison.bar(m), 1e-9, "all of it by the 12th hour");
        assertEquals(0, m.antivenomHours, 1e-9);
        assertTrue(m.antivenomGuardUntil > m.now() + 13 * 24, "the guard: two weeks");
    }

    @Test
    void varespladibWithinFiveHoursAndPlasmaExchange() {
        Character c = body();
        Poison.bite(c, "torso", 10, fail());
        c.clock += 6;
        assertTrue(Poison.treat(c, "varespladib", null, Double.NaN, Double.NaN, false, fail()).contains("too late"));
        assertEquals(0, c.varesHours, 1e-9);
        Character d = body();
        Poison.bite(d, "torso", 10, fail());
        d.clock += 4;
        Poison.treat(d, "varespladib", null, Double.NaN, Double.NaN, false, fail());
        for (int h = 0; h < 12; h++) Poison.hour(d, fail());
        assertEquals(30 * Math.pow(1 - 0.5 / 12, 12), Poison.bar(d), 1e-9, "about half over 12 hours (the sim's 1/24 an hour)");
        Character e = body();
        Poison.addBar(e, 40, false);
        Poison.treat(e, "plasma", null, Double.NaN, Double.NaN, false, fail());
        Poison.hour(e, fail());
        assertEquals(36, Poison.bar(e), 1e-9, "plasma exchange: a tenth an hour, any poison");
    }

    @Test
    void excisionByTheMedicsSkillAndTime() {
        Character c = body();
        Poison.bite(c, "left_leg", 10, fail());
        int hp = c.hp;
        double blood = c.survival.get(Rules.Stat.BLOOD), bmax = c.max(Rules.Stat.BLOOD);
        Poison.treat(c, "excise", "left_leg", 0, 60, false, fail());
        assertEquals(30 * (1 - (0.15 + 60.0 / 400)), c.depots.get("left_leg"), 1e-9, "in the first hour 15 % + Medicine / 400");
        assertEquals(hp - Math.round(5 - 60.0 / 40), c.hp, "HP −max(1, 5 − Medicine / 40)");
        assertEquals(blood - 0.09 * bmax, c.survival.get(Rules.Stat.BLOOD), 1e-6, "Blood −max(3, 15 − Medicine / 10) %");
        assertEquals(40, c.survival.get(Rules.Stat.PAIN), 1e-9);
        Character d = body();
        Poison.bite(d, "left_leg", 10, fail());
        d.clock += 1.5;
        Poison.treat(d, "excise", "left_leg", 0, 200, false, fail());
        assertEquals(30 * 0.75, d.depots.get("left_leg"), 1e-9, "the second hour half of at most 50 %");
        d.clock += 1;
        Poison.treat(d, "excise", "left_leg", 0, 200, false, fail());
        assertEquals(30 * 0.75, d.depots.get("left_leg"), 1e-9, "later nothing");
    }

    @Test
    void amputationTakesTheVenomWithTheLimb() {
        Character c = body();
        Poison.bite(c, "right_arm", 10, fail());
        int hp = c.hp;
        Poison.treat(c, "amputate", "right_arm", Double.NaN, 50, true, fail());
        assertTrue(c.depots.isEmpty());
        assertNotNull(c.inSlot("limb:right_arm"));
        assertEquals(hp - 5, c.hp);
        assertEquals(0, c.survival.get(Rules.Stat.PAIN), 1e-9, "under anaesthesia no Pain");
        assertTrue(Poison.bite(c, "right_arm", 10, fail()).contains("straight into the body"), "no arm, no depot there");
    }

    @Test
    void theWildWastelandGroin() {
        Character c = body();
        c.female = false;
        Poison.bite(c, "groin", 10, fail());
        assertEquals(30, Poison.bar(c), 1e-9, "without the setting: the torso's");
        Travel.wildWasteland = true;
        Character m = body();
        m.female = false;
        Poison.bite(m, "groin", 10, fail());
        assertEquals(30, m.depots.get("groin"), 1e-9, "the setting on: a man's groin is a small depot");
        assertTrue(Poison.acts(m).contains("cutaway"));
        assertTrue(Poison.treat(m, "band", "groin", 99, 99, false, fail()).startsWith("!"), "no band goes round it");
        Poison.treat(m, "cutaway", null, Double.NaN, Double.NaN, false, fail());
        assertTrue(m.depots.isEmpty());
        assertEquals(80, m.survival.get(Rules.Stat.PAIN), 1e-9);
        assertNotNull(m.inSlot("injury:groin"));
        Character f = body();
        f.female = true;
        Poison.bite(f, "groin", 10, fail());
        assertEquals(30, Poison.bar(f), 1e-9, "a woman's: the torso's");
    }

    @Test
    void anUntreatedLimbBiteAsTheSimPlaysIt() {
        // the sim (04d §1's table, expected values): an average body, one bite in a limb (+30), untreated, lives; with the roll at its
        // expected rate the poison peaks and is gone within two days
        Character c = body();
        Poison.bite(c, "left_leg", 10, fail());
        double res = Poison.resistance(c), peak = 0;
        Random r = new Random(11);
        int hours = 0;
        while (Poison.poisoned(c) && hours < 200) {
            Poison.hour(c, r);
            peak = Math.max(peak, Poison.bar(c));
            hours++;
        }
        assertTrue(peak > 15 && peak < 30, "the peak " + peak);
        assertTrue(hours > 12 && hours < 120, "gone in " + hours + " h (resistance " + res + ")");
    }

    @Test
    void luckyEscapeOnCatchingAndCancerDoubleHealingOnAnyHealing() {
        // 04d §4 (RULED round 9): Lucky Escape = 2 × Luck % against a roll that goes against you
        Character c = body();                                                   // Luck 5: 10 %
        String r = Diseases.expose(c, "Wound infection", 50, false, false, new Dice(9) {
            @Override public double nextDouble() { return 0.0; }                // the catching roll 0: caught
        });
        assertTrue(r.contains("LUCKY ESCAPE"), r);
        assertNull(Diseases.active(c, "Wound infection"));
        r = Diseases.expose(c, "Wound infection", 50, false, false, new Dice(10) {
            @Override public double nextDouble() { return 0.0; }
        });
        assertTrue(r.contains("CAUGHT"), "d100 11 > 10: " + r);
        Character k = body();                                                   // cancer's yearly roll
        k.lifetimeRads = 1000;
        k.clock = 2 * Diseases.YEAR;
        Diseases.cancerYear(k, new Dice(0) { @Override public double nextDouble() { return 0.0; } });
        assertNull(Diseases.active(k, "Cancer"), "caught, but Lucky Escape");
        Diseases.cancerYear(k, new Dice(99) { @Override public double nextDouble() { return 0.0; } });
        assertNotNull(Diseases.active(k, "Cancer"));
        // Double Healing: the rest's 8 hours, a Stimpak, never at Luck 0
        Character h = body();
        h.hp = 1;
        h.sleeping = true;
        int rate = h.healingRate();
        assertTrue(rate > 0);
        try {
            Hud.dice = new Dice(0);
            for (int i = 0; i < 8; i++) Hud.heal(h);
            assertEquals(1 + 2 * rate, h.hp, "Double Healing on the rest: × 2");
            Hud.dice = new Dice(99);
            for (int i = 0; i < 8; i++) Hud.heal(h);
            assertEquals(1 + 3 * rate, h.hp, "no proc: the rate");
        } finally {
            Hud.dice = null;
        }
        Character s = body();
        s.hp = 1;
        Meds.inject(s, "Stimpak", new Dice(0));
        assertEquals(1 + 20, s.hp, "a Stimpak's 10 (the least roll), doubled");
        Character z = body();
        z.base[Rules.L] = 0;
        assertEquals(0, z.luck(new Dice(0)).chance(), "Luck 0 has no procs");
        z.hp = 1;
        Meds.inject(z, "Stimpak", new Dice(0));
        assertEquals(11, z.hp);
    }

    @Test
    void theStrikesPartsByTheSilhouette() {
        int[] n = new int[5];
        Random r = new Random(5);
        for (int i = 0; i < 10000; i++) {
            String p = Poison.strikePart(r);
            n[p.endsWith("leg") ? 0 : p.endsWith("arm") ? 1 : p.equals("torso") ? 2 : p.equals("groin") ? 3 : 4]++;
        }
        assertEquals(2700, n[0], 150);
        assertEquals(2700, n[1], 150);
        assertEquals(2500, n[2], 150);
        assertEquals(200, n[3], 60);
        assertEquals(1900, n[4], 150);
    }
}
