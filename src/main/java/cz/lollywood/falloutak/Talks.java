package cz.lollywood.falloutak;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import static cz.lollywood.falloutak.Menus.btn;
import static cz.lollywood.falloutak.Menus.line;
import static cz.lollywood.falloutak.Menus.say;
import static cz.lollywood.falloutak.Menus.tellGm;

/**
 * Part 9d round 3 (Marek 11-23): *"for groups like raiders and others, where you can negotiate, there should be third option to speak with
 * them, which gives bonus to negotiation (since we have seen them first)."* · *"talks or trades as menus - please continue with that."*
 * The talks and the trade of a world-map scene [every form and number here mine, EXAMPLE; trade and money are their own part, RULED
 * in part 7 §10, so this barter is a stand-in until that part is ruled]:
 * <ul>
 *   <li><b>A hostile group seen first can be talked to</b>: the speaker's Rhetorics + 20 (they were seen first) − 20 (they came to
 *   fight); Negotiator retries a failed check once (02c). Talked down = resolved without killing = its full kill XP (06b §3); a failed
 *   talk = the battle map, they go for their guns first, the party keeps its cover.</li>
 *   <li><b>A raider group or a band that comes to talk wants a TOLL</b>: pay it in goods (10 a person in the party, at what they would
 *   pay for them), talk your way out (Rhetorics − 10; the check's XP, at least 10), or refuse (they turn hostile).</li>
 *   <li><b>A patrol brings NEWS</b>: the nearest raider base within 15 km of the party, its group, the way and the distance.</li>
 *   <li><b>Traders BARTER</b>: their wares (tier I-II, drawn for the scene), an item worth 10 x 3^(tier − 1) x rarity; they sell at
 *   x (1.5 − Economy / 200, at least 1) and buy at x (0.5 + Economy / 400, at most 1) by the clicking player's Economy (Fallout 2's
 *   barter in spirit); what you sell is credit for what you buy, and they give no change.</li>
 * </ul>
 */
final class Talks {
    private Talks() {}

    static final int SEEN_FIRST = 20, HOSTILE = -20, TOLL_TALK = -10, TOLL_A_HEAD = 10, NEWS_KM = 15;

    /** Who can talk: the people (raiders, bands, patrols, traders), not the creatures. */
    static boolean talks(WorldMap.Kind k) { return !k.key().startsWith("creature:"); }

    static boolean raiderKind(WorldMap.Kind k) { return k.key().startsWith("raiders:") || k.key().equals("bands"); }

    static int clamp(int c) { return Math.max(5, Math.min(95, c)); }

    /** The talks' dice (a test may fix them). */
    static Random dice;

    static Random rnd(FalloutakPlugin pl) { return dice != null ? dice : pl.rnd; }

    /** The speaker's chance and whether he made it (Negotiator: a second try with a new argument). */
    record Check(int chance, int roll, int roll2, boolean ok) {
        String text() { return "Rhetorics " + chance + " %, rolled " + roll + (roll2 > 0 ? ", Negotiator's second try " + roll2 : "") + (ok ? ": success" : ": failure"); }
    }

    static Check check(Character c, int bonus, Random rnd) {
        int chance = clamp((int) Math.round(c.skill("Rhetorics")) + bonus);
        int r1 = rnd.nextInt(100) + 1, r2 = 0;
        boolean ok = r1 <= chance;
        if (!ok && c.hasPerk("Negotiator")) { r2 = rnd.nextInt(100) + 1; ok = r2 <= chance; }
        return new Check(chance, r1, r2, ok);
    }

