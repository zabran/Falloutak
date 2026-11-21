package cz.lollywood.falloutak;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static cz.lollywood.falloutak.Menus.*;

/**
 * The last-minute hauls, part of the prologue (`07-PRODUCTION.md` §16, RULED 11-21; the data generated into {@code data/hauls.json}
 * by LollyWood-Lore `_tools/fallout_catalogue.py`, `07b` §8). Marek 11-21 (verbatim): *"Are the hauls' contents and numbers in the
 * table right? - yes, now it seems fine."* · *"Should I build the hauls into the plugin now - yes, it will be part of prologue."* On
 * day D each player grabs ONE haul for the base on the way down from the Jihlava shopping centre into the shelter: its items go into
 * the base's store once, the bookshop's XP books onto the base's shelf. Books are knowledge, not items (07 §2): the shelf keeps each
 * title and who has read it; an XP book gives 100 × tier × rarity XP to each reader, once a title a person, and a group's members each
 * add that much to its pool (07 §4.11, RULED round 3).
 */
final class Hauls {
    private Hauls() {}

    record Item(String type, int tier, int rarity, int count, String what) {
        String text() { return count + " " + type + " " + Rules.roman(tier) + (rarity > 1 ? " R" + rarity : "") + (what.isEmpty() ? "" : " (" + what + ")"); }
    }

    record Haul(String id, String name, String shop, String note, int books, List<Item> items) {
        String contents() {
            StringJoiner j = new StringJoiner(" + ");
            for (Item i : items) j.add(i.text());
            if (books > 0) j.add(books + " XP books of Tier " + Rules.roman(BOOK_TIER) + " (" + 100 * BOOK_TIER + " XP to each reader, once a title)");
            return j.toString();
        }
    }

    /** A player's pick: who grabbed it (his name then), which haul, the titles it put on the shelf. */
    static final class Pick {
        String player = "";
        String haul = "";
        final List<String> books = new ArrayList<>();
    }

    /** A book on the base's shelf: its title, tier and rarity, who brought it, who has read it ("pc:<uuid>:<name>", "group:<name>"). */
    static final class Book {
        String title = "";
        int tier = 3, rarity = 1;
        String from = "";
        final Set<String> readBy = new TreeSet<>();
    }

    static final Map<String, Haul> HAULS = new LinkedHashMap<>();
    static final List<String> TITLES = new ArrayList<>();
    static int BOOK_TIER = 3;

