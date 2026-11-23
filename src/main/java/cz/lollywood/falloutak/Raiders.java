package cz.lollywood.falloutak;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * The raiders' turn (Fallouťák part 5, after Marek's round 2 of 11-14: *"please continue with both the TownBuilder and
 * Fallouťák"*, the next step in Fallout/NOW.md being NPC turns). After the player ends his turn every raider still able to act takes
 * his own, one after another in Sequence order (2 x PE, Fallout 2), shown step by step: knocked down he gets up (3 AP, Fallout 2's
 * `_combat_standup`), a dropped gun is picked up (2 AP), an empty one reloaded (2 AP); then, as Fallout 2's AI does at its
 * simplest, he shoots when he can (in range and a clear line) and otherwise walks toward a spot from which he can, keeping 5 AP for
 * the shot if the spot is near enough; after walking he steadies (1 AP) when he still has a shot left; AP he cannot use for a
 * shot crouches him (2 AP) [the crouch mine, Tactics-like]. His shots are the player's own rules (Combat), with the player's AC
 * (Agility + stance) and the light at the player. [EXAMPLE: the raider fires the workbook's 10mm Pistol with skill 50, the Battle
 * tab's two shooters' value; the tab gives him a chainsaw and no skill.]
 */
final class Raiders {
    private final Battle b;
    Raiders(Battle b) { this.b = b; }

    static final int SHOT_AP = Battle.SMG.modes.get(0).ap();
    /** Ticks between two steps of the enemy turn, so it can be followed. */
    static final long TICKS = 6;
    static final int SEARCH = 40;

    /** Runs every raider's turn, then {@code done}; stops early if the player dies or the demo ends. */
    void run(Player p, Battle.State s, Character c, Runnable done) {
        List<UUID> order = new ArrayList<>(s.dummies);
        order.sort(Comparator.comparingInt((UUID u) -> { Battle.Target t = s.targets.get(u); return t == null ? 0 : -2 * t.a[Rules.P]; }));
        Iterator<UUID> it = order.iterator();
        s.pending.clear();
        s.task = b.pl.getServer().getScheduler().runTaskTimer(b.pl, () -> {
            if (!p.isOnline() || !b.inBattle(p)) { s.task.cancel(); return; }
            if (s.pending.isEmpty()) {
                if (!it.hasNext()) { s.task.cancel(); s.task = null; done.run(); return; }
                plan(p, s, c, it.next());
                return;
            }
            s.pending.poll().run();
        }, TICKS, TICKS);
    }

    private void say(Player p, String text) { p.sendMessage(Component.text(text, NamedTextColor.RED)); }

