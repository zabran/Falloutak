package cz.lollywood.falloutak;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Part 9d: the world map's encounter model against the simulation's own numbers. The map is a MADE-UP one (`fallout_danger_sim.py
 * --fixture`: 40 x 30 sectors, three bases, five lairs) with Python's rates at six sectors, so the plugin computes what the simulation
 * computes; the real map stays off the repository (its camps are hidden).
 */
class WorldMapTest {
    WorldMap m;

    static WorldMap fixture() {
        try (var in = WorldMapTest.class.getResourceAsStream("/worldmap-test.json")) {
            assertNotNull(in, "the fixture");
            return WorldMap.read(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (java.io.IOException e) { throw new RuntimeException(e); }
    }

    @BeforeEach
    void setUp() { m = fixture(); }

    @Test
    void theFixtureLoads() {
        assertEquals(40, m.w); assertEquals(30, m.h);
        assertEquals(3, m.bases.size()); assertEquals(5, m.lairs.size()); assertEquals(3, m.places.size());
        assertEquals("road", m.terrainAt(13, 0)); assertEquals("forest", m.terrainAt(20, 30)); assertEquals("town", m.terrainAt(2, 2));
        assertEquals(2, m.groundAt(25, 35));
        assertTrue(m.kinds.containsKey("raiders:pond") && m.kinds.containsKey("creature:boar") && m.kinds.containsKey("traders"));
    }

    @Test
    void theKernelSumsToOneOverItsReach() {
        for (double reach : new double[]{1, 3, 6, 8, 12}) {
            double s = 0;
            int r = (int) Math.ceil(reach / Math.min(m.ex, m.ey)) + 1;
            for (int a = -r; a <= r; a++) for (int b = -r; b <= r; b++) s += m.kernel(reach, a, b);
            assertEquals(1.0, s, 1e-9, "reach " + reach);
        }
    }

    @Test
    void theRatesAreTheSimulations() throws Exception {
        try (var in = WorldMapTest.class.getResourceAsStream("/worldmap-test-ref.json")) {
            assertNotNull(in);
            int checked = 0;
            for (JsonElement e : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray()) {
                JsonObject r = e.getAsJsonObject();
                int i = r.get("i").getAsInt(), j = r.get("j").getAsInt(), season = r.get("season").getAsInt();
                boolean day = r.get("day").getAsBoolean();
                List<WorldMap.Row> rows = m.table(i, j, season, day);
                Map<String, Double> types = m.types(rows);
                for (Map.Entry<String, JsonElement> t : r.getAsJsonObject("types").entrySet()) {
                    double want = t.getValue().getAsDouble();
                    assertEquals(want, types.get(t.getKey()), Math.max(1e-12, 1e-9 * want), i + "," + j + " " + t.getKey());
                    checked++;
                }
                Map<String, Double> kinds = new HashMap<>();
                for (WorldMap.Row row : rows) kinds.put(row.kind().key(), row.all());
                for (Map.Entry<String, JsonElement> k : r.getAsJsonObject("kinds").entrySet()) {
                    double want = k.getValue().getAsDouble();
                    assertEquals(want, kinds.getOrDefault(k.getKey(), 0.0), Math.max(1e-12, 1e-9 * want), i + "," + j + " " + k.getKey());
                }
                assertEquals(r.getAsJsonObject("kinds").size(), kinds.size(), "the same kinds at " + i + "," + j);
            }
            assertTrue(checked >= 40, "every reference value read");
        }
    }

    @Test
    void aDrawFollowsTheShares() {
        List<WorldMap.Row> rows = m.table(13, 10, 2, true);           // the Pond raiders' seat on the road, summer, by day
        double tot = WorldMap.total(rows);
        Random rnd = new Random(7);
        Map<String, Integer> n = new HashMap<>();
        int fights = 0, N = 40000;
        for (int k = 0; k < N; k++) {
            WorldMap.Encounter e = WorldMap.draw(rows, rnd);
            n.merge(e.kind().key(), 1, Integer::sum);
            if (e.type().equals("fight")) fights++;
        }
        for (WorldMap.Row r : rows) assertEquals(r.all() / tot, n.getOrDefault(r.kind().key(), 0) / (double) N, 0.01, r.kind().key());
        assertEquals(m.types(rows).get("fight") / tot, fights / (double) N, 0.01);
        WorldMap.Encounter e = WorldMap.draw(m.table(13, 10, 2, true), new Random(1));
        assertTrue(e.count() >= 1);
    }

    @Test
    void aSightAtNightNeedsHalfTheDistance() {
        // a boar seen at night: its peaceful meetings halved against the same activity by day
        WorldMap.Row d = m.table(10, 30, 2, true).stream().filter(r -> r.kind().key().equals("creature:boar")).findFirst().orElseThrow();
        WorldMap.Row n = m.table(10, 30, 2, false).stream().filter(r -> r.kind().key().equals("creature:boar")).findFirst().orElseThrow();
        WorldMap.Kind b = d.kind();
        assertEquals(d.peace() / b.day() * b.night() * 0.5, n.peace(), 1e-12);
        assertEquals(d.fight() / b.day() * b.night(), n.fight(), 1e-12);
    }

    @Test
    void aClearedBaseIsGoneFromTheTable() {
        double before = m.types(m.table(13, 12, 2, true)).get("fight");
        m.bases.get(0).cleared = true;                                 // the Pond seat
        double after = m.types(m.table(13, 12, 2, true)).get("fight");
        assertTrue(after < before * 0.5, before + " -> " + after);
    }

    /** A Random that rolls the same number every time. */
    static Random fixed(double v) {
        return new Random() {
            @Override public double nextDouble() { return v; }
            @Override public int nextInt(int bound) { return (int) (v * bound); }
        };
    }

    @Test
    void aWalkWithoutEncountersArrivesInItsTerrainsTime() {
        Travel.use(m);
        WorldMap.Place a = m.place("Westtown"), b = m.place("Roadside");
        Travel.Walk w = Travel.walk(a.i() + 0.5, a.j() + 0.5, b.i() + 0.5, b.j() + 0.5, 1.0, 9, 0, false, fixed(0.999999));
        assertEquals("arrived", w.why());
        assertNull(w.enc());
        double km = m.km(a.i() + 0.5, a.j() + 0.5, b.i() + 0.5, b.j() + 0.5);
        assertEquals(km, w.km(), 1e-9);
        // between the road's speed (all road) and the forest's (all forest): town and field at 2.5 / 1.25, the road 3.75
        assertTrue(w.hours() > km / 3.75 && w.hours() < km / 1.25, w.hours() + " h for " + km + " km");
        // twice as fast with the best Survival 100 (Outdoorsman)
        Travel.Walk w2 = Travel.walk(a.i() + 0.5, a.j() + 0.5, b.i() + 0.5, b.j() + 0.5, 2.0, 9, 0, false, fixed(0.999999));
        assertEquals(w.hours() / 2, w2.hours(), 1e-9);
    }

    @Test
    void anEncounterComesWhereTheHazardFell() {
        Travel.use(m);
        WorldMap.Place a = m.place("Westtown"), b = m.place("Roadside");
        Travel.Walk w = Travel.walk(a.i() + 0.5, a.j() + 0.5, b.i() + 0.5, b.j() + 0.5, 1.0, 9, 0, false, fixed(0.0));
        assertNotNull(w.enc(), "a roll of 0 always meets someone");
        assertEquals("an encounter", w.why());
        assertTrue(w.hours() <= Travel.WINDOW_H + 1e-9, "within the first 4 hours");
        assertTrue(w.km() > 0);
    }

    @Test
    void theDaysMarchStopsAtEightHours() {
        Travel.use(m);
        // across the whole map at half speed (overloaded): more than 8 hours
        Travel.Walk w = Travel.walk(0.5, 0.5, 29.5, 39.5, 0.5, 8, 0, false, fixed(0.999999));
        assertEquals("8 hours marched today", w.why());
        assertEquals(8.0, w.hours(), 0.6);
        Travel.Walk pushed = Travel.walk(0.5, 0.5, 29.5, 39.5, 0.5, 8, 0, true, fixed(0.999999));
        assertEquals("arrived", pushed.why(), "pushing on goes past the 8 hours");
        // already 6 hours marched today: 2 more
        Travel.Walk w3 = Travel.walk(0.5, 0.5, 29.5, 39.5, 0.5, 8, 6, false, fixed(0.999999));
        assertEquals(2.0, w3.hours(), 0.6);
    }

    @Test
    void theRollsChanceIsOneMinusEToTheHazard() {
        // a 4-hour window in one sector: over many walks the share with an encounter matches 1 - e^(-meetings x hours)
        Travel.use(m);
        Travel.season = 2;
        int i = 13, j = 12;
        double rate = WorldMap.total(m.table(i, j, 2, true));
        double hours = 0;
        int hits = 0, N = 4000;
        Random rnd = new Random(3);
        for (int k = 0; k < N; k++) {
            Travel.Walk w = Travel.walk(i + 0.5, j + 0.1, i + 0.5, j + 0.9, 1.0 / 20, 10, 0, false, rnd);   // 0.36 km at 1/20 speed
            if (w.enc() != null) hits++;
            else hours = w.hours();
        }
        double p = 1 - Math.exp(-rate * hours);
        assertEquals(p, hits / (double) N, 0.03, "rate " + rate + " over " + hours + " h");
    }
}