    static {
        try (InputStream in = Hauls.class.getResourceAsStream("/data/hauls.json")) {
            if (in != null) {
                JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                BOOK_TIER = o.get("book_tier").getAsInt();
                o.getAsJsonArray("titles").forEach(t -> TITLES.add(t.getAsString()));
                for (JsonElement e : o.getAsJsonArray("rows")) {
                    JsonObject h = e.getAsJsonObject();
                    List<Item> items = new ArrayList<>();
                    for (JsonElement x : h.getAsJsonArray("items")) {
                        JsonObject i = x.getAsJsonObject();
                        items.add(new Item(i.get("type").getAsString(), i.get("tier").getAsInt(), i.get("rarity").getAsInt(), i.get("count").getAsInt(),
                                i.get("what").getAsString()));
                    }
                    HAULS.put(h.get("id").getAsString(), new Haul(h.get("id").getAsString(), h.get("name").getAsString(), h.get("shop").getAsString(),
                            h.get("note").getAsString(), h.get("books").getAsInt(), List.copyOf(items)));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("data/hauls.json: " + e.getMessage(), e);
        }
    }

    /** An XP book's XP (07 §4.11, RULED round 3: 100 × tier × rarity). */
    static long xp(Book b) { return 100L * b.tier * b.rarity; }

    /** Its reading time: 12 h × the tier (07 §4.11, ruled for skill books: *"increase the time to at least tripple"*; the XP book's too [mine]). */
    static int hours(Book b) { return 12 * b.tier; }

    static Book book(Production pr, String title) {
        for (Book b : pr.library) if (b.title.equals(title)) return b;
        return null;
    }

    /** The bookshop's next title not yet on the shelf (a new pair for each player who picks it). */
    static String nextTitle(Production pr) {
        for (String t : TITLES) if (book(pr, t) == null) return t;
        return null;
    }

    /** A character as a reader: the player and the character's name (a new character from the group is a new person). */
    static String reader(Player p, Character c) { return "pc:" + p.getUniqueId() + ":" + c.name; }

    // ------------------------------------------------------------------ the rules
    /** The player grabs a haul: once a player; its items into the store, its books onto the shelf. Returns what to tell him. */
    static String pick(Production pr, Base base, Player p, String id) {
        Haul h = HAULS.get(id);
        if (h == null) return "! no such haul: " + id;
        Pick had = pr.hauls.get(p.getUniqueId().toString());
        if (had != null) return "! you have grabbed your haul already: " + name(had.haul) + " (the GM can take a pick back)";
        Pick k = new Pick();
        k.player = p.getName();
        k.haul = id;
        for (Item i : h.items) base.addUnits(i.type, i.tier, i.rarity, i.count);
        for (int n = 0; n < h.books; n++) {
            String t = nextTitle(pr);
            if (t == null) break;
            Book b = new Book();
            b.title = t;
            b.tier = BOOK_TIER;
            b.from = p.getName();
            pr.library.add(b);
            k.books.add(t);
        }
        pr.hauls.put(p.getUniqueId().toString(), k);
        pr.save();
        return "You grab " + h.name + " in " + h.shop + " on the way down: " + h.contents()
                + (k.books.isEmpty() ? "" : ". On the base's shelf: " + String.join("; ", k.books)) + ".";
    }

    static String name(String id) { Haul h = HAULS.get(id); return h == null ? id : h.name; }

    /**
     * The GM takes a pick back (a wrong click, a test): only while all of it is still there, every item in the store and every
     * book unread; then the player may pick again [mine].
     */
    static String undo(Production pr, Base base, String who) {
        Pick k = pr.hauls.get(who);
        if (k == null) return "! no pick";
        Haul h = HAULS.get(k.haul);
        if (h != null)
            for (Item i : h.items)
                if (base.count(i.type, i.tier, i.rarity) < i.count)
                    return "! the store no longer holds " + i.text() + ": part of " + h.name + " is used, so it stays";
        for (String t : k.books) { Book b = book(pr, t); if (b != null && !b.readBy.isEmpty()) return "! " + t + " has been read: the pick stays"; }
        if (h != null) for (Item i : h.items) base.takeUnits(i.type, i.tier, i.rarity, i.count);
        pr.library.removeIf(b -> k.books.contains(b.title));
        pr.hauls.remove(who);
        pr.save();
        return k.player + "'s " + name(k.haul) + " taken back: " + (h == null ? "" : h.contents()) + " out of the store; " + k.player + " may pick again.";
    }

    /** A character reads a book: once a title a person, 100 × tier × rarity XP (into his group's pool too, part 6). */
    static String read(Production pr, Player p, Character c, String title) {
        Book b = book(pr, title);
        if (b == null) return "! no such book on the shelf: " + title;
        String key = reader(p, c);
        if (b.readBy.contains(key)) return "! " + c.name + " has read " + title + " already (once a title)";
        b.readBy.add(key);
        pr.save();
        String got = pl.roster.award(p, xp(b), title);
        return c.name + " reads " + title + " (" + hours(b) + " h of reading): " + got;
    }

    /** A group reads a book: each member adds the book's XP to the pool, once a title a group (07 §4.11). */
    static String groupRead(Production pr, String group, String title) {
        Book b = book(pr, title);
        if (b == null) return "! no such book on the shelf: " + title;
        Population.Group g = pl.roster.pop.group(group);
        if (g == null) return "! no group " + group;
        int n = pl.roster.pop.members(g.name);
        if (n <= 0) return "! " + g.name + " has nobody to read";
        String key = "group:" + g.name;
        if (b.readBy.contains(key)) return "! " + g.name + " has read " + title + " already";
        b.readBy.add(key);
        pr.save();
        String r = pl.roster.pool(g.name, (double) n * xp(b));
        return g.name + ": " + n + (n == 1 ? " member reads " : " members read ") + title + " (" + hours(b) + " h each, " + n * hours(b)
                + " reading hours; one copy, so one reader at a time): +" + n * xp(b) + " XP into the pool" + (r.isEmpty() ? "" : "; " + r);
    }

    // ------------------------------------------------------------------ the pages
    static Production pr() { return pl.production; }

    /** The player's page: grab one haul (Actions → The last-minute haul). */
    static void page(Player p) {
        Production pr = pr();
        Pick mine = pr.hauls.get(p.getUniqueId().toString());
        List<Component> lines = new ArrayList<>();
        lines.add(line("Day D: on the way down from the Jihlava shopping centre into the shelter, each player grabs ONE haul for the base"
                + " (once). Its items go into the base's store, the bookshop's XP books onto the base's shelf.", NamedTextColor.GRAY));
        lines.add(line(mine == null ? "You have not grabbed yours yet." : "You grabbed: " + name(mine.haul) + ".",
                mine == null ? NamedTextColor.YELLOW : NamedTextColor.GREEN));
        StringJoiner others = new StringJoiner("; ");
        for (Pick k : pr.hauls.values()) others.add(k.player + ": " + name(k.haul));
        if (others.length() > 0) lines.add(line("Grabbed so far: " + others, NamedTextColor.WHITE));
        List<Ui.Btn> b = new ArrayList<>();
        for (Haul h : HAULS.values()) {
            long n = pr.hauls.values().stream().filter(k -> k.haul.equals(h.id)).count();
            b.add(btn(h.name + (n > 0 ? " (" + n + ")" : ""), h.shop + ": " + h.contents() + (h.note.isEmpty() ? "" : " · " + h.note),
                    x -> confirm(x, h.id)));
        }
        if (!pr.library.isEmpty()) b.add(btn("The base's books (" + pr.library.size() + ")", "XP books: read each title once", Hauls::library));
        menu(p, "The last-minute haul", lines, b, Menus::actions);
    }

    static void confirm(Player p, String id) {
        Haul h = HAULS.get(id);
        if (pr().hauls.containsKey(p.getUniqueId().toString())) { say(p, pick(pr(), pl.base, p, id)); page(p); return; }
        ask(p, h.name, h.shop + ": " + h.contents() + (h.note.isEmpty() ? "" : ". " + h.note) + ". One haul a player: this is yours?", List.of(),
                btn("Grab it", "into the base's store, once", x -> { say(x, pick(pr(), pl.base, x, id)); page(x); }), Hauls::page);
    }

    /** The base's shelf: each book, who has read it; read it yourself or with a group. */
    static void library(Player p) {
        Production pr = pr();
        Character c = pl.character(p);
        List<Component> lines = new ArrayList<>();
        lines.add(line("Books are knowledge, not items (07 §2): an XP book gives 100 × tier × rarity XP to each reader, once a title a person; "
                + "a group's members each add that much to its pool. Reading takes 12 h × the tier.", NamedTextColor.GRAY));
        List<Ui.Btn> b = new ArrayList<>();
        for (Book k : pr.library) {
            boolean read = k.readBy.contains(reader(p, c));
            b.add(btn(k.title + (read ? " · read" : " · " + xp(k) + " XP"), "Tier " + Rules.roman(k.tier) + (k.rarity > 1 ? " R" + k.rarity : "")
                    + ", brought by " + k.from + " · " + hours(k) + " h of reading · read by " + (k.readBy.isEmpty() ? "nobody yet" : readers(k)),
                    x -> book(x, k.title)));
        }
        if (pr.library.isEmpty()) lines.add(line("No books yet.", NamedTextColor.RED));
        menu(p, "The base's books", lines, b, Works::main);
    }

    static String readers(Book k) {
        StringJoiner j = new StringJoiner(", ");
        for (String r : k.readBy) j.add(r.startsWith("group:") ? r.substring(6) + " (a group)" : r.substring(r.lastIndexOf(':') + 1));
        return j.toString();
    }

    static void book(Player p, String title) {
        Production pr = pr();
        Book k = book(pr, title);
        if (k == null) { library(p); return; }
        Character c = pl.character(p);
        List<Component> lines = new ArrayList<>();
        lines.add(line(title + ": an XP book of Tier " + Rules.roman(k.tier) + ", " + xp(k) + " XP to each reader, " + hours(k) + " h of reading.", NamedTextColor.WHITE));
        lines.add(line("Read by " + (k.readBy.isEmpty() ? "nobody yet" : readers(k)) + ".", NamedTextColor.GRAY));
        List<Ui.Btn> b = new ArrayList<>();
        if (!k.readBy.contains(reader(p, c))) b.add(btn("Read it (" + c.name + ")", "+" + xp(k) + " XP, " + hours(k) + " h", x -> { say(x, read(pr(), x, pl.character(x), title)); library(x); }));
        for (Population.Group g : pl.roster.pop.groups)
            if (!k.readBy.contains("group:" + g.name) && pl.roster.pop.members(g.name) > 0)
                b.add(btn(g.name + " reads it", pl.roster.pop.members(g.name) + " members × " + xp(k) + " XP into the pool", x -> { say(x, groupRead(pr(), g.name, title)); library(x); }));
        menu(p, title, lines, b, Hauls::library);
    }

    /** The GM's page: who grabbed what; a pick taken back while all of it is still there. */
    static void gmPage(Player p) {
        Production pr = pr();
        List<Component> lines = new ArrayList<>();
        lines.add(line(pr.hauls.size() + " hauls grabbed · " + pr.library.size() + " books on the shelf", NamedTextColor.WHITE));
        List<Ui.Btn> b = new ArrayList<>();
        for (Map.Entry<String, Pick> e : new ArrayList<>(pr.hauls.entrySet()))
            b.add(btn("Take back: " + e.getValue().player + " · " + name(e.getValue().haul), "only while every item is still in the store and every book unread",
                    x -> { say(x, undo(pr(), pl.base, e.getKey())); gmPage(x); }));
        if (pr.hauls.isEmpty()) lines.add(line("Nobody has grabbed a haul yet (each player: Actions → The last-minute haul).", NamedTextColor.GRAY));
        menu(p, "GM · the last-minute hauls", lines, b, Menus::gm);
    }
}
