package cz.lollywood.falloutak;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static cz.lollywood.falloutak.Txt.*;

/**
 * /sheet as a TEXT screen (a dialog, like the level-up form), Marek 11-14: *"The sheet in inventory is quite confusing. Could it be
 * changed with custom sheet like the skill up selection?"*; on 0.2.0: *"It is aligned to centre, it would be better if the
 * important parts were aligned, like in sheet."*; on 0.3.0: *"The sheet now looks a lot better, structure could still be better
 * though. Would it be possible to show the gray details only on hover?"* So: two PANELS side by side like a paper sheet, each a
 * table of name and value (numbers right-aligned), and how each value is computed plus the workbook's note in a TOOLTIP on its row
 * ({@link Txt}). The pages are buttons; the old chest screen stays as /sheet icons.
 */
public final class SheetView {
    private SheetView() {}

    /** The dialog's text width; a line is padded to {@link #W}; two panels of {@link #P} with a gap of {@link #G}. */
    private static final int WIDTH = 440, W = WIDTH - 4, G = 12, P = (W - G) / 2;
    private static final int LABEL = 100;

    private static final TextColor HOW = SheetColors.HOW;
    private static final NamedTextColor WHITE = NamedTextColor.WHITE;
    private static final Table.Cell NONE = c("", WHITE);

    /** The page as blocks of lines (no server needed: the tests render them). */
    static List<List<Component>> page(Character c, SheetGui.Page page) {
        List<List<Component>> body = new ArrayList<>();
        body.add(new Table(W).minWidths(LABEL).row(b(c.name, NamedTextColor.GOLD),
                c(c.template + " · level " + c.level + " · HP " + c.hp + "/" + c.hpMax() + " · AP " + c.apMax() + " · skill points "
                        + c.unspentSkillPoints, WHITE)).render());
        switch (page) {
            case OVERVIEW -> overview(c, body);
            case SKILLS -> skills(c, body);
            case WEAPONS -> weapons(c, body);
            case TRAITS -> traits(c, body);
            case BODY -> bodyPage(c, body);
            case SURVIVAL -> survival(c, body);
            case COMBAT -> combat(c, body);
            case ACHIEVEMENTS -> body.add(new Table(W).title("Achievements", null).note("The player's achievements across games (the wiki's "
                    + "production/lide, _notes/canon/achievements.yaml): the automatic ones applied by the plugin, the active ones with a Use button "
                    + "here (Marek 11-12). EXAMPLE page: filled when the plugin's data is built.", HOW).render());
        }
        body.add(new Table(W).note("Hover a row for how it is computed and the workbook's note.", HOW).render());
        return body;
    }

    public static void open(Player p, Character c, SheetGui.Page page) {
        List<Component> body = new ArrayList<>();
        Icons.VIEWER_HAS_PACK.set(Icons.hasPack(p, c));
        try {
            for (List<Component> lines : page(c, page)) body.add(Component.join(JoinConfiguration.newlines(), lines));
        } finally {
            Icons.VIEWER_HAS_PACK.remove();
        }
        List<Ui.Btn> buttons = new ArrayList<>();
        for (SheetGui.Page pg : SheetGui.Page.values())
            buttons.add(button((pg == page ? "» " : "") + title(pg), "open " + title(pg), 100, pp -> open(pp, c, pg)));
        if (page == SheetGui.Page.SKILLS)
            buttons.add(button("Spend " + c.unspentSkillPoints + " points", "the level-up form", 100, pp -> Forms.spend(pp, null, c)));
        if (page == SheetGui.Page.TRAITS && c.perksDue() > 0)
            buttons.add(button("Pick a perk (" + c.perksDue() + ")", "the perks whose gate you meet", 100, pp -> Menus.perkPick(pp, c, null)));
        Ui.Btn close = button("Close", "close the sheet", 100, pp -> {});
        Ui.show(new Ui.Page(p, c.name + " · " + title(page), body, WIDTH, List.of(), Ui.Type.MULTI, buttons, close, 4, true));
    }

    static String title(SheetGui.Page p) {
        return switch (p) {
            case OVERVIEW -> "Overview"; case SKILLS -> "Skills"; case WEAPONS -> "Weapon skills"; case TRAITS -> "Traits & perks";
            case BODY -> "Body"; case SURVIVAL -> "Survival"; case COMBAT -> "Combat"; case ACHIEVEMENTS -> "Achievements";
        };
    }

    private static Ui.Btn button(String label, String tip, int width, java.util.function.Consumer<Player> then) {
        return new Ui.Btn(label, tip, width, (v, p) -> then.accept(p));
    }

    private static String f(double v) { return SheetGui.f(v); }
    private static String sign(int v) { return (v > 0 ? "+" : "−") + Math.abs(v); }
    /** A panel: name · value (right-aligned) · a short unit or mark. */
    private static Table panel(String head) { return new Table(P).minWidths(LABEL, 40).gap(6).title(head, null); }

