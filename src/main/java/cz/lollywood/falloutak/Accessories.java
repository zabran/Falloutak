package cz.lollywood.falloutak;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * THE ACCESSORIES (05d §5; RULED round 1 as their own item type, Marek 11-25: *"accessories may be a new item type that can be used
 * together with weapon"*; the list and effects RULED round 3: *"The accessories and their effects - yes. The numbers are good."*): part
 * 7's designs of the group "Accessories", a category a slot (sight, muzzle, magazine, under-barrel), every slot Tier I-IV. One a slot on
 * a weapon, kept on the weapon's item; fitted with {@code /sheet fit} (Crafting {@value #FIT_CRAFTING} [mine]), taken off freely. The
 * effects are Fallout 2's weapon perks where it has one (Scope Range, Long Range, Accurate +20, Night Sight, the expanded magazine, Fast
 * Reload) and mine for the rest (the suppressor's quiet, the bipod's +10, the bayonet and the launcher as their own attack).
 */
final class Accessories {
    private Accessories() {}

    static NamespacedKey KEY;
    static final List<String> SLOTS = List.of("Sights", "Muzzle devices", "Magazines", "Under-barrel");
    static final int FIT_CRAFTING = 20;

    /** What it does: the range perk, to hit, night vision, quiet, a 1-AP reload, the magazine x, a bipod, its own attack, and where it fits. */
    record Effect(String perk, int toHit, boolean night, boolean quiet, boolean fastReload, int magazine, boolean bipod, String attack, String fits) {}

    static final Map<String, Effect> EFFECTS = new LinkedHashMap<>();
    static {
        e("Peep sight", "", 10, false, false, false, 1, false, "", "sight");                    // a drilled aperture [mine]
        e("Salvaged scope", "Long Range", 0, false, false, false, 1, false, "", "scope");       // a found scope remounted
        e("Telescopic sight", "Scope Range", 0, false, false, false, 1, false, "", "scope");    // F2: Perception x5, a penalty within 8
        e("Red-dot sight", "", 20, false, false, false, 1, false, "", "sight");                 // F2's Accurate +20
        e("Night sight", "", 0, true, false, false, 1, false, "", "sight");                     // F2's Night Sight
        e("Smart scope", "Scope Range", 10, false, false, false, 1, false, "", "scope");        // the 2040s' ballistic computer [mine]
        e("Oil-filter suppressor", "", 0, false, true, false, 1, false, "", "suppressor");
        e("Long barrel", "Long Range", 0, false, false, false, 1, false, "", "barrel");          // F2's Long Range: Perception x4
        e("Suppressor", "", 0, false, true, false, 1, false, "", "suppressor");
        e("Factory suppressor", "", 0, false, true, false, 1, false, "", "suppressor");
        e("Taped magazines", "", 0, false, false, true, 1, false, "", "magazine");             // two taped together: F2's Fast Reload
        e("Speed loader", "", 0, false, false, true, 1, false, "", "loader");                  // revolvers: Fast Reload
        e("Extended magazine", "", 0, false, false, false, 2, false, "", "magazine");          // F2's expanded magazine (x2, round 3)
        e("Drum magazine", "", 0, false, false, false, 3, false, "", "magazine");              // [mine]
        e("Spike bayonet", "", 0, false, false, false, 1, false, "Spike bayonet", "bayonet");   // a spear on the rifle (GURPS)
        e("Bipod", "", 0, false, false, false, 1, true, "", "bipod");                          // +10 on a burst, unmoved [mine]
        e("Bayonet", "", 0, false, false, false, 1, false, "Bayonet", "bayonet");
        e("Under-barrel launcher", "", 0, false, false, false, 1, false, "Under-barrel launcher", "launcher");
    }

    private static void e(String n, String perk, int hit, boolean night, boolean quiet, boolean fast, int mag, boolean bipod, String attack, String fits) {
        EFFECTS.put(n, new Effect(perk, hit, night, quiet, fast, mag, bipod, attack, fits));
    }

    /** An accessory's design (the group "Accessories"), or null. */
    static Production.Design design(String id) {
        Production.Design d = Items.made(id);
        return d != null && d.group.equals("Accessories") ? d : null;
    }

