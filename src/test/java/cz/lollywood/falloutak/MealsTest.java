package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Marek 11-24: eating and drinking from the pack and in the base (04 §3.9's tiers by harm, 04b §5's rolls), soap as a setting. */
class MealsTest {
    /** A Random whose every double is {@code v}. */
    static Random always(double v) {
        return new Random() { @Override public double nextDouble() { return v; } };
    }

    static double rads(Character c) { return c.survival.getOrDefault(Rules.Stat.RADS, 0.0); }
    /** What a rad taken becomes after his rad resistance. */
    static double rr(Character c) { return 1 - Math.min(100, c.resistances()[1]) / 100; }

    @Test
    void aPortionFeedsAlikeAndItsTierIsTheHarm() {
        Character c = AirTest.human();
        c.vaccinesSet = true;
        c.survival.put(Rules.Stat.FOOD, 10.0);
        double surv = c.skill("Survival");
        String r = Meals.eat(c, 3, 1, "Potatoes", always(0.99));
        assertEquals(10 + 8 * (1 + surv / 100), c.survival.get(Rules.Stat.FOOD), 1e-6, r);
        assertEquals(0, rads(c), 1e-9, "Tier III is clean");
        Meals.eat(c, 2, 1, "Field potatoes", always(0.99));
        assertEquals(3 * rr(c), rads(c), 1e-6, "Tier II field crops: +3 rads, no roll");
        // a stale potato (made Tier III, now Tier II): the roll only, 10 % (rolled 5)
        r = Meals.eat(c, 2, 1, "Potatoes", always(0.05));
        assertEquals(3 * rr(c), rads(c), 1e-6, "no rads from a stale clean portion");
        assertNotNull(Diseases.active(c, "Food poisoning"), r);
        // raw meat: +3 and 3 % (rolled 5: not caught)
        Character d = AirTest.human();
        d.vaccinesSet = true;
        Meals.eat(d, 1, 1, "Raw meat", always(0.05));
        assertEquals(3 * rr(d), rads(d), 1e-6);
        assertNull(Diseases.active(d, "Food poisoning"), "raw meat 3 %: a roll of 5 misses");
        // kept food: clean, botulism 0.1 %
        assertTrue(Meals.harmText("Food", 3, 2, "Jerky").contains("botulism"));
        assertTrue(Meals.harmText("Food", 2, 1, "Potatoes").startsWith("stale"));
    }

    @Test
    void waterByItsTierAndNameAndTheStagesThatStopIt() {
        Character c = AirTest.human();
        c.vaccinesSet = true;
        c.survival.put(Rules.Stat.WATER, 20.0);
        Meals.drink(c, 2, "Boiled water", always(0.0));
        assertEquals(18 * rr(c), rads(c), 1e-6, "boiled: the rads stay, the germs gone");
        assertTrue(c.diseases.isEmpty(), "no roll for boiled water");
        Meals.drink(c, 2, "Filtered water", always(0.0));
        assertEquals(18 * rr(c), rads(c), 1e-6, "filtered: no rads");
        assertFalse(c.diseases.isEmpty(), "the germs' roll, failed at 0: a disease of the spring's two");
        Character d = AirTest.human();
        d.vaccinesSet = true;
        Meals.drink(d, 1, "Dirty water", always(0.99));
        assertEquals(18 * rr(d), rads(d), 1e-6);
        assertEquals(1.0 - Math.pow(0.9, 0.5), Meals.chance(10, 0.5) / 100, 1e-9, "a litre = half the day's 2 l roll");
        // Deadly Dehydration: plain water counts a tenth (04 §3.2)
        Character e = AirTest.human();
        e.survival.put(Rules.Stat.WATER, 0.5);
        assertEquals(0.1, Meals.plainWaterShare(e), 1e-9);
        // a stage that keeps water down
        Character f = AirTest.human();
        f.add(new Character.Effect("Cholera: test", "disease", "disease:Cholera", false, new int[7], Map.of("no_drink", 1.0), 1e6, null, 100));
        assertNotNull(Meals.cannotDrink(f));
        assertTrue(Meals.drink(f, 3, "Purified water", always(0.5)).startsWith("!"));
    }

    @Test
    void theBaseEatsTheBestFirstAndFeedsTheCharactersInIt() {
        Production pr = new Production();
        pr.build("Reactor", 4, "Reactor");
        pr.build("Kitchen", 3, "K");
        Base base = new Base(null);
        base.addUnits("Water", 1, 1, 4);
        base.addUnits("Food", 1, 1, 10);
        base.addUnits("Food", 3, 2, 4);
        base.addUnits("Food", 3, 1, 2);
        List<String> out = pr.advance(1, 0, base, new ProductionTest.Crew(), 2);   // 2 people: 6 portions, 4 l
        assertTrue(out.stream().anyMatch(s -> s.startsWith("Food: the people ate 2 Food III, 4 Food III R2")), out.toString());
        assertEquals(10, base.count("Food", 1, 1), "Tier I last: nothing else was short");
        assertTrue(pr.fedFood);
        Production.Ration r = pr.rations.get(0);
        assertEquals(2, r.water()[1], 1e-9, "each drank 2 l of Tier I");
        Character c = AirTest.human();
        c.vaccinesSet = true;
        c.survival.put(Rules.Stat.FOOD, 30.0);
        String m = Meals.baseDay(c, r, always(0.99));
        assertEquals(c.max(Rules.Stat.FOOD), c.survival.get(Rules.Stat.FOOD), 1e-9, "fed in full: his bar refilled");
        assertEquals(36 * rr(c), rads(c), 1e-6, m);   // 2 l of Tier I at 18 a litre; the kept food is clean
        // fed by the base, his own bars do not fall
        c.baseFood = c.baseWater = true;
        assertEquals(0, c.hourly(Rules.Stat.FOOD), 1e-9);
        assertEquals(0, c.hourly(Rules.Stat.WATER), 1e-9);
        // the next day the store has only the raw meat: they eat it, it harms
        out = pr.advance(24, 1, base, new ProductionTest.Crew(), 2);
        assertTrue(out.stream().anyMatch(s -> s.startsWith("Food: the people ate 6 Food I") && s.contains("one harm")), out.toString());
    }

    @Test
    void soapIsASettingAndTheBarWearsDown() {
        Character c = AirTest.human();
        assertFalse(c.washSoap);
        c.washSoap = true;
        c.soapDays = 1;
        for (int h = 0; h < 12; h++) Hud.hour(c);
        assertEquals(0.5, c.soapDays, 1e-9);
        c.washSoap = false;
        for (int h = 0; h < 12; h++) Hud.hour(c);
        assertEquals(0.5, c.soapDays, 1e-9, "off: the bar rests");
    }

    @Test
    void aFilthyWoundDoublesItsInfection() {
        Character c = AirTest.human();
        c.unwashedDays = 8;
        assertNotNull(Diseases.wound(c, "gunshot", always(0.25)), "15 % × 2: a roll of 25 catches it");
        Character d = AirTest.human();
        assertNull(Diseases.wound(d, "gunshot", always(0.25)), "washed: 15 %, a roll of 25 misses");
    }
}