    // ------------------------------------------------------------------ pages
    private static void overview(Character c, List<List<Component>> body) {
        int[] now = c.attrs(false);
        double[] sk = c.skills();
        int floor = Rules.deathFloor(now);
        Table v = panel("Vitals")
                .row(tip("S " + now[Rules.S] + " + 2 × E " + now[Rules.E] + " + template " + c.hpBonus() + (c.has("Medical Marvel") ? " + Medical Marvel 5" : "")
                                + "\ndying from 0 to " + floor + " (−Endurance), dead below", null),
                        c("HP", WHITE), r(c.hp + " / " + c.hpMax(), c.hp > 0 ? NamedTextColor.RED : NamedTextColor.DARK_RED), c(c.hp > 0 ? "" : c.hp >= floor ? "dying" : "DEAD", NamedTextColor.DARK_RED))
                .row(tip("5 + A " + now[Rules.A] + " / 2" + (c.has("Brittle Bones") ? " + 2 Brittle Bones" : ""), null),
                        c("AP", WHITE), r(String.valueOf(c.apMax()), NamedTextColor.AQUA), NONE)
                .row(tip("E / 3 + Medicine / 50 + Survival / 20, at least 1" + (c.tpl().healingRate() != 0 ? "\n+" + c.tpl().healingRate() + " " + c.template : "")
                                + (c.tpl().healingRateFed() != 0 ? "\n+" + c.tpl().healingRateFed() + " when fed (a feeding in the last 24 hours)" : "")
                                + (Production.hygieneHealing(c.unwashedDays) != 0 ? "\n−" + Production.hygieneHealing(c.unwashedDays) + " unwashed" : "")
                                + "\nnever below 0, except in Fatal Radiation Poisoning: then HP are lost every 8 hours", null),
                        c("Healing rate", WHITE), r(String.valueOf(c.healingRate()), c.healingRate() < 0 ? NamedTextColor.RED : NamedTextColor.GREEN), NONE)
                .row(tip("days since the last wash (round 9): in the base the base's average, outside your own; Dirty from 3 days "
                                + "(wound-infection rolls x1.5), Filthy from 7 (x2, the healing rate −1)" + (Production.hygiene(c.unwashedDays).isEmpty() ? ""
                                : "\n" + Production.hygiene(c.unwashedDays)), null),
                        c("Unwashed", WHITE), r(f(c.unwashedDays), c.unwashedDays >= 7 ? NamedTextColor.RED : c.unwashedDays >= 3 ? NamedTextColor.YELLOW : WHITE),
                        c(c.unwashedDays >= 7 ? "filthy" : c.unwashedDays >= 3 ? "dirty" : "days", HOW))
                .row(tip("(25 + 25 × S + Athletics) pounds × 0.5 (Fallout 2's, in kg: round 12); Small Frame 15 × S, Strong Back +50 lb (+25 kg), "
                                + "Hoarder +25 lb, the artifacts' kg\nwhat you carry = your inventory and the armour you wear (Items.weight)\nover it: to +50 % half "
                                + "speed and the needs ×1.5, to +100 % the AP halved too and ×2, more: no AP", null),
                        c("Carry", WHITE), r(f(Math.round(c.carriedKg * 10) / 10.0) + " / " + f(Math.round(c.capacityKg() * 10) / 10.0),
                                c.loadTier() > 0 ? NamedTextColor.RED : WHITE), c("kg", HOW))
                .row(tip("Luck % + Sneak / 10 %", null), c("Crit chance", WHITE), r(f(Rules.critChance(now, sk[Rules.skillIndex("Sneak")])), WHITE), c("%", HOW))
                .row(tip("2 × I + template " + c.templateSkillPoints, null),
                        c("Skill pts a level", WHITE), r(String.valueOf(c.skillRate()), NamedTextColor.YELLOW), NONE)
                .row(Icons.tip("glowstone_dust", "stat_rads", "here " + f(c.locationRads) + " rads an hour", Data.note("Character", "Rads")),
                        c("Radiation", WHITE), r(f(Math.round(c.survival.get(Rules.Stat.RADS))), NamedTextColor.YELLOW), c("rads", HOW));
        Table a = new Table(P).minWidths(LABEL - 20, 28, 28).gap(6).title("Attributes", null)
                .row(c("", HOW), r("now", HOW), r("perm.", HOW));
        for (int i = 0; i < 7; i++) {
            int perm = c.attr(i, true), cur = now[i];
            a.row(Icons.tip(Icons.ATTR[i], "attr_" + Icons.key(Rules.SPECIAL[i]), breakdown(c, i), Data.note("Character", Rules.SPECIAL[i])), c(Rules.SPECIAL[i], WHITE),
                    r(String.valueOf(cur), cur < perm ? NamedTextColor.RED : cur > perm ? NamedTextColor.GREEN : WHITE), r(String.valueOf(perm), HOW));
        }
        a.note("points " + c.pointsSpent() + " / " + c.tpl().attributePoints() + " (40 + the template's)", HOW);
        body.add(sideBySide(v, a, G));

        Table who = panel("Who")
                .row(c("Player", WHITE), c(c.player, WHITE), NONE)
                .row(tip("HP +" + c.hpBonus() + " · skill points +" + c.templateSkillPoints + " a level · " + c.tpl().perkText()
                                + "\nthe " + c.tpl().family() + " family" + (c.tpl().premium() ? " · premium (CoS " + c.tpl().cosPoints() + " points)" : ""), null),
                        c("Template", WHITE), c(c.template, NamedTextColor.GOLD), NONE)
                .row(tip("XP " + c.xp + " / " + Rules.xpForLevel(c.level + 1) + " to level " + (c.level + 1) + (c.templatePerkRate > 0 ? "\nnext perk at level "
                        + (c.level + c.templatePerkRate - c.level % c.templatePerkRate) : "\nno perks"), null), c("Level", WHITE), c(String.valueOf(c.level), WHITE), NONE)
                .row(tip("part 6: one group per player, one stat block", null), c("Group", WHITE), c(c.group.isEmpty() ? "none" : c.group, c.group.isEmpty() ? HOW : WHITE), NONE)
                .row(tip("after the quiz; spent in the point shop", null), c("CoS points", WHITE), c("later", HOW), NONE);
        Table tt = new Table(P).minWidths(LABEL, 40).gap(6).title("Traits and tags", null);
        if (c.traits.isEmpty()) tt.row(c("no traits", HOW), NONE, NONE);
        for (Character.Feat tr : c.traits) tt.row(Icons.tip("name_tag", tr.text(), tr.gate()), c(tr.name(), NamedTextColor.GOLD), c("trait", HOW), NONE);
        for (String tg : c.tags) tt.row(tip("a tagged skill grows twice as fast", null), c("◆ " + tg, NamedTextColor.GREEN), c("tagged", HOW), NONE);
        body.add(sideBySide(who, tt, G));

        Table cond = new Table(W).minWidths(LABEL + 40, 70).title("Conditions", null);
        boolean noPain = c.ignoresPain();
        int n = 0;
        for (String st : c.stages()) {
            boolean ignored = noPain && Character.isPainStage(st);
            cond.row(tip(Data.effectText(st) + (ignored ? "\nignored: Survival " + f(Math.round(c.painCheckSurvival())) + " ≥ Pain "
                            + f(Math.round(c.survival.get(Rules.Stat.PAIN))) + " (a check makes no roll)" : ""), null),
                    c(c.label(st), NamedTextColor.LIGHT_PURPLE), c(ignored ? "ignored" : "in effect", ignored ? HOW : NamedTextColor.LIGHT_PURPLE), NONE);
            n++;
        }
        for (Character.Effect e : c.effects)
            if (!e.permanent()) {
                cond.row(tip(effText(e) + (e.next() != null ? "\nthen: " + e.next() : ""), null), c(e.name(), NamedTextColor.LIGHT_PURPLE),
                        c(hours(e.hoursLeft()) + " left", HOW), NONE);
                n++;
            }
        if (c.bleeding > 0) {
            cond.row(tip("a weak wound 2, a strong one 5, +1 a further cut (Andor's numbers)\n/sheet firstaid stops it", null),
                    c("Bleeding", NamedTextColor.DARK_RED), c(f(c.bleeding) + " Blood a round", NamedTextColor.DARK_RED), NONE);
            n++;
        }
        if (n == 0) cond.row(c("none", NamedTextColor.GREEN), NONE, NONE);
        body.add(cond.render());
    }

