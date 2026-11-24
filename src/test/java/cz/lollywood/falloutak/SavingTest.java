package cz.lollywood.falloutak;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Every saved file goes through Store (Marek 11-24: "use helper for all"): written whole, an unreadable one kept aside. */
class SavingTest {
    @TempDir Path dir;

    @Test
    void writtenWholeAndAnUnreadableFileIsKeptAside() throws Exception {
        Path f = dir.resolve("sub").resolve("production.json");
        assertTrue(Store.save(f, new Production()));
        assertTrue(Files.isRegularFile(f));
        assertFalse(Files.exists(f.resolveSibling("production.json.tmp")), "the temporary file is moved over the old one");
        assertNotNull(Production.load(dir.resolve("sub")));
        Files.writeString(f, "{ not json");
        Production p = Production.load(dir.resolve("sub"));
        assertNotNull(p, "a new one when the old cannot be read");
        try (var s = Files.list(f.getParent())) {
            assertTrue(s.anyMatch(x -> x.getFileName().toString().startsWith("production.bad-")), "the unreadable file kept aside");
        }
        assertNull(Store.read(dir.resolve("none.json"), Production.class));
    }
}
