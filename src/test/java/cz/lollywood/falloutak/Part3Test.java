package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Part 3 (`_notes/Fallout/03b-SPECIES-RULES.md`, RULED 11-16) against its own examples. */
class Part3Test {
    /** A Random whose rolls are given: nextDouble from {@code d}, nextInt as (roll − 1). */
    static Random fixed(double d, int roll) {
        return new Random() {
            @Override public double nextDouble() { return d; }
            @Override public int nextInt(int bound) { return Math.min(bound - 1, Math.max(0, roll - 1)); }
        };
    }

    static Character of(String template, int... points) {
        Character c = new Character();
        c.applyTemplate(template);
        for (int i = 0; i < 7; i++) c.base[i] = c.tpl().fixedAttr(i) ? 0 : points[i];
        c.hp = c.hpMax();
        for (Rules.Stat st : Rules.Stat.values())
            c.survival.put(st, switch (st) { case POISON, RADS, HYPOTHERMIA, HYPERTHERMIA, PAIN -> 0.0; default -> c.max(st); });
        return c;
    }

    @Test
    void attributesAndTemplates() {
        // the Ent: 40 HP on top of S + 2·E, both at least 11: 73 to 100 (round 3, "intended")
        assertEquals(73, of("Ent", 1, 10, 1, 10, 10, 6, 2).hpMax());
        assertEquals(100, of("Ent", 10, 3, 10, 4, 4, 6, 3).hpMax());     // P −2 and A −5 need 3 and 6 points
        assertNull(of("Ent", 10, 3, 10, 4, 4, 6, 3).checkPoints());
        assertTrue(of("Ent", 10, 1, 10, 5, 5, 6, 3).checkPoints().contains("Perception"));
        assertFalse(Data.TEMPLATES.get("Ent").groupUnlock());
        assertTrue(Data.TEMPLATES.get("Vampire").premium());
        // the modifier moves both ends, never below 1: the deathclaw's C −7 needs 8 points
        Character dc = of("Deathclaw", 4, 4, 4, 7, 7, 8, 8);
        assertEquals(42, dc.tpl().attributePoints());
        assertTrue(dc.checkPoints().contains("Charisma"));
        assertArrayEquals(new int[]{1, 3}, dc.attrRange(Rules.C));
        // the basic robot's fixed C 1 and L 5 take no points
        Character rb = of("Basic Robot", 9, 8, 8, 0, 8, 8, 0);
        assertNull(rb.checkPoints());
        assertEquals(1, rb.attr(Rules.C, true));
        assertEquals(5, rb.attr(Rules.L, true));
        assertEquals(0, rb.tpl().perkRate());
        // the elf 10 HP, Coldvein 3 points (round 3)
        assertEquals(10, Data.TEMPLATES.get("Elf").hp());
        assertEquals("3", Data.TEMPLATES.get("Coldvein").cosPoints());
    }

    @Test
    void resistancesAndSkills() {
        Character v = of("Vampire", 5, 5, 5, 5, 5, 6, 9);
        double[] r = v.resistances();
        // rad: 2 E + Survival / 10 − 100 (the vampire's)
        double surv = v.skill("Survival");
        assertEquals(2 * 7 + surv / 10 - 100, r[1], 1e-9);
        assertEquals(20, v.skillBonus("Rhetorics", true));
        assertEquals(50, of("Pikachu", 6, 6, 6, 6, 6, 5, 5).resistances()[5], 1e-9);   // isolation
        Character ent = of("Ent", 5, 5, 5, 5, 5, 6, 9);
        assertEquals(5, ent.dt());
        assertTrue(ent.tpl().huge());
        assertEquals(ent.skills()[Rules.skillIndex("Sneak")], 3 * ent.attr(Rules.A, false) - 30, 1e-9);
        assertEquals("Isolation", Character.DAMAGE_TYPES[5]);
    }

    @Test
    void senses() {
        assertEquals(-40, Combat.lightTerm(3, 10, 0));
        assertEquals(-20, Combat.lightTerm(3, 10, 1));   // the dark halved
        assertEquals(-12, Combat.lightTerm(8, 10, 1));
        assertEquals(-10, Combat.lightTerm(3, 10, 2));   // night vision: darkness as dim
        assertEquals(0, Combat.lightTerm(8, 10, 2));
        assertEquals(1, of("Elf", 5, 5, 5, 5, 5, 6, 9).senses());
        assertEquals(2, of("Catfolk", 5, 5, 5, 5, 5, 6, 9).senses());
        Character h = of("Human", 5, 5, 5, 5, 5, 6, 9);
        assertNull(h.fit(Data.IMPLANTS.get("Low-Light Eyes"), 4, null));
        assertEquals(1, h.senses());
    }

