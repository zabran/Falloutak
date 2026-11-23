package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ghoul line applied by the sheet (03b §7b). 0.63.0, `09b` round 8 (Marek 11-23: *"Should the plugin apply the three stages itself -
 * yes."* · *"Episodes of insanity - agreed."*). 0.64.0, round 9 (Marek 11-23: *"please, make jumping the stages more punishing - the ghoul
 * missing the benefites of previous stages, only gaining the maluses. Maybe add some more tiny benefits for each stage that can gt
 * collected in this way."* · the glowing one *"should still be irradiated, up to 1000 rads, but it no longer has illness from radiation,
 * instead gaining a bonus effect, and it sheds the radiation to increase healing rate (4 rads per +1 rate for single rest)"* · *"it is a
 * radiation source and is losing rads"* · *"Cannot we just use the ghoul template that we already have?"*).
 */
class GhoulLineTest {
    static Character ghoul() {
        Character g = Part3Test.of("Ghoul", 5, 5, 5, 5, 5, 6, 5);            // points 5: S 3, P 8, E 5, C 5, I 8, A 1, L 8
        g.survival.put(Rules.Stat.RADS, 2000.0);
        return g;
    }

    static double sneak(Character c) { return c.skillBonus("Sneak", true); }

    @Test
    void howFarAVeryBadResultMoves() {
        assertEquals(0, Rules.ghoulSteps(1));
        assertEquals(1, Rules.ghoulSteps(0));
        assertEquals(1, Rules.ghoulSteps(-19));
        assertEquals(2, Rules.ghoulSteps(-20));
        assertEquals(3, Rules.ghoulSteps(-40));
    }

    @Test
    void walkingStepByStepCollectsEveryStagesHelpAndGift() {
        Character g = ghoul();
        int dt0 = g.dt(), e0 = g.attr(Rules.E, true), c0 = g.attr(Rules.C, true), i0 = g.attr(Rules.I, true), a0 = g.attr(Rules.A, true);
        double sn0 = sneak(g), pg0 = g.painGain();
        assertTrue(g.ghoulAdvance(1, Part3Test.fixed(0.5, 1)).contains("ghoul → leathered"));
        assertEquals(dt0 + 3, g.dt());
        assertEquals(e0 - 1, g.attr(Rules.E, true));
        assertEquals(c0 - 2, g.attr(Rules.C, true));
        assertEquals(10, g.other("poison_res"), 1e-9, "the leathered hide's gift");
        assertEquals(sn0 - 10, sneak(g), 1e-9, "the smell");
        assertTrue(g.ghoulAdvance(1, Part3Test.fixed(0.5, 1)).contains("leathered → husk"));
        assertEquals(dt0 + 3, g.dt(), "the hide carries");
        assertEquals(e0 - 1, g.attr(Rules.E, true), "the leathered E −1 carries");
        assertEquals(c0 - 4, g.attr(Rules.C, true), "C −4 against the base ghoul, not −6");
        assertEquals(i0 - 2, g.attr(Rules.I, true));
        assertEquals(1.0, g.other("healing_half"), 1e-9);
        assertEquals(pg0 * 0.75, g.painGain(), 1e-9, "the husk's gift: Pain gained x3/4");
        String out = g.ghoulAdvance(1, Part3Test.fixed(0.5, 1));
        assertTrue(out.contains("husk → glowing") && out.contains("a mental illness"), out);
        assertEquals(dt0 + 3, g.dt());
        assertEquals(10, g.other("poison_res"), 1e-9, "the leathered gift carries");
        assertEquals(pg0 * 0.75, g.painGain(), 1e-9, "the husk gift carries");
        assertEquals(1, g.senses(), "the glowing one's own light: the dark halved");
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
    void aJumpGivesOnlyTheHarmOfTheStageItSkips() {
        Character g = ghoul();
        int dt0 = g.dt(), e0 = g.attr(Rules.E, true), c0 = g.attr(Rules.C, true);
        double sn0 = sneak(g), pg0 = g.painGain();
        // expelling 500: d100 1 + 12 − 40 = −27: two stages, straight to husk
        assertTrue(g.mutate(500, Part3Test.fixed(0.5, 1)).contains("ghoul → husk (a jump of 2 stages)"));
        assertEquals(List.of("husk"), g.ghoulPath);
        assertEquals(dt0, g.dt(), "never leathered: no hide");
        assertEquals(0, g.other("poison_res"), 1e-9, "nor the hide's gift");
        assertEquals(e0 - 1, g.attr(Rules.E, true), "but the leathered harm: E −1");
        assertEquals(sn0 - 10, sneak(g), 1e-9, "and the smell");
        assertEquals(c0 - 4, g.attr(Rules.C, true));
        assertEquals(pg0 * 0.75, g.painGain(), 1e-9, "the husk itself was walked: its gift");
        assertTrue(g.inSlot("ghoul line").name().contains("jumped leathered"), g.inSlot("ghoul line").name());
    }

    @Test
    void straightToGlowingCarriesEveryHarmAndNoHelp() {
        Character g = ghoul();
        int dt0 = g.dt(), e0 = g.attr(Rules.E, true), c0 = g.attr(Rules.C, true), i0 = g.attr(Rules.I, true);
        double sn0 = sneak(g), pg0 = g.painGain();
        g.ghoulAdvance(3, Part3Test.fixed(0.5, 1));
        assertTrue(g.glowing());
        assertEquals(dt0, g.dt());
        assertEquals(0, g.other("poison_res"), 1e-9);
        assertEquals(pg0, g.painGain(), 1e-9, "no husk gift");
        assertEquals(1.0, g.other("healing_half"), 1e-9, "the husk's healing x1/2 is harm: it carries");
        assertEquals(sn0 - 10, sneak(g), 1e-9, "the smell carries");
        assertEquals(e0 + 2, g.attr(Rules.E, true));
        assertEquals(c0 - 5, g.attr(Rules.C, true));
        assertEquals(i0 - 5, g.attr(Rules.I, true));
        assertEquals(1, g.senses(), "its own gift it has");
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
    void theGlowingOneGathersRadsUpToAThousandWithABonusNotASickness() {
        Character g = ghoul();
        g.ghoulAdvance(3, Part3Test.fixed(0.5, 1));
        assertEquals(1000, g.survival.get(Rules.Stat.RADS), 1e-9, "the 2 000 it held kept up to the cap");
        assertTrue(g.stages().contains("Blazing (glowing)"), g.stages().toString());
        assertTrue(g.stages().stream().noneMatch(s -> s.endsWith("Radiation Poisoning")), g.stages().toString());
        assertNull(g.crisis(Part3Test.fixed(0.5, 1)), "no crisis for it");
        g.survival.put(Rules.Stat.RADS, 400.0);
        assertEquals(List.of("Charged (glowing)"), g.stages().stream().filter(s -> s.endsWith("(glowing)")).toList());
        g.survival.put(Rules.Stat.RADS, 200.0);
        assertTrue(g.stages().stream().noneMatch(s -> s.endsWith("(glowing)")));
        g.locationRads = 50;
        assertTrue(g.hourly(Rules.Stat.RADS) > 10, "its body open again: " + g.hourly(Rules.Stat.RADS));
        g.survival.put(Rules.Stat.RADS, 995.0);
        Hud.hour(g);
        assertTrue(g.survival.get(Rules.Stat.RADS) <= 1000, "never above the cap");
    }

    @Test
    void theBlazingBonusAddsStrengthEnduranceAndAnAp() {
        Character g = ghoul();
        g.ghoulAdvance(3, Part3Test.fixed(0.5, 1));
        g.survival.put(Rules.Stat.RADS, 0.0);
        int s0 = g.attr(Rules.S, false), e0 = g.attr(Rules.E, false), ap0 = g.apMax();
        g.survival.put(Rules.Stat.RADS, 1000.0);
        assertEquals(s0 + 2, g.attr(Rules.S, false));
        assertEquals(e0 + 1, g.attr(Rules.E, false));
        assertTrue(g.apMax() >= ap0 + 1, g.apMax() + " vs " + ap0);
    }

    @Test
    void itHealsAtRestByWhatItShedsFourRadsAPoint() {
        Character g = ghoul();
        g.ghoulAdvance(3, Part3Test.fixed(0.5, 1));                         // 1 000 rads
        g.locationRads = 0;
        g.sleeping = true;
        g.hp = g.hpMax() - 20;
        int hp0 = g.hp;
        assertEquals(9, g.healingRate(), "shown: (−2 + 80 / 4) x 1/2, at its rate now");
        for (int h = 0; h < 8; h++) Hud.hour(g);
        // shed 1 % an hour: 1 000 x (1 − 0.99^8) = 77.3 rads → −2 + 19 = 17, halved (the husk's) = 8
        assertEquals(1000 * Math.pow(0.99, 8), g.survival.get(Rules.Stat.RADS), 0.01);
        assertEquals(hp0 + 8, g.hp);
    }

    @Test
    void awayFromTheRadsItRunsDownRestingOrNot() {
        Character g = ghoul();
        g.ghoulAdvance(3, Part3Test.fixed(0.5, 1));
        g.survival.put(Rules.Stat.RADS, 20.0);
        g.locationRads = 0;
        g.sleeping = false;
        int hp0 = g.hp;
        assertEquals(-2, g.healingRate());
        for (int h = 0; h < 8; h++) Hud.hour(g);
        assertEquals(hp0 - 2, g.hp, "a negative rate: HP lost every 8 hours");
        assertTrue(String.valueOf(g.takeNews()).contains("running down"));
    }

    @Test
    void itIsARadiationSourceForTheBodiesNear() {
        Character g = ghoul();
        g.ghoulAdvance(3, Part3Test.fixed(0.5, 1));
        assertEquals(10, g.shedRate(), 1e-9, "1 % of 1 000 an hour");
        assertEquals(0, ghoul().shedRate(), 1e-9, "a plain ghoul sheds nothing");
        Character h = Part3Test.of("Human", 5, 5, 5, 5, 5, 6, 5);
        assertEquals(0, h.hourly(Rules.Stat.RADS), 1e-9);
        h.nearRads = g.shedRate();
        double res = Math.min(100, h.resistances()[1]) / 100;
        assertEquals(10 * (1 - res), h.hourly(Rules.Stat.RADS), 1e-9);
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

    @Test
    void theThirdBadMutationTurnsAHumanIntoAGhoulByTheTemplate() {
        Character h = Part3Test.of("Human", 5, 5, 5, 5, 5, 2, 9);
        for (int k = 0; k < 2; k++) h.effects.add(new Character.Effect("Mutation: old " + k, "mutation", "", true, new int[7], Map.of(), 0, null, 100));
        h.survival.put(Rules.Stat.RADS, 300.0);
        int s0 = h.attr(Rules.S, true), p0 = h.attr(Rules.P, true);
        // expelling 100: d100 30 + (9 − 5) × 4 = 46: bad, the third mutation
        String out = h.mutate(100, Part3Test.fixed(0.5, 30));
        assertTrue(out.contains("the body turns GHOUL: the template is now Ghoul (was Human"), out);
        assertEquals("Ghoul", h.template);
        assertEquals(s0 - 2, h.attr(Rules.S, true));
        assertEquals(p0 + 3, h.attr(Rules.P, true));
        assertEquals(1, h.attr(Rules.A, true), "A 2 − 4 kept at 1");
        assertTrue(out.contains("Agility kept at 1"), out);
        assertEquals(40, 100 * h.baseTpl().radRes(), 1e-9);
        // and the ghoul's next very bad result moves it along the line
        h.survival.put(Rules.Stat.RADS, 500.0);
        assertTrue(h.mutate(500, Part3Test.fixed(0.5, 1)).contains("THE GHOUL LINE"));
    }

    @Test
    void aSyntheticBodyNeverTurns() {
        Character r = Part3Test.of("Android", 5, 5, 5, 5, 5, 5, 5);
        assertEquals("a synthetic body cannot turn ghoul", r.turnGhoul());
        assertEquals("Android", r.template);
    }
}
