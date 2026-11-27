package cz.lollywood.falloutak;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.110.0 (Marek 11-27, verbatim: *"Valerian and hops - yes, it is good. Sleep table - yes, this works great."* · *"I think fight should
 * end (should players choose that they do not want to just kill them) when all enemies are unable to fight (paralysed, asleep, dying, in
 * too much pain etc.). I would say that in that case, they all count as surrendered."*).
 */
class SleepSurrenderTest extends MockGame {

    @BeforeEach
    void map() { Arena.use = world; Travel.use(WorldMapTest.fixture()); Travel.here("Roadside"); }

    @AfterEach
    void noMap() { Arena.use = null; Arena.current = null; Travel.map = null; Travel.file = null; Travel.scene.clear(); Loot.clear(); }

    private static Random calm() { return new Random() { @Override public double nextDouble() { return 0.99; } @Override public int nextInt(int b) { return 0; } }; }

    private static Data.Chem chem(String n) { Data.Chem c = Data.CHEMS.get(n); assertNotNull(c, n); return c; }

    private Character rested() {
        Character c = plugin.character(player());
        c.sleeping = true;
        c.restRun = 0;
        return c;
    }

    private static void at(Character c, Rules.Stat st, double fraction) { c.survival.put(st, fraction * c.max(st)); }

    @Test
    void advancedPainCostsTheFirstHourCriticalPainAllOfItValerianOnlyTheLighter() {
        Character c = rested();
        c.survival.put(Rules.Stat.PAIN, 40.0);
        assertEquals("Advanced Pain", c.stageNow(Rules.Stat.PAIN));
        assertEquals("Advanced Pain", c.keptAwake());
        assertNotNull(c.lyingAwakeHour());
        assertTrue(c.lyingAwake);
        assertTrue(c.hourly(Rules.Stat.SLEEP) < 0, "lying awake gains no Sleep");
        c.restRun = 1;
        assertNull(c.lyingAwakeHour(), "only the first hour of the rest");
        assertTrue(c.hourly(Rules.Stat.SLEEP) > 0, "then he sleeps");
        c.survival.put(Rules.Stat.PAIN, 60.0);
        assertEquals("Critical Pain", c.stageNow(Rules.Stat.PAIN));
        assertTrue(c.keptAwake().startsWith("!"));
        c.restRun = 5;
        assertNotNull(c.lyingAwakeHour(), "Critical Pain: no sleep at all while it lasts");
        c.dose(chem("Valerian"), calm());
        assertTrue(c.keptAwake().startsWith("!"), "valerian does nothing against the no-sleep causes");
        c.survival.put(Rules.Stat.PAIN, 40.0);
        assertNull(c.keptAwake(), "valerian takes the lighter causes' hour off");
        c.survival.put(Rules.Stat.PAIN, 60.0);
        c.dose(chem("Diazepam"), calm());
        assertNull(c.keptAwake(), "a sedative puts him to sleep through it");
    }

    @Test
    void theFightsFirstHourHungerHeatAndTheStimulants() {
        Character c = rested();
        c.fightEndedAt = c.now();
        assertEquals("the fight just over", c.keptAwake());
        c.fightEndedAt = c.now() - 1.5;
        assertNull(c.keptAwake());
        at(c, Rules.Stat.FOOD, 0.5);
        assertEquals("Advanced Starvation", c.stageNow(Rules.Stat.FOOD));
        assertEquals("Advanced Starvation", c.keptAwake());
        at(c, Rules.Stat.FOOD, 0.3);
        assertTrue(c.keptAwake().startsWith("!Critical Starvation"), c.keptAwake());
        at(c, Rules.Stat.FOOD, 1.0);
        at(c, Rules.Stat.HYPERTHERMIA, 0.7);
        assertTrue(c.keptAwake().startsWith("!Critical Hyperthermia"), c.keptAwake());
        at(c, Rules.Stat.HYPERTHERMIA, 0.0);
        c.dose(chem("Caffeine"), calm());
        assertEquals("caffeine", c.keptAwake());
        c.dose(chem("Pervitin"), calm());
        assertTrue(c.keptAwake().startsWith("!a stimulant"), c.keptAwake());
    }

    @Test
    void shiveringKeepsYouAwakeButTheFreezingFallAsleep() {
        Character c = rested();
        at(c, Rules.Stat.HYPOTHERMIA, 0.3);
        assertEquals("Minor Hypothermia", c.stageNow(Rules.Stat.HYPOTHERMIA));
        assertEquals("Minor Hypothermia (shivering)", c.keptAwake());
        at(c, Rules.Stat.HYPOTHERMIA, 0.5);
        assertTrue(c.keptAwake().startsWith("!Advanced Hypothermia"));
        at(c, Rules.Stat.HYPOTHERMIA, 0.7);
        assertTrue(c.freezing());
        assertNull(c.keptAwake(), "the freezing sleep at once");
        c.sleeping = false;
        c.working = true;
        String r = c.freezingHour(calm());   // 0.99: the Endurance check fails
        assertNotNull(r);
        assertTrue(c.sleeping && !c.working, r);
        c.sleeping = false;
        assertNull(c.freezingHour(new Random() { @Override public double nextDouble() { return 0.0; } }), "the check made: he stays awake");
        assertFalse(c.sleeping);
    }

    @Test
    void anHourAwakeThenAnHourAsleepOnTheClock() {
        Character c = rested();
        c.survival.put(Rules.Stat.PAIN, 40.0);
        at(c, Rules.Stat.SLEEP, 0.5);
        double s0 = c.survival.get(Rules.Stat.SLEEP);
        Hud.hour(c);
        double s1 = c.survival.get(Rules.Stat.SLEEP);
        assertTrue(s1 < s0, "the first hour lying awake: " + s0 + " -> " + s1);
        assertTrue(c.news.stream().anyMatch(n -> n.startsWith("Lying awake")), c.news.toString());
        assertEquals(1, c.restRun);
        Hud.hour(c);
        assertTrue(c.survival.get(Rules.Stat.SLEEP) > s1, "the second hour asleep");
    }

    @Test
    void whenEveryEnemyIsOutTheFightEndsAndTheLivingSurrender() {
        PlayerMock a = player();
        Travel.scene.add(new Travel.Met(new WorldMap.Encounter(Travel.map.kinds.get("raiders:pond"), "fight", 3), true));
        Travel.sceneTerrain = "field";
        assertFalse(Travel.battleMap(plugin, a, Arena.Mode.AMBUSH).startsWith("!"));
        Battle.State s = plugin.battle.state(a);
        Battle.Fight f = s.fight;
        assertTrue(f.targets.size() >= 2, "raiders: " + f.targets.size());
        int k = 0;
        Battle.Target netted = null;
        for (Battle.Target t : f.targets.values()) {
            if (k == 0) { t.entangled = 2; netted = t; }
            else if (k == 1) t.out = true;
            else t.dead = true;
            k++;
        }
        assertTrue(netted.inFight(), "the net alone does not take him out of the AI's turns");
        assertTrue(netted.outOfIt());
        assertTrue(plugin.battle.checkOver(a, s, plugin.character(a)));
        assertTrue(f.over);
        long surrendered = Loot.bodies.stream().filter(x -> x.surrendered).count();
        assertEquals(2, surrendered, "the netted and the knocked out");
        Loot.Body x = Loot.bodies.stream().filter(b -> b.surrendered).findFirst().orElseThrow();
        assertEquals("surrendered", x.state());
        String r = Loot.takeAlive(plugin, a, x);
        assertTrue(r.contains("surrendered") && r.contains("a captive"), r);
        assertEquals("taken alive", x.state());
    }

    @Test
    void oneStillStandingKeepsTheFightGoing() {
        PlayerMock a = player();
        Travel.scene.add(new Travel.Met(new WorldMap.Encounter(Travel.map.kinds.get("raiders:pond"), "fight", 3), true));
        Travel.sceneTerrain = "field";
        assertFalse(Travel.battleMap(plugin, a, Arena.Mode.AMBUSH).startsWith("!"));
        Battle.State s = plugin.battle.state(a);
        Battle.Target first = s.fight.targets.values().iterator().next();
        first.entangled = 2;
        assertFalse(plugin.battle.checkOver(a, s, plugin.character(a)), "the others still fight");
        plugin.battle.stop(a);
        assertTrue(plugin.character(a).now() - plugin.character(a).fightEndedAt < 1e-6, "the fight's end is set for the sleep");
    }
}
