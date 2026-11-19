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

import static org.junit.jupiter.api.Assertions.*;

/**
 * The store in Minecraft chests on the mock server (Marek 11-19: *"one chest per type, rows by tiers and rarities. And physical Mess
 * Chest that players can return items into."*).
 */
class StorageTest extends MockGame {
    Block chest(int x, int z) {
        Block b = world.getBlockAt(x, 4, z);
        b.setType(Material.CHEST);
        return b;
    }

    @Test
    void theMessChestSortsItsItemsIntoTheRows() {
        Inventory mess = server.createInventory(null, 27);
        mess.addItem(Items.piece(new Character.Worn("Metal Armor", "Chest", 60)));
        mess.addItem(Items.piece(new Character.Worn("Leather Armor", "Legs", 70)));
        mess.addItem(Items.make("gear:Stimpak", 2));
        mess.addItem(new ItemStack(Material.DIRT, 5));
        assertEquals(2, Storage.sortMess(mess, plugin.base));
        assertEquals(1, plugin.base.pools.get("Body armour, Tier II, rarity 1").count);
        assertTrue(plugin.base.unlocked(Data.ARMOUR.get("Leather Armor"), "Legs"));   // brought in: unlocked
        assertTrue(mess.contains(Material.DIRT));                                      // the rest stays in the chest
        int stims = 0;
        for (ItemStack it : mess.getContents()) if ("gear:Stimpak".equals(Items.id(it))) stims += it.getAmount();
        assertEquals(2, stims);
    }

