package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The commands' and rolls' logic that had no test (JaCoCo, 11-18; Marek: *"I would like to test only UX and UI in game, so try to
 * testing everything you can test outside with unit tests."*): implants, surgery, revival, chems, limbs, the ears' rolls, a blank
 * character, the texts of crits and failures.
 */
class CommandsTest {
    static Character human() { return AirTest.human(); }

    @Test
    void implantsFitAndRemove() {
        Character c = human();
        assertTrue(Part3.implant(c, new String[]{"implant"}).startsWith("Implants"));
        assertTrue(Part3.implant(c, new String[]{"implant", "remove"}).startsWith("!"));
        assertTrue(Part3.implant(c, new String[]{"implant", "Nonsense"}).startsWith("! no implant"));
        assertTrue(Part3.implant(c, new String[]{"implant", "Artificial_Heart", "V"}).startsWith("! the tier"));
        String fit = Part3.implant(c, new String[]{"implant", "Artificial_Heart", "IV"});
        assertTrue(fit.startsWith("Artificial Heart fitted (Tier IV"), fit);
        assertTrue(c.hasPump());
        // taking it out at Medicine 30: max(0, 50 − 30 / 3) = 40 % of its condition lost
        String out = Part3.implant(c, new String[]{"implant", "remove", "heart", "30"});
        assertTrue(out.contains("loses 40 %") && out.contains("60 % left"), out);
        assertTrue(Part3.implant(c, new String[]{"implant", "remove", "heart"}).startsWith("! no implant there"));
        assertEquals("", Part3.harm(c, 3));
        assertTrue(Part3.harm(c, 2).contains("once a month"));
        assertTrue(Part3.harm(c, 1).contains("twice a month"));
        assertEquals("today's prototype: an infection roll at the cut class twice a month", Rules.tierText(1));
        assertEquals("pre-war, the best", Rules.tierText(4));
    }

    @Test
    void surgeryRollsThenFits() {
        Character c = human();
        assertTrue(Part3.surgery(c, new String[]{"surgery"}, Part3Test.fixed(0, 1)).startsWith("!"));
        assertTrue(Part3.surgery(c, new String[]{"surgery", "Artificial_Heart", "x", "clinic", "tier3"}, Part3Test.fixed(0, 1)).startsWith("! Medicine"));
        String ok = Part3.surgery(c, new String[]{"surgery", "Artificial_Heart", "80", "clinic", "tier3"}, Part3Test.fixed(0, 1));
        assertTrue(ok.contains("rolled 1: SUCCESS, fitted."), ok);
        Character d = human();
        int hp = d.hp;
        String bad = Part3.surgery(d, new String[]{"surgery", "Artificial_Heart", "80", "clinic", "tier3"}, Part3Test.fixed(0, 100));
        assertTrue(bad.contains("FAILED"), bad);
        assertTrue(bad.contains("RIDICULOUS") || d.hp < hp, bad);                // minor and catastrophic cost HP
    }

    @Test
    void revival() {
        Character c = human();
        assertTrue(Part3.revive(c, new String[]{"revive"}, Part3Test.fixed(0, 1)).startsWith("!"));
        assertTrue(Part3.revive(c, new String[]{"revive", "60", "2", "nothing"}, Part3Test.fixed(0, 1)).startsWith("! numbers"));
        c.hp = -20;
        String ok = Part3.revive(c, new String[]{"revive", "60", "2", "defibrillator"}, Part3Test.fixed(0, 1));
        assertTrue(ok.contains("the heart beats again"), ok);
        assertEquals(Rules.deathFloor(c.attrs(false)), c.hp);                   // back at −Endurance
        assertTrue(Part3.revive(human(), new String[]{"revive", "60", "600", "hands"}, Part3Test.fixed(0, 1)).startsWith("! FINAL DEATH"));
        assertTrue(Part3.revive(human(), new String[]{"revive", "60", "2", "hands"}, Part3Test.fixed(0, 100)).contains("failed"));
    }

    @Test
    void chemsAndTheClock() {
        Character c = human();
        assertTrue(Part3.dose(c, new String[]{"dose"}, Part3Test.fixed(0, 50)).startsWith("Chems"));
        assertTrue(Part3.dose(c, new String[]{"dose", "Nonsense"}, Part3Test.fixed(0, 50)).startsWith("! no chem"));
        assertEquals("nothing timed runs", Part3.timed(c));
        String name = Data.CHEMS.keySet().iterator().next();
        String r = Part3.dose(c, ("dose " + name).split(" "), Part3Test.fixed(0.99, 100));
        assertFalse(r.startsWith("! no chem"), r);
        assertNotEquals("nothing timed runs", Part3.timed(c));
    }

    @Test
    void limbsAndMutations() {
        Character c = human();
        assertTrue(Part3.limb(c, new String[]{"limb"}).startsWith("!"));
        String leg = Part3.limb(c, new String[]{"limb", "left_leg", "peg"});
        assertTrue(leg.startsWith("left leg: peg") && leg.contains("movement ×"), leg);
        String arm = Part3.limb(c, new String[]{"limb", "right_arm", "hook"});
        assertTrue(arm.startsWith("right arm: hook") && arm.contains("to hit with it"), arm);
        assertTrue(Part3.limb(c, new String[]{"limb", "left_leg", "none"}).contains("natural again"));
        assertTrue(Part3.mutate(c, new String[]{"mutate"}, Part3Test.fixed(0, 50)).startsWith("! /sheet mutate"));
        assertTrue(Part3.mutate(c, new String[]{"mutate", "lots"}, Part3Test.fixed(0, 50)).startsWith("! a number"));
        assertFalse(c.rollMutation(Part3Test.fixed(0, 50)).isEmpty());
    }

    @Test
    void earRolls() {
        Character c = human();
        assertEquals("", c.oneEarRoll(0, Part3Test.fixed(0, 1)));
        assertTrue(c.oneEarRoll(10, Part3Test.fixed(0, 1)).contains("eardrum bursts"));
        Character d = human();
        assertTrue(d.lastingRoll(5, Part3Test.fixed(0, 100)).startsWith("the hearing holds"));
        assertTrue(d.lastingRoll(5, Part3Test.fixed(0, 1)).startsWith("one ear's hearing is lost for good"));
    }

    @Test
    void paralysedByPainCannotAct() {
        // Marek 11-19: "I should have only meant "can't act". ... Maybe it would be fine to just remove 99 AP instead."
        Character c = human();
        int agility = c.attrs(false)[Rules.A];
        c.survival.put(Rules.Stat.PAIN, 95.0);
        assertTrue(c.stages().contains("Paralyzed by Pain"));
        assertFalse(c.ignoresPain());
        assertEquals(0, c.apMax());                                               // no AP: no action
        assertEquals(agility, c.attrs(false)[Rules.A]);                           // Agility (so AC) stays
        assertEquals(-99, Data.EFFECTS.get("Paralyzed by Pain").other().get("ap"), 1e-9);
        c.survival.put(Rules.Stat.PAIN, 0.0);
        assertTrue(c.apMax() > 0);
    }

    @Test
    void aBlankCharacter() {
        Character c = Character.blank("Bob", "Human");
        assertEquals("Bob", c.name);
        assertEquals(c.hpMax(), c.hp);
        assertEquals(c.max(Rules.Stat.WATER), c.survival.get(Rules.Stat.WATER), 1e-9);
        assertEquals(0, c.survival.get(Rules.Stat.PAIN), 1e-9);
        int sum = 0;
        for (int i = 0; i < 7; i++) { assertTrue(c.base[i] >= 1); sum += c.base[i]; }
        assertTrue(sum > 7);
    }

    @Test
    void theTextsOfCritsAndFailures() {
        Combat.CritEffects all = new Combat.CritEffects(true, true, true, true, true, true, true, true, true, "electricity");
        assertEquals(10, all.names().size());
        assertEquals("the heart stops (electricity)", all.names().get(0));
        assertEquals(List.of(), new Combat.CritEffects(false, false, false, false, false, false, false, false, false).names());
        Combat.Failure f = new Combat.Failure(true, true, true, true, true, true, true, true, true, true, true);
        assertEquals(11, f.names().size());
    }
}
