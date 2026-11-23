package cz.lollywood.falloutak;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Part 9's WORLD MAP in Minecraft (`09` §3.1 A, RULED: Arag's Czechia, *"the party is a token (a figure or a banner on a post) that the
 * plugin moves; the players see it from above (spectator or flying) or stand by it"*; 09d round 2, Marek 11-23: *"(his map would need
 * re-exporting for 1.21.11) and a battle arena per terrain - lets go for it."*). Arag's world, exported again for 1.21.11 by
 * `LollyWood-Lore/_tools/wp_export_121.js`, is the world {@link #WORLD} in the server's folder; a sector (i, j) of the world map
 * ({@link WorldMap}, its box at (bx0, by0) of Arag's 1280 x 624 sectors) is the blocks x = 16 (bx0 + j) .. + 15, z = 16 (by0 + i) .. + 15
 * (a block ~28 m). The TOKEN is a red banner on a post, ten blocks tall, the party's name over it, on the ground at the party's place;
 * it walks the way the party walked. The places within sight carry their names in big letters. <i>Actions → Travel → Look at the
 * world map</i> takes a player up over the token, flying; <i>Back from the world map</i> puts him where he was. [The look mine.]
 */
final class MapView {
    private MapView() {}

    static final String WORLD = "fo_worldmap", TAG = "falloutak_map";
    static final int HIGH = 45, LABELS_WITHIN = 480;
    static UUID token, name;
    static final Map<UUID, Location> viewers = new HashMap<>();
    static final Map<UUID, Boolean> flew = new HashMap<>();
    static final List<UUID> labels = new ArrayList<>();
    /** The block the token stands on (x, z), for the tests. */
    static double tokenX = Double.NaN, tokenZ = Double.NaN;

    /** A world map sector (fractional) as Arag's blocks (x, z). */
    static double[] block(WorldMap m, double i, double j) { return new double[]{16 * (m.bx0 + j), 16 * (m.by0 + i)}; }

    /** The world map's world: loaded if it is in the server's folder (nothing is generated round it), else null. */
    static World world() {
        World w = Bukkit.getWorld(WORLD);
        if (w != null) return w;
        File dir = Battle.orElse(Bukkit::getWorldContainer, () -> null);
        if (dir == null || !new File(dir, WORLD + "/level.dat").isFile()) return null;
        World made = Battle.orElse(() -> new WorldCreator(WORLD).generator(new Arena.Empty()).createWorld(), () -> null);
        if (made != null) {   // a map to read: always noon, always clear, nothing spawns
            Battle.cosmetic(() -> made.setGameRule(org.bukkit.GameRules.ADVANCE_TIME, false));
            Battle.cosmetic(() -> made.setGameRule(org.bukkit.GameRules.ADVANCE_WEATHER, false));
            Battle.cosmetic(() -> made.setGameRule(org.bukkit.GameRules.SPAWN_MOBS, false));
            Battle.cosmetic(() -> made.setTime(6000));
            Battle.cosmetic(() -> made.setStorm(false));
        }
        return made;
    }

    static int ground(World w, int x, int z) { return Battle.orElse(() -> w.getHighestBlockYAt(x, z), () -> 64) + 1; }

    /** The token at the party's place (made if it is gone: it is not saved with the chunk, so a restart leaves no copy). */
    static void place() { place(world()); }

    /** The token where the party is, if the world map's world is loaded (the GM moved the party). */
    static void moved() { if (Bukkit.getServer() == null) return; World w = Bukkit.getWorld(WORLD); if (w != null) place(w); }

    private static void place(World w) {
        if (w == null || Travel.map == null || Double.isNaN(Travel.pi)) return;
        double[] b = block(Travel.map, Travel.pi, Travel.pj);
        put(w, b[0], b[1]);
        names(w, b[0], b[1]);
    }

    private static void put(World w, double x, double z) {
        tokenX = x; tokenZ = z;
        Location at = new Location(w, x, ground(w, (int) Math.floor(x), (int) Math.floor(z)), z);
        Entity t = token == null ? null : Bukkit.getEntity(token), n = name == null ? null : Bukkit.getEntity(name);
        if (t == null) {
            t = Battle.orElse(() -> w.spawn(at, ItemDisplay.class, d -> {
                d.setItemStack(new ItemStack(Material.RED_BANNER));
                d.setTransformation(new Transformation(new Vector3f(0, 5, 0), new AxisAngle4f(), new Vector3f(10, 10, 10), new AxisAngle4f()));
                d.setBillboard(Display.Billboard.VERTICAL);
                d.setPersistent(false);
                d.addScoreboardTag(TAG);
            }), () -> null);
            if (t != null) token = t.getUniqueId();
        } else t.teleport(at);
        Location over = at.clone().add(0, 12, 0);
        if (n == null) {
            n = Battle.orElse(() -> w.spawn(over, TextDisplay.class, d -> {
                d.text(Component.text("The party", NamedTextColor.RED));
                d.setBillboard(Display.Billboard.CENTER);
                d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(6, 6, 6), new AxisAngle4f()));
                d.setPersistent(false);
                d.addScoreboardTag(TAG);
            }), () -> null);
            if (n != null) name = n.getUniqueId();
        } else n.teleport(over);
    }

    /** The places within {@link #LABELS_WITHIN} blocks of the token: their names over the ground (made again each time). */
    private static void names(World w, double x, double z) {
        for (UUID u : labels) { Entity e = Bukkit.getEntity(u); if (e != null) e.remove(); }
        labels.clear();
        for (WorldMap.Place p : Travel.map.places) {
            double[] b = block(Travel.map, p.i() + 0.5, p.j() + 0.5);
            if (Math.hypot(b[0] - x, b[1] - z) > LABELS_WITHIN) continue;
            Location at = new Location(w, b[0], ground(w, (int) Math.floor(b[0]), (int) Math.floor(b[1])) + 8, b[1]);
            Entity e = Battle.orElse(() -> w.spawn(at, TextDisplay.class, d -> {
                d.text(Component.text(p.name(), NamedTextColor.WHITE));
                d.setBillboard(Display.Billboard.CENTER);
                d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(8, 8, 8), new AxisAngle4f()));
                d.setSeeThrough(true);
                d.setPersistent(false);
                d.addScoreboardTag(TAG);
            }), () -> null);
            if (e != null) labels.add(e.getUniqueId());
        }
    }

    /**
     * The party walked from (fi, fj) to (ti, tj): the token walks it in {@code ticks} ticks, the viewers over it along with it, and the
     * names round its new place are made again at the end.
     */
    static void walk(FalloutakPlugin pl, double fi, double fj, double ti, double tj, int ticks) {
        World w = Bukkit.getWorld(WORLD);   // only while it is loaded: whoever looks later finds the token in place
        if (w == null || Travel.map == null) return;
        double[] a = block(Travel.map, fi, fj), b = block(Travel.map, ti, tj);
        int n = Math.max(1, ticks);
        for (int k = 1; k <= n; k++) {
            final double u = k / (double) n;
            final boolean last = k == n;
            pl.getServer().getScheduler().runTaskLater(pl, () -> {
                double x = a[0] + (b[0] - a[0]) * u, z = a[1] + (b[1] - a[1]) * u;
                double dx = x - tokenX, dz = z - tokenZ;
                put(w, x, z);
                for (Map.Entry<UUID, Location> v : viewers.entrySet()) {
                    Player p = pl.getServer().getPlayer(v.getKey());
                    if (p != null && p.getWorld().equals(w)) p.teleport(p.getLocation().add(Double.isNaN(dx) ? 0 : dx, 0, Double.isNaN(dz) ? 0 : dz));
                }
                if (last) names(w, x, z);
            }, k);
        }
    }

    /** Up over the token, flying (the GM's and the players' view of the world map). */
    static String look(FalloutakPlugin pl, Player p) {
        World w = world();
        if (w == null) return "! the world map's world (" + WORLD + ") is not in the server's folder: LollyWood-Lore/_tools/wp_export_121.js makes it";
        if (Travel.map == null || Double.isNaN(Travel.pi)) return "! no world map is loaded";
        if (pl.battle.inBattle(p)) return "! not in a fight";
        place();
        if (!viewers.containsKey(p.getUniqueId())) { viewers.put(p.getUniqueId(), p.getLocation().clone()); flew.put(p.getUniqueId(), p.getAllowFlight()); }
        Location at = new Location(w, tokenX, ground(w, (int) Math.floor(tokenX), (int) Math.floor(tokenZ)) + HIGH, tokenZ - 30, 0f, 55f);
        p.teleport(at);
        Battle.cosmetic(() -> { p.setAllowFlight(true); p.setFlying(true); });
        return "The world map: the red banner is your party (" + Travel.where() + "); a block here is ~28 m. Fly round freely; Back from the world map in the Travel menu.";
    }

    static boolean viewing(Player p) { return viewers.containsKey(p.getUniqueId()); }

    /** Back to where he was before he looked. */
    static String back(Player p) {
        Location l = viewers.remove(p.getUniqueId());
        Boolean f = flew.remove(p.getUniqueId());
        if (l == null) return "! you are not over the world map";
        p.teleport(l);
        boolean fly = f != null && f;
        Battle.cosmetic(() -> { p.setFlying(false); p.setAllowFlight(fly); });
        return "Back from the world map.";
    }
}
