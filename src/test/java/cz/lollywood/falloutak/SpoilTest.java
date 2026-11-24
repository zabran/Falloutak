package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spoiling food (07 §19, RULED 11-24, Marek verbatim: *"Should food go fresh → stale (one tier down) → spoiled (Tier I) - yes. With T2
 * staying T2 for quite long and preservation slowling the spoiling."* · *"yes, it increases the rarity of the food. Also, I would count
 * food in cold and preserved in cool as not spoiling."*): a row's daily share steps a tier down, the place and the kept food.
 */
class SpoilTest {
    @Test
    void theDaysByTierPlaceAndKeeping() {
        assertEquals(21, Spoil.days(3, 1, "warm"), 1e-9);
        assertEquals(180, Spoil.days(3, 1, "cool"), 1e-9);
        assertEquals(63, Spoil.days(2, 1, "warm"), 1e-9, "Tier II stays Tier II for quite long");
        assertEquals(3, Spoil.days(1, 1, "warm"), 1e-9, "raw or spoiled food rots away");
        assertEquals(2, Spoil.days(4, 1, "warm"), 1e-9);
        assertEquals(-1, Spoil.days(3, 1, "cold"), 1e-9, "nothing spoils in the cold");
        assertEquals(-1, Spoil.days(2, 2, "cool"), 1e-9, "kept food never spoils in a cool store");
        assertEquals(540, Spoil.days(2, 2, "warm"), 1e-9);
    }

    @Test
    void theStoresRowsStepDownByTheirShareEachDay() {
        Production pr = new Production();
        pr.build("Kitchen", 2, "K");                                   // no cellar, no shelter: warm
        Base base = new Base(null);
        base.addUnits("Food", 3, 1, 100);
        base.addUnits("Food", 1, 1, 30);
        base.addUnits("Food", 2, 2, 40);                                // kept food
        assertEquals("warm", Spoil.place(pr));
        String s = Spoil.store(pr, base);
        assertTrue(s.contains("4 Food III → II") && s.contains("10 Food I rotted away"), s);
        assertEquals(96, base.count("Food", 3, 1));
        assertEquals(4, base.count("Food", 2, 1));
        assertEquals(20, base.count("Food", 1, 1));
        assertEquals(0.762, pr.spoilCarry.get(Base.key("Food", 3, 1)), 1e-3, "the part carried to tomorrow");
        assertEquals(40, base.count("Food", 2, 2), "40 / 540 a day: nothing yet");
        // a cellar: cool, the kept food stops; an ice house: cold, nothing spoils
        pr.build("Root cellar", 1, "Cellar");
        assertEquals("cool", Spoil.place(pr));
        pr.build("Ice house", 2, "Ice");
        assertEquals("cold", Spoil.place(pr));
        assertNull(Spoil.store(pr, base));
    }

    @Test
    void theShelterKeepsCoolAndAFreezerColdOnlyWithItsPower() {
        Production pr = new Production();
        pr.build("Dormitories", 1, "Dorms");
        assertEquals("cool", Spoil.place(pr));
        Production.Building f = pr.build("Freezer", 2, "Freezer");
        assertEquals("cool", Spoil.place(pr), "not charged today");
        f.chargedDay = pr.day;
        assertEquals("cold", Spoil.place(pr));
    }

    @Test
    void aDayInTheBaseSpoilsAndTheKeepingRecipesAreTheSaltSimulations() {
        Production pr = new Production();
        pr.build("Kitchen", 2, "K");
        Base base = new Base(null);
        base.addUnits("Food", 4, 1, 10);
        List<String> out = pr.advance(48, 0, base, new ProductionTest.Crew(), 0);   // the first day passes, then it spoils
        assertTrue(out.stream().anyMatch(x -> x.startsWith("Spoiling (the store keeps warm): 5 Food IV → III")), out.toString());
        Production.Recipe salt = ProductionTest.recipe("salt-meat-in-brine"), kraut = ProductionTest.recipe("sauerkraut"),
                jerky = ProductionTest.recipe("dry-jerky"), smoke = ProductionTest.recipe("hot-smoke-meat"), pem = ProductionTest.recipe("make-pemmican"),
                kiln = ProductionTest.recipe("kiln-dry-roots");
        assertEquals(20, salt.inputs.get(0).count);                    // salt 7 %: a kilo for 20 portions (10 kg)
        assertEquals(100, kraut.inputs.get(0).count);                  // 2 %: a kilo for 100 portions
        assertEquals(1, jerky.inputs.size(), "drying needs no salt");
        assertEquals(8, smoke.inputs.get(1).count, "1.5 kg of wood a kg of meat");
        assertEquals(15, kiln.inputs.get(1).count, "3 kg of wood a kg");
        for (Production.Recipe r : List.of(salt, kraut, jerky, smoke, pem, kiln)) {
            assertEquals(2, r.makes.get(0).tier, r.id);
            assertFalse(Production.reserved("Food", 2, 2, r.makes.get(0).items), r.id + ": its kept food lands in Food II R2");
        }
        assertEquals("Root cellar", ProductionTest.recipe("dig-a-root-cellar").builds.kind);
        assertEquals("Ice house", ProductionTest.recipe("build-an-ice-house").builds.kind);
        assertEquals("Freezer", ProductionTest.recipe("set-up-a-freezer").builds.kind);
    }
}