    /** Works out one raider's turn and queues its steps. */
    private void plan(Player p, Battle.State s, Character c, UUID u) {
        Battle.Target t = s.targets.get(u);
        Entity e = b.pl.getServer().getEntity(u);
        if (t == null || t.dead || !(e instanceof LivingEntity le) || le.isDead()) return;
        Deque<Runnable> q = s.pending;
        if (t.dying()) { q.add(() -> say(p, t.name + " lies dying.")); return; }
        t.ap = Rules.ap(t.a) + b.pl.zones.penaltyAt(le.getEyeLocation())[2];   // riot gas AP −2 (03b §3c; a raider has no mask)
        t.ap -= t.apLoss;   // the lightning's or the cold's AP lost last round (09c §2)
        t.apLoss = 0;
        t.moved = false;
        t.steadied = false;
        t.held = false;
        t.frozen = false;
        // the anomaly he stands in acts on his body each round, whatever he does (09c §2; round 10)
        Zones.Zone az = b.pl.anomalies.at(le.getLocation().add(0, 0.5, 0));
        String inZone = az == null ? "" : az.id;
        if (az != null) {
            Anomalies.Kind k = Anomalies.KINDS.get(az.anomaly);
            boolean centre = az.centre(le.getWorld()).distanceSquared(le.getLocation().add(0, 0.5, 0)) <= 2.25;
            String what;
            if (k.recharge() > 0 && b.pl.zones.round < az.spentUntil) what = "the " + k.name() + " is spent: nothing this round";
            else {
                if (k.recharge() > 0) az.spentUntil = b.pl.zones.round + k.recharge() + 1;
                what = hit(le, t, az, centre);
            }
            q.add(() -> say(p, t.name + " in " + k.name() + ": " + what + "."));
            if (t.dead || t.dying() || t.frozen) return;
        }
        if (t.lostTurn) { t.lostTurn = false; q.add(() -> say(p, t.name + " loses this turn (a critical failure).")); return; }
        if (t.out) {
            if (--t.outRounds < 0) { t.out = false; q.add(() -> { say(p, t.name + " comes to (the turn spent; he still lies)."); b.label(le, t); }); }
            else q.add(() -> say(p, t.name + " is out cold."));
            return;
        }
        if (t.down) {   // Fallout 2: standing up costs 3 AP at the start of the turn
            t.ap -= 3;
            t.down = false;
            q.add(() -> { say(p, t.name + " gets up (3 AP)."); b.label(le, t); });
        }
        // the mirage and the echo: his head (round 10: "Raiders inside could get insanity effect")
        if (az != null && (az.anomaly.equals("mirage") || az.anomaly.equals("echo")) && episode(p, s, c, le, t, Anomalies.KINDS.get(az.anomaly).name())) return;
        if (t.dropped) {
            if (t.ap < 2) { q.add(() -> say(p, t.name + " reaches for his dropped gun.")); return; }
            t.ap -= 2;
            t.dropped = false;
            q.add(() -> say(p, t.name + " picks his gun up (2 AP)."));
        }
        if (t.broken) { q.add(() -> say(p, t.name + "'s gun is destroyed: he has nothing to fight with.")); return; }
        if (Combat.broken(t.gunHealth)) { q.add(() -> say(p, t.name + "'s gun is broken: he has nothing to fight with.")); return; }
        while (t.jammed && t.ap >= SHOT_AP) {   // 05 §9 (round 11): clearing his jam, JA2's check with his skill for its Mechanical [EXAMPLE]
            t.ap -= SHOT_AP;
            int d = 1 + b.rnd.nextInt(100), need = Combat.clearJam(t.skill);
            if (d <= need) t.jammed = false;
            boolean ok = !t.jammed;
            q.add(() -> say(p, t.name + (ok ? " clears his jammed gun" : " fails to clear his jammed gun") + " (d100 " + d + (ok ? " ≤ " : " > ") + need + ", " + SHOT_AP + " AP)."));
        }
        if (t.jammed) { q.add(() -> say(p, t.name + "'s gun is still jammed.")); return; }
        if (t.ammo <= 0 && t.ap >= 2) { t.ap -= 2; t.ammo = Battle.SMG.magazine; q.add(() -> { say(p, t.name + " reloads (2 AP)."); Entity re = b.pl.getServer().getEntity(u); if (re != null) Sfx.at(re.getLocation(), "pistol.reload"); }); }
        if (b.pl.anomalies.hidden(p.getLocation(), le.getLocation())) {   // the player inside a blind spot: unseen (09c §2)
            q.add(() -> say(p, t.name + " cannot see you (a blind spot hides you): he waits."));
            return;
        }

        Location at = le.getLocation();
        World w = at.getWorld();
        Path.P start = new Path.P(at.getBlockX(), at.getBlockY(), at.getBlockZ());
        double eyeH = le.getEyeHeight();
        if (t.held) q.add(() -> say(p, t.name + " is held where he stands: he cannot move."));
        else if (!canShoot(w, start, eyeH, p)) {
            Set<Path.P> taken = taken(s, u, p);
            notice(le, t);   // the tells he sees: he walks round those, into the others
            Path.World world = new Path.World() {
                public boolean canStand(int x, int y, int z) {
                    Block f = w.getBlockAt(x, y, z);
                    return f.isPassable() && f.getRelative(0, 1, 0).isPassable() && f.getRelative(0, -1, 0).getType().isSolid()
                            && !taken.contains(new Path.P(x, y, z)) && !avoids(t, inZone, w, x, y, z);
                }
                public boolean free(int x, int y, int z) { return w.getBlockAt(x, y, z).isPassable(); }
            };
            List<Path.P> path = Path.find(world, start, (x, y, z) -> canShoot(w, new Path.P(x, y, z), eyeH, p), SEARCH);
            if (path == null) { q.add(() -> say(p, t.name + " finds no way to get a shot at you.")); return; }
            if (t.stance == 1) { t.ap -= 2; t.stance = 0; q.add(() -> say(p, t.name + " stands up to move (2 AP).")); }
            double per = Rules.stepAp(t.stance, t.legCrippled ? 1 : 0, false);
            int steps = (int) Math.floor(t.ap / per + 1e-9);
            if (Math.ceil(path.size() * per - 1e-9) <= t.ap - SHOT_AP) steps = path.size();
            steps = Math.max(0, Math.min(steps, path.size()));
            // an anomaly he has not noticed on his way: he walks into it and his move ends there (09c §1.7, round 10)
            Zones.Zone into = null;
            for (int i = 0; i < steps && into == null; i++) {
                Path.P cell = path.get(i);
                Zones.Zone z2 = b.pl.anomalies.at(new Location(w, cell.x() + 0.5, cell.y() + 0.5, cell.z() + 0.5));
                if (z2 != null && !z2.id.equals(inZone)) { into = z2; steps = i + 1; }
            }
            for (int i = 0; i < steps; i++) {
                Path.P to = path.get(i);
                q.add(() -> moveTo(le, to, p));
            }
            if (steps > 0) {
                t.ap -= (int) Math.ceil(steps * per - 1e-9);
                t.moved = true;
                final int n = steps;
                final boolean walkedIn = into != null;
                q.add(() -> say(p, t.name + " moves " + n + " block(s)" + (n < path.size() && !walkedIn ? ", toward a shot at you" : "") + "."));
            }
            if (into != null) {
                final Zones.Zone ez = into;
                final Path.P back = steps >= 2 ? path.get(steps - 2) : start;
                q.add(() -> say(p, t.name + " walks into an anomaly: " + enter(le, t, ez, back, p) + "."));
            }
        }
        if (t.moved && t.ap >= SHOT_AP + 1) { t.ap -= 1; t.steadied = true; q.add(() -> say(p, t.name + " steadies himself (1 AP).")); }
        int shots = Math.min(t.ap / SHOT_AP, t.ammo);
        for (int i = 0; i < shots; i++) q.add(() -> shoot(p, s, c, le, t));
        t.ap -= shots * SHOT_AP;
        if (shots > 0 && t.ap >= 2 && t.stance == 0) {
            t.ap -= 2;
            q.add(() -> { if (!t.dead && !t.down && b.inBattle(p)) { t.stance = 1; say(p, t.name + " crouches (2 AP: AC +2, his shots +5)."); b.label(le, t); } });
        }
    }

