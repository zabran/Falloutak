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
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/** The forms (Dialog API): character creation in three steps and the level-up's skill points. The server checks every answer. */
public final class Forms {
    private Forms() {}

    private static Ui.Btn button(String label, String tip, BiConsumer<DialogResponseView, Player> then) { return new Ui.Btn(label, tip, 150, then); }

    private static void show(Player p, String title, List<Component> body, List<Ui.In> inputs, Ui.Btn yes, Ui.Btn no) {
        Ui.show(new Ui.Page(p, title, List.copyOf(body), 0, List.copyOf(inputs), Ui.Type.CONFIRM, List.of(yes), no, 1, true));
    }

    private static void notice(Player p, String title, String text, Ui.Btn ok) {
        Ui.show(new Ui.Page(p, title, List.of(Component.text(text)), 0, List.of(), Ui.Type.NOTICE, List.of(ok), null, 1, true));
    }

    // ------------------------------------------------------------------ creation, step 1: the template, on its own page (Marek 11-17)
    /**
     * Marek 11-17: *"I think gene template needs separate page selection, the first page is too long already. Also, the custom template
     * option needs to be clickable imediately from that page."* A list of buttons, the details on hover; Own genes first.
     */
    public static void create(Player p, FalloutakPlugin pl, Character draft) {
        List<String> names = new ArrayList<>();
        names.add(Data.CUSTOM);
        for (Data.Template tp : choices(p, pl, draft)) names.add(tp.name());
        Menus.pick(p, "New character · 1 · the template", "Hover a template for its numbers. ★ premium: bought from your CoS points for you, "
                        + "or at 2 × for your whole group (the GM unlocks it).", names,
                n -> n.equals(Data.CUSTOM) ? "✎ Own genes" : (Data.TEMPLATES.get(n).premium() ? "★ " : "") + n,
                n -> n.equals(Data.CUSTOM) ? "your own template from the free genes: a load of at most +" + Data.CUSTOM_MAX_LOAD
                        + "; a drawback gene gives load back" : Menus.templateTip(n),
                (x, n) -> {
                    draft.applyTemplate(n);
                    if (n.equals(Data.CUSTOM)) { genesMenu(x, pl, draft); return; }
                    startPoints(draft);
                    attributes(x, pl, draft, "");
                }, null);
    }

    /** The points a template starts with: 5 each inside its range, the rest left to share (03b §1). */
    static void startPoints(Character draft) {
        Data.Template t = draft.tpl();
        for (int i = 0; i < 7; i++) draft.base[i] = t.fixedAttr(i) ? 0 : Math.max(Math.max(1, 1 - t.mods()[i]), Math.min(10, 5));
        for (int i = 6; i >= 0 && draft.pointsSpent() > t.attributePoints(); i--)
            while (!t.fixedAttr(i) && draft.base[i] > Math.max(1, 1 - t.mods()[i]) && draft.pointsSpent() > t.attributePoints()) draft.base[i]--;
    }

