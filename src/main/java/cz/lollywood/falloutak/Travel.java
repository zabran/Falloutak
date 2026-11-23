package cz.lollywood.falloutak;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static cz.lollywood.falloutak.Menus.btn;
import static cz.lollywood.falloutak.Menus.line;
import static cz.lollywood.falloutak.Menus.say;
import static cz.lollywood.falloutak.Menus.tellGm;

/**
 * Part 9, WORLD-MAP TRAVEL with the encounter roll (`09` §3.2-§3.3, `09d`; Marek 11-23: *"start with testing that simulation and then
 * continue with world-map travel with the encounter roll into the plugin."*). The party (the players within 50 blocks of the one who
 * sets out) stands on a sector of Arag's map ({@link WorldMap}) and walks to a place, sector by sector:
 * <ul>
 *   <li>the speed by the terrain, `09` §3.2 (RULED: 30 / 20 / 10 km in 8 hours on a road / in the open / in forest or ruins), raised as
 *   Fallout 1's Outdoorsman by the best Survival (+0 to +100 %), halved when anyone carries over his limit; at most 8 hours of marching a
 *   day unless they push on;</li>
 *   <li>game time passes for everyone in the party (part 4's survival runs: food, water, sleep), outside the base;</li>
 *   <li><b>a roll every 4 hours of travel</b> (`09` §3.3, RULED) on the sectors walked: any encounter = 1 − e^(−meetings), placed where it
 *   fell, of every type (round 1): a FIGHT, a TALK, a TRADE or a SIGHT;</li>
 *   <li><b>spotting = Fallout 2's</b> with Survival (RULED): d100 below the party's best (at most 95) sees it in time; a hostile one seen
 *   can be GONE ROUND (each PC its kill XP, `06b`) or fought; one not seen is an ambush, they act first (the GM runs the fight).</li>
 * </ul>
 * The GM sees the area's table (who is there, by share and type), sets the season, puts the party at a place and clears bases.
 */
final class Travel {
    private Travel() {}

    static WorldMap map;
    static Path file;
    /** The party's sector (fractional), the season (0 winter .. 3 autumn; the start point is November [mine]). */
    static double pi = Double.NaN, pj = Double.NaN;
    static int season = 3;
    static double marched;
    static int marchDay = Integer.MIN_VALUE;
    /** An encounter that stopped the journey, and where they were going. */
    static WorldMap.Encounter pending;
    static boolean pendingSeen;
    static String dest = "";
    static final List<Integer> cleared = new ArrayList<>();
    static final double STEP_KM = 0.2, WINDOW_H = 4, MARCH_H = 8, PARTY_BLOCKS = 50;

