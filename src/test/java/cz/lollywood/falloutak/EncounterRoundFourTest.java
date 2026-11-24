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
 * `09d` round 4 (Marek 11-24, verbatim in its §10): the go-round pays the check's difficulty, the talk is on the battle map (one speaker,
 * the party moving into position, an attack catching them off guard), the battle map at least 64 and growing with the reach, every kill
 * counts, every body can be looted (Survival butchers, Medicine treats and takes samples), a patrol's news of hidden camps for pay, a good
 * word or a barter.
 */
class EncounterRoundFourTest extends MockGame {

    @BeforeEach
    void map() { Arena.use = world; Travel.use(WorldMapTest.fixture()); Travel.here("Roadside"); }

    @AfterEach
    void noMap() { Arena.use = null; Arena.current = null; Travel.map = null; Travel.file = null; Travel.scene.clear(); Talks.dice = null; Talks.tradingWith = null; Loot.clear(); }

    private Travel.Met scene(String key, String type, int count, boolean seen) {
        Travel.Met m = new Travel.Met(new WorldMap.Encounter(Travel.map.kinds.get(key), type, count), seen);
        Travel.scene.add(m);
        Travel.sceneTerrain = "field";
        return m;
    }

    private static Random d100(int roll) { return WorldMapTest.fixed((roll - 1) / 100.0 + 1e-9); }

    private static int count(PlayerMock a, String part) {
        int n = 0;
        for (ItemStack it : a.getInventory().getContents()) { String id = Items.id(it); if (id != null && id.contains(part)) n += it.getAmount(); }
        return n;
    }

    @Test
    void theSpottingCheckHasADifficultyAndTheGoRoundPaysIt() {
        assertEquals(0, Travel.spotDifficulty("field", true));
        assertEquals(20, Travel.spotDifficulty("road", false));
        assertEquals(20, Travel.spotDifficulty("forest", true));
        assertEquals(40, Travel.spotDifficulty("town", false));
        PlayerMock a = player();
        Character c = plugin.character(a);
        scene("raiders:pond", "fight", 5, true);
        Travel.sceneDifficulty = 0;
        long xp0 = c.xp;
        Travel.goRound(plugin, a);
        assertEquals(10, c.xp - xp0, "an easy check: the minimum");
    }

    @Test
    void anAttackInTheTalkCatchesThemOffGuard() {
        PlayerMock a = player(), b = player(1.5, 0.5, true);
        Travel.Met m = scene("raiders:pond", "fight", 3, true);
        Talks.speakerMenu(plugin, a, m);
        assertTrue(page().buttons().stream().anyMatch(x -> x.label().startsWith(b.getName() + " speaks")), "anyone of the party can speak");
        Talks.dice = d100(1);
        Talks.parley(plugin, a, m, b);
        Battle.Fight f = plugin.battle.fightOf(a);
        assertNotNull(f.talk);
        assertEquals(b.getUniqueId(), f.talk.speaker);
        assertEquals(2, plugin.battle.players(f).size(), "the whole party");
        plugin.battle.breakTalk(f, a);
        assertNull(f.talk);
        for (Battle.Target t : f.targets.values()) { assertFalse(t.talking); assertTrue(t.surprised, "caught off guard"); }
        plugin.battle.enemyPhase(f, () -> { });
        for (Battle.Target t : f.targets.values()) assertFalse(t.surprised, "only for the rest of the party's turn");
        plugin.battle.stop(a);
        plugin.battle.stop(b);
    }

    @Test
    void anotherDangerFallingOnTheTalkersEndsTheTalk() {
        PlayerMock a = player();
        scene("raiders:pond", "fight", 1, true);
        Travel.Met m = Travel.scene.get(0);
        scene("creature:boar", "fight", 1, true);
        Talks.dice = d100(1);
        Talks.parley(plugin, a, m);
        Battle.Fight f = plugin.battle.fightOf(a);
        UUID raider = null, boar = null;
        for (UUID u : f.dummies) if (f.targets.get(u).foe.equals("boar")) boar = u; else raider = u;   // round 5: a raider may fight in melee too
        assertTrue(f.targets.get(raider).talking);
        assertFalse(f.targets.get(boar).talking, "the boar does not talk");
        Entity re = server.getEntity(raider), be = server.getEntity(boar);
        be.teleport(re.getLocation().clone().add(1, 0, 0));
        plugin.battle.endTurnFor(a);   // the talk goes on; the boar's turn: it falls on the raider
        server.getScheduler().performTicks(400);
        assertNull(f.talk, "the boar fell on the raider: the talk is over (round 5: a good talk makes them allies)");
        if (plugin.battle.inBattle(a)) plugin.battle.stop(a);
    }

