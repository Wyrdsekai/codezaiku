package org.codezaiku;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;

/** The config file holds API keys: owner-only, whatever the umask was when it was made. */
class ConfigPrivateTest {

    static String perms(Path p) throws Exception { return PosixFilePermissions.toString(Files.getPosixFilePermissions(p)); }

    @Test
    void theFileBecomes600AndOurOwnFolder700(@TempDir Path home) throws Exception {
        Assumptions.assumeTrue(Files.getFileStore(home).supportsFileAttributeView("posix"));
        Path dir = Files.createDirectory(home.resolve(".codezaiku"));
        Path cfg = Files.writeString(dir.resolve("config"), "api_key = secret\n");
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxr-x"));
        Files.setPosixFilePermissions(cfg, PosixFilePermissions.fromString("rw-rw-r--"));
        Config.keepPrivate(cfg);
        assertEquals("rw-------", perms(cfg));
        assertEquals("rwx------", perms(dir));
    }

    @Test
    void aConfigSomewhereElseIsTightenedAndItsFolderLeftAlone(@TempDir Path home) throws Exception {
        Assumptions.assumeTrue(Files.getFileStore(home).supportsFileAttributeView("posix"));
        Path dir = Files.createDirectory(home.resolve("project"));
        Path cfg = Files.writeString(dir.resolve("cz.conf"), "api_key = secret\n");
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxr-x"));
        Files.setPosixFilePermissions(cfg, PosixFilePermissions.fromString("rw-r--r--"));
        Config.keepPrivate(cfg);
        assertEquals("rw-------", perms(cfg));
        assertEquals("rwxrwxr-x", perms(dir), "a folder that is not ours keeps its permissions");
        Config.keepPrivate(dir.resolve("missing"));   // nothing to do, nothing thrown
    }

    @Test
    void aDriveAddressPastedWithItsV1IsTheSameDrive() {
        for (String pasted : new String[]{"http://localhost:8200", "http://localhost:8200/", "http://localhost:8200/v1", "http://localhost:8200/v1/",
                "http://localhost:8200/v1/chat/completions", "http://localhost:8200/v1/models", " http://localhost:8200/v1 "})
            assertEquals("http://localhost:8200", Config.driveBase(pasted), pasted);
        assertEquals("https://openrouter.ai/api", Config.driveBase("https://openrouter.ai/api/v1"));
        assertEquals("https://host/v1beta", Config.driveBase("https://host/v1beta"), "only a whole /v1 segment");
        assertEquals("off", Config.driveBase("off"));
        assertNull(Config.driveBase(null));
    }
}
