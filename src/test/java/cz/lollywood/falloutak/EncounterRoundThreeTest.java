package cz.lollywood.falloutak;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
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
    void noMap() { Arena.use = null; Arena.current = null; Travel.map = null; Travel.file = null; Travel.scene.clear(); Talks.dice = null; Talks.tradingWith = null; Loot.clear(); }

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
        Travel.scene.get(0).difficulty = 40;   // a forest at night (round 4: the XP = the check's difficulty; round 5: each group its own)
        long xp0 = c.xp;
        String r = Travel.goRound(plugin, a);
        assertTrue(r.contains("Survival check"), r);
        assertEquals(40, c.xp - xp0, "the check's difficulty: " + r);
        assertTrue(c.xp - xp0 < 8 * Rules.killXp(30), "far less than their kill XP");
        assertEquals(10, Travel.dodgeXp(0), "at least 10");
        assertEquals(20, Travel.dodgeXp(20));
    }

    /** Ends the party's turn and lets the enemies' turn run. */
    private void endRound(PlayerMock a) { plugin.battle.endTurnFor(a); server.getScheduler().performTicks(400); }

    @Test
    void aGroupSeenFirstCanBeTalkedDownForItsKillXp() {
        PlayerMock a = player();
        Character c = plugin.character(a);
        Travel.Met m = scene("raiders:pond", "fight", 4, true);
        Menus.actions(a);
        click("Travel (the world map)");
        click("Talk to " + m.e.kind().name());
        assertTrue(page().text().contains("Who speaks"), page().text());
        Talks.dice = d100(1);
        long xp0 = c.xp;
        clickAndClose(page().buttons().stream().filter(b -> b.label().startsWith(a.getName() + " speaks")).findFirst().orElseThrow().label());
        Battle.Fight f = plugin.battle.fightOf(a);
        assertNotNull(f, "the talk is on the battle map");
        assertNotNull(f.talk);
        assertFalse(f.enemiesFirst, "the party moves first: into position");
        for (Battle.Target t : f.targets.values()) assertTrue(t.talking);
        for (int k = 0; k < Battle.TALK_ROUNDS && plugin.battle.inBattle(a); k++) endRound(a);
        assertFalse(plugin.battle.inBattle(a), "talked down: the fight is over");
        assertTrue(c.xp - xp0 >= Math.round(4 * Rules.killXp(30) * 0.99), "their full kill XP: " + (c.xp - xp0));
        assertTrue(Travel.scene.isEmpty());
        // the chance: Rhetorics + 20 (seen first) − 20 (they came to fight)
        Talks.Check k = Talks.check(c, Talks.SEEN_FIRST + Talks.HOSTILE, d100(1));
        assertEquals(Talks.clamp((int) Math.round(c.skill("Rhetorics"))), k.chance());
    }

    @Test
    void aFailedTalkGoesWrongInItsRoundsAndTheyShootFirst() {
        PlayerMock a = player();
        Travel.Met m = scene("raiders:pond", "fight", 2, true);
        Talks.dice = d100(100);   // failed; it goes wrong at the end of round 3
        String r = Talks.parley(plugin, a, m);
        assertTrue(r.contains("THE TALK"), r);
        Battle.Fight f = plugin.battle.fightOf(a);
        assertNotNull(f);
        assertEquals(3, f.talk.failAt);
        assertTrue(a.getLocation().getBlockZ() - Arena.coverZ < 6, "the party kept its cover, " + Arena.ESCAPE + " blocks from the south edge: " + a.getLocation());
        endRound(a);
        endRound(a);
        assertNotNull(f.talk, "still talking in round 3");
        plugin.battle.endTurnFor(a);
        assertNull(f.talk, "it went wrong");
        for (Battle.Target t : f.targets.values()) assertFalse(t.talking, "they go for their guns");
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
        // what another danger killed counts too (round 4, Marek 11-24: "I would count that")
        Battle.Target t = f.targets.get(raider);
        for (Battle.Target x : f.targets.values()) { x.dead = true; x.byParty = false; }
        long xp0 = plugin.character(a).xp;
        plugin.battle.checkOver(a, plugin.battle.state(a), plugin.character(a));
        assertTrue(plugin.character(a).xp - xp0 >= Math.round((Rules.killXp(30) + Rules.killXp(Arena.FOES.get("boar").hp())) * 0.99) - 2,
                "killed by each other: their XP is the party's (" + (plugin.character(a).xp - xp0) + ")");
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
        // round 4: each body is butchered from the field's menu (a Survival check: here a sure one)
        assertEquals(2, Loot.bodies.size());
        plugin.character(a).points[Rules.skillIndex("Survival")] += 200;   // every check a comparison (02c): all of the meat
        for (Loot.Body x : List.copyOf(Loot.bodies)) assertFalse(Loot.butcher(plugin, a, x).startsWith("!"));
        int meat = 0, hides = 0;
        for (ItemStack it : a.getInventory().getContents()) {
            String id = Items.id(it);
            if (id == null) continue;
            if (id.contains("Raw meat")) meat += it.getAmount();
            if (id.contains("Raw hide")) hides += it.getAmount();
        }
        double kg = Arena.FOES.get("boar").meatKg();
        assertEquals(2 * (int) Math.round(kg * 2), meat, "two boars of " + kg + " kg, a portion 0.5 kg");
        assertEquals(2 * Arena.FOES.get("boar").hides(), hides);
    }

    @Test
    void moreGroupsGetABiggerMap() {
        assertEquals(128, Arena.sizeFor(3, 25));
        assertEquals(128, Arena.sizeFor(5, 25));
        assertEquals(160, Arena.sizeFor(8, 25), "six groups or more");
        Arena.Layout big = Arena.build(world, "field", 9, 160, 20);
        assertEquals(160, big.size());
        assertFalse(big.far().isEmpty());
        for (Arena.Spot s : big.far()) assertTrue(s.z() - big.z0() >= Arena.coverZ + 18, "at the start distance north of the party's line");
        assertEquals(8, Travel.AT_ONCE);
    }
}