    // ------------------------------------------------------------------ the anomalies (09c; round 10)
    /*
     * Marek 11-23, `09b` round 10, verbatim: *"raiders walking into anomalies - it should happen and they should get affected by the
     * anomaly."* · *"Raiders inside could get insanity effect."* A raider sees an anomaly's tell as a person would in a fight (09c §1.2,
     * §1.7): d100 ≤ 10 × his Perception, once an anomaly a fight; the blind spot never. He walks round the ones he noticed and into the
     * others, and his move ends there; walking in, its first hit (Lucky Escape 2 × Luck % jerks him back); at the start of each of his
     * turns inside, its round. His body has no sheet: Normal damage through his DT and DR, the rest whole; no Pain, Hypothermia or thirst
     * [the raider's forms mine, EXAMPLE; the dice 09c §2's].
     */
    /** The tells he sees, among the anomalies within his search. */
    void notice(LivingEntity le, Battle.Target t) {
        Location me = le.getLocation();
        for (Zones.Zone z : b.pl.zones.list) {
            if (z.anomaly.isEmpty() || !z.world.equals(me.getWorld().getName()) || t.noticed.contains(z.id) || t.missed.contains(z.id)) continue;
            if (z.centre(me.getWorld()).distanceSquared(me) > SEARCH * SEARCH) continue;
            boolean saw = !z.anomaly.equals("blind") && 1 + b.rnd.nextInt(100) <= 10 * t.a[Rules.P];
            (saw ? t.noticed : t.missed).add(z.id);
        }
    }

    /** A cell inside an anomaly he noticed (and is not already in): he will not step there. */
    boolean avoids(Battle.Target t, String inZone, World w, int x, int y, int z) {
        if (t.noticed.isEmpty()) return false;
        Zones.Zone a = b.pl.anomalies.at(new Location(w, x + 0.5, y + 0.5, z + 0.5));
        return a != null && !a.id.equals(inZone) && t.noticed.contains(a.id);
    }

