package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.68.0, `09b` round 12 (Marek 11-23, verbatim): *"If the numbers have needlessly long decimal expansion and it does not allow players to
 * bring in more items, please consider instead convert it to kg with cruder "×0.5"."* · *"Should I build mutations as gene variants - yes. I
 * think there should be a system where the selected random mutation is applied to selected random gene slot creating a new gene. There genes
 * are not pre-build (too many options). Mutations are historically the low tech way into genetic research, so why not add it here. Also,
 * ghouls should be possible to reconstruct into genes as well - add a new slot if needed."* · on the turned ghoul: *"It is supposed to be a
 * mutation."*
 */
class RoundTwelveTest {
    /** A Random whose nextInt answers from the list in order (then 0). */
    static Random seq(int... v) {
        return new Random() {
            int i;

            @Override public int nextInt(int bound) { return i < v.length ? Math.min(bound - 1, v[i++]) : 0; }

            @Override public double nextDouble() { return 0.5; }
        };
    }

    static int slot(String s) { return Data.mutableSlots().indexOf(s); }

    static Data.Gene gene(Character c, String slot) {
        return c.baseTpl().genes().stream().filter(g -> g.slot().equals(slot)).findFirst().orElse(null);
    }

    static List<String> names(Data.Template t) { return t.genes().stream().map(Data.Gene::name).toList(); }

    // ------------------------------------------------------------------ the carry weight: × 0.5
    @Test
    void theCapacityIsHalfAPoundAKilogram() {
        Character c = Part3Test.of("Human", 5, 5, 5, 5, 5, 5, 5);
        double ath = c.skills()[Rules.skillIndex("Athletics")];
        assertEquals((25 + 125 + ath) / 2, c.capacityKg(), 1e-9);
        double before = c.capacityKg();
        c.perks.add(new Character.Feat("Strong Back", "", ""));
        assertEquals(before + 25, c.capacityKg(), 1e-9, "Strong Back: +50 lb = +25 kg, as it always was");
        double cap = c.capacityKg();
        assertEquals(0, Rules.loadTier(cap * 1.25, cap, true), "and nothing up to +25 % over");
        assertEquals(1, Rules.loadTier(cap * 1.25, cap, false));
        for (int s = 1; s <= 10; s++) {   // whole kilograms or halves, never 0.4536's tails
            double kg = Rules.carryKg(new int[]{s, 5, 5, 5, 5, 5, 5}, 2 * s + 21);
            assertEquals(Math.rint(kg * 2), kg * 2, 1e-9, "S " + s);
        }
    }

    // ------------------------------------------------------------------ mutations as gene variants
    static Data.Step step(String dial, int sign) { return new Data.Step(dial, sign, 1, ""); }

    @Test
    void aMutationChangesTheGeneOfARandomSlot() {
        List<String> slots = Data.mutableSlots();
        assertEquals(21, slots.size(), "the 21 slots of 03c");
        assertFalse(slots.contains("ORGAN") || slots.contains("Aptitude") || slots.contains(Data.NECROSIS));
        Character c = Part3Test.of("Human", 5, 5, 5, 5, 5, 5, 5);
        int i0 = c.attr(Rules.I, true), s0 = c.attr(Rules.S, true);
        // neutral: a good step (the 5th dial, Intelligence), a bad one on another dial (the first left, Strength), then the slot
        String out = c.applyMutation("neutral", seq(Data.DIALS.indexOf("Intelligence"), 0, slot("Mind")));
        assertTrue(out.contains("Intelligence +1, Strength −1 in the Mind slot: the plain Mind → Mind M1"), out);
        Data.Gene g = gene(c, "Mind");
        assertEquals("Mind M1", g.name());
        assertEquals(0, g.load(), "neutral: the load ±0");
        assertEquals("T1", g.tier());
        assertEquals(i0 + 1, c.attr(Rules.I, true));
        assertEquals(s0 - 1, c.attr(Rules.S, true));
        assertEquals("Human", c.template);
        assertTrue(c.effects.stream().noneMatch(e -> "mutation".equals(e.source())), "a gene, not an effect");
        assertEquals(1, c.mutationCount());
        assertNull(Data.gene(g.name()), "made in a body: in no list until a sample");
    }

