package cz.lollywood.falloutak;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.*;

/**
 * The always-on numbers outside battle: the action bar (HP, AP, rads) and the survival sidebar, which by default lists only the
 * stats past stage 0 (so it is nearly empty when all is well); {@code /sheet hud} shows all nine. The demo clock runs one game
 * hour a second so the stages can be watched.
 */
public final class Hud {
    private final FalloutakPlugin pl;
    final Set<UUID> showAll = new HashSet<>(), demo = new HashSet<>();
    private final Map<UUID, Scoreboard> boards = new HashMap<>();

    Hud(FalloutakPlugin pl) { this.pl = pl; }

    private int seconds;

    void tick() {
        seconds++;
        pl.zones.show();   // the visible gases as particles (real time is only for what is seen)
        pl.anomalies.tells();      // the anomalies' tells (09c, round 9)
        pl.anomalies.rainTick();   // the Black Rain: its warning, who is out in it, its end
        for (Player p : Bukkit.getOnlinePlayers()) {
            Character c = pl.characters.get(p.getUniqueId());
            if (c == null) continue;
            if (c.away) continue;   // out of the game (round 11: /game off): the world leaves him alone
            // the load (round 11): what he carries (the demo's: his own inventory, kept aside), his speed by it
            Battle.State bs = pl.battle.state(p);
            c.setLoad(bs != null && bs.saved != null ? Items.weight(bs.saved) : Items.weight(p, c));
            boolean gmSpeed = p.getGameMode() == org.bukkit.GameMode.CREATIVE || p.getGameMode() == org.bukkit.GameMode.SPECTATOR;
            float speed = gmSpeed ? Math.max(0.2f, p.getWalkSpeed()) : switch (c.loadTier()) { case 0 -> 0.2f; case 1, 2 -> 0.1f; default -> 0f; };
            if (Math.abs(p.getWalkSpeed() - speed) > 1e-4) Battle.cosmetic(() -> p.setWalkSpeed(speed));
            c.submerged = headInWater(p);   // the air is read from the world; Oxygen moves only with game time (03b §3c)
            // the sun (round 15): the day by the game clock, nothing over the head, no storm in the world
            c.inSun = !c.night() && !p.getWorld().hasStorm() && p.getGameMode() != org.bukkit.GameMode.CREATIVE
                    && p.getGameMode() != org.bukkit.GameMode.SPECTATOR && Anomalies.openSky(p.getWorld(), p.getLocation());
            c.nearRads = glowNear(p);       // 03b §7b, round 9: the glowing ones near are radiation sources
            pl.zones.apply(c, p);           // the gas zone his eyes are in (Marek 11-18: localized)
            pl.anomalies.apply(p, c);       // the anomaly his body is in (09c, round 9)
            pl.anomalies.belt(p, c);        // the artifacts on his belt
            Map<String, Integer> carry = Items.carried(p);   // what he wears (05c), the big gas mask with the filters he carries
            // 04b §5 (Marek 11-24: washing with soap is a setting): a new bar from his pack when the last one is used up
            if (c.washSoap && c.soapDays <= 0 && carry.containsKey(Diseases.SOAP_ID) && Items.take(p, Diseases.SOAP_ID)) {
                c.soapDays += Diseases.SOAP_DAYS;
                p.sendMessage(net.kyori.adventure.text.Component.text("A new bar of soap (it lasts " + Math.round(Diseases.SOAP_DAYS) + " days).",
                        net.kyori.adventure.text.format.NamedTextColor.GRAY));
            }
            c.soap = (c.washSoap && c.soapDays > 0)
                    || (!c.outside && pl.production != null && pl.production.washSoap && pl.production.soapToday);   // the base's soap
            List<Character> near = new ArrayList<>();
            for (Player q : p.getWorld().getPlayers())
                if (q != p && q.getLocation().distanceSquared(p.getLocation()) <= 64) near.add(pl.character(q));
            c.nearby = near;
            String det = pl.anomalies.detect(p, carry);      // the anomaly detector (09c §1.4)
            c.setArmour(Items.readArmour(p), carry.containsKey("gear:Gas filter"), carry.containsKey("gear:CO filter"));
            // Marek 11-18: "battle rounds should also be active during taking damage from environment"
            // (not in creative or spectator mode: the GM placing a zone around himself is not caught in it)
            boolean gmMode = p.getGameMode() == org.bukkit.GameMode.CREATIVE || p.getGameMode() == org.bukkit.GameMode.SPECTATOR;
            if (!gmMode && !pl.battle.inBattle(p) && c.envHarm()) pl.battle.startEnv(p, c);
            if (demo.contains(p.getUniqueId())) hour(c);
            // out of battle a round of bleeding every 6 s [mine: a round of about six seconds]; in battle at each end of turn
            // no real time (Marek 11-16): bleeding, clinical death and the rest move only when game time passes (pass(), a fight's rounds)
            if (!pl.battle.inBattle(p)) {
                double rads = c.survival.get(Rules.Stat.RADS);
                p.sendActionBar(Component.text("HP " + c.hp + "/" + c.hpMax(), c.hp > 0 ? NamedTextColor.RED : NamedTextColor.DARK_RED)
                        .append(Component.text(" · AP " + c.apMax(), NamedTextColor.AQUA))
                        .append(Component.text(" · Rads " + Math.round(rads), NamedTextColor.YELLOW))
                        .append(Component.text(c.bleeding > 0 ? " · BLEEDING " + Txt.f(c.bleeding) + " Blood/round" : "", NamedTextColor.DARK_RED))
                        .append(Component.text(det, NamedTextColor.LIGHT_PURPLE))
                        .append(Component.text(pl.anomalies.rainNote(p, c), NamedTextColor.DARK_RED))   // the Black Rain's countdown (round 10)
                        .append(Component.text(c.loadTier() > 0 ? " · OVERLOADED " + Math.round(c.carriedKg) + "/" + Math.round(c.capacityKg()) + " kg" : "", NamedTextColor.RED))
                        .append(Component.text(demo.contains(p.getUniqueId()) ? "  [demo clock: 1 s = 1 h]" : "", NamedTextColor.DARK_GRAY)));
            }
            Battle.cosmetic(() -> sidebar(p, c));   // the look only (the test server lacks parts of the scoreboard)
            senses(p, c);
        }
    }

