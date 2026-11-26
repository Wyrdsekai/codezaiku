package org.codezaiku;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `codezaiku config`: a word that is an option is never stored as a value (`config set <key> --verbose` kept the word "--verbose" as
 * the setting), `--file` takes one path and nothing more, a value that really begins with dashes comes after `--`, a refused file is
 * named and nothing of it is shown, and the other forms do not drop extra words without a word. As ResearchZosho's `settings set`
 * does since its 0.8.4, except which line of a file is read: the last with text, so the two lines `researchzosho reader token … >
 * file` writes are read as they are. Every home is the test's own.
 */
class ConfigSetTest {

    @TempDir Path tmp;
    final String realHome = System.getProperty("user.home");
    final PrintStream realOut = System.out, realErr = System.err;
    final ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();

    @BeforeEach void home() {
        System.setProperty("user.home", tmp.resolve("home").toString());
        Config.invalidate();
        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(err, true));
    }

    @AfterEach void back() {
        System.setOut(realOut);
        System.setErr(realErr);
        System.setProperty("user.home", realHome);
        Config.invalidate();
    }

    int config(String... a) { out.reset(); err.reset(); int rc = FamiliarMain.configCommand(a); Config.invalidate(); return rc; }

    static final String KEY = "CODEZAIKU_TEST_SETTING";

    @Test
    void aWordThatIsAnOptionIsNeverStored() {
        assertEquals(2, config("set", KEY, "--verbose"));
        assertNull(Config.get(KEY), "nothing stored");
        assertTrue(err.toString().contains("config set does not take --verbose") && err.toString().contains("usage"), err.toString());
        assertEquals(2, config("set", KEY, "a-value", "--file"));
        assertNull(Config.get(KEY));
        assertEquals(2, config("set", KEY, "--file"));
        assertTrue(err.toString().contains("--file needs the path of a file"), err.toString());
        assertEquals(2, config("set", KEY, "--file", tmp.resolve("x").toString(), "more"));
        assertTrue(err.toString().contains("--file takes one path and nothing after it"), err.toString());
        assertEquals(2, config("set", "--file", tmp.resolve("x").toString()), "a key is never a word that begins with a dash");
        assertNull(Config.get(KEY));
        assertFalse(Files.exists(Config.userConfigPath()), "no settings file was even made");
    }

    @Test
    void aValueThatBeginsWithDashesComesAfterTwoDashes() {
        assertEquals(0, config("set", KEY, "--", "--a-value-with-dashes"));
        assertEquals("--a-value-with-dashes", Config.get(KEY));
        assertEquals(0, config("set", KEY, "two", "words"));
        assertEquals("two words", Config.get(KEY), "the words are joined, not the first kept and the rest dropped");
    }

    @Test
    void aRefusedFileIsNamedAndNothingOfItIsShown() throws Exception {
        Path missing = tmp.resolve("missing.txt"), folder = tmp.resolve("a-folder"), empty = tmp.resolve("empty.txt"), notText = tmp.resolve("binary.bin");
        Files.createDirectories(folder);
        Files.writeString(empty, "\n  \n");
        Files.write(notText, new byte[]{(byte) 0xff, (byte) 0xfe, 0, (byte) 0xc3, 0x28});
        for (Path p : new Path[]{missing, folder, empty, notText}) {
            assertEquals(1, config("set", KEY, "--file", p.toString()), p.toString());
            assertTrue(err.toString().contains(p.toString()) && err.toString().contains("Nothing was changed"), err.toString());
            assertNull(Config.get(KEY));
        }
        Path two = tmp.resolve("signin.txt");
        Files.writeString(two, "bearer token for did:key:local-codezaiku (shown once; only its hash is kept):\nthe-sign-in-itself\n");
        assertEquals(0, config("set", KEY, "--file", two.toString()));
        assertEquals("the-sign-in-itself", Config.get(KEY), "the last line with text");
    }

    @Test
    void theOtherFormsDoNotDropWordsWithoutAWord() {
        assertEquals(0, config("set", KEY, "kept"));
        assertEquals(2, config("unset", KEY, "more"));
        assertEquals("kept", Config.get(KEY), "unset with an extra word does nothing");
        assertEquals(2, config("get", KEY, "more"));
        assertEquals(2, config("path", "more"));
        assertEquals(2, config("list", "more"));
        assertEquals(0, config("unset", KEY));
        assertNull(Config.get(KEY));
    }
}