    @Test
    void aMutationOverATemplatesGeneKeepsItsNumbersAndTheNameDoesNotGrow() {
        Character c = Part3Test.of("Survivor", 5, 5, 5, 5, 5, 5, 5);
        double iso0 = c.baseTpl().isolation(), food0 = c.baseTpl().foodFactor();
        int dt0 = c.dt(), a0 = c.attr(Rules.A, true);
        c.applySteps("Cold", List.of(step("DT", 1)));
        Data.Gene g = gene(c, "Cold");
        assertEquals("Arctic fat-burner M1", g.name());
        assertEquals(4, g.load(), "good: +4");
        assertEquals(iso0, c.baseTpl().isolation(), 1e-9, "the gene's own isolation kept");
        assertEquals(dt0 + 1, c.dt());
        assertEquals(food0 * 1.04, c.baseTpl().foodFactor(), 1e-9, "the load: food × (1 + 0.01 × 4)");
        c.applySteps("Cold", List.of(step("DT", 1), step("Agility", -1)));
        g = gene(c, "Cold");
        assertEquals("Arctic fat-burner M2", g.name(), "round 13: the name does not grow");
        assertEquals(dt0 + 2, c.dt());
        assertEquals(a0 - 1, c.attr(Rules.A, true));
        assertEquals(4, g.load(), "+4, then a neutral ±0");
        assertTrue(g.text().contains("mutated: DT +1, DT +1, Agility −1"), g.text());
        assertEquals(3, names(c.baseTpl()).size(), "still one gene a slot");
    }

    @Test
    void theTumourTheWitheredLimbAndTheBlindEyeStayBodyChanges() {
        Character c = Part3Test.of("Human", 5, 5, 5, 5, 5, 5, 5);
        assertTrue(c.applyMutation("very bad", seq(0)).startsWith("Tumour"), "d10 1-2: the tumour");
        assertTrue(c.applyMutation("very bad", seq(2)).startsWith("Withered Limb"), "d10 3");
        assertTrue(c.applyMutation("very bad", seq(3)).startsWith("One Eye Blind"), "d10 4");
        assertTrue(c.mutated.isEmpty());
        assertEquals(3, c.mutationCount());
    }

    @Test
    void theGmUndoesAMutation() {
        Character c = Part3Test.of("Human", 5, 5, 5, 5, 5, 5, 5);
        int i0 = c.attr(Rules.I, true);
        c.applySteps("Mind", List.of(step("Intelligence", 1)));
        assertTrue(c.undoMutation(0).contains("(Intelligence +1) undone in the Mind slot"));
        assertEquals(i0, c.attr(Rules.I, true));
        assertNull(gene(c, "Mind"));
        assertEquals("no such mutation", c.undoMutation(0));
    }

    // ------------------------------------------------------------------ the ghoul as a gene
    @Test
    void theGhoulTemplateIsTheHumanWithTheGhoulGene() {
        Data.Template t = Data.TEMPLATES.get("Ghoul");
        assertEquals(List.of("Ghoul"), names(t));
        Data.Gene g = Data.GENES.get("Ghoul");
        assertEquals(Data.NECROSIS, g.slot());
        assertArrayEquals(new int[]{-2, 3, 0, 0, 3, -4, 3}, t.mods(), "the template's numbers unchanged");
        assertArrayEquals(t.mods(), g.mods());
        assertEquals(0.4, t.radRes(), 1e-9);
        assertEquals(10, t.hp());
        Character born = Part3Test.of("Ghoul", 5, 5, 5, 5, 5, 6, 5), turned = Part3Test.of("Human", 5, 5, 5, 5, 5, 6, 5);
        turned.turnGhoul();
        for (int i = 0; i < 7; i++) assertEquals(born.attr(i, true), turned.attr(i, true), Rules.SPECIAL[i]);
        assertEquals(born.hpMax(), turned.hpMax(), "the gene's HP −5");
        assertArrayEquals(born.resistances(), turned.resistances(), 1e-9);
        assertTrue(born.isGhoul() && turned.isGhoul());
        assertFalse(Part3Test.of("Human", 5, 5, 5, 5, 5, 6, 5).isGhoul());
    }

