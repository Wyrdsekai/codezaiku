package org.codezaiku.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A run's installs go to the project's own environment. On 2026-09-29 two runs put eleven packages into the user's base conda Python
 * with the first pip on the path; the login shell's profile (conda init) puts that Python in front again, so the environment is switched
 * on inside each command.
 */
class ProjectEnvTest {

    @TempDir Path home;
    @TempDir Path project;
    String realHome;

    @BeforeEach void before() { realHome = System.getProperty("user.home"); System.setProperty("user.home", home.toString()); ProjectEnv.forget(); ProjectEnv.forceOn = true; }
    @AfterEach void after() { System.setProperty("user.home", realHome); ProjectEnv.forget(); ProjectEnv.forceOn = false; }

    @Test
    void theEnvironmentIsTheProjectsOwnAndOutsideIt() throws Exception {
        Path dir = ProjectEnv.dirFor(project);
        assertTrue(dir.startsWith(home), "under CodeZaiku's home: " + dir);
        assertFalse(dir.startsWith(project.toRealPath()), "never inside the project, so the run's changed files stay the project's own");
        assertEquals(dir, ProjectEnv.dirFor(project), "the same project, the same environment");
        Path other = Files.createDirectories(home.resolve("other/" + project.getFileName()));
        assertNotEquals(dir, ProjectEnv.dirFor(other), "another project with the same name gets its own");
    }

    @Test
    void aShellCommandUsesThePythonAndInstallFoldersOfTheProject() throws Exception {
        String prelude = ProjectEnv.prelude(project);
        Path py = ProjectEnv.dirFor(project).resolve("py");
        assumeTrue(ProjectEnv.pythonBin(py) != null, "this machine has no python3 with venv");
        var args = new ObjectMapper().createObjectNode().put("command",
                "python3 -c 'import sys; print(\"PREFIX=\" + sys.prefix)'; pip --version; echo \"NPM=$NPM_CONFIG_PREFIX\"; echo \"GOBIN=$GOBIN\"");
        String out = new ShellTool(new PathScope(project)).execute(args);
        String dir = ProjectEnv.dirFor(project).toString();
        // macOS puts temp folders under /var, a link to /private/var, and python prints the real path
        Path pyReal = Files.exists(py) ? py.toRealPath() : py;
        assertTrue(out.contains("PREFIX=" + py) || out.contains("PREFIX=" + pyReal), "python is the project's: " + out);
        assertTrue(out.contains(py.toString()), "pip is the project's (its path is in `pip --version`): " + out);
        assertTrue(out.contains("NPM=" + dir + "/npm") && out.contains("GOBIN=" + dir + "/go/bin"), out);
        assertFalse(prelude.isBlank());
    }

    @Test
    void anEnvironmentWhoseProjectIsGoneIsRemovedAndNoOtherFolderIsTouched() throws Exception {
        Path envs = Files.createDirectories(home.resolve(".codezaiku/envs"));
        Path gone = Files.createDirectories(envs.resolve("gone-000000000000/py/bin"));
        Files.writeString(envs.resolve("gone-000000000000/project.txt"), home.resolve("no-such-project").toString());
        Path kept = Files.createDirectories(envs.resolve("kept-111111111111"));
        Files.writeString(kept.resolve("project.txt"), project.toString());
        Path stranger = Files.createDirectories(envs.resolve("something-else"));
        assertEquals(1, ProjectEnv.prune(envs));
        assertFalse(Files.exists(gone.getParent().getParent()), "the environment of a project that is gone");
        assertTrue(Files.isDirectory(kept), "the environment of a project that exists");
        assertTrue(Files.isDirectory(stranger), "a folder that names no project is never removed");
    }
}
