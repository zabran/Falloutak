package cz.lollywood.falloutak;

import org.bukkit.inventory.ItemStack;
import java.util.List;
import java.util.Map;
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
            assertTrue(w.minDmg >= 1 && w.minDmg <= w.maxDmg || w.maxDmg == 0 && !w.entangle.isEmpty(), d.name + " damage");   // the net: none, it entangles
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
        p.getInventory().addItem(made("Pistol rounds", "Remanufactured pistol rounds", 10, 100));   // the old rounds: now 9x19 (0.94.0)
        p.getInventory().addItem(Items.make("gear:Stimpak", 2));
        p.getInventory().addItem(made("Grenades", "Frag grenade", 1, 100));
        Battle.State s = fight(p);
        assertTrue(s.own);
        assertEquals("w:Pipe pistol", idOf(p, 0));
        assertEquals("w:Fists", idOf(p, 1), "no second weapon: the bare hand");
        Battle.Weapon w = Battle.GEAR.get("Pipe pistol");
        assertEquals(1, Battle.ammoOf(s, w), "loaded from his rounds: a Tier I pipe gun is single-shot (05d §3b)");
        assertEquals(9, Battle.reserveOf(s, "9x19 rounds"));
        assertEquals("9x19 rounds|9x19 remanufactured", s.load.get(w.tag), "the old rounds are the 9x19 remanufactured");
        assertEquals(2, s.quick.get("stim"));
        assertEquals(1, s.quick.get("grenade"));
        assertEquals(0.6 * w.maxHealth, s.health.get("Pipe pistol"), 1e-9, "its own condition");
        // a reload from the pack, and the pack runs dry
        s.ammo.put(w.tag, 0);
        assertTrue(Battle.reload(s, w));
        assertEquals(1, Battle.ammoOf(s, w));
        assertEquals(8, Battle.reserveOf(s, "9x19 rounds"));
        s.ammo.put(w.tag, 0);
        s.reserve.put("9x19 rounds|9x19 remanufactured", 0);
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
        p.getInventory().addItem(made("9x19 rounds", "9x19 remanufactured", 10, 100));
        p.getInventory().addItem(Items.make("gear:Stimpak", 2));
        Battle.State s = fight(p);
        s.used.put("9x19 rounds|9x19 remanufactured", 3);
        s.used.put("stim", 1);
        s.health.put("Pipe pistol", 0.4 * Battle.GEAR.get("Pipe pistol").maxHealth);
        plugin.battle.stop(p);
        assertEquals(7, count(p, "9x19 remanufactured"));
        assertEquals(1, count(p, "Stimpak"));
        double cond = -1;
        for (ItemStack it : p.getInventory().getContents()) if (String.valueOf(Items.id(it)).contains("Pipe pistol")) cond = Items.madeCondition(it);
        assertEquals(40, cond, 0.5, "the pistol comes back at 40 %");
    }

    @Test
    void aMagazineHoldsOneLoadAndItsModifiersHit() {   // 05d round 1 (RULED 11-25): the real calibres and their loads
        PlayerMock p = player();
        p.getInventory().addItem(made("Pistols", "9mm pistol", 1, 100));
        p.getInventory().addItem(made("9x19 rounds", "9x19 JHP", 5, 100));
        p.getInventory().addItem(made("9x19 rounds", "9x19 ball", 12, 100));
        p.getInventory().addItem(made("Rifles", "Hunting rifle", 1, 100));
        Battle.State s = fight(p);
        Battle.Weapon w = Battle.GEAR.get("9mm pistol");
        assertEquals("9x19 rounds", w.ammoKind);
        assertEquals(12, Battle.ammoOf(s, w), "the load he carries most of: the ball");
        assertEquals("9x19 rounds|9x19 ball", s.load.get(w.tag));
        assertArrayEquals(new double[]{-0.11, -0.2}, Battle.ammoMods(s, w), 1e-9, "ball: the workbook's FMJ");
        s.ammo.put(w.tag, 0);
        assertTrue(Battle.reload(s, w));
        assertEquals(5, Battle.ammoOf(s, w), "the ball spent: the JHP");
        assertArrayEquals(new double[]{0.6, -0.2}, Battle.ammoMods(s, w), 1e-9);
        Battle.Weapon r = Battle.GEAR.get("Hunting rifle");
        assertEquals(0, Battle.ammoOf(s, r), "no hunting rounds: a 9 mm round does not fit a rifle");
        plugin.battle.stop(p);
        assertEquals(2, Production.design("Pistol rounds", "Pistol reloads").tier, "an old name still finds its load");
        assertEquals(3, Production.design("9x19 rounds", "9x19 remanufactured").foul, "black powder fouls x3");
        assertEquals("Hunting rounds", Production.design("Rifle rounds", "Rifle reloads").category);
    }

    @Test
    void theNumbersAreRealistic() {   // 05d §2 (RULED rounds 2-3): realism, and the better tier of the same kind stronger
        assertTrue(avg("9mm pistol") > avg("Pipe pistol"), "the same 9x19: a factory barrel over a pipe (round 3)");
        assertEquals(1, Battle.GEAR.get("Pipe pistol").magazine, "Tier I: single-shot");
        assertTrue(avg("Sledgehammer") > avg("Mace") && avg("Mace") > avg("Club"), "a maul > a mace > a club");
        assertTrue(avg("Hunting spear") > avg("Steel spear") && avg("Steel spear") > avg("Metal spear") && avg("Metal spear") > avg("Bone spear"),
                "spears by the tier");
        assertTrue(avg("Compound bow") > avg("Laminated bow") && avg("Laminated bow") > avg("Recurve bow") && avg("Recurve bow") > avg("Self bow"));
        assertTrue(avg("7.62 battle rifle") > avg("7.62 assault rifle") && avg("7.62 assault rifle") > avg("5.56 assault carbine"));
        assertTrue(avg(".338 sniper rifle") > avg("7.62 battle rifle") && avg("12.7 anti-materiel rifle") > avg(".338 sniper rifle"));
        assertTrue(avg("Pump shotgun") > avg("Pipe shotgun"));
        assertTrue(avg("Survival knife") > avg("Combat knife"), "good knives kept (round 3)");
        for (String cat : List.of("Knives", "Swords", "Axes", "Hammers", "Spears", "Unarmed weapons", "Techno weapons", "Pistols", "Rifles", "Shotguns",
                "Heavy guns", "Energy weapons", "Bows", "Throwing weapons", "Rocket launchers", "Grenades", "Traps", "Sights", "Muzzle devices", "Magazines", "Under-barrel"))
            for (int t = 1; t <= 4; t++) {
                final int tier = t;
                assertTrue(Production.DESIGNS.values().stream().anyMatch(d -> d.category.equals(cat) && d.tier == tier), cat + " has Tier " + t);
            }
        assertEquals("Swords", Production.design("Knives", "Machete").category, "the machete is a short sword (round 3)");
        assertTrue(Battle.GEAR.get("9mm pistol").models.contains("CZ P-10"), "the models in the description");
    }

    private static int sk(Character c, String skill) { return (int) Math.max(0, Math.floor((Battle.weaponSkill(c, skill) - 50) / 50)); }

    private static double avg(String w) { Battle.Weapon x = Battle.GEAR.get(w); return (x.minDmg + x.maxDmg) / 2.0; }

    @Test
    void strengthAddsByGurpsAndSkillALittle() {   // 05d rounds 3-4: (Str - 5) x the weapon's GURPS factor; +1 a full 50 of skill over 50
        Character c = new Character();
        int[] a = {8, 5, 5, 5, 5, 5, 5}, weak = {2, 5, 5, 5, 5, 5, 5};
        Battle.Weapon club = Battle.GEAR.get("Club"), axe = Battle.GEAR.get("Fire axe");
        assertTrue(club.strDmg > 1.4 && club.strDmg < 1.7, "a crushing swing ~1.5 a point (GURPS)");
        assertTrue(axe.strDmg > 2.1, "a cutting swing ~2.3 a point");
        assertEquals(Math.round(3 * club.strDmg) + sk(c, "Hammers"), Battle.handBonus(c, a, club));
        assertEquals(Math.round(-3 * club.strDmg) + sk(c, "Hammers"), Battle.handBonus(c, weak, club), "a weak arm takes");
        assertEquals(Math.round(3 * Battle.GEAR.get("Javelin").strDmg) + sk(c, "Throwing"), Battle.handBonus(c, a, Battle.GEAR.get("Javelin")));
        assertEquals(Math.round(3 * Battle.GEAR.get("Self bow").strDmg) + sk(c, "Bows"), Battle.handBonus(c, a, Battle.GEAR.get("Self bow")), "a bow by its draw");
        assertEquals(sk(c, "Bows"), Battle.handBonus(c, a, Battle.GEAR.get("Crossbow")), "a crossbow: no Strength");
        assertEquals(0, Battle.handBonus(c, a, Battle.GEAR.get("9mm pistol")), "a gun: none");
        assertTrue(Battle.GEAR.get("Monomolecular knife").pen > Battle.GEAR.get("Titanium knife").pen, "the 2040s' edges penetrate");
    }

    @Test
    void theShockOnlyWhenItPassesIsStrongAndNotEndured() {   // 05d §2e (round 4)
        Battle.Target t = new Battle.Target("raider");   // Endurance 3
        assertTrue(Battle.shock(t, 1, false, false, () -> 1).contains("endures"), "a weak shock endured");
        assertEquals(0, t.apLoss);
        assertTrue(Battle.shock(t, 6, false, false, () -> 100).contains("AP -6"));
        assertEquals(6, t.apLoss);
        assertFalse(t.down, "6 does not knock him down");
        Battle.Target wet = new Battle.Target("raider");
        assertTrue(Battle.shock(wet, 6, false, true, () -> 1).contains("knocked down"), "9 when wet: the muscles lock whoever you are");
        assertTrue(wet.down);
        Battle.Target bot = new Battle.Target("robot");
        assertTrue(Battle.shock(bot, 2, true, false, () -> 1).contains("electronics reset"), "a machine x2, no roll");
        assertEquals("", Battle.shock(bot, 0, true, false, () -> 1), "nothing passed the armour: nothing");
    }

    @Test
    void round5PunchesScaleAndPierce() {   // 05d round 5: Unarmed's skill +1 a 25 over 50; from 150 the DT a fifth (Fallout 2's Force Punch)
        Character c = new Character();
        int[] max = {10, 5, 5, 5, 5, 5, 5};
        Battle.Weapon fists = Battle.GEAR.get("Fists");
        c.points[Rules.skillIndex("Athletics")] += 400;   // Unarmed = Athletics
        double un = Battle.weaponSkill(c, "Unarmed");
        assertTrue(un >= 300, "maxed: " + un);
        assertEquals(Math.round(5 * fists.strDmg) + (int) Math.floor((un - 50) / 25), Battle.handBonus(c, max, fists), "twice the melee rate");
        assertTrue(Battle.piercing(c, fists));
        assertFalse(Battle.piercing(new Character(), fists), "an untrained fist does not pierce");
        assertFalse(Battle.piercing(c, Battle.GEAR.get("Combat knife")), "a knife is no punch");
        double blow = avg("Fists") + Battle.handBonus(c, max, fists);
        int vsPower = Combat.damage(blow, 1, 0, 0, (int) Math.round(12 / 5.0), 0.40, false);   // Power Armor: DT 12, DR 40 %
        assertTrue(vsPower >= 8, "the maxed fist through power armour: " + vsPower);
        Character novice = new Character();
        double weak = avg("Fists") + Battle.handBonus(novice, new int[]{5, 5, 5, 5, 5, 5, 5}, fists);
        assertEquals(0, Combat.damage(weak, 1, 0, 0, 12, 0.40, false), "an average fist does nothing to it");
    }

    @Test
    void round6TheChiMasterAndTheHiddenOccultism() {   // 05d round 6; 03f §3: Occultism the hidden 16th skill
        Character c = new Character();
        int occ = Rules.skillIndex("Occultism");
        assertTrue(occ >= 0 && Rules.HIDDEN.contains("Occultism"));
        int[] a = c.attrs(false);
        assertEquals(2 * a[Rules.I] + 2 * a[Rules.L], c.occultism(), 1e-9, "the base 2 I + 2 L");
        Battle.Weapon fists = Battle.GEAR.get("Fists");
        int[] avg5 = {5, 5, 5, 5, 5, 5, 5};
        int before = Battle.handBonus(c, avg5, fists);
        assertEquals(-1, Battle.chiLevel(c, fists), 1e-9, "no perk: nothing");
        assertEquals(fists.range, Battle.reach(c, fists));
        c.perks.add(new Character.Feat("Chi Master", "", ""));
        c.points[occ] += 300 - c.occultism();
        assertEquals(300, c.occultism(), 1e-9);
        assertEquals(before + 12, Battle.handBonus(c, avg5, fists), "+1 a 25 of Occultism");
        assertEquals(fists.range + 6, Battle.reach(c, fists), "the reach +1 a 50");
        assertEquals(0, Battle.chiDamage(c, Battle.GEAR.get("Combat knife")), "a knife: no chi");
        assertEquals("Chi Punch", Battle.chiName(10));
        assertEquals("Dragon Punch", Battle.chiName(50));
        assertEquals("Heaven-Splitting Fist", Battle.chiName(300));
        // 05d round 7 (Marek 11-27): the Kamehameha from Occultism 200, 8 AP, a beam striking everyone on its line
        assertFalse(Battle.kamehameha(199));
        assertTrue(Battle.kamehameha(200));
        assertEquals(8, Battle.KAME_AP);
        org.bukkit.util.Vector eye = new org.bukkit.util.Vector(0, 1.6, 0), east = new org.bukkit.util.Vector(1, 0, 0);
        List<org.bukkit.util.Vector> mids = List.of(new org.bukkit.util.Vector(2, 1, 0), new org.bukkit.util.Vector(4, 1, 0.4),
                new org.bukkit.util.Vector(3, 1, 1.8), new org.bukkit.util.Vector(-1, 1, 0), new org.bukkit.util.Vector(7, 1, 0));
        assertEquals(List.of(0, 1), Battle.onBeam(eye, east, 5, mids, List.of(0.3, 0.3, 0.3, 0.3, 0.3)),
                "on the line within its reach: yes; beside it, behind the fighter, past its end: no");
        assertEquals("deep", Rules.hiddenWord(c.occultism()));
        assertEquals("a little", Rules.hiddenWord(20));
        Character old = new Character();
        old.points = new double[15];   // a save from before 0.98.0
        old.migrate();
        assertEquals(Rules.SKILLS.size(), old.points.length);
    }

    @Test
    void round5TheGuardTheRecoilTheReloadTheUnaware() {   // 05d round 5
        assertEquals(25, Battle.guardAc(300), "300 = a power armour's AC against blows");
        assertEquals(5, Battle.guardAc(100));
        assertEquals(0, Battle.guardAc(40));
        Battle.Weapon gun = Battle.GEAR.values().stream().filter(w -> !w.melee() && w.minStrength > 1).findFirst().orElseThrow();
        assertEquals(5, Battle.recoilBonus(gun.minStrength + 1, gun));
        assertEquals(15, Battle.recoilBonus(gun.minStrength + 5, gun), "at most +15");
        assertEquals(0, Battle.recoilBonus(gun.minStrength - 1, gun));
        assertEquals(0, Battle.recoilBonus(10, Battle.GEAR.get("Club")), "no recoil in a blow");
        Character c = new Character();
        assertEquals(2, Battle.reloadAp(c, gun, false), "Fallout 2's 2 AP");
        c.points[Rules.skillIndex("Guns")] += 120;
        assertTrue(Battle.weaponSkill(c, "Guns") > 100);
        Battle.Weapon pistol = Battle.GEAR.get("9mm pistol");
        assertEquals(1, Battle.reloadAp(c, pistol, false), "Guns over 100: 1 AP less");
        assertEquals(1, Battle.reloadAp(c, pistol, true), "at least 1");
        Battle.Fight f = new Battle.Fight();
        f.ambush = true;
        Battle.Target t = new Battle.Target("raider");
        assertTrue(Battle.unaware(f, t), "the ambush's first round");
        t.touched = true;
        assertFalse(Battle.unaware(f, t), "hit once: he knows");
        Battle.Target u = new Battle.Target("raider");
        f.round = 2;
        assertFalse(Battle.unaware(f, u), "the second round: everyone knows");
        u.surprised = true;
        assertTrue(Battle.unaware(new Battle.Fight(), u), "caught mid-talk");
    }

    @Test
    void round5APlayerIsShockedToo() {   // 05d round 5: the shock rule on a player
        PlayerMock p = player();
        Character c = plugin.character(p);
        Battle.State s = fight(p);
        plugin.battle.d100 = () -> 1;
        assertTrue(plugin.battle.shockPlayer(p, s, c, 1, false).contains("you endure it"));
        assertEquals(0, s.apLoss);
        plugin.battle.d100 = () -> 100;
        assertTrue(plugin.battle.shockPlayer(p, s, c, 9, false).contains("knocked down"));
        assertEquals(9, s.apLoss);
        assertEquals(2, s.stance(), "down: prone");
        assertEquals("", plugin.battle.shockPlayer(p, s, c, 0, false), "nothing through the Isolation: nothing");
    }

    @Test
    void round5TheTierIVTechnoWeaponsBeatTheRipper() {   // 05d round 5: *"Ripper is quite strong compared to T4 technoweapons"*
        java.util.function.ToDoubleFunction<String> dpap = n -> {
            Battle.Weapon w = Battle.GEAR.get(n);
            double sh = w.shockMax > 0 ? (w.shockMin + w.shockMax) / 2.0 : 0;
            return ((w.minDmg + w.maxDmg) / 2.0 + sh) / w.modes.get(0).ap();
        };
        for (String t4 : List.of("Arc baton", "Vibro-blade", "Plasma saber"))
            assertTrue(dpap.applyAsDouble(t4) > dpap.applyAsDouble("Ripper"), t4);
        Battle.Weapon saber = Battle.GEAR.get("Plasma saber");
        assertEquals("Plasma", saber.damageType, "the lightsaber: a plasma blade");
        assertTrue(saber.pen >= 0.5 && saber.melee());
        // 05d round 7 (Marek 11-27: *"Powerful, but with large energy needs - user may need to reload in middle of fight."*)
        assertEquals(6, saber.cell);
        assertEquals("Power", saber.ammoKind);
        Battle.State st = new Battle.State();
        st.own = true;
        st.reserve.put("Power", 15);
        assertTrue(Battle.reload(st, saber));
        assertEquals(6, Battle.ammoOf(st, saber), "a full charge: 6 blows");
        assertEquals(3, st.reserve.get("Power"), "6 blows drew 12 Power shots, 2 a blow");
        st.ammo.put(saber.tag, 0);
        assertTrue(Battle.reload(st, saber));
        assertEquals(1, Battle.ammoOf(st, saber), "3 shots left: one blow");
        assertFalse(Battle.reload(st, saber), "1 shot left: not a blow");
        // 05d round 7 (RULED 11-27): the mono edge pierces
        assertTrue(Battle.GEAR.get("Monomolecular blade").pierce && Battle.GEAR.get("Monomolecular knife").pierce);
        assertFalse(Battle.GEAR.get("Vibro-blade").pierce);
    }

    @Test
    void blackPowderAndTheApRules() {   // 05d round 7 (RULED 11-27)
        Battle.Weapon musket = Battle.GEAR.get("Pipe musket"), breech = Battle.GEAR.get("Black-powder breech-loader"),
                inline = Battle.GEAR.get("Inline muzzle-loader"), cannon = Battle.GEAR.get("Hand cannon");
        assertEquals(1, Production.design("Rifles", "Pipe musket").tier, "black powder: Tier I-II only");
        assertEquals(2, Production.design("Rifles", "Black-powder breech-loader").tier);
        assertEquals(2, Production.design("Rifles", "Inline muzzle-loader").tier);
        assertEquals("flint", musket.lock);
        assertEquals("match", cannon.lock);
        assertEquals("cap", breech.lock);
        assertTrue(musket.powder && breech.powder && inline.powder);
        assertEquals("Black-powder charges", musket.ammoKind);
        assertEquals("Black-powder cartridges", breech.ammoKind);
        assertTrue(musket.minDmg > Battle.GEAR.get("Pipe rifle").minDmg, "per shot more than a pipe rifle");
        // the reload's AP: its own, the skill -1/4 at 100 to -1/2 at 200 (at least 1), +50 % fouled
        Character c = new Character();
        int g = Rules.skillIndex("Guns");
        c.points[g] += 50 - c.skill("Guns");
        assertEquals(23, Battle.reloadAp(c, musket, false));
        assertEquals(35, Battle.reloadAp(c, musket, false, true), "fouled: +50 %");
        assertEquals(2, Battle.reloadAp(c, breech, false), "a single shot: Fallout 2's 2");
        c.points[g] += 100 - c.skill("Guns");
        assertEquals(18, Battle.reloadAp(c, musket, false), "Guns 100: -1/4");
        assertEquals(1, Battle.reloadAp(c, breech, false), "Guns 100: at least -1");
        c.points[g] += 200 - c.skill("Guns");
        assertEquals(12, Battle.reloadAp(c, musket, false), "Guns 200: -1/2");
        // the misfires
        assertTrue(Battle.misfire("flint", false, 0.10));
        assertFalse(Battle.misfire("flint", false, 0.20));
        assertTrue(Battle.misfire("flint", true, 0.20), "wet: twice as often");
        assertTrue(Battle.misfire("cap", false, 0.005));
        assertFalse(Battle.misfire("cap", false, 0.01));
        assertFalse(Battle.misfire("match", false, 0.0));
        assertFalse(Battle.misfire("", false, 0.0));
        // unused AP carried, at most the max; long actions in parts
        assertEquals(10, Battle.turnAp(8, 2, 0, false));
        assertEquals(16, Battle.turnAp(8, 12, 0, false), "at most the max carried");
        assertEquals(13, Battle.turnAp(8, 7, 2, false));
        assertEquals(0, Battle.turnAp(8, 7, 0, true));
        assertArrayEquals(new int[]{8, 8}, Battle.partPay(23, 0, 8));
        assertArrayEquals(new int[]{15, 23}, Battle.partPay(23, 8, 16), "finished in the second turn");
        assertArrayEquals(new int[]{0, 23}, Battle.partPay(23, 23, 5));
        // fouling after ten shots
        Battle.State st = new Battle.State();
        st.fired.put(musket.tag, 9);
        assertFalse(Battle.fouled(st, musket));
        st.fired.put(musket.tag, 10);
        assertTrue(Battle.fouled(st, musket));
        assertFalse(Battle.fouled(st, Battle.GEAR.get("Pipe rifle")), "smokeless: never")
        ;
    }

    @Test
    void throwsBlowsAndDarts() {   // 05d round 7 (RULED 11-27, §7b)
        Battle.Weapon sling = Battle.GEAR.get("Sling"), atlatl = Battle.GEAR.get("Spear-thrower"), bolas = Battle.GEAR.get("Bolas"),
                net = Battle.GEAR.get("Net"), flail = Battle.GEAR.get("War flail"), scythe = Battle.GEAR.get("War scythe"),
                blowgun = Battle.GEAR.get("Blowgun"), dartGun = Battle.GEAR.get("Dart gun");
        assertEquals("Sling stones", sling.ammoKind);
        int[] weak = {5, 5, 5, 5, 5, 5, 5}, strong = {10, 5, 5, 5, 5, 5, 5};
        assertTrue(Battle.handBonus(null, strong, sling) > Battle.handBonus(null, weak, sling), "the sling's swing: Strength counts");
        assertTrue(atlatl.thrown);
        assertEquals("Javelin", atlatl.throwsName);
        assertTrue(atlatl.maxDmg > Battle.GEAR.get("Javelin").maxDmg, "the spear-thrower throws harder than the hand");
        assertEquals("legs", bolas.entangle);
        assertEquals("body", net.entangle);
        assertEquals(0, net.maxDmg, "the net does no harm");
        assertTrue(flail.noGuard && !Battle.GEAR.get("Mace").noGuard);
        assertEquals(3, scythe.range, "a pole's reach");
        assertEquals("Darts", blowgun.ammoKind);
        assertEquals("Darts", dartGun.ammoKind);
        assertEquals("Guns", dartGun.skill);
        assertEquals(3, Production.design("Rifles", "Dart gun").tier, "today's factory: Tier III");
        assertEquals(1, Production.design("Throwing weapons", "Sling").tier);
        assertTrue(Battle.tearsFree(5, 50));
        assertFalse(Battle.tearsFree(5, 51));
        assertEquals(30, Battle.SEDATIVE_ROUNDS, "minutes, not a round: a dart ends a hunt, not a fight");
    }

    @Test
    void accessoriesFitAndWorkInTheFight() {   // 05d §5 (RULED round 3)
        PlayerMock p = player();
        Character c = plugin.character(p);
        c.points[Rules.skillIndex("Crafting")] += 30;
        p.getInventory().setItemInMainHand(made("Rifles", "Hunting rifle", 1, 100));
        p.getInventory().setItem(9, made("Sights", "Telescopic sight", 1, 100));
        p.getInventory().setItem(10, made("Magazines", "Extended magazine", 1, 100));
        p.getInventory().setItem(11, made("Magazines", "Speed loader", 1, 100));
        p.getInventory().setItem(12, made("Under-barrel", "Bayonet", 1, 100));
        assertTrue(Accessories.command(p, c, new String[]{"fit", "9"}).startsWith("Fitted the Telescopic sight"));
        assertTrue(Accessories.command(p, c, new String[]{"fit", "11"}).startsWith("!"), "a speed loader fits revolvers only");
        assertTrue(Accessories.command(p, c, new String[]{"fit", "10"}).startsWith("Fitted the Extended magazine"));
        assertTrue(Accessories.command(p, c, new String[]{"fit", "12"}).startsWith("Fitted the Bayonet"));
        Map<String, String> fit = Accessories.fitted(p.getInventory().getItemInMainHand());
        assertEquals("Telescopic sight", fit.get("Sights"));
        assertEquals("Scope Range", Accessories.perk("", fit));
        assertTrue(Accessories.command(p, c, new String[]{"fit", "off", "Under-barrel"}).startsWith("Took the Bayonet off"));
        assertFalse(Accessories.fitted(p.getInventory().getItemInMainHand()).containsKey("Under-barrel"));
        p.getInventory().addItem(made("Hunting rounds", "hunting soft point", 20, 100));
        Battle.State s = fight(p);
        Battle.Weapon w = Battle.GEAR.get("Hunting rifle");
        assertEquals(10, Battle.cap(s, w), "the extended magazine: x2");
        assertEquals(10, Battle.ammoOf(s, w));
        plugin.battle.stop(p);
        boolean kept = false;
        for (ItemStack it : p.getInventory().getContents()) if (it != null && "Telescopic sight".equals(Accessories.fitted(it).get("Sights"))) kept = true;
        assertTrue(kept, "its accessories stay on it after the fight");
        Character weakling = new Character();
        assertTrue(Accessories.command(p, weakling, new String[]{"fit", "9"}).startsWith("!"), "no accessory there any more, or no Crafting");
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
