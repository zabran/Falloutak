package cz.lollywood.falloutak;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * THE ANOMALIES (`09c-ANOMALIES.md`; `09b` round 9, Marek 11-23, verbatim: *"I agree with the anomalies+artefacts - please add them to
 * the plugin."* · *"the black glass - make it +60 while standing still."* · *""Do you want a Stalker-style "surge" storm?" - yes, I would
 * make it the Black Rain. Build it."*). An anomaly is a ZONE that never ages ({@link Zones.Zone#anomaly}, a ball the GM places): its tell
 * shows as particles; walking in, its first hit lands at once (Lucky Escape 2 × Luck % jerks the body back); inside, the rounds run
 * (part 5's environment rounds) and each round it acts as `09c` §2 says; the kinds that recharge (the throw, the pull, the lightning, the
 * lift) are safe for a round after they fire (the lightning two). The nut sets one off from 10 blocks and shows its edge; the detector
 * (Tier I-IV) beeps, points, names it and sees an artifact; an artifact lies at a field's heart and is picked up by walking to it, and acts
 * from the hotbar (the belt: a quick-item slot). The numbers are 09c's, all EXAMPLE but the black glass's +60 (Marek's).
 * THE BLACK RAIN (the surge): the GM warns, then the storm breaks for a number of the scene's rounds; out under the sky every round brings
 * rads and the acid's burns, a roof or the hazard suit keeps it off; when it ends the fields move and new artifacts lie in them.
 */
final class Anomalies {
    /** A kind (09c §2): its tell, how many rounds it is spent after firing (0 = never recharges, acts every round), its artifact. */
    record Kind(String id, String name, String tell, int recharge, String artifact, boolean hurts) {}

    static final Map<String, Kind> KINDS = new LinkedHashMap<>();

    /** An artifact (09c §3): a gain with a price; {@code mods} and {@code other} are what the sheet counts while it is in the hotbar. */
    record Artifact(String name, String from, String gain, String price, int[] mods, Map<String, Double> other) {
        String text() { return gain + "; the price: " + price; }
    }

    static final Map<String, Artifact> ARTIFACTS = new LinkedHashMap<>();

    private static void k(String id, String name, String tell, int recharge, String artifact, boolean hurts) {
        KINDS.put(id, new Kind(id, name, tell, recharge, artifact, hurts));
    }

    private static void a(String name, String from, String gain, String price, int[] mods, Map<String, Double> other) {
        ARTIFACTS.put(name, new Artifact(name, from, gain, price, mods, other));
    }

    static {
        k("throw", "gravity: the throw", "a shimmer, leaves dancing, flattened grass", 1, "Feather stone", true);
        k("pull", "gravity: the pull", "the same shimmer, with debris heaped at its heart", 1, "Feather stone", true);
        k("lightning", "lightning", "crackle, ozone, burnt spots, the hair stands up", 2, "Spark coil", true);
        k("heat", "heat", "rising air that shimmers, scorched ground, dead insects", 0, "Ember", true);
        k("acid", "acid fog", "a green-grey haze, etched stone and glass", 0, "Glass slug", false);   // its harm is the acid fog gas (03b §3c)
        k("time", "time", "clocks and Pip-Boys disagree, rain hangs in the air", 0, "Stopped watch", true);
        k("cold", "cold", "frost in summer, ice on the grass", 0, "Frost pearl", true);
        k("magnet", "the magnet", "needles spin, cans rolled into a heap, nails standing", 0, "Lodestone", false);
        k("echo", "the echo", "voices of day D, a radio's last news, a crowd", 0, "Shell", true);
        k("mirage", "the mirage", "a place or a person that should not be there", 0, "", false);
        k("radio", "the radio ghost", "dead radios and Pip-Boys wake and play a pre-war broadcast", 0, "Crystal", false);
        k("growth", "the growth", "plants too big, fresh green in winter, a smell of sap", 0, "Green heart", true);
        k("blind", "the blind spot", "none: a nut thrown in never lands", 0, "Black glass", false);
        k("lift", "the lift", "pieces floating, rain falling up", 1, "Float", false);
        int[] no = new int[7];
        a("Feather stone", "gravity", "carry +15 kg", "+1 rad an hour", no, Map.of("rads_per_hour", 1.0));
        a("Spark coil", "lightning", "recharges a fusion microcell a day", "Isolation −10 %", no, Map.of("isolation", -10.0));
        a("Ember", "heat", "Hypothermia stops at Minor", "Heat −10 %, thirst ×1.25", no, Map.of("cold_cap", 1.0, "heat_res", -10.0, "water_need", 0.25));
        a("Glass slug", "acid fog", "poison and disease +15 %", "armour condition wears ×1.5", no, Map.of("poison_res", 15.0));
        a("Stopped watch", "time", "once a session one d100 rolled again (the GM's)", "+2 rads an hour, sleep need ×1.25", no,
                Map.of("rads_per_hour", 2.0, "sleep_need", 0.25));
        a("Frost pearl", "cold", "Heat +15 %", "Hypothermia +1 an hour", no, Map.of("heat_res", 15.0, "cold_per_hour", 1.0));
        a("Lodestone", "the magnet", "no metal weapon is ever torn from his hand; nuts thrown never miss", "firearms near it jam on 1 of d6 (the wearer's)",
                no, Map.of());
        a("Shell", "the echo", "hears through walls (Perception +2 for hearing)", "nightmares: sleep debt +1 hour a night", no, Map.of());
        a("Green heart", "the growth", "healing rate +1", "hunger ×1.25", no, Map.of("healing_rate", 1.0, "food_need", 0.25));
        a("Crystal", "the radio ghost", "hears every radio within 20 km", "the Pip-Boy glitches (its map off a day in six)", no, Map.of());
        // Marek 11-23, round 9, verbatim: "the black glass - make it +60 while standing still."
        a("Black glass", "the blind spot", "Sneak +60 standing still", "Perception −1", new int[]{0, -1, 0, 0, 0, 0, 0}, Map.of());
        a("Float", "the lift", "a fall's first 3 blocks harm nothing", "carry −10 kg", no, Map.of());
    }

    static final int NUT_REACH = 10, DETECT_REACH = 10, RAIN_ROUNDS = 30;
    /** The Black Rain out under the sky, a round (09c §5, round 9) [mine, EXAMPLE]: rads before resistance, the acid's d4. */
    static final double RAIN_RADS = 25;

    final FalloutakPlugin pl;
    private final Map<UUID, Location> last = new HashMap<>();
    final Random rnd = new Random();

    Anomalies(FalloutakPlugin pl) { this.pl = pl; }

    // ------------------------------------------------------------------ where
    /** The anomaly a point is in, or null. */
    Zones.Zone at(Location l) {
        for (Zones.Zone z : pl.zones.list) if (!z.anomaly.isEmpty() && z.contains(l)) return z;
        return null;
    }

    static double surfaceDistance(Zones.Zone z, Location l) {
        if (z.ball) {
            double dx = l.getX() - z.x1, dy = l.getY() - z.y1, dz = l.getZ() - z.z1;
            return Math.max(0, Math.sqrt(dx * dx + dy * dy + dz * dz) - z.r);
        }
        double dx = Math.max(0, Math.max(z.x1 - l.getX(), l.getX() - z.x2)), dy = Math.max(0, Math.max(z.y1 - l.getY(), l.getY() - z.y2)),
                dz = Math.max(0, Math.max(z.z1 - l.getZ(), l.getZ() - z.z2));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ------------------------------------------------------------------ each second, for a player
    /**
     * The body in an anomaly (each second, after the gases): the effect it has while inside, its physical push, the first hit on walking in,
     * the artifact at its heart. Messages go to the player.
     */
    void apply(Player p, Character c) {
        Zones.Zone z = c.dead ? null : at(p.getLocation().add(0, 0.5, 0));
        String before = c.anomalyZone;
        c.anomalyZone = z == null ? "" : z.id;
        c.anomaly = z == null ? "" : z.anomaly;
        if (z == null) {
            c.effects.removeIf(e -> e.slot().equals("anomaly"));
            return;
        }
        Kind k = KINDS.get(z.anomaly);
        c.anomalyCentre = z.centre(p.getWorld()).distanceSquared(p.getLocation().add(0, 0.5, 0)) <= 2.25;
        if (z.anomaly.equals("acid") && (c.zoneGas == null || c.zoneGas.isEmpty())) { c.zoneGas = "acid fog"; c.zoneStrength = 2; }
        Character.Effect in = insideEffect(c, z.anomaly);
        if (in == null) c.effects.removeIf(e -> e.slot().equals("anomaly")); else c.add(in);
        physical(p, c, z);
        if (!z.id.equals(before)) {
            String s = enter(c, z, pl.zones.round, rnd);
            p.sendMessage(Component.text(s, NamedTextColor.LIGHT_PURPLE));
            pl.zones.save();
        }
        if (!z.artifact.isEmpty() && c.anomalyCentre) {
            Items.give(p, "artifact:" + z.artifact, 1);
            p.sendMessage(Component.text("You take the " + z.artifact + " from the " + k.name() + "'s heart: " + ARTIFACTS.get(z.artifact).text()
                    + " (it acts from your hotbar).", NamedTextColor.GOLD));
            z.artifact = "";
            pl.zones.save();
        }
    }

    /** What a kind does to the sheet while one is inside (one effect in the slot "anomaly"), or null. */
    static Character.Effect insideEffect(Character c, String kind) {
        Map<String, Double> o = new LinkedHashMap<>();
        String what;
        switch (kind) {
            case "magnet" -> { o.put("to_hit", -30.0); what = "the magnet: firearms −30 to hit, a jam on 1-2 of d6, metal pulled from the hand"; }
            case "echo" -> {
                if (c.deaf()) return null;
                double v = c.wearing.contains("Earplugs") || c.wearing.contains("Electronic muffs") ? -5 : -10;
                for (Rules.Skill s : Rules.SKILLS) o.put("skill:" + s.name(), v);
                what = "the echo's fear: " + (int) v + " to every check";
            }
            case "radio" -> { o.put("skill:Sneak", -30.0); what = "the radio ghost: your electronics play loud, Sneak −30"; }
            case "blind" -> { o.put("to_hit", -40.0); what = "the blind spot: blind inside, unseen from outside"; }
            default -> { return null; }
        }
        return new Character.Effect("In " + what, "anomaly", "anomaly", false, new int[7], o, 1e6, null, 100);
    }

    /** The world's part: the pull drags, the lift floats, the growth holds, the blind spot blinds and hides, the echo turns the head. */
    void physical(Player p, Character c, Zones.Zone z) {
        Location mid = z.centre(p.getWorld());
        switch (z.anomaly) {
            case "pull" -> p.setVelocity(mid.toVector().subtract(p.getLocation().toVector()).setY(0).normalize().multiply(0.25));
            case "lift" -> {
                double floor = z.ball ? z.y1 - z.r : z.y1;
                if (p.getLocation().getY() < floor + 6) p.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 30, 0, false, false));
                else p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 30, 0, false, false));
            }
            case "growth" -> p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 3, false, false));
            case "blind" -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, false, false));
                p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 30, 0, false, false));
            }
            case "echo" -> { if (!c.deaf()) p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 100, 0, false, false)); }
            default -> { }
        }
        String fx = c.anomalyFx;
        c.anomalyFx = "";
        if (fx.equals("throw")) {
            Vector away = p.getLocation().toVector().subtract(mid.toVector()).setY(0);
            if (away.lengthSquared() < 1e-6) away = new Vector(1, 0, 0);
            p.setVelocity(away.normalize().multiply(1.2).setY(0.6));
        } else if (fx.equals("lightning")) Battle.cosmetic(() -> p.getWorld().strikeLightningEffect(p.getLocation()));
    }

    /**
     * Walking in (09c §1.5): its first hit at once, before anyone can act; Lucky Escape (part 4's luck procs, 2 × Luck %) lets the body
     * jerk back before it bites (the first hit only). The kinds that only change the sheet tell what they do.
     */
    static String enter(Character c, Zones.Zone z, long round, Random rnd) {
        Kind k = KINDS.get(z.anomaly);
        String head = "AN ANOMALY: " + k.name() + " (" + k.tell() + "). ";
        if (!k.hurts() && k.recharge() == 0) return head + hit(c, k, rnd);
        int luck = c.attr(Rules.L, false), d = 1 + rnd.nextInt(100);
        if (d <= 2 * luck) {
            c.anomalyFx = "throw";   // the jerk back out of it
            return head + "LUCKY ESCAPE (d100 " + d + " ≤ 2 × Luck " + luck + "): the body jerks back before it bites.";
        }
        return head + fire(c, z, round, rnd);
    }

    /** A round inside (09c §2): a kind that recharges acts only when charged and is then spent; the others every round. */
    static String fire(Character c, Zones.Zone z, long round, Random rnd) {
        Kind k = KINDS.get(z.anomaly);
        if (k.recharge() > 0) {
            if (round < z.spentUntil) return "the " + k.name() + " is spent: nothing this round";
            z.spentUntil = round + k.recharge() + 1;
        }
        return hit(c, k, rnd);
    }

    static int dice(Random rnd, int n, int sides) { int s = 0; for (int i = 0; i < n; i++) s += 1 + rnd.nextInt(sides); return s; }

    /** What the kind does to the body, once (09c §2's column). */
    static String hit(Character c, Kind k, Random rnd) {
        switch (k.id()) {
            case "throw" -> {
                c.anomalyFx = "throw";
                return "the throw: " + Hud.hit(c, c.harm(dice(rnd, 3, 10), 0), "") + "; thrown 2d6 blocks (part 5's knockback: an Athletics check against the fall)";
            }
            case "pull" -> {
                if (!c.anomalyCentre) return "the pull drags you a block toward its heart: a Strength check to get out (−10 a block in; drop the pack +20; a rope: the puller's Strength)";
                return "crushed at the pull's heart: " + Hud.hit(c, c.harm(dice(rnd, 2, 10), 0), "");
            }
            case "lightning" -> {
                c.anomalyFx = "lightning";
                boolean metal = c.armourClass() >= 2 && c.resistances()[5] < 20;
                int raw = dice(rnd, 4, 8);
                String h = Hud.hit(c, c.harm(metal ? (int) Math.round(raw * 1.5) : raw, 5), "");
                c.survival.put(Rules.Stat.PAIN, Math.min(100, c.survival.getOrDefault(Rules.Stat.PAIN, 0.0) + 20));
                return "lightning" + (metal ? " (×1.5, metal armour)" : "") + ": " + h + "; Pain +20, AP −4 next turn";
            }
            case "heat" -> {
                c.survival.put(Rules.Stat.WATER, Math.max(0, c.survival.getOrDefault(Rules.Stat.WATER, 0.0) - 2));
                return "the heat: " + Hud.hit(c, c.harm(dice(rnd, 2, 10), 4), "") + "; thirst +2 (carried fuel and explosives cook off after 3 rounds)";
            }
            case "time" -> {
                for (int h = 0; h < 6; h++) Hud.hour(c);
                return "time runs: 6 hours pass for your body this round (hunger, thirst, sleep, chems, bleeding and healing); outside you stand frozen";
            }
            case "cold" -> {
                double add = 10 * (1 - Math.max(0, Math.min(100, c.resistances()[5])) / 100);
                c.survival.put(Rules.Stat.HYPOTHERMIA, Math.min(c.max(Rules.Stat.HYPOTHERMIA), c.survival.getOrDefault(Rules.Stat.HYPOTHERMIA, 0.0) + add));
                return "the cold: Hypothermia +" + SheetGui.f(add) + "; bare hands on metal: Pain 5 and the weapon sticks (a Strength check to let go)";
            }
            case "echo" -> {
                if (c.deaf()) return "the echo: deaf, you hear none of it";
                return "the echo: day D's voices; a Survival check or you walk a random way this round (a leader's Leadership check steadies the party)";
            }
            case "growth" -> {
                double ath = c.skills()[Rules.skillIndex("Athletics")];
                int d = 1 + rnd.nextInt(100);
                if (d <= ath) return "the growth: you tear free this round (Athletics, d100 " + d + "); moving costs AP ×2";
                String h = Hud.hit(c, c.harm(dice(rnd, 1, 6), 0), "");
                c.survival.put(Rules.Stat.PAIN, Math.min(100, c.survival.getOrDefault(Rules.Stat.PAIN, 0.0) + 5));
                return "held by the growth (Athletics, d100 " + d + "): the thorns " + h + ", Pain +5; spores: one disease roll (the GM; a blade cuts free, 4 AP; fire clears it)";
            }
            case "mirage" -> { return "the mirage: a Perception check (−20) sees through it; else shots at it are wasted and the way chosen is wrong (the GM)"; }
            case "radio" -> { return "the radio ghost: electronics play loud (Sneak −30 until switched off); a fusion microcell loses a shot a round; an implant switches off on 1 of d6; robots an Intelligence check"; }
            case "magnet" -> { return "the magnet: firearms −30 to hit and jam on 1-2 of d6; a metal weapon pulled from the hand (a Strength check a round); power armour stuck (moving AP ×2)"; }
            case "blind" -> { return "the blind spot: blind inside, unseen from outside (only area attacks reach you)"; }
            case "lift" -> { return "the lift: you float up a block a round to 6 blocks and hang (no moving, only grabbing); out of it you fall (a rope or an anchor holds you down)"; }
            case "acid" -> { return "the acid fog: 03b §3c's acid zone (the skin, the lungs, the armour's condition)"; }
            default -> { return ""; }
        }
    }

    /** The round's anomaly for a body in rounds ({@link Hud#round}); null when it is in none. */
    String round(Character c, Random rnd) {
        if (c.anomalyZone.isEmpty()) return null;
        for (Zones.Zone z : pl.zones.list)
            if (z.id.equals(c.anomalyZone) && !z.anomaly.isEmpty()) {
                Kind k = KINDS.get(z.anomaly);
                if (!k.hurts() && k.recharge() == 0) return null;   // only the sheet's effect while inside: said on the way in
                return fire(c, z, pl.zones.round, rnd);
            }
        return null;
    }

    // ------------------------------------------------------------------ the tells
    /** Each second: every anomaly someone is near shows its tell (09c §2); the blind spot shows none; to the GM its outline. */
    void tells() {
        for (Zones.Zone z : pl.zones.list) {
            if (z.anomaly.isEmpty()) continue;
            World w = Bukkit.getWorld(z.world);
            if (w == null) continue;
            Location mid = z.centre(w);
            List<Player> near = new ArrayList<>();
            for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(mid) <= 48 * 48) near.add(p);
            if (near.isEmpty()) continue;
            Particle pt = switch (z.anomaly) {
                case "throw", "pull" -> Particle.PORTAL;
                case "lightning" -> Particle.ELECTRIC_SPARK;
                case "heat" -> Particle.SMALL_FLAME;
                case "acid" -> Particle.DUST;
                case "time" -> Particle.REVERSE_PORTAL;
                case "cold" -> Particle.SNOWFLAKE;
                case "magnet" -> Particle.ENCHANTED_HIT;
                case "echo", "radio" -> Particle.NOTE;
                case "mirage" -> Particle.END_ROD;
                case "growth" -> Particle.HAPPY_VILLAGER;
                case "lift" -> Particle.CLOUD;
                default -> null;
            };
            double r = z.ball ? z.r : Math.max(z.x2 - z.x1, z.z2 - z.z1) / 2;
            for (int i = 0; i < 6; i++) {
                Location at = mid.clone().add((rnd.nextDouble() * 2 - 1) * r, (rnd.nextDouble() - 0.5) * r, (rnd.nextDouble() * 2 - 1) * r);
                if (pt == Particle.DUST) w.spawnParticle(pt, at, 1, 0.3, 0.3, 0.3, 0.0, new Particle.DustOptions(Color.fromRGB(170, 200, 140), 1.8f));
                else if (pt != null) w.spawnParticle(pt, at, 1, 0.2, 0.2, 0.2, 0.0);
            }
            for (Player g : near) if (g.hasPermission("falloutak.gm")) edge(g, z, 8);
            if (!z.artifact.isEmpty())   // an artifact glints only for a Tier IV detector (09c §1.4)
                for (Player p : near) if (detectorTier(Items.carried(p)) >= 4) p.spawnParticle(Particle.END_ROD, mid, 2, 0.1, 0.1, 0.1, 0.0);
        }
    }

    /** The zone's edge as a ring of faint dots, to one player. */
    static void edge(Player p, Zones.Zone z, int n) {
        World w = p.getWorld();
        Location mid = z.centre(w);
        double r = z.ball ? z.r : Math.max(z.x2 - z.x1, z.z2 - z.z1) / 2;
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            p.spawnParticle(Particle.DUST, mid.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r), 1, 0, 0, 0, 0.0,
                    new Particle.DustOptions(Color.fromRGB(150, 60, 200), 1f));
        }
    }

    // ------------------------------------------------------------------ the nut (09c §1.3)
    /**
     * A nut thrown where he looks, up to 10 blocks: the first anomaly on its way is set off and shows its edge; the recharging kinds are
     * then safe for their rounds; the blind spot swallows it. Returns what he sees.
     */
    String throwNut(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        for (double t = 0.5; t <= NUT_REACH; t += 0.5) {
            Location l = eye.clone().add(dir.clone().multiply(t));
            if (l.getBlock().getType().isSolid()) return "the nut hits the ground " + Math.round(t) + " blocks away: nothing there";
            Zones.Zone z = at(l);
            if (z == null) continue;
            Kind k = KINDS.get(z.anomaly);
            if (z.anomaly.equals("blind")) return "the nut flies in " + Math.round(t) + " blocks away and NEVER LANDS: a blind spot";
            for (int i = 0; i < 3; i++) edge(p, z, 24);
            String spent = "";
            if (k.recharge() > 0) {
                z.spentUntil = pl.zones.round + k.recharge() + 1;
                pl.zones.save();
                spent = "; it is spent: safe for " + k.recharge() + " round" + (k.recharge() > 1 ? "s" : "");
            }
            Battle.cosmetic(() -> p.getWorld().playSound(l, Sound.BLOCK_CHAIN_FALL, 0.8f, 1.4f));
            return "the nut sets off " + k.name() + " " + Math.round(t) + " blocks away (" + k.tell() + "); its edge shows"
                    + (spent.isEmpty() ? "; it never recharges: it acts on anything that goes in" : spent);
        }
        return "the nut lands " + NUT_REACH + " blocks away: nothing there";
    }

    // ------------------------------------------------------------------ the detector (09c §1.4)
    static int detectorTier(Map<String, Integer> carry) {
        for (int t = 4; t >= 1; t--) if (carry.containsKey("gear:Anomaly detector " + Rules.roman(t))) return t;
        return 0;
    }

    /**
     * The detector's reading for the action bar, and its beeps: I = beeps faster the nearer (any anomaly within 10 blocks, the blind spot
     * too), II = the direction, III = the kind, IV = an artifact inside. "" without one.
     */
    String detect(Player p, Map<String, Integer> carry) {
        int tier = detectorTier(carry);
        if (tier == 0) return "";
        Zones.Zone best = null;
        double bd = 1e9;
        for (Zones.Zone z : pl.zones.list) {
            if (z.anomaly.isEmpty() || !z.world.equals(p.getWorld().getName())) continue;
            double d = surfaceDistance(z, p.getLocation());
            if (d < bd) { bd = d; best = z; }
        }
        if (best == null || bd > DETECT_REACH) return " · DETECTOR " + Rules.roman(tier) + ": quiet";
        int beeps = bd < 2 ? 4 : bd < 5 ? 2 : 1;
        for (int i = 0; i < beeps; i++) Battle.cosmetic(() -> p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.8f));
        StringBuilder s = new StringBuilder(" · DETECTOR: " + Math.round(bd) + " m");
        if (tier >= 2) s.append(" ").append(direction(p, best.centre(p.getWorld())));
        if (tier >= 3) s.append(" · ").append(KINDS.get(best.anomaly).name());
        if (tier >= 4 && !best.artifact.isEmpty()) s.append(" · AN ARTIFACT inside");
        return s.toString();
    }

    static String direction(Player p, Location to) {
        Vector look = p.getLocation().getDirection().setY(0), v = to.toVector().subtract(p.getLocation().toVector()).setY(0);
        if (v.lengthSquared() < 1e-6 || look.lengthSquared() < 1e-6) return "(here)";
        double a = Math.toDegrees(Math.atan2(look.getX() * v.getZ() - look.getZ() * v.getX(), look.getX() * v.getX() + look.getZ() * v.getZ()));
        return a > -45 && a < 45 ? "ahead" : a >= 45 && a <= 135 ? "to the right" : a <= -45 && a >= -135 ? "to the left" : "behind";
    }

    // ------------------------------------------------------------------ artifacts on the belt
    /** The artifacts in the hotbar (the belt's quick-item slots) act; the black glass's +60 only while he stands still (Marek, round 9). */
    void belt(Player p, Character c) {
        Set<String> names = new TreeSet<>();
        for (int i = 0; i < 9; i++) {
            ItemStack it = p.getInventory().getItem(i);
            String id = Items.id(it);
            if (id != null && id.startsWith("artifact:") && ARTIFACTS.containsKey(id.substring(9))) names.add(id.substring(9));
        }
        Location now = p.getLocation(), prev = last.put(p.getUniqueId(), now.clone());
        boolean still = prev != null && prev.getWorld() != null && prev.getWorld().equals(now.getWorld()) && prev.distanceSquared(now) < 0.01;
        c.belt(names, still);
    }

    // ------------------------------------------------------------------ the GM's
    /** A field (09c §1.1): {@code n} anomalies of mixed kinds within {@code radius} blocks of him on the ground, 1-3 artifacts in them. */
    List<Zones.Zone> field(Player g, int radius, int n) {
        List<String> kinds = new ArrayList<>(KINDS.keySet());
        List<Zones.Zone> made = new ArrayList<>();
        World w = g.getWorld();
        for (int i = 0; i < n; i++) {
            double ang = rnd.nextDouble() * 2 * Math.PI, d = 4 + rnd.nextDouble() * Math.max(1, radius - 4);
            int x = (int) Math.floor(g.getLocation().getX() + Math.cos(ang) * d), z = (int) Math.floor(g.getLocation().getZ() + Math.sin(ang) * d);
            int y = ground(w, x, z, g.getLocation().getBlockY()) + 1;
            made.add(pl.zones.anomaly(new Location(w, x + 0.5, y + 1, z + 0.5), 2 + rnd.nextInt(2), kinds.get(rnd.nextInt(kinds.size()))));
        }
        int arts = 1 + rnd.nextInt(3);
        List<Zones.Zone> with = new ArrayList<>();
        for (Zones.Zone z : made) if (!KINDS.get(z.anomaly).artifact().isEmpty()) with.add(z);
        Collections.shuffle(with, rnd);
        for (Zones.Zone z : with.subList(0, Math.min(arts, with.size()))) z.artifact = KINDS.get(z.anomaly).artifact();
        pl.zones.save();
        return made;
    }

    /** The top of the ground at x, z near {@code y} (the first solid block scanning down from 24 above). */
    static int ground(World w, int x, int z, int y) {
        for (int k = y + 24; k > y - 48; k--) if (w.getBlockAt(x, k, z).getType().isSolid()) return k;
        return y - 1;
    }

    /** Nothing solid over the head for 48 blocks: out under the sky. */
    static boolean openSky(World w, Location l) {
        int x = l.getBlockX(), z = l.getBlockZ(), y0 = l.getBlockY() + 2;
        for (int y = y0; y < y0 + 48; y++) if (w.getBlockAt(x, y, z).getType().isSolid()) return false;
        return true;
    }

    // ------------------------------------------------------------------ THE BLACK RAIN (the surge, round 9)
    /** 0 = none, 1 = the warning, 2 = the storm. */
    int rainPhase = 0;
    String rainWorld = "";
    long rainEnd = 0;
    private int rainTicks = 0;

    static final String[] WARNINGS = {
            "Every radio and Pip-Boy near wakes by itself: a pre-war emergency broadcast, then static.",
            "The ravens rise from the trees and fly off, all one way. The Geiger counters tick faster.",
            "The sky in the west turns brown-black; the wind drops; a smell of soot and burnt oil.",
            "The first black drops fall, oily on the skin. FIND A ROOF."};

    /** The GM's warning: the signs in that world (09c §5's: the radio ghost, the ravens fleeing), before the storm breaks. */
    String warn(World w) {
        rainPhase = 1;
        rainWorld = w.getName();
        rainTicks = 0;
        Battle.cosmetic(() -> w.setStorm(true));
        return "The Black Rain is coming to " + w.getName() + ": the signs show every few seconds; start the storm when you like.";
    }

    /** The storm breaks for {@code rounds} of the scene's rounds (a fight's, or the GM's Pass time for everyone). */
    String start(World w, int rounds) {
        rainPhase = 2;
        rainWorld = w.getName();
        rainEnd = pl.zones.round + Math.max(1, rounds);
        Battle.cosmetic(() -> { w.setStorm(true); w.setThundering(true); });
        for (Player p : w.getPlayers()) p.sendMessage(Component.text("THE BLACK RAIN. Out under the sky it burns and it is hot: get under a roof.", NamedTextColor.DARK_RED));
        return "The Black Rain falls on " + w.getName() + " for " + rounds + " rounds: out in it each round +" + SheetGui.f(RAIN_RADS)
                + " rads (before resistance) and the acid's d4; a roof or the hazard suit keeps it off.";
    }

    /** It ends: the weather clears, the fields MOVE (up to 8 blocks) and new artifacts lie in half of the empty ones (09c §5). */
    String stop() {
        if (rainPhase == 0) return "No Black Rain now.";
        World w = Bukkit.getWorld(rainWorld);
        rainPhase = 0;
        int moved = 0, seeded = 0;
        for (Zones.Zone z : pl.zones.list) {
            if (z.anomaly.isEmpty() || !z.world.equals(rainWorld)) continue;
            double dx = rnd.nextInt(17) - 8, dz = rnd.nextInt(17) - 8;
            z.x1 += dx; z.z1 += dz;
            if (!z.ball) { z.x2 += dx; z.z2 += dz; }
            moved++;
            String art = KINDS.get(z.anomaly).artifact();
            if (z.artifact.isEmpty() && !art.isEmpty() && rnd.nextBoolean()) { z.artifact = art; seeded++; }
        }
        pl.zones.save();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Character c = pl.characters.get(p.getUniqueId());
            if (c != null) { c.rainExposed = false; c.effects.removeIf(e -> e.slot().equals("black rain")); }
        }
        if (w != null) Battle.cosmetic(() -> { w.setStorm(false); w.setThundering(false); });
        return "The Black Rain is over. " + moved + " anomal" + (moved == 1 ? "y" : "ies") + " moved, " + seeded + " new artifact" + (seeded == 1 ? "" : "s") + " in them.";
    }

    /** Each second: the warning's signs; the storm's exposure (no block over the head), its particles and its end. */
    void rainTick() {
        if (rainPhase == 0) return;
        World w = Bukkit.getWorld(rainWorld);
        if (w == null) { rainPhase = 0; return; }
        rainTicks++;
        if (rainPhase == 1) {
            if (rainTicks % 6 != 1) return;
            String line = WARNINGS[Math.min(WARNINGS.length - 1, rainTicks / 6)];
            for (Player p : w.getPlayers()) {
                p.sendMessage(Component.text(line, NamedTextColor.GRAY));
                Battle.cosmetic(() -> p.playSound(p.getLocation(), rainTicks / 6 == 1 ? Sound.ENTITY_BAT_TAKEOFF : Sound.BLOCK_BEACON_AMBIENT, 0.8f, 0.6f));
            }
            return;
        }
        if (pl.zones.round >= rainEnd) {
            String s = stop();
            for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("falloutak.gm")) p.sendMessage(Component.text(s, NamedTextColor.GOLD));
            return;
        }
        for (Player p : w.getPlayers()) {
            Character c = pl.characters.get(p.getUniqueId());
            if (c == null) continue;
            boolean gm = p.getGameMode() == org.bukkit.GameMode.CREATIVE || p.getGameMode() == org.bukkit.GameMode.SPECTATOR;
            Location l = p.getLocation();
            c.rainExposed = !gm && openSky(w, l);
            if (c.rainExposed && !c.wearing.contains("Hazard suit"))
                c.add(new Character.Effect("In the Black Rain: the eyes sting (P −1)", "black rain", "black rain", false, new int[]{0, -1, 0, 0, 0, 0, 0}, Map.of(), 1e6, null, 100));
            else c.effects.removeIf(e -> e.slot().equals("black rain"));
            Battle.cosmetic(() -> p.spawnParticle(Particle.DUST, l.clone().add(0, 2, 0), 12, 3, 2, 3, 0.0, new Particle.DustOptions(Color.fromRGB(20, 18, 16), 1.6f)));
            if (rnd.nextInt(12) == 0) Battle.cosmetic(() -> p.playSound(l, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.4f, 0.7f));
        }
    }

    /** A round out in the Black Rain ({@link Hud#round}): rads and the acid on the skin, unless the hazard suit keeps it off. */
    static String rainRound(Character c, Random rnd) {
        if (!c.rainExposed || c.dead) return null;
        if (c.wearing.contains("Hazard suit")) return "the Black Rain runs off your hazard suit";
        double res = Math.max(0, Math.min(100, c.resistances()[1])) / 100, rads = RAIN_RADS * (1 - res);
        c.survival.put(Rules.Stat.RADS, Math.min(2000, c.survival.getOrDefault(Rules.Stat.RADS, 0.0) + rads));
        return "THE BLACK RAIN: rads +" + SheetGui.f(rads) + ", the acid " + Hud.hit(c, dice(rnd, 1, 4), "") + " (a roof, the hazard suit)";
    }
}
