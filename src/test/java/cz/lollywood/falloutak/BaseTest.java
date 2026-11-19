package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The base's store (05c §7): rows by type, tier and rarity with their average condition, the unlocked items, the chests (Marek 11-19:
 * *"one chest per type, rows by tiers and rarities. And physical Mess Chest"*; *"Only unlocked items are shown and selectable."*).
 */
class BaseTest {
    static final String BODY_II = "Body armour, Tier II, rarity 1";

    @Test
    void unlockedFromTheStartBroughtInOrByTheGm() {
        Base b = new Base(null);
        assertTrue(b.unlocked(Data.ARMOUR.get("Metal Armor"), "Chest"));              // Tier II, rarity 1: the base can make it
        assertFalse(b.unlocked(Data.ARMOUR.get("Metal Armor"), "Feet"));              // no such piece
        assertFalse(b.unlocked(Data.ARMOUR.get("Leather Armor"), "Legs"));            // rarity 2
        b.put(new Character.Worn("Leather Armor", "Legs", 70));                      // brought in: that piece is unlocked
        assertTrue(b.unlocked(Data.ARMOUR.get("Leather Armor"), "Legs"));
        assertFalse(b.unlocked(Data.ARMOUR.get("Leather Armor"), "Chest"));
        assertTrue(b.toggleSet("Leather Armor"));                                    // the GM: the whole set
        assertTrue(b.unlocked(Data.ARMOUR.get("Leather Armor"), "Chest"));
        assertFalse(b.toggleSet("Leather Armor"));
        assertFalse(b.unlocked(Data.ARMOUR.get("Leather Armor"), "Chest"));
        assertTrue(b.unlocked(Data.ARMOUR.get("Leather Armor"), "Legs"));             // what was brought in stays
    }

