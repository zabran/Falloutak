package cz.lollywood.falloutak;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static cz.lollywood.falloutak.Menus.*;

/**
 * The production pages (part 7, `07-PRODUCTION.md`): the base's buildings, their jobs and crews, for every player (Marek 11-20: *"In
 * base, players are kings and own everything."*); the assembly of what is taken out (round 4); building, removing and unlocking recipes
 * for the GM.
 */
final class Works {
    private Works() {}

    static Production pr() { return pl.production; }

    static boolean gm(Player p) { return p.hasPermission("falloutak.gm"); }

    static String recipeTip(Production.Recipe r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.repairs.isEmpty() ? "makes " + Production.makesText(r) : "mends worn " + r.repairs + " " + Rules.roman(r.tier))
                .append(" from ").append(Production.inputsText(r)).append(" · ").append(r.building)
                .append(" ").append(Rules.roman(r.tier)).append("+ · ").append(r.skill).append(" ").append(r.min).append(" · ")
                .append(SheetGui.f(r.hours)).append(" h of work at the minimum");
        if (r.days > 0) sb.append(" · at least ").append(SheetGui.f(r.days)).append(r.days == 1 ? " day" : " days");
        if (!r.note.isEmpty()) sb.append(" · ").append(r.note);
        if (!r.atStart()) sb.append(" · RESEARCHED: ").append(r.research).append(" (").append(r.better).append(")");
        sb.append(" · * = sets the rarity, tool = wears, extra = x1.5 faster");
        return sb.toString();
    }

    // ------------------------------------------------------------------ the page
    static void main(Player p) {
        Production pr = pr();
        List<Component> lines = new ArrayList<>();
        lines.add(line(pr.supply() + (pr.harvest != 100 ? " · harvests at " + Math.round(pr.harvest) + " %" : ""), NamedTextColor.WHITE));
        lines.add(line("A job runs when the GM passes time for everyone: its crew works, the store gives the inputs and takes what is made.", NamedTextColor.GRAY));
        List<Ui.Btn> b = new ArrayList<>();
        for (Production.Building x : pr.buildings)
            b.add(btn(x.name + (x.job == null ? "" : " · " + Production.recipe(x.job.recipe).name) + (x.job != null && !x.job.waiting.isEmpty() ? " · waits" : ""),
                    x.label() + ": " + pr.status(x), y -> building(y, x.id)));
        if (pr.buildings.isEmpty()) lines.add(line("No buildings yet" + (gm(p) ? ": add the shelter's seven rooms below." : ": the GM adds the shelter's rooms."), NamedTextColor.RED));
        b.add(btn("Assemble items to take out", "weapons, armour, rounds, grenades and traps out of the store's parts: instant (round 4)", Works::assemble));
        b.add(btn("Work anywhere in the base", "building projects and hand work that need no room", x -> site(x, "Base")));
        b.add(btn("Gathering outside", "wood, stone, water, herbs, hunting (0 XP until the local map, part 9)", x -> site(x, "Outside")));
        b.add(btn("All known recipes", pr.known.size() + " known beyond the start", Works::recipes));
        b.add(btn("Washing water", washing(pr), Works::washForm));
        if (gm(p)) b.add(btn("GM: buildings and recipes", "add the shelter, build or remove a building, unlock a recipe, the harvest", Works::gmPage));
        menu(p, "Production", lines, b, Menus::actions);
    }

    /** What the people wash with now (round 9: the players' choice). */
    static String washing(Production pr) {
        StringBuilder s = new StringBuilder("2 l a person a day: ");
        s.append(pr.washTier2 ? "the scrubbers' condensate and Tier II (as filtered water) first, " : "").append(pr.washTier1 ? "then Tier I, " : "")
                .append("the clean water (the filtration's, Tier III)").append(pr.washTier4 ? ", Tier IV last" : "")
                .append("; the wounded the clean water first, then Tier II as boiled water");
        String h = Production.hygiene(pr.unwashed);
        return s + (h.isEmpty() ? "" : " · the base: " + h);
    }

    /** Any player picks the washing water (round 9, Marek 11-21: *"players can choose"*). */
    static void washForm(Player p) {
        Production pr = pr();
        Menus.ask(p, "Washing water", washing(pr) + ". Tier I puts its rads on the skin; water with germs is fine on whole skin, "
                        + "not on a wound; the clean water is always used.",
                List.of(Ui.In.bool("t2", Component.text("Tier II and the scrubbers' condensate")).initial(pr.washTier2).build(),
                        Ui.In.bool("t1", Component.text("Tier I (dirty or irradiated)")).initial(pr.washTier1).build(),
                        Ui.In.bool("t4", Component.text("Tier IV (mineral water, tea)")).initial(pr.washTier4).build()),
                Menus.form("Set", "the people wash with this", (v, x) -> {
                    pr.washTier2 = Boolean.TRUE.equals(v.getBoolean("t2"));
                    pr.washTier1 = Boolean.TRUE.equals(v.getBoolean("t1"));
                    pr.washTier4 = Boolean.TRUE.equals(v.getBoolean("t4"));
                    pr.save();
                    main(x);
                }), Works::main);
    }

    static void building(Player p, String id) {
        Production pr = pr();
        Production.Building x = pr.building(id);
        if (x == null) { main(p); return; }
        List<Component> lines = new ArrayList<>();
        Production.Kind k = x.kind();
        lines.add(line(x.label() + (k == null ? "" : " · " + k.makes), NamedTextColor.WHITE));
        lines.add(line(pr.status(x), NamedTextColor.GRAY));
        if (k != null) lines.add(line("Power " + Math.round(k.power) + ", water " + Math.round(k.water) + " l " + (k.daily ? "every day" : "a working day")
                + (k.produces.isEmpty() ? "" : " · makes " + k.produces) + " · " + x.slots() + (x.slots() == 1 ? " slot" : " slots"), NamedTextColor.GRAY));
        Production.Workforce wf = pl.workforce();
        for (String w : x.crew) {
            Production.Worker wk = pr.worker(w, wf);
            Production.Recipe r = x.job == null ? null : Production.recipe(x.job.recipe);
            lines.add(line(" · " + (wk == null ? w : wk.label() + (r == null ? "" : ", " + r.skill + " " + Math.round(wk.skill(r.skill)))), NamedTextColor.GRAY));
        }
        List<Ui.Btn> b = new ArrayList<>();
        if (x.job == null) {
            b.add(btn("Start a job", "the recipes this building can run, the best first", y -> recipePick(y, x.id)));
            if (Production.fits(x, Production.repairRecipe("Hard armour parts", 1)))
                b.add(btn("Repair worn parts", "a row of worn parts in the store: the repair kit's points by the crew's skill (round 5)", y -> repairPick(y, x.id)));
        } else b.add(btn("Stop the job", "the run's inputs go back to the store; the crew is free again", y -> { say(y, pr.stop(x, pl.workforce(), pl.base)); pr.save(); main(y); }));
        b.add(btn("Pick the crew again", "the best free workers fill the free slots (the automatic pick)", y -> {
            List<String> got = pr.pick(x, pl.workforce());
            say(y, got.isEmpty() ? "Nobody free fits." : "Joined: " + String.join(", ", got));
            pr.save(); building(y, x.id);
        }));
        b.add(btn("Add a worker", "choose one of the free workers (the players may change the pick)", y -> addWorker(y, x.id)));
        if (!x.crew.isEmpty()) b.add(btn("Remove a worker", "back to idling", y -> removeWorker(y, x.id)));
        if (gm(p) && !x.site) b.add(btn("GM: an event damages it", "a burst pipe, a fire, a quake: its condition falls (round 6: the prologue's events)", y -> gmDamage(y, x.id)));
        if (gm(p) && !x.site) b.add(btn("GM: remove the building", "gone, its job stopped", y -> { pr.remove(x, pl.workforce(), pl.base); pr.save(); main(y); }));
        menu(p, x.name, lines, b, Works::main);
    }

    /** The GM's event on a building: its condition falls by the points chosen (round 6) [mine]. */
    static void gmDamage(Player p, String id) {
        Production.Building x = pr().building(id);
        if (x == null) { main(p); return; }
        ask(p, "An event damages " + x.name, "Now " + Production.pct(x.condition) + " %. Below 75 % it works less, at 0 it stops until its crew repairs it with "
                        + Production.UPKEEP_TOOL + " of Tier " + Rules.roman(x.tier) + " or better, "
                        + SheetGui.f(Production.repairPerHour(x.tier)) + " % a crew hour (10 points = " + SheetGui.f(10 / Production.repairPerHour(x.tier)) + " crew hours).",
                List.of(Ui.In.numberRange("d", Component.text("Condition lost (% points)"), 0, 100).step(5f).initial(20f).width(300).build()),
                form("Damage", "the condition falls", (v, y) -> { say(y, pr().damage(x, v.getFloat("d"))); pr().save(); building(y, x.id); }),
                y -> building(y, x.id));
    }

    static void recipePick(Player p, String id) {
        Production.Building x = pr().building(id);
        if (x == null) { main(p); return; }
        List<Production.Recipe> rs = pr().recipesFor(x);
        recipeList(p, "Start a job: " + x.name, rs.isEmpty() ? "Nothing known fits this building." : "Hover for what it takes.", rs,
                (y, r) -> jobForm(y, x.id, null, r), y -> building(y, x.id), y -> recipePick(y, x.id));
    }

    static void site(Player p, String kind) {
        recipeList(p, "Base".equals(kind) ? "Anywhere in the base" : "Gathering outside", "Each job gets its own site; the pick takes up to 4 workers.",
                pr().siteRecipes(kind), (y, r) -> jobForm(y, null, kind, r), Works::main, y -> site(y, kind));
    }

    /** A player's recipe filter (round 5): a part of what it makes, of what it uses, and whether the fully outdated show. */
    record Query(String makes, String uses, boolean old) {}

    static final Map<UUID, Query> QUERIES = new HashMap<>();

    static Query query(Player p) { return QUERIES.getOrDefault(p.getUniqueId(), new Query("", "", false)); }

    /**
     * A list of recipes (round 5, Marek 11-20, verbatim: *"please make sure that in game, it is possible to filter both by input and
     * output and that the best recipes are shown on top, and probably hide these fully outdated."*): a filter by what it makes and by
     * what it uses, the best first ({@link Production#rank}), the fully outdated ({@link Production#outdatedBy}) hidden unless ticked.
     */
    static void recipeList(Player p, String title, String text, Collection<Production.Recipe> pool, BiConsumer<Player, String> then,
                           Consumer<Player> back, Consumer<Player> again) {
        Query q = query(p);
        Production pr = pr();
        List<Production.Recipe> cur = new ArrayList<>(), old = new ArrayList<>();
        for (Production.Recipe r : Production.rank(pool, q.makes(), q.uses())) (pr.outdatedBy(r) == null ? cur : old).add(r);
        List<Ui.In> in = List.of(
                Ui.In.text("m", Component.text("Makes (a part of a type, e.g. metal ii)")).initial(q.makes()).maxLength(32).build(),
                Ui.In.text("u", Component.text("Uses (e.g. salvage)")).initial(q.uses()).maxLength(32).build(),
                Ui.In.bool("o", Component.text("Show the fully outdated")).initial(q.old()).build());
        List<Ui.Btn> b = new ArrayList<>();
        b.add(form("Filter", "what it makes and what it uses; the best first", (v, x) -> {
            QUERIES.put(x.getUniqueId(), new Query(v.getText("m"), v.getText("u"), Boolean.TRUE.equals(v.getBoolean("o"))));
            again.accept(x);
        }));
        for (Production.Recipe r : cur) b.add(btn(r.name + " → " + Production.makesText(r), recipeTip(r), x -> then.accept(x, r.id)));
        if (q.old()) for (Production.Recipe r : old) {
            Production.Recipe by = pr.outdatedBy(r);
            b.add(btn("(outdated) " + r.name + " → " + Production.makesText(r), recipeTip(r) + " · OUTDATED: " + by.name + " (" + by.building + " "
                    + Rules.roman(by.tier) + ") does it with no more of anything", x -> then.accept(x, r.id)));
        }
        List<Component> lines = new ArrayList<>();
        if (!text.isEmpty()) lines.add(line(text, NamedTextColor.GRAY));
        lines.add(line("The best first: the highest tier made, then the fewest inputs and work hours a unit made.", NamedTextColor.GRAY));
        if (!old.isEmpty() && !q.old())
            lines.add(line(old.size() + " fully outdated hidden (a known recipe the base can run does the same with no more of anything).", NamedTextColor.DARK_GRAY));
        if (cur.isEmpty() && (old.isEmpty() || !q.old())) lines.add(line("Nothing matches: change the filter.", NamedTextColor.RED));
        menu(p, title, lines, in, b, back, 2);
    }

    /** The repair job (round 5): a row of worn parts this building can mend, then how many runs. */
    static void repairPick(Player p, String id) {
        Production.Building x = pr().building(id);
        if (x == null) { main(p); return; }
        List<String> keys = new ArrayList<>();
        for (Base.Pool q : pl.base.wornRows()) {
            Production.Recipe r = Production.repairRecipe(q.type, q.tier);
            if (r != null && Production.fits(x, r)) keys.add(q.key());
        }
        pick(p, "Repair worn parts: " + x.name, keys.isEmpty() ? "No worn parts this building can mend (a Workbench or Workshop of at least the parts' tier)."
                        : "A run: 1 unit of the material and " + SheetGui.f(Production.REPAIR.hours) + " h of work at the minimum " + Production.REPAIR.min
                        + "; the repair kit's points by the best skill (Mr. Fixit x2) to each of up to " + Production.REPAIR.parts + " worn parts.", keys,
                k -> { Base.Pool q = pl.base.pools.get(k); return q.type + " " + Rules.roman(q.tier) + (q.rarity > 1 ? " R" + q.rarity : "") + " · " + q.worn() + " worn at " + Math.round(q.average()) + " %"; },
                k -> { Base.Pool q = pl.base.pools.get(k); return recipeTip(Production.repairRecipe(q.type, q.tier)); },
                (y, k) -> {
                    Base.Pool q = pl.base.pools.get(k);
                    Production.Recipe r = Production.repairRecipe(q.type, q.tier);
                    ask(y, r.name, recipeTip(r), List.of(Ui.In.numberRange("n", Component.text("Runs (0 = until none is worn)"), 0, 50).step(1f).initial(0f).width(300).build()),
                            form("Start", "the repair starts; the pick fills the crew", (v, z) -> {
                                say(z, pr().start(x, r, Math.round(v.getFloat("n")), q.rarity, pl.workforce()));
                                pr().save();
                                building(z, x.id);
                            }), w -> repairPick(w, x.id));
                }, y -> building(y, x.id));
    }

    /** How many times and at which rarity (the marked inputs'), then the job starts and the pick fills the crew. */
    static void jobForm(Player p, String buildingId, String siteKind, String recipeId) {
        Production.Recipe r = Production.RECIPES.get(recipeId);
        List<Ui.In> in = List.of(
                Ui.In.numberRange("n", Component.text("Runs (0 = until stopped)"), 0, 50).step(1f).initial(1f).width(300).build(),
                Ui.In.numberRange("r", Component.text("Rarity of the marked inputs"), 1, 4).step(1f).initial(1f).width(300).build());
        ask(p, r.name, recipeTip(r), in, form("Start", "the job starts; the pick fills the crew", (v, y) -> {
            int runs = Math.round(v.getFloat("n")), rar = Math.round(v.getFloat("r"));
            Production pr = pr();
            if (siteKind != null) {
                Production.Building s = pr.startAt(siteKind, r, runs, rar, pl.workforce());
                say(y, s == null ? "! it could not start" : s.name + ": started; " + pr.status(s));
                pr.save();
                if (s != null) building(y, s.id); else main(y);
                return;
            }
            Production.Building x = pr.building(buildingId);
            say(y, pr.start(x, r, runs, rar, pl.workforce()));
            pr.save();
            building(y, buildingId);
        }), y -> { if (buildingId != null) recipePick(y, buildingId); else site(y, siteKind); });
    }

    static void addWorker(Player p, String id) {
        Production pr = pr();
        Production.Building x = pr.building(id);
        if (x == null) { main(p); return; }
        Production.Recipe r = x.job == null ? null : Production.recipe(x.job.recipe);
        Production.Workforce wf = pl.workforce();
        List<Production.Worker> c = r != null ? pr.candidates(r, wf) : pr.upkeepCandidates(x, wf);
        List<String> ids = new ArrayList<>();
        for (Production.Worker w : c) ids.add(w.id());
        String sk = r != null ? r.skill : x.kind() == null ? "Crafting" : x.kind().upkeep_skill;
        pick(p, "Add a worker: " + x.name, ids.isEmpty() ? "Nobody free fits." : "The free workers, the best at " + sk + " first.", ids,
                w -> { Production.Worker k = pr.worker(w, wf); return k == null ? w : k.label() + " · " + sk + " " + Math.round(k.skill(sk)); },
                w -> "into the crew of " + x.name, (y, w) -> {
                    Production.Worker k = pr.worker(w, pl.workforce());
                    String m = k == null ? "! gone" : pr.add(x, k, pl.workforce());
                    say(y, m == null ? k.label() + " joins." : "! " + m);
                    pr.save(); building(y, x.id);
                }, y -> building(y, x.id));
    }

    static void removeWorker(Player p, String id) {
        Production pr = pr();
        Production.Building x = pr.building(id);
        if (x == null) { main(p); return; }
        Production.Workforce wf = pl.workforce();
        pick(p, "Remove a worker: " + x.name, "", new ArrayList<>(x.crew), w -> { Production.Worker k = pr.worker(w, wf); return k == null ? w : k.label(); },
                w -> "back to idling", (y, w) -> { pr.drop(x, w, pl.workforce()); pr.save(); building(y, x.id); }, y -> building(y, x.id));
    }

    static void recipes(Player p) {
        List<Production.Recipe> rs = new ArrayList<>();
        for (Production.Recipe r : Production.RECIPES.values()) if (pr().known(r)) rs.add(r);
        recipeList(p, "Known recipes", rs.size() + " known (Tier I-II of every type from the start; research, part 8, opens more).", rs,
                (y, r) -> recipes(y), Works::main, Works::recipes);
    }

    // ------------------------------------------------------------------ the assembly (round 4)
    /**
     * What is assembled from the store's parts when going out (Marek 11-20, verbatim: *"I think creating just the parts and the item
     * assembled out of them when going out would work better."*): the categories, then their items; instant [mine].
     */
    static void assemble(Player p) {
        List<String> cats = new ArrayList<>();
        for (Production.Category c : Production.CATEGORIES) cats.add(c.name);
        pick(p, "Assemble to take out", "Instant: the parts come out of the store as the item, at the parts' condition. Back through the Mess "
                        + "Chest it comes apart into all its parts again, at its condition.", cats,
                c -> c + " · " + ready(c) + " ready", Works::about, Works::category, Works::main);
    }

    /** How many of a category's items the store's parts make now. */
    static int ready(String cat) {
        int n = 0;
        for (Production.Design d : Production.DESIGNS.values()) if (d.category.equals(cat) && pl.base.canAssemble(d) > 0) n++;
        return n;
    }

    static String about(String cat) {
        for (Production.Category c : Production.CATEGORIES)
            if (c.name.equals(cat)) return c.group + (c.skill.isEmpty() ? "" : " · used with " + c.skill) + (c.note.isEmpty() ? "" : " · " + c.note);
        return cat;
    }

    static void category(Player p, String cat) {
        List<Production.Design> ds = new ArrayList<>();
        for (Production.Design d : Production.DESIGNS.values()) if (d.category.equals(cat)) ds.add(d);
        ds.sort(Comparator.comparing((Production.Design d) -> !pl.base.unlocked(d)).thenComparingInt(d -> d.tier).thenComparingInt(d -> d.rarity));
        List<String> keys = new ArrayList<>();
        for (Production.Design d : ds) keys.add(d.key());
        pick(p, cat, "Unlocked first; the number = how many the store's parts make now. Hover for the parts.", keys,
                k -> label(Production.DESIGNS.get(k)), k -> tip(Production.DESIGNS.get(k)), Works::assembleForm, y -> assemble(y));
    }

    static String label(Production.Design d) {
        if (!pl.base.unlocked(d)) return d.label() + " · locked";
        int can = pl.base.canAssemble(d), fresh = pl.base.canAssembleFresh(d);
        return (can > 0 ? "✔ " : "") + d.label() + " · " + can * d.makes + " ready" + (can > fresh ? " (" + fresh * d.makes + " undamaged)" : "");
    }

    static String tip(Production.Design d) {
        String miss = pl.base.missing(d);
        double worn = pl.base.conditionOf(d, false);
        return "takes " + Production.partsText(d) + (d.armour() ? " (made for your body)" : "")
                + (worn >= 0 && worn < Base.UNDAMAGED ? " · from the worn parts first it comes out at " + Math.round(worn) + " %" : "")
                + (!pl.base.unlocked(d) ? " · LOCKED: Tier I-II at rarity 1 from the start, else brought in through the Mess Chest or the GM's"
                : miss.isEmpty() ? "" : " · missing for one: " + miss);
    }

    /**
     * One item, or a form for how many when the parts make more (armour: one a click); where worn parts are among them, the worn first
     * (the item at their average) or the undamaged (round 5: the parts keep their condition).
     */
    static void assembleForm(Player p, String key) {
        Production.Design d = Production.DESIGNS.get(key);
        if (d == null) { assemble(p); return; }
        if (!pl.base.unlocked(d)) { say(p, "! " + d.name + " is locked: bring one in through the Mess Chest, or the GM unlocks it"); category(p, d.category); return; }
        int can = pl.base.canAssemble(d), fresh = pl.base.canAssembleFresh(d);
        if (can == 0) { say(p, "! not enough parts in the store: " + pl.base.missing(d)); category(p, d.category); return; }
        double worn = pl.base.conditionOf(d, false);
        boolean choice = worn < Base.UNDAMAGED;
        if ((can == 1 || d.armour()) && !choice) { say(p, give(p, d, 1, false)); category(p, d.category); return; }
        List<Ui.In> in = new ArrayList<>();
        if (can > 1 && !d.armour())
            in.add(Ui.In.numberRange("n", Component.text(d.makes > 1 ? "Batches (" + d.makes + " each)" : "How many"), 1, Math.min(64, can)).step(1f).initial(1f).width(300).build());
        if (choice) in.add(Ui.In.bool("f", Component.text("The undamaged parts first (" + fresh * d.makes + " from them alone)")).initial(false).build());
        ask(p, d.name, "Each " + (d.makes > 1 ? "batch of " + d.makes : "one") + " takes " + Production.partsText(d) + "; the store's parts make " + can + "."
                        + (choice ? " From the worn parts first it comes out at " + Math.round(worn) + " %." : ""), in,
                form("Assemble", "out of the store's parts, into your inventory", (v, y) -> {
                    int n = can > 1 && !d.armour() ? Math.round(v.getFloat("n")) : 1;
                    say(y, give(y, d, n, choice && Boolean.TRUE.equals(v.getBoolean("f"))));
                    category(y, d.category);
                }), y -> category(y, d.category));
    }

    /** n batches out of the store's parts, the worn first. */
    static String give(Player p, Production.Design d, int n) { return give(p, d, n, false); }

    /**
     * n batches out of the store's parts into the player's inventory (armour made for his body), the worn parts first or
     * ({@code fresh}) the undamaged: the items come out at the parts' average (round 5). Returns what he is told.
     */
    static String give(Player p, Production.Design d, int n, boolean fresh) {
        double cond = pl.base.assemble(d, n, fresh);
        if (cond < 0) return "! not enough parts in the store: " + pl.base.missing(d);
        String fit = pl.character(p).fit();
        if (d.armour()) for (int i = 0; i < n; i++) Items.giveStack(p, Items.piece(new Character.Worn(d.set, d.slot, cond, fit)));
        else Items.giveStack(p, Items.made(d, n * d.makes, cond));
        Sfx.to(p, "store.take");
        return "Assembled: " + n * d.makes + " × " + d.name + (d.armour() ? " (" + d.slot.toLowerCase(java.util.Locale.ROOT) + ")"
                + (Character.STANDARD.equals(fit) ? "" : ", made for a " + fit + " body") : "")
                + (cond < Base.UNDAMAGED ? ", at " + Math.round(cond) + " % (worn parts)" : "") + ".";
    }

    // ------------------------------------------------------------------ the GM
    static void gmPage(Player g) {
        if (!gm(g)) return;
        Production pr = pr();
        List<Ui.Btn> b = new ArrayList<>();
        b.add(btn("The shelter's seven rooms", "the reactor, water filtration, air scrubbers, kitchen, infirmary, maintenance workshop, dormitories (once)",
                x -> { int n = pr.shelter(); say(x, n == 0 ? "The shelter's rooms are there already." : n + " rooms added."); pr.save(); gmPage(x); }));
        b.add(btn("Build a building", "a kind and its tier (by hand: a recipe of the Base makes one too)", Works::gmBuild));
        b.add(btn("Unlock a recipe", "research (part 8), a found schematic: the researched ways", Works::gmUnlock));
        b.add(btn("Harvest factor: " + Math.round(pr.harvest) + " %", "the fields' yield (the nuclear autumn's failed harvests, part 1)", x -> ask(x, "Harvest factor", "",
                List.of(Ui.In.numberRange("h", Component.text("% of a normal harvest"), 0, 150).step(5f).initial((float) pr.harvest).width(300).build()),
                form("Set", "set it", (v, y) -> { pr.harvest = Math.round(v.getFloat("h")); pr.save(); gmPage(y); }), Works::gmPage)));
        menu(g, "GM · production", List.of(line(pr.buildings.size() + " buildings · " + pr.known.size() + " recipes unlocked beyond the start", NamedTextColor.GRAY)), b, Works::main);
    }

    static void gmBuild(Player g) {
        List<String> kinds = new ArrayList<>();
        for (Production.Kind k : Production.KINDS.values()) if (!Production.SITES.contains(k.kind)) kinds.add(k.kind);
        pick(g, "Build a building", "Its kind, then its tier.", kinds, k -> k, k -> Production.KINDS.get(k).makes + " · " + Production.KINDS.get(k).note,
                (x, k) -> ask(x, "Build: " + k, "", List.of(Ui.In.numberRange("t", Component.text("Tier"), 1, 4).step(1f).initial(2f).width(300).build()),
                        form("Build", "it stands", (v, y) -> { Production.Building nb = pr().build(k, Math.round(v.getFloat("t")), k); pr().save(); say(y, nb.label() + " built."); gmPage(y); }),
                        Works::gmBuild), Works::gmPage);
    }

    static void gmUnlock(Player g) {
        List<String> ids = new ArrayList<>();
        for (Production.Recipe r : Production.RECIPES.values()) if (!r.atStart()) ids.add(r.id);
        pick(g, "Unlock a recipe", "Researched or found: a tick = known now (click again to forget it).", ids,
                r -> (pr().known.contains(r) ? "✔ " : "") + Production.RECIPES.get(r).name, r -> recipeTip(Production.RECIPES.get(r)),
                (x, r) -> { if (!pr().known.remove(r)) pr().known.add(r); pr().save(); gmUnlock(x); }, Works::gmPage);
    }

}
