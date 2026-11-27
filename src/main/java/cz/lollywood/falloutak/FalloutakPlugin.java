package cz.lollywood.falloutak;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * Fallouťák in Minecraft, 0.1: the EXAMPLE Marek asked for (2026-11-14: *"I think I would have to see an example to know for
 * sure."*): the sheet's pages, the creation and level-up forms, the survival sidebar with a demo clock, the battle controls.
 */
public class FalloutakPlugin extends JavaPlugin implements Listener {
    final Map<UUID, Character> characters = new HashMap<>();
    /** NPC figurants' statblocks by name (loaded from the store when first met; saved with the rest). */
    final Map<String, Character> npcs = new LinkedHashMap<>();
    Hud hud;
    Battle battle;
    Store store;
    Roster roster;
    /** Gas zones in the world (03b §3c, Marek 11-18: localized). */
    Zones zones;
    Anomalies anomalies;
    /** Saved games (round 11): a character with its inventory; /game off and on. */
    Saves saves;
    /** The base's store of generic items (05c §7, Marek 11-18: chosen in the base before going out). */
    Base base;
    /** The store's chests (Marek 11-19: one chest per type, the Mess Chest). */
    Storage storage;
    /** Part 7: the base's buildings, jobs and crews (`07-PRODUCTION.md`). */
    Production production;

    /**
     * Who can work in the base (part 7, round 2: *"your idea works"*): the groups' members who idle or already produce (each at his
     * group's statblock, part 6) and the characters online set to *Work in the base*.
     */
    Production.Workforce workforce() {
        return new Production.Workforce() {
            @Override public List<Production.Worker> all() {
                List<Production.Worker> l = new ArrayList<>();
                Map<String, Character> sheets = new HashMap<>();
                for (Population.Person p : roster.pop.people) {
                    String a = p.activity == null ? "" : p.activity.trim().toLowerCase(java.util.Locale.ROOT);
                    if (!(a.isEmpty() || a.equals("idling") || a.equals("idle") || a.equals("production") || a.equals("gathering")) || p.group.isBlank()) continue;
                    Character sb = sheets.computeIfAbsent(p.group.toLowerCase(java.util.Locale.ROOT), g -> roster.statblock(p.group));
                    l.add(new Production.Worker("person:" + p.name, p.name + " (" + p.group + ")", sb));
                }
                for (Player pl : getServer().getOnlinePlayers()) {
                    Character c = character(pl);
                    if (c.working && !c.dead) l.add(new Production.Worker("pc:" + pl.getUniqueId(), c.name.isBlank() ? pl.getName() : c.name, c));
                }
                return l;
            }

            @Override public void award(String id, long xp, String why) {
                if (id.startsWith("pc:")) {
                    Player p = getServer().getPlayer(java.util.UUID.fromString(id.substring(3)));
                    if (p != null) roster.award(p, xp, why);
                } else if (id.startsWith("person:")) {
                    Population.Person p = roster.pop.person(id.substring(7));
                    if (p != null && !p.group.isBlank()) roster.pool(p.group, xp);
                }
            }

            @Override public void assign(String id, String activity) {
                if (!id.startsWith("person:")) return;
                Population.Person p = roster.pop.person(id.substring(7));
                if (p != null) { p.activity = activity.isEmpty() ? "Idling" : activity; roster.save(); }
            }
        };
    }

    @Override
    public void onEnable() {
        store = new Store(getDataFolder().toPath());
        zones = new Zones(getDataFolder().toPath());
        anomalies = new Anomalies(this);
        saves = new Saves(this);
        base = new Base(getDataFolder().toPath());
        Travel.load(getDataFolder().toPath());   // part 9d: the world map's travel and its encounter roll
        production = Production.load(getDataFolder().toPath());
        base.knownMaker = production::makesNamed;   // 0.60.0: a named item is open when a recipe the base knows makes it
        Items.KEY = new org.bukkit.NamespacedKey(this, "fo_thing");
        Items.NPC = new org.bukkit.NamespacedKey(this, "fo_npc");
        Npcs.SIDE = new org.bukkit.NamespacedKey(this, "fo_side");     // the GM's NPC tools (Marek 11-24)
        Npcs.FOLLOW = new org.bukkit.NamespacedKey(this, "fo_follow");
        Items.COND = new org.bukkit.NamespacedKey(this, "fo_cond");
        Accessories.KEY = new org.bukkit.NamespacedKey(this, "fo_acc");   // 05d §5: the accessories on a weapon
        Items.FIT = new org.bukkit.NamespacedKey(this, "fo_fit");
        Menus.pl = this;
        hud = new Hud(this);
        battle = new Battle(this);
        getServer().getScheduler().runTaskTimer(this, () -> Npcs.tick(this), 20L, 10L);   // the GM's followers walk after their leader
        roster = new Roster(this);
        for (String n : List.of("npc", "pop", "group", "xp")) { var cmd = getCommand(n); if (cmd != null) cmd.setExecutor(roster); }
        var gmCmd = getCommand("gm");
        if (gmCmd != null) gmCmd.setExecutor((s, c, l, a) -> { if (s instanceof Player p) Menus.gm(p); else s.sendMessage("Players only."); return true; });
        var sndCmd = getCommand("sounds");
        if (sndCmd != null) sndCmd.setExecutor((s, c, l, a) -> { s.sendMessage(s instanceof Player p ? soundsCommand(p, a) : "Players only."); return true; });
        var creCmd = getCommand("creature");
        if (creCmd != null) creCmd.setExecutor((s, c, l, a) -> { s.sendMessage(s instanceof Player p ? Creatures.command(p, a) : "Players only."); return true; });
        var anCmd = getCommand("anomaly");   // 09c (round 9): the anomalies, their artifacts, the nut and the detector (GMs)
        if (anCmd != null) anCmd.setExecutor((s, c, l, a) -> { s.sendMessage(s instanceof Player p ? Menus.anomalyCommand(p, a) : "Players only."); return true; });
        var rainCmd = getCommand("blackrain");   // the Black Rain, the surge (round 9; GMs)
        if (rainCmd != null) rainCmd.setExecutor((s, c, l, a) -> { s.sendMessage(s instanceof Player p ? Menus.rainCommand(p, a) : "Players only."); return true; });
        var travelCmd = getCommand("travel");   // part 9d (11-23): the party's journey on the world map, a roll every 4 hours
        if (travelCmd != null) travelCmd.setExecutor((s, c, l, a) -> {
            if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
            String r = Travel.command(this, p, a);
            if (r != null) Menus.say(p, r);
            return true;
        });
        var mapsCmd = getCommand("maps");
        if (mapsCmd != null) mapsCmd.setExecutor((s, c, l, a) -> { s.sendMessage(Maps.command(s, a)); return true; });
        var gameCmd = getCommand("game");   // round 11: save and load a character with its inventory, leave the game and come back
        if (gameCmd != null) gameCmd.setExecutor((s, c, l, a) -> { s.sendMessage(s instanceof Player p ? saves.command(p, a) : "Players only."); return true; });
        var packCmd = getCommand("pack");
        if (packCmd != null) packCmd.setExecutor((s, c, l, a) -> { s.sendMessage(s instanceof Player p ? packCommand(p, a) : "Players only."); return true; });
        saveDefaultConfig();
        for (Player p : getServer().getOnlinePlayers()) Items.ensurePipBoy(p);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(battle, this);
        storage = new Storage(this);
        getServer().getPluginManager().registerEvents(storage, this);
        getServer().getScheduler().runTaskTimer(this, hud::tick, 20L, 20L);
        getServer().getScheduler().runTaskTimer(this, this::saveAll, 1200L, 1200L);   // every minute
    }

    @Override
    public void onDisable() { battle.stopAll(); anomalies.clearAllFigures(); saveAll(); }

    /** /sounds: Fallout 2's sounds (the player's own private pack) or Minecraft's, a man's or a woman's body, a test, the list. */
    String soundsCommand(Player p, String[] a) {
        Character c = character(p);
        String arg = a.length == 0 ? "" : a[0].toLowerCase(java.util.Locale.ROOT);
        switch (arg) {
            case "fallout2" -> { c.fallout2Sounds = true; Sfx.to(p, "combat.start");
                return "Fallout 2's sounds (they need the Fallout 2 sound pack in your resource packs; silence means it is missing: /sounds minecraft)."; }
            case "minecraft" -> { c.fallout2Sounds = false; Sfx.f2Loaded.remove(p.getUniqueId()); Sfx.to(p, "combat.start"); return "Minecraft's own sounds."; }
            case "female", "male" -> { c.female = arg.equals("female"); Sfx.to(p, Sfx.body("hurt", c.female)); return "Your body's sounds: a " + (c.female ? "woman" : "man") + "'s."; }
            case "test" -> {
                if (a.length < 2 || !Sfx.ROWS.containsKey(a[1])) return "/sounds test <key>: /sounds list";
                Sfx.to(p, a[1]);
                return a[1] + ": " + Sfx.row(a[1]).what() + " (" + Sfx.name(p, a[1]) + ")";
            }
            case "list" -> { return "Sounds: " + String.join(", ", Sfx.ROWS.keySet()); }
            case "narrator" -> {
                if (a.length > 1) c.narrator = !a[1].equalsIgnoreCase("off");
                if (Narrator.on(p) && a.length > 1) Narrator.say(p.getLocation(), "crit.groin", c.female);
                return "The narrator's comments on crits and fumbles: " + (Narrator.on(p) ? "ON (heard with the Falloutak pack, read by everyone)" : "OFF")
                        + ". /sounds narrator on | off";
            }
            default -> { return "Your sounds: " + (Sfx.f2(p) ? "Fallout 2's" : "Minecraft's") + ", a " + (c.female ? "woman" : "man") + "'s body, the narrator "
                    + (Narrator.on(p) ? "on" : "off") + ". /sounds fallout2 | minecraft | female | male | narrator on|off | test <key> | list"; }
        }
    }

    static final UUID PACK_ID = UUID.nameUUIDFromBytes("falloutak:public-pack".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    static final UUID F2_PACK_ID = UUID.nameUUIDFromBytes("falloutak:fallout2-pack".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    /** The packs in the config (the public one from the GitHub release, the private Fallout 2 one if set), sent on joining. */
    void sendPacks(Player p) {
        sendPack(p, PACK_ID, getConfig().getString("resource-pack.url", ""), getConfig().getString("resource-pack.sha1", ""),
                getConfig().getString("resource-pack.prompt", ""), getConfig().getBoolean("resource-pack.required", false));
        sendPack(p, F2_PACK_ID, getConfig().getString("fallout2-pack.url", ""), getConfig().getString("fallout2-pack.sha1", ""), "", false);
    }

    private void sendPack(Player p, UUID id, String url, String sha1, String prompt, boolean required) {
        if (url == null || url.isBlank()) return;
        byte[] hash = sha1 == null || !sha1.matches("[0-9a-fA-F]{40}") ? null : java.util.HexFormat.of().parseHex(sha1);
        Battle.cosmetic(() -> p.addResourcePack(id, url, hash, prompt == null || prompt.isBlank() ? null : prompt, required));
    }

    @EventHandler
    public void onPackStatus(org.bukkit.event.player.PlayerResourcePackStatusEvent e) {
        if (PACK_ID.equals(e.getID())) {   // the public pack: the Pip-Boy pictures in the sheet
            if (e.getStatus() == org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) Icons.packLoaded.add(e.getPlayer().getUniqueId());
            else if (e.getStatus() != org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.ACCEPTED
                    && e.getStatus() != org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.DOWNLOADED) Icons.packLoaded.remove(e.getPlayer().getUniqueId());
            return;
        }
        if (!F2_PACK_ID.equals(e.getID())) return;
        if (e.getStatus() == org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) Sfx.f2Loaded.add(e.getPlayer().getUniqueId());
        else if (e.getStatus() != org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.ACCEPTED
                && e.getStatus() != org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.DOWNLOADED) Sfx.f2Loaded.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuitSounds(org.bukkit.event.player.PlayerQuitEvent e) {
        Sfx.f2Loaded.remove(e.getPlayer().getUniqueId());
        Icons.packLoaded.remove(e.getPlayer().getUniqueId());
    }

    /** /pack on|off|auto: the Pip-Boy pictures for a player who has the Falloutak pack by hand (or does not want them). */
    String packCommand(Player p, String[] a) {
        Character c = character(p);
        String arg = a.length == 0 ? "" : a[0].toLowerCase(java.util.Locale.ROOT);
        switch (arg) {
            case "on" -> c.pictures = true;
            case "off" -> c.pictures = false;
            case "auto" -> c.pictures = null;
            default -> { }
        }
        return "The Falloutak pack (Pip-Boy pictures, free sounds): " + (Icons.hasPack(p, c) ? "ON" : "OFF")
                + (c.pictures == null ? " (automatic: the server's pack " + (Icons.packLoaded.contains(p.getUniqueId()) ? "is" : "is not") + " loaded)" : " (your choice)")
                + ". /pack on | off | auto";
    }

    /** The player's character: loaded from the store on first use, else the sample. */
    Character character(Player p) {
        return characters.computeIfAbsent(p.getUniqueId(), u -> {
            Character c = store.load(u);
            if (c != null) c.migrate();
            return c != null ? c : Character.sample(p.getName());
        });
    }

    void save(UUID u) {
        Character c = characters.get(u);
        if (c == null) return;
        String npc = roster != null ? roster.editing.get(u) : null;
        try {
            if (npc != null) {   // a GM editing an NPC: the NPC to its file, his own character to his
                store.saveNpc(npc, c);
                Character mine = roster.own.get(u);
                if (mine != null) store.save(u, mine);
            } else store.save(u, c);
        } catch (java.io.IOException e) { getLogger().warning("could not save " + u + ": " + e); }
    }

    void saveAll() {
        for (UUID u : characters.keySet()) save(u);
        for (String n : npcs.keySet()) saveNpc(n);
    }

    /** An NPC's statblock (cached), or null. */
    Character npc(String name) {
        Character c = npcs.get(name);
        if (c == null) { c = store.loadNpc(name); if (c != null) { c.migrate(); npcs.put(name, c); } }
        return c;
    }

    void saveNpc(String name) {
        Character c = npcs.get(name);
        if (c == null) return;
        try { store.saveNpc(name, c); } catch (java.io.IOException e) { getLogger().warning("could not save NPC " + name + ": " + e); }
    }

    /**
     * A figurant for testing actions on NPCs (Marek 11-16: *"I will need some figurant to test this on"*): a villager that stands
     * still and cannot be hurt by Minecraft, carrying its statblock's name; its statblock (a character of the template) is kept as
     * /npc <name>. An existing statblock of that name is reused.
     */
    String spawnFigurant(org.bukkit.Location at, String name, String template) {
        Character c = npc(name);
        boolean fresh = c == null;
        if (fresh) { c = Character.blank(name, template); npcs.put(name, c); saveNpc(name); }
        at.getWorld().spawn(at, org.bukkit.entity.Villager.class, v -> {
            v.setAI(false);
            v.setInvulnerable(true);
            v.setSilent(true);
            Battle.cosmetic(() -> v.setPersistent(true));
            Battle.cosmetic(() -> v.setRemoveWhenFarAway(false));
            v.customName(Component.text(name, NamedTextColor.GOLD));
            v.setCustomNameVisible(true);
            v.getPersistentDataContainer().set(Items.NPC, org.bukkit.persistence.PersistentDataType.STRING, name);
        });
        return "Figurant " + name + " (" + c.template + (fresh ? ", a new statblock" : ", its statblock reused") + ") stands here: right-click it.";
    }

    @org.bukkit.event.EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        if (battle.inBattle(e.getPlayer())) battle.stop(e.getPlayer());   // a battle map sends him back first
        if (MapView.viewing(e.getPlayer())) MapView.back(e.getPlayer());
        roster.done(e.getPlayer().getUniqueId());
        save(e.getPlayer().getUniqueId());
        characters.remove(e.getPlayer().getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        Character c = character(p);
        String a = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (a) {
            case "" -> SheetView.open(p, c, SheetView.Page.OVERVIEW);
            case "actions", "a" -> Menus.actions(p);
            case "create" -> {
                Character draft = Character.sample(p.getName());
                draft.name = p.getName();
                draft.group = c.group;          // the premium templates he may pick follow him (03b §2b)
                draft.unlocked.addAll(c.unlocked);
                Forms.create(p, this, draft);
            }
            case "spend" -> Forms.spend(p, this, c);
            case "hud" -> toggle(p, hud.showAll, "the sidebar shows all nine stats", "the sidebar shows only the stats past stage 0");
            case "demo" -> toggle(p, hud.demo, "demo clock ON: one second = one game hour (rads +25 an hour here)", "demo clock OFF");
            case "hit" -> p.sendMessage(Component.text(Hud.hit(c, args.length > 1 ? Integer.parseInt(args[1]) : 10,
                    args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : ""), NamedTextColor.RED));
            case "firstaid", "bandage" -> p.sendMessage(Component.text(battle.firstAid(p, c), NamedTextColor.GREEN));
            case "repair" -> p.sendMessage(Component.text(battle.repair(p, c), NamedTextColor.GREEN));
            case "fit" -> say(p, Accessories.command(p, c, args));   // 05d §5: the accessories
            case "trap" -> say(p, battle.setTrap(p, c, args));   // 05d §7c: set a trap in a fight
            case "pavise" -> say(p, battle.pavise(p, c));   // 05c §11: the standing shield set down, or taken up
            case "legs" -> {
                c.brokenLegs = Math.max(0, Math.min(2, args.length > 1 ? Integer.parseInt(args[1]) : 0));
                p.sendMessage(Component.text("broken legs: " + c.brokenLegs + " (movement " + Txt.f(Rules.stepAp(0, c.brokenLegs, c.has("Brittle Bones")))
                        + " AP a block standing)", NamedTextColor.YELLOW));
            }
            case "battle" -> { if (battle.inBattle(p)) battle.stop(p); else battle.start(p, c); }
            case "rounds" -> say(p, battle.inBattle(p) ? battle.roundsOff(p, c) : battle.roundsOn(p, c));   // round 11: the player's own rounds
            case "implant", "implants" -> say(p, Part3.implant(c, args));
            case "surgery" -> say(p, Part3.surgery(c, args, rnd));
            case "transplant", "graft" -> say(p, Part3.transplant(c, args, rnd));
            case "disease", "diseases" -> say(p, Diseases.command(c, args, rnd));
            case "condition", "conditions" -> say(p, Conditions.command(c, args));
            case "revive" -> say(p, Part3.revive(c, args, rnd));
            case "dose", "chem", "chems" -> say(p, Part3.dose(c, args, rnd));
            case "mutate" -> say(p, Part3.mutate(c, args, rnd));
            case "limb" -> say(p, Part3.limb(c, args));
            case "wait" -> {   // the stages of a chem, to test: N game hours at once
                int h = args.length > 1 ? Math.max(1, Math.min(24 * 400, Integer.parseInt(args[1]))) : 1;
                for (int i = 0; i < h; i++) Hud.hour(c);
                say(p, h + " game hour(s) passed: " + Part3.timed(c));
            }
            case "reset" -> {
                battle.stop(p);
                hud.showAll.remove(p.getUniqueId());
                characters.put(p.getUniqueId(), Character.sample(p.getName()));
                hud.demo.remove(p.getUniqueId());
                p.sendMessage(Component.text("The sample character is back.", NamedTextColor.GREEN));
            }
            default -> {
                for (SheetView.Page pg : SheetView.Page.values())
                    if (pg.name().toLowerCase(Locale.ROOT).startsWith(a)) { SheetView.open(p, c, pg); return true; }
                p.sendMessage(Component.text("The Pip-Boy (right click) opens your actions; right-click someone to act on him; the GM: /gm. "
                        + "/sheet [overview|skills|weapons|traits|body|survival|combat|achievements] · actions · create · spend · hud · demo · hit <n> [crit|stab] · firstaid · repair · trap [name] · pavise · legs <0-2> · battle · reset"
                        + " · implant · surgery · revive · dose · wait <hours> · mutate <rads> · limb", NamedTextColor.GRAY));
            }
        }
        return true;
    }

    final Random rnd = new Random();

    private static void say(Player p, String text) {
        for (String line : text.split("\n")) p.sendMessage(Component.text(line, line.startsWith("!") ? NamedTextColor.RED : NamedTextColor.YELLOW));
    }

    /**
     * Falls (03b §2d, RULED 11-16: *"your idea sounds better than MC, accepted"*): 1d6 per 3 blocks beyond the first 3, an Athletics
     * roll (d100 at most Athletics) halves it, the catfolk takes half; Minecraft's own fall damage is cancelled for a character.
     */
    @EventHandler(ignoreCancelled = true)
    public void onFall(org.bukkit.event.entity.EntityDamageEvent e) {
        if (e.getCause() != org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL || !(e.getEntity() instanceof Player p)) return;
        Character c = characters.get(p.getUniqueId());
        if (c == null) return;
        e.setCancelled(true);
        double blocks = p.getFallDistance() > 0 ? p.getFallDistance() : e.getDamage() + 3;
        String r = Part3.fall(c, blocks, rnd);
        if (r != null) p.sendMessage(Component.text(r, NamedTextColor.RED));
    }

    /**
     * Drowning (03b §3c, RULED 11-18): Oxygen replaces Minecraft's own air for a character, as falls replaced its fall damage; under
     * water Oxygen falls 1 a round of GAME time (a fight's rounds, the GM's clock), never real time.
     */
    @EventHandler(ignoreCancelled = true)
    public void onDrown(org.bukkit.event.entity.EntityDamageEvent e) {
        if (e.getCause() != org.bukkit.event.entity.EntityDamageEvent.DamageCause.DROWNING || !(e.getEntity() instanceof Player p)) return;
        if (characters.containsKey(p.getUniqueId())) e.setCancelled(true);
    }

    private void toggle(Player p, Set<UUID> set, String on, String off) {
        if (!set.add(p.getUniqueId())) set.remove(p.getUniqueId());
        if (set.contains(p.getUniqueId())) {
            if (set == hud.demo) character(p).locationRads = 25;
            p.sendMessage(Component.text(on, NamedTextColor.GREEN));
        } else {
            if (set == hud.demo) character(p).locationRads = 0;
            p.sendMessage(Component.text(off, NamedTextColor.GRAY));
        }
    }

    // ------------------------------------------------------------------ the menus' ways in (Marek 11-16: no commands to remember)
    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) { Items.ensurePipBoy(e.getPlayer()); sendPacks(e.getPlayer()); }

    /** The Pip-Boy: right click = your actions, sneak + right click = your sheet; a chem in hand: right click takes it. Not in battle. */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOW)
    public void onUse(org.bukkit.event.player.PlayerInteractEvent e) {
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND || (battle.inBattle(e.getPlayer()) && !battle.inEnv(e.getPlayer()))) return;
        var act = e.getAction();
        if (act != org.bukkit.event.block.Action.RIGHT_CLICK_AIR && act != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        String id = Items.id(e.getItem());
        if (id == null) return;
        e.setCancelled(true);   // no bottle filling, no placing
        Player p = e.getPlayer();
        if (id.equals("pipboy")) { if (p.isSneaking()) SheetView.open(p, character(p), SheetView.Page.OVERVIEW); else Menus.actions(p); }
        else if (id.startsWith("chem:")) {
            Character c = character(p);
            if (c.dead || c.inArrest()) return;
            if (Items.take(p, id)) say(p, Part3.dose(c, new String[]{"dose", id.substring(5)}, rnd));
        } else if (Meds.name(id) != null && Meds.selfUse(p).containsKey(Meds.name(id))) {   // 04i: a medicine taken by oneself
            Character c = character(p);
            if (c.dead || c.inArrest()) return;
            String mn = Meds.name(id);
            if (Meds.take(p, mn, 1) == 1) say(p, Meds.useSelf(c, mn, rnd));
        } else if (id.equals("gear:Small gas mask")) {   // an active item (Marek 11-18): right click puts it on or takes it off
            Menus.smallMask(p, character(p));
        } else if (id.equals("gear:Stimpak")) {
            Character c = character(p);
            if (!c.dead && !c.inArrest() && c.hp < c.hpMax()) say(p, Menus.stimpak(p, c, "you"));
        } else say(p, Items.title(id) + ": " + Items.describe(id).replace("\n", "; "));
    }

    /** Right-clicking another player or an NPC figurant (empty hand or the Pip-Boy): the actions on him. */
    @EventHandler
    public void onPlayerClick(org.bukkit.event.player.PlayerInteractEntityEvent e) {
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND || (battle.inBattle(e.getPlayer()) && !battle.inEnv(e.getPlayer()))) return;
        Menus.Who w = Menus.whoOf(e.getRightClicked());
        if (w == null) return;
        e.setCancelled(true);   // no trading with a figurant
        var held = e.getPlayer().getInventory().getItemInMainHand();
        if (!held.getType().isAir() && !"pipboy".equals(Items.id(held))) return;
        Menus.on(e.getPlayer(), w);
    }
}
