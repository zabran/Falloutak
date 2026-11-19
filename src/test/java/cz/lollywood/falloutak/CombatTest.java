package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Part 5 against the workbook's own Battle tab example and Fallout 2's code (05-BATTLE.md). */
class CombatTest {
    @Test
    void theSheetsOwnExampleShot() {
        // Battle!H19: Mr. kkt (Guns 50, PE 6, ST 5) at Nájezdník 2 (AC 5, prone) 8 blocks off, the 10mm pistol (Accurate), BODY,
        // bright: the sheet's M22 = 0.65
        Combat.Shot s = new Combat.Shot();
        s.skill = 50; s.blocks = 8; s.perception = 6; s.strength = 5; s.minStrength = 3; s.weaponPerk = "Accurate";
        s.targetAc = 5; s.part = Rules.Part.TORSO; s.targetProne = true; s.range = 25;
        assertEquals(65, Combat.toHit(s).chance());
        // its damage: base 13, 10mm JHP (+0.6, penetration -0.2) against DT 2, DR 20 %: the sheet's R33 = 14.288
        assertEquals(14, Combat.damage(13, 1, 0.6, -0.2, 2, 0.2, false));
    }

    @Test
    void distanceIsFallout2s() {
        assertEquals(0, Combat.distanceTerm(8, 6, "", false));       // 8 - 2 x 4 = 0
        assertEquals(-48, Combat.distanceTerm(20, 6, "", false));    // 12 blocks too far x -4
        assertEquals(20, Combat.distanceTerm(3, 6, "", false));      // 5 closer: +20
        assertEquals(0, Combat.distanceTerm(2, 6, "", false));       // none within 2 blocks (the sheet)
        assertEquals(80, Combat.distanceTerm(3, 10, "Long Range", false));   // clamped at -2 x PE: +8 x PE (the sheet's bug gave more)
        assertEquals(-52, Combat.distanceTerm(5, 6, "Scope Range", false));  // a scope closer than 8: as if 8 further
        assertEquals(40, Combat.distanceTerm(10, 6, "Scope Range", false));
        assertEquals(-144, Combat.distanceTerm(20, 6, "", true));    // blind: x -12 on a penalty
        assertEquals(20, Combat.distanceTerm(3, 6, "", true));       // ... but not on the bonus (the sheet tripled it)
    }

    @Test
    void critMultipliersAreFallout2sTableForMen() {
        // gCriticalHitTables[KILL_TYPE_MAN], in halves: head 4 4 5 5 6 6, eyes 4 4 6 6 8 8, torso 3 3 4 4 6 6, arms/legs 3 3 4 4 4 4, groin 3 3 3 3 4 4
        int[][] f2 = {{4, 4, 5, 5, 6, 6}, {4, 4, 6, 6, 8, 8}, {3, 3, 4, 4, 6, 6}, {3, 3, 4, 4, 4, 4}, {3, 3, 4, 4, 4, 4}, {3, 3, 3, 3, 4, 4}};
        List<Rules.Part> parts = List.of(Rules.Part.HEAD, Rules.Part.EYES, Rules.Part.TORSO, Rules.Part.LEFT_ARM, Rules.Part.RIGHT_LEG, Rules.Part.GROIN);
        for (int i = 0; i < parts.size(); i++)
            for (int d = 1; d <= 6; d++) assertEquals(f2[i][d - 1] / 2.0, Combat.critMultiplier(parts.get(i), d), parts.get(i) + " degree " + d);
    }

    @Test
    void theRollReadsLikeFallout2s() {
        ArrayDeque<Integer> dice = new ArrayDeque<>(List.of(30, 5, 50));   // 30 vs 60: hit by 30; 5 <= 3 + 6 = crit; 50: degree 3
        Combat.Roll r = Combat.roll(60, 6, 0, 5, 0, dice::pop);
        assertTrue(r.hit() && r.crit());
        assertEquals(3, r.degree());
        dice.addAll(List.of(95, 2, 99));   // 95 vs 40: missed by 55; 2 <= 5: a critical failure; 99 - 5 x (LK 3 - 5) = 109: degree 5
        r = Combat.roll(40, 6, 0, 3, 0, dice::pop);
        assertTrue(r.critFail());
        assertEquals(5, r.degree());
        dice.addAll(List.of(10, 40, 95));  // an aimed head shot (-40) adds 40 to the crit chance; Better Criticals +20 reach degree 6
        r = Combat.roll(60, 0, -40, 5, 20, dice::pop);
        assertTrue(r.crit());
        assertEquals(6, r.degree());
    }