    @Test
    void implants() {
        Character c = of("Human", 5, 5, 5, 5, 5, 6, 9);
        assertNotNull(c.fit(Data.IMPLANTS.get("Reflex Chip"), 4, null));       // needs the Neural Link
        assertNotNull(c.fit(Data.IMPLANTS.get("Reflex Chip"), 2, null));       // no Tier II: nothing like it today (03b §3b)
        int rate = c.skillRate();
        double cs = c.skill("Computer Science");
        assertNull(c.fit(Data.IMPLANTS.get("Neural Link"), 2, null));          // Tier II: CS +10, memory +1 skill point a level
        assertEquals(cs + 10, c.skill("Computer Science"), 1e-9);
        assertEquals(rate + 1, c.skillRate());
        assertNull(c.fit(Data.IMPLANTS.get("Reflex Chip"), 3, null));          // Tier III: Initiative +3
        assertNotNull(c.fit(Data.IMPLANTS.get("Pain Editor"), 4, null));       // brain: the Link + one module, both taken
        assertNull(c.fit(Data.IMPLANTS.get("Cyber Eyes"), 4, null));
        assertEquals(9, c.attr(Rules.P, true));                                 // 5 + 4
        assertNull(c.fit(Data.IMPLANTS.get("Bone Lacing"), 3, null));          // Tier III: HP +3
        assertEquals(5 + 10 + 15 + 3, c.hpMax());
        // a torso hit spares the head's implants but wears the skeleton's
        c.wearOnHit(Rules.Part.TORSO, 30, false);
        assertEquals(9, c.attr(Rules.P, true));
        assertEquals(70, c.inSlot("implant:skeleton#1").condition(), 1e-9);
        // a head hit: the eyes at 70 % give 0.5 + 2 × 0.7 / 3 of +4, rounded down: +3; a crit of 40 breaks them
        c.wearOnHit(Rules.Part.HEAD, 30, false);
        assertEquals(8, c.attr(Rules.P, true));
        c.wearOnHit(Rules.Part.EYES, 40, true);
        assertEquals(5, c.attr(Rules.P, true));
        assertEquals(0, c.inSlot("implant:eyes#1").condition(), 1e-9);
        assertNotNull(c.removeImplant("eyes"));
        assertNull(c.inSlot("implant:eyes#1"));
        assertEquals(25, Rules.salvageLoss(75), 1e-9);
        assertEquals(0, Rules.salvageLoss(150), 1e-9);
        assertNotNull(of("Basic Robot", 9, 8, 8, 0, 8, 8, 0).fit(Data.IMPLANTS.get("Cyber Eyes"), 4, null));
    }

    @Test
    void chems() {
        Character c = of("Human", 5, 5, 5, 5, 5, 6, 9);
        Data.Chem buffout = Data.CHEMS.get("Buffout");
        String s = c.dose(buffout, fixed(0.1, 1));                              // 0.1 < 25 %: addicted
        assertTrue(s.contains("ADDICTED"), s);
        assertEquals(7, c.attr(Rules.S, false));                                // +2 for 6 h
        for (int i = 0; i < 6; i++) Hud.hour(c);
        assertEquals("Buffout (after-effect)", c.inSlot("chem:Buffout").name());
        assertEquals(3, c.attr(Rules.S, false));
        for (int i = 0; i < 18; i++) Hud.hour(c);
        assertEquals("Buffout (withdrawal)", c.inSlot("chem:Buffout").name());
        assertEquals(336, c.inSlot("chem:Buffout").hoursLeft(), 1e-9);         // 2 weeks (RULED lengths)
        c.dose(buffout, fixed(0.99, 1));                                        // a dose in withdrawal: back to stage 1, still addicted
        assertEquals("Buffout", c.inSlot("chem:Buffout").name());
        assertTrue(c.addictions.contains("Buffout"));
        for (int i = 0; i < 6 + 18 + 336; i++) Hud.hour(c);
        assertEquals("Buffout (dependence)", c.inSlot("chem:Buffout").name());
        assertEquals(2160, c.inSlot("chem:Buffout").hoursLeft(), 1e-9);        // 3 months
        for (int i = 0; i < 2160; i++) Hud.hour(c);
        assertNull(c.inSlot("chem:Buffout"));
        assertFalse(c.addictions.contains("Buffout"));
        // not addicted: the crash, then clean
        Character d = of("Human", 5, 5, 5, 5, 5, 6, 9);
        d.dose(buffout, fixed(0.99, 1));
        for (int i = 0; i < 24; i++) Hud.hour(d);
        assertNull(d.inSlot("chem:Buffout"));
        // the 7-day counter: a third dose in a week at 25 % × (1 + ½ × 2) = 50 %
        assertEquals(0.5, Rules.addictionChance(0.25, false, false, 2), 1e-9);
        assertEquals(1.0, Rules.addictionChance(0.5, true, false, 1), 1e-9);
        // RadAway: 50 + Medicine rads over 4 hours
        Character r = of("Human", 5, 5, 5, 5, 5, 6, 9);
        r.survival.put(Rules.Stat.RADS, 400.0);
        double med = r.skill("Medicine");
        r.dose(Data.CHEMS.get("RadAway"), fixed(0.99, 1));
        for (int i = 0; i < 4; i++) Hud.hour(r);
        assertEquals(400 - 50 - med, r.survival.get(Rules.Stat.RADS), 1e-6);
        // Prussian blue (09e round 2: the lower RadAway, Chems III R2): half of it over three days, never addictive
        Character pb = of("Human", 5, 5, 5, 5, 5, 6, 9);
        pb.survival.put(Rules.Stat.RADS, 400.0);
        pb.dose(Data.CHEMS.get("Prussian blue"), fixed(0.0, 1));
        for (int i = 0; i < 24; i++) Hud.hour(pb);
        assertEquals(400 - (50 + med) / 2 / 3, pb.survival.get(Rules.Stat.RADS), 1e-6, "a third of it in the first day");
        for (int i = 0; i < 48; i++) Hud.hour(pb);
        assertEquals(400 - (50 + med) / 2, pb.survival.get(Rules.Stat.RADS), 1e-6);
        assertFalse(pb.addictions.contains("Prussian blue"));
        // a painkiller lowers the felt Pain (04c §4b)
        Character p = of("Human", 5, 5, 5, 5, 5, 6, 9);
        p.survival.put(Rules.Stat.PAIN, 60.0);
        p.dose(Data.CHEMS.get("Morphine"), fixed(0.99, 1));
        assertEquals(20, p.painFelt(), 1e-9);
        // an antidote ends Jet's addiction
        Character j = of("Human", 5, 5, 5, 5, 5, 6, 9);
        j.dose(Data.CHEMS.get("Jet"), fixed(0.1, 1));
        assertTrue(j.addictions.contains("Jet"));
        j.dose(Data.CHEMS.get("Jet Antidote"), fixed(0.99, 1));
        assertFalse(j.addictions.contains("Jet"));
    }

