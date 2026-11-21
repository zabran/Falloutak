package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Part 8, research (`08-RESEARCH.md` §3, RULED rounds 1-3): the cards, the shares, theory, experiments, the prototype and its test. */
class ResearchTest {
    /** A workforce that also remembers the XP it was given. */
    static final class Crew implements Production.Workforce {
        final List<Production.Worker> list = new ArrayList<>();
        final Map<String, Long> xp = new HashMap<>();

        Crew(Production.Worker... ws) { list.addAll(List.of(ws)); }

        @Override public List<Production.Worker> all() { return list; }

        @Override public void award(String id, long n, String why) { xp.merge(id, n, Long::sum); }
    }

    static Research.Card card(String id) {
        Research.Card c = Research.CARDS.get(id);
        assertNotNull(c, id);
        return c;
    }

    /** A base with power, a laboratory and the water its days take. */
    static Production base(Base store) {
        Production pr = new Production();
        pr.build("Reactor", 4, "Reactor");
        pr.build("Laboratory", 2, "Lab");
        store.addUnits("Water", 1, 1, 300);
        return pr;
    }

    /** A seed whose first d100 is above (or at most) this. */
    static long seedFor(int atLeast, int atMost) {
        for (long s = 1; ; s++) { int r = 1 + new Random(s).nextInt(100); if (r >= atLeast && r <= atMost) return s; }
    }

    @Test
    void theCardsAreTheCataloguesResearchedWays() {
        assertEquals(29, Research.CARDS.size());
        assertEquals(2, Research.CARDS.values().stream().filter(c -> c.parked).count());     // gene research PARKED (Marek 11-13)
        int[] gate = {0, 25, 50, 100, 150};
        for (Research.Card c : Research.CARDS.values()) {
            assertEquals(100 * c.tier * c.tier * c.rarity, c.points, c.name);              // RULED round 1: P = 100 x T^2 x R
            assertTrue(c.gate >= gate[c.tier], c.name);
            for (String r : c.recipes) assertEquals(c.name, Production.RECIPES.get(r).research, c.name);
            assertNotNull(Research.jobFor(c, "theory"), c.name);
            assertEquals(c.tier, Research.jobFor(c, "experiment").inputs.get(0).tier, c.name);   // round 2: the apparatus of the tier
        }
        Research.Card still = card("reflux-still");
        assertEquals(400, still.points);
        assertEquals(50, still.gate);
        assertEquals("Chemistry", still.science);
        Production.Recipe proto = Research.jobFor(still, "prototype");
        assertEquals("Still", proto.building);
        assertEquals(8, proto.hours, 1e-9);                                                // twice the way's 4 hours
        assertEquals(2, card("laboratory-iii").lab);                                       // a laboratory's next tier runs in the one below
        assertEquals(List.of("laboratory-iii"), card("laboratory-iv").needs);
        assertTrue(Production.KINDS.containsKey("Laboratory"));
        assertEquals("start", Production.RECIPES.get("build-a-laboratory").known);
    }

