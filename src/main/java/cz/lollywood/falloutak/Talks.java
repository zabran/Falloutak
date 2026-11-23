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
 *   <li><b>A hostile group seen first can be talked to ON THE BATTLE MAP</b> (round 4, Marek 11-24: *"the talking should already start on
 *   the battle map, so players should have chance to get into positions at least. Failure happens during that. Only one of players
 *   talks (selectable). Also, players can attack during the fight to get slight advantage."* · the balance *"yes"*): the chosen
 *   speaker's Rhetorics + 20 (they were seen first) − 20 (they came to fight), Negotiator retries a failed check once (02c); the talk
 *   lasts {@link Battle#TALK_ROUNDS} rounds while the party moves into position; talked down = resolved without killing = its full
 *   kill XP (06b §3); a failed talk goes wrong in one of its rounds and they shoot first; an attack in it catches them off guard
 *   ({@link Battle.Talk}).</li>
 *   <li><b>A raider group or a band that comes to talk wants a TOLL</b>: pay it in goods (10 a person in the party, at what they would
 *   pay for them), talk your way out (Rhetorics − 10; the check's XP, at least 10), or refuse (they turn hostile).</li>
 *   <li><b>A patrol brings NEWS</b>: the nearest raider base within 15 km of the party, its group, the way and the distance; A HIDDEN
 *   CAMP only for pay (goods worth {@link #NEWS_PRICE}), a good word (Rhetorics {@value #NEWS_TALK}) or after a barter with traders
 *   (round 4, Marek 11-24: *"seems fine, if barter is done/information is paid or there is successful speech check."*).</li>
 *   <li><b>Traders BARTER</b>: their wares (tier I-II, drawn for the scene), an item worth 10 x 3^(tier − 1) x rarity; they sell at
 *   x (1.5 − Economy / 200, at least 1) and buy at x (0.5 + Economy / 400, at most 1) by the clicking player's Economy (Fallout 2's
 *   barter in spirit); what you sell is credit for what you buy, and they give no change.</li>
 * </ul>
 */
final class Talks {
    private Talks() {}

    static final int SEEN_FIRST = 20, HOSTILE = -20, TOLL_TALK = -10, TOLL_A_HEAD = 10, NEWS_KM = 15, NEWS_PRICE = 20, NEWS_TALK = -10;

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
    /** Who speaks for the party (round 4: *"Only one of players talks (selectable)"*): each one's chance. */
    static void speakerMenu(FalloutakPlugin pl, Player p, Travel.Met m) {
        List<Component> lines = new ArrayList<>();
        List<Ui.Btn> b = new ArrayList<>();
        lines.add(line("Who speaks to " + m.e.kind().name() + "? The talk is on the battle map: " + Battle.TALK_ROUNDS + " rounds in which the rest of you get into "
                + "position. An attack breaks it and catches them off guard; it can also go wrong, and then they shoot first.", NamedTextColor.GOLD));
        for (Player q : Travel.party(p)) {
            Character c = pl.character(q);
            int ch = clamp((int) Math.round(c.skill("Rhetorics")) + SEEN_FIRST + HOSTILE);
            b.add(btn(q.getName() + " speaks (" + ch + " %" + (c.hasPerk("Negotiator") ? ", Negotiator" : "") + ")", "Rhetorics " + Math.round(c.skill("Rhetorics"))
                    + " + 20 the first sight − 20 they came to fight", x -> say(x, parley(pl, x, m, q))));
        }
        Menus.menu(p, "Talk to " + m.e.kind().name(), lines, b, x -> Travel.menu(pl, x));
    }

    static String parley(FalloutakPlugin pl, Player p, Travel.Met m) { return parley(pl, p, m, p); }

    /** The talk with a hostile group the party saw first, on the battle map: the speaker's check now, its outcome in its rounds. */
    static String parley(FalloutakPlugin pl, Player p, Travel.Met m, Player speaker) {
        if (!m.hostile() || !m.seen || !talks(m.e.kind())) return "! you cannot talk to them";
        Check k = check(pl.character(speaker), SEEN_FIRST + HOSTILE, rnd(pl));
        int failAt = k.ok() ? 0 : 1 + rnd(pl).nextInt(Battle.TALK_ROUNDS);
        Battle.Talk t = new Battle.Talk(speaker.getUniqueId(), speaker.getName(), m.e.kind().name(), k.text(), failAt);
        tellGm("travel: " + speaker.getName() + " talks to " + m.e.kind().name() + " (" + k.text() + (k.ok() ? "" : "; it goes wrong in round " + failAt) + ")");
        return Travel.battleMap(pl, p, Arena.Mode.TALK, t, m.e.kind().key());
    }

    // ------------------------------------------------------------------ a peaceful talk
    /** The toll paid so far in this scene (by value); what was paid toward a patrol's price for the hidden camps. */
    static int paid, newsPaid;

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
            lines.add(line("They stop to talk: news of the roads. What they know of hidden camps costs goods worth " + NEWS_PRICE + " (paid so far: " + newsPaid
                    + "), or a good word.", NamedTextColor.YELLOW));
            b.add(btn("Hear their news", "what they say freely of the raiders near here (no hidden camps)", x -> { say(x, news(false)); Travel.scene.remove(m); newsPaid = 0; Travel.menu(pl, x); }));
            b.add(btn("Ask about hidden camps", "Rhetorics " + Battle.sign(NEWS_TALK) + "; failed, they say only the free news", x -> {
                Check k = check(pl.character(x), NEWS_TALK, rnd(pl));
                if (k.ok()) say(x, "They tell you more (" + k.text() + "): " + news(true) + " " + pl.roster.award(x, Math.max(10, -NEWS_TALK), "news talked out of a patrol (the check)"));
                else say(x, "They will not say more (" + k.text() + "): " + news(false));
                Travel.scene.remove(m); newsPaid = 0; Travel.menu(pl, x);
            }));
            for (Map.Entry<String, int[]> e : sellable(p).entrySet()) {
                int v = sellPrice(e.getValue()[0], e.getValue()[1], pl.character(p));
                if (v <= 0) continue;
                b.add(btn("Pay: " + name(e.getKey()) + " (" + v + ")", "one of " + e.getValue()[2] + " you carry, toward their price for the hidden camps", x -> {
                    if (take(x, e.getKey())) newsPaid += v;
                    if (newsPaid >= NEWS_PRICE) { say(x, "Paid (" + newsPaid + "): " + news(true)); Travel.scene.remove(m); newsPaid = 0; Travel.menu(pl, x); }
                    else talkMenu(pl, x, m);
                }));
            }
            b.add(btn("Part ways", "nothing more", x -> { Travel.scene.remove(m); newsPaid = 0; Travel.menu(pl, x); }));
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

    static String news() { return news(false); }

    /** The nearest raider base within {@link #NEWS_KM} km of the party; a hidden camp only when {@code hidden} (paid, talked out, bartered). */
    static String news(boolean hidden) {
        WorldMap m = Travel.map;
        if (m == null) return "They know nothing.";
        WorldMap.Src best = null;
        double bd = Double.MAX_VALUE;
        for (WorldMap.Src b : m.bases) {
            if (b.cleared || !b.type.equals("raiders") || (b.hidden && !hidden)) continue;
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
    /** Something changed hands with the traders this scene: then they share their news, the hidden camps too (round 4). */
    static boolean bartered;
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
        if (tradingWith != m) { tradingWith = m; bartered = false; stock(new Random(pl.rnd.nextLong())); }
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
                    bartered = true;
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
                if (take(x, e.getKey())) { credit += price; bartered = true; }
                tradeMenu(pl, x, m);
            }));
        }
        b.add(btn("Done trading", "the traders go their way" + (credit > 0 ? " (your " + credit + " credit is lost)" : "") + (bartered ? "; they tell you their news" : ""), x -> {
            if (bartered) say(x, "Over the deal they tell you: " + news(true));
            Travel.scene.remove(m); tradingWith = null; credit = 0; wares.clear(); bartered = false; Travel.menu(pl, x);
        }));
        Menus.menu(p, "Trade: " + m.e.kind().name(), lines, b, x -> Travel.menu(pl, x));
    }
}
