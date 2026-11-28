package cz.lollywood.falloutak;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Part 4's DRAFT 2 (RULED 11-15) in the plugin (TODO 17 of the new notes, Marek 11-28: the unbuilt rules "should be added"); the
 * reference is LollyWood-Lore's {@code _tools/fallout_survival_sim.py}, rule set "draft2", whose numbers are in 04 §3.1-§3.4.
 */
class SurvivalDraftTwoTest extends MockGame {

    @AfterEach
    void noAir() { Travel.air = Double.NaN; }

    private Character body() {
        Character c = plugin.character(player());
        c.outside = true;
        c.baseFood = c.baseWater = false;
        return c;
    }

    private static void at(Character c, Rules.Stat st, double fraction) { c.survival.put(st, fraction * c.max(st)); }

    /** Hours of the pure rule (ordinary clothes as the sim has them: 10-25 °C, no Isolation DR) until Hypothermia passes 72. */
    private static int hoursToFreeze(double airC) {
        double h = 0, c = 0;
        int t = 0;
        while (c <= 72 && t < 500) {
            double[] d = Rules.thermo(airC, 10, 25, h, c, 0, 0, 0, false);
            h += d[0];
            c += d[1];
            t++;
        }
        return t;
    }

    @Test
    void theColdKillsAsFittedToTikuisis() {
        // 04 §3.1: sitting, ordinary clothes: −20 °C in 13 h (Tikuisis 15.4), −30 in 7 (8.6), −40 in 5 (5.6), −50 in 4 (4.0), −10 over a day
        assertEquals(13, hoursToFreeze(-20));
        assertEquals(7, hoursToFreeze(-30));
        assertEquals(5, hoursToFreeze(-40));
        assertEquals(4, hoursToFreeze(-50));
        assertTrue(hoursToFreeze(-10) > 48, "−10 °C: over two days");
        assertEquals(500, hoursToFreeze(0), "0 °C never kills: it settles");
        double c = 0;
        for (int t = 0; t < 200; t++) c += Rules.thermo(0, 10, 25, 0, c, 0, 0, 0, false)[1];
        assertEquals("Minor Hypothermia", Rules.stage(Rules.Stat.HYPOTHERMIA, c, 72), "at 0 °C he settles at Minor Hypothermia");
    }

    @Test
    void theHeatSettlesAndTheSweatCostsWater() {
        double h = 0, sweat = 0;
        for (int t = 0; t < 200; t++) { double[] d = Rules.thermo(35, 10, 25, h, 0, 0, 0, 0, false); h += d[0]; sweat = d[2]; }
        assertEquals("Minor Hyperthermia", Rules.stage(Rules.Stat.HYPERTHERMIA, h, 72), "resting at 35 °C: Minor (04 §3.1)");
        // 04 §3.1, fitted to the water guidance: resting at 35 °C needs 5.8 l a day (the 2 an hour plus the sweat, 25 a litre)
        assertEquals(5.8, (2 + sweat) * 24 / 25, 0.1);
        // a dehydrated body sweats less: Critical Dehydration (level 3) half
        assertEquals(0.5 * Rules.thermo(Double.NaN, 10, 25, 20, 0, 0, 0, 0, false)[2], Rules.thermo(Double.NaN, 10, 25, 20, 0, 0, 0, 3, false)[2], 1e-9);
        Character c = body();
        c.survival.put(Rules.Stat.HYPERTHERMIA, 20.0);
        double s = c.thermo()[2];
        assertTrue(s > 1, "a hot body sweats");
        assertEquals((-2 - s) * c.tpl().waterFactor(), c.hourly(Rules.Stat.WATER), 1e-9, "the sweat comes off the water bar");
        assertTrue(c.thermo()[0] < 0, "no air set: comfortable, the heat goes");
    }

