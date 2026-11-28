package cz.lollywood.falloutak;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `04i` round 1 (Marek 11-27, verbatim: *"Medicines aren't items - this needs to be fixed. They need to be used, at least partially."* ·
 * *"Tiers - yes, fix that."* · *"Medicines as items - 1 dose/item seem fine. But they should be easy to stack, since most of the weight
 * is the vessel and more liquid should weight little."* · *"Today's and Czech options - all seems good."*), with the RULED catches of
 * `04g` §4b that were never built (ibuprofen's bleeding, alcohol's cold) and the hunted animal gone only at the map's edge.
 */
class MedsTest extends MockGame {

    private static ItemStack med(String name, int n) { return Items.make(Meds.id(name), n); }

    private static Random fixed(double v) { return new Random() { @Override public double nextDouble() { return v; } @Override public int nextInt(int b) { return 0; } }; }

    @Test
    void theTiersFollowTheRuleTodaysIsThreeTheFortiesFourTheOldTwo() {
        assertEquals("3", Items.ttrOf("chem:Morphine")[1]);
        assertEquals("3", Items.ttrOf("chem:Ketamine")[1]);
        assertEquals("3", Items.ttrOf("chem:Paracetamol")[1]);
        assertEquals("3", Items.ttrOf("chem:Diazepam")[1]);
        assertEquals("4", Items.ttrOf("chem:Buffout")[1]);
        assertEquals("4", Items.ttrOf("chem:Rad-X")[1]);
        assertEquals("2", Items.ttrOf("chem:Pervitin")[1]);
        assertEquals("4", Items.ttrOf("gear:Stimpak")[1]);
        assertEquals("3", Items.ttrOf("gear:Adrenaline")[1]);
        assertEquals("thing:Medicine|3|1|Penicillin", Meds.id("Penicillin"));
        assertEquals("thing:Medicine|2|2|Crude penicillin", Meds.id("Crude penicillin"));
        assertEquals("thing:Medicine|2|2|Ether", Meds.id("Ether"));   // 0.112.0, the TTR: the researched drugs R2, the dressings R1
        assertEquals("thing:Medicine|2|1|Bandage", Meds.id("Bandage"));
        for (String c : java.util.List.of("Metamizole", "Aspirin", "Tramadol", "Methadone", "Pervitin")) assertNotNull(Data.CHEMS.get(c), c);
    }

    @Test
    void aDoseIsLightAfterTheFirstTheVesselIsTheWeight() {
        String pen = Meds.id("Penicillin");
        assertEquals(0.1 + 9 * Meds.DOSE_KG, Items.weight(new ItemStack[]{med("Penicillin", 10)}), 1e-9);
        assertEquals(0.1 + 4 * Meds.DOSE_KG, Items.weight(new ItemStack[]{Items.make("chem:Morphine", 5)}), 1e-9);
        assertEquals(2 * 0.5, Items.weight(new ItemStack[]{med("Blood bag", 2)}), 1e-9, "a bulky one each its own");
        assertEquals(3.0, Items.kg(Meds.id("Oxygen cylinder")), 1e-9);
        assertEquals(0.1 * 3, Meds.stackKg(Items.thingId("Metal", 1, 1, ""), 0.1, 3), 1e-9, "anything else by the count");
        assertNotNull(pen);
    }

    @Test
    void anAntibioticCourseTakesItsDosesAndFewerAreAShortCourse() {
        PlayerMock a = player();
        a.getInventory().addItem(med("Ciprofloxacin", 3));
        a.getInventory().addItem(med("Phages", 9));
        assertEquals(java.util.Map.of("Ciprofloxacin", 3, "Phages", 9), Meds.antibiotics(a));
        Diseases.Disease dys = Diseases.ALL.get("Dysentery");
        assertEquals(7, Meds.courseDoses(dys));
        assertEquals("ciprofloxacin", Meds.family("Ciprofloxacin", dys));
        assertEquals(dys.families().get(0), Meds.family("Phages", dys), "phages are matched to the germ");
        assertEquals(1.0, Meds.strength("Ciprofloxacin", 2), 1e-9);
        assertEquals(0.5, Meds.strength("Ciprofloxacin", 7), 1e-9, "the pre-war stores at half from 5 to 10 years");
        assertEquals(0.0, Meds.strength("Penicillin", 11), 1e-9);
        assertEquals(1.0, Meds.strength("Crude penicillin", 11), 1e-9, "made after day D: full");
        assertEquals(0.7, Meds.strength("Sulfa", 1), 1e-9);
        assertEquals(3, Meds.take(a, "Ciprofloxacin", 7), "all he had: a short course");
        assertEquals(0, Meds.count(a, "Ciprofloxacin"));
        // the short course ends after its doses' days; the full one runs on
        Character s = AirTest.human(), f = AirTest.human();
        Random r = new Random(1);
        for (Character c : java.util.List.of(s, f)) {
            Diseases.Case kc = Diseases.infect(c, dys, r);
            kc.incubation = 0;   // past the incubation: a medic can give it now (it starts when recognised)
            kc.sev = 0.8;   // recognised (its second stage and on): the course starts at once
            assertFalse(Diseases.treat(c, "Dysentery", "antibiotics", "ciprofloxacin", 1).startsWith("!"));
        }
        Diseases.active(s, "Dysentery").abxDays = 1;
        for (int h = 0; h < 30 * 24; h++) {
            for (Character c : java.util.List.of(s, f)) { c.clock += 1; Diseases.hour(c, r); }
            Diseases.Case ks = Diseases.active(s, "Dysentery"), kf = Diseases.active(f, "Dysentery");
            if (ks != null && kf != null && ks.abxStart >= 0 && s.clock - ks.abxStart >= 1.5 * 24) fail("the short course still runs after 1.5 days");
            if (kf != null && kf.abxStart >= 0 && f.clock - kf.abxStart >= 2 * 24) {
                assertTrue(ks == null || ks.abxStart < 0, "the short one is over while the full one runs");
                return;
            }
        }
        fail("the full course never ran 2 days");
    }

    @Test
    void theActionsTakeTheirItems() {
        PlayerMock m = player();
        Character pat = AirTest.human();
        Random r = new Random(2);
        Diseases.Disease tet = Diseases.ALL.get("Tetanus");
        Diseases.Case k = Diseases.infect(pat, tet, r);
        assertTrue(Meds.forAct(m, tet, k, "support", pat).startsWith("! breathing support needs an oxygen cylinder"));
        m.getInventory().addItem(med("Oxygen cylinder", 1));
        assertTrue(Meds.forAct(m, tet, k, "support", pat).startsWith("an oxygen cylinder"));
        assertEquals(0, Meds.count(m, "Oxygen cylinder"));
        assertNull(Meds.forAct(m, Diseases.ALL.get("Botulism"), k, "support", pat), "botulism is bagged by hand");
        assertTrue(Meds.forAct(m, tet, k, "sedation", pat).startsWith("!"));
        m.getInventory().addItem(Items.make("chem:Diazepam", 1));
        assertEquals("a dose of diazepam", Meds.forAct(m, tet, k, "sedation", pat));
        assertTrue(Meds.forAct(m, tet, k, "antitoxin", pat).startsWith("!"));
        m.getInventory().addItem(med("Crude antiserum", 1));
        assertTrue(Meds.forAct(m, tet, k, "antitoxin", pat).startsWith("the crude antiserum"));
        m.getInventory().addItem(med("Antitoxin", 1));
        assertEquals("the antitoxin", Meds.forAct(m, tet, k, "antitoxin", pat));
        // the rabies series: 4 doses (2 if vaccinated before)
        Diseases.Disease rab = Diseases.ALL.get("Rabies");
        m.getInventory().addItem(med("Rabies vaccine", 3));
        assertTrue(Meds.forAct(m, rab, k, "vaccine", pat).startsWith("! the series needs 4"));
        m.getInventory().addItem(med("Rabies vaccine", 1));
        assertEquals("4 doses of the rabies vaccine", Meds.forAct(m, rab, k, "vaccine", pat));
        // an anaesthetic for the knife; none = the Pain as built
        assertNull(Meds.forAct(m, tet, k, "debride", pat));
        m.getInventory().addItem(med("Ether", 1));
        assertEquals("anaesthesia (ether)", Meds.forAct(m, tet, k, "debride", pat));
        m.getInventory().addItem(Items.make("chem:Ketamine", 1));
        assertEquals("anaesthesia (ketamine)", Meds.forAct(m, tet, k, "amputate", pat));
    }

    @Test
    void aDressingTakesADressingStoppingTheBleedingIsFree() {
        PlayerMock m = player();
        Character me = plugin.character(m);
        me.points[Rules.skillIndex("Medicine")] += 100;
        Character t = AirTest.human();
        t.hp = t.hpMax() - 5;
        t.bleeding = 2;
        assertTrue(Menus.firstAid(m, me, t, "his").contains("bleeding stops"));
        assertTrue(Menus.firstAid(m, me, t, "his").startsWith("! a dressing needs"));
        m.getInventory().addItem(med("Rag dressing", 1));
        m.getInventory().addItem(med("Bandage", 1));
        String r = Menus.firstAid(m, me, t, "his");
        assertTrue(r.contains("(bandage)"), r);
        assertEquals(1, Meds.count(m, "Rag dressing"), "the best one first");
    }

    @Test
    void theMedicinesTakenByOneself() {
        Character c = AirTest.human();
        Random r = new Random(3);
        c.clock = 1000;
        assertTrue(Meds.useSelf(c, "Vitamin C tablets", r).contains("vitamin C day"));
        assertTrue(c.now() - c.vitaminAt <= 24);
        double max = c.max(Rules.Stat.BLOOD);
        c.survival.put(Rules.Stat.BLOOD, max * 0.5);
        Meds.useSelf(c, "Blood bag", r);
        assertEquals(max * 0.75, c.survival.get(Rules.Stat.BLOOD), 1e-6);
        c.survival.put(Rules.Stat.POISON, 70.0);
        c.venom = 70;
        assertTrue(Meds.useSelf(c, "Antivenom", r).contains("into a muscle"));
        assertEquals(12, c.antivenomHours, 1e-9, "04d §1 (0.117.0): into a muscle, the venom out over 12 hours");
        Meds.useSelf(c, "Rehydration salts", r);
        assertEquals(-0.5, c.other("runs"), 1e-9);
        assertTrue(Meds.useSelf(c, "Tetanus vaccine", r).contains("vaccinated"));
        assertTrue(c.immune.containsKey("Tetanus"));
        assertTrue(Meds.useSelf(c, "Splint", r).startsWith("!"));
        PlayerMock p = player();
        p.getInventory().addItem(med("Vitamin C tablets", 2));
        p.getInventory().addItem(med("Penicillin", 2));
        assertEquals(java.util.Map.of("Vitamin C tablets", 2), Meds.selfUse(p));
    }

    @Test
    void theRuledCatchesAspirinBleedsAlcoholChillsMethadoneEasesTheOpioids() {
        Character c = AirTest.human();
        Random r = fixed(0.99);   // no addiction
        c.dose(Data.CHEMS.get("Aspirin"), r);
        assertEquals(1, c.other("bleeding"), 1e-9);
        c.bleeding = 2;
        double b0 = c.survival.get(Rules.Stat.BLOOD);
        Hud.bleedRound(c);
        assertEquals(b0 - 3 * c.tpl().bleedFactor(), c.survival.get(Rules.Stat.BLOOD), 1e-9, "thinned: a Blood more a round");
        c.dose(Data.CHEMS.get("Alcohol"), r);
        assertEquals(0.5, c.other("cold_gain"), 1e-9);
        // morphine's withdrawal → methadone → its dependence
        Character o = AirTest.human();
        Data.Chem mo = Data.CHEMS.get("Morphine");
        o.add(o.stageEffect(mo, 2));
        assertTrue(o.inSlot("chem:Morphine").name().endsWith("(withdrawal)"));
        String s = o.dose(Data.CHEMS.get("Methadone"), r);
        assertTrue(s.contains("Morphine's withdrawal eased"), s);
        assertTrue(o.inSlot("chem:Morphine").name().endsWith("(dependence)"));
    }

    @Test
    void aHuntedAnimalIsGoneOnlyAtTheMapsEdge() {
        Battle.Target t = new Battle.Target("Boar");
        t.field = new org.bukkit.util.BoundingBox(0, 0, 0, 128, 100, 128);
        assertFalse(Raiders.gone(t, 64, 64, 80), "far from everyone, but not at the edge");
        assertTrue(Raiders.gone(t, 1, 64, 10), "at the edge");
        Battle.Target demo = new Battle.Target("Boar");
        assertTrue(Raiders.gone(demo, 0, 0, Raiders.GONE), "no map: the distance");
        assertFalse(Raiders.gone(demo, 0, 0, Raiders.GONE - 1));
    }
}
