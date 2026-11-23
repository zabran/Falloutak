package cz.lollywood.falloutak;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.67.0, `09b` round 11 (Marek 11-23, verbatim): *"The genes should stay, just as implant."* · *"Gun jams - agreed."* · raiders jamming
 * *"yes, 100%. And their guns should definitely be quite used."* · *"Fallout 2's pounds - covert it to kg."* · the overload's three steps ·
 * *"the plugin doesn't add up what you carry." - it should - please add that function. It should work with inventory.* · saving the
 * inventory and the character · the Black Rain *"should turn every player to the battle mode"* · rounds on, and off *"if there are no
 * enemies close"*.
 */
class RoundElevenTest extends MockGame {
    void use(PlayerMock p, int slot, Action a) {
        Battle.State s = plugin.battle.state(p);
        if (s != null) { s.lastLeft = 0; s.lastRight = 0; }
        p.getInventory().setHeldItemSlot(slot);
        server.getPluginManager().callEvent(new PlayerInteractEvent(p, a, p.getInventory().getItemInMainHand(), null, BlockFace.SELF, EquipmentSlot.HAND));
    }

    PlayerMock facingSouth() {
        PlayerMock p = player();
        Location l = p.getLocation();
        l.setYaw(0);
        l.setPitch(0);
        p.teleport(l);
        return p;
    }

    void raidersTurn(Battle.State s) {
        for (int i = 0; i < 100 && s.enemyTurn && !s.over; i++) server.getScheduler().performTicks(20);
    }

    // ------------------------------------------------------------------ the turned ghoul's genes
    @Test
    void aTurnedGhoulKeepsItsGenesBesideTheGhoulGene() {
        // round 12 (Marek 11-23: "It is supposed to be a mutation."): the template and its genes stay, the Ghoul gene fills its own slot
        Character c = Part3Test.of("Survivor", 5, 5, 5, 5, 5, 6, 5);
        double p0 = c.baseTpl().poisonRes();
        String s = c.turnGhoul();
        assertTrue(s.contains("the template (Survivor) and its genes stay"), s);
        assertEquals("Survivor", c.baseTpl().name());
        assertTrue(c.keptGenes.isEmpty(), "no more kept genes: they never left");
        assertTrue(c.baseTpl().genes().stream().anyMatch(g -> g.name().equals("Broad HLA")));
        assertEquals("Ghoul", c.necrosisGene().name());
        assertEquals(p0 + 0.10, c.baseTpl().poisonRes(), 1e-9, "the ghoul's +10 % on the Survivor's own");
        Character h = Part3Test.of("Human", 5, 5, 5, 5, 5, 6, 5);
        h.turnGhoul();
        assertEquals(java.util.List.of("Ghoul"), h.baseTpl().genes().stream().map(Data.Gene::name).toList(), "a human: the Ghoul gene alone");
    }

    // ------------------------------------------------------------------ jams
    @Test
    void jamsFollowJaggedAlliance2UnderNewVegasThreshold() {
        assertEquals(-1, Combat.jamAt(0.75, 3, () -> 1, 0), "never at 75 % and above");
        assertEquals(-1, Combat.jamAt(1.0, 1, () -> 1, 0));
        assertEquals(0, Combat.jamAt(0.0, 1, () -> 1, 0), "the worst dice at 0 %");
        assertEquals(-1, Combat.jamAt(0.0, 1, () -> 100, 0), "the best dice never");
        assertEquals(0, Combat.jamAt(1.0, 1, () -> 1, 2), "the magnet's 1-2 of d6 on top");
        assertEquals(55, Combat.clearJam(50));
        Random r = new Random(7);
        int n = 200_000, at50 = 0, at25 = 0, at0 = 0;
        for (int i = 0; i < n; i++) {
            if (Combat.jamAt(0.5, 1, () -> r.nextInt(100) + 1, 0) >= 0) at50++;
            if (Combat.jamAt(0.25, 1, () -> r.nextInt(100) + 1, 0) >= 0) at25++;
            if (Combat.jamAt(0.0, 1, () -> r.nextInt(100) + 1, 0) >= 0) at0++;
        }
        assertEquals(0.0325, at50 / (double) n, 0.003, "25·26/20000");
        assertEquals(0.1275, at25 / (double) n, 0.004, "50·51/20000");
        assertEquals(0.285, at0 / (double) n, 0.005, "75·76/20000");
    }