    /**
     * What the glowing ones within {@link Rules#GLOW_REACH} blocks shed an hour (03b §7b, `09b` round 9, Marek 11-23: *"it is a radiation
     * source and is losing rads"*): the players and the NPC figurants near, not himself.
     */
    double glowNear(Player p) {
        double s = 0, r = Rules.GLOW_REACH;
        for (org.bukkit.entity.Entity e : p.getNearbyEntities(r, r, r)) {
            if (e.equals(p) || e.getLocation().distanceSquared(p.getLocation()) > r * r) continue;
            String npc = Items.npcName(e);
            Character g = e instanceof Player q ? pl.characters.get(q.getUniqueId()) : npc == null ? null : pl.npc(npc);
            if (g != null) s += g.shedRate();
        }
        return s;
    }

    /**
     * One game hour: the workbook's hourly changes (Character column E) with parts 3 and 4 in them ({@link Character#hourly}), the
     * timed effects run down and a chem's stage, run out, gives way to its next one (03b §8).
     */
    /**
     * Every second (Marek 11-19: the effects that need no resource pack): the body's queued sounds, the Geiger counter where the place
     * has rads, the hiss of a gas around you, and two screen overlays: the frost while a Hypothermia stage lasts, a red edge for
     * radiation sickness or low HP (a per-player world border's warning tint).
     */
    void senses(Player p, Character c) {   // package-visible: the tests call it
        for (String k : c.cues) Sfx.at(p.getLocation(), k);
        c.cues.clear();
        java.util.Random rnd = java.util.concurrent.ThreadLocalRandom.current();
        int clicks = geigerClicks(c.locationRads, rnd.nextDouble());
        for (int i = 0; i < clicks; i++) Sfx.to(p, "geiger");
        if (c.gasHere() != null && rnd.nextInt(3) == 0) Sfx.to(p, "gas.hiss");
        int frost = frost(c.stages());
        Battle.cosmetic(() -> { p.lockFreezeTicks(frost > 0); p.setFreezeTicks(frost); });
        if (frost >= 70 && rnd.nextInt(8) == 0) Sfx.to(p, "cold");
        double red = redEdge(c.stages(), c.hp, c.hpMax());
        Battle.cosmetic(() -> redEdge(p, red));
    }

