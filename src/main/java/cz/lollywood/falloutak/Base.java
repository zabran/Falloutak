package cz.lollywood.falloutak;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * The base's store of generic items (05c §7), kept in Minecraft chests (Marek 11-19, verbatim: *"The store if possible, I would like to
 * use MC chests, with a in MC chest-based storage system (one chest per type, rows by tiers and rarities. And physical Mess Chest that
 * players can return items into."*). The data side, pure Java (the tests run it); the chests themselves: {@link Storage}.
 * <ul>
 * <li>A ROW of the store = a type, a tier and a rarity (TTR): how many; a tool's row also how many are undamaged and the worn ones'
 * condition summed (Marek 11-19: *"maybe use average condition, but with special category for undamaged?"*).</li>
 * <li><b>PARTS, and the items ASSEMBLED from them when going out</b> (round 4, Marek 11-20, verbatim: *"I think the armor slots could
 * be simplified to just armor parts, with each slot made of several armor parts (could be another recipe, but an instant one). It also
 * simplifies repair."* · *"creating just the parts and the item assembled out of them when going out would work better"*): the store
 * holds the parts (`data/designs.json` says what each item takes); {@link #assemble} takes them out as the item, instantly; back
 * through the Mess Chest an item comes apart into ALL its parts at its condition ({@link #disassemble}).</li>
 * <li><b>The parts keep their condition</b> (round 5, Marek 11-20, verbatim: *""loses the damaged share" - this trivialises repair.
 * ... if you believe it is better to not use this, we could use that average condition for parts + the undamaged parts."*): a row
 * keeps how many are undamaged and the worn ones' condition summed; an item assembled from worn parts comes out at their average, from
 * undamaged ones undamaged; the repair = the kits, combining, the workbench's repair job ({@link #repair}).</li>
 * <li>A store chest of an item CATEGORY (round 5: *"make sure that the "store chests" still works (by automaticaly assembling the parts
 * into the item, if there is enough parts)"*): its items assemble from the parts on a click ({@link Storage}).</li>
 * <li>UNLOCKED items (Marek 11-19: *"we should keep track of which items are "unlocked" (can be made in base, or were already brought
 * there). Only unlocked items are shown and selectable."*): everything of Tier I-II at rarity 1 (it can be made), every item that came
 * in through the Mess Chest, and what the GM unlocks.</li>
 * <li>ONE store for the whole base, shared by every group (Marek 11-19).</li>
 * <li>An armour piece assembled is made for the taker's body (the base's crafters fit it while he prepares) [mine]; free for now
 * (Marek 11-19: *"maybe it does not matter and we should allow it for the sake of simplification"*).</li>
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

    /** One item of the old store (0.25.0): read once, into parts. */
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
        Map<String, Integer> wornOut = new LinkedHashMap<>();
        List<OldUnit> units;
        List<Data.Recipe> genePool;
    }

    /** The Mess Chest's name among the chests. */
    static final String MESS = "Mess Chest";

    final Map<String, Pool> pools = new LinkedHashMap<>();
    /** Unlocked beyond the start: an armour piece ({@code set|slot}, brought in), a set (its name, the GM's), an item ({@code category|name}), a row (its key). */
    final Set<String> unlocked = new TreeSet<>();
    /** The chests: a type (or {@link #MESS}) → its blocks (a double chest has two). */
    final Map<String, List<Spot>> chests = new LinkedHashMap<>();
    /** Items worn out in the base's work, by type (part 8 round 4: a eureka counts them). */
    final Map<String, Integer> wornOut = new LinkedHashMap<>();
    /**
     * THE GENE POOL (03b §7a; 09b round 12, Marek 11-23, verbatim: *"Mutations are historically the low tech way into genetic research, so
     * why not add it here."* · *"ghouls should be possible to reconstruct into genes as well"*): the genes made in the game (a mutation's,
     * the ghoul's and its stages') whose carrier gave the base a sample, by name: gene therapy gives them like any gene (03d).
     */
    final Map<String, Data.Recipe> genePool = new LinkedHashMap<>();
    private final Path file;
    /** While the file is read: nothing is written back until it is whole (an old type converted to parts saves once, at the end). */
    private boolean loading;

    /** A store kept in {@code <dataFolder>/base.json}; null = in memory only (tests). */
    Base(Path dataFolder) {
        this.file = dataFolder == null ? null : dataFolder.resolve("base.json");
        load();
    }

    static String key(String type, int tier, int rarity) { return type + ", Tier " + Rules.roman(tier) + ", rarity " + rarity; }

    static String pieceId(String set, String slot) { return set + "|" + slot; }

    // ------------------------------------------------------------------ unlocked
    /** Can the base assemble this armour piece: made from the start (Tier I-II, rarity 1), brought in, or unlocked by the GM. */
    boolean unlocked(Data.Armour a, String slot) {
        return a != null && a.pieces().contains(slot)
                && (Character.knownAtStart(a) || unlocked.contains(a.name()) || unlocked.contains(pieceId(a.name(), slot)));
    }

    /** Can the base assemble this item: Tier I-II at rarity 1 from the start, brought in, or unlocked by the GM. */
    boolean unlocked(Production.Design d) {
        if (d == null) return false;
        if (d.armour()) return unlocked(Data.ARMOUR.get(d.set), d.slot);
        return madeAtStart(d.tier, d.rarity) || unlocked.contains(d.key());
    }

    /** The GM unlocks a whole set or an item (its key), or locks it again (what was brought in stays). Returns whether it is unlocked now. */
    boolean toggleSet(String set) {
        boolean on = !unlocked.remove(set);
        if (on) unlocked.add(set);
        save();
        return on;
    }

    // ------------------------------------------------------------------ the rows
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
        if (!Production.TYPES.containsKey(type)) return l;
        List<Pool> ps = new ArrayList<>();
        for (Pool p : pools.values()) if (p.type.equals(type) && p.count > 0) ps.add(p);
        ps.sort(Comparator.comparingInt((Pool p) -> p.tier).thenComparingInt(p -> p.rarity));
        for (Pool p : ps) l.add(new Row(p, choices(p)));
        return l;
    }

    List<String> choices(Pool p) {
        List<String> l = new ArrayList<>();
        Production.ItemType t = Production.TYPES.get(p.type);
        if (t != null) for (Production.Named n : t.items)
            if (n.tier == p.tier && n.rarity == p.rarity && unlocked(t.name, n)) l.add(n.name);
        if (l.isEmpty() && (madeAtStart(p.tier, p.rarity) || unlocked.contains(key(p.type, p.tier, p.rarity))))
            l.add(genericName(p.type, p.tier, p.rarity));   // a row with no named item: the type itself (part 7)
        return l;
    }

    /** Tier I-II at rarity 1: the base makes it from the start (the gate, 05c §7). */
    static boolean madeAtStart(int tier, int rarity) { return tier <= 2 && rarity == 1; }

    /**
     * A named thing can be handed out. 0.60.0 (Marek 11-22, verbatim: *""An item is unlocked from the start only if a recipe the base knows
     * makes it. Otherwise it unlocks when it is brought in, or by the GM." - yes, 100%."*): a recipe the base knows makes it, or it was
     * made, brought in (the Mess Chest) or unlocked by the GM. Its row being made no longer opens the row's other names (making lye no
     * longer opens salt); a row with none of its names unlocked is still handed out as the type itself ({@link #choices}).
     */
    boolean unlocked(String type, Production.Named n) { return unlockedName(n.name); }

    /** 0.60.0: which named items a recipe the base knows makes; the plugin sets it to the base's Production. Null = the start recipes (tests). */
    transient java.util.function.Predicate<String> knownMaker;

    boolean makeable(String name) {
        if (knownMaker != null) return knownMaker.test(name);
        for (Production.Recipe r : Production.makersOf(name)) if (r.atStart()) return true;
        return false;
    }

    /** 0.60.0: a named item is open to the base: a known recipe makes it, or it was made, brought in or unlocked by the GM. */
    boolean unlockedName(String name) { return name != null && (unlocked.contains(name) || makeable(name)); }

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
    void addUnits(String type, int tier, int rarity, int n) { addUnits(type, tier, rarity, n, null); }

    /** 0.60.0: ... and the named items the recipe makes are unlocked (made in the base). */
    void addUnits(String type, int tier, int rarity, int n, List<String> items) {
        for (int i = 0; i < n; i++) add(type, tier, rarity, 100);
        if (n > 0) {
            unlocked.add(key(type, tier, rarity));
            if (items != null) unlocked.addAll(items);
        }
        save();
    }

    /** A tool wears: one item of the row loses these points of condition; at 0 it is gone [mine]. */
    void wear(String type, int tier, double points) {
        Pool p = null;
        for (Pool q : pools.values()) if (q.type.equals(type) && q.tier == tier && q.count > 0 && (p == null || q.rarity < p.rarity)) p = q;
        if (p == null) return;
        if (p.worn() == 0) { p.fresh--; p.condition += 100; }   // an undamaged one becomes the worn one
        p.condition -= points;
        if (p.condition <= 0) { p.count--; p.condition = 0; wornOut.merge(type, 1, Integer::sum); }   // worn out: gone
        save();
    }

    int wornOut(String type) { return wornOut.getOrDefault(type, 0); }

    /** A thing into its row through the Mess Chest: undamaged, unlocked. False if the type is unknown. */
    boolean putThing(String type, int tier, int rarity, String name, int n) {
        if (!Production.TYPES.containsKey(type) || n <= 0) return false;
        for (int i = 0; i < n; i++) add(type, tier, rarity, 100);
        if (name != null && !name.isBlank()) unlocked.add(name);
        save();
        return true;
    }

    /** One named thing out of a row (a click on its chest): a worn one first. False if none. */
    boolean takeThing(String key, String name) {
        Pool p = pools.get(key);
        if (p == null || p.count <= 0 || !choices(p).contains(name)) return false;
        if (p.worn() > 0) { double c = p.average(); p.condition = p.worn() == 1 ? 0 : Math.max(0, p.condition - c); }
        else p.fresh--;
        p.count--;
        save();
        return true;
    }

    // ------------------------------------------------------------------ the assembly (round 4; the condition: round 5)
    /** How many batches of this item the store's parts make now (0 if it is not unlocked). */
    int canAssemble(Production.Design d) {
        if (!unlocked(d) || d.parts.isEmpty()) return 0;
        int n = Integer.MAX_VALUE;
        for (Production.Part p : d.parts) n = Math.min(n, count(p.type, p.tier, d.rarity) / Math.max(1, p.count));
        return n;
    }

    /** What is missing for one batch ("" = nothing). */
    String missing(Production.Design d) {
        StringJoiner j = new StringJoiner(", ");
        for (Production.Part p : d.parts) {
            int have = count(p.type, p.tier, d.rarity);
            if (have < p.count) j.add((p.count - have) + " " + p.type + " " + Rules.roman(p.tier) + (d.rarity > 1 ? " R" + d.rarity : ""));
        }
        return j.toString();
    }

    /** How many batches of it the store's UNDAMAGED parts alone make. */
    int canAssembleFresh(Production.Design d) {
        if (!unlocked(d) || d.parts.isEmpty()) return 0;
        int n = Integer.MAX_VALUE;
        for (Production.Part p : d.parts) {
            Pool q = pools.get(key(p.type, p.tier, d.rarity));
            n = Math.min(n, (q == null ? 0 : q.fresh) / Math.max(1, p.count));
        }
        return n;
    }

    /** The condition one batch would come out at, the worn parts first ({@code fresh} = the undamaged first); -1 if it cannot. */
    double conditionOf(Production.Design d, boolean fresh) {
        if (canAssemble(d) < 1) return -1;
        double sum = 0;
        int n = 0;
        for (Production.Part p : d.parts) {
            Pool q = pools.get(key(p.type, p.tier, d.rarity));
            int k = p.count, f = fresh ? Math.min(k, q.fresh) : Math.max(0, k - q.worn());
            sum += 100.0 * f + (k - f) * q.average();
            n += k;
        }
        return n == 0 ? 100 : sum / n;
    }

    /** Takes the parts of {@code batches} batches out of the store, the worn first (round 4). False (nothing taken) if it cannot. */
    boolean assemble(Production.Design d, int batches) { return assemble(d, batches, false) >= 0; }

    /**
     * Takes the parts of {@code batches} batches out of the store (instant, round 4), the worn ones first or ({@code fresh}) the
     * undamaged first. Returns the items' condition = the parts' average (round 5), or -1 (nothing taken) if it cannot.
     */
    double assemble(Production.Design d, int batches, boolean fresh) {
        if (batches <= 0 || canAssemble(d) < batches) return -1;
        double sum = 0;
        int n = 0;
        for (Production.Part p : d.parts) {
            int k = p.count * batches;
            sum += take(p.type, p.tier, d.rarity, k, fresh);
            n += k;
        }
        save();
        double c = n == 0 ? 100 : sum / n;
        return c >= UNDAMAGED ? 100 : c;
    }

    /** n parts out of one row, the worn first or the undamaged first; their conditions summed (the row must hold n). */
    private double take(String type, int tier, int rarity, int n, boolean fresh) {
        Pool p = pools.get(key(type, tier, rarity));
        double sum = 0;
        for (int i = 0; i < n && p != null && p.count > 0; i++) {
            boolean undamaged = fresh ? p.fresh > 0 : p.worn() == 0;
            if (undamaged) { p.fresh--; sum += 100; }
            else { double c = p.average(); sum += c; p.condition = p.worn() == 1 ? 0 : Math.max(0, p.condition - c); }
            p.count--;
        }
        return sum;
    }

    /**
     * Items back through the Mess Chest come apart into ALL their parts, at their condition (round 5: the parts keep it, Marek's own
     * form: *"that average condition for parts + the undamaged parts"*): whole batches only; the item is unlocked (brought in). Returns
     * how many parts came back.
     */
    int disassemble(Production.Design d, int items, double condition) {
        if (d == null) return 0;
        int batches = items / Math.max(1, d.makes), back = 0;
        double c = Math.max(0, Math.min(100, condition));
        for (Production.Part p : d.parts) {
            int k = p.count * batches;
            for (int i = 0; i < k; i++) add(p.type, p.tier, d.rarity, c);
            if (k > 0) { unlocked.add(key(p.type, p.tier, d.rarity)); back += k; }
        }
        unlocked.add(d.armour() ? pieceId(d.set, d.slot) : d.key());
        save();
        return back;
    }

    // ------------------------------------------------------------------ the repair (round 5)
    /** How many worn parts a row holds (rarity 0 = every rarity). */
    int worn(String type, int tier, int rarity) {
        int n = 0;
        for (Pool p : pools.values()) if (p.type.equals(type) && p.tier == tier && (rarity == 0 || p.rarity == rarity)) n += p.worn();
        return n;
    }

    /** The rows with worn parts the repair job mends, by type, tier and rarity. */
    List<Pool> wornRows() {
        List<Pool> l = new ArrayList<>();
        for (Pool p : pools.values()) if (p.worn() > 0 && Production.REPAIR.types.containsKey(p.type)) l.add(p);
        l.sort(Comparator.comparing((Pool p) -> p.type).thenComparingInt(p -> p.tier).thenComparingInt(p -> p.rarity));
        return l;
    }

    /**
     * The repair job's run (round 5) [mine, EXAMPLE]: {@code points} of condition to each of up to {@code parts} worn parts of the row
     * (the worn ones are at their average: a part that reaches 100 joins the undamaged, else the average rises). Returns how many parts.
     */
    int repair(String type, int tier, int rarity, double points, int parts) {
        Pool p = pools.get(key(type, tier, rarity));
        if (p == null || p.worn() == 0 || points <= 0) return 0;
        int m = Math.min(parts, p.worn());
        double a = p.average();
        if (a + points >= UNDAMAGED) {
            p.fresh += m;
            p.condition = p.worn() == 0 ? 0 : Math.max(0, p.condition - m * a);
        } else p.condition += m * points;
        save();
        return m;
    }

    /** An armour piece back through the Mess Chest: its set's parts × its condition. -1 if it is no piece the data knows. */
    int disassemble(Character.Worn w) {
        Production.Design d = w == null ? null : Production.piece(w.set(), w.slot());
        return d == null ? -1 : disassemble(d, 1, w.condition());
    }

    /** An item of an old type (0.42.0's 46) → its parts at its tier and rarity, at its condition (share 0-1). -1 if the type is unknown there too. */
    int legacy(String type, int tier, int rarity, int units, double share) {
        Production.Legacy l = Production.LEGACY.get(type);
        if (l == null) return -1;
        int batches = units / Math.max(1, l.per), back = 0;
        double c = 100 * Math.max(0, Math.min(1, share));
        for (Production.Part p : l.parts) {
            int k = p.count * batches;
            for (int i = 0; i < k; i++) add(p.type, tier, rarity, c);
            if (k > 0) { unlocked.add(key(p.type, tier, rarity)); back += k; }
        }
        return back;
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

    /** Every type a chest can hold: part 7's catalogue (`data/itemtypes.json`; the parts first since round 4). */
    static List<String> types() { return new ArrayList<>(Production.TYPES.keySet()); }

    /** The item categories a chest can be (round 5: its items assembled from the parts on a click): the weapons', rounds', armour's. */
    static List<String> categories() {
        List<String> l = new ArrayList<>();
        for (Production.Category c : Production.CATEGORIES) l.add(c.name);
        return l;
    }

    static boolean category(String what) { return categories().contains(what); }

    // ------------------------------------------------------------------ the gene pool (03b §7a)
    /**
     * A carrier's sample (blood, a biopsy): his changed genes into the pool. A gene the pool has (the same recipe) or the data has is known; a
     * new one whose name the pool already holds gets a letter (*Skin M1b*). Returns the genes new to it, by their pool names.
     */
    List<String> sample(List<Data.Recipe> recipes) {
        List<String> added = new ArrayList<>();
        for (Data.Recipe r : recipes) {
            if (knows(r)) continue;
            Data.Recipe p = new Data.Recipe(r.base, r.slot, r.events, r.steps, r.ghoul);
            Data.Gene g = p.build();
            if (g == null || Data.GENES.containsKey(g.name())) continue;
            String name = g.name();
            for (char c = 'b'; genePool.containsKey(name) && c <= 'z'; c++) name = g.name() + c;
            p.name = name;
            genePool.put(name, p);
            Data.POOL.put(name, p.build());
            added.add(name);
        }
        if (!added.isEmpty()) save();
        return added;
    }

    /** Is this gene known: in the pool already (the same recipe), or one of the data's. */
    boolean knows(Data.Recipe r) {
        for (Data.Recipe x : genePool.values()) if (x.key().equals(r.key())) return true;
        Data.Gene g = r.build();
        return g != null && Data.GENES.containsKey(g.name());
    }

    /** The pool's genes of a slot. */
    List<Data.Gene> pooled(String slot) {
        List<Data.Gene> l = new ArrayList<>();
        for (String n : genePool.keySet()) { Data.Gene g = Data.gene(n); if (g != null && g.slot().equals(slot)) l.add(g); }
        return l;
    }

    // ------------------------------------------------------------------ the file
    private void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        loading = true;
        boolean converted = false;
        try {
            Saved s = Store.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Saved.class);
            if (s == null) return;
            if (s.unlocked != null) unlocked.addAll(s.unlocked);
            if (s.wornOut != null) wornOut.putAll(s.wornOut);
            if (s.genePool != null) for (Data.Recipe r : s.genePool) {
                Data.Gene g = r == null ? null : r.build();
                if (g != null) { genePool.put(g.name(), r); Data.POOL.put(g.name(), g); }
            }
            if (s.pools != null) for (Pool p : s.pools) {
                if (p == null || p.count <= 0) continue;
                if (Production.TYPES.containsKey(p.type)) {
                    // 0.26.0 summed every item: a row that was all at full condition is all undamaged now
                    if (p.fresh == 0 && p.condition / p.count >= UNDAMAGED) { p.fresh = p.count; p.condition = 0; }
                    pools.merge(p.key(), p, (a, b) -> { a.count += b.count; a.fresh += b.fresh; a.condition += b.condition; return a; });
                } else converted |= legacy(p.type, p.tier, p.rarity, p.count, (p.fresh * 100.0 + p.condition) / p.count / 100) >= 0;   // 0.42.0's types → parts
            }
            if (s.chests != null) s.chests.forEach((k, v) -> {
                if (v != null && !v.isEmpty() && (k.equals(MESS) || Production.TYPES.containsKey(k) || category(k))) chests.put(k, new ArrayList<>(v));
            });
            if (s.units != null) for (OldUnit u : s.units) if (u != null) converted |= legacy(u.type, u.tier, u.rarity, 1, u.condition / 100) >= 0;
        } catch (IOException | RuntimeException e) {
            try { Files.move(file, file.resolveSibling("base.bad-" + System.currentTimeMillis() + ".json")); } catch (IOException ignored) { }
        }
        loading = false;
        if (converted) save();
    }

    void save() {
        if (file == null || loading) return;
        Saved s = new Saved();
        s.pools = new ArrayList<>(pools.values());
        s.unlocked = new ArrayList<>(unlocked);
        s.chests = chests;
        s.wornOut = wornOut;
        s.genePool = new ArrayList<>(genePool.values());
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("base.json.tmp");
            Files.writeString(tmp, Store.GSON.toJson(s), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) { }
    }
}
