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
    void backThroughTheMessChestAnItemComesApartTheDamagedShareLost() {
        // round 4: "It also simplifies repair."
        Base b = new Base(null);
        Production.Design chest = Production.piece("Metal Armor", "Chest");           // 2 soft + 7 hard, Tier II
        assertEquals(9, chest.parts.stream().mapToInt(p -> p.count).sum());
        assertEquals(9, b.disassemble(new Character.Worn("Metal Armor", "Chest", 100)));
        assertEquals(2, b.count("Soft armour parts", 2, 1));
        assertEquals(7, b.count("Hard armour parts", 2, 1));
        assertEquals(5, b.disassemble(new Character.Worn("Metal Armor", "Chest", 60)));   // 2 x 0.6 = 1, 7 x 0.6 = 4
        assertEquals(0, b.disassemble(new Character.Worn("Metal Armor", "Chest", 0)));    // broken: nothing
        assertEquals(-1, b.disassemble(new Character.Worn("Nonsense", "Chest", 90)));    // nothing the data knows
        // reassembled at once: undamaged again (the repair)
        assertTrue(b.assemble(chest, 1));
        // a batch comes apart only whole: seven arrows give one batch's parts, four give nothing
        Production.Design arrows = d("Arrows", "Stone-headed arrows");
        Base c = new Base(null);
        assertEquals(2, c.disassemble(arrows, 7, 100));
        assertEquals(0, c.disassemble(arrows, 4, 100));
        assertEquals(1, c.count("Light weapon parts", 1, 1));
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
    }

    @Test
    void theFileAndTheOldStores(@TempDir Path dir) throws Exception {
        // 0.25.0 kept every armour piece apart: read once into parts (Body armour = 3 hard + 3 soft a piece, x its condition)
        Files.writeString(dir.resolve("base.json"), "{\"units\": [{\"type\": \"Body armour\", \"tier\": 2, \"rarity\": 1, \"condition\": 80},"
                + " {\"type\": \"Body armour\", \"tier\": 2, \"rarity\": 1, \"condition\": 40}], \"place\": {\"world\": \"world\"}}");
        Base b = new Base(dir);
        assertEquals(3, b.count("Hard armour parts", 2, 1));                          // 3 x 0.8 = 2, 3 x 0.4 = 1
        assertEquals(3, b.count("Soft armour parts", 2, 1));
        b.setChest(Base.MESS, List.of(new Base.Spot("world", 0, 4, 0)));
        b.disassemble(new Character.Worn("Leather Armor", "Legs", 70));
        Base again = new Base(dir);
        assertEquals(3, again.count("Hard armour parts", 2, 1));
        assertEquals(Base.MESS, again.chestAt("world", 0, 4, 0));
        assertTrue(again.unlocked(Data.ARMOUR.get("Leather Armor"), "Legs"));
        // 0.42.0's rows of the 46 types: into parts too; the current types stay; an old type's chest is dropped
        Files.writeString(dir.resolve("base.json"), "{\"pools\": [{\"type\": \"Body armour\", \"tier\": 2, \"rarity\": 1, \"count\": 2, \"condition\": 200},"
                + " {\"type\": \"Head armour\", \"tier\": 2, \"rarity\": 1, \"count\": 2, \"condition\": 150},"
                + " {\"type\": \"Pistols\", \"tier\": 1, \"rarity\": 1, \"count\": 3, \"fresh\": 3},"
                + " {\"type\": \"Rifle ammo\", \"tier\": 2, \"rarity\": 1, \"count\": 20, \"fresh\": 20},"
                + " {\"type\": \"Metal\", \"tier\": 2, \"rarity\": 1, \"count\": 4, \"condition\": 400}],"
                + " \"chests\": {\"Body armour\": [{\"world\": \"world\", \"x\": 1, \"y\": 4, \"z\": 1}], \"Metal\": [{\"world\": \"world\", \"x\": 2, \"y\": 4, \"z\": 1}]}}");
        Base read = new Base(dir);
        assertEquals(6 + 2, read.count("Hard armour parts", 2, 1));                   // 2 bodies at 100 %, 2 heads at 75 %: 2 x 1 x 0.75 = 2
        assertEquals(6, read.count("Firearm parts", 1, 1));                           // 3 pistols: 2 firearm parts + 1 light each
        assertEquals(3, read.count("Light weapon parts", 1, 1));
        assertEquals(40, read.count("Ammo", 2, 1));                                    // a rifle round = 2 loads
        assertEquals(4, read.pools.get("Metal, Tier II, rarity 1").fresh);             // 0.26.0's sum: all undamaged
        assertNull(read.chestAt("world", 1, 4, 1));
        assertEquals("Metal", read.chestAt("world", 2, 4, 1));
        assertEquals(6 + 2, new Base(dir).count("Hard armour parts", 2, 1));          // written back once, converted
        Files.writeString(dir.resolve("base.json"), "{ not json");
        assertTrue(new Base(dir).pools.isEmpty());                                    // a bad file is set aside, not lost
        try (var s = Files.list(dir)) { assertTrue(s.anyMatch(f -> f.getFileName().toString().startsWith("base.bad-"))); }
    }
}
