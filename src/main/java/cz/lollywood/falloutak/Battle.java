package cz.lollywood.falloutak;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Pillager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * The battle CONTROLS as Marek set them 2026-11-14 (`02d-SHEET-LAYOUT.md` §5), on one player with test items and two target dummies:
 * right click = the next mode of the weapon in hand (single, burst, AIMED), left click = fire/strike AT AN ENEMY IN SIGHT (Marek
 * 11-14: *"attack should not be allowed without targeting enemy"*); an aimed shot goes at the body part under the crosshair (Marek
 * 11-14: *"could the aiming take into consideration what body part do I aim at?"*), shown live in the action bar. Right click with
 * a quick item uses it, at an enemy too (Marek 11-14: *"non-weapon items (including granade) cannot be used when targeting enemy"*).
 * Shift = the next stance, standing → crouched → prone → standing (Marek 11-14: crawling was lost when shift only toggled crouch).
 * F = the other hand (0 AP), E = the pack (4 AP on the first click inside it), reload automatic, the hotbar = two hands + quick
 * items + end turn. Movement and stances by Marek's own table, from Fallout Tactics ({@link Rules#STEP_AP}): counted from where
 * the last action was taken, so walking back refunds it (Marek 11-13), and a stance change is movement (Marek 11-14): shifting back
 * to an earlier stance on the same block refunds the changes.
 */
public final class Battle implements Listener {
    record Mode(String name, int ap, int toHit, int ammo, boolean aimed) {}

    static final class Weapon {
        final String name, skill; final List<Mode> modes; final int magazine, range; final boolean twoHanded;
        int minDmg = 1, maxDmg = 1, minStrength = 0;
        String perk = "", ammo = "", damageType = "Normal";
        double ammoDamage = 0, ammoPenetration = 0;   // the workbook's ammo columns: damage modifier, penetration
        /** FNV's item health; the condition = health / this. [EXAMPLE until the workbook has a column] */
        double maxHealth = 100;
        /** The hotbar item's tag ("smg", "knife", "w:<design>"), the rounds' category it loads (own gear), thrown (each attack throws
         *  one you carry), a rocket's blast where it lands (09d round 3: the party's own gear, `data/weapons.json`). */
        String tag = "", ammoKind = "";
        boolean thrown, wears = true;
        int blastMin, blastMax;
        /** 05d round 3: a stun weapon's second damage, Electrical (Marek: *"2 weapons damages (normal+lightning) sounds OK"*); a crossbow
         *  takes no archer's Strength; the real models (shown in the item's description). */
        int shockMin, shockMax;
        boolean crossbow;
        /** 05d round 4 (Marek: *"lets follow GURPS here"*): the damage a point of Strength over 5 adds (under 5 takes), GURPS's
         *  thrust or swing slope x the wound; the armour's DR a 2040s edge ignores (titanium 0.2, diamond 0.4, monomolecular 0.8). */
        double strDmg, pen;
        /** 05d round 7 (Marek 11-27: the Plasma saber *"Powerful, but with large energy needs - user may need to reload in middle of
         *  fight."*): a melee weapon's blows on one Power cell (0 = none); the cell is its magazine, swapped as a reload. */
        int cell;
        /** 05d round 7 (RULED 11-27: black powder; *"reload must have cost in AP"*): its own reload's AP (0 = Fallout 2's 2), its lock
         *  (flint, match, cap: the misfires), black powder (fouls after {@link #FOUL_SHOTS}, smokes); the mono edge PIERCES (the
         *  armour's DT and DR a fifth, *"Should the mono edge pierce? - yes."*). */
        int reload;
        String lock = "";
        boolean powder, pierce;
        /** 05d round 7 (RULED 11-27, §7b): the bolas and the net entangle (legs, body); the spear-thrower throws what it names (the
         *  javelins carried); the war flail ignores the guard (it wraps round a blocking blade). */
        String entangle = "", throwsName = "";
        boolean noGuard;
        String models = "";
        Weapon(String name, String skill, int magazine, int range, boolean twoHanded, Mode... modes) {
            this.name = name; this.skill = skill; this.magazine = magazine; this.range = range; this.twoHanded = twoHanded; this.modes = List.of(modes);
        }
        boolean melee() { return magazine == 0; }
    }

    // the workbook's one weapon (`data/items.json`): AP 5, burst 3, 12 rounds, range 25, one hand; aimed +1 AP (the sheet); the
    // burst's +1 AP is MINE (no column for it); the aimed penalty is the body part's (Fallout 2, {@link Rules.Part})
    static final Weapon SMG = pistol();

    private static Weapon pistol() {
        com.google.gson.JsonObject o = Data.item("10mm Pistol");
        int ap = o != null ? o.get("ap").getAsInt() : 5, cap = o != null ? o.get("capacity").getAsInt() : 12;
        int burst = o != null ? o.get("burst_size").getAsInt() : 3, range = o != null ? o.get("range").getAsInt() : 25;
        boolean two = o != null && o.get("hands").getAsInt() == 2;
        Weapon w = new Weapon(o != null ? o.get("name").getAsString() : "10mm Pistol", "Guns", cap, range, two,
                new Mode("Single", ap, 0, 1, false), new Mode("Burst (" + burst + ")", ap + 1, 0, burst, false), new Mode("Aimed", ap + 1, 0, 1, true));
        w.minDmg = o != null ? o.get("minimum_damage").getAsInt() : 5;
        w.maxDmg = o != null ? o.get("maximum_damage").getAsInt() : 12;
        w.minStrength = o != null ? o.get("min_strength").getAsInt() : 3;
        w.maxHealth = 80;   // FNV's 10mm pistol: ~395 rounds before it breaks = 5 x (80 - 1) at 0.2 a round (Fallout Wiki) [EXAMPLE]
        w.perk = o != null && o.has("perk") ? o.get("perk").getAsString() : "Accurate";
        w.damageType = o != null && o.has("damage_type") ? o.get("damage_type").getAsString() : "Normal";
        // the Battle tab's own example load: 10mm JHP (damage +0.6, penetration -0.2)
        com.google.gson.JsonObject am = Data.item("10mm JHP");
        w.ammo = "10mm JHP";
        w.ammoDamage = am != null ? am.get("damage_modifier").getAsDouble() : 0.6;
        w.ammoPenetration = am != null ? am.get("penetraction").getAsDouble() : -0.2;
        w.tag = "smg";
        return w;
    }
    /** A knife reaches the next block (Fallout's adjacent hex): 2 blocks from the eye. */
    static final Weapon KNIFE = knife();

    /** EXAMPLE stats: Fallout 2's combat knife (3-10, Strength 2); the workbook has no knife. */
    private static Weapon knife() {
        Weapon k = new Weapon("Combat knife", "Knives", 0, 2, false,
                new Mode("Swing", 3, 0, 0, false), new Mode("Thrust", 4, 10, 0, false), new Mode("Aimed", 4, 0, 0, true));
        k.minDmg = 3; k.maxDmg = 10; k.minStrength = 2;
        k.tag = "knife";
        return k;
    }

    /** Part 7's weapon designs in the battle (`data/weapons.json`, EXAMPLE numbers), by name; "Fists" = the bare hands. */
    static final Map<String, Weapon> GEAR = loadGear();

    private static Map<String, Weapon> loadGear() {
        Map<String, Weapon> m = new LinkedHashMap<>();
        try (java.io.InputStream in = Battle.class.getResourceAsStream("/data/weapons.json")) {
            if (in == null) return m;
            for (com.google.gson.JsonElement e : com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("rows")) {
                com.google.gson.JsonObject o = e.getAsJsonObject();
                String name = o.get("name").getAsString(), skill = o.get("skill").getAsString();
                Weapon w;
                if (o.has("workbook") && o.get("workbook").getAsBoolean()) {
                    Weapon b = pistol();
                    w = new Weapon(b.name, b.skill, b.magazine, b.range, b.twoHanded, b.modes.toArray(new Mode[0]));
                    w.minDmg = b.minDmg; w.maxDmg = b.maxDmg; w.minStrength = b.minStrength; w.perk = b.perk; w.damageType = b.damageType;
                    w.ammo = b.ammo; w.ammoDamage = b.ammoDamage; w.ammoPenetration = b.ammoPenetration;
                } else {
                    int ap = o.get("ap").getAsInt(), range = o.get("range").getAsInt(), burst = o.has("burst") ? o.get("burst").getAsInt() : 0;
                    boolean thrown = o.has("thrown") && o.get("thrown").getAsBoolean();
                    int mag = thrown ? 99 : o.has("magazine") ? o.get("magazine").getAsInt() : 0;
                    boolean two = o.has("hands") && o.get("hands").getAsInt() == 2;
                    List<Mode> modes = new ArrayList<>();
                    if (mag == 0) { modes.add(new Mode("Swing", ap, 0, 0, false)); modes.add(new Mode("Thrust", ap + 1, 10, 0, false)); modes.add(new Mode("Aimed", ap + 1, 0, 0, true)); }
                    else if (thrown) { modes.add(new Mode("Throw", ap, 0, 1, false)); modes.add(new Mode("Aimed throw", ap + 1, 0, 1, true)); }
                    else {
                        modes.add(new Mode("Single", ap, 0, 1, false));
                        if (burst > 1) modes.add(new Mode("Burst (" + burst + ")", ap + 1, 0, burst, false));
                        modes.add(new Mode("Aimed", ap + 1, 0, 1, true));
                    }
                    w = new Weapon(name, skill, mag, range, two, modes.toArray(new Mode[0]));
                    w.minDmg = o.get("min").getAsInt(); w.maxDmg = o.get("max").getAsInt();
                    w.minStrength = o.has("str") ? o.get("str").getAsInt() : 0;
                    w.damageType = o.has("type") ? o.get("type").getAsString() : "Normal";
                    w.perk = o.has("perk") ? o.get("perk").getAsString() : "";
                    w.thrown = thrown;
                    if (o.has("blast")) { w.blastMin = o.getAsJsonArray("blast").get(0).getAsInt(); w.blastMax = o.getAsJsonArray("blast").get(1).getAsInt(); }
                    if (o.has("shock")) { w.shockMin = o.getAsJsonArray("shock").get(0).getAsInt(); w.shockMax = o.getAsJsonArray("shock").get(1).getAsInt(); }
                    w.crossbow = o.has("crossbow") && o.get("crossbow").getAsBoolean();
                    w.strDmg = o.has("str_dmg") ? o.get("str_dmg").getAsDouble() : 0;
                    w.pen = o.has("pen") ? o.get("pen").getAsDouble() : 0;
                    w.cell = o.has("cell") ? o.get("cell").getAsInt() : 0;
                    w.reload = o.has("reload") ? o.get("reload").getAsInt() : 0;
                    w.lock = o.has("lock") ? o.get("lock").getAsString() : "";
                    w.powder = o.has("powder") && o.get("powder").getAsBoolean();
                    w.pierce = o.has("pierce") && o.get("pierce").getAsBoolean();
                    w.entangle = o.has("entangle") ? o.get("entangle").getAsString() : "";
                    w.throwsName = o.has("throws") ? o.get("throws").getAsString() : "";
                    w.noGuard = o.has("noguard") && o.get("noguard").getAsBoolean();
                }
                w.ammoKind = o.has("ammo") ? o.get("ammo").getAsString() : "";
                w.models = o.has("models") ? o.get("models").getAsString() : "";
                double hp = o.has("health") ? o.get("health").getAsDouble() : 100;
                w.wears = hp > 0 && !w.thrown;
                w.maxHealth = hp > 0 ? hp : 1000;
                w.tag = "w:" + name;
                m.put(name, w);
            }
        } catch (java.io.IOException ex) { throw new IllegalStateException("data/weapons.json: " + ex.getMessage(), ex); }
        return m;
    }

    // ------------------------------------------------------------------ the bound perks the battle runs (02c; Marek 11-20)
    /** The weapons the plugin knows: Signature Weapon's choices (more come with part 7's items). */
    static List<Weapon> weapons() {
        List<Weapon> l = new ArrayList<>(List.of(SMG, KNIFE));
        for (Weapon w : GEAR.values()) if (!w.name.equals(SMG.name) && !w.name.equals("Fists") && !w.name.equals(KNIFE.name)) l.add(w);
        return l;
    }
    /** Signature Weapon on this weapon (02c): +15 to hit, one crit degree better, it never breaks (wear or a critical failure). */
    static boolean signature(Character c, Weapon w) { return c.hasPerk("Signature Weapon: " + w.name); }
    /** Weapon Master on this weapon's family (02c): its attacks −1 AP (at least 1) and +25 % damage. */
    static boolean master(Character c, Weapon w) { return c.hasPerk("Weapon Master: " + w.skill); }
    /** Nemesis against this target's kind (02c): +15 to hit and +25 % damage. */
    static boolean nemesis(Character c, Target t) { return t != null && c.hasPerk("Nemesis: " + t.kind); }
    /** A mode's AP for this character (Weapon Master). */
    static int apOf(Character c, Weapon w, Mode m) { return master(c, w) ? Math.max(1, m.ap() - 1) : m.ap(); }
    /** The perks' damage factor: each ×1.25, multiplied (Marek 11-20: *"they probably should multiply, if read as written"*). */
    static double perkDamage(Character c, Weapon w, Target t) { return (master(c, w) ? 1.25 : 1) * (nemesis(c, t) ? 1.25 : 1); }
    /** A crit's degree with Signature Weapon: one better, at most 6. */
    static int perkDegree(Character c, Weapon w, int degree) { return signature(c, w) ? Math.min(6, degree + 1) : degree; }

    /**
     * Part 5's target: the Battle tab's own raider, Nájezdník 2 (AC 5, DR 20 %, DT 2, PE 6, ST 5, EN 3, AG 4, LK 5; C and I not
     * given, 5) [EXAMPLE: 30 HP, the tab gives him none]. Blood = 4 x HP max (the workbook). Dying from 0 to -EN HP, dead below.
     */
    static final class Target {
        final String name;
        final int hpMax, ac, dt;
        final double dr;
        final int[] a;
        int hp;
        double blood, bleeding = 0;
        boolean down, out, blind, armCrippled, legCrippled, dead;
        int outRounds;
        // his own turn (Raiders): AP, his gun, his stance; skill 50 [EXAMPLE: the Battle tab's two shooters' value, he has none]
        final int skill;
        int ap, ammo = SMG.magazine, stance;
        /** His ranged weapon (09d round 5: the raiders' bows beside the pistols, `data/arena-foes.json`), and the rounds or arrows he
         *  carries besides the loaded ones (they hoard: Marek 11-24, *"they will hoard it, it is what ensures that they have dominance"*);
         *  with none left he draws his knife. */
        Weapon gun = SMG;
        int reserve = 99;
        /** The name of the weapon he fights with (the pistol, a bow, a machete), for the search of his body. */
        String weaponName = SMG.name;
        /** An ALLY OF CONVENIENCE (09d round 5, Marek 11-24: *"if some other enemy attacks on the same map, it may make the group that does
         *  not fight already allies of convinience instead"*): he fights the others, never the party; his kill XP is paid (once). */
        boolean ally, paid;
        /** A creature of the world map's encounters (09d round 2): its own attack in melee instead of the gun; a HUNTED one that is not
         *  hostile runs from the party (and is gone once it is far enough: escaped). */
        boolean melee, flees, escaped;
        /** 05d round 7: entangled (1 the bolas: the legs, 2 the net: the body); the round a sedative dart puts him to sleep (-1 none). */
        int entangled, sedatedAt = -1, hold;
        /** `04j` §7h (0.111.0): the round the double dose's breathing stage kills him unless naloxone reaches him (-1 none); drowsy
         *  from a dose too small for him (AP −1 each turn). */
        int breathStopAt = -1;
        boolean drowsy;
        /** 05d round 7: asleep from the dart's sedative (out of the fight; the party takes him alive on the field after it); 05d §7c: in
         *  a pit (his next turn goes on climbing out). */
        boolean asleep, inPit;
        int minDmg = 5, maxDmg = 12, reach = 1, attackAp = 4;
        String attack = "bite";
        /** The battle map's ground (null in the demo): a running animal at its edge is gone. */
        org.bukkit.util.BoundingBox field;
        /** Its group on the battle map (09d round 3: *"dangers should also attack each other"*): figures of other groups are its prey,
         *  but a patrol and a caravan (peaceable) leave each other be. */
        int group;
        boolean peaceable;
        /** The party hurt it (touched) / the last hit was the party's (its kill XP is the party's: Fallout 2 pays the player's kills). */
        boolean touched, byParty;
        /** What its body gives the party that holds the field (09d round 3: food from a hunt): raw meat in kg, raw hides. */
        double meatKg;
        int hides;
        /** The talk on the battle map (09d round 4): this figure is talking and holds; caught off guard by an attack in the talk (+10 to
         *  hit it for the rest of the party's turn); its statblock's key (raider, patrol, trader, or the creature's), for the loot. */
        boolean talking, surprised;
        String foe = "";
        double gunHealth = SMG.maxHealth;
        boolean dropped, broken, moved, steadied, lostTurn;
        /** Lying on the ground (knocked down or out, dying): the prone +4 AC and +20 to be hit within reach. */
        boolean lying() { return down || out || dying(); }
        /** What he is, for Nemesis (`data/foes.json`): the demo's raiders are Raiders. */
        String kind = "Raiders";
        /** The anomalies (09c, round 10: *"raiders walking into anomalies - it should happen and they should get affected by the
         *  anomaly."*): the ones he has noticed (he walks round them) or missed; AP lost next turn (the lightning, the cold); held this turn. */
        final Set<String> noticed = new HashSet<>(), missed = new HashSet<>();
        int apLoss;
        boolean held, frozen;
        /** His gun jammed (05 §9, round 11): his next turn begins by clearing it. */
        boolean jammed;
        /** The Battle tab's raider (the demo's). */
        Target(String name) { this(name, 30, 5, 2, 0.2, new int[]{5, 6, 3, 5, 5, 4, 5}, 50); }
        Target(String name, int hpMax, int ac, int dt, double dr, int[] a, int skill) {
            this.byParty = true;   // the demo's raiders fall only to the player
            this.name = name; this.hpMax = hpMax; this.ac = ac; this.dt = dt; this.dr = dr; this.a = a.clone(); this.skill = skill;
            this.hp = hpMax; this.blood = 4.0 * hpMax;
        }
        /** Can still take part: neither dead, dying, beaten (a raider's gun gone) nor escaped. */
        boolean inFight() { return !escaped && !asleep && (melee ? !dead && !dying() : canFight(this)); }
        /** Out of the fight for its end (Marek 11-27, verbatim: *"I think fight should end (should players choose that they do not want to
         *  just kill them) when all enemies are unable to fight (paralysed, asleep, dying, in too much pain etc.). I would say that in that
         *  case, they all count as surrendered."*): not in it, knocked out, or in the net. */
        boolean outOfIt() { return !inFight() || out || entangled == 2; }
        boolean dying() { return !dead && hp <= 0; }
        String label() {
            List<String> st = new ArrayList<>();
            if (dead) st.add("dead");
            else if (dying()) st.add("dying");
            if (!dead && breathStopAt >= 0) st.add("NOT BREATHING");
            if (!dead && asleep) st.add("asleep"); else if (!dead && out) st.add("out");
            if (!dead && drowsy && !asleep) st.add("drowsy"); else if (!dead && inPit) st.add("in a pit");
            else if (!dead && down) st.add("down"); else if (!dead && stance == 1) st.add("crouched");
            if (blind) st.add("blind");
            if (armCrippled) st.add("arm crippled");
            if (legCrippled) st.add("leg crippled");
            if (!dead && jammed) st.add("gun jammed");
            if (!dead && broken) st.add("gun destroyed"); else if (!dead && Combat.broken(gunHealth)) st.add("gun broken"); else if (!dead && dropped) st.add("gun dropped");
            if (!dead && bleeding > 0) st.add("bleeding " + Txt.f(bleeding));
            if (!dead && ally) st.add("ALLY");
            return name + " · " + hp + "/" + hpMax + " HP" + (st.isEmpty() ? "" : " · " + String.join(", ", st));
        }
    }

    /** One stretch of movement in one stance, from the block where it began (a stance change starts a new one). */
    record Leg(int stance, Location from) {}

    static final class State {
        ItemStack[] saved;
        int ap, apAtAnchor;                  // AP now / AP when the last action was taken
        final List<Leg> legs = new ArrayList<>();   // the movement since the last action; legs.get(0).from = the anchor
        boolean steadied, packCharged;
        long lastLeft, lastRight, lastShown, lastEventAt;
        String lastBarText = "", lastEvent;
        final Map<String, Integer> mode = new HashMap<>(), ammo = new HashMap<>();
        /** The fight he is in, and its enemies (shared with the other players in it). */
        Fight fight;
        /** HIS OWN GEAR (a fight on a battle map, 09d round 3): the rounds he carries by category, what he used (rounds by category,
         *  "stim", "grenade", "gasgren", "thrown:<name>"), how many quick items he has, and each kit weapon's item in his inventory. */
        boolean own;
        final Map<String, Integer> reserve = new HashMap<>(), used = new HashMap<>(), quick = new HashMap<>();
        final Map<String, String> source = new HashMap<>();
        /** 05d round 1 (*"Ammo variants per calibre - yes."*): the load in each weapon's magazine ("category|load"; one load a
         *  magazine, the reload takes the load he carries most of) [the rule mine]. */
        final Map<String, String> load = new HashMap<>();
        /** 05d §5: the accessories on each of his own weapons (slot -> name), from its item. */
        final Map<String, Map<String, String>> acc = new HashMap<>();
        List<UUID> dummies = new ArrayList<>();
        Map<UUID, Target> targets = new HashMap<>();
        final Set<String> dropped = new HashSet<>(), broken = new HashSet<>();
        /** Each weapon's item health (FNV); missing = new. */
        final Map<String, Double> health = new HashMap<>();
        int kits = 2;
        /** No raider can fight any more: the demo is ending. A gun was fired (indoors it rings the ears, 03b §3c). */
        boolean over, burning, gunfire;
        /** Rounds the environment forces (no raiders, his own inventory): Marek 11-18, round 23. */
        boolean env;
        /** His own count of the scene's rounds (from the zones' round when he entered): a cloud ages once a round however many play. */
        long sceneRound;
        /** The gas clouds this demo made: gone when it ends. */
        final List<Zones.Zone> clouds = new ArrayList<>();
        boolean armCrippled, blind;
        /** The raiders are taking their turn: the player waits. */
        boolean enemyTurn;
        /** Rounds kept without danger (`09b` round 11): the player's own choice, or the Black Rain's warning; why. */
        boolean hold;
        String holdWhy = "";
        /** The guns jammed (05 §9, round 11): firing one again is the clearing check. */
        final Set<String> jammed = new HashSet<>();
        int round = 1, lostTurns;
        /** The Chi Master's Occultism for the punches' names (05d round 6), -1 without the perk; set before the modes are read. */
        double chi = -1;
        /** AP lost at the next turn (05d round 5: the shock on a player, `05d` §2e). */
        int apLoss;
        /** 05d round 7: the AP already paid into a long action ("reload:" or "clean:" + the weapon's tag); the shots each black-powder
         *  gun fired since it was cleaned; the round his black-powder smoke hangs over him. */
        final Map<String, Integer> progress = new HashMap<>(), fired = new HashMap<>();
        int smokeRound = -1;
        /** The AP he left unused at his turn's end (carried, `turnAp`); the way he faced then (05c §11: facing, yaw); his shield (its
         *  name, its item's id, its health now). */
        int carry;
        float faceYaw;
        String shield, shieldSource;
        double shieldHealth;
        /** 05c §11: his standing shield set down as a wall (null while he holds it). */
        Wall wall;
        org.bukkit.scheduler.BukkitTask task;
        Deque<Runnable> pending = new ArrayDeque<>();
        /** The character as the demo began: given back if he dies in it (it is a demo). */
        int hp0, legs0;
        double bleeding0;
        final EnumMap<Rules.Stat, Double> survival0 = new EnumMap<>(Rules.Stat.class);
        BossBar bar;
        int stance() { return legs.get(legs.size() - 1).stance(); }
    }

    /**
     * One fight and everyone in it (09d round 2, the battle maps): the enemies are the fight's, the players each keep their own turn's
     * state. The order is part 5's (02c §7, the games' own): THE ATTACKER ACTS FIRST, the rest by Sequence; the party's PCs play one
     * after another, so they play AT ONCE (Marek 11-13: *"PCs that are on the same side ... and play right after one other (no enemy in
     * between) can play simultanously"*): a round is the party's turn and the enemies' turn, whichever side attacked first; the party's
     * turn ends when every player in it has ended his. The battle demo is a fight of one player.
     */
    /** A trap set in this fight (05d §7c): where, by whom (his skill of its kind then), the way he faced (a directional mine); known =
     *  an enemy spotted it (they walk round it now); sprung = gone. */
    /** A standing shield set down (05c §11, the pavise: *"set down 4 AP: a wall for whoever is behind it"*): where it stands, the way it
     *  faces (its front toward the enemy), its look on the field (a display entity, gone when it is taken up or the fight ends). */
    static final class Wall {
        final double x, z;
        final int y;
        final float yaw;
        UUID display;
        Wall(double x, int y, double z, float yaw) { this.x = x; this.y = y; this.z = z; this.yaw = yaw; }
    }

    static final class Trap {
        final Data.TrapDef def;
        final int x, y, z;
        final UUID setter;
        final double setterSkill;
        final float yaw;
        boolean known, sprung;
        Trap(Data.TrapDef def, int x, int y, int z, UUID setter, double setterSkill, float yaw) {
            this.def = def; this.x = x; this.y = y; this.z = z; this.setter = setter; this.setterSkill = setterSkill; this.yaw = yaw;
        }
    }