    @Test
    void theBattleMapIsAlways128WithThePartyNearItsEdge() {
        // round 5 (Marek 11-24): "use only the 128 sized ones (or bigger) and in case of smaller encounter, place the players near the border"
        assertEquals(128, Arena.sizeFor(1, 25), "the pistol");
        assertEquals(128, Arena.sizeFor(1, 2), "teeth");
        assertEquals(128, Arena.sizeFor(1, 40), "the bolt-action rifle");
        assertEquals(128, Arena.sizeFor(5, 40));
        assertEquals(Arena.MAX_SIZE, Arena.sizeFor(8, 40));
        assertEquals(160, Arena.MAX_SIZE);
        assertEquals(20, Arena.startDistance(25));
        assertEquals(12, Arena.startDistance(1));
        assertEquals(40, Arena.startDistance(80));
        Arena.Layout l = Arena.build(world, "road", 5, 128, 20);
        assertEquals(Arena.ESCAPE, Arena.coverZ);
        for (Arena.Spot s : l.cover()) assertTrue(s.z() - l.z0() >= Arena.ESCAPE - 4, "the party's line has " + Arena.ESCAPE + " blocks behind it: " + s);
        for (Arena.Spot s : l.far()) assertTrue(s.z() - l.z0() >= Arena.coverZ + 18 && s.z() - l.z0() <= 60, "them at the start distance: " + s);
        // the party's own rifle widens it
        PlayerMock a = player();
        Production.Design rifle = Production.design("Rifles", "Pipe bolt-action rifle");
        if (rifle == null) for (Production.Design d : Production.DESIGNS.values()) if (d.name.equals("Pipe bolt-action rifle")) rifle = d;
        assertNotNull(rifle);
        a.getInventory().addItem(Items.made(rifle, 1, 100));
        scene("raiders:pond", "fight", 2, true);
        assertEquals(40, Arena.reach(Travel.party(a), Travel.groups(false)));
        Travel.battleMap(plugin, a, Arena.Mode.AMBUSH);
        assertEquals(Arena.sizeFor(1, 40), Arena.size);
        plugin.battle.stop(a);
    }

