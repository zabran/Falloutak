package cz.lollywood.falloutak;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * The narrator's comments on critical hits and critical failures (Marek 11-19, verbatim: *"Also, for criticals and fambles, Fallout
 * had funny comments - at least in Czech version. Could you the documentary narrator speak the comments to crits?"*). The text is
 * OUR OWN (`data/narrator.json`, nothing from Fallout's messages), the voice the English documentary narrator of the LollyWood films
 * (`tools/narrator.py`: Kokoro bm_lewis at 0.92) in the public pack: everyone near reads the line in the chat, a player with the
 * pack also hears it ({@code falloutak:narrator.<id>}, a woman's form {@code .f}). {@code /sounds narrator off} silences both.
 */
final class Narrator {
    record Line(String id, String kind, String text) {
        boolean gendered() { return text.contains("{"); }
    }

    static final List<Line> LINES = load();
    static final Map<String, List<Line>> BY_KIND = byKind();
    /** How far the comment carries (blocks): as far as the battle's sounds. */
    static final double HEARD = Sfx.HEARD;
    /** Tests: every voiced key, with who heard it. */
    static BiConsumer<String, Player> recorder;
    static Random rnd = new Random();
    private static final Map<String, String> LAST = new HashMap<>();

    private Narrator() {}

    private static List<Line> load() {
        List<Line> l = new ArrayList<>();
        try (InputStream in = Narrator.class.getResourceAsStream("/data/narrator.json")) {
            JsonObject root = JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in), StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement e : root.getAsJsonArray("lines")) {
                JsonObject o = e.getAsJsonObject();
                l.add(new Line(o.get("id").getAsString(), o.get("kind").getAsString(), o.get("text").getAsString()));
            }
        } catch (Exception ex) {
            throw new IllegalStateException("data/narrator.json: " + ex.getMessage(), ex);
        }
        return List.copyOf(l);
    }

    private static Map<String, List<Line>> byKind() {
        Map<String, List<Line>> m = new LinkedHashMap<>();
        for (Line l : LINES) m.computeIfAbsent(l.kind(), k -> new ArrayList<>()).add(l);
        return m;
    }

    /** The crit's most striking effect picks the comment; with none, the body part hit. */
    static String critKind(Rules.Part part, Combat.CritEffects fx) {
        if (fx.heartStops()) return "crit.heart";
        if (fx.dies()) return "crit.dies";
        if (fx.knockedOut()) return "crit.out";
        if (fx.blinded()) return "crit.blind";
        if (fx.crippledLeg()) return "crit.leg";
        if (fx.crippledArm()) return "crit.arm";
        if (fx.dropsWeapon()) return "crit.drop";
        if (fx.knockedDown()) return "crit.down";
        if (fx.losesTurn()) return "crit.turn";
        if (part == Rules.Part.GROIN) return "crit.groin";
        if (fx.bypass()) return "crit.bypass";
        if (part == null) return "crit.body";
        return switch (part) {
            case HEAD -> "crit.head";
            case EYES -> "crit.eyes";
            case LEFT_ARM, RIGHT_ARM -> "crit.arms";
            case LEFT_LEG, RIGHT_LEG -> "crit.legs";
            case GROIN -> "crit.groin";
            case TORSO -> "crit.body";
        };
    }

    /** A critical failure's worst result picks the comment. */
    static String fumbleKind(Combat.Failure f) {
        if (f.onFire()) return "fumble.fire";
        if (f.hitsSelf()) return "fumble.self";
        if (f.weaponDestroyed()) return "fumble.broken";
        if (f.hitsRandom()) return "fumble.random";
        if (f.knockedDown()) return "fumble.fall";
        if (f.dropsWeapon()) return "fumble.drop";
        if (f.losesAmmo()) return "fumble.ammo";
        return "fumble.plain";
    }

    /** A line of the kind, never the same as the kind's last one when there is another. */
    static Line pick(String kind) {
        List<Line> l = BY_KIND.get(kind);
        if (l == null || l.isEmpty()) throw new IllegalArgumentException("no narrator lines for " + kind);
        Line x = l.get(rnd.nextInt(l.size()));
        if (l.size() > 1 && x.id().equals(LAST.get(kind))) x = l.get((l.indexOf(x) + 1 + rnd.nextInt(l.size() - 1)) % l.size());
        LAST.put(kind, x.id());
        return x;
    }

    private static final Map<String, String> HE = Map.of("he", "he", "him", "him", "his", "his", "himself", "himself", "He", "He", "His", "His");
    private static final Map<String, String> SHE = Map.of("he", "she", "him", "her", "his", "her", "himself", "herself", "He", "She", "His", "Her");

    /** The line's text for a man or a woman. */
    static String text(Line l, boolean female) {
        Map<String, String> f = female ? SHE : HE;
        return java.util.regex.Pattern.compile("\\{(\\w+)}").matcher(l.text()).replaceAll(m -> f.get(m.group(1)));
    }

    /** The pack's sound event for the line. */
    static String key(Line l, boolean female) { return "narrator." + l.id() + (female && l.gendered() ? ".f" : ""); }

    /** A player who has not silenced the narrator ({@code /sounds narrator off}). */
    static boolean on(Player p) {
        Character c = Menus.pl == null ? null : Menus.pl.character(p);
        return c == null || c.narrator == null || c.narrator;
    }

    /** The comment on a crit: {@code female} = the target is a woman. Returns the line said (tests, the log). */
    static String crit(Location at, Rules.Part part, Combat.CritEffects fx, boolean female) { return say(at, critKind(part, fx), female); }

    /** The comment on a critical failure: {@code female} = the one who fumbled is a woman. */
    static String fumble(Location at, Combat.Failure f, boolean female) { return say(at, fumbleKind(f), female); }

    static String say(Location at, String kind, boolean female) {
        Line l = pick(kind);
        String text = text(l, female);
        if (at == null || at.getWorld() == null) return text;
        Component msg = Component.text("The narrator: " + text, NamedTextColor.GRAY, TextDecoration.ITALIC);
        String key = key(l, female);
        for (Player p : at.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(at) > HEARD * HEARD || !on(p)) continue;
            Character c = Menus.pl == null ? null : Menus.pl.character(p);
            boolean pack = c != null ? Icons.hasPack(p, c) : Icons.packLoaded.contains(p.getUniqueId());   // no pack: the text alone
            if (pack && recorder != null) recorder.accept(key, p);
            // a moment after the hit's own sound and its log line; the voice in the listener's head (a narrator, not a sound in the world)
            Runnable out = () -> {
                p.sendMessage(msg);
                if (pack) Battle.cosmetic(() -> p.playSound(p.getLocation(), "falloutak:" + key, SoundCategory.VOICE, 1f, 1f));
            };
            if (Menus.pl != null && Menus.pl.isEnabled()) Battle.cosmetic(() -> Menus.pl.getServer().getScheduler().runTaskLater(Menus.pl, out, 8L));
            else out.run();
        }
        return text;
    }
}