    @Test
    void mutationsSurgeryRevival() {
        assertEquals(50, Rules.mutationScore(50, 5, 100));
        assertEquals("bad", Rules.mutationCategory(50));
        assertEquals("very bad", Rules.mutationCategory(Rules.mutationScore(20, 5, 300)));
        assertEquals("good", Rules.mutationCategory(Rules.mutationScore(81, 5, 100)));
        Character c = of("Human", 5, 5, 5, 5, 5, 6, 9);
        c.survival.put(Rules.Stat.RADS, 250.0);
        assertTrue(c.mutate(300, fixed(0.5, 50)).startsWith("you hold only"));
        assertTrue(c.mutate(200, fixed(0.5, 60)).contains("NEUTRAL"));          // Luck 9: 60 + (9 − 5) × 4 − 10 = 66
        assertEquals(50, c.survival.get(Rules.Stat.RADS), 1e-9);
        // surgery (03b §4's examples): 48 % and the 98 % cap
        assertEquals(48, Rules.surgeryChance(80, 1.0, 1.0, Rules.operationFactor("brain")), 1e-9);
        assertEquals(98, Rules.surgeryChance(150, 1.2, 1.3, 1.0), 1e-9);
        assertEquals("minor", Rules.surgeryFailure(96, 100));
        // revival (03b §5's examples): 4 % and 53 %
        assertEquals(4.3, Rules.revivalChance(50, 0.2, 2, 0, 1), 1e-9);
        assertEquals(52.8, Rules.revivalChance(120, 1.0, 0, 4, 1), 1e-9);
        assertEquals(0, Rules.revivalChance(120, 1.5, 15, 0, 1), 1e-9);        // ~14 minutes untouched: final
        assertTrue(Rules.revivalChance(120, 1.5, 15, 0, 4) > 0);               // Coldvein's window × 4
        assertEquals(1, Rules.brainPrice(8, 1));
        assertEquals(0, Rules.brainPrice(8, 2));
        assertEquals(4.0, of("Coldvein", 5, 5, 5, 5, 5, 6, 9).deathWindow(), 1e-9);
    }

    @Test
    void fallsAndLimbs() {
        assertEquals(0, Rules.fallDice(5.9));
        assertEquals(1, Rules.fallDice(6));
        assertEquals(2, Rules.fallDice(9.5));
        // the Athletics CHECK (round 4: "with high Athletics you can ignore basic falls"): 40 × the dice ignores, 20 × halves
        assertEquals(0, Rules.fallShare(40, 1), 1e-9);
        assertEquals(0.5, Rules.fallShare(38, 1), 1e-9);
        assertEquals(1, Rules.fallShare(38, 2), 1e-9);
        Character cat = of("Catfolk", 5, 5, 5, 5, 5, 6, 9);                    // Athletics 2 × 9 + 2 × 5 + 2 × 5 = 38
        String s = Part3.fall(cat, 9, fixed(0.5, 6));                           // 2d6 = 12, 38 < 40: full, the catfolk × ½: 6
        assertTrue(s.contains("hit for 6"), s);
        assertTrue(Part3.fall(of("Human", 10, 5, 10, 5, 4, 3, 3), 6, fixed(0.5, 6)).contains("takes it"));   // Athletics 46 ≥ 40
        assertNull(Part3.fall(cat, 4, fixed(0.5, 6)));
        assertEquals(-10, Rules.prostheticToHit(80));
        assertEquals(-25, Rules.prostheticToHit(50));
        assertEquals(5, Rules.prostheticToHit(120));
        Character c = of("Human", 5, 5, 5, 5, 5, 6, 9);
        assertNull(c.fitLimb("right_arm", "hook"));
        assertEquals(-25, c.armToHit(false));
        assertNull(c.fitLimb("left_leg", "peg"));
        assertEquals(1.0, c.legFactor(), 1e-9);                                 // RULED 11-16: a prosthetic leg moves as a natural one
        assertNotNull(c.fitLimb("right_leg", "bionic"));                        // needs the Neural Link
        assertNull(c.fitLimb("left_leg", "none"));
        assertEquals(1.0, c.legFactor(), 1e-9);
    }

    @Test
    void round9() {
        // creation (Marek 11-17): every template starts with its points inside its ranges, never more than it has to share
        for (Data.Template tp : Data.TEMPLATES.values()) {
            Character c = new Character();
            c.applyTemplate(tp.name());
            Forms.startPoints(c);
            assertTrue(c.pointsSpent() <= tp.attributePoints(), tp.name());
            for (int i = 0; i < 7; i++)
                if (!tp.fixedAttr(i)) { assertTrue(c.base[i] >= Math.max(1, 1 - tp.mods()[i]), tp.name()); assertTrue(c.base[i] <= 10, tp.name()); }
        }
        // a gene is a gene (Marek 11-17): no trait tag in the names, yet Rh-null still carries Medical Marvel's rule
        for (Data.Gene g : Data.GENES.values()) assertFalse(g.name().contains("("), g.name());
        assertTrue(of("Golden Blood", 5, 5, 5, 5, 5, 6, 9).has("Medical Marvel"));
        assertTrue(Menus.width("a very long label for a button that would overflow") > 150);
    }

