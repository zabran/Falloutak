package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** The sounds (Marek 11-19: every player, NPC and environment action; Fallout 2's from the player's own copy) and the overlays. */
class SfxTest extends MockGame {
    @Test
    void everyKeyTheCodeUsesIsInTheCatalogue() throws Exception {
        Pattern call = Pattern.compile("(?:Sfx\\.(?:at|to)\\([^;]*?|cues\\.add\\(|Sfx\\.body\\()\"([a-z0-9.]+)\"");
        Set<String> used = new TreeSet<>();
        try (var files = Files.list(Path.of("src/main/java/cz/lollywood/falloutak"))) {
            for (Path f : files.toList()) {
                Matcher m = call.matcher(Files.readString(f));
                while (m.find()) used.add(m.group(1));
            }
        }
        assertTrue(used.size() > 40, used.toString());
        for (String k : used) {
            if (Set.of("hurt", "down", "dying", "death").contains(k)) {   // a body: a man's and a woman's
                assertTrue(Sfx.ROWS.containsKey(k + ".male") && Sfx.ROWS.containsKey(k + ".female"), k);
            } else assertTrue(Sfx.ROWS.containsKey(k), "not in sounds.json: " + k);
        }
        for (Sfx.Row r : Sfx.ROWS.values()) {
            assertFalse(r.mc().isBlank(), r.key());
            assertTrue(r.pitch() >= 0.5f && r.pitch() <= 2f, r.key() + ": Minecraft plays pitch 0.5-2");
            assertTrue(r.volume() > 0, r.key());
            for (String f : r.f2()) assertTrue(f.matches("[A-Z0-9_]{1,8}"), r.key() + ": " + f);
        }
    }

    @Test
    void minecraftsOrFallout2s() {
        PlayerMock p = player();
        assertEquals("minecraft:entity.firework_rocket.blast", Sfx.name(p, "pistol.single"));
        List<String> heard = new ArrayList<>();
        Sfx.recorder = (k, who) -> heard.add(k + "@" + who.getName());
        assertTrue(plugin.soundsCommand(p, new String[]{"fallout2"}).startsWith("Fallout 2's sounds"));
        assertEquals("falloutak_f2:pistol.single", Sfx.name(p, "pistol.single"));
        plugin.soundsCommand(p, new String[]{"female"});
        assertTrue(plugin.character(p).female);
        assertEquals("hurt.female", Sfx.body("hurt", true));
        assertTrue(plugin.soundsCommand(p, new String[]{"test", "geiger"}).startsWith("geiger: the Geiger counter"));
        assertTrue(plugin.soundsCommand(p, new String[]{"test", "nonsense"}).startsWith("/sounds test"));
        plugin.soundsCommand(p, new String[]{"minecraft"});
        assertFalse(Sfx.f2(p));
        assertTrue(plugin.soundsCommand(p, new String[]{}).contains("Minecraft's, a woman's body"));
        Sfx.f2Loaded.add(p.getUniqueId());                                         // the server's Fallout 2 pack loaded
        assertTrue(Sfx.f2(p));
        Sfx.f2Loaded.clear();
        // heard by those near, not by one far away
        PlayerMock far = player(200, 200, false);
        Sfx.at(p.getLocation(), "explosion");
        Sfx.recorder = null;
        assertTrue(heard.contains("explosion@" + p.getName()));
        assertFalse(heard.contains("explosion@" + far.getName()));
        assertThrows(IllegalArgumentException.class, () -> Sfx.to(p, "nonsense"));
    }

    @Test
    void geigerFrostAndTheRedEdge() {
        assertEquals(0, Hud.geigerClicks(0.5, 0));                                  // under 1 rad an hour: background
        assertEquals(1, Hud.geigerClicks(1, 0.99));
        assertEquals(3, Hud.geigerClicks(10, 0.0));                                  // 2.5 a second
        assertEquals(2, Hud.geigerClicks(10, 0.9));
        assertEquals(4, Hud.geigerClicks(5000, 0.0));
        assertEquals(0, Hud.frost(List.of("Minor Dehydration")));
        assertEquals(105, Hud.frost(List.of("Critical Hypothermia")));
        assertTrue(Hud.frost(List.of("Deadly Hypothermia")) < 140, "below Minecraft's frozen, so the game itself deals no damage");
        assertEquals(0, Hud.redEdge(List.of(), 30, 30), 1e-9);
        assertEquals(0.6, Hud.redEdge(List.of("Deadly Radiation Poisoning"), 30, 30), 1e-9);
        assertEquals(0.8, Hud.redEdge(List.of(), 0, 30), 1e-9);
        assertEquals(0.42, Hud.redEdge(List.of(), 7, 30), 1e-9);                  // at a quarter of the HP or less
    }

    @Test
    void theBodysOwnSoundsReachThePlayer() {
        PlayerMock p = player();
        Character c = plugin.character(p);
        List<String> heard = new ArrayList<>();
        Sfx.recorder = (k, who) -> heard.add(k);
        c.electricHazard(3, new Random(1));
        assertEquals(List.of("electric"), c.cues);
        c.locationRads = 50;
        plugin.hud.senses(p, c);
        Sfx.recorder = null;
        assertTrue(heard.contains("electric"), heard.toString());
        assertTrue(heard.contains("geiger"), heard.toString());
        assertTrue(c.cues.isEmpty());
    }

