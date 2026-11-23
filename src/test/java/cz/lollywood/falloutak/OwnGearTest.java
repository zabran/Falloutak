package cz.lollywood.falloutak;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `09d` round 3 (Marek 11-23): *"your own gear in fights - please continue with that."* A fight on a battle map takes the weapons, rounds,
 * Stimpaks and grenades the player carries (`data/weapons.json` for part 7's designs), writes back what was used and the weapons'
 * condition, and a death in it is real.
 */
class OwnGearTest extends MockGame {

    @BeforeEach
    void map() { Arena.use = world; Travel.use(WorldMapTest.fixture()); Travel.here("Roadside"); }

    @AfterEach
    void noMap() { Arena.use = null; Arena.current = null; Travel.map = null; Travel.file = null; Travel.scene.clear(); }

    private static ItemStack made(String category, String name, int n, double cond) {
        Production.Design d = Production.design(category, name);
        assertNotNull(d, category + "|" + name);
        return Items.made(d, n, cond);
    }

    private static int count(PlayerMock p, String part) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) { String id = Items.id(it); if (id != null && id.contains(part)) n += it.getAmount(); }
        return n;
    }

    private Battle.State fight(PlayerMock p) {
        Travel.scene.add(new Travel.Met(new WorldMap.Encounter(Travel.map.kinds.get("raiders:pond"), "fight", 2), true));
        Travel.sceneTerrain = "road";
        String r = Travel.battleMap(plugin, p, Arena.Mode.AMBUSH);
        assertTrue(r.startsWith("AMBUSH"), r);
        return plugin.battle.state(p);
    }

    @Test
    void everyWeaponDesignHasItsBattleRow() {
        for (Production.Design d : Production.DESIGNS.values()) {
            Production.Category cat = null;
            for (Production.Category c : Production.CATEGORIES) if (c.name.equals(d.category)) cat = c;
            if (cat == null || !(cat.group.equals("Melee weapons") || cat.group.equals("Ranged weapons"))) continue;
            Battle.Weapon w = Battle.GEAR.get(d.name);
            assertNotNull(w, "a battle row for " + d.name);
            assertTrue(w.minDmg >= 1 && w.minDmg <= w.maxDmg, d.name + " damage");
            assertTrue(w.range >= 2 && !w.modes.isEmpty(), d.name + " range and modes");
            if (cat.group.equals("Ranged weapons") && !w.thrown)
                assertTrue(Battle.ROUNDS.contains(w.ammoKind) || w.ammoKind.equals("Power"), d.name + " loads " + w.ammoKind);
            assertEquals(cat.group.equals("Melee weapons"), w.melee(), d.name + " melee or not");
        }
        assertEquals(Battle.SMG.minDmg, Battle.GEAR.get("10mm Pistol").minDmg, "the workbook's pistol as the workbook has it");
        assertTrue(Battle.GEAR.get("Pipe launcher").blastMax > 0, "a rocket bursts");
        assertTrue(Battle.GEAR.get("Javelin").thrown);
        assertTrue(Battle.weapons().size() > 40, "Signature Weapon can pick any of them");
    }

    @Test
    void theKitIsWhatHeCarries() {
        PlayerMock p = player();
        p.getInventory().addItem(made("Pistols", "Pipe pistol", 1, 60));
        p.getInventory().addItem(made("Pistol rounds", "Remanufactured pistol rounds", 10, 100));
        p.getInventory().addItem(Items.make("gear:Stimpak", 2));
        p.getInventory().addItem(made("Grenades", "Frag grenade", 1, 100));
        Battle.State s = fight(p);
        assertTrue(s.own);
        assertEquals("w:Pipe pistol", idOf(p, 0));
        assertEquals("w:Fists", idOf(p, 1), "no second weapon: the bare hand");
        Battle.Weapon w = Battle.GEAR.get("Pipe pistol");
        assertEquals(6, Battle.ammoOf(s, w), "loaded from his rounds");
        assertEquals(4, s.reserve.get("Pistol rounds"));
        assertEquals(2, s.quick.get("stim"));
        assertEquals(1, s.quick.get("grenade"));
        assertEquals(0.6 * w.maxHealth, s.health.get("Pipe pistol"), 1e-9, "its own condition");
        // a reload from the pack, and the pack runs dry
        s.ammo.put(w.tag, 0);
        assertTrue(Battle.reload(s, w));
        assertEquals(4, Battle.ammoOf(s, w));
        s.ammo.put(w.tag, 0);
        assertFalse(Battle.reload(s, w), "no rounds left");
        plugin.battle.stop(p);
    }

    private static String idOf(PlayerMock p, int slot) {
        ItemStack it = p.getInventory().getItem(slot);
        if (it == null || !it.hasItemMeta()) return "";
        return it.getItemMeta().getPersistentDataContainer().get(new org.bukkit.NamespacedKey("falloutak", "fo_item"), org.bukkit.persistence.PersistentDataType.STRING);
    }

    @Test
    void whatHeUsedIsGoneAndHisGunKeepsItsWear() {
        PlayerMock p = player();
        p.getInventory().addItem(made("Pistols", "Pipe pistol", 1, 100));
        p.getInventory().addItem(made("Pistol rounds", "Remanufactured pistol rounds", 10, 100));
        p.getInventory().addItem(Items.make("gear:Stimpak", 2));
        Battle.State s = fight(p);
        s.used.put("Pistol rounds", 3);
        s.used.put("stim", 1);
        s.health.put("Pipe pistol", 0.4 * Battle.GEAR.get("Pipe pistol").maxHealth);
        plugin.battle.stop(p);
        assertEquals(7, count(p, "pistol rounds"));
        assertEquals(1, count(p, "Stimpak"));
        double cond = -1;
        for (ItemStack it : p.getInventory().getContents()) if (String.valueOf(Items.id(it)).contains("Pipe pistol")) cond = Items.madeCondition(it);
        assertEquals(40, cond, 0.5, "the pistol comes back at 40 %");
    }

    @Test
    void aThrownWeaponIsPickedUpByThoseWhoHoldTheField() {
        PlayerMock p = player();
        p.getInventory().addItem(made("Throwing weapons", "Javelin", 3, 100));
        Battle.State s = fight(p);
        Battle.Weapon j = Battle.GEAR.get("Javelin");
        assertEquals(3, Battle.ammoOf(s, j), "three to throw");
        s.used.put("thrown:Javelin", 2);
        for (Battle.Target t : s.fight.targets.values()) { t.dead = true; t.byParty = true; }
        plugin.battle.checkOver(p, s, plugin.character(p));
        server.getScheduler().performTicks(60);
        assertEquals(3, count(p, "Javelin"), "won: picked up again");
        // fled: lost
        Battle.State s2 = fight(p);
        s2.used.put("thrown:Javelin", 2);
        plugin.battle.leaveAtEdge(p);
        assertEquals(1, count(p, "Javelin"), "fled: two left lying there");
    }

    @Test
    void aDeathOnABattleMapIsReal() {
        PlayerMock p = player();
        Character c = plugin.character(p);
        Battle.State s = fight(p);
        assertFalse(s.fight.demo);
        c.hp = -c.attrs(false)[Rules.E] - 5;
        plugin.battle.died(p, s, c, false);
        assertFalse(plugin.battle.inBattle(p));
        assertTrue(c.hp < 0, "not restored: " + c.hp);
    }
}