    @Test
    void theGhoulLineIsTheNecrosisGenesVariantOverATurnedBody() {
        Character h = Part3Test.of("Survivor", 5, 5, 5, 5, 5, 6, 5);
        h.turnGhoul();
        int dt0 = h.dt();
        h.ghoulAdvance(1, Part3Test.fixed(0.5, 1));
        assertEquals("Ghoul · leathered", h.necrosisGene().name());
        assertEquals("Survivor", h.baseTpl().name());
        assertTrue(names(h.baseTpl()).contains("Broad HLA"), "the template's genes stay");
        assertEquals(dt0 + 3, h.dt(), "the leathered hide");
        h.ghoulAdvance(2, Part3Test.fixed(0.5, 1));
        assertEquals("Ghoul · leathered · glowing (jumped husk)", h.necrosisGene().name());
        assertTrue(h.glowing());
    }

    @Test
    void anOldSavesGhoulLineEffectGivesWayToTheGene() {
        Character g = Part3Test.of("Ghoul", 5, 5, 5, 5, 5, 6, 5);
        int dt0 = g.dt();
        g.ghoulPath.add("leathered");
        g.effects.add(new Character.Effect("Ghoul line: leathered", "ghoul line", "ghoul line", true, new int[7], Map.of("dt", 3.0), 0, null, 100));
        assertEquals(dt0 + 6, g.dt(), "before the repair: counted twice");
        g.migrate();
        assertTrue(g.effects.stream().noneMatch(e -> "ghoul line".equals(e.source())));
        assertEquals(dt0 + 3, g.dt());
    }

    @Test
    void aSaveKeepsTheChangedGenes() {
        Character c = Part3Test.of("Human", 5, 5, 5, 5, 5, 6, 5);
        c.applySteps("Eyes", List.of(step("Perception", -1)));
        c.turnGhoul();
        Character d = Store.fromJson(Store.toJson(c));
        d.migrate();
        assertEquals(names(c.baseTpl()), names(d.baseTpl()));
        assertTrue(d.necrosis);
        for (int i = 0; i < 7; i++) assertEquals(c.attr(i, true), d.attr(i, true), Rules.SPECIAL[i]);
    }

    // ------------------------------------------------------------------ the gene pool
    @Test
    void aSampleTakesTheChangedGenesIntoThePoolAndTherapyGivesThem(@TempDir Path dir) {
        Character c = Part3Test.of("Human", 5, 5, 5, 5, 5, 6, 5);
        c.applySteps("Eyes", List.of(step("dark", 1)));
        c.turnGhoul();
        c.ghoulAdvance(1, Part3Test.fixed(0.5, 1));
        Base b = new Base(dir);
        List<String> added = b.sample(c.recipes());
        assertEquals(List.of("Eyes M1", "Ghoul · leathered"), added);
        assertTrue(b.sample(c.recipes()).isEmpty(), "known already");
        Character other = Part3Test.of("Human", 5, 5, 5, 5, 5, 6, 5);
        other.applySteps("Eyes", List.of(step("Perception", 1)));
        assertEquals(List.of("Eyes M1b"), b.sample(other.recipes()), "another Eyes M1: a letter, so the two are never mistaken");
        assertEquals(2, b.pooled("Eyes").size());
        Character born = Part3Test.of("Ghoul", 5, 5, 5, 5, 5, 6, 5);
        assertTrue(b.sample(born.recipes()).isEmpty(), "a ghoul born carries the data's own Ghoul gene: nothing new");
        Data.POOL.clear();
        Base again = new Base(dir);
        assertEquals(List.of("Eyes M1", "Ghoul · leathered", "Eyes M1b"), new ArrayList<>(again.genePool.keySet()), "kept, each made again from its recipe");
        assertNotNull(Data.gene("Ghoul · leathered"));
        assertEquals(1, Data.gene("Eyes M1b").mods()[Rules.P]);
        Character o = Part3Test.of("Human", 5, 5, 5, 5, 5, 6, 5);
        assertEquals(0, o.senses());
        String r = o.giveTherapy(Data.gene("Eyes M1"), 100, 1, false, false, new Random(1));
        assertFalse(r.startsWith("!"), r);
        o.therapies.get(0).courseLeft = 0;
        assertEquals(1, o.senses(), "the dark halved, by gene therapy");
    }
}
