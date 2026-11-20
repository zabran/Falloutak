package cz.lollywood.falloutak;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * The base's store in Minecraft chests (Marek 11-19: *"one chest per type, rows by tiers and rarities. And physical Mess Chest that
 * players can return items into."*). A type's chest opens as a chest screen: a row for each tier and rarity in the store, its first
 * slot naming the row, then every unlocked item of it; a click takes one, shift + click up to 16. Since round 4 the chests hold PARTS
 * and materials: the items are assembled from them when going out ({@link Works#assemble}). **A chest of an item CATEGORY** (round 5,
 * Marek 11-20: *"make sure that the "store chests" still works (by automaticaly assembling the parts into the item, if there is enough
 * parts)"*) shows that category's unlocked items by tier and rarity, and a click ASSEMBLES one from the store's parts: a left click
 * the worn parts first (the item at their average), a right click the undamaged ones. The Mess Chest is a plain chest: whatever
 * store items are in it when it is closed go into their rows (and are unlocked), an assembled item or an armour piece comes apart
 * into ALL its parts at its condition (round 5: the parts keep it); anything else stays.
 */
final class Storage implements Listener {
    final FalloutakPlugin pl;

    Storage(FalloutakPlugin pl) { this.pl = pl; }

    /** A type chest's screen: which item each slot takes out. */
    static final class Holder implements InventoryHolder {
        final String type;
        final int page;
        /** slot → {row key, set}. */
        final Map<Integer, String[]> takes = new HashMap<>();
        /** A category's chest (round 5): slot → the design it assembles. */
        final Map<Integer, String> designs = new HashMap<>();
        int prev = -1, next = -1;
        Inventory inv;

        Holder(String type, int page) { this.type = type; this.page = page; }

        @Override public Inventory getInventory() { return inv; }
    }

    // ------------------------------------------------------------------ which chest
    /** The blocks of the chest at this block (a double chest's two halves), or empty if it is no chest. */
    static List<Base.Spot> spots(Block b) {
        List<Base.Spot> l = new ArrayList<>();
        if (b == null || !(b.getState() instanceof org.bukkit.block.Chest c)) return l;
        InventoryHolder h = c.getInventory().getHolder();
        if (h instanceof DoubleChest dc) {
            for (InventoryHolder side : new InventoryHolder[]{dc.getLeftSide(), dc.getRightSide()})
                if (side instanceof org.bukkit.block.Chest s) l.add(spot(s.getBlock()));
        }
        if (l.isEmpty()) l.add(spot(b));
        return l;
    }

    static Base.Spot spot(Block b) { return new Base.Spot(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()); }

    /** What a chest inventory is in the store (a type, the Mess Chest), or null. */
    String what(Inventory inv) {
        InventoryHolder h = inv == null ? null : inv.getHolder();
        List<Block> blocks = new ArrayList<>();
        if (h instanceof org.bukkit.block.Chest c) blocks.add(c.getBlock());
        if (h instanceof DoubleChest dc)
            for (InventoryHolder side : new InventoryHolder[]{dc.getLeftSide(), dc.getRightSide()})
                if (side instanceof org.bukkit.block.Chest s) blocks.add(s.getBlock());
        for (Block b : blocks) {
            String w = pl.base.chestAt(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
            if (w != null) return w;
        }
        return null;
    }

    // ------------------------------------------------------------------ a type's chest
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        String what = pl.base.chestAt(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        if (what == null || what.equals(Base.MESS)) return;
        e.setCancelled(true);
        open(e.getPlayer(), what, 0);
    }

    static final int ROWS = 6, PER_ROW = 8;

    void open(Player p, String type, int page) {
        Holder h = new Holder(type, page);
        h.inv = Bukkit.createInventory(h, ROWS * 9, Component.text(type + " · the base's store" + (Base.category(type) ? " (assembled)" : "")));
        if (Base.category(type)) fillCategory(h, p);
        else fill(h, p);
        p.openInventory(h.inv);
    }

    /**
     * A category's chest (round 5): a row for each tier and rarity among its unlocked items, then those items, each with how many the
     * store's parts make now (of them from undamaged parts) and the condition it comes out at.
     */
    void fillCategory(Holder h, Player p) {
        Inventory inv = h.inv;
        inv.clear();
        h.designs.clear();
        h.prev = h.next = -1;
        Map<String, List<Production.Design>> rows = new TreeMap<>();
        for (Production.Design d : Production.DESIGNS.values())
            if (d.category.equals(h.type) && pl.base.unlocked(d)) rows.computeIfAbsent(d.tier + "|" + d.rarity, k -> new ArrayList<>()).add(d);
        List<List<Production.Design>> lines = new ArrayList<>();
        for (List<Production.Design> l : rows.values())
            for (int i = 0; i < l.size(); i += PER_ROW) lines.add(l.subList(i, Math.min(l.size(), i + PER_ROW)));
        if (lines.isEmpty()) {
            inv.setItem(22, label(Material.BARRIER, "Nothing unlocked here yet", "Tier I-II at rarity 1 assembles from the start;",
                    "bring one in through the Mess Chest, or the GM unlocks it"));
            return;
        }
        boolean paged = lines.size() > ROWS;
        int per = paged ? ROWS - 1 : ROWS, from = h.page * per;
        String fit = pl.character(p).fit();
        for (int row = 0; row < per && from + row < lines.size(); row++) {
            List<Production.Design> line = lines.get(from + row);
            Production.Design first = line.get(0);
            int at = row * 9;
            inv.setItem(at, label(Material.PAPER, "Tier " + Rules.roman(first.tier) + ", rarity " + first.rarity,
                    "assembled from the store's parts on a click", "left: the worn parts first · right: the undamaged"));
            for (int k = 0; k < line.size(); k++) {
                Production.Design d = line.get(k);
                int can = pl.base.canAssemble(d), fresh = pl.base.canAssembleFresh(d);
                ItemStack it = d.armour() ? Items.piece(new Character.Worn(d.set, d.slot, 100, fit)) : Items.make(Items.madeId(d), 1);
                ItemMeta im = it.getItemMeta();
                List<Component> lore = new ArrayList<>(im.lore() == null ? List.of() : im.lore());
                List<Component> top = new ArrayList<>();
                if (can == 0) top.add(Component.text("not enough parts: " + pl.base.missing(d), NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
                else {
                    double worn = pl.base.conditionOf(d, false);
                    top.add(Component.text(can * d.makes + " ready (" + fresh * d.makes + " from undamaged parts) · takes " + Production.partsText(d), NamedTextColor.GREEN)
                            .decoration(TextDecoration.ITALIC, false));
                    top.add(Component.text("left click: the worn parts first (comes out at " + Math.round(worn) + " %) · right click: the undamaged"
                            + (d.armour() ? "" : " · shift: up to 16"), NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
                }
                top.addAll(lore);
                im.lore(top);
                int n = Math.max(1, Math.min(99, can * d.makes));
                Battle.cosmetic(() -> im.setMaxStackSize(n));
                it.setItemMeta(im);
                Battle.cosmetic(() -> it.setAmount(n));
                inv.setItem(at + 1 + k, it);
                h.designs.put(at + 1 + k, d.key());
            }
        }
        if (paged) {
            int pages = (lines.size() + per - 1) / per;
            if (h.page > 0) { h.prev = 45; inv.setItem(45, label(Material.ARROW, "Previous page", "page " + h.page + " of " + pages)); }
            if (h.page < pages - 1) { h.next = 53; inv.setItem(53, label(Material.ARROW, "Next page", "page " + (h.page + 2) + " of " + pages)); }
        }
    }

    /** The rows of the screen: a label, then up to 8 items; a long row goes on in the next one; pages past six rows. */
    void fill(Holder h, Player p) {
        Inventory inv = h.inv;
        inv.clear();
        h.takes.clear();
        h.prev = h.next = -1;
        String fit = pl.character(p).fit();
        List<String[]> lines = new ArrayList<>();   // {key, set...} per screen row
        for (Base.Row r : pl.base.rows(h.type)) {
            List<String> sets = r.sets();
            if (sets.isEmpty()) { lines.add(new String[]{r.pool().key()}); continue; }
            for (int i = 0; i < sets.size(); i += PER_ROW) {
                List<String> part = sets.subList(i, Math.min(sets.size(), i + PER_ROW));
                String[] line = new String[part.size() + 1];
                line[0] = r.pool().key();
                for (int k = 0; k < part.size(); k++) line[k + 1] = part.get(k);
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            inv.setItem(22, label(Material.BARRIER, "Nothing here yet", "Put items into the Mess Chest: each goes to its type's chest,",
                    "in the row of its tier and rarity"));
            return;
        }
        boolean paged = lines.size() > ROWS;
        int per = paged ? ROWS - 1 : ROWS, from = h.page * per;
        for (int row = 0; row < per && from + row < lines.size(); row++) {
            String[] line = lines.get(from + row);
            Base.Pool pool = pl.base.pools.get(line[0]);
            int at = row * 9;
            List<String> about = new ArrayList<>();
            if (pool.fresh > 0) about.add(pool.fresh + " undamaged (100 %)");
            if (pool.worn() > 0) about.add(pool.worn() + " worn, " + Math.round(pool.average()) + " % on average");
            about.add(line.length == 1 ? "none of it unlocked: the GM's Base page" : "left click an item: one · shift + left click: up to 16");
            inv.setItem(at, label(Material.PAPER, "Tier " + Rules.roman(pool.tier) + ", rarity " + pool.rarity, about.toArray(new String[0])));
            for (int k = 1; k < line.length; k++) {
                ItemStack it = Items.make(Items.storeId(pool.type, pool.tier, pool.rarity, line[k]), 1);
                ItemMeta im = it.getItemMeta();
                List<Component> lore = new ArrayList<>(im.lore() == null ? List.of() : im.lore());
                lore.add(0, Component.text("left click: one · shift + left click: up to 16 (" + pool.count + " in the store)", NamedTextColor.GREEN)
                        .decoration(TextDecoration.ITALIC, false));
                im.lore(lore);
                int n = Math.max(1, Math.min(99, pool.count));
                Battle.cosmetic(() -> im.setMaxStackSize(n));
                it.setItemMeta(im);
                Battle.cosmetic(() -> it.setAmount(n));
                inv.setItem(at + k, it);
                h.takes.put(at + k, new String[]{line[0], line[k]});
            }
        }
        if (paged) {
            int pages = (lines.size() + per - 1) / per;
            if (h.page > 0) { h.prev = 45; inv.setItem(45, label(Material.ARROW, "Previous page", "page " + h.page + " of " + pages)); }
            if (h.page < pages - 1) { h.next = 53; inv.setItem(53, label(Material.ARROW, "Next page", "page " + (h.page + 2) + " of " + pages)); }
        }
    }

    static ItemStack label(Material m, String name, String... lines) {
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text(name, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String l : lines) lore.add(Component.text(l, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        im.lore(lore);
        it.setItemMeta(im);
        return it;
    }

    /** A click: one item. */
    String click(Player p, Holder h, int slot) { return click(p, h, slot, false, false); }

    /** A click on the store's screen: take the item under it (shift: up to 16), or turn the page. Returns what the player is told (or null). */
    String click(Player p, Holder h, int slot, boolean right, boolean shift) {
        if (slot == h.prev) { open(p, h.type, h.page - 1); return null; }
        if (slot == h.next) { open(p, h.type, h.page + 1); return null; }
        String dk = h.designs.get(slot);
        if (dk != null) {
            // round 5: the category's chest assembles it from the parts (left: the worn first, right: the undamaged)
            Production.Design d = Production.DESIGNS.get(dk);
            int can = d == null ? 0 : pl.base.canAssemble(d);
            if (can == 0) { fillCategory(h, p); return d == null ? null : "! not enough parts in the store: " + pl.base.missing(d); }
            int n = d.armour() ? 1 : Math.min(can, shift ? Math.max(1, 16 / Math.max(1, d.makes)) : 1);
            String r = Works.give(p, d, n, right);
            fillCategory(h, p);
            return r;
        }
        String[] t = h.takes.get(slot);
        if (t == null) return null;
        Base.Pool pool = pl.base.pools.get(t[0]);
        if (pool == null) { fill(h, p); return "! none left in the store"; }
        int want = shift ? 16 : 1, got = 0;
        while (got < want && pl.base.takeThing(t[0], t[1])) got++;
        if (got == 0) { fill(h, p); return "! none left in the store"; }
        Items.giveStack(p, Items.make(Items.storeId(pool.type, pool.tier, pool.rarity, t[1]), got));
        Sfx.to(p, "store.take");
        fill(h, p);
        return "You take " + got + " × " + t[1] + ".";
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);   // nothing goes in or moves: items come back through the Mess Chest
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != h.inv) return;
        String r = click(p, h, e.getSlot(), e.getClick().isRightClick(), e.getClick().isShiftClick());
        if (r != null) Menus.say(p, r);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ the Mess Chest
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!Base.MESS.equals(what(e.getInventory()))) return;
        Set<String> parts = new TreeSet<>();
        int n = sortMess(e.getInventory(), pl.base, parts);
        if (n <= 0 || !(e.getPlayer() instanceof Player p)) return;
        Sfx.to(p, "store.sort");
        Menus.say(p, n + (n == 1 ? " item" : " items") + " sorted from the Mess Chest into the store.");
        // Reverse Engineer waits for part 8 (round 5, Marek 11-20: "this is seriously strong now ... once it is gained, it skyrockets
        // research"): its instant schematics are gone; the parts it came apart into are kept for research's inspiration
    }

    static int sortMess(Inventory inv, Base base) { return sortMess(inv, base, new TreeSet<>()); }

    /**
     * The store's items out of a Mess Chest: things into their rows (each unlocked); an assembled item or an armour piece comes apart
     * into ALL its parts at its condition (round 5), and an item of 0.42.0's old types into its parts too; anything else stays in it.
     * {@code parts} collects the {@code type|tier} of the parts that came back from items (part 8's inspiration). Returns how many items.
     */
    static int sortMess(Inventory inv, Base base, Set<String> parts) {
        int n = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null) continue;
            int k = Math.max(1, it.getAmount());
            String id = Items.id(it);
            String[] th = Items.ttrOf(id);
            if (th != null && base.putThing(th[0], Integer.parseInt(th[1]), Integer.parseInt(th[2]), th[3], k)) {
                n += k;   // a thing, a chem or a gear item the catalogue knows (part 7)
                inv.setItem(i, null);
                continue;
            }
            if (th != null && base.legacy(th[0], Integer.parseInt(th[1]), Integer.parseInt(th[2]), k, 1) >= 0) {
                n += k;   // a thing of 0.42.0's old types (a pistol, pistol ammo...): its parts
                inv.setItem(i, null);
                continue;
            }
            Production.Design d = Items.made(id);
            Character.Worn w = d == null ? Items.worn(it) : null;
            if (w != null) d = Production.piece(w.set(), w.slot());
            if (d == null) continue;   // nothing the store knows: it stays in the chest
            if (w != null) for (int j = 0; j < k; j++) base.disassemble(w);
            else base.disassemble(d, k, Items.madeCondition(it));
            for (Production.Part p : d.parts) parts.add(p.type + "|" + p.tier);
            inv.setItem(i, null);
            n += k;
        }
        return n;
    }
}
