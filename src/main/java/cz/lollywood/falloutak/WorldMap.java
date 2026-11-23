package cz.lollywood.falloutak;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Part 9d, the area's danger (`09d-BASES-DANGER.md`; Marek, part 9 round 2: *"I think we should rather simulate it based on were could
 * by our enemies (which ones, how many), creatures and the environmental danger."*): the world map of the region round Jihlava on ARAG'S
 * sectors (16 x 16 blocks of his Czechia), its bases (the raider groups' seats and HIDDEN camps, the small bands, the patrols) and the
 * creatures' lairs, written by `LollyWood-Lore/_tools/fallout_danger_sim.py --export` into the plugin's data folder as
 * {@code worldmap.json}. It is never in the jar or the public repository: the camps are hidden from the players.
 * <p>
 * A sector's encounter table is computed from the bases and lairs exactly as the simulation does: a base's parties out (fighters x the
 * share out a day / the party x 8 hours / 12) spread over its reach by e^(−d / (reach/2)), a lair's groups over their home range; a
 * party on foot meets them by ecology's GAS MODEL (meetings an hour = density x 2 x the meeting distance x the relative speed); each
 * kind's meetings are a FIGHT by its hostile share, else its peaceful type (TALK, TRADE, SIGHT; round 1: *"Peaceful meetings - yes,
 * lets give all encounter types."*); a sight at night needs half the distance.
 */
final class WorldMap {
    static final String[] TERRAIN = {"field", "road", "forest", "town"};
    static final String[] SEASONS = {"winter", "spring", "summer", "autumn"};

    record Kind(String key, String name, double day, double night, double hostile, double[] season, String peace, double group,
                double range, double hp, int party) {}

    /** A base or a lair; a base can be cleared (the players or a group took it). */
    static final class Src {
        final String key, kind, group, type;
        final int i, j;
        final double fighters, animals, party, reach;
        final boolean hidden, lair;
        boolean cleared;
        Src(String key, String kind, String group, String type, int i, int j, double fighters, double animals, double party, double reach,
            boolean hidden, boolean lair) {
            this.key = key; this.kind = kind; this.group = group; this.type = type; this.i = i; this.j = j; this.fighters = fighters;
            this.animals = animals; this.party = party; this.reach = reach; this.hidden = hidden; this.lair = lair;
        }
    }

    record Place(String name, int i, int j) {}

    /** A kind's meetings an hour at a sector: those that are a fight, and the peaceful ones (of its peaceful type). */
    record Row(Kind kind, double fight, double peace) { double all() { return fight + peace; } }

    /** What the roll met: the kind, the type (fight, talk, trade, sight) and how many. */
    record Encounter(Kind kind, String type, int count) {
        String text() {
            return switch (type) {
                case "fight" -> count + " × " + kind.name() + ": hostile";
                case "talk" -> count + " × " + kind.name() + ": they want to talk (a toll, a parley, news)";
                case "trade" -> kind.name() + " (" + count + "): they trade";
                default -> kind.name() + " (" + count + "): seen, not attacking (hunt it, or keep away)";
            };
        }
    }

    final int w, h;
    final double ex, ey, area, mover, outShare, hoursOut, nightSight, traders;
    final byte[] terrain, ground, road16;
    final Map<String, Double> speed = new HashMap<>(), meet = new HashMap<>();
    final Map<String, Kind> kinds = new LinkedHashMap<>();
    final List<Src> bases = new ArrayList<>(), lairs = new ArrayList<>();
    final List<Place> places = new ArrayList<>();
    private final double roadSum;
    private final Map<Double, Double> norms = new HashMap<>();

    private WorldMap(JsonObject o) {
        w = o.get("width").getAsInt(); h = o.get("height").getAsInt();
        JsonArray km = o.getAsJsonArray("sectorKm");
        ex = km.get(0).getAsDouble(); ey = km.get(1).getAsDouble(); area = ex * ey;
        terrain = Base64.getDecoder().decode(o.get("terrain").getAsString());
        ground = Base64.getDecoder().decode(o.get("ground").getAsString());
        road16 = Base64.getDecoder().decode(o.get("road16").getAsString());
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("speed").entrySet()) speed.put(e.getKey(), e.getValue().getAsDouble());
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("meet").entrySet()) meet.put(e.getKey(), e.getValue().getAsDouble());
        mover = o.get("mover").getAsDouble(); outShare = o.get("outShare").getAsDouble(); hoursOut = o.get("hoursOut").getAsDouble();
        nightSight = o.get("nightSight").getAsDouble(); traders = o.get("traders").getAsDouble();
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("kinds").entrySet()) {
            JsonObject k = e.getValue().getAsJsonObject();
            JsonArray s = k.getAsJsonArray("season");
            kinds.put(e.getKey(), new Kind(e.getKey(), k.get("name").getAsString(), k.get("day").getAsDouble(), k.get("night").getAsDouble(),
                    k.get("hostile").getAsDouble(), new double[]{s.get(0).getAsDouble(), s.get(1).getAsDouble(), s.get(2).getAsDouble(), s.get(3).getAsDouble()},
                    k.get("peace").getAsString(), k.has("group") ? k.get("group").getAsDouble() : 1, k.has("range") ? k.get("range").getAsDouble() : 0,
                    k.has("hp") ? k.get("hp").getAsDouble() : 30, k.has("party") ? k.get("party").getAsInt() : 1));
        }
        for (JsonElement e : o.getAsJsonArray("bases")) {
            JsonObject b = e.getAsJsonObject();
            String kind = b.get("kind").getAsString(), group = b.get("group").getAsString(), type = b.get("type").getAsString();
            String key = kind.equals("band") ? "bands" : type + ":" + group;
            bases.add(new Src(key, kind, group, type, b.get("i").getAsInt(), b.get("j").getAsInt(), b.get("fighters").getAsDouble(), 0,
                    b.get("party").getAsDouble(), b.get("reach").getAsDouble(), b.get("hidden").getAsBoolean(), false));
        }
        for (JsonElement e : o.getAsJsonArray("lairs")) {
            JsonObject l = e.getAsJsonObject();
            String kind = l.get("kind").getAsString();
            Kind k = kinds.get("creature:" + kind);
            lairs.add(new Src("creature:" + kind, kind, "", "creature", l.get("i").getAsInt(), l.get("j").getAsInt(), 0, l.get("animals").getAsDouble(),
                    k == null ? 1 : k.group(), k == null ? 1 : k.range(), true, true));
        }
        for (JsonElement e : o.getAsJsonArray("places")) {
            JsonObject p = e.getAsJsonObject();
            places.add(new Place(p.get("name").getAsString(), p.get("i").getAsInt(), p.get("j").getAsInt()));
        }
        double rs = 0;
        for (int k = 0; k < w * h; k++) if (ground[k] <= 1) rs += road16[k] / 16.0;
        roadSum = rs;
    }

    static WorldMap read(Reader r) { return new WorldMap(JsonParser.parseReader(r).getAsJsonObject()); }

    /** The data folder's worldmap.json, or null when there is none. */
    static WorldMap load(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { return read(r); }
        catch (IOException | RuntimeException e) { throw new IllegalStateException(file + ": " + e.getMessage(), e); }
    }

    boolean inside(int i, int j) { return i >= 0 && i < h && j >= 0 && j < w; }
    String terrainAt(int i, int j) { return TERRAIN[terrain[i * w + j]]; }
    int groundAt(int i, int j) { return ground[i * w + j]; }
    /** km between two sectors' centres */
    double km(double i1, double j1, double i2, double j2) { return Math.hypot((j2 - j1) * ex, (i2 - i1) * ey); }

    /** The kernel's sum over the sectors it covers (the simulation's normalisation). */
    double norm(double reach) {
        return norms.computeIfAbsent(reach, rr -> {
            int rx = (int) Math.ceil(rr / ex), ry = (int) Math.ceil(rr / ey);
            double s = 0;
            for (int a = -ry; a <= ry; a++) for (int b = -rx; b <= rx; b++) {
                double d = Math.hypot(b * ex, a * ey);
                if (d <= rr) s += Math.exp(-d / (rr / 2));
            }
            return s;
        });
    }

    /** A base's or a lair's presence at the offset (di, dj) sectors from it, a share summing to 1 over its reach. */
    double kernel(double reach, int di, int dj) {
        if (reach <= 0) return 0;
        double d = Math.hypot(dj * ex, di * ey);
        return d > reach ? 0 : Math.exp(-d / (reach / 2)) / norm(reach);
    }

    /** The groups out a km² at a daytime hour, by kind, at a sector. */
    Map<String, Double> density(int i, int j) {
        Map<String, Double> d = new LinkedHashMap<>();
        for (Src b : bases) {
            if (b.cleared) continue;
            double k = kernel(b.reach, i - b.i, j - b.j);
            if (k == 0) continue;
            d.merge(b.key, k * (b.fighters * outShare / b.party * hoursOut / 12) / area, Double::sum);
        }
        if (inside(i, j) && ground[i * w + j] <= 1 && roadSum > 0 && road16[i * w + j] > 0)
            d.merge("traders", road16[i * w + j] / 16.0 / roadSum * traders * hoursOut / 12 / area, Double::sum);
        for (Src l : lairs) {
            if (l.cleared) continue;
            double k = kernel(l.reach, i - l.i, j - l.j);
            if (k == 0) continue;
            d.merge(l.key, k * (l.animals / l.party) / area, Double::sum);
        }
        return d;
    }

    /** The sector's encounter table: every kind's meetings an hour for a party on foot, by season (0 winter .. 3 autumn) and day part. */
    List<Row> table(int i, int j, int season, boolean day) {
        String t = terrainAt(i, j);
        double vp = speed.get(t), rm = meet.get(t);
        double sweep = 2 * rm * Math.sqrt(vp * vp + mover * mover);
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, Double> e : density(i, j).entrySet()) {
            Kind k = kinds.get(e.getKey());
            if (k == null) continue;
            double r = e.getValue() * (day ? k.day() : k.night()) * k.season()[season] * sweep;
            double peace = r * (1 - k.hostile()) * (!day && k.peace().equals("sight") ? nightSight : 1);
            if (r > 0) rows.add(new Row(k, r * k.hostile(), peace));
        }
        rows.sort((a, b) -> Double.compare(b.all(), a.all()));
        return rows;
    }

    /** The meetings an hour of each type (fight, talk, trade, sight) at a sector. */
    Map<String, Double> types(List<Row> rows) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (String t : new String[]{"fight", "talk", "trade", "sight"}) m.put(t, 0.0);
        for (Row r : rows) { m.merge("fight", r.fight(), Double::sum); m.merge(r.kind().peace(), r.peace(), Double::sum); }
        return m;
    }

    static double total(List<Row> rows) { double s = 0; for (Row r : rows) s += r.all(); return s; }

    /** What a meeting from this table is: a kind by its share, then a fight by its hostile share, else its peaceful type. */
    static Encounter draw(List<Row> rows, Random rnd) {
        double tot = total(rows);
        if (tot <= 0) return null;
        double u = rnd.nextDouble() * tot;
        Row pick = rows.get(rows.size() - 1);
        for (Row r : rows) { if (u < r.all()) { pick = r; break; } u -= r.all(); }
        boolean fight = rnd.nextDouble() * pick.all() < pick.fight();
        Kind k = pick.kind();
        int n = k.key().startsWith("creature:") ? (int) Math.max(1, Math.round(k.group())) : Math.max(1, k.party());
        return new Encounter(k, fight ? "fight" : k.peace(), n);
    }

    /** The nearest place to a sector. */
    Place nearest(double i, double j) {
        Place best = null;
        double bd = Double.MAX_VALUE;
        for (Place p : places) { double d = km(i, j, p.i(), p.j()); if (d < bd) { bd = d; best = p; } }
        return best;
    }

    Place place(String name) {
        for (Place p : places) if (p.name().equalsIgnoreCase(name.replace('_', ' '))) return p;
        return null;
    }
}
