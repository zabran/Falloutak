package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The raiders' walk on a drawn floor: around a wall, up a step, and nothing when the goal is out of reach. */
class PathTest {
    /** A 12 x 12 floor at y 0 (feet at y 1); '#' a wall two high, '^' a one-block step (feet at y 2 there). */
    private static Path.World floor(String... rows) {
        return new Path.World() {
            char at(int x, int z) { return z < 0 || z >= rows.length || x < 0 || x >= rows[z].length() ? '#' : rows[z].charAt(x); }
            public boolean canStand(int x, int y, int z) {
                char c = at(x, z);
                if (c == '#') return false;
                return y == (c == '^' ? 2 : 1);
            }
            public boolean free(int x, int y, int z) {
                char c = at(x, z);
                if (c == '#') return y >= 3;
                if (c == '^') return y >= 2;
                return y >= 1;
            }
        };
    }

    @Test
    void walksAroundAWall() {
        String[] rows = {
                "..........",
                "....#.....",
                "....#.....",
                "....#.....",
                ".........."};
        Path.World w = floor(rows);
        List<Path.P> p = Path.find(w, new Path.P(2, 1, 2), (x, y, z) -> x == 6 && z == 2, 40);
        assertNotNull(p);
        assertEquals(new Path.P(6, 1, 2), p.get(p.size() - 1));
        assertEquals(8, p.size());   // around the wall's end: 2 sideways, 4 across, 2 back
        for (Path.P q : p) assertNotEquals('#', rows[q.z()].charAt(q.x()));
    }

    @Test
    void climbsOneStepAndStopsAtTheGoal() {
        Path.World w = floor("..^^..");
        List<Path.P> p = Path.find(w, new Path.P(0, 1, 0), (x, y, z) -> x == 5, 40);
        assertNotNull(p);
        assertEquals(List.of(new Path.P(1, 1, 0), new Path.P(2, 2, 0), new Path.P(3, 2, 0), new Path.P(4, 1, 0), new Path.P(5, 1, 0)), p);
        assertTrue(Path.find(w, new Path.P(0, 1, 0), (x, y, z) -> x == 0, 40).isEmpty());   // already there
        assertNull(Path.find(w, new Path.P(0, 1, 0), (x, y, z) -> x == 5, 3));             // too far for 3 steps
    }
}
