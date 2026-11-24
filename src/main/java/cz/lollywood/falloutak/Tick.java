package cz.lollywood.falloutak;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * `09d` §6's WEEKLY TICK inside the plugin (round 3, Marek 11-23: *"the weekly tick inside the plugin ... please continue with that."*):
 * `LollyWood-Lore/_tools/fallout_danger_sim.py`'s `Tick.step` ported line by line on the world map's own bases and lairs, so the camps
 * move as the game's weeks pass [every rule and number the simulation's, tested there with `--test`]:
 * <ol>
 *   <li><b>food:</b> each base claims a share of the villagers in its reach (its fighters x e^(−d / (reach/2))), a ghoul faction none;
 *   fed above its need it grows by recruits, hungry it starves; a little loss to fights and disease;</li>
 *   <li><b>the record:</b> the region's raiders scaled to the run of record (`09b`), less what clearing took off;</li>
 *   <li><b>where:</b> a hungry hidden camp or band moves to better ground in its group's (2-10 km), a fed one now and then when much
 *   better opens; a seat grown by a quarter sends a new hidden camp out; a camp over 40 or a band over 15 splits; a small one joins a
 *   mate with room; never onto held ground;</li>
 *   <li><b>the creatures:</b> the spring births and the winter, deaths rising with the species' density against the land's capacity,
 *   hunting near the living (boar, dogs, wolves), new lairs founded from overfull ones, at most 1.6 x the start's lairs.</li>
 * </ol>
 * The state (the moved bases and lairs, the week, the need, what clearing took) is kept in the plugin's folder
 * ({@code worldmap-state.json}), never in the repository (the camps are hidden). The GM's clear and hold are the tick's.
 */
final class Tick {
    final WorldMap m;
    final WorldMap.TickData d;
    final Random rnd;
    int week;
    double need = Double.NaN, raiders0 = Double.NaN, clearedShare = 1.0;
    final Map<String, Integer> startCount = new HashMap<>();
    final Map<String, Double> startAnimals = new HashMap<>();
    final boolean[] held;
    private double[] hunt;
    private int huntWeek = -99;
    int moves, splits, joins;

    Tick(WorldMap m, Random rnd) {
        this.m = m; this.d = m.tick; this.rnd = rnd;
        held = new boolean[m.w * m.h];
        for (WorldMap.Src l : m.lairs) if (!l.boss) { startCount.merge(l.kind, 1, Integer::sum); startAnimals.merge(l.kind, l.animals, Double::sum); }
        raiders0 = raiders();
    }

    static double interp(double x, double[][] t) {
        if (x <= t[0][0]) return t[0][1];
        for (int k = 1; k < t.length; k++) if (x <= t[k][0]) return t[k - 1][1] + (t[k][1] - t[k - 1][1]) * (x - t[k - 1][0]) / (t[k][0] - t[k - 1][0]);
        return t[t.length - 1][1];
    }

    double years() { return d.startYear + week / 52.0; }
    double record(double years) { return interp(years, d.record); }
    double aliveShare() { return interp(years(), d.alive); }
    double people(int p) { return d.pop[p] * aliveShare(); }

    double raiders() { double s = 0; for (WorldMap.Src b : m.bases) if (b.type.equals("raiders") && !b.cleared) s += b.fighters; return s; }

    int own(int i, int j) { return (d.own[i * m.w + j] & 0xff) - 1; }
    double campable(int i, int j) { return (d.campable[i * m.w + j] & 0xff) / d.campableScale; }
    double bandable(int i, int j) { return (d.bandable[i * m.w + j] & 0xff) / d.bandableScale; }
    double hab(String h, int i, int j) { byte[] a = d.hab.get(h); return a == null ? 0 : (a[i * m.w + j] & 0xff) / d.habScale; }

    private double dist(double i1, double j1, double i2, double j2) { return Math.hypot((i1 - i2) * m.ey, (j1 - j2) * m.ex); }

    // ------------------------------------------------------------------ 1-2. food and the record
    /** Each base's food (villagers fed to it), its fighters for the claim (a ghoul faction 0), and the claims (places x bases). */
    record Supply(double[] got, double[] fig, double[][] w) {}

