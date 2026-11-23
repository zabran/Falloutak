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
    @Test
    void aMutationChangesTheGeneOfARandomSlot() {
        List<String> slots = Data.mutableSlots();
        assertEquals(21, slots.size(), "the 21 slots of 03c");
        assertFalse(slots.contains("ORGAN") || slots.contains("Aptitude") || slots.contains(Data.NECROSIS));
        Character c = Part3Test.of("Human", 5, 5, 5, 5, 5, 5, 5);
        int i0 = c.attr(Rules.I, true), s0 = c.attr(Rules.S, true);
        String out = c.applyMutation(Data.mutation("Big Brain"), seq(slot("Mind")));
        assertTrue(out.contains("in the Mind slot: the plain Mind → a new gene, plain Mind · Big Brain"), out);
        Data.Gene g = gene(c, "Mind");
        assertEquals("plain Mind · Big Brain", g.name());
        assertEquals(0, g.load(), "neutral: the load ±0");
        assertEquals("T1", g.tier());
        assertEquals(i0 + 1, c.attr(Rules.I, true));
        assertEquals(s0 - 1, c.attr(Rules.S, true));
        assertEquals("Human", c.template);
        assertTrue(c.effects.stream().noneMatch(e -> "mutation".equals(e.source())), "a gene, not an effect");
        assertEquals(1, c.mutationCount());
        assertNull(Data.GENES.get(g.name()), "made, never in the data");
        assertEquals(g.text(), Data.gene(g.name()).text(), "found by its name");
    }

    @Test
    void aMutationOverATemplatesGeneKeepsItsNumbersAndStacks() {
        Character c = Part3Test.of("Survivor", 5, 5, 5, 5, 5, 5, 5);
        double iso0 = c.baseTpl().isolation(), food0 = c.baseTpl().foodFactor();
        int dt0 = c.dt(), a0 = c.attr(Rules.A, true);
        c.applyMutation(Data.mutation("Thick Skin"), seq(slot("Cold")));
        Data.Gene g = gene(c, "Cold");
        assertEquals("Arctic fat-burner · Thick Skin", g.name());
        assertEquals(4, g.load(), "good: +4");
        assertEquals(iso0, c.baseTpl().isolation(), 1e-9, "the gene's own isolation kept");
        assertEquals(dt0 + 1, c.dt());
        assertEquals(food0 * 1.04, c.baseTpl().foodFactor(), 1e-9, "the load: food × (1 + 0.01 × 4)");
        c.applyMutation(Data.mutation("Scales"), seq(slot("Cold")));
        g = gene(c, "Cold");
        assertEquals("Arctic fat-burner · Thick Skin · Scales", g.name(), "the same slot again: one more change");
        assertEquals(dt0 + 3, c.dt());
        assertEquals(a0 - 1, c.attr(Rules.A, true));
        assertEquals(3, names(c.baseTpl()).size(), "still one gene a slot");
    }

    @Test
    void aRandomAttributeMutationNamesItsAttribute() {
        Character c = Part3Test.of("Human", 5, 5, 5, 5, 5, 5, 5);
        int a0 = c.attr(Rules.A, true);
        c.applyMutation(Data.mutation("Weak Attribute"), seq(Rules.A, slot("Frame")));
        Data.Gene g = gene(c, "Frame");
        assertEquals("plain Frame · Weak Attribute (A)", g.name());
        assertEquals(-4, g.load(), "bad: −4");
        assertEquals(a0 - 1, c.attr(Rules.A, true));
    }

    @Test
    void theTumourTheWitheredLimbAndTheBlindEyeStayBodyChanges() {
        Character c = Part3Test.of("Human", 5, 5, 5, 5, 5, 5, 5);
        for (String n : List.of("Tumour", "Withered Limb", "One Eye Blind")) {
            assertTrue(c.applyMutation(Data.mutation(n), seq(0)).contains("a body change, no gene"));
        }
        assertTrue(c.mutated.isEmpty());
        assertEquals(3, c.mutationCount());
    }

    @Test
    void theGmUndoesAMutation() {
        Character c = Part3Test.of("Human", 5, 5, 5, 5, 5, 5, 5);
        int i0 = c.attr(Rules.I, true);
        c.applyMutation(Data.mutation("Big Brain"), seq(slot("Mind")));
        assertTrue(c.undoMutation(0).contains("undone in the Mind slot"));
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
        c.applyMutation(Data.mutation("Weak Attribute"), seq(Rules.P, slot("Eyes")));
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
        c.applyMutation(Data.mutation("Night Eyes"), seq(slot("Eyes")));
        c.turnGhoul();
        c.ghoulAdvance(1, Part3Test.fixed(0.5, 1));
        Base b = new Base(dir);
        List<String> added = b.sample(c.recipes());
        assertEquals(List.of("plain Eyes · Night Eyes", "Ghoul · leathered"), added);
        assertTrue(b.sample(c.recipes()).isEmpty(), "known already");
        assertEquals(1, b.pooled("Eyes").size());
        Character born = Part3Test.of("Ghoul", 5, 5, 5, 5, 5, 6, 5);
        assertTrue(b.sample(born.recipes()).isEmpty(), "a ghoul born carries the data's own Ghoul gene: nothing new");
        Data.VARIANTS.clear();
        Base again = new Base(dir);
        assertEquals(added, new ArrayList<>(again.genePool.keySet()), "the pool is kept, each gene made again from its recipe");
        assertNotNull(Data.gene("Ghoul · leathered"));
        Character o = Part3Test.of("Human", 5, 5, 5, 5, 5, 6, 5);
        assertEquals(0, o.senses());
        String r = o.giveTherapy(Data.gene("plain Eyes · Night Eyes"), 100, 1, false, false, new Random(1));
        assertFalse(r.startsWith("!"), r);
        o.therapies.get(0).courseLeft = 0;
        assertEquals(1, o.senses(), "the night eyes, by gene therapy");
    }
}
