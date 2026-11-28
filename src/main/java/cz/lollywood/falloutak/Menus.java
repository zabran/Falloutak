package cz.lollywood.falloutak;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The menus (Marek 11-16: *"I will need some DM menu and all players will need some actions options. Finding all actions by
 * instruction and not being sure which you shuld use at the time is bad. Please, think about how it should work in a game."*).
 * The design (`_notes/Fallout/10-PLUGIN-MENUS.md`): nobody types a command. The Pip-Boy (right click) opens YOUR actions, only
 * those you can take now, the urgent ones first, each with what it does and its chance; right-clicking another character opens the
 * actions ON HIM; what you cannot do now is listed with the reason. Players choose what they do; time passes by the GM (his
 * menu's clock) or the demo clock. The GM's menu: pick a character, then body, items, changes, time.
 */
final class Menus {
    private Menus() {}

    static FalloutakPlugin pl;

    // ------------------------------------------------------------------ dialog helpers
    /** A button's width: its label's pixels (the client's own font widths) + a margin, 150 to 400 (Marek 11-17: labels overflowed). */
    static int width(String label) { return Math.max(150, Math.min(400, Txt.width(label, false) + 24)); }

    static Ui.Btn btn(String label, String tip, Consumer<Player> then) { return new Ui.Btn(label, tip, 0, (v, p) -> then.accept(p)); }

    static Ui.Btn form(String label, String tip, BiConsumer<DialogResponseView, Player> then) { return new Ui.Btn(label, tip, 0, then); }

    /** A menu of buttons, with lines of text above them (grey = what you cannot do now, and why). */
    static void menu(Player p, String title, List<Component> lines, List<Ui.Btn> buttons, Consumer<Player> back) {
        menu(p, title, lines, List.of(), buttons, back, 2);
    }

    /** A menu with inputs above its buttons (a filter, a name); the buttons made by {@link #form} read them. */
    static void menu(Player p, String title, List<Component> lines, List<Ui.In> inputs, List<Ui.Btn> buttons, Consumer<Player> back, int columns) {
        Ui.Btn exit = back == null ? btn("Close", "close", x -> {}) : btn("Back", "back", back);
        Ui.show(new Ui.Page(p, title, List.copyOf(lines), 400, List.copyOf(inputs), Ui.Type.MULTI, List.copyOf(buttons), exit, columns, true));
    }

    /** Lists longer than this get a filter first (Marek 11-17: "You need to replace that with lists, best with filters."). */
    static final int FILTER_AT = 10;

    /**
     * A list to pick from: one button a value, its details on hover (Marek 11-17: a cycling input is unusable over 3 values); a filter
     * (a text box and a Filter button) above a long list.
     */
    static void pick(Player p, String title, String text, List<String> values, Function<String, String> label, Function<String, String> tip,
                     BiConsumer<Player, String> then, Consumer<Player> back) {
        pick(p, title, text, values, label, tip, then, back, "");
    }

    static void pick(Player p, String title, String text, List<String> values, Function<String, String> label, Function<String, String> tip,
                     BiConsumer<Player, String> then, Consumer<Player> back, String filter) {
        String f = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        List<Ui.Btn> b = new ArrayList<>();
        List<Ui.In> in = new ArrayList<>();
        boolean long_ = values.size() > FILTER_AT;
        if (long_) {
            in.add(Ui.In.text("f", Component.text("Filter")).initial(filter == null ? "" : filter).maxLength(32).build());
            b.add(form("Filter", "show only what contains the text", (v, x) -> pick(x, title, text, values, label, tip, then, back, v.getText("f"))));
        }
        int n = 0;
        for (String v : values) {
            String l = label.apply(v);
            if (!f.isEmpty() && !l.toLowerCase(Locale.ROOT).contains(f) && !tip.apply(v).toLowerCase(Locale.ROOT).contains(f)) continue;
            b.add(btn(l, tip.apply(v), x -> then.accept(x, v)));
            n++;
        }
        List<Component> lines = new ArrayList<>();
        if (!text.isEmpty()) lines.add(line(text, NamedTextColor.GRAY));
        if (n == 0) lines.add(line("Nothing matches: change the filter.", NamedTextColor.RED));
        menu(p, title, lines, in, b, back, 2);
    }

    /** A form: inputs and one confirming button. */
    static void ask(Player p, String title, String text, List<Ui.In> inputs, Ui.Btn yes, Consumer<Player> back) {
        Ui.show(new Ui.Page(p, title, List.of(Component.text(text)), 300, List.copyOf(inputs), Ui.Type.CONFIRM, List.of(yes),
                btn("Back", "back", back), 1, true));
    }

    static Ui.In choice(String key, String label, List<String> values) { return Ui.In.choice(key, label, values); }

    static Component line(String s, NamedTextColor col) { return Component.text(s, col); }

    static void say(Player p, String s) { for (String l : s.split("\n")) p.sendMessage(Component.text(l, l.startsWith("!") ? NamedTextColor.RED : NamedTextColor.YELLOW)); }

    /** Tells every GM online (a surgery, a revival: so the GM can object). */
    static void tellGm(String s) {
        for (Player g : Bukkit.getOnlinePlayers()) if (g.hasPermission("falloutak.gm")) g.sendMessage(Component.text("[GM] " + s, NamedTextColor.GRAY));
    }

    static String pct(double v) { return Txt.f(Math.round(v * 10) / 10.0) + " %"; }

    static String chemText(Data.Chem ch) {
        StringBuilder sb = new StringBuilder();
        if (ch.effect() != null) sb.append(stageText(ch.effect())).append(" for ").append(SheetView.hours(ch.effect().hours()));
        if (ch.crash() != null) sb.append("\nthen ").append(stageText(ch.crash())).append(" for ").append(SheetView.hours(ch.crash().hours()));
        sb.append("\n").append(ch.chance() > 0 ? "addiction " + Math.round(ch.chance() * 100) + " % " + (ch.perDay() ? "a day of use" : "a dose") + " (more with recent doses)" : "no addiction");
        if (ch.withdrawal() != null && ch.chance() > 0) sb.append("\naddicted: ").append(stageText(ch.withdrawal())).append(" for ").append(SheetView.hours(ch.withdrawal().hours()));
        if (ch.cures() != null) sb.append("\nends the ").append(ch.cures()).append(" addiction");
        return sb.toString();
    }

    static String stageText(Data.Stage s) {
        Character.Effect e = new Character.Effect("", "chem", "", false, s.mods(), s.other(), s.hours(), null, 100);
        String t = SheetView.effText(e);
        return t.replace("+1 radaway", "rads out").replace("no numbers", "no change");
    }

    /** Players within {@code r} blocks (not himself). */
    static List<Player> near(Player p, double r) {
        List<Player> l = new ArrayList<>();
        for (Player o : p.getWorld().getPlayers())
            if (!o.equals(p) && o.getLocation().distanceSquared(p.getLocation()) <= r * r) l.add(o);
        return l;
    }

    /** A charging point for a synthetic body [mine]: a redstone block within 3 blocks. */
    static boolean nearCharger(Player p) {
        Location l = p.getLocation();
        for (int x = -3; x <= 3; x++) for (int y = -2; y <= 2; y++) for (int z = -3; z <= 3; z++)
            if (l.clone().add(x, y, z).getBlock().getType() == Material.REDSTONE_BLOCK) return true;
        return false;
    }

    // ------------------------------------------------------------------ who is acted on: a player or an NPC figurant
    /** A character in the world: a player, or an NPC figurant (its statblock; the GM plays it, so it agrees by itself). */
    record Who(String name, Character c, Player player, org.bukkit.entity.Entity entity) {
        void tell(String s, NamedTextColor col) { if (player != null) player.sendMessage(Component.text(s, col)); }
        boolean npc() { return player == null; }
    }

    static Who of(Player p) { return new Who(p.getName(), pl.character(p), p, p); }

    static Who whoOf(org.bukkit.entity.Entity e) {
        if (e instanceof Player p) return of(p);
        String n = Items.npcName(e);
        Character c = n == null ? null : pl.npc(n);
        return c == null ? null : new Who(n, c, null, e);
    }

    /** Players and figurants within {@code r} blocks (not himself). */
    static List<Who> around(Player p, double r) {
        List<Who> l = new ArrayList<>();
        for (org.bukkit.entity.Entity e : p.getNearbyEntities(r, r, r)) {
            if (e.equals(p)) continue;
            Who w = whoOf(e);
            if (w != null) l.add(w);
        }
        return l;
    }

    // ------------------------------------------------------------------ the player's own actions
    static void actions(Player p) {
        Character c = pl.character(p);
        List<Ui.Btn> b = new ArrayList<>();
        List<Component> lines = new ArrayList<>();
        List<String> cannot = new ArrayList<>();
        Map<String, Integer> carry = Items.carried(p);
        double[] am = c.arrestMinutes();
        String state = c.dead ? "DEAD" : c.inArrest() ? "CLINICAL DEATH " + Hud.clock(am[0] + am[1]) + (c.arrestCause.isEmpty() ? "" : " (" + c.arrestCause + ")") + ": only others can act"
                : c.unconscious() ? "UNCONSCIOUS: only others can act"
                : "HP " + c.hp + "/" + c.hpMax() + (c.hp <= 0 ? " (dying)" : "") + (c.bleeding > 0 ? " · bleeding " + Txt.f(c.bleeding) : "")
                + (c.sleeping ? " · " + (c.synthetic() ? "cleaning memory" : "resting") : "");
        lines.add(line(c.name + " · " + state, c.hp > 0 && !c.inArrest() && !c.unconscious() ? NamedTextColor.WHITE : NamedTextColor.RED));
        for (String s : Hud.around(c)) lines.add(line(s, NamedTextColor.GOLD));
        boolean able = !c.dead && !c.inArrest() && !c.unconscious();
        if (pl.battle.inEnv(p)) b.add(btn("! End the round", "6 s of game time pass: the air, the gas, bleeding (03b §3c)", x -> pl.battle.endEnvRound(x)));
        // round 11: rounds by the player's own choice, and off when no enemy is near
        if (!pl.battle.inBattle(p)) b.add(btn("Rounds ON", "time stands still between your rounds: move by AP, act, end each round here", x -> { say(x, pl.battle.roundsOn(x, c)); actions(x); }));
        else b.add(btn("Rounds OFF", "only when no enemy that can fight is within " + (int) Battle.ENEMY_NEAR + " blocks and nothing hurts you", x -> { say(x, pl.battle.roundsOff(x, c)); actions(x); }));
        if (able) { gear(p, c, carry, b, cannot); armourRepair(p, c, carry, b, cannot); }
        if (able) {
            if (c.bleeding > 0 || (c.hp < c.hpMax() && c.inSlot("treatment:dressing") == null))
                b.add(btn(c.bleeding > 0 ? "First aid (stop your bleeding)" : "Dress your wounds", c.bleeding > 0 ? "the bleeding stops"
                        : "Medicine 20 (a check) and a dressing: the healing rate +1 for the next 24 hours", x -> { say(x, firstAid(x, c, c, "your")); actions(x); }));
            // round 9: outside the base a character's own days since a wash count; a wash ends them
            b.add(btn(c.outside ? "Back in the base" : "Leave the base", c.outside ? "the base's daily washing covers you again"
                    : "outside, your own days since a wash count (Dirty from 3, Filthy from 7)", x -> { c.outside = !c.outside; actions(x); }));
            if (c.outside && c.unwashedDays > 0)
                b.add(btn("Wash (2 l)", washText(), x -> { c.unwashedDays = 0; say(x, "Washed. " + washText()); actions(x); }));
            // 04b §5 (Marek 11-24: *"washing with soap should be a setting"*): his switch, a bar from the pack every 14 days
            int bars = carry.getOrDefault(Diseases.SOAP_ID, 0);
            b.add(btn(c.washSoap ? "Soap: ON" : "Soap: OFF", c.washSoap
                    ? (c.soapDays > 0 ? "you wash with soap: catching from the sick ×" + Diseases.SOAP + "; this bar " + Math.round(c.soapDays) + " more days"
                    : "no bar left: carry Soap (Chemicals II) and a new one is taken") + "; " + bars + " in your pack"
                    : "wash with soap: ×" + Diseases.SOAP + " on catching from the sick (04b §5), a bar lasts " + Math.round(Diseases.SOAP_DAYS) + " days; "
                    + bars + " in your pack", x -> { c.washSoap = !c.washSoap; actions(x); }));
            // Marek 11-24: eating and drinking from the pack (in the base the base's daily lot feeds you while the GM passes time)
            List<String> foods = new ArrayList<>(), waters = new ArrayList<>();
            for (String id : carry.keySet()) {
                String[] th = Items.thing(id);
                if (th == null) continue;
                if (th[0].equals("Food")) foods.add(id);
                if (th[0].equals("Water")) waters.add(id);
            }
            String noEat = Meals.cannotEat(c), noDrink = Meals.cannotDrink(c);
            if (!foods.isEmpty() && noEat == null) b.add(btn("Eat", "a portion: Food +" + Math.round(Meals.PORTION) + " (a light meal; "
                    + "the tier is the harm, 04 §3.9)", x -> eat(x, c, false)));
            else cannot.add("Eat: " + (noEat != null ? noEat : "you carry no food"));
            if (!waters.isEmpty() && noDrink == null) b.add(btn("Drink", "a litre: Water +" + Math.round(Meals.LITRE) + " (the tier is the harm, "
                    + "04 §3.9)", x -> eat(x, c, true)));
            else cannot.add("Drink: " + (noDrink != null ? noDrink : "you carry no water"));
            for (Map.Entry<String, Integer> e : Meds.injectors(p).entrySet()) {   // 04i §6a: the Stimpak, the Super Stimpak, the Hypo
                String in = e.getKey();
                if (c.dead || c.inArrest() || (!in.equals("Hypo") && c.hp >= c.hpMax() && c.bleeding <= 0)) continue;
                b.add(btn("Use " + in + " (" + e.getValue() + ")", injectorText(in), x -> { say(x, inject(x, c, "you", in)); actions(x); }));
            }
            if (Meds.count(p, "Splint") > 0 && c.brokenLegs > c.splints.size() && !c.dead)   // 0.108.0
                b.add(btn("Splint your leg (" + Meds.count(p, "Splint") + ")", "your Medicine 20 (a check): it knits in 6 weeks", x -> { say(x, splintWith(x, c, c)); actions(x); }));
            if (Meds.count(p, "Trauma pack") > 0 && !c.dead && !c.inArrest())
                b.add(btn("Trauma pack (" + Meds.count(p, "Trauma pack") + ")", "on yourself: 10 minutes, your Medicine 40 (a check)", x -> { say(x, trauma(x, c, c)); actions(x); }));
            for (Map.Entry<String, Integer> e : Meds.selfUse(p).entrySet()) {   // 04i: vitamin C, a blood bag, antivenom, the vaccines
                String mn = e.getKey();
                b.add(btn("Use " + mn + " (" + e.getValue() + ")", "a dose, on yourself", x -> {
                    if (Meds.take(x, mn, 1) < 1) return;
                    say(x, Meds.useSelf(c, mn, pl.rnd));
                    actions(x);
                }));
            }
            for (Map.Entry<String, Integer> e : carry.entrySet()) {
                if (!e.getKey().startsWith("chem:")) continue;
                Data.Chem ch = Data.CHEMS.get(e.getKey().substring(5));
                if (ch == null) continue;
                b.add(btn("Take " + ch.name() + " (" + e.getValue() + ")", chemText(ch), x -> {
                    if (!Items.take(x, "chem:" + ch.name())) return;
                    say(x, Part3.dose(c, new String[]{"dose", ch.name()}, pl.rnd));
                    actions(x);
                }));
            }
            b.add(btn(c.synthetic() ? (c.sleeping ? "Stop cleaning memory" : "Clean memory") : (c.sleeping ? "Get up" : "Rest / sleep"),
                    (c.synthetic() ? "Memory +4 an hour while time passes" : "while time passes: Sleep +4 an hour, Pain −3")
                            + (c.healingRate() < 0 ? "; Fatal Radiation Poisoning: you LOSE " + -c.healingRate() + " HP every 8 hours, resting or not"
                            : "; every 8 hours of rest without bleeding heal " + c.healingRate() + " HP (your healing rate)"),
                    x -> { c.sleeping = !c.sleeping; c.restHours = 0; if (c.sleeping) c.working = false; actions(x); }));
            b.add(btn(c.working ? "Stop working" : "Work in the base", "downtime work: 1 XP an hour while the GM passes time (06b), "
                    + "into your group's pool too" + (c.workedHours > 0 ? "; " + c.workedHours + " h waiting" : ""),
                    x -> { c.working = !c.working; if (c.working) c.sleeping = false; actions(x); }));
            if (c.synthetic()) {
                if (nearCharger(p)) b.add(btn("Recharge", "an hour at the charging point: Power +30 (the GM passes the hour)", x -> {
                    c.survival.put(Rules.Stat.FOOD, Math.min(c.max(Rules.Stat.FOOD), c.survival.get(Rules.Stat.FOOD) + 30));
                    say(x, "Power " + Math.round(c.survival.get(Rules.Stat.FOOD)) + "/" + Math.round(c.max(Rules.Stat.FOOD)));
                    actions(x);
                }));
                else cannot.add("Recharge: no charging point near (a redstone block within 3 blocks)");
            }
            double rads = c.survival.getOrDefault(Rules.Stat.RADS, 0.0);
            if (rads >= 100 && c.clock - c.lastMutation >= 8760) b.add(btn("Expel rads (yearly)", "once a year: 100-500 rads out for ONE mutation; "
                    + "d100 + (Luck − 5) × 4 − 10 a 100 above the first: 50 % bad, 30 neutral, 20 good at Luck 5", x -> expel(x, c)));
            else if (rads < 100) cannot.add("Expel rads: you hold under 100 rads");
            else cannot.add("Expel rads: once a year (next in " + Math.round((8760 - (c.clock - c.lastMutation)) / 24) + " game days)");
            if (carry.keySet().stream().noneMatch(k -> k.startsWith("chem:"))) cannot.add("Take a chem: you carry none");
        }
        List<Who> by = around(p, 4);
        for (Who o : by) {
            Character oc = o.c();
            String tag = oc.dead ? " (dead)" : oc.inArrest() ? " (CLINICAL DEATH)" : oc.hp <= 0 ? " (dying)" : oc.bleeding > 0 ? " (bleeding)" : "";
            b.add(btn((tag.contains("CLINICAL") || tag.contains("dying") ? "! " : "") + o.name() + (o.npc() ? " (NPC)" : "") + tag,
                    "what you can do for or to " + o.name(), x -> on(x, whoOf(o.entity()))));
        }
        if (by.isEmpty()) cannot.add("Help someone: nobody within 4 blocks");
        if (c.dead && !c.group.isEmpty() && !pl.roster.pop.membersOf(c.group).isEmpty())
            b.add(btn("! A new character from " + c.group, "your character is dead: make a new one from his group (its template and "
                    + "training, XP = the pool / the headcount; your own attributes, traits and perks; the last 2 levels' points again)", x -> takeOverPick(x, c)));
        if (c.perksDue() > 0 && !c.dead) b.add(btn("Pick a perk (" + c.perksDue() + ")", "a perk level: the perks whose gate you meet", x -> perkPick(x, c, null)));
        if (!pl.production.hauls.containsKey(p.getUniqueId().toString()))
            b.add(btn("The last-minute haul", "day D, the prologue: grab ONE haul for the base on the way down into the shelter (once)", Hauls::page));
        b.add(btn("Travel (the world map)", "the party's journey to a place: a roll every 4 hours on the way (part 9d)", x -> Travel.menu(pl, x)));
        b.add(btn("Groups", "the base's people: each group's level, its points to spend (any player levels them up), lessons", Menus::groups));
        b.add(btn("Production", "the base's buildings, their jobs and crews (part 7): any player runs them", Works::main));
        b.add(btn("My sheet", "your character sheet", x -> SheetView.open(x, c, SheetView.Page.OVERVIEW)));
        if (p.hasPermission("falloutak.gm")) b.add(btn("GM menu", "the game master's tools", Menus::gm));
        if (!cannot.isEmpty()) {
            lines.add(line("Not now:", NamedTextColor.DARK_GRAY));
            for (String s : cannot) lines.add(line(" " + s, NamedTextColor.DARK_GRAY));
        }
        lines.add(line("Game time passes only by the GM (or a fight's rounds); right-click someone to act on him.", NamedTextColor.DARK_GRAY));
        menu(p, "Actions", lines, b, null);
    }

    /**
     * The breath and the gear worn (03b §3c): hold your breath in a gas, put on the gas mask with the filters you carry, an air bottle
     * (an hour), earplugs or electronic muffs. Worn things are the character's; the items stay in the pack (an air bottle is used up).
     */
    static void gear(Player p, Character c, Map<String, Integer> carry, List<Ui.Btn> b, List<String> cannot) {
        if (c.holdingBreath) b.add(btn("Breathe again", "Oxygen refills (+5 a round in clean air); the gas gets in", x -> { c.holdingBreath = false; actions(x); }));
        else if (c.gasHere() != null && !c.noAir())
            b.add(btn("Hold your breath", "the " + c.gasHere().name() + " stays out; Oxygen −1 a round (" + Math.round(c.survival.getOrDefault(Rules.Stat.OXYGEN, 0.0))
                    + " left); you gasp at 0", x -> { c.holdingBreath = true; actions(x); }));
        if (c.wearing.contains("Gas mask"))
            b.add(btn("Take off the gas mask", "filters: " + (c.maskFilters.isEmpty() ? "none" : String.join(" + ", c.maskFilters)) + " (4 AP in rounds)", x -> {
                if (!pl.battle.spend(x, 4, "the gas mask off")) { actions(x); return; }
                c.wearing.remove("Gas mask"); c.maskFilters.clear(); actions(x); }));
        else if (carry.containsKey("gear:Gas mask") && !c.wearing.contains("Small gas mask")) {
            boolean gas = carry.containsKey("gear:Gas filter"), co = carry.containsKey("gear:CO filter");
            b.add(btn("Put on the gas mask", (gas || co ? "with your " + (gas && co ? "gas and CO filters" : gas ? "gas filter: every gas but bad air and CO" : "CO filter: CO only")
                    : "no filter: it keeps nothing out") + "; the eyes too (4 AP in rounds)", x -> {
                if (!pl.battle.spend(x, 4, "the gas mask")) { actions(x); return; }
                c.wearing.add("Gas mask");
                c.maskFilters.clear();
                if (gas) c.maskFilters.add("gas");
                if (co) c.maskFilters.add("co");
                actions(x);
            }));
        }
        if (c.wearing.contains("Small gas mask") || (carry.containsKey("gear:Small gas mask") && !c.wearing.contains("Gas mask")))
            b.add(btn(c.wearing.contains("Small gas mask") ? "Take off the small gas mask" : "Put on the small gas mask",
                    "mouth and nose only: the breath, not the eyes (2 AP in rounds; or right click it in hand)", x -> { smallMask(x, c); actions(x); }));
        if (c.wearing.contains("Air bottle"))
            b.add(btn("Take off the air bottle", Math.round(c.bottleMinutes) + " minutes left (kept for the next time)", x -> { c.wearing.remove("Air bottle"); actions(x); }));
        else if (c.bottleMinutes > 0 || carry.containsKey("gear:Air bottle"))
            b.add(btn("Put on the air bottle", (c.bottleMinutes > 0 ? Math.round(c.bottleMinutes) + " minutes left in the open one" : "a new bottle: an hour of air")
                    + "; every gas and no air at all", x -> {
                if (!pl.battle.spend(x, 4, "the air bottle")) { actions(x); return; }
                if (c.bottleMinutes <= 0) { if (!Items.take(x, "gear:Air bottle")) return; c.bottleMinutes = 60; }
                c.wearing.add("Air bottle");
                actions(x);
            }));
        if (carry.containsKey("gear:Nut"))   // 09c §1.3 (round 9)
            b.add(btn("Throw a nut (" + carry.get("gear:Nut") + ")", "where you look, up to 10 blocks: it sets off the first anomaly on its way and shows its edge (2 AP in rounds)", x -> {
                if (!pl.battle.spend(x, 2, "a nut")) { actions(x); return; }
                if (!Items.take(x, "gear:Nut")) return;
                say(x, pl.anomalies.throwNut(x));
                actions(x);
            }));
        if (c.wearing.contains("Hazard suit")) b.add(btn("Take off the hazard suit", "", x -> { c.wearing.remove("Hazard suit"); actions(x); }));
        else if (carry.containsKey("gear:Hazard suit") && !pl.battle.inBattle(p))   // minutes to put on: not in rounds [mine]
            b.add(btn("Put on the hazard suit", Items.describe("gear:Hazard suit"), x -> { c.wearing.add("Hazard suit"); actions(x); }));
        for (String ear : List.of("Earplugs", "Electronic muffs")) {
            boolean plugs = ear.equals("Earplugs");
            if (c.wearing.contains(ear)) b.add(btn((plugs ? "Take out" : "Take off") + " the " + ear.toLowerCase(Locale.ROOT), "", x -> { c.wearing.remove(ear); actions(x); }));
            else if (carry.containsKey("gear:" + ear))
                b.add(btn((plugs ? "Put in" : "Put on") + " the " + ear.toLowerCase(Locale.ROOT), Items.describe("gear:" + ear), x -> {
                    if (!pl.battle.spend(x, 2, "the " + ear.toLowerCase(Locale.ROOT))) { actions(x); return; }
                    c.wearing.removeAll(List.of("Earplugs", "Electronic muffs"));
                    c.wearing.add(ear);
                    actions(x);
                }));
        }
    }

    /**
     * The small gas mask (Marek 11-18: *"Small one I would use as active item, big one seems more like a helmet."*): right click it in
     * hand, or the Actions; 2 AP in rounds; mouth and nose only, so the eyes are not kept from a gas [mine].
     */
    static void smallMask(Player p, Character c) {
        if (c.dead || c.inArrest() || c.unconscious()) return;
        if (c.wearing.contains("Small gas mask")) {
            if (!pl.battle.spend(p, 2, "the small gas mask off")) return;
            c.wearing.remove("Small gas mask");
            c.maskFilters.clear();
            say(p, "the small gas mask is off");
            return;
        }
        if (c.wearing.contains("Gas mask")) { say(p, "! you wear the big gas mask"); return; }
        Map<String, Integer> carry = Items.carried(p);
        if (!carry.containsKey("gear:Small gas mask")) return;
        if (!pl.battle.spend(p, 2, "the small gas mask")) return;
        c.wearing.add("Small gas mask");
        c.maskFilters.clear();
        if (carry.containsKey("gear:Gas filter")) c.maskFilters.add("gas");
        if (carry.containsKey("gear:CO filter")) c.maskFilters.add("co");
        say(p, "the small gas mask is on: " + (c.maskFilters.isEmpty() ? "no filter, it keeps nothing out" : String.join(" + ", c.maskFilters) + " filter") + "; the eyes stay bare");
    }

    /**
     * Armour repair (05c, New Vegas's: combine with a spare of the same piece, up to 100 % whatever the skill; Fallout 3's efficiency
     * as fitted in 05 §5b): the better + 5 + 5 % of the other + 0.15 × the skill; the spare is used up. The skill: Crafting, or
     * Engineering for the powered and electric sets. Not in rounds [mine].
     */
    static void armourRepair(Player p, Character c, Map<String, Integer> carry, List<Ui.Btn> b, List<String> cannot) {
        for (Character.Worn w : new ArrayList<>(c.armour.values())) {
            if (!c.fits(w) && !pl.battle.inBattle(p))   // 05c §9.5: made for another body, it gives nothing until refitted
                b.add(btn("Refit the " + w.set() + " (" + w.slot().toLowerCase(Locale.ROOT) + ")", "made for a " + w.fitName() + " body, yours is "
                        + c.fit() + ": it gives nothing until refitted (Crafting " + Character.craftingFor(Data.ARMOUR.containsKey(w.set())
                        ? Data.ARMOUR.get(w.set()).tier() : 1) + ", an hour a kg)", x -> { say(x, c.refit(w.slot())); Items.writeArmour(x, c); actions(x); }));
            if (w.condition() >= 100 - 1e-6) continue;
            Data.Armour a = Data.ARMOUR.get(w.set());
            if (a == null) continue;
            String id = Items.armourId(w.set(), w.slot()), piece = w.set() + " (" + w.slot().toLowerCase(Locale.ROOT) + ")";
            boolean kit = carry.containsKey("gear:Armour repair kit");
            if (!carry.containsKey(id) && !kit) { cannot.add("Repair the " + piece + ": no spare of it to take apart, no armour repair kit"); continue; }
            if (pl.battle.inBattle(p)) { cannot.add("Repair: not in rounds"); continue; }
            double skill = c.skill(a.repair());
            boolean fixit = c.perks.stream().anyMatch(f -> f.name().equals("Mr. Fixit"));
            if (kit) {
                double gain = Combat.kitRepair(w.condition(), skill, fixit) - w.condition();
                b.add(btn("Repair the " + piece + " with a kit (" + carry.get("gear:Armour repair kit") + ")", Math.round(w.condition()) + " % +"
                        + Math.round(gain) + ": " + a.repair() + " " + Math.round(skill) + (fixit ? ", Mr. Fixit x2" : "") + " (05c §5; the kit is used up)", x -> {
                    if (!Items.take(x, "gear:Armour repair kit")) { say(x, "! no kit"); actions(x); return; }
                    double now = Combat.kitRepair(w.condition(), skill, fixit);
                    c.armour.put(w.slot(), w.at(now));
                    Items.writeArmour(x, c);
                    say(x, "the " + piece + ": " + Math.round(w.condition()) + " → " + Math.round(now) + " % (an armour repair kit, " + a.repair() + " " + Math.round(skill) + ")");
                    actions(x);
                }));
            }
            if (!carry.containsKey(id)) continue;
            b.add(btn("Repair the " + piece, Math.round(w.condition()) + " % with a spare: "
                    + a.repair() + " " + Math.round(skill) + (fixit ? ", Mr. Fixit x2" : "") + " (05c; the spare is used up)", x -> {
                Double spare = null;
                for (org.bukkit.inventory.ItemStack it : x.getInventory().getStorageContents()) {
                    Character.Worn s = Items.worn(it);
                    if (s != null && Items.id(it).equals(id)) { spare = s.condition(); break; }
                }
                if (spare == null || !Items.take(x, id)) { say(x, "! no spare"); actions(x); return; }
                double now = Combat.combineRepair(w.condition(), spare, skill, fixit);
                c.armour.put(w.slot(), w.at(now));
                Items.writeArmour(x, c);
                say(x, "the " + w.set() + " (" + w.slot().toLowerCase(Locale.ROOT) + "): " + Math.round(w.condition()) + " % and a spare at " + Math.round(spare)
                        + " % → " + Math.round(now) + " % (" + a.repair() + " " + Math.round(skill) + ")");
                actions(x);
            }));
        }
    }

    static void expel(Player p, Character c) {
        int most = (int) Math.max(100, Math.min(500, 100 * Math.floor(c.survival.getOrDefault(Rules.Stat.RADS, 0.0) / 100)));
        ask(p, "Expel rads", "More rads out = a worse roll (−10 a 100 above the first). No Lucky Escape.",
                List.of(Ui.In.numberRange("rads", Component.text("Rads"), 100, most).step(100f).initial(100f).width(300).build()),
                form("Expel", "roll the mutation", (v, x) -> {
                    String out = c.mutate(Math.round(v.getFloat("rads")), pl.rnd);
                    if (out.startsWith("d100")) c.lastMutation = c.clock;
                    say(x, out);
                    tellGm(x.getName() + " expelled rads: " + out);
                }), Menus::actions);
    }

    // ------------------------------------------------------------------ actions on another character (a player or an NPC)
    static void on(Player p, Who o) {
        if (o == null) return;
        Character me = pl.character(p), t = o.c();
        if (me.dead || me.inArrest()) { say(p, "! you cannot act"); return; }
        List<Ui.Btn> b = new ArrayList<>();
        List<Component> lines = new ArrayList<>();
        List<String> cannot = new ArrayList<>();
        Map<String, Integer> carry = Items.carried(p);
        double med = me.skill("Medicine");
        double[] am = t.arrestMinutes();
        lines.add(line(o.name() + (o.npc() ? " (NPC)" : "") + " · " + t.template + " · " + (t.dead ? "DEAD" : t.inArrest() ? "CLINICAL DEATH " + Hud.clock(am[0] + am[1])
                + (t.arrestCause.isEmpty() ? "" : " (" + t.arrestCause + ")") + (t.cprFrom >= 0 ? ", CPR running" : "")
                : "HP " + t.hp + "/" + t.hpMax() + (t.bleeding > 0 ? ", bleeding" : "") + (t.unconscious() ? ", UNCONSCIOUS" : "")), NamedTextColor.WHITE));
        for (String s : Hud.around(t)) lines.add(line(s, NamedTextColor.GOLD));
        if (t.inArrest()) {
            if (t.cprFrom < 0 && t.tpl().atDeath().isEmpty())
                b.add(btn("! Start CPR", "anyone can: the window ×2 (3 % a minute lost instead of 7)", x -> { t.cprFrom = t.now(); say(x, "CPR on " + o.name() + "."); on(x, whoOf(o.entity())); }));
            List<String> eq = new ArrayList<>(List.of("hands"));
            if (carry.containsKey("gear:Adrenaline")) eq.add("adrenaline");
            if (carry.containsKey("gear:Defibrillator")) eq.add("defibrillator");
            String block = t.arrestBlock();   // 03b §3c: "the cause dealt with first where it lasts"
            if (block != null) { cannot.add("Revive: " + block); eq.clear(); }
            for (String e : eq) {
                double ch = t.revivalChance(med, Rules.EQUIPMENT.get(e));
                b.add(btn("! Revive (" + e + ") " + pct(ch), "Medicine " + Math.round(med) + " / 2 × " + Rules.EQUIPMENT.get(e) + " × the time; one try a game minute"
                        + (e.equals("adrenaline") ? "; the adrenaline is used up" : ""), x -> revive(x, whoOf(o.entity()), e)));
            }
            if (!carry.containsKey("gear:Defibrillator")) cannot.add("Revive with a defibrillator: you carry none");
        }
        // 04b, 04c §7 (09e round 3: "the plugin doesn't simulate diseases - please add that."): the shown diseases and their actions
        if (!t.dead)
            for (Diseases.Case k : new ArrayList<>(t.diseases)) {
                Diseases.Disease d = Diseases.ALL.get(k.name);
                if (d == null || (k.incubation > 0 && !Works.gm(p))) continue;
                String st = k.incubation > 0 ? "incubating" : d.stages().get(Math.max(0, k.stage)).name();
                lines.add(line("Ill: " + d.name() + " (" + st + ")", NamedTextColor.RED));
                if (!Diseases.actions(d, k).isEmpty())
                    b.add(btn("Treat: " + d.name() + " (" + st + ")", d.real(), x -> treatDisease(x, whoOf(o.entity()), d.name())));
            }
        // 04d §1 (0.117.0): the poison, its depots and what holds them
        if (!t.dead && Poison.poisoned(t)) {
            for (String s : Poison.show(t).split("\n")) lines.add(line(s, NamedTextColor.DARK_GREEN));
            b.add(btn("Treat the poison", "keep the venom in the limb, take it out, help the body (04d)", x -> treatPoison(x, whoOf(o.entity()))));
        }
        // 04b §3c: the conditions with their own clocks
        String cond = Conditions.show(t);
        if (!t.dead && !cond.isEmpty()) lines.add(line("Condition: " + cond, NamedTextColor.RED));
        if (!t.dead && t.scabies)
            b.add(btn("Scabies: the ointment", "permethrin (pre-war) or sulphur ointment (made): the mites gone; treat everyone in contact at once",
                    x -> { String r = Conditions.ointment(t); say(x, o.name() + ": " + r); o.tell(r, NamedTextColor.YELLOW); on(x, whoOf(o.entity())); }));
        if (!t.dead && Works.gm(p))
            b.add(btn("GM: a disease", "infect " + o.name() + " with one (the incubation runs, the GM sees it), or vaccinate", x -> gmDisease(x, whoOf(o.entity()))));
        if (!t.dead && (t.bleeding > 0 || (t.hp < t.hpMax() && t.inSlot("treatment:dressing") == null)))
            b.add(btn(t.bleeding > 0 ? "First aid (stop the bleeding)" : "Dress the wounds", t.bleeding > 0 ? "the bleeding stops (Medicine)"
                    : "your Medicine 20 (a check): his healing rate +1 for the next 24 hours", x -> {
                String r = firstAid(x, me, t, o.name() + "'s");
                say(x, r);
                o.tell(p.getName() + ": " + r, NamedTextColor.GREEN);
                on(x, whoOf(o.entity()));
            }));
        if (!t.dead && !t.inArrest())
            for (Map.Entry<String, Integer> e : Meds.injectors(p).entrySet()) {   // 04i §6a
                String in = e.getKey();
                if (!in.equals("Hypo") && t.hp >= t.hpMax() && t.bleeding <= 0) continue;
                b.add(btn(in + " (" + e.getValue() + ")", injectorText(in), x -> {
                    String r = inject(x, t, o.name(), in);
                    say(x, r);
                    o.tell(p.getName() + ": " + r, NamedTextColor.GREEN);
                    on(x, whoOf(o.entity()));
                }));
            }
        if (!t.dead && !t.inArrest() && Meds.count(p, "Trauma pack") > 0)
            b.add(btn("Trauma pack", "10 minutes, your Medicine " + Math.round(med) + " (40 needed, a check): every bleeding stopped, Blood +50 %, Pain −40", x -> {
                String r = trauma(x, me, t);
                say(x, r);
                o.tell(p.getName() + ": " + r, NamedTextColor.GREEN);
                on(x, whoOf(o.entity()));
            }));
        if (!t.dead && t.brokenLegs > t.splints.size() && Meds.count(p, "Splint") > 0)   // 0.108.0
            b.add(btn("Splint the broken leg", "your Medicine " + Math.round(med) + " (20 needed, a check): it knits in 6 weeks", x -> {
                String r = splintWith(x, me, t);
                say(x, r);
                o.tell(p.getName() + ": " + r, NamedTextColor.GREEN);
                on(x, whoOf(o.entity()));
            }));
        if (!t.dead && !t.inArrest() && Meds.count(p, "Atropine") > 0)
            b.add(btn("Atropine", "the pesticide antidote: Poison −30; your Medicine " + Math.round(med) + " (50 needed: an overdose kills)", x -> {
                String r = Meds.atropine(me.skill("Medicine"), t);
                if (!r.startsWith("!")) Meds.take(x, "Atropine", 1);
                say(x, r);
                o.tell(p.getName() + ": " + r, NamedTextColor.GREEN);
                on(x, whoOf(o.entity()));
            }));
        if (!t.dead && !t.inArrest())
            for (String mn : List.of("Blood bag", "Blood Pack", "Antivenom", "Rehydration salts", "Home ORS", "Virex")) {   // 04i: given by the medic
                int n = Meds.count(p, mn);
                if (n <= 0) continue;
                b.add(btn("Give " + mn + " (" + n + ")", "a dose, on " + o.name(), x -> {
                    if (Meds.take(x, mn, 1) < 1) return;
                    String r = Meds.useSelf(t, mn, pl.rnd);
                    say(x, o.name() + ": " + r);
                    o.tell(p.getName() + ": " + r, NamedTextColor.GREEN);
                    on(x, whoOf(o.entity()));
                }));
            }
        if (!t.dead && !t.inArrest() && t != me)
            b.add(btn("Give your blood", "fresh whole blood (04i §6c): your Blood −25 %, his +25 %; the groups must match (the GM's)", x -> {
                String r = Meds.transfuse(me, t);
                say(x, r);
                o.tell(p.getName() + ": " + r, NamedTextColor.GREEN);
                on(x, whoOf(o.entity()));
            }));
        if (!t.dead && t.breathingStage())
            b.add(btn("Breathe for him", "the breathing stage (an opioid and a second depressant): you keep his breath going until the drugs wear off "
                    + "(or give naloxone)", x -> {
                t.breathHelpUntil = t.now() + BREATH_HELP_HOURS;
                say(x, "you breathe for " + o.name() + ": his Oxygen holds while you stay with him");
                o.tell(p.getName() + " breathes for you", NamedTextColor.GREEN);
                on(x, whoOf(o.entity()));
            }));
        if (!t.dead && t.hp >= t.hpMax() && t.bleeding <= 0) cannot.add("First aid: " + o.name() + " is not hurt");
        if (!t.dead)
            for (String k : carry.keySet()) {
                if (!k.startsWith("chem:")) continue;
                Data.Chem ch = Data.CHEMS.get(k.substring(5));
                if (ch == null) continue;
                b.add(btn("Give " + ch.name(), chemText(ch), x -> {
                    if (!Items.take(x, k)) return;
                    String r = Part3.dose(t, new String[]{"dose", ch.name()}, pl.rnd);
                    say(x, o.name() + ": " + r);
                    o.tell(p.getName() + " gives you " + ch.name() + ": " + r, NamedTextColor.YELLOW);
                    on(x, whoOf(o.entity()));
                }));
            }
        if (!t.dead && !t.inArrest()) {
            for (String k : carry.keySet())
                if (k.startsWith("implant:")) {
                    String name = k.substring(8);
                    b.add(btn("Operate: " + name, "fit it into " + o.name() + " (your Medicine " + Math.round(med) + ")" + (o.npc() ? "" : "; he must agree"), x -> operate(x, whoOf(o.entity()), name)));
                }
            if (carry.keySet().stream().noneMatch(k -> k.startsWith("implant:"))) cannot.add("Operate: you carry no implant");
            for (String k : carry.keySet())   // 03e §4, 09e §6: a donor's organ from the field (Loot) as a T II graft
                if (k.startsWith("body:Donor ")) {
                    String what = k.substring("body:Donor ".length());
                    String organ = what.replaceAll("\\|.*", ""), donorBlood = what.contains("|") ? what.substring(what.indexOf('|') + 1) : "";
                    if (Character.graftOf(organ) == null) continue;
                    b.add(btn("Transplant: the donor " + organ + (donorBlood.isEmpty() ? "" : " (" + donorBlood + ")"),
                            "a T II graft into " + o.name() + " (your Medicine " + Math.round(med) + "); it keeps only its hours: the GM checks"
                                    + (o.npc() ? "" : "; he must agree"), x -> transplant(x, whoOf(o.entity()), k, organ, donorBlood)));
                }
            for (String k : carry.keySet())
                if (k.startsWith("therapy:") || k.startsWith("geneset:")) {
                    String what = k.substring(k.indexOf(':') + 1);
                    b.add(btn("Gene therapy: " + what, "give it to " + o.name() + " (your Biology " + Math.round(me.skill("Biology")) + ", 03d §6)"
                            + (o.npc() ? "" : "; he must agree"), x -> therapyGive(x, whoOf(o.entity()), k)));
                }
        }
        for (Character.Effect e : t.implants())
            b.add(btn("Take out: " + e.name().replaceAll(" · Tier.*", ""), "costs it " + Math.round(Rules.salvageLoss(med)) + " % condition at your Medicine"
                    + (t.dead || o.npc() ? "" : "; he must agree"), x -> removeAsk(x, whoOf(o.entity()), e)));
        if (!t.dead && !t.recipes().isEmpty())
            b.add(btn("Take a gene sample", sampleText(t), x -> {
                String r = sample(whoOf(o.entity()));
                say(x, r);
                o.tell(p.getName() + " took your gene sample: " + r, NamedTextColor.LIGHT_PURPLE);
                tellGm(p.getName() + " → " + o.name() + ": " + r);
                on(x, whoOf(o.entity()));
            }));
        b.add(btn("Their sheet", "sheets are open to all", x -> SheetView.open(x, t, SheetView.Page.OVERVIEW)));
        if (p.hasPermission("falloutak.gm")) b.add(btn("GM: " + o.name(), "the GM's tools on him", x -> gmOn(x, whoOf(o.entity()))));
        if (!cannot.isEmpty()) {
            lines.add(line("Not now:", NamedTextColor.DARK_GRAY));
            for (String s : cannot) lines.add(line(" " + s, NamedTextColor.DARK_GRAY));
        }
        menu(p, "Actions on " + o.name(), lines, b, Menus::actions);
    }

    static void revive(Player p, Who o, String eq) {
        if (o == null) return;
        Character me = pl.character(p), t = o.c();
        if (!t.inArrest()) { say(p, "! " + o.name() + " is not in clinical death"); return; }
        String block = t.arrestBlock();
        if (block != null) { say(p, "! the heart would stop again: " + block); return; }
        if (t.now() - t.lastReviveTry < 1.0 / 60 - 1e-9) { say(p, "! one try a game minute: the GM passes time (or a fight's rounds)"); return; }
        if (eq.equals("adrenaline") && !Items.take(p, "gear:Adrenaline")) return;
        t.lastReviveTry = t.now();
        double ch = t.revivalChance(me.skill("Medicine"), Rules.EQUIPMENT.get(eq));
        int d = 1 + pl.rnd.nextInt(100);
        if (t.has("Medical Marvel") || d > ch) {
            String r = "Revival of " + o.name() + " (" + eq + "): " + pct(ch) + ", rolled " + d + ": failed" + (t.has("Medical Marvel") ? " (Medical Marvel: nobody can)" : "") + ".";
            say(p, "! " + r);
            o.tell(r, NamedTextColor.RED);
            tellGm(p.getName() + ": " + r);
            return;
        }
        double[] m = t.arrestMinutes();
        int price = t.tpl().atDeath().equals("torpor") ? 0 : Rules.brainPrice(m[0] + m[1] / 2, t.deathWindow());
        t.revived();
        if (price > 0) {
            int[] mods = new int[7];
            mods[Rules.I] = -price;
            t.add(new Character.Effect("Brain damage (clinical death)", "trait", "", true, mods, new LinkedHashMap<>(), 0, null, 100));
        }
        String r = "Revival of " + o.name() + " (" + eq + "): " + pct(ch) + ", rolled " + d + ": the heart beats again, HP " + t.hp
                + " (still dying: a doctor, or 8 hours of rest if nothing worsens)" + (price > 0 ? "; Intelligence −" + price : "") + ".";
        say(p, r);
        o.tell(r, NamedTextColor.GREEN);
        tellGm(p.getName() + ": " + r);
    }

    /**
     * First aid (no roll): it stops the bleeding; on a wound that no longer bleeds, a dressing (04c §4b, sutures: the healing rate +1)
     * when the medic has Medicine 20 or more [the 20 mine].
     */
    static String firstAid(Character me, Character t, String whose) { return firstAid(null, me, t, whose); }

    /** The same by a player medic: the dressing takes one of his dressings (04i: the medicines used up); stopping the bleeding is free. */
    static String firstAid(Player medic, Character me, Character t, String whose) {
        if (t.bleeding > 0) { t.bleeding = 0; return "first aid: " + whose + " bleeding stops"; }
        if (me.skill("Medicine") < 20) return "! dressing a wound needs Medicine 20 (a check, no roll): " + Math.round(me.skill("Medicine"));
        String used = medic == null ? "a dressing" : Meds.dressing(medic);
        if (used == null) return "! a dressing needs hemostatic gauze, a bandage, wound powder or a rag dressing";
        t.add(new Character.Effect("Wounds dressed", "treatment", "treatment:dressing", false, new int[7],
                new LinkedHashMap<>(Map.of("healing_rate", 1.0)), 24, null, 100));
        return whose + " wounds dressed (" + used.toLowerCase(java.util.Locale.ROOT) + "): the healing rate +1 for the next 24 hours (healing comes with 8 hours of rest)";
    }

    static String stimpak(Player user, Character t, String who) { return inject(user, t, who, "Stimpak"); }

    /** A medic stays with a man in the breathing stage this long (the opioids' hours) [mine]. */
    static final double BREATH_HELP_HOURS = 6;

    /** An injector from the user's pack on {@code t} (04i §6a). */
    static String inject(Player user, Character t, String who, String name) {
        if (t.dead || t.inArrest()) return "! " + who + ": " + (t.dead ? "dead" : "clinical death: revive first");
        if (!Meds.takeInjector(user, name)) return "! no " + name;
        return who + ": " + Meds.inject(t, name, pl.rnd);
    }

    /** A Splint from the user's pack on {@code t}'s broken leg, by the medic {@code me}'s Medicine; kept if it cannot. */
    static String splintWith(Player user, Character me, Character t) {
        if (Meds.count(user, "Splint") < 1) return "! no Splint";
        String r = Meds.splint(me.skill("Medicine"), t);
        if (!r.startsWith("!")) Meds.take(user, "Splint", 1);
        return r;
    }

    /** The Trauma pack from the user's pack, by the medic {@code me}'s Medicine; kept if his check fails. */
    static String trauma(Player user, Character me, Character t) {
        if (Meds.count(user, "Trauma pack") < 1) return "! no Trauma pack";
        String r = Meds.traumaPack(me.skill("Medicine"), t);
        if (!r.startsWith("!")) Meds.take(user, "Trauma pack", 1);
        return r;
    }

    static String injectorText(String name) {
        return switch (name) {
            case "Stimpak" -> "every bleeding stopped, +10 to +20 HP, the healing rate +1 for 3 days (04i §6a)";
            case "Super Stimpak" -> "bleeding stopped, Blood +25 %, +25 HP and 25 HELD for an hour, the healing rate +2; a heart check";
            default -> "a dying man stands up: +40 HP HELD for an hour, bleeding stopped, Pain −50; then he is where he was";
        };
    }

    /** Asks the patient (an NPC agrees by itself: the GM plays it and is told). */
    static void consent(Who o, String title, String text, Runnable yes) {
        if (o.npc()) { yes.run(); return; }
        ask(o.player(), title, text, List.of(), form("Agree", "go ahead", (v, y) -> yes.run()), z -> say(z, "You refuse."));
    }

    static void operate(Player p, Who o, String implant) {
        if (o == null) return;
        Data.Implant im = Data.IMPLANTS.get(implant.replaceAll("\\|.*", ""));
        if (im == null) return;
        int tier = implant.contains("|") ? Integer.parseInt(implant.substring(implant.indexOf('|') + 1)) : 4;
        Map<String, Integer> carry = Items.carried(p);
        List<String> sup = new ArrayList<>(List.of("none"));
        for (String s : Items.SUPPLIES) if (carry.containsKey("supplies:" + s)) sup.add(s);
        Character me = pl.character(p);
        Consumer<Player> back = x -> on(x, whoOf(o.entity()));
        pick(p, "Operate: the room", "Be honest: the GM sees it.", new ArrayList<>(Rules.ROOMS.keySet()), r -> r, r -> "× " + Rules.ROOMS.get(r),
                (x, room) -> pick(x, "Operate: the supplies", "Used up by the operation.", sup, s -> s, s -> "× " + (s.equals("none") ? 0.3 : Rules.SUPPLIES.get(s)),
                        (y, s) -> {
                            if (im.skillChoice() == 0) { operateConfirm(y, o, implant, im, tier, me, room, s, null); return; }
                            List<String> sk = new ArrayList<>();
                            for (Rules.Skill k : Rules.SKILLS) sk.add(k.name());
                            pick(y, "Operate: the chip's skill", "The memory chip's skill.", sk, k -> k, k -> "now " + Math.round(o.c().skill(k)) + " %",
                                    (z, k) -> operateConfirm(z, o, implant, im, tier, me, room, s, k), back);
                        }, back), back);
    }

    /** A disease's actions (04c §7): pick one; antibiotics take a carried Medicine III or IV and the family the medic picks. */
    /** Marek 11-24: eat a portion or drink a litre of what he carries; each item shows its harm (04 §3.9, 04b §5). */
    static void eat(Player p, Character c, boolean water) {
        List<String> ids = new ArrayList<>();
        Map<String, Integer> carry = Items.carried(p);
        for (String id : carry.keySet()) {
            String[] th = Items.thing(id);
            if (th != null && th[0].equals(water ? "Water" : "Food")) ids.add(id);
        }
        ids.sort(java.util.Comparator.comparingInt((String id) -> -Integer.parseInt(Items.thing(id)[1])));
        pick(p, water ? "Drink a litre" : "Eat a portion", water ? "Tier I: rads and the germs; II: one of them; III clean."
                        : "Tier I: rads and a roll; II: one harm (rads, or a stale portion's roll); III clean.", ids,
                id -> { String[] th = Items.thing(id); return (th[3].isEmpty() ? th[0] : th[3]) + " (" + Rules.roman(Integer.parseInt(th[1]))
                        + (th[2].equals("1") ? "" : " R" + th[2]) + ", " + carry.get(id) + ")"; },
                id -> { String[] th = Items.thing(id); return Meals.harmText(th[0], Integer.parseInt(th[1]), Integer.parseInt(th[2]), th[3]); },
                (x, id) -> {
                    if (!Items.take(x, id)) { say(x, "! it is gone"); actions(x); return; }
                    String[] th = Items.thing(id);
                    int tier = Integer.parseInt(th[1]), rarity = Integer.parseInt(th[2]);
                    String r = water ? Meals.drink(c, tier, th[3], pl.rnd) : Meals.eat(c, tier, rarity, th[3], pl.rnd);
                    say(x, r);
                    if (r.contains("CAUGHT")) tellGm(c.name + ": " + r);
                    actions(x);
                }, Menus::actions);
    }

    /** What a poison act takes from the medic's pack (04d §1): "" nothing; "a|b" either; "a+b" both. */
    static String poisonNeeds(String act) {
        return switch (act) {
            case "bandage" -> "Bandage|Rag dressing";
            case "tq" -> "Tourniquet";
            case "fluids" -> "Saline";
            case "antivenom-im" -> "Antivenom";
            case "antivenom-iv" -> "Antivenom+Saline";
            case "varespladib" -> "Varespladib";
            default -> "";
        };
    }

    static boolean hasFor(Player p, String need) {
        if (need.isEmpty()) return true;
        if (need.contains("+")) { for (String n : need.split("\\+")) if (Meds.count(p, n) < 1) return false; return true; }
        for (String n : need.split("\\|")) if (Meds.count(p, n) > 0) return true;
        return false;
    }

    static String takeFor(Player p, String need) {
        if (need.isEmpty()) return "";
        if (need.contains("+")) { for (String n : need.split("\\+")) Meds.take(p, n, 1); return need.replace("+", ", "); }
        for (String n : need.split("\\|")) if (Meds.take(p, n, 1) == 1) return n;
        return "";
    }

    /** The poison's acts on {@code o} by the player (04d §1, 0.117.0): his Survival and Medicine, the items each takes; plasma exchange
     *  is the GM's (a pre-war hospital's machine: nothing in the plugin marks one). */
    static void treatPoison(Player p, Who o) {
        if (o == null) return;
        Character me = pl.character(p), t = o.c();
        List<String> acts = new ArrayList<>(Poison.acts(t));
        if (!Works.gm(p)) acts.remove("plasma");
        acts.removeIf(a -> !hasFor(p, poisonNeeds(a.split(" ")[0])));
        if (acts.isEmpty()) { say(p, "! nothing you can do for the poison with what you carry"); on(p, o); return; }
        pick(p, "The poison: " + o.name(), Poison.show(t), acts, a -> a.replace('_', ' '), a -> Poison.actText(a.split(" ")[0])
                + (poisonNeeds(a.split(" ")[0]).isEmpty() ? "" : "; takes " + poisonNeeds(a.split(" ")[0]).replace("|", " or ").replace("+", " and ")), (x, a) -> {
            String[] w = a.split(" ");
            String act = w[0], limb = w.length > 1 ? w[1] : null;
            String anaesthesia = null;
            boolean an = false;
            if (act.equals("amputate")) {
                if (Meds.take(x, "Ether", 1) == 1) { an = true; anaesthesia = "ether"; }
                else if (Meds.take(x, "Chloroform", 1) == 1) { an = true; anaesthesia = "chloroform"; }
                else if (Meds.takeChem(x, "Ketamine")) { an = true; anaesthesia = "ketamine"; }
            }
            String r = Poison.treat(t, act, limb, me.skill("Survival"), me.skill("Medicine"), an, pl.rnd);
            if (!r.startsWith("!")) {
                String used = takeFor(x, poisonNeeds(act));
                if (act.equals("breathing") && t.poisonBreathing && Meds.take(x, "Oxygen cylinder", 1) == 1) {
                    t.oxygenUntil = t.now() + Meds.OXYGEN_HOURS;
                    used = "an oxygen cylinder (" + Math.round(Meds.OXYGEN_HOURS) + " hours)";
                }
                if (anaesthesia != null) used = (used.isEmpty() ? "" : used + ", ") + "anaesthesia (" + anaesthesia + ")";
                if ("chloroform".equals(anaesthesia) && me.skill("Medicine") < Meds.CHLOROFORM_NEED) {
                    t.arrestAt = t.now();
                    t.arrestCause = "chloroform";
                    r += "; THE CHLOROFORM STOPS THE HEART (Medicine " + Math.round(me.skill("Medicine")) + " of " + Math.round(Meds.CHLOROFORM_NEED) + ")";
                }
                if (!used.isEmpty()) r += " (used: " + used + ")";
            }
            say(x, o.name() + ": " + r);
            if (o.player() != x) o.tell(x.getName() + ": " + r, NamedTextColor.YELLOW);
            on(x, whoOf(o.entity()));
        }, x -> on(x, whoOf(o.entity())));
    }

    static void treatDisease(Player p, Who o, String name) {
        if (o == null) return;
        Diseases.Disease d = Diseases.ALL.get(name);
        Diseases.Case k = d == null ? null : Diseases.active(o.c(), name);
        if (k == null) { on(p, o); return; }
        Consumer<Player> back = x -> on(x, whoOf(o.entity()));
        pick(p, "Treat " + o.name() + ": " + d.name(), d.caught(), Diseases.actions(d, k), a -> a, a -> Diseases.actText(d, a), (x, act) -> {
            if (!act.equals("antibiotics")) {
                String used = Meds.forAct(x, d, k, act, o.c());   // 04i: the doses the action takes
                if (used != null && used.startsWith("!")) { say(x, used); on(x, whoOf(o.entity())); return; }
                double pain0 = o.c().survival.getOrDefault(Rules.Stat.PAIN, 0.0);
                String r = Diseases.treat(o.c(), name, act, "", 1, pl.character(x));
                if (used != null && used.startsWith("anaesthesia")) {
                    o.c().survival.put(Rules.Stat.PAIN, pain0);
                    if (used.contains("chloroform") && pl.character(x).skill("Medicine") < Meds.CHLOROFORM_NEED) {
                        o.c().arrestAt = o.c().now();
                        o.c().arrestCause = "chloroform";
                        r += "; THE CHLOROFORM STOPS THE HEART (Medicine " + Math.round(pl.character(x).skill("Medicine")) + " of " + Math.round(Meds.CHLOROFORM_NEED) + ")";
                    }
                }
                if (used != null && used.startsWith("an oxygen cylinder")) {
                    Diseases.Case kk = Diseases.active(o.c(), name);
                    if (kk != null && kk.acts.containsKey("support")) kk.acts.put("support", o.c().now() + Meds.OXYGEN_HOURS);
                }
                if (used != null) r += " (used: " + used + ")";
                say(x, o.name() + ": " + r);
                o.tell(x.getName() + ": " + r, NamedTextColor.YELLOW);
                on(x, whoOf(o.entity()));
                return;
            }
            Map<String, Integer> abx = Meds.antibiotics(x);   // 04i: a course = one antibiotic, a dose a day
            if (abx.isEmpty()) { say(x, "! you carry no antibiotics (a course: " + Meds.courseDoses(d) + " doses of one)"); on(x, whoOf(o.entity())); return; }
            int need = Meds.courseDoses(d);
            pick(x, "Which antibiotic", "Your Medicine (a check) or your own knowledge picks it (04c §4c): the wrong one does nothing. The course takes "
                    + need + " doses; fewer = a short course.", new ArrayList<>(abx.keySet()), n -> n + " (" + abx.get(n) + ")",
                    n -> Meds.ABX.get(n).family().replace("any", "phages: matched to the germ") + ", " + Math.round(100 * Meds.strength(n, Travel.years())) + " % now",
                    (y, n) -> {
                        String fam = Meds.family(n, d);
                        double str = Meds.strength(n, Travel.years());
                        if (!d.families().contains(fam)) { say(y, "! " + n + " does nothing for " + d.name() + " (it takes " + String.join(", ", d.families()) + ")"); return; }
                        if (str <= 0) { say(y, "! these " + n + " no longer work (over 10 years past day D)"); return; }
                        int took = Meds.take(y, n, need);
                        String r = Diseases.treat(o.c(), name, "antibiotics", fam, str);
                        Diseases.Case kc = Diseases.active(o.c(), name);
                        if (kc != null && took < need) kc.abxDays = took;
                        r += " (" + took + " of the " + need + " doses" + (took < need ? ": a short course" : "") + ")";
                        say(y, o.name() + ": " + r);
                        o.tell(y.getName() + ": " + r, NamedTextColor.YELLOW);
                        on(y, whoOf(o.entity()));
                    }, back);
        }, back);
    }

    /** The GM gives a disease or a vaccine (04b §2b). */
    static void gmDisease(Player p, Who o) {
        if (o == null) return;
        Consumer<Player> back = x -> on(x, whoOf(o.entity()));
        pick(p, "GM: a disease for " + o.name(), Diseases.show(o.c()), List.of("infect", "vaccinate", "cure"), a -> a, a -> "", (x, what) ->
                pick(x, "GM: " + what, "", new ArrayList<>(Diseases.ALL.keySet()), n -> n, n -> Diseases.ALL.get(n).caught(), (y, n) -> {
                    String r = Diseases.command(o.c(), new String[]{"disease", what.equals("infect") ? "catch" : what.equals("vaccinate") ? "vaccine" : "cure",
                            n.replace(' ', '_')}, pl.rnd);
                    say(y, o.name() + ": " + r);
                    if (!what.equals("infect")) o.tell(r, NamedTextColor.YELLOW);
                    on(y, whoOf(o.entity()));
                }, back), back);
    }

    /** A donor's organ into a patient (03e §4, 09e §6): the room, the supplies, an arm's side, then his consent. */
    static void transplant(Player p, Who o, String item, String organ, String donorBlood) {
        if (o == null) return;
        Character.Graft g = Character.graftOf(organ);
        if (g == null) return;
        Map<String, Integer> carry = Items.carried(p);
        List<String> sup = new ArrayList<>(List.of("none"));
        for (String s : Items.SUPPLIES) if (carry.containsKey("supplies:" + s)) sup.add(s);
        Character me = pl.character(p);
        Consumer<Player> back = x -> on(x, whoOf(o.entity()));
        pick(p, "Transplant: the room", "Be honest: the GM sees it.", new ArrayList<>(Rules.ROOMS.keySet()), r -> r, r -> "× " + Rules.ROOMS.get(r),
                (x, room) -> pick(x, "Transplant: the supplies", "Used up by the operation.", sup, s -> s, s -> "× " + (s.equals("none") ? 0.3 : Rules.SUPPLIES.get(s)),
                        (y, s) -> {
                            if (!g.place().equals("limb")) { transplantConfirm(y, o, item, g, donorBlood, me, room, s, null); return; }
                            pick(y, "Transplant: which arm", "The missing arm it replaces.", List.of("left_arm", "right_arm"), k -> k.replace('_', ' '), k -> "",
                                    (z, k) -> transplantConfirm(z, o, item, g, donorBlood, me, room, s, k), back);
                        }, back), back);
    }

    static void transplantConfirm(Player p, Who o, String item, Character.Graft g, String donorBlood, Character me, String room, String s, String limb) {
        double chance = Rules.surgeryChance(me.skill("Medicine"), Rules.ROOMS.get(room), s.equals("none") ? 0.3 : Rules.SUPPLIES.get(s), g.op());
        ask(p, "Transplant into " + o.name(), "The donor " + g.organ() + (donorBlood.isEmpty() ? "" : ", blood group " + donorBlood) + (limb == null ? "" : ", the " + limb.replace('_', ' '))
                        + ", in a " + room + " room with " + s + " supplies: " + pct(chance) + " (your Medicine " + Math.round(me.skill("Medicine")) + " × room × supplies × "
                        + g.op() + "). " + (g.drugs() ? "Then a month's anti-rejection course at a time." : "The corneas need no drugs."),
                List.of(),
                form(o.npc() ? "Operate" : "Ask him", "the transplant", (v, x) -> consent(o, x.getName() + " wants to transplant", "The donor's " + g.organ() + " into you: "
                        + pct(chance) + " (" + room + ", " + s + "). " + (g.drugs() ? "Then the anti-rejection drugs a month at a time, Immunity −30 %." : ""), () -> {
                    if (!Items.has(x, item)) { say(x, "! you no longer have it"); return; }
                    if (!s.equals("none") && !Items.take(x, "supplies:" + s)) { say(x, "! the supplies are gone"); return; }
                    List<String> args = new ArrayList<>(List.of("transplant", g.organ(), donorBlood, String.valueOf(me.skill("Medicine")), room, s));
                    if (limb != null) args.add(limb);
                    String r = Part3.transplant(o.c(), args.toArray(new String[0]), pl.rnd);
                    if (r.contains("SUCCESS") || r.contains("CATASTROPHIC")) Items.take(x, item);
                    say(x, o.name() + ": " + r);
                    o.tell(r, NamedTextColor.YELLOW);
                    tellGm(x.getName() + " transplanted into " + o.name() + ": " + r.split("\n")[0]);
                })), x -> on(x, whoOf(o.entity())));
    }

    static void operateConfirm(Player p, Who o, String implant, Data.Implant im, int tier, Character me, String room, String s, String skill) {
        ask(p, "Operate on " + o.name(), im.name() + " (" + Part3.numbers(im) + ") in a " + room + " room with " + s + " supplies" + (skill == null ? "" : ", the chip's skill " + skill)
                        + ". Chance = min(98 %, your Medicine " + Math.round(me.skill("Medicine")) + " × room × supplies × " + Rules.operationFactor(im.slot()) + " for the " + im.slot() + ").",
                List.of(),
                form(o.npc() ? "Operate" : "Ask him", "the operation", (v, x) -> {
                    double chance = Rules.surgeryChance(me.skill("Medicine"), Rules.ROOMS.get(room), s.equals("none") ? 0.3 : Rules.SUPPLIES.get(s), Rules.operationFactor(im.slot()));
                    consent(o, x.getName() + " wants to operate", "To fit " + im.name() + " into your " + im.slot() + ": " + pct(chance) + " (" + room + ", " + s
                            + "). A failure: minor 70 %, catastrophic 25 %, ridiculous 5 %.", () -> {
                        if (!Items.has(x, "implant:" + implant)) { say(x, "! you no longer have it"); return; }
                        if (!s.equals("none") && !Items.take(x, "supplies:" + s)) { say(x, "! the supplies are gone"); return; }
                        String[] args = {"surgery", im.name(), String.valueOf(me.skill("Medicine")), room, s,
                                tier == 4 ? "IV" : tier == 3 ? "III" : "II", skill == null ? "" : skill};
                        String r = Part3.surgery(o.c(), skill == null ? Arrays.copyOf(args, 6) : args, pl.rnd);
                        if (r.contains("SUCCESS") || r.contains("CATASTROPHIC")) Items.take(x, "implant:" + implant);
                        say(x, o.name() + ": " + r);
                        o.tell(r, NamedTextColor.YELLOW);
                        tellGm(x.getName() + " operated on " + o.name() + ": " + r.split("\n")[0]);
                    });
                    if (!o.npc()) say(x, "Asked " + o.name() + ".");
                }), x -> on(x, whoOf(o.entity())));
    }

    static void removeAsk(Player p, Who o, Character.Effect e) {
        if (o == null) return;
        Character t = o.c();
        Runnable take = () -> {
            double med = pl.character(p).skill("Medicine");
            Character.Effect gone = t.removeImplant(e.slot().substring("implant:".length()));
            if (gone == null) return;
            String base = gone.name().replaceAll(" \\(.*", "").replaceAll(" · Tier.*", "");
            int tier = Rules.tierOf(gone.name());
            Items.give(p, "implant:" + base + (tier == 4 ? "" : "|" + tier), 1);
            String r = gone.name() + " taken out of " + o.name() + ": −" + Math.round(Rules.salvageLoss(med)) + " % condition, "
                    + Math.round(Math.max(0, gone.condition() - Rules.salvageLoss(med))) + " % left" + (t.dead ? "" : "; an operation: the GM may ask for the surgery's roll");
            say(p, r);
            o.tell(r, NamedTextColor.YELLOW);
            tellGm(p.getName() + ": " + r);
        };
        if (t.dead) { take.run(); return; }
        consent(o, p.getName() + " wants an implant", "To take out your " + e.name() + ".", take);
        if (!o.npc()) say(p, "Asked " + o.name() + ".");
    }

    // ------------------------------------------------------------------ the base's store (05c §7): its chests, set by the GM
    /**
     * The GM sets the store's chests (Marek 11-19: *"one chest per type, rows by tiers and rarities. And physical Mess Chest that players
     * can return items into."*), sees what the store holds, and unlocks items beyond the start's (Tier I-II at rarity 1).
     */
    static void gmBase(Player g) {
        Base base = pl.base;
        List<Ui.Btn> b = new ArrayList<>();
        List<Component> lines = new ArrayList<>();
        lines.add(line("The store's chests (look at a chest, then click what it becomes):", NamedTextColor.WHITE));
        int none = 0;
        for (String type : Base.types()) {
            List<Base.Spot> s = base.chests.get(type);
            if (s == null) { none++; continue; }
            lines.add(line(" " + type + ": " + s.get(0).text(), NamedTextColor.GRAY));
        }
        if (none > 0) lines.add(line(" " + none + " of the " + Base.types().size() + " types have no chest yet (part 7's catalogue).", NamedTextColor.DARK_GRAY));
        int cats = 0;
        for (String c : Base.categories()) {
            List<Base.Spot> s = base.chests.get(c);
            if (s == null) continue;
            cats++;
            lines.add(line(" " + c + " (assembled): " + s.get(0).text(), NamedTextColor.GRAY));
        }
        if (cats == 0) lines.add(line(" No item category has a chest yet: its items assemble from the parts on a click (round 5).", NamedTextColor.DARK_GRAY));
        List<Base.Spot> mess = base.chests.getOrDefault(Base.MESS, List.of());
        lines.add(line(" " + Base.MESS + ": " + (mess.isEmpty() ? "none yet" : String.join(", ", mess.stream().map(Base.Spot::text).toList())),
                mess.isEmpty() ? NamedTextColor.DARK_GRAY : NamedTextColor.GRAY));
        int n = 0, fresh = 0;
        for (Base.Pool q : base.pools.values()) { n += q.count; fresh += q.fresh; }
        lines.add(line("In the store: " + n + (n == 1 ? " item" : " items") + (n > 0 ? " (" + fresh + " undamaged)" : "")
                + "; unlocked beyond the start: " + base.unlocked.size() + ".", NamedTextColor.GRAY));
        b.add(btn("Mess Chest: the chest you look at", "players put items in; on closing, the store's items go to their rows (and are unlocked)",
                x -> say(x, setChest(x.getTargetBlockExact(6), Base.MESS))));
        b.add(btn("A type's chest: the chest you look at", "pick the type (" + Base.types().size() + "): it opens as that type's store, a row for each tier and rarity",
                x -> pick(x, "Which type's chest?", "Look at the chest while you pick.", Base.types(), t -> t,
                        t -> "opens as the store's " + t.toLowerCase(Locale.ROOT) + ": a row for each tier and rarity",
                        (y, t) -> { say(y, setChest(y.getTargetBlockExact(6), t)); gmBase(y); }, Menus::gmBase)));
        b.add(btn("An item category's chest: the chest you look at", "pick the category (" + Base.categories().size()
                        + "): its unlocked items assemble from the store's parts on a click (left: the worn parts first, right: the undamaged)",
                x -> pick(x, "Which category's chest?", "Look at the chest while you pick. Its items are assembled from the parts (round 5).", Base.categories(), t -> t,
                        t -> "opens as the " + t.toLowerCase(Locale.ROOT) + ": the unlocked items by tier and rarity, a click assembles one",
                        (y, t) -> { say(y, setChest(y.getTargetBlockExact(6), t)); gmBase(y); }, Menus::gmBase)));
        b.add(btn("No store chest: the chest you look at", "a plain chest again (what the store holds stays)", x -> say(x, setChest(x.getTargetBlockExact(6), null))));
        b.add(btn("Unlocked items", "beyond Tier I-II at rarity 1 (made from the start) and what was brought in", Menus::unlockMenu));
        menu(g, "Base", lines, b, Menus::gm);
    }

    /** A chest block becomes a store chest (a type, the Mess Chest) or, with null, a plain chest again. Returns what happened. */
    static String setChest(org.bukkit.block.Block block, String what) {
        List<Base.Spot> s = Storage.spots(block);
        if (s.isEmpty()) return "! look at a chest first (within 6 blocks)";
        if (what == null) { pl.base.forget(s); return "That chest is a plain chest again."; }
        pl.base.setChest(what, s);
        return "That chest is now the " + (what.equals(Base.MESS) ? Base.MESS : what.toLowerCase(Locale.ROOT) + " chest") + " (" + s.get(0).text() + ").";
    }

    /** The GM unlocks a set for the base, or locks it again (pieces brought in stay unlocked). */
    static void unlockMenu(Player g) {
        List<String> l = new ArrayList<>();
        for (Data.Armour a : Data.ARMOUR.values()) if (!Character.knownAtStart(a)) l.add(a.name());
        for (Production.Design d : Production.DESIGNS.values()) if (!d.armour() && !Base.madeAtStart(d.tier, d.rarity)) l.add(d.key());   // round 4
        pick(g, "Unlocked items", "Unlocked from the start: every item of Tier I-II at rarity 1 (the base can make it). An item brought in through "
                        + "the Mess Chest unlocks itself. A click unlocks a whole set or an item for the assembly, or locks it again.", l,
                n -> n.contains("|") ? Production.DESIGNS.get(n).label() + " · " + Production.DESIGNS.get(n).category + unlockedText(n)
                        : n + " (" + Rules.roman(Data.ARMOUR.get(n).tier()) + "/" + Data.ARMOUR.get(n).rarity() + ")" + unlockedText(n),
                n -> n.contains("|") ? "assembled from " + Production.partsText(Production.DESIGNS.get(n)) : Items.pieceText(n, Data.ARMOUR.get(n).pieces().get(0), 100),
                (x, n) -> { pl.base.toggleSet(n); unlockMenu(x); }, Menus::gmBase);
    }

    static String unlockedText(String set) {
        if (pl.base.unlocked.contains(set)) return " · unlocked";
        if (Data.ARMOUR.get(set) == null) return "";   // an assembled item's key (round 4)
        List<String> in = new ArrayList<>();
        for (String s : Data.ARMOUR.get(set).pieces()) if (pl.base.unlocked.contains(Base.pieceId(set, s))) in.add(s.toLowerCase(Locale.ROOT));
        return in.isEmpty() ? "" : " · brought in: " + String.join(", ", in);
    }

    // ------------------------------------------------------------------ the GM's menu
    static void gm(Player g) {
        if (!g.hasPermission("falloutak.gm")) { say(g, "! the GM's menu needs falloutak.gm (an operator has it: /op <name> in the server console)"); return; }
        List<Ui.Btn> b = new ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers()) {
            Character c = pl.character(o);
            b.add(btn(o.getName() + " · " + c.name, c.template + ", HP " + c.hp + "/" + c.hpMax(), x -> gmOn(x, of(o))));
        }
        for (org.bukkit.entity.Entity e : g.getWorld().getEntities()) {
            Who w = whoOf(e);
            if (w == null || w.player() != null) continue;
            b.add(btn(w.name() + " (NPC)", w.c().template + ", HP " + w.c().hp + "/" + w.c().hpMax(), x -> gmOn(x, whoOf(e))));
        }
        b.add(btn("Spawn a figurant", "an NPC to test on: a statblock (/npc) standing here", Menus::spawnForm));
        b.add(btn("Fight here", "the players and the figurants within " + (int) Npcs.RADIUS + " blocks, each figurant on its side (its menu: Side)",
                x -> pick(x, "Fight here", "Who acts first?", List.of("party", "them"), k -> k.equals("party") ? "The party first" : "They attack first",
                        k -> k.equals("party") ? "an ambush or the party starts it" : "they open fire", (y, k) -> say(y, Npcs.fightHere(pl, y, k.equals("them"))), Menus::gm)));
        b.add(btn("All characters", "for everyone at once: pass time, heal, stop bleeding, give an item", Menus::gmAll));
        b.add(btn("Pass time (everyone)", "the game clock for every character: survival, chems, bleeding, healing, clinical death", x -> passTime(x, null)));
        b.add(btn("Gas zones" + (pl.zones.list.isEmpty() ? "" : " (" + pl.zones.list.size() + ")"), "a gas over an area: a factory hall, an anomaly, a cloud (03b §3c)", Menus::zonesMenu));
        b.add(btn("Anomalies and the Black Rain", "place anomalies and fields, artifacts, nuts and detectors; the surge (09c, round 9)", Menus::anomalyMenu));
        b.add(btn("Time of day (everyone)", "set the hour and minute on everyone's clock (the day stays)", x -> timeOfDay(x, null)));
        b.add(btn("Base", "the store's chests (one a type, the Mess Chest) and the unlocked items (05c §7)", Menus::gmBase));
        b.add(btn("Award XP", "kills, checks, a found area, research, a quest (part 6): into the PCs and their groups' pools", Menus::xpMenu));
        b.add(btn("The last-minute hauls (" + pl.production.hauls.size() + ")", "the prologue: who grabbed what; a pick taken back while all of it is still there", Hauls::gmPage));
        b.add(btn("Maps", "jump to another world (the 1:1 local maps, part 9) and back", Maps::page));
        menu(g, "GM menu", List.of(line("Game time moves only here (and by a fight's rounds). NPC statblocks: /npc.", NamedTextColor.GRAY)), b, null);
    }

    /** A template's details for a hover. */
    static String templateTip(String name) {
        Data.Template t = Data.TEMPLATES.get(name);
        if (t == null) return "";
        StringBuilder m = new StringBuilder();
        for (int i = 0; i < 7; i++)
            if (t.fixedAttr(i)) m.append(" ").append(Rules.SPECIAL[i].charAt(0)).append("=").append(t.fixed()[i]);
            else if (t.mods()[i] != 0) m.append(" ").append(t.mods()[i] > 0 ? "+" : "").append(t.mods()[i]).append(Rules.SPECIAL[i].charAt(0));
        return (t.premium() ? "premium, CoS " + t.cosPoints() + "\n" : "") + "HP +" + t.hp() + ", " + t.attributePoints() + " points"
                + (m.length() == 0 ? "" : ", " + m.toString().trim()) + ", " + t.perkText() + (t.notes().isEmpty() ? "" : "\n" + t.notes());
    }

    static void spawnForm(Player g) {
        ask(g, "Spawn a figurant", "An NPC with its own statblock (saved as /npc <name>), standing where you are; it agrees to anything (you play it). The template next.",
                List.of(Ui.In.text("n", Component.text("Name (letters, digits, _ -)")).initial("Figurant").maxLength(32).build()),
                form("Next", "pick its template", (v, x) -> {
                    String n = v.getText("n").trim();
                    if (!Store.validName(n)) { say(x, "! a name of letters, digits, _ and -"); return; }
                    pick(x, "Template of " + n, "", new ArrayList<>(Data.TEMPLATES.keySet()), t -> t, Menus::templateTip,
                            (y, t) -> say(y, pl.spawnFigurant(y.getLocation(), n, t)), Menus::spawnForm);
                }), Menus::gm);
    }

    // ------------------------------------------------------------------ perks (02c §2-§3, Marek 11-20: "please, add our perks")
    /** What the sheet applies for a perk, or "" when the GM does. */
    static String perkNumbers(Data.Perk p) {
        return Forms.traitNumbers(new Data.Trait(p.name(), "", "", "", "", "", p.mods(), p.other()));
    }

    /**
     * Picks a perk for {@code c} (a PC or a group's statblock): the perks whose gate he meets, not taken, not parked; a bound one asks
     * for the thing it is bound to (Tag! for the skill). {@code saved} runs after a pick (a statblock is written by it, and it opens
     * what follows when none is due); without it the sheet opens.
     */
    static void perkPick(Player p, Character c, Consumer<Player> saved) {
        List<String> open = new ArrayList<>();
        for (Data.Perk k : c.perksOpen()) open.add(k.name());
        if (open.isEmpty()) { say(p, "! no perk's gate is met yet (" + Data.PERKS.size() + " perks; their gates read the skills' permanent values)"); return; }
        pick(p, "Pick a perk · " + c.name, c.perksDue() + " to pick. Only the perks whose gate " + c.name + " meets; hover for the gate and the effect.",
                open, n -> n, n -> { Data.Perk k = Data.PERKS.get(n); String nums = perkNumbers(k);
                    return k.gate() + "\n" + k.effect() + (k.bound() ? "\nYou pick what it is bound to. It runs in " + BOUND_RUNS.getOrDefault(k.name(), "the GM's hands") + "."
                            : nums.isEmpty() ? "\nThe GM applies it (its rule is not in the plugin yet)." : "\nThe sheet applies: " + nums + "."); },
                (x, n) -> {
                    Data.Perk k = Data.PERKS.get(n);
                    if (!k.bound()) { perkDone(x, c, k, "", saved); return; }
                    List<String> l = boundChoices(c, k);
                    if (l.isEmpty()) { say(x, "! nothing to bind " + k.name() + " to yet (" + k.gate() + ")"); return; }
                    List<Ui.In> in = new ArrayList<>();
                    in.add(choice("b", BOUND_LABELS.getOrDefault(k.name(), "Bound to"), l));
                    ask(x, k.name(), k.effect() + "\nIt runs in " + BOUND_RUNS.getOrDefault(k.name(), "the GM's hands") + ".", in,
                            form("Take it", "the perk", (v, y) -> perkDone(y, c, k, v.getText("b"), saved)), z -> perkPick(z, c, saved));
                }, null);
    }

    /** Where each bound perk runs (02c; Marek 11-20: *"how will it work mechanically?"*). */
    static final Map<String, String> BOUND_RUNS = Map.of(
            "Tag!", "the sheet (the skill is tagged)",
            "Signature Weapon", "the battle (+15 to hit, one crit degree better, it never breaks)",
            "Weapon Master", "the battle (the family's attacks -1 AP, +25 % damage)",
            "Nemesis", "the battle (+15 to hit and +25 % damage against it)",
            "Old Friend", "every dose (its effect x1.5, never addicted)",
            "Home Turf", "the GM's hands (the plugin has no places to stand in until the local map, part 9)");
    static final Map<String, String> BOUND_LABELS = Map.of(
            "Tag!", "The fourth tagged skill", "Signature Weapon", "Your weapon (its skill 100+)", "Weapon Master", "The family (its skill 150+)",
            "Home Turf", "The place", "Nemesis", "The foe or power", "Old Friend", "The chem");

    /**
     * A bound perk's choices (Marek 11-20: *"Should we not make it a selection?"*): what the plugin knows of the thing it binds to; the
     * pick becomes part of the perk's name on the sheet (*Nemesis: Raiders*), which is what the battle and the doses read.
     */
    static List<String> boundChoices(Character c, Data.Perk k) {
        double[] v = c.skills(true);
        List<String> l = new ArrayList<>();
        switch (k.name()) {
            case "Tag!" -> { for (Rules.Skill s : Rules.SKILLS) if (!c.tags.contains(s.name())) l.add(s.name()); }
            case "Signature Weapon" -> { for (Battle.Weapon w : Battle.weapons()) if (Rules.skillValue(v, w.skill) >= 100) l.add(w.name); }
            case "Weapon Master" -> { for (String f : Rules.weaponSkills()) if (Rules.skillValue(v, f) >= 150) l.add(f); }
            case "Home Turf" -> { l.add("The base"); l.addAll(pl.roster.pop.areas); }
            case "Nemesis" -> { for (Data.Foe f : Data.FOES) l.add(f.name()); }
            case "Old Friend" -> l.addAll(Data.CHEMS.keySet());
            default -> { }
        }
        return l;
    }

    static void perkDone(Player p, Character c, Data.Perk k, String bound, Consumer<Player> saved) {
        String r = c.takePerk(k, bound);
        say(p, r);
        if (r.startsWith("!")) return;
        if (saved != null) saved.accept(p);
        if (c.perksDue() > 0) perkPick(p, c, saved); else if (saved == null) SheetView.open(p, c, SheetView.Page.TRAITS);
    }

    // ------------------------------------------------------------------ part 6: the groups, for any player (Marek 11-20)
    static void groups(Player p) {
        Population pop = pl.roster.pop;
        List<Ui.Btn> b = new ArrayList<>();
        for (Population.Group g : pop.groups) {
            int n = pop.members(g.name);
            Character sb = pl.roster.statblock(g.name);
            double lv = Population.level(g.xp, n);
            int whole = (int) Math.floor(lv + 1e-9);
            int pts = sb == null ? 0 : sb.unspentSkillPoints;
            b.add(btn(g.name + " · level " + whole + (pts > 0 ? " · " + pts + " points" : ""), n + (n == 1 ? " person" : " people") + ", XP "
                    + Math.round(g.xp) + (n > 0 ? " (" + Math.round(g.xp / n) + " a member; level " + (whole + 1) + " at " + Rules.xpForLevel(whole + 1) + ")" : "")
                    + (sb == null ? "; no statblock yet (the GM: /npc edit " + g.name + ")" : ""), x -> group(x, g.name)));
        }
        b.add(btn("Lessons", "a teacher of a higher level (a character or an NPC) teaches a group: 1 + Leadership / 25 XP a student-hour instead of work's 1, 8 hours a week", Menus::lessonsForm));
        menu(p, "Groups", List.of(line("A group's level = its pool / its people, on the characters' table. Work in the base adds 1 XP a "
                + "member-hour when the GM passes time, and every character's XP goes into his group's pool too.", NamedTextColor.GRAY)), b, Menus::actions);
    }

    static void group(Player p, String name) {
        Population pop = pl.roster.pop;
        Population.Group g = pop.group(name);
        if (g == null) { groups(p); return; }
        Character sb = pl.roster.statblock(g.name);
        List<Component> lines = new ArrayList<>();
        int n = pop.members(g.name);
        lines.add(line(n + (n == 1 ? " person" : " people") + " · XP " + Math.round(g.xp) + " · level " + Txt.f(Math.round(Population.level(g.xp, n) * 100) / 100.0)
                + (sb == null ? " · no statblock yet" : " · statblock level " + g.statLevel + ", " + sb.template), NamedTextColor.WHITE));
        for (Population.Person m : pop.membersOf(g.name))
            lines.add(line(" " + m.name + " · " + m.activity + " (" + Population.workXp(m.activity) + " XP an hour)" + (m.traits.isEmpty() ? "" : " · " + String.join(", ", m.traits)), NamedTextColor.GRAY));
        List<Ui.Btn> b = new ArrayList<>();
        if (sb != null && sb.unspentSkillPoints > 0)
            b.add(btn("Spend " + sb.unspentSkillPoints + " skill points", "the group's level-up: one statblock for all its people",
                    x -> Forms.spend(x, pl, sb, y -> { pl.roster.putStatblock(g.name, sb); group(y, g.name); })));
        if (sb != null && sb.perksDue() > 0)
            b.add(btn("Pick a perk (" + sb.perksDue() + ")", "the group's perk: the gates read its statblock", x -> perkPick(x, sb, y -> { pl.roster.putStatblock(g.name, sb); if (sb.perksDue() == 0) group(y, g.name); })));
        menu(p, "Group " + g.name, lines, b, Menus::groups);
    }

    static void lessonsForm(Player p) {
        List<String> names = new ArrayList<>(), groups = new ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers()) names.add(o.getName());
        for (String n : pl.store.npcNames()) if (!names.contains(n)) names.add(n);
        for (Population.Group g : pl.roster.pop.groups) groups.add(g.name);
        if (groups.isEmpty()) { say(p, "! no groups"); return; }
        ask(p, "Lessons", "A teacher of a higher level than the group (a player's character, or an NPC statblock: a group's teaches at its "
                        + "group's level) teaches it. Each student (a class of 1 + Leadership / 10) gets 1 + Leadership / 25 XP an hour "
                        + "instead of his work's 1; at most " + Rules.LESSON_HOURS_A_WEEK + " lesson hours a group a week; the teacher earns his work's 1 XP an hour.",
                List.of(choice("t", "Teacher", names), choice("g", "Group", groups),
                        Ui.In.numberRange("h", Component.text("Hours"), 1, Rules.LESSON_HOURS_A_WEEK).step(1f).initial(Rules.LESSON_HOURS_A_WEEK).width(300).build()),
                form("Teach", "the lessons", (v, x) -> {
                    say(x, pl.roster.lessons(v.getText("t"), v.getText("g"), Math.round(v.getFloat("h"))));
                    groups(x);
                }), Menus::groups);
    }

    /** The dead PC's player picks the member he goes on as (06 §2.3). */
    static void takeOverPick(Player p, Character dead) {
        List<String> who = new ArrayList<>();
        for (Population.Person m : pl.roster.pop.membersOf(dead.group)) who.add(m.name);
        pick(p, "Who you become", "Your character is dead. Pick who of " + dead.group + " you become (he leaves the group's count with his share); "
                        + "then set his attributes and traits, his perks and the last 2 levels' skill points.",
                who, m -> m, m -> { Population.Person q = pl.roster.pop.person(m); return q == null ? "" : q.activity + (q.traits.isEmpty() ? "" : " · " + String.join(", ", q.traits)); },
                (x, m) -> Forms.fromGroup(x, pl, m), Menus::actions);
    }

    // ------------------------------------------------------------------ part 6: the XP awards (06b §5.4, RULED 11-20)
    /** One toggle a player online: who is in the team (all ticked). */
    static List<Ui.In> teamInputs() {
        List<Ui.In> in = new ArrayList<>();
        int i = 0;
        for (Player o : Bukkit.getOnlinePlayers())
            in.add(Ui.In.bool("p" + i++, Component.text(o.getName() + " · " + pl.character(o).name)).initial(true).build());
        return in;
    }

    /** The players ticked in {@link #teamInputs}. */
    static List<Player> teamOf(DialogResponseView v) {
        List<Player> l = new ArrayList<>();
        int i = 0;
        for (Player o : Bukkit.getOnlinePlayers()) if (Boolean.TRUE.equals(v.getBoolean("p" + i++))) l.add(o);
        return l;
    }

    static void xpMenu(Player g) {
        List<Ui.Btn> b = new ArrayList<>();
        b.add(btn("Enemies beaten", "their kill XP, 3.1 × HP^1.09 each, to the whole team, shared by the team leader's Leadership "
                + "(a peaceful end gives the same)", Menus::xpKill));
        b.add(btn("A passed check", "its difficulty in % (at least " + Rules.CHECK_XP_MIN + "), Doctor doubled, to the person", Menus::xpCheck));
        b.add(btn("An area found", "a non-generic area: 100 × the areas found before (" + pl.roster.pop.areasFound + " so far), to each", Menus::xpDiscover));
        b.add(btn("Research finished", "100 × tier × rarity to each researcher", Menus::xpResearch));
        b.add(btn("A quest or any XP", "a set amount to each", Menus::xpGive));
        menu(g, "GM · award XP", List.of(line("Every PC's XP also goes whole into his group's pool; levels and skill points come by themselves.", NamedTextColor.GRAY),
                line("The table: a level L → L+1 costs 250 × L × √L (level 2 at 250, 5 at 4 256, 10 at 27 762).", NamedTextColor.GRAY)), b, Menus::gm);
    }

    static void xpKill(Player g) {
        List<Ui.In> in = teamInputs();
        in.add(Ui.In.text("hp", Component.text("The enemies' HP (one number each, commas)")).initial("30, 30, 30").maxLength(200).build());
        ask(g, "Enemies beaten", "Tick the team. Each gets the sum × his share: 1 / (1 + (n − 1) × max(0, 1 − Leadership / 250)), the best Leadership in the team.", in,
                form("Award", "the kill XP", (v, x) -> {
                    List<Double> hps = new ArrayList<>();
                    for (String t : v.getText("hp").split("[,; ]+")) {
                        if (t.isBlank()) continue;
                        try { hps.add(Double.parseDouble(t.trim())); } catch (NumberFormatException e) { say(x, "! not a number: " + t); return; }
                    }
                    say(x, pl.roster.killAward(teamOf(v), hps, "enemies beaten"));
                    xpMenu(x);
                }), Menus::xpMenu);
    }

    static void xpCheck(Player g) {
        List<String> names = new ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers()) names.add(o.getName());
        if (names.isEmpty()) { say(g, "! nobody online"); return; }
        List<String> skills = new ArrayList<>(List.of("—"));
        for (Rules.Skill s : Rules.SKILLS) skills.add(s.name());
        ask(g, "A passed check", "XP = the difficulty (the penalty in % points: a −40 % lock = 40), at least " + Rules.CHECK_XP_MIN + ", Doctor doubled. "
                        + "The skill: Hands-On Learner's +1 point when it mattered (up to " + Character.HANDS_ON_MAX + " skills a session).",
                List.of(choice("who", "Who", names), Ui.In.numberRange("d", Component.text("Difficulty (%)"), 0, 100).step(5f).initial(20f).width(300).build(),
                        Ui.In.bool("doc", Component.text("Doctor (doubled)")).initial(false).build(), choice("skill", "The skill (when it mattered)", skills)),
                form("Award", "the check's XP", (v, x) -> {
                    Player who = Bukkit.getPlayerExact(v.getText("who"));
                    if (who == null) { say(x, "! " + v.getText("who") + " is not online"); return; }
                    boolean doc = Boolean.TRUE.equals(v.getBoolean("doc"));
                    String r = pl.roster.award(who, Rules.checkXp(v.getFloat("d"), doc), doc ? "Doctor" : "a check");
                    String ho = pl.character(who).handsOn(v.getText("skill"), handsOnSession());
                    if (!ho.isEmpty()) { who.sendMessage(Component.text(ho, NamedTextColor.GREEN)); pl.save(who.getUniqueId()); r += " · " + ho; }
                    say(x, r);
                    xpMenu(x);
                }), Menus::xpMenu);
    }

    /** The session Hands-On Learner counts in: the evening's date (a session after midnight still counts as that evening) [mine]. */
    static String handsOnSession() { return java.time.LocalDateTime.now().minusHours(6).toLocalDate().toString(); }

    static void xpDiscover(Player g) {
        List<Ui.In> in = teamInputs();
        in.add(Ui.In.text("n", Component.text("Its name (Home Turf can be bound to it)")).initial("").maxLength(60).build());
        ask(g, "An area found", "A non-generic area (not a random encounter's map): each ticked PC gets 100 × " + pl.roster.pop.areasFound + " = "
                        + Rules.discoveryXp(pl.roster.pop.areasFound) + " XP, and it counts as found.", in,
                form("Found", "award and count it", (v, x) -> { say(x, pl.roster.discover(teamOf(v), v.getText("n"))); xpMenu(x); }), Menus::xpMenu);
    }

    static void xpResearch(Player g) {
        List<Ui.In> in = teamInputs();
        in.add(Ui.In.numberRange("t", Component.text("Tier"), 1, 4).step(1f).initial(1f).width(300).build());
        in.add(Ui.In.numberRange("r", Component.text("Rarity"), 1, 5).step(1f).initial(1f).width(300).build());
        ask(g, "Research finished", "Each ticked researcher gets 100 × tier × rarity.", in,
                form("Award", "the research XP", (v, x) -> {
                    int t = Math.round(v.getFloat("t")), r = Math.round(v.getFloat("r"));
                    List<String> out = new ArrayList<>();
                    for (Player p : teamOf(v)) out.add(pl.roster.award(p, Rules.researchXp(t, r), "research, T" + t + " R" + r));
                    say(x, out.isEmpty() ? "! nobody ticked" : String.join(" · ", out));
                    xpMenu(x);
                }), Menus::xpMenu);
    }

    static void xpGive(Player g) {
        List<Ui.In> in = teamInputs();
        in.add(Ui.In.text("n", Component.text("XP each")).initial("100").maxLength(9).build());
        in.add(Ui.In.text("why", Component.text("For (shown to them)")).initial("a quest").maxLength(60).build());
        ask(g, "A quest or any XP", "The same amount to each ticked PC.", in,
                form("Award", "the XP", (v, x) -> {
                    long n;
                    try { n = Long.parseLong(v.getText("n").trim()); } catch (NumberFormatException e) { say(x, "! a whole number, please"); return; }
                    List<String> out = new ArrayList<>();
                    for (Player p : teamOf(v)) out.add(pl.roster.award(p, n, v.getText("why").trim()));
                    say(x, out.isEmpty() ? "! nobody ticked" : String.join(" · ", out));
                    xpMenu(x);
                }), Menus::xpMenu);
    }

    /** Everyone the clock moves for: the players online and the NPCs loaded. */
    static List<Who> everyone() {
        List<Who> l = new ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers()) if (!pl.character(o).away) l.add(of(o));   // out of the game: untouched (round 11)
        for (Map.Entry<String, Character> e : pl.npcs.entrySet()) l.add(new Who(e.getKey(), e.getValue(), null, null));
        return l;
    }

    /** Everything the GM does to every character at once (Marek 11-16: *"option menu that affects every player character at once"*). */
    static void gmAll(Player g) {
        List<Ui.Btn> b = new ArrayList<>();
        b.add(btn("Pass time", "the game clock for every character", x -> passTime(x, null)));
        b.add(btn("Heal everyone fully", "HP to the maximum, bleeding stopped, no arrest", x -> {
            for (Who w : everyone()) { Character c = w.c(); c.dead = false; c.revived(); c.hp = c.hpMax(); c.bleeding = 0; }
            say(x, "Everyone is healed."); gmAll(x); }));
        b.add(btn("Stop all bleeding", "first aid for everyone", x -> { for (Who w : everyone()) w.c().bleeding = 0; say(x, "No one bleeds."); gmAll(x); }));
        b.add(btn("Give every player an item", "the same item into every player's pack", x -> giveStart(x, null)));
        menu(g, "GM · all characters", List.of(line(everyone().size() + " characters: the players online and the NPCs loaded.", NamedTextColor.GRAY)), b, Menus::gm);
    }

    /**
     * The time of day (Marek 11-16: *"game clock has no time of day yet - add it please"*): sliders for the hour and the minute, one
     * character or everyone; the day stays. Passing time moves it on; Night Person reads it (18:00-06:00 = night).
     */
    static void timeOfDay(Player g, Who only) {
        Character ref = only != null ? only.c() : everyone().isEmpty() ? null : everyone().get(0).c();
        double h = ref == null ? 8 : ref.hourOfDay();
        List<Ui.In> in = new ArrayList<>(List.of(
                Ui.In.numberRange("h", Component.text("Hour"), 0, 23).step(1f).initial((float) Math.floor(h)).width(300).build(),
                Ui.In.numberRange("m", Component.text("Minute"), 0, 55).step(5f).initial((float) (5 * Math.floor((h % 1) * 12))).width(300).build()));
        if (only != null) in.add(Ui.In.bool("all", Component.text("For everyone, not only " + only.name())).initial(false).build());
        ask(g, "Time of day", (ref == null ? "" : "Now " + ref.timeText() + ". ") + "Night is 18:00-06:00.", in,
                form("Set", "set the clock", (v, x) -> {
                    double to = Math.round(v.getFloat("h")) + Math.round(v.getFloat("m")) / 60.0;
                    boolean all = only == null || Boolean.TRUE.equals(v.getBoolean("all"));
                    for (Who o : all ? everyone() : List.of(only)) { o.c().setTimeOfDay(to); o.tell("The time: " + o.c().timeText(), NamedTextColor.GRAY); }
                    say(x, "Time set" + (all ? " for everyone" : " for " + only.name()) + ".");
                }), only == null ? Menus::gm : x -> gmOn(x, only));
    }

    /** Sliders for the hours and minutes (Marek 11-16: *"Pass time should be slider"*), for one character or everyone. */
    /** What a wash outside means (round 9) [the numbers EXAMPLE, mine]. */
    static String washText() {
        return "2 l of your own water or a stream's: Tier I on the skin +4 rads (dirty) or +10 (irradiated); with a wound, water with "
                + "germs (Tier I, a filtered Tier II) means a wound-infection roll (2 % in Tier II, 15 % in Tier I)";
    }

    static void passTime(Player g, Who only) {
        List<Ui.In> in = new ArrayList<>(List.of(
                Ui.In.numberRange("h", Component.text("Hours"), 0, 168).step(1f).initial(only == null ? 1f : 0f).width(300).build(),
                Ui.In.numberRange("m", Component.text("Minutes"), 0, 59).step(1f).initial(only == null ? 0f : 1f).width(300).build()));
        if (only != null) in.add(Ui.In.bool("all", Component.text("For everyone, not only " + only.name())).initial(false).build());
        ask(g, "Pass time", only == null ? "For every character (players online and NPCs)." : "For " + only.name() + ", or tick for everyone.", in,
                form("Pass", "the time passes", (v, x) -> {
                    double min = Math.round(v.getFloat("h")) * 60.0 + Math.round(v.getFloat("m"));
                    if (min <= 0) { say(x, "! no time to pass"); return; }
                    boolean all = only == null || Boolean.TRUE.equals(v.getBoolean("all"));
                    String s = (min >= 60 ? (int) (min / 60) + " h " : "") + (min % 60 > 0 ? (int) (min % 60) + " min" : "");
                    for (Who o : all ? everyone() : List.of(only)) {
                        if (o.entity() != null) pl.zones.apply(o.c(), o.entity());   // the gas zone he stands in
                        // Marek 11-24: in the base he eats and drinks from its store by his own needs (Meals.baseDay at each day): his
                        // bars hold while the store had his meals last time; the base's soap covers him on a day it had soap
                        boolean inBase = all && !o.c().outside && !pl.production.buildings.isEmpty();
                        o.c().baseFood = inBase && !o.c().baseShortFood;
                        o.c().baseWater = inBase && !o.c().baseShortWater && Meals.cannotDrink(o.c()) == null;
                        if (inBase && pl.production.washSoap && pl.production.soapToday) o.c().soap = true;
                        String r = Hud.pass(o.c(), min);
                        o.c().baseFood = o.c().baseWater = false;
                        if (o.player() != null && o.c().workedHours > 0) {   // downtime work (06b §5.4.7): 1 XP an hour
                            int wh = o.c().workedHours;
                            o.c().workedHours = 0;
                            pl.roster.award(o.player(), (long) wh * Rules.workXp(true), wh + " h of work");
                        }
                        o.tell(s.trim() + " pass: " + o.c().timeText() + ". " + Part3.timed(o.c()) + (r == null ? "" : " " + r), NamedTextColor.GRAY);
                        if (r != null) tellGm(o.name() + ": " + r);
                    }
                    if (all) pl.zones.rounds((int) Math.round(min * 10));   // the zones that count down: the world's time
                    say(x, s.trim() + " passed" + (all ? " for everyone" : " for " + only.name()) + ".");
                    double before = pl.roster.pop.worldHours;
                    if (all) for (String w : pl.roster.work(min / 60)) say(x, w + ".");   // the groups' people work (06 §2.1)
                    if (all) {   // part 7: the buildings' jobs, power and water by the day, upkeep by the week
                        int hurt = (int) pl.roster.pop.people.stream().filter(p -> p.hpMax > 0 && p.hp < p.hpMax).count();
                        List<Who> inBase = pl.production.buildings.isEmpty() ? List.of()
                                : everyone().stream().filter(o -> !o.c().outside && !o.c().dead).toList();   // they eat and drink there too
                        Meals.npcFactors(pl.production, pl.roster.pop.people, grp -> {   // by their species and their group's Survival
                            Character sb = pl.roster.statblock(grp);
                            return sb == null ? 0 : sb.skill("Survival");
                        });
                        pl.production.soapPeople = inBase.size();
                        pl.production.dayHook = b -> {
                            for (Who o : inBase) {
                                String m = Meals.baseDay(o.c(), pl.production, b, pl.rnd);
                                if (m == null) continue;
                                o.tell(m, m.contains("CAUGHT") ? NamedTextColor.RED : NamedTextColor.GRAY);
                                if (m.contains("CAUGHT") || m.contains("short")) tellGm(o.name() + ": " + m);
                            }
                        };
                        try {
                            for (String w : pl.production.advance(min / 60, before, pl.base, pl.workforce(), pl.roster.pop.people.size(), hurt)) say(x, w);
                        } finally { pl.production.dayHook = null; }
                        if (min >= 1440 - 1e-9 || pl.production.unwashed > 0)   // round 9: in the base, the base's lot
                            for (Who o : everyone()) if (!o.c().outside) o.c().unwashedDays = pl.production.unwashed;
                        pl.production.save();
                        pl.roster.save();
                    }
                }), only == null ? Menus::gm : x -> gmOn(x, only));
    }

    static void gmOn(Player g, Who o) {
        if (o == null || !g.hasPermission("falloutak.gm")) return;
        Character c = o.c();
        List<Ui.Btn> b = new ArrayList<>();
        Consumer<Player> back = x -> gmOn(x, o);
        b.add(btn("Wound", "HP, Pain 3 a HP, bleeding on a crit", x -> ask(x, "Wound " + o.name(), "", List.of(
                        Ui.In.numberRange("n", Component.text("Damage"), 1, 50).step(1f).initial(5f).width(300).build(), choice("k", "Kind", List.of("normal", "crit", "stab"))),
                form("Wound", "apply", (v, y) -> { String r = Hud.hit(c, Math.round(v.getFloat("n")), v.getText("k")); say(y, o.name() + ": " + r); o.tell(r, NamedTextColor.RED); gmOn(y, o); }), back)));
        // 11-17 (Marek: "being fully healed does not seem to remove pain and other effects"): Pain and Blood too
        b.add(btn("Heal fully", "HP to the maximum, Pain 0, Blood full, bleeding stopped, no arrest (hunger, thirst, sleep, poison, "
                + "rads, cold and heat stay: Survival values)", x -> { c.dead = false; c.revived(); c.hp = c.hpMax(); c.bleeding = 0;
            c.survival.put(Rules.Stat.PAIN, 0.0); c.survival.put(Rules.Stat.BLOOD, c.max(Rules.Stat.BLOOD));
            c.survival.put(Rules.Stat.OXYGEN, c.max(Rules.Stat.OXYGEN)); c.oxygenZeroAt = -1; gmOn(x, o); }));
        b.add(btn("Survival values", "set his water, food, sleep, poison, cold, heat, Blood and Pain by sliders (rads: Add rads)", x -> survivalForm(x, o)));
        b.add(btn("Effects", "his chems, hangovers, mutations and traits' effects: remove one", x -> effectsList(x, o)));
        if (!c.mutated.isEmpty()) b.add(btn("Mutated genes (" + c.mutated.size() + ")", "the genes his mutations changed (03b §7a): undo one", x -> mutatedList(x, o)));
        b.add(btn("Clinical death now", "HP below −Endurance: its clock runs with game time (an implanted defibrillator shocks)", x -> { c.hp = Rules.deathFloor(c.attrs(false)) - 1; c.arrestAt = c.now(); c.cprFrom = -1;
            String d = c.autoDefibrillate(pl.rnd); if (!d.isEmpty()) say(x, o.name() + ":" + d); gmOn(x, o); }));
        if (c.inArrest() || c.dead) b.add(btn("Revive now", "no roll: back at −Endurance", x -> { c.dead = false; c.revived(); gmOn(x, o); }));
        // 03b §3c (RULED 11-18): the air and the gases, the ears, the heart's causes
        b.add(btn("Air and gas", "no air, a gas and its strength; set off methane (03b §3c)"
                + (c.gasHere() != null ? "; now " + c.gasHere().name() + " " + Rules.roman(c.gasPower()) : "") + (c.air ? "" : "; NO AIR"), x -> airMenu(x, o)));
        b.add(btn("Ears", "a blast, a flashbang, lightning, a dive, ringing, a lasting loss; heal them (03b §3c)", x -> earsMenu(x, o)));
        b.add(btn("Heart", "a blow to the chest, an electric crit or hazard: the heart stops with HP kept (03b §3c)", x -> heartMenu(x, o)));
        b.add(btn("Power Armour Training", "05c §3c: " + c.paText(), x -> paMenu(x, o)));
        if (o.player() != null) b.add(btn("Give an item", "into his pack: a filter, the list, how many", x -> giveStart(x, o.player())));
        b.add(btn("Fit an implant", "the implant, then its tier (no operation, no roll)", x -> fitStart(x, o)));
        b.add(btn("Gene therapy" + (c.therapies.isEmpty() ? "" : " (" + c.therapies.size() + ")"),
                "give a gene or a shop set as a therapy, see his therapies, the gene pump (03d)", x -> therapies(x, o)));
        for (Character.Effect e : c.implants()) b.add(btn("Remove " + e.name().replaceAll(" · Tier.*", ""), "gone, no item", x -> { c.removeImplant(e.slot().substring(8)); gmOn(x, o); }));
        b.add(btn("Prosthesis", "a limb, then its kind", x -> pick(x, "Prosthesis for " + o.name(), "Which limb?",
                List.of("left_arm", "right_arm", "left_leg", "right_leg"), l -> l.replace('_', ' '), l -> "now: " + c.efficiency(l) + " %",
                (y, l) -> pick(y, "Prosthesis: " + l.replace('_', ' '), "Which kind?", List.of("hook", "peg", "mechanical", "powered", "bionic", "missing", "natural"),
                        k -> k, k -> switch (k) { case "hook", "peg" -> "50-60 %"; case "mechanical" -> "80 %"; case "powered" -> "100 %"; case "bionic" -> "120 %";
                            case "missing" -> "no limb: an arm −50 to hit, a leg as a broken one"; default -> "the natural limb back"; },
                        (z, k) -> { say(z, Part3.limb(c, new String[]{"limb", l, k})); gmOn(z, o); }, back), back)));
        b.add(btn("Mutation roll", "the yearly roll, now", x -> ask(x, "Mutation for " + o.name(), "",
                List.of(Ui.In.numberRange("r", Component.text("Rads expelled"), 100, 500).step(100f).initial(100f).width(300).build()),
                form("Roll", "roll", (v, y) -> { String r = c.mutate(Math.round(v.getFloat("r")), pl.rnd); say(y, r); o.tell(r, NamedTextColor.YELLOW); }), back)));
        // Marek 11-16: "I will likely need individual units. Maybe consider just adding rads with just 100 as a maximum?"
        b.add(btn("Add rads", "a dose of 1-100 rads (a slider), through his Rad resistance", x -> addRads(x, o)));
        if (o.player() != null) {
            List<String> prem = new ArrayList<>();
            for (Data.Template t : Data.TEMPLATES.values()) if (t.premium() || t.faction()) prem.add(t.name());
            b.add(btn("Grant a premium or faction template", "his CoS points, or he joined their faction: /sheet create offers it",
                    x -> pick(x, "Template for " + o.name(), "A premium one is paid from his CoS points; a people's one (the Mývaloid) opens when he joins their faction.",
                    prem, t -> "★ " + t, Menus::templateTip, (y, t) -> { if (!c.unlocked.contains(t)) c.unlocked.add(t); say(y, "granted: " + t); gmOn(y, o); }, back)));
        }
        b.add(btn("Pass time", "his clock, or everyone's (a tick in the form)", x -> passTime(x, o)));
        b.add(btn("Time of day", "now " + c.timeText() + ": set his hour and minute, or everyone's", x -> timeOfDay(x, o)));
        b.add(btn("His sheet", "", x -> SheetView.open(x, c, SheetView.Page.OVERVIEW)));
        if (o.npc() && o.entity() != null) {      // THE GM'S NPC TOOLS (Marek 11-24: follow, hostile, not hostile, join the faction)
            Npcs.Side sd = Npcs.side(o.entity());
            java.util.UUID ld = Npcs.leader(o.entity());
            Player lp = ld == null ? null : Bukkit.getPlayer(ld);
            List<String> names = new ArrayList<>();
            for (Player q : Bukkit.getOnlinePlayers()) names.add(q.getName());
            b.add(btn("Side: " + sd.label, "hostile fights the party; neutral holds until attacked; the party's fights with the party (in the GM's fight here)",
                    x -> pick(x, "Side of " + o.name(), "Its side in a fight where it stands (the GM menu: Fight here).",
                            List.of("HOSTILE", "NEUTRAL", "PARTY"), k -> Npcs.Side.valueOf(k).label, k -> Npcs.Side.valueOf(k).tip,
                            (y, k) -> { say(y, Npcs.setSide(o.entity(), o.name(), Npcs.Side.valueOf(k))); gmOn(y, o); }, back)));
            if (ld != null) b.add(btn("Stop following" + (lp == null ? "" : " " + lp.getName()), "it stays where it stands",
                    x -> { say(x, Npcs.unfollow(o.entity(), o.name())); gmOn(x, o); }));
            else b.add(btn("Follow a player", "it walks after him (a jump to him when he is far or in another world)",
                    x -> pick(x, o.name() + " follows", "Whom?", names, n -> n, n -> "", (y, n) -> {
                        Player q = Bukkit.getPlayerExact(n);
                        say(y, q == null ? "! " + n + " is not online" : Npcs.follow(o.entity(), o.name(), q)); gmOn(y, o); }, back)));
            b.add(btn("Join a player's faction", "the party's side, his faction on its statblock, and it follows him",
                    x -> pick(x, o.name() + " joins", "Whose faction?", names, n -> n, n -> pl.character(Bukkit.getPlayerExact(n)) == null ? "" : "faction: "
                            + (pl.character(Bukkit.getPlayerExact(n)).group.isEmpty() ? "none on his sheet" : pl.character(Bukkit.getPlayerExact(n)).group), (y, n) -> {
                        Player q = Bukkit.getPlayerExact(n);
                        say(y, q == null ? "! " + n + " is not online" : Npcs.join(pl, o.entity(), o.name(), q)); gmOn(y, o); }, back)));
        }
        if (o.npc() && o.entity() != null) b.add(btn("Remove the figurant", "the body goes; its statblock stays (/npc)", x -> { pl.saveNpc(o.name()); o.entity().remove(); pl.npcs.remove(o.name()); gm(x); }));
        double[] am = c.arrestMinutes();
        menu(g, "GM · " + o.name(), List.of(line(c.name + " · " + c.template + " · HP " + c.hp + "/" + c.hpMax() + (c.dead ? " · DEAD" : c.inArrest()
                ? " · CLINICAL DEATH " + Hud.clock(am[0] + am[1]) : "") + (c.bleeding > 0 ? " · bleeding " + Txt.f(c.bleeding) : ""), NamedTextColor.WHITE)), b, Menus::gm);
    }

    /** The survival values a GM sets by hand (11-17), in the order of the sheet; rads have their own form (a dose through resistance). */
    static final List<Rules.Stat> SETTABLE = List.of(Rules.Stat.WATER, Rules.Stat.FOOD, Rules.Stat.SLEEP, Rules.Stat.POISON,
            Rules.Stat.HYPOTHERMIA, Rules.Stat.HYPERTHERMIA, Rules.Stat.BLOOD, Rules.Stat.PAIN, Rules.Stat.OXYGEN);

    // ------------------------------------------------------------------ 03b §3c: the GM's air, ears and heart
    static int strengthOf(String roman) { return switch (roman) { case "III" -> 3; case "II" -> 2; default -> 1; }; }

    /** The place's air for one character (or everyone near him): no air, a gas at strength I-III, setting methane off. */
    static void airMenu(Player g, Who o) {
        Character c = o.c();
        List<Ui.Btn> b = new ArrayList<>();
        Consumer<Player> back = x -> airMenu(x, o);
        b.add(btn(c.air ? "No air here" : "Air again", c.air ? "buried, sealed, a flooded room: Oxygen −1 a round (under water is read from the world)"
                : "he breathes the place's air again", x -> { c.air = !c.air; o.tell(c.air ? "You can breathe here again." : "There is NO AIR here.", NamedTextColor.GOLD); airMenu(x, o); }));
        b.add(btn("Gas zones (the world)", "a gas over an area, for everyone in it: the usual way (Marek 11-18: localized)", Menus::zonesMenu));
        b.add(btn("No gas of his own", "his own setting below is for a statblock with no body or a quick test; a zone wins over it",
                x -> { setGas(o, "", 1); airMenu(x, o); }));
        for (Rules.Gas gs : Rules.GASES.values()) if (!Zones.NOT_BREATHED.contains(gs.name()))
            b.add(btn(Hud.cap(gs.name()), gs.where() + ": " + gs.text(), x -> pick(x, Hud.cap(gs.name()) + " · " + o.name(), gs.text(),
                    List.of("I", "II", "III"), s -> "Strength " + s, s -> "× " + strengthOf(s) + " the row",
                    (y, s) -> { setGas(o, gs.name(), strengthOf(s)); airMenu(y, o); }, back)));
        Rules.Gas here = c.gasHere();
        if (here != null && here.ignites())
            b.add(btn("! Set the " + here.name() + " off", "a spark or a flame: a blast as a grenade's (" + Combat.GRENADE_MIN + "-" + Combat.GRENADE_MAX
                    + ", DT and DR), the ears' roll; the gas burns away", x -> {
                int base = Combat.GRENADE_MIN + pl.rnd.nextInt(Combat.GRENADE_MAX - Combat.GRENADE_MIN + 1);
                int dmg = Combat.damage(base, 1, 0, 0, c.dt(), c.dr(), false);
                String r = "the " + here.name() + " goes off: " + Hud.hit(c, dmg, "normal");
                String e = c.blast(0, Combat.GRENADE_RADIUS, pl.rnd);
                if (!e.isEmpty()) r += "; " + e;
                c.gas = "";
                say(x, o.name() + ": " + r);
                o.tell(r, NamedTextColor.RED);
                airMenu(x, o);
            }));
        if (o.entity() != null)
            b.add(btn("The same for everyone within 8 blocks", "copies " + o.name() + "'s air and gas to everyone around him", x -> {
                List<String> who = new ArrayList<>();
                for (org.bukkit.entity.Entity e : o.entity().getNearbyEntities(8, 8, 8)) {
                    Who w = whoOf(e);
                    if (w == null) continue;
                    w.c().air = c.air;
                    setGas(w, c.gas, c.gasStrength);
                    who.add(w.name());
                }
                say(x, who.isEmpty() ? "nobody else within 8 blocks" : "the same air for " + String.join(", ", who));
                airMenu(x, o);
            }));
        String now = (c.air ? "" : "NO AIR · ") + (here == null ? "no gas" : here.name() + " " + Rules.roman(c.gasPower()) + (c.inZone() ? " (a zone)" : "")) + " · Oxygen "
                + Math.round(c.survival.getOrDefault(Rules.Stat.OXYGEN, 0.0)) + "/" + Math.round(c.max(Rules.Stat.OXYGEN));
        menu(g, "Air and gas · " + o.name(), List.of(line(now, NamedTextColor.WHITE),
                line("Each round inside unprotected (03b §3c, game numbers). Oxygen moves only with game time: a fight's rounds or Pass time.", NamedTextColor.GRAY)),
                b, x -> gmOn(x, o));
    }

    static void setGas(Who o, String gas, int strength) {
        Character c = o.c();
        c.gas = gas;
        c.gasStrength = strength;
        if (gas.isEmpty() || !Rules.GASES.get(gas).bad()) c.badAirRounds = 0;
        if (gas.isEmpty() || !gas.equals("mercury vapour")) c.mercuryHours = 0;
        Rules.Gas g = c.gasHere();
        o.tell(g == null ? "The air here is clean." : "The air here: " + g.name() + " " + Rules.roman(strength) + " (" + g.text() + ")", NamedTextColor.GOLD);
    }

    /** The two corners a GM has marked for a box zone (the blocks he looked at). */
    static final Map<UUID, Location[]> CORNERS = new HashMap<>();

    /**
     * Gas zones (Marek 11-18: *"I would like these effects localized to specific areas, like in the factory, or anomalies with the acid
     * rain and the gas granade also hits specific space."*): a ball around the GM, or a box between two marked corners (a factory
     * hall, an anomaly); lasting or counting down rounds; the list, nearest first, each removable.
     */
    static void zonesMenu(Player g) {
        if (!g.hasPermission("falloutak.gm")) return;
        List<Ui.Btn> b = new ArrayList<>();
        Location[] cs = CORNERS.computeIfAbsent(g.getUniqueId(), u -> new Location[2]);
        b.add(btn("A zone where you look", "a ball of gas centred on the block you look at (up to 64 blocks): the gas, its strength, the radius, how long", x -> {
            org.bukkit.block.Block t = x.getTargetBlockExact(64);
            if (t == null) { say(x, "! look at a block within 64 blocks"); zonesMenu(x); return; }
            Location at = t.getLocation().add(0.5, 1.5, 0.5);   // the air over the block, at breathing height
            zoneGas(x, (gas, s) -> pick(x, "Radius", "Blocks from " + t.getX() + " " + t.getY() + " " + t.getZ() + ", every way.", List.of("2", "3", "4", "8", "16"),
                    r -> r + " blocks", r -> "a ball of radius " + r, (y, r) -> zoneTime(y, rounds -> { Zones.Zone z = pl.zones.ball(at, Double.parseDouble(r), gas, s, rounds, "");
                        say(y, "zone " + z.id + ": " + z.describe()); zonesMenu(y); }), Menus::zonesMenu));
        }));
        b.add(btn("A zone around you", "a ball of gas centred where you stand: the gas, its strength, the radius, how long", x ->
                zoneGas(x, (gas, s) -> pick(x, "Radius", "Blocks from you, every way.", List.of("2", "3", "4", "8", "16"), r -> r + " blocks", r -> "a ball of radius " + r,
                        (y, r) -> zoneTime(y, rounds -> { Zones.Zone z = pl.zones.ball(y.getLocation().add(0, 1, 0), Double.parseDouble(r), gas, s, rounds, "");
                            say(y, "zone " + z.id + ": " + z.describe()); zonesMenu(y); }), Menus::zonesMenu))));
        for (int k = 0; k < 2; k++) {
            int i = k;
            b.add(btn("Corner " + (k + 1) + ": the block you look at" + (cs[k] == null ? "" : " (" + cs[k].getBlockX() + " " + cs[k].getBlockY() + " " + cs[k].getBlockZ() + ")"),
                    "up to 64 blocks away; the box takes both corners' blocks in", x -> {
                        org.bukkit.block.Block t = x.getTargetBlockExact(64);
                        if (t == null) { say(x, "! look at a block within 64 blocks"); zonesMenu(x); return; }
                        cs[i] = t.getLocation();
                        zonesMenu(x);
                    }));
        }
        if (cs[0] != null && cs[1] != null && cs[0].getWorld().equals(cs[1].getWorld()))
            b.add(btn("A zone between the corners", (Math.abs(cs[0].getBlockX() - cs[1].getBlockX()) + 1) + "×" + (Math.abs(cs[0].getBlockY() - cs[1].getBlockY()) + 1) + "×"
                    + (Math.abs(cs[0].getBlockZ() - cs[1].getBlockZ()) + 1) + " blocks: a factory hall, an anomaly's ground", x ->
                    zoneGas(x, (gas, s) -> zoneTime(x, rounds -> { Zones.Zone z = pl.zones.box(cs[0], cs[1], gas, s, rounds, "");
                        say(x, "zone " + z.id + ": " + z.describe()); zonesMenu(x); }))));
        List<Zones.Zone> zs = new ArrayList<>(pl.zones.list);
        zs.sort(Comparator.comparingDouble(z -> { org.bukkit.World w = Bukkit.getWorld(z.world);
            return w == null || !w.equals(g.getWorld()) ? 1e18 : z.centre(w).distanceSquared(g.getLocation()); }));
        for (Zones.Zone z : zs.subList(0, Math.min(12, zs.size())))
            b.add(btn("Remove zone " + z.id + ": " + (z.anomaly.isEmpty() ? z.gas + " " + Rules.roman(z.strength) : "anomaly " + z.anomaly), z.describe(), x -> { pl.zones.remove(z); zonesMenu(x); }));
        if (pl.zones.list.stream().anyMatch(z -> z.rounds >= 0))
            b.add(btn("Remove every zone that counts down", "the clouds; the lasting ones stay", x -> {
                for (Zones.Zone z : new ArrayList<>(pl.zones.list)) if (z.rounds >= 0) pl.zones.remove(z);
                zonesMenu(x); }));
        Zones.Zone here = pl.zones.at(g.getEyeLocation());
        menu(g, "Gas zones", List.of(line(pl.zones.list.size() + " zone(s)" + (here == null ? "; you stand in none" : "; you stand in zone " + here.id + ": " + here.describe()), NamedTextColor.WHITE),
                line("Whoever's eyes are inside breathes it (a zone wins over a character's own gas). Visible: smoke, riot gas, chlorine, acid fog, pollen. Smoke, the acid fog and the pollen HIDE: a ranged shot into, out of or through them −10 (the pollen is breathed only by the allergic); "
                        + "the rest only to the GM (purple). A countdown runs with a fight's rounds and Pass time for everyone.", NamedTextColor.GRAY)), b, Menus::gm);
    }

    // ------------------------------------------------------------------ the anomalies and the Black Rain (09c, `09b` round 9)
    /** The GM's page: an anomaly by kind where he stands, a field around him, the nearest removed, nuts, detectors, artifacts, the Black Rain. */
    static void anomalyMenu(Player g) {
        if (!g.hasPermission("falloutak.gm")) return;
        List<Ui.Btn> b = new ArrayList<>();
        b.add(btn("An anomaly where you stand", "pick the kind, then the radius", x -> pick(x, "The kind", "09c §2: its tell, its harm, its mitigation.",
                new ArrayList<>(Anomalies.KINDS.keySet()), k -> Anomalies.KINDS.get(k).name(), k -> Anomalies.KINDS.get(k).tell(),
                (y, k) -> pick(y, "Radius", "Blocks from you.", List.of("1", "2", "3", "4"), r -> r + " blocks", r -> "a ball of radius " + r,
                        (z, r) -> { say(z, anomalyCommand(z, new String[]{"place", k, r})); anomalyMenu(z); }, Menus::anomalyMenu), Menus::anomalyMenu)));
        b.add(btn("A field around you", "6 anomalies of mixed kinds within 30 blocks on the ground, 1-3 artifacts in them (09c §1.1)",
                x -> { say(x, anomalyCommand(x, new String[]{"field", "30", "6"})); anomalyMenu(x); }));
        b.add(btn("Remove the nearest anomaly", "", x -> { say(x, anomalyCommand(x, new String[]{"remove"})); anomalyMenu(x); }));
        b.add(btn("Give me 10 nuts", "", x -> { say(x, anomalyCommand(x, new String[]{"give", "nut", "10"})); anomalyMenu(x); }));
        for (int t = 1; t <= 4; t++) {
            int tt = t;
            b.add(btn("Give me a detector, Tier " + Rules.roman(t), Items.describe("gear:Anomaly detector " + Rules.roman(t)),
                    x -> { say(x, anomalyCommand(x, new String[]{"give", "detector", String.valueOf(tt)})); anomalyMenu(x); }));
        }
        b.add(btn("Give me an artifact", "the twelve of 09c §3", x -> pick(x, "The artifact", "It acts from the hotbar.", new ArrayList<>(Anomalies.ARTIFACTS.keySet()),
                n -> n, n -> Anomalies.ARTIFACTS.get(n).text(), (y, n) -> { say(y, anomalyCommand(y, new String[]{"give", n})); anomalyMenu(y); }, Menus::anomalyMenu)));
        b.add(btn("The Black Rain: " + Anomalies.WARN_SECONDS + " s of warning, then " + Anomalies.RAIN_ROUNDS + " rounds",
                "the signs and a countdown for the players to find a roof (round 10), then out under the sky rads and the acid each round; then the fields move",
                x -> { say(x, rainCommand(x, new String[]{"start"})); anomalyMenu(x); }));
        b.add(btn("The Black Rain: an open warning", "only the signs: the radios wake, the ravens flee, the sky darkens; you start it", x -> { say(x, rainCommand(x, new String[]{"warn"})); anomalyMenu(x); }));
        b.add(btn("The Black Rain: break it now", "no warning", x -> { say(x, rainCommand(x, new String[]{"start", String.valueOf(Anomalies.RAIN_ROUNDS), "0"})); anomalyMenu(x); }));
        b.add(btn("The Black Rain: stop it now", "the fields move, new artifacts in half of them", x -> { say(x, rainCommand(x, new String[]{"stop"})); anomalyMenu(x); }));
        long n = pl.zones.list.stream().filter(z -> !z.anomaly.isEmpty()).count();
        menu(g, "Anomalies", List.of(line(n + " anomal" + (n == 1 ? "y" : "ies") + " in the worlds; the Black Rain: "
                        + (pl.anomalies.rainPhase == 0 ? "none" : pl.anomalies.rainPhase == 1 ? "the warning" : "falling"), NamedTextColor.WHITE),
                line("Walking in: its first hit at once (Lucky Escape 2 × Luck %); inside, the rounds run. Their tells show to all, their outlines to the GMs.", NamedTextColor.GRAY)), b, Menus::gm);
    }

    /** /anomaly (GMs): kinds · place <kind> [radius] · field [radius] [count] · list · remove [id] · artifact <id> [name] · give <item> [count]. */
    static String anomalyCommand(Player g, String[] a) {
        if (!g.hasPermission("falloutak.gm")) return "GMs only.";
        String sub = a.length == 0 ? "list" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "kinds" -> { StringBuilder s = new StringBuilder("The kinds:"); for (Anomalies.Kind k : Anomalies.KINDS.values()) s.append(" ").append(k.id()).append(" (").append(k.name()).append(");"); return s.toString(); }
            case "place" -> {
                if (a.length < 2 || !Anomalies.KINDS.containsKey(a[1])) return "/anomaly place <kind> [radius]: kinds " + String.join(", ", Anomalies.KINDS.keySet());
                double r = a.length > 2 ? Double.parseDouble(a[2]) : 2;
                Zones.Zone z = pl.zones.anomaly(g.getLocation().add(0, 1, 0), r, a[1]);
                return "anomaly " + z.id + ": " + z.describe();
            }
            case "field" -> {
                int radius = a.length > 1 ? Integer.parseInt(a[1]) : 30, n = a.length > 2 ? Integer.parseInt(a[2]) : 6;
                List<Zones.Zone> made = pl.anomalies.field(g, Math.max(6, radius), Math.max(1, Math.min(10, n)));
                long arts = made.stream().filter(z -> !z.artifact.isEmpty()).count();
                return "a field of " + made.size() + " anomalies within " + radius + " blocks, " + arts + " artifact" + (arts == 1 ? "" : "s") + " in them";
            }
            case "list" -> {
                StringBuilder s = new StringBuilder();
                for (Zones.Zone z : pl.zones.list) if (!z.anomaly.isEmpty()) s.append(s.isEmpty() ? "" : " · ").append(z.id).append(": ").append(z.describe());
                return s.isEmpty() ? "No anomalies." : s.toString();
            }
            case "remove" -> {
                Zones.Zone best = null;
                double bd = 1e18;
                for (Zones.Zone z : pl.zones.list) {
                    if (z.anomaly.isEmpty()) continue;
                    if (a.length > 1) { if (z.id.equals(a[1])) best = z; continue; }
                    if (!z.world.equals(g.getWorld().getName())) continue;
                    double d = z.centre(g.getWorld()).distanceSquared(g.getLocation());
                    if (d < bd) { bd = d; best = z; }
                }
                if (best == null) return "No such anomaly.";
                pl.zones.remove(best);
                return "removed " + best.id + ": " + best.describe();
            }
            case "artifact" -> {
                if (a.length < 2) return "/anomaly artifact <id> [name]: lays an artifact at its heart (its kind's by default)";
                for (Zones.Zone z : pl.zones.list)
                    if (z.id.equals(a[1]) && !z.anomaly.isEmpty()) {
                        String n = a.length > 2 ? String.join(" ", Arrays.copyOfRange(a, 2, a.length)) : Anomalies.KINDS.get(z.anomaly).artifact();
                        if (!Anomalies.ARTIFACTS.containsKey(n)) return "No artifact " + n + ".";
                        z.artifact = n;
                        pl.zones.save();
                        return "the " + n + " lies at the heart of anomaly " + z.id;
                    }
                return "No anomaly " + a[1] + ".";
            }
            case "give" -> {
                if (a.length < 2) return "/anomaly give nut [count] | detector <1-4> | <artifact>";
                if (a[1].equalsIgnoreCase("nut")) { int n = a.length > 2 ? Integer.parseInt(a[2]) : 10; Items.give(g, "gear:Nut", n); return n + " nuts"; }
                if (a[1].equalsIgnoreCase("detector")) { int t = a.length > 2 ? Math.max(1, Math.min(4, Integer.parseInt(a[2]))) : 1; Items.give(g, "gear:Anomaly detector " + Rules.roman(t), 1); return "an anomaly detector, Tier " + Rules.roman(t); }
                String n = String.join(" ", Arrays.copyOfRange(a, 1, a.length));
                for (String x : Anomalies.ARTIFACTS.keySet()) if (x.equalsIgnoreCase(n)) { Items.give(g, "artifact:" + x, 1); return "the " + x + ": " + Anomalies.ARTIFACTS.get(x).text(); }
                return "No artifact " + n + ".";
            }
            default -> { return "/anomaly kinds | place <kind> [radius] | field [radius] [count] | list | remove [id] | artifact <id> [name] | give <item> [count]"; }
        }
    }

    /** /blackrain (GMs): warn · start [rounds] [warning seconds] · stop (09c §5, round 9: the surge as the Black Rain; round 10: the
     *  storm comes after a warning, a minute by default, 0 = at once). */
    static String rainCommand(Player g, String[] a) {
        if (!g.hasPermission("falloutak.gm")) return "GMs only.";
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        try {
            return switch (sub) {
                case "warn" -> pl.anomalies.warn(g.getWorld());
                case "start" -> pl.anomalies.start(g.getWorld(), a.length > 1 ? Integer.parseInt(a[1]) : Anomalies.RAIN_ROUNDS,
                        a.length > 2 ? Integer.parseInt(a[2]) : Anomalies.WARN_SECONDS);
                case "stop" -> pl.anomalies.stop();
                default -> "/blackrain warn | start [rounds] [warning seconds, 0 = at once] | stop";
            };
        } catch (NumberFormatException e) {
            return "/blackrain start [rounds] [warning seconds]: whole numbers";
        }
    }

    /** Picks a gas, then its strength. */
    static void zoneGas(Player g, BiConsumer<String, Integer> then) {
        pick(g, "The gas", "Each round inside unprotected (03b §3c).", new ArrayList<>(Rules.GASES.keySet()), Hud::cap, n -> Rules.GASES.get(n).where() + ": " + Rules.GASES.get(n).text(),
                (x, gas) -> pick(x, Hud.cap(gas) + ": the strength", "× the row.", List.of("I", "II", "III"), s -> "Strength " + s, s -> "× " + strengthOf(s),
                        (y, s) -> then.accept(gas, strengthOf(s)), Menus::zonesMenu), Menus::zonesMenu);
    }

    /** Picks how long a zone lasts: for good, or a number of game rounds (a round = 6 s). */
    static void zoneTime(Player g, Consumer<Integer> then) {
        pick(g, "How long", "Game time: a fight's rounds and Pass time for everyone count it down.", List.of("-1", "5", "10", "100", "600"),
                r -> switch (r) { case "-1" -> "for good (until removed)"; case "5" -> "5 rounds (30 s)"; case "10" -> "a minute"; case "100" -> "10 minutes"; default -> "an hour"; },
                r -> r.equals("-1") ? "a factory hall, an anomaly" : r + " rounds", (x, r) -> then.accept(Integer.parseInt(r)), Menus::zonesMenu);
    }

    /** The ears (03b §3c, RULED round 21): a blast, a flashbang, lightning, a dive, ringing, a lasting loss; heal. */
    static void earsMenu(Player g, Who o) {
        Character c = o.c();
        List<Ui.Btn> b = new ArrayList<>();
        Consumer<Player> back = x -> earsMenu(x, o);
        BiConsumer<Player, String> done = (x, r) -> { String s = r.isEmpty() ? "no harm" : r; say(x, o.name() + ": " + s); o.tell(s, NamedTextColor.GOLD); earsMenu(x, o); };
        b.add(btn("A blast", "an eardrum roll 50 % inside its damage radius, 15 % to twice it, ringing 1d6 h to three times", x -> pick(x, "A blast · " + o.name(), "How far?",
                List.of("1", "2", "3"), s -> switch (s) { case "1" -> "inside its radius"; case "2" -> "to twice its radius"; default -> "to three times"; },
                s -> switch (s) { case "1" -> "eardrums 50 %, ringing"; case "2" -> "eardrums 15 %, ringing"; default -> "ringing"; },
                (y, s) -> done.accept(y, c.blast(Integer.parseInt(s), 1, pl.rnd)), back)));
        b.add(btn("A flashbang", "ringing within 5 hexes, an eardrum roll 15 % within 2", x -> pick(x, "A flashbang · " + o.name(), "How far?",
                List.of("2", "5"), s -> s.equals("2") ? "within 2 hexes" : "3 to 5 hexes", s -> s.equals("2") ? "eardrums 15 %, ringing" : "ringing",
                (y, s) -> done.accept(y, c.flashbang(Integer.parseInt(s), pl.rnd)), back)));
        b.add(btn("Lightning close by", "an eardrum roll at 60 %", x -> done.accept(x, c.eardrumRoll(60, pl.rnd))));
        b.add(btn("A dive below 3 m", c.breathGene() ? "a Breath gene: no harm" : "10 %: one ear", x -> done.accept(x, c.breathGene() ? "" : c.oneEarRoll(10, pl.rnd))));
        b.add(btn("A gunfight indoors", "ringing for an hour (no ear protection)", x -> done.accept(x, c.ring(1))));
        b.add(btn("Meningitis or a streptomycin course", "5 %: one ear's lasting loss (P −1)", x -> done.accept(x, c.lastingRoll(5, pl.rnd))));
        b.add(btn("Heal the ears", "no ringing, both eardrums whole, no lasting loss", x -> { c.healEars(); done.accept(x, "the ears are whole"); }));
        String now = c.deafProof() ? "never deafened (the Audio Implant)" : (c.deaf() ? "DEAF" : c.burstEars() == 1 ? "one burst eardrum" : c.ringing() ? "ringing" : "hearing well")
                + (c.lastingEarLoss ? " · a lasting loss" : "") + " · P −" + c.hearingLoss() + (c.earProtection() > 0 ? " · ears protected" : "");
        menu(g, "Ears · " + o.name(), List.of(line(now, NamedTextColor.WHITE)), b, x -> gmOn(x, o));
    }

    /** Power Armour Training (05c §3c, RULED 11-18): taught by anyone trained (10 hours), or known from before; wearing the suit counts by itself. */
    static void paMenu(Player g, Who o) {
        Character c = o.c();
        List<Ui.Btn> b = new ArrayList<>();
        BiConsumer<Player, String> done = (x, r) -> { say(x, o.name() + ": " + r); o.tell(r, NamedTextColor.GOLD); paMenu(x, o); };
        if (!c.paTrained) {
            b.add(btn("Taught for 10 hours", "by anyone trained: 10 hours teach it [EXAMPLE]", x -> done.accept(x, c.paTaught(10))));
            b.add(btn("Taught for 1 hour", "one hour of a lesson (= 10 hours worn)", x -> done.accept(x, c.paTaught(1))));
            b.add(btn("Known from before", "a soldier's past: trained at once", x -> { c.paTrained = true; done.accept(x, "Power Armour Training known"); }));
        } else b.add(btn("Take it away", "set by mistake", x -> { c.paTrained = false; c.paHours = 0; done.accept(x, "Power Armour Training removed"); }));
        menu(g, "Power Armour Training · " + o.name(), List.of(line(c.paText(), NamedTextColor.WHITE),
                line("Untrained, a power armour is dead weight: no Strength, no cooling, its full weight to the class.", NamedTextColor.GRAY)), b, x -> gmOn(x, o));
    }

    /** The heart's causes with HP kept (03b §3c, RULED): a blow to the chest, an electric weapon's crit, an electric hazard. */
    static void heartMenu(Player g, Who o) {
        Character c = o.c();
        List<Ui.Btn> b = new ArrayList<>();
        Consumer<Player> back = x -> heartMenu(x, o);
        BiConsumer<Player, String> done = (x, r) -> { say(x, o.name() + ": " + r); o.tell(r, NamedTextColor.RED); gmOn(x, o); };
        b.add(btn("A blow to the chest", "the torso's highest crit of a blunt weapon or a fist", x -> done.accept(x, c.stopHeart("a blow to the chest", true, pl.rnd))));
        b.add(btn("An electric weapon's crit", "any crit to the torso of an electric weapon", x -> done.accept(x, c.stopHeart("electricity", true, pl.rnd))));
        b.add(btn("An electric hazard", "a live cable, lightning, the lightning anomaly: its damage; at 10 or more an Endurance roll (d10 ≤ E)",
                x -> ask(x, "Electric hazard · " + o.name(), "", List.of(Ui.In.numberRange("n", Component.text("Damage"), 1, 50).step(1f).initial(10f).width(300).build()),
                        form("Hit", "apply", (v, y) -> done.accept(y, c.electricHazard(Math.round(v.getFloat("n")), pl.rnd))), back)));
        menu(g, "Heart · " + o.name(), List.of(line(c.hasPump() ? "an Artificial Heart: it ignores electricity, a chest blow, E 0 and the cold" : "a natural heart", NamedTextColor.WHITE),
                line("Blood 0, Oxygen 0 (after E minutes), E 0 and the cold's or heat's end stop it by themselves.", NamedTextColor.GRAY)), b, x -> gmOn(x, o));
    }

    /**
     * Survival values (Marek 11-17: *"how do I change pain and other survival values?"*): one slider a value, from 0 to its maximum,
     * starting where it is, the stage it is in now named on it. Water, food, sleep and Blood fall as they run out; poison, cold, heat
     * and Pain rise. One button fills the first three and clears cold and heat.
     */
    static void survivalForm(Player g, Who o) {
        Character c = o.c();
        List<Ui.In> in = new ArrayList<>();
        for (Rules.Stat st : SETTABLE) {
            double max = c.max(st), v = Math.max(0, Math.min(max, c.survival.getOrDefault(st, 0.0)));
            String stage = c.stageOf(st, st == Rules.Stat.PAIN ? c.painFelt() : v, max);
            String n = st.name().charAt(0) + st.name().substring(1).toLowerCase(Locale.ROOT);
            in.add(Ui.In.numberRange(st.name(), Component.text(n + " (max " + Math.round(max) + (stage == null ? ", fine" : ", now " + stage) + ")"),
                    0, (float) Math.max(1, Math.round(max))).step(1f).initial((float) Math.round(v)).width(300).build());
        }
        List<Ui.Btn> b = new ArrayList<>();
        b.add(form("Apply", "set every value to its slider", (v, y) -> {
            for (Rules.Stat st : SETTABLE) { Float f = v.getFloat(st.name()); if (f != null) c.survival.put(st, Math.max(0, Math.min(c.max(st), (double) f))); }
            String r = "survival values set by the GM";
            say(y, o.name() + ": " + r);
            o.tell(r, NamedTextColor.YELLOW);
            gmOn(y, o);
        }));
        b.add(btn("Fed, watered, rested", "water, food and sleep to the maximum, cold and heat to 0", y -> {
            for (Rules.Stat st : List.of(Rules.Stat.WATER, Rules.Stat.FOOD, Rules.Stat.SLEEP)) c.survival.put(st, c.max(st));
            c.survival.put(Rules.Stat.HYPOTHERMIA, 0.0);
            c.survival.put(Rules.Stat.HYPERTHERMIA, 0.0);
            survivalForm(y, o);
        }));
        menu(g, "Survival · " + o.name(), List.of(line("Water, food, sleep and Blood: high is good. Poison, cold, heat and Pain: 0 is good. "
                + "Rads: Add rads.", NamedTextColor.GRAY)), in, b, x -> gmOn(x, o), 2);
    }

    /** His effects but the implants and limbs (their own buttons): each removable (11-17, Marek: "other effects"). */
    static void effectsList(Player g, Who o) {
        Character c = o.c();
        List<Ui.Btn> b = new ArrayList<>();
        for (Character.Effect e : new ArrayList<>(c.effects)) {
            if (e.source().equals("implant") || e.source().equals("prosthesis")) continue;
            b.add(btn("Remove " + e.name(), (e.source().isEmpty() ? "" : e.source() + ": ") + SheetView.effText(e)
                    + (e.permanent() ? "" : " (" + SheetView.hours(e.hoursLeft()) + " left)"), y -> {
                c.effects.remove(e);
                say(y, o.name() + ": " + e.name() + " removed");
                effectsList(y, o);
            }));
        }
        List<Component> lines = new ArrayList<>();
        lines.add(line(b.isEmpty() ? "No effects (implants and limbs have their own buttons)." : "Click one to remove it; hover for what it does.", NamedTextColor.GRAY));
        for (String st : c.stages()) lines.add(line("Now: " + st + " (a survival stage: change its value under Survival values)", NamedTextColor.YELLOW));
        menu(g, "Effects · " + o.name(), lines, List.of(), b, x -> gmOn(x, o), 2);
    }

    /**
     * Add rads (Marek 11-16: individual units, 100 at most a time): a dose cut by his Rad resistance as the hourly rads are (a tick
     * gives it whole), or taken away. 150 minor, 300 advanced, 450 critical, 600 deadly, 1000 fatal.
     */
    static void addRads(Player g, Who o) {
        Character c = o.c();
        double held = c.survival.getOrDefault(Rules.Stat.RADS, 0.0);
        double res = Math.max(0, Math.min(100, c.resistances()[1]));
        ask(g, "Rads for " + o.name(), "He holds " + Math.round(held) + " rads; his Rad resistance " + Math.round(res)
                        + " % cuts a dose. 150 minor, 300 advanced, 450 critical, 600 deadly, 1000 fatal.",
                List.of(Ui.In.numberRange("r", Component.text("Rads"), 1, 100).step(1f).initial(10f).width(300).build(),
                        Ui.In.bool("whole", Component.text("Ignore his Rad resistance")).initial(false).build(),
                        Ui.In.bool("out", Component.text("Take them away instead")).initial(false).build()),
                form("Apply", "apply the dose", (v, y) -> {
                    int dose = Math.round(v.getFloat("r"));
                    boolean out = Boolean.TRUE.equals(v.getBoolean("out"));
                    double got = out || Boolean.TRUE.equals(v.getBoolean("whole")) ? dose : dose * (1 - res / 100);
                    double now = Math.max(0, Math.min(2000, c.survival.getOrDefault(Rules.Stat.RADS, 0.0) + (out ? -got : got)));
                    double was = c.survival.getOrDefault(Rules.Stat.RADS, 0.0);
                    c.survival.put(Rules.Stat.RADS, now);
                    c.radiationRolls(was, now, pl.rnd);   // 03d §5: a stage crossed rolls for every therapy
                    String news = c.takeNews();
                    String r = (out ? "−" : "+") + Txt.f(got) + " rads (now " + Math.round(now) + ")" + (news == null ? "" : "; " + news);
                    say(y, o.name() + ": " + r);
                    o.tell(r, NamedTextColor.YELLOW);
                    addRads(y, o);
                }), x -> gmOn(x, o));
    }

    /** Every item the GM can give: chems, implants at each tier, gear, supplies. */
    static List<String> allItems() {
        List<String> ids = new ArrayList<>();
        for (String ch : Data.CHEMS.keySet()) ids.add("chem:" + ch);
        for (Data.Implant im : Data.IMPLANTS.values()) for (int t : im.tiers().descendingKeySet()) ids.add("implant:" + im.name() + (t == 4 ? "" : "|" + t));
        for (Data.Gene g : Data.GENES.values()) if (!g.slot().equals("ORGAN")) ids.add("therapy:" + g.name());
        if (pl.base != null) for (String n : pl.base.genePool.keySet()) ids.add("therapy:" + n);   // 03b §7a: the genes made in the game
        for (String s : Data.SETS.keySet()) ids.add("geneset:" + s);
        for (String gr : Items.GEAR) ids.add("gear:" + gr);
        for (Data.Armour a : Data.ARMOUR.values()) for (String s : a.pieces()) ids.add(Items.armourId(a.name(), s));   // 05c
        for (String s : Items.SUPPLIES) ids.add("supplies:" + s);
        return ids;
    }

    /** Give, step 1 (Marek 11-16: *"needs some way to select the item from list. Best would be with filter."*): a filter and a kind. */
    static void giveStart(Player g, Player to) {
        List<Ui.Btn> b = new ArrayList<>();
        for (String k : List.of("everything", "chem", "implant", "gear", "supplies"))
            b.add(form(k.equals("everything") ? "Search everything" : "Search " + k + (k.equals("supplies") ? "" : "s"), "list the matches",
                    (v, x) -> giveList(x, to, v.getText("f"), k)));
        menu(g, "Give " + (to == null ? "every player" : to.getName()), List.of(line("Type part of a name (or leave it empty), then search a kind.", NamedTextColor.GRAY)),
                List.of(Ui.In.text("f", Component.text("Filter")).initial("").maxLength(32).build()), b,
                to == null ? Menus::gmAll : x -> gmOn(x, of(to)), 2);
    }

    /** Give, step 2: the matches as buttons. */
    static void giveList(Player g, Player to, String filter, String kind) {
        String f = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        List<Ui.Btn> b = new ArrayList<>();
        for (String id : allItems()) {
            if (!kind.equals("everything") && !id.startsWith(kind + ":")) continue;
            String title = Items.title(id);
            if (!f.isEmpty() && !title.toLowerCase(Locale.ROOT).contains(f)) continue;
            b.add(btn(title, Items.describe(id), x -> giveCount(x, to, id)));
        }
        menu(g, "Give: " + b.size() + " found", List.of(line(b.isEmpty() ? "Nothing matches: Back to change the filter." : "Pick one.", NamedTextColor.GRAY)), b, x -> giveStart(x, to));
    }

    /** Give, step 3: how many, a slider (Marek 11-16). */
    static void giveCount(Player g, Player to, String id) {
        ask(g, "Give " + Items.title(id), Items.describe(id), List.of(Ui.In.numberRange("n", Component.text("How many"), 1, 20).step(1f).initial(1f).width(300).build()),
                form("Give", "into the pack", (v, x) -> {
                    int n = Math.round(v.getFloat("n"));
                    for (Player p : to == null ? new ArrayList<>(Bukkit.getOnlinePlayers()) : List.of(to)) Items.give(p, id, n);
                    say(x, n + " × " + Items.title(id) + " given to " + (to == null ? "every player" : to.getName()) + ".");
                    if (to == null) gmAll(x); else gmOn(x, of(to));
                }), x -> giveStart(x, to));
    }

    /** Fit, step 1 (Marek 11-16: *"Implants seem to ignore tier selection and the third field makes no sense"*): the implant. */
    static void fitStart(Player g, Who o) {
        List<Ui.Btn> b = new ArrayList<>();
        for (Data.Implant im : Data.IMPLANTS.values()) {
            long used = o.c().implants().stream().filter(e -> e.slot().startsWith("implant:" + im.slot() + "#")).count();
            boolean full = used >= o.c().slots(im.slot());
            b.add(btn((full ? "(full) " : "") + im.name(), "the " + im.slot() + " slot" + (full ? " is full: remove one first" : "") + "; Tier " + Rules.roman(im.maxTier()) + ": " + Part3.numbersAt(im, im.maxTier())
                    + (im.needs().isEmpty() ? "" : "; needs the " + im.needs()), x -> fitTier(x, o, im)));
        }
        menu(g, "Fit into " + o.name(), List.of(line("Pick the implant, then its tier (directly, no operation, no roll).", NamedTextColor.GRAY)), b, x -> gmOn(x, o));
    }

    /** Fit, step 2: the tier, each with its numbers. */
    static void fitTier(Player g, Who o, Data.Implant im) {
        List<Ui.Btn> b = new ArrayList<>();
        for (int t : im.tiers().descendingKeySet()) {
            int tier = t;
            String n = Rules.roman(tier);
            b.add(btn("Tier " + n + ": " + Part3.numbersAt(im, tier), Rules.tierText(tier),
                    x -> { if (im.skillChoice() != 0) fitSkill(x, o, im, tier); else doFit(x, o, im, tier, null); }));
        }
        menu(g, im.name() + " into " + o.name(), List.of(line(im.notes(), NamedTextColor.GRAY)), b, x -> fitStart(x, o));
    }

    /** Fit, step 3 (the memory chip only): its skill. */
    static void fitSkill(Player g, Who o, Data.Implant im, int tier) {
        List<Ui.Btn> b = new ArrayList<>();
        for (Rules.Skill sk : Rules.SKILLS) b.add(btn(sk.name(), "+" + Rules.implantTier(im.skillChoice(), tier) + " %", x -> doFit(x, o, im, tier, sk.name())));
        menu(g, im.name() + ": which skill", List.of(), b, x -> fitTier(x, o, im));
    }

    static void doFit(Player g, Who o, Data.Implant im, int tier, String skill) {
        String why = o.c().fit(im, tier, skill);
        if (why != null) say(g, "! " + why);
        else {
            Character.Effect e = o.c().implants().get(o.c().implants().size() - 1);
            String r = e.name() + " fitted: " + SheetView.effText(e);
            say(g, r);
            o.tell(r, NamedTextColor.YELLOW);
        }
        gmOn(g, o);
    }

    // ------------------------------------------------------------------ gene therapies (03d, RULED 11-17)
    /** The GM's therapy menu for one character: his therapies (remove, pump), give a gene, give a shop set, refill the pump. */
    static void therapies(Player g, Who o) {
        Character c = o.c();
        List<Component> lines = new ArrayList<>();
        List<Ui.Btn> b = new ArrayList<>();
        lines.add(line("A therapy lays one gene over the template's own in its slot for a while (03d): the tissue's years × the tier; "
                + "a course (a week to two months, with its fever) before it acts; half its numbers in its last quarter.", NamedTextColor.GRAY));
        b.add(btn("Give a gene", "the slot, the gene, then who gives it and where (03d §1-§6)", x -> therapySlot(x, o)));
        if (!c.recipes().isEmpty()) b.add(btn("Take a gene sample", sampleText(c), x -> { say(x, sample(o)); therapies(x, o); }));
        b.add(btn("Give a shop set", "Heavy Gravity, Witcher, Chlorofil, Tiefling: one 15-year course (03d §9)", x -> pick(x, "Gene set for " + o.name(),
                "One course, one roll at its highest tier; an adult gets no shape or organ genes.", new ArrayList<>(Data.SETS.keySet()), n -> n,
                n -> Data.SETS.get(n).shop() + "\ngenes: " + String.join(", ", Data.SETS.get(n).genes()), (y, n) -> therapyCourse(y, o, null, Data.SETS.get(n)),
                y -> therapies(y, o))));
        if (c.pumpSlots() > 0) b.add(btn("Refill the gene pump", "a refill a year at a T2 course's price (03d §10); "
                + (c.pumpFed() ? "fed, " + Math.round((8760 - (c.now() - c.pumpRefilled)) / 24) + " days left" : "EMPTY: the pumped therapies fade"),
                x -> { c.pumpRefilled = c.now(); say(x, o.name() + ": the gene pump refilled for a year."); therapies(x, o); }));
        for (Character.Therapy t : new ArrayList<>(c.therapies)) {
            lines.add(line(Part3.therapyText(c, t), NamedTextColor.WHITE));
            b.add(btn("Remove " + t.gene, "gone at once (the GM's)", x -> { c.therapies.remove(t); therapies(x, o); }));
            if (c.pumpSlots() > 0 && t.hours > 0)
                b.add(btn((t.pumped ? "Unpump " : "Pump ") + t.gene, "the gene pump holds it: no fading while fed (03d §10; T III one, T IV two)",
                        x -> { t.pumped = !t.pumped; therapies(x, o); }));
        }
        if (c.therapies.isEmpty()) lines.add(line("No therapies.", NamedTextColor.GRAY));
        menu(g, "Gene therapy: " + o.name(), lines, b, x -> gmOn(x, o));
    }

    /** What a sample takes (03b §7a): the body's changed genes, and which the pool lacks. */
    static String sampleText(Character c) {
        List<String> l = new ArrayList<>();
        for (Data.Recipe r : c.recipes()) {
            Data.Gene g = r.build();
            if (g != null) l.add(g.name() + (pl.base.knows(r) ? " (known)" : ""));
        }
        return "blood or a biopsy: his changed genes into the base's gene pool, for gene therapy (03b §7a): " + String.join(", ", l);
    }

    /** A sample of a body's changed genes into the base's pool (03b §7a; 09b round 12). */
    static String sample(Who o) {
        if (o == null) return "! nobody";
        List<String> added = pl.base.sample(o.c().recipes());
        return added.isEmpty() ? o.name() + "'s genes are all in the pool already"
                : o.name() + "'s sample: " + String.join(", ", added) + " into the gene pool (gene therapy gives them now, 03d)";
    }

    /** The GM undoes a mutation's change to a gene (03b §7a). */
    static void mutatedList(Player g, Who o) {
        Character c = o.c();
        List<String> idx = new ArrayList<>();
        for (int i = 0; i < c.mutated.size(); i++) idx.add(String.valueOf(i));
        pick(g, "Mutated genes: " + o.name(), "Each a mutation that changed the gene in its slot, in order. Undo one: the slot loses it.", idx,
                i -> { Character.Mutated m = c.mutated.get(Integer.parseInt(i)); return m.slot + ": " + m.text(); },
                i -> "undo it", (x, i) -> { say(x, o.name() + ": " + c.undoMutation(Integer.parseInt(i))); gmOn(x, o); }, x -> gmOn(x, o));
    }

    /** Give a gene, step 1: the slot (the ORGAN genes are grafts, never therapies). */
    static void therapySlot(Player g, Who o) {
        List<String> slots = new ArrayList<>();
        for (Data.Gene x : Data.GENES.values()) if (!x.slot().equals("ORGAN") && !slots.contains(x.slot())) slots.add(x.slot());
        pick(g, "Therapy for " + o.name(), "Which slot? (organs are grafts, 03e; a shape gene only in puberty)", slots, s -> {
                    Character.Therapy t = null;
                    for (Character.Therapy x : o.c().therapies) if (x.g() != null && x.g().slot().equals(s)) t = x;
                    Data.Gene own = null;
                    for (Data.Gene x : o.c().baseTpl().genes()) if (x.slot().equals(s)) own = x;
                    return s + (t != null ? " (therapy: " + t.gene + ")" : own != null ? " (" + own.name() + ")" : "");
                },
                s -> {
                    StringBuilder sb = new StringBuilder();
                    for (Data.Gene x : Data.GENES.values()) if (x.slot().equals(s)) sb.append(sb.isEmpty() ? "" : ", ").append(x.name());
                    for (Data.Gene x : pl.base.pooled(s)) sb.append(sb.isEmpty() ? "" : ", ").append(x.name()).append(" (the pool)");
                    return sb.toString();
                }, (x, s) -> therapyGene(x, o, s), x -> therapies(x, o));
    }

    /** Give a gene, step 2: the gene. */
    static void therapyGene(Player g, Who o, String slot) {
        List<String> names = new ArrayList<>();
        for (Data.Gene x : Data.GENES.values()) if (x.slot().equals(slot)) names.add(x.name());
        for (Data.Gene x : pl.base.pooled(slot)) names.add(x.name());   // 03b §7a: made in the game, sampled
        pick(g, slot + " for " + o.name(), "The gene: its tier sets the course and its years (" + Character.tissueYears(slot)
                        + " years × T1 1, T2 1.5, T3 2, T4 2.5).", names,
                n -> { Data.Gene x = Data.gene(n); return n + " (" + x.tier() + (x.adult() ? "" : ", puberty only") + ")"; },
                n -> Forms.geneTip(Data.gene(n)) + "\nlasts " + SheetView.hours(Character.therapyHours(Data.gene(n))),
                (x, n) -> therapyCourse(x, o, Data.gene(n), null), x -> therapySlot(x, o));
    }

    /** Give a gene or a set, step 3: who gives it and where; then the rolls (03d §6, §4). */
    static void therapyCourse(Player g, Who o, Data.Gene gene, Data.GeneSet set) {
        Character c = o.c();
        String what = gene != null ? gene.name() + " (" + gene.tier() + ")" : "the " + set.name() + " set";
        Character.Therapy old = gene == null ? null : c.therapyIn(gene.key());
        String text = "The course's chance = the therapist's Biology × the place × the tier (T1 1.2, T2 1, T3 0.8, T4 0.6) × 1.1 a point of ease, "
                + "at most 98 %." + (old != null ? " Over his " + old.gene + " therapy: the conflict roll d6 + Biology / 30 + ease (03d §4)." : "");
        ask(g, "Give " + what + " to " + o.name(), text, List.of(
                        Ui.In.numberRange("bio", Component.text("The therapist's Biology"), 0, 200).step(1f).initial(80f).width(300).build(),
                        choice("place", "The place", List.of("a clinic (x1)", "a pre-war lab (x1.5)", "a field kit (x0.5)")),
                        Ui.In.bool("puberty", Component.text("In puberty: for good, the shape genes too (03d §8)")).initial(false).build(),
                        Ui.In.bool("noroll", Component.text("No rolls (the GM's own)")).initial(false).build()),
                form("Give", "start the course", (v, x) -> {
                    double bio = Math.round(v.getFloat("bio"));
                    String p = v.getText("place");
                    double place = p.startsWith("a pre") ? 1.5 : p.startsWith("a field") ? 0.5 : 1;
                    boolean puberty = Boolean.TRUE.equals(v.getBoolean("puberty")), roll = !Boolean.TRUE.equals(v.getBoolean("noroll"));
                    String r = gene != null ? c.giveTherapy(gene, bio, place, puberty, roll, pl.rnd) : c.giveSet(set, bio, place, puberty, roll, pl.rnd);
                    say(x, o.name() + ": " + r);
                    if (!r.startsWith("!")) o.tell("Gene therapy: " + r, NamedTextColor.LIGHT_PURPLE);
                    therapies(x, o);
                }), x -> therapies(x, o));
    }

    /** A medic gives a carried therapy or gene set to another from his Actions (Marek 11-18: without the GM): the place, his consent, the rolls. */
    static void therapyGive(Player p, Who o, String item) {
        if (o == null) return;
        Character me = pl.character(p), c = o.c();
        boolean set = item.startsWith("geneset:");
        String what = item.substring(item.indexOf(':') + 1);
        Data.Gene gene = set ? null : Data.gene(what);
        Data.GeneSet gs = set ? Data.SETS.get(what) : null;
        if (gene == null && gs == null) return;
        if (gene != null) { String bar = c.therapyBar(gene, false); if (bar != null) { say(p, "! " + bar); return; } }
        double bio = me.skill("Biology");
        pick(p, "Gene therapy: the place", "Be honest: the GM sees it. The chance = your Biology " + Math.round(bio) + " × the place × the tier.",
                List.of("a clinic (x1)", "a pre-war lab (x1.5)", "a field kit (x0.5)"), s -> s,
                s -> "× " + (s.startsWith("a pre") ? "1.5" : s.startsWith("a field") ? "0.5" : "1"),
                (x, s) -> {
                    double place = s.startsWith("a pre") ? 1.5 : s.startsWith("a field") ? 0.5 : 1;
                    consent(o, "Gene therapy from " + p.getName(), (set ? "The " + what + " set" : what) + ": a course with its fever, then it lasts years (03d).", () -> {
                        if (!Items.take(x, item)) { say(x, "! it is gone"); return; }
                        String r = set ? c.giveSet(gs, bio, place, false, true, pl.rnd) : c.giveTherapy(gene, bio, place, false, true, pl.rnd);
                        say(x, o.name() + ": " + r);
                        o.tell(p.getName() + " gives you a gene therapy: " + r, NamedTextColor.LIGHT_PURPLE);
                        tellGm(p.getName() + " → " + o.name() + ": " + r);
                    });
                }, x -> on(x, whoOf(o.entity())));
    }
}
