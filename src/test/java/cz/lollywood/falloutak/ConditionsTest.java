package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** 04b §3c's counters and §5's last exposures, 04c's painkillers, the tending check and the sanatorium (Marek 11-24: "complete the illnesses"). */
class ConditionsTest {
    static Random always(double v) { return MealsTest.always(v); }

    @Test
    void scurvyCountsTheShelterDaysWithoutFreshFood() {
        Character c = DiseasesTest.human();
        for (int d = 0; d < 29; d++) assertNull(Conditions.scurvyDay(c, false, always(0.5)));
        String m = Conditions.scurvyDay(c, false, always(0.5));
        assertTrue(m.startsWith("Minor Scurvy"), m);
        assertEquals(-1, c.effects.stream().filter(e -> e.slot().equals("condition:Scurvy")).findFirst().orElseThrow().mods()[2], "Endurance −1");
        assertEquals(27, scurvyAfter(c, true), 1e-9, "a vitamin C day: −3");
        c.scurvy = 59;
        Conditions.scurvyDay(c, false, always(0.5));
        assertTrue(c.other("no_healing") > 0, "Advanced: wounds stop healing");
        c.scurvy = 149;
        assertTrue(Conditions.scurvyDay(c, false, always(0.5)).contains("Scurvy"), "dead on day 150");
        assertTrue(c.dead || c.inArrest());
        // in the base: a day on Tier II food counts up, a Tier III portion counts down
        Production pr = new Production();
        pr.build("Reactor", 4, "Reactor");
        Base base = new Base(null);
        base.addUnits("Water", 3, 1, 50);
        base.addUnits("Food", 2, 1, 50);
        Character d = DiseasesTest.human();
        Meals.baseDay(d, pr, base, always(0.99));
        assertEquals(1, d.scurvy, 1e-9, "Tier II only");
        base.addUnits("Food", 3, 1, 50);
        Meals.baseDay(d, pr, base, always(0.99));
        assertEquals(0, d.scurvy, 1e-9, "Tier III eaten");
    }

    static double scurvyAfter(Character c, boolean vitaminC) { Conditions.scurvyDay(c, vitaminC, always(0.5)); return c.scurvy; }

    @Test
    void trenchFootByTheWetTheBootsAndTheSeason() {
        Character c = DiseasesTest.human();
        c.outside = true;
        double w = 1 - c.bootsWater();
        for (int h = 0; h < 30; h++) Conditions.trenchHour(c, 1, "rain", always(0.5));
        assertEquals(30 * (2 * w - 1), c.trench, 1e-9, "spring rain in shoes: + w − (1 − w) an hour");
        assertTrue(Conditions.show(c).contains("Immersion foot") || Conditions.show(c).contains("Severe"), Conditions.show(c));
        double t = c.trench;
        Conditions.trenchHour(c, 2, "rain", always(0.5));
        assertEquals(t - 1, c.trench, 1e-9, "summer: out of the 0-15 °C band");
        Conditions.trenchHour(c, 0, "clear", always(0.5));
        assertEquals(t - 2, c.trench, 1e-9, "a frozen winter day");
        c.outside = false;
        Conditions.trenchHour(c, 1, "rain", always(0.5));
        assertEquals(t - 3, c.trench, 1e-9, "under the base's roof");
        assertEquals(0.5, Conditions.wetness(true, "snow"), 1e-9);
        assertTrue(Conditions.trenchBand(0, "fog") && !Conditions.trenchBand(0, "snow"));
    }

    @Test
    void scabiesOnlyByContactAndTheOintment() {
        Character c = DiseasesTest.human(), x = DiseasesTest.human();
        x.scabies = true;
        c.nearby = List.of(x);
        assertNotNull(Conditions.scabiesHour(c, always(0.0)));
        assertTrue(c.scabies);
        assertTrue(Conditions.ointment(c).contains("gone"));
        assertFalse(c.scabies);
        c.nearby = List.of();
        assertNull(Conditions.scabiesHour(c, always(0.0)), "nobody infested nearby");
    }

