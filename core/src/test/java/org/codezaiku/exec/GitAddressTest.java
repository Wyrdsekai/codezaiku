package org.codezaiku.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The addresses CodeZaiku pushes to and fetches from, and the ones it refuses before git ever sees them. */
class GitAddressTest {

    private static GitAddress ok(String raw) {
        GitAddress.Read r = GitAddress.read(raw);
        assertNotNull(r.address(), raw + " refused: " + r.refused());
        return r.address();
    }

    private static String refused(String raw) {
        GitAddress.Read r = GitAddress.read(raw);
        assertNull(r.address(), raw + " was taken");
        return r.refused();
    }

    @Test
    void theFormsGitListsForAServerAreTakenAndNameOneRepository() {
        assertEquals("github.com/me/proj", ok("https://github.com/me/proj.git").repository());
        assertEquals("github.com/me/proj", ok("git@github.com:me/proj.git").repository());
        assertEquals("github.com/me/proj", ok("ssh://git@github.com/me/proj").repository());
        assertEquals("github.com/me/proj", ok("ssh://git@ssh.github.com:443/me/proj.git").repository());
        assertEquals("codeberg.org/me/proj", ok("https://codeberg.org/me/proj/").repository());
        assertEquals("127.0.0.2/stand-in", ok("http://127.0.0.2:18761/stand-in.git").repository());
        assertEquals("example.org/srv/repo", ok("git://example.org/srv/repo.git").repository());
        assertEquals("me/proj", ok("git@github.com:me/proj.git").gitHub().toString());
        assertNull(ok("https://gitlab.com/me/proj.git").gitHub());
        assertNull(ok("https://github.com/me/proj/sub.git").gitHub(), "owner/name only");
    }

    @Test
    void aPasswordInTheAddressIsNeverShown() {
        GitAddress a = ok("https://me:made-up-secret@example.org/me/proj.git");
        assertEquals("https://me:***@example.org/me/proj.git", a.shown());
        assertFalse(GitAddress.shownRaw("file://me:made-up-secret@x/y").contains("made-up-secret"));
    }

    @Test
    void foldersHelpersControlCharactersAndDashesAreRefused() {
        assertTrue(refused("/home/someone/repo.git").contains("folder"));
        assertTrue(refused("../other").contains("folder"));
        assertTrue(refused("file:///tmp/x.git").contains("folder"));
        assertTrue(refused("C:/repos/x").contains("folder"));
        assertTrue(refused("ext::sh -c touch% /tmp/pwned").contains("helper") || refused("ext::sh -c touch% /tmp/pwned").contains("space"));
        assertTrue(refused("ext::sh").contains("helper"));
        assertTrue(refused("fd::3").contains("helper"));
        assertTrue(refused("https://example.org/a\nhttps://evil.example/b").contains("control"));
        assertTrue(refused("https://example.org/a\rb").contains("control"));
        assertTrue(refused("https://example.org/a\u001b[2Jb").contains("control"));
        assertTrue(refused("ssh://-oProxyCommand=touch%20x/repo").contains("host"));
        assertTrue(refused("-oProxyCommand=x:repo").contains("dash"));
        assertTrue(refused("ssh://-user@host/repo").contains("user"));
        assertTrue(refused("host:-repo").contains("dash"));
        assertTrue(refused("ftp://example.org/x.git").contains("https"));
        assertTrue(refused("https://example.org").contains("no repository"));
    }

    @Test
    void theSettingListsRepositoriesWithAStarForTheOwnerOrTheName() {
        GitAddress a = ok("git@github.com:Me/Proj.git");
        assertTrue(a.listedBy("github.com/me/proj"));
        assertTrue(a.listedBy("https://github.com/me/proj.git"));
        assertTrue(a.listedBy("github.com/me/*"));
        assertTrue(a.listedBy("github.com/*/*"));
        assertFalse(a.listedBy("github.com/me"));
        assertFalse(a.listedBy("*/me/proj"), "a host is always named");
        assertFalse(a.listedBy("gitlab.com/me/proj"));
        assertFalse(a.listedBy("github.com/me/proj-other"));
    }
}
