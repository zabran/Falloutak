package cz.lollywood.falloutak;

import org.bukkit.Location;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.block.BlockMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.Coordinate;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The plugin on a mock server (MockBukkit 4.116.3) with every page recorded instead of shown (Ui.recorder): a test opens a menu,
 * reads its lines and clicks its buttons as a player would (Marek 11-18: the logic tested outside, only the UX and UI in the game).
 */
abstract class MockGame {
    /** MockBukkit leaves Block.isPassable unimplemented: a flat world (stone below y 4, air above) whose blocks answer it. */
    static final class FlatWorld extends WorldMock {
        final Map<Long, BlockMock> blocks = new HashMap<>();
        FlatWorld() { super(Material.STONE, 4); }

        @Override public BlockMock getBlockAt(int x, int y, int z) {
            long k = (((long) x & 0x3FFFFFF) << 38) | (((long) z & 0x3FFFFFF) << 12) | ((long) y & 0xFFF);
            return blocks.computeIfAbsent(k, q -> new BlockMock(y < 4 ? Material.STONE : Material.AIR, new Location(this, x, y, z)) {
                @Override public boolean isPassable() { return !getType().isSolid(); }   // grass, crops, ferns: walked through, as in the game
            });
        }
        @Override public BlockMock getBlockAt(Location l) { return getBlockAt(l.getBlockX(), l.getBlockY(), l.getBlockZ()); }
        @Override public BlockMock getBlockAt(Coordinate c) { return getBlockAt(c.x, c.y, c.z); }

        /** Blocks: steps of 0.05 along the ray to the first block that is not passable (the ground, or a wall a test puts up). */
        @Override public org.bukkit.util.RayTraceResult rayTraceBlocks(Location start, org.bukkit.util.Vector dir, double max,
                org.bukkit.FluidCollisionMode fluids, boolean ignorePassable) {
            org.bukkit.util.Vector d = dir.clone().normalize(), at = start.toVector();
            org.bukkit.block.BlockFace face = org.bukkit.block.BlockFace.UP;
            for (double t = 0; t <= max; t += 0.05) {
                org.bukkit.util.Vector q = at.clone().add(d.clone().multiply(t));
                BlockMock b = getBlockAt(q.getBlockX(), q.getBlockY(), q.getBlockZ());
                if (!b.isPassable()) return new org.bukkit.util.RayTraceResult(q, b, face);
            }
            return null;
        }
        @Override public org.bukkit.util.RayTraceResult rayTraceBlocks(Location start, org.bukkit.util.Vector dir, double max,
                org.bukkit.FluidCollisionMode fluids) { return rayTraceBlocks(start, dir, max, fluids, true); }
        @Override public org.bukkit.util.RayTraceResult rayTraceBlocks(Location start, org.bukkit.util.Vector dir, double max) {
            return rayTraceBlocks(start, dir, max, org.bukkit.FluidCollisionMode.NEVER, true);
        }

        /** Entities only (the flat world has no walls above the ground): Bukkit's own bounding-box maths. */
        @Override public org.bukkit.util.RayTraceResult rayTrace(Location start, org.bukkit.util.Vector dir, double max,
                org.bukkit.FluidCollisionMode fluids, boolean passable, double size, java.util.function.Predicate<? super org.bukkit.entity.Entity> filter) {
            org.bukkit.util.RayTraceResult best = null;
            double bestD = Double.MAX_VALUE;
            for (org.bukkit.entity.Entity e : getEntities()) {
                if (filter != null && !filter.test(e)) continue;
                org.bukkit.util.RayTraceResult r = e.getBoundingBox().clone().expand(size).rayTrace(start.toVector(), dir, max);
                if (r == null) continue;
                double d = r.getHitPosition().distanceSquared(start.toVector());
                if (d < bestD) { bestD = d; best = new org.bukkit.util.RayTraceResult(r.getHitPosition(), e, r.getHitBlockFace()); }
            }
            return best;
        }
    }

    ServerMock server;
    FalloutakPlugin plugin;
    FlatWorld world;
    /** Every page shown, in order. */
    final List<Ui.Page> pages = new ArrayList<>();

    @BeforeEach
    void up() {
        server = MockBukkit.mock();
        world = new FlatWorld();
        server.addWorld(world);
        plugin = MockBukkit.load(FalloutakPlugin.class);
        Ui.recorder = pages::add;
        plugin.base.pools.clear();      // the data folder may outlive a test
        plugin.base.unlocked.clear();
        plugin.base.chests.clear();
        plugin.base.save();
    }

    @AfterEach
    void down() {
        Ui.recorder = null;
        Sfx.recorder = null;
        Sfx.f2Loaded.clear();
        MockBukkit.unmock();
    }

    PlayerMock player() { return player(0.5, 0.5, true); }

    PlayerMock player(double x, double z, boolean op) {
        PlayerMock p = server.addPlayer();
        p.setOp(op);
        p.teleport(new Location(world, x, 4, z));
        return p;
    }

    /** The page shown last. */
    Ui.Page page() {
        assertFalse(pages.isEmpty(), "no page was shown");
        return pages.get(pages.size() - 1);
    }

    /** Clicks a button on the last page and returns the page that follows. */
    Ui.Page click(String label, Object... answers) {
        int n = pages.size();
        page().click(label, answers);
        assertTrue(pages.size() > n, "\"" + label + "\" opened no page");
        return page();
    }

    /** Clicks a button that may close the menu (no page follows). */
    void clickAndClose(String label, Object... answers) { page().click(label, answers); }

    /** What the player was told in the chat (and the queue emptied). */
    static String said(PlayerMock p) {
        StringBuilder sb = new StringBuilder();
        String s;
        while ((s = p.nextMessage()) != null) sb.append(s).append("\n");
        return sb.toString();
    }
}