    @Test
    void tuberculosisLiesLatentThenFallsIll() {
        Character c = DiseasesTest.human(), x = DiseasesTest.human();
        Diseases.Case k = Diseases.infect(x, Diseases.ALL.get("Tuberculosis"), always(0.5));
        k.incubation = 0;
        c.nearby = List.of(x);
        Conditions.tbHour(c, always(0.0));
        assertTrue(c.tbLatent >= 0, "a cougher in the room: latent");
        assertNull(Diseases.active(c, "Tuberculosis"), "latent is not ill");
        assertEquals(0.05 / (2 * Diseases.YEAR), Conditions.TB_EARLY, 1e-15);
        Conditions.tbHour(c, always(0.0));
        assertNotNull(Diseases.active(c, "Tuberculosis"), "falls ill (its roll)");
        assertEquals(-1, c.tbLatent, 1e-9);
        // the sanatorium: the immunity × 1.2
        Diseases.Case t = Diseases.active(c, "Tuberculosis");
        assertEquals(1, t.incubation, 1e-9, "the latent years were its incubation: the signs next hour");
        t.incubation = 0;
        assertTrue(Diseases.actions(Diseases.ALL.get("Tuberculosis"), t).contains("sanatorium"));
    }

    @Test
    void theGiantTicksBiteCarriesTheTickDiseases() {
        Character c = DiseasesTest.human();
        String m = Diseases.tickBite(c, always(0.0));
        assertTrue(m.contains("CAUGHT"), m);
        assertNotNull(Diseases.active(c, "Lyme disease"));
        assertNull(Diseases.tickBite(DiseasesTest.human(), always(0.99)), "most bites carry nothing");
    }

    @Test
    void tendingIsAMedicineCheckAgainstTheStage() {
        Character c = DiseasesTest.human(), carer = DiseasesTest.human();
        Diseases.Case k = Diseases.infect(c, Diseases.ALL.get("Dysentery"), always(0.5));
        k.incubation = 0;
        k.stage = 1;   // dysentery, nursing: 10 + 15 × 2 = 40
        double med = carer.skill("Medicine");
        String r = Diseases.treat(c, "Dysentery", "tend", "", 1, carer);
        assertEquals(med >= 40, !r.startsWith("!"), r);
        assertFalse(Diseases.treat(c, "Dysentery", "tend", "", 1, null).startsWith("!"), "the GM's word");
        assertEquals(40, Diseases.tendNeed(Diseases.ALL.get("Dysentery"), k));
        Diseases.Case t = Diseases.infect(c, Diseases.ALL.get("Tetanus"), always(0.5));
        t.stage = 1;
        assertEquals(60, Diseases.tendNeed(Diseases.ALL.get("Tetanus"), t), "intensive care: 30 + 15 × 2");
        for (String n : Diseases.ALL.keySet()) if (!n.equals("Cancer")) assertTrue(Diseases.CARE.containsKey(n), n + " has its care");
    }

    @Test
    void painkillersAndFolkRemediesAreChems() {
        for (String n : List.of("Paracetamol", "Ibuprofen", "Willow bark", "Opium", "Cannabis", "Herbal tea", "Tannin tea", "Placebo", "Diazepam"))
            assertTrue(Data.CHEMS.containsKey(n), n);
        Character a = DiseasesTest.human(), b = DiseasesTest.human();
        for (Character c : List.of(a, b)) {
            Diseases.Case k = Diseases.infect(c, Diseases.ALL.get("Cholera"), always(0.5));
            k.incubation = 0;
            k.sev = 0.9;
            c.survival.put(Rules.Stat.WATER, 100.0);
        }
        b.dose(Data.CHEMS.get("Opium"), always(0.99));
        Diseases.hour(a, always(0.99));
        Diseases.hour(b, always(0.99));
        double lostA = 100 - a.survival.get(Rules.Stat.WATER), lostB = 100 - b.survival.get(Rules.Stat.WATER);
        assertTrue(lostA > 0 && Math.abs(lostB - lostA / 2) < 1e-6, lostA + " vs " + lostB + ": opium halves the runs");
        assertTrue(b.painFelt() <= Math.max(0, b.survival.getOrDefault(Rules.Stat.PAIN, 0.0) - 30) + 1e-9);
    }

    @Test
    void theBasesPeopleEatByTheirGroupsSurvival() {
        Production pr = new Production();
        Population.Person p = new Population.Person();
        p.group = "Farmers";
        Meals.npcFactors(pr, List.of(p), g -> g.equals("Farmers") ? 50 : 0);
        double base = Data.TEMPLATES.get("Human").foodFactor();
        assertEquals(base / 1.5, pr.mealFactor, 1e-9, "Survival 50: a portion gives × 1.5");
        Meals.npcFactors(pr, List.of(p));
        assertEquals(base, pr.mealFactor, 1e-9);
    }
}
