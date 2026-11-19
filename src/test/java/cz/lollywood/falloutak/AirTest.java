package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** 03b §3c (RULED 11-18, rounds 19-21): Oxygen by rounds, the gases, the ears, the heart's causes; part 4's limit gates. */
class AirTest {
    static Character human() { return Part3Test.of("Human", 5, 5, 5, 5, 5, 5, 10); }

    static double oxygen(Character c) { return c.survival.get(Rules.Stat.OXYGEN); }

    /** Trained lungs on top of the held breath (round 24): + Athletics / 10 + (E − 5), from the permanent values. */
    static double bonus(Character c) { return c.skills(true)[Rules.skillIndex("Athletics")] / 10 + c.attrs(true)[Rules.E] - 5; }

    @Test
    void oxygenMaxima() {
        assertEquals(13, human().max(Rules.Stat.OXYGEN), 1e-9);                          // a minute + Athletics 30 / 10, E 5: 78 s
        Character rev = Part3Test.of("Revenant", 5, 5, 5, 5, 5, 5, 5), nec = Part3Test.of("Necroat", 5, 5, 5, 5, 5, 5, 5);
        assertEquals(100 + bonus(rev), rev.max(Rules.Stat.OXYGEN), 1e-9);    // Anoxia, 10 minutes
        assertEquals(50 + bonus(nec), nec.max(Rules.Stat.OXYGEN), 1e-9);     // Diving reflex
        Character lf = human();
        assertNull(lf.fit(Data.IMPLANTS.get("Lung Filter"), 3, null));
        assertEquals(33, lf.max(Rules.Stat.OXYGEN), 1e-9);
        Character lf4 = human();
        assertNull(lf4.fit(Data.IMPLANTS.get("Lung Filter"), 4, null));
        assertEquals(53, lf4.max(Rules.Stat.OXYGEN), 1e-9);
        // Athletics and Endurance (round 24): a trained swimmer, a frail one
        assertEquals(22, 10 + Rules.breathBonus(100, 7), 1e-9);
        assertEquals(8.5, 10 + Rules.breathBonus(15, 2), 1e-9);
        assertEquals(6.6, Part3Test.of("Human", 1, 10, 1, 10, 10, 1, 7).max(Rules.Stat.OXYGEN), 1e-9);   // the least a human holds
    }

    @Test
    void drowningByRounds() {
        Character c = human();
        int p0 = c.attrs(false)[Rules.P], i0 = c.attrs(false)[Rules.I], a0 = c.attrs(false)[Rules.A];
        c.air = false;
        for (int k = 0; k < 7; k++) Hud.round(c, new Random(1));
        assertEquals(6, oxygen(c), 1e-9);                                       // −1 a round: 6 of 13, under 50 %
        assertEquals(p0 - 1, c.attrs(false)[Rules.P]);                          // under 50 %: P −1, I −1
        assertEquals(i0 - 1, c.attrs(false)[Rules.I]);
        assertEquals(a0, c.attrs(false)[Rules.A]);
        for (int k = 0; k < 3; k++) Hud.round(c, new Random(1));
        assertEquals(a0 - 2, c.attrs(false)[Rules.A]);                          // 3 of 13, under 25 %: A −2, I −2
        assertEquals(i0 - 2, c.attrs(false)[Rules.I]);
        // unconscious at 0; the heart stops after E (5) minutes at 0, HP kept
        Character d = human();
        d.air = false;
        Hud.pass(d, 1.3);
        assertTrue(d.unconscious());
        assertFalse(d.inArrest());
        Hud.pass(d, 4.9);                                                       // Oxygen 0 at 1:12; 4.9 minutes at 0 by 6:06
        assertFalse(d.inArrest());
        Hud.pass(d, 0.1);
        assertTrue(d.inArrest(), "an arrest after E minutes at Oxygen 0");
        assertEquals(d.hpMax(), d.hp);                                          // HP kept
        assertTrue(d.arrestCause.startsWith("Oxygen 0"));
        assertNotNull(d.arrestBlock());                                         // no air: no revival yet
        d.air = true;
        assertNull(d.arrestBlock());
        d.revived();
        Hud.pass(d, 0.2);                                                       // +5 a round in clean air
        assertEquals(10, oxygen(d), 1e-9);
    }