    @Test
    void aJammedGunIsClearedByFiringAgain() {
        PlayerMock p = facingSouth();
        Character c = plugin.character(p);
        p.performCommand("sheet battle");
        Battle.State s = plugin.battle.state(p);
        s.health.put(Battle.SMG.name, 2.0);    // condition ~2 % (1 point = broken): near the worst
        plugin.battle.d100 = () -> 1;           // the worst dice: it jams on the first round
        said(p);
        server.getScheduler().performTicks(2);
        use(p, 0, Action.LEFT_CLICK_AIR);
        String shot = said(p);
        assertTrue(shot.contains("JAMMED"), shot);
        assertTrue(s.jammed.contains(Battle.SMG.name));
        s.ap = s.apAtAnchor = c.apMax();
        int ap = s.ap;
        use(p, 0, Action.LEFT_CLICK_AIR);       // firing again is the clearing check (d100 1 ≤ 30 + Crafting / 2)
        assertFalse(s.jammed.contains(Battle.SMG.name), "cleared");
        assertTrue(s.ap < ap, "the attack's AP spent on it");
        if (plugin.battle.inBattle(p)) p.performCommand("sheet battle");
    }

    @Test
    void theRaidersGunsAreUsedAndTheyJamToo() {
        PlayerMock p = facingSouth();
        p.performCommand("sheet battle");
        Battle.State s = plugin.battle.state(p);
        for (Battle.Target t : s.targets.values()) {
            double cond = t.gunHealth / Battle.SMG.maxHealth;
            assertTrue(cond >= 0.25 - 1e-9 && cond <= 0.6 + 1e-9, "quite used: " + cond);
            t.gunHealth = 2;   // near the worst (1 point = broken)
        }
        plugin.battle.d100 = () -> 1;
        said(p);
        use(p, 8, Action.RIGHT_CLICK_AIR);      // end the turn
        raidersTurn(s);
        String turn = said(p);
        assertTrue(turn.contains("gun JAMS"), turn);
        assertTrue(s.targets.values().stream().anyMatch(t -> t.jammed || turn.contains(t.name + " clears")));
        if (plugin.battle.inBattle(p)) p.performCommand("sheet battle");
    }

    // ------------------------------------------------------------------ the load
    @Test
    void theCapacityIsFallout2sPoundsInKgWithThePerksAndTheArtifacts() {
        Character c = Part3Test.of("Human", 5, 5, 5, 5, 5, 6, 5);
        double ath = c.skills()[Rules.skillIndex("Athletics")];
        assertEquals((25 + 125 + ath) * 0.5, c.capacityKg(), 1e-9, "round 12: × 0.5");
        c.traits.add(new Character.Feat("Small Frame", "", ""));
        assertEquals((25 + 75 + ath) * 0.5, c.capacityKg(), 1e-9, "Small Frame: 15 × S");
        c.traits.clear();
        c.perks.add(new Character.Feat("Strong Back", "", ""));
        assertEquals((25 + 125 + ath + 50) * 0.5, c.capacityKg(), 1e-9);
        c.belt(java.util.Set.of("Feather stone"), false);
        assertEquals((25 + 125 + ath + 50) * 0.5 + 15, c.capacityKg(), 1e-9, "the feather stone +15 kg");
    }

