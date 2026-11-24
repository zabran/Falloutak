package cz.lollywood.falloutak;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `09d` round 5 (Marek 11-24, verbatim in its §10): the spotting's difficulty from the terrain, the weather and the light (at most +50) and
 * each group's stealth or visibility; the GM's attack in a talk; allies of convenience when another danger falls on the map; the battle
 * map always 128 with a visible edge, a fight near a unique map on that map; the field graded by skill with priorities; the raiders' bows
 * and melee weapons and their hoarded rounds; the food's way to Tier III in two steps.
 */
class EncounterRoundFiveTest extends MockGame {

    @BeforeEach
    void map() { Arena.use = world; Travel.use(WorldMapTest.fixture()); Travel.here("Roadside"); Travel.weatherSet = false; Travel.weather = "clear"; }

    @AfterEach
    void noMap() {
        Arena.use = null; Arena.current = null; Travel.map = null; Travel.file = null; Travel.scene.clear(); Talks.dice = null; Talks.tradingWith = null;
        Loot.clear(); Travel.weatherSet = false; Travel.weather = "clear";
    }

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
    void theConditionsAreAtMostFiftyAndEachGroupHasItsOwnDifficulty() {
        assertEquals(0, Travel.light(12));
        assertEquals(10, Travel.light(5.5), "dawn");
        assertEquals(10, Travel.light(18.5), "dusk");
        assertEquals(20, Travel.light(23));
        assertEquals(50, Travel.conditions("forest", 23, "fog"), "20 + 20 + 20, at most 50");
        assertEquals(10, Travel.conditions("field", 12, "rain"));
        assertEquals(45, Travel.conditions("town", 5.5, "snow"));
        // sneaking: their stealth check, THE WORST SNEAK IN THE GROUP IN FULL (Marek 11-24, round 6); in the open: how visible and
        // audible they are
        assertEquals(45, Travel.targetTerm(Travel.map.kinds.get("creature:wolves"), 6, true), "60 (round 6: coursing hunters) less 3 a head beyond the first, at most 15");
        assertEquals(60, Travel.targetTerm(Travel.map.kinds.get("creature:wolves"), 1, true), "alone: his own Sneak");
        assertEquals(74, Travel.worstSneak(80, 3));
        assertEquals(-20, Travel.targetTerm(Travel.map.kinds.get("raiders:pond"), 8, false), "eight of them: four over four, -5 each");
        assertEquals(-30, Travel.targetTerm(Travel.map.kinds.get("raiders:pond"), 20, false), "at most -30");
        assertEquals(35, Travel.targetTerm(Travel.map.kinds.get("raiders:pond"), 8, true));
        // the go-round pays each group's check
        PlayerMock a = player();
        Character c = plugin.character(a);
        scene("raiders:pond", "fight", 8, true).difficulty = 45;
        scene("creature:wolves", "fight", 6, true).difficulty = 5;
        long xp0 = c.xp;
        String r = Travel.goRound(plugin, a);
        assertEquals(45 + 10, c.xp - xp0, r);
        assertTrue(r.contains("45 + 5"), r);
    }

    @Test
    void theWeatherIsDrawnByTheSeasonOrSetByTheGm() {
        Travel.season = 2;
        Random rnd = new Random(4);
        Map<String, Integer> seen = new HashMap<>();
        for (int d = 0; d < 400; d++) { Travel.dayWeather(d, rnd); seen.merge(Travel.weather, 1, Integer::sum); }
        assertFalse(seen.containsKey("snow"), "no snow in summer: " + seen);
        assertTrue(seen.getOrDefault("clear", 0) > 200, seen.toString());
        PlayerMock g = player();
        g.setOp(true);
        String r = Travel.command(plugin, g, new String[]{"weather", "fog"});
        assertTrue(r.contains("fog"), r);
        Travel.dayWeather(999, rnd);
        assertEquals("fog", Travel.weather, "the GM's weather stays");
        Travel.command(plugin, g, new String[]{"weather", "auto"});
        assertFalse(Travel.weatherSet);
    }

    @Test
    void theGmCanMakeTheTalkGoWrongAtOnce() {
        PlayerMock a = player();
        Travel.Met m = scene("raiders:pond", "fight", 2, true);
        Talks.dice = d100(1);   // a talk going well
        Talks.parley(plugin, a, m);
        Battle.Fight f = plugin.battle.fightOf(a);
        assertNotNull(f.talk);
        String r = Travel.gmAttack(plugin);
        assertTrue(r.contains("they act first"), r);
        assertNull(f.talk, "the talk went wrong");
        server.getScheduler().performTicks(400);
        for (Battle.Target t : f.targets.values()) assertFalse(t.talking);
        plugin.battle.stop(a);
    }