    @Test
    void theAirIsTheGmsAndTheActivityWarmsTheBody() {
        Character c = body();
        assertTrue(Double.isNaN(c.airTemp()), "no air set");
        Travel.air = 4;
        assertEquals(4, c.airTemp());
        assertTrue(c.thermo()[1] > 0, "resting at 4 °C in ordinary clothes (10-25 °C): colder");
        c.walking = true;
        assertEquals("walk", c.activity());
        assertEquals(0, Math.max(0, c.thermo()[1]), 1e-9, "walking moves the interval 8 °C down: 4 °C is in it");
        c.walking = false;
        c.outside = false;
        assertTrue(Double.isNaN(c.airTemp()), "in the base: no air (comfortable)");
    }

    @Test
    void foodGoesByActivityAndSlowsWithStarvation() {
        Character c = body();
        double f = c.tpl().foodFactor();
        assertEquals(-1 * f, c.hourly(Rules.Stat.FOOD), 1e-9, "at rest 1 an hour");
        c.walking = true;
        assertEquals(-2.1 * f, c.hourly(Rules.Stat.FOOD), 1e-9, "walking at an unknown pace: FAO's without a load, 3.2 / 1.53");
        c.walkKmh = 4.5;
        c.carriedKg = 0;
        double free = -c.hourly(Rules.Stat.FOOD) / f;
        c.walkKmh = 5.4;
        c.carriedKg = 30;
        double march = -c.hourly(Rules.Stat.FOOD) / f;
        assertEquals(Rules.walkFood(70, 0, 4.5, 1.2), free, 1e-9);
        assertEquals(3.0, free, 0.1, "unloaded at 4.5 km/h in light brush: ~3 (Pandolf)");
        assertEquals(5.4, march, 0.2, "a real march, 30 kg at 5.4 km/h: ~5.4");
        assertEquals(1.03, Rules.walkFood(70, 0, 0, 1.2), 0.01, "standing still: the resting hour");
        c.walking = false;
        c.walkKmh = 0;
        c.carriedKg = 0;
        c.working = true;
        assertEquals("light", c.activity());
        assertEquals(-1.2 * f, c.hourly(Rules.Stat.FOOD), 1e-9, "the base's work is light work, 1.2: an 8-hour day +7 % (Marek 11-28: a tiny increase)");
        assertEquals(25.6, 8 * Rules.ACT_FOOD.get("light") + 16 * Rules.ACT_FOOD.get("rest"), 1e-9);
        c.working = false;
        c.fighting = true;
        assertEquals(-2.8 * f, c.hourly(Rules.Stat.FOOD), 1e-9, "a fight's hour: hard labour 2.8 (FAO's farm work, 4.1-4.4 / 1.53)");
        c.fighting = false;
        at(c, Rules.Stat.FOOD, 0.7);
        assertEquals(-0.9 * f, c.hourly(Rules.Stat.FOOD), 1e-9, "Minor Starvation: × 0.9");
        at(c, Rules.Stat.FOOD, 0.1);
        assertEquals(-0.6 * f, c.hourly(Rules.Stat.FOOD), 1e-9, "Deadly Starvation: × 0.6");
    }

    @Test
    void theBarsAreBiggerAndAnOldSaveKeepsItsShare() {
        Character c = body();
        int e = c.attrs(true)[Rules.E];
        double sv = c.skills(true)[Rules.skillIndex("Survival")];
        assertEquals(1.5 * (75 + 5 * e + sv / 2), c.max(Rules.Stat.WATER), 1e-9);
        assertEquals(10 * (50 + 10 * e + sv / 2), c.max(Rules.Stat.FOOD), 1e-9);
        // a save from before 0.114.0 (no "bars"): its water and food were of the old bars, they keep their share
        at(c, Rules.Stat.FOOD, 0.05);
        at(c, Rules.Stat.WATER, 0.5 / 1.5);
        double food = c.survival.get(Rules.Stat.FOOD), water = c.survival.get(Rules.Stat.WATER);
        String json = Store.toJson(c).replaceAll("\"bars\":\\s*2,?", "");
        Character old = Store.fromJson(json);
        assertEquals(0, old.bars);
        old.migrate();
        assertEquals(2, old.bars);
        assertEquals(10 * food, old.survival.get(Rules.Stat.FOOD), 1e-6);
        assertEquals(1.5 * water, old.survival.get(Rules.Stat.WATER), 1e-6);
        Character now = Store.fromJson(Store.toJson(c));
        now.migrate();
        assertEquals(food, now.survival.get(Rules.Stat.FOOD), 1e-6, "a new save is not rescaled");
    }

