package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The base's store (05c §7): rows by type, tier and rarity, the unlocked items, the chests (Marek 11-19: *"one chest per type, rows by
 * tiers and rarities. And physical Mess Chest"*); since part 7's round 4 the PARTS and the items assembled from them (Marek 11-20:
 * *"I think the armor slots could be simplified to just armor parts, with each slot made of several armor parts (could be another
 * recipe, but an instant one). It also simplifies repair."*).
 */
class BaseTest {
    static Production.Design d(String category, String name) {
        Production.Design d = Production.design(category, name);
        assertNotNull(d, category + "|" + name);
        return d;
    }

    @Test
    void unlockedFromTheStartBroughtInOrByTheGm() {
        Base b = new Base(null);
        assertTrue(b.unlocked(Data.ARMOUR.get("Metal Armor"), "Chest"));              // Tier II, rarity 1: the base can make it
        assertFalse(b.unlocked(Data.ARMOUR.get("Metal Armor"), "Feet"));              // no such piece
        assertFalse(b.unlocked(Data.ARMOUR.get("Leather Armor"), "Legs"));            // rarity 2
        assertTrue(b.disassemble(new Character.Worn("Leather Armor", "Legs", 70)) >= 0);   // brought in: that piece is unlocked
        assertTrue(b.unlocked(Data.ARMOUR.get("Leather Armor"), "Legs"));
        assertTrue(b.unlocked(Production.piece("Leather Armor", "Legs")));
        assertFalse(b.unlocked(Data.ARMOUR.get("Leather Armor"), "Chest"));
        assertTrue(b.toggleSet("Leather Armor"));                                    // the GM: the whole set
        assertTrue(b.unlocked(Data.ARMOUR.get("Leather Armor"), "Chest"));
        assertFalse(b.toggleSet("Leather Armor"));
        assertFalse(b.unlocked(Data.ARMOUR.get("Leather Armor"), "Chest"));
        assertTrue(b.unlocked(Data.ARMOUR.get("Leather Armor"), "Legs"));             // what was brought in stays
        // the other items the same way
        assertTrue(b.unlocked(d("Pistols", "Pipe pistol")));
        Production.Design ten = d("Pistols", "10mm Pistol");                          // Tier III
        assertFalse(b.unlocked(ten));
        b.disassemble(ten, 1, 100);
        assertTrue(b.unlocked(ten));
        assertEquals(2, b.count("Firearm parts", 3, 1));                              // and its parts are in the store
        Production.Design knife = d("Knives", "Combat knife");                       // Tier III: the GM unlocks it by its key
        assertFalse(b.unlocked(knife));
        assertTrue(b.toggleSet(knife.key()));
        assertTrue(b.unlocked(knife));
    }

    @Test
    void anItemIsAssembledFromItsPartsAtOnce() {
        Base b = new Base(null);
        Production.Design pipe = d("Pistols", "Pipe pistol");                          // 2 Firearm parts I + 1 Light weapon parts I
        assertEquals(0, b.canAssemble(pipe));
        assertEquals("2 Firearm parts I, 1 Light weapon parts I", b.missing(pipe));
        b.addUnits("Firearm parts", 1, 1, 5);
        b.addUnits("Light weapon parts", 1, 2, 3);                                     // rarity 2: not this item's
        assertEquals(0, b.canAssemble(pipe));
        b.addUnits("Light weapon parts", 1, 1, 1);
        assertEquals(1, b.canAssemble(pipe));
        assertEquals("", b.missing(pipe));
        assertFalse(b.assemble(pipe, 2));                                              // not enough: nothing taken
        assertEquals(5, b.count("Firearm parts", 1, 1));
        assertTrue(b.assemble(pipe, 1));
        assertEquals(3, b.count("Firearm parts", 1, 1));
        assertEquals(0, b.count("Light weapon parts", 1, 1));
        assertEquals(3, b.count("Light weapon parts", 1, 2));                          // the rarity 2 ones stay
        assertEquals(0, b.canAssemble(pipe));
        // a batch gives several: arrows by five
        Production.Design arrows = d("Arrows", "Metal-headed arrows");
        assertEquals(5, arrows.makes);
        b.addUnits("Light weapon parts", 2, 1, 2);
        b.addUnits("Hard weapon parts", 2, 1, 3);
        assertEquals(2, b.canAssemble(arrows));
        // a locked item does not assemble, whatever the parts (Leather Armor is rarity 2)
        Production.Design leather = Production.piece("Leather Armor", "Chest");
        b.addUnits("Soft armour parts", 1, 2, 10);
        assertEquals(0, b.canAssemble(leather));
        b.toggleSet("Leather Armor");
        assertTrue(b.canAssemble(leather) > 0);
    }