    @Test
    void aTypeChestOpensAsTheStore() {
        PlayerMock p = player(0.5, 0.5, false);
        Block body = chest(2, 2);
        assertFalse(Storage.spots(body).isEmpty());
        plugin.base.setChest("Body armour", Storage.spots(body));
        plugin.base.put(new Character.Worn("Metal Armor", "Chest", 60));
        plugin.base.put(new Character.Worn("Vandal Armor", "Chest", 90));
        plugin.base.put(new Character.Worn("Leather Armor", "Chest", 50));            // its row: Tier I, rarity 2
        PlayerInteractEvent e = new PlayerInteractEvent(p, Action.RIGHT_CLICK_BLOCK, null, body, BlockFace.UP, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(e);
        assertTrue(e.useInteractedBlock() == org.bukkit.event.Event.Result.DENY || e.isCancelled());
        Inventory top = p.getOpenInventory().getTopInventory();
        assertInstanceOf(Storage.Holder.class, top.getHolder());
        Storage.Holder h = (Storage.Holder) top.getHolder();
        // row 1: Tier I rarity 2 (leather armour, unlocked by coming in); row 2: Tier II rarity 1 (two items, 75 % on average)
        assertEquals("Tier I, rarity 2", plain(top.getItem(0)));
        assertEquals("Tier II, rarity 1", plain(top.getItem(9)));
        assertTrue(lore(top.getItem(9)).contains("2 worn, 75 % on average"), lore(top.getItem(9)));
        assertFalse(lore(top.getItem(9)).contains("undamaged ("), lore(top.getItem(9)));
        int vandal = -1;
        for (int s = 10; s < 18; s++) if (top.getItem(s) != null && plain(top.getItem(s)).startsWith("Vandal Armor")) vandal = s;
        assertTrue(vandal > 0, "the vandal armour is one of the row's items");
        assertEquals("You take the Vandal Armor (chest) at 75 %.", plugin.storage.click(p, h, vandal));
        Character.Worn got = null;
        for (ItemStack it : p.getInventory().getStorageContents()) if (Items.worn(it) != null) got = Items.worn(it);
        assertEquals(new Character.Worn("Vandal Armor", "Chest", 75), got);
        assertTrue(lore(top.getItem(9)).contains("1 worn"), lore(top.getItem(9)));   // the screen refreshed
        assertNull(plugin.storage.click(p, h, 8));                                       // an empty slot: nothing
        // the last one goes, then the row is gone
        plugin.storage.click(p, h, vandal);
        assertEquals("Tier I, rarity 2", plain(top.getItem(0)));
        assertNull(top.getItem(9));
    }

    @Test
    void aRightClickTakesAnUndamagedOne() {
        PlayerMock p = player(0.5, 0.5, false);
        plugin.base.put(new Character.Worn("Metal Armor", "Chest", 100));
        plugin.base.put(new Character.Worn("Metal Armor", "Chest", 40));
        plugin.storage.open(p, "Body armour", 0);
        Inventory top = p.getOpenInventory().getTopInventory();
        Storage.Holder h = (Storage.Holder) top.getHolder();
        String label = lore(top.getItem(0));
        assertTrue(label.contains("1 undamaged (100 %)") && label.contains("1 worn, 40 % on average"), label);
        assertTrue(lore(top.getItem(1)).contains("left click: a worn one at 40 % (1 worn)"), lore(top.getItem(1)));
        assertTrue(lore(top.getItem(1)).contains("right click: an undamaged one at 100 % (1)"), lore(top.getItem(1)));
        String set = h.takes.get(1)[1];
        assertEquals("You take an undamaged " + set + " (chest) at 100 %.", plugin.storage.click(p, h, 1, true));
        assertTrue(plugin.storage.click(p, h, 1, true).startsWith("! no undamaged ones"));
        assertTrue(lore(top.getItem(1)).contains("right click: no undamaged ones"), lore(top.getItem(1)));
        assertEquals("You take the " + set + " (chest) at 40 %.", plugin.storage.click(p, h, 1, false));
        assertEquals("Nothing here yet", plain(top.getItem(22)));
    }

    @Test
    void anEmptyChestSaysWhereItemsGo() {
        PlayerMock p = player(0.5, 0.5, false);
        plugin.storage.open(p, "Footwear", 0);
        Inventory top = p.getOpenInventory().getTopInventory();
        assertEquals("Nothing here yet", plain(top.getItem(22)));
    }

    @Test
    void anOrcGetsHisSize() {
        PlayerMock p = player(0.5, 0.5, false);
        plugin.character(p).applyTemplate("Orc");
        plugin.base.put(new Character.Worn("Metal Armor", "Chest", 80));
        plugin.storage.open(p, "Body armour", 0);
        Storage.Holder h = (Storage.Holder) p.getOpenInventory().getTopInventory().getHolder();
        assertTrue(plugin.storage.click(p, h, 1).endsWith("at 80 %, made for a large body."));
        for (ItemStack it : p.getInventory().getStorageContents())
            if (Items.worn(it) != null) assertEquals("large", Items.worn(it).fitName());
    }

    @Test
    void theGmSetsTheChests() {
        PlayerMock gm = player(0.5, 0.5, true);
        Menus.gm(gm);
        Ui.Page base = click("Base");
        assertTrue(base.text().contains("Body armour: no chest yet"), base.text());
        Block b = chest(4, 4);
        assertEquals("That chest is now the head armour chest (4 4 4).", Menus.setChest(b, "Head armour"));
        assertEquals("Head armour", plugin.base.chestAt(world.getName(), 4, 4, 4));
        assertTrue(Menus.setChest(world.getBlockAt(6, 4, 6), Base.MESS).startsWith("! look at a chest"));   // air
        assertTrue(Menus.setChest(b, Base.MESS).contains(Base.MESS));
        assertEquals(Base.MESS, plugin.base.chestAt(world.getName(), 4, 4, 4));
        assertEquals("That chest is a plain chest again.", Menus.setChest(b, null));
        assertNull(plugin.base.chestAt(world.getName(), 4, 4, 4));
        // unlocking a set for the base
        Menus.gmBase(gm);
        click("Unlocked items");
        click("Filter", "f", "leather");
        click("Leather Armor (I/2)");
        assertTrue(plugin.base.unlocked(Data.ARMOUR.get("Leather Armor"), "Chest"));
        assertTrue(page().labels().contains("Leather Armor (I/2) · unlocked"), page().labels().toString());
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
