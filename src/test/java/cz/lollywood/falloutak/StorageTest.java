package cz.lollywood.falloutak;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The store in Minecraft chests on the mock server (Marek 11-19: *"one chest per type, rows by tiers and rarities. And physical Mess
 * Chest that players can return items into."*); since part 7's round 4 the chests hold parts and the Mess Chest takes items apart.
 */
class StorageTest extends MockGame {
    Block chest(int x, int z) {
        Block b = world.getBlockAt(x, 4, z);
        b.setType(Material.CHEST);
        return b;
    }

    @Test
    void theMessChestTakesItemsApartAndSortsThings() {
        Inventory mess = server.createInventory(null, 27);
        mess.addItem(Items.piece(new Character.Worn("Metal Armor", "Chest", 60)));    // 2 soft + 7 hard x 60 %: 1 + 4
        mess.addItem(Items.piece(new Character.Worn("Leather Armor", "Legs", 70)));   // 1 soft I R2 x 70 %: 1
        mess.addItem(Items.make("gear:Stimpak", 2));
        mess.addItem(Items.make(Items.madeId(Production.design("Pistols", "Pipe pistol")), 1));
        mess.addItem(Items.make(Items.madeId(Production.design("Rifle rounds", "Rifle reloads")), 12));
        mess.addItem(Items.make(Items.thingId("Pistol ammo", 1, 1, "Remanufactured pistol rounds"), 10));   // 0.42.0's old type
        mess.addItem(new ItemStack(Material.DIRT, 5));
        Set<String> parts = new TreeSet<>();
        assertEquals(1 + 1 + 2 + 1 + 12 + 10, Storage.sortMess(mess, plugin.base, parts));
        assertEquals(1, plugin.base.count("Soft armour parts", 2, 1));
        assertEquals(4, plugin.base.count("Hard armour parts", 2, 1));
        assertEquals(1, plugin.base.count("Soft armour parts", 1, 2));
        assertTrue(plugin.base.unlocked(Data.ARMOUR.get("Leather Armor"), "Legs"));   // brought in: unlocked
        assertEquals(2, plugin.base.count("Medicine", 4, 1));                          // the catalogue's row (07b)
        assertEquals(2, plugin.base.count("Firearm parts", 1, 1));
        assertEquals(24, plugin.base.count("Ammo", 2, 1));                              // 12 rifle rounds x 2 loads
        assertEquals(10, plugin.base.count("Ammo", 1, 1));                              // the old pistol ammo: a load a round
        assertTrue(mess.contains(Material.DIRT));                                      // the rest stays in the chest
        assertTrue(parts.containsAll(List.of("Hard armour parts|2", "Firearm parts|1", "Ammo|2")), parts.toString());
    }

    @Test
    void reverseEngineerLearnsHowToMakeTheParts() {
        // 02c: "taking a working item apart gives you its schematic" (round 4: the researched ways to its parts)
        Production pr = new Production();
        List<String> got = Storage.reverseEngineer(pr, Set.of("Firearm parts|2"));
        assertEquals(List.of("Gun parts on jigs"), got);
        assertTrue(pr.known.contains("gun-parts-on-jigs"));
        assertEquals(List.of(), Storage.reverseEngineer(pr, Set.of("Firearm parts|2")));   // known now
    }

    @Test
    void aTypeChestOpensAsTheStore() {
        PlayerMock p = player(0.5, 0.5, false);
        Block hard = chest(2, 2);
        assertFalse(Storage.spots(hard).isEmpty());
        plugin.base.setChest("Hard armour parts", Storage.spots(hard));
        plugin.base.addUnits("Hard armour parts", 2, 1, 5);
        plugin.base.addUnits("Hard armour parts", 1, 1, 3);
        PlayerInteractEvent e = new PlayerInteractEvent(p, Action.RIGHT_CLICK_BLOCK, null, hard, BlockFace.UP, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(e);
        assertTrue(e.useInteractedBlock() == org.bukkit.event.Event.Result.DENY || e.isCancelled());
        Inventory top = p.getOpenInventory().getTopInventory();
        assertInstanceOf(Storage.Holder.class, top.getHolder());
        Storage.Holder h = (Storage.Holder) top.getHolder();
        assertEquals("Tier I, rarity 1", plain(top.getItem(0)));
        assertEquals("Tier II, rarity 1", plain(top.getItem(9)));
        assertEquals("Hard armour parts II", plain(top.getItem(10)));
        assertEquals("You take 1 × Hard armour parts II.", plugin.storage.click(p, h, 10));
        assertEquals(4, plugin.base.count("Hard armour parts", 2, 1));
        assertEquals("You take 3 × Hard armour parts I.", plugin.storage.click(p, h, 1, false, true));   // shift: up to 16
        assertNull(plugin.storage.click(p, h, 8));                                       // an empty slot: nothing
        assertEquals("Tier II, rarity 1", plain(top.getItem(0)));                        // the empty row is gone
    }

    @Test
    void anEmptyChestSaysWhereItemsGo() {
        PlayerMock p = player(0.5, 0.5, false);
        plugin.storage.open(p, "Soft armour parts", 0);
        Inventory top = p.getOpenInventory().getTopInventory();
        assertEquals("Nothing here yet", plain(top.getItem(22)));
    }

    @Test
    void theGmSetsTheChests() {
        PlayerMock gm = player(0.5, 0.5, true);
        Menus.gm(gm);
        Ui.Page base = click("Base");
        assertTrue(base.text().contains("26 of the 26 types have no chest yet"), base.text());
        Block b = chest(4, 4);
        assertEquals("That chest is now the hard armour parts chest (4 4 4).", Menus.setChest(b, "Hard armour parts"));
        assertEquals("Hard armour parts", plugin.base.chestAt(world.getName(), 4, 4, 4));
        assertTrue(Menus.setChest(world.getBlockAt(6, 4, 6), Base.MESS).startsWith("! look at a chest"));   // air
        assertTrue(Menus.setChest(b, Base.MESS).contains(Base.MESS));
        assertEquals(Base.MESS, plugin.base.chestAt(world.getName(), 4, 4, 4));
        assertEquals("That chest is a plain chest again.", Menus.setChest(b, null));
        assertNull(plugin.base.chestAt(world.getName(), 4, 4, 4));
        // unlocking a set, and an item, for the assembly
        Menus.gmBase(gm);
        click("Unlocked items");
        click("Filter", "f", "leather");
        click("Leather Armor (I/2)");
        assertTrue(plugin.base.unlocked(Data.ARMOUR.get("Leather Armor"), "Chest"));
        assertTrue(page().labels().contains("Leather Armor (I/2) · unlocked"), page().labels().toString());
        click("Filter", "f", "10mm");
        click("10mm Pistol (III) · Pistols");
        assertTrue(plugin.base.unlocked(Production.design("Pistols", "10mm Pistol")));
        assertTrue(page().labels().contains("10mm Pistol (III) · Pistols · unlocked"), page().labels().toString());
    }

    static String plain(ItemStack it) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it.getItemMeta().displayName());
    }

    static String lore(ItemStack it) {
        StringBuilder sb = new StringBuilder();
        List<net.kyori.adventure.text.Component> l = it.getItemMeta().lore();
        if (l != null) for (var c : l) sb.append(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c)).append("\n");
        return sb.toString();
    }
}
