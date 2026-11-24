package cz.lollywood.falloutak;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * THE GM'S NPC TOOLS (Marek 11-24, verbatim: *"as a GM, I will need a few tools to use ingame to control NPCs: make some of them follow
 * players, make some hostile, make some not hostile, make them join player's faction."*): a figurant's SIDE (hostile, neutral, the
 * party's), FOLLOWING a player, JOINING a player's faction, and A FIGHT WHERE THEY STAND with every figurant near on its side. The side
 * and the leader are kept on the figurant's body (its data tags), so they last with it.
 * <p>In the fight [the forms mine, on the battle's own rules]: the hostile ones are the enemies; the neutral ones hold (as talkers do)
 * and nobody shoots at them until the party does, which turns their group hostile; the party's side are allies (09d round 5's: they
 * fight the others, never the party; attacked by the party they turn) and pay no kill XP. Each figure's HP is written back to its
 * statblock when the fight ends.
 */
final class Npcs {
    private Npcs() {}

    enum Side {
        HOSTILE("hostile", "fights the party", NamedTextColor.RED),
        NEUTRAL("neutral", "holds: nobody shoots at it until the party does, then its group fights", NamedTextColor.GOLD),
        PARTY("the party's", "fights with the party (an ally), never against it", NamedTextColor.GREEN);
        final String label, tip;
        final NamedTextColor color;
        Side(String label, String tip, NamedTextColor color) { this.label = label; this.tip = tip; this.color = color; }
    }

    static NamespacedKey SIDE, FOLLOW;
    /** The fight's reach round the GM: the players and the figurants within it [mine]. */
    static final double RADIUS = 32;
    /** A follower keeps within KEEP blocks; farther than JUMP (or in another world) it is put behind the leader [mine]. */
    static final double KEEP = 3.0, JUMP = 32.0, SPEED = 1.0;
    /** The rounds a figurant carries besides the loaded ones [mine, EXAMPLE]. */
    static final int RESERVE = 30;

    // ------------------------------------------------------------------ the rules (pure)
    enum Step { STAY, WALK, JUMP }

    /** What a follower does this moment, from its distance to the leader. */
    static Step step(double dist, boolean sameWorld) {
        if (!sameWorld || dist > JUMP) return Step.JUMP;
        return dist > KEEP ? Step.WALK : Step.STAY;
    }

    /** The fight's group of a side (figures of different groups fight each other; an ally never fights the party). */
    static int group(Side s) {
        return switch (s) { case HOSTILE -> 1; case PARTY -> 2; case NEUTRAL -> 3; };
    }

    /** A figurant's statblock as a figure of the fight: its HP now, Agility's AC with the armour's, DT and DR, Guns; a pistol. */
    static Battle.Target target(String name, Character c, Side side) {
        int[] a = c.attrs(false);
        Battle.Target t = new Battle.Target(name, c.hpMax(), Rules.ac(a) + c.armourAc(), c.dt(), c.dr(), a, (int) Math.round(c.skill("Guns")));
        t.hp = Math.max(1, Math.min(c.hp, c.hpMax()));
        t.byParty = false;
        t.reserve = RESERVE;
        t.foe = "figurant";
        t.group = group(side);
        t.ally = side == Side.PARTY;
        t.paid = side == Side.PARTY;          // the party's own: no kill XP for them
        t.talking = side == Side.NEUTRAL;     // holds (the battle's talkers' rule) until the party attacks it
        return t;
    }

    /** The fight's figures written back to their statblocks: HP, and dead if killed. */
    static void writeBack(Battle.Target t, Character c) {
        c.hp = Math.max(0, t.hp);
        if (t.dead) c.dead = true;
    }

    // ------------------------------------------------------------------ the body's tags
    static Side side(Entity e) {
        String s = e == null || SIDE == null ? null : e.getPersistentDataContainer().get(SIDE, PersistentDataType.STRING);
        try { return s == null ? Side.NEUTRAL : Side.valueOf(s); } catch (IllegalArgumentException ex) { return Side.NEUTRAL; }
    }

    static String setSide(Entity e, String name, Side s) {
        e.getPersistentDataContainer().set(SIDE, PersistentDataType.STRING, s.name());
        e.customName(Component.text(name, s.color));
        return name + " is now " + s.label + ": " + s.tip + ".";
    }

    static UUID leader(Entity e) {
        String s = e == null || FOLLOW == null ? null : e.getPersistentDataContainer().get(FOLLOW, PersistentDataType.STRING);
        try { return s == null ? null : UUID.fromString(s); } catch (IllegalArgumentException ex) { return null; }
    }

    static String follow(Entity e, String name, Player p) {
        e.getPersistentDataContainer().set(FOLLOW, PersistentDataType.STRING, p.getUniqueId().toString());
        return name + " follows " + p.getName() + ".";
    }

    static String unfollow(Entity e, String name) {
        e.getPersistentDataContainer().remove(FOLLOW);
        if (e instanceof Mob m) { Battle.cosmetic(() -> m.getPathfinder().stopPathfinding()); Battle.cosmetic(() -> m.setAI(false)); }
        return name + " stays where it stands.";
    }

    /** Joining a player's faction: the party's side, his faction (the group on his sheet) on its statblock, and it follows him. */
    static String join(FalloutakPlugin pl, Entity e, String name, Player p) {
        Character c = pl.npc(name);
        String g = pl.character(p).group == null ? "" : pl.character(p).group;
        setSide(e, name, Side.PARTY);
        follow(e, name, p);
        if (c != null && !g.isEmpty()) { c.group = g; pl.saveNpc(name); }
        return name + " joins " + p.getName() + (g.isEmpty() ? " (he has no faction on his sheet: only the side and the following)"
                : "'s faction, " + g) + ": on the party's side and following him.";
    }

    // ------------------------------------------------------------------ following (every half second)
    static void tick(FalloutakPlugin pl) {
        for (World w : Bukkit.getWorlds()) {
            for (Mob m : w.getEntitiesByClass(Mob.class)) {
                if (Items.npcName(m) == null) continue;
                UUID id = leader(m);
                if (id == null || pl.battle.figureInFight(m.getUniqueId())) continue;   // in a fight the battle moves it
                Player L = Bukkit.getPlayer(id);
                if (L == null) { Battle.cosmetic(() -> m.getPathfinder().stopPathfinding()); continue; }
                boolean same = L.getWorld().equals(m.getWorld());
                double d = same ? L.getLocation().distance(m.getLocation()) : Double.MAX_VALUE;
                switch (step(d, same)) {
                    case STAY -> Battle.cosmetic(() -> m.getPathfinder().stopPathfinding());
                    case WALK -> {
                        if (!m.hasAI()) Battle.cosmetic(() -> m.setAI(true));
                        Battle.cosmetic(() -> m.getPathfinder().moveTo(L.getLocation(), SPEED));
                    }
                    case JUMP -> {
                        Location behind = L.getLocation().clone().subtract(L.getLocation().getDirection().setY(0).normalize().multiply(2));
                        m.teleport(behind);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ the fight where they stand
    static String fightHere(FalloutakPlugin pl, Player gm, boolean theyFirst) {
        Location at = gm.getLocation();
        List<Player> party = new ArrayList<>();
        for (Player q : at.getWorld().getPlayers())
            if (q.getLocation().distance(at) <= RADIUS && q.getGameMode() != GameMode.CREATIVE && q.getGameMode() != GameMode.SPECTATOR) party.add(q);
        if (party.isEmpty()) party.add(gm);   // the GM alone, trying it
        for (Player q : party) if (pl.battle.inBattle(q)) return "! " + q.getName() + " is already in a fight";
        Battle.Fight f = new Battle.Fight("the enemies");
        f.demo = false;
        f.enemiesFirst = theyFirst;
        f.field = org.bukkit.util.BoundingBox.of(at, RADIUS + 16, 64, RADIUS + 16);
        int[] n = new int[3];
        for (Entity e : at.getWorld().getNearbyEntities(at, RADIUS, 16, RADIUS)) {
            String nm = Items.npcName(e);
            if (nm == null || !(e instanceof LivingEntity le)) continue;
            Character c = pl.npc(nm);
            if (c == null || c.dead || c.hp <= 0) continue;
            Side s = side(e);
            Battle.Target t = target(nm, c, s);
            if (le instanceof Mob m) { Battle.cosmetic(() -> m.getPathfinder().stopPathfinding()); Battle.cosmetic(() -> m.setAI(false)); }
            f.dummies.add(e.getUniqueId());
            f.targets.put(e.getUniqueId(), t);
            pl.battle.label(le, t);
            n[s.ordinal()]++;
        }
        if (n[Side.HOSTILE.ordinal()] == 0) {
            for (UUID u : f.dummies) { Entity e = Bukkit.getEntity(u); if (e != null) e.customName(Component.text(Items.npcName(e), side(e).color)); }
            return "! no hostile figurant within " + (int) RADIUS + " blocks: mark one hostile first (its GM menu: Side)";
        }
        for (Player q : party) pl.battle.join(q, pl.character(q), f, true);
        f.onEnd = () -> after(pl, f);
        f.started = true;
        String intro = "THE FIGHT HERE: " + n[0] + " hostile" + (n[2] > 0 ? ", " + n[2] + " on your side" : "") + (n[1] > 0 ? ", " + n[1]
                + " neutral (they hold until you attack them)" : "") + ". " + (theyFirst ? "They act first." : "Round 1: YOUR TURN, all of you at once.");
        for (Player q : party) q.sendMessage(Component.text(intro, NamedTextColor.AQUA));
        if (theyFirst) pl.battle.enemyPhase(f, () -> pl.battle.partyPhase(f));
        else for (Player q : party) { Battle.State s = pl.battle.state(q); if (s != null) pl.battle.show(q, s, pl.character(q), "round 1: your turn"); }
        return intro;
    }

    /** The fight is over: each figure's HP into its statblock, its name back in its side's colour. */
    static void after(FalloutakPlugin pl, Battle.Fight f) {
        for (Map.Entry<UUID, Battle.Target> en : f.targets.entrySet()) {
            Entity e = Bukkit.getEntity(en.getKey());
            String nm = e == null ? en.getValue().name : Items.npcName(e);
            Character c = nm == null ? null : pl.npc(nm);
            if (c == null) continue;
            writeBack(en.getValue(), c);
            pl.saveNpc(nm);
            if (e != null && !e.isDead()) e.customName(Component.text(nm, side(e).color));
        }
    }
}
