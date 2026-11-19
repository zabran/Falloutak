package cz.lollywood.falloutak;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * /npc, /pop and /group: the data store's step 2 (the workbook's NPC, Population and Factions tabs). Anyone may look; changing
 * needs {@code falloutak.gm} (operators). An NPC statblock is a character sheet saved under its name; {@code /npc edit} puts it in
 * place of your own sheet, so every /sheet page and form works on it, and {@code /npc done} gives yours back.
 */
final class Roster implements CommandExecutor {
    private final FalloutakPlugin pl;
    private final Path popFile;
    Population pop;
    /** Who is editing which NPC, and his own character meanwhile. */
    final Map<UUID, String> editing = new HashMap<>();
    final Map<UUID, Character> own = new HashMap<>();

    Roster(FalloutakPlugin pl) {
        this.pl = pl;
        this.popFile = pl.getDataFolder().toPath().resolve("population.json");
        try {
            pop = Population.load(popFile);
        } catch (Exception e) {
            pl.getLogger().warning("population.json unreadable (" + e.getMessage() + "): kept as .bad, starting from the workbook's example");
            try { Files.move(popFile, popFile.resolveSibling("population.bad-" + System.currentTimeMillis() + ".json")); } catch (Exception ignored) { }
        }
        if (pop == null) { pop = Population.example(); save(); }
    }

    void save() {
        try { pop.save(popFile); } catch (Exception e) { pl.getLogger().warning("could not save population.json: " + e); }
    }

    private static void say(CommandSender s, String text, NamedTextColor col) { s.sendMessage(Component.text(text, col)); }

    private static boolean gm(CommandSender s) {
        if (s.hasPermission("falloutak.gm")) return true;
        say(s, "Only the GM may change this (falloutak.gm).", NamedTextColor.RED);
        return false;
    }