    @Test
    void heldBreathAndBadAir() {
        Character c = human();
        c.gas = "chlorine";
        c.holdingBreath = true;
        Hud.round(c, new Random(1));
        assertEquals(12, oxygen(c), 1e-9);
        assertEquals(0, c.survival.get(Rules.Stat.POISON), 1e-9);              // the gas stays out
        for (int k = 0; k < 12; k++) Hud.round(c, new Random(1));
        assertFalse(c.holdingBreath);                                           // nobody holds it to the end: a gasp
        Character b = human();
        b.gas = "bad air";
        b.survival.put(Rules.Stat.OXYGEN, 2.0);
        Hud.round(b, new Random(1));
        Hud.round(b, new Random(1));
        assertEquals(6.5, oxygen(b), 1e-9);                                     // refills only to half
        int p0 = b.attrs(false)[Rules.P];
        Hud.pass(b, 60);
        assertEquals(p0 - 1, b.attrs(false)[Rules.P]);                          // after an hour P −1 (and I −1)
    }

    @Test
    void smokeMaskAndFilters() {
        Character c = human();
        double res = c.resistances()[2] / 100;
        c.gas = "smoke";
        Hud.round(c, new Random(1));
        assertEquals(12, oxygen(c), 1e-9);                                      // smoke: Oxygen −1
        // smoke +2 and its CO +3, × (1 − the poison resistance), which the smoke's own P −1 and the new Poison stage lower a little
        assertEquals((2 + 3) * (1 - res), c.survival.get(Rules.Stat.POISON), 0.05);
        assertTrue(c.coUntil > c.now());
        assertEquals(-1, c.gasPenalty()[0]);
        assertEquals(-10, c.gasPenalty()[1]);
        // a gas mask with a gas filter keeps the smoke out but not its CO; the CO filter does
        Character m = human();
        m.gas = "smoke";
        m.wearing.add("Gas mask");
        m.maskFilters.add("gas");
        m.survival.put(Rules.Stat.OXYGEN, 5.0);
        Hud.round(m, new Random(1));
        assertEquals(10, oxygen(m), 1e-9);                                      // breathes: +5
        assertEquals(3 * (1 - res), m.survival.get(Rules.Stat.POISON), 1e-9);
        assertEquals(0, m.gasPenalty()[1]);
        m.maskFilters.add("co");
        Hud.round(m, new Random(1));
        assertEquals(3 * (1 - res), m.survival.get(Rules.Stat.POISON), 1e-9);
        // strength III triples the row
        Character s = human();
        s.gas = "chlorine";
        s.gasStrength = 3;
        assertEquals(-6, s.gasPenalty()[0]);
    }

    @Test
    void lungFilterAndGenes() {
        Character t3 = human();
        assertNull(t3.fit(Data.IMPLANTS.get("Lung Filter"), 3, null));
        Rules.Gas co = Rules.GASES.get("carbon monoxide"), cl = Rules.GASES.get("chlorine");
        assertEquals(1, t3.gasFactor(co), 1e-9);                               // T III: not CO
        assertEquals(0.5, t3.gasFactor(cl), 1e-9);
        Character t4 = human();
        assertNull(t4.fit(Data.IMPLANTS.get("Lung Filter"), 4, null));
        assertEquals(0, t4.gasFactor(co), 1e-9);                               // T IV: harmless, CO too
        t4.gas = "carbon monoxide";
        Hud.round(t4, new Random(1));
        assertEquals(0, t4.survival.get(Rules.Stat.POISON), 1e-9);
        assertEquals(1, t4.gasFactor(Rules.GASES.get("bad air")), 1e-9);        // never bad air
        Character rev = Part3Test.of("Revenant", 5, 5, 5, 5, 5, 5, 5);
        assertEquals(0.2, rev.gasFactor(cl), 1e-9);                             // Anoxia: every gas x0.2
        Character hl = new Character();
        hl.setGenes(java.util.List.of("Highlander"));
        hl.applyTemplate(Data.CUSTOM);
        assertEquals(0.75, hl.gasFactor(co), 1e-9);                             // the Blood genes: CO and sewer gas only
        assertEquals(1, hl.gasFactor(cl), 1e-9);
    }

    @Test
    void riotGasLingers() {
        Character c = human();
        int ap0 = c.apMax();
        c.gas = "riot gas";
        assertArrayEquals(new int[]{-3, -20, -2}, c.gasPenalty());
        assertEquals(ap0 - 2, c.apMax());
        Hud.round(c, new Random(1));
        c.gas = "";
        assertArrayEquals(new int[]{-3, -20, -2}, c.gasPenalty());             // 10 minutes after
        Hud.pass(c, 10.5);
        assertArrayEquals(new int[]{0, 0, 0}, c.gasPenalty());
    }