    // ------------------------------------------------------------------ step 2: the name and the attributes, a point at a time
    /**
     * Marek 11-17: *"Attribute selection allows everyone to use arbitrary smount of points. Please, add there some counter of how many
     * points player can styl distribute and how it affects derived statistics. And if possible, do not allow increase of attributes over
     * the maximum distributable."* One − and one + a attribute; + stops at 10 points or when none are left, − at the template's floor.
     */
    static void attributes(Player p, FalloutakPlugin pl, Character draft, String msg) {
        Data.Template t = draft.tpl();
        int left = t.attributePoints() - draft.pointsSpent();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("Template: " + t.name() + " · points left: " + left + " of " + t.attributePoints(),
                left == 0 ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        double[] sk = draft.skills();
        double[] res = draft.resistances();
        lines.add(Component.text("HP " + draft.hpMax() + " · AP " + draft.apMax() + " · Initiative " + draft.initiative() + " · Healing " + draft.healingRate()
                + " · Skill points a level " + Rules.skillPointsPerLevel(draft.attrs(true), t.skillPoints())
                + " · Carry " + Math.round(Rules.carry(draft.attrs(true), sk[Rules.skillIndex("Athletics")])) + " lb"
                + " · Rad res " + Math.round(res[1]) + " % · Poison res " + Math.round(res[2]) + " %", NamedTextColor.WHITE));
        if (!msg.isEmpty()) lines.add(Component.text(msg, NamedTextColor.RED));
        List<Ui.Btn> b = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            int a = i;
            String name = Rules.SPECIAL[i];
            String now = name + " " + draft.attr(i, true);
            if (t.fixedAttr(i)) {
                b.add(Menus.form("  " + now + " (fixed)", "the template fixes it: no points", (v, x) -> { keepName(v, draft); attributes(x, pl, draft, ""); }));
                b.add(Menus.form("  " + now + " (fixed) ", "the template fixes it: no points", (v, x) -> { keepName(v, draft); attributes(x, pl, draft, ""); }));
                continue;
            }
            int floor = Math.max(1, 1 - t.mods()[i]);
            b.add(Menus.form("− " + now, "one point back (the lowest: " + (floor + t.mods()[i]) + ")", (v, x) -> {
                keepName(v, draft);
                if (draft.base[a] <= floor) { attributes(x, pl, draft, name + " is at its lowest."); return; }
                draft.base[a]--;
                attributes(x, pl, draft, "");
            }));
            b.add(Menus.form("+ " + now, "one point more (the highest: " + (10 + t.mods()[i]) + ")", (v, x) -> {
                keepName(v, draft);
                if (draft.tpl().attributePoints() - draft.pointsSpent() <= 0) { attributes(x, pl, draft, "No points left: take one back elsewhere first."); return; }
                if (draft.base[a] >= 10) { attributes(x, pl, draft, name + " is at its highest."); return; }
                draft.base[a]++;
                attributes(x, pl, draft, "");
            }));
        }
        b.add(Menus.form("Next: the tags", "share every point first", (v, x) -> {
            keepName(v, draft);
            String bad = draft.checkPoints();
            if (bad != null) { attributes(x, pl, draft, bad + "."); return; }
            tags(x, pl, draft);
        }));
        b.add(Menus.form("Change the template", "back to the list", (v, x) -> { keepName(v, draft); create(x, pl, draft); }));
        Menus.menu(p, "New character · 2 · name and attributes", lines,
                List.of(Ui.In.text("name", Component.text("Name")).initial(draft.name).maxLength(32).build()), b,
                x -> { if (Data.CUSTOM.equals(draft.template)) genesMenu(x, pl, draft); else create(x, pl, draft); }, 2);
    }

    private static void keepName(DialogResponseView v, Character draft) {
        String n = v.getText("name");
        if (n != null && !n.isBlank()) draft.name = n.trim();
    }

    /**
     * The templates this player may pick (03b §2b): the basic ones ("pc"), the premium ones bought for him or unlocked for his
     * group; the GM (falloutak.gm) sees every one, for NPCs and group members.
     */
    static List<Data.Template> choices(Player p, FalloutakPlugin pl, Character draft) {
        boolean gm = p.hasPermission("falloutak.gm");
        Set<String> open = new HashSet<>(draft.unlocked);
        if (pl != null && pl.roster != null && !draft.group.isEmpty()) {
            Population.Group f = pl.roster.pop.group(draft.group);
            if (f != null) open.addAll(f.unlocked);
        }
        List<Data.Template> l = new ArrayList<>();
        for (Data.Template t : Data.TEMPLATES.values())
            if (gm || t.who().equals("pc") || (t.premium() && open.contains(t.name()))) l.add(t);
        return l;
    }

    // ------------------------------------------------------------------ step 1b: your own genes (03c §7)
    /** The slots that have free genes, in the catalogue's order. */
    static List<String> freeSlots() {
        List<String> s = new ArrayList<>();
        for (Data.Gene g : Data.GENES.values()) if (g.free() && !s.contains(g.slot())) s.add(g.slot());
        return s;
    }

    static String sign(int v) { return v > 0 ? "+" + v : String.valueOf(v); }