    @Test
    void theSharesThenTheoryThenThePrototypeAndItsTest() {
        Base store = new Base(null);
        Production pr = base(store);
        Production.Worker chem = ProductionTest.worker("Chem", "Chemistry", 75, "Prototype");   // 1.5 points an hour (75 / 50)
        Crew crew = new Crew(chem);
        Research.Card c = card("reflux-still");
        Production.Building lab = pr.buildings.get(1);
        assertEquals(0, Research.progress(pr, c), 1e-9);
        // the GM's find and achievement (RULED round 1: a find up to 50 %, the achievement 20 %)
        assertTrue(Research.find(pr, c, 30, false, crew).endsWith("→ 30 %"));
        assertTrue(Research.achievement(pr, c).endsWith("→ 50 %"));
        assertTrue(Research.achievement(pr, c).startsWith("!"));                           // once
        // two experiments in the laboratory: the apparatus used up, no roll, 10 % each (round 1: "I would not roll here.")
        store.addUnits("Electronics", 2, 1, 4);
        store.addUnits("Machinery", 2, 1, 4);
        Production.Recipe ex = Research.jobFor(c, "experiment");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String m = pr.start(lab, ex, 1, 1, crew);
            assertFalse(m.startsWith("!"), m);
            out.addAll(pr.advance(6, 6 * i, store, crew, 1, 0));
        }
        assertTrue(out.contains("Lab: Experiment: Reflux still done (1 of 2) +10 % → 60 %"), out.toString());
        assertTrue(out.contains("Lab: Experiment: Reflux still done (2 of 2) +10 % → 70 %"), out.toString());
        assertEquals(0, store.count("Electronics", 2, 0));
        assertEquals(0, store.count("Machinery", 2, 0));
        assertNull(lab.job);
        // an inspiration (the GM's): 10 %; then the theory for the rest, 20 % of 400 = 80 points at 1.5 an hour
        Research.inspiration(pr, c, crew);
        assertEquals(80, Research.theoryLeft(pr, c), 1e-9);
        assertFalse(pr.start(lab, Research.jobFor(c, "theory"), 0, 1, crew).startsWith("!"));
        out = pr.advance(80, 12, store, crew, 1, 0);
        assertTrue(out.contains("Lab: Reflux still: the theory is done (100 %): the prototype next"), out.toString());
        assertEquals(80, pr.research.get(c.id).theory, 1e-9);
        assertNull(lab.job);
        assertTrue(Research.ready(pr, c, crew));
        // the prototype in a still, twice the hours, then the test: Prototype's first test passes
        Production.Building still = pr.build("Still", 2, "Still");
        store.addUnits("Food", 1, 1, 3);
        store.addUnits("Fuel", 1, 1, 1);
        assertFalse(pr.start(still, Research.jobFor(c, "prototype"), 1, 1, crew).startsWith("!"));
        out = pr.advance(12, 92, store, crew, 1, 0);
        assertTrue(out.stream().anyMatch(s -> s.startsWith("Still: Prototype: Reflux still: its first test passes (Prototype): PASSED. The base knows Reflux still now")), out.toString());
        assertTrue(pr.known.contains("reflux-still"));
        assertTrue(Research.done(pr, c));
        assertEquals(2, store.count("Chemicals", 2, 1));                                    // the prototype works: its 2 Chemicals II
        assertEquals(200L, crew.xp.get(chem.id()));                                         // 100 x T x R to each researcher (06b)
        // related research (round 2): a finished card of the line gives 5 % to the others of it
        assertEquals(5, Research.progress(pr, card("solar-still")), 1e-9);                  // water, distilling
        assertEquals(0, Research.progress(pr, card("saw-pit")), 1e-9);
    }

    @Test
    void aFailedTestAtRarityOneGoesToTheGmAndEachFailureHelpsTheNext() {
        Base store = new Base(null);
        Production pr = base(store);
        Production.Worker chem = ProductionTest.worker("Chem", "Chemistry", 50);            // at the gate: the test 50 %
        Crew crew = new Crew(chem);
        Research.Card c = card("reflux-still");
        Research.Project p = Research.project(pr, c);
        p.find = 50; p.achievement = 20; p.experiments = 20; p.inspiration = 10;           // 100 % without theory (no floor, round 2)
        assertEquals(0, Research.theoryLeft(pr, c), 1e-9);
        Production.Building still = pr.build("Still", 2, "Still");
        store.addUnits("Food", 1, 1, 6);
        store.addUnits("Fuel", 1, 1, 2);
        pr.seed(seedFor(51, 99));                                                         // a roll over 50: failed
        pr.start(still, Research.jobFor(c, "prototype"), 1, 1, crew);
        List<String> out = pr.advance(24, 0, store, crew, 1, 0);
        assertTrue(out.stream().anyMatch(s -> s.contains("FAILED at rarity 1: the GM rules")), out.toString());
        assertEquals(1, p.failures);
        assertEquals(1, pr.pending.size());
        assertEquals(0, store.count("Chemicals", 2, 0));
        assertFalse(pr.known.contains("reflux-still"));
        // the GM rules it (round 2: "I would rule each on case by case basis when it happens...")
        assertEquals("Prototype: Reflux still (2 Chemicals II): comes out as 2 Chemicals I R1",
                Research.rule(pr, store, pr.pending.get(0), 1, 1, false));
        assertEquals(2, store.count("Chemicals", 1, 1));
        assertTrue(pr.pending.isEmpty());
        // a new prototype: the test is 60 % now, so a 55 passes
        pr.seed(seedFor(55, 55));
        pr.start(still, Research.jobFor(c, "prototype"), 1, 1, crew);
        out = pr.advance(24, 24, store, crew, 1, 0);
        assertTrue(out.stream().anyMatch(s -> s.contains("the test: rolled 55 of 60 %: PASSED")), out.toString());
        assertTrue(pr.known.contains("reflux-still"));
    }

    @Test
    void thePerksInTheirRuledForms() {
        Research.Card c = card("reflux-still");
        // Lab Rat (round 2): experiments 15 % each and half the apparatus (rounded up)
        Base store = new Base(null);
        Production pr = base(store);
        Production.Worker rat = ProductionTest.worker("Rat", "Chemistry", 100, "Lab Rat");
        Crew crew = new Crew(rat);
        store.addUnits("Electronics", 2, 1, 1);
        store.addUnits("Machinery", 2, 1, 1);
        pr.start(pr.buildings.get(1), Research.jobFor(c, "experiment"), 1, 1, crew);
        List<String> out = pr.advance(6, 0, store, crew, 1, 0);
        assertTrue(out.contains("Lab: Experiment: Reflux still done (1 of 2) +15 % → 15 %"), out.toString());
        assertEquals(0, store.count("Electronics", 2, 0));
        // Tinkerer (round 2): a third experiment, the prototype −25 % time
        Production.Worker tink = ProductionTest.worker("Tink", "Chemistry", 60, "Tinkerer");
        assertEquals(3, Research.maxExperiments(null, new Crew(), List.of(tink)));
        assertEquals(6, Research.need(Research.jobFor(c, "prototype"), List.of(tink)), 1e-9);
        // Absent-Minded Genius (round 2): an inspiration 20 %; Eureka (round 3): once, at will, worth 10 % more
        Production pr2 = new Production();
        Production.Worker genius = ProductionTest.worker("Gen", "Chemistry", 60, "Absent-Minded Genius", "Eureka");
        Crew g = new Crew(genius);
        Research.project(pr2, c).researchers.add(genius.id());
        assertEquals("Reflux still: Eureka! the inspiration +30 % → 30 %", Research.eureka(pr2, c, g));
        assertTrue(Research.eureka(pr2, c, g).startsWith("!"));
        assertTrue(Research.eureka(new Production(), c, g).startsWith("! nobody"));
        // Reverse Engineer (round 1): the working item taken apart = the find 50 % and a free inspiration
        Production pr3 = new Production();
        assertEquals("Reflux still: a find +50 %, and the inspiration +10 % (Reverse Engineer) → 60 %", Research.find(pr3, c, 0, true, new Crew()));
        // Prototype (round 3): at 90 %
        Production pr4 = new Production();
        Research.Project p = Research.project(pr4, c);
        p.find = 50; p.achievement = 20; p.experiments = 20;
        assertFalse(Research.ready(pr4, c, new Crew()));
        Production.Worker proto = ProductionTest.worker("Pro", "Chemistry", 60, "Prototype");
        p.researchers.add(proto.id());
        assertTrue(Research.ready(pr4, c, new Crew(proto)));
    }

    @Test
    void theAchievementCountsWhatTheBaseMakesAndASharedLineGetsHalf() {
        Production pr = new Production();
        Research.Card still = card("reflux-still"), solar = card("solar-still");
        Research.project(pr, solar).status = "theory";                                     // running, of the distilling line
        List<String> out = new ArrayList<>();
        Research.made(pr, "Chemicals", 2, 12, out);
        assertTrue(out.isEmpty());
        assertEquals(12, pr.research.get(still.id).achieved);
        Research.made(pr, "Chemicals", 2, 8, out);                                         // 20 Chemicals II made: met
        assertEquals(List.of("Research: Reflux still: its achievement met (20 Chemicals II made in the base (the old way teaches)) +20 %"), out);
        assertEquals(20, Research.progress(pr, still), 1e-9);
        assertEquals(10, Research.progress(pr, solar), 1e-9);                              // half of it to the running related project
        // a run of the old way counts too
        Production p2 = new Production();
        p2.build("Reactor", 4, "Reactor");
        Production.Building w = p2.build("Workshop", 2, "W");
        Base b = new Base(null);
        b.addUnits("Metal", 1, 1, 3);
        b.addUnits("Fuel", 2, 1, 2);
        b.addUnits("Water", 1, 1, 50);
        Production.Worker smith = ProductionTest.worker("Smith", "Crafting", 60);
        ProductionTest.Crew cw = new ProductionTest.Crew(smith);
        p2.start(w, ProductionTest.recipe("recast-scrap"), 1, 1, cw);
        p2.advance(8, 0, b, cw, 1, 0);
        assertEquals(1, p2.research.get("bellows-furnace").achieved);                      // 1 Metal II of the 20
    }
}
