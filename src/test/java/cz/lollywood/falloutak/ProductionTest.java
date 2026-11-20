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

    /** A store with Tier I water: a workshop or a kiln cools with it on its working days (round 5: "these use T1 water first"). */
    static Base cooled() {
        Base b = new Base(null);
        b.addUnits("Water", 1, 1, 200);
        return b;
    }

    static Production.Recipe recipe(String id) {
        Production.Recipe r = Production.RECIPES.get(id);
        assertNotNull(r, id);
        return r;
    }

    // ------------------------------------------------------------------ the catalogue
    @Test
    void theGateEveryTierIAndIIOfEveryTypeHasSeveralWaysOneKnownAtTheStart() {
        assertEquals(26, Production.TYPES.size());                                     // round 4: parts, not finished items
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
        assertTrue(Production.fits(ws, recipe("cold-hammer-weapon-parts")));         // a Workshop does a Workbench's work
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
        base.addUnits("Gear and tools", 2, 1, 1);                                      // upkeep's tool of the workshop's tier (round 5)
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
        assertEquals(96, base.pools.get(Base.key("Gear and tools", 2, 1)).average(), 1e-9);   // 4 h of upkeep: 1 % an hour
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
        Base base = cooled();
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
        base.addUnits("Electronics", 2, 1, 1);
        Crew c3 = new Crew(worker("Z", "Engineering", 20));
        pr.start(ws, charge, 1, 1, c3);
        pr.advance(1, 3, base, c3, 0);
        assertEquals(1.5, ws.job.work, 0.01);
        assertEquals(0, base.count("Electronics", 2, 0));                              // the extra used up
    }

    @Test
    void rarityIsTheLowestOfTheMarkedInputsOnlyPerksCheatIt() {
        Production pr = new Production();
        pr.build("Reactor", 4, "Reactor");
        Production.Building ws = pr.build("Workshop", 2, "W");
        ws.upkeepWeek = 0;
        ws.upkeepDone = 99;
        Base base = cooled();
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
        assertEquals(20 + 40 + 20, pr.waterUsed, 1e-9);                                // ten people's 2 l, the kitchen's 40, the scrubbers' 20
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
        // round 5: "upkeep needs tools of the same tier": without a Tier IV tool nothing moves
        base.addUnits("Gear and tools", 3, 1, 1);                                      // Tier III: not enough for the reactor
        pr.advance(5, 170, base, crew, 0);
        assertEquals(75, reactor.condition, 1e-9);
        assertTrue(pr.status(reactor).contains("WAITS: it needs Gear and tools of Tier IV or better"), pr.status(reactor));
        base.addUnits("Gear and tools", 4, 1, 1);
        pr.advance(8 + 10, 175, base, crew, 0);                                        // 8 h upkeep (Tier IV), then repairs +2 an hour
        assertEquals(95, reactor.condition, 1e-9);
        assertEquals(82, base.pools.get(Base.key("Gear and tools", 4, 1)).average(), 1e-9);   // 18 h of work: 18 %
        assertEquals(1, base.pools.get(Base.key("Gear and tools", 3, 1)).fresh);      // the Tier III one untouched
    }

    @Test
    void waterOfALowerTierComesFromTheStore() {
        // round 5 (Marek 11-20): "Lower-tier water - yes, agreed." · "workshop, kiln, still, scrubbers - I would make it rule that these
        // use T1 water first (reversed priority)."
        Production pr = new Production();
        pr.shelter();
        Base base = new Base(null);
        base.addUnits("Water", 1, 1, 100);
        base.addUnits("Water", 2, 1, 100);
        List<String> out = pr.advance(1, 0, base, new Crew(), 160);                    // 320 l for the people: 300 + 20 from the store
        assertEquals(300, pr.waterUsed, 1e-9);
        assertTrue(out.stream().anyMatch(s -> s.contains("the people drank 20 l of Tier II") && s.contains("one harm")), out.toString());
        assertEquals(100 - 20, base.count("Water", 1, 0));                             // the scrubbers cool with Tier I first
        assertEquals(100 - 20 - 40, base.count("Water", 2, 0));                        // the people and the kitchen: the best first
        Production.Building kitchen = pr.buildings.stream().filter(b -> b.kind.equals("Kitchen")).findFirst().orElseThrow();
        assertEquals(2, kitchen.waterTier);
        // not enough of any: the room waits and nothing is taken
        Production dry = new Production();
        dry.build("Reactor", 4, "Reactor");
        Production.Building k = dry.build("Kitchen", 3, "K");
        Base none = new Base(null);
        none.addUnits("Water", 1, 1, 10);
        assertTrue(dry.advance(1, 0, none, new Crew(), 0).stream().anyMatch(s -> s.startsWith("K: no power or water")));
        assertEquals(10, none.count("Water", 1, 0));
        assertEquals(-1, k.chargedDay);
    }

    @Test
    void aProductRoomsProductIsNoBetterThanItsWorstWater() {
        // the kitchen cooks with Tier I water: its Tier II meat comes out Tier I (one harm more, 04 §3.9)
        Production pr = new Production();
        pr.build("Reactor", 4, "Reactor");
        Production.Building k = pr.build("Kitchen", 3, "K");
        Base base = new Base(null);
        base.addUnits("Water", 1, 1, 40);
        base.addUnits("Food", 1, 1, 2);
        base.addUnits("Fuel", 1, 1, 1);
        Crew crew = new Crew(worker("C", "Survival", 30));
        pr.start(k, recipe("cook-wasteland-meat"), 1, 1, crew);
        List<String> out = pr.advance(2, 0, base, crew, 0);
        assertNull(k.job, out.toString());
        assertEquals(2, base.count("Food", 1, 1));
        assertEquals(0, base.count("Food", 2, 0));
        assertTrue(out.stream().anyMatch(s -> s.contains("2 Food I R1 (Tier I water)")), out.toString());
        // a recipe that makes water is not capped: its own water input is the product [mine]
        base.addUnits("Water", 2, 1, 40 + 3);                                         // the kitchen's day takes its 40 l of the best first
        base.addUnits("Fuel", 1, 1, 1);
        pr.start(k, recipe("boil-filtered-water"), 1, 1, crew);
        pr.advance(2, 24, base, crew, 0);
        assertNull(k.job);
        assertEquals(3, base.count("Water", 3, 1));
    }

    @Test
    void theRepairJobMendsWornParts() {
        // round 5 [mine, EXAMPLE]: a run = 1 material of the tier + 1 h at the minimum 25; the kit's points (Mr. Fixit x2) to up to 3 parts
        Production pr = new Production();
        Production.Building wb = pr.build("Workbench", 2, "B");
        Base base = new Base(null);
        base.disassemble(Production.piece("Metal Armor", "Chest"), 1, 60);           // 7 hard + 2 soft at 60 %
        base.addUnits("Metal", 2, 1, 5);
        Production.Recipe r = Production.repairRecipe("Hard armour parts", 2);
        assertNotNull(r);
        assertTrue(Production.fits(wb, r));
        assertFalse(Production.fits(wb, Production.repairRecipe("Hard armour parts", 3)));   // the building's tier caps it
        assertNull(Production.RECIPES.get(r.id));                                       // not in the recipe lists
        Crew crew = new Crew(worker("F", "Crafting", 100, "Mr. Fixit"));
        String m = pr.start(wb, r, 0, 1, crew);
        assertTrue(m.contains("until stopped"), m);
        List<String> out = pr.advance(3, 0, base, crew, 0);                            // a run an hour at most; 60 points: 3 to full a run
        assertNull(wb.job, out.toString());
        assertEquals(0, base.worn("Hard armour parts", 2, 1));
        assertEquals(7, base.pools.get(Base.key("Hard armour parts", 2, 1)).fresh);
        assertEquals(5 - 3, base.count("Metal", 2, 1));                                 // three runs: 3 + 3 + 1
        assertTrue(out.stream().anyMatch(s -> s.contains("the repair is done")), out.toString());
        // Jury Rigging: any material of the tier when its own is missing
        base.disassemble(Production.piece("Metal Armor", "Head"), 1, 50);
        base.addUnits("Wood", 2, 1, 1);
        base.takeUnits("Metal", 2, 0, 2);
        Crew rig = new Crew(worker("J", "Crafting", 100, "Jury Rigging"));
        pr.start(wb, r, 1, 1, rig);
        pr.advance(1, 3, base, rig, 0);
        assertEquals(0, base.count("Wood", 2, 0));
        assertEquals(80, base.pools.get(Base.key("Hard armour parts", 2, 1)).average(), 1e-9);   // 2 at 50 % + 30 (no Mr. Fixit)
    }

    @Test
    void theRecipesAreRankedAndTheFullyOutdatedFound() {
        // round 5 (Marek 11-20): "filter both by input and output and that the best recipes are shown on top, and probably hide these
        // fully outdated"
        Production pr = new Production();
        pr.build("Workshop", 2, "W");
        Production.Recipe old = recipe("recast-scrap"), better = recipe("bellows-furnace");
        assertTrue(Production.dominates(better, old));
        assertFalse(Production.dominates(old, better));
        assertNull(pr.outdatedBy(old));                                                // not known yet
        pr.known.add(better.id);
        assertNull(pr.outdatedBy(old));                                                // known, but no kiln to run it
        pr.build("Kiln", 2, "K");
        assertEquals(better, pr.outdatedBy(old));
        // the best first: the highest tier made, then the fewest inputs and hours a unit made
        List<Production.Recipe> metal = Production.rank(Production.RECIPES.values(), "metal ii", "");
        assertFalse(metal.isEmpty());
        for (Production.Recipe r : metal) assertTrue(Production.matches(r.makes, "metal ii"), r.name);
        for (int i = 1; i < metal.size(); i++) {
            Production.Recipe a = metal.get(i - 1), b = metal.get(i);
            assertTrue(Production.madeTier(a, "metal ii") > Production.madeTier(b, "metal ii")
                    || Production.perUnit(a, "metal ii") <= Production.perUnit(b, "metal ii") + 1e-9, a.name + " before " + b.name);
        }
        for (Production.Recipe r : Production.rank(Production.RECIPES.values(), "", "salvage")) assertTrue(Production.matches(r.inputs, "salvage"), r.name);
    }

    @Test
    void daysMustPassWhateverTheHandsAndHarvestsFollowTheFactor() {
        Production pr = new Production();
        pr.build("Reactor", 4, "Reactor");
        Production.Building ws = pr.build("Workshop", 2, "W");
        Base base = cooled();
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
        base.addUnits("Gear and tools", 2, 1, 1);                                      // 17 weeks of upkeep: without a tool the field breaks (round 5)
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
        assertEquals(26, Base.types().size());                                         // round 4: parts, not finished items
    }

    // ------------------------------------------------------------------ round 4 (Marek 11-20)
    @Test
    void everyAssembledItemTakesKnownParts() {
        assertEquals(167, Production.DESIGNS.size());
        for (Production.Design d : Production.DESIGNS.values()) {
            assertFalse(d.parts.isEmpty(), d.key());
            for (Production.Part p : d.parts) assertTrue(Production.TYPES.containsKey(p.type), d.key() + ": " + p.type);
        }
        for (Production.Category c : Production.CATEGORIES)
            assertTrue(Production.DESIGNS.values().stream().anyMatch(d -> d.category.equals(c.name) && d.tier <= 2 && d.rarity == 1), c.name);
        assertNotNull(Production.piece("Power Armor", "Feet"));
        assertTrue(Production.LEGACY.containsKey("Pistol ammo"));
    }

    @Test
    void aBuildingWorksLessAsItsConditionFalls() {
        // "could be good at the start, since it will get worse as condition worsens."
        Production pr = new Production();
        Production.Building r = pr.build("Reactor", 4, "Reactor");
        assertEquals(1, Production.efficiency(r), 1e-9);
        r.condition = 75;
        assertEquals(1, Production.efficiency(r), 1e-9);
        r.condition = 37.5;
        assertEquals(0.75, Production.efficiency(r), 1e-9);
        assertEquals(150, pr.powerCap(), 1e-9);
        r.condition = 0;
        assertEquals(0, pr.powerCap(), 1e-9);
        // the crew's work too: a workshop at 37.5 %, a worker at twice the minimum: 2 h of work an hour x 0.75
        r.condition = 100;
        Production.Building ws = pr.build("Workshop", 2, "W");
        ws.condition = 37.5;
        Base base = cooled();
        base.addUnits("Metal", 1, 1, 3);
        base.addUnits("Fuel", 2, 1, 2);
        Crew crew = new Crew(worker("H", "Crafting", 60));
        pr.advance(1, 0, base, crew, 0);
        ws.upkeepDone = 99;                                                            // the week's upkeep done: the hour is the job's
        pr.start(ws, recipe("recast-scrap"), 1, 1, crew);
        pr.advance(1, 1, base, crew, 0);
        assertEquals(1.5, ws.job.work, 1e-9);
        assertTrue(pr.status(ws).contains("condition 38 % (works at 75 %)"), pr.status(ws));
    }

    @Test
    void theProductionPerksAreOfferedAndRun() {
        // round 4: "Production perks - yes, add them as part of this update."
        for (String p : List.of("Salvager", "Assembly Line", "Master Craftsman", "Cook", "Good Harvest", "Quartermaster", "Foreman"))
            assertFalse(Data.PERKS.get(p).parked(), p);
        // part 8; Reverse Engineer back with it (round 5: "seriously strong now")
        for (String p : List.of("Data Miner", "Prototype", "Lab Rat", "Eureka", "Reverse Engineer")) assertTrue(Data.PERKS.get(p).parked(), p);
        // Quartermaster: the faction's upkeep -20 % (02c) [the base's buildings: mine]
        Production pr = new Production();
        Production.Building ws = pr.build("Workshop", 2, "W");
        pr.advance(1, 0, new Base(null), new Crew(worker("Q", "Economy", 100, "Quartermaster")), 0);
        assertEquals(3.2, pr.upkeep(ws), 1e-9);
        pr.advance(1, 1, new Base(null), new Crew(), 0);
        assertEquals(4, pr.upkeep(ws), 1e-9);
        // Salvager: +50 % from found junk taken apart; the half carried to the next run
        assertTrue(Production.salvage(recipe("take-apart-junk")));
        assertFalse(Production.salvage(recipe("recast-scrap")));
        Production.Building wb = pr.build("Workbench", 2, "B");
        Base base = new Base(null);
        base.addUnits("Salvage", 1, 1, 6);
        Crew crew = new Crew(worker("S", "Crafting", 50, "Salvager"));
        pr.start(wb, recipe("take-apart-junk"), 2, 1, crew);
        pr.advance(10, 2, base, crew, 0);
        assertNull(wb.job);
        assertEquals(3, base.count("Metal", 1, 1));                                   // 1.5 + 1.5
    }
}
