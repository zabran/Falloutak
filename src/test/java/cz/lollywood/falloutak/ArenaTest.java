package cz.lollywood.falloutak;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Part 9d round 2 (Marek 11-23): *"please, would it be possible to give players the option to ambush them instead"* · *"a battle arena
 * per terrain - lets go for it"* · *"it would be possible to have more encounters at once at one battle map. And sight can be turned to
 * hunt, if players want to."*: the scene (every meeting of the window), the arenas by terrain, and the party's fight on them, played on
 * the mock server (the made-up world map of WorldMapTest).
 */
class ArenaTest extends MockGame {

    @BeforeEach
    void map() { Arena.use = world; Travel.use(WorldMapTest.fixture()); Travel.here("Roadside"); }

    @AfterEach
    void noMap() { Arena.use = null; Arena.current = null; Travel.map = null; Travel.file = null; Travel.scene.clear(); }

    // ------------------------------------------------------------------ the scene
    @Test
    void theWindowsMeetingsArePoissonAtLeastOneAtMostFour() {
        double h = 1.5;
        Random rnd = new Random(5);
        int n = 40000;
        int[] count = new int[Travel.AT_ONCE + 1];
        for (int k = 0; k < n; k++) count[Travel.meetings(h, rnd)]++;
        assertEquals(0, count[0], "never none: the window met someone");
        // P(k | k >= 1), the tail from AT_ONCE up folded into AT_ONCE
        double p0 = Math.exp(-h), pk = p0;
        double[] want = new double[Travel.AT_ONCE + 1];
        double tail = 1 - p0;
        for (int k = 1; k < Travel.AT_ONCE; k++) { pk *= h / k; want[k] = pk / (1 - p0); tail -= pk; }
        want[Travel.AT_ONCE] = tail / (1 - p0);
        for (int k = 1; k <= Travel.AT_ONCE; k++) assertEquals(want[k], count[k] / (double) n, 0.01, "k = " + k);
        // a tiny hazard: one meeting
        for (int k = 0; k < 100; k++) assertEquals(1, Travel.meetings(1e-4, rnd));
    }

    @Test
    void theSameKindAndTypeMergeIntoOneGroup() {
        List<WorldMap.Row> rows = Travel.map.table(13, 10, 2, true);
        List<WorldMap.Encounter> l = Travel.drawAll(rows, 4, WorldMapTest.fixed(0.0));   // the same draw four times
        assertEquals(1, l.size(), l.toString());
        WorldMap.Encounter one = WorldMap.draw(rows, WorldMapTest.fixed(0.0));
        assertEquals(4 * one.count(), l.get(0).count());
    }

    @Test
    void aWalkThatMeetsSomeoneBringsTheWholeScene() {
        Travel.here("Westtown");
        PlayerMock p = player();
        String r = Travel.go(plugin, p, "Roadside", false, WorldMapTest.fixed(0.0));
        assertFalse(Travel.scene.isEmpty(), r);
        assertTrue(java.util.Arrays.asList(Arena.TERRAINS).contains(Travel.sceneTerrain), Travel.sceneTerrain);
        assertTrue(Travel.go(plugin, p, "Roadside", false, WorldMapTest.fixed(0.5)).startsWith("! an encounter waits"));
    }