    @Test
    void overloadHalvesTheSpeedThenTheApThenLeavesNone() {
        Character c = Part3Test.of("Human", 5, 5, 5, 5, 5, 6, 5);
        int ap = c.apMax();
        double cap = c.capacityKg();
        c.setLoad(cap * 0.9);
        assertEquals(0, c.loadTier());
        assertTrue(c.effects.stream().noneMatch(e -> e.slot().equals("load")));
        c.setLoad(cap * 1.2);
        assertEquals(1, c.loadTier());
        assertEquals(ap, c.apMax());
        assertEquals(2, c.loadMoveFactor(), "half speed");
        assertEquals(0.5, c.other("food_need"), 1e-9);
        assertEquals(0.5, c.other("oxygen_need"), 1e-9);
        c.setLoad(cap * 1.7);
        assertEquals(2, c.loadTier());
        assertEquals(ap / 2, c.apMax(), "the AP halved");
        assertEquals(1.0, c.other("water_need"), 1e-9);
        c.setLoad(cap * 2.5);
        assertEquals(3, c.loadTier());
        assertEquals(0, c.apMax(), "no AP, as paralysed");
        assertEquals(0, Rules.loadTier(1.2, 1, true), "Strong Back: nothing to +25 %");
        assertEquals(1, Rules.loadTier(1.3, 1, true));
        Character h = Part3Test.of("Human", 5, 5, 5, 5, 5, 6, 5);
        h.traits.add(new Character.Feat("Hoarder", "", ""));
        int s0 = h.attr(Rules.S, false);
        h.setLoad(0);
        assertEquals(s0 - 1, h.attr(Rules.S, false), "the Hoarder below half: −1");
        h.setLoad(h.capacityKg() * 0.8);
        assertEquals(s0, h.attr(Rules.S, false));
    }

    @Test
    void itemsWeighByTheirKind() {
        assertEquals(1, Items.kg(Items.thingId("Metal", 1, 1, "scrap")), 1e-9);
        assertEquals(1, Items.kg(Items.thingId("Water", 2, 1, "boiled water")), 1e-9);
        assertEquals(0.5, Items.kg(Items.thingId("Food", 1, 1, "a portion")), 1e-9);
        Production.Design shiv = Production.design("Knives", "Shiv");
        assertEquals(1, Items.kg(Items.madeId(shiv)), 1e-9, "its parts' kg");
        Data.Armour robes = Data.ARMOUR.get("Robes");
        assertEquals(robes.weightKg() * robes.shares().get("Chest"), Items.kg("armour:Robes|Chest"), 1e-9);
        assertEquals(3.0, Items.kg("gear:Air bottle"), 1e-9);
        assertEquals(0, Items.kg("pipboy"), 1e-9);
        assertEquals(0, Items.weight(new ItemStack[]{new ItemStack(Material.DIRT, 64)}), 1e-9, "Minecraft's own weigh nothing");
    }

    @Test
    void theHudWeighsTheInventoryAndSlowsTheOverloaded() {
        PlayerMock p = player(0.5, 0.5, false);
        Character c = plugin.character(p);
        plugin.hud.tick();
        assertEquals(0.2f, p.getWalkSpeed(), 1e-6);
        Items.give(p, "gear:Air bottle", 2);
        plugin.hud.tick();
        assertEquals(6 + c.wornKg(), c.carriedKg, 1e-6);
        int need = (int) Math.ceil(c.capacityKg() * 1.2);
        Items.give(p, Items.thingId("Metal", 1, 1, "scrap"), need);
        plugin.hud.tick();
        assertEquals(1, c.loadTier());
        assertEquals(0.1f, p.getWalkSpeed(), 1e-6, "half speed");
        Items.give(p, Items.thingId("Metal", 1, 1, "scrap"), need * 2);
        plugin.hud.tick();
        assertEquals(3, c.loadTier());
        assertEquals(0f, p.getWalkSpeed(), 1e-6, "no moving");
        p.getInventory().clear();
        plugin.hud.tick();
        assertEquals(0.2f, p.getWalkSpeed(), 1e-6);
    }

