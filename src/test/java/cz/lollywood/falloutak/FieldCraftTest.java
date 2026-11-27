package cz.lollywood.falloutak;

import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The three pieces of the weapons batch left over from rounds 7-8 (Marek 11-27: *"Not built yet - build please."*): the pavise set down
 * as a wall (05c §11), a pit or a deadfall dug before a fight (05d §7c), a darted creature found asleep after the fight (05d round 7:
 * *"a dart takes a creature ALIVE for the pens or the lab, it does not stop a fight"*).
 */
class FieldCraftTest extends MockGame {

    @BeforeEach
    void map() { Arena.use = world; Travel.use(WorldMapTest.fixture()); Travel.here("Roadside"); }

    @AfterEach
    void noMap() { Arena.use = null; Arena.current = null; Travel.map = null; Travel.file = null; Travel.scene.clear(); Travel.dug.clear(); Loot.clear(); }

    private Travel.Met scene(String key, String type, int count, boolean seen) {
        Travel.Met m = new Travel.Met(new WorldMap.Encounter(Travel.map.kinds.get(key), type, count), seen);
        Travel.scene.add(m);
        Travel.sceneTerrain = "field";
        return m;
    }

    private static Production.Design design(String name) {
        for (Production.Design d : Production.DESIGNS.values()) if (d.name.equals(name)) return d;
        throw new AssertionError(name);
    }

    private static int count(PlayerMock a, String name) {
        int n = 0;
        for (org.bukkit.inventory.ItemStack it : a.getInventory().getContents()) {
            Production.Design d = Items.made(Items.id(it));
            if (d != null && d.name.equals(name)) n += it.getAmount();
        }
        return n;
    }

    // ------------------------------------------------------------------ the pavise
    @Test
    void behindTheWallMeansCloseBehindItsFace() {
        // a pavise at (0, 0) facing +z (yaw 0): the man a block behind it is covered, one beside it or in front is not
        assertTrue(Battle.behindWall(0, 0, 0, 0, -1));
        assertTrue(Battle.behindWall(0, 0, 0, 0.5, -1.5));
        assertFalse(Battle.behindWall(0, 0, 0, 0, 1), "in front of it");
        assertFalse(Battle.behindWall(0, 0, 0, 1.2, -1), "beside it");
        assertFalse(Battle.behindWall(0, 0, 0, 0, -3), "too far behind");
        // facing east (yaw -90 = +x): behind it is west
        assertTrue(Battle.behindWall(0, 0, -90, -1, 0));
        assertFalse(Battle.behindWall(0, 0, -90, 1, 0));
    }

    @Test
    void thePaviseSetDownIsAWallForWhoeverIsBehindIt() {
        PlayerMock a = player();
        Character c = plugin.character(a);
        a.getInventory().addItem(Items.made(design("Pavise"), 1, 100));
        scene("raiders:pond", "fight", 1, true);
        assertFalse(Travel.battleMap(plugin, a, Arena.Mode.AMBUSH).startsWith("!"));
        Battle.State s = plugin.battle.state(a);
        assertEquals("Pavise", s.shield);
        for (float yaw : new float[]{0, 90, 180, 270}) {   // in cover the block in front is solid: face open ground
            Location l = a.getLocation().clone();
            l.setYaw(yaw);
            double yr = Math.toRadians(yaw);
            if (l.clone().add(-Math.sin(yr), 0, Math.cos(yr)).getBlock().isPassable()) { a.teleport(l); break; }
        }
        Data.Shield sd = Data.SHIELDS.get("Pavise");
        int ap0 = s.ap;
        String r = plugin.battle.pavise(a, c);
        assertTrue(r.startsWith("You set the Pavise down"), r);
        assertEquals(ap0 - Battle.PAVISE_AP, s.ap);
        assertNotNull(s.wall);
        // its front is the way he looked; he stands a block behind it
        Location me = a.getLocation();
        double yr = Math.toRadians(me.getYaw());
        Location ahead = me.clone().add(-Math.sin(yr) * 10, 0, Math.cos(yr) * 10), behind = me.clone().add(Math.sin(yr) * 10, 0, -Math.cos(yr) * 10);
        assertSame(s, plugin.battle.wallCovering(a, ahead));
        assertNull(plugin.battle.wallCovering(a, behind), "no help from the other side");
        // a shot from its front strikes it first: its DT and DR, and it wears by what it stopped
        double h0 = s.shieldHealth;
        StringBuilder log = new StringBuilder();
        double on = plugin.battle.wallTake(a, ahead, 20, 0, true, log);
        double[] h = Battle.shieldHit(sd.dt(), sd.dr(), 20, 0);
        assertEquals(h[0], on, 1e-9);
        assertEquals(h0 - h[1], s.shieldHealth, 1e-9);
        assertTrue(log.toString().contains("set down in front of you"), log.toString());
        // set down, it is not held: no cover from the held shield, no too-heavy penalty
        assertEquals(0, plugin.battle.shieldObscures(a, s, ahead));
        // under 50 % it only hides him
        s.shieldHealth = sd.health() * 0.4;
        assertEquals(20, plugin.battle.wallTake(a, ahead, 20, 0, true, new StringBuilder()), 1e-9);
        assertEquals(1, plugin.battle.wallObscures(a, ahead));
        // the enemies walk round it
        assertEquals(1, plugin.battle.wallCells(s.fight).size());
        // taken up again: 4 AP more
        s.ap = 10;
        r = plugin.battle.pavise(a, c);
        assertTrue(r.startsWith("You take up the Pavise"), r);
        assertNull(s.wall);
        assertEquals(10 - Battle.PAVISE_AP, s.ap);
        assertTrue(plugin.battle.wallCells(s.fight).isEmpty());
        plugin.battle.stop(a);
    }

