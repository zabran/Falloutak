package cz.lollywood.falloutak;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * The things the menus use, as real Minecraft items in the pack (Marek 11-16: *"all players will need some actions options"*): an
 * item carries its kind in its data ({@code chem:Buffout}, {@code implant:Cyber Eyes}, {@code gear:Adrenaline},
 * {@code supplies:tier3}, {@code pipboy}), so the Actions menu offers what the character actually carries.
 */
final class Items {
    private Items() {}

    static NamespacedKey KEY, NPC;

    /** An NPC figurant's statblock name, or null (Marek 11-16: *"It should be usable on NPCs as well, right?"*). */
    static String npcName(org.bukkit.entity.Entity e) {
        return e == null || NPC == null ? null : e.getPersistentDataContainer().get(NPC, PersistentDataType.STRING);
    }

    /** 03b §3c: the gas mask and its filters, the air bottle, ear protection (worn from the Actions). */
    static final List<String> GEAR = List.of("Stimpak", "Adrenaline", "Defibrillator", "Gas filter", "CO filter", "Air bottle",
            "Earplugs", "Electronic muffs", "Small gas mask", "Armour repair kit");   // the big gas mask and the hazard suit are armour now (05c)
    static final List<String> SUPPLIES = List.of("improvised", "tier3", "tier4");

