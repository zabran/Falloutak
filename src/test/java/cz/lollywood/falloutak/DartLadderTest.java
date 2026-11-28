package cz.lollywood.falloutak;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.111.0, the sleep darts' ladder (`04j` §7h; Marek 11-27 night, verbatim: *"just use that drug level as base with some other generic
 * chemical to make generic T3 tranquilizer ... With effect of sleep in 3 rounds or instant with double dose (but chance to make target
 * dying if not also add Naloxone). T4 version would make safe sleep in the instant and work on even large/highly resistant
 * creatures."*). Game numbers only.
 */
class DartLadderTest extends MockGame {

    @AfterEach
    void noField() { Loot.clear(); }

    private static Random always(double u) { return new Random() { @Override public double nextDouble() { return u; } }; }

    private static Battle.Target body(String name, int hp) {
        Battle.Target t = new Battle.Target(name, hp, 5, 2, 0.2, new int[]{5, 6, 3, 5, 5, 4, 5}, 50);
        t.foe = "raider";
        return t;
    }

    @Test
    void theLoadNamesItsDoseAndTheOldSedativeDartIsTheKetamine() {
        assertNull(Battle.dose("Darts|Plain darts"), "a plain dart carries nothing");
        assertNull(Battle.dose(null));
        assertEquals("the opium", Battle.dose("Darts|Opium darts").name());
        assertEquals("the ketamine", Battle.dose("Darts|Ketamine darts").name());
        assertEquals("the ketamine", Battle.dose("Darts|Sedative darts").name(), "a load kept from before 0.111.0");
        assertEquals("the tranquilliser", Battle.dose("Darts|Tranquilliser darts").name());
        assertEquals("the double dose", Battle.dose("Darts|Tranquilliser darts, double dose").name());
        assertTrue(Battle.dose("Darts|Tranquilliser darts, double dose").risky());
        assertFalse(Battle.dose("Darts|Tranquilliser darts").risky());
        assertEquals("the knockout", Battle.dose("Darts|Knockout darts").name());
        assertFalse(Battle.dose("Darts|Knockout darts").risky(), "the knockout is safe");
        // the old name still finds its design
        assertEquals("Ketamine darts", Production.design("Darts", "Sedative darts").name);
    }

    @Test
    void theRoundsGrowWithTheBodyAndOnlyTheKnockoutTakesTheLargeOnes() {
        Battle.Dose tranq = Battle.dose("Darts|Tranquilliser darts"), dbl = Battle.dose("Darts|Tranquilliser darts, double dose"),
                ket = Battle.dose("Darts|Ketamine darts"), opium = Battle.dose("Darts|Opium darts"), ko = Battle.dose("Darts|Knockout darts");
        assertEquals(3, Battle.sleepRounds(tranq, 30), "Marek: sleep in 3 rounds (a person)");
        assertEquals(3, Battle.sleepRounds(tranq, 12), "a smaller body no quicker");
        assertEquals(4, Battle.sleepRounds(tranq, 40), "the boar: 3 x 40 / 30, up");
        assertEquals(6, Battle.sleepRounds(tranq, 60));
        assertEquals(-1, Battle.sleepRounds(tranq, 61), "LARGE: only drowsy");
        assertEquals(-1, Battle.sleepRounds(tranq, 120), "the Radmie");
        assertEquals(0, Battle.sleepRounds(dbl, 30), "Marek: instant with double dose");
        assertEquals(0, Battle.sleepRounds(dbl, 60));
        assertEquals(-1, Battle.sleepRounds(dbl, 90), "the tiger: the double dose too is too little");
        assertEquals(30, Battle.sleepRounds(ket, 30));
        assertEquals(45, Battle.sleepRounds(ket, 45));
        assertEquals(20, Battle.sleepRounds(opium, 12), "the dog asleep");
        assertEquals(20, Battle.sleepRounds(opium, 15));
        assertEquals(-1, Battle.sleepRounds(opium, 30), "a person only drowsy (Marek: a small effect on people)");
        for (int hp : new int[]{12, 30, 60, 120, 150, 200})
            assertEquals(0, Battle.sleepRounds(ko, hp), "Marek: the T4 in the instant, on even the large (" + hp + " HP)");
    }

    @Test
    void theDoubleDosesRiskFallsWithTheBody() {
        assertEquals(0.25, Battle.breathRisk(30), 1e-9);
        assertEquals(0.625, Battle.breathRisk(12), 1e-9);
        assertEquals(0.1875, Battle.breathRisk(40), 1e-9);
        assertEquals(0.05, Battle.breathRisk(200), 1e-9, "at least 5 %");
        assertEquals(0.75, Battle.breathRisk(1), 1e-9, "at most 75 %");
    }