    @Test
    void onlyAStandingShieldIsSetDown() {
        PlayerMock a = player();
        a.getInventory().addItem(Items.made(design("Riot shield"), 1, 100));
        scene("raiders:pond", "fight", 1, true);
        Travel.battleMap(plugin, a, Arena.Mode.AMBUSH);
        assertTrue(plugin.battle.pavise(a, plugin.character(a)).startsWith("!"));
        plugin.battle.stop(a);
        PlayerMock b = player(3.5, 0.5, true);
        assertTrue(plugin.battle.pavise(b, plugin.character(b)).startsWith("! a pavise is set down in a fight"));
    }

    // ------------------------------------------------------------------ dug before the fight
    @Test
    void aPitDugBeforeAnAmbushWaitsHalfWayToThem() {
        PlayerMock a = player();
        Character c = plugin.character(a);
        a.getInventory().addItem(Items.made(design("Spike pit"), 2, 100));
        a.getInventory().addItem(Items.made(design("Deadfall"), 1, 100));
        scene("raiders:pond", "fight", 2, true);
        assertTrue(Travel.canDig());
        String r = Travel.dig(plugin, a, "spike");
        assertTrue(r.startsWith("You dig a spike pit"), r);
        assertEquals(1, count(a, "Spike pit"), "one used");
        r = Travel.dig(plugin, a, "dead");
        assertTrue(r.startsWith("You set a deadfall"), r);
        assertEquals(0, count(a, "Deadfall"));
        assertEquals(2, Travel.dug.size());
        assertEquals("Spike pit", Travel.dug.get(0).def().name());
        assertEquals(Battle.weaponSkill(c, "Traps"), Travel.dug.get(0).skill(), 1e-9);
        // the menu shows what is dug and the dig buttons
        Menus.actions(a);
        click("Travel (the world map)");
        assertTrue(page().text().contains("Dug on their way: spike pit, deadfall"), page().text());
        assertTrue(page().buttons().stream().anyMatch(x -> x.label().startsWith("Dig a spike pit on their way (30 min)")));
        a.closeInventory();
        // the battle map: both lie between the party and them
        assertFalse(Travel.battleMap(plugin, a, Arena.Mode.AMBUSH).startsWith("!"));
        Battle.Fight f = plugin.battle.fightOf(a);
        assertEquals(2, f.traps.size());
        assertTrue(Travel.dug.isEmpty(), "laid");
        double ez = 0;
        for (java.util.UUID u : f.dummies) ez += server.getEntity(u).getLocation().getZ();
        ez /= f.dummies.size();
        double pz = a.getLocation().getZ();
        for (Battle.Trap tr : f.traps) {
            assertTrue(tr.z > Math.min(pz, ez) && tr.z < Math.max(pz, ez), "between them: " + tr.z + " (party " + pz + ", them " + ez + ")");
            assertEquals(a.getUniqueId(), tr.setter);
        }
        plugin.battle.stop(a);
    }

    @Test
    void noDiggingWhenTheyAreOnYouAndNothingDugIsKeptWhenYouGoRound() {
        PlayerMock a = player();
        a.getInventory().addItem(Items.made(design("Spike pit"), 1, 100));
        scene("raiders:pond", "fight", 2, false);   // not seen in time
        assertFalse(Travel.canDig());
        assertTrue(Travel.dig(plugin, a, "").startsWith("!"));
        Travel.scene.clear();
        scene("raiders:pond", "fight", 2, true);
        assertFalse(Travel.dig(plugin, a, "").startsWith("!"));
        assertEquals(1, Travel.dug.size());
        Travel.goRound(plugin, a);
        assertTrue(Travel.dug.isEmpty(), "left behind");
        // a pit is not set in a fight
        assertTrue(plugin.battle.setTrap(a, plugin.character(a), new String[]{"trap"}).contains("dug before one"));
    }