    // ------------------------------------------------------------------ state
    static void load(Path dataFolder) {
        file = dataFolder.resolve("travel.json");
        map = WorldMap.load(dataFolder.resolve("worldmap.json"));
        pi = pj = Double.NaN; season = 3; marched = 0; marchDay = Integer.MIN_VALUE; pending = null; dest = ""; cleared.clear();
        if (Files.isRegularFile(file)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                pi = o.get("i").getAsDouble(); pj = o.get("j").getAsDouble(); season = o.get("season").getAsInt();
                marched = o.get("marched").getAsDouble(); marchDay = o.get("marchDay").getAsInt();
                if (o.has("cleared")) o.getAsJsonArray("cleared").forEach(e -> cleared.add(e.getAsInt()));
            } catch (IOException | RuntimeException e) { /* a broken file: start again at the start */ }
        }
        applyCleared();
        start();
    }

    static void use(WorldMap m) { map = m; pi = pj = Double.NaN; season = 3; marched = 0; marchDay = Integer.MIN_VALUE; pending = null; cleared.clear(); start(); }

    /** The party starts at Jihlava (the shelter's town), else the map's first place. */
    static void start() {
        if (map == null || !Double.isNaN(pi)) return;
        WorldMap.Place p = map.place("Jihlava");
        if (p == null && !map.places.isEmpty()) p = map.places.get(0);
        if (p != null) { pi = p.i() + 0.5; pj = p.j() + 0.5; }
    }

    /** Puts the party at a place (the GM's, and the tests'). */
    static boolean here(String name) {
        WorldMap.Place q = map == null ? null : map.place(name);
        if (q == null) return false;
        pi = q.i() + 0.5; pj = q.j() + 0.5; pending = null; save();
        return true;
    }

    static void applyCleared() {
        if (map == null) return;
        for (int k : cleared) if (k >= 0 && k < map.bases.size()) map.bases.get(k).cleared = true;
    }

    static void save() {
        if (file == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("i", pi); o.addProperty("j", pj); o.addProperty("season", season); o.addProperty("marched", marched);
        o.addProperty("marchDay", marchDay);
        o.add("cleared", new Gson().toJsonTree(cleared));
        try { Files.createDirectories(file.getParent()); Files.writeString(file, o.toString(), StandardCharsets.UTF_8); }
        catch (IOException e) { /* the next save tries again */ }
    }

    // ------------------------------------------------------------------ the party
    static List<Player> party(Player p) {
        List<Player> l = new ArrayList<>();
        l.add(p);
        for (Entity e : p.getNearbyEntities(PARTY_BLOCKS, PARTY_BLOCKS, PARTY_BLOCKS)) if (e instanceof Player q) l.add(q);
        return l;
    }

    static double best(List<Character> cs, String skill) {
        double b = 0;
        for (Character c : cs) if (!c.dead) b = Math.max(b, c.skill(skill));
        return b;
    }

    /** The speed factor: Fallout 1's Outdoorsman by the best Survival (+0 to +100 %), halved when anyone carries over his limit. */
    static double speedFactor(List<Character> cs) {
        double f = 1 + Math.min(100, Math.max(0, best(cs, "Survival"))) / 100;
        for (Character c : cs) if (c.loadTier() >= 1) { f *= 0.5; break; }
        return f;
    }

    static int[] cell(double i, double j) { return new int[]{(int) Math.floor(i), (int) Math.floor(j)}; }

    static String where() {
        if (map == null || Double.isNaN(pi)) return "nowhere on the map";
        WorldMap.Place n = map.nearest(pi, pj);
        int[] c = cell(pi, pj);
        double d = map.km(pi, pj, n.i() + 0.5, n.j() + 0.5);
        return (d < 0.5 ? "at " + n.name() : String.format(Locale.ROOT, "%.1f km from %s", d, n.name())) + " (" + map.terrainAt(c[0], c[1]) + ")";
    }

    // ------------------------------------------------------------------ the journey
    /** The result of a walk: where it stopped, the hours, the encounter if one came, and why it stopped. */
    record Walk(double i, double j, double hours, double km, WorldMap.Encounter enc, String why) {}

    /**
     * Walks from (i, j) toward the sector (ti, tj): the steps of {@link #STEP_KM}, each its terrain's time; every 4 hours (and at the
     * end) the roll on the hazard of the sectors walked since the last roll; an encounter is placed where the hazard fell. Stops at the
     * destination, at an encounter, or at the day's march limit (unless {@code push}). {@code hour0} = the time of day at the start.
     */
    static Walk walk(double i, double j, double ti, double tj, double factor, double hour0, double marchedBefore, boolean push, Random rnd) {
        double dist = map.km(i, j, ti, tj);
        int n = Math.max(1, (int) Math.ceil(dist / STEP_KM));
        double stepKm = dist / n;
        double t = 0, window = 0, hazard = 0, km = 0;
        List<double[]> steps = new ArrayList<>();          // per step since the last roll: index, cumulative hazard, end time, hour
        for (int k = 0; k < n; k++) {
            double ci = i + (ti - i) * (k + 0.5) / n, cj = j + (tj - j) * (k + 0.5) / n;
            int[] c = cell(ci, cj);
            if (!map.inside(c[0], c[1])) return new Walk(ci, cj, t, km, null, "the edge of the map");
            double h = hour0 + t;
            boolean day = !(((h % 24) + 24) % 24 >= 18 || ((h % 24) + 24) % 24 < 6);
            double dt = stepKm / (map.speed.get(map.terrainAt(c[0], c[1])) * factor);
            hazard += WorldMap.total(map.table(c[0], c[1], season, day)) * dt;
            t += dt; window += dt; km += stepKm;
            steps.add(new double[]{k, hazard, t, h});
            boolean last = k == n - 1, tired = !push && marchedBefore + t >= MARCH_H - 1e-9;
            if (window >= WINDOW_H - 1e-9 || last || tired) {
                if (hazard > 0 && rnd.nextDouble() < 1 - Math.exp(-hazard)) {
                    double u = rnd.nextDouble() * hazard;
                    double[] at = steps.get(steps.size() - 1);
                    for (double[] s : steps) if (s[1] >= u) { at = s; break; }
                    int kk = (int) at[0];
                    double ei = i + (ti - i) * (kk + 0.5) / n, ej = j + (tj - j) * (kk + 0.5) / n;
                    int[] ec = cell(ei, ej);
                    double eh = at[3];
                    boolean eday = !(((eh % 24) + 24) % 24 >= 18 || ((eh % 24) + 24) % 24 < 6);
                    WorldMap.Encounter e = WorldMap.draw(map.table(ec[0], ec[1], season, eday), rnd);
                    return new Walk(ei, ej, at[2], stepKm * (kk + 1), e, "an encounter");
                }
                window = 0; hazard = 0; steps.clear();
            }
            if (tired && !last) return new Walk(ci, cj, t, km, null, "8 hours marched today");
        }
        return new Walk(ti, tj, t, km, null, "arrived");
    }

    /** The party sets out (or goes on) to a place. Returns what happened. */
    static String go(FalloutakPlugin pl, Player p, String placeName, boolean push, Random rnd) {
        if (map == null) return "! no world map is loaded (worldmap.json from fallout_danger_sim.py --export in the plugin's folder)";
        if (pending != null) return "! an encounter waits (" + pending.text() + "): go round it, fight it, or go on (the menu)";
        WorldMap.Place to = map.place(placeName);
        if (to == null) return "! no such place: " + placeName;
        List<Player> ps = party(p);
        List<Character> cs = new ArrayList<>();
        for (Player q : ps) cs.add(pl.character(q));
        Character lead = pl.character(p);
        if (lead.day() != marchDay) { marchDay = lead.day(); marched = 0; }
        if (!push && marched >= MARCH_H - 1e-9) return "! 8 hours marched today: rest, or push on (sleep debt, part 4)";
        Walk wk = walk(pi, pj, to.i() + 0.5, to.j() + 0.5, speedFactor(cs), lead.hourOfDay(), marched, push, rnd);
        // time passes for the whole party, outside the base
        List<String> news = new ArrayList<>();
        for (Character c : cs) {
            c.outside = true;
            String r = Hud.pass(c, wk.hours() * 60);
            if (r != null) news.add(c.name + ": " + r);
        }
        if (lead.day() != marchDay) { marchDay = lead.day(); marched = 0; }
        marched += wk.hours();
        pi = wk.i(); pj = wk.j();
        dest = to.name();
        StringBuilder out = new StringBuilder(String.format(Locale.ROOT, "%.1f km in %s toward %s: now %s, %s.", wk.km(), Hud.clock(wk.hours() * 60),
                to.name(), where(), lead.timeText()));
        if (wk.enc() != null) {
            pending = wk.enc();
            boolean fight = pending.type().equals("fight");
            double seeAt = Math.min(95, best(cs, "Survival"));
            pendingSeen = rnd.nextInt(100) < seeAt;
            out.append("\n").append(fight ? (pendingSeen ? "SPOTTED IN TIME: " : "AMBUSH! ") : "ENCOUNTER: ").append(pending.text())
                    .append(fight ? (pendingSeen ? ". Go round it (each of you its kill XP) or fight it." : ". They act first: the GM sets the fight.")
                    : ".");
            tellGm("travel: " + pending.kind().key() + " (" + pending.type() + ", " + pending.count() + ")" + (fight ? (pendingSeen ? ", spotted" : ", AMBUSH") : "")
                    + " near " + where() + "; the area's table: /travel table");
        } else out.append(" ").append(switch (wk.why()) {
            case "arrived" -> "Arrived.";
            case "8 hours marched today" -> "8 hours marched today: rest, or push on.";
            default -> "Stopped: " + wk.why() + ".";
        });
        if (!news.isEmpty()) out.append("\n").append(String.join("\n", news));
        save();
        String msg = out.toString();
        for (Player q : ps) if (q != p) say(q, msg);
        return msg;
    }

    /** Going round a hostile encounter seen in time: each PC its kill XP (`06b`, RULED). */
    static String goRound(FalloutakPlugin pl, Player p) {
        if (pending == null || !pending.type().equals("fight") || !pendingSeen) return "! nothing seen in time to go round";
        List<Double> hps = new ArrayList<>();
        for (int k = 0; k < pending.count(); k++) hps.add(pending.kind().hp());
        String r = pl.roster.killAward(party(p), hps, "an encounter avoided");
        tellGm("travel: went round " + pending.text() + ". " + r);
        pending = null;
        return "You go round them unseen.";
    }

    /** The encounter is played out (a fight, a talk, a trade, a sight): the way is free again. */
    static String resolved() {
        if (pending == null) return "! no encounter waits";
        String t = pending.text();
        pending = null;
        return "Done with: " + t;
    }

    // ------------------------------------------------------------------ the GM's
    /** The area's table at the party's sector: every kind with its meetings in 4 hours and its type. */
    static List<String> table() {
        List<String> l = new ArrayList<>();
        if (map == null || Double.isNaN(pi)) { l.add("no world map"); return l; }
        int[] c = cell(pi, pj);
        for (boolean day : new boolean[]{true, false}) {
            List<WorldMap.Row> rows = map.table(c[0], c[1], season, day);
            double tot = WorldMap.total(rows);
            var ty = map.types(rows);
            l.add(String.format(Locale.ROOT, "%s, %s: an encounter %.0f %% in 4 h (a fight %.0f %%)", WorldMap.SEASONS[season], day ? "by day" : "at night",
                    100 * (1 - Math.exp(-tot * WINDOW_H)), 100 * (1 - Math.exp(-ty.get("fight") * WINDOW_H))));
            for (WorldMap.Row r : rows) {
                if (r.all() / Math.max(1e-12, tot) < 0.02) continue;
                l.add(String.format(Locale.ROOT, "  %s %.0f %% (fight %.0f %% of them)", r.kind().name(), 100 * r.all() / tot, 100 * r.fight() / r.all()));
            }
        }
        l.add("ground: " + new String[]{"clean", "light", "medium", "heavy"}[Math.max(0, Math.min(3, map.groundAt(c[0], c[1])))]);
        return l;
    }

    /** The GM clears every raider base within {@code km} of the party (a fight won there); held ground is the plugin's tick's, later. */
    static String clear(double km) {
        if (map == null) return "! no world map";
        int n = 0;
        double f = 0;
        for (int k = 0; k < map.bases.size(); k++) {
            WorldMap.Src b = map.bases.get(k);
            if (b.cleared || !b.type.equals("raiders") || map.km(pi, pj, b.i + 0.5, b.j + 0.5) > km) continue;
            b.cleared = true; cleared.add(k); n++; f += b.fighters;
        }
        save();
        return String.format(Locale.ROOT, "%d raider bases (%.0f fighters) within %.1f km cleared", n, f, km);
    }

    static String command(FalloutakPlugin pl, Player p, String[] a) {
        String arg = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        boolean gm = p.hasPermission("falloutak.gm");
        switch (arg) {
            case "" -> { menu(pl, p); return null; }
            case "where" -> { return "The party is " + where() + "; " + WorldMap.SEASONS[season] + "; marched today " + Hud.clock(marched * 60) + "."; }
            case "to", "push" -> {
                if (a.length < 2) return "/travel to <place>";
                return go(pl, p, String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)), arg.equals("push"), pl.rnd);
            }
            case "round" -> { return goRound(pl, p); }
            case "on" -> { return resolved(); }
            default -> { }
        }
        if (!gm) return "/travel [where|to <place>|push <place>|round|on]";
        switch (arg) {
            case "table" -> { return String.join("\n", table()); }
            case "season" -> {
                if (a.length > 1) for (int k = 0; k < 4; k++) if (WorldMap.SEASONS[k].startsWith(a[1].toLowerCase(Locale.ROOT))) season = k;
                save();
                return "The season: " + WorldMap.SEASONS[season];
            }
            case "here" -> {
                if (a.length < 2 || !here(String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)))) return "! /travel here <place>";
                return "The party is now " + where();
            }
            case "clear" -> { return clear(a.length > 1 ? Double.parseDouble(a[1]) : 3); }
            default -> { return "/travel [where|to|push|round|on] · GM: table|season <s>|here <place>|clear [km]"; }
        }
    }

    // ------------------------------------------------------------------ the menu
    static void menu(FalloutakPlugin pl, Player p) {
        List<Component> lines = new ArrayList<>();
        List<Ui.Btn> b = new ArrayList<>();
        if (map == null) {
            lines.add(line("No world map is loaded: worldmap.json (fallout_danger_sim.py --export) belongs in the plugin's folder.", NamedTextColor.RED));
            Menus.menu(p, "Travel", lines, b, Menus::actions);
            return;
        }
        Character c = pl.character(p);
        if (c.day() != marchDay) { marchDay = c.day(); marched = 0; }
        lines.add(line("The party (you and the players within " + (int) PARTY_BLOCKS + " blocks) is " + where() + ".", NamedTextColor.WHITE));
        lines.add(line(c.timeText() + " · " + WorldMap.SEASONS[season] + " · marched today " + Hud.clock(marched * 60) + " of 8 h", NamedTextColor.GRAY));
        if (pending != null) {
            boolean fight = pending.type().equals("fight");
            lines.add(line((fight ? (pendingSeen ? "Spotted in time: " : "AMBUSH: ") : "Encounter: ") + pending.text(), fight ? NamedTextColor.RED : NamedTextColor.GOLD));
            if (fight && pendingSeen) b.add(btn("Go round them", "unseen: each of you gets their kill XP (06b)", x -> { say(x, goRound(pl, x)); menu(pl, x); }));
            b.add(btn(fight ? "It is over (fought)" : "Done (go on)", "the encounter is played out: the way is free", x -> { say(x, resolved()); menu(pl, x); }));
            if (!dest.isEmpty()) b.add(btn("Then on to " + dest, "after the encounter", x -> { say(x, resolved()); say(x, go(pl, x, dest, false, pl.rnd)); menu(pl, x); }));
        } else {
            List<WorldMap.Place> near = new ArrayList<>(map.places);
            near.sort(Comparator.comparingDouble(q -> map.km(pi, pj, q.i() + 0.5, q.j() + 0.5)));
            int shown = 0;
            for (WorldMap.Place q : near) {
                double d = map.km(pi, pj, q.i() + 0.5, q.j() + 0.5);
                if (d < 0.5) continue;
                if (++shown > 14) break;
                b.add(btn(String.format(Locale.ROOT, "To %s (%.1f km)", q.name(), d), "set out: a roll every 4 hours; at most 8 hours a day",
                        x -> { say(x, go(pl, x, q.name(), false, pl.rnd)); menu(pl, x); }));
            }
            if (marched >= MARCH_H - 1e-9 && !dest.isEmpty())
                b.add(btn("Push on to " + dest, "past the day's 8 hours (sleep debt, part 4)", x -> { say(x, go(pl, x, dest, true, pl.rnd)); menu(pl, x); }));
        }
        if (p.hasPermission("falloutak.gm")) {
            b.add(btn("GM: the area's table", "who is here, by share and type (the hidden camps' too)", x -> { say(x, String.join("\n", table())); menu(pl, x); }));
            b.add(btn("GM: season " + WorldMap.SEASONS[season], "the next season", x -> { season = (season + 1) % 4; save(); menu(pl, x); }));
            b.add(btn("GM: clear 3 km", "every raider base within 3 km of the party is gone (a fight won there)", x -> { say(x, clear(3)); menu(pl, x); }));
        }
        Menus.menu(p, "Travel: the world map", lines, b, Menus::actions);
    }
}
