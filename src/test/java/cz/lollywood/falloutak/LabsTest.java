package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

/** The research pages clicked through (part 8): a card, the GM's find, the theory in the laboratory, the prototype, the XP. */
class LabsTest extends MockGame {
    @Test
    void aPlayerResearchesAWayThroughThePages() {
        PlayerMock gm = player();
        Character c = plugin.character(gm);
        int sk = Rules.skillIndex("Chemistry");
        c.points[sk] = 0;
        c.points[sk] = 75 - c.skill("Chemistry");                                       // 1.5 points an hour at the gate 50
        c.working = true;
        c.perks.add(new Character.Feat("Prototype", "", ""));
        Production pr = plugin.production;
        pr.build("Reactor", 4, "Reactor");
        pr.build("Laboratory", 2, "Lab");
        pr.build("Still", 2, "Still");
        plugin.base.addUnits("Water", 1, 1, 300);
        plugin.base.addUnits("Food", 1, 1, 3);
        plugin.base.addUnits("Fuel", 1, 1, 1);
        Menus.actions(gm);
        click("Production");
        Ui.Page pg = click("Research");
        assertTrue(pg.text().contains("Laboratories: Lab (Tier II, free)"), pg.text());
        assertFalse(pg.labels().stream().anyMatch(l -> l.startsWith("Gene therapy")), pg.labels().toString());   // PARKED
        pg = click("Reflux still · open · 0 %");
        assertTrue(pg.text().contains("Progress 0 %: nothing yet"), pg.text());
        click("GM: a find");
        pg = click("Credit", "s", 50f, "re", false);
        assertTrue(said(gm).contains("Reflux still: a find +50 % → 50 %"), said(gm));
        click("GM: the achievement");
        click("GM: an inspiration");
        pg = page();
        assertTrue(pg.text().contains("Progress 80 %: find 50 % · achievement 20 % · inspiration 10 %"), pg.text());
        assertTrue(pg.text().contains("Theory left: 80 points"), pg.text());
        click("Theory in a laboratory");
        pg = click("Lab (Tier II)");
        assertTrue(said(gm).contains("Lab: Theory: Reflux still until stopped; the pick: " + c.name), said(gm));
        Menus.gm(gm);
        click("Pass time (everyone)");
        clickAndClose("Pass", "h", 60f, "m", 0f);
        assertTrue(said(gm).contains("Lab: Reflux still: the theory is done (100 %): the prototype next"), said(gm));
        long xp0 = c.xp;
        Labs.card(gm, "reflux-still");
        click("Build the prototype");
        click("Still");
        Menus.gm(gm);
        click("Pass time (everyone)");
        clickAndClose("Pass", "h", 10f, "m", 0f);
        assertTrue(said(gm).contains("its first test passes (Prototype): PASSED"), said(gm));
        assertTrue(pr.known.contains("reflux-still"));
        assertEquals(xp0 + 200 + 10, c.xp);                                                  // 100 x T x R, and his 10 hours of work
        Labs.main(gm);
        assertTrue(page().labels().contains("Reflux still · done"), page().labels().toString());
    }
}
