package cz.lollywood.falloutak;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.object.ObjectContents;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pictures in the sheet's hover texts (Marek 11-19, verbatim: *"the on hover explanation for skills, attributes, perks etc. could show
 * some simple picture to make it quickly understandable what the thing is about."*): Minecraft 1.21.9's object text component draws a
 * sprite of the game's own items atlas inside any text, so no resource pack is needed; the pack can add our own sprites to the same
 * atlas later (`assets/falloutak/textures/item/*.png`). The pictures are my picks [mine].
 */
final class Icons {
    private Icons() {}

    static final Key ITEMS = Key.key("minecraft", "items");

    /** S P E C I A L: a mace, a spyglass, a golden apple, an emerald, a book, a feather, a rabbit's foot. */
    static final String[] ATTR = {"mace", "spyglass", "golden_apple", "emerald", "book", "feather", "rabbit_foot"};

    static final Map<String, String> SKILL = Map.ofEntries(
            Map.entry("Athletics", "leather_boots"), Map.entry("Medicine", "glistering_melon_slice"),
            Map.entry("Computer Science", "comparator"), Map.entry("Sneak", "ink_sac"), Map.entry("Survival", "campfire"),
            Map.entry("Crafting", "iron_ingot"), Map.entry("Engineering", "repeater"), Map.entry("Chemistry", "brewing_stand"),
            Map.entry("Biology", "fermented_spider_eye"), Map.entry("Pilot", "elytra"), Map.entry("Economy", "gold_nugget"),
            Map.entry("Rhetorics", "writable_book"), Map.entry("Gambling", "paper"), Map.entry("Guns", "crossbow_standby"),
            Map.entry("Leadership", "goat_horn"));

    static String skill(String name) { return SKILL.getOrDefault(name, "book"); }

    /** The survival values: water, food, sleep, poison, rads, cold, heat, blood, pain, oxygen. */
    static String stat(Rules.Stat st) {
        return switch (st) {
            case WATER -> "water_bucket";
            case FOOD -> "bread";
            case SLEEP -> "clock_00";
            case POISON -> "spider_eye";
            case RADS -> "glowstone_dust";
            case HYPOTHERMIA -> "snowball";
            case HYPERTHERMIA -> "blaze_powder";
            case BLOOD -> "redstone";
            case PAIN -> "arrow";
            case OXYGEN -> "nautilus_shell";
        };
    }

    /**
     * The item's own picture (Marek 11-19: *"something resembling the item in inventory and hotbar"*): a custom-model-data string the
     * public resource pack switches on (`tools/build_pack.py` overrides the vanilla item's definition with a select on it, falling back
     * to the vanilla model), so a player without the pack still sees the vanilla item.
     */
    static void model(org.bukkit.inventory.meta.ItemMeta im, String slug) { model(im, slug, "none"); }

    static void model(org.bukkit.inventory.meta.ItemMeta im, String slug, String kind) {
        Battle.cosmetic(() -> {
            var cmd = im.getCustomModelDataComponent();
            // [0] the item itself, [1] its kind (a chem, an implant...): the pack draws the kind where it has no picture of the item
            cmd.setStrings(java.util.List.of("falloutak:" + slug, "falloutak:kind/" + kind));
            im.setCustomModelDataComponent(cmd);
        });
    }

    /** The picture's name for an item id: the battle's own ids by name, the rest from the id ("gear:Gas mask" → gas_mask). */
    static String slug(String id) {
        String s = switch (id) {
            case "smg" -> "pistol_10mm";
            case "stim", "gear:Stimpak" -> "stimpak";
            case "grenade" -> "grenade_frag";
            case "gasgren" -> "grenade_gas";
            case "end" -> "end_turn";
            case "kit" -> "weapon_repair_kit";
            default -> id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        };
        return s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }

    /** A sprite of the items atlas ({@code item/<name>}). */
    static Component item(String name) { return Component.object(ObjectContents.sprite(ITEMS, Key.key("minecraft", "item/" + name))); }

    /** A tooltip with the picture on its first line, then the details and the workbook's note ({@link Txt#tip}). */
    static Component tip(String icon, String how, String note) { return tip(icon, null, how, note); }

    /**
     * The same with a Pip-Boy picture ({@code card}, data/cards.json) for a viewer who has the pack, else the small sprite
     * ({@code icon}). The picture is a glyph of the pack's font falloutak:cards, drawn HEIGHT px tall from its line's top, so
     * the lines under it are left empty (tools/cards.py).
     */
    static Component tip(String icon, String card, String how, String note) {
        Component t = Txt.tip(how, note);
        Integer cp = card == null || !VIEWER_HAS_PACK.get() ? null : CARDS.get(card);
        Component pic = cp == null ? item(icon) : Component.text(new String(java.lang.Character.toChars(cp)))
                .font(CARD_FONT).color(net.kyori.adventure.text.format.NamedTextColor.WHITE)
                .append(Component.text("\n".repeat(Math.max(0, CARD_NEWLINES - 1))));
        return t == null ? pic : pic.append(Component.newline()).append(t);
    }

    // ------------------------------------------------------------------ the Pip-Boy pictures (Marek 11-19)
    static final Key CARD_FONT = Key.key("falloutak", "cards");
    static final Map<String, Integer> CARDS = new java.util.LinkedHashMap<>();
    static int CARD_NEWLINES = 6;
    static {
        try (var in = Icons.class.getResourceAsStream("/data/cards.json")) {
            if (in != null) {
                var root = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                CARD_NEWLINES = root.get("newlines").getAsInt();
                root.getAsJsonObject("chars").entrySet().forEach(e -> CARDS.put(e.getKey(), e.getValue().getAsInt()));
            }
        } catch (Exception ex) {
            throw new IllegalStateException("data/cards.json: " + ex.getMessage(), ex);
        }
    }

    /** Players whose client loaded the public pack from the server (the pack status event). */
    static final Set<UUID> packLoaded = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Set while one player's sheet is built (SheetView.open): does HE see the pack's pictures? */
    static final ThreadLocal<Boolean> VIEWER_HAS_PACK = ThreadLocal.withInitial(() -> false);

    /** His own choice (/pack on|off), else whether the server's pack loaded in his client. */
    static boolean hasPack(org.bukkit.entity.Player p, Character c) {
        return c.pictures != null ? c.pictures : packLoaded.contains(p.getUniqueId());
    }

    /** "Computer Science" -> "computer_science". */
    static String key(String name) { return name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_"); }
}
