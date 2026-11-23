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
 *   can be GONE ROUND (only the Survival check's XP, 100 − the chance, to the scout: 06b §3, restated by Marek 11-23), TALKED TO (the
 *   people: a bonus for the first sight, {@link Talks}) or AMBUSHED (09d round 2, Marek 11-23: *"would it be possible to give players the
 *   option to ambush them instead"*: on the battle map, the party acts first); one not seen is an ambush ON THE PARTY, they act
 *   first;</li>
 *   <li><b>every meeting of the window at once</b> (09d round 2: *"I think it would be possible to have more encounters at once at one
 *   battle map. And sight can be turned to hunt, if players want to."*): the count by Poisson on the window's hazard (at least one, at
 *   most {@link #AT_ONCE}), all of them one scene on one battle map ({@link Arena}, the terrain where they met); a SIGHT can be HUNTED
 *   (the animal runs unless it is the hostile kind).</li>
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
    /** One meeting of the scene: what, whether it was seen in time, whether the party hunts it (a sight). */
    static final class Met {
        final WorldMap.Encounter e;
        final boolean seen;
        boolean hunt;
        Met(WorldMap.Encounter e, boolean seen) { this.e = e; this.seen = seen; }
        boolean hostile() { return e.type().equals("fight"); }
    }

    /** The scene that stopped the journey (every meeting of that window), the terrain where they met, and where they were going. */
    static final List<Met> scene = new ArrayList<>();
    static String sceneTerrain = "field";
    static String dest = "";
    /** The most meetings one window puts into one scene; the battle map grows with them (09d round 3) [EXAMPLE, mine]. */
    static final int AT_ONCE = 8;
    static final List<Integer> cleared = new ArrayList<>();
    static final double STEP_KM = 0.2, WINDOW_H = 4, MARCH_H = 8, PARTY_BLOCKS = 50;

    // ------------------------------------------------------------------ state
    static void load(Path dataFolder) {
        file = dataFolder.resolve("travel.json");
        map = WorldMap.load(dataFolder.resolve("worldmap.json"));
        pi = pj = Double.NaN; season = 3; marched = 0; marchDay = Integer.MIN_VALUE; scene.clear(); dest = ""; cleared.clear();
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

    static void use(WorldMap m) { map = m; pi = pj = Double.NaN; season = 3; marched = 0; marchDay = Integer.MIN_VALUE; scene.clear(); cleared.clear(); start(); }

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
        pi = q.i() + 0.5; pj = q.j() + 0.5; scene.clear(); save();
        MapView.moved();
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
    record Walk(double i, double j, double hours, double km, WorldMap.Encounter enc, String why, List<WorldMap.Encounter> encs) {
        Walk(double i, double j, double hours, double km, WorldMap.Encounter enc, String why) { this(i, j, hours, km, enc, why, enc == null ? List.of() : List.of(enc)); }
    }

    /** How many met in a window that met someone: Poisson on its hazard, at least one, at most {@link #AT_ONCE}. */
    static int meetings(double hazard, Random rnd) {
        double p0 = Math.exp(-hazard), u = p0 + rnd.nextDouble() * (1 - p0), pk = p0, cum = p0;
        int k = 0;
        while (cum < u && k < AT_ONCE) { k++; pk *= hazard / k; cum += pk; }
        return Math.max(1, Math.min(AT_ONCE, k));
    }

    /** The window's meetings from one table, the same kind and type as one bigger group. */
    static List<WorldMap.Encounter> drawAll(List<WorldMap.Row> rows, int n, Random rnd) {
        List<WorldMap.Encounter> l = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            WorldMap.Encounter e = WorldMap.draw(rows, rnd);
            if (e == null) continue;
            boolean merged = false;
            for (int m = 0; m < l.size() && !merged; m++) {
                WorldMap.Encounter o = l.get(m);
                if (o.kind() == e.kind() && o.type().equals(e.type())) { l.set(m, new WorldMap.Encounter(o.kind(), o.type(), o.count() + e.count())); merged = true; }
            }
            if (!merged) l.add(e);
        }
        return l;
    }

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
                    List<WorldMap.Encounter> all = drawAll(map.table(ec[0], ec[1], season, eday), meetings(hazard, rnd), rnd);
                    return new Walk(ei, ej, at[2], stepKm * (kk + 1), all.isEmpty() ? null : all.get(0), "an encounter", all);
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
        if (!scene.isEmpty()) return "! an encounter waits (" + sceneShort() + "): the menu (go round, ambush, hunt, or go on)";
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
        double fi = pi, fj = pj;
        pi = wk.i(); pj = wk.j();
        MapView.walk(pl, fi, fj, pi, pj, (int) Math.min(80, Math.max(10, wk.km() * 4)));   // on Arag's world: 4 ticks a km, 0.5 to 4 s
        dest = to.name();
        StringBuilder out = new StringBuilder(String.format(Locale.ROOT, "%.1f km in %s toward %s: now %s, %s.", wk.km(), Hud.clock(wk.hours() * 60),
                to.name(), where(), lead.timeText()));
        if (!wk.encs().isEmpty()) {
            double seeAt = Math.min(95, best(cs, "Survival"));
            scene.clear();
            for (WorldMap.Encounter e : wk.encs()) scene.add(new Met(e, !e.type().equals("fight") || rnd.nextInt(100) < seeAt));
            int[] c0 = cell(pi, pj);
            sceneTerrain = map.terrainAt(c0[0], c0[1]);
            out.append("\n").append(sceneText());
            List<String> gm = new ArrayList<>();
            for (Met m : scene) gm.add(m.e.kind().key() + " (" + m.e.type() + ", " + m.e.count() + (m.hostile() ? (m.seen ? ", spotted" : ", AMBUSH") : "") + ")");
            tellGm("travel: " + String.join(" + ", gm) + " near " + where() + "; the area's table: /travel table");
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

    // ------------------------------------------------------------------ the scene
    static boolean anyHostile() { for (Met m : scene) if (m.hostile()) return true; return false; }
    /** A hostile group nobody saw in time: they attack first. */
    static boolean ambushed() { for (Met m : scene) if (m.hostile() && !m.seen) return true; return false; }
    static boolean anyHunted() { for (Met m : scene) if (m.hunt && !m.hostile()) return true; return false; }

    static String sceneShort() {
        List<String> l = new ArrayList<>();
        for (Met m : scene) l.add(m.e.count() + " × " + m.e.kind().name());
        return String.join(", ", l);
    }

    static String sceneText() {
        List<String> l = new ArrayList<>();
        for (Met m : scene)
            l.add((m.hostile() ? (m.seen ? "SPOTTED IN TIME: " : "AMBUSH! ") : m.hunt ? "HUNTED: " : "ENCOUNTER: ") + m.e.text() + ".");
        if (ambushed()) l.add("They act first: to the battle map (" + sceneTerrain + ").");
        else if (anyHostile()) l.add("Go round them (the Survival check's XP), talk to them (you saw them first: a bonus), or ambush them (on the battle map, you act first).");
        for (Met m : scene) if (m.e.type().equals("sight")) { l.add("A sight can be hunted (the menu)."); break; }
        return String.join("\n", l);
    }

    /**
     * Going round the hostile ones, all seen in time: ONLY THE SURVIVAL CHECK'S XP (Marek 11-23, `09d` round 3: *"When you dodge encounter
     * on the world map, you only get the Survival check XP, just like in Fallout."*): Fallout 2's 100 − the chance it had, at least 10
     * (06b §2), to the scout (the party's best Survival); the peaceful ones stay in the scene.
     */
    static String goRound(FalloutakPlugin pl, Player p) {
        if (!anyHostile() || ambushed()) return "! nothing seen in time to go round";
        List<String> what = new ArrayList<>();
        for (Met m : scene) if (m.hostile()) what.add(m.e.text());
        Player scout = p;
        double bs = -1;
        for (Player q : party(p)) { double v = pl.character(q).skill("Survival"); if (v > bs) { bs = v; scout = q; } }
        long xp = dodgeXp(bs);
        String r = pl.roster.award(scout, xp, "an encounter dodged (the Survival check)");
        tellGm("travel: went round " + String.join(", ", what) + ". " + r);
        scene.removeIf(Met::hostile);
        return "You go round them unseen: " + scout.getName() + "'s Survival check, " + xp + " XP.";
    }

    /** Fallout 2's XP for an encounter dodged: 100 − the chance it had (the best Survival, at most 95), at least 10. */
    static long dodgeXp(double bestSurvival) { return Math.max(10, 100 - Math.round(Math.min(95, Math.max(0, bestSurvival)))); }

    /** The scene's groups for the battle map: every hostile one, and the hunted animals (which run unless they are the hostile kind). */
    static List<Arena.Group> groups(boolean hunted) {
        List<Arena.Group> g = new ArrayList<>();
        for (Met m : scene) {
            if (m.hostile()) g.add(new Arena.Group(m.e.kind(), m.e.count(), false));
            else if (hunted && m.hunt) g.add(new Arena.Group(m.e.kind(), m.e.count(), true));
        }
        return g;
    }

    /**
     * To the battle map (09d round 2): AMBUSH = the hostile ones were all seen and the party attacks (the hunted animals too), the
     * party first; AMBUSHED = one was not seen, they act first; HUNT = no hostile one, the hunted animals. The scene's fought and
     * hunted groups are gone when the fight ends; the others stay.
     */
    static String battleMap(FalloutakPlugin pl, Player p, Arena.Mode mode) {
        if (scene.isEmpty()) return "! no encounter waits";
        if (mode == Arena.Mode.AMBUSH && (!anyHostile() || ambushed())) return "! nothing hostile seen in time to ambush";
        if (mode == Arena.Mode.HUNT && (anyHostile() || !anyHunted())) return anyHostile() ? "! the hostile ones first (go round them or ambush them; the hunted join an ambush)" : "! mark what you hunt first";
        if (mode == Arena.Mode.AMBUSHED && !ambushed()) return "! nobody ambushed you";
        List<Arena.Group> g = groups(mode != Arena.Mode.AMBUSHED);
        if (g.isEmpty()) return "! nobody to fight";
        String r = Arena.fight(pl, party(p), sceneTerrain, g, mode, pl.rnd.nextLong(), Travel::afterFight);
        if (!r.startsWith("!")) tellGm("travel: " + mode.name().toLowerCase(Locale.ROOT) + " on the " + sceneTerrain + " battle map: " + sceneShort());
        return r;
    }

    /** The battle map's fight is over: the fought and hunted groups leave the scene. */
    static void afterFight() {
        scene.removeIf(m -> m.hostile() || m.hunt);
        save();
    }

    /** The encounter is played out (a talk, a trade, a sight let be, or a fight the GM ran): the way is free again. */
    static String resolved() {
        if (scene.isEmpty()) return "! no encounter waits";
        String t = sceneShort();
        scene.clear();
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
            case "ambush" -> { return battleMap(pl, p, ambushed() ? Arena.Mode.AMBUSHED : Arena.Mode.AMBUSH); }
            case "hunt" -> {
                for (Met m : scene) if (m.e.type().equals("sight")) m.hunt = true;
                return anyHostile() ? "The sights are hunted: they join an ambush." : battleMap(pl, p, Arena.Mode.HUNT);
            }
            case "on" -> { return resolved(); }
            case "map", "look" -> { return MapView.viewing(p) ? MapView.back(p) : MapView.look(pl, p); }
            default -> { }
        }
        if (!gm) return "/travel [where|to <place>|push <place>|round|ambush|hunt|on|map]";
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
            default -> { return "/travel [where|to|push|round|ambush|hunt|on|map] · GM: table|season <s>|here <place>|clear [km]"; }
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
        if (!scene.isEmpty()) {
            lines.add(line("On the " + sceneTerrain + ":", NamedTextColor.GRAY));
            for (Met m : scene)
                lines.add(line((m.hostile() ? (m.seen ? "Spotted in time: " : "AMBUSH: ") : m.hunt ? "Hunted: " : "Encounter: ") + m.e.text(),
                        m.hostile() ? NamedTextColor.RED : m.hunt ? NamedTextColor.GOLD : NamedTextColor.YELLOW));
            if (ambushed()) b.add(btn("To the battle map: they act first", "an ambush on you (" + sceneTerrain + "): every hostile group of the scene", x -> { say(x, battleMap(pl, x, Arena.Mode.AMBUSHED)); }));
            else if (anyHostile()) {
                b.add(btn("Go round them", "unseen: only the Survival check's XP, to your scout (06b §3)", x -> { say(x, goRound(pl, x)); menu(pl, x); }));
                for (Met m : scene) if (m.hostile() && Talks.talks(m.e.kind()))
                    b.add(btn("Talk to " + m.e.kind().name(), "you saw them first: Rhetorics " + Battle.sign(Talks.SEEN_FIRST + Talks.HOSTILE)
                            + " (+20 the first sight, −20 they came to fight); talked down = their full kill XP, failed = the fight, they first",
                            x -> { say(x, Talks.parley(pl, x, m)); if (!pl.battle.inBattle(x)) menu(pl, x); }));
                b.add(btn("Ambush them", "the battle map (" + sceneTerrain + "): you in cover, YOU act first" + (anyHunted() ? "; the hunted animals too" : ""),
                        x -> { say(x, battleMap(pl, x, Arena.Mode.AMBUSH)); }));
            }
            for (Met m : scene) if (m.e.type().equals("talk") || m.e.type().equals("trade"))
                b.add(btn((m.e.type().equals("trade") ? "Trade with " : "Talk with ") + m.e.kind().name(),
                        m.e.type().equals("trade") ? "barter: their wares for your goods" : Talks.raiderKind(m.e.kind()) ? "they want a toll" : "news of the roads",
                        x -> Talks.talkMenu(pl, x, m)));
            for (Met m : scene) if (m.e.type().equals("sight"))
                b.add(btn((m.hunt ? "Do not hunt: " : "Hunt: ") + m.e.kind().name(), "a sight turned into a hunt: it runs unless it is the hostile kind"
                        + (anyHostile() ? " (it joins the ambush)" : ""), x -> { m.hunt = !m.hunt; menu(pl, x); }));
            if (!anyHostile() && anyHunted()) b.add(btn("Hunt now", "the battle map (" + sceneTerrain + "): you downwind, you act first", x -> { say(x, battleMap(pl, x, Arena.Mode.HUNT)); }));
            b.add(btn(anyHostile() ? "It is over (the GM ran it)" : "Done (go on)", "the encounter is played out: the way is free", x -> { say(x, resolved()); menu(pl, x); }));
            if (!dest.isEmpty() && !anyHostile()) b.add(btn("Then on to " + dest, "after the encounter", x -> { say(x, resolved()); say(x, go(pl, x, dest, false, pl.rnd)); menu(pl, x); }));
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
        if (MapView.viewing(p)) b.add(btn("Back from the world map", "to where you were", x -> { say(x, MapView.back(x)); menu(pl, x); }));
        else b.add(btn("Look at the world map", "Arag's Czechia, ~28 m a block: up over your party's banner, flying", x -> { say(x, MapView.look(pl, x)); }));
        if (p.hasPermission("falloutak.gm")) {
            b.add(btn("GM: the area's table", "who is here, by share and type (the hidden camps' too)", x -> { say(x, String.join("\n", table())); menu(pl, x); }));
            b.add(btn("GM: season " + WorldMap.SEASONS[season], "the next season", x -> { season = (season + 1) % 4; save(); menu(pl, x); }));
            b.add(btn("GM: clear 3 km", "every raider base within 3 km of the party is gone (a fight won there)", x -> { say(x, clear(3)); menu(pl, x); }));
        }
        Menus.menu(p, "Travel: the world map", lines, b, Menus::actions);
    }
}
