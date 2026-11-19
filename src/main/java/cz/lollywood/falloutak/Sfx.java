package cz.lollywood.falloutak;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Every action's sound (Marek 11-19, verbatim: *"I would like every player action + action of NPC and environment to have some sound
 * effect. Please, look for Fallout 2, if the sounds are free/accessable"*). The catalogue is `data/sounds.json`: each key has
 * Minecraft's own sound (always there) and the Fallout 2 files it stands for. Fallout 2's sounds are Interplay's: never in the public
 * pack; `tools/fallout2_sounds.py` builds the PRIVATE pack {@code falloutak_f2} from a player's own copy of the game, and a player
 * with it hears {@code falloutak_f2:<key>} (by {@code /sounds fallout2}, or by loading that pack from the server).
 */
final class Sfx {
    record Row(String key, String what, String mc, float volume, float pitch, List<String> f2) {}

    static final String F2 = "falloutak_f2";
    static final Map<String, Row> ROWS = load();
    /** How far a sound in the world carries (blocks). */
    static final double HEARD = 32;
    /** Tests: every key played, with who heard it. */
    static java.util.function.BiConsumer<String, Player> recorder;
    /** Players whose client loaded the server's Fallout 2 pack this session (their saved choice is in the character). */
    static final Set<UUID> f2Loaded = new HashSet<>();

    private Sfx() {}

    private static Map<String, Row> load() {
        Map<String, Row> m = new LinkedHashMap<>();
        try (InputStream in = Sfx.class.getResourceAsStream("/data/sounds.json")) {
            JsonObject root = JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in), StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement e : root.getAsJsonArray("rows")) {
                JsonObject o = e.getAsJsonObject();
                List<String> f2 = new ArrayList<>();
                o.getAsJsonArray("f2").forEach(x -> f2.add(x.getAsString()));
                String key = o.get("key").getAsString();
                m.put(key, new Row(key, o.get("what").getAsString(), o.get("mc").getAsString(), o.get("volume").getAsFloat(),
                        o.get("pitch").getAsFloat(), List.copyOf(f2)));
            }
        } catch (Exception ex) {
            throw new IllegalStateException("data/sounds.json: " + ex.getMessage(), ex);
        }
        return m;
    }

    static Row row(String key) {
        Row r = ROWS.get(key);
        if (r == null) throw new IllegalArgumentException("no sound " + key);
        return r;
    }

    /** Whether this player hears Fallout 2's sounds (his choice, or the server's F2 pack loaded). */
    static boolean f2(Player p) {
        if (f2Loaded.contains(p.getUniqueId())) return true;
        Character c = Menus.pl == null ? null : Menus.pl.character(p);
        return c != null && c.fallout2Sounds;
    }

    /** The sound name this player's client plays for a key. */
    static String name(Player p, String key) { return f2(p) ? F2 + ":" + key : "minecraft:" + row(key).mc(); }

    /** A body's own sound: a man's or a woman's (hurt, down, dying, death). */
    static String body(String what, boolean female) { return what + (female ? ".female" : ".male"); }

    /** Heard by everyone within {@link #HEARD} blocks of the place. */
    static void at(Location l, String key) {
        row(key);
        if (l == null || l.getWorld() == null) return;
        for (Player p : l.getWorld().getPlayers())
            if (p.getLocation().distanceSquared(l) <= HEARD * HEARD) play(p, l, key);
    }

    /** Heard by one player only (his turn, his menus, his own body's signals). */
    static void to(Player p, String key) {
        row(key);
        if (p != null) play(p, p.getLocation(), key);
    }

    private static void play(Player p, Location l, String key) {
        Row r = row(key);
        if (recorder != null) recorder.accept(key, p);
        boolean f = f2(p);
        // the F2 pack's events carry their own volume and pitch (tools/fallout2_sounds.py), Minecraft's are set here
        Battle.cosmetic(() -> p.playSound(l, f ? F2 + ":" + key : "minecraft:" + r.mc(), SoundCategory.PLAYERS, f ? 1f : r.volume(), f ? 1f : r.pitch()));
    }
}