    // ------------------------------------------------------------------ a hostile group seen first
    /** Talking to a hostile group the party saw first: talked down (the kill XP) or the fight. */
    static String parley(FalloutakPlugin pl, Player p, Travel.Met m) {
        if (!m.hostile() || !m.seen || !talks(m.e.kind())) return "! you cannot talk to them";
        Check k = check(pl.character(p), SEEN_FIRST + HOSTILE, rnd(pl));
        if (k.ok()) {
            List<Double> hps = new ArrayList<>();
            for (int i = 0; i < m.e.count(); i++) hps.add(m.e.kind().hp());
            String r = pl.roster.killAward(Travel.party(p), hps, "talked down on the battle map");
            Travel.scene.remove(m);
            tellGm("travel: " + p.getName() + " talked " + m.e.kind().name() + " down (" + k.text() + ")");
            return "You step out first and talk (" + k.text() + "): they lower their guns and let you pass. Resolved without killing: their full kill XP. " + r;
        }
        tellGm("travel: the talk with " + m.e.kind().name() + " failed (" + k.text() + ")");
        String r = Travel.battleMap(pl, p, Arena.Mode.PARLEY);
        return "You step out and talk (" + k.text() + "): it goes wrong. " + r;
    }

    // ------------------------------------------------------------------ a peaceful talk
    /** The toll paid so far in this scene (by value). */
    static int paid;

    static int toll(Player p) { return TOLL_A_HEAD * Travel.party(p).size(); }

    /** A peaceful talk's menu: a toll (raiders, bands) or news (patrols); traders go to the barter. */
    static void talkMenu(FalloutakPlugin pl, Player p, Travel.Met m) {
        if (m.e.type().equals("trade")) { tradeMenu(pl, p, m); return; }
        List<Component> lines = new ArrayList<>();
        List<Ui.Btn> b = new ArrayList<>();
        lines.add(line(m.e.text(), NamedTextColor.GOLD));
        if (raiderKind(m.e.kind())) {
            int toll = toll(p);
            lines.add(line("They block the way and want a toll: goods worth " + toll + " (" + TOLL_A_HEAD + " a head), at what they would pay. Paid so far: " + paid + ".", NamedTextColor.YELLOW));
            for (Map.Entry<String, int[]> e : sellable(p).entrySet()) {
                int v = sellPrice(e.getValue()[0], e.getValue()[1], pl.character(p));
                b.add(btn("Give: " + name(e.getKey()) + " (" + v + ")", "one of " + e.getValue()[2] + " you carry, toward the toll", x -> {
                    if (take(x, e.getKey())) paid += v;
                    if (paid >= toll(x)) { say(x, "The toll is paid (" + paid + "): they step aside."); tellGm("travel: " + x.getName() + " paid the toll to " + m.e.kind().name()); Travel.scene.remove(m); paid = 0; Travel.menu(pl, x); }
                    else talkMenu(pl, x, m);
                }));
            }
            b.add(btn("Talk your way out", "Rhetorics " + Battle.sign(TOLL_TALK) + "; failed, they turn hostile", x -> {
                Check k = check(pl.character(x), TOLL_TALK, rnd(pl));
                if (k.ok()) {
                    String r = pl.roster.award(x, Math.max(10, -TOLL_TALK), "a toll talked away (the check)");
                    Travel.scene.remove(m); paid = 0;
                    say(x, "No toll (" + k.text() + "): they let you through. " + r);
                    Travel.menu(pl, x);
                } else { say(x, "They will not have it (" + k.text() + ")."); say(x, turnHostile(pl, x, m)); }
            }));
            b.add(btn("Refuse and walk on", "they turn hostile", x -> say(x, turnHostile(pl, x, m))));
        } else {
            lines.add(line("They stop to talk: news of the roads.", NamedTextColor.YELLOW));
            b.add(btn("Hear their news", "what they know of the raiders near here", x -> { say(x, news()); Travel.scene.remove(m); Travel.menu(pl, x); }));
            b.add(btn("Part ways", "nothing more", x -> { Travel.scene.remove(m); Travel.menu(pl, x); }));
        }
        Menus.menu(p, "Talk: " + m.e.kind().name(), lines, b, x -> Travel.menu(pl, x));
    }

