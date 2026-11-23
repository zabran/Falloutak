package cz.lollywood.falloutak;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

/** Part 9d: the world map's travel played on the mock server through its menu and command (the made-up map of WorldMapTest). */
class TravelTest extends MockGame {

    @AfterEach
    void noMap() { Travel.map = null; Travel.file = null; Travel.scene.clear(); }

    @Test
    void withoutAMapTheMenuSaysWhereItBelongs() {
        Travel.map = null;
        PlayerMock p = player();
        Menus.actions(p);
        click("Travel (the world map)");
        assertTrue(page().text().contains("No world map is loaded"), page().text());
        assertTrue(Travel.command(plugin, p, new String[]{"to", "Roadside"}).startsWith("! no world map"));
    }

    @Test
    void thePartySetsOutAndTimePasses() {
        Travel.use(WorldMapTest.fixture());
        Travel.here("Westtown");
        PlayerMock p = player();
        Character c = plugin.character(p);
        double t0 = c.now();
        Menus.actions(p);
        click("Travel (the world map)");
        assertTrue(page().text().contains("at Westtown"), page().text());
        Travel.scene.clear();
        String r = Travel.go(plugin, p, "Roadside", false, WorldMapTest.fixed(0.999999));
        assertTrue(r.contains("toward Roadside") && r.contains("Arrived."), r);
        assertTrue(c.now() > t0 + 1, "the journey's hours passed for him: " + t0 + " -> " + c.now());
        assertTrue(c.outside, "outside the base");
        assertTrue(Travel.where().startsWith("at Roadside"), Travel.where());
    }

    @Test
    void anEncounterSpottedCanBeGoneRoundForItsKillXp() {
        Travel.use(WorldMapTest.fixture());
        Travel.here("Westtown");
        PlayerMock p = player();
        Character c = plugin.character(p);
        long xp0 = c.xp;
        String r = Travel.go(plugin, p, "Roadside", false, WorldMapTest.fixed(0.0));   // always met, always spotted (d100 0 < Survival)
        assertFalse(Travel.scene.isEmpty(), r);
        // whatever came, make it a hostile one seen in time and go round it
        WorldMap.Kind raider = Travel.map.kinds.get("raiders:pond");
        Travel.scene.clear();
        Travel.scene.add(new Travel.Met(new WorldMap.Encounter(raider, "fight", 8), true));
        Menus.actions(p);
        click("Travel (the world map)");
        assertTrue(page().text().contains("Spotted in time"), page().text());
        click("Go round them");
        assertTrue(Travel.scene.isEmpty());
        assertTrue(c.xp > xp0, "the kill XP of eight raiders: " + xp0 + " -> " + c.xp);
        assertTrue(Travel.go(plugin, p, "Roadside", false, WorldMapTest.fixed(0.999999)).contains("Roadside"));
    }

    @Test
    void anAmbushCannotBeGoneRound() {
        Travel.use(WorldMapTest.fixture());
        PlayerMock p = player();
        Travel.scene.add(new Travel.Met(new WorldMap.Encounter(Travel.map.kinds.get("raiders:pond"), "fight", 8), false));
        assertTrue(Travel.goRound(plugin, p).startsWith("!"));
        assertTrue(Travel.go(plugin, p, "Roadside", false, WorldMapTest.fixed(0.5)).startsWith("! an encounter waits"));
        assertTrue(Travel.resolved().startsWith("Done with"));
        assertTrue(Travel.scene.isEmpty());
    }

    @Test
    void theGmSeesTheTableAndClearsBases() {
        Travel.use(WorldMapTest.fixture());
        Travel.here("Roadside");
        PlayerMock g = player();
        String t = Travel.command(plugin, g, new String[]{"table"});
        assertTrue(t.contains("an encounter") && t.contains("Pond raiders"), t);
        assertTrue(Travel.command(plugin, g, new String[]{"season", "winter"}).contains("winter"));
        String cl = Travel.command(plugin, g, new String[]{"clear", "5"});
        assertTrue(cl.startsWith("1 raider bases"), cl);
        assertTrue(Travel.map.bases.get(0).cleared);
        PlayerMock pl2 = player(3, 3, false);
        assertTrue(Travel.command(plugin, pl2, new String[]{"table"}).startsWith("/travel"), "players do not see the table");
    }
}
