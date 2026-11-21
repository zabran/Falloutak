package cz.lollywood.falloutak;

import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The battle demo played on the mock server (05 BATTLE, the controls of 02d §5): the hotbar's clicks as events, the raiders' turn run
 * by the scheduler's ticks. The dice are real, so the tests check what every outcome must keep (AP paid, the turn passing, the end).
 */
class BattleTest extends MockGame {
    /** A click with the hotbar slot in hand (the demo's 150 ms guard against a double click is reset first). */
    void use(PlayerMock p, int slot, Action a) {
        Battle.State s = plugin.battle.state(p);
        if (s != null) { s.lastLeft = 0; s.lastRight = 0; }
        p.getInventory().setHeldItemSlot(slot);
        server.getPluginManager().callEvent(new PlayerInteractEvent(p, a, p.getInventory().getItemInMainHand(), null, BlockFace.SELF, EquipmentSlot.HAND));
    }

    PlayerMock facingSouth() {
        PlayerMock p = player();
        Location l = p.getLocation();
        l.setYaw(0);   // south, +z: the demo's raiders stand 3 and 8 blocks ahead
        l.setPitch(0);
        p.teleport(l);
        return p;
    }

    /** Runs the raiders' turn to its end (their steps are ticks apart). */
    void raidersTurn(Battle.State s) {
        for (int i = 0; i < 100 && s.enemyTurn && !s.over; i++) server.getScheduler().performTicks(20);
    }

    @Test
    void aRoundOfTheDemo() {
        PlayerMock p = facingSouth();
        Character c = plugin.character(p);
        p.performCommand("sheet battle");
        Battle.State s = plugin.battle.state(p);
        assertNotNull(s);
        assertEquals(2, s.targets.size());                                        // two target raiders
        assertEquals(c.apMax(), s.ap);
        said(p);
        server.getScheduler().performTicks(2);
        use(p, 0, Action.LEFT_CLICK_AIR);                                          // the SMG at the close raider
        String shot = said(p);
        assertTrue(s.ap < c.apMax(), "a shot costs AP: " + shot);
        int ap = s.ap;
        use(p, 0, Action.RIGHT_CLICK_AIR);                                         // the next fire mode: no AP
        assertEquals(ap, s.ap);
        use(p, 8, Action.RIGHT_CLICK_AIR);                                         // the clock: end the turn
        assertTrue(s.enemyTurn || s.over);
        use(p, 0, Action.LEFT_CLICK_AIR);                                          // the raiders' turn: the player waits
        said(p);
        int hp0 = c.hp;
        raidersTurn(s);
        String turn = said(p);
        if (!s.over && plugin.battle.inBattle(p)) {
            assertFalse(s.enemyTurn, "the raiders' turn ended");
            assertEquals(2, s.round);
            assertEquals(c.apMax() - Math.max(0, s.lostTurns), s.ap, 1e-9 + c.apMax());
        } else if (!s.over) {
            // the raiders killed him in their turn (the dice are real: it happens now and then): the demo ended by itself and gave
            // the character back as it began. The test once sent /sheet battle here, which STARTED a new demo (the command toggles)
            assertTrue(turn.contains("The demo ends; your character is restored as it began."), turn);
            assertEquals(hp0, c.hp);
        }
        if (plugin.battle.inBattle(p)) p.performCommand("sheet battle");          // stop: the inventory comes back
        assertFalse(plugin.battle.inBattle(p));
    }

    @Test
    void theDemoEndsByItselfWhenTheRaidersKillThePlayer() {
        // the other branch of aRoundOfTheDemo, played every run: one HP left and no Endurance to spare, so the raiders' first hit kills
        PlayerMock p = facingSouth();
        Character c = plugin.character(p);
        p.performCommand("sheet battle");
        Battle.State s = plugin.battle.state(p);
        int hp0 = c.hp;
        for (int t = 0; t < 30 && plugin.battle.inBattle(p); t++) {
            c.hp = -c.attrs(false)[Rules.E];                                       // at the edge of clinical death
            use(p, 8, Action.RIGHT_CLICK_AIR);
            raidersTurn(s);
        }
        assertFalse(plugin.battle.inBattle(p), "the raiders hit him in 30 turns");
        assertTrue(said(p).contains("The demo ends; your character is restored as it began."));
        assertEquals(hp0, c.hp);
        assertNotNull(p.getInventory());
    }

