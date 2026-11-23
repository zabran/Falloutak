package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.63.0, `09b` round 8 (Marek 11-23: *"Should the plugin apply the three stages itself - yes."* · *"I have seen in more as paralel
 * evolutions, so that you could jump "stages". But yes, it makes sense that when evolving slowly through leathered ghoul, you get the +3
 * DT."* · *"Episodes of insanity - agreed."*): the ghoul line applied by the sheet (03b §7b).
 */
class GhoulLineTest {
    static Character ghoul() {
        Character g = Part3Test.of("Ghoul", 5, 5, 5, 5, 5, 6, 5);            // points 5: S 3, P 8, E 5, C 5, I 8, A 1, L 8
        g.survival.put(Rules.Stat.RADS, 2000.0);
        return g;
    }

    @Test
    void howFarAVeryBadResultMoves() {
        assertEquals(0, Rules.ghoulSteps(1));
        assertEquals(1, Rules.ghoulSteps(0));
        assertEquals(1, Rules.ghoulSteps(-19));
        assertEquals(2, Rules.ghoulSteps(-20));
        assertEquals(3, Rules.ghoulSteps(-40));
    }

    @Test
    void stepByStepCarriesTheHideAndTheTotalsAreMareksNumbers() {
        Character g = ghoul();
        int dt0 = g.dt(), e0 = g.attr(Rules.E, true), c0 = g.attr(Rules.C, true), i0 = g.attr(Rules.I, true), a0 = g.attr(Rules.A, true);
        assertTrue(g.ghoulAdvance(1, Part3Test.fixed(0.5, 1)).contains("ghoul → leathered"));
        assertEquals(dt0 + 3, g.dt());
        assertEquals(e0 - 1, g.attr(Rules.E, true));
        assertEquals(c0 - 2, g.attr(Rules.C, true));
        assertTrue(g.ghoulAdvance(1, Part3Test.fixed(0.5, 1)).contains("leathered → husk"));
        assertEquals(dt0 + 3, g.dt(), "the hide carries");
        assertEquals(e0 - 1, g.attr(Rules.E, true), "the leathered E −1 carries");
        assertEquals(c0 - 4, g.attr(Rules.C, true), "C −4 against the base ghoul, not −6");
        assertEquals(i0 - 2, g.attr(Rules.I, true));
        assertEquals(1.0, g.other("healing_half"), 1e-9);
        String out = g.ghoulAdvance(1, Part3Test.fixed(0.5, 1));
        assertTrue(out.contains("husk → glowing") && out.contains("a mental illness"), out);
        assertEquals(dt0 + 3, g.dt());
        assertEquals(e0 + 2, g.attr(Rules.E, true));
        assertEquals(i0 - 5, g.attr(Rules.I, true), "a ghoul's I +3 − 5 = −2 against a human");
        assertEquals(a0 + 2, g.attr(Rules.A, true));
        assertEquals(List.of("leathered", "husk", "glowing"), g.ghoulPath);
        assertEquals(1, g.effects.stream().filter(e -> "ghoul line".equals(e.source())).count(), "one effect in its slot");
        assertEquals(1, g.effects.stream().filter(e -> "glowing mind".equals(e.source())).count());
    }

    @Test
    void aVeryBadMutationMovesTheGhoulByItself() {
        Character g = ghoul();
        // expelling 300: d100 5 + (8 − 5) × 4 − 20 = −3, very bad: one stage
        assertTrue(g.mutate(300, Part3Test.fixed(0.5, 5)).contains("THE GHOUL LINE: ghoul → leathered"));
        assertEquals(List.of("leathered"), g.ghoulPath);
        // a bad result moves nothing
        assertFalse(g.mutate(100, Part3Test.fixed(0.5, 30)).contains("GHOUL LINE"));
    }

    @Test
    void aJumpSkipsTheStagesBetween() {
        Character g = ghoul();
        int dt0 = g.dt(), e0 = g.attr(Rules.E, true);
        // expelling 500: d100 1 + 12 − 40 = −27: two stages, straight to husk
        assertTrue(g.mutate(500, Part3Test.fixed(0.5, 1)).contains("ghoul → husk (a jump of 2 stages)"));
        assertEquals(List.of("husk"), g.ghoulPath);
        assertEquals(dt0, g.dt(), "never leathered: no hide");
        assertEquals(e0, g.attr(Rules.E, true));
    }

    @Test
    void theHuskHealsAtHalfSpeed() {
        Character g = ghoul();
        g.survival.put(Rules.Stat.RADS, 0.0);                                 // out of Fatal Radiation's negative rate
        g.points[Rules.skillIndex("Medicine")] = 150;
        int r0 = g.healingRate();
        assertTrue(r0 >= 4, "" + r0);
        g.ghoulAdvance(2, Part3Test.fixed(0.5, 1));
        assertTrue(g.healingRate() <= r0 / 2, g.healingRate() + " vs " + r0);
    }

    @Test
    void radiationHealsTheGlowingOneAndItKeepsNone() {
        Character g = ghoul();
        g.ghoulAdvance(3, Part3Test.fixed(0.5, 1));
        assertTrue(g.glowing());
        assertEquals(0, g.survival.get(Rules.Stat.RADS), 1e-9, "it keeps none");
        g.hp = g.hpMax() - 10;
        g.locationRads = 25;                                                  // 2.5 HP an hour
        assertEquals(0, g.hourly(Rules.Stat.RADS), 1e-9);
        Hud.hour(g);
        Hud.hour(g);
        assertEquals(g.hpMax() - 10 + 5, g.hp, 1, "10 rads = 1 HP");
        assertEquals(0, g.survival.get(Rules.Stat.RADS), 1e-9);
    }

    @Test
    void episodesOnlyForTheGlowingAndLuckHelps() {
        assertTrue(Rules.episode(12, 8));
        assertFalse(Rules.episode(13, 8));
        Character g = ghoul();
        assertNull(g.episode(Part3Test.fixed(0.5, 1), true), "a plain ghoul has none");
        g.ghoulAdvance(3, Part3Test.fixed(0.5, 1));
        String ep = g.episode(Part3Test.fixed(0.5, 1), true);
        assertTrue(ep != null && ep.startsWith("AN EPISODE OF INSANITY") && ep.contains("round"), ep);
        assertTrue(g.episode(Part3Test.fixed(0.5, 1), false).contains("minutes"));
        assertNull(g.episode(Part3Test.fixed(0.5, 50), true));
    }

    @Test
    void aGlowingOnesFurtherVeryBadResultGivesAnotherIllnessAndAHumanHasNoLine() {
        Character g = ghoul();
        g.ghoulAdvance(3, Part3Test.fixed(0.5, 1));
        assertTrue(g.ghoulAdvance(1, Part3Test.fixed(0.5, 1)).contains("already glowing: a mental illness"));
        assertEquals(2, g.effects.stream().filter(e -> "glowing mind".equals(e.source())).count());
        Character h = Part3Test.of("Human", 5, 5, 5, 5, 5, 6, 5);
        assertEquals("", h.ghoulAdvance(1, Part3Test.fixed(0.5, 1)));
        assertTrue(h.ghoulPath.isEmpty());
    }
}
