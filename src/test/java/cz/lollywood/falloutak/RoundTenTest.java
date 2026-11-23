package cz.lollywood.falloutak;

import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Zombie;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.66.0, `09b` round 10 (Marek 11-23, verbatim): *"raiders walking into anomalies - it should happen and they should get affected by the
 * anomaly."* · *"the mirage - I think monsters and other people would work there. The audio halucination should also play sounds.
 * Raiders inside could get insanity effect."* · *"The Black Rain's numbers - yes, but it should first give some warning time before it
 * starts, so that players can find somewhere to hide."*
 */
class RoundTenTest extends MockGame {
    void use(PlayerMock p, int slot, Action a) {
        Battle.State s = plugin.battle.state(p);
        if (s != null) { s.lastLeft = 0; s.lastRight = 0; }
        p.getInventory().setHeldItemSlot(slot);
        server.getPluginManager().callEvent(new PlayerInteractEvent(p, a, p.getInventory().getItemInMainHand(), null, BlockFace.SELF, EquipmentSlot.HAND));
    }

    PlayerMock facingSouth() {
        PlayerMock p = player();
        Location l = p.getLocation();
        l.setYaw(0);
        l.setPitch(0);
        p.teleport(l);
        return p;
    }

    void raidersTurn(Battle.State s) {
        for (int i = 0; i < 100 && s.enemyTurn && !s.over; i++) server.getScheduler().performTicks(20);
    }

    Zombie mob(double x, double z) { return world.spawn(new Location(world, x, 4, z), Zombie.class); }

    static Zones.Zone zone(String id, String kind, double x, double y, double z, double r) {
        Zones.Zone a = new Zones.Zone();
        a.id = id; a.world = "world"; a.ball = true; a.x1 = x; a.y1 = y; a.z1 = z; a.r = r; a.anomaly = kind;
        return a;
    }

    // ------------------------------------------------------------------ the raiders and the anomalies
    @Test
    void aRaiderNoticesByHisPerceptionAndNeverTheBlindSpot() {
        Raiders r = new Raiders(plugin.battle);
        Zombie le = mob(0.5, 0.5);
        plugin.zones.anomaly(new Location(world, 5.5, 5, 0.5), 2, "heat");
        plugin.zones.anomaly(new Location(world, -5.5, 5, 0.5), 2, "blind");
        Battle.Target sharp = new Battle.Target("Sharp"), dull = new Battle.Target("Dull");
        sharp.a[Rules.P] = 10;   // d100 ≤ 100: he sees every tell
        dull.a[Rules.P] = 0;
        r.notice(le, sharp);
        r.notice(le, dull);
        assertEquals(1, sharp.noticed.size(), "the heat, never the blind spot");
        assertEquals(1, sharp.missed.size());
        assertEquals(2, dull.missed.size());
        assertTrue(r.avoids(sharp, "", world, 5, 4, 0), "he walks round the heat he saw");
        assertFalse(r.avoids(dull, "", world, 5, 4, 0), "the one who missed it walks in");
        String heatId = sharp.noticed.iterator().next();
        assertFalse(r.avoids(sharp, heatId, world, 5, 4, 0), "the one he stands in he may walk out through");
    }

    @Test
    void walkingInHitsTheRaiderUnlessLuckJerksHimBack() {
        PlayerMock p = player();
        Raiders r = new Raiders(plugin.battle);
        Zombie le = mob(5.5, 0.5);
        Zones.Zone heat = plugin.zones.anomaly(new Location(world, 5.5, 5, 0.5), 2, "heat");
        Battle.Target t = new Battle.Target("Raider");
        t.a[Rules.L] = 0;
        String s = r.enter(le, t, heat, new Path.P(1, 4, 0), p);
        assertTrue(s.startsWith("heat: the heat, "), s);
        assertTrue(t.hp < t.hpMax);
        Battle.Target lucky = new Battle.Target("Lucky");
        lucky.a[Rules.L] = 50;   // 2 × 50 %: always
        assertTrue(r.enter(le, lucky, heat, new Path.P(1, 4, 0), p).contains("LUCKY ESCAPE"));
        assertEquals(lucky.hpMax, lucky.hp);
        assertEquals(1, le.getLocation().getBlockX(), "jerked back to where he came from");
    }

