package org.codezaiku;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The suite runs with a home of its own: the test task names a folder under the build folder as user.home, so a test that changes
 * a setting without a folder of its own changes a file there and never the settings of the person who runs the suite.
 */
class SuiteHomeTest {

    @Test
    void theTestTaskGivesEveryTestAHomeUnderTheBuildFolder() {
        assumeTrue(System.getenv("CODEZAIKU_CTX") != null, "under the Gradle test task");
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        assertTrue(home.endsWith(Path.of("build", "test-home")), "user.home is the suite's own folder: " + home);
        Path settings = Config.userConfigPath().toAbsolutePath().normalize();
        assertTrue(settings.startsWith(home), "and the settings file is in it: " + settings);
        String login = System.getProperty("user.name", "");
        assertFalse(!login.isBlank() && settings.equals(Path.of("/home", login, ".codezaiku", "config")), "never the person's own settings file");
    }
}
