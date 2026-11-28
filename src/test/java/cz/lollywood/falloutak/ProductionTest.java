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
        assertEquals(400, pr.waterCap(), 1e-9);                                       // round 8: "400 l sounds more reasonable"
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
        // the week's upkeep comes first: Tier II = 3 h (round 6); then 4 h of recipe at x2 = 2 h a run
        pr.advance(4, 0, base, crew, 0);
        assertEquals(0, base.count("Metal", 2, 0));
        assertEquals(3, base.count("Metal", 1, 0));                                    // the first run's inputs are in
        pr.advance(1, 4, base, crew, 0);
        assertEquals(1, base.count("Metal", 2, 1));
        pr.advance(2, 5, base, crew, 0);
        assertEquals(2, base.count("Metal", 2, 1));
        assertNull(ws.job);                                                            // two runs: done
        assertTrue(ws.crew.isEmpty());
        assertEquals("", crew.activity.get("person:Honza"));                           // idle again
        assertEquals(0, base.count("Metal", 1, 0));
        assertEquals(0, base.count("Fuel", 2, 0));
        assertTrue(base.unlocked.contains(Base.key("Metal", 2, 1)));                  // made in the base: unlocked
        assertEquals(97, base.pools.get(Base.key("Gear and tools", 2, 1)).average(), 1e-9);   // 3 h of upkeep: the TOOL 1 % an hour
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
        // round 8: ten drink 20 l and wash 20 (the scrubbers' 10 l of condensate first), the kitchen 3 l each; the scrubbers draw none
        assertEquals(20 + (20 - 10) + 30, pr.waterUsed, 1e-9);
        assertEquals(0, base.count("Water", 2, 0));                                  // the condensate went to the washing
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
    void aMissedWeekOfUpkeepWearsTheBuildingByItsTierAndItsCrewRepairsIt() {
        // round 6 (Marek 11-20): "The buildings must have condition lost in some absolute number - bigger tier, the less maintanance
        // and the longer it lasts abandoned." · "players must not be forced out before first year" [the numbers EXAMPLE]
        Production pr = new Production();
        pr.shelter();
        Base base = new Base(null);
        List<String> out = pr.advance(170, 0, base, new Crew(), 0);
        Production.Building reactor = pr.buildings.get(0), filtration = pr.buildings.get(1);
        Production.Building ws = pr.buildings.stream().filter(b -> b.kind.equals("Workshop")).findFirst().orElseThrow();
        Production.Building dorm = pr.buildings.stream().filter(b -> b.kind.equals("Dormitories")).findFirst().orElseThrow();
        assertEquals(100 - 100.0 / 1300, reactor.condition, 1e-9);                     // a week running, T IV: 0 in 25 years
        assertEquals(100 - 100.0 / 260, filtration.condition, 1e-9);                   // T III: 0 in 5 years
        assertEquals(100 - 100.0 / 780, ws.condition, 1e-9);                           // T II idle (no job): 0 in 15 years abandoned
        assertTrue(out.contains("Reactor: upkeep missed a week, condition 100 → 99.9 %"), out.toString());
        // the first year with no tools: everything still works fully
        out = pr.advance(52 * 168, 170, base, new Crew(), 0);
        assertEquals(100 - 53 * 100.0 / 260, filtration.condition, 1e-9);              // 79.6 %
        assertEquals(1, Production.efficiency(filtration), 1e-9);
        assertEquals(100 - 53 * 100.0 / 1300, reactor.condition, 1e-9);
        assertEquals(100, dorm.condition, 1e-9);                                       // no upkeep, no wear
        assertTrue(out.contains("Water filtration: upkeep missed 52 weeks, condition 99.6 → 79.6 %"), out.toString());
        // ... and down below 75 % it says so
        out = pr.advance(3 * 168, 170 + 52 * 168, base, new Crew(), 0);
        assertTrue(out.contains("Water filtration: upkeep missed 3 weeks, condition 79.6 → 78.5 %"), out.toString());
        out = pr.advance(10 * 168, 170 + 55 * 168, base, new Crew(), 0);
        assertTrue(out.contains("Water filtration: upkeep missed 10 weeks, condition 78.5 → 74.6 %: it works less from now"), out.toString());
        // the GM's event (round 6: "The prologue should be full of events") [mine]
        double r0 = reactor.condition;
        assertEquals("Reactor: condition " + Production.pct(r0) + " → " + Production.pct(r0 - 30) + " % (works at 93 %)", pr.damage(reactor, 30));
        Crew crew = new Crew(worker("Tech", "Engineering", 40));
        pr.pick(reactor, crew);                                                        // an automatic room's crew: its upkeep
        assertEquals(List.of("person:Tech"), reactor.crew);
        // round 5: "upkeep needs tools of the same tier": without a Tier IV tool nothing moves
        double t = 170 + 65 * 168;                                                     // week 65 starts at 65 * 168
        base.addUnits("Gear and tools", 3, 1, 1);                                      // Tier III: not enough for the reactor
        pr.advance(5, t, base, crew, 0);
        assertEquals(r0 - 30, reactor.condition, 1e-9);
        assertTrue(pr.status(reactor).contains("WAITS: it needs Gear and tools of Tier IV or better"), pr.status(reactor));
        base.addUnits("Gear and tools", 4, 1, 1);
        pr.advance(1 + 10, t + 5, base, crew, 0);                                      // 1 h upkeep (Tier IV), then repairs +0.4 an hour
        assertEquals(r0 - 30 + 4, reactor.condition, 1e-9);                            // round 7: a Tier IV room far more time demanding
        assertEquals((100 - reactor.condition) / 0.4, Production.repairHours(reactor), 1e-9);
        assertTrue(pr.status(reactor).contains(", repair " + Txt.f((100 - reactor.condition) / 0.4) + " crew h"), pr.status(reactor));
        assertEquals(89, base.pools.get(Base.key("Gear and tools", 4, 1)).average(), 1e-9);   // 11 h of work: the TOOL 11 %
        assertEquals(1, base.pools.get(Base.key("Gear and tools", 3, 1)).fresh);      // the Tier III one untouched
    }

    @Test
    void aRoomsRepairIsSlowerTheHigherItsTier() {
        // round 7 (Marek 11-21: "Yes, needed far less often, but far more time demanding.") [the numbers EXAMPLE]
        assertEquals(4, Production.repairPerHour(1), 1e-9);
        assertEquals(2, Production.repairPerHour(2), 1e-9);
        assertEquals(1, Production.repairPerHour(3), 1e-9);
        assertEquals(0.4, Production.repairPerHour(4), 1e-9);
        Production pr = new Production();
        int[] fromZero = {25, 50, 100, 250};
        for (int tier = 1; tier <= 4; tier++) {
            Production.Building b = pr.build("Workshop", tier, "W" + tier);
            b.condition = 0;
            assertEquals(fromZero[tier - 1], Production.repairHours(b), 1e-9);
        }
        // two workers repair twice as fast (their hours add up), the tool wears 1 % a crew hour
        Production.Building ws = pr.build("Workshop", 2, "W");
        ws.condition = 50;
        ws.upkeepWeek = 0;                                                             // this week's upkeep already done
        ws.upkeepDone = ws.upkeepNeed();
        Base base = new Base(null);
        base.addUnits("Gear and tools", 2, 1, 1);
        Crew crew = new Crew(worker("A", "Crafting", 40), worker("B", "Crafting", 40));
        pr.pick(ws, crew);
        pr.advance(5, 1, base, crew, 0);
        assertEquals(50 + 2 * 5 * 2, ws.condition, 1e-9);
        assertEquals(90, base.pools.get(Base.key("Gear and tools", 2, 1)).average(), 1e-9);
    }

    @Test
    void aJobRoomWearsOnlyTheHoursItWorks() {
        // round 6: a missed week = its running hours at the running rate, the rest idle [EXAMPLE]
        Production pr = new Production();
        Production.Building ws = pr.build("Workshop", 2, "W");
        ws.upkeepWeek = 0;
        ws.ranHours = 40;
        assertEquals(40 * 100.0 / (104 * 168) + 128 * 100.0 / (780 * 168), pr.weekWear(ws), 1e-9);
        ws.upkeepDone = 1.5;                                                           // half of the 3 h done: half the wear
        assertEquals((40 * 100.0 / (104 * 168) + 128 * 100.0 / (780 * 168)) / 2, pr.weekWear(ws), 1e-9);
        assertEquals(3, ws.upkeepNeed(), 1e-9);
        assertEquals(4, pr.build("Workbench", 1, "B").upkeepNeed(), 1e-9);            // the lower the tier, the more upkeep
        assertEquals(1, pr.build("Workshop", 4, "W4").upkeepNeed(), 1e-9);
    }

    @Test
    void waterOfALowerTierComesFromTheStore() {
        // round 5 (Marek 11-20): "Lower-tier water - yes, agreed." · "workshop, kiln, still, scrubbers - I would make it rule that these
        // use T1 water first (reversed priority)."
        // round 8 ("Water: switch to the realistic version - yes"): 100 people drink 200 l and wash 200 l, the kitchen 3 l each
        Production pr = new Production();
        pr.shelter();
        Base base = new Base(null);
        base.addUnits("Water", 1, 1, 100);
        base.addUnits("Water", 2, 1, 100);
        base.addUnits("Food", 3, 1, 300);                                               // Marek 11-24: the people eat 3 portions a day
        List<String> out = pr.advance(1, 0, base, new Crew(), 100);
        // the people drink 200 of the filtration's 400; round 9: they wash with the scrubbers' condensate of the day (100) and the
        // store's Tier II (100), never Tier I unless chosen; the kitchen's 300 l: the filtration's other 200, then the store's best
        // first (only Tier I is left: 100)
        assertEquals(400, pr.waterUsed, 1e-9);
        assertEquals(0, base.count("Water", 1, 0));
        assertEquals(0, base.count("Water", 2, 0));
        Production.Building kitchen = pr.buildings.stream().filter(b -> b.kind.equals("Kitchen")).findFirst().orElseThrow();
        assertEquals(1, kitchen.waterTier);                                            // its food no better than Tier I water
        assertTrue(out.stream().noneMatch(s -> s.contains("short") || s.contains("no power or water")), out.toString());
        // 210 people: they drink 20 l from the store (Tier II), wash with the condensate 210 and Tier II 80, 130 l short; the kitchen waits
        Production big = new Production();
        big.shelter();
        Base b2 = new Base(null);
        b2.addUnits("Water", 1, 1, 100);
        b2.addUnits("Water", 2, 1, 100);
        out = big.advance(1, 0, b2, new Crew(), 210);
        assertTrue(out.stream().anyMatch(s -> s.contains("the people drank 20 l of Tier II") && s.contains("one harm")), out.toString());
        assertTrue(out.contains("Washing: 130 l short today (420 l for 210 people)"), out.toString());
        assertTrue(out.stream().anyMatch(s -> s.startsWith("Kitchen and food store: no power or water")), out.toString());
        // not enough of any: the room waits and nothing is taken
        Production dry = new Production();
        dry.build("Reactor", 4, "Reactor");
        Production.Building k = dry.build("Kitchen", 3, "K");
        Base none = new Base(null);
        none.addUnits("Water", 1, 1, 15);
        assertTrue(dry.advance(1, 0, none, new Crew(), 4).stream().anyMatch(s -> s.startsWith("K: no power or water")));
        assertEquals(15 - 8, none.count("Water", 1, 0));                              // they drank 8, never washed in Tier I, the kitchen took none
        assertEquals(-1, k.chargedDay);
    }

    @Test
    void theWashingTakesTheCondensateFirstAndTheWoundedGetTheCleanWater() {
        // round 9 (Marek 11-21): "Washing takes the scrubbers' condensed water of the day first" - seems usable. · "I think germ water
        // is fine without wound." · "that one can be selected, so choosing the scrubber water and choosing T2 is the same answer"
        Production pr = new Production();
        pr.shelter();
        Base base = new Base(null);
        base.addUnits("Water", 1, 1, 50);
        base.addUnits("Water", 2, 1, 5);
        List<String> out = pr.advance(1, 0, base, new Crew(), 10, 4);
        // the 4 wounded's 8 l from the filtration; the 6 others' 12 l: the day's condensate 10, then the store's Tier II 2 (as filtered)
        assertEquals(3, base.count("Water", 2, 0));                                  // 5 − 2, and no condensate left for the store
        assertEquals(50, base.count("Water", 1, 0));                                 // Tier I not chosen
        assertTrue(out.stream().noneMatch(s -> s.startsWith("Washing")), out.toString());   // germs on whole skin: no harm, none short
        assertEquals(0, pr.unwashed, 1e-9);
        // with fewer people the condensate is left over and goes into the store as Water II the same day
        Production small = new Production();
        small.shelter();
        small.washTier2 = false;                                                    // the players wash with clean water only
        Base b2 = new Base(null);
        small.advance(1, 0, b2, new Crew(), 10, 0);
        assertEquals(10, b2.count("Water", 2, 0));                                  // the whole condensate stored
        // round 11 (Marek 11-21): "Washing wounded with condensed water is worse, so boiled is used here instead." The wounded with
        // only the store's Tier II: taken as boiled water, its rads told, no infection roll
        Production poor = new Production();
        poor.build("Reactor", 4, "Reactor");
        Base b3 = new Base(null);
        b3.addUnits("Water", 2, 1, 10);
        out = poor.advance(1, 0, b3, new Crew(), 3, 2);
        assertTrue(out.stream().anyMatch(s -> s.contains("2 wounded washed in the store's Tier II water as boiled water: +4 rads each, no germs")), out.toString());
        assertTrue(out.stream().noneMatch(s -> s.contains("wound-infection")), out.toString());
        assertTrue(out.contains("Washing: 2 l short today (6 l for 3 people)"), out.toString());
        // the order: the wounded the clean water, then the store's Tier II (boiled), then the day's condensate (germs: a roll)
        assertArrayEquals(new int[]{Production.FILTRATION, 3, 2, Production.CONDENSATE}, poor.washOrder(true));
        assertArrayEquals(new int[]{Production.CONDENSATE, 2, Production.FILTRATION, 3}, poor.washOrder(false));
        double[] hurt = new double[6], whole = new double[6];
        hurt[Production.CONDENSATE] = 4;
        whole[2] = 6;                                                                 // the unwounded's filtered water: nothing told
        assertEquals(List.of("Washing: 2 wounded washed in the scrubbers' condensate: a wound-infection roll at 2 % each"),
                Production.washHarm(hurt, whole));
        // one row, two named items: the store's Water II is handed out as either
        assertTrue(Production.TYPES.get("Water").items.stream().filter(n -> n.tier == 2).map(n -> n.name).toList()
                .containsAll(List.of("Boiled water", "Filtered water")));
    }

    @Test
    void tierOneOnlyWhenChosenAndGoingUnwashedMakesThePeopleDirtyThenFilthy() {
        // round 9 (Marek 11-21): "we should make rules for the penalities. At least it should make infections and wounds/healing worse."
        Production pr = new Production();
        pr.build("Reactor", 4, "Reactor");
        pr.washTier1 = true;
        Base base = new Base(null);
        base.addUnits("Water", 1, 1, 30);
        List<String> out = pr.advance(1, 0, base, new Crew(), 5, 0);
        assertEquals(10, base.count("Water", 1, 0));                                 // 10 drunk, 10 washed with
        assertTrue(out.stream().anyMatch(s -> s.contains("5 people washed in Tier I water: +4 rads each")), out.toString());
        // a week with no water at all: 1, 2, ... 7 days unwashed on average
        Production dry = new Production();
        dry.build("Reactor", 4, "Reactor");
        List<String> week = dry.advance(24 * 3, 0, new Base(null), new Crew(), 5, 0);
        assertEquals(3, dry.unwashed, 1e-9);
        assertTrue(week.stream().anyMatch(s -> s.contains("Dirty (unwashed 3 days): wound-infection rolls x1.5")), week.toString());
        week = dry.advance(24 * 4, 72, new Base(null), new Crew(), 5, 0);
        assertEquals(7, dry.unwashed, 1e-9);
        assertTrue(week.stream().anyMatch(s -> s.contains("Filthy (unwashed 7 days)")), week.toString());
        // half the water every day: everyone washes every other day, about one day unwashed: no penalty
        Production half = new Production();
        half.build("Reactor", 4, "Reactor");
        for (int d = 0; d < 30; d++) {
            Base b = new Base(null);
            b.addUnits("Water", 3, 1, 10 + 5);                                      // 10 to drink, 5 of the 10 to wash
            half.advance(24, d * 24, b, new Crew(), 5, 0);
        }
        assertEquals(1, half.unwashed, 0.01);
        assertEquals(1, Production.infectionFactor(2.9), 0);
        assertEquals(1.5, Production.infectionFactor(3), 0);
        assertEquals(2, Production.infectionFactor(7), 0);
        // the healing rate −1 when filthy, never below 0
        Character c = new Character();
        int clean = c.healingRate();
        c.unwashedDays = 7;
        assertEquals(Math.max(0, clean - 1), c.healingRate());
        c.unwashedDays = 6.9;
        assertEquals(clean, c.healingRate());
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
        base.addUnits("Food", 3, 1, 15);                                                // the people's 15 portions, the best first
        base.addUnits("Fuel", 1, 1, 1);
        Crew crew = new Crew(worker("C", "Survival", 30));
        pr.start(k, recipe("cook-wasteland-meat"), 1, 1, crew);
        List<String> out = pr.advance(2, 0, base, crew, 5);                            // 5 people: drink 10, wash 10, the kitchen 15
        assertNull(k.job, out.toString());
        assertEquals(2, base.count("Food", 1, 1));
        assertEquals(0, base.count("Food", 2, 0));
        assertTrue(out.stream().anyMatch(s -> s.contains("2 Food I R1 (Tier I water)")), out.toString());
        // a recipe that makes water is not capped: its own water input is the product [mine]
        base.addUnits("Water", 2, 1, 3);                                              // nobody in the base that day: the kitchen draws none
        base.addUnits("Fuel", 1, 1, 1);
        pr.start(k, recipe("boil-filtered-water"), 1, 1, crew);
        pr.advance(2, 24, base, crew, 0);
        assertNull(k.job);
        assertEquals(3, base.count("Water", 3, 1));
    }

    @Test
    void aBetterWaterStandsInButNeverAsGoodAsTheWaterMade() {
        // round 8 (Marek 11-21): "yes, fix that, but with care. Adding T III water to recipe making T II water from T I would be idiotic."
        Production.Recipe boil = recipe("boil-water"), distil = recipe("distil-water"), brew = recipe("home-brew");
        Production.Ttr boilW = boil.inputs.stream().filter(i -> i.type.equals("Water")).findFirst().orElseThrow();
        Production.Ttr distilW = distil.inputs.stream().filter(i -> i.type.equals("Water")).findFirst().orElseThrow();
        Production.Ttr brewW = brew.inputs.stream().filter(i -> i.type.equals("Water")).findFirst().orElseThrow();
        assertEquals(1, Production.topTier(boil, boilW));                              // I -> II: Tier I only
        assertEquals(2, Production.topTier(distil, distilW));                          // I -> III: Tier II will do too
        assertEquals(4, Production.topTier(brew, brewW));                              // not water made: any better water
        Production.Ttr fuel = brew.inputs.stream().filter(i -> i.type.equals("Food")).findFirst().orElseThrow();
        assertEquals(1, Production.topTier(brew, fuel));                               // only Water stands in
        Production pr = new Production();
        pr.build("Reactor", 4, "Reactor");
        Production.Building k = pr.build("Kitchen", 3, "K");
        Base base = new Base(null);
        base.addUnits("Food", 1, 1, 3);
        base.addUnits("Water", 3, 1, 1);                                               // no Tier I in the store, only clean water
        Crew crew = new Crew(worker("C", "Chemistry", 30));
        pr.start(k, brew, 1, 1, crew);
        pr.advance(1, 0, base, crew, 0);
        assertTrue(k.job.inputsTaken, k.job.waiting);                                 // the home brew takes the Tier III water
        assertEquals(0, base.count("Water", 3, 0));
        // boiling never takes the better water
        Production pr2 = new Production();
        pr2.build("Reactor", 4, "Reactor");
        Production.Building k2 = pr2.build("Kitchen", 3, "K");
        Base b2 = new Base(null);
        b2.addUnits("Water", 3, 1, 3);
        b2.addUnits("Fuel", 1, 1, 1);
        Crew c2 = new Crew(worker("S", "Survival", 30));
        pr2.start(k2, boil, 1, 1, c2);
        pr2.advance(1, 0, b2, c2, 0);
        assertEquals("needs 3 Water I", k2.job.waiting);
        assertEquals(3, b2.count("Water", 3, 0));
    }

    @Test
    void theRepairJobMendsWornParts() {
        // round 5 [mine, EXAMPLE]: a run = 1 material of the tier + 1 h at the minimum 25; the kit's points (Mr. Fixit x2) to up to 3 parts
        Production pr = new Production();
        Production.Building wb = pr.build("Workbench", 2, "B");
        Base base = new Base(null);
        base.disassemble(Production.piece("Metal Armor", "Chest"), 1, 60);           // 8 hard + 2 soft at 60 %
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
        assertEquals(8, base.pools.get(Base.key("Hard armour parts", 2, 1)).fresh);
        assertEquals(5 - 3, base.count("Metal", 2, 1));                                 // three runs: 3 + 3 + 2
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
        // on clean ground half comes in Tier III (09e round 5): 2.5 + 2.5, each half's rest carried to the next harvest
        assertEquals(2, base.count("Food", 2, 1));
        assertEquals(2, base.count("Food", 3, 1));
        assertEquals(0, base.count("Seeds", 2, 1));                                    // the seed lot: 1 x 50 % = 0
    }

    @Test
    void aGrowRoomIsBuiltFromTheStartAndGrowsTheFieldsWaysIndoors() {
        // part 8 round 8 (Marek 11-21: "Grow room III can be build from the start, if players bring seeds and soil, it makes sense."):
        // the soil, the grow lamps and the racks; no research
        Production.Recipe build = recipe("build-a-grow-room");
        assertTrue(build.atStart());
        assertEquals("Grow room", build.builds.kind);
        assertEquals(3, build.builds.tier);
        Production pr = new Production();
        pr.build("Reactor", 4, "Reactor");
        Base base = new Base(null);
        base.addUnits("Stone and glass", 1, 1, 20);                                     // the soil (two flowerpots hauls)
        base.addUnits("Electronics", 3, 1, 2);                                          // the grow lamps
        base.addUnits("Wood", 2, 1, 4);
        Crew gardener = new Crew(worker("G", "Biology", 80));
        assertNotNull(pr.startAt("Base", build, 1, 1, gardener));
        pr.advance(24, 0, base, gardener, 0);
        Production.Building room = pr.buildings.stream().filter(b -> "Grow room".equals(b.kind)).findFirst().orElseThrow();
        assertEquals(3, room.tier);
        assertEquals(0, base.count("Stone and glass", 1, 1));
        // round 7 ("Should it grow the field's Tier II ways indoors" - yes.): the whole harvest under lamps
        pr.harvest = 50;
        base.addUnits("Water", 3, 1, 3000);                                             // the room draws its water every day
        base.addUnits("Seeds", 2, 1, 1);
        base.addUnits("Gear and tools", 3, 1, 1);
        Crew farmer = new Crew(worker("F", "Biology", 15));
        assertTrue(Production.fits(room, recipe("field-potatoes")));
        assertTrue(Production.fits(room, recipe("select-seed")));
        assertTrue(Production.fits(room, recipe("potatoes-under-lamps")));                 // the pre-war seed's Food III
        pr.start(room, recipe("field-potatoes"), 1, 1, farmer);
        pr.advance(121 * 24, 14 * 24, base, farmer, 0);
        assertEquals(10, base.count("Food", 2, 1));                                     // no harvest factor indoors
        assertEquals(1, base.count("Seeds", 2, 1));
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
        b.addUnits("Gear and tools", 2, 2, 1);   // 0.112.0, the TTR: the repair kit out of the gas filters' row
        Base.Pool tools = b.pools.get(Base.key("Gear and tools", 2, 2));
        assertTrue(b.choices(tools).contains("Armour repair kit"));
        assertFalse(b.choices(tools).contains("Gas filter"));
        assertEquals("gear:Armour repair kit", Items.storeId("Gear and tools", 2, 2, "Armour repair kit"));
        assertArrayEquals(new String[]{"Chems", "4", "1", "Buffout"}, Items.ttrOf("chem:Buffout"));   // 04i: the 2040s = Tier IV
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
        assertEquals(410, Production.DESIGNS.size());   // 0.111.0: the sleep darts (opium, the tranquilliser and its double dose, the knockout; 04j §7h); 0.104.0: the traps by kind (05d §7c); 0.103.0: the throws, blows and darts, the stones and darts; 0.102.0: the six shields; 0.101.0: the armour of rounds 7-8 (20 sets, their pieces); 0.100.0: black powder (4 guns, 4 loads); 0.97.0: the plasma saber; 0.96.0: Tier IV of 2046, the accessories; 0.95.0: every weapon type Tier I-IV; 0.94.0: the calibres' loads, the region's guns, the sci-fi (05d)
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
        assertTrue(pr.status(ws).contains("condition 37.5 % (works at 75 %)"), pr.status(ws));
    }

    @Test
    void theProductionPerksAreOfferedAndRun() {
        // round 4: "Production perks - yes, add them as part of this update."
        for (String p : List.of("Salvager", "Assembly Line", "Master Craftsman", "Cook", "Good Harvest", "Quartermaster", "Foreman"))
            assertFalse(Data.PERKS.get(p).parked(), p);
        // part 8 in the plugin (0.51.0): the research ones offered in their RULED forms, Reverse Engineer with them
        for (String p : List.of("Data Miner", "Prototype", "Lab Rat", "Flash of Insight", "Reverse Engineer")) assertFalse(Data.PERKS.get(p).parked(), p);
        // Quartermaster: the faction's upkeep -20 % (02c) [the base's buildings: mine]
        Production pr = new Production();
        Production.Building ws = pr.build("Workshop", 2, "W");
        pr.advance(1, 0, new Base(null), new Crew(worker("Q", "Economy", 100, "Quartermaster")), 0);
        assertEquals(2.4, pr.upkeep(ws), 1e-9);
        pr.advance(1, 1, new Base(null), new Crew(), 0);
        assertEquals(3, pr.upkeep(ws), 1e-9);
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
