package cz.lollywood.falloutak;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Part 9's BATTLE MAPS for the world map's encounters (`09` §3.1 C, RULED: *"a world-map encounter pastes an arena by terrain"*; `09d`
 * round 2, Marek 11-23: *"a battle arena per terrain - lets go for it."* · *"it would be possible to have more encounters at once at one
 * battle map. And sight can be turned to hunt, if players want to."*). An arena world ({@link #WORLD}, empty but for the arenas) holds
 * one 48 x 48 arena a terrain of Arag's map (field, road, forest, town ruin), BUILT AGAIN for every fight from a new seed, so no two look
 * the same; the party is moved in, the scene's enemies (every hostile group and every hunted animal of it) stand where the way they met
 * puts them, the fight is the battle's own, and each player goes back to where he was when it ends.
 * <ul>
 *   <li><b>AMBUSH</b> (they were spotted and the party attacks): the party in cover along the south edge, the enemies in the open in the
 *   north half; THE PARTY ACTS FIRST (02c §7: the attacker acts first).</li>
 *   <li><b>HUNT</b> (a sight turned into a hunt): placed as an ambush; an animal that is not hostile runs from the party and is gone once
 *   far enough.</li>
 *   <li><b>AMBUSHED</b> (not spotted): the party in the open in the middle, the enemies round it 9-14 blocks away; THEY ACT FIRST.</li>
 * </ul>
 * Each player fights with HIS OWN GEAR (09d round 3; {@link Battle#ownKit}), and a death in it is real. [The arenas' looks, sizes and
 * spots mine, EXAMPLE; Arag's builders can replace them.]
 */
final class Arena {
    private Arena() {}

    static final String WORLD = "fo_arena";
    static final int SIZE = 48, Y = 64, H = 14, MARGIN = 6, SLOT = 200;
    /** The size of the arena being built (48 for up to three groups, 64 for five, 80 for more: 09d round 3, *"if it is big enough,
     *  more could get there"*) [the steps mine]. */
    static int size = SIZE;
    static final String[] TERRAINS = {"field", "road", "forest", "town"};
    /** AMBUSH and HUNT: the party first, in cover; AMBUSHED: they first, round the party; PARLEY: a talk that failed, placed as an
     *  ambush (they were seen first) but they attack first. */
    enum Mode { AMBUSH, HUNT, AMBUSHED, PARLEY }

    static int sizeFor(int groups) { return groups <= 3 ? 48 : groups <= 5 ? 64 : 80; }

    record Spot(int x, int y, int z) { Location at(World w) { return new Location(w, x + 0.5, y, z + 0.5); } }

    /** A built arena: its corner, the party's spots (in cover, in the open) and the enemies' (far, on the ring round the middle). */
    record Layout(String terrain, int x0, int z0, int size, List<Spot> cover, List<Spot> open, List<Spot> far, List<Spot> ring) {
        Location centre(World w) { return new Location(w, x0 + size / 2.0, Y, z0 + size / 2.0); }
        org.bukkit.util.BoundingBox box() { return new org.bukkit.util.BoundingBox(x0, Y - 1, z0, x0 + size, Y + H, z0 + size); }
    }

    /** One enemy's statblock (`data/arena-foes.json`). */
    record Foe(String key, String name, String mob, String creature, double scale, int hp, int ac, int dt, double dr, int[] a, int skill,
               int minDmg, int maxDmg, int reach, int attackAp, String attack, boolean gun, String nemesis, double meatKg, int hides) {}

    static final Map<String, Foe> FOES = loadFoes();

    private static Map<String, Foe> loadFoes() {
        Map<String, Foe> m = new LinkedHashMap<>();
        try (InputStream in = Arena.class.getResourceAsStream("/data/arena-foes.json")) {
            if (in == null) return m;
            for (JsonElement e : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("rows")) {
                JsonObject o = e.getAsJsonObject();
                JsonArray aa = o.getAsJsonArray("a");
                int[] a = new int[7];
                for (int k = 0; k < 7; k++) a[k] = aa.get(k).getAsInt();
                JsonArray d = o.has("dmg") ? o.getAsJsonArray("dmg") : null;
                m.put(o.get("key").getAsString(), new Foe(o.get("key").getAsString(), o.get("name").getAsString(),
                        o.has("mob") ? o.get("mob").getAsString() : "PILLAGER", o.has("creature") ? o.get("creature").getAsString() : "",
                        o.has("scale") ? o.get("scale").getAsDouble() : 1, o.has("hp") ? o.get("hp").getAsInt() : 0, o.get("ac").getAsInt(),
                        o.get("dt").getAsInt(), o.get("dr").getAsDouble(), a, o.get("skill").getAsInt(), d == null ? 5 : d.get(0).getAsInt(),
                        d == null ? 12 : d.get(1).getAsInt(), o.has("reach") ? o.get("reach").getAsInt() : 1,
                        o.has("attack_ap") ? o.get("attack_ap").getAsInt() : 4, o.has("attack") ? o.get("attack").getAsString() : "",
                        o.has("gun") && o.get("gun").getAsBoolean(), o.has("nemesis") ? o.get("nemesis").getAsString() : "",
                        o.has("meat_kg") ? o.get("meat_kg").getAsDouble() : 0, o.has("hides") ? o.get("hides").getAsInt() : 0));
            }
        } catch (Exception e) { throw new IllegalStateException("data/arena-foes.json: " + e.getMessage(), e); }
        return m;
    }

    /** The statblock of a world map kind: raiders:* and bands = the raider, patrol:* the guard, traders the caravan's, creature:x its own. */
    static Foe foe(String kindKey) {
        if (kindKey.startsWith("creature:")) { Foe f = FOES.get(kindKey.substring(9)); if (f != null) return f; }
        if (kindKey.startsWith("patrol:")) return FOES.get("patrol");
        if (kindKey.equals("traders")) return FOES.get("trader");
        return FOES.get("raider");
    }

    // ------------------------------------------------------------------ the world
    /** An empty world: nothing is generated, the arenas are built into it. */
    static final class Empty extends ChunkGenerator {
        @Override public boolean shouldGenerateNoise() { return false; }
        @Override public boolean shouldGenerateSurface() { return false; }
        @Override public boolean shouldGenerateCaves() { return false; }
        @Override public boolean shouldGenerateDecorations() { return false; }
        @Override public boolean shouldGenerateMobs() { return false; }
        @Override public boolean shouldGenerateStructures() { return false; }
    }

    /** The arena world, made on first use (the test server: its first world). */
    /** The tests' world (the mock server's flat world answers isPassable, its made worlds do not). */
    static World use;

    static World world() {
        if (use != null) return use;
        World w = Bukkit.getWorld(WORLD);
        if (w == null) w = Battle.orElse(() -> new WorldCreator(WORLD).generator(new Empty()).createWorld(), () -> null);
        if (w == null) w = Bukkit.getWorlds().get(0);
        World ww = w;
        Battle.cosmetic(() -> ww.setGameRule(org.bukkit.GameRules.RANDOM_TICK_SPEED, 0));   // leaves and crops stay as built
        Battle.cosmetic(() -> ww.setGameRule(org.bukkit.GameRules.SPAWN_MOBS, false));
        Battle.cosmetic(() -> ww.setGameRule(org.bukkit.GameRules.ADVANCE_WEATHER, false));
        return w;
    }

    static int slot(String terrain) { for (int k = 0; k < TERRAINS.length; k++) if (TERRAINS[k].equals(terrain)) return k; return 0; }

    // ------------------------------------------------------------------ building
    /** Builds the terrain's arena at its slot from {@code seed}: the ground, the things on it, and the spots. */
    static Layout build(World w, String terrain, long seed) { return build(w, terrain, seed, SIZE); }

    static Layout build(World w, String terrain, long seed, int sz) {
        if (slot(terrain) == 0 && !terrain.equals("field")) terrain = "field";
        size = sz;
        int x0 = slot(terrain) * SLOT, z0 = 0;
        Random r = new Random(seed);
        // clear: the air above, the ground below, a barrier round it (nobody walks off the map)
        for (int x = x0 - MARGIN; x < x0 + size + MARGIN; x++)
            for (int z = z0 - MARGIN; z < z0 + size + MARGIN; z++) {
                boolean edge = x == x0 - MARGIN || z == z0 - MARGIN || x == x0 + size + MARGIN - 1 || z == z0 + size + MARGIN - 1;
                for (int y = Y - 4; y < Y + H; y++) {
                    Material m = y < Y - 1 ? (y == Y - 4 ? Material.BEDROCK : Material.DIRT) : y == Y - 1 ? ground(terrain, r, x - x0, z - z0) : Material.AIR;
                    if (edge && y >= Y && y < Y + 4) m = Material.BARRIER;
                    set(w, x, y, z, m);
                }
            }
        for (Entity e : w.getEntities()) {   // what the last fight left (dropped items, figures)
            Location l = e.getLocation();
            if (!(e instanceof Player) && l.getX() >= x0 - MARGIN && l.getX() < x0 + size + MARGIN && l.getZ() >= z0 - MARGIN && l.getZ() < z0 + size + MARGIN) e.remove();
        }
        switch (terrain) {
            case "road" -> road(w, r, x0, z0);
            case "forest" -> forest(w, r, x0, z0);
            case "town" -> town(w, r, x0, z0);
            default -> field(w, r, x0, z0);
        }
        return spots(w, terrain, x0, z0, r);
    }

    private static Material ground(String terrain, Random r, int x, int z) {
        double u = r.nextDouble();
        return switch (terrain) {
            case "forest" -> u < 0.25 ? Material.PODZOL : u < 0.32 ? Material.MOSS_BLOCK : u < 0.36 ? Material.COARSE_DIRT : Material.GRASS_BLOCK;
            case "town" -> u < 0.45 ? Material.GRAY_CONCRETE : u < 0.6 ? Material.ANDESITE : u < 0.72 ? Material.GRAVEL : u < 0.82 ? Material.STONE
                    : u < 0.9 ? Material.COBBLESTONE : Material.COARSE_DIRT;
            case "road" -> u < 0.08 ? Material.COARSE_DIRT : Material.GRASS_BLOCK;
            default -> u < 0.06 ? Material.COARSE_DIRT : Material.GRASS_BLOCK;
        };
    }

    private static void set(World w, int x, int y, int z, Material m) {
        Block b = w.getBlockAt(x, y, z);
        if (b.getType() != m) b.setType(m, false);
    }

    private static void setLog(World w, int x, int y, int z, Material m, Axis axis) {
        Block b = w.getBlockAt(x, y, z);
        b.setType(m, false);
        Battle.cosmetic(() -> { BlockData d = b.getBlockData(); if (d instanceof Orientable o) { o.setAxis(axis); b.setBlockData(o, false); } });
    }

    private static boolean freeAt(World w, int x, int y, int z) { return w.getBlockAt(x, y, z).getType() == Material.AIR; }

    /** A tree: a trunk of {@code h} logs and a crown of leaves (the world's random ticks are off, so they never decay). */
    private static void tree(World w, Random r, int x, int z, Material log, Material leaves, int h) {
        for (int y = Y; y < Y + h; y++) setLog(w, x, y, z, log, Axis.Y);
        int top = Y + h - 1, rad = log == Material.SPRUCE_LOG ? 2 : 2;
        for (int dy = -2; dy <= 1; dy++) {
            int rr = log == Material.SPRUCE_LOG ? Math.max(0, rad - (dy + 2) / 2) : (dy == 1 ? 1 : rad);
            for (int dx = -rr; dx <= rr; dx++)
                for (int dz = -rr; dz <= rr; dz++) {
                    if (Math.abs(dx) == rr && Math.abs(dz) == rr && r.nextBoolean()) continue;
                    int y = top + dy;
                    if (y < Y + 1 || y >= Y + H) continue;
                    if (freeAt(w, x + dx, y, z + dz)) set(w, x + dx, y, z + dz, leaves);
                }
        }
        if (freeAt(w, x, top + 1, z)) set(w, x, top + 1, z, leaves);
    }

    /** A car wreck: a body 2 x 4, a cabin on its middle half, burned and rusted [after `_tools/mc_cars.py`'s shapes]. */
    private static void car(World w, Random r, int x, int z, boolean alongX) {
        Material[] paint = {Material.RED_CONCRETE, Material.BLUE_CONCRETE, Material.WHITE_CONCRETE, Material.LIGHT_GRAY_CONCRETE,
                Material.BLACK_CONCRETE, Material.GREEN_TERRACOTTA, Material.BROWN_TERRACOTTA};
        Material body = paint[r.nextInt(paint.length)];
        int lx = alongX ? 4 : 2, lz = alongX ? 2 : 4;
        for (int dx = 0; dx < lx; dx++)
            for (int dz = 0; dz < lz; dz++) {
                set(w, x + dx, Y, z + dz, r.nextDouble() < 0.2 ? Material.ORANGE_TERRACOTTA : body);
                boolean cabin = alongX ? dx == 1 || dx == 2 : dz == 1 || dz == 2;
                if (cabin && r.nextDouble() < 0.85) set(w, x + dx, Y + 1, z + dz, r.nextDouble() < 0.5 ? Material.TINTED_GLASS : body);
            }
    }

    private static void rubble(World w, Random r, int x, int z) {
        Material[] mm = {Material.GRAVEL, Material.COBBLESTONE, Material.ANDESITE, Material.STONE_BRICKS, Material.BRICKS};
        int n = 2 + r.nextInt(4);
        for (int k = 0; k < n; k++) {
            int dx = r.nextInt(3) - 1, dz = r.nextInt(3) - 1;
            int y = Y;
            while (y < Y + 2 && !freeAt(w, x + dx, y, z + dz)) y++;
            if (y < Y + 2) set(w, x + dx, y, z + dz, mm[r.nextInt(mm.length)]);
        }
    }

    private static void field(World w, Random r, int x0, int z0) {
        // a crop strip (wheat on farmland) across the north, hay, bushes, a fence line, a ruined shed
        int cz = z0 + 30 + r.nextInt(6);
        for (int x = x0 + 4; x < x0 + size - 4; x++)
            for (int z = cz; z < cz + 6; z++) if ((x + z) % 7 != 0) { set(w, x, Y - 1, z, Material.FARMLAND); set(w, x, Y, z, Material.WHEAT); }
        for (int k = 0; k < 7; k++) {
            int x = x0 + 3 + r.nextInt(size - 6), z = z0 + 8 + r.nextInt(size - 12);
            set(w, x, Y, z, Material.HAY_BLOCK);
            if (r.nextBoolean()) set(w, x + 1, Y, z, Material.HAY_BLOCK);
            if (r.nextDouble() < 0.4) set(w, x, Y + 1, z, Material.HAY_BLOCK);
        }
        for (int k = 0; k < 10; k++) {
            int x = x0 + 2 + r.nextInt(size - 4), z = z0 + 2 + r.nextInt(size - 4);
            set(w, x, Y, z, Material.OAK_LEAVES);
            if (r.nextBoolean()) set(w, x, Y, z + 1, Material.OAK_LEAVES);
        }
        int fz = z0 + 10 + r.nextInt(4);
        for (int x = x0 + 2; x < x0 + size - 2; x++) if (r.nextDouble() < 0.8) set(w, x, Y, fz, Material.OAK_FENCE);
        int sx = x0 + 6 + r.nextInt(size - 18), sz = z0 + 18 + r.nextInt(8);
        for (int x = sx; x < sx + 6; x++)
            for (int z = sz; z < sz + 5; z++) {
                boolean wall = x == sx || x == sx + 5 || z == sz || z == sz + 4;
                if (!wall) continue;
                int h = 1 + r.nextInt(3);
                for (int y = Y; y < Y + h; y++) if (r.nextDouble() < 0.85) set(w, x, y, z, Material.COBBLESTONE);
            }
        for (int k = 0; k < 140; k++) {
            int x = x0 + r.nextInt(size), z = z0 + r.nextInt(size);
            if (freeAt(w, x, Y, z) && w.getBlockAt(x, Y - 1, z).getType() == Material.GRASS_BLOCK) set(w, x, Y, z, r.nextDouble() < 0.7 ? Material.SHORT_GRASS : Material.TALL_GRASS);
        }
    }

    private static void road(World w, Random r, int x0, int z0) {
        // the road east to west through the middle, its lane marks, potholes, wrecks; a ditch and trees beside it
        int rz = z0 + size / 2 - 3;
        for (int x = x0 - MARGIN + 1; x < x0 + size + MARGIN - 1; x++)
            for (int z = rz; z < rz + 7; z++) {
                double u = r.nextDouble();
                Material m = u < 0.12 ? Material.BLACK_CONCRETE : u < 0.2 ? Material.GRAVEL : u < 0.24 ? Material.COBBLESTONE : Material.GRAY_CONCRETE;
                if (z == rz + 3 && (x / 3) % 2 == 0 && u > 0.2) m = Material.WHITE_CONCRETE;
                set(w, x, Y - 1, z, m);
            }
        for (int x = x0 + 2; x < x0 + size - 2; x++) { set(w, x, Y - 1, rz - 2, Material.MUD); set(w, x, Y - 1, rz + 8, Material.MUD); }
        int cars = 3 + r.nextInt(3);
        for (int k = 0; k < cars; k++) car(w, r, x0 + 3 + r.nextInt(size - 8), rz + (r.nextBoolean() ? 0 : 4), r.nextDouble() < 0.75);
        for (int k = 0; k < 9; k++) {
            int x = x0 + 2 + r.nextInt(size - 4), z = r.nextBoolean() ? z0 + 4 + r.nextInt(rz - z0 - 8) : rz + 11 + r.nextInt(size - (rz - z0) - 14);
            if (freeAt(w, x, Y, z)) tree(w, r, x, z, Material.OAK_LOG, Material.OAK_LEAVES, 4 + r.nextInt(3));
        }
        for (int k = 0; k < 6; k++) { int x = x0 + 2 + r.nextInt(size - 4), z = z0 + 6 + r.nextInt(size - 12); if (freeAt(w, x, Y, z)) set(w, x, Y, z, Material.OAK_LEAVES); }
        // a bus stop's ruin and a fallen sign
        int bx = x0 + 8 + r.nextInt(size - 16);
        for (int x = bx; x < bx + 4; x++) set(w, x, Y, rz - 4, Material.STONE_BRICK_WALL);
        set(w, bx, Y + 1, rz - 4, Material.STONE_BRICK_WALL);
        for (int k = 0; k < 80; k++) {
            int x = x0 + r.nextInt(size), z = z0 + r.nextInt(size);
            if (freeAt(w, x, Y, z) && w.getBlockAt(x, Y - 1, z).getType() == Material.GRASS_BLOCK) set(w, x, Y, z, Material.SHORT_GRASS);
        }
    }

    private static void forest(World w, Random r, int x0, int z0) {
        Material[][] kinds = {{Material.SPRUCE_LOG, Material.SPRUCE_LEAVES}, {Material.OAK_LOG, Material.OAK_LEAVES}, {Material.BIRCH_LOG, Material.BIRCH_LEAVES}};
        int trees = 34 + r.nextInt(10);
        for (int k = 0; k < trees; k++) {
            int x = x0 + 1 + r.nextInt(size - 2), z = z0 + 1 + r.nextInt(size - 2);
            if (!freeAt(w, x, Y, z) || !freeAt(w, x + 1, Y, z) || !freeAt(w, x - 1, Y, z)) continue;
            Material[] kk = kinds[r.nextDouble() < 0.55 ? 0 : r.nextBoolean() ? 1 : 2];
            tree(w, r, x, z, kk[0], kk[1], 5 + r.nextInt(4));
        }
        for (int k = 0; k < 5; k++) {   // fallen trunks
            int x = x0 + 3 + r.nextInt(size - 10), z = z0 + 3 + r.nextInt(size - 6);
            boolean ax = r.nextBoolean();
            int len = 3 + r.nextInt(4);
            for (int i = 0; i < len; i++) if (freeAt(w, x + (ax ? i : 0), Y, z + (ax ? 0 : i))) setLog(w, x + (ax ? i : 0), Y, z + (ax ? 0 : i), Material.SPRUCE_LOG, ax ? Axis.X : Axis.Z);
        }
        for (int k = 0; k < 8; k++) {   // rocks
            int x = x0 + 2 + r.nextInt(size - 4), z = z0 + 2 + r.nextInt(size - 4);
            if (freeAt(w, x, Y, z)) set(w, x, Y, z, r.nextBoolean() ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE);
            if (r.nextBoolean() && freeAt(w, x + 1, Y, z)) set(w, x + 1, Y, z, Material.MOSSY_COBBLESTONE);
        }
        for (int k = 0; k < 120; k++) {
            int x = x0 + r.nextInt(size), z = z0 + r.nextInt(size);
            Material g = w.getBlockAt(x, Y - 1, z).getType();
            if (freeAt(w, x, Y, z) && (g == Material.GRASS_BLOCK || g == Material.PODZOL || g == Material.MOSS_BLOCK))
                set(w, x, Y, z, r.nextDouble() < 0.5 ? Material.FERN : Material.SHORT_GRASS);
        }
    }

    private static void town(World w, Random r, int x0, int z0) {
        // ruined houses (walls broken to random heights, window and door gaps), rubble, wrecks, a street through the middle
        int sz = z0 + size / 2 - 2;
        for (int x = x0 - MARGIN + 1; x < x0 + size + MARGIN - 1; x++) for (int z = sz; z < sz + 5; z++) set(w, x, Y - 1, z, r.nextDouble() < 0.15 ? Material.BLACK_CONCRETE : Material.GRAY_CONCRETE);
        Material[] walls = {Material.BRICKS, Material.STONE_BRICKS, Material.CRACKED_STONE_BRICKS, Material.WHITE_CONCRETE, Material.LIGHT_GRAY_CONCRETE};
        int houses = 0;
        for (int tries = 0; tries < 40 && houses < 6; tries++) {
            int wx = 6 + r.nextInt(5), wz = 5 + r.nextInt(4);
            int hx = x0 + 2 + r.nextInt(size - wx - 4), hz = r.nextBoolean() ? z0 + 3 + r.nextInt(Math.max(1, sz - z0 - wz - 5)) : sz + 7 + r.nextInt(Math.max(1, size - (sz - z0) - wz - 9));
            boolean clash = false;
            for (int x = hx - 1; x <= hx + wx && !clash; x++) for (int z = hz - 1; z <= hz + wz && !clash; z++) if (!freeAt(w, x, Y, z)) clash = true;
            if (clash) continue;
            houses++;
            Material m = walls[r.nextInt(walls.length)];
            int full = 2 + r.nextInt(4);
            int door = hx + 1 + r.nextInt(wx - 2);
            for (int x = hx; x < hx + wx; x++)
                for (int z = hz; z < hz + wz; z++) {
                    boolean wall = x == hx || x == hx + wx - 1 || z == hz || z == hz + wz - 1;
                    if (!wall) { if (r.nextDouble() < 0.12) rubble(w, r, x, z); continue; }
                    if ((z == hz || z == hz + wz - 1) && x == door) continue;   // the doorway
                    int h = Math.max(0, full - r.nextInt(3));
                    for (int y = Y; y < Y + h; y++) {
                        boolean window = y == Y + 1 && (x + z) % 3 == 0 && r.nextDouble() < 0.6;
                        if (!window) set(w, x, y, z, r.nextDouble() < 0.1 ? Material.COBBLESTONE : m);
                    }
                }
        }
        for (int k = 0; k < 10; k++) rubble(w, r, x0 + 2 + r.nextInt(size - 4), z0 + 2 + r.nextInt(size - 4));
        for (int k = 0; k < 2 + r.nextInt(2); k++) car(w, r, x0 + 3 + r.nextInt(size - 8), sz + r.nextInt(2), true);
        for (int k = 0; k < 4; k++) { int x = x0 + 4 + r.nextInt(size - 8); if (freeAt(w, x, Y, sz - 1)) { set(w, x, Y, sz - 1, Material.ANDESITE_WALL); if (r.nextBoolean()) set(w, x, Y + 1, sz - 1, Material.IRON_BARS); } }
        for (int k = 0; k < 30; k++) { int x = x0 + r.nextInt(size), z = z0 + r.nextInt(size); if (freeAt(w, x, Y, z) && r.nextBoolean()) set(w, x, Y, z, Material.DEAD_BUSH); }
    }

    // ------------------------------------------------------------------ the spots
    /** A block a body can stand in: free at the feet and the head, solid under. */
    static boolean standing(World w, int x, int y, int z) {
        return w.getBlockAt(x, y, z).isPassable() && w.getBlockAt(x, y + 1, z).isPassable() && w.getBlockAt(x, y - 1, z).getType().isSolid();
    }

    private static Layout spots(World w, String terrain, int x0, int z0, Random r) {
        List<Spot> cover = new ArrayList<>(), open = new ArrayList<>(), far = new ArrayList<>(), ring = new ArrayList<>();
        double cx = x0 + size / 2.0, cz = z0 + size / 2.0;
        for (int x = x0 + 2; x < x0 + size - 2; x++)
            for (int z = z0 + 2; z < z0 + size - 2; z++) {
                int y = Y;
                if (!standing(w, x, y, z)) continue;
                Spot s = new Spot(x, y, z);
                int dz = z - z0;
                boolean shield = false;   // something solid just north (toward the enemies)
                for (int dx = -1; dx <= 1; dx++) if (w.getBlockAt(x + dx, y, z + 1).getType().isSolid() || w.getBlockAt(x + dx, y, z + 2).getType().isSolid()) shield = true;
                if (dz >= 3 && dz <= 12 && shield) cover.add(s);
                double d = Math.hypot(x + 0.5 - cx, z + 0.5 - cz);
                if (d <= 4.5) open.add(s);
                if (dz >= Math.round(size * 0.58) && dz <= size - 6) far.add(s);
                if (d >= 9 && d <= Math.max(14, size / 4.0)) ring.add(s);
            }
        // when the cover is thin, the south strip's open spots do
        if (cover.size() < 8) for (int x = x0 + 4; x < x0 + size - 4 && cover.size() < 12; x += 2) if (standing(w, x, Y, z0 + 6)) cover.add(new Spot(x, Y, z0 + 6));
        return new Layout(terrain, x0, z0, size, spread(cover, r), spread(open, r), spread(far, r), spread(ring, r));
    }

    /** The spots shuffled, then kept two blocks apart (nobody stands next to another). */
    private static List<Spot> spread(List<Spot> l, Random r) {
        List<Spot> in = new ArrayList<>(l);
        java.util.Collections.shuffle(in, r);
        List<Spot> out = new ArrayList<>();
        for (Spot s : in) {
            boolean near = false;
            for (Spot o : out) if (Math.abs(o.x() - s.x()) <= 1 && Math.abs(o.z() - s.z()) <= 1) { near = true; break; }
            if (!near) out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------------ the fight
    /** An encounter in the fight: what, how many, and whether it runs (a hunted animal that is not hostile). */
    record Group(WorldMap.Kind kind, int count, boolean flees) {}

    /** The fight on a battle map now (one at a time), and where each player was. */
    static Battle.Fight current;
    static final Map<UUID, Location> back = new HashMap<>();
    static Runnable afterAll = () -> { };

    /**
     * Moves the party into the terrain's arena, sets the groups where {@code mode} puts them and starts the fight. {@code done} runs when
     * the last player has left it. Returns what happened, or a refusal ("!").
     */
    static String fight(FalloutakPlugin pl, List<Player> party, String terrain, List<Group> groups, Mode mode, long seed, Runnable done) {
        if (current != null && !current.over && !pl.battle.players(current).isEmpty()) return "! another fight is on a battle map";
        for (Player q : party) if (pl.battle.inBattle(q)) pl.battle.stop(q);
        World w = world();
        Layout lay = build(w, terrain, seed, sizeFor(groups.size()));
        Battle.Fight f = new Battle.Fight("the enemies");
        f.demo = false;
        f.enemiesFirst = mode == Mode.AMBUSHED || mode == Mode.PARLEY;
        f.field = lay.box();
        current = f;
        afterAll = done;
        // the enemies: an ambush or a hunt finds them in the open in the north half, an ambush on the party round it
        List<Spot> theirs = mode == Mode.AMBUSHED ? lay.ring() : lay.far();
        theirs = new ArrayList<>(theirs);
        theirs.sort(Comparator.comparingInt(Spot::x));   // a group stands together, left to right
        Random r = new Random(seed ^ 0x5DEECE66DL);
        int k = 0, total = 0, gi = 0;
        for (Group g : groups) total += g.count();
        int stride = Math.max(1, theirs.size() / Math.max(1, total));
        for (Group g : groups) {
            gi++;
            Foe foe = foe(g.kind().key());
            for (int n = 0; n < g.count(); n++) {
                if (theirs.isEmpty()) break;
                Spot sp = theirs.get(Math.min(theirs.size() - 1, (k++ * stride) % theirs.size()));
                Location at = sp.at(w);
                at.setDirection(lay.centre(w).toVector().subtract(at.toVector()).setY(0));
                String name = (g.count() > 1 ? foe.name() + " " + (n + 1) : foe.name());
                LivingEntity e = spawn(w, foe, at, name);
                Battle.Target t = new Battle.Target(name, foe.hp() > 0 ? foe.hp() : (int) Math.round(g.kind().hp()), foe.ac(), foe.dt(), foe.dr(), foe.a(), foe.skill());
                t.melee = !foe.gun();
                t.flees = g.flees();
                t.minDmg = foe.minDmg(); t.maxDmg = foe.maxDmg(); t.reach = foe.reach(); t.attackAp = foe.attackAp(); t.attack = foe.attack();
                t.field = lay.box();
                t.group = gi;
                t.peaceable = foe.key().equals("patrol") || foe.key().equals("trader");
                t.byParty = false;
                t.meatKg = foe.meatKg();
                t.hides = foe.hides();
                if (!foe.nemesis().isEmpty()) t.kind = foe.nemesis();
                if (foe.gun()) t.gunHealth = Math.max(1, Battle.SMG.maxHealth * (0.25 + 0.35 * r.nextDouble()));   // used guns (05 §9, round 11)
                f.dummies.add(e.getUniqueId());
                f.targets.put(e.getUniqueId(), t);
                pl.battle.label(e, t);
            }
        }
        // the party
        List<Spot> ours = mode == Mode.AMBUSHED ? lay.open() : lay.cover();   // a failed talk: they were seen first, the party kept its cover
        if (ours.isEmpty()) ours = lay.open();
        back.clear();
        StringBuilder who = new StringBuilder();
        for (Group g : groups) who.append(who.length() == 0 ? "" : ", ").append(g.count()).append(" × ").append(foe(g.kind().key()).name()).append(g.flees() ? " (hunted)" : "");
        String intro = switch (mode) {
            case AMBUSH -> "AMBUSH on the " + terrain + " map: you lie in cover, they have not seen you (" + who + "). Round 1: YOUR TURN, all of you at once.";
            case HUNT -> "THE HUNT on the " + terrain + " map (" + who + "): you are downwind. Round 1: YOUR TURN; what is not hostile will run.";
            case AMBUSHED -> "AMBUSHED on the " + terrain + " map (" + who + "): they are all round you and act first.";
            case PARLEY -> "THE TALK FAILED on the " + terrain + " map (" + who + "): they go for their guns first; you still have your cover.";
        };
        int i = 0;
        for (Player q : party) {
            back.put(q.getUniqueId(), q.getLocation().clone());
            Spot sp = ours.get(Math.min(ours.size() - 1, i++));
            Location at = sp.at(w);
            at.setDirection(lay.centre(w).toVector().subtract(at.toVector()).setY(0));
            if (mode == Mode.AMBUSHED) at.setDirection(theirs.isEmpty() ? at.getDirection() : theirs.get(0).at(w).toVector().subtract(at.toVector()).setY(0));
            q.teleport(at);
            Character c = pl.character(q);
            pl.battle.join(q, c, f, true);   // his own gear (09d round 3), and a death in it is real
            q.sendMessage(Component.text(intro + " Left click AT AN ENEMY = fire, right click = mode, shift = stance, the clock = end your turn; when every one "
                    + "of you has ended his, it is their turn. Walk to the map's edge to get away; the dangers also fight each other.", NamedTextColor.AQUA));
        }
        f.onLeave = q -> { Location l = back.remove(q.getUniqueId()); if (l != null) q.teleport(l); };
        f.onEnd = () -> { current = null; Runnable a = afterAll; afterAll = () -> { }; a.run(); };
        f.started = true;
        if (f.enemiesFirst) pl.battle.enemyPhase(f, () -> pl.battle.partyPhase(f));
        else for (Player q : party) { Battle.State s = pl.battle.state(q); if (s != null) pl.battle.show(q, s, pl.character(q), "round 1: your turn"); }
        return intro;
    }

    /** An item in stacks of at most 64. */
    static List<org.bukkit.inventory.ItemStack> stacks(String id, int n) {
        List<org.bukkit.inventory.ItemStack> l = new ArrayList<>();
        while (n > 0) { int k = Math.min(64, n); l.add(Items.make(id, k)); n -= k; }
        return l;
    }

    /** "12 × Raw meat, 1 × Raw hide". */
    static String spoilsText(List<org.bukkit.inventory.ItemStack> l) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (org.bukkit.inventory.ItemStack it : l) {
            String id = Items.id(it);
            String[] t = id == null ? null : Items.thing(id);
            m.merge(t != null ? t[3] : it.getType().name().toLowerCase(java.util.Locale.ROOT), it.getAmount(), Integer::sum);
        }
        List<String> out = new ArrayList<>();
        m.forEach((k, v) -> out.add(v + " × " + k));
        return String.join(", ", out);
    }

    /** An enemy's figure: our own model (the Radmie, the Trhač) or the Minecraft stand-in, still, silent, its size by the statblock. */
    static LivingEntity spawn(World w, Foe foe, Location at, String name) {
        LivingEntity e;
        Creatures.Kind ck = foe.creature().isEmpty() ? null : Creatures.KINDS.get(foe.creature());
        if (ck != null) e = Creatures.spawn(ck, at);
        else {
            EntityType type;
            try { type = EntityType.valueOf(foe.mob()); } catch (IllegalArgumentException ex) { type = EntityType.PILLAGER; }
            EntityType tt = type;
            Entity en = Battle.orElse(() -> w.spawnEntity(at, tt), () -> w.spawnEntity(at, EntityType.PILLAGER));
            e = en instanceof LivingEntity le0 ? le0 : (LivingEntity) w.spawnEntity(at, EntityType.PILLAGER);
            double sc = foe.scale();
            LivingEntity le = e;
            Battle.cosmetic(() -> { var a = le.getAttribute(Attribute.SCALE); if (a != null) a.setBaseValue(sc); });
        }
        LivingEntity le = e;
        if (le instanceof Mob m) { Battle.cosmetic(() -> m.setAI(false)); Battle.cosmetic(() -> m.setPersistent(false)); }
        Battle.cosmetic(() -> le.setSilent(true));
        Battle.cosmetic(() -> le.setInvulnerable(true));
        Battle.cosmetic(() -> le.setRemoveWhenFarAway(false));
        Battle.cosmetic(() -> le.getEquipment().clear());
        Battle.cosmetic(() -> le.setCanPickupItems(false));
        le.customName(Component.text(name, NamedTextColor.RED));
        le.setCustomNameVisible(true);
        return le;
    }
}
