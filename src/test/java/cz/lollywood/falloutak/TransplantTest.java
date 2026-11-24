package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static cz.lollywood.falloutak.Part3Test.fixed;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The transplant (Marek 11-24, verbatim: *"The transplant operation itself still isn't built. - please, look into that."*): a donor's
 * organ as a T II graft (03e §4; RULED `09d` round 5), the limb a T II with a worse rejection and the made drug Tier III (RULED `09e`
 * round 2, §6): the blood match, the place's slot, the operation, the monthly rejection on the pre-war or the made drug, two weeks
 * without the drugs, the arm's nerves regrowing.
 */
class TransplantTest {
    private static Character patient(String blood) {
        Character c = AirTest.human();
        c.blood = blood;
        return c;
    }

    private static Character.Effect only(Character c) {
        assertEquals(1, c.grafts().size(), c.effects.toString());
        return c.grafts().get(0);
    }

    @Test
    void theBloodMatchesByAboAndEachOrganTakesItsPlacesSlot() {
        Random r = new Random(1);
        assertNotNull(patient("O+").graft("kidney", "A+", null, r));                     // O takes only from O
        Character a = patient("A+");
        assertNull(a.graft("kidney", "O-", null, r));                                   // O gives to all, Rh no bar
        assertEquals("implant:gut#1", only(a).slot());
        assertTrue(only(a).name().contains("blood O-"), only(a).name());
        assertNotNull(a.graft("liver", "A+", null, r), "the gut's one slot: one thing a place (03e §3)");
        assertNull(patient("AB-").graft("heart", "B+", null, r));                       // AB takes from all
        assertNull(patient("B+").graft("corneas", "A+", null, r));                      // the corneas need no match
        assertNotNull(patient("A+").graft("spleen", "A+", null, r));
        // the corneas mend a blind eye
        Character b = patient("A+");
        b.add(new Character.Effect("One Eye Blind", "mutation", "", true, new int[7], java.util.Map.of(), 0, null, 100));
        assertNull(b.graft("corneas", "", null, r));
        assertTrue(b.effects.stream().noneMatch(e -> e.name().startsWith("One Eye Blind")));
    }

    @Test
    void anArmReplacesAMissingOneAndItsUseGrowsOverMonths() {
        Random r = new Random(1);
        Character c = patient("A+");
        assertEquals("the left arm is whole", c.graft("arm", "A+", "left_arm", r));
        assertNull(c.fitLimb("left_arm", "hook"));
        assertTrue(c.graft("arm", "A+", "left_arm", r).startsWith("take off the Hook"), "a prosthesis comes off first");
        assertNull(c.fitLimb("left_arm", "none"));
        assertNull(c.fitLimb("left_arm", "missing"));
        assertNull(c.graft("arm", "A+", "left_arm", r));
        assertEquals(20, c.efficiency("left_arm"), 1e-9);
        c.dose(Data.CHEMS.get("Tacrolimus"), fixed(0.99, 1));
        Random never = fixed(0.99, 100);
        for (int h = 0; h < 135 * 24; h++) assertNull(c.graftHour(never));
        assertEquals(55, c.efficiency("left_arm"), 1e-6, "halfway: nerves ~1 mm a day");
        for (int h = 0; h < 200 * 24; h++) c.graftHour(never);
        assertEquals(90, c.efficiency("left_arm"), 1e-6, "never quite natural");
    }

    @Test
    void theMonthsRejectionRollIsThePreWarRateOrTheMadeDrugsWorseOne() {
        // the pre-war tablets: 2 % an organ; a roll of 2 rejects at the month's end, a 3 does not
        for (int roll : new int[]{2, 3}) {
            Character c = patient("A+");
            assertNull(c.graft("kidney", "A+", null, new Random(1)));
            c.dose(Data.CHEMS.get("Tacrolimus"), fixed(0.99, 1));
            assertEquals(2, c.immuno(), 1e-9);
            assertEquals(-30, c.other("immunity"), 1e-9, "Immunity −30 % while taken (03e §4)");
            String said = null;
            for (int h = 0; h < 720; h++) said = c.graftHour(fixed(0.99, roll));
            assertEquals(roll == 2, said != null && said.contains("REJECTED"), String.valueOf(said));
            assertEquals(roll == 2 ? 0 : 1, c.grafts().size());
            if (roll == 2) assertTrue(said.contains("weeks to live without another kidney"), said);
        }
        // the made drug: 5 % an organ, 10 % an arm
        Character m = patient("A+");
        assertNull(m.graft("heart", "A+", null, new Random(1)));
        m.dose(Data.CHEMS.get("Azathioprine"), fixed(0.99, 1));
        assertEquals(1, m.immuno(), 1e-9);
        String said = null;
        for (int h = 0; h < 720; h++) said = m.graftHour(fixed(0.99, 5));
        assertTrue(said != null && said.contains("the made drug") && said.contains("death roll"), String.valueOf(said));
        Character arm = patient("A+");
        assertNull(arm.fitLimb("right_arm", "missing"));
        assertNull(arm.graft("arm", "A+", "right_arm", new Random(1)));
        arm.dose(Data.CHEMS.get("Azathioprine"), fixed(0.99, 1));
        for (int h = 0; h < 720; h++) said = arm.graftHour(fixed(0.99, 10));
        assertTrue(said.contains("REJECTED"), said);
        assertEquals("injury", arm.inSlot("limb:right_arm").source(), "the arm is missing again");
    }

