package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Part 7, production (`07-PRODUCTION.md` §4, RULED 11-20; the catalogue `07b`): the gate, the shelter's rooms, jobs and the automatic
 * pick, speed, rarity by the marked inputs, power and water by the working day, upkeep, days that must pass, harvests, projects.
 */
class ProductionTest {
    /** A worker with this skill (a human's base + points). */
    static Production.Worker worker(String name, String skill, double atLeast, String... perks) {
        Character c = AirTest.human();
        c.name = name;
        int i = Rules.skillIndex(skill);
        c.points[i] = 0;
        c.points[i] = atLeast - c.skill(skill);                                        // exactly that skill
        assertEquals(atLeast, c.skill(skill), 1e-9);
        for (String p : perks) c.perks.add(new Character.Feat(p, "", ""));
        return new Production.Worker("person:" + name, name, c);
    }

    /** A workforce of these workers, remembering their activities. */
    static final class Crew implements Production.Workforce {
        final List<Production.Worker> list = new ArrayList<>();
        final Map<String, String> activity = new HashMap<>();

        Crew(Production.Worker... ws) { list.addAll(List.of(ws)); }

        @Override public List<Production.Worker> all() { return list; }

        @Override public void assign(String id, String a) { activity.put(id, a); }
    }

    static Production.Recipe recipe(String id) {
        Production.Recipe r = Production.RECIPES.get(id);
        assertNotNull(r, id);
        return r;
    }

    // ------------------------------------------------------------------ the catalogue
    @Test
    void theGateEveryTierIAndIIOfEveryTypeHasSeveralWaysOneKnownAtTheStart() {
        assertEquals(46, Production.TYPES.size());
        for (Production.ItemType t : Production.TYPES.values()) {
            if (t.found_only) continue;
            for (int tier = 1; tier <= 2; tier++) {
                int all = 0, start = 0;
                for (Production.Recipe r : Production.RECIPES.values())
                    for (Production.Ttr m : r.makes)
                        if (m.type.equals(t.name) && m.tier == tier) { all++; if (r.atStart()) start++; }
                assertTrue(start >= 1, t.name + " " + tier + ": no recipe known at the start (the gate, 05c §7)");
                assertTrue(all >= 2, t.name + " " + tier + ": one way only (Marek 11-20: several ways to the same TTR)");
            }
        }
        for (Production.Recipe r : Production.RECIPES.values()) {
            assertTrue(Production.KINDS.containsKey(r.building), r.name + ": " + r.building);
            for (Production.Ttr t : r.makes) {
                assertTrue(Production.TYPES.containsKey(t.type), r.name + ": " + t.type);
                if (!Production.SITES.contains(r.building)) assertTrue(t.tier <= r.tier, r.name + ": the building's tier caps what it makes");
            }
            for (Production.Ttr t : r.inputs) assertTrue(Production.TYPES.containsKey(t.type), r.name + ": " + t.type);
            if (!r.atStart()) assertFalse(r.research.isEmpty(), r.name + ": a researched way names its research");
        }
    }

    @Test
    void startRecipesMarkEveryInputResearchedOnesOnlyTheirMain() {
        Production.Recipe start = recipe("recast-scrap"), better = recipe("bellows-furnace");
        assertTrue(start.inputs.stream().allMatch(i -> i.sets_rarity));               // round 2: "bad recipes may need all of them"
        assertEquals(1, better.inputs.stream().filter(i -> i.sets_rarity).count());   // "for better, only 1 material is needed"
        assertTrue(better.inputs.stream().mapToInt(i -> i.count).sum() < start.inputs.stream().mapToInt(i -> i.count).sum());
    }

    // ------------------------------------------------------------------ the shelter, a job, the pick
    @Test
    void theShelterIsSevenRoomsWithItsPowerAndWater() {
        Production pr = new Production();
        assertEquals(7, pr.shelter());
        assertEquals(0, pr.shelter());                                                 // once
        assertEquals(200, pr.powerCap(), 1e-9);
        assertEquals(300, pr.waterCap(), 1e-9);
        Production.Building ws = pr.buildings.stream().filter(b -> b.kind.equals("Workshop")).findFirst().orElseThrow();
        assertEquals(2, ws.tier);
        assertTrue(Production.fits(ws, recipe("recast-scrap")));
        assertTrue(Production.fits(ws, recipe("knives-i-from-scrap")));               // a Workshop does a Workbench's work
        assertFalse(Production.fits(ws, recipe("potatoes-under-lamps")));
    }