    @Test
    void theMaximaFollowTheCurrentAttributesAndEveryBarKeepsItsShare() {
        Character c = body();
        at(c, Rules.Stat.WATER, 0.7);   // inside Minor Dehydration, off its edges
        at(c, Rules.Stat.SLEEP, 0.9);
        c.hp = Math.max(2, c.hpMax() / 2);
        c.readAttributes();   // the body as it is now (its own stages in its current attributes): the base to compare with
        double w0 = c.max(Rules.Stat.WATER), s0 = c.max(Rules.Stat.SLEEP);
        int h0 = c.hpMaxBase(), hp0 = c.hp;
        c.add(new Character.Effect("Test: Buffout's body", "chem", "chem:test", false, new int[]{2, 0, 3, 0, 0, 0, 0}, java.util.Map.of(), 3, null, 100));
        assertEquals(w0, c.max(Rules.Stat.WATER), 1e-9, "within the hour the maximum stays");
        c.clock = 5;
        Hud.hour(c);
        double w1 = c.max(Rules.Stat.WATER), s1 = c.max(Rules.Stat.SLEEP);
        assertTrue(w1 > w0 + 15 && s1 > s0 + 20, "the next hour: Endurance +3 raises the maxima (RULED 11-14) " + w0 + " -> " + w1);
        assertEquals(0.7, c.survival.get(Rules.Stat.WATER) / w1, 5 / w1, "the water keeps its share, less the hour's own (unscaled it would be ~0.63)");
        assertEquals(0.9, c.survival.get(Rules.Stat.SLEEP) / s1, 2 / s1, "the sleep keeps its share");
        assertNull(c.stageNow(Rules.Stat.SLEEP), "so no Sleep Deprivation from a stimulant (the 11-28 finding)");
        int h1 = c.hpMaxBase();
        assertTrue(h1 > h0, "Strength +2, Endurance +3: more HP");
        assertEquals((double) hp0 / h0, (double) c.hp / h1, 1.0 / h1 + 1e-9, "HP keeps its share (Marek 11-28)");
        c.effects.removeIf(e -> e.name().startsWith("Test:"));
        Hud.hour(c);
        assertEquals(w0, c.max(Rules.Stat.WATER), 1e-6, "the effect gone: the maximum back");
        assertEquals(0.7, c.survival.get(Rules.Stat.WATER) / w0, 8 / w0, "and the share still kept, less two hours' loss (kept whole it would be ~0.76)");
        assertEquals(h0, c.hpMaxBase());
    }

    @Test
    void aShortNightIsADebtThatCapsTheSleep() {
        Character c = body();
        c.clock = 0;
        for (int h = 0; h < 24; h++) {
            c.sleeping = h < 4;   // four hours of sleep, twenty awake
            Hud.hour(c);
        }
        assertEquals(4, c.sleepDebt, 1e-9, "a 4-hour night: 4 h of debt");
        at(c, Rules.Stat.SLEEP, 0.5);
        double cap = c.max(Rules.Stat.SLEEP) - c.sleepDebt;
        c.survival.put(Rules.Stat.SLEEP, cap - 1);
        c.sleeping = true;
        assertEquals(1, c.hourly(Rules.Stat.SLEEP), 1e-9, "sleep restores only to the maximum minus the debt");
        c.survival.put(Rules.Stat.SLEEP, cap + 3);
        assertEquals(0, c.hourly(Rules.Stat.SLEEP), 1e-9, "above it: nothing, but nothing taken");
        c.sleptToday = 12;
        c.sleepDay();
        assertEquals(0, c.sleepDebt, 1e-9, "twelve hours repay the four");
    }
}
