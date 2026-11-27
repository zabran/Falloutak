package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Part 8, research (`08-RESEARCH.md` §3, RULED rounds 1-4): the cards, the shares, theory, experiments, the prototype (built by the way's
 * own crew led by a researcher, round 4) and its test, the eurekas pre-prepared in Civ6's way (round 4, named so in round 6), the tree (round 4).
 */
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

    /** A worker with two skills set exactly. */
    static Production.Worker two(String name, String s1, double v1, String s2, double v2) {
        Production.Worker w = ProductionTest.worker(name, s1, v1);
        Character c = w.sheet();
        int j = Rules.skillIndex(s2);
        c.points[j] = 0;
        c.points[j] = v2 - c.skill(s2);
        assertEquals(v2, c.skill(s2), 1e-9);
        assertEquals(v1, c.skill(s1), 1e-9);
        return w;
    }

    /** A seed whose first d100 is above (or at most) this. */
    static long seedFor(int atLeast, int atMost) {
        for (long s = 1; ; s++) { int r = 1 + new Random(s).nextInt(100); if (r >= atLeast && r <= atMost) return s; }
    }

    @Test
    void theCardsAreTheCataloguesResearchedWays() {
        assertEquals(48, Research.CARDS.size());                                            // 0.107.0 + Pervitin kitchen, Ephedra (04j §7e); 0.106.0 + the 7 of the medicines ladder (04i); round 8: the grow room built from the start; 0.61.0 + Chlor-alkali; 0.81.0 + Prussian blue; 0.84.0 + Immunosuppressants
        assertEquals(2, Research.CARDS.values().stream().filter(c -> c.parked).count());     // gene research PARKED (Marek 11-13)
        int[] gate = {0, 25, 50, 100, 150};
        for (Research.Card c : Research.CARDS.values()) {
            assertEquals(100 * c.tier * c.tier * c.rarity, c.points, c.name);              // RULED round 1: P = 100 x T^2 x R
            assertTrue(c.gate >= gate[c.tier], c.name);
            for (String r : c.recipes) assertEquals(c.name, Production.RECIPES.get(r).research, c.name);
            assertNotNull(Research.jobFor(c, "theory"), c.name);
            assertEquals(c.tier, Research.jobFor(c, "experiment").inputs.get(0).tier, c.name);   // round 2: the apparatus of the tier
            Production.Recipe t = c.target(), proto = Research.jobFor(c, "prototype");
            assertEquals(t.skill, proto.skill, c.name);                                      // round 4: the way's own crew builds it
            assertEquals(t.min, proto.min, 1e-9, c.name);
        }
        Research.Card still = card("reflux-still");
        assertEquals(400, still.points);
        assertEquals(50, still.gate);
        assertEquals("Chemistry", still.science);
        Production.Recipe proto = Research.jobFor(still, "prototype");
        assertEquals("Still", proto.building);
        assertEquals(8, proto.hours, 1e-9);                                                // twice the way's 4 hours
        assertEquals(2, card("laboratory-iii").lab);                                       // a laboratory's next tier runs in the one below
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
        // the GM's find and eureka (RULED round 1: a find up to 50 %, the eureka 20 %)
        assertTrue(Research.find(pr, c, 30, false, crew).endsWith("→ 30 %"));
        assertTrue(Research.eureka(pr, c).endsWith("→ 50 %"));
        assertTrue(Research.eureka(pr, c).startsWith("!"));                           // once
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
        // the prototype in a still, twice the hours, then the test: Prototype's first test passes; Chem leads it and works it (Chemistry 30)
        Production.Building still = pr.build("Still", 2, "Still");
        store.addUnits("Food", 1, 1, 3);
        store.addUnits("Fuel", 1, 1, 1);
        String m = pr.start(still, Research.jobFor(c, "prototype"), 1, 1, crew);
        assertTrue(m.contains("led by Chem"), m);
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
    void thePrototypeIsBuiltByTheWaysOwnCrewLedByAResearcher() {
        // round 4 (Marek 11-21: "it should be build together with the crew. That makes the most sense.")
        Research.Card c = card("bellows-furnace");                                         // Engineering 50; the way: a Kiln II, Crafting 30, 3 h
        Production.Worker sci = two("Sci", "Engineering", 60, "Crafting", 10);              // can lead, cannot build it
        Production.Worker smith = two("Smith", "Crafting", 60, "Engineering", 10);          // can build it, cannot lead
        Base store = ProductionTest.cooled();
        store.addUnits("Metal", 1, 1, 2);
        store.addUnits("Fuel", 2, 1, 1);
        Production pr = new Production();
        pr.build("Reactor", 4, "Reactor");
        Production.Building kiln = pr.build("Kiln", 2, "Kiln");
        Research.Project p = Research.project(pr, c);
        p.find = 50; p.eureka = 20; p.experiments = 20; p.inspiration = 10;
        p.researchers.add(sci.id());
        Production.Recipe proto = Research.jobFor(c, "prototype");
        assertEquals("Crafting", proto.skill);
        // nobody to lead it: it does not start
        assertEquals("! nobody free has Engineering 50 to lead the prototype (a researcher)", pr.start(kiln, proto, 1, 1, new Crew(smith)));
        assertNull(kiln.job);
        // the researcher alone leads it, but nobody can build it: it waits for a crew
        Production pr2 = new Production();
        pr2.build("Reactor", 4, "Reactor");
        Production.Building k2 = pr2.build("Kiln", 2, "Kiln");
        Research.project(pr2, c).find = 100;
        String m2 = pr2.start(k2, proto, 1, 1, new Crew(sci));
        assertTrue(m2.contains("led by Sci") && m2.contains("! nobody free has Crafting 30"), m2);
        Base s2 = ProductionTest.cooled();
        s2.addUnits("Metal", 1, 1, 2);
        s2.addUnits("Fuel", 2, 1, 1);
        pr2.advance(2, 0, s2, new Crew(sci), 1, 0);
        assertEquals("no crew at Crafting 30", k2.job.waiting);
        // both: the researcher leads (no slot), the smith builds; the test is the lead's: 50 + (60 − 50) = 60 %
        Crew both = new Crew(sci, smith);
        String m = pr.start(kiln, proto, 1, 1, both);
        assertEquals("Kiln: Prototype: Bellows furnace × 1; led by Sci; the pick: Smith", m);
        assertEquals(List.of(smith.id()), kiln.crew);
        assertEquals(sci.id(), kiln.job.lead);
        assertEquals(kiln.id, pr.busyAt(sci.id()));
        pr.seed(seedFor(1, 60));
        List<String> out = pr.advance(4, 0, store, both, 1, 0);                             // 6 h of work at 2 an hour: 3 h
        assertTrue(out.stream().anyMatch(s -> s.contains("of 60 %: PASSED. The base knows Bellows furnace now")), out.toString());
        assertEquals(200L, both.xp.get(sci.id()));                                          // the researcher's 100 x T x R
        assertNull(both.xp.get(smith.id()));                                                // the crew: its work's XP, not research's
        assertNull(pr.busyAt(sci.id()));
        assertNull(kiln.job);
    }

    @Test
    void aFailedTestAtRarityOneGoesToTheGmAndEachFailureHelpsTheNext() {
        Base store = new Base(null);
        Production pr = base(store);
        Production.Worker chem = ProductionTest.worker("Chem", "Chemistry", 50);            // at the gate: the test 50 %
        Crew crew = new Crew(chem);
        Research.Card c = card("reflux-still");
        Research.Project p = Research.project(pr, c);
        p.find = 50; p.eureka = 20; p.experiments = 20; p.inspiration = 10;           // 100 % without theory (no floor, round 2)
        assertEquals(0, Research.theoryLeft(pr, c), 1e-9);
        Production.Building still = pr.build("Still", 2, "Still");
        store.addUnits("Food", 1, 1, 6);
        store.addUnits("Food", 3, 1, 6);                                                  // the person's meals (Marek 11-24), the best first
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
        // Absent-Minded Genius (round 2): an inspiration 20 %; Flash of Insight (round 3, was Eureka until round 7): once, at will, 10 % more
        Production pr2 = new Production();
        Production.Worker genius = ProductionTest.worker("Gen", "Chemistry", 60, "Absent-Minded Genius", "Flash of Insight");
        Crew g = new Crew(genius);
        Research.project(pr2, c).researchers.add(genius.id());
        assertEquals("Reflux still: Flash of Insight, the inspiration +30 % → 30 %", Research.atWill(pr2, c, g));
        assertTrue(Research.atWill(pr2, c, g).startsWith("!"));
        assertTrue(Research.atWill(new Production(), c, g).startsWith("! nobody"));
        // Reverse Engineer (round 1): the working item taken apart = the find 50 % and a free inspiration
        Production pr3 = new Production();
        assertEquals("Reflux still: a find +50 %, and the inspiration +10 % (Reverse Engineer) → 60 %", Research.find(pr3, c, 0, true, new Crew()));
        // Prototype (round 3): at 90 %
        Production pr4 = new Production();
        Research.Project p = Research.project(pr4, c);
        p.find = 50; p.eureka = 20; p.experiments = 20;
        assertFalse(Research.ready(pr4, c, new Crew()));
        Production.Worker proto = ProductionTest.worker("Pro", "Chemistry", 60, "Prototype");
        p.researchers.add(proto.id());
        assertTrue(Research.ready(pr4, c, new Crew(proto)));
    }

    @Test
    void theEurekasArePrePreparedLikeCiv6s() {
        // round 4 (Marek 11-21: "I think these could pre-prepared. Like in Civ6." · "The "use recipe XX times" kind should be rarer")
        int gm = 0, sameWay = 0;
        for (Research.Card c : Research.CARDS.values()) {
            if (c.parked) continue;
            assertNotNull(c.eureka, c.name);
            assertFalse(c.eureka.text.isEmpty(), c.name);
            if (!c.eureka.counted()) gm++;
            if (c.eureka.model.contains("a way used N times")) sameWay++;
        }
        assertEquals(13, gm);                                                                  // 0.107.0: + Pervitin kitchen, Ephedra; 0.106.0: + mould broth, phages (04i); 0.84.0: + Immunosuppressants (a graft kept a month)
        assertEquals(4, sameWay);                                                           // the rare kind (0.81.0: Prussian blue by boiling potash)
        Production pr = new Production();
        pr.shelter();
        Base store = new Base(null);
        List<String> out = new ArrayList<>();
        Research.check(pr, store, out);
        assertEquals(List.of(), out);                                                       // the shelter's rooms count for nothing
        // building: a kiln, pens, a field; "*" three buildings beyond the shelter's rooms; two workshops
        pr.build("Kiln", 2, "Kiln");
        Research.check(pr, store, out);
        assertEquals(List.of("Research: Bellows furnace: its eureka met (Build a kiln) +20 %"), out);
        assertEquals(20, Research.progress(pr, card("bellows-furnace")), 1e-9);
        assertEquals("The eureka (counted): Three buildings built beyond the shelter's rooms, 1 of 3",
                Research.eurekaLine(pr, card("down-draught-kiln"), store));
        pr.build("Pens", 2, "Pens");
        pr.build("Field", 2, "Field");
        pr.build("Workshop", 2, "Second workshop");
        out.clear();
        Research.check(pr, store, out);
        assertTrue(out.contains("Research: Lime tanning: its eureka met (Build pens (the animals' hides)) +20 %"), out.toString());
        assertTrue(out.contains("Research: Saw pit: its eureka met (Prepare a field (its clearing gives the logs)) +20 %"), out.toString());
        assertTrue(out.contains("Research: Down-draught kiln: its eureka met (Three buildings built beyond the shelter's rooms) +20 %"), out.toString());
        assertTrue(out.contains("Research: Lathe jigs: its eureka met (Have two workshops) +20 %"), out.toString());
        // stored: 6 Power II in the store at once (Tier III counts too)
        store.addUnits("Power", 2, 1, 5);
        out.clear();
        Research.check(pr, store, out);
        assertTrue(out.isEmpty(), out.toString());
        store.addUnits("Power", 3, 1, 1);
        Research.check(pr, store, out);
        assertEquals(List.of("Research: Edison cell: its eureka met (6 rebuilt lead batteries (Power II) in the store at once) +20 %"), out);
        // condition: the water filtration below 75 %
        out.clear();
        for (Production.Building b : pr.buildings) if ("Water filtration".equals(b.kind)) b.condition = 74;
        Research.check(pr, store, out);
        assertEquals(List.of("Research: Solar still: its eureka met (The water filtration falls below 75 % (it gives less water)) +20 %"), out);
        // worn: three tools worn out in the base's work
        out.clear();
        store.addUnits("Gear and tools", 1, 1, 3);
        for (int i = 0; i < 3; i++) store.wear("Gear and tools", 1, 100);
        assertEquals(3, store.wornOut("Gear and tools"));
        Research.check(pr, store, out);
        assertEquals(List.of("Research: Hardening and tempering: its eureka met (Three tools worn out in the base's work) +20 %"), out);
        // cards: five research cards done
        out.clear();
        for (String id : List.of("saw-pit", "lime-tanning", "retort-kiln", "bullet-moulds", "stock-removal")) Research.project(pr, card(id)).status = "done";
        Research.check(pr, store, out);
        assertEquals(List.of("Research: Laboratory III: its eureka met (Five research cards done) +20 %"), out);
        // assembled: five firearms for going out; soft armour counts its own
        out.clear();
        Research.assembled(pr, Production.design("Pistols", "Pipe pistol"), 4, out);
        Research.assembled(pr, Production.design("Body armour", "Leather Jacket"), 2, out);
        assertTrue(out.isEmpty(), out.toString());
        Research.assembled(pr, Production.design("Pistols", "Pipe pistol"), 1, out);
        assertEquals(List.of("Research: Gun jigs: its eureka met (Five firearms assembled for going out) +20 %"), out);
        assertEquals(2, pr.research.get("armour-patterns").eurekaCount);
        // made, of ANOTHER type ("most should work with different type"): 5 Seeds II saved; half to a running project of a line
        Research.project(pr, card("rad-scrubbing")).status = "theory";                       // food
        out.clear();
        Research.made(pr, "Seeds", 2, 5, out);
        assertEquals(List.of("Research: Chicory: its eureka met (5 Seeds II saved from the base's crops) +20 %"), out);
        assertEquals(10, Research.progress(pr, card("rad-scrubbing")), 1e-9);
        // recipe: a way used N times (the rare kind): 10 appliances stripped
        out.clear();
        for (int i = 0; i < 9; i++) Research.ran(pr, "strip-appliances", out);
        assertTrue(out.isEmpty());
        Research.ran(pr, "strip-appliances", out);
        assertEquals(List.of("Research: Coil winder: its eureka met (Strip 10 appliances of their wiring and coils (the old way)) +20 %"), out);
        // gm: a deed in the world, never counted
        assertEquals("The eureka (the GM credits it): Find a bed of bog iron ore or a rusty spring (the local map)",
                Research.eurekaLine(pr, card("iron-from-ore"), store));
    }

    @Test
    void aWayRunInTheBaseCountsForTheRecipeKind() {
        Production pr = new Production();
        pr.build("Reactor", 4, "Reactor");
        Production.Building k = pr.build("Kitchen", 1, "Kitchen");
        Base b = new Base(null);
        b.addUnits("Livestock", 1, 1, 2);
        b.addUnits("Water", 3, 1, 50);
        Production.Worker cook = ProductionTest.worker("Cook", "Survival", 40);
        ProductionTest.Crew cw = new ProductionTest.Crew(cook);
        pr.start(k, ProductionTest.recipe("butcher"), 2, 1, cw);
        pr.advance(4, 0, b, cw, 1, 0);
        assertEquals(2, pr.research.get("hide-glue").eurekaCount);                            // 2 of the 5 animals butchered or skinned
        assertEquals("The eureka (counted): Five animals butchered or skinned (the hides, hooves and bones), 2 of 5",
                Research.eurekaLine(pr, card("hide-glue"), b));
    }

    @Test
    void theTreeOpensTheTierThreeCardsAfterTheLaboratory() {
        // round 4 (Marek 11-21: "yes, it should have a bit tree like structure due to that.")
        Research.Card iron = card("iron-from-ore"), lab3 = card("laboratory-iii"), lab4 = card("laboratory-iv"), edison = card("edison-cell");
        Research.Card kiln3 = card("kiln-iii"), ws3 = card("workshop-iii"), ws4 = card("workshop-iv");
        // round 5 ("Labs can research basic (just the basic) researches of 2 tier above"): the first way to a TTR or a building's tier
        // round 6 ("up to 1 tier bellow. So that you cannot rush it without building T3 lab."): ONE tier below
        for (Research.Card c : List.of(iron, lab3, edison, kiln3, ws3, card("circuit-boards"), card("precision-parts"))) {
            assertTrue(c.basic, c.name);
            assertEquals(3, c.tier, c.name);
            assertEquals(2, c.lab, c.name);                                                  // a Tier III basic card in a Laboratory II
        }
        for (Research.Card c : List.of(lab4, ws4, card("microelectronics"), card("servos-and-turbines"))) {
            assertTrue(c.basic, c.name);
            assertEquals(4, c.tier, c.name);
            assertEquals(3, c.lab, c.name);                                                  // a Tier IV one in a Laboratory III
            assertEquals("laboratory-iii", c.needs.get(0), c.name);
        }
        Production.Building lab2 = new Production().build("Laboratory", 2, "Lab");
        assertTrue(Production.fits(lab2, Research.jobFor(iron, "theory")));
        assertFalse(Production.fits(lab2, Research.jobFor(card("microelectronics"), "experiment")));
        assertTrue(Production.fits(new Production().build("Laboratory", 3, "Lab"), Research.jobFor(card("microelectronics"), "experiment")));
        assertFalse(Production.fits(lab2, Research.jobFor(card("rad-scrubbing"), "theory")));
        for (String id : List.of("rad-scrubbing", "mushrooms-on-straw", "solar-still")) {   // better ways to what the start makes
            assertFalse(card(id).basic, id);
            assertEquals(3, card(id).lab, id);
            assertEquals("laboratory-iii", card(id).needs.get(0), id);
        }
        // round 8 ("Grow room III can be build from the start, if players bring seeds and soil"): no grow room card any more
        assertNull(Research.CARDS.get("grow-room"));
        assertNull(Research.CARDS.get("grow-room-iii"));
        assertTrue(card("chicory").needs.isEmpty());
        assertEquals(List.of("laboratory-iii"), card("mushrooms-on-straw").needs);
        // round 5 ("buildings should be researched as well"): the way's building raised by research comes first
        assertEquals(List.of("bellows-furnace", "kiln-iii"), iron.needs);
        assertEquals(List.of("iron-from-ore", "workshop-iii"), edison.needs);
        assertEquals(List.of("iron-from-ore"), lab3.needs);                                  // no steel to find any more
        assertEquals(List.of("laboratory-iii", "iron-from-ore", "edison-cell"), lab4.needs);
        assertEquals(List.of("laboratory-iii", "workshop-iii", "iron-from-ore", "precision-parts", "circuit-boards", "edison-cell"), ws4.needs);
        assertTrue(kiln3.needs.isEmpty());
        assertTrue(iron.notes.stream().noneMatch(n -> n.contains("no way builds")), iron.notes.toString());
        // round 5 ("there should be research for that"): Electronics and Machinery III-IV made by research
        assertEquals("Electronics", card("circuit-boards").target().makes.get(0).type);
        assertEquals(3, card("circuit-boards").target().makes.get(0).tier);
        assertEquals("Machinery", card("servos-and-turbines").target().makes.get(0).type);
        assertEquals(4, card("servos-and-turbines").target().makes.get(0).tier);
        assertTrue(iron.notes.contains("the experiments' Electronics III: from Circuit boards (or found)"), iron.notes.toString());
        Production pr = new Production();
        for (Research.Card c : Research.CARDS.values())
            if (!c.parked) assertEquals(c.needs.isEmpty(), Research.open(pr, c), c.name);   // what needs nothing is open from the start
        assertTrue(Research.open(pr, kiln3));
        assertEquals("needs Bellows furnace, Kiln III", Research.state(pr, iron, new Crew()));
        Research.project(pr, kiln3).status = "done";
        assertFalse(Research.open(pr, iron));
        Research.project(pr, card("bellows-furnace")).status = "done";
        assertTrue(Research.open(pr, iron));
        assertTrue(Research.opens(lab3).containsAll(List.of(card("rad-scrubbing"), card("mushrooms-on-straw"), card("solar-still"), lab4)));
        assertEquals(List.of(edison, card("precision-parts"), lab3, lab4, ws3, ws4), Research.opens(iron));
        // a Laboratory III the GM placed stands: the cards that need Laboratory III open without it [mine, round 6]
        Production pkg = new Production();
        assertFalse(Research.open(pkg, card("mushrooms-on-straw")));
        pkg.build("Laboratory", 2, "Lab");
        assertFalse(Research.open(pkg, card("mushrooms-on-straw")));                        // a Tier II one is not enough
        pkg.build("Laboratory", 3, "Lab III");
        assertTrue(Research.open(pkg, card("mushrooms-on-straw")));
        // round 7 ("it should not give you the research to make it"): the card stays undone, its way unknown
        assertFalse(Research.done(pkg, lab3));
        assertFalse(pkg.known(Production.RECIPES.get("raise-a-laboratory-to-iii")));
        assertEquals("Laboratory III (its building stands: met, the research itself not done)", Research.needsLine(pkg, card("solar-still")));
        Research.project(pkg, lab3).status = "done";
        assertEquals("Laboratory III (done)", Research.needsLine(pkg, card("solar-still")));
    }

    @Test
    void oldSavesKeepTheirAchievementsAsEurekas() {
        // round 6 ("rename them into eurakas, so that they do not clash with normal achievements"): a 0.53.0 save still loads
        Production pr = Store.GSON.fromJson("{\"research\":{\"reflux-still\":{\"status\":\"theory\",\"achievement\":20.0,\"achieved\":2,"
                + "\"eureka\":true}}}", Production.class);
        Research.Project p = pr.research.get("reflux-still");
        assertEquals(20, p.eureka, 1e-9);
        assertEquals(2, p.eurekaCount);
        assertTrue(p.atWill);                                                               // the perk Eureka's inspiration at will
        String json = Store.GSON.toJson(p).replace(" ", "");
        assertTrue(json.contains("\"eurekaShare\":20.0") && json.contains("\"eurekaCount\":2") && json.contains("\"atWill\":true"), json);
        assertEquals(20, Research.RULES.eureka, 1e-9);
        assertEquals(20, Research.progress(pr, card("reflux-still")), 1e-9);
        // round 7 ("Shall I rename it, for example to Flash of Insight? - yes."): a sheet with the perk Eureka gets its new name
        Character ch = AirTest.human();
        ch.perks.add(new Character.Feat("Eureka", "any science 150", ""));
        ch.migrate();
        assertTrue(ch.hasPerk("Flash of Insight"));
        assertFalse(ch.hasPerk("Eureka"));
    }
}