    @Test
    void burstSplitsAsFallout2() {
        assertArrayEquals(new int[]{1, 0, 1, 1}, Combat.burstSplit(3));
        assertArrayEquals(new int[]{1, 1, 2, 2}, Combat.burstSplit(6));
        assertArrayEquals(new int[]{1, 2, 3, 4}, Combat.burstSplit(10));
    }

    @Test
    void critEffectsOnATypicalTarget() {
        int[] five = {5, 5, 5, 5, 5, 5, 5};
        assertFalse(Combat.critEffects(Rules.Part.HEAD, 2, five).knockedDown());
        assertTrue(Combat.critEffects(Rules.Part.HEAD, 3, five).knockedDown());   // EN 5 < 3 x 3 - 1
        assertFalse(Combat.critEffects(Rules.Part.HEAD, 3, five).knockedOut());
        assertTrue(Combat.critEffects(Rules.Part.HEAD, 4, five).knockedOut());    // EN 5 < -7 + 12 + 3
        assertTrue(Combat.critEffects(Rules.Part.LEFT_ARM, 3, five).dropsWeapon());
        assertFalse(Combat.critEffects(Rules.Part.LEFT_ARM, 3, five).crippledArm());
        assertTrue(Combat.critEffects(Rules.Part.LEFT_ARM, 4, five).crippledArm());
        assertFalse(Combat.critEffects(Rules.Part.LEFT_ARM, 3, five).bypass());   // arms and legs from degree 4
        assertTrue(Combat.critEffects(Rules.Part.TORSO, 2, five).bypass());
        assertTrue(Combat.critEffects(Rules.Part.TORSO, 6, five).dies());
        assertFalse(Combat.critEffects(Rules.Part.TORSO, 5, five).dies());
    }

    @Test
    void marekRulingsOf1114() {
        // light at any distance (F1 and F2), no cap, knockback for bullets too; a target down: the sheet's +10 (ruled back 11-14)
        assertEquals(-40, Combat.lightTerm(3, 2));
        Combat.Shot s = new Combat.Shot();
        s.skill = 150; s.blocks = 3; s.perception = 6; s.range = 25; s.targetDown = true;
        Combat.ToHit th = Combat.toHit(s);
        assertEquals(10, th.terms().get("target down"));
        assertNull(th.terms().get("lying, within reach"));
        assertTrue(th.chance() > 100);
        assertEquals(1, Combat.knockback(14));
        // the sheet's combined critical failures
        Combat.Failure f2 = Combat.critFailure(2), f4 = Combat.critFailure(4), f5 = Combat.critFailure(5);
        assertTrue(f2.losesAmmo() && f2.losesTurn() && !f2.dropsWeapon());
        assertTrue(Combat.critFailure(3).dropsWeapon() && Combat.critFailure(3).weaponDamaged());
        assertTrue(f4.hitsRandom() && f4.hurt1d6() && f4.weaponDestroyed());
        assertTrue(f5.knockedDown() && f5.crippledArm() && f5.hitsSelf() && !f5.onFire() && f5.weaponDestroyed());
        assertTrue(Combat.critFailure(5, true).onFire());
        assertTrue(Combat.critFailure(1).names().isEmpty());
        // F2's grenade scatter and Stimpak
        assertEquals(1, Combat.grenadeScatter(1, (lo, hi) -> lo));
        assertEquals(4, Combat.grenadeScatter(8, (lo, hi) -> hi));
        assertEquals(30, Combat.stimpak(25, 30, (lo, hi) -> hi));
        assertEquals(20, Combat.stimpak(10, 30, (lo, hi) -> lo));
    }

