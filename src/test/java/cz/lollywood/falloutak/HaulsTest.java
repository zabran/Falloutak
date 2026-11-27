package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The last-minute hauls (07 §16, RULED 11-21: *"Are the hauls' contents and numbers in the table right? - yes, now it seems fine."* ·
 * *"Should I build the hauls into the plugin now - yes, it will be part of prologue."*): one haul a player, clicked through the pages.
 */
class HaulsTest extends MockGame {
    @Test
    void theEightHaulsAreTheRuledTable() {
        assertEquals(8, Hauls.HAULS.size());
        assertEquals("10 Stone and glass I (potting soil and pots) + 1 Electronics III (a grow lamp) + 2 Seeds III (seed potatoes, packets)",
                Hauls.HAULS.get("flowerpots").contents());
        assertEquals("4 Medicine III (sterile kits, IV fluids) + 7 Medicine III (a course of pre-war antibiotics (7 doses))", Hauls.HAULS.get("pharmacy").contents());   // 04i: a course 7 doses, Tier III
        assertEquals("2 Gear and tools III (power tools) + 2 Machinery III (precision parts, small motors)", Hauls.HAULS.get("hardware").contents());
        assertEquals("3 Electronics III (LED lamps, circuit boards) + 2 Power III (batteries)", Hauls.HAULS.get("electronics").contents());
        assertEquals("4 Textile III (canvas, nylon) + 2 Soft armour parts III (jackets, boots)", Hauls.HAULS.get("outdoor").contents());
        assertEquals("2 XP books of Tier III (300 XP to each reader, once a title)", Hauls.HAULS.get("bookshop").contents());
        assertEquals("6 Stone and glass III (concrete, clear glass) + 4 Metal III (mild steel)", Hauls.HAULS.get("builders").contents());
        assertEquals("1 Salvage III (a car to strip) + 4 Fuel IV (siphoned petrol)", Hauls.HAULS.get("carpark").contents());
        // every type is one of the store's
        for (Hauls.Haul h : Hauls.HAULS.values()) for (Hauls.Item i : h.items()) assertTrue(Production.TYPES.containsKey(i.type()), i.type());
    }

    @Test
    void eachPlayerGrabsOneHaulAndTwoFlowerpotsBuildAGrowRoom() {
        Production pr = plugin.production;
        Base base = plugin.base;
        PlayerMock a = player(), b = player();
        Menus.actions(a);
        click("The last-minute haul");
        assertTrue(page().text().contains("You have not grabbed yours yet."), page().text());
        click("Flowerpots and seeds");
        click("Grab it");
        assertTrue(said(a).contains("You grab Flowerpots and seeds in the garden shop on the way down: 10 Stone and glass I"), "the pick told");
        assertTrue(page().text().contains("You grabbed: Flowerpots and seeds."), page().text());
        assertEquals(10, base.count("Stone and glass", 1, 1));
        assertEquals(1, base.count("Electronics", 3, 1));
        assertEquals(2, base.count("Seeds", 3, 1));
        // once a player: the Actions page drops the button, a second pick is refused
        Menus.actions(a);
        assertFalse(page().labels().contains("The last-minute haul"), page().labels().toString());
        assertTrue(Hauls.pick(pr, base, a, "pharmacy").startsWith("! you have grabbed your haul already: Flowerpots and seeds"));
        assertEquals(0, base.count("Medicine", 3, 1));
        // round 8: "needing 2 players with flowerpots to get grow room": the second one makes the soil and the lamps of Build a grow room
        Hauls.pick(pr, base, b, "flowerpots");
        base.addUnits("Wood", 2, 1, 4);
        Production.Recipe build = Production.RECIPES.get("build-a-grow-room");
        for (Production.Ttr in : build.inputs) assertTrue(base.count(in.type, in.tier, 0) >= in.count, in.type + " " + in.tier);
        assertEquals(2, pr.hauls.size());
        // the GM sees both, and the haul page counts them
        Menus.actions(a);
        click("GM menu");
        click("The last-minute hauls (2)");
        assertTrue(page().labels().contains("Take back: " + a.getName() + " · Flowerpots and seeds"), page().labels().toString());
    }

    @Test
    void theBookshopsXpBooksAreReadOnceAPersonAndByAGroup() {
        Production pr = plugin.production;
        Base base = plugin.base;
        PlayerMock a = player(), b = player();
        Hauls.pick(pr, base, a, "bookshop");
        Hauls.pick(pr, base, b, "bookshop");
        // a new pair of titles for each player who picks the bookshop
        assertEquals(4, pr.library.size());
        assertEquals(Hauls.TITLES.subList(0, 4), pr.library.stream().map(x -> x.title).toList());
        assertEquals(a.getName(), pr.library.get(0).from);
        String t = Hauls.TITLES.get(0);
        Character c = plugin.character(a);
        long xp0 = c.xp;
        Menus.actions(a);
        click("Production");
        click("The base's books (4)");
        click(t + " · 300 XP");
        click("Read it (" + c.name + ")");
        assertEquals(xp0 + 300, c.xp);                                                   // 100 × tier III × rarity 1
        assertTrue(page().labels().contains(t + " · read"), page().labels().toString());
        assertTrue(Hauls.read(pr, a, c, t).startsWith("! " + c.name + " has read " + t + " already"));
        assertEquals(xp0 + 300, c.xp);
        // a group: each member adds the book's XP to the pool, once a title a group
        Population.Group g = plugin.roster.pop.groups.stream().filter(x -> plugin.roster.pop.members(x.name) > 0).findFirst().orElseThrow();
        int n = plugin.roster.pop.members(g.name);
        double pool = g.xp;
        assertTrue(Hauls.groupRead(pr, g.name, t).startsWith(g.name + ": " + n), "the group reads");
        assertEquals(pool + 300.0 * n, g.xp, 1e-9);
        assertTrue(Hauls.groupRead(pr, g.name, t).startsWith("! " + g.name + " has read"));
        // the GM takes back the second bookshop (unread); the first stays (read)
        assertTrue(Hauls.undo(pr, base, b.getUniqueId().toString()).contains("may pick again"));
        assertEquals(2, pr.library.size());
        assertTrue(Hauls.undo(pr, base, a.getUniqueId().toString()).contains("has been read: the pick stays"));
        // b picks again: the shelf's next free titles
        Hauls.pick(pr, base, b, "bookshop");
        assertEquals(Hauls.TITLES.subList(0, 4), pr.library.stream().map(x -> x.title).toList());
    }

    @Test
    void aPickIsTakenBackOnlyWhileAllOfItIsInTheStore() {
        Production pr = plugin.production;
        Base base = plugin.base;
        PlayerMock a = player();
        Hauls.pick(pr, base, a, "carpark");
        assertEquals(4, base.count("Fuel", 4, 1));
        base.takeUnits("Fuel", 4, 1, 1);                                                  // a can of petrol used
        assertTrue(Hauls.undo(pr, base, a.getUniqueId().toString()).startsWith("! the store no longer holds 4 Fuel IV"));
        base.addUnits("Fuel", 4, 1, 1);
        assertTrue(Hauls.undo(pr, base, a.getUniqueId().toString()).contains("taken back"));
        assertEquals(0, base.count("Fuel", 4, 1));
        assertEquals(0, base.count("Salvage", 3, 1));
        assertTrue(pr.hauls.isEmpty());
        // saved with the base's production state and read back
        Hauls.pick(pr, base, a, "pharmacy");
        Production back = Store.GSON.fromJson(Store.GSON.toJson(pr), Production.class);
        assertEquals("pharmacy", back.hauls.get(a.getUniqueId().toString()).haul);
    }
}