    private static String mods(int[] m) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++) if (m[i] != 0) sb.append(sb.isEmpty() ? "" : ", ").append(sign(m[i])).append(" ").append(Rules.SPECIAL[i]);
        return sb.toString();
    }

    /** An effect's numbers as they work now (an implant's scaled by its condition). */
    static String effText(Character.Effect e) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++) { int v = e.mod(i); if (v != 0) sb.append(sb.isEmpty() ? "" : ", ").append(sign(v)).append(" ").append(Rules.SPECIAL[i]); }
        for (String k : e.other().keySet()) {
            double v = e.other(k);
            if (v == 0) continue;
            String label = switch (k) {
                case "dr" -> "DR %"; case "dt" -> "DT"; case "poison_res" -> "poison/disease res. %"; case "rad_res" -> "rad res. %";
                case "isolation" -> "isolation %"; case "hp_max" -> "HP max"; case "ap" -> "AP"; case "sequence" -> "Sequence";
                case "aimed" -> "aimed shots"; case "pain_felt" -> "Pain felt"; case "pain_gain" -> "Pain gained (share)";
                case "sleep_stage_off" -> "the sleep stage off"; case "rads_per_hour" -> "rads an hour"; case "efficiency" -> "efficiency %";
                case "senses" -> v >= 2 ? "night vision" : "the dark halved"; case "pain_floor" -> "Pain never below";
                case "heal_factor" -> "healing (share)";
                default -> k.startsWith("skill:") ? k.substring(6) + " %" : k.replace('_', ' ');
            };
            if (k.equals("senses") || k.equals("sleep_stage_off")) sb.append(sb.isEmpty() ? "" : ", ").append(label);
            else sb.append(sb.isEmpty() ? "" : ", ").append(v > 0 ? "+" : "").append(f(Math.round(v * 100) / 100.0)).append(" ").append(label);
        }
        return sb.isEmpty() ? "no numbers" : sb.toString();
    }

    /** 30 h, 6 d, 3 wk, 4 mo. */
    static String hours(double h) {
        if (h >= Character.UNTIL_TREATED / 10) return "until treated";
        if (h < 48) return f(Math.round(h * 10) / 10.0) + " h";
        if (h < 24 * 21) return f(Math.round(h / 24 * 10) / 10.0) + " d";
        if (h < 24 * 90) return f(Math.round(h / 168 * 10) / 10.0) + " wk";
        if (h < 8760 * 1.5) return f(Math.round(h / 720 * 10) / 10.0) + " mo";
        return f(Math.round(h / 8760 * 10) / 10.0) + " y";
    }

    private static String breakdown(Character c, int i) {
        int[] range = c.attrRange(i);
        StringBuilder sb = new StringBuilder(c.tpl().fixedAttr(i) ? "fixed " + c.tpl().fixed()[i] + " (" + c.template + ")" : c.base[i] + " points");
        if (!c.tpl().fixedAttr(i) && c.tpl().mods()[i] != 0) sb.append(", ").append(sign(c.tpl().mods()[i])).append(" ").append(c.template);
        if (!c.tpl().fixedAttr(i)) sb.append(" (at creation ").append(range[0]).append("-").append(range[1]).append(")");
        for (Character.Effect e : c.effects)
            if (e.mod(i) != 0) sb.append(", ").append(sign(e.mod(i))).append(" ").append(e.name()).append(e.permanent() ? "" : " (" + hours(e.hoursLeft()) + ")");
        boolean noPain = c.ignoresPain();
        for (String st : c.stages()) {
            Data.EffectRow r = Data.EFFECTS.get(st);
            if (r != null && r.mods()[i] != 0)
                sb.append(", ").append(noPain && Character.isPainStage(st) ? "(" + sign(r.mods()[i]) + " " + st + ": ignored)" : sign(r.mods()[i]) + " " + st);
        }
        return sb.toString();
    }

    private static void skills(Character c, List<List<Component>> body) {
        double[] now = c.skills(), perm = c.skills(true);
        int[] a = c.attrs(false);
        body.add(new Table(W).title("Skills", "◆ tagged (grows twice as fast) · a % costs 1 point to 100, 2 to 200, 4 above").render());
        Table l = new Table(P).minWidths(LABEL + 10, 40).gap(6), rt = new Table(P).minWidths(LABEL + 10, 40).gap(6);
        for (int i = 0; i < Rules.SKILLS.size(); i++) {
            Rules.Skill s = Rules.SKILLS.get(i);
            boolean tag = c.tags.contains(s.name());
            Table t = i < 8 ? l : rt;
            double bonus = c.skillBonus(s.name(), false);
            t.row(Icons.tip(Icons.skill(s.name()), "skill_" + Icons.key(s.name()), s.formula() + " = " + f(s.base().applyAsDouble(a)) + (c.points[i] != 0 ? " + " + f(c.points[i]) + " points" : "")
                            + (bonus != 0 ? (bonus > 0 ? " + " : " − ") + f(Math.abs(bonus)) + " template and implants" : "")
                            + (perm[i] != now[i] ? "\npermanent " + f(perm[i]) + " (level-up reads it)" : "") + "\nthe next %: " + Rules.pricePerPercent(perm[i]) + " point(s)",
                            Data.note("Character", s.name())),
                    c((tag ? "◆ " : "") + s.name(), tag ? NamedTextColor.GREEN : WHITE), r(f(now[i]), tag ? NamedTextColor.GREEN : WHITE), c("%", HOW));
        }
        body.add(sideBySide(l, rt, G));
    }

    private static void weapons(Character c, List<List<Component>> body) {
        double[] v = c.skills();
        body.add(new Table(W).title("Weapon skills", "from the skills as they are now").render());
        Table l = new Table(P).minWidths(LABEL + 10, 40).gap(6), rt = new Table(P).minWidths(LABEL + 10, 40).gap(6);
        int i = 0;
        for (Rules.Combo co : Rules.COMBOS) {
            (i++ < 7 ? l : rt).row(Icons.tip("iron_sword", co.formula(), Data.note("Character", co.name())), c(co.name(), WHITE),
                    r(f(Math.round(co.value().applyAsDouble(v) * 10) / 10.0), WHITE), c("%", HOW));
        }
        body.add(sideBySide(l, rt, G));
    }

    private static void traits(Character c, List<List<Component>> body) {
        Table t = new Table(W).minWidths(LABEL + 20, 110).title("Traits", "0, 1 or 2, chosen at creation");
        if (c.traits.isEmpty()) t.row(c("none", HOW), NONE, NONE);
        for (Character.Feat tr : c.traits) t.row(Icons.tip("name_tag", tr.text(), null), c(tr.name(), NamedTextColor.GOLD), c(tr.gate(), HOW), NONE);
        body.add(t.render());
        int due = c.perksDue();
        body.add(new Table(W).title("Perks", c.perks.size() + " owned · " + c.tpl().perkText() + (due > 0 ? " · " + due + " to pick" : "")
                + " · " + c.perksOpen().size() + " open to you").render());
        Table l = new Table(P).minWidths(LABEL + 10, 50).gap(6), rt = new Table(P).minWidths(LABEL + 10, 50).gap(6);
        if (c.perks.isEmpty()) l.row(c("none yet", HOW), NONE, NONE);
        int i = 0;
        for (Character.Feat f : c.perks)
            (i++ % 2 == 0 ? l : rt).row(Icons.tip("nether_star", f.text() + "\ngate: " + f.gate(), null), c(f.name(), NamedTextColor.GOLD), NONE);
        body.add(sideBySide(l, rt, G));
    }

    private static void bodyPage(Character c, List<List<Component>> body) {
        Data.Template tp = c.tpl();
        StringBuilder how = new StringBuilder("HP +" + c.hpBonus() + " · skill points +" + c.templateSkillPoints + " a level · " + tp.perkText());
        if (!tp.senses().isEmpty()) how.append("\n").append(tp.senses());
        if (tp.fallFactor() != 1) how.append("\nfalls × ").append(f(tp.fallFactor()));
        if (tp.deathWindow() != 1) how.append("\nthe clinical death window × ").append(f(tp.deathWindow()));
        if (!tp.atDeath().isEmpty()) how.append("\nat clinical death: ").append(tp.atDeath());
        if (!tp.sun().isEmpty()) how.append("\nthe sun: ").append(tp.sun().equals("pain") ? "Pain 10 after an hour of bright sun" : "Pain 5 an hour, after 2 hours 1 HP an hour");
        if (!tp.bans().isEmpty()) how.append("\nno ").append(String.join(", no ", tp.bans()));
        if (!tp.forbiddenTraits().isEmpty()) how.append("\nnever the traits ").append(String.join(", ", tp.forbiddenTraits()));
        if (!tp.notes().isEmpty()) how.append("\n").append(tp.notes());
        Table t = new Table(W).minWidths(LABEL + 60, 80).title("Template", "03b §2, for life")
                .row(tip(how.toString(), null), c(c.template + " (" + tp.family() + ")", NamedTextColor.GOLD),
                        c(tp.premium() ? "premium, CoS " + tp.cosPoints() : tp.faction() ? "a people's, opened by joining them" : tp.who().equals("pc") ? "basic" : tp.who(), HOW), NONE);
        Data.Template born = c.baseTpl();
        if (!born.genes().isEmpty()) {
            t.title("Genes", "one a slot · yellow = changed by a mutation (03b §7a) · load " + (born.load() >= 0 ? "+" : "") + born.load() + " · food × " + f(tp.foodFactor()) + " (03c)");
            for (Data.Gene g : born.genes()) {
                Character.Therapy over = c.therapyIn(g.key());
                boolean covered = over != null && over.active();
                t.row(tip(g.text() + (g.real().isEmpty() ? "" : "\n[real] " + g.real()) + "\nload " + g.load()
                                + (covered ? "\ncovered by the " + over.gene + " therapy while it lasts" : ""), null),
                        c(g.slot() + ": " + g.name() + (covered ? " (covered)" : ""), covered ? HOW : Data.GENES.containsKey(g.name()) ? WHITE : NamedTextColor.YELLOW),
                        c(g.tier(), HOW), NONE);
            }
        }
        if (!c.therapies.isEmpty()) {
            t.title("Therapies", "03d: a gene laid over the template's own for a while");
            for (Character.Therapy th : c.therapies) {
                Data.Gene g = th.g();
                if (g == null) continue;
                t.row(tip(Part3.therapyText(c, th) + "\n" + g.text() + (g.real().isEmpty() ? "" : "\n[real] " + g.real()), null),
                        c(g.slot() + ": " + g.name(), NamedTextColor.LIGHT_PURPLE), c(Part3.therapyState(th), HOW), NONE);
            }
        }
        t.title("Implants", "one a slot · /sheet implant <name> [IV|III|II] · /sheet surgery");
        int n = 0;
        for (String slot : Data.SLOTS.keySet())
            for (int k = 1; k <= c.slots(slot); k++) {
                Character.Effect e = c.inSlot("implant:" + slot + "#" + k);
                if (e == null) continue;
                t.row(tip(effText(e) + "\nin the " + Character.place(e.slot()) + ": a hit there costs it its damage in % (a crit × 2)", null), c(slot + ": " + e.name(), WHITE),
                        c(Math.round(e.condition()) + " %" + (e.condition() <= 0 ? " BROKEN" : ""), e.condition() >= 75 ? NamedTextColor.GREEN : e.condition() > 0 ? NamedTextColor.YELLOW : NamedTextColor.RED), NONE);
                n++;
            }
        if (n == 0) t.row(c("none", HOW), NONE, NONE);
        t.title("Limbs", "/sheet limb <limb> <kind> (hook, peg 50-60 %, mechanical 80, powered 100, bionic 120)");
        for (String limb : List.of("left_arm", "right_arm", "left_leg", "right_leg")) {
            Character.Effect e = c.inSlot("limb:" + limb);
            double eff = c.efficiency(limb);
            String what = limb.endsWith("arm") ? "to hit " + (Rules.prostheticToHit(eff) == 0 ? "±0" : sign(Rules.prostheticToHit(eff))) + ", Strength in this arm × " + f(eff / 100)
                    : "movement × " + f(Rules.legFactor(eff));
            t.row(tip(what + (e != null ? "\ncondition " + Math.round(e.condition()) + " %" : ""), null), c(limb.replace('_', ' '), WHITE),
                    c(e == null ? "natural" : e.name().replaceAll(" \\(.*", "") + " " + f(Math.round(eff)) + " %", e == null ? HOW : WHITE), NONE);
        }
        t.title("Mutations, chems, the rest", "/sheet mutate <rads> · /sheet dose <chem>");
        int m = 0;
        for (Character.Effect x : c.effects) {
            if (x.worn()) continue;
            t.row(tip(effText(x) + (x.next() != null ? "\nthen: " + x.next() : ""), null), c(x.name(), WHITE),
                    c(x.permanent() ? "permanent" : hours(x.hoursLeft()) + " left", x.permanent() ? HOW : NamedTextColor.LIGHT_PURPLE), NONE);
            m++;
        }
        for (String a : c.addictions) { t.row(tip("the stages run on: withdrawal, then dependence; a dose starts them again", null), c("addicted: " + a, NamedTextColor.RED), NONE, NONE); m++; }
        if (m == 0) t.row(c("none", HOW), NONE, NONE);
        t.row(tip("movement 400 % with one, 800 % with both (the stance table)\n/sheet legs 0-2 to test", null),
                c("Broken legs", WHITE), r(String.valueOf(c.brokenLegs), c.brokenLegs > 0 ? NamedTextColor.RED : WHITE), NONE);
        t.note("Gene therapies: their own session (Marek 11-15).", HOW);
        body.add(t.render());
    }

    private static void survival(Character c, List<List<Component>> body) {
        Table t = new Table(W).minWidths(LABEL - 10, 64, 120, 40).gap(8).title("Survival", null)
                .row(c("", HOW), r("value", HOW), c("stage", HOW), r("an hour", HOW), NONE);
        boolean noPain = c.ignoresPain();
        for (Rules.Stat st : Rules.Stat.values()) {
            double v = c.survival.get(st), m = c.max(st);
            String sg = c.stageOf(st, v, m);
            double h = Rules.hourly(st, c.survival.get(Rules.Stat.HYPERTHERMIA), c.sleeping, c.locationRads);
            String eff = sg == null ? "" : Data.effectText(sg);
            if (st == Rules.Stat.PAIN && sg != null && noPain) eff += "\nIGNORED: Survival " + f(Math.round(c.painCheckSurvival())) + " ≥ Pain " + f(Math.round(v));
            t.row(Icons.tip(Icons.stat(st), "stat_" + Icons.key(st.name()), eff, Data.note("Character", SheetGui.name(st))), c(c.statName(st), WHITE), r(f(Math.round(v)) + " / " + f(Math.round(m)), WHITE),
                    c(sg == null ? "fine" : c.label(sg) + (st == Rules.Stat.PAIN && noPain ? " (ignored)" : ""), SheetGui.color(Rules.level(sg))),
                    r((h >= 0 ? "+" : "") + f(Math.round(h * 10) / 10.0), HOW), NONE);
        }
        t.row(tip("Blood a round (Andor's numbers, absolute): a weak wound 2, a strong one 5, +1 a further cut\n/sheet firstaid stops it", null),
                        c("Bleeding", WHITE), r(f(c.bleeding), c.bleeding > 0 ? NamedTextColor.DARK_RED : NamedTextColor.GREEN),
                        c(c.bleeding > 0 ? "Blood a round" : "none", c.bleeding > 0 ? NamedTextColor.DARK_RED : NamedTextColor.GREEN), NONE, NONE)
                .row(tip("Sleep +4 an hour asleep, −1 awake; Pain −3 an hour only asleep or with anaesthetics", null),
                        c("Sleeping", WHITE), r(c.sleeping ? "yes" : "no", WHITE), NONE, NONE, NONE)
                .row(tip("the place's radiation", null), c("Here", WHITE), r(f(c.locationRads), NamedTextColor.YELLOW), c("rads an hour", HOW), NONE, NONE);
        // 03b §3c (RULED 11-18): the air and the gas, the ears, what is worn
        Rules.Gas g = c.gasHere();
        String airV = c.submerged ? "under water" : !c.air ? "none" : g == null ? "clean" : g.name() + " " + Rules.roman(c.gasPower()) + (c.inZone() ? " (a zone)" : "");
        String airS = c.noAir() ? "Oxygen −1 a round" : g == null ? "Oxygen +5 a round" : c.shielded(g) ? (c.eyesShielded(g) ? "kept out" : "breath kept out") : "unprotected";
        t.row(tip((g == null ? "" : g.name() + ", each round inside: " + g.text() + "\n")
                                + "Oxygen counts ROUNDS (a round = 6 s of game time): −1 without air or with a held breath, +5 in clean air, only to half in"
                                + " bad air; the GM sets the place's air and gas (03b §3c)" + (c.coUntil > c.now() ? "\nCO: Poison cures at half speed until "
                                + SheetView.hours(c.coUntil - c.now()) + " from now (full on bottled oxygen)" : ""), null),
                        c("Air", WHITE), r(airV, g == null && !c.noAir() ? NamedTextColor.GREEN : NamedTextColor.GOLD), c(airS, HOW), NONE, NONE)
                .row(tip("ringing −1, one burst eardrum −1, deaf −2, a lasting loss −1, earplugs −1 while worn; the worst counts (03b §3c)", null),
                        c("Ears", WHITE), r(c.hearingLoss() == 0 ? "P ±0" : "P −" + c.hearingLoss(), c.hearingLoss() == 0 ? NamedTextColor.GREEN : NamedTextColor.GOLD),
                        c(c.deafProof() ? "never deafened" : c.deaf() ? "deaf" : c.burstEars() == 1 ? "a burst eardrum" : c.ringing() ? "ringing" : c.lastingEarLoss ? "a lasting loss" : "fine", HOW), NONE, NONE);
        if (!c.wearing.isEmpty())
            t.row(tip("put on and taken off from your Actions (the Pip-Boy)", null), c("Worn", WHITE), r(String.join(", ", c.wearing), WHITE),
                    c(c.wearing.contains("Air bottle") ? Math.round(c.bottleMinutes) + " min of air" : "", HOW), NONE, NONE);
        t.note("/sheet demo: a clock of one game hour a second · /sheet hit <damage> [crit|stab] · /sheet firstaid", HOW);
        body.add(t.render());
    }

    private static void combat(Character c, List<List<Component>> body) {
        int[] a = c.attrs(false);
        double[] sk = c.skills();
        Table l = new Table(P).minWidths(LABEL, 36, 22).gap(6).title("Combat", null)
                .row(tip("Agility " + Rules.ac(a) + " + the armour " + c.armourAc() + " (each piece's share of its set's AC, 05c) + the stance", null), c("AC", WHITE),
                        r(String.valueOf(Rules.ac(a) + c.armourAc()), WHITE), NONE, NONE)
                .row(tip("2 × Perception + implants", null), c("Initiative", WHITE), r(String.valueOf(c.initiative()), WHITE), NONE, NONE)
                .row(tip("the template's (the orc's 3 % in melee)", null), c("Damage bonus", WHITE), r(f(Math.round(c.tpl().bonusDamage() * 100)), WHITE), c("%", HOW), NONE)
                .row(tip("S − 5, at least 1", null), c("Melee bonus", WHITE), r(String.valueOf(Rules.meleeBonus(a)), WHITE), NONE, NONE)
                .row(tip("Luck % + Sneak / 10 %", null), c("Crit chance", WHITE), r(f(Rules.critChance(a, sk[Rules.skillIndex("Sneak")])), WHITE), c("%", HOW), NONE)
                .title("Resistances", null)
                .row(c("", HOW), r("DR %", HOW), r("DT", HOW), NONE);
        String[] types = Character.DAMAGE_TYPES;   // Isolation = the old Cold/Lightning (Marek 11-16)
        double[] dr = c.resistances();
        String[] how = {"Athletics / 10", "2 × E + Survival / 10", "5 × E + Survival / 4 (poison and disease, one value)", "armour (catching fire, burning)",
                "armour (lasers, plasma, the weather's heat: reflect and vent)", "armour (the cold, tasers, electricity, EMP, the boots' water)"};
        Data.Template tp = c.tpl();
        double[] tv = {tp.damageRes(), tp.radRes(), tp.poisonRes(), 0, 0, tp.isolation()};
        for (int i = 0; i < types.length; i++)
            l.row(tip("DR: " + how[i] + (tv[i] != 0 ? " + " + c.template + " " + f(Math.round(tv[i] * 100)) + " %" : "") + " + implants and chems\nDT: armour"
                            + (i == 0 && c.dt() != 0 ? " + template and implants " + c.dt() : ""), Data.note("Character", types[i])), c(types[i], WHITE),
                    r(f(Math.round(dr[i] * 10) / 10.0), WHITE), r(String.valueOf(i == 0 ? c.dt() : 0), WHITE), NONE);
        // the armour, slot by slot (05c): a hit reads the piece over the part it strikes
        l.title("Armour", null).row(c("", HOW), r("cond. %", HOW), r("AC", HOW), NONE);
        for (String slot : Character.ARMOUR_SLOTS) {
            Character.Worn w = c.armour.get(slot);
            Data.Armour ar = w == null ? null : Data.ARMOUR.get(w.set());
            if (ar == null) { l.row(tip("nothing worn: the " + slot.toLowerCase(java.util.Locale.ROOT) + " takes the body's DT and DR only", null), c(slot + ": none", HOW), NONE, NONE, NONE); continue; }
            l.row(tip(slot + ": " + Items.pieceText(w.set(), slot, w.condition()) + (c.complete(w.set()) && ar.pieces().size() > 1 ? "\nthe whole set is worn" : "")
                            + (c.fits(w) ? "" : "\nMADE FOR A " + w.fitName().toUpperCase(java.util.Locale.ROOT) + " BODY: it gives nothing until refitted (your Actions)"), null),
                    c(slot + ": " + w.set() + (c.fits(w) ? "" : " (misfit)"), c.fits(w) ? WHITE : NamedTextColor.GOLD),
                    r(String.valueOf(Math.round(w.condition())), w.condition() >= 50 ? WHITE : NamedTextColor.GOLD),
                    r("+" + ar.pieceAc(slot), WHITE), NONE);
        }
        int cls = c.armourClass();
        int[] cf = c.comfort();
        l.row(tip("by the worn weight, " + f(Math.round(c.wornKg() * 10) / 10.0) + " kg (a running power armour counts half): light to 6 kg; medium to 13, Sneak −10 %;"
                        + "\nheavy to 25, Sneak −25 %, AP −1; super-heavy, Sneak −40 %, AP −2 (05c §3b)", null), c("Class: " + Character.CLASS_NAMES[cls], WHITE), NONE, NONE, NONE);
        l.row(tip("05c §3c: " + c.paText() + "\nuntrained, a power armour is dead weight: no Strength, no cooling, its full weight", null),
                c("PA training: " + (c.paTrained ? "yes" : Math.round(c.paHours) + "/100 h"), WHITE), NONE, NONE, NONE);
        l.row(tip("the outfit (05c §6): from 16 − Isolation DT to 31 − Isolation DT + Heat DT (a cooled suit: 31 + Heat DT);\nordinary clothes 10-25 °C;"
                        + " the boots keep out water " + Math.round(c.bootsWater() * 100) + " % (trench foot)", null), c("Comfort: " + cf[0] + " to " + cf[1] + " °C", WHITE), NONE, NONE, NONE);

        boolean brittle = c.has("Brittle Bones");
        double legs = c.legFactor();
        Table rt = new Table(P).minWidths(LABEL - 10, 40, 22).gap(6).title("Stances", null)
                .row(c("", HOW), r("AP/block", HOW), r("AC", HOW), NONE);
        String[] hit = {"", "ranged +5, melee −20", "ranged +15, no melee, +20 to be hit at short range"};
        for (int st = 0; st < 3; st++)
            rt.row(tip(hit[st].isEmpty() ? "1 AP for 2 blocks" + (brittle ? " (x2 Brittle Bones)" : "") : hit[st], null), c(Rules.STANCES[st], WHITE),
                    r(f(Rules.stepAp(st, 0, brittle) * legs), NamedTextColor.AQUA), r(Rules.STANCE_AC[st] == 0 ? "" : "+" + Rules.STANCE_AC[st], WHITE), NONE);
        rt.row(tip("400 %", null), c("a leg broken", WHITE), r(f(Rules.stepAp(0, 1, brittle)), NamedTextColor.AQUA), NONE, NONE)
                .row(tip("800 %", null), c("both legs", WHITE), r(f(Rules.stepAp(0, 2, brittle)), NamedTextColor.AQUA), NONE, NONE)
                .note("a change: 2 AP to or from a crouch, 4 standing ↔ prone", HOW)
                .title("Aimed shots", null);
        for (Rules.Part pt : Rules.Part.values())
            if (pt != Rules.Part.RIGHT_ARM && pt != Rules.Part.RIGHT_LEG)
                rt.row(tip("the part under the crosshair; +1 AP (Fallout 2's penalties)" + (pt == Rules.Part.EYES ? "\nonly when it faces you" : ""), null),
                        c(pt == Rules.Part.LEFT_ARM ? "arms" : pt == Rules.Part.LEFT_LEG ? "legs" : pt.label, WHITE),
                        r(pt.toHit == 0 ? "0" : sign(pt.toHit), NamedTextColor.YELLOW), NONE, NONE);
        body.add(sideBySide(l, rt, G));
    }
}
