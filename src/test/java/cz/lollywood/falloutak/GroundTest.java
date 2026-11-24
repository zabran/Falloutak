package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;

import java.util.List;

import static cz.lollywood.falloutak.ProductionTest.Crew;
import static cz.lollywood.falloutak.ProductionTest.recipe;
import static cz.lollywood.falloutak.ProductionTest.worker;
import static org.junit.jupiter.api.Assertions.*;

/**
 * `09e` round 4 (Marek 11-24, verbatim: *""the plugin doesn't know where hot spots are, so it's the GM's call." - the ground should be
 * part of the input. In the same way, it should not be possible to grow T2 food in medium and very hot area, just T1 food."* · *"Also,
 * just an idea, currently we have water cost for both kitchen and recipes - are the numbers right, do we not count some water twice?"*):
 * the fields' ground caps their food and refuses the skim and potash on hot ground; the grow room's crops draw only the room's water.
 * Round 5 (Marek 11-24, verbatim: *"I would say they grow some T2 and some T3 due to black rain. Only food grown in inside is pure T3."*
 * · *""the pens' animals only drink while a job is running." - means as long as they are in, job must be running."*): clean ground's
 * half comes in Tier III, and the pens drink while the base holds animals.
 */
class GroundTest {
    private static Base fieldStore() {
        Base base = new Base(null);
        base.addUnits("Seeds", 2, 1, 1);
        base.addUnits("Gear and tools", 2, 1, 2);                                      // the field's upkeep tool over 17 weeks
        return base;
    }

    private static Production fieldOn(String ground) {
        Production pr = new Production();
        pr.build("Reactor", 4, "Reactor");
        pr.ground = ground;
        return pr;
    }

    @Test
    void onMediumAndHeavyGroundAFieldGrowsOnlyTierIFood() {
        for (String g : List.of("clean", "light", "medium", "heavy")) {
            Production pr = fieldOn(g);
            Production.Building field = pr.build("Field", 2, "Field");
            Base base = fieldStore();
            Crew farmer = new Crew(worker("F", "Biology", 15));
            assertFalse(pr.start(field, recipe("field-potatoes"), 1, 1, farmer).startsWith("!"));
            List<String> out = pr.advance(121 * 24, 14 * 24, base, farmer, 0);
            boolean hot = g.equals("medium") || g.equals("heavy"), clean = g.equals("clean");
            assertEquals(hot ? 0 : clean ? 5 : 10, base.count("Food", 2, 1), g + " " + out);
            assertEquals(clean ? 5 : 0, base.count("Food", 3, 1), g + " " + out);   // round 5: half clean, half the black rain's
            assertEquals(hot ? 10 : 0, base.count("Food", 1, 1), g + " " + out);
            if (clean) assertTrue(out.stream().anyMatch(s -> s.contains("5 Food III R1 (clean ground)") && s.contains("5 Food II R1 (the black rain)")), out.toString());
            assertEquals(1, base.count("Seeds", 2, 1), g);                             // the seed is not food: it comes back as it was
            if (hot) assertTrue(out.stream().anyMatch(s -> s.contains("10 Food I R1 (" + g + " ground)")), out.toString());
        }
    }

    @Test
    void thePensDrinkWhileTheBaseHoldsAnimalsJobOrNot() {
        Production pr = new Production();
        Production.Building pens = pr.build("Pens", 2, "Pens");
        Base base = new Base(null);
        base.addUnits("Water", 3, 1, 100);
        pr.advance(48, 0, base, new Crew(), 0);
        assertEquals(100, base.count("Water", 3, 0), "no animals, no job: the empty pens drink nothing");
        base.addUnits("Livestock", 2, 1, 2);
        List<String> out = pr.advance(48, 48, base, new Crew(), 0);
        assertNull(pens.job);
        assertEquals(80, base.count("Water", 3, 0), "two days, 10 l each, with no job running: " + out);
        base.takeUnits("Livestock", 2, 0, 2);
        pr.advance(48, 96, base, new Crew(), 0);
        assertEquals(80, base.count("Water", 3, 0), "the animals gone: the pens stop drinking");
    }

