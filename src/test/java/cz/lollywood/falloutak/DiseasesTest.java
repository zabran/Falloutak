package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static cz.lollywood.falloutak.Part3Test.fixed;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Part 4b in the plugin (Marek 11-24, verbatim, `09e` round 3: *"the plugin doesn't simulate diseases - please add that."*): the race of
 * the simulation of record (`fallout_disease_sim.py`, its table generated into `data/diseases.json`), catching by `04b` §5 with soap and
 * the vaccines, the actions of `04c` §7.
 */
class DiseasesTest {

    /** AirTest's human without luck procs (Luck 0): the scripted rolls stay the catching's (Lucky Escape: PoisonTest). */
    static Character human() { Character c = AirTest.human(); c.base[Rules.L] = 0; return c; }
    /** Runs the diseases {@code hours} game hours (the clock moves as the HUD's does); what they said. */
    private static List<String> run(Character c, int hours, Random r) {
        List<String> out = new ArrayList<>();
        for (int h = 0; h < hours; h++) {
            c.clock += 1;
            String s = Diseases.hour(c, r);
            if (s != null) out.add(s);
        }
        return out;
    }

    private static Diseases.Disease of(String n) { return Diseases.find(n); }

    @Test
    void theDataIsTheSimulationsTable() {
        assertEquals(19, Diseases.ALL.size(), "16 acute, tuberculosis and Lyme, cancer");
        Diseases.Disease t = of("Typhoid");
        assertEquals(0.039, t.rS(), 1e-12);
        assertEquals(6, t.incLo(), 1e-12);
        assertEquals(30, t.incHi(), 1e-12);
        assertEquals(4, t.stages().size());
        assertTrue(t.bacterial() && t.lethal());
        assertEquals(List.of("chloramphenicol", "ciprofloxacin", "sulfa"), t.families());
        assertEquals(3, of("Influenza").abxStage(), "only the flu's pneumonia stage takes antibiotics");
        assertTrue(of("Botulism").supportSaves());
        assertFalse(of("Wound infection").resist(), "04b §5: a wound's dirt, not the resistance");
        assertSame(of("Food poisoning"), Diseases.find("food_poisoning"));
        assertSame(of("Influenza"), Diseases.find("chřipka"));
        assertEquals(-1, of("Measles").immuneDays(), 1e-12);
        assertEquals("Pneumonia", of("Influenza").stages().get(3).name());
        assertEquals(-4, of("Influenza").stages().get(3).mods()[Rules.S]);
    }

    @Test
    void theSickNearbySpreadItAndTheBedriddenFluBringsPneumonia() {
        Character sick = human(), c = human();
        Random r = fixed(0.5, 1);
        Diseases.infect(sick, of("Common cold"), r);
        run(sick, 48, r);                                                         // it shows
        c.nearby = List.of(sick);
        Random always = fixed(0.0, 1);                                            // every roll under the chance
        Diseases.hour(c, always);
        assertNotNull(Diseases.active(c, "Common cold"), "a day's 3 % spread over its hours, caught");
        Character far = human();
        Diseases.hour(far, always);
        assertNull(Diseases.active(far, "Common cold"), "nobody near, nothing caught");
        // the flu reaching Bedridden: a pneumonia roll (5 %)
        Character f = human();
        Diseases.infect(f, of("Influenza"), r);
        f.clock += 1;
        Diseases.Case k = Diseases.active(f, "Influenza");
        k.incubation = 0;
        k.sev = 0.59;
        for (int h = 0; h < 48 && Diseases.active(f, "Pneumonia") == null; h++) { f.clock += 1; Diseases.hour(f, always); }
        assertNotNull(Diseases.active(f, "Pneumonia"));
    }

    @Test
    void aWoundRollsItsInfectionByItsClassAndTetanusOnlyWithoutTheVaccine() {
        Character c = human();
        assertNotNull(Diseases.wound(c, "gunshot", fixed(0.1, 1)), "15 %: a roll of 10 catches it");
        assertNotNull(Diseases.active(c, "Wound infection"));
        assertNull(Diseases.active(c, "Tetanus"), "the start's booster keeps tetanus off");
        Character d = human();
        assertNull(Diseases.wound(d, "cut", fixed(0.1, 1)), "a cut 2 %: a roll of 10 does not");
        Character u = human();
        u.vaccinesSet = true;                                                     // no booster
        Diseases.wound(u, "bite", fixed(0.005, 1));
        assertNotNull(Diseases.active(u, "Tetanus"), "unvaccinated: 1 %");
    }

