package cz.lollywood.falloutak;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /sheet: a 6-row chest screen; the value in each item's NAME, its breakdown in the tooltip; the tabs on the bottom row. */
public final class SheetGui implements InventoryHolder {
    public enum Page { OVERVIEW, SKILLS, WEAPONS, TRAITS, BODY, SURVIVAL, COMBAT, ACHIEVEMENTS }

    public static final int SPEND_SLOT = 31;
    final Character c;
    final Page page;
    private final Inventory inv;

    private SheetGui(Character c, Page page) {
        this.c = c;
        this.page = page;
        this.inv = Bukkit.createInventory(this, 54, Component.text(c.name + " · " + title(page)));
        fill();
    }

    public static void open(Player p, Character c, Page page) {
        p.openInventory(new SheetGui(c, page).inv);
    }

    @Override public Inventory getInventory() { return inv; }

    private static String title(Page p) {
        String s = p.name().toLowerCase(Locale.ROOT);
        return switch (p) {
            case WEAPONS -> "Weapon skills";
            case TRAITS -> "Traits & perks";
            default -> s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
        };
    }

    // ------------------------------------------------------------------ items
    static Component t(String s, TextColor col) { return Component.text(s, col).decoration(TextDecoration.ITALIC, false); }
    static Component t(String s) { return t(s, NamedTextColor.GRAY); }