    Supply supply() {
        List<WorldMap.Src> bs = m.bases;
        int n = bs.size(), P = d.pop.length;
        double[] fig = new double[n], got = new double[n];
        double[][] w = new double[P][n];
        for (int k = 0; k < n; k++) fig[k] = d.ghouls.contains(bs.get(k).group) ? 0 : bs.get(k).fighters;
        for (int p = 0; p < P; p++) {
            double tot = 0;
            for (int k = 0; k < n; k++) {
                WorldMap.Src b = bs.get(k);
                double dd = dist(d.pi[p], d.pj[p], b.i, b.j);
                w[p][k] = dd <= b.reach ? Math.exp(-dd / (b.reach / 2)) : 0;
                tot += w[p][k] * fig[k];
            }
            if (tot <= 0) continue;
            double pe = people(p);
            for (int k = 0; k < n; k++) got[k] += pe * w[p][k] * fig[k] / Math.max(tot, 1e-12);
        }
        if (Double.isNaN(need)) {   // the start is in balance: the region's villagers fed to it over its fighters
            double g = 0, f = 0;
            for (int k = 0; k < n; k++) if (fig[k] > 0) { g += got[k]; f += fig[k]; }
            need = g / f;
        }
        return new Supply(got, fig, w);
    }

    /** Steps 1-2: grow or starve by the food, then the region's raiders to the record. Each fed base's share of its need. */
    Map<Integer, Double> feed(double[] got) {
        Map<Integer, Double> sat = new LinkedHashMap<>();
        for (int k = 0; k < m.bases.size(); k++) {
            WorldMap.Src b = m.bases.get(k);
            if (!b.type.equals("raiders") || d.ghouls.contains(b.group)) continue;
            double s = got[k] / Math.max(1e-9, b.fighters * need);
            sat.put(k, s);
            if (s >= 1) b.fighters *= 1 + d.grow * Math.min(s - 1, 1);
            else b.fighters *= 1 - d.starve * (1 - s);
            b.fighters *= 1 - d.loss;
        }
        double cur = raiders();
        double target = raiders0 * record(d.startYear + (week + 1) / 52.0) / record(d.startYear) * clearedShare;
        if (cur > 0) for (WorldMap.Src b : m.bases) if (b.type.equals("raiders")) b.fighters *= target / cur;
        return sat;
    }

    // ------------------------------------------------------------------ 3. where they are
    record Site(int i, int j, double v) {}

    /** The best ground for a camp of b within rmin-rmax km: its prospective food against the others' claims. */
    Site candidate(WorldMap.Src b, double rmin, double rmax, double[][] w, double[] fig) {
        int gi = b.kind.equals("band") ? -1 : d.groups.indexOf(b.group);
        Site best = null;
        double[] others = new double[d.pop.length];
        for (int p = 0; p < d.pop.length; p++) { double s = 0; for (int k = 0; k < fig.length; k++) s += w[p][k] * fig[k]; others[p] = s; }
        for (int t = 0; t < 48; t++) {
            double ang = rnd.nextDouble() * 2 * Math.PI, r = rmin + rnd.nextDouble() * (rmax - rmin);
            int i = (int) (b.i + r * Math.sin(ang) / m.ey), j = (int) (b.j + r * Math.cos(ang) / m.ex);
            if (!m.inside(i, j)) continue;
            double site = b.kind.equals("band") ? bandable(i, j) : campable(i, j);
            if (m.groundAt(i, j) > 1 || held[i * m.w + j] || site <= 0.02 || own(i, j) != gi) continue;
            boolean crowded = false;
            for (WorldMap.Src o : m.bases) if (o != b && dist(i, j, o.i, o.j) < 1.5) { crowded = true; break; }
            if (crowded) continue;
            double v = 0;
            for (int p = 0; p < d.pop.length; p++) {
                double dd = dist(i, j, d.pi[p], d.pj[p]);
                if (dd > b.reach) continue;
                double wk = Math.exp(-dd / (b.reach / 2)) * b.fighters;
                v += people(p) * wk / Math.max(others[p] + wk, 1e-12);
            }
            if (best == null || v > best.v) best = new Site(i, j, v);
        }
        return best;
    }

    private double lim(WorldMap.Src o) { return o.kind.equals("band") ? d.bandMax : o.kind.equals("camp") ? d.campMax : 1e9; }