    @Test
    void aHuntsPitLiesPastTheAnimalsWhereTheyRun() {
        PlayerMock a = player();
        a.getInventory().addItem(Items.made(design("Spike pit"), 1, 100));
        Travel.Met m = scene("creature:boar", "sight", 1, true);
        m.hunt = true;
        assertTrue(Travel.canDig());
        Travel.dig(plugin, a, "");
        assertFalse(Travel.battleMap(plugin, a, Arena.Mode.HUNT).startsWith("!"));
        Battle.Fight f = plugin.battle.fightOf(a);
        assertEquals(1, f.traps.size());
        Location boar = server.getEntity(f.dummies.get(0)).getLocation();
        double pz = a.getLocation().getZ();
        Battle.Trap tr = f.traps.get(0);
        Location me = a.getLocation();
        assertTrue(Math.hypot(tr.x + 0.5 - me.getX(), tr.z + 0.5 - me.getZ()) > Math.hypot(boar.getX() - me.getX(), boar.getZ() - me.getZ()), "past the boar, away from the party: " + tr.x + " " + tr.z + ", boar " + boar + ", party " + me);
        plugin.battle.stop(a);
    }

    @Test
    void aHuntedBoarRunsIntoThePitAndLosesItsNextTurnClimbingOut() {
        PlayerMock a = player();
        Travel.Met m = scene("creature:boar", "sight", 1, true);
        m.hunt = true;
        assertFalse(Travel.battleMap(plugin, a, Arena.Mode.HUNT).startsWith("!"));
        Battle.Fight f = plugin.battle.fightOf(a);
        java.util.UUID u = f.dummies.get(0);
        Battle.Target t = f.targets.get(u);
        Location at = a.getLocation().clone().add(0, 0, 10).toBlockLocation().add(0.5, 0, 0.5);   // 10 blocks off (a far start can be gone at once)
        server.getEntity(u).teleport(at);
        // pits on every cell round it, dug by a master (it cannot spot them)
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            if (dx != 0 || dz != 0) f.traps.add(new Battle.Trap(Data.TRAPS.get("Spike pit"), at.getBlockX() + dx, at.getBlockY(), at.getBlockZ() + dz, a.getUniqueId(), 400, 0));
        plugin.battle.endTurnFor(a);
        enemiesTurn(f);
        assertTrue(t.inPit, "it ran into a pit: " + said(a));
        assertTrue(t.hp < t.hpMax || t.dead, "the stakes: " + t.hp + " of " + t.hpMax);
        assertFalse(t.escaped);
        Location inPit = server.getEntity(u).getLocation();
        plugin.battle.endTurnFor(a);
        enemiesTurn(f);
        assertFalse(t.inPit, "out again");
        assertEquals(inPit.getBlockX(), server.getEntity(u).getLocation().getBlockX(), "the turn went on climbing out");
        assertEquals(inPit.getBlockZ(), server.getEntity(u).getLocation().getBlockZ());
        plugin.battle.stop(a);
    }

    private void enemiesTurn(Battle.Fight f) { for (int i = 0; i < 200 && f.enemyPhase && !f.over; i++) server.getScheduler().performTicks(20); }

    @Test
    void layingFindsGroundOnTheWay() {
        Arena.Layout l = Arena.build(world, "field", 5, 128, 20);
        Data.TrapDef pit = Data.TRAPS.get("Spike pit");
        java.util.List<Travel.Dug> dug = java.util.List.of(new Travel.Dug(pit, java.util.UUID.randomUUID(), 40), new Travel.Dug(pit, java.util.UUID.randomUUID(), 40),
                new Travel.Dug(pit, java.util.UUID.randomUUID(), 40));
        double px = l.x0() + 64, pz = l.z0() + 10, ex = l.x0() + 64, ez = l.z0() + 40;
        java.util.List<Battle.Trap> laid = Arena.layDug(world, l, px, pz, l.y0() + 1, ex, ez, false, dug, java.util.Set.of());
        assertEquals(3, laid.size());
        java.util.Set<String> cells = new java.util.HashSet<>();
        for (Battle.Trap t : laid) {
            assertTrue(Math.abs(t.z - (pz + ez) / 2) <= 5, "half-way: " + t.z);
            assertTrue(cells.add(t.x + "," + t.z), "each on its own cell");
            assertTrue(world.getBlockAt(t.x, t.y, t.z).isPassable() && world.getBlockAt(t.x, t.y - 1, t.z).getType().isSolid(), "on the ground");
        }
        java.util.List<Battle.Trap> hunt = Arena.layDug(world, l, px, pz, l.y0() + 1, ex, ez, true, dug.subList(0, 1), java.util.Set.of());
        assertTrue(hunt.get(0).z >= ez + Arena.HUNT_PAST - 1, "past them: " + hunt.get(0).z);
        // an animal at the map's edge: the pit short of the edge, never under its feet
        double wx = l.x0() + 3.5;
        java.util.List<Battle.Trap> edge = Arena.layDug(world, l, px, ez, l.y0() + 1, wx, ez, true, dug.subList(0, 1), java.util.Set.of(((long) (int) Math.floor(wx) << 32) ^ ((int) Math.floor(ez) & 0xffffffffL)));
        assertTrue(edge.get(0).x >= l.x0() + 2 && edge.get(0).x < wx, "between it and the edge: " + edge.get(0).x);
        assertEquals(Arena.HUNT_PAST - 1, Arena.toEdge(l, l.x0() + 4 + Arena.HUNT_PAST - 1, l.z0() + 64, -1, 0) - 2, 1e-9);
    }