    @Test
    void eachKindActsOnARaidersBody() {
        Raiders r = new Raiders(plugin.battle);
        Zombie le = mob(0.5, 0.5);
        Battle.Target t = new Battle.Target("Raider");
        assertTrue(r.hit(le, t, zone("a", "throw", 0.5, 4.5, 0.5, 2), true).startsWith("the throw hurls him"));
        assertTrue(t.down && t.hp < t.hpMax);
        Battle.Target c = new Battle.Target("Cold");
        r.hit(le, c, zone("b", "cold", 0.5, 4.5, 0.5, 2), true);
        assertEquals(2, c.apLoss, "numb: AP −2 next turn");
        Battle.Target l = new Battle.Target("Lightning");
        r.hit(le, l, zone("c", "lightning", 0.5, 4.5, 0.5, 2), true);
        assertEquals(4, l.apLoss);
        assertTrue(l.hp <= l.hpMax - 4, "4d8, no armour against it");
        Battle.Target f = new Battle.Target("Floating");
        r.hit(le, f, zone("d", "lift", 0.5, 4.5, 0.5, 2), true);
        assertTrue(f.held, "the lift: he hangs");
        Battle.Target bl = new Battle.Target("Bleeding");
        bl.bleeding = 2;
        assertTrue(r.hit(le, bl, zone("e", "time", 0.5, 4.5, 0.5, 2), true).contains("bleeds out"));
        assertTrue(bl.dead && bl.frozen);
        Battle.Target ok = new Battle.Target("Frozen");
        assertTrue(r.hit(le, ok, zone("e", "time", 0.5, 4.5, 0.5, 2), true).contains("frozen"));
        assertTrue(ok.frozen && !ok.dead);
        Zombie far = mob(3.5, 0.5);   // the pull, off its heart: a block toward it
        r.hit(far, new Battle.Target("Pulled"), zone("f", "pull", 0.5, 4.5, 0.5, 4), false);
        assertEquals(2, far.getLocation().getBlockX());
    }

    @Test
    void aRaiderStandingInAnAnomalyTakesItsRoundAtTheStartOfHisTurn() {
        PlayerMock p = facingSouth();
        p.performCommand("sheet battle");
        Battle.State s = plugin.battle.state(p);
        UUID near = s.dummies.get(0);
        Entity e = server.getEntity(near);
        Battle.Target t = s.targets.get(near);
        plugin.zones.anomaly(e.getLocation().add(0, 1, 0), 1, "heat");
        said(p);
        use(p, 8, Action.RIGHT_CLICK_AIR);   // end the turn
        raidersTurn(s);
        String turn = said(p);
        assertTrue(turn.contains(t.name + " in heat: the heat, "), turn);
        assertTrue(t.hp < t.hpMax || t.dead);
        if (plugin.battle.inBattle(p)) p.performCommand("sheet battle");
    }

    @Test
    void inTheMirageARaiderLosesHisHeadByHisPerception() {
        PlayerMock p = facingSouth();
        p.performCommand("sheet battle");
        Battle.State s = plugin.battle.state(p);
        Battle.Target mad = s.targets.get(s.dummies.get(0)), calm = s.targets.get(s.dummies.get(1));
        mad.a[Rules.P] = 0;     // 10 × 0 − 20: never keeps it
        calm.a[Rules.P] = 12;   // 10 × 12 − 20 = 100: always
        calm.broken = true;     // he goes first (the higher Perception) and must not end the demo by killing the player
        for (UUID u : s.dummies) plugin.zones.anomaly(server.getEntity(u).getLocation().add(0, 1, 0), 1, "mirage");
        said(p);
        use(p, 8, Action.RIGHT_CLICK_AIR);
        raidersTurn(s);
        String turn = said(p);
        assertTrue(turn.contains(mad.name + " loses his head in the mirage"), turn);
        assertTrue(turn.contains(calm.name + " keeps his head in the mirage"), turn);
        if (plugin.battle.inBattle(p)) p.performCommand("sheet battle");
    }

    // ------------------------------------------------------------------ the mirage's figures and the echo's sounds
    @Test
    void theMirageShowsItsFiguresOnlyToTheOneWhoDidNotSeeThrough() {
        PlayerMock p = player(0.5, 0.5, false), other = player(40.5, 40.5, false);
        Character c = plugin.character(p);
        c.base[Rules.P] = 1;   // 10 − 20: he never sees through
        Zones.Zone z = plugin.zones.anomaly(new Location(world, 0.5, 5, 0.5), 3, "mirage");
        said(p);
        plugin.hud.tick();
        assertTrue(said(p).contains("Something moves at the edge of your sight."), "not told it is a mirage");
        Entity fig = null;
        for (int i = 0; i < 200 && fig == null; i++) {
            plugin.anomalies.mirage(p, z);
            List<UUID> mine = plugin.anomalies.figures.get(p.getUniqueId());
            if (mine != null && !mine.isEmpty()) fig = server.getEntity(mine.get(0));
        }
        assertNotNull(fig, "a figure shows");
        assertTrue(Anomalies.figure(fig));
        assertTrue(Anomalies.seenBy(fig, p));
        assertFalse(Anomalies.seenBy(fig, other), "nobody else sees it");
        p.teleport(new Location(world, 20.5, 4, 20.5));   // out of the mirage: they vanish
        plugin.hud.tick();
        assertNull(plugin.anomalies.figures.get(p.getUniqueId()));
        assertTrue(fig.isDead() || !fig.isValid());
        // a sharp one sees through it and is told so; no figures for him
        PlayerMock q = player(0.5, 0.5, false);
        plugin.character(q).base[Rules.P] = 12;
        said(q);
        plugin.hud.tick();
        assertTrue(said(q).contains("You see through it"));
        for (int i = 0; i < 50; i++) plugin.anomalies.mirage(q, z);
        assertNull(plugin.anomalies.figures.get(q.getUniqueId()));
    }

