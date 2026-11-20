package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RulesTest {
    @Test
    void sampleCharacter() {
        Character c = Character.sample("x");
        int[] perm = c.attrs(true), now = c.attrs(false);
        assertEquals(7, perm[Rules.P]);          // 6 + the implant
        assertEquals(6, now[Rules.P]);           // − the hangover
        assertEquals(4, now[Rules.C]);
        assertEquals(5 + 2 * 6 + 15 + 5, c.hpMax()); // S + 2E + human 15 (data/species.json) + Medical Marvel
        assertEquals(5 + 7 / 2 + 2, c.apMax());  // + Brittle Bones
        assertEquals(-6, Rules.deathFloor(now));
        assertEquals(2 * 7 + 2 * 5 + 2 * 6, c.skill("Athletics"));
        assertEquals(2 * 7 + 6 + 30, c.skill("Guns"));
    }

    @Test
    void theXpTableAndTheAwards() {
        // 06b §5.5 (RULED 11-20): a level L -> L+1 costs 250 x L x sqrt(L)
        assertEquals(0, Rules.xpForLevel(1));
        assertEquals(250, Rules.xpForLevel(2));
        assertEquals(957, Rules.xpForLevel(3));
        assertEquals(4256, Rules.xpForLevel(5));
        assertEquals(27762, Rules.xpForLevel(10));
        assertEquals(79995, Rules.xpForLevel(15));
        assertEquals(472575, Rules.xpForLevel(30));
        for (int l = 1; l < 40; l++) assertEquals(250 * l * Math.sqrt(l), Rules.xpForLevel(l + 1) - Rules.xpForLevel(l), 1.0);
        assertEquals(1, Rules.levelFor(0));
        assertEquals(1, Rules.levelFor(249));
        assertEquals(2, Rules.levelFor(250));
        assertEquals(9, Rules.levelFor(27761));
        assertEquals(10, Rules.levelFor(27762));
        assertEquals(3.0, Rules.levelExact(957), 1e-9);
        assertEquals(3.5, Rules.levelExact((957 + 2256) / 2.0), 1e-9);
        // kills: 3.1 x HP^1.09 (a raider of 30 HP 126)
        assertEquals(126, Rules.killXp(30));
        assertEquals(38, Rules.killXp(10));
        assertEquals(0, Rules.killXp(0));
        // the split: Mark's 2023 rule on his four points for six people, the leader included
        assertEquals(1.0 / 6, Rules.xpShare(6, 0), 1e-9);
        assertEquals(1.0 / 4, Rules.xpShare(6, 100), 1e-9);
        assertEquals(1.0 / 2, Rules.xpShare(6, 200), 1e-9);
        assertEquals(1.0, Rules.xpShare(6, 250), 1e-9);
        assertEquals(1.0, Rules.xpShare(6, 300), 1e-9);
        assertEquals(1.0, Rules.xpShare(1, 0), 1e-9);
        // checks: the difficulty, at least 10, Doctor doubled
        assertEquals(40, Rules.checkXp(-40, false));
        assertEquals(10, Rules.checkXp(0, false));
        assertEquals(20, Rules.checkXp(5, true));
        assertEquals(80, Rules.checkXp(40, true));
        // discovery: 100 x the areas found before (the 1st 0, the 10th 900); research 100 x tier x rarity
        assertEquals(0, Rules.discoveryXp(0));
        assertEquals(900, Rules.discoveryXp(9));
        assertEquals(600, Rules.researchXp(3, 2));
        // work and teaching (06 §2.1, §2.5)
        assertEquals(0, Rules.workXp(false));
        assertEquals(1, Rules.workXp(true));
        assertEquals(1, Rules.lessonXp(0), 1e-9);
        assertEquals(3, Rules.lessonXp(50), 1e-9);
        assertEquals(5, Rules.lessonXp(100), 1e-9);
        assertEquals(11, Rules.classSize(100));
    }

    @Test
    void xpRaisesTheLevelAndGivesItsSkillPoints() {
        Character c = Character.sample("x");
        assertEquals(1, c.level);
        assertEquals(120, c.xp);
        int pts = c.unspentSkillPoints, rate = c.skillRate();
        assertEquals("", c.gainXp(100));                     // 220: still level 1
        assertEquals(1, c.level);
        String up = c.gainXp(800);                           // 1 020: past 250 and 957
        assertEquals(3, c.level);
        assertEquals(pts + 2 * rate, c.unspentSkillPoints);
        assertTrue(up.contains("Level 2") && up.contains("Level 3"), up);
        assertTrue(up.contains("perk"), up);                 // a human: a perk every 3 levels
        assertEquals("", c.gainXp(0));
        c.dead = true;
        assertEquals("", c.gainXp(5000));
        assertEquals(3, c.level);
    }

    @Test
    void ourPerksAndTheirGates() {
        assertEquals(111, Data.PERKS.size());                                        // 02c §2-§3
        assertTrue(Data.PERKS.get("Cook").parked());
        assertTrue(Data.PERKS.get("Nemesis").bound());
        Character c = Character.sample("x");
        double[] v = c.skills(true);
        Data.Perk tough = Data.PERKS.get("Toughness"), weapon = Data.PERKS.get("More Criticals"), slayer = Data.PERKS.get("Slayer");
        assertEquals(v[Rules.skillIndex("Athletics")] >= 75 || v[Rules.skillIndex("Survival")] >= 75, Rules.gateMet(v, tough));
        v[Rules.skillIndex("Guns")] = 100;                                            // any weapon skill: Guns counts
        assertTrue(Rules.gateMet(v, weapon));
        assertFalse(Rules.gateMet(v, slayer));                                        // Unarmed or a melee family 150
        v[Rules.skillIndex("Athletics")] = 150;                                       // Unarmed = Athletics
        assertTrue(Rules.gateMet(v, slayer));
        assertEquals(1, Data.PERKS.get("Gain Strength").mods()[Rules.S]);
        // taking them
        assertTrue(c.takePerk(tough, "").startsWith("!"));                           // level 1: none due
        c.level = 6;                                                                  // a human: 2 due
        assertEquals(2, c.perksDue());
        c.points[Rules.skillIndex("Athletics")] += 80;
        int ap = c.apMax();
        assertTrue(c.takePerk(Data.PERKS.get("Action Boy"), "").contains("taken"));
        assertEquals(ap + 2, c.apMax());
        Data.Perk tag = Data.PERKS.get("Tag!");
        assertTrue(c.takePerk(tag, "").startsWith("!"));                             // name the skill
        assertTrue(c.takePerk(tag, "Guns").startsWith("!"));                         // tagged already
        assertTrue(c.takePerk(tag, "Pilot").contains("taken"));
        assertTrue(c.tags.contains("Pilot"));
        assertEquals(0, c.perksDue());
        assertTrue(c.takePerk(Data.PERKS.get("Thief"), "").startsWith("!"));         // none due
        c.level = 9;
        assertTrue(c.takePerk(Data.PERKS.get("Action Boy"), "").startsWith("!"));    // taken already
        assertTrue(c.takePerk(Data.PERKS.get("Cook"), "").startsWith("!"));          // parked
        long xp = c.xp;
        assertTrue(c.takePerk(Data.PERKS.get("Here and Now"), "").contains("taken")); // the XP to the next level
        assertTrue(c.xp >= Rules.xpForLevel(10) && c.xp > xp);
    }

    @Test
    void theMeleeFamiliesAndTheWeaponSkills() {   // Marek 11-20, round 9
        double[] v = new double[Rules.SKILLS.size()];
        Data.Perk slayer = Data.PERKS.get("Slayer");                                   // Unarmed or a melee family 150
        v[Rules.skillIndex("Engineering")] = 150;
        v[Rules.skillIndex("Athletics")] = 140;                                        // Techno Weapons 160, Unarmed 140, Hammers 115
        assertTrue(Rules.gateMet(v, slayer));                                          // "technoweapons are technically also melee"
        v[Rules.skillIndex("Engineering")] = 100;                                      // Techno Weapons 135
        assertFalse(Rules.gateMet(v, slayer));
        assertTrue(Rules.MELEE.contains("Unarmed"));                                   // "also unarmed"
        double[] w = new double[Rules.SKILLS.size()];
        Data.Perk crits = Data.PERKS.get("More Criticals");                            // any weapon skill 100
        w[Rules.skillIndex("Chemistry")] = 100;                                        // "chemistry is also explosives"
        assertTrue(Rules.gateMet(w, crits));
        w[Rules.skillIndex("Chemistry")] = 0;
        w[Rules.skillIndex("Athletics")] = 100;                                        // "athletics is unarmed"
        assertTrue(Rules.gateMet(w, crits));
        assertEquals(15, Rules.weaponSkills().size());                                 // Guns + the 14 combinations
        // the bound perks the battle runs
        Character c = Character.sample("x");
        c.level = 9;                                                                   // a human: 3 due
        c.points[Rules.skillIndex("Guns")] += 150;
        Battle.Weapon gun = Battle.SMG;
        Battle.Mode single = gun.modes.get(0);
        Battle.Target t = new Battle.Target("Nájezdník 1");
        int ap = Battle.apOf(c, gun, single);
        assertEquals(1, Battle.perkDamage(c, gun, t), 1e-9);
        assertTrue(c.takePerk(Data.PERKS.get("Weapon Master"), "Guns").contains("taken"));
        assertEquals(Math.max(1, ap - 1), Battle.apOf(c, gun, single));
        assertEquals(Battle.KNIFE.modes.get(0).ap(), Battle.apOf(c, Battle.KNIFE, Battle.KNIFE.modes.get(0)));   // another family
        assertEquals(1.25, Battle.perkDamage(c, gun, t), 1e-9);
        assertTrue(c.takePerk(Data.PERKS.get("Nemesis"), "Raiders").contains("taken"));
        assertEquals(1.5, Battle.perkDamage(c, gun, t), 1e-9);                         // each +25 %, added
        t.kind = "Ghouls";
        assertEquals(1.25, Battle.perkDamage(c, gun, t), 1e-9);
        assertTrue(c.takePerk(Data.PERKS.get("Signature Weapon"), gun.name).contains("taken"));
        assertEquals(3, Battle.perkDegree(c, gun, 2));                                 // one crit degree better
        assertEquals(6, Battle.perkDegree(c, gun, 6));
        assertEquals(2, Battle.perkDegree(c, Battle.KNIFE, 2));
    }

    @Test
    void workbookData() {
        assertEquals(27, Data.TEMPLATES.size());   // part 3: the workbook's seven + the eight basic gene templates (03c §6, Golden Blood since round 8) + the ten premium + the Fallen Angel and her graced form (03f §2)
        assertEquals(39, Data.TEMPLATES.get("Ghoul").attributePoints());
        assertEquals(0, Data.EFFECTS.get("Paralyzed by Pain").mods()[Rules.A]);   // Marek 11-19: AP −99 instead of Agility −99
        assertEquals(-99, Data.EFFECTS.get("Paralyzed by Pain").other().get("ap"), 1e-9);
        Character c = Character.sample("x");
        c.survival.put(Rules.Stat.PAIN, 80.0);          // Paralyzed by Pain: no AP left
        assertEquals(0, c.apMax());
        assertEquals(7, c.attr(Rules.A, true));        // the permanent value is untouched
    }

    @Test
    void survivalStagesTakeHp() {
        // the workbook's HP Max and HP Current (04, RULED 11-15): poison's an hour, radiation's once and into the healing rate
        Character c = Character.sample("x");
        int base = c.hpMaxBase(), rate = c.healingRate();
        assertEquals(base, c.hpMax());
        c.survival.put(Rules.Stat.POISON, c.max(Rules.Stat.POISON) * 0.5);           // Critical Poisoning: HP Max −3, HP −2 an hour
        assertTrue(c.stages().contains("Critical Poisoning"), c.stages().toString());
        assertEquals(base - 3, c.hpMax());
        assertEquals(base, c.max(Rules.Stat.BLOOD) / 4, 1e-9);                      // Blood's maximum reads the base (no loop)
        c.hp = base;
        String news = c.stageHour(c.stages());
        assertEquals(base - 3 - 2, c.hp);                                           // down to the new maximum, then the hour's 2
        assertTrue(news.startsWith("Critical Poisoning: HP −2"), news);
        c.stageHour(c.stages());
        assertEquals(base - 7, c.hp);                                               // every hour
        c.survival.put(Rules.Stat.POISON, 0.0);
        assertEquals(base, c.hpMax());
        // radiation: HP Max moved to the healing rate, HP Current once when the stage begins
        c.hp = base;
        c.survival.put(Rules.Stat.RADS, 500.0);                                     // Critical Radiation Poisoning
        assertEquals(base, c.hpMax());
        assertEquals(-5, c.stageOther("healing_rate"), 1e-9);
        assertTrue(c.healingRate() <= Math.max(0, rate - 5), c.healingRate() + " vs " + rate);   // E −1 lowers it a little more
        List<String> none = List.of();
        assertTrue(c.stageHour(none).contains("HP −5"));                            // begun: −5 (Advanced passed has none)
        assertNull(c.stageHour(c.stages()));                                        // staying: nothing more
        c.hp = base;
        c.survival.put(Rules.Stat.RADS, 700.0);                                     // none → Deadly in one hour: Critical's −5 + Deadly's −15
        assertTrue(c.stageHour(none).contains("HP −20"), c.hp + "");
        assertEquals(base - 20, c.hp);
        c.survival.put(Rules.Stat.RADS, 500.0);                                     // RadAway: down to Critical costs nothing
        assertNull(c.stageHour(List.of("Deadly Radiation Poisoning")));
        c.survival.put(Rules.Stat.RADS, 700.0);
        assertEquals(0, c.healingRate());                                           // Deadly: −10 stops at 0, rest heals nothing
        c.survival.put(Rules.Stat.RADS, 1200.0);                                    // Fatal: the only stage that goes below 0 (11-19)
        assertEquals(-10, c.stageOther("healing_rate"), 1e-9);
        int neg = c.healingRate();
        assertTrue(neg < 0, neg + "");
        c.hp = 40;
        c.sleeping = false;
        for (int h = 0; h < 7; h++) Hud.heal(c);
        assertEquals(40, c.hp);                                                     // nothing for 7 hours
        Hud.heal(c);
        assertEquals(40 + neg, c.hp);                                               // the 8th hour: the rate, awake or asleep
        assertTrue(c.news.get(c.news.size() - 1).startsWith("Fatal Radiation Poisoning: HP " + neg), c.news.toString());
        c.sleeping = true;
        for (int h = 0; h < 8; h++) Hud.heal(c);
        assertEquals(40 + 2 * neg, c.hp);                                           // rest does not stop it
        // out of it alive (RadAway): a permanent scar, Endurance −1 and HP Max −5 (11-19, EXAMPLE numbers)
        int e0 = c.attr(Rules.E, true), max0 = c.hpMaxBase();
        List<String> fatal = c.stages();
        c.survival.put(Rules.Stat.RADS, 900.0);
        String out = c.stageHour(fatal);
        assertTrue(out != null && out.startsWith("Survived Fatal Radiation"), out);
        assertEquals(e0 - 1, c.attr(Rules.E, true));
        assertTrue(c.hpMaxBase() <= max0 - 5, c.hpMaxBase() + " vs " + max0);
        assertNull(c.stageHour(c.stages()));                                       // once, not every hour
        // out of Deadly Hypothermia alive: the workbook's Frostbite, HP Max −3 for good (0.31.0, "Similar to frostbite", 11-19)
        c.survival.put(Rules.Stat.RADS, 0.0);
        c.hp = 20;
        double coldMax = c.max(Rules.Stat.HYPOTHERMIA);
        c.survival.put(Rules.Stat.HYPOTHERMIA, coldMax * 0.9);
        List<String> deadly = c.stages();
        assertTrue(deadly.contains("Deadly Hypothermia"), deadly.toString());
        int max1 = c.hpMaxBase();
        c.survival.put(Rules.Stat.HYPOTHERMIA, coldMax * 0.5);
        String bite = c.stageHour(deadly);
        assertTrue(bite != null && bite.startsWith("Frostbite: HP Max −3"), bite);
        assertEquals(max1 - 3, c.hpMaxBase());
        assertTrue(c.effects.stream().anyMatch(e -> e.name().equals("Frostbite") && e.permanent()));
        c.survival.put(Rules.Stat.HYPOTHERMIA, 0.0);
        // a drain past −Endurance starts clinical death
        c.hp = 1;
        c.drain(50);
        assertTrue(c.inArrest());
    }

    @Test
    void woundsAndNotes() {
        assertEquals(30, Rules.painOfHit(10, false));      // the note on Pain: 3 a HP lost
        assertEquals(55, Rules.painOfHit(10, true));       // + 25 with a crit
        assertEquals(2, Rules.bleedingPerRound(0, false)); // Andor: a weak wound 2 %, every further cut +1 %, a strong one 5 %
        assertEquals(3, Rules.bleedingPerRound(2, false));
        assertEquals(5, Rules.bleedingPerRound(2, true));
        assertEquals(0, Rules.hourly(Rules.Stat.PAIN, 0, false, 0));   // pain falls only asleep (or with anaesthetics)
        assertEquals(-3, Rules.hourly(Rules.Stat.PAIN, 0, true, 0));
        assertTrue(Data.note("Character", "Sneak").contains("stealing"));   // Marek 11-14: stealing is in Sneak
        assertTrue(Data.note("Character", "Pain").startsWith("Gained 3x"));
        assertTrue(Data.note("Battle", "Stance").contains("Prone"));
    }

    @Test
    void painCheckMakesNoRoll() {
        Character c = Character.sample("x");                 // Survival = P 6 + A 7 + E 6 = 19 (no points)
        c.survival.put(Rules.Stat.PAIN, 15.0);
        assertTrue(c.ignoresPain());                          // Marek 11-14: Survival ≥ Pain passes, no roll
        c.survival.put(Rules.Stat.PAIN, 20.0);
        assertFalse(c.ignoresPain());
        assertTrue(Rules.check(75, 75));
        assertFalse(Rules.check(74, 75));
    }

    @Test
    void stancesFromTacticsAndAimedParts() {
        assertEquals(0.5, Rules.stepAp(0, 0, false));        // Marek's table: standing 1 AP for 2 blocks
        assertEquals(1.0, Rules.stepAp(0, 0, true));         // Brittle Bones doubles
        assertEquals(1.0, Rules.stepAp(1, 0, false));        // crouched 200 %
        assertEquals(1.5, Rules.stepAp(2, 0, false));        // prone 300 %
        assertEquals(2.0, Rules.stepAp(0, 1, false));        // a broken leg 400 %
        assertEquals(4.0, Rules.stepAp(2, 2, false));        // both 800 %
        assertEquals(2, Rules.stanceChangeAp(0, 1));
        assertEquals(2, Rules.stanceChangeAp(1, 2));
        assertEquals(4, Rules.stanceChangeAp(2, 0));
        assertEquals(Rules.Part.HEAD, Rules.part(0.95, 0, true));
        assertEquals(Rules.Part.EYES, Rules.part(0.87, 0, true));
        assertEquals(Rules.Part.HEAD, Rules.part(0.87, 0, false));   // no eyes from behind
        assertEquals(Rules.Part.TORSO, Rules.part(0.6, 0.05, true));
        assertEquals(Rules.Part.GROIN, Rules.part(0.4, 0.02, true));
        assertEquals(Rules.Part.RIGHT_ARM, Rules.part(0.6, 0.2, true));
        assertEquals(Rules.Part.LEFT_ARM, Rules.part(0.6, -0.2, true));
        assertEquals(Rules.Part.LEFT_LEG, Rules.part(0.2, -0.05, true));
        assertNull(Rules.part(0.6, 0.3, true));
        assertNull(Rules.part(1.1, 0, true));
    }

    @Test
    void columnsLineUp() {
        assertEquals(6, Txt.width("a", false));
        assertEquals(7, Txt.width("a", true));
        assertEquals(4, Txt.width(" ", false));
        for (int px = 12; px < 80; px++) assertEquals(px, Txt.width(Txt.pad(px)));
        var t = new Txt.Table(300).minWidths(80, 40).title("Vitals", "a subtitle long enough to wrap onto a second line of the table here")
                .row(Txt.c("HP", null), Txt.r("37 / 37", null), Txt.c("S 5 + 2 × E 6 + template 15 + Medical Marvel 5 · dying from 0 to −6", null))
                .note("Paralyzed on 0−. A note that is long enough to wrap onto the next line as well.");
        var lines = t.render();
        assertTrue(lines.size() >= 5);
        for (var l : lines) assertEquals(300, Txt.width(l));   // every line one width: the dialog centres them as one block
    }

    @Test
    void stagesAndPrices() {
        assertNull(Rules.stage(Rules.Stat.WATER, 90, 100));
        assertEquals("Minor Dehydration", Rules.stage(Rules.Stat.WATER, 70, 100));
        assertEquals("Deadly Starvation", Rules.stage(Rules.Stat.FOOD, 10, 100));
        assertEquals("Paralyzed by Pain", Rules.stage(Rules.Stat.PAIN, 80, 100));
        assertEquals("Minor Radiation Poisoning", Rules.stage(Rules.Stat.RADS, 160, 1000));
        assertEquals(1, Rules.pricePerPercent(99));
        assertEquals(2, Rules.pricePerPercent(100));
        assertEquals(4, Rules.pricePerPercent(200));
    }

    @Test
    void perkGatesReadPermanentValues() {
        Character c = Character.sample("x");
        Character.Feat medic = Character.SAMPLE_PERKS.stream().filter(f -> f.name().equals("Medic!")).findFirst().orElseThrow();
        assertEquals(c.skills(true)[Rules.skillIndex("Medicine")] >= 50, c.meets(medic));
    }
}