    // ------------------------------------------------------------------ the dart
    @Test
    void anAsleepCreatureIsOutOfTheFightAndTakenAliveAfterIt() {
        Arena.Foe boar = Arena.FOES.get("boar");
        Battle.Target t = new Battle.Target("Boar", 30, boar.ac(), boar.dt(), boar.dr(), boar.a(), boar.skill());
        t.melee = true; t.foe = "boar"; t.meatKg = boar.meatKg(); t.hides = boar.hides();
        assertTrue(t.inFight());
        t.out = true; t.asleep = true;
        assertFalse(t.inFight(), "asleep: beaten, the fight can end");
        assertTrue(t.label().contains("asleep"), t.label());
        Battle.Fight f = new Battle.Fight("the enemies");
        f.targets.put(java.util.UUID.randomUUID(), t);
        assertEquals(1, Loot.collect(f, new Random(1)));
        Loot.Body x = Loot.bodies.get(0);
        assertTrue(x.asleep && !x.dead && !x.trail);
        assertEquals("asleep", x.state());
        PlayerMock a = player();
        String r = Loot.takeAlive(plugin, a, x);
        assertTrue(r.contains("alive") && r.contains("pens or the lab"), r);
        assertTrue(x.taken);
        assertEquals("taken alive", x.state());
        assertTrue(Loot.takeAlive(plugin, a, x).startsWith("!"), "once");
    }

    @Test
    void oneThatRanOffDartedIsTrackedAndFoundAsleep() {
        Arena.Foe boar = Arena.FOES.get("boar");
        Battle.Target t = new Battle.Target("Boar", 30, boar.ac(), boar.dt(), boar.dr(), boar.a(), boar.skill());
        t.melee = true; t.foe = "boar"; t.escaped = true; t.sedatedAt = 40;
        Battle.Target clean = new Battle.Target("Boar 2", 30, boar.ac(), boar.dt(), boar.dr(), boar.a(), boar.skill());
        clean.melee = true; clean.foe = "boar"; clean.escaped = true;
        Battle.Fight f = new Battle.Fight("the enemies");
        f.targets.put(java.util.UUID.randomUUID(), t);
        f.targets.put(java.util.UUID.randomUUID(), clean);
        assertEquals(1, Loot.collect(f, new Random(1)), "only the darted one left a trail");
        Loot.Body x = Loot.bodies.get(0);
        assertTrue(x.trail);
        assertTrue(x.state().contains("a trail to follow"), x.state());
        PlayerMock a = player();
        Character c = plugin.character(a);
        // no Survival: the trail is lost
        c.points[Rules.skillIndex("Survival")] -= 400;
        assertTrue(Loot.track(plugin, a, x).contains("lose"));
        assertTrue(x.lost && !x.asleep);
        assertEquals("the trail lost", x.state());
        // with it: found asleep, the check's XP and its kill XP
        Loot.collect(f, new Random(1));
        Loot.Body y = Loot.bodies.get(0);
        c.points[Rules.skillIndex("Survival")] += 800;
        long xp0 = c.xp;
        String r = Loot.track(plugin, a, y);
        assertTrue(r.contains("asleep"), r);
        assertTrue(y.asleep);
        assertTrue(c.xp - xp0 >= Loot.TRAIL_NEED + Math.round(Rules.killXp(30) * 0.99), "the check and the kill: " + (c.xp - xp0));
        assertFalse(Loot.takeAlive(plugin, a, y).startsWith("!"));
    }
}
