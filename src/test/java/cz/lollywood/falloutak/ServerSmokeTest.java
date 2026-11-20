package cz.lollywood.falloutak;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

/** The plugin on a mock server (MockBukkit 4.116.3): the battle demo and the items, without a client. */
class ServerSmokeTest extends MockGame {
    @Test
    void theBattleDemoStartsAndStops() {
        PlayerMock p = player();
        p.performCommand("sheet battle");
        assertTrue(plugin.battle.inBattle(p));
        p.performCommand("sheet battle");
        assertFalse(plugin.battle.inBattle(p));
    }

    @Test
    void piecesAsItems() {
        PlayerMock p = player();
        Character.Worn w = new Character.Worn("Metal Armor", "Chest", 55, "large");
        ItemStack it = Items.piece(w);
        assertEquals(w, Items.worn(it));                                          // the condition and the fit travel in the item
        assertTrue(Items.title(Items.id(it)).startsWith("Metal Armor (chest)"));
        Items.giveStack(p, it);
        p.getInventory().setHelmet(Items.piece(new Character.Worn("Metal Armor", "Head", 80)));
        assertEquals(80, Items.readArmour(p).get("Head").condition(), 1e-9);
        // a hit wears the worn piece: the character's condition goes back into the item
        Character c = plugin.character(p);
        c.armour.put("Head", new Character.Worn("Metal Armor", "Head", 30));
        Items.writeArmour(p, c);
        assertEquals(30, Items.worn(p.getInventory().getHelmet()).condition(), 1e-9);
        // back in the base: the Mess Chest takes it apart into all its parts at its condition (round 5); fresh parts make it whole
        Inventory mess = server.createInventory(null, 27);
        mess.addItem(p.getInventory().getHelmet());
        assertEquals(1, Storage.sortMess(mess, plugin.base));
        assertTrue(mess.isEmpty());
        assertEquals(2, plugin.base.count("Hard armour parts", 2, 1));                // 2 hard + 1 soft, all at 30 %
        assertEquals(1, plugin.base.count("Soft armour parts", 2, 1));
        assertEquals(30, plugin.base.pools.get(Base.key("Hard armour parts", 2, 1)).average(), 1e-9);
        Production.Design helmet = Production.piece("Metal Armor", "Head");
        assertEquals(30, plugin.base.assemble(helmet, 1, false), 1e-9);               // the same parts: the same 30 %
        plugin.base.addUnits("Hard armour parts", 2, 1, 2);
        plugin.base.addUnits("Soft armour parts", 2, 1, 1);
        assertEquals(100, plugin.base.assemble(helmet, 1, true), 1e-9);               // undamaged parts: an undamaged helmet
    }

    @Test
    void theGearInThePack() {
        PlayerMock p = player();
        Items.give(p, "gear:Stimpak", 3);
        Items.give(p, "chem:" + Data.CHEMS.keySet().iterator().next(), 1);
        assertEquals(3, Items.carried(p).get("gear:Stimpak"));
        assertTrue(Items.take(p, "gear:Stimpak"));
        assertEquals(2, Items.carried(p).get("gear:Stimpak"));
        assertFalse(Items.take(p, "gear:Defibrillator"));
        Items.ensurePipBoy(p);
        assertTrue(Items.has(p, "pipboy"));
        for (String g : Items.GEAR) assertFalse(Items.describe("gear:" + g).isEmpty(), g);
        for (String s : Items.SUPPLIES) assertTrue(Items.title("supplies:" + s).startsWith("Surgical supplies"));
    }
}
