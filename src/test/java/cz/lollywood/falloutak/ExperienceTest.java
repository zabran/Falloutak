package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Part 6 (RULED 11-20, `06-GROUPS.md`, `06b-PC-XP.md`) on the mock server: the GM awards XP from his menu and by /xp, a PC levels up,
 * his XP goes whole into his group's pool, the group's statblock gets its levels' skill points, the faction leader spends them, and
 * the world's time makes the members work.
 */
class ExperienceTest extends MockGame {
    /** A player in the workbook's group Smugler, whose statblock is saved. */
    PlayerMock member() throws Exception {
        PlayerMock p = player(2.5, 0.5, false);
        plugin.character(p).group = "Smugler";
        Character sb = Character.sample("Smugler");
        sb.name = "Smugler";
        sb.unspentSkillPoints = 0;
        plugin.store.saveNpc("Smugler", sb);
        return p;
    }

    @Test
    void theGmAwardsAQuestFromHisMenu() throws Exception {
        PlayerMock gm = player();
        PlayerMock p = member();
        Character c = plugin.character(p);
        Population.Group g = plugin.roster.pop.group("Smugler");
        double pool = g.xp;
        long xp = c.xp;
        int pts = c.unspentSkillPoints;
        Menus.gm(gm);
        click("Award XP");
        assertEquals("GM · award XP", page().title());
        click("A quest or any XP");
        click("Award", "p0", false, "p1", true, "n", "300", "why", "a quest");
        assertEquals(xp + 300, c.xp);
        assertEquals(2, c.level);                                    // 420 >= 250
        assertEquals(pts + c.skillRate(), c.unspentSkillPoints);
        assertEquals(pool + 300, g.xp, 1e-9);                        // 100 % into his group's pool
        String told = said(p);
        assertTrue(told.contains("+300 XP (a quest)") && told.contains("Level 2"), told);
        assertEquals("GM · award XP", page().title());               // back on the page
    }

    @Test
    void killsAreSharedByTheTeamLeadersLeadership() throws Exception {
        PlayerMock gm = player();
        PlayerMock p = member();
        Character a = plugin.character(gm), b = plugin.character(p);
        long xa = a.xp, xb = b.xp;
        double lead = Math.max(a.skill("Leadership"), b.skill("Leadership"));
        long each = Math.round(3 * 126 * Rules.xpShare(2, lead));
        gm.performCommand("xp kill all 30 30 30");
        assertEquals(xa + each, a.xp);
        assertEquals(xb + each, b.xp);
        // the same from the menu, a peaceful end counts the same
        Menus.gm(gm);
        click("Award XP");
        click("Enemies beaten");
        click("Award", "p0", false, "p1", true, "hp", "10");
        assertEquals(xb + each + 38, b.xp);                           // alone: his share is whole
    }

    @Test
    void checksDiscoveryAndResearch() throws Exception {
        PlayerMock gm = player();
        PlayerMock p = member();
        Character c = plugin.character(p);
        long xp = c.xp;
        gm.performCommand("xp check " + p.getName() + " 5 doctor");     // at least 10, doubled
        assertEquals(xp + 20, c.xp);
        int found = plugin.roster.pop.areasFound;
        gm.performCommand("xp discover " + p.getName());
        assertEquals(xp + 20 + 100L * found, c.xp);
        assertEquals(found + 1, plugin.roster.pop.areasFound);
        gm.performCommand("xp research " + p.getName() + " 3 2");
        assertEquals(xp + 20 + 100L * found + 600, c.xp);
        said(gm);
        gm.performCommand("xp give nobody 5");
        assertTrue(said(gm).contains("Not online: nobody"));
        p.performCommand("xp give all 5000");                            // not the GM
        assertEquals(xp + 20 + 100L * found + 600, c.xp);
    }

    @Test
    void anyPlayerLevelsAGroupUpFromTheMenu() throws Exception {
        PlayerMock gm = player();
        PlayerMock p = member();
        Population pop = plugin.roster.pop;
        Population.Group g = pop.group("Smugler");
        int n = pop.members("Smugler");
        int rate = plugin.store.loadNpc("Smugler").skillRate();
        gm.performCommand("group xp Smugler " + (n * 957 - Math.round(g.xp)));   // exactly level 3 a member
        Character sb = plugin.store.loadNpc("Smugler");
        assertEquals(3, g.statLevel);
        assertEquals(3, sb.level);
        assertEquals(2 * rate, sb.unspentSkillPoints);
        // a player who is not the GM (Marek 11-20: "any player, it does not really matter who does faction management")
        assertFalse(p.isOp());
        Menus.actions(p);
        click("Groups");
        click("Smugler · level 3 · " + 2 * rate + " points");
        assertEquals("Group Smugler", page().title());
        assertTrue(page().text().contains("Honza"), page().text());
        Ui.Page form = click("Spend " + 2 * rate + " skill points");
        assertTrue(form.title().startsWith("Skill points: " + 2 * rate), form.title());
        double guns = sb.skills(true)[Rules.skillIndex("Guns")];
        click("Spend", "s" + Rules.skillIndex("Guns"), 4f);
        Character after = plugin.store.loadNpc("Smugler");
        assertEquals(2 * rate - 4, after.unspentSkillPoints);
        assertTrue(after.skills(true)[Rules.skillIndex("Guns")] > guns);
        // the command does the same for any player
        p.performCommand("group spend Smugler");
        assertTrue(page().title().startsWith("Skill points: " + (2 * rate - 4)), page().title());
        // a newcomer dilutes the level: no new points until it is passed again
        gm.performCommand("pop add Nováček Smugler");
        assertTrue(Population.level(g.xp, pop.members("Smugler")) < 3);
        gm.performCommand("group xp Smugler 1");
        assertEquals(3, g.statLevel);
        assertEquals(2 * rate - 4, plugin.store.loadNpc("Smugler").unspentSkillPoints);
    }

