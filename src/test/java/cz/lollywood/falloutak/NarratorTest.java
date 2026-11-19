package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The narrator's comments on crits and fumbles (Marek 11-19: the documentary narrator speaks them; our own text, tools/narrator.py). */
class NarratorTest extends MockGame {
    private static final int[] AVG = {5, 5, 5, 5, 5, 5, 5};

    @Test
    void everyLineIsVoicedInBothFormsAndEveryKindHasLines() throws Exception {
        String sounds = Files.readString(Path.of("pack/assets/falloutak/sounds.json"));
        for (Narrator.Line l : Narrator.LINES) {
            for (boolean f : l.gendered() ? new boolean[]{false, true} : new boolean[]{false}) {
                String key = Narrator.key(l, f);
                assertTrue(sounds.contains("\"" + key + "\""), "not in the pack: " + key);
                String file = l.id().replace('.', '_') + (key.endsWith(".f") ? "_f" : "") + ".ogg";
                assertTrue(Files.exists(Path.of("pack/assets/falloutak/sounds/narrator/" + file)), file);
            }
            assertFalse(Narrator.text(l, true).contains("{"), l.id());
        }
        // every kind the pickers can give has lines, for every body part and every crit and failure degree
        Set<String> kinds = new TreeSet<>();
        for (Rules.Part part : Rules.Part.values())
            for (int d = 1; d <= 6; d++)
                for (int st = 1; st <= 10; st += 3) {
                    int[] a = {st, st, st, st, st, st, st};
                    for (String kind : List.of("", "blunt", "electric")) kinds.add(Narrator.critKind(part, Combat.critEffects(part, d, a, kind)));
                }
        for (int d = 1; d <= 5; d++) for (boolean fire : new boolean[]{false, true}) kinds.add(Narrator.fumbleKind(Combat.critFailure(d, fire)));
        for (String k : kinds) assertTrue(Narrator.BY_KIND.containsKey(k), "no lines for " + k);
        assertTrue(kinds.containsAll(List.of("crit.dies", "crit.heart", "crit.groin", "crit.leg", "crit.arm", "fumble.fire", "fumble.ammo")), kinds.toString());
    }

    @Test
    void theMostStrikingEffectPicksTheLine() {
        assertEquals("crit.groin", Narrator.critKind(Rules.Part.GROIN, Combat.critEffects(Rules.Part.GROIN, 1, AVG)));
        assertEquals("crit.head", Narrator.critKind(Rules.Part.HEAD, new Combat.CritEffects(false, false, false, false, false, false, false, false, false)));
        assertEquals("crit.bypass", Narrator.critKind(Rules.Part.TORSO, new Combat.CritEffects(true, false, false, false, false, false, false, false, false)));
        assertEquals("crit.dies", Narrator.critKind(Rules.Part.HEAD, new Combat.CritEffects(true, true, true, false, false, true, true, false, true)));
        assertEquals("crit.heart", Narrator.critKind(Rules.Part.TORSO, Combat.critEffects(Rules.Part.TORSO, 6, AVG, "blunt")));
        assertEquals("crit.leg", Narrator.critKind(Rules.Part.LEFT_LEG, new Combat.CritEffects(false, true, false, false, true, false, false, false, false)));
        assertEquals("fumble.fire", Narrator.fumbleKind(Combat.critFailure(5, true)));
        assertEquals("fumble.self", Narrator.fumbleKind(Combat.critFailure(5, false)));
        assertEquals("fumble.broken", Narrator.fumbleKind(Combat.critFailure(4)));
        assertEquals("fumble.drop", Narrator.fumbleKind(Combat.critFailure(3)));
        assertEquals("fumble.ammo", Narrator.fumbleKind(Combat.critFailure(2)));
        assertEquals("fumble.plain", Narrator.fumbleKind(Combat.critFailure(1)));
        // a woman's form, and never the same line twice in a row
        Narrator.Line l = Narrator.BY_KIND.get("fumble.self").get(1);
        assertEquals("She is now, technically, her own worst enemy.", Narrator.text(l, true));
        assertEquals("narrator.f.self.2.f", Narrator.key(l, true));
        String last = null;
        for (int i = 0; i < 50; i++) {
            String id = Narrator.pick("crit.groin").id();
            assertNotEquals(last, id);
            last = id;
        }
    }

    @Test
    void theHitPlayerHearsYouTheOthersHearHimOrHer() {
        PlayerMock victim = player();
        PlayerMock friend = player(3, 3, false);
        plugin.packCommand(victim, new String[]{"on"});
        plugin.packCommand(friend, new String[]{"on"});
        Map<String, String> heard = new HashMap<>();
        Narrator.recorder = (k, who) -> heard.put(who.getName(), k);
        try {
            Combat.CritEffects leg = new Combat.CritEffects(false, false, false, false, true, false, false, false, false);
            Narrator.crit(victim.getLocation(), Rules.Part.LEFT_LEG, leg, true, victim);
            assertTrue(heard.get(victim.getName()).startsWith("narrator.you.leg."), heard.toString());
            assertTrue(heard.get(friend.getName()).matches("narrator\\.leg\\.\\d(\\.f)?"), heard.toString());
            // a kind with no second-person lines: both hear the third-person one
            heard.clear();
            Narrator.crit(victim.getLocation(), Rules.Part.TORSO, new Combat.CritEffects(false, false, false, false, false, false, false, true, false), false, victim);
            assertEquals(heard.get(friend.getName()), heard.get(victim.getName()));
            for (Narrator.Line l : Narrator.LINES)
                if (l.kind().startsWith("you.")) assertTrue(Narrator.BY_KIND.containsKey("crit." + l.kind().substring(4)), l.id());
        } finally {
            Narrator.recorder = null;
            plugin.packCommand(victim, new String[]{"auto"});
            plugin.packCommand(friend, new String[]{"auto"});
        }
    }

    @Test
    void readByEveryoneNearHeardWithThePackSilencedOnRequest() {
        PlayerMock p = player();
        PlayerMock far = player(200, 200, false);
        List<String> heard = new ArrayList<>();
        Narrator.recorder = (k, who) -> heard.add(k + "@" + who.getName());
        try {
            said(p);
            String text = Narrator.say(p.getLocation(), "fumble.fall", false);
            server.getScheduler().performTicks(10);
            assertTrue(said(p).contains("The narrator: " + text), text);
            assertTrue(heard.isEmpty(), "no pack, no voice: " + heard);
            assertEquals("", said(far));                                            // too far away
            plugin.packCommand(p, new String[]{"on"});
            plugin.character(p).female = true;
            String hers = Narrator.say(p.getLocation(), "fumble.self", true);
            assertTrue(hers.contains("she") || hers.startsWith("She"), hers);
            assertEquals(1, heard.size());
            assertTrue(heard.get(0).matches("narrator\\.f\\.self\\.\\d\\.f@" + p.getName()), heard.toString());
            server.getScheduler().performTicks(10);                                 // that line arrives a moment later
            assertTrue(said(p).contains("The narrator: " + hers));
            assertTrue(plugin.soundsCommand(p, new String[]{"narrator", "off"}).contains("OFF"));
            heard.clear();
            said(p);
            Narrator.say(p.getLocation(), "crit.groin", false);
            server.getScheduler().performTicks(10);
            assertTrue(heard.isEmpty());
            assertFalse(said(p).contains("The narrator"));
            assertTrue(plugin.soundsCommand(p, new String[]{"narrator", "on"}).contains("ON"));
            plugin.packCommand(p, new String[]{"auto"});
        } finally {
            Narrator.recorder = null;
        }
    }
}