    @Test
    void everyBodyCanBeLooted() {
        PlayerMock a = player();
        Character c = plugin.character(a);
        scene("raiders:pond", "fight", 1, true);
        scene("creature:boar", "fight", 1, true);
        Travel.battleMap(plugin, a, Arena.Mode.AMBUSH);
        Battle.Fight f = plugin.battle.fightOf(a);
        for (Battle.Target t : f.targets.values()) { if (Loot.PEOPLE.contains(t.foe)) t.hp = -1; else t.dead = true; t.byParty = true; t.touched = true; }
        plugin.battle.checkOver(a, plugin.battle.state(a), plugin.character(a));
        server.getScheduler().performTicks(60);
        assertEquals(2, Loot.bodies.size());
        Menus.actions(a);
        click("Travel (the world map)");
        click("The field: 2 bodies");
        Loot.Body raider = Loot.bodies.stream().filter(x -> x.person).findFirst().orElseThrow();
        Loot.Body boar = Loot.bodies.stream().filter(x -> !x.person).findFirst().orElseThrow();
        assertFalse(raider.dead, "down and dying");
        // the raider: his own weapon (09d round 5: a pistol, a bow or a melee weapon) and what he had left; treated he lives, a captive
        String r = Loot.search(plugin, a, raider);
        assertTrue(r.contains(raider.weapon), r);
        assertEquals(1, count(a, raider.weapon));
        // every check is a comparison (02c): a Medicine and a Survival far above every need
        c.points[Rules.skillIndex("Medicine")] += 200;
        c.points[Rules.skillIndex("Survival")] += 200;
        long xp0 = c.xp;
        assertTrue(Loot.treat(plugin, a, raider).contains("stabilise"));
        assertTrue(raider.treated && !raider.dead);
        assertEquals(Loot.treatNeed(raider), c.xp - xp0, "the check's XP = its difficulty");
        // his kidney, with his blood group; a creature's organ is rejected within hours: none
        assertTrue(Loot.organ(plugin, a, boar).startsWith("!"));
        Loot.Body other = new Loot.Body(raiderTarget(), new Random(2));
        assertTrue(Loot.organ(plugin, a, other).contains("blood group " + other.blood), other.blood);
        // the boar: butchered (meat, fat, hide, sinew), a tissue sample
        assertFalse(Loot.butcher(plugin, a, boar).startsWith("!"));
        assertTrue(count(a, "Raw meat") > 0 && count(a, "Raw fat") > 0 && count(a, "Raw hide") > 0 && count(a, "Sinew") > 0);
        assertTrue(count(a, "Tusks") > 0 && count(a, "Bones") > 0, "the boar's parts at this Survival");
        assertTrue(Loot.sample(plugin, a, boar).contains("tissue sample"));
        assertEquals(1, count(a, "body:Tissue sample|"));
        assertEquals(2, count(a, "body:Donor kidney|"), "both kidneys");
        assertTrue(Loot.butcher(plugin, a, boar).startsWith("!"), "once a body");
        // no Survival: half the meat and no hide
        Loot.Body b2 = new Loot.Body(boarTarget(), new Random(1));
        c.points[Rules.skillIndex("Survival")] -= 400;
        int m0 = count(a, "Raw meat"), h0 = count(a, "Raw hide");
        assertTrue(Loot.butcher(plugin, a, b2).contains("Lost: the hide"));
        assertEquals((int) Math.round(Arena.FOES.get("boar").meatKg()), count(a, "Raw meat") - m0);
        assertEquals(h0, count(a, "Raw hide"));
        // setting out leaves the field
        Loot.collect(f, new Random(1));
        assertFalse(Loot.bodies.isEmpty());
        Travel.go(plugin, a, "Westtown", false, new Random(3));
        assertTrue(Loot.bodies.isEmpty());
    }

    private static Battle.Target raiderTarget() {
        Battle.Target t = new Battle.Target("Raider 2");
        t.foe = "raider"; t.hp = -1;
        return t;
    }

    private static Battle.Target boarTarget() {
        Arena.Foe b = Arena.FOES.get("boar");
        Battle.Target t = new Battle.Target("Boar", b.hp(), b.ac(), b.dt(), b.dr(), b.a(), b.skill());
        t.melee = true; t.dead = true; t.meatKg = b.meatKg(); t.hides = b.hides(); t.foe = "boar";
        return t;
    }

    @Test
    void aPatrolTellsOfHiddenCampsOnlyForPayOrAGoodWord() {
        PlayerMock a = player();
        WorldMap.Src hiddenOne = null;
        for (WorldMap.Src b : Travel.map.bases) if (b.type.equals("raiders") && b.hidden) { hiddenOne = b; break; }
        assertNotNull(hiddenOne, "the fixture has a hidden camp");
        // stand at the hidden camp: the free news never names it
        Travel.pi = hiddenOne.i + 0.5; Travel.pj = hiddenOne.j + 0.5;
        for (WorldMap.Src b : Travel.map.bases) if (b != hiddenOne && b.type.equals("raiders")) b.cleared = true;
        assertFalse(Talks.news(false).contains("hidden"), Talks.news(false));
        assertTrue(Talks.news(true).contains("hidden"), Talks.news(true));
        a.getInventory().addItem(Items.make(Items.storeId("Textile", 2, 1, "Tanned leather"), 10));
        Travel.Met m = scene("patrol:" + Travel.map.kinds.keySet().stream().filter(k -> k.startsWith("patrol:")).findFirst().orElseThrow().substring(7), "talk", 4, true);
        Talks.talkMenu(plugin, a, m);
        assertTrue(page().text().contains("hidden camps costs"), page().text());
        for (int k = 0; k < 10 && !Travel.scene.isEmpty(); k++)
            clickAndClose(page().buttons().stream().filter(x -> x.label().startsWith("Pay: Tanned leather")).findFirst().orElseThrow().label());
        assertTrue(Travel.scene.isEmpty(), "paid: they tell");
        assertTrue(said(a).contains("hidden"), "the hidden camp named");
    }
}