    static final class Fight {
        /** 05d §7c: the traps the party set in this fight. */
        final List<Trap> traps = new ArrayList<>();
        final Map<UUID, Target> targets = new HashMap<>();
        final List<UUID> dummies = new ArrayList<>();
        final List<UUID> players = new ArrayList<>();
        final Set<UUID> done = new HashSet<>();
        final Deque<Runnable> pending = new ArrayDeque<>();
        org.bukkit.scheduler.BukkitTask task;
        int round = 1;
        boolean over, enemyPhase, enemiesFirst, started;
        /** The party's ambush (the battle map's AMBUSH): in its first round a foe not yet hit is unaware (05d round 5). */
        boolean ambush;
        /** The demo restores a character who dies in it; so does a fight on a battle map (its kit is still the demo's). */
        boolean demo = true;
        /** How the enemies are named ("the raiders", "the enemies"). */
        String them = "the raiders";
        /** Everyone who took part; the battle map's ground (a player at its edge gets away); who got away, who died; the XP settled. */
        final List<UUID> members = new ArrayList<>();
        org.bukkit.util.BoundingBox field;
        final Set<UUID> fled = new HashSet<>(), died = new HashSet<>();
        boolean settled;
        /** What each player still on the field when it ended takes home (meat, hides), given when his inventory is back. */
        final Map<UUID, List<ItemStack>> spoils = new HashMap<>();
        /** The talk in progress on the battle map (09d round 4), or null. */
        Talk talk;
        /** Each player leaving (the arena sends him back), and the fight's end (the last one out). */
        java.util.function.Consumer<Player> onLeave = q -> { };
        Runnable onEnd = () -> { };
        Fight() {}
        Fight(String them) { this.them = them; }
    }

    /**
     * THE TALK ON THE BATTLE MAP (`09d` round 4, Marek 11-24: *"the talking should already start on the battle map, so players should have
     * chance to get into positions at least. Failure happens during that. Only one of players talks (selectable). Also, players can attack
     * during the fight to get slight advantage."*): the speaker's check (Rhetorics + 20 the first sight − 20 they came to fight, ruled
     * *"yes"* the same day) is rolled when it begins; it lasts {@value #TALK_ROUNDS} rounds of the party's turns, in which everyone moves
     * into position while the talkers hold; a failed one goes wrong at the end of the party's turn of round {@code failAt} and they shoot
     * first; a good one ends with them going their way for their full kill XP; an attack in it breaks it and catches them off guard
     * [the rounds, the moment and the +10 mine, EXAMPLE].
     */
    static final class Talk {
        final UUID speaker;
        final String speakerName, who, check;
        /** The round at whose end it goes wrong (0 = it would end well); the GM can bring it forward (09d round 5). */
        int failAt;
        Talk(UUID speaker, String speakerName, String who, String check, int failAt) {
            this.speaker = speaker; this.speakerName = speakerName; this.who = who; this.check = check; this.failAt = failAt;
        }
    }

    static final int TALK_ROUNDS = 3, TALK_SURPRISE = 10;

    final FalloutakPlugin pl;
    final Raiders raiders;
    private final NamespacedKey key;
    private final Map<UUID, State> states = new HashMap<>();
    final Random rnd = new Random();
    java.util.function.IntSupplier d100 = () -> rnd.nextInt(100) + 1;   // the dice (a test may fix them)

    Battle(FalloutakPlugin pl) {
        this.pl = pl;
        this.key = new NamespacedKey(pl, "fo_item");
        this.raiders = new Raiders(this);
        // the aim is read EVERY TICK (Marek 11-14: *"The aiming body part change activates irregularily."*): Paper fires a move
        // event for turning only past ~10 degrees, so a small or slow change of aim never arrived as an event
        pl.getServer().getScheduler().runTaskTimer(pl, this::tickAim, 1L, 1L);
    }

    /** Each tick: the action bar again when what it shows has changed (the part under the crosshair), else once a second. */
    private void tickAim() {
        long now = System.currentTimeMillis();
        for (var e : states.entrySet()) {
            Player p = pl.getServer().getPlayer(e.getKey());
            if (p == null) continue;
            State s = e.getValue();
            Component bar = bar(p, s, pl.character(p));
            String txt = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(bar);
            if (!txt.equals(s.lastBarText) || now - s.lastShown > 1000) { p.sendActionBar(bar); s.lastBarText = txt; s.lastShown = now; }
        }
    }

    /**
     * An entity's look and manners (no AI, silent, invulnerable): real Paper does them all; the test server (MockBukkit) leaves some
     * unimplemented, and only that is ignored, so the battle's logic runs in the unit tests (Marek 11-18: test all but the UX and UI).
     */
    /** A value the test server may lack (MockBukkit): the fallback there, the real one on Paper. */
    static <T> T orElse(java.util.function.Supplier<T> s, java.util.function.Supplier<T> fallback) {
        try { return s.get(); } catch (RuntimeException e) { if (!e.getClass().getSimpleName().equals("UnimplementedOperationException")) throw e; return fallback.get(); }
    }

    static void cosmetic(Runnable r) {
        try { r.run(); } catch (RuntimeException e) { if (!e.getClass().getSimpleName().equals("UnimplementedOperationException")) throw e; }
    }

    boolean inBattle(Player p) { return states.containsKey(p.getUniqueId()); }

    /** The player's battle (for the tests), or null. */
    State state(Player p) { return states.get(p.getUniqueId()); }

    // ------------------------------------------------------------------ start / stop
    void start(Player p, Character c) {
        Fight f = new Fight();
        State s = join(p, c, f);
        dummy(p, s, 8, "Raider (target dummy)");
        dummy(p, s, 3, "Raider, close (target dummy)");
        p.sendMessage(Component.text("Battle demo: right click = mode (single, burst, aimed), left click AT AN ENEMY = fire; the action bar names the "
                + "body part under your crosshair. Shift = next stance (standing, crouched, prone; back to an earlier one on the same block = refunded). "
                + "Quick items: right click, at an enemy too. F = other hand, E = the pack, walk to spend AP (walking back refunds it), the clock = "
                + "end turn: then the raiders take their turn and shoot back. /sheet repair = a repair kit on the weapon in hand. /sheet legs 0-2 breaks legs for the test. /sheet battle again "
                + "to stop. Round 1: your turn.", NamedTextColor.AQUA));
        f.started = true;
        show(p, s, c, null);
    }

    /** The player's fight, or null. */
    Fight fightOf(Player p) { State s = states.get(p.getUniqueId()); return s == null ? null : s.fight; }

    /** The players of a fight still in it (online, in the battle). */
    List<Player> players(Fight f) {
        List<Player> l = new ArrayList<>();
        for (UUID u : f.players) { Player q = pl.getServer().getPlayer(u); if (q != null && inBattle(q) && states.get(u).fight == f) l.add(q); }
        return l;
    }

    /** A line to everyone in the fight. */
    void tell(Fight f, String text, NamedTextColor colour) { for (Player q : players(f)) q.sendMessage(Component.text(text, colour)); }

    /** Puts a player into a fight with the demo's kit (the workbook's pistol, a knife, the quick items). */
    State join(Player p, Character c, Fight f) { return join(p, c, f, false); }

    /** The same with HIS OWN GEAR (a battle map, 09d round 3): {@link #ownKit}. */
    State join(Player p, Character c, Fight f, boolean own) {
        if (inEnv(p)) stop(p);
        State s = new State();
        s.fight = f;
        s.targets = f.targets;
        s.dummies = f.dummies;
        s.pending = f.pending;
        s.round = f.round;
        if (!f.players.contains(p.getUniqueId())) f.players.add(p.getUniqueId());
        if (!f.members.contains(p.getUniqueId())) f.members.add(p.getUniqueId());
        s.sceneRound = pl.zones.round;
        s.saved = p.getInventory().getContents().clone();
        for (int i = 0; i < s.saved.length; i++) if (s.saved[i] != null) s.saved[i] = s.saved[i].clone();
        s.own = own;
        if (own) { for (int i = 0; i < 9; i++) p.getInventory().setItem(i, null); }   // his pack stays his; the hotbar is the battle's
        else {
            ItemStack[] worn = p.getInventory().getArmorContents().clone();
            p.getInventory().clear();
            p.getInventory().setArmorContents(worn);   // the armour stays on in the demo (05c)
        }
        s.ap = s.apAtAnchor = c.apMax();
        s.faceYaw = p.getLocation().getYaw();
        s.legs.add(new Leg(0, p.getLocation().toBlockLocation()));
        s.ammo.put("smg", SMG.magazine);
        s.hp0 = c.hp; s.bleeding0 = c.bleeding; s.legs0 = c.brokenLegs; s.survival0.putAll(c.survival);
        s.bar = BossBar.bossBar(Component.text(""), 1f, BossBar.Color.BLUE, BossBar.Overlay.NOTCHED_10);
        Sfx.to(p, "combat.start");
        p.showBossBar(s.bar);
        states.put(p.getUniqueId(), s);
        String episode = c.episode(java.util.concurrent.ThreadLocalRandom.current(), true);   // 03b §7b: at the start of each fight (round 8)
        if (episode != null) p.sendMessage(Component.text(episode, NamedTextColor.LIGHT_PURPLE));
        var inv = p.getInventory();
        if (own) { ownKit(p, s); return s; }
        inv.setItem(0, tagged(Material.IRON_HORSE_ARMOR, "smg", SMG.name, "right hand · the workbook's weapon: 5-12 Normal, range " + SMG.range + ", Accurate",
                "right click: next mode (single, burst, aimed)", "aimed: the body part under your crosshair", "left click at an enemy: fire"));
        inv.setItem(1, tagged(Material.IRON_SWORD, "knife", KNIFE.name, "left hand · reach: the next block", "F or scroll: the other hand (0 AP)"));
        inv.setItem(2, tagged(Material.HONEY_BOTTLE, "stim", "Stimpak", "quick item (belt): 2 AP", "right click: use on yourself"));
        inv.setItem(3, tagged(Material.FIRE_CHARGE, "grenade", "Frag grenade", "quick item (belt): right click throws it at what you aim at",
                "4 AP (EXAMPLE) · range 3 × Strength, at most 15 (Fallout 2) · Grenades skill"));
        inv.setItem(4, tagged(Material.TORCH, "flare", "Flare", "quick item (belt): right click throws it, 2 AP", "EXAMPLE"));
        inv.setItem(5, tagged(Material.SLIME_BALL, "gasgren", "Riot gas grenade", "quick item (belt): right click throws it, 4 AP",
                "a cloud of riot gas I, radius " + GAS_RADIUS + ", for " + GAS_ROUNDS + " rounds (03b §3c) [EXAMPLE]", "P −3, to hit −20, AP −2 inside"));
        for (int i = 6; i < 8; i++) inv.setItem(i, tagged(Material.GRAY_STAINED_GLASS_PANE, "locked", "no quick slot",
                "a belt or bandolier gives more quick slots", "(the trait Prospector takes one)"));
        inv.setItem(8, tagged(Material.CLOCK, "end", "End turn", "click: end the turn (the demo refills your AP)"));
        inv.setItem(9, tagged(Material.IRON_NUGGET, "ammo", "10mm ball x60", "in the pack: E opens it (4 AP on the first click)"));
        inv.setItem(10, tagged(Material.POTION, "water", "Water bottle", "in the pack"));
        inv.setItem(11, tagged(Material.COOKED_BEEF, "food", "Dried meat", "in the pack"));
        inv.setItem(12, tagged(Material.SHEARS, "kit", "Weapon repair kit x2", "in the pack · /sheet repair: on the weapon in hand, 4 AP",
                "Fallout: New Vegas: +10 to +30 % condition by your repair skill (Crafting)"));
        inv.setHeldItemSlot(0);
        return s;
    }

    // ------------------------------------------------------------------ his own gear (09d round 3: "your own gear in fights")
    /** The rounds' categories (part 7's designs) and what a power cell holds [EXAMPLE]. */
    static final Set<String> ROUNDS = Production.roundsCategories();
    static final int CELL_SHOTS = 10;
    /** 05d round 7 (Marek 11-27: the Plasma saber's *"large energy needs"*): a plasma blade's blow draws 2 Power shots, so its 6-blow
     *  charge is a laser pistol's 12-shot magazine [mine]. */
    static final int CELL_DRAW = 2;

    /**
     * The battle's hotbar from what he carries [the form mine]: his first two weapons (part 7's designs with a row in
     * `data/weapons.json`; none = his fists) in the hands at their own condition, loaded from the rounds he carries of their category
     * (a power cell = {@value #CELL_SHOTS} shots of an energy weapon; a thrown weapon = how many he carries); his Stimpaks, frag, pipe and
     * fire grenades, riot gas grenades as the quick items with their counts; his pack (the rest of the inventory) as it is.
     */
    void ownKit(Player p, State s) {
        List<Weapon> hands = new ArrayList<>();
        Map<String, Integer> thrown = new HashMap<>();
        for (ItemStack it : s.saved) {
            String id = Items.id(it);
            if (id == null) continue;
            int n = it.getAmount();
            if (id.equals("gear:Stimpak")) { s.quick.merge("stim", n, Integer::sum); continue; }
            String[] th = Items.thing(id);
            if (th != null && th[0].equals("Power")) { s.reserve.merge("Power", n * CELL_SHOTS, Integer::sum); continue; }
            Production.Design d = Items.made(id);
            if (d == null) continue;
            if (d.category.equals("Shields") && s.shield == null && Data.SHIELDS.containsKey(d.name)) {   // 05c §11: the off hand
                s.shield = d.name;
                s.shieldSource = id;
                s.shieldHealth = Items.madeCondition(it) / 100.0 * Data.SHIELDS.get(d.name).health();
                continue;
            }
            if (ROUNDS.contains(d.category)) { s.reserve.merge(d.key(), n, Integer::sum); continue; }
            switch (d.name) {
                case "Frag grenade", "Military frag grenade", "Pipe grenade", "Fire bottle" -> { s.quick.merge("grenade", n, Integer::sum); continue; }
                case "Riot gas grenade" -> { s.quick.merge("gasgren", n, Integer::sum); continue; }
                default -> { }
            }
            Weapon w = GEAR.get(d.name);
            if (w == null || w.name.equals("Fists")) continue;
            if (w.thrown) thrown.merge(w.name, n, Integer::sum);
            if (!hands.contains(w) && hands.size() < 2) {
                hands.add(w);
                s.source.put(w.tag, id);
                s.acc.put(w.tag, Accessories.fitted(it));
                if (w.wears) s.health.put(w.name, Items.madeCondition(it) / 100.0 * w.maxHealth);
            }
        }
        Weapon fists = GEAR.get("Fists");
        while (hands.size() < 2) hands.add(fists);
        var inv = p.getInventory();
        for (int k = 0; k < 2; k++) {
            Weapon w = hands.get(k);
            if (w.thrown) s.ammo.put(w.tag, thrown.getOrDefault(w.throwsName.isEmpty() ? w.name : w.throwsName, 0));   // the spear-thrower: the javelins
            else if (!w.melee() || w.cell > 0) reload(s, w);   // 05d round 7: the saber's cell
            for (String at : Accessories.attacks(s.acc.get(w.tag))) { Weapon x = GEAR.get(at); if (x != null && !x.melee()) reload(s, x); }   // the launcher
            Material m = w == fists ? Material.LEATHER : w.melee() || w.thrown ? Material.IRON_SWORD : Material.IRON_HORSE_ARMOR;
            String what = w.minDmg + "-" + w.maxDmg + " " + w.damageType + (w.melee() ? ", reach " + (w.range - 1) + (w.cell > 0 ? ", " + ammoOf(s, w) + " blows on its cell" : "") : w.thrown ? ", thrown, " + ammoOf(s, w) + " carried"
                    : ", range " + w.range + ", " + cap(s, w) + " a load of " + w.ammoKind + " (" + ammoOf(s, w) + " in" + loadName(s, w) + ", " + reserveOf(s, w.ammoKind) + " more)")
                    + (w.blastMax > 0 ? ", bursts " + w.blastMin + "-" + w.blastMax : "");
            inv.setItem(k, tagged(m, w.tag, w.name, (k == 0 ? "right hand" : "left hand") + " · your own · " + w.skill + " · " + what,
                    "right click: next mode · left click at an enemy: attack", "F or scroll: the other hand (0 AP)"));
        }
        // the belt (Marek 11-27, verbatim: *""Belt: in a fight with your own gear" - this needs to be possible to prepare before battle."*):
        // what he put on hotbar keys 3-6 before the fight stays there; the Stimpaks and grenades he did not place take their usual slot,
        // or the first free one; what is left free takes the pack's items in the fight (the pack opened for 4 AP)
        boolean[] taken = new boolean[9];
        Set<String> placedKinds = new HashSet<>();
        for (int i = BELT_FIRST; i <= BELT_LAST; i++) {
            ItemStack it = i < s.saved.length ? s.saved[i] : null;
            String kind = beltKind(it);
            if (kind == null || placedKinds.contains(kind)) { inv.setItem(i, null); continue; }
            taken[i] = true;
            if (kind.startsWith("item:")) inv.setItem(i, it.clone());   // his own item: used from here, taken from his gear after the fight
            else { placedKinds.add(kind); quick(inv, s, i, kind); }
        }
        for (String kind : List.of("stim", "grenade", "gasgren")) {
            if (placedKinds.contains(kind) || s.quick.getOrDefault(kind, 0) <= 0) continue;
            int at = BELT_HOME.get(kind);
            if (taken[at]) { at = -1; for (int i = BELT_FIRST; i <= BELT_LAST; i++) if (!taken[i]) { at = i; break; } }
            if (at < 0) continue;   // a full belt: the rest stays in the pack
            taken[at] = true;
            quick(inv, s, at, kind);
        }
        for (int i = 6; i < 8; i++) inv.setItem(i, tagged(Material.GRAY_STAINED_GLASS_PANE, "locked", "no quick slot", "a belt or bandolier gives more quick slots"));
        inv.setItem(8, tagged(Material.CLOCK, "end", "End turn", "click: end your turn; when everyone has, it is theirs"));
        p.sendMessage(Component.text("Your belt (hotbar keys 3-6): what you put there before the fight is on it. A free belt slot takes a Super Stimpak, "
                + "a Hypo, a chem or a medicine dragged from your pack (4 AP to open it); right click uses it on you, " + BELT_AP + " AP.", NamedTextColor.GRAY));
        inv.setHeldItemSlot(0);
        s.kits = 0;
        show(p, s, pl.character(p), null);
    }

    /** The belt's counted kinds and their usual slot (before 0.109.0 their fixed one). */
    static final Map<String, Integer> BELT_HOME = Map.of("stim", 2, "grenade", 3, "gasgren", 5);

    private void quick(org.bukkit.inventory.PlayerInventory inv, State s, int slot, String id) {
        int n = s.quick.getOrDefault(id, 0);
        switch (id) {
            case "stim" -> inv.setItem(slot, tagged(Material.HONEY_BOTTLE, id, "Stimpak x" + n, "quick item (belt): 2 AP: right click, on yourself", "your own"));
            case "grenade" -> inv.setItem(slot, tagged(Material.FIRE_CHARGE, id, "Grenade x" + n, "quick item (belt): 4 AP: right click throws it (3 × Strength, at most 15) · Grenades", "your own"));
            default -> inv.setItem(slot, tagged(Material.SLIME_BALL, id, "Riot gas grenade x" + n, "quick item (belt): 4 AP: right click throws it", "your own"));
        }
    }

    /** What an item on a belt slot before the fight is there: a counted kind (stim, grenade, gasgren), "item:<id>" (used itself), or null. */
    static String beltKind(ItemStack it) {
        String id = Items.id(it);
        if (id == null) return null;
        if (id.equals("gear:Stimpak")) return "stim";
        Production.Design d = Items.made(id);
        if (d != null) switch (d.name) {
            case "Frag grenade", "Military frag grenade", "Pipe grenade", "Fire bottle" -> { return "grenade"; }
            case "Riot gas grenade" -> { return "gasgren"; }
            default -> { }
        }
        return beltUsable(id) ? "item:" + id : null;
    }

    /** A real item used from the belt on oneself: an injector, a medicine taken by oneself, a vaccine, a chem. */
    static boolean beltUsable(String iid) {
        String med = Meds.name(iid);
        if (med != null && (Meds.INJECTORS.contains(med) || Meds.SELF.contains(med) || Meds.VACCINES.containsKey(med))) return true;
        return iid.startsWith("chem:") && Data.CHEMS.containsKey(iid.substring(5));
    }

    /**
     * After a fight with his own gear: what he used is gone from the inventory he gets back (rounds by category, the lowest tier first;
     * power cells by the shots; Stimpaks; grenades), his weapons keep the condition they ended with (a destroyed one is gone), a thrown
     * weapon is picked up again if he held the field to the end, else it is lost.
     */
    void settleGear(Player p, State s) {
        Fight f = s.fight;
        boolean held = f.over && !f.fled.contains(p.getUniqueId()) && !f.died.contains(p.getUniqueId());
        for (Map.Entry<String, Integer> u : s.used.entrySet()) {
            String k = u.getKey();
            int n = u.getValue();
            if (n <= 0) continue;
            switch (k) {
                case "stim" -> removeIds(p, id -> id.equals("gear:Stimpak"), n);
                case "grenade" -> removeIds(p, id -> { Production.Design d = Items.made(id); return d != null && (d.name.equals("Frag grenade") || d.name.equals("Military frag grenade") || d.name.equals("Pipe grenade") || d.name.equals("Fire bottle")); }, n);
                case "gasgren" -> removeIds(p, id -> { Production.Design d = Items.made(id); return d != null && d.name.equals("Riot gas grenade"); }, n);
                case "Power" -> removeIds(p, id -> { String[] t = Items.thing(id); return t != null && t[0].equals("Power"); }, (n + CELL_SHOTS - 1) / CELL_SHOTS);
                default -> {
                    if (k.startsWith("item:")) { String want = k.substring(5); removeIds(p, id -> id.equals(want), n); }   // the belt's own items
                    else if (k.startsWith("thrown:")) { if (!held) { String name = k.substring(7); removeIds(p, id -> { Production.Design d = Items.made(id); return d != null && d.name.equals(name); }, n); } }
                    else removeIds(p, id -> { Production.Design d = Items.made(id); return d != null && (k.contains("|") ? d.key().equals(k) : d.category.equals(k)); }, n);
                }
            }
        }
        for (Map.Entry<String, String> e : s.source.entrySet()) {
            Weapon w = weapon(e.getKey());
            Production.Design d = Items.made(e.getValue());
            if (w == null || d == null) continue;
            if (s.broken.contains(w.name)) { removeIds(p, id -> id.equals(e.getValue()), 1); p.sendMessage(Component.text("Your " + w.name + " is destroyed.", NamedTextColor.RED)); continue; }
            if (!w.wears || !s.health.containsKey(w.name)) continue;
            double cond = Math.max(0, Math.min(100, s.health.get(w.name) / w.maxHealth * 100));
            if (removeIds(p, id -> id.equals(e.getValue()), 1) > 0)
                { ItemStack back = Items.made(d, 1, cond);   // 05d §5: its accessories stay on it
                    if (s.acc.containsKey(e.getKey()) && !s.acc.get(e.getKey()).isEmpty()) Accessories.set(back, s.acc.get(e.getKey()));
                    for (ItemStack rest : p.getInventory().addItem(back).values()) p.getWorld().dropItem(p.getLocation(), rest); }
        }
        if (s.shield != null && s.shieldSource != null) {   // 05c §11: the shield goes back at its condition (broken = 0 %, repairable)
            Data.Shield sd = Data.SHIELDS.get(s.shield);
            Production.Design d = Items.made(s.shieldSource);
            if (sd != null && d != null && removeIds(p, id -> id.equals(s.shieldSource), 1) > 0) {
                ItemStack back = Items.made(d, 1, Math.max(0, Math.min(100, s.shieldHealth / sd.health() * 100)));
                for (ItemStack rest : p.getInventory().addItem(back).values()) p.getWorld().dropItem(p.getLocation(), rest);
            }
        }
    }