    /** Walking in (09c §1.5): Lucky Escape at 2 × Luck % (he jerks back to where he came from), else its first hit. */
    String enter(LivingEntity le, Battle.Target t, Zones.Zone z, Path.P back, Player p) {
        Anomalies.Kind k = Anomalies.KINDS.get(z.anomaly);
        if (t.dead) return k.name();
        int d = 1 + b.rnd.nextInt(100), luck = t.a[Rules.L];
        if ((k.hurts() || k.recharge() > 0) && d <= 2 * luck) {
            moveTo(le, back, p);
            return k.name() + ", LUCKY ESCAPE (d100 " + d + " ≤ 2 × Luck " + luck + "): he jerks back out of it";
        }
        boolean centre = z.centre(le.getWorld()).distanceSquared(le.getLocation().add(0, 0.5, 0)) <= 2.25;
        if (k.recharge() > 0) {
            if (b.pl.zones.round < z.spentUntil) return k.name() + " (spent: nothing happens)";
            z.spentUntil = b.pl.zones.round + k.recharge() + 1;
        }
        return k.name() + ": " + hit(le, t, z, centre);
    }

    private int harm(LivingEntity le, Battle.Target t, int raw, boolean normal, StringBuilder log) {
        int dmg = normal ? Combat.damage(raw, 1, 0, 0, t.dt, t.dr, false) : raw;
        b.hurt(le, t, dmg, Combat.critEffects(Rules.Part.TORSO, 0, t.a), false, 0, log);
        return dmg;
    }