    // ------------------------------------------------------------------ the arenas
    @Test
    void everyTerrainBuildsAnArenaWithItsSpots() {
        for (String t : Arena.TERRAINS) {
            Arena.Layout a = Arena.build(world, t, 42);
            assertEquals(t, a.terrain());
            assertTrue(a.cover().size() >= 6, t + ": cover " + a.cover().size());
            assertTrue(a.open().size() >= 4, t + ": open " + a.open().size());
            assertTrue(a.far().size() >= 12, t + ": far " + a.far().size());
            assertTrue(a.ring().size() >= 12, t + ": ring " + a.ring().size());
            for (List<Arena.Spot> l : List.of(a.cover(), a.open(), a.far(), a.ring())) {
                for (Arena.Spot s : l) {
                    assertTrue(Arena.standing(world, s.x(), s.y(), s.z()), t + ": " + s + " is a standing spot");
                    assertTrue(s.x() >= a.x0() && s.x() < a.x0() + Arena.SIZE && s.z() >= a.z0() && s.z() < a.z0() + Arena.SIZE, t + ": inside");
                    for (Arena.Spot o : l) if (o != s) assertFalse(Math.abs(o.x() - s.x()) <= 1 && Math.abs(o.z() - s.z()) <= 1, t + ": nobody next to another");
                }
            }
            for (Arena.Spot s : a.cover()) assertTrue(s.z() - a.z0() <= 12, t + ": the cover is on the party's (south) side");
            for (Arena.Spot s : a.far()) assertTrue(s.z() - a.z0() >= 28, t + ": the enemies far, in the north half");
            // the barrier keeps the fight on the map
            assertEquals(Material.BARRIER, world.getBlockAt(a.x0() - Arena.MARGIN, Arena.Y, a.z0() + 10).getType());
        }
        // a seed is an arena: the same seed builds it again
        Arena.Layout x = Arena.build(world, "forest", 7), y = Arena.build(world, "forest", 7), z = Arena.build(world, "forest", 8);
        assertEquals(x.cover(), y.cover());
        assertNotEquals(x.cover(), z.cover());
        // the terrains look like themselves
        assertEquals(Material.GRAY_CONCRETE.isSolid(), true);
        assertTrue(count(world, Arena.build(world, "forest", 3), "_LOG") > 100, "a forest has trees");
        assertTrue(count(world, Arena.build(world, "road", 3), "CONCRETE") > 300, "a road has its asphalt");
        assertTrue(count(world, Arena.build(world, "town", 3), "BRICK") + count(world, Arena.build(world, "town", 3), "WHITE_CONCRETE") > 40, "a town ruin has walls");
        assertTrue(count(world, Arena.build(world, "field", 3), "WHEAT") > 50, "a field has its crop");
    }

    private static int count(World w, Arena.Layout a, String part) {
        int n = 0;
        for (int x = a.x0(); x < a.x0() + Arena.SIZE; x++)
            for (int z = a.z0(); z < a.z0() + Arena.SIZE; z++)
                for (int y = Arena.Y - 1; y < Arena.Y + 8; y++) if (w.getBlockAt(x, y, z).getType().name().contains(part)) n++;
        return n;
    }

    @Test
    void everyKindHasItsStatblock() {
        for (WorldMap.Kind k : Travel.map.kinds.values()) assertNotNull(Arena.foe(k.key()), k.key());
        assertTrue(Arena.foe("raiders:pond").gun());
        assertFalse(Arena.foe("creature:boar").gun());
        assertEquals("Wild boar", Arena.foe("creature:boar").name());
        assertEquals("Radmie", Arena.foe("creature:radmie").nemesis());
        for (String k : List.of("boar", "dogs", "wolves", "radmie", "trhac", "krtek", "krtek-old", "ticks", "spider", "ropak", "tigers", "jozin", "jozin2"))
            assertNotNull(Arena.FOES.get(k), "the real map's creature " + k);
    }

    // ------------------------------------------------------------------ the fight
    private static WorldMap.Kind kind(String key) { return Travel.map.kinds.get(key); }

    private void scene(String key, String type, int count, boolean seen) {
        Travel.scene.add(new Travel.Met(new WorldMap.Encounter(kind(key), type, count), seen));
        Travel.sceneTerrain = "field";
    }

    private void enemiesTurn(Battle.Fight f) { for (int i = 0; i < 200 && f.enemyPhase && !f.over; i++) server.getScheduler().performTicks(20); }