    @Test
    void aDangerFallingOnTheTalkMakesTheTalkersAllies() {
        PlayerMock a = player();
        Character c = plugin.character(a);
        Travel.Met m = scene("raiders:pond", "fight", 2, true);
        scene("creature:boar", "fight", 1, true);
        Talks.dice = d100(1);
        Talks.parley(plugin, a, m);
        Battle.Fight f = plugin.battle.fightOf(a);
        assertNotNull(f.talk);
        int boarGroup = -1, raiderGroup = -1;
        UUID raider = null;
        for (var en : f.targets.entrySet()) {
            if (en.getValue().talking) { raiderGroup = en.getValue().group; raider = en.getKey(); }
            else boarGroup = en.getValue().group;
        }
        assertTrue(boarGroup > 0 && raiderGroup > 0 && boarGroup != raiderGroup);
        long xp0 = c.xp;
        plugin.battle.allies(f, boarGroup);
        assertNull(f.talk);
        int allies = 0;
        for (Battle.Target t : f.targets.values()) if (t.group == raiderGroup) { assertTrue(t.ally && t.paid); allies++; }
        assertEquals(2, allies);
        assertTrue(c.xp - xp0 >= Math.round(2 * Rules.killXp(30) * 0.99), "resolved without killing: their full kill XP, " + (c.xp - xp0));
        // an ally never goes for the party
        Object prey = plugin.battle.raiders.prey(f, raider, plugin.battle.players(f));
        assertFalse(prey instanceof org.bukkit.entity.Player, "an ally's prey: " + prey);
        // the boar beaten: the fight is over though the allies stand, and their XP is not paid twice
        long xp1 = c.xp;
        for (Battle.Target t : f.targets.values()) if (t.group == boarGroup) { t.dead = true; t.byParty = true; }
        assertTrue(plugin.battle.checkOver(a, plugin.battle.state(a), c));
        assertTrue(c.xp - xp1 < Math.round(2 * Rules.killXp(30)), "only the boar's: " + (c.xp - xp1));
        server.getScheduler().performTicks(60);
    }

    @Test
    void anUndecidedTalkMakesAlliesAndAnAttackedAllyTurnsAgain() {
        PlayerMock a = player();
        Travel.Met m = scene("raiders:pond", "fight", 2, true);
        scene("creature:boar", "fight", 1, true);
        Talks.dice = d100(100);   // failing
        Talks.parley(plugin, a, m);
        Battle.Fight f = plugin.battle.fightOf(a);
        int raiderGroup = -1, boarGroup = -1;
        for (Battle.Target t : f.targets.values()) if (t.talking) raiderGroup = t.group; else boarGroup = t.group;
        // 09d round 6 (Marek: *"if they do not yet shoot each other, common enemy will make them into allies"*): the check failed, but
        // nobody shoots yet, so the common enemy makes them allies all the same
        plugin.battle.allies(f, boarGroup);
        assertNull(f.talk);
        int allies = 0;
        for (Battle.Target t : f.targets.values()) if (t.group == raiderGroup) { assertTrue(t.ally, "an undecided talk makes allies"); allies++; }
        assertEquals(2, allies);
        // an ally attacked: the whole group is hostile again
        plugin.battle.betray(f, raiderGroup, a);
        for (Battle.Target t : f.targets.values()) assertFalse(t.ally);
        plugin.battle.stop(a);
    }