    /** A peaceful meeting gone hostile: it fights now, on the battle map, they first. */
    static String turnHostile(FalloutakPlugin pl, Player p, Travel.Met m) {
        int k = Travel.scene.indexOf(m);
        if (k < 0) return "! they are gone";
        Travel.scene.set(k, new Travel.Met(new WorldMap.Encounter(m.e.kind(), "fight", m.e.count()), true));
        paid = 0;
        tellGm("travel: " + m.e.kind().name() + " turned hostile");
        return Travel.battleMap(pl, p, Arena.Mode.PARLEY);
    }

    /** The nearest raider base within {@link #NEWS_KM} km of the party (the hidden camps too: that is what news is worth). */
    static String news() {
        WorldMap m = Travel.map;
        if (m == null) return "They know nothing.";
        WorldMap.Src best = null;
        double bd = Double.MAX_VALUE;
        for (WorldMap.Src b : m.bases) {
            if (b.cleared || !b.type.equals("raiders")) continue;
            double d = m.km(Travel.pi, Travel.pj, b.i + 0.5, b.j + 0.5);
            if (d < bd) { bd = d; best = b; }
        }
        if (best == null || bd > NEWS_KM) return "\"The roads round here have been quiet.\"";
        double di = best.i + 0.5 - Travel.pi, dj = best.j + 0.5 - Travel.pj;   // south and east
        String way = compass(-di * m.ey, dj * m.ex);
        WorldMap.Kind k = m.kinds.get("raiders:" + best.group);
        String who = best.kind.equals("band") ? "a small band" : k != null ? k.name() : "raiders";
        tellGm("travel: the news told the party of " + best.key + " (" + best.kind + ", " + Math.round(best.fighters) + ")");
        return String.format(Locale.ROOT, "\"Watch out: %s, about %.0f km %s of here%s.\"", who, bd, way, best.hidden ? ", hidden in a camp" : "");
    }

    static String compass(double north, double east) {
        String[] dirs = {"east", "north-east", "north", "north-west", "west", "south-west", "south", "south-east"};
        double a = Math.toDegrees(Math.atan2(north, east));
        return dirs[(int) Math.floorMod(Math.round(a / 45.0), 8)];
    }

    // ------------------------------------------------------------------ the barter
    /** An item's worth [EXAMPLE]: 10 x 3^(tier − 1) x rarity. */
    static int value(int tier, int rarity) { return (int) Math.round(10 * Math.pow(3, Math.max(0, tier - 1)) * Math.max(1, rarity)); }

    static int buyPrice(int tier, int rarity, Character c) {
        return (int) Math.ceil(value(tier, rarity) * Math.max(1.0, 1.5 - c.skill("Economy") / 200.0));
    }

    static int sellPrice(int tier, int rarity, Character c) {
        return (int) Math.floor(value(tier, rarity) * Math.min(1.0, 0.5 + c.skill("Economy") / 400.0));
    }

    /** An item id's tier and rarity, or null (what has none cannot be traded). */
    static int[] tr(String id) {
        if (id == null) return null;
        String[] t = Items.thing(id);
        if (t == null) t = Items.ttrOf(id);
        if (t != null) return new int[]{Integer.parseInt(t[1]), Integer.parseInt(t[2])};
        Production.Design d = Items.made(id);
        return d == null ? null : new int[]{d.tier, d.rarity};
    }

    static String name(String id) {
        String[] t = Items.thing(id);
        if (t == null) t = Items.ttrOf(id);
        if (t != null) return t[3];
        Production.Design d = Items.made(id);
        return d != null ? d.name : id;
    }

    /** What the player carries that can be traded: id -> {tier, rarity, how many}. */
    static Map<String, int[]> sellable(Player p) {
        Map<String, int[]> m = new LinkedHashMap<>();
        for (ItemStack it : p.getInventory().getContents()) {
            String id = Items.id(it);
            int[] t = tr(id);
            if (t == null) continue;
            int[] o = m.computeIfAbsent(id, k -> new int[]{t[0], t[1], 0});
            o[2] += it.getAmount();
        }
        return m;
    }

