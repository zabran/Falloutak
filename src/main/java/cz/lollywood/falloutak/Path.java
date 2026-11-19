package cz.lollywood.falloutak;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How a raider walks: a breadth-first search over standing spots (feet and head free, solid ground under them), four neighbours a
 * step like the players' own block count, a step at most one block up or down. Pure: the world comes in as a predicate, so the
 * tests run it on a drawn grid.
 */
final class Path {
    private Path() {}

    /** Can a creature stand with its feet in this block? Is a block free to pass (for the head room of a step up)? */
    interface World {
        boolean canStand(int x, int y, int z);
        boolean free(int x, int y, int z);
    }

    /** Is this spot good enough (a raider: from here it can shoot the player)? */
    interface Goal { boolean done(int x, int y, int z); }

    record P(int x, int y, int z) {}

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /**
     * The shortest walk from {@code from} to the nearest spot meeting {@code goal}, at most {@code maxSteps} steps: the spots after
     * the start, in order (empty = already there); null = no such spot within {@code maxSteps}.
     */
    static List<P> find(World w, P from, Goal goal, int maxSteps) {
        if (goal.done(from.x(), from.y(), from.z())) return List.of();
        Map<P, P> prev = new HashMap<>();
        Map<P, Integer> steps = new HashMap<>();
        ArrayDeque<P> q = new ArrayDeque<>();
        q.add(from);
        steps.put(from, 0);
        while (!q.isEmpty()) {
            P a = q.poll();
            int n = steps.get(a);
            if (n >= maxSteps) continue;
            for (int[] d : DIRS) {
                P b = step(w, a, d[0], d[1]);
                if (b == null || steps.containsKey(b)) continue;
                steps.put(b, n + 1);
                prev.put(b, a);
                if (goal.done(b.x(), b.y(), b.z())) return back(prev, from, b);
                q.add(b);
            }
        }
        return null;
    }

    /** One step sideways: level, else one up (head room above the start), else one down. */
    private static P step(World w, P a, int dx, int dz) {
        int x = a.x() + dx, z = a.z() + dz;
        if (w.canStand(x, a.y(), z)) return new P(x, a.y(), z);
        if (w.canStand(x, a.y() + 1, z) && w.free(a.x(), a.y() + 2, a.z())) return new P(x, a.y() + 1, z);
        if (w.canStand(x, a.y() - 1, z) && w.free(x, a.y() + 1, z)) return new P(x, a.y() - 1, z);
        return null;
    }

    private static List<P> back(Map<P, P> prev, P from, P to) {
        List<P> l = new ArrayList<>();
        for (P p = to; !p.equals(from); p = prev.get(p)) l.add(0, p);
        return l;
    }
}
