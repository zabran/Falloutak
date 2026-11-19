package cz.lollywood.falloutak;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.animation.AnimationModifier;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.tracker.EntityTracker;
import org.bukkit.entity.Entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The only class that touches BetterModel's API (2.2.0, compileOnly: the last release for Java 21, which our servers run; 3.x needs Java 25): loaded only when the plugin is on the server
 * ({@link Creatures#modelsOn()}), so the rest of Falloutak runs without it. A model = `plugins/BetterModel/models/<name>.bbmodel`.
 */
final class BmModels {
    private BmModels() {}

    private static final Map<UUID, EntityTracker> TRACKERS = new ConcurrentHashMap<>();

    static boolean attach(Entity base, String model) {
        EntityTracker t = BetterModel.model(model).map(r -> r.create(BukkitAdapter.adapt(base))).orElse(null);
        if (t == null) return false;
        TRACKERS.put(base.getUniqueId(), t);
        return true;
    }

    static boolean animate(Entity base, String anim) {
        EntityTracker t = TRACKERS.get(base.getUniqueId());
        if (t == null && attach(base, base.getScoreboardTags().stream().filter(s -> s.startsWith(Creatures.TAG + ":"))
                .map(s -> s.substring(Creatures.TAG.length() + 1)).findFirst().orElse("radmie"))) t = TRACKERS.get(base.getUniqueId());
        if (t == null) return false;
        return t.animate(anim, AnimationModifier.DEFAULT);   // the file's own loop mode: loop, once or hold
    }

    static void close(Entity base) {
        EntityTracker t = TRACKERS.remove(base.getUniqueId());
        if (t != null) t.close();
    }
}
