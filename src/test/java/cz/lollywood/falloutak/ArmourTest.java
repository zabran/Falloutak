package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Armour (05c-ARMOUR.md; the slots 05 §6b, RULED 11-18): the EXAMPLE sets of Fallout 1, 2 and Tactics, New Vegas's condition and repair. */
class ArmourTest {
    static Character human() { return AirTest.human(); }

    static Map<String, Character.Worn> set(String name, double cond, String... slots) {
        Map<String, Character.Worn> m = new LinkedHashMap<>();
        for (String s : slots) m.put(s, new Character.Worn(name, s, cond));
        return m;
    }

    @Test
    void theSetsAndTheirPieces() {
        Data.Armour ca = Data.ARMOUR.get("Combat Armor");
        assertEquals(20, ca.ac());                                               // Fallout 2's
        assertEquals(5, ca.dt("Normal"));
        assertEquals(40, ca.dr("Normal"), 1e-9);
        assertEquals(2, ca.dt("Isolation"));                                     // Fallout's Electrical
        assertEquals(java.util.List.of("Head", "Chest", "Legs"), ca.pieces());
        assertEquals(20, ca.pieceAc("Head") + ca.pieceAc("Chest") + ca.pieceAc("Legs"));   // the shares add up to the set's AC
        Data.Armour env = Data.ARMOUR.get("Environmental Armor");                // Fallout Tactics: Energy fills Laser and Plasma
        assertEquals(55, env.dr("Heat"), 1e-9);                                  // Tactics' Energy
        assertEquals(6, ca.dt("Heat"));                                          // Fallout 2's Laser 8 and Plasma 4 in one (05c §2)
        assertEquals(40, Data.ARMOUR.get("Metal Armor").dr("Fire"), 1e-9);       // metal great against fire (Marek 11-18)
        assertTrue(env.sealed());
        assertEquals(1, Data.ARMOUR.get("Leather Jacket").pieces().size());
        assertEquals(4, Data.ARMOUR.get("Power Armor").pieces().size());
    }

    @Test
    void protectionByPartAndCondition() {
        Character c = human();
        int dt0 = c.dtAt(Rules.Part.TORSO, "Normal");
        double dr0 = c.drAt(Rules.Part.TORSO, "Normal");
        c.setArmour(set("Combat Armor", 100, "Head", "Chest", "Legs"), false, false);
        assertEquals(dt0 + 5, c.dtAt(Rules.Part.TORSO, "Normal"));
        assertEquals(dr0 + 0.40, c.drAt(Rules.Part.LEFT_ARM, "Normal"), 1e-9);   // the chest covers the arms and the hands
        assertEquals(6, c.dtAt(Rules.Part.HEAD, "Heat"));
        assertEquals(dt0 + 5, c.dtAt(Rules.Part.GROIN, "Normal"));                // the legs piece
        assertEquals(20, c.armourAc());
        // a missing piece: that part bare
        c.setArmour(set("Combat Armor", 100, "Chest"), false, false);
        assertEquals(dt0, c.dtAt(Rules.Part.LEFT_LEG, "Normal"));
        // New Vegas: full at 50 % and above, two thirds at 0
        assertEquals(1, Character.conditionShare(50), 1e-9);
        assertEquals(0.66, Character.conditionShare(0), 1e-9);
        c.setArmour(set("Combat Armor", 0, "Chest"), false, false);
        assertEquals(dt0 + 3, c.dtAt(Rules.Part.TORSO, "Normal"));               // 5 × 0.66 = 3.3
        assertEquals(dr0 + 0.264, c.drAt(Rules.Part.TORSO, "Normal"), 1e-9);
    }

