package cz.lollywood.falloutak;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.64.0, `09b` round 9 on the mock server (Marek 11-23, verbatim: *"the glowing one irradiating others - yes, it is a radiation source and
 * is losing rads."* · *"The Mývalové - poison resistance 50% and add radiation resistance. Set P +3 and A +4 - these 2 are all they are
 * about. I agree about the rest."* · *"Should I build it into the plugin, locked behind their faction? - yes."*).
 */
class RoundNineTest extends MockGame {
    @Test
    void aGlowingPlayerIrradiatesTheOnesWithinFiveBlocks() {
        PlayerMock glow = player(0.5, 0.5, false), near = player(3.5, 0.5, false), far = player(9.5, 0.5, false);
        Character g = plugin.character(glow);
        g.applyTemplate("Ghoul");
        g.ghoulAdvance(3, Part3Test.fixed(0.5, 1));
        g.survival.put(Rules.Stat.RADS, 800.0);
        assertEquals(8, plugin.hud.glowNear(near), 1e-9, "3 blocks away: what it sheds an hour");
        assertEquals(0, plugin.hud.glowNear(far), 1e-9, "9 blocks away: nothing");
        assertEquals(0, plugin.hud.glowNear(glow), 1e-9, "not onto itself");
        near.teleport(new Location(world, 0.5, 4, 5.0));                  // 4.5 blocks: still within the reach
        assertEquals(8, plugin.hud.glowNear(near), 1e-9);
    }

    @Test
    void theMyvaloidIsLockedUntilHeJoinsTheirFaction() {
        Data.Template m = Data.TEMPLATES.get("Mývaloid");
        assertNotNull(m);
        assertTrue(m.faction() && !m.premium());
        // Marek's round 9: P +3, A +4, poison 50 %; the rest as proposed (S −3, E −1, C −3, L +1, HP 8, perk rate 3, a point more)
        assertArrayEquals(new int[]{-3, 3, -1, -3, 0, 4, 1}, m.mods());
        assertEquals(0.50, m.poisonRes(), 1e-9);
        assertEquals(0.20, m.radRes(), 1e-9);
        assertEquals(8, m.hp());
        assertEquals(3, m.perkRate());
        assertEquals(Data.TEMPLATES.get("Human").attributePoints() + 1, m.attributePoints());
        assertEquals("dark halved", m.senses());
        PlayerMock p = player(0.5, 0.5, false);
        Character draft = new Character();
        List<String> before = Forms.choices(p, plugin, draft).stream().map(Data.Template::name).toList();
        assertFalse(before.contains("Mývaloid"), before.toString());
        draft.unlocked.add("Mývaloid");                                     // the GM: he joined their faction
        assertTrue(Forms.choices(p, plugin, draft).stream().anyMatch(t -> t.name().equals("Mývaloid")));
        PlayerMock gm = player(0.5, 0.5, true);
        assertTrue(Forms.choices(gm, plugin, new Character()).stream().anyMatch(t -> t.name().equals("Mývaloid")), "the GM sees it");
    }

    @Test
    void theGmOpensItWithGroupGrant() {
        PlayerMock gm = player(0.5, 0.5, true), p = player(2.5, 0.5, false);
        server.dispatchCommand(gm, "group grant " + p.getName() + " Mývaloid");
        assertTrue(plugin.character(p).unlocked.contains("Mývaloid"));
    }
}