    /** Whether this accessory fits this weapon (05d §5's "fits") [the reading mine]. */
    static boolean fits(String acc, Battle.Weapon w, Production.Design gun) {
        Effect e = EFFECTS.get(acc);
        if (e == null || w == null || gun == null || w.melee() || w.thrown) return false;
        String cat = gun.category, n = w.name.toLowerCase(Locale.ROOT);
        boolean gunCat = Set.of("Pistols", "Rifles", "Shotguns", "Heavy guns", "Energy weapons").contains(cat), bow = cat.equals("Bows");
        boolean revolver = n.contains("revolver");
        return switch (e.fits()) {
            case "sight" -> gunCat || bow;
            case "scope" -> cat.equals("Rifles") || cat.equals("Heavy guns") || bow;
            case "suppressor" -> (cat.equals("Pistols") && !revolver) || cat.equals("Rifles");
            case "barrel" -> cat.equals("Pistols") || cat.equals("Rifles");
            case "magazine" -> gunCat && !cat.equals("Energy weapons") && !revolver && w.magazine >= 5;
            case "loader" -> revolver;
            case "bayonet" -> cat.equals("Rifles") || cat.equals("Shotguns");
            case "bipod" -> cat.equals("Rifles") || cat.equals("Heavy guns");
            case "launcher" -> cat.equals("Rifles") && w.modes.stream().anyMatch(m -> m.name().startsWith("Burst"));
            default -> false;
        };
    }

    /** The accessories on a weapon's item: slot -> name (none = empty). */
    static Map<String, String> fitted(ItemStack it) {
        Map<String, String> m = new LinkedHashMap<>();
        if (KEY == null || it == null || !it.hasItemMeta()) return m;
        String v = it.getItemMeta().getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
        if (v == null || v.isEmpty()) return m;
        for (String part : v.split(";")) {
            int i = part.indexOf('=');
            if (i > 0) m.put(part.substring(0, i), part.substring(i + 1));
        }
        return m;
    }

