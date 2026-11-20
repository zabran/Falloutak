package cz.lollywood.falloutak;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

/** The production pages clicked through (part 7): the GM adds the shelter, a player starts a job, the clock runs it; the store's things. */
class WorksTest extends MockGame {
    @Test
    void aPlayerStartsAJobAndTheClockRunsIt() {
        PlayerMock gm = player();
        Character c = plugin.character(gm);
        c.points[Rules.skillIndex("Crafting")] = 0;
        c.points[Rules.skillIndex("Crafting")] = 60 - c.skill("Crafting");             // twice the minimum
        c.working = true;                                                               // Work in the base: he is a worker
        plugin.base.addUnits("Metal", 1, 1, 3);
        plugin.base.addUnits("Fuel", 2, 1, 2);
        Menus.actions(gm);
        Ui.Page pg = click("Production");
        assertTrue(pg.text().contains("No buildings yet"), pg.text());
        click("GM: buildings and recipes");
        click("The shelter's seven rooms");
        assertTrue(said(gm).contains("7 rooms added."));
        click("Back");
        pg = click("Maintenance workshop");
        assertTrue(pg.text().contains("Maintenance workshop (Workshop, Tier II)"), pg.text());
        click("Start a job");
        click("Recast scrap → 1 Metal II");
        pg = click("Start", "n", 1f, "r", 1f);
        String s = said(gm);
        assertTrue(s.contains("Recast scrap × 1; the pick: " + c.name), s);           // the character, not the account
        assertTrue(pg.text().contains("crew 1/3"), pg.text());
        long xp0 = c.xp;
        Menus.gm(gm);
        click("Pass time (everyone)");
        clickAndClose("Pass", "h", 8f, "m", 0f);                                         // 4 h upkeep, then 2 h of work
        s = said(gm);
        assertTrue(s.contains("Maintenance workshop: Recast scrap done → 1 Metal II R1 (1 of 1)"), s);
        assertEquals(1, plugin.base.count("Metal", 2, 1));
        assertEquals(xp0 + 8, c.xp);                                                    // his downtime work: 1 XP an hour
    }

    @Test
    void theStoreHandsOutThings() {
        PlayerMock p = player();
        plugin.base.addUnits("Metal", 2, 1, 20);
        plugin.storage.open(p, "Metal", 0);
        Inventory top = p.getOpenInventory().getTopInventory();
        Storage.Holder h = (Storage.Holder) top.getHolder();
        ItemStack first = top.getItem(1);
        assertNotNull(first);
        String r = plugin.storage.click(p, h, 1, false, true);                          // shift: up to 16
        assertTrue(r.startsWith("You take 16 × "), r);
        assertEquals(4, plugin.base.count("Metal", 2, 1));
        int got = 0;
        for (ItemStack it : p.getInventory().getContents()) if (it != null && Items.ttrOf(Items.id(it)) != null) got += it.getAmount();
        assertEquals(16, got);
    }
}
