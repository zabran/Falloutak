package cz.lollywood.falloutak;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `09d` round 3 (Marek 11-23, verbatim in its §10): going round pays only the Survival check, a seen group can be talked to (the first
 * sight's bonus), a peaceful end on the battle map pays the full kill XP, a player leaves the map at its edge, the dangers attack each
 * other, more groups on a bigger map, food from a hunt, the talks (a toll, news) and the barter as menus.
 */
class EncounterRoundThreeTest extends MockGame {

    @BeforeEach
    void map() { Arena.use = world; Travel.use(WorldMapTest.fixture()); Travel.here("Roadside"); }

    @AfterEach
    void noMap() { Arena.use = null; Arena.current = null; Travel.map = null; Travel.file = null; Travel.scene.clear(); Talks.dice = null; Talks.tradingWith = null; }

    private static WorldMap.Kind kind(String key) { return Travel.map.kinds.get(key); }

    private Travel.Met scene(String key, String type, int count, boolean seen) {
        Travel.Met m = new Travel.Met(new WorldMap.Encounter(kind(key), type, count), seen);
        Travel.scene.add(m);
        Travel.sceneTerrain = "field";
        return m;
    }

    /** Dice that always roll this d100 (1-100). */
    private static Random d100(int roll) { return WorldMapTest.fixed((roll - 1) / 100.0 + 1e-9); }

    @Test
    void goingRoundPaysOnlyTheSurvivalCheck() {
        PlayerMock a = player();
        Character c = plugin.character(a);
        scene("raiders:pond", "fight", 8, true);
        long xp0 = c.xp;
        double surv = c.skill("Survival");
        String r = Travel.goRound(plugin, a);
        assertTrue(r.contains("Survival check"), r);
        assertEquals(Travel.dodgeXp(surv), c.xp - xp0, "100 − the chance it had, at least 10: " + r);
        assertTrue(c.xp - xp0 < 8 * Rules.killXp(30), "far less than their kill XP");
        assertEquals(10, Travel.dodgeXp(95));
        assertEquals(10, Travel.dodgeXp(150), "the chance is at most 95");
        assertEquals(70, Travel.dodgeXp(30));
    }

    @Test
    void aGroupSeenFirstCanBeTalkedDownForItsKillXp() {
        PlayerMock a = player();
        Character c = plugin.character(a);
        Travel.Met m = scene("raiders:pond", "fight", 4, true);
        Menus.actions(a);
        click("Travel (the world map)");
        assertTrue(page().text().contains("Talk to the Pond raiders") || page().buttons().stream().anyMatch(b -> b.label().startsWith("Talk to")), page().text());
        Talks.dice = d100(1);
        long xp0 = c.xp;
        String r = Talks.parley(plugin, a, m);
        assertTrue(r.contains("let you pass"), r);
        assertTrue(c.xp - xp0 >= Math.round(4 * Rules.killXp(30) * 0.99), "their full kill XP: " + (c.xp - xp0));
        assertTrue(Travel.scene.isEmpty());
        // the chance: Rhetorics + 20 (seen first) − 20 (they came to fight)
        Talks.Check k = Talks.check(c, Talks.SEEN_FIRST + Talks.HOSTILE, d100(1));
        assertEquals(Talks.clamp((int) Math.round(c.skill("Rhetorics"))), k.chance());
    }

    @Test
    void aFailedTalkIsAFightTheyBegin() {
        PlayerMock a = player();
        Travel.Met m = scene("raiders:pond", "fight", 2, true);
        Talks.dice = d100(100);
        String r = Talks.parley(plugin, a, m);
        assertTrue(r.contains("goes wrong") && r.contains("THE TALK FAILED"), r);
        Battle.Fight f = plugin.battle.fightOf(a);
        assertNotNull(f);
        assertTrue(f.enemiesFirst, "they go for their guns first");
        assertTrue(a.getLocation().getBlockZ() - 0 < 14, "the party kept its cover (the south side): " + a.getLocation());
        plugin.battle.stop(a);
    }

    @Test
    void aTollIsPaidInGoods() {
        PlayerMock a = player();
        a.getInventory().addItem(Items.make(Items.storeId("Food", 1, 1, "Raw meat"), 20));
        Travel.Met m = scene("raiders:pond", "talk", 6, true);
        Talks.talkMenu(plugin, a, m);
        assertTrue(page().text().contains("toll"), page().text());
        int toll = Talks.toll(a);
        int each = Talks.sellPrice(1, 1, plugin.character(a));
        assertTrue(each > 0);
        for (int k = 0; k < 30 && !Travel.scene.isEmpty(); k++) click(page().buttons().stream().filter(b -> b.label().startsWith("Give: Raw meat")).findFirst().orElseThrow().label());
        assertTrue(Travel.scene.isEmpty(), "paid: they step aside");
        int left = 0;
        for (ItemStack it : a.getInventory().getContents()) if (it != null && String.valueOf(Items.id(it)).contains("Raw meat")) left += it.getAmount();
        assertEquals(20 - (int) Math.ceil(toll / (double) each), left, "the toll's worth of meat is gone (toll " + toll + ", each " + each + ", Economy " + plugin.character(a).skill("Economy") + ", pages " + pages.size() + ")");
    }

    @Test
    void aTollRefusedIsAFight() {
        PlayerMock a = player();
        Travel.Met m = scene("bands", "talk", 5, true);
        Talks.talkMenu(plugin, a, m);
        clickAndClose("Refuse and walk on");
        Battle.Fight f = plugin.battle.fightOf(a);
        assertNotNull(f, "they turned hostile");
        assertTrue(Travel.scene.get(0).hostile());
        plugin.battle.stop(a);
    }

    @Test
    void aPatrolBringsNewsOfTheNearestRaiders() {
        String n = Talks.news();
        assertTrue(n.startsWith("\"Watch out") || n.contains("quiet"), n);
        Travel.here("Westtown");
        String n2 = Talks.news();
        assertNotNull(n2);
        assertEquals("north", Talks.compass(1, 0));
        assertEquals("south-west", Talks.compass(-1, -1));
    }

    @Test
    void tradersBarter() {
        PlayerMock a = player();
        Character c = plugin.character(a);
        a.getInventory().addItem(Items.make(Items.storeId("Textile", 2, 1, "Tanned leather"), 5));
        Travel.Met m = scene("traders", "trade", 6, true);
        Talks.dice = new Random(1);
        Talks.talkMenu(plugin, a, m);
        assertTrue(page().text().contains("Credit: 0"), page().text());
        int sell = Talks.sellPrice(2, 1, c);
        click("Sell: Tanned leather (" + sell + ")");
        assertEquals(sell, Talks.credit);
        click("Sell: Tanned leather (" + sell + ")");
        click("Sell: Tanned leather (" + sell + ")");
        // buy the cheapest of their wares with the credit
        String cheapest = null;
        int best = Integer.MAX_VALUE;
        for (var w : Talks.wares.keySet()) { int[] t = Talks.tr(w); int pr = Talks.buyPrice(t[0], t[1], c); if (pr < best) { best = pr; cheapest = w; } }
        assertNotNull(cheapest);
        int credit = Talks.credit;
        if (credit >= best) {
            click("Buy: " + Talks.name(cheapest) + " (" + best + ")");
            assertEquals(credit - best, Talks.credit);
            boolean got = false;
            for (ItemStack it : a.getInventory().getContents()) if (it != null && cheapest.equals(Items.id(it))) got = true;
            assertTrue(got, "the ware is his");
        }
        // the prices: they sell dearer than they buy, Economy narrows it
        assertTrue(Talks.buyPrice(1, 1, c) >= Talks.sellPrice(1, 1, c));
        assertEquals(10, Talks.value(1, 1));
        assertEquals(60, Talks.value(2, 2));
        clickAndClose("Done trading");
        assertTrue(Travel.scene.isEmpty());
    }

    @Test
    void aPlayerLeavingAtTheEdgeSlipsPastTheUntouchedForTheirKillXp() {
        PlayerMock a = player();
        Location was = a.getLocation().clone();
        Character c = plugin.character(a);
        scene("raiders:pond", "fight", 3, true);
        Travel.battleMap(plugin, a, Arena.Mode.AMBUSH);
        Battle.Fight f = plugin.battle.fightOf(a);
        assertNotNull(f.field);
        assertTrue(Battle.atEdge(f.field, new Location(world, f.field.getMinX() + 1, Arena.Y, f.field.getMinZ() + 20)));
        assertFalse(Battle.atEdge(f.field, new Location(world, f.field.getCenterX(), Arena.Y, f.field.getCenterZ())));
        long xp0 = c.xp;
        assertTrue(plugin.battle.leaveAtEdge(a));
        assertFalse(plugin.battle.inBattle(a));
        assertEquals(was.getBlockX(), a.getLocation().getBlockX(), "back on the world map's spot");
        long want = Math.round(3 * Rules.killXp(30) * Rules.xpShare(1, 0));
        assertTrue(Math.abs(c.xp - xp0 - want) <= 3 || c.xp - xp0 >= want * 0.9, "slipped past three: their kill XP (" + (c.xp - xp0) + " vs " + want + ")");
        assertTrue(Travel.scene.isEmpty());
    }

    @Test
    void theDangersAttackEachOther() {
        PlayerMock a = player();
        scene("raiders:pond", "fight", 1, true);
        scene("creature:boar", "fight", 1, true);
        Travel.battleMap(plugin, a, Arena.Mode.AMBUSH);
        Battle.Fight f = plugin.battle.fightOf(a);
        UUID raider = null, boar = null;
        for (UUID u : f.dummies) if (f.targets.get(u).melee) boar = u; else raider = u;
        assertNotNull(raider); assertNotNull(boar);
        assertNotEquals(f.targets.get(raider).group, f.targets.get(boar).group);
        // the boar right next to the raider, both far from the player: each goes for the other
        Entity re = server.getEntity(raider), be = server.getEntity(boar);
        Location l = re.getLocation().clone();
        be.teleport(l.clone().add(1, 0, 0));
        Object prey = plugin.battle.raiders.prey(f, boar, plugin.battle.players(f));
        assertEquals(raider, prey, "the boar goes for the raider beside it");
        // a patrol and a caravan leave each other be
        f.targets.get(raider).peaceable = true;
        f.targets.get(boar).peaceable = true;
        assertTrue(plugin.battle.raiders.prey(f, boar, plugin.battle.players(f)) instanceof org.bukkit.entity.Player);
        f.targets.get(raider).peaceable = false;
        f.targets.get(boar).peaceable = false;
        // its hit is not the party's: no kill XP for what another danger killed
        Battle.Target t = f.targets.get(raider);
        for (Battle.Target x : f.targets.values()) { x.dead = true; x.byParty = false; }
        long xp0 = plugin.character(a).xp;
        plugin.battle.checkOver(a, plugin.battle.state(a), plugin.character(a));
        assertEquals(xp0, plugin.character(a).xp, "killed by each other: nobody's XP");
        assertTrue(t.dead);
        server.getScheduler().performTicks(60);
    }

    @Test
    void aHuntFeedsThePartyThatHoldsTheField() {
        PlayerMock a = player();
        Travel.Met m = scene("creature:boar", "sight", 2, true);
        m.hunt = true;
        Travel.battleMap(plugin, a, Arena.Mode.HUNT);
        Battle.Fight f = plugin.battle.fightOf(a);
        for (Battle.Target t : f.targets.values()) { t.dead = true; t.byParty = true; t.touched = true; }
        plugin.battle.checkOver(a, plugin.battle.state(a), plugin.character(a));
        server.getScheduler().performTicks(60);
        assertFalse(plugin.battle.inBattle(a));
        int meat = 0, hides = 0;
        for (ItemStack it : a.getInventory().getContents()) {
            String id = Items.id(it);
            if (id == null) continue;
            if (id.contains("Raw meat")) meat += it.getAmount();
            if (id.contains("Raw hide")) hides += it.getAmount();
        }
        double kg = Arena.FOES.get("boar").meatKg();
        assertEquals((int) Math.round(2 * kg * 2), meat, "two boars of " + kg + " kg, a portion 0.5 kg");
        assertEquals(2, hides);
    }

    @Test
    void moreGroupsGetABiggerMap() {
        assertEquals(48, Arena.sizeFor(3));
        assertEquals(64, Arena.sizeFor(5));
        assertEquals(80, Arena.sizeFor(8));
        Arena.Layout big = Arena.build(world, "field", 9, 80);
        assertEquals(80, big.size());
        assertTrue(big.far().size() > Arena.build(world, "field", 9, 48).far().size(), "more room for them");
        for (Arena.Spot s : big.far()) assertTrue(s.z() - big.z0() >= 46, "their half of the bigger map");
        assertEquals(8, Travel.AT_ONCE);
    }
}
