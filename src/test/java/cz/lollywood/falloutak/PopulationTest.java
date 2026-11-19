package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The data store, step 2: the workbook's Population, Factions and NPC tabs, and its survival maxima. */
class PopulationTest {
    @Test
    void theGroupLevelIsTheWorkbooksFormula() {
        // Factions!D2: Smugler, 2 members, 2000 XP -> 2 (the workbook shows 2)
        assertEquals(2.0, Population.level(2000, 2), 1e-9);
        assertEquals(1.0, Population.level(0, 2), 1e-9);
        assertEquals(3.0, Population.level(3 * 3000, 3), 1e-9);   // 3000 a member = level 3 in Fallout 2's table
        assertEquals(3000, Population.xpForLevel(3), 1e-9);
        // the same as the workbook's own expression
        double x = 1234.5;
        assertEquals((1.0 / 50) * (25 + Math.sqrt(5) * Math.sqrt(x / 3 + 125)), Population.level(x, 3), 1e-9);
    }

    @Test
    void thePopulationRoundTripsAndCountsItsGroups() throws Exception {
        Population p = Population.example();
        assertEquals(2, p.members("Smugler"));
        assertEquals(0, p.members("kkti"));
        assertNull(p.set(p.person("faiden"), "traits", "Genius, Fast Shot"));
        assertEquals(2, p.person("Faiden").traits.size());
        assertNotNull(p.set(p.person("Honza"), "hp", "lots"));
        assertNotNull(p.set(p.person("Honza"), "colour", "red"));
        assertNull(p.set(p.person("Honza"), "group", "Raiders"));
        assertNotNull(p.group("raiders"));
        Path f = Files.createTempDirectory("fo-pop").resolve("population.json");
        p.save(f);
        Population q = Population.load(f);
        assertEquals(Store.GSON.toJson(p), Store.GSON.toJson(q));
        assertNull(Population.load(f.resolveSibling("none.json")));
    }

    @Test
    void anNpcStatblockIsASavedSheet() throws Exception {
        Store s = new Store(Files.createTempDirectory("fo-npc"));
        Character c = Character.sample("GM");
        c.name = "Smugler";
        c.base[0] = 8;
        s.saveNpc("Smugler", c);
        assertEquals(java.util.List.of("Smugler"), s.npcNames());
        assertEquals(Store.toJson(c), Store.toJson(s.loadNpc("smugler")));
        assertTrue(s.deleteNpc("Smugler"));
        assertTrue(s.npcNames().isEmpty());
        assertNull(s.loadNpc("Smugler"));
        assertFalse(Store.validName("../x"));
        assertTrue(Store.validName("Nájezdník_2"));
    }

    @Test
    void theSurvivalMaximaAreTheWorkbooks() {
        // Character!C14-C20 of the workbook's example: a human (HP bonus 15), Endurance 6, Survival 21.2, 0 Survival points
        int[] a = {2, 6, 6, 5, 8, 7, 4};
        assertEquals(115.6, Rules.max(Rules.Stat.WATER, a, 21.2, 0, 30, 15), 1e-9);
        assertEquals(120.6, Rules.max(Rules.Stat.FOOD, a, 21.2, 0, 30, 15), 1e-9);
        assertEquals(110, Rules.max(Rules.Stat.SLEEP, a, 21.2, 0, 30, 15), 1e-9);
        assertEquals(85, Rules.max(Rules.Stat.POISON, a, 21.2, 0, 30, 15), 1e-9);
        assertEquals(72, Rules.max(Rules.Stat.HYPOTHERMIA, a, 21.2, 0, 30, 15), 1e-9);
        assertEquals(72, Rules.max(Rules.Stat.HYPERTHERMIA, a, 21.2, 0, 30, 15), 1e-9);
    }
}
