package org.codezaiku;

import org.codezaiku.research.LibraryBridge;
import org.codezaiku.research.LibraryBridge.Door;
import org.codezaiku.research.LibraryBridge.Seen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * doctor's lines on the research library, with ResearchZosho installed on this computer or not, the library here or on another
 * computer. Until 0.3.21 doctor said "not installed" whenever ResearchZosho was not installed here, though the chat used a library
 * on another computer. And `config set` takes a value from a file, so a sign-in need not pass through the shell's history.
 */
class DoctorLibraryTest {

    static final String HERE = "http://127.0.0.1:4649", THERE = "http://192.168.1.20:4649";
    static final Seen IN = new Seen(Door.IN, ""), NONE = new Seen(Door.NONE, "nothing answers at " + THERE),
            REFUSED = new Seen(Door.REFUSED, "An anonymous patron may not read this library; the person who keeps it edits catalog/patrons.md.");

    static String text(List<Doctor.Check> cs) {
        StringBuilder b = new StringBuilder();
        for (Doctor.Check c : cs) b.append(c.ok() ? "ok " : "-- ").append(c.name()).append(" | ").append(c.detail()).append(" | ").append(c.fix()).append('\n');
        return b.toString();
    }

    @Test
    void nothingInstalledNothingSetNothingAnsweringIsNotInstalledAsBefore() {
        String t = text(Doctor.libraryChecks(null, null, new Seen(Door.NONE, ""), false, HERE, false, false));
        assertEquals("-- researchzosho (the research library) | not installed; research keeps its findings in the research memory only | codezaiku install researchzosho\n", t);
    }

    @Test
    void aLibraryOnAnotherComputerIsSaidWithNothingInstalledHere() {
        String in = text(Doctor.libraryChecks(null, null, IN, true, THERE, true, true));
        assertEquals("ok research library at " + THERE + " (on another computer): lets CodeZaiku in |  | \n", in);

        String refused = text(Doctor.libraryChecks(null, null, REFUSED, true, THERE, true, false));
        assertTrue(refused.startsWith("-- research library at " + THERE + " (on another computer): answers, but does not let CodeZaiku in | CodeZaiku has not been given a sign-in for it"), refused);
        assertTrue(refused.contains("the library says: An anonymous patron may not read this library; the person who keeps it edits catalog/patrons.md |"), refused);
        assertTrue(refused.contains("on the computer that keeps the library, run researchzosho reader allow did:key:local-codezaiku write codezaiku")
                && refused.contains("--file codezaiku-signin.txt"), refused);

        String none = text(Doctor.libraryChecks(null, null, NONE, true, THERE, true, false));
        assertTrue(none.startsWith("-- research library at " + THERE + " (on another computer): nothing answers |  | check that the computer that keeps it is on and its library is running there"), none);
        assertFalse((in + refused + none).contains("not installed"));
    }

    @Test
    void anInstallHereKeepsItsUpdateNoticeAndItsNotRunningLine() {
        String t = text(Doctor.libraryChecks("0.8.3", "0.8.4", new Seen(Door.NONE, ""), false, HERE, false, false));
        assertTrue(t.startsWith("-- researchzosho 0.8.3, installed here | 0.8.4 is available | codezaiku update now"), t);
        assertTrue(t.contains("research library at " + HERE + ": nothing answers |  | the library is installed but not running: `researchzosho service install`"), t);
        String refused = text(Doctor.libraryChecks("0.8.3", null, REFUSED, false, HERE, false, true));
        assertTrue(refused.contains("does not accept the sign-in CodeZaiku has for it") && refused.endsWith("| codezaiku install researchzosho --token\n"), refused);
        // installed here, but CodeZaiku is set to use a library elsewhere: this computer's command cannot make its sign-in
        String there = text(Doctor.libraryChecks("0.8.3", null, REFUSED, true, THERE, true, true));
        assertTrue(there.contains("on the computer that keeps the library") && !there.contains("--token\n"), there);
    }

    @Test
    void configSetReadsAValueFromAFileAndDoesNotPrintASecret(@TempDir Path tmp) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString());
        Config.invalidate();
        PrintStream realOut = System.out;
        var out = new ByteArrayOutputStream();
        try {
            Path f = tmp.resolve("codezaiku-signin.txt");
            Files.writeString(f, "bearer token for did:key:local-codezaiku (shown once; only its hash is kept):\nthe-sign-in-itself\n\n");
            System.setOut(new PrintStream(out, true));
            assertEquals(0, FamiliarMain.configCommand(new String[]{"set", "CODEZAIKU_LIBRARIAN_TOKEN", "--file", f.toString()}));
            assertEquals("the-sign-in-itself", Config.get("CODEZAIKU_LIBRARIAN_TOKEN"), "the file's last line that is not empty");
            assertFalse(out.toString().contains("the-sign-in-itself"), "the value is not printed back: " + out);
            assertTrue(out.toString().startsWith("CODEZAIKU_LIBRARIAN_TOKEN = (set)"), out.toString());
        } finally {
            System.setOut(realOut);
            System.setProperty("user.home", realHome);
            Config.invalidate();
        }
    }
}