    /** Takes one of the item from the player. */
    static boolean take(Player p, String id) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (it == null || !id.equals(Items.id(it))) continue;
            ItemStack one = it.clone();
            one.setAmount(1);
            return p.getInventory().removeItem(one).isEmpty();
        }
        return false;
    }

    /** The traders' wares of this scene: item id -> stock; and the party's credit with them. */
    static final Map<String, Integer> wares = new LinkedHashMap<>();
    static int credit;
    static Travel.Met tradingWith;

    /** Their wares, drawn once for the scene: six goods and two made things of tier I-II. */
    static void stock(Random rnd) {
        wares.clear();
        credit = 0;
        List<String> goods = new ArrayList<>(), made = new ArrayList<>();
        for (Production.ItemType ty : Production.TYPES.values())
            for (Production.Named n : ty.items) if (n.tier <= 2 && n.rarity <= 2) goods.add(Items.storeId(ty.name, n.tier, n.rarity, n.name));
        for (Production.Design d : Production.DESIGNS.values()) if (d.tier <= 2) made.add(Items.madeId(d));
        java.util.Collections.shuffle(goods, rnd);
        java.util.Collections.shuffle(made, rnd);
        for (int k = 0; k < Math.min(6, goods.size()); k++) wares.put(goods.get(k), 2 + rnd.nextInt(9));
        for (int k = 0; k < Math.min(2, made.size()); k++) wares.put(made.get(k), 1 + rnd.nextInt(2));
    }

    static void tradeMenu(FalloutakPlugin pl, Player p, Travel.Met m) {
        if (tradingWith != m) { tradingWith = m; stock(new Random(pl.rnd.nextLong())); }
        Character c = pl.character(p);
        List<Component> lines = new ArrayList<>();
        List<Ui.Btn> b = new ArrayList<>();
        lines.add(line(m.e.text() + ": barter (your Economy " + Math.round(c.skill("Economy")) + "). Credit: " + credit + ". They give no change.", NamedTextColor.GOLD));
        for (Map.Entry<String, Integer> w : wares.entrySet()) {
            int[] t = tr(w.getKey());
            if (t == null || w.getValue() <= 0) continue;
            int price = buyPrice(t[0], t[1], c);
            b.add(btn("Buy: " + name(w.getKey()) + " (" + price + ")", w.getValue() + " left; tier " + t[0] + ", rarity " + t[1], x -> {
                if (credit < price) say(x, "! not enough credit: sell them something first");
                else {
                    credit -= price;
                    wares.merge(w.getKey(), -1, Integer::sum);
                    for (ItemStack rest : x.getInventory().addItem(Items.make(w.getKey(), 1)).values()) x.getWorld().dropItem(x.getLocation(), rest);
                }
                tradeMenu(pl, x, m);
            }));
        }
        for (Map.Entry<String, int[]> e : sellable(p).entrySet()) {
            int price = sellPrice(e.getValue()[0], e.getValue()[1], c);
            if (price <= 0) continue;
            b.add(btn("Sell: " + name(e.getKey()) + " (" + price + ")", "one of " + e.getValue()[2] + " you carry", x -> {
                if (take(x, e.getKey())) credit += price;
                tradeMenu(pl, x, m);
            }));
        }
        b.add(btn("Done trading", "the traders go their way" + (credit > 0 ? " (your " + credit + " credit is lost)" : ""), x -> {
            Travel.scene.remove(m); tradingWith = null; credit = 0; wares.clear(); Travel.menu(pl, x);
        }));
        Menus.menu(p, "Trade: " + m.e.kind().name(), lines, b, x -> Travel.menu(pl, x));
    }
}