    @Test
    void raiderGroupsDrawBowsAndMeleeWeaponsAndHoardTheirRounds() {
        Random r = new Random(11);
        Map<String, Integer> bands = new HashMap<>(), early = new HashMap<>(), late = new HashMap<>();
        for (int k = 0; k < 4000; k++) {
            bands.merge(Arena.kitFor("bands", 2, r).key(), 1, Integer::sum);
            early.merge(Arena.kitFor("raiders:pond", 2, r).key(), 1, Integer::sum);
            late.merge(Arena.kitFor("raiders:pond", 8, r).key(), 1, Integer::sum);
        }
        assertEquals(0.15, bands.get("raider") / 4000.0, 0.03, "a band's guns: " + bands);
        assertEquals(0.45, bands.get("raider-bow") / 4000.0, 0.03);
        assertEquals(0.55, early.get("raider") / 4000.0, 0.03, "a tribe in year 2: " + early);
        assertEquals(0.15, late.get("raider") / 4000.0, 0.03, "in year 8: " + late);
        assertEquals("boar", Arena.kitFor("creature:boar", 2, r).key());
        assertEquals("patrol", Arena.kitFor("patrol:count", 2, r).key());
        // the archer: a bow and ten arrows; the brawler: a melee weapon; out of both, the knife
        Battle.Target t = new Battle.Target("Raider archer");
        Arena.arm(t, Arena.FOES.get("raider-bow"), new Random(3));
        assertEquals("Bows", t.gun.skill);
        assertFalse(t.melee);
        assertEquals(10, t.reserve);
        assertEquals("bow.shot", Raiders.shotSound(t));
        Battle.Target b = new Battle.Target("Raider brawler");
        Arena.arm(b, Arena.FOES.get("raider-melee"), new Random(3));
        assertTrue(b.melee);
        assertTrue(Arena.FOES.get("raider-melee").weapons().contains(b.weaponName));
        assertEquals(Battle.GEAR.get(b.weaponName).maxDmg, b.maxDmg);
        Raiders.drawKnife(t);
        assertTrue(t.melee);
        assertEquals(Battle.KNIFE.maxDmg, t.maxDmg);
        assertEquals(12, Arena.FOES.get("raider").reserve(), "the pistol raider's one spare magazine");
    }

    @Test
    void aFightOnOrNearAUniqueMapHappensOnIt() {
        WorldMap.OnUnique on = Travel.map.unique(3.5, 3.5);
        assertNotNull(on);
        assertEquals("Testtown", on.u().name());
        assertEquals(140, on.x());
        assertEquals(140, on.z());
        WorldMap.OnUnique near = Travel.map.unique(3.5, 8.0);   // 20 blocks past its edge: near enough, the nearest point inside
        assertNotNull(near);
        assertEquals(236, near.x());
        assertNull(Travel.map.unique(20, 35), "far away: the arena");
        PlayerMock a = player();
        Travel.here("Westtown");
        scene("raiders:pond", "fight", 2, true);
        String r = Travel.battleMap(plugin, a, Arena.Mode.AMBUSH);
        assertTrue(r.contains("the Testtown map"), r);
        Battle.Fight f = plugin.battle.fightOf(a);
        assertEquals(128, f.field.getWidthX(), 1e-9);
        assertTrue(f.field.getMinX() >= 0 && f.field.getMaxX() <= 300 && f.field.getMinZ() >= 0 && f.field.getMaxZ() <= 300, f.field.toString());
        assertTrue(f.field.contains(a.getLocation().toVector()), "the party on the cut-out: " + a.getLocation());
        plugin.battle.stop(a);
    }

    @Test
    void theFieldGivesMoreToMoreSkillAndThePriorityDecides() {
        assertEquals(0.5, Loot.meatShare(0, null, false), 1e-9);
        assertEquals(1.0, Loot.meatShare(100, null, false), 1e-9);
        assertEquals(0.9, Loot.meatShare(60, Loot.Focus.MEAT, false), 1e-9);
        assertEquals(0.7, Loot.meatShare(60, Loot.Focus.PARTS, true), 1e-9);
        assertEquals(10, Loot.need(25, Loot.Focus.HIDE, Loot.Focus.HIDE));
        assertEquals(35, Loot.need(25, Loot.Focus.HIDE, Loot.Focus.MEAT));
        assertEquals(25, Loot.need(25, Loot.Focus.HIDE, null));
        PlayerMock a = player();
        Character c = plugin.character(a);
        c.points[Rules.skillIndex("Survival")] += (int) Math.round(70 - c.skill("Survival"));
        assertEquals(70, c.skill("Survival"), 1.0);
        // a Radmie's venom (fragile, 60): ruptured when the meat came first; taken when the parts did
        String r1 = Loot.butcher(plugin, a, new Loot.Body(creature("radmie"), new Random(1)), Loot.Focus.MEAT);
        assertTrue(r1.contains("Radmie venom (ruptured"), r1);
        assertEquals(0, count(a, "Radmie venom"));
        String r2 = Loot.butcher(plugin, a, new Loot.Body(creature("radmie"), new Random(1)), Loot.Focus.PARTS);
        assertFalse(r2.contains("ruptured"), r2);
        assertEquals(1, count(a, "Radmie venom"));
        // donor organs by Medicine: the kidneys at 40, the liver beyond 50, the heart ends a dying donor
        c.points[Rules.skillIndex("Medicine")] += (int) Math.round(50 - c.skill("Medicine"));
        Battle.Target pt = new Battle.Target("Raider 3");
        pt.foe = "raider"; pt.hp = -1;
        Loot.Body donor = new Loot.Body(pt, new Random(5));
        assertTrue(Loot.organ(plugin, a, donor, "kidney").contains("2 × kidney"));
        assertTrue(Loot.organ(plugin, a, donor, "liver").startsWith("! the liver is beyond you"));
        c.points[Rules.skillIndex("Medicine")] += 30;
        assertFalse(donor.dead);
        assertTrue(Loot.organ(plugin, a, donor, "heart").contains("heart"));
        assertTrue(donor.dead, "the heart ends him");
        // an archer's body: his bow and his arrows
        Battle.Target archer = new Battle.Target("Raider archer");
        archer.foe = "raider-bow"; archer.hp = -1;
        Arena.arm(archer, Arena.FOES.get("raider-bow"), new Random(3));
        Loot.Body ab = new Loot.Body(archer, new Random(1));
        assertTrue(ab.bow);
        assertEquals(archer.ammo + archer.reserve, ab.rounds);
        String s = Loot.search(plugin, a, ab);
        assertTrue(s.contains(archer.weaponName) && s.contains("arrows"), s);
    }

