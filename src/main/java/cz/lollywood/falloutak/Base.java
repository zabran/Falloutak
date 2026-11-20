package cz.lollywood.falloutak;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * The base's store of generic items (05c §7), kept in Minecraft chests (Marek 11-19, verbatim: *"The store if possible, I would like to
 * use MC chests, with a in MC chest-based storage system (one chest per type, rows by tiers and rarities). And physical Mess Chest that
 * players can return items into."*). The data side, pure Java (the tests run it); the chests themselves: {@link Storage}.
 * <ul>
 * <li>A ROW of the store = a type, a tier and a rarity (TTR): how many items, how many of them are UNDAMAGED, and the worn ones'
 * condition summed (Marek 11-19, verbatim: *"maybe use average condition, but with special category for undamaged? Players would like
 * to use these for the most dangerous action and while repairing all of them is too expensive, repairing some makes sense"*). A worn
 * item taken out gets the worn ones' average, an undamaged one 100 %; an item put back adds its own (at 100 % to the undamaged), so no
 * condition is made or lost, and a piece repaired to full and put back is undamaged again (`05c` §7).</li>
 * <li>UNLOCKED items (Marek 11-19: *"we should keep track of which items are "unlocked" (can be made in base, or were already brought
 * there). Only unlocked items are shown and selectable."*): everything of Tier I-II at rarity 1 (it can be made), every piece that came
 * in through the Mess Chest, and what the GM unlocks.</li>
 * <li>ONE store for the whole base, shared by every group (Marek 11-19).</li>
 * <li>A piece taken out is made for the taker's body (the base's crafters fit it while he prepares) [mine]; free for now (Marek 11-19:
 * *"maybe it does not matter and we should allow it for the sake of simplification"*), the refit's hours belong to the base's work
 * once part 6/7 count it.</li>
 * </ul>
 */
final class Base {
    /** Undamaged = shown as 100 % (the sheet rounds), the kits and combining reach it (05c §5). */
    static final double UNDAMAGED = 99.5;

    /** One row: its type, tier and rarity, how many, how many of them undamaged, and the WORN ones' condition summed (0-100 each). */
    static final class Pool {
        String type = "";
        int tier = 1, rarity = 1, count, fresh;
        double condition;

        String key() { return Base.key(type, tier, rarity); }

        int worn() { return Math.max(0, count - fresh); }

        /** The worn ones' average condition (0 without any). */
        double average() { return worn() == 0 ? 0 : condition / worn(); }
    }

    /** A chest block. */
    record Spot(String world, int x, int y, int z) {
        boolean at(String w, int bx, int by, int bz) { return world.equals(w) && x == bx && y == by && z == bz; }

        String text() { return x + " " + y + " " + z; }
    }

    /** One item of the old store (0.25.0): read once, added to its row. */
    static final class OldUnit {
        String type = "";
        int tier = 1, rarity = 1;
        double condition = 100;
    }

    /** What the file holds. */
    static final class Saved {
        List<Pool> pools = new ArrayList<>();
        List<String> unlocked = new ArrayList<>();
        Map<String, List<Spot>> chests = new LinkedHashMap<>();
        List<OldUnit> units;
    }

    /** The armour's types are the four slots (05c §7 [mine]). */
    static final Map<String, String> ARMOUR_TYPE = new LinkedHashMap<>();
    static {
        ARMOUR_TYPE.put("Head", "Head armour");
        ARMOUR_TYPE.put("Chest", "Body armour");
        ARMOUR_TYPE.put("Legs", "Leg armour");
        ARMOUR_TYPE.put("Feet", "Footwear");
    }

    /** The Mess Chest's name among the chests. */
    static final String MESS = "Mess Chest";

    final Map<String, Pool> pools = new LinkedHashMap<>();
    /** Unlocked beyond the start: a piece ({@code set|slot}, brought in) or a whole set (its name, the GM's). */
    final Set<String> unlocked = new TreeSet<>();
    /** The chests: a type (or {@link #MESS}) → its blocks (a double chest has two). */
    final Map<String, List<Spot>> chests = new LinkedHashMap<>();
    private final Path file;

