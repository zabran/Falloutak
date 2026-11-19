package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The character survives a save and a load whole. */
class StoreTest {
    @Test
    void aCharacterRoundTrips() throws Exception {
        Character c = Character.sample("Tester");
        c.hp = 7; c.xp = 1234; c.bleeding = 3; c.brokenLegs = 1; c.base[2] = 9;
        c.survival.put(Rules.Stat.PAIN, 40.0);
        c.tags.add("Ghoul-touched");
        Path tmp = Files.createTempDirectory("fo-store");
        Store s = new Store(tmp);
        UUID u = UUID.randomUUID();
        s.save(u, c);
        Character d = s.load(u);
        assertNotNull(d);
        assertEquals(Store.toJson(c), Store.toJson(d));   // every field, the effects, traits, perks and survival too
        assertEquals(9, d.base[2]);
        assertEquals(40.0, d.survival.get(Rules.Stat.PAIN));
        assertEquals(c.perks.size(), d.perks.size());
        assertNull(s.load(UUID.randomUUID()));
    }
}