    @Test
    void round8() {
        // a player's own template from the free genes (Marek 11-16)
        assertTrue(Data.GENES.size() > 30);
        assertNotNull(Data.checkCustom(List.of()));
        assertNotNull(Data.checkCustom(List.of("Power fibres", "Endurance fibres")));                      // one slot
        assertNull(Data.checkCustom(List.of("Twitchy", "Big boned")));                                     // round 9: no trait limit
        assertNotNull(Data.checkCustom(List.of("DEC2", "Highlander", "Mild FAAH")));                      // load +24 (11-17: the finer scale, at most +20)
        assertNull(Data.checkCustom(List.of("DEC2", "Highlander", "Mild FAAH", "Myopic")));               // a drawback pays back: +16
        assertNull(Data.checkCustom(List.of("Power fibres", "Highlander", "Keen nose", "Tall", "Bright")));      // no count of genes (11-17)
        List<String> mine = List.of("Power fibres", "Twitchy", "Highlander");
        assertNull(Data.checkCustom(mine));
        Character c = new Character();
        c.setGenes(mine);
        c.applyTemplate(Data.CUSTOM);
        int[] pts = {4, 5, 6, 5, 5, 6, 9};
        for (int i = 0; i < 7; i++) c.base[i] = pts[i];
        assertNull(c.checkPoints());
        assertEquals(5, c.attr(Rules.S, true));
        assertEquals(5, c.attr(Rules.E, true));
        Character human = of("Human", 5, 5, 5, 5, 5, 6, 9);
        assertEquals(human.initiative() + 10, c.initiative());
        assertTrue(c.has("Twitchy"));
        assertEquals(1.06 * human.hourly(Rules.Stat.FOOD), c.hourly(Rules.Stat.FOOD), 1e-9);             // load +6 (Highlander)
        assertTrue(c.tpl().forbiddenTraits().contains("Kamikaze"));
        // the time of day (Marek 11-16): 08:00 on day 1 at clock 0; Night Person by it
        Character s = of("Scholar", 5, 5, 5, 5, 5, 6, 9);
        assertEquals("Day 1 · 08:00", s.timeText());
        int iDay = s.attr(Rules.I, false);
        Hud.pass(s, 11 * 60);
        assertEquals("Day 1 · 19:00", s.timeText());
        assertTrue(s.night());
        assertEquals(iDay + 2, s.attr(Rules.I, false));                                                // -1 by day, +1 at night
        assertEquals(s.attr(Rules.I, true), human.attr(Rules.I, true) + 1);                           // level-up never reads it
        s.setTimeOfDay(23.5);
        assertEquals("Day 1 · 23:30", s.timeText());
        Hud.pass(s, 60);
        assertEquals("Day 2 · 00:30", s.timeText());
    }

    @Test
    void round7() {
        // the basic templates with the trait genes (03c §6 and §8, Marek 11-16: "please update the starting templates with the new trait genes")
        for (Data.Template t : Data.TEMPLATES.values()) {
            if (!t.genome()) continue;
            Set<String> slots = new HashSet<>();
            for (Data.Gene g : t.genes()) assertTrue(slots.add(g.slot()), t.name() + ": two genes in " + g.slot());
        }
        Character human = of("Human", 5, 5, 5, 5, 5, 6, 9);
        Character brawler = of("Brawler", 5, 5, 6, 5, 5, 6, 8);              // S +1, E −1: the same attributes as the human's
        assertEquals(human.apMax() - 1, brawler.apMax());                     // Thick-Skinned: AP −1
        assertEquals(2, brawler.dt());                                         // and DT +2
        Character watch = of("Watchman", 5, 5, 5, 5, 5, 6, 9);
        assertEquals(human.initiative() + 10, watch.initiative());             // Twitchy: Initiative +10
        assertEquals(-10, watch.other("to_hit"), 1e-9);
        Character lab = of("Laborer", 4, 5, 4, 5, 6, 7, 9);
        assertNull(lab.checkPoints());
        assertEquals(2 * human.hourly(Rules.Stat.FOOD), lab.hourly(Rules.Stat.FOOD), 1e-9);   // Glutton: hunger and thirst x2
        assertEquals(2 * human.hourly(Rules.Stat.WATER), lab.hourly(Rules.Stat.WATER), 1e-9);
        Character sc = of("Scout", 5, 5, 5, 6, 5, 6, 8);
        assertEquals(0.5 * human.hourly(Rules.Stat.WATER), sc.hourly(Rules.Stat.WATER), 1e-9); // Weird Loner: x1/2 (food x0.5 x the load's 1.1)
        assertEquals(20, Data.TEMPLATES.get("Scout").skills().get("Survival"), 1e-9);
        assertEquals(human.hpMax() + 5, of("Golden Blood", 5, 5, 5, 5, 5, 6, 9).hpMax());      // Medical Marvel (its gene counts as the trait): HP +5
        assertEquals(human.hpMax(), of("Survivor", 5, 5, 5, 5, 5, 6, 9).hpMax());               // round 8: the Survivor without it
        assertTrue(of("Golden Blood", 5, 5, 5, 5, 5, 6, 9).has("Medical Marvel"));
        assertTrue(Data.TEMPLATES.get("Laborer").forbiddenTraits().contains("Brittle Bones")); // its Frame holds Dense bones
        assertTrue(Data.TEMPLATES.get("Golden Blood").forbiddenTraits().contains("Medical Marvel"));
        assertTrue(Data.TEMPLATES.get("Survivor").forbiddenTraits().contains("Garrakg Returnz"));          // its Immunity slot
        assertTrue(Data.TEMPLATES.get("Orc").forbiddenTraits().contains("Bruiser"));           // Double muscle fills the Muscle slot
        assertFalse(Data.TEMPLATES.get("Human").forbiddenTraits().contains("Brittle Bones"));
    }