    @Test
    void theSkimAndPotashAreRefusedOnHotGroundButNotIndoors() {
        Production.Recipe skim = recipe("field-potatoes-on-skimmed-ground"), potash = recipe("field-potatoes-on-potash");
        assertEquals("light", skim.ground);
        assertEquals("light", potash.ground);
        assertEquals("", recipe("field-potatoes").ground);
        for (String g : List.of("clean", "light", "medium", "heavy")) {
            Production pr = fieldOn(g);
            pr.known.add(potash.id);
            Production.Building field = pr.build("Field", 2, "Field");
            Crew farmer = new Crew(worker("F", "Biology", 30));
            String said = pr.start(field, skim, 1, 1, farmer);
            boolean hot = g.equals("medium") || g.equals("heavy");
            assertEquals(hot, said.startsWith("!"), g + ": " + said);
            if (hot) {
                assertTrue(said.contains(g + " ground") && said.contains("light ground or cleaner"), said);
                assertNull(field.job);
                assertTrue(pr.start(field, potash, 1, 1, farmer).startsWith("!"));
            }
            // a grow room has its own soil (the flowerpots hauls): the ground never touches it
            Production.Building room = pr.build("Grow room", 3, "Grow room");
            assertFalse(pr.start(room, skim, 1, 1, new Crew(worker("G", "Biology", 30))).startsWith("!"), g);
        }
        // the skim on light ground gives the step between (Food II R2, the named crop)
        Production pr = fieldOn("light");
        Production.Building field = pr.build("Field", 2, "Field");
        Base base = fieldStore();
        Crew farmer = new Crew(worker("F", "Biology", 15));
        pr.start(field, skim, 1, 1, farmer);
        pr.advance(121 * 24, 14 * 24, base, farmer, 0);
        assertEquals(10, base.count("Food", 2, 2));
    }

    @Test
    void theGroundIsSavedWithTheBaseAndCyclesInItsOrder() {
        Production pr = new Production();
        assertEquals("clean", pr.ground);                                              // an older production.json without it: clean
        Production back = Store.GSON.fromJson("{\"harvest\": 80}", Production.class);
        assertEquals("clean", back.ground);
        pr.ground = "medium";
        assertEquals("medium", Store.GSON.fromJson(Store.GSON.toJson(pr), Production.class).ground);
        assertEquals(List.of("clean", "light", "medium", "heavy"), Production.GROUNDS);
        assertEquals(0, Production.groundIndex("nonsense"));
    }

    @Test
    void theGrowRoomsCropsDrawOnlyTheRoomsDailyWater() {
        // the room's 20 l a day while a crop grows is the plants' water; the crops' recipes take none of their own (it was counted twice)
        for (String id : List.of("potatoes-under-lamps", "mushroom-cellar", "chicory-beds", "select-seed", "field-potatoes"))
            assertTrue(recipe(id).inputs.stream().noneMatch(i -> "Water".equals(i.type)), id);
        Production pr = new Production();
        pr.build("Reactor", 4, "Reactor");
        Production.Building room = pr.build("Grow room", 3, "Grow room");
        Base base = new Base(null);
        base.addUnits("Water", 3, 1, 1900);                                             // 90 days x 20 l = 1 800
        base.addUnits("Seeds", 3, 1, 1);
        base.addUnits("Gear and tools", 3, 1, 1);
        Crew gardener = new Crew(worker("G", "Biology", 25));
        assertFalse(pr.start(room, recipe("potatoes-under-lamps"), 1, 1, gardener).startsWith("!"));
        pr.advance(91 * 24, 13 * 24, base, gardener, 0);
        assertEquals(15, base.count("Food", 3, 1));
        int left = base.count("Water", 3, 1);
        assertTrue(left >= 1900 - 20 * 92 && left <= 1900 - 20 * 89, "the room drew " + (1900 - left) + " l");
        // and its product is no better than the room's water: with Tier II water the potatoes come out Tier II
        Production pr2 = new Production();
        pr2.build("Reactor", 4, "Reactor");
        Production.Building room2 = pr2.build("Grow room", 3, "Grow room");
        Base b2 = new Base(null);
        b2.addUnits("Water", 2, 1, 1900);
        b2.addUnits("Seeds", 3, 1, 1);
        b2.addUnits("Gear and tools", 3, 1, 1);
        pr2.start(room2, recipe("potatoes-under-lamps"), 1, 1, gardener);
        pr2.advance(91 * 24, 13 * 24, b2, gardener, 0);
        assertEquals(15, b2.count("Food", 2, 1));
    }
}