    @Test
    void aShotAtAFigureIsSpentOnNothingAndAnothersFigureIsNotThere() {
        PlayerMock p = facingSouth();
        Character c = plugin.character(p);
        p.performCommand("sheet battle");
        Battle.State s = plugin.battle.state(p);
        LivingEntity fig = plugin.anomalies.spawnFigure(p, new Location(world, 0.5, 4, 1.8), Zombie.class);
        assertNotNull(fig);
        said(p);
        server.getScheduler().performTicks(2);
        use(p, 0, Action.LEFT_CLICK_AIR);
        String shot = said(p);
        assertTrue(shot.contains("NOTHING IS THERE"), shot);
        assertTrue(s.ap < c.apMax(), "the AP are spent");
        assertTrue(fig.isDead() || !fig.isValid(), "it dissolves");
        PlayerMock other = player(30.5, 30.5, false);
        plugin.anomalies.spawnFigure(other, new Location(world, 0.5, 4, 1.8), Zombie.class);
        s.ap = s.apAtAnchor = c.apMax();
        s.legs.clear();
        s.legs.add(new Battle.Leg(0, p.getLocation().toBlockLocation()));
        use(p, 0, Action.LEFT_CLICK_AIR);
        String next = said(p);
        assertFalse(next.contains("NOTHING IS THERE"), "another's figure is not there for him: " + next);
        if (plugin.battle.inBattle(p)) p.performCommand("sheet battle");
    }

    @Test
    void theEchoPlaysItsSoundsToItsListenerAlone() {
        PlayerMock p = player(0.5, 0.5, false), far = player(60.5, 60.5, false);
        Character c = plugin.character(p);
        p.clearSounds();
        far.clearSounds();
        for (int i = 0; i < 60; i++) plugin.anomalies.echo(p, c);
        assertFalse(p.getHeardSounds().isEmpty(), "day D's voices");
        assertTrue(far.getHeardSounds().isEmpty());
    }

    @Test
    void theBlindSpotHidesWhoIsInsideFromWhoIsOutside() {
        plugin.zones.anomaly(new Location(world, 0.5, 5, 0.5), 2, "blind");
        Location in = new Location(world, 0.5, 4, 0.5), out = new Location(world, 10.5, 4, 0.5);
        assertTrue(plugin.anomalies.hidden(in, out));
        assertFalse(plugin.anomalies.hidden(in, in.clone().add(0.5, 0, 0)), "both inside");
        assertFalse(plugin.anomalies.hidden(out, in));
    }

    // ------------------------------------------------------------------ the Black Rain's warning
    @Test
    void theBlackRainCountsDownThenBreaksByItself() {
        PlayerMock gm = player(0.5, 0.5, true), out = player(10.5, 10.5, false);
        Character c = plugin.character(out);
        String s = Menus.rainCommand(gm, new String[]{"start", "3", "5"});
        assertTrue(s.contains("10 rounds of warning (5 s if nobody stays in rounds)"), s);
        assertEquals(1, plugin.anomalies.rainPhase);
        assertTrue(plugin.battle.inBattle(out), "round 11: every player into rounds");
        assertTrue(plugin.anomalies.rainNote(out, c).contains("THE BLACK RAIN in 10 rounds"));
        said(out);
        for (int i = 0; i < 4; i++) plugin.anomalies.rainTick();
        assertEquals(1, plugin.anomalies.rainPhase, "still the warning");
        String signs = said(out);
        assertTrue(signs.contains("Every radio") && signs.contains("The ravens rise") && signs.contains("The sky in the west"), signs);
        plugin.anomalies.rainTick();
        assertEquals(1, plugin.anomalies.rainPhase, "in rounds the warning is ten of them");
        plugin.zones.rounds(Anomalies.WARN_ROUNDS);
        plugin.anomalies.rainTick();
        assertEquals(2, plugin.anomalies.rainPhase, "it breaks");
        assertTrue(said(out).contains("THE BLACK RAIN."));
        assertTrue(Menus.rainCommand(gm, new String[]{"stop"}).startsWith("The Black Rain is over"));
    }

    @Test
    void aFightHoldsTheCountdownForTenRounds() {
        PlayerMock gm = player(0.5, 0.5, true), fighter = facingSouth();
        fighter.performCommand("sheet battle");
        assertTrue(plugin.battle.inBattle(fighter));
        Menus.rainCommand(gm, new String[]{"start", "3", "2"});
        for (int i = 0; i < 5; i++) plugin.anomalies.rainTick();
        assertEquals(1, plugin.anomalies.rainPhase, "a fight's minute is ten rounds");
        plugin.zones.rounds(Anomalies.WARN_ROUNDS);
        plugin.anomalies.rainTick();
        assertEquals(2, plugin.anomalies.rainPhase);
        assertTrue(Menus.rainCommand(gm, new String[]{"start", "3", "0"}).startsWith("The Black Rain falls"), "0 = at once");
        fighter.performCommand("sheet battle");
    }
}
