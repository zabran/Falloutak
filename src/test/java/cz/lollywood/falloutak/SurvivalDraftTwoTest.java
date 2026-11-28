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
        assertEquals(-2.5 * f, c.hourly(Rules.Stat.FOOD), 1e-9, "walking 2.5");
        c.walking = false;
        c.working = true;
        assertEquals(-4 * f, c.hourly(Rules.Stat.FOOD), 1e-9, "hard work 4");
        c.working = false;
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