    @Test
    void anAmbushPutsThePartyInCoverAndFirstThenTheEnemies() {
        PlayerMock a = player(), b = player(2.5, 0.5, false);
        Location aWas = a.getLocation().clone();
        scene("raiders:pond", "fight", 3, true);
        Menus.actions(a);
        click("Travel (the world map)");
        assertTrue(page().text().contains("Spotted in time"), page().text());
        clickAndClose("Ambush them");
        Battle.Fight f = plugin.battle.fightOf(a);
        assertNotNull(f, said(a));
        assertSame(f, plugin.battle.fightOf(b), "one fight for the party");
        assertEquals(3, f.targets.size());
        assertFalse(f.enemiesFirst);
        assertFalse(f.enemyPhase, "round 1: the party's turn");
        Battle.State sa = plugin.battle.state(a), sb = plugin.battle.state(b);
        assertTrue(sa.ap > 0 && sb.ap > 0 && !sa.enemyTurn && !sb.enemyTurn, "both play at once");
        assertTrue(a.getLocation().getBlockY() == Arena.Y && a.getLocation().getBlockZ() < 14, "a in cover on the south side: " + a.getLocation());
        // a ends his turn: the party's turn goes on for b
        plugin.battle.endTurnFor(a);
        assertTrue(sa.enemyTurn && !f.enemyPhase, "a waits for b");
        plugin.battle.endTurnFor(b);
        assertTrue(f.enemyPhase || f.over, "then the enemies' turn");
        enemiesTurn(f);
        if (!f.over && plugin.battle.inBattle(a)) {
            assertEquals(2, f.round, "round 2");
            assertFalse(sa.enemyTurn, "the party's turn again");
        }
        // the end: every raider down; both get the kill XP and go back to where they were
        long xa = plugin.character(a).xp;
        for (Battle.Target t : f.targets.values()) t.dead = true;
        Battle.State s = plugin.battle.state(a) != null ? plugin.battle.state(a) : plugin.battle.state(b);
        if (s != null) plugin.battle.checkOver(a, s, plugin.character(a));
        server.getScheduler().performTicks(60);
        assertFalse(plugin.battle.inBattle(a) || plugin.battle.inBattle(b), "the fight is over for both");
        if (!plugin.character(a).dead) assertTrue(plugin.character(a).xp > xa || xa > 0, "the kill XP");
        assertEquals(aWas.getBlockX(), a.getLocation().getBlockX());
        assertEquals(aWas.getBlockZ(), a.getLocation().getBlockZ(), "back where he was");
        assertTrue(Travel.scene.isEmpty(), "the fought group left the scene");
        assertNull(Arena.current);
    }

    @Test
    void anAmbushOnThePartyLetsThemActFirst() {
        PlayerMock a = player();
        scene("raiders:pond", "fight", 2, false);
        assertTrue(Travel.goRound(plugin, a).startsWith("!"), "nobody saw them: no going round");
        assertTrue(Travel.battleMap(plugin, a, Arena.Mode.AMBUSH).startsWith("!"), "nor ambushing them");
        String r = Travel.battleMap(plugin, a, Arena.Mode.AMBUSHED);
        assertTrue(r.startsWith("AMBUSHED"), r);
        Battle.Fight f = plugin.battle.fightOf(a);
        assertTrue(f.enemiesFirst);
        assertTrue(f.enemyPhase && plugin.battle.state(a).enemyTurn, "they act first, he waits");
        enemiesTurn(f);
        if (plugin.battle.inBattle(a) && !f.over) {
            assertEquals(1, f.round, "then his turn in round 1");
            assertFalse(plugin.battle.state(a).enemyTurn);
            assertTrue(plugin.battle.state(a).ap > 0 || plugin.character(a).unconscious());
            plugin.battle.endTurnFor(a);
            assertTrue(f.enemyPhase || f.over);
            assertEquals(2, f.round, "round 2 begins with them");
        }
        plugin.battle.stop(a);
    }

    @Test
    void aSightCanBeHuntedAndTheAnimalRuns() {
        PlayerMock a = player();
        scene("creature:boar", "sight", 2, true);
        assertTrue(Travel.battleMap(plugin, a, Arena.Mode.HUNT).startsWith("!"), "nothing marked yet");
        Menus.actions(a);
        click("Travel (the world map)");
        click("Hunt: wild boar");
        assertTrue(page().text().contains("Hunted: "), page().text());
        clickAndClose("Hunt now");
        Battle.Fight f = plugin.battle.fightOf(a);
        assertNotNull(f, said(a));
        assertEquals(2, f.targets.size());
        for (Battle.Target t : f.targets.values()) assertTrue(t.flees && t.melee, "a hunted boar runs");
        Map<java.util.UUID, Double> before = new HashMap<>();
        for (java.util.UUID u : f.dummies) before.put(u, server.getEntity(u).getLocation().distance(a.getLocation()));
        plugin.battle.endTurnFor(a);
        enemiesTurn(f);
        for (java.util.UUID u : f.dummies) {
            Battle.Target t = f.targets.get(u);
            Entity e = server.getEntity(u);
            assertTrue(t.escaped || e == null || e.getLocation().distance(a.getLocation()) > before.get(u), "it ran from him");
        }
        // when both are gone the hunt is over (no XP for what got away)
        for (Battle.Target t : f.targets.values()) t.escaped = true;
        if (plugin.battle.inBattle(a)) plugin.battle.checkOver(a, plugin.battle.state(a), plugin.character(a));
        server.getScheduler().performTicks(60);
        assertFalse(plugin.battle.inBattle(a));
        assertTrue(Travel.scene.isEmpty(), "the hunted ones left the scene");
    }