    /** A store kept in {@code <dataFolder>/base.json}; null = in memory only (tests). */
    Base(Path dataFolder) {
        this.file = dataFolder == null ? null : dataFolder.resolve("base.json");
        load();
    }

    static String key(String type, int tier, int rarity) { return type + ", Tier " + Rules.roman(tier) + ", rarity " + rarity; }

    static String slotOf(String type) {
        for (Map.Entry<String, String> e : ARMOUR_TYPE.entrySet()) if (e.getValue().equals(type)) return e.getKey();
        return null;
    }

    static String pieceId(String set, String slot) { return set + "|" + slot; }

    // ------------------------------------------------------------------ unlocked
    /** Can the base hand this piece out: made from the start (Tier I-II, rarity 1), brought in, or unlocked by the GM. */
    boolean unlocked(Data.Armour a, String slot) {
        return a != null && a.pieces().contains(slot)
                && (Character.knownAtStart(a) || unlocked.contains(a.name()) || unlocked.contains(pieceId(a.name(), slot)));
    }

    /** The GM unlocks a whole set, or locks it again (pieces brought in stay unlocked). Returns whether it is unlocked now. */
    boolean toggleSet(String set) {
        boolean on = !unlocked.remove(set);
        if (on) unlocked.add(set);
        save();
        return on;
    }

    // ------------------------------------------------------------------ the rows
    /** A piece into the store (through the Mess Chest): its row grows, the piece is unlocked. False if it is no armour the store knows. */
    boolean put(Character.Worn w) {
        Data.Armour a = w == null ? null : Data.ARMOUR.get(w.set());
        if (a == null || !ARMOUR_TYPE.containsKey(w.slot())) return false;
        add(ARMOUR_TYPE.get(w.slot()), a.tier(), a.rarity(), Math.max(0, Math.min(100, w.condition())));
        unlocked.add(pieceId(a.name(), w.slot()));
        save();
        return true;
    }

    /** One item into its row: undamaged ones counted apart, a worn one's condition into the sum. */
    private Pool add(String type, int tier, int rarity, double condition) {
        Pool p = pools.computeIfAbsent(key(type, tier, rarity), k -> { Pool q = new Pool(); q.type = type; q.tier = tier; q.rarity = rarity; return q; });
        p.count++;
        if (condition >= UNDAMAGED) p.fresh++;
        else p.condition += condition;
        return p;
    }

    /** A row of a chest: its TTR and the unlocked items it can be taken as. */
    record Row(Pool pool, List<String> sets) {}

    /** The rows of one type's chest with something in them, by tier then rarity: each with its unlocked items (the data's order). */
    List<Row> rows(String type) {
        List<Row> l = new ArrayList<>();
        if (slotOf(type) == null && !Production.TYPES.containsKey(type)) return l;
        List<Pool> ps = new ArrayList<>();
        for (Pool p : pools.values()) if (p.type.equals(type) && p.count > 0) ps.add(p);
        ps.sort(Comparator.comparingInt((Pool p) -> p.tier).thenComparingInt(p -> p.rarity));
        for (Pool p : ps) l.add(new Row(p, choices(p)));
        return l;
    }

    List<String> choices(Pool p) {
        String slot = slotOf(p.type);
        List<String> l = new ArrayList<>();
        if (slot != null) {
            for (Data.Armour a : Data.ARMOUR.values())
                if (a.tier() == p.tier && a.rarity() == p.rarity && unlocked(a, slot)) l.add(a.name());
            return l;
        }
        Production.ItemType t = Production.TYPES.get(p.type);
        if (t != null) for (Production.Named n : t.items)
            if (n.tier == p.tier && n.rarity == p.rarity && unlocked(t.name, n)) l.add(n.name);
        if (l.isEmpty() && (madeAtStart(p.tier, p.rarity) || unlocked.contains(key(p.type, p.tier, p.rarity))))
            l.add(genericName(p.type, p.tier, p.rarity));   // a row with no named item: the type itself (part 7)
        return l;
    }