    @Test
    void rowsKeepTheirConditionWhole() {
        Base b = new Base(null);
        assertTrue(b.put(new Character.Worn("Metal Armor", "Chest", 60)));
        assertTrue(b.put(new Character.Worn("Vandal Armor", "Chest", 90)));
        assertFalse(b.put(new Character.Worn("Nonsense", "Chest", 90)));
        Base.Pool row = b.pools.get(BODY_II);
        assertEquals(2, row.count);
        assertEquals(75, row.average(), 1e-9);
        List<Base.Row> rows = b.rows("Body armour");
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).sets().containsAll(List.of("Metal Armor", "Vandal Armor", "Arctic gear", "Ghillie suit")), rows.get(0).sets().toString());
        // out as any unlocked item of the row, at its average; the rest keeps the same average (no condition made or lost)
        assertEquals(new Character.Worn("Ghillie suit", "Chest", 75, "standard"), b.take(BODY_II, "Ghillie suit", "standard"));
        assertEquals(1, row.count);
        assertEquals(75, row.average(), 1e-9);
        assertNull(b.take(BODY_II, "Metal Armor Mk II", "standard"));                // another rarity
        b.put(new Character.Worn("Metal Armor", "Chest", 0));
        assertEquals(37.5, row.average(), 1e-9);                                     // a broken piece pulls the row down
        assertEquals("large", b.take(BODY_II, "Metal Armor", "large").fitName());    // made for the taker's body
        b.take(BODY_II, "Metal Armor", "standard");
        assertNull(b.take(BODY_II, "Metal Armor", "standard"));                      // empty
        assertTrue(b.rows("Body armour").isEmpty());
        // a row with nothing unlocked shows, but gives nothing
        b.put(new Character.Worn("Leather Armor", "Chest", 50));
        b.unlocked.clear();
        assertEquals(List.of(), b.rows("Body armour").get(0).sets());
        assertNull(b.take("Body armour, Tier I, rarity 2", "Leather Armor", "standard"));
    }

    @Test
    void undamagedItemsApart() {
        // Marek 11-19: *"maybe use average condition, but with special category for undamaged?"*
        Base b = new Base(null);
        b.put(new Character.Worn("Metal Armor", "Chest", 100));
        b.put(new Character.Worn("Vandal Armor", "Chest", 99.6));                    // shows as 100 %: undamaged
        b.put(new Character.Worn("Metal Armor", "Chest", 40));
        b.put(new Character.Worn("Metal Armor", "Chest", 60));
        Base.Pool row = b.pools.get(BODY_II);
        assertEquals(4, row.count);
        assertEquals(2, row.fresh);
        assertEquals(2, row.worn());
        assertEquals(50, row.average(), 1e-9);                                       // the worn ones only
        assertEquals(50, b.take(BODY_II, "Metal Armor", "standard").condition(), 1e-9);   // a left click: a worn one
        assertEquals(100, b.take(BODY_II, "Metal Armor", "standard", true).condition(), 1e-9);
        assertEquals(100, b.take(BODY_II, "Vandal Armor", "standard", true).condition(), 1e-9);
        assertNull(b.take(BODY_II, "Metal Armor", "standard", true));                 // no undamaged ones left
        assertEquals(1, row.count);
        assertEquals(50, row.average(), 1e-9);                                       // the last worn one kept its 50
        // a worn piece repaired to full comes back undamaged (the kit at Crafting 100: +30 %)
        Character.Worn w = b.take(BODY_II, "Metal Armor", "standard");
        assertEquals(50, w.condition(), 1e-9);
        double fixed = Combat.kitRepair(Combat.kitRepair(w.condition(), 100, false), 100, false);
        assertEquals(100, fixed, 1e-9);
        b.put(new Character.Worn("Metal Armor", "Chest", fixed));
        assertEquals(1, row.fresh);
        assertEquals(0, row.worn());
        assertEquals(100, b.take(BODY_II, "Metal Armor", "standard").condition(), 1e-9);  // no worn ones: a left click gives an undamaged one
        assertTrue(b.rows("Body armour").isEmpty());
        // nothing made or lost: in 300 + 99.6 → out as the same total, in any order
        Base c = new Base(null);
        double in = 0, out = 0;
        for (double v : new double[]{100, 30, 70, 100, 55}) { c.put(new Character.Worn("Metal Armor", "Chest", v)); in += v >= Base.UNDAMAGED ? 100 : v; }
        for (boolean u : new boolean[]{false, true, false, true, false}) out += c.take(BODY_II, "Metal Armor", "standard", u).condition();
        assertEquals(in, out, 1e-9);
    }

    @Test
    void theChests() {
        Base b = new Base(null);
        Base.Spot a = new Base.Spot("world", 1, 4, 1), a2 = new Base.Spot("world", 2, 4, 1), m = new Base.Spot("world", 5, 4, 5);
        b.setChest("Body armour", List.of(a, a2));                                    // a double chest
        assertEquals("Body armour", b.chestAt("world", 2, 4, 1));
        assertNull(b.chestAt("world_nether", 2, 4, 1));
        b.setChest("Head armour", List.of(a, a2));                                    // the same chest made another type's
        assertEquals("Head armour", b.chestAt("world", 1, 4, 1));
        assertFalse(b.chests.containsKey("Body armour"));
        b.setChest(Base.MESS, List.of(m));
        b.setChest(Base.MESS, List.of(new Base.Spot("world", 9, 4, 9)));               // several Mess Chests
        assertEquals(2, b.chests.get(Base.MESS).size());
        b.forget(List.of(m));
        assertNull(b.chestAt("world", 5, 4, 5));
        assertEquals(List.of("Head armour", "Body armour", "Leg armour", "Footwear"), Base.types());
    }

    @Test
    void theFileAndTheOldStore(@TempDir Path dir) throws Exception {
        // 0.25.0 kept every item apart: read once into the rows
        Files.writeString(dir.resolve("base.json"), "{\"units\": [{\"type\": \"Body armour\", \"tier\": 2, \"rarity\": 1, \"condition\": 80},"
                + " {\"type\": \"Body armour\", \"tier\": 2, \"rarity\": 1, \"condition\": 40}], \"place\": {\"world\": \"world\"}}");
        Base b = new Base(dir);
        assertEquals(2, b.pools.get(BODY_II).count);
        assertEquals(60, b.pools.get(BODY_II).average(), 1e-9);
        b.setChest(Base.MESS, List.of(new Base.Spot("world", 0, 4, 0)));
        b.put(new Character.Worn("Leather Armor", "Legs", 70));
        Base again = new Base(dir);
        assertEquals(2, again.pools.get(BODY_II).count);
        assertEquals(Base.MESS, again.chestAt("world", 0, 4, 0));
        assertTrue(again.unlocked(Data.ARMOUR.get("Leather Armor"), "Legs"));
        // 0.26.0 summed every item of a row: a row all at full condition is all undamaged, a mixed one stays worn
        Files.writeString(dir.resolve("base.json"), "{\"pools\": [{\"type\": \"Body armour\", \"tier\": 2, \"rarity\": 1, \"count\": 2, \"condition\": 200},"
                + " {\"type\": \"Head armour\", \"tier\": 2, \"rarity\": 1, \"count\": 2, \"condition\": 150}]}");
        Base read = new Base(dir);
        assertEquals(2, read.pools.get(BODY_II).fresh);
        assertEquals(0, read.pools.get(BODY_II).worn());
        assertEquals(0, read.pools.get("Head armour, Tier II, rarity 1").fresh);
        assertEquals(75, read.pools.get("Head armour, Tier II, rarity 1").average(), 1e-9);
        Files.writeString(dir.resolve("base.json"), "{ not json");
        assertTrue(new Base(dir).pools.isEmpty());                                    // a bad file is set aside, not lost
        try (var s = Files.list(dir)) { assertTrue(s.anyMatch(f -> f.getFileName().toString().startsWith("base.bad-"))); }
    }
}
