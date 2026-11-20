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
 * slot naming the row (how many undamaged, how many worn and their average condition), then every unlocked item of it; a left click
 * takes a worn one, a right click an undamaged one (Marek 11-19: undamaged ones apart, for the most dangerous jobs). The Mess Chest is a
 * plain chest: whatever store items are in it when it is closed go into their rows (and are unlocked); anything else stays.
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
        h.inv = Bukkit.createInventory(h, ROWS * 9, Component.text(type + " · the base's store"));
        fill(h, p);
        p.openInventory(h.inv);
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
            String slot = Base.slotOf(h.type);
            about.add(line.length == 1 ? "none of it unlocked: the GM's Base page" : slot == null ? "left click an item: one · shift + left click: up to 16"
                    : "left click an item: a worn one · right click: an undamaged one");
            inv.setItem(at, label(Material.PAPER, "Tier " + Rules.roman(pool.tier) + ", rarity " + pool.rarity, about.toArray(new String[0])));
            for (int k = 1; k < line.length; k++) {
                if (slot == null) {   // part 7's things: no condition yet, one or a handful a click
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
                    continue;
                }
                ItemStack it = Items.piece(new Character.Worn(line[k], slot, pool.worn() > 0 ? pool.average() : 100, fit));
                ItemMeta im = it.getItemMeta();
                List<Component> lore = new ArrayList<>(im.lore() == null ? List.of() : im.lore());
                lore.add(0, Component.text(pool.worn() > 0 ? "left click: a worn one at " + Math.round(pool.average()) + " % (" + pool.worn() + " worn)"
                        : "left click: an undamaged one (no worn ones)", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
                lore.add(1, Component.text(pool.fresh > 0 ? "right click: an undamaged one at 100 % (" + pool.fresh + ")" : "right click: no undamaged ones",
                        pool.fresh > 0 ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
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

    /** A left click: a worn one (an undamaged one if the row has no worn ones). */
    String click(Player p, Holder h, int slot) { return click(p, h, slot, false); }

    String click(Player p, Holder h, int slot, boolean undamaged) { return click(p, h, slot, undamaged, false); }

    /** A click on the store's screen: take the item under it (a right click: an undamaged one), or turn the page. Returns what the player is told (or null). */
    String click(Player p, Holder h, int slot, boolean undamaged, boolean shift) {
        if (slot == h.prev) { open(p, h.type, h.page - 1); return null; }
        if (slot == h.next) { open(p, h.type, h.page + 1); return null; }
        String[] t = h.takes.get(slot);
        if (t == null) return null;
        if (Base.slotOf(h.type) == null) {   // a thing (part 7)
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
        String fit = pl.character(p).fit();
        Base.Pool pool = pl.base.pools.get(t[0]);
        if (undamaged && pool != null && pool.count > 0 && pool.fresh == 0) return "! no undamaged ones in this row: a left click takes a worn one";
        Character.Worn w = undamaged ? pl.base.take(t[0], t[1], fit, true) : pl.base.take(t[0], t[1], fit);
        if (w == null) { fill(h, p); return "! none left in the store"; }
        Items.giveStack(p, Items.piece(w));
        Sfx.to(p, "store.take");
        fill(h, p);
        return "You take " + (w.condition() >= Base.UNDAMAGED ? "an undamaged " : "the ") + w.set() + " (" + w.slot().toLowerCase(Locale.ROOT) + ") at "
                + Math.round(w.condition()) + " %" + (Character.STANDARD.equals(w.fitName()) ? "." : ", made for a " + w.fitName() + " body.");
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
        int n = sortMess(e.getInventory(), pl.base);
        if (n > 0 && e.getPlayer() instanceof Player p) Sfx.to(p, "store.sort");
        if (n > 0 && e.getPlayer() instanceof Player p) Menus.say(p, n + (n == 1 ? " item" : " items") + " sorted from the Mess Chest into the store.");
    }

    /** The store's items out of a Mess Chest into their rows (each unlocked); anything else stays in it. Returns how many. */
    static int sortMess(Inventory inv, Base base) {
        int n = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            String[] th = it == null ? null : Items.ttrOf(Items.id(it));
            if (th != null && base.putThing(th[0], Integer.parseInt(th[1]), Integer.parseInt(th[2]), th[3], Math.max(1, it.getAmount()))) {
                n += Math.max(1, it.getAmount());   // a thing, a chem or a gear item the catalogue knows (part 7)
                inv.setItem(i, null);
                continue;
            }
            Character.Worn w = Items.worn(it);
            if (w == null) continue;
            if (!base.put(w)) continue;   // a piece the store does not know stays in the chest
            int k = Math.max(1, it.getAmount());
            for (int j = 1; j < k; j++) base.put(w);
            inv.setItem(i, null);
            n += k;
        }
        return n;
    }
}