    /** Tier I-II at rarity 1: the base makes it from the start (the gate, 05c §7). */
    static boolean madeAtStart(int tier, int rarity) { return tier <= 2 && rarity == 1; }

    /** A named item (not armour) can be handed out: made from the start, its row made or brought in, or unlocked by the GM. */
    boolean unlocked(String type, Production.Named n) {
        return madeAtStart(n.tier, n.rarity) || unlocked.contains(n.name) || unlocked.contains(key(type, n.tier, n.rarity));
    }

    static String genericName(String type, int tier, int rarity) { return type + " " + Rules.roman(tier) + (rarity > 1 ? " R" + rarity : ""); }

    // ------------------------------------------------------------------ units (part 7: production takes and makes them)
    /** How many of a type and tier (rarity 0 = any). */
    int count(String type, int tier, int rarity) {
        int n = 0;
        for (Pool p : pools.values()) if (p.type.equals(type) && p.tier == tier && (rarity == 0 || p.rarity == rarity)) n += p.count;
        return n;
    }

    /**
     * Takes n units (rarity 0 = the lowest rarities first), the worn ones first so the undamaged stay. Returns the lowest rarity
     * taken, or 0 when there were not n (then nothing is taken).
     */
    int takeUnits(String type, int tier, int rarity, int n) {
        if (n <= 0) return rarity == 0 ? 1 : rarity;
        if (count(type, tier, rarity) < n) return 0;
        List<Pool> ps = new ArrayList<>();
        for (Pool p : pools.values()) if (p.type.equals(type) && p.tier == tier && (rarity == 0 || p.rarity == rarity) && p.count > 0) ps.add(p);
        ps.sort(Comparator.comparingInt(q -> q.rarity));
        int lowest = 0, left = n;
        for (Pool p : ps) {
            while (left > 0 && p.count > 0) {
                if (p.worn() > 0) { double c = p.average(); p.condition = p.worn() == 1 ? 0 : Math.max(0, p.condition - c); }
                else p.fresh--;
                p.count--;
                left--;
                if (lowest == 0) lowest = p.rarity;
            }
            if (left == 0) break;
        }
        save();
        return lowest;
    }

    /** n units made in the base: undamaged, into their row; the row is unlocked (made in the base, 05c §7). */
    void addUnits(String type, int tier, int rarity, int n) {
        for (int i = 0; i < n; i++) add(type, tier, rarity, 100);
        unlocked.add(key(type, tier, rarity));
        if (slotOf(type) != null) for (Data.Armour a : Data.ARMOUR.values())
            if (a.tier() == tier && a.rarity() == rarity && a.pieces().contains(slotOf(type))) unlocked.add(pieceId(a.name(), slotOf(type)));
        save();
    }

    /** A tool wears: one item of the row loses these points of condition; at 0 it is gone [mine]. */
    void wear(String type, int tier, double points) {
        Pool p = null;
        for (Pool q : pools.values()) if (q.type.equals(type) && q.tier == tier && q.count > 0 && (p == null || q.rarity < p.rarity)) p = q;
        if (p == null) return;
        if (p.worn() == 0) { p.fresh--; p.condition += 100; }   // an undamaged one becomes the worn one
        p.condition -= points;
        if (p.condition <= 0) { p.count--; p.condition = 0; }   // worn out: gone
        save();
    }

    /** A thing (not armour) into its row through the Mess Chest: undamaged, unlocked. False if the type is unknown. */
    boolean putThing(String type, int tier, int rarity, String name, int n) {
        if (!Production.TYPES.containsKey(type) || n <= 0) return false;
        for (int i = 0; i < n; i++) add(type, tier, rarity, 100);
        if (name != null && !name.isBlank()) unlocked.add(name);
        save();
        return true;
    }

    /** One named thing out of a row (a click on its chest): a worn one first (things have no condition yet). False if none. */
    boolean takeThing(String key, String name) {
        Pool p = pools.get(key);
        if (p == null || p.count <= 0 || !choices(p).contains(name)) return false;
        if (p.worn() > 0) { double c = p.average(); p.condition = p.worn() == 1 ? 0 : Math.max(0, p.condition - c); }
        else p.fresh--;
        p.count--;
        save();
        return true;
    }

