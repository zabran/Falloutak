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

    /** The keys the public pack's FREE sounds cover (tools/free_sounds.py: CC0 recordings and our own; the voices are not among them). */
    static final java.util.Set<String> FREE = loadFree();

    private static java.util.Set<String> loadFree() {
        try (InputStream in = Sfx.class.getResourceAsStream("/data/free-sounds.json")) {
            if (in == null) return java.util.Set.of();
            java.util.Set<String> s = new java.util.HashSet<>();
            JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray().forEach(e -> s.add(e.getAsString()));
            return java.util.Set.copyOf(s);
        } catch (Exception ex) {
            throw new IllegalStateException("data/free-sounds.json: " + ex.getMessage(), ex);
        }
    }

    /** The public pack's free sound for this player: he has the pack (loaded from the server, or /pack on) and the key is covered. */
    static boolean free(Player p, String key) {
        if (!FREE.contains(key)) return false;
        Character c = Menus.pl == null ? null : Menus.pl.character(p);
        return c != null ? Icons.hasPack(p, c) : Icons.packLoaded.contains(p.getUniqueId());
    }

    /** The sound name this player's client plays for a key: Fallout 2's (his own private pack), the free pack's, or Minecraft's. */
    static String name(Player p, String key) {
        return f2(p) ? F2 + ":" + key : free(p, key) ? "falloutak:" + key : "minecraft:" + row(key).mc();
    }

    /** A body's own sound: a man's or a woman's (hurt, down, dying, death). */
    static String body(String what, boolean female) { return what + (female ? ".female" : ".male"); }

    /** Heard by everyone within {@link #HEARD} blocks of the place. */
    static void at(Location l, String key) {
        row(key);
        if (l == null || l.getWorld() == null) return;
        for (Player p : l.getWorld().getPlayers())
            if (p.getLocation().distanceSquared(l) <= HEARD * HEARD) play(p, l, key);
    }

    /**
     * A body falling or thrown back WITH its pain voice (Marek 11-19 on the knockdown and the knockback: *"original makes sound of
     * pain and body hitting ground. Both is missing in our and MC."*): Fallout 2's own fall already cries out; for everyone else
     * Minecraft's body hitting the ground plus the man's or the woman's hurt voice. The free pack's falls carry their own cry
     * since round 2 of the listening test ({@link #VOICED}, tools/free_sounds.py: a cry, then the body hitting the ground, as
     * Fallout 2's), so they play alone; Fallout 2's knockback is its man's fall, so a woman's knockback plays her own fall.
     */
    static void fall(Location l, String key, boolean female) {
        row(key);
        if (l == null || l.getWorld() == null) return;
        for (Player p : l.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(l) > HEARD * HEARD) continue;
            if (f2(p)) play(p, l, key);
            else if (VOICED.contains(key) && free(p, key))
                play(p, l, female && key.equals("knockback") ? body("down", true) : key);
            else {
                play(p, l, key);
                play(p, l, body("hurt", female));
            }
        }
    }

    /** The free pack's sounds that already carry the body's cry (a fall: the cry, then the body hitting the ground). */
    static final java.util.Set<String> VOICED = java.util.Set.of("knockback", "down.male", "down.female");

    /** Heard by one player only (his turn, his menus, his own body's signals). */
    static void to(Player p, String key) {
        row(key);
        if (p != null) play(p, p.getLocation(), key);
    }

    /** Heard by one player only, from a place (the echo's voices round him, 09c §2, round 10). */
    static void toAt(Player p, Location l, String key) {
        row(key);
        if (p != null && l != null) play(p, l, key);
    }

    private static void play(Player p, Location l, String key) {
        Row r = row(key);
        if (recorder != null) recorder.accept(key, p);
        String n = name(p, key);
        boolean own = !n.startsWith("minecraft:");
        // the packs' events carry their own volume and pitch (tools/fallout2_sounds.py, tools/free_sounds.py), Minecraft's are set here
        Battle.cosmetic(() -> p.playSound(l, n, SoundCategory.PLAYERS, own ? 1f : r.volume(), own ? 1f : r.pitch()));
    }
}