    /** Geiger clicks this second: none under 1 rad an hour (part 4's background), one at 1-10, up to four at 100 and more [mine]. */
    static int geigerClicks(double radsPerHour, double roll) {
        if (radsPerHour < 1) return 0;
        double rate = Math.min(4, 1 + Math.log10(radsPerHour) * 1.5);   // 1 → 1, 10 → 2.5, 100 → 4
        int n = (int) rate;
        return n + (roll < rate - n ? 1 : 0);
    }

    /** The frost overlay's ticks for the cold stage (Minecraft's own powder-snow frost; 140 = frozen, kept below it so the game deals no damage). */
    static int frost(List<String> stages) {
        for (String s : stages) {
            if (!s.endsWith("Hypothermia")) continue;
            return s.startsWith("Minor") ? 35 : s.startsWith("Advanced") ? 70 : s.startsWith("Critical") ? 105 : 139;
        }
        return 0;
    }

    /** The red edge's strength 0-1: radiation sickness by its stage, and HP at a quarter or less (dying = the strongest) [mine]. */
    static double redEdge(List<String> stages, int hp, int hpMax) {
        double r = 0;
        for (String s : stages)
            if (s.endsWith("Radiation Poisoning"))
                r = Math.max(r, s.startsWith("Minor") ? 0.15 : s.startsWith("Advanced") ? 0.3 : s.startsWith("Critical") ? 0.45 : s.startsWith("Deadly") ? 0.6 : 0.75);
        if (hp <= 0) r = Math.max(r, 0.8);
        else if (hp * 4 <= hpMax) r = Math.max(r, 0.4 + 0.3 * (1 - hp * 4.0 / hpMax));
        return r;
    }

    /**
     * The tint: a world border for this player alone, as big as Minecraft allows and centred on him, whose warning distance is set so
     * his distance to it gives the strength (the client tints by 1 − distance / warning distance); 0 gives the world's own border back.
     */
    static void redEdge(Player p, double strength) {
        if (strength <= 0) { if (p.getWorldBorder() != null) p.setWorldBorder(null); return; }
        org.bukkit.WorldBorder b = org.bukkit.Bukkit.createWorldBorder();
        double size = 59_999_968, dist = size / 2;
        b.setCenter(p.getLocation().getX(), p.getLocation().getZ());
        b.setSize(size);
        b.setWarningDistance((int) Math.min(Integer.MAX_VALUE, dist / (1 - Math.min(0.95, strength))));
        p.setWorldBorder(b);
    }

