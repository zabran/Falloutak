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
        mess.addItem(Items.piece(new Character.Worn("Metal Armor", "Chest", 60)));    // 2 soft + 7 hard, all at 60 % (round 5)
        mess.addItem(Items.piece(new Character.Worn("Leather Armor", "Legs", 70)));   // 1 soft I R2 at 70 %
        mess.addItem(Items.make("gear:Stimpak", 2));
        mess.addItem(Items.made(Production.design("Pistols", "Pipe pistol"), 1, 40)); // assembled from worn parts: its 40 % back
        mess.addItem(Items.make(Items.madeId(Production.design("Rifle rounds", "Rifle reloads")), 12));
        mess.addItem(Items.make(Items.thingId("Pistol ammo", 1, 1, "Remanufactured pistol rounds"), 10));   // 0.42.0's old type
        mess.addItem(new ItemStack(Material.DIRT, 5));
        Set<String> parts = new TreeSet<>();
        assertEquals(1 + 1 + 2 + 1 + 12 + 10, Storage.sortMess(mess, plugin.base, parts));
        assertEquals(2, plugin.base.count("Soft armour parts", 2, 1));
        assertEquals(7, plugin.base.count("Hard armour parts", 2, 1));
        assertEquals(60, plugin.base.pools.get(Base.key("Hard armour parts", 2, 1)).average(), 1e-9);
        assertEquals(1, plugin.base.count("Soft armour parts", 1, 2));
        assertEquals(40, plugin.base.pools.get(Base.key("Firearm parts", 1, 1)).average(), 1e-9);
        assertTrue(plugin.base.unlocked(Data.ARMOUR.get("Leather Armor"), "Legs"));   // brought in: unlocked
        assertEquals(2, plugin.base.count("Medicine", 4, 1));                          // the catalogue's row (07b)
        assertEquals(2, plugin.base.count("Firearm parts", 1, 1));
        assertEquals(24, plugin.base.count("Ammo", 2, 1));                              // 12 rifle rounds x 2 loads
        assertEquals(10, plugin.base.count("Ammo", 1, 1));                              // the old pistol ammo: a load a round
        assertTrue(mess.contains(Material.DIRT));                                      // the rest stays in the chest
        assertTrue(parts.containsAll(List.of("Hard armour parts|2", "Firearm parts|1", "Ammo|2")), parts.toString());
    }

    @Test
    void reverseEngineerWaitsForResearch() {
        // round 5 (Marek 11-20): "Reverse Engineer - this is seriously strong now ... once it is gained, it skyrockets research."
        assertTrue(Data.PERKS.get("Reverse Engineer").parked());
    }

    @Test
    void aCategoryChestAssemblesOnAClick() {
        // round 5 (Marek 11-20): "make sure that the "store chests" still works (by automaticaly assembling the parts into the item,
        // if there is enough parts)"
        PlayerMock p = player(0.5, 0.5, false);
        Block body = chest(2, 6);
        plugin.base.setChest("Body armour", Storage.spots(body));
        Production.Design metal = Production.piece("Metal Armor", "Chest");
        plugin.base.disassemble(metal, 1, 50);                                         // one at 50 % in parts
        for (Production.Part q : metal.parts) plugin.base.addUnits(q.type, q.tier, metal.rarity, q.count);   // and one undamaged
        PlayerInteractEvent e = new PlayerInteractEvent(p, Action.RIGHT_CLICK_BLOCK, null, body, BlockFace.UP, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(e);
        Storage.Holder h = (Storage.Holder) p.getOpenInventory().getTopInventory().getHolder();
        int slot = -1;
        for (var en : h.designs.entrySet()) if (en.getValue().equals(metal.key())) slot = en.getKey();
        assertTrue(slot > 0, h.designs.toString());
        assertTrue(lore(h.inv.getItem(slot)).contains("2 ready (1 from undamaged parts)"), lore(h.inv.getItem(slot)));
        assertTrue(plugin.storage.click(p, h, slot, false, false).startsWith("Assembled: 1 × Metal Armor (chest), at 50 % (worn parts)"));
        assertTrue(plugin.storage.click(p, h, slot, true, false).startsWith("Assembled: 1 × Metal Armor (chest)."));
        assertTrue(plugin.storage.click(p, h, slot, false, false).startsWith("! not enough parts"));
        List<Double> got = new java.util.ArrayList<>();
        for (ItemStack it : p.getInventory().getStorageContents()) if (Items.worn(it) != null) got.add(Items.worn(it).condition());
        got.sort(null);
        assertEquals(List.of(50.0, 100.0), got);
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
        assertTrue(base.text().contains("27 of the 27 types have no chest yet"), base.text());
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