    @Test
    void freeSoundsForAPlayerWithThePackTheVoicesStayMinecrafts() throws Exception {
        PlayerMock p = player();
        assertTrue(Sfx.FREE.contains("pistol.single") && Sfx.FREE.contains("geiger"), Sfx.FREE.toString());
        assertFalse(Sfx.FREE.contains("hurt.male"));                           // no free voices: Minecraft's
        String sounds = Files.readString(Path.of("pack/assets/falloutak/sounds.json"));
        for (String k : Sfx.FREE) {
            assertTrue(Sfx.ROWS.containsKey(k), "not a sound key: " + k);
            assertTrue(sounds.contains("\"" + k + "\""), "not in the pack: " + k);
            assertTrue(Files.exists(Path.of("pack/assets/falloutak/sounds/free/" + k.replace('.', '_') + ".ogg")), k);
        }
        assertEquals("minecraft:" + Sfx.row("pistol.single").mc(), Sfx.name(p, "pistol.single"));
        plugin.packCommand(p, new String[]{"on"});
        assertEquals("falloutak:pistol.single", Sfx.name(p, "pistol.single"));
        assertEquals("minecraft:" + Sfx.row("hurt.male").mc(), Sfx.name(p, "hurt.male"));
        plugin.character(p).fallout2Sounds = true;                              // Fallout 2's own pack wins
        assertEquals("falloutak_f2:pistol.single", Sfx.name(p, "pistol.single"));
        plugin.character(p).fallout2Sounds = false;
        plugin.packCommand(p, new String[]{"auto"});
    }

    @Test
    void marekVerdicts1119FallsCryOutChemsAreHeardCritsHaveNoSoundOfTheirOwn() {
        PlayerMock p = player();
        List<String> heard = new ArrayList<>();
        Sfx.recorder = (k, who) -> heard.add(k);
        try {
            Sfx.fall(p.getLocation(), "down.male", false);                      // the body hitting the ground + the pain voice
            assertEquals(List.of("down.male", "hurt.male"), heard);
            heard.clear();
            Sfx.fall(p.getLocation(), "knockback", true);
            assertEquals(List.of("knockback", "hurt.female"), heard);
            heard.clear();
            plugin.character(p).fallout2Sounds = true;                          // Fallout 2's own fall already cries out
            Sfx.fall(p.getLocation(), "down.male", false);
            assertEquals(List.of("down.male"), heard);
            plugin.character(p).fallout2Sounds = false;
            Character c = plugin.character(p);
            c.dose(Data.CHEMS.get("Mentats"), new Random(1));
            c.dose(Data.CHEMS.get("Psycho"), new Random(1));
            assertEquals(List.of("chem", "stimpak"), c.cues);                   // a pill as a drink, a needle as the Stimpak
            c.cues.clear();
        } finally {
            Sfx.recorder = null;
        }
        assertFalse(Sfx.ROWS.containsKey("crit"));                              // crits: the hit's own sound and the narrator
        assertFalse(Sfx.FREE.contains("explosion"));                            // "use MC"
        assertTrue(Sfx.FREE.contains("weapon.drop") && Sfx.FREE.contains("levelup"));
        assertEquals("entity.witch.throw", Sfx.row("flare").mc());              // "use just the MC throw sound"
    }

    @Test
    void pipBoyPicturesOnlyForAPlayerWithThePack() throws Exception {
        // every attribute, skill and survival value has its picture, and the pack's font has the same characters (tools/cards.py)
        String font = Files.readString(Path.of("pack/assets/falloutak/font/cards.json"));
        List<String> keys = new ArrayList<>();
        for (String s : Rules.SPECIAL) keys.add("attr_" + Icons.key(s));
        for (Rules.Skill s : Rules.SKILLS) keys.add("skill_" + Icons.key(s.name()));
        for (Rules.Stat s : Rules.Stat.values()) keys.add("stat_" + Icons.key(s.name()));
        for (String k : keys) {
            assertTrue(Icons.CARDS.containsKey(k), "no picture: " + k);
            assertTrue(font.contains("font/cards/" + k + ".png"), "not in the font: " + k);
            assertTrue(Files.exists(Path.of("pack/assets/falloutak/textures/font/cards/" + k + ".png")), k);
        }
        PlayerMock p = player();
        Character c = plugin.character(p);
        assertFalse(Icons.hasPack(p, c));                                     // nothing loaded: the small sprite
        String plain = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().serialize(Icons.tip("mace", "attr_strength", "x", null));
        assertFalse(plain.contains("falloutak:cards"), plain);
        Icons.packLoaded.add(p.getUniqueId());
        assertTrue(Icons.hasPack(p, c));
        Icons.VIEWER_HAS_PACK.set(true);
        try {
            String pic = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().serialize(Icons.tip("mace", "attr_strength", "x", null));
            assertTrue(pic.contains("falloutak:cards"), pic);
        } finally {
            Icons.VIEWER_HAS_PACK.remove();
            Icons.packLoaded.remove(p.getUniqueId());
        }
        assertTrue(plugin.packCommand(p, new String[]{"on"}).contains("ON"));  // a pack installed by hand
        assertTrue(Icons.hasPack(p, c));
        plugin.packCommand(p, new String[]{"auto"});
        assertFalse(Icons.hasPack(p, c));
    }
}
