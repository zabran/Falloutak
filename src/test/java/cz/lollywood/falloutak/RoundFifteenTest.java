package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.71.0, `09b` round 15 (Marek 11-23, verbatim): the skill dial *"Either increase heir weight to 6, or divide them into 6 mutations."* ·
 * *"the sun and melee damage - what is the issue here?"* · the lean Necrosis *"seems so. I presume that every ghouls gene is now the mutation
 * of current gene instead of their replacement."* · ordinary mutations in Necrosis *"yes, that is what was meant. Also, after gaining the
 * necrosis gene, it should be possible to use it in gene therapy."*
 */
class RoundFifteenTest {
    static Data.Step step(String dial, int sign, int size) { return new Data.Step(dial, sign, size, ""); }

    static Data.Gene gene(Character c, String slot) { return RoundTwelveTest.gene(c, slot); }

    // ------------------------------------------------------------------ the skill dial weighs 6
    @Test
    void theSkillDialIsSixOfTwentyNine() {
        assertEquals(6, Data.SKILL_WEIGHT);
        for (int i = 24; i < 29; i++)   // after the 24 dials, five more draws of the skill dial
            assertEquals("skill", Data.rollMutation("good", RoundTwelveTest.seq(i, 0)).steps().get(0).dial(), "draw " + i);
        assertEquals("Strength", Data.rollMutation("good", RoundTwelveTest.seq(0)).steps().get(0).dial());
        // a neutral pair never puts both steps on the skill dial
        List<Data.Step> n = Data.rollMutation("neutral", RoundTwelveTest.seq(Data.DIALS.indexOf("skill"), 0, 23)).steps();
        assertEquals("skill", n.get(0).dial());
        assertNotEquals("skill", n.get(1).dial());
    }

    // ------------------------------------------------------------------ the lean Necrosis
    @Test
    void aTurnedGhoulsNumbersAreMutationsOfItsOwnGenes() {
        Character c = Part3Test.of("Survivor", 5, 5, 5, 5, 5, 6, 5);
        double hla = Data.GENES.get("Broad HLA").poison();
        c.turnGhoul();
        Data.Gene imm = gene(c, "Immunity");
        assertEquals("Broad HLA G1", imm.name(), "round 16: the ghoul's mutations G1-G4");
        assertEquals(hla, imm.poison(), 1e-9, "the gene's own numbers kept");
        assertEquals(10, imm.other().get("poison_res"), 1e-9, "and the ghoul's on top");
        assertEquals("Muscle G1", gene(c, "Muscle").name(), "a plain slot changed");
        Data.Gene nec = c.necrosisGene();
        assertEquals("Ghoul", nec.name());
        assertEquals(0.4, nec.rad(), 1e-9);
        assertArrayEquals(new int[]{0, 0, 0, 0, 0, 0, 3}, nec.mods(), "the lean Necrosis: rad res and Luck");
    }

    @Test
    void theGhoulsMutationsAreNamedG1ToG4() {
        Character h = Part3Test.of("Human", 5, 5, 5, 5, 5, 6, 5);
        h.turnGhoul();
        assertEquals("Look", gene(h, "Look") == null ? "Look" : "?", "the ghoul itself touches no Look");
        String[] want = {"G2", "G3", "G4"};
        String[] meta = {"Metabolism G2", "Metabolism G2 G3", "Metabolism G3 G4"};
        String[] mind = {"Mind G1", "Mind G1 G3", "Mind G1 G4"};
        String[] nec = {"Ghoul G2", "Ghoul G3", "Ghoul"};
        for (int k = 0; k < 3; k++) {
            h.ghoulAdvance(1, Part3Test.fixed(0.5, 1));
            assertEquals("Look " + want[k], gene(h, "Look").name(), "each stage's Charisma replaces the last: only the latest lasts");
            assertEquals("Muscle G1", gene(h, "Muscle").name(), "only the turn changed it: G1 to the end");
            assertEquals(meta[k], gene(h, "Metabolism").name(), "the leathered's E −1 lasts until the glowing one's E +2 replaces it; the husk's healing stays");
            assertEquals(mind[k], gene(h, "Mind").name(), "the ghoul's own I +3 stays; the stages' I replace each other");
            assertEquals(nec[k], h.necrosisGene().name(), "the rad res: 75, 90, then back to the ghoul's own");
        }
        assertEquals("Skin G2", gene(h, "Skin").name(), "the hide and the smell are the leathered's: G2");
        assertEquals("Pain G3", gene(h, "Pain").name());
        assertEquals("Eyes G1 G4", gene(h, "Eyes").name(), "the ghoul's P +3 and the glowing one's light");
        assertEquals("Immunity G1 G2", gene(h, "Immunity").name());
        assertEquals("Frame G1 G4", gene(h, "Frame").name());
        Character b = Part3Test.of("Ghoul", 5, 5, 5, 5, 5, 6, 5);
        assertEquals("Ghoul mind", gene(b, "Mind").name(), "born: its own genes, unchanged");
        b.ghoulAdvance(2, Part3Test.fixed(0.5, 1));
        assertEquals("Ghoul mind G3", gene(b, "Mind").name());
    }