    @Test
    void hostileCreaturesComeAndBite() {
        PlayerMock a = player();
        Character c = plugin.character(a);
        scene("creature:boar", "fight", 3, false);
        Travel.battleMap(plugin, a, Arena.Mode.AMBUSHED);
        Battle.Fight f = plugin.battle.fightOf(a);
        for (Battle.Target t : f.targets.values()) assertTrue(t.melee && !t.flees, "a hostile boar attacks");
        plugin.battle.d100 = () -> 50;   // a middling roll: hits more often than not
        double near0 = nearest(f, a);
        boolean bitten = false;
        for (int round = 0; round < 4 && plugin.battle.inBattle(a) && !f.over; round++) {
            enemiesTurn(f);
            String heard = said(a);
            if (heard.contains("attacks you")) bitten = true;
            c.hp = c.hpMax();
            if (!plugin.battle.inBattle(a) || f.over) break;
            plugin.battle.endTurnFor(a);
        }
        assertTrue(bitten || nearest(f, a) < near0, "they came at him (" + near0 + " -> " + nearest(f, a) + ")");
        if (plugin.battle.inBattle(a)) plugin.battle.stop(a);
    }

    private double nearest(Battle.Fight f, PlayerMock a) {
        double d = Double.MAX_VALUE;
        for (java.util.UUID u : f.dummies) { Entity e = server.getEntity(u); if (e != null) d = Math.min(d, e.getLocation().distance(a.getLocation())); }
        return d;
    }

    @Test
    void goingRoundLeavesThePeacefulOnesAndHuntedJoinAnAmbush() {
        PlayerMock a = player();
        scene("raiders:pond", "fight", 2, true);
        scene("creature:boar", "sight", 1, true);
        Travel.scene.get(1).hunt = true;
        List<Arena.Group> g = Travel.groups(true);
        assertEquals(2, g.size(), "the raiders and the hunted boar on one battle map");
        assertTrue(g.get(1).flees());
        assertTrue(Travel.battleMap(plugin, a, Arena.Mode.HUNT).startsWith("!"), "a hunt waits for the hostile ones");
        long xp0 = plugin.character(a).xp;
        assertEquals("You go round them unseen.", Travel.goRound(plugin, a));
        assertTrue(plugin.character(a).xp > xp0);
        assertEquals(1, Travel.scene.size(), "the boar is still there");
        assertTrue(Travel.battleMap(plugin, a, Arena.Mode.HUNT).startsWith("THE HUNT"));
        plugin.battle.stop(a);
    }

    // ------------------------------------------------------------------ the world map in Minecraft
    @Test
    void aSectorIsAragsSixteenBlocksAndTheBoxIsWhereTheMapLies() {
        WorldMap m = Travel.map;
        assertArrayEquals(new double[]{16 * 7.5, 16 * 3.25}, MapView.block(m, 3.25, 7.5), 1e-9);   // the fixture's box starts at 0
        // the real map's box: [500, 813, 312, 528] of Arag's 1280 x 624 sectors, so Jihlava (100, 166) is at blocks x 10656, z 6592
        WorldMap real = boxed(500, 813, 312, 528);
        assertEquals(500, real.bx0);
        assertEquals(312, real.by0);
        assertArrayEquals(new double[]{10656, 6592}, MapView.block(real, 100, 166), 1e-9);
        PlayerMock p = player();
        assertTrue(MapView.look(plugin, p).startsWith("! the world map's world"), "without Arag's world in the folder: says where it comes from");
    }

    /** The made-up map with another box. */
    private static WorldMap boxed(int x0, int x1, int y0, int y1) {
        try (var in = ArenaTest.class.getResourceAsStream("/worldmap-test.json")) {
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            com.google.gson.JsonArray b = new com.google.gson.JsonArray();
            for (int v : new int[]{x0, x1, y0, y1}) b.add(v);
            o.add("box", b);
            return WorldMap.read(new java.io.StringReader(o.toString()));
        } catch (java.io.IOException e) { throw new RuntimeException(e); }
    }
}
