package cz.lollywood.falloutak;

import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Part 9: the GM moves people between the worlds (the 1:1 local maps) and back. */
class MapsTest extends MockGame {

    @Test
    void theServerFoldersWorldsAreTheFoldersWithALevelDat(@TempDir File dir) throws IOException {
        for (String n : List.of("jihlava", "jihlava_ruin")) {
            File d = new File(dir, n);
            assertTrue(d.mkdirs());
            assertTrue(new File(d, "level.dat").createNewFile());
        }
        assertTrue(new File(dir, "plugins").mkdirs());                      // no level.dat: not a world
        List<String> w = Maps.worlds(dir);
        assertTrue(w.containsAll(List.of("jihlava", "jihlava_ruin", world.getName())), w.toString());
        assertFalse(w.contains("plugins"));
    }

    @Test
    void theGmJumpsToAWorldAndBackToWhereHeStood() {
        World jihlava = server.addSimpleWorld("jihlava");
        PlayerMock gm = player(7.5, 9.5, true);
        String r = Maps.command(gm, new String[]{"go", "jihlava"});
        assertTrue(r.startsWith("You are in jihlava"), r);
        assertEquals(jihlava, gm.getWorld());
        assertTrue(Maps.command(gm, new String[]{"go", "jihlava"}).startsWith("! already in jihlava"));
        assertTrue(Maps.command(gm, new String[]{"go", "nowhere"}).startsWith("! no world \"nowhere\""));
        assertEquals("Back in " + world.getName() + ".", Maps.command(gm, new String[]{"back"}));
        assertEquals(world, gm.getWorld());
        assertEquals(7.5, gm.getLocation().getX(), 1e-9);                   // where he stood, not the spawn
        assertEquals(9.5, gm.getLocation().getZ(), 1e-9);
        Maps.command(gm, new String[]{"back"});                             // nothing remembered: the main world's spawn
        assertEquals(world, gm.getWorld());
        String l = Maps.command(server.getConsoleSender(), new String[]{"load", "jihlava"});   // the console loads and reports
        assertTrue(l.startsWith("jihlava loaded: spawn "), l);
        assertTrue(Maps.command(server.getConsoleSender(), new String[]{"go", "jihlava"}).contains("players only"));
    }

    @Test
    void everyoneGoesTogetherAndOnlyAGmMovesPeople() {
        World ruin = server.addSimpleWorld("jihlava_ruin");
        PlayerMock gm = player(), a = player(3, 3, false), b = player(5, 5, false);
        assertTrue(Maps.command(a, new String[]{"go", "jihlava_ruin"}).startsWith("! only a GM"));
        assertEquals(world, a.getWorld());
        assertTrue(Maps.command(gm, new String[]{"go", "jihlava_ruin", "all"}).startsWith("3 moved in jihlava_ruin"));
        for (PlayerMock p : List.of(gm, a, b)) assertEquals(ruin, p.getWorld());
        Maps.back(a);
        assertEquals(world, a.getWorld());
        assertEquals(3.0, a.getLocation().getX(), 1e-9);
    }

    @Test
    void theGmMenuHasTheMaps() {
        World jihlava = server.addSimpleWorld("jihlava");
        PlayerMock gm = player();
        Menus.gm(gm);
        Ui.Page p = click("Maps");
        assertEquals("GM · maps", p.title());
        click("Go: jihlava");
        assertEquals(jihlava, gm.getWorld());
        click("Back");
        assertEquals(world, gm.getWorld());
    }
}
