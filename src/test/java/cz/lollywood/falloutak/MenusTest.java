package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The menus clicked through on the mock server, as a player and as the GM (Marek 11-18: *"I would like to test only UX and UI in game,
 * so try to testing everything you can test outside with unit tests."*). Each test follows one path a player takes.
 */
class MenusTest extends MockGame {
    static final String BODY_II = "Body armour, Tier II, rarity 1";

    @Test
    void theActionsAndTheSheet() {
        PlayerMock p = player();
        Menus.actions(p);
        assertEquals("Actions", page().title());
        assertTrue(page().labels().contains("My sheet"));
        assertTrue(page().labels().contains("GM menu"));                          // an operator
        Ui.Page sheet = click("My sheet");
        assertTrue(sheet.title().endsWith("Overview"), sheet.title());
        for (String tab : java.util.List.of("Skills", "Weapon skills", "Traits & perks", "Body", "Survival", "Combat", "Achievements")) {
            Ui.Page pg = click(tab);
            assertTrue(pg.title().endsWith(tab), pg.title());
            assertFalse(pg.text().isBlank(), tab);
            if (tab.equals("Combat")) assertTrue(pg.text().contains("Comfort"), pg.text());   // the outfit's comfort interval (05c §6)
        }
    }

    @Test
    void theGmsToolsOnAPlayer() {
        PlayerMock gm = player(0.5, 0.5, true);
        PlayerMock p = player(2.5, 0.5, false);
        Character c = plugin.character(p);
        int hp = c.hp;
        Menus.gm(gm);
        Ui.Page on = click(p.getName() + " · ");
        assertTrue(on.title().startsWith("GM · "));
        click("Wound");
        click("Wound", "n", 7f, "k", "normal");
        assertTrue(c.hp < hp, "the wound took HP");
        click("Heal fully");
        assertEquals(c.hpMax(), c.hp);
        assertEquals(0, c.survival.get(Rules.Stat.PAIN), 1e-9);
        // Power Armour Training taught
        click("Power Armour Training");
        click("Taught for 10 hours");
        assertTrue(c.paTrained);
        click("Take it away");
        assertFalse(c.paTrained);
        // the clock
        Menus.gmOn(gm, Menus.of(p));
        double t0 = c.clock;
        click("Pass time");
        clickAndClose("Pass", "h", 2f, "m", 0f);
        assertEquals(t0 + 2, c.clock, 1e-6);
        // an item into his pack
        Menus.gmOn(gm, Menus.of(p));
        click("Give an item");
        click("Search gears", "f", "stimpak");
        assertTrue(page().labels().stream().anyMatch(l -> l.toLowerCase(Locale.ROOT).contains("stimpak")), page().labels().toString());
    }

    /** The tag form's answers: these ticked, every other skill unticked. */
    static Object[] tags(String... on) {
        java.util.List<Object> l = new java.util.ArrayList<>();
        for (Rules.Skill s : Rules.SKILLS) {
            String k = s.name().toLowerCase().replaceAll("[^a-z]", "_");
            l.add(k);
            l.add(java.util.Arrays.asList(on).contains(k));
        }
        return l.toArray();
    }

    @Test
    void creatingACharacter() {
        PlayerMock p = player(0.5, 0.5, false);
        p.performCommand("sheet create");
        Ui.Page tpl = page();
        assertTrue(tpl.title().startsWith("New character · 1"), tpl.title());
        Ui.Page attrs = click("Human");
        assertTrue(attrs.title().startsWith("New character · 2"), attrs.title());
        // a Human has 40 points, the start shares 35: five more, one click each
        assertTrue(attrs.text().contains("points left: 5 of 40"), attrs.text());
        Ui.Page refused = click("Next: the tags", "name", "Tester");
        assertTrue(refused.text().contains("a Human has 40"), refused.text());
        for (int i = 0; i < 5; i++) click("+ Agility", "name", "Tester");
        assertTrue(page().text().contains("points left: 0 of 40"), page().text());
        click("+ Strength", "name", "Tester");
        assertTrue(page().text().contains("No points left"), page().text());       // + stops at no points left
        Ui.Page tags = click("Next: the tags", "name", "Tester");
        assertTrue(tags.title().startsWith("New character · 3"), tags.title() + "\n" + tags.text());
        // two tags: refused, then three
        assertTrue(tags.inputs().stream().filter(i -> i.on()).count() > 0);         // the sample's own tags come ticked
        click("Next", tags("guns", "medicine"));
        assertEquals(Ui.Type.NOTICE, page().type());
        assertTrue(page().text().contains("you tagged 2"), page().text());
        click("Back");
        Ui.Page traits = click("Next", tags("guns", "medicine", "sneak"));
        assertTrue(traits.title().startsWith("New character · 4"), traits.title());
        click("Create the character");
        Character made = plugin.character(p);
        assertEquals("Tester", made.name);
        assertEquals(3, made.tags.size());
        assertTrue(made.tags.contains("Medicine"));
        assertTrue(page().title().endsWith("Overview"));
    }
}