    @Test
    void roundsTakeLoadsByTheirCalibre() {
        // Marek 11-20: "Pistol, rifle and shotgun ammo, I would simplify all to just ammo (with possible some types needing more than 1 piece per ammo piece)"
        Base b = new Base(null);
        b.addUnits("Ammo", 1, 1, 10);
        assertEquals(10, b.canAssemble(d("Pistol rounds", "Remanufactured pistol rounds")));
        assertEquals(5, b.canAssemble(d("Rifle rounds", "Remanufactured rifle rounds")));
        assertEquals(5, b.canAssemble(d("Shotgun shells", "Scrap-shot shells")));
        assertEquals(2, d("Heavy rounds", "Nails and bolts").makes);                   // a load gives two nails
    }

    @Test
    void backThroughTheMessChestAnItemComesApartAtItsCondition() {
        // round 5 (Marek 11-20): ""loses the damaged share" - this trivialises repair. ... we could use that average condition for
        // parts + the undamaged parts."
        Base b = new Base(null);
        Production.Design chest = Production.piece("Metal Armor", "Chest");           // 2 soft + 8 hard, Tier II (its kg: 0.69.0's pounds x 0.5)
        assertEquals(10, chest.parts.stream().mapToInt(p -> p.count).sum());
        assertEquals(10, b.disassemble(new Character.Worn("Metal Armor", "Chest", 100)));
        assertEquals(10, b.disassemble(new Character.Worn("Metal Armor", "Chest", 60)));   // ALL its parts, at 60 %
        Base.Pool hard = b.pools.get(Base.key("Hard armour parts", 2, 1));
        assertEquals(16, hard.count);
        assertEquals(8, hard.fresh);                                                  // the undamaged apart
        assertEquals(60, hard.average(), 1e-9);                                       // the worn at their average
        assertEquals(-1, b.disassemble(new Character.Worn("Nonsense", "Chest", 90)));    // nothing the data knows
        // assembled: the worn parts first (it comes out at their average) or the undamaged
        assertEquals(60, b.conditionOf(chest, false), 1e-9);
        assertEquals(100, b.conditionOf(chest, true), 1e-9);
        assertEquals(1, b.canAssembleFresh(chest));
        assertEquals(60, b.assemble(chest, 1, false), 1e-9);
        assertEquals(0, b.worn("Hard armour parts", 2, 1));
        assertEquals(100, b.assemble(chest, 1, true), 1e-9);
        assertEquals(-1, b.assemble(chest, 1, true), 1e-9);                            // none left: nothing taken
        // a mix: 7 hard undamaged, 4 hard and 2 soft worn at 40 % → (4 x 40 + 4 x 100 + 2 x 40) / 10
        b.addUnits("Hard armour parts", 2, 1, 7);
        b.disassemble(Production.piece("Metal Armor", "Head"), 1, 40);                 // 2 hard + 1 soft at 40 %
        b.disassemble(Production.piece("Metal Armor", "Head"), 1, 40);
        assertEquals((4 * 40 + 4 * 100 + 2 * 40) / 10.0, b.conditionOf(chest, false), 1e-9);   // the 4 worn hard first, then 4 undamaged
        // a broken item's parts come back at 0 %: they still count, and the repair job mends them
        Base z = new Base(null);
        assertEquals(10, z.disassemble(new Character.Worn("Metal Armor", "Chest", 0)));
        assertEquals(10, z.worn("Hard armour parts", 2, 1) + z.worn("Soft armour parts", 2, 1));
        // a batch comes apart only whole: seven arrows give one batch's parts, four give nothing
        Production.Design arrows = d("Arrows", "Stone-headed arrows");
        Base c = new Base(null);
        assertEquals(2, c.disassemble(arrows, 7, 100));
        assertEquals(0, c.disassemble(arrows, 4, 100));
        assertEquals(1, c.count("Light weapon parts", 1, 1));
    }

    @Test
    void theRepairMendsAWornRow() {
        // round 5 [mine, EXAMPLE]: points to up to 3 worn parts; a part that reaches 100 joins the undamaged
        Base b = new Base(null);
        b.disassemble(Production.piece("Metal Armor", "Chest"), 1, 60);              // 8 hard at 60 %
        assertEquals(3, b.repair("Hard armour parts", 2, 1, 30, 3));                   // 60 + 30 < 100: the average rises
        Base.Pool hard = b.pools.get(Base.key("Hard armour parts", 2, 1));
        assertEquals(0, hard.fresh);
        assertEquals((8 * 60 + 90) / 8.0, hard.average(), 1e-9);
        assertEquals(3, b.repair("Hard armour parts", 2, 1, 60, 3));                   // Mr. Fixit's 60: three to full
        assertEquals(3, hard.fresh);
        assertEquals(5, b.worn("Hard armour parts", 2, 1));
        assertEquals(0, b.repair("Firearm parts", 1, 1, 30, 3));                       // nothing worn there
        assertTrue(b.wornRows().stream().anyMatch(p -> p.type.equals("Hard armour parts")));
    }