    @Test
    void wholeSuits() {
        Character c = human();
        int s0 = c.attrs(false)[Rules.S], sPerm = c.attrs(true)[Rules.S];
        double rad0 = c.resistances()[1];
        c.setArmour(set("Power Armor", 100, "Head", "Chest", "Legs"), false, false);
        assertEquals(s0, c.attrs(false)[Rules.S]);                                // three pieces of four: no Strength
        c.setArmour(set("Power Armor", 100, "Head", "Chest", "Legs", "Feet"), false, false);
        assertEquals(s0, c.attrs(false)[Rules.S]);                                // untrained: dead weight (05c §3c)
        c.paTrained = true;
        assertEquals(s0 + 3, c.attrs(false)[Rules.S]);                            // the whole power armour, trained: Strength +3
        assertEquals(sPerm, c.attrs(true)[Rules.S]);                              // never read by level-up
        assertEquals(rad0 + 30, c.resistances()[1], 1e-9);
        // the hazard suit (ours): sealed, the acid's burns stay out; the big gas mask a Head piece with the filters carried
        Character h = human();
        h.setArmour(set("Hazard suit", 100, "Head", "Chest", "Legs", "Feet"), false, false);
        h.zoneGas = "acid fog";
        Hud.round(h, Part3Test.fixed(0, 4));
        assertEquals(h.hpMax(), h.hp);
        Character m = human();
        m.setArmour(set("Gas mask", 100, "Head"), true, false);
        assertTrue(m.wearing.contains("Gas mask"));
        m.zoneGas = "chlorine";
        assertTrue(m.shielded(Rules.GASES.get("chlorine")) && m.eyesShielded(Rules.GASES.get("chlorine")));
        m.setArmour(Map.of(), true, false);
        assertFalse(m.wearing.contains("Gas mask"));                              // taken off with the helmet slot
        // Tactics' environmental armour: sealed, a gas filter built in; 9.1 kg = medium, Sneak −10 % (Tactics' −50 % dropped, 05c §3b)
        Character e = human();
        double sneak = e.skill("Sneak");
        e.setArmour(set("Environmental Armor", 100, "Head", "Chest", "Legs", "Feet"), false, false);
        assertTrue(e.shielded(Rules.GASES.get("chlorine")));
        assertFalse(e.shielded(Rules.GASES.get("carbon monoxide")));
        assertEquals(sneak - 10, e.skill("Sneak"), 1e-9);
    }

    @Test
    void wearAndRepair() {
        Character c = human();
        c.setArmour(set("Leather Armor", 100, "Chest", "Legs"), false, false);
        assertEquals("", c.wearArmour(Rules.Part.TORSO, 2));                     // not beyond its DT 2: no wear
        c.wearArmour(Rules.Part.TORSO, 20);                                       // (20 − 2) × DR 0.25 = 4.5 of the chest's 80 item health
        assertEquals(100 - 4.5 / 80.0 * 100, c.armour.get("Chest").condition(), 1e-2);   // the share is stored as 0.6667
        assertEquals(100, c.armour.get("Legs").condition(), 1e-9);
        // New Vegas's combining, Fallout 3's efficiency (the wiki's two examples), capped at 100
        assertEquals(27, Rules.repairCombine(20, 10, 10), 1e-9);
        assertEquals(33, Rules.repairCombine(20, 10, 50), 1e-9);
        assertEquals(100, Rules.repairCombine(90, 80, 100), 1e-9);
        assertEquals("Engineering", Data.ARMOUR.get("Power Armor").repair());
        assertEquals("Crafting", Data.ARMOUR.get("Leather Armor").repair());
        assertEquals(65, Combat.kitRepair(50, 30, false), 1e-9);                 // the armour repair kit (the mods): +15 % at the skill 25-49
        Character h = human();
        h.setArmour(set("Hazard suit", 100, "Head", "Chest", "Legs", "Feet"), false, false);
        assertFalse(h.wearArmour(Rules.Part.TORSO, 20).isEmpty());               // DR 5 % only: (20 − 0) × 0.05 = 1 of ~50
        assertEquals(98, h.armour.get("Chest").condition(), 0.1);
    }

    @Test
    void powerArmourGauntlets() {
        // Marek 11-18, round 3: "lets in that case add there the -10% for Medical and Crafting. Others seem fine."
        Character c = human();
        double med = c.skill("Medicine"), craft = c.skill("Crafting"), cs = c.skill("Computer Science"), eng = c.skill("Engineering");
        c.paTrained = true;
        c.setArmour(set("Power Armor", 100, "Head", "Chest", "Legs", "Feet"), false, false);
        assertEquals(med - 10, c.skill("Medicine"), 1e-9);
        assertEquals(craft - 10, c.skill("Crafting"), 1e-9);
        assertEquals(cs, c.skill("Computer Science"), 1e-9);                     // "Others seem fine": no −5 there
        assertEquals(eng, c.skill("Engineering"), 1e-9);
        assertEquals(1, c.armourClass());                                         // trained: medium, no AP lost
        c.paTrained = false;
        assertEquals(med - 10, c.skill("Medicine"), 1e-9);                        // the gauntlets are on, trained or not
        for (String pa : java.util.List.of("Hardened Power Armor", "Advanced Power Armor", "Advanced Power Armor Mk II"))
            assertEquals(-10, Data.ARMOUR.get(pa).skills().get("Crafting"), 1e-9, pa);
        Character d = human();
        d.setArmour(set("Power Armor", 100, "Chest"), false, false);
        assertEquals(med, d.skill("Medicine"), 1e-9);                             // a set's effects only when worn whole
    }