    @Test
    void round4() {
        // the basic gene templates (03c §6): zero-sum shifts, about the baseline's strength
        Character st = of("Scout", 5, 5, 5, 5, 5, 6, 9);
        assertNull(st.checkPoints());
        assertEquals(6, st.attr(Rules.E, true));
        assertEquals(4, st.attr(Rules.S, true));
        assertEquals(1.2, of("Scholar", 5, 5, 5, 5, 5, 6, 9).painGain(), 1e-9);
        assertEquals(-0.75, of("Watchman", 5, 5, 5, 5, 5, 6, 9).hourly(Rules.Stat.SLEEP), 1e-9);
        // zero-sum shifts but for the trait genes (round 7): Glutton's E +2 (Laborer), Weird Loner's C −1 (Scout)
        Map<String, Integer> traitShift = Map.of("Laborer", 2, "Scout", -1);
        for (Data.Template t : Data.TEMPLATES.values())
            if (t.genome()) { int s = 0; for (int m : t.mods()) s += m; assertEquals(traitShift.getOrDefault(t.name(), 0), s, t.name()); }
        assertTrue(Data.TEMPLATES.get("Android").premium());                     // round 5: a CoS template
        // 8 hours of rest without bleeding heal the healing rate, also from −Endurance (Marek 11-16)
        Character c = of("Human", 5, 5, 5, 5, 5, 6, 9);
        c.hp = Rules.deathFloor(c.attrs(false));
        c.sleeping = true;
        for (int i = 0; i < 7; i++) Hud.hour(c);
        assertEquals(-5, c.hp);
        Hud.hour(c);
        assertEquals(-5 + c.healingRate(), c.hp);
        c.bleeding = 2;
        for (int i = 0; i < 8; i++) Hud.hour(c);
        assertEquals(-5 + c.healingRate(), c.hp);                               // bleeding: no healing
        // clinical death on GAME time only (Marek 11-16: "only DM changes it"): the GM's minutes, CPR, the revival
        Character d = of("Human", 5, 5, 5, 5, 5, 6, 9);
        Hud.hit(d, d.hp + 6, "normal");                                         // below −5
        assertTrue(d.inArrest());
        assertEquals(60 * 0.2, d.revivalChance(120, 0.2), 1e-6);                // no game time has passed: no loss
        Hud.pass(d, 2);
        assertEquals(60 * 0.2 * (1 - 0.07 * 2), d.revivalChance(120, 0.2), 1e-6);
        Character e = Store.fromJson(Store.toJson(d));
        e.cprFrom = e.arrestAt;                                                 // CPR from the start: ~33 minutes
        assertNull(Hud.pass(e, 13));
        assertNotNull(Hud.pass(d, 13));                                         // untouched: final death at ~14 minutes
        assertTrue(d.dead);
        d.dead = false;
        d.revived();
        assertFalse(d.inArrest());
        assertEquals(-5, d.hp);
        // a synthetic body: power and memory
        Character a = of("Android", 5, 5, 5, 5, 5, 6, 9);
        assertEquals("Power", a.statName(Rules.Stat.FOOD));
        assertEquals("Minor Memory Clutter", a.label("Minor Sleep Deprivation"));
        assertEquals(0, a.hourly(Rules.Stat.WATER), 1e-9);
        assertEquals(-1, a.hourly(Rules.Stat.FOOD), 1e-9);
        // Rad-X as Fallout 2: +50 for 24 h, then +25 for 48 h
        Character r = of("Human", 5, 5, 5, 5, 5, 6, 9);
        double base = r.resistances()[1];
        r.dose(Data.CHEMS.get("Rad-X"), fixed(0.99, 1));
        assertEquals(base + 50, r.resistances()[1], 1e-9);
        for (int i = 0; i < 24; i++) Hud.hour(r);
        assertEquals(25, r.other("rad_res"), 1e-9);                             // (thirst after 24 h lowers E: read the chem's own value)
    }