    @Test
    void eachStageReplacesOnlyItsOwnNumbers() {
        Character g = Part3Test.of("Human", 5, 5, 5, 5, 5, 6, 5);
        g.turnGhoul();
        int c0 = g.attr(Rules.C, true), dt0 = g.dt();
        g.ghoulAdvance(1, Part3Test.fixed(0.5, 1));
        assertEquals(-2, gene(g, "Look").mods()[Rules.C]);
        assertEquals(dt0 + 3, g.dt(), "the walked hide in Skin");
        g.ghoulAdvance(1, Part3Test.fixed(0.5, 1));
        assertEquals(-4, gene(g, "Look").mods()[Rules.C], "the husk's C −4 replaces the leathered's −2");
        assertEquals(c0 - 4, g.attr(Rules.C, true));
        assertEquals(1.0, gene(g, "Metabolism").other().get("healing_half"), 1e-9);
        g.ghoulAdvance(1, Part3Test.fixed(0.5, 1));
        assertEquals(-5, gene(g, "Look").mods()[Rules.C]);
        assertEquals(dt0 + 3, g.dt(), "the hide stays: no later stage touches it");
        assertEquals(2, gene(g, "Metabolism").mods()[Rules.E], "E +2 replaces the leathered's −1");
        assertNull(g.necrosisGene().other().get("rad_res"), "glowing: back to the ghoul's own 40 %");
        Character j = Part3Test.of("Human", 5, 5, 5, 5, 5, 6, 5);
        j.turnGhoul();
        int jd = j.dt();
        j.ghoulAdvance(2, Part3Test.fixed(0.5, 1));   // straight to husk: the leathered jumped
        assertEquals(jd, j.dt(), "a jumped stage: no hide");
        assertEquals(-10, gene(j, "Skin").other().get("skill:Sneak"), 1e-9, "but its smell");
        assertEquals(50, j.necrosisGene().other().get("rad_res"), 1e-9, "the husk walked: 40 + 50 = 90 %");
    }

    @Test
    void bornAndTurnedGhoulsAreTheSameBodyAtEveryStage() {
        for (int steps = 0; steps <= 3; steps++) {
            Character born = Part3Test.of("Ghoul", 5, 5, 5, 5, 5, 6, 5), turned = Part3Test.of("Human", 5, 5, 5, 5, 5, 6, 5);
            turned.turnGhoul();
            for (int k = 0; k < steps; k++) { born.ghoulAdvance(1, Part3Test.fixed(0.5, 1)); turned.ghoulAdvance(1, Part3Test.fixed(0.5, 1)); }
            for (int i = 0; i < 7; i++) assertEquals(born.attr(i, true), turned.attr(i, true), Rules.SPECIAL[i] + " at " + steps);
            assertEquals(born.dt(), turned.dt());
            assertEquals(born.hpMax(), turned.hpMax());
            assertArrayEquals(born.resistances(), turned.resistances(), 1e-9, "at " + steps);
            assertEquals(born.painGain(), turned.painGain(), 1e-9);
            assertEquals(born.senses(), turned.senses());
            assertEquals(born.other("healing_half"), turned.other("healing_half"), 1e-9);
        }
    }