    /** A gene's details for a hover. */
    static String geneTip(Data.Gene g) { return g.tier() + " · load " + sign(g.load()) + "\n" + g.text() + (g.real().isEmpty() ? "" : "\n[real] " + g.real()); }

    /** The own-genes menu: one button a slot (its gene, or -), the totals and the limits above, then Done. */
    static void genesMenu(Player p, FalloutakPlugin pl, Character draft) {
        Data.Template t = draft.tpl();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("One gene a slot, a load of at most +" + Data.CUSTOM_MAX_LOAD + " (a gene with no real drawback costs load, a drawback "
                + "gives it back). The load makes you eat more: food × (1 + 0.01 × load). Hover a slot for its genes.", NamedTextColor.GRAY));
        StringBuilder mods = new StringBuilder();
        for (int i = 0; i < 7; i++) if (t.mods()[i] != 0) mods.append(" ").append(sign(t.mods()[i])).append(Rules.SPECIAL[i].charAt(0));
        int load = 0;
        for (Data.Gene g : t.genes()) load += g.load();
        lines.add(Component.text("Picked " + t.genes().size() + ": " + (t.genes().isEmpty() ? "none" : String.join(", ", draft.genes))
                + " · attributes" + (mods.length() == 0 ? " ±0" : mods) + " · load " + sign(load) + " · food × " + SheetGui.f(t.foodFactor())
                + (t.waterFactor() != 1 ? " · water × " + SheetGui.f(t.waterFactor()) : ""), NamedTextColor.WHITE));
        String bad = Data.checkCustom(draft.genes);
        if (bad != null && !draft.genes.isEmpty()) lines.add(Component.text("! " + bad, NamedTextColor.RED));
        List<Ui.Btn> b = new ArrayList<>();
        for (String slot : freeSlots()) {
            Data.Gene cur = t.genes().stream().filter(g -> g.slot().equals(slot)).findFirst().orElse(null);
            StringBuilder tip = new StringBuilder();
            for (Data.Gene g : Data.GENES.values()) if (g.free() && g.slot().equals(slot)) tip.append(g.name()).append(" (").append(sign(g.load())).append("): ").append(g.text()).append("\n");
            b.add(Menus.btn(slot + ": " + (cur == null ? "-" : cur.name()), tip.toString().trim(), x -> slotMenu(x, pl, draft, slot)));
        }
        b.add(Menus.btn("Done: the attributes", "check the genes, then share the points", x -> {
            String why = Data.checkCustom(draft.genes);
            if (why != null) { notice(x, "Your genes", why + ".", button("Back", "the genes", (v2, p2) -> genesMenu(p2, pl, draft))); return; }
            draft.applyTemplate(Data.CUSTOM);
            startPoints(draft);
            attributes(x, pl, draft, "");
        }));
        Menus.menu(p, "New character · 1b · your own genes", lines, b, x -> create(x, pl, draft));
    }

    /** One slot: none, or one of its free genes (the details on hover). */
    static void slotMenu(Player p, FalloutakPlugin pl, Character draft, String slot) {
        List<String> opts = new ArrayList<>(List.of("-"));
        for (Data.Gene g : Data.GENES.values()) if (g.free() && g.slot().equals(slot)) opts.add(g.name());
        Menus.pick(p, "Slot: " + slot, "One gene a slot. Hover a gene for what it does.", opts,
                n -> n.equals("-") ? "none" : n + " (" + sign(Data.GENES.get(n).load()) + ")",
                n -> n.equals("-") ? "leave the slot empty" : geneTip(Data.GENES.get(n)),
                (x, n) -> { setSlot(draft, slot, n.equals("-") ? null : n); genesMenu(x, pl, draft); }, x -> genesMenu(x, pl, draft));
    }

    static void setSlot(Character draft, String slot, String gene) {
        List<String> l = new ArrayList<>();
        for (String n : draft.genes) { Data.Gene g = Data.GENES.get(n); if (g != null && !g.slot().equals(slot)) l.add(n); }
        if (gene != null) l.add(gene);
        draft.setGenes(l);
        draft.applyTemplate(Data.CUSTOM);
    }

    // ------------------------------------------------------------------ step 2: three tags
    private static void tags(Player p, FalloutakPlugin pl, Character draft) {
        List<Ui.In> in = new ArrayList<>();
        for (Rules.Skill s : Rules.SKILLS)
            in.add(Ui.In.bool(key(s.name()), Component.text(s.name() + "  (" + s.formula() + ")")).initial(draft.tags.contains(s.name())).build());
        show(p, "New character · 3 · three tags", List.of(Component.text("Tag exactly three skills: they start better and grow twice as fast.")),
                in,
                button("Next", "check the three, then the traits", (v, pp) -> {
                    List<String> chosen = new ArrayList<>();
                    for (Rules.Skill s : Rules.SKILLS) if (Boolean.TRUE.equals(v.getBoolean(key(s.name())))) chosen.add(s.name());
                    draft.tags.clear();
                    draft.tags.addAll(chosen);
                    if (chosen.size() != 3) {
                        notice(pp, chosen.size() + " tagged", "Tag exactly three skills (you tagged " + chosen.size() + ").",
                                button("Back", "fix the tags", (v2, p2) -> tags(p2, pl, draft)));
                        return;
                    }
                    traits(pp, pl, draft);
                }),
                button("Back", "the attributes", (v, pp) -> attributes(pp, pl, draft, "")));
    }

    private static String key(String name) { return name.toLowerCase().replaceAll("[^a-z]", "_"); }

    // ------------------------------------------------------------------ step 4: none, one or two traits (a list), then create
    // Marek 11-17: "the trait section has no list and shows traits that have already become genes" -> every trait of 02c §1 that is
    // still a trait (data/traits.json), as a list of buttons with a filter, the details on hover; a click takes or drops one.

    /** The traits offered: not parked (parts 7-8), Fast Shot only when bought with CoS points. */
    static List<Data.Trait> offeredTraits(Character draft) {
        List<Data.Trait> out = new ArrayList<>();
        for (Data.Trait t : Data.TRAITS.values()) {
            if (t.status().equals("parked")) continue;
            if (t.status().equals("cos") && !draft.unlocked.contains(t.name())) continue;
            out.add(t);
        }
        return out;
    }

    /** What the sheet applies for a trait, as text ("+2 Strength, -2 AP"), or "" when the GM applies it. */
    static String traitNumbers(Data.Trait t) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++) if (t.mods()[i] != 0) sb.append(sb.isEmpty() ? "" : ", ").append(t.mods()[i] > 0 ? "+" : "").append(t.mods()[i]).append(" ").append(Rules.SPECIAL[i]);
        int skillsAll = 0;
        for (var e : t.other().entrySet()) if (e.getKey().startsWith("skill:")) skillsAll++;
        if (skillsAll == Rules.SKILLS.size()) {
            double v = t.other().get("skill:" + Rules.SKILLS.get(0).name());
            sb.append(sb.isEmpty() ? "" : ", ").append(v > 0 ? "+" : "").append(SheetGui.f(v)).append(" to every skill");
        }
        for (var e : t.other().entrySet()) {
            if (skillsAll == Rules.SKILLS.size() && e.getKey().startsWith("skill:")) continue;
            double v = e.getValue();
            String k = e.getKey().replace("skill:", "").replace("hp_max", "HP").replace("ap", "AP").replace("sequence", "Initiative")
                    .replace("aimed", "aimed shots").replace("poison_res", "Poison resistance %").replace("rad_res", "Rad resistance %");
            sb.append(sb.isEmpty() ? "" : ", ").append(v > 0 ? "+" : "").append(SheetGui.f(v)).append(" ").append(k);
        }
        return sb.toString();
    }

    private static boolean taken(Character draft, String name) { return draft.traits.stream().anyMatch(x -> x.name().equals(name)); }

    static void traits(Player p, FalloutakPlugin pl, Character draft) { traits(p, pl, draft, ""); }

    static void traits(Player p, FalloutakPlugin pl, Character draft, String filter) {
        String f = filter == null ? "" : filter.trim().toLowerCase(java.util.Locale.ROOT);
        List<Ui.Btn> b = new ArrayList<>();
        b.add(Menus.form("Filter", "show only the traits whose name, group, bonus or drawback holds the text", (v, x) -> traits(x, pl, draft, v.getText("f"))));
        b.add(Menus.btn("Create the character", "save the character with " + (draft.traits.isEmpty() ? "no trait" : String.join(" and ",
                draft.traits.stream().map(Character.Feat::name).toList())) + " and open the sheet", x -> finish(x, pl, draft)));
        List<Data.Trait> list = new ArrayList<>(offeredTraits(draft));
        list.sort(java.util.Comparator.comparing(t -> taken(draft, t.name()) ? 0 : 1));   // the taken ones first
        int shown = 0;
        for (Data.Trait t : list) {
            boolean mine = taken(draft, t.name());
            String all = (t.name() + " " + t.group() + " " + t.bonus() + " " + t.drawback()).toLowerCase(java.util.Locale.ROOT);
            if (!mine && !f.isEmpty() && !all.contains(f)) continue;
            boolean barred = draft.tpl().forbiddenTraits().contains(t.name());
            String nums = traitNumbers(t);
            String tip = t.group() + (t.from().isEmpty() ? "" : " (" + t.from() + ")") + ". Bonus: " + t.bonus() + ". Drawback: " + t.drawback() + "."
                    + (nums.isEmpty() ? " The GM applies it (it acts in a rule the plugin does not run yet)." : " The sheet applies: " + nums + ".")
                    + (barred ? " A " + draft.template + " cannot take it." : mine ? " Click to drop it." : "");
            b.add(Menus.btn((mine ? "✓ " : barred ? "✗ " : "") + t.name(), tip, x -> toggle(x, pl, draft, t, filter)));
            shown++;
        }
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text(draft.traits.isEmpty() ? "No trait taken (that is allowed)." : "Taken: " + String.join(", ",
                draft.traits.stream().map(Character.Feat::name).toList()) + " (" + draft.traits.size() + " of 2).", NamedTextColor.GOLD));
        lines.add(Component.text("None, one or two traits: click one to take it, a ticked one to drop it; hover for its bonus and drawback. "
                + "The traits that are bodies (Big boned, Night owl, Rh-null, Brittle bones...) are genes now, on the template's page.", NamedTextColor.GRAY));
        if (shown == 0) lines.add(Component.text("Nothing matches: change the filter.", NamedTextColor.RED));
        List<Ui.In> in = List.of(Ui.In.text("f", Component.text("Filter")).initial(filter == null ? "" : filter).maxLength(32).build());
        Menus.menu(p, "New character · 4 · traits", lines, in, b, x -> tags(x, pl, draft), 2);
    }

    private static void toggle(Player p, FalloutakPlugin pl, Character draft, Data.Trait t, String filter) {
        if (taken(draft, t.name())) draft.traits.removeIf(x -> x.name().equals(t.name()));
        else if (draft.tpl().forbiddenTraits().contains(t.name())) {   // 03b §2g: the bans per template
            notice(p, "Not for a " + draft.template, "A " + draft.template + " cannot take " + t.name() + ".",
                    button("Back", "the traits", (v, x) -> traits(x, pl, draft, filter)));
            return;
        } else if (draft.traits.size() >= 2) {
            notice(p, "Two traits at most", "Drop one first: click a ticked trait.", button("Back", "the traits", (v, x) -> traits(x, pl, draft, filter)));
            return;
        } else draft.traits.add(new Character.Feat(t.name(), "trait (" + t.from() + ")", t.bonus() + "; " + t.drawback()));
        traits(p, pl, draft, filter);
    }

    /** Create: the traits' numbers become permanent effects (attributes, skills, HP, AP, Initiative, resistances; 11-17). */
    static void applyTraits(Character draft) {
        for (Character.Feat f : draft.traits) {
            Data.Trait t = Data.TRAITS.get(f.name());
            if (t != null && t.hasNumbers())
                draft.effects.add(new Character.Effect(t.name(), "trait", "trait:" + t.name(), true, t.mods().clone(),
                        new java.util.LinkedHashMap<>(t.other()), 0, null, 100));
        }
    }

    private static void finish(Player pp, FalloutakPlugin pl, Character draft) {
        for (Character.Feat f : draft.traits)
            if (draft.tpl().forbiddenTraits().contains(f.name())) {
                notice(pp, "Not for a " + draft.template, "A " + draft.template + " cannot take " + f.name() + ".",
                        button("Back", "the traits", (v2, p2) -> traits(p2, pl, draft)));
                return;
            }
        draft.effects.clear();
        applyTraits(draft);
        java.util.Arrays.fill(draft.points, 0);
        draft.unspentSkillPoints = Rules.skillPointsPerLevel(draft.attrs(true), draft.templateSkillPoints);
        draft.hp = draft.hpMax();
        for (Rules.Stat st : Rules.Stat.values())
            draft.survival.put(st, switch (st) { case POISON, RADS, HYPOTHERMIA, HYPERTHERMIA, PAIN -> 0.0; default -> draft.max(st); });
        pl.characters.put(pp.getUniqueId(), draft);
        pp.sendMessage(Component.text("Character " + draft.name + " created.", NamedTextColor.GREEN));
        SheetView.open(pp, draft, SheetGui.Page.OVERVIEW);
    }

    // ------------------------------------------------------------------ level-up: skill points, one slider a skill
    public static void spend(Player p, FalloutakPlugin pl, Character c) { spend(p, pl, c, null); }

    /** {@code onSpent} runs after points are spent (a group's statblock is saved by it, 06 §2.2). */
    public static void spend(Player p, FalloutakPlugin pl, Character c, java.util.function.Consumer<Player> onSpent) {
        List<Ui.In> in = new ArrayList<>();
        double[] perm = c.skills(true);
        int max = Math.max(1, c.unspentSkillPoints);
        for (int i = 0; i < Rules.SKILLS.size(); i++) {
            String n = Rules.SKILLS.get(i).name();
            in.add(Ui.In.numberRange("s" + i, Component.text(n + " " + SheetGui.f(perm[i]) + " %" + (c.tags.contains(n) ? " (tag)" : "")), 0, max)
                    .step(1f).initial(0f).width(300).labelFormat("%s: +%s points").build());
        }
        show(p, "Skill points: " + c.unspentSkillPoints, List.of(Component.text(
                        "Points per skill. A % costs 1 point to 100, 2 to 200, 4 above; a tagged skill grows twice as fast. The server prices it and refuses an overspend.")),
                in,
                button("Spend", "price and apply", (v, pp) -> {
                    int total = 0;
                    int[] want = new int[Rules.SKILLS.size()];
                    for (int i = 0; i < want.length; i++) { want[i] = Math.round(v.getFloat("s" + i)); total += want[i]; }
                    if (total > c.unspentSkillPoints) {
                        notice(pp, "Too many points", total + " points chosen, " + c.unspentSkillPoints + " to spend.",
                                button("Back", "choose again", (v2, p2) -> spend(p2, pl, c, onSpent)));
                        return;
                    }
                    StringBuilder sb = new StringBuilder();
                    double[] cur = c.skills(true);
                    for (int i = 0; i < want.length; i++) {
                        int pts = want[i];
                        if (pts == 0) continue;
                        double gain = 0, val = cur[i];
                        boolean tag = c.tags.contains(Rules.SKILLS.get(i).name());
                        while (pts > 0) {
                            int price = Rules.pricePerPercent(val);
                            if (pts < price) break;
                            pts -= price;
                            double step = tag ? 2 : 1;
                            gain += step; val += step;
                        }
                        c.points[i] += gain;
                        c.unspentSkillPoints -= want[i] - pts;
                        sb.append(Rules.SKILLS.get(i).name()).append(" +").append(SheetGui.f(gain)).append(" %  ");
                    }
                    pp.sendMessage(Component.text("Spent: " + sb, NamedTextColor.GREEN));
                    if (onSpent != null) onSpent.accept(pp);
                    SheetView.open(pp, c, SheetGui.Page.SKILLS);
                }),
                button("Cancel", "nothing spent", (v, pp) -> SheetView.open(pp, c, SheetGui.Page.SKILLS)));
    }
}