    @Test
    void tuberculosisTakesItsSixMonthCourseAndLymeIsRecognisedAtOnce() {
        Diseases.Disease tb = of("Tuberculosis"), lyme = of("Lyme disease");
        assertEquals(180, tb.course(), 1e-9);
        assertEquals(List.of("streptomycin"), tb.families());
        assertEquals(0, lyme.abxStage());
        Character c = human();
        Random r = fixed(0.5, 1);
        Diseases.infect(c, tb, r);
        Diseases.Case k = Diseases.active(c, "Tuberculosis");
        k.incubation = 0;
        k.sev = 0.3;                                                               // Consumption: it can be recognised
        assertFalse(Diseases.treat(c, "Tuberculosis", "antibiotics", "streptomycin", 1).startsWith("!"));
        List<String> said = run(c, 24, r);
        assertTrue(said.stream().anyMatch(x -> x.contains("streptomycin course starts (180 days)")), said.toString());
        run(c, 179 * 24, r);
        assertNotNull(Diseases.active(c, "Tuberculosis"), "a few germs live until the course ends");
        run(c, 48, r);
        assertNull(Diseases.active(c, "Tuberculosis"), "the full course cures");
        Character l = human();
        Diseases.infect(l, lyme, r);
        Diseases.active(l, "Lyme disease").incubation = 0;
        Diseases.treat(l, "Lyme disease", "antibiotics", "", 1);
        assertTrue(Diseases.treat(l, "Lyme disease", "antibiotics", "tetracyclines", 1).contains("given"));
        assertTrue(run(l, 2, r).stream().anyMatch(x -> x.contains("tetracyclines course starts (21 days)")));
    }

    @Test
    void cancerRollsEachYearOnTheLifetimesRadsAndSurgeryCuresItWhileLocal() {
        Character c = human();
        c.lifetimeRads = 1000;                                                    // 2 % a year
        c.clock = 2 * Diseases.YEAR - 1;
        run(c, 1, fixed(0.03, 1));
        assertNull(Diseases.active(c, "Cancer"), "a roll of 3 against 2 %");
        c.clock = 3 * Diseases.YEAR - 1;
        run(c, 1, fixed(0.015, 1));
        Diseases.Case k = Diseases.active(c, "Cancer");
        assertNotNull(k);
        run(c, 24, fixed(0.5, 1));
        assertEquals("Cancer: Local", c.inSlot("disease:Cancer").name());
        assertEquals(List.of("surgery", "radiotherapy"), Diseases.actions(of("Cancer"), k));
        assertTrue(Diseases.treat(c, "Cancer", "surgery", "", 1).contains("cured"));
        assertNull(c.inSlot("disease:Cancer"));
    }

    @Test
    void aColdShowsAfterItsIncubationRunsItsCourseAndLeavesNoImmunity() {
        Character c = human();
        Random r = fixed(0.5, 1);
        Diseases.Case k = Diseases.infect(c, of("Common cold"), r);
        assertEquals(48, k.incubation, 1e-9);                                    // 1-3 days: the middle
        assertTrue(run(c, 47, r).isEmpty(), "the incubation is silent");
        List<String> said = run(c, 1, r);
        assertTrue(said.get(0).contains("Common cold: the first signs") && said.get(0).contains("Sniffles"), said.toString());
        assertEquals("Common cold: Sniffles", c.inSlot("disease:Common cold").name());
        run(c, 30 * 24, r);
        assertTrue(c.diseases.isEmpty(), "over within a month");
        assertNull(c.inSlot("disease:Common cold"));
        assertEquals(0, Diseases.kept(c, "Common cold"), 1e-12, "a cold leaves nothing (200 strains)");
    }

    @Test
    void theFluLeavesAYearOfImmunity() {
        Character c = human();
        Random r = fixed(0.5, 1);
        Diseases.infect(c, of("Influenza"), r);
        run(c, 40 * 24, r);
        assertTrue(c.diseases.isEmpty());
        assertEquals(1, Diseases.kept(c, "Influenza"), 1e-12);
        c.clock += 366 * 24;
        assertEquals(0, Diseases.kept(c, "Influenza"), 1e-12, "a year: the next season's strain");
    }

    @Test
    void antibioticsTurnDysentereBackFromItsSecondStageButOnlyTheRightFamily() {
        Character a = human(), b = human();
        Random r = fixed(0.5, 1);
        for (Character c : List.of(a, b)) Diseases.infect(c, of("Dysentery"), r);
        assertTrue(Diseases.treat(a, "Dysentery", "antibiotics", "ciprofloxacin", 1).contains("before it shows"), "nobody knows it yet");
        run(a, 3 * 24, r);   // the incubation
        run(b, 3 * 24, r);
        assertTrue(Diseases.treat(a, "Dysentery", "antibiotics", "penicillins", 1).startsWith("! penicillins does nothing"));
        assertFalse(Diseases.treat(a, "Dysentery", "antibiotics", "ciprofloxacin", 1).startsWith("!"));
        List<String> said = run(a, 24, r);
        assertEquals(-1, Diseases.active(a, "Dysentery").abxStart, 1e-12, "not recognised in its first stage: the course waits");
        said.addAll(run(a, 4 * 24, r));
        run(b, 5 * 24, r);
        assertTrue(said.stream().anyMatch(s -> s.contains("Dysentery recognised: the ciprofloxacin course starts")), said.toString());
        Diseases.Case ka = Diseases.active(a, "Dysentery"), kb = Diseases.active(b, "Dysentery");
        assertTrue(ka == null || ka.sev < kb.sev, "the treated one is better");
    }