    @Test
    void twoWeeksWithoutTheDrugsRejectButNeverTheCorneas() {
        Character c = patient("A+");
        assertNull(c.graft("lungs", "A+", null, new Random(1)));
        assertNull(c.graft("corneas", "", null, new Random(1)));
        Random never = fixed(0.99, 100);
        for (int h = 0; h < 335; h++) assertNull(c.graftHour(never));
        String said = c.graftHour(never);
        assertTrue(said != null && said.contains("two weeks without"), String.valueOf(said));
        assertEquals("implant:eyes#1", only(c).slot(), "the corneas stay");
        for (int h = 0; h < 2000; h++) c.graftHour(never);
        assertEquals(1, c.grafts().size());
        // a month's course resets the clock: a day without it and then the drug again is no rejection
        Character d = patient("A+");
        assertNull(d.graft("kidney", "A+", null, new Random(1)));
        for (int h = 0; h < 300; h++) d.graftHour(never);
        d.dose(Data.CHEMS.get("Tacrolimus"), fixed(0.99, 1));
        for (int h = 0; h < 300; h++) assertNull(d.graftHour(never));
        assertEquals(1, d.grafts().size());
    }

    @Test
    void theOperationIsTheImplantsChanceAndItsFailures() {
        Character c = patient("A+");
        String r = Part3.transplant(c, new String[]{"transplant", "kidney", "B+", "80", "clinic", "tier3"}, fixed(0.5, 1));
        assertTrue(r.startsWith("! the donor's blood group B+ does not match A+"), r);
        assertTrue(c.grafts().isEmpty());
        r = Part3.transplant(c, new String[]{"transplant", "kidney", "O+", "80", "clinic", "tier3"}, fixed(0.5, 64));
        assertTrue(r.contains("80 × 1.0 × 1.0 × 0.8 = 64") && r.contains("SUCCESS"), r);
        assertEquals(1, c.grafts().size());
        Character f = patient("A+");
        int hp = f.hp;
        r = Part3.transplant(f, new String[]{"transplant", "kidney", "A+", "80", "clinic", "tier3"}, fixed(0.5, 65));
        assertTrue(r.contains("FAILED"), r);
        assertTrue(f.grafts().isEmpty());
        assertTrue(f.hp < hp);
        // an arm: the microsurgery's 0.6, the side named
        Character a = patient("A+");
        assertNull(a.fitLimb("left_arm", "missing"));
        r = Part3.transplant(a, new String[]{"transplant", "arm", "A+", "100", "prewar", "tier4", "left_arm"}, fixed(0.5, 1));
        assertTrue(r.contains("100 × 1.2 × 1.3 × 0.6 = 93.6") && r.contains("SUCCESS"), r);
        assertEquals("graft", a.inSlot("limb:left_arm").source());
        assertTrue(Part3.transplant(a, new String[]{"transplant"}, fixed(0.5, 1)).startsWith("! /sheet transplant"));
    }

    @Test
    void theDrugsTheirCardAndTheDonorArmAreInTheData() {
        for (String n : new String[]{"Tacrolimus", "Azathioprine"}) {
            Data.Chem ch = Data.CHEMS.get(n);
            assertNotNull(ch, n);
        }
        Production.Recipe make = Production.RECIPES.get("make-azathioprine");
        assertNotNull(make);
        assertEquals("Immunosuppressants", make.research);
        assertEquals("Chems", make.makes.get(0).type);
        assertEquals(3, make.makes.get(0).tier);
        assertTrue(Loot.ORGANS.stream().anyMatch(o -> o.name().equals("arm")));
        for (Loot.Organ o : Loot.ORGANS)
            if (!o.name().equals("skin")) assertNotNull(Character.graftOf(o.name()), o.name() + ": every field organ but skin can go in");
    }
}
