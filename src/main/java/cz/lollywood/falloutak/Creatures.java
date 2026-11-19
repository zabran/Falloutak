package cz.lollywood.falloutak;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Silverfish;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The game's own creatures in the world (Marek 11-19: *"Is there some way that you could create and / or animate the models?"* → yes,
 * the Radmie first). A creature is a plain base entity (AI off, our code moves it) wearing a Blockbench model through BetterModel
 * when that plugin is on the server (`models/<name>.bbmodel`, made by `tools/models/<name>.py`); without it, the stand-in: a
 * silverfish scaled up (`look-and-sound.md` §3). /creature spawn radmie · play <animation> · remove (GMs).
 */
final class Creatures {
    private Creatures() {}

    /** name -> its animations (the model's own names; BetterModel plays idle and walk by itself) and the stand-in's scale. */
    record Kind(String name, String label, List<String> animations, double standInScale) {}

    static final Map<String, Kind> KINDS = Map.of(
            "radmie", new Kind("radmie", "Radmie", List.of("idle", "walk", "lunge", "hide", "spawn", "death"), 4.0));

    static final String TAG = "falloutak_creature";

    static boolean modelsOn() { return Bukkit.getPluginManager().isPluginEnabled("BetterModel"); }

    static String command(Player p, String[] a) {
        if (!p.hasPermission("falloutak.gm")) return "Only a GM can do that.";
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "spawn" -> {
                Kind k = KINDS.get(a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "radmie");
                if (k == null) return "Creatures: " + String.join(", ", KINDS.keySet());
                var block = p.getTargetBlockExact(40);
                Location at = block == null ? p.getLocation() : block.getLocation().add(0.5, 1, 0.5);
                at.setYaw(p.getLocation().getYaw() + 180);   // it faces the GM
                at.setPitch(0);
                spawn(k, at);
                return k.label() + " spawned" + (modelsOn() ? " with its model." : " as the stand-in (BetterModel is not on the server).");
            }
            case "play" -> {
                if (a.length < 2) return "/creature play <" + String.join("|", KINDS.get("radmie").animations()) + ">";
                LivingEntity e = nearest(p);
                if (e == null) return "No creature within 30 blocks.";
                return play(e, a[1].toLowerCase(Locale.ROOT)) ? "Playing " + a[1] + "." : "No such animation (or no model on the server).";
            }
            case "remove" -> {
                LivingEntity e = nearest(p);
                if (e == null) return "No creature within 30 blocks.";
                if (modelsOn()) BmModels.close(e);
                e.remove();
                return "Removed.";
            }
            default -> {
                return "/creature spawn radmie · play <animation> · remove   (models " + (modelsOn() ? "ON: BetterModel" : "OFF: stand-ins") + ")";
            }
        }
    }

    static LivingEntity spawn(Kind k, Location at) {
        Silverfish base = at.getWorld().spawn(at, Silverfish.class, s -> {
            s.setAI(false);
            s.setSilent(true);
            s.setPersistent(true);
            s.setRemoveWhenFarAway(false);
            s.customName(Component.text(k.label(), NamedTextColor.RED));
            s.setCustomNameVisible(false);
            s.addScoreboardTag(TAG);
            s.addScoreboardTag(TAG + ":" + k.name());
            var scale = s.getAttribute(Attribute.SCALE);
            if (scale != null) scale.setBaseValue(modelsOn() ? 2.0 : k.standInScale());   // the hitbox (the model draws itself)
        });
        if (modelsOn()) BmModels.attach(base, k.name());
        Sfx.at(at, "radmie");
        return base;
    }

    static boolean play(LivingEntity e, String anim) {
        if ("lunge".equals(anim)) Sfx.at(e.getLocation(), "radmie");
        return modelsOn() && BmModels.animate(e, anim);
    }

    static LivingEntity nearest(Player p) {
        return p.getWorld().getNearbyEntities(p.getLocation(), 30, 30, 30, x -> x.getScoreboardTags().contains(TAG) && x instanceof LivingEntity)
                .stream().map(x -> (LivingEntity) x).min(Comparator.comparingDouble(x -> x.getLocation().distanceSquared(p.getLocation())))
                .orElse(null);
    }
}