    void where(Supply sp, Map<Integer, Double> sat) {
        List<WorldMap.Src> news = new ArrayList<>();
        for (Map.Entry<Integer, Double> e : sat.entrySet()) {
            WorldMap.Src b = m.bases.get(e.getKey());
            if (b.gone || b.cleared) continue;
            double s = e.getValue(), mx = lim(b), mn = b.kind.equals("band") ? d.bandMin - 2 : d.campMin - 4;
            double gotK = sp.got()[e.getKey()];
            boolean look = s < d.moveBelow || rnd.nextDouble() < d.opportunity;
            if ((b.kind.equals("camp") || b.kind.equals("band")) && look && week - b.moved >= d.moveEvery) {
                Site c = candidate(b, 2.0, 10.0, sp.w(), sp.fig());
                if (c != null && c.v > gotK * (s < d.moveBelow ? 1.2 : 1.5)) { b.i = c.i; b.j = c.j; b.moved = week; moves++; }
            }
            if (b.kind.equals("seat")) {
                double surplus = b.fighters - b.start;
                if (surplus > Math.max(d.campMin + 5, 0.25 * b.start)) {
                    Site c = candidate(b, 3.0, 10.0, sp.w(), sp.fig());
                    if (c != null) {
                        double give = Math.min(surplus, d.campMax);
                        b.fighters -= give;
                        WorldMap.Src nb = new WorldMap.Src(b.key, "camp", b.group, b.type, c.i, c.j, give, 0, b.party, Math.min(b.reach, 8), true, false);
                        nb.moved = week; nb.start = give;
                        news.add(nb); splits++;
                    }
                }
            } else if (b.fighters > mx) {
                Site c = candidate(b, 3.0, 8.0, sp.w(), sp.fig());
                if (c != null) {
                    double half = b.fighters / 2;
                    b.fighters = half;
                    WorldMap.Src nb = b.copy();
                    nb.i = c.i; nb.j = c.j; nb.fighters = half; nb.moved = week;
                    news.add(nb); splits++;
                } else b.fighters = mx;   // no ground to split onto: the extra drift off to the villages
            }
            if ((b.kind.equals("camp") || b.kind.equals("band")) && b.fighters < mn) {
                WorldMap.Src mate = null;
                double md = Double.MAX_VALUE;
                for (WorldMap.Src o : m.bases) {
                    if (o == b || o.gone || o.cleared || !o.group.equals(b.group) || o.fighters + b.fighters > lim(o)) continue;
                    double dd = (o.i - b.i) * (o.i - b.i) + (o.j - b.j) * (o.j - b.j);
                    if (dd < md) { md = dd; mate = o; }
                }
                if (mate != null) mate.fighters += b.fighters;
                b.gone = true; joins++;
            }
        }
        m.bases.removeIf(b -> b.gone);
        m.bases.addAll(news);
    }

    // ------------------------------------------------------------------ 4. the creatures
    /** The living within 3 km of a sector (the hunting pressure), refreshed each quarter. */
    double hunters(int i, int j) {
        if (hunt == null || huntWeek + 13 <= week) { hunt = new double[m.w * m.h]; huntWeek = week; }
        int k = i * m.w + j;
        if (hunt[k] == 0) {
            double s = 0;
            for (int p = 0; p < d.pop.length; p++) if (dist(d.pi[p], d.pj[p], i, j) <= 3) s += people(p);
            hunt[k] = s + 1e-9;
        }
        return hunt[k];
    }

