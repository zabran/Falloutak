package cz.lollywood.falloutak;

import org.bukkit.entity.Hoglin;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Silverfish;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The creatures without BetterModel (the mock server has none): the stand-ins, the cries, the Trhač's pollen. */
class CreaturesTest extends MockGame {

    @Test
    void theTrhacIsABoarWithPollenAndTheRadmieStaysASnake() {
        PlayerMock gm = player();
        List<String> heard = new ArrayList<>();
        Sfx.recorder = (k, who) -> heard.add(k);
        try {
            assertTrue(Creatures.command(gm, new String[]{"spawn", "trhač"}).startsWith("Trhač spawned as the stand-in"));
            LivingEntity t = Creatures.nearest(gm);
            assertInstanceOf(Hoglin.class, t);                                     // a boar stands in for the boar
            assertTrue(((Hoglin) t).isImmuneToZombification());                    // not a zoglin after 15 s in the overworld
            assertFalse(((Hoglin) t).hasAI());
            assertEquals(2.6, Creatures.scale(Creatures.kindOf(t), false), 1e-9);    // (the mock's hoglin has no SCALE)
            assertEquals(2.6, Creatures.scale(Creatures.kindOf(t), true), 1e-9);
            assertEquals("trhac", Creatures.kindOf(t).name());
            assertEquals(List.of("trhac"), heard);                                 // its own cry when it appears
            heard.clear();
            assertTrue(Creatures.command(gm, new String[]{"play", "lunge"}).startsWith("The Trhač has no lunge"));
            assertTrue(Creatures.command(gm, new String[]{"play", "charge"}).startsWith("Playing charge."));
            assertEquals(List.of("trhac"), heard);
            heard.clear();
            Creatures.pollenPuffs = 0;
            Creatures.command(gm, new String[]{"play", "pollen"});
            assertTrue(heard.isEmpty());                                           // the pollen is silent
            assertEquals(1, Creatures.pollenPuffs);
            server.getScheduler().performTicks(Creatures.POLLEN_TICKS + 20);
            assertEquals(Creatures.POLLEN_TICKS / Creatures.POLLEN_EVERY, Creatures.pollenPuffs);   // a puff every 5 ticks for 10 s, then none
            // the cloud HIDES (Marek 11-19: "it should obscure for range attacks, the same way as smoke or fog does")
            Zones.Zone cloud = plugin.zones.list.stream().filter(z -> z.gas.equals("pollen")).findFirst().orElseThrow();
            org.bukkit.Location mid = cloud.centre(t.getWorld());
            assertEquals(Creatures.POLLEN_RADIUS, cloud.r, 1e-9);
            assertNull(plugin.zones.at(mid));                                      // nobody breathes it: no gas, no hold-your-breath
            org.bukkit.Location west = mid.clone().add(-15, 0, 0), east = mid.clone().add(15, 0, 0), far = mid.clone().add(0, 0, 30);
            assertSame(cloud, plugin.zones.veil(west, east));                     // a shot across it
            assertSame(cloud, plugin.zones.veil(mid, east));                      // out of it
            assertNull(plugin.zones.veil(west.clone().add(0, 0, 20), east.clone().add(0, 0, 20)));   // beside it: clear
            assertNull(plugin.zones.veil(far, far.clone().add(10, 0, 0)));
            Combat.Shot sh = new Combat.Shot();
            Battle.veil(plugin.zones, west, mid, sh, false);                       // into it
            assertEquals(-10, sh.extra.get("pollen"));                             // smoke's own number
            Combat.Shot knife = new Combat.Shot();
            Battle.veil(plugin.zones, west, mid, knife, true);
            assertFalse(knife.extra.containsKey("pollen"));                        // only ranged attacks
            plugin.zones.rounds(Creatures.POLLEN_ROUNDS);
            assertTrue(plugin.zones.list.stream().noneMatch(z -> z.gas.equals("pollen")));   // gone after 3 rounds
            assertEquals("Removed.", Creatures.command(gm, new String[]{"remove"}));
            assertTrue(Creatures.command(gm, new String[]{"spawn"}).startsWith("Radmie spawned"));
            LivingEntity r = Creatures.nearest(gm);
            assertInstanceOf(Silverfish.class, r);
            assertEquals(4.0, Creatures.scale(Creatures.kindOf(r), false), 1e-9);
            assertEquals("radmie", Creatures.kindOf(r).name());
            assertTrue(Creatures.command(gm, new String[]{"play"}).contains("rise|lunge"));
            assertTrue(Creatures.command(gm, new String[]{}).contains("radmie") && Creatures.command(gm, new String[]{}).contains("trhac"));
            assertEquals("Only a GM can do that.", Creatures.command(player(5, 5, false), new String[]{"spawn", "trhac"}));
        } finally {
            Sfx.recorder = null;
        }
    }
}