    @Test
    void aJobTakesItsInputsAndMakesItsOutputAtTheSkillsSpeed() {
        Production pr = new Production();
        pr.shelter();
        Production.Building ws = pr.buildings.stream().filter(b -> b.kind.equals("Workshop")).findFirst().orElseThrow();
        Base base = new Base(null);
        base.addUnits("Metal", 1, 1, 6);
        base.addUnits("Fuel", 2, 1, 4);
        Crew crew = new Crew(worker("Honza", "Crafting", 60));                        // twice the minimum (30): twice as fast
        String m = pr.start(ws, recipe("recast-scrap"), 2, 1, crew);
        assertTrue(m.contains("Honza"), m);
        assertEquals("production", crew.activity.get("person:Honza"));
        // the week's upkeep comes first: 2 h x Tier II = 4 h; then 4 h of recipe at x2 = 2 h a run
        pr.advance(5, 0, base, crew, 0);
        assertEquals(0, base.count("Metal", 2, 0));
        assertEquals(3, base.count("Metal", 1, 0));                                    // the first run's inputs are in
        pr.advance(1, 5, base, crew, 0);
        assertEquals(1, base.count("Metal", 2, 1));
        pr.advance(2, 6, base, crew, 0);
        assertEquals(2, base.count("Metal", 2, 1));
        assertNull(ws.job);                                                            // two runs: done
        assertTrue(ws.crew.isEmpty());
        assertEquals("", crew.activity.get("person:Honza"));                           // idle again
        assertEquals(0, base.count("Metal", 1, 0));
        assertEquals(0, base.count("Fuel", 2, 0));
        assertTrue(base.unlocked.contains(Base.key("Metal", 2, 1)));                  // made in the base: unlocked
    }

    @Test
    void thePickTakesTheBestFreeWorkersUpToTheSlots() {
        Production pr = new Production();
        pr.shelter();
        Production.Building ws = pr.buildings.stream().filter(b -> b.kind.equals("Workshop")).findFirst().orElseThrow();
        Production.Building kit = pr.buildings.stream().filter(b -> b.kind.equals("Kitchen")).findFirst().orElseThrow();
        Crew crew = new Crew(worker("A", "Crafting", 35), worker("B", "Crafting", 80), worker("C", "Crafting", 50),
                worker("D", "Crafting", 45), worker("E", "Crafting", 10));
        pr.start(ws, recipe("recast-scrap"), 0, 1, crew);
        assertEquals(List.of("person:B", "person:C", "person:D"), ws.crew);            // the best first, 3 slots
        Production.Recipe meat = recipe("cook-wasteland-meat");                       // Survival 15
        pr.start(kit, meat, 0, 1, crew);
        assertFalse(kit.crew.contains("person:B"));                                    // busy elsewhere
        assertNotNull(pr.add(ws, worker("F", "Crafting", 90), crew));                  // no free slot
        pr.drop(ws, "person:D", crew);
        assertNotNull(pr.add(ws, crew.list.get(4), crew));                             // E: below the minimum
        assertNull(pr.add(ws, worker("F", "Crafting", 90), crew));
    }

