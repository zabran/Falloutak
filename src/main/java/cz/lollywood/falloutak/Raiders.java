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
        t.moved = false;
        t.steadied = false;
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
        if (t.dropped) {
            if (t.ap < 2) { q.add(() -> say(p, t.name + " reaches for his dropped gun.")); return; }
            t.ap -= 2;
            t.dropped = false;
            q.add(() -> say(p, t.name + " picks his gun up (2 AP)."));
        }
        if (t.broken) { q.add(() -> say(p, t.name + "'s gun is destroyed: he has nothing to fight with.")); return; }
        if (Combat.broken(t.gunHealth)) { q.add(() -> say(p, t.name + "'s gun is broken: he has nothing to fight with.")); return; }
        if (t.ammo <= 0 && t.ap >= 2) { t.ap -= 2; t.ammo = Battle.SMG.magazine; q.add(() -> { say(p, t.name + " reloads (2 AP)."); Entity re = b.pl.getServer().getEntity(u); if (re != null) Sfx.at(re.getLocation(), "pistol.reload"); }); }

        Location at = le.getLocation();
        World w = at.getWorld();
        Path.P start = new Path.P(at.getBlockX(), at.getBlockY(), at.getBlockZ());
        double eyeH = le.getEyeHeight();
        if (!canShoot(w, start, eyeH, p)) {
            Set<Path.P> taken = taken(s, u, p);
            Path.World world = new Path.World() {
                public boolean canStand(int x, int y, int z) {
                    Block f = w.getBlockAt(x, y, z);
                    return f.isPassable() && f.getRelative(0, 1, 0).isPassable() && f.getRelative(0, -1, 0).getType().isSolid()
                            && !taken.contains(new Path.P(x, y, z));
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
            for (int i = 0; i < steps; i++) {
                Path.P to = path.get(i);
                q.add(() -> moveTo(le, to, p));
            }
            if (steps > 0) {
                t.ap -= (int) Math.ceil(steps * per - 1e-9);
                t.moved = true;
                final int n = steps;
                q.add(() -> say(p, t.name + " moves " + n + " block(s)" + (n < path.size() ? ", toward a shot at you" : "") + "."));
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
            if (e.equals(shooter) || e.equals(target) || !(e instanceof LivingEntity le) || le.isDead()) continue;
            if (e.getBoundingBox().rayTrace(eye.toVector(), dir, len - 0.3) != null) n++;
        }
        return n;
    }

    /** One shot at the player, by the rules the player shoots by. */
    private void shoot(Player p, Battle.State s, Character c, LivingEntity le, Battle.Target t) {
        if (!b.inBattle(p) || t.dead || t.dying() || t.down || t.out || t.dropped || t.broken || Combat.broken(t.gunHealth) || t.ammo <= 0) return;
        Location at = le.getLocation();
        if (!canShoot(le.getWorld(), new Path.P(at.getBlockX(), at.getBlockY(), at.getBlockZ()), le.getEyeHeight(), p)) {
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
            if (r.crit()) Narrator.crit(p.getLocation(), Rules.Part.TORSO, fx, c.female);
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
