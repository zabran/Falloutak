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
import java.util.Set;
import java.util.TreeSet;

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
 *   <li><b>spotting = Fallout 2's</b> with Survival (RULED): d100 below the party's best (at most 95) less the check's difficulty, EACH
 *   GROUP ITS OWN (09d round 5, Marek 11-24: *"based on terrain, weather, daytime and other conditions, the difficulty should should go
 *   up +50. Also, the check should go counter to enemy stealth check, if the enemy is trying for stealth. Without stealth, it should
 *   instead check how much visible and audible the target is."*): the conditions ({@link #conditions}, at most +50) and the group's
 *   stealth or its visibility ({@link #targetTerm}) sees it in time; a hostile one seen can be GONE ROUND (only the Survival check's XP = ITS DIFFICULTY, at
 *   least 10: Marek 11-24, *"unlike in fallout, here it should be basic skill check XP, i.e. as much as was difficulty"*), TALKED TO ON
 *   THE BATTLE MAP (the people: one speaker, a bonus for the first sight, {@link Talks}, {@link Battle.Talk}) or AMBUSHED (09d round 2, Marek 11-23: *"would it be possible to give players the
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
        /** Seen in time to ambush or talk (the plain check); {@code glimpsed} = seen well enough only to go round (Survival +30, 09d round 6). */
        final boolean seen, glimpsed;
        boolean hunt;
        /** Its spotting check's difficulty (the go-round pays it), and whether it was sneaking (09d round 5). */
        int difficulty;
        boolean sneaking;
        Met(WorldMap.Encounter e, boolean seen) { this(e, seen, seen); }
        Met(WorldMap.Encounter e, boolean seen, boolean glimpsed) { this.e = e; this.seen = seen; this.glimpsed = seen || glimpsed; }
        boolean hostile() { return e.type().equals("fight"); }
    }

    /**
     * Going round needs less than an ambush (09d round 6, Marek 11-24: *"avoid on world map should be easier than ambush. So consider
     * +30% bonus to Survival for simple avoid as opposed to ambush."*): the same roll, the go-round's threshold Survival +30 (at most 95).
     */
    static final int AVOID_BONUS = 30;
    /** The party's noise beyond its size (a vehicle, a cart: none yet) [mine]. */
    static final int PARTY_LOUD = 0;
    /**
     * THE FEUDS (09d round 6, Marek 11-24: *"They only attack first if it is monster or an enemy already shoots them on sight."*): the
     * people the party struck first (an ambush, a blow in a talk) shoot it on sight; the rest ask a toll first ({@link #tollFirst}).
     */
    static final Set<String> feuds = new TreeSet<>();
    /** The kind the party is talking to on the battle map (a blow in the talk starts a feud with it). */
    static String talkKind = "";

    /** The scene that stopped the journey (every meeting of that window), the terrain where they met, and where they were going. */
    static final List<Met> scene = new ArrayList<>();
    /**
     * 05d §7c (*"Spike pit: outside a fight (dug ~30 min)"*, *"Deadfall: outside a fight"*): the pits and deadfalls the party dug while
     * the scene waited (an ambush seen in time, or a hunt): the trap, its digger and his skill of its kind then. Laid on the battle map on
     * the enemies' way ({@link Arena#layDug}); left behind when the party goes round or on.
     */
    record Dug(Data.TrapDef def, java.util.UUID setter, double skill) {}
    static final List<Dug> dug = new ArrayList<>();
    /** The digging's minutes [the pit's ~30 RULED with the table; the deadfall's 20 mine, EXAMPLE]. */
    static int digMinutes(String trap) { return trap.equals("Spike pit") ? 30 : 20; }
    static String sceneTerrain = "field";
    /** The spotting check's difficulty where and when the scene met the party (the go-round's XP). */
    static int sceneDifficulty;
    static String dest = "";
    /** The most meetings one window puts into one scene; the battle map grows with them (09d round 3) [EXAMPLE, mine]. */
    static final int AT_ONCE = 8;
    static final List<Integer> cleared = new ArrayList<>();
    /** The weekly tick (`09d` §6, round 3: *"the weekly tick inside the plugin"*), its state file, and the game day it last ran to. */
    static Tick tick;
    static Path tickFile;
    static int tickDay = Integer.MIN_VALUE;
    static final double STEP_KM = 0.2, WINDOW_H = 4, MARCH_H = 8, PARTY_BLOCKS = 50;
    /** The day's weather on the world map (09d round 5): drawn each day by the season, unless the GM set it. */
    static final String[] WEATHER = {"clear", "rain", "fog", "snow", "black rain"};
    /** The day's weather's shares by season (winter .. autumn) over {@link #WEATHER} [mine, EXAMPLE: Central Europe's in the nuclear autumn]. */
    static final double[][] WEATHER_SHARE = {{0.45, 0.10, 0.20, 0.25, 0}, {0.55, 0.30, 0.10, 0.05, 0}, {0.65, 0.30, 0.05, 0, 0}, {0.45, 0.30, 0.25, 0, 0}};
    static String weather = "clear";
    static boolean weatherSet;
    static int weatherDay = Integer.MIN_VALUE;
    /** The conditions' difficulty at most (Marek: *"the difficulty should should go up +50"*). */
    static final int CONDITIONS_MAX = 50;

    // ------------------------------------------------------------------ state
    static void load(Path dataFolder) {
        file = dataFolder.resolve("travel.json");
        map = WorldMap.load(dataFolder.resolve("worldmap.json"));
        pi = pj = Double.NaN; season = 3; marched = 0; marchDay = Integer.MIN_VALUE; scene.clear(); dug.clear(); dest = ""; cleared.clear(); feuds.clear();
        tickFile = dataFolder.resolve("worldmap-state.json");
        tick = map != null && map.tick != null ? Tick.load(map, tickFile, new Random()) : null;
        tickDay = Integer.MIN_VALUE;
        if (Files.isRegularFile(file)) {
            try {
                JsonObject o = JsonParser.parseString(Store.readText(file)).getAsJsonObject();
                pi = o.get("i").getAsDouble(); pj = o.get("j").getAsDouble(); season = o.get("season").getAsInt();
                marched = o.get("marched").getAsDouble(); marchDay = o.get("marchDay").getAsInt();
                if (o.has("cleared")) o.getAsJsonArray("cleared").forEach(e -> cleared.add(e.getAsInt()));
                if (o.has("feuds")) o.getAsJsonArray("feuds").forEach(e -> feuds.add(e.getAsString()));
                if (o.has("tickDay")) tickDay = o.get("tickDay").getAsInt();
            } catch (IOException | RuntimeException e) { /* a broken file: start again at the start */ }
        }
        applyCleared();
        start();
    }

    static void use(WorldMap m) {
        map = m; pi = pj = Double.NaN; season = 3; marched = 0; marchDay = Integer.MIN_VALUE; scene.clear(); dug.clear(); cleared.clear(); feuds.clear();
        tick = m != null && m.tick != null ? new Tick(m, new Random(1)) : null; tickFile = null; tickDay = Integer.MIN_VALUE;
        start();
    }

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
        pi = q.i() + 0.5; pj = q.j() + 0.5; scene.clear(); dug.clear(); save();
        MapView.moved();
        return true;
    }

    static void applyCleared() {
        if (map == null || tick != null) return;   // with the tick, the state file holds the bases as they are
        for (int k : cleared) if (k >= 0 && k < map.bases.size()) map.bases.get(k).cleared = true;
    }

    static void save() {
        if (file == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("i", pi); o.addProperty("j", pj); o.addProperty("season", season); o.addProperty("marched", marched);
        o.addProperty("marchDay", marchDay);
        o.addProperty("tickDay", tickDay);
        o.add("cleared", new Gson().toJsonTree(cleared));
        o.add("feuds", new Gson().toJsonTree(feuds));
        Store.save(file, o);   // the next save tries again if the disk refused
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

    /**
     * The spotting check's difficulty in % points (09d round 4: the go-round pays it as its XP, at least 10): night −20, forest or a ruined
     * town −20 (Fallout 2's tile modifier) [the numbers mine, EXAMPLE].
     */
    static int spotDifficulty(String terrain, boolean day) { return conditions(terrain, day ? 12 : 0, "clear"); }

    /** The light: day 0, dusk and dawn (an hour each side) 10, night 20 [mine, EXAMPLE]. */
    static int light(double hour) {
        double h = ((hour % 24) + 24) % 24;
        if (h >= 6 && h < 18) return 0;
        return (h >= 5 && h < 6) || (h >= 18 && h < 19) ? 10 : 20;
    }

    /** The weather: rain 10, snow 15, the black rain 15, fog 20 [mine, EXAMPLE]. */
    static int weatherTerm(String w) {
        return switch (w) { case "rain" -> 10; case "snow", "black rain" -> 15; case "fog" -> 20; default -> 0; };
    }

    /**
     * THE CONDITIONS of a spotting check (09d round 5): the light, the ground (forest or a ruined town 20, Fallout 2's tile modifier) and
     * the weather, AT MOST {@link #CONDITIONS_MAX} [the terms mine, EXAMPLE].
     */
    static int conditions(String terrain, double hour, String weather) {
        int t = terrain.equals("forest") || terrain.equals("town") ? 20 : 0;
        return Math.min(CONDITIONS_MAX, light(hour) + t + weatherTerm(weather));
    }

    /**
     * The group's own term (09d round 5): SNEAKING, their stealth check against the scout = THE WORST SNEAK IN THE GROUP, IN FULL
     * ({@link #worstSneak}; Marek 11-24: *"it should be full of the worst sneak in group"*); NOT sneaking, how visible and audible they
     * are: -5 a head over four (at most -30) and the kind's noise (a caravan's carts, a herd) [mine, EXAMPLE].
     */
    static int targetTerm(WorldMap.Kind k, int count, boolean sneaking) {
        if (sneaking) return worstSneak(k.sneak(), count);
        return -Math.min(30, 5 * Math.max(0, count - 4)) - k.loud();
    }

    /**
     * The party's own term against the enemy's senses (09d round 6, *"Should a party's own size and noise make it easier to spot?" - yes,
     * definitely*): −5 a head over four (at most −30) and its noise [mine, EXAMPLE].
     */
    static int partyTerm(int size) { return -Math.min(30, 5 * Math.max(0, size - 4)) - PARTY_LOUD; }

    /** THE TOLL FIRST (09d round 6): people never attack first unless the party struck them first; their fight is their peaceful meeting. */
    static WorldMap.Encounter tollFirst(WorldMap.Encounter e) {
        if (e == null || !e.type().equals("fight") || !e.kind().people() || feuds.contains(e.kind().key())) return e;
        return new WorldMap.Encounter(e.kind(), e.kind().peace(), e.count());
    }

    /** The clumsiest of a group: the kind's Sneak less 3 a head beyond the first, at most 15 less [mine, EXAMPLE]. */
    static int worstSneak(int sneak, int count) { return Math.max(0, sneak - Math.min(15, 3 * Math.max(0, count - 1))); }

    /** The day's weather: drawn by the season once a day, unless the GM set it. */
    static void dayWeather(int day, Random rnd) {
        if (day == weatherDay) return;
        weatherDay = day;
        if (weatherSet) return;
        double u = rnd.nextDouble(), acc = 0;
        double[] w = WEATHER_SHARE[Math.max(0, Math.min(3, season))];
        weather = WEATHER[0];
        for (int k = 0; k < WEATHER.length; k++) { acc += w[k]; if (u < acc) { weather = WEATHER[k]; break; } }
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
            WorldMap.Encounter e = tollFirst(WorldMap.draw(rows, rnd));
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
        Loot.clear();   // the party sets out: the last field's bodies stay behind
        // time passes for the whole party, outside the base
        List<String> news = new ArrayList<>();
        for (Character c : cs) {
            c.outside = true;
            String r = Hud.pass(c, wk.hours() * 60);
            if (r != null) news.add(c.name + ": " + r);
        }
        if (lead.day() != marchDay) { marchDay = lead.day(); marched = 0; }
        String weeks = catchUp(lead.day());
        if (!weeks.isEmpty()) tellGm("travel: " + weeks);
        marched += wk.hours();
        double fi = pi, fj = pj;
        pi = wk.i(); pj = wk.j();
        MapView.walk(pl, fi, fj, pi, pj, (int) Math.min(80, Math.max(10, wk.km() * 4)));   // on Arag's world: 4 ticks a km, 0.5 to 4 s
        dest = to.name();
        StringBuilder out = new StringBuilder(String.format(Locale.ROOT, "%.1f km in %s toward %s: now %s, %s.", wk.km(), Hud.clock(wk.hours() * 60),
                to.name(), where(), lead.timeText()));
        if (!wk.encs().isEmpty()) {
            int[] c0 = cell(pi, pj);
            sceneTerrain = map.terrainAt(c0[0], c0[1]);
            dayWeather(lead.day(), rnd);
            int cond = conditions(sceneTerrain, lead.hourOfDay(), weather);
            double scout = Math.min(95, best(cs, "Survival")), avoid = Math.min(95, best(cs, "Survival") + AVOID_BONUS);
            scene.clear();
            dug.clear();
            sceneDifficulty = cond;
            List<String> passed = new ArrayList<>();
            for (WorldMap.Encounter e : wk.encs()) {
                boolean fight = e.type().equals("fight");
                boolean sneaking = fight && rnd.nextDouble() < e.kind().stealth();
                int diff = cond + targetTerm(e.kind(), e.count(), sneaking);
                int roll = fight ? rnd.nextInt(100) : 0;
                boolean seen = !fight || roll < scout - diff, glimpsed = !fight || roll < avoid - diff;
                // the enemy's own check (09d round 6): a sneaking group stalks the party already; one in the open by its senses against
                // the conditions and the party's size and noise; nobody seeing anybody = they pass each other by
                if (!glimpsed && !sneaking && rnd.nextInt(100) >= Math.min(95, e.kind().notice()) - (cond + partyTerm(cs.size()))) {
                    passed.add(e.text());
                    continue;
                }
                Met m = new Met(e, seen, glimpsed);
                m.difficulty = diff;
                m.sneaking = sneaking;
                scene.add(m);
                if (fight) sceneDifficulty = Math.max(sceneDifficulty, diff);
            }
            if (!passed.isEmpty()) out.append("\n").append("Unseen, and they did not notice you either: you passed each other by (").append(String.join("; ", passed)).append(").");
            if (!scene.isEmpty()) out.append("\n").append(sceneText());
            List<String> gm = new ArrayList<>();
            for (Met m : scene) gm.add(m.e.kind().key() + " (" + m.e.type() + ", " + m.e.count() + (m.hostile() ? (m.seen ? ", spotted" : m.glimpsed ? ", glimpsed" : ", AMBUSH") : "") + ")");
            for (String x : passed) gm.add(x + " (passed by)");
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
    /** A hostile group nobody saw even well enough to go round: they attack first. */
    static boolean ambushed() { for (Met m : scene) if (m.hostile() && !m.glimpsed) return true; return false; }
    /** Every hostile group seen in time to ambush or talk to. */
    static boolean allSeen() { for (Met m : scene) if (m.hostile() && !m.seen) return false; return true; }
    /** A hostile group seen only well enough to go round (09d round 6). */
    static boolean glimpsedOnly() { for (Met m : scene) if (m.hostile() && m.glimpsed && !m.seen) return true; return false; }
    static boolean anyHunted() { for (Met m : scene) if (m.hunt && !m.hostile()) return true; return false; }

    static String sceneShort() {
        List<String> l = new ArrayList<>();
        for (Met m : scene) l.add(m.e.count() + " × " + m.e.kind().name());
        return String.join(", ", l);
    }

    static String sceneText() {
        List<String> l = new ArrayList<>();
        for (Met m : scene)
            l.add((m.hostile() ? (m.seen ? "SPOTTED IN TIME: " : m.glimpsed ? "GLIMPSED (only to go round): " : "AMBUSH! ") : m.hunt ? "HUNTED: " : "ENCOUNTER: ") + m.e.text()
                    + (m.hostile() ? " (" + (m.sneaking ? "sneaking" : "in the open") + ", the spotting's difficulty " + m.difficulty + ")" : "") + ".");
        if (anyHostile()) l.add("The weather: " + weather + "; the ground: " + sceneTerrain + ".");
        if (ambushed()) l.add("They act first: to the battle map (" + sceneTerrain + ").");
        else if (anyHostile() && !allSeen()) l.add("Go round them (Survival +" + AVOID_BONUS + "; each group's check pays its difficulty, at least 10: " + goRoundXp()
                + " XP), or face them (seen too late to take position: they act first).");
        else if (anyHostile()) l.add("Go round them (each group's Survival check pays its difficulty, at least 10: " + goRoundXp() + " XP), talk to them on the battle map "
                + "(you saw them first: a bonus), or ambush them (on the battle map, you act first).");
        for (Met m : scene) if (m.e.type().equals("sight")) { l.add("A sight can be hunted (the menu)."); break; }
        return String.join("\n", l);
    }

    /**
     * Going round the hostile ones, all seen in time: ONLY THE SURVIVAL CHECK'S XP (Marek 11-23, `09d` round 3: *"When you dodge encounter
     * on the world map, you only get the Survival check XP"*), which is OUR check's XP, its difficulty, at least 10 (Marek 11-24: *"unlike in
     * fallout, here it should be basic skill check XP, i.e. as much as was difficulty"*; 06b §2), to the scout (the party's best Survival);
     * the peaceful ones stay in the scene.
     */
    static String goRound(FalloutakPlugin pl, Player p) {
        if (!anyHostile() || ambushed()) return "! nothing seen in time to go round";
        List<String> what = new ArrayList<>();
        for (Met m : scene) if (m.hostile()) what.add(m.e.text());
        Player scout = p;
        double bs = -1;
        for (Player q : party(p)) { double v = pl.character(q).skill("Survival"); if (v > bs) { bs = v; scout = q; } }
        long xp = goRoundXp();
        String ds = diffs();
        String r = pl.roster.award(scout, xp, "an encounter dodged (the Survival checks, difficulty " + ds + ")");
        tellGm("travel: went round " + String.join(", ", what) + ". " + r);
        scene.removeIf(Met::hostile);
        dug.clear();   // what was dug for them stays behind
        return "You go round them unseen: " + scout.getName() + "'s Survival check" + (ds.contains("+") ? "s" : "") + " (difficulty " + ds + "), " + xp + " XP.";
    }

    /** The go-round's XP: each hostile group's check pays its difficulty, at least 10 (09d rounds 4-5). */
    static long goRoundXp() { long x = 0; for (Met m : scene) if (m.hostile()) x += dodgeXp(m.difficulty); return x; }

    private static String diffs() {
        List<String> l = new ArrayList<>();
        for (Met m : scene) if (m.hostile()) l.add(String.valueOf(m.difficulty));
        return String.join(" + ", l);
    }

    /** The XP for an encounter dodged: the spotting check's difficulty, at least 10 (06b §2). */
    static long dodgeXp(int difficulty) { return Math.max(10, difficulty); }

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
    static String battleMap(FalloutakPlugin pl, Player p, Arena.Mode mode) { return battleMap(pl, p, mode, null, ""); }

    /** The same with the talk on the battle map (09d round 4): {@code talk} with the group of {@code talkKey}. */
    static String battleMap(FalloutakPlugin pl, Player p, Arena.Mode mode, Battle.Talk talk, String talkKey) {
        if (scene.isEmpty()) return "! no encounter waits";
        if ((mode == Arena.Mode.AMBUSH || mode == Arena.Mode.TALK) && (!anyHostile() || ambushed() || !allSeen()))
            return "! nothing hostile seen in time to ambush or talk to" + (glimpsedOnly() ? " (only glimpsed: go round them, or face them and they act first)" : "");
        if (mode == Arena.Mode.HUNT && (anyHostile() || !anyHunted())) return anyHostile() ? "! the hostile ones first (go round them or ambush them; the hunted join an ambush)" : "! mark what you hunt first";
        if (mode == Arena.Mode.AMBUSHED && !ambushed() && !glimpsedOnly()) return "! nobody ambushed you";
        List<Arena.Group> g = groups(mode != Arena.Mode.AMBUSHED);
        if (g.isEmpty()) return "! nobody to fight";
        WorldMap.OnUnique on = map.unique(pi, pj);
        String r = Arena.fight(pl, party(p), sceneTerrain, g, mode, pl.rnd.nextLong(), Travel::afterFight, talk, talkKey, on, years());
        if (!r.startsWith("!")) tellGm("travel: " + mode.name().toLowerCase(Locale.ROOT) + (on != null ? " on the unique map " + on.u().name() + " (" + on.x() + ", " + on.z() + ")"
                : " on the " + sceneTerrain + " battle map") + ": " + sceneShort());
        if (!r.startsWith("!")) {
            talkKind = mode == Arena.Mode.TALK ? talkKey : "";
            if (mode == Arena.Mode.AMBUSH) for (Met m : scene) if (m.hostile() && m.e.kind().people()) feuds.add(m.e.kind().key());   // they were struck first
            save();
        }
        return r;
    }

    /** The years since day D now (the tick's clock, else the start point's): the raiders' kit follows it. */
    static double years() {
        if (tick != null) return tick.years();
        return map != null && map.tick != null ? map.tick.startYear : 2;
    }

    /** The party struck in the middle of a talk: a feud with that kind (09d round 6). */
    static void struckFirst() {
        if (talkKind.isEmpty()) return;
        feuds.add(talkKind);
        save();
    }

    /** The GM makes the talk go wrong NOW (09d round 5, Marek: *"give there option for GM to trigger the enemy attack first"*). */
    static String gmAttack(FalloutakPlugin pl) {
        Battle.Fight f = Arena.current;
        if (f == null || f.over || f.talk == null) return "! no talk is going on on a battle map";
        return pl.battle.gmAttack(f);
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
        dug.clear();
        return "Done with: " + t;
    }

    /** The party may dig before this scene's fight: an ambush seen in time (they have not seen the party), or a hunt. */
    static boolean canDig() { return anyHostile() ? !ambushed() && allSeen() : anyHunted(); }

    /** The dug-only traps (a pit, a deadfall) he carries, by name, with the id of one of each. */
    static java.util.Map<String, String> diggable(Player p) {
        java.util.Map<String, String> l = new java.util.LinkedHashMap<>();
        for (org.bukkit.inventory.ItemStack it : p.getInventory().getContents()) {
            String id = Items.id(it);
            Production.Design d = id == null ? null : Items.made(id);
            if (d != null && Data.TRAPS.containsKey(d.name) && Data.TRAPS.get(d.name).ap() < 0) l.putIfAbsent(d.name, id);
        }
        return l;
    }

    /** /travel dig [name]: one of his pits or deadfalls dug on the enemies' way while the scene waits (his time passes). */
    static String dig(FalloutakPlugin pl, Player p, String want) {
        if (scene.isEmpty()) return "! no encounter waits";
        if (!canDig()) return ambushed() || glimpsedOnly() ? "! no time to dig: they are on you" : "! nothing to dig for (an ambush seen in time, or a hunt)";
        String name = null, id = null;
        for (var e : diggable(p).entrySet())
            if (want.isEmpty() || e.getKey().toLowerCase(Locale.ROOT).startsWith(want.toLowerCase(Locale.ROOT))) { name = e.getKey(); id = e.getValue(); break; }
        if (name == null) return "! no pit or deadfall with you" + (want.isEmpty() ? "" : " named " + want);
        String one = id;
        if (Battle.removeIds(p, x -> x.equals(one), 1) < 1) return "! no " + name + " with you";
        Data.TrapDef def = Data.TRAPS.get(name);
        Character c = pl.character(p);
        double skill = Battle.weaponSkill(c, def.kind());
        dug.add(new Dug(def, p.getUniqueId(), skill));
        int min = digMinutes(name);
        String r = Hud.pass(c, min);
        return "You " + (name.equals("Spike pit") ? "dig a spike pit" : "set a " + name.toLowerCase(Locale.ROOT)) + " on their way (" + min + " min; your "
                + def.kind() + " " + Math.round(skill) + "): it waits for them on the battle map (" + dug.size() + " dug)." + (r == null ? "" : " " + r);
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

    /** The weeks the game has passed since the tick last ran: one step each (the first call only sets the day). */
    static String catchUp(int day) {
        if (tick == null) return "";
        if (tickDay == Integer.MIN_VALUE) { tickDay = day; save(); return ""; }
        int n = 0;
        while (day - tickDay >= 7 && n < 520) { tick.step(); tickDay += 7; n++; }
        if (n == 0) return "";
        if (tickFile != null) tick.save(tickFile);
        save();
        return n + " week(s) of the world's tick: " + tick.summary();
    }

    /** The GM holds the ground within {@code km} of the party (a guard post): no camp moves or splits into it. */
    static String hold(double km) {
        if (tick == null) return "! no tick on this world map (an older export)";
        int n = tick.hold(pi, pj, km);
        if (tickFile != null) tick.save(tickFile);
        return String.format(Locale.ROOT, "%d sectors within %.1f km held: no camp moves or splits into them", n, km);
    }

    /** The GM clears every raider base within {@code km} of the party (a fight won there): the tick's own clear when there is one. */
    static String clear(double km) {
        if (map == null) return "! no world map";
        if (tick != null) {
            double[] r = tick.clear(pi, pj, km);
            if (tickFile != null) tick.save(tickFile);
            return String.format(Locale.ROOT, "%d raider bases (%.0f fighters) within %.1f km cleared; the region's raiders stay that much fewer, and a group "
                    + "with no base left leaves its ground to whoever moves in (the tick)", (int) r[0], r[1], km);
        }
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
            case "ambush" -> { return battleMap(pl, p, ambushed() || !allSeen() ? Arena.Mode.AMBUSHED : Arena.Mode.AMBUSH); }
            case "hunt" -> {
                for (Met m : scene) if (m.e.type().equals("sight")) m.hunt = true;
                return anyHostile() ? "The sights are hunted: they join an ambush." : battleMap(pl, p, Arena.Mode.HUNT);
            }
            case "on" -> { return resolved(); }
            case "dig" -> { return dig(pl, p, a.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)) : ""); }
            case "map", "look" -> { return MapView.viewing(p) ? MapView.back(p) : MapView.look(pl, p); }
            case "depots" -> { return depots(); }
            default -> { }
        }
        if (!gm) return "/travel [where|to <place>|push <place>|round|ambush|hunt|dig [pit|deadfall]|on|map|depots]";
        switch (arg) {
            case "table" -> { return String.join("\n", table()); }
            case "attack" -> { return gmAttack(pl); }
            case "feud" -> {
                if (a.length > 1 && a[1].equalsIgnoreCase("clear")) {
                    if (a.length > 2) feuds.remove(a[2]); else feuds.clear();
                    save();
                    return "Feuds: " + (feuds.isEmpty() ? "none" : String.join(", ", feuds));
                }
                if (a.length > 1 && map != null && map.kinds.containsKey(a[1])) { feuds.add(a[1]); save(); }
                return "Feuds (they shoot on sight): " + (feuds.isEmpty() ? "none" : String.join(", ", feuds)) + " · /travel feud <kind> | clear [kind]";
            }
            case "weather" -> {
                if (a.length < 2) return "The weather: " + weather + (weatherSet ? " (set by the GM)" : " (drawn each day)");
                String w = String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)).toLowerCase(Locale.ROOT);
                if (w.equals("auto")) { weatherSet = false; weatherDay = Integer.MIN_VALUE; return "The weather is drawn each day again."; }
                for (String x : WEATHER) if (x.startsWith(w)) { weather = x; weatherSet = true; return "The weather: " + x + " (until /travel weather auto)."; }
                return "! /travel weather clear|rain|fog|snow|black rain|auto";
            }
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
            case "hold" -> { return hold(a.length > 1 ? Double.parseDouble(a[1]) : 3); }
            case "tick" -> {
                if (tick == null) return "! no tick on this world map (an older export)";
                int n = a.length > 1 ? Integer.parseInt(a[1]) : 1;
                for (int k = 0; k < n; k++) tick.step();
                if (tickFile != null) tick.save(tickFile);
                return tick.summary();
            }
            default -> { return "/travel [where|to|push|round|ambush|hunt|dig [pit|deadfall]|on|map|depots] · GM: table|season <s>|weather <w>|attack|feud [kind|clear]|here <place>|clear [km]|hold [km]|tick [weeks]"; }
        }
    }

    /** The menu's dig buttons (05d §7c): one for each kind of pit or deadfall he carries, and what is dug already. */
    static void digButtons(FalloutakPlugin pl, Player p, List<Component> lines, List<Ui.Btn> b) {
        if (!dug.isEmpty()) {
            List<String> l = new ArrayList<>();
            for (Dug d : dug) l.add(d.def().name().toLowerCase(Locale.ROOT));
            lines.add(line("Dug on their way: " + String.join(", ", l) + ".", NamedTextColor.GOLD));
        }
        for (String name : diggable(p).keySet()) {
            Data.TrapDef def = Data.TRAPS.get(name);
            b.add(btn((name.equals("Spike pit") ? "Dig a spike pit" : "Set a " + name.toLowerCase(Locale.ROOT)) + " on their way (" + digMinutes(name) + " min)",
                    def.min() + "-" + def.max() + " damage, " + (def.effect().equals("fall") ? "in the pit: its next turn lost climbing out" : "knocked down")
                    + "; your " + def.kind() + " " + Math.round(Battle.weaponSkill(pl.character(p), def.kind())) + " against their eyes; "
                    + (anyHostile() ? "half-way to them (where they charge)" : "past the animals (where they run)"),
                    x -> { say(x, dig(pl, x, name)); menu(pl, x); }));
        }
    }

    /** The salt depots the players know of (09d round 5, Marek: *"mark the depots"*): the road maintenance depots, nearest first. */
    static String depots() {
        if (map == null) return "! no world map";
        List<String> l = new ArrayList<>();
        List<WorldMap.Place> ds = new ArrayList<>();
        for (WorldMap.Place q : map.places) if (q.depot()) ds.add(q);
        ds.sort(Comparator.comparingDouble(q -> map.km(pi, pj, q.i() + 0.5, q.j() + 0.5)));
        for (WorldMap.Place q : ds) l.add(String.format(Locale.ROOT, "%s, %.1f km", q.name(), map.km(pi, pj, q.i() + 0.5, q.j() + 0.5)));
        return l.isEmpty() ? "No salt depot is marked on this map." : "The road maintenance depots (their winter salt: dissolve, settle and boil it down before eating):\n" + String.join("\n", l);
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
        dayWeather(c.day(), pl.rnd);
        lines.add(line(c.timeText() + " · " + WorldMap.SEASONS[season] + ", " + weather + " · marched today " + Hud.clock(marched * 60) + " of 8 h", NamedTextColor.GRAY));
        if (Arena.current != null && !Arena.current.over && Arena.current.talk != null && p.hasPermission("falloutak.gm"))
            b.add(btn("GM: they attack now", "the talk on the battle map goes wrong at once: they act first (09d round 5)", x -> { say(x, gmAttack(pl)); }));
        if (!Loot.bodies.isEmpty())
            b.add(btn("The field: " + Loot.bodies.size() + " bod" + (Loot.bodies.size() == 1 ? "y" : "ies"), "search, butcher (Survival), treat or take samples (Medicine)",
                    x -> Loot.menu(pl, x)));
        if (!scene.isEmpty()) {
            lines.add(line("On the " + sceneTerrain + ":", NamedTextColor.GRAY));
            for (Met m : scene)
                lines.add(line((m.hostile() ? (m.seen ? "Spotted in time: " : m.glimpsed ? "Glimpsed (only to go round): " : "AMBUSH: ") : m.hunt ? "Hunted: " : "Encounter: ") + m.e.text(),
                        m.hostile() ? NamedTextColor.RED : m.hunt ? NamedTextColor.GOLD : NamedTextColor.YELLOW));
            if (ambushed()) b.add(btn("To the battle map: they act first", "an ambush on you (" + sceneTerrain + "): every hostile group of the scene", x -> { say(x, battleMap(pl, x, Arena.Mode.AMBUSHED)); }));
            else if (anyHostile() && !allSeen()) {
                b.add(btn("Go round them", "Survival +" + AVOID_BONUS + " (09d round 6): only the Survival check's XP, to your scout (06b §3)", x -> { say(x, goRound(pl, x)); menu(pl, x); }));
                b.add(btn("Face them: they act first", "seen too late to take position (" + sceneTerrain + ")", x -> { say(x, battleMap(pl, x, Arena.Mode.AMBUSHED)); }));
            }
            else if (anyHostile()) {
                b.add(btn("Go round them", "unseen: only the Survival check's XP, to your scout (06b §3)", x -> { say(x, goRound(pl, x)); menu(pl, x); }));
                for (Met m : scene) if (m.hostile() && Talks.talks(m.e.kind()))
                    b.add(btn("Talk to " + m.e.kind().name(), "on the battle map: one of you speaks, Rhetorics " + Battle.sign(Talks.SEEN_FIRST + Talks.HOSTILE)
                            + " (+20 the first sight, −20 they came to fight); the rest get into position; talked down = their full kill XP",
                            x -> Talks.speakerMenu(pl, x, m)));
                digButtons(pl, p, lines, b);
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
            if (!anyHostile() && anyHunted()) digButtons(pl, p, lines, b);
            if (!anyHostile() && anyHunted()) b.add(btn("Hunt now", "the battle map (" + sceneTerrain + "): you downwind, you act first", x -> { say(x, battleMap(pl, x, Arena.Mode.HUNT)); }));
            b.add(btn(anyHostile() ? "It is over (the GM ran it)" : "Done (go on)", "the encounter is played out: the way is free", x -> { say(x, resolved()); menu(pl, x); }));
            if (!dest.isEmpty() && !anyHostile()) b.add(btn("Then on to " + dest, "after the encounter", x -> { say(x, resolved()); say(x, go(pl, x, dest, false, pl.rnd)); menu(pl, x); }));
        } else {
            List<WorldMap.Place> near = new ArrayList<>(map.places);
            near.sort(Comparator.comparingDouble(q -> map.km(pi, pj, q.i() + 0.5, q.j() + 0.5)));
            int shown = 0, depots = 0;
            for (WorldMap.Place q : near) {
                double d = map.km(pi, pj, q.i() + 0.5, q.j() + 0.5);
                if (d < 0.5) continue;
                if (q.depot() ? ++depots > 3 : ++shown > 14) continue;
                b.add(btn(String.format(Locale.ROOT, "To %s (%.1f km)", q.name(), d), q.depot() ? "a road maintenance depot: its winter salt (a scavenging target); a roll every 4 hours"
                        : "set out: a roll every 4 hours; at most 8 hours a day", x -> { say(x, go(pl, x, q.name(), false, pl.rnd)); menu(pl, x); }));
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
            if (tick != null) {
                b.add(btn("GM: hold 3 km", "a guard post: no camp moves or splits into the ground within 3 km", x -> { say(x, hold(3)); menu(pl, x); }));
                b.add(btn("GM: a week of the tick", "the camps move, grow, split and join, the creatures breed and die (09d §6)", x -> {
                    tick.step(); if (tickFile != null) tick.save(tickFile); say(x, tick.summary()); menu(pl, x); }));
            }
        }
        Menus.menu(p, "Travel: the world map", lines, b, Menus::actions);
    }
}
