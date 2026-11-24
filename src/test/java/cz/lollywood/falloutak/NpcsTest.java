package cz.lollywood.falloutak;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

/** THE GM'S NPC TOOLS (Marek 11-24: follow players, hostile, not hostile, join the player's faction): `Npcs`. */
class NpcsTest extends MockGame {

    Villager figurant(String name, double x, double z) {
        plugin.spawnFigurant(new Location(world, x, 4, z), name, "Human");
        for (Entity e : world.getEntities()) if (name.equals(Items.npcName(e))) return (Villager) e;
        fail("no figurant " + name);
        return null;
    }

    @Test
    void aFollowerWalksWhenFarJumpsWhenVeryFarAndStaysWhenClose() {
        assertEquals(Npcs.Step.STAY, Npcs.step(2.5, true));
        assertEquals(Npcs.Step.WALK, Npcs.step(10, true));
        assertEquals(Npcs.Step.JUMP, Npcs.step(40, true));
        assertEquals(Npcs.Step.JUMP, Npcs.step(1, false));     // another world
    }

    @Test
    void aStatblockBecomesAFigureOnItsSide() {
        Character c = Character.blank("Bob", "Human");
        int[] a = c.attrs(false);
        Battle.Target h = Npcs.target("Bob", c, Npcs.Side.HOSTILE);
        assertEquals(c.hpMax(), h.hpMax);
        assertEquals(Rules.ac(a) + c.armourAc(), h.ac);
        assertEquals((int) Math.round(c.skill("Guns")), h.skill);
        assertFalse(h.ally || h.talking || h.byParty);
        Battle.Target n = Npcs.target("Bob", c, Npcs.Side.NEUTRAL);
        assertTrue(n.talking && !n.ally);
        Battle.Target p = Npcs.target("Bob", c, Npcs.Side.PARTY);
        assertTrue(p.ally && p.paid && !p.talking);                  // the party's own pay no kill XP
        assertNotEquals(h.group, p.group);
        assertNotEquals(h.group, n.group);
        c.hp = 5;
        assertEquals(5, Npcs.target("Bob", c, Npcs.Side.HOSTILE).hp);
        p.hp = -3; p.dead = true;
        Npcs.writeBack(p, c);
        assertEquals(0, c.hp);
        assertTrue(c.dead);
    }

    @Test
    void theGmSetsASideFromTheFigurantsMenu() {
        PlayerMock gm = player();
        Villager v = figurant("Bob", 3, 0);
        assertEquals(Npcs.Side.NEUTRAL, Npcs.side(v));                // a new figurant holds
        Menus.gmOn(gm, Menus.whoOf(v));
        click("Side: neutral");
        click("hostile");
        assertEquals(Npcs.Side.HOSTILE, Npcs.side(v));
        assertTrue(said(gm).contains("Bob is now hostile"));
    }

    @Test
    void aFigurantFollowsAPlayerAndJumpsToHimWhenFar() {
        PlayerMock gm = player();
        PlayerMock p = player(60.5, 0.5, false);
        Villager v = figurant("Bob", 3, 0);
        Menus.gmOn(gm, Menus.whoOf(v));
        click("Follow a player");
        click(p.getName());
        assertEquals(p.getUniqueId(), Npcs.leader(v));
        Npcs.tick(plugin);
        assertTrue(v.getLocation().distance(p.getLocation()) < 4, "jumped behind him: " + v.getLocation());
        Menus.gmOn(gm, Menus.whoOf(v));
        click("Stop following " + p.getName());
        assertNull(Npcs.leader(v));
    }

    @Test
    void joiningAFactionPutsItOnThePartysSideWithHisFactionAndFollowing() {
        PlayerMock gm = player();
        PlayerMock p = player(5.5, 0.5, false);
        plugin.character(p).group = "Pirates";
        Villager v = figurant("Bob", 3, 0);
        Menus.gmOn(gm, Menus.whoOf(v));
        click("Join a player's faction");
        click(p.getName());
        assertEquals(Npcs.Side.PARTY, Npcs.side(v));
        assertEquals(p.getUniqueId(), Npcs.leader(v));
        assertEquals("Pirates", plugin.npc("Bob").group);
    }

    @Test
    void aFightHereNeedsAHostileFigurant() {
        PlayerMock gm = player();
        figurant("Bob", 3, 0);
        assertTrue(Npcs.fightHere(plugin, gm, false).startsWith("! no hostile figurant"));
        assertFalse(plugin.battle.inBattle(gm));
    }

    @Test
    void aFightHerePutsEachFigurantOnItsSideAndNeutralsNeitherFightNorHoldItOpen() {
        PlayerMock gm = player();
        Villager h = figurant("Raider", 6, 0), n = figurant("Farmer", 0, 6), a = figurant("Guard", -4, 0);
        Npcs.setSide(h, "Raider", Npcs.Side.HOSTILE);
        Npcs.setSide(a, "Guard", Npcs.Side.PARTY);
        String r = Npcs.fightHere(plugin, gm, false);
        assertTrue(r.startsWith("THE FIGHT HERE: 1 hostile, 1 on your side, 1 neutral"), r);
        assertTrue(plugin.battle.inBattle(gm));
        Battle.State s = plugin.battle.state(gm);
        Battle.Fight f = s.fight;
        Battle.Target th = f.targets.get(h.getUniqueId()), tn = f.targets.get(n.getUniqueId()), ta = f.targets.get(a.getUniqueId());
        assertTrue(ta.ally && !th.ally && !th.talking);
        assertTrue(Battle.neutral(f, tn));
        assertFalse(plugin.battle.checkOver(gm, s, plugin.character(gm)));    // the hostile one still fights
        th.hp = -20; th.dead = true;                                            // beaten: the neutral one does not hold the fight open
        assertTrue(plugin.battle.checkOver(gm, s, plugin.character(gm)));
    }

    @Test
    void attackingANeutralFigureTurnsItsGroupHostile() {
        PlayerMock gm = player();
        Villager h = figurant("Raider", 6, 0), n = figurant("Farmer", 0, 6);
        Npcs.setSide(h, "Raider", Npcs.Side.HOSTILE);
        Npcs.fightHere(plugin, gm, false);
        Battle.Fight f = plugin.battle.state(gm).fight;
        Battle.Target tn = f.targets.get(n.getUniqueId());
        plugin.battle.provoke(f, tn.group, gm);
        assertFalse(tn.talking);
        assertFalse(Battle.neutral(f, tn));
    }
}