    @Test
    void rabiesKillsOnceItShowsButTheVaccineInTheIncubationSaves() {
        Character a = human(), b = human();
        Random r = fixed(0.5, 1);
        Diseases.infect(a, of("Rabies"), r);
        Diseases.infect(b, of("Rabies"), r);
        assertEquals(List.of("vaccine"), Diseases.actions(of("Rabies"), Diseases.active(a, "Rabies")));
        assertTrue(Diseases.treat(a, "Rabies", "vaccine", "", 1).contains("never shows"));
        assertTrue(a.diseases.isEmpty());
        run(b, 55 * 24, r);   // 20-90 days: the middle
        List<String> said = run(b, 8 * 24, r);
        assertTrue(b.inArrest(), said.toString());
        assertTrue(said.stream().anyMatch(s -> s.contains("Rabies at its worst")), said.toString());
        assertEquals(1.0, b.inSlot("disease:Rabies").other("no_drink"), 1e-12, "the fear of water: he cannot drink");
    }

    @Test
    void catchingTakesTheResistanceSoapTheVaccinesAndAWoundsOwnDirt() {
        Character c = human();
        double res = Diseases.resistance(c);
        Random r = fixed(0.5, 1);                                                   // the roll: 50
        double pct = 60 / (1 - res);                                               // 60 after the resistance
        assertTrue(Diseases.expose(c, "Dysentery", pct, true, true, r).contains("not caught"), "soap: 42 < 50");
        assertTrue(Diseases.expose(c, "Dysentery", pct, true, false, r).contains("CAUGHT"));
        assertTrue(Diseases.expose(c, "Measles", 100, true, false, r).contains("not caught"), "MMR before day D: for life");
        assertEquals(1, Diseases.kept(c, "Measles"), 1e-12);
        assertEquals(1, Diseases.kept(c, "Tetanus"), 1e-12, "the start's booster: 10 years in full");
        c.clock += 10 * Diseases.YEAR;
        assertEquals(0.5, Diseases.kept(c, "Tetanus"), 1e-12, "then 5 years at half");
        Character w = human();
        assertTrue(Diseases.expose(w, "Wound infection", 50.5, false, false, r).contains("CAUGHT"), "not × the resistance");
    }

    @Test
    void theGraftsDrugsSlowTheImmunity() {
        Character a = human(), b = human();
        b.add(new Character.Effect("Tacrolimus", "chem", "chem:Tacrolimus", false, new int[7], Map.of("immunity", -30.0), 1e6, null, 100));
        Random r = fixed(0.5, 1);
        for (Character c : List.of(a, b)) Diseases.infect(c, of("Typhoid"), r);
        run(a, 20 * 24, r);
        run(b, 20 * 24, r);
        double ia = Diseases.active(a, "Typhoid").imm, ib = Diseases.active(b, "Typhoid").imm;
        assertEquals(0.7, ib / ia, 1e-9, "Immunity −30 %: 03e §4");
    }

    @Test
    void theSheetCommandAndTheActions() {
        Character c = human();
        Random r = fixed(0.5, 1);
        assertTrue(Diseases.command(c, new String[]{"disease"}, r).startsWith("No disease."));
        assertTrue(Diseases.command(c, new String[]{"disease", "catch", "tetanus"}, r).startsWith("Tetanus: infected"));
        run(c, 13 * 24, r);
        String shown = Diseases.command(c, new String[]{"disease"}, r);
        assertTrue(shown.startsWith("Tetanus: Lockjaw"), shown);
        assertTrue(Diseases.actions(of("Tetanus"), Diseases.active(c, "Tetanus")).containsAll(List.of("tend", "antibiotics", "support", "sedation", "antitoxin")));
        assertTrue(Diseases.command(c, new String[]{"disease", "treat", "tetanus", "antitoxin"}, r).contains("antitoxin"));
        assertTrue(Diseases.command(c, new String[]{"disease", "treat", "tetanus", "support"}, r).contains("breathing kept up"));
        assertTrue(Diseases.command(c, new String[]{"disease", "treat", "tetanus", "maggots"}, r).startsWith("! maggots does nothing"));
        assertTrue(Diseases.command(c, new String[]{"disease", "vaccine", "tick_borne_encephalitis", "0"}, r).contains("vaccinated"));
        assertTrue(Diseases.command(c, new String[]{"disease", "cure", "tetanus"}, r).contains("cured"));
        assertNull(c.inSlot("disease:Tetanus"));
        assertTrue(Diseases.command(c, new String[]{"disease"}, r).contains("Tick-borne encephalitis"));
        // a wound infection: debridement takes 0.3 off, amputation ends it
        Diseases.infect(c, of("Wound infection"), r);
        run(c, 4 * 24, r);
        double before = Diseases.active(c, "Wound infection").sev;
        Diseases.treat(c, "Wound infection", "debride", "", 1);
        assertEquals(Math.max(0.01, before - 0.3), Diseases.active(c, "Wound infection").sev, 1e-9);
        assertTrue(Diseases.treat(c, "Wound infection", "amputate", "", 1).contains("the limb is off"));
        assertNull(Diseases.active(c, "Wound infection"));
    }
}