    @Test
    void round6() {
        // a 0.9.0 save: "arrestAt": 0 and full HP is no clinical death (Marek 11-16: "why am I in clinical death with full HP?")
        Character c = Store.fromJson("{\"name\":\"Old\",\"template\":\"Human\",\"base\":[5,6,6,5,7,7,4],\"hp\":30,\"arrestAt\":0,\"cprFrom\":0,"
                + "\"effects\":[{\"name\":\"Eagle Eye implant (example)\",\"permanent\":true,\"mods\":[0,1,0,0,0,0,0],\"hoursLeft\":0}]}");
        assertFalse(c.inArrest());
        c.migrate();
        assertEquals(-1, c.arrestAt, 1e-9);
        assertEquals("implant", c.effects.get(0).source());                     // the example implant in a slot
        assertEquals("implant:eyes#1", c.effects.get(0).slot());
        // a missing limb
        Character m = of("Human", 5, 5, 5, 5, 5, 6, 9);
        assertNull(m.fitLimb("right_arm", "missing"));
        assertEquals(-50, m.armToHit(false));
        assertNull(m.fitLimb("left_leg", "missing"));
        assertEquals(1, m.missingLegs());
        // first aid: the bleeding first, then a dressing (Medicine 20)
        Character t = of("Human", 5, 5, 5, 5, 5, 6, 9);
        t.hp = 3;
        t.bleeding = 2;
        Character medic = of("Human", 5, 9, 5, 5, 9, 4, 3);                     // Medicine 18 + 10 points
        medic.points[Rules.skillIndex("Medicine")] = 10;
        assertTrue(Menus.firstAid(medic, t, "his").contains("bleeding stops"));
        assertTrue(Menus.firstAid(medic, t, "his").contains("dressed"));
        assertEquals(1, t.other("healing_rate"), 1e-9);
        // an implant's numbers by tier
        assertEquals("+4 Perception; +4 against blinding (Genkar's)", Part3.numbersAt(Data.IMPLANTS.get("Cyber Eyes"), 4));
        assertEquals("-3 Perception; a blind eye sees shapes", Part3.numbersAt(Data.IMPLANTS.get("Cyber Eyes"), 1));
        assertEquals(0, Data.TEMPLATES.get("Android").attributePoints() - Rules.ATTRIBUTE_POINTS);   // round 6: no extra point
        assertEquals("10", Data.TEMPLATES.get("Android").cosPoints());
    }

    @Test
    void oldSavesAndSlots() {
        // an effect saved before 0.8.0 (the four-field record) still loads and works
        Character c = Store.fromJson("{\"name\":\"Old\",\"template\":\"Human\",\"base\":[5,6,6,5,7,7,4],"
                + "\"effects\":[{\"name\":\"Eagle Eye implant (example)\",\"permanent\":true,\"mods\":[0,1,0,0,0,0,0],\"hoursLeft\":0}]}");
        assertEquals(7, c.attr(Rules.P, true));
        assertTrue(c.unlocked.isEmpty());
        // one slot, one effect; a round trip keeps the new fields
        Character d = of("Human", 5, 5, 5, 5, 5, 6, 9);
        d.fit(Data.IMPLANTS.get("Neural Link"), 4, null);
        d.fit(Data.IMPLANTS.get("Reflex Chip"), 4, null);
        d.dose(Data.CHEMS.get("Alcohol"), fixed(0.01, 1));          // 0.01 < 5 %: addicted
        Character e = Store.fromJson(Store.toJson(d));
        assertEquals(d.skill("Medicine"), e.skill("Medicine"), 1e-9);
        assertEquals("Alcohol", e.inSlot("chem:Alcohol").name());
        assertTrue(e.addictions.contains("Alcohol"));
        e.dose(Data.CHEMS.get("Alcohol"), fixed(0.99, 1));
        assertEquals(1, e.effects.stream().filter(x -> x.slot().equals("chem:Alcohol")).count());
    }

    @Test
    void round11TraitsAsAListWithoutTheGenes() {
        // 11-17: "the trait section has no list and shows traits that have already become genes"
        assertTrue(Data.TRAITS.size() > 40, "every trait of 02c §1 still a trait");
        for (Data.Gene g : Data.GENES.values())
            if (g.rule() != null && !g.rule().isEmpty()) assertFalse(Data.TRAITS.containsKey(g.rule()), g.rule() + " is the gene " + g.name());
        assertFalse(Data.TRAITS.containsKey("Brittle Bones"));
        assertFalse(Data.TRAITS.containsKey("Medical Marvel"));
        assertEquals("in", Data.TRAITS.get("Tinkerer").status());                     // part 8 in the plugin (0.51.0)
        assertEquals("in", Data.TRAITS.get("Perfectionist").status());                // production: offered since part 7's round 4
        assertEquals("cos", Data.TRAITS.get("Fast Shot").status());
        Character d = of("Human", 5, 5, 5, 5, 5, 6, 9);
        assertTrue(Forms.offeredTraits(d).stream().noneMatch(t -> t.name().equals("Fast Shot")));
        assertTrue(Forms.offeredTraits(d).stream().anyMatch(t -> t.name().equals("Tinkerer")));     // part 8 in the plugin (0.51.0)
        d.unlocked.add("Fast Shot");
        assertTrue(Forms.offeredTraits(d).stream().anyMatch(t -> t.name().equals("Fast Shot")));
        // the numbers become a permanent effect: Bruiser +2 S, -2 AP; Gifted +1 everything, -10 every skill
        int s0 = d.attr(Rules.S, true), ap0 = d.apMax();
        double guns0 = d.skill("Guns");
        d.traits.add(new Character.Feat("Bruiser", "trait (F1, F2)", ""));
        d.traits.add(new Character.Feat("Gifted", "trait (F)", ""));
        Forms.applyTraits(d);
        assertEquals(s0 + 3, d.attr(Rules.S, true));
        assertTrue(d.apMax() <= ap0 - 1, "Bruiser's -2 AP (Gifted's +1 A may give some back)");
        assertTrue(d.skill("Guns") < guns0, "Gifted's -10 to every skill outweighs its +1 A and +1 P (Guns = 2 A + P)");
        assertEquals("+2 Strength, -2 AP", Forms.traitNumbers(Data.TRAITS.get("Bruiser")));
    }