    // ------------------------------------------------------------------ a little necrosis, and its therapy
    @Test
    void anOrdinaryMutationInNecrosisGivesALittleNecrosis(@TempDir Path dir) {
        Character c = Part3Test.of("Human", 5, 5, 5, 5, 5, 6, 5);
        double r0 = c.resistances()[1];
        c.applySteps(Data.NECROSIS, List.of(step("DT", 1, 1)));
        Data.Gene n = c.necrosisGene();
        assertEquals("Necrosis M1", n.name());
        assertEquals(0.10, n.rad(), 1e-9, "a little necrosis: rad res +10 %");
        assertEquals(r0 + 10, c.resistances()[1], 1e-9);
        assertFalse(c.isGhoul(), "without becoming a ghoul");
        c.applySteps(Data.NECROSIS, List.of(step("Luck", -1, 1)));
        assertEquals(0.20, c.necrosisGene().rad(), 1e-9, "each one a little more");
        assertEquals("Necrosis M2", c.necrosisGene().name());
        Base b = new Base(dir);
        assertEquals(List.of("Necrosis M2"), b.sample(c.recipes()), "its sample into the pool");
        Character o = Part3Test.of("Human", 5, 5, 5, 5, 5, 6, 5);
        double o0 = o.resistances()[1];
        assertFalse(o.giveTherapy(Data.gene("Necrosis M2"), 100, 1, false, false, new Random(1)).startsWith("!"));
        o.therapies.get(0).courseLeft = 0;
        assertEquals(o0 + 20, o.resistances()[1], 1e-9, "the necrosis gene by gene therapy");
        assertFalse(o.isGhoul());
    }

    @Test
    void aGhoulsOrdinaryMutationInNecrosisChangesItsNecrosisGene() {
        Character g = Part3Test.of("Ghoul", 5, 5, 5, 5, 5, 6, 5);
        g.ghoulAdvance(1, Part3Test.fixed(0.5, 1));
        g.applySteps(Data.NECROSIS, List.of(step("HP max", 1, 1)));
        assertEquals("Ghoul M1 G2", g.necrosisGene().name(), "a mutation's M and the line's G");
        assertEquals(0.4 + 0.10, g.necrosisGene().rad(), 1e-9);
        assertEquals(35, g.necrosisGene().other().get("rad_res"), 1e-9, "the leathered stage's own kept");
    }

    // ------------------------------------------------------------------ the sun and melee damage, wired
    @Test
    void theSunHurtsABodyThatCannotBearIt() {
        Character c = Part3Test.of("Human", 5, 5, 5, 5, 5, 6, 5);
        c.inSun = true;
        assertNull(c.sunHour(), "a plain body: nothing");
        c.applySteps("Skin", List.of(step("dark", -1, 1)));
        assertEquals("pain", c.tpl().sun());
        double p0 = c.survival.get(Rules.Stat.PAIN);
        assertTrue(c.sunHour().startsWith("the sun hurts"));
        assertEquals(p0 + 10 * c.painGain(), c.survival.get(Rules.Stat.PAIN), 1e-9);
        c.inSun = false;
        assertNull(c.sunHour());
        assertEquals(0, c.sunHours, 1e-9, "out of the sun the count starts again");
        Character v = Part3Test.of("Human", 5, 5, 5, 5, 5, 6, 5);
        v.applySteps("Skin", List.of(step("dark", -1, 2)));
        assertEquals("hp", v.tpl().sun());
        v.inSun = true;
        int hp0 = v.hp;
        v.sunHour();
        v.sunHour();
        assertEquals(hp0, v.hp, "two hours: Pain only");
        assertTrue(v.sunHour().contains("1 HP"));
        assertEquals(hp0 - 1, v.hp, "the third: 1 HP");
    }

    @Test
    void meleeDamageFollowsTheBody() {
        Character c = Part3Test.of("Human", 5, 5, 5, 5, 5, 6, 5);
        assertEquals(1.0, Rules.meleeFactor(c.tpl().bonusDamage()), 1e-9);
        c.applySteps("Muscle", List.of(step("melee", 1, 1)));
        assertEquals(1.1, Rules.meleeFactor(c.tpl().bonusDamage()), 1e-9);
        assertEquals(1.06, Rules.meleeFactor(Data.TEMPLATES.get("Super Mutant").bonusDamage()), 1e-9, "a template's too");
    }
}