    /** What an anomaly does to a raider's body, once (09c §2's dice). Returns what happened. */
    String hit(LivingEntity le, Battle.Target t, Zones.Zone z, boolean centre) {
        Random rnd = b.rnd;
        StringBuilder log = new StringBuilder();
        Location mid = z.centre(le.getWorld());
        String what = switch (z.anomaly) {
            case "throw" -> {
                int d = harm(le, t, Anomalies.dice(rnd, 3, 10), true, log);
                if (!t.dead) { t.down = true; t.stance = 0; b.knockback(mid, le, Anomalies.dice(rnd, 2, 6), log); b.label(le, t); }
                yield "the throw hurls him, " + d + " HP, knocked down";
            }
            case "pull" -> {
                if (!centre) {
                    Vector to = mid.toVector().subtract(le.getLocation().toVector()).setY(0);
                    if (to.lengthSquared() > 1e-6) {
                        Location l = le.getLocation().add(to.normalize());
                        if (l.getBlock().isPassable() && l.clone().add(0, 1, 0).getBlock().isPassable()) le.teleport(l);
                    }
                    int d = 1 + rnd.nextInt(100), need = 10 * t.a[Rules.S];
                    t.held = d > need;
                    yield "the pull drags him a block toward its heart; " + (t.held ? "held (Strength, d100 " + d + " > " + need + ")" : "he tears free (Strength, d100 " + d + " ≤ " + need + ")");
                }
                t.held = true;
                yield "crushed at the pull's heart, " + harm(le, t, Anomalies.dice(rnd, 2, 10), true, log) + " HP";
            }
            case "lightning" -> {
                Battle.cosmetic(() -> le.getWorld().strikeLightningEffect(le.getLocation()));
                t.apLoss = 4;
                yield "lightning, " + harm(le, t, Anomalies.dice(rnd, 4, 8), false, log) + " HP, AP −4 next turn";
            }
            case "heat" -> "the heat, " + harm(le, t, Anomalies.dice(rnd, 2, 10), false, log) + " HP";
            case "acid" -> "the acid fog on his skin, " + harm(le, t, 2 * Anomalies.dice(rnd, 1, 6), false, log) + " HP";
            case "time" -> {
                t.frozen = true;
                if (t.bleeding > 0) { t.hp = -t.a[Rules.E] - 1; t.dead = true; b.label(le, t); yield "six hours pass for his body in a round: he bleeds out, dead"; }
                yield "six hours pass for his body in a round: he stands frozen";
            }
            case "cold" -> { t.apLoss = Math.max(t.apLoss, 2); yield "the cold numbs him, AP −2 next turn"; }
            case "growth" -> {
                int d = 1 + rnd.nextInt(100);
                if (d <= t.skill) yield "he tears free of the growth (Athletics " + t.skill + ", d100 " + d + ")";
                t.held = true;
                yield "held by the growth (d100 " + d + "): the thorns, " + harm(le, t, Anomalies.dice(rnd, 1, 6), true, log) + " HP";
            }
            case "lift" -> {
                t.held = true;
                Battle.cosmetic(() -> le.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 40, 0, false, false)));
                yield "the lift raises him, he hangs and cannot move";
            }
            case "magnet" -> "the magnet tugs at his gun: −30 to hit from inside";
            case "blind" -> "blind inside, unseen from outside";
            case "radio" -> "his radio wakes and blares";
            case "echo" -> "day D's voices all round him";
            case "mirage" -> "figures move round him that are not there";
            default -> "";
        };
        return what + (log.length() == 0 ? "" : " (" + log.toString().trim() + ")");
    }

    /**
     * His head in the mirage or the echo (round 10: *"Raiders inside could get insanity effect"*): at the start of his turn d100 ≤ 10 ×
     * Perception − 20 keeps it (the PCs' −20); else one of the glowing one's episodes (03b §7b), for this turn [the use mine, EXAMPLE].
     * Returns true when his turn is spent.
     */
    private boolean episode(Player p, Battle.State s, Character c, LivingEntity le, Battle.Target t, String where) {
        Deque<Runnable> q = s.pending;
        int need = 10 * t.a[Rules.P] - 20, d = 1 + b.rnd.nextInt(100);
        if (d <= need) { q.add(() -> say(p, t.name + " keeps his head in " + where + " (Perception, d100 " + d + " ≤ " + need + ").")); return false; }
        String head = t.name + " loses his head in " + where + " (Perception, d100 " + d + " > " + need + "): ";
        switch (b.rnd.nextInt(6)) {
            case 0 -> q.add(() -> say(p, head + "he freezes, staring at nothing (his turn lost)."));
            case 1 -> {   // flees: up to 4 blocks straight away from the player
                Vector away = le.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
                if (away.lengthSquared() < 1e-6) away = new Vector(1, 0, 0);
                away.normalize();
                Location from = le.getLocation();
                int n = 0;
                for (int i = 1; i <= 4; i++) {
                    Location l = from.clone().add(away.clone().multiply(i));
                    Block f = l.getBlock();
                    if (!f.isPassable() || !f.getRelative(0, 1, 0).isPassable() || !f.getRelative(0, -1, 0).getType().isSolid()) break;
                    Path.P to = new Path.P(l.getBlockX(), l.getBlockY(), l.getBlockZ());
                    q.add(() -> moveTo(le, to, p));
                    n++;
                }
                final int fled = n;
                q.add(() -> say(p, head + "he flees from what he sees" + (fled > 0 ? ", " + fled + " block(s)" : ", but has nowhere to go") + "."));
            }
            case 2 -> q.add(() -> turnOn(p, s, c, le, t, head));
            case 3 -> q.add(() -> say(p, head + "he shouts and babbles at the voices (his turn lost; everyone hears where he is)."));
            case 4 -> {
                int n = Math.min(t.ap / SHOT_AP, t.ammo);
                t.ammo -= n;
                q.add(() -> { say(p, head + (n > 0 ? "he empties " + n + " shot(s) into figures that are not there." : "he aims at figures that are not there.")); if (n > 0) Sfx.at(le.getLocation(), "pistol.burst"); });
            }
            default -> {
                t.out = true;
                t.outRounds = 0;
                q.add(() -> { say(p, head + "a vision takes him: he drops, out cold (the next turn spent coming to)."); b.label(le, t); });
            }
        }
        return true;
    }

    /** The episode's "attacks the nearest, friend or foe": the nearest other raider he can shoot at, if nearer than the player. */
    private void turnOn(Player p, Battle.State s, Character c, LivingEntity le, Battle.Target t, String head) {
        LivingEntity best = null;
        Battle.Target bt = null;
        double bd = le.getLocation().distanceSquared(p.getLocation());
        for (UUID o : s.dummies) {
            Battle.Target ot = s.targets.get(o);
            Entity oe = b.pl.getServer().getEntity(o);
            if (ot == null || ot == t || ot.dead || !(oe instanceof LivingEntity ole)) continue;
            double dd = le.getLocation().distanceSquared(ole.getLocation());
            if (dd < bd && dd <= Battle.SMG.range * Battle.SMG.range) { bd = dd; best = ole; bt = ot; }
        }
        if (best == null) { say(p, head + "he turns on the nearest: you."); shoot(p, s, c, le, t); return; }
        if (t.ammo <= 0) { say(p, head + "he turns on " + bt.name + ", but his gun is empty."); return; }
        t.ammo--;
        Sfx.at(le.getLocation(), "pistol.single");
        int d = 1 + b.rnd.nextInt(100);
        StringBuilder log = new StringBuilder(head + "he turns his gun on " + bt.name + " (d100 " + d + " vs his " + t.skill + "): ");
        if (d > t.skill) log.append("a miss.");
        else {
            Battle.Weapon w = Battle.SMG;
            int dmg = Combat.damage(w.minDmg + b.rnd.nextInt(w.maxDmg - w.minDmg + 1), 1, w.ammoDamage, w.ammoPenetration, bt.dt, bt.dr, false);
            log.append(dmg).append(" HP.");
            b.hurt(best, bt, dmg, Combat.critEffects(Rules.Part.TORSO, 0, bt.a), false, 0, log);
        }
        say(p, log.toString());
    }

    /** Where the others stand (the player, the other raiders): nobody walks into them. */
    private Set<Path.P> taken(Battle.State s, UUID me, Player p) {
        Set<Path.P> l = new HashSet<>();
        Location pl = p.getLocation();
        l.add(new Path.P(pl.getBlockX(), pl.getBlockY(), pl.getBlockZ()));
        for (UUID o : s.dummies) {
            if (o.equals(me)) continue;
            Entity e = b.pl.getServer().getEntity(o);
            if (e != null) { Location x = e.getLocation(); l.add(new Path.P(x.getBlockX(), x.getBlockY(), x.getBlockZ())); }
        }
        return l;
    }

    /** From this spot: the player within the gun's range and no block between the two eyes. */
    private static boolean canShoot(World w, Path.P at, double eyeH, Player p) {
        Location eye = new Location(w, at.x() + 0.5, at.y() + eyeH, at.z() + 0.5);
        Vector to = p.getEyeLocation().toVector().subtract(eye.toVector());
        double d = to.length();
        if (d > Battle.SMG.range || d < 1e-6) return d < 1e-6;
        RayTraceResult r = w.rayTraceBlocks(eye, to.normalize(), d, FluidCollisionMode.NEVER, true);
        return r == null;
    }

    private void moveTo(LivingEntity le, Path.P to, Player p) {
        Location l = new Location(le.getWorld(), to.x() + 0.5, to.y(), to.z() + 0.5);
        l.setDirection(p.getLocation().toVector().subtract(l.toVector()).setY(0));
        le.teleport(l);
        Sfx.at(l, "step");
    }

    /** Fallout 2: −10 for each creature in the line of fire. */
    private int inTheWay(Location eye, LivingEntity target, Entity shooter) {
        Vector to = target.getEyeLocation().toVector().subtract(eye.toVector());
        double len = to.length();
        if (len < 1e-6) return 0;
        Vector dir = to.clone().normalize();
        int n = 0;
        for (Entity e : eye.getWorld().getNearbyEntities(eye.clone().add(to.clone().multiply(0.5)), len / 2 + 1, len / 2 + 1, len / 2 + 1)) {
            if (e.equals(shooter) || e.equals(target) || !(e instanceof LivingEntity le) || le.isDead() || Anomalies.figure(e)) continue;
            if (e.getBoundingBox().rayTrace(eye.toVector(), dir, len - 0.3) != null) n++;
        }
        return n;
    }

    /** One shot at the player, by the rules the player shoots by. */
    private void shoot(Player p, Battle.State s, Character c, LivingEntity le, Battle.Target t) {
        if (!b.inBattle(p) || t.dead || t.dying() || t.down || t.out || t.frozen || t.jammed || t.dropped || t.broken || Combat.broken(t.gunHealth) || t.ammo <= 0) return;
        Location at = le.getLocation();
        if (!canShoot(le.getWorld(), new Path.P(at.getBlockX(), at.getBlockY(), at.getBlockZ()), le.getEyeHeight(), p)
                || b.pl.anomalies.hidden(p.getLocation(), at)) {
            say(p, t.name + " has lost sight of you.");
            return;
        }
        Location face = at.clone();
        face.setDirection(p.getLocation().toVector().subtract(at.toVector()).setY(0));
        le.teleport(face);
        Location eye = le.getEyeLocation();
        Battle.Weapon w = Battle.SMG;
        Combat.Shot sh = new Combat.Shot();
        sh.skill = t.skill;
        sh.blocks = Math.max(1, (int) Math.round(eye.distance(p.getEyeLocation())));
        int[] gas = b.pl.zones.penaltyAt(eye);   // a gas zone around the raider (03b §3c): P and to hit
        sh.perception = t.a[Rules.P] + gas[0];
        sh.strength = t.a[Rules.S];
        sh.minStrength = w.minStrength;
        sh.weaponPerk = w.perk;
        sh.blind = t.blind;
        if (gas[1] != 0) { Zones.Zone z = b.pl.zones.at(eye); sh.extra.put(z != null ? z.gas : "gas", gas[1]); }
        Zones.Zone az = b.pl.anomalies.at(at.clone().add(0, 0.5, 0));   // the anomaly he shoots from (09c §2, round 10)
        if (az != null) switch (az.anomaly) {
            case "magnet" -> sh.extra.put("the magnet", -30);
            case "blind" -> sh.blind = true;
            case "echo" -> sh.skill -= 10;
            default -> { }
        }
        // the jam (05 §9, round 11: raiders too, Marek *"yes, 100%"*): his used gun's condition, the magnet's 1-2 of d6
        if (Combat.jamAt(t.gunHealth / w.maxHealth, 1, b.d100, az != null && az.anomaly.equals("magnet") ? 2 : 0) >= 0) {
            t.jammed = true;
            t.ammo--;
            t.gunHealth = Math.max(1, t.gunHealth - Combat.WEAR_PER_ROUND);
            Sfx.at(eye, "pistol.empty");
            say(p, t.name + "'s gun JAMS (condition " + Math.round(t.gunHealth / w.maxHealth * 100) + " %): the round sticks; he will try to clear it.");
            b.label(le, t);
            return;
        }
        Battle.veil(b.pl.zones, eye, p.getEyeLocation(), sh, false);   // the Trhač's pollen hides the player from him too
        sh.part = Rules.Part.TORSO;
        sh.lightLevel = p.getEyeLocation().getBlock().getLightLevel();
        sh.targetAc = Rules.ac(c.attrs(false)) + Rules.STANCE_AC[s.stance()] + c.armourAc();   // + the armour's AC (05c)
        sh.targetProne = s.stance() == 2;
        sh.obstacles = inTheWay(eye, p, le);
        sh.huge = c.tpl().huge();   // the Ent (03b §2d): +15 to hit it, obstacles x 2
        sh.stance = t.stance;
        sh.movePenalty = t.moved && !t.steadied ? -10 : 0;
        sh.range = w.range;
        Combat.ToHit th = Combat.toHit(sh);
        Combat.Roll r = Combat.roll(th.chance(), Rules.critChance(t.a, 0), 0, t.a[Rules.L], 0, b.jinxed(c), b.d100);
        t.ammo--;
        double cf = Combat.conditionDamage(t.gunHealth / w.maxHealth);
        t.gunHealth = Math.max(1, t.gunHealth - Combat.WEAR_PER_ROUND);
        Sfx.at(eye, "pistol.single");
        Vector dir = p.getEyeLocation().toVector().subtract(eye.toVector());
        double len = dir.length();
        dir.normalize();
        for (double k = 0.6; k < len; k += 0.5) le.getWorld().spawnParticle(Particle.CRIT, eye.clone().add(dir.clone().multiply(k)), 1, 0, 0, 0, 0);
        StringBuilder log = new StringBuilder(t.name).append(" fires at you, ").append(sh.blocks).append(" blocks (5 AP). To hit ").append(th.chance()).append(" % =");
        th.terms().forEach((k, v) -> log.append(" ").append(k.equals("skill") ? "Guns " + v : Battle.sign(v) + " " + k));
        log.append(". Rolled ").append(r.d100()).append(r.d100() >= 100 ? " (a 100 always misses)" : "").append(": ");
        String result;
        if (r.critFail()) {
            Combat.Failure f = Combat.critFailure(r.degree(), Combat.fireWeapon(w.damageType));
            String what = f.names().isEmpty() ? "nothing worse than a miss" : String.join(", ", f.names());
            log.append("HIS CRITICAL FAILURE (degree ").append(r.degree()).append("): ").append(what).append(".");
            failure(p, s, c, le, t, f, log);
            Sfx.at(le.getLocation(), "crit.fail");
            Narrator.fumble(le.getLocation(), f, false);
            result = t.name + ": critical failure";
        } else if (!r.hit()) {
            log.append("MISS.");
            result = t.name + " missed you";
            Sfx.at(p.getLocation(), "bullet.miss");
        } else {
            int deg = r.crit() ? r.degree() : 0;
            Combat.CritEffects fx = Combat.critEffects(Rules.Part.TORSO, deg, c.attrs(false));
            double mult = Combat.critMultiplier(Rules.Part.TORSO, deg);
            double base = (w.minDmg + b.rnd.nextInt(w.maxDmg - w.minDmg + 1)) * cf;
            // the piece over the torso (an unaimed shot, Fallout 2), Normal damage; it wears when the hit beats its DT (05c)
            int dmg = Combat.damage(base, mult, w.ammoDamage, w.ammoPenetration, c.dtAt(Rules.Part.TORSO, "Normal"), c.drAt(Rules.Part.TORSO, "Normal"), fx.bypass());
            String wear = c.wearArmour(Rules.Part.TORSO, Combat.damage(base, mult, w.ammoDamage, w.ammoPenetration, 0, 0, false));
            if (!wear.isEmpty()) Items.writeArmour(p, c);
            log.append(r.crit() ? "CRITICAL HIT, degree " + deg + " (x" + SheetGui.f(mult) + ")" : "HIT").append(", ").append(dmg)
                    .append(" HP (").append(w.minDmg).append("-").append(w.maxDmg).append(", ").append(w.ammo).append("; your DT ").append(c.dtAt(Rules.Part.TORSO, "Normal")).append(", DR ").append(Math.round(c.drAt(Rules.Part.TORSO, "Normal") * 100))
                    .append(" %").append(c.armourAt(Rules.Part.TORSO) == null ? ", no armour" : ", " + c.armourAt(Rules.Part.TORSO).set()).append(").").append(wear);
            if (!fx.names().isEmpty()) log.append(" The crit: ").append(String.join(", ", fx.names())).append(".");
            result = (r.crit() ? "CRIT on you, " : "hit, ") + dmg + " HP";
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 1f);
            b.hitPlayer(p, s, c, le.getLocation(), dmg, fx, r.crit(), deg, log);
            if (r.crit()) Narrator.crit(p.getLocation(), Rules.Part.TORSO, fx, c.female, p);   // he hears "you..."
        }
        p.sendMessage(Component.text(log.toString(), NamedTextColor.RED));
        if (b.inBattle(p)) b.show(p, s, c, result);
    }

    /** A raider's critical failure: the sheet's combined list, on him. */
    private void failure(Player p, Battle.State s, Character c, LivingEntity le, Battle.Target t, Combat.Failure f, StringBuilder log) {
        Battle.Weapon w = Battle.SMG;
        if (f.losesAmmo()) { t.ammo = 0; log.append(" His magazine is empty."); }
        if (f.dropsWeapon()) { t.dropped = true; log.append(" He drops his gun."); }
        if (f.weaponDamaged()) {
            t.gunHealth = Combat.damaged(t.gunHealth, w.maxHealth);
            log.append(" His gun is damaged: condition ").append(Math.round(t.gunHealth / w.maxHealth * 100)).append(" %").append(Combat.broken(t.gunHealth) ? ", broken." : ".");
        }
        if (f.weaponDestroyed()) { t.broken = true; log.append(" His gun is destroyed."); }
        if (f.hitsRandom()) {
            List<UUID> others = new ArrayList<>(s.dummies);
            Collections.shuffle(others, b.rnd);
            boolean hit = false;
            for (UUID o : others) {
                Battle.Target ot = s.targets.get(o);
                Entity oe = b.pl.getServer().getEntity(o);
                if (ot == null || ot == t || ot.dead || !(oe instanceof LivingEntity ole)) continue;
                int dmg = Combat.damage(w.minDmg + b.rnd.nextInt(w.maxDmg - w.minDmg + 1), 1, w.ammoDamage, w.ammoPenetration, ot.dt, ot.dr, false);
                log.append(" It hit ").append(ot.name).append(" for ").append(dmg).append(" HP.");
                b.hurt(ole, ot, dmg, Combat.critEffects(Rules.Part.TORSO, 0, ot.a), false, 0, log);
                hit = true;
                break;
            }
            if (!hit) log.append(" The stray round hits nobody.");
        }
        if (f.hurt1d6()) { int d = 1 + b.rnd.nextInt(6); t.hp -= d; log.append(" He takes ").append(d).append(" HP."); }
        if (f.hitsSelf()) {
            int dmg = Combat.damage(w.minDmg + b.rnd.nextInt(w.maxDmg - w.minDmg + 1), 1, w.ammoDamage, w.ammoPenetration, t.dt, t.dr, false);
            t.hp -= dmg;
            log.append(" He shoots himself for ").append(dmg).append(" HP.");
        }
        if (f.knockedDown()) { t.down = true; t.stance = 0; log.append(" He falls."); }
        if (f.crippledArm()) { t.armCrippled = true; log.append(" He cripples an arm."); }
        if (f.onFire()) log.append(" He catches fire.");
        if (f.losesTurn()) { s.pending.clear(); log.append(" His turn is over."); }
        if (t.hp < -t.a[Rules.E]) { t.dead = true; log.append(" Dead."); }
        b.label(le, t);
    }
}