    @Test
    void aDartsDoseInTheFight() {
        StringBuilder log = new StringBuilder();
        // the tranquilliser: asleep in 3 rounds; a slower dose later does not push it back, the knockout makes it now
        Battle.Target a = body("Raider", 30);
        Battle.dart(a, Battle.dose("Darts|Tranquilliser darts"), 5, always(0.0), log);
        assertEquals(8, a.sedatedAt);
        assertFalse(a.asleep);
        Battle.dart(a, Battle.dose("Darts|Ketamine darts"), 6, always(0.0), log);
        assertEquals(8, a.sedatedAt, "the quicker dose stands");
        Battle.dart(a, Battle.dose("Darts|Knockout darts"), 6, always(0.0), log);
        assertTrue(a.asleep && a.out && a.outRounds > 100);
        assertEquals(-1, a.breathStopAt, "the knockout is safe");
        assertTrue(a.outOfIt(), "asleep: out of it for the fight's end");
        // the double dose: at once, and the roll under the risk stops his breathing
        Battle.Target b = body("Raider 2", 30);
        Battle.dart(b, Battle.dose("Darts|Tranquilliser darts, double dose"), 5, always(0.2), log);
        assertTrue(b.asleep);
        assertEquals(5 + Battle.BREATH_ROUNDS, b.breathStopAt, "0.2 < 25 %: not breathing");
        assertTrue(b.label().contains("NOT BREATHING"), b.label());
        Battle.Target c = body("Raider 3", 30);
        Battle.dart(c, Battle.dose("Darts|Tranquilliser darts, double dose"), 5, always(0.3), log);
        assertTrue(c.asleep);
        assertEquals(-1, c.breathStopAt, "0.3 over 25 %: he breathes");
        // too big a body: drowsy, never asleep
        Battle.Target radmie = body("Radmie", 120);
        radmie.melee = true;
        Battle.dart(radmie, Battle.dose("Darts|Tranquilliser darts, double dose"), 5, always(0.0), log);
        assertFalse(radmie.asleep);
        assertTrue(radmie.drowsy && radmie.sedatedAt < 0);
        assertTrue(radmie.label().contains("drowsy"), radmie.label());
        assertTrue(log.toString().contains("too little for it"), log.toString());
        // a plain dart carries nothing
        Battle.Target d = body("Raider 4", 30);
        Battle.dart(d, Battle.dose("Darts|Plain darts"), 5, always(0.0), log);
        assertFalse(d.asleep || d.drowsy || d.sedatedAt >= 0);
    }

    @Test
    void theBreathingStageEndsHimWhenItsRoundsRunOut() {
        Battle.Target b = body("Raider", 30);
        Battle.dart(b, Battle.dose("Darts|Tranquilliser darts, double dose"), 5, always(0.0), new StringBuilder());
        assertFalse(Raiders.breathEnds(b, 5 + Battle.BREATH_ROUNDS - 1));
        assertFalse(b.dead);
        assertTrue(Raiders.breathEnds(b, 5 + Battle.BREATH_ROUNDS));
        assertTrue(b.dead && b.byParty, "the party's kill");
        assertFalse(Raiders.breathEnds(b, 99), "once");
        assertFalse(Raiders.breathEnds(body("Raider 2", 30), 99), "nobody else");
    }

    private Loot.Body breathlessOnTheField(int round) {
        Battle.Target b = body("Raider", 30);
        Battle.dart(b, Battle.dose("Darts|Tranquilliser darts, double dose"), round, always(0.0), new StringBuilder());
        Battle.Fight f = new Battle.Fight("the enemies");
        f.round = round + 4;
        f.targets.put(UUID.randomUUID(), b);
        assertEquals(1, Loot.collect(f, new Random(1)));
        return Loot.bodies.get(0);
    }

    @Test
    void naloxoneOnTheFieldSavesHimAndThenHeIsTakenAlive() {
        PlayerMock a = player();
        Loot.Body x = breathlessOnTheField(5);
        assertTrue(x.breathless && x.asleep && !x.dead);
        assertEquals(Battle.BREATH_ROUNDS - 4, x.breathLeft, "the rounds left at the fight's end");
        assertTrue(x.state().contains("NOT BREATHING"), x.state());
        assertTrue(Loot.takeAlive(plugin, a, x).contains("naloxone first"));
        assertTrue(Loot.naloxone(plugin, a, x).startsWith("! no naloxone"), "none in the pack");
        assertTrue(x.breathless);
        a.getInventory().addItem(Items.make("chem:Naloxone", 1));
        String r = Loot.naloxone(plugin, a, x);
        assertTrue(r.contains("breathes again"), r);
        assertFalse(x.breathless);
        assertFalse(a.getInventory().containsAtLeast(Items.make("chem:Naloxone", 1), 1), "the dose used");
        assertEquals("asleep", x.state());
        assertFalse(Loot.takeAlive(plugin, a, x).startsWith("!"), "now taken alive");
        assertTrue(Loot.naloxone(plugin, a, x).startsWith("!"), "no second dose needed");
    }

