package cz.lollywood.falloutak;

import org.bukkit.Location;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.65.0, `09b` round 9 (Marek 11-23, verbatim: *"I agree with the anomalies+artefacts - please add them to the plugin."* · *"the black
 * glass - make it +60 while standing still."* · *""Do you want a Stalker-style "surge" storm?" - yes, I would make it the Black Rain.
 * Build it."*): the anomalies of `09c`, their artifacts, the nut, the detector and the Black Rain.
 */
class AnomaliesTest extends MockGame {
    static Character human() { return Part3Test.of("Human", 5, 5, 5, 5, 5, 6, 5); }

    static Zones.Zone zone(String kind) {
        Zones.Zone z = new Zones.Zone();
        z.id = "1"; z.world = "world"; z.ball = true; z.r = 2; z.anomaly = kind;
        return z;
    }

    @Test
    void thirteenKindsAndTwelveArtifactsAsIn09c() {
        assertEquals(14, Anomalies.KINDS.size(), "13, the gravity one in two");
        assertEquals(12, Anomalies.ARTIFACTS.size());
        for (Anomalies.Kind k : Anomalies.KINDS.values())
            assertTrue(k.artifact().isEmpty() || Anomalies.ARTIFACTS.containsKey(k.artifact()), k.name());
        assertEquals(1, Anomalies.KINDS.get("throw").recharge());
        assertEquals(2, Anomalies.KINDS.get("lightning").recharge());
        assertEquals(0, Anomalies.KINDS.get("heat").recharge());
    }

    @Test
    void aRechargingKindFiresThenIsSpent() {
        Character c = human();
        Zones.Zone z = zone("throw");
        int hp0 = c.hp;
        String first = Anomalies.fire(c, z, 5, Part3Test.fixed(0.9, 10));   // 3d10 at 10 each = 30
        assertTrue(first.startsWith("the throw: hit for"), first);
        assertTrue(c.hp < hp0);
        assertEquals(7, z.spentUntil, "fired at round 5: spent in round 6");
        assertTrue(Anomalies.fire(c, z, 6, Part3Test.fixed(0.9, 10)).contains("spent"));
        assertTrue(Anomalies.fire(c, z, 7, Part3Test.fixed(0.9, 10)).startsWith("the throw"));
        Zones.Zone l = zone("lightning");
        Anomalies.fire(c, l, 1, Part3Test.fixed(0.9, 8));
        assertTrue(Anomalies.fire(c, l, 3, Part3Test.fixed(0.9, 8)).contains("spent"), "the lightning: two rounds");
        assertTrue(Anomalies.fire(c, l, 4, Part3Test.fixed(0.9, 8)).startsWith("lightning"));
    }

    @Test
    void luckyEscapeJerksTheBodyBackFromTheFirstHit() {
        Character c = human();                                                // Luck 5: 10 %
        int hp0 = c.hp;
        assertTrue(Anomalies.enter(c, zone("heat"), 0, Part3Test.fixed(0.5, 10)).contains("LUCKY ESCAPE"));
        assertEquals(hp0, c.hp);
        assertTrue(Anomalies.enter(c, zone("heat"), 0, Part3Test.fixed(0.5, 11)).contains("the heat: hit for"));
        assertTrue(c.hp < hp0);
        assertTrue(Anomalies.enter(c, zone("magnet"), 0, Part3Test.fixed(0.5, 1)).contains("the magnet"), "no harm: no escape needed");
    }

    @Test
    void theNormalHarmGoesThroughDtAndDr() {
        Character c = human();
        assertEquals(Math.round(Math.max(0, 30 - c.dt()) * (1 - c.dr())), c.harm(30, 0));
        assertEquals(30, c.harm(30, 4), "no Heat resistance");
    }

    @Test
    void timeRunsSixHoursARoundAndTheColdChills() {
        Character c = human();
        double clock0 = c.clock;
        Anomalies.hit(c, Anomalies.KINDS.get("time"), Part3Test.fixed(0.5, 1));
        assertEquals(clock0 + 6, c.clock, 1e-9);
        double h0 = c.survival.get(Rules.Stat.HYPOTHERMIA);
        Anomalies.hit(c, Anomalies.KINDS.get("cold"), Part3Test.fixed(0.5, 1));
        assertEquals(h0 + 10 * (1 - Math.max(0, c.resistances()[5]) / 100), c.survival.get(Rules.Stat.HYPOTHERMIA), 1e-9);
    }

    @Test
    void theEnvironmentRoundsRunOnlyForTheKindsThatHurt() {
        Character c = human();
        c.anomaly = "heat";
        assertTrue(c.envHarm());
        c.anomaly = "magnet";
        assertFalse(c.envHarm());
        c.anomaly = "";
        c.rainExposed = true;
        assertTrue(c.envHarm());
        c.wearing.add("Hazard suit");
        assertFalse(c.envHarm(), "the Black Rain runs off the suit");
    }

    @Test
    void theInsideEffects() {
        Character c = human();
        assertEquals(-30, Anomalies.insideEffect(c, "magnet").other().get("to_hit"), 1e-9);
        assertEquals(-10, Anomalies.insideEffect(c, "echo").other().get("skill:Guns"), 1e-9);
        assertEquals(-30, Anomalies.insideEffect(c, "radio").other().get("skill:Sneak"), 1e-9);
        assertNull(Anomalies.insideEffect(c, "heat"));
    }

    @Test
    void theBlackGlassGivesSixtyOnlyStandingStill() {
        Character c = human();
        double s0 = c.skillBonus("Sneak", false);
        int p0 = c.attr(Rules.P, false);
        c.belt(Set.of("Black glass"), true);
        assertEquals(s0 + 60, c.skillBonus("Sneak", false), 1e-9);
        assertEquals(p0 - 1, c.attr(Rules.P, false));
        c.belt(Set.of("Black glass"), false);
        assertEquals(s0, c.skillBonus("Sneak", false), 1e-9, "moving: no +60");
        assertEquals(p0 - 1, c.attr(Rules.P, false), "the price stays");
        c.belt(Set.of(), false);
        assertEquals(p0, c.attr(Rules.P, false), "off the belt: nothing");
        assertTrue(c.effects.stream().noneMatch(e -> e.slot().startsWith("artifact:")));
    }

    @Test
    void theArtifactsPricesAndGainsCount() {
        Character c = human();
        int r0 = c.healingRate();
        double food0 = c.hourly(Rules.Stat.FOOD);
        c.belt(Set.of("Green heart"), false);
        assertEquals(r0 + 1, c.healingRate());
        assertEquals(food0 * 1.25, c.hourly(Rules.Stat.FOOD), 1e-9);
        c.belt(Set.of("Stopped watch"), false);
        assertEquals(2, c.hourly(Rules.Stat.RADS), 1e-9, "+2 rads an hour");
        c.belt(Set.of("Ember"), false);
        c.survival.put(Rules.Stat.HYPOTHERMIA, 0.9 * c.max(Rules.Stat.HYPOTHERMIA));
        Hud.hour(c);
        assertTrue(c.survival.get(Rules.Stat.HYPOTHERMIA) <= 0.4 * c.max(Rules.Stat.HYPOTHERMIA) + 1e-9, "the ember: Minor at most");
    }

    @Test
    void theBlackRainBurnsInTheOpenAndTheSuitKeepsItOff() {
        Character c = human();
        assertNull(Anomalies.rainRound(c, Part3Test.fixed(0.5, 1)), "under a roof: nothing");
        c.rainExposed = true;
        double r0 = c.survival.get(Rules.Stat.RADS);
        int hp0 = c.hp;
        String s = Anomalies.rainRound(c, Part3Test.fixed(0.5, 4));
        assertTrue(s.startsWith("THE BLACK RAIN"), s);
        assertEquals(r0 + 25 * (1 - c.resistances()[1] / 100), c.survival.get(Rules.Stat.RADS), 1e-9);
        assertEquals(hp0 - 4, c.hp);
        c.wearing.add("Hazard suit");
        assertTrue(Anomalies.rainRound(c, Part3Test.fixed(0.5, 4)).contains("runs off"));
    }

    // ------------------------------------------------------------------ on the mock server
    @Test
    void walkingInHitsAtOnceAndTheArtifactIsTakenAtItsHeart() {
        PlayerMock p = player(0.5, 0.5, false);
        Character c = plugin.character(p);
        Zones.Zone z = plugin.zones.anomaly(new Location(world, 0.5, 5, 0.5), 2, "magnet");
        z.artifact = "Lodestone";
        plugin.hud.tick();
        assertEquals("magnet", c.anomaly);
        assertTrue(c.effects.stream().anyMatch(e -> e.slot().equals("anomaly")));
        assertTrue(Items.carried(p).containsKey("artifact:Lodestone"), "at its heart: taken");
        assertEquals("", z.artifact);
        p.teleport(new Location(world, 20.5, 4, 20.5));
        plugin.hud.tick();
        assertEquals("", c.anomaly);
        assertTrue(c.effects.stream().noneMatch(e -> e.slot().equals("anomaly")));
    }

    @Test
    void theNutSetsOffTheFirstAnomalyAndTheBlindSpotSwallowsIt() {
        PlayerMock p = player(0.5, 0.5, false);
        p.teleport(new Location(world, 0.5, 4, 0.5, -90, 0));                 // looking along +x
        Zones.Zone z = plugin.zones.anomaly(new Location(world, 6.5, 5.6, 0.5), 2, "throw");
        String s = plugin.anomalies.throwNut(p);
        assertTrue(s.contains("sets off gravity: the throw") && s.contains("spent"), s);
        assertEquals(plugin.zones.round + 2, z.spentUntil);
        plugin.zones.remove(z);
        plugin.zones.anomaly(new Location(world, 6.5, 5.6, 0.5), 2, "blind");
        assertTrue(plugin.anomalies.throwNut(p).contains("NEVER LANDS"));
    }

    @Test
    void theDetectorSaysMoreByItsTier() {
        PlayerMock p = player(0.5, 0.5, false);
        p.teleport(new Location(world, 0.5, 4, 0.5, -90, 0));
        Zones.Zone z = plugin.zones.anomaly(new Location(world, 6.5, 5, 0.5), 2, "cold");
        z.artifact = "Frost pearl";
        assertEquals("", plugin.anomalies.detect(p, Items.carried(p)));
        Items.give(p, "gear:Anomaly detector I", 1);
        String one = plugin.anomalies.detect(p, Items.carried(p));
        assertTrue(one.contains("DETECTOR: 4 m") && !one.contains("cold"), one);
        Items.give(p, "gear:Anomaly detector IV", 1);
        String four = plugin.anomalies.detect(p, Items.carried(p));
        assertTrue(four.contains("ahead") && four.contains("cold") && four.contains("AN ARTIFACT"), four);
    }

    @Test
    void theBlackRainFindsThoseUnderTheSkyAndMovesTheFieldsWhenItEnds() {
        PlayerMock gm = player(0.5, 0.5, true), out = player(10.5, 10.5, false), roofed = player(20.5, 20.5, false);
        world.getBlockAt(20, 8, 20).setType(Material.STONE);                  // a roof over the second
        plugin.character(out);
        plugin.character(roofed);
        Zones.Zone z = plugin.zones.anomaly(new Location(world, 30.5, 5, 30.5), 2, "heat");
        double x0 = z.x1, z0 = z.z1;
        assertTrue(Menus.rainCommand(gm, new String[]{"start", "3"}).startsWith("The Black Rain falls"));
        plugin.anomalies.rainTick();
        assertTrue(plugin.character(out).rainExposed);
        assertFalse(plugin.character(roofed).rainExposed);
        assertTrue(plugin.character(out).envHarm(), "out in it: the rounds run");
        plugin.zones.rounds(3);
        plugin.anomalies.rainTick();                                           // its rounds are over
        assertEquals(0, plugin.anomalies.rainPhase);
        assertFalse(plugin.character(out).rainExposed);
        assertTrue(Math.abs(z.x1 - x0) <= 8 && Math.abs(z.z1 - z0) <= 8);
    }

    @Test
    void theGmCommandsPlaceAndRemove() {
        PlayerMock gm = player(0.5, 0.5, true);
        assertTrue(Menus.anomalyCommand(gm, new String[]{"place", "lift", "3"}).contains("anomaly: the lift"));
        assertTrue(Menus.anomalyCommand(gm, new String[]{"give", "Black glass"}).contains("Sneak +60 standing still"));
        assertTrue(Items.carried(gm).containsKey("artifact:Black glass"));
        assertTrue(Menus.anomalyCommand(gm, new String[]{"remove"}).startsWith("removed"));
        assertEquals("No anomalies.", Menus.anomalyCommand(gm, new String[]{"list"}));
        assertEquals("GMs only.", Menus.anomalyCommand(player(2.5, 2.5, false), new String[]{"list"}));
    }
}
