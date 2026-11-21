package cz.lollywood.falloutak;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import static cz.lollywood.falloutak.Menus.btn;
import static cz.lollywood.falloutak.Menus.line;
import static cz.lollywood.falloutak.Menus.menu;
import static cz.lollywood.falloutak.Menus.say;

/**
 * Part 9, the maps (`09-MAPS.md`): the three levels are worlds the players are moved between (Marek 11-13: *"I think it would be
 * better to have it all in MC and just jump to different map when needed."*). For now the GM's jump between the server folder's
 * worlds: the 1:1 local maps made by Arnis from OpenStreetMap (Marek 11-21: *"Interiors - yes, lets try Arnis."*; Jihlava as on day
 * D and Jihlava ruined), each loaded when it is first visited, and back.
 * /maps · /maps go &lt;world&gt; [all] · /maps back · /maps load &lt;world&gt; · /maps unload &lt;world&gt; (GMs; load and unload also
 * from the console: an unloaded world's files can be edited, replaced or deleted while the server runs).
 */
final class Maps {
    private Maps() {}

    /** Where each player stood before his last jump (lost on a restart: then the main world's spawn). */
    static final Map<UUID, Location> from = new HashMap<>();

    /** The server folder's worlds (a folder with a level.dat) and the loaded ones, by name. */
    static List<String> worlds(File container) {
        TreeSet<String> names = new TreeSet<>();
        for (World w : Bukkit.getWorlds()) names.add(w.getName());
        File[] dirs = container == null ? null : container.listFiles(File::isDirectory);
        if (dirs != null) for (File d : dirs) if (new File(d, "level.dat").isFile()) names.add(d.getName());
        return new ArrayList<>(names);
    }

    static File container() {
        try { return Bukkit.getWorldContainer(); } catch (RuntimeException e) { return null; }   // the mock server has none (its own exception)
    }

    static String command(CommandSender s, String[] a) {
        if (!s.hasPermission("falloutak.gm")) return "! only a GM moves people between the maps";
        String arg = a.length == 0 ? "" : a[0].toLowerCase(java.util.Locale.ROOT);
        if (arg.equals("load")) return a.length < 2 ? "/maps load <world>: " + String.join(", ", worlds(container())) : load(a[1]);
        if (arg.equals("unload")) return a.length < 2 ? "/maps unload <world>: " + loaded() : unload(a[1]);
        if (!(s instanceof Player p)) return "/maps load|unload <world> (go and back: players only)";
        return switch (arg) {
            case "go" -> {
                if (a.length < 2) yield "/maps go <world> [all]: " + String.join(", ", worlds(container()));
                boolean all = a.length > 2 && a[2].equalsIgnoreCase("all");
                yield go(p, a[1], all);
            }
            case "back" -> back(p);
            default -> "Maps: " + String.join(", ", worlds(container())) + " · you are in " + p.getWorld().getName()
                    + " · /maps go <world> [all] · /maps back";
        };
    }

    /** The world by name, loaded from the server folder when it is not loaded yet; null if there is none. */
    static World world(String name) {
        World w = Bukkit.getWorld(name);
        if (w != null) return w;
        File c = container();
        if (c == null || !new File(new File(c, name), "level.dat").isFile()) return null;
        return Bukkit.createWorld(new WorldCreator(name));
    }

    /** Loads a world (if it is not loaded yet) and tells where its spawn and the ground there are. */
    static String load(String name) {
        World w = world(name);
        if (w == null) return "! no world \"" + name + "\" in the server folder";
        Location a = arrival(w), s = w.getSpawnLocation();
        return w.getName() + " loaded: spawn " + s.getBlockX() + " " + s.getBlockY() + " " + s.getBlockZ() + ", arrival at y " + a.getBlockY()
                + " on " + w.getBlockAt(a.getBlockX(), a.getBlockY() - 1, a.getBlockZ()).getType().getKey().getKey()
                + ", heights " + w.getMinHeight() + " to " + w.getMaxHeight();
    }

