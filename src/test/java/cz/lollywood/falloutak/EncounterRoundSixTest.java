package cz.lollywood.falloutak;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `09d` round 6 (Marek 11-24, verbatim in `09e` §9 round 3): going round with Survival +30; the toll first (people never attack first
 * unless the party struck them first: a feud); the enemy's own check against the party's size and noise; realistic stealth.
 */
class EncounterRoundSixTest extends MockGame {

    @BeforeEach
    void map() { Arena.use = world; Travel.use(WorldMapTest.fixture()); Travel.here("Roadside"); Travel.weatherSet = false; Travel.weather = "clear"; }

    @AfterEach
    void noMap() {
        Arena.use = null; Arena.current = null; Travel.map = null; Travel.file = null; Travel.scene.clear(); Travel.feuds.clear(); Travel.talkKind = "";
        Talks.dice = null; Loot.clear();
    }

    private Travel.Met scene(String key, String type, int count, boolean seen, boolean glimpsed) {
        Travel.Met m = new Travel.Met(new WorldMap.Encounter(Travel.map.kinds.get(key), type, count), seen, glimpsed);
        Travel.scene.add(m);
        Travel.sceneTerrain = "field";
        return m;
    }

    @Test
    void peopleAskATollFirstUnlessTheyAreAtFeud() {
        WorldMap.Kind raiders = Travel.map.kinds.get("raiders:pond"), boar = Travel.map.kinds.get("creature:boar");
        assertTrue(raiders.people());
        assertFalse(boar.people());
        assertEquals("talk", Travel.tollFirst(new WorldMap.Encounter(raiders, "fight", 6)).type(), "the toll first");
        assertEquals("fight", Travel.tollFirst(new WorldMap.Encounter(boar, "fight", 1)).type(), "a monster attacks first");
        assertEquals("talk", Travel.tollFirst(new WorldMap.Encounter(raiders, "talk", 6)).type());
        Travel.feuds.add("raiders:pond");
        assertEquals("fight", Travel.tollFirst(new WorldMap.Encounter(raiders, "fight", 6)).type(), "struck first: they shoot on sight");
        assertEquals("trade", Travel.tollFirst(new WorldMap.Encounter(Travel.map.kinds.get("traders"), "fight", 3)).type());
    }

    @Test
    void anAmbushOnPeopleStartsAFeudAndTheGmCanClearIt() {
        PlayerMock a = player();
        a.setOp(true);
        scene("raiders:pond", "fight", 2, true, true);
        String r = Travel.battleMap(plugin, a, Arena.Mode.AMBUSH);
        assertFalse(r.startsWith("!"), r);
        assertTrue(Travel.feuds.contains("raiders:pond"));
        plugin.battle.stop(a);
        assertTrue(Travel.command(plugin, a, new String[]{"feud"}).contains("raiders:pond"));
        Travel.command(plugin, a, new String[]{"feud", "clear"});
        assertTrue(Travel.feuds.isEmpty());
    }

    @Test
    void aGroupOnlyGlimpsedCanBeGoneRoundOrFacedButNotAmbushed() {
        PlayerMock a = player();
        scene("creature:wolves", "fight", 6, false, true);
        assertFalse(Travel.ambushed());
        assertFalse(Travel.allSeen());
        assertTrue(Travel.glimpsedOnly());
        assertTrue(Travel.battleMap(plugin, a, Arena.Mode.AMBUSH).startsWith("! nothing hostile seen in time"));
        assertTrue(Travel.sceneText().contains("GLIMPSED"));
        assertFalse(Travel.goRound(plugin, a).startsWith("!"), "going round needs only the glimpse");
        assertTrue(Travel.scene.isEmpty());
        // faced: they act first
        scene("creature:wolves", "fight", 6, false, true);
        String r = Travel.battleMap(plugin, a, Arena.Mode.AMBUSHED);
        assertFalse(r.startsWith("!"), r);
        plugin.battle.stop(a);
        // not even glimpsed: an ambush on the party
        Travel.scene.clear();
        scene("creature:wolves", "fight", 6, false, false);
        assertTrue(Travel.ambushed());
        assertTrue(Travel.goRound(plugin, a).startsWith("!"));
    }

    @Test
    void theAvoidCheckIsThirtyEasierAndTheEnemySeesABigPartyBetter() {
        assertEquals(30, Travel.AVOID_BONUS);
        assertEquals(0, Travel.partyTerm(4));
        assertEquals(-10, Travel.partyTerm(6), "two heads over four: easier for them to notice");
        assertEquals(-30, Travel.partyTerm(20), "at most -30");
        // realistic stealth: the ambush predators near the top, the coursing hunters lower; their senses exported
        WorldMap.Kind tigers = Travel.map.kinds.get("creature:tigers"), wolves = Travel.map.kinds.get("creature:wolves"),
                dogs = Travel.map.kinds.get("creature:dogs");
        assertTrue(tigers.sneak() > wolves.sneak() && wolves.sneak() > dogs.sneak());
        assertTrue(wolves.notice() >= 80, "a wolf's nose");
    }
}