    @Test
    void theHeartsCauses() {
        Character b = human();
        b.survival.put(Rules.Stat.BLOOD, 0.0);
        assertTrue(b.checkArrest(new Random(1)).startsWith("CARDIAC ARREST (Blood 0)"));
        assertTrue(b.inArrest());
        assertEquals(b.hpMaxBase(), b.hp);                                       // HP kept (Deadly Anemia lowers only the maximum, by 8)
        assertEquals(b.hpMaxBase() - 8, b.hpMax());
        assertNotNull(b.arrestBlock());
        // the cold's end; the window doubles in the cold
        Character k = human();
        double w = k.deathWindow();
        k.survival.put(Rules.Stat.HYPOTHERMIA, k.max(Rules.Stat.HYPOTHERMIA));
        assertNotNull(k.checkArrest(new Random(1)));
        assertEquals(2 * w, k.deathWindow(), 1e-9);
        // an Artificial Heart ignores the rhythm causes, never Blood 0
        Character h = human();
        assertNull(h.fit(Data.IMPLANTS.get("Artificial Heart"), 3, null));
        assertTrue(h.hasPump());
        assertTrue(h.stopHeart("electricity", true, new Random(1)).contains("pumps on"));
        assertFalse(h.inArrest());
        h.survival.put(Rules.Stat.HYPOTHERMIA, h.max(Rules.Stat.HYPOTHERMIA));
        assertNull(h.checkArrest(new Random(1)));
        assertTrue(h.unconscious());
        h.survival.put(Rules.Stat.BLOOD, 0.0);
        assertNotNull(h.checkArrest(new Random(1)));
        assertTrue(h.inArrest());
        // an electric hazard rolls only from a 10-point hit: d10 at most E holds
        Character e = human();
        assertFalse(e.electricHazard(9, Part3Test.fixed(0, 10)).contains("Endurance roll"));
        Character e2 = human();
        assertTrue(e2.electricHazard(10, Part3Test.fixed(0, 10)).contains("CARDIAC ARREST"));
        Character e3 = human();
        assertTrue(e3.electricHazard(10, Part3Test.fixed(0, 3)).contains("the heart holds"));
        // a blunt weapon's highest crit to the torso stops the heart instead of killing; an electric crit any degree
        int[] a = {5, 5, 5, 5, 5, 5, 5};
        Combat.CritEffects blunt = Combat.critEffects(Rules.Part.TORSO, 6, a, "blunt");
        assertTrue(blunt.heartStops());
        assertFalse(blunt.dies());
        assertTrue(Combat.critEffects(Rules.Part.TORSO, 2, a, "electric").heartStops());
        assertFalse(Combat.critEffects(Rules.Part.TORSO, 6, a, "normal").heartStops());
        assertFalse(Combat.critEffects(Rules.Part.HEAD, 6, a, "blunt").heartStops());
    }

    @Test
    void enduranceZeroAndTheLimitGates() {
        // E 3: Minor Anemia (E −1) and Advanced Dehydration (E −2) cannot take it below 1 (part 4 §3.5)
        Character c = Part3Test.of("Human", 5, 5, 3, 5, 5, 7, 10);
        c.survival.put(Rules.Stat.BLOOD, 0.7 * c.max(Rules.Stat.BLOOD));
        c.survival.put(Rules.Stat.WATER, 0.5 * c.max(Rules.Stat.WATER));
        assertEquals(1, c.attrs(false)[Rules.E]);
        assertNull(c.checkArrest(new Random(1)));
        // Critical Dehydration (E −3) can: E 0 stops the heart
        c.survival.put(Rules.Stat.WATER, 0.3 * c.max(Rules.Stat.WATER));
        assertTrue(c.attrs(false)[Rules.E] <= 0);
        assertTrue(c.checkArrest(new Random(1)).contains("Endurance 0"));
    }

    @Test
    void zonesAndTheHazardSuit() throws Exception {
        // a box takes both corner blocks in; a ball by its radius; a countdown removes a spent zone
        Zones zs = new Zones(java.nio.file.Files.createTempDirectory("zones"));
        Zones.Zone box = new Zones.Zone();
        box.world = "w"; box.gas = "chlorine"; box.x1 = 0; box.x2 = 10; box.y1 = 60; box.y2 = 64; box.z1 = 0; box.z2 = 10;
        Zones.Zone cloud = new Zones.Zone();
        cloud.world = "w"; cloud.gas = "riot gas"; cloud.ball = true; cloud.x1 = 20; cloud.y1 = 61; cloud.z1 = 20; cloud.r = 3; cloud.rounds = 5;
        zs.list.add(box);
        zs.list.add(cloud);
        assertTrue(box.contains("w", 9.9, 63.5, 0));
        assertFalse(box.contains("w", 10.0, 63.5, 0));
        assertFalse(box.contains("other", 5, 62, 5));
        assertTrue(cloud.contains("w", 22.9, 61, 20));
        assertFalse(cloud.contains("w", 23.1, 61, 20));
        zs.rounds(3);                                                           // the GM's Pass time: the scene's round 3
        assertEquals(2, zs.list.size());
        // two players in rounds side by side (round 24): each reaches round 4, the cloud ages once
        long ra = zs.round, rb = zs.round;
        zs.reach(++ra);
        zs.reach(++rb);
        assertEquals(1, cloud.rounds);
        assertEquals(2, zs.list.size());
        zs.reach(++rb);                                                         // one gets ahead: round 5
        assertEquals(1, zs.list.size());                                        // the cloud is spent; the hall lasts
        zs.reach(++ra);                                                         // the other catches up: nothing more ages
        // a character in a zone breathes the zone's gas at its strength, before his own setting
        Character c = human();
        c.gas = "smoke";
        c.zoneGas = "chlorine";
        c.zoneStrength = 2;
        assertEquals("chlorine", c.gasHere().name());
        assertEquals(-4, c.gasPenalty()[0]);
        c.zoneGas = "";
        assertEquals("smoke", c.gasHere().name());
        // acid fog burns bare skin; a hazard suit keeps it off (Marek 11-18)
        Character a = human();
        a.zoneGas = "acid fog";
        Hud.round(a, Part3Test.fixed(0, 4));
        assertEquals(a.hpMax() - 4, a.hp);
        Character s = human();
        s.zoneGas = "acid fog";
        s.wearing.add("Hazard suit");
        Hud.round(s, Part3Test.fixed(0, 4));
        assertEquals(s.hpMax(), s.hp);
    }