    static void hour(Character c) {
        c.clock += 1;
        if (c.washSoap && c.soapDays > 0) c.soapDays = Math.max(0, c.soapDays - 1 / 24.0);   // 04b §5: the bar wears down
        if (c.working && !c.sleeping && !c.dead && !c.inArrest() && !c.unconscious()) c.workedHours++;   // 06b §5.4.7
        List<String> stages0 = c.stages();
        double rads0 = c.survival.getOrDefault(Rules.Stat.RADS, 0.0);
        double[] d = new double[Rules.Stat.values().length];
        for (Rules.Stat st : Rules.Stat.values()) d[st.ordinal()] = c.hourly(st);
        for (Rules.Stat st : Rules.Stat.values()) {
            double v = c.survival.get(st) + d[st.ordinal()];
            c.survival.put(st, Math.max(0, Math.min(st == Rules.Stat.RADS ? (c.glowing() ? Rules.GLOW_CAP : 2000) : c.max(st), v)));
        }
        if (c.other("cold_cap") > 0)   // the ember (09c §3): Hypothermia stops at Minor
            c.survival.put(Rules.Stat.HYPOTHERMIA, Math.min(c.survival.getOrDefault(Rules.Stat.HYPOTHERMIA, 0.0), 0.4 * c.max(Rules.Stat.HYPOTHERMIA)));
        // the note on Pain: "When over 75, it lowers to 75 after hour of resting" (resting = asleep here)
        if (c.sleeping && c.survival.get(Rules.Stat.PAIN) > 75) c.survival.put(Rules.Stat.PAIN, 75.0);
        double floor = c.other("pain_floor", true);   // the mutation Chronic Pain: never below 10
        if (floor > 0 && c.survival.get(Rules.Stat.PAIN) < floor) c.survival.put(Rules.Stat.PAIN, floor);
        String stageHp = c.stageHour(stages0);   // the stages' HP Current (an hour; radiation's once) and HP Max (04, RULED 11-15)
        if (stageHp != null) c.news.add(stageHp);
        String sun = c.sunHour();   // the sun on a body that cannot bear it (round 15)
        if (sun != null) c.news.add(sun);
        String crisis = c.crisis(java.util.concurrent.ThreadLocalRandom.current());   // 03b §7b: the crisis at 1 000 rads (round 7)
        if (crisis != null) c.news.add(crisis);
        c.glowHour();                                                                // 03b §7b: the glowing one sheds (round 9)
        if (Math.round(c.clock) % 24 == 0) {                                         // 03b §7b: the day's episode roll (round 8)
            String ep = c.episode(java.util.concurrent.ThreadLocalRandom.current(), false);
            if (ep != null) c.news.add(ep);
            if (Menus.pl != null && org.bukkit.Bukkit.getServer() != null)            // 07 §19: the food carried spoils by the day
                for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers())
                    if (Menus.pl.character(p) == c) { String sp = Spoil.carried(p, c); if (sp != null) c.news.add(sp); }
        }
        heal(c);
        c.radiationRolls(rads0, c.survival.getOrDefault(Rules.Stat.RADS, 0.0), java.util.concurrent.ThreadLocalRandom.current());   // 03d §5
        c.therapyHour();   // 03d §2, §10
        String graft = c.graftHour(java.util.concurrent.ThreadLocalRandom.current());   // 03e §4, 09e §6: the grafts' drugs and rejection
        if (graft != null) c.news.add(graft);
        double radsNow = c.survival.getOrDefault(Rules.Stat.RADS, 0.0);              // 04b §3b: the lifetime's rads
        if (radsNow > c.radsSeen) c.lifetimeRads += radsNow - c.radsSeen;
        c.radsSeen = radsNow;
        String sick = Diseases.hour(c, java.util.concurrent.ThreadLocalRandom.current());   // 04b: the diseases' race (09e round 3)
        if (sick != null) c.news.add(sick);
        String cond = Conditions.hour(c, java.util.concurrent.ThreadLocalRandom.current());   // 04b §3c: trench foot, scabies, latent TB
        if (cond != null) c.news.add(cond);
        c.gasHour();       // 03b §3c: mercury vapour
        String pa = c.paHour();   // 05c §3c: Power Armour Training by wearing the suit
        if (pa != null) c.news.add(pa);
        String arrest = c.checkArrest(java.util.concurrent.ThreadLocalRandom.current());   // 03b §3c: Blood 0, the cold's or heat's end, E 0
        if (arrest != null) c.news.add(arrest);
        String rebound = c.reboundHour();   // 04i §6a: propranolol stopped after a week
        if (rebound != null) c.news.add(rebound);
        String knit = c.splintHour();       // 0.108.0: a splinted leg knits in 6 weeks
        if (knit != null) c.news.add(knit);
        List<Character.Effect> next = new ArrayList<>();
        boolean release = false;
        for (Character.Effect e : new ArrayList<>(c.effects)) {
            if (e.permanent()) { next.add(e); continue; }
            if (e.hoursLeft() - 1 > 1e-9) { next.add(e.withHours(e.hoursLeft() - 1)); continue; }
            if (e.slot().equals("held_hp")) { release = true; continue; }   // 04i §6a: the hold's hour ends
            if (e.other().getOrDefault("mend_limb", 0.0) > 0 && c.brokenLegs > 0) {   // 04i §6a: Hydra's bone knits in its 3 days
                c.brokenLegs--;
                c.trimSplints();
                c.news.add("Hydra: a broken leg has mended (" + c.brokenLegs + " broken)");
            }
            Character.Effect follow = c.afterStage(e);
            if (follow != null) next.add(follow);
        }
        c.effects.clear();
        c.effects.addAll(next);
        if (release) { String r = c.releaseHeld(); if (r != null) c.news.add(r); }
    }

    /**
     * Healing (part 4: every 8 hours of rest; Marek 11-16: a revived man at −Endurance gets *"the normal healing rate"* from *"8 hours
     * of rest"* *"if the situation does not worsen"*): 8 hours resting in a row, not bleeding, heal the healing rate (× the template's,
     * Coldvein ½; × a mutation's). A revenant in clinical death mends 1 HP a day and wakes above −Endurance with 10 % of its XP lost.
     */
    static void heal(Character c) {
        if (c.dead) return;
        if (c.inArrest()) {
            c.restHours = 0;
            if (!c.tpl().atDeath().equals("self-mend")) return;
            c.mendHours += 1;
            if (c.mendHours >= 24) { c.mendHours = 0; c.hp += 1; }
            if (c.hp >= Rules.deathFloor(c.attrs(false))) {
                c.arrestAt = -1;
                long floorXp = Rules.xpForLevel(c.level);
                c.xp = Math.max(floorXp, c.xp - c.xp / 10);
            }
            return;
        }
        if (c.glowing()) { glowHeal(c); return; }
        int rate = c.healingRate();
        if (rate < 0) {   // Fatal Radiation Poisoning (Marek 11-19): the body loses the rate every 8 hours, resting or not
            c.restHours = 0;
            c.sickHours += 1;
            if (c.sickHours >= 8) {
                c.sickHours = 0;
                c.news.add(Character.FATAL_RADIATION + ": HP " + rate + c.drain(-rate));
            }
            return;
        }
        c.sickHours = 0;
        if (!c.sleeping || c.bleeding > 0) { c.restHours = 0; return; }
        c.restHours += c.other("rest_double") > 0 ? 2 : 1;   // Nightcap (04j §7c): 4 hours of unbroken sleep count as 8
        if (c.restHours < 8) return;
        c.restHours = 0;
        double f = c.tpl().healFactor() * Math.max(0, 1 + c.other("heal_factor", true));
        if (rate == 0 || c.other("no_healing") > 0) return;   // radiation's healing rate can take it all (04 §3.8); scurvy (04b §3c)
        c.hp = Math.min(c.hpMax(), c.hp + Math.max(1, (int) Math.round(rate * f)));
    }

    /**
     * The glowing one's 8 hours (03b §7b, `09b` round 9, Marek 11-23: *"it sheds the radiation to increase healing rate (4 rads per +1
     * rate for single rest)"* · *"They have negative healing rate and only increase it with rads."*): every 8 hours of its own clock the
     * rate is {@link Rules#glowRate} of what it shed in them; below 0 it loses that many HP, resting or not; above 0 it heals that many
     * if the 8 hours were rest (asleep, not bleeding).
     */
    static void glowHeal(Character c) {
        c.sickHours = 0;
        if (c.sleeping && c.bleeding <= 0) c.restHours += 1; else c.restHours = 0;
        c.glowHours += 1;
        if (c.glowHours < 8) return;
        int rate = Rules.glowRate(c.glowShed, c.other("healing_half") > 0);
        boolean rested = c.restHours >= 8;
        c.glowHours = 0;
        c.glowShed = 0;
        c.restHours = 0;
        if (rate < 0) c.news.add("the glowing one is running down (too few rads shed): HP " + rate + c.drain(-rate));
        else if (rate > 0 && rested) c.hp = Math.min(c.hpMax(), c.hp + rate);
    }

    private void sidebar(Player p, Character c) {
        Scoreboard sb = boards.computeIfAbsent(p.getUniqueId(), u -> Bukkit.getScoreboardManager().getNewScoreboard());
        if (p.getScoreboard() != sb) p.setScoreboard(sb);
        Objective o = sb.getObjective("fo");
        if (o == null) {
            o = sb.registerNewObjective("fo", Criteria.DUMMY, Component.text("Survival", NamedTextColor.GOLD));
            o.setDisplaySlot(DisplaySlot.SIDEBAR);
            o.numberFormat(NumberFormat.blank());
        }
        o.displayName(Component.text(c.timeText(), NamedTextColor.GOLD));   // the time of day on top (Marek 11-16)
        for (String entry : new ArrayList<>(sb.getEntries())) sb.resetScores(entry);   // rewrite the lines, keep the objective (no flicker)
        boolean all = showAll.contains(p.getUniqueId());
        int line = 20, shown = 0;
        for (Rules.Stat st : Rules.Stat.values()) {
            double v = c.survival.get(st), m = c.max(st);
            String sg = c.stageOf(st, v, m);
            if (sg == null && !all) continue;
            var score = o.getScore("l" + line);
            score.setScore(line--);
            score.customName(Component.text(c.statName(st) + " " + Math.round(v) + "/" + Math.round(m) + "  ", NamedTextColor.WHITE)
                    .append(Component.text(sg == null ? "fine" : c.label(sg), SheetView.color(Rules.level(sg)))));
            shown++;
        }
        if (c.hp <= 0 || c.dead || c.inArrest() || c.unconscious()) {
            var score = o.getScore("l" + line);
            score.setScore(line--);
            double[] m = c.arrestMinutes();
            String s = c.dead ? "DEAD" : c.inArrest() ? "CLINICAL DEATH " + clock(m[0] + m[1]) + (c.cprFrom > 0 ? " (CPR)" : "")
                    + (c.arrestCause.isEmpty() ? "" : " · " + c.arrestCause)
                    : c.unconscious() ? "UNCONSCIOUS" + (c.hp <= 0 ? " · DYING HP " + c.hp : "")
                    : "DYING  HP " + c.hp + " (clinical death below " + Rules.deathFloor(c.attrs(false)) + ")";
            score.customName(Component.text(s, NamedTextColor.DARK_RED));
            shown++;
        }
        for (String s : around(c)) {   // 03b §3c: the air, the gas, the ears, what is worn
            var score = o.getScore("l" + line);
            score.setScore(line--);
            score.customName(Component.text(s, NamedTextColor.GOLD));
            shown++;
        }
        if (shown == 0) {
            var score = o.getScore("l" + line);
            score.setScore(line);
            score.customName(Component.text("all well  (/sheet hud: all)", NamedTextColor.GREEN));
        }
    }

    /**
     * /sheet hit n [crit|stab]: a wound. HP −n; Pain +3 a HP lost, +25 on a crit (the workbook's note); a crit of normal damage
     * opens a weak bleeding, a stab a strong one (Andor's numbers, absolute Blood, {@link Rules#bleedingPerRound}). Which crits bleed is part 5's table.
     */
    /**
     * GAME time passes for a character (Marek 11-16: *"only DM changes it"*; the GM's menu, or a fight's rounds): bleeding a round
     * every 6 s of it (a round = 6 s [mine, as D&D]), whole hours through {@link #hour}, and final death when the clinical death
     * window runs out. Returns a note when something happened.
     */
    static String pass(Character c, double minutes) {
        if (c.outside && minutes > 0) c.unwashedDays += minutes / 1440;   // round 9: outside, his own days since a wash
        int rounds = (int) Math.round(minutes * 10);
        Random rnd = java.util.concurrent.ThreadLocalRandom.current();
        // round by round while rounds change anything (bleeding, the air, a gas: 03b §3c), else straight to the next whole hour
        while (rounds > 0 && !c.dead) {
            int k = 1;
            if (c.roundsMatter()) round(c, rnd);
            else k = Math.max(1, Math.min(rounds, (int) Math.ceil((60 - c.minuteAcc) * 10 - 1e-6)));
            rounds -= k;
            c.minuteAcc += k * 0.1;
            while (c.minuteAcc >= 60 - 1e-6) { c.minuteAcc = Math.max(0, c.minuteAcc - 60); hour(c); }
            if (c.windowGone()) break;
        }
        if (rounds > 0) { c.minuteAcc += rounds * 0.1; while (c.minuteAcc >= 60 - 1e-6) { c.minuteAcc = Math.max(0, c.minuteAcc - 60); hour(c); } }
        String news = c.takeNews();
        if (c.windowGone()) { c.dead = true; return "FINAL DEATH: the window ran out (implants can still be taken out within a day)." + (news == null ? "" : " " + news + "."); }
        return news == null ? null : news + ".";
    }

    /** One round of the body (6 s): bleeding, then breathing and the gas (03b §3c); its news goes to the character's news. */
    static void round(Character c, Random rnd) {
        bleedRound(c);
        String r = c.breathRound(rnd);
        if (r != null) c.news.add(r);
        String a = Menus.pl == null || Menus.pl.anomalies == null ? null : Menus.pl.anomalies.round(c, rnd);   // 09c §2 (round 9)
        if (a != null) c.news.add(a);
        String b = Anomalies.rainRound(c, rnd);                                                            // the Black Rain
        if (b != null) c.news.add(b);
    }

    static String clock(double minutes) { int s = (int) Math.round(minutes * 60); return s / 60 + ":" + String.format("%02d", s % 60); }

    /** The head under water: water, a bubble column, kelp or seagrass, or a waterlogged block at the eyes. */
    static boolean headInWater(Player p) {
        org.bukkit.block.Block b = p.getEyeLocation().getBlock();
        switch (b.getType()) {
            case WATER, BUBBLE_COLUMN, KELP, KELP_PLANT, SEAGRASS, TALL_SEAGRASS -> { return true; }
            default -> { return b.getBlockData() instanceof org.bukkit.block.data.Waterlogged w && w.isWaterlogged(); }
        }
    }

    /** The sidebar's lines on the air, the gas, the ears and the gear worn (03b §3c); none when all is plain. */
    static List<String> around(Character c) {
        List<String> l = new ArrayList<>();
        if (c.submerged) l.add("UNDER WATER: no air");
        else if (!c.air) l.add("NO AIR here");
        Rules.Gas g = c.gasHere();
        if (g != null) l.add(cap(g.name()) + " " + Rules.roman(c.gasPower()) + (c.inZone() ? " (here)" : "") + (c.shielded(g) ? (c.eyesShielded(g) ? " (kept out)" : " (breath kept out)") : ""));
        if (c.holdingBreath) l.add("holding your breath");
        if (c.breathingStage()) l.add(c.breathHelpUntil > c.now() ? "BREATHING STOPPED: a medic breathes for you"
                : "BREATHING STOPPED (an opioid and a second depressant): naloxone, or a medic breathes for you");
        if (c.holding) l.add("held HP: " + Math.max(0, c.hp - c.heldReal) + " go when the hour ends");
        if (c.oxygenExtra > 0) l.add("the O-Shot: " + c.oxygenExtra + " rounds of breath");
        if (c.riotUntil > c.now() && (g == null || !g.name().equals("riot gas"))) l.add("riot gas in the eyes");
        if (c.deaf()) l.add("DEAF (P −2)");
        else if (c.burstEars() > 0) l.add("a burst eardrum (P −1)");
        else if (c.ringing()) l.add("ringing ears (P −1)");
        if (!c.wearing.isEmpty()) l.add("wearing: " + String.join(", ", c.wearing) + (c.wearing.contains("Air bottle") ? " " + Math.round(c.bottleMinutes) + " min" : ""));
        return l;
    }

    static String cap(String s) { return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1); }

    static String hit(Character c, int n, String kind) {
        boolean crit = kind.equals("crit") || kind.equals("stab");
        if (c.dead) return "the body takes " + n;
        c.hp -= n;
        double pain = Rules.painOfHit(n, crit) * c.painGain();
        c.survival.put(Rules.Stat.PAIN, Math.min(100, c.survival.get(Rules.Stat.PAIN) + pain));
        if (crit) c.bleeding = Rules.bleedingPerRound(c.bleeding, kind.equals("stab"));
        int floor = Rules.deathFloor(c.attrs(false));
        String defib = "";
        if (c.hp < floor && c.arrestAt < 0) {
            c.arrestAt = c.now();   // clinical death begins (03b §5), on the game clock
            defib = c.autoDefibrillate(java.util.concurrent.ThreadLocalRandom.current());   // an implanted defibrillator (03b §3b)
        }
        String what = "hit for " + n + (crit ? " (" + kind + ")" : "") + ": Pain +" + Txt.f(pain)
                + (c.bleeding > 0 ? ", bleeding " + Txt.f(c.bleeding) + " Blood a round (/sheet firstaid)" : "") + defib;
        return c.hp > 0 ? what + "; HP " + c.hp
                : c.hp >= floor ? what + "; DYING (HP " + c.hp + ", dead below " + floor + "): Pain and Blood decide what you can do"
                : what + "; CLINICAL DEATH: " + Battle.clinical(c);
    }

    /** One round of bleeding: −rate Blood, absolute (Marek 11-14), so a bigger body (Blood = 4 x HP max) lasts longer. */
    static void bleedRound(Character c) {
        if (c.bleeding <= 0) return;
        double b = c.survival.get(Rules.Stat.BLOOD) - (c.bleeding + Math.max(0, c.other("bleeding"))) * c.tpl().bleedFactor();   // Coldvein bleeds x 1/2; ibuprofen, aspirin +1 (04g §4b)
        c.survival.put(Rules.Stat.BLOOD, Math.max(0, b));
    }
}
