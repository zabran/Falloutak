package cz.lollywood.falloutak;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.108.0 (Marek 11-27, verbatim: *""The Trauma pack's splint is text only." - why? We do have broken legs and splinting, right?"* ·
 * *""Cateye's Perception −2 in a flash or sunlight is left to the GM." - sunlight is trivial to get in MC, it is light level. Flash from
 * explosion can be explosion effect."* · *""Nicotine's earlier frostbite isn't tied in." - add it please.* · *"it should be possible for
 * players to change what is on their belts by getting it from inventory."*).
 */
class BeltSplintTest extends MockGame {

    @BeforeEach
    void map() { Arena.use = world; Travel.use(WorldMapTest.fixture()); Travel.here("Roadside"); }

    @AfterEach
    void noMap() { Arena.use = null; Arena.current = null; Travel.map = null; Travel.file = null; Travel.scene.clear(); }

    private static Random calm() { return new Random() { @Override public double nextDouble() { return 0.99; } @Override public int nextInt(int b) { return 0; } }; }

    private static Data.Chem chem(String n) { Data.Chem c = Data.CHEMS.get(n); assertNotNull(c, n); return c; }

    @Test
    void aSplintedLegKnitsInSixWeeksTheTraumaPackSplintsHydraTrims() {
        Character c = AirTest.human();
        c.brokenLegs = 1;
        assertTrue(Meds.splint(19, c).startsWith("!"), "Medicine 20");
        String r = Meds.splint(20, c);
        assertEquals(1, c.splints.size(), r);
        assertTrue(Meds.splint(50, c).startsWith("!"), "already splinted");
        c.clock += Character.SPLINT_HOURS - 1;
        assertNull(c.splintHour(), "not yet");
        c.clock += 1;
        assertNotNull(c.splintHour());
        assertEquals(0, c.brokenLegs);
        assertTrue(c.splints.isEmpty());
        Character u = AirTest.human();
        u.brokenLegs = 1;
        u.clock += 2 * Character.SPLINT_HOURS;
        assertNull(u.splintHour(), "unsplinted it does not knit by itself");
        assertEquals(1, u.brokenLegs);
        Character t = AirTest.human();
        t.brokenLegs = 2;
        String k = Meds.traumaPack(40, t);
        assertTrue(k.contains("splinted"), k);
        assertEquals(1, t.splints.size(), "the kit splints one leg");
        t.dose(chem("Hydra"), calm());
        for (int h = 0; h < 72; h++) Hud.hour(t);
        assertEquals(1, t.brokenLegs);
        assertEquals(1, t.splints.size(), "one broken leg left, still splinted");
        t.brokenLegs = 0;
        t.trimSplints();
        assertTrue(t.splints.isEmpty(), "a leg mended another way leaves its splint");
    }

    @Test
    void cateyeHurtsInTheSunAndAnHourAfterABlastSeen() {
        Character c = AirTest.human();
        int p = c.attr(Rules.P, false);
        c.dose(chem("Cateye"), calm());
        c.inSun = false;
        assertEquals(p, c.attr(Rules.P, false));
        c.inSun = true;
        assertEquals(p - 2, c.attr(Rules.P, false), "the open sky by day");
        c.inSun = false;
        c.flashUntil = c.now() + 1;
        assertEquals(p - 2, c.attr(Rules.P, false), "after a flash");
        c.clock += 1;
        assertEquals(p, c.attr(Rules.P, false), "the hour is over");
        Character n = AirTest.human();
        n.inSun = true;
        assertEquals(p, n.attr(Rules.P, false), "without Cateye the sun is nothing");
        PlayerMock a = player();
        Character pc = plugin.character(a);
        pc.dose(chem("Cateye"), calm());
        Location boom = a.getLocation().clone().add(5, 0, 0);
        plugin.battle.flash(boom);
        assertTrue(pc.flashUntil > pc.now(), "a blast seen within 32 blocks");
        PlayerMock far = player(100.5, 0.5, false);
        Character fc = plugin.character(far);
        fc.dose(chem("Cateye"), calm());
        plugin.battle.flash(boom);
        assertTrue(fc.flashUntil < fc.now(), "too far to be dazzled");
    }

    @Test
    void aSmokerInACriticalColdGetsTheFrostbiteOneStageSooner() {
        Character s = AirTest.human();
        double max = s.max(Rules.Stat.HYPOTHERMIA);
        s.survival.put(Rules.Stat.HYPOTHERMIA, 0.7 * max);
        assertTrue(s.stages().contains("Critical Hypothermia"), s.stages().toString());
        s.dose(chem("Nicotine"), calm());
        java.util.List<String> cold = s.stages();
        s.stageHour(cold);
        s.survival.put(Rules.Stat.HYPOTHERMIA, 0.0);
        String r = s.stageHour(cold);
        assertNotNull(r);
        assertTrue(r.contains("one stage sooner"), r);
        assertTrue(s.effects.stream().anyMatch(e -> e.name().equals("Frostbite")));
        Character n = AirTest.human();
        n.survival.put(Rules.Stat.HYPOTHERMIA, 0.7 * max);
        java.util.List<String> c2 = n.stages();
        n.stageHour(c2);
        n.survival.put(Rules.Stat.HYPOTHERMIA, 0.0);
        n.stageHour(c2);
        assertTrue(n.effects.stream().noneMatch(e -> e.name().equals("Frostbite")), "no smoker: a Critical cold leaves none");
    }

    @Test
    void aSuperStimpakMovedFromThePackOntoTheBeltIsUsedThereAndGoneAfter() {
        PlayerMock a = player();
        a.getInventory().setItem(12, Items.make(Meds.id("Super Stimpak"), 2));
        a.getInventory().setItem(13, Items.make("chem:Psycho", 1));
        Travel.scene.add(new Travel.Met(new WorldMap.Encounter(Travel.map.kinds.get("raiders:pond"), "fight", 1), true));
        Travel.sceneTerrain = "field";
        assertFalse(Travel.battleMap(plugin, a, Arena.Mode.AMBUSH).startsWith("!"));
        Battle.State s = plugin.battle.state(a);
        Character c = plugin.character(a);
        assertNull(a.getInventory().getItem(4), "the belt's empty slot is free for the pack's items");
        a.getInventory().setItem(4, a.getInventory().getItem(12));   // dragged from the pack onto the belt
        a.getInventory().setItem(12, null);
        a.getInventory().setItem(5, a.getInventory().getItem(13));
        a.getInventory().setItem(13, null);
        c.hp = 10;
        int ap = s.ap;
        String r = plugin.battle.useBelt(a, s, c, 4);
        assertNotNull(r);
        assertTrue(r.contains("Super Stimpak"), r);
        assertTrue(c.holding);
        assertEquals(ap - Battle.BELT_AP, s.ap);
        assertEquals(1, a.getInventory().getItem(4).getAmount());
        String q = plugin.battle.useBelt(a, s, c, 5);
        assertNotNull(q, "a chem from the belt");
        assertEquals(10, c.other("dr"), 1e-9);
        assertNull(a.getInventory().getItem(5));
        a.getInventory().setItem(3, new ItemStack(org.bukkit.Material.STONE));
        assertNull(plugin.battle.useBelt(a, s, c, 3), "not a belt item");
        plugin.battle.stop(a);
        assertEquals(1, Meds.count(a, "Super Stimpak"), "the one used is gone from his gear");
        assertFalse(Items.carried(a).containsKey("chem:Psycho"));
    }

    /** 0.109.0, Marek 11-27: *""Belt: in a fight with your own gear" - this needs to be possible to prepare before battle."* */
    @Test
    void theBeltSetOnHotbarKeys3To6BeforeTheFightIsTheBeltInIt() {
        PlayerMock a = player();
        a.getInventory().setItem(2, Items.make("chem:Psycho", 1));                // key 3: a chem
        a.getInventory().setItem(3, Items.make(Meds.id("Super Stimpak"), 2));     // key 4: an injector (the grenades' usual slot)
        a.getInventory().setItem(5, Items.make("gear:Stimpak", 3));               // key 6: the Stimpaks
        a.getInventory().setItem(20, Items.make("gear:Stimpak", 1));              // one more in the pack: counted with them
        a.getInventory().setItem(21, Items.made(Production.design("Grenades", "Frag grenade"), 2, 100));   // grenades left in the pack
        a.getInventory().setItem(22, new ItemStack(org.bukkit.Material.STONE));
        Travel.scene.add(new Travel.Met(new WorldMap.Encounter(Travel.map.kinds.get("raiders:pond"), "fight", 1), true));
        Travel.sceneTerrain = "field";
        assertFalse(Travel.battleMap(plugin, a, Arena.Mode.AMBUSH).startsWith("!"));
        Battle.State s = plugin.battle.state(a);
        Character c = plugin.character(a);
        assertEquals("chem:Psycho", Items.id(a.getInventory().getItem(2)), "his chem stays on key 3");
        assertEquals(Meds.id("Super Stimpak"), Items.id(a.getInventory().getItem(3)), "his Super Stimpaks stay on key 4");
        assertEquals(2, a.getInventory().getItem(3).getAmount());
        assertEquals("stim", plugin.battle.id(a.getInventory().getItem(5)), "the Stimpaks where he put them");
        assertEquals(4, s.quick.get("stim"), "every Stimpak he carries, the pack's too");
        assertEquals("grenade", plugin.battle.id(a.getInventory().getItem(4)), "the grenades' usual slot was taken: the first free one");
        c.hp = 10;
        assertNotNull(plugin.battle.useBelt(a, s, c, 3));
        assertNotNull(plugin.battle.useBelt(a, s, c, 2));
        plugin.battle.stop(a);
        assertEquals(1, Meds.count(a, "Super Stimpak"), "the one used is gone");
        assertFalse(Items.carried(a).containsKey("chem:Psycho"));
        assertEquals(Meds.id("Super Stimpak"), Items.id(a.getInventory().getItem(3)), "his hotbar is back as he set it");
        assertEquals(3, a.getInventory().getItem(5).getAmount(), "the Stimpaks unused: all back");
    }

    @Test
    void withNothingPlacedTheStimpaksAndGrenadesTakeTheirUsualSlots() {
        PlayerMock a = player();
        a.getInventory().setItem(20, Items.make("gear:Stimpak", 2));
        a.getInventory().setItem(21, Items.made(Production.design("Grenades", "Riot gas grenade"), 1, 100));
        Travel.scene.add(new Travel.Met(new WorldMap.Encounter(Travel.map.kinds.get("raiders:pond"), "fight", 1), true));
        Travel.sceneTerrain = "field";
        assertFalse(Travel.battleMap(plugin, a, Arena.Mode.AMBUSH).startsWith("!"));
        assertEquals("stim", plugin.battle.id(a.getInventory().getItem(2)));
        assertNull(a.getInventory().getItem(3), "no grenades: a free slot");
        assertNull(a.getInventory().getItem(4));
        assertEquals("gasgren", plugin.battle.id(a.getInventory().getItem(5)));
        assertNull(Battle.beltKind(new ItemStack(org.bukkit.Material.STONE)));
        assertEquals("item:chem:Psycho", Battle.beltKind(Items.make("chem:Psycho", 1)));
        plugin.battle.stop(a);
    }
}