    @Test
    void twoMasksAndWhenRoundsRun() {
        // the small gas mask keeps the breath, not the eyes; the big one both (Marek 11-18: small = an active item, big = like a helmet)
        Character small = human();
        small.zoneGas = "chlorine";
        small.wearing.add("Small gas mask");
        small.maskFilters.add("gas");
        Hud.round(small, new Random(1));
        assertEquals(0, small.survival.get(Rules.Stat.POISON), 1e-9);
        assertEquals(-2, small.gasPenalty()[0]);                               // chlorine's P −2 on bare eyes
        assertTrue(small.envHarm());
        Character big = human();
        big.zoneGas = "chlorine";
        big.wearing.add("Gas mask");
        big.maskFilters.add("gas");
        assertEquals(0, big.gasPenalty()[0]);
        assertFalse(big.envHarm());                                             // nothing gets through: no rounds
        big.zoneGas = "smoke";
        assertTrue(big.envHarm());                                              // the smoke's CO needs its own filter
        big.maskFilters.add("co");
        assertFalse(big.envHarm());
        // the rounds run while the environment hurts: no air, a held breath, a gas that acts each round; not methane, bad air, mercury
        Character c = human();
        assertFalse(c.envHarm());
        c.air = false;
        assertTrue(c.envHarm());
        c.air = true;
        for (String g : new String[]{"methane", "bad air", "mercury vapour"}) { c.zoneGas = g; assertFalse(c.envHarm(), g); }
        c.zoneGas = "";
        c.holdingBreath = true;
        assertTrue(c.envHarm());
    }

    @Test
    void audioImplantTiers() {
        // Marek 11-18: Tier I P −1, Tier II ±0 (no Perception "by ear")
        Character c = human();
        int p0 = c.attrs(false)[Rules.P];
        assertNull(c.fit(Data.IMPLANTS.get("Audio Implant"), 1, null));
        assertEquals(p0 - 1, c.attrs(false)[Rules.P]);
        Character d = human();
        assertNull(d.fit(Data.IMPLANTS.get("Audio Implant"), 2, null));
        assertEquals(p0, d.attrs(false)[Rules.P]);
    }

    @Test
    void theEars() {
        Character c = human();
        int p0 = c.attrs(false)[Rules.P];
        String r = c.blast(1, 2, Part3Test.fixed(0, 1));                       // inside the radius: 50 % an ear, both burst
        assertTrue(r.contains("DEAF"), r);
        assertEquals(p0 - 2, c.attrs(false)[Rules.P]);
        assertTrue(c.lastingEarLoss);
        c.healEars();
        c.ring(2);
        assertEquals(p0 - 1, c.attrs(false)[Rules.P]);                          // ringing P −1
        Character imp = human();
        assertNull(imp.fit(Data.IMPLANTS.get("Audio Implant"), 3, null));
        int pi = imp.attrs(false)[Rules.P];
        assertEquals("", imp.blast(1, 2, Part3Test.fixed(0, 1)));               // T III: never deafened
        assertEquals(pi, imp.attrs(false)[Rules.P]);
        Character far = human();
        assertEquals("", far.blast(7, 2, Part3Test.fixed(0, 1)));               // beyond three times the radius
        Character plugs = human();
        plugs.wearing.add("Earplugs");
        assertEquals(p0 - 1, plugs.attrs(false)[Rules.P]);                      // earplugs: P −1 while worn
        assertTrue(plugs.blast(1, 2, Part3Test.fixed(0, 26)).isEmpty());        // the roll halved: 26 > 25
    }
}