    static String loaded() {
        List<String> n = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) n.add(w.getName());
        return String.join(", ", n);
    }

    /**
     * Unloads a world (saved first): whoever is in it goes back to where he stood before his jump, or to the main world's spawn.
     * The main world (the first) never unloads.
     */
    static String unload(String name) {
        World w = Bukkit.getWorld(name);
        if (w == null) return "! \"" + name + "\" is not loaded (loaded: " + loaded() + ")";
        World main = Bukkit.getWorlds().get(0);
        if (w.equals(main)) return "! the main world never unloads";
        int moved = 0;
        for (Player q : new ArrayList<>(w.getPlayers())) {
            Location l = from.remove(q.getUniqueId());
            if (l == null || l.getWorld() == null || l.getWorld().equals(w)) l = arrival(main);
            q.teleport(l);
            moved++;
        }
        from.values().removeIf(l -> w.equals(l.getWorld()));
        boolean ok;
        try { ok = Bukkit.unloadWorld(w, true); } catch (RuntimeException e) { ok = false; }
        if (!ok) return "! " + name + " did not unload" + (moved > 0 ? " (" + moved + " moved out)" : "");
        return name + " unloaded" + (moved > 0 ? ", " + moved + " moved out" : "") + ": its folder can be edited or deleted now";
    }

    /** The world's spawn, on the top block there (a map from Arnis may spawn in a roof). */
    static Location arrival(World w) {
        Location s = w.getSpawnLocation();
        int y = Math.max(w.getHighestBlockYAt(s.getBlockX(), s.getBlockZ()) + 1, s.getBlockY());
        return new Location(w, s.getBlockX() + 0.5, y, s.getBlockZ() + 0.5);
    }

    /** Moves the GM (or every player online) to the world's spawn, remembering where each one stood. */
    static String go(Player p, String name, boolean all) {
        World w = world(name);
        if (w == null) return "! no world \"" + name + "\" in the server folder: /maps";
        List<Player> who = all ? new ArrayList<>(Bukkit.getOnlinePlayers()) : List.of(p);
        Location to = arrival(w);
        int moved = 0;
        for (Player q : who) {
            if (q.getWorld().equals(w)) continue;
            from.put(q.getUniqueId(), q.getLocation());
            q.teleport(to);
            moved++;
        }
        if (moved == 0) return "! already in " + w.getName();
        return (all ? moved + " moved" : "You are") + " in " + w.getName() + " (its spawn). /maps back returns " + (all ? "each who types it." : "you.");
    }

    /** Back to where the player stood before his last jump, or to the main world's spawn. */
    static String back(Player p) {
        Location l = from.remove(p.getUniqueId());
        if (l == null || l.getWorld() == null) l = arrival(Bukkit.getWorlds().get(0));
        p.teleport(l);
        return "Back in " + l.getWorld().getName() + ".";
    }

    /** The GM's page: every world with a jump, and back. */
    static void page(Player g) {
        List<Component> lines = new ArrayList<>();
        lines.add(line("You are in " + g.getWorld().getName() + ". A world not loaded yet loads on the first jump (a few seconds).", NamedTextColor.WHITE));
        lines.add(line("The 1:1 local maps (09 §3.1 B), e.g. Jihlava from OpenStreetMap by Arnis: as on day D, and ruined.", NamedTextColor.GRAY));
        List<Ui.Btn> b = new ArrayList<>();
        for (String n : worlds(container())) {
            if (n.equals(g.getWorld().getName())) continue;
            b.add(btn("Go: " + n, "you alone, to its spawn", x -> { say(x, go(x, n, false)); page(x); }));
            b.add(btn("Everyone: " + n, "every player online, to its spawn", x -> { say(x, go(x, n, true)); page(x); }));
        }
        b.add(btn("Back", "to where you stood before the last jump", x -> { say(x, back(x)); page(x); }));
        menu(g, "GM · maps", lines, b, Menus::gm);
    }
}
