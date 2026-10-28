package org.codezaiku.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WebAccountsTest {
    @Test
    void peopleAreKeptHashedAndASessionIsSignedAndDated(@TempDir Path tmp) throws Exception {
        WebAccounts a = new WebAccounts(tmp.resolve("web"));
        assertFalse(a.any());
        a.put("ada", "correct horse", WebAccounts.VIEWER); a.put("Bob", "battery staple", WebAccounts.OWNER);
        assertEquals(2, a.people().size()); assertTrue(a.any());
        String kept = Files.readString(a.file()); assertFalse(kept.contains("correct horse"), "no password in the file"); assertTrue(kept.contains("ada\tviewer\t"));
        assertNull(a.signIn("ada", "wrong")); assertEquals("viewer", a.signIn("ADA", "correct horse").role()); assertTrue(a.signIn("bob", "battery staple").owner());
        String s = a.session(a.signIn("ada", "correct horse"), 30);
        assertEquals("ada", a.check(s).name()); assertNull(a.check(s + "x")); assertNull(a.check("ada.owner.9999999999.deadbeef"), "a forged role is refused");
        WebAccounts again = new WebAccounts(tmp.resolve("web")); assertEquals("ada", again.check(s).name(), "the secret is kept, so a session survives a restart");
        assertThrows(IllegalArgumentException.class, () -> a.put("x y", "longenough", "viewer")); assertThrows(IllegalArgumentException.class, () -> a.put("eve", "short", "viewer")); assertThrows(IllegalArgumentException.class, () -> a.put("eve", "longenough", "king"));
        assertTrue(a.remove("ada")); assertFalse(a.remove("ada")); assertEquals(1, a.people().size());
    }
}