    @Test
    void bodyFits() {
        assertTrue(Character.fits("standard", "large", "Head"));                 // the orc shares the human head and feet
        assertFalse(Character.fits("standard", "large", "Chest"));
        assertTrue(Character.fits("tailed", "standard", "Feet"));
        assertFalse(Character.fits("tailed", "standard", "Legs"));
        assertTrue(Character.fits(null, "standard", "Chest"));                    // old saves: standard
        assertFalse(Character.fits("standard", "huge", "Feet"));
        Character orc = AirTest.human();
        orc.applyTemplate("Orc");
        assertEquals("large", orc.fit());
        orc.setArmour(set("Combat Armor", 100, "Head", "Chest", "Legs"), false, false);
        assertEquals(3, orc.armourAc());                                          // the human helmet only (20 × 0.1667)
        assertEquals(orc.dt(), orc.dtAt(Rules.Part.TORSO, "Normal"));            // the misfit chest gives nothing
        assertEquals(9.1, orc.wornKg(), 0.05);                                    // but still weighs
        assertFalse(orc.complete("Combat Armor"));
        String r = orc.refit("Chest");
        assertTrue(r.contains("refitted") || r.contains("needs Crafting 100"), r);
        if (r.contains("refitted")) assertEquals("large", orc.armour.get("Chest").fit());
        Character ent = AirTest.human();
        ent.applyTemplate("Ent");
        ent.setArmour(set("Leather Jacket", 100, "Chest"), false, false);
        assertTrue(ent.refit("Chest").startsWith("a huge body"));
        assertEquals(25, Character.craftingFor(1));
        assertEquals(100, Character.craftingFor(4));
    }

    @Test
    void classesTrainingAndWarmth() {
        Character c = human();
        int ap0 = c.apMax();
        double sneak0 = c.skill("Sneak");
        assertEquals(0, c.armourClass());
        c.setArmour(set("Metal Armor", 100, "Head", "Chest", "Legs"), false, false);   // 15.9 kg
        assertEquals(2, c.armourClass());
        assertEquals(ap0 - 1, c.apMax());
        assertEquals(sneak0 - 25, c.skill("Sneak"), 1e-9);
        c.setArmour(set("Power Armor", 100, "Head", "Chest", "Legs", "Feet"), false, false);   // 19.1 kg
        assertEquals(2, c.armourClass());                                        // untrained: its full weight
        assertEquals("Power Armour Training 50 of 100 hours", c.paTaught(5));
        assertEquals("Power Armour Training learned", c.paTaught(5));
        assertEquals(1, c.armourClass());                                        // trained: half, 9.6 kg = medium
        assertNull(c.paHour());
        // the comfort interval (05c §6): ordinary clothes 10-25 °C, winter clothes 6-21, arctic gear −9 to 6, power armour cooled 4-45
        Character w = human();
        assertArrayEquals(new int[]{10, 25}, w.comfort());
        assertEquals(0.25, w.bootsWater(), 1e-9);
        w.setArmour(set("Winter clothes", 100, "Head", "Chest", "Legs", "Feet"), false, false);
        assertArrayEquals(new int[]{6, 21}, w.comfort());
        w.setArmour(set("Arctic gear", 100, "Head", "Chest", "Legs", "Feet"), false, false);
        assertArrayEquals(new int[]{-9, 6}, w.comfort());
        assertArrayEquals(new int[]{4, 45}, c.comfort());
        c.paTrained = false;
        assertArrayEquals(new int[]{4, 33}, c.comfort());                        // untrained: no cooling, the isolation keeps the heat in
        w.setArmour(set("Rubber boots", 100, "Feet"), false, false);
        assertEquals(0.9, w.bootsWater(), 1e-9);
        w.setArmour(set("Hazard suit", 100, "Head", "Chest", "Legs", "Feet"), false, false);
        assertEquals(1, w.bootsWater(), 1e-9);
    }
}