    /** A worn one if the row has any, else an undamaged one (the chest's left click). */
    Character.Worn take(String key, String set, String bodyFit) {
        Pool p = pools.get(key);
        return take(key, set, bodyFit, p != null && p.worn() == 0);
    }

    /**
     * One item out of a row as the unlocked set chosen, made for the taker's body: an undamaged one at 100 %, or a worn one at the worn
     * ones' average. Null if the row has none of that kind or the set is not one of its choices.
     */
    Character.Worn take(String key, String set, String bodyFit, boolean undamaged) {
        Pool p = pools.get(key);
        if (p == null || (undamaged ? p.fresh : p.worn()) <= 0 || !choices(p).contains(set)) return null;
        double c;
        if (undamaged) { c = 100; p.fresh--; }
        else { c = p.average(); p.condition = p.worn() == 1 ? 0 : Math.max(0, p.condition - c); }
        p.count--;
        save();
        return new Character.Worn(set, slotOf(p.type), c, bodyFit == null ? Character.STANDARD : bodyFit);
    }

    // ------------------------------------------------------------------ the chests
    /** What the chest at this block is: a type, {@link #MESS}, or null. */
    String chestAt(String world, int x, int y, int z) {
        for (Map.Entry<String, List<Spot>> e : chests.entrySet())
            for (Spot s : e.getValue()) if (s.at(world, x, y, z)) return e.getKey();
        return null;
    }

    /** These blocks become the chest of a type (or the Mess Chest); a type has one chest, the Mess Chest may be several. */
    void setChest(String what, List<Spot> spots) {
        forget(spots);
        if (what.equals(MESS)) chests.computeIfAbsent(MESS, k -> new ArrayList<>()).addAll(spots);
        else chests.put(what, new ArrayList<>(spots));
        save();
    }

    /** These blocks are no store chest any more. */
    void forget(List<Spot> spots) {
        for (List<Spot> l : chests.values()) l.removeIf(spots::contains);
        chests.values().removeIf(List::isEmpty);
        save();
    }

    /** Every type a chest can hold: the armour's four, then part 7's catalogue (`data/itemtypes.json`). */
    static List<String> types() {
        List<String> l = new ArrayList<>(ARMOUR_TYPE.values());
        for (String t : Production.TYPES.keySet()) if (!l.contains(t)) l.add(t);
        return l;
    }

    // ------------------------------------------------------------------ the file
    private void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            Saved s = Store.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Saved.class);
            if (s == null) return;
            if (s.pools != null) for (Pool p : s.pools) if (p != null && (slotOf(p.type) != null || Production.TYPES.containsKey(p.type))) {
                // 0.26.0 summed every item: a row that was all at full condition is all undamaged now
                if (p.fresh == 0 && p.count > 0 && p.condition / p.count >= UNDAMAGED) { p.fresh = p.count; p.condition = 0; }
                pools.put(p.key(), p);
            }
            if (s.unlocked != null) unlocked.addAll(s.unlocked);
            if (s.chests != null) s.chests.forEach((k, v) -> { if (v != null && !v.isEmpty()) chests.put(k, new ArrayList<>(v)); });
            if (s.units != null) for (OldUnit u : s.units) if (u != null && slotOf(u.type) != null) add(u.type, u.tier, u.rarity, u.condition);
        } catch (IOException | RuntimeException e) {
            try { Files.move(file, file.resolveSibling("base.bad-" + System.currentTimeMillis() + ".json")); } catch (IOException ignored) { }
        }
    }

    void save() {
        if (file == null) return;
        Saved s = new Saved();
        s.pools = new ArrayList<>(pools.values());
        s.unlocked = new ArrayList<>(unlocked);
        s.chests = chests;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("base.json.tmp");
            Files.writeString(tmp, Store.GSON.toJson(s), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) { }
    }
}