    @Test
    void implantTiers() {
        // 03b §3b (RULED 11-18): each tier its own numbers; the Pain Editor's T IV = no Pain, no side effects
        Character c = of("Human", 5, 5, 5, 5, 5, 6, 9);
        assertNull(c.fit(Data.IMPLANTS.get("Neural Link"), 1, null));
        assertNull(c.fit(Data.IMPLANTS.get("Pain Editor"), 4, null));
        assertEquals(0, c.painGain(), 1e-9);
        assertEquals("Neural Link · Tier I", c.inSlot("implant:brain#1").name());
        // the Mood Stimulator's T IV halves the addiction chance
        Character m = of("Human", 5, 5, 5, 5, 5, 6, 9);
        m.fit(Data.IMPLANTS.get("Neural Link"), 4, null);
        assertNull(m.fit(Data.IMPLANTS.get("Mood Stimulator"), 4, null));
        assertEquals(-0.5, m.other("addiction"), 1e-9);
        // the Defibrillator: T IV, the window ×1.5 and three shocks at clinical death (the heart slot: not beside an Artificial Heart)
        Character h = of("Human", 5, 5, 5, 5, 5, 6, 9);
        double w = h.deathWindow();
        assertNull(h.fit(Data.IMPLANTS.get("Defibrillator"), 4, null));
        assertNotNull(h.fit(Data.IMPLANTS.get("Artificial Heart"), 4, null));
        assertEquals(w * 1.5, h.deathWindow(), 1e-9);
        h.hp = Rules.deathFloor(h.attrs(false)) - 1;
        h.arrestAt = h.now();
        String log = h.autoDefibrillate(fixed(0.5, 10));                       // a roll of 10 under the shock's 50 %
        assertTrue(log.contains("beats again"), log);
        assertFalse(h.inArrest());
        Character g = of("Human", 5, 5, 5, 5, 5, 6, 9);
        g.fit(Data.IMPLANTS.get("Defibrillator"), 2, null);
        g.hp = Rules.deathFloor(g.attrs(false)) - 1;
        g.arrestAt = g.now();
        assertTrue(g.autoDefibrillate(fixed(0.5, 99)).contains("no rhythm"));  // one shock, rolled 99
        assertTrue(g.inArrest());
        // fashion implants: their own slot, six of them
        assertNull(g.fit(Data.IMPLANTS.get("RFID Chip"), 2, null));
        assertNotNull(g.fit(Data.IMPLANTS.get("RFID Chip"), 4, null));         // only Tier II exists
        // a save before 0.17.0: the Memory Chip is gone into the Link, a Tier II Reflex Chip becomes Tier III
        Character o = Store.fromJson("{\"name\":\"Old\",\"template\":\"Human\",\"base\":[5,6,6,5,7,7,4],\"effects\":["
                + "{\"name\":\"Neural Link · Tier IV\",\"source\":\"implant\",\"slot\":\"implant:brain#1\",\"permanent\":true,\"mods\":[0,0,0,0,0,0,0],\"other\":{\"skill:Computer Science\":10},\"hoursLeft\":0,\"condition\":80},"
                + "{\"name\":\"Memory Chip (Guns) · Tier IV\",\"source\":\"implant\",\"slot\":\"implant:brain#2\",\"permanent\":true,\"mods\":[0,0,0,0,0,0,0],\"other\":{\"skill:Guns\":20},\"hoursLeft\":0,\"condition\":100},"
                + "{\"name\":\"Reflex Chip · Tier II\",\"source\":\"implant\",\"slot\":\"implant:brain#3\",\"permanent\":true,\"mods\":[0,0,0,0,0,0,0],\"other\":{\"sequence\":2},\"hoursLeft\":0,\"condition\":100}]}");
        o.migrate();
        assertNull(o.inSlot("implant:brain#2"));
        assertEquals("Neural Link · Tier IV", o.inSlot("implant:brain#1").name());
        assertEquals(80, o.inSlot("implant:brain#1").condition(), 1e-9);
        assertEquals(1, o.inSlot("implant:brain#1").mods()[Rules.I]);           // T IV: I +1
        assertEquals("Reflex Chip · Tier III", o.inSlot("implant:brain#3").name());
    }

