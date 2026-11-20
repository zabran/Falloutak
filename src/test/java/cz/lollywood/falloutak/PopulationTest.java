package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The data store, step 2: the workbook's Population, Factions and NPC tabs, and its survival maxima. */
class PopulationTest {
    @Test
    void theGroupLevelIsThePCsTableOnTheXpPerMember() {
        // 06 §2.1 (RULED 11-20): the pool / the headcount on the PCs' table (it replaced the workbook's Fallout 2 formula)
        assertEquals(Rules.levelExact(1000), Population.level(2000, 2), 1e-9);
        assertEquals(1.0, Population.level(0, 2), 1e-9);
        assertEquals(1.0, Population.level(500, 0), 1e-9);
        assertEquals(3.0, Population.level(3 * 957, 3), 1e-9);    // 957 a member = level 3
        assertEquals(957, Population.xpForLevel(3), 1e-9);
        // a newcomer with nothing dilutes it (06 §2.3)
        assertTrue(Population.level(3 * 957, 4) < 3);
    }

    @Test
    void membersWorkIntoTheirGroupsPool() {
        Population p = Population.example();                  // Honza idling, Faiden healing: no work
        assertTrue(p.work(8).isEmpty());
        assertEquals(0, Population.workXp("Idling"));
        assertEquals(0, Population.workXp("Sleeping"));
        assertEquals(1, Population.workXp("Farming"));
        assertEquals(2, Population.workXp("Scavenging"));
        double xp = p.group("Smugler").xp;
        p.person("Honza").activity = "Farming";
        p.person("Faiden").activity = "Patrol";
        java.util.Map<String, Double> got = p.work(8);
        assertEquals(8 + 16, got.get("Smugler"), 1e-9);
        assertEquals(xp + 24, p.group("Smugler").xp, 1e-9);
        p.person("Faiden").group = "nobody";                  // a group that does not exist gains nothing
        assertEquals(8, p.work(8).get("Smugler"), 1e-9);
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