    @Test
    void speedCapsAtFourWorkersAddUpAndAnExtraIsHalfAgainFaster() {
        Production.Recipe r = recipe("recast-scrap");
        Production pr = new Production();
        pr.build("Reactor", 4, "Reactor");
        Production.Building ws = pr.build("Workshop", 2, "W");
        ws.upkeepWeek = 0;
        ws.upkeepDone = 99;                                                            // upkeep done for the week
        Base base = new Base(null);
        base.addUnits("Metal", 1, 1, 3);
        base.addUnits("Fuel", 2, 1, 2);
        Crew one = new Crew(worker("Master", "Crafting", 300));                        // x10 would be: capped at x4
        pr.start(ws, r, 1, 1, one);
        pr.advance(1, 0, base, one, 0);
        assertNull(ws.job);                                                            // 4 h of recipe in 1 h
        assertEquals(1, base.count("Metal", 2, 1));
        // two at the minimum: the hours add up (round 1-2)
        base.addUnits("Metal", 1, 1, 3);
        base.addUnits("Fuel", 2, 1, 2);
        Crew two = new Crew(worker("X", "Crafting", 30), worker("Y", "Crafting", 30));
        pr.start(ws, r, 1, 1, two);
        pr.advance(1, 1, base, two, 0);
        assertNotNull(ws.job);
        assertEquals(2, ws.job.work, 0.01);
        pr.advance(1, 2, base, two, 0);
        assertNull(ws.job);
        assertEquals(2, base.count("Metal", 2, 1));
        // an extra: the charger's spare part makes it x1.5
        Production.Recipe charge = recipe("charge-cells");
        assertTrue(charge.inputs.stream().anyMatch(i -> i.extra));
        base.addUnits("Power", 2, 1, 4);
        base.addUnits("Parts", 2, 1, 1);
        Crew c3 = new Crew(worker("Z", "Engineering", 20));
        pr.start(ws, charge, 1, 1, c3);
        pr.advance(1, 3, base, c3, 0);
        assertEquals(1.5, ws.job.work, 0.01);
        assertEquals(0, base.count("Parts", 2, 0));                                    // the extra used up
    }

    @Test
    void rarityIsTheLowestOfTheMarkedInputsOnlyPerksCheatIt() {
        Production pr = new Production();
        pr.build("Reactor", 4, "Reactor");
        Production.Building ws = pr.build("Workshop", 2, "W");
        ws.upkeepWeek = 0;
        ws.upkeepDone = 99;
        Base base = new Base(null);
        base.addUnits("Metal", 1, 2, 3);                                               // rarity 2 scrap
        base.addUnits("Fuel", 2, 1, 2);
        Crew crew = new Crew(worker("H", "Crafting", 120));
        pr.start(ws, recipe("recast-scrap"), 1, 2, crew);                              // the start recipe marks the fuel too
        pr.advance(2, 0, base, crew, 0);
        assertEquals("needs 2 Fuel II R2", ws.job.waiting);
        base.addUnits("Fuel", 2, 2, 2);
        pr.advance(2, 2, base, crew, 0);
        assertEquals(1, base.count("Metal", 2, 2));
        // the researched way marks only the scrap: R1 fuel will do
        pr.known.add("bellows-furnace");
        Production.Building kiln = pr.build("Kiln", 2, "K");
        kiln.upkeepWeek = 0;
        kiln.upkeepDone = 99;
        base.addUnits("Metal", 1, 2, 2);
        pr.start(kiln, recipe("bellows-furnace"), 1, 2, crew);
        pr.advance(2, 4, base, crew, 0);
        assertEquals(2, base.count("Metal", 2, 2));
        // Master Craftsman: one rarity higher (02c)
        base.addUnits("Metal", 1, 1, 2);
        Crew mc = new Crew(worker("M", "Crafting", 120, "Master Craftsman"));
        pr.start(kiln, recipe("bellows-furnace"), 1, 1, mc);
        pr.advance(2, 6, base, mc, 0);
        assertEquals(3, base.count("Metal", 2, 2));
    }

    @Test
    void anUnknownRecipeWaitsForItsResearch() {
        Production pr = new Production();
        Production.Building k = pr.build("Kiln", 2, "K");
        assertTrue(pr.start(k, recipe("bellows-furnace"), 1, 1, new Crew()).startsWith("! "));
        assertTrue(pr.start(pr.build("Workbench", 1, "B"), recipe("recast-scrap"), 1, 1, new Crew()).startsWith("! "));   // the wrong room
        pr.known.add("bellows-furnace");
        assertFalse(pr.start(k, recipe("bellows-furnace"), 1, 1, new Crew()).startsWith("! "));
    }

