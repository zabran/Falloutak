package cz.lollywood.falloutak;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `09d` round 3 (Marek 11-23): *"the weekly tick inside the plugin ... please continue with that."* The plugin's {@link Tick} against the
 * simulation's own numbers (`fallout_danger_sim.py --fixture` writes the made-up map's food step) and against the simulation's own checks
 * (`--test`: the raiders follow the record, the creatures hold, clearing lasts, held ground stays clear).
 */
class TickTest {

    private static WorldMap map() { return WorldMapTest.fixture(); }

    private static JsonObject ref() {
        try (var in = TickTest.class.getResourceAsStream("/worldmap-test-tick-ref.json")) {
            assertNotNull(in);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException e) { throw new RuntimeException(e); }
    }

    @Test
    void theFoodStepIsTheSimulations() {
        WorldMap m = map();
        assertNotNull(m.tick, "the fixture carries the tick's data");
        Tick t = new Tick(m, new Random(1));
        JsonObject r = ref();
        assertEquals(r.get("years").getAsDouble(), t.years(), 1e-12);
        double people = 0;
        for (int p = 0; p < m.tick.pop.length; p++) people += t.people(p);
        assertEquals(r.get("people").getAsDouble(), people, 1e-6);
        Tick.Supply sp = t.supply();
        assertEquals(r.get("need").getAsDouble(), t.need, 1e-9 * r.get("need").getAsDouble());
        JsonArray got = r.getAsJsonArray("got");
        for (int k = 0; k < got.size(); k++) assertEquals(got.get(k).getAsDouble(), sp.got()[k], 1e-9 * Math.max(1, got.get(k).getAsDouble()), "base " + k);
        t.feed(sp.got());
        JsonArray f1 = r.getAsJsonArray("fighters1");
        for (int k = 0; k < f1.size(); k++) assertEquals(f1.get(k).getAsDouble(), m.bases.get(k).fighters, 1e-9 * f1.get(k).getAsDouble(), "base " + k + " after the feed");
    }

    @Test
    void theRaidersFollowTheRecordAndTheCreaturesHold() {
        WorldMap m = map();
        Tick t = new Tick(m, new Random(3));
        double r0 = t.raiders();
        Map<String, Integer> lairs0 = new HashMap<>(t.startCount);
        for (int w = 0; w < 5 * 52; w++) {
            t.step();
            double want = r0 * t.record(t.years()) / t.record(m.tick.startYear) * t.clearedShare;
            assertTrue(t.raiders() <= want * 1.0001 && t.raiders() >= want * 0.7, "week " + t.week + ": " + t.raiders() + " vs the record's " + want);
        }
        for (Map.Entry<String, Double> e : t.startAnimals.entrySet()) {
            double now = 0;
            int n = 0;
            for (WorldMap.Src l : m.lairs) if (!l.boss && l.kind.equals(e.getKey())) { now += l.animals; n++; }
            assertTrue(now / e.getValue() > 0.2 && now / e.getValue() < 2.5, e.getKey() + ": " + now + " of " + e.getValue());
            assertTrue(n <= (int) (lairs0.get(e.getKey()) * 1.6) + 1, e.getKey() + " lairs " + n);
        }
        for (WorldMap.Src l : m.lairs) if (l.boss) assertEquals(4.0, l.animals, 1e-9, "a boss lair does not breed");
        assertTrue(t.summary().contains("week 260"), t.summary());
    }

    @Test
    void aClearingLastsAndHeldGroundStaysClear() {
        WorldMap m = map();
        Tick t = new Tick(m, new Random(5));
        double before = t.raiders();
        double[] c = t.clear(13.5, 10.5, 3);   // the Pond raiders' seat
        assertEquals(1, (int) c[0]);
        assertTrue(t.clearedShare < 0.5, "the seat held most of the region's raiders: " + t.clearedShare);
        t.step();
        for (WorldMap.Src b : m.bases) assertFalse(b.cleared, "a cleared base is gone at the next week");
        double want = before * t.record(t.years()) / t.record(m.tick.startYear) * t.clearedShare;
        assertTrue(t.raiders() <= want * 1.0001, "the clearing lasts: " + t.raiders() + " vs " + want);
        // hold everything: nothing moves or splits any more
        WorldMap m2 = map();
        Tick h = new Tick(m2, new Random(6));
        assertEquals(m2.w * m2.h, h.hold(15, 20, 100));
        Map<WorldMap.Src, int[]> at = new HashMap<>();
        for (WorldMap.Src b : m2.bases) at.put(b, new int[]{b.i, b.j});
        for (int w = 0; w < 52; w++) h.step();
        for (WorldMap.Src b : m2.bases) {
            int[] was = at.get(b);
            assertNotNull(was, "no new camp on held ground");
            assertArrayEquals(was, new int[]{b.i, b.j}, "nothing moves on held ground");
        }
    }

    @Test
    void theStateIsSavedAndLoaded() throws Exception {
        WorldMap m = map();
        Tick t = new Tick(m, new Random(8));
        for (int w = 0; w < 30; w++) t.step();
        t.hold(3, 3, 2);
        Path f = Files.createTempFile("worldmap-state", ".json");
        t.save(f);
        WorldMap m2 = map();
        Tick t2 = Tick.load(m2, f, new Random(9));
        assertEquals(30, t2.week);
        assertEquals(t.need, t2.need, 1e-12);
        assertEquals(m.bases.size(), m2.bases.size());
        for (int k = 0; k < m.bases.size(); k++) {
            assertEquals(m.bases.get(k).i, m2.bases.get(k).i);
            assertEquals(m.bases.get(k).fighters, m2.bases.get(k).fighters, 1e-9);
        }
        assertEquals(m.lairs.size(), m2.lairs.size());
        assertTrue(t2.held[3 * m2.w + 3]);
        Files.deleteIfExists(f);
    }

    @Test
    void theGamesWeeksRunTheTick() {
        Travel.use(map());
        try {
            assertNotNull(Travel.tick);
            assertEquals("", Travel.catchUp(10), "the first call only sets the day");
            assertEquals("", Travel.catchUp(16));
            assertEquals(0, Travel.tick.week);
            String r = Travel.catchUp(24);
            assertEquals(2, Travel.tick.week, r);
            assertTrue(r.startsWith("2 week(s)"), r);
            Travel.here("Roadside");
            assertTrue(Travel.hold(2).contains("held"));
            assertTrue(Travel.clear(30).contains("raider bases"));
        } finally { Travel.map = null; Travel.tick = null; }
    }
}
