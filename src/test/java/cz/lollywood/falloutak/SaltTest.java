package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.60.0, part 7 round 12 (Marek 11-22, `09b` round 4): an item is unlocked from the start only if a recipe the base knows makes it
 * (otherwise brought in or by the GM); a recipe input may name an item and takes its row only while the item is unlocked; SALT is
 * Chemicals I at rarity 2 (*"rarity is the simpliest solution"*), made by no recipe in Czechia, asked for by name by the salt recipes.
 */
class SaltTest {
    static Production.Named named(String type, String name) {
        return Production.TYPES.get(type).items.stream().filter(n -> n.name.equals(name)).findFirst().orElseThrow();
    }

    @Test
    void saltIsChemicalsOneAtRarityTwoAndLyeAndVinegarStayRarityOne() {
        Production.Named salt = named("Chemicals", "Salt");
        assertEquals(1, salt.tier);
        assertEquals(2, salt.rarity);
        assertEquals(1, named("Chemicals", "Lye").rarity);
        assertEquals(1, named("Chemicals", "Vinegar").rarity);
        assertTrue(Production.makersOf("Salt").isEmpty(), "no recipe makes salt in Czechia");
    }

    @Test
    void unlockedFromTheStartOnlyWhatAKnownRecipeMakes() {
        Base base = new Base(null);
        assertTrue(base.unlocked("Chemicals", named("Chemicals", "Lye")), "Leach lye is a start recipe");
        assertTrue(base.unlocked("Chemicals", named("Chemicals", "Vinegar")));
        assertFalse(base.unlocked("Chemicals", named("Chemicals", "Salt")), "nothing makes salt");
        assertFalse(base.unlocked("Textile", named("Textile", "Rags")), "Tier I rarity 1, but no recipe makes rags: brought in only");
        assertTrue(base.unlocked("Fuel", named("Fuel", "Firewood")));
        // making a row no longer opens the row's other names: lye made, salt (another row anyway) and vinegar's state unchanged
        base.addUnits("Chemicals", 1, 1, 2, List.of("Lye"));
        assertFalse(base.unlockedName("Salt"));
        // brought in through the Mess Chest: unlocked by its name
        base.putThing("Chemicals", 1, 2, "Salt", 1);
        assertTrue(base.unlockedName("Salt"));
        assertEquals(1, base.count("Chemicals", 1, 2));
    }

    @Test
    void aResearchedRecipeOpensItsItemOnlyOnceKnown() {
        Production pr = new Production();
        Base base = new Base(null);
        base.knownMaker = pr::makesNamed;
        assertFalse(base.unlockedName("Mushrooms"), "the mushroom cellar is researched");
        pr.known.add(ProductionTest.recipe("mushroom-cellar").id);
        assertTrue(base.unlockedName("Mushrooms"));
        assertTrue(base.unlockedName("Lye"), "a start recipe through the production too");
    }

    @Test
    void theJerkyWantsSaltByNameAndTakesTheSaltRowOnly() {
        Production pr = new Production();
        Production.Building k = pr.build("Kitchen", 2, "K");
        Base base = new Base(null);
        base.addUnits("Food", 1, 1, 3);
        base.addUnits("Chemicals", 1, 1, 5, List.of("Vinegar"));   // vinegar no longer stands in for salt
        ProductionTest.Crew crew = new ProductionTest.Crew(ProductionTest.worker("C", "Survival", 30));
        Production.Recipe jerky = ProductionTest.recipe("dry-jerky");
        assertTrue(Production.inputsText(jerky).contains("1 Salt (Chemicals I R2)"), Production.inputsText(jerky));
        pr.start(k, jerky, 1, 1, crew);
        String miss = pr.takeInputs(k, jerky, base, 0);
        assertNotNull(miss);
        assertTrue(miss.contains("Salt (not available"), miss);
        assertEquals(3, base.count("Food", 1, 0), "nothing taken while salt is missing");
        // a sack brought in: unlocked, and one unit of the R2 row goes into the jerky; the vinegar stays
        base.putThing("Chemicals", 1, 2, "Salt", 2);
        assertNull(pr.takeInputs(k, jerky, base, 0));
        assertEquals(1, base.count("Chemicals", 1, 2));
        assertEquals(5, base.count("Chemicals", 1, 1));
        assertEquals(0, base.count("Food", 1, 0));
    }

    @Test
    void unlockedSaltWithNoUnitsLeftIsStillMissing() {
        Production pr = new Production();
        Production.Building k = pr.build("Kitchen", 2, "K");
        Base base = new Base(null);
        base.unlocked.add("Salt");                                   // known (the GM's unlock) but the sack is empty
        base.addUnits("Food", 1, 1, 5);
        ProductionTest.Crew crew = new ProductionTest.Crew(ProductionTest.worker("C", "Survival", 30));
        Production.Recipe kraut = ProductionTest.recipe("sauerkraut");
        pr.start(k, kraut, 1, 1, crew);
        String miss = pr.takeInputs(k, kraut, base, 0);
        assertNotNull(miss);
        assertTrue(miss.contains("Chemicals I"), miss);
    }

    @Test
    void aMarkedChemicalsInputNeverEatsTheSalt() {
        Production pr = new Production();
        Production.Building w = pr.build("Workshop", 2, "W");
        Base base = new Base(null);
        base.addUnits("Textile", 1, 1, 2);
        base.putThing("Chemicals", 1, 2, "Salt", 3);                // only salt in the chemicals
        ProductionTest.Crew crew = new ProductionTest.Crew(ProductionTest.worker("T", "Crafting", 30));
        Production.Recipe tan = ProductionTest.recipe("tan-hides");
        pr.start(w, tan, 1, 1, crew);
        assertNotNull(pr.takeInputs(w, tan, base, 0), "a rarity-1 job takes rarity-1 chemicals: salt is kept for what needs it");
        assertEquals(3, base.count("Chemicals", 1, 2));
    }

    @Test
    void lyeMadeFromBetterInputsNeverLandsInTheSaltRow() {
        assertTrue(Production.reserved("Chemicals", 1, 2, List.of("Lye")), "Chemicals I R2 is kept for salt");
        assertFalse(Production.reserved("Chemicals", 1, 2, List.of("Salt")));
        assertFalse(Production.reserved("Chemicals", 1, 1, List.of("Lye")));
        assertFalse(Production.reserved("Food", 2, 2, List.of("Jerky")), "rows without named items take any rarity");
    }

    @Test
    void theRowsChoicesNameOnlyTheUnlockedItems() {
        Base base = new Base(null);
        base.addUnits("Chemicals", 1, 1, 2, List.of("Lye"));
        List<Base.Row> rows = base.rows("Chemicals");
        assertEquals(List.of("Lye", "Vinegar"), rows.get(0).sets());
        base.putThing("Chemicals", 1, 2, "Salt", 1);
        Base.Row r2 = base.rows("Chemicals").stream().filter(r -> r.pool().rarity == 2).findFirst().orElseThrow();
        assertEquals(List.of("Salt"), r2.sets());
    }
}