    static ItemStack make(String id, int count) {
        String[] k = id.split(":", 2);
        Material m = switch (k[0]) {
            case "chem" -> Material.GLASS_BOTTLE;
            case "implant" -> Material.IRON_NUGGET;
            case "therapy", "geneset" -> Material.AMETHYST_SHARD;
            case "armour" -> armourMaterial(Data.ARMOUR.get(k[1].replaceAll("\\|.*", "")), k[1].replaceAll(".*\\|", ""));
            case "gear" -> switch (k[1]) {   // items that do nothing on a right click in Minecraft (no armour, no block, no drink)
                case "Defibrillator" -> Material.COPPER_INGOT;
                case "Stimpak" -> Material.HONEY_BOTTLE;
                case "Gas mask" -> Material.PHANTOM_MEMBRANE;
                case "Gas filter" -> Material.CHARCOAL;
                case "CO filter" -> Material.FLINT;
                case "Air bottle" -> Material.NAUTILUS_SHELL;
                case "Earplugs" -> Material.RABBIT_HIDE;
                case "Electronic muffs" -> Material.ECHO_SHARD;
                case "Hazard suit" -> Material.SHULKER_SHELL;
                case "Small gas mask" -> Material.FIREWORK_STAR;
                case "Armour repair kit" -> Material.IRON_NUGGET;
                default -> Material.GLASS_BOTTLE;
            };
            case "supplies" -> Material.PAPER;
            default -> Material.CLOCK;   // the Pip-Boy
        };
        ItemStack it = new ItemStack(m, Math.max(1, count));
        ItemMeta im = it.getItemMeta();
        im.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, id);
        im.displayName(Component.text(title(id), color(k[0])).decoration(TextDecoration.ITALIC, false));
        if (k[0].equals("armour")) {   // its condition in the item; no vanilla wear; dyed leather [mine]
            im.getPersistentDataContainer().set(COND, PersistentDataType.DOUBLE, 100.0);
            im.setUnbreakable(true);
            im.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES, org.bukkit.inventory.ItemFlag.HIDE_UNBREAKABLE, org.bukkit.inventory.ItemFlag.HIDE_DYE);
            Data.Armour a = Data.ARMOUR.get(k[1].replaceAll("\\|.*", ""));
            if (a != null && !a.colour().isEmpty() && im instanceof org.bukkit.inventory.meta.LeatherArmorMeta lm)
                lm.setColor(org.bukkit.Color.fromRGB(Integer.parseInt(a.colour(), 16)));
        }
        List<Component> lore = new ArrayList<>();
        for (String line : describe(id).split("\n"))
            if (!line.isBlank()) lore.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        im.lore(lore);
        Icons.model(im, Icons.slug(id), k[0]);   // our own picture with the resource pack, the vanilla one without it
        it.setItemMeta(im);
        return it;
    }

    static String title(String id) {
        String[] k = id.split(":", 2);
        return switch (k[0]) {
            case "chem" -> k[1];
            case "implant" -> "Implant: " + k[1].replaceAll("\\|.*", "") + " (Tier " + switch (k[1].replaceAll(".*\\|", "")) { case "3" -> "III"; case "2" -> "II"; case "1" -> "I"; default -> "IV"; } + ")";
            case "therapy" -> "Gene therapy: " + k[1] + (Data.GENES.containsKey(k[1]) ? " (" + Data.GENES.get(k[1]).tier() + ")" : "");
            case "geneset" -> "Gene set: " + k[1];
            case "armour" -> k[1].replaceAll("\\|.*", "") + " (" + k[1].replaceAll(".*\\|", "").toLowerCase(Locale.ROOT) + ")";
            case "gear" -> k[1];
            case "supplies" -> "Surgical supplies (" + switch (k[1]) { case "tier3" -> "Tier III"; case "tier4" -> "Tier IV"; default -> "improvised"; } + ")";
            default -> "Pip-Boy";
        };
    }

    private static NamedTextColor color(String kind) {
        return switch (kind) { case "chem", "therapy", "geneset" -> NamedTextColor.LIGHT_PURPLE; case "implant" -> NamedTextColor.AQUA; case "armour" -> NamedTextColor.GOLD; case "pipboy" -> NamedTextColor.GREEN; default -> NamedTextColor.WHITE; };
    }

    static String describe(String id) {
        String[] k = id.split(":", 2);
        return switch (k[0]) {
            case "chem" -> {
                Data.Chem ch = Data.CHEMS.get(k[1]);
                yield ch == null ? "" : Menus.chemText(ch);
            }
            case "implant" -> {
                Data.Implant im = Data.IMPLANTS.get(k[1].replaceAll("\\|.*", ""));
                int tier = k[1].contains("|") ? Integer.parseInt(k[1].substring(k[1].indexOf('|') + 1)) : 4;
                yield im == null ? "" : "the " + im.slot() + " slot: " + Part3.numbersAt(im, tier) + "\nfitted by an operation (a medic's Actions, on you)";
            }
            case "therapy" -> {
                Data.Gene g = Data.GENES.get(k[1]);
                yield g == null ? "" : "the " + g.slot() + " slot: " + g.text() + "\nlasts " + SheetView.hours(Character.therapyHours(g))
                        + "\ngiven by a medic's Actions, on you (his Biology; used up)";
            }
            case "armour" -> pieceText(k[1].replaceAll("\\|.*", ""), k[1].replaceAll(".*\\|", ""), 100);
            case "geneset" -> {
                Data.GeneSet s = Data.SETS.get(k[1]);
                yield s == null ? "" : s.shop() + "\none 15-year course; given by a medic's Actions, on you (his Biology; used up)";
            }
            case "gear" -> switch (k[1]) {
                case "Stimpak" -> "+10 to +20 HP at once (Fallout 2); it does not stop bleeding (used up)";
                case "Adrenaline" -> "a revival: the chance × 0.4 instead of 0.2 (used up)";
                case "Gas mask" -> "the big one, like a helmet (a Head piece): worn from your Actions with the filters you carry,\nthe breath and the eyes; a gas filter keeps out every gas but bad air and CO, a CO filter CO (03b §3c)";
                case "Small gas mask" -> "an active item: right click it in hand to put it on or off (2 AP in rounds);\nmouth and nose only, the eyes stay bare; your filters as the big one's";
                case "Gas filter" -> "for the gas mask: every gas of 03b §3c but bad air and CO";
                case "Armour repair kit" -> "the worn piece +10 to +30 % condition by the repair skill (Crafting, Engineering for powered sets);\nfrom your Actions, not in rounds; Mr. Fixit doubles it (05c §5; used up)";
                case "CO filter" -> "for the gas mask: carbon monoxide (a filter of its own)";
                case "Air bottle" -> "worn from your Actions: an hour of air, every gas and no air at all (under water too);\nbottled oxygen against CO (used up)";
                case "Earplugs" -> "worn from your Actions: the eardrum roll halved, no ringing; P −1 while worn";
                case "Electronic muffs" -> "Tier IV, worn from your Actions: the eardrum roll halved, no ringing, no P −1";
                case "Hazard suit" -> "worn from your Actions: the skin sealed, so the acid fog's burns stay out (03b §3c);\nthe breath still needs a mask or a bottle";
                default -> "a revival: the chance × 1.0 (kept)";
            };
            case "supplies" -> "an operation: × " + Rules.SUPPLIES.getOrDefault(k[1], 0.6) + " (used up)";
            default -> "right click: your actions · sneak + right click: your sheet";
        };
    }

    // ------------------------------------------------------------------ armour (05c-ARMOUR.md)
    /** The piece's condition in its item (0-100 %). */
    static NamespacedKey COND;
    /** The body a piece is made for (05c §9.5; none = standard). */
    static NamespacedKey FIT;

    /** An armour piece's id: {@code armour:<set>|<slot>}. */
    static String armourId(String set, String slot) { return "armour:" + set + "|" + slot; }

    /** The vanilla look of a piece [mine]: the set's material for the slot (leather dyed). */
    static Material armourMaterial(Data.Armour a, String slot) {
        String m = a == null ? "leather" : a.material();
        String s = switch (slot) { case "Head" -> "HELMET"; case "Legs" -> "LEGGINGS"; case "Feet" -> "BOOTS"; default -> "CHESTPLATE"; };
        String base = switch (m) { case "iron" -> "IRON"; case "golden" -> "GOLDEN"; case "chainmail" -> "CHAINMAIL"; case "diamond" -> "DIAMOND";
            case "netherite" -> "NETHERITE"; default -> "LEATHER"; };
        Material x = Material.matchMaterial(base + "_" + s);
        return x == null ? Material.LEATHER_CHESTPLATE : x;
    }

    /** The piece in an item, or null. */
    static Character.Worn worn(ItemStack it) {
        String id = id(it);
        if (id == null || !id.startsWith("armour:") || !id.contains("|")) return null;
        String set = id.substring(7, id.indexOf('|')), slot = id.substring(id.indexOf('|') + 1);
        var pdc = it.getItemMeta().getPersistentDataContainer();
        Double c = pdc.get(COND, PersistentDataType.DOUBLE);
        String f = FIT == null ? null : pdc.get(FIT, PersistentDataType.STRING);
        return new Character.Worn(set, slot, c == null ? 100 : c, f == null ? Character.STANDARD : f);
    }

    /** What the player wears, slot by slot (a piece only counts in its own slot). */
    static Map<String, Character.Worn> readArmour(Player p) {
        Map<String, Character.Worn> m = new LinkedHashMap<>();
        var inv = p.getInventory();
        ItemStack[] it = {inv.getHelmet(), inv.getChestplate(), inv.getLeggings(), inv.getBoots()};
        for (int i = 0; i < 4; i++) {
            Character.Worn w = worn(it[i]);
            if (w != null && w.slot().equals(Character.ARMOUR_SLOTS.get(i))) m.put(w.slot(), w);
        }
        return m;
    }

    /** The character's piece conditions back into the worn items (after a hit or a repair). */
    static void writeArmour(Player p, Character c) {
        var inv = p.getInventory();
        ItemStack[] it = {inv.getHelmet(), inv.getChestplate(), inv.getLeggings(), inv.getBoots()};
        for (int i = 0; i < 4; i++) {
            Character.Worn w = worn(it[i]), now = c.armour.get(Character.ARMOUR_SLOTS.get(i));
            if (w == null || now == null || !now.set().equals(w.set())) continue;
            if (!now.fitName().equals(w.fitName())) {
                ItemMeta im = it[i].getItemMeta();
                im.getPersistentDataContainer().set(FIT, PersistentDataType.STRING, now.fitName());
                it[i].setItemMeta(im);
            }
            if (Math.abs(now.condition() - w.condition()) >= 1e-6) setCondition(it[i], now.condition());
        }
    }

    static void setCondition(ItemStack it, double cond) {
        Character.Worn w = worn(it);
        if (w == null) return;
        ItemMeta im = it.getItemMeta();
        im.getPersistentDataContainer().set(COND, PersistentDataType.DOUBLE, Math.max(0, Math.min(100, cond)));
        List<Component> lore = new ArrayList<>();
        String text = pieceText(w.set(), w.slot(), cond) + (w.fitName().equals(Character.STANDARD) ? "" : "\nmade for a " + w.fitName() + " body");
        for (String line : text.split("\n"))
            if (!line.isBlank()) lore.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        im.lore(lore);
        it.setItemMeta(im);
    }

    /** A piece as an item, with its condition and the body it was made for (taken from the base's store, 05c §7). */
    static ItemStack piece(Character.Worn w) {
        ItemStack it = make(armourId(w.set(), w.slot()), 1);
        if (FIT != null && !w.fitName().equals(Character.STANDARD)) {
            ItemMeta im = it.getItemMeta();
            im.getPersistentDataContainer().set(FIT, PersistentDataType.STRING, w.fitName());
            it.setItemMeta(im);
        }
        setCondition(it, w.condition());
        return it;
    }

    static void giveStack(Player p, ItemStack it) {
        for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItem(p.getLocation(), left);
    }

    /** A piece's text: its share of the set's AC, the set's DT/DR by type, its condition, weight, the set and what mends it. */
    static String pieceText(String set, String slot, double cond) {
        Data.Armour a = Data.ARMOUR.get(set);
        if (a == null) return "";
        StringBuilder sb = new StringBuilder("AC +" + a.pieceAc(slot) + " · condition " + Math.round(cond) + " % (protects "
                + Math.round(Character.conditionShare(cond) * 100) + " %)\n");
        List<String> t = new ArrayList<>();
        for (String type : List.of("Normal", "Heat", "Fire", "Isolation"))
            if (a.dt(type) != 0 || a.dr(type) != 0) t.add(type + " " + a.dt(type) + "/" + Math.round(a.dr(type)) + " %");
        sb.append(t.isEmpty() ? "no DT or DR" : "DT/DR " + String.join(", ", t)).append("\n");
        sb.append(switch (slot) { case "Head" -> "covers the head and the eyes"; case "Chest" -> "covers the torso, the arms and the hands";
            case "Legs" -> "covers the groin and the legs"; default -> "the feet"; }).append(" · ").append(a.pieceWeight(slot)).append(" kg\n");
        List<String> whole = new ArrayList<>();
        for (int i = 0; i < 7; i++) if (a.mods()[i] != 0) whole.add(Rules.SPECIAL[i] + " " + (a.mods()[i] > 0 ? "+" : "") + a.mods()[i]);
        if (a.radRes() != 0) whole.add("Rad res +" + Math.round(a.radRes()) + " %");
        if (a.poisonRes() != 0) whole.add("Poison res +" + Math.round(a.poisonRes()) + " %");
        a.skills().forEach((k, v) -> whole.add(k + " " + Math.round(v) + " %"));
        if (a.sealed()) whole.add("sealed: the acid fog's burns stay out" + (a.gasDr() > 0 ? ", a gas filter built in" : ""));
        if (a.powerArmour()) whole.add("runs only with Power Armour Training");
        if (a.cooled()) whole.add("cooled");
        if (a.pieces().size() > 1) sb.append("the set: ").append(String.join(", ", a.pieces()).toLowerCase(Locale.ROOT))
                .append(whole.isEmpty() ? "" : "; all worn: " + String.join(", ", whole)).append("\n");
        else if (!whole.isEmpty()) sb.append(String.join(", ", whole)).append("\n");
        sb.append("Tier ").append(Rules.roman(a.tier())).append(", rarity ").append(a.rarity()).append(" · ").append(a.weightKg()).append(" kg the set · ")
                .append(a.source()).append("\nmended with a spare or an armour repair kit (").append(a.repair()).append(")");
        return sb.toString();
    }

    /** The item's kind, or null. */
    static String id(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
    }

    /** What the player carries: id → count. */
    static Map<String, Integer> carried(Player p) {
        Map<String, Integer> m = new TreeMap<>();
        for (ItemStack it : p.getInventory().getContents()) {
            String id = id(it);
            if (id != null) m.merge(id, it.getAmount(), Integer::sum);
        }
        return m;
    }

    static boolean has(Player p, String id) { return carried(p).getOrDefault(id, 0) > 0; }

    /** Takes one; false if he has none. */
    static boolean take(Player p, String id) {
        ItemStack[] c = p.getInventory().getContents();
        for (int i = 0; i < c.length; i++)
            if (id.equals(id(c[i]))) {
                if (c[i].getAmount() > 1) c[i].setAmount(c[i].getAmount() - 1);
                else p.getInventory().setItem(i, null);
                return true;
            }
        return false;
    }

    static void give(Player p, String id, int n) {
        for (ItemStack left : p.getInventory().addItem(make(id, n)).values()) p.getWorld().dropItem(p.getLocation(), left);
    }

    /** Every player gets a Pip-Boy (once, in the last hotbar slot if it is free). */
    static void ensurePipBoy(Player p) {
        if (has(p, "pipboy")) return;
        if (p.getInventory().getItem(8) == null) p.getInventory().setItem(8, make("pipboy", 1));
        else give(p, "pipboy", 1);
    }
}