    @Test
    void anythingElseFirstAndHeDiesOnTheFieldsClock() {
        PlayerMock a = player();
        Character c = plugin.character(a);
        Loot.Body x = breathlessOnTheField(5);
        assertFalse(Loot.settleBreath(c, x), "the first look starts the clock");
        assertFalse(Double.isNaN(x.breathUntil));
        assertFalse(Loot.settleBreath(c, x), "still within his seconds");
        Hud.pass(c, 10);   // ten minutes on another body
        assertTrue(Loot.settleBreath(c, x));
        assertTrue(x.dead && !x.breathless && !x.asleep);
        assertEquals("dead", x.state());
        a.getInventory().addItem(Items.make("chem:Naloxone", 1));
        assertTrue(Loot.naloxone(plugin, a, x).startsWith("!"), "too late");
    }

    @Test
    void theTranquilliserDartsAreAnAssemblyOfTheDrugLevelAndAGenericChemical() {
        // THE TTR (Marek 11-28): the darts are his condition 2, an assembly of TTRs; no Tranquilliser chem shares a row with Prussian
        // blue (whose cheap recipe would have made it in the base), no recipe, no research card
        assertNull(Production.RECIPES.get("make-tranquilliser"));
        assertFalse(Research.CARDS.containsKey("tranquilliser"));
        for (Production.Named n : Production.TYPES.get("Chems").items)
            assertFalse(n.name.equals("Tranquilliser") || n.name.equals("Knockout"), n.name);
        Production.Design ket = Production.design("Darts", "Ketamine darts"), dt = Production.design("Darts", "Tranquilliser darts"),
                dd = Production.design("Darts", "Tranquilliser darts, double dose"), ko = Production.design("Darts", "Knockout darts");
        assertEquals(3, dt.tier);
        assertEquals(1, dt.rarity, "a design's parts are at its rarity: Chems III R1, the drug level");
        assertEquals(3, parts(dt, "Chems", 3), "the drug level as base");
        assertEquals(1, parts(dt, "Chemicals", 3), "some other generic chemical");
        assertEquals(0, parts(ket, "Chemicals", 3), "the ketamine dart has no chemical: the extra part is what the tranquilliser is worth more by");
        assertEquals(2 * parts(dt, "Chems", 3), parts(dd, "Chems", 3), "the double dose takes twice the doses");
        assertEquals(2 * parts(dt, "Chemicals", 3), parts(dd, "Chemicals", 3));
        assertEquals(4, ko.tier, "the knockout: Tier IV, the 2040s");
        assertEquals(3, parts(ko, "Chems", 4));
    }

    private static int parts(Production.Design d, String type, int tier) {
        int n = 0;
        for (Production.Part p : d.parts) if (p.type.equals(type) && p.tier == tier) n += p.count;
        return n;
    }

    @Test
    void theOpiumDartTakesOpiumsRowFromTheStore() {
        // the TTR fix moved opium and pervitin to Chems II R2 (ersatz coffee's cheap recipe made them in R1); the Opium darts, Tier II
        // R1, take their Chems at R2 (a part's own rarity)
        Production.Design op = Production.design("Darts", "Opium darts");
        assertEquals(2, op.tier);
        assertEquals(1, op.rarity);
        Production.Part chem = op.parts.stream().filter(x -> x.type.equals("Chems")).findFirst().orElseThrow();
        assertEquals(2, chem.rarity(op), "opium's row");
        assertEquals(1, op.parts.stream().filter(x -> x.type.equals("Light weapon parts")).findFirst().orElseThrow().rarity(op), "the rest at the design's");
        Base b = plugin.base;
        b.addUnits("Light weapon parts", 2, 1, 1);
        b.addUnits("Chems", 2, 1, 3);
        assertEquals(0, b.canAssemble(op), "caffeine and willow bark do not make an opium dart");
        assertTrue(b.missing(op).contains("Chems II R2"), b.missing(op));
        b.addUnits("Chems", 2, 2, 3);
        assertEquals(1, b.canAssemble(op));
        assertTrue(b.assemble(op, 1));
        assertEquals(0, b.count("Chems", 2, 2));
        assertEquals(3, b.count("Chems", 2, 1), "the R1 chems untouched");
    }
}
