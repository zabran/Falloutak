package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.69.0, `09b` round 13 (Marek 11-23, verbatim): *""a turned ghoul keeps its template's perk rate" - no. It should change as well. Perk rate
 * should scale with how humanly you can expirience the reality."* · *"The armour sets' kg - use 0.5 as well."*
 */
class RoundThirteenTest {
    @Test
    void aTurnedGhoulTakesTheGhoulsPerkRate() {
        Character h = Part3Test.of("Human", 5, 5, 5, 5, 5, 6, 5);
        assertEquals(3, h.templatePerkRate);
        String s = h.turnGhoul();
        assertEquals(4, h.templatePerkRate, "the Ghoul template's 4");
        assertTrue(s.contains("a perk every 4 levels now (was 3)"), s);
        assertEquals(Data.TEMPLATES.get("Ghoul").perkRate(), h.templatePerkRate, "as one born a ghoul");
    }

    @Test
    void eachStageFurtherFromHumanSlowsThePerks() {
        Character g = Part3Test.of("Ghoul", 5, 5, 5, 5, 5, 6, 5);
        g.ghoulAdvance(1, Part3Test.fixed(0.5, 1));
        assertEquals(4, g.templatePerkRate, "leathered 4");
        g.ghoulAdvance(1, Part3Test.fixed(0.5, 1));
        assertEquals(4, g.templatePerkRate, "husk 4 (round 14: \"So that the slow down is not so fast.\")");
        assertTrue(g.ghoulAdvance(1, Part3Test.fixed(0.5, 1)).contains("a perk every 5 levels now"));
        assertEquals(5, g.templatePerkRate, "glowing 5");
        Character j = Part3Test.of("Ghoul", 5, 5, 5, 5, 5, 6, 5);
        j.ghoulAdvance(3, Part3Test.fixed(0.5, 1));
        assertEquals(5, j.templatePerkRate, "a jump straight to glowing: its rate all the same");
    }

    @Test
    void theOwnedPerksStayWhenTheRateSlows() {
        Character h = Part3Test.of("Human", 5, 5, 5, 5, 5, 6, 5);
        h.level = 9;   // three perks due at a rate of 3
        assertEquals(3, h.perksDue());
        h.turnGhoul();
        assertEquals(2, h.perksDue(), "9 / 4: two (none taken yet)");
    }

    @Test
    void falloutsArmourPoundsAreHalfAKilogram() {
        assertEquals(21.0, Data.ARMOUR.get("Power Armor").weightKg(), 1e-9, "Fallout 2's 42 lb × 0.5");
        assertEquals(17.5, Data.ARMOUR.get("Metal Armor").weightKg(), 1e-9, "35 lb × 0.5");
        assertEquals(4.0, Data.ARMOUR.get("Leather Armor").weightKg(), 1e-9, "8 lb × 0.5");
    }
}