    @Test
    void untilTheRaidersCannotFight() {
        PlayerMock p = facingSouth();
        Character c = plugin.character(p);
        c.base[Rules.A] = 10;                                                      // a good shot, so the demo ends in a few rounds
        long xp0 = c.xp;
        p.performCommand("sheet battle");
        Battle.State s = plugin.battle.state(p);
        Set<String> heard = new HashSet<>();
        Sfx.recorder = (k, who) -> heard.add(k);
        int deaths = 0;
        for (int round = 0; round < 100 && !s.over; round++) {                     // random hits: 40 rounds were sometimes too few
            if (!plugin.battle.inBattle(p)) {
                // a raider's crit can kill him outright even at full HP (the head destroyed), and the demo ends by itself: the test
                // is about the raiders' end, so a new demo starts (this was the test's flakiness, 11-21)
                deaths++;
                p.performCommand("sheet battle");
                s = plugin.battle.state(p);
            }
            for (int k = 0; k < 4 && s.ap > 0 && !s.enemyTurn; k++) use(p, 0, Action.LEFT_CLICK_AIR);
            if (!plugin.battle.inBattle(p) || s.over) continue;
            use(p, 8, Action.RIGHT_CLICK_AIR);
            raidersTurn(s);
            if (!plugin.battle.inBattle(p)) continue;
            // keep him standing (the test is about the raiders' end): HP, and Pain, whose 75+ stage sets Agility −99 (the workbook's)
            c.hp = c.hpMax();
            c.bleeding = 0;
            c.survival.put(Rules.Stat.PAIN, 0.0);
            // and his gun whole: a long fight wears it out (0.2 a round, ~395 rounds) or a fumble destroys it, and the demo never ends
            s.health.clear();
            s.broken.clear();
        }
        assertTrue(s.over || s.targets.values().stream().noneMatch(Battle::canFight), "the fight ends when no raider can fight (deaths " + deaths + ")");
        // part 6: each raider beaten gives his kill XP (3.1 x 30^1.09 = 126) to the one player
        if (s.over) assertEquals(xp0 + 126L * s.targets.size(), c.xp);
        Sfx.recorder = null;
        // every action is heard (Marek 11-19): his shots, the hits or misses, the clock, his turns, the raiders' shots and the end
        assertTrue(heard.containsAll(List.of("pistol.single", "turn.end", "turn.start", "combat.end")), heard.toString());
        assertTrue(heard.contains("bullet.flesh") || heard.contains("bullet.miss"), heard.toString());
        assertTrue(heard.stream().anyMatch(k -> k.endsWith(".male")), "the raiders' bodies: " + heard);
    }

    @Test
    void theBoundPerksInPlay() {   // Marek 11-20: "how will it work mechanically?"
        PlayerMock p = facingSouth();
        Character c = plugin.character(p);
        c.level = 9;
        c.points[Rules.skillIndex("Guns")] += 150;
        assertTrue(c.takePerk(Data.PERKS.get("Weapon Master"), "Guns").contains("taken"));
        assertTrue(c.takePerk(Data.PERKS.get("Nemesis"), "Raiders").contains("taken"));
        assertTrue(c.takePerk(Data.PERKS.get("Signature Weapon"), Battle.SMG.name).contains("taken"));
        p.performCommand("sheet battle");
        Battle.State s = plugin.battle.state(p);
        said(p);
        server.getScheduler().performTicks(2);
        int ap = s.ap;
        use(p, 0, Action.LEFT_CLICK_AIR);                                              // the SMG's single shot at the close raider
        String shot = said(p);
        assertEquals(ap - (Battle.SMG.modes.get(0).ap() - 1), s.ap, shot);             // Weapon Master: -1 AP
        assertTrue(shot.contains("+15 Signature Weapon") && shot.contains("+15 Nemesis"), shot);
        assertEquals(Battle.SMG.maxHealth, s.health.getOrDefault(Battle.SMG.name, Battle.SMG.maxHealth), 1e-9);   // it does not wear
        p.performCommand("sheet battle");
    }

    @Test
    void quickItems() {
        PlayerMock p = facingSouth();
        Character c = plugin.character(p);
        p.performCommand("sheet battle");
        Battle.State s = plugin.battle.state(p);
        c.hp = c.hpMax() - 15;
        use(p, 2, Action.RIGHT_CLICK_AIR);                                         // the Stimpak: 2 AP, HP back
        assertEquals(c.apMax() - 2, s.ap);
        assertTrue(c.hp > c.hpMax() - 15);
        int before = s.ap;
        use(p, 3, Action.RIGHT_CLICK_AIR);                                         // a frag grenade at what he aims at: 4 AP
        assertTrue(s.ap <= before - 4 || s.ap == 0, said(p));                      // (a miss can land at his feet: Pain, a lower maximum)
        p.performCommand("sheet battle");
    }
}