    /** The weapon's item with these accessories (kept in its data and shown in its description). */
    static void set(ItemStack it, Map<String, String> acc) {
        ItemMeta im = it.getItemMeta();
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : acc.entrySet()) b.append(b.length() > 0 ? ";" : "").append(e.getKey()).append('=').append(e.getValue());
        if (b.length() == 0) im.getPersistentDataContainer().remove(KEY);
        else im.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, b.toString());
        List<Component> lore = new ArrayList<>(im.lore() == null ? List.of() : im.lore());
        lore.removeIf(c -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c).startsWith("fitted: "));
        if (!acc.isEmpty()) lore.add(0, Component.text("fitted: " + String.join(", ", acc.values()), NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        im.lore(lore);
        it.setItemMeta(im);
    }

    // ------------------------------------------------------------------ the effects, for the battle
    static String perk(String own, Map<String, String> acc) {
        String best = own == null ? "" : own;
        for (String n : acc == null ? List.<String>of() : acc.values()) {
            Effect e = EFFECTS.get(n);
            if (e == null || e.perk().isEmpty()) continue;
            if (rank(e.perk()) > rank(best)) best = e.perk();
        }
        return best;
    }

    private static int rank(String perk) { return switch (perk) { case "Scope Range" -> 3; case "Long Range" -> 2; case "Accurate" -> 1; default -> 0; }; }

    /** To hit from the sights (and only those: a sight a slot, so they never add up). */
    static int toHit(Map<String, String> acc) { int t = 0; for (Effect e : effects(acc)) t += e.toHit(); return t; }

    static boolean night(Map<String, String> acc) { return effects(acc).stream().anyMatch(Effect::night); }

    static boolean quiet(Map<String, String> acc) { return effects(acc).stream().anyMatch(Effect::quiet); }

    static boolean fastReload(Map<String, String> acc) { return effects(acc).stream().anyMatch(Effect::fastReload); }

    static int magazine(Map<String, String> acc) { int m = 1; for (Effect e : effects(acc)) m = Math.max(m, e.magazine()); return m; }

    static boolean bipod(Map<String, String> acc) { return effects(acc).stream().anyMatch(Effect::bipod); }

    /** The accessories' own attacks (a bayonet, the launcher): their rows in data/weapons.json. */
    static List<String> attacks(Map<String, String> acc) {
        List<String> l = new ArrayList<>();
        for (Effect e : effects(acc)) if (!e.attack().isEmpty()) l.add(e.attack());
        return l;
    }

    private static List<Effect> effects(Map<String, String> acc) {
        List<Effect> l = new ArrayList<>();
        if (acc != null) for (String n : acc.values()) { Effect e = EFFECTS.get(n); if (e != null) l.add(e); }
        return l;
    }

    // ------------------------------------------------------------------ /sheet fit
    /** The weapon in his main hand as a design (a gun or a bow), or null. */
    static Production.Design held(Player p) {
        Production.Design d = Items.made(Items.id(p.getInventory().getItemInMainHand()));
        return d != null && Battle.GEAR.containsKey(d.name) && (d.group.equals("Ranged weapons")) ? d : null;
    }

    /** {@code /sheet fit}: what is on the gun in hand and what of his fits it, as clickable lines; {@code /sheet fit <slot>} fits the
     *  accessory in that inventory slot; {@code /sheet fit off <slot name>} takes one off. */
    static String command(Player p, Character c, String[] args) {
        Production.Design gun = held(p);
        if (gun == null) return "! hold the gun or bow in your main hand";
        Battle.Weapon w = Battle.GEAR.get(gun.name);
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (args.length >= 3 && args[1].equals("off")) {
            String slot = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
            Map<String, String> acc = fitted(hand);
            String name = acc.remove(slot);
            if (name == null) return "! nothing on its " + slot;
            apply(p, acc);
            give(p, back(name));
            return "Took the " + name + " off the " + gun.name + ".";
        }
        if (args.length >= 2) {
            int slot;
            try { slot = Integer.parseInt(args[1]); } catch (NumberFormatException ex) { return "! /sheet fit [inventory slot | off <slot>]"; }
            ItemStack it = p.getInventory().getItem(slot);
            Production.Design a = it == null ? null : design(Items.id(it));
            if (a == null) return "! no accessory in that slot";
            if (!fits(a.name, w, gun)) return "! the " + a.name + " does not fit the " + gun.name;
            if (c.skill("Crafting") < FIT_CRAFTING) return "! fitting needs Crafting " + FIT_CRAFTING + " (you have " + Math.round(c.skill("Crafting")) + ")";
            Map<String, String> acc = fitted(p.getInventory().getItemInMainHand());
            String old = acc.put(a.category, a.name);
            it.setAmount(it.getAmount() - 1);
            apply(p, acc);
            if (old != null) give(p, back(old));
            return "Fitted the " + a.name + " to the " + gun.name + (old != null ? " (the " + old + " came off)" : "") + ".";
        }
        Map<String, String> acc = fitted(hand);
        p.sendMessage(Component.text("The " + gun.name + ": " + (acc.isEmpty() ? "nothing fitted" : String.join(", ", acc.values())), NamedTextColor.GOLD));
        for (Map.Entry<String, String> e : acc.entrySet())
            p.sendMessage(Component.text("  [take off] " + e.getValue() + " (" + e.getKey() + ")", NamedTextColor.YELLOW)
                    .clickEvent(ClickEvent.runCommand("/sheet fit off " + e.getKey())));
        int n = 0;
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack it = p.getInventory().getItem(i);
            Production.Design a = it == null ? null : design(Items.id(it));
            if (a == null || !fits(a.name, w, gun)) continue;
            n++;
            p.sendMessage(Component.text("  [fit] " + a.name + " (" + a.category + ")" + (acc.containsKey(a.category) ? ", replacing the " + acc.get(a.category) : ""), NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.runCommand("/sheet fit " + i)));
        }
        return n == 0 ? "none of your accessories fits it" : "click one to fit it (Crafting " + FIT_CRAFTING + ")";
    }

    /** The accessories onto ONE weapon of the stack in the main hand; the others of the stack go back to the pack as they were. */
    private static void apply(Player p, Map<String, String> acc) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        int n = hand.getAmount();
        ItemStack one = hand.clone();
        one.setAmount(1);
        set(one, acc);
        ItemStack rest = hand.clone();
        p.getInventory().setItemInMainHand(one);
        if (n > 1) { rest.setAmount(n - 1); give(p, rest); }
    }

    private static ItemStack back(String name) {
        for (Production.Design d : Production.DESIGNS.values()) if (d.group.equals("Accessories") && d.name.equals(name)) return Items.made(d, 1, 100);
        return null;
    }

    private static void give(Player p, ItemStack it) {
        if (it == null) return;
        for (ItemStack rest : p.getInventory().addItem(it).values()) p.getWorld().dropItem(p.getLocation(), rest);
    }
}
