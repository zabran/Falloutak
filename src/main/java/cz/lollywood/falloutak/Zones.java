package cz.lollywood.falloutak;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Gas ZONES in the world (03b §3c; Marek 11-18: *"I would like these effects localized to specific areas, like in the factory, or
 * anomalies with the acid rain and the gas granade also hits specific space."*): a gas at a strength fills a BOX (a factory hall, an
 * anomaly, marked by two corners) or a BALL (a gas grenade's cloud). Whoever breathes inside it (his eyes in it) is in that gas; a
 * zone may last (the GM removes it) or count down game ROUNDS (a fight's rounds and the GM's Pass time for everyone). The visible
 * gases show as particles; the invisible ones (CO, methane, sewer gas, bad air, ammonia, mercury) only to the GM.
 */
final class Zones {
    static final class Zone {
        String id = "", world = "", gas = "", label = "";
        int strength = 1;
        /** A ball around (x1, y1, z1) of radius r; else the box x1..x2, y1..y2, z1..z2 (block corners, both included). */
        boolean ball;
        double x1, y1, z1, x2, y2, z2, r;
        /** Game rounds left; -1 = lasting. */
        int rounds = -1;

        boolean contains(Location l) { return l.getWorld() != null && contains(l.getWorld().getName(), l.getX(), l.getY(), l.getZ()); }

        boolean contains(String w, double x, double y, double z) {
            if (!w.equals(world)) return false;
            if (ball) {
                double dx = x - x1, dy = y - y1, dz = z - z1;
                return dx * dx + dy * dy + dz * dz <= r * r + 1e-9;
            }
            return x >= x1 && x < x2 && y >= y1 && y < y2 && z >= z1 && z < z2;
        }

        double volume() { return ball ? 4.0 / 3 * Math.PI * r * r * r : (x2 - x1) * (y2 - y1) * (z2 - z1); }

        Location centre(World w) { return ball ? new Location(w, x1, y1, z1) : new Location(w, (x1 + x2) / 2, (y1 + y2) / 2, (z1 + z2) / 2); }

        Rules.Gas kind() { return Rules.GASES.get(gas); }

        String describe() {
            String shape = ball ? "a ball of radius " + SheetGui.f(r) + " at " + (int) Math.floor(x1) + " " + (int) Math.floor(y1) + " " + (int) Math.floor(z1)
                    : "a box " + (int) (x2 - x1) + "×" + (int) (y2 - y1) + "×" + (int) (z2 - z1) + " from " + (int) x1 + " " + (int) y1 + " " + (int) z1;
            return (label.isEmpty() ? "" : label + ": ") + gas + " " + Rules.roman(strength) + ", " + shape + (rounds < 0 ? ", lasting" : ", " + rounds + " rounds left");
        }
    }

    final List<Zone> list = new ArrayList<>();
    private final Path file;
    private int seq = 0;
    private final Random rnd = new Random();

    Zones(Path dataFolder) {
        this.file = dataFolder.resolve("zones.json");
        load();
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            Zone[] zs = Store.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Zone[].class);
            if (zs != null) for (Zone z : zs) if (z != null && Rules.GASES.containsKey(z.gas)) list.add(z);
            for (Zone z : list) try { seq = Math.max(seq, Integer.parseInt(z.id)); } catch (NumberFormatException ignored) { }
        } catch (IOException | RuntimeException e) {
            Bukkit.getLogger().warning("[Falloutak] zones.json unreadable: " + e);
        }
    }

    void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Store.GSON.toJson(list.toArray(new Zone[0])), StandardCharsets.UTF_8);
        } catch (IOException e) { Bukkit.getLogger().warning("[Falloutak] could not save zones: " + e); }
    }

    /** The clouds nobody breathes (they only hide: {@link Rules#OBSCURES}). */
    static final Set<String> NOT_BREATHED = Set.of("pollen");

    /** The gas zone a point is in (the strongest where they overlap), or null; a cloud that only hides is not a gas here. */
    Zone at(Location l) {
        Zone best = null;
        for (Zone z : list) if (!NOT_BREATHED.contains(z.gas) && z.contains(l) && (best == null || z.strength > best.strength)) best = z;
        return best;
    }

    /**
     * The worst hiding cloud on a line of fire (Marek 11-19: the Trhač's pollen *"should obscure for range attacks, the same way as
     * smoke or fog does"*): a shot into it, out of it or through it, sampled every half block; null if the line is clear.
     */
    Zone veil(Location from, Location to) {
        if (from.getWorld() == null || to.getWorld() == null || !from.getWorld().equals(to.getWorld())) return null;
        String w = from.getWorld().getName();
        double d = from.distance(to);
        int n = Math.max(1, (int) Math.ceil(d / 0.5));
        Zone worst = null;
        for (Zone z : list) {
            Integer v = Rules.OBSCURES.get(z.gas);
            if (v == null || (worst != null && v * z.strength >= Rules.OBSCURES.get(worst.gas) * worst.strength)) continue;
            for (int i = 0; i <= n; i++) {
                double t = (double) i / n;
                if (z.contains(w, from.getX() + (to.getX() - from.getX()) * t, from.getY() + (to.getY() - from.getY()) * t,
                        from.getZ() + (to.getZ() - from.getZ()) * t)) { worst = z; break; }
            }
        }
        return worst;
    }

    Zone ball(Location c, double r, String gas, int strength, int rounds, String label) {
        Zone z = new Zone();
        z.id = String.valueOf(++seq);
        z.world = c.getWorld().getName();
        z.ball = true;
        z.x1 = c.getX(); z.y1 = c.getY(); z.z1 = c.getZ(); z.r = r;
        z.gas = gas; z.strength = strength; z.rounds = rounds; z.label = label;
        list.add(z);
        save();
        return z;
    }

    /** A box between two blocks, both included. */
    Zone box(Location a, Location b, String gas, int strength, int rounds, String label) {
        Zone z = new Zone();
        z.id = String.valueOf(++seq);
        z.world = a.getWorld().getName();
        z.x1 = Math.min(a.getBlockX(), b.getBlockX()); z.x2 = Math.max(a.getBlockX(), b.getBlockX()) + 1;
        z.y1 = Math.min(a.getBlockY(), b.getBlockY()); z.y2 = Math.max(a.getBlockY(), b.getBlockY()) + 1;
        z.z1 = Math.min(a.getBlockZ(), b.getBlockZ()); z.z2 = Math.max(a.getBlockZ(), b.getBlockZ()) + 1;
        z.gas = gas; z.strength = strength; z.rounds = rounds; z.label = label;
        list.add(z);
        save();
        return z;
    }

    void remove(Zone z) { if (list.remove(z)) save(); }

    /**
     * The scene's round (Marek 11-18, round 24: two players in rounds aged a cloud twice, *"that will be common issue"*): every
     * player in rounds counts his own rounds from the scene's round when he entered; a cloud ages only when someone gets AHEAD of it
     * ({@link #reach}), so players in rounds side by side age it once a round, as one table would.
     */
    long round = 0;

    /** A player in rounds has reached his round {@code r}: the zones age to it once, however many reach it. */
    void reach(long r) {
        if (r <= round) return;
        int n = (int) (r - round);
        round = r;
        age(n);
    }

    /** Game rounds pass for the whole world (the GM's Pass time for everyone). */
    void rounds(int n) {
        round += n;
        age(n);
    }

    /** The zones that count down lose n rounds; a spent one is gone. */
    private void age(int n) {
        boolean changed = false;
        for (Iterator<Zone> it = list.iterator(); it.hasNext(); ) {
            Zone z = it.next();
            if (z.rounds < 0) continue;
            z.rounds -= n;
            changed = true;
            if (z.rounds <= 0) it.remove();
        }
        if (changed) save();
    }

    /** A creature's breath is where its eyes are: the gas there goes into its character (each second, and before time passes). */
    void apply(Character c, Entity e) {
        Zone z = e instanceof LivingEntity le ? at(le.getEyeLocation()) : e == null ? null : at(e.getLocation());
        c.zoneGas = z == null ? "" : z.gas;
        c.zoneStrength = z == null ? 1 : z.strength;
    }

    /** What a gas does to one with no protection at all (a raider): {P, to hit, AP} × the strength. */
    int[] penaltyAt(Location l) {
        Zone z = at(l);
        if (z == null || z.kind() == null || z.kind().bad()) return new int[3];
        Rules.Gas g = z.kind();
        return new int[]{g.p() * z.strength, g.toHit() * z.strength, g.ap() * z.strength};
    }

    // ------------------------------------------------------------------ what can be seen
    /** The visible gases (03b §3c: CO "no smell, no warning", ammonia and the rest have no colour). */
    static Particle.DustOptions dust(String gas) {
        return switch (gas) {
            case "riot gas" -> new Particle.DustOptions(Color.fromRGB(235, 235, 230), 2.2f);
            case "chlorine" -> new Particle.DustOptions(Color.fromRGB(200, 215, 80), 2.2f);
            case "acid fog" -> new Particle.DustOptions(Color.fromRGB(170, 200, 140), 1.8f);
            case "pollen" -> new Particle.DustOptions(Color.fromRGB(236, 206, 52), 3.0f);   // the Trhač's: dense, it must hide
            default -> null;
        };
    }

    /** Each second: particles in every zone someone is near; the invisible ones faintly, to the GMs only. */
    void show() {
        for (Zone z : list) {
            World w = Bukkit.getWorld(z.world);
            if (w == null) continue;
            Location mid = z.centre(w);
            double reach = 48 + (z.ball ? z.r : Math.max(z.x2 - z.x1, z.z2 - z.z1));
            List<Player> near = new ArrayList<>();
            for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(mid) <= reach * reach) near.add(p);
            if (near.isEmpty()) continue;
            int n = (int) Math.max(4, Math.min(40, z.volume() / 6));
            boolean smoke = z.gas.equals("smoke");
            Particle.DustOptions d = dust(z.gas);
            for (int i = 0; i < n; i++) {
                Location at = randomIn(z, w);
                if (at == null) continue;
                if (smoke) w.spawnParticle(Particle.LARGE_SMOKE, at, 1, 0.2, 0.2, 0.2, 0.0);
                else if (d != null) w.spawnParticle(Particle.DUST, at, NOT_BREATHED.contains(z.gas) ? 4 : 1, 0.3, 0.3, 0.3, 0.0, d);
                else if (i % 4 == 0)
                    for (Player g : near) if (g.hasPermission("falloutak.gm"))
                        g.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0.0, new Particle.DustOptions(Color.fromRGB(150, 60, 200), 1f));
            }
        }
    }

    private Location randomIn(Zone z, World w) {
        if (!z.ball) return new Location(w, z.x1 + rnd.nextDouble() * (z.x2 - z.x1), z.y1 + rnd.nextDouble() * (z.y2 - z.y1), z.z1 + rnd.nextDouble() * (z.z2 - z.z1));
        for (int k = 0; k < 8; k++) {
            double x = rnd.nextDouble() * 2 - 1, y = rnd.nextDouble() * 2 - 1, zz = rnd.nextDouble() * 2 - 1;
            if (x * x + y * y + zz * zz <= 1) return new Location(w, z.x1 + x * z.r, z.y1 + y * z.r, z.z1 + zz * z.r);
        }
        return null;
    }
}
