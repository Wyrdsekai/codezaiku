package org.codezaiku.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Export→import round-trips the store; import never silently clobbers; zip-slip is refused. */
class SessionArchiveTest {

    private static Path store(Path tmp) throws IOException {
        Path s = tmp.resolve("store");
        Files.createDirectories(s.resolve("journal"));
        Files.writeString(s.resolve("2026-08-28-a-session.md"), "# a session\n");
        Files.writeString(s.resolve("2026-08-28-a-session.log.jsonl"), "{\"role\":\"user\"}\n");
        Files.writeString(s.resolve("journal/step-1.pre"), "old content");
        return s;
    }

    @Test
    void roundTripRestoresEveryFile(@TempDir Path tmp) throws Exception {
        Path zip = tmp.resolve("out.zip");
        var names = SessionArchive.export(store(tmp), zip, "test");
        assertEquals(3, names.size());

        Path dest = tmp.resolve("other-machine");
        var r = SessionArchive.importInto(zip, dest, false);
        assertEquals(3, r.restored().size());
        assertTrue(r.skipped().isEmpty());
        assertEquals("# a session\n", Files.readString(dest.resolve("2026-08-28-a-session.md")));
        assertEquals("old content", Files.readString(dest.resolve("journal/step-1.pre")));
    }

    @Test
    void importSkipsExistingFilesUnlessForced(@TempDir Path tmp) throws Exception {
        Path zip = tmp.resolve("out.zip");
        SessionArchive.export(store(tmp), zip, "test");
        Path dest = tmp.resolve("dest");
        Files.createDirectories(dest);
        Files.writeString(dest.resolve("2026-08-28-a-session.md"), "MINE, EDITED HERE");

        var r = SessionArchive.importInto(zip, dest, false);
        assertEquals(1, r.skipped().size(), "the local file is not clobbered");
        assertEquals("MINE, EDITED HERE", Files.readString(dest.resolve("2026-08-28-a-session.md")));

        var forced = SessionArchive.importInto(zip, dest, true);
        assertTrue(forced.skipped().isEmpty());
        assertEquals("# a session\n", Files.readString(dest.resolve("2026-08-28-a-session.md")));
    }

    @Test
    void anEntryEscapingTheStoreIsRefused(@TempDir Path tmp) throws Exception {
        Path evil = tmp.resolve("evil.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(evil))) {
            z.putNextEntry(new ZipEntry("../outside.md"));
            z.write("escape".getBytes());
            z.closeEntry();
        }
        Path dest = tmp.resolve("dest");
        assertThrows(IOException.class, () -> SessionArchive.importInto(evil, dest, false));
        assertFalse(Files.exists(tmp.resolve("outside.md")));
    }

    @Test
    void exportOfAMissingStoreSaysSoInsteadOfWritingAnEmptyArchive(@TempDir Path tmp) {
        assertThrows(IOException.class, () ->
                SessionArchive.export(tmp.resolve("nope"), tmp.resolve("o.zip"), "test"));
    }
}