    private static Battle.Target creature(String key) {
        Arena.Foe f = Arena.FOES.get(key);
        Battle.Target t = new Battle.Target(f.name(), Math.max(1, f.hp()), f.ac(), f.dt(), f.dr(), f.a(), f.skill());
        t.foe = key; t.meatKg = f.meatKg(); t.hides = f.hides(); t.dead = true;
        return t;
    }

    @Test
    void theFoodsWayToTierThreeIsTwoSteps() {
        assertNull(Production.RECIPES.get("wash-and-peel"), "washing and peeling is the first season's only: gone");
        Production.Recipe leach = ProductionTest.recipe("leach-food"), brine = ProductionTest.recipe("brine-and-soak"),
                potash = ProductionTest.recipe("field-potatoes-on-potash"), cheese = ProductionTest.recipe("make-cheese-and-butter");
        assertEquals(2, leach.makes.get(0).tier);
        assertEquals(2, leach.makes.get(0).rarity, "the step between: Food II R2");
        assertEquals(2, potash.makes.get(0).rarity);
        assertEquals("Rad scrubbing", potash.research);
        assertEquals(3, brine.makes.get(0).tier);
        assertTrue(brine.inputs.stream().anyMatch(i -> i.type.equals("Food") && i.tier == 2 && i.rarity == 2), "only the step's food");
        assertTrue(brine.inputs.stream().anyMatch(i -> i.items != null && i.items.contains("Salt")), "salt takes it the rest of the way");
        assertEquals(3, cheese.makes.get(0).tier, "the caesium stays in the whey");
        // 09e round 2 (Marek 11-24: *"please, reuse brine"*): one salt cleans three times the food (6 Food III from 9 Food II R2)
        assertEquals(60, brine.makes.get(0).count);   // 07 §19 round 3: 90 in, 1 Salt (~0.03 kg a kg, the salt simulation)
        assertEquals(1, brine.inputs.stream().filter(i -> i.items != null && i.items.contains("Salt")).mapToInt(i -> i.count).sum());
        assertEquals(9, brine.inputs.stream().filter(i -> i.type.equals("Food")).mapToInt(i -> i.count).sum() / 10);   // 90 Food II R2
        // a run: Food II R1 leached lands in the Food II R2 row
        Production pr = new Production();
        pr.build("Reactor", 4, "Reactor");
        Production.Building k = pr.build("Kitchen", 2, "K");
        Base base = new Base(null);
        base.addUnits("Water", 3, 1, 40);
        base.addUnits("Food", 2, 1, 3);
        base.addUnits("Water", 2, 1, 8);
        base.addUnits("Fuel", 1, 1, 4);
        base.addUnits("Gear and tools", 2, 1, 2);
        ProductionTest.Crew crew = new ProductionTest.Crew(ProductionTest.worker("C", "Biology", 40));
        pr.start(k, leach, 1, 1, crew);
        List<String> out = pr.advance(12, 0, base, crew, 0);
        assertEquals(2, base.count("Food", 2, 2), "two leached portions: " + out + " " + (k.job == null ? "" : k.job.waiting));
        assertEquals(0, base.count("Food", 2, 1));
    }
}