    /** Removes up to n items whose id matches, the lowest tier first. */
    static int removeIds(Player p, java.util.function.Predicate<String> match, int n) {
        var inv = p.getInventory();
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) { String id = Items.id(inv.getItem(i)); if (id != null && match.test(id)) slots.add(i); }
        slots.sort(Comparator.comparingInt(i -> { Production.Design d = Items.made(Items.id(inv.getItem(i))); return d == null ? 0 : d.tier; }));
        int took = 0;
        for (int i : slots) {
            if (took >= n) break;
            ItemStack it = inv.getItem(i);
            int k = Math.min(n - took, it.getAmount());
            if (k >= it.getAmount()) inv.setItem(i, null);
            else { ItemStack less = it.clone(); less.setAmount(it.getAmount() - k); inv.setItem(i, less); }
            took += k;
        }
        return took;
    }

    /** A still raider to aim at, {@code dist} blocks ahead on the nearest standing spot, facing the player; removed when the demo stops. */
    private void dummy(Player p, State s, int dist, String name) {
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) dir = new Vector(0, 0, 1);
        Location at = p.getLocation().add(dir.normalize().multiply(dist));
        Block foot = null;
        for (int dy = 3; dy >= -4 && foot == null; dy--) {
            Block b = at.getWorld().getBlockAt(at.getBlockX(), at.getBlockY() + dy, at.getBlockZ());
            if (b.isPassable() && b.getRelative(0, 1, 0).isPassable() && b.getRelative(0, -1, 0).isSolid()) foot = b;
        }
        if (foot == null) return;
        Location loc = foot.getLocation().add(0.5, 0, 0.5);
        loc.setDirection(p.getLocation().toVector().subtract(loc.toVector()));
        Pillager d = p.getWorld().spawn(loc, Pillager.class, e -> {
            cosmetic(() -> e.setAI(false)); cosmetic(() -> e.setSilent(true)); cosmetic(() -> e.setPersistent(false));
            cosmetic(() -> e.setRemoveWhenFarAway(false)); cosmetic(() -> e.setInvulnerable(true)); cosmetic(() -> e.setPatrolLeader(false));
            cosmetic(() -> e.getEquipment().clear()); cosmetic(() -> e.setCanPickupItems(false));
            e.customName(Component.text(name, NamedTextColor.RED)); e.setCustomNameVisible(true);
        });
        s.dummies.add(d.getUniqueId());
        Target t = new Target(name);
        // round 11 (Marek: *"their guns should definitely be quite used"*): 25-60 % condition [EXAMPLE]: less damage, and they jam
        t.gunHealth = Math.max(1, SMG.maxHealth * (0.25 + 0.35 * rnd.nextDouble()));
        s.targets.put(d.getUniqueId(), t);
        d.customName(Component.text(t.label(), NamedTextColor.RED));
    }

    void stop(Player p) {
        State s = states.remove(p.getUniqueId());
        if (s == null) return;
        if (s.env) { p.hideBossBar(s.bar); p.sendMessage(Component.text("The rounds end.", NamedTextColor.AQUA)); return; }
        Fight f = s.fight;
        f.players.remove(p.getUniqueId());
        f.done.remove(p.getUniqueId());
        boolean last = players(f).isEmpty();
        if (last) {
            if (f.task != null) { f.task.cancel(); f.task = null; }
            f.pending.clear();
            if (!f.settled) settle(f, f.died.isEmpty() && f.fled.containsAll(f.members));   // all got away = slipped past
        }
        removeWall(s);   // 05c §11: the pavise goes back with him
        if (s.burning) p.setVisualFire(false);
        if (s.gunfire && roofed(p)) {   // 03b §3c: a gunfight indoors without ear protection rings the ears for an hour
            String r = pl.character(p).ring(1);
            if (!r.isEmpty()) p.sendMessage(Component.text("A gunfight under a roof: " + r + ".", NamedTextColor.GOLD));
        }
        p.hideBossBar(s.bar);
        for (Zones.Zone z : s.clouds) pl.zones.remove(z);
        p.getInventory().setContents(s.saved);
        if (s.own) { settleGear(p, s); Character ch = pl.character(p); ch.fightEndedAt = ch.now(); }   // 04j §7g: its first hour keeps him awake
        p.sendMessage(Component.text((f.them.equals("the raiders") ? "Battle demo over" : "The fight is over") + (s.own ? "; your gear is as the fight left it." : "; your inventory is back."), NamedTextColor.AQUA));
        List<ItemStack> got = f.spoils.remove(p.getUniqueId());
        if (got != null && !got.isEmpty()) {
            int left = 0;
            for (ItemStack it : got) for (ItemStack rest : p.getInventory().addItem(it).values()) left += rest.getAmount();
            p.sendMessage(Component.text("You take " + Arena.spoilsText(got) + (left > 0 ? " (" + left + " left behind: no room)" : "") + ".", NamedTextColor.GOLD));
        }
        f.onLeave.accept(p);
        if (last) {
            for (UUID u : f.dummies) { Entity e = pl.getServer().getEntity(u); if (e != null) e.remove(); }
            f.onEnd.run();
        } else if (f.started && !f.over && !f.enemyPhase) partyMaybeDone(f);   // the others need not wait for him
    }

    void stopAll() { for (UUID u : new ArrayList<>(states.keySet())) { Player p = pl.getServer().getPlayer(u); if (p != null) stop(p); } }

    private ItemStack tagged(Material m, String id, String name, String... lore) {
        List<Component> l = new ArrayList<>();
        for (String x : lore) l.add(Items.t(x));
        ItemStack it = Items.item(m, 1, Items.t(name, NamedTextColor.WHITE), l, false);
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, id);
        Icons.model(meta, Icons.slug(id));
        it.setItemMeta(meta);
        return it;
    }

    String id(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return "";
        String v = it.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return v == null ? "" : v;
    }

    private Weapon weapon(String id) {
        if (id == null) return null;
        return switch (id) { case "smg" -> SMG; case "knife" -> KNIFE; default -> id.startsWith("w:") ? GEAR.get(id.substring(2)) : null; };
    }

    /** The rounds in the weapon now. */
    static int ammoOf(State s, Weapon w) { return s.ammo.getOrDefault(w.tag, 0); }

    /**
     * The body behind a blow, a throw or a bow (05d rounds 3-4, Marek 11-25: *"strength and maybe even skill should increase the damage"*
     * · *"lets follow GURPS here, it seems more realistic"*): (Strength - 5) x the weapon's GURPS factor (a swing 1.5-2.3, a thrust
     * 0.8-1.7, a bow 0.84 by its draw; a crossbow, a chainsaw, a gun none; under 5 it takes), rounded; and the skill +1 for each full 50
     * over 50 (melee, thrown, bows) [the skill's rule mine, RULED round 4]. The numbers of `data/weapons.json` are an average person's.
     */
    static int handBonus(Character c, int[] attrs, Weapon w) {
        boolean bow = w.skill.equals("Bows");
        if (!w.melee() && !w.thrown && !bow && w.strDmg == 0) return 0;   // 05d round 7: the sling's swing counts the arm too
        int str = (int) Math.round((attrs[Rules.S] - 5) * w.strDmg);
        int per = w.skill.equals("Unarmed") ? 25 : 50;   // round 5: the fist's damage is its technique (300 = +10)
        int sk = c != null && !w.skill.isEmpty() ? (int) Math.max(0, Math.floor((weaponSkill(c, w.skill) - 50) / per)) : 0;
        return str + sk + chiDamage(c, w);
    }

    /**
     * CHI MASTER (05d round 6, Marek 11-25: *"Occultism perk for punches - add it. It should be nod to eastern martial arts being
     * presented as "magic" in the media. Adding +1 per each 25 of skill and increasing reach by 1 per every 50 points (while changing
     * the punch name ...)"*): an unarmed blow +1 a 25 points of Occultism (the hidden skill; "skill" read as Occultism), its reach +1
     * block a 50, and its name grows every 50 [the names mine].
     */
    static final String CHI = "Chi Master";
    static final String[] CHI_NAMES = {"Chi Punch", "Dragon Punch", "Tiger Palm", "Force Palm", "Hundred-Pace Fist", "Empty Hand", "Heaven-Splitting Fist"};

    static double chiLevel(Character c, Weapon w) {
        return c != null && w != null && w.skill.equals("Unarmed") && c.perks.stream().anyMatch(f -> f.name().equals(CHI)) ? Math.max(0, c.occultism()) : -1;
    }

    static int chiDamage(Character c, Weapon w) { double o = chiLevel(c, w); return o < 0 ? 0 : (int) Math.floor(o / 25); }

    static int chiReach(Character c, Weapon w) { double o = chiLevel(c, w); return o < 0 ? 0 : (int) Math.floor(o / 50); }

    /** A weapon's reach or range in blocks, the Chi Master's longer punch counted. */
    static int reach(Character c, Weapon w) { return w.range + chiReach(c, w); }

    static String chiName(double occ) { return CHI_NAMES[(int) Math.max(0, Math.min(CHI_NAMES.length - 1, Math.floor(occ / 50)))]; }

    /**
     * KAMEHAMEHA (05d round 7, Marek 11-27: *"add unlock of more AP heavy AoE version (Kamehameha) from some high Occultism level"*): the
     * Chi Master's blow as a beam from Occultism 200: along the line toward the enemy aimed at, as long as the punch's reach (the Chi
     * Master's longer reach counted), it strikes EVERYONE on it (allies too) with the punch's damage until a solid block stops it; the
     * aimed one's roll decides whether it flies true; 8 AP [the level, the AP and the beam's width mine].
     */
    static final String KAME = "Kamehameha";
    static final int KAME_OCC = 200, KAME_AP = 8;

    static boolean kamehameha(double chi) { return chi >= KAME_OCC; }

    /** The bodies on a beam from {@code eye} along {@code dir} for {@code stop} blocks: their centre within half their width + 0.5 of
     *  the line and ahead of the eye (indices into {@code mids}). */
    static List<Integer> onBeam(Vector eye, Vector dir, double stop, List<Vector> mids, List<Double> halfWidths) {
        Vector d = dir.clone().normalize();
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < mids.size(); i++) {
            Vector rel = mids.get(i).clone().subtract(eye);
            double t = rel.dot(d);
            if (t < 0.3 || t > stop + 0.5) continue;
            if (rel.clone().subtract(d.clone().multiply(t)).length() <= halfWidths.get(i) + 0.5) out.add(i);
        }
        return out;
    }

    /**
     * PUNCHES THAT SCALE (05d round 5, Marek 11-25: *"punch and unarmed weapons should be able to scale a bit ... It should not be serious
     * option, but I would still like the maxed character in unarmed (300 skill, 10 strength ...) to punch through power armor."*):
     * Unarmed's skill adds +1 a 25 points over 50 (the melee families a 50, {@link #handBonus}), and from Unarmed 150 every unarmed blow
     * PIERCES: the armour's DT a fifth, as Fallout 2's Dragon and Force Punches (`isPenetrate`, its Unarmed 115 and 130 at 6 and 8 AP;
     * here a plain blow at the gate 150) [the numbers mine]. Fists at Strength 10 and Unarmed 300: 16-17 a blow, through a power
     * armour's DT 12 / DR 40 % about 9.
     */
    static boolean piercing(Character c, Weapon w) { return c != null && w.skill.equals("Unarmed") && weaponSkill(c, "Unarmed") >= PIERCING_SKILL; }

    static final int PIERCING_SKILL = 150;

    /**
     * THE GUARD (05d round 5, Marek 11-25: *"Maybe instead add AC against melee attacks from part of his weapons skill?"*): one with a
     * melee weapon or his bare hands ready turns blows aside, +1 AC against a melee attack a 10 points of that weapon's skill over 50
     * (300 = +25, a power armour's AC); none lying, none with a gun in hand [the numbers mine].
     */
    static int guardAc(double skill) { return (int) Math.max(0, Math.floor((skill - 50) / 10)); }

    /**
     * THE UNAWARE (05d round 5, Marek 11-25: *"Hitting an unaware target: double crit chance. - yes, part of the surprise."*): caught
     * mid-talk, or in the party's ambush its first round before he was hit [the reading mine].
     */
    static boolean unaware(Fight f, Target tg) { return tg != null && (tg.surprised || f != null && f.ambush && f.round == 1 && !tg.touched); }

    /**
     * RECOIL (05d round 5, Marek 11-25: *"great idea, removing part of the burst penalty. Accepted."*): on a burst, +5 to hit a point
     * of Strength over the gun's minimum, at most +15.
     */
    static int recoilBonus(int strength, Weapon w) { return w.melee() || w.minStrength <= 0 ? 0 : Math.min(15, 5 * Math.max(0, strength - w.minStrength)); }

    /**
     * THE RELOAD (05d round 5, Marek 11-25: *"I think this should come with Guns skill over 100 instead. Agility already increases AP."*):
     * 2 AP (Fallout 2), 1 with taped magazines or a speed loader, 1 less over 100 in the gun's skill (Guns; an energy weapon's or a
     * launcher's own) [that reading mine], at least 1.
     */
    static int reloadAp(Character c, Weapon w, boolean fast) { return reloadAp(c, w, fast, false); }

    /**
     * A reload's AP (05d round 7, RULED 11-27): the weapon's own (a muzzle-loader's 20-70) or Fallout 2's 2 (1 with taped magazines or
     * a speed loader); +50 % while a black-powder gun is fouled; the skill takes **1/4 at 100, rising to 1/2 at 200, at least 1 AP**
     * (Marek: *"1/4, at least -1 ... Maybe scale it to 1/2, with at least -1 in 200 Guns"*; for a 2-AP magazine the old -1); at least 1.
     */
    static int reloadAp(Character c, Weapon w, boolean fast, boolean fouled) {
        int base = w.reload > 0 ? w.reload : (fast ? 1 : 2);
        if (fouled) base = (int) Math.ceil(base * 1.5);
        double sk = c != null && !w.skill.isEmpty() ? weaponSkill(c, w.skill) : 0;
        if (sk < 100) return base;
        double f = 0.25 + 0.25 * Math.min(1, (sk - 100) / 100.0);
        return Math.max(1, base - Math.max(1, (int) Math.floor(base * f)));
    }

    /** 05d round 7: fouled after ~10 shots (Mäll's rebuilt handgonne: the bore had to be cleaned after about ten). */
    static final int FOUL_SHOTS = 10, CLEAN_AP = 35;
    static final String CLEAN = "Clean the bore";

    static boolean fouled(State s, Weapon w) { return w.powder && s.fired.getOrDefault(w.tag, 0) >= FOUL_SHOTS; }

    /**
     * A misfire (05d round 7): a flint 1 in 6.5 (the British trials of 1834 as quoted), twice that wet; a cap 1 in 165; a match none
     * (in rain it does not fire at all, {@link #act}). {@code roll} in [0, 1).
     */
    static boolean misfire(String lock, boolean wet, double roll) {
        return switch (lock) {
            case "flint" -> roll < (wet ? 2 : 1) / 6.5;
            case "cap" -> roll < 1 / 165.0;
            default -> false;
        };
    }

    /** The next turn's AP (05d round 7, RULED: *"Max AP is needed due to stealth"*): the max, the unused AP carried (at most the max
     *  again), less a shock's; none on a lost turn. */
    static int turnAp(int apMax, int left, int shock, boolean lost) {
        return lost ? 0 : apMax - shock + Math.max(0, Math.min(left, apMax));
    }

    /** 05c §11 (RULED: *"they should work only in one direction"*): the attacker is in front of him, within the three front directions of
     *  eight (67.5 degrees either side of where he faces; Minecraft's yaw 0 = +z). */
    static boolean frontal(float yaw, double mx, double mz, double fx, double fz) {
        double r = Math.toRadians(yaw), ax = -Math.sin(r), az = Math.cos(r), dx = fx - mx, dz = fz - mz, len = Math.hypot(dx, dz);
        if (len < 1e-6) return true;
        return (ax * dx + az * dz) / len >= Math.cos(Math.toRadians(67.5)) - 1e-9;
    }

    /** What a shield does to a hit (the ammo's penetration on its DR as on armour's): {what goes on, what it stopped}. */
    static double[] shieldHit(int dt, double dr, double raw, double pen) {
        double after = Math.max(0, raw - dt) * (1 - Math.max(0, Math.min(1, (1 - pen) * dr)));
        return new double[]{after, raw - after};
    }

    /** His shield covers this attack: held (not with a two-handed weapon), whole (50 % and over), from the front. */
    private Data.Shield covering(Player p, State s, Location from) {
        if (s.shield == null || s.wall != null) return null;   // set down: a wall now ({@link #wallTake})
        Data.Shield sd = Data.SHIELDS.get(s.shield);
        Weapon held = heldWeapon(p);
        if (sd == null || held != null && held.twoHanded || s.shieldHealth < sd.health() * 0.5) return null;
        return frontal(s.faceYaw, p.getLocation().getX(), p.getLocation().getZ(), from.getX(), from.getZ()) ? sd : null;
    }

    /**
     * A hit from the front strikes his shield first (05c §11, RULED: *"everything the shield block strikes it"*): its DT and DR, the rest
     * goes on into his armour; it wears by all it stopped; under 50 % it no longer covers (it only obscures); at 0 broken. A gun's hit
     * passes a shield that is not bullet-rated (the riot shield). Returns the raw damage that goes on.
     */
    double shieldTake(Player p, State s, Character c, Location from, double raw, double pen, boolean bullet, StringBuilder log) {
        Data.Shield sd = covering(p, s, from);
        if (sd == null || bullet && sd.bulletsPass() || raw <= 0) return raw;
        double[] h = shieldHit(sd.dt(), sd.dr(), raw, pen);
        s.shieldHealth = Math.max(0, s.shieldHealth - h[1]);
        int pct = (int) Math.round(s.shieldHealth / sd.health() * 100);
        log.append(" Your ").append(sd.name()).append(" takes it: ").append(Math.round(h[1])).append(" stopped, ").append(Math.round(h[0]))
                .append(" goes on (its condition ").append(pct).append(" %").append(pct <= 0 ? ": BROKEN" : pct < 50 ? ": holed, it only obscures you now" : "").append(").");
        p.getWorld().playSound(p.getLocation(), org.bukkit.Sound.ITEM_SHIELD_BLOCK, 1f, 1f);
        return h[0];
    }

    /** 05c §11: a holed shield (under 50 %, not broken) still hides him from the front: one more "in the way" (-10, Fallout 2's). */
    int shieldObscures(Player p, State s, Location from) {
        if (s.shield == null || s.wall != null) return 0;
        Data.Shield sd = Data.SHIELDS.get(s.shield);
        Weapon held = heldWeapon(p);
        if (sd == null || held != null && held.twoHanded || s.shieldHealth <= 0 || s.shieldHealth >= sd.health() * 0.5) return 0;
        return frontal(s.faceYaw, p.getLocation().getX(), p.getLocation().getZ(), from.getX(), from.getZ()) ? 1 : 0;
    }

    // ------------------------------------------------------------------ THE PAVISE SET DOWN (05c §11)
    /** Setting a standing shield down, or taking it up again [the 4 AP RULED with the table; taking it up the same, mine]. */
    static final int PAVISE_AP = 4;

    /**
     * Behind a set-down shield [mine, EXAMPLE: a pavise ~0.6 m wide hides one man crouched or standing close behind it]: within
     * {@value #WALL_BEHIND} blocks behind its face and {@value #WALL_SIDE} to either side of its middle line.
     */
    static final double WALL_BEHIND = 2.0, WALL_SIDE = 0.7;

    static boolean behindWall(double wx, double wz, float yaw, double px, double pz) {
        double r = Math.toRadians(yaw), ax = -Math.sin(r), az = Math.cos(r), dx = px - wx, dz = pz - wz;
        double along = dx * ax + dz * az, side = Math.abs(dx * az - dz * ax);
        return along < -0.2 && along >= -WALL_BEHIND && side <= WALL_SIDE;
    }

    /** The set-down shield of the party that stands between this attacker and him: its bearer's state, or null. */
    State wallCovering(Player p, Location from) {
        State me = states.get(p.getUniqueId());
        if (me == null || me.fight == null) return null;
        for (Player q : players(me.fight)) {
            State sq = states.get(q.getUniqueId());
            if (sq == null || sq.wall == null || sq.shield == null) continue;
            Wall wl = sq.wall;
            if (behindWall(wl.x, wl.z, wl.yaw, p.getLocation().getX(), p.getLocation().getZ()) && frontal(wl.yaw, wl.x, wl.z, from.getX(), from.getZ())) return sq;
        }
        return null;
    }

    /**
     * A shot at him from the front of a set-down shield he stands behind strikes it first, as a held one (its DT and DR, the rest goes
     * on; it wears by what it stopped; under 50 % it only obscures; a gun's hit passes one that is not bullet-rated). Returns the raw
     * damage that goes on.
     */
    double wallTake(Player p, Location from, double raw, double pen, boolean bullet, StringBuilder log) {
        State sq = wallCovering(p, from);
        if (sq == null || raw <= 0) return raw;
        Data.Shield sd = Data.SHIELDS.get(sq.shield);
        if (sd == null || bullet && sd.bulletsPass() || sq.shieldHealth < sd.health() * 0.5) return raw;
        double[] h = shieldHit(sd.dt(), sd.dr(), raw, pen);
        sq.shieldHealth = Math.max(0, sq.shieldHealth - h[1]);
        int pct = (int) Math.round(sq.shieldHealth / sd.health() * 100);
        log.append(" The ").append(sd.name()).append(" set down in front of you takes it: ").append(Math.round(h[1])).append(" stopped, ").append(Math.round(h[0]))
                .append(" goes on (its condition ").append(pct).append(" %").append(pct <= 0 ? ": BROKEN" : pct < 50 ? ": holed, it only hides you now" : "").append(").");
        p.getWorld().playSound(p.getLocation(), org.bukkit.Sound.ITEM_SHIELD_BLOCK, 1f, 1f);
        return h[0];
    }

    /** A holed set-down shield (under 50 %, not broken) still hides whoever is behind it: one more "in the way". */
    int wallObscures(Player p, Location from) {
        State sq = wallCovering(p, from);
        if (sq == null) return 0;
        Data.Shield sd = Data.SHIELDS.get(sq.shield);
        return sd != null && sq.shieldHealth > 0 && sq.shieldHealth < sd.health() * 0.5 ? 1 : 0;
    }

    /** The cells of the set-down shields: the enemies walk round them. */
    Set<Path.P> wallCells(Fight f) {
        Set<Path.P> l = new HashSet<>();
        if (f != null) for (Player q : players(f)) {
            State sq = states.get(q.getUniqueId());
            if (sq != null && sq.wall != null) l.add(new Path.P((int) Math.floor(sq.wall.x), sq.wall.y, (int) Math.floor(sq.wall.z)));
        }
        return l;
    }

    /** /sheet pavise: his standing shield set down a block in front of him, facing where he looks (4 AP), or taken up again (4 AP). */
    String pavise(Player p, Character c) {
        State s = state(p);
        if (s == null || s.fight == null) return "! a pavise is set down in a fight (/sheet pavise)";
        Data.Shield sd = s.shield == null ? null : Data.SHIELDS.get(s.shield);
        if (sd == null || !sd.stands()) return "! no standing shield (a pavise) with you";
        if (s.wall != null) {
            if (Math.hypot(p.getLocation().getX() - s.wall.x, p.getLocation().getZ() - s.wall.z) > 2.5) return "! go back to your " + sd.name() + " first (within 2 blocks)";
            if (!pay(p, s, c, PAVISE_AP, "taking up the " + sd.name())) return "! not enough AP (" + PAVISE_AP + ")";
            removeWall(s);
            show(p, s, c, "the " + sd.name() + " taken up (" + PAVISE_AP + " AP)");
            return "You take up the " + sd.name() + " again (" + PAVISE_AP + " AP).";
        }
        if (s.shieldHealth <= 0) return "! your " + sd.name() + " is broken";
        Location l = p.getLocation();
        float yaw = l.getYaw();
        double r = Math.toRadians(yaw);
        Location at = l.clone().add(-Math.sin(r), 0, Math.cos(r));
        if (!at.getBlock().isPassable()) return "! no room in front of you";
        if (!pay(p, s, c, PAVISE_AP, "setting down the " + sd.name())) return "! not enough AP (" + PAVISE_AP + ")";
        s.wall = new Wall(at.getX(), at.getBlockY(), at.getZ(), yaw);
        s.wall.display = wallDisplay(at, yaw);
        Sfx.to(p, "mode.next");
        show(p, s, c, "the " + sd.name() + " set down: a wall in front of you (" + PAVISE_AP + " AP)");
        return "You set the " + sd.name() + " down in front of you (" + PAVISE_AP + " AP): whoever stands close behind it is covered from its front "
                + "(" + sd.dt() + "/" + Math.round(sd.dr() * 100) + " %, its condition " + Math.round(s.shieldHealth / sd.health() * 100) + " %); your hands are free.";
    }

    /** The set-down shield's look: planks standing on the ground (cosmetic; a server without display entities shows none). */
    private UUID wallDisplay(Location at, float yaw) {
        try {
            Location base = new Location(at.getWorld(), at.getX(), at.getBlockY(), at.getZ(), yaw, 0);
            org.bukkit.entity.BlockDisplay d = at.getWorld().spawn(base, org.bukkit.entity.BlockDisplay.class, x -> {
                x.setBlock(Material.SPRUCE_PLANKS.createBlockData());
                x.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(-0.35f, 0f, -0.08f), new org.joml.AxisAngle4f(),
                        new org.joml.Vector3f(0.7f, 1.3f, 0.16f), new org.joml.AxisAngle4f()));
            });
            return d.getUniqueId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The set-down shield back in his hands (its look gone from the field). */
    void removeWall(State s) {
        if (s.wall == null) return;
        if (s.wall.display != null) { Entity e = pl.getServer().getEntity(s.wall.display); if (e != null) e.remove(); }
        s.wall = null;
    }

    /** 05d round 7: the Ketamine dart (the old Sedative dart): asleep in 30 rounds, so it ends a hunt, not a fight [EXAMPLE]. */
    static final int SEDATIVE_ROUNDS = 30;

    /**
     * The sleep darts' ladder (`04j` §7h, Marek 11-27 night, verbatim: *"just use that drug level as base with some other generic
     * chemical to make generic T3 tranquilizer ... With effect of sleep in 3 rounds or instant with double dose (but chance to make
     * target dying if not also add Naloxone). T4 version would make safe sleep in the instant and work on even large/highly resistant
     * creatures."*): the load's name picks the dose; {@code rounds} until asleep (0 = at once), {@code upTo} the biggest body (HP) it
     * puts to sleep (0 = any), {@code risky} the double dose's breathing stage. Game numbers only [the sizes and the risk mine, EXAMPLE].
     */
    record Dose(String name, int rounds, int upTo, boolean risky) {}

    /** A body up to this many HP is a person's or smaller: the dose's own time; bigger, the time grows with it [EXAMPLE]. */
    static final int PERSON_HP = 30;
    /** Over this many HP a creature is LARGE: only the Tier IV knockout puts it to sleep (Marek: *"work on even large"*) [EXAMPLE]. */
    static final int LARGE_HP = 60;
    /** The opium dart's body: a small animal (the dogs, the ticks) [EXAMPLE]. */
    static final int SMALL_HP = 15;
    /** The double dose's breathing stage: dead in this many rounds unless naloxone reaches him [EXAMPLE]. */
    static final int BREATH_ROUNDS = 10;

    /** The dose of a dart's load ({@code "Darts|<design>"}), or null for a plain dart. */
    static Dose dose(String load) {
        if (load == null) return null;
        if (load.contains("Knockout")) return new Dose("the knockout", 0, 0, false);
        if (load.contains("Tranquilliser") && load.contains("double")) return new Dose("the double dose", 0, LARGE_HP, true);
        if (load.contains("Tranquilliser")) return new Dose("the tranquilliser", 3, LARGE_HP, false);
        if (load.contains("Ketamine") || load.contains("Sedative")) return new Dose("the ketamine", SEDATIVE_ROUNDS, LARGE_HP, false);
        if (load.contains("Opium")) return new Dose("the opium", 20, SMALL_HP, false);
        return null;
    }

    /** Rounds until the dose puts a body of {@code hpMax} to sleep: -1 = too big for it (only drowsy), 0 = at once. */
    static int sleepRounds(Dose d, int hpMax) {
        if (d.upTo() > 0 && hpMax > d.upTo()) return -1;
        if (d.rounds() == 0 || d.upTo() == 0 || hpMax <= PERSON_HP) return d.rounds();
        return (int) Math.ceil(d.rounds() * hpMax / (double) PERSON_HP);
    }

    /** The double dose's chance to stop his breathing: 25 % on a person's body, more on a smaller one, less on a bigger [EXAMPLE]. */
    static double breathRisk(int hpMax) { return Math.max(0.05, Math.min(0.75, 0.25 * PERSON_HP / (double) Math.max(1, hpMax))); }

    /** A dart's dose lands in {@code tg} at {@code round}. */
    static void dart(Target tg, Dose d, int round, Random rnd, StringBuilder log) {
        if (d == null || tg.dead || tg.asleep) return;
        int n = sleepRounds(d, tg.hpMax);
        String him = tg.melee ? "it" : "him";
        if (n < 0) {
            tg.drowsy = true;
            log.append(" ").append(cap(d.name())).append(" is too little for ").append(him).append(": only drowsy (AP −1 each turn).");
            return;
        }
        if (n == 0) {
            tg.out = true; tg.outRounds = 9999; tg.asleep = true; tg.sedatedAt = -1;
            log.append(" ").append(cap(d.name())).append(": asleep at once, out of the fight.");
            if (d.risky() && rnd.nextDouble() < breathRisk(tg.hpMax)) {
                tg.breathStopAt = round + BREATH_ROUNDS;
                log.append(" HIS BREATHING STOPS: naloxone within ").append(BREATH_ROUNDS).append(" rounds, or ").append(tg.melee ? "it dies" : "he dies").append(".");
            }
            return;
        }
        if (tg.sedatedAt < 0 || round + n < tg.sedatedAt) {
            tg.sedatedAt = round + n;
            log.append(" ").append(cap(d.name())).append(" is in ").append(him).append(": asleep in ").append(n).append(" rounds, unless the fight ends first.");
        }
    }

    private static String cap(String s) { return s.isEmpty() ? s : java.lang.Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    /** 05d round 7: tearing free of the bolas or the net, a turn: d100 <= 10 x Strength (a jaw trap harder by its hold). */
    static boolean tearsFree(int strength, int d100) { return tearsFree(strength, d100, 0); }

    static boolean tearsFree(int strength, int d100, int hold) { return d100 <= 10 * strength - hold; }

    // ------------------------------------------------------------------ THE TRAPS (05d §7c, RULED rounds 7-8)
    /** A walker spots a trap on d100 <= 10 x Perception + 1/2 his skill of its kind - 1/2 the setter's + its spot modifier. */
    static boolean spots(int perception, double walkerSkill, double setterSkill, int spotMod, int d100) {
        return d100 <= 10 * perception + walkerSkill / 2 - setterSkill / 2 + spotMod;
    }

    /** Inside a directional mine's cone: within its range and half its angle either side of the way it faces (yaw 0 = +z). */
    static boolean inCone(float yaw, double tx, double tz, double x, double z, int coneDeg, int range) {
        double dx = x - tx, dz = z - tz, d = Math.hypot(dx, dz);
        if (d > range + 0.5 || d < 1e-6) return d < 1e-6;
        double r = Math.toRadians(yaw);
        return (-Math.sin(r) * dx + Math.cos(r) * dz) / d >= Math.cos(Math.toRadians(coneDeg / 2.0)) - 1e-9;
    }

    /** A trap of the party's on this cell, not sprung. */
    static Trap trapAt(Fight f, int x, int y, int z) {
        if (f == null) return null;
        for (Trap t : f.traps) if (!t.sprung && t.x == x && t.y == y && t.z == z) return t;
        return null;
    }

    /** The cells of the traps the enemies have spotted: they walk round them. */
    static Set<Path.P> knownTraps(Fight f) {
        Set<Path.P> l = new HashSet<>();
        if (f != null) for (Trap t : f.traps) if (!t.sprung && t.known) l.add(new Path.P(t.x, t.y, t.z));
        return l;
    }

    /** /sheet trap [name]: sets the first trap of his own (or the one named) on the block he looks at, within 2 blocks, its AP. */
    String setTrap(Player p, Character c, String[] args) {
        State s = state(p);
        if (s == null || s.fight == null) return "! traps are set in a fight (/sheet trap); a pit or a deadfall is dug before one (the encounter's menu: Dig)";
        String want = args.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).toLowerCase(Locale.ROOT) : "";
        Production.Design pick = null;
        for (ItemStack it : s.saved) {
            Production.Design d = Items.made(Items.id(it));
            if (d == null || !Data.TRAPS.containsKey(d.name) || !want.isEmpty() && !d.name.toLowerCase(Locale.ROOT).startsWith(want)) continue;
            int have = 0;
            for (ItemStack x : s.saved) { Production.Design e = Items.made(Items.id(x)); if (e != null && e.key().equals(d.key())) have += x.getAmount(); }
            if (have > s.used.getOrDefault(d.key(), 0)) { pick = d; break; }
        }
        if (pick == null) return "! no trap of yours left to set" + (want.isEmpty() ? "" : " named " + want);
        Data.TrapDef def = Data.TRAPS.get(pick.name);
        if (def.ap() < 0) return "! a " + def.name() + " is dug before a fight, not in one (the encounter's menu: Dig, before an ambush or a hunt)";
        RayTraceResult r = p.rayTraceBlocks(3, FluidCollisionMode.NEVER);
        if (r == null || r.getHitBlock() == null || r.getHitPosition().distance(p.getEyeLocation().toVector()) > 3.5) return "! look at the ground within 2 blocks";
        org.bukkit.block.Block cell = r.getHitBlock().getRelative(org.bukkit.block.BlockFace.UP);
        if (!cell.isPassable()) return "! no room there";
        if (trapAt(s.fight, cell.getX(), cell.getY(), cell.getZ()) != null) return "! a trap is there already";
        if (!pay(p, s, c, def.ap(), "setting the " + def.name())) return "! not enough AP (" + def.ap() + ")";
        s.used.merge(pick.key(), 1, Integer::sum);
        double skill = weaponSkill(c, def.kind());
        s.fight.traps.add(new Trap(def, cell.getX(), cell.getY(), cell.getZ(), p.getUniqueId(), skill, p.getLocation().getYaw()));
        p.spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER, cell.getLocation().add(0.5, 0.2, 0.5), 6, 0.3, 0.1, 0.3, 0);
        Sfx.to(p, "mode.next");
        show(p, s, c, "the " + def.name() + " is set (" + def.ap() + " AP; your " + def.kind() + " " + Math.round(skill) + ")");
        return "Set the " + def.name() + " at " + cell.getX() + " " + cell.getY() + " " + cell.getZ() + " (" + def.kind() + " " + Math.round(skill) + ").";
    }

    /** An enemy walks onto a trap he did not spot: it springs on him. */
    void spring(Player p, State s, Trap tr, LivingEntity le, Target t) {
        if (tr.sprung || t.dead) return;
        Data.TrapDef d = tr.def;
        tr.sprung = !d.effect().equals("noise") && !d.effect().equals("alert");   // an alarm sounds again
        Fight f = s.fight;
        StringBuilder log = new StringBuilder("YOUR " + d.name().toUpperCase(Locale.ROOT) + " springs on " + t.name + ": ");
        Location at = new Location(le.getWorld(), tr.x + 0.5, tr.y, tr.z + 0.5);
        switch (d.effect()) {
            case "held", "fall", "knock", "cut" -> {
                int dmg = d.max() <= 0 ? 0 : Combat.damage(d.min() + rnd.nextInt(d.max() - d.min() + 1), 1, 0, d.pen(), t.dt, t.dr, d.pen() >= 0.8);
                log.append(dmg).append(" HP");
                if (dmg > 0) hurt(le, t, dmg, Combat.critEffects(Rules.Part.LEFT_LEG, 0, t.a), false, 0, log);
                if (!t.dead && d.effect().equals("held")) { t.entangled = 1; t.hold = d.hold(); log.append("; HELD by a leg (he tears free on d100 <= 10 x Strength").append(d.hold() > 0 ? " - " + d.hold() : "").append(")"); }
                if (!t.dead && d.effect().equals("knock")) { t.down = true; log.append("; knocked down"); }
                if (!t.dead && d.effect().equals("fall")) { t.down = true; t.inPit = true; log.append("; in the pit (his next turn goes on climbing out)"); }
            }
            case "blast" -> { log.append("it blows."); explode(p, s, pl.character(p), at, le, 0, log, d.min(), d.max()); }
            case "cone" -> {
                log.append("the steel balls sweep the cone:");
                for (Map.Entry<UUID, Target> en : f.targets.entrySet()) {
                    Entity e = pl.getServer().getEntity(en.getKey());
                    Target x = en.getValue();
                    if (x.dead || !(e instanceof LivingEntity xe) || !inCone(tr.yaw, at.getX(), at.getZ(), xe.getLocation().getX(), xe.getLocation().getZ(), d.cone(), d.range())) continue;
                    int dmg = Combat.damage(d.min() + rnd.nextInt(d.max() - d.min() + 1), 1, 0, 0, x.dt, x.dr, false);
                    log.append(" ").append(x.name).append(" ").append(dmg).append(" HP;");
                    hurt(xe, x, dmg, Combat.critEffects(Rules.Part.TORSO, 0, x.a), false, 0, log);
                }
                at.getWorld().spawnParticle(org.bukkit.Particle.EXPLOSION, at, 1);
                Sfx.at(at, "explosion");
                flash(at);
            }
            case "shock" -> {
                int zap = Combat.damage(d.shockMin() + rnd.nextInt(Math.max(1, d.shockMax() - d.shockMin() + 1)), 1, 0, 0, 0, 0, false);
                log.append(zap).append(" Electrical");
                hurt(le, t, zap, Combat.critEffects(Rules.Part.LEFT_LEG, 0, t.a), false, 0, log);
                if (!t.dead) log.append(shock(t, zap, machine(t), le.isInWaterOrRain(), d100));
            }
            default -> {   // noise, alert: he is seen where he is for a round
                le.setGlowing(true);
                pl.getServer().getScheduler().runTaskLater(pl, () -> le.setGlowing(false), 20L * 6);
                log.append(d.effect().equals("noise") ? "it rattles: he is there." : "a silent alert on your radio: he is there.");
            }
        }
        tell(f, log.toString(), NamedTextColor.GOLD);
    }

    /** A long action's payment (05d round 7: *"very long actions ... in parts"*): {pay now, paid after}; done when paid after = need. */
    static int[] partPay(int need, int done, int ap) {
        int pay = Math.max(0, Math.min(ap, need - done));
        return new int[]{pay, done + pay};
    }

    /** A reload, in parts across turns when it is longer than the AP at hand. */
    private void reloadStep(Player p, State s, Character c, Weapon w) {
        int need = reloadAp(c, w, Accessories.fastReload(s.acc.get(w.tag)), fouled(s, w));
        String key = "reload:" + w.tag;
        int[] pp = partPay(need, s.progress.getOrDefault(key, 0), s.ap);
        if (pp[0] <= 0) { Sfx.to(p, "ap.lacking"); show(p, s, c, "no AP left: the reload goes on next turn (" + pp[1] + " of " + need + " AP)"); return; }
        if (!pay(p, s, c, pp[0], "reloading")) return;
        Sfx.at(p.getLocation(), "pistol.reload");
        if (pp[1] < need) { s.progress.put(key, pp[1]); show(p, s, c, "reloading the " + w.name + ": " + pp[1] + " of " + need + " AP (it goes on next turn)"); return; }
        s.progress.remove(key);
        reload(s, w);
        show(p, s, c, "reloaded the " + w.name + " (" + need + " AP)");
    }

    /** Cleaning a fouled black-powder bore: 35 AP, in parts; the shots counted from nothing again. */
    private void cleanStep(Player p, State s, Character c, Weapon w) {
        String key = "clean:" + w.tag;
        int[] pp = partPay(CLEAN_AP, s.progress.getOrDefault(key, 0), s.ap);
        if (pp[0] <= 0) { Sfx.to(p, "ap.lacking"); show(p, s, c, "no AP left: the cleaning goes on next turn (" + pp[1] + " of " + CLEAN_AP + " AP)"); return; }
        if (!pay(p, s, c, pp[0], "cleaning")) return;
        if (pp[1] < CLEAN_AP) { s.progress.put(key, pp[1]); show(p, s, c, "cleaning the " + w.name + ": " + pp[1] + " of " + CLEAN_AP + " AP"); return; }
        s.progress.remove(key);
        s.fired.remove(w.tag);
        s.mode.put(w.name, 0);
        show(p, s, c, "the " + w.name + "'s bore is clean (" + CLEAN_AP + " AP)");
    }

    /**
     * THE SHOCK ON A PLAYER (05d round 5, Marek 11-25: *"Should players be shocked too, once robots with electric arms arrive? - yes."*;
     * the rule of §2e): the Electrical damage that passed his armour's Isolation, x1.5 wet; he endures it on d100 <= End x 10 + 20 - 10 x
     * the damage; not endured: that many AP (at least 2) less next turn, 8+ knocks him down (Stonewall keeps him up). Returns the log.
     */
    String shockPlayer(Player p, State s, Character c, int passed, boolean wet) {
        int e = (int) Math.round(passed * (wet ? 1.5 : 1));
        if (e <= 0) return "";
        int need = c.attrs(false)[Rules.E] * 10 + 20 - 10 * e, roll = d100.getAsInt();
        if (roll <= need) return " The shock: " + e + " Electrical, you endure it (d100 " + roll + " <= " + need + ").";
        int lose = Math.max(2, e);
        s.apLoss = Math.max(s.apLoss, lose);
        boolean down = e >= 8 && !c.has("Stonewall");
        if (down) { s.legs.clear(); s.legs.add(new Leg(2, p.getLocation().toBlockLocation())); }
        return " The shock: " + e + " Electrical, you do not endure it (d100 " + roll + " > " + Math.max(0, need) + "): AP -" + lose + " next turn"
                + (down ? ", knocked down" : "") + ".";
    }

    /**
     * THE SHOCK (05d §2e, round 4, Marek: *"The effect should not arrive always, just when it passes armor, does enough lightning damage
     * and the target does not endure it. Try to make it somewhat realistic."*) [the numbers mine]: the Electrical damage that passed the
     * armour's Isolation (a weapon's arc jumps cloth: an NPC's clothes stop none) x2 on a robot or drone, x1.5 wet; a living target
     * endures it on d100 <= End x 10 + 20 - 10 x the damage (a strong current locks the muscles whoever you are); not endured: it loses
     * that many AP next turn (at least 2), and 8+ knocks it down; a machine has no roll: 4+ resets it the same way. Returns the log's part.
     */
    static String shock(Target t, int passed, boolean machine, boolean wet, java.util.function.IntSupplier d100) {
        int e = (int) Math.round(passed * (machine ? 2 : 1) * (wet ? 1.5 : 1));
        if (e <= 0) return "";
        boolean hit;
        String why;
        if (machine) { hit = e >= 4; why = hit ? "its electronics reset" : "it shrugs it off"; }
        else {
            int need = t.a[Rules.E] * 10 + 20 - 10 * e, roll = d100.getAsInt();
            hit = roll > need;
            why = hit ? "he does not endure it (d100 " + roll + " > " + Math.max(0, need) + ")" : "he endures it (d100 " + roll + " <= " + need + ")";
        }
        if (!hit) return " The shock: " + e + " Electrical, " + why + ".";
        t.apLoss = Math.max(t.apLoss, Math.max(2, e));
        if (e >= 8) t.down = true;
        return " The shock: " + e + " Electrical, " + why + ": AP -" + Math.max(2, e) + " next turn" + (e >= 8 ? ", knocked down" : "") + ".";
    }

    static boolean machine(Target t) { String k = t.kind.toLowerCase(Locale.ROOT); return k.contains("robot") || k.contains("drone"); }

    /** The magazine with its accessory (05d §5: an extended magazine x2, a drum x3). */
    static int cap(State s, Weapon w) { return (w.cell > 0 ? w.cell : w.magazine) * Accessories.magazine(s.acc.get(w.tag)); }

    /** An accessory's own attack chosen as the mode (05d §5: the bayonet, the launcher): its weapon, or null. */
    static Weapon accessoryWeapon(State s, Weapon w, Mode m) {
        for (String at : Accessories.attacks(s.acc.get(w.tag)))
            if (m.name().equals(at) || (at.equals("Under-barrel launcher") && m.name().startsWith("Launcher"))) return GEAR.get(at);
        return null;
    }

    /** The rounds he carries of a category (its calibre): every load of it; Power cells by the shots. */
    static int reserveOf(State s, String kind) {
        int n = s.reserve.getOrDefault(kind, 0);
        for (Map.Entry<String, Integer> e : s.reserve.entrySet()) if (e.getKey().startsWith(kind + "|")) n += e.getValue();
        return n;
    }

    /** The load in the magazine, for the lines (" of 9x19 JHP"), or "". */
    static String loadName(State s, Weapon w) {
        String k = s.load.get(w.tag);
        return k == null ? "" : " " + k.substring(k.indexOf('|') + 1);
    }

    /** The load's design in the weapon's magazine, or null (the demo, Power, a load the data lost). */
    static Production.Design loaded(State s, Weapon w) {
        String k = s.load.get(w.tag);
        if (k == null) return null;
        int i = k.indexOf('|');
        return Production.design(k.substring(0, i), k.substring(i + 1));
    }

    /** The ammo's damage modifier and penetration of a shot: his own load's (05d round 1), else the weapon's own (the demo). */
    static double[] ammoMods(State s, Weapon w) {
        if (w.melee() || w.thrown) return new double[]{0, w.pen};
        Production.Design d = s.own ? loaded(s, w) : null;
        return d != null && d.dmg != null ? new double[]{d.dmg, d.pen != null ? d.pen : 0} : new double[]{w.ammoDamage, w.ammoPenetration};
    }

    /**
     * Loads the weapon: the demo's from nothing; his own from the rounds he carries of its calibre (a thrown weapon never reloads):
     * one load a magazine, the one in it while he has more of it, else the load he carries most of [mine]; Power cells by the shots.
     */
    static boolean reload(State s, Weapon w) {
        if (!s.own) { s.ammo.put(w.tag, cap(s, w)); return true; }
        if (w.thrown) return false;
        String key = w.ammoKind;
        if (s.reserve.getOrDefault(key, 0) <= 0) {
            String in = s.load.get(w.tag);
            key = in != null && s.reserve.getOrDefault(in, 0) > 0 ? in : null;
            if (key == null) {
                int most = 0;
                for (Map.Entry<String, Integer> e : s.reserve.entrySet())
                    if (e.getKey().startsWith(w.ammoKind + "|") && e.getValue() > most) { most = e.getValue(); key = e.getKey(); }
            }
        }
        if (key == null) return false;
        int per = w.cell > 0 ? CELL_DRAW : 1;   // 05d round 7: a blade's blow draws more than a shot
        int have = s.reserve.getOrDefault(key, 0), n = Math.min(cap(s, w) - ammoOf(s, w), have / per);
        if (n <= 0) return false;
        s.reserve.put(key, have - n * per);
        s.ammo.put(w.tag, ammoOf(s, w) + n);
        if (key.contains("|")) s.load.put(w.tag, key);
        return true;
    }

    private static boolean quickItem(String id) { return id.equals("stim") || id.equals("grenade") || id.equals("gasgren") || id.equals("flare"); }

    // ------------------------------------------------------------------ the belt (Marek 11-27, verbatim: *"it should be possible for players
    // to change what is on their belts by getting it from inventory."*)
    /** The belt's quick slots of the hotbar (slots 6-7 wait for a belt or bandolier item). */
    static final int BELT_FIRST = 2, BELT_LAST = 5;
    /** Fallout 2: using an item in a fight, 2 AP (its Stimpak's). */
    static final int BELT_AP = 2;

    static boolean onBelt(int slot) { return slot >= BELT_FIRST && slot <= BELT_LAST; }

    /**
     * A real item the player put on a belt slot from his pack (the pack opened for 4 AP, Fallout 2's): right click uses it on himself
     * for {@value #BELT_AP} AP: the Super Stimpak and the Hypo (`Meds.inject`), a chem (its dose), a medicine taken by oneself (the Blood
     * Pack, antivenom, the salts, Virex ...). One of the stack is used; the fight's end takes it from his gear (`settleGear`). The
     * Stimpak keeps its own belt slot (counted at the start), so a Stimpak stack moved there is not a second supply.
     */
    String useBelt(Player p, State s, Character c, int slot) {
        ItemStack it = p.getInventory().getItem(slot);
        String iid = Items.id(it);
        if (iid == null || it == null) { show(p, s, c, null); return null; }
        String med = Meds.name(iid), what;
        if (iid.equals("gear:Stimpak")) { show(p, s, c, "the Stimpaks are on your belt already (their own slot)"); return null; }
        if (!beltUsable(iid)) { show(p, s, c, Items.title(iid) + " is not used from the belt"); return null; }
        boolean injector = med != null && Meds.INJECTORS.contains(med), self = med != null && (Meds.SELF.contains(med) || Meds.VACCINES.containsKey(med));
        if (!pay(p, s, c, BELT_AP, Items.title(iid))) return null;
        if (injector) what = Meds.inject(c, med, rnd);
        else if (self) what = Meds.useSelf(c, med, rnd);
        else what = c.dose(Data.CHEMS.get(iid.substring(5)), rnd);
        Sfx.at(p.getLocation(), injector ? "stimpak" : "chem");
        it.setAmount(it.getAmount() - 1);
        p.getInventory().setItem(slot, it.getAmount() > 0 ? it : null);
        s.used.merge("item:" + iid, 1, Integer::sum);
        show(p, s, c, what + " (" + BELT_AP + " AP)");
        return what;
    }

    /** Cateye's flash (04i §6a): every player with Cateye in his eyes who sees a blast within this many blocks [mine]. */
    static final double FLASH_RANGE = 32;

    void flash(Location at) {
        if (at.getWorld() == null) return;
        for (Player pp : at.getWorld().getPlayers()) {
            if (pp.getLocation().distanceSquared(at) > FLASH_RANGE * FLASH_RANGE) continue;
            Character ch = pl.character(pp);
            if (ch.other("light_sensitive") <= 0) continue;
            Location eye = pp.getEyeLocation();
            Vector to = at.clone().add(0, 0.5, 0).toVector().subtract(eye.toVector());
            if (to.lengthSquared() > 1e-6 && at.getWorld().rayTraceBlocks(eye, to.clone().normalize(), to.length(), FluidCollisionMode.NEVER, true) != null) continue;
            ch.flashUntil = ch.now() + 1;
            pp.sendMessage(Component.text("The blast's flash in your Cateye eyes: Perception −2 for an hour.", NamedTextColor.GOLD));
        }
    }

    // ------------------------------------------------------------------ movement: legs since the last action
    private double stepCost(int stance, Character c) { return Rules.stepAp(stance, Math.max(c.brokenLegs, c.missingLegs()), c.has("Brittle Bones")) * c.legFactor() * c.loadMoveFactor(); }   // round 11: overloaded, half speed

    private static int blocks(Location a, Location b) { return Math.abs(a.getBlockX() - b.getBlockX()) + Math.abs(a.getBlockZ() - b.getBlockZ()); }

    /** The AP the movement since the last action costs, if the player stood at {@code at}: each leg's walk + its stance change. */
    private double spent(State s, Character c, Location at) {
        double sum = 0;
        for (int k = 0; k < s.legs.size(); k++) {
            Leg l = s.legs.get(k);
            Location end = k + 1 < s.legs.size() ? s.legs.get(k + 1).from() : at;
            sum += blocks(l.from(), end) * stepCost(l.stance(), c);
            if (k > 0) sum += Rules.stanceChangeAp(s.legs.get(k - 1).stance(), l.stance());
        }
        return sum;
    }

    /** Moved since the last action (for the shooting-after-moving penalty): any leg walked at least a block. */
    private boolean moved(State s, Location at) {
        for (int k = 0; k < s.legs.size(); k++) {
            Location end = k + 1 < s.legs.size() ? s.legs.get(k + 1).from() : at;
            if (blocks(s.legs.get(k).from(), end) > 0) return true;
        }
        return false;
    }

    /** AP now = AP at the anchor − the movement's cost rounded up (half an AP a block standing: two blocks for one AP). */
    private void setAp(State s, Character c, Location at) {
        s.ap = (int) Math.floor(s.apAtAnchor - spent(s, c, at) + 1e-9);
        if (!moved(s, at)) s.steadied = false;
    }

    /** The modes in order; after a move, "Steady (1 AP)" comes first (Marek 11-14: the optional 1 AP to steady after moving). */
    private List<Mode> modes(State s, Weapon w, Location at) {
        List<Mode> l = new ArrayList<>();
        if (moved(s, at) && !s.steadied && !w.melee()) l.add(new Mode("Steady yourself", 1, 0, 0, false));
        for (Mode x : w.modes)   // 05d round 6: the Chi Master's punches by their names
            l.add(s.chi >= 0 && w.skill.equals("Unarmed") && !x.aimed()
                    ? new Mode(x.name().equals("Thrust") ? chiName(s.chi) + ", focused" : chiName(s.chi), x.ap(), x.toHit(), x.ammo(), x.aimed()) : x);
        if (w.skill.equals("Unarmed") && kamehameha(s.chi)) l.add(new Mode(KAME, KAME_AP, 0, 0, false));   // 05d round 7
        if (fouled(s, w)) l.add(new Mode(CLEAN + " (" + CLEAN_AP + " AP)", CLEAN_AP, 0, 0, false));   // 05d round 7: black powder
        for (String x : Accessories.attacks(s.acc.get(w.tag)))   // 05d §5: the bayonet, the launcher as modes of the gun
            l.add(x.equals("Under-barrel launcher") ? new Mode("Launcher (40 mm)", 6, 0, 1, false) : new Mode(x, 4, 0, 0, false));
        return l;
    }

    private Mode mode(State s, Weapon w, Location at) {
        List<Mode> ms = modes(s, w, at);
        return ms.get(Math.min(s.mode.getOrDefault(w.name, 0), ms.size() - 1));
    }

    private String stanceLine(State s, Character c) {
        int st = s.stance();
        StringBuilder b = new StringBuilder(Rules.STANCES[st] + ": " + Txt.f(stepCost(st, c)) + " AP a block");
        if (st == 1) b.append(" · AC +2 · ranged +5 · melee −20");
        if (st == 2) b.append(" · AC +4 · ranged +15 · no melee · +20 to be hit at short range");
        if (c.brokenLegs > 0) b.append(" · ").append(c.brokenLegs).append(c.brokenLegs == 1 ? " leg" : " legs").append(" broken");
        return b.toString();
    }

    /** Shows the boss bar and the action bar now; an event's message stays in the action bar for 2.5 s. */
    void show(Player p, State s, Character c, String event) {
        long now = System.currentTimeMillis();
        if (event != null) { s.lastEvent = event; s.lastEventAt = now; }
        Component bar = bar(p, s, c);
        p.sendActionBar(bar);
        s.lastBarText = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(bar);
        s.lastShown = now;
    }

    private Component bar(Player p, State s, Character c) {
        Location at = p.getLocation().toBlockLocation();
        String event = System.currentTimeMillis() - s.lastEventAt < 2500 ? s.lastEvent : null;
        s.bar.name(Component.text("Round " + s.round + (s.env ? " · " + (s.hold && !c.envHarm() ? s.holdWhy : envWhat(c)).toUpperCase(java.util.Locale.ROOT) : "") + (s.fight == null || s.fight.over ? "" : s.fight.enemyPhase ? " · " + s.fight.them.toUpperCase(java.util.Locale.ROOT) + "' TURN" : s.enemyTurn ? " · WAITING FOR THE OTHERS" : "") + " · HP " + c.hp + " / " + c.hpMax()
                + " · AP " + s.ap + " / " + c.apMax() + " · " + stanceLine(s, c) + (s.blind ? " · blind" : "")));
        s.bar.progress(Math.max(0f, Math.min(1f, s.ap / (float) Math.max(1, c.apMax()))));
        String id = id(p.getInventory().getItemInMainHand());
        Weapon w = weapon(id);
        Component bar;
        if (w != null) {
            s.chi = chiLevel(c, w);
            Mode m = mode(s, w, at);
            int pen = movePenalty(s, w, at);
            Aim a = aim(p, reach(c, w));
            String look = a == null ? (w.melee() ? "no enemy within reach" : "no enemy in your sights")
                    : a.part() == null ? who(a.target()) + ": aim at the body"
                    : who(a.target()) + " · " + a.part().label + (m.aimed() ? " (" + sign(a.part().toHit) + ")" : "") + " · " + Txt.f(Math.round(a.dist() * 10) / 10.0) + " bl."
                    + " · " + Combat.toHit(shot(p, s, c, w, m, a, pen)).chance() + " %";
            bar = Component.text(w.name + ": " + m.name() + " · " + apOf(c, w, m) + " AP", NamedTextColor.WHITE)
                    .append(Component.text(m.toHit() != 0 ? " · to hit " + sign(m.toHit()) : "", NamedTextColor.YELLOW))
                    .append(Component.text(pen != 0 ? " · moved " + pen + " (steady: 1 AP)" : "", NamedTextColor.RED))
                    .append(Component.text(!w.melee() ? (w.thrown ? " · " + ammoOf(s, w) + " to throw" : " · ammo " + ammoOf(s, w) + "/" + cap(s, w) + (s.own ? loadName(s, w) + " (+" + reserveOf(s, w.ammoKind) + ")" : "")) : "", NamedTextColor.GRAY))
                    .append(Component.text(s.jammed.contains(w.name) ? " · JAMMED (fire: clear it, " + apOf(c, w, m) + " AP)" : "", NamedTextColor.RED))
                    .append(Component.text(" · cond " + Math.round(cond(s, w) * 100) + " %" + (Combat.broken(health(s, w)) ? " BROKEN" : ""),
                            cond(s, w) >= 0.75 ? NamedTextColor.GRAY : NamedTextColor.GOLD))
                    .append(Component.text("  » " + look, a != null && a.part() != null ? NamedTextColor.GOLD : NamedTextColor.DARK_GRAY));
        } else if (id.equals("grenade") || id.equals("flare")) {
            int range = throwRange(c);
            Aim a = aim(p, range);
            bar = Component.text((id.equals("grenade") ? "Frag grenade · 4 AP" : "Flare · 2 AP") + " · range " + range, NamedTextColor.WHITE)
                    .append(Component.text("  » " + (a != null && a.part() != null ? who(a.target()) + " · " + Txt.f(Math.round(a.dist() * 10) / 10.0) + " bl."
                            : "at the ground you look at"), NamedTextColor.GOLD));
        } else if (s.env) bar = Component.text(Rules.STANCES[s.stance()] + " · AP " + s.ap + " · Oxygen " + Math.round(c.survival.getOrDefault(Rules.Stat.OXYGEN, 0.0))
                + "/" + Math.round(c.max(Rules.Stat.OXYGEN)) + " · " + envWhat(c) + " · the Pip-Boy: act, end the round", NamedTextColor.GOLD);
        else bar = Component.text(Rules.STANCES[s.stance()] + " · AP " + s.ap, NamedTextColor.AQUA);
        if (event != null) bar = Component.text(event + "  ", NamedTextColor.GREEN).append(bar);
        return bar;
    }

    static String sign(int v) { return (v > 0 ? "+" : v < 0 ? "−" : "±") + Math.abs(v); }

    private int movePenalty(State s, Weapon w, Location at) {
        return moved(s, at) && !s.steadied && !w.melee() ? (w.twoHanded ? -20 : -10) : 0;
    }

    /** An action: pays its AP; the movement before it can no longer be refunded (Marek 11-13). */
    private boolean pay(Player p, State s, Character c, int ap, String what) {
        if (s.ap < ap) { Sfx.to(p, "ap.lacking"); show(p, s, c, "not enough AP for " + what + " (" + ap + ")"); return false; }
        s.ap -= ap;
        int st = s.stance();
        s.legs.clear();
        s.legs.add(new Leg(st, p.getLocation().toBlockLocation()));
        s.apAtAnchor = s.ap;
        return true;
    }

    // ------------------------------------------------------------------ aiming
    /** The enemy under the crosshair, the body part the crosshair is on (null = beside the body) and the distance. */
    record Aim(LivingEntity target, Rules.Part part, double dist) {}

    /**
     * Looks along the crosshair up to {@code range} blocks (walls stop it; players are never targets: one side). The ray is 0.3 wide,
     * so it catches the arms, which stick out of the mob's hitbox; the aimed point is where the ray passes the target's middle, read
     * against Minecraft's own body model ({@link Rules#part}).
     */
    private Aim aim(Player p, double range) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        RayTraceResult r = p.getWorld().rayTrace(eye, dir, range, FluidCollisionMode.NEVER, true, 0.3,
                e -> e instanceof LivingEntity le && !(e instanceof Player) && !le.isDead() && Anomalies.seenBy(e, p));   // another's mirage figure is not there for him
        if (r == null || !(r.getHitEntity() instanceof LivingEntity t)) return null;
        Location f = t.getLocation();
        double ox = eye.getX() - f.getX(), oz = eye.getZ() - f.getZ();
        double dd = dir.getX() * dir.getX() + dir.getZ() * dir.getZ();
        double k = dd < 1e-9 ? 0 : -(ox * dir.getX() + oz * dir.getZ()) / dd;
        double px = eye.getX() + dir.getX() * k, py = eye.getY() + dir.getY() * k, pz = eye.getZ() + dir.getZ() * k;
        double height = t.getHeight();
        double yaw = Math.toRadians(orElse(t::getBodyYaw, () -> t.getLocation().getYaw()));
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);   // where it faces (Minecraft: yaw 0 = south, +z)
        double rx = -fz, rz = fx;                         // its own right (facing south, the right hand is west)
        double lat = ((px - f.getX()) * rx + (pz - f.getZ()) * rz) / height;
        double h = (py - f.getY()) / height;
        double tl = Math.hypot(ox, oz);
        boolean facing = tl > 1e-6 && (fx * ox + fz * oz) / tl > 0.5;
        return new Aim(t, Rules.part(h, lat, facing), eye.distance(new Location(eye.getWorld(), px, py, pz)));
    }

    private static String who(Entity e) {
        Component n = e.customName();
        return n != null ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(n) : e.getType().name().toLowerCase(Locale.ROOT);
    }

    private double skillOf(Character c, String skill) { return weaponSkill(c, skill); }

    /** A weapon skill: one of the 15 or a combination skill (Knives, Hammers ...). */
    static double weaponSkill(Character c, String skill) {
        if (Rules.SKILLS.stream().anyMatch(x -> x.name().equals(skill))) return c.skill(skill);
        return Rules.COMBOS.stream().filter(x -> x.name().equals(skill)).findFirst().map(x -> x.value().applyAsDouble(c.skills())).orElse(0.0);
    }

    /** Left click with a weapon: steady, or an attack at the enemy under the crosshair (none = refused, no AP spent). */
    private void act(Player p, State s, Character c, Weapon w) {
        Location at = p.getLocation().toBlockLocation();
        s.chi = chiLevel(c, w);
        Mode m = mode(s, w, at);
        if (m.name().startsWith("Steady")) {
            if (pay(p, s, c, 1, "steadying")) { Sfx.to(p, "steady"); s.steadied = true; s.mode.put(w.name, 0); show(p, s, c, "steadied: no moving penalty"); }
            return;
        }
        if (m.name().startsWith(CLEAN)) { cleanStep(p, s, c, w); return; }   // 05d round 7
        Weapon sub = accessoryWeapon(s, w, m);   // 05d §5: the bayonet or the launcher on this gun
        if (sub != null) { w = sub; m = sub.modes.get(0); }
        if (w.melee() && s.stance() == 2) { show(p, s, c, "prone: no melee attacks (the stance table)"); return; }
        Aim a = aim(p, reach(c, w));
        if (a == null) {
            Sfx.to(p, "ap.lacking");
            show(p, s, c, !w.melee() ? "no enemy in your sights (range " + w.range + "): aim at one" : "no enemy within reach: step next to one");
            return;
        }
        if (a.part() == null) { Sfx.to(p, "ap.lacking"); show(p, s, c, "the crosshair is beside " + who(a.target()) + ": aim at the body"); return; }
        if (pl.anomalies.hidden(a.target().getLocation(), p.getLocation())) {   // 09c §2: unseen from outside a blind spot (only area attacks reach him)
            Sfx.to(p, "ap.lacking"); show(p, s, c, "you cannot see him: he is inside a blind spot (only a grenade's blast reaches him)"); return;
        }
        if (w.cell > 0 && ammoOf(s, w) <= 0) {   // 05d round 7: the cell is spent: this click swaps it (a reload's AP)
            if (s.own && reserveOf(s, w.ammoKind) <= 0) { show(p, s, c, "the " + w.name + "'s cell is spent and you carry no other " + w.ammoKind); return; }
            int rap = reloadAp(c, w, false);
            if (pay(p, s, c, rap, "swapping the cell")) { reload(s, w); Sfx.at(p.getLocation(), "pistol.reload"); show(p, s, c, "a fresh cell (" + rap + " AP): " + ammoOf(s, w) + " blows"); }
            return;
        }
        if (!w.melee() && !w.thrown && ammoOf(s, w) == 0 && (!s.own || reserveOf(s, w.ammoKind) > 0)) { reloadStep(p, s, c, w); return; }   // 05d round 7
        if (!w.melee() && ammoOf(s, w) < m.ammo()) { Sfx.at(p.getLocation(), "pistol.empty"); show(p, s, c, w.thrown ? "none left to throw" : "not enough ammo for " + m.name() + (s.own ? " (" + reserveOf(s, w.ammoKind) + " " + w.ammoKind + " left)" : "")); return; }
        if (s.broken.contains(w.name)) { show(p, s, c, "the " + w.name + " is destroyed (a critical failure)"); return; }
        if (s.dropped.contains(w.name)) {   // a critical failure dropped it: picking it up is an action [2 AP mine]
            if (pay(p, s, c, 2, "picking the weapon up")) { Sfx.at(p.getLocation(), "weapon.pickup"); s.dropped.remove(w.name); show(p, s, c, "picked the " + w.name + " up again (2 AP)"); }
            return;
        }
        if (Combat.broken(health(s, w))) { show(p, s, c, "the " + w.name + " is broken: /sheet repair (a kit) first"); return; }
        if (!w.melee() && s.jammed.contains(w.name)) {   // 05 §9 (round 11): firing a jammed gun is the clearing check (JA2), the attack's AP
            if (!pay(p, s, c, apOf(c, w, m), "clearing the jam")) return;
            int d = d100.getAsInt(), need = Combat.clearJam(c.skill("Crafting"));
            if (d <= need) { s.jammed.remove(w.name); Sfx.at(p.getLocation(), "pistol.reload"); show(p, s, c, "the jam cleared (d100 " + d + " ≤ " + need + ": 30 + Crafting / 2)"); }
            else { Sfx.at(p.getLocation(), "pistol.empty"); show(p, s, c, "still jammed (d100 " + d + " > " + need + ": 30 + Crafting / 2)"); }
            return;
        }
        if (s.fight != null && s.fight.talk != null && s.ap >= apOf(c, w, m)) breakTalk(s.fight, p);
        if (s.fight != null && s.ap >= apOf(c, w, m)) {
            Target ta = s.fight.targets.get(a.target().getUniqueId());
            if (ta != null && ta.ally) betray(s.fight, ta.group, p);
            if (ta != null && neutral(s.fight, ta)) provoke(s.fight, ta.group, p);
        }
        int pen = movePenalty(s, w, at);
        Combat.Shot sh = shot(p, s, c, w, m, a, pen);
        Combat.ToHit th = Combat.toHit(sh);
        if (th.outOfRange()) { Sfx.to(p, "ap.lacking"); show(p, s, c, "out of range (" + w.range + " blocks)"); return; }
        if (w.lock.equals("match") && p.isInWaterOrRain()) { Sfx.to(p, "ap.lacking"); show(p, s, c, "the match is out in the wet: the " + w.name + " cannot fire"); return; }
        if (!pay(p, s, c, apOf(c, w, m), m.name())) return;
        if (misfire(w.lock, p.isInWaterOrRain(), rnd.nextDouble())) {   // 05d round 7: a flash in the pan; the charge stays in
            Sfx.at(p.getLocation(), "pistol.empty");
            p.getWorld().spawnParticle(org.bukkit.Particle.SMOKE, p.getEyeLocation(), 8, 0.2, 0.2, 0.2, 0.01);
            show(p, s, c, "MISFIRE: a flash in the pan, the " + w.name + " stays loaded (" + apOf(c, w, m) + " AP)");
            return;
        }
        Sfx.at(p.getLocation(), w.melee() ? (m.name().startsWith("Thrust") ? "knife.thrust" : "knife.swing") : w.skill.equals("Bows") ? "bow.shot" : m.ammo() > 1 ? "pistol.burst" : "pistol.single");
        int[] me = c.attrs(false);
        boolean off = unaware(s.fight, s.targets.get(a.target().getUniqueId()));
        double cc = Rules.critChance(me, c.skill("Sneak")) * (off ? 2 : 1);   // 05d round 5: the unaware
        int better = c.perks.stream().anyMatch(f -> f.name().equals("Better Criticals")) ? 20 : 0;
        int pt = Combat.partTerm(a.part(), m.aimed(), sh.blocks);
        Combat.Roll r = Combat.roll(th.chance(), cc, pt, me[Rules.L], better, jinxed(c), d100);
        Target tg = s.targets.get(a.target().getUniqueId());
        boolean burst = !w.melee() && m.ammo() > 1;
        // the jam (05 §9, round 11): which round sticks; those before it leave the barrel, the stuck one is lost (JA2)
        int jamRound = w.melee() || signature(c, w) ? -1 : Combat.jamAt(cond(s, w), m.ammo(), d100, jamExtra(c));
        int shots = jamRound < 0 ? m.ammo() : jamRound, spent = jamRound < 0 ? m.ammo() : jamRound + 1;
        if (jamRound >= 0) s.jammed.add(w.name);
        StringBuilder log = new StringBuilder();
        log.append(!w.melee() ? "Fired at " : "Struck at ").append(tg != null ? tg.name : who(a.target())).append(", ").append(sh.blocks).append(" blocks: ")
                .append(m.name()).append(m.aimed() ? " at the " + a.part().label : "").append(" (").append(apOf(c, w, m)).append(" AP). To hit ")
                .append(th.chance()).append(" % =");
        final String wskill = w.skill;
        th.terms().forEach((k, v) -> log.append(" ").append(k.equals("skill") ? wskill + " " + v : sign(v) + " " + k));
        log.append(". Rolled ").append(r.d100()).append(r.d100() >= 100 ? " (a 100 always misses)" : "").append(": ");
        String result;
        if (!w.melee() && shots == 0) {
            log.append("JAMMED: the round sticks in the chamber, nothing fires (the round lost; firing again tries to clear it, 30 + Crafting / 2).");
            result = "JAMMED";
            Sfx.at(p.getLocation(), "pistol.empty");
        } else if (Anomalies.figure(a.target())) {   // 09c §2, round 10: a mirage's figure; the shot or the blow is spent on nothing
            log.append("it goes through ").append(who(a.target())).append(": NOTHING IS THERE (a figure of the mirage).");
            result = (w.melee() ? "a blow" : "a shot") + " at nothing: the mirage";
            pl.anomalies.dissolve(a.target());
        } else if (r.critFail()) {
            Combat.Failure f = Combat.critFailure(r.degree(), Combat.fireWeapon(w.damageType));
            String what = f.names().isEmpty() ? "nothing worse than a miss" : String.join(", ", f.names());
            log.append("CRITICAL FAILURE (degree ").append(r.degree()).append("): ").append(what).append(".");
            result = "critical failure: " + what;
            critFailure(p, s, c, w, f, log);
            Sfx.at(p.getLocation(), "crit.fail");
            Narrator.fumble(p.getLocation(), f, c.female);
        } else if (m.name().equals(KAME)) {   // 05d round 7: the beam strikes everyone on its line
            result = beam(p, s, c, w, a, r, me, log);
            if (!inBattle(p)) { p.sendMessage(Component.text(log.toString(), NamedTextColor.GRAY)); return; }
        } else if (w.blastMax > 0) {   // a rocket: it explodes where it lands (on him if it hit, 1-3 blocks off if it missed)
            Location boom = a.target().getLocation().toBlockLocation();
            if (!r.hit()) boom.add(rnd.nextInt(5) - 2, 0, rnd.nextInt(5) - 2);
            log.append(r.hit() ? (r.crit() ? "CRITICAL HIT" : "HIT") + ": it bursts on him." : "MISS: it bursts beside him.");
            result = r.hit() ? "the rocket hit" : "the rocket missed";
            explode(p, s, c, boom, r.hit() ? a.target() : null, r.crit() ? perkDegree(c, w, r.degree()) : 0, log, w.blastMin, w.blastMax);
            if (!inBattle(p)) { p.sendMessage(Component.text(log.toString(), NamedTextColor.GRAY)); return; }
        } else {
            int hits;
            String spray = "";
            if (burst) {   // Fallout 2's spray: one roll for the burst; each round at the target rolls again (+20 on a crit)
                int[] sp = Combat.burstSplit(shots);
                int acc = th.chance() + (r.crit() ? 20 : 0);
                hits = 0;
                for (int i = 0; i < sp[0]; i++) if (d100.getAsInt() <= acc) hits++;
                spray = " " + hits + " of " + sp[0] + " round(s) at him hit; " + (sp[1] + sp[2] + sp[3]) + " flew past (" + sp[1] + " on along the line, "
                        + sp[2] + " left, " + sp[3] + " right).";
                if (!r.hit()) hits = 0;
            } else hits = r.hit() ? 1 : 0;
            if (hits == 0) {
                log.append(r.hit() ? "the burst missed him." : "MISS.").append(spray);
                result = "missed";
                Sfx.at(a.target().getLocation(), w.melee() ? "melee.miss" : "bullet.miss");
            } else {
                Rules.Part part = m.aimed() ? a.part() : Rules.Part.TORSO;   // F2: an unaimed shot uses the uncalled (torso) table
                int deg = r.crit() ? perkDegree(c, w, r.degree()) : 0;
                Combat.CritEffects fx = Combat.critEffects(part, deg, tg != null ? tg.a : new int[]{5, 5, 5, 5, 5, 5, 5});
                double mult = Combat.critMultiplier(part, deg);
                int total = 0;
                List<Integer> each = new ArrayList<>();
                StringBuilder shocks = new StringBuilder();   // 05d §2e: what the shocks did
                for (int i = 0; i < hits; i++) {
                    double base = (w.minDmg + rnd.nextInt(w.maxDmg - w.minDmg + 1) + handBonus(c, me, w)) * Combat.conditionDamage(cond(s, w))
                            * perkDamage(c, w, tg) * (w.melee() ? Rules.meleeFactor(c.tpl().bonusDamage()) : 1);   // round 15: the body's melee damage
                    double[] am = ammoMods(s, w);
                    boolean arc = w.damageType.equals("Electrical");   // 05d §2e: through the Isolation, not the armour (an NPC's: none yet)
                    int dtv = tg != null && !arc ? tg.dt : 0;
                    if (piercing(c, w)) dtv = (int) Math.round(dtv / 5.0);   // round 5: Unarmed 150 pierces (Fallout 2's Force Punch)
                    int dmg = Combat.damage(Math.max(1, base), mult, am[0], am[1], dtv, tg != null && !arc ? tg.dr : 0, fx.bypass() || w.pierce);
                    if (w.maxDmg <= 0) dmg = 0;   // 05d round 7: the net does no harm, it entangles
                    int elec = arc ? dmg : 0;
                    if (w.shockMax > 0) { int zap = w.shockMin + rnd.nextInt(w.shockMax - w.shockMin + 1); dmg += zap; elec += zap; }   // round 3
                    if (elec > 0 && tg != null && !tg.dead) shocks.append(shock(tg, elec, machine(tg), a.target().isInWaterOrRain(), d100));
                    each.add(dmg);
                    total += dmg;
                }
                log.append(r.crit() ? "CRITICAL HIT, degree " + deg + " (x" + Txt.f(mult) + ")" : "HIT").append(spray).append(" Damage ")
                        .append(total).append(hits > 1 ? " " + each : "").append(" (").append(w.minDmg).append("-").append(w.maxDmg)
                        .append(handBonus(c, me, w) != 0 ? (handBonus(c, me, w) > 0 ? " + " : " - ") + Math.abs(handBonus(c, me, w)) + " Strength and skill" : "")
                        .append(w.shockMax > 0 ? " + the shock " + w.shockMin + "-" + w.shockMax + " Electrical" : "")
                        .append(w.melee() ? "" : ", " + w.ammo)
                        .append(Combat.conditionDamage(cond(s, w)) < 1 ? "; x" + Txt.f(Math.round(Combat.conditionDamage(cond(s, w)) * 100) / 100.0) + " for its condition" : "")
                        .append(perkDamage(c, w, tg) > 1 ? "; x" + Txt.f(perkDamage(c, w, tg)) + (master(c, w) ? " Weapon Master" : "") + (nemesis(c, tg) ? " Nemesis" : "") : "")
                        .append("; DT ").append(tg != null ? tg.dt : 0)
                        .append(", DR ").append(tg != null ? Math.round(tg.dr * 100) : 0).append(" %").append(fx.bypass() ? ", through the armour" : "").append(").");
                if (!fx.names().isEmpty()) log.append(" The crit: ").append(String.join(", ", fx.names())).append(".");
                log.append(shocks);
                result = (r.crit() ? "CRIT " : "hit ") + total + " HP";
                Sfx.at(a.target().getLocation(), w.melee() ? "knife.flesh" : "bullet.flesh");
                if (r.crit()) {   // the hit's own sound and the narrator (Marek 11-19: "Just make crits have normal sound + the narrator speach")
                    Narrator.crit(a.target().getLocation(), part, fx, false);   // the raiders are men (the workbook's Nájezdník)
                }
                if (tg != null) {
                    hurt(a.target(), tg, total, fx, r.crit(), deg, log);
                    if (!tg.dead && !w.entangle.isEmpty()) {   // 05d round 7: the bolas the legs, the net the whole body
                        tg.entangled = Math.max(tg.entangled, w.entangle.equals("body") ? 2 : 1);
                        log.append(" ENTANGLED (").append(tg.entangled == 2 ? "the net" : "the bolas").append("): his turns go on tearing free (d100 <= 10 x Strength).");
                    }
                    if (!tg.dead && w.ammoKind.equals("Darts"))   // the sleep darts' ladder (`04j` §7h)
                        dart(tg, dose(s.load.getOrDefault(w.tag, "")), s.fight != null ? s.fight.round : s.round, rnd, log);
                    int kb = Combat.knockback(total);
                    if (kb > 0 && !tg.dead) knockback(p.getLocation(), a.target(), kb, log);
                    result += " · " + tg.label().substring(tg.name.length() + 3);
                }
            }
        }
        if (!w.melee()) {
            double h = health(s, w);
            if (w.wears && !Combat.broken(h) && !signature(c, w)) {   // FNV: 0.2 item health a round fired; a Signature Weapon never breaks
                Production.Design ld = s.own ? loaded(s, w) : null;   // 05d round 1: black-powder loads foul x3
                s.health.put(w.name, Math.max(1, h - Combat.WEAR_PER_ROUND * spent * (ld != null ? ld.foul : 1)));
                if (Combat.broken(health(s, w))) log.append(" The ").append(w.name).append(" breaks from wear (its last health point): /sheet repair.");
            }
            s.ammo.put(w.tag, Math.max(0, ammoOf(s, w) - spent));
            if (w.powder && spent > 0) {   // 05d round 7: black powder fouls the bore and hangs a white cloud over the shooter for the round
                s.fired.merge(w.tag, spent, Integer::sum);
                s.smokeRound = s.round;
                p.getWorld().spawnParticle(org.bukkit.Particle.CAMPFIRE_COSY_SMOKE, p.getLocation().add(0, 1.4, 0), 25, 0.6, 0.5, 0.6, 0.01);
                if (s.fired.get(w.tag) == FOUL_SHOTS) log.append(" The bore is FOULED: each reload +50 % AP until it is cleaned (a mode, ").append(CLEAN_AP).append(" AP).");
            }
            if (s.own) s.used.merge(w.thrown ? "thrown:" + (w.throwsName.isEmpty() ? w.name : w.throwsName) : s.load.getOrDefault(w.tag, w.ammoKind), spent, Integer::sum);
            if (jamRound > 0) { log.append(" THE GUN JAMS after ").append(shots).append(" round(s), the next one lost (firing again tries to clear it)."); result += " · JAMMED"; }
            if (ammoOf(s, w) <= 0 && !s.broken.contains(w.name) && !w.thrown) {   // reload automatic when out of ammo (Marek 11-14), 2 AP (Fallout 2)
                int rap = reloadAp(c, w, Accessories.fastReload(s.acc.get(w.tag)), fouled(s, w));   // 05d §5 taped magazines, a speed loader; round 5 the skill
                if (s.ap >= rap && (!s.own || reserveOf(s, w.ammoKind) > 0)) { s.ap -= rap; s.apAtAnchor = s.ap; reload(s, w); log.append(" Reloaded automatically (" + rap + " AP)."); Sfx.at(p.getLocation(), "pistol.reload"); }
                else log.append(s.own && reserveOf(s, w.ammoKind) <= 0 ? " Empty, and no " + w.ammoKind + " left."
                        : " Empty: click it to reload (" + rap + " AP" + (rap > c.apMax() ? ", over several turns" : "") + ").");
            }
            if (!Accessories.quiet(s.acc.get(w.tag))) s.gunfire = true;   // 05d §5: a suppressor
            Rules.Gas here = c.gasHere();
            if (here != null && here.ignites()) {   // 03b §3c: a spark or a flame sets it off, a blast as a grenade's over the pocket
                log.append(" The muzzle flash sets off the ").append(here.name()).append("!");
                burnAway(p, c);   // it burns away [mine]
                explode(p, s, c, p.getLocation().toBlockLocation(), null, 0, log);
                if (!inBattle(p)) { p.sendMessage(Component.text(log.toString(), NamedTextColor.GRAY)); return; }
            }
        }
        if (w.cell > 0) {   // 05d round 7: each blow drains the cell
            s.ammo.put(w.tag, Math.max(0, ammoOf(s, w) - 1));
            if (s.own) s.used.merge(s.load.getOrDefault(w.tag, w.ammoKind), CELL_DRAW, Integer::sum);
            if (ammoOf(s, w) <= 0) log.append(" The cell is spent: the next click swaps it (").append(reloadAp(c, w, false)).append(" AP).");
        }
        p.sendMessage(Component.text(log.toString(), NamedTextColor.GRAY));
        show(p, s, c, result);
        checkOver(p, s, c);
    }

    /** The shot's inputs from the world: the distance, the light at the target, who stands in the way. */
    private Combat.Shot shot(Player p, State s, Character c, Weapon w, Mode m, Aim a, int pen) {
        int[] me = c.attrs(false);
        Target tg = s.targets.get(a.target().getUniqueId());
        Combat.Shot sh = new Combat.Shot();
        sh.skill = skillOf(c, w.skill) + m.toHit();
        sh.blocks = Math.max(1, (int) Math.round(a.dist()));
        sh.perception = me[Rules.P];
        sh.strength = me[Rules.S];
        sh.minStrength = w.minStrength;
        sh.weaponPerk = Accessories.perk(w.perk, s.acc.get(w.tag));   // 05d §5: a scope, a long barrel
        sh.melee = w.melee();
        sh.aimed = m.aimed();
        sh.part = a.part();
        sh.lightLevel = a.target().getEyeLocation().getBlock().getLightLevel();
        sh.blind = s.blind;
        // a target on the ground lies like a crawling one: the prone +4 AC (Marek's Tactics note) and +20 within reach (Combat)
        sh.targetAc = tg != null ? tg.ac + (tg.lying() ? Rules.STANCE_AC[2] : tg.stance == 1 ? Rules.STANCE_AC[1] : 0) : 0;
        sh.targetDown = tg != null && tg.lying();
        sh.obstacles = w.melee() ? 0 : inTheWay(p, a);
        sh.huge = a.target().getWidth() > 1.4;
        sh.stance = s.stance();
        sh.senses = c.senses();
        sh.extra.put("prosthetic arm", c.armToHit(w.twoHanded));
        if (s.shield != null && s.wall == null && !w.twoHanded && Data.SHIELDS.containsKey(s.shield)) {   // 05c §11: a shield too heavy for him
            int shortOf = Data.SHIELDS.get(s.shield).minStr() - me[Rules.S];
            if (shortOf > 0) sh.extra.put("shield too heavy", -20 * shortOf);
        }
        Map<String, String> fit = s.acc.get(w.tag);   // 05d §5: the sights, the bipod
        if (Accessories.night(fit)) sh.senses = Math.max(sh.senses, 2);
        if (Accessories.toHit(fit) != 0) sh.extra.put("sight", Accessories.toHit(fit));
        if (Accessories.bipod(fit) && m.ammo() > 1 && !moved(s, p.getLocation().toBlockLocation())) sh.extra.put("bipod", 10);
        if (m.ammo() > 1 && recoilBonus(me[Rules.S], w) > 0) sh.extra.put("Strength over the minimum", recoilBonus(me[Rules.S], w));   // round 5
        if (w.melee() && !w.noGuard && tg != null && !tg.lying() && tg.gun != null && tg.gun.melee() && guardAc(tg.skill) > 0)   // 05d round 7: not a flail
            sh.extra.put("his guard", -guardAc(tg.skill));   // round 5: his blade turns blows aside
        if (signature(c, w)) sh.extra.put("Signature Weapon", 15);
        if (nemesis(c, tg)) sh.extra.put("Nemesis", 15);
        if (tg != null && tg.surprised) sh.extra.put("caught mid-talk", TALK_SURPRISE);
        if (m.aimed()) sh.extra.put("aimed (targeting eyes, Steady, propranolol)", (int) Math.round(c.other("aimed")));
        if (c.other("to_hit") != 0) sh.extra.put("Twitchy", (int) Math.round(c.other("to_hit")));   // the trait as a gene (03c §8)
        int gasHit = c.gasPenalty()[1];
        if (gasHit != 0) sh.extra.put(c.gasHere() != null ? c.gasHere().name() : "riot gas", gasHit);   // smoke −10, riot gas −20 (03b §3c)
        veil(pl.zones, p.getEyeLocation(), a.target().getEyeLocation(), sh, w.melee());
        sh.movePenalty = pen;
        sh.range = reach(c, w);
        return sh;
    }

    /**
     * A hiding cloud on the line of fire (the Trhač's pollen, smoke, the acid fog; Zones#veil): its to-hit on a ranged shot, under the
     * cloud's name. The shooter's own smoke around his eyes is the same cover seen from the other side, so the worst counts, once.
     */
    static void veil(Zones zones, Location from, Location to, Combat.Shot sh, boolean melee) {
        if (melee || zones == null) return;
        Zones.Zone z = zones.veil(from, to);
        if (z == null) return;
        int v = Rules.OBSCURES.get(z.gas) * z.strength;
        for (String g : Rules.OBSCURES.keySet()) {
            Integer own = sh.extra.remove(g);
            if (own != null) v = Math.min(v, own);
        }
        sh.extra.put(z.gas, v);
    }

    /** Fallout 2: −10 for each creature in the line of fire (players count: a friend in the way is in the way). */
    private int inTheWay(Player p, Aim a) {
        Location eye = p.getEyeLocation();
        Vector to = a.target().getEyeLocation().toVector().subtract(eye.toVector());
        double len = to.length();
        if (len < 1e-6) return 0;
        Vector dir = to.clone().normalize();
        int n = 0;
        for (Entity e : p.getWorld().getNearbyEntities(eye.clone().add(to.clone().multiply(0.5)), len / 2 + 1, len / 2 + 1, len / 2 + 1)) {
            if (e.equals(p) || e.equals(a.target()) || !(e instanceof LivingEntity le) || le.isDead() || Anomalies.figure(e)) continue;
            RayTraceResult hit = e.getBoundingBox().rayTrace(eye.toVector(), dir, len - 0.3);
            if (hit != null) n++;
        }
        return n;
    }

    /** Damage, the crit's effects and the wound on a target dummy; its name shows the state. */
    void hurt(LivingEntity e, Target t, int dmg, Combat.CritEffects fx, boolean crit, int deg, StringBuilder log) {
        t.touched = true; t.byParty = true;   // a danger's own hit puts both back (Raiders.cross)
        t.hp -= dmg;
        if (fx.knockedDown()) t.down = true;
        if (fx.knockedOut()) { t.out = true; t.outRounds = 1; }
        if (fx.blinded()) t.blind = true;
        if (fx.crippledArm()) t.armCrippled = true;
        if (fx.crippledLeg()) t.legCrippled = true;
        if (crit && dmg > 0) {   // a crit of normal damage opens a wound (the workbook's Blood note); strong from degree 4 or a cripple [mine]
            boolean strong = deg >= 4 || fx.crippledArm() || fx.crippledLeg();
            double before = t.bleeding;
            t.bleeding = Rules.bleedingPerRound(t.bleeding, strong);
            log.append(" He bleeds ").append(Txt.f(t.bleeding)).append(" Blood a round").append(before > 0 ? " (was " + Txt.f(before) + ")" : "").append(".");
        }
        if (fx.dies() || fx.heartStops() || t.hp < -t.a[Rules.E]) {   // a raider has no clinical death: a stopped heart ends him (03b §3c)
            t.dead = true;
            log.append(fx.dies() ? " Killed outright." : fx.heartStops() ? " His heart stops (" + fx.heart() + ")." : " Dead (below −" + t.a[Rules.E] + " HP, his −Endurance).");
        } else if (t.dying()) log.append(" Dying (0 to −").append(t.a[Rules.E]).append(" HP).");
        label(e, t);
        // the raiders are men (Fallout 2's HMXXXX sounds)
        if (!t.dead && !t.dying() && (fx.knockedDown() || fx.knockedOut())) Sfx.fall(e.getLocation(), "down.male", false);
        else Sfx.at(e.getLocation(), t.dead ? "death.male" : t.dying() ? "dying.male" : "hurt.male");
    }

    void label(LivingEntity e, Target t) {
        e.customName(Component.text(t.label(), t.dead ? NamedTextColor.DARK_GRAY : t.dying() ? NamedTextColor.GOLD : t.ally ? NamedTextColor.GREEN
                : t.talking ? NamedTextColor.YELLOW : NamedTextColor.RED));
        if (t.dead) { e.setInvulnerable(false); e.setHealth(0); }
    }

    /** Fallout 2's knockback (melee, unarmed, explosions): the target slides back while the blocks behind him are free. */
    void knockback(Location from, LivingEntity e, int blocks, StringBuilder log) {
        Sfx.fall(e.getLocation(), "knockback", e instanceof Player pk && pl.character(pk).female);
        Vector dir = e.getLocation().toVector().subtract(from.toVector()).setY(0);
        if (dir.lengthSquared() < 1e-6) return;
        dir.normalize();
        Location to = e.getLocation();
        int moved = 0;
        for (int i = 0; i < blocks; i++) {
            Location n = to.clone().add(dir);
            if (!n.getBlock().isPassable() || !n.clone().add(0, 1, 0).getBlock().isPassable()) break;
            to = n;
            moved++;
        }
        if (moved > 0) { e.teleport(to); log.append(" ").append(name(e)).append(" knocked back ").append(moved).append(" block(s)."); }
    }

    double health(State s, Weapon w) { return s.health.getOrDefault(w.name, w.maxHealth); }

    /** The condition, 0 to 1 (FNV: item health / its maximum). */
    double cond(State s, Weapon w) { return health(s, w) / w.maxHealth; }

    /** Can this raider still fight? Not dead, not dying, a gun that is neither destroyed nor broken (knocked out he comes to). */
    static boolean canFight(Target t) { return !t.dead && !t.dying() && !t.broken && !Combat.broken(t.gunHealth); }

    /**
     * Marek 11-14: *"Just I would end the combat when enemies cannot fight."* (Fallout 2 ends it when nobody outside the player's
     * side is left in it, `_combat_should_end`): when no raider can fight, the fight is over and the demo ends 2 s later.
     * @return true when it is over
     */
    boolean checkOver(Player p, State s, Character c) {
        if (s.env) return false;
        Fight f = s.fight;
        if (f.over) return true;
        for (Target t : f.targets.values()) if (!t.outOfIt() && !t.ally && !neutral(f, t)) return false;
        f.over = true;
        List<Player> ps = players(f);
        if (!ps.contains(p)) ps.add(p);
        List<String> why = new ArrayList<>();
        for (Target t : f.targets.values())
            why.add(t.name + ": " + (t.escaped ? "got away" : t.dead ? "dead" : t.dying() ? "dying" : t.asleep ? "asleep" : t.out ? "knocked out"
                    : t.entangled == 2 ? "in the net" : t.broken ? "gun destroyed" : "gun broken"));
        boolean alive = false;
        for (Target t : f.targets.values()) if (!t.dead && !t.escaped && !t.ally) alive = true;
        String text = "The fight is over: " + (f.them.equals("the raiders") ? "no raider" : "no enemy") + " can fight (" + String.join("; ", why) + "). Round " + f.round + "."
                + (alive && f.field != null ? " The living ones surrender: on the field take them alive, or finish them." : "");
        for (Player q : ps) {
            State sq = states.get(q.getUniqueId());
            if (sq != null) { sq.enemyTurn = true; sq.over = true; }   // freezes the controls until the end
            Sfx.to(q, "combat.end");
            q.sendMessage(Component.text(text, NamedTextColor.GREEN));
        }
        settle(f, false);
        for (Player q : ps) { State sq = states.get(q.getUniqueId()); if (sq != null) show(q, sq, pl.character(q), "the fight is over"); }
        pl.getServer().getScheduler().runTaskLater(pl, () -> {
            for (Player q : ps) if (inBattle(q) && states.get(q.getUniqueId()).fight == f) stop(q);
        }, 40L);
        return true;
    }

    /**
     * /sheet repair: a weapon repair kit on the weapon in hand (FNV: +10 to +30 % condition by the repair skill; Mr. Fixit x2).
     * Our repair skill for a gun is Crafting (the workbook: *"crafting of non-electric and mostly simple items ... some weapons"*;
     * Engineering for electric ones). In battle it costs 4 AP [EXAMPLE: FNV is real-time].
     */
    String repair(Player p, Character c) {
        State s = states.get(p.getUniqueId());
        if (s == null) return "the demo's weapons exist only in the battle demo (/sheet battle)";
        if (s.enemyTurn) return "the raiders' turn: wait";
        Weapon w = heldWeapon(p);
        if (w == null) return "take the weapon to repair in hand";
        if (s.broken.contains(w.name)) return "the " + w.name + " is destroyed: nothing to repair";
        if (s.kits <= 0) return "no weapon repair kit left";
        double before = cond(s, w) * 100;
        if (before >= 100 - 1e-9) return "the " + w.name + " is in perfect condition";
        if (!pay(p, s, c, 4, "the repair kit")) return "not enough AP for the repair kit (4)";
        double skill = c.skill("Crafting");
        boolean fixit = c.perks.stream().anyMatch(f -> f.name().equals("Mr. Fixit"));
        double after = Combat.kitRepair(before, skill, fixit);
        s.health.put(w.name, after / 100 * w.maxHealth);
        s.kits--;
        Sfx.at(p.getLocation(), "repair");
        show(p, s, c, "repaired: " + Math.round(before) + " → " + Math.round(after) + " %");
        return "Weapon repair kit (4 AP): the " + w.name + " " + Math.round(before) + " % → " + Math.round(after) + " % (Crafting " + Math.round(skill)
                + (fixit ? ", Mr. Fixit x2" : "") + "); " + s.kits + " kit(s) left";
    }

    /** Jinxed: anyone in the battle with the trait makes everyone's misses fumble half the time (Fallout 2). */
    boolean jinxed(Character c) { return c.has("Jinxed"); }

    /** The weapon in the player's main hand, or null. */
    Weapon heldWeapon(Player p) { return weapon(id(p.getInventory().getItemInMainHand())); }

    /** The player died in the demo: the battle ends and the character is given back as it began (a demo death is not kept). */
    void died(Player p, State s, Character c, boolean outright) {
        Sfx.at(p.getLocation(), Sfx.body(outright || c.dead ? "death" : "dying", c.female));
        if (!outright && !c.dead) Sfx.to(p, "heart.stop");
        if (s.env) {
            p.sendMessage(Component.text((c.dead ? "DEAD." : "CLINICAL DEATH" + (c.arrestCause.isEmpty() ? "" : " (" + c.arrestCause + ")") + ": " + clinical(c)), NamedTextColor.DARK_RED));
            stop(p);
            return;
        }
        s.fight.died.add(p.getUniqueId());
        if (players(s.fight).size() <= 1) {
            if (s.fight.task != null) { s.fight.task.cancel(); s.fight.task = null; }
            s.fight.pending.clear();
        }
        p.sendMessage(Component.text((outright ? "FINAL DEATH: killed outright (the head or most of the body destroyed)."
                : (c.arrestCause.isEmpty() ? "CLINICAL DEATH (below −" + c.attrs(false)[Rules.E] + " HP): " : "CLINICAL DEATH (" + c.arrestCause + ", HP kept): ")
                + clinical(c))
                + (s.fight.demo ? " The demo ends; your character is restored as it began." : " The fight ends for you: it is real (your own gear): the others can still save you (part 3's revival)."), NamedTextColor.DARK_RED));
        if (s.fight.demo) {
            c.hp = s.hp0;
            c.bleeding = s.bleeding0;
            c.brokenLegs = s.legs0;
            c.survival.putAll(s.survival0);
            c.arrestAt = -1; c.arrestCause = ""; c.cprFrom = -1; c.oxygenZeroAt = -1;
        }
        stop(p);
    }

    /** What clinical death means for this template (03b §5, ACCEPTED 11-16). */
    static String clinical(Character c) {
        Data.Template t = c.tpl();
        if (t.atDeath().equals("torpor")) return "TORPOR, not death: no window while the body is whole and not burned; raise the HP above −Endurance and warm it (a revival roll with no time penalty).";
        if (t.atDeath().equals("self-mend")) return "the revenant shuts down and mends itself, 1 HP a day, waking above −Endurance with 10 % of its XP lost; a medic can speed it with a revival roll.";
        double w = c.deathWindow();
        return "the brain is harmed from " + Txt.f(5 * w) + " minutes and final death comes at ~" + Math.round(14.3 * w) + " minutes untouched, ~"
                + Math.round(33.3 * w) + " with CPR from the start (the cold doubles it). /sheet revive <Medicine> <minutes> [CPR minutes] <hands|adrenaline|defibrillator|icu>";
    }

    private String name(Entity e) {
        for (State s : states.values()) { Target t = s.targets.get(e.getUniqueId()); if (t != null) return t.name; }
        return e instanceof Player pp ? pp.getName() : who(e);
    }

    /**
     * The sheet's critical failure list (RULED 11-14) applied to the shooter: the weapon damaged lowers its condition (Marek 11-14;
     * the amount EXAMPLE), the fire only from a fire weapon (Marek 11-14; Fallout 2's flamers' row).
     */
    private void critFailure(Player p, State s, Character c, Weapon w, Combat.Failure f, StringBuilder log) {
        if (f.losesAmmo() && !w.melee() && !w.thrown) { if (s.own) s.used.merge(s.load.getOrDefault(w.tag, w.ammoKind), ammoOf(s, w), Integer::sum); s.ammo.put(w.tag, 0); log.append(" The magazine is empty."); }
        if (f.dropsWeapon()) { Sfx.at(p.getLocation(), "weapon.drop"); s.dropped.add(w.name); log.append(" Left click again to pick it up (2 AP)."); }
        if ((f.weaponDamaged() || f.weaponDestroyed()) && signature(c, w)) log.append(" Your Signature Weapon takes no harm.");
        else if (f.weaponDamaged()) {
            s.health.put(w.name, Combat.damaged(health(s, w), w.maxHealth));
            log.append(" The ").append(w.name).append(" is damaged: condition ").append(Math.round(cond(s, w) * 100)).append(" % (a tenth of its health, EXAMPLE)")
                    .append(Combat.broken(health(s, w)) ? ", BROKEN: /sheet repair." : ".");
        }
        if (f.weaponDestroyed() && !signature(c, w)) { s.broken.add(w.name); log.append(" The " + w.name + " is destroyed."); }
        if (f.hitsRandom()) {
            List<UUID> others = new ArrayList<>(s.dummies);
            Collections.shuffle(others, rnd);
            for (UUID u : others) {
                Entity e = pl.getServer().getEntity(u);
                Target t = s.targets.get(u);
                if (e instanceof LivingEntity le && t != null && !t.dead) {
                    double[] am = ammoMods(s, w);
                    int dmg = Combat.damage(w.minDmg + rnd.nextInt(w.maxDmg - w.minDmg + 1), 1, w.melee() ? 0 : am[0], w.melee() ? 0 : am[1], t.dt, t.dr, false);
                    log.append(" It hit ").append(t.name).append(" for ").append(dmg).append(" HP.");
                    hurt(le, t, dmg, Combat.critEffects(Rules.Part.TORSO, 0, t.a), false, 0, log);
                    break;
                }
            }
        }
        if (f.hurt1d6()) { int d = 1 + rnd.nextInt(6); c.hp -= d; log.append(" You take ").append(d).append(" HP (").append(c.hp).append(" left)."); }
        if (f.hitsSelf()) {
            int base = w.minDmg + rnd.nextInt(w.maxDmg - w.minDmg + 1) + handBonus(c, c.attrs(false), w);
            double[] am = ammoMods(s, w);
            int dmg = Combat.damage(base, 1, w.melee() ? 0 : am[0], w.melee() ? 0 : am[1], 0, 0, false);
            c.hp -= dmg;
            log.append(" You hit yourself for ").append(dmg).append(" HP (").append(c.hp).append(" left)");
            if (f.onFire()) {   // Fallout 2: the flamer's own damage once, as fire; the survival sheet: fire damage x3 into Hyperthermia
                double heat = Combat.FIRE_HYPERTHERMIA_PER_HP * dmg;
                c.survival.put(Rules.Stat.HYPERTHERMIA, c.survival.getOrDefault(Rules.Stat.HYPERTHERMIA, 0.0) + heat);
                log.append(", fire damage: Hyperthermia +").append(Txt.f(heat));
            }
            log.append(".");
        }
        if (f.knockedDown()) { s.legs.clear(); s.legs.add(new Leg(2, p.getLocation().toBlockLocation())); log.append(" You fall: prone (getting up is a stance change)."); }
        if (f.crippledArm()) { s.armCrippled = true; log.append(" Your arm is crippled."); }
        if (f.onFire()) { Sfx.at(p.getLocation(), "burning"); s.burning = true; p.setVisualFire(true); log.append(" You are on fire (Fallout 2: the hit above is the harm, no burning over time)."); }
        if (f.losesTurn()) { s.ap = 0; s.apAtAnchor = 0; log.append(" Your turn is over."); }
    }

    // ------------------------------------------------------------------ rounds the environment forces (Marek 11-18, round 23)
    /** The jams on top (09c): the magnet's 1-2 of d6 while he stands in it, the Lodestone's 1 of d6 on his belt. */
    static int jamExtra(Character c) {
        int e = "magnet".equals(c.anomaly) ? 2 : 0;
        if (c.effects.stream().anyMatch(x -> x.slot().equals("artifact:Lodestone"))) e += 1;
        return e;
    }

    /** How near an enemy keeps a player in his rounds (round 11: off "if there are no enemies close") [mine, EXAMPLE]. */
    static final double ENEMY_NEAR = 30;

    /** The player's own rounds (round 11): on. */
    String roundsOn(Player p, Character c) {
        if (inBattle(p)) return "You are in rounds already.";
        startEnv(p, c, "your own rounds");
        return "Rounds ON: time stands still between them; end each from your Pip-Boy. Off again when no enemy is within " + (int) ENEMY_NEAR + " blocks.";
    }

    /** The player's own rounds (round 11): off, when no enemy that can fight is within {@link #ENEMY_NEAR} blocks and nothing hurts him. */
    String roundsOff(Player p, Character c) {
        State s = states.get(p.getUniqueId());
        if (s == null) return "You are not in rounds.";
        if (s.enemyTurn) return "The raiders' turn: wait.";
        for (UUID u : s.dummies) {
            Target t = s.targets.get(u);
            Entity e = pl.getServer().getEntity(u);
            if (t != null && !t.dead && !t.dying() && e != null && e.getWorld().equals(p.getWorld()) && e.getLocation().distance(p.getLocation()) < ENEMY_NEAR)
                return "No: " + t.name + " is " + Math.round(e.getLocation().distance(p.getLocation())) + " blocks away (get " + (int) ENEMY_NEAR + " away first).";
        }
        if (s.env && c.envHarm()) return "No: " + envWhat(c) + " still hurts you; the rounds go on until you are out of it.";
        stop(p);
        return s.env ? "Rounds OFF." : "You got away: the fight is over for you.";
    }

    /** Everyone in that world into rounds, held there for {@code why} (the Black Rain's warning; not the GMs in creative or spectator). */
    void holdAll(org.bukkit.World w, String why) {
        for (Player p : w.getPlayers()) {
            if (p.getGameMode() == org.bukkit.GameMode.CREATIVE || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            Character c = pl.character(p);
            if (c.away || c.dead) continue;
            State s = states.get(p.getUniqueId());
            if (s == null) startEnv(p, c, why);
            else if (s.env && !s.hold) { s.hold = true; s.holdWhy = why; }
        }
    }

    /** The Black Rain broke or ended: the rounds it held go on only for those it hurts. */
    void releaseHold(String why) {
        for (State s : states.values()) if (s.hold && s.holdWhy.equals(why)) s.hold = false;
    }

    /** In rounds because the air or a gas hurts him, not in a fight. */
    boolean inEnv(Player p) { State s = states.get(p.getUniqueId()); return s != null && s.env; }

    /**
     * Marek 11-18: *"battle rounds should also be active during taking damage from environment"*: when the air or a gas starts to
     * hurt a player out of a fight ({@link Character#envHarm}), the rounds begin as in a fight (AP, movement by AP, stances; time
     * stands still between them, game time only) and end when it stops. His own inventory stays; the Pip-Boy's Actions act (a
     * mask, the held breath, for AP) and end the round.
     */
    void startEnv(Player p, Character c) { startEnv(p, c, null); }

    /**
     * Rounds without danger (`09b` round 11, Marek 11-23, verbatim: *"it should be possible to turn battle mode on by player and tu turn if
     * off if there are no enemies close (player ran away)."* · the Black Rain *"should turn every player to the battle mode, so they can
     * realize it is going to rain and plan based on that."*): {@code why} keeps them until he ends them or the reason passes.
     */
    void startEnv(Player p, Character c, String why) {
        State s = new State();
        s.env = true;
        if (why != null) { s.hold = true; s.holdWhy = why; }
        s.sceneRound = pl.zones.round;
        s.ap = s.apAtAnchor = c.apMax();
        s.faceYaw = p.getLocation().getYaw();
        s.legs.add(new Leg(0, p.getLocation().toBlockLocation()));
        s.bar = BossBar.bossBar(Component.text(""), 1f, BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_10);
        p.showBossBar(s.bar);
        states.put(p.getUniqueId(), s);
        String what = s.hold && !c.envHarm() ? s.holdWhy : envWhat(c);
        p.sendMessage(Component.text("ROUNDS: " + what + ". Time stands still between the rounds: move by AP, act and END THE ROUND from your Pip-Boy.", NamedTextColor.GOLD));
        show(p, s, c, "rounds: " + what);
    }

    /** What the environment is doing to him, in a few words. */
    static String envWhat(Character c) {
        if (c.submerged) return "under water";
        if (!c.air) return "no air";
        if (c.rainExposed) return "out in the Black Rain";
        if (!c.anomaly.isEmpty() && Anomalies.KINDS.containsKey(c.anomaly)) return "in " + Anomalies.KINDS.get(c.anomaly).name();
        Rules.Gas g = c.gasHere();
        if (g != null) return g.name() + " " + Rules.roman(c.gasPower());
        if (c.holdingBreath) return "a held breath";
        return c.riotUntil > c.now() ? "riot gas in the eyes" : "the air";
    }

    /** The round ends (from the Pip-Boy): the body's round, the zones count down; the next round, or out of danger the rounds end. */
    void endEnvRound(Player p) {
        State s = states.get(p.getUniqueId());
        if (s == null || !s.env) return;
        Character c = pl.character(p);
        pl.zones.apply(c, p);
        Hud.round(c, rnd);
        pl.zones.reach(++s.sceneRound);   // the scene's round: shared with everyone else in rounds
        c.minuteAcc += 0.1;
        while (c.minuteAcc >= 60) { c.minuteAcc -= 60; Hud.hour(c); }
        String body = c.takeNews();
        if (body != null) p.sendMessage(Component.text("Round " + s.round + ": " + body + ".", NamedTextColor.GOLD));
        pl.zones.apply(c, p);
        if (c.dead || c.inArrest()) { died(p, s, c, false); return; }
        if (!c.envHarm() && !s.hold) { p.sendMessage(Component.text("Out of danger.", NamedTextColor.GREEN)); stop(p); return; }
        newTurn(p, s, c);
    }

    /** An action from the menus during rounds costs AP; true = paid, or not in rounds. */
    boolean spend(Player p, int ap, String what) {
        State s = states.get(p.getUniqueId());
        if (s == null || ap <= 0) return true;
        Character c = pl.character(p);
        if (s.enemyTurn) { show(p, s, c, "the raiders' turn: wait"); return false; }
        if (!pay(p, s, c, ap, what)) return false;
        show(p, s, c, what + " (" + ap + " AP)");
        return true;
    }

    /**
     * A frag grenade (Fallout 2's [EXAMPLE]: 20-35 explosion, 4 AP, Strength 3, a 2-block blast): the throw rolls like a shot with
     * Grenades (a throw is ranged in F2: the distance term counts). A hit bursts on the target, who takes it as the attack (a crit
     * multiplies it); a miss lands 1 to distance / 2 blocks off in a random direction and bursts there. Everyone within 2 blocks
     * whom no block shields from the burst takes the damage, the thrower too, and is knocked back damage / 10. A critical failure
     * reads the sheet's list for a grenade [mine]: no ammo = a dud, dropped or hit himself = it bursts at his feet, a random hit =
     * it flies at someone else.
     */
    private void grenade(Player p, State s, Character c, int range, boolean gas) {
        Aim a = aim(p, range);
        LivingEntity te = a != null && a.part() != null ? a.target() : null;
        Location aimAt;
        if (te != null) aimAt = te.getLocation();
        else {
            RayTraceResult hitB = p.rayTraceBlocks(range, FluidCollisionMode.NEVER);
            if (hitB == null) { show(p, s, c, "nothing within your throw (" + range + " blocks = 3 × Strength, at most 15): aim at an enemy or the ground"); return; }
            aimAt = hitB.getHitPosition().toLocation(p.getWorld());
        }
        int blocks = Math.max(1, (int) Math.round(aimAt.distance(p.getEyeLocation())));
        if (s.fight != null && s.fight.talk != null && s.ap >= 4) breakTalk(s.fight, p);
        if (!pay(p, s, c, 4, "the throw")) return;
        Sfx.at(p.getLocation(), "grenade.throw");
        Target tg = te != null ? s.targets.get(te.getUniqueId()) : null;
        int[] me = c.attrs(false);
        Combat.Shot sh = new Combat.Shot();
        sh.skill = skillOf(c, "Grenades");
        sh.blocks = blocks;
        sh.perception = me[Rules.P];
        sh.strength = me[Rules.S];
        sh.minStrength = 3;
        sh.lightLevel = aimAt.getBlock().getLightLevel();
        sh.blind = s.blind;
        // a target on the ground lies like a crawling one: the prone +4 AC (Marek's Tactics note) and +20 within reach (Combat)
        sh.targetAc = tg != null ? tg.ac + (tg.lying() ? Rules.STANCE_AC[2] : tg.stance == 1 ? Rules.STANCE_AC[1] : 0) : 0;
        sh.targetDown = tg != null && tg.lying();
        sh.obstacles = te != null ? inTheWay(p, a) : 0;
        sh.stance = s.stance();
        sh.senses = c.senses();
        sh.extra.put("prosthetic arm", c.armToHit(false));
        if (tg != null && tg.surprised) sh.extra.put("caught mid-talk", TALK_SURPRISE);
        sh.movePenalty = moved(s, p.getLocation().toBlockLocation()) && !s.steadied ? -10 : 0;
        sh.range = range;
        Combat.ToHit th = Combat.toHit(sh);
        Combat.Roll r = Combat.roll(th.chance(), Rules.critChance(me, c.skill("Sneak")) * (unaware(s.fight, tg) ? 2 : 1), 0, me[Rules.L],
                c.perks.stream().anyMatch(f -> f.name().equals("Better Criticals")) ? 20 : 0, jinxed(c), d100);
        StringBuilder log = new StringBuilder("Threw the " + (gas ? "riot gas" : "frag") + " grenade at " + (tg != null ? tg.name : te != null ? who(te) : "the ground") + ", " + blocks
                + " blocks (4 AP). To hit " + th.chance() + " % =");
        th.terms().forEach((k, v) -> log.append(" ").append(k.equals("skill") ? "Grenades " + v : sign(v) + " " + k));
        log.append(". Rolled ").append(r.d100()).append(": ");
        Location boom;
        LivingEntity direct = null;
        if (r.critFail() && r.degree() >= 2) {   // degree 1 is an ordinary miss: it scatters
            Combat.Failure f = Combat.critFailure(r.degree());
            log.append("CRITICAL FAILURE (degree ").append(r.degree()).append("): ");
            if (f.losesAmmo()) { log.append("a dud, it does not go off."); p.sendMessage(Component.text(log.toString(), NamedTextColor.GRAY)); show(p, s, c, "a dud"); if (f.losesTurn()) { s.ap = 0; s.apAtAnchor = 0; } return; }
            if (f.hitsRandom()) {
                List<Entity> others = new ArrayList<>();
                for (UUID u : s.dummies) { Entity e = pl.getServer().getEntity(u); if (e != null && !e.equals(te) && !e.isDead()) others.add(e); }
                boom = others.isEmpty() ? p.getLocation() : others.get(rnd.nextInt(others.size())).getLocation();
                log.append("it flies off at random.");
            } else { boom = p.getLocation(); log.append("it bursts at your feet."); }
            if (f.losesTurn()) { s.ap = 0; s.apAtAnchor = 0; }
        } else if (r.hit()) {
            boom = aimAt;
            direct = te;
            log.append(r.crit() ? "CRITICAL HIT, degree " + r.degree() + "." : "HIT.");
        } else {
            int off = Combat.grenadeScatter(blocks, (lo, hi) -> lo + rnd.nextInt(hi - lo + 1));
            double ang = rnd.nextDouble() * 2 * Math.PI;
            boom = ground(aimAt.clone().add(Math.cos(ang) * off, 0, Math.sin(ang) * off));
            log.append("MISS: it lands ").append(off).append(" block(s) off.");
        }
        if (gas) {   // 03b §3c (Marek 11-18: "the gas granade also hits specific space"): a cloud where it lands
            Zones.Zone z = pl.zones.ball(boom.clone().add(0, 1, 0), GAS_RADIUS, "riot gas", 1, GAS_ROUNDS, "a grenade's cloud");
            s.clouds.add(z);
            Sfx.at(boom, "gas.release");
            log.append(" A cloud of riot gas I bursts out: radius ").append(GAS_RADIUS).append(", ").append(GAS_ROUNDS).append(" rounds (zone ").append(z.id).append(").");
            pl.zones.apply(c, p);
        } else {
            explode(p, s, c, boom, direct, r.crit() ? r.degree() : 0, log);
            Zones.Zone pocket = inBattle(p) ? pl.zones.at(boom.clone().add(0, 1, 0)) : null;
            if (pocket != null && pocket.kind().ignites()) {   // the burst is a flame: the pocket goes off too
                log.append(" The burst sets off the ").append(pocket.gas).append("!");
                pl.zones.remove(pocket);
                explode(p, s, c, boom, null, 0, log);
            }
        }
        if (!inBattle(p)) { p.sendMessage(Component.text(log.toString(), NamedTextColor.GRAY)); return; }
        p.sendMessage(Component.text(log.toString(), NamedTextColor.GRAY));
        show(p, s, c, "the grenade burst" + (direct != null ? " on " + name(direct) : ""));
        checkOver(p, s, c);
    }

    /** The highest standing spot at or below a point (a missed grenade rolls to the ground). */
    private static Location ground(Location l) {
        Location x = l.clone();
        for (int i = 0; i < 6 && x.getBlock().isPassable() && x.clone().add(0, -1, 0).getBlock().isPassable(); i++) x.add(0, -1, 0);
        for (int i = 0; i < 6 && !x.getBlock().isPassable(); i++) x.add(0, 1, 0);
        return x;
    }

    /**
     * What a hit does to the player, a raider's shot or a grenade's blast alike (Marek 11-14: *"fix it like the raider's shot"*):
     * HP, Pain and bleeding (the /sheet hit rules), the crit's effects, knockback away from {@code from}, death.
     */
    void hitPlayer(Player p, State s, Character c, Location from, int dmg, Combat.CritEffects fx, boolean crit, int deg, StringBuilder log) {
        hitPlayer(p, s, c, from, dmg, fx, crit, deg, log, "gunshot");
    }

    /** The same, the wound's class named (04b §5: a cut, a gunshot or a blast's fragments, a dirty wound or a bite) for its infection. */
    void hitPlayer(Player p, State s, Character c, Location from, int dmg, Combat.CritEffects fx, boolean crit, int deg, StringBuilder log, String wound) {
        if (dmg > 0) Diseases.wound(c, wound, rnd);   // silent: the incubation is the GM's
        boolean strong = deg >= 4 || fx.crippledArm() || fx.crippledLeg();
        log.append(" You: ").append(Hud.hit(c, dmg, crit ? (strong ? "stab" : "crit") : "normal")).append(".");
        if (c.hp > 0 && (fx.knockedDown() || fx.knockedOut())) Sfx.fall(p.getLocation(), Sfx.body("down", c.female), c.female);
        else Sfx.at(p.getLocation(), Sfx.body(c.hp <= 0 ? "dying" : "hurt", c.female));
        log.append(c.wearOnHit(Rules.Part.TORSO, dmg, crit));
        Location here = p.getLocation().toBlockLocation();
        boolean psycho = c.other("no_knockdown") > 0;   // 04i §6a (RULED 11-27): Psycho ignores being knocked down and stunned
        if (fx.knockedDown() && psycho) log.append(" Psycho: you stay on your feet.");
        else if (fx.knockedDown() && !c.has("Stonewall")) { s.legs.clear(); s.legs.add(new Leg(2, here)); log.append(" You are knocked down: prone."); }
        if (fx.knockedOut()) { s.lostTurns = Math.max(s.lostTurns, 1); s.legs.clear(); s.legs.add(new Leg(2, here)); log.append(" You are knocked out: your next turn is lost."); }
        if (fx.losesTurn() && psycho) log.append(" Psycho: the stun does not take.");
        else if (fx.losesTurn()) { s.lostTurns = Math.max(s.lostTurns, 1); log.append(" You lose your next turn."); }
        if (fx.crippledArm()) { s.armCrippled = true; log.append(" Your arm is crippled."); }
        if (fx.crippledLeg()) { c.brokenLegs = Math.min(2, c.brokenLegs + 1); log.append(" A leg is crippled (").append(c.brokenLegs).append(" broken)."); }
        if (fx.blinded()) { s.blind = true; log.append(" You are blinded."); }
        if (fx.dropsWeapon()) {
            Weapon held = heldWeapon(p);
            if (held != null) { s.dropped.add(held.name); log.append(" You drop the ").append(held.name).append("."); }
        }
        if (fx.heartStops()) log.append(" ").append(c.stopHeart(fx.heart(), true, rnd)).append(".");   // 03b §3c: a chest blow, electricity
        if (fx.dies()) { c.hp = Rules.deathFloor(c.attrs(false)) - 1; log.append(" Killed outright."); }
        if (c.inArrest() || c.hp < Rules.deathFloor(c.attrs(false))) { p.sendMessage(Component.text(log.toString(), NamedTextColor.RED)); log.setLength(0); died(p, s, c, fx.dies()); return; }
        int kb = Combat.knockback(dmg);
        if (kb > 0) {
            knockback(from, p, kb, log);
            int st = s.stance();
            s.legs.clear();
            s.legs.add(new Leg(st, p.getLocation().toBlockLocation()));
        }
    }

    /** The burst: everyone within 2 blocks whom no block shields (a line from the burst to his middle) takes 20-35, less DT and DR. */
    private void explode(Player p, State s, Character c, Location boom, LivingEntity direct, int critDegree, StringBuilder log) {
        explode(p, s, c, boom, direct, critDegree, log, Combat.GRENADE_MIN, Combat.GRENADE_MAX);
    }

    /** The Kamehameha's beam ({@link #KAME}): each body on its line takes the punch's damage, each its own roll. Returns the result. */
    private String beam(Player p, State s, Character c, Weapon w, Aim a, Combat.Roll r, int[] me, StringBuilder log) {
        if (!r.hit()) { log.append("MISS: the beam flies wide."); Sfx.at(a.target().getLocation(), "melee.miss"); return "the beam missed"; }
        org.bukkit.World wd = p.getWorld();
        Location eye = p.getEyeLocation();
        Vector dir = a.target().getLocation().add(0, a.target().getHeight() / 2, 0).toVector().subtract(eye.toVector());
        if (dir.lengthSquared() < 1e-6) dir = eye.getDirection();
        dir.normalize();
        double len = reach(c, w) + 0.5;
        org.bukkit.util.RayTraceResult wall = wd.rayTraceBlocks(eye, dir, len, FluidCollisionMode.NEVER, true);
        double stop = wall != null ? wall.getHitPosition().distance(eye.toVector()) : len;
        for (double d = 0.5; d <= stop; d += 0.5) wd.spawnParticle(org.bukkit.Particle.END_ROD, eye.clone().add(dir.clone().multiply(d)), 3, 0.08, 0.08, 0.08, 0.0);
        Sfx.at(p.getLocation(), "explosion");
        Location centre = eye.clone().add(dir.clone().multiply(stop / 2));
        List<LivingEntity> bodies = new ArrayList<>();
        List<Vector> mids = new ArrayList<>();
        List<Double> hw = new ArrayList<>();
        for (Entity e : wd.getNearbyEntities(centre, stop / 2 + 1.5, stop / 2 + 2, stop / 2 + 1.5)) {
            if (!(e instanceof LivingEntity le) || le.isDead() || le.equals(p)) continue;
            bodies.add(le);
            mids.add(le.getLocation().add(0, le.getHeight() / 2, 0).toVector());
            hw.add(le.getWidth() / 2);
        }
        log.append(r.crit() ? "CRITICAL: " : "").append("the beam flies ").append(Txt.f(Math.round(stop * 10) / 10.0)).append(" blocks")
                .append(wall != null ? " to a wall" : "").append(".");
        List<String> hit = new ArrayList<>();
        for (int i : onBeam(eye.toVector(), dir, stop, mids, hw)) {
            LivingEntity le = bodies.get(i);
            int base = Math.max(1, w.minDmg + rnd.nextInt(w.maxDmg - w.minDmg + 1) + handBonus(c, me, w));
            Target t = s.targets.get(le.getUniqueId());
            if (t != null) {
                if (t.dead) continue;
                int dtv = piercing(c, w) ? (int) Math.round(t.dt / 5.0) : t.dt;
                int dmg = Combat.damage(base, 1, 0, w.pen, dtv, t.dr, false);
                hit.add(t.name + " " + dmg + " HP");
                StringBuilder sub = new StringBuilder();
                hurt(le, t, dmg, Combat.critEffects(Rules.Part.TORSO, 0, t.a), false, 0, sub);
                if (!t.dead) knockback(p.getLocation(), le, Combat.knockback(dmg), sub);
                log.append(sub);
            } else if (le instanceof Player pp) {   // a player on the line is hit as by a raider's blow (the chest piece)
                Character pc = pl.character(pp);
                int dmg = Combat.damage(base, 1, 0, 0, pc.dtAt(Rules.Part.TORSO, "Normal"), pc.drAt(Rules.Part.TORSO, "Normal"), false);
                if (!pc.wearArmour(Rules.Part.TORSO, base).isEmpty()) Items.writeArmour(pp, pc);
                hit.add(pp.getName() + " " + dmg + " HP");
                State ps = states.get(pp.getUniqueId());
                StringBuilder sub = new StringBuilder();
                if (ps != null) hitPlayer(pp, ps, pc, p.getLocation(), dmg, Combat.critEffects(Rules.Part.TORSO, 0, pc.attrs(false)), false, 0, sub);
                else sub.append(" ").append(pp.getName()).append(": ").append(Hud.hit(pc, dmg, "normal")).append(".");
                pp.sendMessage(Component.text("A Kamehameha's beam:" + sub, NamedTextColor.RED));
                if (!inBattle(p)) return "the beam";
            }
        }
        log.append(" The beam hits: ").append(hit.isEmpty() ? "nobody" : String.join("; ", hit)).append(".");
        return "the beam: " + (hit.isEmpty() ? "nobody hit" : hit.size() + " hit");
    }

    private void explode(Player p, State s, Character c, Location boom, LivingEntity direct, int critDegree, StringBuilder log, int dmgMin, int dmgMax) {
        org.bukkit.World w = boom.getWorld();
        w.spawnParticle(org.bukkit.Particle.EXPLOSION, boom.clone().add(0, 0.5, 0), 1);
        Sfx.at(boom, "explosion");
        flash(boom);
        Location centre = boom.clone().add(0, 0.5, 0);
        int r = Combat.GRENADE_RADIUS;
        List<String> hit = new ArrayList<>();
        for (Entity e : w.getNearbyEntities(centre, r + 0.5, r + 1.5, r + 0.5)) {
            if (!(e instanceof LivingEntity le) || le.isDead()) continue;
            Location mid = le.getLocation().add(0, le.getHeight() / 2, 0);
            double dist = Math.hypot(mid.getX() - centre.getX(), mid.getZ() - centre.getZ());
            if (dist > r + 0.5 && !le.equals(direct)) continue;
            Vector to = mid.toVector().subtract(centre.toVector());
            if (to.lengthSquared() > 1e-6 && w.rayTraceBlocks(centre, to.clone().normalize(), to.length(), FluidCollisionMode.NEVER, true) != null) continue;
            int base = dmgMin + rnd.nextInt(dmgMax - dmgMin + 1);
            boolean isDirect = le.equals(direct);
            Target t = s.targets.get(le.getUniqueId());
            Combat.CritEffects fx = Combat.critEffects(Rules.Part.TORSO, isDirect ? critDegree : 0, t != null ? t.a : new int[]{5, 5, 5, 5, 5, 5, 5});
            double mult = isDirect ? Combat.critMultiplier(Rules.Part.TORSO, critDegree) : 1;
            if (t != null) {
                if (t.dead) continue;
                int dmg = Combat.damage(base, mult, 0, 0, t.dt, t.dr, fx.bypass());
                hit.add(t.name + " " + dmg + " HP" + (fx.names().isEmpty() ? "" : " (" + String.join(", ", fx.names()) + ")"));
                StringBuilder sub = new StringBuilder();
                hurt(le, t, dmg, fx, isDirect && critDegree > 0, critDegree, sub);
                if (!t.dead) knockback(boom, le, Combat.knockback(dmg), sub);
                log.append(sub);
            } else if (le instanceof Player pp) {
                // a player in the blast is hit like a raider's shot hits him: HP, Pain, knockback from the burst, death (Marek 11-14)
                Character pc = pl.character(pp);
                int dmg = Combat.damage(base, 1, 0, 0, pc.dtAt(Rules.Part.TORSO, "Normal"), pc.drAt(Rules.Part.TORSO, "Normal"), false);   // the chest piece (05c)
                if (!pc.wearArmour(Rules.Part.TORSO, base).isEmpty()) Items.writeArmour(pp, pc);
                hit.add((pp.equals(p) ? "you" : pp.getName()) + " " + dmg + " HP");
                State ps = states.get(pp.getUniqueId());
                StringBuilder sub = new StringBuilder();
                if (ps != null) hitPlayer(pp, ps, pc, boom, dmg, Combat.critEffects(Rules.Part.TORSO, 0, pc.attrs(false)), false, 0, sub);
                else sub.append(" ").append(pp.getName()).append(": ").append(Hud.hit(pc, dmg, "normal")).append(".");
                if (pp.equals(p)) log.append(sub);
                else pp.sendMessage(Component.text("A grenade's blast:" + sub, NamedTextColor.RED));
                if (!inBattle(p)) return;   // the thrower died in his own blast: the demo has ended
            }
        }
        log.append(" The burst hits: ").append(hit.isEmpty() ? "nobody" : String.join("; ", hit)).append(".");
        // 03b §3c: the blast's ears, out to three times its radius (an eardrum roll 50 % / 15 %, ringing 1d6 hours)
        for (Player pp : w.getNearbyPlayers(centre, 3 * r + 0.5)) {
            String e = pl.character(pp).blast(pp.getEyeLocation().distance(centre), r, rnd);
            if (e.isEmpty()) continue;
            if (pp.equals(p)) log.append(" Your ears: ").append(e).append(".");
            else pp.sendMessage(Component.text("The blast: " + e + ".", NamedTextColor.RED));
        }
    }

    /** A roof over the head (no sky above the eyes, trees not counted): "indoors" for the gunfight's ringing (03b §3c) [mine]. */
    static boolean roofed(Player p) {
        return p.getWorld().getHighestBlockYAt(p.getLocation(), org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES) > p.getEyeLocation().getBlockY();
    }

    /** The riot gas grenade's cloud [EXAMPLE, mine]: radius 3 blocks, 5 rounds (30 s), strength I. */
    static final int GAS_RADIUS = 3;
    static final int GAS_ROUNDS = 5;

    /** A gas that went off is gone: the zone the player stands in, or his own setting. */
    void burnAway(Player p, Character c) {
        if (c.inZone()) { Zones.Zone z = pl.zones.at(p.getEyeLocation()); if (z != null) pl.zones.remove(z); c.zoneGas = ""; }
        else c.gas = "";
    }

    /** Fallout 2: a throw reaches 3 x Strength hexes, at most the item's range (a frag grenade's 15). */
    private static int throwRange(Character c) { return Math.min(15, 3 * c.attrs(false)[Rules.S]); }

    /** A quick item by right click, wherever the crosshair is (at an enemy too). */
    private void useItem(Player p, State s, Character c, String id) {
        if (s.own && (id.equals("stim") || id.equals("grenade") || id.equals("gasgren"))) {
            if (s.quick.getOrDefault(id, 0) <= 0) { show(p, s, c, "none left"); return; }
            int ap0 = s.ap;
            useItemNow(p, s, c, id);
            if (s.ap < ap0 || !inBattle(p)) {   // used: one fewer
                s.quick.merge(id, -1, Integer::sum);
                s.used.merge(id, 1, Integer::sum);
                if (inBattle(p) && s.quick.getOrDefault(id, 0) <= 0) {
                    for (int i = 0; i < 9; i++) if (id.equals(id(p.getInventory().getItem(i))))
                        p.getInventory().setItem(i, tagged(Material.GRAY_STAINED_GLASS_PANE, "locked", "none left", "used up in this fight"));
                }
            }
            return;
        }
        useItemNow(p, s, c, id);
    }

    private void useItemNow(Player p, State s, Character c, String id) {
        switch (id) {
            case "stim" -> {
                if (!pay(p, s, c, 2, "the Stimpak")) return;
                String r = Meds.inject(c, "Stimpak", rnd);   // 04i §6a: it stops the bleeding now
                Sfx.at(p.getLocation(), "stimpak");
                show(p, s, c, r + " (2 AP)");
            }
            case "grenade" -> grenade(p, s, c, throwRange(c), false);
            case "gasgren" -> grenade(p, s, c, throwRange(c), true);
            case "flare" -> {
                int range = throwRange(c), ap = 2;
                Aim a = aim(p, range);
                String where;
                if (a != null && a.part() != null) where = who(a.target()) + ", " + Txt.f(Math.round(a.dist() * 10) / 10.0) + " blocks";
                else {
                    RayTraceResult r = p.rayTraceBlocks(range, FluidCollisionMode.NEVER);
                    if (r == null) { show(p, s, c, "nothing within your throw (" + range + " blocks = 3 × Strength, at most 15): aim at an enemy or the ground"); return; }
                    where = "the ground " + Txt.f(Math.round(r.getHitPosition().distance(p.getEyeLocation().toVector()) * 10) / 10.0) + " blocks away";
                }
                if (!pay(p, s, c, ap, "the flare")) return;
                Sfx.at(p.getLocation(), "flare");
                show(p, s, c, "threw the flare at " + where + " (" + ap + " AP)");
            }
            default -> show(p, s, c, null);
        }
    }

    /** /sheet firstaid: first aid (Medicine) stops all bleeding (Marek 11-14: *"Bandaging here is just first aid."*); in battle it
     *  takes the whole turn (Andor), so all your AP; Medical Marvel: nobody can stabilise you while dying. */
    String firstAid(Player p, Character c) {
        if (c.bleeding <= 0) return "nothing bleeds";
        if (c.hp <= 0 && c.has("Medical Marvel")) return "Medical Marvel: nobody can stabilise you while you are dying (no first aid)";
        State s = states.get(p.getUniqueId());
        if (s == null) { c.bleeding = 0; Sfx.at(p.getLocation(), "firstaid"); return "first aid (Medicine): the bleeding stops"; }
        if (s.enemyTurn) return "the raiders' turn: wait";
        if (s.ap < c.apMax()) return "first aid takes the whole turn: start a turn with all your AP (the clock ends this one)";
        s.ap = s.apAtAnchor = 0;
        int st = s.stance();
        s.legs.clear();
        s.legs.add(new Leg(st, p.getLocation().toBlockLocation()));
        c.bleeding = 0;
        show(p, s, c, "first aid: the whole turn");
        Sfx.at(p.getLocation(), "firstaid");
        return "first aid (the whole turn): the bleeding stops";
    }

    // ------------------------------------------------------------------ the keys
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        State s = states.get(p.getUniqueId());
        if (s == null || e.getHand() != EquipmentSlot.HAND) return;
        e.setCancelled(true);
        if (s.env) return;   // the Pip-Boy and the active items: FalloutakPlugin.onUse
        Character c = pl.character(p);
        String id = id(e.getItem());
        boolean right = e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK;
        boolean left = e.getAction() == Action.LEFT_CLICK_AIR || e.getAction() == Action.LEFT_CLICK_BLOCK;
        long now = System.currentTimeMillis();
        if (left && now - s.lastLeft < 150) return;     // the swing after an attack on an entity: handled there
        if (right && now - s.lastRight < 150) return;   // the use after a right click on an entity: handled there
        if (left) s.lastLeft = now;
        if (right) s.lastRight = now;
        if (s.enemyTurn) { show(p, s, c, "the raiders' turn: wait"); return; }
        if (id.equals("end")) { endTurn(p, s, c); return; }
        Weapon w = weapon(id);
        if (w != null) {
            if (right) nextMode(p, s, c, w);
            else if (left) act(p, s, c, w);
            return;
        }
        if (right && quickItem(id)) useItem(p, s, c, id);
        else if (right && id.isEmpty() && s.own && onBelt(p.getInventory().getHeldItemSlot())) useBelt(p, s, c, p.getInventory().getHeldItemSlot());
        else show(p, s, c, null);
    }

    /**
     * The clock: the player's turn ends, a round of bleeding (his and the raiders'), then every raider able to act takes his turn
     * ({@link Raiders}), then the player's next turn. Any click, at an enemy too.
     */
    private void endTurn(Player p, State s, Character c) {
        if (s.enemyTurn) { show(p, s, c, "the raiders' turn: wait"); return; }
        s.carry = Math.max(0, s.ap);   // 05d round 7: the unused AP, carried into the next turn (at most the max)
        s.faceYaw = p.getLocation().getYaw();   // 05c §11: facing = where he looks as his turn ends
        s.ap = s.apAtAnchor = 0;
        Sfx.to(p, "turn.end");
        pl.zones.apply(c, p);
        Hud.round(c, rnd);   // bleeding, breathing, the gas (03b §3c)
        pl.zones.reach(++s.sceneRound);   // the clouds count down once a round of the scene (Marek 11-18, round 24)
        c.minuteAcc += 0.1;   // a round = 6 s of game time (Marek 11-16: time stands still between the rounds)
        c.fighting = true;    // 04 §3.1, §3.3: an hour that ends in a fight is hard work
        while (c.minuteAcc >= 60) { c.minuteAcc -= 60; Hud.hour(c); }
        c.fighting = false;
        String body = c.takeNews();
        if (body != null) p.sendMessage(Component.text(body + ".", NamedTextColor.GOLD));
        if (c.inArrest()) { died(p, s, c, false); return; }
        Fight f = s.fight;
        f.done.add(p.getUniqueId());
        s.enemyTurn = true;   // he waits: for the others, then for the enemies
        if (!partyMaybeDone(f)) {
            p.sendMessage(Component.text("Your turn is over: waiting for the others.", NamedTextColor.GRAY));
            show(p, s, c, "waiting for the others");
        }
    }

    /**
     * The fight's XP, once (06b §3 and §5.4.1; 09d rounds 3-4): EVERY ENEMY BEATEN gives his kill XP, 3.1 x HP^1.09, to the team, whoever
     * beat him (Marek 11-24: *"kills made by other dangers pay you no XP" - I would count that, because you may have used them to kill
     * others and running away gives full XP already.*); one that got away pays nothing; when the whole party got away at the edge alive,
     * every group it never touched was SLIPPED PAST = resolved without killing on the battle map = its full kill XP (Marek 11-23: *"When
     * you resolve the encounter non-lethaly on the battle map, you get the full kill XP."*). The players on the field at the end get ITS
     * BODIES to loot, one by one ({@link Loot}; Marek 11-24: *"allow to loot every killed/downed creature"*).
     */
    void settle(Fight f, boolean slippedPast) {
        if (f.settled) return;
        f.settled = true;
        List<Player> team = new ArrayList<>();
        for (UUID u : f.members) { Player q = pl.getServer().getPlayer(u); if (q != null) team.add(q); }
        List<Double> hps = new ArrayList<>();
        Map<Integer, Boolean> groupTouched = new HashMap<>(), groupStanding = new HashMap<>();
        for (Target t : f.targets.values()) {
            groupTouched.merge(t.group, t.touched, Boolean::logicalOr);
            groupStanding.merge(t.group, !t.outOfIt(), Boolean::logicalOr);
        }
        int slipped = 0, byOthers = 0;
        for (Target t : f.targets.values()) {
            if (t.escaped || t.paid) continue;   // an ally of convenience was paid when he turned (09d round 5)
            boolean beaten = t.outOfIt();
            if (beaten) { hps.add((double) t.hpMax); if (!t.byParty) byOthers++; }
            else if (slippedPast && !t.flees && !groupTouched.getOrDefault(t.group, false) && groupStanding.getOrDefault(t.group, false)) { hps.add((double) t.hpMax); slipped++; }
        }
        if (pl.roster != null && !hps.isEmpty() && !team.isEmpty()) {
            String r = pl.roster.killAward(team, hps, slipped > 0 ? "an encounter slipped past on the battle map" : f.them + " beaten");
            for (Player q : team) q.sendMessage(Component.text((slipped > 0 ? "Slipped past " + slipped + " unharmed: their full kill XP. " : "")
                    + (byOthers > 0 ? byOthers + " fell to the other dangers: theirs counts too. " : "") + r, NamedTextColor.GREEN));
        }
        if (slippedPast || f.field == null) return;   // whoever left the field left the bodies; the demo has none
        List<Player> here = players(f);
        if (here.isEmpty()) return;
        int n = Loot.collect(f, rnd);
        if (n > 0) for (Player q : here) q.sendMessage(Component.text("The field is yours: " + n + " bod" + (n == 1 ? "y" : "ies")
                + " to loot (the Travel menu: The field): search, butcher (Survival), treat or take samples (Medicine).", NamedTextColor.GOLD));
    }

    /** A player at the battle map's edge gets away (09d round 3: *"get out of the map by going to the edge (just like hunted animals)"*). */
    static boolean atEdge(org.bukkit.util.BoundingBox f, Location l) {
        return f != null && (l.getBlockX() <= f.getMinX() + 1 || l.getBlockX() >= f.getMaxX() - 2 || l.getBlockZ() <= f.getMinZ() + 1 || l.getBlockZ() >= f.getMaxZ() - 2);
    }

    /** Ends the player's turn as the clock does (the tests, and the menus). */
    void endTurnFor(Player p) { State s = states.get(p.getUniqueId()); if (s != null) endTurn(p, s, pl.character(p)); }

    /** The party's turn is over once every player in the fight has ended his: then the enemies'. */
    boolean partyMaybeDone(Fight f) {
        List<Player> ps = players(f);
        if (ps.isEmpty()) return false;
        for (Player q : ps) if (!f.done.contains(q.getUniqueId())) return false;
        partyDone(f, ps);
        return true;
    }

    private void partyDone(Fight f, List<Player> ps) {
        // the enemies bleed once a round, at the end of the party's turn
        List<String> them = new ArrayList<>();
        for (var en : f.targets.entrySet()) {
            Target t = en.getValue();
            Entity e = pl.getServer().getEntity(en.getKey());
            if (t.dead || t.escaped || !(e instanceof LivingEntity le)) continue;
            if (t.bleeding > 0) {
                t.blood = Math.max(0, t.blood - t.bleeding);
                if (t.blood < 0.2 * 4 * t.hpMax) { t.dead = true; them.add(t.name + " bled out (Deadly Anemia: under 20 % Blood)"); }
                else them.add(t.name + " bleeds: Blood " + Txt.f(t.blood) + " / " + 4 * t.hpMax);
            }
            label(le, t);
        }
        if (!them.isEmpty()) tell(f, String.join("; ", them) + ".", NamedTextColor.GRAY);
        Player q0 = ps.get(0);
        if (checkOver(q0, states.get(q0.getUniqueId()), pl.character(q0))) return;
        if (f.talk != null && talkStep(f)) return;
        if (f.enemiesFirst) f.round++;
        enemyPhase(f, () -> { if (!f.enemiesFirst) f.round++; partyPhase(f); });
    }

    /**
     * The talk at the end of the party's turn (09d round 4): it goes wrong (they shoot first: their turn follows), it ends well (the
     * talkers go their way for their full kill XP; true when that ended the fight), or it goes on (the talkers hold in their turn).
     */
    boolean talkStep(Fight f) {
        Talk k = f.talk;
        if (k.failAt > 0 && f.round >= k.failAt) {
            endTalk(f);
            tell(f, "THE TALK GOES WRONG (" + k.speakerName + ": " + k.check + "): " + k.who + " go for their guns. They shoot first.", NamedTextColor.RED);
            return false;
        }
        if (k.failAt == 0 && f.round >= TALK_ROUNDS) {
            List<Double> hps = new ArrayList<>();
            for (var en : f.targets.entrySet()) {
                Target t = en.getValue();
                if (!t.talking) continue;
                hps.add((double) t.hpMax);
                t.talking = false;
                t.escaped = true;   // they go their way
                Entity e = pl.getServer().getEntity(en.getKey());
                if (e != null) e.remove();
            }
            f.talk = null;
            List<Player> team = new ArrayList<>();
            for (UUID u : f.members) { Player q = pl.getServer().getPlayer(u); if (q != null) team.add(q); }
            String r = pl.roster != null && !team.isEmpty() && !hps.isEmpty() ? pl.roster.killAward(team, hps, "talked down on the battle map") : "";
            tell(f, "TALKED DOWN (" + k.speakerName + ": " + k.check + "): " + k.who + " lower their guns and go their way. Resolved without killing: "
                    + "their full kill XP. " + r, NamedTextColor.GREEN);
            List<Player> ps = players(f);
            if (ps.isEmpty()) return true;
            Player q0 = ps.get(0);
            return checkOver(q0, states.get(q0.getUniqueId()), pl.character(q0));
        }
        tell(f, "The talk goes on (" + k.speakerName + " talks; round " + f.round + " of " + TALK_ROUNDS + "): get into position. An attack now catches "
                + k.who + " off guard (+" + TALK_SURPRISE + " to hit them this turn).", NamedTextColor.YELLOW);
        return false;
    }

    /** A player attacks in the middle of the talk: it is over and the talkers are caught off guard until the party's turn ends. */
    void breakTalk(Fight f, Player p) {
        Talk k = f.talk;
        if (k == null) return;
        for (Target t : f.targets.values()) if (t.talking) { t.talking = false; t.surprised = true; }
        f.talk = null;
        Travel.struckFirst();   // 09d round 6: struck first, they shoot on sight from now on
        tell(f, p.getName() + " strikes in the middle of the talk: " + k.who + " are caught off guard (+" + TALK_SURPRISE + " to hit them for the rest of "
                + "your turn).", NamedTextColor.GOLD);
    }

    /** The talk is over without a word more (it went wrong, or another danger fell on the talkers). */
    void endTalk(Fight f) {
        for (Target t : f.targets.values()) t.talking = false;
        f.talk = null;
    }

    /**
     * THE GM's ATTACK (09d round 5, Marek 11-24: *"give there option for GM to trigger the enemy attack first"*): the talk goes wrong at
     * once; every player's turn ends where it stands, and the talkers act first.
     */
    String gmAttack(Fight f) {
        Talk k = f.talk;
        if (k == null || f.over) return "! no talk is going on";
        k.failAt = Math.max(1, f.round);
        tell(f, "THE GM: the talk breaks off at once. " + k.who + " go for their weapons.", NamedTextColor.RED);
        for (Player q : players(f)) if (!f.done.contains(q.getUniqueId())) endTurnFor(q);
        return "The talk breaks off: they act first.";
    }

    /**
     * ANOTHER DANGER FALLS ON THE MAP DURING THE TALK (09d rounds 5-6): while nobody shoots yet (the talk still on, whatever its check;
     * Marek 11-24: *"if they do not yet shoot each other, common enemy will make them into allies"*) the talkers become ALLIES OF
     * CONVENIENCE: they turn on the newcomers, never on the party, and the talk counts as resolved without killing (their full kill XP,
     * once). A talk that went wrong is over already (they shoot). {@code attacker} = the newcomers' group.
     */
    void allies(Fight f, int attacker) {
        Talk k = f.talk;
        if (k == null) return;
        List<Double> hps = new ArrayList<>();
        int g = -1;
        for (var en : f.targets.entrySet()) {
            Target t = en.getValue();
            if (!t.talking || t.group == attacker) continue;
            t.talking = false;
            t.ally = true;
            t.paid = true;
            g = t.group;
            hps.add((double) t.hpMax);
            Entity e = pl.getServer().getEntity(en.getKey());
            if (e instanceof LivingEntity le) label(le, t);
        }
        f.talk = null;
        if (g < 0) return;
        List<Player> team = new ArrayList<>();
        for (UUID u : f.members) { Player q = pl.getServer().getPlayer(u); if (q != null) team.add(q); }
        String r = pl.roster != null && !team.isEmpty() && !hps.isEmpty() ? pl.roster.killAward(team, hps, "allies of convenience on the battle map") : "";
        tell(f, "ALLIES OF CONVENIENCE: another danger falls on the map, and " + k.who + " (still talking with " + k.speakerName + ") turn their weapons on it. "
                + "Resolved without killing: their full kill XP. " + r, NamedTextColor.GREEN);
    }

    /** A NEUTRAL figure (the GM's tools, `Npcs`): it holds as a talker does, but outside any talk; it does not keep the fight going. */
    static boolean neutral(Fight f, Target t) { return t.talking && f.talk == null; }

    /** A player attacks a neutral figure (the GM's tools): its whole group fights from now on. */
    void provoke(Fight f, int group, Player p) {
        for (var en : f.targets.entrySet()) {
            Target t = en.getValue();
            if (t.group != group || !neutral(f, t)) continue;
            t.talking = false;
            Entity e = pl.getServer().getEntity(en.getKey());
            if (e instanceof LivingEntity le) label(le, t);
        }
        tell(f, p.getName() + " attacks the neutral ones: they fight back.", NamedTextColor.RED);
    }

    /** Whether a figure is in a fight that is not over (the GM's followers stand still in it: the battle moves them). */
    boolean figureInFight(UUID u) {
        for (State s : states.values()) if (s.fight != null && !s.fight.over && s.fight.targets.containsKey(u)) return true;
        return false;
    }

    /** A player attacks an ally of convenience: the whole group is hostile again (their XP was paid already). */
    void betray(Fight f, int group, Player p) {
        boolean any = false;
        for (var en : f.targets.entrySet()) {
            Target t = en.getValue();
            if (t.group != group || !t.ally) continue;
            t.ally = false;
            any = true;
            Entity e = pl.getServer().getEntity(en.getKey());
            if (e instanceof LivingEntity le) label(le, t);
        }
        if (any) tell(f, p.getName() + " attacks the allies: they are enemies again.", NamedTextColor.RED);
    }

    /** The enemies' turn, each in Sequence order, at the player nearest to him; then {@code then}. */
    void enemyPhase(Fight f, Runnable then) {
        f.enemyPhase = true;
        for (Target t : f.targets.values()) t.surprised = false;   // caught off guard only in the party's turn of the attack
        for (Player q : players(f)) {
            State sq = states.get(q.getUniqueId());
            sq.enemyTurn = true;
            sq.round = f.round;
            q.sendMessage(Component.text("Round " + f.round + ": " + f.them + "' turn.", NamedTextColor.RED));
            show(q, sq, pl.character(q), f.them + "' turn");
        }
        raiders.run(f, () -> { f.enemyPhase = false; then.run(); });
    }

    /** The party's turn: every player in the fight at once. */
    void partyPhase(Fight f) {
        f.done.clear();
        List<Player> ps = players(f);
        if (ps.isEmpty()) return;
        Player q0 = ps.get(0);
        if (checkOver(q0, states.get(q0.getUniqueId()), pl.character(q0))) return;
        for (Player q : ps) newTurn(q, states.get(q.getUniqueId()), pl.character(q));
    }

    /** The player's turn again: full AP (none if a crit or a fumble took this turn), the movement counted from here. */
    private void newTurn(Player p, State s, Character c) {
        if (!inBattle(p) || s.fight != null && s.fight.over) return;
        if (checkOver(p, s, c)) return;
        s.enemyTurn = false;
        if (s.fight != null) s.round = s.fight.round; else s.round++;   // the environment's own rounds have no fight
        if (s.burning) { s.burning = false; p.setVisualFire(false); }
        s.steadied = false;
        int st = s.stance();
        s.legs.clear();
        s.legs.add(new Leg(st, p.getLocation().toBlockLocation()));
        boolean out = c.unconscious();   // Oxygen 0 (03b §3c): no turn while it lasts
        boolean lost = s.lostTurns > 0 || out;
        if (s.lostTurns > 0) s.lostTurns--;
        int shockAp = lost ? 0 : Math.min(c.apMax(), s.apLoss);   // 05d round 5: a shock last round
        s.apLoss = 0;
        int carry = lost ? 0 : Math.max(0, Math.min(s.carry, c.apMax()));   // 05d round 7: unused AP carried, at most the max
        s.ap = s.apAtAnchor = turnAp(c.apMax(), s.carry, shockAp, lost);
        s.carry = 0;
        if (carry > 0) p.sendMessage(Component.text(carry + " AP carried over from your last turn.", NamedTextColor.GRAY));
        if (shockAp > 0) p.sendMessage(Component.text("The shock still in your muscles: " + shockAp + " AP less this turn.", NamedTextColor.RED));
        Sfx.to(p, lost ? "ap.lacking" : "turn.start");
        String pass = s.env ? "end the round from the Pip-Boy" : "click the clock to pass it";
        p.sendMessage(Component.text("Round " + s.round + ": your turn" + (out ? " (UNCONSCIOUS: " + pass + ")." : lost ? " (lost: " + pass + ")." : "."), NamedTextColor.AQUA));
        if (s.fight != null && s.fight.talk != null) {
            Talk k = s.fight.talk;
            p.sendMessage(Component.text((p.getUniqueId().equals(k.speaker) ? "You talk to " + k.who : k.speakerName + " talks to " + k.who) + " (round " + s.round
                    + " of " + TALK_ROUNDS + "): move into position. An attack breaks the talk and catches them off guard; the talk can still go wrong.", NamedTextColor.YELLOW));
        }
        show(p, s, c, out ? "unconscious" : lost ? "this turn is lost" : "your turn: " + s.ap + " AP" + (c.bleeding > 0 ? "; bleeding " + Txt.f(c.bleeding) + " Blood a round" : ""));
    }

    private void nextMode(Player p, State s, Character c, Weapon w) {
        s.chi = chiLevel(c, w);
        List<Mode> ms = modes(s, w, p.getLocation().toBlockLocation());
        int i = Math.min(s.mode.getOrDefault(w.name, 0), ms.size() - 1);
        s.mode.put(w.name, (i + 1) % ms.size());
        Sfx.to(p, "mode.next");
        show(p, s, c, null);
    }

    /** A left click on an entity in vanilla reach comes as an attack, not as a click: the same action, along the crosshair. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onAttack(PrePlayerAttackEntityEvent e) {
        Player p = e.getPlayer();
        State s = states.get(p.getUniqueId());
        if (s == null || s.env) return;
        e.setCancelled(true);
        s.lastLeft = System.currentTimeMillis();
        if (s.enemyTurn) { show(p, s, pl.character(p), "the raiders' turn: wait"); return; }
        String id = id(p.getInventory().getItemInMainHand());
        Weapon w = weapon(id);
        if (w != null) act(p, s, pl.character(p), w);
        else if (id.equals("end")) endTurn(p, s, pl.character(p));
    }

    /** A right click on an entity: the weapon's next mode, or the quick item used (at that enemy); the item's own use after it is dropped. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        State s = states.get(p.getUniqueId());
        if (s == null || s.env || e.getHand() != EquipmentSlot.HAND) return;
        e.setCancelled(true);
        long now = System.currentTimeMillis();
        if (now - s.lastRight < 150) return;
        s.lastRight = now;
        if (s.enemyTurn) { show(p, s, pl.character(p), "the raiders' turn: wait"); return; }
        String id = id(p.getInventory().getItemInMainHand());
        Weapon w = weapon(id);
        if (w != null) nextMode(p, s, pl.character(p), w);
        else if (quickItem(id)) useItem(p, s, pl.character(p), id);
        else if (id.isEmpty() && s.own && onBelt(p.getInventory().getHeldItemSlot())) useBelt(p, s, pl.character(p), p.getInventory().getHeldItemSlot());
        else if (id.equals("end")) endTurn(p, s, pl.character(p));   // Marek 11-14: end turn could not be clicked at an enemy
    }

    /** The dummies take no harm from anything. */
    @EventHandler
    public void onDummyHurt(EntityDamageEvent e) {
        for (State s : states.values()) if (s.dummies.contains(e.getEntity().getUniqueId())) { e.setCancelled(true); return; }
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        State s = states.get(e.getPlayer().getUniqueId());
        if (s == null || s.env) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        int slot = p.getInventory().getHeldItemSlot();
        p.getInventory().setHeldItemSlot(slot == 0 ? 1 : 0);
        Sfx.at(p.getLocation(), "hands");
        show(p, s, pl.character(p), "the other hand (0 AP)");
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent e) {
        State s = states.get(e.getPlayer().getUniqueId());
        if (s != null) pl.getServer().getScheduler().runTask(pl, () -> show(e.getPlayer(), s, pl.character(e.getPlayer()), null));
    }

    /**
     * Shift: the next stance, standing → crouched → prone → standing (Fallout Tactics' cost: 2 AP to or from a crouch, 4 straight
     * between standing and prone). A change is movement: back to a stance held earlier on this same block, with no step since,
     * undoes the changes after it and refunds their AP (a full round from standing on one block costs nothing).
     */
    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        State s = states.get(e.getPlayer().getUniqueId());
        if (s == null || !e.isSneaking()) return;
        Player p = e.getPlayer();
        Character c = pl.character(p);
        if (s.enemyTurn) { show(p, s, c, "the raiders' turn: wait"); return; }
        Location at = p.getLocation().toBlockLocation();
        int cur = s.stance(), target = (cur + 1) % 3;
        // an undo: the latest earlier leg in that stance with no walking since it began
        for (int j = s.legs.size() - 2; j >= 0; j--) {
            int k = j + 1;   // every leg after j must be unwalked (the later ones were checked in the earlier turns of the loop)
            Location end = k + 1 < s.legs.size() ? s.legs.get(k + 1).from() : at;
            if (blocks(s.legs.get(k).from(), end) > 0) break;
            if (s.legs.get(j).stance() == target) {
                int undone = s.legs.size() - 1 - j;
                while (s.legs.size() > j + 1) s.legs.remove(s.legs.size() - 1);
                setAp(s, c, at);
                show(p, s, c, "back to " + Rules.STANCES[target] + ": " + undone + " stance change" + (undone > 1 ? "s" : "") + " refunded");
                return;
            }
        }
        int cost = Rules.stanceChangeAp(cur, target);
        s.legs.add(new Leg(target, at));
        Sfx.at(p.getLocation(), "stance");
        if (spent(s, c, at) > s.apAtAnchor + 1e-9) {
            s.legs.remove(s.legs.size() - 1);
            show(p, s, c, "not enough AP to go " + Rules.STANCES[target] + " (" + cost + " AP)");
            return;
        }
        setAp(s, c, at);
        show(p, s, c, "now " + Rules.STANCES[target] + " (" + cost + " AP; shift round to an earlier stance here to undo)");
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        State s = states.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        Player p = e.getPlayer();
        Character c = pl.character(p);
        if (!e.hasChangedBlock()) return;   // looking around: the aim is read each tick (tickAim)
        if (s.enemyTurn) { e.setCancelled(true); show(p, s, c, "the raiders' turn: wait"); return; }
        Location to = e.getTo().toBlockLocation();
        if (spent(s, c, to) > s.apAtAnchor + 1e-9) {
            e.setCancelled(true);
            show(p, s, c, "no AP left to go further");
            return;
        }
        setAp(s, c, to);
        show(p, s, c, null);
        if (s.fight != null && !s.fight.over && atEdge(s.fight.field, to)) {
            Fight f = s.fight;
            f.fled.add(p.getUniqueId());
            tell(f, p.getName() + " gets away at the edge of the map.", NamedTextColor.YELLOW);
            pl.getServer().getScheduler().runTask(pl, () -> { if (inBattle(p) && states.get(p.getUniqueId()).fight == f) stop(p); });
        }
    }

    /** The same from a test: the player steps onto the map's edge (as onMove after a paid step). */
    boolean leaveAtEdge(Player p) {
        State s = states.get(p.getUniqueId());
        if (s == null || s.fight == null || s.fight.field == null || s.fight.over) return false;
        s.fight.fled.add(p.getUniqueId());
        stop(p);
        return true;
    }

    @EventHandler
    public void onPack(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        State s = states.get(p.getUniqueId());
        if (s == null || e.getView().getTopInventory().getType() != InventoryType.CRAFTING) return;
        if (s.packCharged) return;
        Character c = pl.character(p);
        if (s.enemyTurn) { e.setCancelled(true); show(p, s, c, "the raiders' turn: wait"); return; }
        if (!pay(p, s, c, 4, "the pack")) { e.setCancelled(true); return; }
        s.packCharged = true;
        show(p, s, c, "into the pack: 4 AP (once per opening)");
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        State s = states.get(e.getPlayer().getUniqueId());
        if (s != null) s.packCharged = false;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { stop(e.getPlayer()); }

    /** In creative mode a left click breaks blocks at once: not during the demo. */
    @EventHandler
    public void onBreak(org.bukkit.event.block.BlockBreakEvent e) { if (inBattle(e.getPlayer())) e.setCancelled(true); }

    @EventHandler
    public void onPlace(org.bukkit.event.block.BlockPlaceEvent e) { if (inBattle(e.getPlayer())) e.setCancelled(true); }
}