    void creatures() {
        int wk = week % 52;
        Map<String, Double> tot = new HashMap<>();
        for (WorldMap.Src l : m.lairs) if (!l.boss) tot.merge(l.kind, l.animals, Double::sum);
        for (WorldMap.Src l : m.lairs) {
            WorldMap.TickData.Creature c = d.creatures.get(l.kind);
            if (l.boss || c == null) continue;
            if (wk >= 5 && wk < 18) l.animals *= Math.pow(1 - c.winterLoss(), 1 / 13.0);
            if (wk == c.birthWeek()) l.animals *= 1 + c.growth();
            double d0 = 1 - Math.pow((1 - c.winterLoss()) * (1 + c.growth()), -1 / 52.0);
            double n = tot.getOrDefault(l.kind, 0.0) / Math.max(1e-9, d.k * startAnimals.getOrDefault(l.kind, 1.0));
            l.animals *= 1 - d0 * n;
        }
        for (WorldMap.Src l : m.lairs)
            if (l.kind.equals("boar") || l.kind.equals("dogs") || l.kind.equals("wolves")) l.animals *= Math.max(0, 1 - d.hunt * hunters(l.i, l.j) / 1000);
        List<WorldMap.Src> founded = new ArrayList<>();
        for (WorldMap.Src l : m.lairs) {
            WorldMap.TickData.Creature c = d.creatures.get(l.kind);
            if (l.boss || c == null) continue;
            double cap = l.cap > 0 ? l.cap : c.group() * 1.6;
            while (l.animals > cap && founded.size() < 400) {
                int[] site = null;
                for (int t = 0; t < 30 && site == null; t++) {
                    double ang = rnd.nextDouble() * 2 * Math.PI, r = 0.5 + rnd.nextDouble() * (c.disp() - 0.5);
                    int i = (int) (l.i + r * Math.sin(ang) / m.ey), j = (int) (l.j + r * Math.cos(ang) / m.ex);
                    if (!m.inside(i, j)) continue;
                    double h = 0;
                    for (Map.Entry<String, Double> e : c.hab().entrySet()) h += hab(e.getKey(), i, j) * e.getValue();
                    if (h > 0.3) site = new int[]{i, j};
                }
                if (site == null) break;   // no free ground near: they stay and crowd (the density deaths take it)
                l.animals -= c.group();
                WorldMap.Src nl = new WorldMap.Src(l.key, l.kind, "", "creature", site[0], site[1], 0, c.group(), l.party, l.reach, true, true);
                nl.cap = c.group() * 1.6;
                founded.add(nl);
            }
        }
        m.lairs.removeIf(l -> !l.boss && l.animals < 0.5);
        m.lairs.addAll(founded);
        Map<String, Integer> cnt = new HashMap<>();
        for (WorldMap.Src l : m.lairs) if (!l.boss) cnt.merge(l.kind, 1, Integer::sum);
        for (Map.Entry<String, Integer> e : cnt.entrySet()) {
            int limit = (int) (startCount.getOrDefault(e.getKey(), 0) * 1.6) + 1;
            if (e.getValue() <= limit) continue;
            List<WorldMap.Src> mine = new ArrayList<>();
            for (WorldMap.Src l : m.lairs) if (l.kind.equals(e.getKey()) && !l.boss) mine.add(l);
            mine.sort(Comparator.comparingDouble(l -> l.animals));
            m.lairs.removeAll(mine.subList(0, e.getValue() - limit));
        }
    }

    /** One week. */
    void step() {
        m.bases.removeIf(b -> b.cleared);   // a base the GM cleared is gone (clear() took its share already)
        Supply sp = supply();
        Map<Integer, Double> sat = feed(sp.got());
        where(sp, sat);
        creatures();
        week++;
    }

    // ------------------------------------------------------------------ the GM's
    /** Every raider base within km of (i, j) is gone; what it held comes off the region's raiders for good; a group with no base left
     *  leaves its ground to whoever moves in. Returns {bases, fighters}. */
    double[] clear(double i0, double j0, double km) {
        double before = raiders(), n = 0, f = 0;
        java.util.Set<String> lost = new java.util.HashSet<>();
        for (WorldMap.Src b : m.bases) {
            if (b.cleared || !b.type.equals("raiders") || dist(b.i + 0.5, b.j + 0.5, i0, j0) > km) continue;
            b.cleared = true; n++; f += b.fighters; lost.add(b.group);
        }
        double after = raiders();
        clearedShare *= after / Math.max(1e-9, before);
        for (WorldMap.Src b : m.bases) if (!b.cleared) lost.remove(b.group);
        for (String g : lost) {
            int gi = d.groups.indexOf(g);
            if (gi < 0) continue;
            for (int k = 0; k < d.own.length; k++) if ((d.own[k] & 0xff) - 1 == gi) d.own[k] = 0;   // -1 + 1
        }
        return new double[]{n, f};
    }

    /** A guard post holds the ground within km: no camp moves or splits into it. Returns the sectors held. */
    int hold(double i0, double j0, double km) {
        int n = 0;
        for (int i = 0; i < m.h; i++) for (int j = 0; j < m.w; j++) if (dist(i + 0.5, j + 0.5, i0, j0) <= km && !held[i * m.w + j]) { held[i * m.w + j] = true; n++; }
        return n;
    }

    // ------------------------------------------------------------------ the state
    void save(Path file) {
        JsonObject o = new JsonObject();
        o.addProperty("week", week); o.addProperty("need", need); o.addProperty("raiders0", raiders0); o.addProperty("clearedShare", clearedShare);
        Gson g = new Gson();
        o.add("startCount", g.toJsonTree(startCount)); o.add("startAnimals", g.toJsonTree(startAnimals));
        byte[] h = new byte[held.length];
        for (int k = 0; k < held.length; k++) h[k] = (byte) (held[k] ? 1 : 0);
        o.addProperty("held", Base64.getEncoder().encodeToString(h));
        o.addProperty("own", Base64.getEncoder().encodeToString(d.own));
        o.add("bases", list(m.bases));
        o.add("lairs", list(m.lairs));
        Store.save(file, o);   // the next save tries again if the disk refused
    }