    private static String rest(String[] a, int from) { return String.join(" ", Arrays.copyOfRange(a, Math.min(from, a.length), a.length)); }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "list";
        switch (cmd.getName()) {
            case "npc" -> npc(s, sub, a);
            case "pop" -> pop(s, sub, a);
            case "group" -> group(s, sub, a);
            default -> { return false; }
        }
        return true;
    }

    // ------------------------------------------------------------------ /npc
    private void npc(CommandSender s, String sub, String[] a) {
        Store st = pl.store;
        switch (sub) {
            case "list" -> {
                List<String> n = st.npcNames();
                say(s, "NPC statblocks (" + n.size() + "): " + (n.isEmpty() ? "none yet: /npc edit <name>" : String.join(", ", n.stream().map(x ->
                        pop.group(x) != null ? x + " (group, " + pop.members(x) + " people)" : x).toList())), NamedTextColor.AQUA);
            }
            case "edit" -> {
                if (!(s instanceof Player p)) { say(s, "Only a player can edit an NPC: it takes the place of his own sheet.", NamedTextColor.RED); return; }
                if (!gm(s)) return;
                if (a.length < 2) { say(s, "/npc edit <name>", NamedTextColor.GRAY); return; }
                if (pl.battle.inBattle(p)) { say(s, "End the battle demo first (/sheet battle).", NamedTextColor.RED); return; }
                String name = a[1];
                if (!Store.validName(name)) { say(s, "A name of letters, digits, _ and - only.", NamedTextColor.RED); return; }
                UUID u = p.getUniqueId();
                if (editing.containsKey(u)) saveNpc(p);
                else own.put(u, pl.character(p));
                Character c = st.loadNpc(name);
                boolean fresh = c == null;
                if (fresh) { c = Character.sample(name); c.name = name; }
                pl.characters.put(u, c);
                editing.put(u, name);
                say(s, (fresh ? "A new NPC statblock " : "Editing the NPC ") + name + ": /sheet and its forms (create, spend) now work on it; "
                        + "/npc save writes it, /npc done saves it and gives you your own sheet back.", NamedTextColor.GREEN);
            }
            case "save" -> { if (s instanceof Player p && gm(s)) { if (!saveNpc(p)) say(s, "You are not editing an NPC.", NamedTextColor.GRAY); } }
            case "done" -> { if (s instanceof Player p) { if (!done(p.getUniqueId())) say(s, "You are not editing an NPC.", NamedTextColor.GRAY); else say(s, "Your own sheet is back.", NamedTextColor.GREEN); } }
            case "show" -> {
                if (!(s instanceof Player p) || a.length < 2) { say(s, "/npc show <name> (a player: it opens the sheet)", NamedTextColor.GRAY); return; }
                Character c = st.loadNpc(a[1]);
                if (c == null) { say(s, "No NPC " + a[1] + " (/npc list).", NamedTextColor.RED); return; }
                SheetView.open(p, c, SheetGui.Page.OVERVIEW);
            }
            case "delete" -> {
                if (!gm(s) || a.length < 2) return;
                say(s, st.deleteNpc(a[1]) ? "NPC " + a[1] + " set aside (renamed .deleted, nothing lost)." : "No NPC " + a[1] + ".", NamedTextColor.YELLOW);
            }
            default -> say(s, "/npc [list] · edit <name> · save · done · show <name> · delete <name>", NamedTextColor.GRAY);
        }
    }

    /** Writes the NPC being edited; false if none. */
    boolean saveNpc(Player p) {
        String name = editing.get(p.getUniqueId());
        if (name == null) return false;
        try {
            pl.store.saveNpc(name, pl.characters.get(p.getUniqueId()));
            say(p, "NPC " + name + " saved.", NamedTextColor.GREEN);
        } catch (Exception e) {
            say(p, "Could not save NPC " + name + ": " + e.getMessage(), NamedTextColor.RED);
        }
        return true;
    }

    /** Saves the NPC being edited and puts the player's own character back; false if he was not editing. */
    boolean done(UUID u) {
        String name = editing.remove(u);
        if (name == null) return false;
        try { pl.store.saveNpc(name, pl.characters.get(u)); } catch (Exception e) { pl.getLogger().warning("could not save NPC " + name + ": " + e); }
        Character mine = own.remove(u);
        if (mine != null) pl.characters.put(u, mine); else pl.characters.remove(u);
        return true;
    }

    // ------------------------------------------------------------------ /pop
    private void pop(CommandSender s, String sub, String[] a) {
        switch (sub) {
            case "list" -> {
                say(s, "Population (" + pop.people.size() + "):", NamedTextColor.AQUA);
                for (Population.Person p : pop.people)
                    say(s, " " + p.name + " · " + p.hp + "/" + p.hpMax + " HP" + (p.state.isEmpty() ? "" : " · " + p.state) + " · " + p.species
                            + " · " + (p.group.isEmpty() ? "no group" : p.group) + (p.armor.isEmpty() ? "" : " · " + p.armor)
                            + (p.weapon1.isEmpty() && p.weapon2.isEmpty() ? "" : " · " + p.weapon1 + (p.weapon2.isEmpty() ? "" : " / " + p.weapon2))
                            + " · " + p.activity + (p.traits.isEmpty() ? "" : " · " + String.join(", ", p.traits)) + (p.born != 0 ? " · born " + p.born : ""),
                            NamedTextColor.WHITE);
            }
            case "add" -> {
                if (!gm(s)) return;
                if (a.length < 3) { say(s, "/pop add <name> <group> [species]", NamedTextColor.GRAY); return; }
                if (pop.person(a[1]) != null) { say(s, a[1] + " is already in the population (/pop set).", NamedTextColor.RED); return; }
                Population.Person p = new Population.Person();
                p.name = a[1];
                p.group = a[2];
                if (a.length > 3) p.species = a[3];
                pop.groupOrNew(a[2]);
                Character sb = pl.store.loadNpc(a[2]);   // the group's statblock gives the HP (part 6: one statblock per group)
                if (sb != null) { p.hpMax = sb.hpMax(); p.hp = p.hpMax; }
                pop.people.add(p);
                save();
                say(s, "Added " + p.name + " to " + p.group + (sb != null ? " (HP " + p.hpMax + " from the group's statblock)"
                        : " (no statblock " + p.group + " yet: /npc edit " + p.group + "; HP 0 until then or /pop set)") + ".", NamedTextColor.GREEN);
            }
            case "set" -> {
                if (!gm(s)) return;
                if (a.length < 4) { say(s, "/pop set <name> <field> <value>: hp, hpmax, state, species, group, armor, weapon1, weapon2, activity, traits, born", NamedTextColor.GRAY); return; }
                Population.Person p = pop.person(a[1]);
                if (p == null) { say(s, "No " + a[1] + " in the population.", NamedTextColor.RED); return; }
                String err = pop.set(p, a[2], rest(a, 3));
                if (err != null) { say(s, err, NamedTextColor.RED); return; }
                save();
                say(s, p.name + ": " + a[2] + " = " + rest(a, 3), NamedTextColor.GREEN);
            }
            case "remove" -> {
                if (!gm(s) || a.length < 2) return;
                Population.Person p = pop.person(a[1]);
                if (p == null) { say(s, "No " + a[1] + ".", NamedTextColor.RED); return; }
                pop.people.remove(p);
                save();
                say(s, p.name + " removed from the population.", NamedTextColor.YELLOW);
            }
            default -> say(s, "/pop [list] · add <name> <group> [species] · set <name> <field> <value> · remove <name>", NamedTextColor.GRAY);
        }
    }

    // ------------------------------------------------------------------ /group
    private void group(CommandSender s, String sub, String[] a) {
        switch (sub) {
            case "list" -> {
                say(s, "Groups (the level = Fallout 2's table on the XP per member, the workbook's formula):", NamedTextColor.AQUA);
                for (Population.Group f : pop.groups) {
                    int n = pop.members(f.name);
                    double lv = Population.level(f.xp, n);
                    int whole = (int) Math.floor(lv + 1e-9);
                    say(s, " " + f.name + " · " + n + (n == 1 ? " person" : " people") + " · XP " + Math.round(f.xp)
                            + (n > 0 ? " (" + Math.round(f.xp / n) + " a member)" : "") + " · level " + whole + " (" + SheetGui.f(Math.round(lv * 100) / 100.0)
                            + "; level " + (whole + 1) + " at " + Math.round(Population.xpForLevel(whole + 1)) + " a member)"
                            + " · statblock " + (pl.store.loadNpc(f.name) != null ? "yes" : "none (/npc edit " + f.name + ")")
                            + (f.unlocked.isEmpty() ? "" : " · unlocked " + String.join(", ", f.unlocked)), NamedTextColor.WHITE);
                }
            }
            case "add" -> {
                if (!gm(s) || a.length < 2) return;
                if (pop.group(a[1]) != null) { say(s, a[1] + " exists.", NamedTextColor.RED); return; }
                pop.groupOrNew(a[1]);
                save();
                say(s, "Group " + a[1] + " added.", NamedTextColor.GREEN);
            }
            case "xp" -> {
                if (!gm(s)) return;
                if (a.length < 3) { say(s, "/group xp <name> <amount> (how a group learns is part 6's open question: this only records it)", NamedTextColor.GRAY); return; }
                Population.Group f = pop.group(a[1]);
                if (f == null) { say(s, "No group " + a[1] + ".", NamedTextColor.RED); return; }
                try { f.xp = Math.max(0, f.xp + Double.parseDouble(a[2])); } catch (NumberFormatException e) { say(s, "A number, please.", NamedTextColor.RED); return; }
                save();
                say(s, f.name + ": XP " + Math.round(f.xp) + ", level " + SheetGui.f(Math.round(Population.level(f.xp, pop.members(f.name)) * 100) / 100.0), NamedTextColor.GREEN);
            }
            case "unlock" -> {   // 03b §2b (RULED 11-16: "yes, pay twice and whole group can use it")
                if (!gm(s)) return;
                if (a.length < 3) { say(s, "/group unlock <group> <template>: a premium template for every member, at 2 x its CoS points", NamedTextColor.GRAY); return; }
                Population.Group f = pop.group(a[1]);
                Data.Template t = Data.find(Data.TEMPLATES, rest(a, 2));
                if (f == null) { say(s, "No group " + a[1] + ".", NamedTextColor.RED); return; }
                if (t == null || !t.premium()) { say(s, "Not a premium template: " + rest(a, 2), NamedTextColor.RED); return; }
                if (!t.groupUnlock()) { say(s, t.name() + " cannot be unlocked for a group (Marek 11-16: \"due to the high points value, the template cannot be unlocked\").", NamedTextColor.RED); return; }
                if (!f.unlocked.contains(t.name())) f.unlocked.add(t.name());
                save();
                say(s, t.name() + " is now a starting option for everyone in " + f.name + " (2 x " + t.cosPoints() + " CoS points).", NamedTextColor.GREEN);
            }
            case "grant", "join" -> {
                if (!gm(s)) return;
                if (a.length < 3) { say(s, "/group grant <player> <template> (a premium template for that PC, at its CoS points) · /group join <player> <group>", NamedTextColor.GRAY); return; }
                Player who = org.bukkit.Bukkit.getPlayerExact(a[1]);
                if (who == null) { say(s, a[1] + " is not online.", NamedTextColor.RED); return; }
                Character c = pl.character(who);
                if (sub.equals("join")) {
                    if (pop.group(a[2]) == null) { say(s, "No group " + a[2] + ".", NamedTextColor.RED); return; }
                    c.group = pop.group(a[2]).name;
                    say(s, who.getName() + "'s character is in " + c.group + ".", NamedTextColor.GREEN);
                    return;
                }
                Data.Template t = Data.find(Data.TEMPLATES, rest(a, 2));
                if (t == null || !t.premium()) { say(s, "Not a premium template: " + rest(a, 2), NamedTextColor.RED); return; }
                if (!c.unlocked.contains(t.name())) c.unlocked.add(t.name());
                say(s, t.name() + " unlocked for " + who.getName() + " (" + t.cosPoints() + " CoS points): /sheet create offers it.", NamedTextColor.GREEN);
            }
            default -> say(s, "/group [list] · add <name> · xp <name> <amount> · unlock <group> <template> · grant <player> <template> · join <player> <group>", NamedTextColor.GRAY);
        }
    }
}
