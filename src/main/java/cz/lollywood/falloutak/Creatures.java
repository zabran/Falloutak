package cz.lollywood.falloutak;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Hoglin;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Silverfish;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The game's own creatures in the world (Marek 11-19: *"Is there some way that you could create and / or animate the models?"* → yes,
 * the Radmie first, the Trhač next). A creature is a plain base entity (AI off, our code moves it) wearing a Blockbench model through
 * BetterModel when that plugin is on the server (`models/<name>.bbmodel`, made by `tools/models/<name>.py`); without it, the base
 * itself is the stand-in, scaled up: a silverfish for the Radmie, a hoglin (a boar) for the Trhač (`look-and-sound.md` §3).
 * /creature spawn radmie|trhac · play <animation> · remove (GMs).
 */
final class Creatures {
    private Creatures() {}

    /**
     * name -> its animations (the model's own names; BetterModel plays idle and walk by itself), the base entity, the hitbox's scale
     * with the model on, the stand-in's scale without it, and the animations that sound the creature's own cry.
     */
    record Kind(String name, String label, List<String> animations, Class<? extends Mob> base, double hitbox, double standInScale,
                List<String> cries) {}

    static final Map<String, Kind> KINDS = Map.of(
            "radmie", new Kind("radmie", "Radmie", List.of("idle", "walk", "rise", "lunge", "hide", "spawn", "death"),
                    Silverfish.class, 2.0, 4.0, List.of("lunge")),
            // Marek 11-13: "a gigantic boar ... can move fast in straight direction and even just the mass alone makes it deadly";
            // the model 3.75 blocks at the shoulder, ~7.4 long [mine]: a hoglin (1.4 blocks) x 2.6 is its hitbox and its stand-in
            "trhac", new Kind("trhac", "Trhač", List.of("idle", "walk", "charge", "gore", "pollen", "spawn", "death"),
                    Hoglin.class, 2.6, 2.6, List.of("charge", "gore")));

    static final String TAG = "falloutak_creature";

    static boolean modelsOn() { return Bukkit.getPluginManager().isPluginEnabled("BetterModel"); }

