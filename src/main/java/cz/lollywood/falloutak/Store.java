package cz.lollywood.falloutak;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * The data store, step 1 (Marek 11-14: the plugin holds all data, *"I would like to get into point that I can archive the Google
 * sheet and never return to it."*): each player's character is one readable JSON file, {@code plugins/Falloutak/characters/<uuid>.json},
 * written whole (to a temporary file, then moved over the old one) and read back on join. Pure Java: the tests run it.
 */
final class Store {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create();
    private final Path dir;

    Store(Path dataFolder) { this.dir = dataFolder.resolve("characters"); }

    static String toJson(Character c) { return GSON.toJson(c); }

    static Character fromJson(String json) { return GSON.fromJson(json, Character.class); }

    Path file(UUID u) { return dir.resolve(u + ".json"); }

    /** The saved character, or null if none (or unreadable: the file is kept, renamed .bad, so nothing is lost). */
    Character load(UUID u) {
        Path f = file(u);
        if (!Files.isRegularFile(f)) return null;
        try {
            return fromJson(Files.readString(f, StandardCharsets.UTF_8));
        } catch (Exception e) {
            try { Files.move(f, f.resolveSibling(u + ".bad-" + System.currentTimeMillis() + ".json")); } catch (IOException ignored) { }
            return null;
        }
    }

    // ------------------------------------------------------------------ NPC statblocks (step 2: the workbook's NPC tab)
    private Path npcDir() { return dir.resolveSibling("npcs"); }

    /** A name for a file: letters (any language), digits, _ and -. */
    static boolean validName(String n) { return n != null && n.matches("[\\p{L}\\p{N}_-]{1,40}"); }

    java.util.List<String> npcNames() {
        java.util.List<String> l = new java.util.ArrayList<>();
        try (var s = Files.list(npcDir())) {
            s.map(p -> p.getFileName().toString()).filter(f -> f.endsWith(".json") && !f.contains(".deleted-") && !f.contains(".bad-"))
                    .map(f -> f.substring(0, f.length() - 5)).sorted().forEach(l::add);
        } catch (IOException ignored) { }
        return l;
    }

    /** The NPC statblock of this name (a character sheet), or null. */
    Character loadNpc(String name) {
        if (!validName(name)) return null;
        Path f = npcDir().resolve(name + ".json");
        if (!Files.isRegularFile(f)) {   // the name as typed, else any case
            for (String n : npcNames()) if (n.equalsIgnoreCase(name)) { f = npcDir().resolve(n + ".json"); break; }
            if (!Files.isRegularFile(f)) return null;
        }
        try { return fromJson(Files.readString(f, StandardCharsets.UTF_8)); } catch (Exception e) { return null; }
    }

    void saveNpc(String name, Character c) throws IOException {
        if (!validName(name)) throw new IOException("not a valid name: " + name);
        Files.createDirectories(npcDir());
        Path tmp = npcDir().resolve(name + ".json.tmp");
        Files.writeString(tmp, toJson(c), StandardCharsets.UTF_8);
        Files.move(tmp, npcDir().resolve(name + ".json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Sets a statblock aside (renamed .deleted-<time>, never lost); false if there is none. */
    boolean deleteNpc(String name) {
        if (!validName(name)) return false;
        Path f = npcDir().resolve(name + ".json");
        if (!Files.isRegularFile(f)) return false;
        try { Files.move(f, npcDir().resolve(name + ".deleted-" + System.currentTimeMillis() + ".json")); return true; } catch (IOException e) { return false; }
    }

    void save(UUID u, Character c) throws IOException {
        Files.createDirectories(dir);
        Path tmp = dir.resolve(u + ".json.tmp");
        Files.writeString(tmp, toJson(c), StandardCharsets.UTF_8);
        Files.move(tmp, file(u), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