    // ------------------------------------------------------------------ power, water, upkeep, days
    @Test
    void powerOnlyOnTheDaysItWorksTheKitchenEveryDay() {
        Production pr = new Production();
        pr.shelter();
        Base base = new Base(null);
        pr.advance(1, 0, base, new Crew(), 10);
        // the filtration 20 + the scrubbers 20 + the kitchen 15 always; nobody works in the workshop: not its 20
        assertEquals(55, pr.powerUsed, 1e-9);
        assertEquals(20 + 40, pr.waterUsed, 1e-9);                                     // ten people's 2 l, the kitchen's 40
        Production.Building ws = pr.buildings.stream().filter(b -> b.kind.equals("Workshop")).findFirst().orElseThrow();
        base.addUnits("Metal", 1, 1, 3);
        base.addUnits("Fuel", 2, 1, 2);
        Crew crew = new Crew(worker("H", "Crafting", 30));
        ws.upkeepDone = 99;                                                            // the week's upkeep done: the hour is the job's
        pr.start(ws, recipe("recast-scrap"), 1, 1, crew);
        pr.advance(1, 1, base, crew, 10);
        assertEquals(75, pr.powerUsed, 1e-9);                                          // the workshop's day
        pr.advance(1, 2, base, crew, 10);
        assertEquals(75, pr.powerUsed, 1e-9);                                          // once a day
    }

    @Test
    void withoutPowerTheWorkshopWaits() {
        Production pr = new Production();
        Production.Building ws = pr.build("Workshop", 2, "W");
        Base base = new Base(null);
        base.addUnits("Metal", 1, 1, 3);
        base.addUnits("Fuel", 2, 1, 2);
        Crew crew = new Crew(worker("H", "Crafting", 30));
        pr.start(ws, recipe("recast-scrap"), 1, 1, crew);
        pr.advance(10, 0, base, crew, 0);
        assertEquals("no power or water left today", ws.job.waiting);
        assertEquals(0, ws.job.work, 1e-9);
    }

    @Test
    void aMissedWeekOfUpkeepWearsTheBuildingDownAndItsCrewRepairsIt() {
        Production pr = new Production();
        pr.shelter();
        Base base = new Base(null);
        List<String> out = pr.advance(170, 0, base, new Crew(), 0);
        Production.Building reactor = pr.buildings.get(0);
        assertEquals(75, reactor.condition, 1e-9);                                     // a week with nobody: −25 [EXAMPLE]
        assertTrue(out.stream().anyMatch(s -> s.startsWith("Reactor: upkeep missed")), out.toString());
        Crew crew = new Crew(worker("Tech", "Engineering", 40));
        pr.pick(reactor, crew);                                                        // an automatic room's crew: its upkeep
        assertEquals(List.of("person:Tech"), reactor.crew);
        pr.advance(8 + 10, 170, base, crew, 0);                                        // 8 h upkeep (Tier IV), then repairs +2 an hour
        assertEquals(95, reactor.condition, 1e-9);
    }

    @Test
    void daysMustPassWhateverTheHandsAndHarvestsFollowTheFactor() {
        Production pr = new Production();
        pr.build("Reactor", 4, "Reactor");
        Production.Building ws = pr.build("Workshop", 2, "W");
        Base base = new Base(null);
        base.addUnits("Textile", 1, 1, 2);
        base.addUnits("Chemicals", 1, 1, 1);
        Crew crew = new Crew(worker("T", "Crafting", 100));
        pr.start(ws, recipe("tan-hides"), 1, 1, crew);
        pr.advance(24, 0, base, crew, 0);
        assertNotNull(ws.job);                                                         // the work is done, the 14 days are not
        pr.advance(13 * 24, 24, base, crew, 0);
        assertNull(ws.job);
        assertEquals(1, base.count("Textile", 2, 1));
        // a field at half a harvest (the nuclear autumn): 10 potatoes → 5
        Production.Building field = pr.build("Field", 2, "Field");
        pr.harvest = 50;
        base.addUnits("Seeds", 2, 1, 1);
        Crew farmer = new Crew(worker("F", "Biology", 15));
        pr.start(field, recipe("field-potatoes"), 1, 1, farmer);
        pr.advance(121 * 24, 14 * 24, base, farmer, 0);
        assertEquals(5, base.count("Food", 2, 1));
        assertEquals(0, base.count("Seeds", 2, 1));                                    // the seed lot: 1 x 50 % = 0
    }