    // ------------------------------------------------------------------ rounds by choice; the rain's
    @Test
    void aPlayerTurnsRoundsOnAndOffAndRunsAwayFromAFight() {
        PlayerMock p = player(0.5, 0.5, false);
        Character c = plugin.character(p);
        assertTrue(plugin.battle.roundsOn(p, c).startsWith("Rounds ON"));
        assertTrue(plugin.battle.inEnv(p));
        plugin.battle.endEnvRound(p);
        assertTrue(plugin.battle.inBattle(p), "kept: his own choice, though nothing hurts");
        assertEquals("Rounds OFF.", plugin.battle.roundsOff(p, c));
        assertFalse(plugin.battle.inBattle(p));
        // the demo: not while a raider is near; far enough, he gets away
        PlayerMock q = facingSouth();
        Character qc = plugin.character(q);
        q.performCommand("sheet battle");
        assertTrue(plugin.battle.roundsOff(q, qc).startsWith("No:"));
        Battle.State s = plugin.battle.state(q);
        for (UUID u : s.dummies) { Entity e = server.getEntity(u); e.teleport(e.getLocation().add(0, 0, 60)); }
        assertTrue(plugin.battle.roundsOff(q, qc).startsWith("You got away"));
        assertFalse(plugin.battle.inBattle(q));
    }

    @Test
    void theRainsWarningPutsEveryoneInRoundsAndReleasesTheRoofed() {
        PlayerMock gm = player(0.5, 0.5, true), roofed = player(20.5, 20.5, false);
        world.getBlockAt(20, 8, 20).setType(Material.STONE);
        Character c = plugin.character(roofed);
        Menus.rainCommand(gm, new String[]{"start", "3", "5"});
        assertTrue(plugin.battle.inBattle(roofed));
        assertEquals(Anomalies.RAIN_WHY, plugin.battle.state(roofed).holdWhy);
        plugin.battle.endEnvRound(roofed);
        assertTrue(plugin.battle.inBattle(roofed), "held through the warning");
        plugin.zones.rounds(Anomalies.WARN_ROUNDS);
        for (int i = 0; i < 6; i++) plugin.anomalies.rainTick();
        assertEquals(2, plugin.anomalies.rainPhase);
        assertFalse(c.rainExposed);
        plugin.battle.endEnvRound(roofed);
        assertFalse(plugin.battle.inBattle(roofed), "under a roof: out of danger when it breaks");
    }

    // ------------------------------------------------------------------ saved games
    @Test
    void aGameIsSavedAndLoadedAndLeftAndReturnedTo() {
        PlayerMock p = player(0.5, 0.5, false);
        Character c = plugin.character(p);
        c.hp = 7;
        Items.give(p, "gear:Stimpak", 3);
        assertTrue(plugin.saves.command(p, new String[]{"save", "before"}).startsWith("Saved as \"before\""));
        plugin.character(p).hp = 1;
        p.getInventory().clear();
        assertTrue(plugin.saves.command(p, new String[]{"load", "before"}).startsWith("Loaded"));
        assertEquals(7, plugin.character(p).hp);
        assertEquals(3, Items.carried(p).getOrDefault("gear:Stimpak", 0));
        assertTrue(plugin.saves.command(p, new String[]{"list"}).contains("before"));
        // off: the game kept, the Minecraft inventory (none yet) back, the world leaves him alone
        assertTrue(plugin.saves.command(p, new String[]{"off"}).startsWith("OUT OF THE GAME"));
        assertTrue(plugin.character(p).away);
        assertTrue(Items.carried(p).isEmpty());
        p.getInventory().addItem(new ItemStack(Material.DIRT, 5));
        plugin.hud.tick();   // nothing happens to him
        assertTrue(plugin.saves.command(p, new String[]{"on"}).startsWith("BACK IN THE GAME"));
        assertFalse(plugin.character(p).away);
        assertEquals(3, Items.carried(p).getOrDefault("gear:Stimpak", 0));
        assertEquals(7, plugin.character(p).hp);
        assertFalse(p.getInventory().contains(Material.DIRT), "his Minecraft things kept aside");
        assertTrue(plugin.saves.command(p, new String[]{"off"}).startsWith("OUT OF THE GAME"));
        assertTrue(p.getInventory().contains(Material.DIRT), "and given back");
    }
}