    static String command(Player p, String[] a) {
        if (!p.hasPermission("falloutak.gm")) return "Only a GM can do that.";
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "spawn" -> {
                Kind k = KINDS.get(a.length > 1 ? a[1].toLowerCase(Locale.ROOT).replace("č", "c") : "radmie");
                if (k == null) return "Creatures: " + String.join(", ", KINDS.keySet());
                var hit = p.getWorld().rayTraceBlocks(p.getEyeLocation(), p.getEyeLocation().getDirection(), 40);   // where the GM looks
                Location at = hit == null || hit.getHitBlock() == null ? p.getLocation() : hit.getHitBlock().getLocation().add(0.5, 1, 0.5);
                at.setYaw(p.getLocation().getYaw() + 180);   // it faces the GM
                at.setPitch(0);
                spawn(k, at);
                return k.label() + " spawned" + (modelsOn() ? " with its model." : " as the stand-in (BetterModel is not on the server).");
            }
            case "play" -> {
                LivingEntity e = nearest(p);
                if (a.length < 2) return "/creature play <" + String.join("|", kindOf(e).animations()) + ">";
                if (e == null) return "No creature within 30 blocks.";
                String anim = a[1].toLowerCase(Locale.ROOT);
                if (!kindOf(e).animations().contains(anim)) return "The " + kindOf(e).label() + " has no " + anim + ": "
                        + String.join(", ", kindOf(e).animations());
                boolean shown = play(e, anim);
                return "Playing " + anim + "." + (shown ? "" : " (No model on the server: only its sound and effects.)");
            }
            case "remove" -> {
                LivingEntity e = nearest(p);
                if (e == null) return "No creature within 30 blocks.";
                if (modelsOn()) BmModels.close(e);
                e.remove();
                return "Removed.";
            }
            default -> {
                return "/creature spawn " + String.join("|", KINDS.keySet()) + " · play <animation> · remove   (models "
                        + (modelsOn() ? "ON: BetterModel" : "OFF: stand-ins") + ")";
            }
        }
    }

    /** The creature's kind by its tag (the Radmie for an untagged one). */
    static Kind kindOf(LivingEntity e) {
        if (e != null)
            for (String t : e.getScoreboardTags())
                if (t.startsWith(TAG + ":") && KINDS.containsKey(t.substring(TAG.length() + 1))) return KINDS.get(t.substring(TAG.length() + 1));
        return KINDS.get("radmie");
    }

    /** The base's scale: its hitbox under the model, or the stand-in's size without one. */
    static double scale(Kind k, boolean models) { return models ? k.hitbox() : k.standInScale(); }

    static LivingEntity spawn(Kind k, Location at) {
        Mob base = at.getWorld().spawn(at, k.base(), s -> {
            s.setAI(false);
            s.setSilent(true);
            s.setPersistent(true);
            Battle.cosmetic(() -> s.setRemoveWhenFarAway(false));   // (the mock server has no despawning)
            s.customName(Component.text(k.label(), NamedTextColor.RED));
            s.setCustomNameVisible(false);
            s.addScoreboardTag(TAG);
            s.addScoreboardTag(TAG + ":" + k.name());
            if (s instanceof Hoglin h) h.setImmuneToZombification(true);   // a hoglin outside the Nether turns into a zoglin
            var scale = s.getAttribute(Attribute.SCALE);
            if (scale != null) scale.setBaseValue(scale(k, modelsOn()));
        });
        if (modelsOn()) BmModels.attach(base, k.name());
        Sfx.at(at, k.name());
        return base;
    }

    /** Plays an animation: the model's (if BetterModel is on), the creature's cry, the Trhač's pollen. True if the model showed it. */
    static boolean play(LivingEntity e, String anim) {
        Kind k = kindOf(e);
        if (k.cries().contains(anim)) Sfx.at(e.getLocation(), k.name());
        if ("pollen".equals(anim)) pollen(e.getLocation());
        return modelsOn() && BmModels.animate(e, anim);
    }

    // ------------------------------------------------------------------ the Trhač's pollen
    /** The cloud (Marek 11-13: "releasing its pollen in large quantities to confuse enemies and obfuscate its location"): a ball of
     *  yellow dust POLLEN_RADIUS blocks round the creature, renewed every POLLEN_EVERY ticks for POLLEN_TICKS (10 s) [the sizes mine;
     *  what it does to aim and sight is Marek's call, not built]. */
    static final double POLLEN_RADIUS = 6;
    static final int POLLEN_TICKS = 200, POLLEN_EVERY = 5;
    static final Color POLLEN = Color.fromRGB(236, 206, 52);
    /** For the tests: the number of puffs released so far. */
    static int pollenPuffs;

    static void pollen(Location at) {
        Location c = at.clone().add(0, 2.5, 0);
        Runnable puff = () -> {
            pollenPuffs++;
            Battle.cosmetic(() -> {
                c.getWorld().spawnParticle(Particle.DUST, c, 90, POLLEN_RADIUS / 2, 1.6, POLLEN_RADIUS / 2, 0,
                        new Particle.DustOptions(POLLEN, 3.0f), true);
                c.getWorld().spawnParticle(Particle.FALLING_SPORE_BLOSSOM, c, 12, POLLEN_RADIUS / 2, 1.2, POLLEN_RADIUS / 2, 0);
            });
        };
        puff.run();
        if (Menus.pl == null || !Menus.pl.isEnabled()) return;
        for (int t = POLLEN_EVERY; t < POLLEN_TICKS; t += POLLEN_EVERY)
            Menus.pl.getServer().getScheduler().runTaskLater(Menus.pl, puff, t);
    }

    static LivingEntity nearest(Player p) {
        return p.getWorld().getNearbyEntities(p.getLocation(), 30, 30, 30, x -> x.getScoreboardTags().contains(TAG) && x instanceof LivingEntity)
                .stream().map(x -> (LivingEntity) x).min(Comparator.comparingDouble(x -> x.getLocation().distanceSquared(p.getLocation())))
                .orElse(null);
    }
}
