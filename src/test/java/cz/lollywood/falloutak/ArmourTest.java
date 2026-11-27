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
    void shields() {   // 05c §11 (RULED 05d rounds 7-8, 11-27)
        assertEquals(6, Data.SHIELDS.size());
        Data.Shield steel = Data.SHIELDS.get("Workshop steel shield"), comp = Data.SHIELDS.get("Composite shield"), riot = Data.SHIELDS.get("Riot shield");
        assertEquals(1012, steel.health(), "what a 6 mm Hardox plate holds: ~150 pistol rounds");
        assertTrue(comp.health() > steel.health(), "the better shield is not weaker for being lighter");
        assertEquals(7, steel.minStr(), "1 + kg / 3");
        assertTrue(riot.bulletsPass());
        assertTrue(Data.SHIELDS.get("Pavise").stands());
        assertEquals(4, comp.tier());
        assertEquals(3, Data.SHIELDS.get("Ballistic shield").tier(), "today's police shield: Tier III");
        // the front only: the three front directions of eight (67.5 degrees either side; yaw 0 faces +z)
        assertTrue(Battle.frontal(0, 0, 0, 0, 5));
        assertTrue(Battle.frontal(0, 0, 0, 2, 5));
        assertFalse(Battle.frontal(0, 0, 0, 5, 1), "beside him");
        assertFalse(Battle.frontal(0, 0, 0, 0, -5), "behind him");
        assertTrue(Battle.frontal(90, 0, 0, -5, 0), "yaw 90 faces -x");
        // a hit on it: its DT and DR, the rest goes on; what it stopped wears it
        double[] h = Battle.shieldHit(6, 0.45, 15.5, 0);
        assertEquals(5.225, h[0], 1e-9);
        assertEquals(10.275, h[1], 1e-9);
        assertEquals(0, Battle.shieldHit(10, 0.6, 7.5, 0)[0], 1e-9, "the composite stops a pistol round whole");
        assertTrue(Production.design("Shields", "Road-sign shield") != null && Production.design("Shields", "Composite shield") != null);
    }

    @Test
    void roundSevenAndEight() {   // 05d rounds 7-8 (RULED 11-27, 05c §12)
        assertEquals(57, Data.ARMOUR.size());
        // today's gear Tier III, the 2040s Tier IV (Marek: *"transport them to T3 and add new T4 equivalent"*)
        assertEquals(3, Data.ARMOUR.get("Combat Armor").tier());
        assertEquals(3, Data.ARMOUR.get("Environmental Armor Mk II").tier());
        assertEquals(4, Data.ARMOUR.get("Combat Armor 2040s").tier());
        assertTrue(Data.ARMOUR.get("Combat Armor 2040s").dt("Normal") > Data.ARMOUR.get("Combat Armor Mk II").dt("Normal"));
        assertTrue(Data.ARMOUR.get("Sealed suit 2040s").sealed() && Data.ARMOUR.get("Sealed suit 2040s").cooled());
        // the Brotherhood Armor renamed; the old name still finds it (worn or stored pieces)
        Data.Armour sf = Data.ARMOUR.get("Special forces armour");
        assertEquals(3, sf.tier());
        assertEquals(3, sf.rarity());
        assertSame(sf, Data.ARMOUR.get("Brotherhood Armor"));
        assertEquals(57, Data.ARMOUR.values().size(), "the old name is no second set");
        // the slots that had nothing alone, the improvised full armours
        assertEquals(java.util.List.of("Legs"), Data.ARMOUR.get("Chainsaw trousers").pieces());
        assertEquals(java.util.List.of("Feet"), Data.ARMOUR.get("Hiking boots").pieces());
        assertEquals(2, Data.ARMOUR.get("Forged helmet").tier());
        assertEquals(4, Data.ARMOUR.get("Forged plate harness").pieces().size());
        assertTrue(Data.ARMOUR.get("Brigandine").dt("Normal") >= Data.ARMOUR.get("Mail").dt("Normal"), "cloth < mail < brigandine < plate");
        assertTrue(Data.ARMOUR.get("Forged plate harness").dt("Normal") > Data.ARMOUR.get("Brigandine").dt("Normal"));
        // the load exoskeleton: Strength +2 for carrying only
        Character c = human();
        double cap = c.capacityKg();
        int st = c.attrs(false)[Rules.S];
        c.setArmour(set("Load exoskeleton", 100, "Legs"), false, false);
        assertEquals(2, c.carryStrength(), 1e-9);
        assertTrue(c.capacityKg() > cap, "it carries more");
        assertEquals(st, c.attrs(false)[Rules.S], "but the Strength itself is not raised");
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
        // Tactics' environmental armour: sealed, a gas filter built in; 10 kg = medium, Sneak −10 % (Tactics' −50 % dropped, 05c §3b)
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
        assertEquals(10.0, orc.wornKg(), 0.05);                                   // but still weighs (Fallout's 20 lb x 0.5, round 13)
        assertFalse(orc.complete("Combat Armor"));
        String r = orc.refit("Chest");
        assertTrue(r.contains("refitted") || r.contains("needs Crafting 75"), r);   // 05d round 8: Combat Armor is Tier III (25 a tier)
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
        c.setArmour(set("Metal Armor", 100, "Head", "Chest", "Legs"), false, false);   // 17.5 kg (round 13: 35 lb x 0.5)
        assertEquals(2, c.armourClass());
        assertEquals(ap0 - 1, c.apMax());
        assertEquals(sneak0 - 25, c.skill("Sneak"), 1e-9);
        c.setArmour(set("Power Armor", 100, "Head", "Chest", "Legs", "Feet"), false, false);   // 21 kg (42 lb x 0.5)
        assertEquals(2, c.armourClass());                                        // untrained: its full weight
        assertEquals("Power Armour Training 50 of 100 hours", c.paTaught(5));
        assertEquals("Power Armour Training learned", c.paTaught(5));
        assertEquals(1, c.armourClass());                                        // trained: half, 10.5 kg = medium
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