    static ItemStack item(Material m, int amount, Component name, List<Component> lore, boolean glow) {
        ItemStack it = new ItemStack(m, Math.max(1, Math.min(99, amount)));
        ItemMeta meta = it.getItemMeta();
        if (amount > 64) meta.setMaxStackSize(99);
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        if (glow) meta.setEnchantmentGlintOverride(true);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    static ItemStack item(Material m, String name, String... lore) {
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(t(s));
        return item(m, 1, t(name, NamedTextColor.WHITE), l, false);
    }

    private void set(int slot, ItemStack it) { inv.setItem(slot, it); }

    static String f(double v) { return v == Math.floor(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v); }

    // ------------------------------------------------------------------ pages
    private void fill() {
        switch (page) {
            case OVERVIEW -> overview();
            case SKILLS -> skills();
            case WEAPONS -> weapons();
            case TRAITS -> traits();
            case BODY -> body();
            case SURVIVAL -> survival();
            case COMBAT -> combat();
            case ACHIEVEMENTS -> achievements();
        }
        Material[] icons = {Material.PLAYER_HEAD, Material.BOOK, Material.CROSSBOW, Material.NETHER_STAR, Material.TOTEM_OF_UNDYING,
                Material.COOKED_BEEF, Material.IRON_CHESTPLATE, Material.GOLDEN_APPLE};
        for (Page p : Page.values()) {
            ItemStack it = item(icons[p.ordinal()], 1, t(title(p), p == page ? NamedTextColor.YELLOW : NamedTextColor.WHITE),
                    List.of(t(p == page ? "this page" : "click to open")), p == page);
            set(45 + p.ordinal(), it);
        }
        set(53, item(Material.BARRIER, "Close"));
    }

    private void overview() {
        int[] now = c.attrs(false);
        double[] sk = c.skills();
        ItemStack head = item(Material.PLAYER_HEAD, 1, t(c.name, NamedTextColor.GOLD), List.of(
                t("player " + c.player), t("template " + c.template), t("level " + c.level),
                t("XP " + c.xp + " / " + Rules.xpForLevel(c.level + 1) + " to level " + (c.level + 1)),
                t(c.templatePerkRate <= 0 ? "no perks (the template)" : "a perk every " + c.templatePerkRate + " levels: next at level " + (c.level + c.templatePerkRate - c.level % c.templatePerkRate)),
                t("skill points to spend: " + c.unspentSkillPoints, NamedTextColor.YELLOW)), false);
        if (head.getItemMeta() instanceof SkullMeta sm && !c.player.isEmpty()) {
            sm.setOwningPlayer(Bukkit.getOfflinePlayer(c.player));
            head.setItemMeta(sm);
        }
        set(0, head);
        int floor = Rules.deathFloor(now);
        set(1, item(Material.RED_DYE, 1, t("HP " + c.hp + " / " + c.hpMax(), c.hp > 0 ? NamedTextColor.RED : NamedTextColor.DARK_RED), List.of(
                t("S " + now[Rules.S] + " + 2 x E " + now[Rules.E] + " + template " + c.hpBonus() + (c.hpMax() > Rules.hpMax(now, c.hpBonus()) ? " + Medical Marvel 5" : "")),
                t("dying from 0 down to " + floor + " (−Endurance), dead below", NamedTextColor.DARK_GRAY),
                t(c.hp > 0 ? "" : c.hp >= floor ? "DYING: what you can still do = Pain and Blood" : "DEAD", NamedTextColor.DARK_RED)), false));
        set(2, item(Material.SUGAR, 1, t("AP " + c.apMax(), NamedTextColor.AQUA), List.of(
                t("5 + A " + now[Rules.A] + " / 2 = " + Rules.ap(now) + (c.apMax() > Rules.ap(now) ? ", + 2 Brittle Bones" : ""))), false));
        set(3, item(Material.GLISTERING_MELON_SLICE, 1, t("Healing rate " + Rules.healingRate(now, sk[Rules.skillIndex("Medicine")], sk[Rules.skillIndex("Survival")])), List.of(
                t("E / 3 + Medicine / 50 + Survival / 20, at least 1 (ruled 11-13)")), false));
        set(4, item(Material.CHEST, 1, t("Carry 0 / " + f(Rules.carry(now, sk[0])) + " kg"), List.of(
                t("25 + 25 x S + Athletics"), t("the weight carried = Minecraft's own inventory (items carry tier, rarity, weight)"),
                t("EXAMPLE: nothing carried yet", NamedTextColor.DARK_GRAY)), false));
        List<Component> cond = new ArrayList<>();
        for (Rules.Stat st : Rules.Stat.values()) {
            String sg = Rules.stage(st, c.survival.get(st), c.max(st));
            if (sg != null) cond.add(t(sg, color(Rules.level(sg))));
        }
        for (Character.Effect e : c.effects) if (!e.permanent()) cond.add(t(e.name() + ", " + SheetView.hours(e.hoursLeft()) + " left", NamedTextColor.LIGHT_PURPLE));
        if (cond.isEmpty()) cond.add(t("none"));
        set(8, item(Material.POTION, 1, t("Conditions (" + (cond.size()) + ")", NamedTextColor.LIGHT_PURPLE), cond, false));
        Material[] sp = {Material.IRON_INGOT, Material.SPYGLASS, Material.APPLE, Material.EMERALD, Material.BOOK, Material.FEATHER, Material.RABBIT_FOOT};
        int sum = 0;
        for (int i = 0; i < 7; i++) {
            sum += c.base[i];
            set(9 + i, item(sp[i], Math.max(1, now[i]), t(Rules.SPECIAL[i] + " " + now[i], now[i] < c.attr(i, true) ? NamedTextColor.RED : NamedTextColor.WHITE), breakdown(i), false));
        }
        set(16, item(Material.PAPER, "Attribute sum " + sum + " / " + c.tpl().attributePoints(), "the base values: 40 + the template's modifier"));
        set(17, item(Material.EXPERIENCE_BOTTLE, 1, t("Skill points " + c.unspentSkillPoints, NamedTextColor.YELLOW), List.of(
                t("2 x I + template " + c.templateSkillPoints + (c.other("skill_points_level", true) > 0 ? " + the Neural Link's memory " + Math.round(c.other("skill_points_level", true)) : "") + " = " + c.skillRate() + " a level"),
                t("spend them on the Skills page")), false));
        set(18, item(Material.SHIELD, Rules.ac(now), t("AC " + Rules.ac(now)), List.of(t("= Agility (+ armour)")), false));
        set(19, item(Material.CLOCK, Rules.initiative(now), t("Initiative " + Rules.initiative(now)), List.of(t("2 x Perception")), false));
        set(20, item(Material.IRON_SWORD, 1, t("Damage bonus 0"), List.of(t("template + effects")), false));
        set(21, item(Material.STONE_AXE, Rules.meleeBonus(now), t("Melee bonus " + Rules.meleeBonus(now)), List.of(t("S − 5, at least 1")), false));
        set(22, item(Material.TARGET, 1, t("Crit chance " + f(Rules.critChance(now, sk[3])) + " %"), List.of(t("Luck % + Sneak / 10 %")), false));
        set(23, item(Material.LECTERN, 1, t("Skill rate " + c.skillRate()), List.of(t("skill points a level")), false));
        set(24, item(Material.NETHER_STAR, c.templatePerkRate, t("Perk rate " + c.templatePerkRate), List.of(t("a perk every " + c.templatePerkRate + " levels (the template)")), false));
        set(25, item(Material.SKELETON_SKULL, 1, t("Death floor " + floor), List.of(t("−Endurance: dying from 0 to here (ruled 11-14)")), false));
        set(26, item(Material.GLOWSTONE_DUST, 1, t("Radiation " + f(c.survival.get(Rules.Stat.RADS)) + " rads"), List.of(t("here: " + f(c.locationRads) + " rads an hour")), false));
        for (int i = 0; i < 2; i++) {
            if (i < c.traits.size()) {
                Character.Feat tr = c.traits.get(i);
                set(27 + i, item(Material.NAME_TAG, 1, t(tr.name(), NamedTextColor.GOLD), List.of(t(tr.text()), t(tr.gate(), NamedTextColor.DARK_GRAY)), false));
            } else set(27 + i, item(Material.GRAY_STAINED_GLASS_PANE, "no trait", "0, 1 or 2 traits (ruled)"));
        }
        int k = 30;
        for (String tag : c.tags) set(k++, item(Material.ENCHANTED_BOOK, 1, t("Tag: " + tag, NamedTextColor.GREEN), List.of(t("grows twice as fast")), false));
        set(34, item(Material.WHITE_BANNER, "Group: none yet", "part 6: one group per player, one stat block"));
        set(35, item(Material.GOLD_NUGGET, "CoS points: after the quiz", "the Curse of Strahd quiz's points, spent in the point shop"));
        int s = 36;
        for (Rules.Stat st : Rules.Stat.values()) set(s++, survivalPane(st));
    }

    private List<Component> breakdown(int i) {
        List<Component> l = new ArrayList<>();
        l.add(t("base " + c.base[i]));
        if (c.tpl().mods()[i] != 0) l.add(t((c.tpl().mods()[i] > 0 ? "+" : "") + c.tpl().mods()[i] + " template " + c.template));
        int perm = c.base[i];
        for (Character.Effect e : c.effects)
            if (e.permanent() && e.mods()[i] != 0) { perm += e.mods()[i]; l.add(t((e.mods()[i] > 0 ? "+" : "") + e.mods()[i] + " " + e.name())); }
        l.add(t("= " + c.attr(i, true) + " permanent (level-up reads this)", NamedTextColor.WHITE));
        boolean temp = false;
        for (Character.Effect e : c.effects)
            if (!e.permanent() && e.mods()[i] != 0) { temp = true; l.add(t((e.mods()[i] > 0 ? "+" : "") + e.mods()[i] + " " + e.name() + ", " + f(e.hoursLeft()) + " h", NamedTextColor.LIGHT_PURPLE)); }
        for (String st : c.stages()) {
            Data.EffectRow r = Data.EFFECTS.get(st);
            if (r != null && r.mods()[i] != 0) { temp = true; l.add(t((r.mods()[i] > 0 ? "+" : "") + r.mods()[i] + " " + st, NamedTextColor.RED)); }
        }
        if (temp) l.add(t("= " + c.attr(i, false) + " now", NamedTextColor.WHITE));
        return l;
    }

    static NamedTextColor color(int level) {
        return switch (level) { case 0 -> NamedTextColor.GREEN; case 1 -> NamedTextColor.YELLOW; case 2 -> NamedTextColor.GOLD; case 3 -> NamedTextColor.RED; default -> NamedTextColor.DARK_RED; };
    }

    private ItemStack survivalPane(Rules.Stat st) {
        double v = c.survival.get(st), m = c.max(st);
        String sg = Rules.stage(st, v, m);
        int lv = Rules.level(sg);
        Material[] panes = {Material.LIME_STAINED_GLASS_PANE, Material.YELLOW_STAINED_GLASS_PANE, Material.ORANGE_STAINED_GLASS_PANE,
                Material.RED_STAINED_GLASS_PANE, Material.BLACK_STAINED_GLASS_PANE};
        double h = Rules.hourly(st, c.survival.get(Rules.Stat.HYPERTHERMIA), c.sleeping, c.locationRads);
        return item(panes[lv], 1, t(name(st) + " " + f(Math.round(v)) + " / " + f(Math.round(m)), color(lv)), List.of(
                t(sg == null ? "fine" : sg, color(lv)), t(sg == null ? "" : Data.effectText(sg), NamedTextColor.RED),
                t((h >= 0 ? "+" : "") + f(Math.round(h * 10) / 10.0) + " an hour")), false);
    }

    static String name(Rules.Stat st) {
        String s = st.name().toLowerCase(Locale.ROOT);
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    private void skills() {
        double[] now = c.skills(), perm = c.skills(true);
        int[] a = c.attrs(false);
        Material[] icons = {Material.FEATHER, Material.GOLDEN_APPLE, Material.COMPARATOR, Material.LEATHER_BOOTS, Material.CAMPFIRE,
                Material.CRAFTING_TABLE, Material.PISTON, Material.BREWING_STAND, Material.OAK_SAPLING, Material.ELYTRA, Material.EMERALD,
                Material.WRITABLE_BOOK, Material.RABBIT_FOOT, Material.CROSSBOW, Material.GOLDEN_HELMET};
        for (int i = 0; i < Rules.SKILLS.size(); i++) {
            Rules.Skill s = Rules.SKILLS.get(i);
            boolean tag = c.tags.contains(s.name());
            List<Component> l = new ArrayList<>();
            l.add(t("base " + s.formula() + " = " + f(s.base().applyAsDouble(a))));
            l.add(t("points spent " + f(c.points[i])));
            l.add(t("= " + f(perm[i]) + " % permanent" + (perm[i] != now[i] ? ", " + f(now[i]) + " % now" : ""), NamedTextColor.WHITE));
            l.add(t("the next %: " + Rules.pricePerPercent(perm[i]) + " point(s)" + (tag ? ", tagged: grows twice as fast" : ""), NamedTextColor.YELLOW));
            l.add(t("1 / 2 / 4 points a % to 100, to 200, above (ruled)", NamedTextColor.DARK_GRAY));
            set(i < 9 ? i : i + 0, item(icons[i], 1, t(s.name() + " " + f(now[i]) + " %", tag ? NamedTextColor.GREEN : NamedTextColor.WHITE), l, tag));
        }
        set(SPEND_SLOT, item(Material.EXPERIENCE_BOTTLE, 1, t("Spend " + c.unspentSkillPoints + " skill points", NamedTextColor.YELLOW),
                List.of(t("click: the level-up form (a dialog with a slider a skill)")), true));
    }

    private void weapons() {
        double[] v = c.skills();
        Material[] icons = {Material.END_ROD, Material.BOW, Material.SNOWBALL, Material.IRON_NUGGET, Material.TRIPWIRE_HOOK, Material.TNT,
                Material.SHEARS, Material.MACE, Material.TRIDENT, Material.IRON_AXE, Material.IRON_SWORD, Material.LIGHTNING_ROD,
                Material.FIRE_CHARGE, Material.FIREWORK_ROCKET};
        for (int i = 0; i < Rules.COMBOS.size(); i++) {
            Rules.Combo co = Rules.COMBOS.get(i);
            set(i < 7 ? i : i + 2, item(icons[i], 1, t(co.name() + " " + f(Math.round(co.value().applyAsDouble(v) * 10) / 10.0) + " %", NamedTextColor.WHITE),
                    List.of(t(co.formula()), t("from the skills as they are now", NamedTextColor.DARK_GRAY)), false));
        }
    }

    private void traits() {
        for (int i = 0; i < c.traits.size(); i++) {
            Character.Feat tr = c.traits.get(i);
            set(i, item(Material.NAME_TAG, 1, t(tr.name(), NamedTextColor.GOLD), List.of(t(tr.text()), t(tr.gate(), NamedTextColor.DARK_GRAY)), true));
        }
        set(8, item(Material.PAPER, "Perks: " + c.perks.size() + " owned", "a perk every " + c.templatePerkRate + " levels; never granted by a skill level",
                "glowing = owned · white = you meet the gate · grey = locked"));
        int s = 9;
        for (Character.Feat p : Character.SAMPLE_PERKS) {
            boolean owned = c.perks.stream().anyMatch(x -> x.name().equals(p.name()));
            boolean ok = c.meets(p);
            set(s++, item(owned ? Material.NETHER_STAR : ok ? Material.PAPER : Material.GRAY_DYE, 1,
                    t(p.name(), owned ? NamedTextColor.GOLD : ok ? NamedTextColor.WHITE : NamedTextColor.DARK_GRAY),
                    List.of(t(p.text()), t("gate: " + p.gate(), ok ? NamedTextColor.GREEN : NamedTextColor.RED),
                            t(ok ? "at your next perk level: click, then confirm" : "locked", NamedTextColor.DARK_GRAY)), owned));
        }
    }

    private void body() {
        set(0, item(Material.ARMOR_STAND, 1, t("Template: " + c.template, NamedTextColor.GOLD), List.of(
                t("HP +" + c.hpBonus() + " · skill points +" + c.templateSkillPoints + " a level · a perk every " + c.templatePerkRate + " levels"),
                t("fixed for life; its racial perks come in part 3", NamedTextColor.DARK_GRAY)), false));
        int s = 9;
        for (Character.Effect e : c.effects) {
            List<Component> l = new ArrayList<>();
            for (int i = 0; i < 7; i++) if (e.mods()[i] != 0) l.add(t((e.mods()[i] > 0 ? "+" : "") + e.mods()[i] + " " + Rules.SPECIAL[i]));
            l.add(t(e.permanent() ? "permanent (counts at level-up)" : "temporary: " + f(e.hoursLeft()) + " h left", e.permanent() ? NamedTextColor.WHITE : NamedTextColor.LIGHT_PURPLE));
            set(s++, item(e.permanent() ? Material.REDSTONE : Material.HONEY_BOTTLE, 1, t(e.name(), NamedTextColor.WHITE), l, false));
        }
        set(26, item(Material.BARRIER, "Gene therapies: PARKED", "Marek 11-13: the gene details wait; this page shows what exists"));
    }

    private void survival() {
        int s = 0;
        for (Rules.Stat st : Rules.Stat.values()) set(s++, survivalPane(st));
        set(18, item(c.sleeping ? Material.RED_BED : Material.WHITE_BED, "Sleeping: " + (c.sleeping ? "yes" : "no"), "sleep +4 an hour asleep, −1 awake"));
        set(19, item(Material.GLOWSTONE_DUST, "Here: " + f(c.locationRads) + " rads an hour", "the place's radiation"));
        set(20, item(Material.REDSTONE, "Bleeding: " + f(c.bleeding) + " Blood a round", "drains Blood each round; +1/6 an hour regrows", "/sheet firstaid stops it"));
        set(22, item(Material.CLOCK, "/sheet demo", "runs a demo clock: one second = one hour", "/sheet hit <damage>: a wound (EXAMPLE pain and bleeding)"));
    }

    private void combat() {
        int[] a = c.attrs(false);
        double[] sk = c.skills();
        set(0, item(Material.SHIELD, Rules.ac(a), t("AC " + Rules.ac(a)), List.of(t("Agility + armour")), false));
        set(1, item(Material.CLOCK, Rules.initiative(a), t("Initiative " + Rules.initiative(a)), List.of(t("2 x Perception")), false));
        set(2, item(Material.IRON_SWORD, 1, t("Damage bonus 0"), List.of(t("template + effects")), false));
        set(3, item(Material.STONE_AXE, Rules.meleeBonus(a), t("Melee bonus " + Rules.meleeBonus(a)), List.of(t("S − 5, at least 1")), false));
        set(4, item(Material.TARGET, 1, t("Crit chance " + f(Rules.critChance(a, sk[3])) + " %"), List.of(t("Luck % + Sneak / 10 %")), false));
        String[] types = Character.DAMAGE_TYPES;   // Isolation = the old Cold/Lightning (Marek 11-16)
        double[] dr = c.resistances();
        String[] how = {"Athletics / 10 %", "2 x E % + Survival / 10 %", "5 x E % + Survival / 4 %", "armour", "armour", "armour"};
        Material[] m = {Material.IRON_INGOT, Material.GLOWSTONE_DUST, Material.SPIDER_EYE, Material.BLAZE_POWDER, Material.REDSTONE_TORCH, Material.PACKED_ICE};
        for (int i = 0; i < types.length; i++) {
            set(9 + i, item(m[i], 1, t(types[i] + " DR " + f(Math.round(dr[i] * 10) / 10.0) + " %"), List.of(t(how[i]), t("the % = DR", NamedTextColor.DARK_GRAY)), false));
            set(18 + i, item(m[i], 1, t(types[i] + " DT " + (i == 0 ? c.dt() : 0)), List.of(t("flat, from armour and the template"), t("the flat = DT", NamedTextColor.DARK_GRAY)), false));
        }
    }

    private void achievements() {
        set(0, item(Material.GOLDEN_APPLE, "Achievements across games",
                "the player's own, from the wiki (production/lide) and _notes/canon/achievements.yaml",
                "automatic ones: applied by the plugin", "active ones: a Use button here (Marek 11-12)",
                "EXAMPLE page: filled when the plugin's data is built"));
    }
}