    @Test
    void therapies() {
        // 03c §5 (DRAFT 2 11-18): every CoS template is its genes; the generator checks the sums, here the counts and the coldvein's sleep
        assertEquals(13, Data.TEMPLATES.get("Vampire").genes().size());
        assertEquals(0.75, Data.TEMPLATES.get("Coldvein").sleepFactor(), 1e-9);
        assertTrue(Data.TEMPLATES.get("Orc").forbiddenTraits().contains("Medical Marvel"));          // its Blood slot holds the Bull heart
        // 03d (RULED 11-17): a T4 bone gene into an empty slot: the course first (two months), then HP +5 for 4 years × 2.5
        Character c = of("Human", 5, 5, 5, 5, 5, 6, 9);
        int hp = c.hpMax();
        String log = c.giveTherapy(Data.GENES.get("Bone lattice"), 100, 1.0, false, true, fixed(0.5, 10));   // 100 × 1 × T4 0.6 = 60 %, rolled 10
        assertTrue(log.contains("laid on an empty slot"), log);
        assertEquals(hp, c.hpMax());
        // kept fed, watered and asleep: two months of the stages' penalties would lower HP max too (04 §3.10, the current attributes)
        c.baseFood = c.baseWater = c.sleeping = true;
        for (int k = 0; k < 1440; k++) Hud.hour(c);
        assertEquals(hp + 5, c.hpMax());
        assertEquals(4 * 2.5 * 8760, c.therapies.get(0).hours, 1e-9);
        c.therapies.get(0).elapsed = 0.8 * c.therapies.get(0).hours;           // its last quarter: half its numbers
        assertEquals(hp + 2, c.hpMax());
        // the elf's Tapetum covered by Night eyes: night vision, P unchanged (+1 for +1)
        Character e = of("Elf", 5, 5, 5, 5, 5, 5, 5);
        int p = e.attr(Rules.P, false);
        assertEquals("dark halved", e.tpl().senses());
        e.giveTherapy(Data.GENES.get("Night eyes"), 100, 1.5, false, false, fixed(0.5, 1));
        e.therapies.get(0).courseLeft = 0;
        assertEquals("night vision", e.tpl().senses());
        assertEquals(p, e.attr(Rules.P, false));
        assertTrue(e.giveTherapy(Data.GENES.get("Cat frame"), 100, 1, false, true, fixed(0.5, 1)).startsWith("!"));   // a shape gene: puberty only
        assertTrue(e.giveTherapy(Data.GENES.get("Claws"), 100, 1, true, true, fixed(0.5, 1)).startsWith("!"));        // an organ: a graft
        // the vampire's Blood slot covered (Mäntyranta over Protoporphyria): the sun no longer burns, E +1
        Character v = of("Vampire", 5, 5, 5, 5, 5, 5, 5);
        int en = v.attr(Rules.E, true);
        assertEquals("protoporphyria", v.tpl().sun());
        v.giveTherapy(Data.GENES.get("Mäntyranta"), 100, 1, false, false, fixed(0.5, 1));
        v.therapies.get(0).courseLeft = 0;
        assertEquals("", v.tpl().sun());
        assertEquals(en + 1, v.attr(Rules.E, true));
        // the conflict roll over a therapy (03d §4): FAAH-OUT over Mild FAAH, ease +2 (one gene) −1 (nerves) = 1; d6 6 + 90 / 30 + 1 = 10: a blend
        Character f = of("Human", 5, 5, 5, 5, 5, 6, 9);
        int iq = f.attr(Rules.I, false);
        f.giveTherapy(Data.GENES.get("Mild FAAH"), 90, 1, false, false, fixed(0.5, 1));
        String r = f.giveTherapy(Data.GENES.get("FAAH-OUT"), 90, 1, false, true, fixed(0.5, 6));
        assertTrue(r.contains("a BLEND"), r);
        assertEquals(1, f.therapies.size());
        f.therapies.get(0).courseLeft = 0;
        assertEquals(iq, f.attr(Rules.I, false));                              // its upside only: the memory lapses (I −1) dropped
        assertEquals(0.5, f.painGain(), 1e-9);
        // radiation (03d §5): stage 3 twists at 30 % (rolled 10: only its drawback); stage 1 switches off at 10 % (rolled 5)
        Character q = of("Human", 5, 5, 5, 5, 5, 6, 9);
        int s = q.attr(Rules.S, true);
        q.giveTherapy(Data.GENES.get("Lean muscle"), 90, 1, false, false, fixed(0.5, 1));
        q.therapies.get(0).courseLeft = 0;
        assertEquals(s + 1, q.attr(Rules.S, true));
        q.radiationRolls(440, 460, fixed(0.5, 10));
        assertEquals("twisted", q.therapies.get(0).state);
        assertEquals(s, q.attr(Rules.S, true));
        q.radiationRolls(140, 160, fixed(0.5, 5));
        assertTrue(q.therapies.isEmpty());
        // the shop's Witcher set for an adult (03d §9): one roll, 15 years, no Light frame (puberty only)
        Character w = of("Human", 5, 5, 5, 5, 5, 6, 9);
        int pw = w.attr(Rules.P, true), cw = w.attr(Rules.C, true);
        double poison = w.resistances()[2];
        String ws = w.giveSet(Data.SETS.get("Witcher"), 120, 1.5, false, true, fixed(0.5, 10));
        assertTrue(ws.contains("not given: Light frame (puberty only)"), ws);
        assertEquals(7, w.therapies.size());
        assertEquals(15 * 8760, w.therapies.get(0).hours, 1e-9);
        for (Character.Therapy th : w.therapies) th.courseLeft = 0;
        w.effects.removeIf(x -> x.source().equals("therapy"));                // the course's fever (S -1, E -1) over
        assertEquals(pw + 2, w.attr(Rules.P, true));
        assertEquals(cw - 3, w.attr(Rules.C, true));
        assertEquals(poison + 50 + (20 + 2) / 4.0, w.resistances()[2], 1e-9);   // +50 %, and Survival +20 (Wild instinct) + 2 (P) / 4
        // the gene pump (03d §10): a fed pump holds its therapy's clock
        Character g = of("Human", 5, 5, 5, 5, 5, 6, 9);
        assertNull(g.fit(Data.IMPLANTS.get("Gene Pump"), 3, null));
        g.giveTherapy(Data.GENES.get("Lean muscle"), 90, 1, false, false, fixed(0.5, 1));
        Character.Therapy th = g.therapies.get(0);
        th.courseLeft = 0;
        th.pumped = true;
        g.pumpRefilled = g.now();
        for (int k = 0; k < 24; k++) Hud.hour(g);
        assertEquals(0, th.elapsed, 1e-9);
        th.pumped = false;
        for (int k = 0; k < 24; k++) Hud.hour(g);
        assertEquals(24, th.elapsed, 1e-9);
    }
}
