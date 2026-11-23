package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.62.0, `09b` round 7 (Marek 11-22: *""at 1,000 rads the body gets a forced mutation roll" - great idea, lets add that to PCs as well.
 * And just like before, Luck helps a lot here (hence why ghouls have bigger luck)."* · *"please, add luck inside ... The 1 in 2 seems
 * still usable."*): the crisis roll d100 + (Luck − 5) × 4 > 50 at 1 000 rads, then a forced mutation expelling 500 outside the yearly
 * roll; a failed crisis leaves Fatal Radiation to run as ruled. And the ghoul line told after a mutation (03b §7, §7b).
 */
class CrisisTest {
    @Test
    void theScoreIsOneInTwoAtLuckFiveAndLuckHelpsALot() {
        assertFalse(Rules.crisisLives(Rules.crisisScore(50, 5)));
        assertTrue(Rules.crisisLives(Rules.crisisScore(51, 5)));
        assertFalse(Rules.crisisLives(Rules.crisisScore(30, 10)));
        assertTrue(Rules.crisisLives(Rules.crisisScore(31, 10)));
        // the chance by Luck: 34 % at 1, 50 % at 5, 62 % at 8 (a ghoul from Luck 5), 70 % at 10, 82 % at 13 (a ghoul from Luck 10)
        int[][] want = {{1, 34}, {5, 50}, {8, 62}, {10, 70}, {13, 82}};
        for (int[] w : want) {
            int lives = 0;
            for (int d = 1; d <= 100; d++) if (Rules.crisisLives(Rules.crisisScore(d, w[0]))) lives++;
            assertEquals(w[1], lives, "Luck " + w[0]);
        }
    }

    @Test
    void theBodyAnswersWithAForcedMutationOutsideTheYearlyRoll() {
        Character c = Part3Test.of("Human", 5, 5, 5, 5, 5, 6, 5);          // Luck 5
        c.survival.put(Rules.Stat.RADS, 999.0);
        assertNull(c.crisis(Part3Test.fixed(0.5, 80)), "below 1 000: no crisis");
        c.survival.put(Rules.Stat.RADS, 1000.0);
        String out = c.crisis(Part3Test.fixed(0.5, 80));                    // 80 > 50: it answers; the forced roll 80 − 40 = 40, bad
        assertTrue(out.startsWith("THE CRISIS"), out);
        assertTrue(out.contains("the body answers") && out.contains("BAD"), out);
        assertEquals(500, c.survival.get(Rules.Stat.RADS), 1e-9);
        assertEquals(-1e9, c.lastMutation, 1e-9, "the yearly roll is untouched");
        assertEquals(1, c.effects.stream().filter(e -> "mutation".equals(e.source())).count());
        assertNull(c.crisis(Part3Test.fixed(0.5, 80)), "below 1 000 again: nothing to roll");
    }

    @Test
    void aFailedCrisisLeavesFatalRadiationToRunAndRollsOnceAStay() {
        Character c = Part3Test.of("Human", 5, 5, 5, 5, 5, 6, 5);
        c.survival.put(Rules.Stat.RADS, 1200.0);
        String out = c.crisis(Part3Test.fixed(0.5, 40));                    // 40 ≤ 50
        assertTrue(out.contains("does not answer") && out.contains("Fatal Radiation runs"), out);
        assertEquals(1200, c.survival.get(Rules.Stat.RADS), 1e-9);
        assertTrue(c.stages().contains(Character.FATAL_RADIATION));
        assertNull(c.crisis(Part3Test.fixed(0.5, 99)), "one crisis a stay");
        c.survival.put(Rules.Stat.RADS, 800.0);                            // RadAway took him below 1 000
        assertNull(c.crisis(Part3Test.fixed(0.5, 99)));
        c.survival.put(Rules.Stat.RADS, 1000.0);                           // back over: a new stay, a new roll
        assertTrue(c.crisis(Part3Test.fixed(0.5, 99)).contains("the body answers"));
    }

    @Test
    void luckDecidesWhereLuckFiveWouldDie() {
        Character lucky = Part3Test.of("Human", 5, 5, 5, 5, 5, 6, 10);     // Luck 10: 31 + 20 = 51
        lucky.survival.put(Rules.Stat.RADS, 1000.0);
        assertTrue(lucky.crisis(Part3Test.fixed(0.5, 31)).contains("the body answers"));
        Character plain = Part3Test.of("Human", 5, 5, 5, 5, 5, 6, 5);      // Luck 5: 31
        plain.survival.put(Rules.Stat.RADS, 1000.0);
        assertTrue(plain.crisis(Part3Test.fixed(0.5, 31)).contains("does not answer"));
    }

    @Test
    void theHourRollsTheCrisisWhenTheRadsReachAThousand() {
        Character c = Part3Test.of("Human", 5, 5, 5, 5, 5, 6, 5);
        c.survival.put(Rules.Stat.RADS, 1000.0);
        Hud.hour(c);
        assertTrue(c.crisisRolled);
        assertTrue(c.news.stream().anyMatch(n -> n.startsWith("THE CRISIS")), String.valueOf(c.news));
    }

    @Test
    void theGhoulLineIsToldAfterAMutation() {
        Character c = Part3Test.of("Human", 5, 5, 5, 5, 5, 6, 5);
        c.survival.put(Rules.Stat.RADS, 2000.0);
        assertFalse(c.mutate(100, Part3Test.fixed(0.5, 30)).contains("GHOUL"));   // the 1st, bad
        assertFalse(c.mutate(100, Part3Test.fixed(0.5, 30)).contains("GHOUL"));   // the 2nd
        assertTrue(c.mutate(100, Part3Test.fixed(0.5, 30)).contains("turns GHOUL"), "the 3rd, bad");
        Character g = Part3Test.of("Ghoul", 5, 5, 5, 5, 5, 6, 5);
        g.survival.put(Rules.Stat.RADS, 2000.0);
        assertTrue(g.mutate(500, Part3Test.fixed(0.5, 1)).contains("THE GHOUL LINE: ghoul → husk"), "a ghoul's very bad result");
        assertFalse(g.mutate(100, Part3Test.fixed(0.5, 60)).contains("ghoul line"));
    }
}