    private static JsonArray list(List<WorldMap.Src> l) {
        JsonArray a = new JsonArray();
        for (WorldMap.Src s : l) {
            JsonObject o = new JsonObject();
            o.addProperty("key", s.key); o.addProperty("kind", s.kind); o.addProperty("group", s.group); o.addProperty("type", s.type);
            o.addProperty("i", s.i); o.addProperty("j", s.j); o.addProperty("fighters", s.fighters); o.addProperty("animals", s.animals);
            o.addProperty("party", s.party); o.addProperty("reach", s.reach); o.addProperty("hidden", s.hidden); o.addProperty("lair", s.lair);
            o.addProperty("cleared", s.cleared); o.addProperty("start", s.start); o.addProperty("cap", s.cap); o.addProperty("moved", s.moved);
            o.addProperty("boss", s.boss);
            a.add(o);
        }
        return a;
    }

    private static List<WorldMap.Src> unlist(JsonArray a) {
        List<WorldMap.Src> l = new ArrayList<>();
        for (JsonElement e : a) {
            JsonObject o = e.getAsJsonObject();
            WorldMap.Src s = new WorldMap.Src(o.get("key").getAsString(), o.get("kind").getAsString(), o.get("group").getAsString(), o.get("type").getAsString(),
                    o.get("i").getAsInt(), o.get("j").getAsInt(), o.get("fighters").getAsDouble(), o.get("animals").getAsDouble(),
                    o.get("party").getAsDouble(), o.get("reach").getAsDouble(), o.get("hidden").getAsBoolean(), o.get("lair").getAsBoolean());
            s.cleared = o.get("cleared").getAsBoolean(); s.start = o.get("start").getAsDouble(); s.cap = o.get("cap").getAsDouble();
            s.moved = o.get("moved").getAsInt(); s.boss = o.get("boss").getAsBoolean();
            l.add(s);
        }
        return l;
    }

    /** The saved state onto the map (its bases and lairs replaced), or a new tick from the map's start. */
    static Tick load(WorldMap m, Path file, Random rnd) {
        Tick t = new Tick(m, rnd);
        if (file == null || !Files.isRegularFile(file)) return t;
        try {
            JsonObject o = JsonParser.parseString(Store.readText(file)).getAsJsonObject();
            m.bases.clear(); m.bases.addAll(unlist(o.getAsJsonArray("bases")));
            m.lairs.clear(); m.lairs.addAll(unlist(o.getAsJsonArray("lairs")));
            t.week = o.get("week").getAsInt();
            t.need = o.get("need").getAsDouble(); t.raiders0 = o.get("raiders0").getAsDouble(); t.clearedShare = o.get("clearedShare").getAsDouble();
            t.startCount.clear(); t.startAnimals.clear();
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("startCount").entrySet()) t.startCount.put(e.getKey(), e.getValue().getAsInt());
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("startAnimals").entrySet()) t.startAnimals.put(e.getKey(), e.getValue().getAsDouble());
            byte[] h = Base64.getDecoder().decode(o.get("held").getAsString());
            for (int k = 0; k < Math.min(h.length, t.held.length); k++) t.held[k] = h[k] != 0;
            byte[] own = Base64.getDecoder().decode(o.get("own").getAsString());
            if (own.length == m.tick.own.length) System.arraycopy(own, 0, m.tick.own, 0, own.length);
        } catch (IOException | RuntimeException e) { /* a broken state: the map's start */ }
        return t;
    }

    /** A line for the GM. */
    String summary() {
        int camps = 0, bands = 0;
        for (WorldMap.Src b : m.bases) if (b.type.equals("raiders")) { if (b.kind.equals("camp")) camps++; else if (b.kind.equals("band")) bands++; }
        Map<String, Double> an = new HashMap<>();
        for (WorldMap.Src l : m.lairs) if (!l.boss) an.merge(l.kind, l.animals, Double::sum);
        List<String> cr = new ArrayList<>();
        for (Map.Entry<String, Double> e : startAnimals.entrySet()) cr.add(e.getKey() + " " + Math.round(100 * an.getOrDefault(e.getKey(), 0.0) / e.getValue()) + " %");
        return String.format(java.util.Locale.ROOT, "week %d (%.2f years after day D): %d raiders, %d hidden camps, %d bands; moves %d, splits %d, joins %d; creatures of the start: %s",
                week, years(), Math.round(raiders()), camps, bands, moves, splits, joins, String.join(", ", cr));
    }
}
