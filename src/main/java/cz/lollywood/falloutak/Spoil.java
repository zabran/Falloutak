package cz.lollywood.falloutak;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Spoiling food (`07` §19, RULED 11-24; Marek, verbatim: *"Should food go fresh → stale (one tier down) → spoiled (Tier I) - yes. With
 * T2 staying T2 for quite long and preservation slowling the spoiling. "Should a share of each stored pile go off every day (1 / how long
 * it keeps), with no date on each portion?" - depending on preservation, without it yes. It is nice simplification. Do these places and
 * times fit? - yes. Should food the players carry spoil too? - if they go for longer, then yes, definitely. ... yes, it increases the
 * rarity of the food. Also, I would count food in cold and preserved in cool as not spoiling."*).
 * <p>
 * The store keeps ROWS (a tier and a rarity), not named items, so the pace is a row's: each day a share of every Food row, its count /
 * its keeping days (a carry for the parts), steps one tier down; Tier I rots away. Kept food (rarity 2 and up: the keeping recipes, and
 * the step between of `09d`) never spoils in a cool store, and nothing spoils in a cold one. The days [the table RULED; by tier mine]:
 * Tier IV (proper meals) 2 warm / 4 cool, Tier III (fresh clean food) 21 / 180, Tier II ×3 of that (*"T2 staying T2 for quite long"*),
 * Tier I (raw or spoiled) rots away in 3 / 12; kept food 180 warm (Tier II ×3).
 */
final class Spoil {
    private Spoil() {}

    static final double SLOW_T2 = 3, ROT_T1 = 3, KEPT = 180;

    /** The keeping days of a Food row in a place ("warm", "cool", "cold"); −1 = it does not spoil there. */
    static double days(int tier, int rarity, String place) {
        if (place.equals("cold")) return -1;
        boolean kept = rarity >= 2, warm = place.equals("warm");
        if (kept && !warm) return -1;
        double d = kept ? KEPT : switch (tier) {
            case 4 -> warm ? 2 : 4;
            case 3, 2 -> warm ? 21 : 180;
            default -> (warm ? 1 : 4) * ROT_T1;
        };
        if (tier == 2) d *= SLOW_T2;
        if (tier == 1 && kept) d *= ROT_T1;
        return d;
    }

    /** The base store's place: the coldest one built (a freezer only on the day it had its power), the shelter itself cool (its
     *  own rooms: the reactor, the filtration, the scrubbers, the dormitories; the kitchen is its food store). */
    static String place(Production pr) {
        String best = "warm";
        for (Production.Building b : pr.buildings) {
            if (b.condition <= 0) continue;
            switch (b.kind) {
                case "Freezer" -> { if (b.chargedDay == pr.day) return "cold"; }
                case "Ice house" -> { return "cold"; }
                case "Root cellar", "Dormitories", "Reactor", "Water filtration", "Air scrubbers" -> best = "cool";
                default -> { }
            }
        }
        return best;
    }

    /** A day of the base's store: each Food row's share steps down. What spoiled, for the GM, or null. */
    static String store(Production pr, Base base) {
        if (base == null) return null;
        if (pr.spoilCarry == null) pr.spoilCarry = new LinkedHashMap<>();
        String place = place(pr);
        StringJoiner out = new StringJoiner(", ");
        List<Base.Pool> rows = new ArrayList<>();
        for (Base.Pool p : base.pools.values()) if (p.type.equals("Food") && p.count > 0) rows.add(p);
        for (Base.Pool p : rows) {
            double d = days(p.tier, p.rarity, place);
            if (d <= 0) continue;
            String key = p.key();
            double total = p.count / d + pr.spoilCarry.getOrDefault(key, 0.0);
            int k = Math.min(p.count, (int) Math.floor(total + 1e-9));
            pr.spoilCarry.put(key, total - k);
            if (k <= 0) continue;
            int tier = p.tier, rarity = p.rarity;
            base.takeUnits("Food", tier, rarity, k);
            if (tier > 1) base.addUnits("Food", tier - 1, rarity, k);
            out.add(k + " Food " + Rules.roman(tier) + (rarity > 1 ? " R" + rarity : "") + (tier > 1 ? " → " + Rules.roman(tier - 1) : " rotted away"));
        }
        return out.length() == 0 ? null : "Spoiling (the store keeps " + place + "): " + out;
    }

    /**
     * A day of the food a player carries: the same rule, the season's air (winter cold, else warm); a stale portion keeps its name,
     * a Tier I one rots away. The parts carried on the character. What spoiled, or null.
     */
    static String carried(Player p, Character c) {
        if (p == null) return null;
        String place = Travel.season == 0 ? "cold" : "warm";
        StringJoiner out = new StringJoiner(", ");
        for (Map.Entry<String, Integer> e : new ArrayList<>(Items.carried(p).entrySet())) {
            String[] th = Items.thing(e.getKey());
            if (th == null || !th[0].equals("Food")) continue;
            int tier = Integer.parseInt(th[1]), rarity = Integer.parseInt(th[2]);
            double d = days(tier, rarity, place);
            if (d <= 0) continue;
            double total = e.getValue() / d + c.spoilCarry.getOrDefault(e.getKey(), 0.0);
            int k = Math.min(e.getValue(), (int) Math.floor(total + 1e-9));
            c.spoilCarry.put(e.getKey(), total - k);
            if (k <= 0) continue;
            int took = 0;
            for (int i = 0; i < k && Items.take(p, e.getKey()); i++) took++;
            if (tier > 1 && took > 0) Items.give(p, Items.thingId("Food", tier - 1, rarity, th[3]), took);
            out.add(took + " " + (th[3].isEmpty() ? "Food" : th[3]) + (tier > 1 ? " went " + (tier == 2 ? "bad (Tier I)" : "stale (Tier " + Rules.roman(tier - 1) + ")") : " rotted away"));
        }
        return out.length() == 0 ? null : "Your food: " + out;
    }
}