    @Test
    void projectsMakeBuildingsAndSitesComeAndGo() {
        Production pr = new Production();
        Base base = new Base(null);
        base.addUnits("Stone and glass", 1, 1, 20);
        base.addUnits("Stone and glass", 2, 1, 4);
        base.addUnits("Metal", 1, 1, 2);
        Crew crew = new Crew(worker("A", "Crafting", 50), worker("B", "Crafting", 50));
        Production.Building site = pr.startAt("Base", recipe("build-a-kiln"), 1, 1, crew);
        assertNotNull(site);
        assertEquals(2, site.crew.size());
        pr.advance(6, 0, base, crew, 0);                                               // 24 h of work at x2 by two
        assertTrue(pr.buildings.stream().anyMatch(b -> b.kind.equals("Kiln") && b.tier == 2));
        assertFalse(pr.buildings.contains(site));                                      // the site is gone with its job
        Production.Building out = pr.startAt("Outside", recipe("gather-wood"), 2, 1, crew);
        assertEquals("gathering", crew.activity.get("person:A"));                      // outside: 0 XP until part 9
        pr.advance(2, 6, base, crew, 0);
        assertEquals(6, base.count("Wood", 1, 1));
        assertFalse(pr.buildings.contains(out));
    }

    @Test
    void stoppingGivesTheRunsInputsBack() {
        Production pr = new Production();
        pr.build("Reactor", 4, "Reactor");
        Production.Building ws = pr.build("Workshop", 2, "W");
        Base base = new Base(null);
        base.addUnits("Metal", 1, 1, 3);
        base.addUnits("Fuel", 2, 1, 2);
        Crew crew = new Crew(worker("H", "Crafting", 30));
        pr.start(ws, recipe("recast-scrap"), 0, 1, crew);
        pr.advance(1, 0, base, crew, 0);
        assertEquals(0, base.count("Metal", 1, 0));
        assertTrue(pr.stop(ws, crew, base).contains("stopped"));
        assertEquals(3, base.count("Metal", 1, 1));
        assertEquals(2, base.count("Fuel", 2, 1));
        assertTrue(ws.crew.isEmpty());
    }

    // ------------------------------------------------------------------ the store's things (part 7's types)
    @Test
    void theStoreKeepsEveryTypeLowestRarityFirst() {
        Base b = new Base(null);
        b.addUnits("Metal", 2, 2, 2);
        b.addUnits("Metal", 2, 1, 3);
        assertEquals(5, b.count("Metal", 2, 0));
        assertEquals(1, b.takeUnits("Metal", 2, 0, 4));                                // the lowest first: three R1, one R2
        assertEquals(1, b.count("Metal", 2, 2));
        assertEquals(0, b.takeUnits("Metal", 2, 0, 5));                                // not enough: nothing taken
        assertEquals(1, b.count("Metal", 2, 0));
        b.addUnits("Gear and tools", 2, 1, 1);
        Base.Pool tools = b.pools.get(Base.key("Gear and tools", 2, 1));
        assertTrue(b.choices(tools).contains("Armour repair kit"));
        assertEquals("gear:Armour repair kit", Items.storeId("Gear and tools", 2, 1, "Armour repair kit"));
        assertArrayEquals(new String[]{"Chems", "3", "1", "Buffout"}, Items.ttrOf("chem:Buffout"));
        assertTrue(b.putThing("Food", 3, 1, "Potatoes", 4));
        assertTrue(b.takeThing(Base.key("Food", 3, 1), "Potatoes"));
        assertEquals(3, b.count("Food", 3, 1));
        b.wear("Gear and tools", 2, 60);
        b.wear("Gear and tools", 2, 60);                                               // worn out: gone
        assertEquals(0, b.count("Gear and tools", 2, 0));
        assertTrue(Base.types().size() >= 46);
    }
}
