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
 * one arena a terrain of Arag's map (field, road, forest, town ruin), BUILT AGAIN for every fight from a new seed, so no two look
 * the same, ALWAYS 128 x 128 (09d round 5, Marek 11-24: *"instead of making several battle maps of different sizes, I think it would be
 * better to use only the 128 sized ones (or bigger) and in case of smaller encounter, place the players near the border. If the border
 * will be visible, it should be fine."*; 160 for six groups or more, {@link #sizeFor}): the party stands {@link #ESCAPE} blocks from the
 * edge behind it (round 4: *"You need 30 to run away"*), the enemies at a distance by the longest reach in the fight; the map's edge is
 * shown to each player as a world border. A fight on or near a UNIQUE LOCATION MAP (Jihlava from OpenStreetMap) happens on that map
 * itself instead, the same 128 blocks cut out of it round the place (round 5: *"if it is near/on some unique location map, it should
 * instead happen on the full unique map"*). The party is moved in, the scene's enemies (every hostile group and every hunted animal of
 * it) stand where the way they met puts them, the fight is the battle's own, and each player goes back to where he was when it ends.
 * <ul>
 *   <li><b>AMBUSH</b> (they were spotted and the party attacks): the party in cover {@link #ESCAPE} blocks from the south edge, the
 *   enemies in the open to the north at the start distance; THE PARTY ACTS FIRST (02c §7: the attacker acts first).</li>
 *   <li><b>TALK</b> (09d round 4): placed as an ambush, the talk goes on for {@link Battle#TALK_ROUNDS} rounds while the party moves
 *   into position ({@link Battle.Talk}).</li>
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
    static final int SIZE = 128, Y = 64, H = 14, MARGIN = 6, SLOT = 200, ESCAPE = 30, MAX_SIZE = 160;
    /** The arena being built: its side, the party's line (blocks from the south edge) and the enemies' start distance from it. */
    static int size = SIZE, coverZ = ESCAPE, dist = 20;
    static final String[] TERRAINS = {"field", "road", "forest", "town"};
    /** AMBUSH and HUNT: the party first, in cover; AMBUSHED: they first, round the party; TALK: placed as an ambush, the talk first;
     *  PARLEY: a peaceful meeting gone hostile, placed as an ambush but they attack first. */
    enum Mode { AMBUSH, HUNT, AMBUSHED, TALK, PARLEY }

    /** The enemies' start distance from the party's line: 0.8 x the longest reach in the fight, 12 to 40 blocks [mine, EXAMPLE]. */
    static int startDistance(int reach) { return Math.max(12, Math.min(40, (int) Math.round(reach * 0.8))); }

    /**
     * The map's side (09d round 5): always {@link #SIZE}, the party near the edge behind it whatever the encounter's size; six groups or
     * more {@link #MAX_SIZE} [the 160 mine, EXAMPLE].
     */
    static int sizeFor(int groups, int reach) { return groups > 5 ? MAX_SIZE : SIZE; }

    /** The longest reach in the fight: the party's guns and bows (their own gear) and the enemies' (a gunner's pistol, a creature's bite). */
    static int reach(List<Player> party, List<Group> groups) {
        int r = 0;
        for (Player q : party)
            for (org.bukkit.inventory.ItemStack it : q.getInventory().getContents()) {
                Production.Design d = Items.made(Items.id(it));
                Battle.Weapon w = d == null ? null : Battle.GEAR.get(d.name);
                if (w != null && !w.melee()) r = Math.max(r, w.range);
            }
        for (Group g : groups) { Foe f = foe(g.kind().key()); if (f != null) r = Math.max(r, f.gun() ? Battle.SMG.range : f.reach()); }
        for (Group g : groups) if (raiderKind(g.kind().key())) r = Math.max(r, Math.max(Battle.SMG.range, bowRange()));
        return r;
    }

    private static int bowRange() {
        int r = 0;
        Foe b = FOES.get("raider-bow");
        if (b != null) for (String n : b.weapons()) { Battle.Weapon w = Battle.GEAR.get(n); if (w != null) r = Math.max(r, w.range); }
        return r;
    }

    /** A raider kind of the world map (a group's seat or camp, the small bands): their kit is drawn per figure ({@link #kitFor}). */
    static boolean raiderKind(String key) { return key.equals("bands") || key.startsWith("raiders:"); }

    /**
     * THE RAIDERS' KIT (09d round 5, Marek 11-24: *"we need more enemies with bows and melee weapons"* · *"smaller band are likely to
     * actually use bows and melee weapons more. Primers will be the main issue"*): each figure of a raider group draws its statblock, a
     * pistol, a bow or a melee weapon. A tribe's camps and seats: guns 55 % in year 2, 10 points less each year after (at least 15 %),
     * the rest bows 55 : melee 45; the small bands: guns 15 % (falling 3 points a year, at least 5 %), bows 45 %, melee the rest
     * [the shares mine, EXAMPLE; `09e` §2 the ammunition behind them]. Everyone else his kind's own.
     */
    static Foe kitFor(String kindKey, double years, Random r) {
        if (!raiderKind(kindKey)) return foe(kindKey);
        double gun, bow;
        if (kindKey.equals("bands")) { gun = Math.max(0.05, 0.15 - 0.03 * Math.max(0, years - 2)); bow = 0.45; }
        else { gun = Math.max(0.15, Math.min(0.55, 0.55 - 0.1 * Math.max(0, years - 2))); bow = (1 - gun) * 0.55; }
        double u = r.nextDouble();
        Foe f = FOES.get(u < gun ? "raider" : u < gun + bow ? "raider-bow" : "raider-melee");
        return f != null ? f : FOES.get("raider");
    }

    /** A count of things on a 48-block arena, scaled to this one's area. */
    private static int sc(int n) { return Math.max(1, (int) Math.round(n * size * (double) size / (48.0 * 48.0))); }

    record Spot(int x, int y, int z) { Location at(World w) { return new Location(w, x + 0.5, y, z + 0.5); } }

    /** A built arena: its corner, the party's spots (in cover, in the open) and the enemies' (far, on the ring round the middle). */
    record Layout(String terrain, int x0, int z0, int size, List<Spot> cover, List<Spot> open, List<Spot> far, List<Spot> ring, int y0, int y1) {
        Layout(String terrain, int x0, int z0, int size, List<Spot> cover, List<Spot> open, List<Spot> far, List<Spot> ring) {
            this(terrain, x0, z0, size, cover, open, far, ring, Y - 1, Y + H);
        }
        Location centre(World w) { return new Location(w, x0 + size / 2.0, y0 + 1, z0 + size / 2.0); }
        org.bukkit.util.BoundingBox box() { return new org.bukkit.util.BoundingBox(x0, y0, z0, x0 + size, y1, z0 + size); }
    }

    /** One enemy's statblock (`data/arena-foes.json`). */
    record Foe(String key, String name, String mob, String creature, double scale, int hp, int ac, int dt, double dr, int[] a, int skill,
               int minDmg, int maxDmg, int reach, int attackAp, String attack, boolean gun, String nemesis, double meatKg, int hides,
               List<String> weapons, int reserve, List<Part> parts, boolean venom) {}

    /**
     * A special part of a creature's body (09d round 5, Marek 11-24: *"How much you get depends on skill and higher skill may give you
     * harder to get resources"*): what it is (a named item of a store row), the skill and the level it needs, and whether it is FRAGILE
     * (ruined by a butcher who did not put it first) [mine, EXAMPLE].
     */
    record Part(String name, String row, int tier, int rarity, String skill, int need, boolean fragile, int count) {}

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
                        o.has("meat_kg") ? o.get("meat_kg").getAsDouble() : 0, o.has("hides") ? o.get("hides").getAsInt() : 0,
                        strings(o, "weapons"), o.has("reserve") ? o.get("reserve").getAsInt() : 99, parts(o),
                        o.has("venom") && o.get("venom").getAsBoolean()));
            }
        } catch (Exception e) { throw new IllegalStateException("data/arena-foes.json: " + e.getMessage(), e); }
        return m;
    }

    private static List<String> strings(JsonObject o, String k) {
        List<String> l = new ArrayList<>();
        if (o.has(k)) for (JsonElement e : o.getAsJsonArray(k)) l.add(e.getAsString());
        return l;
    }

    private static List<Part> parts(JsonObject o) {
        List<Part> l = new ArrayList<>();
        if (o.has("parts")) for (JsonElement e : o.getAsJsonArray("parts")) {
            JsonObject q = e.getAsJsonObject();
            l.add(new Part(q.get("name").getAsString(), q.get("row").getAsString(), q.get("tier").getAsInt(), q.get("rarity").getAsInt(),
                    q.get("skill").getAsString(), q.get("need").getAsInt(), q.has("fragile") && q.get("fragile").getAsBoolean(), q.has("count") ? q.get("count").getAsInt() : 1));
        }
        return l;
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

    static Layout build(World w, String terrain, long seed, int sz) { return build(w, terrain, seed, sz, 20); }

    static Layout build(World w, String terrain, long seed, int sz, int d) {
        if (slot(terrain) == 0 && !terrain.equals("field")) terrain = "field";
        size = sz;
        dist = d;
        coverZ = Math.max(8, Math.min(ESCAPE, sz - d - 14));
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
        int cz = Math.min(z0 + size - 8, z0 + coverZ + 8 + r.nextInt(Math.max(1, dist - 10)));
        for (int x = x0 + 4; x < x0 + size - 4; x++)
            for (int z = cz; z < cz + 6; z++) if ((x + z) % 7 != 0) { set(w, x, Y - 1, z, Material.FARMLAND); set(w, x, Y, z, Material.WHEAT); }
        for (int k = 0; k < sc(7); k++) {
            int x = x0 + 3 + r.nextInt(size - 6), z = z0 + 8 + r.nextInt(size - 12);
            set(w, x, Y, z, Material.HAY_BLOCK);
            if (r.nextBoolean()) set(w, x + 1, Y, z, Material.HAY_BLOCK);
            if (r.nextDouble() < 0.4) set(w, x, Y + 1, z, Material.HAY_BLOCK);
        }
        for (int k = 0; k < sc(10); k++) {
            int x = x0 + 2 + r.nextInt(size - 4), z = z0 + 2 + r.nextInt(size - 4);
            set(w, x, Y, z, Material.OAK_LEAVES);
            if (r.nextBoolean()) set(w, x, Y, z + 1, Material.OAK_LEAVES);
        }
        int fz = z0 + coverZ + 2 + r.nextInt(3);
        for (int x = x0 + 2; x < x0 + size - 2; x++) if (r.nextDouble() < 0.8) set(w, x, Y, fz, Material.OAK_FENCE);
        int sx = x0 + 6 + r.nextInt(size - 18), sz = z0 + coverZ + 8 + r.nextInt(Math.max(1, size - coverZ - 16));
        for (int x = sx; x < sx + 6; x++)
            for (int z = sz; z < sz + 5; z++) {
                boolean wall = x == sx || x == sx + 5 || z == sz || z == sz + 4;
                if (!wall) continue;
                int h = 1 + r.nextInt(3);
                for (int y = Y; y < Y + h; y++) if (r.nextDouble() < 0.85) set(w, x, y, z, Material.COBBLESTONE);
            }
        for (int k = 0; k < sc(140); k++) {
            int x = x0 + r.nextInt(size), z = z0 + r.nextInt(size);
            if (freeAt(w, x, Y, z) && w.getBlockAt(x, Y - 1, z).getType() == Material.GRASS_BLOCK) set(w, x, Y, z, r.nextDouble() < 0.7 ? Material.SHORT_GRASS : Material.TALL_GRASS);
        }
    }

    private static void road(World w, Random r, int x0, int z0) {
        // the road east to west between the two lines, its lane marks, potholes, wrecks; a ditch and trees beside it
        int rz = Math.min(z0 + size - 12, z0 + coverZ + Math.max(5, dist / 2) - 3);
        for (int x = x0 - MARGIN + 1; x < x0 + size + MARGIN - 1; x++)
            for (int z = rz; z < rz + 7; z++) {
                double u = r.nextDouble();
                Material m = u < 0.12 ? Material.BLACK_CONCRETE : u < 0.2 ? Material.GRAVEL : u < 0.24 ? Material.COBBLESTONE : Material.GRAY_CONCRETE;
                if (z == rz + 3 && (x / 3) % 2 == 0 && u > 0.2) m = Material.WHITE_CONCRETE;
                set(w, x, Y - 1, z, m);
            }
        for (int x = x0 + 2; x < x0 + size - 2; x++) { set(w, x, Y - 1, rz - 2, Material.MUD); set(w, x, Y - 1, rz + 8, Material.MUD); }
        int cars = sc(3) + r.nextInt(3);
        for (int k = 0; k < cars; k++) car(w, r, x0 + 3 + r.nextInt(size - 8), rz + (r.nextBoolean() ? 0 : 4), r.nextDouble() < 0.75);
        for (int k = 0; k < sc(9); k++) {
            int x = x0 + 2 + r.nextInt(size - 4), z = r.nextBoolean() ? z0 + 4 + r.nextInt(Math.max(1, rz - z0 - 8)) : rz + 11 + r.nextInt(Math.max(1, size - (rz - z0) - 14));
            if (freeAt(w, x, Y, z)) tree(w, r, x, z, Material.OAK_LOG, Material.OAK_LEAVES, 4 + r.nextInt(3));
        }
        for (int k = 0; k < sc(6); k++) { int x = x0 + 2 + r.nextInt(size - 4), z = z0 + 6 + r.nextInt(size - 12); if (freeAt(w, x, Y, z)) set(w, x, Y, z, Material.OAK_LEAVES); }
        // a bus stop's ruin and a fallen sign
        int bx = x0 + 8 + r.nextInt(size - 16);
        for (int x = bx; x < bx + 4; x++) set(w, x, Y, rz - 4, Material.STONE_BRICK_WALL);
        set(w, bx, Y + 1, rz - 4, Material.STONE_BRICK_WALL);
        for (int k = 0; k < sc(80); k++) {
            int x = x0 + r.nextInt(size), z = z0 + r.nextInt(size);
            if (freeAt(w, x, Y, z) && w.getBlockAt(x, Y - 1, z).getType() == Material.GRASS_BLOCK) set(w, x, Y, z, Material.SHORT_GRASS);
        }
    }

    private static void forest(World w, Random r, int x0, int z0) {
        Material[][] kinds = {{Material.SPRUCE_LOG, Material.SPRUCE_LEAVES}, {Material.OAK_LOG, Material.OAK_LEAVES}, {Material.BIRCH_LOG, Material.BIRCH_LEAVES}};
        int trees = sc(34) + r.nextInt(10);
        for (int k = 0; k < trees; k++) {
            int x = x0 + 1 + r.nextInt(size - 2), z = z0 + 1 + r.nextInt(size - 2);
            if (!freeAt(w, x, Y, z) || !freeAt(w, x + 1, Y, z) || !freeAt(w, x - 1, Y, z)) continue;
            Material[] kk = kinds[r.nextDouble() < 0.55 ? 0 : r.nextBoolean() ? 1 : 2];
            tree(w, r, x, z, kk[0], kk[1], 5 + r.nextInt(4));
        }
        for (int k = 0; k < sc(5); k++) {   // fallen trunks
            int x = x0 + 3 + r.nextInt(size - 10), z = z0 + 3 + r.nextInt(size - 6);
            boolean ax = r.nextBoolean();
            int len = 3 + r.nextInt(4);
            for (int i = 0; i < len; i++) if (freeAt(w, x + (ax ? i : 0), Y, z + (ax ? 0 : i))) setLog(w, x + (ax ? i : 0), Y, z + (ax ? 0 : i), Material.SPRUCE_LOG, ax ? Axis.X : Axis.Z);
        }
        for (int k = 0; k < sc(8); k++) {   // rocks
            int x = x0 + 2 + r.nextInt(size - 4), z = z0 + 2 + r.nextInt(size - 4);
            if (freeAt(w, x, Y, z)) set(w, x, Y, z, r.nextBoolean() ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE);
            if (r.nextBoolean() && freeAt(w, x + 1, Y, z)) set(w, x + 1, Y, z, Material.MOSSY_COBBLESTONE);
        }
        for (int k = 0; k < sc(120); k++) {
            int x = x0 + r.nextInt(size), z = z0 + r.nextInt(size);
            Material g = w.getBlockAt(x, Y - 1, z).getType();
            if (freeAt(w, x, Y, z) && (g == Material.GRASS_BLOCK || g == Material.PODZOL || g == Material.MOSS_BLOCK))
                set(w, x, Y, z, r.nextDouble() < 0.5 ? Material.FERN : Material.SHORT_GRASS);
        }
    }

    private static void town(World w, Random r, int x0, int z0) {
        // ruined houses (walls broken to random heights, window and door gaps), rubble, wrecks, a street through the middle
        int sz = Math.min(z0 + size - 12, z0 + coverZ + Math.max(5, dist / 2) - 2);
        for (int x = x0 - MARGIN + 1; x < x0 + size + MARGIN - 1; x++) for (int z = sz; z < sz + 5; z++) set(w, x, Y - 1, z, r.nextDouble() < 0.15 ? Material.BLACK_CONCRETE : Material.GRAY_CONCRETE);
        Material[] walls = {Material.BRICKS, Material.STONE_BRICKS, Material.CRACKED_STONE_BRICKS, Material.WHITE_CONCRETE, Material.LIGHT_GRAY_CONCRETE};
        int houses = 0;
        for (int tries = 0; tries < sc(40) && houses < sc(6); tries++) {
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
        for (int k = 0; k < sc(10); k++) rubble(w, r, x0 + 2 + r.nextInt(size - 4), z0 + 2 + r.nextInt(size - 4));
        for (int k = 0; k < 2 + r.nextInt(2); k++) car(w, r, x0 + 3 + r.nextInt(size - 8), sz + r.nextInt(2), true);
        for (int k = 0; k < 4; k++) { int x = x0 + 4 + r.nextInt(size - 8); if (freeAt(w, x, Y, sz - 1)) { set(w, x, Y, sz - 1, Material.ANDESITE_WALL); if (r.nextBoolean()) set(w, x, Y + 1, sz - 1, Material.IRON_BARS); } }
        for (int k = 0; k < sc(30); k++) { int x = x0 + r.nextInt(size), z = z0 + r.nextInt(size); if (freeAt(w, x, Y, z) && r.nextBoolean()) set(w, x, Y, z, Material.DEAD_BUSH); }
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
                if (dz >= coverZ - 4 && dz <= coverZ + 3 && shield) cover.add(s);
                double d = Math.hypot(x + 0.5 - cx, z + 0.5 - cz);
                if (d <= 4.5) open.add(s);
                if (dz >= coverZ + dist - 2 && dz <= Math.min(size - 4, coverZ + dist + 10)) far.add(s);
                if (d >= 9 && d <= Math.max(14, size / 5.0)) ring.add(s);
            }
        // when the cover is thin, the line's open spots do
        if (cover.size() < 8) for (int x = x0 + 4; x < x0 + size - 4 && cover.size() < 12; x += 2) if (standing(w, x, Y, z0 + coverZ)) cover.add(new Spot(x, Y, z0 + coverZ));
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
        return fight(pl, party, terrain, groups, mode, seed, done, null, "");
    }

    /** The same, with a talk on the battle map ({@code talk} with the group whose kind's key is {@code talkKey}; 09d round 4). */
    static String fight(FalloutakPlugin pl, List<Player> party, String terrain, List<Group> groups, Mode mode, long seed, Runnable done, Battle.Talk talk, String talkKey) {
        return fight(pl, party, terrain, groups, mode, seed, done, talk, talkKey, null, 2);
    }

    /**
     * The same on a UNIQUE LOCATION MAP when {@code on} is given (09d round 5), and with the raiders' kit of {@code years} after day D.
     */
    static String fight(FalloutakPlugin pl, List<Player> party, String terrain, List<Group> groups, Mode mode, long seed, Runnable done, Battle.Talk talk, String talkKey,
                        WorldMap.OnUnique on, double years) {
        if (current != null && !current.over && !pl.battle.players(current).isEmpty()) return "! another fight is on a battle map";
        for (Player q : party) if (pl.battle.inBattle(q)) pl.battle.stop(q);
        int reach = reach(party, groups);
        World w = on == null ? world() : uniqueWorld(on.u().world());
        if (w == null) { on = null; w = world(); }
        Layout lay = on != null ? survey(w, terrain, on.x(), on.z(), on.u().size(), sizeFor(groups.size(), reach), startDistance(reach), mode == Mode.AMBUSHED)
                : build(w, terrain, seed, sizeFor(groups.size(), reach), startDistance(reach));
        String where = on != null ? "the " + on.u().name() + " map" : "the " + terrain + " map";
        Battle.Fight f = new Battle.Fight("the enemies");
        f.demo = false;
        f.enemiesFirst = mode == Mode.AMBUSHED || mode == Mode.PARLEY;
        f.ambush = mode == Mode.AMBUSH;   // 05d round 5: the unaware
        f.field = lay.box();
        current = f;
        afterAll = done;
        // the enemies: an ambush or a hunt finds them in the open in the north half, an ambush on the party round it
        List<Spot> theirs = mode == Mode.AMBUSHED ? lay.ring() : lay.far();
        theirs = new ArrayList<>(theirs);
        theirs.sort(Comparator.comparingInt(Spot::x));   // a group stands together, left to right
        Random r = new Random(seed ^ 0x5DEECE66DL);
        int k = 0, total = 0, gi = 0, en = 0, pn = 0;
        double ex = 0, ez = 0, px = 0, pz = 0, py = 0;   // the enemies' and the party's middles (the dug traps between them)
        java.util.Set<Long> stand = new java.util.HashSet<>();   // where they stand (no trap under their feet)
        for (Group g : groups) total += g.count();
        int stride = Math.max(1, theirs.size() / Math.max(1, total));
        for (Group g : groups) {
            gi++;
            for (int n = 0; n < g.count(); n++) {
                Foe foe = kitFor(g.kind().key(), years, r);
                if (theirs.isEmpty()) break;
                Spot sp = theirs.get(Math.min(theirs.size() - 1, (k++ * stride + stride / 2) % theirs.size()));   // spread from the middle (a lone one not at the edge)
                Location at = sp.at(w);
                at.setDirection(lay.centre(w).toVector().subtract(at.toVector()).setY(0));
                ex += at.getX(); ez += at.getZ(); en++;
                stand.add(((long) sp.x() << 32) ^ (sp.z() & 0xffffffffL));
                String name = (g.count() > 1 ? foe.name() + " " + (n + 1) : foe.name());
                LivingEntity e = spawn(w, foe, at, name);
                Battle.Target t = new Battle.Target(name, foe.hp() > 0 ? foe.hp() : (int) Math.round(g.kind().hp()), foe.ac(), foe.dt(), foe.dr(), foe.a(), foe.skill());
                t.melee = !foe.gun();
                t.flees = g.flees();
                t.minDmg = foe.minDmg(); t.maxDmg = foe.maxDmg(); t.reach = foe.reach(); t.attackAp = foe.attackAp(); t.attack = foe.attack();
                t.venom = foe.venom();   // 04d §1: its strike carries venom
                t.field = lay.box();
                t.group = gi;
                t.peaceable = foe.key().equals("patrol") || foe.key().equals("trader");
                t.byParty = false;
                t.meatKg = foe.meatKg();
                t.hides = foe.hides();
                t.foe = foe.key();
                arm(t, foe, r);
                t.talking = talk != null && g.kind().key().equals(talkKey);
                if (!foe.nemesis().isEmpty()) t.kind = foe.nemesis();
                if (foe.gun()) t.gunHealth = Math.max(1, Battle.SMG.maxHealth * (0.25 + 0.35 * r.nextDouble()));   // used guns (05 §9, round 11)
                f.dummies.add(e.getUniqueId());
                f.targets.put(e.getUniqueId(), t);
                pl.battle.label(e, t);
            }
        }
        // the party
        if (talk != null) for (Battle.Target t : f.targets.values()) if (t.talking) { f.talk = talk; break; }
        List<Spot> ours = mode == Mode.AMBUSHED ? lay.open() : lay.cover();   // a talk or a parley: they were seen first, the party kept its cover
        if (ours.isEmpty()) ours = lay.open();
        if (ours.isEmpty()) ours = List.of(new Spot((int) Math.floor(lay.centre(w).getX()), lay.y0() + 1, lay.z0() + ESCAPE));   // a map with no ground found
        back.clear();
        StringBuilder who = new StringBuilder();
        for (Group g : groups) who.append(who.length() == 0 ? "" : ", ").append(g.count()).append(" × ").append(foe(g.kind().key()).name()).append(g.flees() ? " (hunted)" : "");
        String intro = switch (mode) {
            case AMBUSH -> "AMBUSH on " + where + ": you lie in cover, they have not seen you (" + who + "). Round 1: YOUR TURN, all of you at once.";
            case HUNT -> "THE HUNT on " + where + " (" + who + "): you are downwind. Round 1: YOUR TURN; what is not hostile will run.";
            case AMBUSHED -> "AMBUSHED on " + where + " (" + who + "): they are all round you and act first.";
            case TALK -> "THE TALK on " + where + " (" + who + "): " + (talk == null ? "you" : talk.speakerName) + " steps out and talks; it lasts "
                    + Battle.TALK_ROUNDS + " rounds. Move into position meanwhile. An attack breaks the talk and catches them off guard; it can also go wrong; "
                    + "another danger falling on the map can make them your allies.";
            case PARLEY -> "THEY TURN HOSTILE on " + where + " (" + who + "): they go for their weapons first; you still have your cover.";
        };
        int i = 0;
        for (Player q : party) {
            back.put(q.getUniqueId(), q.getLocation().clone());
            Spot sp = ours.get(Math.min(ours.size() - 1, i++));
            Location at = sp.at(w);
            at.setDirection(lay.centre(w).toVector().subtract(at.toVector()).setY(0));
            if (mode == Mode.AMBUSHED) at.setDirection(theirs.isEmpty() ? at.getDirection() : theirs.get(0).at(w).toVector().subtract(at.toVector()).setY(0));
            px += at.getX(); pz += at.getZ(); py += at.getY(); pn++;
            q.teleport(at);
            border(q, lay);
            Character c = pl.character(q);
            pl.battle.join(q, c, f, true);   // his own gear (09d round 3), and a death in it is real
            q.sendMessage(Component.text(intro + " Left click AT AN ENEMY = fire, right click = mode, shift = stance, the clock = end your turn; when every one "
                    + "of you has ended his, it is their turn. Walk to the map's edge to get away; the dangers also fight each other.", NamedTextColor.AQUA));
        }
        if ((mode == Mode.AMBUSH || mode == Mode.HUNT) && en > 0 && pn > 0 && !Travel.dug.isEmpty()) {   // 05d §7c: dug before the fight
            List<Battle.Trap> laid = layDug(w, lay, px / pn, pz / pn, py / pn, ex / en, ez / en, mode == Mode.HUNT, Travel.dug, stand);
            f.traps.addAll(laid);
            for (Player q : party) q.sendMessage(Component.text(laid.size() + " trap(s) you dug wait on their way" + (mode == Mode.HUNT ? " (past the animals, where they will run)" : " (half-way to them)") + ".", NamedTextColor.GOLD));
        }
        Travel.dug.clear();
        f.onLeave = q -> { unborder(q); Location l = back.remove(q.getUniqueId()); if (l != null) q.teleport(l); };
        f.onEnd = () -> { current = null; Runnable a = afterAll; afterAll = () -> { }; a.run(); };
        f.started = true;
        if (f.enemiesFirst) pl.battle.enemyPhase(f, () -> pl.battle.partyPhase(f));
        else for (Player q : party) { Battle.State s = pl.battle.state(q); if (s != null) pl.battle.show(q, s, pl.character(q), "round 1: your turn"); }
        return intro;
    }

    /** A hunt's pits lie this far past the animals; dug traps side by side this far apart [mine, EXAMPLE]. */
    static final double HUNT_PAST = 5, DUG_APART = 2;

    /**
     * 05d §7c: the pits and deadfalls dug before the fight laid on the enemies' way [mine, EXAMPLE]: an ambush half-way between the party
     * and them (where they charge), a hunt {@value #HUNT_PAST} blocks past the animals (where they run from the party); several side by
     * side {@value #DUG_APART} blocks apart across the way; each on the nearest cell where a figure can stand (the party's height first).
     */
    static List<Battle.Trap> layDug(World w, Layout lay, double px, double pz, double py, double ex, double ez, boolean hunt, List<Travel.Dug> dug,
                                    java.util.Set<Long> occupied) {
        List<Battle.Trap> out = new ArrayList<>();
        double dx = ex - px, dz = ez - pz, len = Math.hypot(dx, dz);
        if (len < 1e-6 || dug.isEmpty()) return out;
        double ux = dx / len, uz = dz / len;
        double past = Math.max(1.5, Math.min(HUNT_PAST, toEdge(lay, ex, ez, ux, uz) - 0.5));   // short of the edge, where they would be gone
        double bx = hunt ? ex + ux * past : px + dx * 0.5, bz = hunt ? ez + uz * past : pz + dz * 0.5;
        float yaw = (float) Math.toDegrees(Math.atan2(-ux, uz));
        java.util.Set<Long> used = new java.util.HashSet<>(occupied);
        int n = dug.size();
        for (int k = 0; k < n; k++) {
            double off = (k - (n - 1) / 2.0) * DUG_APART;
            int[] c = standNear(w, lay, bx - uz * off, bz + ux * off, (int) Math.round(py), used);
            if (c == null) continue;
            Travel.Dug d = dug.get(k);
            out.add(new Battle.Trap(d.def(), c[0], c[1], c[2], d.setter(), d.skill(), yaw));
        }
        return out;
    }

    /** How far from (x, z) along (ux, uz) the map's walkable inside goes (2 blocks from each edge). */
    static double toEdge(Layout lay, double x, double z, double ux, double uz) {
        double lo = 2, hi = lay.size() - 2, t = Double.MAX_VALUE, rx = x - lay.x0(), rz = z - lay.z0();
        if (ux > 1e-9) t = Math.min(t, (hi - rx) / ux); else if (ux < -1e-9) t = Math.min(t, (lo - rx) / ux);
        if (uz > 1e-9) t = Math.min(t, (hi - rz) / uz); else if (uz < -1e-9) t = Math.min(t, (lo - rz) / uz);
        return Math.max(0, t);
    }

    /** The cell nearest the point (tx, tz) inside the map where a figure can stand (air for the feet and the head, solid ground), the
     *  height nearest {@code refY} first; within 4 blocks, or null. */
    static int[] standNear(World w, Layout lay, double tx, double tz, int refY, java.util.Set<Long> used) {
        int cx = (int) Math.floor(tx), cz = (int) Math.floor(tz);
        int[] best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
            int x = cx + dx, z = cz + dz;
            double d = Math.hypot(x + 0.5 - tx, z + 0.5 - tz);
            long key = ((long) x << 32) ^ (z & 0xffffffffL);
            if (d >= bestD || d > 4.5 || used.contains(key) || x < lay.x0() + 2 || z < lay.z0() + 2 || x > lay.x0() + lay.size() - 3 || z > lay.z0() + lay.size() - 3) continue;
            int y0 = Integer.MIN_VALUE;
            for (int y = lay.y0() + 1; y <= lay.y1(); y++) {
                Block f = w.getBlockAt(x, y, z);
                if (f.isPassable() && f.getRelative(0, 1, 0).isPassable() && f.getRelative(0, -1, 0).getType().isSolid()
                        && (y0 == Integer.MIN_VALUE || Math.abs(y - refY) < Math.abs(y0 - refY))) y0 = y;
            }
            if (y0 != Integer.MIN_VALUE) { best = new int[]{x, y0, z}; bestD = d; }
        }
        if (best != null) used.add(((long) best[0] << 32) ^ (best[2] & 0xffffffffL));
        return best;
    }

    /**
     * The figure's weapon from its statblock (09d round 5): a bow or a melee weapon of `data/weapons.json` drawn from the row's list, the
     * rounds or arrows it carries besides the loaded ones; a gunner's pistol otherwise.
     */
    static void arm(Battle.Target t, Foe foe, Random r) {
        t.reserve = foe.reserve();
        if (foe.weapons().isEmpty()) return;
        Battle.Weapon w = Battle.GEAR.get(foe.weapons().get(r.nextInt(foe.weapons().size())));
        if (w == null) return;
        if (w.melee()) {
            t.melee = true;
            t.minDmg = w.minDmg; t.maxDmg = w.maxDmg;
            t.reach = Math.max(1, w.range - 1);
            t.attackAp = w.modes.get(0).ap();
            t.attack = w.name.toLowerCase(java.util.Locale.ROOT);
        } else {
            t.melee = false;
            t.gun = w;
            t.ammo = w.magazine;
            t.gunHealth = Math.max(1, w.maxHealth * (0.25 + 0.35 * r.nextDouble()));
        }
        t.weaponName = w.name;
    }

    /** The map's edge shown as a world border to the player (09d round 5: *"If the border will be visible, it should be fine."*). */
    static void border(Player q, Layout lay) {
        Battle.cosmetic(() -> {
            org.bukkit.WorldBorder b = Bukkit.createWorldBorder();
            b.setCenter(lay.x0() + lay.size() / 2.0, lay.z0() + lay.size() / 2.0);
            b.setSize(lay.size());
            b.setWarningDistance(0);
            q.setWorldBorder(b);
        });
    }

    static void unborder(Player q) { Battle.cosmetic(() -> q.setWorldBorder(null)); }

    /** A unique map's world, loaded on first use (null when it is not on the server). */
    static World uniqueWorld(String name) {
        if (use != null) return use;
        World w = Bukkit.getWorld(name);
        if (w != null) return w;
        if (!java.nio.file.Files.isDirectory(Bukkit.getWorldContainer().toPath().resolve(name))) return null;
        return Battle.orElse(() -> new WorldCreator(name).createWorld(), () -> null);
    }

    /**
     * THE FIGHT'S GROUND ON A UNIQUE MAP (09d round 5): nothing is built; the 128 blocks round the place are surveyed as they stand. The
     * party's point ({@code px}, {@code pz}) is on its line {@link #ESCAPE} blocks from the box's south edge (in the middle when they
     * are ambushed); the ground is the median of the surface in the box, and a column whose surface is within 3 blocks of it is a spot
     * (a roof or a wall is cover, not a spot) [mine, EXAMPLE].
     */
    static Layout survey(World w, String terrain, int px, int pz, int mapSize, int sz, int d, boolean centred) {
        size = sz;
        dist = d;
        coverZ = ESCAPE;
        int x0 = Math.max(0, Math.min(mapSize - sz, px - sz / 2));
        int z0 = Math.max(0, Math.min(mapSize - sz, centred ? pz - sz / 2 : pz - ESCAPE));
        int[][] top = new int[sz][sz];
        List<Integer> sample = new ArrayList<>();
        for (int x = 0; x < sz; x++)
            for (int z = 0; z < sz; z++) {
                int xx = x0 + x, zz = z0 + z;
                top[x][z] = Battle.orElse(() -> w.getHighestBlockYAt(xx, zz, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES), () -> w.getHighestBlockYAt(xx, zz));
                if ((x & 3) == 0 && (z & 3) == 0) sample.add(top[x][z]);
            }
        java.util.Collections.sort(sample);
        int ground = sample.isEmpty() ? Y : sample.get(sample.size() / 2);
        List<Spot> cover = new ArrayList<>(), open = new ArrayList<>(), far = new ArrayList<>(), ring = new ArrayList<>();
        double cx = centred ? px + 0.5 : x0 + sz / 2.0, cz = centred ? pz + 0.5 : z0 + sz / 2.0;
        double ox = px + 0.5, oz = pz + 0.5;
        int pzLine = pz - z0;
        for (int x = 2; x < sz - 2; x++)
            for (int z = 2; z < sz - 2; z++) {
                int y = top[x][z] + 1;
                if (Math.abs(y - 1 - ground) > 3 || !standing(w, x0 + x, y, z0 + z)) continue;
                Spot s = new Spot(x0 + x, y, z0 + z);
                boolean shield = false;
                for (int dx = -1; dx <= 1; dx++) if (w.getBlockAt(x0 + x + dx, y, z0 + z + 1).getType().isSolid() || w.getBlockAt(x0 + x + dx, y, z0 + z + 2).getType().isSolid()) shield = true;
                if (Math.abs(x0 + x + 0.5 - ox) <= 24 && z >= pzLine - 4 && z <= pzLine + 3 && shield) cover.add(s);
                double dc = Math.hypot(x0 + x + 0.5 - (centred ? cx : ox), z0 + z + 0.5 - (centred ? cz : oz));
                if (dc <= 4.5) open.add(s);
                if (z >= pzLine + d - 2 && z <= Math.min(sz - 4, pzLine + d + 10) && Math.abs(x0 + x + 0.5 - ox) <= 40) far.add(s);
                double dr = Math.hypot(x0 + x + 0.5 - cx, z0 + z + 0.5 - cz);
                if (dr >= 9 && dr <= Math.max(14, sz / 5.0)) ring.add(s);
            }
        Random r = new Random(((long) px << 20) ^ pz);
        if (cover.size() < 8) for (Spot s : open) if (cover.size() < 12) cover.add(s);
        if (cover.size() < 8) for (int x = 4; x < sz - 4 && cover.size() < 12; x += 2) {
            int y = top[x][Math.max(0, Math.min(sz - 1, pzLine))] + 1;
            if (standing(w, x0 + x, y, z0 + pzLine)) cover.add(new Spot(x0 + x, y, z0 + pzLine));
        }
        return new Layout(terrain, x0, z0, sz, spread(cover, r), spread(open, r), spread(far, r), spread(ring, r), ground - 8, ground + 24);
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
