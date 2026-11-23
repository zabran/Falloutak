package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.70.0, `09b` round 14 (Marek 11-23, verbatim): *"the 18-dial mutation catalogue - this seems fine, just attributes should be 7 options
 * instead additional of d7 roll."* · *"each stage slows it further - husk 4, glowing 5. So that the slow down is not so fast."*
 */
class RoundFourteenTest {
    static Data.Step step(String dial, int sign, int size) { return new Data.Step(dial, sign, size, dial.equals("skill") ? "Medicine" : ""); }

    static Data.Gene plainAfter(Data.Step s) { return Data.variant(null, "Skin", 1, List.of(s)); }

    @Test
    void theSevenAttributesAreDialsOfTheirOwn() {
        assertEquals(24, Data.DIALS.size(), "the 17 others + the 7 attributes");
        for (String a : Rules.SPECIAL) assertTrue(Data.DIALS.contains(a), a);
        assertFalse(Data.DIALS.contains("an attribute"));
        // a bad roll on the 6th dial is Agility −1, no second roll
        Data.Rolled r = Data.rollMutation("bad", RoundTwelveTest.seq(5));
        assertEquals(List.of(new Data.Step("Agility", -1, 1, "")), r.steps());
    }

    @Test
    void eachGradeTurnsItsSteps() {
        assertEquals(List.of(new Data.Step("Strength", 1, 1, "")), Data.rollMutation("good", RoundTwelveTest.seq(0)).steps());
        List<Data.Step> n = Data.rollMutation("neutral", RoundTwelveTest.seq(0, 0)).steps();
        assertEquals(2, n.size());
        assertEquals(1, n.get(0).sign());
        assertEquals(-1, n.get(1).sign());
        assertNotEquals(n.get(0).dial(), n.get(1).dial(), "the bad step on another dial");
        for (int d = 0; d < 10; d++) {
            Data.Rolled r = Data.rollMutation("very bad", RoundTwelveTest.seq(d, 7));
            String want = d <= 1 ? "Tumour" : d == 2 ? "Withered Limb" : d == 3 ? "One Eye Blind" : null;
            assertEquals(want, r.bodyChange(), "d10 " + (d + 1));
            if (want == null) assertEquals(List.of(new Data.Step(Data.DIALS.get(7), -1, 2, "")), r.steps(), "a bad step at double");
        }
        Data.Step sk = Data.rollMutation("good", RoundTwelveTest.seq(Data.DIALS.indexOf("skill"), 2)).steps().get(0);
        assertEquals(Rules.SKILLS.get(2).name(), sk.skill(), "the skill dial picks a base skill");
    }

    @Test
    void everyDialChangesTheGeneBothWays() {
        Data.Gene plain = Data.plain("Skin");
        for (String d : Data.DIALS)
            for (int sign : new int[]{1, -1}) {
                Data.Gene g = plainAfter(step(d, sign, 1));
                boolean changed = !java.util.Arrays.equals(g.mods(), plain.mods()) || g.hp() != plain.hp() || g.dt() != plain.dt()
                        || g.dr() != plain.dr() || g.rad() != plain.rad() || g.poison() != plain.poison() || g.iso() != plain.iso()
                        || g.food() != plain.food() || g.water() != plain.water() || g.sleep() != plain.sleep() || g.heal() != plain.heal()
                        || g.bleed() != plain.bleed() || g.pain() != plain.pain() || g.senses() != plain.senses() || !g.sun().equals(plain.sun())
                        || !g.skills().equals(plain.skills()) || g.melee() != plain.melee() || g.fall() != plain.fall() || g.window() != plain.window();
                assertTrue(changed, d + " " + sign);
                assertEquals(4 * sign, g.load(), d + ": good +4, bad −4");
            }
    }

    @Test
    void theStepsAreAT1GenesSizeAndAVeryBadOneDouble() {
        assertEquals(0.9, plainAfter(step("food", 1, 1)).food(), 1e-9);
        assertEquals(1.15 * 1.15, plainAfter(step("food", -1, 2)).food(), 1e-9);
        assertEquals(-6, plainAfter(step("HP max", -1, 2)).hp());
        assertEquals(-2, plainAfter(step("Luck", -1, 2)).mods()[Rules.L]);
        assertEquals(-8, plainAfter(step("Luck", -1, 2)).load(), "very bad −8");
        assertEquals(0.10, plainAfter(step("rad res", 1, 1)).rad(), 1e-9);
        assertEquals(1, plainAfter(step("dark", 1, 1)).senses(), "the dark halved");
        assertEquals("pain", plainAfter(step("dark", -1, 1)).sun(), "the sun hurts");
        assertEquals("hp", plainAfter(step("dark", -1, 2)).sun(), "the sun burns");
        assertEquals(10.0, plainAfter(step("skill", 1, 1)).skills().get("Medicine"), 1e-9);
        assertEquals("Skin M1", plainAfter(step("DT", 1, 1)).name());
        assertEquals("food need × 1.32", step("food", -1, 2).text());
    }

    @Test
    void aGoodDialReachesTheCharacter() {
        Character c = Part3Test.of("Human", 5, 5, 5, 5, 5, 5, 5);
        double med0 = c.skill("Medicine"), heal0 = c.tpl().healFactor();
        int hp0 = c.hpMax();
        c.applySteps("Skin", List.of(step("skill", 1, 1)));
        c.applySteps("Frame", List.of(step("HP max", 1, 1)));
        c.applySteps("Metabolism", List.of(step("healing", 1, 1)));
        assertEquals(med0 + 10, c.skill("Medicine"), 1e-9);
        assertEquals(hp0 + 3, c.hpMax());
        assertEquals(heal0 * 1.25, c.tpl().healFactor(), 1e-9);
    }

    @Test
    void theStagesPerkRatesAre4_4_4_5() {
        assertArrayEquals(new int[]{4, 4, 5}, Rules.GHOUL_STAGE_PERK_RATE);
    }
}
