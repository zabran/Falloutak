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
        raidersTurn(s);
        if (!s.over && plugin.battle.inBattle(p)) {
            assertFalse(s.enemyTurn, "the raiders' turn ended");
            assertEquals(2, s.round);
            assertEquals(c.apMax() - Math.max(0, s.lostTurns), s.ap, 1e-9 + c.apMax());
        }
        p.performCommand("sheet battle");                                          // stop: the inventory comes back
        assertFalse(plugin.battle.inBattle(p));
    }

    @Test
    void untilTheRaidersCannotFight() {
        PlayerMock p = facingSouth();
        Character c = plugin.character(p);
        c.base[Rules.A] = 10;                                                      // a good shot, so the demo ends in a few rounds
        p.performCommand("sheet battle");
        Battle.State s = plugin.battle.state(p);
        Set<String> heard = new HashSet<>();
        Sfx.recorder = (k, who) -> heard.add(k);
        for (int round = 0; round < 40 && plugin.battle.inBattle(p) && !s.over; round++) {
            for (int k = 0; k < 4 && s.ap > 0 && !s.enemyTurn; k++) use(p, 0, Action.LEFT_CLICK_AIR);
            if (!plugin.battle.inBattle(p) || s.over) break;
            use(p, 8, Action.RIGHT_CLICK_AIR);
            raidersTurn(s);
            // keep him standing (the test is about the raiders' end): HP, and Pain, whose 75+ stage sets Agility −99 (the workbook's)
            c.hp = c.hpMax();
            c.bleeding = 0;
            c.survival.put(Rules.Stat.PAIN, 0.0);
        }
        assertTrue(s.over || s.targets.values().stream().noneMatch(Battle::canFight), "the fight ends when no raider can fight");
        Sfx.recorder = null;
        // every action is heard (Marek 11-19): his shots, the hits or misses, the clock, his turns, the raiders' shots and the end
        assertTrue(heard.containsAll(List.of("pistol.single", "turn.end", "turn.start", "combat.end")), heard.toString());
        assertTrue(heard.contains("bullet.flesh") || heard.contains("bullet.miss"), heard.toString());
        assertTrue(heard.stream().anyMatch(k -> k.endsWith(".male")), "the raiders' bodies: " + heard);
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
        use(p, 3, Action.RIGHT_CLICK_AIR);                                         // a frag grenade at what he aims at
        assertTrue(s.ap <= c.apMax() - 2, said(p));
        p.performCommand("sheet battle");
    }
}
