package cz.lollywood.falloutak;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * SAVED GAMES (`09b` round 11, Marek 11-23, verbatim: *"Also, for the purpose of playing, it should be possible to save your current
 * inventory + character (esp. in MC, where you can do other things than just play)."*): `/game save [name]` keeps the character, the whole
 * inventory and where he stood; `/game load [name]` puts them back (what he had first is kept as "_before-load"); `/game off` leaves the
 * game (the game kept as "_game", his Minecraft inventory given back, the world leaves him alone) and `/game on` returns to it where he
 * left. Files: `plugins/Falloutak/saves/<player's id>/<name>.yml`. [the commands and the places mine]
 */
final class Saves {
    static final String GAME = "_game", MC = "_minecraft", BEFORE = "_before-load";

    final FalloutakPlugin pl;
    final Path dir;

    Saves(FalloutakPlugin pl) {
        this.pl = pl;
        this.dir = pl.getDataFolder().toPath().resolve("saves");
    }

    Path file(UUID u, String name) { return dir.resolve(u.toString()).resolve(name + ".yml"); }

    static String clean(String name) {
        String n = name == null ? "" : name.replaceAll("[^A-Za-z0-9_-]", "");
        return n.isEmpty() ? "main" : n;
    }

    /** Writes the character (or none), the inventory and the place. */
    void write(Player p, String name, Character c) throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        if (c != null) y.set("character", Store.toJson(c));
        y.set("inventory", Arrays.asList(p.getInventory().getContents()));
        y.set("location", p.getLocation());
        y.set("saved", java.time.LocalDateTime.now().withNano(0).toString());
        Path f = file(p.getUniqueId(), name);
        Files.createDirectories(f.getParent());
        y.save(f.toFile());
    }

    /** Reads a save back onto the player: the inventory, the character if it holds one, the place if asked. Null when done, else why not. */
    String read(Player p, String name, boolean place) throws IOException {
        Path f = file(p.getUniqueId(), name);
        if (!Files.exists(f)) return "no save \"" + name + "\"";
        YamlConfiguration y = new YamlConfiguration();
        try { y.load(f.toFile()); } catch (org.bukkit.configuration.InvalidConfigurationException e) { return "the save \"" + name + "\" cannot be read: " + e.getMessage(); }
        List<?> inv = y.getList("inventory", List.of());
        ItemStack[] items = new ItemStack[p.getInventory().getSize()];
        for (int i = 0; i < Math.min(items.length, inv.size()); i++) items[i] = inv.get(i) instanceof ItemStack it ? it : null;
        p.getInventory().setContents(items);
        String json = y.getString("character");
        if (json != null) {
            Character c = Store.fromJson(json);
            c.migrate();
            pl.characters.put(p.getUniqueId(), c);
            pl.save(p.getUniqueId());
        }
        Location l = y.getLocation("location");
        if (place && l != null && l.getWorld() != null) p.teleport(l);
        return null;
    }

    List<String> list(UUID u) {
        Path d = dir.resolve(u.toString());
        List<String> l = new ArrayList<>();
        if (!Files.isDirectory(d)) return l;
        try (var s = Files.list(d)) { s.forEach(x -> { String n = x.getFileName().toString(); if (n.endsWith(".yml")) l.add(n.substring(0, n.length() - 4)); }); }
        catch (IOException e) { return l; }
        Collections.sort(l);
        return l;
    }

    /** /game save | load | list | off | on. */
    String command(Player p, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        String name = clean(a.length > 1 ? a[1] : "main");
        Character c = pl.character(p);
        if (!sub.equals("list") && !sub.isEmpty() && pl.battle.inBattle(p)) return "Not in rounds: end them first.";
        try {
            switch (sub) {
                case "save" -> {
                    if (c.away) return "You are out of the game (/game on first): your game is kept as " + GAME + ".";
                    write(p, name, c);
                    return "Saved as \"" + name + "\": your character, your inventory and where you stand. /game load " + name + " puts them back.";
                }
                case "load" -> {
                    if (c.away) return "You are out of the game: /game on first.";
                    write(p, BEFORE, c);
                    String r = read(p, name, true);
                    return r != null ? "! " + r : "Loaded \"" + name + "\" (what you had before: \"" + BEFORE + "\").";
                }
                case "list" -> {
                    List<String> l = list(p.getUniqueId());
                    return l.isEmpty() ? "No saves." : "Your saves: " + String.join(", ", l) + (c.away ? " (you are out of the game)" : "");
                }
                case "off" -> {
                    if (c.away) return "You are out of the game already.";
                    write(p, GAME, c);
                    if (Files.exists(file(p.getUniqueId(), MC))) read(p, MC, false);   // his Minecraft inventory back (it holds no character)
                    else p.getInventory().clear();
                    Character now = pl.character(p);
                    now.away = true;
                    pl.save(p.getUniqueId());
                    Battle.cosmetic(() -> p.setWalkSpeed(0.2f));
                    Battle.cosmetic(() -> p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard()));
                    return "OUT OF THE GAME: your game is kept (character, inventory, place) as \"" + GAME + "\"; the world leaves you alone. /game on returns to it.";
                }
                case "on" -> {
                    if (!c.away) return "You are in the game.";
                    write(p, MC, null);   // the Minecraft inventory, no character
                    String r = read(p, GAME, true);
                    if (r != null) { c.away = false; return "! " + r + ": you are back in the game as you are."; }
                    pl.character(p).away = false;
                    pl.save(p.getUniqueId());
                    Items.ensurePipBoy(p);
                    return "BACK IN THE GAME where you left it.";
                }
                default -> { return "/game save [name] | load [name] | list | off | on"; }
            }
        } catch (IOException e) {
            return "! the save failed: " + e.getMessage();
        }
    }
}
