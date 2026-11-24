package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.61.0, `09b` round 6 (Marek 11-22: *"Salt for livestock, and the salt-water cell's chlorine and lye: build them?" - yes*):
 * the salt lick is an optional EXTRA on the breeding (it speeds the job, never blocks it); goats and pigs in stalls (Pens III); the
 * card Chlor-alkali splits brine into bleach and lye, and bleach makes water safe without fuel. Also: a stopped job gives a named
 * input back to its own row (salt R2), not to rarity 1.
 */
class SaltLivestockTest {
    static ProductionTest.Crew crew(String skill) {
        return new ProductionTest.Crew(ProductionTest.worker("A", skill, 40));
    }

    @Test
    void theRabbitsBreedWithoutSaltAndALickIsTakenFromTheSaltRowOnly() {
        Production pr = new Production();
        Production.Building pens = pr.build("Pens", 2, "P");
        Base base = new Base(null);
        base.addUnits("Livestock", 2, 1, 2);
        base.addUnits("Food", 1, 1, 5);
        base.addUnits("Chemicals", 1, 1, 4, List.of("Lye"));        // lye is no salt lick
        Production.Recipe breed = ProductionTest.recipe("breed-rabbits");
        assertTrue(Production.inputsText(breed).contains("extra: 1 Salt"), Production.inputsText(breed));
        pr.start(pens, breed, 1, 1, crew("Biology"));
        assertNull(pr.takeInputs(pens, breed, base, 0), "no salt: the job still runs");
        assertFalse(pens.job.extra);
        assertEquals(4, base.count("Chemicals", 1, 1), "the lye untouched");
        // with a sack of salt brought in, the next run takes one unit of the R2 row as its lick
        base.addUnits("Livestock", 2, 1, 2);
        base.addUnits("Food", 1, 1, 5);
        base.putThing("Chemicals", 1, 2, "Salt", 2);
        Production.Building pens2 = pr.build("Pens", 2, "P2");
        pr.start(pens2, breed, 1, 1, crew("Biology"));
        assertNull(pr.takeInputs(pens2, breed, base, 0));
        assertTrue(pens2.job.extra);
        assertEquals(1, base.count("Chemicals", 1, 2));
        assertEquals(4, base.count("Chemicals", 1, 1));
    }

    @Test
    void anUnlockedButEmptySaltRowLeavesTheLickOut() {
        Production pr = new Production();
        Production.Building pens = pr.build("Pens", 2, "P");
        Base base = new Base(null);
        base.unlocked.add("Salt");
        base.addUnits("Livestock", 2, 1, 2);
        base.addUnits("Food", 1, 1, 5);
        Production.Recipe breed = ProductionTest.recipe("breed-rabbits");
        pr.start(pens, breed, 1, 1, crew("Biology"));
        assertNull(pr.takeInputs(pens, breed, base, 0));
        assertFalse(pens.job.extra);
    }

    @Test
    void goatsAndPigsLiveInStalls() {
        Production.Recipe goats = ProductionTest.recipe("breed-goats-and-pigs");
        assertEquals("Pens", goats.building);
        assertEquals(3, goats.tier);
        assertEquals(List.of("Goats", "Pigs"), goats.makes.get(0).items);
        Production.Recipe stalls = ProductionTest.recipe("build-stalls");
        assertEquals("Pens", stalls.builds.kind);
        assertEquals(3, stalls.builds.tier);
        Production.Recipe milk = ProductionTest.recipe("milk-the-goats");
        assertTrue(milk.inputs.stream().anyMatch(t -> t.tool && t.type.equals("Livestock") && t.tier == 3), "the goats are kept");
        assertEquals(List.of("Goat milk"), milk.makes.get(0).items);
    }

    @Test
    void splitBrineMakesBleachAndLyeOnlyOnceTheCardIsKnown() {
        Production.Recipe split = ProductionTest.recipe("split-brine");
        assertEquals("research", split.known);
        assertEquals("Chlor-alkali", split.research);
        assertTrue(Production.inputsText(split).contains("1 Salt (Chemicals I R2)"), Production.inputsText(split));
        Production.Ttr bleach = split.makes.stream().filter(t -> t.tier == 2).findFirst().orElseThrow();
        Production.Ttr lye = split.makes.stream().filter(t -> t.tier == 1).findFirst().orElseThrow();
        assertEquals(List.of("Bleach"), bleach.items);
        assertEquals(List.of("Lye"), lye.items);
        Production pr = new Production();
        Base base = new Base(null);
        base.knownMaker = pr::makesNamed;
        assertFalse(base.unlockedName("Bleach"), "bleach opens with the card");
        pr.known.add(split.id);
        assertTrue(base.unlockedName("Bleach"));
    }

    @Test
    void bleachMakesWaterSafeWithoutFuel() {
        Production pr = new Production();
        Production.Building k = pr.build("Kitchen", 2, "K");
        Base base = new Base(null);
        base.knownMaker = pr::makesNamed;
        Production.Recipe chl = ProductionTest.recipe("chlorinate-water");
        pr.known.add(chl.id);
        pr.known.add(ProductionTest.recipe("split-brine").id);
        base.addUnits("Water", 1, 1, 8);
        pr.start(k, chl, 1, 1, crew("Chemistry"));
        String miss = pr.takeInputs(k, chl, base, 0);
        assertNotNull(miss, "no bleach in the store");
        base.addUnits("Chemicals", 2, 1, 1, List.of("Bleach"));
        assertNull(pr.takeInputs(k, chl, base, 0));
        assertEquals(0, base.count("Water", 1, 0));
        assertEquals(0, base.count("Chemicals", 2, 0));
        assertEquals(List.of("Chlorinated water"), chl.makes.get(0).items);
    }

    @Test
    void aStoppedJobGivesTheSaltBackToTheSaltRow() {
        Production pr = new Production();
        Production.Building k = pr.build("Kitchen", 3, "K");
        Base base = new Base(null);
        base.addUnits("Food", 3, 1, 20);
        base.addUnits("Water", 3, 1, 1);
        base.putThing("Chemicals", 1, 2, "Salt", 1);
        ProductionTest.Crew c = crew("Survival");
        Production.Recipe jerky = ProductionTest.recipe("salt-meat-in-brine");
        pr.start(k, jerky, 1, 1, c);
        assertNull(pr.takeInputs(k, jerky, base, 0));
        assertEquals(0, base.count("Chemicals", 1, 2));
        assertTrue(pr.stop(k, c, base).contains("stopped"));
        assertEquals(1, base.count("Chemicals", 1, 2), "back in the salt row");
        assertEquals(0, base.count("Chemicals", 1, 1), "not into the lye and vinegar row");
    }

    @Test
    void theChlorAlkaliCardHasItsEureka() {
        assertTrue(Research.CARDS.containsKey("chlor-alkali"), "the card exists: " + Research.CARDS.keySet());
        assertNotNull(Research.CARDS.get("chlor-alkali").eureka);
    }
}