    @Test
    void theChests() {
        Base b = new Base(null);
        Base.Spot a = new Base.Spot("world", 1, 4, 1), a2 = new Base.Spot("world", 2, 4, 1), m = new Base.Spot("world", 5, 4, 5);
        b.setChest("Hard armour parts", List.of(a, a2));                              // a double chest
        assertEquals("Hard armour parts", b.chestAt("world", 2, 4, 1));
        assertNull(b.chestAt("world_nether", 2, 4, 1));
        b.setChest("Soft armour parts", List.of(a, a2));                              // the same chest made another type's
        assertEquals("Soft armour parts", b.chestAt("world", 1, 4, 1));
        assertFalse(b.chests.containsKey("Hard armour parts"));
        b.setChest(Base.MESS, List.of(m));
        b.setChest(Base.MESS, List.of(new Base.Spot("world", 9, 4, 9)));               // several Mess Chests
        assertEquals(2, b.chests.get(Base.MESS).size());
        b.forget(List.of(m));
        assertNull(b.chestAt("world", 5, 4, 5));
        assertEquals(List.of("Soft armour parts", "Hard armour parts"), Base.types().subList(0, 2));
        assertEquals(26, Base.types().size());                                         // part 7's catalogue: a chest a type
        assertTrue(Base.category("Body armour"));                                      // round 5: a chest of an item category too
        assertEquals(40, Base.categories().size());                                    // 0.94.0: a rounds category a calibre (05d)
    }

    @Test
    void theFileAndTheOldStores(@TempDir Path dir) throws Exception {
        // 0.25.0 kept every armour piece apart: read once into parts (Body armour = 3 hard + 3 soft a piece, at its condition)
        Files.writeString(dir.resolve("base.json"), "{\"units\": [{\"type\": \"Body armour\", \"tier\": 2, \"rarity\": 1, \"condition\": 80},"
                + " {\"type\": \"Body armour\", \"tier\": 2, \"rarity\": 1, \"condition\": 40}], \"place\": {\"world\": \"world\"}}");
        Base b = new Base(dir);
        assertEquals(6, b.count("Hard armour parts", 2, 1));                          // 3 at 80 %, 3 at 40 %
        assertEquals(6, b.count("Soft armour parts", 2, 1));
        assertEquals(60, b.pools.get(Base.key("Hard armour parts", 2, 1)).average(), 1e-9);
        b.setChest(Base.MESS, List.of(new Base.Spot("world", 0, 4, 0)));
        b.disassemble(new Character.Worn("Leather Armor", "Legs", 70));
        Base again = new Base(dir);
        assertEquals(6, again.count("Hard armour parts", 2, 1));
        assertEquals(Base.MESS, again.chestAt("world", 0, 4, 0));
        assertTrue(again.unlocked(Data.ARMOUR.get("Leather Armor"), "Legs"));
        // 0.42.0's rows of the 46 types: into parts too; the current types stay; an old type's chest that is a category now stays
        Files.writeString(dir.resolve("base.json"), "{\"pools\": [{\"type\": \"Body armour\", \"tier\": 2, \"rarity\": 1, \"count\": 2, \"condition\": 200},"
                + " {\"type\": \"Head armour\", \"tier\": 2, \"rarity\": 1, \"count\": 2, \"condition\": 150},"
                + " {\"type\": \"Pistols\", \"tier\": 1, \"rarity\": 1, \"count\": 3, \"fresh\": 3},"
                + " {\"type\": \"Rifle ammo\", \"tier\": 2, \"rarity\": 1, \"count\": 20, \"fresh\": 20},"
                + " {\"type\": \"Metal\", \"tier\": 2, \"rarity\": 1, \"count\": 4, \"condition\": 400}],"
                + " \"chests\": {\"Body armour\": [{\"world\": \"world\", \"x\": 1, \"y\": 4, \"z\": 1}], \"Metal\": [{\"world\": \"world\", \"x\": 2, \"y\": 4, \"z\": 1}]}}");
        Base read = new Base(dir);
        assertEquals(6 + 2, read.count("Hard armour parts", 2, 1));                   // 2 bodies at 100 %, 2 heads at 75 %
        assertEquals(6, read.count("Firearm parts", 1, 1));                           // 3 pistols: 2 firearm parts + 1 light each
        assertEquals(3, read.count("Light weapon parts", 1, 1));
        assertEquals(40, read.count("Ammo", 2, 1));                                    // a rifle round = 2 loads
        assertEquals(4, read.pools.get("Metal, Tier II, rarity 1").fresh);             // 0.26.0's sum: all undamaged
        assertEquals("Body armour", read.chestAt("world", 1, 4, 1));                  // round 5: the category's chest
        assertEquals("Metal", read.chestAt("world", 2, 4, 1));
        assertEquals(6 + 2, new Base(dir).count("Hard armour parts", 2, 1));          // written back once, converted
        Files.writeString(dir.resolve("base.json"), "{ not json");
        assertTrue(new Base(dir).pools.isEmpty());                                    // a bad file is set aside, not lost
        try (var s = Files.list(dir)) { assertTrue(s.anyMatch(f -> f.getFileName().toString().startsWith("base.bad-"))); }
    }
}