    @Test
    void marekRulingsRound2Of1114() {
        // a fallen target lies like a crawling one: +10 and, within reach, the prone +20 (the +4 AC comes in targetAc)
        Combat.Shot s = new Combat.Shot();
        s.skill = 50; s.blocks = 2; s.perception = 6; s.range = 25; s.targetDown = true;
        assertEquals(10, Combat.toHit(s).terms().get("target down"));
        assertEquals(20, Combat.toHit(s).terms().get("lying, within reach"));
        s.targetDown = false; s.targetProne = true;
        assertEquals(20, Combat.toHit(s).terms().get("lying, within reach"));
        // a 100 always misses, even at 140 %; it fumbles only with Jinxed (F2: half of every miss)
        java.util.ArrayDeque<Integer> dice = new java.util.ArrayDeque<>(java.util.List.of(100, 30, 50));
        Combat.Roll r = Combat.roll(140, 5, 0, 5, 0, true, dice::pop);
        assertFalse(r.hit());
        assertTrue(r.critFail());
        assertEquals(2, r.degree());   // 50 - 5 x (5 - 5) = 50: degree 2
        dice = new java.util.ArrayDeque<>(java.util.List.of(100));
        r = Combat.roll(140, 5, 0, 5, 0, false, dice::pop);
        assertFalse(r.hit() || r.critFail());
        dice = new java.util.ArrayDeque<>(java.util.List.of(99, 100));
        assertTrue(Combat.roll(140, 5, 0, 5, 0, false, dice::pop).hit());
        // Jinxed's coin on an ordinary miss: 51+ keeps it a plain miss
        dice = new java.util.ArrayDeque<>(java.util.List.of(80, 90, 51));
        r = Combat.roll(60, 5, 0, 5, 0, true, dice::pop);
        assertFalse(r.hit() || r.critFail());
        // the weapon damaged: a tenth of its health [EXAMPLE], never below 0
        assertEquals(72, Combat.damaged(80, 80), 1e-9);
        assertEquals(0, Combat.damaged(5, 80), 1e-9);
        assertTrue(Combat.fireWeapon("Fire") && !Combat.fireWeapon("Normal"));
        // the light at the balancing: free within N blocks when set
        Combat.lightFreeWithin = 4;
        try { assertEquals(0, Combat.lightTerm(3, 4)); assertEquals(-40, Combat.lightTerm(3, 5)); } finally { Combat.lightFreeWithin = 0; }
    }

    @Test
    void falloutNewVegasCondition() {
        // damage: full at 75 % and above, half at 0, linear between (Fallout Wiki, Equipment condition)
        assertEquals(1.0, Combat.conditionDamage(1.0), 1e-9);
        assertEquals(1.0, Combat.conditionDamage(0.75), 1e-9);
        assertEquals(0.5, Combat.conditionDamage(0.0), 1e-9);
        assertEquals(0.5 + 2 * 0.3 / 3, Combat.conditionDamage(0.3), 1e-9);
        // wear 0.2 a round, broken on the last health point: an 80-health pistol fires 395 rounds (the wiki: "about 395")
        double h = 80;
        int rounds = 0;
        while (!Combat.broken(h)) { h -= Combat.WEAR_PER_ROUND; rounds++; }
        assertEquals(395, rounds);
        // repair by combining: the wiki's two worked examples (20 % with 10 %, Repair 10 and 50), capped at 100; Mr. Fixit x2
        assertEquals(27, Combat.combineRepair(20, 10, 10, false), 1e-9);
        assertEquals(33, Combat.combineRepair(10, 20, 50, false), 1e-9);
        assertEquals(100, Combat.combineRepair(95, 90, 100, false), 1e-9);
        assertEquals(20 + 2 * 7, Combat.combineRepair(20, 10, 10, true), 1e-9);
        // the repair kit: +10 / 15 / 20 / 25 / 30 by the skill bands
        assertEquals(60, Combat.kitRepair(50, 24, false), 1e-9);
        assertEquals(65, Combat.kitRepair(50, 25, false), 1e-9);
        assertEquals(80, Combat.kitRepair(50, 100, false), 1e-9);
        assertEquals(100, Combat.kitRepair(90, 60, true), 1e-9);
    }
}
