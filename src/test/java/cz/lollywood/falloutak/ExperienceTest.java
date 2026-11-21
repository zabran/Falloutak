package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;

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
    void handsOnLearnerGrowsUpToThreeSkillsASession() {
        // round 6 (Marek 11-20): "but dying should remove it, so it shuld be a bit lower on average, for PCs. It would be really OP for
        // members. Also, I would make it up to 3 (meaning it can be less)."
        Character c = Character.sample("Learner");
        assertTrue(Forms.offeredTraits(c).stream().anyMatch(t -> t.name().equals("Hands-On Learner")));
        assertEquals("", c.handsOn("Guns", "s1"));                                    // without the trait: nothing
        int rate = c.skillRate();
        c.traits.add(new Character.Feat("Hands-On Learner", "trait", ""));
        Forms.applyTraits(c);
        assertEquals(rate - 3, c.skillRate());                                         // −3 skill points a level
        int g = Rules.skillIndex("Guns");
        double g0 = c.skills(true)[g];
        double gain = (c.tags.contains("Guns") ? 2.0 : 1.0) / Rules.pricePerPercent(g0);
        assertTrue(c.handsOn("Guns", "s1").startsWith("Hands-On Learner: Guns +"));
        assertEquals(g0 + gain, c.skills(true)[g], 1e-9);
        assertEquals("Hands-On Learner: Guns already grew this session", c.handsOn("Guns", "s1"));
        assertTrue(c.handsOn("Medicine", "s1").endsWith("(2 of 3 this session)"));
        assertTrue(c.handsOn("Sneak", "s1").endsWith("(3 of 3 this session)"));
        assertEquals("Hands-On Learner: 3 skills already grew this session", c.handsOn("Survival", "s1"));
        assertTrue(c.handsOn("Survival", "s2").endsWith("(1 of 3 this session)"));      // the next session
        assertEquals(g0 + gain, c.skills(true)[g], 1e-9);
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
    void aDeadCharactersPlayerMakesANewOneFromTheGroup() throws Exception {
        PlayerMock gm = player();
        PlayerMock p = member();
        Population pop = plugin.roster.pop;
        Population.Group g = pop.group("Smugler");
        int n0 = pop.members("Smugler");
        gm.performCommand("group xp Smugler " + (n0 * 957 - Math.round(g.xp)));   // level 3 a member: two levels' points
        int gi = Rules.skillIndex("Guns");
        double gunsBefore = g.pointsAt.get(2)[gi];                                  // the points before level 2's
        Character sb = plugin.store.loadNpc("Smugler");
        sb.points[gi] += 20;                                                        // the group spent its levels on Guns
        sb.unspentSkillPoints = 0;
        plugin.store.saveNpc("Smugler", sb);
        Character dead = plugin.character(p);
        dead.dead = true;
        Menus.actions(p);
        click("! A new character from Smugler");
        assertEquals("Who you become", page().title());
        click("Faiden");
        assertTrue(page().title().startsWith("New character from Smugler"), page().title());
        assertFalse(page().labels().contains("Change the template"));
        click("− Endurance");
        click("+ Charisma");
        click("Next: the traits");
        String trait = Forms.offeredTraits(Character.sample("x")).get(0).name();
        click(trait);
        click("Create the character");
        Character c = plugin.character(p);
        assertNotSame(dead, c);
        assertEquals("Faiden", c.name);
        assertEquals("Smugler", c.group);
        assertEquals(957, c.xp);
        assertEquals(3, c.level);
        assertEquals(gunsBefore, c.points[gi], 1e-9);                                // the last 2 levels undone
        assertEquals(2 * c.skillRate(), c.unspentSkillPoints);                       // and his to spend again
        assertEquals(sb.base[Rules.E] - 1, c.base[Rules.E]);                         // his own attributes
        assertEquals(sb.base[Rules.C] + 1, c.base[Rules.C]);
        assertTrue(c.traits.stream().anyMatch(f -> f.name().equals(trait)));       // his own trait
        assertFalse(c.dead);
        assertNull(pop.person("Faiden"));                                            // he left the count with his share
        assertEquals(n0 - 1, pop.members("Smugler"));
        assertEquals(n0 * 957 - 957, g.xp, 1e-6);
        assertTrue(java.nio.file.Files.list(plugin.getDataFolder().toPath().resolve("fallen")).findAny().isPresent());
        assertTrue(page().title().startsWith("Skill points: " + 2 * c.skillRate()), page().title());
        click("Spend", "s" + gi, 4f);
        assertEquals(1, c.perksDue());                                               // a human: a perk at level 3
        assertTrue(page().title().startsWith("Pick a perk"), page().title());
        click("Awareness");                                                          // Guns 50
        assertTrue(c.hasPerk("Awareness"));
        assertEquals(0, c.perksDue());
    }

    @Test
    void lessonsByLevelUpToEightHoursAWeek() throws Exception {
        PlayerMock p = member();
        Population pop = plugin.roster.pop;
        Population.Group g = pop.group("Smugler");                                   // 2000 XP, 2 people: level 3
        Character t = plugin.character(p);
        assertTrue(plugin.roster.lessons(p.getName(), "Smugler", 2).startsWith("!"));   // level 1: not above the group
        t.xp = 5000;
        t.level = 5;
        t.points[Rules.skillIndex("Leadership")] += 60;
        double lead = t.skill("Leadership");
        int students = Math.min(pop.members("Smugler"), Rules.classSize(lead));
        double pool = g.xp;
        long tx = t.xp;
        Menus.actions(p);
        click("Groups");
        click("Lessons");
        click("Teach", "t", p.getName(), "g", "Smugler", "h", 5f);
        assertEquals(pool + students * 5 * (Rules.lessonXp(lead) - 1) + 5, g.xp, 1e-6);   // + the teacher's own 5 into his pool
        assertEquals(tx + 5, t.xp);
        // an NPC teaches too (Marek 11-20): a statblock of a higher level
        Character npc = Character.sample("Mentor");
        npc.name = "Mentor";
        npc.level = 6;
        plugin.store.saveNpc("Mentor", npc);
        String r = plugin.roster.lessons("Mentor", "Smugler", 8);                   // 3 hours left this week
        assertTrue(r.contains("for 3 h") && r.contains("(an NPC)"), r);
        assertTrue(plugin.roster.lessons("Mentor", "Smugler", 1).startsWith("!"));
        assertTrue(plugin.roster.lessons("Smugler", "Smugler", 1).startsWith("!"));  // a group cannot teach itself
        assertTrue(plugin.roster.lessons("Nobody", "Smugler", 1).startsWith("!"));
        pop.worldHours += 168;                                                       // the world's next week
        assertFalse(plugin.roster.lessons("Mentor", "Smugler", 1).startsWith("!"));
    }

    @Test
    void perksArePickedAtPerkLevels() throws Exception {
        PlayerMock p = member();
        Character c = plugin.character(p);
        c.level = 3;
        assertEquals(1, c.perksDue());
        c.points[Rules.skillIndex("Athletics")] += 60;
        Menus.actions(p);
        click("Pick a perk (1)");
        assertTrue(page().labels().contains("Toughness"), page().labels().toString());
        assertFalse(page().labels().contains("Lab Rat"));                             // its gate: any science 100
        double dr = c.resistances()[0];
        click("Toughness");
        assertTrue(c.hasPerk("Toughness"));
        assertEquals(dr + 10, c.resistances()[0], 1e-9);                             // the sheet applies its +10 % DR
        // a group's statblock picks too, from its page
        Character sb = plugin.store.loadNpc("Smugler");
        sb.level = 3;
        plugin.store.saveNpc("Smugler", sb);
        Menus.actions(p);
        click("Groups");
        click("Smugler");
        click("Pick a perk (1)");
        click("Awareness");
        assertTrue(plugin.store.loadNpc("Smugler").hasPerk("Awareness"));           // saved
    }

    @Test
    void boundPerksPickFromLists() throws Exception {   // Marek 11-20: "Should we not make it a selection?"
        PlayerMock p = member();
        Character c = plugin.character(p);
        c.level = 3;                                                                   // a human: one perk due
        c.points[Rules.skillIndex("Medicine")] += 100;                                // Old Friend: Chemistry or Medicine 100
        Menus.actions(p);
        click("Pick a perk (1)");
        click("Old Friend");
        assertEquals(new java.util.ArrayList<>(Data.CHEMS.keySet()), Menus.boundChoices(c, Data.PERKS.get("Old Friend")));
        click("Take it", "b", "Psycho");
        assertTrue(c.hasPerk("Old Friend: Psycho"));
        String r = c.dose(Data.CHEMS.get("Psycho"), new java.util.Random(1));
        assertTrue(r.contains("never addicts"), r);
        assertTrue(c.addictions.isEmpty());
        assertEquals(75, c.inSlot("chem:Psycho").other().get("dr"), 1e-9);             // its 50 % DR x1.5
        // the other lists
        assertTrue(Menus.boundChoices(c, Data.PERKS.get("Nemesis")).contains("Raiders"));
        assertFalse(Data.PERKS.get("My People").bound());                              // faction-wide (Marek 11-20: "Group is tiny")
        assertEquals(List.of("The base"), Menus.boundChoices(c, Data.PERKS.get("Home Turf")));
        plugin.roster.discover(List.of(), "Jihlava");                                  // the GM names an area found
        assertEquals(List.of("The base", "Jihlava"), Menus.boundChoices(c, Data.PERKS.get("Home Turf")));
        assertFalse(Menus.boundChoices(c, Data.PERKS.get("Tag!")).stream().anyMatch(c.tags::contains));
    }
}
