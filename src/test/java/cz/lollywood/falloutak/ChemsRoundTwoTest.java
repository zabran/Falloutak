package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `04i` round 2 and `04j` round 3 (RULED 11-27, Marek, verbatim: *"Stimpak, Hypo, Trauma pack - thank you, this works a lot better."* ·
 * *"Should the drugs affect the survival bars as I listed in §6b? - yes."* · *"When two sedating drugs are taken together - great idea.
 * Yes, lets do that."* · *"More home-made options - yes, please add them."* · *"Tier IV Steady - yes."* · *"Are the names and numbers in
 * the table right? - yes."* · *"Are the stimulant stages above right? - yes, all is fine."* · pervitin's two recipes): the Fallout standard
 * rationalized (held HP, the Trauma pack), the survival bars on every chem, the stimulants a stage or two better, the breathing stage and
 * naloxone, the home-made, the Tier IV drug for every survival issue.
 */
class ChemsRoundTwoTest extends MockGame {

    /** No addiction roll succeeds, every d10 is 1. */
    private static Random calm() { return new Random() { @Override public double nextDouble() { return 0.99; } @Override public int nextInt(int b) { return 0; } }; }

    private static Data.Chem chem(String n) { Data.Chem c = Data.CHEMS.get(n); assertNotNull(c, n); return c; }

    private static boolean has(Character c, String stage) { return c.stages().contains(stage); }

    @Test
    void aStimulantShowsTheSleepStageOneOrTwoBetterTheStrongestCountsTheDebtStays() {
        assertEquals("Critical Sleep Deprivation", Rules.better("Deadly Sleep Deprivation", 1));
        assertEquals("Advanced Sleep Deprivation", Rules.better("Deadly Sleep Deprivation", 2));
        assertNull(Rules.better("Minor Sleep Deprivation", 1), "Minor shown one better = none");
        assertEquals("Minor Sleep Deprivation", Rules.better("Minor Sleep Deprivation", 0));
        Character c = AirTest.human();
        double max = c.max(Rules.Stat.SLEEP);
        c.survival.put(Rules.Stat.SLEEP, 0.1 * max);
        assertTrue(has(c, "Deadly Sleep Deprivation"));
        c.dose(chem("Caffeine"), calm());
        assertTrue(has(c, "Critical Sleep Deprivation"), c.stages().toString());
        c.dose(chem("Pervitin"), calm());
        assertTrue(has(c, "Advanced Sleep Deprivation"), "pervitin two better; the caffeine does not add: " + c.stages());
        assertEquals(0.1 * max, c.survival.get(Rules.Stat.SLEEP), 1e-9, "the debt itself stays");
        Character m = AirTest.human();
        m.survival.put(Rules.Stat.SLEEP, 0.7 * max);
        assertTrue(has(m, "Minor Sleep Deprivation"));
        m.dose(chem("Jet"), calm());
        assertFalse(m.stages().stream().anyMatch(s -> s.endsWith("Sleep Deprivation")), "Jet one better: Minor gone");
        Character d = AirTest.human();
        d.survival.put(Rules.Stat.SLEEP, 0.1 * max);
        d.addictions.add("Caffeine");
        String r = d.dose(chem("Caffeine"), calm());
        assertTrue(r.contains("wakes nothing"), r);
        assertTrue(has(d, "Deadly Sleep Deprivation"), "a daily user's coffee wakes nothing");
    }

    @Test
    void theChemsMoveTheSurvivalBarsMentatsBuffoutRadAwayAlcoholTheStimulantsHeatAtWork() {
        Character c = AirTest.human();
        double sleep = c.hourly(Rules.Stat.SLEEP), food = c.hourly(Rules.Stat.FOOD), water = c.hourly(Rules.Stat.WATER);
        c.dose(chem("Mentats"), calm());
        assertEquals(1.5 * sleep, c.hourly(Rules.Stat.SLEEP), 1e-9, "Mentats: the debt ×1.5");
        c.dose(chem("Buffout"), calm());
        assertEquals(1.5 * food, c.hourly(Rules.Stat.FOOD), 1e-9, "Buffout: food +50 %");
        assertEquals(0, c.hourly(Rules.Stat.HYPERTHERMIA), 1e-9, "the heat only at work");
        c.working = true;
        assertEquals(1, c.hourly(Rules.Stat.HYPERTHERMIA), 1e-9, "Buffout at work: heat +1 an hour");
        Character r = AirTest.human();
        r.dose(chem("RadAway"), calm());
        assertEquals(1.5 * water, r.hourly(Rules.Stat.WATER), 1e-9, "RadAway's flush: water +50 %");
        int e = r.attr(Rules.E, false);
        for (int h = 0; h < 4; h++) Hud.hour(r);
        assertEquals(e - 1, r.attr(Rules.E, false), "RadAway: Endurance −1 for a day after");
        Character a = AirTest.human();
        a.dose(chem("Alcohol"), calm());
        assertEquals(1.25 * water, a.hourly(Rules.Stat.WATER), 1e-9, "alcohol: a diuretic");
        Hud.hour(a);
        assertEquals(1.25 * water, a.hourly(Rules.Stat.WATER), 1e-9, "still thirsty the hours after the drink");
        Character x = AirTest.human();
        double f0 = x.survival.get(Rules.Stat.FOOD);
        String s = x.dose(chem("Rad-X"), calm());
        assertEquals(f0 - 10, x.survival.get(Rules.Stat.FOOD), 1e-9, s);
    }

    @Test
    void psychoKeepsASlightDrNoPainNoKnockdown() {
        Character c = AirTest.human();
        c.dose(chem("Psycho"), calm());
        assertEquals(10, c.other("dr"), 1e-9);
        assertEquals(-60, c.other("pain_felt"), 1e-9);
        assertEquals(1, c.other("no_knockdown"), 1e-9);
        assertNull(chem("Psycho").crash().other().get("dr"), "the crash keeps no DR");
    }

    @Test
    void onPsychoAHitThatWouldKnockDownOrStunDoesNot() {
        Arena.use = world;
        Travel.use(WorldMapTest.fixture());
        Travel.here("Roadside");
        try { psychoHit(); } finally { Arena.use = null; Arena.current = null; Travel.map = null; Travel.file = null; Travel.scene.clear(); }
    }

    private void psychoHit() {
        PlayerMock a = player();
        Travel.scene.add(new Travel.Met(new WorldMap.Encounter(Travel.map.kinds.get("raiders:pond"), "fight", 1), true));
        Travel.sceneTerrain = "field";
        assertFalse(Travel.battleMap(plugin, a, Arena.Mode.AMBUSH).startsWith("!"));
        Battle.State s = plugin.battle.state(a);
        Character c = plugin.character(a);
        Combat.CritEffects fx = new Combat.CritEffects(false, true, false, false, false, false, true, false, false);
        c.dose(chem("Psycho"), calm());
        StringBuilder log = new StringBuilder();
        plugin.battle.hitPlayer(a, s, c, a.getLocation(), 1, fx, true, 3, log);
        assertTrue(log.toString().contains("Psycho: you stay on your feet"), log.toString());
        assertEquals(0, s.lostTurns, "the stun does not take");
        assertTrue(s.legs.stream().noneMatch(l -> l.stance() == 2), "not prone");
        c.effects.removeIf(e -> e.slot().equals("chem:Psycho"));
        StringBuilder log2 = new StringBuilder();
        plugin.battle.hitPlayer(a, s, c, a.getLocation(), 1, fx, true, 3, log2);
        assertEquals(1, s.lostTurns, log2.toString());
        assertTrue(s.legs.stream().anyMatch(l -> l.stance() == 2), "without it: prone");
    }

    @Test
    void anOpioidWithASecondDepressantStopsTheBreathingUntilNaloxoneOrAMedic() {
        Character c = AirTest.human();
        c.dose(chem("Morphine"), calm());
        assertFalse(c.breathingStage(), "one opioid alone keeps the breathing");
        c.dose(chem("Ketamine"), calm());
        assertFalse(c.breathingStage(), "ketamine keeps the breathing");
        String r = c.dose(chem("Diazepam"), calm());
        assertTrue(r.contains("BREATHING STOPS"), r);
        assertTrue(c.breathingStage());
        assertTrue(c.unconscious());
        assertTrue(c.roundsMatter());
        double o = c.survival.get(Rules.Stat.OXYGEN);
        c.breathRound(new Random(1));
        assertEquals(o - 1, c.survival.get(Rules.Stat.OXYGEN), 1e-9, "Oxygen −1 a round");
        c.breathHelpUntil = c.now() + Menus.BREATH_HELP_HOURS;
        c.breathRound(new Random(1));
        assertEquals(o, c.survival.get(Rules.Stat.OXYGEN), 1e-9, "a medic breathes for him: the breath refills");
        c.breathHelpUntil = -1;
        c.addictions.add("Morphine");
        String n = c.dose(chem("Naloxone"), calm());
        assertFalse(c.breathingStage(), n);
        assertNull(c.inSlot("chem:Morphine") == null ? null : c.inSlot("chem:Morphine").other().get("opioid"), "the morphine is ended");
        assertTrue(c.inSlot("chem:Morphine") != null && c.inSlot("chem:Morphine").name().endsWith("(withdrawal)"), "an addict goes into withdrawal at once: " + n);
        Character a = AirTest.human();
        a.dose(chem("Codeine"), calm());
        a.dose(chem("Alcohol"), calm());
        assertTrue(a.breathingStage(), "codeine and a drink");
    }

    @Test
    void anOpioidWorksSlowlyInTheColdOrDry() {
        Character c = AirTest.human();
        c.survival.put(Rules.Stat.HYPOTHERMIA, 0.5 * c.max(Rules.Stat.HYPOTHERMIA));
        c.survival.put(Rules.Stat.PAIN, 60.0);
        String r = c.dose(chem("Morphine"), calm());
        assertTrue(r.contains("works slowly"), r);
        assertEquals(40, c.painFelt(), 1e-9, "60 − 40 + 20: half the relief the first hour");
        Hud.hour(c);
        assertNull(c.inSlot("onset:Morphine"));
        assertEquals(20, c.painFelt(), 1e-9, "then the full relief");
        Character w = AirTest.human();
        w.survival.put(Rules.Stat.PAIN, 60.0);
        w.dose(chem("Morphine"), calm());
        assertEquals(20, w.painFelt(), 1e-9, "warm and watered: full at once");
    }

    @Test
    void theStimpakStopsEveryBleedingAndHealingCostsFoodAndWater() {
        Character c = AirTest.human();
        c.hp = 5;
        c.bleeding = 2;
        double food = c.hourly(Rules.Stat.FOOD);
        String r = Meds.inject(c, "Stimpak", calm());
        assertEquals(15, c.hp, r);
        assertEquals(0, c.bleeding, 1e-9);
        assertEquals(1, c.other("healing_rate"), 1e-9);
        assertEquals(1.25 * food, c.hourly(Rules.Stat.FOOD), 1e-9);
        assertEquals(-10, c.other("pain_felt"), 1e-9);
        Meds.inject(c, "Super Stimpak", calm());
        assertEquals(2, c.other("healing_rate"), 1e-9, "the Super's +2");
        Meds.inject(c, "Stimpak", calm());
        assertEquals(2, c.other("healing_rate"), 1e-9, "a Stimpak after it does not cut the +2");
    }

    @Test
    void heldHpGoWhenTheHourEndsAndAHitWhileHeldCameOffTheHeldFirst() {
        Character c = AirTest.human();
        int max = c.hpMax();
        c.hp = 10;
        String r = Meds.inject(c, "Super Stimpak", calm());
        assertEquals(Math.min(max, 35) + 25, c.hp, r);
        assertTrue(r.contains("the heart holds"), r);
        assertTrue(c.holding);
        Hud.hit(c, 10, "normal");
        Hud.hour(c);
        assertFalse(c.holding);
        assertEquals(Math.min(max, 35), c.hp, "the 10 came off the 25 held");
        Character h = AirTest.human();
        h.hp = -3;
        String y = Meds.inject(h, "Hypo", calm());
        assertEquals(37, h.hp, y);
        assertTrue(y.contains("stands up"), y);
        Hud.hour(h);
        assertEquals(-3, h.hp, "when it ends he is where he was");
        assertTrue(h.news.stream().anyMatch(n -> n.contains("DYING again")), h.news.toString());
        Character z = AirTest.human();
        z.hp = 10;
        Random racing = new Random() { @Override public int nextInt(int b) { return 9; } };
        String q = Meds.inject(z, "Super Stimpak", racing);
        assertTrue(q.contains("the heart races"), q);
        assertEquals(-2, z.other("ap"), 1e-9);
    }

    @Test
    void theTraumaPackNeedsMedicineFortyAndSealsEverything() {
        Character t = AirTest.human();
        t.hp = 3;
        t.bleeding = 3;
        double bmax = t.max(Rules.Stat.BLOOD);
        t.survival.put(Rules.Stat.BLOOD, 0.3 * bmax);
        assertTrue(Meds.traumaPack(39, t).startsWith("!"), "under 40 the kit stays unused");
        String r = Meds.traumaPack(40, t);
        assertEquals(0, t.bleeding, 1e-9, r);
        assertEquals(0.8 * bmax, t.survival.get(Rules.Stat.BLOOD), 1e-9);
        assertEquals(18, t.hp);
        assertEquals(-40, t.other("pain_felt"), 1e-9);
        assertEquals(2, t.other("healing_rate"), 1e-9);
    }

    @Test
    void fixerHidesTheWithdrawalHydraMendsALegPropranololRebounds() {
        Character c = AirTest.human();
        int a = c.attr(Rules.A, false);
        c.add(c.stageEffect(chem("Morphine"), 2));
        assertEquals(a - 1, c.attr(Rules.A, false), "morphine's withdrawal");
        c.dose(chem("Fixer"), calm());
        assertEquals(a, c.attr(Rules.A, false), "Fixer: the withdrawal off");
        for (int h = 0; h < 24; h++) Hud.hour(c);
        for (Rules.Stat st : Rules.Stat.values())   // the day's hunger and thirst out of the way: only the withdrawal is read
            c.survival.put(st, switch (st) { case POISON, RADS, HYPOTHERMIA, HYPERTHERMIA, PAIN -> 0.0; default -> c.max(st); });
        assertEquals(a - 1, c.attr(Rules.A, false), "after its day it is back");
        Character l = AirTest.human();
        l.brokenLegs = 1;
        l.dose(chem("Hydra"), calm());
        for (int h = 0; h < 71; h++) Hud.hour(l);
        assertEquals(1, l.brokenLegs, "bone still has to knit");
        Hud.hour(l);
        assertEquals(0, l.brokenLegs, "mended in 3 days");
        Character p = AirTest.human();
        for (int d = 0; d < 7; d++) { p.dose(chem("Propranolol"), calm()); for (int h = 0; h < 24; h++) Hud.hour(p); }
        assertNotNull(p.inSlot("rebound:Propranolol"), "stopped after a week: " + p.news);
        assertEquals(-1, p.other("ap"), 1e-9);
        Character s = AirTest.human();
        s.dose(chem("Propranolol"), calm());
        assertEquals(10, s.other("aimed"), 1e-9);
        assertEquals(-1, s.other("ap"), 1e-9);
        assertEquals(0.5, s.hourly(Rules.Stat.HYPOTHERMIA), 1e-9, "cold hands");
        for (int h = 0; h < 30; h++) Hud.hour(s);
        assertNull(s.inSlot("rebound:Propranolol"), "one dose: no rebound");
        Character st = AirTest.human();
        st.dose(chem("Steady"), calm());
        assertEquals(15, st.other("aimed"), 1e-9);
        assertEquals(0, st.other("ap"), 1e-9, "none of propranolol's costs");
        assertEquals(0.10, chem("Steady").chance(), 1e-9);
    }

    @Test
    void aTierFourDrugForEverySurvivalIssue() {
        Character c = AirTest.human();
        double water = c.hourly(Rules.Stat.WATER), food = c.hourly(Rules.Stat.FOOD);
        c.dose(chem("Hydrotab"), calm());
        assertEquals(0.5 * water, c.hourly(Rules.Stat.WATER), 1e-9);
        for (int h = 0; h < 24; h++) Hud.hour(c);
        assertEquals(1.25 * water, c.hourly(Rules.Stat.WATER), 1e-9, "the next day +25 %");
        Character t = AirTest.human();
        int ap = t.apMax();
        t.dose(chem("Torpor"), calm());
        assertEquals(0.5 * food, t.hourly(Rules.Stat.FOOD), 1e-9);
        assertEquals(0.5 * water, t.hourly(Rules.Stat.WATER), 1e-9);
        assertEquals(ap - 2, t.apMax());
        Character f = AirTest.human();
        f.dose(chem("Furnace"), calm());
        assertEquals(-0.5, f.other("cold_gain"), 1e-9);
        assertEquals(1.5 * food, f.hourly(Rules.Stat.FOOD), 1e-9);
        Character k = AirTest.human();
        k.dose(chem("Coolant"), calm());
        assertEquals(-0.5, k.other("heat_gain"), 1e-9);
        Character n = AirTest.human();
        n.sleeping = true;
        double gain = n.hourly(Rules.Stat.SLEEP);
        n.dose(chem("Nightcap"), calm());
        assertEquals(2 * gain, n.hourly(Rules.Stat.SLEEP), 1e-9);
        n.hp = 1;
        for (int h = 0; h < 4; h++) Hud.hour(n);
        assertTrue(n.hp > 1, "4 hours of unbroken sleep count as 8: the healing came");
        Character p = AirTest.human();
        p.survival.put(Rules.Stat.POISON, 80.0);
        p.dose(chem("Antidote"), calm());
        assertEquals(5, p.survival.get(Rules.Stat.POISON), 1e-9);
        Character o = AirTest.human();
        o.survival.put(Rules.Stat.OXYGEN, 3.0);
        o.dose(chem("O-Shot"), calm());
        assertEquals(o.max(Rules.Stat.OXYGEN), o.survival.get(Rules.Stat.OXYGEN), 1e-9);
        o.holdingBreath = true;
        for (int r = 0; r < 10; r++) o.breathRound(new Random(1));
        assertEquals(o.max(Rules.Stat.OXYGEN), o.survival.get(Rules.Stat.OXYGEN), 1e-9, "10 more rounds of breath");
        o.breathRound(new Random(1));
        assertTrue(o.survival.get(Rules.Stat.OXYGEN) < o.max(Rules.Stat.OXYGEN));
        Character u = AirTest.human();
        u.dose(chem("Numb"), calm());
        assertEquals(-40, u.other("pain_felt"), 1e-9);
        assertEquals(0, u.other("opioid") + u.other("depressant"), 1e-9, "no breathing slowed");
        assertEquals(0, chem("Numb").chance(), 1e-9, "no addiction");
        Character v = AirTest.human();
        Meds.useSelf(v, "Virex", calm());
        assertEquals(100, v.other("viral_immunity"), 1e-9);
        Character b = AirTest.human();
        double bm = b.max(Rules.Stat.BLOOD);
        b.survival.put(Rules.Stat.BLOOD, 0.5 * bm);
        Meds.useSelf(b, "Blood Pack", calm());
        assertEquals(0.9 * bm, b.survival.get(Rules.Stat.BLOOD), 1e-9);
        Diseases.Disease dys = Diseases.ALL.get("Dysentery");
        assertEquals(dys.families().get(0), Meds.family("Lysin", dys));
        assertEquals(1.0, Meds.strength("Lysin", 14), 1e-9, "never aged");
        assertEquals(1, Character.withdrawal(c.stageEffect(chem("Morphine"), 2)) ? 1 : 0);
    }

    @Test
    void theHomeMadeAndTheMedicsBloodAndAtropine() {
        assertEquals(List.of("Hemostatic gauze", "Bandage", "Sphagnum moss", "Wound powder", "Rag dressing"), Meds.DRESSINGS);
        Character c = AirTest.human();
        assertTrue(Meds.useSelf(c, "Home ORS", calm()).contains("halved"));
        assertEquals(-0.5, c.other("runs"), 1e-9);
        Character t = AirTest.human();
        t.survival.put(Rules.Stat.POISON, 40.0);
        assertTrue(Meds.atropine(49, t).startsWith("!"), "an overdose kills: Medicine 50");
        Meds.atropine(50, t);
        assertEquals(10, t.survival.get(Rules.Stat.POISON), 1e-9);
        Character donor = AirTest.human(), pat = AirTest.human();
        double dm = donor.max(Rules.Stat.BLOOD), pm = pat.max(Rules.Stat.BLOOD);
        pat.survival.put(Rules.Stat.BLOOD, 0.4 * pm);
        String r = Meds.transfuse(donor, pat);
        assertEquals(0.75 * dm, donor.survival.get(Rules.Stat.BLOOD), 1e-9, r);
        assertEquals(0.65 * pm, pat.survival.get(Rules.Stat.BLOOD), 1e-9);
        donor.survival.put(Rules.Stat.BLOOD, 0.4 * dm);
        assertTrue(Meds.transfuse(donor, pat).startsWith("!"), "too little to give");
        PlayerMock p = player();
        assertTrue(Meds.forAct(p, Diseases.ALL.get("Dysentery"), new Diseases.Case(), "maggots", c).startsWith("!"), "maggots are an item now");
        p.getInventory().addItem(Items.make(Meds.id("Maggots"), 1));
        assertEquals("maggots", Meds.forAct(p, Diseases.ALL.get("Dysentery"), new Diseases.Case(), "maggots", c));
        for (String n : List.of("Valerian", "Hops", "Herbal bitters", "Absinthe")) assertEquals("1", Items.ttrOf("chem:" + n)[1], n);
        assertEquals(1, chem("Absinthe").effect().other().get("depressant"), 1e-9, "it is alcohol");
        assertEquals(1, chem("Herbal bitters").effect().other().get("nausea"), 1e-9);
        assertEquals(50, chem("Activated charcoal").effect().other().get("poison_res"), 1e-9);
    }

    @Test
    void theTiersAndPervitinsTwoRecipes() {
        for (String n : List.of("Fixer", "Hydra", "Cateye", "Steady", "Hydrotab", "Torpor", "Nightcap", "Antidote", "Furnace", "Coolant", "Numb", "O-Shot"))
            assertEquals("4", Items.ttrOf("chem:" + n)[1], n);
        for (String n : List.of("Propranolol", "Naloxone", "Activated charcoal", "Pseudoephedrine"))
            assertEquals("3", Items.ttrOf("chem:" + n)[1], n);
        for (String n : List.of("Super Stimpak", "Hypo", "Trauma pack", "Blood Pack", "Lysin", "Virex"))
            assertTrue(Meds.id(n).startsWith("thing:Medicine|4|"), n);
        Production.Recipe k = Production.RECIPES.get("pervitin-kitchen"), e = Production.RECIPES.get("pervitin-from-ephedra"),
                g = Production.RECIPES.get("grow-ephedra");
        assertNotNull(k);
        assertNotNull(e);
        assertNotNull(g);
        assertEquals("Pervitin kitchen", k.research);
        assertEquals("Pervitin kitchen", e.research);
        assertEquals("Ephedra", g.research, "the plant unlocked by its own card");
        assertTrue(k.inputs.stream().anyMatch(i -> i.items != null && i.items.contains("Pseudoephedrine")), "the flu tablets");
        assertTrue(e.inputs.stream().anyMatch(i -> i.items != null && i.items.contains("Ephedra")));
        assertTrue(e.inputs.stream().noneMatch(i -> i.type.equals("Chemicals") && i.tier > 2), "low-tier chemicals");
        assertTrue(Research.CARDS.containsKey("pervitin-kitchen"), Research.CARDS.keySet().toString());
        assertTrue(Research.CARDS.containsKey("ephedra"));
    }
}