    @Test
    void passingTheWorldsTimeMakesTheMembersWork() throws Exception {
        PlayerMock gm = player();
        PlayerMock p = member();
        Population pop = plugin.roster.pop;
        pop.person("Honza").activity = "Farming";
        pop.person("Faiden").activity = "Scavenging";                  // outside the base: nothing yet
        double xp = pop.group("Smugler").xp;
        Character c = plugin.character(p);
        long cx = c.xp;
        Menus.actions(p);
        click("Work in the base");
        assertTrue(c.working);
        Menus.gm(gm);
        click("Pass time (everyone)");
        clickAndClose("Pass", "h", 8f, "m", 0f);
        assertEquals(cx + 8, c.xp);                                      // his downtime work, 1 XP an hour
        assertEquals(0, c.workedHours);
        assertEquals(xp + 8 + 8, pop.group("Smugler").xp, 1e-9);          // Honza's work + his 8 into the pool
        assertTrue(said(gm).contains("Smugler +8 XP from work"));
        Menus.actions(p);
        click("Rest / sleep");                                            // resting stops the work
        assertFalse(c.working);
    }

    @Test
    void aDeadCharactersPlayerGoesOnAsAMember() throws Exception {
        PlayerMock p = member();
        Population pop = plugin.roster.pop;
        Population.Group g = pop.group("Smugler");
        g.xp = 3000;
        int n = pop.members("Smugler");
        Character dead = plugin.character(p);
        dead.dead = true;
        Menus.actions(p);
        click("! Go on as one of Smugler");
        click("Faiden");
        Character c = plugin.character(p);
        assertNotSame(dead, c);
        assertEquals("Faiden", c.name);
        assertEquals("Smugler", c.group);
        assertEquals(Math.round(3000.0 / n), c.xp);
        assertEquals(Rules.levelFor(c.xp), c.level);
        assertEquals(0, c.unspentSkillPoints);
        assertEquals(java.util.List.of("Genius"), c.traits.stream().map(Character.Feat::name).toList());   // his own row's trait
        assertEquals(Math.min(c.hpMax(), 15), c.hp);                                                      // his row's HP
        assertFalse(c.dead);
        assertNull(pop.person("Faiden"));                                   // he left the count with his share
        assertEquals(n - 1, pop.members("Smugler"));
        assertEquals(3000 - 3000.0 / n, g.xp, 1e-6);
        assertTrue(java.nio.file.Files.list(plugin.getDataFolder().toPath().resolve("fallen")).findAny().isPresent());
        assertTrue(plugin.roster.takeOver(p, "Honza").startsWith("!"));     // only when dead
    }

    @Test
    void lessonsAddXpUpToEightHoursAWeek() throws Exception {
        PlayerMock p = member();
        Population pop = plugin.roster.pop;
        Population.Group g = pop.group("Smugler");
        Character t = plugin.character(p);
        t.points[Rules.skillIndex("Leadership")] += 60;
        t.points[Rules.skillIndex("Medicine")] += 80;
        double lead = t.skill("Leadership");
        int students = Math.min(pop.members("Smugler"), Rules.classSize(lead));
        double pool = g.xp;
        long tx = t.xp;
        Menus.actions(p);
        click("Groups");
        click("Lessons");
        click("Teach", "t", p.getName(), "g", "Smugler", "s", "Medicine", "h", 5f);
        assertEquals(pool + students * 5 * (Rules.lessonXp(lead) - 1) + 5, g.xp, 1e-6);   // + the teacher's own 5 (100 % into the pool)
        assertEquals(tx + 5, t.xp);
        String r = plugin.roster.lessons(p, "Smugler", "Medicine", 8);       // 3 hours left this week
        assertTrue(r.contains("for 3 h"), r);
        assertTrue(plugin.roster.lessons(p, "Smugler", "Medicine", 1).startsWith("!"));
        assertTrue(plugin.roster.lessons(p, "Smugler", "Juggling", 1).startsWith("!"));   // no such skill
        t.clock += 168;                                                       // a new week
        assertFalse(plugin.roster.lessons(p, "Smugler", "Medicine", 1).startsWith("!"));
        assertTrue(plugin.roster.lessons(p, "Smugler", "Sneak", 1).startsWith("!"));      // he knows no more Sneak than the group
    }
}
